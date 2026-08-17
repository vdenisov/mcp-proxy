package org.plukh.mcpproxy.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.method

class NdjsonFramingTest {

    private fun input(vararg lines: String) =
        ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))

    private fun framing(input: ByteArrayInputStream, output: ByteArrayOutputStream = ByteArrayOutputStream()) =
        NdjsonFraming(input, output)

    // --- line reader ---

    @Test
    fun `reads lines and reports EOF as null`() {
        val stream = input("one", "two")
        assertEquals("one", NdjsonFraming.readLine(stream, 1024))
        assertEquals("two", NdjsonFraming.readLine(stream, 1024))
        assertNull(NdjsonFraming.readLine(stream, 1024))
    }

    @Test
    fun `strips CR so CRLF input works`() {
        val stream = ByteArrayInputStream("one\r\n".toByteArray())
        assertEquals("one", NdjsonFraming.readLine(stream, 1024))
    }

    @Test
    fun `returns a trailing unterminated line`() {
        val stream = ByteArrayInputStream("no newline".toByteArray())
        assertEquals("no newline", NdjsonFraming.readLine(stream, 1024))
    }

    @Test
    fun `decodes multi-byte UTF-8 correctly`() {
        val stream = ByteArrayInputStream("héllo — wörld\n".toByteArray(Charsets.UTF_8))
        assertEquals("héllo — wörld", NdjsonFraming.readLine(stream, 1024))
    }

    @Test
    fun `refuses to buffer an oversized frame`() {
        val stream = ByteArrayInputStream("x".repeat(5000).toByteArray())
        assertFailsWith<IllegalStateException> { NdjsonFraming.readLine(stream, 100) }
    }

    // --- read loop ---

    @Test
    fun `a malformed line goes to onMalformed and reading continues`() = runBlocking {
        val frames = mutableListOf<JsonObject>()
        val malformed = mutableListOf<String>()

        framing(input("not json", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")).readLoop(
            onFrame = { frames += it },
            onMalformed = { line, _ -> malformed += line },
        )

        assertEquals(listOf("not json"), malformed)
        assertEquals(listOf("ping"), frames.map { it.method })
    }

    @Test
    fun `blank lines are skipped`() = runBlocking {
        val frames = mutableListOf<JsonObject>()

        framing(input("", "   ", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")).readLoop(
            onFrame = { frames += it },
            onMalformed = { _, e -> throw AssertionError("blank line reached onMalformed", e) },
        )

        assertEquals(listOf("ping"), frames.map { it.method })
    }

    @Test
    fun `the read loop returns at end of stream`() = runBlocking {
        // No exception, no callback - EOF is simply a normal return, leaving the meaning to the caller.
        framing(input()).readLoop(
            onFrame = { throw AssertionError("no frames expected") },
            onMalformed = { _, _ -> throw AssertionError("no frames expected") },
        )
    }

    @Test
    fun `an oversized line propagates to the caller`() = runBlocking {
        val huge = "x".repeat(5000)
        val framing = NdjsonFraming(ByteArrayInputStream(huge.toByteArray()), ByteArrayOutputStream(), maxFrameBytes = 100)

        assertFailsWith<IllegalStateException> {
            framing.readLoop(onFrame = {}, onMalformed = { _, _ -> })
        }
        Unit
    }

    // --- writes ---

    @Test
    fun `concurrent sends produce intact lines`() = runBlocking {
        val out = ByteArrayOutputStream()
        val framing = NdjsonFraming(ByteArrayInputStream(ByteArray(0)), out)
        val blob = "y".repeat(50_000)

        withContext(Dispatchers.IO) {
            (1..16).map { i ->
                async { framing.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", i); put("blob", blob) }) }
            }.awaitAll()
        }

        val lines = out.toString(Charsets.UTF_8).trim().lines()
        assertEquals(16, lines.size)
        // Each line must be a complete frame - interleaved writes would corrupt the JSON.
        assertEquals((1..16).toSet(), lines.map { decodeFrame(it)["id"]!!.jsonPrimitive.content.toInt() }.toSet())
    }
}
