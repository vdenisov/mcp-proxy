package org.plukh.mcpproxy

import java.nio.file.Path
import org.plukh.mcpproxy.config.ConfigException

/** Expands a leading `~`, which a shell would have expanded but a config file value never is. */
internal fun expandUserHome(path: String): Path = when {
    path == "~" -> Path.of(System.getProperty("user.home"))
    path.startsWith("~/") || path.startsWith("~\\") ->
        Path.of(System.getProperty("user.home"), path.substring(2))

    else -> Path.of(path)
}

/**
 * The proxy's home: config lookup root, and the base every other configured path is resolved
 * against. `$MCP_PROXY_HOME` overrides it, and everything the proxy would put in the home - configs,
 * tokens, registrations, log files - moves with it. A container where `$HOME` is `/root` or
 * read-only has to relocate the whole set, not part of it.
 *
 * A blank value counts as unset, and a **relative** one is rejected outright. Both would otherwise
 * make the home working-directory-relative and hand config selection back to whoever spawned the
 * proxy - `MCP_PROXY_HOME=configs` in a client's `env` block resolves against a directory the client
 * chose, which is precisely the shadowing that [org.plukh.mcpproxy.config.ConfigResolver] refuses to
 * allow, with an arbitrary `upstream.command` at the end of it. Absolutizing instead of rejecting
 * would only paper over it: `toAbsolutePath()` resolves against that same working directory.
 *
 * @param env environment lookup; injectable because a JVM cannot mutate its own environment
 *
 * @throws org.plukh.mcpproxy.config.ConfigException if `MCP_PROXY_HOME` is set to a relative path
 */
internal fun proxyHome(env: (String) -> String? = System::getenv): Path {
    val configured = env("MCP_PROXY_HOME")?.takeUnless { it.isBlank() }
        ?: return Path.of(System.getProperty("user.home"), ".mcp-proxy")

    return expandUserHome(configured).also {
        if (!it.isAbsolute) {
            throw ConfigException("MCP_PROXY_HOME must be an absolute path, got: $configured")
        }
    }
}

/**
 * A configured path made concrete: `~` expanded, and anything still relative resolved against
 * [home] rather than the working directory.
 *
 * The working directory is the wrong base for every path a config names. An MCP client picks it
 * (Claude Code uses the project directory), so `logging.file: proxy.log` would otherwise scatter a
 * log file across every project the proxy is started from, and `oauth.tokenDir: tokens` would hide
 * credentials somewhere different each run. Resolving against the home instead is what makes
 * `$MCP_PROXY_HOME` relocate the whole installation. An absolute path is always left alone.
 */
internal fun resolveUnderHome(path: String, home: Path): Path =
    expandUserHome(path).let { if (it.isAbsolute) it else home.resolve(it) }
