package org.plukh.mcpproxy.upstream

/**
 * Where [HttpEndpoint] gets its Authorization header when the upstream is OAuth-gated.
 *
 * Lives in `upstream`, not `oauth`, so the vendored endpoint depends on a seam rather than on the
 * OAuth machinery. The contract splits blocking from non-blocking on purpose:
 *
 * - [ensureToken] and [handleUnauthorized] may take arbitrarily long (network, or a browser flow)
 *   and are called *outside* any HTTP request - the client's request timeout covers plugin hooks,
 *   so a slow call inside the pipeline would be killed mid-flow;
 * - [currentHeaders] is read inside the pipeline on every request and must never block.
 */
interface UpstreamTokenSource {

    /** Called before the first request and before (re)connecting the SSE stream. */
    suspend fun ensureToken()

    /** The current Authorization header (or nothing when no token is held). */
    fun currentHeaders(): Map<String, String>

    /**
     * A request came back 401. Refresh or re-authorize, single-flight across concurrent callers.
     *
     * @return true when a new token exists and replaying the request is worthwhile
     */
    suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean
}
