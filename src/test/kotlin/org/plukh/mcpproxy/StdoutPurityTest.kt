package org.plukh.mcpproxy

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.plukh.mcpproxy.jsonrpc.decodeFrame

/**
 * Guards the one invariant whose violation is both catastrophic and silent: stdout must carry
 * protocol frames and nothing else. A single stray `println` - ours, a library's, or a future
 * dependency's - corrupts the stream and the client sees a protocol error it cannot diagnose.
 *
 * This has to run out-of-process. In-process the test would be asserting against our own
 * `System.setOut` redirect rather than the real file descriptor, which is exactly the thing that
 * could be wrong. It is also the only way to observe initialisation-time output, such as the banner
 * kotlin-logging prints to stdout before any of our code runs.
 *
 * Skipped when the shadow jar has not been built; `gradlew build` produces it.
 */
class StdoutPurityTest {

    private val ANSI = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

    private fun jar(): File? =
        File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }

    private data class Run(val stdout: String, val stderr: String, val exitCode: Int) {
        /** Failures here are opaque without the child's own output, so every message carries it. */
        val diagnostics: String
            get() = "\n  exit=$exitCode\n  stdout=<${stdout.take(400)}>\n  stderr=<${stderr.take(400)}>"
    }

    private fun runProxy(args: List<String>, stdin: String = "", timeoutSeconds: Long = 60): Run {
        val jar = jar() ?: fail("shadow jar not found")
        val process = ProcessBuilder(
            listOf(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java", "-jar", jar.path) + args,
        ).start()

        process.outputStream.use { it.write(stdin.toByteArray()) }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("proxy did not exit within ${timeoutSeconds}s")
        }
        return Run(stdout, stderr, process.exitValue())
    }

    @Test
    fun `a config error puts nothing on stdout`() {
        if (jar() == null) return
        val run = runProxy(emptyList()) // no upstream configured

        assertEquals("", run.stdout.trim(), "config errors must go to stderr, not the protocol stream")
        assertTrue(run.stderr.contains("upstream.url is required"), "missing config error" + run.diagnostics)
        assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode)
    }

    @Test
    fun `every stdout line during a session is a well-formed JSON-RPC frame`() {
        if (jar() == null) return
        // Points at a port nothing is listening on, so the upstream fails and the proxy has to log.
        // Whatever it logs must not reach stdout; the only stdout content may be the error frame.
        val run = runProxy(
            listOf("--upstream-url", "http://127.0.0.1:1/mcp", "--log-level", "DEBUG"),
            stdin = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}""" + "\n",
        )

        val lines = run.stdout.trim().lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            val frame = runCatching { decodeFrame(line) }
                .getOrElse { fail("non-protocol output on stdout: <$line>") }
            assertTrue(frame.containsKey("jsonrpc"), "stdout line is not a JSON-RPC frame: <$line>")
        }
        // The logging really did happen - it just went to the right stream.
        assertTrue(run.stderr.isNotBlank(), "expected diagnostics on stderr")
        assertEquals(ExitCodes.OK, run.exitCode)
    }

    @Test
    fun `stdin EOF exits promptly and cleanly`() {
        if (jar() == null) return
        val run = runProxy(
            listOf("--upstream-url", "http://127.0.0.1:1/mcp"),
            stdin = "",
            timeoutSeconds = 30,
        )
        assertEquals(ExitCodes.OK, run.exitCode)
    }

    @Test
    fun `--help and --version go to stdout so they can be piped`() {
        if (jar() == null) return

        // Styling is stripped first. Clikt colourises through mordant, whose terminal detection in
        // the packaged jar does not reliably notice that stdout is a pipe, so the help text may or
        // may not arrive wrapped in ANSI escapes. Which stream it lands on is the invariant here;
        // whether it is coloured is not.
        val help = runProxy(listOf("--help"))
        assertTrue(help.stdout.stripAnsi().contains("Usage: mcp-proxy"), "--help must reach stdout" + help.diagnostics)

        val version = runProxy(listOf("--version"))
        assertTrue(version.stdout.stripAnsi().contains("mcp-proxy version"), "--version must reach stdout")
    }

    /**
     * Whether the child emits colour depends on mordant's terminal detection, so the assertions above
     * may never exercise the stripping. This pins it directly - the previous form matched only the
     * CSI body and left the ESC byte sitting between the words, which no `contains` would survive.
     */
    @Test
    fun `ansi styling is stripped, escape byte and all`() {
        assertEquals("Usage: mcp-proxy", "\u001B[38;2;229;192;123mUsage:\u001B[39m mcp-proxy".stripAnsi())
        assertEquals("plain [bracketed] text", "plain [bracketed] text".stripAnsi())
    }

    /**
     * Regression: `--help` and `--version` run *without* [Stdio.lockdown], so anything a library
     * prints to stdout during initialization lands on the real FD1 ahead of the output being piped.
     * kotlin-logging announces its logger factory there the moment it initializes, and it initializes
     * with the first logger created - which a top-level `val` in Cli.kt made happen during class
     * init, before either flag was handled. The `contains` assertions above cannot see the extra
     * line, so this asserts on the whole stream.
     */
    @Test
    fun `info-only flags emit nothing but their own output on stdout`() {
        if (jar() == null) return

        val version = runProxy(listOf("--version"))
        val versionLines = version.stdout.stripAnsi().trim().lines().filter { it.isNotBlank() }
        assertEquals(1, versionLines.size, "stdout carried more than the version" + version.diagnostics)
        assertTrue(
            versionLines.single().startsWith("mcp-proxy version"),
            "stdout carried something besides the version" + version.diagnostics,
        )

        val help = runProxy(listOf("--help"))
        assertTrue(
            help.stdout.stripAnsi().trimStart().startsWith("Usage: mcp-proxy"),
            "stdout carried something before the help text" + help.diagnostics,
        )
    }

    private fun String.stripAnsi(): String = ANSI.replace(this, "")
}
