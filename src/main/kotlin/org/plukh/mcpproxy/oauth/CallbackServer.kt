package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import kotlinx.coroutines.CompletableDeferred

private val log = KotlinLogging.logger {}

/** What the authorization server sent back through the browser. */
data class AuthorizationCallback(
    val code: String,
    /** RFC 9207 issuer identification, when the AS sends it. */
    val iss: String?,
)

/**
 * The loopback listener the browser is redirected back to.
 *
 * Bound before the redirect URI is ever advertised - RFC 8252 says authorization servers must accept
 * any loopback port, but real ones exact-match the registered string, so the port must be known (and
 * registered) first, not hoped for. Lives only for the duration of one flow: a permanently-bound
 * fixed port would collide across the one-JVM-per-upstream deployment model.
 *
 * The listener is reachable by any local process, so it is deliberately hard to abort from outside:
 * a request with an unknown `state` gets a 400 and the wait continues - only the matching state (or
 * an AS error response carrying it, or the timeout) completes the flow.
 */
class CallbackServer(
    private val bindHost: String,
    private val port: Int,
    private val advertisedUrl: String?,
    private val expectedState: String,
) : AutoCloseable {

    private var server: EmbeddedServer<*, *>? = null
    private val result = CompletableDeferred<AuthorizationCallback>()

    /**
     * The path we listen on, taken from [advertisedUrl] when there is one. Hardcoding `/callback`
     * while advertising something else is a silent trap: the authorization server would redirect
     * the browser correctly, the listener would 404, and the flow would die at its timeout with
     * nothing pointing at the cause.
     */
    private val callbackPath: String = advertisedUrl
        ?.let { runCatching { URI(it).path }.getOrNull() }
        ?.takeIf { it.isNotBlank() && it != "/" }
        ?: "/callback"

    /** The redirect URI to register and to send on the authorize request. Call after [start]. */
    lateinit var redirectUri: String
        private set

    /**
     * Where the listener actually bound, which is not [redirectUri] when a public callback URL is
     * advertised. Test seam, and what the log line reports.
     */
    lateinit var boundAddress: String
        private set

    suspend fun start() {
        val engine = embeddedServer(ServerCIO, port = port, host = bindHost) {
            routing {
                get(callbackPath) {
                    val params = call.request.queryParameters
                    val state = params["state"]
                    if (state != expectedState) {
                        log.warn { "Callback with wrong or missing state rejected; still waiting" }
                        call.respondText("Invalid state.", status = HttpStatusCode.BadRequest)
                        return@get
                    }

                    val error = params["error"]
                    if (error != null) {
                        val description = params["error_description"]
                        call.respondText(page("Authorization failed: $error"), io.ktor.http.ContentType.Text.Html)
                        result.completeExceptionally(
                            OAuthFlowException("authorization was denied: $error${description?.let { " - $it" } ?: ""}"),
                        )
                        return@get
                    }

                    val code = params["code"]
                    if (code == null) {
                        call.respondText("Missing code.", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    call.respondText(
                        page("Authorization complete. You can close this window."),
                        io.ktor.http.ContentType.Text.Html,
                    )
                    result.complete(AuthorizationCallback(code = code, iss = params["iss"]))
                }
            }
        }
        engine.start(wait = false)
        server = engine

        val resolvedPort = engine.engine.resolvedConnectors().first().port
        boundAddress = "http://$bindHost:$resolvedPort$callbackPath"
        // The IP literal, not "localhost": RFC 8252 §8.3 - localhost can resolve off-loopback.
        redirectUri = advertisedUrl ?: "http://127.0.0.1:$resolvedPort$callbackPath"
        log.info { "Waiting for OAuth callback on $boundAddress" }
    }

    suspend fun await(): AuthorizationCallback = result.await()

    override fun close() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private fun page(message: String) =
        "<!doctype html><html><body style=\"font-family: sans-serif\"><p>$message</p></body></html>"
}
