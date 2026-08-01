package org.archphene.launcher

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun collectBoundedRegularFiles(
    directory: File,
    namePattern: Regex,
    allowedNamePattern: Regex?,
    maximumFiles: Int,
    maximumEntries: Int,
    unsafeMessage: String,
    overflowMessage: String,
): MutableList<File> {
    val files = ArrayList<File>(maximumFiles)
    visitBoundedRegularFiles(
        directory,
        namePattern,
        allowedNamePattern,
        maximumFiles,
        maximumEntries,
        unsafeMessage,
        overflowMessage,
    ) { file -> files.add(file) }
    return files
}

internal fun visitBoundedRegularFiles(
    directory: File,
    namePattern: Regex,
    allowedNamePattern: Regex?,
    maximumFiles: Int,
    maximumEntries: Int,
    unsafeMessage: String,
    overflowMessage: String,
    visitor: (File) -> Unit,
): Int {
    require(maximumFiles > 0)
    require(maximumEntries > 0)
    var scanned = 0
    var matched = 0
    Files.newDirectoryStream(directory.toPath()).use { entries ->
        for (path in entries) {
            check(scanned < maximumEntries) { overflowMessage }
            scanned++
            val file = path.toFile()
            if (!namePattern.matches(file.name)) {
                if (allowedNamePattern != null && !allowedNamePattern.matches(file.name)) {
                    throw SecurityException(unsafeMessage)
                }
                continue
            }
            if (
                Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw SecurityException(unsafeMessage)
            }
            check(matched < maximumFiles) { overflowMessage }
            visitor(file)
            matched++
        }
    }
    return matched
}
