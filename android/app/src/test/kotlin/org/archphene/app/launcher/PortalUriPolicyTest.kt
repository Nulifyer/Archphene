package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalUriPolicyTest {
    @Test
    fun acceptsBoundedHttpDevelopmentUrls() {
        assertTrue(PortalUriPolicy.valid("http://127.0.0.1:5000/"))
        assertTrue(PortalUriPolicy.valid("http://localhost:8080/weather?unit=c"))
        assertTrue(PortalUriPolicy.valid("https://example.com/project#readme"))
    }

    @Test
    fun rejectsConfusedDeputyAndMalformedUris() {
        assertFalse(PortalUriPolicy.valid("file:///home/archphene/secret"))
        assertFalse(PortalUriPolicy.valid("content://provider/document/1"))
        assertFalse(PortalUriPolicy.valid("intent://example/#Intent;end"))
        assertFalse(PortalUriPolicy.valid("http://user:password@example.com/"))
        assertFalse(PortalUriPolicy.valid("http://localhost:0/"))
        assertFalse(PortalUriPolicy.valid("http://localhost:65536/"))
        assertFalse(PortalUriPolicy.valid("http://localhost/a b"))
        assertFalse(PortalUriPolicy.valid("http://localhost/\u0000"))
    }

    @Test
    fun enforcesUtf8UriSizeBoundary() {
        val prefix = "https://example.com/"
        val exactAscii = prefix + "a".repeat(PortalUriPolicy.MAX_URI_BYTES - prefix.length)
        val exactMultibyte = prefix + "é".repeat((PortalUriPolicy.MAX_URI_BYTES - prefix.length) / 2)

        assertTrue(PortalUriPolicy.valid(exactAscii))
        assertFalse(PortalUriPolicy.valid("${exactAscii}a"))
        assertTrue(PortalUriPolicy.valid(exactMultibyte))
        assertFalse(PortalUriPolicy.valid("${exactMultibyte}é"))
        assertFalse(PortalUriPolicy.valid("${prefix}\uD800"))
        assertFalse(PortalUriPolicy.valid("${prefix}\uDC00"))
    }

    @Test
    fun emitsStandardEncodedFileUrisForLogicalHomePaths() {
        assertEquals(
            "file:///home/archphene/Projects/Project%20One/%E2%9C%93.txt",
            PortalFileUri.fromLogicalPath(
                "/home/archphene/Projects/Project One/\u2713.txt",
            ),
        )
        assertEquals(
            "file:///home/archphene////...//a..//.hidden///file",
            PortalFileUri.fromLogicalPath("/home/archphene////...//a..//.hidden///file"),
        )
    }

    @Test
    fun enforcesTheExactAsciiFileUriExpansionBoundary() {
        val prefix = "/home/archphene/"
        val exactPath = prefix + "a".repeat(PortalUriPolicy.MAX_URI_BYTES - 7 - prefix.length)
        assertEquals(PortalUriPolicy.MAX_URI_BYTES, PortalFileUri.fromLogicalPath(exactPath).length)
        assertThrows(IllegalArgumentException::class.java) {
            PortalFileUri.fromLogicalPath("${exactPath}a")
        }
    }

    @Test
    fun rejectsUnboundedOrTraversingFilePaths() {
        for (
            path in listOf(
                "/tmp/file",
                "/home/archphene/../secret",
                "/home/archphene////safe//..//secret",
                "/home/archphene/a\u0000b",
                "/home/archphene/\uD800",
            )
        ) {
            assertThrows(IllegalArgumentException::class.java) {
                PortalFileUri.fromLogicalPath(path)
            }
        }
    }
}
