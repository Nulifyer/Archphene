package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
            )

        assertEquals("installed", updated.installStates.single())
        assertEquals("50.0-3", updated.installedVersions.single())
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
            ),
        )
    }
}
