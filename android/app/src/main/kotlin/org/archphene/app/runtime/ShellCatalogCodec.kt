package org.archphene.app.runtime

internal object ShellCatalogCodec {
    private const val MAX_ROWS = 8
    private const val MIN_FIELDS = 3
    private const val MAX_FIELDS = 7
    private const val MAX_FIELD_LENGTH = 64

    fun decode(catalog: String): List<List<String>> {
        val rows = ArrayList<List<String>>(MAX_ROWS)
        var lineStart = 0
        var index = 0
        while (index <= catalog.length) {
            val atEnd = index == catalog.length
            if (atEnd || catalog[index] == '\n' || catalog[index] == '\r') {
                if (index > lineStart) {
                    check(rows.size < MAX_ROWS) { "Installed shell catalog is too large" }
                    rows.add(parseRow(catalog, lineStart, index))
                }
                if (atEnd) break
                if (catalog[index] == '\r' && index + 1 < catalog.length && catalog[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        check(rows.isNotEmpty()) { "Installed shell catalog is invalid" }
        return rows
    }

    private fun parseRow(
        catalog: String,
        start: Int,
        end: Int,
    ): List<String> {
        val fields = ArrayList<String>(MAX_FIELDS)
        var fieldStart = start
        while (true) {
            var fieldEnd = fieldStart
            while (fieldEnd < end && catalog[fieldEnd] != '\t') {
                val character = catalog[fieldEnd]
                check(character.code in 0x20..0x7e) { "Installed shell catalog is invalid" }
                check(fields.size < 2 || character != ' ') { "Installed shell catalog is invalid" }
                fieldEnd++
                check(fieldEnd - fieldStart <= MAX_FIELD_LENGTH) {
                    "Installed shell catalog is invalid"
                }
            }
            check(fieldEnd > fieldStart) { "Installed shell catalog is invalid" }
            check(fields.size < MAX_FIELDS) { "Installed shell catalog is invalid" }
            fields.add(catalog.substring(fieldStart, fieldEnd))
            if (fieldEnd == end) break
            check(fields.size < MAX_FIELDS) { "Installed shell catalog is invalid" }
            fieldStart = fieldEnd + 1
        }
        check(fields.size >= MIN_FIELDS) { "Installed shell catalog is invalid" }
        return fields
    }
}
