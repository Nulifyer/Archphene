package org.archphene.app.runtime

internal object PackageSearchPageCodec {
    private const val FIELD_COUNT = 6

    fun decode(
        value: String,
        maximumRows: Int,
    ): List<List<String>> {
        require(maximumRows > 0)
        var end = value.length
        while (end > 0 && value[end - 1] == '\n') {
            end--
        }
        check(end > 0) { "Invalid native package-search response" }
        val rows = ArrayList<List<String>>(maximumRows)
        var lineStart = 0
        var index = 0
        while (index <= end) {
            val atEnd = index == end
            if (atEnd || value[index] == '\n' || value[index] == '\r') {
                check(index > lineStart) { "Invalid native package-search response" }
                check(rows.size < maximumRows) { "Native package-search response is too large" }
                rows.add(parseRow(value, lineStart, index))
                if (atEnd) {
                    break
                }
                if (value[index] == '\r' && index + 1 < end && value[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        return rows
    }

    private fun parseRow(
        value: String,
        start: Int,
        end: Int,
    ): List<String> {
        val fields = ArrayList<String>(FIELD_COUNT)
        var fieldStart = start
        while (true) {
            val delimiter = value.indexOf('\t', fieldStart).takeIf { it in fieldStart..<end }
            val fieldEnd = delimiter ?: end
            check(fields.size < FIELD_COUNT) { "Invalid native package-search response" }
            fields.add(value.substring(fieldStart, fieldEnd))
            if (delimiter == null) {
                break
            }
            fieldStart = fieldEnd + 1
        }
        check(fields.size == FIELD_COUNT) { "Invalid native package-search response" }
        return fields
    }
}
