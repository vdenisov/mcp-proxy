package org.plukh.mcpproxy.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.jsonrpc.id
import org.plukh.mcpproxy.jsonrpc.method
import org.plukh.mcpproxy.upstream.HttpEndpoint

/**
 * The reflexive test: the proxy's own Streamable HTTP *client* driving the proxy's new Streamable
 * HTTP *server*, with a stub MCP server behind it.
 *
 * Both ends are ours, which is the point - the client is a vendored implementation of the same spec
 * the server implements, so anything the two disagree about is a bug in one of them rather than in
 * a test's idea of the protocol. Real sockets throughout: Ktor's client SSE plugin cannot run on
 * MockEngine, and the stream is half of what is being tested.
 */
class ProxyServerEndToEndTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun stub(): StubHttpMcpUpstream =
        StubHttpMcpUpstream().also { it.start(); closeables += AutoCloseable { it.stop() } }

    private fun harness(upstreams: Map<String, org.plukh.mcpproxy.config.ProxyConfig>): ProxyServerHarness =
        ProxyServerHarness(upstreams).also { closeables += it }

    /** A client of the proxy, built from the proxy's own upstream client. */
    private fun client(url: String): Pair<HttpEndpoint, Channel<JsonObject>> {
        val http = HttpClient(CIO) { install(SSE) }
        closeables += AutoCloseable { http.close() }
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = HttpEndpoint(client = http, url = url)
        closeables += AutoCloseable { runBlocking { endpoint.close() } }
        endpoint.onFrame { frames.trySend(it) }
        return endpoint to frames
    }

    private fun initialize(id: Int = 1) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "reflexive-test")
                put("version", "1.0")
            }
        }
    }

    private fun request(id: Int, method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
    }

    private fun notification(method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }

    @Test
    fun `a session initializes, relays a request, and the upstream sees our identity`() = runBlocking {
        val upstream = stub()
        val harness = harness(mapOf("svc" to httpUpstream(upstream.url)))
        val (client, frames) = client(harness.endpointUrl("svc"))

        client.start()
        client.send(initialize())
        val initResult = withTimeout(20.seconds) { frames.receive() }

        val serverInfo = (initResult["result"] as JsonObject)["serverInfo"] as JsonObject
        assertEquals("stub-upstream", serverInfo["name"].asString())
        // The client identity was rewritten on the way through, exactly as in stdio mode.
        val upstreamInit = upstream.requests.first { it.method == "initialize" }
        val sentClientInfo = (upstreamInit["params"] as JsonObject)["clientInfo"] as JsonObject
        assertEquals("mcp-proxy", sentClientInfo["name"].asString(), "the real client name reached the upstream")

        client.send(request(2, "tools/list"))
        val toolsResult = withTimeout(20.seconds) { frames.receive() }
        assertEquals(2, toolsResult.id.toString().toInt())
    }

    /**
     * The reverse direction over two HTTP hops: the upstream initiates, it reaches the client on the
     * standalone stream, and the client's answer travels all the way back.
     */
    @Test
    fun `a server-initiated request reaches the client and its response reaches the upstream`() = runBlocking {
        val upstream = stub()
        val harness = harness(mapOf("svc" to httpUpstream(upstream.url)))
        val (client, frames) = client(harness.endpointUrl("svc"))

        client.start()
        client.send(initialize())
        withTimeout(20.seconds) { frames.receive() }
        // 202 on this is the client's cue to open its GET stream.
        client.send(notification("notifications/initialized"))

        upstream.push(request(77, "sampling/createMessage"))
        val serverRequest = withTimeout(20.seconds) { frames.receive() }
        assertEquals("sampling/createMessage", serverRequest.method)

        client.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 77)
                putJsonObject("result") { put("role", "assistant") }
            },
        )
        withTimeout(20.seconds) {
            while (upstream.requests.none { it.id?.toString() == "77" && it.method == null }) {
                kotlinx.coroutines.delay(50)
            }
        }
    }

    @Test
    fun `two upstreams are served independently and a session cannot cross between them`() = runBlocking {
        val first = stub()
        val second = stub()
        first.respondTo("tools/list", buildJsonObject { putJsonObject("result") { put("who", "first") } })
        second.respondTo("tools/list", buildJsonObject { putJsonObject("result") { put("who", "second") } })
        val harness = harness(
            mapOf("one" to httpUpstream(first.url), "two" to httpUpstream(second.url)),
        )

        val (clientOne, framesOne) = client(harness.endpointUrl("one"))
        val (clientTwo, framesTwo) = client(harness.endpointUrl("two"))
        clientOne.start()
        clientTwo.start()
        clientOne.send(initialize())
        withTimeout(20.seconds) { framesOne.receive() }
        clientTwo.send(initialize())
        withTimeout(20.seconds) { framesTwo.receive() }

        clientOne.send(request(2, "tools/list"))
        clientTwo.send(request(2, "tools/list"))

        val answerOne = withTimeout(20.seconds) { framesOne.receive() }
        val answerTwo = withTimeout(20.seconds) { framesTwo.receive() }

        assertEquals("first", (answerOne["result"] as JsonObject)["who"].asString())
        assertEquals("second", (answerTwo["result"] as JsonObject)["who"].asString())
    }

    @Test
    fun `closing the client ends the session and terminates the upstream session too`() = runBlocking {
        val upstream = stub()
        val harness = harness(mapOf("svc" to httpUpstream(upstream.url)))
        val (client, frames) = client(harness.endpointUrl("svc"))

        client.start()
        client.send(initialize())
        withTimeout(20.seconds) { frames.receive() }

        client.close()

        withTimeout(20.seconds) {
            while (upstream.deletes.isEmpty()) kotlinx.coroutines.delay(50)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.asString(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
