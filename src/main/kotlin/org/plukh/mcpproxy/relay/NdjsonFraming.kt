package org.plukh.mcpproxy.relay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode

/**
 * Newline-delimited JSON framing over a pair of streams, shared by both stdio sides of the proxy.
 *
 * Composition rather than a base class: the two endpoints get their streams at different times -
 * the client-facing one at construction, the upstream one only after the child process is spawned -
 * so a template method would need an `openStreams()` hook that exists solely to accommodate that.
 *
 * Policy deliberately stays with the caller. What a malformed line means differs by side (the client
 * gets a parse error back; a server must not, since an unsolicited error frame in its stdin is not
 * something it asked for), and so does end of stream (the client going away is a clean disconnect,
 * a child process going away is a failure).
 *
 * Writes are serialised through a mutex, since the relay can emit a response and a notification
 * concurrently.
 */
class NdjsonFraming(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {

    private val writeLock = Mutex()

    /**
     * Reads frames until end of stream, then returns. Blank lines are skipped; a line that does not
     * parse goes to [onMalformed] and reading continues.
     *
     * An oversized line or a stream failure propagates to the caller, whose job it is to decide
     * whether that ends the session.
     */
    suspend fun readLoop(
        onFrame: suspend (JsonObject) -> Unit,
        onMalformed: suspend (line: String, cause: Exception) -> Unit,
    ) {
        while (true) {
            val line = readLine(input, maxFrameBytes) ?: return
            if (line.isBlank()) continue

            val frame = try {
                decodeFrame(line)
            } catch (e: Exception) {
                onMalformed(line, e)
                continue
            }
            onFrame(frame)
        }
    }

    suspend fun send(frame: JsonObject) {
        val bytes = (frame.encode() + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            output.write(bytes)
            output.flush()
        }
    }

    /** Best-effort final flush, for close paths where a failure is no longer actionable. */
    suspend fun flushQuietly() {
        runCatching { writeLock.withLock { output.flush() } }
    }

    companion object {
        /** Matches the SDK's Streamable HTTP limit, so neither side is the surprising one. */
        const val DEFAULT_MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

        /**
         * Reads one `\n`-terminated line as UTF-8, bounded by [limit] bytes.
         *
         * Hand-rolled rather than using [java.io.BufferedReader] so an oversized frame fails fast
         * instead of being buffered in full first.
         *
         * @return the line without its terminator, or null at end of stream
         */
        internal fun readLine(input: InputStream, limit: Int): String? {
            val buffer = ByteArrayOutputStream(1024)
            while (true) {
                val b = input.read()
                when {
                    b == -1 -> return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8)
                    b == '\n'.code -> return buffer.toString(Charsets.UTF_8).removeSuffix("\r")
                    else -> {
                        if (buffer.size() >= limit) {
                            throw IllegalStateException("Frame exceeds $limit bytes; refusing to buffer more")
                        }
                        buffer.write(b)
                    }
                }
            }
        }
    }
}
