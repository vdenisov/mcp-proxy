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
 * The authenticated HTTP path, which until now had never run against a real server - Context7 needs
 * no credentials, so `Authorization` was only ever exercised against a mock. This closes that gap:
 * a wrong header name, a mangled value or a dropped header shows up here as a 401 and nowhere else.
 */
class GitHubHostedLiveTest {

    @Test
    fun `an authenticated session against the hosted GitHub server round-trips`() {
        val pat = Live.githubPat()
        val identity = IdentityConfig(name = "mcp-proxy", version = "1.0.0", userAgent = "mcp-proxy/1.0")
        val upstreamConfig = UpstreamConfig(url = Live.HOSTED_GITHUB_URL, authToken = pat)
        val client = buildHttpClient(upstreamConfig, identity)

        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject
        lateinit var call: JsonObject

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
                send(Live.SEARCH_REPOSITORIES_CALL)
                call = nextFrame()
                disconnect()
            }
        } finally {
            client.close()
        }

        assertEquals(ExitCodes.OK, exitCode)
        // An auth failure arrives as an error frame rather than an exception, so say so plainly -
        // "expected result, got error" is the message that saves the next person ten minutes.
        assertTrue(
            initialize.containsKey("result"),
            "initialize failed - is ${Live.GITHUB_PAT_VAR} valid? got: $initialize",
        )

        val toolNames = tools["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.isNotEmpty(), "no tools listed, got: $tools")

        assertToolCallSucceeded(call)
    }
}

/**
 * A tool call reports its failures inside the result, not as a JSON-RPC error, so a test that only
 * checks for `result` would pass on a call that failed.
 */
internal fun assertToolCallSucceeded(frame: JsonObject) {
    assertTrue(frame.containsKey("result"), "tools/call returned an error frame: $frame")
    val result = frame["result"]!!.jsonObject
    assertTrue(
        result["isError"]?.jsonPrimitive?.content != "true",
        "the tool call failed, which usually means the token was not accepted by the API: $frame",
    )
}
