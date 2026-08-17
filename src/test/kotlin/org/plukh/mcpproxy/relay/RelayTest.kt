package org.plukh.mcpproxy.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.plukh.mcpproxy.config.IdentityConfig
import org.plukh.mcpproxy.jsonrpc.JsonRpc
import org.plukh.mcpproxy.jsonrpc.decodeFrame
import org.plukh.mcpproxy.jsonrpc.encode
import org.plukh.mcpproxy.jsonrpc.method

@OptIn(ExperimentalCoroutinesApi::class)
class RelayTest {

    private val identity = IdentityConfig(name = "mcp-proxy", version = "1.0.0", userAgent = "mcp-proxy/1.0")

    private class Harness(identity: IdentityConfig) {
        val client = FakeEndpoint()
        val server = FakeEndpoint()
        val relay = Relay(client, server, IdentityRewriter(identity))
    }

    /**
     * Runs the relay in [kotlinx.coroutines.test.TestScope.backgroundScope]: `relay.run()` only
     * returns when a side disconnects, so a plain `launch` would leave runTest waiting forever.
     */
    private suspend fun TestScope.start(h: Harness) {
        backgroundScope.launch { h.relay.run() }
        // Let the relay register its handlers before the test drives either endpoint.
        kotlinx.coroutines.yield()
    }

    // --- fidelity: the reason this proxy does not use the MCP SDK on the data path ---

    @Test
    fun `a tool schema relays byte-for-byte`() = runTest {
        val h = Harness(identity)
        start(h)

        // Every one of these keywords is silently dropped by the SDK's ToolSchema, which models
        // only $schema/properties/required/$defs and hardcodes type=object.
        val toolsList = decodeFrame(
            """
            {"jsonrpc":"2.0","id":7,"result":{"tools":[{
              "name":"search",
              "description":"Search things",
              "inputSchema":{
                "type":"object",
                "title":"SearchArgs",
                "description":"Arguments for search",
                "additionalProperties":false,
                "properties":{"q":{"type":"string"}},
                "required":["q"],
                "oneOf":[{"required":["q"]},{"required":["id"]}],
                "${'$'}ref":"#/${'$'}defs/Thing",
                "x-vendor-extension":{"anything":[1,2,3]}
              }
            }]}}
            """.trimIndent().replace("\n", ""),
        )

        h.server.receive(toolsList)

        val delivered = h.client.nextSent()
        assertEquals(toolsList, delivered, "tool schema must survive the relay unmodified")
        // Structural equality is the real assertion; serialised equality guards key ordering too.
        assertEquals(toolsList.encode(), delivered.encode())
    }

    @Test
    fun `an unknown result shape relays instead of throwing`() = runTest {
        val h = Harness(identity)
        start(h)

        // The SDK's polymorphic serializer infers the result type from which keys are present and
        // throws when nothing matches, which would kill the message rather than degrade.
        val future = decodeFrame("""{"jsonrpc":"2.0","id":9,"result":{"somethingNobodyHasSpecdYet":{"a":1}}}""")
        h.server.receive(future)

        assertEquals(future, h.client.nextSent())
    }

    @Test
    fun `serverInfo fields the SDK cannot model survive`() = runTest {
        val h = Harness(identity)
        start(h)

        // Real Context7 sends serverInfo.description; the SDK's Implementation has no such field.
        val initializeResult = decodeFrame(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{"tools":{"listChanged":true}},""" +
                """"serverInfo":{"name":"Context7","version":"4.0.1","description":"Up-to-date docs",""" +
                """"websiteUrl":"https://context7.com","icons":[{"src":"https://x/i.png"}]},""" +
                """"instructions":"Use resolve-library-id first."}}""",
        )

        h.server.receive(initializeResult)

        assertEquals(initializeResult, h.client.nextSent())
    }

    // --- identity rewriting ---

    @Test
    fun `initialize carries our identity upstream, not the client's`() = runTest {
        val h = Harness(identity)
        start(h)

        h.client.receive(
            decodeFrame(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                    """"protocolVersion":"2025-06-18",""" +
                    """"capabilities":{"sampling":{},"roots":{"listChanged":true}},""" +
                    """"clientInfo":{"name":"claude-code","version":"2.1.233"}}}""",
            ),
        )

        val sent = h.server.nextSent()
        val params = sent["params"] as JsonObject
        val clientInfo = params["clientInfo"] as JsonObject

        assertEquals("mcp-proxy", clientInfo["name"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", clientInfo["version"]!!.jsonPrimitive.content)
        assertFalse(
            sent.encode().contains("claude-code"),
            "the real client name must not appear anywhere in the outbound frame",
        )
        // Untouched: version negotiation and id must pass through.
        assertEquals("2025-06-18", params["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(1, sent["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `strict mode keeps only the three spec params`() = runTest {
        val h = Harness(identity)
        start(h)

        h.client.receive(
            decodeFrame(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                    """"protocolVersion":"2025-06-18","capabilities":{},""" +
                    """"clientInfo":{"name":"c","version":"1"},""" +
                    """"_meta":{"vendor":"acme"},"vendorExtra":"leaky"}}""",
            ),
        )

        val params = h.server.nextSent()["params"] as JsonObject
        assertEquals(setOf("protocolVersion", "capabilities", "clientInfo"), params.keys)
    }

    @Test
    fun `non-strict mode forwards vendor extras`() = runTest {
        val h = Harness(identity.copy(strictInitializeParams = false))
        start(h)

        h.client.receive(
            decodeFrame(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                    """"protocolVersion":"2025-06-18","capabilities":{},""" +
                    """"clientInfo":{"name":"c","version":"1"},"vendorExtra":"kept"}}""",
            ),
        )

        val params = h.server.nextSent()["params"] as JsonObject
        assertEquals("kept", params["vendorExtra"]!!.jsonPrimitive.content)
    }

    @Test
    fun `capabilities pass through but extension buckets are dropped`() = runTest {
        val h = Harness(identity)
        start(h)

        h.client.receive(
            decodeFrame(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                    """"protocolVersion":"2025-06-18",""" +
                    """"capabilities":{"sampling":{},"roots":{"listChanged":true},""" +
                    """"experimental":{"acmeThing":true},"extensions":{"x":1}},""" +
                    """"clientInfo":{"name":"c","version":"1"}}}""",
            ),
        )

        val caps = (h.server.nextSent()["params"] as JsonObject)["capabilities"] as JsonObject
        assertEquals(setOf("sampling", "roots"), caps.keys)
    }

    @Test
    fun `only initialize is rewritten`() = runTest {
        val h = Harness(identity)
        start(h)

        val call = decodeFrame(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search","arguments":{"q":"x"}}}""",
        )
        h.client.receive(call)

        assertEquals(call, h.server.nextSent())
    }

    // --- transparent relay of everything else ---

    @Test
    fun `notifications relay in both directions`() = runTest {
        val h = Harness(identity)
        start(h)

        val fromClient = decodeFrame("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        h.client.receive(fromClient)
        assertEquals(fromClient, h.server.nextSent())

        val fromServer = decodeFrame("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
        h.server.receive(fromServer)
        assertEquals(fromServer, h.client.nextSent())
    }

    @Test
    fun `server-initiated requests relay downstream, so stage 2 needs no new code`() = runTest {
        val h = Harness(identity)
        start(h)

        val sampling = decodeFrame(
            """{"jsonrpc":"2.0","id":"s1","method":"sampling/createMessage","params":{"messages":[]}}""",
        )
        h.server.receive(sampling)
        assertEquals(sampling, h.client.nextSent())

        val reply = decodeFrame("""{"jsonrpc":"2.0","id":"s1","result":{"role":"assistant"}}""")
        h.client.receive(reply)
        assertEquals(reply, h.server.nextSent())
    }

    @Test
    fun `upstream errors relay verbatim`() = runTest {
        val h = Harness(identity)
        start(h)

        val error = decodeFrame(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32002,"message":"Resource not found","data":{"uri":"file:///x"}}}""",
        )
        h.server.receive(error)

        assertEquals(error, h.client.nextSent())
    }

    @Test
    fun `a failed upstream send becomes a JSON-RPC error, not a dropped request`() = runTest {
        val client = FakeEndpoint()
        val server = object : FakeEndpoint() {
            override suspend fun send(frame: JsonObject) = throw RuntimeException("connection refused")
        }
        backgroundScope.launch { Relay(client, server, IdentityRewriter(identity)).run() }
        kotlinx.coroutines.yield()

        client.receive(decodeFrame("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""))

        val error = client.nextSent()
        assertEquals(3, error["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            JsonRpc.INTERNAL_ERROR,
            (error["error"] as JsonObject)["code"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `a failed notification is dropped, not answered`() = runTest {
        val client = FakeEndpoint()
        val server = object : FakeEndpoint() {
            override suspend fun send(frame: JsonObject) = throw RuntimeException("connection refused")
        }
        backgroundScope.launch { Relay(client, server, IdentityRewriter(identity)).run() }
        kotlinx.coroutines.yield()

        client.receive(decodeFrame("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))
        kotlinx.coroutines.yield()

        assertNull(client.sent.tryReceive().getOrNull(), "a notification has no id, so there is nothing to answer")
    }

    @Test
    fun `a per-request upstream failure does not end the session`() = runTest {
        // Regression: send() used to report per-request failures through errorHandler as well as
        // throwing, so a single 401 or 429 tore down the whole proxy. Verified against a live 404:
        // the client got its error frame and every later request went unanswered.
        val client = FakeEndpoint()
        val server = object : FakeEndpoint() {
            var failNext = true
            override suspend fun send(frame: JsonObject) {
                if (failNext) {
                    failNext = false
                    throw RuntimeException("HTTP 429 Too Many Requests")
                }
                super.send(frame)
            }
        }
        backgroundScope.launch { Relay(client, server, IdentityRewriter(identity)).run() }
        kotlinx.coroutines.yield()

        client.receive(decodeFrame("""{"jsonrpc":"2.0","id":1,"method":"tools/call"}"""))
        val error = client.nextSent()
        assertEquals(JsonRpc.INTERNAL_ERROR, (error["error"] as JsonObject)["code"]!!.jsonPrimitive.content.toInt())

        // The session must still be alive for the next request.
        val second = decodeFrame("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        client.receive(second)
        assertEquals(second, server.nextSent(), "session died after one failed request")
    }

    @Test
    fun `client disconnect ends the session cleanly`() = runTest {
        val h = Harness(identity)
        var code: Int? = null
        launch { code = h.relay.run() }
        kotlinx.coroutines.yield()

        h.client.closeRemote()
        kotlinx.coroutines.yield()

        assertEquals(0, code)
        assertTrue(h.server.closedCount > 0, "upstream should be closed on teardown")
    }
}
