package org.plukh.mcpproxy.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode

/**
 * The wire contract, asserted with a raw HTTP client.
 *
 * The reflexive test cannot see most of this: our own client tolerates a 200 where the spec says
 * 202, and never sends a session id it was not given. These are the assertions that keep the server
 * honest for *other* clients.
 */
class ProxyServerProtocolTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun stub() = StubHttpMcpUpstream().also { it.start(); closeables += AutoCloseable { it.stop() } }

    private fun harness(vararg names: Pair<String, StubHttpMcpUpstream>) =
        ProxyServerHarness(names.associate { (n, s) -> n to httpUpstream(s.url) }).also { closeables += it }

    private fun http() = HttpClient(CIO).also { closeables += AutoCloseable { it.close() } }

    private fun initialize() = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "raw"); put("version", "1") }
        }
    }

    private suspend fun HttpClient.postFrame(url: String, frame: JsonObject, session: String? = null): HttpResponse =
        post(url) {
            contentType(ContentType.Application.Json)
            session?.let { header("Mcp-Session-Id", it) }
            setBody(frame.encode())
        }

    /** Opens a session and returns its id. */
    private suspend fun HttpClient.openSession(url: String): String {
        val response = postFrame(url, initialize())
        assertEquals(HttpStatusCode.OK, response.status)
        return assertNotNull(response.headers["Mcp-Session-Id"], "initialize must issue a session id")
    }

    @Test
    fun `an initialize request answers with json and a session id, a notification with 202`() = runBlocking {
        val upstream = stub()
        val harness = harness("svc" to upstream)
        val client = http()
        val url = harness.endpointUrl("svc")

        val init = client.postFrame(url, initialize())
        assertEquals(HttpStatusCode.OK, init.status)
        assertEquals(ContentType.Application.Json, init.contentType()?.withoutParameters())
        val session = assertNotNull(init.headers["Mcp-Session-Id"])
        assertEquals(1, decodeFrame(init.bodyAsText())["id"].toString().toInt())

        val notification = client.postFrame(
            url,
            buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") },
            session,
        )
        // 202 exactly: a client uses it to decide the standalone stream is where to listen next.
        assertEquals(HttpStatusCode.Accepted, notification.status)
    }

    @Test
    fun `a first frame that is not initialize is refused, and an unknown session is a 404`() = runBlocking {
        val upstream = stub()
        val harness = harness("svc" to upstream)
        val client = http()
        val url = harness.endpointUrl("svc")

        val noSession = client.postFrame(
            url,
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 9); put("method", "tools/list") },
        )
        assertEquals(HttpStatusCode.BadRequest, noSession.status)

        val bogus = client.postFrame(url, initialize(), session = "deadbeef")
        assertEquals(HttpStatusCode.NotFound, bogus.status, "an unknown session must say 'start over'")
    }

    @Test
    fun `a malformed body is answered with a parse error frame`() = runBlocking {
        val upstream = stub()
        val harness = harness("svc" to upstream)
        val client = http()

        val response = client.post(harness.endpointUrl("svc")) {
            contentType(ContentType.Application.Json)
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val frame = decodeFrame(response.bodyAsText())
        assertEquals(-32700, (frame["error"] as JsonObject)["code"].toString().toInt())
    }

    /** An id issued for one upstream must not address another, however unguessable it is. */
    @Test
    fun `a session id from one upstream is not valid on another`() = runBlocking {
        val one = stub()
        val two = stub()
        val harness = harness("one" to one, "two" to two)
        val client = http()

        val session = client.openSession(harness.endpointUrl("one"))
        val crossed = client.postFrame(
            harness.endpointUrl("two"),
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/list") },
            session,
        )

        assertEquals(HttpStatusCode.NotFound, crossed.status)
    }

    @Test
    fun `DELETE ends the session and the id stops working`() = runBlocking {
        val upstream = stub()
        val harness = harness("svc" to upstream)
        val client = http()
        val url = harness.endpointUrl("svc")
        val session = client.openSession(url)

        val deleted = client.delete(url) { header("Mcp-Session-Id", session) }
        assertEquals(HttpStatusCode.OK, deleted.status)

        val after = client.postFrame(
            url,
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 3); put("method", "tools/list") },
            session,
        )
        assertEquals(HttpStatusCode.NotFound, after.status)
    }

    @Test
    fun `a request from a foreign Origin is refused`() = runBlocking {
        val upstream = stub()
        val harness = harness("svc" to upstream)
        val client = http()

        val hostile = client.post(harness.endpointUrl("svc")) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example.com")
            setBody(initialize().encode())
        }
        assertEquals(HttpStatusCode.Forbidden, hostile.status)

        // No Origin at all is how every MCP client and curl behave, and must keep working.
        assertEquals(HttpStatusCode.OK, client.postFrame(harness.endpointUrl("svc"), initialize()).status)
    }

    @Test
    fun `the status page lists every upstream and its endpoint`() = runBlocking {
        val one = stub()
        val two = stub()
        val harness = harness("one" to one, "two" to two)

        val body = http().get(harness.baseUrl()).bodyAsText()

        assertTrue(body.contains("one"), "status page omits an upstream")
        assertTrue(body.contains("two"), "status page omits an upstream")
        assertTrue(body.contains("/one/mcp"), "status page omits an endpoint URL")
        assertTrue(body.contains("no authentication"), "status page must carry the trust warning")
    }
}
