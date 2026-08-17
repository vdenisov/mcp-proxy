package org.plukh.mcpproxy.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reactive 401 path in [HttpEndpoint.send]: refresh through the [UpstreamTokenSource] seam and
 * replay exactly once.
 */
class HttpEndpointAuthTest {

    private val requests = mutableListOf<HttpRequestData>()

    private class FakeTokenSource(private val refreshSucceeds: Boolean = true) : UpstreamTokenSource {
        var token = "token-1"
        val challenges = mutableListOf<String?>()
        var refreshes = 0

        override suspend fun ensureToken() = Unit
        override fun currentHeaders() = mapOf(HttpHeaders.Authorization to "Bearer $token")
        override suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean {
            challenges += wwwAuthenticate
            refreshes++
            if (refreshSucceeds) token = "token-${refreshes + 1}"
            return refreshSucceeds
        }
    }

    private fun client(
        tokenSource: FakeTokenSource,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requests += request
                handler(request)
            }
        }
        install(IdentityHeaders) {
            userAgent = "test/1.0"
            dynamicHeaders = tokenSource::currentHeaders
        }
    }

    private val frame = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/list") }

    @Test
    fun `a 401 refreshes the token and replays the request once`() = runBlocking {
        val tokenSource = FakeTokenSource()
        var calls = 0
        val endpoint = HttpEndpoint(
            client = client(tokenSource) {
                if (++calls == 1) {
                    respond(
                        "",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.WWWAuthenticate, """Bearer resource_metadata="https://h/prm""""),
                    )
                } else {
                    respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", headers = jsonType())
                }
            },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )
        var received = 0
        endpoint.onFrame { received++ }

        endpoint.send(frame)

        assertEquals(2, requests.size, "expected the original request plus one replay")
        assertEquals("Bearer token-1", requests[0].headers[HttpHeaders.Authorization])
        assertEquals("Bearer token-2", requests[1].headers[HttpHeaders.Authorization], "the replay must carry the new token")
        assertEquals(1, received, "the replayed response must be delivered normally")
    }

    @Test
    fun `the challenge reaches the token source`() = runBlocking {
        val tokenSource = FakeTokenSource()
        var calls = 0
        val endpoint = HttpEndpoint(
            client = client(tokenSource) {
                if (++calls == 1) {
                    respond(
                        "",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.WWWAuthenticate, """Bearer resource_metadata="https://h/prm""""),
                    )
                } else {
                    respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", headers = jsonType())
                }
            },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )

        endpoint.send(frame)

        assertEquals<List<String?>>(listOf("""Bearer resource_metadata="https://h/prm""""), tokenSource.challenges)
    }

    @Test
    fun `a second 401 surfaces as the per-request error, never a loop`() = runBlocking {
        val tokenSource = FakeTokenSource()
        val endpoint = HttpEndpoint(
            client = client(tokenSource) { respond("nope", HttpStatusCode.Unauthorized) },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )
        var fatal: Throwable? = null
        endpoint.onError { fatal = it }

        // Bounded: the bug this guards is an unbounded replay loop, and an infinite loop must fail
        // the test rather than hang the build.
        val e = kotlinx.coroutines.withTimeout(10_000) {
            assertFailsWith<StreamableHttpError> { endpoint.send(frame) }
        }

        assertEquals(401, e.code)
        assertEquals(2, requests.size, "exactly one replay, then give up")
        assertNull(fatal, "a per-request 401 must not be fatal to the session")
    }

    @Test
    fun `a failed refresh skips the replay and surfaces the 401`() = runBlocking {
        val tokenSource = FakeTokenSource(refreshSucceeds = false)
        val endpoint = HttpEndpoint(
            client = client(tokenSource) { respond("nope", HttpStatusCode.Unauthorized) },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )

        assertFailsWith<StreamableHttpError> { endpoint.send(frame) }

        assertEquals(1, requests.size, "no replay without a new token")
    }

    @Test
    fun `the replay preserves the session id`() = runBlocking {
        val tokenSource = FakeTokenSource()
        var calls = 0
        val endpoint = HttpEndpoint(
            client = client(tokenSource) {
                when (++calls) {
                    // First exchange establishes the session.
                    1 -> respond(
                        """{"jsonrpc":"2.0","id":1,"result":{}}""",
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            MCP_SESSION_ID_HEADER to listOf("session-abc"),
                        ),
                    )

                    2 -> respond("", HttpStatusCode.Unauthorized)
                    else -> respond("""{"jsonrpc":"2.0","id":2,"result":{}}""", headers = jsonType())
                }
            },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )
        endpoint.onFrame { }

        endpoint.send(frame)
        endpoint.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/call") })

        // Both the 401'd attempt and its replay carry the session established earlier - session
        // identity and authorization are orthogonal, and a 401 must not reset the session.
        assertEquals("session-abc", requests[1].headers[MCP_SESSION_ID_HEADER])
        assertEquals("session-abc", requests[2].headers[MCP_SESSION_ID_HEADER])
        assertEquals("session-abc", endpoint.sessionId)
    }

    @Test
    fun `start ensures a token before the first request`() = runBlocking {
        var ensured = false
        val tokenSource = object : UpstreamTokenSource {
            override suspend fun ensureToken() {
                ensured = true
            }

            override fun currentHeaders() = emptyMap<String, String>()
            override suspend fun handleUnauthorized(wwwAuthenticate: String?) = false
        }
        val endpoint = HttpEndpoint(
            client = HttpClient(MockEngine) { engine { addHandler { respond("") } } },
            url = "https://mcp.example.com/mcp",
            tokenSource = tokenSource,
        )

        endpoint.start()

        assertTrue(ensured, "start() must run the token bootstrap")
    }

    private fun jsonType() = headersOf(HttpHeaders.ContentType, "application/json")
}
