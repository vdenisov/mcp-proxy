package org.plukh.mcpproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Discovery starts from a 401 sent by a server we have not authenticated, so everything in that
 * challenge is untrusted input. These are the tests for treating it that way.
 */
class DiscoverySecurityTest {

    private val fetched = CopyOnWriteArrayList<String>()

    private val goodPrm =
        """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"]}"""
    private val goodAsMetadata =
        """
        {"issuer":"https://auth.example.com",
         "authorization_endpoint":"https://auth.example.com/authorize",
         "token_endpoint":"https://auth.example.com/token",
         "code_challenge_methods_supported":["S256"]}
        """.trimIndent()

    private fun discovery(handler: (String) -> Pair<HttpStatusCode, String>?) = Discovery(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    fetched += url
                    val response = handler(url)
                    if (response == null) {
                        respondError(HttpStatusCode.NotFound)
                    } else {
                        respond(response.second, response.first, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            }
        },
    )

    /**
     * Regression: a hostile upstream pointing `resource_metadata` at its own host would otherwise
     * nominate its own authorization server - and the real damage is not a bad token, it is the
     * user's browser being opened at an attacker's consent page at exactly the moment they expect a
     * login prompt.
     */
    @Test
    fun `a cross-origin resource_metadata pointer is refused`() = runBlocking {
        val discovery = discovery { url ->
            when {
                url.startsWith("https://evil.tld") ->
                    HttpStatusCode.OK to
                        """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://evil.tld/as"]}"""

                url == "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" ->
                    HttpStatusCode.OK to goodPrm

                url == "https://auth.example.com/.well-known/oauth-authorization-server" ->
                    HttpStatusCode.OK to goodAsMetadata

                else -> null
            }
        }

        val result = discovery.discover(
            "https://mcp.example.com/mcp",
            """Bearer resource_metadata="https://evil.tld/prm"""",
        )

        // Discovery fell back to the real server's own well-known document...
        assertEquals("https://auth.example.com", result.authServer.issuer)
        // ...and never fetched the attacker's document at all.
        assertTrue(
            fetched.none { it.startsWith("https://evil.tld") },
            "the attacker's metadata URL was fetched: $fetched",
        )
    }

    @Test
    fun `a same-origin resource_metadata pointer is honoured`() = runBlocking {
        val discovery = discovery { url ->
            when (url) {
                "https://mcp.example.com/custom-prm" -> HttpStatusCode.OK to goodPrm
                "https://auth.example.com/.well-known/oauth-authorization-server" -> HttpStatusCode.OK to goodAsMetadata
                else -> null
            }
        }

        val result = discovery.discover(
            "https://mcp.example.com/mcp",
            """Bearer resource_metadata="https://mcp.example.com/custom-prm"""",
        )

        assertEquals("https://auth.example.com", result.authServer.issuer)
        assertTrue(fetched.contains("https://mcp.example.com/custom-prm"), "the pointer was not used: $fetched")
    }

    /**
     * Regression: redirects are followed by default, so validating only the URL we asked for would
     * let a same-origin pointer bounce the fetch to another host.
     */
    @Test
    fun `a metadata fetch redirected off-origin is refused`() = runBlocking {
        val discovery = Discovery(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val url = request.url.toString()
                        fetched += url
                        when {
                            url == "https://mcp.example.com/custom-prm" -> respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://evil.tld/prm"),
                            )

                            url.startsWith("https://evil.tld") -> respond(
                                """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://evil.tld/as"]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )

                            url == "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> respond(
                                goodPrm,
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )

                            url == "https://auth.example.com/.well-known/oauth-authorization-server" -> respond(
                                goodAsMetadata,
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )

                            else -> respondError(HttpStatusCode.NotFound)
                        }
                    }
                }
            },
        )

        val result = discovery.discover(
            "https://mcp.example.com/mcp",
            """Bearer resource_metadata="https://mcp.example.com/custom-prm"""",
        )

        // The redirected document was discarded and the real well-known used instead.
        assertEquals("https://auth.example.com", result.authServer.issuer)
    }

    @Test
    fun `a PRM resource that does not cover the upstream is not adopted as the audience`() = runBlocking {
        val discovery = discovery { url ->
            when (url) {
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp-internal" -> HttpStatusCode.OK to
                    """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"]}"""

                "https://auth.example.com/.well-known/oauth-authorization-server" -> HttpStatusCode.OK to goodAsMetadata
                else -> null
            }
        }

        val result = discovery.discover("https://mcp.example.com/mcp-internal", null)

        // `/mcp` must not be read as covering `/mcp-internal` - a prefix is not a path boundary.
        assertEquals("https://mcp.example.com/mcp-internal", result.resource)
    }

    @Test
    fun `a PRM resource that covers the upstream by path segment is adopted`() = runBlocking {
        val discovery = discovery { url ->
            when (url) {
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp/v1" -> HttpStatusCode.OK to
                    """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"]}"""

                "https://auth.example.com/.well-known/oauth-authorization-server" -> HttpStatusCode.OK to goodAsMetadata
                else -> null
            }
        }

        val result = discovery.discover("https://mcp.example.com/mcp/v1", null)

        assertEquals("https://mcp.example.com/mcp", result.resource)
    }

    @Test
    fun `a second authorization server is tried when the first is unusable`() = runBlocking {
        val discovery = discovery { url ->
            when (url) {
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> HttpStatusCode.OK to
                    """{"authorization_servers":["https://dead.example.com","https://auth.example.com"]}"""

                "https://auth.example.com/.well-known/oauth-authorization-server" -> HttpStatusCode.OK to goodAsMetadata
                else -> null // everything under dead.example.com 404s
            }
        }

        val result = discovery.discover("https://mcp.example.com/mcp", null)

        assertEquals("https://auth.example.com", result.authServer.issuer)
    }

    @Test
    fun `an authorization server whose metadata declares a different issuer is rejected`() = runBlocking {
        val discovery = discovery { url ->
            when (url) {
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> HttpStatusCode.OK to goodPrm
                "https://auth.example.com/.well-known/oauth-authorization-server" -> HttpStatusCode.OK to
                    goodAsMetadata.replace("https://auth.example.com\"", "https://someone-else.example.com\"")

                else -> null
            }
        }

        val e = assertFailsWith<DiscoveryException> { discovery.discover("https://mcp.example.com/mcp", null) }
        assertTrue(e.message!!.contains("no usable authorization server"), "got: ${e.message}")
    }
}
