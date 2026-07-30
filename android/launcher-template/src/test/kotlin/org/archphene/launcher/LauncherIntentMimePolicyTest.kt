package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIntentMimePolicyTest {
    @Test
    fun enforcesSignedMimeDeclarationAtAndroidBoundary() {
        val declared =
            checkNotNull(LauncherIntentMimePolicy.parseSpec("text/plain;image/*"))
        assertEquals(listOf("text/plain", "image/*"), declared)
        assertTrue(LauncherIntentMimePolicy.matches(declared, "image/png"))
        assertFalse(LauncherIntentMimePolicy.matches(declared, "image/*"))
        assertFalse(LauncherIntentMimePolicy.matches(declared, "application/pdf"))
        assertNull(LauncherIntentMimePolicy.parseSpec("text/plain;text/plain"))
    }
}
