package org.archphene.app.launcher

import java.net.URI
import org.archphene.app.utf8LengthAtMost

internal object PortalUriPolicy {
    const val MAX_URI_BYTES = 4_096

    fun valid(value: String): Boolean {
        if (
            value.isBlank() ||
            !utf8LengthAtMost(value, MAX_URI_BYTES) ||
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

internal object PortalFileUri {
    private const val LINUX_HOME_PREFIX = "/home/archphene/"

    fun fromLogicalPath(path: String): String {
        require(
            path.startsWith(LINUX_HOME_PREFIX) &&
                utf8LengthAtMost(path, PortalUriPolicy.MAX_URI_BYTES) &&
                path.none { character -> character.isISOControl() } &&
                !hasTraversalComponent(path, LINUX_HOME_PREFIX.length),
        ) {
            "File portal path is not a bounded logical Archphene home path"
        }
        return URI("file", "", path, null).toASCIIString().also { uri ->
            require(uri.length <= PortalUriPolicy.MAX_URI_BYTES) {
                "File portal URI exceeds the protocol limit"
            }
        }
    }
}

private fun hasTraversalComponent(path: String, startIndex: Int): Boolean {
    var componentStart = startIndex
    var index = startIndex
    while (index <= path.length) {
        if (index == path.length || path[index] == '/') {
            val componentLength = index - componentStart
            if (
                componentLength == 1 && path[componentStart] == '.' ||
                componentLength == 2 && path[componentStart] == '.' && path[componentStart + 1] == '.'
            ) {
                return true
            }
            componentStart = index + 1
        }
        index++
    }
    return false
}
