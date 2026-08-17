package org.plukh.mcpproxy.upstream

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Runs against the real [StubScenario] child process, on real dispatchers: a subprocess and
 * `runTest`'s virtual clock cannot be reconciled, since the child's timeline is genuinely wall-clock.
 * Every wait is bounded by [withTimeout], so a hang fails the test instead of the build.
 */
class StdioUpstreamEndpointTest {

    private var endpoint: StdioUpstreamEndpoint? = null

    @AfterTest
    fun tearDown() = runBlocking {
        endpoint?.let { runCatching { withTimeout(TIMEOUT) { it.close() } } }
        Unit
    }

    private fun endpoint(scenario: String, command: List<String> = stubCommand(scenario)) =
        StdioUpstreamEndpoint(
            command = command,
            // Handing the test classpath over as CLASSPATH rather than -cp keeps the command line
            // short: Gradle's test classpath comfortably exceeds the Windows 32K argument limit.
            extraEnv = mapOf("CLASSPATH" to System.getProperty("java.class.path")),
        ).also { endpoint = it }

    @Test
    fun `frames round-trip through the child process`() = runBlocking {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = endpoint(StubScenario.ECHO)
        endpoint.onFrame { frames.send(it) }
        endpoint.start()

        endpoint.send(request(id = 1, method = "initialize"))

        val response = withTimeout(TIMEOUT) { frames.receive() }
        assertEquals(1, response["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("initialize", response.echoedMethod())
    }

    /**
     * Regression: a child that dies is unrecoverable - there is no reconnect for a pipe - so it must
     * reach the relay as a fatal error, which is what ends the session with a status instead of
     * leaving the proxy waiting on a stream nobody will ever write to.
     */
    @Test
    fun `child death is reported as a fatal error`() = runBlocking {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val failure = CompletableDeferred<Throwable>()
        val closed = CompletableDeferred<Unit>()

        val endpoint = endpoint(StubScenario.DIE_AFTER_FIRST_RESPONSE)
        endpoint.onFrame { frames.send(it) }
        endpoint.onError { failure.complete(it) }
        endpoint.onClose { closed.complete(Unit) }
        endpoint.start()

        endpoint.send(request(id = 1, method = "initialize"))
        withTimeout(TIMEOUT) { frames.receive() } // the answer, then the child exits

        withTimeout(TIMEOUT) { failure.await() }
        withTimeout(TIMEOUT) { closed.await() }
    }

    /**
     * Regression: a line the child could not have meant as a frame must be dropped, and dropped
     * silently as far as the child is concerned. Replying with a parse error - the policy the
     * client-facing side uses - would push an unsolicited error frame into a server's stdin; the stub
     * exits [StubScenario.UNEXPECTED_ERROR_FRAME] if that ever happens.
     */
    @Test
    fun `garbage on the child's stdout is dropped and the session survives`() = runBlocking {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = endpoint(StubScenario.GARBAGE_THEN_ECHO)
        endpoint.onFrame { frames.send(it) }
        endpoint.onError { throw AssertionError("a malformed line must not be fatal", it) }
        endpoint.start()

        // Two requests in sequence, and the second one is the load-bearing half. The child emits its
        // garbage at startup, so whether a wrongly-sent error frame reaches its stdin before or after
        // the first request is a race - but either ordering leaves the second request unanswered,
        // because the child exits the moment it reads one.
        repeat(2) { i ->
            endpoint.send(request(id = 7 + i, method = "tools/list"))
            val response = withTimeout(TIMEOUT) { frames.receive() }
            assertEquals(7 + i, response["id"]!!.jsonPrimitive.content.toInt())
        }
        assertTrue(endpoint.process!!.isAlive, "the child was killed by something we sent it")
    }

    @Test
    fun `close lets a well-behaved child exit on stdin EOF`() = runBlocking {
        val endpoint = endpoint(StubScenario.ECHO)
        endpoint.start()
        val child = endpoint.process!!
        assertTrue(child.isAlive)

        withTimeout(TIMEOUT) { endpoint.close() }

        assertFalse(child.isAlive, "the child outlived the endpoint that spawned it")
    }

    /**
     * Regression: closing stdin is a request, not a guarantee. A server that ignores it - or is wedged -
     * has to be killed, or the proxy leaves an orphan behind every time it exits.
     */
    @Test
    fun `close kills a child that ignores stdin EOF`() = runBlocking {
        val endpoint = endpoint(StubScenario.IGNORES_SHUTDOWN)
        endpoint.start()
        val child = endpoint.process!!
        assertTrue(child.isAlive)

        withTimeout(TIMEOUT) { endpoint.close() }

        assertFalse(child.isAlive, "a child that ignores stdin EOF was left running")
    }

    @Test
    fun `the child's stderr is folded into the proxy log`() = runBlocking {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val childLogger = LoggerFactory.getLogger("upstream.child") as Logger
        childLogger.addAppender(appender)

        try {
            val frames = Channel<JsonObject>(Channel.UNLIMITED)
            val endpoint = endpoint(StubScenario.STDERR_CHATTER)
            endpoint.onFrame { frames.send(it) }
            endpoint.start()

            // Round-trip first, so the child has demonstrably got going before we look at the log.
            endpoint.send(request(id = 1, method = "initialize"))
            withTimeout(TIMEOUT) { frames.receive() }

            withTimeout(TIMEOUT) {
                while (appender.list.none { it.formattedMessage.contains(StubScenario.STDERR_MARKER) }) {
                    kotlinx.coroutines.delay(50)
                }
            }
        } finally {
            childLogger.detachAppender(appender)
        }
    }

    @Test
    fun `a command that cannot be spawned fails the start`() = runBlocking {
        val endpoint = endpoint(StubScenario.ECHO, command = listOf("definitely-not-a-real-command-xyz"))

        val e = assertFailsWith<java.io.IOException> { endpoint.start() }
        assertTrue(
            e.message!!.contains("could not spawn"),
            "spawn failures must say what failed, got: ${e.message}",
        )
    }

    // --- helpers ---

    private fun request(id: Int, method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        putJsonObject("params") {
            putJsonObject("clientInfo") {
                put("name", "mcp-proxy")
                put("version", "1.0.0")
            }
        }
    }

    /** The stub echoes each request back under `result.echo` - our only view into what it received. */
    private fun JsonObject.echoedMethod(): String? =
        this["result"]?.jsonObject?.get("echo")?.jsonObject?.get("method")?.jsonPrimitive?.content

    private companion object {
        val TIMEOUT = 30.seconds
    }
}

internal fun stubCommand(scenario: String): List<String> = listOf(
    File(System.getProperty("java.home"), "bin${File.separator}java").path,
    "org.plukh.mcpproxy.upstream.StubStdioServerKt",
    scenario,
)
