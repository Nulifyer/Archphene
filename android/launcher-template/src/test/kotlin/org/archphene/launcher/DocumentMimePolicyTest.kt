package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentMimePolicyTest {
    @Test
    fun validatesTheLauncherTrustBoundary() {
        assertEquals(
            listOf("image/png", "image/jpeg"),
            DocumentMimePolicy.parse("image/png;image/jpeg"),
        )
        assertEquals(
            listOf("text/plain"),
            DocumentMimePolicy.parse("TEXT/PLAIN"),
        )
        assertNull(DocumentMimePolicy.parse(""))
        assertNull(DocumentMimePolicy.parse("image/png;image/png"))
        assertNull(DocumentMimePolicy.parse("IMAGE/PNG;image/png"))
        assertNull(DocumentMimePolicy.parse("image/p*"))
        assertNull(DocumentMimePolicy.parse("*/png"))
        assertNull(DocumentMimePolicy.parse("image/png;"))
    }

    @Test
    fun usesTheAndroidRequiredBaseType() {
        assertEquals(
            "image/png",
            DocumentMimePolicy.androidBaseType(listOf("image/png")),
        )
        assertEquals(
            "*/*",
            DocumentMimePolicy.androidBaseType(listOf("image/png", "image/jpeg")),
        )
        assertEquals(
            "*/*",
            DocumentMimePolicy.androidBaseType(listOf("image/png", "text/plain")),
        )
    }
}
