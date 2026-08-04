package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageJobRecordCodecTest {
    @Test
    fun decodesExactlyNineFieldsAndTerminalLfRuns() {
        val expected = listOf("42", "1", "2", "phase", "75", "1234", "core", "btop", "Complete")
        val record = expected.joinToString("\t")

        assertEquals(expected, PackageJobRecordCodec.decode(record))
        assertEquals(expected, PackageJobRecordCodec.decode("$record\n\n"))
    }

    @Test
    fun preservesEmptyFieldsWithinTheExactSchema() {
        assertEquals(
            listOf("1", "2", "3", "", "4", "5", "", "package", ""),
            PackageJobRecordCodec.decode("1\t2\t3\t\t4\t5\t\tpackage\t"),
        )
    }

    @Test
    fun rejectsEmptyRecordUnderflowOverflowAndInternalNewlines() {
        for (record in listOf(
            "",
            "\n",
            "1\t2\t3\t4\t5\t6\t7\t8",
            "1\t2\t3\t4\t5\t6\t7\t8\t9\t10",
            "1\t2\t3\t4\t5\t6\t7\t8\t9\n10",
            "1\t2\t3\t4\t5\t6\t7\t8\t9\r",
        )) {
            assertNull(PackageJobRecordCodec.decode(record))
        }
    }

    @Test
    fun rejectsExact16KiBTabFloodBeforeRetainingExcessFields() {
        assertNull(PackageJobRecordCodec.decode("1\t".repeat(8_192)))
    }
}
