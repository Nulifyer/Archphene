package org.archphene.app.runtime

import java.nio.ByteBuffer

internal object NativeRuntime {
    const val PROTOCOL_VERSION = 1
    const val SNAPSHOT_SIZE = 64
    const val PACKAGE_MANIFEST_LIMIT = 32 * 1024
    const val PACKAGE_OUTPUT_SIZE = 16 * 1024
    const val COMMAND_REQUEST_LIMIT = 16 * 1024
    const val TERMINAL_DAMAGE_SIZE = 6_080_040

    const val LIFECYCLE_RUNNING = 2
    const val LIFECYCLE_SUSPENDED = 3
    const val LIFECYCLE_STOPPING = 4
    const val LIFECYCLE_STOPPED = 5

    const val REPOSITORY_X86_64 = 1
    const val REPOSITORY_AARCH64 = 2
    const val CATALOG_CORE = 1
    const val CATALOG_EXTRA = 2
    const val CATALOG_MESSAGE_SIZE = 512
    const val JOB_OPERATION_INSTALL = 1
    const val JOB_OPERATION_UPDATE = 2
    const val JOB_OPERATION_REMOVE = 3
    const val PACKAGE_COMMAND_INSTALLED_VERSION = 1
    const val PACKAGE_COMMAND_INSTALL = 2
    const val PACKAGE_COMMAND_REMOVE = 3
    const val JOB_QUEUED = 1
    const val JOB_RESOLVING = 2
    const val JOB_DOWNLOADING = 3
    const val JOB_VERIFYING = 4
    const val JOB_INSTALLING = 5
    const val JOB_COMPLETE = 6
    const val JOB_FAILED = 7
    const val JOB_CANCELLED = 8
    const val PTY_EVENT_READABLE = 1
    const val PTY_EVENT_WRITABLE = 1 shl 1
    const val PTY_EVENT_HANGUP = 1 shl 2
    const val PTY_EVENT_WOKEN = 1 shl 3
    const val STORAGE_OUTPUT_SIZE = 1024
    const val STORAGE_MODE_READ = 1
    const val STORAGE_MODE_WRITE = 1 shl 1
    const val STORAGE_MODE_TRUNCATE = 1 shl 2
    const val STORAGE_MODE_APPEND = 1 shl 3

    init {
        System.loadLibrary("archphene_android")
    }

    external fun nativeProtocolVersion(): Int
    external fun nativeOpenHomeDocument(
        requestBuffer: ByteBuffer,
        requestLength: Int,
        mode: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeCreateHomeDocument(
        requestBuffer: ByteBuffer,
        requestLength: Int,
        directory: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeDeleteHomeDocument(
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeRenameHomeDocument(
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long): Boolean
    external fun nativeTransition(handle: Long, lifecycle: Int): Int
    external fun nativeBootstrapArchRoot(
        handle: Long,
        buffer: ByteBuffer,
        byteCount: Int,
        nowMillis: Long,
    ): Int
    external fun nativePreparePackageRuntime(
        handle: Long,
        architecture: Int,
        nativePathBuffer: ByteBuffer,
        nativePathLength: Int,
        manifestBuffer: ByteBuffer,
        manifestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeBeginPackageCatalogDownload(
        handle: Long,
        repository: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFinishPackageCatalogDownload(
        handle: Long,
        repository: Int,
        success: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeSearchPackages(
        handle: Long,
        queryBuffer: ByteBuffer,
        queryLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeDiscoverShells(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeResolvePackage(
        handle: Long,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativePackageCommand(
        handle: Long,
        action: Int,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeRunCommand(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeOpenPty(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        rows: Int,
        columns: Int,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativePtyIo(
        handle: Long,
        ptyHandle: Long,
        writeOperation: Boolean,
        buffer: ByteBuffer,
        byteCount: Int,
    ): Int
    external fun nativeReadTerminalDamage(
        handle: Long,
        ptyHandle: Long,
        fullSnapshot: Boolean,
        viewportOffset: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeWaitPty(
        handle: Long,
        ptyHandle: Long,
        writePending: Boolean,
    ): Int
    external fun nativeWakePty(
        handle: Long,
        ptyHandle: Long,
    ): Int
    external fun nativeResizePty(
        handle: Long,
        ptyHandle: Long,
        rows: Int,
        columns: Int,
    ): Int
    external fun nativePtyExitStatus(
        handle: Long,
        ptyHandle: Long,
    ): Long
    external fun nativeClosePty(
        handle: Long,
        ptyHandle: Long,
    ): Int
    external fun nativeQueuePackageJob(
        handle: Long,
        operation: Int,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        nowMillis: Long,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeUpdatePackageJob(
        handle: Long,
        jobId: Long,
        state: Int,
        phase: Int,
        progress: Int,
        messageBuffer: ByteBuffer,
        messageLength: Int,
        nowMillis: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReadLatestPackageJob(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeBeginPackageDownload(
        handle: Long,
        filenameBuffer: ByteBuffer,
        filenameLength: Int,
        expectedSize: Long,
        signature: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFinishPackageDownload(
        handle: Long,
        success: Boolean,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeVerifyPackage(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        expectedSize: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeSubmitEvents(handle: Long, buffer: ByteBuffer, byteCount: Int): Int
    external fun nativeDrainInput(handle: Long, maximum: Int): Int
    external fun nativeWriteSnapshot(handle: Long, buffer: ByteBuffer): Int
}
