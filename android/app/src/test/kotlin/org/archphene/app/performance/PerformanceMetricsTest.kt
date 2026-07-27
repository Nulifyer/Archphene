package org.archphene.app.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceMetricsTest {
    @Test
    fun enabledCountersTrackExactCallsBytesAndLatency() {
        PerformanceMetrics.resetAndEnable()

        PerformanceMetrics.recordTerminalJni(
            directInputBytes = 7,
            directOutputBytes = 11,
        )
        PerformanceMetrics.recordTerminalJni(directInputBytes = -1)
        PerformanceMetrics.recordCompositorJni(
            directInputBytes = 24,
            directOutputBytes = 31,
            arrayCopyBytes = 13,
            kind = PerformanceMetrics.COMPOSITOR_INPUT,
        )
        PerformanceMetrics.recordCompositorJni(
            kind = PerformanceMetrics.COMPOSITOR_DISPATCH,
        )
        PerformanceMetrics.recordCompositorJni(
            kind = PerformanceMetrics.COMPOSITOR_SNAPSHOT,
        )
        PerformanceMetrics.recordTerminalKotlinCopy(19)
        PerformanceMetrics.recordTerminalKotlinCopy(-1)
        PerformanceMetrics.recordCompositorKotlinCopy(23)
        PerformanceMetrics.noteTerminalInput(1_000)
        PerformanceMetrics.noteTerminalFrame(1_042)
        PerformanceMetrics.noteTerminalInput(2_000)
        PerformanceMetrics.noteTerminalFrame(2_018)
        PerformanceMetrics.noteLauncherInput(3_000)
        PerformanceMetrics.noteLauncherFrame(3_075)

        val snapshot = PerformanceMetrics.disableAndSnapshot()

        assertEquals(2, snapshot.terminalJniCalls)
        assertEquals(7, snapshot.terminalDirectInputBytes)
        assertEquals(11, snapshot.terminalDirectOutputBytes)
        assertEquals(3, snapshot.compositorJniCalls)
        assertEquals(1, snapshot.compositorDispatchJniCalls)
        assertEquals(1, snapshot.compositorInputJniCalls)
        assertEquals(1, snapshot.compositorSnapshotJniCalls)
        assertEquals(24, snapshot.compositorDirectInputBytes)
        assertEquals(31, snapshot.compositorDirectOutputBytes)
        assertEquals(13, snapshot.jniArrayCopyBytes)
        assertEquals(42, snapshot.kotlinCopyBytes)
        assertEquals(19, snapshot.terminalKotlinCopyBytes)
        assertEquals(23, snapshot.compositorKotlinCopyBytes)
        assertEquals(2, snapshot.terminalLatencySamples)
        assertEquals(60, snapshot.terminalLatencyTotalMillis)
        assertEquals(42, snapshot.terminalLatencyMaximumMillis)
        assertEquals(18, snapshot.terminalLatencyLastMillis)
        assertEquals(1, snapshot.launcherLatencySamples)
        assertEquals(75, snapshot.launcherLatencyTotalMillis)
        assertEquals(75, snapshot.launcherLatencyMaximumMillis)
        assertEquals(75, snapshot.launcherLatencyLastMillis)
    }

    @Test
    fun disabledCountersStayDormantAndInvalidLatencyIsDiscarded() {
        PerformanceMetrics.resetAndEnable()
        PerformanceMetrics.noteTerminalInput(5_000)
        PerformanceMetrics.noteTerminalFrame(4_999)
        PerformanceMetrics.noteLauncherInput(10_000)
        PerformanceMetrics.noteLauncherFrame(20_001)
        PerformanceMetrics.disableAndSnapshot()

        PerformanceMetrics.recordTerminalJni(
            directInputBytes = 9,
            directOutputBytes = 10,
        )
        PerformanceMetrics.recordCompositorJni(arrayCopyBytes = 11)
        PerformanceMetrics.recordTerminalKotlinCopy(12)
        PerformanceMetrics.noteTerminalInput(1_000)
        PerformanceMetrics.noteTerminalFrame(1_001)

        val snapshot = PerformanceMetrics.disableAndSnapshot()
        assertEquals(0, snapshot.terminalJniCalls)
        assertEquals(0, snapshot.compositorJniCalls)
        assertEquals(0, snapshot.kotlinCopyBytes)
        assertEquals(0, snapshot.terminalLatencySamples)
        assertEquals(0, snapshot.launcherLatencySamples)
    }
}
