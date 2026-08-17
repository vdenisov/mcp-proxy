package org.plukh.mcpproxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.OAuthConfig
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.oauth.FakeOAuthServer
import org.plukh.mcpproxy.oauth.TokenStore

/**
 * Full-stack OAuth: client stdin -> relay -> HttpEndpoint -> a real OAuth-gated MCP server, with the
 * whole flow - discovery from the 401, registration, PKCE, callback, exchange, refresh, replay -
 * running through the same [buildHttpUpstream] factory `serve` uses. The browser seam is a real
 * HTTP client that follows the authorize redirect to the loopback callback.
 */
class OAuthEndToEndTest {

    private val server = FakeOAuthServer()
    private lateinit var tokenDir: Path

    @BeforeTest
    fun setUp() = runBlocking {
        server.start()
        tokenDir = Files.createTempDirectory("mcp-proxy-oauth-e2e")
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    /** GET the authorize URL, follow the 302 to the loopback callback - a browser minus the human. */
    private val fakeBrowser: (String) -> Unit = { authorizeUrl ->
        Thread {
            runBlocking {
                HttpClient(CIO) { followRedirects = false }.use { http ->
                    val redirect: HttpResponse = http.get(authorizeUrl)
                    http.get(redirect.headers["Location"]!!)
                }
            }
        }.start()
    }

    private fun config(identityName: String = "generic-proxy", interactiveWaitSeconds: Long = 25) = ProxyConfig(
        identity = IdentityConfig(name = identityName, version = "9.9.9"),
        upstream = UpstreamConfig(
            url = server.mcpUrl,
            oauth = OAuthConfig(tokenDir = tokenDir.toString(), interactiveWaitSeconds = interactiveWaitSeconds),
        ),
    )

    private fun runOAuthProxy(config: ProxyConfig = config(), block: suspend ProxySession.() -> Unit): Int {
        val upstream = buildHttpUpstream(config, openBrowser = fakeBrowser, announceUrl = {})
        return try {
            runProxy(upstream = upstream.endpoint, identity = config.identity, block = block)
        } finally {
            upstream.close()
        }
    }

    @Test
    fun `the first run authorizes and serves, and the server sees only the configured identity`() {
        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject

        val exitCode = runOAuthProxy {
            send(initializeFrame())
            initialize = nextFrame()
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            tools = nextFrame()
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals(
            "fake-oauth-server",
            initialize["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(2, tools["id"]!!.jsonPrimitive.content.toInt())

        // The stage 3 payoff: the registered client is the configured identity, and nothing that
        // reached the server names the real client.
        val registration = server.registrations.single()
        assertTrue(
            registration.contains("\"client_name\":\"generic-proxy\""),
            "client_name must come from identity.name, got: $registration",
        )
        assertTrue(server.mcpRequests.none { it.contains("claude-code") }, "the real client identity leaked")
    }

    @Test
    fun `the resource parameter reaches both authorize and token requests`() {
        runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            disconnect()
        }

        assertEquals<List<String?>>(listOf(server.mcpUrl), server.authorizeResources)
        assertEquals<List<String?>>(listOf(server.mcpUrl), server.tokenResources)
    }

    @Test
    fun `discovery works from the well-known fallback when the 401 has no challenge header`() {
        server.behaviour.set(FakeOAuthServer.Behaviour.NO_WWW_AUTHENTICATE)

        val exitCode = runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
    }

    /**
     * Regression: a token dying mid-session must be repaired by refresh + replay without the client
     * ever seeing it - the downstream frame for the second request must be a result, not an error.
     */
    @Test
    fun `a mid-session 401 is refreshed and replayed invisibly`() {
        lateinit var second: JsonObject

        val exitCode = runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            server.behaviour.set(FakeOAuthServer.Behaviour.EXPIRE_TOKEN_AFTER_FIRST_USE)
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""") // kills the token after answering
            nextFrame()
            send("""{"jsonrpc":"2.0","id":3,"method":"tools/call"}""") // hits the 401, must recover
            second = nextFrame()
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertTrue(second.containsKey("result"), "the client saw the 401 instead of the replayed result: $second")
        assertNull(second["error"], "no error frame may reach the client for a recovered 401")
    }

    /**
     * Regression: a 401 on the GET/SSE connect must trigger a refresh and an immediate reconnect;
     * the old classification treated it as a connectivity failure, burned the backoff retries and
     * tore the session down. The server-initiated notification only arrives if the recovery worked.
     */
    @Test
    fun `a 401 on the SSE stream is refreshed and the stream recovers`() {
        lateinit var streamed: JsonObject

        val exitCode = runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            server.behaviour.set(FakeOAuthServer.Behaviour.EXPIRE_TOKEN_BEFORE_SSE)
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") // opens the GET stream
            streamed = nextFrame() // the notification the fake server pushes on its SSE stream
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals("notifications/from-stream", streamed["method"]!!.jsonPrimitive.content)
    }

    /**
     * Regression: the loopback callback is open to any local process, so a callback carrying the
     * wrong state must be rejected and must not produce tokens - `state` is the only thing binding
     * the callback to the flow that asked for it.
     */
    @Test
    fun `a callback with a forged state yields no tokens`() {
        server.behaviour.set(FakeOAuthServer.Behaviour.WRONG_STATE)
        // The flow can only time out here; a short bound keeps the suite fast.
        val config = config(interactiveWaitSeconds = 3)

        runOAuthProxy(config) {
            send(initializeFrame())
            // No frame will come: authorization cannot complete. The relay answers the initialize
            // with a JSON-RPC error once the bounded wait expires.
            val answer = nextFrame()
            assertTrue(answer.containsKey("error"), "expected an error answer, got: $answer")
            disconnect()
        }

        assertNull(
            TokenStore(tokenDir).loadTokens(server.mcpUrl),
            "a forged callback must never produce a token file",
        )
    }

    @Test
    fun `a second session reuses the stored tokens without a new flow`() {
        runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            disconnect()
        }
        val flowsAfterFirst = server.authorizeResources.size

        val exitCode = runOAuthProxy {
            send(initializeFrame())
            nextFrame()
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals(flowsAfterFirst, server.authorizeResources.size, "the second run must not re-authorize")
    }
}
