package org.archphene.app.runtime

import java.nio.charset.StandardCharsets

internal data class VerifiedBuildClosure(
    val packageRows: List<List<String>>,
    val summary: List<String>,
)

internal object VerifiedBuildClosureCodec {
    private const val HEADER = "ABPC0001"
    private const val MAX_PACKAGE_COUNT = 512
    private const val PACKAGE_FIELD_COUNT = 9
    private const val SUMMARY_FIELD_COUNT = 3

    fun decode(
        manifest: ByteArray,
        expectedPackageCount: Int,
    ): VerifiedBuildClosure {
        require(expectedPackageCount in 0..MAX_PACKAGE_COUNT)
        require(manifest.size in 1..NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE)

        val text = String(manifest, StandardCharsets.US_ASCII)
        var headerEnd = 0
        while (headerEnd < text.length && text[headerEnd] != '\n' && text[headerEnd] != '\r') {
            headerEnd++
        }
        require(headerEnd == HEADER.length && text.regionMatches(0, HEADER, 0, HEADER.length))

        var lineStart = skipDelimiter(text, headerEnd)
        val packageRows = ArrayList<List<String>>(expectedPackageCount)
        var summary: List<String>? = null
        while (lineStart < text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n' && text[lineEnd] != '\r') {
                lineEnd++
            }
            if (lineEnd > lineStart) {
                if (packageRows.size < expectedPackageCount) {
                    packageRows.add(parseFields(text, lineStart, lineEnd, PACKAGE_FIELD_COUNT))
                } else {
                    require(summary == null)
                    summary = parseFields(text, lineStart, lineEnd, SUMMARY_FIELD_COUNT)
                }
            }
            lineStart = skipDelimiter(text, lineEnd)
        }
        require(packageRows.size == expectedPackageCount && summary != null)
        return VerifiedBuildClosure(packageRows, summary)
    }

    private fun skipDelimiter(
        text: String,
        index: Int,
    ): Int {
        if (index >= text.length) return index
        return if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            index + 2
        } else {
            index + 1
        }
    }

    private fun parseFields(
        text: String,
        start: Int,
        end: Int,
        fieldCount: Int,
    ): List<String> {
        val fields = ArrayList<String>(fieldCount)
        var fieldStart = start
        repeat(fieldCount - 1) {
            var fieldEnd = fieldStart
            while (fieldEnd < end && text[fieldEnd] != '\t') fieldEnd++
            require(fieldEnd < end)
            fields.add(text.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        var fieldEnd = fieldStart
        while (fieldEnd < end && text[fieldEnd] != '\t') fieldEnd++
        require(fieldEnd == end)
        fields.add(text.substring(fieldStart, end))
        return fields
    }
}
