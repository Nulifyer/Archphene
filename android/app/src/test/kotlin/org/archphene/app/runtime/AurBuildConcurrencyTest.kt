package org.archphene.app.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun staleOutputCleanupBoundsNonRegularVisits() {
        val directory = Files.createTempDirectory("archphene-stale-aur-output").toFile()
        try {
            val limit = 64
            val staleDirectories =
                List(limit + 1) { index ->
                    directory.resolve(".aur-$index.pkg").apply { mkdir() }
                }

            val result =
                removeStaleAurBuildOutputs(
                    directory.toPath(),
                    ".aur-*.pkg",
                    limit,
                )

            assertEquals(limit + 1, result.visited)
            assertTrue(result.truncated)
            assertEquals(limit, result.unsafeEntries.size)
            assertEquals(0, result.removed)
            assertNull(result.error)
            assertTrue(staleDirectories.all { it.isDirectory })
            assertFalse(staleDirectories.any { it.isFile })
        } finally {
            directory.deleteRecursively()
        }
    }
}
