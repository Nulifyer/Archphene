package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherAuthorizationCodecTest {
    @Test
    fun decodesExactlySixFieldsWithOptionalTerminalLf() {
        val expected = listOf("A4", "1", "16", "ff", "Foot", "text/plain;")
        val value = expected.joinToString("\t")

        assertEquals(expected, LauncherAuthorizationCodec.decode(value))
        assertEquals(expected, LauncherAuthorizationCodec.decode("$value\n"))
    }

    @Test
    fun preservesEmptyFieldsForServiceBoundaryValidation() {
        assertEquals(
            listOf("A4", "", "", "", "", ""),
            LauncherAuthorizationCodec.decode("A4\t\t\t\t\t"),
        )
    }

    @Test
    fun rejectsEmptyUnderflowOverflowAndLineBreaks() {
        for (value in listOf(
            "",
            "A4\t1\t2\t3\t4",
            "A4\t1\t2\t3\t4\t5\t6",
            "A4\t1\t2\t3\t4\t5\n\n",
            "A4\t1\t2\t3\t4\t5\r",
        )) {
            assertNull(LauncherAuthorizationCodec.decode(value))
        }
    }

    @Test
    fun rejectsExactThreeKiBTabFloodBeforeRetainingExcessFields() {
        assertNull(LauncherAuthorizationCodec.decode("\t".repeat(3 * 1024)))
    }
}
