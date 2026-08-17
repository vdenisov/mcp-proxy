package org.plukh.mcpproxy.oauth

import java.net.URI

/**
 * The canonical MCP server URI (RFC 8707 style): lowercase scheme and host, default port stripped,
 * path kept, no trailing slash, no query or fragment.
 *
 * One function on purpose - the same string is the `resource` parameter on every authorization,
 * token and refresh request *and* the key the token store files are named by. Two canonicalizations
 * would eventually disagree, and the symptom (a token that exists but is never found, or an AS
 * rejecting an audience) is miserable to diagnose.
 */
internal fun canonicalResourceUri(url: String): String {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase() ?: error("upstream url has no scheme: $url")
    val host = uri.host?.lowercase() ?: error("upstream url has no host: $url")
    val port = when {
        uri.port == -1 -> ""
        scheme == "https" && uri.port == 443 -> ""
        scheme == "http" && uri.port == 80 -> ""
        else -> ":${uri.port}"
    }
    val path = uri.path.orEmpty().trimEnd('/')
    return "$scheme://$host$port$path"
}
