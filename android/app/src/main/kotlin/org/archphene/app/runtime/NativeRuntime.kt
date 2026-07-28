package org.archphene.app.runtime

import java.nio.ByteBuffer

internal object NativeRuntime {
    const val PROTOCOL_VERSION = 1
    const val SNAPSHOT_SIZE = 64
    const val PACKAGE_MANIFEST_LIMIT = 32 * 1024
    const val PACKAGE_OUTPUT_SIZE = 16 * 1024
    const val PACKAGE_RESOLUTION_OUTPUT_SIZE = 320 * 1024
    const val AUR_BUILD_CLOSURE_OUTPUT_SIZE = 512 * 1024
    const val AUR_BUILD_GRAPH_OUTPUT_SIZE = 128 * 1024
    const val BUILT_PACKAGE_REPORT_SIZE = 64
    const val AUR_RPC_SIZE = 128 * 1024
    const val AUR_SNAPSHOT_SIZE = 4 * 1024 * 1024
    const val AUR_REVIEW_SIZE = 1024 * 1024
    const val AUR_SOURCE_MAX_SIZE = 4L * 1024 * 1024 * 1024
    const val INSTALLED_PACKAGE_PAGE_SIZE = 60
    const val INSTALLED_PACKAGE_LIMIT = 4096
    const val PACKAGE_CAPABILITY_GRAPHICAL = 1 shl 0
    const val PACKAGE_CAPABILITY_COMMAND_LINE = 1 shl 1
    const val PACKAGE_CAPABILITY_LIBRARY = 1 shl 2
    const val PACKAGE_CAPABILITY_SYSTEM = 1 shl 3
    const val PACKAGE_CAPABILITY_MASK = 0x0f
    const val DESKTOP_ENTRY_LIMIT = 256
    const val LAUNCHER_PROCESS_LOG_SIZE = 16 * 1024
    const val COMMAND_REQUEST_LIMIT = 16 * 1024
    const val DNS_REQUEST_LIMIT = 512
    const val LAUNCHER_REVIEW_REQUEST_LIMIT = 32 * 1024
    const val TERMINAL_DAMAGE_SIZE = 6_080_052
    const val TERMINAL_SELECTION_SIZE = 8 * 1024
    const val TERMINAL_CLIPBOARD_SIZE = 2 * 1024
    const val ERROR_INVALID_STATE = -3

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
    const val PACKAGE_COMMAND_INSTALLED_ORIGIN = 4
    const val PACKAGE_COMMAND_INSTALLATION_BYTES = 5
    const val PACKAGE_COMMAND_PENDING_MUTATION = 6
    const val PACKAGE_COMMAND_REPAIR_MUTATION = 7
    const val PACKAGE_COMMAND_UPDATE = 8
    const val PACKAGE_COMMAND_AVAILABLE_VERSION_STATE = 9
    const val PACKAGE_COMMAND_AUR_CANDIDATE_STATE = 10
    const val JOB_QUEUED = 1
    const val JOB_RESOLVING = 2
    const val JOB_DOWNLOADING = 3
    const val JOB_VERIFYING = 4
    const val JOB_INSTALLING = 5
    const val JOB_COMPLETE = 6
    const val JOB_FAILED = 7
    const val JOB_CANCELLED = 8
    const val JOB_PUBLISHING = 9
    const val JOB_BUILDING = 10
    const val JOB_AWAITING_CONFIRMATION = 11
    const val PTY_EVENT_READABLE = 1
    const val PTY_EVENT_WRITABLE = 1 shl 1
    const val PTY_EVENT_HANGUP = 1 shl 2
    const val PTY_EVENT_WOKEN = 1 shl 3
    const val STORAGE_OUTPUT_SIZE = 1024
    const val PROJECT_SYNC_BUFFER_SIZE = 8 * 1024
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
    external fun nativeOpenShellStartupDocument(
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
    external fun nativeImportHomeDocument(
        requestBuffer: ByteBuffer,
        requestLength: Int,
        sourceDescriptor: Int,
        debugChunkDelayMillis: Int,
        providerIdleTimeoutMillis: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeDocumentImportProgress(): Long
    external fun nativePrepareDocumentImport(): Boolean
    external fun nativeCancelDocumentImport(): Boolean
    external fun nativeExportHomeDocument(
        sourceDescriptor: Int,
        destinationDescriptor: Int,
        debugChunkDelayMillis: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeDocumentExportProgress(): Long
    external fun nativeCancelDocumentExport(): Boolean
    external fun nativeBeginProjectMirror(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeImportPortalFolder(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        sourceDescriptor: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAddProjectMirrorDirectory(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAddProjectMirrorFile(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        sourceDescriptor: Int,
        expectedBytes: Long,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeFinishProjectMirror(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAbortProjectMirror(handle: Long): Boolean
    external fun nativeCancelProjectMirror(handle: Long): Boolean
    external fun nativeBeginProjectSync(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFingerprintProjectSyncFile(
        handle: Long,
        sourceDescriptor: Int,
        expectedBytes: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFingerprintFile(
        handle: Long,
        sourceDescriptor: Int,
        expectedBytes: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAddProjectSyncAndroidDirectory(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAddProjectSyncAndroidFile(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        sourceDescriptor: Int,
        expectedBytes: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFinishProjectSyncScan(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeProjectSyncPlanEntry(
        handle: Long,
        index: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeExecuteProjectSyncLocal(
        handle: Long,
        operation: Int,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        sourceDescriptor: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeBeginProjectSyncCommitScan(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeCommitProjectSync(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAbortProjectSync(handle: Long): Boolean
    external fun nativeCancelProjectSync(handle: Long): Boolean
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long): Boolean
    external fun nativeTransition(handle: Long, lifecycle: Int): Int
    external fun nativeBootstrapArchRoot(
        handle: Long,
        buffer: ByteBuffer,
        byteCount: Int,
        nowMillis: Long,
    ): Int
    external fun nativeConfigureDns(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
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
    external fun nativeResolveAurBuildEnvironment(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReadAurBuildGraph(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeVerifyAurBuildEnvironment(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReadVerifiedAurBuildClosure(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeOpenVerifiedAurBuildPackage(
        handle: Long,
        packageIndex: Int,
        signature: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeVerifyAurBuiltPackage(
        handle: Long,
        descriptor: Int,
        filename: String,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        dependencyManifestBuffer: ByteBuffer,
        dependencyManifestLength: Int,
        dependencyManifestSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativePersistAurBuiltPackages(
        handle: Long,
        descriptors: IntArray,
        filenameManifest: ByteBuffer,
        filenameManifestLength: Int,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeRestoreAurBuiltPackages(
        handle: Long,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativePersistAurGraphBuiltPackages(
        handle: Long,
        descriptors: IntArray,
        graphManifest: ByteBuffer,
        graphManifestLength: Int,
        selectedPackage: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeRestoreAurGraphBuiltPackages(
        handle: Long,
        selectedPackage: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeClearAurBuiltCapability(handle: Long): Int
    external fun nativeInstallAurBuiltPackage(
        handle: Long,
        descriptor: Int,
        filename: String,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeInstallAurBuiltPackages(
        handle: Long,
        descriptors: IntArray,
        filenameManifest: ByteBuffer,
        filenameManifestLength: Int,
        packageBase: String,
        packageName: String,
        version: String,
        architecture: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeInstallAurGraphBuiltPackages(
        handle: Long,
        descriptors: IntArray,
        graphManifest: ByteBuffer,
        graphManifestLength: Int,
        selectedPackage: String,
        closureSha256: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReviewAur(
        handle: Long,
        architecture: Int,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        rpcBuffer: ByteBuffer,
        rpcLength: Int,
        snapshotBuffer: ByteBuffer,
        snapshotLength: Int,
        dependencyReview: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeResolveAurSnapshotPath(
        handle: Long,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        rpcBuffer: ByteBuffer,
        rpcLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAurProviderCandidates(
        handle: Long,
        dependencyBuffer: ByteBuffer,
        dependencyLength: Int,
        rpcBuffer: ByteBuffer,
        rpcLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeBeginAurSourceDownload(
        handle: Long,
        packageBase: String,
        sourceIndex: Int,
        maximumSize: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeVerifiedCachedAurSourceSize(
        handle: Long,
        packageBase: String,
        sourceIndex: Int,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeOpenReviewedAurSnapshot(
        handle: Long,
        packageBase: String,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeOpenVerifiedAurSource(
        handle: Long,
        packageBase: String,
        sourceIndex: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeFinishAurSourceDownload(
        handle: Long,
        success: Boolean,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativePackageCommand(
        handle: Long,
        action: Int,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeListInstalledPackages(
        handle: Long,
        offset: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeListDesktopEntries(
        handle: Long,
        offset: Int,
        refresh: Boolean,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativePackageLauncherReview(
        handle: Long,
        packageBuffer: ByteBuffer,
        packageLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeLauncherRegistryStatus(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeLauncherRegistryPage(
        handle: Long,
        offset: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeAuthorizeLauncher(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeOpenLauncherProcess(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeUpdateGuiColors(
        handle: Long,
        dark: Boolean,
        accent: Int,
        background: Int,
        foreground: Int,
    ): Int
    external fun nativeCloseLauncherProcess(
        handle: Long,
        launcherHandle: Long,
    ): Int
    external fun nativeLauncherProcessExitStatus(
        handle: Long,
        launcherHandle: Long,
    ): Long
    external fun nativeReadLauncherProcessLog(
        handle: Long,
        launcherHandle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeClaimLauncherPublish(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeClaimLauncherRemoval(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeLauncherTransition(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
    ): Int
    external fun nativeReviewLaunchers(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
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
    external fun nativeCopyTerminalSelection(
        handle: Long,
        ptyHandle: Long,
        originEpoch: Long,
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
        outputBuffer: ByteBuffer,
    ): Int
    // Zero means no write; a write returns decoded bytes + 1 so empty text is observable.
    external fun nativeReadTerminalClipboard(
        handle: Long,
        ptyHandle: Long,
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
    external fun nativeClearPackageCache(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeClearAurBuildCache(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Long
    external fun nativeRefreshPackageCache(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReadStorageUsage(
        handle: Long,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeReadPackageCachePage(
        handle: Long,
        offset: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativeClearSelectedPackageCache(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Long
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
    external fun nativeAnalyzeCachedPackage(
        handle: Long,
        requestBuffer: ByteBuffer,
        requestLength: Int,
        outputBuffer: ByteBuffer,
    ): Int
    external fun nativePreparePackageCompatibilityReview(handle: Long): Boolean
    external fun nativeCancelPackageCompatibilityReview(handle: Long): Boolean
    external fun nativeArmPackageCompatibilityReviewTestHold(holdMillis: Long): Boolean
    external fun nativeSubmitEvents(handle: Long, buffer: ByteBuffer, byteCount: Int): Int
    external fun nativeDrainInput(handle: Long, maximum: Int): Int
    external fun nativeWriteSnapshot(handle: Long, buffer: ByteBuffer): Int
}
