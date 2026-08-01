package org.archphene.app.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal class AndroidGpuBridge(
    context: Context,
    private val sessionId: Int,
) : AutoCloseable {
    private val helper = File(context.applicationInfo.nativeLibraryDir, HELPER)
    private val runtimeDirectory =
        File(context.cacheDir, "gpu-$sessionId-${randomToken()}")
    private var process: Process? = null
    private var socket: File? = null
    private var reaper: Thread? = null

    @Synchronized
    fun start(): File? {
        if (reaper != null) {
            Log.w(TAG, "Previous GPU helper is still stopping; session=$sessionId uses llvmpipe")
            return null
        }
        close()
        if (process != null) {
            Log.w(TAG, "Previous GPU helper is still stopping; session=$sessionId uses llvmpipe")
            return null
        }
        if (!helper.isFile || !helper.canExecute()) {
            Log.w(TAG, "GPU helper is unavailable; session=$sessionId uses llvmpipe")
            return null
        }
        if (
            Files.isSymbolicLink(runtimeDirectory.toPath()) ||
            (!runtimeDirectory.isDirectory && !runtimeDirectory.mkdir())
        ) {
            Log.w(TAG, "GPU runtime directory is unavailable; session=$sessionId uses llvmpipe")
            return null
        }
        val candidate = File(runtimeDirectory, SOCKET_NAME)
        if (
            candidate.absolutePath.toByteArray(StandardCharsets.UTF_8).size >=
            UNIX_SOCKET_PATH_LIMIT
        ) {
            Log.w(TAG, "GPU socket path is too long; session=$sessionId uses llvmpipe")
            cleanupFiles(candidate)
            return null
        }
        if (candidate.exists() && !candidate.delete()) {
            Log.w(TAG, "Stale GPU socket could not be removed; session=$sessionId uses llvmpipe")
            cleanupFiles(candidate)
            return null
        }
        return runCatching {
            val child =
                ProcessBuilder(
                    helper.absolutePath,
                    "--no-fork",
                    "--use-egl-surfaceless",
                    "--use-gles",
                    "--socket-path",
                    candidate.absolutePath,
                ).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
            process = child
            socket = candidate
            val deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
            while (
                !candidate.exists() &&
                child.isAlive &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(25)
            }
            check(candidate.exists() && child.isAlive) {
                "GPU helper did not publish its socket"
            }
            Log.i(TAG, "GPU bridge ready session=$sessionId")
            candidate
        }.getOrElse { error ->
            Log.w(TAG, "GPU helper startup failed; session=$sessionId uses llvmpipe", error)
            close()
            null
        }
    }

    @Synchronized
    fun failedUnexpectedly(): Boolean = process?.let { !it.isAlive } == true

    @Synchronized
    override fun close() {
        val child = process
        if (child != null && !stopProcess(child, STOP_TIMEOUT_MILLIS)) {
            Log.w(TAG, "GPU helper did not stop; retaining cleanup session=$sessionId")
            scheduleReap(child, socket)
            return
        }
        process = null
        cleanupFiles(socket)
        socket = null
    }

    private fun scheduleReap(child: Process, candidate: File?) {
        if (reaper != null) return
        reaper =
            Thread({
                var interrupted = false
                while (child.isAlive) {
                    try {
                        child.waitFor()
                    } catch (_: InterruptedException) {
                        interrupted = true
                        child.destroyForcibly()
                    }
                }
                synchronized(this@AndroidGpuBridge) {
                    if (process === child) process = null
                    if (socket === candidate) {
                        cleanupFiles(candidate)
                        socket = null
                    }
                    reaper = null
                }
                if (interrupted) Thread.currentThread().interrupt()
            }, "archphene-gpu-reaper-$sessionId").apply {
                isDaemon = true
                start()
            }
    }

    private fun cleanupFiles(candidate: File?) {
        if (candidate != null && candidate.exists() && !candidate.delete()) {
            Log.w(TAG, "Could not remove GPU socket session=$sessionId")
        }
        if (runtimeDirectory.exists() && !runtimeDirectory.delete()) {
            Log.w(TAG, "Could not remove GPU runtime directory session=$sessionId")
        }
    }

    companion object {
        private const val TAG = "ArchpheneGpu"
        private const val HELPER = "libarchphene_virgl_server.so"
        private const val SOCKET_NAME = ".vg"
        private const val RUNTIME_PREFIX = "gpu-"
        private const val TOKEN_LENGTH = 16
        private const val MAX_STALE_DIRECTORIES = 64
        private const val UNIX_SOCKET_PATH_LIMIT = 104
        private const val START_TIMEOUT_MILLIS = 3_000L
        private const val STOP_TIMEOUT_MILLIS = 1_000L
        private const val HEX = "0123456789abcdef"
        private val random = SecureRandom()

        internal fun stopProcess(child: Process, timeoutMillis: Long): Boolean {
            var interrupted = false
            child.destroy()
            val stoppedGracefully =
                try {
                    child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
            if (!stoppedGracefully && child.isAlive) {
                child.destroyForcibly()
                try {
                    child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return !child.isAlive
        }

        fun cleanupStaleRuntimeDirectories(context: Context) {
            val cachePath = context.cacheDir.toPath()
            if (!Files.isDirectory(cachePath) || Files.isSymbolicLink(cachePath)) return
            runCatching {
                var visited = 0
                Files.newDirectoryStream(cachePath, "$RUNTIME_PREFIX*").use { entries ->
                    for (entry in entries) {
                        if (visited >= MAX_STALE_DIRECTORIES) {
                            Log.w(TAG, "GPU stale-runtime cleanup reached its bounded limit")
                            break
                        }
                        visited += 1
                        cleanupStaleRuntimeDirectory(entry)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not inspect stale GPU runtime directories", error)
            }
        }

        internal fun isRuntimeDirectoryName(name: String): Boolean {
            if (!name.startsWith(RUNTIME_PREFIX)) return false
            val separator = name.length - TOKEN_LENGTH - 1
            if (separator <= RUNTIME_PREFIX.length || name[separator] != '-') return false
            for (index in RUNTIME_PREFIX.length until separator) {
                if (name[index] !in '0'..'9') return false
            }
            for (index in separator + 1 until name.length) {
                if (name[index] !in '0'..'9' && name[index] !in 'a'..'f') return false
            }
            return true
        }

        private fun cleanupStaleRuntimeDirectory(path: Path) {
            if (
                !isRuntimeDirectoryName(path.fileName.toString()) ||
                Files.isSymbolicLink(path) ||
                !Files.isDirectory(path)
            ) {
                return
            }
            var socket: Path? = null
            Files.newDirectoryStream(path).use { entries ->
                for (entry in entries) {
                    if (socket != null || entry.fileName.toString() != SOCKET_NAME) {
                        Log.w(TAG, "Leaving GPU runtime directory with unexpected contents")
                        return
                    }
                    socket = entry
                }
            }
            socket?.let(Files::deleteIfExists)
            if (Files.deleteIfExists(path)) {
                Log.i(TAG, "Removed stale GPU runtime directory")
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
