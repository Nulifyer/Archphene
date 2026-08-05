package org.archphene.app.launcher

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.archphene.app.boundedUtf8Text

internal data class LauncherPortalSaveResult(
    val descriptor: ParcelFileDescriptor?,
    val displayName: String,
    val cancelled: Boolean,
)

internal data class LauncherPortalOpenDocument(
    val descriptor: ParcelFileDescriptor,
    val displayName: String,
    val writable: Boolean,
)

internal data class LauncherPortalOpenResult(
    val documents: List<LauncherPortalOpenDocument>,
    val cancelled: Boolean,
)

internal data class LauncherPortalDirectoryResult(
    val descriptor: ParcelFileDescriptor?,
    val displayName: String,
    val cancelled: Boolean,
)

internal data class PortalCloseReadiness(
    val brokerStopped: Boolean,
    val clientsStopped: Boolean,
    val importsStopped: Boolean,
    val mirrorStopped: Boolean,
    val processesStopped: Boolean,
    val drainersStopped: Boolean,
    val saveFinalizerStopped: Boolean,
    val directoryCancelStopped: Boolean,
    val savesFinalized: Boolean,
) {
    val canCleanup: Boolean
        get() =
            brokerStopped &&
                clientsStopped &&
                importsStopped &&
                mirrorStopped &&
                processesStopped &&
                drainersStopped &&
                saveFinalizerStopped &&
                directoryCancelStopped &&
                savesFinalized
}

internal fun canDiscardPortalStaging(
    copyRequired: Boolean,
    copySucceeded: Boolean,
    recovered: Boolean,
): Boolean = !copyRequired || copySucceeded || recovered

internal class PortalRecoveryCapacityException(message: String) : IllegalStateException(message)

/**
 * One private XDG portal frontend for one authenticated visible launcher.
 *
 * Android owns the destination descriptor. Linux receives only an app-private
 * staging path, which this bridge mirrors to the descriptor after each stable
 * save. No Android URI or grant crosses into the Arch process.
 */
internal class LauncherPortalBridge(
    context: Context,
    private val sessionId: Int,
    private val appName: String,
    private val archRoot: File,
    initialDark: Boolean,
    initialAccent: Int,
    private val requestSave: (String, String, String) -> LauncherPortalSaveResult,
    private val requestOpen: (String, String, Boolean) -> LauncherPortalOpenResult,
    private val requestDirectory: (String) -> LauncherPortalDirectoryResult,
    private val requestOpenUri: (String) -> Boolean,
    private val requestNotification: (String, String, String) -> Boolean,
    private val withdrawNotification: (String) -> Boolean,
    private val audioInputEnabled: Boolean,
    private val requestAudioInput: (Boolean) -> String,
    private val printingEnabled: Boolean,
    private val requestPrint: (String, ParcelFileDescriptor) -> Boolean,
    private val secretsEnabled: Boolean,
    private val requestSecret: (String, List<String>, ParcelFileDescriptor?) -> String,
    private val cameraEnabled: Boolean,
    private val cameraPipeWireSocket: String?,
    private val requestCamera: (String, Int, Int, Boolean, ParcelFileDescriptor?) -> String,
    private val accessibilityEnabled: Boolean,
    private val publishAccessibilityTree: (ParcelFileDescriptor) -> Boolean,
    private val publishAccessibilityEvent: (Int, String) -> Boolean,
    private val takeAccessibilityAction: (Int) -> String,
    private val requestAccessibilityMenu: (Int, Boolean) -> Boolean,
    private val importDirectory: (String, ParcelFileDescriptor, Long) -> String?,
    private val cancelDirectoryImport: (Long) -> Boolean,
) : Closeable {
    private class ActiveSave(
        val staging: File,
        val stagingDirectory: File,
        val output: ParcelFileDescriptor.AutoCloseOutputStream,
        val initialModified: Long,
        val createdAtMillis: Long,
        var destinationLength: Long,
    ) {
        val copyBuffer = ByteArray(COPY_BUFFER_BYTES)
        var observedLength = -1L
        var observedModified = -1L
        var stablePolls = 0
        var copiedLength = -1L
        var copiedModified = -1L
        var writeObserved = false
        var truncateFallbackLogged = false
        var recovered = false
    }

    private val random = SecureRandom()
    private val instanceToken = randomHex(8)
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    private val runtimeDirectory = File(context.cacheDir, "p$sessionId-$instanceToken")
    private val savesBaseDirectory =
        File(archRoot, "home/archphene/.cache/archphene/portal-save")
    private val savesDirectory =
        File(savesBaseDirectory, "$sessionId-$instanceToken")
    private val importsDirectory =
        File(archRoot, "home/archphene/Documents/Android")
    private val recoveryDirectory = File(context.filesDir, "portal-save-recovery")
    private val appearanceState = File(runtimeDirectory, APPEARANCE_STATE)
    private val appearanceStateTemporary = File(runtimeDirectory, APPEARANCE_STATE_TEMPORARY)
    private val activeSaves = ArrayList<ActiveSave>(MAX_ACTIVE_SAVES)
    @Volatile private var saveSnapshot = emptyArray<ActiveSave>()
    @Volatile private var running = false
    @Volatile private var closing = false
    private var server: LocalServerSocket? = null
    private lateinit var brokerSocketName: String
    private var brokerThread: Thread? = null
    private val brokerSlots = Semaphore(MAX_BROKER_CLIENTS, true)
    private val brokerRequestLock = Any()
    private val brokerClients = HashSet<LocalSocket>(MAX_BROKER_CLIENTS)
    private val brokerClientThreads = HashSet<Thread>(MAX_BROKER_CLIENTS)
    private val activeImportDescriptors = HashSet<ParcelFileDescriptor>(MAX_OPEN_DOCUMENTS)
    private val activeImportThreads = HashSet<Thread>(MAX_BROKER_CLIENTS + 1)
    private var mirrorThread: Thread? = null
    private var daemon: java.lang.Process? = null
    private var portal: java.lang.Process? = null
    private var daemonLogThread: Thread? = null
    private var portalLogThread: Thread? = null
    private var saveFinalizerThread: Thread? = null
    private var finalizingSaves = emptyArray<ActiveSave>()
    @Volatile private var mirrorCopyingSave: ActiveSave? = null
    @Volatile private var finalizerCopyingSave: ActiveSave? = null
    private var directoryCancelThread: Thread? = null
    private var directoryImportActive = false
    private var directoryImportToken = 0L
    private var directoryCancelRequested = false
    private var cleanupRetryScheduled = false
    private var recoveryCapacityBlocked = false
    private var busSocket: File? = null
    private var nextSaveId = 1
    private var publishedDark = initialDark
    private var publishedAccent = initialAccent and 0x00ff_ffff

    lateinit var busAddress: String
        private set
    val brokerAddress: String
        get() = "@$brokerSocketName"

    fun importLaunchDocument(document: LauncherPortalOpenDocument): String {
        val uri = beginOpen(listOf(document), multiple = false).single()
        return Uri.parse(uri).path ?: error("Imported document URI has no path")
    }

    fun openLaunchDocumentForWriteback(logicalPath: String): ParcelFileDescriptor {
        val prefix = "/home/archphene/Documents/Android/"
        check(logicalPath.startsWith(prefix))
        val name = logicalPath.removePrefix(prefix)
        check(safeName(name) && '/' !in name && '\\' !in name)
        val canonicalDirectory = importsDirectory.canonicalFile
        val document = File(canonicalDirectory, name).canonicalFile
        check(document.parentFile == canonicalDirectory)
        val descriptor =
            Os.open(
                document.path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        try {
            val stat = Os.fstat(descriptor)
            check(
                OsConstants.S_ISREG(stat.st_mode) &&
                    stat.st_nlink == 1L &&
                    stat.st_size in 0..MAX_OPEN_BYTES,
            )
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    fun start() =
        synchronized(runtimeLifecycleLock) {
            runtimeRegistry.unreapedSnapshot().forEach { bridge ->
                bridge.closeLocked()
            }
            runtimeRegistry.ownedSnapshot().forEach { bridge ->
                if (bridge !== this && bridge.sessionId == sessionId) bridge.closeLocked()
            }
            check(!runtimeRegistry.hasUnreaped()) {
                "An earlier portal runtime did not terminate"
            }
            check(
                !runtimeRegistry.hasOwnedMatching { bridge ->
                    bridge !== this && bridge.sessionId == sessionId
                },
            ) {
                "An earlier portal runtime still owns session=$sessionId"
            }
            startLocked()
        }

    private fun startLocked() {
        check(!running && !closing) { "Portal bridge is already active" }
        runtimeRegistry.claim(this)
        synchronized(this) {
            directoryImportActive = false
            directoryImportToken = 0L
            directoryCancelRequested = false
        }
        try {
            check(
                !cameraEnabled ||
                    (
                        cameraPipeWireSocket != null &&
                            cameraPipeWireSocket.length in 1 until UNIX_SOCKET_PATH_LIMIT &&
                            cameraPipeWireSocket.startsWith("/data/") &&
                            cameraPipeWireSocket.none { character ->
                                character == '\u0000' || character.isISOControl()
                            }
                    ),
            ) {
                "Camera portal requires one private PipeWire socket"
            }
            requireDirectory(runtimeDirectory)
            writeAppearanceState(publishedDark, publishedAccent)
            prepareSavesDirectory()
            prepareImportsDirectory()
            val socketName =
                "archphene.portal.${Process.myPid()}.$sessionId.${randomHex(8)}"
            brokerSocketName = socketName
            val localServer = LocalServerSocket(socketName)
            server = localServer
            running = true
            brokerThread = thread(
                start = true,
                isDaemon = true,
                name = "ArchphenePortalBroker-$sessionId",
            ) {
                acceptLoop(localServer)
            }
            startDesktopPortal("@$socketName")
            mirrorThread = thread(
                start = true,
                isDaemon = true,
                name = "ArchphenePortalMirror-$sessionId",
            ) {
                mirrorLoop()
            }
        } catch (error: Throwable) {
            closeLocked()
            throw error
        }
    }

    private fun startDesktopPortal(brokerAddress: String) {
        val daemonFile = requireHelper(DAEMON)
        val portalFile = requireHelper(PORTAL)
        val socket = File(runtimeDirectory, "bus")
        if (socket.exists() && !socket.delete()) {
            error("Could not remove stale D-Bus socket")
        }
        busSocket = socket
        val socketPath = socket.canonicalPath
        check(fitsLauncherUnixSocketPath(socketPath, 100)) {
            "D-Bus socket path is too long"
        }
        val config = File(runtimeDirectory, "session.conf")
        FileOutputStream(config, false).use { output ->
            output.write(busConfiguration(socketPath).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        val daemonProcess =
            ProcessBuilder(
                daemonFile.absolutePath,
                "--config-file=${config.absolutePath}",
                "--nofork",
                "--nopidfile",
            ).redirectErrorStream(true)
                .start()
        daemon = daemonProcess
        daemonLogThread = drain(daemonProcess, "dbus")
        val deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
        while (
            !socket.exists() &&
            daemon?.isAlive == true &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(25)
        }
        check(socket.exists() && daemon?.isAlive == true) {
            "Private D-Bus session did not become ready"
        }
        busAddress = "unix:path=$socketPath"
        val portalProcess =
            ProcessBuilder(portalFile.absolutePath)
                .redirectErrorStream(true)
                .apply {
                    environment()["DBUS_SESSION_BUS_ADDRESS"] = busAddress
                    environment()["ARCHPHENE_ANDROID_BROKER"] = brokerAddress
                    environment()["ARCHPHENE_RUNTIME_DIR"] = runtimeDirectory.absolutePath
                    environment()["ARCHPHENE_APP_NAME"] = appName
                    environment()["ARCHPHENE_APPEARANCE_STATE"] =
                        appearanceState.absolutePath
                    environment()["ARCHPHENE_COLOR_SCHEME"] =
                        if (publishedDark) "dark" else "light"
                    environment()["ARCHPHENE_ACCENT_RGB"] =
                        String.format(Locale.ROOT, "%06x", publishedAccent)
                    environment()["ARCHPHENE_ENABLE_SECRETS"] =
                        if (secretsEnabled) "1" else "0"
                    environment()["ARCHPHENE_ENABLE_CAMERA"] = "0"
                    if (cameraEnabled) {
                        environment()["ARCHPHENE_ENABLE_CAMERA"] = "1"
                        environment()["ARCHPHENE_PIPEWIRE_SOCKET"] =
                            checkNotNull(cameraPipeWireSocket)
                    }
                    environment()["ARCHPHENE_ENABLE_ACCESSIBILITY"] =
                        if (accessibilityEnabled) "1" else "0"
                }.start()
        portal = portalProcess
        portalLogThread = drain(portalProcess, "portal")
        SystemClock.sleep(PORTAL_READY_DELAY_MILLIS)
        check(portal?.isAlive == true) { "Private portal frontend exited during startup" }
        Log.i(TAG, "Private desktop portal ready session=$sessionId")
    }

    @Synchronized
    fun updateAppearance(
        dark: Boolean,
        accent: Int,
    ) {
        if (!running) return
        val boundedAccent = accent and 0x00ff_ffff
        if (dark == publishedDark && boundedAccent == publishedAccent) return
        runCatching {
            writeAppearanceState(dark, boundedAccent)
        }.onSuccess {
            publishedDark = dark
            publishedAccent = boundedAccent
            Log.i(
                TAG,
                "Published portal appearance session=$sessionId " +
                    "dark=$dark accent=${boundedAccent.toString(16).padStart(6, '0')}",
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not publish portal appearance session=$sessionId", error)
        }
    }

    internal fun debugPrintPdf(
        title: String,
        payload: ByteArray,
        nonRegular: Boolean,
    ): String {
        check(running && printingEnabled)
        check(
            title.isNotBlank() &&
                title.length <= MAX_PRINT_TITLE_CHARACTERS &&
                payload.size <= MAX_DEBUG_PRINT_BYTES,
        )
        val request =
            "ARCHPHENE/1\tPRINT_PDF\t${encodeField(title)}\n"
                .toByteArray(StandardCharsets.US_ASCII)
        val pipe = if (nonRegular) Os.pipe() else null
        val staging =
            if (nonRegular) {
                null
            } else {
                File(runtimeDirectory, "debug-print-${randomHex(8)}.pdf").canonicalFile
                    .also { file ->
                        check(file.parentFile == runtimeDirectory.canonicalFile)
                        FileOutputStream(file, false).use { output ->
                            output.write(payload)
                            output.fd.sync()
                        }
                    }
            }
        try {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(
                        brokerSocketName,
                        LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                if (nonRegular) {
                    socket.setFileDescriptorsForSend(arrayOf(checkNotNull(pipe)[0]))
                    socket.outputStream.write(request)
                    socket.outputStream.flush()
                } else {
                    FileInputStream(checkNotNull(staging)).use { input ->
                        socket.setFileDescriptorsForSend(arrayOf(input.fd))
                        socket.outputStream.write(request)
                        socket.outputStream.flush()
                    }
                }
                socket.setFileDescriptorsForSend(null)
                return readRequest(socket) ?: error("Print probe response is missing")
            }
        } finally {
            pipe?.forEach { descriptor ->
                if (descriptor.valid()) runCatching { Os.close(descriptor) }
            }
            staging?.let { file ->
                if (file.exists()) check(file.delete())
            }
        }
    }

    private fun writeAppearanceState(
        dark: Boolean,
        accent: Int,
    ) {
        check(
            appearanceState.parentFile == runtimeDirectory &&
                appearanceStateTemporary.parentFile == runtimeDirectory &&
                !Files.isSymbolicLink(runtimeDirectory.toPath()) &&
                !Files.isSymbolicLink(appearanceState.toPath()) &&
                !Files.isSymbolicLink(appearanceStateTemporary.toPath()),
        ) {
            "Unsafe portal appearance state"
        }
        if (appearanceStateTemporary.exists()) {
            check(appearanceStateTemporary.delete()) {
                "Could not remove stale portal appearance state"
            }
        }
        val bytes = ByteArray(APPEARANCE_STATE_BYTES)
        bytes[0] = '1'.code.toByte()
        bytes[1] = '\n'.code.toByte()
        bytes[2] = if (dark) '1'.code.toByte() else '2'.code.toByte()
        bytes[3] = '\n'.code.toByte()
        var shift = 20
        for (index in 4..9) {
            bytes[index] = HEX[(accent ushr shift) and 0x0f].code.toByte()
            shift -= 4
        }
        bytes[10] = '\n'.code.toByte()
        FileOutputStream(appearanceStateTemporary, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Os.chmod(appearanceStateTemporary.absolutePath, 0x180)
        Os.rename(appearanceStateTemporary.absolutePath, appearanceState.absolutePath)
    }

    private fun acceptLoop(localServer: LocalServerSocket) {
        while (running) {
            val socket =
                try {
                    localServer.accept()
                } catch (error: Exception) {
                    if (running) Log.w(TAG, "Portal broker accept failed session=$sessionId", error)
                    break
                }
            socket.soTimeout = BROKER_IO_TIMEOUT_MILLIS
            if (!brokerSlots.tryAcquire()) {
                runCatching { writeResponse(socket, "ERROR\tBUSY") }
                runCatching { socket.close() }
                continue
            }
            val worker =
                Thread(
                    {
                        try {
                            handleClient(socket)
                        } finally {
                            synchronized(this) {
                                brokerClients.remove(socket)
                                brokerClientThreads.remove(Thread.currentThread())
                            }
                            brokerSlots.release()
                        }
                    },
                    "ArchphenePortalClient-$sessionId",
                ).apply { isDaemon = true }
            val accepted =
                synchronized(this) {
                    if (running) {
                        brokerClients.add(socket)
                        brokerClientThreads.add(worker)
                        true
                    } else {
                        false
                    }
                }
            if (!accepted) {
                brokerSlots.release()
                runCatching { socket.close() }
                continue
            }
            worker.start()
        }
    }

    private fun handleClient(socket: LocalSocket) {
        socket.use { client ->
            var descriptors = emptyArray<FileDescriptor>()
            try {
                runCatching {
                    if (client.peerCredentials.uid != Process.myUid()) {
                        writeResponse(client, "ERROR\tUNAUTHORIZED")
                        return
                    }
                    val request = readRequest(client) ?: run {
                        writeResponse(client, "ERROR\tINVALID_REQUEST")
                        return
                    }
                    descriptors = client.ancillaryFileDescriptors ?: emptyArray()
                    val fields = splitPortalRequest(request) ?: run {
                        writeResponse(client, "ERROR\tINVALID_REQUEST")
                        return
                    }
                    if (
                        fields.isEmpty() ||
                        (
                            fields[0] != "ARCHPHENE/1" &&
                            fields[0] != "ARCHPHENE/2" &&
                                fields[0] != "ARCHPHENE/3" &&
                                fields[0] != "ARCHPHENE/4"
                        )
                    ) {
                        writeResponse(client, "ERROR\tUNSUPPORTED")
                        return
                    }
                    if (
                        fields.getOrNull(1) !in DESCRIPTOR_OPERATIONS &&
                        descriptors.isNotEmpty()
                    ) {
                        writeResponse(client, "ERROR\tINVALID_REQUEST")
                        return
                    }
                    val operation = fields.getOrNull(1)
                    if (
                        operation == "STREAM_CAMERA_I420" ||
                        operation in CONCURRENT_OPERATIONS
                    ) {
                        dispatchClient(client, fields, descriptors)
                    } else {
                        /*
                         * Preserve the broker's original serial semantics for
                         * document, notification, print, secret, and one-shot
                         * camera state. Only the long-lived camera stream may
                         * coexist with those bounded requests.
                         */
                        synchronized(brokerRequestLock) {
                            dispatchClient(client, fields, descriptors)
                        }
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Portal broker request failed session=$sessionId", error)
                    runCatching { writeResponse(client, "ERROR\tFAILED") }
                }
            } finally {
                descriptors.forEach { descriptor ->
                    if (descriptor.valid()) runCatching { Os.close(descriptor) }
                }
            }
        }
    }

    private fun dispatchClient(
        client: LocalSocket,
        fields: List<String>,
        descriptors: Array<FileDescriptor>,
    ) {
        when (fields.getOrNull(1)) {
            "OPEN_URI" -> handleOpenUriRequest(client, fields)
            "NOTIFY" -> handleNotificationRequest(client, fields)
            "WITHDRAW_NOTIFICATION" -> handleNotificationWithdrawal(client, fields)
            "REQUEST_AUDIO_INPUT" -> handleAudioInputRequest(client, fields, request = true)
            "CHECK_AUDIO_INPUT" -> handleAudioInputRequest(client, fields, request = false)
            "PRINT_PDF" -> handlePrintRequest(client, fields, descriptors)
            "STORE_SECRET",
            "READ_SECRET",
            "DELETE_SECRET",
            "LIST_SECRETS",
            "CATALOG_SECRETS",
            -> handleSecretRequest(client, fields, descriptors)
            "REQUEST_CAMERA",
            "CHECK_CAMERA",
            "CAPTURE_CAMERA_JPEG",
            "STREAM_CAMERA_I420",
            -> handleCameraRequest(client, fields, descriptors)
            "PUBLISH_ACCESSIBILITY_TREE" ->
                handleAccessibilityTree(client, fields, descriptors)
            "ACCESSIBILITY_EVENT" ->
                handleAccessibilityEvent(client, fields)
            "TAKE_ACCESSIBILITY_ACTION" ->
                handleAccessibilityAction(client, fields)
            "ACCESSIBILITY_MENU_FALLBACK",
            "ACCESSIBILITY_MENU_ACTION",
            -> handleAccessibilityMenu(client, fields)
            "SAVE_FILE" -> handleSaveRequest(client, fields)
            "OPEN_FILE" -> handleOpenRequest(client, fields, multiple = false)
            "OPEN_FILES" -> handleOpenRequest(client, fields, multiple = true)
            "OPEN_DIRECTORY" -> handleDirectoryRequest(client, fields)
            else -> writeResponse(client, "ERROR\tUNSUPPORTED")
        }
    }

    private fun handleAccessibilityTree(
        client: LocalSocket,
        fields: List<String>,
        descriptors: Array<FileDescriptor>,
    ) {
        if (!accessibilityEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        if (
            fields.size != 2 ||
            fields[0] != "ARCHPHENE/1" ||
            descriptors.size != 1 ||
            !descriptors[0].valid()
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val stat =
            runCatching { Os.fstat(descriptors[0]) }.getOrElse {
                writeResponse(client, "ERROR\tINVALID_REQUEST")
                return
            }
        if (
            stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG ||
            stat.st_size !in ACCESSIBILITY_TREE_MIN_BYTES..ACCESSIBILITY_TREE_MAX_BYTES
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val accepted =
            runCatching {
                ParcelFileDescriptor.dup(descriptors[0]).use { descriptor ->
                    publishAccessibilityTree(descriptor)
                }
            }.getOrDefault(false)
        writeResponse(client, if (accepted) "OK" else "ERROR\tFAILED")
    }

    private fun handleAccessibilityEvent(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (!accessibilityEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        val nodeId = fields.getOrNull(2)?.toIntOrNull()
        val type = fields.getOrNull(3).orEmpty()
        if (
            fields.size != 4 ||
            fields[0] != "ARCHPHENE/1" ||
            nodeId !in 0..MAX_ACCESSIBILITY_NODE_ID ||
            type !in ACCESSIBILITY_EVENTS
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        writeResponse(
            client,
            if (publishAccessibilityEvent(checkNotNull(nodeId), type)) {
                "OK"
            } else {
                "ERROR\tFAILED"
            },
        )
    }

    private fun handleAccessibilityAction(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (!accessibilityEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        val timeoutMillis = fields.getOrNull(2)?.toIntOrNull()
        if (
            fields.size != 3 ||
            fields[0] != "ARCHPHENE/1" ||
            timeoutMillis !in 0..MAX_ACCESSIBILITY_POLL_MILLIS
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val response = takeAccessibilityAction(checkNotNull(timeoutMillis))
        writeResponse(
            client,
            if (validAccessibilityActionResponse(response)) {
                response
            } else {
                "ERROR\tFAILED"
            },
        )
    }

    private fun handleAccessibilityMenu(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (!accessibilityEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        val operation = fields.getOrNull(1)
        val nodeId = fields.getOrNull(2)?.toIntOrNull()
        val transition =
            when (operation) {
                "ACCESSIBILITY_MENU_FALLBACK" ->
                    if (fields.size == 3) false else null
                "ACCESSIBILITY_MENU_ACTION" ->
                    fields.getOrNull(3)?.toIntOrNull()?.let { value ->
                        if (fields.size == 4 && value in 0..1) value == 1 else null
                    }
                else -> null
            }
        if (
            fields[0] != "ARCHPHENE/1" ||
            nodeId !in 1..MAX_ACCESSIBILITY_NODE_ID ||
            transition == null
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        writeResponse(
            client,
            if (requestAccessibilityMenu(checkNotNull(nodeId), transition)) {
                "OK"
            } else {
                "ERROR\tFAILED"
            },
        )
    }

    private fun validAccessibilityActionResponse(response: String): Boolean {
        if (response == "ERROR\tEMPTY") return true
        val fields = splitPortalFields(response, 4) ?: return false
        val nodeId = fields.getOrNull(1)?.toIntOrNull()
        val action = fields.getOrNull(2).orEmpty()
        val encoded = fields.getOrNull(3).orEmpty()
        val internalRefresh = nodeId == 0 && action == "refresh"
        if (
            fields.size != 4 ||
            fields[0] != "OK" ||
            (!internalRefresh && nodeId !in 1..MAX_ACCESSIBILITY_NODE_ID) ||
            (action == "refresh" && !internalRefresh) ||
            (internalRefresh && encoded.isNotEmpty()) ||
            action !in ACCESSIBILITY_ACTIONS ||
            encoded.length > MAX_ACCESSIBILITY_ACTION_ENCODED_BYTES
        ) {
            return false
        }
        val decoded =
            runCatching {
                Base64.decode(
                    encoded,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            }.getOrNull() ?: return false
        return decoded.size <= MAX_ACCESSIBILITY_ACTION_TEXT_BYTES &&
            runCatching {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(decoded))
            }.isSuccess
    }

    private fun handleOpenUriRequest(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (fields.size != 3 || fields[0] != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val uri = decodeField(fields[2], PortalUriPolicy.MAX_URI_BYTES)
        if (uri == null || !PortalUriPolicy.valid(uri)) {
            writeResponse(client, "ERROR\tINVALID_URI")
            return
        }
        writeResponse(client, if (requestOpenUri(uri)) "OK" else "ERROR\tFAILED")
    }

    private fun handleNotificationRequest(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (fields.size != 5 || fields[0] != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val id = decodeField(fields[2], MAX_NOTIFICATION_ID_BYTES)
        val title = decodeField(fields[3], MAX_NOTIFICATION_TITLE_BYTES)
        val body = decodeField(fields[4], MAX_NOTIFICATION_BODY_BYTES)
        if (
            id == null ||
            title == null ||
            body == null ||
            !validNotificationText(id, MAX_NOTIFICATION_ID_CHARACTERS, false) ||
            !validNotificationText(title, MAX_NOTIFICATION_TITLE_CHARACTERS, false) ||
            !validNotificationText(body, MAX_NOTIFICATION_BODY_CHARACTERS, true)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        writeResponse(
            client,
            if (requestNotification(id, title, body)) "OK" else "ERROR\tFAILED",
        )
    }

    private fun handleNotificationWithdrawal(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (fields.size != 3 || fields[0] != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val id = decodeField(fields[2], MAX_NOTIFICATION_ID_BYTES)
        if (
            id == null ||
            !validNotificationText(id, MAX_NOTIFICATION_ID_CHARACTERS, false)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        writeResponse(client, if (withdrawNotification(id)) "OK" else "ERROR\tFAILED")
    }

    private fun handleAudioInputRequest(
        client: LocalSocket,
        fields: List<String>,
        request: Boolean,
    ) {
        if (!audioInputEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        if (fields.size != 2 || fields[0] != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val response = requestAudioInput(request)
        if (response !in AUDIO_INPUT_RESPONSES) {
            Log.e(TAG, "Rejected invalid microphone response session=$sessionId")
            writeResponse(client, "ERROR\tFAILED")
            return
        }
        writeResponse(client, response)
    }

    private fun handlePrintRequest(
        client: LocalSocket,
        fields: List<String>,
        descriptors: Array<FileDescriptor>,
    ) {
        if (!printingEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        if (
            fields.size != 3 ||
            fields[0] != "ARCHPHENE/1" ||
            descriptors.size != 1 ||
            !descriptors[0].valid()
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val title = decodeField(fields[2], MAX_TITLE_BYTES)
        if (
            title == null ||
            !validNotificationText(title, MAX_PRINT_TITLE_CHARACTERS, false)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val accepted =
            runCatching {
                ParcelFileDescriptor.dup(descriptors[0]).use { descriptor ->
                    requestPrint(title, descriptor)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Could not relay Linux print request session=$sessionId", error)
                false
            }
        writeResponse(client, if (accepted) "OK" else "ERROR\tFAILED")
    }

    private fun handleSecretRequest(
        client: LocalSocket,
        fields: List<String>,
        descriptors: Array<FileDescriptor>,
    ) {
        if (!secretsEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        if (fields.getOrNull(0) != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val operation = fields.getOrNull(1).orEmpty()
        val decoded =
            when (operation) {
                "STORE_SECRET" -> {
                    if (
                        fields.size !in 5..6 ||
                        descriptors.size != 1 ||
                        !descriptors[0].valid()
                    ) {
                        null
                    } else {
                        val id = decodeField(fields[2], MAX_SECRET_ID_BYTES)
                        val label =
                            decodeFieldAllowEmpty(fields[3], MAX_SECRET_LABEL_BYTES)
                        val attributes =
                            decodeField(fields[4], MAX_SECRET_ATTRIBUTES_BYTES)
                        val contentType =
                            if (fields.size == 6) {
                                decodeField(fields[5], MAX_SECRET_CONTENT_TYPE_BYTES)
                            } else {
                                "text/plain"
                            }
                        if (
                            id == null ||
                            label == null ||
                            attributes == null ||
                            contentType == null ||
                            !validSecretText(id, MAX_SECRET_ID_CHARACTERS, allowEmpty = false) ||
                            !validSecretText(
                                label,
                                MAX_SECRET_LABEL_CHARACTERS,
                                allowEmpty = true,
                            ) ||
                            !validSecretText(
                                contentType,
                                MAX_SECRET_CONTENT_TYPE_CHARACTERS,
                                allowEmpty = false,
                            )
                        ) {
                            null
                        } else {
                            listOf(id, label, attributes, contentType)
                        }
                    }
                }
                "READ_SECRET",
                "DELETE_SECRET",
                -> {
                    val expectedDescriptors = if (operation == "READ_SECRET") 1 else 0
                    if (
                        fields.size != 3 ||
                        descriptors.size != expectedDescriptors ||
                        descriptors.any { !it.valid() }
                    ) {
                        null
                    } else {
                        val id = decodeField(fields[2], MAX_SECRET_ID_BYTES)
                        if (
                            id == null ||
                            !validSecretText(id, MAX_SECRET_ID_CHARACTERS, allowEmpty = false)
                        ) {
                            null
                        } else {
                            listOf(id)
                        }
                    }
                }
                "LIST_SECRETS",
                "CATALOG_SECRETS",
                ->
                    if (
                        fields.size == 2 &&
                        descriptors.size == 1 &&
                        descriptors[0].valid()
                    ) {
                        emptyList()
                    } else {
                        null
                    }
                else -> null
            }
        if (decoded == null) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val descriptor =
            descriptors.singleOrNull()?.let { source ->
                runCatching { ParcelFileDescriptor.dup(source) }.getOrNull()
            }
        if (descriptors.isNotEmpty() && descriptor == null) {
            writeResponse(client, "ERROR\tFAILED")
            return
        }
        val response =
            descriptor.use {
                runCatching {
                    requestSecret(operation, decoded, it)
                }.getOrElse { error ->
                    Log.w(
                        TAG,
                        "Could not relay Linux secret request session=$sessionId operation=$operation",
                        error,
                    )
                    "ERROR\tFAILED"
                }
            }
        if (!validSecretResponse(operation, response)) {
            Log.e(
                TAG,
                "Rejected invalid secret response session=$sessionId operation=$operation",
            )
            writeResponse(client, "ERROR\tFAILED")
            return
        }
        writeResponse(client, response)
    }

    private fun handleCameraRequest(
        client: LocalSocket,
        fields: List<String>,
        descriptors: Array<FileDescriptor>,
    ) {
        if (!cameraEnabled) {
            writeResponse(client, "ERROR\tUNSUPPORTED")
            return
        }
        if (fields.getOrNull(0) != "ARCHPHENE/1") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val operation = fields.getOrNull(1).orEmpty()
        val request =
            when (operation) {
                "REQUEST_CAMERA",
                "CHECK_CAMERA",
                ->
                    if (fields.size == 2 && descriptors.isEmpty()) {
                        CameraRequest(0, 0, false, null)
                    } else {
                        null
                    }
                "CAPTURE_CAMERA_JPEG",
                "STREAM_CAMERA_I420",
                -> {
                    val width = fields.getOrNull(2)?.toIntOrNull()
                    val height = fields.getOrNull(3)?.toIntOrNull()
                    val facing = fields.getOrNull(4)
                    if (
                        fields.size == 5 &&
                        width != null &&
                        width in 1..MAX_CAMERA_DIMENSION &&
                        height != null &&
                        height in 1..MAX_CAMERA_DIMENSION &&
                        (facing == "front" || facing == "back") &&
                        descriptors.size == 1 &&
                        descriptors[0].valid()
                    ) {
                        val descriptor =
                            runCatching {
                                ParcelFileDescriptor.dup(descriptors[0])
                            }.getOrNull() ?: run {
                                writeResponse(client, "ERROR\tFAILED")
                                return
                            }
                        CameraRequest(
                            width,
                            height,
                            facing == "front",
                            descriptor,
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        if (request == null) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val response =
            request.descriptor.use { descriptor ->
                runCatching {
                    requestCamera(
                        operation,
                        request.width,
                        request.height,
                        request.front,
                        descriptor,
                    )
                }.getOrElse { error ->
                    Log.w(
                        TAG,
                        "Could not relay Linux camera request session=$sessionId " +
                            "operation=$operation",
                        error,
                    )
                    "ERROR\tFAILED"
                }
            }
        if (!validCameraResponse(operation, response)) {
            Log.e(
                TAG,
                "Rejected invalid camera response session=$sessionId operation=$operation",
            )
            writeResponse(client, "ERROR\tFAILED")
            return
        }
        writeResponse(client, response)
    }

    private fun handleSaveRequest(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (fields.size != 5) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val title = decodeField(fields[2], MAX_TITLE_BYTES)
        val name = decodeField(fields[3], MAX_NAME_BYTES)
        val mime = decodeField(fields[4], MAX_MIME_BYTES)
        if (
            title == null ||
            name == null ||
            mime == null ||
            title.isBlank() ||
            !safeName(name) ||
            !PortalMimePolicy.valid(mime)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val result = requestSave(title, name, mime)
        val descriptor = result.descriptor
        if (descriptor == null || !safeName(result.displayName)) {
            descriptor?.close()
            writeResponse(client, if (result.cancelled) "CANCEL" else "ERROR\tFAILED")
            return
        }
        val uri =
            runCatching { beginSave(result.displayName, descriptor) }
                .getOrElse { error ->
                    descriptor.close()
                    Log.e(TAG, "Could not create portal staging file session=$sessionId", error)
                    writeResponse(client, "ERROR\tFAILED")
                    return
                }
        writeResponse(client, "OK\t${encodeField(uri)}")
    }

    private fun handleOpenRequest(
        client: LocalSocket,
        fields: List<String>,
        multiple: Boolean,
    ) {
        if (
            fields.size != 4 ||
            (multiple && fields[0] != "ARCHPHENE/3") ||
            (!multiple && fields[0] != "ARCHPHENE/2")
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val title = decodeField(fields[2], MAX_TITLE_BYTES)
        val mime = decodeField(fields[3], MAX_MIME_BYTES)
        if (
            title == null ||
            mime == null ||
            title.isBlank() ||
            !PortalMimePolicy.valid(mime)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val result = requestOpen(title, mime, multiple)
        if (result.documents.isEmpty()) {
            writeResponse(client, if (result.cancelled) "CANCEL" else "ERROR\tFAILED")
            return
        }
        val uris =
            runCatching { beginOpen(result.documents, multiple) }
                .getOrElse { error ->
                    result.documents.forEach { document ->
                        runCatching { document.descriptor.close() }
                    }
                    Log.e(TAG, "Could not import portal documents session=$sessionId", error)
                    writeResponse(client, "ERROR\tFAILED")
                    return
                }
        writeResponse(
            client,
            buildString {
                append("OK\t")
                append(uris.size)
                for (uri in uris) {
                    append('\t')
                    append(encodeField(uri))
                }
            },
        )
    }

    private fun handleDirectoryRequest(
        client: LocalSocket,
        fields: List<String>,
    ) {
        if (fields.size != 3 || fields[0] != "ARCHPHENE/4") {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val title = decodeField(fields[2], MAX_NAME_BYTES)
        if (title == null) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val result = requestDirectory(title)
        val descriptor = result.descriptor
        if (descriptor == null) {
            writeResponse(client, if (result.cancelled) "CANCEL" else "ERROR\tFAILED")
            return
        }
        val uri =
            runCatching {
                descriptor.use {
                    val cancellationToken = random.nextLong().let { token -> if (token == 0L) 1L else token }
                    synchronized(this) {
                        check(running)
                        directoryImportActive = true
                        directoryImportToken = cancellationToken
                    }
                    val logicalPath =
                        try {
                            importDirectory(result.displayName, it, cancellationToken)
                                ?: error("Could not import the selected Android folder")
                        } finally {
                            synchronized(this) {
                                if (directoryImportToken == cancellationToken) {
                                    directoryImportActive = false
                                    directoryImportToken = 0L
                                }
                            }
                        }
                    PortalFileUri.fromLogicalPath(logicalPath)
                }
            }.getOrElse { error ->
                runCatching { descriptor.close() }
                Log.e(TAG, "Could not import portal folder session=$sessionId", error)
                writeResponse(client, "ERROR\tFAILED")
                return
            }
        writeResponse(client, "OK\t1\t${encodeField(uri)}")
    }

    @Synchronized
    private fun beginSave(
        displayName: String,
        descriptor: ParcelFileDescriptor,
    ): String {
        check(running && activeSaves.size < MAX_ACTIVE_SAVES)
        check(safeName(displayName))
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        val destinationLength = Os.fstat(output.fd).st_size
        check(destinationLength in 0..MAX_SAVE_BYTES) {
            "Portal destination has an invalid initial size"
        }
        val canonicalStaging = createStaging(displayName)
        val stagingDirectory = checkNotNull(canonicalStaging.parentFile)
        activeSaves +=
            ActiveSave(
                canonicalStaging,
                stagingDirectory,
                output,
                canonicalStaging.lastModified(),
                SystemClock.uptimeMillis(),
                destinationLength,
            )
        saveSnapshot = activeSaves.toTypedArray()
        val logicalPath =
            "/home/archphene/.cache/archphene/portal-save/" +
                "$sessionId-$instanceToken/${stagingDirectory.name}/" +
                canonicalStaging.name
        return PortalFileUri.fromLogicalPath(logicalPath)
    }

    private fun beginOpen(
        documents: List<LauncherPortalOpenDocument>,
        multiple: Boolean,
    ): List<String> {
        val importThread = Thread.currentThread()
        synchronized(this) {
            check(running)
            check(documents.size in 1..MAX_OPEN_DOCUMENTS)
            check(multiple || documents.size == 1)
            check(documents.all { document -> safeName(document.displayName) })
            check(activeImportDescriptors.size + documents.size <= MAX_OPEN_DOCUMENTS)
            check(activeImportThreads.size < MAX_BROKER_CLIENTS + 1)
            documents.forEach { document -> activeImportDescriptors.add(document.descriptor) }
            activeImportThreads.add(importThread)
        }
        val imported = ArrayList<File>(documents.size)
        var totalCopied = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            for (document in documents) {
                val target =
                    synchronized(this) {
                        check(running) { "Portal session closed during document import" }
                        reserveImport(document.displayName)
                    }
                imported += target
                ParcelFileDescriptor.AutoCloseInputStream(document.descriptor).use { input ->
                    FileOutputStream(target, false).use { output ->
                        while (true) {
                            check(running) { "Portal session closed during document import" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalCopied += count
                            check(totalCopied <= MAX_OPEN_BYTES) {
                                "Portal document batch exceeds the size limit"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (document.writable) {
                    Log.w(TAG, "Ignored untrusted writable OpenFile hint session=$sessionId")
                }
            }
        } catch (error: Exception) {
            documents.forEach { document ->
                runCatching { document.descriptor.close() }
            }
            imported.forEach { file -> runCatching { file.delete() } }
            throw error
        } finally {
            synchronized(this) {
                documents.forEach { document ->
                    activeImportDescriptors.remove(document.descriptor)
                }
                activeImportThreads.remove(importThread)
            }
        }
        return imported.map { file ->
            val logicalPath = "/home/archphene/Documents/Android/${file.name}"
            PortalFileUri.fromLogicalPath(logicalPath)
        }
    }

    private fun createStaging(displayName: String): File {
        val saveId = nextSaveId++
        val canonicalDirectory = savesDirectory.canonicalFile
        val slot = File(canonicalDirectory, "$saveId-${randomHex(8)}").canonicalFile
        check(slot.parentFile == canonicalDirectory)
        check(slot.mkdir()) { "Could not create portal staging slot" }
        val staging = File(slot, displayName)
        val canonicalStaging = staging.canonicalFile
        return try {
            check(canonicalStaging.parentFile == slot)
            check(canonicalStaging.createNewFile()) { "Portal staging file already exists" }
            canonicalStaging
        } catch (error: Exception) {
            runCatching { slot.delete() }
            throw error
        }
    }

    private fun reserveImport(displayName: String): File {
        val canonicalDirectory = importsDirectory.canonicalFile
        val extensionIndex =
            displayName.lastIndexOf('.').takeIf { index -> index > 0 } ?: displayName.length
        val stem = displayName.substring(0, extensionIndex)
        val extension = displayName.substring(extensionIndex)
        for (attempt in 1..MAX_IMPORT_COLLISIONS) {
            val candidateName =
                if (attempt == 1) displayName else "$stem ($attempt)$extension"
            val candidate = File(canonicalDirectory, candidateName).canonicalFile
            check(candidate.parentFile == canonicalDirectory)
            if (candidate.createNewFile()) {
                return candidate
            }
            // Preserve the existing document and try the next visible suffix.
        }
        error("Too many imported documents with the same display name")
    }

    private fun mirrorLoop() {
        while (running) {
            for (save in saveSnapshot) {
                runCatching { pollSave(save) }
                    .onFailure { error ->
                        Log.e(TAG, "Portal document mirror failed session=$sessionId", error)
                    }
            }
            SystemClock.sleep(MIRROR_POLL_MILLIS)
        }
    }

    private fun pollSave(save: ActiveSave) {
        val length = save.staging.length()
        val modified = save.staging.lastModified()
        if (length > MAX_SAVE_BYTES) {
            error("Portal save exceeds the size limit")
        }
        if (length != save.observedLength || modified != save.observedModified) {
            save.observedLength = length
            save.observedModified = modified
            save.stablePolls = 0
            if (length > 0L || modified != save.initialModified) {
                save.writeObserved = true
            }
        }
        if (
            !save.writeObserved &&
            SystemClock.uptimeMillis() - save.createdAtMillis < EMPTY_SAVE_GRACE_MILLIS
        ) {
            return
        }
        save.stablePolls++
        if (
            save.stablePolls < REQUIRED_STABLE_POLLS ||
            (length == save.copiedLength && modified == save.copiedModified)
        ) {
            return
        }
        mirrorCopyingSave = save
        try {
            copySave(save, length)
        } finally {
            if (mirrorCopyingSave === save) mirrorCopyingSave = null
        }
        save.copiedLength = length
        save.copiedModified = modified
        Log.i(TAG, "Mirrored Linux SaveFile session=$sessionId bytes=$length")
    }

    private fun copySave(
        save: ActiveSave,
        expectedLength: Long,
    ) {
        try {
            Os.ftruncate(save.output.fd, 0)
        } catch (error: ErrnoException) {
            if (
                error.errno != OsConstants.EIO ||
                expectedLength < save.destinationLength
            ) {
                throw error
            }
            if (!save.truncateFallbackLogged) {
                save.truncateFallbackLogged = true
                Log.w(
                    TAG,
                    "Provider cannot truncate; using length-safe overwrite session=$sessionId",
                )
            }
        }
        Os.lseek(save.output.fd, 0, 0)
        FileInputStream(save.staging).use { input ->
            var copied = 0L
            while (true) {
                val count = input.read(save.copyBuffer)
                if (count < 0) break
                copied += count
                check(copied <= MAX_SAVE_BYTES)
                save.output.write(save.copyBuffer, 0, count)
            }
            check(copied == expectedLength) { "Staging file changed while copying" }
        }
        save.output.flush()
        Os.fsync(save.output.fd)
        save.destinationLength = expectedLength
    }

    override fun close() {
        synchronized(runtimeLifecycleLock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        val localServer: LocalServerSocket?
        val clientSockets: Array<LocalSocket>
        val importDescriptors: Array<ParcelFileDescriptor>
        synchronized(this) {
            val hasResources =
                running ||
                    closing ||
                    server != null ||
                    brokerThread != null ||
                    brokerClients.isNotEmpty() ||
                    brokerClientThreads.isNotEmpty() ||
                    activeImportDescriptors.isNotEmpty() ||
                    activeImportThreads.isNotEmpty() ||
                    mirrorThread != null ||
                    daemon != null ||
                    portal != null ||
                    daemonLogThread != null ||
                    portalLogThread != null ||
                    saveFinalizerThread != null ||
                    directoryCancelThread != null ||
                    directoryImportActive ||
                    activeSaves.isNotEmpty() ||
                    finalizingSaves.isNotEmpty() ||
                    runtimeDirectory.exists() ||
                    savesDirectory.exists()
            if (!hasResources) {
                runtimeRegistry.finish(this, terminated = true)
                return
            }
            running = false
            closing = true
            saveSnapshot = emptyArray()
            localServer = server
            server = null
            clientSockets = brokerClients.toTypedArray()
            importDescriptors = activeImportDescriptors.toTypedArray()
        }
        runCatching { localServer?.close() }
        clientSockets.forEach { socket -> runCatching { socket.close() } }
        importDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        val cancelWorkerToStart =
            synchronized(this) {
                if (
                    directoryImportActive &&
                    !directoryCancelRequested &&
                    directoryCancelThread == null
                ) {
                    directoryCancelRequested = true
                    val cancellationToken = directoryImportToken
                    Thread(
                        {
                            val cancelled =
                                runCatching { cancelDirectoryImport(cancellationToken) }
                                    .getOrDefault(false)
                            if (!cancelled) {
                                synchronized(this) {
                                    if (
                                        directoryImportActive &&
                                        directoryImportToken == cancellationToken
                                    ) {
                                        directoryCancelRequested = false
                                    }
                                }
                            }
                        },
                        "ArchphenePortalImportCancel-$sessionId",
                    ).apply {
                        isDaemon = true
                        directoryCancelThread = this
                    }
                } else {
                    null
                }
            }
        if (cancelWorkerToStart != null) {
            runCatching { cancelWorkerToStart.start() }
                .onFailure { error ->
                    synchronized(this) {
                        if (directoryCancelThread === cancelWorkerToStart) {
                            directoryCancelThread = null
                            directoryCancelRequested = false
                        }
                    }
                    Log.e(TAG, "Could not start directory cancellation session=$sessionId", error)
                }
        }
        val cancelWorker = synchronized(this) { directoryCancelThread }
        if (joinWorker(cancelWorker, "document-import-cancel")) {
            synchronized(this) {
                if (directoryCancelThread === cancelWorker) directoryCancelThread = null
            }
        }

        val mirrorWorker = synchronized(this) { mirrorThread }
        var mirrorStopped = joinWorker(mirrorWorker, "mirror")
        if (!mirrorStopped) {
            mirrorCopyingSave?.let { save -> runCatching { save.output.close() } }
            mirrorStopped = joinWorker(mirrorWorker, "mirror-cancelled")
        }
        if (mirrorStopped) {
            synchronized(this) {
                if (mirrorThread === mirrorWorker) mirrorThread = null
            }
        }
        val brokerWorker = synchronized(this) { brokerThread }
        if (joinWorker(brokerWorker, "broker")) {
            synchronized(this) {
                if (brokerThread === brokerWorker) brokerThread = null
            }
        }
        val clientWorkers = synchronized(this) { brokerClientThreads.toTypedArray() }
        clientWorkers.forEach { worker ->
            if (joinWorker(worker, "client")) {
                synchronized(this) { brokerClientThreads.remove(worker) }
            }
        }
        val importWorkers = synchronized(this) { activeImportThreads.toTypedArray() }
        importWorkers.forEach { worker -> joinWorker(worker, "document-import") }

        stopTrackedProcess(portal, portalLogThread, "portal") { process, drainer ->
            if (portal === process) portal = null
            if (portalLogThread === drainer) portalLogThread = null
        }
        stopTrackedProcess(daemon, daemonLogThread, "dbus") { process, drainer ->
            if (daemon === process) daemon = null
            if (daemonLogThread === drainer) daemonLogThread = null
        }

        synchronized(this) {
            if (
                mirrorThread == null &&
                saveFinalizerThread == null &&
                (activeSaves.isNotEmpty() || finalizingSaves.isNotEmpty())
            ) {
                runCatching { startSaveFinalizerLocked() }
                    .onFailure { error ->
                        Log.e(TAG, "Could not start portal save finalizer session=$sessionId", error)
                    }
            }
        }
        val finalizer = synchronized(this) { saveFinalizerThread }
        var finalizerStopped = joinWorker(finalizer, "save-finalizer")
        if (!finalizerStopped) {
            finalizerCopyingSave?.let { save -> runCatching { save.output.close() } }
            finalizerStopped = joinWorker(finalizer, "save-finalizer-cancelled")
        }
        if (finalizerStopped) {
            synchronized(this) {
                if (saveFinalizerThread === finalizer) saveFinalizerThread = null
            }
        }

        val resourcesStopped =
            synchronized(this) {
                brokerClients.removeAll { socket -> socket.isClosed }
                PortalCloseReadiness(
                    brokerStopped = brokerThread == null,
                    clientsStopped =
                        brokerClients.isEmpty() && brokerClientThreads.isEmpty(),
                    importsStopped =
                        activeImportDescriptors.isEmpty() && activeImportThreads.isEmpty(),
                    mirrorStopped = mirrorThread == null,
                    processesStopped = portal == null && daemon == null,
                    drainersStopped = portalLogThread == null && daemonLogThread == null,
                    saveFinalizerStopped = saveFinalizerThread == null,
                    directoryCancelStopped =
                        directoryCancelThread == null && !directoryImportActive,
                    savesFinalized = activeSaves.isEmpty() && finalizingSaves.isEmpty(),
                ).canCleanup
            }
        val cleaned = resourcesStopped && cleanupOwnedPaths()
        synchronized(this) {
            if (cleaned) closing = false
        }
        runtimeRegistry.finish(this, terminated = cleaned)
        if (!cleaned) {
            Log.w(TAG, "Portal runtime remained live after bounded teardown session=$sessionId")
            scheduleCleanupRetryLocked()
        }
    }

    private fun stopTrackedProcess(
        process: java.lang.Process?,
        drainer: Thread?,
        label: String,
        release: (java.lang.Process?, Thread?) -> Unit,
    ) {
        val processStopped = stopProcessBoundedly(process, PROCESS_STOP_TIMEOUT_MILLIS)
        if (processStopped) runCatching { process?.inputStream?.close() }
        val drainerStopped = processStopped && joinWorker(drainer, "$label-log")
        if (processStopped && drainerStopped) {
            synchronized(this) { release(process, drainer) }
        }
    }

    private fun startSaveFinalizerLocked() {
        val retainedSaves = finalizingSaves
        val saves =
            if (retainedSaves.isNotEmpty()) retainedSaves else activeSaves.toTypedArray()
        val worker =
            thread(
                start = false,
                isDaemon = true,
                name = "ArchphenePortalSaveFinalizer-$sessionId",
            ) {
                finalizeSaves(saves)
            }
        saveFinalizerThread = worker
        finalizingSaves = saves
        activeSaves.clear()
        saveSnapshot = emptyArray()
        try {
            worker.start()
        } catch (error: Throwable) {
            saveFinalizerThread = null
            if (retainedSaves.isNotEmpty()) {
                finalizingSaves = retainedSaves
            } else {
                finalizingSaves = emptyArray()
                activeSaves.addAll(saves)
            }
            saveSnapshot = activeSaves.toTypedArray()
            throw error
        }
    }

    private fun finalizeSaves(saves: Array<ActiveSave>) {
        val residual = ArrayList<ActiveSave>(saves.size)
        try {
            for (save in saves) {
                val length = save.staging.length()
                val modified = save.staging.lastModified()
                val copyRequired =
                    !save.recovered &&
                        save.staging.isFile &&
                        length <= MAX_SAVE_BYTES &&
                        (length != save.copiedLength || modified != save.copiedModified)
                val copySucceeded =
                    if (copyRequired && save.output.fd.valid()) {
                        finalizerCopyingSave = save
                        try {
                            runCatching { copySave(save, length) }
                                .onFailure { error ->
                                    Log.e(
                                        TAG,
                                        "Final portal document mirror failed session=$sessionId",
                                        error,
                                    )
                                }.isSuccess
                        } finally {
                            if (finalizerCopyingSave === save) finalizerCopyingSave = null
                        }
                    } else {
                        false
                    }
                runCatching { save.output.close() }
                val recovered =
                    save.recovered ||
                        (copyRequired && !copySucceeded && recoverFailedSave(save))
                if (canDiscardPortalStaging(copyRequired, copySucceeded, recovered)) {
                    val stagingRemoved = !save.staging.exists() || save.staging.delete()
                    val slotRemoved =
                        !save.stagingDirectory.exists() || save.stagingDirectory.delete()
                    if (!stagingRemoved || !slotRemoved) residual += save
                } else {
                    Log.e(TAG, "Preserving uncommitted portal save session=$sessionId")
                    residual += save
                }
            }
        } finally {
            finalizerCopyingSave = null
            synchronized(this) {
                if (finalizingSaves === saves) finalizingSaves = residual.toTypedArray()
            }
        }
    }

    private fun recoverFailedSave(save: ActiveSave): Boolean {
        return runCatching {
            prepareRecoveryDirectory(recoveryDirectory)
            val recoveredName = recoverPortalSaveFile(save.staging, recoveryDirectory)
            save.recovered = true
            Log.e(
                TAG,
                "Recovered uncommitted portal save session=$sessionId file=$recoveredName",
            )
            true
        }.getOrElse { error ->
            if (error is PortalRecoveryCapacityException) {
                synchronized(this) { recoveryCapacityBlocked = true }
            }
            Log.e(TAG, "Could not recover uncommitted portal save session=$sessionId", error)
            false
        }
    }

    private fun scheduleCleanupRetryLocked() {
        synchronized(this) {
            if (cleanupRetryScheduled || recoveryCapacityBlocked) return
            cleanupRetryScheduled = true
        }
        runCatching {
            thread(
                start = true,
                isDaemon = true,
                name = "ArchphenePortalCleanup-$sessionId",
            ) {
                try {
                    while (true) {
                        SystemClock.sleep(CLEANUP_RETRY_MILLIS)
                        val complete =
                            synchronized(runtimeLifecycleLock) {
                                if (!closing) {
                                    true
                                } else {
                                    runCatching { closeLocked() }
                                        .onFailure { error ->
                                            Log.e(
                                                TAG,
                                                "Portal cleanup retry failed session=$sessionId",
                                                error,
                                            )
                                        }
                                    !closing
                                }
                            }
                        if (complete) return@thread
                        if (synchronized(this) { recoveryCapacityBlocked }) return@thread
                    }
                } finally {
                    synchronized(this) { cleanupRetryScheduled = false }
                }
            }
        }.onFailure { error ->
            synchronized(this) { cleanupRetryScheduled = false }
            Log.e(TAG, "Could not schedule portal cleanup retry session=$sessionId", error)
        }
    }

    private fun cleanupOwnedPaths(): Boolean {
        var cleaned = true
        val socket = busSocket
        if (socket != null && socket.exists() && !socket.delete()) cleaned = false
        if (cleaned) busSocket = null
        if (runtimeDirectory.exists() && !runtimeDirectory.deleteRecursively()) cleaned = false
        if (savesDirectory.exists() && !savesDirectory.delete()) cleaned = false
        return cleaned
    }

    private fun requireHelper(name: String): File {
        val helper = File(nativeLibraryDir, name)
        check(helper.isFile) { "Desktop helper is missing: $name" }
        return helper
    }

    private fun requireDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create ${directory.absolutePath}"
        }
    }

    private fun prepareSavesDirectory() {
        requireTrustedDirectoryChain(archRoot, savesBaseDirectory)
        check(!Files.isSymbolicLink(savesDirectory.toPath()))
        requireDirectory(savesDirectory)
        val canonicalDirectory = savesDirectory.canonicalFile
        check(canonicalDirectory.parentFile == savesBaseDirectory.canonicalFile) {
            "Portal save directory escaped its private base"
        }
        for (
            entry in
                collectBoundedDirectoryEntries(
                    savesDirectory,
                    MAX_ACTIVE_SAVES,
                    "Too many portal save entries",
                )
        ) {
            check(entry.canonicalFile.parentFile == canonicalDirectory && entry.isFile) {
                "Unsafe stale portal save"
            }
            check(entry.delete()) { "Could not remove stale portal save" }
        }
    }

    private fun prepareImportsDirectory() {
        requireTrustedDirectoryChain(archRoot, importsDirectory)
        check(
            importsDirectory.canonicalFile.toPath()
                .startsWith(archRoot.canonicalFile.toPath()),
        ) {
            "Portal import directory escaped the Arch root"
        }
    }

    private fun readRequest(client: LocalSocket): String? {
        val bytes = ByteArray(MAX_REQUEST_BYTES)
        var length = 0
        while (length < bytes.size) {
            val value = client.inputStream.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                return String(bytes, 0, length, StandardCharsets.US_ASCII)
            }
            if (value == '\r'.code || value == 0 || value > 127) return null
            bytes[length++] = value.toByte()
        }
        return null
    }

    private fun writeResponse(
        client: LocalSocket,
        response: String,
    ) {
        val bytes = "$response\n".toByteArray(StandardCharsets.US_ASCII)
        client.outputStream.write(bytes)
        client.outputStream.flush()
    }

    private fun decodeField(
        field: String,
        maximumBytes: Int,
    ): String? =
        runCatching {
            val bytes = Base64.decode(field, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            check(bytes.isNotEmpty() && bytes.size <= maximumBytes)
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun decodeFieldAllowEmpty(
        field: String,
        maximumBytes: Int,
    ): String? =
        if (field.isEmpty()) {
            ""
        } else {
            decodeField(field, maximumBytes)
        }

    private fun encodeField(field: String): String =
        Base64.encodeToString(
            field.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun safeName(name: String): Boolean =
        name.length in 1..255 &&
            boundedUtf8Text(name, MAX_DOCUMENT_NAME_BYTES) &&
            name != "." &&
            name != ".." &&
            name.none { character ->
                character == '/' ||
                    character == '\\' ||
                    character == '\u0000' ||
                    character.code < 32 ||
                    character.code == 127
            }

    private fun validNotificationText(
        value: String,
        maximumCharacters: Int,
        allowWhitespace: Boolean,
    ): Boolean =
        value.isNotEmpty() &&
            value.length <= maximumCharacters &&
            value.none { character ->
                character == '\u0000' ||
                    (
                        character.isISOControl() &&
                            !(allowWhitespace && (character == '\n' || character == '\t'))
                    )
            }

    private fun validSecretText(
        value: String,
        maximumCharacters: Int,
        allowEmpty: Boolean,
    ): Boolean =
        value.length <= maximumCharacters &&
            (allowEmpty || value.isNotEmpty()) &&
            value.none(Char::isISOControl)

    private fun validSecretResponse(
        operation: String,
        response: String,
    ): Boolean {
        if (
            response.length > MAX_SECRET_RESPONSE_BYTES ||
            response.any { character ->
                character.code !in 0x20..0x7e && character != '\t'
            }
        ) {
            return false
        }
        if (response == "ERROR\tFAILED" || response == "ERROR\tINVALID_REQUEST") {
            return true
        }
        return when (operation) {
            "STORE_SECRET",
            "DELETE_SECRET",
            -> response == "OK"
            "READ_SECRET" -> {
                if (response == "ERROR\tNOT_FOUND") {
                    true
                } else {
                    val fields = splitPortalFields(response, 4) ?: return false
                    fields.size == 4 &&
                        fields[0] == "OK" &&
                        decodeFieldAllowEmpty(fields[1], MAX_SECRET_LABEL_BYTES) != null &&
                        decodeField(fields[2], MAX_SECRET_ATTRIBUTES_BYTES) != null &&
                        fields[3].toIntOrNull() in 0..MAX_SECRET_VALUE_BYTES
                }
            }
            "LIST_SECRETS",
            "CATALOG_SECRETS",
            -> {
                val fields = splitPortalFields(response, 2) ?: return false
                fields.size == 2 &&
                    fields[0] == "OK" &&
                    fields[1].toIntOrNull() in 0..MAX_SECRET_ITEMS
            }
            else -> false
        }
    }

    private fun validCameraResponse(
        operation: String,
        response: String,
    ): Boolean {
        if (response in CAMERA_ERROR_RESPONSES) return true
        return when (operation) {
            "REQUEST_CAMERA",
            "CHECK_CAMERA",
            "STREAM_CAMERA_I420",
            -> response == "OK"
            "CAPTURE_CAMERA_JPEG" -> {
                val fields = splitPortalFields(response, 4) ?: return false
                fields.size == 4 &&
                    fields[0] == "OK" &&
                    fields[1].toIntOrNull() in 1..MAX_CAMERA_DIMENSION &&
                    fields[2].toIntOrNull() in 1..MAX_CAMERA_DIMENSION &&
                    fields[3].toIntOrNull() in 1..MAX_CAMERA_JPEG_BYTES
            }
            else -> false
        }
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { value ->
            "%02x".format(value.toInt() and 0xff)
        }

    private fun drain(
        process: java.lang.Process,
        label: String,
    ): Thread =
        thread(start = true, isDaemon = true, name = "ArchphenePortal-$label-$sessionId") {
            runCatching {
                drainBoundedUtf8Lines(process.inputStream, MAX_LOG_LINE_BYTES) { line ->
                    Log.i(TAG, "$label session=$sessionId: $line")
                }
            }
        }

    private fun joinWorker(
        worker: Thread?,
        label: String,
    ): Boolean {
        if (worker == null) return true
        if (worker === Thread.currentThread()) return false
        try {
            worker.join(WORKER_STOP_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return !worker.isAlive
        }
        if (worker.isAlive) {
            Log.w(TAG, "Portal worker did not stop label=$label session=$sessionId")
            return false
        }
        return true
    }

    private fun busConfiguration(socketPath: String): String {
        val escaped =
            socketPath
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        return "<!DOCTYPE busconfig PUBLIC \"-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN\" " +
            "\"http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd\">" +
            "<busconfig><type>session</type><listen>unix:path=$escaped</listen>" +
            "<auth>EXTERNAL</auth><policy context=\"default\"><allow own=\"*\"/>" +
            "<allow send_destination=\"*\"/><allow receive_sender=\"*\"/></policy>" +
            "<limit name=\"max_completed_connections\">16</limit>" +
            "<limit name=\"max_connections_per_user\">16</limit>" +
            "<limit name=\"max_message_size\">1048576</limit>" +
            "<limit name=\"max_incoming_bytes\">4194304</limit>" +
            "<limit name=\"max_outgoing_bytes\">4194304</limit></busconfig>"
    }

    companion object {
        private data class CameraRequest(
            val width: Int,
            val height: Int,
            val front: Boolean,
            val descriptor: ParcelFileDescriptor?,
        )

        private const val TAG = "ArchphenePortal"
        private const val DAEMON = "libarchphene_dbus_daemon.so"
        private const val PORTAL = "libarchphene_portal_service.so"
        private const val APPEARANCE_STATE = "appearance-v1"
        private const val APPEARANCE_STATE_TEMPORARY = "appearance-v1.tmp"
        private const val APPEARANCE_STATE_BYTES = 11
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val PORTAL_READY_DELAY_MILLIS = 100L
        private const val PROCESS_STOP_TIMEOUT_MILLIS = 2_000L
        private const val WORKER_STOP_TIMEOUT_MILLIS = 2_000L
        private const val CLEANUP_RETRY_MILLIS = 250L
        private const val MAX_LOG_LINE_BYTES = 512
        private const val BROKER_IO_TIMEOUT_MILLIS = 1_000
        private const val MAX_BROKER_CLIENTS = 4
        private const val MAX_REQUEST_BYTES = 16_384
        private const val MAX_REQUEST_FIELDS = 6
        private const val MAX_TITLE_BYTES = 512
        private const val MAX_NAME_BYTES = 512
        private const val MAX_DOCUMENT_NAME_BYTES = 255
        private const val MAX_MIME_BYTES = PortalMimePolicy.MAX_SPEC_UTF16
        private const val MAX_NOTIFICATION_ID_BYTES = 512
        private const val MAX_NOTIFICATION_TITLE_BYTES = 1_024
        private const val MAX_NOTIFICATION_BODY_BYTES = 8_192
        private const val MAX_NOTIFICATION_ID_CHARACTERS = 128
        private const val MAX_NOTIFICATION_TITLE_CHARACTERS = 256
        private const val MAX_NOTIFICATION_BODY_CHARACTERS = 4_096
        private const val MAX_PRINT_TITLE_CHARACTERS = 256
        private const val MAX_SECRET_ID_BYTES = 512
        private const val MAX_SECRET_LABEL_BYTES = 1_024
        private const val MAX_SECRET_ATTRIBUTES_BYTES = 8 * 1_024
        private const val MAX_SECRET_CONTENT_TYPE_BYTES = 512
        private const val MAX_SECRET_ID_CHARACTERS = 128
        private const val MAX_SECRET_LABEL_CHARACTERS = 256
        private const val MAX_SECRET_CONTENT_TYPE_CHARACTERS = 128
        private const val MAX_SECRET_VALUE_BYTES = 64 * 1_024
        private const val MAX_SECRET_RESPONSE_BYTES = 16 * 1_024
        private const val MAX_SECRET_ITEMS = 256
        private const val MAX_CAMERA_DIMENSION = 8_192
        private const val MAX_CAMERA_JPEG_BYTES = 32 * 1_024 * 1_024
        private const val ACCESSIBILITY_TREE_MIN_BYTES = 24L
        private const val ACCESSIBILITY_TREE_MAX_BYTES = 1_024L * 1_024
        private const val MAX_ACCESSIBILITY_NODE_ID = 1_000_000
        private const val MAX_ACCESSIBILITY_POLL_MILLIS = 250
        private const val MAX_ACCESSIBILITY_ACTION_TEXT_BYTES = 4_096
        private const val MAX_ACCESSIBILITY_ACTION_ENCODED_BYTES = 5_464
        private const val UNIX_SOCKET_PATH_LIMIT = 104
        private const val MAX_DEBUG_PRINT_BYTES = 65_536
        private const val MAX_ACTIVE_SAVES = 8
        private const val MAX_OPEN_DOCUMENTS = 32
        private const val MAX_IMPORT_COLLISIONS = 1_000
        private const val MAX_SAVE_BYTES = 512L * 1024 * 1024
        private const val MAX_OPEN_BYTES = 512L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MIRROR_POLL_MILLIS = 250L
        private const val REQUIRED_STABLE_POLLS = 2
        private const val EMPTY_SAVE_GRACE_MILLIS = 5_000L
        private const val MAX_RECOVERED_SAVE_DIRECTORIES = 128
        private const val MAX_RECOVERED_SAVES = 32
        private const val MAX_RECOVERED_SAVE_BYTES = 1_024L * 1_024 * 1_024
        private const val MAX_PORTAL_CACHE_ENTRIES = 4_096
        private const val MAX_RUNTIME_ENTRIES = 4
        private const val HEX = "0123456789abcdef"
        private val runtimeLifecycleLock = Any()
        private val runtimeRegistry = RuntimeLifecycleRegistry<LauncherPortalBridge>()
        private val recoveryRandom = SecureRandom()
        private val AUDIO_INPUT_RESPONSES =
            setOf(
                "OK",
                "ERROR\tPERMISSION_REQUESTED",
                "ERROR\tPERMISSION_DENIED",
                "ERROR\tPERMISSION_NOT_REQUESTED",
                "ERROR\tUNAVAILABLE",
                "ERROR\tFAILED",
            )
        private val CAMERA_ERROR_RESPONSES =
            setOf(
                "ERROR\tPERMISSION_REQUESTED",
                "ERROR\tPERMISSION_DENIED",
                "ERROR\tPERMISSION_NOT_REQUESTED",
                "ERROR\tUNAVAILABLE",
                "ERROR\tNOT_READY",
                "ERROR\tINVALID_REQUEST",
                "ERROR\tFAILED",
            )
        private val ACCESSIBILITY_EVENTS =
            setOf("focus", "selected", "text", "clicked", "window", "content")
        private val ACCESSIBILITY_ACTIONS =
            setOf(
                "click",
                "focus",
                "set-text",
                "scroll-forward",
                "scroll-backward",
                "refresh",
            )
        private val CONCURRENT_OPERATIONS =
            setOf(
                "PUBLISH_ACCESSIBILITY_TREE",
                "ACCESSIBILITY_EVENT",
                "TAKE_ACCESSIBILITY_ACTION",
                "ACCESSIBILITY_MENU_FALLBACK",
                "ACCESSIBILITY_MENU_ACTION",
            )
        private val DESCRIPTOR_OPERATIONS =
            setOf(
                "PRINT_PDF",
                "STORE_SECRET",
                "READ_SECRET",
                "LIST_SECRETS",
                "CATALOG_SECRETS",
                "CAPTURE_CAMERA_JPEG",
                "STREAM_CAMERA_I420",
                "PUBLISH_ACCESSIBILITY_TREE",
            )
        private val STALE_SAVE_DIRECTORY_NAME =
            Regex("[1-9][0-9]*(-[0-9a-f]{16})?")
        private val STALE_RUNTIME_DIRECTORY_NAME =
            Regex("p[1-9][0-9]*-[0-9a-f]{16}")
        private val RECOVERED_SAVE_NAME = Regex("Recovered portal save [0-9a-f]{32}")

        internal fun splitPortalRequest(request: String): List<String>? =
            splitPortalFields(request, MAX_REQUEST_FIELDS)

        internal fun splitPortalFields(
            value: String,
            maximumFields: Int,
        ): List<String>? {
            require(maximumFields in 1..MAX_REQUEST_FIELDS)
            val fields = ArrayList<String>(maximumFields)
            var start = 0
            while (fields.size < maximumFields) {
                val delimiter = value.indexOf('\t', start)
                if (delimiter < 0) {
                    fields.add(value.substring(start))
                    return fields
                }
                fields.add(value.substring(start, delimiter))
                start = delimiter + 1
            }
            return null
        }

        internal fun stopProcessBoundedly(
            process: java.lang.Process?,
            timeoutMillis: Long,
        ): Boolean {
            if (process == null || !process.isAlive) return true
            require(timeoutMillis > 0)
            process.destroy()
            return try {
                if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    true
                } else {
                    process.destroyForcibly()
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                !process.isAlive
            }
        }

        internal fun shouldRecoverPortalPath(
            path: Path,
            ownedPaths: Set<Path>,
        ): Boolean = path.toAbsolutePath().normalize() !in ownedPaths

        internal fun recoverPortalSaveFile(
            source: File,
            recoveryDirectory: File,
        ): String {
            check(
                Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(source.toPath()) &&
                    source.length() in 0..MAX_SAVE_BYTES &&
                    Files.isDirectory(recoveryDirectory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(recoveryDirectory.toPath()),
            ) {
                "Invalid portal save recovery path"
            }
            var recoveredCount = 0
            var recoveredBytes = 0L
            Files.newDirectoryStream(recoveryDirectory.toPath()).use { entries ->
                for (entry in entries) {
                    check(recoveredCount++ < MAX_RECOVERED_SAVES) {
                        "Too many recovered portal saves"
                    }
                    check(
                        entry.fileName.toString().matches(RECOVERED_SAVE_NAME) &&
                            Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(entry),
                    ) {
                        "Invalid recovered portal save"
                    }
                    recoveredBytes += Files.size(entry)
                    check(recoveredBytes <= MAX_RECOVERED_SAVE_BYTES) {
                        "Recovered portal saves exceed the byte limit"
                    }
                }
            }
            if (
                recoveredCount >= MAX_RECOVERED_SAVES ||
                recoveredBytes > MAX_RECOVERED_SAVE_BYTES - source.length()
            ) {
                throw PortalRecoveryCapacityException(
                    "Recovered portal save capacity is exhausted",
                )
            }
            repeat(MAX_IMPORT_COLLISIONS) {
                val token = ByteArray(16).also(recoveryRandom::nextBytes)
                val name =
                    "Recovered portal save " +
                        token.joinToString("") { value ->
                            "%02x".format(value.toInt() and 0xff)
                        }
                val target = File(recoveryDirectory, name).canonicalFile
                check(target.parentFile == recoveryDirectory.canonicalFile)
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return@repeat
                FileInputStream(source).use { input -> input.fd.sync() }
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
                FileChannel.open(recoveryDirectory.toPath(), StandardOpenOption.READ).use { directory ->
                    directory.force(true)
                }
                return name
            }
            error("Too many recovered portal save collisions")
        }

        internal fun drainBoundedUtf8Lines(
            input: InputStream,
            maximumLineBytes: Int,
            consume: (String) -> Unit,
        ) {
            require(maximumLineBytes > 0)
            val retained = ByteArray(maximumLineBytes)
            val chunk = ByteArray(1024)
            var retainedBytes = 0
            var sawBytes = false
            var carriageReturn = false

            fun publish() {
                consume(String(retained, 0, retainedBytes, StandardCharsets.UTF_8))
                retainedBytes = 0
                sawBytes = false
            }

            input.use { stream ->
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val value = chunk[index]
                        when (value) {
                            '\r'.code.toByte() -> {
                                publish()
                                carriageReturn = true
                            }
                            '\n'.code.toByte() -> {
                                if (!carriageReturn) publish()
                                carriageReturn = false
                            }
                            else -> {
                                carriageReturn = false
                                sawBytes = true
                                if (retainedBytes < retained.size) {
                                    retained[retainedBytes++] = value
                                }
                            }
                        }
                    }
                }
            }
            if (sawBytes) publish()
        }

        fun recoverStaleRuntime(cacheRoot: File) {
            synchronized(runtimeLifecycleLock) {
                runtimeRegistry.unreapedSnapshot().forEach { bridge -> bridge.closeLocked() }
                val ownedPaths =
                    runtimeRegistry.ownedSnapshot().mapTo(HashSet()) { bridge ->
                        bridge.runtimeDirectory.toPath().toAbsolutePath().normalize()
                    }
            check(
                cacheRoot.isDirectory &&
                    !Files.isSymbolicLink(cacheRoot.toPath()),
            ) {
                "Invalid portal cache root"
            }
            val directories = ArrayList<File>(MAX_RECOVERED_SAVE_DIRECTORIES)
            visitBoundedDirectoryEntries(
                cacheRoot,
                MAX_PORTAL_CACHE_ENTRIES,
                "Too many portal cache entries",
            ) { entry ->
                if (entry.name.matches(STALE_RUNTIME_DIRECTORY_NAME)) {
                    check(directories.size < MAX_RECOVERED_SAVE_DIRECTORIES) {
                        "Too many stale portal runtime directories"
                    }
                    directories.add(entry)
                }
            }
            for (directory in directories) {
                if (!shouldRecoverPortalPath(directory.toPath(), ownedPaths)) continue
                check(
                    !Files.isSymbolicLink(directory.toPath()) &&
                        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        directory.canonicalFile.parentFile == cacheRoot.canonicalFile,
                ) {
                    "Unsafe stale portal runtime directory"
                }
                val entries =
                    collectBoundedDirectoryEntries(
                        directory,
                        MAX_RUNTIME_ENTRIES,
                        "Too many files in stale portal runtime directory",
                    )
                for (entry in entries) {
                    check(
                        !Files.isSymbolicLink(entry.toPath()) &&
                            entry.canonicalFile.parentFile == directory.canonicalFile,
                    ) {
                        "Unsafe stale portal runtime entry"
                    }
                    check(entry.delete()) { "Could not remove stale portal runtime entry" }
                }
                check(directory.delete()) { "Could not remove stale portal runtime directory" }
            }
            }
        }

        fun recoverStaleSaves(archRoot: File) {
            synchronized(runtimeLifecycleLock) {
                runtimeRegistry.unreapedSnapshot().forEach { bridge -> bridge.closeLocked() }
                val ownedPaths =
                    runtimeRegistry.ownedSnapshot().mapTo(HashSet()) { bridge ->
                        bridge.savesDirectory.toPath().toAbsolutePath().normalize()
                    }
            if (!archRoot.exists()) return
            val base = File(archRoot, "home/archphene/.cache/archphene/portal-save")
            if (!base.exists()) return
            requireTrustedDirectoryChain(archRoot, base)
            val recoveryDirectory = File(checkNotNull(archRoot.parentFile), "portal-save-recovery")
            prepareRecoveryDirectory(recoveryDirectory)
            val directories =
                collectBoundedDirectoryEntries(
                    base,
                    MAX_RECOVERED_SAVE_DIRECTORIES,
                    "Too many stale portal save directories",
                )
            for (directory in directories) {
                if (!shouldRecoverPortalPath(directory.toPath(), ownedPaths)) continue
                val path = directory.toPath()
                val compatibleName = directory.name.matches(STALE_SAVE_DIRECTORY_NAME)
                check(
                    compatibleName &&
                        !Files.isSymbolicLink(path) &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        directory.canonicalFile.parentFile == base.canonicalFile,
                ) {
                    "Unsafe stale portal save directory"
                }
                val entries =
                    collectBoundedDirectoryEntries(
                        directory,
                        MAX_ACTIVE_SAVES,
                        "Too many files in stale portal save directory",
                    )
                val recoveryEntries = ArrayList<Pair<File, File?>>(entries.size)
                for (entry in entries) {
                    val entryPath = entry.toPath()
                    if (Files.isDirectory(entryPath, LinkOption.NOFOLLOW_LINKS)) {
                        check(
                            !Files.isSymbolicLink(entryPath) &&
                                entry.canonicalFile.parentFile == directory.canonicalFile,
                        ) {
                            "Unsafe stale portal save slot"
                        }
                        val staged =
                            collectBoundedDirectoryEntries(
                                entry,
                                1,
                                "Invalid stale portal save slot",
                            )
                        val file = staged.singleOrNull()
                        if (file == null) {
                            recoveryEntries.add(entry to null)
                            continue
                        }
                        check(
                            !Files.isSymbolicLink(file.toPath()) &&
                                Files.isRegularFile(
                                    file.toPath(),
                                    LinkOption.NOFOLLOW_LINKS,
                                ) &&
                                file.canonicalFile.parentFile == entry.canonicalFile,
                        ) {
                            "Unsafe stale portal save"
                        }
                        recoveryEntries.add(entry to file)
                    } else {
                        check(
                            !Files.isSymbolicLink(entryPath) &&
                                Files.isRegularFile(entryPath, LinkOption.NOFOLLOW_LINKS) &&
                                entry.canonicalFile.parentFile == directory.canonicalFile,
                        ) {
                            "Unsafe legacy portal save"
                        }
                        recoveryEntries.add(entry to entry)
                    }
                }
                for ((entry, staged) in recoveryEntries) {
                    if (staged != null) recoverPortalSaveFile(staged, recoveryDirectory)
                    check(!entry.exists() || entry.delete()) {
                        "Could not remove stale portal save entry"
                    }
                }
                check(directory.delete()) { "Could not remove stale portal save directory" }
            }
            }
        }

        private fun requireTrustedDirectoryChain(
            archRoot: File,
            target: File,
        ) {
            check(
                archRoot.isDirectory &&
                    !Files.isSymbolicLink(archRoot.toPath()) &&
                    target.toPath().normalize().startsWith(archRoot.toPath().normalize()),
            ) {
                "Invalid Arch root for portal saves"
            }
            var current = archRoot
            val relative = archRoot.toPath().normalize().relativize(target.toPath().normalize())
            for (segment in relative) {
                current = File(current, segment.toString())
                check(!Files.isSymbolicLink(current.toPath())) {
                    "Portal save path contains a symbolic link"
                }
                requireDirectoryStatic(current)
            }
            check(target.canonicalFile.toPath().startsWith(archRoot.canonicalFile.toPath())) {
                "Portal save path escaped the Arch root"
            }
        }

        private fun prepareRecoveryDirectory(directory: File) {
            val parent = checkNotNull(directory.parentFile).canonicalFile
            check(
                parent.isDirectory &&
                    !Files.isSymbolicLink(parent.toPath()) &&
                    directory.canonicalFile.parentFile == parent &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    (directory.isDirectory || directory.mkdir()),
            ) {
                "Invalid portal save recovery directory"
            }
            runCatching { Os.chmod(directory.absolutePath, 0b111_000_000) }
        }

        private fun requireDirectoryStatic(directory: File) {
            check(directory.isDirectory || directory.mkdir()) {
                "Could not create ${directory.absolutePath}"
            }
        }
    }
}
