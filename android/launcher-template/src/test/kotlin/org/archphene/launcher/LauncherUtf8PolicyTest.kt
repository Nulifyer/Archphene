package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUtf8PolicyTest {
    @Test
    fun enforcesByteBoundaryWithoutAllocatingEncodedText() {
        assertTrue(LauncherUtf8Policy.lengthAtMost("", 0))
        assertTrue(LauncherUtf8Policy.lengthAtMost("a".repeat(512), 512))
        assertTrue(LauncherUtf8Policy.lengthAtMost("é".repeat(256), 512))
        assertTrue(LauncherUtf8Policy.lengthAtMost("😀".repeat(128), 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("a".repeat(513), 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("é".repeat(257), 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("😀".repeat(129), 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("a", -1))
    }

    @Test
    fun rejectsMalformedSurrogates() {
        assertFalse(LauncherUtf8Policy.lengthAtMost("\uD800", 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("\uDC00", 512))
        assertFalse(LauncherUtf8Policy.lengthAtMost("a\uD800b", 512))
    }

    @Test
    fun enforcesAccessibilityTextBoundary() {
        assertTrue(LauncherUtf8Policy.lengthAtMost("a".repeat(16_384), 16_384))
        assertFalse(LauncherUtf8Policy.lengthAtMost("a".repeat(16_385), 16_384))
        assertTrue(LauncherUtf8Policy.lengthAtMost("😀".repeat(4_096), 16_384))
        assertFalse(LauncherUtf8Policy.lengthAtMost("😀".repeat(4_097), 16_384))
    }
}
