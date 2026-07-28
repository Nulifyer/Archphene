package org.archphene.app

internal object TerminalKeyEncoder {
    private const val ESCAPE = 0x1b
    private const val MAX_UNICODE_CODEPOINT = 0x10ffff
    private const val REPLACEMENT_CODEPOINT = 0xfffd
    private val SURROGATE_RANGE = 0xd800..0xdfff

    fun encodeModifiedCodepoint(
        codepoint: Int,
        meta: Boolean,
        eightBitMeta: Boolean,
        output: ByteArray,
    ): Int {
        var offset = 0
        var value = codepoint
        if (meta) {
            if (eightBitMeta && value in 0..0x7f) {
                value = value or 0x80
            } else {
                output[offset++] = ESCAPE.toByte()
            }
        }
        return offset + encodeCodepoint(value, output, offset)
    }

    fun encodeCodepoint(
        codepoint: Int,
        output: ByteArray,
        offset: Int,
    ): Int {
        val value =
            if (codepoint in 0..MAX_UNICODE_CODEPOINT && codepoint !in SURROGATE_RANGE) {
                codepoint
            } else {
                REPLACEMENT_CODEPOINT
            }
        return when {
            value <= 0x7f -> {
                output[offset] = value.toByte()
                1
            }
            value <= 0x7ff -> {
                output[offset] = (0xc0 or (value shr 6)).toByte()
                output[offset + 1] = (0x80 or (value and 0x3f)).toByte()
                2
            }
            value <= 0xffff -> {
                output[offset] = (0xe0 or (value shr 12)).toByte()
                output[offset + 1] = (0x80 or (value shr 6 and 0x3f)).toByte()
                output[offset + 2] = (0x80 or (value and 0x3f)).toByte()
                3
            }
            else -> {
                output[offset] = (0xf0 or (value shr 18)).toByte()
                output[offset + 1] = (0x80 or (value shr 12 and 0x3f)).toByte()
                output[offset + 2] = (0x80 or (value shr 6 and 0x3f)).toByte()
                output[offset + 3] = (0x80 or (value and 0x3f)).toByte()
                4
            }
        }
    }
}
