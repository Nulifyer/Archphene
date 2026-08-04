package org.archphene.app

import java.nio.ByteBuffer

internal fun putUtf8(
    destination: ByteBuffer,
    value: String,
    maximumBytes: Int,
): Int? {
    val encodedBytes =
        utf8EncodedLength(value, minOf(destination.capacity(), maximumBytes))
            ?: return null
    destination.clear()
    writeUtf8(destination, value)
    return encodedBytes
}

internal fun putTabSeparatedUtf8(
    destination: ByteBuffer,
    fields: Array<out String>,
    maximumBytes: Int,
): Int? = putDelimitedUtf8(destination, fields, '\t', maximumBytes)

internal fun putDelimitedUtf8(
    destination: ByteBuffer,
    fields: Array<out String>,
    delimiter: Char,
    maximumBytes: Int,
): Int? {
    val encodedBytes =
        delimitedUtf8Length(
            fields,
            delimiter,
            minOf(destination.capacity(), maximumBytes),
        ) ?: return null
    destination.clear()
    fields.forEachIndexed { index, field ->
        if (index > 0) {
            destination.put(delimiter.code.toByte())
        }
        writeUtf8(destination, field)
    }
    return encodedBytes
}

internal fun tabSeparatedUtf8Length(
    fields: Array<out String>,
    maximumBytes: Int,
): Int? = delimitedUtf8Length(fields, '\t', maximumBytes)

internal fun delimitedUtf8Length(
    fields: Array<out String>,
    delimiter: Char,
    maximumBytes: Int,
): Int? {
    if (
        maximumBytes < 0 ||
        delimiter.code !in 1..0x7f ||
        fields.isEmpty() ||
        fields.any { it.isEmpty() || delimiter in it }
    ) {
        return null
    }
    var encodedBytes = fields.size - 1
    if (encodedBytes > maximumBytes) return null
    fields.forEach { field ->
        val fieldBytes = utf8EncodedLength(field, maximumBytes - encodedBytes) ?: return null
        encodedBytes += fieldBytes
    }
    return encodedBytes
}

private fun writeUtf8(
    destination: ByteBuffer,
    value: String,
) {
    var index = 0
    while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        when {
            codePoint <= 0x7f -> destination.put(codePoint.toByte())
            codePoint <= 0x7ff -> {
                destination.put((0xc0 or (codePoint ushr 6)).toByte())
                destination.put((0x80 or (codePoint and 0x3f)).toByte())
            }
            codePoint <= 0xffff -> {
                destination.put((0xe0 or (codePoint ushr 12)).toByte())
                destination.put((0x80 or ((codePoint ushr 6) and 0x3f)).toByte())
                destination.put((0x80 or (codePoint and 0x3f)).toByte())
            }
            else -> {
                destination.put((0xf0 or (codePoint ushr 18)).toByte())
                destination.put((0x80 or ((codePoint ushr 12) and 0x3f)).toByte())
                destination.put((0x80 or ((codePoint ushr 6) and 0x3f)).toByte())
                destination.put((0x80 or (codePoint and 0x3f)).toByte())
            }
        }
        index += Character.charCount(codePoint)
    }
}
