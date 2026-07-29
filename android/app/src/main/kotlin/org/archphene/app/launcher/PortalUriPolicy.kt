package org.archphene.app.launcher

import java.net.URI
import java.nio.charset.StandardCharsets

internal object PortalUriPolicy {
    const val MAX_URI_BYTES = 4_096

    fun valid(value: String): Boolean {
        if (
            value.isBlank() ||
            value.toByteArray(StandardCharsets.UTF_8).size > MAX_URI_BYTES ||
            value.any { character -> character.isISOControl() }
        ) {
            return false
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        if (
            uri.isOpaque ||
            !scheme.equals("http", ignoreCase = true) &&
                !scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            return false
        }
        return uri.port == -1 || uri.port in 1..65_535
    }
}
