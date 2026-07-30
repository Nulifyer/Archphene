package org.archphene.app.appearance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxAppearancePreferencesTest {
    @Test
    fun colorSchemeAutoTracksAndroidAndOverridesAreExact() {
        assertFalse(
            LinuxAppearancePreferences.resolveDark(
                false,
                LinuxAppearancePreferences.AUTO,
            ),
        )
        assertTrue(
            LinuxAppearancePreferences.resolveDark(
                true,
                LinuxAppearancePreferences.AUTO,
            ),
        )
        assertFalse(
            LinuxAppearancePreferences.resolveDark(
                true,
                LinuxAppearancePreferences.LIGHT,
            ),
        )
        assertTrue(
            LinuxAppearancePreferences.resolveDark(
                false,
                LinuxAppearancePreferences.DARK,
            ),
        )
    }

    @Test
    fun unknownColorSchemeFailsBackToAndroid() {
        assertFalse(LinuxAppearancePreferences.resolveDark(false, Int.MAX_VALUE))
        assertTrue(LinuxAppearancePreferences.resolveDark(true, Int.MIN_VALUE))
    }
}
