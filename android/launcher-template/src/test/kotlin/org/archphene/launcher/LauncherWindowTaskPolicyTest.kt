package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherWindowTaskPolicyTest {
    @Test
    fun keepsCompactPhoneInOneTask() {
        assertFalse(policy(smallestWidthDp = 411, precisePointer = false, width = 1080, height = 2316))
    }

    @Test
    fun usesIndependentTasksForLargeAndExternalDisplays() {
        assertTrue(policy(smallestWidthDp = 600, precisePointer = false, width = 1200, height = 1920))
        assertTrue(
            policy(
                smallestWidthDp = 411,
                precisePointer = false,
                width = 1080,
                height = 1920,
                displayId = 2,
            ),
        )
    }

    @Test
    fun usesCurrentPointerWindowMetricsWithoutOemDetection() {
        assertTrue(policy(smallestWidthDp = 411, precisePointer = true, width = 1920, height = 1080))
        assertFalse(policy(smallestWidthDp = 411, precisePointer = true, width = 1080, height = 600))
        assertFalse(policy(smallestWidthDp = 411, precisePointer = false, width = 1920, height = 1080))
    }

    private fun policy(
        smallestWidthDp: Int,
        precisePointer: Boolean,
        width: Int,
        height: Int,
        displayId: Int = 0,
    ): Boolean =
        LauncherWindowTaskPolicy.useIndependentTasks(
            smallestWidthDp = smallestWidthDp,
            displayId = displayId,
            defaultDisplayId = 0,
            precisePointer = precisePointer,
            widthPixels = width,
            heightPixels = height,
            density = 2f,
        )
}
