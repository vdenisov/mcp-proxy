package org.plukh.mcpproxy.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private val noEnv: (String) -> String? = { null }

    private fun writeConfig(content: String): Path =
        Files.createTempFile("mcp-proxy-test", ".yaml").also { Files.writeString(it, content.trimIndent()) }

    // --- parsing and defaults ---

    @Test
    fun `minimal config falls back to defaults`() {
        val config = ConfigLoader.load(writeConfig("upstream:\n  url: https://example.com/mcp"), env = noEnv)

        assertEquals("https://example.com/mcp", config.upstream.url)
        assertEquals("mcp-proxy", config.identity.name)
        assertEquals("mcp-proxy/1.0", config.identity.userAgent)
        assertEquals(UpstreamTransport.HTTP, config.upstream.transport)
        assertTrue(config.identity.strictInitializeParams)
        assertTrue(config.identity.forwardCapabilities)
        assertEquals(false, config.upstream.sendMcpMethodHeaders)
        assertNull(config.logging.file)
    }

    @Test
    fun `full config round-trips every field`() {
        val config = ConfigLoader.load(
            writeConfig(
                """
                identity:
                  name: some-client
                  version: 9.9.9
                  title: Some Client
                  userAgent: some-client/9.9
                  forwardCapabilities: false
                  strictInitializeParams: false
                upstream:
                  transport: http
                  url: https://mcp.example.com/mcp
                  authToken: secret
                  requestTimeoutSeconds: 30
                  sendMcpMethodHeaders: true
                logging:
                  file: /tmp/proxy.log
                  level: DEBUG
                """,
            ),
            env = noEnv,
        )

        assertEquals("some-client", config.identity.name)
        assertEquals("Some Client", config.identity.title)
        assertEquals(false, config.identity.forwardCapabilities)
        assertEquals("secret", config.upstream.authToken)
        assertEquals(30, config.upstream.requestTimeoutSeconds)
        assertEquals("DEBUG", config.logging.level)
    }

    @Test
    fun `unknown keys are rejected`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig("upstream:\n  url: https://example.com/mcp\n  totallyUnknown: 1"),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "totallyUnknown")
    }

    @Test
    fun `empty file is a valid use-all-defaults config`() {
        val config = ConfigLoader.load(
            writeConfig("   "),
            CliOverrides(upstreamUrl = "https://example.com/mcp"),
            env = noEnv,
        )
        assertEquals("https://example.com/mcp", config.upstream.url)
    }

    @Test
    fun `missing file is reported readably`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(Path.of("does-not-exist-12345.yaml"), env = noEnv)
        }
        assertContains(e.message!!, "not found")
    }

    /**
     * A directory is readable, and CONFIG is no longer a clikt `path(canBeDir = false)`, so without
     * the regular-file check this surfaces as a raw IOException from `readString`.
     */
    @Test
    fun `a directory passed as config is reported readably`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(Files.createTempDirectory("mcp-proxy-not-a-config"), env = noEnv)
        }
        assertContains(e.message!!, "not found")
    }

    // --- interpolation ---

    @Test
    fun `env vars are interpolated`() {
        val config = ConfigLoader.load(
            writeConfig("upstream:\n  url: https://example.com/mcp\n  authToken: \${MY_TOKEN}"),
            env = mapOf("MY_TOKEN" to "s3cret")::get,
        )
        assertEquals("s3cret", config.upstream.authToken)
    }

    @Test
    fun `default is used when env var is absent`() {
        assertEquals("fallback", "\${NOPE:-fallback}".interpolate("f", noEnv))
        assertEquals("", "\${NOPE:-}".interpolate("f", noEnv))
        assertEquals("real", "\${NOPE:-fallback}".interpolate("f", mapOf("NOPE" to "real")::get))
    }

    @Test
    fun `interpolation is partial and repeatable within one string`() {
        val env = mapOf("A" to "1", "B" to "2")::get
        assertEquals("x1-2y", "x\${A}-\${B}y".interpolate("f", env))
    }

    @Test
    fun `missing env var without default names the offending field`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig("upstream:\n  url: https://example.com/mcp\n  authToken: \${ABSENT_VAR}"),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "upstream.authToken")
        assertContains(e.message!!, "ABSENT_VAR")
    }

    @Test
    fun `a token containing yaml punctuation survives interpolation`() {
        // The reason interpolation runs after the parse, not over the raw text.
        val config = ConfigLoader.load(
            writeConfig("upstream:\n  url: https://example.com/mcp\n  authToken: \${TRICKY}"),
            env = mapOf("TRICKY" to "a:b#c a lot")::get,
        )
        assertEquals("a:b#c a lot", config.upstream.authToken)
    }

    // --- precedence ---

    @Test
    fun `cli overrides beat the file`() {
        val config = ConfigLoader.load(
            writeConfig(
                """
                identity:
                  name: from-file
                upstream:
                  url: https://from-file.example.com/mcp
                """,
            ),
            CliOverrides(upstreamUrl = "https://from-cli.example.com/mcp", identityName = "from-cli"),
            env = noEnv,
        )

        assertEquals("https://from-cli.example.com/mcp", config.upstream.url)
        assertEquals("from-cli", config.identity.name)
    }

    @Test
    fun `cli values are interpolated too`() {
        val config = ConfigLoader.load(
            path = null,
            overrides = CliOverrides(upstreamUrl = "https://example.com/mcp", authToken = "\${CLI_TOKEN}"),
            env = mapOf("CLI_TOKEN" to "from-env")::get,
        )
        assertEquals("from-env", config.upstream.authToken)
    }

    @Test
    fun `config file is optional`() {
        val config = ConfigLoader.load(null, CliOverrides(upstreamUrl = "https://example.com/mcp"), env = noEnv)
        assertEquals("https://example.com/mcp", config.upstream.url)
    }

    // --- validation ---

    @Test
    fun `upstream url is required`() {
        val e = assertFailsWith<ConfigException> { ConfigLoader.load(null, env = noEnv) }
        assertContains(e.message!!, "upstream.url is required")
    }

    @Test
    fun `auth token and auth header are mutually exclusive`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                null,
                CliOverrides(
                    upstreamUrl = "https://example.com/mcp",
                    authToken = "t",
                    authHeader = "X-Key: v",
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "mutually exclusive")
    }

    @Test
    fun `auth header must be name colon value`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                null,
                CliOverrides(upstreamUrl = "https://example.com/mcp", authHeader = "nocolon"),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "authHeader")
    }

    @Test
    fun `non-http upstream url is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(null, CliOverrides(upstreamUrl = "ftp://example.com"), env = noEnv)
        }
        assertContains(e.message!!, "http(s)")
    }

    @Test
    fun `invalid log level is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                null,
                CliOverrides(upstreamUrl = "https://example.com/mcp", logLevel = "CHATTY"),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "logging.level")
    }

    // --- stdio transport ---

    private val stdioConfig = """
        upstream:
          transport: stdio
          command: ["docker", "run", "-i", "--rm", "ghcr.io/example/server"]
          env:
            EXAMPLE_TOKEN: shhh
    """

    @Test
    fun `stdio transport with a command is accepted`() {
        val config = ConfigLoader.load(writeConfig(stdioConfig), env = noEnv)

        assertEquals(UpstreamTransport.STDIO, config.upstream.transport)
        assertEquals(listOf("docker", "run", "-i", "--rm", "ghcr.io/example/server"), config.upstream.command)
        assertEquals(mapOf("EXAMPLE_TOKEN" to "shhh"), config.upstream.env)
        assertNull(config.upstream.url)
    }

    @Test
    fun `stdio transport without a command is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(writeConfig("upstream:\n  transport: stdio"), env = noEnv)
        }
        assertContains(e.message!!, "upstream.command is required")
    }

    @Test
    fun `stdio transport with an empty command is rejected`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(writeConfig("upstream:\n  transport: stdio\n  command: []"), env = noEnv)
        }
        assertContains(e.message!!, "upstream.command is required")
    }

    /**
     * Rejected rather than ignored: silently dropping an auth setting would let the user believe a
     * credential is being sent to a server that never sees one.
     */
    @Test
    fun `http-only settings are rejected with the stdio transport`() {
        listOf(
            "url: https://example.com/mcp" to "upstream.url",
            "authToken: secret" to "upstream.authToken",
            "authHeader: \"X-Key: v\"" to "upstream.authHeader",
        ).forEach { (line, expected) ->
            val e = assertFailsWith<ConfigException>("expected $line to be rejected") {
                ConfigLoader.load(
                    writeConfig("upstream:\n  transport: stdio\n  command: [\"srv\"]\n  $line"),
                    env = noEnv,
                )
            }
            assertContains(e.message!!, expected)
            assertContains(e.message!!, "stdio")
        }
    }

    @Test
    fun `stdio-only settings are rejected with the http transport`() {
        listOf(
            "command: [\"srv\"]" to "upstream.command",
            "env: { A: b }" to "upstream.env",
        ).forEach { (line, expected) ->
            val e = assertFailsWith<ConfigException>("expected $line to be rejected") {
                ConfigLoader.load(
                    writeConfig("upstream:\n  url: https://example.com/mcp\n  $line"),
                    env = noEnv,
                )
            }
            assertContains(e.message!!, expected)
        }
    }

    @Test
    fun `command elements and env values are interpolated`() {
        val config = ConfigLoader.load(
            writeConfig(
                """
                upstream:
                  transport: stdio
                  command: ["docker", "run", "$DOLLAR{IMAGE}"]
                  env:
                    GITHUB_TOKEN: $DOLLAR{GH_PAT}
                """,
            ),
            env = mapOf("IMAGE" to "ghcr.io/example/server:v2", "GH_PAT" to "ghp_secret")::get,
        )

        assertEquals(listOf("docker", "run", "ghcr.io/example/server:v2"), config.upstream.command)
        assertEquals(mapOf("GITHUB_TOKEN" to "ghp_secret"), config.upstream.env)
    }

    @Test
    fun `a missing env var in an env value names the offending key`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig(
                    """
                    upstream:
                      transport: stdio
                      command: ["srv"]
                      env:
                        GITHUB_TOKEN: $DOLLAR{ABSENT_VAR}
                    """,
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "upstream.env.GITHUB_TOKEN")
        assertContains(e.message!!, "ABSENT_VAR")
    }

    // --- oauth ---

    @Test
    fun `an empty oauth block enables oauth with defaults`() {
        val config = ConfigLoader.load(
            writeConfig("upstream:\n  url: https://mcp.example.com/mcp\n  oauth: {}"),
            env = noEnv,
        )

        val oauth = config.upstream.oauth!!
        assertEquals("127.0.0.1", oauth.callbackBindHost)
        assertEquals(0, oauth.callbackPort)
        assertNull(oauth.clientName)
        assertTrue(oauth.openBrowser)
    }

    @Test
    fun `oauth is mutually exclusive with static auth`() {
        listOf("authToken: t", "authHeader: \"X-Key: v\"").forEach { authLine ->
            val e = assertFailsWith<ConfigException>("expected oauth + $authLine to be rejected") {
                ConfigLoader.load(
                    writeConfig("upstream:\n  url: https://mcp.example.com/mcp\n  $authLine\n  oauth: {}"),
                    env = noEnv,
                )
            }
            assertContains(e.message!!, "oauth")
        }
    }

    @Test
    fun `oauth clientSecret requires clientId`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig(
                    """
                    upstream:
                      url: https://mcp.example.com/mcp
                      oauth:
                        clientSecret: shhh
                    """,
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "clientId")
    }

    @Test
    fun `oauth callbackUrl requires a fixed callbackPort`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig(
                    """
                    upstream:
                      url: https://mcp.example.com/mcp
                      oauth:
                        callbackUrl: https://proxy.example.com/callback
                    """,
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "callbackPort")
    }

    @Test
    fun `oauth is rejected with the stdio transport`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig("upstream:\n  transport: stdio\n  command: [\"srv\"]\n  oauth: {}"),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "oauth")
        assertContains(e.message!!, "stdio")
    }

    @Test
    fun `oauth string fields and scopes are interpolated`() {
        val config = ConfigLoader.load(
            writeConfig(
                """
                upstream:
                  url: https://mcp.example.com/mcp
                  oauth:
                    scopes: ["$DOLLAR{SCOPE_A}", "fixed"]
                    clientName: $DOLLAR{CLIENT_NAME}
                    clientId: $DOLLAR{CLIENT_ID}
                    clientSecret: $DOLLAR{CLIENT_SECRET}
                    callbackBindHost: $DOLLAR{BIND_HOST}
                    callbackPort: 8765
                    callbackUrl: $DOLLAR{CALLBACK_URL}
                    tokenDir: $DOLLAR{TOKEN_DIR}
                """,
            ),
            env = mapOf(
                "SCOPE_A" to "mcp.read",
                "CLIENT_NAME" to "generic-client",
                "CLIENT_ID" to "cid",
                "CLIENT_SECRET" to "cs",
                "BIND_HOST" to "0.0.0.0",
                "CALLBACK_URL" to "https://proxy.example.com/callback",
                "TOKEN_DIR" to "/var/lib/mcp-proxy/tokens",
            )::get,
        )

        val oauth = config.upstream.oauth!!
        assertEquals(listOf("mcp.read", "fixed"), oauth.scopes)
        assertEquals("generic-client", oauth.clientName)
        assertEquals("cid", oauth.clientId)
        assertEquals("cs", oauth.clientSecret)
        assertEquals("0.0.0.0", oauth.callbackBindHost)
        assertEquals("https://proxy.example.com/callback", oauth.callbackUrl)
        assertEquals("/var/lib/mcp-proxy/tokens", oauth.tokenDir)
    }

    @Test
    fun `a missing env var in an oauth field names it`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig(
                    """
                    upstream:
                      url: https://mcp.example.com/mcp
                      oauth:
                        clientName: $DOLLAR{ABSENT_VAR}
                    """,
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "upstream.oauth.clientName")
        assertContains(e.message!!, "ABSENT_VAR")
    }

    @Test
    fun `a missing env var in a command element names its index`() {
        val e = assertFailsWith<ConfigException> {
            ConfigLoader.load(
                writeConfig(
                    """
                    upstream:
                      transport: stdio
                      command: ["docker", "run", "$DOLLAR{ABSENT_VAR}"]
                    """,
                ),
                env = noEnv,
            )
        }
        assertContains(e.message!!, "upstream.command[2]")
        assertContains(e.message!!, "ABSENT_VAR")
    }
}

/** Raw strings have no escape for `$`, and these fixtures are full of `${VAR}` references. */
private const val DOLLAR = "$"
