package org.plukh.mcpproxy.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/** Configuration could not be read, parsed, interpolated or validated. Message is user-facing. */
class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Overrides supplied on the command line. Every field is nullable: null means "not specified",
 * which is distinct from an explicit empty value.
 */
data class CliOverrides(
    val upstreamUrl: String? = null,
    val authToken: String? = null,
    val authHeader: String? = null,
    val identityName: String? = null,
    val identityVersion: String? = null,
    val userAgent: String? = null,
    val logFile: String? = null,
    val logLevel: String? = null,
)

object ConfigLoader {

    private val yaml = Yaml(
        configuration = YamlConfiguration(strictMode = true),
    )

    /**
     * Loads configuration in precedence order: defaults < YAML file < CLI overrides. Environment
     * interpolation runs last, so `${VAR}` works in both the file and CLI arguments.
     *
     * @param path config file, or null to run purely from defaults and CLI overrides
     * @param env environment lookup; injectable so tests do not have to mutate the real environment
     *
     * @throws ConfigException on any read, parse, interpolation or validation failure
     */
    fun load(
        path: Path?,
        overrides: CliOverrides = CliOverrides(),
        env: (String) -> String? = System::getenv,
    ): ProxyConfig {
        val parsed = if (path == null) ProxyConfig() else parse(path)
        return interpolate(merge(parsed, overrides), env).also(::validate)
    }

    private fun parse(path: Path): ProxyConfig {
        // Directories are excluded rather than non-regular files included: a directory is readable,
        // and CONFIG is no longer a clikt path(canBeDir = false), so one would otherwise reach
        // readString and surface as a raw IOException instead of this line. Testing for a *regular*
        // file instead would also reject the perfectly good `mcp-proxy <(render-config)`, /dev/stdin
        // and named pipes, which read fine.
        if (Files.isDirectory(path) || !Files.isReadable(path)) {
            throw ConfigException("Config file not found or not readable: ${path.pathString}")
        }
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            throw ConfigException("Could not read ${path.pathString}: ${e.message}", e)
        }
        // An empty file is a valid "use all defaults" config; kaml would reject it as a null document.
        if (text.isBlank()) return ProxyConfig()
        return try {
            yaml.decodeFromString(ProxyConfig.serializer(), text)
        } catch (e: YamlException) {
            throw ConfigException("${path.pathString}: ${e.message} (line ${e.line}, column ${e.column})", e)
        } catch (e: Exception) {
            throw ConfigException("${path.pathString}: ${e.message}", e)
        }
    }

    private fun merge(config: ProxyConfig, o: CliOverrides) = config.copy(
        identity = config.identity.copy(
            name = o.identityName ?: config.identity.name,
            version = o.identityVersion ?: config.identity.version,
            userAgent = o.userAgent ?: config.identity.userAgent,
        ),
        // The two auth forms are mutually exclusive, so specifying either on the command line
        // replaces the file's choice rather than colliding with it - otherwise passing
        // --auth-token against a config using authHeader fails validation over a config the user
        // never wrote.
        upstream = config.upstream.copy(
            url = o.upstreamUrl ?: config.upstream.url,
            authToken = when {
                o.authToken != null -> o.authToken
                o.authHeader != null -> null
                else -> config.upstream.authToken
            },
            authHeader = when {
                o.authHeader != null -> o.authHeader
                o.authToken != null -> null
                else -> config.upstream.authHeader
            },
        ),
        logging = config.logging.copy(
            file = o.logFile ?: config.logging.file,
            level = o.logLevel ?: config.logging.level,
        ),
    )

    private fun interpolate(config: ProxyConfig, env: (String) -> String?) = config.copy(
        identity = config.identity.copy(
            name = config.identity.name.interpolate("identity.name", env),
            version = config.identity.version.interpolate("identity.version", env),
            title = config.identity.title?.interpolate("identity.title", env),
            userAgent = config.identity.userAgent.interpolate("identity.userAgent", env),
        ),
        upstream = config.upstream.copy(
            url = config.upstream.url?.interpolate("upstream.url", env),
            authToken = config.upstream.authToken?.interpolate("upstream.authToken", env),
            authHeader = config.upstream.authHeader?.interpolate("upstream.authHeader", env),
            // Keys are left alone: an environment variable named by another environment variable is
            // nobody's idea of a readable config.
            command = config.upstream.command.mapIndexed { i, arg ->
                arg.interpolate("upstream.command[$i]", env)
            },
            env = config.upstream.env.mapValues { (key, value) ->
                value.interpolate("upstream.env.$key", env)
            },
            oauth = config.upstream.oauth?.let { oauth ->
                oauth.copy(
                    scopes = oauth.scopes.mapIndexed { i, s -> s.interpolate("upstream.oauth.scopes[$i]", env) },
                    clientName = oauth.clientName?.interpolate("upstream.oauth.clientName", env),
                    clientId = oauth.clientId?.interpolate("upstream.oauth.clientId", env),
                    clientSecret = oauth.clientSecret?.interpolate("upstream.oauth.clientSecret", env),
                    callbackBindHost = oauth.callbackBindHost.interpolate("upstream.oauth.callbackBindHost", env),
                    callbackUrl = oauth.callbackUrl?.interpolate("upstream.oauth.callbackUrl", env),
                    tokenDir = oauth.tokenDir?.interpolate("upstream.oauth.tokenDir", env),
                )
            },
        ),
        logging = config.logging.copy(
            file = config.logging.file?.interpolate("logging.file", env),
        ),
    )

    private fun validate(config: ProxyConfig) {
        val upstream = config.upstream
        when (upstream.transport) {
            UpstreamTransport.HTTP -> validateHttp(upstream)
            UpstreamTransport.STDIO -> validateStdio(upstream)
        }
        if (upstream.requestTimeoutSeconds <= 0) {
            throw ConfigException("upstream.requestTimeoutSeconds must be positive, got ${upstream.requestTimeoutSeconds}")
        }
        if (config.logging.level.uppercase() !in VALID_LOG_LEVELS) {
            throw ConfigException(
                "logging.level must be one of ${VALID_LOG_LEVELS.joinToString(", ")}, got: ${config.logging.level}",
            )
        }
    }

    private fun validateHttp(upstream: UpstreamConfig) {
        if (upstream.url.isNullOrBlank()) {
            throw ConfigException("upstream.url is required (set it in the config file or pass --upstream-url)")
        }
        if (!upstream.url.startsWith("http")) {
            throw ConfigException("upstream.url must be an http(s) URL, got: ${upstream.url}")
        }
        if (upstream.authToken != null && upstream.authHeader != null) {
            throw ConfigException("upstream.authToken and upstream.authHeader are mutually exclusive - set only one")
        }
        if (upstream.oauth != null && (upstream.authToken != null || upstream.authHeader != null)) {
            throw ConfigException(
                "upstream.oauth is mutually exclusive with authToken/authHeader - the proxy either owns the flow or forwards a static credential",
            )
        }
        upstream.authHeader?.let {
            if (!it.contains(':')) {
                throw ConfigException("upstream.authHeader must be \"Name: value\", got: $it")
            }
        }
        if (upstream.command.isNotEmpty()) {
            throw ConfigException("upstream.command is only valid with transport 'stdio'")
        }
        if (upstream.env.isNotEmpty()) {
            throw ConfigException("upstream.env is only valid with transport 'stdio'")
        }
        upstream.oauth?.let(::validateOAuth)
    }

    private fun validateOAuth(oauth: OAuthConfig) {
        if (oauth.clientSecret != null && oauth.clientId == null) {
            throw ConfigException("upstream.oauth.clientSecret requires upstream.oauth.clientId")
        }
        if (oauth.callbackPort !in 0..65535) {
            throw ConfigException("upstream.oauth.callbackPort must be 0..65535, got ${oauth.callbackPort}")
        }
        oauth.callbackUrl?.let { url ->
            if (!url.startsWith("http")) {
                throw ConfigException("upstream.oauth.callbackUrl must be an http(s) URL, got: $url")
            }
            // An advertised URL implies a registration that must stay valid across logins; an
            // ephemeral port changes every run and can never match it.
            if (oauth.callbackPort == 0) {
                throw ConfigException("upstream.oauth.callbackUrl requires a fixed callbackPort (0 binds an ephemeral one)")
            }
        }
        if (oauth.authTimeoutSeconds <= 0 || oauth.interactiveWaitSeconds <= 0) {
            throw ConfigException("upstream.oauth timeouts must be positive")
        }
    }

    /**
     * The HTTP-only settings are rejected rather than ignored: a config that names an authToken the
     * proxy would never send is a security surprise, not a harmless leftover.
     */
    private fun validateStdio(upstream: UpstreamConfig) {
        if (upstream.command.isEmpty()) {
            throw ConfigException("upstream.command is required with transport 'stdio' (the server to spawn)")
        }
        if (upstream.command.any { it.isBlank() }) {
            throw ConfigException("upstream.command must not contain blank arguments: ${upstream.command}")
        }
        HTTP_ONLY_SETTINGS.forEach { (name, value) ->
            if (value(upstream) != null) {
                throw ConfigException("$name is not used with transport 'stdio' - a pipe carries no URL or headers")
            }
        }
    }

    private val HTTP_ONLY_SETTINGS: List<Pair<String, (UpstreamConfig) -> Any?>> = listOf(
        "upstream.url" to { it.url },
        "upstream.authToken" to { it.authToken },
        "upstream.authHeader" to { it.authHeader },
        "upstream.oauth" to { it.oauth },
    )

    private val VALID_LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
}

/**
 * Expands `${VAR}` and `${VAR:-default}` from the environment.
 *
 * Interpolation runs after the YAML parse rather than over the raw text: substituting first would
 * let a token containing `:` or `#` silently break the document.
 */
internal fun String.interpolate(field: String, env: (String) -> String? = System::getenv): String =
    ENV_PATTERN.replace(this) { match ->
        val name = match.groupValues[1]
        // Group 2 is the whole ":-default" clause; present (even if empty) means a default was given.
        val default = match.groups[2]?.let { match.groupValues[3] }
        env(name)
            ?: default
            ?: throw ConfigException("$field references \${$name}, but that environment variable is not set")
    }

private val ENV_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}""")
