package org.plukh.mcpproxy.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.buildHttpUpstream
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.OAuthConfig
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.initializeFrame
import org.plukh.mcpproxy.runProxy

/**
 * A real OAuth-gated hosted server (Linear, Notion, Sentry, ...), with a real browser and a human.
 *
 * Doubly gated: `MCP_PROXY_TEST_OAUTH_URL` selects the server, and `MCP_PROXY_TEST_OAUTH_INTERACTIVE=1`
 * confirms someone is at the keyboard - an unattended `-PliveTests` run must never pop a browser.
 * Tokens go to the real `~/.mcp-proxy/tokens`, so a second run reuses them and needs no browser.
 */
class RealServerOAuthLiveTest {

    @Test
    fun `login and a full session against a real OAuth server`() {
        val url = System.getenv("MCP_PROXY_TEST_OAUTH_URL")
        assumeTrue(!url.isNullOrBlank(), "MCP_PROXY_TEST_OAUTH_URL is not set")
        assumeTrue(
            System.getenv("MCP_PROXY_TEST_OAUTH_INTERACTIVE") == "1",
            "MCP_PROXY_TEST_OAUTH_INTERACTIVE=1 not set; this test opens a browser",
        )

        val config = ProxyConfig(
            identity = IdentityConfig(name = "mcp-proxy", version = "1.0.0"),
            upstream = UpstreamConfig(
                url = url,
                // A generous wait: a human has to find the browser window and click through consent.
                oauth = OAuthConfig(interactiveWaitSeconds = 240),
            ),
        )

        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject

        val upstream = buildHttpUpstream(config)
        val exitCode = try {
            runProxy(upstream = upstream.endpoint, identity = config.identity, timeoutMs = 300_000) {
                send(initializeFrame())
                initialize = nextFrame()
                send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                tools = nextFrame()
                disconnect()
            }
        } finally {
            upstream.close()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertTrue(initialize.containsKey("result"), "initialize failed: $initialize")
        assertTrue(
            tools["result"]!!.jsonObject["tools"]!!.jsonArray.isNotEmpty(),
            "no tools listed, got: $tools",
        )
    }
}
