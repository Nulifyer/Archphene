package org.archphene.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AurInstallScriptPathPolicyTest {
    @Test
    fun acceptsSafeRelativePathsAndExactSegmentLimit() {
        assertTrue(safeAurInstallScriptPath("visual-studio-code-bin.install"))
        assertTrue(safeAurInstallScriptPath("package/scripts/install@1+,_.-"))
        assertTrue(safeAurInstallScriptPath("a".repeat(240)))
    }

    @Test
    fun rejectsUnsafeOrOversizedSegments() {
        listOf(
            "",
            "/install",
            "install/",
            "package//install",
            ".",
            "..",
            "package/./install",
            "package/../install",
            "a".repeat(241),
            "package/install script",
            "package/install\\script",
            "package/install\né",
        ).forEach { path -> assertFalse(path, safeAurInstallScriptPath(path)) }
    }

    @Test
    fun scansDelimiterHeavyCallerBoundWithoutRetainingSegments() {
        assertTrue(safeAurInstallScriptPath("a/".repeat(2_047) + "a"))
    }
}
