package org.archphene.app.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherApkAssemblerTest {
    @Test
    fun acceptsOnlyCanonicalImplementedCapabilityContracts() {
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_V4,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_PRINTING_V5,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_V6,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_PRINTING_V6,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_V7,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_PRINTING_V7,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_SECRETS_V8,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V8,
            ),
        )
        assertFalse(
            LauncherApkAssembler.validCapabilities(
                "${LauncherApkAssembler.CAPABILITIES_V4},camera",
            ),
        )
        assertTrue(
            LauncherApkAssembler.validMetadataCapabilities(
                "c:${LauncherApkAssembler.CAPABILITIES_PRINTING_V5}",
            ),
        )
        assertFalse(
            LauncherApkAssembler.validMetadataCapabilities(
                LauncherApkAssembler.CAPABILITIES_PRINTING_V5,
            ),
        )
    }
}
