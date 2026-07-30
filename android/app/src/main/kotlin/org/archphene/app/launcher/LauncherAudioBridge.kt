package org.archphene.app.launcher

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal data class DebugMicrophoneCapture(
    val bytes: Int,
    val nonzeroBytes: Int,
)

/**
 * Session-scoped PulseAudio server backed by Android AAudio, with an OpenSL ES fallback.
 *
 * Linux clients use the ordinary Pulse native protocol. The socket remains in the manager's
 * private cache and is never exposed through Binder or shared storage.
 */
internal class LauncherAudioBridge(
    context: Context,
    private val sessionId: Int,
    private val inputEnabled: Boolean,
    private val brokerAddress: String,
) : Closeable {
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    private val runtimeDirectory = File(context.cacheDir, "audio-$sessionId")
    private val moduleDirectory = File(runtimeDirectory, "modules")
    private val stateDirectory = File(runtimeDirectory, "state")
    private val socket = File(runtimeDirectory, "pulse")
    private val inputFifo = File(runtimeDirectory, "input")
    private var server: Process? = null
    private var input: Process? = null

    val serverAddress: String
        get() = "unix:${socket.absolutePath}"

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        close()
        if (
            (!moduleDirectory.mkdirs() && !moduleDirectory.isDirectory) ||
                (!stateDirectory.mkdirs() && !stateDirectory.isDirectory)
        ) {
            throw IOException("Could not create private audio directories")
        }
        runCatching { Os.chmod(runtimeDirectory.absolutePath, 0b111_000_000) }
            .getOrElse { error ->
                throw IOException("Could not protect private audio directory", error)
            }
        linkModule(AAUDIO_MODULE, "module-aaudio-sink.so")
        linkModule(SLES_MODULE, "module-sles-sink.so")
        linkModule(NATIVE_PROTOCOL_MODULE, "module-native-protocol-unix.so")
        if (inputEnabled) {
            if (!brokerAddress.startsWith("@") || brokerAddress.length > MAX_BROKER_BYTES) {
                throw IOException("Android microphone broker is unavailable")
            }
            linkModule(PIPE_SOURCE_MODULE, "module-pipe-source.so")
            unlinkIfPresent(inputFifo, "microphone input FIFO")
        }
        val socketPath = socket.canonicalPath
        if (socketPath.toByteArray(StandardCharsets.UTF_8).size >= UNIX_SOCKET_PATH_LIMIT) {
            throw IOException("PulseAudio socket path is too long")
        }

        val firstFailure =
            runCatching {
                launch(socketPath, "module-aaudio-sink")
                Log.i(TAG, "Private AAudio server ready session=$sessionId")
            }.exceptionOrNull()
        if (firstFailure == null) return

        Log.w(TAG, "AAudio startup failed; trying OpenSL ES session=$sessionId", firstFailure)
        stopServer()
        unlinkIfPresent(socket, "PulseAudio socket")
        runCatching {
            launch(socketPath, "module-sles-sink")
            Log.i(TAG, "Private OpenSL ES server ready session=$sessionId")
        }.getOrElse { fallbackFailure ->
            fallbackFailure.addSuppressed(firstFailure)
            close()
            throw IOException("Android audio output is unavailable", fallbackFailure)
        }
    }

    @Synchronized
    fun isReady(): Boolean =
        server?.isAlive == true &&
            socket.exists() &&
            (!inputEnabled || input?.isAlive == true)

    @Synchronized
    fun playDebugTone(): Boolean {
        if (!isReady()) return false
        val probe = requireHelper(PROBE)
        val process =
            ProcessBuilder(
                    probe.absolutePath,
                    "--playback",
                    "--raw",
                    "--device=archphene_output",
                    "--rate=48000",
                    "--channels=2",
                    "--format=s16le",
                    "--client-name=Archphene output probe",
                )
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
                    environment()["PULSE_SERVER"] = serverAddress
                    environment()["PULSE_RUNTIME_PATH"] = runtimeDirectory.absolutePath
                }.start()
        drain(process, "output-probe")
        val samples = ByteArray(DEBUG_TONE_FRAMES_PER_CHUNK * DEBUG_TONE_FRAME_BYTES)
        var phase = 0
        process.outputStream.use { output ->
            repeat(DEBUG_TONE_CHUNKS) {
                var index = 0
                repeat(DEBUG_TONE_FRAMES_PER_CHUNK) {
                    phase += DEBUG_TONE_HZ
                    if (phase >= DEBUG_TONE_SAMPLE_RATE) phase -= DEBUG_TONE_SAMPLE_RATE
                    val sample =
                        if (phase < DEBUG_TONE_SAMPLE_RATE / 2) {
                            DEBUG_TONE_AMPLITUDE
                        } else {
                            -DEBUG_TONE_AMPLITUDE
                        }
                    repeat(2) {
                        samples[index++] = sample.toByte()
                        samples[index++] = (sample shr 8).toByte()
                    }
                }
                output.write(samples)
            }
        }
        return process.waitFor(DEBUG_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            process.exitValue() == 0
    }

    @Synchronized
    fun captureDebugMicrophone(): DebugMicrophoneCapture {
        if (!inputEnabled || !isReady()) return DebugMicrophoneCapture(0, 0)
        val probe = requireHelper(PROBE)
        val process =
            ProcessBuilder(
                    probe.absolutePath,
                    "--record",
                    "--raw",
                    "--device=archphene_input",
                    "--rate=48000",
                    "--channels=1",
                    "--format=s16le",
                    "--client-name=Archphene microphone probe",
                )
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
                    environment()["PULSE_SERVER"] = serverAddress
                    environment()["PULSE_RUNTIME_PATH"] = runtimeDirectory.absolutePath
                }.start()
        drainError(process, "microphone-probe")
        val buffer = ByteArray(DEBUG_CAPTURE_BUFFER_BYTES)
        var captured = 0
        var nonzero = 0
        val deadline =
            android.os.SystemClock.uptimeMillis() + DEBUG_CAPTURE_TIMEOUT_MILLIS
        try {
            while (
                captured < DEBUG_CAPTURE_TARGET_BYTES &&
                    process.isAlive &&
                    android.os.SystemClock.uptimeMillis() < deadline
            ) {
                val available = process.inputStream.available()
                if (available <= 0) {
                    android.os.SystemClock.sleep(DEBUG_CAPTURE_POLL_MILLIS)
                    continue
                }
                val count =
                    process.inputStream.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            available,
                            DEBUG_CAPTURE_TARGET_BYTES - captured,
                        ),
                    )
                if (count < 0) break
                for (index in 0 until count) {
                    if (buffer[index].toInt() != 0) nonzero++
                }
                captured += count
            }
        } finally {
            stopProcess(process, "microphone probe")
        }
        return DebugMicrophoneCapture(captured, nonzero)
    }

    @Synchronized
    override fun close() {
        stopServer()
        unlinkIfPresentQuietly(socket)
        deleteTree(runtimeDirectory)
    }

    private fun launch(
        socketPath: String,
        sinkModule: String,
    ) {
        val serverFile = requireHelper(SERVER)
        val config = File(runtimeDirectory, "default.pa")
        val configuration =
            "load-module $sinkModule sink_name=archphene_output\n" +
                "load-module module-native-protocol-unix socket=$socketPath auth-anonymous=1\n" +
                if (inputEnabled) {
                    "load-module module-pipe-source source_name=archphene_input " +
                        "file=${inputFifo.absolutePath} format=s16le rate=48000 channels=1\n"
                } else {
                    ""
                }
        FileOutputStream(config, false).use { output ->
            output.write(configuration.toByteArray(StandardCharsets.UTF_8))
        }
        unlinkIfPresent(socket, "PulseAudio socket")
        val process =
            ProcessBuilder(
                    serverFile.absolutePath,
                    "--daemonize=no",
                    "--fail=yes",
                    "--use-pid-file=no",
                    "--system=no",
                    "--exit-idle-time=-1",
                    "--disallow-exit=yes",
                    "--disable-shm=yes",
                    "--log-target=stderr",
                    "--log-level=info",
                    "--dl-search-path=${moduleDirectory.absolutePath}",
                    "--file=${config.absolutePath}",
                )
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
                    environment()["HOME"] = runtimeDirectory.absolutePath
                    environment()["XDG_RUNTIME_DIR"] = runtimeDirectory.absolutePath
                    environment()["PULSE_RUNTIME_PATH"] = runtimeDirectory.absolutePath
                    environment()["PULSE_STATE_PATH"] = stateDirectory.absolutePath
                    environment()["TMPDIR"] = runtimeDirectory.absolutePath
                }.start()
        server = process
        drain(process, sinkModule)
        val deadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
        while (
            !socket.exists() &&
                process.isAlive &&
                android.os.SystemClock.uptimeMillis() < deadline
        ) {
            android.os.SystemClock.sleep(START_POLL_MILLIS)
        }
        if (!socket.exists() || !process.isAlive) {
            throw IOException("Private PulseAudio server did not become ready")
        }
        if (inputEnabled) {
            launchInput()
        }
    }

    private fun launchInput() {
        val helper = requireHelper(INPUT_HELPER)
        val process =
            ProcessBuilder(helper.absolutePath, inputFifo.absolutePath)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
                    environment()["PULSE_SERVER"] = serverAddress
                    environment()["PULSE_RUNTIME_PATH"] = runtimeDirectory.absolutePath
                    environment()["ARCHPHENE_ANDROID_BROKER"] = brokerAddress
                    environment()["ARCHPHENE_ANDROID_PROTOCOL"] = "1"
                }.start()
        input = process
        drain(process, "microphone")
        android.os.SystemClock.sleep(INPUT_START_DELAY_MILLIS)
        if (!process.isAlive) {
            throw IOException("Android microphone bridge exited during startup")
        }
        Log.i(TAG, "Private PulseAudio microphone bridge ready session=$sessionId")
    }

    private fun linkModule(
        helperName: String,
        moduleName: String,
    ) {
        val helper = requireHelper(helperName)
        val module = File(moduleDirectory, moduleName)
        unlinkIfPresent(module, moduleName)
        runCatching { Os.symlink(helper.absolutePath, module.absolutePath) }
            .getOrElse { error ->
                throw IOException("Could not publish audio module: $moduleName", error)
            }
    }

    private fun requireHelper(name: String): File {
        val helper = File(nativeLibraryDir, name)
        if (!helper.isFile) throw IOException("Audio helper is missing: $name")
        return helper
    }

    @Synchronized
    private fun stopServer() {
        stopProcess(input, "microphone bridge")
        input = null
        val process = server
        server = null
        stopProcess(process, "audio server")
    }

    private fun stopProcess(
        process: Process?,
        label: String,
    ) {
        if (process == null) return
        process.destroy()
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "$label did not report exit after forced termination")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    private fun drain(
        process: Process,
        label: String,
    ) {
        Thread(
                {
                    try {
                        BufferedReader(
                                InputStreamReader(
                                    process.inputStream,
                                    StandardCharsets.UTF_8,
                                ),
                            )
                            .useLines { lines ->
                                lines.forEach { line -> Log.i(TAG, "$label: $line") }
                            }
                    } catch (error: IOException) {
                        Log.d(TAG, "$label log stream closed: ${error.message}")
                    }
                },
                "ArchpheneAudioLog",
            )
            .apply { isDaemon = true }
            .start()
    }

    private fun drainError(
        process: Process,
        label: String,
    ) {
        Thread(
                {
                    try {
                        BufferedReader(
                                InputStreamReader(
                                    process.errorStream,
                                    StandardCharsets.UTF_8,
                                ),
                            )
                            .useLines { lines ->
                                lines.forEach { line -> Log.i(TAG, "$label: $line") }
                            }
                    } catch (error: IOException) {
                        Log.d(TAG, "$label error stream closed: ${error.message}")
                    }
                },
                "ArchpheneAudioErrorLog",
            )
            .apply { isDaemon = true }
            .start()
    }

    private fun unlinkIfPresent(
        path: File,
        label: String,
    ) {
        if (path.delete()) return
        try {
            Os.lstat(path.absolutePath)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return
            throw IOException("Could not inspect stale $label", error)
        }
        throw IOException("Could not replace stale $label")
    }

    private fun unlinkIfPresentQuietly(path: File) {
        if (path.delete() || !path.exists()) return
        Log.w(TAG, "Could not remove stale audio path=${path.name}")
    }

    internal companion object {
        private const val TAG = "ArchpheneAudio"
        private const val SERVER = "libarchphene_pulseaudio.so"
        private const val AAUDIO_MODULE = "libarchphene_pulse_module_aaudio_sink.so"
        private const val SLES_MODULE = "libarchphene_pulse_module_sles_sink.so"
        private const val NATIVE_PROTOCOL_MODULE =
            "libarchphene_pulse_module_native_protocol_unix.so"
        private const val PIPE_SOURCE_MODULE =
            "libarchphene_pulse_module_pipe_source.so"
        private const val INPUT_HELPER = "libarchphene_audio_input.so"
        private const val PROBE = "libarchphene_pulse_probe.so"
        private const val UNIX_SOCKET_PATH_LIMIT = 100
        private const val MAX_BROKER_BYTES = 128
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val START_POLL_MILLIS = 25L
        private const val INPUT_START_DELAY_MILLIS = 100L
        private const val STOP_TIMEOUT_SECONDS = 2L
        private const val DEBUG_PROBE_TIMEOUT_SECONDS = 5L
        private const val DEBUG_TONE_SAMPLE_RATE = 48_000
        private const val DEBUG_TONE_HZ = 440
        private const val DEBUG_TONE_AMPLITUDE = 4_096
        private const val DEBUG_TONE_FRAMES_PER_CHUNK = 480
        private const val DEBUG_TONE_FRAME_BYTES = 4
        private const val DEBUG_TONE_CHUNKS = 25
        private const val DEBUG_CAPTURE_BUFFER_BYTES = 4_096
        private const val DEBUG_CAPTURE_TARGET_BYTES = 96_000
        private const val DEBUG_CAPTURE_TIMEOUT_MILLIS = 90_000L
        private const val DEBUG_CAPTURE_POLL_MILLIS = 10L

        fun cleanupStaleRuntimeDirectories(context: Context) {
            context.cacheDir
                .listFiles { file -> file.name.startsWith("audio-") }
                ?.forEach(::deleteTree)
        }

        private fun deleteTree(root: File) {
            val rootPath = root.toPath()
            if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return
            runCatching {
                Files.walkFileTree(
                    rootPath,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            directory: Path,
                            error: IOException?,
                        ): FileVisitResult {
                            if (error != null) throw error
                            Files.deleteIfExists(directory)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }.onFailure { error ->
                Log.w(TAG, "Could not remove stale audio tree=${root.name}", error)
            }
        }
    }
}
