package org.plukh.mcpproxy.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Loads [ServerConfig]. Mirrors [ConfigLoader] deliberately - same strict parse, same `${VAR}`
 * interpolation, same one-readable-line failure contract - because the two are read by the same
 * command and a user should not be able to tell which loader rejected their file.
 */
object ServerConfigLoader {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    /** Names must survive being a URL path segment untouched, so no encoding question ever arises. */
    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

    /**
     * Unlike [ConfigLoader.load] there is no null-path form: a server with no upstreams to serve is
     * not a thing to default into.
     *
     * @throws ConfigException on any read, parse, interpolation or validation failure
     */
    fun load(path: Path, env: (String) -> String? = System::getenv): ServerConfig =
        interpolate(parse(path), env).also(::validate)

    private fun parse(path: Path): ServerConfig {
        if (Files.isDirectory(path) || !Files.isReadable(path)) {
            throw ConfigException("Server config not found or not readable: ${path.pathString}")
        }
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            throw ConfigException("Could not read ${path.pathString}: ${e.message}", e)
        }
        // No blank-file shortcut: server.port has no default, so an empty document is an error and
        // the parser's own message says so.
        return try {
            yaml.decodeFromString(ServerConfig.serializer(), text)
        } catch (e: YamlException) {
            throw ConfigException("${path.pathString}: ${e.message} (line ${e.line}, column ${e.column})", e)
        } catch (e: Exception) {
            throw ConfigException("${path.pathString}: ${e.message}", e)
        }
    }

    private fun interpolate(config: ServerConfig, env: (String) -> String?) = config.copy(
        server = config.server.copy(
            bindHost = config.server.bindHost.interpolate("server.bindHost", env),
            publicUrl = config.server.publicUrl?.interpolate("server.publicUrl", env),
        ),
        upstreams = config.upstreams.mapIndexed { i, name -> name.interpolate("upstreams[$i]", env) },
        logging = config.logging.copy(file = config.logging.file?.interpolate("logging.file", env)),
    )

    private fun validate(config: ServerConfig) {
        val server = config.server
        if (server.port !in 1..65535) {
            throw ConfigException("server.port must be 1..65535, got ${server.port}")
        }
        if (server.bindHost.isBlank()) {
            throw ConfigException("server.bindHost must not be blank")
        }
        server.publicUrl?.let {
            if (!it.startsWith("http")) {
                throw ConfigException("server.publicUrl must be an http(s) URL, got: $it")
            }
        }
        if (server.sessionIdleTimeoutSeconds <= 0) {
            throw ConfigException("server.sessionIdleTimeoutSeconds must be positive, got ${server.sessionIdleTimeoutSeconds}")
        }
        if (server.maxSessions <= 0) {
            throw ConfigException("server.maxSessions must be positive, got ${server.maxSessions}")
        }
        if (server.eventStoreMaxBytes <= 0) {
            throw ConfigException("server.eventStoreMaxBytes must be positive, got ${server.eventStoreMaxBytes}")
        }
        if (config.logging.level.uppercase() !in VALID_LOG_LEVELS) {
            throw ConfigException(
                "logging.level must be one of ${VALID_LOG_LEVELS.joinToString(", ")}, got: ${config.logging.level}",
            )
        }
        validateUpstreamNames(config.upstreams)
    }

    private fun validateUpstreamNames(names: List<String>) {
        if (names.isEmpty()) {
            throw ConfigException("upstreams must list at least one config name to serve")
        }
        names.forEach { name ->
            // A name is both a config to resolve and a URL path segment. Path-shaped entries are
            // refused for the reason ConfigResolver refuses them - a name must mean one file
            // regardless of the working directory - and the charset keeps the route literal.
            if (ConfigResolver.isPathShaped(name)) {
                throw ConfigException("upstream name must be a plain name, not a path: $name")
            }
            if (!NAME_PATTERN.matches(name)) {
                throw ConfigException("upstream name may only contain letters, digits, '.', '_' and '-', got: $name")
            }
            if (name.lowercase() in RESERVED_UPSTREAM_NAMES) {
                throw ConfigException(
                    "upstream name '$name' is reserved for the server's own routes " +
                        "(${RESERVED_UPSTREAM_NAMES.sorted().joinToString(", ")})",
                )
            }
            if (name.startsWith("_") || name.startsWith(".")) {
                throw ConfigException("upstream name '$name' is reserved: names may not start with '_' or '.'")
            }
        }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ConfigException("upstream '$it' is listed more than once")
        }
    }

    private val VALID_LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
}

/** The browser-facing base URL, defaulted from the bound port. Trailing slash stripped so callers can concatenate. */
internal fun ServerSettings.resolvedPublicUrl(): String =
    (publicUrl ?: "http://127.0.0.1:$port").trimEnd('/')

/** True when nothing outside this machine can reach the listener. */
internal fun ServerSettings.bindsLoopbackOnly(): Boolean =
    bindHost == "127.0.0.1" || bindHost == "::1" || bindHost.equals("localhost", ignoreCase = true)
