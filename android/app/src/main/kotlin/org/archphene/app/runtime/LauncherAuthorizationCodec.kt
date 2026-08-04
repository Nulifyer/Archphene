package org.archphene.app.runtime

internal object LauncherAuthorizationCodec {
    private const val FIELD_COUNT = 6

    fun decode(value: String): List<String>? {
        val end = if (value.endsWith('\n')) value.length - 1 else value.length
        if (
            end == 0 ||
            value.indexOf('\n') in 0 until end ||
            value.indexOf('\r') in 0 until end
        ) {
            return null
        }

        val fields = ArrayList<String>(FIELD_COUNT)
        var fieldStart = 0
        repeat(FIELD_COUNT - 1) {
            val fieldEnd = value.indexOf('\t', fieldStart)
            if (fieldEnd < fieldStart || fieldEnd >= end) return null
            fields.add(value.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        val extraField = value.indexOf('\t', fieldStart)
        if (extraField >= fieldStart && extraField < end) return null
        fields.add(value.substring(fieldStart, end))
        return fields
    }
}
