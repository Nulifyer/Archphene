package org.archphene.launcher

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherOrientationPolicyTest {
    @Test
    fun sdlUsesSensorLandscapeOnlyOnPhoneSizedDisplays() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            LauncherOrientationPolicy.requestedOrientation(
                LauncherOrientationPolicy.SDL_PHONE,
                432,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            LauncherOrientationPolicy.requestedOrientation(
                LauncherOrientationPolicy.SDL_PHONE,
                600,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            LauncherOrientationPolicy.requestedOrientation(
                LauncherOrientationPolicy.DEFAULT,
                432,
            ),
        )
    }
}
