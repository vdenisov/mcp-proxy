package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.config.OAuthConfig
import org.plukh.mcpproxy.upstream.UpstreamTokenSource

private val log = KotlinLogging.logger {}

/**
 * The token lifecycle for one OAuth-gated upstream: discovery, registration reuse, the interactive
 * flow, proactive and reactive refresh, rotation-safe persistence.
 *
 * Single-flight: a Mutex serializes all token work, and a generation counter tells a caller that
 * lost the race whether someone else already fixed the problem - a concurrent POST-401 and SSE-401
 * must produce one refresh (or one browser flow), not two.
 */
class OAuthSession(
    private val upstreamUrl: String,
    private val oauth: OAuthConfig,
    private val identity: IdentityConfig,
    private val store: TokenStore,
    private val http: HttpClient,
    private val openBrowser: (String) -> Unit = BrowserLauncher::open,
    /** False in audit mode: never a browser, never a wait - fail with the actionable message. */
    private val interactive: Boolean = true,
    /** Shown in "run: mcp-proxy --login <hint>" messages. */
    private val configPathHint: String? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val announceUrl: (String) -> Unit = { url -> System.err.println("To authorize, open: $url") },
    /** How the flow receives its redirect; server mode routes it through the shared listener. */
    private val callbackTransport: CallbackTransport = EphemeralCallbackTransport(oauth),
    /**
     * What an "authorization required" error tells the user to do. Server mode points at its own
     * login page, because there is no terminal in front of a container to run `--login` in.
     */
    private val loginHint: String? = null,
) : UpstreamTokenSource, AutoCloseable {

    private val lock = Mutex()

    @Volatile
    private var generation = 0L

    @Volatile
    private var current: StoredTokens? = store.loadTokens(upstreamUrl)

    private val discovery = Discovery(http, oauth.assumePkceS256)
    private val tokenClient = TokenClient(http)
    private val dcr = DcrClient(http, clock)

    /**
     * A lazy flow that outlived its caller's patience: the serve-time wait is bounded, but the flow
     * keeps running so the user finishing in the browser still lands a token for the next attempt.
     */
    private var detachedFlow: kotlinx.coroutines.Deferred<Unit>? = null
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun currentHeaders(): Map<String, String> =
        current?.let { mapOf("Authorization" to "Bearer ${it.accessToken}") } ?: emptyMap()

    override suspend fun ensureToken() {
        lock.withLock {
            val tokens = current
            when {
                tokens == null -> obtainInteractivelyLocked(wwwAuthenticate = null)
                expiringSoon(tokens) -> refreshOrReauthorizeLocked(tokens, wwwAuthenticate = null)
                else -> Unit
            }
        }
    }

    override suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean {
        val seen = generation
        lock.withLock {
            // Someone else already rotated the token while this caller was waiting for the lock -
            // or a parallel --login run landed one on disk. Either way: retry with what exists now.
            if (generation != seen) return current != null
            store.loadTokens(upstreamUrl)?.let { onDisk ->
                if (onDisk.accessToken != current?.accessToken) {
                    log.info { "Newer tokens found on disk, using them" }
                    install(onDisk)
                    return true
                }
            }
            val tokens = current
            if (tokens?.refreshToken != null) {
                refreshOrReauthorizeLocked(tokens, wwwAuthenticate)
            } else {
                obtainInteractivelyLocked(wwwAuthenticate)
            }
            return current != null
        }
    }

    /**
     * The `--login` entry point: always interactive, and waits for the flow with **no bound of its
     * own**. There is nothing for this caller to fall back to - unlike a mid-session request, which
     * gives up early so the client gets an actionable error while the browser stays open - so a
     * second clock here can only fire too early. It used to be set to `authTimeoutSeconds`, the same
     * budget as the flow, and since the caller's clock starts first (the flow spends its head start
     * on discovery and, on a first run, dynamic registration) it always won: an abandoned login
     * reported "authorization is pending in the browser" for a flow that was about to be cancelled
     * by the process exiting. A fixed margin only narrows that window - slow DNS or the
     * `invalid_client` branch's second `authorize` can outlast any guess. The flow bounds itself.
     */
    suspend fun login(): StoredTokens {
        lock.withLock { runFlowLocked(wwwAuthenticate = null, timeoutSeconds = null) }
        return checkNotNull(current)
    }

    // --- internals; every *Locked function assumes the mutex is held ---

    // Synchronized rather than relying on the mutex: the detached flow finishes on its own scope,
    // possibly long after the caller that started it gave up and released the lock.
    @Synchronized
    private fun install(tokens: StoredTokens) {
        current = tokens
        generation++
    }

    private fun expiringSoon(tokens: StoredTokens): Boolean {
        val expiresAt = tokens.expiresAtEpochSeconds ?: return false
        return clock.instant().epochSecond >= expiresAt - REFRESH_SKEW_SECONDS
    }

    private suspend fun refreshOrReauthorizeLocked(tokens: StoredTokens, wwwAuthenticate: String?) {
        val refreshToken = tokens.refreshToken
        if (refreshToken == null) {
            obtainInteractivelyLocked(wwwAuthenticate)
            return
        }

        val disco = discoverLocked(wwwAuthenticate)
        // Refreshing needs client credentials but no redirect URI; without a usable registration
        // the only way forward is the interactive flow, which will register one.
        val registration = configuredOrCachedRegistration(disco.authServer.issuer)
        if (registration == null) {
            obtainInteractivelyLocked(wwwAuthenticate)
            return
        }

        repeat(REFRESH_ATTEMPTS) { attempt ->
            try {
                val response = tokenClient.refresh(
                    tokenEndpoint = disco.authServer.tokenEndpoint,
                    registration = registration,
                    refreshToken = refreshToken,
                    resource = disco.resource,
                )
                persist(response, disco, fallbackRefreshToken = refreshToken)
                log.info { "Access token refreshed" }
                return
            } catch (e: OAuthErrorException) {
                if (e.error == "invalid_grant") {
                    // The grant is dead - rotation reuse, revocation, expiry. Only a human fixes this.
                    log.info { "Refresh token rejected (invalid_grant); re-authorization required" }
                    store.deleteTokens(upstreamUrl)
                    current = null
                    obtainInteractivelyLocked(wwwAuthenticate)
                    return
                }
                if (e.error == "invalid_client") {
                    log.warn { "Authorization server no longer knows our client; re-registering" }
                    store.deleteRegistration(disco.authServer.issuer)
                    obtainInteractivelyLocked(wwwAuthenticate)
                    return
                }
                throw authRequired("token refresh failed: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Not a transient failure: shutdown or a client disconnect. Swallowing it here would
                // exhaust the attempts and hand the caller "run --login" for something a login
                // cannot fix.
                throw e
            } catch (e: Exception) {
                // Transient - a 5xx or a network hiccup must not burn a rotating grant. Backed off,
                // because three requests inside a millisecond ride out neither.
                log.warn(e) { "Token refresh attempt ${attempt + 1}/$REFRESH_ATTEMPTS failed" }
                if (attempt < REFRESH_ATTEMPTS - 1) delay(RETRY_BACKOFF * (attempt + 1))
            }
        }
        throw authRequired("token refresh kept failing")
    }

    private suspend fun obtainInteractivelyLocked(wwwAuthenticate: String?) {
        if (!interactive) throw authRequired("no usable cached token")
        runFlowLocked(wwwAuthenticate, timeoutSeconds = oauth.interactiveWaitSeconds)
    }

    /**
     * Runs (or joins) the interactive flow. The flow itself always gets the full
     * [OAuthConfig.authTimeoutSeconds]; what [timeoutSeconds] bounds is how long *this caller* waits
     * for it - a request that triggered the flow mid-session gives up early with the actionable
     * error while the user is still in the browser, and their eventual completion lands the token
     * for the retry. `--login` passes null: it has nothing to fall back to, so it waits for the
     * flow's own verdict.
     */
    private suspend fun runFlowLocked(wwwAuthenticate: String?, timeoutSeconds: Long?) {
        val flow = detachedFlow?.takeIf { it.isActive } ?: detachedScope.async {
            val disco = discoverLocked(wwwAuthenticate)
            val flowRunner = AuthorizationFlow(oauth, tokenClient, openBrowser, announceUrl, callbackTransport)
            try {
                val result = flowRunner.authorize(
                    disco,
                    registrationFor = { redirectUri -> registrationFor(disco, redirectUri) },
                    timeout = oauth.authTimeoutSeconds.seconds,
                )
                persist(result.tokens, disco, fallbackRefreshToken = null)
            } catch (e: OAuthErrorException) {
                if (e.error == "invalid_client") {
                    // The AS purged our registration - a documented real-world behavior, not an edge case.
                    log.warn { "Authorization server no longer knows our client; re-registering and retrying once" }
                    store.deleteRegistration(disco.authServer.issuer)
                    val result = flowRunner.authorize(
                        disco,
                        registrationFor = { redirectUri -> registrationFor(disco, redirectUri) },
                        timeout = oauth.authTimeoutSeconds.seconds,
                    )
                    persist(result.tokens, disco, fallbackRefreshToken = null)
                } else {
                    throw e
                }
            }
        }.also { detachedFlow = it }

        val completed = try {
            if (timeoutSeconds == null) flow.await() else withTimeoutOrNull(timeoutSeconds.seconds) { flow.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AuthRequiredException) {
            detachedFlow = null
            throw e
        } catch (e: Throwable) {
            detachedFlow = null
            throw authRequired("authorization failed: ${e.message}")
        }
        if (completed == null) {
            // The flow keeps running detached; this caller's patience ran out, not the flow.
            throw authRequired("authorization is pending in the browser")
        }
    }

    private suspend fun discoverLocked(wwwAuthenticate: String?): DiscoveryResult {
        val result = discovery.discover(upstreamUrl, wwwAuthenticate)
        return if (oauth.scopes.isNotEmpty()) result.copy(scope = oauth.scopes.joinToString(" ")) else result
    }

    /**
     * A registration usable without a redirect URI - the refresh path. Pre-configured credentials
     * win; a cached registration counts unless its secret expired.
     */
    private fun configuredOrCachedRegistration(issuer: String): StoredRegistration? {
        preConfiguredRegistration(issuer)?.let { return it }
        return store.loadRegistration(issuer)?.takeIf { !secretExpired(it) }
    }

    /**
     * A registration for the interactive flow, where the redirect URI must actually be registered.
     * Cached ones are reused only when they cover the URI - ephemeral ports change every login, and
     * real authorization servers exact-match the registered string despite RFC 8252's loopback
     * port-flexibility rule.
     */
    private suspend fun registrationFor(disco: DiscoveryResult, redirectUri: String): StoredRegistration {
        val issuer = disco.authServer.issuer

        preConfiguredRegistration(issuer)?.let { return it }

        store.loadRegistration(issuer)?.let { cached ->
            when {
                secretExpired(cached) -> log.info { "Cached registration's client secret expired; re-registering" }
                redirectUri !in cached.redirectUris ->
                    log.info { "Cached registration does not cover $redirectUri; re-registering" }

                else -> {
                    log.info { "Reusing OAuth client registration for $issuer, clientId=${cached.clientId}" }
                    return cached
                }
            }
        }

        val endpoint = disco.authServer.registrationEndpoint
            ?: throw authRequired(
                "authorization server $issuer does not support dynamic registration; " +
                    "set upstream.oauth.clientId to a manually registered client",
            )

        val registration = dcr.register(
            registrationEndpoint = endpoint,
            issuer = issuer,
            clientName = oauth.clientName ?: identity.name,
            redirectUri = redirectUri,
            scope = disco.scope,
        )
        store.saveRegistration(registration)
        return registration
    }

    private fun preConfiguredRegistration(issuer: String): StoredRegistration? = oauth.clientId?.let { clientId ->
        StoredRegistration(
            issuer = issuer,
            clientId = clientId,
            clientSecret = oauth.clientSecret,
            tokenEndpointAuthMethod = if (oauth.clientSecret != null) "client_secret_post" else "none",
            redirectUris = emptyList(), // pre-registered clients manage their own redirect URIs
            issuedAtEpochSeconds = 0,
        )
    }

    /** RFC 7591: 0 means never expires. */
    private fun secretExpired(registration: StoredRegistration): Boolean =
        registration.clientSecretExpiresAt?.let { it != 0L && it <= clock.instant().epochSecond } == true

    private fun persist(response: TokenResponse, disco: DiscoveryResult, fallbackRefreshToken: String?) {
        val now = clock.instant().epochSecond
        val tokens = StoredTokens(
            accessToken = response.accessToken,
            // Rotation: a missing refresh_token in the response means "keep using the old one".
            refreshToken = response.refreshToken ?: fallbackRefreshToken,
            expiresAtEpochSeconds = response.expiresIn?.let { now + it },
            scope = response.scope,
            resource = disco.resource,
            issuer = disco.authServer.issuer,
            obtainedAtEpochSeconds = now,
        )
        // Disk first: with rotation, losing the new refresh token to a crash strands the grant.
        store.saveTokens(upstreamUrl, tokens)
        install(tokens)
    }

    private fun authRequired(reason: String): AuthRequiredException {
        val hint = loginHint
            ?: configPathHint?.let { " - run: mcp-proxy --login $it" }
            ?: " - run mcp-proxy --login with this config"
        return AuthRequiredException("authorization required for $upstreamUrl ($reason)$hint")
    }

    /**
     * Releases the scope the detached login flow runs on. Idempotent; a flow still parked on the
     * browser is cancelled with it, which is correct - by the time the owner is closing, nobody is
     * left to receive the token.
     */
    override fun close() {
        detachedScope.cancel()
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 120L
        const val REFRESH_ATTEMPTS = 3
        val RETRY_BACKOFF = 500.milliseconds
    }
}
