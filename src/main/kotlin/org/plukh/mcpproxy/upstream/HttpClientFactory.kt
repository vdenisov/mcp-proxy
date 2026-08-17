package org.plukh.mcpproxy.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.UpstreamConfig

/**
 * Builds the Ktor client used for upstream traffic.
 *
 * CIO is deliberate: it is pure Kotlin, so its default header set is enumerable from source rather
 * than inherited from an OS-native stack, and it speaks HTTP/1.1 only - which is what the Node-based
 * MCP client population we are blending into does. An HTTP/2-negotiating engine would stand out at
 * the transport layer no matter what headers we send.
 *
 * Plugins are added sparingly and on purpose. `UserAgent`, `ContentEncoding` and `HttpRequestRetry`
 * are all deliberately absent: the first would fight [IdentityHeaders], and the other two change
 * request shape and timing in ways a server can observe.
 */
fun buildHttpClient(
    upstream: UpstreamConfig,
    identity: IdentityConfig,
    engine: HttpClientEngineFactory<*> = CIO,
    onRequestHeaders: ((Map<String, List<String>>) -> Unit)? = null,
    dynamicHeaders: (() -> Map<String, String>)? = null,
): HttpClient = HttpClient(engine) {
    expectSuccess = false

    install(SSE)

    install(HttpTimeout) {
        requestTimeoutMillis = upstream.requestTimeoutSeconds * 1000
    }

    install(IdentityHeaders) {
        userAgent = identity.userAgent
        extraHeaders = upstream.authHeaders()
        this.dynamicHeaders = dynamicHeaders
        allowed = if (upstream.sendMcpMethodHeaders) {
            DEFAULT_ALLOWED_HEADERS + setOf(MCP_METHOD_HEADER.lowercase(), MCP_NAME_HEADER.lowercase())
        } else {
            DEFAULT_ALLOWED_HEADERS
        }
        this.onRequestHeaders = onRequestHeaders
    }
}

/**
 * The client the OAuth machinery itself uses for discovery, registration and token requests.
 *
 * Separate from the upstream client on purpose: MCP access tokens must never reach the
 * authorization server, and the AS's client credentials must never reach the MCP server - two
 * clients with disjoint header state make that structural rather than careful. Identity scrubbing
 * still applies: a token request announcing `ktor-client` would undo what this proxy is for.
 */
fun buildOAuthHttpClient(
    identity: IdentityConfig,
    engine: HttpClientEngineFactory<*> = CIO,
): HttpClient = HttpClient(engine) {
    expectSuccess = false

    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
    }

    install(IdentityHeaders) {
        userAgent = identity.userAgent
    }
}

/** Auth as a header map. Validation has already guaranteed at most one of the two is set. */
internal fun UpstreamConfig.authHeaders(): Map<String, String> = when {
    authToken != null -> mapOf("Authorization" to "Bearer $authToken")
    authHeader != null -> {
        val (name, value) = authHeader.split(":", limit = 2)
        mapOf(name.trim() to value.trim())
    }

    else -> emptyMap()
}
