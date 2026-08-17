package org.plukh.mcpproxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.upstream.StdioUpstreamEndpoint
import org.plukh.mcpproxy.upstream.StubScenario
import org.plukh.mcpproxy.upstream.stubCommand

/**
 * Full-stack test for the stdio upstream: client stdin -> relay -> a real child process -> stdout.
 *
 * The stdio counterpart of [ProxyEndToEndTest], which does the same over a loopback socket. What the
 * child received cannot be read from a shared list across a process boundary, so the stub echoes
 * every request back under `result.echo` and the assertions read it from there.
 */
class StdioProxyEndToEndTest {

    private fun upstream(scenario: String) = StdioUpstreamEndpoint(
        command = stubCommand(scenario),
        extraEnv = mapOf("CLASSPATH" to System.getProperty("java.class.path")),
    )

    @Test
    fun `a full session round-trips through a child process`() {
        lateinit var responses: List<JsonObject>

        val exitCode = runProxy(upstream(StubScenario.ECHO)) {
            send(initializeFrame())
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            responses = listOf(nextFrame(), nextFrame())
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals(listOf(1, 2), responses.map { it["id"]!!.jsonPrimitive.content.toInt() })
        // The notification travelled too, and drew no answer - exactly one response per request.
        assertEquals("tools/list", responses[1].echo()["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the real client identity never reaches the child`() {
        lateinit var response: JsonObject

        runProxy(upstream(StubScenario.ECHO)) {
            send(initializeFrame())
            response = nextFrame()
            disconnect()
        }

        val clientInfo = response.echo()["params"]!!.jsonObject["clientInfo"]!!.jsonObject
        assertEquals("mcp-proxy", clientInfo["name"]!!.jsonPrimitive.content)
        assertEquals("9.9.9", clientInfo["version"]!!.jsonPrimitive.content)
        assertTrue(
            !response.toString().contains("claude-code"),
            "the client's real identity was sent to the server",
        )
    }

    /**
     * Regression: a child that dies mid-session must end the proxy with a status, promptly. Getting
     * this wrong does not produce a wrong answer - it produces a proxy sitting forever on a pipe
     * nobody will write to again - so the bounded wait is as much of the assertion as the exit code.
     * Note the client never disconnects here: the upstream failure alone has to end the session.
     */
    @Test
    fun `a child dying mid-session ends the proxy with the upstream failure code`() {
        val exitCode = runProxy(upstream(StubScenario.DIE_AFTER_FIRST_RESPONSE)) {
            send(initializeFrame())
            nextFrame() // answered, and the child exits immediately afterwards
        }

        assertEquals(ExitCodes.UPSTREAM_FAILED, exitCode)
    }

    /** Regression: a line the child could not have meant as a frame must not cost the session. */
    @Test
    fun `garbage from the child does not break the session`() {
        lateinit var responses: List<JsonObject>

        val exitCode = runProxy(upstream(StubScenario.GARBAGE_THEN_ECHO)) {
            send(initializeFrame())
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            responses = listOf(nextFrame(), nextFrame())
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals(listOf(1, 2), responses.map { it["id"]!!.jsonPrimitive.content.toInt() })
    }

    /** The stub echoes each request under `result.echo` - the only view into what the child received. */
    private fun JsonObject.echo(): JsonObject = this["result"]!!.jsonObject["echo"]!!.jsonObject
}
