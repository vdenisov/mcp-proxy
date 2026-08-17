package org.plukh.mcpproxy.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Codec for everything OAuth - wire documents and stored files.
 *
 * Not [org.plukh.mcpproxy.jsonrpc.JsonRpc.json]: that is the relay's wire codec with
 * `encodeDefaults = true`, wrong for optional RFC fields; and authorization-server metadata carries
 * dozens of fields no client needs, hence `ignoreUnknownKeys`.
 */
internal val OAuthJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // RFC 7591's default for an omitted token_endpoint_auth_method is client_secret_basic, so "none"
    // must actually go over the wire - defaults are encoded, absent-optional nulls are not.
    encodeDefaults = true
}

// --- wire documents ---

/** RFC 9728 protected resource metadata. */
@Serializable
data class ProtectedResourceMetadata(
    val resource: String? = null,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
)

/** RFC 8414 / OIDC authorization server metadata - only the fields a client acts on. */
@Serializable
data class AuthServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>? = null,
    @SerialName("authorization_response_iss_parameter_supported") val issParameterSupported: Boolean? = null,
)

/** RFC 7591 registration request. */
@Serializable
data class RegistrationRequest(
    @SerialName("client_name") val clientName: String,
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
    val scope: String? = null,
)

/** RFC 7591 registration response. The AS may override what was requested; what it says goes. */
@Serializable
data class RegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long? = null,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @SerialName("redirect_uris") val redirectUris: List<String>? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    /**
     * Defaulted rather than required: the spec says a server must send it, but one that does not
     * has still issued a perfectly good token, and failing the parse would report that as
     * "unparseable response". We only ever issue Bearer headers anyway.
     */
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

/** RFC 6749 §5.2 error body. */
@Serializable
data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

// --- stored records ---

@Serializable
data class StoredTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Absolute expiry; null when the AS sent no `expires_in`. */
    val expiresAtEpochSeconds: Long? = null,
    val scope: String? = null,
    /** The canonical resource URI these tokens were issued for. */
    val resource: String,
    /** Kept here so `--logout --forget-client` can find the registration without a network round. */
    val issuer: String,
    val obtainedAtEpochSeconds: Long,
)

@Serializable
data class StoredRegistration(
    val issuer: String,
    val clientId: String,
    val clientSecret: String? = null,
    /** RFC 7591 semantics: 0 = never expires; null = no secret / not reported. */
    val clientSecretExpiresAt: Long? = null,
    /** The auth method the AS resolved for us - may differ from the "none" we asked for. */
    val tokenEndpointAuthMethod: String,
    val redirectUris: List<String>,
    val issuedAtEpochSeconds: Long,
)
