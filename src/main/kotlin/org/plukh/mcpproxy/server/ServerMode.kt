package org.plukh.mcpproxy.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.config.ConfigException
import org.plukh.mcpproxy.config.ConfigLoader
import org.plukh.mcpproxy.config.ConfigResolver
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.config.ServerConfig
import org.plukh.mcpproxy.config.resolvedPublicUrl

private val log = KotlinLogging.logger {}

/**
 * `--serve`: resolve every named upstream, stand up the listener, and stay up until the process is
 * asked to stop.
 */
object ServerMode {

    fun run(config: ServerConfig, home: Path): Int {
        val upstreams = try {
            loadUpstreams(config, home)
        } catch (e: ConfigException) {
            System.err.println("mcp-proxy: ${e.message}")
            return ExitCodes.CONFIG_ERROR
        }

        val callbacks = CallbackRegistry()
        val eventStore = InMemoryEventStore(config.server.eventStoreMaxBytes)
        val runtimes = buildRuntimes(upstreams, config.server.resolvedPublicUrl(), callbacks)
        val server = ProxyServer(config, runtimes, callbacks, eventStore)

        // SIGTERM is how a container is asked to stop, and the JVM is PID 1 in ours, so this hook is
        // the whole graceful path. It must *wait* for the shutdown it triggers: a hook that only
        // signals and returns lets the JVM terminate while sessions are still closing, which loses
        // exactly the upstream DELETEs the graceful path exists to send.
        val stopped = CompletableDeferred<Unit>()
        val hook = Thread {
            server.requestStop()
            runBlocking { withTimeoutOrNull(15.seconds) { stopped.await() } }
        }

        return try {
            server.start()
            Runtime.getRuntime().addShutdownHook(hook)
            try {
                runBlocking { server.awaitShutdown() }
            } finally {
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        } catch (e: Exception) {
            // A port already in use is the common one, and it is an environment mismatch rather
            // than a crash: one readable line, same as any config failure.
            System.err.println("mcp-proxy: could not start the server: ${e.message}")
            ExitCodes.CONFIG_ERROR
        } finally {
            server.close()
            log.info { "Server stopped" }
            // Releases the shutdown hook, if that is what got us here.
            stopped.complete(Unit)
        }
    }

    /** Each name resolves exactly as the CLI's `CONFIG` argument does - same lookup, same errors. */
    private fun loadUpstreams(config: ServerConfig, home: Path): Map<String, ProxyConfig> =
        config.upstreams.associateWith { name ->
            val path = ConfigResolver.resolve(name, home)
            log.info { "Upstream '$name' from ${path.toAbsolutePath()}" }
            ConfigLoader.load(path)
        }
}
