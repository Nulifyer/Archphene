package org.archphene.app.runtime

internal object PackageMutationRecoveryPolicy {
    fun defaultOperation(install: Boolean): Int =
        if (install) {
            NativeRuntime.JOB_OPERATION_INSTALL
        } else {
            NativeRuntime.JOB_OPERATION_REMOVE
        }

    fun matches(
        install: Boolean,
        operation: Int,
    ): Boolean =
        if (install) {
            operation == NativeRuntime.JOB_OPERATION_INSTALL ||
                operation == NativeRuntime.JOB_OPERATION_UPDATE
        } else {
            operation == NativeRuntime.JOB_OPERATION_REMOVE
        }
}
