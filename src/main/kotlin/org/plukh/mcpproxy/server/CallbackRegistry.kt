package org.plukh.mcpproxy.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.plukh.mcpproxy.oauth.AuthorizationCallback
import org.plukh.mcpproxy.oauth.CallbackTransport
import org.plukh.mcpproxy.oauth.OAuthFlowException

private val log = KotlinLogging.logger {}

/**
 * Flows waiting for their redirect, keyed by the single-use `state` they generated.
 *
 * The shared listener's answer to what [org.plukh.mcpproxy.oauth.CallbackServer] does per flow, and
 * it keeps that class's load-bearing property: a request carrying an unknown state is refused and
 * the genuine flow *keeps waiting*. The listener is reachable by anything on the host, so a hostile
 * local process must not be able to cancel someone's login by guessing at it.
 */
class CallbackRegistry {

    private class Pending(val upstreamName: String, val result: CompletableDeferred<AuthorizationCallback>)

    private val pending = ConcurrentHashMap<String, Pending>()

    internal fun register(upstreamName: String, state: String): CompletableDeferred<AuthorizationCallback> {
        val result = CompletableDeferred<AuthorizationCallback>()
        pending[state] = Pending(upstreamName, result)
        return result
    }

    internal fun forget(state: String) {
        pending.remove(state)
    }

    /**
     * Delivers a callback that arrived on [upstreamName]'s route.
     *
     * The upstream is checked as well as the state: a state registered for one upstream must not be
     * completable through another's callback path, or a flow could be steered by whoever knows its
     * state to a route with a different redirect URI.
     *
     * @return false when nothing was waiting for this state here, which the route answers with 400
     */
    fun complete(upstreamName: String, state: String?, code: String?, error: String?, iss: String?): Boolean {
        val waiting = state?.let { pending[it] } ?: return false
        if (waiting.upstreamName != upstreamName) {
            log.warn { "Callback for state of '${waiting.upstreamName}' arrived on '$upstreamName', refusing" }
            return false
        }
        pending.remove(state)
        return when {
            error != null -> waiting.result.completeExceptionally(OAuthFlowException("authorization failed: $error"))
            code != null -> waiting.result.complete(AuthorizationCallback(code, iss))
            else -> waiting.result.completeExceptionally(OAuthFlowException("authorization response had neither code nor error"))
        }
    }

    internal fun pendingCount(): Int = pending.size
}

/**
 * The server's transport: no listener of its own, a redirect URI that never changes.
 *
 * That stability is the point of the whole arrangement. With an ephemeral port every login
 * registers a fresh client with the authorization server - services that surface redirect URIs
 * accumulate a row per login - whereas a fixed path on a known port is registered once and reused
 * forever.
 */
class SharedCallbackTransport(
    private val upstreamName: String,
    override val redirectUriValue: String,
    private val registry: CallbackRegistry,
) : CallbackTransport, RedirectUriHolder {

    override suspend fun begin(state: String): CallbackTransport.Handle {
        val result = registry.register(upstreamName, state)
        return object : CallbackTransport.Handle {
            override val redirectUri: String = redirectUriValue
            override suspend fun await(): AuthorizationCallback = result.await()
            override fun close() = registry.forget(state)
        }
    }
}

/** Lets the status page and login route name the redirect URI without starting a flow. */
interface RedirectUriHolder {
    val redirectUriValue: String
}
