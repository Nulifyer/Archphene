package org.archphene.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconPathPolicyTest {
    @Test
    fun acceptsRootRelativePathsAtTheExactLimit() {
        assertTrue(safeLauncherIconLogicalPath("/usr/share/icons/app.png"))
        assertTrue(safeLauncherIconLogicalPath("/icons/é\\app.png"))
        assertTrue(safeLauncherIconLogicalPath("/" + "a".repeat(239)))
    }

    @Test
    fun rejectsInvalidShapeTraversalAndOverflow() {
        listOf(
            "",
            "/",
            "relative/icon.png",
            "//icon.png",
            "/icons//app.png",
            "/icons/",
            "/.",
            "/..",
            "/icons/./app.png",
            "/icons/../app.png",
            "/" + "a".repeat(240),
        ).forEach { path -> assertFalse(path, safeLauncherIconLogicalPath(path)) }
    }
}
