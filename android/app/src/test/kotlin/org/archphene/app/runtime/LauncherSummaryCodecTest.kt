package org.archphene.app.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherSummaryCodecTest {
    @Test
    fun decodesExactlyTenNumbersAndTerminalLfRuns() {
        val expected = LongArray(10) { index -> index.toLong() }
        val summary = "L3\t" + expected.joinToString("\t")

        assertArrayEquals(expected, LauncherSummaryCodec.decode(summary))
        assertArrayEquals(expected, LauncherSummaryCodec.decode("$summary\n\n"))
    }

    @Test
    fun rejectsInvalidHeadersFieldCountsAndNumbers() {
        for (value in listOf(
            "",
            "L2\t0\t1\t2\t3\t4\t5\t6\t7\t8\t9",
            "L3\t0\t1\t2\t3\t4\t5\t6\t7\t8",
            "L3\t0\t1\t2\t3\t4\t5\t6\t7\t8\t9\t10",
            "L3\t0\t1\t2\t3\t4\t5\t6\t7\t8\tinvalid",
            "L3\t0\t1\t2\t3\t4\t5\t6\t7\t8\t9223372036854775808",
        )) {
            assertNull(LauncherSummaryCodec.decode(value))
        }
    }

    @Test
    fun rejectsExact16KiBTabFloodWithoutUnboundedFields() {
        assertNull(LauncherSummaryCodec.decode("L3\t" + "1\t".repeat(8_190) + "1"))
    }
}
