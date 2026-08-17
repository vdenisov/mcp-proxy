package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.plukh.mcpproxy.ExitCodes
import org.plukh.mcpproxy.Stdio
import org.plukh.mcpproxy.buildHttpUpstream
import org.plukh.mcpproxy.config.ProxyConfig
import org.plukh.mcpproxy.redactSecret
import org.plukh.mcpproxy.tokenDir

private val log = KotlinLogging.logger {}

/**
 * `--login` / `--logout`: the explicit token lifecycle commands. The interactive flow also runs
 * lazily mid-serve, but a browser wait does not fit inside most clients' initialize timeout, so an
 * up-front login is the documented path.
 *
 * Output goes to the captured real stdout, like `--check` - the stdout lockdown has repointed
 * `System.out` at stderr, and these reports should be pipeable.
 */
object LoginMode {

    /** @param configArg the `CONFIG` argument as typed, echoed back in `--login` hints */
    fun run(config: ProxyConfig, configArg: String?): Int {
        val oauth = config.upstream.oauth ?: run {
            System.err.println("mcp-proxy: --login requires an 'oauth:' block under upstream in the config")
            return ExitCodes.CONFIG_ERROR
        }

        val out = StringBuilder()
        return try {
            val tokens = buildHttpUpstream(
                config,
                configArg = configArg,
                interactive = true,
                announceUrl = { url -> print("To authorize, open:\n  $url\n") },
            ).use { upstream ->
                out.appendLine("Logging in to ${config.upstream.url}")
                runBlocking { upstream.oauthSession!!.login() }
            }

            out.appendLine("issuer: ${tokens.issuer}")
            out.appendLine("access token: ${redactSecret(tokens.accessToken)}")
            tokens.expiresAtEpochSeconds?.let { expiresAt ->
                out.appendLine("expires in: ${expiresAt - tokens.obtainedAtEpochSeconds}s")
            }
            out.appendLine("refresh token: ${if (tokens.refreshToken != null) "present" else "absent"}")
            out.appendLine("stored under: ${tokenDir(oauth.tokenDir)}")
            print(out.toString())
            ExitCodes.OK
        } catch (e: Exception) {
            log.error(e) { "Login failed" }
            print(out.toString())
            print("FAILED: ${e.message}\n")
            ExitCodes.AUTH_FAILED
        }
    }

    fun logout(config: ProxyConfig, forgetClient: Boolean): Int {
        val oauth = config.upstream.oauth ?: run {
            System.err.println("mcp-proxy: --logout requires an 'oauth:' block under upstream in the config")
            return ExitCodes.CONFIG_ERROR
        }

        val store = TokenStore(tokenDir(oauth.tokenDir))
        val url = config.upstream.url!!

        // The issuer is read before the tokens are deleted - it is stored with them precisely so
        // logout needs no network round.
        val issuer = store.loadTokens(url)?.issuer
        val deletedTokens = store.deleteTokens(url)
        print(if (deletedTokens) "Deleted tokens for $url\n" else "No tokens stored for $url\n")

        if (forgetClient) {
            when {
                issuer == null -> print("No stored issuer; client registration left untouched\n")
                store.deleteRegistration(issuer) -> print("Deleted client registration for $issuer\n")
                else -> print("No client registration stored for $issuer\n")
            }
        }
        return ExitCodes.OK
    }

    private fun print(text: String) {
        Stdio.protocolOut.write(text.toByteArray())
        Stdio.protocolOut.flush()
    }
}
