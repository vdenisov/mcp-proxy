package org.plukh.mcpproxy.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.method
import io.ktor.server.cio.CIO as ServerCIO

/**
 * Covers the long-lived GET/SSE stream: the path that carries every server-initiated message
 * (notifications, and in stage 2 sampling and elicitation requests).
 *
 * These need a real socket - Ktor's client SSE plugin cannot run on `MockEngine` - and none of this
 * code had ever executed before these tests: the one server used for manual verification returns
 * 405 for GET, which disables the stream entirely.
 */
class HttpEndpointStreamTest {

    /** What the stub does when the client opens the GET stream. */
    private enum class StreamBehaviour {
        /** Emit one notification, then hold the stream open. */
        NOTIFY,

        /**
         * Emit a response-shaped frame and close, then serve a notification on the reconnect.
         *
         * The close is what makes this discriminating: `break` on a response frame only takes
         * effect once the stream ends, so a stub that sent both frames on one connection would
         * pass whether or not the bug is present.
         */
        RESPONSE_THEN_NOTIFY,

        /** Emit a notification, then drop the connection - the client should reconnect. */
        NOTIFY_THEN_DROP,

        /** Fail every attempt, so reconnection eventually gives up. */
        REFUSE,

        /** No server-initiated stream at all - what the live Context7 server does. */
        REJECT_405,
    }

    private val behaviour = AtomicReference(StreamBehaviour.NOTIFY)
    private val getAttempts = AtomicInteger()
    private var server: EmbeddedServer<*, *>? = null

    private val notification =
        """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""

    private suspend fun startStub(): Int {
        val engine = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/mcp") {
                    val frame = decodeFrame(call.receiveText())
                    when (frame.method) {
                        "initialize" -> call.respondText(
                            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"stub","version":"1"}}}""",
                            ContentType.Application.Json,
                        )
                        // 202 with no body is the cue for the client to open the GET stream.
                        else -> call.respond(HttpStatusCode.Accepted)
                    }
                }
                get("/mcp") {
                    val attempt = getAttempts.incrementAndGet()
                    when (behaviour.get()) {
                        StreamBehaviour.REFUSE -> call.respond(HttpStatusCode.InternalServerError)

                        StreamBehaviour.REJECT_405 -> call.respond(HttpStatusCode.MethodNotAllowed)

                        StreamBehaviour.NOTIFY -> call.respondTextWriter(ContentType.Text.EventStream) {
                            write("data: $notification\n\n")
                            flush()
                            delay(3_000.milliseconds) // hold it open, as a real server would
                        }

                        StreamBehaviour.RESPONSE_THEN_NOTIFY ->
                            call.respondTextWriter(ContentType.Text.EventStream) {
                                if (attempt == 1) {
                                    // A response frame, then end the stream. The client must treat
                                    // the standalone stream as still needed and come back.
                                    write("""data: {"jsonrpc":"2.0","id":99,"result":{"stray":true}}""" + "\n\n")
                                    flush()
                                } else {
                                    write("data: $notification\n\n")
                                    flush()
                                    delay(3_000.milliseconds)
                                }
                            }

                        StreamBehaviour.NOTIFY_THEN_DROP ->
                            call.respondTextWriter(ContentType.Text.EventStream) {
                                // Only the first connection drops; the reconnect gets a live stream.
                                if (attempt == 1) {
                                    write("data: $notification\n\n")
                                    flush()
                                } else {
                                    write("""data: {"jsonrpc":"2.0","method":"notifications/reconnected"}""" + "\n\n")
                                    flush()
                                    delay(3_000.milliseconds)
                                }
                            }
                    }
                }
            }
        }
        engine.start(wait = false)
        server = engine
        return engine.engine.resolvedConnectors().first().port
    }

    @AfterTest
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private fun client() = HttpClient(CIO) { install(SSE) }

    /** Drives the handshake far enough to open the GET stream. */
    private suspend fun openStream(endpoint: HttpEndpoint) {
        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""))
        endpoint.send(decodeFrame("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `server-initiated notifications arrive on the GET stream`() = runBlocking {
        behaviour.set(StreamBehaviour.NOTIFY)
        val port = startStub()
        val client = client()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)

        try {
            val endpoint = HttpEndpoint(client, "http://127.0.0.1:$port/mcp")
            endpoint.onFrame { frames.send(it) }
            openStream(endpoint)

            // The initialize response, then the server-pushed notification.
            withTimeout(15_000.milliseconds) {
                assertEquals(null, frames.receive().method) // initialize result
                assertEquals("notifications/tools/list_changed", frames.receive().method)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a response frame on the GET stream does not kill it`() = runBlocking {
        // Regression: the collect loop broke on any response frame, which is correct for a
        // POST-resumption stream but silently ended all server-initiated traffic on the
        // standalone one - with nothing to restart it and no error surfaced.
        behaviour.set(StreamBehaviour.RESPONSE_THEN_NOTIFY)
        val port = startStub()
        val client = client()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)

        val endpoint = HttpEndpoint(
            client,
            "http://127.0.0.1:$port/mcp",
            reconnectionOptions = ReconnectionOptions(initialReconnectionDelay = 50.milliseconds, maxRetries = 5),
        )
        try {
            endpoint.onFrame { frames.send(it) }
            openStream(endpoint)

            withTimeout(15_000.milliseconds) {
                frames.receive() // initialize result
                assertEquals(null, frames.receive().method) // the stray response frame
                // Only arrives if the client reconnected instead of retiring the stream.
                assertEquals("notifications/tools/list_changed", frames.receive().method)
            }
        } finally {
            withTimeoutOrNull(5_000.milliseconds) { endpoint.close() }
            client.close()
        }
    }

    @Test
    fun `a dropped stream reconnects instead of failing the session`() = runBlocking {
        behaviour.set(StreamBehaviour.NOTIFY_THEN_DROP)
        val port = startStub()
        val client = client()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)

        try {
            val endpoint = HttpEndpoint(
                client,
                "http://127.0.0.1:$port/mcp",
                reconnectionOptions = ReconnectionOptions(
                    initialReconnectionDelay = 50.milliseconds,
                    maxRetries = 5,
                ),
            )
            var fatal: Throwable? = null
            endpoint.onFrame { frames.send(it) }
            endpoint.onError { fatal = it }
            openStream(endpoint)

            withTimeout(15_000.milliseconds) {
                frames.receive() // initialize result
                assertEquals("notifications/tools/list_changed", frames.receive().method)
                // Proof the client came back after the drop.
                assertEquals("notifications/reconnected", frames.receive().method)
            }
            assertEquals(null, fatal, "a dropped stream is recoverable and must not be fatal")
            assertTrue(getAttempts.get() >= 2, "expected a reconnect attempt")
        } finally {
            client.close()
        }
    }

    @Test
    fun `exhausting reconnection attempts is reported as fatal`() = runBlocking {
        // The one upstream condition that legitimately ends the session: the server-initiated
        // stream is gone for good, so the proxy can no longer deliver everything the client
        // expects. Never executed before this test.
        behaviour.set(StreamBehaviour.REFUSE)
        val port = startStub()
        val client = client()

        try {
            val endpoint = HttpEndpoint(
                client,
                "http://127.0.0.1:$port/mcp",
                reconnectionOptions = ReconnectionOptions(
                    initialReconnectionDelay = 20.milliseconds,
                    maxRetries = 2,
                ),
            )
            val fatal = CompletableDeferred<Throwable>()
            endpoint.onError { fatal.complete(it) }
            openStream(endpoint)

            val cause = withTimeoutOrNull(15_000.milliseconds) { fatal.await() }
            assertTrue(cause is StreamableHttpError, "expected a fatal transport error, got $cause")
            assertTrue(
                cause.message!!.contains("Maximum reconnection attempts"),
                "unexpected fatal cause: ${cause.message}",
            )
            assertTrue(getAttempts.get() >= 2, "should have retried before giving up")
        } finally {
            client.close()
        }
    }

    @Test
    fun `a 405 on GET disables the stream quietly rather than failing`() = runBlocking {
        // What the live Context7 server does: it simply has no server-initiated stream. That is a
        // supported configuration, not an error, and must not be reported as fatal.
        behaviour.set(StreamBehaviour.REJECT_405)
        val port = startStub()
        val client = client()
        var fatal: Throwable? = null

        val endpoint = HttpEndpoint(client, "http://127.0.0.1:$port/mcp")
        endpoint.onError { fatal = it }
        try {
            withTimeout(15_000.milliseconds) {
                openStream(endpoint)
                // Give the GET attempt time to happen and be classified.
                delay(1_000.milliseconds)
            }
        } finally {
            // Closing the endpoint (not just the client) cancels its internal SSE scope; leaving it
            // running holds the connection open and blocks HttpClient.close().
            withTimeoutOrNull(5_000.milliseconds) { endpoint.close() }
            client.close()
        }

        assertEquals(null, fatal, "405 on GET means no server-initiated stream, not a failure")
        assertTrue(getAttempts.get() >= 1, "the client should have tried to open the stream")
    }
}
