package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageSearchPageCodecTest {
    private val row = "core\tpkg\t1.0\tdescription\tavailable\t"

    @Test
    fun decodesDelimiterFormsAndTerminalLfRuns() {
        assertEquals(
            List(4) { listOf("core", "pkg", "1.0", "description", "available", "") },
            PackageSearchPageCodec.decode("$row\n$row\r\n$row\r$row\n\n", 4),
        )
    }

    @Test
    fun admitsExactRowsAndRejectsTheNextBeforeRetention() {
        assertEquals(100, PackageSearchPageCodec.decode(List(100) { row }.joinToString("\n"), 100).size)
        assertThrows(IllegalStateException::class.java) {
            PackageSearchPageCodec.decode(List(101) { row }.joinToString("\n"), 100)
        }
    }

    @Test
    fun rejectsBlankRowsAndFieldUnderflowOrOverflow() {
        for (value in listOf("", "\n", "$row\n\n$row", "a\tb\tc\td\te", "a\tb\tc\td\te\tf\tg")) {
            assertThrows(IllegalStateException::class.java) {
                PackageSearchPageCodec.decode(value, 100)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageSearchPageCodec.decode(row, 0)
        }
    }

    @Test
    fun rejectsExact16KiBNewlineAndTabFloods() {
        for (value in listOf("\n".repeat(16 * 1024), "a\t".repeat(8 * 1024))) {
            assertThrows(IllegalStateException::class.java) {
                PackageSearchPageCodec.decode(value, 100)
            }
        }
    }
}
