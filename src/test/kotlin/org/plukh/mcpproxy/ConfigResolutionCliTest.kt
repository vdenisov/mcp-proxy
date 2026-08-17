package org.plukh.mcpproxy

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Config resolution through the packaged jar, which is the only tier that can set `MCP_PROXY_HOME`:
 * a JVM cannot mutate its own environment, so everything below this level has to inject the lookup.
 *
 * Skipped when the shadow jar has not been built, like [StdoutPurityTest].
 */
class ConfigResolutionCliTest {

    private fun jar(): File? =
        File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }

    private data class Run(val stdout: String, val stderr: String, val exitCode: Int) {
        val diagnostics: String
            get() = "\n  exit=$exitCode\n  stdout=<${stdout.take(600)}>\n  stderr=<${stderr.take(600)}>"
    }

    private fun runProxy(args: List<String>, home: Path, workingDir: Path? = null, timeoutSeconds: Long = 60): Run {
        val jar = jar() ?: fail("shadow jar not found")
        val builder = ProcessBuilder(
            listOf(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-jar",
                // Absolute, because the working directory is a variable in these tests.
                jar.absolutePath,
            ) + args,
        )
        builder.environment()["MCP_PROXY_HOME"] = home.toString()
        workingDir?.let { builder.directory(it.toFile()) }
        val process = builder.start()

        process.outputStream.use { }
        // Both pipes are drained concurrently. Draining stdout to EOF first would let a child that
        // fills the stderr buffer block on its write while this thread waits on stdout - a deadlock
        // that surfaces as the timeout below, which reads as "the proxy hung" and is not that.
        var stderrText = ""
        val stderrReader = Thread { stderrText = process.errorStream.bufferedReader().readText() }
        stderrReader.start()
        val stdout = process.inputStream.bufferedReader().readText()
        stderrReader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("proxy did not exit within ${timeoutSeconds}s")
        }
        return Run(stdout, stderrText, process.exitValue())
    }

    private fun home(): Path = Files.createTempDirectory("mcp-proxy-home")

    @Test
    fun `a name that matches nothing lists every candidate tried`() {
        if (jar() == null) return
        val home = home()

        val run = runProxy(listOf("nope"), home)

        assertEquals("", run.stdout.trim(), "config errors must not touch the protocol stream")
        assertTrue(run.stderr.contains("nope"), "the name the user typed is missing" + run.diagnostics)
        listOf("nope", "nope.yaml", "nope.yml").forEach {
            assertTrue(
                run.stderr.contains(home.resolve(it).toString()),
                "candidate $it not listed" + run.diagnostics,
            )
        }
        assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode, "wrong exit code" + run.diagnostics)
    }

    @Test
    fun `a bare name resolves from MCP_PROXY_HOME and the resolved path is logged`() {
        if (jar() == null) return
        val home = home()
        // stdio, because --check never spawns the child - nothing is executed by this test.
        val config = home.resolve("local.yaml")
        Files.writeString(config, "upstream:\n  transport: stdio\n  command: [\"no-such-server\"]\n")

        val run = runProxy(listOf("--check", "local"), home)

        assertEquals(ExitCodes.OK, run.exitCode, "audit should pass" + run.diagnostics)
        assertTrue(
            run.stderr.contains(config.toAbsolutePath().toString()),
            "the resolved config path was not logged" + run.diagnostics,
        )
        assertTrue(run.stdout.contains("leak audit"), "no audit report" + run.diagnostics)
    }

    /**
     * The shadowing attack this feature is shaped around: a config sitting in the process's working
     * directory must be invisible to a bare name. MCP clients choose that directory (Claude Code uses
     * the project directory), and a config can name any `upstream.command` to spawn.
     */
    @Test
    fun `a working-directory file is not found by a bare name`() {
        if (jar() == null) return
        val home = home()
        val hostileCwd = Files.createTempDirectory("mcp-proxy-hostile-cwd")
        Files.writeString(
            hostileCwd.resolve("linear.yaml"),
            "upstream:\n  url: https://shadow.example.com/mcp\n",
        )

        val run = runProxy(listOf("--check", "linear.yaml"), home, workingDir = hostileCwd)

        assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode, "the working-directory config was loaded" + run.diagnostics)
        assertFalse(run.stdout.contains("shadow.example.com"), "the working-directory config was loaded" + run.diagnostics)
        // ...while the explicit relative form still reaches it, which is the escape hatch.
        val explicit = runProxy(listOf("--check", "./linear.yaml"), home, workingDir = hostileCwd)
        assertTrue(
            explicit.stdout.contains("leak audit") || explicit.stderr.contains("shadow.example.com"),
            "a path-shaped argument should still resolve against the working directory" + explicit.diagnostics,
        )
    }

    /**
     * A path typed at a shell prompt means what the shell means by it. Config *file* values resolve
     * under the proxy home - the working directory belongs to whoever spawned us - but that rule must
     * not reach back and relocate an argument the user typed with a working directory in mind.
     */
    @Test
    fun `a relative --log-file lands in the working directory, not the home`() {
        if (jar() == null) return
        val home = home()
        val workingDir = Files.createTempDirectory("mcp-proxy-cwd")
        Files.writeString(home.resolve("local.yaml"), "upstream:\n  transport: stdio\n  command: [\"no-such-server\"]\n")

        runProxy(listOf("--check", "local", "--log-file", "typed.log"), home, workingDir = workingDir)

        assertTrue(Files.exists(workingDir.resolve("typed.log")), "the log file did not land in the working directory")
        assertFalse(Files.exists(home.resolve("typed.log")), "the typed path was relocated into the home")
    }

    @Test
    fun `a relative logging file in the config lands in the home`() {
        if (jar() == null) return
        val home = home()
        val workingDir = Files.createTempDirectory("mcp-proxy-cwd")
        Files.writeString(
            home.resolve("local.yaml"),
            "upstream:\n  transport: stdio\n  command: [\"no-such-server\"]\nlogging:\n  file: configured.log\n",
        )

        runProxy(listOf("--check", "local"), home, workingDir = workingDir)

        assertTrue(Files.exists(home.resolve("configured.log")), "the configured log file did not land in the home")
        assertFalse(Files.exists(workingDir.resolve("configured.log")), "the config value followed the working directory")
    }

    @Test
    fun `a relative MCP_PROXY_HOME is refused with a readable error`() {
        if (jar() == null) return
        val workingDir = Files.createTempDirectory("mcp-proxy-cwd")

        val run = runProxy(listOf("linear"), Path.of("configs"), workingDir = workingDir)

        assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode, "a relative home must not be honoured" + run.diagnostics)
        assertTrue(run.stderr.contains("MCP_PROXY_HOME"), "the error must name the variable" + run.diagnostics)
        assertEquals("", run.stdout.trim())
    }

    @Test
    fun `the login hint echoes the name as typed, not the resolved path`() {
        if (jar() == null) return
        val home = home()
        Files.writeString(
            home.resolve("svc.yaml"),
            """
            upstream:
              url: http://127.0.0.1:1/mcp
              oauth: {}
            """.trimIndent() + "\n",
        )

        // An empty home means an empty token store, so the audit reaches its auth-required verdict
        // without any network round: --check is non-interactive, so a missing token fails outright.
        val run = runProxy(listOf("--check", "svc"), home)

        assertEquals(ExitCodes.AUTH_REQUIRED, run.exitCode, "expected an auth-required verdict" + run.diagnostics)
        assertTrue(run.stdout.contains("--login svc"), "hint is not copy-pasteable" + run.diagnostics)
        assertFalse(
            run.stdout.contains("svc.yaml"),
            "the hint leaked the resolved path instead of the argument" + run.diagnostics,
        )
    }
}
