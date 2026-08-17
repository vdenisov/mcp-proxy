package org.plukh.mcpproxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.server.StubHttpMcpUpstream

/**
 * Server mode through the packaged jar: the only tier that proves `--serve` works as a *program* -
 * config resolved from `$MCP_PROXY_HOME`, listener bound, routes answering, process stopping when
 * asked.
 *
 * Skipped when the shadow jar has not been built, like the other jar-tier tests.
 */
class ServeModeCliTest {

    private var process: Process? = null
    private val stubs = mutableListOf<StubHttpMcpUpstream>()

    @AfterTest
    fun tearDown() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        stubs.forEach { runCatching { it.stop() } }
    }

    private fun jar(): File? =
        File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun stub(): StubHttpMcpUpstream = StubHttpMcpUpstream().also { it.start(); stubs += it }

    private class Run(val process: Process, val stderr: AtomicReference<String>)

    private fun launch(home: Path, vararg args: String): Run {
        val jar = jar() ?: fail("shadow jar not found")
        val builder = ProcessBuilder(
            listOf(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-jar",
                jar.absolutePath,
            ) + args,
        )
        builder.environment()["MCP_PROXY_HOME"] = home.toString()
        val started = builder.start()
        process = started
        started.outputStream.close()

        // Both pipes drained on their own threads: a server that fills one would otherwise wedge,
        // and its diagnostics are the only clue when a start-up assertion fails.
        val stderr = AtomicReference("")
        Thread { stderr.set(started.errorStream.bufferedReader().readText()) }.start()
        Thread { started.inputStream.bufferedReader().readText() }.start()
        return Run(started, stderr)
    }

    private suspend fun awaitListening(client: HttpClient, url: String, run: Run): String? =
        withTimeoutOrNull(60.seconds) {
            while (true) {
                if (!run.process.isAlive) return@withTimeoutOrNull null
                val body = runCatching { client.get(url).bodyAsText() }.getOrNull()
                if (body != null) return@withTimeoutOrNull body
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    @Test
    fun `the packaged jar serves configured upstreams over HTTP`() = runBlocking {
        if (jar() == null) return@runBlocking
        val upstream = stub()
        val home = Files.createTempDirectory("mcp-proxy-serve-home")
        val port = freePort()
        Files.writeString(home.resolve("svc.yaml"), "upstream:\n  url: ${upstream.url}\n")
        Files.writeString(home.resolve("server.yaml"), "server:\n  port: $port\nupstreams: [svc]\n")

        val run = launch(home, "--serve", "server")
        val client = HttpClient(CIO)
        try {
            val status = awaitListening(client, "http://127.0.0.1:$port/", run)
            assertTrue(status != null, "the server never came up; stderr=${run.stderr.get().take(600)}")
            assertTrue(status!!.contains("svc"), "status page does not list the upstream: $status")

            val response = client.post("http://127.0.0.1:$port/svc/mcp") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cli-test","version":"1"}}}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Mcp-Session-Id"] != null, "no session id issued")
            assertTrue(decodeFrame(response.bodyAsText()).containsKey("result"))

            // SIGTERM equivalent: the shutdown hook is the whole graceful path in a container.
            run.process.destroy()
            assertTrue(run.process.waitFor(30, TimeUnit.SECONDS), "the server did not stop when asked")
        } finally {
            client.close()
        }
    }

    @Test
    fun `a per-upstream config given to --serve is refused readably`() = runBlocking {
        if (jar() == null) return@runBlocking
        val home = Files.createTempDirectory("mcp-proxy-serve-home")
        Files.writeString(home.resolve("svc.yaml"), "upstream:\n  url: https://example.com/mcp\n")

        val run = launch(home, "--serve", "svc")
        assertTrue(run.process.waitFor(60, TimeUnit.SECONDS), "the process should have exited")

        assertEquals(ExitCodes.CONFIG_ERROR, run.process.exitValue())
        assertTrue(
            run.stderr.get().contains("upstream"),
            "the error should name the unexpected key; stderr=${run.stderr.get().take(400)}",
        )
    }

    @Test
    fun `--serve with a per-upstream override is a usage error`() = runBlocking {
        if (jar() == null) return@runBlocking
        val home = Files.createTempDirectory("mcp-proxy-serve-home")
        Files.writeString(home.resolve("server.yaml"), "server:\n  port: ${freePort()}\nupstreams: [svc]\n")

        val run = launch(home, "--serve", "server", "--upstream-url", "https://example.com/mcp")
        assertTrue(run.process.waitFor(60, TimeUnit.SECONDS), "the process should have exited")

        // 1, not 64: clikt's UsageError carries statusCode 1, whatever the docs used to claim.
        assertEquals(1, run.process.exitValue())
        assertTrue(run.stderr.get().contains("--upstream-url"), "stderr=${run.stderr.get().take(400)}")
    }
}
