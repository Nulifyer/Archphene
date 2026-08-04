package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AurCandidateStateCodecTest {
    @Test
    fun decodesExactlyTwoFieldsIncludingEmptyInstalledVersion() {
        assertEquals(listOf("installed", "1.2.3"), AurCandidateStateCodec.decode("installed\t1.2.3"))
        assertEquals(listOf("available", ""), AurCandidateStateCodec.decode("available\t"))
    }

    @Test
    fun preservesEmptyStateForServiceBoundaryValidation() {
        assertEquals(listOf("", "1.2.3"), AurCandidateStateCodec.decode("\t1.2.3"))
    }

    @Test
    fun rejectsEmptyUnderflowOverflowAndLineBreaks() {
        for (value in listOf("", "installed", "installed\t1\textra", "installed\t1\n", "installed\t1\r")) {
            assertNull(AurCandidateStateCodec.decode(value))
        }
    }

    @Test
    fun rejectsExact16KiBTabFloodWithoutRetainingExcessFields() {
        assertNull(AurCandidateStateCodec.decode("\t".repeat(16 * 1024)))
    }
}
