package org.plukh.mcpproxy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.id
import org.plukh.mcpproxy.jsonrpc.method

/**
 * The routing rule the whole server-side transport rests on: a response answers the POST that is
 * waiting for it, everything else goes to the stream. None of this needs a socket, so none of it
 * uses one.
 */
class HttpServerEndpointTest {

    private fun endpoint(store: EventStore = InMemoryEventStore(1_000_000)) =
        HttpServerEndpoint(sessionId = "s1", eventStore = store, responseTimeout = 30.seconds)

    private fun request(id: Int, method: String = "tools/list") = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
    }

    private fun response(id: Int, value: String = "ok") = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("result") { put("value", value) }
    }

    private fun notification(method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }

    /** Collects what the stream would write, without an HTTP response to write into. */
    private class StreamSink {
        val frames = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
        suspend fun write(id: String, encoded: String) {
            frames.send(id to decodeFrame(encoded))
        }
    }

    // --- correlation ---

    @Test
    fun `a response answers the POST waiting for it and never reaches the stream`() = runTest {
        val endpoint = endpoint()
        val sink = StreamSink()
        endpoint.onFrame { frame -> launch { endpoint.send(response(frame.id!!.toString().toInt())) } }
        val streaming = launch { endpoint.streamTo(null, sink::write) }

        val answer = endpoint.postRequest(request(1))

        assertEquals(response(1), answer)
        assertTrue(sink.frames.tryReceive().isFailure, "the answered response leaked onto the stream")
        streaming.cancel()
    }

    @Test
    fun `notifications, server-initiated requests and unmatched responses go to the stream`() = runTest {
        val endpoint = endpoint()
        val sink = StreamSink()
        val streaming = launch { endpoint.streamTo(null, sink::write) }

        endpoint.send(notification("notifications/message"))
        endpoint.send(request(7, "sampling/createMessage"))
        endpoint.send(response(99)) // nobody is waiting for this one

        assertEquals("notifications/message", sink.frames.receive().second.method)
        assertEquals("sampling/createMessage", sink.frames.receive().second.method)
        assertEquals(response(99), sink.frames.receive().second)
        streaming.cancel()
    }

    /**
     * An upstream must echo the id it was given, unaltered. Treating `"1"` and `1` as the same
     * request would let a sloppy upstream's answer satisfy the wrong caller.
     */
    @Test
    fun `a string id and a numeric id are different requests`() = runTest {
        val endpoint = endpoint()
        val sink = StreamSink()
        val streaming = launch { endpoint.streamTo(null, sink::write) }
        endpoint.onFrame { }

        val waiting = async { endpoint.postRequest(request(1)) }
        testScheduler.runCurrent() // let the POST register its waiter before answering it
        // The same digit, as a string. It must not complete the numeric request.
        endpoint.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "1")
                putJsonObject("result") { put("value", "wrong") }
            },
        )
        assertTrue(!waiting.isCompleted, "a string id completed a numeric request's POST")

        endpoint.send(response(1, "right"))
        assertEquals(response(1, "right"), waiting.await())
        streaming.cancel()
    }

    @Test
    fun `two requests in flight get their own answers, whatever order they arrive in`() = runTest {
        val endpoint = endpoint()
        endpoint.onFrame { }

        val first = async { endpoint.postRequest(request(1)) }
        val second = async { endpoint.postRequest(request(2)) }
        testScheduler.runCurrent() // both waiters registered before either is answered

        endpoint.send(response(2, "second"))
        endpoint.send(response(1, "first"))

        assertEquals(response(1, "first"), first.await())
        assertEquals(response(2, "second"), second.await())
    }

    @Test
    fun `an upstream that never answers yields an error frame carrying the request id`() = runTest {
        val endpoint = endpoint()
        endpoint.onFrame { }

        val answer = endpoint.postRequest(request(42))

        assertEquals(42, answer["id"].toString().toInt())
        assertTrue(answer.containsKey("error"), "expected a JSON-RPC error frame, got $answer")
    }

    // --- the stream ---

    @Test
    fun `frames emitted while nobody is listening are delivered when a stream attaches`() = runTest {
        val endpoint = endpoint()
        endpoint.send(notification("notifications/early"))

        val sink = StreamSink()
        val streaming = launch { endpoint.streamTo(null, sink::write) }

        assertEquals("notifications/early", sink.frames.receive().second.method)
        streaming.cancel()
    }

    @Test
    fun `a second stream displaces the first`() = runTest {
        val endpoint = endpoint()
        val first = StreamSink()
        val firstJob = launch { endpoint.streamTo(null, first::write) }
        endpoint.send(notification("notifications/one"))
        assertEquals("notifications/one", first.frames.receive().second.method)

        val second = StreamSink()
        val secondJob = launch { endpoint.streamTo(null, second::write) }
        endpoint.send(notification("notifications/two"))

        assertEquals("notifications/two", second.frames.receive().second.method)
        withTimeout(5.seconds) { firstJob.join() }
        assertTrue(firstJob.isCancelled, "the displaced stream should have been cancelled")
        secondJob.cancel()
    }

    @Test
    fun `a reconnect with Last-Event-ID replays only what came after it`() = runTest {
        val endpoint = endpoint()
        val first = StreamSink()
        val firstJob = launch { endpoint.streamTo(null, first::write) }
        endpoint.send(notification("notifications/one"))
        endpoint.send(notification("notifications/two"))
        val firstId = first.frames.receive().first
        first.frames.receive()
        firstJob.cancel()

        endpoint.send(notification("notifications/three"))
        val resumed = StreamSink()
        val resumedJob = launch { endpoint.streamTo(firstId, resumed::write) }

        // Everything after event one: two and three, and neither of them twice.
        assertEquals("notifications/two", resumed.frames.receive().second.method)
        assertEquals("notifications/three", resumed.frames.receive().second.method)
        assertTrue(resumed.frames.tryReceive().isFailure, "replayed more than what followed the id")
        resumedJob.cancel()
    }

    @Test
    fun `an unknown Last-Event-ID yields a fresh stream rather than a silent hole`() = runTest {
        val endpoint = endpoint()
        endpoint.send(notification("notifications/old"))

        val sink = StreamSink()
        val streaming = launch { endpoint.streamTo("e-forged", sink::write) }
        testScheduler.runCurrent() // the stream picks its cursor before the next frame exists
        endpoint.send(notification("notifications/new"))

        // The retained history is not replayed against an id we cannot vouch for; the live frame is.
        assertEquals("notifications/new", sink.frames.receive().second.method)
        streaming.cancel()
    }

    // --- closing ---

    @Test
    fun `close fails a POST that is still waiting instead of leaving it to hang`() = runTest {
        val endpoint = endpoint()
        endpoint.onFrame { }
        // runCatching inside the coroutine, not assertFailsWith around await: a failing `async`
        // propagates to the test scope the moment it fails, cancelling the body before it can look.
        var thrown: Throwable? = null
        launch { runCatching { endpoint.postRequest(request(1)) }.onFailure { thrown = it } }
        testScheduler.runCurrent() // the POST is genuinely waiting when the session goes away

        endpoint.close()
        testScheduler.runCurrent()

        assertTrue(thrown is SessionClosedException, "expected the waiting POST to fail, got $thrown")
    }

    @Test
    fun `close fires the close handler exactly once, however many times it is called`() = runTest {
        val endpoint = endpoint()
        var closes = 0
        endpoint.onClose { closes++ }

        endpoint.close()
        endpoint.close()

        assertEquals(1, closes)
        assertTrue(endpoint.isClosed())
    }
}
