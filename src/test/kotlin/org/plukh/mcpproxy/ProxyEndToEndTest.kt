package org.plukh.mcpproxy

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.downstream.StdioEndpoint
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.relay.IdentityRewriter
import org.plukh.mcpproxy.relay.Relay
import org.plukh.mcpproxy.upstream.HttpEndpoint
import org.plukh.mcpproxy.upstream.buildHttpClient

/**
 * Full-stack test: stdin -> relay -> real HTTP over a loopback socket -> stdout.
 *
 * Complements the MockEngine tests by exercising a genuine socket, real chunked transfer, and the
 * actual stdio framing. Responses are served from payloads **recorded from the live Context7
 * server**, so this doubles as the regression guard for real-world fidelity - notably
 * `serverInfo.description`, which the MCP SDK's `Implementation` type cannot represent at all.
 */
class ProxyEndToEndTest {

    private val received = CopyOnWriteArrayList<String>()
    private var server: EmbeddedServer<*, *>? = null

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText().trim()

    /** A minimal Streamable HTTP MCP server that replays recorded payloads. */
    private suspend fun startStub(): Int {
        val initializeResult = fixture("context7-initialize-result.json")
        val toolsResult = fixture("context7-tools-list-result.json")

        val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/mcp") {
                    val body = call.receiveText()
                    received += body
                    val frame = decodeFrame(body)
                    val method = frame["method"]?.jsonPrimitive?.content
                    when (method) {
                        "initialize" -> call.respondText(initializeResult, io.ktor.http.ContentType.Application.Json)
                        "tools/list" -> call.respondText(toolsResult, io.ktor.http.ContentType.Application.Json)
                        else -> call.respondText("", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Accepted)
                    }
                }
            }
        }
        engine.start(wait = false)
        server = engine
        return engine.engine.resolvedConnectors().first().port
    }

    @AfterTest
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    /** Drives the whole proxy over piped stdio and returns the frames written to stdout. */
    private fun runProxy(vararg clientFrames: String): List<JsonObject> {
        val stdout = ByteArrayOutputStream()
        val stdinSource = PipedOutputStream()
        val stdin = PipedInputStream(stdinSource, 1 shl 16)

        val frames = runBlocking {
            val port = startStub()
            val upstreamConfig = UpstreamConfig(url = "http://127.0.0.1:$port/mcp")
            val identity = IdentityConfig(name = "mcp-proxy", version = "9.9.9", userAgent = "mcp-proxy/9.9")
            val httpClient = buildHttpClient(upstreamConfig, identity)
            val stdioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            try {
                val relay = Relay(
                    downstream = StdioEndpoint(scope = stdioScope, input = stdin, output = stdout),
                    upstream = HttpEndpoint(httpClient, upstreamConfig.url!!),
                    identity = IdentityRewriter(identity),
                )
                val exit = CompletableDeferred<Int>()
                stdioScope.launch { exit.complete(relay.run()) }

                clientFrames.forEach { stdinSource.write((it + "\n").toByteArray()) }
                stdinSource.flush()
                stdinSource.close() // EOF ends the session

                withTimeout(20_000) { exit.await() }
            } finally {
                stdioScope.cancel()
                httpClient.close()
            }
        }
        check(frames == 0) { "proxy exited with $frames" }

        return stdout.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }.map { decodeFrame(it) }
    }

    @Test
    fun `a full handshake and tool listing round-trips over real HTTP`() {
        val out = runProxy(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"sampling":{}},"clientInfo":{"name":"claude-code","version":"2.1.233"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
        )

        assertEquals(2, out.size, "expected responses to initialize and tools/list")
        assertEquals(1, out[0]["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, out[1]["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the recorded server identity reaches the client intact`() {
        val out = runProxy(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1"}}}""",
        )

        val serverInfo = out[0]["result"]!!.jsonObject["serverInfo"]!!.jsonObject
        assertEquals("Context7", serverInfo["name"]!!.jsonPrimitive.content)
        // `description` and `icons` have no equivalent on the SDK's Implementation type - relaying
        // through it would silently drop them.
        assertTrue(serverInfo.containsKey("description"), "serverInfo.description was lost")
        assertTrue(serverInfo.containsKey("icons"), "serverInfo.icons was lost")
        assertTrue(out[0]["result"]!!.jsonObject.containsKey("instructions"))
    }

    @Test
    fun `the relayed payload is byte-identical to what the server sent`() {
        val out = runProxy(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
        )

        assertEquals(decodeFrame(fixture("context7-tools-list-result.json")), out[1])
    }

    @Test
    fun `the real client identity never reaches the server`() {
        runProxy(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1.233"}}}""",
        )

        val initialize = decodeFrame(received.first())
        val clientInfo = initialize["params"]!!.jsonObject["clientInfo"]!!.jsonObject
        assertEquals("mcp-proxy", clientInfo["name"]!!.jsonPrimitive.content)
        assertEquals("9.9.9", clientInfo["version"]!!.jsonPrimitive.content)
        assertTrue(
            received.none { it.contains("claude-code") },
            "the client's real identity was sent over the wire",
        )
    }
}
