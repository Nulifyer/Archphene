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
    fun rejectsOversizedUris() {
        val oversized = "https://example.com/" + "a".repeat(PortalUriPolicy.MAX_URI_BYTES)
        assertFalse(PortalUriPolicy.valid(oversized))
    }

    @Test
    fun emitsStandardEncodedFileUrisForLogicalHomePaths() {
        assertEquals(
            "file:///home/archphene/Projects/Project%20One/%E2%9C%93.txt",
            PortalFileUri.fromLogicalPath(
                "/home/archphene/Projects/Project One/\u2713.txt",
            ),
        )
    }

    @Test
    fun rejectsUnboundedOrTraversingFilePaths() {
        for (path in listOf("/tmp/file", "/home/archphene/../secret", "/home/archphene/a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) {
                PortalFileUri.fromLogicalPath(path)
            }
        }
    }
}
