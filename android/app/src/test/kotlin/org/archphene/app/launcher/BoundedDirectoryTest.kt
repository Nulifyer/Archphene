package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedDirectoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun visitsExactEntryLimit() {
        repeat(2) { index -> temporaryFolder.newFile("$index") }
        val visited = ArrayList<String>()

        val count =
            visitBoundedDirectoryEntries(temporaryFolder.root, 2, "overflow") { entry ->
                visited.add(entry.name)
            }

        assertEquals(2, count)
        assertEquals(setOf("0", "1"), visited.toSet())
    }

    @Test
    fun overflowStopsBeforeVisitingEntryPastLimit() {
        repeat(3) { index -> temporaryFolder.newFile("$index") }
        val visited = ArrayList<String>()

        assertThrows(IllegalStateException::class.java) {
            visitBoundedDirectoryEntries(temporaryFolder.root, 2, "overflow") { entry ->
                visited.add(entry.name)
            }
        }

        assertEquals(2, visited.size)
    }

    @Test
    fun emptyDirectoryReturnsZero() {
        assertEquals(
            0,
            visitBoundedDirectoryEntries(temporaryFolder.root, 1, "overflow") {},
        )
    }

    @Test
    fun boundedCollectionRejectsOverflowWithoutReturningPartialEntries() {
        repeat(3) { index -> temporaryFolder.newFile("$index") }

        assertThrows(IllegalStateException::class.java) {
            collectBoundedDirectoryEntries(temporaryFolder.root, 2, "overflow")
        }
    }
}
