package org.archphene.app.launcher

import java.io.File
import java.nio.file.Files

internal fun collectBoundedDirectoryEntries(
    directory: File,
    maximumEntries: Int,
    overflowMessage: String,
): List<File> {
    val entries = ArrayList<File>(maximumEntries)
    visitBoundedDirectoryEntries(directory, maximumEntries, overflowMessage) { entry ->
        entries.add(entry)
    }
    return entries
}

internal fun visitBoundedDirectoryEntries(
    directory: File,
    maximumEntries: Int,
    overflowMessage: String,
    visitor: (File) -> Unit,
): Int {
    require(maximumEntries > 0)
    var count = 0
    Files.newDirectoryStream(directory.toPath()).use { entries ->
        for (path in entries) {
            check(count < maximumEntries) { overflowMessage }
            visitor(path.toFile())
            count++
        }
    }
    return count
}
