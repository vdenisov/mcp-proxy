package org.plukh.mcpproxy

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.plukh.mcpproxy.config.ConfigException

class PathsTest {

    private val userHome = Path.of(System.getProperty("user.home"))

    /** Absolute on every platform - `/opt/mcp` is merely root-relative on Windows, not absolute. */
    private val absoluteHome = userHome.resolve("proxy-home-under-test")

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    // --- proxy home ---

    @Test
    fun `the default home is under the user home`() {
        assertEquals(userHome.resolve(".mcp-proxy"), proxyHome { null })
    }

    @Test
    fun `MCP_PROXY_HOME overrides the default`() {
        assertEquals(absoluteHome, proxyHome(env("MCP_PROXY_HOME" to absoluteHome.toString())))
    }

    @Test
    fun `a tilde in MCP_PROXY_HOME is expanded`() {
        assertEquals(userHome.resolve("proxy-home"), proxyHome(env("MCP_PROXY_HOME" to "~/proxy-home")))
    }

    /**
     * `Path.of("")` is the working directory, so a blank override that were honoured would put the
     * config home wherever the MCP client happened to start the proxy - reinstating exactly the
     * CWD-relative lookup [org.plukh.mcpproxy.config.ConfigResolver] refuses to do.
     */
    @Test
    fun `a blank MCP_PROXY_HOME counts as unset, not as the working directory`() {
        assertEquals(userHome.resolve(".mcp-proxy"), proxyHome(env("MCP_PROXY_HOME" to "")))
        assertEquals(userHome.resolve(".mcp-proxy"), proxyHome(env("MCP_PROXY_HOME" to "   ")))
    }

    /**
     * A relative home hands config selection back to the working directory, which the MCP client
     * chooses - the shadowing route to an attacker-supplied `upstream.command` that config
     * resolution exists to close. Absolutizing would resolve against that same directory, so the
     * only honest answer is to refuse.
     */
    @Test
    fun `a relative MCP_PROXY_HOME is rejected rather than silently absolutized`() {
        listOf("configs", ".", "./configs", "../mcp").forEach { value ->
            val e = assertFailsWith<ConfigException>("$value should be rejected") {
                proxyHome(env("MCP_PROXY_HOME" to value))
            }
            assertContains(e.message!!, value)
        }
    }

    // --- token directory ---

    @Test
    fun `tokens default to a subdirectory of the proxy home`() {
        val env = env("MCP_PROXY_HOME" to absoluteHome.toString())

        assertEquals(absoluteHome.resolve("tokens"), tokenDir(null, env))
        assertEquals(userHome.resolve(".mcp-proxy").resolve("tokens"), tokenDir(null) { null })
    }

    @Test
    fun `an explicit absolute tokenDir wins over the home and is tilde-expanded`() {
        val env = env("MCP_PROXY_HOME" to absoluteHome.toString())
        val elsewhere = userHome.resolve("elsewhere-tokens")

        assertEquals(userHome.resolve("tokens"), tokenDir("~/tokens", env))
        assertEquals(elsewhere, tokenDir(elsewhere.toString(), env))
    }

    @Test
    fun `a relative tokenDir lands in the home, not the working directory`() {
        assertEquals(
            absoluteHome.resolve("creds"),
            tokenDir("creds", env("MCP_PROXY_HOME" to absoluteHome.toString())),
        )
    }

    // --- resolving configured paths ---

    /**
     * The working directory belongs to whoever spawned the proxy, so it is never the right base for a
     * path the user wrote in a config: `logging.file: proxy.log` must not scatter one log file per
     * project directory the client happens to start us from.
     */
    @Test
    fun `a relative configured path resolves under the home`() {
        assertEquals(absoluteHome.resolve("proxy.log"), resolveUnderHome("proxy.log", absoluteHome))
        assertEquals(absoluteHome.resolve("logs").resolve("proxy.log"), resolveUnderHome("logs/proxy.log", absoluteHome))
    }

    @Test
    fun `absolute and tilde configured paths are left where they point`() {
        val absolute = userHome.resolve("var").resolve("proxy.log")

        assertEquals(userHome.resolve("proxy.log"), resolveUnderHome("~/proxy.log", absoluteHome))
        assertEquals(absolute, resolveUnderHome(absolute.toString(), absoluteHome))
    }
}
