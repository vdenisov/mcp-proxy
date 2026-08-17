package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * Dynamic client registration (RFC 7591). This is the stage 3 payoff: the `client_name` the
 * authorization server stores comes from `identity.name`, not from whichever real client sits
 * behind the proxy.
 */
class DcrClient(private val http: HttpClient, private val clock: Clock = Clock.systemUTC()) {

    /**
     * Registers a public client. The response wins over the request - an AS may force a client
     * secret and a different auth method, and pretending otherwise just fails later at the token
     * endpoint.
     */
    suspend fun register(
        registrationEndpoint: String,
        issuer: String,
        clientName: String,
        redirectUri: String,
        scope: String?,
    ): StoredRegistration {
        val request = RegistrationRequest(
            clientName = clientName,
            redirectUris = listOf(redirectUri),
            scope = scope,
        )
        log.info { "Registering OAuth client '$clientName' at $registrationEndpoint" }

        val response = http.post(registrationEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(OAuthJson.encodeToString(RegistrationRequest.serializer(), request))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { OAuthJson.decodeFromString<OAuthErrorResponse>(body) }.getOrNull()
            throw OAuthFlowException(
                "client registration failed with HTTP ${response.status.value}" +
                    (error?.let { ": ${it.error}${it.errorDescription?.let { d -> " - $d" } ?: ""}" } ?: ""),
            )
        }

        val registered = try {
            OAuthJson.decodeFromString<RegistrationResponse>(body)
        } catch (e: Exception) {
            throw OAuthFlowException("registration endpoint returned an unparseable response", e)
        }

        val method = registered.tokenEndpointAuthMethod ?: "none"
        if (method != "none" && registered.clientSecret == null) {
            throw OAuthFlowException("authorization server requires '$method' but issued no client secret")
        }
        log.info { "Registered OAuth client, clientId=${registered.clientId}, tokenEndpointAuthMethod=$method" }

        return StoredRegistration(
            issuer = issuer,
            clientId = registered.clientId,
            clientSecret = registered.clientSecret,
            clientSecretExpiresAt = registered.clientSecretExpiresAt,
            tokenEndpointAuthMethod = method,
            redirectUris = registered.redirectUris ?: listOf(redirectUri),
            issuedAtEpochSeconds = registered.clientIdIssuedAt ?: clock.instant().epochSecond,
        )
    }
}
