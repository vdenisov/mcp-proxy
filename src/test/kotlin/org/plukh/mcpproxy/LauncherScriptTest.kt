package org.plukh.mcpproxy

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A launcher and how to build its command line for a given argument list. */
private typealias Launcher = Pair<String, (List<String>) -> List<String>>

/**
 * The launcher scripts, run as real processes.
 *
 * They are three lines of logic and a quoting minefield, and every one of their failure modes is
 * silent in the place it matters: an argument that loses its quoting, an exit code swallowed by the
 * wrapper, or - worst - a diagnostic on stdout, where the client is reading JSON-RPC frames. None of
 * that is visible from reading them.
 *
 * Skipped when the shadow jar has not been built. The POSIX script additionally needs a `sh`; on
 * Windows the Git/Cygwin one serves, and it is exercised rather than skipped because a script nobody
 * runs is a script nobody knows is broken.
 */
class LauncherScriptTest {

    private val scripts = Path.of("scripts")

    private fun jarBuilt(): Boolean =
        File("build/libs").listFiles()
            ?.any { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") } == true

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    /**
     * A shell that can run the POSIX launcher *and* hand a usable path to the JVM it starts.
     *
     * Absolute paths only: a Windows JVM cannot resolve `/bin/sh`, so probing that name is how the
     * POSIX launcher silently went untested here. Git for Windows' MSYS2 `sh` works because it
     * translates paths on the way to a native `java`. `bash.exe` on the Windows PATH is deliberately
     * *not* used - that is WSL's, and it runs in a filesystem namespace where `C:\...` does not
     * exist, which would fail for reasons that have nothing to do with the launcher.
     */
    private fun sh(): String? {
        val candidates = if (isWindows) {
            listOf(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
                .filterNotNull()
                .flatMap { listOf("$it\\Git\\usr\\bin\\sh.exe", "$it\\Programs\\Git\\usr\\bin\\sh.exe") }
        } else {
            listOf("/bin/sh", "/usr/bin/sh")
        }
        return candidates.firstOrNull { File(it).canExecute() }
    }

    /** `sh` needs forward slashes; `Path.toString()` hands out backslashes on Windows. */
    private fun posix(path: Path): String = path.toAbsolutePath().toString().replace('\\', '/')

    private data class Run(val stdout: String, val stderr: String, val exitCode: Int) {
        val diagnostics: String
            get() = "\n  exit=$exitCode\n  stdout=<${stdout.take(400)}>\n  stderr=<${stderr.take(400)}>"
    }

    /**
     * Both pipes are drained on their own threads and the wait is on the *process*, not on stdout
     * reaching EOF. Reading stdout inline instead would make the timeout unreachable: a launcher that
     * leaves the JVM - or, on Windows, the `cmd.exe` wrapper - alive holding the pipe would hang the
     * build forever, which is exactly the regression the timeout exists to report.
     */
    private fun run(command: List<String>, workingDir: Path? = null, env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(command)
        workingDir?.let { builder.directory(it.toFile()) }
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.use { }

        val stdout = AtomicReference("")
        val stderr = AtomicReference("")
        val readers = listOf(
            Thread { stdout.set(process.inputStream.bufferedReader().readText()) },
            Thread { stderr.set(process.errorStream.bufferedReader().readText()) },
        )
        readers.forEach(Thread::start)

        val exited = process.waitFor(60, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        readers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        if (!exited) fail("launcher did not exit within 60s; stderr=<${stderr.get().take(400)}>")

        return Run(stdout.get(), stderr.get(), process.exitValue())
    }

    /**
     * Both launchers, as a caller would invoke them: the `.cmd` through cmd.exe, the other through sh.
     *
     * Asserts it found something. Every test here iterates this list, so an empty one turns them all
     * green while testing nothing - which is how the POSIX launcher went unexercised the first time,
     * and how it would go unexercised again if [sh]'s paths stop matching a future Git install.
     */
    private fun launchers(): List<Launcher> {
        val found = buildList<Launcher> {
            if (isWindows) {
                add("mcp-proxy.cmd" to { args -> listOf("cmd.exe", "/c", scripts.resolve("mcp-proxy.cmd").toString()) + args })
            }
            sh()?.let { shell ->
                add("mcp-proxy" to { args -> listOf(shell, posix(scripts.resolve("mcp-proxy"))) + args })
            }
        }
        assertTrue(found.isNotEmpty(), "no launcher could be exercised: not Windows and no POSIX sh found")
        return found
    }

    /** The same pair, pointed at scripts copied elsewhere. */
    private fun launchersIn(dir: Path): List<Launcher> {
        val found = buildList<Launcher> {
            if (isWindows) {
                add("mcp-proxy.cmd" to { args -> listOf("cmd.exe", "/c", dir.resolve("mcp-proxy.cmd").toString()) + args })
            }
            sh()?.let { shell ->
                add("mcp-proxy" to { args -> listOf(shell, posix(dir.resolve("mcp-proxy"))) + args })
            }
        }
        assertTrue(found.isNotEmpty(), "no launcher could be exercised: not Windows and no POSIX sh found")
        return found
    }

    @Test
    fun `every launcher runs the jar from a checkout and keeps stdout clean`() {
        if (!jarBuilt()) return

        launchers().forEach { (name, command) ->
            val run = run(command(listOf("--version")))

            assertEquals(ExitCodes.OK, run.exitCode, "$name failed" + run.diagnostics)
            val lines = run.stdout.trim().lines().filter { it.isNotBlank() }
            assertEquals(1, lines.size, "$name put more than the version on stdout" + run.diagnostics)
            assertTrue(lines.single().startsWith("mcp-proxy version"), "$name: unexpected stdout" + run.diagnostics)
        }
    }

    /**
     * The exit code is the whole reason `--check` can gate CI, and a wrapper that reports its own
     * status instead of the proxy's would turn every audit green.
     */
    @Test
    fun `every launcher propagates the proxy's exit code`() {
        if (!jarBuilt()) return

        launchers().forEach { (name, command) ->
            val run = run(command(emptyList())) // no upstream configured

            assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode, "$name swallowed the exit code" + run.diagnostics)
            assertEquals("", run.stdout.trim(), "$name put the config error on stdout" + run.diagnostics)
        }
    }

    /**
     * Regression: `%ERRORLEVEL%` resolves to an environment variable of that name when one exists,
     * and only falls back to the exit code when it does not. A user with `ERRORLEVEL` set therefore
     * got that value back from every run - a `--check` gating CI reporting success on a failed audit.
     * The test that already covers exit codes cannot see it, because it inherits a clean environment.
     */
    @Test
    fun `every launcher propagates the exit code even with ERRORLEVEL set in the environment`() {
        if (!jarBuilt()) return

        launchers().forEach { (name, command) ->
            val run = run(command(emptyList()), env = mapOf("ERRORLEVEL" to "0"))

            assertEquals(ExitCodes.CONFIG_ERROR, run.exitCode, "$name read ERRORLEVEL from the environment" + run.diagnostics)
        }
    }

    /** An argument containing a space must arrive as one argument, not two. */
    @Test
    fun `every launcher forwards quoted arguments intact`() {
        if (!jarBuilt()) return
        val config = Files.createTempDirectory("mcp-proxy-launcher").resolve("local.yaml")
        Files.writeString(config, "upstream:\n  transport: stdio\n  command: [\"no-such-server\"]\n")

        launchers().forEach { (name, command) ->
            val run = run(command(listOf("--check", config.toString(), "--identity-name", "spaced client")))

            assertEquals(ExitCodes.OK, run.exitCode, "$name failed" + run.diagnostics)
            assertTrue(
                run.stdout.contains("identity is spaced client/"),
                "$name mangled an argument containing a space" + run.diagnostics,
            )
        }
    }

    /**
     * The packaged layout - jar renamed `mcp-proxy.jar` and sitting beside the script - is what
     * `packageDistribution` produces and what a user unpacks. The checkout fallback above cannot
     * fail if this lookup is broken, so it gets its own test.
     */
    @Test
    fun `every launcher finds a jar sitting beside it`() {
        if (!jarBuilt()) return
        val installed = Files.createTempDirectory("mcp-proxy-installed")
        val jar = File("build/libs").listFiles()!!
            .first { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }
        Files.copy(jar.toPath(), installed.resolve("mcp-proxy.jar"))
        listOf("mcp-proxy", "mcp-proxy.cmd").forEach {
            Files.copy(scripts.resolve(it), installed.resolve(it))
        }

        launchersIn(installed).forEach { (name, command) ->
            // Run from an unrelated directory: the script must locate the jar from its own path.
            val run = run(command(listOf("--version")), workingDir = Files.createTempDirectory("mcp-proxy-elsewhere"))

            assertEquals(ExitCodes.OK, run.exitCode, "$name did not find the jar beside it" + run.diagnostics)
            assertTrue(run.stdout.contains("mcp-proxy version"), "$name: unexpected stdout" + run.diagnostics)
        }
    }

    /**
     * `shadowJar` never cleans, so `build/libs` accumulates a jar per version bump. Picking whichever
     * the glob happens to yield last would run a stale build with no hint that it had - and version
     * order is not glob order, so 1.9.0 beats 1.10.0.
     */
    @Test
    fun `an ambiguous checkout build refuses rather than guessing`() {
        if (!jarBuilt()) return
        val checkout = Files.createTempDirectory("mcp-proxy-ambiguous")
        val scriptDir = Files.createDirectories(checkout.resolve("scripts"))
        val libs = Files.createDirectories(checkout.resolve("build").resolve("libs"))
        listOf("mcp-proxy", "mcp-proxy.cmd").forEach { Files.copy(scripts.resolve(it), scriptDir.resolve(it)) }
        val jar = File("build/libs").listFiles()!!
            .first { it.name.startsWith("mcp-proxy") && it.name.endsWith(".jar") && !it.name.contains("thin") }
        Files.copy(jar.toPath(), libs.resolve("mcp-proxy-1.9.0.jar"))
        Files.copy(jar.toPath(), libs.resolve("mcp-proxy-1.10.0.jar"))

        launchersIn(scriptDir).forEach { (name, command) ->
            val run = run(command(listOf("--version")))

            assertEquals(1, run.exitCode, "$name silently picked one of two jars" + run.diagnostics)
            assertEquals("", run.stdout.trim(), "$name put its error on stdout" + run.diagnostics)
            assertTrue(run.stderr.contains("several jars"), "$name: unhelpful error" + run.diagnostics)
        }
    }

    @Test
    fun `a launcher pointed at a missing MCP_PROXY_JAR says so itself`() {
        val missing = Files.createTempDirectory("mcp-proxy-missing").resolve("nope.jar")

        launchers().forEach { (name, command) ->
            val run = run(command(listOf("--version")), env = mapOf("MCP_PROXY_JAR" to missing.toString()))

            assertEquals(1, run.exitCode, "$name did not reject a missing jar" + run.diagnostics)
            assertEquals("", run.stdout.trim(), "$name put its error on stdout" + run.diagnostics)
            assertTrue(run.stderr.contains("does not exist"), "$name deferred to java's message" + run.diagnostics)
        }
    }

    @Test
    fun `a launcher with no jar to run fails on stderr, not stdout`() {
        val empty = Files.createTempDirectory("mcp-proxy-nojar")
        listOf("mcp-proxy", "mcp-proxy.cmd").forEach {
            Files.copy(scripts.resolve(it), empty.resolve(it))
        }

        launchersIn(empty).forEach { (name, command) ->
            val run = run(command(listOf("--version")))

            assertEquals(1, run.exitCode, "$name should fail when there is no jar" + run.diagnostics)
            assertEquals("", run.stdout.trim(), "$name put its error on stdout" + run.diagnostics)
            assertTrue(run.stderr.contains("mcp-proxy.jar"), "$name: unhelpful error" + run.diagnostics)
        }
    }
}
