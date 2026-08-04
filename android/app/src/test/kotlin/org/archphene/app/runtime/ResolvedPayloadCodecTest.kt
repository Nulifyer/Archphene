package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolvedPayloadCodecTest {
    private val row = "core\tpkg\t1.0\tpkg.pkg.tar.zst\thttps://example.test/pkg\t42"

    @Test
    fun decodesRowsAndDelimiterForms() {
        val result = ResolvedPayloadCodec.decode("$row\n\n$row\r\n$row\r$row".bytes(), 4)
        assertEquals(4, result.size)
        assertEquals("pkg", result[0].name)
        assertEquals(42L, result[0].size)
    }

    @Test
    fun admitsExactPackageLimitAndRejectsTheNext() {
        assertEquals(512, ResolvedPayloadCodec.decode(List(512) { row }.joinToString("\n").bytes(), 512).size)
        assertThrows(IllegalStateException::class.java) {
            ResolvedPayloadCodec.decode(List(513) { row }.joinToString("\n").bytes(), 512)
        }
    }

    @Test
    fun rejectsEmptyMalformedAndInvalidSizeRows() {
        for (value in listOf("", "a\tb\tc\td\te", "a\tb\tc\td\te\tf\tg", "a\tb\tc\td\te\t0", "a\tb\tc\td\te\tbad")) {
            assertThrows(IllegalStateException::class.java) {
                ResolvedPayloadCodec.decode(value.bytes(), 512)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedPayloadCodec.decode(row.bytes(), 0)
        }
    }

    @Test
    fun rejects320KiBDelimiterFloodsWithoutUnboundedRows() {
        for (value in listOf("x\n".repeat(160 * 1024), "x\t".repeat(160 * 1024))) {
            assertThrows(IllegalStateException::class.java) {
                ResolvedPayloadCodec.decode(value.bytes(), 512)
            }
        }
    }

    private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
