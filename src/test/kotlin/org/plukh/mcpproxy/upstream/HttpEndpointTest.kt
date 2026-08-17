package org.plukh.mcpproxy.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.jsonrpc.decodeFrame

/**
 * Transport-level tests for the vendored Streamable HTTP client, driven by [MockEngine] so status
 * codes, content types and bodies can be scripted exactly.
 *
 * Covers the POST side. The long-lived GET/SSE stream needs a real socket and lives in the
 * end-to-end test instead.
 */
class HttpEndpointTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    handler(request)
                }
            }
            install(SSE)
        }

    private val ping = decodeFrame("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")

    // --- response handling ---

    @Test
    fun `a JSON response is delivered as a frame`() = runTest {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        val endpoint = HttpEndpoint(
            client { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            "http://test/mcp",
        )
        var received: JsonObject? = null
        endpoint.onFrame { received = it }

        endpoint.send(ping)

        assertEquals(decodeFrame(body), received)
    }

    @Test
    fun `an inline SSE response is delivered as a frame`() = runTest {
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"
        val endpoint = HttpEndpoint(
            client { respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) },
            "http://test/mcp",
        )
        var received: JsonObject? = null
        endpoint.onFrame { received = it }

        endpoint.send(ping)

        assertEquals(1, received!!["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `multi-line SSE data fields are joined with a newline, per spec`() = runTest {
        // Regression: the SDK concatenates data: fragments bare and trims each one, losing the
        // newline the spec mandates and any significant whitespace. Ktor's own parser (used by the
        // GET path) gets this right, so the two paths would otherwise disagree.
        val sse = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\n" +
            "data:  \"result\":{\"text\":\"a\"}}\n" +
            "\n"
        val endpoint = HttpEndpoint(
            client { respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) },
            "http://test/mcp",
        )
        var received: JsonObject? = null
        endpoint.onFrame { received = it }

        endpoint.send(ping)

        // Only a single leading space is stripped, so the second fragment keeps its indent - and the
        // whole thing still parses because the fields were joined with a newline.
        assertEquals(1, received!!["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `202 Accepted yields no frame`() = runTest {
        val endpoint = HttpEndpoint(
            client { respond("", HttpStatusCode.Accepted) },
            "http://test/mcp",
        )
        var received: JsonObject? = null
        endpoint.onFrame { received = it }

        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))

        assertNull(received)
    }

    // --- failure classification (regression for the session-killing bug) ---

    @Test
    fun `a non-2xx response throws and is NOT reported as a fatal endpoint error`() = runTest {
        // The relay treats errorHandler as fatal. Reporting per-request failures through it meant a
        // single 429 or expired token tore down the whole session; verified live against a 404.
        val endpoint = HttpEndpoint(
            client { respondError(HttpStatusCode.TooManyRequests) },
            "http://test/mcp",
        )
        var fatal: Throwable? = null
        endpoint.onError { fatal = it }

        assertFailsWith<StreamableHttpError> { endpoint.send(ping) }
        assertNull(fatal, "a 429 must not be reported as an unrecoverable endpoint failure")
    }

    @Test
    fun `an unexpected content type throws without a fatal error`() = runTest {
        val endpoint = HttpEndpoint(
            client { respond("<html/>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) },
            "http://test/mcp",
        )
        var fatal: Throwable? = null
        endpoint.onError { fatal = it }

        assertFailsWith<StreamableHttpError> { endpoint.send(ping) }
        assertNull(fatal)
    }

    @Test
    fun `an empty body with no content type is accepted`() = runTest {
        val endpoint = HttpEndpoint(client { respond("", HttpStatusCode.OK) }, "http://test/mcp")
        endpoint.send(ping) // must not throw
    }

    // --- session and protocol headers ---

    @Test
    fun `the session id is captured and replayed on later requests`() = runTest {
        val endpoint = HttpEndpoint(
            client {
                respond(
                    """{"jsonrpc":"2.0","id":1,"result":{}}""",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        MCP_SESSION_ID_HEADER to listOf("sess-abc"),
                    ),
                )
            },
            "http://test/mcp",
        )

        endpoint.send(ping)
        endpoint.send(ping)

        assertEquals("sess-abc", endpoint.sessionId)
        assertNull(requests[0].headers[MCP_SESSION_ID_HEADER], "first request cannot know the session yet")
        assertEquals("sess-abc", requests[1].headers[MCP_SESSION_ID_HEADER])
    }

    @Test
    fun `the negotiated protocol version is sent once set`() = runTest {
        // The SDK declares this field but never assigns it, so the required header never went out.
        val endpoint = HttpEndpoint(
            client { respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            "http://test/mcp",
        )

        endpoint.send(ping)
        assertNull(requests[0].headers[MCP_PROTOCOL_VERSION_HEADER])

        endpoint.protocolVersion = "2025-06-18"
        endpoint.send(ping)
        assertEquals("2025-06-18", requests[1].headers[MCP_PROTOCOL_VERSION_HEADER])
    }

    @Test
    fun `Mcp-Method and Mcp-Name are suppressed by default`() = runTest {
        val endpoint = HttpEndpoint(
            client { respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            "http://test/mcp",
        )

        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"secret-tool"}}"""))

        assertNull(requests[0].headers[MCP_METHOD_HEADER])
        assertNull(requests[0].headers[MCP_NAME_HEADER], "tool names must not leak into HTTP metadata")
    }

    @Test
    fun `Mcp-Method and Mcp-Name are sent when explicitly enabled`() = runTest {
        val endpoint = HttpEndpoint(
            client { respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            "http://test/mcp",
            sendMcpMethodHeaders = true,
        )

        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"my-tool"}}"""))

        assertEquals("tools/call", requests[0].headers[MCP_METHOD_HEADER])
        assertEquals("my-tool", requests[0].headers[MCP_NAME_HEADER])
    }

    // --- teardown ---

    @Test
    fun `close sends DELETE when a session exists`() = runTest {
        val endpoint = HttpEndpoint(
            client { request ->
                if (request.method == HttpMethod.Delete) {
                    respond("", HttpStatusCode.OK)
                } else {
                    respond(
                        """{"jsonrpc":"2.0","id":1,"result":{}}""",
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            MCP_SESSION_ID_HEADER to listOf("sess-xyz"),
                        ),
                    )
                }
            },
            "http://test/mcp",
        )

        endpoint.send(ping)
        endpoint.close()

        val delete = requests.last()
        assertEquals(HttpMethod.Delete, delete.method)
        assertEquals("sess-xyz", delete.headers[MCP_SESSION_ID_HEADER])
    }

    @Test
    fun `close without a session sends no DELETE`() = runTest {
        val endpoint = HttpEndpoint(client { respond("", HttpStatusCode.Accepted) }, "http://test/mcp")

        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))
        endpoint.close()

        assertTrue(requests.none { it.method == HttpMethod.Delete })
    }
}
