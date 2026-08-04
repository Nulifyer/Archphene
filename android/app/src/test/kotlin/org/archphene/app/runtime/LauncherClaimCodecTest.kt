package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherClaimCodecTest {
    @Test
    fun decodesExactRemovalAndPublicationClaims() {
        val removal = listOf("R1", "org.archphene.linux.p123", "42")
        val publication =
            listOf("W4", "package", "descriptor", "7", "label", "capabilities", "command", "digest", "text/plain;")

        assertEquals(removal, LauncherClaimCodec.decodeRemoval(removal.joinToString("\t")))
        assertEquals(removal, LauncherClaimCodec.decodeRemoval(removal.joinToString("\t") + "\n\n"))
        assertEquals(publication, LauncherClaimCodec.decodePublication(publication.joinToString("\t")))
        assertEquals(publication, LauncherClaimCodec.decodePublication(publication.joinToString("\t") + "\n"))
    }

    @Test
    fun preservesEmptyFieldsWithinEachExactSchema() {
        assertEquals(listOf("R1", "", "1"), LauncherClaimCodec.decodeRemoval("R1\t\t1"))
        assertEquals(
            listOf("W4", "", "", "", "", "", "", "", ""),
            LauncherClaimCodec.decodePublication("W4\t\t\t\t\t\t\t\t"),
        )
    }

    @Test
    fun rejectsEmptyUnderflowOverflowAndInternalLineBreaks() {
        for (value in listOf("", "\n", "R1\tpackage", "R1\tpackage\t1\textra", "R1\tpackage\t1\r")) {
            assertNull(LauncherClaimCodec.decodeRemoval(value))
        }
        for (value in listOf(
            "W4\t1\t2\t3\t4\t5\t6\t7",
            "W4\t1\t2\t3\t4\t5\t6\t7\t8\t9",
            "W4\t1\t2\t3\t4\t5\t6\t7\t8\n9",
        )) {
            assertNull(LauncherClaimCodec.decodePublication(value))
        }
    }

    @Test
    fun rejectsExactFourKiBTabFloodBeforeRetainingExcessFields() {
        val flood = "\t".repeat(4 * 1024)

        assertNull(LauncherClaimCodec.decodeRemoval(flood))
        assertNull(LauncherClaimCodec.decodePublication(flood))
    }
}
