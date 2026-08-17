package org.plukh.mcpproxy.relay

import kotlinx.serialization.json.JsonObject

/**
 * One side of the proxy: a bidirectional stream of JSON-RPC frames.
 *
 * Frames are opaque [JsonObject]s - an endpoint parses the JSON but never interprets it. This is
 * the seam that keeps the relay honest (tests wire two fakes together with real JSON semantics)
 * and makes stage 2's stdio upstream a drop-in replacement for the HTTP one.
 *
 * Handlers must be registered before [start]; an endpoint may deliver frames as soon as it starts.
 */
interface Endpoint {

    /** Called for each inbound frame, in arrival order. */
    fun onFrame(handler: suspend (JsonObject) -> Unit)

    /** Called on an unrecoverable transport failure. [onClose] follows. */
    fun onError(handler: suspend (Throwable) -> Unit)

    /** Called once when the endpoint stops, whether cleanly or after an error. */
    fun onClose(handler: suspend () -> Unit)

    /** Begins reading. Returns once started; delivery continues in the background. */
    suspend fun start()

    /** Sends one frame. */
    suspend fun send(frame: JsonObject)

    /** Stops the endpoint and releases its resources. Idempotent. */
    suspend fun close()
}

/**
 * Convenience base handling the callback bookkeeping every endpoint needs.
 */
abstract class AbstractEndpoint : Endpoint {

    protected var frameHandler: (suspend (JsonObject) -> Unit)? = null
        private set

    protected var errorHandler: (suspend (Throwable) -> Unit)? = null
        private set

    protected var closeHandler: (suspend () -> Unit)? = null
        private set

    override fun onFrame(handler: suspend (JsonObject) -> Unit) {
        frameHandler = handler
    }

    override fun onError(handler: suspend (Throwable) -> Unit) {
        errorHandler = handler
    }

    override fun onClose(handler: suspend () -> Unit) {
        closeHandler = handler
    }
}
