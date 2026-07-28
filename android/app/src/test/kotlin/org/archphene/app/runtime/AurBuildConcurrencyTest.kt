package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AurBuildConcurrencyTest {
    @Test
    fun boundsCpuAndMemoryPressure() {
        assertEquals(1, AurBuildConcurrency.recommendedJobs(8, 2L shl 30, false, 0))
        assertEquals(2, AurBuildConcurrency.recommendedJobs(8, 4L shl 30, false, 0))
        assertEquals(4, AurBuildConcurrency.recommendedJobs(8, 8L shl 30, false, 0))
        assertEquals(2, AurBuildConcurrency.recommendedJobs(2, 8L shl 30, false, 0))
    }

    @Test
    fun pressureAndThermalSignalsReduceParallelism() {
        assertEquals(1, AurBuildConcurrency.recommendedJobs(8, 8L shl 30, true, 0))
        assertEquals(2, AurBuildConcurrency.recommendedJobs(8, 8L shl 30, false, 2))
        assertEquals(1, AurBuildConcurrency.recommendedJobs(8, 8L shl 30, false, 3))
        assertEquals(1, AurBuildConcurrency.recommendedJobs(0, 8L shl 30, false, 0))
        assertEquals(1, AurBuildConcurrency.recommendedJobs(8, 0L, false, 0))
    }
}
