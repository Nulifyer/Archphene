package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalMimePolicyTest {
    @Test
    fun parsesBoundedUniqueMimeSpecs() {
        assertEquals(
            listOf("image/png", "image/jpeg"),
            PortalMimePolicy.parse("image/png;image/jpeg"),
        )
        assertEquals(
            listOf("text/plain"),
            PortalMimePolicy.parse("TEXT/PLAIN"),
        )
        assertEquals(listOf("*/*"), PortalMimePolicy.parse("*/*"))
        assertNull(PortalMimePolicy.parse(""))
        assertNull(PortalMimePolicy.parse("image/png;image/png"))
        assertNull(PortalMimePolicy.parse("IMAGE/PNG;image/png"))
        assertNull(PortalMimePolicy.parse("image/p*"))
        assertNull(PortalMimePolicy.parse("*/png"))
        assertNull(PortalMimePolicy.parse("image/png;"))
        assertNull(
            PortalMimePolicy.parse(
                (0..PortalMimePolicy.MAX_TYPES).joinToString(";") { index ->
                    "application/x-$index"
                },
            ),
        )
    }
}
