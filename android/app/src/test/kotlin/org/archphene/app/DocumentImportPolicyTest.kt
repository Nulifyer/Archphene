package org.archphene.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentImportPolicyTest {
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
}
