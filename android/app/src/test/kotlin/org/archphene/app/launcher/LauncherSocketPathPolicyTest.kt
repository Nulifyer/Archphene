package org.archphene.app.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSocketPathPolicyTest {
    @Test
    fun reservesOneNativeByteAtExactAsciiAndMultibyteLimits() {
        assertTrue(fitsLauncherUnixSocketPath("a".repeat(7), 8))
        assertFalse(fitsLauncherUnixSocketPath("a".repeat(8), 8))
        assertTrue(fitsLauncherUnixSocketPath("é".repeat(3), 7))
        assertFalse(fitsLauncherUnixSocketPath("é".repeat(3) + "a", 7))
    }

    @Test
    fun rejectsMalformedUnicodeAndInvalidNativeLimits() {
        assertFalse(fitsLauncherUnixSocketPath("socket\ud800", 100))
        assertFalse(fitsLauncherUnixSocketPath("socket", 0))
        assertFalse(fitsLauncherUnixSocketPath("", -1))
    }
}
