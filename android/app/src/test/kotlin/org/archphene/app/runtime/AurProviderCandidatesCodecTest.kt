package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AurProviderCandidatesCodecTest {
    @Test
    fun decodesDelimiterFormsAndSkipsBlankLines() {
        assertEquals(
            listOf("first", "second", "third", "fourth"),
            AurProviderCandidatesCodec.decode("\nfirst\r\n \t\rsecond\rthird\nfourth"),
        )
        assertEquals(emptyList<String>(), AurProviderCandidatesCodec.decode("\n\r\n\t\r"))
    }

    @Test
    fun admitsExactly32CandidatesAndRejectsCandidate33() {
        val candidates = List(32) { index -> "provider-$index" }
        assertEquals(candidates, AurProviderCandidatesCodec.decode(candidates.joinToString("\n")))

        assertThrows(IllegalArgumentException::class.java) {
            AurProviderCandidatesCodec.decode((candidates + "provider-32").joinToString("\n"))
        }
    }

    @Test
    fun rejectsDuplicatesAndInvalidPackageNames() {
        for (value in listOf("same\nsame", "contains space", "x".repeat(129), "name/escape")) {
            assertThrows(IllegalArgumentException::class.java) {
                AurProviderCandidatesCodec.decode(value)
            }
        }
    }

    @Test
    fun scansExact16KiBBlankFloodWithoutRetainingRows() {
        assertEquals(emptyList<String>(), AurProviderCandidatesCodec.decode("\n".repeat(16 * 1024)))
    }
}
