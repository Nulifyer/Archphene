package org.archphene.builder

import java.nio.ByteBuffer

internal object NativeBuilder {
    const val CLOSURE_REPORT_BYTES = 64
    const val EXTRACTION_REPORT_BYTES = 32
    const val ERROR_OUTPUT_BYTES = 512
    const val RUNTIME_OUTPUT_BYTES = 16 * 1024

    init {
        System.loadLibrary("archphene_builder")
    }

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
}
