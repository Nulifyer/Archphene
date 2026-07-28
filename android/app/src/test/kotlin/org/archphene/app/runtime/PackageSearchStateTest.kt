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
}
