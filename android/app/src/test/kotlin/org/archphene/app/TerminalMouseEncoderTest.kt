package org.archphene.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalMouseEncoderTest {
    private val output = ByteArray(24)

    @Test
    fun legacyAndUtf8ReportsAreExactAndBounded() {
        var length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_NORMAL,
                button = 0,
                x = 12,
                y = 3,
                modifiers = 0,
                release = false,
                motion = false,
            )
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, 44, 35),
            output.copyOf(length),
        )

        length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_NORMAL,
                button = 2,
                x = 12,
                y = 3,
                modifiers = 4,
                release = true,
                motion = false,
            )
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 39, 44, 35),
            output.copyOf(length),
        )
        assertEquals(
            0,
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_NORMAL,
                button = 0,
                x = 224,
                y = 3,
                modifiers = 0,
                release = false,
                motion = false,
            ),
        )

        length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_UTF8,
                button = 0,
                x = 224,
                y = 3,
                modifiers = 0,
                release = false,
                motion = false,
            )
        assertArrayEquals(
            byteArrayOf(
                0x1b,
                '['.code.toByte(),
                'M'.code.toByte(),
                32,
                0xc4.toByte(),
                0x80.toByte(),
                35,
            ),
            output.copyOf(length),
        )
    }

    @Test
    fun sgrAndUrxvtReportsRetainButtonIdentity() {
        var length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_SGR,
                button = 2,
                x = 300,
                y = 120,
                modifiers = 16,
                release = true,
                motion = false,
            )
        assertArrayEquals(
            "\u001b[<18;300;120m".encodeToByteArray(),
            output.copyOf(length),
        )

        length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_SGR_PIXELS,
                button = 0,
                x = 1080,
                y = 2202,
                modifiers = 8,
                release = false,
                motion = true,
            )
        assertArrayEquals(
            "\u001b[<40;1080;2202M".encodeToByteArray(),
            output.copyOf(length),
        )

        length =
            TerminalMouseEncoder.encode(
                output,
                TerminalMouseEncoder.ENCODING_URXVT,
                button = 0,
                x = 12,
                y = 3,
                modifiers = 0,
                release = false,
                motion = false,
            )
        assertArrayEquals(
            "\u001b[32;12;3M".encodeToByteArray(),
            output.copyOf(length),
        )
    }
}
