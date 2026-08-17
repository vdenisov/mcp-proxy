package org.plukh.mcpproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class CallbackServerTest {

    /**
     * Regression: the listening path used to be hardcoded `/callback` while the advertised URL was
     * whatever the config said. A deployment advertising any other path - the containerized case -
     * would have the authorization server redirect correctly, the listener 404, and the flow die at
     * its timeout with nothing in the message pointing at the cause.
     */
    @Test
    fun `the listening path follows the advertised callback url`() = runBlocking {
        CallbackServer(
            bindHost = "127.0.0.1",
            port = 0,
            advertisedUrl = "https://proxy.example.com/oauth/redirect",
            expectedState = "state-1",
        ).use { server ->
            server.start()
            assertEquals("https://proxy.example.com/oauth/redirect", server.redirectUri)

            // The advertised URL is public; what matters is that the *local* listener answers the
            // same path, since that is where the reverse proxy forwards to.
            val port = localPort(server)
            HttpClient(CIO).use { http ->
                val response = http.get("http://127.0.0.1:$port/oauth/redirect?code=abc&state=state-1")
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val callback = withTimeout(5.seconds) { server.await() }
            assertEquals("abc", callback.code)
        }
    }

    @Test
    fun `without an advertised url the default path and a loopback ip literal are used`() = runBlocking {
        CallbackServer("127.0.0.1", 0, advertisedUrl = null, expectedState = "s").use { server ->
            server.start()

            assertTrue(
                Regex("""^http://127\.0\.0\.1:\d+/callback$""").matches(server.redirectUri),
                "unexpected redirect uri: ${server.redirectUri}",
            )
        }
    }

    /**
     * Regression: the listener is reachable by any local process, so a wrong-state request must be
     * rejected *and* must not end the wait - otherwise anything on the machine could abort a login
     * by spraying the port.
     */
    @Test
    fun `a wrong-state callback is rejected and the wait continues`() = runBlocking {
        CallbackServer("127.0.0.1", 0, advertisedUrl = null, expectedState = "good-state").use { server ->
            server.start()
            val port = localPort(server)

            HttpClient(CIO).use { http ->
                val rejected = http.get("http://127.0.0.1:$port/callback?code=evil&state=wrong-state")
                assertEquals(HttpStatusCode.BadRequest, rejected.status)

                // Still waiting: no result yet.
                assertTrue(withTimeoutOrNull(1.seconds) { server.await() } == null, "the flow was aborted")

                val accepted = http.get("http://127.0.0.1:$port/callback?code=real&state=good-state")
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertTrue(accepted.bodyAsText().contains("close this window"))
            }

            assertEquals("real", withTimeout(5.seconds) { server.await() }.code)
        }
    }

    private fun localPort(server: CallbackServer): Int =
        // The advertised URL may be public, so read the port from the bound listener instead.
        Regex(""":(\d+)/""").find(server.boundAddress)!!.groupValues[1].toInt()
}
