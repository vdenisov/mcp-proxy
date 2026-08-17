package org.plukh.mcpproxy.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.initializeFrame
import org.plukh.mcpproxy.runProxy
import org.plukh.mcpproxy.upstream.HttpEndpoint
import org.plukh.mcpproxy.upstream.buildHttpClient

/**
 * The unauthenticated HTTP upstream, against a server that needs no credentials.
 *
 * Its value over the loopback tests is everything a stub cannot fake: TLS, a real CDN in front of the
 * server, chunked transfer, and whatever the server does with the handshake. Anonymous access is
 * rate-limited, so this is a smoke test, not something to loop on.
 */
class Context7LiveTest {

    @Test
    fun `a real session against Context7 round-trips through the proxy`() {
        val identity = IdentityConfig(name = "mcp-proxy", version = "1.0.0", userAgent = "mcp-proxy/1.0")
        val upstreamConfig = UpstreamConfig(url = Live.CONTEXT7_URL)
        val client = buildHttpClient(upstreamConfig, identity)

        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject

        val exitCode = try {
            runProxy(
                upstream = HttpEndpoint(client, upstreamConfig.url!!),
                identity = identity,
                timeoutMs = Live.TIMEOUT_MS,
            ) {
                send(initializeFrame())
                initialize = nextFrame()
                send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                tools = nextFrame()
                disconnect()
            }
        } finally {
            client.close()
        }

        assertEquals(ExitCodes.OK, exitCode)

        val serverInfo = initialize["result"]!!.jsonObject["serverInfo"]!!.jsonObject
        assertEquals("Context7", serverInfo["name"]!!.jsonPrimitive.content)

        val toolNames = tools["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.isNotEmpty(), "no tools listed, got: $tools")
        // Nothing of ours should have survived into the relayed payload.
        assertTrue(!tools.toString().contains("mcp-proxy"), "the proxy left traces in the relayed payload")
    }
}
