package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSyncConflictAggregationTest {
    @Test
    fun exactConflictPathLimitIsRetainedInOrder() {
        val aggregate = linkedSetOf<String>()
        val source = List(64) { index -> "conflict-$index" }

        val truncated = mergeBoundedProjectSyncConflictPaths(aggregate, source, 64)

        assertEquals(source, aggregate.toList())
        assertFalse(truncated)
    }

    @Test
    fun limitPlusOneStopsWithoutRetainingTheExtraPath() {
        val aggregate = linkedSetOf<String>()
        val source = List(65) { index -> "conflict-$index" }

        val truncated = mergeBoundedProjectSyncConflictPaths(aggregate, source, 64)

        assertEquals(source.take(64), aggregate.toList())
        assertTrue(truncated)
    }

    @Test
    fun duplicatesDoNotConsumeAggregateCapacity() {
        val aggregate = linkedSetOf("first")

        val truncated =
            mergeBoundedProjectSyncConflictPaths(
                aggregate,
                listOf("first", "second", "second", "third"),
                3,
            )

        assertEquals(listOf("first", "second", "third"), aggregate.toList())
        assertFalse(truncated)
    }

    @Test
    fun fullAggregateAcceptsDuplicatesButRejectsANewPath() {
        val aggregate = linkedSetOf("first", "second")

        assertFalse(
            mergeBoundedProjectSyncConflictPaths(
                aggregate,
                listOf("second", "first"),
                2,
            ),
        )
        assertTrue(
            mergeBoundedProjectSyncConflictPaths(
                aggregate,
                listOf("second", "third"),
                2,
            ),
        )
        assertEquals(listOf("first", "second"), aggregate.toList())
    }
}
