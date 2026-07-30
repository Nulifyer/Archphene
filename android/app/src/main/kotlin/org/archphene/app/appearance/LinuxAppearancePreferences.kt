package org.archphene.app.appearance

import android.content.Context

internal data class LinuxAppearanceOverrides(
    val geometryPercent: Int,
    val fontPercent: Int,
    val controlVisualDp: Int,
    val themeMode: Int,
    val materialYou: Boolean,
)

internal object LinuxAppearancePreferences {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2
    const val PREFERENCES = "linux_appearance"
    const val GEOMETRY_PERCENT = "geometry_percent"
    const val FONT_PERCENT = "font_percent"
    const val CONTROL_VISUAL_DP = "control_visual_dp"
    const val THEME_MODE = "theme_mode"
    const val MATERIAL_YOU = "material_you"

    val geometryValues = intArrayOf(AUTO, 75, 100, 125, 150)
    val fontValues = intArrayOf(AUTO, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200)
    val controlValues = intArrayOf(AUTO, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48)
    val themeValues = intArrayOf(AUTO, LIGHT, DARK)

    fun read(context: Context): LinuxAppearanceOverrides {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return LinuxAppearanceOverrides(
            preferences.getInt(GEOMETRY_PERCENT, AUTO).validatedBy(geometryValues),
            preferences.getInt(FONT_PERCENT, AUTO).validatedBy(fontValues),
            preferences.getInt(CONTROL_VISUAL_DP, AUTO).validatedBy(controlValues),
            preferences.getInt(THEME_MODE, AUTO).validatedBy(themeValues),
            preferences.getBoolean(MATERIAL_YOU, true),
        )
    }

    fun resolveDark(
        systemDark: Boolean,
        themeMode: Int,
    ): Boolean =
        when (themeMode.validatedBy(themeValues)) {
            LIGHT -> false
            DARK -> true
            else -> systemDark
        }

    private fun Int.validatedBy(values: IntArray): Int =
        if (values.contains(this)) this else AUTO
}
