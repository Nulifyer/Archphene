package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifiedBuildClosureCodecTest {
    @Test
    fun zeroPackageManifestIgnoresBlankLines() {
        val closure = decode("ABPC0001\n\r\n\rsummary\t0\t0\n", 0)

        assertEquals(emptyList<List<String>>(), closure.packageRows)
        assertEquals(listOf("summary", "0", "0"), closure.summary)
    }

    @Test
    fun twoPackageManifestSupportsMixedDelimitersAndUnterminatedSummary() {
        val closure = decode("ABPC0001\r\n${row("a")}\n\n${row("b")}\r\rsummary\t2\t30", 2)

        assertEquals(listOf(rowFields("a"), rowFields("b")), closure.packageRows)
        assertEquals(listOf("summary", "2", "30"), closure.summary)
    }

    @Test
    fun exactPackageBoundIsAdmitted() {
        val manifest = buildString {
            append("ABPC0001\n")
            repeat(512) { append(row(it.toString())).append('\n') }
            append("summary\t512\t0")
        }

        assertEquals(512, decode(manifest, 512).packageRows.size)
    }

    @Test
    fun additionalNonemptyRowIsRejected() {
        assertRejected("ABPC0001\nsummary\t0\t0\nextra\t0\t0", 0)
    }

    @Test
    fun tooFewPackageRowsAndMissingSummaryAreRejected() {
        assertRejected("ABPC0001\n${row("a")}\nsummary\t1\t0", 2)
        assertRejected("ABPC0001\n${row("a")}", 1)
    }

    @Test
    fun wrongHeaderIsRejected() {
        assertRejected("ABPC0002\nsummary\t0\t0", 0)
    }

    @Test
    fun incorrectPackageFieldCountsAreRejected() {
        assertRejected("ABPC0001\na\tb\tc\td\te\tf\tg\th\nsummary\t1\t0", 1)
        assertRejected("ABPC0001\na\tb\tc\td\te\tf\tg\th\ti\tj\nsummary\t1\t0", 1)
    }

    @Test
    fun incorrectSummaryFieldCountsAreRejected() {
        assertRejected("ABPC0001\nsummary\t0", 0)
        assertRejected("ABPC0001\nsummary\t0\t0\textra", 0)
    }

    @Test
    fun invalidExpectedCountsAreRejected() {
        val manifest = "ABPC0001\nsummary\t0\t0"

        assertRejected(manifest, -1)
        assertRejected(manifest, 513)
    }

    @Test
    fun invalidManifestSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedBuildClosureCodec.decode(ByteArray(0), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedBuildClosureCodec.decode(
                ByteArray(NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE + 1),
                0,
            )
        }
    }

    @Test
    fun exactLimitDelimiterFloodsAreRejected() {
        val limit = NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE
        val newlineFlood = "ABPC0001" + "\n".repeat(limit - 8)
        val tabFlood = "ABPC0001\n" + "\t".repeat(limit - 9)

        assertEquals(limit, newlineFlood.length)
        assertEquals(limit, tabFlood.length)
        assertRejected(newlineFlood, 0)
        assertRejected(tabFlood, 0)
    }

    private fun decode(
        manifest: String,
        expectedPackageCount: Int,
    ): VerifiedBuildClosure =
        VerifiedBuildClosureCodec.decode(manifest.toByteArray(StandardCharsets.US_ASCII), expectedPackageCount)

    private fun assertRejected(
        manifest: String,
        expectedPackageCount: Int,
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            decode(manifest, expectedPackageCount)
        }
    }

    private fun row(id: String): String = rowFields(id).joinToString("\t")

    private fun rowFields(id: String): List<String> =
        listOf(id, "name", "version", "file", "url", "10", "archive", "20", "signature")
}
