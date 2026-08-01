package org.archphene.builder

import java.io.ByteArrayOutputStream
import java.io.InputStream

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
