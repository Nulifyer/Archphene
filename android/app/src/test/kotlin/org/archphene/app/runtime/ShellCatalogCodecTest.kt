package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellCatalogCodecTest {
    @Test
    fun lineDelimitersAndFinalUnterminatedLineAreAdmitted() {
        val catalog = "a\tA\t/bin/a\nb\tB\t/bin/b\r\nc\tC\t/bin/c\rd\tD\t/bin/d"

        assertEquals(listOf("a", "b", "c", "d"), ShellCatalogCodec.decode(catalog).map { it[0] })
    }

    @Test
    fun blankLinesAreIgnored() {
        assertEquals(
            listOf(listOf("a", "Label", "/bin/a")),
            ShellCatalogCodec.decode("\n\r\n\ra\tLabel\t/bin/a\r\n\n"),
        )
    }

    @Test
    fun exactRowBoundIsAdmittedAndNextRowIsRejected() {
        val eightRows = (1..8).joinToString("\n") { "$it\tShell$it\t/bin/s$it" }
        assertEquals(8, ShellCatalogCodec.decode(eightRows).size)

        assertRejected("$eightRows\n9\tShell9\t/bin/s9")
    }

    @Test
    fun exactFieldBoundsAreAdmittedAndOutsideBoundsAreRejected() {
        val three = listOf("id", "Label", "/bin/shell")
        val seven = listOf("id2", "Label2", "/bin/shell", "-a", "x", "y", "z")
        assertEquals(listOf(three, seven), ShellCatalogCodec.decode("${three.joinToString("\t")}\n${seven.joinToString("\t")}"))

        assertRejected("id\tLabel")
        assertRejected("id\tLabel\t/bin/shell\ta\tb\tc\td\te")
    }

    @Test
    fun exactFieldLengthIsAdmittedAndNextUnitIsRejected() {
        val exact = "x".repeat(64)
        assertEquals(exact, ShellCatalogCodec.decode("id\t$exact\t/bin/shell")[0][1])

        assertRejected("id\t${"x".repeat(65)}\t/bin/shell")
    }

    @Test
    fun controlAndNonAsciiCharactersAreRejected() {
        for (character in listOf('\u0000', '\u001f', '\u007f', '\u0080', 'é')) {
            assertRejected("id\tLabel\t/bin/${character}shell")
        }
    }

    @Test
    fun argumentSpacesAreRejectedButNamesMayContainSpaces() {
        assertEquals(
            listOf("id value", "Shell Label", "/bin/shell"),
            ShellCatalogCodec.decode("id value\tShell Label\t/bin/shell").single(),
        )

        assertRejected("id\tLabel\t/bin/shell\tbad argument")
    }

    @Test
    fun emptyCatalogIsRejected() {
        assertRejected("")
    }

    @Test
    fun exactSixteenKibibyteDelimiterFloodsAreRejected() {
        assertRejected("\n".repeat(16 * 1024))
        assertRejected("\t".repeat(16 * 1024))
    }

    private fun assertRejected(catalog: String) {
        assertThrows(IllegalStateException::class.java) {
            ShellCatalogCodec.decode(catalog)
        }
    }
}
