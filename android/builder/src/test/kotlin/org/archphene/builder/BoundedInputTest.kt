package org.archphene.builder

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputTest {
    @Test
    fun acceptsExactLimit() {
        val bytes = ByteArray(258) { index -> index.toByte() }

        assertArrayEquals(
            bytes,
            ByteArrayInputStream(bytes).readBoundedBytes(258, "oversized"),
        )
    }

    @Test
    fun rejectsLimitPlusOne() {
        val bytes = ByteArray(259)

        assertThrows(IllegalStateException::class.java) {
            ByteArrayInputStream(bytes).readBoundedBytes(258, "oversized")
        }
    }
}
