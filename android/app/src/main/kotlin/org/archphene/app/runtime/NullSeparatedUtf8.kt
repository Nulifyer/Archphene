package org.archphene.app.runtime

internal fun encodeNullSeparatedUtf8(
    fields: List<String>,
    firstField: Int,
    maximumBytes: Int,
): ByteArray? {
    require(firstField in fields.indices)
    require(maximumBytes >= 0)
    var length = fields.size - firstField - 1
    if (length > maximumBytes) {
        return null
    }
    for (index in firstField until fields.size) {
        length = utf8LengthAtMost(fields[index], length, maximumBytes) ?: return null
    }
    val output = ByteArray(length)
    var offset = 0
    for (index in firstField until fields.size) {
        if (index > firstField) {
            output[offset++] = 0
        }
        offset = encodeUtf8(fields[index], output, offset)
    }
    check(offset == output.size)
    return output
}

private fun utf8LengthAtMost(
    value: String,
    initialLength: Int,
    maximumBytes: Int,
): Int? {
    var length = initialLength
    var index = 0
    while (index < value.length) {
        val character = value[index]
        val encodedBytes =
            when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                character.isSurrogate() -> 1
                else -> 3
            }
        if (length > maximumBytes - encodedBytes) {
            return null
        }
        length += encodedBytes
        index++
    }
    return length
}

private fun encodeUtf8(
    value: String,
    output: ByteArray,
    initialOffset: Int,
): Int {
    var offset = initialOffset
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            character.code <= 0x7f -> output[offset++] = character.code.toByte()
            character.code <= 0x7ff -> {
                output[offset++] = (0xc0 or (character.code shr 6)).toByte()
                output[offset++] = (0x80 or (character.code and 0x3f)).toByte()
            }
            character.isHighSurrogate() &&
                index + 1 < value.length &&
                value[index + 1].isLowSurrogate() -> {
                val codePoint = Character.toCodePoint(character, value[++index])
                output[offset++] = (0xf0 or (codePoint shr 18)).toByte()
                output[offset++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
                output[offset++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                output[offset++] = (0x80 or (codePoint and 0x3f)).toByte()
            }
            character.isSurrogate() -> output[offset++] = '?'.code.toByte()
            else -> {
                output[offset++] = (0xe0 or (character.code shr 12)).toByte()
                output[offset++] = (0x80 or ((character.code shr 6) and 0x3f)).toByte()
                output[offset++] = (0x80 or (character.code and 0x3f)).toByte()
            }
        }
        index++
    }
    return offset
}
