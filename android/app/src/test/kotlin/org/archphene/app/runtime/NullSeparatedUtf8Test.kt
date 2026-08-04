package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NullSeparatedUtf8Test {
    @Test
    fun encodesOnlyTheRequestedFieldsExactly() {
        val fields = listOf("id", "label", "bash", "-lc", "printf café 🚀")
        val expected =
            fields
                .drop(2)
                .joinToString("\u0000")
                .toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(expected, encodeNullSeparatedUtf8(fields, 2, expected.size))
    }

    @Test
    fun matchesJvmReplacementForMalformedSurrogates() {
        val fields = listOf("\ud800", "middle\udc00", "🚀")
        val expected = fields.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(expected, encodeNullSeparatedUtf8(fields, 0, expected.size))
    }

    @Test
    fun enforcesTheExactEncodedLimitBeforeAllocation() {
        assertArrayEquals(
            "é\u0000a".toByteArray(StandardCharsets.UTF_8),
            encodeNullSeparatedUtf8(listOf("é", "a"), 0, 4),
        )
        assertNull(encodeNullSeparatedUtf8(listOf("é", "a"), 0, 3))
        assertNull(encodeNullSeparatedUtf8(listOf("", ""), 0, 0))
    }

    @Test
    fun rejectsInvalidCallerBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeNullSeparatedUtf8(emptyList(), 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeNullSeparatedUtf8(listOf("a"), 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeNullSeparatedUtf8(listOf("a"), 0, -1)
        }
    }
}
