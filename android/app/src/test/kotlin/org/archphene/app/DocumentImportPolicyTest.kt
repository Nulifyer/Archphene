package org.archphene.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImportPolicyTest {
    @Test
    fun boundedUtf8TextRejectsOverflowAndMalformedSurrogates() {
        assertTrue(boundedUtf8Text("a".repeat(255), 255))
        assertTrue(boundedUtf8Text("é".repeat(127) + "a", 255))
        assertFalse(boundedUtf8Text("é".repeat(128), 255))
        assertFalse(boundedUtf8Text("", 255))
        assertFalse(boundedUtf8Text("\uD800", 255))
        assertFalse(boundedUtf8Text("\uDC00", 255))
        assertTrue(utf8LengthAtMost("", 0))
        assertFalse(utf8LengthAtMost("a", 0))
        assertFalse(utf8LengthAtMost("", -1))
    }

    @Test
    fun utf8LengthAtMostEnforcesAccessibilityBoundary() {
        assertTrue(utf8LengthAtMost("a".repeat(16_384), 16_384))
        assertFalse(utf8LengthAtMost("a".repeat(16_385), 16_384))
        assertTrue(utf8LengthAtMost("😀".repeat(4_096), 16_384))
        assertFalse(utf8LengthAtMost("😀".repeat(4_097), 16_384))
    }

    @Test
    fun exactUtf8LengthRejectsOverflowAndMalformedUnicode() {
        assertEquals(0, utf8EncodedLength(""))
        assertEquals(6, utf8EncodedLength("aé€"))
        assertEquals(4, utf8EncodedLength("😀"))
        assertEquals(4, utf8EncodedLength("😀", 4))
        assertEquals(null, utf8EncodedLength("😀", 3))
        assertEquals(null, utf8EncodedLength("\ud800"))
        assertEquals(null, utf8EncodedLength("\udc00"))
        assertEquals(null, utf8EncodedLength("", -1))
    }

    @Test
    fun boundedUtf8TextEnforcesFolderLabelBoundary() {
        assertTrue(boundedUtf8Text("a".repeat(128), 128))
        assertTrue(boundedUtf8Text("é".repeat(64), 128))
        assertFalse(boundedUtf8Text("a".repeat(129), 128))
        assertFalse(boundedUtf8Text("é".repeat(65), 128))
    }

    @Test
    fun aggregate_batch_admission_is_bounded_before_collection() {
        assertEquals(true, DocumentImportPolicy.admitsAdditionalDocuments(0, 32))
        assertEquals(true, DocumentImportPolicy.admitsAdditionalDocuments(1, 31))
        assertEquals(false, DocumentImportPolicy.admitsAdditionalDocuments(1, 32))
        assertEquals(false, DocumentImportPolicy.admitsAdditionalDocuments(32, 1))
        assertEquals(false, DocumentImportPolicy.admitsAdditionalDocuments(0, 33))
        assertEquals(false, DocumentImportPolicy.admitsAdditionalDocuments(-1, 1))
        assertEquals(false, DocumentImportPolicy.admitsAdditionalDocuments(0, -1))
    }

    @Test
    fun content_uris_are_deduplicated_in_selection_order() {
        assertEquals(
            listOf("content://files/one", "content://files/two"),
            DocumentImportPolicy.normalizeContentUris(
                listOf(
                    "content://files/one",
                    "content://files/two",
                    "content://files/one",
                ),
            ),
        )
    }

    @Test
    fun empty_and_oversized_batches_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentImportPolicy.normalizeContentUris(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentImportPolicy.normalizeContentUris(
                List(DocumentImportPolicy.MAX_DOCUMENTS + 1) {
                    "content://files/$it"
                },
            )
        }
    }

    @Test
    fun non_content_and_oversized_uris_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentImportPolicy.normalizeContentUris(listOf("file:///tmp/unsafe"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentImportPolicy.normalizeContentUris(
                listOf("content://files/" + "x".repeat(DocumentImportPolicy.MAX_URI_BYTES)),
            )
        }
    }

    @Test
    fun uriTextBoundAcceptsExactMultibyteLimitWithoutEncodingCopy() {
        val exact = "content://files/" + "€".repeat(1_360)

        assertEquals(4_096, exact.toByteArray(Charsets.UTF_8).size)
        assertEquals(true, DocumentImportPolicy.boundedDocumentUriText(exact))
        assertEquals(false, DocumentImportPolicy.boundedDocumentUriText(exact + "a"))
    }

    @Test
    fun uriTextBoundRejectsEmptyAndMalformedUnicode() {
        assertEquals(false, DocumentImportPolicy.boundedDocumentUriText(""))
        assertEquals(
            false,
            DocumentImportPolicy.boundedDocumentUriText("content://files/broken\ud800path"),
        )
        assertEquals(
            false,
            DocumentImportPolicy.boundedDocumentUriText("content://files/broken\udc00path"),
        )
    }
}
