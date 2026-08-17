package org.plukh.mcpproxy.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The proxy configuration, as read from YAML. Defaults live here, so a config file only has to
 * state what it changes; `upstream.url` is the single mandatory setting.
 */
@Serializable
data class ProxyConfig(
    val identity: IdentityConfig = IdentityConfig(),
    val upstream: UpstreamConfig = UpstreamConfig(),
    val logging: LoggingConfig = LoggingConfig(),
)

/**
 * What the upstream server is told about us. Every field here is deliberately user-controlled -
 * the proxy ships neutral defaults and takes no view on what identity you should present.
 */
@Serializable
data class IdentityConfig(
    /** Replaces `clientInfo.name` in the initialize request. */
    val name: String = "mcp-proxy",
    /** Replaces `clientInfo.version` in the initialize request. */
    val version: String = "1.0.0",
    /** Optional `clientInfo.title`. Omitted from the handshake when null. */
    val title: String? = null,
    /** HTTP `User-Agent` for upstream requests. Ktor's own default would otherwise leak the engine. */
    val userAgent: String = "mcp-proxy/1.0",
    /**
     * Forward the client's capabilities (minus [DROPPED_CAPABILITIES]) rather than sending an empty
     * object. Keep this on: with a raw relay the reverse direction genuinely works, so claiming
     * capabilities the client does not have - or hiding ones it does - only breaks things.
     */
    val forwardCapabilities: Boolean = true,
    /**
     * Send only the three spec-mandated keys in `initialize.params`, dropping vendor extras that
     * could identify the client. Turn off only if an upstream needs a non-standard param.
     */
    val strictInitializeParams: Boolean = true,
)

@Serializable
data class UpstreamConfig(
    val transport: UpstreamTransport = UpstreamTransport.HTTP,
    /** Streamable HTTP endpoint of the real MCP server. Required for [UpstreamTransport.HTTP]. */
    val url: String? = null,
    /** Sent as `Authorization: Bearer <token>`. Mutually exclusive with [authHeader]. HTTP only. */
    val authToken: String? = null,
    /** Verbatim header for non-Bearer schemes, `"Name: value"`. Mutually exclusive with [authToken]. HTTP only. */
    val authHeader: String? = null,
    /**
     * The server to spawn, argv-style. Required for [UpstreamTransport.STDIO].
     *
     * Executed directly, without a shell, so redirections and wildcards do not apply and on Windows
     * only real executables resolve from PATH - a `.cmd` shim needs `["cmd", "/c", ...]`.
     */
    val command: List<String> = emptyList(),
    /** Environment overlaid on the proxy's own for the spawned server. Stdio only. */
    val env: Map<String, String> = emptyMap(),
    /**
     * OAuth-gated upstream: the proxy runs the whole flow (discovery, registration, browser,
     * tokens). Mutually exclusive with [authToken]/[authHeader]; HTTP only.
     *
     * kaml note: a bare `oauth:` key decodes as null (OAuth off); enabling with all defaults is
     * spelled `oauth: {}`.
     */
    val oauth: OAuthConfig? = null,
    /** HTTP only; a pipe has no request timeout. */
    val requestTimeoutSeconds: Long = 60,
    /**
     * Let the SDK-derived `Mcp-Method` / `Mcp-Name` headers through. Off by default: they push tool
     * and resource names into HTTP metadata that CDNs and reverse proxies log, and their presence is
     * itself a client fingerprint. No server is known to require them.
     */
    val sendMcpMethodHeaders: Boolean = false,
)

/**
 * How the proxy authorizes against an OAuth-gated upstream. Presence of the block is the switch;
 * every field has a working default, so `oauth: {}` is a complete configuration.
 */
@Serializable
data class OAuthConfig(
    /** Overrides scope selection when non-empty; else the 401 challenge's scope, else PRM's. */
    val scopes: List<String> = emptyList(),
    /** RFC 7591 `client_name` - the identity the authorization server stores. Default: `identity.name`. */
    val clientName: String? = null,
    /** Pre-registered client id; skips dynamic registration entirely. */
    val clientId: String? = null,
    /** Secret for a pre-registered confidential client. Requires [clientId]. */
    val clientSecret: String? = null,
    /** Address the callback listener binds. Non-loopback only makes sense containerized. */
    val callbackBindHost: String = "127.0.0.1",
    /**
     * Callback port; 0 binds an ephemeral one. A fixed port keeps the registered redirect URI
     * stable across logins, which some authorization servers require despite RFC 8252.
     */
    val callbackPort: Int = 0,
    /**
     * Advertised redirect URL when it differs from `http://<callbackBindHost>:<port>/callback` -
     * the dockerized case: bind inside the container, advertise the host-reachable URL. Requires a
     * fixed [callbackPort].
     */
    val callbackUrl: String? = null,
    /** Token/registration storage; `~` is expanded. Default `~/.mcp-proxy/tokens`. */
    val tokenDir: String? = null,
    /** When false, the authorization URL is only printed, never opened. */
    val openBrowser: Boolean = true,
    /** Proceed with S256 against a server whose metadata omits code_challenge_methods_supported. */
    val assumePkceS256: Boolean = false,
    /** How long `--login` waits for the browser callback. */
    val authTimeoutSeconds: Long = 300,
    /** Bounded wait when the flow triggers mid-serve - kept under typical client initialize timeouts. */
    val interactiveWaitSeconds: Long = 55,
)

@Serializable
enum class UpstreamTransport {
    @SerialName("http")
    HTTP,

    /** A local server spawned as a child process, framed over its stdin/stdout. */
    @SerialName("stdio")
    STDIO,
}

@Serializable
data class LoggingConfig(
    /** Log file path; `~` is expanded. When null, logs go to stderr only. */
    val file: String? = null,
    val level: String = "INFO",
)

/** Capability keys stripped from the forwarded handshake - they are fingerprints, not features. */
val DROPPED_CAPABILITIES = setOf("experimental", "extensions")
