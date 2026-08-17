package org.plukh.mcpproxy

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.upstream.StubScenario
import org.plukh.mcpproxy.upstream.stubCommand

/**
 * The stdio-upstream half of the stdout purity guarantee, run against the packaged jar.
 *
 * A spawned server is a new way to pollute stdout - inheriting its stdout, or logging its stderr to
 * the wrong stream, would corrupt the protocol the moment the server printed a banner. Only an
 * out-of-process run can prove it did not, for the reasons [StdoutPurityTest] documents.
 */
class StdioUpstreamPurityTest {

    private fun jar(): File? =
        File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }

    /**
     * Streams are drained on their own threads and the timeout covers the drain, not just the exit.
     * Reading one stream to completion before the other deadlocks against any child that fills a pipe
     * buffer or outlives the read, and here there are two processes that could do it.
     */
    private fun runProxy(configPath: File, stdin: String, timeoutSeconds: Long = 60): Triple<String, String, Int> {
        val jar = jar() ?: fail("shadow jar not found")
        val process = ProcessBuilder(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-jar",
            jar.path,
            configPath.path,
        ).start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val drains = listOf(
            Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } },
            Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } },
        ).onEach { it.isDaemon = true; it.start() }

        process.outputStream.use { it.write(stdin.toByteArray()) }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("proxy did not exit within ${timeoutSeconds}s")
        }
        drains.forEach { it.join(5_000) }
        return Triple(stdout.toString(), stderr.toString(), process.exitValue())
    }

    /** Single-quoted YAML: Windows paths are full of backslashes, which double-quoted style would eat. */
    private fun yamlQuote(value: String) = "'" + value.replace("'", "''") + "'"

    private fun writeConfig(): File {
        val command = stubCommand(StubScenario.STDERR_CHATTER).joinToString(", ") { yamlQuote(it) }
        val classpath = yamlQuote(System.getProperty("java.class.path"))
        val file = Files.createTempFile("mcp-proxy-stdio", ".yaml").toFile()
        file.writeText(
            """
            upstream:
              transport: stdio
              command: [$command]
              env:
                CLASSPATH: $classpath
            logging:
              level: DEBUG
            """.trimIndent(),
        )
        file.deleteOnExit()
        return file
    }

    @Test
    fun `a stdio upstream session keeps stdout free of everything but frames`() {
        if (jar() == null) return

        val (stdout, stderr, exitCode) = runProxy(
            writeConfig(),
            stdin = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1"}}}""" + "\n",
        )

        val diagnostics = "\n  exit=$exitCode\n  stdout=<${stdout.take(400)}>\n  stderr=<${stderr.take(400)}>"
        stdout.trim().lines().filter { it.isNotBlank() }.forEach { line ->
            val frame = runCatching { decodeFrame(line) }
                .getOrElse { fail("non-protocol output on stdout: <$line>$diagnostics") }
            assertTrue(frame.containsKey("jsonrpc"), "stdout line is not a JSON-RPC frame: <$line>")
        }
        // The child really was spawned, so the purity above is about a session that happened. Whether
        // its stderr had time to reach the log before shutdown is a race, and is asserted
        // deterministically in StdioUpstreamEndpointTest instead.
        assertTrue(stderr.contains("Spawning upstream process"), "no child was spawned$diagnostics")
        assertEquals(ExitCodes.OK, exitCode, "unexpected exit$diagnostics")
    }
}
