package org.plukh.mcpproxy.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.jsonrpc.id
import org.plukh.mcpproxy.jsonrpc.isRequest
import org.plukh.mcpproxy.jsonrpc.method

/**
 * A plain (unauthenticated) Streamable HTTP MCP server, for testing the proxy's *server* side
 * without dragging OAuth in.
 *
 * Answers requests inline as `application/json`, 202s notifications, and holds a GET stream that the
 * test can push frames into on demand - which is how a server-initiated request (the reverse
 * direction) gets into the relay.
 */
class StubHttpMcpUpstream(
    /** Frames to answer with, keyed by method; anything else gets an empty result. */
    private val responses: MutableMap<String, JsonObject> = mutableMapOf(),
) {

    private var server: EmbeddedServer<*, *>? = null

    val requests = CopyOnWriteArrayList<JsonObject>()
    val deletes = CopyOnWriteArrayList<String>()

    /** Frames the test wants pushed down the standalone stream. */
    private val toPush = Channel<JsonObject>(Channel.UNLIMITED)

    var port: Int = 0
        private set

    val url: String get() = "http://127.0.0.1:$port/mcp"

    fun respondTo(method: String, frame: JsonObject) {
        responses[method] = frame
    }

    suspend fun push(frame: JsonObject) {
        toPush.send(frame)
    }

    fun start() {
        val engine = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/mcp") {
                    val frame = decodeFrame(call.receiveText())
                    requests += frame
                    if (!frame.isRequest) {
                        // 202 for a notification is what makes our own client open its GET stream.
                        call.respondText("", ContentType.Text.Plain, HttpStatusCode.Accepted)
                        return@post
                    }
                    call.response.header("Mcp-Session-Id", "upstream-session")
                    call.respondText(answer(frame).encode(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                get("/mcp") {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        for (frame in toPush) {
                            write("data: ${frame.encode()}\n\n")
                            flush()
                        }
                    }
                }
                delete("/mcp") {
                    deletes += (call.request.headers["Mcp-Session-Id"] ?: "")
                    call.respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
                }
            }
        }
        engine.start(wait = false)
        port = runBlocking { engine.engine.resolvedConnectors().first().port }
        server = engine
    }

    fun stop() {
        toPush.close()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private fun answer(frame: JsonObject): JsonObject {
        responses[frame.method]?.let { canned ->
            return buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", frame.id!!)
                canned["result"]?.let { put("result", it) }
                canned["error"]?.let { put("error", it) }
            }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", frame.id!!)
            putJsonObject("result") {
                if (frame.method == "initialize") {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") {}
                    putJsonObject("serverInfo") {
                        put("name", "stub-upstream")
                        put("version", "1.0")
                    }
                } else {
                    put("echo", frame.method ?: "")
                }
            }
        }
    }
}
