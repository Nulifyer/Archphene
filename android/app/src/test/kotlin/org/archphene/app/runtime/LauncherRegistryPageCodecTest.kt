package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherRegistryPageCodecTest {
    @Test
    fun headerOnlyPagesAllowAnyTerminalNewlineCount() {
        for (suffix in listOf("", "\n", "\n\n\n")) {
            val page = LauncherRegistryPageCodec.decode("P2\t0\t0$suffix")

            assertEquals(listOf("P2", "0", "0"), page.header)
            assertEquals(emptyList<List<String>>(), page.rows)
        }
    }

    @Test
    fun oneAndTwoRowPagesPreserveEveryField() {
        val header = listOf("version", " 2 ", "02")
        val first = listOf("pkg", "desktop", "1", "0", "", "7", " spaced ", "source")
        val second = listOf("二", "", "3", "2", "1", "9", "Name", "")

        for (suffix in listOf("", "\n", "\n\n")) {
            val oneRow = decode(listOf(header, first), suffix)
            val twoRows = decode(listOf(header, first, second), suffix)

            assertEquals(header, oneRow.header)
            assertEquals(listOf(first), oneRow.rows)
            assertEquals(header, twoRows.header)
            assertEquals(listOf(first, second), twoRows.rows)
        }
    }

    @Test
    fun exactRowBoundIsAdmitted() {
        val page = buildString {
            append("P2\t256\t256\n")
            repeat(256) { index ->
                if (index > 0) append('\n')
                append("$index\tb\tc\td\te\tf\tg\th")
            }
        }

        assertEquals(256, LauncherRegistryPageCodec.decode(page).rows.size)
    }

    @Test
    fun rowBeyondBoundIsRejected() {
        val page = buildString {
            append("P2\t257\t257\n")
            repeat(257) { index ->
                if (index > 0) append('\n')
                append("$index\tb\tc\td\te\tf\tg\th")
            }
        }

        assertRejected(page)
    }

    @Test
    fun incorrectHeaderFieldCountsAreRejected() {
        for (page in listOf("P2\t0", "P2\t0\t0\textra")) assertRejected(page)
    }

    @Test
    fun incorrectRowFieldCountsAreRejected() {
        val header = "P2\t1\t1\n"
        for (row in listOf("a\tb\tc\td\te\tf\tg", "a\tb\tc\td\te\tf\tg\th\ti")) {
            assertRejected(header + row)
        }
    }

    @Test
    fun internalBlankRowIsRejected() {
        assertRejected("P2\t2\t2\na\tb\tc\td\te\tf\tg\th\n\na\tb\tc\td\te\tf\tg\th")
    }

    @Test
    fun emptyAndNewlineOnlyPagesAreRejected() {
        for (page in listOf("", "\n", "\n\n")) assertRejected(page)
    }

    @Test
    fun exactEightKibibyteDelimiterFloodsAreRejected() {
        for (page in listOf("\n".repeat(8 * 1024), "\t".repeat(8 * 1024))) assertRejected(page)
    }

    private fun decode(lines: List<List<String>>, suffix: String): LauncherRegistryPage =
        LauncherRegistryPageCodec.decode(lines.joinToString("\n") { it.joinToString("\t") } + suffix)

    private fun assertRejected(page: String) {
        assertThrows(IllegalStateException::class.java) {
            LauncherRegistryPageCodec.decode(page)
        }
    }
}
