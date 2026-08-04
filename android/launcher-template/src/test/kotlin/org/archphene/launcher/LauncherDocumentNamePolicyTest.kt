package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherDocumentNamePolicyTest {
    @Test
    fun enforcesUtf8Boundary() {
        assertTrue(LauncherDocumentNamePolicy.valid("a".repeat(255)))
        assertTrue(LauncherDocumentNamePolicy.valid("é".repeat(127) + "a"))
        assertFalse(LauncherDocumentNamePolicy.valid("a".repeat(256)))
        assertFalse(LauncherDocumentNamePolicy.valid("é".repeat(128)))
        assertFalse(LauncherDocumentNamePolicy.valid("😀".repeat(64)))
    }

    @Test
    fun rejectsUnsafeAndMalformedNames() {
        assertFalse(LauncherDocumentNamePolicy.valid(""))
        assertFalse(LauncherDocumentNamePolicy.valid("."))
        assertFalse(LauncherDocumentNamePolicy.valid(".."))
        assertFalse(LauncherDocumentNamePolicy.valid("folder/name"))
        assertFalse(LauncherDocumentNamePolicy.valid("folder\\name"))
        assertFalse(LauncherDocumentNamePolicy.valid("bad\u0000name"))
        assertFalse(LauncherDocumentNamePolicy.valid("\uD800"))
        assertFalse(LauncherDocumentNamePolicy.valid("\uDC00"))
    }
}
