package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestDispatchSlotTest {
    @Test
    fun burstRetainsOnePostedDrainAndLatestValue() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<Int>()
        val slot = slot(scheduled, consumed)
        repeat(100) { value -> assertTrue(slot.offer(value)) }
        assertEquals(1, scheduled.size)
        assertEquals(1, slot.pendingCount())
        scheduled.removeFirst().run()
        assertEquals(listOf(99), consumed)
        assertEquals(0, slot.pendingCount())
    }

    @Test
    fun replacementMovesLatestDrainBehindExistingWork() {
        val scheduled = ArrayDeque<Runnable>()
        val order = mutableListOf<String>()
        var canonical = 0
        val slot =
            LatestDispatchSlot<Int>(
                schedule = { scheduled.addLast(it); true },
                cancel = scheduled::remove,
                consume = {
                    canonical = it
                    order.add("clipboard:$it")
                },
            )
        assertTrue(slot.offer(1))
        scheduled.addLast {
            canonical = -1
            order.add("barrier")
        }
        assertTrue(slot.offer(2))
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        assertEquals(listOf("barrier", "clipboard:2"), order)
        assertEquals(2, canonical)
    }

    @Test
    fun failedScheduleRetainsNothingAndAllowsRetry() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<String>()
        var reject = true
        val slot =
            LatestDispatchSlot<String>(
                schedule = {
                    if (reject) false else scheduled.addLast(it).let { true }
                },
                cancel = scheduled::remove,
                consume = consumed::add,
            )
        assertFalse(slot.offer("failed"))
        assertEquals(0, slot.pendingCount())
        reject = false
        assertTrue(slot.offer("accepted"))
        scheduled.removeFirst().run()
        assertEquals(listOf("accepted"), consumed)
    }

    @Test
    fun failedReplacementPreservesPreviouslyAcceptedDrain() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<String>()
        var reject = false
        val slot =
            LatestDispatchSlot<String>(
                schedule = {
                    if (reject) false else scheduled.addLast(it).let { true }
                },
                cancel = scheduled::remove,
                consume = consumed::add,
            )
        assertTrue(slot.offer("first"))
        reject = true
        assertFalse(slot.offer("rejected"))
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().run()
        assertEquals(listOf("first"), consumed)
    }

    @Test
    fun closeCancelsDrainAndRejectsLateValues() {
        val scheduled = ArrayDeque<Runnable>()
        val consumed = mutableListOf<String>()
        val slot = slot(scheduled, consumed)
        assertTrue(slot.offer("close"))
        slot.close()
        assertTrue(scheduled.isEmpty())
        assertFalse(slot.offer("late"))
        assertTrue(consumed.isEmpty())
    }

    @Test
    fun mergeTransfersRetainedStateAndUsesDistinctDisposalPaths() {
        data class Value(val current: Int, val retained: Int)

        val scheduled = ArrayDeque<Runnable>()
        val replaced = mutableListOf<Value>()
        val cleared = mutableListOf<Value>()
        val slot =
            LatestDispatchSlot<Value>(
                schedule = { scheduled.addLast(it); true },
                cancel = scheduled::remove,
                consume = {},
                merge = { previous, next -> next.copy(retained = previous.retained) },
                discardReplaced = replaced::add,
                discardCleared = cleared::add,
            )
        assertTrue(slot.offer(Value(1, 10)))
        assertTrue(slot.offer(Value(2, 20)))
        assertEquals(listOf(Value(1, 10)), replaced)
        slot.clear()
        assertEquals(listOf(Value(2, 10)), cleared)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun replacementBurstReleasesEverySupersededAndLifecycleSurfaceOnce() {
        data class Attachment(val current: Int, val releaseBefore: Int)

        val scheduled = ArrayDeque<Runnable>()
        val released = mutableListOf<Int>()
        val slot =
            LatestDispatchSlot<Attachment>(
                schedule = { scheduled.addLast(it); true },
                cancel = scheduled::remove,
                consume = {},
                merge = { previous, next ->
                    next.copy(releaseBefore = previous.releaseBefore)
                },
                discardReplaced = { released.add(it.current) },
                discardCleared = { released.add(it.releaseBefore) },
            )
        for (surface in 1..100) {
            assertTrue(slot.offer(Attachment(surface, surface - 1)))
        }
        slot.clear()
        released.add(100)
        assertEquals((0..100).toList(), released.sorted())
        assertEquals(101, released.toSet().size)
    }

    private fun <T : Any> slot(
        scheduled: ArrayDeque<Runnable>,
        consumed: MutableList<T>,
    ) =
        LatestDispatchSlot<T>(
            schedule = { scheduled.addLast(it); true },
            cancel = scheduled::remove,
            consume = consumed::add,
        )
}
