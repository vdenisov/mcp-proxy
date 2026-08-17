package org.plukh.mcpproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.OAuthConfig

/**
 * [OAuthSession] against a MockEngine authorization server. The interactive tests drive a real
 * [CallbackServer] through the `openBrowser` seam: the "browser" parses the authorize URL it was
 * handed and calls the loopback callback over a real socket, which is exactly what a human plus a
 * browser would do minus the consent screen.
 */
class OAuthSessionTest {

    private val upstreamUrl = "https://mcp.example.com/mcp"
    private val issuer = "https://auth.example.com"

    private val dir: Path = Files.createTempDirectory("mcp-proxy-oauth-test")
    private val store = TokenStore(dir)
    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC)

    /** Requests the mock AS saw, as (method, url, form-or-body). */
    private val asRequests = CopyOnWriteArrayList<Triple<String, String, String>>()
    private val tokenGeneration = AtomicInteger(0)

    /** Scripted /token behaviour; default: rotate on refresh, issue at-1/rt-1 on code exchange. */
    private var tokenHandler: (Map<String, String>) -> Pair<HttpStatusCode, String> = ::defaultTokenHandler

    private fun defaultTokenHandler(form: Map<String, String>): Pair<HttpStatusCode, String> =
        when (form["grant_type"]) {
            "refresh_token" -> {
                val n = tokenGeneration.incrementAndGet()
                HttpStatusCode.OK to
                    """{"access_token":"at-refreshed-$n","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-rotated-$n"}"""
            }

            "authorization_code" ->
                HttpStatusCode.OK to
                    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1"}"""

            else -> HttpStatusCode.BadRequest to """{"error":"unsupported_grant_type"}"""
        }

    private fun mockAs() = MockEngine { request ->
        val url = request.url.toString()
        val body = runCatching { String(request.body.toByteArray()) }.getOrDefault("")
        asRequests += Triple(request.method.value, url, body)

        when {
            url == "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> respond(
                """{"resource":"https://mcp.example.com/mcp","authorization_servers":["$issuer"],"scopes_supported":["mcp.read"]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            url == "$issuer/.well-known/oauth-authorization-server" -> respond(
                """
                {"issuer":"$issuer",
                 "authorization_endpoint":"$issuer/authorize",
                 "token_endpoint":"$issuer/token",
                 "registration_endpoint":"$issuer/register",
                 "code_challenge_methods_supported":["S256"]}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            url == "$issuer/register" -> respond(
                """{"client_id":"registered-client","token_endpoint_auth_method":"none"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )

            url == "$issuer/token" -> {
                val form = parseQueryString(body).entries().associate { it.key to it.value.first() }
                val (status, responseBody) = tokenHandler(form)
                respond(responseBody, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    /** A "browser" that immediately authorizes: parse the URL, call the loopback callback. */
    private val fakeBrowser: (String) -> Unit = { authorizeUrl ->
        val url = Url(authorizeUrl)
        val redirectUri = url.parameters["redirect_uri"]!!
        val state = url.parameters["state"]!!
        // A real socket call, on a real thread - the browser is not part of our coroutine world.
        Thread {
            runBlocking {
                HttpClient(CIO).use { it.get("$redirectUri?code=fake-code&state=$state") }
            }
        }.start()
    }

    private fun session(
        oauth: OAuthConfig = OAuthConfig(),
        interactive: Boolean = true,
        store: TokenStore = this.store,
    ) = OAuthSession(
        upstreamUrl = upstreamUrl,
        oauth = oauth,
        identity = IdentityConfig(name = "generic-proxy"),
        store = store,
        http = HttpClient(mockAs()),
        openBrowser = fakeBrowser,
        interactive = interactive,
        configPathHint = "config.yaml",
        clock = clock,
        announceUrl = {},
    )

    private fun seedTokens(expiresAt: Long? = clock.instant().epochSecond + 3600, refreshToken: String? = "rt-0") {
        store.saveTokens(
            upstreamUrl,
            StoredTokens(
                accessToken = "at-0",
                refreshToken = refreshToken,
                expiresAtEpochSeconds = expiresAt,
                resource = "https://mcp.example.com/mcp",
                issuer = issuer,
                obtainedAtEpochSeconds = clock.instant().epochSecond - 100,
            ),
        )
    }

    private fun seedRegistration() {
        store.saveRegistration(
            StoredRegistration(
                issuer = issuer,
                clientId = "cached-client",
                tokenEndpointAuthMethod = "none",
                redirectUris = listOf("http://127.0.0.1:9999/callback"),
                issuedAtEpochSeconds = clock.instant().epochSecond - 1000,
            ),
        )
    }

    private fun tokenRequests() = asRequests.filter { it.second == "$issuer/token" }

    // --- refresh behaviour ---

    @Test
    fun `a fresh token is used as-is`() = runBlocking {
        seedTokens()
        val session = session()

        session.ensureToken()

        assertEquals(mapOf("Authorization" to "Bearer at-0"), session.currentHeaders())
        assertTrue(tokenRequests().isEmpty(), "no token request expected for a fresh token")
    }

    @Test
    fun `a token expiring within the skew is refreshed proactively`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60) // inside the 120s skew
        seedRegistration()
        val session = session()

        session.ensureToken()

        assertEquals(mapOf("Authorization" to "Bearer at-refreshed-1"), session.currentHeaders())
        assertEquals(1, tokenRequests().size)
    }

    @Test
    fun `rotation persists the new refresh token`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60)
        seedRegistration()
        val session = session()

        session.ensureToken()

        val onDisk = store.loadTokens(upstreamUrl)!!
        assertEquals("rt-rotated-1", onDisk.refreshToken, "the rotated refresh token must be on disk")
        assertEquals("at-refreshed-1", onDisk.accessToken)
    }

    @Test
    fun `refresh sends the resource parameter`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60)
        seedRegistration()

        session().ensureToken()

        val form = parseQueryString(tokenRequests().single().third)
        assertEquals("https://mcp.example.com/mcp", form["resource"])
        assertEquals("rt-0", form["refresh_token"])
    }

    /**
     * Regression: with rotation, a new pair that was installed in memory but failed to persist is a
     * time bomb - the old refresh token dies on first use of the new one, and a restart then has
     * nothing valid. Disk must come first; a failed save must leave the session on the old token.
     */
    @Test
    fun `a token that cannot be persisted is not installed`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60)
        seedRegistration()
        val session = session()

        // Make the final move fail: a non-empty directory where the token file should land.
        val target = store.tokensFile(upstreamUrl)
        Files.delete(target)
        Files.createDirectory(target)
        Files.createFile(target.resolve("occupied"))

        runCatching { session.ensureToken() }

        assertEquals(
            mapOf("Authorization" to "Bearer at-0"),
            session.currentHeaders(),
            "an unpersisted token must not be used",
        )
    }

    @Test
    fun `invalid_grant deletes tokens and demands re-authorization`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60)
        seedRegistration()
        tokenHandler = { _ -> HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""" }
        val session = session(interactive = false)

        val e = assertFailsWith<org.plukh.mcpproxy.oauth.AuthRequiredException> { session.ensureToken() }

        assertNull(store.loadTokens(upstreamUrl), "a dead grant's tokens must not linger")
        assertTrue(e.message!!.contains("--login"), "the error must name the fix, got: ${e.message}")
    }

    @Test
    fun `a transient 500 does not burn the grant`() = runBlocking {
        seedTokens(expiresAt = clock.instant().epochSecond + 60)
        seedRegistration()
        tokenHandler = { _ -> HttpStatusCode.InternalServerError to "boom" }
        val session = session(interactive = false)

        assertFailsWith<org.plukh.mcpproxy.oauth.AuthRequiredException> { session.ensureToken() }

        assertEquals("rt-0", store.loadTokens(upstreamUrl)!!.refreshToken, "the refresh token must survive 5xx")
        assertEquals(3, tokenRequests().size, "expected the refresh to be retried")
    }

    @Test
    fun `concurrent 401s produce a single refresh`() = runBlocking {
        seedTokens()
        seedRegistration()
        tokenHandler = { form ->
            Thread.sleep(100) // widen the race window
            defaultTokenHandler(form)
        }
        val session = session()

        val results = listOf(
            async { session.handleUnauthorized(null) },
            async { session.handleUnauthorized(null) },
        ).awaitAll()

        assertEquals(listOf(true, true), results)
        assertEquals(1, tokenRequests().size, "two racing 401s must share one refresh")
    }

    @Test
    fun `newer tokens on disk rescue the session without a network round`() = runBlocking {
        seedTokens()
        val session = session()
        session.ensureToken()

        // A parallel --login run landed fresh tokens.
        store.saveTokens(
            upstreamUrl,
            StoredTokens(
                accessToken = "at-from-login",
                refreshToken = "rt-from-login",
                expiresAtEpochSeconds = clock.instant().epochSecond + 3600,
                resource = "https://mcp.example.com/mcp",
                issuer = issuer,
                obtainedAtEpochSeconds = clock.instant().epochSecond,
            ),
        )

        assertTrue(session.handleUnauthorized(null))
        assertEquals(mapOf("Authorization" to "Bearer at-from-login"), session.currentHeaders())
        assertTrue(tokenRequests().isEmpty())
    }

    // --- the interactive flow ---

    @Test
    fun `the full flow registers with the configured identity and lands tokens`() = runBlocking {
        val session = session()

        session.ensureToken()

        assertEquals(mapOf("Authorization" to "Bearer at-1"), session.currentHeaders())

        val registration = asRequests.single { it.second == "$issuer/register" }
        assertTrue(
            registration.third.contains("\"client_name\":\"generic-proxy\""),
            "the registered client name must be the configured identity, got: ${registration.third}",
        )

        val exchange = parseQueryString(tokenRequests().single().third)
        assertEquals("https://mcp.example.com/mcp", exchange["resource"])
        assertNotNull(exchange["code_verifier"], "PKCE verifier missing from the exchange")

        assertNotNull(store.loadTokens(upstreamUrl), "tokens must be persisted")
        assertNotNull(store.loadRegistration(issuer), "the registration must be persisted")
    }

    @Test
    fun `the authorize url carries PKCE, state and resource`() = runBlocking {
        var captured: String? = null
        val session = OAuthSession(
            upstreamUrl = upstreamUrl,
            oauth = OAuthConfig(),
            identity = IdentityConfig(),
            store = store,
            http = HttpClient(mockAs()),
            openBrowser = { url ->
                captured = url
                fakeBrowser(url)
            },
            clock = clock,
            announceUrl = {},
        )

        session.ensureToken()

        val url = Url(captured!!)
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertNotNull(url.parameters["code_challenge"])
        assertNotNull(url.parameters["state"])
        assertEquals("https://mcp.example.com/mcp", url.parameters["resource"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("mcp.read", url.parameters["scope"], "PRM scopes_supported should be requested")
    }

    @Test
    fun `a second session reuses the persisted registration`() = runBlocking {
        session().ensureToken() // first run registers
        store.deleteTokens(upstreamUrl)
        asRequests.clear()

        // The cached registration's redirect URI has an ephemeral port that will not match the new
        // flow's port, so this run re-registers - with a fixed port it must NOT.
        val fixedPort = OAuthConfig(callbackPort = 18321)
        session(oauth = fixedPort).ensureToken()
        store.deleteTokens(upstreamUrl)
        asRequests.clear()
        session(oauth = fixedPort).ensureToken()

        assertTrue(
            asRequests.none { it.second == "$issuer/register" },
            "a fixed-port rerun must reuse the stored registration",
        )
    }

    @Test
    fun `non-interactive mode never opens a browser`() = runBlocking {
        val session = OAuthSession(
            upstreamUrl = upstreamUrl,
            oauth = OAuthConfig(),
            identity = IdentityConfig(),
            store = store,
            http = HttpClient(mockAs()),
            openBrowser = { throw AssertionError("audit mode must not open a browser") },
            interactive = false,
            clock = clock,
            announceUrl = {},
        )

        assertFailsWith<AuthRequiredException> { session.ensureToken() }
    }

    /**
     * Regression: `--login` used to give its caller the same budget as the flow it waits on, and the
     * caller's clock starts first - the flow spends its head start on discovery and registration - so
     * it always won. An abandoned login then reported "authorization is pending in the browser" for a
     * flow that the process exit was about to cancel, and the accurate message was unreachable.
     */
    @Test
    fun `an abandoned login reports the flow's own timeout, not a premature pending-in-browser`() = runBlocking {
        val session = OAuthSession(
            upstreamUrl = upstreamUrl,
            oauth = OAuthConfig(authTimeoutSeconds = 1),
            identity = IdentityConfig(),
            store = store,
            http = HttpClient(mockAs()),
            // A browser the user never comes back from.
            openBrowser = {},
            clock = clock,
            announceUrl = {},
        )

        val e = assertFailsWith<AuthRequiredException> { session.login() }

        assertContains(e.message!!, "authorization callback")
        assertTrue(
            !e.message!!.contains("pending in the browser"),
            "the caller gave up before the flow did: ${e.message}",
        )
        session.close()
    }
}
