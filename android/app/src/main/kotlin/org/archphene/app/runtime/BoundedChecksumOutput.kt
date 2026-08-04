package org.archphene.app.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

internal fun encodeCrc32Bounded(
    maximumBytes: Int,
    oversizedMessage: String,
    writeBody: (DataOutputStream) -> Unit,
): ByteArray {
    require(maximumBytes > CHECKSUM_BYTES)
    val output = ByteArrayOutputStream(minOf(maximumBytes, OUTPUT_BUFFER_BYTES))
    val checksum = CRC32()
    val body =
        DataOutputStream(
            CheckedOutputStream(
                BoundedOutputStream(
                    output,
                    maximumBytes - CHECKSUM_BYTES,
                    oversizedMessage,
                ),
                checksum,
            ),
        )
    writeBody(body)
    body.flush()
    DataOutputStream(output).writeLong(checksum.value)
    return output.toByteArray()
}

private class BoundedOutputStream(
    private val output: OutputStream,
    private val maximumBytes: Int,
    private val oversizedMessage: String,
) : OutputStream() {
    private var written = 0

    override fun write(value: Int) {
        check(written < maximumBytes) { oversizedMessage }
        output.write(value)
        written++
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        check(length <= maximumBytes - written) { oversizedMessage }
        output.write(bytes, offset, length)
        written += length
    }
}

private const val CHECKSUM_BYTES = Long.SIZE_BYTES
private const val OUTPUT_BUFFER_BYTES = 8 * 1024
