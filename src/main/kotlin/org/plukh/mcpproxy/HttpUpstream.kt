package org.plukh.mcpproxy

import io.ktor.client.HttpClient
import java.nio.file.Path
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.oauth.BrowserLauncher
import org.plukh.mcpproxy.oauth.CallbackTransport
import org.plukh.mcpproxy.oauth.EphemeralCallbackTransport
import org.plukh.mcpproxy.oauth.OAuthSession
import org.plukh.mcpproxy.oauth.TokenStore
import org.plukh.mcpproxy.upstream.HttpEndpoint
import org.plukh.mcpproxy.upstream.buildHttpClient
import org.plukh.mcpproxy.upstream.buildOAuthHttpClient

/**
 * Everything one HTTP upstream needs, built consistently. `serve`, `--check` and `--check
 * --loopback` all construct the same stack; before this factory they hand-mirrored each other, and
 * "the audit must audit the shipping configuration" only held as long as nobody forgot a parameter.
 */
internal class HttpUpstream(
    private val shared: SharedHttpUpstream,
    val endpoint: HttpEndpoint,
) : AutoCloseable {

    val client: HttpClient get() = shared.client
    val oauthSession: OAuthSession? get() = shared.oauthSession

    override fun close() = shared.close()
}

/**
 * The half of an upstream that outlives any one MCP session: the HTTP client and the OAuth
 * machinery, both of which are deliberately shared.
 *
 * Server mode holds one of these per configured upstream and mints an [HttpEndpoint] per session -
 * the endpoint cannot be shared, because `Mcp-Session-Id` is per-session state and `Relay.shutdown`
 * closes the endpoint it was given. Sharing the [OAuthSession] is the point rather than an
 * optimization: its single-flight lock is what stops ten concurrent sessions opening ten browser
 * windows for the same upstream.
 */
internal class SharedHttpUpstream(
    val client: HttpClient,
    val oauthSession: OAuthSession?,
    private val oauthClient: HttpClient?,
    private val url: String,
    private val sendMcpMethodHeaders: Boolean,
) : AutoCloseable {

    /** A fresh endpoint for one session. */
    fun newEndpoint(urlOverride: String? = null): HttpEndpoint = HttpEndpoint(
        client = client,
        url = urlOverride ?: url,
        sendMcpMethodHeaders = sendMcpMethodHeaders,
        tokenSource = oauthSession,
    )

    override fun close() {
        client.close()
        // Before the client it depends on: a detached login flow still parked on the browser would
        // otherwise wake up and try to exchange its code through a closed client.
        oauthSession?.close()
        oauthClient?.close()
    }
}

/**
 * @param configArg the `CONFIG` argument exactly as the user typed it, echoed in `--login` hints;
 *   deliberately not the resolved path, so the hint stays copy-pasteable
 * @param interactive whether an OAuth flow may open a browser and wait; false in audit mode
 * @param urlOverride target other than the configured url - the loopback audit's local listener
 * @param announceUrl where the authorization URL is printed; defaults to stderr (serve mode)
 */
internal fun buildHttpUpstream(
    config: ProxyConfig,
    configArg: String? = null,
    interactive: Boolean = true,
    onRequestHeaders: ((Map<String, List<String>>) -> Unit)? = null,
    urlOverride: String? = null,
    announceUrl: ((String) -> Unit)? = null,
    openBrowser: ((String) -> Unit)? = null,
): HttpUpstream {
    val shared = buildSharedHttpUpstream(
        config = config,
        configArg = configArg,
        interactive = interactive,
        onRequestHeaders = onRequestHeaders,
        announceUrl = announceUrl,
        openBrowser = openBrowser,
    )
    return HttpUpstream(shared, shared.newEndpoint(urlOverride))
}

/**
 * @param configArg the `CONFIG` argument exactly as the user typed it, echoed in `--login` hints;
 *   deliberately not the resolved path, so the hint stays copy-pasteable
 * @param interactive whether an OAuth flow may open a browser and wait; false in audit mode
 * @param announceUrl where the authorization URL is printed; defaults to stderr (serve mode)
 * @param callbackTransport how the OAuth flow receives its redirect; the default binds an ephemeral
 *   loopback listener per flow, while server mode routes the callback through its own listener
 * @param loginHint what an "authorization required" error tells the user to do; defaults to the
 *   `--login` CLI phrasing
 */
internal fun buildSharedHttpUpstream(
    config: ProxyConfig,
    configArg: String? = null,
    interactive: Boolean = true,
    onRequestHeaders: ((Map<String, List<String>>) -> Unit)? = null,
    announceUrl: ((String) -> Unit)? = null,
    openBrowser: ((String) -> Unit)? = null,
    callbackTransport: CallbackTransport? = null,
    loginHint: String? = null,
): SharedHttpUpstream {
    val upstream = config.upstream

    var oauthClient: HttpClient? = null
    val session = upstream.oauth?.let { oauth ->
        oauthClient = buildOAuthHttpClient(config.identity)
        OAuthSession(
            upstreamUrl = upstream.url!!,
            oauth = oauth,
            identity = config.identity,
            store = TokenStore(tokenDir(oauth.tokenDir)),
            http = oauthClient,
            openBrowser = openBrowser ?: BrowserLauncher::open,
            interactive = interactive,
            configPathHint = configArg,
            announceUrl = announceUrl ?: { u -> System.err.println("To authorize, open: $u") },
            callbackTransport = callbackTransport ?: EphemeralCallbackTransport(oauth),
            loginHint = loginHint,
        )
    }

    val client = buildHttpClient(
        upstream = upstream,
        identity = config.identity,
        onRequestHeaders = onRequestHeaders,
        dynamicHeaders = session?.let { it::currentHeaders },
    )

    return SharedHttpUpstream(
        client = client,
        oauthSession = session,
        oauthClient = oauthClient,
        url = upstream.url ?: "",
        sendMcpMethodHeaders = upstream.sendMcpMethodHeaders,
    )
}

/**
 * Where tokens and registrations live: `<proxy home>/tokens` unless configured otherwise, and a
 * configured *relative* path is itself resolved under the home. `$MCP_PROXY_HOME` therefore moves
 * credentials together with configs rather than leaving them behind in `$HOME`.
 */
internal fun tokenDir(configured: String?, env: (String) -> String? = System::getenv): Path {
    val home = proxyHome(env)
    return configured?.let { resolveUnderHome(it, home) } ?: home.resolve("tokens")
}
