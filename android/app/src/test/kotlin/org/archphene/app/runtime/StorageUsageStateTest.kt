package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageUsageStateTest {
    @Test
    fun decodesEveryBoundedStorageClass() {
        assertEquals(
            NativeStorageUsage(
                packageDownloadsBytes = 20L,
                sharedRuntimeBytes = 40L,
                buildCacheBytes = 60L,
                userFilesBytes = 80L,
            ),
            decodeNativeStorageUsage("S1\t10\t20\t30\t40\t50\t60\t70\t80\n"),
        )
    }

    @Test
    fun rejectsMalformedNegativeOversizedAndOverflowingSummaries() {
        for (value in listOf(
            "",
            "S2\t1\t2\t3\t4\t5\t6\t7\t8\n",
            "S1\t1\t2\t3\t4\t5\t6\t7\n",
            "S1\t-1\t2\t3\t4\t5\t6\t7\t8\n",
            "S1\t2000001\t2\t0\t4\t0\t6\t0\t8\n",
            "S1\t1\t9223372036854775807\t1\t1\t1\t1\t1\t1\n",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeStorageUsage(value)
            }
        }
    }
}
