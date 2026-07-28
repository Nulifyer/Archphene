package org.archphene.app

internal object TerminalMouseEncoder {
    const val ENCODING_NORMAL = 0
    const val ENCODING_UTF8 = 1
    const val ENCODING_SGR = 2
    const val ENCODING_URXVT = 3
    const val ENCODING_SGR_PIXELS = 4

    private const val ESCAPE = 0x1b
    private const val LEGACY_OFFSET = 32
    private const val LEGACY_RELEASE = 3
    private const val MOTION_FLAG = 32
    private const val MAX_LEGACY_COORDINATE = 223
    private const val MAX_UTF8_COORDINATE = 2015
    private const val MAX_REPORT_COORDINATE = 65_535
    private const val MINIMUM_DESTINATION_BYTES = 24

    fun encode(
        destination: ByteArray,
        encoding: Int,
        button: Int,
        x: Int,
        y: Int,
        modifiers: Int,
        release: Boolean,
        motion: Boolean,
    ): Int {
        if (
            destination.size < MINIMUM_DESTINATION_BYTES ||
            !(button in 0..3 || button in 64..65) ||
            x !in 1..MAX_REPORT_COORDINATE ||
            y !in 1..MAX_REPORT_COORDINATE ||
            modifiers and 0x1c != modifiers ||
            release && motion
        ) {
            return 0
        }
        val eventCode = button or modifiers or if (motion) MOTION_FLAG else 0
        return when (encoding) {
            ENCODING_NORMAL ->
                encodeLegacy(
                    destination,
                    eventCode,
                    x,
                    y,
                    release,
                    utf8 = false,
                )
            ENCODING_UTF8 ->
                encodeLegacy(
                    destination,
                    eventCode,
                    x,
                    y,
                    release,
                    utf8 = true,
                )
            ENCODING_SGR, ENCODING_SGR_PIXELS ->
                encodeDecimal(
                    destination,
                    eventCode,
                    x,
                    y,
                    release,
                    sgr = true,
                )
            ENCODING_URXVT ->
                encodeDecimal(
                    destination,
                    eventCode,
                    x,
                    y,
                    release,
                    sgr = false,
                )
            else -> 0
        }
    }

    private fun encodeLegacy(
        destination: ByteArray,
        eventCode: Int,
        x: Int,
        y: Int,
        release: Boolean,
        utf8: Boolean,
    ): Int {
        val maximum = if (utf8) MAX_UTF8_COORDINATE else MAX_LEGACY_COORDINATE
        if (x > maximum || y > maximum) {
            return 0
        }
        val encodedButton =
            (if (release) LEGACY_RELEASE or (eventCode and 0x1c) else eventCode) +
                LEGACY_OFFSET
        destination[0] = ESCAPE.toByte()
        destination[1] = '['.code.toByte()
        destination[2] = 'M'.code.toByte()
        if (!utf8) {
            if (encodedButton > 255) {
                return 0
            }
            destination[3] = encodedButton.toByte()
            destination[4] = (x + LEGACY_OFFSET).toByte()
            destination[5] = (y + LEGACY_OFFSET).toByte()
            return 6
        }
        var offset = 3
        offset += encodeCodepoint(destination, offset, encodedButton)
        offset += encodeCodepoint(destination, offset, x + LEGACY_OFFSET)
        offset += encodeCodepoint(destination, offset, y + LEGACY_OFFSET)
        return offset
    }

    private fun encodeDecimal(
        destination: ByteArray,
        eventCode: Int,
        x: Int,
        y: Int,
        release: Boolean,
        sgr: Boolean,
    ): Int {
        var offset = 0
        destination[offset++] = ESCAPE.toByte()
        destination[offset++] = '['.code.toByte()
        if (sgr) {
            destination[offset++] = '<'.code.toByte()
        }
        val encodedButton =
            if (sgr) {
                eventCode
            } else {
                (if (release) LEGACY_RELEASE or (eventCode and 0x1c) else eventCode) +
                    LEGACY_OFFSET
            }
        offset = appendDecimal(destination, offset, encodedButton)
        destination[offset++] = ';'.code.toByte()
        offset = appendDecimal(destination, offset, x)
        destination[offset++] = ';'.code.toByte()
        offset = appendDecimal(destination, offset, y)
        destination[offset++] =
            if (sgr && release) {
                'm'.code.toByte()
            } else {
                'M'.code.toByte()
            }
        return offset
    }

    private fun appendDecimal(
        destination: ByteArray,
        start: Int,
        value: Int,
    ): Int {
        var divisor = 1
        while (value / divisor >= 10) {
            divisor *= 10
        }
        var remaining = value
        var offset = start
        while (divisor != 0) {
            destination[offset++] = ('0'.code + remaining / divisor).toByte()
            remaining %= divisor
            divisor /= 10
        }
        return offset
    }

    private fun encodeCodepoint(
        destination: ByteArray,
        offset: Int,
        codepoint: Int,
    ): Int =
        when (codepoint) {
            in 0..0x7f -> {
                destination[offset] = codepoint.toByte()
                1
            }
            in 0x80..0x7ff -> {
                destination[offset] = (0xc0 or (codepoint ushr 6)).toByte()
                destination[offset + 1] = (0x80 or (codepoint and 0x3f)).toByte()
                2
            }
            else -> 0
        }
}
