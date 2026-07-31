package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherInputTimestampPolicyTest {
    @Test
    fun acceptsFirstAndIncreasingTimes() {
        assertEquals(100, LauncherInputTimestampPolicy.next(100, 0, false))
        assertEquals(101, LauncherInputTimestampPolicy.next(101, 100, true))
    }

    @Test
    fun advancesDuplicateAndStaleReleaseTimes() {
        assertEquals(101, LauncherInputTimestampPolicy.next(100, 100, true))
        assertEquals(601, LauncherInputTimestampPolicy.next(100, 600, true))
    }

    @Test
    fun advancesAcrossUnsignedWrap() {
        assertEquals(0, LauncherInputTimestampPolicy.next(0, -1, true))
    }
}
