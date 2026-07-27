package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import org.archphene.app.performance.PerformanceMetrics

/**
 * Debug-only device gate for process-local hot-path and ART measurements.
 *
 * The explicit token prevents unrelated broadcasts from enabling counters in
 * an interactive debug session. No receiver or counter controls ship in the
 * release manifest.
 */
internal class PerformanceTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getStringExtra(EXTRA_TOKEN) != TOKEN) {
            Log.e(TAG, "Rejected performance probe")
            return
        }
        when (intent.action) {
            ACTION_RESET -> reset()
            ACTION_SNAPSHOT -> snapshot()
            else -> Log.e(TAG, "Rejected unknown performance operation")
        }
    }

    private fun reset() {
        synchronized(artBaseline) {
            for (index in ART_RUNTIME_KEYS.indices) {
                artBaseline[index] = runtimeStat(ART_RUNTIME_KEYS[index])
            }
            PerformanceMetrics.resetAndEnable()
        }
    }

    private fun snapshot() {
        val metrics = PerformanceMetrics.disableAndSnapshot()
        val artDeltas = LongArray(ART_RUNTIME_KEYS.size)
        synchronized(artBaseline) {
            for (index in ART_RUNTIME_KEYS.indices) {
                val current = runtimeStat(ART_RUNTIME_KEYS[index])
                val baseline = artBaseline[index]
                artDeltas[index] =
                    if (current >= 0 && baseline >= 0 && current >= baseline) {
                        current - baseline
                    } else {
                        UNSUPPORTED
                    }
            }
        }
        Log.i(
            TAG,
            "terminalCalls=${metrics.terminalJniCalls} " +
                "terminalDirectIn=${metrics.terminalDirectInputBytes} " +
                "terminalDirectOut=${metrics.terminalDirectOutputBytes} " +
                "compositorCalls=${metrics.compositorJniCalls} " +
                "compositorDispatchCalls=${metrics.compositorDispatchJniCalls} " +
                "compositorInputCalls=${metrics.compositorInputJniCalls} " +
                "compositorSnapshotCalls=${metrics.compositorSnapshotJniCalls} " +
                "compositorDirectIn=${metrics.compositorDirectInputBytes} " +
                "compositorDirectOut=${metrics.compositorDirectOutputBytes} " +
                "jniArrayCopy=${metrics.jniArrayCopyBytes} " +
                "kotlinCopy=${metrics.kotlinCopyBytes} " +
                "terminalKotlinCopy=${metrics.terminalKotlinCopyBytes} " +
                "compositorKotlinCopy=${metrics.compositorKotlinCopyBytes} " +
                "terminalLatencySamples=${metrics.terminalLatencySamples} " +
                "terminalLatencyTotalMs=${metrics.terminalLatencyTotalMillis} " +
                "terminalLatencyMaxMs=${metrics.terminalLatencyMaximumMillis} " +
                "terminalLatencyLastMs=${metrics.terminalLatencyLastMillis} " +
                "launcherLatencySamples=${metrics.launcherLatencySamples} " +
                "launcherLatencyTotalMs=${metrics.launcherLatencyTotalMillis} " +
                "launcherLatencyMaxMs=${metrics.launcherLatencyMaximumMillis} " +
                "launcherLatencyLastMs=${metrics.launcherLatencyLastMillis} " +
                "artBytesAllocated=${artDeltas[ART_BYTES_ALLOCATED]} " +
                "artObjectsAllocated=${artDeltas[ART_OBJECTS_ALLOCATED]} " +
                "artGcCount=${artDeltas[ART_GC_COUNT]} " +
                "artGcTimeMs=${artDeltas[ART_GC_TIME]} " +
                "artBlockingGcCount=${artDeltas[ART_BLOCKING_GC_COUNT]} " +
                "artBlockingGcTimeMs=${artDeltas[ART_BLOCKING_GC_TIME]}",
        )
    }

    private fun runtimeStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: UNSUPPORTED

    private companion object {
        private const val TAG = "ArchphenePerformanceProbe"
        private const val ACTION_RESET = "org.archphene.app.debug.action.RESET_PERFORMANCE"
        private const val ACTION_SNAPSHOT =
            "org.archphene.app.debug.action.SNAPSHOT_PERFORMANCE"
        private const val EXTRA_TOKEN = "token"
        private const val TOKEN = "performance-gate"
        private const val UNSUPPORTED = -1L

        private const val ART_BYTES_ALLOCATED = 0
        private const val ART_OBJECTS_ALLOCATED = 1
        private const val ART_GC_COUNT = 2
        private const val ART_GC_TIME = 3
        private const val ART_BLOCKING_GC_COUNT = 4
        private const val ART_BLOCKING_GC_TIME = 5

        private val ART_RUNTIME_KEYS =
            arrayOf(
                "art.gc.bytes-allocated",
                "art.gc.objects-allocated",
                "art.gc.gc-count",
                "art.gc.gc-time",
                "art.gc.blocking-gc-count",
                "art.gc.blocking-gc-time",
            )
        private val artBaseline = LongArray(ART_RUNTIME_KEYS.size) { UNSUPPORTED }
    }
}
