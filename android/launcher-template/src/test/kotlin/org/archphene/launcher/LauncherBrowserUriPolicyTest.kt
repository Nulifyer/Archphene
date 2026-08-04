package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherBrowserUriPolicyTest {
    @Test
    fun acceptsOnlySafeHttpUris() {
        assertTrue(LauncherBrowserUriPolicy.valid("http://127.0.0.1:5000/"))
        assertTrue(LauncherBrowserUriPolicy.valid("https://example.com/project#readme"))
        assertFalse(LauncherBrowserUriPolicy.valid("file:///home/archphene/secret"))
        assertFalse(LauncherBrowserUriPolicy.valid("http://user:password@example.com/"))
        assertFalse(LauncherBrowserUriPolicy.valid("http://localhost:0/"))
        assertFalse(LauncherBrowserUriPolicy.valid("http://localhost/\u0000"))
    }

    @Test
    fun enforcesUtf8BoundaryWithoutAcceptingMalformedSurrogates() {
        val prefix = "https://example.com/"
        val exactAscii = prefix + "a".repeat(LauncherBrowserUriPolicy.MAX_URI_BYTES - prefix.length)
        val exactMultibyte =
            prefix + "é".repeat((LauncherBrowserUriPolicy.MAX_URI_BYTES - prefix.length) / 2)

        assertTrue(LauncherBrowserUriPolicy.valid(exactAscii))
        assertFalse(LauncherBrowserUriPolicy.valid("${exactAscii}a"))
        assertTrue(LauncherBrowserUriPolicy.valid(exactMultibyte))
        assertFalse(LauncherBrowserUriPolicy.valid("${exactMultibyte}é"))
        assertFalse(LauncherBrowserUriPolicy.valid("${prefix}\uD800"))
        assertFalse(LauncherBrowserUriPolicy.valid("${prefix}\uDC00"))
    }
}
