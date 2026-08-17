package org.plukh.mcpproxy.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) and `state` material. S256 only - `plain` exists in the RFC solely for clients
 * that cannot hash, which we are not.
 */
object Pkce {

    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes base64url-encoded: 86 chars of the RFC 7636 unreserved alphabet. */
    fun generateVerifier(): String = base64Url.encodeToString(ByteArray(64).also(random::nextBytes))

    fun challengeS256(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * The callback listener is reachable by any local process, so `state` is what ties a callback to
     * the flow that asked for it - high-entropy and single-use, not a formality.
     */
    fun generateState(): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))
}
