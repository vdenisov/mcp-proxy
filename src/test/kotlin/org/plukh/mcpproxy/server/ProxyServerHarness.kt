package org.plukh.mcpproxy.server

import java.time.Clock
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.ServerConfig
import org.plukh.mcpproxy.config.ServerSettings
import org.plukh.mcpproxy.config.UpstreamConfig
import org.plukh.mcpproxy.config.resolvedPublicUrl

/**
 * Stands up a [ProxyServer] on an ephemeral port with upstreams given directly, skipping config
 * files - the resolution path has its own tests, and dragging it in here would make every server
 * test also a filesystem test.
 */
internal class ProxyServerHarness(
    upstreams: Map<String, ProxyConfig>,
    settings: ServerSettings = ServerSettings(port = 0),
    eventStoreMaxChars: Long = 1_000_000,
    clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val callbacks = CallbackRegistry()
    private val runtimes: Map<String, UpstreamRuntime>
    val server: ProxyServer

    init {
        // The redirect URI has to name the port before anything binds it, so the port is chosen
        // first and used as a fixed one - the same shape a real deployment has.
        val port = if (settings.port != 0) settings.port else freePort()
        val config = ServerConfig(
            server = settings.copy(port = port, publicUrl = settings.publicUrl ?: "http://127.0.0.1:$port"),
            upstreams = upstreams.keys.toList(),
        )
        runtimes = buildRuntimes(upstreams, config.server.resolvedPublicUrl(), callbacks)
        server = ProxyServer(config, runtimes, callbacks, InMemoryEventStore(eventStoreMaxChars), clock)
        server.start()
    }

    val port: Int get() = server.boundPort

    fun endpointUrl(name: String) = "http://127.0.0.1:$port/$name/mcp"

    fun baseUrl() = "http://127.0.0.1:$port"

    override fun close() {
        server.close()
    }
}

/** An HTTP upstream config pointing at [url], with no auth. */
internal fun httpUpstream(url: String): ProxyConfig =
    ProxyConfig(upstream = UpstreamConfig(url = url, requestTimeoutSeconds = 10))

/** A port nothing is listening on right now. Racy in principle, standard in practice. */
internal fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
