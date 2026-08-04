package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedDigestTest {
    @Test
    fun hashesEmptyAndExactLimitInputs() {
        for (payload in listOf(byteArrayOf(), ByteArray(32) { it.toByte() })) {
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(payload),
                ByteArrayInputStream(payload).sha256Bounded(32, "oversized"),
            )
        }
    }

    @Test
    fun rejectsLimitPlusOneBeforeHashingPastTheCeiling() {
        assertThrows(IllegalStateException::class.java) {
            ByteArrayInputStream(ByteArray(33)).sha256Bounded(32, "oversized")
        }
    }

    @Test
    fun supportsChunkedAndZeroProgressStreams() {
        val payload = ByteArray(31) { (it * 7).toByte() }
        val input =
            object : InputStream() {
                private val source = ByteArrayInputStream(payload)
                private var returnZero = true

                override fun read(): Int = source.read()

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (returnZero) {
                        returnZero = false
                        return 0
                    }
                    returnZero = true
                    return source.read(buffer, offset, minOf(length, 3))
                }
            }
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(payload),
            input.sha256Bounded(32, "oversized"),
        )
    }

    @Test
    fun rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).sha256Bounded(0, "oversized")
        }
    }
}
