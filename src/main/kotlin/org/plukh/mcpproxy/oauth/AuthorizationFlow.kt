package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.URLBuilder
import java.net.URI
import kotlin.time.Duration
import kotlinx.coroutines.withTimeoutOrNull
import org.plukh.mcpproxy.config.OAuthConfig

private val log = KotlinLogging.logger {}

/**
 * One interactive authorization-code + PKCE flow: build the URL, hand it to the browser, wait for
 * the loopback callback, exchange the code. Everything long-lived (token persistence, refresh,
 * retry policy) lives in [OAuthSession]; this class is one-shot.
 */
class AuthorizationFlow(
    private val oauth: OAuthConfig,
    private val tokenClient: TokenClient,
    private val openBrowser: (String) -> Unit,
    private val announceUrl: (String) -> Unit,
    private val callbackTransport: CallbackTransport = EphemeralCallbackTransport(oauth),
) {

    class Result(val tokens: TokenResponse, val redirectUri: String)

    /**
     * [registrationFor] is called with the redirect URI once the transport can name it - which for
     * the ephemeral loopback listener is only after it has bound, since with an ephemeral port the
     * URI simply does not exist earlier and registering anything else (`:0`, a guess) hands the
     * authorization server a redirect it will rightly refuse.
     */
    suspend fun authorize(
        discovery: DiscoveryResult,
        registrationFor: suspend (redirectUri: String) -> StoredRegistration,
        timeout: Duration,
    ): Result {
        val verifier = Pkce.generateVerifier()
        val state = Pkce.generateState()

        callbackTransport.begin(state).use { callback ->
            val redirectUri = callback.redirectUri
            val registration = registrationFor(redirectUri)

            val authorizeUrl = URLBuilder(discovery.authServer.authorizationEndpoint).apply {
                parameters.append("response_type", "code")
                parameters.append("client_id", registration.clientId)
                parameters.append("redirect_uri", redirectUri)
                parameters.append("code_challenge", Pkce.challengeS256(verifier))
                parameters.append("code_challenge_method", "S256")
                parameters.append("state", state)
                parameters.append("resource", discovery.resource)
                discovery.scope?.let { parameters.append("scope", it) }
            }.buildString()

            // The URL carries the state and challenge - log the host, print the full thing only to
            // the user who is about to open it.
            log.info { "Opening browser for authorization at ${URI(authorizeUrl).host}" }
            announceUrl(authorizeUrl)
            if (oauth.openBrowser) openBrowser(authorizeUrl)

            val received = withTimeoutOrNull(timeout) { callback.await() }
                ?: throw OAuthFlowException("timed out after $timeout waiting for the authorization callback")
            log.info { "Authorization callback received" }

            // RFC 9207: when the AS says it identifies itself on the response, a missing or foreign
            // iss means the response came from somewhere else - a mix-up, not a formality.
            if (discovery.authServer.issParameterSupported == true &&
                received.iss != discovery.authServer.issuer
            ) {
                throw OAuthFlowException(
                    "authorization response iss '${received.iss}' does not match issuer '${discovery.authServer.issuer}'",
                )
            }

            val tokens = tokenClient.exchangeCode(
                tokenEndpoint = discovery.authServer.tokenEndpoint,
                registration = registration,
                code = received.code,
                codeVerifier = verifier,
                redirectUri = redirectUri,
                resource = discovery.resource,
            )
            log.info { "Token obtained, expiresIn=${tokens.expiresIn}, refreshToken=${if (tokens.refreshToken != null) "present" else "absent"}" }
            return Result(tokens, redirectUri)
        }
    }
}
