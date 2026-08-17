package org.plukh.mcpproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The token endpoint: authorization-code exchange and refresh. One class because both requests share
 * the client-authentication rules - whatever method the AS resolved at registration time
 * (`none` + PKCE for the public client we ask to be, or a forced secret) applies to both.
 */
class TokenClient(private val http: HttpClient) {

    suspend fun exchangeCode(
        tokenEndpoint: String,
        registration: StoredRegistration,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String,
    ): TokenResponse = request(tokenEndpoint, registration) {
        append("grant_type", "authorization_code")
        append("code", code)
        append("code_verifier", codeVerifier)
        append("redirect_uri", redirectUri)
        append("resource", resource)
    }

    suspend fun refresh(
        tokenEndpoint: String,
        registration: StoredRegistration,
        refreshToken: String,
        resource: String,
    ): TokenResponse = request(tokenEndpoint, registration) {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
        // Scope is deliberately omitted (RFC 6749 default: as originally granted); resource is not -
        // RFC 8707 applies to every token request, refresh included.
        append("resource", resource)
    }

    private suspend fun request(
        tokenEndpoint: String,
        registration: StoredRegistration,
        // Not named `build`: inside Parameters.build the receiver's own build() would shadow it,
        // silently dropping every grant parameter.
        grantParams: io.ktor.http.ParametersBuilder.() -> Unit,
    ): TokenResponse {
        val response = http.submitForm(
            url = tokenEndpoint,
            formParameters = Parameters.build {
                grantParams()
                when (registration.tokenEndpointAuthMethod) {
                    "client_secret_post" -> {
                        append("client_id", registration.clientId)
                        append("client_secret", checkNotNull(registration.clientSecret))
                    }
                    "client_secret_basic" -> {} // header below
                    else -> append("client_id", registration.clientId) // public client
                }
            },
        ) {
            if (registration.tokenEndpointAuthMethod == "client_secret_basic") {
                // RFC 6749 §2.3.1: both halves are form-urlencoded *before* base64. Skipping this
                // works until a generated secret contains '+', '%' or a space, and then fails as
                // invalid_client - which this client would misread as "the server forgot our
                // registration" and answer by re-registering, forever.
                val credentials = formEncode(registration.clientId) + ":" +
                    formEncode(checkNotNull(registration.clientSecret))
                header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
            }
        }
        return decode(response)
    }

    private fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private suspend fun decode(response: HttpResponse): TokenResponse {
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            return try {
                OAuthJson.decodeFromString<TokenResponse>(body)
            } catch (e: Exception) {
                throw OAuthFlowException("token endpoint returned an unparseable response", e)
            }
        }
        val error = runCatching { OAuthJson.decodeFromString<OAuthErrorResponse>(body) }.getOrNull()
            ?: throw OAuthFlowException("token endpoint returned HTTP ${response.status.value}")
        throw OAuthErrorException(error.error, error.errorDescription, response.status.value)
    }
}
