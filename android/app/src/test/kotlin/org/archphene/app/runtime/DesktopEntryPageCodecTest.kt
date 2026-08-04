package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopEntryPageCodecTest {
    @Test
    fun headerOnlyPageIsAdmitted() {
        val page = DesktopEntryPageCodec.decode("D3\t0\t0\t0\t0\t0\n")

        assertEquals(listOf("D3", "0", "0", "0", "0", "0"), page.header)
        assertEquals(emptyList<List<String>>(), page.rows)
    }

    @Test
    fun normalPagesPreserveEveryField() {
        val header = listOf("D3", "2", "02", " 3 ", "", "state")
        val first = listOf("id", "Name", "/bin/app", "0", "", "try", "%F", "a;b", "pkg", "exec")
        val second = listOf("二", " spaced ", "relative", "yes", "icon", "", "%%", "", "source", "")

        val oneRow = DesktopEntryPageCodec.decode((listOf(header, first)).joinToString("\n") { it.joinToString("\t") } + "\n")
        val twoRows = DesktopEntryPageCodec.decode((listOf(header, first, second)).joinToString("\n") { it.joinToString("\t") } + "\n")

        assertEquals(header, oneRow.header)
        assertEquals(listOf(first), oneRow.rows)
        assertEquals(header, twoRows.header)
        assertEquals(listOf(first, second), twoRows.rows)
    }

    @Test
    fun exactRowBoundIsAdmitted() {
        val page = buildString {
            append("D3\t256\t256\t256\t0\t0\n")
            repeat(256) { append("$it\tn\t/bin/a\t0\ti\tt\ta\tm\ts\te\n") }
        }

        assertEquals(256, DesktopEntryPageCodec.decode(page).rows.size)
    }

    @Test
    fun rowBeyondBoundIsRejected() {
        val page = buildString {
            append("D3\t257\t257\t257\t0\t0\n")
            repeat(257) { append("$it\tn\t/bin/a\t0\ti\tt\ta\tm\ts\te\n") }
        }

        assertThrows(IllegalStateException::class.java) {
            DesktopEntryPageCodec.decode(page)
        }
    }

    @Test
    fun incorrectHeaderFieldCountsAreRejected() {
        for (page in listOf("D3\t0\t0\t0\t0\n", "D3\t0\t0\t0\t0\t0\textra\n")) {
            assertThrows(IllegalStateException::class.java) {
                DesktopEntryPageCodec.decode(page)
            }
        }
    }

    @Test
    fun incorrectRowFieldCountsAreRejected() {
        val header = "D3\t1\t1\t1\t0\t0\n"
        for (row in listOf("a\tb\tc\td\te\tf\tg\th\ti\n", "a\tb\tc\td\te\tf\tg\th\ti\tj\tk\n")) {
            assertThrows(IllegalStateException::class.java) {
                DesktopEntryPageCodec.decode(header + row)
            }
        }
    }

    @Test
    fun missingTerminalNewlineIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            DesktopEntryPageCodec.decode("D3\t0\t0\t0\t0\t0")
        }
    }

    @Test
    fun blankInternalRowIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            DesktopEntryPageCodec.decode("D3\t2\t2\t2\t0\t0\na\tb\tc\td\te\tf\tg\th\ti\tj\n\na\tb\tc\td\te\tf\tg\th\ti\tj\n")
        }
    }

    @Test
    fun exactSixteenKibibyteNewlineFloodIsRejected() {
        val page = "\n".repeat(16 * 1024)

        assertThrows(IllegalStateException::class.java) {
            DesktopEntryPageCodec.decode(page)
        }
    }

    @Test
    fun exactSixteenKibibyteTabFloodIsRejected() {
        val page = "\t".repeat(16 * 1024 - 1) + "\n"

        assertThrows(IllegalStateException::class.java) {
            DesktopEntryPageCodec.decode(page)
        }
    }
}
