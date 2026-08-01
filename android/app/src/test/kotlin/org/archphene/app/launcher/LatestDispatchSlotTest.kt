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
