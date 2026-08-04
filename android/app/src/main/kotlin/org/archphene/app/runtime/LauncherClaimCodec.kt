package org.archphene.app.runtime

internal object LauncherClaimCodec {
    private const val REMOVAL_FIELD_COUNT = 3
    private const val PUBLICATION_FIELD_COUNT = 9

    fun decodeRemoval(value: String): List<String>? = decode(value, REMOVAL_FIELD_COUNT)

    fun decodePublication(value: String): List<String>? = decode(value, PUBLICATION_FIELD_COUNT)

    private fun decode(
        value: String,
        fieldCount: Int,
    ): List<String>? {
        var end = value.length
        while (end > 0 && value[end - 1] == '\n') end--
        if (end == 0 || value.indexOf('\n') in 0 until end || value.indexOf('\r') in 0 until end) {
            return null
        }

        val fields = ArrayList<String>(fieldCount)
        var fieldStart = 0
        repeat(fieldCount - 1) {
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
