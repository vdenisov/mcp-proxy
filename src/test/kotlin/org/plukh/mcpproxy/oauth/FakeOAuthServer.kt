package org.plukh.mcpproxy.oauth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An OAuth-gated MCP server in one embedded listener: the resource (`/mcp`), the RFC 9728/8414
 * well-knowns, and a complete little authorization server (`/register`, `/authorize`, `/token`)
 * with PKCE verification and refresh rotation.
 *
 * Scripted through [behaviour] the way `HttpEndpointStreamTest`'s stub is - one server, many
 * scenarios - and it records everything a test could want to assert: registration bodies, the
 * `resource` parameter as seen on authorize and token requests, and every token generation.
 */
class FakeOAuthServer {

    enum class Behaviour {
        HAPPY,

        /** The access token dies right after the first authenticated MCP response - the mid-session 401. */
        EXPIRE_TOKEN_AFTER_FIRST_USE,

        /** The access token dies right after `notifications/initialized`, so the GET/SSE connect 401s. */
        EXPIRE_TOKEN_BEFORE_SSE,

        /** Refresh requests are answered with invalid_grant. */
        REFRESH_INVALID_GRANT,

        /** The 401 carries no WWW-Authenticate; discovery must fall back to the well-known URLs. */
        NO_WWW_AUTHENTICATE,

        /** The authorize redirect carries a wrong state - a forged or mixed-up callback. */
        WRONG_STATE,
    }

    val behaviour = AtomicReference(Behaviour.HAPPY)

    val registrations = CopyOnWriteArrayList<String>()
    val authorizeResources = CopyOnWriteArrayList<String?>()
    val tokenResources = CopyOnWriteArrayList<String?>()
    val mcpRequests = CopyOnWriteArrayList<String>()

    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    private val validRefreshTokens = ConcurrentHashMap.newKeySet<String>()
    private val pendingCodes = ConcurrentHashMap<String, String>() // code -> expected S256 challenge
    private val counter = AtomicInteger(0)
    private val json = Json

    private var server: EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    val mcpUrl get() = "http://127.0.0.1:$port/mcp"
    private val origin get() = "http://127.0.0.1:$port"

    suspend fun start() {
        val engine = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/mcp") { handleMcp() }
                get("/mcp") { handleMcpStream() }

                get("/.well-known/oauth-protected-resource") { respondPrm() }
                get("/.well-known/oauth-protected-resource/mcp") { respondPrm() }
                get("/.well-known/oauth-authorization-server") {
                    call.respondText(
                        """
                        {"issuer":"$origin",
                         "authorization_endpoint":"$origin/authorize",
                         "token_endpoint":"$origin/token",
                         "registration_endpoint":"$origin/register",
                         "code_challenge_methods_supported":["S256"]}
                        """.trimIndent(),
                        ContentType.Application.Json,
                    )
                }

                post("/register") {
                    registrations += call.receiveText()
                    call.respondText(
                        """{"client_id":"fake-client-${counter.incrementAndGet()}","token_endpoint_auth_method":"none"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }

                get("/authorize") {
                    val params = call.request.queryParameters
                    authorizeResources += params["resource"]
                    val code = "code-${counter.incrementAndGet()}"
                    pendingCodes[code] = params["code_challenge"]!!
                    val state =
                        if (behaviour.get() == Behaviour.WRONG_STATE) "forged-state" else params["state"]!!
                    call.response.headers.append(
                        "Location",
                        "${params["redirect_uri"]}?code=$code&state=$state",
                    )
                    call.respondText("", status = HttpStatusCode.Found)
                }

                post("/token") { handleToken() }
            }
        }
        engine.start(wait = false)
        server = engine
        port = engine.engine.resolvedConnectors().first().port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondPrm() {
        call.respondText(
            """{"resource":"$origin/mcp","authorization_servers":["$origin"]}""",
            ContentType.Application.Json,
        )
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleMcp() {
        val token = bearer()
        if (token == null || token !in validTokens) {
            if (behaviour.get() != Behaviour.NO_WWW_AUTHENTICATE) {
                call.response.headers.append(
                    "WWW-Authenticate",
                    """Bearer resource_metadata="$origin/.well-known/oauth-protected-resource"""",
                )
            }
            call.respondText("", status = HttpStatusCode.Unauthorized)
            return
        }

        val body = call.receiveText()
        mcpRequests += body
        val frame = json.parseToJsonElement(body).jsonObject
        val method = frame["method"]?.jsonPrimitive?.content
        val id = frame["id"]

        if (id == null) {
            // A notification. `notifications/initialized` is also the trigger for the
            // expire-before-SSE scenario: the GET that follows must find the token dead.
            if (method == "notifications/initialized" && behaviour.get() == Behaviour.EXPIRE_TOKEN_BEFORE_SSE) {
                validTokens.remove(token)
            }
            call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
            return
        }

        call.respondText(
            """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"serverInfo":{"name":"fake-oauth-server","version":"1"},"echo":$body}}""",
            ContentType.Application.Json,
        )
        if (behaviour.get() == Behaviour.EXPIRE_TOKEN_AFTER_FIRST_USE) {
            behaviour.set(Behaviour.HAPPY) // expire once, not on every request
            validTokens.remove(token)
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleMcpStream() {
        val token = bearer()
        if (token == null || token !in validTokens) {
            call.response.headers.append(
                "WWW-Authenticate",
                """Bearer resource_metadata="$origin/.well-known/oauth-protected-resource"""",
            )
            call.respondText("", status = HttpStatusCode.Unauthorized)
            return
        }
        call.respondTextWriter(ContentType.Text.EventStream) {
            write("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/from-stream\"}\n\n")
            flush()
            delay(60_000) // hold the stream open; the test tears the server down
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleToken() {
        val form = call.receiveParameters()
        tokenResources += form["resource"]

        when (form["grant_type"]) {
            "authorization_code" -> {
                val expectedChallenge = pendingCodes.remove(form["code"])
                if (expectedChallenge == null || Pkce.challengeS256(form["code_verifier"]!!) != expectedChallenge) {
                    call.respondText(
                        """{"error":"invalid_grant","error_description":"bad code or PKCE verifier"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return
                }
                issueTokens()
            }

            "refresh_token" -> {
                if (behaviour.get() == Behaviour.REFRESH_INVALID_GRANT ||
                    form["refresh_token"] !in validRefreshTokens
                ) {
                    call.respondText(
                        """{"error":"invalid_grant"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return
                }
                validRefreshTokens.remove(form["refresh_token"]) // rotation: old one is dead
                issueTokens()
            }

            else -> call.respondText(
                """{"error":"unsupported_grant_type"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.issueTokens() {
        val n = counter.incrementAndGet()
        val access = "access-$n"
        val refresh = "refresh-$n"
        validTokens += access
        validRefreshTokens += refresh
        call.respondText(
            """{"access_token":"$access","token_type":"Bearer","expires_in":3600,"refresh_token":"$refresh"}""",
            ContentType.Application.Json,
        )
    }

    private fun io.ktor.server.routing.RoutingContext.bearer(): String? =
        call.request.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
}
