package org.archphene.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdPolicyTest {
    @Test
    fun documentIdSegmentsAreBoundedDuringParsing() {
        assertEquals(emptyList<String>(), boundedHomeDocumentSegments("home"))

        val exact = "home/" + List(32) { index -> "directory-$index" }.joinToString("/")
        assertEquals(32, boundedHomeDocumentSegments(exact)?.size)

        val overflow = "$exact/directory-32"
        assertNull(boundedHomeDocumentSegments(overflow))

        val hostile = "home/" + "a/".repeat(509) + "a"
        assertEquals(1_024, hostile.length)
        assertNull(boundedHomeDocumentSegments(hostile))
        assertNull(boundedHomeDocumentSegments("${hostile}a"))
    }

    @Test
    fun malformedSurrogatesAreRejectedBeforePathParsing() {
        assertNull(boundedHomeDocumentSegments("home/\ud800"))
        assertNull(boundedHomeDocumentSegments("home/\udc00"))
    }

    @Test
    fun visibleNamesEnforceUtf8BoundaryWithoutEncodingCopies() {
        assertTrue(visibleName("a".repeat(255)))
        assertTrue(visibleName("é".repeat(127) + "a"))
        assertFalse(visibleName("é".repeat(128)))
        assertFalse(visibleName(".hidden"))
        assertFalse(visibleName("folder/name"))
        assertFalse(visibleName("\ud800"))
        assertFalse(visibleName("\udc00"))
    }
}
