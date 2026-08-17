package org.plukh.mcpproxy.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WwwAuthenticateTest {

    @Test
    fun `extracts resource_metadata from a bearer challenge`() {
        val challenges = parseWwwAuthenticate(
            """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource"""",
        )

        assertEquals(1, challenges.size)
        assertEquals("Bearer", challenges[0].scheme)
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            challenges[0].resourceMetadata,
        )
    }

    @Test
    fun `a quoted value containing commas survives`() {
        val challenges = parseWwwAuthenticate("""Bearer realm="a,b", resource_metadata="https://h/x,y"""")

        assertEquals("https://h/x,y", challenges[0].resourceMetadata)
        assertEquals("a,b", challenges[0].params["realm"])
    }

    @Test
    fun `escaped quotes inside a quoted value survive`() {
        val challenges = parseWwwAuthenticate("""Bearer realm="say \"hi\""""")
        assertEquals("""say "hi"""", challenges[0].params["realm"])
    }

    @Test
    fun `multiple challenges are separated`() {
        val challenges = parseWwwAuthenticate("""Basic realm="old", Bearer scope="mcp.read mcp.write"""")

        assertEquals(listOf("Basic", "Bearer"), challenges.map { it.scheme })
        assertEquals("mcp.read mcp.write", challenges[1].scope)
        assertNull(challenges[0].scope)
    }

    @Test
    fun `param names are case-insensitive`() {
        val challenges = parseWwwAuthenticate("""Bearer Resource_Metadata="https://h/prm"""")
        assertEquals("https://h/prm", challenges[0].resourceMetadata)
    }

    @Test
    fun `unquoted token values parse`() {
        val challenges = parseWwwAuthenticate("Bearer error=invalid_token, scope=mcp")
        assertEquals("invalid_token", challenges[0].error)
        assertEquals("mcp", challenges[0].scope)
    }

    @Test
    fun `a bare scheme with no params is a challenge`() {
        val challenges = parseWwwAuthenticate("Bearer")
        assertEquals("Bearer", challenges[0].scheme)
        assertTrue(challenges[0].params.isEmpty())
    }
}
