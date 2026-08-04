package org.archphene.app.runtime

import java.nio.charset.StandardCharsets

internal data class ResolvedPayload(
    val repository: String,
    val name: String,
    val version: String,
    val filename: String,
    val url: String,
    val size: Long,
)

internal object ResolvedPayloadCodec {
    private const val FIELD_COUNT = 6

    fun decode(
        bytes: ByteArray,
        maximumPackages: Int,
    ): List<ResolvedPayload> {
        require(maximumPackages in 1..512)
        val value = String(bytes, StandardCharsets.UTF_8)
        val packages = ArrayList<ResolvedPayload>(maximumPackages)
        var lineStart = 0
        var index = 0
        while (index <= value.length) {
            val atEnd = index == value.length
            if (atEnd || value[index] == '\n' || value[index] == '\r') {
                if (index > lineStart) {
                    check(packages.size < maximumPackages) { "Package closure exceeds its limit" }
                    packages.add(parseRow(value, lineStart, index))
                }
                if (atEnd) {
                    break
                }
                if (value[index] == '\r' && index + 1 < value.length && value[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        check(packages.isNotEmpty()) { "Rust returned an invalid resolution" }
        return packages
    }

    private fun parseRow(
        value: String,
        start: Int,
        end: Int,
    ): ResolvedPayload {
        val fields = arrayOfNulls<String>(FIELD_COUNT)
        var fieldStart = start
        for (fieldIndex in fields.indices) {
            val delimiter = value.indexOf('\t', fieldStart)
            val fieldEnd =
                if (fieldIndex < fields.lastIndex) {
                    check(delimiter >= fieldStart && delimiter < end) {
                        "Rust returned an invalid resolution"
                    }
                    delimiter
                } else {
                    check(delimiter < fieldStart || delimiter >= end) {
                        "Rust returned an invalid resolution"
                    }
                    end
                }
            fields[fieldIndex] = value.substring(fieldStart, fieldEnd)
            fieldStart = fieldEnd + 1
        }
        val size = fields[5]?.toLongOrNull()
        check(size != null && size > 0) { "Rust returned an invalid resolution" }
        return ResolvedPayload(
            repository = checkNotNull(fields[0]),
            name = checkNotNull(fields[1]),
            version = checkNotNull(fields[2]),
            filename = checkNotNull(fields[3]),
            url = checkNotNull(fields[4]),
            size = size,
        )
    }
}
