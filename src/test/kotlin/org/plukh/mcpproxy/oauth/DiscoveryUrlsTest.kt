package org.plukh.mcpproxy.oauth

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryUrlsTest {

    // --- protected resource metadata candidates ---

    @Test
    fun `challenge url comes first, then path-aware, then root`() {
        assertEquals(
            listOf(
                "https://as.example.com/prm",
                "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            Discovery.prmCandidates("https://mcp.example.com/public/mcp", "https://as.example.com/prm"),
        )
    }

    @Test
    fun `no challenge and no path leaves only the root form`() {
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-protected-resource"),
            Discovery.prmCandidates("https://mcp.example.com", null),
        )
    }

    @Test
    fun `non-default port is preserved`() {
        assertEquals(
            listOf(
                "http://127.0.0.1:3000/.well-known/oauth-protected-resource/mcp",
                "http://127.0.0.1:3000/.well-known/oauth-protected-resource",
            ),
            Discovery.prmCandidates("http://127.0.0.1:3000/mcp", null),
        )
    }

    // --- authorization server metadata candidates ---

    @Test
    fun `issuer with a path probes all three forms in order`() {
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant1",
                "https://auth.example.com/.well-known/openid-configuration/tenant1",
                "https://auth.example.com/tenant1/.well-known/openid-configuration",
            ),
            Discovery.asMetadataCandidates("https://auth.example.com/tenant1"),
        )
    }

    @Test
    fun `issuer without a path probes two forms`() {
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server",
                "https://auth.example.com/.well-known/openid-configuration",
            ),
            Discovery.asMetadataCandidates("https://auth.example.com"),
        )
    }

    // --- canonical resource uri ---

    @Test
    fun `canonicalization lowercases and strips default ports and trailing slashes`() {
        assertEquals("https://mcp.example.com/mcp", canonicalResourceUri("HTTPS://MCP.Example.COM:443/mcp/"))
        assertEquals("https://mcp.example.com:8443", canonicalResourceUri("https://mcp.example.com:8443"))
        assertEquals("http://127.0.0.1:3000/mcp", canonicalResourceUri("http://127.0.0.1:3000/mcp"))
        assertEquals("https://mcp.example.com", canonicalResourceUri("https://mcp.example.com/"))
    }
}
