package org.archphene.app.runtime

internal data class DesktopEntryPage(
    val header: List<String>,
    val rows: List<List<String>>,
)

internal object DesktopEntryPageCodec {
    private const val HEADER_FIELD_COUNT = 6
    private const val ROW_FIELD_COUNT = 10
    private const val MAX_ROWS = 256

    fun decode(page: String): DesktopEntryPage {
        check(page.isNotEmpty() && page[page.lastIndex] == '\n') {
            "Desktop-entry page is not terminated"
        }

        val headerEnd = page.indexOf('\n')
        val header = parseFields(page, 0, headerEnd, HEADER_FIELD_COUNT, "header")
        val rows = ArrayList<List<String>>(MAX_ROWS)
        var rowStart = headerEnd + 1
        while (rowStart < page.length) {
            check(rows.size < MAX_ROWS) { "Desktop-entry page exceeds its row bound" }
            val rowEnd = page.indexOf('\n', rowStart)
            check(rowEnd > rowStart) { "Desktop-entry page contains an empty row" }
            rows.add(parseFields(page, rowStart, rowEnd, ROW_FIELD_COUNT, "row"))
            rowStart = rowEnd + 1
        }
        return DesktopEntryPage(header, rows)
    }

    private fun parseFields(
        page: String,
        start: Int,
        end: Int,
        fieldCount: Int,
        kind: String,
    ): List<String> {
        val fields = ArrayList<String>(fieldCount)
        var fieldStart = start
        repeat(fieldCount - 1) {
            val fieldEnd = page.indexOf('\t', fieldStart)
            check(fieldEnd in fieldStart until end) {
                "Desktop-entry $kind has too few fields"
            }
            fields.add(page.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        val extraField = page.indexOf('\t', fieldStart)
        check(extraField < 0 || extraField >= end) {
            "Desktop-entry $kind exceeds its field bound"
        }
        fields.add(page.substring(fieldStart, end))
        return fields
    }
}
