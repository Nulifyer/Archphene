package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilitySearchPolicyTest {
    @Test
    fun admitsExactUtf16LimitBeforeNormalization() {
        val searched = "A".repeat(1_024)

        assertEquals("a".repeat(1_024), normalizeAccessibilitySearch(searched))
    }

    @Test
    fun rejectsLimitPlusOneBeforeNormalization() {
        assertNull(normalizeAccessibilitySearch("A".repeat(1_025)))
    }

    @Test
    fun rejectsAbsentOrBlankSearches() {
        assertNull(normalizeAccessibilitySearch(null))
        assertNull(normalizeAccessibilitySearch(" \t\n"))
    }
}
