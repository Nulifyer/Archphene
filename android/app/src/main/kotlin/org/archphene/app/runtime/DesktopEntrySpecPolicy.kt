package org.archphene.app.runtime

internal fun validDesktopArgumentSpec(argumentSpec: String): Boolean {
    if (argumentSpec.isEmpty()) return true

    var tokenStart = 0
    var tokenCount = 1
    var index = 0
    while (index <= argumentSpec.length) {
        if (index == argumentSpec.length || argumentSpec[index] == '\u001f') {
            val tokenLength = index - tokenStart
            if (tokenLength == 0) return false
            val fixedArgument =
                tokenLength == 1 &&
                    when (argumentSpec[tokenStart]) {
                        'f', 'F', 'u', 'U', 'i', 'c', 'k' -> true
                        else -> false
                    }
            if (
                !fixedArgument &&
                !(
                    tokenLength > 2 &&
                        argumentSpec[tokenStart] == 'L' &&
                        argumentSpec[tokenStart + 1] == ':'
                )
            ) {
                return false
            }
            if (index == argumentSpec.length) return true
            tokenCount += 1
            if (tokenCount > 32) return false
            tokenStart = index + 1
        }
        index += 1
    }
    return true
}

internal fun validDesktopMimeSpec(mimeSpec: String): Boolean {
    if (mimeSpec.isEmpty()) return true
    if (mimeSpec[mimeSpec.lastIndex] != ';') return false

    var entryStart = 0
    var entryCount = 0
    var containsSlash = false
    var index = 0
    while (index < mimeSpec.length) {
        when (mimeSpec[index]) {
            '/' -> containsSlash = true
            ';' -> {
                if (index == entryStart || !containsSlash) return false
                entryCount += 1
                if (entryCount > 16) return false
                if (entryCount == 16 && index != mimeSpec.lastIndex) return false
                entryStart = index + 1
                containsSlash = false
            }
        }
        index += 1
    }
    return true
}
