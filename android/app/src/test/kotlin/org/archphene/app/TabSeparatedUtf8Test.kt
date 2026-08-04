package org.archphene.app

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabSeparatedUtf8Test {
    @Test
    fun writesOneFieldDirectlyWithoutEncodedArray() {
        val value = "package-é-\ud83d\ude80"
        val expected = value.toByteArray(StandardCharsets.UTF_8)
        val destination = ByteBuffer.allocateDirect(expected.size)

        assertEquals(expected.size, putUtf8(destination, value, expected.size))
        val actual = ByteArray(expected.size)
        destination.position(0)
        destination.get(actual)
        assertArrayEquals(expected, actual)
        assertNull(putUtf8(destination, value, expected.size - 1))
        assertNull(putUtf8(destination, "broken\ud800", expected.size))
        val unchanged = ByteBuffer.allocate(2).apply { put(0x5a.toByte()) }
        assertNull(putUtf8(unchanged, "abc", 3))
        assertEquals(1, unchanged.position())
        assertEquals(0x5a, unchanged.get(0).toInt())
    }

    @Test
    fun writesExactStandardUtf8ToDirectBuffer() {
        val fields = arrayOf("home", "notes/é.txt", "rocket-\ud83d\ude80")
        val expected = fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
        val destination = ByteBuffer.allocateDirect(expected.size)

        assertEquals(expected.size, tabSeparatedUtf8Length(fields, expected.size))
        assertEquals(expected.size, putTabSeparatedUtf8(destination, fields, expected.size))
        val actual = ByteArray(expected.size)
        destination.position(0)
        destination.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun rejectsBoundsAndInvalidFieldsWithoutMutatingDestination() {
        val destination = ByteBuffer.allocate(8).apply { put(0x5a.toByte()) }
        assertNull(putTabSeparatedUtf8(destination, arrayOf("123456789"), 8))
        assertNull(tabSeparatedUtf8Length(arrayOf("123456789"), 8))
        assertEquals(1, destination.position())
        assertEquals(0x5a, destination.get(0).toInt())
        assertNull(putTabSeparatedUtf8(destination, emptyArray(), 8))
        assertNull(putTabSeparatedUtf8(destination, arrayOf(""), 8))
        assertNull(putTabSeparatedUtf8(destination, arrayOf("a\tb"), 8))
        assertNull(putTabSeparatedUtf8(destination, arrayOf("broken\ud800"), 8))
        assertNull(putTabSeparatedUtf8(destination, arrayOf("a"), -1))
    }

    @Test
    fun supportsBoundedNewlineSeparatedFields() {
        val fields = arrayOf("alpha", "beta", "package-é")
        val expected = fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val destination = ByteBuffer.allocateDirect(expected.size)

        assertEquals(expected.size, delimitedUtf8Length(fields, '\n', expected.size))
        assertEquals(
            expected.size,
            putDelimitedUtf8(destination, fields, '\n', expected.size),
        )
        val actual = ByteArray(expected.size)
        destination.position(0)
        destination.get(actual)
        assertArrayEquals(expected, actual)
        assertNull(delimitedUtf8Length(fields, '\n', expected.size - 1))
        assertNull(delimitedUtf8Length(arrayOf("bad\nfield"), '\n', 32))
        assertNull(delimitedUtf8Length(arrayOf("field"), 'é', 32))
    }
}
