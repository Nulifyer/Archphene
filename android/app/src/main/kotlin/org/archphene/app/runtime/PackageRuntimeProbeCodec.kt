package org.archphene.app.runtime

internal object PackageRuntimeProbeCodec {
    private const val VERSION_MARKER = "Pacman v"

    fun firstPacmanVersion(output: String): String? {
        var lineStart = 0
        while (lineStart <= output.length) {
            var lineEnd = lineStart
            while (lineEnd < output.length && output[lineEnd] != '\n' && output[lineEnd] != '\r') {
                lineEnd++
            }
            val marker = output.indexOf(VERSION_MARKER, lineStart)
            if (marker in lineStart until lineEnd) {
                var trimmedStart = lineStart
                while (trimmedStart < lineEnd && output[trimmedStart].isWhitespace()) trimmedStart++
                var trimmedEnd = lineEnd
                while (trimmedEnd > trimmedStart && output[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                return output.substring(trimmedStart, trimmedEnd)
            }
            if (lineEnd == output.length) break
            lineStart = lineEnd + 1
            if (output[lineEnd] == '\r' && lineStart < output.length && output[lineStart] == '\n') {
                lineStart++
            }
        }
        return null
    }
}
