package org.archphene.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageMutationRecoveryPolicyTest {
    @Test
    fun installMutationAcceptsInstallAndUpdateJobs() {
        assertTrue(
            PackageMutationRecoveryPolicy.matches(
                install = true,
                NativeRuntime.JOB_OPERATION_INSTALL,
            ),
        )
        assertTrue(
            PackageMutationRecoveryPolicy.matches(
                install = true,
                NativeRuntime.JOB_OPERATION_UPDATE,
            ),
        )
        assertFalse(
            PackageMutationRecoveryPolicy.matches(
                install = true,
                NativeRuntime.JOB_OPERATION_REMOVE,
            ),
        )
    }

    @Test
    fun removalMutationAcceptsOnlyRemovalJob() {
        assertTrue(
            PackageMutationRecoveryPolicy.matches(
                install = false,
                NativeRuntime.JOB_OPERATION_REMOVE,
            ),
        )
        assertFalse(
            PackageMutationRecoveryPolicy.matches(
                install = false,
                NativeRuntime.JOB_OPERATION_UPDATE,
            ),
        )
    }
}
