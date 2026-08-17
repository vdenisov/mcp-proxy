package org.plukh.mcpproxy.relay

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.jsonrpc.JsonRpc
import org.plukh.mcpproxy.jsonrpc.id
import org.plukh.mcpproxy.jsonrpc.isRequest
import org.plukh.mcpproxy.jsonrpc.method
import org.plukh.mcpproxy.jsonrpc.resultProtocolVersion
import org.plukh.mcpproxy.upstream.HttpEndpoint

private val log = KotlinLogging.logger {}

/**
 * Pumps frames between the client and the upstream server.
 *
 * Everything except the `initialize` request is relayed byte-for-byte in both directions - a
 * notification is simply a frame without an id, and a server-initiated request (sampling,
 * elicitation, roots) is just a frame travelling the other way, so neither needs special handling.
 *
 * Request ids pass through 1:1. The proxy never originates a request of its own, so there is
 * nothing to map; if that ever changes, namespace the id and never relay its response.
 */
class Relay(
    private val downstream: Endpoint,
    private val upstream: Endpoint,
    private val identity: IdentityRewriter,
) {

    private val connectLock = Mutex()
    private var upstreamStarted = false
    private val exit = CompletableDeferred<Int>()

    /** Runs until one side closes. Returns the process exit code. */
    suspend fun run(): Int {
        upstream.onFrame(::fromServer)
        // Only fatal conditions reach here. A per-request failure (4xx, 5xx, a malformed response)
        // is thrown from send() instead and answered with a JSON-RPC error, and a dropped SSE
        // stream is retried by the endpoint's own reconnect loop - neither ends the session.
        upstream.onError { cause ->
            log.error(cause) { "Upstream failed unrecoverably" }
            exit.complete(ExitCodes.UPSTREAM_FAILED)
        }

        downstream.onFrame(::fromClient)
        downstream.onError { cause ->
            log.error(cause) { "Client connection failed" }
            exit.complete(ExitCodes.OK)
        }
        downstream.onClose {
            log.info { "Client disconnected" }
            exit.complete(ExitCodes.OK)
        }

        downstream.start()

        val code = exit.await()
        shutdown()
        return code
    }

    private suspend fun fromClient(frame: JsonObject) {
        val outbound = if (frame.method == INITIALIZE) {
            log.info { "Rewriting client identity in initialize" }
            identity.rewriteInitialize(frame)
        } else {
            frame
        }

        if (!ensureUpstream(frame)) return

        try {
            upstream.send(outbound)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val id = frame.id
            if (id == null) {
                // Nothing to answer - a failed notification is logged and dropped.
                log.warn(e) { "Dropping notification ${frame.method}: upstream send failed" }
                return
            }
            log.warn(e) { "Upstream send failed for ${frame.method} (id=$id)" }
            downstream.send(
                JsonRpc.errorFrame(id, JsonRpc.INTERNAL_ERROR, "upstream request failed: ${e.message}"),
            )
        }
    }

    private suspend fun fromServer(frame: JsonObject) {
        // Only an initialize result carries protocolVersion; capture it so subsequent upstream
        // requests can send the MCP-Protocol-Version header the spec requires. The cast is meant to
        // miss for a stdio upstream - there is no header on a pipe, and nothing to record it in.
        frame.resultProtocolVersion?.let { version ->
            (upstream as? HttpEndpoint)?.let {
                if (it.protocolVersion != version) {
                    log.info { "Upstream negotiated protocol version $version" }
                    it.protocolVersion = version
                }
            }
        }
        downstream.send(frame)
    }

    /**
     * Starts the upstream on first use.
     *
     * Connecting lazily rather than at startup means a server that is down surfaces as a JSON-RPC
     * error answering `initialize` - which every client renders - instead of the proxy vanishing
     * with a non-zero exit before the client has said anything.
     *
     * @return true if the upstream is usable
     */
    private suspend fun ensureUpstream(frame: JsonObject): Boolean = connectLock.withLock {
        if (upstreamStarted) return true
        try {
            upstream.start()
            upstreamStarted = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error(e) { "Upstream connect failed" }
            frame.takeIf { it.isRequest }?.id?.let { id ->
                downstream.send(
                    JsonRpc.errorFrame(id, JsonRpc.INTERNAL_ERROR, "upstream unavailable: ${e.message}"),
                )
            }
            false
        }
    }

    private suspend fun shutdown() {
        // Best effort, and bounded: a wedged upstream must not stop the process from exiting.
        withTimeoutOrNull(SHUTDOWN_TIMEOUT) { runCatching { upstream.close() } }
        runCatching { downstream.close() }
    }

    private companion object {
        const val INITIALIZE = "initialize"
        val SHUTDOWN_TIMEOUT = 3.seconds
    }
}
