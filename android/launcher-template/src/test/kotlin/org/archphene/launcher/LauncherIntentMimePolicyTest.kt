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

    @Test
    fun boundsMimeTypesWhileParsingTheSignedDeclaration() {
        val exact = List(16) { index -> "application/x-archphene-$index" }.joinToString(";")
        assertEquals(16, LauncherIntentMimePolicy.parseSpec(exact)?.size)

        val overflow = "$exact;application/x-archphene-16"
        assertNull(LauncherIntentMimePolicy.parseSpec(overflow))

        val hostile = buildString(2_080) { while (length < 2_080) append("a;") }
        assertEquals(2_080, hostile.length)
        assertNull(LauncherIntentMimePolicy.parseSpec(hostile))
    }
}
