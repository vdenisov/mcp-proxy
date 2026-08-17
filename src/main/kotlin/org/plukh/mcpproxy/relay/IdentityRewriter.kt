package org.plukh.mcpproxy.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.config.DROPPED_CAPABILITIES
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.jsonrpc.params

/**
 * Replaces the client's identity in the `initialize` handshake.
 *
 * This is the only frame the proxy ever modifies. In particular the initialize *response* is left
 * completely alone, so the client sees the real server's `serverInfo`, capabilities, instructions
 * and negotiated protocol version - which is what makes the proxy invisible from its side.
 */
class IdentityRewriter(private val identity: IdentityConfig) {

    fun rewriteInitialize(frame: JsonObject): JsonObject {
        val original = frame.params ?: JsonObject(emptyMap())

        val params = buildJsonObject {
            original["protocolVersion"]?.let { put("protocolVersion", it) }
            put("capabilities", sanitizeCapabilities(original["capabilities"] as? JsonObject))
            putJsonObject("clientInfo") {
                put("name", identity.name)
                put("version", identity.version)
                identity.title?.let { put("title", it) }
            }

            if (!identity.strictInitializeParams) {
                // Vendor extras and `_meta` survive only when the user opts out of strict mode:
                // they are exactly the kind of thing that identifies a specific client.
                original.forEach { (key, value) -> if (key !in SPEC_PARAM_KEYS) put(key, value) }
            }
        }

        return JsonObject(frame + ("params" to params))
    }

    /**
     * Capabilities pass through largely intact. Because the relay is transparent, a capability the
     * client advertises really does work end to end, so hiding one would break the session; the
     * exceptions are the open-ended extension buckets, whose contents are client-specific by
     * definition and therefore identifying.
     */
    private fun sanitizeCapabilities(capabilities: JsonObject?): JsonObject = when {
        capabilities == null -> JsonObject(emptyMap())
        !identity.forwardCapabilities -> JsonObject(emptyMap())
        else -> JsonObject(capabilities.filterKeys { it !in DROPPED_CAPABILITIES })
    }

    private companion object {
        val SPEC_PARAM_KEYS = setOf("protocolVersion", "capabilities", "clientInfo")
    }
}
