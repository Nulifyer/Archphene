package org.archphene.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentSharePolicyTest {
    @Test
    fun exact_mime_types_remain_exact() {
        assertEquals(
            "text/plain",
            DocumentSharePolicy.commonMimeType(listOf("text/plain", "text/plain")),
        )
    }

    @Test
    fun related_mime_types_share_their_top_level_type() {
        assertEquals(
            "image/*",
            DocumentSharePolicy.commonMimeType(listOf("image/png", "image/jpeg")),
        )
    }

    @Test
    fun unrelated_mime_types_use_the_general_wildcard() {
        assertEquals(
            "*/*",
            DocumentSharePolicy.commonMimeType(listOf("text/plain", "image/png")),
        )
    }

    @Test
    fun an_empty_selection_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentSharePolicy.commonMimeType(emptyList())
        }
    }
}
