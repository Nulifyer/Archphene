package org.archphene.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.archphene.app.MainActivity
import org.archphene.app.R

class ArchpheneRuntimeService : Service() {
    inner class LocalBinder : Binder() {
        val runtimeHandle: Long
            get() = readyHandle

        val packageCatalogStatus: String
            get() = catalogStatus

        val packageSearchStatus: String
            get() = searchStatus

        val packageJobStatus: String
            get() = jobStatus

        val packagePrimaryActionLabel: String
            get() = primaryActionLabel

        val packagePrimaryActionAvailable: Boolean
            get() =
                lastResolvedPackage.isNotEmpty() &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val packageRemoveAvailable: Boolean
            get() =
                removeAvailable &&
                    !searchActive &&
                    !packageOperationActive &&
                    !commandActive

        val packageCancellationAvailable: Boolean
            get() = packageOperationActive && packageOperationCancelable

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

        val folderGrantRunning: Boolean
            get() = folderOperationActive

        val linuxCommandStatus: String
            get() =
                if (shellActive) {
                    shellPhase
                } else if (shellWasStarted) {
                    sharedShellDisplayStatus()
                } else {
                    commandStatus
                }

        val linuxCommandAvailable: Boolean
            get() =
                if (shellActive) {
                    shellHandle != 0L && !shellStopRequested
                } else {
                    readyHandle != 0L &&
                        !catalogRefreshActive &&
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

        fun searchPackages(query: String): Boolean = requestPackageSearch(query)

        fun resolvePackage(packageName: String): Boolean =
            requestPackageResolution(packageName)

        fun installPackage(packageName: String): Boolean =
            requestPackageInstall(packageName)

        fun removePackage(packageName: String): Boolean =
            requestPackageRemoval(packageName)

        fun cancelPackageOperation(): Boolean = requestPackageCancellation()

        fun importAndroidDocument(uri: Uri): Boolean = requestDocumentImport(uri)

        fun connectAndroidFolder(
            uri: Uri,
            flags: Int,
        ): Boolean = requestFolderGrant(uri, flags)

        fun disconnectAndroidFolder(): Boolean = requestFolderDisconnect()

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

        fun toggleSharedShell(): Boolean = requestSharedShellToggle()
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handle = 0L
    @Volatile private var readyHandle = 0L
    private var bootstrapThread: Thread? = null
    private var catalogThread: Thread? = null
    private var packageThread: Thread? = null
    private var commandThread: Thread? = null
    private var shellThread: Thread? = null
    private var storageThread: Thread? = null
    private var boundClients = 0
    @Volatile private var catalogRefreshActive = false
    @Volatile private var catalogStatus = "Package catalog not downloaded"
    @Volatile private var searchActive = false
    @Volatile private var searchStatus = "Search the official Arch repositories"
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
    @Volatile private var folderStatus = "Loading Android folder access…"
    @Volatile private var shellActive = false
    @Volatile private var shellWasStarted = false
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
    @Volatile private var lastResolvedPackage = ""
    @Volatile private var lastResolvedRepository = ""
    @Volatile private var lastResolvedInstalledVersion = ""
    @Volatile private var lastResolvedAvailableVersion = ""
    @Volatile private var primaryActionLabel = "Install"
    @Volatile private var removeAvailable = false
    @Volatile private var commandStatus = "Run an installed Linux command"
    private val shellOutput = BoundedByteRing(SHELL_SCROLLBACK_BYTES)
    private val shellInput = FixedByteQueue(SHELL_INPUT_BYTES)
    private val shellTerminalDamageBuffer: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer
            .allocateDirect(NativeRuntime.TERMINAL_DAMAGE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
    }

    private data class ResolvedPayload(
        val repository: String,
        val name: String,
        val version: String,
        val filename: String,
        val url: String,
        val size: Long,
    )

    private data class ShellChoice(
        val id: String,
        val label: String,
        val requestBytes: ByteArray,
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
        startBootstrap(handle)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP_SHELL) {
            stopSharedShell(waitForWorker = false)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        boundClients++
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!shellActive && !storageImportActive && !folderOperationActive) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSharedShell(waitForWorker = true)
        removeSessionNotification()
        val activeHandle = handle
        handle = 0L
        readyHandle = 0L
        bootstrapThread?.interrupt()
        bootstrapThread = null
        catalogThread?.interrupt()
        catalogThread = null
        packageCancellationRequested = true
        activePackageConnection?.disconnect()
        packageThread?.interrupt()
        packageThread = null
        commandThread?.interrupt()
        commandThread = null
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
        private const val SHELL_PREFERENCES = "terminal"
        private const val SHELL_PREFERENCE_ID = "shared_shell_id"
        private const val SESSION_NOTIFICATION_ID = 0x4152
        private const val SESSION_NOTIFICATION_CHANNEL = "archphene_linux_sessions"
        private const val ACTION_STOP_SHELL = "org.archphene.app.action.STOP_SHARED_SHELL"
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
        private const val MAX_STORAGE_URI_BYTES = 4 * 1024
        private const val MAX_STORAGE_REQUEST_BYTES = 4 * 1024
        private const val MAX_STORAGE_NAME_BYTES = 255
        private const val MAX_FOLDER_LABEL_BYTES = 128
        private const val MAX_STORAGE_IMPORT_BYTES = 16L * 1024 * 1024 * 1024
        private val PROCESS_STORAGE_ACTIVE = AtomicBoolean()
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
        if (savedUri == null) {
            folderConnected = false
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
            folderStatus = connectedFolderStatus(savedLabel, permission.second)
            return
        }
        folderConnected = false
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
            if (
                !preferences
                    .edit()
                    .putString(FOLDER_URI, uri.toString())
                    .putString(FOLDER_LABEL, label)
                    .putString(FOLDER_STATE, FOLDER_CONNECTED)
                    .commit()
            ) {
                throw IllegalStateException("Could not save the Android folder grant")
            }
            if (previousUri != null && previousUri != uri) {
                runCatching { releaseFolderPermission(previousUri) }
                    .onFailure { error ->
                        Log.w(TAG, "Could not release replaced Android folder grant", error)
                    }
            }
            folderConnected = true
            folderStatus = connectedFolderStatus(label, permission.second)
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
        PROCESS_STORAGE_ACTIVE.set(false)
        storageThread = null
        mainHandler.post {
            if (!shellActive && boundClients == 0) {
                stopSelf()
            }
        }
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
    ): String =
        if (writable) {
            "Android folder: $label · read/write"
        } else {
            "Android folder: $label · read-only"
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
                        mainHandler.post {
                            if (!shellActive && boundClients == 0) {
                                stopSelf()
                            }
                        }
                    }
                },
                "ArchpheneImport",
            )
        storageThread = worker
        return try {
            worker.start()
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
        val openIntent =
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val openAction =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent = Intent(this, ArchpheneRuntimeService::class.java).setAction(ACTION_STOP_SHELL)
        val stopAction =
            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
            .setContentIntent(openAction)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.session_notification_stop),
                    stopAction,
                ).build(),
            )
            .build()
    }

    private fun promoteSessionToForeground() {
        val notification = sessionNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SESSION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SESSION_NOTIFICATION_ID, notification)
        }
    }

    private fun updateSessionNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(SESSION_NOTIFICATION_ID, sessionNotification())
    }

    private fun removeSessionNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startBootstrap(activeHandle: Long) {
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
                        val packageVersion = preparePackageRuntime(activeHandle)
                        refreshShellChoices(activeHandle)
                        jobStatus = readLatestPackageJob(activeHandle)
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

    @Synchronized
    private fun requestCatalogRefresh(): Boolean {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            commandActive
        ) {
            return false
        }
        catalogRefreshActive = true
        catalogThread =
            Thread(
                {
                    try {
                        catalogStatus = "Refreshing core package catalog"
                        downloadCatalog(activeHandle, NativeRuntime.CATALOG_CORE)
                        catalogStatus = "Refreshing extra package catalog"
                        downloadCatalog(activeHandle, NativeRuntime.CATALOG_EXTRA)
                        catalogStatus = "Package catalog ready"
                        Log.i(TAG, "Official package catalogs refreshed")
                    } catch (error: Exception) {
                        catalogStatus =
                            "Catalog refresh failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "Package catalog refresh failed", error)
                    } finally {
                        catalogRefreshActive = false
                        catalogThread = null
                    }
                },
                "ArchpheneCatalog",
            ).also(Thread::start)
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
        buffer.position(0)
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        val terminator = bytes.indexOf(0)
        val length = if (terminator >= 0) terminator else bytes.size
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }

    @Synchronized
    private fun requestPackageSearch(query: String): Boolean {
        val normalized = query.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
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
            return false
        }
        searchActive = true
        searchStatus = "Searching for $normalized"
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
                    } else {
                        val bytes = ByteArray(outputLength)
                        outputBuffer.position(0)
                        outputBuffer.get(bytes)
                        searchStatus =
                            String(bytes, StandardCharsets.UTF_8)
                                .lineSequence()
                                .mapNotNull { line ->
                                    val fields = line.split('\t', limit = 4)
                                    if (fields.size != 4) {
                                        null
                                    } else {
                                        "${fields[0]}/${fields[1]} ${fields[2]}\n" +
                                            "  ${fields[3]}"
                                    }
                                }.joinToString("\n")
                                .ifEmpty { "No official packages match $normalized" }
                    }
                } catch (error: Exception) {
                    searchStatus =
                        "Package search failed: ${error.message ?: error.javaClass.simpleName}"
                    Log.e(TAG, "Package search failed", error)
                } finally {
                    searchActive = false
                }
            },
            "ArchpheneSearch",
        ).start()
        return true
    }

    @Synchronized
    private fun requestPackageResolution(packageName: String): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
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
                    primaryActionLabel =
                        when {
                            installedVersion.isEmpty() -> "Install"
                            installedVersion == resolvedTarget.version -> "Verify"
                            else -> "Update"
                        }
                    removeAvailable = installedVersion.isNotEmpty()
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
                            append(" packages · ")
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
                    searchStatus =
                        "Package resolution failed: ${error.message ?: error.javaClass.simpleName}"
                    Log.e(TAG, "Package resolution failed", error)
                } finally {
                    searchActive = false
                }
            },
            "ArchpheneResolve",
        ).start()
        return true
    }

    private fun resolvePayloads(
        activeHandle: Long,
        packageName: String,
    ): List<ResolvedPayload> {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val packageBuffer = ByteBuffer.allocateDirect(packageBytes.size)
        packageBuffer.put(packageBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativeResolvePackage(
                activeHandle,
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
                if (packages.size > 256) {
                    throw IllegalStateException("Package closure exceeds its limit")
                }
            }
        if (packages.none { payload -> payload.name == packageName }) {
            throw IllegalStateException("Resolved packages omit the requested target")
        }
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
    private fun requestPackageInstall(packageName: String): Boolean {
        val normalized = packageName.trim()
        val repository = lastResolvedRepository
        val installedVersion = lastResolvedInstalledVersion
        val availableVersion = lastResolvedAvailableVersion
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
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
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        jobStatus = "$normalized · Queued · 0%\nQueued"
        packageThread =
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
                                boundedJobMessage(
                                    if (cancelled) {
                                        "Cancelled before package mutation"
                                    } else {
                                        "Install failed: " +
                                            (error.message ?: error.javaClass.simpleName)
                                    },
                                ),
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
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
                    }
                },
                "ArchpheneInstall",
            ).also(Thread::start)
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

    @Synchronized
    private fun requestPackageRemoval(packageName: String): Boolean {
        val normalized = packageName.trim()
        val repository = lastResolvedRepository
        val installedVersion = lastResolvedInstalledVersion
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            commandActive ||
            normalized != lastResolvedPackage ||
            installedVersion.isEmpty() ||
            (repository != "core" && repository != "extra")
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
        packageCancellationRequested = false
        packageOperationCancelable = true
        packageOperationActive = true
        jobStatus = "$normalized · Queued · 0%\nQueued"
        packageThread =
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
                        refreshShellChoices(activeHandle)
                        lastResolvedInstalledVersion = ""
                        primaryActionLabel = "Install"
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
                                boundedJobMessage(
                                    if (cancelled) {
                                        "Cancelled before package mutation"
                                    } else {
                                        "Removal failed: " +
                                            (error.message ?: error.javaClass.simpleName)
                                    },
                                ),
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
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
                    }
                },
                "ArchpheneRemove",
            ).also(Thread::start)
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
        jobStatus =
            "$packageName · ${jobStateName(state)} · $progress%\n$safeMessage"
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
            NativeRuntime.JOB_INSTALLING -> "Installing"
            NativeRuntime.JOB_COMPLETE -> "Complete"
            NativeRuntime.JOB_FAILED -> "Failed"
            NativeRuntime.JOB_CANCELLED -> "Cancelled"
            else -> "Unknown"
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
                    }
                },
                "ArchpheneCommand",
            ).also(Thread::start)
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
                if (boundClients == 0) {
                    stopSelf()
                }
            }
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
            return "No package transaction"
        }
        val bytes = ByteArray(length)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        val fields = String(bytes, StandardCharsets.UTF_8).trimEnd().split('\t', limit = 9)
        if (fields.size != 9) {
            return "Package journal returned an invalid record"
        }
        val state = fields[2].toIntOrNull() ?: return "Package journal returned invalid state"
        val progress = fields[4].toIntOrNull() ?: return "Package journal returned invalid progress"
        return "${fields[7]} · ${jobStateName(state)} · $progress%\n${fields[8]}"
    }
}
