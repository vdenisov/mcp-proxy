package org.plukh.mcpproxy.docker

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.plukh.mcpproxy.jsonrpc.decodeFrame

/**
 * The image, actually built and actually run.
 *
 * Self-skipping when there is no docker daemon, so it lives in the normal tier rather than behind
 * `-PliveTests`: it needs no credentials and no network beyond the daemon.
 *
 * The upstream it is pointed at is deliberately unreachable. Reaching a real server from inside a
 * container means host-networking tricks that differ per platform, whereas an unreachable one still
 * proves everything this test is about - the image starts, resolves its config from the mounted
 * volume, routes, opens a session, and turns an upstream failure into a JSON-RPC error rather than
 * a crash.
 */
class DockerImageTest {

    private val image = "mcp-proxy:test"
    private var container: String? = null

    @AfterTest
    fun tearDown() {
        container?.let { docker("rm", "-f", it) }
        docker("rmi", "-f", image)
    }

    private fun docker(vararg args: String, timeoutSeconds: Long = 300): Pair<Int, String> {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timed out: ${args.joinToString(" ")}"
        }
        return process.exitValue() to output
    }

    private fun assumeDocker() {
        val available = runCatching { docker("version", "--format", "{{.Server.Version}}", timeoutSeconds = 20).first == 0 }
            .getOrDefault(false)
        assumeTrue(available, "no docker daemon available")
    }

    private fun distJar(): File? = File("build/dist/mcp-proxy.jar").takeIf { it.exists() }

    @Test
    fun `the image serves an upstream from a mounted config volume and stops gracefully`() = runBlocking {
        assumeDocker()
        val jar = distJar()
        assumeTrue(jar != null, "build/dist/mcp-proxy.jar is missing; run gradlew packageDistribution")

        val (buildStatus, buildOutput) = docker("build", "-t", image, ".")
        assertEquals(0, buildStatus, "docker build failed:\n${buildOutput.takeLast(2000)}")

        val data: Path = Files.createTempDirectory("mcp-proxy-docker-data")
        Files.writeString(data.resolve("svc.yaml"), "upstream:\n  url: http://127.0.0.1:1/mcp\n")
        Files.writeString(
            data.resolve("server.yaml"),
            // 0.0.0.0 inside the container, published to loopback on the host.
            "server:\n  port: 8080\n  bindHost: 0.0.0.0\nupstreams: [svc]\n",
        )

        val (runStatus, runOutput) = docker(
            "run", "-d",
            "-v", "${data.toAbsolutePath()}:/data",
            "-p", "127.0.0.1::8080",
            image,
        )
        assertEquals(0, runStatus, "docker run failed:\n$runOutput")
        val id = runOutput.trim().also { container = it }

        val (portStatus, portOutput) = docker("port", id, "8080/tcp")
        assertEquals(0, portStatus, "could not read the published port:\n$portOutput")
        val hostPort = portOutput.trim().lines().first().substringAfterLast(':').trim()

        val client = HttpClient(CIO)
        try {
            val status = withTimeoutOrNull(90.seconds) {
                while (true) {
                    runCatching { client.get("http://127.0.0.1:$hostPort/").bodyAsText() }
                        .getOrNull()?.let { return@withTimeoutOrNull it }
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            assertTrue(status != null, "the container never served: ${docker("logs", id).second.takeLast(1500)}")
            assertTrue(status!!.contains("svc"), "status page does not list the upstream")

            val response = client.post("http://127.0.0.1:$hostPort/svc/mcp") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"docker-test","version":"1"}}}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Mcp-Session-Id"] != null, "no session id issued")
            val frame = decodeFrame(response.bodyAsText())
            assertTrue(frame.containsKey("error"), "an unreachable upstream should answer with an error frame, got $frame")
        } finally {
            client.close()
        }

        // SIGTERM to a JVM that is PID 1. 143 (128 + SIGTERM) is what a JVM exits with when a signal
        // ends it, and it is the *graceful* outcome: what matters is that the shutdown hook ran to
        // completion first, which the log line below proves.
        val (stopStatus, _) = docker("stop", "-t", "20", id, timeoutSeconds = 60)
        assertEquals(0, stopStatus)
        val exit = docker("inspect", "-f", "{{.State.ExitCode}}", id).second.trim()
        val logs = docker("logs", id).second
        assertEquals("143", exit, "expected a SIGTERM exit, logs:\n${logs.takeLast(1000)}")
        assertTrue(
            logs.contains("Server stopped"),
            "the shutdown hook did not finish before the JVM died, logs:\n${logs.takeLast(1500)}",
        )
    }
}
