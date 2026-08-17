package org.plukh.mcpproxy.upstream

import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A stub MCP server that runs as a child process, so the stdio upstream is exercised against a real
 * pipe and a real process lifecycle rather than a fake.
 *
 * Deliberately dependency-light - no logging framework, no proxy classes beyond kotlinx-serialization -
 * because anything it pulls in could itself write to stdout and corrupt the protocol stream. Behaviour
 * is chosen by argv, the process-boundary equivalent of the scripted stub in `HttpEndpointStreamTest`.
 *
 * Every response echoes the request back under `result.echo`. That is the side channel tests use to
 * assert what actually reached the server - the in-process equivalent is `ProxyEndToEndTest`'s list of
 * recorded request bodies, which a separate process cannot share.
 */
object StubScenario {
    const val ECHO = "ECHO"
    const val DIE_AFTER_FIRST_RESPONSE = "DIE_AFTER_FIRST_RESPONSE"
    const val GARBAGE_THEN_ECHO = "GARBAGE_THEN_ECHO"
    const val STDERR_CHATTER = "STDERR_CHATTER"

    /**
     * Never reads stdin and never exits, so closing its stdin achieves nothing. This is the only
     * scenario under which killing the child is what ends it - a well-behaved server exits on stdin
     * EOF all by itself, which makes it useless for testing that we can kill one.
     */
    const val IGNORES_SHUTDOWN = "IGNORES_SHUTDOWN"

    /** Exit code when the proxy sends us an error frame, which it must never do. */
    const val UNEXPECTED_ERROR_FRAME = 99

    /** Exit code for the deliberate mid-session death. */
    const val DELIBERATE_DEATH = 13

    const val STDERR_MARKER = "STUB-STDERR-MARKER"
}

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    val scenario = args.firstOrNull() ?: StubScenario.ECHO

    if (scenario == StubScenario.IGNORES_SHUTDOWN) {
        while (true) Thread.sleep(60_000)
    }
    if (scenario == StubScenario.GARBAGE_THEN_ECHO) {
        emit("this is not json {{{")
    }
    if (scenario == StubScenario.STDERR_CHATTER) {
        System.err.println("${StubScenario.STDERR_MARKER} server starting")
        System.err.println("${StubScenario.STDERR_MARKER} listening on stdin")
        System.err.flush()
    }

    var answered = 0
    while (true) {
        val line = readlnOrNull() ?: exitProcess(0) // stdin EOF: the polite shutdown
        if (line.isBlank()) continue

        val frame = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue

        // The proxy must never relay a parse error to a server - a malformed line from us is its
        // problem to drop, not ours to answer.
        if (frame.containsKey("error")) exitProcess(StubScenario.UNEXPECTED_ERROR_FRAME)

        val id = frame["id"] ?: continue // a notification: nothing to answer
        emit(json.encodeToString(JsonObject.serializer(), response(id = id, request = frame)))
        answered++

        if (scenario == StubScenario.DIE_AFTER_FIRST_RESPONSE && answered == 1) {
            exitProcess(StubScenario.DELIBERATE_DEATH)
        }
    }
}

private fun response(id: kotlinx.serialization.json.JsonElement, request: JsonObject) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("result") {
        put("protocolVersion", "2025-06-18")
        putJsonObject("capabilities") {}
        putJsonObject("serverInfo") {
            put("name", "stub-stdio-server")
            put("version", "1.0.0")
        }
        put("echo", request)
    }
}

private fun emit(line: String) {
    // print + flush rather than println through a buffered writer: an unflushed response would look
    // to the proxy exactly like a server that never answered.
    System.out.write((line + "\n").toByteArray(Charsets.UTF_8))
    System.out.flush()
}
