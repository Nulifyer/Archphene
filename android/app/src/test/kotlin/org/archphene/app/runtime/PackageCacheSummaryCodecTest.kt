package org.archphene.app.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageCacheSummaryCodecTest {
    @Test
    fun decodesExactlyTwoNumbersAndTerminalLfRuns() {
        val expected = longArrayOf(1_024L, 9_223_372_036_854_775_807L)

        assertArrayEquals(expected, PackageCacheSummaryCodec.decode("C1\t1024\t9223372036854775807"))
        assertArrayEquals(expected, PackageCacheSummaryCodec.decode("C1\t1024\t9223372036854775807\n\n"))
    }

    @Test
    fun rejectsInvalidHeadersFieldCountsAndNumbers() {
        for (value in listOf(
            "",
            "C0\t1\t2",
            "C1\t1",
            "C1\t1\t2\t3",
            "C1\t\t2",
            "C1\t1\t",
            "C1\tinvalid\t2",
            "C1\t1\t9223372036854775808",
            "C1\t1\t2\n3",
        )) {
            assertNull(PackageCacheSummaryCodec.decode(value))
        }
    }

    @Test
    fun rejectsExact16KiBTabFloodWithoutRetainingUnboundedFields() {
        assertNull(PackageCacheSummaryCodec.decode("C1\t" + "1\t".repeat(8_190) + "1"))
    }
}
