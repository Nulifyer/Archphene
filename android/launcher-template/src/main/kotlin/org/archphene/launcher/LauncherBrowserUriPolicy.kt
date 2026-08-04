package org.archphene.launcher

import java.net.URI

internal object LauncherBrowserUriPolicy {
    const val MAX_URI_BYTES = 4_096

    fun valid(value: String): Boolean {
        if (
            value.isBlank() ||
            !hasValidUtf8Size(value) ||
            value.any { character -> character.isISOControl() }
        ) {
            return false
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        return !uri.isOpaque &&
            (
                scheme.equals("http", ignoreCase = true) ||
                    scheme.equals("https", ignoreCase = true)
            ) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    }

    private fun hasValidUtf8Size(value: String): Boolean {
        var byteCount = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            byteCount +=
                when {
                    character.code <= 0x7f -> 1
                    character.code <= 0x7ff -> 2
                    character.isHighSurrogate() -> {
                        if (
                            index + 1 >= value.length ||
                            !value[index + 1].isLowSurrogate()
                        ) {
                            return false
                        }
                        index++
                        4
                    }
                    character.isLowSurrogate() -> return false
                    else -> 3
                }
            if (byteCount > MAX_URI_BYTES) return false
            index++
        }
        return true
    }
}
