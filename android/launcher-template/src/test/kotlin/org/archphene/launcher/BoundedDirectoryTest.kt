package org.archphene.launcher

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedDirectoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun collectsOnlyMatchingRegularFilesAtExactLimit() {
        temporaryFolder.newFile("first.secret")
        temporaryFolder.newFile("second.secret")
        temporaryFolder.newFile("ignored")

        val files = collect(2)

        assertEquals(setOf("first.secret", "second.secret"), files.map { it.name }.toSet())
    }

    @Test
    fun rejectsMatchingEntryPastLimit() {
        temporaryFolder.newFile("first.secret")
        temporaryFolder.newFile("second.secret")

        assertThrows(IllegalStateException::class.java) { collect(1) }
    }

    @Test
    fun rejectsTotalDirectoryEntriesPastLimit() {
        temporaryFolder.newFile("first")
        temporaryFolder.newFile("second")

        assertThrows(IllegalStateException::class.java) { collect(2, maximumEntries = 1) }
    }

    @Test
    fun boundedDeleteMakesProgressBeforeOverflow() {
        repeat(3) { index -> temporaryFolder.newFile("$index.secret") }

        assertThrows(IllegalStateException::class.java) {
            visitBoundedRegularFiles(
                temporaryFolder.root,
                Regex(".+\\.secret"),
                null,
                2,
                3,
                "unsafe",
                "overflow",
            ) { file -> check(file.delete()) }
        }
        assertEquals(1, temporaryFolder.root.listFiles().orEmpty().size)

        visitBoundedRegularFiles(
            temporaryFolder.root,
            Regex(".+\\.secret"),
            null,
            2,
            3,
            "unsafe",
            "overflow",
        ) { file -> check(file.delete()) }
        assertEquals(0, temporaryFolder.root.listFiles().orEmpty().size)
    }

    @Test
    fun boundedDeleteRecoversAcrossAllowedNonmatchingEntries() {
        repeat(2) { index -> temporaryFolder.newFile("$index.record") }
        repeat(3) { index -> temporaryFolder.newFile("$index.temporary") }
        val temporary = Regex(".+\\.temporary")
        val allowed = Regex(".+\\.(?:record|temporary)")

        assertThrows(IllegalStateException::class.java) {
            visitBoundedRegularFiles(
                temporaryFolder.root,
                temporary,
                allowed,
                2,
                5,
                "unsafe",
                "overflow",
            ) { file -> check(file.delete()) }
        }
        assertEquals(
            1,
            temporaryFolder.root.listFiles { file -> temporary.matches(file.name) }.orEmpty().size,
        )

        visitBoundedRegularFiles(
            temporaryFolder.root,
            temporary,
            allowed,
            2,
            5,
            "unsafe",
            "overflow",
        ) { file -> check(file.delete()) }
        assertEquals(
            0,
            temporaryFolder.root.listFiles { file -> temporary.matches(file.name) }.orEmpty().size,
        )
    }

    @Test
    fun rejectsMatchingSymbolicLink() {
        val target = temporaryFolder.newFile("target")
        Files.createSymbolicLink(
            temporaryFolder.root.toPath().resolve("linked.secret"),
            target.toPath(),
        )

        assertThrows(SecurityException::class.java) { collect(1) }
    }

    @Test
    fun rejectsEntryOutsideAllowedNames() {
        temporaryFolder.newFile("unexpected")

        assertThrows(SecurityException::class.java) {
            collectBoundedRegularFiles(
                temporaryFolder.root,
                Regex(".+\\.secret"),
                Regex(".+\\.(?:secret|temporary)"),
                1,
                2,
                "unsafe",
                "overflow",
            )
        }
    }

    private fun collect(
        maximumFiles: Int,
        maximumEntries: Int = 8,
    ) =
        collectBoundedRegularFiles(
            temporaryFolder.root,
            Regex(".+\\.secret"),
            null,
            maximumFiles,
            maximumEntries,
            "unsafe",
            "overflow",
        )
}
