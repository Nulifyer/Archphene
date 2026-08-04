package org.archphene.app.runtime

internal data class LauncherRegistryPage(
    val header: List<String>,
    val rows: List<List<String>>,
)

internal object LauncherRegistryPageCodec {
    private const val HEADER_FIELD_COUNT = 3
    private const val ROW_FIELD_COUNT = 8
    private const val MAX_ROWS = 256

    fun decode(page: String): LauncherRegistryPage {
        var pageEnd = page.length
        while (pageEnd > 0 && page[pageEnd - 1] == '\n') pageEnd--
        check(pageEnd > 0) { "Launcher registry page is empty" }

        val headerDelimiter = page.indexOf('\n')
        val headerEnd = if (headerDelimiter < 0 || headerDelimiter >= pageEnd) pageEnd else headerDelimiter
        check(headerEnd > 0) { "Launcher registry header is empty" }
        val header = parseFields(page, 0, headerEnd, HEADER_FIELD_COUNT, "header")

        val rows = ArrayList<List<String>>(MAX_ROWS)
        var rowStart = headerEnd + 1
        while (rowStart < pageEnd) {
            check(rows.size < MAX_ROWS) { "Launcher registry page exceeds its row bound" }
            val delimiter = page.indexOf('\n', rowStart)
            val rowEnd = if (delimiter < 0 || delimiter >= pageEnd) pageEnd else delimiter
            check(rowEnd > rowStart) { "Launcher registry page contains an empty row" }
            rows.add(parseFields(page, rowStart, rowEnd, ROW_FIELD_COUNT, "row"))
            rowStart = rowEnd + 1
        }
        return LauncherRegistryPage(header, rows)
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
                "Launcher registry $kind has too few fields"
            }
            fields.add(page.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        val extraField = page.indexOf('\t', fieldStart)
        check(extraField < 0 || extraField >= end) {
            "Launcher registry $kind exceeds its field bound"
        }
        fields.add(page.substring(fieldStart, end))
        return fields
    }
}
