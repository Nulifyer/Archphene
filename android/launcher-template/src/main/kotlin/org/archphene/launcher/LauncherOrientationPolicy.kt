package org.archphene.launcher

import android.content.pm.ActivityInfo

internal object LauncherOrientationPolicy {
    const val DEFAULT = 0
    const val SDL_PHONE = 1

    private const val TABLET_MINIMUM_DP = 600

    fun requestedOrientation(
        policy: Int,
        smallestScreenWidthDp: Int,
    ): Int =
        if (policy == SDL_PHONE && smallestScreenWidthDp in 1 until TABLET_MINIMUM_DP) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
}
