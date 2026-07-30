package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityBoundsMapperTest {
    @Test
    fun primaryLogicalOutputMapsToTheCompletePhysicalSurface() {
        val transform =
            AccessibilityViewportTransform(
                presentationWidth = 432,
                presentationHeight = 881,
                destinationX = 0,
                destinationY = 0,
                destinationWidth = 432,
                destinationHeight = 881,
            )
        assertEquals(
            AccessibilityDisplayBounds(30, 59, 750, 239),
            mapAccessibilityDisplayBounds(
                12,
                24,
                300,
                96,
                sourceWidth = 432,
                sourceHeight = 881,
                transform = transform,
                hostWidth = 1080,
                hostHeight = 2202,
            ),
        )
    }

    @Test
    fun oversizedSecondaryUsesTheCompositorsUniformFit() {
        val transform =
            AccessibilityViewportTransform(
                presentationWidth = 1080,
                presentationHeight = 2202,
                destinationX = 0,
                destinationY = 275,
                destinationWidth = 1080,
                destinationHeight = 1651,
            )
        assertEquals(
            AccessibilityDisplayBounds(1039, 1650, 1080, 1926),
            mapAccessibilityDisplayBounds(
                1386,
                1834,
                1440,
                2202,
                sourceWidth = 1440,
                sourceHeight = 2202,
                transform = transform,
                hostWidth = 1080,
                hostHeight = 2202,
            ),
        )
    }

    @Test
    fun compactSecondaryUsesItsCenteredCompositorRectangle() {
        val transform =
            AccessibilityViewportTransform(
                presentationWidth = 432,
                presentationHeight = 881,
                destinationX = 14,
                destinationY = 320,
                destinationWidth = 404,
                destinationHeight = 240,
            )
        assertEquals(
            AccessibilityDisplayBounds(665, 1279, 915, 1399),
            mapAccessibilityDisplayBounds(
                252,
                192,
                352,
                240,
                sourceWidth = 404,
                sourceHeight = 240,
                transform = transform,
                hostWidth = 1080,
                hostHeight = 2202,
            ),
        )
    }

    @Test
    fun stalePrimaryFrameMapsCurrentAtSpiViewportAcrossPresentationCanvas() {
        val transform =
            AccessibilityViewportTransform(
                presentationWidth = 990,
                presentationHeight = 645,
                destinationX = 0,
                destinationY = 0,
                destinationWidth = 990,
                destinationHeight = 645,
            )
        assertEquals(
            AccessibilityDisplayBounds(1733, 927, 1915, 978),
            mapAccessibilityDisplayBounds(
                766,
                410,
                846,
                432,
                sourceWidth = 990,
                sourceHeight = 432,
                transform = transform,
                hostWidth = 2241,
                hostHeight = 978,
            ),
        )
    }

    @Test
    fun fallbackRetainsThePreviousPrimaryViewportBehavior() {
        assertEquals(
            AccessibilityDisplayBounds(30, 60, 750, 240),
            mapAccessibilityDisplayBoundsFallback(
                12,
                24,
                300,
                96,
                viewportWidth = 432,
                viewportHeight = 880,
                hostWidth = 1080,
                hostHeight = 2200,
            ),
        )
    }
}
