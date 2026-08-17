package org.plukh.mcpproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class TokenClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private var body: String = ""

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        response: String = """{"access_token":"at","token_type":"Bearer"}""",
    ) = TokenClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    body = String(request.body.toByteArray())
                    respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        },
    )

    private fun registration(method: String, secret: String? = null) = StoredRegistration(
        issuer = "https://auth.example.com",
        clientId = "client id+1",
        clientSecret = secret,
        tokenEndpointAuthMethod = method,
        redirectUris = listOf("http://127.0.0.1:1234/callback"),
        issuedAtEpochSeconds = 0,
    )

    @Test
    fun `a public client sends its id in the form and no auth header`() = runBlocking {
        client().exchangeCode(
            tokenEndpoint = "https://auth.example.com/token",
            registration = registration("none"),
            code = "c",
            codeVerifier = "v",
            redirectUri = "http://127.0.0.1:1234/callback",
            resource = "https://mcp.example.com/mcp",
        )

        val form = parseQueryString(body)
        assertEquals("client id+1", form["client_id"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("v", form["code_verifier"])
        assertEquals("https://mcp.example.com/mcp", form["resource"])
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
    }

    /**
     * Regression: RFC 6749 §2.3.1 form-encodes both halves before base64. Concatenating them raw
     * works right up until a generated secret contains '+', '%' or a space - and the resulting
     * `invalid_client` is then misread as "the server forgot our registration", which answers by
     * re-registering, every run, forever.
     */
    @Test
    fun `client_secret_basic form-encodes the credentials before base64`() = runBlocking {
        client().refresh(
            tokenEndpoint = "https://auth.example.com/token",
            registration = registration("client_secret_basic", secret = "se cret+/%"),
            refreshToken = "rt",
            resource = "https://mcp.example.com/mcp",
        )

        val header = requests.single().headers[HttpHeaders.Authorization]!!
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))

        assertEquals("client+id%2B1:se+cret%2B%2F%25", decoded)
    }

    @Test
    fun `client_secret_post puts the credentials in the form`() = runBlocking {
        client().refresh(
            tokenEndpoint = "https://auth.example.com/token",
            registration = registration("client_secret_post", secret = "s3cret"),
            refreshToken = "rt",
            resource = "https://mcp.example.com/mcp",
        )

        val form = parseQueryString(body)
        assertEquals("client id+1", form["client_id"])
        assertEquals("s3cret", form["client_secret"])
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `an error body becomes a structured OAuth error`() = runBlocking {
        val client = client(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"expired"}""")

        val e = assertFailsWith<OAuthErrorException> {
            client.refresh("https://auth.example.com/token", registration("none"), "rt", "https://mcp.example.com/mcp")
        }

        assertEquals("invalid_grant", e.error)
        assertEquals("expired", e.description)
        assertEquals(400, e.status)
    }
}
