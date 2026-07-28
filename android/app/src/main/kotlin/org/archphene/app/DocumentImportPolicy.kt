package org.archphene.app

import java.net.URI
import java.nio.charset.StandardCharsets

internal object DocumentImportPolicy {
    const val MAX_DOCUMENTS = 32
    const val MAX_URI_BYTES = 4 * 1024

    fun normalizeContentUris(values: List<String>): List<String> {
        require(values.isNotEmpty()) { "Choose at least one Android document" }
        require(values.size <= MAX_DOCUMENTS) {
            "Choose at most $MAX_DOCUMENTS Android documents"
        }
        val result = LinkedHashSet<String>(values.size)
        values.forEach { value ->
            require(value.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_URI_BYTES) {
                "Android document URI is too large"
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
}
