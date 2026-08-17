package org.plukh.mcpproxy.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Resolution is pure by design (see [ConfigResolver]), so almost everything here runs without a
 * filesystem - the `isRegularFile` predicate is the seam. The exception is the last section, which
 * exercises the real predicate because "a directory must not shadow the yaml beside it" is precisely
 * the wiring a fake predicate cannot see.
 */
class ConfigResolverTest {

    private val home: Path = Path.of("/proxy-home")

    /** Records what was probed, so tests can assert on paths that were *not* tried. */
    private class Probes(private val present: Set<Path> = emptySet()) : (Path) -> Boolean {
        val seen = mutableListOf<Path>()

        override fun invoke(path: Path): Boolean {
            // add, not +=: Path is itself an Iterable<Path>, so `seen += path` resolves to
            // List.plus(Iterable) and fails to compile against a val.
            seen.add(path)
            return path in present
        }
    }

    // --- candidates ---

    @Test
    fun `candidates are the literal name, then yaml, then yml`() {
        assertEquals(
            listOf(home.resolve("linear"), home.resolve("linear.yaml"), home.resolve("linear.yml")),
            ConfigResolver.configCandidates("linear", home),
        )
    }

    @Test
    fun `a dotted name is tried literally first and never split`() {
        assertEquals(
            listOf(home.resolve("mcp.linear"), home.resolve("mcp.linear.yaml"), home.resolve("mcp.linear.yml")),
            ConfigResolver.configCandidates("mcp.linear", home),
        )
    }

    // --- bare names ---

    @Test
    fun `the first candidate that is a regular file wins`() {
        val probes = Probes(setOf(home.resolve("linear.yaml")))

        assertEquals(home.resolve("linear.yaml"), ConfigResolver.resolve("linear", home, probes))
        assertEquals(listOf(home.resolve("linear"), home.resolve("linear.yaml")), probes.seen)
    }

    /**
     * The security property of the whole feature: a bare name must never be looked up relative to the
     * working directory, which an MCP client chooses and a hostile repository can populate. A config
     * can name an arbitrary `upstream.command`, so a shadowing `linear.yaml` would be code execution.
     */
    @Test
    fun `a bare name is never looked up outside the home`() {
        val probes = Probes()

        assertFailsWith<ConfigException> { ConfigResolver.resolve("linear", home, probes) }

        assertTrue(probes.seen.isNotEmpty(), "nothing was probed at all")
        probes.seen.forEach { assertTrue(it.startsWith(home), "probed outside the home: $it") }
    }

    @Test
    fun `not found lists every candidate tried`() {
        val e = assertFailsWith<ConfigException> { ConfigResolver.resolve("linear", home, Probes()) }

        assertContains(e.message!!, "linear")
        ConfigResolver.configCandidates("linear", home).forEach {
            assertContains(e.message!!, it.toAbsolutePath().toString())
        }
    }

    @Test
    fun `blank argument is rejected`() {
        assertFailsWith<ConfigException> { ConfigResolver.resolve("", home, Probes()) }
        assertFailsWith<ConfigException> { ConfigResolver.resolve("   ", home, Probes()) }
    }

    // --- path-shaped arguments ---

    @Test
    fun `path-shaped arguments bypass the home entirely`() {
        listOf("./linear.yaml", "configs/linear.yaml", "configs\\linear.yaml", "C:\\configs\\linear.yaml", "C:linear.yaml")
            .forEach { arg ->
                val probes = Probes()
                assertEquals(Path.of(arg), ConfigResolver.resolve(arg, home, probes), "wrong path for $arg")
                assertTrue(probes.seen.isEmpty(), "$arg probed the home: ${probes.seen}")
            }
    }

    @Test
    fun `only separator-free names are treated as bare`() {
        listOf("./l.yaml", "configs/x.yaml", "configs\\x.yaml", "C:\\x.yaml", "C:x.yaml", "~", "~/x.yaml", "~\\x.yaml")
            .forEach { assertTrue(ConfigResolver.isPathShaped(it), "$it should be path-shaped") }

        listOf("linear", "linear.yaml", "mcp.linear").forEach {
            assertTrue(!ConfigResolver.isPathShaped(it), "$it should be a bare name")
        }
    }

    @Test
    fun `a tilde in the argument is expanded`() {
        val probes = Probes()
        val userHome = Path.of(System.getProperty("user.home"))

        assertEquals(userHome.resolve("x.yaml"), ConfigResolver.resolve("~/x.yaml", home, probes))
        assertEquals(userHome, ConfigResolver.resolve("~", home, probes))
        assertTrue(probes.seen.isEmpty())
    }

    // --- against a real filesystem ---

    @Test
    fun `a directory named like the config does not shadow the yaml beside it`() {
        val realHome = Files.createTempDirectory("mcp-proxy-home")
        Files.createDirectory(realHome.resolve("linear"))
        val yaml = Files.createFile(realHome.resolve("linear.yaml"))

        // Default predicate on purpose: with Files::exists the directory would win.
        assertEquals(yaml, ConfigResolver.resolve("linear", realHome))
    }

    @Test
    fun `a bare name resolves to a real file in the home`() {
        val realHome = Files.createTempDirectory("mcp-proxy-home")
        val yaml = Files.createFile(realHome.resolve("linear.yml"))

        assertEquals(yaml, ConfigResolver.resolve("linear", realHome))
    }
}
