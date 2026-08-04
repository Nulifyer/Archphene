package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageStatusTextTest {
    @Test
    fun findsExactLinePrefixesWithoutCrossingDelimiters() {
        assertTrue(hasPackageStatusLineStartingWith("Title\rInstalled\r\nIntegration: Wayland\nTail", "Integration:"))
        assertFalse(hasPackageStatusLineStartingWith("Title\nIntegr\nation: Wayland", "Integration:"))
    }

    @Test
    fun replacesTheFirstMatchingLineAndNormalizesDelimiters() {
        assertEquals(
            "Title\nInstalled\nIntegration: Updated\nIntegration: Later\n",
            replaceFirstPackageStatusLineStartingWith(
                "Title\rInstalled\r\nIntegration: Old\nIntegration: Later\r",
                "Integration:",
                "Integration: Updated",
            ),
        )
    }

    @Test
    fun replacesIndexedEmptyLineAndLeavesMissingTargetsUntouched() {
        assertEquals("Title\nInstalled: 1.2.3", replacePackageStatusLine("Title\n", 1, "Installed: 1.2.3"))
        val oneLine = "Title"
        assertSame(oneLine, replacePackageStatusLine(oneLine, 1, "Not installed"))
        assertSame(oneLine, replaceFirstPackageStatusLineStartingWith(oneLine, "Integration:", "replacement"))
    }
}
