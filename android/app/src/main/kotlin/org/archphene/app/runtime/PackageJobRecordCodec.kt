package org.archphene.app.runtime

internal object PackageJobRecordCodec {
    private const val FIELD_COUNT = 9

    fun decode(record: String): List<String>? {
        var end = record.length
        while (end > 0 && record[end - 1] == '\n') end--
        if (end == 0) return null
        if (record.indexOf('\n') in 0 until end || record.indexOf('\r') in 0 until end) return null

        val fields = ArrayList<String>(FIELD_COUNT)
        var fieldStart = 0
        repeat(FIELD_COUNT - 1) {
            val fieldEnd = record.indexOf('\t', fieldStart)
            if (fieldEnd < fieldStart || fieldEnd >= end) return null
            fields.add(record.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        val extraField = record.indexOf('\t', fieldStart)
        if (extraField >= fieldStart && extraField < end) return null
        fields.add(record.substring(fieldStart, end))
        return fields
    }
}
