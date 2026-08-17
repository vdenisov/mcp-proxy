/*
 * Adapted from the Model Context Protocol Kotlin SDK, tag 0.15.0:
 *   kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/
 *       StreamableHttpClientTransport.kt
 *   kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/
 *       ReconnectionOptions.kt
 *
 * Copyright the Model Context Protocol Kotlin SDK contributors.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * See VENDORED.md for the list of changes made to the original.
 */
package org.plukh.mcpproxy.upstream

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readUTF8Line
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.jsonrpc.isResponse
import org.plukh.mcpproxy.jsonrpc.method
import org.plukh.mcpproxy.jsonrpc.params
import org.plukh.mcpproxy.relay.AbstractEndpoint

private val log = KotlinLogging.logger {}

internal const val MCP_SESSION_ID_HEADER = "mcp-session-id"
internal const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
internal const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"
internal const val MCP_METHOD_HEADER = "Mcp-Method"
internal const val MCP_NAME_HEADER = "Mcp-Name"
private const val MCP_BASE64_PREFIX = "=?base64?"
private const val MCP_BASE64_SUFFIX = "?="

private const val DEFAULT_MAX_INLINE_SSE_EVENT_SIZE: Int = 16 * 1024 * 1024

/** An HTTP-level failure talking to the upstream server. */
class StreamableHttpError(
    val code: Int? = null,
    message: String? = null,
    /** The 401's challenge, when there was one - OAuth discovery starts from it. */
    val wwwAuthenticate: String? = null,
) : Exception("Streamable HTTP error: $message")

/** A single SSE event or line exceeded the configured cap. */
class TooLongFrameException(actual: Long, limit: Int) :
    Exception("Frame of $actual exceeds the $limit byte limit")

/** SSE reconnection backoff and retry limits. */
data class ReconnectionOptions(
    val initialReconnectionDelay: Duration = 1.seconds,
    val maxReconnectionDelay: Duration = 30.seconds,
    val reconnectionDelayMultiplier: Double = 1.5,
    val maxRetries: Int = 2,
)

private sealed interface ConnectResult {
    data class Success(val session: ClientSSESession) : ConnectResult
    data object NonRetryable : ConnectResult
    data object Failed : ConnectResult

    /** A 401 on the GET: not a connectivity failure, and backoff will not fix it. */
    data class Unauthorized(val wwwAuthenticate: String?) : ConnectResult
}

/**
 * The server-facing side: MCP Streamable HTTP, POST for sending and SSE for receiving.
 *
 * Frames are relayed as opaque [JsonObject]s. That is the whole point of vendoring this from the
 * SDK rather than using it directly: the SDK parses into a closed, typed hierarchy that silently
 * drops JSON Schema keywords it does not model and throws on result shapes it does not recognise,
 * which would make the proxy lossy exactly where it promises to be transparent.
 */
class HttpEndpoint(
    private val client: HttpClient,
    private val url: String,
    private val reconnectionOptions: ReconnectionOptions = ReconnectionOptions(),
    private val maxInlineSseEventSize: Int = DEFAULT_MAX_INLINE_SSE_EVENT_SIZE,
    /**
     * Emit `Mcp-Method` / `Mcp-Name`. Off by default: they put tool and resource names into HTTP
     * metadata that intermediaries log, and their presence is itself a client fingerprint.
     */
    private val sendMcpMethodHeaders: Boolean = false,
    /**
     * Supplies and refreshes the Authorization header for an OAuth-gated upstream. Null for static
     * or no auth. Its blocking work runs from [start] and between a 401 and its replay - never
     * inside the request pipeline, where the request timeout would kill a browser wait.
     */
    private val tokenSource: UpstreamTokenSource? = null,
) : AbstractEndpoint() {

    init {
        require(maxInlineSseEventSize > 0) { "maxInlineSseEventSize must be greater than 0" }
    }

    /** Session id assigned by the server, echoed on every subsequent request. */
    var sessionId: String? = null
        private set

    /**
     * Negotiated protocol version, sent as `MCP-Protocol-Version` on subsequent requests.
     *
     * The SDK declares this field but never assigns it, so that required header is never actually
     * sent. The relay sets it from the observed `initialize` result.
     *
     * Volatile because it is written from whichever coroutine delivered the initialize result -
     * possibly an SSE thread on [Dispatchers.Default] - and read from the POST coroutine.
     */
    @Volatile
    var protocolVersion: String? = null

    private var sseJob: Job? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private data class SseStreamResult(
        val hasPrimingEvent: Boolean,
        val receivedResponse: Boolean,
        val lastEventId: String? = null,
        val serverRetryDelay: Duration? = null,
    )

    /**
     * Streamable HTTP has no connection to establish, but an OAuth upstream may need a token before
     * the first POST - and this is the one place a first-run interactive flow can wait without an
     * HTTP request timeout ticking underneath it.
     */
    override suspend fun start() {
        tokenSource?.ensureToken()
    }

    override suspend fun send(frame: JsonObject) {
        log.trace { "POST $url: ${frame.encode()}" }

        var response = post(frame)

        // Reactive auth: refresh (or re-authorize) and replay exactly once. A loop would hammer a
        // server that keeps saying 401; a second failure surfaces as the per-request error below.
        if (response.status == HttpStatusCode.Unauthorized && tokenSource != null &&
            tokenSource.handleUnauthorized(response.headers[HttpHeaders.WWWAuthenticate])
        ) {
            log.info { "Replaying request after token refresh" }
            response = post(frame)
        }

        response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }

        if (response.status == HttpStatusCode.Accepted) {
            // The server accepted a notification with no body. `notifications/initialized` is the
            // cue to open the long-lived GET stream that carries server-initiated traffic.
            if (frame.method == "notifications/initialized") startSseSession(breakOnResponse = false)
            return
        }

        // Per-request failures throw and nothing more: the relay turns the exception into a
        // JSON-RPC error for this one request. Reporting them through errorHandler as well would
        // tear the whole session down over a single 401, 429 or malformed call.
        if (!response.status.isSuccess()) {
            throw StreamableHttpError(
                response.status.value,
                response.bodyAsText(),
                wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate],
            )
        }

        when (response.contentType()?.withoutParameters()) {
            ContentType.Application.Json ->
                response.bodyAsText().takeIf { it.isNotEmpty() }?.let { json ->
                    frameHandler?.invoke(decodeFrame(json))
                }

            ContentType.Text.EventStream -> {
                val result = handleInlineSse(response)
                if (result.hasPrimingEvent && !result.receivedResponse) {
                    // A resumption stream: it exists to deliver this request's response, so it is
                    // finished once that arrives.
                    startSseSession(
                        resumptionToken = result.lastEventId,
                        initialServerRetryDelay = result.serverRetryDelay,
                        breakOnResponse = true,
                    )
                }
            }

            else -> {
                val body = response.bodyAsText()
                if (response.contentType() == null && body.isBlank()) return

                val contentType = response.contentType()?.toString() ?: "<none>"
                throw StreamableHttpError(-1, "Unexpected content type: $contentType")
            }
        }
    }

    private suspend fun post(frame: JsonObject): HttpResponse = client.post(url) {
        applyCommonHeaders(this)
        if (sendMcpMethodHeaders) applyMcpMethodHeaders(this, frame)
        headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
        contentType(ContentType.Application.Json)
        setBody(frame.encode())
    }

    override suspend fun close() {
        log.debug { "Upstream closing" }
        sseJob?.cancelAndJoin()
        runCatching { terminateSession() }
            .onFailure { log.debug(it) { "Session termination failed; closing anyway" } }
        scope.cancel()
        closeHandler?.invoke()
    }

    /** Best-effort `DELETE` so the server can release the session. 405 means it does not support it. */
    suspend fun terminateSession() {
        val id = sessionId ?: return
        log.debug { "Terminating upstream session $id" }
        val response = client.delete(url) { applyCommonHeaders(this) }

        if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
            throw StreamableHttpError(
                response.status.value,
                "Failed to terminate session: ${response.status.description}",
            )
        }
        sessionId = null
    }

    /**
     * @param breakOnResponse stop collecting once a response frame arrives. True for a resumption
     *   stream, which exists only to deliver one request's response; false for the standalone GET
     *   stream, which must keep running for the life of the session - breaking it there would
     *   silently end all server-initiated traffic with nothing to restart it.
     */
    private fun startSseSession(
        breakOnResponse: Boolean,
        resumptionToken: String? = null,
        initialServerRetryDelay: Duration? = null,
    ) {
        // Cancel-and-replace: cancel() signals the previous job, join() inside the new coroutine
        // ensures it finished before we start collecting. Non-suspend so send() is not blocked.
        val previousJob = sseJob
        previousJob?.cancel()
        sseJob = scope.launch(CoroutineName("mcp-proxy-sse#${hashCode()}")) {
            previousJob?.join()
            var lastEventId = resumptionToken
            var serverRetryDelay = initialServerRetryDelay
            var attempt = 0
            var needsDelay = initialServerRetryDelay != null
            var authRetried = false

            while (isActive) {
                if (needsDelay) delay(nextReconnectionDelay(attempt, serverRetryDelay))
                needsDelay = true

                val session = when (val result = connectSse(lastEventId)) {
                    is ConnectResult.Success -> {
                        attempt = 0
                        authRetried = false
                        result.session
                    }

                    ConnectResult.NonRetryable -> return@launch

                    is ConnectResult.Unauthorized -> {
                        // Backoff cannot fix a 401; a token refresh can - once. A second 401 right
                        // after a successful refresh means the server rejects what the AS issued,
                        // which no amount of reconnecting will change. POSTs keep working (each one
                        // has its own reactive path), only server-initiated traffic stops.
                        if (!authRetried && tokenSource != null &&
                            runCatching { tokenSource.handleUnauthorized(result.wwwAuthenticate) }.getOrDefault(false)
                        ) {
                            log.info { "Reconnecting SSE stream after token refresh" }
                            authRetried = true
                            needsDelay = false
                            continue
                        }
                        log.warn { "GET/SSE stream unauthorized; server-initiated stream disabled" }
                        return@launch
                    }

                    ConnectResult.Failed -> {
                        if (++attempt >= reconnectionOptions.maxRetries) {
                            // Genuinely fatal: we have exhausted reconnection and the session can
                            // no longer receive server-initiated traffic.
                            errorHandler?.invoke(
                                StreamableHttpError(null, "Maximum reconnection attempts exceeded"),
                            )
                            return@launch
                        }
                        continue
                    }
                }

                val result = collectSse(session)
                lastEventId = result.lastEventId ?: lastEventId
                serverRetryDelay = result.serverRetryDelay ?: serverRetryDelay
                if (breakOnResponse && result.receivedResponse) break
            }
        }
    }

    private suspend fun connectSse(lastEventId: String?): ConnectResult {
        log.debug { "Opening SSE stream to $url" }
        // The stream outlives any single request, so it is the one place an access token can expire
        // while nothing else is talking to the server. Refreshing here turns "reconnect, get 401,
        // refresh, reconnect again" into one connect. A failure is not fatal: fall through and let
        // the 401 path handle it.
        runCatching { tokenSource?.ensureToken() }
            .onFailure { log.debug(it) { "Token refresh before SSE connect failed; connecting anyway" } }
        return try {
            val session = client.sseSession(urlString = url, showRetryEvents = true) {
                method = HttpMethod.Get
                applyCommonHeaders(this)
                accept(ContentType.Application.Json)
                lastEventId?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
            }
            ConnectResult.Success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SSEClientException) {
            when {
                e.response?.status == HttpStatusCode.Unauthorized ->
                    ConnectResult.Unauthorized(e.response?.headers?.get(HttpHeaders.WWWAuthenticate))

                isNonRetryableSseError(e) -> ConnectResult.NonRetryable
                else -> ConnectResult.Failed
            }
        } catch (e: Exception) {
            log.debug { "SSE connection failed: ${e.message}" }
            ConnectResult.Failed
        }
    }

    private fun nextReconnectionDelay(attempt: Int, serverRetryDelay: Duration?): Duration {
        // Per the SSE spec a server-sent `retry` sets the reconnection time for all subsequent
        // attempts, taking priority over our exponential backoff.
        serverRetryDelay?.let { return it }
        val delay = reconnectionOptions.initialReconnectionDelay *
            reconnectionOptions.reconnectionDelayMultiplier.pow(attempt)
        return delay.coerceAtMost(reconnectionOptions.maxReconnectionDelay)
    }

    private fun isNonRetryableSseError(e: SSEClientException): Boolean {
        val status = e.response?.status
        return when {
            status == HttpStatusCode.NotFound || status == HttpStatusCode.MethodNotAllowed -> {
                log.info { "Server returned ${status.value} for GET/SSE; server-initiated stream disabled" }
                true
            }

            e.response?.contentType()?.match(ContentType.Application.Json) == true -> {
                log.info { "Server returned application/json for GET/SSE; JSON-only mode" }
                true
            }

            else -> false
        }
    }

    private fun applyCommonHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
            protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
        }
    }

    private fun applyMcpMethodHeaders(builder: HttpRequestBuilder, frame: JsonObject) {
        val method = frame.method ?: return
        builder.headers {
            append(MCP_METHOD_HEADER, method)
            val params = frame.params ?: return@headers
            val name = params.stringValue("name") ?: params.stringValue("uri")
            name?.let { append(MCP_NAME_HEADER, it.encodeMcpHeaderValue()) }
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.encodeMcpHeaderValue(): String {
        val containsUnsafeCharacters = any { it != '\t' && it.code !in 0x20..0x7e }
        val hasEdgeWhitespace = firstOrNull()?.isWhitespace() == true || lastOrNull()?.isWhitespace() == true
        val matchesBase64Sentinel = startsWith(MCP_BASE64_PREFIX) && endsWith(MCP_BASE64_SUFFIX)

        if (!containsUnsafeCharacters && !hasEdgeWhitespace && !matchesBase64Sentinel) return this
        return "$MCP_BASE64_PREFIX${Base64.Default.encode(encodeToByteArray())}$MCP_BASE64_SUFFIX"
    }

    private suspend fun collectSse(session: ClientSSESession): SseStreamResult {
        var hasPrimingEvent = false
        var receivedResponse = false
        var lastEventId: String? = null
        var serverRetryDelay: Duration? = null
        try {
            session.incoming.collect { event ->
                event.retry?.let { serverRetryDelay = it.milliseconds }
                event.id?.let {
                    lastEventId = it
                    hasPrimingEvent = true
                }
                log.trace { "SSE event=${event.event} id=${event.id} data=${event.data}" }
                when (event.event) {
                    null, "message" -> event.data?.takeIf { it.isNotEmpty() }?.let { json ->
                        runCatching { decodeFrame(json) }
                            .onSuccess { frame ->
                                if (frame.isResponse) receivedResponse = true
                                frameHandler?.invoke(frame)
                            }
                            .onFailure { log.warn(it) { "Discarding unparseable SSE frame" } }
                    }

                    "error" -> log.warn { "Server sent an SSE error event: ${event.data}" }
                }
            }
        } catch (_: CancellationException) {
            // Expected on teardown.
        } catch (t: Throwable) {
            // Recoverable: the caller's reconnect loop retries with backoff. Reporting this as a
            // fatal error would make the reconnection machinery unreachable.
            log.debug(t) { "SSE stream ended; will reconnect if attempts remain" }
        }
        return SseStreamResult(hasPrimingEvent, receivedResponse, lastEventId, serverRetryDelay)
    }

    /** Parses an SSE body delivered inline as the POST response. */
    private suspend fun handleInlineSse(response: HttpResponse): SseStreamResult {
        val channel = response.bodyAsChannel()

        var hasPrimingEvent = false
        var receivedResponse = false
        var lastEventId: String? = null
        var serverRetryDelay: Duration? = null
        val data = StringBuilder()
        var id: String? = null
        var eventName: String? = null

        suspend fun dispatch(eventId: String?, event: String?, payload: String) {
            eventId?.let {
                lastEventId = it
                hasPrimingEvent = true
            }
            if (payload.isBlank()) return
            if (event == null || event == "message") {
                // Throws on a malformed frame: this is the POST response path, so the failure
                // belongs to the request in flight and the relay answers it with a JSON-RPC error.
                val frame = decodeFrame(payload)
                if (frame.isResponse) receivedResponse = true
                frameHandler?.invoke(frame)
            }
            if (event == "error") log.warn { "Server sent an SSE error event: $payload" }
        }

        while (!channel.isClosedForRead) {
            // Bound each line so a server streaming an unterminated line cannot exhaust memory.
            val line = try {
                channel.readUTF8Line(maxInlineSseEventSize)
            } catch (_: TooLongLineException) {
                throw TooLongFrameException(maxInlineSseEventSize.toLong() + 1, maxInlineSseEventSize)
            } ?: break

            if (line.isEmpty()) {
                dispatch(id, eventName, data.toString())
                id = null
                eventName = null
                data.clear()
                continue
            }
            when {
                line.startsWith("id:") -> id = line.substringAfter("id:").trim()
                line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                line.startsWith("data:") -> {
                    // Per the SSE spec, consecutive data: fields are joined with a newline and only
                    // a single leading space is stripped. The SDK concatenates them bare and trims
                    // each fragment, which loses significant whitespace; Ktor's own parser used by
                    // collectSse gets this right, so the two paths would otherwise disagree.
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter("data:").removePrefix(" "))
                    // Cap an event assembled from many data: lines with no terminating blank line.
                    if (data.length > maxInlineSseEventSize) {
                        throw TooLongFrameException(data.length.toLong(), maxInlineSseEventSize)
                    }
                }

                line.startsWith("retry:") ->
                    line.substringAfter("retry:").trim().toLongOrNull()?.let {
                        serverRetryDelay = it.milliseconds
                    }
            }
        }
        return SseStreamResult(hasPrimingEvent, receivedResponse, lastEventId, serverRetryDelay)
    }
}
