package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AurGraphRecoveryPolicyTest {
    @Test
    fun acceptsOnlyCompleteDependencyFirstPrefixes() {
        val counts = intArrayOf(2, 1, 3)
        assertEquals(1, AurGraphRecoveryPolicy.completedBaseCount(counts, 2))
        assertEquals(2, AurGraphRecoveryPolicy.completedBaseCount(counts, 3))
        assertEquals(3, AurGraphRecoveryPolicy.completedBaseCount(counts, 6))

        for (incomplete in intArrayOf(0, 1, 4, 5, 7)) {
            assertThrows(IllegalArgumentException::class.java) {
                AurGraphRecoveryPolicy.completedBaseCount(counts, incomplete)
            }
        }
    }

    @Test
    fun rejectsEmptyBasesAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            AurGraphRecoveryPolicy.completedBaseCount(intArrayOf(2, 0, 1), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AurGraphRecoveryPolicy.completedBaseCount(intArrayOf(2, 0, 1), 3)
        }
        assertThrows(ArithmeticException::class.java) {
            AurGraphRecoveryPolicy.completedBaseCount(
                intArrayOf(Int.MAX_VALUE - 1, 2),
                Int.MAX_VALUE,
            )
        }
    }
}
