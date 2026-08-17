package org.plukh.mcpproxy.jsonrpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON-RPC envelope helpers. Deliberately the only place the proxy looks inside a frame, and it
 * never goes deeper than the envelope - payloads stay opaque so they relay byte-for-byte.
 */
object JsonRpc {

    /** Lenient enough to relay whatever a server sends, strict enough to stay valid JSON. */
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
    }

    // Standard JSON-RPC 2.0 error codes.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603

    fun errorFrame(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}

/** The JSON-RPC method, or null for responses. */
val JsonObject.method: String?
    get() = this["method"]?.jsonPrimitive?.contentOrNull

/** The request id. Absent on notifications; may legitimately be a string, number or null. */
val JsonObject.id: JsonElement?
    get() = this["id"]

/** A request expects a response: it carries both a method and an id. */
val JsonObject.isRequest: Boolean
    get() = method != null && containsKey("id")

/** A notification is fire-and-forget: a method with no id. */
val JsonObject.isNotification: Boolean
    get() = method != null && !containsKey("id")

/** A response carries a result or an error, and no method. */
val JsonObject.isResponse: Boolean
    get() = method == null && (containsKey("result") || containsKey("error"))

/** `params` as an object, or null when absent or not an object. */
val JsonObject.params: JsonObject?
    get() = (this["params"] as? JsonObject)

/**
 * The protocol version from an `initialize` result, if this frame is one. Used to populate the
 * `MCP-Protocol-Version` header on subsequent upstream requests.
 */
val JsonObject.resultProtocolVersion: String?
    get() = (this["result"] as? JsonObject)
        ?.get("protocolVersion")
        ?.jsonPrimitive
        ?.contentOrNull

/** Renders the frame as a single line of compact JSON - the stdio framing. */
fun JsonObject.encode(): String = JsonRpc.json.encodeToString(JsonObject.serializer(), this)

/**
 * Parses one stdio line into a frame.
 *
 * @throws IllegalArgumentException if the line is not a JSON object
 */
fun decodeFrame(line: String): JsonObject = JsonRpc.json.parseToJsonElement(line).jsonObject
