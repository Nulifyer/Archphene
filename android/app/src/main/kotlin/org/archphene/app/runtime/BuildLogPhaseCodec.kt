package org.archphene.app.runtime

internal object BuildLogPhaseCodec {
    fun lastNonEmptyLine(
        value: String,
        maximumLength: Int,
    ): String? {
        require(maximumLength > 0) { "Build-log phase length must be positive" }
        var searchEnd = value.length
        while (searchEnd > 0) {
            while (searchEnd > 0 && (value[searchEnd - 1] == '\n' || value[searchEnd - 1] == '\r')) {
                searchEnd--
            }
            if (searchEnd == 0) return null

            var lineStart = searchEnd
            while (lineStart > 0 && value[lineStart - 1] != '\n' && value[lineStart - 1] != '\r') {
                lineStart--
            }
            var trimmedStart = lineStart
            while (trimmedStart < searchEnd && value[trimmedStart].isWhitespace()) trimmedStart++
            var trimmedEnd = searchEnd
            while (trimmedEnd > trimmedStart && value[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            if (trimmedStart < trimmedEnd) {
                return value.substring(trimmedStart, minOf(trimmedEnd, trimmedStart + maximumLength))
            }
            searchEnd = lineStart
        }
        return null
    }
}
