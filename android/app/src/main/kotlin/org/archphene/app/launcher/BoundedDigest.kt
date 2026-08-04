package org.archphene.app.launcher

import java.io.InputStream
import java.security.MessageDigest

internal fun InputStream.sha256Bounded(
    maximumBytes: Int,
    oversizedMessage: String,
): ByteArray {
    require(maximumBytes > 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(minOf(maximumBytes, DIGEST_BUFFER_BYTES))
    var remaining = maximumBytes
    while (true) {
        val requested = if (remaining < buffer.size) remaining + 1 else buffer.size
        val read = read(buffer, 0, requested)
        if (read < 0) {
            return digest.digest()
        }
        if (read == 0) {
            val byte = read()
            if (byte < 0) {
                return digest.digest()
            }
            check(remaining > 0) { oversizedMessage }
            digest.update(byte.toByte())
            remaining--
            continue
        }
        check(read <= remaining) { oversizedMessage }
        digest.update(buffer, 0, read)
        remaining -= read
    }
}

private const val DIGEST_BUFFER_BYTES = 16 * 1024
