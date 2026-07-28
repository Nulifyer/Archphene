package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageSearchStateTest {
    private fun snapshot(
        state: String,
        installedVersion: String,
    ): AvailablePackageSnapshot =
        AvailablePackageSnapshot(
            arrayOf("extra"),
            arrayOf("libsysprof-capture"),
            arrayOf("50.0-3"),
            arrayOf("Capture library"),
            arrayOf(state),
            arrayOf(installedVersion),
            intArrayOf(4),
            booleanArrayOf(true),
            "1 official package matches",
            7,
        )

    @Test
    fun updateReconcilesAStaleDifferentResult() {
        val updated =
            reconcileAvailablePackageInstalledVersion(
                snapshot("different", "50.0-2.1"),
                "libsysprof-capture",
                "50.0-3",
                4,
                true,
            )

        assertEquals("installed", updated.installStates.single())
        assertEquals("50.0-3", updated.installedVersions.single())
        assertEquals(4, updated.installedCapabilities.single())
        assertEquals(true, updated.installedCapabilitiesAnalyzed.single())
        assertEquals(8, updated.revision)
    }

    @Test
    fun removalReconcilesAnInstalledResult() {
        val updated =
            reconcileAvailablePackageInstalledVersion(
                snapshot("installed", "50.0-3"),
                "libsysprof-capture",
                "",
            )

        assertEquals("available", updated.installStates.single())
        assertEquals("", updated.installedVersions.single())
        assertEquals(0, updated.installedCapabilities.single())
        assertEquals(false, updated.installedCapabilitiesAnalyzed.single())
        assertEquals(8, updated.revision)
    }

    @Test
    fun unrelatedAndAlreadyCurrentResultsDoNotAllocateSnapshots() {
        val current = snapshot("installed", "50.0-3")
        assertSame(
            current,
            reconcileAvailablePackageInstalledVersion(current, "different-package", "1"),
        )
        assertSame(
            current,
            reconcileAvailablePackageInstalledVersion(
                current,
                "libsysprof-capture",
                "50.0-3",
                4,
                true,
            ),
        )
    }

    @Test
    fun reviewedAurResultJoinsOfficialRowsWithVersionAndInstalledClass() {
        val previous =
            AvailablePackageSnapshot(
                arrayOf("extra", "aur"),
                arrayOf("code", "old-aur"),
                arrayOf("1.0-1", "0.1-1"),
                arrayOf("Official editor", "Stale community row"),
                arrayOf("available", "available"),
                arrayOf("", ""),
                intArrayOf(0, 0),
                booleanArrayOf(false, false),
                "stale",
                11,
            )
        val installed =
            InstalledPackageSnapshot(
                arrayOf("visual-studio-code-bin"),
                arrayOf("1.90.0-1"),
                booleanArrayOf(true),
                intArrayOf(3),
                booleanArrayOf(true),
                "ready",
                1,
            )

        val merged =
            mergeReviewedAurPackage(
                previous,
                installed,
                "visual-studio-code-bin",
                "1.91.0-1",
                "Visual Studio Code",
                "update",
                "1.90.0-1",
            )

        assertEquals(listOf("extra", "aur"), merged.repositories.toList())
        assertEquals(listOf("code", "visual-studio-code-bin"), merged.names.toList())
        assertEquals("update", merged.installStates.last())
        assertEquals("1.90.0-1", merged.installedVersions.last())
        assertEquals(3, merged.installedCapabilities.last())
        assertEquals(true, merged.installedCapabilitiesAnalyzed.last())
        assertEquals("1 official package · 1 reviewed AUR result", merged.status)
        assertEquals(12, merged.revision)
    }

    @Test
    fun reviewedAurResultIsBoundedAndRejectsContradictoryState() {
        val names = Array(100) { index -> "package-$index" }
        val previous =
            AvailablePackageSnapshot(
                Array(100) { "extra" },
                names,
                Array(100) { "1-1" },
                Array(100) { "" },
                Array(100) { "available" },
                Array(100) { "" },
                IntArray(100),
                BooleanArray(100),
                "full",
                4,
            )
        val installed =
            InstalledPackageSnapshot(
                emptyArray(),
                emptyArray(),
                BooleanArray(0),
                IntArray(0),
                BooleanArray(0),
                "empty",
                1,
            )
        val merged =
            mergeReviewedAurPackage(
                previous,
                installed,
                "community-package",
                "2-1",
                "",
                "available",
                "",
            )
        assertEquals(100, merged.names.size)
        assertEquals("package-98", merged.names[98])
        assertEquals("community-package", merged.names.last())
        assertEquals("aur", merged.repositories.last())

        assertThrows(IllegalArgumentException::class.java) {
            mergeReviewedAurPackage(
                previous,
                installed,
                "community-package",
                "2-1",
                "",
                "installed",
                "1-1",
            )
        }
    }
}
