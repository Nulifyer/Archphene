package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedChecksumOutputTest {
    @Test
    fun appendsChecksumAtExactEncodedLimit() {
        val payload = ByteArray(24) { (it * 11).toByte() }
        val encoded = encodeCrc32Bounded(32, "oversized") { output -> output.write(payload) }

        assertEquals(32, encoded.size)
        assertEquals(
            CRC32().apply { update(payload) }.value,
            ByteBuffer.wrap(encoded, payload.size, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long,
        )
    }

    @Test
    fun rejectsBodyLimitPlusOneDuringWrite() {
        assertThrows(IllegalStateException::class.java) {
            encodeCrc32Bounded(32, "oversized") { output ->
                output.write(ByteArray(25))
            }
        }
    }

    @Test
    fun checksumCoversSingleAndBulkWrites() {
        val encoded =
            encodeCrc32Bounded(32, "oversized") { output ->
                output.writeByte(0x12)
                output.write(byteArrayOf(0x34, 0x56))
            }
        val expected = CRC32().apply { update(byteArrayOf(0x12, 0x34, 0x56)) }.value
        val actual =
            ByteBuffer
                .wrap(encoded, encoded.size - Long.SIZE_BYTES, Long.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .long
        assertEquals(expected, actual)
    }

    @Test
    fun rejectsLimitsWithoutBodyCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeCrc32Bounded(Long.SIZE_BYTES, "oversized") {}
        }
    }
}
