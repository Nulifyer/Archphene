package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageJobMessagePolicyTest {
    @Test
    fun sanitizesAndBoundsWithoutProcessingAnEncodedCopy() {
        assertEquals("Package operation", boundedPackageJobMessage(""))
        assertEquals("a b c d", boundedPackageJobMessage("a\tb\rc\nd"))
        assertEquals("a".repeat(192), boundedPackageJobMessage("a".repeat(10_000)))

        val multibyte = boundedPackageJobMessage("界".repeat(1_000))
        assertEquals(64, multibyte.length)
        assertEquals(192, multibyte.toByteArray(StandardCharsets.UTF_8).size)

        val astral = boundedPackageJobMessage("😀".repeat(1_000))
        assertEquals(48, astral.codePointCount(0, astral.length))
        assertEquals(192, astral.toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(astral.length <= 192)
    }

    @Test
    fun malformedUnicodeFallsBack() {
        assertEquals("Package operation", boundedPackageJobMessage("bad\ud800"))
        assertEquals("Package operation", boundedPackageJobMessage("bad\udc00"))
    }
}
