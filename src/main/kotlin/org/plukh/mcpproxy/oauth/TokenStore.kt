package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Plain-JSON persistence under the token directory (default `~/.mcp-proxy/tokens`).
 *
 * Tokens are keyed by the canonical upstream URL; registrations live in `registrations/` keyed by
 * the authorization server's issuer, because several MCP servers can share one AS and a registration
 * belongs to the AS, not to any single upstream.
 *
 * Writes go through a temp sibling plus an atomic move: refresh-token rotation means the previous
 * refresh token dies the moment the new one is used, so the new pair must be durably on disk before
 * anything can go wrong - a half-written token file after a crash would strand the whole grant.
 *
 * Plaintext by design for now, matching what MCP clients already do with `mcp.json`; hardening is
 * stage 4. File permissions are tightened to owner-only where the filesystem supports it.
 */
class TokenStore(private val dir: Path) {

    fun loadTokens(upstreamUrl: String): StoredTokens? = read(tokensFile(upstreamUrl))

    fun saveTokens(upstreamUrl: String, tokens: StoredTokens) = write(tokensFile(upstreamUrl), OAuthJson.encodeToString(StoredTokens.serializer(), tokens))

    /**
     * Deletes the token file *and* any temp copies of it left behind by an interrupted write, so
     * `--logout` really removes every on-disk copy of the credential rather than the one with the
     * tidy name.
     *
     * @return true if there was anything to delete
     */
    fun deleteTokens(upstreamUrl: String): Boolean {
        val file = tokensFile(upstreamUrl)
        val strays = sweepTemps(file, olderThan = null)
        return Files.deleteIfExists(file) || strays
    }

    fun loadRegistration(issuer: String): StoredRegistration? = read(registrationFile(issuer))

    fun saveRegistration(registration: StoredRegistration) =
        write(registrationFile(registration.issuer), OAuthJson.encodeToString(StoredRegistration.serializer(), registration))

    fun deleteRegistration(issuer: String): Boolean = Files.deleteIfExists(registrationFile(issuer))

    internal fun tokensFile(upstreamUrl: String): Path = dir.resolve(fileNameFor(canonicalResourceUri(upstreamUrl)))

    internal fun registrationFile(issuer: String): Path =
        dir.resolve("registrations").resolve(fileNameFor(canonicalResourceUri(issuer)))

    private inline fun <reified T> read(file: Path): T? {
        if (!Files.isReadable(file)) return null
        return try {
            OAuthJson.decodeFromString<T>(Files.readString(file))
        } catch (e: Exception) {
            // A corrupt file is recoverable - the flow just runs again - but silence would hide it.
            log.warn(e) { "Could not read ${file.fileName}, ignoring it" }
            null
        }
    }

    /**
     * A *unique* temp per write, not a fixed `<file>.tmp` sibling. Concurrent savers are expected and
     * span processes - a `--login` in another terminal is documented as rescuing a live session - so
     * a shared temp name lets one writer publish another's half-written content, or lets the losing
     * `ATOMIC_MOVE` fail with `NoSuchFileException`. That failure surfaces inside the refresh path's
     * transient-error branch, which retries with a refresh token the server has already consumed and
     * turns a successful rotation into a forced re-login. A per-write temp plus the atomic move makes
     * the last writer win cleanly, which no in-process lock could have achieved across processes.
     */
    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        // Private temps mean an interrupted write leaves a uniquely-named file behind instead of one
        // the next write would overwrite - and that file holds a full access/refresh pair in clear.
        // Sweep the stale ones on the way past; the age bound is what keeps a concurrent writer's
        // in-flight temp safe.
        sweepTemps(file, olderThan = STALE_TEMP_AGE)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(temp, content)
            restrictToOwner(temp)
            moveIntoPlace(temp, file)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    /**
     * Windows rejects an atomic replace with `AccessDeniedException` while another rename momentarily
     * holds the destination, so two savers of the same upstream fail there even with private temps -
     * measured, not theorised. The contention lasts microseconds, and the alternative is that a
     * perfectly good token file surfaces as a transient refresh failure and burns a rotating grant.
     */
    private fun moveIntoPlace(temp: Path, file: Path) {
        repeat(MOVE_ATTEMPTS) { attempt ->
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: java.nio.file.AccessDeniedException) {
                if (attempt == MOVE_ATTEMPTS - 1) throw e
                Thread.sleep(attempt + 1L)
            }
        }
    }

    /**
     * Deletes temp files belonging to [file] - `Files.createTempFile` names them `<file><random>.tmp`.
     *
     * @param olderThan only sweep temps last modified longer ago than this, so a write in flight
     *   (in this process or another) is never pulled out from under itself; null sweeps all of them
     *
     * @return whether anything was deleted
     */
    private fun sweepTemps(file: Path, olderThan: Duration?): Boolean {
        val prefix = file.fileName.toString()
        val cutoff = olderThan?.let { Instant.now().minus(it) }
        return runCatching {
            Files.list(file.parent).use { entries ->
                entries.filter { candidate ->
                    val name = candidate.fileName.toString()
                    name.startsWith(prefix) && name.endsWith(".tmp") &&
                        (cutoff == null || Files.getLastModifiedTime(candidate).toInstant().isBefore(cutoff))
                }.toList()
            }.count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) } > 0
        }.getOrDefault(false)
    }

    private fun restrictToOwner(file: Path) {
        // Best effort: POSIX only. On Windows the file inherits the profile directory's ACL, which
        // is already owner-scoped.
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {

        /** ~78 ms of retries in total. Exhausting it lands in the refresh path's transient branch,
         *  which is the failure this whole dance exists to avoid, so the budget is deliberately far
         *  above the microseconds real contention lasts. */
        private const val MOVE_ATTEMPTS = 12

        /** Old enough that no live write could still be holding it. */
        private val STALE_TEMP_AGE: Duration = Duration.ofMinutes(5)

        /**
         * `<host>-<16 hex of sha256(canonical url)>.json`. The host prefix is for humans browsing
         * the directory; the hash is the actual identity, so two upstreams on one host never collide
         * and no URL character needs escaping into a filename.
         */
        internal fun fileNameFor(canonicalUrl: String): String {
            val host = URI(canonicalUrl).host ?: "unknown"
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalUrl.toByteArray())
            val hash = digest.take(8).joinToString("") { "%02x".format(it) }
            return "$host-$hash.json"
        }
    }
}
