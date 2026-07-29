package org.archphene.app.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGpuBridgeTest {
    @Test
    fun runtimeDirectoryNamesUseTheExactManagerOwnedShape() {
        assertTrue(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdef",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdeg",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdef-extra",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu--0123456789abcdef",
            ),
        )
        assertFalse(AndroidGpuBridge.isRuntimeDirectoryName("unrelated-cache"))
    }
}
