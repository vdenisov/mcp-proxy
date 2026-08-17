package org.plukh.mcpproxy.config

import kotlinx.serialization.Serializable

/**
 * Single-process server mode: one HTTP listener in front of several upstreams, so N servers cost
 * one JVM instead of N.
 *
 * A separate schema from [ProxyConfig] rather than a marker inside it. kaml runs in strict mode, so
 * feeding a per-upstream config to `--serve` (or a server config to a normal run) fails with a
 * readable line naming the unknown key - the discrimination is free, and [ProxyConfig] does not
 * grow server-only fields its own validator would have to reject.
 *
 * Upstreams are named, not defined here: each name resolves from the config home exactly as the
 * `CONFIG` argument does, so the same `linear.yaml` serves `mcp-proxy linear` and a route on the
 * listener.
 */
@Serializable
data class ServerConfig(
    val server: ServerSettings,
    /** Config names to serve, one route each. Resolved from the proxy home like the CLI argument. */
    val upstreams: List<String> = emptyList(),
    val logging: LoggingConfig = LoggingConfig(),
)

@Serializable
data class ServerSettings(
    /**
     * Required, deliberately without a default: a defaulted port invites two installations to
     * collide silently, and a container publishes an explicit port anyway.
     */
    val port: Int,
    /** `0.0.0.0` inside a container. Anything but loopback is logged as a trust-boundary warning. */
    val bindHost: String = "127.0.0.1",
    /**
     * Base URL a *browser* uses to reach this server, for the OAuth login and callback routes.
     * Defaults to `http://127.0.0.1:<port>`.
     *
     * Containerized, this stays the host-side URL while [bindHost] is `0.0.0.0`: the redirect URI
     * is a string the authorization server hands to the browser, and the browser resolves it on the
     * host - which is what keeps the RFC 8252 loopback exemption, and HTTP, legitimate here.
     */
    val publicUrl: String? = null,
    /** Sessions idle longer than this are closed by the sweeper. */
    val sessionIdleTimeoutSeconds: Long = 1800,
    val maxSessions: Int = 64,
    /**
     * Memory budget for replayable stream events, summed over encoded frame bytes. Frames are held
     * so a client that reconnects with `Last-Event-ID` gets what it missed rather than a hole;
     * oldest go first when the budget is reached, and a client asking for an evicted id is answered
     * with a fresh stream.
     */
    val eventStoreMaxBytes: Long = 64L * 1024 * 1024,
)

/**
 * Path segments the server keeps for itself. An upstream may not take one, so a later web UI can
 * own `/ui`, `/api/...` or an `_`-prefixed namespace without colliding with somebody's config file
 * name. The per-upstream subtree (`/<name>/...`) is ours to extend freely and needs no reservation.
 */
val RESERVED_UPSTREAM_NAMES = setOf("api", "ui", "assets", "static", "admin", "health", "metrics")
