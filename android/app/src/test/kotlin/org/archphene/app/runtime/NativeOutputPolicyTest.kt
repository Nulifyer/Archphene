package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeOutputPolicyTest {
    @Test
    fun admitsExactCapacityAndRejectsInvalidLengths() {
        assertEquals(0, checkedNativeOutputLength(0, 16))
        assertEquals(16, checkedNativeOutputLength(16, 16))
        for ((length, capacity) in listOf(-1 to 16, 17 to 16, 0 to -1)) {
            assertThrows(IllegalStateException::class.java) {
                checkedNativeOutputLength(length, capacity)
            }
        }
    }

    @Test
    fun enforcesASmallerPolicyCeilingBeforeBufferCapacity() {
        assertEquals(8, checkedNativeOutputLength(8, 16, 8))
        for ((length, capacity, maximum) in
            listOf(
                Triple(9, 16, 8),
                Triple(0, 16, -1),
                Triple(0, 16, 17),
                Triple(0, -1, 0),
            )
        ) {
            assertThrows(IllegalStateException::class.java) {
                checkedNativeOutputLength(length, capacity, maximum)
            }
        }
    }
}
