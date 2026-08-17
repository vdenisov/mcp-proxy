package org.plukh.mcpproxy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InMemoryEventStoreTest {

    private val roomy = 1_000_000L

    @Test
    fun `replay returns what followed an id, in order`() = runTest {
        val store = InMemoryEventStore(roomy)
        val first = store.append("s", "one")
        store.append("s", "two")
        store.append("s", "three")

        assertEquals(listOf("two", "three"), store.replayAfter("s", first)!!.map { it.encoded })
    }

    @Test
    fun `a null id replays everything still retained`() = runTest {
        val store = InMemoryEventStore(roomy)
        store.append("s", "one")
        store.append("s", "two")

        assertEquals(listOf("one", "two"), store.replayAfter("s", null)!!.map { it.encoded })
    }

    /** The signal that lets a stream start fresh rather than pretend it knows what was missed. */
    @Test
    fun `an unknown id is reported as unknown, not as an empty replay`() = runTest {
        val store = InMemoryEventStore(roomy)
        store.append("s", "one")

        assertNull(store.replayAfter("s", "e-forged"))
        assertTrue(store.replayAfter("s", null)!!.isNotEmpty(), "an empty list would mean 'nothing followed'")
    }

    @Test
    fun `streams do not see each other's events or ids`() = runTest {
        val store = InMemoryEventStore(roomy)
        val mine = store.append("mine", "a")
        store.append("theirs", "b")

        assertEquals(listOf("a"), store.replayAfter("mine", null)!!.map { it.encoded })
        assertEquals(listOf("b"), store.replayAfter("theirs", null)!!.map { it.encoded })
        // An id from another stream is not a position in this one.
        assertNull(store.replayAfter("theirs", mine))
    }

    // --- the budget ---

    @Test
    fun `the budget accounts for exactly the retained characters`() = runTest {
        val store = InMemoryEventStore(roomy)
        store.append("s", "12345")
        store.append("s", "678")

        assertEquals(8L, store.retainedChars())
    }

    @Test
    fun `oldest events are evicted once the budget is reached`() = runTest {
        val store = InMemoryEventStore(10)
        store.append("s", "aaaaa")
        store.append("s", "bbbbb")
        val third = store.append("s", "ccccc")

        assertTrue(store.retainedChars() <= 10, "budget exceeded: ${store.retainedChars()}")
        val retained = store.replayAfter("s", null)!!.map { it.encoded }
        assertEquals(listOf("bbbbb", "ccccc"), retained)
        // The evicted event's id is gone, so a client quoting it starts fresh.
        assertEquals(emptyList(), store.replayAfter("s", third)!!.map { it.encoded })
    }

    @Test
    fun `eviction spans streams, so a quiet stream holds no claim on the budget`() = runTest {
        val store = InMemoryEventStore(10)
        store.append("quiet", "aaaaa")
        store.append("busy", "bbbbb")
        store.append("busy", "ccccc")

        assertNull(store.replayAfter("quiet", null)?.firstOrNull()?.encoded)
        assertEquals(listOf("bbbbb", "ccccc"), store.replayAfter("busy", null)!!.map { it.encoded })
    }

    /** Retaining nothing would be worse than briefly exceeding the budget for one huge frame. */
    @Test
    fun `a single frame larger than the whole budget is still retained`() = runTest {
        val store = InMemoryEventStore(10)
        val huge = "x".repeat(50)

        store.append("s", huge)

        assertEquals(listOf(huge), store.replayAfter("s", null)!!.map { it.encoded })
    }

    @Test
    fun `forgetting a stream releases its budget and leaves others alone`() = runTest {
        val store = InMemoryEventStore(roomy)
        store.append("gone", "aaaaa")
        store.append("stays", "bb")

        store.forget("gone")

        assertEquals(2L, store.retainedChars())
        assertEquals(0, store.replayAfter("gone", null)!!.size)
        assertEquals(listOf("bb"), store.replayAfter("stays", null)!!.map { it.encoded })
    }
}
