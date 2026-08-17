package org.plukh.mcpproxy.live

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The live tier talks to real MCP servers, so it runs only with `-PliveTests` and each test still
 * skips itself when what it needs is absent. Being able to run the tier with only some of the
 * prerequisites configured is the point: a missing PAT should silently skip the GitHub tests, not
 * fail them, or nobody will run the tier at all.
 */
object Live {

    /**
     * A dedicated variable name rather than the conventional `GITHUB_TOKEN` / `GITHUB_PAT`, so a
     * token that happens to be in the environment for other reasons cannot be spent here by accident.
     *
     * Note for GitHub Enterprise Managed Users: PATs are disabled by default on EMU accounts, so this
     * needs a token from a personal identity.
     */
    const val GITHUB_PAT_VAR = "MCP_PROXY_TEST_GITHUB_PAT"

    const val HOSTED_GITHUB_URL = "https://api.githubcopilot.com/mcp/"
    const val CONTEXT7_URL = "https://mcp.context7.com/mcp"
    const val GITHUB_MCP_IMAGE = "ghcr.io/github/github-mcp-server"

    fun githubPat(): String {
        val pat = System.getenv(GITHUB_PAT_VAR)
        assumeTrue(!pat.isNullOrBlank(), "$GITHUB_PAT_VAR is not set")
        return pat
    }

    /** Skips unless a docker daemon actually answers - having the CLI on PATH is not enough. */
    fun assumeDocker() {
        val available = runCatching {
            val process = ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)

        assumeTrue(available, "no docker daemon available")
    }

    /** Live servers are slower and less predictable than a loopback socket; be generous. */
    const val TIMEOUT_MS = 120_000L

    /**
     * A read against a public repository, which needs no scope beyond what any valid token has.
     *
     * Listing tools only proves the server accepted the token at its own gate; calling one proves the
     * token was good enough for GitHub's API behind it, which is the half that was never tested.
     */
    const val SEARCH_REPOSITORIES_CALL =
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_repositories",""" +
            """"arguments":{"query":"repo:github/github-mcp-server"}}}"""
}
