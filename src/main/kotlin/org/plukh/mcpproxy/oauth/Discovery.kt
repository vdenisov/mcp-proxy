package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URI

private val log = KotlinLogging.logger {}

/** Everything learned about who authorizes access to an MCP server, and how. */
data class DiscoveryResult(
    val resource: String,
    /** The scope to request: the 401 challenge's if present (authoritative), else PRM's, else null. */
    val scope: String?,
    val authServer: AuthServerMetadata,
)

class DiscoveryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Finds the authorization server for an MCP endpoint: protected-resource metadata (RFC 9728) from
 * the 401's `WWW-Authenticate` or well-known fallbacks, then authorization-server metadata
 * (RFC 8414 / OIDC) probed in the compatibility order the MCP spec prescribes.
 */
class Discovery(
    private val http: HttpClient,
    private val assumePkceS256: Boolean = false,
) {

    suspend fun discover(mcpUrl: String, wwwAuthenticate: String?): DiscoveryResult {
        val canonical = canonicalResourceUri(mcpUrl)
        val resourceOrigin = originOf(mcpUrl)
        val challenge = wwwAuthenticate
            ?.let { parseWwwAuthenticate(it) }
            ?.firstOrNull { it.scheme.equals("Bearer", ignoreCase = true) }

        // The 401 that starts this flow comes from a server we have not authenticated yet, so its
        // metadata pointer is untrusted input. A cross-origin pointer is refused (RFC 9728 §3.3):
        // following one would let a hostile upstream nominate its own authorization server, and the
        // damage is not a bad token - it is the user's browser being opened at an attacker's consent
        // page at the exact moment they are expecting to log in.
        val challengeUrl = challenge?.resourceMetadata?.let { url ->
            if (sameOrigin(url, mcpUrl)) {
                url
            } else {
                log.warn {
                    "Ignoring cross-origin resource_metadata pointer '$url' from the 401 challenge; " +
                        "it does not belong to $resourceOrigin"
                }
                null
            }
        }

        val (prm, prmSource) = fetchFirst<ProtectedResourceMetadata>(
            prmCandidates(mcpUrl, challengeUrl),
            "protected resource metadata",
            // Redirects are followed by default, so the origin has to hold for where we *landed*,
            // not merely for what we asked for.
            requiredOrigin = resourceOrigin,
        )
        log.info { "Protected resource metadata from $prmSource, authorizationServers=${prm.authorizationServers}" }

        // The PRM's resource is the server's own statement of its identifier - prefer it, but only
        // when it actually covers the URL we are talking to, otherwise a misconfigured (or hostile)
        // metadata document could redirect tokens to a different audience. Coverage is tested on
        // path segments: `/mcp` must not be taken to cover `/mcp-internal`.
        val prmResource = prm.resource?.let(::canonicalResourceUri)
        val resource = when {
            prmResource == null -> canonical
            canonical == prmResource || canonical.startsWith("$prmResource/") -> prmResource
            else -> {
                log.warn { "PRM resource '${prm.resource}' does not cover the upstream url, using $canonical" }
                canonical
            }
        }

        if (prm.authorizationServers.isEmpty()) {
            throw DiscoveryException("protected resource metadata lists no authorization servers")
        }

        // Try each advertised authorization server in turn: a PRM may list several, and one being
        // unreachable or inconsistent is not a reason to give up on the rest.
        val asFailures = mutableListOf<String>()
        var found: Triple<String, AuthServerMetadata, String>? = null
        for (issuer in prm.authorizationServers) {
            val attempt = try {
                fetchFirst<AuthServerMetadata>(asMetadataCandidates(issuer), "authorization server metadata")
            } catch (e: DiscoveryException) {
                asFailures += "$issuer: ${e.message}"
                continue
            }
            if (canonicalResourceUri(attempt.first.issuer) != canonicalResourceUri(issuer)) {
                asFailures += "$issuer: metadata declares issuer '${attempt.first.issuer}'"
                continue
            }
            found = Triple(issuer, attempt.first, attempt.second)
            break
        }

        val (_, asMetadata, asSource) = found
            ?: throw DiscoveryException("no usable authorization server; tried: ${asFailures.joinToString("; ")}")
        log.info { "Authorization server metadata from $asSource, issuer=${asMetadata.issuer}" }

        val methods = asMetadata.codeChallengeMethodsSupported
        if (methods == null && !assumePkceS256) {
            throw DiscoveryException(
                "authorization server does not advertise code_challenge_methods_supported; " +
                    "refusing to proceed without PKCE (set oauth.assumePkceS256: true to override)",
            )
        }
        if (methods != null && methods.none { it == "S256" }) {
            throw DiscoveryException("authorization server does not support PKCE S256, got: $methods")
        }

        // The challenged scope is authoritative for this request; scopes_supported is the fallback.
        val scope = challenge?.scope ?: prm.scopesSupported?.takeIf { it.isNotEmpty() }?.joinToString(" ")

        return DiscoveryResult(resource = resource, scope = scope, authServer = asMetadata)
    }

    // Decoded by hand rather than via ContentNegotiation - the OAuth client stays plugin-free on
    // purpose, and a metadata document served with a sloppy content type should still parse.
    private suspend inline fun <reified T> fetchFirst(
        candidates: List<String>,
        what: String,
        requiredOrigin: String? = null,
    ): Pair<T, String> {
        val failures = mutableListOf<String>()
        for (url in candidates) {
            log.debug { "Probing $what at $url" }
            try {
                val response = http.get(url)
                val landedAt = response.call.request.url.toString()
                if (requiredOrigin != null && originOf(landedAt) != requiredOrigin) {
                    failures += "$url -> redirected off-origin to ${originOf(landedAt)}"
                    continue
                }
                if (response.status.isSuccess()) {
                    return OAuthJson.decodeFromString<T>(response.bodyAsText()) to url
                }
                failures += "$url -> ${response.status.value}"
            } catch (e: DiscoveryException) {
                throw e
            } catch (e: Exception) {
                failures += "$url -> ${e.message}"
            }
        }
        throw DiscoveryException("could not fetch $what; tried: ${failures.joinToString("; ")}")
    }

    companion object {

        /**
         * Protected-resource-metadata URLs to try, in order: whatever the 401 pointed at, then the
         * path-aware well-known form, then the root form.
         */
        internal fun prmCandidates(mcpUrl: String, challengeUrl: String?): List<String> = buildList {
            challengeUrl?.let { add(it) }
            val uri = URI(mcpUrl)
            val origin = origin(uri)
            val path = uri.path.orEmpty().trimEnd('/')
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()

        /**
         * Authorization-server-metadata URLs, in the MCP spec's compatibility order. The odd shapes
         * exist because RFC 8414 *inserts* the well-known segment before the issuer path while OIDC
         * historically *appended* it, and real servers exist on every side of that disagreement.
         */
        internal fun asMetadataCandidates(issuer: String): List<String> {
            val uri = URI(issuer)
            val origin = origin(uri)
            val path = uri.path.orEmpty().trimEnd('/')
            return if (path.isNotEmpty()) {
                listOf(
                    "$origin/.well-known/oauth-authorization-server$path",
                    "$origin/.well-known/openid-configuration$path",
                    "$origin$path/.well-known/openid-configuration",
                )
            } else {
                listOf(
                    "$origin/.well-known/oauth-authorization-server",
                    "$origin/.well-known/openid-configuration",
                )
            }
        }

        private fun origin(uri: URI): String =
            uri.scheme + "://" + uri.host + if (uri.port == -1) "" else ":${uri.port}"

        /** Scheme, host and port of a URL, with default ports normalised away. */
        internal fun originOf(url: String): String {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return url
            val host = uri.host?.lowercase() ?: return url
            val port = when {
                uri.port == -1 -> ""
                scheme == "https" && uri.port == 443 -> ""
                scheme == "http" && uri.port == 80 -> ""
                else -> ":${uri.port}"
            }
            return "$scheme://$host$port"
        }

        internal fun sameOrigin(a: String, b: String): Boolean =
            runCatching { originOf(a) == originOf(b) }.getOrDefault(false)
    }
}
