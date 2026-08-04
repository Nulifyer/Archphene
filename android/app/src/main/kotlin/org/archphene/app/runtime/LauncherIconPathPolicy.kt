package org.archphene.app.runtime

internal fun safeLauncherIconLogicalPath(path: String): Boolean {
    if (path.length !in 2..240 || path[0] != '/') {
        return false
    }
    var segmentStart = 1
    for (index in 1 until path.length) {
        if (path[index] == '/') {
            if (!safeLauncherIconPathSegment(path, segmentStart, index)) {
                return false
            }
            segmentStart = index + 1
        }
    }
    return safeLauncherIconPathSegment(path, segmentStart, path.length)
}

private fun safeLauncherIconPathSegment(
    path: String,
    start: Int,
    end: Int,
): Boolean =
    end > start &&
        !(end - start == 1 && path[start] == '.') &&
        !(end - start == 2 && path[start] == '.' && path[start + 1] == '.')
