package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestCallbackSlotTest {
    @Test
    fun burstRetainsOnlyLatestValueAndOneSchedule() {
        val slot = LatestCallbackSlot<Int>()
        var schedules = 0
        repeat(100) { value ->
            assertTrue(slot.offer(value) { schedules++; true })
        }
        assertEquals(1, schedules)
        assertEquals(1, slot.pendingCount())
        assertEquals(99, slot.take())
        assertEquals(0, slot.pendingCount())
        assertNull(slot.take())
        assertTrue(slot.offer(100) { schedules++; true })
        assertEquals(2, schedules)
    }

    @Test
    fun replacementAndClearDiscardSupersededPayloads() {
        val discarded = mutableListOf<String>()
        val slot = LatestCallbackSlot<String>(discarded::add)
        assertTrue(slot.offer("first") { true })
        assertTrue(slot.offer("second") { error("already scheduled") })
        assertEquals(listOf("first"), discarded)
        slot.clear()
        assertEquals(listOf("first", "second"), discarded)
        assertEquals(0, slot.pendingCount())
        assertTrue(slot.offer("third") { true })
    }

    @Test
    fun schedulingFailureDiscardsValueWithoutExposingPendingWork() {
        val discarded = mutableListOf<String>()
        val slot = LatestCallbackSlot<String>(discarded::add)
        assertFalse(slot.offer("failed") { false })
        assertEquals(listOf("failed"), discarded)
        assertEquals(0, slot.pendingCount())
        assertTrue(slot.offer("accepted") { true })
    }

    @Test
    fun closeRejectsAndDiscardsConcurrentFutureValues() {
        val discarded = mutableListOf<String>()
        val slot = LatestCallbackSlot<String>(discarded::add)
        assertTrue(slot.offer("pending") { true })
        slot.close()
        assertFalse(slot.offer("late") { error("closed slot must not schedule") })
        assertEquals(listOf("pending", "late"), discarded)
    }

    @Test
    fun mergePreservesActivationEdgeWhileKeepingLatestState() {
        data class State(val active: Boolean, val revision: Int, val restart: Boolean)

        val slot =
            LatestCallbackSlot<State>(
                merge = { previous, next ->
                    next.copy(restart = previous.restart || (!previous.active && next.active))
                },
            )
        assertTrue(slot.offer(State(active = false, revision = 1, restart = false)) { true })
        assertTrue(
            slot.offer(State(active = true, revision = 2, restart = false)) {
                error("already scheduled")
            },
        )
        assertEquals(State(active = true, revision = 2, restart = true), slot.take())
    }

    @Test
    fun mergePreservesIntermediateDeactivationCleanup() {
        data class State(val active: Boolean, val revision: Int, val deactivate: Boolean)

        val slot =
            LatestCallbackSlot<State>(
                merge = { previous, next ->
                    next.copy(
                        deactivate =
                            previous.deactivate || (previous.active && !next.active),
                    )
                },
            )
        assertTrue(slot.offer(State(active = true, revision = 1, deactivate = false)) { true })
        assertTrue(
            slot.offer(State(active = false, revision = 2, deactivate = false)) {
                error("already scheduled")
            },
        )
        assertTrue(
            slot.offer(State(active = true, revision = 3, deactivate = false)) {
                error("already scheduled")
            },
        )
        assertEquals(State(active = true, revision = 3, deactivate = true), slot.take())
    }
}
