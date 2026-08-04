package org.archphene.app.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherEntryNamePolicyTest {
    @Test
    fun acceptsNestedUnicodeNamesAtTheExactLimit() {
        assertTrue(safeLauncherEntryName("AndroidManifest.xml"))
        assertTrue(safeLauncherEntryName("res/drawable/ícono.png"))
        assertTrue(safeLauncherEntryName("a".repeat(240)))
    }

    @Test
    fun rejectsUnsafeShapeTraversalAndOverflow() {
        listOf(
            "",
            "/AndroidManifest.xml",
            "res/",
            "res//icon.png",
            ".",
            "..",
            "res/./icon.png",
            "res/../icon.png",
            "res\\icon.png",
            "a".repeat(241),
        ).forEach { name -> assertFalse(name, safeLauncherEntryName(name)) }
    }
}
