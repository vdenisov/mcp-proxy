package org.plukh.mcpproxy.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.jsonrpc.JsonRpc
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.jsonrpc.id
import org.plukh.mcpproxy.jsonrpc.isResponse
import org.plukh.mcpproxy.relay.AbstractEndpoint

private val log = KotlinLogging.logger {}

/** Raised into a POST still waiting for its response when the session ends underneath it. */
class SessionClosedException(message: String) : Exception(message)

/**
 * The client-facing half of one MCP session, spoken over Streamable HTTP.
 *
 * A drop-in [org.plukh.mcpproxy.relay.Endpoint] like `StdioEndpoint`, so `Relay` neither knows nor
 * cares which transport carries the client. One instance per session: the session id, the pending
 * requests and the event stream are all per-session state.
 *
 * **Where a frame goes.** The relay hands every upstream frame to [send], which routes on one rule:
 * a response whose id matches a POST still waiting answers *that POST* inline; everything else -
 * notifications, server-initiated requests, and responses nobody is waiting for - goes to the
 * standalone SSE stream. That is what makes reverse forwarding (`sampling/createMessage` and
 * friends) work over HTTP without the client polling for it.
 *
 * Ids are compared as `JsonElement`s, so a string `"1"` and a number `1` are different requests -
 * correct, because an upstream must echo the id it was given, unaltered.
 *
 * Written against the MCP spec with the stream lifecycle and replay contract adapted from the
 * Kotlin SDK's server transport; see `VENDORED.md` for what was taken and what was left.
 */
class HttpServerEndpoint(
    val sessionId: String,
    private val eventStore: EventStore,
    private val responseTimeout: Duration,
) : AbstractEndpoint() {

    private val pending = ConcurrentHashMap<JsonElement, CompletableDeferred<JsonObject>>()
    private val closed = AtomicBoolean(false)

    /** The stream's id in the event store. One standalone stream per session, so one id. */
    private val streamId = "stream-$sessionId"

    /** Signals an attached writer that new events are available. Conflated: the writer re-reads the store. */
    private val newEvents = Channel<Unit>(Channel.CONFLATED)

    /** The coroutine currently serving GET, so a second GET can displace the first. */
    private val activeStream = AtomicReference<Job?>(null)

    private val cursorLock = Mutex()

    /** How far a client has been served, so a reconnect without `Last-Event-ID` still gets the gap. */
    private var delivered: String? = null

    /**
     * Signals that the relay has wired its handlers. `Relay.run` registers them and *then* calls
     * `start()`, per the [org.plukh.mcpproxy.relay.Endpoint] contract, so this is the moment the
     * endpoint becomes usable.
     */
    private val started = CompletableDeferred<Unit>()

    override suspend fun start() {
        // Nothing to begin - frames arrive when the route handlers call postRequest/postOneWay -
        // but the POST that created this session must not run before the relay is listening.
        started.complete(Unit)
    }

    /**
     * Waits until the relay is listening.
     *
     * Without this the very first POST of a session races the coroutine running the relay: a frame
     * handed over before `Relay.run` registered its handler is dropped on the floor, and the POST
     * waits out its whole timeout for an answer nobody was ever asked for. It reproduced as an
     * intermittent client-side timeout on the *second* session, which is exactly how long a race
     * like this stays plausible-looking.
     */
    suspend fun awaitStarted() {
        started.await()
    }

    /**
     * Routes one upstream frame. See the class doc for the rule; the interesting half is that a
     * response with no waiter still reaches the client rather than being dropped - an upstream that
     * answers late, or on its own stream, must not lose the answer.
     */
    override suspend fun send(frame: JsonObject) {
        if (frame.isResponse) {
            val waiter = frame.id?.let { pending.remove(it) }
            if (waiter != null) {
                waiter.complete(frame)
                return
            }
        }
        emit(frame)
    }

    /** Appends to the event store and wakes the attached writer, if any. */
    private suspend fun emit(frame: JsonObject) {
        if (closed.get()) return
        eventStore.append(streamId, frame.encode())
        newEvents.trySend(Unit)
    }

    /**
     * Delivers a client request upstream and waits for its answer.
     *
     * The wait is a backstop, not policy: an unreachable upstream, an HTTP error and an expired
     * authorization all come back through the relay as JSON-RPC error frames carrying this very id,
     * which completes the waiter. Only a bug leaves it hanging.
     */
    suspend fun postRequest(frame: JsonObject): JsonObject {
        val id = frame.id ?: error("postRequest called with a frame that has no id")
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        try {
            frameHandler?.invoke(frame)
            return withTimeoutOrNull(responseTimeout) { waiter.await() }
                ?: JsonRpc.errorFrame(id, JsonRpc.INTERNAL_ERROR, "upstream did not answer within $responseTimeout")
        } finally {
            pending.remove(id)
        }
    }

    /** Delivers a notification, or a client's response to a server-initiated request. Nothing comes back. */
    suspend fun postOneWay(frame: JsonObject) {
        frameHandler?.invoke(frame)
    }

    /**
     * Serves the standalone SSE stream until the caller's coroutine is cancelled.
     *
     * Displaces any previous stream: with one connection per session, a client that reconnects
     * without the old one having closed cleanly must not end up with two. The replacement is
     * installed before the old one is cancelled, and the loser removes only *its own* registration -
     * the identity guard that keeps a slow disconnect from evicting its successor.
     *
     * @param lastEventId the client's `Last-Event-ID`; null resumes from wherever this session was
     *   last served, which is what makes frames emitted while nobody was listening survive
     */
    suspend fun streamTo(lastEventId: String?, write: suspend (id: String, encoded: String) -> Unit) {
        coroutineScope {
            val self = currentCoroutineContext().job
            activeStream.getAndSet(self)?.cancel(CancellationException("displaced by a new GET stream"))

            var cursor = lastEventId ?: cursorLock.withLock { delivered }
            if (lastEventId != null && eventStore.replayAfter(streamId, lastEventId) == null) {
                // Evicted, forged, or another stream's id. We cannot say what followed it, so the
                // client gets a *fresh* stream - everything from now on, and none of the history it
                // asked to be positioned within. Resuming from `delivered` instead would replay a
                // backlog the client never asked for and may already have seen.
                log.info { "Unknown Last-Event-ID $lastEventId, starting a fresh stream" }
                cursor = eventStore.replayAfter(streamId, null)?.lastOrNull()?.id
            }

            try {
                while (true) {
                    val batch = eventStore.replayAfter(streamId, cursor) ?: emptyList()
                    for (event in batch) {
                        write(event.id, event.encoded)
                        cursor = event.id
                        cursorLock.withLock { delivered = event.id }
                    }
                    newEvents.receive()
                }
            } finally {
                activeStream.compareAndSet(self, null)
            }
        }
    }

    /**
     * Ends the session. Waiting POSTs are failed rather than left to time out, so a client that
     * asked something during a shutdown learns immediately - as a 404, which tells it to
     * re-initialize rather than to retry into a session that no longer exists.
     */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return

        // Release anyone waiting to start: a session that dies before the relay got going must fail
        // its POST rather than hold it until the timeout.
        started.complete(Unit)
        pending.values.forEach { it.completeExceptionally(SessionClosedException("session $sessionId closed")) }
        pending.clear()
        activeStream.getAndSet(null)?.cancel(CancellationException("session closed"))
        newEvents.close()
        eventStore.forget(streamId)
        closeHandler?.invoke()
    }

    internal fun isClosed(): Boolean = closed.get()
}
