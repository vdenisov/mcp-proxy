package org.plukh.mcpproxy.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OAuthDtosTest {

    @Test
    fun `AS metadata with a sea of unknown fields still decodes`() {
        val metadata = OAuthJson.decodeFromString<AuthServerMetadata>(
            """
            {
              "issuer": "https://auth.example.com",
              "authorization_endpoint": "https://auth.example.com/authorize",
              "token_endpoint": "https://auth.example.com/token",
              "jwks_uri": "https://auth.example.com/jwks",
              "userinfo_endpoint": "https://auth.example.com/userinfo",
              "response_modes_supported": ["query"],
              "claims_supported": ["sub", "iss"],
              "code_challenge_methods_supported": ["S256", "plain"]
            }
            """.trimIndent(),
        )

        assertEquals("https://auth.example.com", metadata.issuer)
        assertNull(metadata.registrationEndpoint)
        assertEquals(listOf("S256", "plain"), metadata.codeChallengeMethodsSupported)
    }

    @Test
    fun `registration request omits null scope rather than sending it`() {
        val encoded = OAuthJson.encodeToString(
            RegistrationRequest.serializer(),
            RegistrationRequest(clientName = "mcp-proxy", redirectUris = listOf("http://127.0.0.1:1234/callback")),
        )

        assertFalse(encoded.contains("\"scope\""), "null scope must be omitted, got: $encoded")
        assertEquals(
            true,
            encoded.contains("\"token_endpoint_auth_method\":\"none\""),
            "public client declaration missing: $encoded",
        )
    }

    @Test
    fun `stored records round-trip`() {
        val tokens = StoredTokens(
            accessToken = "at",
            refreshToken = "rt",
            expiresAtEpochSeconds = 1_700_000_000,
            scope = "mcp",
            resource = "https://mcp.example.com/mcp",
            issuer = "https://auth.example.com",
            obtainedAtEpochSeconds = 1_699_999_000,
        )
        assertEquals(tokens, OAuthJson.decodeFromString(OAuthJson.encodeToString(StoredTokens.serializer(), tokens)))

        val registration = StoredRegistration(
            issuer = "https://auth.example.com",
            clientId = "abc",
            tokenEndpointAuthMethod = "none",
            redirectUris = listOf("http://127.0.0.1:1234/callback"),
            issuedAtEpochSeconds = 1_699_999_000,
        )
        assertEquals(
            registration,
            OAuthJson.decodeFromString(OAuthJson.encodeToString(StoredRegistration.serializer(), registration)),
        )
    }

    /**
     * Regression: a server that omits the (spec-required) `token_type` has still issued a working
     * token; failing the parse would report that as "unparseable response" and send the user
     * hunting for a problem that is not there.
     */
    @Test
    fun `a token response without token_type still decodes`() {
        val decoded = OAuthJson.decodeFromString<TokenResponse>("""{"access_token":"a","expires_in":60}""")

        assertEquals("a", decoded.accessToken)
        assertEquals("Bearer", decoded.tokenType)
    }

    @Test
    fun `token response decodes with and without optional fields`() {
        val full = OAuthJson.decodeFromString<TokenResponse>(
            """{"access_token":"a","token_type":"Bearer","expires_in":3600,"refresh_token":"r","scope":"s"}""",
        )
        assertEquals(3600, full.expiresIn)

        val minimal = OAuthJson.decodeFromString<TokenResponse>("""{"access_token":"a","token_type":"Bearer"}""")
        assertNull(minimal.expiresIn)
        assertNull(minimal.refreshToken)
    }
}
