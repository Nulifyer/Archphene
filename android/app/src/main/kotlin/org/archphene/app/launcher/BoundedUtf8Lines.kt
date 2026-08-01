package org.archphene.app.launcher

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class BoundedUtf8Line(
    val text: String,
    val truncated: Boolean,
)

internal fun drainBoundedUtf8Lines(
    input: InputStream,
    maximumLineBytes: Int,
    consume: (BoundedUtf8Line) -> Unit,
) {
    require(maximumLineBytes > 0)
    val retained = ByteArray(maximumLineBytes)
    val chunk = ByteArray(1024)
    var retainedBytes = 0
    var sawBytes = false
    var carriageReturn = false
    var truncated = false

    fun publish() {
        val text =
            if (truncated) {
                decodeLongestValidUtf8Prefix(retained, retainedBytes)
            } else {
                String(retained, 0, retainedBytes, StandardCharsets.UTF_8)
            }
        consume(
            BoundedUtf8Line(
                text,
                truncated,
            ),
        )
        retainedBytes = 0
        sawBytes = false
        truncated = false
    }

    input.use { stream ->
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            for (index in 0 until count) {
                val value = chunk[index]
                when (value) {
                    '\r'.code.toByte() -> {
                        publish()
                        carriageReturn = true
                    }
                    '\n'.code.toByte() -> {
                        if (!carriageReturn) publish()
                        carriageReturn = false
                    }
                    else -> {
                        carriageReturn = false
                        sawBytes = true
                        if (retainedBytes < retained.size) {
                            retained[retainedBytes++] = value
                        } else {
                            truncated = true
                        }
                    }
                }
            }
        }
    }
    if (sawBytes) publish()
}

private fun decodeLongestValidUtf8Prefix(
    bytes: ByteArray,
    length: Int,
): String {
    val decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    for (candidate in length downTo maxOf(0, length - 3)) {
        val decoded =
            runCatching {
                decoder.reset().decode(ByteBuffer.wrap(bytes, 0, candidate)).toString()
            }.getOrNull()
        if (decoded != null) return decoded
    }
    return ""
}
