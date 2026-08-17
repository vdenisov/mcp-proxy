package org.plukh.mcpproxy.check

import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.Stdio
import org.plukh.mcpproxy.buildHttpUpstream
import org.plukh.mcpproxy.config.DROPPED_CAPABILITIES
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamTransport
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.oauth.AuthRequiredException
import org.plukh.mcpproxy.redactSecret
import org.plukh.mcpproxy.relay.IdentityRewriter
import org.plukh.mcpproxy.upstream.authHeaders

/**
 * The leak self-audit: shows exactly what the proxy would disclose, so an SDK or Ktor upgrade that
 * quietly adds an identifying header gets caught here rather than in a server's logs.
 *
 * Two levels, because they see different things:
 * - default: the headers as they stand after our own plugin, plus the initialize payload;
 * - `--loopback`: the headers a real listener actually receives, which is the only way to observe
 *   what the engine adds below the client pipeline (`Host`, `Accept-Encoding`, `Connection`, …).
 *
 * The report is written to the captured real stdout so it can be redirected to a file, while the
 * stdout lockdown stays in force - library initialisation banners (kotlin-logging prints one) would
 * otherwise land in the middle of an audit report.
 *
 * Exits non-zero when it finds something identifying, so it works as a CI gate and not just a
 * document someone has to read.
 */
object CheckMode {

    /**
     * Header names whose values must never be printed in full. Well-known ones, plus - computed per
     * run - whatever `upstream.authHeader` is set to: that accepts an arbitrary `"Name: value"`, so
     * a fixed list would print secrets like `X-Goog-Api-Key` verbatim into output users paste into
     * issues.
     */
    private val WELL_KNOWN_SECRET_HEADERS =
        setOf("authorization", "proxy-authorization", "cookie", "x-api-key")

    private fun secretHeaders(config: ProxyConfig): Set<String> =
        WELL_KNOWN_SECRET_HEADERS + config.upstream.authHeaders().keys.map { it.lowercase() }

    /** @param configArg the `CONFIG` argument as typed, for `--login` hints in the auth-required verdict */
    fun run(config: ProxyConfig, loopback: Boolean, configArg: String? = null): Int {
        if (config.upstream.transport == UpstreamTransport.STDIO) return runStdio(config, loopback)
        return runHttp(config, loopback, configArg)
    }

    /**
     * The stdio audit is payload-only: a pipe carries no headers, so the `initialize` frame is the
     * entire disclosure surface the proxy controls. The child is deliberately not spawned - the
     * audit is about what we would send, and spawning a server (often `docker run`) as a side effect
     * of an audit command is a surprise nobody asked for.
     */
    private fun runStdio(config: ProxyConfig, loopback: Boolean): Int {
        if (loopback) {
            System.err.println(
                "mcp-proxy: --loopback observes HTTP headers and does not apply to the stdio transport",
            )
            return ExitCodes.CONFIG_ERROR
        }

        val initialize = sampleInitialize(config)
        val leaks = findPayloadLeaks(initialize)

        val out = StringBuilder()
        out.appendLine("mcp-proxy leak audit")
        out.appendLine("=".repeat(72))
        out.appendLine()
        out.appendLine("upstream: stdio, command ${config.upstream.command.joinToString(" ")}")
        out.appendLine()
        out.appendLine("initialize payload sent upstream:")
        out.appendLine(prettyJson.encodeToString(JsonObject.serializer(), initialize))
        out.appendLine()
        out.appendLine("HTTP headers: n/a - a child process is spoken to over a pipe.")
        out.appendLine(
            "Note: the spawned server sets its own User-Agent on the API calls it makes, and that is\n" +
                "out of the proxy's reach. Only the client identity in the handshake is controlled here.",
        )
        out.appendLine()
        out.appendLine(
            if (leaks.isEmpty()) {
                "OK: identity is ${config.identity.name}/${config.identity.version}; " +
                    "nothing identifying found in the handshake."
            } else {
                "WARNING:\n" + leaks.joinToString("\n") { "  - $it" }
            },
        )

        Stdio.protocolOut.write((out.toString().trimEnd() + System.lineSeparator()).toByteArray())
        Stdio.protocolOut.flush()

        return if (leaks.isEmpty()) ExitCodes.OK else ExitCodes.LEAKS_FOUND
    }

    private fun runHttp(config: ProxyConfig, loopback: Boolean, configArg: String?): Int = runBlocking {
        val out = StringBuilder()
        out.appendLine("mcp-proxy leak audit")
        out.appendLine("=".repeat(72))

        val initialize = sampleInitialize(config)
        out.appendLine()
        out.appendLine("initialize payload sent upstream:")
        out.appendLine(prettyJson.encodeToString(JsonObject.serializer(), initialize))

        val result =
            if (loopback) checkLoopback(config, configArg, initialize) else checkUpstream(config, configArg, initialize)

        out.appendLine()
        out.appendLine(if (loopback) "headers as received by a local listener:" else "headers after our header policy:")
        val secrets = secretHeaders(config)
        result.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, values) ->
            out.appendLine("  $name: ${redact(name, values, secrets)}")
        }

        result.upstreamIdentity?.let {
            out.appendLine()
            out.appendLine("upstream responded:")
            out.appendLine("  $it")
        }

        val leaks = findLeaks(result)
        out.appendLine()
        out.appendLine(verdict(config, result, leaks))
        // Report goes to the captured real stdout, not System.out - which the lockdown has
        // repointed at stderr precisely so library banners cannot pollute this stream.
        Stdio.protocolOut.write((out.toString().trimEnd() + System.lineSeparator()).toByteArray())
        Stdio.protocolOut.flush()

        // A reported leak must fail the process: this mode exists to be run in CI after a Ktor or
        // JDK upgrade, and a green exit alongside a WARNING would defeat the point.
        when {
            result.exitCode != ExitCodes.OK -> result.exitCode
            leaks.isNotEmpty() -> ExitCodes.LEAKS_FOUND
            else -> ExitCodes.OK
        }
    }

    private data class CheckResult(
        val headers: Map<String, List<String>>,
        val upstreamIdentity: String? = null,
        val exitCode: Int = ExitCodes.OK,
        val error: String? = null,
    )

    /**
     * Connects to the real upstream and records what our plugin produced. Built through the same
     * factory as `serve`, so it audits the shipping configuration - including OAuth, in
     * non-interactive mode: cached tokens are used and refreshed, but an audit command never opens
     * a browser, for the same reason `--check` on a stdio config never spawns the child.
     */
    private suspend fun checkUpstream(config: ProxyConfig, configArg: String?, initialize: JsonObject): CheckResult {
        val captured = ConcurrentHashMap<String, List<String>>()

        return try {
            buildHttpUpstream(
                config,
                configArg = configArg,
                interactive = false,
                onRequestHeaders = { captured.putAll(it) },
            ).use { upstream ->
                var identity: String? = null
                upstream.endpoint.onFrame { frame ->
                    val info = frame["result"]?.jsonObject?.get("serverInfo")?.jsonObject
                    val version = frame["result"]?.jsonObject?.get("protocolVersion")
                    identity = "${info?.get("name")} ${info?.get("version")} (protocol $version)"
                }
                upstream.endpoint.start()
                upstream.endpoint.send(initialize)
                runCatching { upstream.endpoint.close() }
                CheckResult(captured, identity)
            }
        } catch (e: AuthRequiredException) {
            CheckResult(captured, null, ExitCodes.AUTH_REQUIRED, e.message)
        } catch (e: Throwable) {
            CheckResult(captured, null, ExitCodes.UPSTREAM_CONNECT_FAILED, e.message)
        }
    }

    /**
     * Points the proxy's own client at a local listener, so we see the request exactly as the wire
     * carries it - including everything the engine adds after our plugin has run.
     */
    private suspend fun checkLoopback(config: ProxyConfig, configArg: String?, initialize: JsonObject): CheckResult {
        val captured = ConcurrentHashMap<String, List<String>>()

        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                route("{...}") {
                    handle {
                        captured.putAll(call.request.headers.entries().associate { it.key to it.value })
                        captured["(request-line)"] = listOf("${call.request.httpMethod.value} ${call.request.uri}")
                        call.respondText(
                            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",""" +
                                """"capabilities":{},"serverInfo":{"name":"loopback","version":"0"}}}""",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        server.start(wait = false)

        return try {
            val port = server.engine.resolvedConnectors().first().port
            // Built through the serving factory - the audit must report on the proxy being run,
            // not a hand-mirrored copy. The local listener never 401s, so a missing OAuth token
            // does not fail the loopback check; a cached one shows up (redacted) like any header.
            buildHttpUpstream(
                config,
                configArg = configArg,
                interactive = false,
                urlOverride = "http://127.0.0.1:$port/mcp",
            ).use { upstream ->
                upstream.endpoint.send(initialize)
            }
            CheckResult(captured, "local listener (headers above are ground truth)")
        } catch (e: Throwable) {
            CheckResult(captured, null, ExitCodes.UPSTREAM_CONNECT_FAILED, e.message)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    /**
     * The initialize the proxy would send, built by running a representative client handshake
     * through the real [IdentityRewriter] - so this audits the actual rewriting code, not a copy.
     */
    private fun sampleInitialize(config: ProxyConfig): JsonObject {
        val fromClient = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {
                    putJsonObject("sampling") {}
                    putJsonObject("roots") { put("listChanged", true) }
                }
                putJsonObject("clientInfo") {
                    put("name", "REAL-CLIENT-NAME")
                    put("version", "REAL-CLIENT-VERSION")
                }
            }
        }
        return IdentityRewriter(config.identity).rewriteInitialize(fromClient)
    }

    private fun redact(name: String, values: List<String>, secrets: Set<String>): String =
        if (name.lowercase() in secrets) {
            values.joinToString(", ") { redactSecret(it) }
        } else {
            values.joinToString(", ")
        }

    private fun findLeaks(result: CheckResult): List<String> = buildList {
        if (result.headers.isEmpty()) return@buildList // nothing observed; the error path reports it

        val payload = result.headers.entries.joinToString(" ") { "${it.key}: ${it.value}" }
        if (payload.contains("ktor", ignoreCase = true)) add("a header mentions Ktor")
        if (payload.contains("REAL-CLIENT", ignoreCase = true)) add("the client identity reached the headers")
        result.headers.keys.firstOrNull { it.equals("mcp-name", true) || it.equals("mcp-method", true) }
            ?.let { add("$it exposes tool/resource names") }
        if (result.headers.none { it.key.equals("user-agent", true) }) {
            add("no User-Agent was set, so the engine default will be used")
        }
    }

    /**
     * Leak heuristics for the handshake itself, used where there are no headers to inspect. The
     * sample client identity is a distinctive sentinel precisely so its survival is detectable.
     */
    private fun findPayloadLeaks(initialize: JsonObject): List<String> = buildList {
        val encoded = initialize.encode()
        if (encoded.contains("REAL-CLIENT", ignoreCase = true)) {
            add("the client identity survived rewriting and would reach the server")
        }
        val capabilities = initialize["params"]?.jsonObject?.get("capabilities")?.jsonObject
        capabilities?.keys?.filter { it in DROPPED_CAPABILITIES }?.forEach {
            add("capabilities.$it is forwarded, and its contents are client-specific")
        }
    }

    private fun verdict(config: ProxyConfig, result: CheckResult, leaks: List<String>): String {
        // Auth-required is not "unreachable" - the server answered, it just wants a login first.
        if (result.exitCode == ExitCodes.AUTH_REQUIRED) return "FAILED: ${result.error}"
        result.error?.let { return "FAILED: could not reach upstream: $it" }

        return if (leaks.isEmpty()) {
            "OK: identity is ${config.identity.name}/${config.identity.version}, " +
                "User-Agent ${config.identity.userAgent}; nothing identifying found."
        } else {
            "WARNING:\n" + leaks.joinToString("\n") { "  - $it" }
        }
    }

    private val prettyJson = Json { prettyPrint = true }
}
