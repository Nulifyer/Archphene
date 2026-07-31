package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSurfaceCoordinatePolicyTest {
    @Test
    fun mapsScaledViewCoordinatesIntoAttachedBuffer() {
        assertEquals(
            1_254,
            LauncherSurfaceCoordinatePolicy.absolute(
                windowCoordinate = 1_204f,
                viewOffset = 75,
                viewExtent = 2_018,
                bufferExtent = 2_241,
            ),
        )
        assertEquals(
            482,
            LauncherSurfaceCoordinatePolicy.absolute(
                windowCoordinate = 497f,
                viewOffset = 63,
                viewExtent = 881,
                bufferExtent = 978,
            ),
        )
    }

    @Test
    fun mapsRelativeMotionAndFallsBackBeforeAttachment() {
        assertEquals(
            11f,
            LauncherSurfaceCoordinatePolicy.relative(10f, 2_000, 2_200),
            0.001f,
        )
        assertEquals(
            10f,
            LauncherSurfaceCoordinatePolicy.relative(10f, 2_000, 0),
            0.001f,
        )
    }

    @Test
    fun clampsHostileAbsoluteCoordinates() {
        assertEquals(0, LauncherSurfaceCoordinatePolicy.absolute(Float.NaN, 0, 100, 200))
        assertEquals(0, LauncherSurfaceCoordinatePolicy.absolute(-10f, 0, 100, 200))
        assertEquals(199, LauncherSurfaceCoordinatePolicy.absolute(500f, 0, 100, 200))
        assertEquals(0, LauncherSurfaceCoordinatePolicy.absolute(0f, 0, 100, 200))
        assertEquals(101, LauncherSurfaceCoordinatePolicy.absolute(50f, 0, 100, 200))
        assertEquals(199, LauncherSurfaceCoordinatePolicy.absolute(99f, 0, 100, 200))
    }
}
