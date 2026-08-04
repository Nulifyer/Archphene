package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageCachePageCodecTest {
    @Test
    fun normalPagePreservesFieldsAndIgnoresTerminalNewlines() {
        assertEquals(
            listOf(
                listOf("alpha", "1.0", "x86_64", "12", "1"),
                listOf("beta", "", "any", "34", "2"),
            ),
            PackageCachePageCodec.decode(
                "alpha\t1.0\tx86_64\t12\t1\nbeta\t\tany\t34\t2\n\n",
            ),
        )
    }

    @Test
    fun exactRowBoundIsAdmitted() {
        val page = (1..32).joinToString("\n") { "$it\tv\ta\t0\t1" }

        assertEquals(32, PackageCachePageCodec.decode(page).size)
    }

    @Test
    fun rowBeyondBoundIsRejected() {
        val page = (1..33).joinToString("\n") { "$it\tv\ta\t0\t1" }

        assertThrows(IllegalStateException::class.java) {
            PackageCachePageCodec.decode(page)
        }
    }

    @Test
    fun suppliedRowBoundRejectsNextRow() {
        val page = "1\tv\ta\t0\t1\n2\tv\ta\t0\t1"

        assertThrows(IllegalStateException::class.java) {
            PackageCachePageCodec.decode(page, maximumRows = 1)
        }
    }

    @Test
    fun suppliedRowBoundAdmitsExactRowCount() {
        assertEquals(
            1,
            PackageCachePageCodec.decode("1\tv\ta\t0\t1", maximumRows = 1).size,
        )
    }

    @Test
    fun invalidRowBoundsAreRejected() {
        for (maximumRows in listOf(0, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                PackageCachePageCodec.decode("1\tv\ta\t0\t1", maximumRows)
            }
        }
    }

    @Test
    fun incorrectFieldCountsAreRejected() {
        for (page in listOf("a\tb\tc\td", "a\tb\tc\td\te\tf")) {
            assertThrows(IllegalStateException::class.java) {
                PackageCachePageCodec.decode(page)
            }
        }
    }

    @Test
    fun emptyPagesAreRejected() {
        for (page in listOf("", "\n", "\n\n")) {
            assertThrows(IllegalStateException::class.java) {
                PackageCachePageCodec.decode(page)
            }
        }
    }

    @Test
    fun exactSixteenKibibyteTabFloodIsRejected() {
        val page = "\t".repeat(16 * 1024)

        assertThrows(IllegalStateException::class.java) {
            PackageCachePageCodec.decode(page)
        }
    }
}
