package org.archphene.app.launcher

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class LauncherPortalSaveResult(
    val descriptor: ParcelFileDescriptor?,
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
    private val dark: Boolean,
    private val accent: Int,
    private val requestSave: (String, String, String) -> LauncherPortalSaveResult,
    private val requestOpen: (String, String, Boolean) -> LauncherPortalOpenResult,
) : Closeable {
    private class ActiveSave(
        val staging: File,
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
    private val activeSaves = ArrayList<ActiveSave>(MAX_ACTIVE_SAVES)
    @Volatile private var saveSnapshot = emptyArray<ActiveSave>()
    @Volatile private var running = false
    private var server: LocalServerSocket? = null
    private var brokerThread: Thread? = null
    private var mirrorThread: Thread? = null
    private var daemon: java.lang.Process? = null
    private var portal: java.lang.Process? = null
    private var busSocket: File? = null
    private var nextSaveId = 1

    lateinit var busAddress: String
        private set

    @Synchronized
    fun start() {
        check(!running) { "Portal bridge is already running" }
        requireDirectory(runtimeDirectory)
        prepareSavesDirectory()
        prepareImportsDirectory()
        val socketName =
            "archphene.portal.${Process.myPid()}.$sessionId.${randomHex(8)}"
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
        try {
            startDesktopPortal("@$socketName")
            mirrorThread = thread(
                start = true,
                isDaemon = true,
                name = "ArchphenePortalMirror-$sessionId",
            ) {
                mirrorLoop()
            }
        } catch (error: Exception) {
            close()
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
        check(socketPath.toByteArray(StandardCharsets.UTF_8).size < 100) {
            "D-Bus socket path is too long"
        }
        val config = File(runtimeDirectory, "session.conf")
        FileOutputStream(config, false).use { output ->
            output.write(busConfiguration(socketPath).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        daemon =
            ProcessBuilder(
                daemonFile.absolutePath,
                "--config-file=${config.absolutePath}",
                "--nofork",
                "--nopidfile",
            ).redirectErrorStream(true)
                .start()
                .also { process -> drain(process, "dbus") }
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
        portal =
            ProcessBuilder(portalFile.absolutePath)
                .redirectErrorStream(true)
                .apply {
                    environment()["DBUS_SESSION_BUS_ADDRESS"] = busAddress
                    environment()["ARCHPHENE_ANDROID_BROKER"] = brokerAddress
                    environment()["ARCHPHENE_RUNTIME_DIR"] = runtimeDirectory.absolutePath
                    environment()["ARCHPHENE_APP_NAME"] = appName
                    environment()["ARCHPHENE_COLOR_SCHEME"] = if (dark) "dark" else "light"
                    environment()["ARCHPHENE_ACCENT_RGB"] =
                        String.format(Locale.ROOT, "%06x", accent and 0x00ff_ffff)
                    environment()["ARCHPHENE_ENABLE_SECRETS"] = "0"
                    environment()["ARCHPHENE_ENABLE_CAMERA"] = "0"
                    environment()["ARCHPHENE_ENABLE_ACCESSIBILITY"] = "0"
                }.start()
                .also { process -> drain(process, "portal") }
        SystemClock.sleep(PORTAL_READY_DELAY_MILLIS)
        check(portal?.isAlive == true) { "Private portal frontend exited during startup" }
        Log.i(TAG, "Private desktop portal ready session=$sessionId")
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
            handleClient(socket)
        }
    }

    private fun handleClient(socket: LocalSocket) {
        socket.use { client ->
            runCatching {
                if (client.peerCredentials.uid != Process.myUid()) {
                    writeResponse(client, "ERROR\tUNAUTHORIZED")
                    return
                }
                val request = readRequest(client) ?: run {
                    writeResponse(client, "ERROR\tINVALID_REQUEST")
                    return
                }
                val fields = request.split('\t')
                if (
                    fields.isEmpty() ||
                    (fields[0] != "ARCHPHENE/2" && fields[0] != "ARCHPHENE/3")
                ) {
                    writeResponse(client, "ERROR\tUNSUPPORTED")
                    return
                }
                when (fields.getOrNull(1)) {
                    "SAVE_FILE" -> handleSaveRequest(client, fields)
                    "OPEN_FILE" -> handleOpenRequest(client, fields, multiple = false)
                    "OPEN_FILES" -> handleOpenRequest(client, fields, multiple = true)
                    else -> writeResponse(client, "ERROR\tUNSUPPORTED")
                }
            }.onFailure { error ->
                Log.w(TAG, "Portal broker request failed session=$sessionId", error)
                runCatching { writeResponse(client, "ERROR\tFAILED") }
            }
        }
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
            !safeMime(mime)
        ) {
            writeResponse(client, "ERROR\tINVALID_REQUEST")
            return
        }
        val result = requestSave(title, name, mime)
        val descriptor = result.descriptor
        if (descriptor == null) {
            writeResponse(client, if (result.cancelled) "CANCEL" else "ERROR\tFAILED")
            return
        }
        val uri =
            runCatching { beginSave(name, descriptor) }
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
        if (title == null || mime == null || title.isBlank() || !safeMime(mime)) {
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

    @Synchronized
    private fun beginSave(
        suggestedName: String,
        descriptor: ParcelFileDescriptor,
    ): String {
        check(running && activeSaves.size < MAX_ACTIVE_SAVES)
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        val destinationLength = Os.fstat(output.fd).st_size
        check(destinationLength in 0..MAX_SAVE_BYTES) {
            "Portal destination has an invalid initial size"
        }
        val canonicalStaging = createStaging(suggestedName)
        activeSaves +=
            ActiveSave(
                canonicalStaging,
                output,
                canonicalStaging.lastModified(),
                SystemClock.uptimeMillis(),
                destinationLength,
            )
        saveSnapshot = activeSaves.toTypedArray()
        val logicalPath =
            "/home/archphene/.cache/archphene/portal-save/" +
                "$sessionId-$instanceToken/${canonicalStaging.name}"
        return Uri.Builder().scheme("file").path(logicalPath).build().toString()
    }

    @Synchronized
    private fun beginOpen(
        documents: List<LauncherPortalOpenDocument>,
        multiple: Boolean,
    ): List<String> {
        check(running)
        check(documents.size in 1..MAX_OPEN_DOCUMENTS)
        check(multiple || documents.size == 1)
        check(documents.all { document -> safeName(document.displayName) })
        val imported = ArrayList<File>(documents.size)
        var totalCopied = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            for (document in documents) {
                val target = reserveImport(document.displayName)
                imported += target
                ParcelFileDescriptor.AutoCloseInputStream(document.descriptor).use { input ->
                    FileOutputStream(target, false).use { output ->
                        while (true) {
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
        }
        return imported.map { file ->
            val logicalPath = "/home/archphene/Documents/Android/${file.name}"
            Uri.Builder().scheme("file").path(logicalPath).build().toString()
        }
    }

    private fun createStaging(suggestedName: String): File {
        val saveId = nextSaveId++
        val staging = File(savesDirectory, "$saveId-${randomHex(8)}-$suggestedName")
        val canonicalDirectory = savesDirectory.canonicalFile
        val canonicalStaging = staging.canonicalFile
        check(canonicalStaging.parentFile == canonicalDirectory)
        check(canonicalStaging.createNewFile()) { "Portal staging file already exists" }
        return canonicalStaging
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
        copySave(save, length)
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
        val saves: Array<ActiveSave>
        val portalProcess: java.lang.Process?
        val daemonProcess: java.lang.Process?
        val localServer: LocalServerSocket?
        val brokerWorker: Thread?
        val mirrorWorker: Thread?
        synchronized(this) {
            if (!running && server == null && daemon == null && portal == null) return
            running = false
            saves = activeSaves.toTypedArray()
            activeSaves.clear()
            saveSnapshot = emptyArray()
            portalProcess = portal
            portal = null
            daemonProcess = daemon
            daemon = null
            localServer = server
            server = null
            brokerWorker = brokerThread
            brokerThread = null
            mirrorWorker = mirrorThread
            mirrorThread = null
        }
        runCatching { localServer?.close() }
        joinWorker(mirrorWorker)
        joinWorker(brokerWorker)
        for (save in saves) {
            runCatching {
                val length = save.staging.length()
                val modified = save.staging.lastModified()
                if (
                    save.staging.isFile &&
                    length <= MAX_SAVE_BYTES &&
                    (length != save.copiedLength || modified != save.copiedModified)
                ) {
                    copySave(save, length)
                }
            }.onFailure { error ->
                Log.e(TAG, "Final portal document mirror failed session=$sessionId", error)
            }
            runCatching { save.output.close() }
            runCatching { save.staging.delete() }
        }
        stopProcess(portalProcess)
        stopProcess(daemonProcess)
        runCatching { busSocket?.delete() }
        busSocket = null
        runCatching { runtimeDirectory.deleteRecursively() }
        runCatching { savesDirectory.delete() }
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
        for (entry in savesDirectory.listFiles() ?: error("Could not inspect portal saves")) {
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

    private fun encodeField(field: String): String =
        Base64.encodeToString(
            field.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun safeName(name: String): Boolean =
        name.length in 1..255 &&
            name != "." &&
            name != ".." &&
            name.none { character ->
                character == '/' ||
                    character == '\\' ||
                    character == '\u0000' ||
                    character.code < 32 ||
                    character.code == 127
            }

    private fun safeMime(mime: String): Boolean =
        mime.length in 3..127 &&
            mime.indexOf('/') in 1 until mime.lastIndex &&
            mime.all { character ->
                character.code in 33..126 && character != '\t'
            }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { value ->
            "%02x".format(value.toInt() and 0xff)
        }

    private fun drain(
        process: java.lang.Process,
        label: String,
    ) {
        thread(start = true, isDaemon = true, name = "ArchphenePortal-$label-$sessionId") {
            runCatching {
                BufferedReader(
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
                ).useLines { lines ->
                    lines.forEach { line -> Log.i(TAG, "$label session=$sessionId: $line") }
                }
            }
        }
    }

    private fun stopProcess(process: java.lang.Process?) {
        if (process == null) return
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    private fun joinWorker(worker: Thread?) {
        if (worker == null || worker === Thread.currentThread()) return
        worker.join(WORKER_STOP_TIMEOUT_MILLIS)
        if (worker.isAlive) {
            Log.w(TAG, "Portal worker did not stop promptly session=$sessionId")
        }
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
        private const val TAG = "ArchphenePortal"
        private const val DAEMON = "libarchphene_dbus_daemon.so"
        private const val PORTAL = "libarchphene_portal_service.so"
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val PORTAL_READY_DELAY_MILLIS = 100L
        private const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
        private const val WORKER_STOP_TIMEOUT_MILLIS = 2_000L
        private const val BROKER_IO_TIMEOUT_MILLIS = 1_000
        private const val MAX_REQUEST_BYTES = 16_384
        private const val MAX_TITLE_BYTES = 512
        private const val MAX_NAME_BYTES = 512
        private const val MAX_MIME_BYTES = 128
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
        private const val MAX_RUNTIME_ENTRIES = 4
        private val STALE_SAVE_DIRECTORY_NAME =
            Regex("[1-9][0-9]*(-[0-9a-f]{16})?")
        private val STALE_RUNTIME_DIRECTORY_NAME =
            Regex("p[1-9][0-9]*-[0-9a-f]{16}")

        fun recoverStaleRuntime(cacheRoot: File) {
            check(
                cacheRoot.isDirectory &&
                    !Files.isSymbolicLink(cacheRoot.toPath()),
            ) {
                "Invalid portal cache root"
            }
            val directories =
                cacheRoot.listFiles { entry ->
                    entry.name.matches(STALE_RUNTIME_DIRECTORY_NAME)
                } ?: error("Could not inspect stale portal runtime directories")
            check(directories.size <= MAX_RECOVERED_SAVE_DIRECTORIES) {
                "Too many stale portal runtime directories"
            }
            for (directory in directories) {
                check(
                    !Files.isSymbolicLink(directory.toPath()) &&
                        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        directory.canonicalFile.parentFile == cacheRoot.canonicalFile,
                ) {
                    "Unsafe stale portal runtime directory"
                }
                val entries =
                    directory.listFiles() ?: error("Could not inspect stale portal runtime")
                check(entries.size <= MAX_RUNTIME_ENTRIES) {
                    "Too many files in stale portal runtime directory"
                }
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

        fun recoverStaleSaves(archRoot: File) {
            if (!archRoot.exists()) return
            val base = File(archRoot, "home/archphene/.cache/archphene/portal-save")
            if (!base.exists()) return
            requireTrustedDirectoryChain(archRoot, base)
            val directories =
                base.listFiles() ?: error("Could not inspect stale portal save directories")
            check(directories.size <= MAX_RECOVERED_SAVE_DIRECTORIES) {
                "Too many stale portal save directories"
            }
            for (directory in directories) {
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
                    directory.listFiles() ?: error("Could not inspect stale portal save")
                check(entries.size <= MAX_ACTIVE_SAVES) {
                    "Too many files in stale portal save directory"
                }
                for (entry in entries) {
                    val entryPath = entry.toPath()
                    check(
                        !Files.isSymbolicLink(entryPath) &&
                            Files.isRegularFile(entryPath, LinkOption.NOFOLLOW_LINKS) &&
                            entry.canonicalFile.parentFile == directory.canonicalFile,
                    ) {
                        "Unsafe stale portal save"
                    }
                }
                for (entry in entries) {
                    check(entry.delete()) { "Could not remove stale portal save" }
                }
                check(directory.delete()) { "Could not remove stale portal save directory" }
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

        private fun requireDirectoryStatic(directory: File) {
            check(directory.isDirectory || directory.mkdir()) {
                "Could not create ${directory.absolutePath}"
            }
        }
    }
}
