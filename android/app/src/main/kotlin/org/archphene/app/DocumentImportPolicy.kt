package org.archphene.app

import java.net.URI

internal object DocumentImportPolicy {
    const val MAX_DOCUMENTS = 32
    const val MAX_URI_BYTES = 4 * 1024

    fun admitsAdditionalDocuments(
        currentCount: Int,
        additionalCount: Int,
    ): Boolean =
        currentCount in 0..MAX_DOCUMENTS &&
            additionalCount >= 0 &&
            additionalCount <= MAX_DOCUMENTS - currentCount

    fun normalizeContentUris(values: List<String>): List<String> {
        require(values.isNotEmpty()) { "Choose at least one Android document" }
        require(values.size <= MAX_DOCUMENTS) {
            "Choose at most $MAX_DOCUMENTS Android documents"
        }
        val result = LinkedHashSet<String>(values.size)
        values.forEach { value ->
            require(boundedDocumentUriText(value)) {
                "Android document URI is too large or malformed"
            }
            val parsed =
                runCatching { URI(value) }.getOrNull()
                    ?: throw IllegalArgumentException("Android document URI is invalid")
            require(parsed.scheme == "content" && !parsed.rawAuthority.isNullOrEmpty()) {
                "Choose documents supplied by Android Files"
            }
            result += value
        }
        return result.toList()
    }

    internal fun boundedDocumentUriText(value: String): Boolean {
        return boundedUtf8Text(value, MAX_URI_BYTES)
    }
}

internal fun boundedUtf8Text(
    value: String,
    maximumBytes: Int,
): Boolean = value.isNotEmpty() && utf8LengthAtMost(value, maximumBytes)

internal fun utf8LengthAtMost(
    value: String,
    maximumBytes: Int,
): Boolean = utf8EncodedLength(value, maximumBytes) != null

internal fun utf8EncodedLength(
    value: String,
    maximumBytes: Int = Int.MAX_VALUE,
): Int? {
    if (maximumBytes < 0) return null
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (
            Character.isHighSurrogate(character) &&
            (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))
        ) {
            return null
        }
        if (Character.isLowSurrogate(character)) {
            return null
        }
        val codePoint = Character.codePointAt(value, index)
        val codePointBytes =
            when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
        if (codePointBytes > maximumBytes - bytes) {
            return null
        }
        bytes += codePointBytes
        index += Character.charCount(codePoint)
    }
    return bytes
}
