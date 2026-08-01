package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCallbackQueueTest {
    @Test
    fun capacityRejectsOverflowAndSchedulesOnce() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val queue =
            BoundedCallbackQueue(
                capacity = 3,
                schedule = { scheduled.addLast(it); true },
                consume = consumed::add,
                discard = discarded::add,
            )

        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))
        assertTrue(queue.offer(3))
        assertFalse(queue.offer(4))
        assertEquals(1, scheduled.size)
        assertEquals(listOf(4), discarded)

        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        assertEquals(listOf(1, 2, 3), consumed)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun clearAndCloseDiscardPendingValues() {
        val scheduled = ArrayDeque<Runnable>()
        val discarded = mutableListOf<String>()
        val queue =
            BoundedCallbackQueue(
                capacity = 2,
                schedule = { scheduled.addLast(it); true },
                consume = { _: String -> },
                discard = discarded::add,
            )
        assertTrue(queue.offer("clear"))
        queue.clear()
        assertEquals(listOf("clear"), discarded)
        assertTrue(queue.offer("close"))
        queue.close()
        assertEquals(listOf("clear", "close"), discarded)
        assertFalse(queue.offer("late"))
        assertEquals(listOf("clear", "close", "late"), discarded)
    }

    @Test
    fun schedulingFailureDiscardsUnscheduledWork() {
        val discarded = mutableListOf<Int>()
        val queue =
            BoundedCallbackQueue(
                capacity = 2,
                schedule = { false },
                consume = { _: Int -> },
                discard = discarded::add,
            )
        assertFalse(queue.offer(1))
        assertEquals(listOf(1), discarded)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun clearInvalidatesAlreadyPostedDrain() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<String>()
        val queue =
            BoundedCallbackQueue(
                capacity = 2,
                schedule = { scheduled.addLast(it); true },
                consume = consumed::add,
            )
        assertTrue(queue.offer("stale"))
        val staleDrain = scheduled.removeFirst()
        queue.clear()
        assertTrue(queue.offer("current"))
        val currentDrain = scheduled.removeFirst()

        staleDrain.run()
        assertEquals(emptyList<String>(), consumed)
        currentDrain.run()
        assertEquals(listOf("current"), consumed)
    }

    @Test
    fun rejectedAndAcceptedThenClearedValuesUseDistinctCleanup() {
        val scheduled = ArrayDeque<Runnable>()
        val discarded = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
        val queue =
            BoundedCallbackQueue(
                capacity = 1,
                schedule = { scheduled.addLast(it); true },
                consume = { _: Int -> },
                discard = discarded::add,
                reject = rejected::add,
            )
        assertTrue(queue.offer(1))
        assertFalse(queue.offer(2))
        queue.clear()
        assertEquals(listOf(1), discarded)
        assertEquals(listOf(2), rejected)
    }

    @Test
    fun pauseDiscardsPendingWorkAndRejectsUntilResume() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
        val queue =
            BoundedCallbackQueue(
                capacity = 2,
                schedule = { scheduled.addLast(it); true },
                consume = consumed::add,
                discard = discarded::add,
                reject = rejected::add,
            )
        assertTrue(queue.offer(1))
        val staleDrain = scheduled.removeFirst()
        queue.pause()
        assertFalse(queue.offer(2))
        assertEquals(listOf(1), discarded)
        assertEquals(listOf(2), rejected)
        queue.resume()
        assertTrue(queue.offer(3))
        val currentDrain = scheduled.removeFirst()
        staleDrain.run()
        assertEquals(emptyList<Int>(), consumed)
        currentDrain.run()
        assertEquals(listOf(3), consumed)
    }

    @Test
    fun overflowCanAppendOneConservativeRecoveryAfterCapacityReturns() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<Int>()
        var overflow = false
        var recoveryQueued = false
        lateinit var queue: BoundedCallbackQueue<Int>
        queue =
            BoundedCallbackQueue(
                capacity = 2,
                schedule = { scheduled.addLast(it); true },
                consume = { value ->
                    consumed.add(value)
                    if (value == -1) recoveryQueued = false
                    if (value == 1 || value == 4) {
                        assertTrue(queue.offer(value + 3))
                        assertFalse(queue.offer(value + 4))
                        overflow = true
                    }
                    if (overflow && !recoveryQueued) {
                        overflow = false
                        recoveryQueued = true
                        if (!queue.offer(-1)) {
                            overflow = true
                            recoveryQueued = false
                        }
                    }
                },
            )
        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))
        assertFalse(queue.offer(3))
        overflow = true
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        assertEquals(listOf(1, 2, 4, -1, 7, -1), consumed)
        assertEquals(0, queue.pendingCount())
    }
}
