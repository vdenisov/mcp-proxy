package org.plukh.mcpproxy.server

import org.plukh.mcpproxy.config.UpstreamTransport
import org.plukh.mcpproxy.oauth.TokenStore
import org.plukh.mcpproxy.tokenDir

/** Minimal HTML, no assets, no scripts - the same shape as the OAuth callback pages. */
internal fun page(title: String, body: String): String = """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>mcp-proxy</title></head>
    <body style="font-family: system-ui, sans-serif; max-width: 46rem; margin: 3rem auto; line-height: 1.5">
    <h1>$title</h1>
    $body
    </body></html>
""".trimIndent()

/**
 * What is configured, whether it can currently reach it, and where to fix it if not.
 *
 * Deliberately says nothing a token could be reconstructed from: token *presence* and expiry are
 * operational facts, token values are not.
 */
internal fun statusPage(
    runtimes: Map<String, UpstreamRuntime>,
    sessions: SessionRegistry,
    publicUrl: String,
): String {
    val rows = runtimes.values.joinToString("\n") { runtime ->
        val upstream = runtime.config.upstream
        val target = when (upstream.transport) {
            UpstreamTransport.HTTP -> upstream.url ?: "?"
            UpstreamTransport.STDIO -> "stdio: ${upstream.command.firstOrNull() ?: "?"}"
        }
        val auth = when {
            upstream.oauth != null -> oauthStatus(runtime, publicUrl)
            upstream.authToken != null || upstream.authHeader != null -> "static credential"
            else -> "none"
        }
        """
        <tr>
          <td><code>${runtime.name}</code></td>
          <td><code>$target</code></td>
          <td>$auth</td>
          <td>${sessions.count(runtime.name)}</td>
          <td><code>$publicUrl/${runtime.name}/mcp</code></td>
        </tr>
        """.trimIndent()
    }

    return page(
        "mcp-proxy",
        """
        <p>Point an MCP client at the endpoint of the upstream it should use.</p>
        <table cellpadding="6" style="border-collapse: collapse">
          <tr style="text-align: left"><th>Name</th><th>Upstream</th><th>Auth</th><th>Sessions</th><th>Endpoint</th></tr>
          $rows
        </table>
        <p style="margin-top:2rem; padding:1rem; border:2px solid #b00; background:#fee">
          <strong>This listener has no authentication.</strong> Anyone who can reach it uses these
          upstreams with the stored credentials of whoever runs this proxy. Keep it on loopback.
        </p>
        """.trimIndent(),
    )
}

private fun oauthStatus(runtime: UpstreamRuntime, publicUrl: String): String {
    val loginLink = """<a href="$publicUrl/${runtime.name}/login">log in</a>"""
    runtime.loginCoordinator?.pendingUrl()?.let {
        return """OAuth - <strong>authorization in progress</strong>, <a href="$it">continue</a>"""
    }
    runtime.loginCoordinator?.lastError?.let { return "OAuth - last attempt failed: $it ($loginLink)" }

    val url = runtime.config.upstream.url ?: return "OAuth ($loginLink)"
    val tokens = runCatching {
        TokenStore(tokenDir(runtime.config.upstream.oauth?.tokenDir)).loadTokens(url)
    }.getOrNull() ?: return "OAuth - no token stored ($loginLink)"

    val expiry = tokens.expiresAtEpochSeconds?.let { " until epoch $it" } ?: ""
    val refresh = if (tokens.refreshToken != null) ", refreshable" else ""
    return "OAuth - token stored$expiry$refresh ($loginLink)"
}
