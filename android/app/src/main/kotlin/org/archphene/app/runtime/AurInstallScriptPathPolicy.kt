package org.archphene.app.runtime

internal fun safeAurInstallScriptPath(path: String): Boolean {
    if (path.isEmpty()) {
        return false
    }
    var segmentStart = 0
    for (index in path.indices) {
        val character = path[index]
        if (character == '/') {
            if (!safeAurInstallScriptPathSegment(path, segmentStart, index)) {
                return false
            }
            segmentStart = index + 1
        } else if (!safeAurInstallScriptPathCharacter(character) || index - segmentStart >= 240) {
            return false
        }
    }
    return safeAurInstallScriptPathSegment(path, segmentStart, path.length)
}

private fun safeAurInstallScriptPathSegment(
    path: String,
    start: Int,
    end: Int,
): Boolean =
    end > start &&
        !(end - start == 1 && path[start] == '.') &&
        !(end - start == 2 && path[start] == '.' && path[start + 1] == '.')

private fun safeAurInstallScriptPathCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '@' ||
        character == '+' ||
        character == ',' ||
        character == '.' ||
        character == '_' ||
        character == '-'
