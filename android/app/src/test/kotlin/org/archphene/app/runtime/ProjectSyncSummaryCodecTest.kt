package org.archphene.app.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectSyncSummaryCodecTest {
    @Test
    fun decodesExactlySevenBoundedCounts() {
        assertArrayEquals(
            intArrayOf(6, 1, 2, 3, 4, 5, 6),
            ProjectSyncSummaryCodec.decode("6\t1\t2\t3\t4\t5\t6", 6),
        )
    }

    @Test
    fun rejectsFieldCountValuesAndInvalidMaximum() {
        for (value in listOf(
            "",
            "1\t2\t3\t4\t5\t6",
            "1\t2\t3\t4\t5\t6\t7\t8",
            "1\t2\t3\t4\t5\t6\t",
            "1\t2\t3\t4\t5\tinvalid\t7",
            "-1\t2\t3\t4\t5\t6\t7",
            "8\t2\t3\t4\t5\t6\t7",
        )) {
            assertThrows(IllegalStateException::class.java) {
                ProjectSyncSummaryCodec.decode(value, 7)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectSyncSummaryCodec.decode("0\t0\t0\t0\t0\t0\t0", -1)
        }
    }

    @Test
    fun rejectsExact8KiBTabFloodBeforeExcessFieldRetention() {
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncSummaryCodec.decode("1\t".repeat(4_096), 10_000)
        }
    }
}
