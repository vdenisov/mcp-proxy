package org.plukh.mcpproxy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.plukh.mcpproxy.config.LoggingConfig
import org.plukh.mcpproxy.config.OAuthConfig
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamConfig

/**
 * Server mode honours a per-upstream config as written except for the two things it cannot: where
 * logs go, and where the OAuth callback lives. Those are dropped with a warning rather than
 * rejected - see the stage 4.3 notes in DESIGN.md for why this departs from the stage-2 precedent of
 * refusing settings that do not apply.
 */
class ServerModeSanitizeTest {

    private fun config(
        logging: LoggingConfig = LoggingConfig(),
        oauth: OAuthConfig? = null,
    ) = ProxyConfig(
        upstream = UpstreamConfig(url = "https://mcp.example.com/mcp", oauth = oauth),
        logging = logging,
    )

    @Test
    fun `a per-upstream logging block is dropped`() {
        val sanitized = sanitizeForServerMode(config(logging = LoggingConfig(file = "own.log", level = "DEBUG")), "svc")

        assertEquals(LoggingConfig(), sanitized.logging, "the server's own logging config must win")
    }

    @Test
    fun `callback settings are dropped so the server's own route is used`() {
        val oauth = OAuthConfig(
            callbackBindHost = "0.0.0.0",
            callbackPort = 8765,
            callbackUrl = "https://elsewhere.example.com/callback",
        )

        val sanitized = sanitizeForServerMode(config(oauth = oauth), "svc")

        val result = assertNotNull(sanitized.upstream.oauth)
        assertEquals("127.0.0.1", result.callbackBindHost)
        assertEquals(0, result.callbackPort)
        assertNull(result.callbackUrl)
    }

    /** Everything else is the user's configuration and must survive untouched. */
    @Test
    fun `identity, auth and the rest of the oauth block are left alone`() {
        val oauth = OAuthConfig(
            scopes = listOf("read", "write"),
            clientName = "some-name",
            clientId = "abc",
            tokenDir = "/tmp/tokens",
            authTimeoutSeconds = 42,
            interactiveWaitSeconds = 7,
            assumePkceS256 = true,
            callbackPort = 9999,
        )
        val original = config(oauth = oauth).let {
            it.copy(upstream = it.upstream.copy(requestTimeoutSeconds = 123, sendMcpMethodHeaders = true))
        }

        val sanitized = sanitizeForServerMode(original, "svc")

        val result = assertNotNull(sanitized.upstream.oauth)
        assertEquals(listOf("read", "write"), result.scopes)
        assertEquals("some-name", result.clientName)
        assertEquals("abc", result.clientId)
        assertEquals("/tmp/tokens", result.tokenDir)
        assertEquals(42, result.authTimeoutSeconds)
        assertEquals(7, result.interactiveWaitSeconds)
        assertEquals(true, result.assumePkceS256)
        assertEquals(original.identity, sanitized.identity)
        assertEquals(123, sanitized.upstream.requestTimeoutSeconds)
        assertEquals(true, sanitized.upstream.sendMcpMethodHeaders)
        assertEquals(original.upstream.url, sanitized.upstream.url)
    }

    @Test
    fun `an upstream without oauth stays without oauth`() {
        assertNull(sanitizeForServerMode(config(), "svc").upstream.oauth)
    }
}
