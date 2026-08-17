package org.plukh.mcpproxy.downstream

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.Stdio
import org.plukh.mcpproxy.jsonrpc.JsonRpc
import org.plukh.mcpproxy.relay.AbstractEndpoint
import org.plukh.mcpproxy.relay.NdjsonFraming

private val log = KotlinLogging.logger {}

/**
 * The client-facing side: newline-delimited JSON-RPC over stdin/stdout.
 *
 * Reads run on [Dispatchers.IO] because the underlying read blocks and **cannot be interrupted** -
 * `readerJob.cancel()` marks the coroutine cancelled but the thread stays parked in `read()` until
 * a byte or EOF arrives. Consequently [scope] must not be one the process waits on before exiting
 * (never `runBlocking`'s own scope), or shutdown for any reason other than stdin EOF will hang.
 * [Dispatchers.IO] threads are daemons, so the JVM can exit with the read still outstanding.
 *
 * Framing and write serialisation live in [NdjsonFraming], shared with the stdio upstream; what
 * stays here is the policy that differs between the two sides.
 */
class StdioEndpoint(
    private val scope: CoroutineScope,
    input: InputStream = Stdio.protocolIn,
    output: OutputStream = Stdio.protocolOut,
    maxFrameBytes: Int = NdjsonFraming.DEFAULT_MAX_FRAME_BYTES,
    /** Injectable so tests can run the read loop deterministically instead of racing a real thread. */
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractEndpoint() {

    private val framing = NdjsonFraming(input, output, maxFrameBytes)
    private val closed = AtomicBoolean(false)
    private var readerJob: Job? = null

    override suspend fun start() {
        readerJob = scope.launch(readDispatcher) { readLoop() }
    }

    private suspend fun readLoop() {
        try {
            framing.readLoop(
                onFrame = { frameHandler?.invoke(it) },
                onMalformed = { line, e ->
                    // A malformed line is the client's problem, not a reason to tear down the
                    // session - tell it so and keep reading.
                    log.warn(e) { "Discarding malformed frame (${line.length} chars)" }
                    runCatching { send(JsonRpc.errorFrame(null, JsonRpc.PARSE_ERROR, "Invalid JSON")) }
                },
            )
            log.debug { "stdin closed" }
        } catch (e: CancellationException) {
            // Teardown, not a failure. Rethrow so the coroutine machinery sees it, and skip the
            // close callback below - invoking a suspending handler here would throw immediately.
            throw e
        } catch (e: Throwable) {
            if (closed.get()) return // teardown races are expected, not errors
            log.error(e) { "stdin read failed" }
            errorHandler?.invoke(e)
        }
        closeHandler?.invoke()
    }

    override suspend fun send(frame: JsonObject) = framing.send(frame)

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        framing.flushQuietly()
        readerJob?.cancel()
    }
}
