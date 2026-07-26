package org.archphene.builder

import java.nio.ByteBuffer

internal object NativeBuilder {
    const val CLOSURE_REPORT_BYTES = 64
    const val ERROR_OUTPUT_BYTES = 512

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
}
