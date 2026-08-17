package org.plukh.mcpproxy.live

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The TypeScript SDK's reference OAuth server as an external oracle. Its `--oauth-strict` mode
 * enforces the RFC 8707 `resource` binding - a token whose audience is not the MCP server URL is
 * rejected - which makes it the one test that proves our `resource` parameter handling against an
 * implementation we did not write.
 *
 * Needs a typescript-sdk checkout (env `MCP_PROXY_TEST_TS_SDK_DIR`) with dependencies installed,
 * and `npx` on the PATH. Skips otherwise.
 */
class TsSdkOAuthLiveTest {

    private var serverProcess: Process? = null

    @AfterTest
    fun tearDown() {
        serverProcess?.let { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the full flow works against the reference server in strict resource mode`() {
        val sdkDir = System.getenv("MCP_PROXY_TEST_TS_SDK_DIR")
        assumeTrue(!sdkDir.isNullOrBlank(), "MCP_PROXY_TEST_TS_SDK_DIR is not set")
        assumeTrue(File(sdkDir, "src/examples/server/simpleStreamableHttp.ts").exists(), "SDK example not found in $sdkDir")

        startReferenceServer(sdkDir)

        val tokenDir = Files.createTempDirectory("mcp-proxy-tssdk")
        val config = ProxyConfig(
            identity = IdentityConfig(name = "mcp-proxy-live-test", version = "1.0.0"),
            upstream = UpstreamConfig(
                url = "http://localhost:3000/mcp",
                oauth = OAuthConfig(tokenDir = tokenDir.toString(), interactiveWaitSeconds = 50),
            ),
        )

        // The demo AS auto-approves, so a redirect-following GET is a complete "browser".
        val browser: (String) -> Unit = { url ->
            Thread {
                runBlocking { HttpClient(CIO) { followRedirects = true }.use { it.get(url) } }
            }.start()
        }

        lateinit var initialize: JsonObject
        lateinit var tools: JsonObject

        val upstream = buildHttpUpstream(config, openBrowser = browser, announceUrl = {})
        val exitCode = try {
            runProxy(upstream = upstream.endpoint, identity = config.identity, timeoutMs = Live.TIMEOUT_MS) {
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
        assertTrue(
            initialize.containsKey("result"),
            "initialize failed against the reference server: $initialize",
        )
        assertTrue(
            tools["result"]!!.jsonObject["tools"] != null,
            "tools/list failed - with --oauth-strict this is where a missing resource param shows up: $tools",
        )
    }

    private fun startReferenceServer(sdkDir: String) {
        val process = ProcessBuilder(
            "cmd", "/c", "npx", "tsx", "src/examples/server/simpleStreamableHttp.ts", "--oauth", "--oauth-strict",
        )
            .directory(File(sdkDir))
            .redirectErrorStream(true)
            .start()
        serverProcess = process

        // tsx compiles on first run; give it a while, but fail clearly if it never comes up.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().readText().take(2000)
                assumeTrue(false, "reference server exited at startup: $output")
            }
            if (portOpen(3000) && portOpen(3001)) return
            Thread.sleep(500)
        }
        process.destroyForcibly()
        assumeTrue(false, "reference server did not come up on :3000/:3001 within 90s")
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)
}
