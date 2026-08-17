package org.plukh.mcpproxy.upstream

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.relay.AbstractEndpoint
import org.plukh.mcpproxy.relay.NdjsonFraming

private val log = KotlinLogging.logger {}

/** Child diagnostics get their own logger name, so the log shows at a glance who said what. */
private val childLog = KotlinLogging.logger("upstream.child")

/**
 * The server-facing side for a locally spawned MCP server: newline-delimited JSON-RPC over a child
 * process's stdin and stdout, with its stderr folded into the proxy log.
 *
 * Unlike the client-facing [org.plukh.mcpproxy.downstream.StdioEndpoint], a malformed line is dropped
 * rather than answered - a parse error frame is something a client asks for, not something to push at
 * a server that never requested it - and end of stream is fatal rather than clean, because a child
 * whose stdout closed has died and there is nothing to reconnect to.
 *
 * The identity limitation worth remembering: this hides the *client* from the server, but the server
 * binary sets its own User-Agent on whatever API it calls, and that traffic never passes through here.
 */
class StdioUpstreamEndpoint(
    private val command: List<String>,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val maxFrameBytes: Int = NdjsonFraming.DEFAULT_MAX_FRAME_BYTES,
    /** Kept below `Relay`'s own shutdown budget, so the grace wait cannot be what blows it. */
    private val graceTimeout: Duration = 2.seconds,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractEndpoint() {

    private val scope = CoroutineScope(SupervisorJob() + readDispatcher)
    private val closed = AtomicBoolean(false)
    private val closeFired = AtomicBoolean(false)

    /** Test seam: lets a test assert the child is really gone after [close]. */
    internal var process: Process? = null
        private set

    private var framing: NdjsonFraming? = null
    private var shutdownHook: Thread? = null

    /**
     * Spawns the server.
     *
     * A failure here is the "server is down" case: it propagates to the relay, which answers the
     * client's `initialize` with a JSON-RPC error instead of the proxy disappearing. The child is
     * torn down before rethrowing so a later attempt starts from a clean slate.
     */
    override suspend fun start() {
        check(process == null) { "Endpoint already started" }
        log.info { "Spawning upstream process, command=${command.joinToString(" ")}, envKeys=${extraEnv.keys}" }

        val child = try {
            ProcessBuilder(command)
                .also { it.environment().putAll(extraEnv) }
                .start()
        } catch (e: IOException) {
            throw IOException("could not spawn upstream process (${command.firstOrNull()}): ${e.message}", e)
        }
        process = child

        try {
            // The backstop for the paths that never reach close(): a kill -9 of the proxy, or a
            // shutdown that ran out of time before the grace wait finished.
            shutdownHook = Thread { child.destroyForcibly() }.also { Runtime.getRuntime().addShutdownHook(it) }

            framing = NdjsonFraming(child.inputStream, child.outputStream, maxFrameBytes)
            scope.launch { pumpStderr(child) }
            scope.launch { readLoop(child) }

            log.info { "Upstream process started, pid=${child.pid()}" }
        } catch (e: Throwable) {
            destroyQuietly(child)
            process = null
            framing = null
            throw e
        }
    }

    private suspend fun readLoop(child: Process) {
        try {
            framing!!.readLoop(
                onFrame = { frameHandler?.invoke(it) },
                onMalformed = { line, e ->
                    // Warn and drop. Answering would put an unsolicited error frame into a server's
                    // stdin, which is not a thing the protocol lets us do.
                    log.warn(e) { "Discarding malformed frame from upstream process (${line.length} chars)" }
                },
            )
            // Falling out of the loop means the child closed its stdout, i.e. it exited.
            reportDeath(child, cause = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportDeath(child, cause = e)
        }
    }

    /**
     * Child stdout EOF is the single source of truth for "the server is gone". `onExit` is consulted
     * only for the exit code to log; making it a second trigger would race this one and report the
     * same death twice.
     */
    private suspend fun reportDeath(child: Process, cause: Throwable?) {
        if (closed.get()) return // we killed it, on purpose

        val exitCode = withTimeoutOrNull(EXIT_CODE_TIMEOUT) { child.onExit().await().exitValue() }
        val description = "upstream process exited (exitCode=$exitCode, command=${command.firstOrNull()})"
        log.error(cause) { description }
        errorHandler?.invoke(cause ?: IOException(description))
        fireClose()
    }

    private suspend fun pumpStderr(child: Process) {
        try {
            child.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { childLog.info { it } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!closed.get()) log.debug(e) { "Upstream process stderr pump ended" }
        }
    }

    override suspend fun send(frame: JsonObject) {
        val framing = checkNotNull(framing) { "Endpoint not started" }
        // A broken pipe here is one request's failure, answered as a JSON-RPC error. The session
        // ends separately, through the stdout EOF that follows.
        framing.send(frame)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val child = process ?: return

        // Closing stdin is how a stdio server is asked to leave; only then escalate.
        runCatching { child.outputStream.close() }
        child.destroy()
        if (withTimeoutOrNull(graceTimeout) { child.onExit().await() } == null) {
            log.warn { "Upstream process did not exit within $graceTimeout, killing it" }
            child.destroyForcibly()
        } else {
            log.info { "Upstream process exited, exitCode=${child.exitValue()}" }
        }

        removeShutdownHook()
        scope.cancel()
        fireClose()
    }

    private suspend fun fireClose() {
        if (closeFired.compareAndSet(false, true)) closeHandler?.invoke()
    }

    private fun destroyQuietly(child: Process) {
        removeShutdownHook()
        runCatching { child.destroyForcibly() }
        scope.cancel()
    }

    private fun removeShutdownHook() {
        shutdownHook?.let {
            // Throws once the VM is already shutting down, which is exactly when the hook is running
            // and there is nothing left to remove.
            runCatching { Runtime.getRuntime().removeShutdownHook(it) }
            shutdownHook = null
        }
    }

    private companion object {
        val EXIT_CODE_TIMEOUT = 1.seconds
    }
}
