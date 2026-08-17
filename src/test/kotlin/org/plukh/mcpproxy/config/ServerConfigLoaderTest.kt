package org.plukh.mcpproxy.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigLoaderTest {

    private val noEnv: (String) -> String? = { null }

    private fun writeConfig(content: String): Path =
        Files.createTempFile("mcp-proxy-server-test", ".yaml").also { Files.writeString(it, content.trimIndent()) }

    private fun minimal() = writeConfig(
        """
        server:
          port: 8090
        upstreams: [linear]
        """,
    )

    // --- parsing and defaults ---

    @Test
    fun `minimal config falls back to defaults`() {
        val config = ServerConfigLoader.load(minimal(), noEnv)

        assertEquals(8090, config.server.port)
        assertEquals("127.0.0.1", config.server.bindHost)
        assertEquals(listOf("linear"), config.upstreams)
        assertEquals(1800, config.server.sessionIdleTimeoutSeconds)
        assertEquals(64, config.server.maxSessions)
        assertEquals(64L * 1024 * 1024, config.server.eventStoreMaxBytes)
        assertEquals("INFO", config.logging.level)
    }

    @Test
    fun `publicUrl defaults to loopback on the configured port and strips a trailing slash`() {
        assertEquals("http://127.0.0.1:8090", ServerConfigLoader.load(minimal(), noEnv).server.resolvedPublicUrl())

        val configured = ServerConfigLoader.load(
            writeConfig(
                """
                server:
                  port: 8090
                  publicUrl: https://proxy.example.com/
                upstreams: [linear]
                """,
            ),
            noEnv,
        )
        assertEquals("https://proxy.example.com", configured.server.resolvedPublicUrl())
    }

    @Test
    fun `a per-upstream config is rejected with a readable message rather than half-parsed`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("upstream:\n  url: https://example.com/mcp"), noEnv)
        }
        assertContains(e.message!!, "upstream")
    }

    @Test
    fun `unknown keys are rejected`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(
                writeConfig("server:\n  port: 8090\nupstreams: [linear]\ntotallyUnknown: 1"),
                noEnv,
            )
        }
        assertContains(e.message!!, "totallyUnknown")
    }

    @Test
    fun `missing file is reported readably`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(Path.of("no-such-server-config-12345.yaml"), noEnv)
        }
        assertContains(e.message!!, "not found")
    }

    // --- interpolation ---

    @Test
    fun `env vars are interpolated`() {
        val env = mapOf("BIND" to "0.0.0.0", "PUBLIC" to "http://host.example:9000")::get
        val config = ServerConfigLoader.load(
            writeConfig(
                """
                server:
                  port: 8090
                  bindHost: ${'$'}{BIND}
                  publicUrl: ${'$'}{PUBLIC}
                upstreams: [linear]
                """,
            ),
            env,
        )

        assertEquals("0.0.0.0", config.server.bindHost)
        assertEquals("http://host.example:9000", config.server.resolvedPublicUrl())
    }

    @Test
    fun `a missing env var names the offending field`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(minimal().let { writeConfig("server:\n  port: 8090\n  bindHost: \${NOPE}\nupstreams: [linear]") }, noEnv)
        }
        assertContains(e.message!!, "server.bindHost")
    }

    // --- validation ---

    @Test
    fun `port is required and range-checked`() {
        assertFailsWith<ConfigException> { ServerConfigLoader.load(writeConfig("server: {}\nupstreams: [linear]"), noEnv) }
        assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("server:\n  port: 0\nupstreams: [linear]"), noEnv)
        }
        assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("server:\n  port: 70000\nupstreams: [linear]"), noEnv)
        }
    }

    @Test
    fun `publicUrl must be an http url`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(
                writeConfig("server:\n  port: 8090\n  publicUrl: ftp://x/\nupstreams: [linear]"),
                noEnv,
            )
        }
        assertContains(e.message!!, "publicUrl")
    }

    @Test
    fun `an empty upstream list is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("server:\n  port: 8090"), noEnv)
        }
        assertContains(e.message!!, "at least one")
    }

    @Test
    fun `duplicate upstream names are rejected`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("server:\n  port: 8090\nupstreams: [linear, linear]"), noEnv)
        }
        assertContains(e.message!!, "more than once")
    }

    /**
     * A name is resolved from the config home *and* becomes a URL path segment; a path-shaped entry
     * would mean a file relative to a working directory the operator did not choose, which is the
     * hole [ConfigResolver] exists to close.
     */
    @Test
    fun `path-shaped upstream names are rejected`() {
        // Single-quoted YAML scalars, so a backslash stays a backslash rather than an escape.
        listOf("../evil", "configs/linear", "configs\\linear", "~/linear", "C:linear").forEach { name ->
            val e = assertFailsWith<ConfigException>("$name should be rejected") {
                ServerConfigLoader.load(writeConfig("server:\n  port: 8090\nupstreams: ['$name']"), noEnv)
            }
            assertContains(e.message!!, "path")
        }
    }

    @Test
    fun `names outside the safe charset are rejected`() {
        listOf("has space", "hash#", "percent%20", "über").forEach { name ->
            assertFailsWith<ConfigException>("$name should be rejected") {
                ServerConfigLoader.load(writeConfig("server:\n  port: 8090\nupstreams: ['$name']"), noEnv)
            }
        }
    }

    /** Reserved so a later web UI can own /ui and /api without colliding with somebody's config. */
    @Test
    fun `reserved names are rejected`() {
        val reserved = RESERVED_UPSTREAM_NAMES + setOf("UI", "Api", "_internal")
        reserved.forEach { name ->
            val e = assertFailsWith<ConfigException>("$name should be rejected") {
                ServerConfigLoader.load(writeConfig("server:\n  port: 8090\nupstreams: ['$name']"), noEnv)
            }
            assertContains(e.message!!, "reserved")
        }
        // '.hidden' is path-shaped to nobody, but a leading dot is ours to keep too.
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(writeConfig("server:\n  port: 8090\nupstreams: ['.hidden']"), noEnv)
        }
        assertContains(e.message!!, "reserved")
    }

    @Test
    fun `limits must be positive`() {
        listOf("sessionIdleTimeoutSeconds: 0", "maxSessions: 0", "eventStoreMaxBytes: 0").forEach { setting ->
            assertFailsWith<ConfigException>("$setting should be rejected") {
                ServerConfigLoader.load(
                    writeConfig("server:\n  port: 8090\n  $setting\nupstreams: [linear]"),
                    noEnv,
                )
            }
        }
    }

    @Test
    fun `invalid log level is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ServerConfigLoader.load(
                writeConfig("server:\n  port: 8090\nupstreams: [linear]\nlogging:\n  level: LOUD"),
                noEnv,
            )
        }
        assertContains(e.message!!, "logging.level")
    }

    // --- loopback classification, which drives the trust-boundary warning ---

    @Test
    fun `loopback bind hosts are recognised as such`() {
        listOf("127.0.0.1", "::1", "localhost", "LOCALHOST").forEach {
            assertTrue(ServerSettings(port = 1, bindHost = it).bindsLoopbackOnly(), "$it should be loopback")
        }
        listOf("0.0.0.0", "192.168.1.10", "::").forEach {
            assertTrue(!ServerSettings(port = 1, bindHost = it).bindsLoopbackOnly(), "$it should not be loopback")
        }
    }
}
