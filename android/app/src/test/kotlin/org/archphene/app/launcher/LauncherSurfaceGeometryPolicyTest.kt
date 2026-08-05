package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherSurfaceGeometryPolicyTest {
    @Test
    fun matchesAutomaticCompositorGeometry() {
        assertEquals(
            LauncherSurfaceGeometryPolicy.LogicalSize(990, 432),
            LauncherSurfaceGeometryPolicy.logicalSize(2241, 978, 420, 0),
        )
    }

    @Test
    fun appliesExplicitGeometryToAutomaticDensity() {
        assertEquals(
            LauncherSurfaceGeometryPolicy.LogicalSize(792, 345),
            LauncherSurfaceGeometryPolicy.logicalSize(2241, 978, 420, 125),
        )
    }

    @Test
    fun rejectsUnsupportedGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            LauncherSurfaceGeometryPolicy.logicalSize(2241, 978, 420, 110)
        }
    }

    @Test
    fun acceptsInstalledLaunchersAcrossCompatibleProtocolVersions() {
        assertEquals(true, LauncherSessionService.supportedProtocolVersion(16))
        assertEquals(true, LauncherSessionService.supportedProtocolVersion(17))
        assertEquals(true, LauncherSessionService.supportedProtocolVersion(18))
        assertEquals(true, LauncherSessionService.supportedProtocolVersion(19))
        assertEquals(true, LauncherSessionService.supportedProtocolVersion(20))
        assertEquals(false, LauncherSessionService.supportedProtocolVersion(15))
        assertEquals(false, LauncherSessionService.supportedProtocolVersion(21))
    }

    @Test
    fun scalesOnlyLegacyPhysicalInputAtManagerBoundary() {
        val records =
            intArrayOf(
                1,
                7,
                540,
                1158,
                9,
                0,
                11,
                10_000,
                -10_000,
                5_000,
                -5_000,
                10,
            )
        LauncherSessionService.scaleLegacyInputRecord(records, 0, 432, 926, 1080, 2316)
        LauncherSessionService.scaleLegacyInputRecord(records, 6, 432, 926, 1080, 2316)
        assertEquals(
            listOf(1, 7, 216, 463, 9, 0),
            records.slice(0 until 6),
        )
        assertEquals(
            listOf(11, 4_000, -3_998, 2_000, -1_999, 10),
            records.slice(6 until 12),
        )
    }
}
