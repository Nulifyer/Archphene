package org.archphene.launcher

internal object LauncherWindowTaskPolicy {
    fun useIndependentTasks(
        smallestWidthDp: Int,
        displayId: Int,
        defaultDisplayId: Int,
        precisePointer: Boolean,
        widthPixels: Int,
        heightPixels: Int,
        density: Float,
    ): Boolean {
        if (smallestWidthDp >= 600 || displayId != defaultDisplayId) return true
        if (!precisePointer || density <= 0f || widthPixels <= 0 || heightPixels <= 0) return false
        return widthPixels / density >= 600f && heightPixels / density >= 360f
    }
}
