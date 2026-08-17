package org.plukh.mcpproxy.live

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.initializeFrame
import org.plukh.mcpproxy.runProxy
import org.plukh.mcpproxy.upstream.StdioUpstreamEndpoint

/**
 * The stdio upstream against a real server, in the shape people actually use it: `docker run -i`.
 *
 * A stub proves the framing; this proves the things a stub cannot - that a real server tolerates our
 * handshake, that a container's startup latency does not trip the session, and that the child is
 * genuinely reclaimed afterwards.
 *
 * Pull the image first (`docker pull ghcr.io/github/github-mcp-server`); a cold pull can outlast the
 * timeout.
 */
class GitHubStdioLiveTest {

    @Test
    fun `a real github-mcp-server under docker round-trips and leaves nothing behind`() {
        Live.assumeDocker()
        val pat = Live.githubPat()
        // Named so the cleanup assertion can look for this exact container rather than guessing from
        // the image, which would trip over anything else the user happens to be running.
        val containerName = "mcp-proxy-live-${System.nanoTime()}"

        val upstream = StdioUpstreamEndpoint(
            command = listOf(
                "docker", "run", "-i", "--rm",
                "--name", containerName,
                // Named without a value, so the token is passed through from the child's environment
                // instead of appearing in the process table.
                "-e", "GITHUB_PERSONAL_ACCESS_TOKEN",
                Live.GITHUB_MCP_IMAGE,
            ),
            extraEnv = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to pat),
        )

        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject
        lateinit var call: JsonObject

        val exitCode = runProxy(upstream = upstream, timeoutMs = Live.TIMEOUT_MS) {
            send(initializeFrame())
            initialize = nextFrame()
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            tools = nextFrame()
            send(Live.SEARCH_REPOSITORIES_CALL)
            call = nextFrame()
            disconnect()
        }

        assertEquals(ExitCodes.OK, exitCode)
        assertTrue(
            initialize.containsKey("result"),
            "initialize failed - is ${Live.GITHUB_PAT_VAR} valid? got: $initialize",
        )

        val toolNames = tools["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.isNotEmpty(), "no tools listed, got: $tools")

        assertToolCallSucceeded(call)

        // Killing the docker CLI does not necessarily stop the container it started, which is how a
        // proxy leaves orphans behind. `--rm` plus the server exiting on stdin EOF should handle it -
        // this is the assertion that says so.
        assertTrue(containerIsGone(containerName), "container $containerName was left running")
    }

    private fun containerIsGone(name: String): Boolean {
        // The container may take a moment to disappear after the CLI exits.
        repeat(20) {
            val listed = ProcessBuilder("docker", "ps", "-a", "--filter", "name=$name", "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor(20, TimeUnit.SECONDS)
                    output
                }
            if (!listed.contains(name)) return true
            Thread.sleep(500)
        }
        return false
    }
}
