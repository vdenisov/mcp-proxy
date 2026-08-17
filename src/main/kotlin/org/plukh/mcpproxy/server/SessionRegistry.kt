package org.plukh.mcpproxy.server

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/** One live MCP session: a client on one side, that upstream's own connection on the other. */
class Session(
    val id: String,
    val upstreamName: String,
    val downstream: HttpServerEndpoint,
) {
    @Volatile
    var lastActivity: Instant = Instant.EPOCH

    @Volatile
    var relayJob: Job? = null

    fun touch(clock: Clock) {
        lastActivity = clock.instant()
    }
}

/** Raised when a client asks for a session the server will not create. */
class TooManySessionsException(val limit: Int) : Exception("session limit of $limit reached")

/**
 * Every live session, keyed by the id the client quotes back in `Mcp-Session-Id`.
 *
 * One registry for the whole server rather than one per upstream, so an id is unique across the
 * process - but every lookup names the upstream it expects, because an id issued for one upstream
 * must not address another. The ids are unguessable, so this is defence in depth rather than the
 * only thing standing between two upstreams; it costs one comparison.
 */
class SessionRegistry(
    private val maxSessions: Int,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    /** 128 bits of unguessable id: the session is the only thing authenticating a client's requests. */
    fun newId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun register(session: Session): Session {
        if (sessions.size >= maxSessions) throw TooManySessionsException(maxSessions)
        session.touch(clock)
        sessions[session.id] = session
        return session
    }

    /** @return the session, or null when it is unknown, expired, or belongs to another upstream */
    fun get(id: String?, upstreamName: String): Session? =
        id?.let { sessions[it] }?.takeIf { it.upstreamName == upstreamName }?.also { it.touch(clock) }

    fun remove(id: String): Session? = sessions.remove(id)

    fun count(upstreamName: String): Int = sessions.values.count { it.upstreamName == upstreamName }

    fun all(): List<Session> = sessions.values.toList()

    /** Sessions untouched for longer than [idleTimeoutSeconds]; the caller closes them. */
    fun idleSince(idleTimeoutSeconds: Long): List<Session> {
        val cutoff = clock.instant().minusSeconds(idleTimeoutSeconds)
        return sessions.values.filter { it.lastActivity.isBefore(cutoff) }
    }
}
