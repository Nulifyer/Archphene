package org.archphene.app.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.nio.file.Files
import java.nio.channels.Channels
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal fun InputStream.readBoundedBytes(
    maximumBytes: Int,
    oversizedMessage: String,
): ByteArray {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = maximumBytes
    while (true) {
        val requested = if (remaining >= buffer.size) buffer.size else remaining + 1
        val read = read(buffer, 0, requested)
        if (read < 0) return output.toByteArray()
        if (read == 0) {
            val byte = read()
            if (byte < 0) return output.toByteArray()
            check(remaining > 0) { oversizedMessage }
            output.write(byte)
            remaining--
            continue
        }
        check(read <= remaining) { oversizedMessage }
        output.write(buffer, 0, read)
        remaining -= read
    }
}

private const val READ_BUFFER_BYTES = 8 * 1024

internal fun readRecoveredAtomicBytes(
    file: File,
    maximumBytes: Int,
    oversizedMessage: String,
): ByteArray? {
    val base = file.toPath()
    val backup = File(file.path + ".bak").toPath()
    val candidate = if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) backup else base
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null
    check(
        Files.readAttributes(
            candidate,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).isRegularFile,
    ) {
        "Atomic input is not a regular file"
    }
    if (candidate == backup) {
        Files.move(
            backup,
            base,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
    val channel =
        Files.newByteChannel(
            base,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
    return Channels.newInputStream(channel).use { stream ->
        stream.readBoundedBytes(maximumBytes, oversizedMessage)
    }
}
