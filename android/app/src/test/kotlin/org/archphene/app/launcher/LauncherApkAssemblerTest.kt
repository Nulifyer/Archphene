package org.archphene.app.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherApkAssemblerTest {
    @Test
    fun binaryXmlPoolStringsUseExactLegacyEncoding() {
        assertArrayEquals(
            byteArrayOf(
                4,
                8,
                0x41,
                0xe7.toByte(),
                0x95.toByte(),
                0x8c.toByte(),
                0xf0.toByte(),
                0x9f.toByte(),
                0x98.toByte(),
                0x80.toByte(),
                0,
            ),
            encodeBinaryXmlPoolString("A界😀", utf8 = true),
        )
        assertArrayEquals(
            byteArrayOf(2, 0, 0x41, 0, 0x4c, 0x75, 0, 0),
            encodeBinaryXmlPoolString("A界", utf8 = false),
        )
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte()),
            encodeBinaryXmlPoolString("A".repeat(128), utf8 = true).copyOf(2),
        )
    }

    @Test
    fun launcherLabelsRejectMalformedUnicodeWithoutEncodingCopies() {
        assertTrue(LauncherApkAssembler.validLabel("a".repeat(128)))
        assertTrue(LauncherApkAssembler.validLabel("界".repeat(128)))
        assertFalse(LauncherApkAssembler.validLabel("a".repeat(129)))
        assertFalse(LauncherApkAssembler.validLabel(" "))
        assertFalse(LauncherApkAssembler.validLabel("\u202eSpoof"))
        assertFalse(LauncherApkAssembler.validLabel("\ud800"))
        assertFalse(LauncherApkAssembler.validLabel("\udc00"))
    }

    @Test
    fun acceptsOnlyCanonicalImplementedCapabilityContracts() {
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_PRINTING_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_PRINTING_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_PRINTING_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_SECRETS_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V10,
            ),
        )
        assertFalse(
            LauncherApkAssembler.validCapabilities(
                "${LauncherApkAssembler.CAPABILITIES_V10},accessibility",
            ),
        )
        assertFalse(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_V4,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_CAMERA_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validCapabilities(
                LauncherApkAssembler.CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_CAMERA_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.hasCameraCapability(
                LauncherApkAssembler.CAPABILITIES_CAMERA_V10,
            ),
        )
        assertFalse(
            LauncherApkAssembler.hasCameraCapability(
                LauncherApkAssembler.CAPABILITIES_SECRETS_V10,
            ),
        )
        assertTrue(
            LauncherApkAssembler.validMetadataCapabilities(
                "c:${LauncherApkAssembler.CAPABILITIES_PRINTING_V10}",
            ),
        )
        assertFalse(
            LauncherApkAssembler.validMetadataCapabilities(
                LauncherApkAssembler.CAPABILITIES_PRINTING_V10,
            ),
        )
    }
}
