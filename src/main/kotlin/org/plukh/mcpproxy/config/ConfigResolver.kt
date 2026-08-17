package org.plukh.mcpproxy.config

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.plukh.mcpproxy.expandUserHome

/**
 * Turns the `CONFIG` command-line argument into a file to load, so a client entry can say `linear`
 * instead of carrying an absolute path.
 *
 * The split is by *shape*, and the security argument runs one way only:
 *
 * - **A bare name** (no path separator) resolves from the proxy home and nowhere else - never from
 *   the working directory. MCP clients spawn the proxy with a working directory the user does not
 *   choose (Claude Code uses the project directory), so a "CWD first, then home" rule would make a
 *   shorthand in a global client config mean something different per project, and a repository
 *   containing `linear.yaml` would shadow the real one. Since a config can name an arbitrary
 *   `upstream.command`, loading an attacker-supplied one means running an attacker-chosen process:
 *   opening a hostile repository would be enough. A bare name must mean exactly one thing.
 * - **A path-shaped argument** (`./linear.yaml`, `configs/linear.yaml`, `~/linear.yaml`, absolute)
 *   stays a filesystem path relative to the working directory. That is the escape hatch for a config
 *   that genuinely belongs to a checkout.
 *
 * Dots do not enter into it: `mcp-proxy linear.yaml` is a bare name and comes from the home. There is
 * no extension *parsing* anywhere here, only extension *appending* - `mcp.linear` is tried literally
 * first, then with `.yaml` and `.yml` glued on.
 */
internal object ConfigResolver {

    /**
     * True when the argument names a filesystem location rather than a config in the home.
     *
     * Beyond the separator rule, two shapes are filesystem paths despite carrying no separator:
     * a leading `~` (the whole point of which is to name a location under the user's home), and a
     * Windows drive-relative prefix like `C:linear.yaml` - probing the home for a file literally
     * named `C:linear.yaml` would only produce an [InvalidPathException].
     */
    internal fun isPathShaped(arg: String): Boolean =
        arg.contains('/') ||
            arg.contains('\\') ||
            arg.startsWith("~") ||
            (arg.length >= 2 && arg[0].isLetter() && arg[1] == ':')

    /** Home-relative candidates for a bare name, in probe order. Pure: touches no filesystem. */
    internal fun configCandidates(arg: String, home: Path): List<Path> =
        listOf(home.resolve(arg), home.resolve("$arg.yaml"), home.resolve("$arg.yml"))

    /**
     * Resolves the `CONFIG` argument to a path for [ConfigLoader.load].
     *
     * Existence is only checked for bare names, and with [isRegularFile] rather than a plain
     * existence test - a directory named `linear` must not shadow the `linear.yaml` beside it.
     * Path-shaped arguments are handed over unchecked so that `ConfigLoader` remains the single
     * owner of the "no such config file" message for them.
     *
     * @param isRegularFile existence predicate; injectable so resolution is testable without a filesystem
     *
     * @throws ConfigException if the argument is blank or unusable as a path, or if no candidate matches
     */
    internal fun resolve(
        arg: String,
        home: Path,
        isRegularFile: (Path) -> Boolean = { Files.isRegularFile(it) },
    ): Path {
        // Left alone, home.resolve("") is the home directory itself and the candidates are nonsense.
        if (arg.isBlank()) throw ConfigException("CONFIG must not be blank")

        try {
            if (isPathShaped(arg)) return expandUserHome(arg)

            val candidates = configCandidates(arg, home)
            return candidates.firstOrNull(isRegularFile) ?: throw ConfigException(
                "config not found: $arg; tried: " + candidates.joinToString(", ") { it.toAbsolutePath().toString() },
            )
        } catch (e: InvalidPathException) {
            // Reserved characters make Path operations throw, and a stack trace would break the
            // "one readable stderr line" contract every other config failure keeps.
            throw ConfigException("invalid config name or path: $arg (${e.message})", e)
        }
    }
}
