package org.archphene.app.runtime

internal fun hasPackageStatusLineStartingWith(
    text: String,
    prefix: String,
): Boolean = findPackageStatusLineStartingWith(text, prefix) >= 0

internal fun replaceFirstPackageStatusLineStartingWith(
    text: String,
    prefix: String,
    replacement: String,
): String {
    val lineIndex = findPackageStatusLineStartingWith(text, prefix)
    return if (lineIndex < 0) text else replacePackageStatusLine(text, lineIndex, replacement)
}

internal fun replacePackageStatusLine(
    text: String,
    lineIndex: Int,
    replacement: String,
): String {
    require(lineIndex >= 0)
    if (packageStatusLineStart(text, lineIndex) < 0) {
        return text
    }
    val output = StringBuilder(text.length - packageStatusLineLength(text, lineIndex) + replacement.length)
    var start = 0
    var currentLine = 0
    while (true) {
        val end = packageStatusLineEnd(text, start)
        if (currentLine > 0) {
            output.append('\n')
        }
        if (currentLine == lineIndex) {
            output.append(replacement)
        } else {
            output.append(text, start, end)
        }
        if (end == text.length) {
            return output.toString()
        }
        start = packageStatusNextLineStart(text, end)
        currentLine++
    }
}

private fun findPackageStatusLineStartingWith(
    text: String,
    prefix: String,
): Int {
    var start = 0
    var lineIndex = 0
    while (true) {
        val end = packageStatusLineEnd(text, start)
        if (
            prefix.length <= end - start &&
            text.regionMatches(start, prefix, 0, prefix.length)
        ) {
            return lineIndex
        }
        if (end == text.length) {
            return -1
        }
        start = packageStatusNextLineStart(text, end)
        lineIndex++
    }
}

private fun packageStatusLineStart(
    text: String,
    targetLine: Int,
): Int {
    var start = 0
    repeat(targetLine) {
        val end = packageStatusLineEnd(text, start)
        if (end == text.length) {
            return -1
        }
        start = packageStatusNextLineStart(text, end)
    }
    return start
}

private fun packageStatusLineLength(
    text: String,
    lineIndex: Int,
): Int {
    val start = packageStatusLineStart(text, lineIndex)
    return packageStatusLineEnd(text, start) - start
}

private fun packageStatusLineEnd(
    text: String,
    start: Int,
): Int {
    var index = start
    while (index < text.length && text[index] != '\r' && text[index] != '\n') {
        index++
    }
    return index
}

private fun packageStatusNextLineStart(
    text: String,
    lineEnd: Int,
): Int =
    if (text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n') {
        lineEnd + 2
    } else {
        lineEnd + 1
    }
