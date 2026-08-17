package org.plukh.mcpproxy.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject

/**
 * In-memory [Endpoint] for tests.
 *
 * Note we deliberately do not use the MCP SDK's `ChannelTransport`: it passes typed protocol
 * objects without a serialisation round-trip, which would hide exactly the fidelity bugs this
 * proxy exists to avoid. Frames here stay [JsonObject]s throughout, as they do in production.
 */
open class FakeEndpoint : AbstractEndpoint() {

    /** Frames the endpoint was asked to send, i.e. what the far side would receive. */
    val sent = Channel<JsonObject>(Channel.UNLIMITED)

    var started = false
        private set

    var closedCount = 0
        private set

    override suspend fun start() {
        started = true
    }

    override suspend fun send(frame: JsonObject) {
        sent.send(frame)
    }

    override suspend fun close() {
        closedCount++
        sent.close()
    }

    /** Simulates a frame arriving from the far side. */
    suspend fun receive(frame: JsonObject) {
        frameHandler?.invoke(frame)
    }

    /** Simulates a transport failure. */
    suspend fun fail(cause: Throwable) {
        errorHandler?.invoke(cause)
    }

    /** Simulates the far side closing the connection. */
    suspend fun closeRemote() {
        closeHandler?.invoke()
    }

    /** Next frame this endpoint was asked to send. */
    suspend fun nextSent(): JsonObject = sent.receive()
}
