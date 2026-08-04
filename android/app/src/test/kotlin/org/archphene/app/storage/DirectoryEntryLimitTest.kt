package org.archphene.app.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectoryEntryLimitTest {
    @Test
    fun admitsExactPhysicalLimitWhenAllEntriesAreHidden() {
        val root = Files.createTempDirectory("archphene-directory-limit")
        val directory = Files.createDirectory(root.resolve("listing"))
        try {
            repeat(4095) { index ->
                Files.createFile(directory.resolve(".$index"))
            }
            val target = Files.createFile(root.resolve("target"))
            Files.createSymbolicLink(directory.resolve("hidden-link"), target)

            assertEquals(0, directory.readVisibleDirectoryEntries(4096).size)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun rejectsPhysicalLimitPlusOneBeforeFiltering() {
        val directory = Files.createTempDirectory("archphene-directory-limit")
        try {
            repeat(4097) { index ->
                Files.createFile(directory.resolve(".$index"))
            }

            val error =
                assertThrows(DirectoryEntryLimitExceededException::class.java) {
                    directory.readVisibleDirectoryEntries(4096)
                }
            assertEquals(DirectoryEntryLimit.PHYSICAL, error.limit)
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun rejectsVisibleLimitPlusOne() {
        val directory = Files.createTempDirectory("archphene-directory-limit")
        try {
            repeat(4096) { index ->
                Files.createFile(directory.resolve("visible-$index"))
            }

            val error =
                assertThrows(DirectoryEntryLimitExceededException::class.java) {
                    directory.readVisibleDirectoryEntries(4095)
                }
            assertEquals(DirectoryEntryLimit.VISIBLE, error.limit)
        } finally {
            deleteRecursively(directory)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
