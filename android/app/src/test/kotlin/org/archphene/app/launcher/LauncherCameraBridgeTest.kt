package org.archphene.app.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherCameraBridgeTest {
    @Test
    fun runtimeDirectoryNamesUseTheExactManagerOwnedShape() {
        assertTrue(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdef",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdeg",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdef-extra",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera--0123456789abcdef",
            ),
        )
        assertFalse(LauncherCameraBridge.isRuntimeDirectoryName("unrelated-cache"))
    }
}
