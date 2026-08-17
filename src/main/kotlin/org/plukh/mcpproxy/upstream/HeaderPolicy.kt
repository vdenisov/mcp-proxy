package org.plukh.mcpproxy.upstream

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * Headers the upstream legitimately needs. Everything else is stripped before the request leaves.
 *
 * An allowlist rather than a blocklist: the threat is a header we did not anticipate - added by a
 * future Ktor version, a plugin, or an SDK change - and a blocklist cannot cover what it has not
 * been told about.
 */
val DEFAULT_ALLOWED_HEADERS = setOf(
    HttpHeaders.Accept,
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.Authorization,
    MCP_SESSION_ID_HEADER,
    MCP_PROTOCOL_VERSION_HEADER,
    MCP_RESUMPTION_TOKEN_HEADER,
).map { it.lowercase() }.toSet()

class HeaderPolicyConfig {
    /** Replaces whatever the engine would otherwise send. Never leave this unset. */
    var userAgent: String = "mcp-proxy/1.0"

    /** Auth and any other headers the config asks for, applied after the allowlist. */
    var extraHeaders: Map<String, String> = emptyMap()

    /**
     * Headers read fresh on every request, applied after [extraHeaders] - the seam a rotating OAuth
     * access token comes through. Must never block: token refresh happens *outside* the request
     * pipeline (the request timeout covers this hook), so by the time a request is here the current
     * token is already a cached value.
     */
    var dynamicHeaders: (() -> Map<String, String>)? = null

    var allowed: Set<String> = DEFAULT_ALLOWED_HEADERS

    /** Called with the final header set of every request. Used by `--check`. */
    var onRequestHeaders: ((Map<String, List<String>>) -> Unit)? = null
}

/**
 * Strips every outgoing header down to [HeaderPolicyConfig.allowed], then applies our own identity.
 *
 * Runs as the last thing in the client pipeline, so it also catches headers added by other plugins.
 * It cannot see headers the engine itself adds below the pipeline (`Host`, `Connection`,
 * `Accept-Encoding`, …) - `--check --loopback` exists to observe those.
 */
val IdentityHeaders = createClientPlugin("IdentityHeaders", ::HeaderPolicyConfig) {
    val config = pluginConfig

    onRequest { request, _ ->
        val kept = request.headers.entries()
            .filter { it.key.lowercase() in config.allowed }
            .flatMap { entry -> entry.value.map { entry.key to it } }

        request.headers.clear()
        kept.forEach { (name, value) -> request.headers.append(name, value) }

        request.headers[HttpHeaders.UserAgent] = config.userAgent
        config.extraHeaders.forEach { (name, value) -> request.headers[name] = value }
        config.dynamicHeaders?.invoke()?.forEach { (name, value) -> request.headers[name] = value }

        config.onRequestHeaders?.invoke(request.headers.entries().associate { it.key to it.value })
    }
}
