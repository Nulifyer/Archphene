package org.archphene.app.performance

import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in counters for debug device performance gates.
 *
 * Release builds never enable these counters. Disabled hot paths pay one
 * volatile read and perform no allocation. Enabled paths use fixed atomics so
 * snapshots can be taken from a debug receiver without stopping compositor,
 * PTY, or UI threads.
 */
internal object PerformanceMetrics {
    @Volatile
    private var enabled = false

    private val terminalJniCalls = AtomicLong()
    private val terminalDirectInputBytes = AtomicLong()
    private val terminalDirectOutputBytes = AtomicLong()
    private val compositorJniCalls = AtomicLong()
    private val compositorDispatchJniCalls = AtomicLong()
    private val compositorInputJniCalls = AtomicLong()
    private val compositorSnapshotJniCalls = AtomicLong()
    private val compositorDirectInputBytes = AtomicLong()
    private val compositorDirectOutputBytes = AtomicLong()
    private val jniArrayCopyBytes = AtomicLong()
    private val kotlinCopyBytes = AtomicLong()
    private val terminalKotlinCopyBytes = AtomicLong()
    private val compositorKotlinCopyBytes = AtomicLong()
    private val terminalPendingInputMillis = AtomicLong()
    private val terminalLatencySamples = AtomicLong()
    private val terminalLatencyTotalMillis = AtomicLong()
    private val terminalLatencyMaximumMillis = AtomicLong()
    private val terminalLatencyLastMillis = AtomicLong()
    private val launcherPendingInputMillis = AtomicLong()
    private val launcherLatencySamples = AtomicLong()
    private val launcherLatencyTotalMillis = AtomicLong()
    private val launcherLatencyMaximumMillis = AtomicLong()
    private val launcherLatencyLastMillis = AtomicLong()

    fun resetAndEnable() {
        terminalJniCalls.set(0)
        terminalDirectInputBytes.set(0)
        terminalDirectOutputBytes.set(0)
        compositorJniCalls.set(0)
        compositorDispatchJniCalls.set(0)
        compositorInputJniCalls.set(0)
        compositorSnapshotJniCalls.set(0)
        compositorDirectInputBytes.set(0)
        compositorDirectOutputBytes.set(0)
        jniArrayCopyBytes.set(0)
        kotlinCopyBytes.set(0)
        terminalKotlinCopyBytes.set(0)
        compositorKotlinCopyBytes.set(0)
        terminalPendingInputMillis.set(0)
        terminalLatencySamples.set(0)
        terminalLatencyTotalMillis.set(0)
        terminalLatencyMaximumMillis.set(0)
        terminalLatencyLastMillis.set(0)
        launcherPendingInputMillis.set(0)
        launcherLatencySamples.set(0)
        launcherLatencyTotalMillis.set(0)
        launcherLatencyMaximumMillis.set(0)
        launcherLatencyLastMillis.set(0)
        enabled = true
    }

    fun disableAndSnapshot(): PerformanceSnapshot {
        enabled = false
        return PerformanceSnapshot(
            terminalJniCalls = terminalJniCalls.get(),
            terminalDirectInputBytes = terminalDirectInputBytes.get(),
            terminalDirectOutputBytes = terminalDirectOutputBytes.get(),
            compositorJniCalls = compositorJniCalls.get(),
            compositorDispatchJniCalls = compositorDispatchJniCalls.get(),
            compositorInputJniCalls = compositorInputJniCalls.get(),
            compositorSnapshotJniCalls = compositorSnapshotJniCalls.get(),
            compositorDirectInputBytes = compositorDirectInputBytes.get(),
            compositorDirectOutputBytes = compositorDirectOutputBytes.get(),
            jniArrayCopyBytes = jniArrayCopyBytes.get(),
            kotlinCopyBytes = kotlinCopyBytes.get(),
            terminalKotlinCopyBytes = terminalKotlinCopyBytes.get(),
            compositorKotlinCopyBytes = compositorKotlinCopyBytes.get(),
            terminalLatencySamples = terminalLatencySamples.get(),
            terminalLatencyTotalMillis = terminalLatencyTotalMillis.get(),
            terminalLatencyMaximumMillis = terminalLatencyMaximumMillis.get(),
            terminalLatencyLastMillis = terminalLatencyLastMillis.get(),
            launcherLatencySamples = launcherLatencySamples.get(),
            launcherLatencyTotalMillis = launcherLatencyTotalMillis.get(),
            launcherLatencyMaximumMillis = launcherLatencyMaximumMillis.get(),
            launcherLatencyLastMillis = launcherLatencyLastMillis.get(),
        )
    }

    fun recordTerminalJni(
        directInputBytes: Int = 0,
        directOutputBytes: Int = 0,
    ) {
        if (!enabled) {
            return
        }
        terminalJniCalls.incrementAndGet()
        addNonnegative(terminalDirectInputBytes, directInputBytes)
        addNonnegative(terminalDirectOutputBytes, directOutputBytes)
    }

    fun recordCompositorJni(
        directInputBytes: Int = 0,
        directOutputBytes: Int = 0,
        arrayCopyBytes: Int = 0,
        kind: Int = COMPOSITOR_CONTROL,
    ) {
        if (!enabled) {
            return
        }
        compositorJniCalls.incrementAndGet()
        when (kind) {
            COMPOSITOR_DISPATCH -> compositorDispatchJniCalls.incrementAndGet()
            COMPOSITOR_INPUT -> compositorInputJniCalls.incrementAndGet()
            COMPOSITOR_SNAPSHOT -> compositorSnapshotJniCalls.incrementAndGet()
        }
        addNonnegative(compositorDirectInputBytes, directInputBytes)
        addNonnegative(compositorDirectOutputBytes, directOutputBytes)
        addNonnegative(jniArrayCopyBytes, arrayCopyBytes)
    }

    fun recordTerminalKotlinCopy(bytes: Int) {
        if (enabled) {
            addNonnegative(kotlinCopyBytes, bytes)
            addNonnegative(terminalKotlinCopyBytes, bytes)
        }
    }

    fun recordCompositorKotlinCopy(bytes: Int) {
        if (enabled) {
            addNonnegative(kotlinCopyBytes, bytes)
            addNonnegative(compositorKotlinCopyBytes, bytes)
        }
    }

    fun noteTerminalInput(eventTimeMillis: Long) {
        if (enabled && eventTimeMillis > 0) {
            terminalPendingInputMillis.set(eventTimeMillis)
        }
    }

    fun noteTerminalFrame(frameTimeMillis: Long) {
        if (enabled) {
            recordLatency(
                terminalPendingInputMillis,
                terminalLatencySamples,
                terminalLatencyTotalMillis,
                terminalLatencyMaximumMillis,
                terminalLatencyLastMillis,
                frameTimeMillis,
            )
        }
    }

    fun noteLauncherInput(eventTimeMillis: Long) {
        if (enabled && eventTimeMillis > 0) {
            launcherPendingInputMillis.set(eventTimeMillis)
        }
    }

    fun noteLauncherFrame(frameTimeMillis: Long) {
        if (enabled) {
            recordLatency(
                launcherPendingInputMillis,
                launcherLatencySamples,
                launcherLatencyTotalMillis,
                launcherLatencyMaximumMillis,
                launcherLatencyLastMillis,
                frameTimeMillis,
            )
        }
    }

    private fun addNonnegative(
        counter: AtomicLong,
        value: Int,
    ) {
        if (value > 0) {
            counter.addAndGet(value.toLong())
        }
    }

    private fun recordLatency(
        pending: AtomicLong,
        samples: AtomicLong,
        total: AtomicLong,
        maximum: AtomicLong,
        last: AtomicLong,
        frameTimeMillis: Long,
    ) {
        val inputTimeMillis = pending.getAndSet(0)
        if (inputTimeMillis == 0L) {
            return
        }
        val latency = frameTimeMillis - inputTimeMillis
        if (latency !in 0..MAX_LATENCY_MILLIS) {
            return
        }
        samples.incrementAndGet()
        total.addAndGet(latency)
        last.set(latency)
        var currentMaximum = maximum.get()
        while (
            latency > currentMaximum &&
            !maximum.compareAndSet(currentMaximum, latency)
        ) {
            currentMaximum = maximum.get()
        }
    }

    private const val MAX_LATENCY_MILLIS = 10_000L
    const val COMPOSITOR_CONTROL = 0
    const val COMPOSITOR_DISPATCH = 1
    const val COMPOSITOR_INPUT = 2
    const val COMPOSITOR_SNAPSHOT = 3
}

internal data class PerformanceSnapshot(
    val terminalJniCalls: Long,
    val terminalDirectInputBytes: Long,
    val terminalDirectOutputBytes: Long,
    val compositorJniCalls: Long,
    val compositorDispatchJniCalls: Long,
    val compositorInputJniCalls: Long,
    val compositorSnapshotJniCalls: Long,
    val compositorDirectInputBytes: Long,
    val compositorDirectOutputBytes: Long,
    val jniArrayCopyBytes: Long,
    val kotlinCopyBytes: Long,
    val terminalKotlinCopyBytes: Long,
    val compositorKotlinCopyBytes: Long,
    val terminalLatencySamples: Long,
    val terminalLatencyTotalMillis: Long,
    val terminalLatencyMaximumMillis: Long,
    val terminalLatencyLastMillis: Long,
    val launcherLatencySamples: Long,
    val launcherLatencyTotalMillis: Long,
    val launcherLatencyMaximumMillis: Long,
    val launcherLatencyLastMillis: Long,
)
