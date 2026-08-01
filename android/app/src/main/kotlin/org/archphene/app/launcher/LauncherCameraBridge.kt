package org.archphene.app.launcher

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class RuntimeLifecycleRegistry<T> {
    private val owned = LinkedHashSet<T>()
    private val unreaped = LinkedHashSet<T>()

    @Synchronized
    fun claim(value: T) {
        owned.add(value)
    }

    @Synchronized
    fun finish(
        value: T,
        terminated: Boolean,
    ) {
        if (terminated) {
            unreaped.remove(value)
            owned.remove(value)
        } else {
            owned.add(value)
            unreaped.add(value)
        }
    }

    @Synchronized
    fun hasUnreaped(): Boolean = unreaped.isNotEmpty()

    @Synchronized
    fun unreapedSnapshot(): List<T> = unreaped.toList()

    @Synchronized
    fun ownedSnapshot(): List<T> = owned.toList()

    @Synchronized
    fun hasOwnedMatching(predicate: (T) -> Boolean): Boolean = owned.any(predicate)
}

/**
 * Session-scoped minimal PipeWire camera remote.
 *
 * All executable bytes remain APK-owned. This class publishes a bounded view
 * of verified loader/runtime symlinks plus the pinned camera payload and starts
 * one helpers-only supervisor. The Linux application sees only its private
 * PipeWire socket through the XDG camera portal.
 */
internal class LauncherCameraBridge(
    context: Context,
    private val sessionId: Int,
) : AutoCloseable {
    private val cacheRoot = context.cacheDir.toPath()
    private val archRoot = File(context.filesDir, "arch-root")
    private val packageRuntime = File(archRoot, "run/package-runtime-v1")
    private val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val runtimeDirectory =
        File(context.cacheDir, "$RUNTIME_PREFIX$sessionId-${randomToken()}")
    private val pipeWireSocket = File(runtimeDirectory, PIPEWIRE_SOCKET)
    private var process: Process? = null
    private var logThread: Thread? = null

    val socketPath: String
        get() = pipeWireSocket.absolutePath

    fun start(brokerAddress: String): Boolean =
        synchronized(runtimeLifecycleLock) {
            closeLocked()
            runtimeRegistry.unreapedSnapshot().forEach { bridge ->
                if (bridge !== this) bridge.closeLocked()
            }
            runtimeRegistry.ownedSnapshot().forEach { bridge ->
                if (bridge !== this && bridge.sessionId == sessionId) bridge.closeLocked()
            }
            if (
                runtimeRegistry.hasUnreaped() ||
                runtimeRegistry.hasOwnedMatching { bridge ->
                    bridge !== this && bridge.sessionId == sessionId
                }
            ) {
                Log.e(TAG, "An earlier camera runtime still owns session=$sessionId")
                return@synchronized false
            }
            startLocked(brokerAddress)
        }

    private fun startLocked(brokerAddress: String): Boolean {
        if (process?.isAlive == true || logThread?.isAlive == true) {
            Log.e(TAG, "Prior camera helper did not terminate session=$sessionId")
            return false
        }
        runtimeRegistry.claim(this)
        if (
            !validBrokerAddress(brokerAddress) ||
            !prepareRuntimeDirectory() ||
            socketPath.toByteArray(StandardCharsets.UTF_8).size >= UNIX_SOCKET_PATH_LIMIT
        ) {
            close()
            return false
        }
        val loader = File(runtimeDirectory, loaderName())
        val supervisor = File(runtimeDirectory, "archphene-runtime-supervisor")
        if (!loader.exists() || !supervisor.exists()) {
            Log.e(TAG, "Camera runtime loader or supervisor is unavailable session=$sessionId")
            close()
            return false
        }
        return runCatching {
            val child =
                ProcessBuilder(
                    loader.absolutePath,
                    "--library-path",
                    runtimeDirectory.absolutePath,
                    supervisor.absolutePath,
                    loader.absolutePath,
                    runtimeDirectory.absolutePath,
                    "--helpers-only",
                ).redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["XDG_RUNTIME_DIR"] = runtimeDirectory.absolutePath
                        environment()["ARCHPHENE_ANDROID_BROKER"] = brokerAddress
                        environment()["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
                        environment()["HOME"] = File(archRoot, "home/archphene").absolutePath
                        environment()["LANG"] = "C.UTF-8"
                        environment()["LC_ALL"] = "C.UTF-8"
                    }.start()
            process = child
            logThread =
                thread(
                    start = true,
                    isDaemon = true,
                    name = "ArchpheneCameraRuntime-$sessionId",
                ) {
                    runCatching {
                        drainBoundedUtf8Lines(child.inputStream, MAX_LOG_LINE_BYTES) { line ->
                            Log.i(TAG, "runtime session=$sessionId: $line")
                        }
                    }
                }
            val deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
            while (
                !pipeWireSocket.exists() &&
                child.isAlive &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(25)
            }
            check(pipeWireSocket.exists() && child.isAlive) {
                "Camera runtime did not publish its PipeWire socket"
            }
            Log.i(TAG, "Private PipeWire camera ready session=$sessionId")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not start private PipeWire camera session=$sessionId", error)
            close()
            false
        }
    }

    fun isReady(): Boolean =
        synchronized(runtimeLifecycleLock) {
            process?.isAlive == true && pipeWireSocket.exists()
        }

    override fun close() {
        synchronized(runtimeLifecycleLock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        val child = process
        var childTerminated = child == null || !child.isAlive
        if (child != null) {
            child.destroy()
            try {
                if (!child.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly()
                    childTerminated =
                        child.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } else {
                    childTerminated = true
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                child.destroyForcibly()
                childTerminated = !child.isAlive
            }
        }
        if (childTerminated) {
            if (process === child) process = null
        } else {
            Log.w(TAG, "Camera helper did not report exit after forced termination session=$sessionId")
        }
        val worker = logThread
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(STOP_TIMEOUT_MILLIS) }
        }
        val workerTerminated = worker == null || !worker.isAlive
        if (workerTerminated) {
            if (logThread === worker) logThread = null
        } else {
            Log.w(TAG, "Camera log drainer did not terminate session=$sessionId")
        }
        if (childTerminated && workerTerminated) {
            cleanupRuntimeDirectory(runtimeDirectory.toPath(), log = false)
        }
        runtimeRegistry.finish(this, childTerminated && workerTerminated)
    }

    private fun prepareRuntimeDirectory(): Boolean =
        runCatching {
            check(
                Files.isDirectory(cacheRoot) &&
                    !Files.isSymbolicLink(cacheRoot) &&
                    runtimeDirectory.toPath().parent == cacheRoot &&
                    isRuntimeDirectoryName(runtimeDirectory.name) &&
                    !Files.exists(runtimeDirectory.toPath()),
            ) {
                "Unsafe camera runtime directory"
            }
            Files.createDirectory(runtimeDirectory.toPath())
            check(
                packageRuntime.isDirectory &&
                    !Files.isSymbolicLink(packageRuntime.toPath()),
            ) {
                "Verified package runtime is unavailable"
            }
            val runtimeEntries = packageRuntime.listFiles() ?: error("Could not list runtime")
            check(runtimeEntries.size in 1..MAX_RUNTIME_LINKS) {
                "Verified package runtime exceeds the camera link bound"
            }
            for (entry in runtimeEntries) {
                check(
                    safeName(entry.name) &&
                        Files.isSymbolicLink(entry.toPath()),
                ) {
                    "Unsafe package runtime entry"
                }
                createLink(File(runtimeDirectory, entry.name), entry)
            }
            for ((name, payload) in PAYLOAD_LINKS) {
                val source = File(nativeLibraryDirectory, payload)
                check(source.isFile && source.canExecute()) {
                    "Camera payload is missing: $payload"
                }
                createLink(File(runtimeDirectory, name), source)
            }
            for ((directory, links) in NESTED_PAYLOAD_LINKS) {
                val targetDirectory = File(runtimeDirectory, directory)
                check(
                    targetDirectory.canonicalPath.startsWith(
                        runtimeDirectory.canonicalPath + File.separator,
                    ) &&
                        targetDirectory.mkdirs(),
                ) {
                    "Could not create camera payload directory"
                }
                for ((name, payload) in links) {
                    val source = File(nativeLibraryDirectory, payload)
                    check(source.isFile && source.canExecute()) {
                        "Camera payload is missing: $payload"
                    }
                    createLink(File(targetDirectory, name), source)
                }
            }
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not prepare camera runtime session=$sessionId", error)
            false
        }

    private fun createLink(
        destination: File,
        source: File,
    ) {
        check(
            destination.parentFile?.canonicalFile?.let(::insideRuntimeDirectory) == true &&
                !destination.exists() &&
                !Files.isSymbolicLink(destination.toPath()),
        ) {
            "Unsafe camera runtime link"
        }
        Os.symlink(source.absolutePath, destination.absolutePath)
    }

    private fun insideRuntimeDirectory(directory: File): Boolean {
        val root = runtimeDirectory.canonicalFile
        var current: File? = directory
        repeat(MAX_RUNTIME_DEPTH) {
            if (current == root) return true
            current = current?.parentFile
        }
        return false
    }

    private fun loaderName(): String =
        when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "ld-linux-aarch64.so.1"
            "x86_64" -> "ld-linux-x86-64.so.2"
            else -> error("Unsupported Android ABI")
        }

    private fun validBrokerAddress(value: String): Boolean =
        value.startsWith("@archphene.portal.") &&
            value.length in 24 until UNIX_SOCKET_PATH_LIMIT &&
            value.none { character -> character == '\u0000' || character.isISOControl() }

    private fun safeName(value: String): Boolean =
        value.length in 1..128 &&
            value != "." &&
            value != ".." &&
            value.all { character ->
                character.isLetterOrDigit() ||
                    character == '.' ||
                    character == '_' ||
                    character == '-' ||
                    character == '+'
            }

    companion object {
        private const val TAG = "ArchpheneCameraRuntime"
        private const val RUNTIME_PREFIX = "camera-"
        private const val PIPEWIRE_SOCKET = "pipewire-0"
        private const val TOKEN_LENGTH = 16
        private const val MAX_RUNTIME_LINKS = 128
        private const val MAX_RUNTIME_ENTRIES = 160
        private const val MAX_RUNTIME_DEPTH = 3
        private const val MAX_STALE_DIRECTORIES = 32
        private const val UNIX_SOCKET_PATH_LIMIT = 104
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
        private const val MAX_LOG_LINE_BYTES = 512
        private const val HEX = "0123456789abcdef"
        private val random = SecureRandom()
        private val runtimeLifecycleLock = Any()
        private val runtimeRegistry = RuntimeLifecycleRegistry<LauncherCameraBridge>()
        private val PAYLOAD_LINKS =
            arrayOf(
                "libpipewire-0.3.so.0" to "libarchphene_pipewire_client.so",
                "archphene-pipewire" to "libarchphene_pipewire_daemon.so",
                "archphene-pipewire-camera" to "libarchphene_pipewire_camera.so",
                "archphene-pipewire-policy" to "libarchphene_pipewire_policy.so",
                "archphene-runtime-supervisor" to "libarchphene_pipewire_supervisor.so",
            )
        private val NESTED_PAYLOAD_LINKS =
            arrayOf(
                "pipewire-0.3" to
                    arrayOf(
                        "libpipewire-module-protocol-native.so" to
                            "libarchphene_pw_module_protocol_native.so",
                        "libpipewire-module-access.so" to
                            "libarchphene_pw_module_access.so",
                        "libpipewire-module-metadata.so" to
                            "libarchphene_pw_module_metadata.so",
                        "libpipewire-module-client-node.so" to
                            "libarchphene_pw_module_client_node.so",
                        "libpipewire-module-adapter.so" to
                            "libarchphene_pw_module_adapter.so",
                        "libpipewire-module-link-factory.so" to
                            "libarchphene_pw_module_link_factory.so",
                    ),
                "spa-0.2/support" to
                    arrayOf(
                        "libspa-support.so" to "libarchphene_spa_support.so",
                    ),
                "spa-0.2/videoconvert" to
                    arrayOf(
                        "libspa-videoconvert.so" to "libarchphene_spa_videoconvert.so",
                    ),
            )

        fun cleanupStaleRuntimeDirectories(context: Context) {
            synchronized(runtimeLifecycleLock) {
                runtimeRegistry.unreapedSnapshot().forEach { bridge -> bridge.closeLocked() }
                val ownedPaths =
                    runtimeRegistry
                        .ownedSnapshot()
                        .mapTo(HashSet()) { bridge -> bridge.runtimeDirectory.toPath() }
                val cache = context.cacheDir.toPath()
                if (!Files.isDirectory(cache) || Files.isSymbolicLink(cache)) return
                runCatching {
                    var visited = 0
                    Files.newDirectoryStream(cache, "$RUNTIME_PREFIX*").use { entries ->
                        for (entry in entries) {
                            if (visited++ >= MAX_STALE_DIRECTORIES) {
                                Log.w(TAG, "Camera stale-runtime cleanup reached its bounded limit")
                                break
                            }
                            if (
                                shouldCleanupRuntimeDirectory(entry, ownedPaths)
                            ) {
                                cleanupRuntimeDirectory(entry, log = true)
                            }
                        }
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Could not inspect stale camera runtime directories", error)
                }
            }
        }

        internal fun isRuntimeDirectoryName(name: String): Boolean {
            if (!name.startsWith(RUNTIME_PREFIX)) return false
            val separator = name.length - TOKEN_LENGTH - 1
            if (separator <= RUNTIME_PREFIX.length || name[separator] != '-') return false
            val session = name.substring(RUNTIME_PREFIX.length, separator)
            if (
                session.isEmpty() ||
                session[0] !in '1'..'9' ||
                session.any { character -> character !in '0'..'9' }
            ) {
                return false
            }
            return name.substring(separator + 1).all { character ->
                character in '0'..'9' || character in 'a'..'f'
            }
        }

        internal fun shouldCleanupRuntimeDirectory(
            path: Path,
            ownedPaths: Set<Path>,
        ): Boolean =
            path !in ownedPaths && isRuntimeDirectoryName(path.fileName.toString())

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

            fun publish() {
                var length = retainedBytes
                if (length > 0 && retained[length - 1] == '\r'.code.toByte()) length--
                consume(String(retained, 0, length, StandardCharsets.UTF_8))
                retainedBytes = 0
                sawBytes = false
            }

            input.use { stream ->
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val value = chunk[index]
                        if (value == '\n'.code.toByte()) {
                            publish()
                        } else {
                            sawBytes = true
                            if (retainedBytes < retained.size) {
                                retained[retainedBytes++] = value
                            }
                        }
                    }
                }
            }
            if (sawBytes) publish()
        }

        private fun cleanupRuntimeDirectory(
            path: Path,
            log: Boolean,
        ) {
            if (
                !isRuntimeDirectoryName(path.fileName.toString()) ||
                Files.isSymbolicLink(path) ||
                !Files.isDirectory(path)
            ) {
                return
            }
            var visited = 0
            fun deleteChildren(
                directory: Path,
                depth: Int,
            ) {
                check(depth <= MAX_RUNTIME_DEPTH) { "Camera runtime nesting exceeds its bound" }
                Files.newDirectoryStream(directory).use { entries ->
                    for (entry in entries) {
                        check(++visited <= MAX_RUNTIME_ENTRIES) {
                            "Camera runtime entry count exceeds its bound"
                        }
                        if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                            deleteChildren(entry, depth + 1)
                        } else {
                            Files.deleteIfExists(entry)
                        }
                    }
                }
                Files.deleteIfExists(directory)
            }
            runCatching { deleteChildren(path, 0) }
                .onSuccess {
                    if (log) Log.i(TAG, "Removed stale camera runtime directory")
                }.onFailure { error ->
                    Log.w(TAG, "Could not remove camera runtime directory", error)
                }
        }

        private fun randomToken(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return CharArray(bytes.size * 2).also { output ->
                bytes.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    output[index * 2] = HEX[value ushr 4]
                    output[index * 2 + 1] = HEX[value and 0x0f]
                }
            }.concatToString()
        }
    }
}
