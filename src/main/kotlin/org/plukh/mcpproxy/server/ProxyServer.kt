package org.plukh.mcpproxy.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.URI
import java.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.config.ServerConfig
import org.plukh.mcpproxy.config.bindsLoopbackOnly
import org.plukh.mcpproxy.config.resolvedPublicUrl
import org.plukh.mcpproxy.jsonrpc.JsonRpc
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.jsonrpc.isRequest
import org.plukh.mcpproxy.jsonrpc.method
import org.plukh.mcpproxy.relay.Relay

private val log = KotlinLogging.logger {}

private const val SESSION_HEADER = "Mcp-Session-Id"
private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"

/**
 * The single-process server: one listener, one route set per configured upstream, one relay per
 * client session.
 *
 * Every route is a literal path (`/<name>/mcp`, `/<name>/callback`, `/<name>/login`, `/`), so a
 * callback can never be swallowed by a relay route - there is no wildcard for it to fall into.
 *
 * **This listener has no authentication.** Anything that can reach the port drives every configured
 * upstream with the operator's stored credentials. That is a deliberate, documented choice for a
 * single-user deployment; the startup log says so out loud whenever the bind is not loopback.
 */
class ProxyServer internal constructor(
    private val config: ServerConfig,
    private val runtimes: Map<String, UpstreamRuntime>,
    private val callbacks: CallbackRegistry,
    private val eventStore: EventStore,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val sessions = SessionRegistry(config.server.maxSessions, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publicUrl = config.server.resolvedPublicUrl()
    private val stopRequested = CompletableDeferred<Unit>()

    private var engine: EmbeddedServer<*, *>? = null

    /** Where the listener actually bound. Differs from the configured port only when it was 0 (tests). */
    var boundPort: Int = 0
        private set

    fun start() {
        val server = embeddedServer(ServerCIO, port = config.server.port, host = config.server.bindHost) {
            routing {
                get("/") { call.respondText(statusPage(runtimes, sessions, publicUrl), ContentType.Text.Html) }
                runtimes.values.forEach { runtime ->
                    route("/${runtime.name}") {
                        // Before the relay route in the file as well as in specificity - the ordering
                        // that matters is that these paths are literal and distinct.
                        get("/login") { call.handleLogin(runtime) }
                        get("/callback") { call.handleCallback(runtime) }
                        post("/mcp") { call.handlePost(runtime) }
                        get("/mcp") { call.handleStream(runtime) }
                        delete("/mcp") { call.handleDelete(runtime) }
                    }
                }
            }
        }
        server.start(wait = false)
        engine = server
        boundPort = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }

        log.info { "Listening on http://${config.server.bindHost}:$boundPort, serving ${runtimes.keys.joinToString(", ")}" }
        if (!config.server.bindsLoopbackOnly()) {
            log.warn {
                "Bound to ${config.server.bindHost}, which is not loopback: this listener has no " +
                    "authentication, so anyone who can reach port $boundPort can use every configured " +
                    "upstream with your stored credentials. Publish it to 127.0.0.1 only."
            }
        }
        scope.launch { sweepIdleSessions() }
    }

    /** Blocks until [requestStop]; the process's shutdown hook is what normally ends it. */
    suspend fun awaitShutdown(): Int {
        stopRequested.await()
        return ExitCodes.OK
    }

    fun requestStop() {
        stopRequested.complete(Unit)
    }

    override fun close() {
        scope.cancel()
        runBlockingClose()
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        runtimes.values.forEach { runCatching { it.close() } }
    }

    private fun runBlockingClose() = kotlinx.coroutines.runBlocking {
        // Sessions first: each close cascades into Relay.shutdown, which DELETEs the upstream
        // session rather than leaving it to expire server-side.
        sessions.all().forEach { session ->
            runCatching { withTimeoutOrNull(5.seconds) { session.downstream.close() } }
        }
    }

    private suspend fun sweepIdleSessions() {
        while (scope.isActive) {
            delay(60.seconds)
            sessions.idleSince(config.server.sessionIdleTimeoutSeconds).forEach { session ->
                log.info { "Closing idle session ${session.id} on '${session.upstreamName}'" }
                sessions.remove(session.id)
                runCatching { session.downstream.close() }
            }
        }
    }

    // --- MCP routes ---

    /**
     * Runs [block] with this session's identity in the logging context.
     *
     * It has to wrap the *route handler*, not the coroutine running `Relay.run`: the relay's own
     * coroutine spends its life awaiting, while the work it logs about - rewriting the handshake,
     * talking upstream, tearing down - happens inline on whichever request coroutine drove it.
     * Tagging the relay's coroutine alone tagged almost nothing.
     */
    private suspend fun <T> Session.logged(block: suspend () -> T): T =
        withContext(MDCContext(mapOf("ctx" to "$upstreamName/${id.take(8)}"))) { block() }

    private suspend fun ApplicationCall.rejectForeignOrigin(): Boolean {
        val origin = request.header(HttpHeaders.Origin) ?: return false
        val allowed = runCatching {
            val uri = URI(origin)
            uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "::1" ||
                origin.trimEnd('/') == publicUrl
        }.getOrDefault(false)
        if (!allowed) {
            // DNS-rebinding guard: a page on the internet must not be able to drive a loopback
            // listener from the victim's own browser.
            log.warn { "Refusing request with foreign Origin: $origin" }
            respondText("Forbidden: unexpected Origin", status = HttpStatusCode.Forbidden)
            return true
        }
        return false
    }

    private suspend fun ApplicationCall.handlePost(runtime: UpstreamRuntime) {
        if (rejectForeignOrigin()) return

        val frame = try {
            decodeFrame(receiveText())
        } catch (e: Exception) {
            respondText(
                JsonRpc.errorFrame(null, JsonRpc.PARSE_ERROR, "invalid JSON: ${e.message}").encode(),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val headerId = request.header(SESSION_HEADER)
        val session = if (headerId == null) {
            if (frame.method != "initialize" || !frame.isRequest) {
                respondText(
                    "Bad Request: no $SESSION_HEADER, and this is not an initialize request",
                    status = HttpStatusCode.BadRequest,
                )
                return
            }
            try {
                openSession(runtime)
            } catch (e: TooManySessionsException) {
                respondText("Service Unavailable: ${e.message}", status = HttpStatusCode.ServiceUnavailable)
                return
            }
        } else {
            sessions.get(headerId, runtime.name) ?: run {
                // The spec's signal to start over. Also what an id from another upstream gets.
                respondText("Not Found: unknown session", status = HttpStatusCode.NotFound)
                return
            }
        }

        response.header(SESSION_HEADER, session.id)
        if (frame.isRequest) {
            val answer = try {
                session.logged { session.downstream.postRequest(frame) }
            } catch (e: SessionClosedException) {
                respondText("Not Found: session closed", status = HttpStatusCode.NotFound)
                return
            }
            respondText(answer.encode(), ContentType.Application.Json, HttpStatusCode.OK)
        } else {
            session.logged { session.downstream.postOneWay(frame) }
            respondText("", ContentType.Text.Plain, HttpStatusCode.Accepted)
        }
    }

    private suspend fun ApplicationCall.handleStream(runtime: UpstreamRuntime) {
        if (rejectForeignOrigin()) return
        val session = sessions.get(request.header(SESSION_HEADER), runtime.name) ?: run {
            respondText("Not Found: unknown session", status = HttpStatusCode.NotFound)
            return
        }
        val lastEventId = request.header(LAST_EVENT_ID_HEADER)

        response.header(SESSION_HEADER, session.id)
        response.header(HttpHeaders.CacheControl, "no-cache")
        respondTextWriter(ContentType.Text.EventStream) {
            // A comment, flushed immediately: nothing else is written until the server has something
            // to say, and a client waiting for response headers would time out before that. SSE
            // ignores comment lines, so this costs the client nothing.
            write(": connected\n\n")
            flush()
            session.downstream.streamTo(lastEventId) { id, encoded ->
                // id: then data:, blank line to terminate - the framing a client's parser needs to
                // dispatch the event and to remember where it got to.
                write("id: $id\n")
                write("data: $encoded\n\n")
                flush()
            }
        }
    }

    private suspend fun ApplicationCall.handleDelete(runtime: UpstreamRuntime) {
        if (rejectForeignOrigin()) return
        val session = sessions.get(request.header(SESSION_HEADER), runtime.name) ?: run {
            respondText("Not Found: unknown session", status = HttpStatusCode.NotFound)
            return
        }
        // Deregistered here rather than left to the relay's completion: DELETE promises the id is
        // dead, and the relay reaps asynchronously, so a request arriving right after would
        // otherwise still find the session. Removing twice is harmless.
        sessions.remove(session.id)
        session.logged { session.downstream.close() }
        respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
    }

    private suspend fun openSession(runtime: UpstreamRuntime): Session {
        val id = sessions.newId()
        val downstream = HttpServerEndpoint(id, eventStore, runtime.responseTimeout)
        val session = sessions.register(Session(id, runtime.name, downstream))

        val upstream = runtime.newSessionUpstream()
        val relay = Relay(downstream = downstream, upstream = upstream, identity = runtime.rewriter)
        // Everything the relay logs is tagged with which upstream and which session it came from.
        // With N sessions interleaving in one process, an untagged line is nearly useless - and the
        // relay is where the interesting lines are. (Route handlers run on Ktor's own contexts and
        // are not covered; they name the session in the message instead.)
        session.relayJob = scope.launch(MDCContext(mapOf("ctx" to "${runtime.name}/${id.take(8)}"))) {
            val code = try {
                relay.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error(e) { "Session $id on '${runtime.name}' failed" }
                ExitCodes.UPSTREAM_FAILED
            }
            // A session's exit code is a session's business: it is logged and the session is
            // reaped, never turned into a process exit.
            if (code != ExitCodes.OK) log.warn { "Session $id on '${runtime.name}' ended with code $code" }
            sessions.remove(id)
        }
        // The relay runs on its own coroutine; this POST must not hand it a frame before it is
        // listening. See HttpServerEndpoint.awaitStarted.
        downstream.awaitStarted()
        log.info { "Session $id opened on '${runtime.name}'" }
        return session
    }

    // --- OAuth routes ---

    private suspend fun ApplicationCall.handleLogin(runtime: UpstreamRuntime) {
        val session = runtime.shared?.oauthSession
        val coordinator = runtime.loginCoordinator
        if (session == null || coordinator == null) {
            respondText(page("Not an OAuth upstream", "'${runtime.name}' needs no login."), ContentType.Text.Html)
            return
        }

        // Already in a browser round? Send this one to the same URL rather than starting a second
        // flow - OAuthSession single-flights anyway, but a redirect is friendlier than a wait.
        coordinator.pendingUrl()?.let {
            respondRedirect(it)
            return
        }

        val flow = coordinator.beginFlow()
        scope.launch {
            runCatching { session.login() }
                .onSuccess { coordinator.finished(flow.generation, error = null) }
                .onFailure {
                    coordinator.finished(flow.generation, it.message ?: it::class.simpleName ?: "login failed")
                }
        }

        val url = withTimeoutOrNull(20.seconds) { flow.url.await() }
        if (url == null) {
            respondText(
                page(
                    "Could not start authorization",
                    coordinator.lastError ?: "The authorization server did not respond in time.",
                ),
                ContentType.Text.Html,
                HttpStatusCode.BadGateway,
            )
            return
        }
        respondRedirect(url)
    }

    private suspend fun ApplicationCall.handleCallback(runtime: UpstreamRuntime) {
        val params = request.queryParameters
        val delivered = callbacks.complete(
            upstreamName = runtime.name,
            state = params["state"],
            code = params["code"],
            error = params["error"],
            iss = params["iss"],
        )
        if (!delivered) {
            // Unknown state: stale, forged, or for another upstream. The genuine flow is untouched
            // and still waiting - which is the point.
            respondText(
                page("Invalid state", "This callback does not match a login in progress."),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val message = params["error"]?.let { "Authorization failed: $it" } ?: "You can close this window."
        respondText(page("Authorization complete", message), ContentType.Text.Html)
    }
}
