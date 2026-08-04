package org.archphene.app.runtime

import java.nio.charset.StandardCharsets

internal data class PersistedAurGraphOutput(
    val outputCount: Int,
    val rows: List<List<String>>,
)

internal object PersistedAurGraphOutputCodec {
    private const val HEADER = "ABGY0001"
    private const val MAX_OUTPUT_COUNT = 256
    private const val FIELD_COUNT = 8

    fun decode(output: ByteArray): PersistedAurGraphOutput {
        require(output.size in 1..NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE)

        val text = String(output, StandardCharsets.UTF_8)
        var contentEnd = text.length
        while (contentEnd > 0 && text[contentEnd - 1] == '\n') contentEnd--
        require(contentEnd > 0)

        var headerEnd = 0
        while (headerEnd < contentEnd && text[headerEnd] != '\n') headerEnd++
        require(headerEnd > HEADER.length + 1)
        require(text.regionMatches(0, HEADER, 0, HEADER.length))
        require(text[HEADER.length] == '\t')

        var countIndex = HEADER.length + 1
        var negative = false
        if (text[countIndex] == '+' || text[countIndex] == '-') {
            negative = text[countIndex] == '-'
            countIndex++
        }
        require(countIndex < headerEnd)
        var outputCount = 0
        while (countIndex < headerEnd) {
            val character = text[countIndex]
            require(character in '0'..'9')
            val digit = character - '0'
            require(outputCount <= (MAX_OUTPUT_COUNT - digit) / 10)
            outputCount = outputCount * 10 + digit
            countIndex++
        }
        require(!negative && outputCount in 1..MAX_OUTPUT_COUNT)

        var rowStart = if (headerEnd < contentEnd) headerEnd + 1 else contentEnd
        val rows = ArrayList<List<String>>(outputCount)
        repeat(outputCount) {
            require(rowStart < contentEnd)
            var rowEnd = rowStart
            while (rowEnd < contentEnd && text[rowEnd] != '\n') rowEnd++
            require(rowEnd > rowStart)

            val fields = ArrayList<String>(FIELD_COUNT)
            var fieldStart = rowStart
            repeat(FIELD_COUNT - 1) {
                var fieldEnd = fieldStart
                while (fieldEnd < rowEnd && text[fieldEnd] != '\t') fieldEnd++
                require(fieldEnd < rowEnd)
                fields.add(text.substring(fieldStart, fieldEnd))
                fieldStart = fieldEnd + 1
            }
            var fieldEnd = fieldStart
            while (fieldEnd < rowEnd && text[fieldEnd] != '\t') fieldEnd++
            require(fieldEnd == rowEnd)
            fields.add(text.substring(fieldStart, rowEnd))
            rows.add(fields)

            rowStart = if (rowEnd < contentEnd) rowEnd + 1 else contentEnd
        }
        require(rowStart == contentEnd)
        return PersistedAurGraphOutput(outputCount, rows)
    }
}
