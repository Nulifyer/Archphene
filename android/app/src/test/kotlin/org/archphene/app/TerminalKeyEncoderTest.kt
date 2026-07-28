package org.archphene.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyEncoderTest {
    @Test
    fun meta_uses_escape_prefix_until_eight_bit_mode_is_enabled() {
        val output = ByteArray(8)

        var length =
            TerminalKeyEncoder.encodeModifiedCodepoint(
                'a'.code,
                meta = true,
                eightBitMeta = false,
                output = output,
            )
        assertEquals(2, length)
        assertArrayEquals(byteArrayOf(0x1b, 'a'.code.toByte()), output.copyOf(length))

        length =
            TerminalKeyEncoder.encodeModifiedCodepoint(
                'a'.code,
                meta = true,
                eightBitMeta = true,
                output = output,
            )
        assertEquals(2, length)
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0xa1.toByte()), output.copyOf(length))
    }

    @Test
    fun eight_bit_meta_preserves_utf8_for_non_ascii_and_control_input() {
        val output = ByteArray(8)

        var length =
            TerminalKeyEncoder.encodeModifiedCodepoint(
                0x01,
                meta = true,
                eightBitMeta = true,
                output = output,
            )
        assertEquals(2, length)
        assertArrayEquals(byteArrayOf(0xc2.toByte(), 0x81.toByte()), output.copyOf(length))

        length =
            TerminalKeyEncoder.encodeModifiedCodepoint(
                0x03bb,
                meta = true,
                eightBitMeta = true,
                output = output,
            )
        assertEquals(3, length)
        assertArrayEquals(
            byteArrayOf(0x1b, 0xce.toByte(), 0xbb.toByte()),
            output.copyOf(length),
        )
    }

    @Test
    fun invalid_scalars_are_replaced_without_growth() {
        val output = ByteArray(8)
        val length = TerminalKeyEncoder.encodeCodepoint(0xd800, output, 0)

        assertEquals(3, length)
        assertArrayEquals(
            byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()),
            output.copyOf(length),
        )
    }
}
