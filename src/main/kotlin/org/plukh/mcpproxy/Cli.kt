package org.plukh.mcpproxy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import org.plukh.mcpproxy.check.CheckMode
import org.plukh.mcpproxy.oauth.LoginMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.plukh.mcpproxy.config.CliOverrides
import org.plukh.mcpproxy.config.ConfigException
import org.plukh.mcpproxy.config.ConfigLoader
import org.plukh.mcpproxy.config.ConfigResolver
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.ServerConfigLoader
import org.plukh.mcpproxy.config.UpstreamTransport
import org.plukh.mcpproxy.downstream.StdioEndpoint
import org.plukh.mcpproxy.logging.LoggingSetup
import org.plukh.mcpproxy.server.ServerMode
import org.plukh.mcpproxy.relay.Endpoint
import org.plukh.mcpproxy.relay.IdentityRewriter
import org.plukh.mcpproxy.relay.Relay
import org.plukh.mcpproxy.upstream.StdioUpstreamEndpoint

/** Version from the jar manifest; "dev" when running from a classpath build. */
val APP_VERSION: String = ProxyCommand::class.java.`package`?.implementationVersion ?: "dev"

/**
 * Lazy, and that is load-bearing. Creating the logger eagerly runs kotlin-logging's initialization
 * during this file's class init - which `ProxyCommand.init { versionOption(...) }` triggers - and
 * kotlin-logging announces itself on **stdout**. `--help` and `--version` deliberately run without
 * [Stdio.lockdown], so that banner would land on the real FD1 ahead of the output being piped.
 */
private val log by lazy { KotlinLogging.logger {} }

class ProxyCommand : CliktCommand(name = "mcp-proxy") {

    override fun help(context: Context) =
        "Proxies MCP traffic to an upstream server, replacing the client's identity."

    // Deliberately a string, not clikt's path(): a bare name is resolved from the proxy home rather
    // than the working directory, and `~` needs expanding, neither of which path() does.
    private val configArg by argument("CONFIG")
        .optional()
        .help(
            "Config name, resolved from \$MCP_PROXY_HOME (default ~/.mcp-proxy) with .yaml/.yml tried; " +
                "or a path to a YAML file if it contains a separator. Optional if the upstream is given " +
                "on the command line.",
        )

    private val serve by option("--serve")
        .flag()
        .help("Server mode: CONFIG names a server config listing upstreams to serve on one HTTP listener.")

    private val check by option("--check")
        .flag()
        .help("Audit mode: dump the initialize payload and HTTP headers we would send, then exit.")

    private val loopback by option("--loopback")
        .flag()
        .help("With --check: capture headers as actually received by a local listener (ground truth).")

    private val login by option("--login")
        .flag()
        .help("Run the OAuth authorization flow for this upstream, store the tokens, then exit.")

    private val logout by option("--logout")
        .flag()
        .help("Delete the stored OAuth tokens for this upstream, then exit.")

    private val forgetClient by option("--forget-client")
        .flag()
        .help("With --logout: also delete the client registration for the upstream's authorization server.")

    private val upstreamUrl by option("--upstream-url", metavar = "URL")
        .help("Streamable HTTP endpoint of the upstream MCP server.")

    private val authToken by option("--auth-token", metavar = "TOKEN")
        .help("Sent as 'Authorization: Bearer <token>'.")

    private val authHeader by option("--auth-header", metavar = "NAME: VALUE")
        .help("Verbatim auth header, for non-Bearer schemes.")

    private val identityName by option("--identity-name", metavar = "NAME")
        .help("clientInfo.name reported upstream.")

    private val identityVersion by option("--identity-version", metavar = "VERSION")
        .help("clientInfo.version reported upstream.")

    private val userAgent by option("--user-agent", metavar = "UA")
        .help("HTTP User-Agent for upstream requests.")

    private val logFile by option("--log-file", metavar = "PATH")
        .help(
            "Also write logs to this file, relative to the current directory (a config file's " +
                "logging.file is relative to the config home instead). Logs never go to stdout.",
        )

    private val logLevel by option("--log-level", metavar = "LEVEL")
        .help("TRACE, DEBUG, INFO, WARN, ERROR or OFF.")

    /** Read by `main` after [run] returns; clikt owns its own usage-error exits. */
    var exitCode: Int = ExitCodes.OK
        private set

    init {
        versionOption(APP_VERSION)
    }

    override fun run() {
        if (listOf(check, login, logout, serve).count { it } > 1) {
            throw UsageError("--serve, --check, --login and --logout are mutually exclusive")
        }
        if (forgetClient && !logout) {
            throw UsageError("--forget-client only makes sense with --logout")
        }
        if (serve) {
            if (configArg == null) throw UsageError("--serve needs a server config to name the upstreams to serve")
            // Single-upstream overrides have no meaning when several upstreams are served, and
            // silently applying one to all of them would be worse than refusing.
            val perUpstream = listOf(
                "--upstream-url" to upstreamUrl,
                "--auth-token" to authToken,
                "--auth-header" to authHeader,
                "--identity-name" to identityName,
                "--identity-version" to identityVersion,
                "--user-agent" to userAgent,
            ).filter { it.second != null }.map { it.first }
            if (perUpstream.isNotEmpty()) {
                throw UsageError("${perUpstream.joinToString(", ")} cannot be used with --serve: set them per upstream config")
            }
        }

        val overrides = CliOverrides(
            upstreamUrl = upstreamUrl,
            authToken = authToken,
            authHeader = authHeader,
            identityName = identityName,
            identityVersion = identityVersion,
            userAgent = userAgent,
            // Pinned to the working directory before it can reach the home-relative resolution that
            // config *file* values get: a path typed at a shell prompt means what the shell means by
            // it, and silently writing it under ~/.mcp-proxy would be a small betrayal.
            logFile = logFile?.let { expandUserHome(it).toAbsolutePath().toString() },
            logLevel = logLevel,
        )

        if (serve) {
            exitCode = runServer()
            return
        }

        val home: Path
        val resolved: Path?
        val config = try {
            // Read once and threaded through, so config lookup and the log file cannot disagree
            // about where the home is. Inside the try: a bad MCP_PROXY_HOME is a config error and
            // gets the same one readable line as any other.
            home = proxyHome()
            // Resolution shares the catch below: a missing config and an unparseable one are the
            // same class of failure to the user, and both must be one line on stderr.
            resolved = configArg?.let { ConfigResolver.resolve(it, home) }
            ConfigLoader.load(resolved, overrides)
        } catch (e: ConfigException) {
            System.err.println("mcp-proxy: ${e.message}")
            exitCode = ExitCodes.CONFIG_ERROR
            return
        }

        LoggingSetup.configure(config.logging, home)

        // After configure, so it honours logging.level and reaches logging.file; and in every mode,
        // because "which file did I just audit / log in with" is exactly what multi-candidate lookup
        // takes away. The modes below get the raw argument instead - see their hint messages.
        resolved?.let { log.info { "Using config ${it.toAbsolutePath()}" } }

        exitCode = when {
            check -> CheckMode.run(config, loopback, configArg)
            login -> LoginMode.run(config, configArg)
            logout -> LoginMode.logout(config, forgetClient)
            else -> serveStdio(config)
        }
    }

    /**
     * Server mode reads a different schema, so it branches before the per-upstream config is loaded.
     * Logging is configured exactly once here, from the server file - `LoggingSetup.configure`
     * appends an appender per call, so doing it per upstream would duplicate every line N times.
     */
    private fun runServer(): Int {
        val home: Path
        val resolved: Path
        val serverConfig = try {
            home = proxyHome()
            resolved = ConfigResolver.resolve(configArg!!, home)
            ServerConfigLoader.load(resolved)
        } catch (e: ConfigException) {
            System.err.println("mcp-proxy: ${e.message}")
            return ExitCodes.CONFIG_ERROR
        }

        val logging = serverConfig.logging.copy(
            file = logFile?.let { expandUserHome(it).toAbsolutePath().toString() } ?: serverConfig.logging.file,
            level = logLevel ?: serverConfig.logging.level,
        )
        LoggingSetup.configure(logging, home)
        log.info { "Using server config ${resolved.toAbsolutePath()}" }

        return ServerMode.run(serverConfig.copy(logging = logging), home)
    }

    private fun serveStdio(config: ProxyConfig): Int = runBlocking {
        val stdio = config.upstream.transport == UpstreamTransport.STDIO
        val description =
            if (stdio) "stdio ${config.upstream.command.joinToString(" ")}" else config.upstream.url.toString()
        log.info { "mcp-proxy $APP_VERSION starting, upstream $description" }
        log.info {
            "Presenting identity ${config.identity.name}/${config.identity.version}" +
                // A pipe carries no User-Agent, so naming one here would only be misleading.
                if (stdio) "" else " (User-Agent: ${config.identity.userAgent})"
        }

        // Only the HTTP branch owns clients to dispose of; the child process is reclaimed by the
        // endpoint's own close(), which Relay.shutdown calls.
        var httpUpstream: HttpUpstream? = null
        val upstream: Endpoint = if (stdio) {
            StdioUpstreamEndpoint(command = config.upstream.command, extraEnv = config.upstream.env)
        } else {
            buildHttpUpstream(config, configArg).also { httpUpstream = it }.endpoint
        }

        // Deliberately NOT this runBlocking scope. The stdin read blocks uninterruptibly, so a
        // reader parented here would keep runBlocking waiting forever whenever the session ends
        // for any reason other than stdin EOF - an upstream failure would hang the process instead
        // of exiting with a status.
        val stdioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val downstream = StdioEndpoint(scope = stdioScope)
        val relay = Relay(
            downstream = downstream,
            upstream = upstream,
            identity = IdentityRewriter(config.identity),
        )

        try {
            relay.run()
        } catch (e: Throwable) {
            log.error(e) { "Fatal: could not start" }
            ExitCodes.UPSTREAM_CONNECT_FAILED
        } finally {
            stdioScope.cancel()
            httpUpstream?.close()
        }
    }
}
