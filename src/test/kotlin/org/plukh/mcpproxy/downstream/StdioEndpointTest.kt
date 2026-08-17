package org.plukh.mcpproxy.downstream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.method

/**
 * The read loop runs on an [UnconfinedTestDispatcher] rather than [kotlinx.coroutines.Dispatchers.IO]
 * so it executes eagerly on the test thread. Mixing runTest's virtual clock with a real dispatcher
 * makes these tests race the scheduler; against a [ByteArrayInputStream] there is nothing to gain
 * from real threading anyway.
 */
class StdioEndpointTest {

    private fun input(vararg lines: String) =
        ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray())

    private fun TestScope.endpoint(
        input: ByteArrayInputStream,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) = StdioEndpoint(
        scope = this,
        input = input,
        output = output,
        readDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    // --- round trip ---

    @Test
    fun `delivers inbound frames in order`() = runTest {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = endpoint(
            input(
                """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            ),
        )
        endpoint.onFrame { frames.send(it) }
        endpoint.start()

        assertEquals("tools/list", frames.receive().method)
        assertEquals("notifications/initialized", frames.receive().method)
        endpoint.close()
    }

    @Test
    fun `writes frames as one compact line each`() = runTest {
        val out = ByteArrayOutputStream()
        val endpoint = endpoint(input(), out)

        endpoint.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1) })
        endpoint.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", 2) })

        val lines = out.toString(Charsets.UTF_8).trim().lines()
        assertEquals(2, lines.size)
        assertEquals(1, decodeFrame(lines[0])["id"]!!.jsonPrimitive.asInt())
        assertEquals(2, decodeFrame(lines[1])["id"]!!.jsonPrimitive.asInt())
        endpoint.close()
    }

    @Test
    fun `a large frame survives the round trip`() = runTest {
        val big = "x".repeat(200_000)
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            putJsonObject("result") { put("blob", big) }
        }
        val out = ByteArrayOutputStream()
        val writer = endpoint(input(), out)
        writer.send(frame)
        writer.close()

        val received = CompletableDeferred<JsonObject>()
        val reader = endpoint(ByteArrayInputStream(out.toByteArray()))
        reader.onFrame { received.complete(it) }
        reader.start()

        assertEquals(frame, received.await())
        reader.close()
    }

    // --- failure handling ---

    @Test
    fun `a malformed line yields a parse error and does not stop the session`() = runTest {
        val out = ByteArrayOutputStream()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = endpoint(
            input("not json at all", """{"jsonrpc":"2.0","id":1,"method":"ping"}"""),
            out,
        )
        endpoint.onFrame { frames.send(it) }
        endpoint.start()

        // The good frame after the bad one still arrives - the session survived.
        assertEquals("ping", frames.receive().method)

        val error = decodeFrame(out.toString(Charsets.UTF_8).trim().lines().first())
        assertEquals(-32700, (error["error"] as JsonObject)["code"]!!.jsonPrimitive.asInt())
        endpoint.close()
    }

    @Test
    fun `EOF triggers the close handler`() = runTest {
        val closed = CompletableDeferred<Unit>()
        val endpoint = endpoint(input())
        endpoint.onClose { closed.complete(Unit) }
        endpoint.start()

        closed.await()
        assertTrue(closed.isCompleted)
    }

    @Test
    fun `blank lines are ignored`() = runTest {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val endpoint = endpoint(input("", "   ", """{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
        endpoint.onFrame { frames.send(it) }
        endpoint.start()

        assertEquals("ping", frames.receive().method)
        endpoint.close()
    }

    /**
     * Regression: the stdin read blocks uninterruptibly, so if the reader is launched in
     * `runBlocking`'s own scope, any session ending for a reason other than stdin EOF (an
     * unrecoverable upstream failure) leaves `runBlocking` waiting on it forever and the process
     * never reaches `exitProcess`. Verified as a real hang before the fix.
     */
    @Test
    fun `runBlocking returns while the stdin reader is still blocked`() {
        val neverEnds = object : java.io.InputStream() {
            override fun read(): Int {
                Thread.sleep(60_000)
                return -1
            }
        }

        val elapsed = kotlin.system.measureTimeMillis {
            runBlocking {
                val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val endpoint = StdioEndpoint(
                    scope = readerScope,
                    input = neverEnds,
                    output = ByteArrayOutputStream(),
                )
                endpoint.start()
                delay(100)
                readerScope.cancel() // as Cli.serve does in its finally block
            }
        }

        assertTrue(elapsed < 10_000, "runBlocking waited on the blocked stdin reader (${elapsed}ms)")
    }

    @Test
    fun `close is idempotent`() = runTest {
        val endpoint = endpoint(input())
        endpoint.start()
        endpoint.close()
        endpoint.close()
    }
}

private fun JsonPrimitive.asInt(): Int = content.toInt()
