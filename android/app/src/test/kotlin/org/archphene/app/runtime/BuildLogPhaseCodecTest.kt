package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BuildLogPhaseCodecTest {
    @Test
    fun returnsTrimmedLastNonEmptyLine() {
        assertEquals(
            "linking package",
            BuildLogPhaseCodec.lastNonEmptyLine("compiling\n  linking package  \n\n", 160),
        )
    }

    @Test
    fun supportsLfCrLfCrAndFinalUnterminatedLines() {
        for (delimiter in listOf("\n", "\r\n", "\r")) {
            assertEquals(
                "second",
                BuildLogPhaseCodec.lastNonEmptyLine("first${delimiter}second${delimiter}", 160),
            )
        }
        assertEquals("second", BuildLogPhaseCodec.lastNonEmptyLine("first\nsecond", 160))
    }

    @Test
    fun truncatesAfterTrimmingAndRejectsInvalidLimit() {
        assertEquals("abc", BuildLogPhaseCodec.lastNonEmptyLine("  abcdef  ", 3))
        assertThrows(IllegalArgumentException::class.java) {
            BuildLogPhaseCodec.lastNonEmptyLine("value", 0)
        }
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnlyLogs() {
        for (value in listOf("", "\n\r\n", "  \n\t\r")) {
            assertNull(BuildLogPhaseCodec.lastNonEmptyLine(value, 160))
        }
    }

    @Test
    fun scansExactEightKiBLineFloodWithoutConstructingEachLine() {
        val value = "x\n".repeat(4_093) + "final\n"

        assertEquals(8 * 1024, value.length)
        assertEquals("final", BuildLogPhaseCodec.lastNonEmptyLine(value, 160))
    }
}
