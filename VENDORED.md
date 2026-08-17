# Vendored code

The MCP Kotlin SDK is Apache-2.0; the files below keep their upstream copyright headers and a copy of
that license is at `licenses/APACHE-2.0.txt`. The project itself is MIT (`LICENSE`).

## `org.plukh.mcpproxy.upstream.HttpEndpoint`

**Source:** [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk), tag `0.15.0`, Apache-2.0

- `kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/StreamableHttpClientTransport.kt`
- `kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/ReconnectionOptions.kt`

### Why vendored rather than depended on

The proxy relays frames verbatim. The SDK cannot: it parses every message into a closed, typed
hierarchy inside the transport, below any hook a caller controls.

- `Tool.inputSchema` is `ToolSchema`, which models only `$schema`, `properties`, `required` and
  `$defs`, hardcodes `type = "object"`, and has no catch-all. Relaying `tools/list` through it
  silently drops `additionalProperties`, root-level `description`/`title`, `oneOf`/`anyOf`/`allOf`
  and `$ref`, and corrupts any tool whose schema is not an object.
- `RequestResultPolymorphicSerializer` infers the result class from which keys are present and
  throws on a shape it does not recognise, so an unfamiliar or future result kills the message
  rather than degrading.
- Outbound, `Request` is a sealed interface and the only open implementation, `CustomRequest`,
  takes `BaseRequestParams` - which carries only `_meta`. There is no way to send an arbitrary
  method with real arguments at all.

Taking the transport and dropping the type layer keeps the parts that are genuinely hard (SSE
priming, `202 Accepted` handling, resumption via `Last-Event-ID`, reconnection backoff) and removes
the parts that break transparency. The SDK is consequently not a runtime dependency.

### Changes made to the original

1. **Frames are `JsonObject`, not `JSONRPCMessage`.** `McpJson.decodeFromString<JSONRPCMessage>()`
   became `decodeFrame()` and `McpJson.encodeToString(message)` became `frame.encode()`, in
   `send`, `collectSse` and `handleInlineSse`. This is the whole point of the fork.
2. **Base class.** `AbstractClientTransport` → our `AbstractEndpoint`; `_onMessage` / `_onError`
   became `frameHandler` / `errorHandler`.
3. **`Mcp-Method` / `Mcp-Name` are opt-in** (`sendMcpMethodHeaders`, default off). They leak tool
   and resource names into HTTP metadata that CDNs and reverse proxies log, and their presence is
   itself a client fingerprint. No server is known to require them.
4. **`protocolVersion` is actually assigned.** The SDK declares the field and reads it when emitting
   `MCP-Protocol-Version`, but never writes it, so that header is never sent. The relay now sets it
   from the observed `initialize` result.
5. **Dropped `replayMessageId`.** The SDK rewrites a response's `id` to the id of the request that
   was POSTed. In a raw relay the client's own ids pass through untouched, so the server's response
   already carries the right one - and rewriting it would mutate a frame we promise to relay as-is.
6. **Dropped `TransportSendOptions` and the caller-supplied resumption token.** Resumption is
   managed internally via `Last-Event-ID`; nothing outside the endpoint needs to drive it.
7. **Dropped the deprecated secondary constructor** and the `requestBuilder` parameter - headers are
   owned by the `IdentityHeaders` Ktor plugin, which applies to every request from one place.
8. `ReconnectionOptions` became a `data class` (the original hand-writes `equals`/`hashCode`).
9. Logging goes through a file-level `KotlinLogging` logger rather than an overridden member.
10. **401-driven token refresh (stage 3).** The SDK has no auth handling at all. Added: an optional
    `tokenSource: UpstreamTokenSource` constructor parameter; `start()` calls its `ensureToken()`
    (the SDK's `start()` is empty); the POST in `send` is extracted into a private `post()` and
    replayed exactly once after a successful `handleUnauthorized()`; `StreamableHttpError` carries
    the `WWW-Authenticate` challenge; `connectSse` classifies a 401 as its own `ConnectResult`
    variant, retried once without backoff after a refresh and then disabling the GET stream, since
    backoff cannot fix a 401 and the old behavior (silent retry to `maxRetries`, then fatal) tore
    the session down.

### Upgrade procedure

When bumping the SDK version, diff the two upstream files against tag `0.15.0` and re-apply changes
1-10. The logic worth watching is `send`'s `202 Accepted` branch and `startSseSession`'s
cancel-and-replace - both are subtle and both were kept verbatim apart from the type substitution -
plus the 401/replay block in `send` and the `Unauthorized` branch of the reconnect loop, which are
ours entirely.

## `org.plukh.mcpproxy.server.HttpServerEndpoint` and `EventStore`

**Reference:** the same SDK, tag `0.15.0`, Apache-2.0

- `kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/
  StreamableHttpServerTransport.kt`
- `kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/EventStore.kt`

### Derived, not copied - and why

The client transport above was taken wholesale because its hard parts (SSE priming, `202 Accepted`,
resumption, backoff) are exactly what we need and the typed layer was localised to three functions.
The server transport was read closely and **re-implemented**, because the shape of the problem
differs:

- Roughly 60% of its ~950 lines exists to serve an SDK `Server` that runs *N concurrent handlers per
  POST*: request batching (dropped from the spec in 2025-06-18 anyway), the `enableJsonResponse`
  duality between per-request SSE streams and collected JSON responses, `pendingJsonResponses`,
  `cancelledRequestIds` and the settle-tracking that goes with them. A relay is one frame in, one
  frame out; every bit of that machinery would be untested dead code we still had to maintain.
- Its typed layer is not localised - `when (message) { is JSONRPCResponse -> ... }` *is* the routing
  logic, so substituting `JsonObject` means rewriting the routing rather than the edges.

What was taken, and is acknowledged as derivative:

1. **The routing rule.** A response is delivered to whatever is waiting for its request id; anything
   else goes to the standalone stream. Ours keys on `JsonElement` ids in a `ConcurrentHashMap`
   instead of `requestToStreamMapping`, because there is exactly one stream per session.
2. **The standalone stream lifecycle**: one active GET per session, a new one displacing the old,
   and the loser deregistering only *its own* entry - the identity guard that stops a slow
   disconnect from evicting its successor.
3. **The `EventStore` contract**: append returns the event id; replay is "everything after this id";
   an unknown id is reported as unknown so the caller starts a fresh stream rather than inventing
   continuity. Our `InMemoryEventStore` is our own, bounded by retained characters rather than event
   count, evicting oldest-first across all streams.

Deliberately not implemented: batching, stateless mode, per-request SSE streams, priming events
(2025-11-25), the deprecated DNS-rebinding configuration (we do a plain `Origin` check on the MCP
routes instead), and `closeSseStream`.

Nothing here depends on `ktor-server-sse`: the stream is written with `respondTextWriter`, matching
how the rest of this codebase serves SSE.
