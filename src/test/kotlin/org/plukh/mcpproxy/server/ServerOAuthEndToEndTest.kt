package org.plukh.mcpproxy.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.config.OAuthConfig
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.oauth.FakeOAuthServer

/**
 * The OAuth half of server mode: logging in through the browser page, and the property the whole
 * arrangement exists for - a redirect URI that never changes, so the authorization server is asked
 * to register a client **once** instead of once per login.
 */
class ServerOAuthEndToEndTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun oauthUpstream(server: FakeOAuthServer): ProxyConfig {
        val tokens = Files.createTempDirectory("mcp-proxy-server-oauth")
        return ProxyConfig(
            upstream = UpstreamConfig(
                url = server.mcpUrl,
                requestTimeoutSeconds = 10,
                oauth = OAuthConfig(
                    tokenDir = tokens.toString(),
                    openBrowser = false,
                    authTimeoutSeconds = 30,
                    interactiveWaitSeconds = 5,
                ),
            ),
        )
    }

    /** Follows redirects by hand, so each hop can be asserted on. */
    private fun browser() = HttpClient(CIO) { install(HttpRedirect) { checkHttpMethod = false } }
        .also { closeables += AutoCloseable { it.close() } }

    private fun plain() = HttpClient(CIO) { followRedirects = false }
        .also { closeables += AutoCloseable { it.close() } }

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

    private suspend fun HttpClient.postFrame(url: String, frame: JsonObject, session: String? = null) =
        post(url) {
            contentType(ContentType.Application.Json)
            session?.let { header("Mcp-Session-Id", it) }
            setBody(frame.encode())
        }

    private fun startAll(): Triple<FakeOAuthServer, ProxyServerHarness, String> = runBlocking {
        val upstream = FakeOAuthServer()
        upstream.start()
        closeables += AutoCloseable { upstream.stop() }
        val harness = ProxyServerHarness(mapOf("svc" to oauthUpstream(upstream)))
        closeables += harness
        Triple(upstream, harness, harness.endpointUrl("svc"))
    }

    @Test
    fun `logging in through the browser page makes the upstream usable`() = runBlocking {
        val (upstream, harness, mcpUrl) = startAll()

        // The whole browser round in one call: /login redirects to /authorize, which redirects to
        // our own /svc/callback. This is the fakeBrowser pattern, minus the consent screen.
        val done = browser().get("${harness.baseUrl()}/svc/login")
        assertEquals(HttpStatusCode.OK, done.status)
        assertTrue(done.bodyAsText().contains("Authorization complete"), "login did not complete: ${done.bodyAsText()}")

        val response = plain().postFrame(mcpUrl, initialize())

        assertEquals(HttpStatusCode.OK, response.status)
        val frame = decodeFrame(response.bodyAsText())
        assertTrue(frame.containsKey("result"), "expected a successful initialize, got $frame")
        assertTrue(upstream.mcpRequests.isNotEmpty(), "the upstream was never reached")
    }

    /**
     * The stage's payoff. With an ephemeral callback port every login registers a fresh client and
     * services that surface redirect URIs accumulate a row per login; a fixed path on the server's
     * own port is registered once and reused forever.
     */
    @Test
    fun `a second login reuses the registration and the same redirect uri`() = runBlocking {
        val (upstream, harness, _) = startAll()

        browser().get("${harness.baseUrl()}/svc/login")
        val afterFirst = upstream.registrations.size
        // A second, independent browser round.
        browser().get("${harness.baseUrl()}/svc/login")

        assertEquals(1, afterFirst, "the first login should register exactly once")
        assertEquals(1, upstream.registrations.size, "a second login must reuse the stored registration")
        val registered = upstream.registrations.first()
        assertTrue(
            registered.contains("${harness.baseUrl()}/svc/callback"),
            "the registered redirect uri should be the server's stable callback path, got: $registered",
        )
    }

    @Test
    fun `an unauthorized upstream answers with an error naming the login page`() = runBlocking {
        val (_, harness, mcpUrl) = startAll()

        val response = plain().postFrame(mcpUrl, initialize())

        // The session exists - the client is told what to do rather than left guessing.
        assertEquals(HttpStatusCode.OK, response.status)
        val frame = decodeFrame(response.bodyAsText())
        val message = (frame["error"] as JsonObject)["message"].toString()
        assertTrue(message.contains("/svc/login"), "the error should point at the login page, got: $message")
    }

    /** The callback listener is reachable by anything local, so a wrong state must not derail a login. */
    @Test
    fun `a callback with an unknown state is refused`() = runBlocking {
        val (_, harness, _) = startAll()

        val forged = plain().get("${harness.baseUrl()}/svc/callback?code=stolen&state=not-a-real-state")

        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertTrue(forged.bodyAsText().contains("Invalid state"))
    }

    @Test
    fun `a mid-session token expiry is refreshed and the request replayed`() = runBlocking {
        val (upstream, harness, mcpUrl) = startAll()
        browser().get("${harness.baseUrl()}/svc/login")

        upstream.behaviour.set(FakeOAuthServer.Behaviour.EXPIRE_TOKEN_AFTER_FIRST_USE)
        val client = plain()
        val first = client.postFrame(mcpUrl, initialize())
        val session = first.headers["Mcp-Session-Id"]!!
        assertTrue(decodeFrame(first.bodyAsText()).containsKey("result"))

        // The token is dead now; the proxy must refresh and replay rather than surface the 401.
        val second = client.postFrame(
            mcpUrl,
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/list") },
            session,
        )

        val frame = decodeFrame(second.bodyAsText())
        assertTrue(frame.containsKey("result"), "expected the replayed request to succeed, got $frame")
    }
}
