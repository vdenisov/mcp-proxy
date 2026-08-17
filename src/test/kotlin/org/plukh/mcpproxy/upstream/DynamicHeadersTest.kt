package org.plukh.mcpproxy.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.plukh.mcpproxy.config.IdentityConfig

/**
 * The [HeaderPolicyConfig.dynamicHeaders] seam: an OAuth access token rotates between requests, so
 * the header must be read per request, not captured at client construction the way the static
 * [authHeaders] map is.
 */
class DynamicHeadersTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        static: Map<String, String> = emptyMap(),
        dynamic: (() -> Map<String, String>)?,
    ) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requests += request
                respond("")
            }
        }
        install(IdentityHeaders) {
            userAgent = IdentityConfig().userAgent
            extraHeaders = static
            dynamicHeaders = dynamic
        }
    }

    @Test
    fun `dynamic headers are read fresh on every request`() = runBlocking {
        var token = "token-1"
        val client = client(dynamic = { mapOf(HttpHeaders.Authorization to "Bearer $token") })

        client.get("https://example.com/mcp")
        token = "token-2"
        client.get("https://example.com/mcp")

        assertEquals("Bearer token-1", requests[0].headers[HttpHeaders.Authorization])
        assertEquals("Bearer token-2", requests[1].headers[HttpHeaders.Authorization])
        client.close()
    }

    @Test
    fun `a dynamic header replaces a static one of the same name`() = runBlocking {
        val client = client(
            static = mapOf(HttpHeaders.Authorization to "Bearer stale-static"),
            dynamic = { mapOf(HttpHeaders.Authorization to "Bearer fresh") },
        )

        client.get("https://example.com/mcp")

        assertEquals(listOf("Bearer fresh"), requests[0].headers.getAll(HttpHeaders.Authorization))
        client.close()
    }
}
