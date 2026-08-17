package org.plukh.mcpproxy.server

import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One replayable SSE event: the id the client may quote back, and the frame exactly as it went out. */
data class StoredEvent(val id: String, val encoded: String)

/**
 * Retains stream events so a client that reconnects with `Last-Event-ID` gets what it missed rather
 * than a hole.
 *
 * The contract is adapted from the MCP Kotlin SDK's `EventStore` (see `VENDORED.md`): append returns
 * the event id, replay is "everything after this id", and an id the store no longer knows is
 * reported as such so the caller can start a fresh stream instead of silently skipping events.
 */
interface EventStore {

    /** @return the id of the stored event, which the client may later send as `Last-Event-ID` */
    suspend fun append(streamId: String, encoded: String): String

    /**
     * @param afterEventId the client's `Last-Event-ID`, or null for "everything still retained"
     *
     * @return events after [afterEventId] in order, or **null** when that id is unknown - evicted,
     *   from another stream, or forged - which the caller must treat as "start a fresh stream"
     */
    suspend fun replayAfter(streamId: String, afterEventId: String?): List<StoredEvent>?

    /** Drops everything retained for a stream whose session has ended. */
    suspend fun forget(streamId: String)
}

/**
 * The default store: bounded by **memory**, not event count, because that is the resource that
 * actually runs out - one upstream emitting a few enormous frames and another emitting thousands of
 * tiny ones should not need different tuning.
 *
 * The budget counts characters of the encoded frames, which is what a compact-strings JVM retains
 * for the ASCII-dominant JSON these are. Oldest events go first, across all streams: a session that
 * has been quiet holds no claim on the budget over one that is busy.
 */
class InMemoryEventStore(private val maxChars: Long) : EventStore {

    private class Entry(val streamId: String, val id: String, val encoded: String)

    private val lock = Mutex()
    private val events = ArrayDeque<Entry>()
    private var retainedChars = 0L
    private var sequence = 0L

    /** Test seam: what the budget currently accounts for. */
    internal suspend fun retainedChars(): Long = lock.withLock { retainedChars }

    internal suspend fun size(): Int = lock.withLock { events.size }

    override suspend fun append(streamId: String, encoded: String): String = lock.withLock {
        val id = "e${sequence++}"
        events.addLast(Entry(streamId, id, encoded))
        retainedChars += encoded.length
        // Evict globally-oldest first. A single frame larger than the whole budget would empty the
        // store and still be retained - keeping it is better than retaining nothing at all.
        while (retainedChars > maxChars && events.size > 1) {
            val evicted = events.removeFirst()
            retainedChars -= evicted.encoded.length
        }
        id
    }

    override suspend fun replayAfter(streamId: String, afterEventId: String?): List<StoredEvent>? = lock.withLock {
        if (afterEventId == null) {
            return@withLock events.filter { it.streamId == streamId }.map { StoredEvent(it.id, it.encoded) }
        }
        // Unknown id: evicted, forged, or another stream's. Either way we cannot honestly say what
        // came after it, so the caller starts fresh rather than inventing continuity.
        if (events.none { it.id == afterEventId && it.streamId == streamId }) return@withLock null

        events.asSequence()
            .dropWhile { it.id != afterEventId }
            .drop(1)
            .filter { it.streamId == streamId }
            .map { StoredEvent(it.id, it.encoded) }
            .toList()
    }

    override suspend fun forget(streamId: String) = lock.withLock {
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.streamId == streamId) {
                retainedChars -= entry.encoded.length
                iterator.remove()
            }
        }
    }
}
