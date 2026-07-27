package org.archphene.app.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageRecoveryPolicyTest {
    @Test
    fun typed_preflight_storage_failure_preserves_exact_capacity_evidence() {
        val message =
            PackageFailureDiagnostics.install(
                InsufficientPackageStorageException(
                    requiredBytes = 757L * 1024 * 1024,
                    availableBytes = 454L * 1024 * 1024,
                ),
                mutationStarted = false,
                installedStateRefreshed = true,
            )

        assertEquals(
            "Not enough Linux storage: 757 MiB is required and 454 MiB is available. " +
                "Clear unrelated downloads or free Android storage, then Review.",
            message,
        )
        assertEquals(
            "Not enough Linux storage. Free space, then Review.",
            PackageFailureDiagnostics.install(
                IOException("ENOSPC"),
                mutationStarted = false,
                installedStateRefreshed = true,
            ),
        )
    }

    @Test
    fun cache_recovery_reclaims_only_packages_outside_the_current_closure() {
        assertEquals(
            listOf("old-tool", "unrelated"),
            PackageCacheRecoveryPolicy.reclaimablePackages(
                arrayOf("base", "foot", "old-tool", "unrelated"),
                setOf("base", "foot"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            PackageCacheRecoveryPolicy.reclaimablePackages(
                arrayOf("dependency", "failed-target", "old-tool"),
                setOf("dependency", "failed-target", "old-tool"),
            ),
        )
        assertEquals(
            listOf("fixture"),
            PackageCacheRecoveryPolicy.reclaimablePackages(
                arrayOf("fixture"),
                emptySet(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PackageCacheRecoveryPolicy.reclaimablePackages(
                arrayOf("foot", "base"),
                setOf("foot"),
            )
        }
    }
}
