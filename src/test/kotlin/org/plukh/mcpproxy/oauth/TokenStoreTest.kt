package org.plukh.mcpproxy.oauth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenStoreTest {

    private val dir: Path = Files.createTempDirectory("mcp-proxy-tokens")
    private val store = TokenStore(dir)

    private fun tokens(resource: String = "https://mcp.example.com/mcp") = StoredTokens(
        accessToken = "at-1",
        refreshToken = "rt-1",
        expiresAtEpochSeconds = 1_700_000_000,
        resource = resource,
        issuer = "https://auth.example.com",
        obtainedAtEpochSeconds = 1_699_999_000,
    )

    @Test
    fun `tokens round-trip through disk`() {
        store.saveTokens("https://mcp.example.com/mcp", tokens())
        assertEquals(tokens(), store.loadTokens("https://mcp.example.com/mcp"))
    }

    @Test
    fun `missing and corrupt files read as null`() {
        assertNull(store.loadTokens("https://nothing.example.com/mcp"))

        val file = store.tokensFile("https://corrupt.example.com/mcp")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ not json")
        assertNull(store.loadTokens("https://corrupt.example.com/mcp"))
    }

    @Test
    fun `equivalent urls share a file, distinct urls do not`() {
        // Same server through canonicalization - one identity.
        assertEquals(
            store.tokensFile("https://mcp.example.com/mcp"),
            store.tokensFile("HTTPS://MCP.EXAMPLE.COM:443/mcp/"),
        )
        // Same host, different path - different servers, and the file names must not collide.
        assertNotEquals(
            store.tokensFile("https://mcp.example.com/mcp"),
            store.tokensFile("https://mcp.example.com/other"),
        )
    }

    @Test
    fun `save leaves no temp file behind`() {
        store.saveTokens("https://mcp.example.com/mcp", tokens())
        store.saveTokens("https://mcp.example.com/mcp", tokens().copy(accessToken = "at-2"))

        assertTrue(dir.listDirectoryEntries().none { it.name.endsWith(".tmp") })
        assertEquals("at-2", store.loadTokens("https://mcp.example.com/mcp")!!.accessToken)
    }

    /**
     * Regression: refresh rotation kills the old token the moment the new one is used, so a save
     * that could be seen half-written after a crash would strand the grant. Atomicity here means the
     * final path never holds partial content - which an atomic move guarantees and a direct write
     * does not.
     */
    @Test
    fun `a failed write never corrupts the existing file and leaves no temp behind`() {
        val good = "https://mcp.example.com/mcp"
        val doomed = "https://other.example.com/mcp"
        store.saveTokens(good, tokens())

        // Sabotage the destination rather than the temp: a non-empty directory in the target's place
        // cannot be replaced, so the move fails after the temp has been written. (Sabotaging the temp
        // itself is no longer possible - and that is the point: its name is unique per write.)
        val blocked = store.tokensFile(doomed)
        Files.createDirectories(blocked)
        Files.createFile(blocked.resolve("blocker"))

        runCatching { store.saveTokens(doomed, tokens(resource = doomed)) }

        assertEquals("at-1", store.loadTokens(good)!!.accessToken)
        assertTrue(dir.listDirectoryEntries().none { it.name.endsWith(".tmp") }, "the failed write leaked its temp file")
    }

    /**
     * Regression: the temp file used to be a fixed `<file>.tmp` sibling guarded by a per-instance
     * lock, which two savers of the same upstream could not share. Two `TokenStore` instances stand
     * in for the two *processes* the design expects - a `--login` in another terminal is documented
     * as rescuing a live session - and they collided on that one name: one publishing the other's
     * half-written content, or the loser's move failing outright, which the refresh path then treats
     * as transient and retries with an already-consumed rotating refresh token.
     */
    @Test
    fun `concurrent savers never collide on a shared temp file`() {
        val url = "https://mcp.example.com/mcp"
        val stores = List(4) { TokenStore(dir) }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        // Enough contention to expose a shared temp name, far short of hammering one destination
        // for a second - the real workload is two processes saving occasionally, and a test that
        // sustains worst-case contention only measures how long the retry budget is.
        val threads = stores.mapIndexed { i, s ->
            Thread {
                repeat(40) { n ->
                    runCatching { s.saveTokens(url, tokens().copy(accessToken = "at-$i-$n")) }
                        .onFailure(failures::add)
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(60_000) }

        assertTrue(failures.isEmpty(), "concurrent saves failed: ${failures.take(3).map { it.toString() }}")
        // Whoever wrote last, the file is complete and parseable - never a half-written document.
        assertNotNull(store.loadTokens(url))
        assertTrue(dir.listDirectoryEntries().none { it.name.endsWith(".tmp") }, "temp files leaked")
    }

    /**
     * A temp left by an interrupted write holds a full access/refresh pair in clear. With a fixed
     * temp name the next write overwrote it; with a private name per write nothing would ever have
     * removed it, and `--logout` would report success while a copy of the credential stayed on disk.
     */
    @Test
    fun `logout deletes temp copies of the token, not just the tidy filename`() {
        val url = "https://mcp.example.com/mcp"
        store.saveTokens(url, tokens())
        val leaked = store.tokensFile(url).resolveSibling(store.tokensFile(url).fileName.toString() + "8675309.tmp")
        Files.writeString(leaked, """{"accessToken":"at-leaked"}""")

        assertTrue(store.deleteTokens(url))

        assertFalse(Files.exists(leaked), "logout left a temp copy of the credential behind")
        assertTrue(dir.listDirectoryEntries().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a stale temp is swept by the next write, a fresh one is left alone`() {
        val url = "https://mcp.example.com/mcp"
        val name = store.tokensFile(url).fileName.toString()
        val stale = store.tokensFile(url).resolveSibling(name + "1111.tmp")
        val inFlight = store.tokensFile(url).resolveSibling(name + "2222.tmp")
        Files.createDirectories(dir)
        Files.writeString(stale, "{}")
        Files.writeString(inFlight, "{}")
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(1))))

        store.saveTokens(url, tokens())

        assertFalse(Files.exists(stale), "the stale temp was not swept")
        // Another process could be mid-write on this one; only age tells them apart.
        assertTrue(Files.exists(inFlight), "a fresh temp was deleted out from under its writer")
    }

    @Test
    fun `registrations are keyed by issuer in their own directory`() {
        val registration = StoredRegistration(
            issuer = "https://auth.example.com",
            clientId = "abc",
            tokenEndpointAuthMethod = "none",
            redirectUris = listOf("http://127.0.0.1:1234/callback"),
            issuedAtEpochSeconds = 1_699_999_000,
        )
        store.saveRegistration(registration)

        assertEquals(registration, store.loadRegistration("https://auth.example.com"))
        assertTrue(store.registrationFile("https://auth.example.com").parent.name == "registrations")
    }

    @Test
    fun `delete is idempotent and reports whether anything existed`() {
        store.saveTokens("https://mcp.example.com/mcp", tokens())

        assertTrue(store.deleteTokens("https://mcp.example.com/mcp"))
        assertFalse(store.deleteTokens("https://mcp.example.com/mcp"))
        assertNull(store.loadTokens("https://mcp.example.com/mcp"))
    }
}
