package org.plukh.mcpproxy

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.downstream.StdioEndpoint
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.relay.Endpoint
import org.plukh.mcpproxy.relay.IdentityRewriter
import org.plukh.mcpproxy.relay.Relay

/**
 * Runs the real proxy over piped stdio against a given upstream, and lets a test drive it.
 *
 * The session has to be driven rather than scripted because responses are not synchronous with the
 * requests that caused them: an upstream may answer whenever it likes, and closing stdin is a client
 * disconnect, which correctly ends the session whether or not answers have arrived. So a test sends,
 * waits for what it expects, and only then hangs up.
 */
internal class ProxySession(
    private val stdinSource: PipedOutputStream,
    private val collector: FrameCollector,
    private val exit: CompletableDeferred<Int>,
    private val timeoutMs: Long,
) {
    fun send(frame: String) {
        stdinSource.write((frame + "\n").toByteArray())
        stdinSource.flush()
    }

    suspend fun nextFrame(): JsonObject = withTimeout(timeoutMs) { collector.frames.receive() }

    /** Client hangs up. Ends the session unless the upstream has already ended it. */
    fun disconnect() = stdinSource.close()

    suspend fun awaitExit(): Int = withTimeout(timeoutMs) { exit.await() }
}

/** Splits the proxy's stdout into frames as they are written, so a test can wait for one. */
internal class FrameCollector : OutputStream() {
    val frames = Channel<JsonObject>(Channel.UNLIMITED)
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            val line = buffer.toString(Charsets.UTF_8)
            buffer.reset()
            if (line.isNotBlank()) frames.trySend(decodeFrame(line))
        } else {
            buffer.write(b)
        }
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }
}

/**
 * @param upstream the endpoint under test; the relay closes it on shutdown
 *
 * @return the proxy's exit code
 */
internal fun runProxy(
    upstream: Endpoint,
    identity: IdentityConfig = IdentityConfig(name = "mcp-proxy", version = "9.9.9"),
    timeoutMs: Long = 30_000,
    block: suspend ProxySession.() -> Unit,
): Int = runBlocking {
    val collector = FrameCollector()
    val stdinSource = PipedOutputStream()
    val stdin = PipedInputStream(stdinSource, 1 shl 16)
    // Detached for the reason StdioEndpoint documents: its read blocks uninterruptibly, so a reader
    // parented here would keep runBlocking waiting whenever the session ends other than by stdin EOF.
    val stdioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    try {
        val relay = Relay(
            downstream = StdioEndpoint(scope = stdioScope, input = stdin, output = collector),
            upstream = upstream,
            identity = IdentityRewriter(identity),
        )
        val exit = CompletableDeferred<Int>()
        stdioScope.launch { exit.complete(relay.run()) }

        val session = ProxySession(stdinSource, collector, exit, timeoutMs)
        session.block()
        session.awaitExit()
    } finally {
        runCatching { stdinSource.close() }
        stdioScope.cancel()
    }
}

internal fun initializeFrame(clientName: String = "claude-code", clientVersion: String = "2.1.233") =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"capabilities":{"sampling":{}},"clientInfo":{"name":"$clientName","version":"$clientVersion"}}}"""
