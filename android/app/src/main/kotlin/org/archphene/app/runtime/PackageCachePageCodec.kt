package org.archphene.app.runtime

internal object PackageCachePageCodec {
    private const val MAX_ROWS = 32
    private const val FIELD_COUNT = 5

    fun decode(page: String, maximumRows: Int = MAX_ROWS): List<List<String>> {
        require(maximumRows in 1..MAX_ROWS) { "Package cache row bound must be in 1..$MAX_ROWS" }
        val rows = ArrayList<List<String>>(maximumRows)
        var pageEnd = page.length
        while (pageEnd > 0 && page[pageEnd - 1] == '\n') pageEnd--
        check(pageEnd > 0) { "Package cache page is empty" }

        var rowStart = 0
        while (rowStart < pageEnd) {
            check(rows.size < maximumRows) { "Package cache page exceeds its row bound" }
            val delimiter = page.indexOf('\n', rowStart)
            val rowEnd = if (delimiter < 0 || delimiter >= pageEnd) pageEnd else delimiter
            check(rowEnd > rowStart) { "Package cache page contains an empty row" }

            val fields = ArrayList<String>(FIELD_COUNT)
            var fieldStart = rowStart
            repeat(FIELD_COUNT - 1) {
                val fieldEnd = page.indexOf('\t', fieldStart)
                check(fieldEnd in fieldStart until rowEnd) {
                    "Package cache row has too few fields"
                }
                fields.add(page.substring(fieldStart, fieldEnd))
                fieldStart = fieldEnd + 1
            }
            val extraField = page.indexOf('\t', fieldStart)
            check(extraField < 0 || extraField >= rowEnd) {
                "Package cache row exceeds its field bound"
            }
            fields.add(page.substring(fieldStart, rowEnd))
            rows.add(fields)
            rowStart = rowEnd + 1
        }
        return rows
    }
}
