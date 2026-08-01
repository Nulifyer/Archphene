package org.archphene.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchphenePreferencesExecutorTest {
    @Test
    fun executorKeepsOnlyLatestTaskForEachPendingKey() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val value = AtomicInteger()
        val executor = LatestTaskExecutor<String>(2, "PreferenceExecutorTest") { throw it }
        try {
            executor.execute("running") {
                running.countDown()
                release.await()
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            repeat(100) { next ->
                executor.execute("slider") {
                    value.set(next)
                    completed.countDown()
                }
            }
            executor.execute("toggle") { completed.countDown() }
            assertEquals(2, executor.pendingTaskCount())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(99, value.get())
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun executorRejectsMoreDistinctPendingKeysThanItsBound() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = LatestTaskExecutor<String>(1, "PreferenceExecutorBoundTest") { throw it }
        try {
            executor.execute("running") {
                running.countDown()
                release.await()
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            executor.execute("first") {}
            assertThrows(RejectedExecutionException::class.java) {
                executor.execute("second") {}
            }
            assertEquals(1, executor.pendingTaskCount())
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun replacementMovesKeyBehindOlderDistinctWork() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = mutableListOf<String>()
        val executor = LatestTaskExecutor<String>(2, "PreferenceExecutorOrderTest") {}
        try {
            executor.execute("running") {
                running.countDown()
                release.await()
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            executor.execute("appearance") { error("replaced") }
            executor.execute("toggle") {
                order.add("toggle")
                completed.countDown()
            }
            executor.execute("appearance") {
                order.add("appearance-latest")
                completed.countDown()
            }
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("toggle", "appearance-latest"), order)
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun taskAndFailureReporterExceptionsDoNotKillWorker() {
        val completed = CountDownLatch(1)
        val reported = CountDownLatch(1)
        val executor =
            LatestTaskExecutor<String>(2, "PreferenceExecutorFailureTest") {
                reported.countDown()
                throw IllegalStateException("report failed")
            }
        try {
            executor.execute("failure") { error("task failed") }
            executor.execute("success") { completed.countDown() }
            assertTrue(reported.await(2, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(executor.isWorkerAlive())
        } finally {
            executor.close()
        }
    }

    @Test
    fun closeInterruptsBlockedTaskAndStopsWorker() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = LatestTaskExecutor<String>(1, "PreferenceExecutorCloseTest") {}
        executor.execute("blocked") {
            running.countDown()
            release.await()
        }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        executor.close()
        assertFalse(executor.isWorkerAlive())
    }
}
