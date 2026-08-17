package org.plukh.mcpproxy.oauth

/**
 * Authorization is needed and could not be obtained non-interactively. The message is what the MCP
 * client's user ultimately sees (the relay wraps it into a JSON-RPC error), so it names the fix.
 */
class AuthRequiredException(message: String) : Exception(message)

/** An OAuth flow step failed - discovery, registration, authorization or token exchange. */
class OAuthFlowException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A structured error from the authorization server (RFC 6749 §5.2). */
class OAuthErrorException(
    val error: String,
    val description: String?,
    val status: Int,
) : Exception("authorization server returned $error (HTTP $status)${description?.let { ": $it" } ?: ""}")
