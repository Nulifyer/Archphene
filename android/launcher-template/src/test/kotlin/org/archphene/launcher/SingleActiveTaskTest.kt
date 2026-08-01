package org.archphene.launcher

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleActiveTaskTest {
    @Test
    fun burstRetainsOnlyOneActiveTask() {
        val task = SingleActiveTask()

        assertTrue(task.tryAcquire())
        repeat(100) { assertFalse(task.tryAcquire()) }
    }

    @Test
    fun releaseAllowsNextTask() {
        val task = SingleActiveTask()

        assertTrue(task.tryAcquire())
        task.release()
        assertTrue(task.tryAcquire())
    }

    @Test
    fun closeRejectsFutureTasksAfterActiveRelease() {
        val task = SingleActiveTask()

        assertTrue(task.tryAcquire())
        task.close()
        assertFalse(task.tryAcquire())
        task.release()
        assertFalse(task.tryAcquire())
    }

    @Test
    fun concurrentAcquisitionRetainsOneTask() {
        val task = SingleActiveTask()
        val ready = CountDownLatch(32)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(32)
        val release = CountDownLatch(1)
        val acquired = AtomicInteger(0)
        val workers =
            List(32) {
                Thread {
                    ready.countDown()
                    start.await()
                    if (task.tryAcquire()) {
                        acquired.incrementAndGet()
                        attempted.countDown()
                        release.await()
                        task.release()
                    } else {
                        attempted.countDown()
                    }
                }.apply { start() }
            }

        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertEquals(1, acquired.get())
        } finally {
            start.countDown()
            release.countDown()
            workers.forEach { worker -> worker.join(2_000) }
        }
        workers.forEach { worker -> assertFalse(worker.isAlive) }
    }

    @Test
    fun completionReleasesBeforeExactlyOneCallback() {
        val task = SingleActiveTask()
        assertTrue(task.tryAcquire())
        var callbackCount = 0
        val completion =
            SingleTaskCompletion(task) { outcome ->
                assertEquals(SingleTaskOutcome.FINISHED, outcome)
                assertTrue(task.tryAcquire())
                task.release()
                callbackCount++
            }

        assertTrue(completion.complete(SingleTaskOutcome.FINISHED))
        assertFalse(completion.complete(SingleTaskOutcome.FAILED))
        assertEquals(1, callbackCount)
    }

    @Test
    fun concurrentCompletionInvokesOneCallback() {
        val task = SingleActiveTask()
        assertTrue(task.tryAcquire())
        val callbackCount = AtomicInteger(0)
        val completion = SingleTaskCompletion(task) { callbackCount.incrementAndGet() }
        val start = CountDownLatch(1)
        val workers =
            List(32) { index ->
                Thread {
                    start.await()
                    completion.complete(
                        if (index == 0) SingleTaskOutcome.FINISHED else SingleTaskOutcome.FAILED,
                    )
                }.apply { start() }
            }

        start.countDown()
        workers.forEach { worker -> worker.join(2_000) }
        workers.forEach { worker -> assertFalse(worker.isAlive) }
        assertEquals(1, callbackCount.get())
        assertTrue(task.tryAcquire())
        task.release()
    }

    @Test
    fun cancellationBeforeCommitWinsTerminalOutcome() {
        val cancellation = PrintWriteCancellation()

        cancellation.cancel()

        assertEquals(
            SingleTaskOutcome.CANCELLED,
            cancellation.commit(SingleTaskOutcome.FINISHED),
        )
    }

    @Test
    fun cancellationAfterCommitCannotChangeTerminalOutcome() {
        val cancellation = PrintWriteCancellation()

        assertEquals(
            SingleTaskOutcome.FINISHED,
            cancellation.commit(SingleTaskOutcome.FINISHED),
        )
        cancellation.cancel()
        assertFalse(cancellation.cancelled)
    }

    @Test
    fun commitWaitsForCancellationCleanup() {
        val cancellation = PrintWriteCancellation()
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        cancellation.attachOutput(
            Closeable {
                closeStarted.countDown()
                allowClose.await(2, TimeUnit.SECONDS)
            },
        )
        val outcome = AtomicReference<SingleTaskOutcome>()
        val cancelWorker = Thread(cancellation::cancel).apply { start() }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        val commitWorker =
            Thread {
                outcome.set(cancellation.commit(SingleTaskOutcome.FINISHED))
            }.apply { start() }

        try {
            assertTrue(commitWorker.isAlive)
        } finally {
            allowClose.countDown()
            cancelWorker.join(2_000)
            commitWorker.join(2_000)
        }
        assertFalse(cancelWorker.isAlive)
        assertFalse(commitWorker.isAlive)
        assertEquals(SingleTaskOutcome.CANCELLED, outcome.get())
    }
}
