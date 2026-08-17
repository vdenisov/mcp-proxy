package org.plukh.mcpproxy.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.plukh.mcpproxy.SharedHttpUpstream
import org.plukh.mcpproxy.buildSharedHttpUpstream
import org.plukh.mcpproxy.config.LoggingConfig
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.UpstreamTransport
import org.plukh.mcpproxy.relay.Endpoint
import org.plukh.mcpproxy.relay.IdentityRewriter
import org.plukh.mcpproxy.upstream.StdioUpstreamEndpoint

private val log = KotlinLogging.logger {}

/**
 * One configured upstream, ready to serve sessions: the parts that are shared (HTTP client, OAuth)
 * built once, and a factory for the parts that are not (one endpoint per session).
 */
internal class UpstreamRuntime(
    val name: String,
    val config: ProxyConfig,
    val shared: SharedHttpUpstream?,
    val loginCoordinator: LoginCoordinator?,
    val redirectUri: String?,
    val responseTimeout: Duration,
) : AutoCloseable {

    val rewriter = IdentityRewriter(config.identity)

    val isOAuth: Boolean get() = config.upstream.oauth != null

    /** A connection of this upstream's own, for one session. */
    fun newSessionUpstream(): Endpoint = when (config.upstream.transport) {
        UpstreamTransport.HTTP -> shared!!.newEndpoint()
        UpstreamTransport.STDIO -> StdioUpstreamEndpoint(
            command = config.upstream.command,
            extraEnv = config.upstream.env,
        )
    }

    override fun close() {
        shared?.close()
    }
}

/**
 * Where a login flow's authorize URL surfaces while it is running.
 *
 * In server mode nothing can open a browser on the user's desktop - the process may be in a
 * container on another machine - so the URL is published here for the login route to redirect to
 * and the status page to show.
 */
class LoginCoordinator {

    /** One in-flight login: the URL it will announce, and the generation that owns it. */
    class Flow(val generation: Long, val url: CompletableDeferred<String>)

    private val lock = Any()
    private var current: Flow? = null
    private var generations = 0L

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Starts a login, or joins one already running.
     *
     * The caller keeps the returned [Flow] and waits on *its* deferred. Handing the instance out
     * rather than exposing an `awaitUrl()` is what makes this safe: a previous flow finishing late
     * used to swap the shared deferred out from under a waiter, which stranded the next login until
     * its timeout - and looked exactly like an authorization server being slow.
     */
    fun beginFlow(): Flow = synchronized(lock) {
        current?.takeIf { !it.url.isCompleted } ?: Flow(++generations, CompletableDeferred()).also {
            current = it
            lastError = null
        }
    }

    /** Publishes the authorize URL of whichever flow is running. */
    fun announce(url: String) = synchronized(lock) {
        val flow = current ?: Flow(++generations, CompletableDeferred()).also { current = it }
        flow.url.complete(url)
        Unit
    }

    fun pendingUrl(): String? = synchronized(lock) { current?.url?.takeIf { it.isCompleted }?.getCompleted() }

    /** Retires [generation]; a later flow's registration is left alone. */
    fun finished(generation: Long, error: String?) = synchronized(lock) {
        lastError = error
        if (current?.generation == generation) current = null
    }
}

/**
 * Strips settings that a per-upstream config may carry but that server mode cannot honour, warning
 * for each.
 *
 * Warn rather than reject, deliberately, and against the stage-2 precedent that rejects HTTP-only
 * settings under stdio. The difference is what being wrong costs: an ignored `authToken` is a
 * credential the user thinks is being sent, while an ignored callback port is a deployment detail
 * with a self-healing fallback - the redirect URI changes, so the next login simply re-registers.
 * Rejecting would force two config files per upstream, which is exactly what naming existing
 * configs was meant to avoid.
 */
internal fun sanitizeForServerMode(config: ProxyConfig, name: String): ProxyConfig {
    if (config.logging.file != null || config.logging.level != LoggingConfig().level) {
        log.warn { "Ignoring the logging block in '$name': server mode logs through the server config" }
    }
    val oauth = config.upstream.oauth
    if (oauth != null && (oauth.callbackUrl != null || oauth.callbackPort != 0 || oauth.callbackBindHost != "127.0.0.1")) {
        log.warn {
            "Ignoring callback settings in '$name': server mode serves the callback on its own " +
                "listener, at <publicUrl>/$name/callback"
        }
    }
    return config.copy(
        logging = LoggingConfig(),
        upstream = config.upstream.copy(
            oauth = oauth?.copy(callbackBindHost = "127.0.0.1", callbackPort = 0, callbackUrl = null),
        ),
    )
}

/** Builds every upstream's runtime. The caller owns closing them. */
internal fun buildRuntimes(
    configs: Map<String, ProxyConfig>,
    publicUrl: String,
    callbacks: CallbackRegistry,
): Map<String, UpstreamRuntime> = configs.mapValues { (name, raw) ->
    val config = sanitizeForServerMode(raw, name)
    val redirectUri = "$publicUrl/$name/callback"
    val coordinator = if (config.upstream.oauth != null) LoginCoordinator() else null

    val shared = if (config.upstream.transport == UpstreamTransport.HTTP) {
        buildSharedHttpUpstream(
            config = config,
            interactive = true,
            // Nothing to open a browser with, and possibly nobody at this machine at all.
            openBrowser = {},
            announceUrl = { url ->
                coordinator?.announce(url)
                log.warn { "Authorization required for '$name': open $publicUrl/$name/login" }
            },
            callbackTransport = SharedCallbackTransport(name, redirectUri, callbacks),
            loginHint = " - authorize at $publicUrl/$name/login",
        )
    } else {
        null
    }

    UpstreamRuntime(
        name = name,
        config = config,
        shared = shared,
        loginCoordinator = coordinator,
        redirectUri = redirectUri.takeIf { config.upstream.oauth != null },
        // A backstop well clear of the paths that produce a real answer: an upstream timeout and an
        // interactive authorization wait both come back as JSON-RPC errors long before this.
        responseTimeout = maxOf(
            config.upstream.requestTimeoutSeconds,
            config.upstream.oauth?.interactiveWaitSeconds ?: 0,
        ).seconds + 10.seconds,
    )
}
