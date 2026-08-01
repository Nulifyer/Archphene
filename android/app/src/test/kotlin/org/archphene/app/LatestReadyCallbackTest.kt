package org.archphene.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReadyCallbackTest {
    @Test
    fun repeatedRegistrationsRetainOnlyLatestObserver() {
        val callbacks = LatestReadyCallback<Int>()
        val delivered = mutableListOf<Int>()
        repeat(100) { generation ->
            callbacks.register { delivered.add(generation) }
        }
        assertEquals(1, callbacks.pendingCount())
        assertTrue(callbacks.take()!!.deliver(7))
        assertEquals(listOf(99), delivered)
        assertNull(callbacks.take())
    }

    @Test
    fun cancellationSuppressesPostedAndDuplicateDelivery() {
        val delivered = mutableListOf<Int>()
        val callbacks = LatestReadyCallback<Int>()
        val cancelled = callbacks.register(delivered::add)
        val posted = callbacks.take()!!
        cancelled.cancel()
        assertFalse(posted.deliver(1))
        val active = callbacks.register(delivered::add)
        assertTrue(callbacks.take() === active)
        assertTrue(active.deliver(2))
        assertFalse(active.deliver(3))
        assertEquals(listOf(2), delivered)
    }

    @Test
    fun replacementAfterDrainInvalidatesAlreadyPostedObserver() {
        val delivered = mutableListOf<Int>()
        val callbacks = LatestReadyCallback<Int>()
        callbacks.register { delivered.add(1) }
        val posted = callbacks.take()!!
        val latest = callbacks.register { delivered.add(2) }
        assertFalse(posted.deliver(0))
        assertTrue(latest.deliver(0))
        assertEquals(listOf(2), delivered)
    }

    @Test
    fun cancellationUnlinksPendingObserver() {
        val callbacks = LatestReadyCallback<Int>()
        val registration = callbacks.register {}
        registration.cancel()
        assertEquals(0, callbacks.pendingCount())
        assertNull(callbacks.take())
    }
}
