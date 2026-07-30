package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIntentMimePolicyTest {
    @Test
    fun parsesBoundedSignedDeclaration() {
        assertEquals(
            listOf("text/plain", "image/*"),
            LauncherIntentMimePolicy.parseSpec("text/plain;image/*"),
        )
        assertEquals(emptyList<String>(), LauncherIntentMimePolicy.parseSpec(""))
        assertNull(LauncherIntentMimePolicy.parseSpec("text/plain;text/plain"))
        assertNull(LauncherIntentMimePolicy.parseSpec("text/plain;"))
    }

    @Test
    fun acceptsOnlyConcreteMatchingIncomingTypes() {
        val declared = listOf("text/plain", "image/*")
        assertTrue(LauncherIntentMimePolicy.matches(declared, "text/plain"))
        assertTrue(LauncherIntentMimePolicy.matches(declared, "image/png"))
        assertFalse(LauncherIntentMimePolicy.matches(declared, "image/*"))
        assertFalse(LauncherIntentMimePolicy.matches(declared, "application/pdf"))
    }
}
