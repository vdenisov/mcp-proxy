package org.plukh.mcpproxy.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {

    /** The worked example from RFC 7636 appendix B - the one true answer for S256. */
    @Test
    fun `S256 challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier uses the RFC 7636 unreserved alphabet and legal length`() {
        val verifier = Pkce.generateVerifier()
        assertTrue(verifier.length in 43..128, "length ${verifier.length} outside RFC bounds")
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, "illegal character in $verifier")
    }

    @Test
    fun `verifier and state are not repeated`() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
        assertNotEquals(Pkce.generateState(), Pkce.generateState())
    }
}
