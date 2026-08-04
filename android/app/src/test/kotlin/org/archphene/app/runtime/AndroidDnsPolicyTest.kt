package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDnsPolicyTest {
    @Test
    fun exactCandidateLimitIsAdmittedAndDeduplicated() {
        val candidates = List(32) { index -> if (index == 31) "1.1.1.1" else "" }

        val selection = selectAndroidDnsServers(candidates, 32, 4) { it }

        assertEquals(listOf("1.1.1.1"), selection.addresses)
        assertFalse(selection.truncated)
    }

    @Test
    fun candidateLimitPlusOneFailsBeforeExaminingTheExtraValue() {
        val candidates = List(33) { index -> if (index == 32) "8.8.8.8" else "" }

        val selection = selectAndroidDnsServers(candidates, 32, 4) { it }

        assertEquals(emptyList<String>(), selection.addresses)
        assertTrue(selection.truncated)
    }

    @Test
    fun fourUniqueServersStopAnOtherwiseLargeScan() {
        val candidates = List(1_000) { index -> "192.0.2.${index + 1}" }

        val selection = selectAndroidDnsServers(candidates, 32, 4) { it }

        assertEquals(
            listOf("192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4"),
            selection.addresses,
        )
        assertFalse(selection.truncated)
    }

    @Test
    fun nullEmptyAndDuplicateCandidatesDoNotConsumeServerSlots() {
        val candidates = listOf(null, "", "1.1.1.1", "1.1.1.1", "8.8.8.8")

        val selection = selectAndroidDnsServers(candidates, 32, 4) { it }

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), selection.addresses)
        assertFalse(selection.truncated)
    }
}
