package org.archphene.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Parcel
import android.os.Process
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.net.ssl.HttpsURLConnection
import org.archphene.app.MainActivity
import org.archphene.app.R
import org.archphene.app.launcher.LauncherApkAssembler
import org.archphene.app.launcher.LauncherApkRequest
import org.archphene.app.launcher.LauncherApkSigner
import org.archphene.app.launcher.LauncherPackageInstaller

internal class InstalledPackageSnapshot(
    val names: Array<String>,
    val versions: Array<String>,
    val explicitlyInstalled: BooleanArray,
    val status: String,
    val revision: Int,
)

internal class DesktopEntrySnapshot(
    val desktopIds: Array<String>,
    val names: Array<String>,
    val executables: Array<String>,
    val terminal: BooleanArray,
    val icons: Array<String>,
    val sourcePackages: Array<String>,
    val status: String,
    val revision: Int,
)

internal class LauncherReviewSnapshot(
    val androidPackages: Array<String>,
    val desiredGenerations: LongArray,
    val labels: Array<String>,
    val sourcePackages: Array<String>,
    val statuses: IntArray,
    val needsReviewCount: Int,
    val dismissedCount: Int,
    val revision: Int,
)

internal class AvailablePackageSnapshot(
    val repositories: Array<String>,
    val names: Array<String>,
    val versions: Array<String>,
    val descriptions: Array<String>,
    val installStates: Array<String>,
    val installedVersions: Array<String>,
    val status: String,
    val revision: Int,
)

internal class AurReviewSnapshot(
    val packageName: String,
    val summary: String,
    val sources: String,
    val trust: String,
    val buildEnvironment: String,
    val digests: String,
    val recipe: String,
    val logs: String,
    val revision: Int,
)

internal class PackageCacheSnapshot(
    val names: Array<String>,
    val versions: Array<String>,
    val bytes: LongArray,
    val artifacts: IntArray,
    val totalBytes: Long,
    val status: String,
    val revision: Int,
)

internal data class LauncherAuthorization(
    val label: String,
    val terminal: Boolean,
)

private data class LauncherRegistryRow(
    val androidPackage: String,
    val descriptorIdHex: String,
    val desiredGeneration: Long,
    val status: Int,
    val name: String,
    val sourcePackage: String,
)

class ArchpheneRuntimeService : Service() {
    inner class LocalBinder : Binder() {
        val runtimeHandle: Long
            get() = readyHandle

        val packageCatalogStatus: String
            get() = catalogStatus

        internal val packageCache: PackageCacheSnapshot
            get() = packageCacheSnapshot

        val packageCacheActionAvailable: Boolean
            get() =
                readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val packageSearchStatus: String
            get() = searchStatus

        internal val aurReview: AurReviewSnapshot
            get() = aurReviewSnapshot

        val aurReviewAvailable: Boolean
            get() =
                readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val aurReviewedPackage: String
            get() = retainedAurReview?.packageName.orEmpty()

        val aurSourcesAvailable: Boolean
            get() =
                retainedAurReview?.sources?.any { source ->
                    !source.local && source.remoteUrl != null
                } == true &&
                    readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val aurBuildAvailable: Boolean
            get() =
                retainedAurBuilderReport?.packageName != null &&
                    retainedAurReview != null &&
                    retainedAurBuiltPackage == null &&
                    readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val aurInstallAvailable: Boolean
            get() =
                retainedAurBuiltPackage != null &&
                    retainedAurReview != null &&
                    readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val aurBuildCancellationAvailable: Boolean
            get() = aurBuildActive && aurBuildCancelable

        internal val installedPackages: InstalledPackageSnapshot
            get() = installedPackageSnapshot

        internal val desktopEntries: DesktopEntrySnapshot
            get() = desktopEntrySnapshot

        internal val launcherReview: LauncherReviewSnapshot
            get() = launcherReviewSnapshot

        internal val availablePackages: AvailablePackageSnapshot
            get() = availablePackageSnapshot

        val packageJobStatus: String
            get() = jobStatus

        val packageJobName: String
            get() = jobPackage

        val packageJobProgress: Int
            get() = jobProgress

        val packageJobState: Int
            get() = jobState

        val packageJobRevision: Int
            get() = jobRevision

        val packageJobMessage: String
            get() =
                if (
                    packageRecoveryMessageRevision == jobRevision &&
                    packageRecoveryMessage.isNotEmpty()
                ) {
                    packageRecoveryMessage
                } else {
                    jobMessage
                }

        val packageJobActivityLabel: String
            get() = jobActivityLabel

        val serviceRetentionRequired: Boolean
            get() = hasActiveRuntimeWork()

        val launcherInstallPermissionRequired: Boolean
            get() = launcherPermissionRequired

        val cancelledLauncherCount: Int
            get() = launcherCancelledCount

        val launcherReviewInProgress: Boolean
            get() = launcherReviewActive.get()

        fun resumeLauncherPublisher(): Boolean {
            val activeHandle = readyHandle
            if (activeHandle == 0L || !launcherPublicationPending) {
                return false
            }
            if (!packageManager.canRequestPackageInstalls()) {
                return false
            }
            launcherPermissionRequired = false
            startLauncherPublisher(activeHandle)
            return true
        }

        fun retryCancelledLauncher(): Boolean = requestCancelledLauncherDecision("retry")

        fun dismissCancelledLauncher(): Boolean = requestCancelledLauncherDecision("dismiss")

        fun reviewLaunchers(
            revision: Int,
            publish: BooleanArray,
        ): Boolean = requestLauncherReview(revision, publish)

        internal fun authorizeLauncher(
            androidPackage: String,
            descriptorIdHex: String,
            generation: Long,
        ): LauncherAuthorization? =
            this@ArchpheneRuntimeService.authorizeLauncher(
                androidPackage,
                descriptorIdHex,
                generation,
            )

        internal fun openLauncherProcess(
            androidPackage: String,
            descriptorIdHex: String,
            generation: Long,
            waylandDisplay: String,
            dark: Boolean,
            fontPercent: Int,
            controlVisualDp: Int,
            controlTargetDp: Int,
            accent: Int,
            background: Int,
            foreground: Int,
        ): Long =
            this@ArchpheneRuntimeService.openLauncherProcess(
                androidPackage,
                descriptorIdHex,
                generation,
                waylandDisplay,
                dark,
                fontPercent,
                controlVisualDp,
                controlTargetDp,
                accent,
                background,
                foreground,
            )

        internal fun closeLauncherProcess(launcherHandle: Long): Boolean =
            this@ArchpheneRuntimeService.closeLauncherProcess(launcherHandle)

        internal fun launcherProcessExitStatus(launcherHandle: Long): Int? =
            this@ArchpheneRuntimeService.launcherProcessExitStatus(launcherHandle)

        internal fun launcherProcessLog(launcherHandle: Long): String =
            this@ArchpheneRuntimeService.launcherProcessLog(launcherHandle)

        val packagePrimaryActionLabel: String
            get() = primaryActionLabel

        val resolvedPackageName: String
            get() = lastResolvedPackage

        val packagePrimaryActionAvailable: Boolean
            get() =
                lastResolvedPackage.isNotEmpty() &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive &&
                    (
                        (
                            lastResolvedRepository != "aur" &&
                                !terminalJobRequiresReview(lastResolvedPackage)
                        ) ||
                            (
                                retainedAurBuiltPackage != null &&
                                    retainedAurReview?.packageName == lastResolvedPackage
                            )
                    )

        val packageTerminalActivityVisible: Boolean
            get() = !searchActive && !aurBuildActive

        val packageRemoveAvailable: Boolean
            get() =
                removeAvailable &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive &&
                    !terminalJobRequiresReview(lastResolvedPackage)

        val packageRemoveActionLabel: String
            get() = removeActionLabel

        val packageCancellationAvailable: Boolean
            get() = packageOperationActive && packageOperationCancelable

        val packageRecoveryAvailable: Boolean
            get() =
                jobPackage.isNotEmpty() &&
                    packageMutationStatus.isEmpty() &&
                    (
                        jobState == NativeRuntime.JOB_FAILED ||
                            jobState == NativeRuntime.JOB_CANCELLED
                    ) &&
                    readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive &&
                    recoveryReviewedJobRevision != jobRevision

        val packageMutationRepairAvailable: Boolean
            get() =
                packageMutationStatus.isNotEmpty() &&
                    jobPackage.isNotEmpty() &&
                    readyHandle != 0L &&
                    !catalogRefreshActive &&
                    !packageCacheActive &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val packageCacheRecoveryAvailable: Boolean
            get() = packageCacheRecoveryReady()

        val packageCatalogRecoveryAvailable: Boolean
            get() = packageCatalogRecoveryReady()

        val packageActivityActionLabel: String
            get() =
                when {
                    aurBuildCancellationAvailable -> "Cancel build"
                    packageCancellationAvailable -> "Cancel"
                    packageMutationRepairAvailable -> "Repair"
                    packageCacheRecoveryAvailable -> "Clear cache"
                    packageCatalogRecoveryAvailable -> "Refresh package catalogs"
                    else -> "Review"
                }

        val documentImportStatus: String
            get() = storageStatus

        val documentImportAvailable: Boolean
            get() = readyHandle != 0L && !PROCESS_STORAGE_ACTIVE.get()

        val documentImportRunning: Boolean
            get() = storageImportActive

        val folderGrantStatus: String
            get() = folderStatus

        val folderGrantActionLabel: String
            get() = if (folderConnected) "Change" else "Connect"

        val folderGrantAvailable: Boolean
            get() = folderStateReady && !PROCESS_STORAGE_ACTIVE.get()

        val folderDisconnectAvailable: Boolean
            get() = folderStateReady && folderConnected && !PROCESS_STORAGE_ACTIVE.get()

        val folderMirrorAvailable: Boolean
            get() =
                folderMirrorRunning ||
                    (
                        folderStateReady &&
                            folderConnected &&
                            readyHandle != 0L &&
                            !PROCESS_STORAGE_ACTIVE.get()
                    )

        val folderMirrorActionLabel: String
            get() =
                when {
                    folderMirrorRunning -> "Cancel"
                    folderMirrorPath.isEmpty() -> "Mirror"
                    else -> "Sync"
                }

        val folderGrantRunning: Boolean
            get() = folderOperationActive

        val storageOnboardingRequired: Boolean
            get() = folderStateReady && folderOnboardingNeeded

        val linuxCommandStatus: String
            get() =
                if (shellActive) {
                    shellPhase
                } else if (shellWasStarted) {
                    sharedShellDisplayStatus()
                } else {
                    commandStatus
                }

        val linuxCommandStarted: Boolean
            get() = directCommandStarted

        val linuxCommandAvailable: Boolean
            get() =
                if (shellActive) {
                    shellHandle != 0L && !shellStopRequested
                } else {
                    readyHandle != 0L &&
                        !catalogRefreshActive &&
                        !packageCacheActive &&
                        !searchActive &&
                        !packageOperationActive &&
                        !commandActive
                }

        val linuxInputActionLabel: String
            get() = if (shellActive) "Send" else "Run"

        val sharedShellActionLabel: String
            get() = if (shellActive) "Stop shell" else "Start shell"

        val sharedShellActionAvailable: Boolean
            get() =
                if (shellActive) {
                    true
                } else {
                    readyHandle != 0L &&
                        selectedShellIndex >= 0 &&
                        !catalogRefreshActive &&
                        !packageCacheActive &&
                        !searchActive &&
                        !packageOperationActive &&
                        !commandActive
                }

        val sharedShellRunning: Boolean
            get() = shellActive

        val sharedShellTerminalRevision: Long
            get() = shellTerminalRevision.get()

        val sharedShellTerminalDamageBuffer: ByteBuffer
            get() = shellTerminalDamageBuffer

        val shellCatalogRevision: Int
            get() = shellChoicesRevision

        val supportedShellLabels: Array<String>
            get() = shellChoices.map(ShellChoice::label).toTypedArray()

        val selectedSharedShellIndex: Int
            get() = selectedShellIndex

        val sharedShellSelectionAvailable: Boolean
            get() = !shellActive && shellChoices.size > 1

        fun refreshPackageCatalogs(): Boolean = requestCatalogRefresh()

        fun refreshPackageCatalogsForRecovery(): Boolean =
            requestCatalogRefresh(recoverPackageJob = true)

        fun refreshPackageCacheInventory(): Boolean = requestPackageCacheInventory()

        fun clearSelectedPackageCache(packages: Array<String>): Boolean =
            requestSelectedPackageCacheCleanup(packages)

        fun clearAllPackageCacheDownloads(): Boolean =
            requestAllPackageCacheCleanup()

        fun searchPackages(query: String): Boolean = requestPackageSearch(query)

        fun resolvePackage(packageName: String): Boolean =
            requestPackageResolution(packageName)

        fun reviewAurPackage(packageName: String): Boolean =
            requestAurReview(packageName)

        fun verifyAurSources(packageName: String): Boolean =
            requestAurSourceVerification(packageName)

        fun buildAurPackage(packageName: String): Boolean =
            requestAurBuild(packageName)

        fun cancelAurBuild(): Boolean = requestAurBuildCancellation()

        fun installPackage(packageName: String): Boolean =
            if (
                retainedAurBuiltPackage != null &&
                retainedAurReview?.packageName == packageName.trim()
            ) {
                requestAurPackageInstall(packageName)
            } else {
                requestPackageInstall(packageName)
            }

        fun removePackage(packageName: String): Boolean =
            requestPackageRemoval(packageName)

        fun cancelPackageOperation(): Boolean = requestPackageCancellation()

        fun repairPackageMutation(): Boolean = requestPackageMutationRepair()

        fun clearPackageCache(): Boolean = requestPackageCacheCleanup()

        fun startDebugPackagePhaseFixture(
            packageName: String,
            holdMillis: Long,
        ): Boolean = requestDebugPackagePhaseFixture(packageName, holdMillis)

        fun startDebugInterruptedRemovalFixture(
            packageName: String,
            holdMillis: Long,
        ): Boolean = requestDebugInterruptedRemovalFixture(packageName, holdMillis)

        fun publishDebugAurReviewFixture(packageName: String): Boolean =
            requestDebugAurReviewFixture(packageName)

        fun clearDebugAurReviewFixture(packageName: String): Boolean =
            clearDebugAurReviewFixtureState(packageName)

        fun releaseWhenIdle() {
            stopWhenUnobservedRequested = true
        }

        fun importAndroidDocument(uri: Uri): Boolean = requestDocumentImport(uri)

        fun connectAndroidFolder(
            uri: Uri,
            flags: Int,
        ): Boolean = requestFolderGrant(uri, flags)

        fun disconnectAndroidFolder(): Boolean = requestFolderDisconnect()

        fun mirrorAndroidFolder(): Boolean =
            if (folderMirrorRunning) {
                requestFolderMirrorCancellation()
            } else if (folderMirrorPath.isNotEmpty()) {
                requestFolderSync()
            } else {
                requestFolderMirror()
            }

        fun completeStorageOnboarding() {
            folderOnboardingNeeded = false
            getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(FOLDER_ONBOARDING_SEEN, true)
                .apply()
        }

        fun submitLinuxInput(commandLine: String): Boolean =
            if (shellActive) {
                requestShellInput(commandLine)
            } else {
                requestLinuxCommand(commandLine)
            }

        fun submitTerminalInput(
            source: ByteArray,
            length: Int,
        ): Boolean = requestTerminalInput(source, length)

        fun selectSharedShell(index: Int): Boolean = requestShellSelection(index)

        fun resizeSharedShell(
            rows: Int,
            columns: Int,
        ): Boolean = requestShellResize(rows, columns)

        fun readSharedShellTerminalDamage(
            fullSnapshot: Boolean,
            viewportOffset: Int,
        ): Int {
            val activeHandle = readyHandle
            val activePty = shellHandle
            if (activeHandle == 0L || activePty == 0L) {
                return 0
            }
            shellTerminalDamageBuffer.clear()
            return NativeRuntime.nativeReadTerminalDamage(
                activeHandle,
                activePty,
                fullSnapshot,
                viewportOffset,
                shellTerminalDamageBuffer,
            )
        }

        fun copySharedShellTerminalSelection(
            originEpoch: Long,
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ): String? {
            val activeHandle = readyHandle
            val activePty = shellHandle
            if (activeHandle == 0L || activePty == 0L) {
                return null
            }
            shellTerminalSelectionBuffer.clear()
            val length =
                NativeRuntime.nativeCopyTerminalSelection(
                    activeHandle,
                    activePty,
                    originEpoch,
                    startRow,
                    startColumn,
                    endRow,
                    endColumn,
                    shellTerminalSelectionBuffer,
                )
            if (length < 0 || length > shellTerminalSelectionBytes.size) {
                return null
            }
            shellTerminalSelectionBuffer.position(0)
            shellTerminalSelectionBuffer.get(shellTerminalSelectionBytes, 0, length)
            return String(shellTerminalSelectionBytes, 0, length, Charsets.UTF_8)
        }

        fun toggleSharedShell(): Boolean = requestSharedShellToggle()
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectSyncProvider by lazy {
        ProjectSyncProvider(
            contentResolver,
            mainHandler,
            PROJECT_SYNC_PROVIDER_DEADLINE_MILLIS,
        ) { operation ->
            folderStatus =
                "Android file provider stopped responding while attempting to $operation"
            Log.e(TAG, "$folderStatus; terminating for journal recovery")
            Process.killProcess(Process.myPid())
        }
    }
    @Volatile private var handle = 0L
    @Volatile private var readyHandle = 0L
    @Volatile private var dnsRootReady = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private val dnsRefreshActive = AtomicBoolean(false)
    private val dnsRefreshPending = AtomicBoolean(false)
    private var dnsThread: Thread? = null
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleAndroidDnsRefresh()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                scheduleAndroidDnsRefresh()
            }

            override fun onLost(network: Network) {
                scheduleAndroidDnsRefresh()
            }
        }
    private val launcherPublisherActive = AtomicBoolean(false)
    private val launcherDecisionActive = AtomicBoolean(false)
    private val launcherReviewActive = AtomicBoolean(false)
    @Volatile private var launcherPermissionRequired = false
    @Volatile private var launcherPublicationPending = false
    @Volatile private var launcherCancelledCount = 0
    @Volatile private var pendingLauncherResultPackage = ""
    @Volatile private var pendingLauncherResultGeneration = 0L
    @Volatile private var pendingLauncherResultAction = ""
    private var bootstrapThread: Thread? = null
    @Volatile private var bootstrapActive = false
    private var catalogThread: Thread? = null
    private var packageThread: Thread? = null
    private var commandThread: Thread? = null
    private var shellThread: Thread? = null
    private var storageThread: Thread? = null
    private var boundClients = 0
    private var stopWhenUnobservedRequested = false
    @Volatile private var catalogRefreshActive = false
    @Volatile private var catalogStatus = "Package catalog not downloaded"
    @Volatile private var searchActive = false
    @Volatile private var searchStatus = "Search the official Arch repositories"
    @Volatile private var retainedAurReview: AurReviewData? = null
    @Volatile private var retainedAurVerifiedBytes = 0L
    @Volatile private var retainedAurSourceEvidence: Array<AurSourceEvidence> = emptyArray()
    @Volatile private var retainedAurBuilderReport: AurBuilderReport? = null
    @Volatile private var retainedAurBuiltPackage: AurBuiltPackage? = null
    @Volatile private var retainedAurBuiltPackages: Array<AurBuiltPackage> = emptyArray()
    @Volatile private var retainedAurBuildLogs = ""
    @Volatile
    private var aurReviewSnapshot =
        AurReviewSnapshot(
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            0,
        )
    @Volatile private var aurBuildActive = false
    @Volatile private var aurBuildCancelable = false
    @Volatile private var aurBuildCancellationRequested = false
    @Volatile
    private var installedPackageSnapshot =
        InstalledPackageSnapshot(
            emptyArray(),
            emptyArray(),
            BooleanArray(0),
            "Loading installed packages…",
            0,
        )
    @Volatile
    private var desktopEntrySnapshot =
        DesktopEntrySnapshot(
            emptyArray(),
            emptyArray(),
            emptyArray(),
            BooleanArray(0),
            emptyArray(),
            emptyArray(),
            "Discovering Linux apps…",
            0,
        )
    @Volatile
    private var launcherReviewSnapshot =
        LauncherReviewSnapshot(
            emptyArray(),
            LongArray(0),
            emptyArray(),
            emptyArray(),
            IntArray(0),
            0,
            0,
            0,
        )
    @Volatile
    private var availablePackageSnapshot =
        AvailablePackageSnapshot(
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            "Search the official Arch repositories",
            0,
        )
    @Volatile private var packageOperationActive = false
    @Volatile private var packageOperationCancelable = false
    @Volatile private var packageCancellationRequested = false
    @Volatile private var activePackageConnection: HttpsURLConnection? = null
    @Volatile private var commandActive = false
    @Volatile private var storageImportActive = false
    @Volatile private var storageStatus = "Import an Android file into ~/Downloads"
    @Volatile private var folderOperationActive = false
    @Volatile private var folderStateReady = false
    @Volatile private var folderConnected = false
    @Volatile private var folderWritable = false
    @Volatile private var folderUri = ""
    @Volatile private var folderLabel = ""
    @Volatile private var folderMirrorPath = ""
    @Volatile private var folderMirrorRunning = false
    @Volatile private var folderSyncRunning = false
    @Volatile private var folderMirrorCancellationRequested = false
    @Volatile private var folderOnboardingNeeded = false
    @Volatile private var folderStatus = "Loading Android folder access…"
    @Volatile private var shellActive = false
    @Volatile private var shellWasStarted = false
    @Volatile private var directCommandStarted = false
    @Volatile private var shellStopRequested = false
    @Volatile private var shellHandle = 0L
    private val shellTerminalRevision = AtomicLong()
    @Volatile private var shellRows = DEFAULT_SHELL_ROWS
    @Volatile private var shellColumns = DEFAULT_SHELL_COLUMNS
    @Volatile private var shellPhase = "Shared shell stopped"
    @Volatile private var shellChoices: List<ShellChoice> = emptyList()
    @Volatile private var shellChoicesRevision = 0
    @Volatile private var selectedShellIndex = -1
    @Volatile private var jobStatus = "No package transaction"
    @Volatile private var jobPersistentId = 0L
    @Volatile private var jobPackage = ""
    @Volatile private var jobRepository = ""
    @Volatile private var jobOperation = 0
    @Volatile private var jobState = 0
    @Volatile private var jobProgress = 0
    @Volatile private var jobRevision = 0
    @Volatile private var jobMessage = ""
    @Volatile private var jobActivityLabel = ""
    @Volatile private var packageMutationStatus = ""
    @Volatile private var lastResolvedPackage = ""
    @Volatile private var lastResolvedRepository = ""
    @Volatile private var lastResolvedInstalledVersion = ""
    @Volatile private var lastResolvedAvailableVersion = ""
    @Volatile private var primaryActionLabel = "Install"
    @Volatile private var removeActionLabel = "Remove"
    @Volatile private var removeAvailable = false
    @Volatile private var recoveryReviewedJobRevision = Int.MIN_VALUE
    @Volatile private var packageRecoveryHandledJobRevision = Int.MIN_VALUE
    @Volatile private var packageRecoveryMessageRevision = Int.MIN_VALUE
    @Volatile private var packageRecoveryMessage = ""
    @Volatile private var packageCacheActive = false
    @Volatile
    private var packageCacheSnapshot =
        PackageCacheSnapshot(
            emptyArray(),
            emptyArray(),
            LongArray(0),
            IntArray(0),
            0L,
            "Open Downloads to inspect the package cache",
            0,
        )
    @Volatile private var commandStatus = "Run an installed Linux command"
    private val shellOutput = BoundedByteRing(SHELL_SCROLLBACK_BYTES)
    private val shellInput = FixedByteQueue(SHELL_INPUT_BYTES)
    private val installedPackageOutputBuffer =
        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
    private val installedPackageOutputBytes = ByteArray(NativeRuntime.PACKAGE_OUTPUT_SIZE)
    private val desktopEntryOutputBuffer =
        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
    private val desktopEntryOutputBytes = ByteArray(NativeRuntime.PACKAGE_OUTPUT_SIZE)
    private val packageResolutionRequestBuffer = ByteBuffer.allocateDirect(128)
    private val packageResolutionOutputBuffer =
        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_RESOLUTION_OUTPUT_SIZE)
    private val aurBuildClosureOutputBuffer =
        ByteBuffer.allocateDirect(NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE)
    private val aurPackageBuffer = ByteBuffer.allocateDirect(128)
    private val aurEndpointBuffer =
        ByteBuffer
            .allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
    private val aurRpcBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer.allocateDirect(NativeRuntime.AUR_RPC_SIZE)
    }
    private val aurSnapshotBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer.allocateDirect(NativeRuntime.AUR_SNAPSHOT_SIZE)
    }
    private val aurReviewBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer
            .allocateDirect(NativeRuntime.AUR_REVIEW_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
    }
    private val aurTransferBuffer = ByteArray(64 * 1024)
    private val launcherAuthorizationRequestBuffer = ByteBuffer.allocateDirect(256)
    private val launcherAuthorizationOutputBuffer = ByteBuffer.allocateDirect(512)
    private val launcherAuthorizationOutputBytes = ByteArray(512)
    private val launcherProcessLogBuffer =
        ByteBuffer.allocateDirect(NativeRuntime.LAUNCHER_PROCESS_LOG_SIZE)
    private val launcherProcessLogBytes = ByteArray(NativeRuntime.LAUNCHER_PROCESS_LOG_SIZE)
    private val shellTerminalDamageBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer
            .allocateDirect(NativeRuntime.TERMINAL_DAMAGE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
    }
    private val shellTerminalSelectionBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer.allocateDirect(NativeRuntime.TERMINAL_SELECTION_SIZE)
    }
    private val shellTerminalSelectionBytes by lazy(LazyThreadSafetyMode.NONE) {
        ByteArray(NativeRuntime.TERMINAL_SELECTION_SIZE)
    }

    private data class ResolvedPayload(
        val repository: String,
        val name: String,
        val version: String,
        val filename: String,
        val url: String,
        val size: Long,
    )

    private data class AurSourceReview(
        val architecture: String,
        val expression: String,
        val filename: String,
        val remoteUrl: String?,
        val local: Boolean,
        val checksumAlgorithm: String?,
        val checksum: String?,
        val insecureTransport: Boolean,
    )

    private data class AurSourceEvidence(
        val filename: String,
        val bytes: Long,
        val endpoint: String,
        val cached: Boolean,
        val sha256: String,
    )

    private data class AurBuilderReport(
        val packageName: String,
        val uid: Int,
        val selinuxContext: String,
        val stagedBytes: Long,
        val inputManifestSha256: String,
        val closurePackageCount: Int,
        val closureArchiveBytes: Long,
        val closureSignatureBytes: Long,
        val closureManifestSha256: String,
        val buildRootEntries: Long,
        val buildRootBytes: Long,
        val runtimeVersion: String,
        val recipeEntries: Long,
        val recipeBytes: Long,
        val recipeSourceBytes: Long,
    )

    private data class AurRecipeWorkspace(
        val entryCount: Long,
        val recipeBytes: Long,
        val sourceBytes: Long,
    )

    private data class AurBuildPoll(
        val exitStatus: Int,
        val logs: String,
    )

    private data class AurBuiltPackage(
        val packageName: String,
        val filename: String,
        val archiveBytes: Long,
        val installedBytes: Long,
        val buildPackageCount: Int,
        val sha256: String,
        val file: File,
        val logs: String,
    )

    private fun clearRetainedAurBuiltPackages() {
        if (retainedAurBuiltPackages.isEmpty()) {
            retainedAurBuiltPackage?.file?.delete()
        } else {
            retainedAurBuiltPackages.forEach { built -> built.file.delete() }
        }
        retainedAurBuiltPackages = emptyArray()
        retainedAurBuiltPackage = null
    }

    private data class AurBuildEnvironment(
        val packages: List<ResolvedPayload>,
        val resolutionBytes: ByteArray,
        val downloadBytes: Long,
        val verifiedPackages: List<VerifiedBuildPackage> = emptyList(),
        val closureManifest: ByteArray = ByteArray(0),
        val closureManifestSha256: String = "",
        val cachedPackages: Int = 0,
        val downloadedPackages: Int = 0,
        val verified: Boolean = false,
    ) {
        val packageCount: Int
            get() = packages.size
    }

    private data class VerifiedBuildPackage(
        val repository: String,
        val name: String,
        val version: String,
        val filename: String,
        val url: String,
        val archiveBytes: Long,
        val archiveSha256: String,
        val signatureBytes: Long,
        val signatureSha256: String,
    )

    private data class AurBuilderInput(
        val role: Int,
        val filename: String,
        val sha256: String,
        val bytes: Long,
        val descriptor: ParcelFileDescriptor,
    )

    private data class AurReviewData(
        val packageBase: String,
        val packageName: String,
        val version: String,
        val description: String,
        val maintainer: String,
        val projectUrl: String,
        val snapshotPath: String,
        val snapshotCommit: String,
        val snapshotSha256: String,
        val lastModified: Long,
        val outOfDate: Boolean,
        val licenses: Array<String>,
        val dependencies: Array<String>,
        val requiredPackages: Array<String>,
        val makeDependencies: Array<String>,
        val checkDependencies: Array<String>,
        val sources: Array<AurSourceReview>,
        val validPgpKeys: Array<String>,
        val buildSteps: Array<String>,
        val installScript: String,
        val pkgbuild: String,
        val installScriptContents: String,
        val unverifiedSources: Boolean,
        val insecureSources: Boolean,
    )

    private class AurWireReader(
        source: ByteBuffer,
        length: Int,
    ) {
        private val buffer =
            source
                .duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    position(0)
                    limit(length)
                }
        private val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun byte(): Int {
            require(buffer.remaining() >= 1)
            return buffer.get().toInt() and 0xff
        }

        fun int(): Int {
            require(buffer.remaining() >= Int.SIZE_BYTES)
            return buffer.getInt()
        }

        fun long(): Long {
            require(buffer.remaining() >= Long.SIZE_BYTES)
            return buffer.getLong()
        }

        fun bytes(length: Int): ByteArray {
            require(length >= 0 && length <= buffer.remaining())
            return ByteArray(length).also(buffer::get)
        }

        fun blob(maximumBytes: Int): ByteArray {
            val length = int()
            require(length in 0..maximumBytes)
            return bytes(length)
        }

        fun string(
            maximumBytes: Int,
            allowEmpty: Boolean = false,
            allowMultiline: Boolean = false,
        ): String {
            val bytes = blob(maximumBytes)
            require(allowEmpty || bytes.isNotEmpty())
            decoder.reset()
            val value = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            require(
                value.none { character ->
                    character == '\u0000' ||
                        character == '\r' ||
                        (
                            character.isISOControl() &&
                                !(allowMultiline && character in "\n\t")
                        )
                },
            )
            return value
        }

        fun exhausted(): Boolean = !buffer.hasRemaining()
    }

    private data class ShellChoice(
        val id: String,
        val label: String,
        val requestBytes: ByteArray,
    )

    private data class LauncherSummary(
        val total: Int,
        val needsPublish: Int,
        val current: Int,
        val needsRemoval: Int,
        val active: Int,
        val failed: Int,
        val cancelled: Int,
        val dismissed: Int,
        val needsReview: Int,
    )

    private data class MirrorDirectory(
        val documentId: String,
        val relativePath: String,
    )

    private class MirrorProgress {
        var entries = 0
        var bytes = 0L
    }

    private data class ProjectSyncRemoteEntry(
        val documentId: String,
        val uri: Uri,
        val mime: String,
        val directory: Boolean,
    )

    private data class ProjectSyncFingerprint(
        val kind: Int,
        val bytes: Long,
        val sha256: ByteArray,
    ) {
        fun encode(): String =
            when (kind) {
                SYNC_KIND_DIRECTORY -> "d"
                SYNC_KIND_FILE -> {
                    val alphabet = "0123456789abcdef"
                    val encoded = CharArray(64)
                    sha256.forEachIndexed { index, value ->
                        val unsigned = value.toInt() and 0xff
                        encoded[index * 2] = alphabet[unsigned ushr 4]
                        encoded[index * 2 + 1] = alphabet[unsigned and 0x0f]
                    }
                    "f:$bytes:${encoded.concatToString()}"
                }
                else -> "n"
            }
    }

    private data class ProjectSyncPlanEntry(
        val path: String,
        val action: Int,
        val baseline: ProjectSyncFingerprint?,
        val linux: ProjectSyncFingerprint?,
        val android: ProjectSyncFingerprint?,
    )

    private data class ProjectSyncDeletedDocument(
        val uri: Uri,
        val expected: ProjectSyncFingerprint,
    )

    private data class ProjectSyncJournal(
        val operation: Int,
        val phase: Int,
        val treeUri: String,
        val parentUri: String,
        val path: String,
        val targetName: String,
        val stagingName: String,
        val backupName: String,
        val expected: String,
        val hadOriginal: Boolean,
    )

    private data class ProjectSyncResult(
        var pulled: Int = 0,
        var pushed: Int = 0,
        var deferredDeletes: Int = 0,
        var androidDeletesApplied: Int = 0,
        val conflictPaths: MutableSet<String> = linkedSetOf(),
        val ignoredDocumentIds: MutableSet<String> = linkedSetOf(),
        val deletedDocuments: MutableList<ProjectSyncDeletedDocument> = ArrayList(),
    )

    private class PackageIoScratch {
        val requestBuffer: ByteBuffer = ByteBuffer.allocateDirect(512)
        val messageBuffer: ByteBuffer = ByteBuffer.allocateDirect(192)
        val outputBuffer: ByteBuffer =
            ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val transferBuffer = ByteArray(64 * 1024)
    }

    private class BoundedByteRing(capacity: Int) {
        private val bytes = ByteArray(capacity)
        private var start = 0
        private var size = 0

        @Synchronized
        fun clear() {
            start = 0
            size = 0
        }

        @Synchronized
        fun append(
            source: ByteArray,
            length: Int,
        ) {
            if (length <= 0) {
                return
            }
            val sourceStart =
                if (length >= bytes.size) {
                    length - bytes.size
                } else {
                    0
                }
            val retained = length - sourceStart
            if (retained >= bytes.size) {
                System.arraycopy(source, sourceStart, bytes, 0, bytes.size)
                start = 0
                size = bytes.size
                return
            }
            val overflow = (size + retained - bytes.size).coerceAtLeast(0)
            if (overflow != 0) {
                start = (start + overflow) % bytes.size
                size -= overflow
            }
            var destination = (start + size) % bytes.size
            var copied = 0
            while (copied < retained) {
                val count = minOf(retained - copied, bytes.size - destination)
                System.arraycopy(source, sourceStart + copied, bytes, destination, count)
                copied += count
                destination = 0
            }
            size += retained
        }

        @Synchronized
        fun snapshotTail(maximum: Int): String {
            val length = minOf(size, maximum)
            if (length == 0) {
                return ""
            }
            val result = ByteArray(length)
            var source = (start + size - length) % bytes.size
            var copied = 0
            while (copied < length) {
                val count = minOf(length - copied, bytes.size - source)
                System.arraycopy(bytes, source, result, copied, count)
                copied += count
                source = 0
            }
            return String(result, StandardCharsets.UTF_8)
        }
    }

    private class FixedByteQueue(capacity: Int) {
        private val bytes = ByteArray(capacity)
        private var start = 0
        private var size = 0

        @Synchronized
        fun clear() {
            start = 0
            size = 0
        }

        @Synchronized
        fun offerLine(source: ByteArray): Boolean {
            val required = source.size + 1
            if (required > bytes.size - size) {
                return false
            }
            append(source, source.size)
            bytes[(start + size) % bytes.size] = '\n'.code.toByte()
            size++
            return true
        }

        @Synchronized
        fun offer(
            source: ByteArray,
            length: Int,
        ): Boolean {
            if (length !in 1..source.size || length > bytes.size - size) {
                return false
            }
            append(source, length)
            return true
        }

        @Synchronized
        fun peek(destination: ByteArray): Int {
            val length = minOf(size, destination.size)
            var source = start
            var copied = 0
            while (copied < length) {
                val count = minOf(length - copied, bytes.size - source)
                System.arraycopy(bytes, source, destination, copied, count)
                copied += count
                source = 0
            }
            return length
        }

        @Synchronized
        fun discard(length: Int) {
            check(length in 0..size)
            start = (start + length) % bytes.size
            size -= length
        }

        private fun append(
            source: ByteArray,
            length: Int,
        ) {
            var destination = (start + size) % bytes.size
            var copied = 0
            while (copied < length) {
                val count = minOf(length - copied, bytes.size - destination)
                System.arraycopy(source, copied, bytes, destination, count)
                copied += count
                destination = 0
            }
            size += length
        }
    }

    override fun onCreate() {
        super.onCreate()
        removeStaleAurBuildOutputs()
        createSessionNotificationChannel()
        if (NativeRuntime.nativeProtocolVersion() != NativeRuntime.PROTOCOL_VERSION) {
            Log.e(TAG, "Native protocol version mismatch")
            stopSelf()
            return
        }
        handle = NativeRuntime.nativeCreate()
        if (handle == 0L) {
            Log.e(TAG, "Native runtime creation failed")
            stopSelf()
            return
        }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = connectivityManager != null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not observe Android network changes", error)
        }
        startBootstrap(handle)
    }

    private fun removeStaleAurBuildOutputs() {
        var removed = 0
        try {
            Files.newDirectoryStream(cacheDir.toPath(), AUR_BUILD_OUTPUT_GLOB).use { entries ->
                for (path in entries) {
                    if (removed >= MAX_STALE_AUR_BUILD_OUTPUTS) {
                        Log.w(TAG, "Stopped bounded stale AUR output cleanup")
                        break
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        Log.w(TAG, "Ignored unsafe stale AUR output: ${path.fileName}")
                        continue
                    }
                    if (Files.deleteIfExists(path)) {
                        removed++
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not clean stale AUR build output", error)
        }
        if (removed != 0) {
            Log.i(TAG, "Removed $removed stale AUR build output files")
        }
    }

    @Synchronized
    private fun authorizeLauncher(
        androidPackage: String,
        descriptorIdHex: String,
        generation: Long,
    ): LauncherAuthorization? {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            androidPackage.length != 53 ||
            descriptorIdHex.length != 64 ||
            generation !in 1..Int.MAX_VALUE.toLong()
        ) {
            return null
        }
        val request = "A1\t$androidPackage\t$descriptorIdHex\t$generation\n"
        val requestBytes = request.toByteArray(StandardCharsets.US_ASCII)
        if (requestBytes.size > launcherAuthorizationRequestBuffer.capacity()) {
            return null
        }
        launcherAuthorizationRequestBuffer.clear()
        launcherAuthorizationRequestBuffer.put(requestBytes)
        launcherAuthorizationOutputBuffer.clear()
        val length =
            NativeRuntime.nativeAuthorizeLauncher(
                activeHandle,
                launcherAuthorizationRequestBuffer,
                requestBytes.size,
                launcherAuthorizationOutputBuffer,
            )
        if (length <= 0 || length > launcherAuthorizationOutputBytes.size) {
            return null
        }
        launcherAuthorizationOutputBuffer.position(0)
        launcherAuthorizationOutputBuffer.get(launcherAuthorizationOutputBytes, 0, length)
        val response =
            String(
                launcherAuthorizationOutputBytes,
                0,
                length,
                StandardCharsets.UTF_8,
            )
        val fields = response.removeSuffix("\n").split('\t', limit = 3)
        if (
            fields.size != 3 ||
            fields[0] != "A1" ||
            fields[1] !in setOf("0", "1") ||
            fields[2].isEmpty() ||
            fields[2].length > 256
        ) {
            return null
        }
        return LauncherAuthorization(fields[2], fields[1] == "1")
    }

    @Synchronized
    private fun openLauncherProcess(
        androidPackage: String,
        descriptorIdHex: String,
        generation: Long,
        waylandDisplay: String,
        dark: Boolean,
        fontPercent: Int,
        controlVisualDp: Int,
        controlTargetDp: Int,
        accent: Int,
        background: Int,
        foreground: Int,
    ): Long {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            androidPackage.length != 53 ||
            descriptorIdHex.length != 64 ||
            generation !in 1..Int.MAX_VALUE.toLong() ||
            fontPercent !in 100..200 ||
            controlVisualDp !in 12..48 ||
            controlTargetDp !in 24..64 ||
            controlTargetDp < controlVisualDp ||
            waylandDisplay.isEmpty() ||
            waylandDisplay.length > 64 ||
            !waylandDisplay.all { character ->
                character in 'a'..'z' ||
                    character in 'A'..'Z' ||
                    character in '0'..'9' ||
                    character == '.' ||
                    character == '_' ||
                    character == '-'
            }
        ) {
            return 0L
        }
        val request =
            "G2\t$androidPackage\t$descriptorIdHex\t$generation\t$waylandDisplay\t" +
                "${if (dark) 1 else 0}\t$fontPercent\t$controlVisualDp\t$controlTargetDp\t" +
                "${rgbHex(accent)}\t${rgbHex(background)}\t${rgbHex(foreground)}\n"
        val requestBytes = request.toByteArray(StandardCharsets.US_ASCII)
        if (requestBytes.size > launcherAuthorizationRequestBuffer.capacity()) {
            return 0L
        }
        launcherAuthorizationRequestBuffer.clear()
        launcherAuthorizationRequestBuffer.put(requestBytes)
        launcherAuthorizationOutputBuffer.clear()
        val launcherHandle =
            NativeRuntime.nativeOpenLauncherProcess(
                activeHandle,
                launcherAuthorizationRequestBuffer,
                requestBytes.size,
                launcherAuthorizationOutputBuffer,
            )
        if (launcherHandle <= 0L) {
            launcherAuthorizationOutputBuffer.position(0)
            launcherAuthorizationOutputBuffer.get(launcherAuthorizationOutputBytes)
            val length =
                launcherAuthorizationOutputBytes.indexOf(0).let { index ->
                    if (index < 0) launcherAuthorizationOutputBytes.size else index
                }
            val detail =
                String(
                    launcherAuthorizationOutputBytes,
                    0,
                    length,
                    StandardCharsets.UTF_8,
                ).ifEmpty { "native result $launcherHandle" }
            Log.e(TAG, "Could not launch graphical Linux process: $detail")
            return 0L
        }
        return launcherHandle
    }

    private fun rgbHex(color: Int): String =
        String.format(
            java.util.Locale.ROOT,
            "%02x%02x%02x",
            color shr 16 and 0xff,
            color shr 8 and 0xff,
            color and 0xff,
        )

    @Synchronized
    private fun closeLauncherProcess(launcherHandle: Long): Boolean {
        val activeHandle = readyHandle
        return activeHandle != 0L &&
            launcherHandle > 0L &&
            NativeRuntime.nativeCloseLauncherProcess(activeHandle, launcherHandle) == 0
    }

    @Synchronized
    private fun launcherProcessExitStatus(launcherHandle: Long): Int? {
        val activeHandle = readyHandle
        if (activeHandle == 0L || launcherHandle <= 0L) {
            return null
        }
        val encoded =
            NativeRuntime.nativeLauncherProcessExitStatus(activeHandle, launcherHandle)
        if (encoded < 0L) {
            Log.w(TAG, "Could not read graphical Linux process status: native result $encoded")
            return null
        }
        if (encoded and 1L == 0L) {
            return null
        }
        return (encoded ushr 1).toInt()
    }

    @Synchronized
    private fun launcherProcessLog(launcherHandle: Long): String {
        val activeHandle = readyHandle
        if (activeHandle == 0L || launcherHandle <= 0L) {
            return ""
        }
        launcherProcessLogBuffer.clear()
        val length =
            NativeRuntime.nativeReadLauncherProcessLog(
                activeHandle,
                launcherHandle,
                launcherProcessLogBuffer,
            )
        if (length <= 0 || length > launcherProcessLogBytes.size) {
            return ""
        }
        launcherProcessLogBuffer.position(0)
        launcherProcessLogBuffer.get(launcherProcessLogBytes, 0, length)
        return String(launcherProcessLogBytes, 0, length, StandardCharsets.UTF_8)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP_SHELL) {
            stopSharedShell(waitForWorker = false)
        } else if (
            intent?.action == ACTION_LAUNCHER_INSTALLED ||
            intent?.action == ACTION_LAUNCHER_REMOVED ||
            intent?.action == ACTION_LAUNCHER_FAILED ||
            intent?.action == ACTION_LAUNCHER_CANCELLED
        ) {
            val androidPackage = intent.getStringExtra(EXTRA_LAUNCHER_PACKAGE).orEmpty()
            val generation = intent.getLongExtra(EXTRA_LAUNCHER_GENERATION, 0)
            if (
                LAUNCHER_PACKAGE.matches(androidPackage) &&
                generation in 1..Int.MAX_VALUE.toLong()
            ) {
                pendingLauncherResultPackage = androidPackage
                pendingLauncherResultGeneration = generation
                pendingLauncherResultAction = intent.action.orEmpty()
                processPendingLauncherResult()
            } else {
                Log.e(TAG, "Rejected invalid launcher result")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        boundClients++
        stopWhenUnobservedRequested = false
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        if (stopWhenUnobservedRequested) {
            stopIfUnobservedAndIdle()
        }
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopWhenUnobservedRequested = true
        if (hasActiveRuntimeWork()) {
            Log.i(TAG, "Task removed; keeping active runtime work")
        } else {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        dnsRootReady = false
        dnsRefreshPending.set(false)
        if (networkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not unregister Android network observer", error)
            }
            networkCallbackRegistered = false
        }
        dnsThread?.interrupt()
        dnsThread = null
        stopSharedShell(waitForWorker = true)
        removeSessionNotification()
        val activeHandle = handle
        handle = 0L
        readyHandle = 0L
        bootstrapThread?.interrupt()
        bootstrapThread = null
        bootstrapActive = false
        launcherPublisherActive.set(false)
        catalogThread?.interrupt()
        catalogThread = null
        packageCancellationRequested = true
        activePackageConnection?.disconnect()
        packageThread?.interrupt()
        packageThread = null
        commandThread?.interrupt()
        commandThread = null
        if (folderMirrorRunning && activeHandle != 0L) {
            if (folderSyncRunning) {
                NativeRuntime.nativeCancelProjectSync(activeHandle)
                NativeRuntime.nativeAbortProjectSync(activeHandle)
            } else {
                NativeRuntime.nativeCancelProjectMirror(activeHandle)
            }
        }
        storageThread?.interrupt()
        storageThread = null
        if (activeHandle != 0L) {
            NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPING)
            NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPED)
            if (!NativeRuntime.nativeDestroy(activeHandle)) {
                Log.e(TAG, "Native runtime handle was already closed")
            }
        }
        Log.i(TAG, "Shared Rust runtime stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ArchpheneRuntime"
        private const val SHELL_SCROLLBACK_BYTES = 16 * 1024
        private const val SHELL_DISPLAY_BYTES = 4 * 1024
        private const val SHELL_INPUT_BYTES = 8 * 1024
        private const val SHELL_INPUT_CHARACTERS = 2 * 1024
        private const val SHELL_IO_BYTES = 4 * 1024
        private const val SHELL_READ_BATCHES = 4
        private const val DEFAULT_SHELL_ROWS = 24
        private const val DEFAULT_SHELL_COLUMNS = 48
        private const val MIN_SHELL_ROWS = 2
        private const val MAX_SHELL_ROWS = 200
        private const val MIN_SHELL_COLUMNS = 2
        private const val MAX_SHELL_COLUMNS = 400
        private const val SHELL_CHOICE_LIMIT = 8
        private const val SHELL_FIELD_LIMIT = 64
        private const val AVAILABLE_PACKAGE_LIMIT = 100
        private const val PACKAGE_CACHE_ENTRY_LIMIT = 4096
        private const val PACKAGE_CACHE_PAGE_SIZE = 32
        private const val MAX_PACKAGE_CACHE_SELECTION = 256
        private const val PACKAGE_CACHE_SELECTION_BYTES = 32 * 1024
        private const val DEBUG_AUR_FIXTURE_MAINTAINER = "Archphene test maintainer"
        private val DEBUG_AUR_PACKAGE_NAME = Regex("[a-z0-9@._+\\-]{1,96}")
        private val AUR_PACKAGE_NAME = Regex("[A-Za-z0-9@+._-]{1,128}")
        private val AUR_SOURCE_FILENAME = Regex("[A-Za-z0-9@+,._-]{1,240}")
        private val AUR_BUILT_PACKAGE_FILENAME =
            Regex("[A-Za-z0-9@+:._-]{1,240}\\.pkg\\.tar\\.(xz|zst)")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val AUR_BUILDER_INPUT_SNAPSHOT = 0
        private const val AUR_BUILDER_INPUT_SOURCE = 1
        private const val AUR_BUILDER_TRANSACTION_BEGIN_CLOSURE =
            IBinder.FIRST_CALL_TRANSACTION + 1
        private const val AUR_BUILDER_TRANSACTION_STAGE_BATCH =
            IBinder.FIRST_CALL_TRANSACTION + 2
        private const val AUR_BUILDER_TRANSACTION_FINISH_CLOSURE =
            IBinder.FIRST_CALL_TRANSACTION + 3
        private const val AUR_BUILDER_TRANSACTION_ABORT_CLOSURE =
            IBinder.FIRST_CALL_TRANSACTION + 4
        private const val AUR_BUILDER_TRANSACTION_BEGIN_PROVISION =
            IBinder.FIRST_CALL_TRANSACTION + 5
        private const val AUR_BUILDER_TRANSACTION_EXTRACT_PROVISION_BATCH =
            IBinder.FIRST_CALL_TRANSACTION + 6
        private const val AUR_BUILDER_TRANSACTION_FINISH_PROVISION =
            IBinder.FIRST_CALL_TRANSACTION + 7
        private const val AUR_BUILDER_TRANSACTION_ABORT_PROVISION =
            IBinder.FIRST_CALL_TRANSACTION + 8
        private const val AUR_BUILDER_TRANSACTION_PROBE_RUNTIME =
            IBinder.FIRST_CALL_TRANSACTION + 9
        private const val AUR_BUILDER_TRANSACTION_PREPARE_RECIPE =
            IBinder.FIRST_CALL_TRANSACTION + 10
        private const val AUR_BUILDER_TRANSACTION_START_BUILD =
            IBinder.FIRST_CALL_TRANSACTION + 11
        private const val AUR_BUILDER_TRANSACTION_POLL_BUILD =
            IBinder.FIRST_CALL_TRANSACTION + 12
        private const val AUR_BUILDER_TRANSACTION_CANCEL_BUILD =
            IBinder.FIRST_CALL_TRANSACTION + 13
        private const val AUR_BUILDER_TRANSACTION_VERIFY_OUTPUT =
            IBinder.FIRST_CALL_TRANSACTION + 14
        private const val AUR_BUILDER_PACKAGE_BATCH = 8
        private const val AUR_BUILD_POLL_MILLIS = 100L
        private const val AUR_BUILD_VISIBLE_LOG_CHARACTERS = 8 * 1024
        private const val AUR_BUILD_OUTPUT_GLOB = ".aur-*.pkg"
        private const val MAX_STALE_AUR_BUILD_OUTPUTS = 64
        private const val AUR_REDIRECT_LIMIT = 5
        private const val AUR_STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
        private const val AUR_TOTAL_SOURCE_MAX_BYTES = 8L * 1024 * 1024 * 1024
        private const val SHELL_PREFERENCES = "terminal"
        private const val SHELL_PREFERENCE_ID = "shared_shell_id"
        private const val PACKAGE_RECOVERY_PREFERENCES = "package_recovery"
        private const val PACKAGE_RECOVERY_JOB_ID = "job_id"
        private const val PACKAGE_RECOVERY_PACKAGE = "package"
        private const val PACKAGE_RECOVERY_OPERATION = "operation"
        private const val PACKAGE_RECOVERY_STATE = "state"
        private const val PACKAGE_RECOVERY_FAILURE = "failure"
        private const val PACKAGE_RECOVERY_RESULT = "result"
        private const val PACKAGE_JOB_TEST_PREFERENCES = "package_job_test"
        private const val PACKAGE_JOB_TEST_CACHE_HOLD_MILLIS = "cache_hold_ms"
        private const val PACKAGE_JOB_TEST_CATALOG_RECOVERY = "catalog_recovery_fixture"
        private const val PACKAGE_JOB_TEST_WORKER_HOLD_MILLIS = "worker_hold_ms"
        private const val MAX_PACKAGE_JOB_TEST_HOLD_MILLIS = 30_000L
        private const val MIN_PACKAGE_PHASE_TEST_HOLD_MILLIS = 750L
        private const val MAX_ANDROID_DNS_SERVERS = 4
        private const val SESSION_NOTIFICATION_ID = 0x4152
        private const val SESSION_NOTIFICATION_CHANNEL = "archphene_linux_sessions"
        private const val ACTION_STOP_SHELL = "org.archphene.app.action.STOP_SHARED_SHELL"
        const val ACTION_LAUNCHER_INSTALLED =
            "org.archphene.app.action.LAUNCHER_INSTALLED"
        const val ACTION_LAUNCHER_REMOVED =
            "org.archphene.app.action.LAUNCHER_REMOVED"
        const val ACTION_LAUNCHER_FAILED =
            "org.archphene.app.action.LAUNCHER_FAILED"
        const val ACTION_LAUNCHER_CANCELLED =
            "org.archphene.app.action.LAUNCHER_CANCELLED"
        const val EXTRA_LAUNCHER_PACKAGE = "launcherPackage"
        const val EXTRA_LAUNCHER_GENERATION = "launcherGeneration"
        private val LAUNCHER_PACKAGE =
            Regex("org\\.archphene\\.linux\\.p[0-9a-f]{32}")
        private val LAUNCHER_DESCRIPTOR = Regex("[0-9a-f]{64}")
        private const val LAUNCHER_STATUS_AWAITING_INSTALL = 3
        private const val LAUNCHER_STATUS_NEEDS_REMOVAL = 5
        private const val LAUNCHER_STATUS_AWAITING_REMOVAL = 6
        private const val LAUNCHER_STATUS_CANCELLED = 8
        private const val LAUNCHER_STATUS_DISMISSED = 9
        private const val LAUNCHER_STATUS_NEEDS_REVIEW = 10
        private const val LAUNCHER_ICON_BYTES_LIMIT = 1024 * 1024
        private const val LAUNCHER_ICON_DIMENSION_LIMIT = 2048
        private const val LAUNCHER_ICON_PIXEL_LIMIT = 4L * 1024 * 1024
        private const val STORAGE_PREFERENCES = "storage"
        private const val STORAGE_STATE = "import_state"
        private const val STORAGE_MESSAGE = "import_message"
        private const val STORAGE_IDLE = "idle"
        private const val STORAGE_RUNNING = "running"
        private const val STORAGE_COMPLETE = "complete"
        private const val STORAGE_FAILED = "failed"
        private const val FOLDER_URI = "folder_tree_uri"
        private const val FOLDER_LABEL = "folder_label"
        private const val FOLDER_STATE = "folder_state"
        private const val FOLDER_DISCONNECTED = "disconnected"
        private const val FOLDER_CONNECTED = "connected"
        private const val FOLDER_REVOKED = "revoked"
        private const val FOLDER_MIRROR_URI = "folder_mirror_uri"
        private const val FOLDER_MIRROR_NAME = "folder_mirror_name"
        private const val FOLDER_MAPPING_ID = "folder_mapping_id"
        private const val FOLDER_ONBOARDING_SEEN = "folder_onboarding_seen"
        private const val MAX_MIRROR_ENTRIES = 10_000
        private const val MAX_MIRROR_DEPTH = 64
        private const val MAX_MIRROR_PATH_BYTES = 4 * 1024
        private const val MAX_MIRROR_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val PROJECT_SYNC_PROVIDER_DEADLINE_MILLIS = 30_000L
        private const val SYNC_PLAN_HEADER_BYTES = 136
        private const val SYNC_KIND_ABSENT = 0
        private const val SYNC_KIND_DIRECTORY = 1
        private const val SYNC_KIND_FILE = 2
        private const val SYNC_ACTION_CONVERGED = 0
        private const val SYNC_ACTION_PUSH_ANDROID = 1
        private const val SYNC_ACTION_PULL_LINUX = 2
        private const val SYNC_ACTION_DELETE_ANDROID = 3
        private const val SYNC_ACTION_DELETE_LINUX = 4
        private const val SYNC_ACTION_CONFLICT = 5
        private const val SYNC_LOCAL_OPEN_FILE = 1
        private const val SYNC_LOCAL_PULL_FILE = 2
        private const val SYNC_LOCAL_DELETE = 3
        private const val SYNC_LOCAL_CREATE_DIRECTORY = 4
        private const val SYNC_LOCAL_PRESERVE_CONFLICT = 5
        private const val SYNC_JOURNAL_FILE = "project-sync-journal-v1"
        private const val MAX_SYNC_JOURNAL_BYTES = 64 * 1024
        private const val SYNC_JOURNAL_PUSH = 1
        private const val SYNC_JOURNAL_DELETE = 2
        private const val SYNC_JOURNAL_PREPARED = 1
        private const val SYNC_JOURNAL_STAGED = 2
        private const val SYNC_JOURNAL_BACKED_UP = 3
        private const val SYNC_JOURNAL_PUBLISHED = 4
        private const val SYNC_JOURNAL_COMMITTED = 5
        private const val SYNC_TEST_PREFERENCES = "project_sync_test"
        private const val SYNC_TEST_PHASE = "hold_phase"
        private const val SYNC_TEST_HOLD_MILLIS = "hold_ms"
        private const val SYNC_TEST_PHASE_BACKED_UP = "backed-up"
        private const val SYNC_TEST_PHASE_PUBLISHED = "published"
        private const val SYNC_TEST_PHASE_COMMITTED = "committed"
        private const val MAX_SYNC_TEST_HOLD_MILLIS = 30_000L
        private const val MAX_STORAGE_URI_BYTES = 4 * 1024
        private const val MAX_STORAGE_REQUEST_BYTES = 4 * 1024
        private const val MAX_STORAGE_NAME_BYTES = 255
        private const val MAX_FOLDER_LABEL_BYTES = 128
        private const val MAX_STORAGE_IMPORT_BYTES = 16L * 1024 * 1024 * 1024
        private val PROCESS_STORAGE_ACTIVE = AtomicBoolean()
        private val FOLDER_MAPPING_ID_PATTERN = Regex("[0-9a-f]{32}")
        private val FOLDER_MAPPING_RANDOM = SecureRandom()
        private val SYNC_JOURNAL_MAGIC =
            "APSJ0001".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun restoreStorageStatus() {
        val preferences = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
        val state = preferences.getString(STORAGE_STATE, STORAGE_IDLE) ?: STORAGE_IDLE
        val message =
            preferences.getString(
                STORAGE_MESSAGE,
                "Import an Android file into ~/Downloads",
            ) ?: "Import an Android file into ~/Downloads"
        if (state == STORAGE_RUNNING) {
            storageStatus = "The previous file import was interrupted. Choose the file again."
            preferences
                .edit()
                .putString(STORAGE_STATE, STORAGE_FAILED)
                .putString(STORAGE_MESSAGE, storageStatus)
                .commit()
        } else {
            storageStatus = message
        }
        try {
            restoreFolderGrant(preferences)
        } catch (error: Exception) {
            folderConnected = false
            folderStatus = "Could not validate Android folder access. Connect it again."
            Log.e(TAG, "Could not restore Android folder state", error)
        } finally {
            folderStateReady = true
        }
    }

    private fun persistStorageStatus(
        state: String,
        message: String,
    ) {
        storageStatus = message
        getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(STORAGE_STATE, state)
            .putString(STORAGE_MESSAGE, message)
            .commit()
    }

    private fun restoreFolderGrant(preferences: SharedPreferences) {
        val savedLabel =
            preferences.getString(FOLDER_LABEL, null)
                ?.takeIf(::safeFolderLabel)
                ?: "selected folder"
        val savedUri = preferences.getString(FOLDER_URI, null)
        val state = preferences.getString(FOLDER_STATE, FOLDER_DISCONNECTED)
        folderOnboardingNeeded =
            !preferences.getBoolean(FOLDER_ONBOARDING_SEEN, false) &&
                savedUri == null &&
                state == FOLDER_DISCONNECTED
        if (savedUri == null) {
            folderConnected = false
            folderWritable = false
            folderUri = ""
            folderLabel = savedLabel
            folderMirrorPath = ""
            folderStatus =
                if (state == FOLDER_REVOKED) {
                    "Access to $savedLabel was revoked. Connect it again."
                } else {
                    "No Android folder connected"
                }
            return
        }
        val uri = runCatching { Uri.parse(savedUri) }.getOrNull()
        val permission =
            uri
                ?.takeIf(::safeTreeUri)
                ?.let(::persistedFolderPermission)
        if (permission?.first == true) {
            folderConnected = true
            folderWritable = permission.second
            folderUri = savedUri
            folderLabel = savedLabel
            folderMirrorPath = restoredMirrorPath(preferences, savedUri)
            folderStatus =
                connectedFolderStatus(savedLabel, permission.second, folderMirrorPath)
            return
        }
        folderConnected = false
        folderWritable = false
        folderUri = ""
        folderLabel = savedLabel
        folderMirrorPath = ""
        folderStatus = "Access to $savedLabel was revoked. Connect it again."
        if (
            !preferences
                .edit()
                .remove(FOLDER_URI)
                .putString(FOLDER_LABEL, savedLabel)
                .putString(FOLDER_STATE, FOLDER_REVOKED)
                .commit()
        ) {
            Log.e(TAG, "Could not persist revoked Android folder state")
        }
    }

    private fun restoredMirrorPath(
        preferences: SharedPreferences,
        activeUri: String,
    ): String {
        if (preferences.getString(FOLDER_MIRROR_URI, null) != activeUri) {
            return ""
        }
        val name =
            preferences.getString(FOLDER_MIRROR_NAME, null)
                ?.takeIf(::safeFolderLabel)
                ?: return ""
        val path = File(filesDir, "arch-root/home/archphene/Projects/$name")
        val mode = runCatching { Os.lstat(path.absolutePath).st_mode }.getOrNull() ?: return ""
        return if (mode and OsConstants.S_IFMT == OsConstants.S_IFDIR) {
            "~/Projects/$name"
        } else {
            ""
        }
    }

    @Synchronized
    private fun requestFolderGrant(
        uri: Uri,
        resultFlags: Int,
    ): Boolean {
        val persistable =
            resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val requestedFlags =
            resultFlags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (
            !safeTreeUri(uri) ||
            !persistable ||
            requestedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            folderStatus = "Choose an Android folder that allows persistent read access"
            return false
        }
        if (!PROCESS_STORAGE_ACTIVE.compareAndSet(false, true)) {
            return false
        }
        folderOperationActive = true
        folderStatus = "Connecting the selected Android folder…"
        val worker =
            Thread(
                {
                    try {
                        connectFolderGrant(uri, requestedFlags)
                    } catch (error: Exception) {
                        folderStatus =
                            "Folder connection failed: " +
                                (error.message ?: error.javaClass.simpleName)
                        Log.e(TAG, "Android folder connection failed", error)
                    } finally {
                        finishFolderOperation()
                    }
                },
                "ArchpheneFolderGrant",
            )
        storageThread = worker
        return try {
            worker.start()
            promoteWorkToForeground()
            true
        } catch (error: Exception) {
            storageThread = null
            folderOperationActive = false
            PROCESS_STORAGE_ACTIVE.set(false)
            folderStatus =
                "Folder connection failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "Could not start Android folder connection", error)
            false
        }
    }

    private fun connectFolderGrant(
        uri: Uri,
        requestedFlags: Int,
    ) {
        val preferences = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
        val previousUri =
            preferences
                .getString(FOLDER_URI, null)
                ?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull() }
                ?.takeIf(::safeTreeUri)
        var acquired = false
        try {
            contentResolver.takePersistableUriPermission(uri, requestedFlags)
            acquired = true
            val permission =
                persistedFolderPermission(uri)
                    ?.takeIf { it.first }
                    ?: throw SecurityException("Android did not persist read access")
            val label = queryFolderLabel(uri)
            val encodedUri = uri.toString()
            val editor =
                preferences
                    .edit()
                    .putString(FOLDER_URI, encodedUri)
                    .putString(FOLDER_LABEL, label)
                    .putString(FOLDER_STATE, FOLDER_CONNECTED)
                    .putBoolean(FOLDER_ONBOARDING_SEEN, true)
            if (preferences.getString(FOLDER_MIRROR_URI, null) != encodedUri) {
                editor
                    .remove(FOLDER_MIRROR_URI)
                    .remove(FOLDER_MIRROR_NAME)
                    .remove(FOLDER_MAPPING_ID)
            }
            if (!editor.commit()) {
                throw IllegalStateException("Could not save the Android folder grant")
            }
            if (previousUri != null && previousUri != uri) {
                runCatching { releaseFolderPermission(previousUri) }
                    .onFailure { error ->
                        Log.w(TAG, "Could not release replaced Android folder grant", error)
                    }
            }
            folderConnected = true
            folderOnboardingNeeded = false
            folderWritable = permission.second
            folderUri = encodedUri
            folderLabel = label
            folderMirrorPath = restoredMirrorPath(preferences, encodedUri)
            folderStatus =
                connectedFolderStatus(label, permission.second, folderMirrorPath)
            Log.i(
                TAG,
                "Android folder connected label=$label writable=${permission.second}",
            )
        } catch (error: Exception) {
            if (acquired && previousUri != uri) {
                runCatching { releaseFolderPermission(uri) }
                    .onFailure { cleanupError ->
                        Log.e(TAG, "Could not release failed Android folder grant", cleanupError)
                    }
            }
            throw error
        }
    }

    @Synchronized
    private fun requestFolderMirror(): Boolean {
        val activeHandle = readyHandle
        val activeUri =
            folderUri
                .takeIf(String::isNotEmpty)
                ?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull() }
                ?.takeIf(::safeTreeUri)
        val projectName = folderLabel.takeIf(::safeFolderLabel)
        if (
            activeHandle == 0L ||
            !folderConnected ||
            activeUri == null ||
            projectName == null ||
            folderMirrorPath.isNotEmpty()
        ) {
            return false
        }
        if (!PROCESS_STORAGE_ACTIVE.compareAndSet(false, true)) {
            return false
        }
        folderOperationActive = true
        folderMirrorRunning = true
        folderMirrorCancellationRequested = false
        folderStatus = "Preparing ~/Projects/$projectName…"
        val worker =
            Thread(
                {
                    var nativeStarted = false
                    val preferences = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
                    try {
                        val mappingId =
                            preferences
                                .getString(FOLDER_MAPPING_ID, null)
                                ?.takeIf(FOLDER_MAPPING_ID_PATTERN::matches)
                                ?.takeIf {
                                    preferences.getString(FOLDER_MIRROR_URI, null) ==
                                        activeUri.toString() &&
                                        preferences.getString(FOLDER_MIRROR_NAME, null) ==
                                        projectName
                                }
                                ?: newFolderMappingId()
                        if (
                            !preferences
                                .edit()
                                .putString(FOLDER_MIRROR_URI, activeUri.toString())
                                .putString(FOLDER_MIRROR_NAME, projectName)
                                .putString(FOLDER_MAPPING_ID, mappingId)
                                .commit()
                        ) {
                            throw IllegalStateException("Could not save the project mirror intent")
                        }
                        checkFolderMirrorCancellation()
                        val request = ByteBuffer.allocateDirect(MAX_MIRROR_PATH_BYTES)
                        val output = ByteBuffer.allocateDirect(NativeRuntime.STORAGE_OUTPUT_SIZE)
                        val beginLength =
                            putProjectMirrorBeginRequest(request, projectName, mappingId)
                        val beginResult =
                            NativeRuntime.nativeBeginProjectMirror(
                                activeHandle,
                                request,
                                beginLength,
                                output,
                            )
                        requireMirrorSuccess(beginResult.toLong(), output, "begin project mirror")
                        nativeStarted = true
                        checkFolderMirrorCancellation()
                        val progress = MirrorProgress()
                        val projection =
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_SIZE,
                            )
                        mirrorDocumentChildren(
                            activeHandle,
                            activeUri,
                            DocumentsContract.getTreeDocumentId(activeUri),
                            "",
                            0,
                            projection,
                            request,
                            output,
                            progress,
                        )
                        checkFolderMirrorCancellation()
                        output.clear()
                        val finishResult =
                            NativeRuntime.nativeFinishProjectMirror(activeHandle, output)
                        requireMirrorSuccess(
                            finishResult.toLong(),
                            output,
                            "publish project mirror",
                        )
                        nativeStarted = false
                        val report = readCString(output).split('\t')
                        if (
                            report.size != 2 ||
                            report[0].toIntOrNull() != progress.entries ||
                            report[1].toLongOrNull() != progress.bytes
                        ) {
                            throw IllegalStateException("Invalid native project mirror report")
                        }
                        folderMirrorRunning = false
                        folderMirrorCancellationRequested = false
                        folderMirrorPath = "~/Projects/$projectName"
                        folderStatus =
                            connectedFolderStatus(
                                projectName,
                                folderWritable,
                                folderMirrorPath,
                            )
                        Log.i(
                            TAG,
                            "Android folder mirrored name=$projectName " +
                                "entries=${progress.entries} bytes=${progress.bytes}",
                        )
                    } catch (error: Exception) {
                        if (nativeStarted) {
                            NativeRuntime.nativeAbortProjectMirror(activeHandle)
                        }
                        preferences
                            .edit()
                            .remove(FOLDER_MIRROR_URI)
                            .remove(FOLDER_MIRROR_NAME)
                            .remove(FOLDER_MAPPING_ID)
                            .commit()
                        folderMirrorPath = ""
                        if (folderMirrorCancellationRequested) {
                            folderStatus = "Project mirror cancelled"
                            Log.i(TAG, "Android folder mirror cancelled name=$projectName")
                        } else {
                            folderStatus =
                                "Mirror failed: ${error.message ?: error.javaClass.simpleName}"
                            Log.e(TAG, "Android folder mirror failed", error)
                        }
                    } finally {
                        finishFolderOperation()
                    }
                },
                "ArchpheneFolderMirror",
            )
        storageThread = worker
        return try {
            worker.start()
            promoteWorkToForeground()
            true
        } catch (error: Exception) {
            storageThread = null
            folderOperationActive = false
            folderMirrorRunning = false
            folderMirrorCancellationRequested = false
            PROCESS_STORAGE_ACTIVE.set(false)
            folderStatus =
                "Mirror failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "Could not start Android folder mirror", error)
            false
        }
    }

    @Synchronized
    private fun requestFolderSync(): Boolean {
        val activeHandle = readyHandle
        val activeUri =
            folderUri
                .takeIf(String::isNotEmpty)
                ?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull() }
                ?.takeIf(::safeTreeUri)
        val preferences = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
        val mappingId =
            preferences
                .getString(FOLDER_MAPPING_ID, null)
                ?.takeIf(FOLDER_MAPPING_ID_PATTERN::matches)
        if (
            activeHandle == 0L ||
            !folderConnected ||
            activeUri == null ||
            mappingId == null ||
            folderMirrorPath.isEmpty()
        ) {
            return false
        }
        if (!PROCESS_STORAGE_ACTIVE.compareAndSet(false, true)) {
            return false
        }
        folderOperationActive = true
        folderMirrorRunning = true
        folderSyncRunning = true
        folderMirrorCancellationRequested = false
        folderStatus = "Scanning Linux project…"
        val worker =
            Thread(
                {
                    var nativeStarted = false
                    try {
                        val request =
                            ByteBuffer.allocateDirect(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE)
                        val output =
                            ByteBuffer.allocateDirect(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE)
                        val aggregate = ProjectSyncResult()
                        var pass = 0
                        var repeatForDeferredDeletes: Boolean
                        do {
                            pass++
                            val beginLength = putProjectSyncRequest(request, mappingId)
                            output.clear()
                            requireMirrorSuccess(
                                NativeRuntime.nativeBeginProjectSync(
                                    activeHandle,
                                    request,
                                    beginLength,
                                    output,
                                ).toLong(),
                                output,
                                "begin project synchronization",
                            )
                            nativeStarted = true
                            if (pass == 1) {
                                recoverProjectSyncJournal(activeHandle, activeUri, output)
                            }
                            checkFolderMirrorCancellation()
                            val progress = MirrorProgress()
                            val remote =
                                scanProjectSyncAndroidTree(
                                    activeHandle,
                                    activeUri,
                                    request,
                                    output,
                                    progress,
                                )
                            output.clear()
                            val summaryLength =
                                NativeRuntime.nativeFinishProjectSyncScan(activeHandle, output)
                            requireMirrorSuccess(
                                summaryLength.toLong(),
                                output,
                                "plan project synchronization",
                            )
                            val summary = readCString(output).split('\t')
                            if (summary.size != 7) {
                                throw IllegalStateException(
                                    "Native synchronization summary is invalid",
                                )
                            }
                            val planCount =
                                summary[0].toIntOrNull()
                                    ?.takeIf { it in 0..MAX_MIRROR_ENTRIES }
                                    ?: throw IllegalStateException(
                                        "Native synchronization count is invalid",
                                    )
                            val nativeActionCounts =
                                summary.drop(1).map { value ->
                                    value.toIntOrNull()
                                        ?.takeIf { it in 0..MAX_MIRROR_ENTRIES }
                                        ?: throw IllegalStateException(
                                            "Native synchronization summary count is invalid",
                                        )
                                }
                            val plan = ArrayList<ProjectSyncPlanEntry>(planCount)
                            repeat(planCount) { index ->
                                checkFolderMirrorCancellation()
                                output.clear()
                                val length =
                                    NativeRuntime.nativeProjectSyncPlanEntry(
                                        activeHandle,
                                        index,
                                        output,
                                    )
                                requireMirrorSuccess(
                                    length.toLong(),
                                    output,
                                    "read project synchronization plan",
                                )
                                plan.add(decodeProjectSyncPlanEntry(output, length))
                            }
                            val observedActionCounts =
                                (SYNC_ACTION_CONVERGED..SYNC_ACTION_CONFLICT).map { action ->
                                    plan.count { it.action == action }
                                }
                            check(observedActionCounts == nativeActionCounts) {
                                "Native synchronization summary differs from its plan"
                            }
                            if (
                                !folderWritable &&
                                plan.any {
                                    it.action == SYNC_ACTION_PUSH_ANDROID ||
                                        it.action == SYNC_ACTION_DELETE_ANDROID
                                }
                            ) {
                                throw SecurityException(
                                    "Android folder is read-only; Linux changes were not applied",
                                )
                            }
                            folderStatus =
                                "Applying ${plan.count { it.action != SYNC_ACTION_CONVERGED }} " +
                                    "project change(s)…"
                            val result =
                                executeProjectSyncPlan(
                                    activeHandle,
                                    activeUri,
                                    mappingId,
                                    remote,
                                    plan,
                                    request,
                                    output,
                                )
                            checkFolderMirrorCancellation()
                            output.clear()
                            requireMirrorSuccess(
                                NativeRuntime.nativeBeginProjectSyncCommitScan(
                                    activeHandle,
                                    output,
                                ).toLong(),
                                output,
                                "rescan Linux project",
                            )
                            scanProjectSyncAndroidTree(
                                activeHandle,
                                activeUri,
                                request,
                                output,
                                MirrorProgress(),
                                result.ignoredDocumentIds,
                            )
                            result.deletedDocuments.forEach { document ->
                                verifyProjectSyncAndroidFingerprint(
                                    activeHandle,
                                    document.uri,
                                    document.expected,
                                    output,
                                )
                            }
                            output.clear()
                            requireMirrorSuccess(
                                NativeRuntime.nativeCommitProjectSync(activeHandle, output).toLong(),
                                output,
                                "commit project synchronization",
                            )
                            nativeStarted = false
                            if (result.deletedDocuments.isNotEmpty()) {
                                updateProjectSyncJournalPhase(SYNC_JOURNAL_COMMITTED)
                                holdProjectSyncTestPhase(SYNC_TEST_PHASE_COMMITTED)
                            }
                            result.deletedDocuments.forEach { document ->
                                if (
                                    !projectSyncProvider.delete(
                                        document.uri,
                                        "finalize a committed Android project deletion",
                                    )
                                ) {
                                    throw IllegalStateException(
                                        "Android provider retained a committed deletion backup",
                                    )
                                }
                            }
                            if (result.deletedDocuments.isNotEmpty()) {
                                clearProjectSyncJournal()
                            }
                            aggregate.pulled += result.pulled
                            aggregate.pushed += result.pushed
                            aggregate.conflictPaths.addAll(result.conflictPaths)
                            aggregate.deferredDeletes = result.deferredDeletes
                            repeatForDeferredDeletes =
                                result.deferredDeletes > 0 &&
                                    result.androidDeletesApplied > 0 &&
                                    pass < MAX_MIRROR_ENTRIES
                        } while (repeatForDeferredDeletes)
                        folderStatus =
                            "Synced ${aggregate.pulled + aggregate.pushed} change(s): " +
                                "${aggregate.pulled} pulled, ${aggregate.pushed} pushed, " +
                                "${aggregate.conflictPaths.size} conflict(s), " +
                                "${aggregate.deferredDeletes} deletion(s) deferred"
                        Log.i(TAG, "Android folder synchronization complete: $folderStatus")
                    } catch (error: Exception) {
                        if (nativeStarted) {
                            NativeRuntime.nativeAbortProjectSync(activeHandle)
                        }
                        if (folderMirrorCancellationRequested || error is InterruptedException) {
                            folderStatus = "Project synchronization cancelled"
                            Log.i(TAG, "Android folder synchronization cancelled")
                        } else {
                            folderStatus =
                                "Sync failed: ${error.message ?: error.javaClass.simpleName}"
                            Log.e(TAG, "Android folder synchronization failed", error)
                        }
                    } finally {
                        finishFolderOperation()
                    }
                },
                "ArchpheneFolderSync",
            )
        storageThread = worker
        return try {
            worker.start()
            promoteWorkToForeground()
            true
        } catch (error: Exception) {
            storageThread = null
            folderOperationActive = false
            folderMirrorRunning = false
            folderSyncRunning = false
            folderMirrorCancellationRequested = false
            PROCESS_STORAGE_ACTIVE.set(false)
            folderStatus = "Sync failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "Could not start Android folder synchronization", error)
            false
        }
    }

    private fun scanProjectSyncAndroidTree(
        activeHandle: Long,
        treeUri: Uri,
        request: ByteBuffer,
        output: ByteBuffer,
        progress: MirrorProgress,
        ignoredDocumentIds: Set<String> = emptySet(),
    ): LinkedHashMap<String, ProjectSyncRemoteEntry> {
        folderStatus = "Scanning Android folder…"
        val result = LinkedHashMap<String, ProjectSyncRemoteEntry>()
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        scanProjectSyncAndroidChildren(
            activeHandle,
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
            "",
            0,
            projection,
            request,
            output,
            progress,
            ignoredDocumentIds,
            result,
        )
        return result
    }

    private fun scanProjectSyncAndroidChildren(
        activeHandle: Long,
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String,
        depth: Int,
        projection: Array<String>,
        request: ByteBuffer,
        output: ByteBuffer,
        progress: MirrorProgress,
        ignoredDocumentIds: Set<String>,
        result: LinkedHashMap<String, ProjectSyncRemoteEntry>,
    ) {
        checkFolderMirrorCancellation()
        if (depth > MAX_MIRROR_DEPTH) {
            throw SecurityException("Android project exceeds $MAX_MIRROR_DEPTH levels")
        }
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val directories = ArrayList<MirrorDirectory>()
        projectSyncProvider.query(
            childrenUri,
            projection,
            "list the Android project directory",
        ) {
            while (it.moveToNext()) {
                checkFolderMirrorCancellation()
                val documentId =
                    it.getString(0)
                        ?.takeIf(String::isNotEmpty)
                        ?: throw SecurityException("Android provider returned no document ID")
                if (documentId in ignoredDocumentIds) {
                    continue
                }
                progress.entries++
                if (progress.entries > MAX_MIRROR_ENTRIES) {
                    throw SecurityException(
                        "Android project exceeds $MAX_MIRROR_ENTRIES entries",
                    )
                }
                val name =
                    it.getString(1)
                        ?.takeIf(::safeProjectName)
                        ?: throw SecurityException("Android provider returned an unsafe name")
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (utf8Length(relativePath) > MAX_MIRROR_PATH_BYTES) {
                    throw SecurityException("Android project path is too long")
                }
                val mime = it.getString(2) ?: "application/octet-stream"
                val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                check(
                    result.put(
                        relativePath,
                        ProjectSyncRemoteEntry(
                            documentId,
                            documentUri,
                            mime,
                            directory,
                        ),
                    ) == null,
                ) {
                    "Android provider returned a duplicate project path"
                }
                val length = putProjectSyncRequest(request, relativePath)
                output.clear()
                if (directory) {
                    requireMirrorSuccess(
                        NativeRuntime.nativeAddProjectSyncAndroidDirectory(
                            activeHandle,
                            request,
                            length,
                            output,
                        ).toLong(),
                        output,
                        "record Android project directory",
                    )
                    directories.add(MirrorDirectory(documentId, relativePath))
                } else {
                    val expectedBytes =
                        if (it.isNull(3) || it.getLong(3) < 0) -1L else it.getLong(3)
                    val descriptor =
                        projectSyncProvider.open(
                            documentUri,
                            "r",
                            "open an Android project file",
                        )
                    descriptor.use { source ->
                        requireMirrorSuccess(
                            NativeRuntime.nativeAddProjectSyncAndroidFile(
                                activeHandle,
                                request,
                                length,
                                source.fd,
                                expectedBytes,
                                output,
                            ).toLong(),
                            output,
                            "fingerprint Android project file",
                        )
                    }
                    if (expectedBytes > 0) {
                        progress.bytes = Math.addExact(progress.bytes, expectedBytes)
                    }
                }
                if (progress.entries % 25 == 0) {
                    folderStatus =
                        "Scanning Android folder: ${progress.entries} entries · " +
                            formatStorageBytes(progress.bytes)
                }
            }
        }
        directories.forEach { directory ->
            scanProjectSyncAndroidChildren(
                activeHandle,
                treeUri,
                directory.documentId,
                directory.relativePath,
                depth + 1,
                projection,
                request,
                output,
                progress,
                ignoredDocumentIds,
                result,
            )
        }
    }

    private fun executeProjectSyncPlan(
        activeHandle: Long,
        treeUri: Uri,
        mappingId: String,
        remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
        plan: List<ProjectSyncPlanEntry>,
        request: ByteBuffer,
        output: ByteBuffer,
    ): ProjectSyncResult {
        val result = ProjectSyncResult()
        plan.forEach { entry ->
            checkFolderMirrorCancellation()
            when {
                entry.action == SYNC_ACTION_PUSH_ANDROID &&
                    entry.linux?.kind == SYNC_KIND_DIRECTORY -> {
                    createProjectSyncAndroidDirectory(treeUri, remote, entry.path)
                    result.pushed++
                }
                entry.action == SYNC_ACTION_PULL_LINUX &&
                    entry.android?.kind == SYNC_KIND_DIRECTORY -> {
                    val length = putProjectSyncRequest(request, entry.path)
                    output.clear()
                    requireMirrorSuccess(
                        NativeRuntime.nativeExecuteProjectSyncLocal(
                            activeHandle,
                            SYNC_LOCAL_CREATE_DIRECTORY,
                            request,
                            length,
                            -1,
                            output,
                        ).toLong(),
                        output,
                        "create Linux project directory",
                    )
                    result.pulled++
                }
            }
        }

        plan.forEachIndexed { index, entry ->
            checkFolderMirrorCancellation()
            folderStatus = "Synchronizing ${index + 1} of ${plan.size}…"
            when (entry.action) {
                SYNC_ACTION_PUSH_ANDROID -> {
                    if (entry.linux?.kind == SYNC_KIND_FILE) {
                        pushProjectSyncAndroidFile(
                            activeHandle,
                            treeUri,
                            mappingId,
                            remote,
                            entry,
                            request,
                            output,
                        )
                        result.pushed++
                    }
                }
                SYNC_ACTION_PULL_LINUX -> {
                    if (entry.android?.kind == SYNC_KIND_FILE) {
                        pullProjectSyncLinuxFile(
                            activeHandle,
                            remote[entry.path]
                                ?: error("Android synchronization source disappeared"),
                            entry,
                            request,
                            output,
                        )
                        result.pulled++
                    }
                }
                SYNC_ACTION_CONFLICT -> {
                    if (entry.android?.kind == SYNC_KIND_FILE) {
                        val remoteEntry =
                            remote[entry.path]
                                ?: error("Android conflict source disappeared")
                        val descriptor =
                            projectSyncProvider.open(
                                remoteEntry.uri,
                                "r",
                                "open an Android conflict file",
                            )
                        descriptor.use { source ->
                            val length =
                                putProjectSyncRequest(
                                    request,
                                    entry.path,
                                    entry.android.encode(),
                                )
                            output.clear()
                            requireMirrorSuccess(
                                NativeRuntime.nativeExecuteProjectSyncLocal(
                                    activeHandle,
                                    SYNC_LOCAL_PRESERVE_CONFLICT,
                                    request,
                                    length,
                                    source.fd,
                                    output,
                                ).toLong(),
                                output,
                                "preserve Android project conflict",
                            )
                        }
                    }
                    result.conflictPaths.add(entry.path)
                }
            }
        }

        plan.asReversed().forEach { entry ->
            checkFolderMirrorCancellation()
            when (entry.action) {
                SYNC_ACTION_DELETE_LINUX -> {
                    val expected =
                        entry.linux
                            ?: error("Linux deletion has no expected fingerprint")
                    val length =
                        putProjectSyncRequest(request, entry.path, expected.encode())
                    output.clear()
                    requireMirrorSuccess(
                        NativeRuntime.nativeExecuteProjectSyncLocal(
                            activeHandle,
                            SYNC_LOCAL_DELETE,
                            request,
                            length,
                            -1,
                            output,
                        ).toLong(),
                        output,
                        "delete Linux project entry",
                    )
                    result.pulled++
                }
                SYNC_ACTION_DELETE_ANDROID -> {
                    when {
                        result.deletedDocuments.isEmpty() &&
                            entry.android?.kind == SYNC_KIND_FILE -> {
                            stageProjectSyncAndroidDeletion(
                                activeHandle,
                                treeUri,
                                mappingId,
                                remote,
                                entry,
                                output,
                                result,
                            )
                            result.pushed++
                            result.androidDeletesApplied++
                        }
                        entry.android?.kind == SYNC_KIND_DIRECTORY &&
                            deleteProjectSyncAndroidDirectory(remote, entry.path) -> {
                            result.pushed++
                            result.androidDeletesApplied++
                        }
                        else -> {
                            // One remote file deletion is journaled per baseline
                            // checkpoint. Later files and nonempty directories
                            // are retried automatically after that checkpoint.
                            result.deferredDeletes++
                        }
                    }
                }
            }
        }
        return result
    }

    private fun deleteProjectSyncAndroidDirectory(
        remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
        path: String,
    ): Boolean {
        val target =
            remote[path]?.takeIf(ProjectSyncRemoteEntry::directory)
                ?: error("Android directory deletion target disappeared")
        val expectedName = path.substringAfterLast('/')
        check(queryProjectSyncDocumentName(target.uri) == expectedName) {
            "Android project directory changed during synchronization"
        }
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                target.uri,
                target.documentId,
            )
        val empty =
            projectSyncProvider.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                "verify an Android project directory is empty",
            ) {
            if (it.moveToFirst()) {
                    false
                } else {
                    true
                }
            }
        if (!empty) {
            return false
        }
        check(
            projectSyncProvider.delete(
                target.uri,
                "delete an empty Android project directory",
            ),
        ) {
            "Android provider retained an empty project directory"
        }
        remote.remove(path)
        return true
    }

    private fun stageProjectSyncAndroidDeletion(
        activeHandle: Long,
        treeUri: Uri,
        mappingId: String,
        remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
        entry: ProjectSyncPlanEntry,
        output: ByteBuffer,
        result: ProjectSyncResult,
    ) {
        val expected =
            entry.android?.takeIf { it.kind == SYNC_KIND_FILE }
                ?: error("Android deletion fingerprint is invalid")
        val target =
            remote[entry.path]?.takeIf { !it.directory }
                ?: error("Android deletion target disappeared")
        verifyProjectSyncAndroidFingerprint(activeHandle, target.uri, expected, output)
        val slash = entry.path.lastIndexOf('/')
        val parentPath = if (slash < 0) "" else entry.path.substring(0, slash)
        val name = if (slash < 0) entry.path else entry.path.substring(slash + 1)
        val parentUri = projectSyncAndroidParent(treeUri, remote, parentPath)
        val dot = name.lastIndexOf('.')
        val extension =
            if (dot > 0 && dot < name.lastIndex) name.substring(dot).take(33) else ""
        val backupName =
            "Archphene-delete-${mappingId.take(8)}-" +
                "${expected.sha256.copyOfRange(0, 6).toHex()}$extension"
        persistProjectSyncJournal(
            ProjectSyncJournal(
                SYNC_JOURNAL_DELETE,
                SYNC_JOURNAL_PREPARED,
                treeUri.toString(),
                parentUri.toString(),
                entry.path,
                name,
                "",
                backupName,
                expected.encode(),
                true,
            ),
        )
        val backup =
            projectSyncProvider.rename(
                target.uri,
                backupName,
                "stage an Android project deletion",
            )
                ?: error("Android provider could not stage project deletion")
        check(queryProjectSyncDocumentName(backup) == backupName) {
            "Android provider changed the deletion backup name"
        }
        updateProjectSyncJournalPhase(SYNC_JOURNAL_BACKED_UP)
        holdProjectSyncTestPhase(SYNC_TEST_PHASE_BACKED_UP)
        val backupId = DocumentsContract.getDocumentId(backup)
        result.ignoredDocumentIds.add(backupId)
        result.deletedDocuments.add(ProjectSyncDeletedDocument(backup, expected))
        remote.remove(entry.path)
    }

    private fun pullProjectSyncLinuxFile(
        activeHandle: Long,
        remote: ProjectSyncRemoteEntry,
        entry: ProjectSyncPlanEntry,
        request: ByteBuffer,
        output: ByteBuffer,
    ) {
        val android =
            entry.android?.takeIf { it.kind == SYNC_KIND_FILE }
                ?: error("Android pull fingerprint is invalid")
        val descriptor =
            projectSyncProvider.open(
                remote.uri,
                "r",
                "open an Android project file for Linux",
            )
        descriptor.use { source ->
            val length =
                putProjectSyncRequest(
                    request,
                    entry.path,
                    android.encode(),
                    entry.linux?.encode() ?: "n",
                )
            output.clear()
            requireMirrorSuccess(
                NativeRuntime.nativeExecuteProjectSyncLocal(
                    activeHandle,
                    SYNC_LOCAL_PULL_FILE,
                    request,
                    length,
                    source.fd,
                    output,
                ).toLong(),
                output,
                "pull Android project file",
            )
        }
    }

    private fun pushProjectSyncAndroidFile(
        activeHandle: Long,
        treeUri: Uri,
        mappingId: String,
        remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
        entry: ProjectSyncPlanEntry,
        request: ByteBuffer,
        output: ByteBuffer,
    ) {
        val linux =
            entry.linux?.takeIf { it.kind == SYNC_KIND_FILE }
                ?: error("Linux push fingerprint is invalid")
        val slash = entry.path.lastIndexOf('/')
        val parentPath = if (slash < 0) "" else entry.path.substring(0, slash)
        val name = if (slash < 0) entry.path else entry.path.substring(slash + 1)
        val parentUri = projectSyncAndroidParent(treeUri, remote, parentPath)
        val token =
            "${mappingId.take(8)}-${linux.sha256.copyOfRange(0, 6).toHex()}"
        val dot = name.lastIndexOf('.')
        val extension =
            if (dot > 0 && dot < name.lastIndex) name.substring(dot).take(33) else ""
        val stagingName = "Archphene-sync-$token$extension"
        val backupName = "Archphene-backup-$token$extension"
        check(
            remote[if (parentPath.isEmpty()) stagingName else "$parentPath/$stagingName"] == null &&
                remote[if (parentPath.isEmpty()) backupName else "$parentPath/$backupName"] == null,
        ) {
            "An interrupted Android synchronization requires recovery"
        }
        persistProjectSyncJournal(
            ProjectSyncJournal(
                SYNC_JOURNAL_PUSH,
                SYNC_JOURNAL_PREPARED,
                treeUri.toString(),
                parentUri.toString(),
                entry.path,
                name,
                stagingName,
                backupName,
                linux.encode(),
                entry.android != null,
            ),
        )
        val stagingUri =
            projectSyncProvider.create(
                parentUri,
                entry.android?.let { remote[entry.path]?.mime } ?: projectSyncMime(name),
                stagingName,
                "create an Android synchronization staging file",
            ) ?: error("Android provider could not create a synchronization staging file")
        check(queryProjectSyncDocumentName(stagingUri) == stagingName) {
            "Android provider changed the synchronization staging name"
        }
        updateProjectSyncJournalPhase(SYNC_JOURNAL_STAGED)
        var stagingPublished = false
        try {
            val requestLength =
                putProjectSyncRequest(request, entry.path, linux.encode())
            output.clear()
            val sourceDescriptor =
                NativeRuntime.nativeExecuteProjectSyncLocal(
                    activeHandle,
                    SYNC_LOCAL_OPEN_FILE,
                    request,
                    requestLength,
                    -1,
                    output,
                )
            requireMirrorSuccess(
                sourceDescriptor.toLong(),
                output,
                "open Linux project file",
            )
            val source = ParcelFileDescriptor.adoptFd(sourceDescriptor)
            val destination =
                projectSyncProvider.open(
                    stagingUri,
                    "rwt",
                    "open an Android synchronization staging file",
                )
            source.use { inputDescriptor ->
                destination.use { outputDescriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor).use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor).use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                checkFolderMirrorCancellation()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied = Math.addExact(copied, count.toLong())
                                check(copied <= linux.bytes) {
                                    "Linux project file changed during upload"
                                }
                                sink.write(buffer, 0, count)
                            }
                            sink.flush()
                            check(copied == linux.bytes) {
                                "Linux project file changed during upload"
                            }
                        }
                    }
                }
            }
            verifyProjectSyncAndroidFingerprint(activeHandle, stagingUri, linux, output)
            val existing = remote[entry.path]
            if (existing != null) {
                val expected =
                    entry.android
                        ?: error("Android replacement has no expected fingerprint")
                verifyProjectSyncAndroidFingerprint(activeHandle, existing.uri, expected, output)
                val backupUri =
                    projectSyncProvider.rename(
                        existing.uri,
                        backupName,
                        "stage the previous Android project file",
                    ) ?: error("Android provider could not stage the previous project file")
                updateProjectSyncJournalPhase(SYNC_JOURNAL_BACKED_UP)
                holdProjectSyncTestPhase(SYNC_TEST_PHASE_BACKED_UP)
                try {
                    val published =
                        projectSyncProvider.rename(
                            stagingUri,
                            name,
                            "publish an Android project file",
                        ) ?: error("Android provider could not publish the project file")
                    stagingPublished = true
                    check(queryProjectSyncDocumentName(published) == name) {
                        "Android provider changed the published project name"
                    }
                    verifyProjectSyncAndroidFingerprint(activeHandle, published, linux, output)
                    updateProjectSyncJournalPhase(SYNC_JOURNAL_PUBLISHED)
                    holdProjectSyncTestPhase(SYNC_TEST_PHASE_PUBLISHED)
                    check(
                        projectSyncProvider.delete(
                            backupUri,
                            "remove the previous Android project file",
                        ),
                    ) {
                        "Android provider retained the previous project file"
                    }
                    clearProjectSyncJournal()
                    remote[entry.path] =
                        ProjectSyncRemoteEntry(
                            DocumentsContract.getDocumentId(published),
                            published,
                            existing.mime,
                            false,
                        )
                } catch (error: Exception) {
                    runCatching {
                        projectSyncProvider.rename(
                            backupUri,
                            name,
                            "restore the previous Android project file",
                        )
                    }
                    throw error
                }
            } else {
                val published =
                    projectSyncProvider.rename(
                        stagingUri,
                        name,
                        "publish a new Android project file",
                    )
                        ?: error("Android provider could not publish the project file")
                stagingPublished = true
                check(queryProjectSyncDocumentName(published) == name) {
                    "Android provider changed the published project name"
                }
                verifyProjectSyncAndroidFingerprint(activeHandle, published, linux, output)
                updateProjectSyncJournalPhase(SYNC_JOURNAL_PUBLISHED)
                holdProjectSyncTestPhase(SYNC_TEST_PHASE_PUBLISHED)
                clearProjectSyncJournal()
                remote[entry.path] =
                    ProjectSyncRemoteEntry(
                        DocumentsContract.getDocumentId(published),
                        published,
                        projectSyncMime(name),
                        false,
                    )
            }
        } finally {
            if (!stagingPublished) {
                runCatching {
                    projectSyncProvider.delete(
                        stagingUri,
                        "discard an Android synchronization staging file",
                    )
                }
            }
        }
    }

    private fun createProjectSyncAndroidDirectory(
        treeUri: Uri,
        remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
        path: String,
    ): ProjectSyncRemoteEntry {
        remote[path]?.let { existing ->
            check(existing.directory) { "Android project parent changed type" }
            return existing
        }
        val slash = path.lastIndexOf('/')
        val parentPath = if (slash < 0) "" else path.substring(0, slash)
        val name = if (slash < 0) path else path.substring(slash + 1)
        val parentUri = projectSyncAndroidParent(treeUri, remote, parentPath)
        val created =
            projectSyncProvider.create(
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
                "create an Android project directory",
            ) ?: error("Android provider could not create project directory")
        check(queryProjectSyncDocumentName(created) == name) {
            "Android provider changed the project directory name"
        }
        return ProjectSyncRemoteEntry(
            DocumentsContract.getDocumentId(created),
            created,
            DocumentsContract.Document.MIME_TYPE_DIR,
            true,
        ).also { remote[path] = it }
    }

    private fun projectSyncAndroidParent(
        treeUri: Uri,
        remote: Map<String, ProjectSyncRemoteEntry>,
        path: String,
    ): Uri =
        if (path.isEmpty()) {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } else {
            remote[path]?.takeIf { it.directory }?.uri
                ?: error("Android project parent is unavailable")
        }

    private fun queryProjectSyncDocumentName(uri: Uri): String {
        return projectSyncProvider.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                "read Android project document metadata",
            ) {
            check(it.moveToFirst() && !it.isNull(0)) {
                "Android provider returned no document name"
            }
                it.getString(0)
        }
    }

    private fun projectSyncMime(name: String): String {
        val extension =
            name.substringAfterLast('.', "").lowercase().takeIf(String::isNotEmpty)
        return extension
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun verifyProjectSyncAndroidFingerprint(
        activeHandle: Long,
        uri: Uri,
        expected: ProjectSyncFingerprint,
        output: ByteBuffer,
    ) {
        val descriptor =
            projectSyncProvider.open(
                uri,
                "r",
                "open an Android project file for verification",
            )
        descriptor.use { source ->
            output.clear()
            val length =
                NativeRuntime.nativeFingerprintProjectSyncFile(
                    activeHandle,
                    source.fd,
                    expected.bytes,
                    output,
                )
            requireMirrorSuccess(
                length.toLong(),
                output,
                "verify Android project file",
            )
            check(readCString(output) == expected.encode()) {
                "Android project file changed during synchronization"
            }
        }
    }

    private fun persistProjectSyncJournal(journal: ProjectSyncJournal) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            output.write(SYNC_JOURNAL_MAGIC)
            output.writeInt(journal.operation)
            output.writeInt(journal.phase)
            output.writeBoolean(journal.hadOriginal)
            output.writeProjectSyncJournalString(journal.treeUri)
            output.writeProjectSyncJournalString(journal.parentUri)
            output.writeProjectSyncJournalString(journal.path)
            output.writeProjectSyncJournalString(journal.targetName)
            output.writeProjectSyncJournalString(journal.stagingName)
            output.writeProjectSyncJournalString(journal.backupName)
            output.writeProjectSyncJournalString(journal.expected)
        }
        val bodyBytes = body.toByteArray()
        val checksum = CRC32().apply { update(bodyBytes) }.value
        val encoded = ByteArrayOutputStream(bodyBytes.size + 8)
        DataOutputStream(encoded).use { output ->
            output.write(bodyBytes)
            output.writeLong(checksum)
        }
        val bytes = encoded.toByteArray()
        check(bytes.size <= MAX_SYNC_JOURNAL_BYTES) {
            "Project synchronization journal is oversized"
        }
        val atomic = AtomicFile(File(filesDir, SYNC_JOURNAL_FILE))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun loadProjectSyncJournal(): ProjectSyncJournal? {
        val file = File(filesDir, SYNC_JOURNAL_FILE)
        if (!file.exists()) {
            return null
        }
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Project synchronization journal is not a regular file"
        }
        check(file.length() in 1..MAX_SYNC_JOURNAL_BYTES.toLong()) {
            "Project synchronization journal is oversized"
        }
        val encoded = AtomicFile(file).openRead().use { it.readBytes() }
        check(encoded.size >= SYNC_JOURNAL_MAGIC.size + 17) {
            "Project synchronization journal is truncated"
        }
        val bodyLength = encoded.size - 8
        val expectedChecksum =
            ByteBuffer
                .wrap(encoded, bodyLength, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .long
        val checksum = CRC32().apply { update(encoded, 0, bodyLength) }.value
        check(expectedChecksum == checksum) {
            "Project synchronization journal checksum differs"
        }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, bodyLength))
        val magic = ByteArray(SYNC_JOURNAL_MAGIC.size)
        input.readFully(magic)
        check(magic.contentEquals(SYNC_JOURNAL_MAGIC)) {
            "Project synchronization journal version differs"
        }
        val operation = input.readInt()
        val phase = input.readInt()
        val hadOriginal = input.readBoolean()
        check(operation in SYNC_JOURNAL_PUSH..SYNC_JOURNAL_DELETE) {
            "Project synchronization journal operation is invalid"
        }
        check(phase in SYNC_JOURNAL_PREPARED..SYNC_JOURNAL_COMMITTED) {
            "Project synchronization journal phase is invalid"
        }
        val result =
            ProjectSyncJournal(
                operation,
                phase,
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                input.readProjectSyncJournalString(),
                hadOriginal,
            )
        check(input.available() == 0) {
            "Project synchronization journal has trailing data"
        }
        return result
    }

    private fun DataOutputStream.writeProjectSyncJournalString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
            "Project synchronization journal field is oversized"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readProjectSyncJournalString(): String {
        val length = readInt()
        check(length in 0..NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
            "Project synchronization journal field is invalid"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun updateProjectSyncJournalPhase(phase: Int) {
        val journal =
            loadProjectSyncJournal()
                ?: error("Project synchronization journal disappeared")
        persistProjectSyncJournal(journal.copy(phase = phase))
    }

    private fun clearProjectSyncJournal() {
        AtomicFile(File(filesDir, SYNC_JOURNAL_FILE)).delete()
    }

    private fun holdProjectSyncTestPhase(phase: String) {
        val preferences = getSharedPreferences(SYNC_TEST_PREFERENCES, MODE_PRIVATE)
        if (preferences.getString(SYNC_TEST_PHASE, null) != phase) {
            return
        }
        val holdMillis =
            preferences
                .getLong(SYNC_TEST_HOLD_MILLIS, 0)
                .coerceIn(0, MAX_SYNC_TEST_HOLD_MILLIS)
        preferences.edit().remove(SYNC_TEST_PHASE).remove(SYNC_TEST_HOLD_MILLIS).commit()
        if (holdMillis < 5_000L) {
            return
        }
        Log.i(TAG, "Project sync test holding phase=$phase for ${holdMillis}ms")
        Thread.sleep(holdMillis)
    }

    private fun recoverProjectSyncJournal(
        activeHandle: Long,
        activeTreeUri: Uri,
        output: ByteBuffer,
    ) {
        val journal = loadProjectSyncJournal() ?: return
        check(journal.treeUri == activeTreeUri.toString()) {
            "An interrupted synchronization belongs to another Android folder"
        }
        val parentUri =
            Uri.parse(journal.parentUri)
                .takeIf { it.scheme == "content" }
                ?: error("Project synchronization journal parent is invalid")
        val target = findProjectSyncAndroidChild(parentUri, journal.targetName)
        val staging =
            journal.stagingName
                .takeIf(String::isNotEmpty)
                ?.let { findProjectSyncAndroidChild(parentUri, it) }
        val backup =
            journal.backupName
                .takeIf(String::isNotEmpty)
                ?.let { findProjectSyncAndroidChild(parentUri, it) }
        if (journal.operation == SYNC_JOURNAL_DELETE) {
            if (journal.phase == SYNC_JOURNAL_COMMITTED) {
                check(target == null) {
                    "Committed Android deletion target reappeared"
                }
                backup?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "finalize a recovered Android project deletion",
                        ),
                    ) {
                        "Android provider retained a committed deletion backup"
                    }
                }
                clearProjectSyncJournal()
                return
            }
            when {
                backup != null && target == null -> {
                    val restored =
                        projectSyncProvider.rename(
                            backup.uri,
                            journal.targetName,
                            "restore an interrupted Android project deletion",
                        ) ?: error("Android provider could not restore interrupted deletion")
                    verifyProjectSyncAndroidFingerprint(
                        activeHandle,
                        restored,
                        decodeProjectSyncFingerprintText(journal.expected),
                        output,
                    )
                }
                backup != null && target != null -> {
                    verifyProjectSyncAndroidFingerprint(
                        activeHandle,
                        target.uri,
                        decodeProjectSyncFingerprintText(journal.expected),
                        output,
                    )
                    check(
                        projectSyncProvider.delete(
                            backup.uri,
                            "remove a duplicate Android deletion backup",
                        ),
                    ) {
                        "Android provider retained duplicate deletion backup"
                    }
                }
                backup == null && target != null -> {
                    verifyProjectSyncAndroidFingerprint(
                        activeHandle,
                        target.uri,
                        decodeProjectSyncFingerprintText(journal.expected),
                        output,
                    )
                }
                else -> error("Interrupted Android deletion lost both versions")
            }
            clearProjectSyncJournal()
            return
        }

        val expected = decodeProjectSyncFingerprintText(journal.expected)
        val targetIsPublished =
            target?.let {
                runCatching {
                    verifyProjectSyncAndroidFingerprint(activeHandle, it.uri, expected, output)
                    true
                }.getOrDefault(false)
            } == true
        when {
            targetIsPublished -> {
                staging?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "discard recovered Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
                backup?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "finalize a recovered Android synchronization backup",
                        ),
                    ) {
                        "Android provider retained synchronization backup"
                    }
                }
            }
            backup != null && target == null -> {
                val restored =
                    projectSyncProvider.rename(
                        backup.uri,
                        journal.targetName,
                        "restore an interrupted Android project update",
                    ) ?: error("Android provider could not restore interrupted update")
                if (journal.hadOriginal) {
                    check(queryProjectSyncDocumentName(restored) == journal.targetName) {
                        "Android provider changed restored project name"
                    }
                }
                staging?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "discard interrupted Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
            backup != null && target != null -> {
                error("Interrupted Android update retained two unequal versions")
            }
            target != null -> {
                check(journal.hadOriginal) {
                    "Interrupted Android update collided with a new target"
                }
                staging?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "discard collided Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
            !journal.hadOriginal -> {
                staging?.let {
                    check(
                        projectSyncProvider.delete(
                            it.uri,
                            "discard new-file Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
            else -> error("Interrupted Android update lost the previous project file")
        }
        clearProjectSyncJournal()
        Log.i(TAG, "Recovered interrupted Android project synchronization")
    }

    private fun decodeProjectSyncFingerprintText(value: String): ProjectSyncFingerprint {
        val fields = value.split(':')
        check(fields.size == 3 && fields[0] == "f") {
            "Project synchronization journal fingerprint is invalid"
        }
        val bytes =
            fields[1].toLongOrNull()?.takeIf { it in 0..MAX_MIRROR_FILE_BYTES }
                ?: error("Project synchronization journal size is invalid")
        val digest = fields[2]
        check(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Project synchronization journal digest is invalid"
        }
        val sha256 = ByteArray(32)
        repeat(32) { index ->
            sha256[index] = digest.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return ProjectSyncFingerprint(SYNC_KIND_FILE, bytes, sha256)
    }

    private fun findProjectSyncAndroidChild(
        parentUri: Uri,
        name: String,
    ): ProjectSyncRemoteEntry? {
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri),
            )
        return projectSyncProvider.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                "list Android project recovery documents",
            ) {
            var match: ProjectSyncRemoteEntry? = null
            while (it.moveToNext()) {
                if (it.getString(1) != name) continue
                check(match == null) { "Android provider returned duplicate recovery names" }
                val id = it.getString(0) ?: error("Android recovery document has no ID")
                val mime = it.getString(2) ?: "application/octet-stream"
                match =
                    ProjectSyncRemoteEntry(
                        id,
                        DocumentsContract.buildDocumentUriUsingTree(parentUri, id),
                        mime,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
            }
                match
        }
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        val encoded = CharArray(size * 2)
        forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            encoded[index * 2] = alphabet[unsigned ushr 4]
            encoded[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return encoded.concatToString()
    }

    @Synchronized
    private fun requestFolderMirrorCancellation(): Boolean {
        if (!folderMirrorRunning) {
            return false
        }
        folderMirrorCancellationRequested = true
        folderStatus = "Cancelling the project operation…"
        projectSyncProvider.cancel()
        val activeHandle = readyHandle
        if (activeHandle != 0L) {
            if (folderSyncRunning) {
                NativeRuntime.nativeCancelProjectSync(activeHandle)
            } else {
                NativeRuntime.nativeCancelProjectMirror(activeHandle)
            }
        }
        storageThread?.interrupt()
        return true
    }

    private fun mirrorDocumentChildren(
        activeHandle: Long,
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String,
        depth: Int,
        projection: Array<String>,
        request: ByteBuffer,
        output: ByteBuffer,
        progress: MirrorProgress,
    ) {
        checkFolderMirrorCancellation()
        if (depth > MAX_MIRROR_DEPTH) {
            throw SecurityException("Android project exceeds $MAX_MIRROR_DEPTH levels")
        }
        val childUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val directories = ArrayList<MirrorDirectory>()
        projectSyncProvider.query(
            childUri,
            projection,
            "list the Android project directory for its initial mirror",
        ) {
            while (it.moveToNext()) {
                checkFolderMirrorCancellation()
                progress.entries++
                if (progress.entries > MAX_MIRROR_ENTRIES) {
                    throw SecurityException(
                        "Android project exceeds $MAX_MIRROR_ENTRIES entries",
                    )
                }
                val documentId =
                    it.getString(0)
                        ?.takeIf(String::isNotEmpty)
                        ?: throw SecurityException("Android provider returned no document ID")
                val name =
                    it.getString(1)
                        ?.takeIf(::safeProjectName)
                        ?: throw SecurityException("Android provider returned an unsafe name")
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (utf8Length(relativePath) > MAX_MIRROR_PATH_BYTES) {
                    throw SecurityException("Android project path is too long")
                }
                val mime = it.getString(2) ?: "application/octet-stream"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val length = putUtf8Request(request, relativePath)
                    output.clear()
                    val result =
                        NativeRuntime.nativeAddProjectMirrorDirectory(
                            activeHandle,
                            request,
                            length,
                            output,
                        )
                    requireMirrorSuccess(result.toLong(), output, "create mirror directory")
                    directories.add(MirrorDirectory(documentId, relativePath))
                } else {
                    val expectedBytes =
                        if (it.isNull(3) || it.getLong(3) < 0) {
                            -1L
                        } else {
                            it.getLong(3)
                        }
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val descriptor =
                        projectSyncProvider.open(
                            documentUri,
                            "r",
                            "open an Android project file for its initial mirror",
                        )
                    val copied =
                        descriptor.use {
                            val length = putUtf8Request(request, relativePath)
                            output.clear()
                            NativeRuntime.nativeAddProjectMirrorFile(
                                activeHandle,
                                request,
                                length,
                                it.fd,
                                expectedBytes,
                                output,
                            )
                        }
                    requireMirrorSuccess(copied, output, "copy mirror file")
                    progress.bytes =
                        Math.addExact(
                            progress.bytes,
                            copied,
                        )
                }
                if (progress.entries % 25 == 0) {
                    folderStatus =
                        "Mirroring $projectNameForStatus: " +
                            "${progress.entries} entries · ${formatStorageBytes(progress.bytes)}"
                }
            }
        }
        for (directory in directories) {
            mirrorDocumentChildren(
                activeHandle,
                treeUri,
                directory.documentId,
                directory.relativePath,
                depth + 1,
                projection,
                request,
                output,
                progress,
            )
        }
    }

    private fun checkFolderMirrorCancellation() {
        if (folderMirrorCancellationRequested || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Project mirror cancelled")
        }
    }

    private val projectNameForStatus: String
        get() = folderLabel.ifEmpty { "Android folder" }

    private fun requireMirrorSuccess(
        result: Long,
        output: ByteBuffer,
        operation: String,
    ) {
        if (result < 0) {
            throw IllegalStateException(readCString(output).ifEmpty { "$operation failed ($result)" })
        }
    }

    private fun decodeProjectSyncPlanEntry(
        source: ByteBuffer,
        length: Int,
    ): ProjectSyncPlanEntry {
        check(length in SYNC_PLAN_HEADER_BYTES..source.capacity()) {
            "Native synchronization plan entry is not bounded"
        }
        val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        input.clear()
        input.limit(length)
        val magic = ByteArray(8)
        input.get(magic)
        check(magic.contentEquals("ASPE0001".toByteArray(StandardCharsets.US_ASCII))) {
            "Native synchronization plan entry has invalid magic"
        }
        val action = input.get(8).toInt() and 0xff
        check(action in SYNC_ACTION_CONVERGED..SYNC_ACTION_CONFLICT) {
            "Native synchronization action is invalid"
        }
        val pathLength = input.getInt(12)
        check(pathLength in 1..MAX_MIRROR_PATH_BYTES) {
            "Native synchronization path length is invalid"
        }
        check(length == SYNC_PLAN_HEADER_BYTES + pathLength) {
            "Native synchronization plan entry length differs"
        }
        val baseline = decodeProjectSyncFingerprint(input, input.get(9).toInt() and 0xff, 16)
        val linux = decodeProjectSyncFingerprint(input, input.get(10).toInt() and 0xff, 56)
        val android = decodeProjectSyncFingerprint(input, input.get(11).toInt() and 0xff, 96)
        val pathBytes = ByteArray(pathLength)
        input.position(SYNC_PLAN_HEADER_BYTES)
        input.get(pathBytes)
        val path =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(pathBytes))
                .toString()
        val segments = path.split('/')
        check(
            segments.isNotEmpty() &&
                segments.size <= MAX_MIRROR_DEPTH &&
                segments.all(::safeProjectName),
        ) {
            "Native synchronization path is unsafe"
        }
        return ProjectSyncPlanEntry(path, action, baseline, linux, android)
    }

    private fun decodeProjectSyncFingerprint(
        source: ByteBuffer,
        kind: Int,
        offset: Int,
    ): ProjectSyncFingerprint? {
        val bytes = source.getLong(offset)
        val sha256 = ByteArray(32)
        val digest = source.duplicate()
        digest.position(offset + 8)
        digest.get(sha256)
        return when (kind) {
            SYNC_KIND_ABSENT -> {
                check(bytes == 0L && sha256.all { it == 0.toByte() }) {
                    "Absent synchronization fingerprint has data"
                }
                null
            }
            SYNC_KIND_DIRECTORY -> {
                check(bytes == 0L && sha256.all { it == 0.toByte() }) {
                    "Directory synchronization fingerprint has data"
                }
                ProjectSyncFingerprint(kind, 0, sha256)
            }
            SYNC_KIND_FILE -> {
                check(bytes in 0..MAX_MIRROR_FILE_BYTES) {
                    "File synchronization fingerprint is oversized"
                }
                ProjectSyncFingerprint(kind, bytes, sha256)
            }
            else -> error("Native synchronization fingerprint kind is invalid")
        }
    }

    private fun putProjectSyncRequest(
        destination: ByteBuffer,
        vararg fields: String,
    ): Int {
        check(fields.isNotEmpty() && fields.none { it.isEmpty() || '\t' in it }) {
            "Project synchronization request is invalid"
        }
        val encoded = fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
        check(encoded.size <= NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
            "Project synchronization request is too long"
        }
        destination.clear()
        destination.put(encoded)
        return encoded.size
    }

    private fun newFolderMappingId(): String {
        val bytes = ByteArray(16)
        do {
            FOLDER_MAPPING_RANDOM.nextBytes(bytes)
        } while (bytes.all { it == 0.toByte() })
        val alphabet = "0123456789abcdef"
        val encoded = CharArray(32)
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            encoded[index * 2] = alphabet[unsigned ushr 4]
            encoded[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return encoded.concatToString()
    }

    private fun putProjectMirrorBeginRequest(
        destination: ByteBuffer,
        projectName: String,
        mappingId: String,
    ): Int {
        check(FOLDER_MAPPING_ID_PATTERN.matches(mappingId)) {
            "Project mapping identity is invalid"
        }
        putUtf8Request(destination, projectName)
        destination.put('\t'.code.toByte())
        mappingId.forEach { destination.put(it.code.toByte()) }
        return destination.position()
    }

    private fun putUtf8Request(
        destination: ByteBuffer,
        value: String,
    ): Int {
        destination.clear()
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (
                codePoint in 0xD800..0xDFFF ||
                !Character.isValidCodePoint(codePoint)
            ) {
                throw SecurityException("Project path is not valid Unicode")
            }
            when {
                codePoint <= 0x7f -> destination.put(codePoint.toByte())
                codePoint <= 0x7ff -> {
                    destination.put((0xc0 or (codePoint shr 6)).toByte())
                    destination.put((0x80 or (codePoint and 0x3f)).toByte())
                }
                codePoint <= 0xffff -> {
                    destination.put((0xe0 or (codePoint shr 12)).toByte())
                    destination.put((0x80 or ((codePoint shr 6) and 0x3f)).toByte())
                    destination.put((0x80 or (codePoint and 0x3f)).toByte())
                }
                else -> {
                    destination.put((0xf0 or (codePoint shr 18)).toByte())
                    destination.put((0x80 or ((codePoint shr 12) and 0x3f)).toByte())
                    destination.put((0x80 or ((codePoint shr 6) and 0x3f)).toByte())
                    destination.put((0x80 or (codePoint and 0x3f)).toByte())
                }
            }
            if (destination.position() > MAX_MIRROR_PATH_BYTES) {
                throw SecurityException("Project path is too long")
            }
            index += Character.charCount(codePoint)
        }
        if (destination.position() == 0) {
            throw SecurityException("Project path is empty")
        }
        return destination.position()
    }

    private fun utf8Length(value: String): Int {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            bytes +=
                when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
            if (bytes > MAX_MIRROR_PATH_BYTES) {
                return bytes
            }
            index += Character.charCount(codePoint)
        }
        return bytes
    }

    private fun safeProjectName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            utf8Length(name) <= MAX_STORAGE_NAME_BYTES &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name &&
            '\t' !in name &&
            name.none { character ->
                character.isISOControl() ||
                    character == '\u061c' ||
                    character == '\u200e' ||
                    character == '\u200f' ||
                    character in '\u202a'..'\u202e' ||
                    character in '\u2066'..'\u2069'
            }

    @Synchronized
    private fun requestFolderDisconnect(): Boolean {
        if (!folderConnected || !PROCESS_STORAGE_ACTIVE.compareAndSet(false, true)) {
            return false
        }
        folderOperationActive = true
        folderStatus = "Disconnecting the Android folder…"
        val worker =
            Thread(
                {
                    try {
                        val preferences = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
                        val uri =
                            preferences
                                .getString(FOLDER_URI, null)
                                ?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull() }
                                ?.takeIf(::safeTreeUri)
                                ?: throw IllegalStateException("Saved folder grant is invalid")
                        releaseFolderPermission(uri)
                        if (
                            !preferences
                                .edit()
                                .remove(FOLDER_URI)
                                .remove(FOLDER_LABEL)
                                .putString(FOLDER_STATE, FOLDER_DISCONNECTED)
                                .commit()
                        ) {
                            throw IllegalStateException("Could not save the disconnected state")
                        }
                        folderConnected = false
                        folderWritable = false
                        folderUri = ""
                        folderLabel = ""
                        folderMirrorPath = ""
                        folderStatus = "No Android folder connected"
                        Log.i(TAG, "Android folder disconnected")
                    } catch (error: Exception) {
                        val permission =
                            getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)
                                .getString(FOLDER_URI, null)
                                ?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull() }
                                ?.let(::persistedFolderPermission)
                        folderConnected = permission?.first == true
                        folderStatus =
                            "Folder disconnect failed: " +
                                (error.message ?: error.javaClass.simpleName)
                        Log.e(TAG, "Android folder disconnect failed", error)
                    } finally {
                        finishFolderOperation()
                    }
                },
                "ArchpheneFolderDisconnect",
            )
        storageThread = worker
        return try {
            worker.start()
            promoteWorkToForeground()
            true
        } catch (error: Exception) {
            storageThread = null
            folderOperationActive = false
            PROCESS_STORAGE_ACTIVE.set(false)
            folderStatus =
                "Folder disconnect failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "Could not start Android folder disconnect", error)
            false
        }
    }

    private fun finishFolderOperation() {
        folderOperationActive = false
        folderMirrorRunning = false
        folderSyncRunning = false
        folderMirrorCancellationRequested = false
        PROCESS_STORAGE_ACTIVE.set(false)
        storageThread = null
        stopWhenUnobservedAndIdle()
    }

    private fun safeTreeUri(uri: Uri): Boolean {
        val encoded = uri.toString().toByteArray(StandardCharsets.UTF_8)
        return uri.scheme == "content" &&
            encoded.isNotEmpty() &&
            encoded.size <= MAX_STORAGE_URI_BYTES &&
            DocumentsContract.isTreeUri(uri)
    }

    private fun persistedFolderPermission(uri: Uri): Pair<Boolean, Boolean>? =
        contentResolver.persistedUriPermissions
            .firstOrNull { permission -> permission.uri == uri }
            ?.let { permission ->
                Pair(permission.isReadPermission, permission.isWritePermission)
            }

    private fun releaseFolderPermission(uri: Uri) {
        val permission = persistedFolderPermission(uri) ?: return
        var flags = 0
        if (permission.first) {
            flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (permission.second) {
            flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        if (flags != 0) {
            contentResolver.releasePersistableUriPermission(uri, flags)
        }
    }

    private fun queryFolderLabel(uri: Uri): String {
        val queried =
            runCatching {
                contentResolver
                    .query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getString(0)
                        } else {
                            null
                        }
                    }
            }.getOrNull()
        if (queried?.let(::safeFolderLabel) == true) {
            return queried
        }
        val documentLabel =
            runCatching {
                DocumentsContract
                    .getTreeDocumentId(uri)
                    .substringAfterLast('/')
                    .substringAfterLast(':')
            }.getOrNull()
        return documentLabel?.takeIf(::safeFolderLabel) ?: "Selected Android folder"
    }

    private fun safeFolderLabel(label: String): Boolean =
        label.toByteArray(StandardCharsets.UTF_8).size <= MAX_FOLDER_LABEL_BYTES &&
            safeVisibleName(label)

    private fun connectedFolderStatus(
        label: String,
        writable: Boolean,
        mirrorPath: String,
    ): String {
        val access =
            if (writable) {
                "Android folder: $label · read/write"
            } else {
                "Android folder: $label · read-only"
            }
        return if (mirrorPath.isEmpty()) access else "$access\nLinux: $mirrorPath"
    }

    @Synchronized
    private fun requestDocumentImport(uri: Uri): Boolean {
        val encodedUri = uri.toString().toByteArray(StandardCharsets.UTF_8)
        if (
            readyHandle == 0L ||
            storageImportActive ||
            uri.scheme != "content" ||
            encodedUri.isEmpty() ||
            encodedUri.size > MAX_STORAGE_URI_BYTES
        ) {
            if (uri.scheme != "content" || encodedUri.size > MAX_STORAGE_URI_BYTES) {
                storageStatus = "Choose a document supplied by Android Files"
            }
            return false
        }
        if (!PROCESS_STORAGE_ACTIVE.compareAndSet(false, true)) {
            return false
        }
        storageImportActive = true
        storageStatus = "Opening the selected Android document…"
        val worker =
            Thread(
                {
                    try {
                        val displayName = safeImportDisplayName(uri)
                        persistStorageStatus(
                            STORAGE_RUNNING,
                            "Importing $displayName into ~/Downloads…",
                        )
                        val root =
                            File(filesDir, "arch-root/home/archphene").absolutePath
                        val fields = listOf(root, "home/Downloads", displayName)
                        val requestBytes =
                            fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
                        if (
                            requestBytes.isEmpty() ||
                            requestBytes.size > MAX_STORAGE_REQUEST_BYTES
                        ) {
                            throw IllegalStateException("Document import request is too large")
                        }
                        val request = ByteBuffer.allocateDirect(requestBytes.size)
                        request.put(requestBytes)
                        val output = ByteBuffer.allocateDirect(NativeRuntime.STORAGE_OUTPUT_SIZE)
                        val descriptor =
                            contentResolver.openFileDescriptor(uri, "r", null)
                                ?: throw IllegalStateException(
                                    "Android provider returned no file descriptor",
                                )
                        val result =
                            descriptor.use {
                                NativeRuntime.nativeImportHomeDocument(
                                    request,
                                    requestBytes.size,
                                    it.fd,
                                    output,
                                )
                            }
                        val response = readCString(output)
                        if (result <= 0 || result != response.toByteArray(StandardCharsets.UTF_8).size) {
                            throw IllegalStateException(
                                response.ifEmpty { "Native storage error $result" },
                            )
                        }
                        val responseFields = response.split('\t')
                        if (responseFields.size != 2) {
                            throw IllegalStateException("Invalid native import response")
                        }
                        val importedName = responseFields[0]
                        val importedBytes =
                            responseFields[1].toLongOrNull()
                                ?: throw IllegalStateException("Invalid imported byte count")
                        if (
                            !safeVisibleName(importedName) ||
                            importedBytes !in 0..MAX_STORAGE_IMPORT_BYTES
                        ) {
                            throw IllegalStateException("Unsafe native import response")
                        }
                        val status =
                            "Imported $importedName (${formatStorageBytes(importedBytes)}) " +
                                "to ~/Downloads"
                        persistStorageStatus(STORAGE_COMPLETE, status)
                        Log.i(
                            TAG,
                            "Android document imported name=$importedName bytes=$importedBytes",
                        )
                    } catch (error: Exception) {
                        val status =
                            "Import failed: ${error.message ?: error.javaClass.simpleName}"
                        persistStorageStatus(STORAGE_FAILED, status)
                        Log.e(TAG, "Android document import failed", error)
                    } finally {
                        storageImportActive = false
                        PROCESS_STORAGE_ACTIVE.set(false)
                        storageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneImport",
            )
        storageThread = worker
        return try {
            worker.start()
            promoteWorkToForeground()
            true
        } catch (error: Exception) {
            storageThread = null
            storageImportActive = false
            PROCESS_STORAGE_ACTIVE.set(false)
            storageStatus = "Import failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "Could not start Android document import", error)
            false
        }
    }

    private fun safeImportDisplayName(uri: Uri): String {
        val queried =
            runCatching {
                contentResolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getString(0)
                        } else {
                            null
                        }
                    }
            }.getOrNull()
        return queried?.takeIf(::safeVisibleName) ?: "Imported file"
    }

    private fun safeVisibleName(name: String): Boolean =
        name.isNotEmpty() &&
            name.toByteArray(StandardCharsets.UTF_8).size <= MAX_STORAGE_NAME_BYTES &&
            name != "." &&
            name != ".." &&
            !name.startsWith('.') &&
            '/' !in name &&
            '\\' !in name &&
            name.none { character ->
                character.isISOControl() ||
                    character == '\u061c' ||
                    character == '\u200e' ||
                    character == '\u200f' ||
                    character in '\u202a'..'\u202e' ||
                    character in '\u2066'..'\u2069'
            }

    private fun formatStorageBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KiB"
            bytes < 1024L * 1024 * 1024 ->
                "${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MiB"
            else ->
                "${(bytes + 1024L * 1024 * 1024 - 1) / (1024L * 1024 * 1024)} GiB"
        }

    private fun createSessionNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel =
            NotificationChannel(
                SESSION_NOTIFICATION_CHANNEL,
                getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.session_channel_description)
            }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun sessionNotification(): Notification {
        return Notification.Builder(this, SESSION_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(
                getString(
                    if (shellHandle == 0L) {
                        R.string.session_notification_starting
                    } else {
                        R.string.session_notification_running
                    },
                ),
            )
            .setContentIntent(openRuntimeAction())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.session_notification_stop),
                    shellStopAction(),
                ).build(),
            )
            .build()
    }

    private fun workNotification(): Notification {
        val text =
            when {
                packageOperationActive || packageCacheActive || searchActive ->
                    R.string.work_notification_packages
                catalogRefreshActive -> R.string.work_notification_catalogs
                commandActive -> R.string.work_notification_command
                else -> R.string.work_notification_storage
            }
        return Notification.Builder(this, SESSION_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(getString(R.string.work_notification_title))
            .setContentText(getString(text))
            .setContentIntent(openRuntimeAction())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply {
                if (packageOperationActive) {
                    setProgress(100, jobProgress, jobProgress == 0)
                }
                if (shellActive) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.session_notification_stop),
                            shellStopAction(),
                        ).build(),
                    )
                }
            }
            .build()
    }

    private fun shellStopAction(): PendingIntent {
        val stopIntent = Intent(this, ArchpheneRuntimeService::class.java).setAction(ACTION_STOP_SHELL)
        return PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openRuntimeAction(): PendingIntent {
        val openIntent =
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        return PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun promoteSessionToForeground() {
        promoteToForeground(activeForegroundNotification())
    }

    private fun promoteWorkToForeground() {
        promoteToForeground(activeForegroundNotification())
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SESSION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SESSION_NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground runtime notification active")
    }

    private fun updateSessionNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(SESSION_NOTIFICATION_ID, activeForegroundNotification())
    }

    private fun reconcileForegroundNotification() {
        if (shellActive || hasForegroundWork()) {
            updateSessionNotification()
        } else {
            removeSessionNotification()
        }
    }

    private fun activeForegroundNotification(): Notification =
        if (hasForegroundWork()) {
            workNotification()
        } else {
            sessionNotification()
        }

    private fun hasForegroundWork(): Boolean =
        catalogRefreshActive ||
            packageOperationActive ||
            commandActive ||
            storageImportActive ||
            folderOperationActive

    private fun removeSessionNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun scheduleAndroidDnsRefresh() {
        if (!dnsRootReady || handle == 0L) {
            return
        }
        dnsRefreshPending.set(true)
        if (!dnsRefreshActive.compareAndSet(false, true)) {
            return
        }
        dnsThread =
            Thread(
                {
                    try {
                        while (
                            dnsRefreshPending.getAndSet(false) &&
                            !Thread.currentThread().isInterrupted
                        ) {
                            val activeHandle = handle
                            if (!dnsRootReady || activeHandle == 0L) {
                                return@Thread
                            }
                            publishAndroidDns(activeHandle)
                        }
                    } finally {
                        dnsRefreshActive.set(false)
                        dnsThread = null
                        if (dnsRefreshPending.get() && dnsRootReady && handle != 0L) {
                            scheduleAndroidDnsRefresh()
                        }
                    }
                },
                "ArchpheneDns",
            ).also(Thread::start)
    }

    private fun publishAndroidDns(activeHandle: Long): Boolean {
        val manager = connectivityManager ?: return false
        val linkProperties =
            try {
                val activeNetwork = manager.activeNetwork ?: return false
                manager.getLinkProperties(activeNetwork) ?: return false
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not read Android DNS configuration", error)
                return false
            }
        val request = StringBuilder("D1\n")
        val emitted = ArrayList<String>(MAX_ANDROID_DNS_SERVERS)
        for (address in linkProperties.dnsServers) {
            val hostAddress = address.hostAddress ?: continue
            if (hostAddress.isEmpty() || emitted.contains(hostAddress)) {
                continue
            }
            emitted.add(hostAddress)
            request.append(hostAddress).append('\n')
            if (emitted.size == MAX_ANDROID_DNS_SERVERS) {
                break
            }
        }
        if (emitted.isEmpty()) {
            Log.i(TAG, "Android active network has no DNS servers; retaining prior resolver")
            return false
        }
        val bytes = request.toString().toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size > NativeRuntime.DNS_REQUEST_LIMIT) {
            Log.e(TAG, "Android DNS request exceeded native bound")
            return false
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        val result = NativeRuntime.nativeConfigureDns(activeHandle, buffer, bytes.size)
        if (result < 0) {
            Log.e(TAG, "Android DNS publication failed: $result")
            return false
        }
        Log.i(TAG, "Published $result Android DNS server(s) to the Arch root")
        return true
    }

    private fun startBootstrap(activeHandle: Long) {
        bootstrapActive = true
        bootstrapThread =
            Thread(
                {
                    try {
                        restoreStorageStatus()
                        val pathBytes =
                            File(filesDir, "arch-root")
                                .absolutePath
                                .toByteArray(StandardCharsets.UTF_8)
                        val pathBuffer = ByteBuffer.allocateDirect(pathBytes.size)
                        pathBuffer.put(pathBytes)
                        val createdDirectories =
                            NativeRuntime.nativeBootstrapArchRoot(
                                activeHandle,
                                pathBuffer,
                                pathBytes.size,
                                System.currentTimeMillis(),
                            )
                        if (createdDirectories < 0) {
                            throw IllegalStateException(
                                "Shared Arch root bootstrap failed: $createdDirectories",
                            )
                        }
                        dnsRootReady = true
                        publishAndroidDns(activeHandle)
                        val packageVersion = preparePackageRuntime(activeHandle)
                        refreshPackageInventory(activeHandle)
                        reconcileInstalledLaunchers(activeHandle)
                        refreshDesktopEntries(activeHandle)
                        refreshShellChoices(activeHandle)
                        jobStatus = readLatestPackageJob(activeHandle)
                        refreshPendingPackageMutation(activeHandle)
                        restorePackageRecovery()
                        mainHandler.post {
                            if (handle != activeHandle) {
                                return@post
                            }
                            if (
                                NativeRuntime.nativeTransition(
                                    activeHandle,
                                    NativeRuntime.LIFECYCLE_RUNNING,
                                ) != 0
                            ) {
                                Log.e(TAG, "Native runtime start transition failed")
                                stopSelf()
                                return@post
                            }
                            readyHandle = activeHandle
                            processPendingLauncherResult()
                            Log.i(TAG, "Package runtime ready: $packageVersion")
                            Log.i(
                                TAG,
                                "Shared Rust runtime started; root directories created=$createdDirectories",
                            )
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            if (handle == activeHandle) {
                                Log.e(TAG, "Runtime bootstrap failed", error)
                                stopSelf()
                            }
                        }
                    } finally {
                        mainHandler.post {
                            bootstrapActive = false
                            bootstrapThread = null
                            stopIfUnobservedAndIdle()
                        }
                    }
                },
                "ArchpheneBootstrap",
            ).also(Thread::start)
    }

    private fun preparePackageRuntime(activeHandle: Long): String {
        val (architecture, repositoryArchitecture) =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64" to NativeRuntime.REPOSITORY_X86_64
                "arm64-v8a" -> "aarch64" to NativeRuntime.REPOSITORY_AARCH64
                else -> throw IllegalStateException("Unsupported Android ABI")
            }
        val nativePathBytes =
            File(applicationInfo.nativeLibraryDir)
                .canonicalPath
                .toByteArray(StandardCharsets.UTF_8)
        val manifestBytes =
            assets.open("package-runtime-$architecture.tsv").use { input ->
                input.readBytes()
            }
        if (manifestBytes.isEmpty() || manifestBytes.size > NativeRuntime.PACKAGE_MANIFEST_LIMIT) {
            throw IllegalStateException("Invalid package-runtime manifest size")
        }
        val nativePathBuffer = ByteBuffer.allocateDirect(nativePathBytes.size)
        nativePathBuffer.put(nativePathBytes)
        val manifestBuffer = ByteBuffer.allocateDirect(manifestBytes.size)
        manifestBuffer.put(manifestBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativePreparePackageRuntime(
                activeHandle,
                repositoryArchitecture,
                nativePathBuffer,
                nativePathBytes.size,
                manifestBuffer,
                manifestBytes.size,
                outputBuffer,
            )
        if (outputLength <= 0 || outputLength > NativeRuntime.PACKAGE_OUTPUT_SIZE) {
            outputBuffer.position(0)
            val diagnosticBytes = ByteArray(NativeRuntime.PACKAGE_OUTPUT_SIZE)
            outputBuffer.get(diagnosticBytes)
            val terminator = diagnosticBytes.indexOf(0)
            val diagnosticLength =
                if (terminator >= 0) {
                    terminator
                } else {
                    diagnosticBytes.size
                }
            val diagnostic =
                String(
                    diagnosticBytes,
                    0,
                    diagnosticLength,
                    StandardCharsets.UTF_8,
                ).trim()
            throw IllegalStateException(
                buildString {
                    append("Package-runtime probe failed: ")
                    append(outputLength)
                    if (diagnostic.isNotEmpty()) {
                        append(" (")
                        append(diagnostic)
                        append(')')
                    }
                },
            )
        }
        val outputBytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(outputBytes)
        return outputBytes
            .toString(StandardCharsets.UTF_8)
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line -> line.contains("Pacman v") }
            ?: throw IllegalStateException("Package-runtime probe returned no pacman version")
    }

    private fun refreshInstalledPackages(activeHandle: Long): Boolean {
        val names = ArrayList<String>()
        val versions = ArrayList<String>()
        val explicitFlags = BooleanArray(NativeRuntime.INSTALLED_PACKAGE_LIMIT)
        var offset = 0
        var previousName = ""
        try {
            while (offset <= NativeRuntime.INSTALLED_PACKAGE_LIMIT) {
                installedPackageOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeListInstalledPackages(
                        activeHandle,
                        offset,
                        installedPackageOutputBuffer,
                    )
                if (outputLength < 0) {
                    throw IllegalStateException(
                        readNativeMessage(installedPackageOutputBuffer, outputLength),
                    )
                }
                if (outputLength == 0) {
                    break
                }
                if (outputLength > installedPackageOutputBytes.size) {
                    throw IllegalStateException("Installed package page exceeds its output buffer")
                }
                installedPackageOutputBuffer.position(0)
                installedPackageOutputBuffer.get(installedPackageOutputBytes, 0, outputLength)
                var rowStart = 0
                var pageRows = 0
                while (rowStart < outputLength) {
                    var firstTab = -1
                    var secondTab = -1
                    var rowEnd = -1
                    var index = rowStart
                    while (index < outputLength) {
                        when (installedPackageOutputBytes[index]) {
                            '\t'.code.toByte() -> {
                                if (firstTab < 0) {
                                    firstTab = index
                                } else if (secondTab < 0) {
                                    secondTab = index
                                } else {
                                    throw IllegalStateException("Invalid installed package row")
                                }
                            }
                            '\n'.code.toByte() -> {
                                rowEnd = index
                                break
                            }
                        }
                        index++
                    }
                    if (
                        firstTab <= rowStart ||
                        secondTab <= firstTab + 1 ||
                        rowEnd != secondTab + 2
                    ) {
                        throw IllegalStateException("Invalid installed package row")
                    }
                    val name =
                        String(
                            installedPackageOutputBytes,
                            rowStart,
                            firstTab - rowStart,
                            StandardCharsets.UTF_8,
                        )
                    val version =
                        String(
                            installedPackageOutputBytes,
                            firstTab + 1,
                            secondTab - firstTab - 1,
                            StandardCharsets.UTF_8,
                        )
                    if (previousName.isNotEmpty() && name <= previousName) {
                        throw IllegalStateException("Installed packages are not strictly ordered")
                    }
                    val explicitlyInstalled =
                        when (installedPackageOutputBytes[secondTab + 1]) {
                            '1'.code.toByte() -> true
                            '0'.code.toByte() -> false
                            else -> throw IllegalStateException("Invalid package install reason")
                        }
                    explicitFlags[names.size] = explicitlyInstalled
                    names.add(name)
                    versions.add(version)
                    previousName = name
                    pageRows++
                    rowStart = rowEnd + 1
                }
                if (
                    pageRows == 0 ||
                    pageRows > NativeRuntime.INSTALLED_PACKAGE_PAGE_SIZE ||
                    names.size > NativeRuntime.INSTALLED_PACKAGE_LIMIT
                ) {
                    throw IllegalStateException("Invalid installed package page size")
                }
                offset += pageRows
                if (pageRows < NativeRuntime.INSTALLED_PACKAGE_PAGE_SIZE) {
                    break
                }
            }
            val previousRevision = installedPackageSnapshot.revision
            installedPackageSnapshot =
                InstalledPackageSnapshot(
                    names.toTypedArray(),
                    versions.toTypedArray(),
                    explicitFlags.copyOf(names.size),
                    if (names.isEmpty()) {
                        "No Linux packages installed"
                    } else {
                        "${names.size} Linux packages installed"
                    },
                    previousRevision + 1,
                )
            return true
        } catch (error: Exception) {
            val previous = installedPackageSnapshot
            installedPackageSnapshot =
                InstalledPackageSnapshot(
                    previous.names,
                    previous.versions,
                    previous.explicitlyInstalled,
                    "Installed package list unavailable",
                    previous.revision + 1,
                )
            Log.w(TAG, "Could not refresh installed package list", error)
            return false
        }
    }

    private fun refreshDesktopEntries(activeHandle: Long): Boolean {
        val desktopIds = ArrayList<String>()
        val names = ArrayList<String>()
        val executables = ArrayList<String>()
        val terminal = BooleanArray(NativeRuntime.DESKTOP_ENTRY_LIMIT)
        val icons = ArrayList<String>()
        val sourcePackages = ArrayList<String>()
        var offset = 0
        var expectedTotal = -1
        var examined = 0
        var rejected = 0
        var truncated = false
        var previousName = ""
        var previousDesktopId = ""
        try {
            while (true) {
                desktopEntryOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeListDesktopEntries(
                        activeHandle,
                        offset,
                        desktopEntryOutputBuffer,
                    )
                if (outputLength < 0) {
                    throw IllegalStateException(
                        readNativeMessage(desktopEntryOutputBuffer, outputLength),
                    )
                }
                if (
                    outputLength == 0 ||
                    outputLength > desktopEntryOutputBytes.size
                ) {
                    throw IllegalStateException("Invalid desktop-entry page length")
                }
                desktopEntryOutputBuffer.position(0)
                desktopEntryOutputBuffer.get(desktopEntryOutputBytes, 0, outputLength)
                if (desktopEntryOutputBytes[outputLength - 1] != '\n'.code.toByte()) {
                    throw IllegalStateException("Desktop-entry page is not terminated")
                }
                val lines =
                    String(
                        desktopEntryOutputBytes,
                        0,
                        outputLength,
                        StandardCharsets.UTF_8,
                    ).dropLast(1).split('\n')
                val header = lines.first().split('\t')
                if (header.size != 6 || header[0] != "D2") {
                    throw IllegalStateException("Invalid desktop-entry page header")
                }
                val nextOffset = header[1].toInt()
                val total = header[2].toInt()
                val pageExamined = header[3].toInt()
                val pageRejected = header[4].toInt()
                val pageTruncated =
                    when (header[5]) {
                        "0" -> false
                        "1" -> true
                        else -> throw IllegalStateException("Invalid desktop scan state")
                    }
                if (
                    total !in 0..NativeRuntime.DESKTOP_ENTRY_LIMIT ||
                    nextOffset !in offset..total ||
                    lines.size - 1 != nextOffset - offset ||
                    pageExamined !in total..1024 ||
                    pageRejected !in 0..pageExamined ||
                    (expectedTotal >= 0 && expectedTotal != total)
                ) {
                    throw IllegalStateException("Inconsistent desktop-entry page")
                }
                expectedTotal = total
                examined = pageExamined
                rejected = pageRejected
                truncated = pageTruncated
                for (line in lines.drop(1)) {
                    val fields = line.split('\t', limit = 9)
                    if (fields.size != 9) {
                        throw IllegalStateException("Invalid desktop-entry row")
                    }
                    val desktopId = fields[0]
                    val name = fields[1]
                    val executable = fields[2]
                    val rowTerminal =
                        when (fields[3]) {
                            "0" -> false
                            "1" -> true
                            else -> throw IllegalStateException("Invalid desktop terminal flag")
                        }
                    val icon = fields[4]
                    val tryExec = fields[5]
                    val argumentSpec = fields[6]
                    val mimeSpec = fields[7]
                    val sourcePackage = fields[8]
                    if (
                        desktopId.isEmpty() ||
                        name.isEmpty() ||
                        !executable.startsWith('/') ||
                        (
                            sourcePackage.isNotEmpty() &&
                                (
                                    sourcePackage.length > 128 ||
                                        sourcePackage == "." ||
                                        sourcePackage == ".." ||
                                        !sourcePackage.all { character ->
                                            character.code < 128 &&
                                                (
                                                    character.isLetterOrDigit() ||
                                                        character == '@' ||
                                                        character == '.' ||
                                                        character == '_' ||
                                                        character == '+' ||
                                                        character == '-'
                                                )
                                        }
                                )
                        ) ||
                        (tryExec.isNotEmpty() && !tryExec.startsWith('/')) ||
                        (
                            previousName.isNotEmpty() &&
                                (name < previousName ||
                                    (name == previousName && desktopId <= previousDesktopId))
                        )
                    ) {
                        throw IllegalStateException("Invalid desktop-entry identity")
                    }
                    if (argumentSpec.isNotEmpty()) {
                        for (argument in argumentSpec.split('\u001f')) {
                            when (argument) {
                                "f", "F", "u", "U", "i", "c", "k" -> Unit
                                else -> {
                                    if (!(argument.startsWith("L:") && argument.length > 2)) {
                                        throw IllegalStateException("Invalid desktop argument")
                                    }
                                }
                            }
                        }
                    }
                    if (
                        mimeSpec.isNotEmpty() &&
                        (
                            !mimeSpec.endsWith(';') ||
                                mimeSpec
                                    .dropLast(1)
                                    .split(';')
                                    .any { value -> !value.contains('/') }
                        )
                    ) {
                        throw IllegalStateException("Invalid desktop MIME list")
                    }
                    terminal[desktopIds.size] = rowTerminal
                    desktopIds.add(desktopId)
                    names.add(name)
                    executables.add(executable)
                    icons.add(icon)
                    sourcePackages.add(sourcePackage)
                    previousName = name
                    previousDesktopId = desktopId
                }
                offset = nextOffset
                if (offset == total) {
                    break
                }
                if (lines.size == 1) {
                    throw IllegalStateException("Desktop-entry pagination made no progress")
                }
            }
            val launcherSummary = readLauncherSummary(activeHandle)
            launcherPublicationPending =
                launcherSummary?.let { summary ->
                    summary.needsPublish > 0 || summary.needsRemoval > 0
                } == true
            launcherCancelledCount = launcherSummary?.cancelled ?: 0
            val launcherRows =
                if (
                    launcherSummary != null &&
                    (launcherSummary.needsReview > 0 || launcherSummary.dismissed > 0)
                ) {
                    readLauncherRegistryRows(activeHandle)
                } else {
                    emptyList()
                }
            updateLauncherReviewSnapshot(launcherRows)
            val status =
                buildString {
                    append(desktopIds.size)
                    append(
                        if (desktopIds.size == 1) {
                            " launchable Linux app found"
                        } else {
                            " launchable Linux apps found"
                        },
                    )
                    if (rejected > 0) {
                        append(" · ")
                        append(rejected)
                        append(" invalid ")
                        append(if (rejected == 1) "entry" else "entries")
                        append(" ignored")
                    }
                    if (truncated) {
                        append(" · scan limit reached")
                    }
                    if (launcherSummary == null) {
                        append(" · launcher registry paused")
                    } else {
                        if (launcherSummary.current > 0) {
                            append(" · ")
                            append(launcherSummary.current)
                            append(" Android ")
                            append(
                                if (launcherSummary.current == 1) {
                                    "launcher"
                                } else {
                                    "launchers"
                                },
                            )
                            append(" installed")
                        }
                        val pending =
                            launcherSummary.needsPublish +
                                launcherSummary.needsRemoval +
                                launcherSummary.active
                        if (pending > 0) {
                            append(" · ")
                            append(pending)
                            append(" launcher ")
                            append(if (pending == 1) "change" else "changes")
                            append(" pending")
                        }
                        if (launcherSummary.failed > 0) {
                            append(" · ")
                            append(launcherSummary.failed)
                            append(" launcher ")
                            append(if (launcherSummary.failed == 1) "failure" else "failures")
                        }
                        if (launcherSummary.cancelled > 0) {
                            append(" · ")
                            append(launcherSummary.cancelled)
                            append(" launcher ")
                            append(
                                if (launcherSummary.cancelled == 1) {
                                    "confirmation cancelled"
                                } else {
                                    "confirmations cancelled"
                                },
                            )
                        }
                        if (launcherSummary.needsReview > 0) {
                            append(" · ")
                            append(launcherSummary.needsReview)
                            append(" ")
                            append(
                                if (launcherSummary.needsReview == 1) {
                                    "launcher awaiting selection"
                                } else {
                                    "launchers awaiting selection"
                                },
                            )
                        }
                        if (launcherSummary.dismissed > 0) {
                            append(" · ")
                            append(launcherSummary.dismissed)
                            append(" ")
                            append(
                                if (launcherSummary.dismissed == 1) {
                                    "launcher not added"
                                } else {
                                    "launchers not added"
                                },
                            )
                            append(" · tap to manage")
                        }
                    }
                }
            val previousRevision = desktopEntrySnapshot.revision
            desktopEntrySnapshot =
                DesktopEntrySnapshot(
                    desktopIds.toTypedArray(),
                    names.toTypedArray(),
                    executables.toTypedArray(),
                    terminal.copyOf(desktopIds.size),
                    icons.toTypedArray(),
                    sourcePackages.toTypedArray(),
                    status,
                    previousRevision + 1,
                )
            Log.i(
                TAG,
                "Desktop catalog refreshed: entries=${desktopIds.size} examined=$examined rejected=$rejected truncated=$truncated",
            )
            return true
        } catch (error: Exception) {
            launcherPublicationPending = false
            val previous = desktopEntrySnapshot
            desktopEntrySnapshot =
                DesktopEntrySnapshot(
                    previous.desktopIds,
                    previous.names,
                    previous.executables,
                    previous.terminal,
                    previous.icons,
                    previous.sourcePackages,
                    "Linux app discovery unavailable",
                    previous.revision + 1,
                )
            Log.w(TAG, "Could not refresh Linux desktop entries", error)
            return false
        }
    }

    private fun refreshPackageInventory(activeHandle: Long): Boolean {
        val installedPackagesReady = refreshInstalledPackages(activeHandle)
        refreshDesktopEntries(activeHandle)
        return installedPackagesReady
    }

    private fun startLauncherPublisher(activeHandle: Long) {
        if (
            readyHandle != activeHandle ||
            !launcherPublisherActive.compareAndSet(false, true)
        ) {
            return
        }
        Thread(
            {
                var claimedPackage = ""
                var claimedGeneration = 0L
                try {
                    val output = ByteBuffer.allocateDirect(1024)
                    val bytes = ByteArray(1024)
                    val removalLength =
                        NativeRuntime.nativeClaimLauncherRemoval(
                            activeHandle,
                            output,
                        )
                    if (removalLength != 0) {
                        check(removalLength in 1..bytes.size) {
                            "Could not claim launcher removal: $removalLength"
                        }
                        output.position(0)
                        output.get(bytes, 0, removalLength)
                        val removal =
                            String(bytes, 0, removalLength, StandardCharsets.US_ASCII)
                                .trimEnd('\n')
                                .split('\t')
                        check(
                            removal.size == 3 &&
                                removal[0] == "R1" &&
                                LAUNCHER_PACKAGE.matches(removal[1]),
                        ) {
                            "Invalid native launcher removal"
                        }
                        val generation = removal[2].toLongOrNull()
                        check(generation != null && generation in 1..Int.MAX_VALUE.toLong()) {
                            "Invalid native launcher removal generation"
                        }
                        claimedPackage = removal[1]
                        claimedGeneration = generation
                        LauncherPackageInstaller.uninstall(
                            this,
                            claimedPackage,
                            claimedGeneration,
                        )
                        Log.i(
                            TAG,
                            "Submitted launcher removal package=$claimedPackage " +
                                "generation=$claimedGeneration",
                        )
                        return@Thread
                    }
                    val summary = readLauncherSummary(activeHandle)
                    if (summary == null || summary.needsPublish == 0) {
                        launcherPublisherActive.set(false)
                        return@Thread
                    }
                    if (!packageManager.canRequestPackageInstalls()) {
                        launcherPermissionRequired = true
                        launcherPublisherActive.set(false)
                        mainHandler.post { stopIfUnobservedAndIdle() }
                        return@Thread
                    }
                    launcherPermissionRequired = false
                    output.clear()
                    val length =
                        NativeRuntime.nativeClaimLauncherPublish(
                            activeHandle,
                            output,
                        )
                    if (length == 0) {
                        launcherPublisherActive.set(false)
                        return@Thread
                    }
                    check(length in 1..bytes.size) {
                        "Could not claim launcher publication: $length"
                    }
                    output.position(0)
                    output.get(bytes, 0, length)
                    val fields =
                        String(bytes, 0, length, StandardCharsets.UTF_8)
                            .trimEnd('\n')
                            .split('\t', limit = 7)
                    check(
                        fields.size == 7 &&
                            fields[0] == "W2" &&
                            LAUNCHER_PACKAGE.matches(fields[1]) &&
                            LAUNCHER_DESCRIPTOR.matches(fields[2]),
                    ) {
                        "Invalid native launcher publication"
                    }
                    val generation = fields[3].toLongOrNull()
                    check(generation != null && generation in 1..Int.MAX_VALUE.toLong()) {
                        "Invalid native launcher generation"
                    }
                    claimedPackage = fields[1]
                    claimedGeneration = generation
                    val iconDigest = decodeSha256(fields[6])
                    check(
                        (fields[5].isEmpty() && fields[6].isEmpty()) ||
                            (
                                fields[5].startsWith('/') &&
                                    fields[5].length <= 240 &&
                                    iconDigest != null
                            ),
                    ) {
                        "Invalid native launcher icon"
                    }
                    val iconPng =
                        if (iconDigest == null) {
                            null
                        } else {
                            loadLauncherIcon(fields[5], iconDigest)
                                ?: error("Package launcher icon changed or is unsupported")
                        }
                    val generated =
                        LauncherApkAssembler.assembleAndSign(
                            this,
                            LauncherApkRequest(
                                claimedPackage,
                                fields[2],
                                claimedGeneration,
                                fields[4],
                                iconPng,
                                iconDigest,
                            ),
                        )
                    check(
                        launcherTransition(
                            activeHandle,
                            "awaiting-install",
                            claimedPackage,
                            claimedGeneration,
                        ),
                    ) {
                        "Could not persist launcher installer handoff"
                    }
                    val session = LauncherPackageInstaller.submit(this, generated)
                    Log.i(
                        TAG,
                        "Submitted launcher package=$claimedPackage " +
                            "generation=$claimedGeneration session=$session",
                    )
                } catch (error: Exception) {
                    if (claimedPackage.isNotEmpty() && claimedGeneration != 0L) {
                        launcherTransition(
                            activeHandle,
                            "failed",
                            claimedPackage,
                            claimedGeneration,
                        )
                    }
                    launcherPublisherActive.set(false)
                    Log.e(TAG, "Launcher publication failed", error)
                }
            },
            "ArchpheneLauncherPublisher",
        ).start()
    }

    private fun loadLauncherIcon(
        logicalPath: String,
        expectedSha256: ByteArray,
    ): ByteArray? {
        val root = File(filesDir, "arch-root").canonicalFile
        val relative = logicalPath.removePrefix("/")
        if (
            relative.isEmpty() ||
            relative.split('/').any { part -> part.isEmpty() || part == "." || part == ".." }
        ) {
            return null
        }
        val icon = File(root, relative).canonicalFile
        if (icon == root || !icon.path.startsWith("${root.path}${File.separator}")) {
            return null
        }
        val descriptor =
            try {
                Os.open(
                    icon.path,
                    OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    0,
                )
            } catch (_: Exception) {
                return null
            }
        try {
            val stat = Os.fstat(descriptor)
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_mode and 18 != 0 ||
                stat.st_size !in 33..LAUNCHER_ICON_BYTES_LIMIT.toLong()
            ) {
                return null
            }
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeFileDescriptor(descriptor, null, bounds)
            if (
                bounds.outMimeType != "image/png" ||
                bounds.outWidth !in 1..LAUNCHER_ICON_DIMENSION_LIMIT ||
                bounds.outHeight !in 1..LAUNCHER_ICON_DIMENSION_LIMIT ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() >
                LAUNCHER_ICON_PIXEL_LIMIT
            ) {
                return null
            }
            Os.lseek(descriptor, 0, OsConstants.SEEK_SET)
            val bytes = ByteArray(stat.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = Os.read(descriptor, bytes, offset, bytes.size - offset)
                if (read <= 0) {
                    return null
                }
                offset += read
            }
            return bytes.takeIf { value ->
                MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(value),
                    expectedSha256,
                )
            }
        } finally {
            Os.close(descriptor)
        }
    }

    private fun decodeSha256(value: String): ByteArray? {
        if (value.isEmpty()) {
            return null
        }
        if (value.length != 64) {
            return null
        }
        return ByteArray(32).also { output ->
            for (index in output.indices) {
                val high = value[index * 2].digitToIntOrNull(16) ?: return null
                val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
                output[index] = ((high shl 4) or low).toByte()
            }
        }
    }

    private fun processPendingLauncherResult() {
        val activeHandle = readyHandle
        val androidPackage = pendingLauncherResultPackage
        val generation = pendingLauncherResultGeneration
        if (
            activeHandle == 0L ||
            androidPackage.isEmpty() ||
            generation == 0L
        ) {
            return
        }
        pendingLauncherResultPackage = ""
        pendingLauncherResultGeneration = 0
        val action = pendingLauncherResultAction
        pendingLauncherResultAction = ""
        Thread(
            {
                val transition =
                    when (action) {
                        ACTION_LAUNCHER_INSTALLED -> "installed"
                        ACTION_LAUNCHER_REMOVED -> "removed"
                        ACTION_LAUNCHER_CANCELLED -> "cancelled"
                        else -> "failed"
                    }
                val transitioned =
                    launcherTransition(
                        activeHandle,
                        transition,
                        androidPackage,
                        generation,
                    )
                launcherPublisherActive.set(false)
                if (transitioned) {
                    refreshDesktopEntries(activeHandle)
                    Log.i(
                        TAG,
                        "Launcher package=$androidPackage generation=$generation " +
                            transition,
                    )
                } else {
                    Log.e(TAG, "Could not persist launcher install result")
                }
            },
            "ArchpheneLauncherResult",
        ).start()
    }

    @Suppress("DEPRECATION")
    private fun reconcileInstalledLaunchers(activeHandle: Long) {
        val rows = readLauncherRegistryRows(activeHandle)
        if (rows.isEmpty()) {
            return
        }
        val signer = LauncherApkSigner.signerSha256()
        val templateDigest = LauncherApkAssembler.templateDigestHex(this)
        val activeInstallerSessions =
            runCatching {
                packageManager.packageInstaller.mySessions
            }.getOrElse { error ->
                Log.w(TAG, "Could not inspect active launcher installer sessions", error)
                emptyList()
            }
        for (row in rows) {
            val generation =
                try {
                    val flags =
                        PackageManager.GET_META_DATA or
                            PackageManager.GET_SIGNING_CERTIFICATES
                    val info = packageManager.getPackageInfo(row.androidPackage, flags)
                    val application = info.applicationInfo
                        ?: error("launcher application metadata is missing")
                    val metadata = application.metaData
                        ?: error("launcher metadata is missing")
                    val generationValue =
                        metadata
                            .getString("org.archphene.launcher.GENERATION")
                            ?.takeIf { value ->
                                value.length == 22 &&
                                    value.startsWith("g:") &&
                                    value.drop(2).all(Char::isDigit)
                            }?.drop(2)
                            ?.toLongOrNull()
                        ?: error("launcher generation metadata is invalid")
                    val certificates =
                        info.signingInfo?.apkContentsSigners
                            ?: error("launcher signer is missing")
                    check(
                        info.packageName == row.androidPackage &&
                            generationValue <= row.desiredGeneration &&
                            info.longVersionCode == generationValue &&
                            metadata.getString("org.archphene.launcher.DESCRIPTOR_ID") ==
                            "d:${row.descriptorIdHex}" &&
                            metadata.getString("org.archphene.launcher.MANAGER_PACKAGE") ==
                            packageName &&
                            certificates.size == 1 &&
                            MessageDigest.isEqual(
                                MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(certificates.single().toByteArray()),
                                signer,
                            ),
                    ) {
                        "launcher identity or signer changed"
                    }
                    if (
                        generationValue == row.desiredGeneration &&
                        row.status != LAUNCHER_STATUS_NEEDS_REMOVAL &&
                        row.status != LAUNCHER_STATUS_AWAITING_REMOVAL &&
                        metadata.getString("org.archphene.launcher.TEMPLATE_SHA256") !=
                        "h:$templateDigest"
                    ) {
                        -2L
                    } else {
                        generationValue
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    -1L
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "Refusing untrusted launcher package=${row.androidPackage}",
                        error,
                    )
                    0L
                }
            val transitioned =
                when {
                    generation == -2L -> {
                        for (
                            session in
                                activeInstallerSessions.filter { session ->
                                    session.appPackageName == row.androidPackage
                                }
                        ) {
                            runCatching {
                                packageManager.packageInstaller.abandonSession(session.sessionId)
                            }.onFailure { error ->
                                Log.w(
                                    TAG,
                                    "Could not abandon stale launcher session=" +
                                        session.sessionId,
                                    error,
                                )
                            }
                        }
                        launcherTransition(
                            activeHandle,
                            "template-stale",
                            row.androidPackage,
                            row.desiredGeneration,
                        )
                    }
                    generation > 0 ->
                        launcherTransition(
                            activeHandle,
                            "present",
                            row.androidPackage,
                            generation,
                        )
                    generation == -1L &&
                        row.status == LAUNCHER_STATUS_AWAITING_INSTALL -> {
                        for (
                            session in
                                activeInstallerSessions.filter { session ->
                                    session.appPackageName == row.androidPackage
                                }
                        ) {
                            runCatching {
                                packageManager.packageInstaller.abandonSession(session.sessionId)
                            }.onFailure { error ->
                                Log.w(
                                    TAG,
                                    "Could not abandon interrupted launcher session=" +
                                        session.sessionId,
                                    error,
                                )
                            }
                        }
                        launcherTransition(activeHandle, "absent", row.androidPackage, 0)
                    }
                    generation == -1L ->
                        launcherTransition(activeHandle, "absent", row.androidPackage, 0)
                    else ->
                        launcherTransition(activeHandle, "quarantined", row.androidPackage, 0)
                }
            check(transitioned) {
                "Could not reconcile Android launcher ${row.androidPackage}"
            }
        }
    }

    private fun readLauncherRegistryRows(activeHandle: Long): List<LauncherRegistryRow> {
        val rows = ArrayList<LauncherRegistryRow>()
        val output = ByteBuffer.allocateDirect(8192)
        val bytes = ByteArray(8192)
        var offset = 0
        var expectedTotal = -1
        while (true) {
            output.clear()
            val length =
                NativeRuntime.nativeLauncherRegistryPage(
                    activeHandle,
                    offset,
                    output,
                )
            if (length == NativeRuntime.ERROR_INVALID_STATE && offset == 0) {
                Log.i(TAG, "Launcher registry is unavailable; reconciliation paused")
                return emptyList()
            }
            check(length in 1..bytes.size) {
                "Could not read launcher registry: $length"
            }
            output.position(0)
            output.get(bytes, 0, length)
            val lines =
                String(bytes, 0, length, StandardCharsets.UTF_8)
                    .trimEnd('\n')
                    .split('\n')
            val header = lines.first().split('\t')
            check(header.size == 3 && header[0] == "P2") {
                "Invalid launcher registry page"
            }
            val next = header[1].toInt()
            val total = header[2].toInt()
            check(
                total in 0..NativeRuntime.DESKTOP_ENTRY_LIMIT &&
                    next in offset..total &&
                    lines.size - 1 == next - offset &&
                    (expectedTotal == -1 || expectedTotal == total),
            ) {
                "Inconsistent launcher registry page"
            }
            expectedTotal = total
            for (line in lines.drop(1)) {
                val fields = line.split('\t')
                check(
                    fields.size == 8 &&
                        LAUNCHER_PACKAGE.matches(fields[0]) &&
                        LAUNCHER_DESCRIPTOR.matches(fields[1]),
                ) {
                    "Invalid launcher registry row"
                }
                val desired = fields[2].toLong()
                val published = fields[3].toLong()
                val pending = fields[4].toLong()
                val status = fields[5].toInt()
                val name = fields[6]
                val sourcePackage = fields[7]
                check(
                    desired in 1..Int.MAX_VALUE.toLong() &&
                        published in 0..desired &&
                        pending in 0..desired &&
                        status in 1..10 &&
                        name.toByteArray(StandardCharsets.UTF_8).size in 1..256 &&
                        name.none(Char::isISOControl) &&
                        (sourcePackage.isEmpty() || AUR_PACKAGE_NAME.matches(sourcePackage)),
                ) {
                    "Invalid launcher registry state"
                }
                rows.add(
                    LauncherRegistryRow(
                        fields[0],
                        fields[1],
                        desired,
                        status,
                        name,
                        sourcePackage,
                    ),
                )
            }
            offset = next
            if (offset == total) {
                return rows
            }
        }
    }

    private fun updateLauncherReviewSnapshot(rows: List<LauncherRegistryRow>) {
        val relevant =
            rows.filter { row ->
                row.status == LAUNCHER_STATUS_DISMISSED ||
                    row.status == LAUNCHER_STATUS_NEEDS_REVIEW
            }
        val packages = Array(relevant.size) { index -> relevant[index].androidPackage }
        val generations = LongArray(relevant.size) { index -> relevant[index].desiredGeneration }
        val labels = Array(relevant.size) { index -> relevant[index].name }
        val sources = Array(relevant.size) { index -> relevant[index].sourcePackage }
        val statuses = IntArray(relevant.size) { index -> relevant[index].status }
        val previous = launcherReviewSnapshot
        if (
            packages.contentEquals(previous.androidPackages) &&
            generations.contentEquals(previous.desiredGenerations) &&
            labels.contentEquals(previous.labels) &&
            sources.contentEquals(previous.sourcePackages) &&
            statuses.contentEquals(previous.statuses)
        ) {
            return
        }
        launcherReviewSnapshot =
            LauncherReviewSnapshot(
                packages,
                generations,
                labels,
                sources,
                statuses,
                statuses.count { status -> status == LAUNCHER_STATUS_NEEDS_REVIEW },
                statuses.count { status -> status == LAUNCHER_STATUS_DISMISSED },
                previous.revision + 1,
            )
    }

    private fun requestCancelledLauncherDecision(action: String): Boolean {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            launcherCancelledCount == 0 ||
            (action != "retry" && action != "dismiss") ||
            !launcherDecisionActive.compareAndSet(false, true)
        ) {
            return false
        }
        Thread(
            {
                try {
                    val row =
                        readLauncherRegistryRows(activeHandle)
                            .firstOrNull { entry ->
                                entry.status == LAUNCHER_STATUS_CANCELLED
                            }
                    if (row == null) {
                        launcherCancelledCount = 0
                        return@Thread
                    }
                    check(
                        launcherTransition(
                            activeHandle,
                            action,
                            row.androidPackage,
                            row.desiredGeneration,
                        ),
                    ) {
                        "Could not persist cancelled launcher decision"
                    }
                    refreshDesktopEntries(activeHandle)
                } catch (error: Exception) {
                    Log.e(TAG, "Cancelled launcher decision failed", error)
                } finally {
                    launcherDecisionActive.set(false)
                    mainHandler.post { stopIfUnobservedAndIdle() }
                }
            },
            "ArchpheneLauncherDecision",
        ).start()
        return true
    }

    private fun requestLauncherReview(
        revision: Int,
        publish: BooleanArray,
    ): Boolean {
        val activeHandle = readyHandle
        val review = launcherReviewSnapshot
        if (
            activeHandle == 0L ||
            review.revision != revision ||
            review.androidPackages.isEmpty() ||
            publish.size != review.androidPackages.size ||
            (review.needsReviewCount == 0 && publish.none { selected -> selected }) ||
            !launcherReviewActive.compareAndSet(false, true)
        ) {
            return false
        }
        val choices = publish.copyOf()
        Thread(
            {
                try {
                    val request =
                        buildString {
                            append("B1\t")
                            append(review.androidPackages.size)
                            append('\n')
                            for (index in review.androidPackages.indices) {
                                append(review.androidPackages[index])
                                append('\t')
                                append(review.desiredGenerations[index])
                                append('\t')
                                append(if (choices[index]) '1' else '0')
                                append('\n')
                            }
                        }.toByteArray(StandardCharsets.US_ASCII)
                    check(request.size <= NativeRuntime.LAUNCHER_REVIEW_REQUEST_LIMIT) {
                        "Launcher review request exceeds its bound"
                    }
                    val buffer =
                        ByteBuffer.allocateDirect(NativeRuntime.LAUNCHER_REVIEW_REQUEST_LIMIT)
                    buffer.put(request)
                    check(
                        NativeRuntime.nativeReviewLaunchers(
                            activeHandle,
                            buffer,
                            request.size,
                        ) == 0,
                    ) {
                        "Could not persist launcher review"
                    }
                    refreshDesktopEntries(activeHandle)
                } catch (error: Exception) {
                    Log.e(TAG, "Launcher review failed", error)
                } finally {
                    launcherReviewActive.set(false)
                    mainHandler.post { stopIfUnobservedAndIdle() }
                }
            },
            "ArchpheneLauncherReview",
        ).start()
        return true
    }

    private fun launcherTransition(
        activeHandle: Long,
        action: String,
        androidPackage: String,
        generation: Long,
    ): Boolean {
        val request =
            "T1\t$action\t$androidPackage\t$generation\n"
                .toByteArray(StandardCharsets.US_ASCII)
        if (request.size > 160) {
            return false
        }
        val buffer = ByteBuffer.allocateDirect(160)
        buffer.put(request)
        return NativeRuntime.nativeLauncherTransition(
            activeHandle,
            buffer,
            request.size,
        ) == 0
    }

    private fun readLauncherSummary(activeHandle: Long): LauncherSummary? {
        desktopEntryOutputBuffer.clear()
        val outputLength =
            NativeRuntime.nativeLauncherRegistryStatus(
                activeHandle,
                desktopEntryOutputBuffer,
            )
        if (outputLength <= 0 || outputLength > desktopEntryOutputBytes.size) {
            return null
        }
        desktopEntryOutputBuffer.position(0)
        desktopEntryOutputBuffer.get(desktopEntryOutputBytes, 0, outputLength)
        val fields =
            String(
                desktopEntryOutputBytes,
                0,
                outputLength,
                StandardCharsets.US_ASCII,
            ).trimEnd('\n').split('\t')
        if (fields.size != 11 || fields[0] != "L3") {
            return null
        }
        val generation = fields[1].toLongOrNull() ?: return null
        val total = fields[2].toLongOrNull() ?: return null
        val needsPublish = fields[3].toLongOrNull() ?: return null
        val current = fields[4].toLongOrNull() ?: return null
        val needsRemoval = fields[5].toLongOrNull() ?: return null
        val active = fields[6].toLongOrNull() ?: return null
        val failed = fields[7].toLongOrNull() ?: return null
        val cancelled = fields[8].toLongOrNull() ?: return null
        val dismissed = fields[9].toLongOrNull() ?: return null
        val needsReview = fields[10].toLongOrNull() ?: return null
        if (
            generation < 0 ||
            total !in 0..NativeRuntime.DESKTOP_ENTRY_LIMIT.toLong() ||
            needsPublish !in 0..total ||
            current !in 0..total ||
            needsRemoval !in 0..total ||
            active !in 0..total ||
            failed !in 0..total ||
            cancelled !in 0..total ||
            dismissed !in 0..total ||
            needsReview !in 0..total ||
            needsPublish +
                current +
                needsRemoval +
                active +
                failed +
                cancelled +
                dismissed +
                needsReview != total
        ) {
            return null
        }
        return LauncherSummary(
            total.toInt(),
            needsPublish.toInt(),
            current.toInt(),
            needsRemoval.toInt(),
            active.toInt(),
            failed.toInt(),
            cancelled.toInt(),
            dismissed.toInt(),
            needsReview.toInt(),
        )
    }

    @Synchronized
    private fun requestCatalogRefresh(recoverPackageJob: Boolean = false): Boolean {
        val activeHandle = readyHandle
        val recoveryRevision = jobRevision
        val recoveryJobId = jobPersistentId
        val recoveryPackage = jobPackage
        val recoveryOperation = jobOperation
        val recoveryState = jobState
        val recoveryFailure = jobMessage
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            packageCacheActive ||
            commandActive ||
            (recoverPackageJob && !packageCatalogRecoveryReady())
        ) {
            return false
        }
        catalogRefreshActive = true
        val debugRecoveryFixture =
            recoverPackageJob && consumeDebugCatalogRecoveryFixture()
        if (recoverPackageJob) {
            packageRecoveryMessageRevision = recoveryRevision
            packageRecoveryMessage = "Refreshing signed package catalogs…"
        }
        catalogThread =
            Thread(
                {
                    try {
                        if (!debugRecoveryFixture) {
                            catalogStatus = "Refreshing core package catalog"
                            downloadCatalog(activeHandle, NativeRuntime.CATALOG_CORE)
                            catalogStatus = "Refreshing extra package catalog"
                            downloadCatalog(activeHandle, NativeRuntime.CATALOG_EXTRA)
                        }
                        catalogStatus = "Package catalog ready"
                        if (recoverPackageJob && jobRevision == recoveryRevision) {
                            val recoveryResult =
                                "Catalogs refreshed. Review the current package before retrying."
                            require(
                                persistPackageRecovery(
                                    recoveryJobId,
                                    recoveryPackage,
                                    recoveryOperation,
                                    recoveryState,
                                    recoveryFailure,
                                    recoveryResult,
                                ),
                            ) {
                                "Could not save the catalog refresh result"
                            }
                            packageRecoveryHandledJobRevision = recoveryRevision
                            packageRecoveryMessage = recoveryResult
                        }
                        Log.i(TAG, "Official package catalogs refreshed")
                    } catch (error: Exception) {
                        catalogStatus =
                            "Catalog refresh failed: ${error.message ?: error.javaClass.simpleName}"
                        if (recoverPackageJob && jobRevision == recoveryRevision) {
                            packageRecoveryMessage =
                                boundedJobMessage(
                                    "Catalog refresh failed: " +
                                        (error.message ?: error.javaClass.simpleName) +
                                        ". Check the connection, then try again.",
                                )
                        }
                        Log.e(TAG, "Package catalog refresh failed", error)
                    } finally {
                        catalogRefreshActive = false
                        catalogThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneCatalog",
            ).also(Thread::start)
        promoteWorkToForeground()
        return true
    }

    private fun downloadCatalog(
        activeHandle: Long,
        repository: Int,
    ) {
        val messageBuffer = ByteBuffer.allocateDirect(NativeRuntime.CATALOG_MESSAGE_SIZE)
        val descriptor =
            NativeRuntime.nativeBeginPackageCatalogDownload(
                activeHandle,
                repository,
                messageBuffer,
            )
        if (descriptor < 0) {
            throw IllegalStateException(readNativeMessage(messageBuffer, descriptor))
        }
        var finishAttempted = false
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor),
            ).use { output ->
                val endpoint = URL(readCString(messageBuffer))
                val expectedHost =
                    when (Build.SUPPORTED_ABIS.firstOrNull()) {
                        "x86_64" -> "geo.mirror.pkgbuild.com"
                        "arm64-v8a" -> "ca.us.mirror.archlinuxarm.org"
                        else -> throw IllegalStateException("Unsupported Android ABI")
                    }
                if (
                    endpoint.protocol != "https" ||
                    endpoint.host != expectedHost ||
                    endpoint.userInfo != null ||
                    endpoint.port != -1
                ) {
                    throw SecurityException("Rust supplied an invalid catalog endpoint")
                }
                val maximumBytes =
                    when (repository) {
                        NativeRuntime.CATALOG_CORE -> 8L * 1024 * 1024
                        NativeRuntime.CATALOG_EXTRA -> 64L * 1024 * 1024
                        else -> throw IllegalArgumentException("Unknown catalog")
                    }
                val connection = endpoint.openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status != HttpsURLConnection.HTTP_OK) {
                        throw IllegalStateException("Catalog server returned HTTP $status")
                    }
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > maximumBytes) {
                        throw IllegalStateException("Catalog exceeds its download limit")
                    }
                    connection.inputStream.use { input ->
                        val transferBuffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(transferBuffer)
                            if (count < 0) {
                                break
                            }
                            total += count
                            if (total > maximumBytes) {
                                throw IllegalStateException("Catalog exceeds its download limit")
                            }
                            output.write(transferBuffer, 0, count)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            messageBuffer.position(0)
            val published =
                NativeRuntime.nativeFinishPackageCatalogDownload(
                    activeHandle,
                    repository,
                    true,
                    messageBuffer,
                )
            finishAttempted = true
            if (published <= 0) {
                throw IllegalStateException(readNativeMessage(messageBuffer, published))
            }
        } finally {
            if (!finishAttempted) {
                messageBuffer.position(0)
                NativeRuntime.nativeFinishPackageCatalogDownload(
                    activeHandle,
                    repository,
                    false,
                    messageBuffer,
                )
            }
        }
    }

    private fun readNativeMessage(
        buffer: ByteBuffer,
        result: Int,
    ): String {
        val message = readCString(buffer)
        return if (message.isEmpty()) {
            "Native package operation failed: $result"
        } else {
            "$message ($result)"
        }
    }

    private fun readNativeMessage(
        buffer: ByteBuffer,
        result: Long,
    ): String = readNativeMessage(buffer, result.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt())

    private fun readCString(buffer: ByteBuffer): String {
        var length = 0
        while (length < buffer.capacity() && buffer.get(length) != 0.toByte()) {
            length += 1
        }
        val bytes = ByteArray(length)
        buffer.position(0)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    @Synchronized
    private fun requestPackageSearch(query: String): Boolean {
        val normalized = query.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized.length !in 2..128 ||
            normalized.any { character ->
                character.code > 0x7f ||
                    (!character.isLetterOrDigit() && character !in "@._+:-")
            }
        ) {
            searchStatus = "Enter 2–128 package-name characters"
            publishAvailablePackageStatus(searchStatus)
            return false
        }
        searchActive = true
        retainedAurReview = null
        retainedAurVerifiedBytes = 0L
        retainedAurSourceEvidence = emptyArray()
        retainedAurBuilderReport = null
        clearRetainedAurBuiltPackages()
        clearAurReviewPresentation()
        searchStatus = "Searching for $normalized"
        publishAvailablePackageStatus(searchStatus)
        Thread(
            {
                try {
                    val queryBytes = normalized.toByteArray(StandardCharsets.UTF_8)
                    val queryBuffer = ByteBuffer.allocateDirect(queryBytes.size)
                    queryBuffer.put(queryBytes)
                    val outputBuffer =
                        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val outputLength =
                        NativeRuntime.nativeSearchPackages(
                            activeHandle,
                            queryBuffer,
                            queryBytes.size,
                            outputBuffer,
                        )
                    if (outputLength < 0) {
                        throw IllegalStateException(
                            readNativeMessage(outputBuffer, outputLength),
                        )
                    }
                    if (outputLength == 0) {
                        searchStatus = "No official packages match $normalized"
                        publishAvailablePackageStatus(searchStatus)
                    } else {
                        val bytes = ByteArray(outputLength)
                        outputBuffer.position(0)
                        outputBuffer.get(bytes)
                        publishAvailablePackages(bytes, normalized)
                        searchStatus = availablePackageSnapshot.status
                    }
                } catch (error: Exception) {
                    searchStatus =
                        "Package search failed: ${error.message ?: error.javaClass.simpleName}"
                    publishAvailablePackageStatus(searchStatus)
                    Log.e(TAG, "Package search failed", error)
                } finally {
                    searchActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneSearch",
        ).start()
        return true
    }

    private fun publishAvailablePackageStatus(status: String) {
        val previousRevision = availablePackageSnapshot.revision
        availablePackageSnapshot =
            AvailablePackageSnapshot(
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                status,
                previousRevision + 1,
            )
    }

    private fun publishAvailablePackages(
        bytes: ByteArray,
        query: String,
    ) {
        val repositories = ArrayList<String>()
        val names = ArrayList<String>()
        val versions = ArrayList<String>()
        val descriptions = ArrayList<String>()
        val installStates = ArrayList<String>()
        val installedVersions = ArrayList<String>()
        String(bytes, StandardCharsets.UTF_8)
            .trimEnd('\n')
            .lineSequence()
            .forEach { line ->
                val fields = line.split('\t', limit = 6)
                val validInstalledVersion =
                    fields.size == 6 &&
                        fields[5].length <= 128 &&
                        fields[5].none(Char::isWhitespace)
                val validInstallState =
                    validInstalledVersion &&
                        when (fields[4]) {
                            "available" -> fields[5].isEmpty()
                            "installed" -> fields[5] == fields[2]
                            "different" -> fields[5].isNotEmpty()
                            else -> false
                        }
                if (
                    fields.size != 6 ||
                    (fields[0] != "core" && fields[0] != "extra") ||
                    fields[1].isEmpty() ||
                    fields[1].length > 128 ||
                    fields[1].any { character ->
                        character.code > 0x7f ||
                            (!character.isLetterOrDigit() && character !in "@._+-")
                    } ||
                    fields[2].isEmpty() ||
                    fields[2].length > 128 ||
                    fields[2].any(Char::isWhitespace) ||
                    fields[3].length > 512 ||
                    fields[3].any { character ->
                        character == '\u0000' || character == '\r'
                    } ||
                    !validInstallState ||
                    names.contains(fields[1]) ||
                    names.size >= AVAILABLE_PACKAGE_LIMIT
                ) {
                    throw IllegalStateException("Invalid native package-search response")
                }
                repositories.add(fields[0])
                names.add(fields[1])
                versions.add(fields[2])
                descriptions.add(fields[3])
                installStates.add(fields[4])
                installedVersions.add(fields[5])
            }
        val previousRevision = availablePackageSnapshot.revision
        val status =
            if (names.isEmpty()) {
                "No official packages match $query"
            } else {
                "${names.size} official packages match $query"
            }
        availablePackageSnapshot =
            AvailablePackageSnapshot(
                repositories.toTypedArray(),
                names.toTypedArray(),
                versions.toTypedArray(),
                descriptions.toTypedArray(),
                installStates.toTypedArray(),
                installedVersions.toTypedArray(),
                status,
                previousRevision + 1,
            )
    }

    @Synchronized
    private fun requestPackageResolution(packageName: String): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized.length !in 1..128 ||
            normalized.any { character ->
                character.code > 0x7f ||
                    (!character.isLetterOrDigit() && character !in "@._+-")
            }
        ) {
            searchStatus = "Enter one exact official package name"
            return false
        }
        searchActive = true
        lastResolvedPackage = ""
        lastResolvedRepository = ""
        lastResolvedInstalledVersion = ""
        lastResolvedAvailableVersion = ""
        primaryActionLabel = "Install"
        removeActionLabel = "Remove"
        removeAvailable = false
        searchStatus = "Resolving $normalized and its dependencies"
        Thread(
            {
                try {
                    val packages = resolvePayloads(activeHandle, normalized)
                    var totalBytes = 0L
                    var target: ResolvedPayload? = null
                    val packageNames = StringBuilder()
                    packages.forEach { resolved ->
                        totalBytes = Math.addExact(totalBytes, resolved.size)
                        if (resolved.name == normalized) {
                            target = resolved
                        }
                        if (packageNames.isNotEmpty()) {
                            packageNames.append('\n')
                        }
                        packageNames.append("  ").append(resolved.name)
                    }
                    val resolvedTarget =
                        target
                            ?: throw IllegalStateException(
                                "Resolved packages omit the requested target",
                            )
                    val installedVersion = installedPackageVersion(activeHandle, normalized)
                    lastResolvedPackage = normalized
                    lastResolvedRepository = resolvedTarget.repository
                    lastResolvedInstalledVersion = installedVersion
                    lastResolvedAvailableVersion = resolvedTarget.version
                    val recoveryOperation =
                        if (
                            normalized == jobPackage &&
                            (
                                jobState == NativeRuntime.JOB_FAILED ||
                                    jobState == NativeRuntime.JOB_CANCELLED
                            )
                        ) {
                            jobOperation
                        } else {
                            0
                        }
                    primaryActionLabel =
                        when {
                            recoveryOperation == NativeRuntime.JOB_OPERATION_INSTALL ||
                                recoveryOperation == NativeRuntime.JOB_OPERATION_UPDATE -> "Retry"
                            installedVersion.isEmpty() -> "Install"
                            installedVersion == resolvedTarget.version -> "Verify"
                            else -> "Update"
                        }
                    removeAvailable = installedVersion.isNotEmpty()
                    removeActionLabel =
                        if (
                            removeAvailable &&
                            recoveryOperation == NativeRuntime.JOB_OPERATION_REMOVE
                        ) {
                            "Retry"
                        } else {
                            "Remove"
                        }
                    if (recoveryOperation != 0) {
                        recoveryReviewedJobRevision = jobRevision
                    }
                    val mebibytes = (totalBytes + (1024 * 1024 - 1)) / (1024 * 1024)
                    searchStatus =
                        buildString {
                            append(resolvedTarget.repository)
                            append('/')
                            append(resolvedTarget.name)
                            append(' ')
                            append(resolvedTarget.version)
                            append('\n')
                            append(
                                if (installedVersion.isEmpty()) {
                                    "Not installed"
                                } else {
                                    "Installed: $installedVersion"
                                },
                            )
                            append('\n')
                            append("Dependency closure: ")
                            append(packages.size)
                            append(if (packages.size == 1) " package · " else " packages · ")
                            append(mebibytes)
                            append(" MiB download")
                            append("\n\nPackages\n")
                            append(packageNames)
                        }
                    Log.i(
                        TAG,
                        "Resolved $normalized: ${packages.size} packages, $totalBytes bytes",
                    )
                } catch (error: Exception) {
                    val installedVersion =
                        runCatching {
                            installedPackageVersion(activeHandle, normalized)
                        }.getOrDefault("")
                    val installedOrigin =
                        if (installedVersion.isEmpty()) {
                            ""
                        } else {
                            runCatching {
                                installedPackageOrigin(activeHandle, normalized)
                            }.getOrDefault("")
                        }
                    if (installedOrigin == "aur") {
                        lastResolvedPackage = normalized
                        lastResolvedRepository = "aur"
                        lastResolvedInstalledVersion = installedVersion
                        lastResolvedAvailableVersion = installedVersion
                        primaryActionLabel = "Installed"
                        removeAvailable = true
                        val removalRetry =
                            normalized == jobPackage &&
                                (
                                    jobState == NativeRuntime.JOB_FAILED ||
                                        jobState == NativeRuntime.JOB_CANCELLED
                                ) &&
                                jobOperation == NativeRuntime.JOB_OPERATION_REMOVE
                        removeActionLabel = if (removalRetry) "Retry" else "Remove"
                        if (removalRetry) {
                            recoveryReviewedJobRevision = jobRevision
                        }
                        searchStatus =
                            "aur/$normalized $installedVersion\n" +
                                "Installed from a locally verified AUR build\n" +
                                "Review AUR to check the current available version"
                        Log.i(TAG, "Resolved installed AUR package $normalized $installedVersion")
                    } else {
                        searchStatus =
                            "Package resolution failed: " +
                                (error.message ?: error.javaClass.simpleName)
                        Log.e(TAG, "Package resolution failed", error)
                    }
                } finally {
                    searchActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneResolve",
        ).start()
        return true
    }

    @Synchronized
    private fun requestAurReview(packageName: String): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized.length !in 1..128 ||
            normalized.any { character ->
                character.code > 0x7f ||
                    (!character.isLetterOrDigit() && character !in "@._+-")
            }
        ) {
            searchStatus = "Enter one exact AUR package name"
            return false
        }
        searchActive = true
        retainedAurReview = null
        retainedAurVerifiedBytes = 0L
        retainedAurSourceEvidence = emptyArray()
        retainedAurBuilderReport = null
        clearRetainedAurBuiltPackages()
        clearAurReviewPresentation()
        lastResolvedPackage = ""
        lastResolvedRepository = ""
        lastResolvedInstalledVersion = ""
        lastResolvedAvailableVersion = ""
        removeAvailable = false
        searchStatus = "Downloading the AUR review for $normalized"
        Thread(
            {
                try {
                    val packageBytes = normalized.toByteArray(StandardCharsets.US_ASCII)
                    aurPackageBuffer.clear()
                    aurPackageBuffer.put(packageBytes)
                    val rpcEndpoint = "https://aur.archlinux.org/rpc/v5/info/$normalized"
                    val rpcLength =
                        downloadAurObject(
                            rpcEndpoint,
                            aurRpcBuffer,
                            NativeRuntime.AUR_RPC_SIZE,
                        )
                    aurEndpointBuffer.clear()
                    val pathLength =
                        NativeRuntime.nativeResolveAurSnapshotPath(
                            activeHandle,
                            aurPackageBuffer,
                            packageBytes.size,
                            aurRpcBuffer,
                            rpcLength,
                            aurEndpointBuffer,
                        )
                    if (pathLength <= 0) {
                        throw IllegalStateException(
                            readNativeMessage(aurEndpointBuffer, pathLength),
                        )
                    }
                    if (pathLength > aurEndpointBuffer.capacity()) {
                        throw SecurityException("Native AUR snapshot path exceeds its limit")
                    }
                    val pathBytes = ByteArray(pathLength)
                    aurEndpointBuffer.position(0)
                    aurEndpointBuffer.get(pathBytes)
                    val snapshotPath = String(pathBytes, StandardCharsets.US_ASCII)
                    if (
                        !snapshotPath.startsWith("/cgit/aur.git/snapshot/") ||
                        !snapshotPath.endsWith(".tar.gz") ||
                        snapshotPath.any { character ->
                            character.code > 0x7f ||
                                (!character.isLetterOrDigit() &&
                                    character !in "/@._+-")
                        }
                    ) {
                        throw SecurityException("Native AUR snapshot path is invalid")
                    }
                    val snapshotLength =
                        downloadAurObject(
                            "https://aur.archlinux.org$snapshotPath",
                            aurSnapshotBuffer,
                            NativeRuntime.AUR_SNAPSHOT_SIZE,
                        )
                    val architecture =
                        when (Build.SUPPORTED_ABIS.firstOrNull()) {
                            "x86_64" -> NativeRuntime.REPOSITORY_X86_64
                            "arm64-v8a" -> NativeRuntime.REPOSITORY_AARCH64
                            else -> throw IllegalStateException("Unsupported Android ABI")
                        }
                    aurReviewBuffer.clear()
                    val reviewLength =
                        NativeRuntime.nativeReviewAur(
                            activeHandle,
                            architecture,
                            aurPackageBuffer,
                            packageBytes.size,
                            aurRpcBuffer,
                            rpcLength,
                            aurSnapshotBuffer,
                            snapshotLength,
                            aurReviewBuffer,
                        )
                    if (reviewLength <= 0) {
                        throw IllegalStateException(
                            readNativeMessage(aurReviewBuffer, reviewLength),
                        )
                    }
                    val review = parseAurReview(aurReviewBuffer, reviewLength)
                    retainedAurReview = review
                    publishAurReviewPresentation(review)
                    searchStatus =
                        "Reviewed ${review.packageName} ${review.version} · " +
                            "expand the evidence sections below"
                    Log.i(
                        TAG,
                        "Reviewed AUR ${review.packageName} ${review.version} " +
                            "commit=${review.snapshotCommit}",
                    )
                } catch (error: Exception) {
                    searchStatus =
                        "AUR review failed: ${error.message ?: error.javaClass.simpleName}"
                    Log.e(TAG, "AUR review failed", error)
                } finally {
                    activePackageConnection = null
                    searchActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneAurReview",
        ).start()
        promoteWorkToForeground()
        return true
    }

    private fun downloadAurObject(
        expectedEndpoint: String,
        destination: ByteBuffer,
        maximumBytes: Int,
    ): Int {
        val endpoint = URL(expectedEndpoint)
        if (
            endpoint.toString() != expectedEndpoint ||
            endpoint.protocol != "https" ||
            endpoint.host != "aur.archlinux.org" ||
            endpoint.userInfo != null ||
            endpoint.port != -1 ||
            endpoint.ref != null ||
            endpoint.query != null
        ) {
            throw SecurityException("Invalid AUR endpoint")
        }
        destination.clear()
        val connection = endpoint.openConnection() as HttpsURLConnection
        activePackageConnection = connection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "AUR server returned HTTP ${connection.responseCode}",
                )
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength == 0L || declaredLength > maximumBytes) {
                throw SecurityException("AUR object has an invalid size")
            }
            var total = 0
            connection.inputStream.use { input ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("AUR review interrupted")
                    }
                    val count = input.read(aurTransferBuffer)
                    if (count < 0) {
                        break
                    }
                    total = Math.addExact(total, count)
                    if (total > maximumBytes || count > destination.remaining()) {
                        throw SecurityException("AUR object exceeds its size limit")
                    }
                    destination.put(aurTransferBuffer, 0, count)
                }
            }
            if (total == 0 || declaredLength >= 0 && declaredLength != total.toLong()) {
                throw SecurityException("AUR object is empty or truncated")
            }
            return total
        } finally {
            if (activePackageConnection === connection) {
                activePackageConnection = null
            }
            connection.disconnect()
        }
    }

    @Synchronized
    private fun requestAurSourceVerification(packageName: String): Boolean {
        val normalized = packageName.trim()
        val review = retainedAurReview
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            review == null ||
            normalized != review.packageName ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            searchStatus = "Review this exact AUR package before verifying its sources"
            return false
        }
        val remoteSources =
            review.sources.withIndex().filter { (_, source) -> !source.local }
        if (
            remoteSources.isEmpty() ||
            remoteSources.any { (_, source) ->
                source.remoteUrl == null ||
                    source.checksum == null ||
                    source.insecureTransport
            }
        ) {
            searchStatus =
                "Source verification requires direct HTTPS sources with SHA-256 or SHA-512 checksums"
            return false
        }
        searchActive = true
        retainedAurVerifiedBytes = 0L
        retainedAurSourceEvidence = emptyArray()
        retainedAurBuilderReport = null
        clearRetainedAurBuiltPackages()
        searchStatus = "Preparing ${remoteSources.size} reviewed AUR source download(s)"
        Thread(
            {
                try {
                    var totalVerified = 0L
                    val evidence = ArrayList<AurSourceEvidence>(remoteSources.size)
                    remoteSources.forEachIndexed { remoteIndex, (sourceIndex, source) ->
                        val initialEndpoint =
                            source.remoteUrl
                                ?: throw SecurityException("AUR source has no HTTPS endpoint")
                        aurEndpointBuffer.clear()
                        val cachedSize =
                            NativeRuntime.nativeVerifiedCachedAurSourceSize(
                                activeHandle,
                                sourceIndex,
                                aurEndpointBuffer,
                            )
                        if (cachedSize < 0L) {
                            throw SecurityException(
                                readNativeMessage(aurEndpointBuffer, cachedSize.toInt()),
                            )
                        }
                        if (cachedSize > 0L) {
                            totalVerified = Math.addExact(totalVerified, cachedSize)
                            if (totalVerified > AUR_TOTAL_SOURCE_MAX_BYTES) {
                                throw SecurityException(
                                    "AUR sources exceed the total download limit",
                                )
                            }
                            retainedAurVerifiedBytes = totalVerified
                            evidence +=
                                AurSourceEvidence(
                                    source.filename,
                                    cachedSize,
                                    initialEndpoint,
                                    true,
                                    sha256VerifiedAurSource(
                                        activeHandle,
                                        sourceIndex,
                                        cachedSize,
                                    ),
                                )
                            searchStatus =
                                "Verified cached source ${remoteIndex + 1}/" +
                                    "${remoteSources.size}: ${source.filename}"
                            return@forEachIndexed
                        }
                        val connection = openAurSourceConnection(initialEndpoint)
                        try {
                            val declaredLength = connection.contentLengthLong
                            if (
                                declaredLength == 0L ||
                                declaredLength > NativeRuntime.AUR_SOURCE_MAX_SIZE
                            ) {
                                throw SecurityException("AUR source has an invalid size")
                            }
                            val remainingTotal =
                                AUR_TOTAL_SOURCE_MAX_BYTES - totalVerified
                            if (remainingTotal <= 0L || declaredLength > remainingTotal) {
                                throw SecurityException(
                                    "AUR sources exceed the total download limit",
                                )
                            }
                            val maximumSize =
                                if (declaredLength > 0L) {
                                    declaredLength
                                } else {
                                    minOf(
                                        NativeRuntime.AUR_SOURCE_MAX_SIZE,
                                        remainingTotal,
                                    )
                                }
                            if (
                                declaredLength > 0L &&
                                declaredLength + AUR_STORAGE_RESERVE_BYTES > filesDir.usableSpace
                            ) {
                                throw IllegalStateException(
                                    "Not enough private storage for ${source.filename}",
                                )
                            }
                            val (verified, verifiedSha256) =
                                downloadAndVerifyAurSource(
                                    activeHandle,
                                    sourceIndex,
                                    source,
                                    connection,
                                    maximumSize,
                                    declaredLength,
                                    remoteIndex + 1,
                                    remoteSources.size,
                                )
                            totalVerified = Math.addExact(totalVerified, verified)
                            if (totalVerified > AUR_TOTAL_SOURCE_MAX_BYTES) {
                                throw SecurityException(
                                    "AUR sources exceed the total download limit",
                                )
                            }
                            retainedAurVerifiedBytes = totalVerified
                            evidence +=
                                AurSourceEvidence(
                                    source.filename,
                                    verified,
                                    connection.url.toString(),
                                    false,
                                    verifiedSha256,
                                )
                        } finally {
                            if (activePackageConnection === connection) {
                                activePackageConnection = null
                            }
                            connection.disconnect()
                        }
                    }
                    retainedAurSourceEvidence = evidence.toTypedArray()
                    val buildEnvironment =
                        downloadAndVerifyAurBuildEnvironment(
                            activeHandle,
                            resolveAurBuildEnvironment(activeHandle),
                        )
                    val builder =
                        probeAurBuilderCompanion(
                            activeHandle,
                            review,
                            retainedAurSourceEvidence,
                            buildEnvironment,
                        )
                    retainedAurBuilderReport = builder
                    publishAurReviewPresentation(
                        review,
                        totalVerified,
                        retainedAurSourceEvidence,
                        builder,
                        buildEnvironment,
                    )
                    searchStatus =
                        "Verified ${remoteSources.size} source(s) · " +
                            "${formatStorageBytes(totalVerified)} · " +
                            if (builder == null) {
                                "builder companion unavailable"
                            } else {
                                "ready to build"
                            }
                    Log.i(
                        TAG,
                            "Verified ${remoteSources.size} AUR source(s) for " +
                            "${review.packageName}: $totalVerified bytes; " +
                            "build=${buildEnvironment.packageCount} verified packages/" +
                            "${buildEnvironment.downloadBytes} bytes " +
                            "(cached=${buildEnvironment.cachedPackages} " +
                            "downloaded=${buildEnvironment.downloadedPackages}) " +
                            "manifest=${buildEnvironment.closureManifestSha256}",
                    )
                    if (builder != null) {
                        Log.i(
                            TAG,
                            "AUR builder boundary ready: package=${builder.packageName} " +
                                "uid=${builder.uid} context=${builder.selinuxContext} " +
                                "staged=${builder.stagedBytes} " +
                                "manifest=${builder.inputManifestSha256} " +
                                "closure=${builder.closurePackageCount}/" +
                                "${builder.closureArchiveBytes}+" +
                                "${builder.closureSignatureBytes} " +
                                "${builder.closureManifestSha256} " +
                                "root=${builder.buildRootEntries}/" +
                                "${builder.buildRootBytes} " +
                                "tool=${builder.runtimeVersion.replace('\n', ' ')} " +
                                "recipe=${builder.recipeEntries}/" +
                                "${builder.recipeBytes}+${builder.recipeSourceBytes}",
                        )
                    } else {
                        Log.i(TAG, "AUR builder companion is not installed")
                    }
                } catch (error: Exception) {
                    searchStatus =
                        "AUR source verification failed: " +
                            (error.message ?: error.javaClass.simpleName)
                    Log.e(TAG, "AUR source verification failed", error)
                } finally {
                    activePackageConnection = null
                    searchActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneAurSources",
        ).start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestAurBuild(packageName: String): Boolean {
        val normalized = packageName.trim()
        val review = retainedAurReview
        val builder = retainedAurBuilderReport
        if (
            review == null ||
            builder == null ||
            normalized != review.packageName ||
            readyHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            searchStatus = "Verify the exact reviewed AUR sources before building"
            return false
        }
        searchActive = true
        aurBuildActive = true
        aurBuildCancelable = true
        aurBuildCancellationRequested = false
        publishAurBuildLogs("")
        clearRetainedAurBuiltPackages()
        searchStatus = "Starting isolated offline build for ${review.packageName}"
        Thread(
            {
                try {
                    val result = runAurBuilderBuild(review, builder)
                    clearRetainedAurBuiltPackages()
                    retainedAurBuiltPackages = result
                    val selected =
                        result.singleOrNull { built ->
                            built.packageName == review.packageName
                        } ?: throw IllegalStateException(
                            "Builder did not return exactly one selected package",
                        )
                    retainedAurBuiltPackage = selected
                    publishAurBuiltPresentation(review, selected)
                    lastResolvedPackage = review.packageName
                    lastResolvedRepository = "aur"
                    lastResolvedInstalledVersion =
                        installedPackageVersion(readyHandle, review.packageName)
                    lastResolvedAvailableVersion = review.version
                    primaryActionLabel =
                        if (lastResolvedInstalledVersion.isEmpty()) "Install" else "Update"
                    removeAvailable = lastResolvedInstalledVersion.isNotEmpty()
                    searchStatus =
                        "Built and verified ${review.packageName} ${review.version} · " +
                            "${result.size} split package(s) · " +
                            "${result.first().buildPackageCount} signed build dependencies"
                    Log.i(
                        TAG,
                        "AUR build completed and independently verified for " +
                            "${review.packageName} ${review.version}: " +
                            result.joinToString { built ->
                                "${built.filename} ${built.archiveBytes} bytes ${built.sha256}"
                            },
                    )
                } catch (error: Exception) {
                    searchStatus =
                        if (
                            error is InterruptedException ||
                            aurBuildCancellationRequested
                        ) {
                            "AUR build cancelled"
                        } else {
                            "AUR build failed: ${error.message ?: error.javaClass.simpleName}"
                        }
                    Log.e(TAG, "AUR build failed", error)
                } finally {
                    aurBuildCancelable = false
                    aurBuildActive = false
                    aurBuildCancellationRequested = false
                    searchActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneAurBuild",
        ).start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestAurBuildCancellation(): Boolean {
        if (!aurBuildActive || !aurBuildCancelable) {
            return false
        }
        aurBuildCancellationRequested = true
        aurBuildCancelable = false
        searchStatus = "Cancelling the AUR build"
        return true
    }

    private fun runAurBuilderBuild(
        review: AurReviewData,
        builder: AurBuilderReport,
    ): Array<AurBuiltPackage> {
        check(builder.inputManifestSha256.matches(SHA256_HEX))
        check(builder.closureManifestSha256.matches(SHA256_HEX))
        val activeHandle = readyHandle
        check(activeHandle != 0L)
        val architecture =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64"
                "arm64-v8a" -> "aarch64"
                else -> throw IllegalStateException("Unsupported Android ABI")
            }
        check(
            packageManager.checkSignatures(packageName, builder.packageName) ==
                PackageManager.SIGNATURE_MATCH,
        ) {
            "AUR builder signer changed after source verification"
        }
        val connected = CountDownLatch(1)
        var remote: IBinder? = null
        var disconnected = false
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    remote = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    disconnected = true
                    connected.countDown()
                }
            }
        var bound = false
        var buildStarted = false
        try {
            bound =
                bindService(
                    Intent("org.archphene.action.BIND_BUILDER")
                        .setPackage(builder.packageName),
                    connection,
                    BIND_AUTO_CREATE,
                )
            if (!bound || !connected.await(10, TimeUnit.SECONDS) || disconnected) {
                throw IllegalStateException("Could not bind the verified AUR builder")
            }
            val endpoint =
                remote ?: throw IllegalStateException("AUR builder returned no Binder")
            transactAurBuilder(
                endpoint,
                AUR_BUILDER_TRANSACTION_START_BUILD,
                { request ->
                    request.writeString(review.packageBase)
                    request.writeString(review.version)
                    request.writeString(builder.inputManifestSha256)
                    request.writeString(builder.closureManifestSha256)
                },
            ) {}
            buildStarted = true
            while (true) {
                if (aurBuildCancellationRequested) {
                    transactAurBuilder(
                        endpoint,
                        AUR_BUILDER_TRANSACTION_CANCEL_BUILD,
                        {},
                    ) { reply ->
                        check(reply.readBoolean()) {
                            "AUR builder had no active build to cancel"
                        }
                    }
                    buildStarted = false
                    throw InterruptedException("AUR build cancelled")
                }
                val poll =
                    transactAurBuilder(
                        endpoint,
                        AUR_BUILDER_TRANSACTION_POLL_BUILD,
                        {},
                    ) { reply ->
                        val exitStatus = reply.readInt()
                        val logBytes = reply.createByteArray() ?: ByteArray(0)
                        check(logBytes.size <= 64 * 1024)
                        val raw = String(logBytes, StandardCharsets.UTF_8)
                        val visible =
                            sanitizeCommandOutput(
                                raw.takeLast(AUR_BUILD_VISIBLE_LOG_CHARACTERS),
                            ).trim()
                        AurBuildPoll(exitStatus, visible)
                    }
                if (poll.logs.isNotEmpty()) {
                    publishAurBuildLogs(poll.logs)
                    val phase =
                        poll.logs
                            .lineSequence()
                            .map(String::trim)
                            .lastOrNull(String::isNotEmpty)
                            ?.take(160)
                            .orEmpty()
                    searchStatus =
                        if (phase.isEmpty()) {
                            "Building ${review.packageName}"
                        } else {
                            "Building ${review.packageName} · $phase"
                        }
                }
                if (poll.exitStatus >= 0) {
                    buildStarted = false
                    check(poll.exitStatus == 0) {
                        "makepkg exited ${poll.exitStatus}" +
                            if (poll.logs.isEmpty()) "" else "\n${poll.logs}"
                    }
                    searchStatus =
                        "Verifying ${review.requiredPackages.size} package output(s)"
                    val outputs =
                        ArrayList<AurBuiltPackage>(review.requiredPackages.size)
                    val outputFiles = ArrayList<File>(review.requiredPackages.size)
                    try {
                        review.requiredPackages.forEachIndexed { index, packageName ->
                            val outputFile =
                                File.createTempFile(
                                    ".aur-$packageName-",
                                    ".pkg",
                                    cacheDir,
                                )
                            outputFiles += outputFile
                            val report =
                            ParcelFileDescriptor.open(
                                outputFile,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_TRUNCATE,
                            ).use { destination ->
                                transactAurBuilder(
                                    endpoint,
                                    AUR_BUILDER_TRANSACTION_VERIFY_OUTPUT,
                                    { request ->
                                        request.writeString(review.packageBase)
                                        request.writeString(packageName)
                                        request.writeString(review.version)
                                        request.writeString(architecture)
                                        request.writeString(builder.closureManifestSha256)
                                        request.writeFileDescriptor(destination.fileDescriptor)
                                    },
                                ) { reply ->
                                    AurBuiltPackage(
                                        packageName = packageName,
                                        filename = reply.readString().orEmpty(),
                                        archiveBytes = reply.readLong(),
                                        installedBytes = reply.readLong(),
                                        buildPackageCount = reply.readInt(),
                                        sha256 = reply.readString().orEmpty(),
                                        file = outputFile,
                                        logs = poll.logs,
                                    )
                                }.also { report ->
                                    check(
                                        report.filename.matches(AUR_BUILT_PACKAGE_FILENAME) &&
                                            report.archiveBytes > 0L &&
                                            report.installedBytes > 0L &&
                                            report.buildPackageCount == builder.closurePackageCount &&
                                            report.sha256.matches(SHA256_HEX) &&
                                            outputFile.length() == report.archiveBytes,
                                    ) {
                                        "Builder returned an invalid package-output report"
                                    }
                                    verifyManagerOwnedAurPackage(
                                        activeHandle,
                                        review,
                                        packageName,
                                        architecture,
                                        builder.closureManifestSha256,
                                        destination,
                                        report,
                                    )
                                }
                            }
                            outputs += report
                            searchStatus =
                                "Verified package output ${index + 1}/" +
                                    "${review.requiredPackages.size}: $packageName"
                        }
                        check(
                            outputs.map(AurBuiltPackage::packageName)
                                .toTypedArray()
                                .contentEquals(review.requiredPackages),
                        )
                        return outputs.toTypedArray()
                    } catch (error: Exception) {
                        outputFiles.forEach(File::delete)
                        throw error
                    }
                }
                Thread.sleep(AUR_BUILD_POLL_MILLIS)
            }
        } finally {
            if (buildStarted) {
                remote?.let { endpoint ->
                    runCatching {
                        transactAurBuilder(
                            endpoint,
                            AUR_BUILDER_TRANSACTION_CANCEL_BUILD,
                            {},
                        ) {}
                    }
                }
            }
            if (bound) {
                unbindService(connection)
            }
        }
    }

    private fun verifyManagerOwnedAurPackage(
        activeHandle: Long,
        review: AurReviewData,
        packageName: String,
        architecture: String,
        closureSha256: String,
        descriptor: ParcelFileDescriptor,
        builderReport: AurBuiltPackage,
    ) {
        val output =
            ByteBuffer
                .allocateDirect(NativeRuntime.BUILT_PACKAGE_REPORT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
        val result =
            NativeRuntime.nativeVerifyAurBuiltPackage(
                activeHandle,
                descriptor.fd,
                builderReport.filename,
                review.packageBase,
                packageName,
                review.version,
                architecture,
                closureSha256,
                output,
            )
        check(result == NativeRuntime.BUILT_PACKAGE_REPORT_SIZE) {
            "Manager rejected Builder output: ${readNativeMessage(output, result)}"
        }
        val magic = ByteArray(8)
        output.position(0)
        output.get(magic)
        check(String(magic, StandardCharsets.US_ASCII) == "ABMV0001")
        val archiveBytes = output.getLong(8)
        val installedBytes = output.getLong(16)
        val buildPackageCount = output.getInt(24)
        val digest = ByteArray(32)
        output.position(32)
        output.get(digest)
        check(
            archiveBytes == builderReport.archiveBytes &&
                installedBytes == builderReport.installedBytes &&
                buildPackageCount == builderReport.buildPackageCount &&
                hexSha256(digest) == builderReport.sha256,
        ) {
            "Manager package verification disagrees with Builder"
        }
    }

    private fun openAurSourceConnection(initialEndpoint: String): HttpsURLConnection {
        var endpoint = validateAurSourceEndpoint(initialEndpoint)
        val visited = LinkedHashSet<String>(AUR_REDIRECT_LIMIT + 1)
        repeat(AUR_REDIRECT_LIMIT + 1) {
            val exactEndpoint = endpoint.toString()
            if (!visited.add(exactEndpoint)) {
                throw SecurityException("AUR source redirect loop")
            }
            val connection = endpoint.openConnection() as HttpsURLConnection
            activePackageConnection = connection
            val status =
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 120_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.responseCode
                } catch (error: Exception) {
                    connection.disconnect()
                    if (activePackageConnection === connection) {
                        activePackageConnection = null
                    }
                    throw error
                }
            when (status) {
                HttpsURLConnection.HTTP_OK -> {
                    val encoding = connection.contentEncoding
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                        connection.disconnect()
                        throw SecurityException("AUR source used unexpected content encoding")
                    }
                    return connection
                }
                HttpsURLConnection.HTTP_MOVED_PERM,
                HttpsURLConnection.HTTP_MOVED_TEMP,
                HttpsURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val location = connection.getHeaderField("Location")
                    if (location == null) {
                        connection.disconnect()
                        if (activePackageConnection === connection) {
                            activePackageConnection = null
                        }
                        throw SecurityException("AUR source redirect has no location")
                    }
                    val next =
                        try {
                            validateAurSourceEndpoint(URL(endpoint, location).toString())
                        } catch (error: Exception) {
                            connection.disconnect()
                            if (activePackageConnection === connection) {
                                activePackageConnection = null
                            }
                            throw error
                        }
                    connection.disconnect()
                    if (activePackageConnection === connection) {
                        activePackageConnection = null
                    }
                    endpoint = next
                }
                else -> {
                    connection.disconnect()
                    throw IllegalStateException("AUR source server returned HTTP $status")
                }
            }
        }
        throw SecurityException("AUR source exceeded the redirect limit")
    }

    private fun validateAurSourceEndpoint(value: String): URL {
        require(
            value.isNotEmpty() &&
                value.length <= 4 * 1024 &&
                value.all { character ->
                    character.code in 0x21..0x7e && character != '\\'
                },
        )
        val endpoint = URL(value)
        require(
            endpoint.toString() == value &&
                endpoint.protocol == "https" &&
                endpoint.host.isNotEmpty() &&
                endpoint.userInfo == null &&
                (endpoint.port == -1 || endpoint.port == 443) &&
                endpoint.ref == null,
        )
        return endpoint
    }

    private fun downloadAndVerifyAurSource(
        activeHandle: Long,
        sourceIndex: Int,
        source: AurSourceReview,
        connection: HttpsURLConnection,
        maximumSize: Long,
        declaredLength: Long,
        ordinal: Int,
        totalSources: Int,
    ): Pair<Long, String> {
        aurEndpointBuffer.clear()
        val descriptor =
            NativeRuntime.nativeBeginAurSourceDownload(
                activeHandle,
                sourceIndex,
                maximumSize,
                aurEndpointBuffer,
            )
        if (descriptor < 0) {
            throw IllegalStateException(readNativeMessage(aurEndpointBuffer, descriptor))
        }
        var finishAttempted = false
        val parcelDescriptor = ParcelFileDescriptor.adoptFd(descriptor)
        try {
            val nativePlan = aurEndpointBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            nativePlan.position(0)
            val endpointLength = nativePlan.int
            val filenameLength = nativePlan.int
            require(
                endpointLength in 1..(4 * 1024) &&
                    filenameLength in 1..240 &&
                    endpointLength + filenameLength <= nativePlan.remaining(),
            )
            val endpointBytes = ByteArray(endpointLength)
            val filenameBytes = ByteArray(filenameLength)
            nativePlan.get(endpointBytes)
            nativePlan.get(filenameBytes)
            val nativeEndpoint = String(endpointBytes, StandardCharsets.US_ASCII)
            val nativeFilename = String(filenameBytes, StandardCharsets.US_ASCII)
            require(nativeEndpoint == source.remoteUrl && nativeFilename == source.filename)
            var total = 0L
            var nextProgress = 1024L * 1024
            val sha256 = MessageDigest.getInstance("SHA-256")
            ParcelFileDescriptor.AutoCloseOutputStream(parcelDescriptor).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("AUR source download interrupted")
                        }
                        val count = input.read(aurTransferBuffer)
                        if (count < 0) {
                            break
                        }
                        total = Math.addExact(total, count.toLong())
                        if (total > maximumSize) {
                            throw SecurityException("AUR source exceeds its size limit")
                        }
                        output.write(aurTransferBuffer, 0, count)
                        sha256.update(aurTransferBuffer, 0, count)
                        if (total >= nextProgress) {
                            searchStatus =
                                "Downloading source $ordinal/$totalSources: " +
                                    "${source.filename} · ${formatStorageBytes(total)}"
                            nextProgress = total + 1024L * 1024
                        }
                    }
                    output.flush()
                }
            }
            if (total == 0L || declaredLength >= 0L && total != declaredLength) {
                throw SecurityException("AUR source is empty or truncated")
            }
            aurEndpointBuffer.clear()
            val verified =
                NativeRuntime.nativeFinishAurSourceDownload(
                    activeHandle,
                    true,
                    aurEndpointBuffer,
                )
            finishAttempted = true
            if (verified < 0L) {
                throw SecurityException(
                    readNativeMessage(aurEndpointBuffer, verified.toInt()),
                )
            }
            require(verified == total)
            return verified to hexSha256(sha256.digest())
        } finally {
            try {
                parcelDescriptor.close()
            } catch (_: Exception) {
            }
            if (!finishAttempted) {
                aurEndpointBuffer.clear()
                NativeRuntime.nativeFinishAurSourceDownload(
                    activeHandle,
                    false,
                    aurEndpointBuffer,
                )
            }
        }
    }

    private fun sha256VerifiedAurSource(
        activeHandle: Long,
        sourceIndex: Int,
        expectedBytes: Long,
    ): String {
        aurEndpointBuffer.clear()
        val descriptor =
            NativeRuntime.nativeOpenVerifiedAurSource(
                activeHandle,
                sourceIndex,
                aurEndpointBuffer,
            )
        if (descriptor < 0) {
            throw SecurityException(readNativeMessage(aurEndpointBuffer, descriptor))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.adoptFd(descriptor),
        ).use { input ->
            while (true) {
                val count = input.read(aurTransferBuffer)
                if (count < 0) {
                    break
                }
                total = Math.addExact(total, count.toLong())
                if (total > expectedBytes) {
                    throw SecurityException("Verified AUR source changed while hashing")
                }
                digest.update(aurTransferBuffer, 0, count)
            }
        }
        require(total == expectedBytes)
        return hexSha256(digest.digest())
    }

    private fun probeAurBuilderCompanion(
        activeHandle: Long,
        review: AurReviewData,
        sourceEvidence: Array<AurSourceEvidence>,
        buildEnvironment: AurBuildEnvironment,
    ): AurBuilderReport? {
        val builderPackage =
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "org.archphene.builder.debug"
            } else {
                "org.archphene.builder"
            }
        val builderInfo =
            try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    builderPackage,
                    PackageManager.GET_PERMISSIONS,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
        check(
            packageManager.checkSignatures(packageName, builderPackage) ==
                PackageManager.SIGNATURE_MATCH,
        ) {
            "Installed AUR builder signer does not match the manager"
        }
        check(
            builderInfo.requestedPermissions?.none { permission ->
                permission == android.Manifest.permission.INTERNET
            } != false,
        ) {
            "AUR builder must not request network permission"
        }
        val builderUid =
            builderInfo.applicationInfo?.uid
                ?: throw IllegalStateException("AUR builder has no application UID")
        check(builderUid != Process.myUid())

        val sentinel = File(filesDir, "aur-builder-manager-sentinel")
        val outputFile = File(cacheDir, "aur-builder-probe-output")
        sentinel.writeText("manager-private\n", StandardCharsets.US_ASCII)
        outputFile.writeBytes(ByteArray(0))
        val outputDescriptor =
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_READ_WRITE,
            )
        val buildInputs = ArrayList<AurBuilderInput>(sourceEvidence.size + 1)
        try {
            aurEndpointBuffer.clear()
            val snapshotFd =
                NativeRuntime.nativeOpenReviewedAurSnapshot(
                    activeHandle,
                    aurEndpointBuffer,
                )
            if (snapshotFd < 0) {
                throw IllegalStateException(readNativeMessage(aurEndpointBuffer, snapshotFd))
            }
            val snapshotDescriptor = ParcelFileDescriptor.adoptFd(snapshotFd)
            val snapshotBytes = Os.fstat(snapshotDescriptor.fileDescriptor).st_size
            buildInputs +=
                AurBuilderInput(
                    AUR_BUILDER_INPUT_SNAPSHOT,
                    "${review.packageBase}.tar.gz",
                    review.snapshotSha256,
                    snapshotBytes,
                    snapshotDescriptor,
                )
            var evidenceIndex = 0
            review.sources.forEachIndexed { sourceIndex, source ->
                if (source.local) {
                    return@forEachIndexed
                }
                val evidence =
                    sourceEvidence.getOrNull(evidenceIndex++)
                        ?: throw IllegalStateException("Missing verified AUR source evidence")
                require(
                    evidence.filename == source.filename &&
                        source.checksum != null &&
                        evidence.sha256.matches(SHA256_HEX),
                )
                aurEndpointBuffer.clear()
                val sourceFd =
                    NativeRuntime.nativeOpenVerifiedAurSource(
                        activeHandle,
                        sourceIndex,
                        aurEndpointBuffer,
                    )
                if (sourceFd < 0) {
                    throw IllegalStateException(readNativeMessage(aurEndpointBuffer, sourceFd))
                }
                buildInputs +=
                    AurBuilderInput(
                        AUR_BUILDER_INPUT_SOURCE,
                        source.filename,
                        evidence.sha256,
                        evidence.bytes,
                        ParcelFileDescriptor.adoptFd(sourceFd),
                    )
            }
            require(evidenceIndex == sourceEvidence.size)
        } catch (error: Exception) {
            buildInputs.forEach { input -> runCatching { input.descriptor.close() } }
            outputDescriptor.close()
            sentinel.delete()
            outputFile.delete()
            throw error
        }
        val connected = CountDownLatch(1)
        var remote: IBinder? = null
        var disconnected = false
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    remote = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    disconnected = true
                    connected.countDown()
                }
            }
        var bound = false
        try {
            bound =
                bindService(
                    Intent("org.archphene.action.BIND_BUILDER")
                        .setPackage(builderPackage),
                    connection,
                    BIND_AUTO_CREATE,
                )
            if (!bound || !connected.await(10, TimeUnit.SECONDS) || disconnected) {
                throw IllegalStateException("Could not bind the AUR builder companion")
            }
            val endpoint =
                remote ?: throw IllegalStateException("AUR builder returned no Binder")
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken("org.archphene.builder.AurBuilder")
                request.writeString(sentinel.absolutePath)
                request.writeFileDescriptor(outputDescriptor.fileDescriptor)
                request.writeString(review.packageBase)
                request.writeString(review.version)
                request.writeInt(buildInputs.size)
                buildInputs.forEach { input ->
                    request.writeInt(input.role)
                    request.writeString(input.filename)
                    request.writeString(input.sha256)
                    request.writeLong(input.bytes)
                    request.writeFileDescriptor(input.descriptor.fileDescriptor)
                }
                if (!endpoint.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0)) {
                    throw IllegalStateException("AUR builder rejected its boundary probe")
                }
                reply.readException()
                val reportedUid = reply.readInt()
                val callingUid = reply.readInt()
                val internetPermission = reply.readBoolean()
                val directManagerDataReadable = reply.readBoolean()
                val privateWorkspaceWritable = reply.readBoolean()
                val outputWriteSucceeded = reply.readBoolean()
                val selinuxContext = reply.readString().orEmpty()
                val stagedBytes = reply.readLong()
                val inputManifestSha256 = reply.readString().orEmpty()
                val output = outputFile.readText(StandardCharsets.US_ASCII)
                val expectedStagedBytes =
                    buildInputs.fold(0L) { total, input ->
                        Math.addExact(total, input.bytes)
                    }
                check(
                    reportedUid == builderUid &&
                        reportedUid != Process.myUid() &&
                        callingUid == Process.myUid() &&
                        !internetPermission &&
                        !directManagerDataReadable &&
                        privateWorkspaceWritable &&
                        outputWriteSucceeded &&
                        output == "builder-output:$reportedUid\n" &&
                        stagedBytes == expectedStagedBytes &&
                        inputManifestSha256.matches(SHA256_HEX) &&
                        selinuxContext.length in 1..256 &&
                        selinuxContext.contains("untrusted_app") &&
                        selinuxContext.none { character ->
                            character.isISOControl()
                        },
                ) {
                    "AUR builder companion failed its storage or permission boundary"
                }
                val closure =
                    stageAurBuildClosure(
                        endpoint,
                        activeHandle,
                        review,
                        buildEnvironment,
                    )
                searchStatus =
                    "Scanning ${buildEnvironment.packageCount} verified packages for " +
                        "the isolated build root"
                val buildRoot =
                    provisionAurBuildRoot(
                        endpoint,
                        review,
                        buildEnvironment,
                    )
                searchStatus = "Validating the isolated Builder runtime"
                val runtimeVersion = probeAurBuilderRuntime(endpoint)
                searchStatus = "Preparing the exact reviewed build recipe"
                val recipe =
                    prepareAurBuilderRecipe(
                        endpoint,
                        review,
                        inputManifestSha256,
                        closure.manifestSha256,
                    )
                return AurBuilderReport(
                    builderPackage,
                    reportedUid,
                    selinuxContext,
                    stagedBytes,
                    inputManifestSha256,
                    closure.packageCount,
                    closure.archiveBytes,
                    closure.signatureBytes,
                    closure.manifestSha256,
                    buildRoot.entryCount,
                    buildRoot.expandedBytes,
                    runtimeVersion,
                    recipe.entryCount,
                    recipe.recipeBytes,
                    recipe.sourceBytes,
                )
            } finally {
                request.recycle()
                reply.recycle()
            }
        } finally {
            outputDescriptor.close()
            buildInputs.forEach { input ->
                runCatching { input.descriptor.close() }
            }
            if (bound) {
                unbindService(connection)
            }
            sentinel.delete()
            outputFile.delete()
        }
    }

    private data class AurBuilderClosureReport(
        val packageCount: Int,
        val archiveBytes: Long,
        val signatureBytes: Long,
        val manifestSha256: String,
    )

    private data class AurBuilderRootReport(
        val packageCount: Int,
        val entryCount: Long,
        val expandedBytes: Long,
    )

    private fun stageAurBuildClosure(
        endpoint: IBinder,
        activeHandle: Long,
        review: AurReviewData,
        environment: AurBuildEnvironment,
    ): AurBuilderClosureReport {
        check(
            environment.verified &&
                environment.packageCount in 1..512 &&
                environment.verifiedPackages.size == environment.packageCount &&
                environment.closureManifest.isNotEmpty() &&
                environment.closureManifest.size <= NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE &&
                environment.closureManifestSha256.matches(SHA256_HEX),
        ) {
            "AUR build closure is not retained and verified"
        }
        var began = false
        try {
            transactAurBuilder(
                endpoint,
                AUR_BUILDER_TRANSACTION_BEGIN_CLOSURE,
                { request ->
                    request.writeString(review.packageBase)
                    request.writeString(review.version)
                    request.writeByteArray(environment.closureManifest)
                    request.writeString(environment.closureManifestSha256)
                },
            ) { reply ->
                val packageCount = reply.readInt()
                check(packageCount == environment.packageCount) {
                    "Builder package-closure count changed"
                }
            }
            began = true

            environment.verifiedPackages.indices
                .chunked(AUR_BUILDER_PACKAGE_BATCH)
                .forEachIndexed { batchIndex, indices ->
                    val descriptors =
                        ArrayList<Pair<ParcelFileDescriptor, ParcelFileDescriptor>>(indices.size)
                    try {
                        indices.forEach { packageIndex ->
                            aurEndpointBuffer.clear()
                            val archive =
                                NativeRuntime.nativeOpenVerifiedAurBuildPackage(
                                    activeHandle,
                                    packageIndex,
                                    false,
                                    aurEndpointBuffer,
                                )
                            if (archive < 0) {
                                throw SecurityException(
                                    readNativeMessage(aurEndpointBuffer, archive),
                                )
                            }
                            val archiveDescriptor = ParcelFileDescriptor.adoptFd(archive)
                            try {
                                aurEndpointBuffer.clear()
                                val signature =
                                    NativeRuntime.nativeOpenVerifiedAurBuildPackage(
                                        activeHandle,
                                        packageIndex,
                                        true,
                                        aurEndpointBuffer,
                                    )
                                if (signature < 0) {
                                    throw SecurityException(
                                        readNativeMessage(aurEndpointBuffer, signature),
                                    )
                                }
                                descriptors +=
                                    archiveDescriptor to ParcelFileDescriptor.adoptFd(signature)
                            } catch (error: Exception) {
                                archiveDescriptor.close()
                                throw error
                            }
                        }
                        transactAurBuilder(
                            endpoint,
                            AUR_BUILDER_TRANSACTION_STAGE_BATCH,
                            { request ->
                                request.writeInt(indices.size)
                                indices.forEachIndexed { offset, packageIndex ->
                                    val (archive, signature) = descriptors[offset]
                                    request.writeInt(packageIndex)
                                    request.writeFileDescriptor(archive.fileDescriptor)
                                    request.writeFileDescriptor(signature.fileDescriptor)
                                }
                            },
                        ) { reply ->
                            check(reply.readInt() == indices.size) {
                                "Builder did not stage the complete package batch"
                            }
                        }
                    } finally {
                        descriptors.forEach { (archive, signature) ->
                            runCatching { archive.close() }
                            runCatching { signature.close() }
                        }
                    }
                    val completed = minOf(
                        environment.packageCount,
                        (batchIndex + 1) * AUR_BUILDER_PACKAGE_BATCH,
                    )
                    searchStatus =
                        "Staging verified build package $completed/" +
                            "${environment.packageCount}"
                }

            val closure =
                transactAurBuilder(
                    endpoint,
                    AUR_BUILDER_TRANSACTION_FINISH_CLOSURE,
                    {},
                ) { reply ->
                    AurBuilderClosureReport(
                        reply.readInt(),
                        reply.readLong(),
                        reply.readLong(),
                        reply.readString().orEmpty(),
                    )
                }
            val expectedSignatureBytes =
                environment.verifiedPackages.fold(0L) { total, value ->
                    Math.addExact(total, value.signatureBytes)
                }
            check(
                closure.packageCount == environment.packageCount &&
                    closure.archiveBytes == environment.downloadBytes &&
                    closure.signatureBytes == expectedSignatureBytes &&
                    closure.manifestSha256 == environment.closureManifestSha256,
            ) {
                "Builder package closure does not match the verified manager closure"
            }
            began = false
            return closure
        } finally {
            if (began) {
                runCatching {
                    transactAurBuilder(
                        endpoint,
                        AUR_BUILDER_TRANSACTION_ABORT_CLOSURE,
                        {},
                    ) { Unit }
                }
            }
        }
    }

    private inline fun <T> transactAurBuilder(
        endpoint: IBinder,
        transaction: Int,
        writeRequest: (Parcel) -> Unit,
        readReply: (Parcel) -> T,
    ): T {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken("org.archphene.builder.AurBuilder")
            writeRequest(request)
            check(endpoint.transact(transaction, request, reply, 0)) {
                "AUR builder rejected transaction $transaction"
            }
            reply.readException()
            return readReply(reply)
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun provisionAurBuildRoot(
        endpoint: IBinder,
        review: AurReviewData,
        environment: AurBuildEnvironment,
    ): AurBuilderRootReport {
        var began = false
        try {
            val expected =
                transactAurBuilder(
                    endpoint,
                    AUR_BUILDER_TRANSACTION_BEGIN_PROVISION,
                    { request ->
                        request.writeString(review.packageBase)
                        request.writeString(review.version)
                        request.writeString(environment.closureManifestSha256)
                    },
                ) { reply -> readAurBuilderRootReport(reply) }
            check(
                expected.packageCount == environment.packageCount &&
                    expected.entryCount > expected.packageCount &&
                    expected.expandedBytes > 0,
            ) {
                "Builder root extraction plan does not match the verified closure"
            }
            began = true
            var extracted = AurBuilderRootReport(0, 0, 0)
            while (extracted.packageCount < expected.packageCount) {
                extracted =
                    transactAurBuilder(
                        endpoint,
                        AUR_BUILDER_TRANSACTION_EXTRACT_PROVISION_BATCH,
                        { request -> request.writeInt(AUR_BUILDER_PACKAGE_BATCH) },
                    ) { reply -> readAurBuilderRootReport(reply) }
                check(
                    extracted.packageCount in 1..expected.packageCount &&
                        extracted.entryCount in 1..expected.entryCount &&
                        extracted.expandedBytes in 0..expected.expandedBytes,
                ) {
                    "Builder root extraction exceeded its verified plan"
                }
                searchStatus =
                    "Provisioning isolated build root ${extracted.packageCount}/" +
                        "${expected.packageCount}"
            }
            val finished =
                transactAurBuilder(
                    endpoint,
                    AUR_BUILDER_TRANSACTION_FINISH_PROVISION,
                    {},
                ) { reply -> readAurBuilderRootReport(reply) }
            check(finished == expected) {
                "Builder root extraction changed between scan and publication"
            }
            began = false
            return finished
        } finally {
            if (began) {
                runCatching {
                    transactAurBuilder(
                        endpoint,
                        AUR_BUILDER_TRANSACTION_ABORT_PROVISION,
                        {},
                    ) { Unit }
                }
            }
        }
    }

    private fun readAurBuilderRootReport(reply: Parcel): AurBuilderRootReport {
        val report =
            AurBuilderRootReport(
                reply.readInt(),
                reply.readLong(),
                reply.readLong(),
            )
        check(
            report.packageCount in 0..512 &&
                report.entryCount >= 0 &&
                report.expandedBytes >= 0,
        )
        return report
    }

    private fun probeAurBuilderRuntime(endpoint: IBinder): String =
        transactAurBuilder(
            endpoint,
            AUR_BUILDER_TRANSACTION_PROBE_RUNTIME,
            {},
        ) { reply ->
            val version = reply.readString().orEmpty().trim()
            check(
                version.length in 1..16 * 1024 &&
                    version.contains("makepkg", ignoreCase = true) &&
                    version.none { character ->
                        character == '\u0000' ||
                            character.isISOControl() && character !in "\n\r\t"
                    },
            ) {
                "Builder returned an invalid makepkg probe"
            }
            version
        }

    private fun prepareAurBuilderRecipe(
        endpoint: IBinder,
        review: AurReviewData,
        inputManifestSha256: String,
        closureSha256: String,
    ): AurRecipeWorkspace =
        transactAurBuilder(
            endpoint,
            AUR_BUILDER_TRANSACTION_PREPARE_RECIPE,
            { request ->
                request.writeString(review.packageBase)
                request.writeString(review.version)
                request.writeString(inputManifestSha256)
                request.writeString(closureSha256)
            },
        ) { reply ->
            val report =
                AurRecipeWorkspace(
                    reply.readLong(),
                    reply.readLong(),
                    reply.readLong(),
                )
            check(
                report.entryCount > 0 &&
                    report.recipeBytes > 0 &&
                    report.sourceBytes >= 0,
            ) {
                "Builder returned an invalid reviewed recipe workspace"
            }
            report
        }

    private fun resolveAurBuildEnvironment(activeHandle: Long): AurBuildEnvironment {
        val bytes =
            synchronized(packageResolutionOutputBuffer) {
                packageResolutionOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeResolveAurBuildEnvironment(
                        activeHandle,
                        packageResolutionOutputBuffer,
                    )
                if (outputLength <= 0) {
                    throw IllegalStateException(
                        readNativeMessage(packageResolutionOutputBuffer, outputLength),
                    )
                }
                ByteArray(outputLength).also { output ->
                    packageResolutionOutputBuffer.position(0)
                    packageResolutionOutputBuffer.get(output)
                }
            }
        val packages = decodeResolvedPayloads(bytes, 512)
        require(packages.any { payload -> payload.name == "base-devel" })
        val totalBytes =
            packages.fold(0L) { total, payload ->
                Math.addExact(total, payload.size)
            }
        return AurBuildEnvironment(packages, bytes, totalBytes)
    }

    private fun downloadAndVerifyAurBuildEnvironment(
        activeHandle: Long,
        environment: AurBuildEnvironment,
    ): AurBuildEnvironment {
        val scratch = PackageIoScratch()
        var cachedPackages = 0
        var downloadedPackages = 0
        environment.packages.forEachIndexed { index, payload ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("AUR build-environment verification interrupted")
            }
            searchStatus =
                "Verifying official build package ${index + 1}/${environment.packageCount}: " +
                    payload.name
            if (isCachedPackageValid(activeHandle, payload, scratch)) {
                cachedPackages += 1
                return@forEachIndexed
            }
            if (payload.size + AUR_STORAGE_RESERVE_BYTES > filesDir.usableSpace) {
                throw IllegalStateException(
                    "Not enough private storage for build package ${payload.name}",
                )
            }
            searchStatus =
                "Downloading official build package ${index + 1}/${environment.packageCount}: " +
                    payload.name
            downloadPackagePayload(activeHandle, payload, false, scratch)
            downloadPackagePayload(activeHandle, payload, true, scratch)
            verifyPackagePayload(activeHandle, payload, scratch)
            downloadedPackages += 1
        }
        val verifiedBytes =
            synchronized(packageResolutionOutputBuffer) {
                packageResolutionOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeVerifyAurBuildEnvironment(
                        activeHandle,
                        packageResolutionOutputBuffer,
                    )
                if (outputLength <= 0) {
                    throw SecurityException(
                        readNativeMessage(packageResolutionOutputBuffer, outputLength),
                    )
                }
                ByteArray(outputLength).also { output ->
                    packageResolutionOutputBuffer.position(0)
                    packageResolutionOutputBuffer.get(output)
                }
            }
        if (!verifiedBytes.contentEquals(environment.resolutionBytes)) {
            throw SecurityException("Verified build environment changed after resolution")
        }
        val verifiedPackages = decodeResolvedPayloads(verifiedBytes, 512)
        if (
            verifiedPackages.size != environment.packageCount ||
            verifiedPackages.fold(0L) { total, payload ->
                Math.addExact(total, payload.size)
            } != environment.downloadBytes
        ) {
            throw SecurityException("Verified build environment does not match its plan")
        }
        val closureManifest =
            synchronized(aurBuildClosureOutputBuffer) {
                aurBuildClosureOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeReadVerifiedAurBuildClosure(
                        activeHandle,
                        aurBuildClosureOutputBuffer,
                    )
                if (outputLength <= 0) {
                    throw SecurityException(
                        readNativeMessage(aurBuildClosureOutputBuffer, outputLength),
                    )
                }
                ByteArray(outputLength).also { output ->
                    aurBuildClosureOutputBuffer.position(0)
                    aurBuildClosureOutputBuffer.get(output)
                }
            }
        val closurePackages =
            decodeVerifiedBuildClosure(closureManifest, environment.packages)
        val closureManifestSha256 =
            hexSha256(MessageDigest.getInstance("SHA-256").digest(closureManifest))
        return environment.copy(
            verifiedPackages = closurePackages,
            closureManifest = closureManifest,
            closureManifestSha256 = closureManifestSha256,
            cachedPackages = cachedPackages,
            downloadedPackages = downloadedPackages,
            verified = true,
        )
    }

    private fun decodeVerifiedBuildClosure(
        manifest: ByteArray,
        resolvedPackages: List<ResolvedPayload>,
    ): List<VerifiedBuildPackage> {
        require(manifest.isNotEmpty() && manifest.size <= NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE)
        val lines = String(manifest, StandardCharsets.US_ASCII).lines()
        require(lines.firstOrNull() == "ABPC0001")
        val packageLines = lines.drop(1).filter { line -> line.isNotEmpty() }
        require(packageLines.size == resolvedPackages.size + 1)
        val summary = packageLines.last().split('\t')
        require(
            summary.size == 3 &&
                summary[0] == "summary" &&
                summary[1].toIntOrNull() == resolvedPackages.size &&
                summary[2].toLongOrNull() ==
                resolvedPackages.fold(0L) { total, payload ->
                    Math.addExact(total, payload.size)
                },
        )
        val packages = ArrayList<VerifiedBuildPackage>(resolvedPackages.size)
        packageLines.dropLast(1).forEachIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 9)
            val archiveBytes = fields[5].toLongOrNull() ?: error("Invalid archive size")
            val signatureBytes = fields[7].toLongOrNull() ?: error("Invalid signature size")
            val resolved = resolvedPackages[index]
            require(
                fields[0] == resolved.repository &&
                    fields[1] == resolved.name &&
                    fields[2] == resolved.version &&
                    fields[3] == resolved.filename &&
                    fields[4] == resolved.url &&
                    archiveBytes == resolved.size &&
                    fields[6].matches(SHA256_HEX) &&
                    signatureBytes in 1..1024L * 1024 &&
                    fields[8].matches(SHA256_HEX),
            )
            packages +=
                VerifiedBuildPackage(
                    fields[0],
                    fields[1],
                    fields[2],
                    fields[3],
                    fields[4],
                    archiveBytes,
                    fields[6],
                    signatureBytes,
                    fields[8],
                )
        }
        return packages
    }

    private fun parseAurReview(
        source: ByteBuffer,
        length: Int,
    ): AurReviewData {
        require(length in 1..NativeRuntime.AUR_REVIEW_SIZE)
        val reader = AurWireReader(source, length)
        require(reader.bytes(8).contentEquals("ARVW0004".toByteArray(StandardCharsets.US_ASCII)))
        reader.bytes(32)
        val snapshotSha256Bytes = reader.bytes(32)
        require(snapshotSha256Bytes.any { byte -> byte != 0.toByte() })
        val lastModified = reader.long()
        require(lastModified > 0)
        val flags = reader.int()
        require(flags and 0x1f.inv() == 0)
        val licenseCount = boundedAurCount(reader.int(), 32)
        val dependencyCount = boundedAurCount(reader.int(), 256)
        val requiredPackageCount = boundedAurCount(reader.int(), 256)
        val makeDependencyCount = boundedAurCount(reader.int(), 256)
        val checkDependencyCount = boundedAurCount(reader.int(), 256)
        val sourceCount = boundedAurCount(reader.int(), 64)
        val pgpKeyCount = boundedAurCount(reader.int(), 32)
        val buildStepCount = boundedAurCount(reader.int(), 4)
        val packageBase = reader.string(128)
        val packageName = reader.string(128)
        val version = reader.string(128)
        val description = reader.string(2 * 1024, allowEmpty = true)
        val maintainer = reader.string(128, allowEmpty = true)
        val projectUrl = reader.string(4 * 1024, allowEmpty = true)
        val snapshotPath = reader.string(4 * 1024)
        val snapshotCommit = reader.string(64)
        val installScript = reader.string(4 * 1024, allowEmpty = true)
        val pkgbuild =
            decodeAurScript(reader.blob(256 * 1024), allowEmpty = false)
        val installScriptContents =
            decodeAurScript(reader.blob(512 * 1024), allowEmpty = true)
        require(packageBase.matches(AUR_PACKAGE_NAME))
        require(packageName.matches(AUR_PACKAGE_NAME))
        require(version.none(Char::isWhitespace))
        require(
            snapshotCommit.length == 40 &&
                snapshotCommit.all { character ->
                    character in '0'..'9' ||
                        character in 'a'..'f' ||
                        character in 'A'..'F'
                },
        )
        require(
            snapshotPath == "/cgit/aur.git/snapshot/$packageBase.tar.gz",
        )
        require((flags and (1 shl 1) != 0) == maintainer.isEmpty())
        require((flags and (1 shl 2) != 0) == installScript.isNotEmpty())
        require(installScript.isEmpty() == installScriptContents.isEmpty())

        fun strings(
            count: Int,
            maximumBytes: Int,
        ): Array<String> =
            Array(count) {
                reader.string(maximumBytes)
            }

        val licenses = strings(licenseCount, 4 * 1024)
        val dependencies = strings(dependencyCount, 4 * 1024)
        val requiredPackages = strings(requiredPackageCount, 128)
        require(
            requiredPackages.isNotEmpty() &&
                requiredPackages.contentEquals(requiredPackages.sortedArray()) &&
                requiredPackages.toSet().size == requiredPackages.size &&
                requiredPackages.all(AUR_PACKAGE_NAME::matches) &&
                packageName in requiredPackages,
        )
        val makeDependencies = strings(makeDependencyCount, 4 * 1024)
        val checkDependencies = strings(checkDependencyCount, 4 * 1024)
        val sources =
            Array(sourceCount) {
                val architecture = reader.string(16, allowEmpty = true)
                require(
                    architecture.isEmpty() ||
                        architecture == "x86_64" ||
                        architecture == "aarch64",
                )
                val expression = reader.string(4 * 1024)
                val filename = reader.string(240)
                require(filename.matches(AUR_SOURCE_FILENAME))
                val kind = reader.byte()
                require(kind in 1..3)
                val remoteUrl = reader.string(4 * 1024, allowEmpty = true)
                require((kind == 2) == remoteUrl.isNotEmpty())
                if (remoteUrl.isNotEmpty()) {
                    validateAurSourceEndpoint(remoteUrl)
                }
                val checksumKind = reader.byte()
                require(checksumKind in 0..2)
                val checksumAlgorithm =
                    when (checksumKind) {
                        1 -> "SHA-256"
                        2 -> "SHA-512"
                        else -> null
                    }
                val checksum =
                    when (checksumKind) {
                        1 -> hexSha256(reader.bytes(32))
                        2 -> hexBytes(reader.bytes(64))
                        else -> null
                    }
                val insecure = reader.byte()
                require(insecure in 0..1)
                AurSourceReview(
                    architecture,
                    expression,
                    filename,
                    remoteUrl.ifEmpty { null },
                    kind == 1,
                    checksumAlgorithm,
                    checksum,
                    insecure == 1,
                )
            }
        val validPgpKeys = strings(pgpKeyCount, 4 * 1024)
        val seenSteps = BooleanArray(5)
        val buildSteps =
            Array(buildStepCount) {
                val code = reader.byte()
                require(code in 1..4 && !seenSteps[code])
                seenSteps[code] = true
                when (code) {
                    1 -> "prepare"
                    2 -> "build"
                    3 -> "check"
                    else -> "package"
                }
            }
        require(reader.exhausted())
        val unverifiedSources = sources.any { sourceReview -> sourceReview.checksum == null }
        val insecureSources = sources.any { sourceReview -> sourceReview.insecureTransport }
        require((flags and (1 shl 3) != 0) == unverifiedSources)
        require((flags and (1 shl 4) != 0) == insecureSources)
        return AurReviewData(
            packageBase,
            packageName,
            version,
            description,
            maintainer,
            projectUrl,
            snapshotPath,
            snapshotCommit.lowercase(),
            hexSha256(snapshotSha256Bytes),
            lastModified,
            flags and 1 != 0,
            licenses,
            dependencies,
            requiredPackages,
            makeDependencies,
            checkDependencies,
            sources,
            validPgpKeys,
            buildSteps,
            installScript,
            pkgbuild,
            installScriptContents,
            unverifiedSources,
            insecureSources,
        )
    }

    @Synchronized
    private fun clearAurReviewPresentation() {
        retainedAurBuildLogs = ""
        val revision = aurReviewSnapshot.revision + 1
        aurReviewSnapshot =
            AurReviewSnapshot(
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                revision,
            )
    }

    @Synchronized
    private fun publishAurReviewPresentation(
        review: AurReviewData,
        verifiedSourceBytes: Long = retainedAurVerifiedBytes,
        sourceEvidence: Array<AurSourceEvidence> = retainedAurSourceEvidence,
        builder: AurBuilderReport? = retainedAurBuilderReport,
        buildEnvironment: AurBuildEnvironment? = null,
        built: AurBuiltPackage? = retainedAurBuiltPackage,
    ) {
        val previousRevision = aurReviewSnapshot.revision
        aurReviewSnapshot =
            formatAurReviewPresentation(
                review,
                verifiedSourceBytes,
                sourceEvidence,
                builder,
                buildEnvironment,
                built,
                retainedAurBuildLogs,
                previousRevision + 1,
            )
    }

    @Synchronized
    private fun publishAurBuildLogs(logs: String) {
        if (logs == retainedAurBuildLogs) {
            return
        }
        retainedAurBuildLogs = logs
        val current = aurReviewSnapshot
        if (current.packageName.isEmpty()) {
            return
        }
        aurReviewSnapshot =
            AurReviewSnapshot(
                current.packageName,
                current.summary,
                current.sources,
                current.trust,
                current.buildEnvironment,
                current.digests,
                current.recipe,
                logs,
                current.revision + 1,
            )
    }

    @Synchronized
    private fun publishAurBuiltPresentation(
        review: AurReviewData,
        built: AurBuiltPackage,
    ) {
        retainedAurBuildLogs = built.logs
        val current = aurReviewSnapshot
        if (current.packageName != review.packageName) {
            publishAurReviewPresentation(review, built = built)
            return
        }
        val buildEnvironment =
            current.buildEnvironment +
                "\nVerified package: ${built.filename} · " +
                "${formatStorageBytes(built.archiveBytes)} archive · " +
                "${formatStorageBytes(built.installedBytes)} installed"
        val digests =
            current.digests +
                "\nBuilt package SHA-256: ${built.sha256}"
        aurReviewSnapshot =
            AurReviewSnapshot(
                current.packageName,
                current.summary,
                current.sources,
                current.trust,
                buildEnvironment,
                digests,
                current.recipe,
                built.logs,
                current.revision + 1,
            )
    }

    private fun formatAurReviewPresentation(
        review: AurReviewData,
        verifiedSourceBytes: Long,
        sourceEvidence: Array<AurSourceEvidence>,
        builder: AurBuilderReport?,
        buildEnvironment: AurBuildEnvironment?,
        built: AurBuiltPackage?,
        logs: String,
        revision: Int,
    ): AurReviewSnapshot {
        val summary =
            buildString(768) {
                append(review.packageName).append(' ').append(review.version).append('\n')
                if (review.description.isNotEmpty()) {
                    append(review.description).append('\n')
                }
                append("Community AUR package")
                if (review.outOfDate) {
                    append(" · flagged out of date")
                }
                append(" · review evidence before continuing")
            }
        val sources =
            buildString(4096) {
                if (review.licenses.isNotEmpty()) {
                    append("Licenses\n")
                    review.licenses.forEach { value -> append("• ").append(value).append('\n') }
                    append('\n')
                }
                review.sources.forEach { source ->
                    append("• ")
                    if (source.architecture.isNotEmpty()) {
                        append('[').append(source.architecture).append("] ")
                    }
                    append(source.expression).append('\n')
                    append("  File: ").append(source.filename).append('\n')
                    append("  Origin: ")
                        .append(
                            when {
                                source.local -> "included in AUR snapshot"
                                source.remoteUrl != null -> source.remoteUrl
                                else -> "unsupported source transport"
                            },
                        )
                        .append('\n')
                    if (source.insecureTransport) {
                        append("  Warning: insecure transport\n")
                    }
                }
            }.trimEnd()
        val trust =
            buildString(2048) {
                append("Community PKGBUILD; not an official signed Arch package.\n")
                append(
                    "If installed, it joins the shared Archphene Linux environment and can " +
                        "access its files and toolchain.\n",
                )
                append("Package base: ").append(review.packageBase).append('\n')
                append("Maintainer: ")
                    .append(review.maintainer.ifEmpty { "Orphaned" })
                    .append('\n')
                if (review.projectUrl.isNotEmpty()) {
                    append("Project: ").append(review.projectUrl).append('\n')
                }
                append("AUR snapshot: ").append(review.snapshotPath).append('\n')
                append("Unverified sources: ")
                    .append(if (review.unverifiedSources) "yes" else "none")
                    .append('\n')
                append("Insecure source transports: ")
                    .append(if (review.insecureSources) "yes" else "none")
                    .append('\n')
                append("Android permissions: none requested at this review stage.")
            }
        val buildEnvironmentText =
            buildString(4096) {
                if (verifiedSourceBytes > 0L) {
                    append("Verified source downloads: ")
                        .append(formatStorageBytes(verifiedSourceBytes))
                        .append('\n')
                    sourceEvidence.forEach { evidence ->
                        append("• ")
                            .append(evidence.filename)
                            .append(": ")
                            .append(formatStorageBytes(evidence.bytes))
                            .append(if (evidence.cached) " · cached\n" else "\n")
                            .append("  HTTPS endpoint: ")
                            .append(evidence.endpoint)
                            .append('\n')
                    }
                } else {
                    append("Source download size: verify sources to measure.\n")
                }
                if (buildEnvironment == null) {
                    append("Official build environment: verify sources to resolve.\n")
                } else {
                    append(
                        if (buildEnvironment.verified) {
                            "Verified official build environment: "
                        } else {
                            "Official build environment plan: "
                        },
                    )
                        .append(buildEnvironment.packageCount)
                        .append(" packages · ")
                        .append(formatStorageBytes(buildEnvironment.downloadBytes))
                        .append(" archives")
                    if (buildEnvironment.verified) {
                        append(" · ")
                            .append(buildEnvironment.cachedPackages)
                            .append(" cached · ")
                            .append(buildEnvironment.downloadedPackages)
                            .append(" downloaded")
                    }
                    append('\n')
                }
                if (builder == null) {
                    append("Build sandbox: signed companion not ready.\n")
                } else {
                    append("Build sandbox: signed companion UID ")
                        .append(builder.uid)
                        .append("; no network permission or direct manager-data access.\n")
                    append("Reviewed inputs: ")
                        .append(formatStorageBytes(builder.stagedBytes))
                        .append('\n')
                    append("Signed build packages: ")
                        .append(builder.closurePackageCount)
                        .append(" · ")
                        .append(formatStorageBytes(builder.closureArchiveBytes))
                        .append(" archives\n")
                    append("Isolated build root: ")
                        .append(formatStorageBytes(builder.buildRootBytes))
                        .append(" across ")
                        .append(builder.buildRootEntries)
                        .append(" verified entries\n")
                    append("Builder toolchain: ")
                        .append(builder.runtimeVersion)
                        .append('\n')
                }
                if (built == null) {
                    append("Installed/build disk impact: pending the isolated package build.")
                } else {
                    append("Verified package: ")
                        .append(built.filename)
                        .append(" · ")
                        .append(formatStorageBytes(built.archiveBytes))
                        .append(" archive · ")
                        .append(formatStorageBytes(built.installedBytes))
                        .append(" installed")
                }
            }.trimEnd()
        val digests =
            buildString(4096) {
                append("AUR commit: ").append(review.snapshotCommit).append('\n')
                append("Snapshot SHA-256: ").append(review.snapshotSha256).append('\n')
                review.sources.forEach { source ->
                    append(source.filename)
                        .append(' ')
                        .append(source.checksumAlgorithm ?: "checksum")
                        .append(": ")
                        .append(source.checksum ?: "SKIP")
                        .append('\n')
                }
                if (buildEnvironment?.verified == true) {
                    append("Build closure SHA-256: ")
                        .append(buildEnvironment.closureManifestSha256)
                        .append('\n')
                }
                if (builder != null) {
                    append("Builder input SHA-256: ")
                        .append(builder.inputManifestSha256)
                        .append('\n')
                    append("Builder closure SHA-256: ")
                        .append(builder.closureManifestSha256)
                        .append('\n')
                }
                if (built != null) {
                    append("Built package SHA-256: ").append(built.sha256).append('\n')
                }
            }.trimEnd()
        val recipe =
            buildString(
                minOf(
                    NativeRuntime.AUR_REVIEW_SIZE,
                    review.pkgbuild.length + review.installScriptContents.length + 2048,
                ),
            ) {
                appendAurValues("Runtime dependencies", review.dependencies)
                appendAurValues("Build dependencies", review.makeDependencies)
                appendAurValues("Check dependencies", review.checkDependencies)
                appendAurValues("Valid PGP keys", review.validPgpKeys)
                append("\nVisible build functions\n")
                review.buildSteps.forEach { step -> append("• ").append(step).append('\n') }
                if (review.installScript.isNotEmpty()) {
                    append("\nInstall script: ").append(review.installScript).append('\n')
                    append(review.installScriptContents).append('\n')
                }
                append("\nPKGBUILD\n")
                append(review.pkgbuild)
            }.trim()
        return AurReviewSnapshot(
            review.packageName,
            summary,
            sources,
            trust,
            buildEnvironmentText,
            digests,
            recipe,
            logs,
            revision,
        )
    }

    private fun StringBuilder.appendAurValues(
        heading: String,
        values: Array<String>,
    ) {
        if (values.isEmpty()) {
            return
        }
        append('\n').append(heading).append('\n')
        values.forEach { value -> append("• ").append(value).append('\n') }
    }

    private fun boundedAurCount(
        value: Int,
        maximum: Int,
    ): Int {
        require(value in 0..maximum)
        return value
    }

    private fun decodeAurScript(
        bytes: ByteArray,
        allowEmpty: Boolean,
    ): String {
        require(allowEmpty || bytes.isNotEmpty())
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require(
            value.none { character ->
                character == '\u0000' ||
                    character == '\r' ||
                    (character.isISOControl() && character !in "\n\t")
            },
        )
        return value
    }

    private fun hexSha256(bytes: ByteArray): String {
        require(bytes.size == 32)
        return hexBytes(bytes)
    }

    private fun hexBytes(bytes: ByteArray): String {
        val output = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_DIGITS[value ushr 4]
            output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(output)
    }

    private fun resolvePayloads(
        activeHandle: Long,
        packageName: String,
    ): List<ResolvedPayload> {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val bytes =
            synchronized(packageResolutionOutputBuffer) {
                packageResolutionRequestBuffer.clear()
                packageResolutionRequestBuffer.put(packageBytes)
                packageResolutionOutputBuffer.clear()
                val outputLength =
                    NativeRuntime.nativeResolvePackage(
                        activeHandle,
                        packageResolutionRequestBuffer,
                        packageBytes.size,
                        packageResolutionOutputBuffer,
                    )
                if (outputLength <= 0) {
                    throw IllegalStateException(
                        readNativeMessage(packageResolutionOutputBuffer, outputLength),
                    )
                }
                ByteArray(outputLength).also { output ->
                    packageResolutionOutputBuffer.position(0)
                    packageResolutionOutputBuffer.get(output)
                }
            }
        val packages = decodeResolvedPayloads(bytes, 256)
        if (packages.none { payload -> payload.name == packageName }) {
            throw IllegalStateException("Resolved packages omit the requested target")
        }
        return packages
    }

    private fun decodeResolvedPayloads(
        bytes: ByteArray,
        maximumPackages: Int,
    ): List<ResolvedPayload> {
        require(maximumPackages in 1..512)
        val packages = ArrayList<ResolvedPayload>()
        String(bytes, StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotEmpty)
            .forEach { line ->
                val fields = line.split('\t', limit = 6)
                val size = fields.getOrNull(5)?.toLongOrNull()
                if (fields.size != 6 || size == null || size <= 0) {
                    throw IllegalStateException("Rust returned an invalid resolution")
                }
                packages.add(
                    ResolvedPayload(
                        repository = fields[0],
                        name = fields[1],
                        version = fields[2],
                        filename = fields[3],
                        url = fields[4],
                        size = size,
                    ),
                )
                if (packages.size > maximumPackages) {
                    throw IllegalStateException("Package closure exceeds its limit")
                }
            }
        require(packages.isNotEmpty())
        return packages
    }

    private fun installedPackageVersion(
        activeHandle: Long,
        packageName: String,
    ): String {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val packageBuffer = ByteBuffer.allocateDirect(packageBytes.size)
        packageBuffer.put(packageBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativePackageCommand(
                activeHandle,
                NativeRuntime.PACKAGE_COMMAND_INSTALLED_VERSION,
                packageBuffer,
                packageBytes.size,
                outputBuffer,
            )
        if (outputLength < 0) {
            throw IllegalStateException(readNativeMessage(outputBuffer, outputLength))
        }
        val bytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun packageInstallationBytes(
        activeHandle: Long,
        packageName: String,
    ): Long {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val packageBuffer = ByteBuffer.allocateDirect(packageBytes.size)
        packageBuffer.put(packageBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativePackageCommand(
                activeHandle,
                NativeRuntime.PACKAGE_COMMAND_INSTALLATION_BYTES,
                packageBuffer,
                packageBytes.size,
                outputBuffer,
            )
        if (outputLength <= 0) {
            throw IllegalStateException(readNativeMessage(outputBuffer, outputLength))
        }
        val bytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
            .toLongOrNull()
            ?.takeIf { value -> value > 0L }
            ?: throw IllegalStateException("Invalid package installation size")
    }

    private fun installedPackageOrigin(
        activeHandle: Long,
        packageName: String,
    ): String {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val packageBuffer = ByteBuffer.allocateDirect(packageBytes.size)
        packageBuffer.put(packageBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativePackageCommand(
                activeHandle,
                NativeRuntime.PACKAGE_COMMAND_INSTALLED_ORIGIN,
                packageBuffer,
                packageBytes.size,
                outputBuffer,
            )
        if (outputLength <= 0) {
            throw IllegalStateException(readNativeMessage(outputBuffer, outputLength))
        }
        val bytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun discoverShells(activeHandle: Long): List<ShellChoice> {
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength = NativeRuntime.nativeDiscoverShells(activeHandle, outputBuffer)
        if (outputLength < 0) {
            throw IllegalStateException(
                "Installed shell discovery failed: ${readNativeMessage(outputBuffer, outputLength)}",
            )
        }
        if (outputLength == 0 || outputLength > NativeRuntime.PACKAGE_OUTPUT_SIZE) {
            throw IllegalStateException("No supported installed shell is available")
        }
        val bytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        val choices = ArrayList<ShellChoice>(2)
        val seenIds = HashSet<String>(2)
        String(bytes, StandardCharsets.UTF_8).lineSequence().forEach { line ->
            if (line.isEmpty()) {
                return@forEach
            }
            if (choices.size >= SHELL_CHOICE_LIMIT) {
                throw IllegalStateException("Installed shell catalog is too large")
            }
            val fields = line.split('\t')
            if (
                fields.size < 3 ||
                fields.size > 7 ||
                fields.any { field ->
                    field.isEmpty() ||
                        field.length > SHELL_FIELD_LIMIT ||
                        field.any { character ->
                            character.code !in 0x20..0x7e || character == '\u0000'
                        }
                } ||
                fields.drop(2).any { field -> field.indexOf(' ') >= 0 }
            ) {
                throw IllegalStateException("Installed shell catalog is invalid")
            }
            val id = fields[0]
            val label = fields[1]
            if (
                !id.all { character ->
                    character.isLowerCase() || character.isDigit() || character == '-'
                } ||
                !seenIds.add(id)
            ) {
                throw IllegalStateException("Installed shell catalog has an invalid identifier")
            }
            val encoded = fields.drop(2).map { field -> field.toByteArray(StandardCharsets.UTF_8) }
            val requestLength = encoded.sumOf(ByteArray::size) + encoded.size - 1
            if (requestLength > NativeRuntime.COMMAND_REQUEST_LIMIT) {
                throw IllegalStateException("Installed shell launch request is too large")
            }
            val requestBytes = ByteArray(requestLength)
            var offset = 0
            encoded.forEachIndexed { index, field ->
                if (index != 0) {
                    requestBytes[offset++] = 0
                }
                field.copyInto(requestBytes, offset)
                offset += field.size
            }
            choices.add(ShellChoice(id, label, requestBytes))
        }
        if (choices.isEmpty()) {
            throw IllegalStateException("No supported installed shell is available")
        }
        return choices
    }

    @Synchronized
    private fun publishShellChoices(choices: List<ShellChoice>) {
        val preferredId =
            getSharedPreferences(SHELL_PREFERENCES, MODE_PRIVATE)
                .getString(SHELL_PREFERENCE_ID, "bash")
        shellChoices = choices
        selectedShellIndex =
            if (choices.isEmpty()) {
                -1
            } else {
                choices.indexOfFirst { choice -> choice.id == preferredId }
                    .takeIf { index -> index >= 0 }
                    ?: choices.indexOfFirst { choice -> choice.id == "bash" }
                        .takeIf { index -> index >= 0 }
                    ?: 0
            }
        shellChoicesRevision++
    }

    private fun refreshShellChoices(activeHandle: Long) {
        try {
            publishShellChoices(discoverShells(activeHandle))
        } catch (error: Exception) {
            publishShellChoices(emptyList())
            shellPhase = "No supported installed shell"
            Log.w(TAG, "Installed shell catalog unavailable", error)
        }
    }

    @Synchronized
    private fun requestAurPackageInstall(packageName: String): Boolean {
        val normalized = packageName.trim()
        val review = retainedAurReview
        val built = retainedAurBuiltPackage
        val builtPackages = retainedAurBuiltPackages
        val builder = retainedAurBuilderReport
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            review == null ||
            built == null ||
            builder == null ||
            normalized != review.packageName ||
            builtPackages.isEmpty() ||
            !builtPackages
                .map(AurBuiltPackage::packageName)
                .toTypedArray()
                .contentEquals(review.requiredPackages) ||
            builtPackages.any { output ->
                !output.file.isFile || output.file.length() != output.archiveBytes
            } ||
            !built.file.isFile ||
            built.file.length() != built.archiveBytes ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            jobStatus = "Build and verify this exact AUR package before installing it"
            return false
        }
        val architecture =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64"
                "arm64-v8a" -> "aarch64"
                else -> {
                    jobStatus = "Unsupported Android ABI"
                    return false
                }
            }
        val operation =
            if (lastResolvedInstalledVersion.isEmpty()) {
                NativeRuntime.JOB_OPERATION_INSTALL
            } else {
                NativeRuntime.JOB_OPERATION_UPDATE
            }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "aur\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                operation,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0) {
            jobStatus = "Could not queue AUR install: ${readNativeMessage(outputBuffer, jobId)}"
            return false
        }
        jobPersistentId = jobId
        jobRepository = "aur"
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        publishPackageJob(
            normalized,
            operation,
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued verified AUR package",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var recordedPhase = 0
                    var recordedProgress = 0
                    fun record(
                        state: Int,
                        phase: Int,
                        progress: Int,
                        message: String,
                    ) {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            state,
                            phase,
                            progress,
                            message,
                            normalized,
                            scratch,
                        )
                        recordedPhase = phase
                        recordedProgress = progress
                    }
                    try {
                        throwIfPackageCancelled()
                        record(
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            5,
                            "Resolving reviewed runtime dependencies",
                        )
                        throwIfPackageCancelled()
                        record(
                            NativeRuntime.JOB_VERIFYING,
                            2,
                            15,
                            "Reverifying built package and signed dependency closure",
                        )
                        if (!enterPackageCommit()) {
                            throw InterruptedException("Package operation cancelled")
                        }
                        record(
                            NativeRuntime.JOB_INSTALLING,
                            3,
                            35,
                            "Installing verified dependencies and AUR package",
                        )
                        val nativeOutput =
                            ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                        val filenameManifest = aurInstallFilenameManifest(builtPackages)
                        val descriptors =
                            builtPackages.map { output ->
                                ParcelFileDescriptor.open(
                                    output.file,
                                    ParcelFileDescriptor.MODE_READ_ONLY,
                                )
                            }
                        val installedVersion =
                            try {
                                val length =
                                    NativeRuntime.nativeInstallAurBuiltPackages(
                                        activeHandle,
                                        descriptors.map { descriptor -> descriptor.fd }.toIntArray(),
                                        filenameManifest,
                                        filenameManifest.capacity(),
                                        review.packageBase,
                                        review.packageName,
                                        review.version,
                                        architecture,
                                        builder.closureManifestSha256,
                                        nativeOutput,
                                    )
                                if (length <= 0 || length > NativeRuntime.PACKAGE_OUTPUT_SIZE) {
                                    throw IllegalStateException(
                                        readNativeMessage(nativeOutput, length),
                                    )
                                }
                                val bytes = ByteArray(length)
                                nativeOutput.position(0)
                                nativeOutput.get(bytes)
                                String(bytes, StandardCharsets.UTF_8)
                            } finally {
                                descriptors.forEach(ParcelFileDescriptor::close)
                            }
                        check(installedVersion == review.version) {
                            "Installed AUR version does not match the reviewed build"
                        }
                        refreshPackageInventory(activeHandle)
                        refreshShellChoices(activeHandle)
                        record(
                            NativeRuntime.JOB_COMPLETE,
                            4,
                            100,
                            "Installed ${review.packageName} ${review.version}",
                        )
                        clearRetainedAurBuiltPackages()
                        lastResolvedInstalledVersion = review.version
                        lastResolvedAvailableVersion = review.version
                        primaryActionLabel = "Installed"
                        removeAvailable = true
                        searchStatus =
                            "Installed verified AUR package ${review.packageName} ${review.version}"
                        Log.i(
                            TAG,
                            "Installed verified AUR package ${review.packageName} " +
                                "${review.version} (${built.sha256})",
                        )
                    } catch (error: Exception) {
                        val cancelled =
                            error is InterruptedException || packageCancellationRequested
                        val mutationStarted = !cancelled && recordedPhase >= 3
                        val refreshed =
                            if (mutationStarted) {
                                val inventory = refreshPackageInventory(activeHandle)
                                refreshShellChoices(activeHandle)
                                inventory
                            } else {
                                true
                            }
                        val failureMessage =
                            boundedJobMessage(
                                if (cancelled) {
                                    "Cancelled before package mutation"
                                } else {
                                    PackageFailureDiagnostics.install(
                                        error,
                                        mutationStarted,
                                        refreshed,
                                    )
                                },
                            )
                        try {
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                if (cancelled) {
                                    NativeRuntime.JOB_CANCELLED
                                } else {
                                    NativeRuntime.JOB_FAILED
                                },
                                recordedPhase,
                                recordedProgress,
                                failureMessage,
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
                            publishPackageJob(
                                normalized,
                                operation,
                                if (cancelled) {
                                    NativeRuntime.JOB_CANCELLED
                                } else {
                                    NativeRuntime.JOB_FAILED
                                },
                                recordedProgress,
                                boundedJobMessage(
                                    "$failureMessage Activity journal update failed; " +
                                        "restart Archphene.",
                                ),
                            )
                            jobStatus =
                                "AUR install failed and journal update failed: " +
                                    (updateError.message ?: updateError.javaClass.simpleName)
                        }
                        Log.e(TAG, "Verified AUR package install failed", error)
                    } finally {
                        packageOperationCancelable = false
                        packageCancellationRequested = false
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneAurInstall",
            )
        schedulePackageWorker(worker, activeHandle)
        promoteWorkToForeground()
        return true
    }

    private fun aurInstallFilenameManifest(
        packages: Array<AurBuiltPackage>,
    ): ByteBuffer {
        require(packages.isNotEmpty() && packages.size <= 256)
        val filenames =
            packages.map { built ->
                built.filename.toByteArray(StandardCharsets.US_ASCII).also { filename ->
                    require(filename.isNotEmpty() && filename.size <= 240)
                }
            }
        val length =
            filenames.fold(12) { total, filename ->
                Math.addExact(total, Math.addExact(4, filename.size))
            }
        require(length <= 64 * 1024)
        return ByteBuffer
            .allocateDirect(length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("AIFN0001".toByteArray(StandardCharsets.US_ASCII))
                putInt(filenames.size)
                filenames.forEach { filename ->
                    putInt(filename.size)
                    put(filename)
                }
            }
    }

    @Synchronized
    private fun requestPackageInstall(packageName: String): Boolean {
        val normalized = packageName.trim()
        val repository = lastResolvedRepository
        val installedVersion = lastResolvedInstalledVersion
        val availableVersion = lastResolvedAvailableVersion
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized != lastResolvedPackage ||
            availableVersion.isEmpty() ||
            (repository != "core" && repository != "extra")
        ) {
            jobStatus = "Open Details for one exact package before installing it"
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "$repository\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size)
        requestBuffer.put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                if (installedVersion.isEmpty()) {
                    NativeRuntime.JOB_OPERATION_INSTALL
                } else {
                    NativeRuntime.JOB_OPERATION_UPDATE
                },
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0) {
            jobStatus = "Could not queue install: ${readNativeMessage(outputBuffer, jobId)}"
            return false
        }
        jobPersistentId = jobId
        jobRepository = repository
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        publishPackageJob(
            normalized,
            if (installedVersion.isEmpty()) {
                NativeRuntime.JOB_OPERATION_INSTALL
            } else {
                NativeRuntime.JOB_OPERATION_UPDATE
            },
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var recordedPhase = 0
                    var recordedProgress = 0
                    fun record(
                        state: Int,
                        phase: Int,
                        progress: Int,
                        message: String,
                    ) {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            state,
                            phase,
                            progress,
                            message,
                            normalized,
                            scratch,
                        )
                        recordedPhase = phase
                        recordedProgress = progress
                    }
                    try {
                        holdDebugPackageWorker()
                        throwIfPackageCancelled()
                        record(
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            5,
                            "Resolving signed dependency closure",
                        )
                        val packages = resolvePayloads(activeHandle, normalized)
                        throwIfPackageCancelled()
                        val target =
                            packages.firstOrNull { payload -> payload.name == normalized }
                                ?: throw IllegalStateException(
                                    "Resolved packages omit the requested target",
                                )
                        if (target.repository != repository) {
                            throw SecurityException("Target repository changed during install")
                        }
                        if (target.version != availableVersion) {
                            throw SecurityException(
                                "Target version changed; open Details again",
                            )
                        }
                        packages.forEachIndexed { index, payload ->
                            throwIfPackageCancelled()
                            val progress = 10 + (index * 65 / packages.size)
                            record(
                                NativeRuntime.JOB_DOWNLOADING,
                                2,
                                progress,
                                "Downloading ${payload.name} (${index + 1}/${packages.size})",
                            )
                            if (isCachedPackageValid(activeHandle, payload, scratch)) {
                                return@forEachIndexed
                            }
                            downloadPackagePayload(activeHandle, payload, false, scratch)
                            downloadPackagePayload(activeHandle, payload, true, scratch)
                        }
                        packages.forEachIndexed { index, payload ->
                            throwIfPackageCancelled()
                            val progress = 76 + (index * 20 / packages.size)
                            record(
                                NativeRuntime.JOB_VERIFYING,
                                3,
                                progress,
                                "Verifying ${payload.name} (${index + 1}/${packages.size})",
                            )
                            verifyPackagePayload(activeHandle, payload, scratch)
                        }
                        val installedBytes =
                            packageInstallationBytes(activeHandle, normalized)
                        val availableBytes = StatFs(filesDir.absolutePath).availableBytes
                        val reserveBytes =
                            maxOf(
                                64L * 1024L * 1024L,
                                installedBytes / 10L,
                            )
                        val requiredBytes = Math.addExact(installedBytes, reserveBytes)
                        if (availableBytes < requiredBytes) {
                            throw IllegalStateException(
                                "Not enough storage: ${formatStorageBytes(requiredBytes)} free " +
                                    "is required to install; " +
                                    "${formatStorageBytes(availableBytes)} is available",
                            )
                        }
                        if (!enterPackageCommit()) {
                            throw InterruptedException("Package operation cancelled")
                        }
                        record(
                            NativeRuntime.JOB_INSTALLING,
                            4,
                            97,
                            "Installing verified packages",
                        )
                        runPackageCommand(
                            activeHandle,
                            NativeRuntime.PACKAGE_COMMAND_INSTALL,
                            normalized,
                            scratch,
                        )
                        refreshPackageInventory(activeHandle)
                        refreshShellChoices(activeHandle)
                        record(
                            NativeRuntime.JOB_COMPLETE,
                            5,
                            100,
                            when {
                                installedVersion.isEmpty() ->
                                    "Installed ${target.name} ${target.version}"
                                installedVersion == target.version ->
                                    "Verified ${target.name} ${target.version}"
                                else -> "Updated ${target.name} to ${target.version}"
                            },
                        )
                        lastResolvedInstalledVersion = target.version
                        lastResolvedAvailableVersion = target.version
                        primaryActionLabel = "Verify"
                        removeActionLabel = "Remove"
                        removeAvailable = true
                        searchStatus = withInstalledStatus(searchStatus, target.version)
                        Log.i(
                            TAG,
                            when {
                                installedVersion.isEmpty() ->
                                    "Installed $normalized: ${packages.size} signed packages"
                                installedVersion == target.version ->
                                    "Verified $normalized: ${packages.size} signed packages"
                                else -> "Updated $normalized: ${packages.size} signed packages"
                            },
                        )
                    } catch (error: Exception) {
                        val cancelled =
                            error is InterruptedException || packageCancellationRequested
                        val mutationStarted = !cancelled && recordedPhase >= 4
                        val installedStateRefreshed =
                            if (mutationStarted) {
                                val refreshed = refreshPackageInventory(activeHandle)
                                refreshShellChoices(activeHandle)
                                refreshPendingPackageMutation(activeHandle)
                                refreshed
                            } else {
                                true
                            }
                        val terminalState =
                            if (cancelled) {
                                NativeRuntime.JOB_CANCELLED
                            } else {
                                NativeRuntime.JOB_FAILED
                            }
                        val failureMessage =
                            boundedJobMessage(
                                if (cancelled) {
                                    "Cancelled before package mutation"
                                } else {
                                    PackageFailureDiagnostics.install(
                                        error,
                                        mutationStarted,
                                        installedStateRefreshed,
                                    )
                                },
                            )
                        try {
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                terminalState,
                                recordedPhase,
                                recordedProgress,
                                failureMessage,
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
                            publishPackageJob(
                                normalized,
                                jobOperation,
                                terminalState,
                                recordedProgress,
                                boundedJobMessage(
                                    "$failureMessage Activity journal update failed; " +
                                        "restart Archphene.",
                                ),
                            )
                            jobStatus =
                                "Install failed and journal update failed: " +
                                    (updateError.message ?: updateError.javaClass.simpleName)
                        }
                        if (cancelled) {
                            Log.i(TAG, "Cancelled package operation for $normalized")
                        } else {
                            Log.e(TAG, "Package install failed", error)
                        }
                    } finally {
                        activePackageConnection?.disconnect()
                        activePackageConnection = null
                        packageOperationCancelable = false
                        packageCancellationRequested = false
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneInstall",
            )
        schedulePackageWorker(worker, activeHandle)
        promoteWorkToForeground()
        return true
    }

    private fun runPackageCommand(
        activeHandle: Long,
        action: Int,
        packageName: String,
        scratch: PackageIoScratch,
    ) {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(packageBytes)
        scratch.outputBuffer.clear()
        val outputLength =
            NativeRuntime.nativePackageCommand(
                activeHandle,
                action,
                scratch.requestBuffer,
                packageBytes.size,
                scratch.outputBuffer,
            )
        if (outputLength < 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, outputLength))
        }
    }

    private fun refreshPendingPackageMutation(activeHandle: Long) {
        val packageName = jobPackage
        if (packageName.isEmpty()) {
            packageMutationStatus = ""
            return
        }
        val scratch = PackageIoScratch()
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(packageBytes)
        scratch.outputBuffer.clear()
        val length =
            NativeRuntime.nativePackageCommand(
                activeHandle,
                NativeRuntime.PACKAGE_COMMAND_PENDING_MUTATION,
                scratch.requestBuffer,
                packageBytes.size,
                scratch.outputBuffer,
            )
        if (length < 0) {
            packageMutationStatus = ""
            Log.e(
                TAG,
                "Pending package mutation inspection failed: " +
                    readNativeMessage(scratch.outputBuffer, length),
            )
            return
        }
        val bytes = ByteArray(length)
        scratch.outputBuffer.position(0)
        scratch.outputBuffer.get(bytes)
        packageMutationStatus = String(bytes, StandardCharsets.UTF_8)
        if (packageMutationStatus.isNotEmpty()) {
            packageRecoveryMessageRevision = jobRevision
            packageRecoveryMessage =
                "Package mutation was interrupted. Repair re-verifies and completes the " +
                    "retained transaction."
        }
    }

    @Synchronized
    private fun requestPackageMutationRepair(): Boolean {
        val activeHandle = readyHandle
        val packageName = jobPackage
        val operation = jobOperation
        if (
            activeHandle == 0L ||
            packageMutationStatus.isEmpty() ||
            packageName.isEmpty() ||
            operation !in
                NativeRuntime.JOB_OPERATION_INSTALL..NativeRuntime.JOB_OPERATION_REMOVE ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val repository = jobRepository.ifEmpty { "recovery" }
        val requestBytes = "$repository\t$packageName".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                operation,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0L) {
            jobStatus = "Could not queue package repair: ${readNativeMessage(outputBuffer, jobId)}"
            return false
        }
        jobPersistentId = jobId
        jobRepository = repository
        packageOperationCancelable = false
        packageOperationActive = true
        publishPackageJob(
            packageName,
            operation,
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued interrupted package repair",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var phase = 0
                    var progress = 0
                    fun record(
                        state: Int,
                        nextPhase: Int,
                        nextProgress: Int,
                        message: String,
                    ) {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            state,
                            nextPhase,
                            nextProgress,
                            message,
                            packageName,
                            scratch,
                        )
                        phase = nextPhase
                        progress = nextProgress
                    }
                    try {
                        record(
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            10,
                            "Inspecting retained package mutation",
                        )
                        record(
                            NativeRuntime.JOB_VERIFYING,
                            2,
                            30,
                            "Re-verifying retained transaction inputs",
                        )
                        record(
                            NativeRuntime.JOB_INSTALLING,
                            3,
                            60,
                            "Repairing interrupted package transaction",
                        )
                        runPackageCommand(
                            activeHandle,
                            NativeRuntime.PACKAGE_COMMAND_REPAIR_MUTATION,
                            packageName,
                            scratch,
                        )
                        refreshPackageInventory(activeHandle)
                        refreshShellChoices(activeHandle)
                        packageMutationStatus = ""
                        record(
                            NativeRuntime.JOB_COMPLETE,
                            4,
                            100,
                            "Repaired package transaction for $packageName",
                        )
                        Log.i(TAG, "Repaired interrupted package transaction for $packageName")
                    } catch (error: Exception) {
                        refreshPackageInventory(activeHandle)
                        refreshShellChoices(activeHandle)
                        refreshPendingPackageMutation(activeHandle)
                        val message =
                            boundedJobMessage(
                                "Package repair did not finish: " +
                                    (error.message ?: error.javaClass.simpleName),
                            )
                        runCatching {
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                NativeRuntime.JOB_FAILED,
                                phase,
                                progress,
                                message,
                                packageName,
                                scratch,
                            )
                        }
                        Log.e(TAG, "Package mutation repair failed", error)
                    } finally {
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchphenePackageRepair",
            )
        packageThread = worker
        worker.start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestPackageRemoval(packageName: String): Boolean {
        val normalized = packageName.trim()
        val repository = lastResolvedRepository
        val installedVersion = lastResolvedInstalledVersion
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized != lastResolvedPackage ||
            installedVersion.isEmpty() ||
            (repository != "core" && repository != "extra" && repository != "aur")
        ) {
            jobStatus = "Open Details for an installed package before removing it"
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "$repository\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size)
        requestBuffer.put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                NativeRuntime.JOB_OPERATION_REMOVE,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0) {
            jobStatus = "Could not queue removal: ${readNativeMessage(outputBuffer, jobId)}"
            return false
        }
        jobPersistentId = jobId
        jobRepository = repository
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        publishPackageJob(
            normalized,
            NativeRuntime.JOB_OPERATION_REMOVE,
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var recordedPhase = 0
                    var recordedProgress = 0
                    fun record(
                        state: Int,
                        phase: Int,
                        progress: Int,
                        message: String,
                    ) {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            state,
                            phase,
                            progress,
                            message,
                            normalized,
                            scratch,
                        )
                        recordedPhase = phase
                        recordedProgress = progress
                    }
                    try {
                        throwIfPackageCancelled()
                        record(
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            20,
                            "Checking installed package and dependents",
                        )
                        val currentVersion = installedPackageVersion(activeHandle, normalized)
                        throwIfPackageCancelled()
                        if (currentVersion != installedVersion) {
                            throw IllegalStateException(
                                "Installed version changed; open Details again",
                            )
                        }
                        record(
                            NativeRuntime.JOB_VERIFYING,
                            2,
                            60,
                            "Validating conservative removal plan",
                        )
                        if (!enterPackageCommit()) {
                            throw InterruptedException("Package operation cancelled")
                        }
                        record(
                            NativeRuntime.JOB_INSTALLING,
                            3,
                            80,
                            "Removing $normalized $installedVersion",
                        )
                        runPackageCommand(
                            activeHandle,
                            NativeRuntime.PACKAGE_COMMAND_REMOVE,
                            normalized,
                            scratch,
                        )
                        refreshPackageInventory(activeHandle)
                        refreshShellChoices(activeHandle)
                        lastResolvedInstalledVersion = ""
                        primaryActionLabel = "Install"
                        removeActionLabel = "Remove"
                        removeAvailable = false
                        searchStatus = withInstalledStatus(searchStatus, "")
                        record(
                            NativeRuntime.JOB_COMPLETE,
                            4,
                            100,
                            "Removed $normalized $installedVersion",
                        )
                        Log.i(TAG, "Removed $normalized $installedVersion")
                    } catch (error: Exception) {
                        val cancelled =
                            error is InterruptedException || packageCancellationRequested
                        val mutationStarted = !cancelled && recordedPhase >= 3
                        val installedStateRefreshed =
                            if (mutationStarted) {
                                val refreshed = refreshPackageInventory(activeHandle)
                                refreshShellChoices(activeHandle)
                                refreshPendingPackageMutation(activeHandle)
                                refreshed
                            } else {
                                true
                            }
                        val terminalState =
                            if (cancelled) {
                                NativeRuntime.JOB_CANCELLED
                            } else {
                                NativeRuntime.JOB_FAILED
                            }
                        val failureMessage =
                            boundedJobMessage(
                                if (cancelled) {
                                    "Cancelled before package mutation"
                                } else {
                                    PackageFailureDiagnostics.removal(
                                        error,
                                        mutationStarted,
                                        installedStateRefreshed,
                                    )
                                },
                            )
                        try {
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                terminalState,
                                recordedPhase,
                                recordedProgress,
                                failureMessage,
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
                            publishPackageJob(
                                normalized,
                                jobOperation,
                                terminalState,
                                recordedProgress,
                                boundedJobMessage(
                                    "$failureMessage Activity journal update failed; " +
                                        "restart Archphene.",
                                ),
                            )
                            jobStatus =
                                "Removal failed and journal update failed: " +
                                    (updateError.message ?: updateError.javaClass.simpleName)
                        }
                        if (cancelled) {
                            Log.i(TAG, "Cancelled package operation for $normalized")
                        } else {
                            Log.e(TAG, "Package removal failed", error)
                        }
                    } finally {
                        packageOperationCancelable = false
                        packageCancellationRequested = false
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneRemove",
            )
        schedulePackageWorker(worker, activeHandle)
        promoteWorkToForeground()
        return true
    }

    private fun schedulePackageWorker(
        worker: Thread,
        activeHandle: Long,
    ) {
        packageThread = worker
        // Local Binder calls run on the Activity thread. One Looper turn gives that caller a
        // deterministic chance to render the already-durable Queued record before work advances.
        mainHandler.post {
            synchronized(this) {
                if (
                    packageThread === worker &&
                    packageOperationActive &&
                    handle == activeHandle
                ) {
                    worker.start()
                }
            }
        }
    }

    @Synchronized
    private fun requestPackageCacheInventory(): Boolean {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        packageCacheActive = true
        packageCacheSnapshot =
            copyPackageCacheSnapshot("Inspecting downloaded package storage…")
        Thread(
            {
                try {
                    packageCacheSnapshot = loadPackageCacheSnapshot(activeHandle)
                } catch (error: Exception) {
                    packageCacheSnapshot =
                        copyPackageCacheSnapshot(
                            "Package storage unavailable: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    Log.e(TAG, "Package cache inventory failed", error)
                } finally {
                    packageCacheActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchphenePackageCacheInventory",
        ).start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestSelectedPackageCacheCleanup(packages: Array<String>): Boolean {
        val activeHandle = readyHandle
        val current = packageCacheSnapshot
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            packages.isEmpty() ||
            packages.size > MAX_PACKAGE_CACHE_SELECTION ||
            packages.size > current.names.size
        ) {
            return false
        }
        val selected = packages.copyOf()
        if (
            selected.any { packageName ->
                packageName.isEmpty() ||
                    packageName.length > 128 ||
                    current.names.binarySearch(packageName) < 0
            } ||
            selected.toSet().size != selected.size
        ) {
            return false
        }
        val requestBytes =
            selected
                .sorted()
                .joinToString("\n")
                .toByteArray(StandardCharsets.UTF_8)
        if (requestBytes.isEmpty() || requestBytes.size > PACKAGE_CACHE_SELECTION_BYTES) {
            return false
        }
        packageCacheActive = true
        packageCacheSnapshot =
            copyPackageCacheSnapshot("Clearing selected downloaded packages…")
        Thread(
            {
                try {
                    val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size)
                    requestBuffer.put(requestBytes)
                    val outputBuffer =
                        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val reclaimedBytes =
                        NativeRuntime.nativeClearSelectedPackageCache(
                            activeHandle,
                            requestBuffer,
                            requestBytes.size,
                            outputBuffer,
                        )
                    if (reclaimedBytes < 0L) {
                        throw IllegalStateException(
                            readNativeMessage(outputBuffer, reclaimedBytes),
                        )
                    }
                    packageCacheSnapshot =
                        loadPackageCacheSnapshot(
                            activeHandle,
                            if (reclaimedBytes == 0L) {
                                "No matching downloaded packages were found"
                            } else {
                                "Freed ${formatStorageBytes(reclaimedBytes)} of downloaded packages"
                            },
                        )
                    Log.i(
                        TAG,
                        "Cleared $reclaimedBytes package-cache bytes for " +
                            "${selected.size} selected package(s)",
                    )
                } catch (error: Exception) {
                    packageCacheSnapshot =
                        copyPackageCacheSnapshot(
                            "Package cleanup failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    Log.e(TAG, "Selected package cache cleanup failed", error)
                } finally {
                    packageCacheActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneSelectedPackageCache",
        ).start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestAllPackageCacheCleanup(): Boolean {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        packageCacheActive = true
        packageCacheSnapshot =
            copyPackageCacheSnapshot("Clearing all downloaded packages…")
        Thread(
            {
                try {
                    val outputBuffer =
                        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val reclaimedBytes =
                        NativeRuntime.nativeClearPackageCache(activeHandle, outputBuffer)
                    if (reclaimedBytes < 0L) {
                        throw IllegalStateException(
                            readNativeMessage(outputBuffer, reclaimedBytes.toInt()),
                        )
                    }
                    packageCacheSnapshot =
                        loadPackageCacheSnapshot(
                            activeHandle,
                            if (reclaimedBytes == 0L) {
                                "No downloaded packages are cached"
                            } else {
                                "Freed ${formatStorageBytes(reclaimedBytes)} of downloaded packages"
                            },
                        )
                    Log.i(TAG, "Cleared all $reclaimedBytes package-cache bytes")
                } catch (error: Exception) {
                    packageCacheSnapshot =
                        copyPackageCacheSnapshot(
                            "Package cleanup failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    Log.e(TAG, "Complete package cache cleanup failed", error)
                } finally {
                    packageCacheActive = false
                    stopWhenUnobservedAndIdle()
                }
            },
            "ArchpheneAllPackageCache",
        ).start()
        promoteWorkToForeground()
        return true
    }

    private fun loadPackageCacheSnapshot(
        activeHandle: Long,
        completionStatus: String? = null,
    ): PackageCacheSnapshot {
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val summaryLength = NativeRuntime.nativeRefreshPackageCache(activeHandle, outputBuffer)
        if (summaryLength < 0) {
            throw IllegalStateException(readNativeMessage(outputBuffer, summaryLength))
        }
        val summary = readUtf8(outputBuffer, summaryLength).trimEnd().split('\t')
        if (summary.size != 3 || summary[0] != "C1") {
            throw IllegalStateException("Package cache returned an invalid summary")
        }
        val expectedEntries = summary[1].toIntOrNull() ?: -1
        val expectedBytes = summary[2].toLongOrNull() ?: -1L
        if (
            expectedEntries !in 0..PACKAGE_CACHE_ENTRY_LIMIT ||
            expectedBytes < 0L
        ) {
            throw IllegalStateException("Package cache summary exceeds its bounds")
        }
        val names = ArrayList<String>()
        val versions = ArrayList<String>()
        val latestVersions = ArrayList<String>()
        val sizes = ArrayList<Long>()
        val artifacts = ArrayList<Int>()
        val versionCounts = ArrayList<Int>()
        var offset = 0
        var observedBytes = 0L
        while (offset < expectedEntries) {
            outputBuffer.clear()
            val pageLength =
                NativeRuntime.nativeReadPackageCachePage(
                    activeHandle,
                    offset,
                    outputBuffer,
                )
            if (pageLength < 0) {
                throw IllegalStateException(readNativeMessage(outputBuffer, pageLength))
            }
            if (pageLength == 0) {
                throw IllegalStateException("Package cache ended before its declared count")
            }
            var pageRows = 0
            readUtf8(outputBuffer, pageLength)
                .trimEnd('\n')
                .lineSequence()
                .forEach { row ->
                    val fields = row.split('\t')
                    if (fields.size != 5) {
                        throw IllegalStateException("Package cache returned an invalid row")
                    }
                    val packageName = fields[0]
                    val version = fields[1]
                    val architecture = fields[2]
                    val bytes = fields[3].toLongOrNull() ?: -1L
                    val artifactCount = fields[4].toIntOrNull() ?: -1
                    if (
                        packageName.isEmpty() ||
                        packageName.length > 128 ||
                        version.isEmpty() ||
                        version.length > 193 ||
                        architecture.isEmpty() ||
                        architecture.length > 32 ||
                        bytes < 0L ||
                        artifactCount <= 0
                    ) {
                        throw IllegalStateException("Package cache row exceeds its bounds")
                    }
                    observedBytes =
                        Math.addExact(observedBytes, bytes)
                    val lastIndex = names.lastIndex
                    if (lastIndex >= 0 && names[lastIndex] == packageName) {
                        sizes[lastIndex] = Math.addExact(sizes[lastIndex], bytes)
                        artifacts[lastIndex] =
                            Math.addExact(artifacts[lastIndex], artifactCount)
                        if (latestVersions[lastIndex] != version) {
                            latestVersions[lastIndex] = version
                            versionCounts[lastIndex] =
                                Math.addExact(versionCounts[lastIndex], 1)
                            versions[lastIndex] =
                                "${versionCounts[lastIndex]} cached versions"
                        }
                    } else {
                        if (lastIndex >= 0 && names[lastIndex] >= packageName) {
                            throw IllegalStateException("Package cache rows are not ordered")
                        }
                        names.add(packageName)
                        versions.add(version)
                        latestVersions.add(version)
                        sizes.add(bytes)
                        artifacts.add(artifactCount)
                        versionCounts.add(1)
                    }
                    pageRows++
                }
            if (pageRows !in 1..PACKAGE_CACHE_PAGE_SIZE) {
                throw IllegalStateException("Package cache page exceeds its row bound")
            }
            offset = Math.addExact(offset, pageRows)
        }
        if (offset != expectedEntries || observedBytes != expectedBytes) {
            throw IllegalStateException("Package cache summary does not match its rows")
        }
        return PackageCacheSnapshot(
            names.toTypedArray(),
            versions.toTypedArray(),
            sizes.toLongArray(),
            artifacts.toIntArray(),
            expectedBytes,
            completionStatus
                ?: if (names.isEmpty()) {
                    "No downloaded packages are cached"
                } else {
                    "${names.size} cached packages · ${formatStorageBytes(expectedBytes)}"
                },
            packageCacheSnapshot.revision + 1,
        )
    }

    private fun copyPackageCacheSnapshot(status: String): PackageCacheSnapshot {
        val current = packageCacheSnapshot
        return PackageCacheSnapshot(
            current.names,
            current.versions,
            current.bytes,
            current.artifacts,
            current.totalBytes,
            status,
            current.revision + 1,
        )
    }

    private fun readUtf8(
        buffer: ByteBuffer,
        length: Int,
    ): String {
        if (length < 0 || length > buffer.capacity()) {
            throw IllegalStateException("Native output exceeds its buffer")
        }
        val bytes = ByteArray(length)
        buffer.position(0)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    @Synchronized
    private fun requestPackageCacheCleanup(): Boolean {
        val activeHandle = readyHandle
        val recoveryRevision = jobRevision
        val recoveryJobId = jobPersistentId
        val recoveryPackage = jobPackage
        val recoveryOperation = jobOperation
        val recoveryState = jobState
        val recoveryFailure = jobMessage
        if (activeHandle == 0L || !packageCacheRecoveryReady()) {
            return false
        }
        packageOperationActive = true
        packageOperationCancelable = false
        packageCancellationRequested = false
        packageRecoveryMessageRevision = recoveryRevision
        packageRecoveryMessage = "Clearing downloaded package cache…"
        val worker =
            Thread(
                {
                    try {
                        holdDebugPackageCacheCleanup()
                        val outputBuffer =
                            ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                        val reclaimedBytes =
                            NativeRuntime.nativeClearPackageCache(activeHandle, outputBuffer)
                        if (reclaimedBytes < 0L) {
                            throw IllegalStateException(
                                readNativeMessage(outputBuffer, reclaimedBytes.toInt()),
                            )
                        }
                        packageCacheSnapshot =
                            PackageCacheSnapshot(
                                emptyArray(),
                                emptyArray(),
                                LongArray(0),
                                IntArray(0),
                                0L,
                                if (reclaimedBytes == 0L) {
                                    "No downloaded packages are cached"
                                } else {
                                    "Freed ${formatStorageBytes(reclaimedBytes)} of " +
                                        "downloaded packages"
                                },
                                packageCacheSnapshot.revision + 1,
                            )
                        if (jobRevision == recoveryRevision) {
                            val recoveryResult =
                                if (reclaimedBytes == 0L) {
                                    "No cached downloads could be freed. " +
                                        "Free Android storage, then Review."
                                } else {
                                    "Freed ${formatStorageBytes(reclaimedBytes)} of downloaded " +
                                        "packages. Review before retrying."
                                }
                            require(
                                persistPackageRecovery(
                                    recoveryJobId,
                                    recoveryPackage,
                                    recoveryOperation,
                                    recoveryState,
                                    recoveryFailure,
                                    recoveryResult,
                                ),
                            ) {
                                "Could not save the cache cleanup result"
                            }
                            packageRecoveryHandledJobRevision = recoveryRevision
                            packageRecoveryMessage = recoveryResult
                        }
                        Log.i(TAG, "Cleared $reclaimedBytes bytes from the package cache")
                    } catch (error: Exception) {
                        if (jobRevision == recoveryRevision) {
                            val recoveryResult =
                                boundedJobMessage(
                                    "Cache cleanup failed: " +
                                        (error.message ?: error.javaClass.simpleName) +
                                        ". Restart Archphene, then Review.",
                                )
                            persistPackageRecovery(
                                recoveryJobId,
                                recoveryPackage,
                                recoveryOperation,
                                recoveryState,
                                recoveryFailure,
                                recoveryResult,
                            )
                            packageRecoveryHandledJobRevision = recoveryRevision
                            packageRecoveryMessage = recoveryResult
                        }
                        Log.e(TAG, "Package cache cleanup failed", error)
                    } finally {
                        synchronized(this@ArchpheneRuntimeService) {
                            if (packageThread === Thread.currentThread()) {
                                packageOperationActive = false
                                packageOperationCancelable = false
                                packageCancellationRequested = false
                                packageThread = null
                            }
                        }
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchphenePackageCache",
            )
        packageThread = worker
        worker.start()
        promoteWorkToForeground()
        return true
    }

    private fun holdDebugPackageCacheCleanup() {
        holdDebugPackageWork(PACKAGE_JOB_TEST_CACHE_HOLD_MILLIS)
    }

    private fun holdDebugPackageWorker() {
        holdDebugPackageWork(PACKAGE_JOB_TEST_WORKER_HOLD_MILLIS)
    }

    private fun consumeDebugCatalogRecoveryFixture(): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return false
        }
        val preferences = getSharedPreferences(PACKAGE_JOB_TEST_PREFERENCES, MODE_PRIVATE)
        if (!preferences.getBoolean(PACKAGE_JOB_TEST_CATALOG_RECOVERY, false)) {
            return false
        }
        return preferences
            .edit()
            .remove(PACKAGE_JOB_TEST_CATALOG_RECOVERY)
            .commit()
    }

    private fun holdDebugPackageWork(preference: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }
        val preferences = getSharedPreferences(PACKAGE_JOB_TEST_PREFERENCES, MODE_PRIVATE)
        val holdMillis =
            preferences
                .getLong(preference, 0L)
                .coerceIn(0L, MAX_PACKAGE_JOB_TEST_HOLD_MILLIS)
        if (holdMillis == 0L) {
            return
        }
        preferences.edit().remove(preference).commit()
        Thread.sleep(holdMillis)
    }

    @Synchronized
    private fun requestDebugAurReviewFixture(packageName: String): Boolean {
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !packageName.matches(DEBUG_AUR_PACKAGE_NAME) ||
            readyHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        val sourceDigest = "2".repeat(64)
        val review =
            AurReviewData(
                packageBase = packageName,
                packageName = packageName,
                version = "1.2.3-1",
                description = "A bounded community development tool fixture",
                maintainer = DEBUG_AUR_FIXTURE_MAINTAINER,
                projectUrl = "https://example.invalid/$packageName",
                snapshotPath = "/cgit/aur.git/snapshot/$packageName.tar.gz",
                snapshotCommit = "1".repeat(40),
                snapshotSha256 = "3".repeat(64),
                lastModified = 1_720_000_000L,
                outOfDate = false,
                licenses = arrayOf("MIT"),
                dependencies = arrayOf("glibc", "zlib"),
                requiredPackages = arrayOf(packageName),
                makeDependencies = arrayOf("rust", "cargo"),
                checkDependencies = arrayOf("bats"),
                sources =
                    arrayOf(
                        AurSourceReview(
                            architecture = "",
                            expression = "$packageName-1.2.3.tar.gz::https://example.invalid/source",
                            filename = "$packageName-1.2.3.tar.gz",
                            remoteUrl = "https://example.invalid/source",
                            local = false,
                            checksumAlgorithm = "SHA-256",
                            checksum = sourceDigest,
                            insecureTransport = false,
                        ),
                    ),
                validPgpKeys = arrayOf("0123456789ABCDEF0123456789ABCDEF01234567"),
                buildSteps = arrayOf("prepare()", "build()", "check()", "package()"),
                installScript = "$packageName.install",
                pkgbuild =
                    "pkgname=$packageName\n" +
                        "pkgver=1.2.3\n" +
                        "pkgrel=1\n" +
                        "arch=('x86_64' 'aarch64')\n" +
                        "sha256sums=('$sourceDigest')\n",
                installScriptContents =
                    "post_install() {\n  echo 'fixture installed'\n}\n",
                unverifiedSources = false,
                insecureSources = false,
            )
        val evidence =
            arrayOf(
                AurSourceEvidence(
                    filename = "$packageName-1.2.3.tar.gz",
                    bytes = 2_097_152L,
                    endpoint = "https://example.invalid/source",
                    cached = true,
                    sha256 = sourceDigest,
                ),
            )
        val builder =
            AurBuilderReport(
                packageName = "org.archphene.builder.debug",
                uid = 12_345,
                selinuxContext = "u:r:untrusted_app:s0",
                stagedBytes = 2_097_152L,
                inputManifestSha256 = "4".repeat(64),
                closurePackageCount = 4,
                closureArchiveBytes = 16_777_216L,
                closureSignatureBytes = 2_048L,
                closureManifestSha256 = "5".repeat(64),
                buildRootEntries = 128L,
                buildRootBytes = 33_554_432L,
                runtimeVersion = "makepkg 7.0",
                recipeEntries = 3L,
                recipeBytes = 1_024L,
                recipeSourceBytes = 2_097_152L,
            )
        val buildEnvironment =
            AurBuildEnvironment(
                packages =
                    listOf(
                        ResolvedPayload(
                            repository = "core",
                            name = "base-devel",
                            version = "1-2",
                            filename = "base-devel-1-2-any.pkg.tar.zst",
                            url =
                                "https://example.invalid/" +
                                    "base-devel-1-2-any.pkg.tar.zst",
                            size = 16_777_216L,
                        ),
                    ),
                resolutionBytes = ByteArray(0),
                downloadBytes = 16_777_216L,
                closureManifestSha256 = builder.closureManifestSha256,
                cachedPackages = 1,
                downloadedPackages = 0,
                verified = true,
            )
        retainedAurReview = review
        retainedAurVerifiedBytes = evidence.sumOf(AurSourceEvidence::bytes)
        retainedAurSourceEvidence = evidence
        retainedAurBuilderReport = builder
        clearRetainedAurBuiltPackages()
        publishAurReviewPresentation(
            review,
            retainedAurVerifiedBytes,
            evidence,
            builder,
            buildEnvironment,
        )
        publishAurBuildLogs(
            "==> Making package: $packageName 1.2.3-1\n" +
                "==> Finished making: $packageName 1.2.3-1",
        )
        searchStatus = "Verified fixture evidence · ready to build"
        return true
    }

    @Synchronized
    private fun clearDebugAurReviewFixtureState(packageName: String): Boolean {
        val review = retainedAurReview
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            review?.packageName != packageName ||
            review.maintainer != DEBUG_AUR_FIXTURE_MAINTAINER
        ) {
            return false
        }
        retainedAurReview = null
        retainedAurVerifiedBytes = 0L
        retainedAurSourceEvidence = emptyArray()
        retainedAurBuilderReport = null
        clearRetainedAurBuiltPackages()
        clearAurReviewPresentation()
        searchStatus = "Search the official Arch repositories"
        return true
    }

    @Synchronized
    private fun requestDebugPackagePhaseFixture(
        packageName: String,
        holdMillis: Long,
    ): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            normalized.isEmpty() ||
            normalized.length > 96 ||
            !normalized.all { character ->
                character.isLowerCase() ||
                    character.isDigit() ||
                    character in "@._+-"
            } ||
            holdMillis !in
                MIN_PACKAGE_PHASE_TEST_HOLD_MILLIS..MAX_PACKAGE_JOB_TEST_HOLD_MILLIS ||
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "extra\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                NativeRuntime.JOB_OPERATION_INSTALL,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0L) {
            return false
        }
        jobPersistentId = jobId
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        publishPackageJob(
            normalized,
            NativeRuntime.JOB_OPERATION_INSTALL,
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var recordedPhase = 0
                    var recordedProgress = 0
                    try {
                        Thread.sleep(holdMillis)
                        val states =
                            intArrayOf(
                                NativeRuntime.JOB_RESOLVING,
                                NativeRuntime.JOB_DOWNLOADING,
                                NativeRuntime.JOB_VERIFYING,
                                NativeRuntime.JOB_BUILDING,
                                NativeRuntime.JOB_PUBLISHING,
                                NativeRuntime.JOB_INSTALLING,
                                NativeRuntime.JOB_AWAITING_CONFIRMATION,
                                NativeRuntime.JOB_COMPLETE,
                            )
                        val progress = intArrayOf(5, 25, 50, 65, 78, 88, 95, 100)
                        val messages =
                            arrayOf(
                                "Resolving signed dependency closure",
                                "Downloading verified package archives",
                                "Verifying package signatures",
                                "Building Android launcher",
                                "Publishing verified runtime pack",
                                "Installing Linux package transaction",
                                "Awaiting Android installation confirmation",
                                "Installed $normalized 1.0.0",
                            )
                        states.indices.forEach { index ->
                            throwIfPackageCancelled()
                            if (states[index] == NativeRuntime.JOB_INSTALLING) {
                                packageOperationCancelable = false
                            }
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                states[index],
                                index + 1,
                                progress[index],
                                messages[index],
                                normalized,
                                scratch,
                            )
                            recordedPhase = index + 1
                            recordedProgress = progress[index]
                            Log.i(TAG, "Debug package phase ${jobStateName(states[index])}")
                            if (states[index] != NativeRuntime.JOB_COMPLETE) {
                                Thread.sleep(holdMillis)
                            }
                        }
                    } catch (error: Exception) {
                        val cancelled =
                            error is InterruptedException || packageCancellationRequested
                        if (cancelled) {
                            try {
                                updatePackageJob(
                                    activeHandle,
                                    jobId,
                                    NativeRuntime.JOB_CANCELLED,
                                    recordedPhase,
                                    recordedProgress,
                                    "Cancelled before package mutation",
                                    normalized,
                                    scratch,
                                )
                            } catch (updateError: Exception) {
                                Log.e(TAG, "Debug package phase cancellation failed", updateError)
                            }
                        } else {
                            try {
                                updatePackageJob(
                                    activeHandle,
                                    jobId,
                                    NativeRuntime.JOB_FAILED,
                                    recordedPhase,
                                    recordedProgress,
                                    "Phase presentation fixture failed",
                                    normalized,
                                    scratch,
                                )
                            } catch (updateError: Exception) {
                                Log.e(TAG, "Debug package phase journal failed", updateError)
                            }
                            Log.e(TAG, "Debug package phase fixture failed", error)
                        }
                    } finally {
                        packageOperationCancelable = false
                        packageCancellationRequested = false
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchphenePackagePhases",
            )
        schedulePackageWorker(worker, activeHandle)
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestDebugInterruptedRemovalFixture(
        packageName: String,
        holdMillis: Long,
    ): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            normalized.isEmpty() ||
            normalized.length > 96 ||
            !normalized.all { character ->
                character.isLowerCase() ||
                    character.isDigit() ||
                    character in "@._+-"
            } ||
            holdMillis !in 5_000L..MAX_PACKAGE_JOB_TEST_HOLD_MILLIS ||
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "extra\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackageJob(
                activeHandle,
                NativeRuntime.JOB_OPERATION_REMOVE,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0L) {
            return false
        }
        jobPersistentId = jobId
        packageOperationCancelable = false
        packageOperationActive = true
        publishPackageJob(
            normalized,
            NativeRuntime.JOB_OPERATION_REMOVE,
            NativeRuntime.JOB_QUEUED,
            0,
            "Queued interruption recovery fixture",
        )
        val worker =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    try {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            10,
                            "Inspecting installed package",
                            normalized,
                            scratch,
                        )
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            NativeRuntime.JOB_VERIFYING,
                            2,
                            30,
                            "Validating retained removal baseline",
                            normalized,
                            scratch,
                        )
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            NativeRuntime.JOB_INSTALLING,
                            3,
                            60,
                            "Holding package mutation fixture",
                            normalized,
                            scratch,
                        )
                        Log.i(TAG, "Debug interrupted removal fixture entered mutation")
                        Thread.sleep(holdMillis)
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            NativeRuntime.JOB_FAILED,
                            3,
                            60,
                            "Debug interruption fixture expired",
                            normalized,
                            scratch,
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        packageOperationActive = false
                        packageThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneInterruptedRemovalFixture",
            )
        packageThread = worker
        worker.start()
        promoteWorkToForeground()
        return true
    }

    @Synchronized
    private fun requestPackageCancellation(): Boolean {
        if (!packageOperationActive || !packageOperationCancelable) {
            return false
        }
        packageCancellationRequested = true
        packageOperationCancelable = false
        jobStatus = "Cancellation requested\nFinishing the current safe step"
        jobMessage = "Finishing the current safe step"
        activePackageConnection?.disconnect()
        packageThread?.interrupt()
        return true
    }

    private fun throwIfPackageCancelled() {
        if (packageCancellationRequested || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Package operation cancelled")
        }
    }

    @Synchronized
    private fun enterPackageCommit(): Boolean {
        if (packageCancellationRequested || Thread.currentThread().isInterrupted) {
            return false
        }
        packageOperationCancelable = false
        return true
    }

    private fun withInstalledStatus(
        details: String,
        installedVersion: String,
    ): String {
        val lines = details.lineSequence().toMutableList()
        if (lines.size < 2) {
            return details
        }
        lines[1] =
            if (installedVersion.isEmpty()) {
                "Not installed"
            } else {
                "Installed: $installedVersion"
            }
        return lines.joinToString("\n")
    }

    private fun updatePackageJob(
        activeHandle: Long,
        jobId: Long,
        state: Int,
        phase: Int,
        progress: Int,
        message: String,
        packageName: String,
        scratch: PackageIoScratch,
    ) {
        val safeMessage = boundedJobMessage(message)
        val messageBytes = safeMessage.toByteArray(StandardCharsets.UTF_8)
        scratch.messageBuffer.clear()
        scratch.messageBuffer.put(messageBytes)
        val result =
            NativeRuntime.nativeUpdatePackageJob(
                activeHandle,
                jobId,
                state,
                phase,
                progress,
                scratch.messageBuffer,
                messageBytes.size,
                System.currentTimeMillis(),
                scratch.outputBuffer,
            )
        if (result != 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, result))
        }
        publishPackageJob(
            packageName,
            jobOperation,
            state,
            progress,
            safeMessage,
        )
    }

    private fun publishPackageJob(
        packageName: String,
        operation: Int,
        state: Int,
        progress: Int,
        message: String,
    ) {
        jobPackage = packageName
        jobOperation = operation
        jobState = state
        jobProgress = progress.coerceIn(0, 100)
        jobMessage = message
        jobActivityLabel =
            "${jobOperationName(operation)} · ${jobStateName(state)} · $jobProgress%"
        jobStatus = "$packageName · ${jobStateName(state)} · $jobProgress%\n$message"
        jobRevision++
        if (packageThread != null) {
            mainHandler.post {
                if (packageOperationActive) {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(SESSION_NOTIFICATION_ID, activeForegroundNotification())
                }
            }
        }
    }

    private fun boundedJobMessage(message: String): String {
        val sanitized =
            message
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .ifEmpty { "Package operation" }
        var end = minOf(sanitized.length, 192)
        while (
            end > 0 &&
            sanitized.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > 192
        ) {
            end -= 1
        }
        return sanitized.substring(0, end).ifEmpty { "Package operation" }
    }

    private fun jobStateName(state: Int): String =
        when (state) {
            NativeRuntime.JOB_QUEUED -> "Queued"
            NativeRuntime.JOB_RESOLVING -> "Resolving"
            NativeRuntime.JOB_DOWNLOADING -> "Downloading"
            NativeRuntime.JOB_VERIFYING -> "Verifying"
            NativeRuntime.JOB_PUBLISHING -> "Publishing"
            NativeRuntime.JOB_BUILDING -> "Building"
            NativeRuntime.JOB_INSTALLING -> "Installing"
            NativeRuntime.JOB_AWAITING_CONFIRMATION -> "Awaiting Android confirmation"
            NativeRuntime.JOB_COMPLETE -> "Complete"
            NativeRuntime.JOB_FAILED -> "Failed"
            NativeRuntime.JOB_CANCELLED -> "Cancelled"
            else -> "Unknown"
        }

    private fun jobOperationName(operation: Int): String =
        when (operation) {
            NativeRuntime.JOB_OPERATION_INSTALL -> "Install"
            NativeRuntime.JOB_OPERATION_UPDATE -> "Update"
            NativeRuntime.JOB_OPERATION_REMOVE -> "Remove"
            else -> "Package"
        }

    private fun terminalJobRequiresReview(packageName: String): Boolean =
        packageName == jobPackage &&
            (
                jobState == NativeRuntime.JOB_FAILED ||
                    jobState == NativeRuntime.JOB_CANCELLED
            ) &&
            recoveryReviewedJobRevision != jobRevision

    private fun packageCacheRecoveryReady(): Boolean =
        jobPersistentId > 0L &&
            jobPackage.isNotEmpty() &&
            (
                jobState == NativeRuntime.JOB_FAILED ||
                    jobState == NativeRuntime.JOB_CANCELLED
            ) &&
            packageJobNeedsStorageRecovery() &&
            readyHandle != 0L &&
            !catalogRefreshActive &&
            !packageCacheActive &&
            !searchActive &&
            !packageOperationActive &&
            !commandActive &&
            recoveryReviewedJobRevision != jobRevision &&
            packageRecoveryHandledJobRevision != jobRevision

    private fun packageCatalogRecoveryReady(): Boolean =
        jobPersistentId > 0L &&
            jobPackage.isNotEmpty() &&
            jobState == NativeRuntime.JOB_FAILED &&
            packageJobNeedsCatalogRecovery() &&
            readyHandle != 0L &&
            !catalogRefreshActive &&
            !packageCacheActive &&
            !searchActive &&
            !packageOperationActive &&
            !commandActive &&
            recoveryReviewedJobRevision != jobRevision &&
            packageRecoveryHandledJobRevision != jobRevision

    private fun packageJobNeedsStorageRecovery(): Boolean =
        jobMessage.startsWith("Not enough Linux storage.")

    private fun packageJobNeedsCatalogRecovery(): Boolean =
        jobMessage.startsWith("Package catalog is unavailable or invalid.") ||
            jobMessage.startsWith("Package trust verification failed.")

    private fun persistPackageRecovery(
        jobId: Long,
        packageName: String,
        operation: Int,
        state: Int,
        failure: String,
        result: String,
    ): Boolean =
        getSharedPreferences(PACKAGE_RECOVERY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putLong(PACKAGE_RECOVERY_JOB_ID, jobId)
            .putString(PACKAGE_RECOVERY_PACKAGE, packageName)
            .putInt(PACKAGE_RECOVERY_OPERATION, operation)
            .putInt(PACKAGE_RECOVERY_STATE, state)
            .putString(PACKAGE_RECOVERY_FAILURE, failure)
            .putString(PACKAGE_RECOVERY_RESULT, result)
            .commit()

    private fun restorePackageRecovery() {
        val preferences = getSharedPreferences(PACKAGE_RECOVERY_PREFERENCES, MODE_PRIVATE)
        val result = preferences.getString(PACKAGE_RECOVERY_RESULT, null) ?: return
        if (
            preferences.getLong(PACKAGE_RECOVERY_JOB_ID, Long.MIN_VALUE) != jobPersistentId ||
            preferences.getString(PACKAGE_RECOVERY_PACKAGE, null) != jobPackage ||
            preferences.getInt(PACKAGE_RECOVERY_OPERATION, Int.MIN_VALUE) != jobOperation ||
            preferences.getInt(PACKAGE_RECOVERY_STATE, Int.MIN_VALUE) != jobState ||
            preferences.getString(PACKAGE_RECOVERY_FAILURE, null) != jobMessage
        ) {
            return
        }
        packageRecoveryHandledJobRevision = jobRevision
        packageRecoveryMessageRevision = jobRevision
        packageRecoveryMessage = boundedJobMessage(result)
    }

    private fun downloadPackagePayload(
        activeHandle: Long,
        payload: ResolvedPayload,
        signature: Boolean,
        scratch: PackageIoScratch,
    ) {
        val filenameBytes = payload.filename.toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(filenameBytes)
        val descriptor =
            NativeRuntime.nativeBeginPackageDownload(
                activeHandle,
                scratch.requestBuffer,
                filenameBytes.size,
                payload.size,
                signature,
                scratch.outputBuffer,
            )
        if (descriptor < 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, descriptor))
        }
        var finishAttempted = false
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor),
            ).use { output ->
                val expectedEndpoint = if (signature) "${payload.url}.sig" else payload.url
                val endpoint = URL(expectedEndpoint)
                val expectedHost =
                    when (Build.SUPPORTED_ABIS.firstOrNull()) {
                        "x86_64" -> "geo.mirror.pkgbuild.com"
                        "arm64-v8a" -> "ca.us.mirror.archlinuxarm.org"
                        else -> throw IllegalStateException("Unsupported Android ABI")
                    }
                if (
                    endpoint.toString() != expectedEndpoint ||
                    endpoint.protocol != "https" ||
                    endpoint.host != expectedHost ||
                    endpoint.userInfo != null ||
                    endpoint.port != -1
                ) {
                    throw SecurityException("Rust supplied an invalid package endpoint")
                }
                val maximumBytes = if (signature) 1024L * 1024 else payload.size
                val connection = endpoint.openConnection() as HttpsURLConnection
                activePackageConnection = connection
                try {
                    throwIfPackageCancelled()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                        throw IllegalStateException(
                            "Package server returned HTTP ${connection.responseCode}",
                        )
                    }
                    val declaredLength = connection.contentLengthLong
                    if (
                        declaredLength == 0L ||
                        declaredLength > maximumBytes ||
                        !signature && declaredLength >= 0 && declaredLength != payload.size
                    ) {
                        throw SecurityException("Package object has an invalid size")
                    }
                    connection.inputStream.use { input ->
                        var total = 0L
                        while (true) {
                            throwIfPackageCancelled()
                            val count = input.read(scratch.transferBuffer)
                            if (count < 0) {
                                break
                            }
                            total += count
                            if (total > maximumBytes) {
                                throw SecurityException("Package object exceeds its limit")
                            }
                            output.write(scratch.transferBuffer, 0, count)
                        }
                        if (
                            total == 0L ||
                            !signature && total != payload.size ||
                            declaredLength >= 0 && total != declaredLength
                        ) {
                            throw SecurityException("Package object transfer is incomplete")
                        }
                    }
                } finally {
                    if (activePackageConnection === connection) {
                        activePackageConnection = null
                    }
                    connection.disconnect()
                }
            }
            scratch.outputBuffer.position(0)
            val published =
                NativeRuntime.nativeFinishPackageDownload(
                    activeHandle,
                    true,
                    scratch.outputBuffer,
                )
            finishAttempted = true
            if (published <= 0) {
                throw IllegalStateException(readNativeMessage(scratch.outputBuffer, published))
            }
        } finally {
            if (!finishAttempted) {
                scratch.outputBuffer.position(0)
                NativeRuntime.nativeFinishPackageDownload(
                    activeHandle,
                    false,
                    scratch.outputBuffer,
                )
            }
        }
    }

    private fun verifyPackagePayload(
        activeHandle: Long,
        payload: ResolvedPayload,
        scratch: PackageIoScratch,
    ) {
        val requestBytes =
            "${payload.filename}\t${payload.name}\t${payload.version}"
                .toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(requestBytes)
        val result =
            NativeRuntime.nativeVerifyPackage(
                activeHandle,
                scratch.requestBuffer,
                requestBytes.size,
                payload.size,
                scratch.outputBuffer,
            )
        if (result <= 0) {
            throw SecurityException(readNativeMessage(scratch.outputBuffer, result))
        }
    }

    private fun isCachedPackageValid(
        activeHandle: Long,
        payload: ResolvedPayload,
        scratch: PackageIoScratch,
    ): Boolean {
        val requestBytes =
            "${payload.filename}\t${payload.name}\t${payload.version}"
                .toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(requestBytes)
        val result =
            NativeRuntime.nativeVerifyPackage(
                activeHandle,
                scratch.requestBuffer,
                requestBytes.size,
                payload.size,
                scratch.outputBuffer,
            )
        if (result > 0) {
            return true
        }
        val diagnostic = readNativeMessage(scratch.outputBuffer, result)
        if (!diagnostic.contains("No such file")) {
            Log.w(TAG, "Rejected invalid cached package ${payload.filename}: $diagnostic")
        }
        return false
    }

    @Synchronized
    private fun requestLinuxCommand(commandLine: String): Boolean {
        val activeHandle = readyHandle
        val tokens = splitCommandLine(commandLine)
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            shellActive ||
            tokens.isEmpty()
        ) {
            if (tokens.isEmpty()) {
                commandStatus = "Enter a command and optional whitespace-separated arguments"
            }
            return false
        }
        val encoded = tokens.map { token -> token.toByteArray(StandardCharsets.UTF_8) }
        val requestLength =
            encoded.sumOf { bytes -> bytes.size } + (encoded.size - 1).coerceAtLeast(0)
        if (requestLength > NativeRuntime.COMMAND_REQUEST_LIMIT) {
            commandStatus = "Command request is too large"
            return false
        }
        val requestBuffer = ByteBuffer.allocateDirect(requestLength)
        encoded.forEachIndexed { index, bytes ->
            if (index != 0) {
                requestBuffer.put(0.toByte())
            }
            requestBuffer.put(bytes)
        }
        commandActive = true
        shellWasStarted = false
        directCommandStarted = true
        commandStatus = "Running ${tokens.first()}"
        commandThread =
            Thread(
                {
                    try {
                        val outputBuffer =
                            ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                        val outputLength =
                            NativeRuntime.nativeRunCommand(
                                activeHandle,
                                requestBuffer,
                                requestLength,
                                outputBuffer,
                            )
                        if (outputLength < 0) {
                            throw IllegalStateException(
                                readNativeMessage(outputBuffer, outputLength),
                            )
                        }
                        val bytes = ByteArray(outputLength)
                        outputBuffer.position(0)
                        outputBuffer.get(bytes)
                        val separator = bytes.indexOf('\n'.code.toByte())
                        if (separator <= 0) {
                            throw IllegalStateException("Linux command returned an invalid result")
                        }
                        val exitCode =
                            String(bytes, 0, separator, StandardCharsets.US_ASCII).toIntOrNull()
                                ?: throw IllegalStateException(
                                    "Linux command returned an invalid exit status",
                                )
                        val text =
                            sanitizeCommandOutput(
                                String(
                                    bytes,
                                    separator + 1,
                                    bytes.size - separator - 1,
                                    StandardCharsets.UTF_8,
                                ),
                            )
                        commandStatus =
                            if (text.isEmpty()) {
                                "Exited $exitCode"
                            } else {
                                "Exited $exitCode\n$text"
                            }
                        Log.i(TAG, "Linux command ${tokens.first()} exited $exitCode")
                    } catch (error: Exception) {
                        commandStatus =
                            "Command failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "Linux command failed", error)
                    } finally {
                        commandActive = false
                        commandThread = null
                        stopWhenUnobservedAndIdle()
                    }
                },
                "ArchpheneCommand",
            ).also(Thread::start)
        promoteWorkToForeground()
        return true
    }

    private fun splitCommandLine(commandLine: String): List<String> {
        val normalized = commandLine.trim()
        if (normalized.isEmpty() || normalized.length > NativeRuntime.COMMAND_REQUEST_LIMIT) {
            return emptyList()
        }
        val result = ArrayList<String>(8)
        var start = -1
        for (index in normalized.indices) {
            if (normalized[index].isWhitespace()) {
                if (start >= 0) {
                    result.add(normalized.substring(start, index))
                    if (result.size > 33) {
                        return emptyList()
                    }
                    start = -1
                }
            } else if (start < 0) {
                start = index
            }
        }
        if (start >= 0) {
            result.add(normalized.substring(start))
        }
        return result
    }

    private fun sanitizeCommandOutput(output: String): String {
        val sanitized = StringBuilder(output.length.coerceAtMost(4096))
        var index = 0
        while (index < output.length && sanitized.length < 4096) {
            val character = output[index++]
            if (character == '\u001b') {
                if (index < output.length) {
                    when (output[index++]) {
                        '[' -> {
                            while (index < output.length) {
                                val value = output[index++]
                                if (value.code in 0x40..0x7e) {
                                    break
                                }
                            }
                        }
                        ']' -> {
                            while (index < output.length) {
                                val value = output[index++]
                                if (value == '\u0007') {
                                    break
                                }
                                if (
                                    value == '\u001b' &&
                                    index < output.length &&
                                    output[index] == '\\'
                                ) {
                                    index++
                                    break
                                }
                            }
                        }
                    }
                }
                continue
            }
            if (character == '\n' || character == '\t' || character.code >= 0x20) {
                sanitized.append(character)
            }
        }
        return sanitized.toString().trimEnd()
    }

    @Synchronized
    private fun requestShellSelection(index: Int): Boolean {
        if (shellActive || index !in shellChoices.indices) {
            return false
        }
        if (selectedShellIndex == index) {
            return true
        }
        val saved =
            getSharedPreferences(SHELL_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(SHELL_PREFERENCE_ID, shellChoices[index].id)
                .commit()
        if (!saved) {
            return false
        }
        selectedShellIndex = index
        return true
    }

    @Synchronized
    private fun requestSharedShellToggle(): Boolean {
        if (shellActive) {
            stopSharedShell(waitForWorker = false)
            return true
        }
        val activeHandle = readyHandle
        val selectedShell = shellChoices.getOrNull(selectedShellIndex)
        if (
            activeHandle == 0L ||
            selectedShell == null ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        shellOutput.clear()
        shellInput.clear()
        shellStopRequested = false
        shellWasStarted = true
        shellActive = true
        shellPhase = "Starting shared shell"
        promoteSessionToForeground()
        shellThread =
            Thread(
                { runSharedShell(activeHandle, selectedShell) },
                "ArchpheneShell",
            ).also(Thread::start)
        return true
    }

    private fun requestShellInput(commandLine: String): Boolean {
        if (
            !shellActive ||
            shellHandle == 0L ||
            shellStopRequested ||
            commandLine.isEmpty() ||
            commandLine.length > SHELL_INPUT_CHARACTERS ||
            commandLine.indexOf('\u0000') >= 0
        ) {
            return false
        }
        val bytes = commandLine.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size + 1 > SHELL_INPUT_BYTES || !shellInput.offerLine(bytes)) {
            shellPhase = "Shared shell input queue is full"
            return false
        }
        val result = NativeRuntime.nativeWakePty(readyHandle, shellHandle)
        if (result < 0) {
            shellPhase = "Could not wake the shared shell"
            return false
        }
        return result == 0
    }

    private fun requestTerminalInput(
        source: ByteArray,
        length: Int,
    ): Boolean {
        if (
            !shellActive ||
            shellHandle == 0L ||
            shellStopRequested ||
            length !in 1..SHELL_INPUT_BYTES ||
            length > source.size
        ) {
            return false
        }
        if (!shellInput.offer(source, length)) {
            shellPhase = "Shared shell input queue is full"
            return false
        }
        val result = NativeRuntime.nativeWakePty(readyHandle, shellHandle)
        if (result < 0) {
            shellPhase = "Could not wake the shared shell"
            return false
        }
        return result == 0
    }

    private fun requestShellResize(
        rows: Int,
        columns: Int,
    ): Boolean {
        if (
            rows !in MIN_SHELL_ROWS..MAX_SHELL_ROWS ||
            columns !in MIN_SHELL_COLUMNS..MAX_SHELL_COLUMNS
        ) {
            return false
        }
        val activeHandle: Long
        val activePty: Long
        synchronized(this) {
            if (rows == shellRows && columns == shellColumns) {
                return true
            }
            shellRows = rows
            shellColumns = columns
            activeHandle = readyHandle
            activePty = shellHandle
        }
        if (activeHandle == 0L || activePty == 0L) {
            return true
        }
        val result =
            NativeRuntime.nativeResizePty(
                activeHandle,
                activePty,
                rows,
                columns,
            )
        if (result == 0) {
            shellTerminalRevision.incrementAndGet()
        }
        return result == 0
    }

    private fun runSharedShell(
        activeHandle: Long,
        selectedShell: ShellChoice,
    ) {
        var ptyHandle = 0L
        var exitStatus: Int? = null
        var failure: Exception? = null
        val readBuffer = ByteBuffer.allocateDirect(SHELL_IO_BYTES)
        val writeBuffer = ByteBuffer.allocateDirect(SHELL_IO_BYTES)
        val readBytes = ByteArray(SHELL_IO_BYTES)
        val writeBytes = ByteArray(SHELL_IO_BYTES)
        try {
            val initialRows = shellRows
            val initialColumns = shellColumns
            val requestBytes = selectedShell.requestBytes
            val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size)
            requestBuffer.put(requestBytes)
            val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
            ptyHandle =
                NativeRuntime.nativeOpenPty(
                    activeHandle,
                    requestBuffer,
                    requestBytes.size,
                    initialRows,
                    initialColumns,
                    outputBuffer,
                )
            if (ptyHandle <= 0) {
                throw IllegalStateException(readNativeMessage(outputBuffer, ptyHandle))
            }
            synchronized(this) {
                if (shellStopRequested || readyHandle != activeHandle) {
                    throw InterruptedException("Shared shell start cancelled")
                }
                shellHandle = ptyHandle
                shellPhase = "Shared shell ready"
                shellTerminalRevision.incrementAndGet()
            }
            mainHandler.post(::updateSessionNotification)
            Log.i(TAG, "Shared ${selectedShell.label} session started")
            while (!shellStopRequested) {
                val queued = shellInput.peek(writeBytes)
                if (queued != 0) {
                    writeBuffer.clear()
                    writeBuffer.put(writeBytes, 0, queued)
                    val written =
                        NativeRuntime.nativePtyIo(
                            activeHandle,
                            ptyHandle,
                            true,
                            writeBuffer,
                            queued,
                        )
                    if (written < 0) {
                        throw IllegalStateException("Could not write to the shared shell")
                    }
                    if (written != 0) {
                        shellInput.discard(written)
                    }
                }

                var readBatches = 0
                while (readBatches < SHELL_READ_BATCHES) {
                    readBatches++
                    readBuffer.clear()
                    val read =
                        NativeRuntime.nativePtyIo(
                            activeHandle,
                            ptyHandle,
                            false,
                            readBuffer,
                            SHELL_IO_BYTES,
                        )
                    if (read < 0) {
                        throw IllegalStateException("Could not read from the shared shell")
                    }
                    if (read == 0) {
                        break
                    }
                    readBuffer.position(0)
                    readBuffer.get(readBytes, 0, read)
                    shellOutput.append(readBytes, read)
                    shellTerminalRevision.incrementAndGet()
                }
                val encodedStatus =
                    NativeRuntime.nativePtyExitStatus(activeHandle, ptyHandle)
                if (encodedStatus < 0) {
                    throw IllegalStateException("Could not read the shared shell exit status")
                }
                if (encodedStatus and 1L != 0L) {
                    exitStatus = (encodedStatus ushr 1).toInt()
                    break
                }
                if (!shellStopRequested) {
                    val writePending = shellInput.peek(writeBytes) != 0
                    val events =
                        NativeRuntime.nativeWaitPty(
                            activeHandle,
                            ptyHandle,
                            writePending,
                        )
                    val knownEvents =
                        NativeRuntime.PTY_EVENT_READABLE or
                            NativeRuntime.PTY_EVENT_WRITABLE or
                            NativeRuntime.PTY_EVENT_HANGUP or
                            NativeRuntime.PTY_EVENT_WOKEN
                    if (events <= 0 || events and knownEvents != events) {
                        throw IllegalStateException("Could not wait for shared shell activity")
                    }
                }
            }
        } catch (error: InterruptedException) {
            if (!shellStopRequested) {
                failure = error
            }
        } catch (error: Exception) {
            if (!shellStopRequested) {
                failure = error
            }
        } finally {
            if (ptyHandle > 0) {
                NativeRuntime.nativeClosePty(activeHandle, ptyHandle)
            }
            synchronized(this) {
                shellHandle = 0L
                shellTerminalRevision.incrementAndGet()
                shellInput.clear()
                shellActive = false
                shellStopRequested = false
                shellThread = null
                shellPhase =
                    when {
                        failure != null ->
                            "Shared shell failed: ${failure.message ?: failure.javaClass.simpleName}"
                        exitStatus != null -> "Shared shell exited $exitStatus"
                        else -> "Shared shell stopped"
                    }
            }
            if (failure != null) {
                Log.e(TAG, "Shared ${selectedShell.label} session failed", failure)
            } else {
                Log.i(
                    TAG,
                    "Shared ${selectedShell.label} session finished with status " +
                        "${exitStatus ?: "stopped"}",
                )
            }
            mainHandler.post {
                removeSessionNotification()
                stopIfUnobservedAndIdle()
            }
        }
    }

    private fun hasActiveRuntimeWork(): Boolean =
        bootstrapActive ||
            launcherPublisherActive.get() ||
            launcherDecisionActive.get() ||
            launcherReviewActive.get() ||
            catalogRefreshActive ||
            packageCacheActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            shellActive ||
            storageImportActive ||
            folderOperationActive

    private fun stopWhenUnobservedAndIdle() {
        mainHandler.post {
            reconcileForegroundNotification()
            stopIfUnobservedAndIdle()
        }
    }

    private fun stopIfUnobservedAndIdle() {
        if (boundClients == 0 && !hasActiveRuntimeWork()) {
            stopSelf()
        }
    }

    private fun stopSharedShell(waitForWorker: Boolean) {
        val worker: Thread?
        val activeHandle: Long
        val ptyHandle: Long
        synchronized(this) {
            if (!shellActive) {
                return
            }
            shellStopRequested = true
            shellPhase = "Stopping shared shell"
            worker = shellThread
            activeHandle = readyHandle
            ptyHandle = shellHandle
        }
        if (activeHandle != 0L && ptyHandle != 0L) {
            NativeRuntime.nativeWakePty(activeHandle, ptyHandle)
        }
        if (waitForWorker && worker !== Thread.currentThread()) {
            try {
                worker?.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun sharedShellDisplayStatus(): String {
        val output = sanitizeCommandOutput(shellOutput.snapshotTail(SHELL_DISPLAY_BYTES))
        return if (output.isEmpty()) {
            shellPhase
        } else {
            "$shellPhase\n$output"
        }
    }

    private fun readLatestPackageJob(activeHandle: Long): String {
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val length = NativeRuntime.nativeReadLatestPackageJob(activeHandle, outputBuffer)
        if (length < 0) {
            return "Package journal unavailable: ${readNativeMessage(outputBuffer, length)}"
        }
        if (length == 0) {
            jobPersistentId = 0L
            jobPackage = ""
            jobRepository = ""
            jobOperation = 0
            jobState = 0
            jobProgress = 0
            jobMessage = ""
            jobActivityLabel = ""
            jobRevision++
            return "No package transaction"
        }
        val bytes = ByteArray(length)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        val fields = String(bytes, StandardCharsets.UTF_8).trimEnd().split('\t', limit = 9)
        if (fields.size != 9) {
            return "Package journal returned an invalid record"
        }
        val id = fields[0].toLongOrNull() ?: return "Package journal returned invalid identifier"
        if (id <= 0L) {
            return "Package journal returned invalid identifier"
        }
        val operation =
            fields[1].toIntOrNull() ?: return "Package journal returned invalid operation"
        val state = fields[2].toIntOrNull() ?: return "Package journal returned invalid state"
        val progress = fields[4].toIntOrNull() ?: return "Package journal returned invalid progress"
        jobPersistentId = id
        jobRepository = fields[6]
        publishPackageJob(fields[7], operation, state, progress, fields[8])
        return jobStatus
    }
}
