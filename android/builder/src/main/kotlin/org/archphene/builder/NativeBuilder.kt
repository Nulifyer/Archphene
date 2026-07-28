package org.archphene.builder

import java.nio.ByteBuffer

internal object NativeBuilder {
    const val CLOSURE_REPORT_BYTES = 64
    const val EXTRACTION_REPORT_BYTES = 32
    const val REVIEWED_INPUT_REPORT_BYTES = 56
    const val RECIPE_WORKSPACE_REPORT_BYTES = 32
    const val ERROR_OUTPUT_BYTES = 512
    const val RUNTIME_OUTPUT_BYTES = 16 * 1024
    const val BUILD_POLL_OUTPUT_BYTES = 16 + 64 * 1024
    const val BUILT_PACKAGE_REPORT_BYTES = 304

    init {
        System.loadLibrary("archphene_builder")
    }

    external fun nativeBeginReviewedInputs(
        filesDirectory: String,
        packageBase: String,
        version: String,
        expectedInputs: Int,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeStageReviewedInput(
        role: Int,
        filename: String,
        expectedBytes: Long,
        expectedSha256: String,
        descriptor: Int,
    ): Int

    external fun nativeFinishReviewedInputs(outputBuffer: ByteBuffer): Int

    external fun nativeAbortReviewedInputs(): Boolean

    external fun nativeBeginPackageClosure(
        filesDirectory: String,
        packageBase: String,
        version: String,
        manifestBuffer: ByteBuffer,
        manifestLength: Int,
        manifestSha256: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeStagePackage(
        packageIndex: Int,
        archiveDescriptor: Int,
        signatureDescriptor: Int,
    ): Int

    external fun nativeFinishPackageClosure(outputBuffer: ByteBuffer): Int

    external fun nativeAbortPackageClosure(): Boolean

    external fun nativeBeginProvision(
        filesDirectory: String,
        packageBase: String,
        version: String,
        manifestSha256: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeExtractProvisionBatch(
        maximumPackages: Int,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeFinishProvision(outputBuffer: ByteBuffer): Int

    external fun nativeAbortProvision(): Boolean

    external fun nativeProbeRuntime(
        filesDirectory: String,
        nativeDirectory: String,
        manifestBuffer: ByteBuffer,
        manifestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativePrepareRecipeWorkspace(
        filesDirectory: String,
        packageBase: String,
        version: String,
        inputManifestSha256: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeStartBuild(
        filesDirectory: String,
        nativeDirectory: String,
        runtimeManifestBuffer: ByteBuffer,
        runtimeManifestLength: Int,
        packageBase: String,
        version: String,
        inputManifestSha256: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativePollBuild(outputBuffer: ByteBuffer): Int

    external fun nativeCancelBuild(): Boolean

    external fun nativeReadStorageUsage(
        filesDirectory: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeClearStorage(
        filesDirectory: String,
        outputBuffer: ByteBuffer,
    ): Int

    external fun nativeVerifyAndCopyBuiltPackage(
        filesDirectory: String,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        outputDescriptor: Int,
        outputBuffer: ByteBuffer,
    ): Int
}
