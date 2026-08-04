package org.archphene.app.launcher

internal fun safeLauncherEntryName(name: String): Boolean {
    if (name.length !in 1..240 || name[0] == '/' || name[name.lastIndex] == '/') {
        return false
    }
    var segmentStart = 0
    for (index in name.indices) {
        when (name[index]) {
            '\\' -> return false
            '/' -> {
                if (!safeLauncherEntryNameSegment(name, segmentStart, index)) {
                    return false
                }
                segmentStart = index + 1
            }
        }
    }
    return safeLauncherEntryNameSegment(name, segmentStart, name.length)
}

private fun safeLauncherEntryNameSegment(
    name: String,
    start: Int,
    end: Int,
): Boolean =
    end > start &&
        !(end - start == 1 && name[start] == '.') &&
        !(end - start == 2 && name[start] == '.' && name[start + 1] == '.')
