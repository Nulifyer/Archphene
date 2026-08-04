package org.archphene.app.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class DebugMicrophoneCapture(
    val bytes: Int,
    val nonzeroBytes: Int,
)

internal class BoundedProcessDiagnostic(
    input: InputStream,
    maximumBytes: Int,
) {
    @Volatile private var text = ""
    private val thread =
        Thread(
            {
                text =
                    runCatching {
                        LauncherAudioBridge.readBoundedUtf8Diagnostic(input, maximumBytes)
                    }.getOrDefault("")
            },
            "ArchpheneAudioControlLog",
        ).apply {
            isDaemon = true
            start()
        }

    fun awaitText(timeoutMillis: Long): String {
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return text
    }
}

/**
 * Session-scoped PulseAudio server backed by Android AAudio, with an OpenSL ES fallback.
 *
 * Linux clients use the ordinary Pulse native protocol. The socket remains in the manager's
 * private cache and is never exposed through Binder or shared storage.
 */
internal class LauncherAudioBridge(
    context: Context,
    private val sessionId: Int,
    runtimeIdentity: String,
    private val inputEnabled: Boolean,
    private val brokerAddress: String,
) : Closeable {
    private val audioManager =
        checkNotNull(context.getSystemService(AudioManager::class.java)) {
            "Android audio service is unavailable"
        }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playbackControlExecutor = newPlaybackControlExecutor()
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            synchronized(this) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusRequested = true
                        focusInterrupted = false
                        focusRetryCount = 0
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusRequested = false
                        focusInterrupted = true
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> focusInterrupted = true
                }
                reconcilePlaybackSuspension()
            }
            Log.i(
                TAG,
                "Android audio focus session=$sessionId change=${audioFocusChangeName(change)}",
            )
        }
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(focusListener)
            .build()
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    private val runtimeDirectory =
        File(context.cacheDir, runtimeDirectoryName(sessionId, runtimeIdentity))
    private val moduleDirectory = File(runtimeDirectory, "modules")
    private val stateDirectory = File(runtimeDirectory, "state")
    private val socket = File(runtimeDirectory, "pulse")
    private val inputFifo = File(runtimeDirectory, "input")
    private val serverStateLock = Any()
    @Volatile private var server: Process? = null
    @Volatile private var readyServer: Process? = null
    @Volatile private var unreapedServer: Process? = null
    private var input: Process? = null
    @Volatile private var closing = false
    private val debugToneLock = Any()
    private var debugToneStarting = false
    @Volatile private var debugToneProcess: Process? = null
    private var hostActive = false
    private var runtimeForeground = false
    private var focusRequested = false
    private var focusInterrupted = false
    @Volatile private var playbackSuspended: Boolean? = null
    @Volatile private var playbackSuspensionRequested: Boolean? = null
    @Volatile private var playbackControlFuture: Future<*>? = null
    @Volatile private var playbackControlGeneration = 0L
    private val playbackControlLock = Any()
    private var playbackControlStarting = false
    @Volatile private var playbackControlProcess: Process? = null
    private var focusRetryCount = 0
    @Volatile private var playbackControlRetryCount = 0
    private val retryAudioFocus = Runnable { retryAudioFocus() }
    private val retryPlaybackSuspension = Runnable { retryPlaybackSuspension() }
    private val reconcileAudioFocusOnMain =
        Runnable {
            synchronized(this) {
                reconcileAudioFocus()
            }
        }
    private val playbackInputIds = LongArray(MAX_CONCURRENT_PLAYBACK_INPUTS) { UNUSED_INPUT_ID }
    private var activePlaybackInputCount = 0
    private var untrackedPlaybackInputCount = 0

    val serverAddress: String
        get() = "unix:${socket.absolutePath}"

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        close()
        val debugToneBusy =
            synchronized(debugToneLock) {
                debugToneStarting || debugToneProcess?.isAlive == true
            }
        val playbackControlBusy =
            synchronized(playbackControlLock) {
                playbackControlStarting || playbackControlProcess?.isAlive == true
            }
        if (
            debugToneBusy ||
            playbackControlBusy ||
            input?.isAlive == true ||
            unreapedServer?.isAlive == true
        ) {
            throw IOException("Could not reap prior audio helper")
        }
        closing = false
        playbackControlExecutor = newPlaybackControlExecutor()
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
        if (!fitsLauncherUnixSocketPath(socketPath, UNIX_SOCKET_PATH_LIMIT)) {
            throw IOException("PulseAudio socket path is too long")
        }

        val firstFailure =
            runCatching {
                launch(socketPath, "module-aaudio-sink")
                Log.i(TAG, "Private AAudio server ready session=$sessionId")
            }.exceptionOrNull()
        if (firstFailure == null) {
            return
        }
        Log.w(TAG, "AAudio startup failed; trying OpenSL ES session=$sessionId", firstFailure)
        stopServer()
        val controlBusy =
            synchronized(playbackControlLock) {
                playbackControlStarting || playbackControlProcess?.isAlive == true
            }
        if (unreapedServer?.isAlive == true || input?.isAlive == true || controlBusy) {
            close()
            throw IOException("Could not reap failed AAudio server", firstFailure)
        }
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
            readyServer === server &&
            socket.exists() &&
            (!inputEnabled || input?.isAlive == true)

    @Synchronized
    fun setHostActive(
        active: Boolean,
        closing: Boolean = false,
    ) {
        if (closing) this.closing = true
        if (active && !hostActive) focusInterrupted = false
        hostActive = active
        Log.i(TAG, "Android audio host active session=$sessionId active=$active")
        if (closing) {
            mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
            mainHandler.removeCallbacks(retryPlaybackSuspension)
            synchronized(playbackControlLock) {
                playbackControlGeneration++
                playbackSuspensionRequested = null
                playbackControlFuture = null
            }
            abandonAudioFocus()
            return
        }
        if (active) {
            reconcileAudioFocus()
        } else {
            mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
            mainHandler.postDelayed(reconcileAudioFocusOnMain, INACTIVE_RECONCILE_DELAY_MILLIS)
        }
    }

    /**
     * Audio focus is restricted on Android 15+ until the owning process is a
     * foreground service. The runtime calls this only after the Linux process
     * has been tracked and its foreground-service type includes media playback.
     */
    @Synchronized
    fun setRuntimeForeground(active: Boolean) {
        runtimeForeground = active
        if (active) {
            reconcileAudioFocus()
        } else {
            mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
            mainHandler.postDelayed(reconcileAudioFocusOnMain, INACTIVE_RECONCILE_DELAY_MILLIS)
        }
    }

    fun playDebugTone(): Boolean {
        val (audioServer, probe) =
            synchronized(this) {
                if (!isReady()) return false
                if (closing) return false
                val requiredProbe = requireHelper(PROBE)
                val reserved =
                    synchronized(debugToneLock) {
                        if (debugToneStarting || debugToneProcess?.isAlive == true) {
                            false
                        } else {
                            debugToneStarting = true
                            true
                        }
                    }
                if (!reserved) return false
                Pair(server, requiredProbe)
            }
        val process =
            try {
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
            } catch (error: IOException) {
                synchronized(debugToneLock) { debugToneStarting = false }
                throw error
            }
        val accepted =
            synchronized(debugToneLock) {
                if (
                    server !== audioServer ||
                    closing ||
                    readyServer !== audioServer ||
                    audioServer?.isAlive != true ||
                    !socket.exists() ||
                    debugToneProcess?.isAlive == true
                ) {
                    false
                } else {
                    debugToneProcess = process
                    debugToneStarting = false
                    true
                }
            }
        if (!accepted) {
            val terminated = stopProcess(process, "output probe")
            synchronized(debugToneLock) {
                if (!terminated && debugToneProcess?.isAlive != true) {
                    debugToneProcess = process
                }
                debugToneStarting = false
            }
            return false
        }
        drain(process, "output-probe")
        return try {
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
            process.waitFor(DEBUG_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                process.exitValue() == 0
        } finally {
            val terminated = stopProcess(process, "output probe")
            synchronized(debugToneLock) {
                if (debugToneProcess === process && terminated) debugToneProcess = null
            }
        }
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
        closing = true
        hostActive = false
        runtimeForeground = false
        focusInterrupted = false
        focusRetryCount = 0
        playbackControlRetryCount = 0
        mainHandler.removeCallbacks(retryAudioFocus)
        mainHandler.removeCallbacks(retryPlaybackSuspension)
        mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
        abandonAudioFocus()
        awaitDebugToneStart()
        val toneProcess = synchronized(debugToneLock) { debugToneProcess }
        if (stopProcess(toneProcess, "output probe")) {
            synchronized(debugToneLock) {
                if (debugToneProcess === toneProcess) debugToneProcess = null
            }
        }
        stopServer()
        playbackControlExecutor.shutdownNow()
        mainHandler.removeCallbacks(retryAudioFocus)
        mainHandler.removeCallbacks(retryPlaybackSuspension)
        mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
        val helperAlive =
            synchronized(debugToneLock) {
                debugToneStarting || debugToneProcess?.isAlive == true
            } ||
                synchronized(playbackControlLock) {
                    playbackControlStarting || playbackControlProcess?.isAlive == true
                } ||
                input?.isAlive == true ||
                unreapedServer?.isAlive == true
        if (helperAlive) {
            Log.w(TAG, "Could not remove audio runtime while a helper remains alive")
        } else {
            unlinkIfPresentQuietly(socket)
            deleteTree(runtimeDirectory.toPath())
        }
    }

    private fun requestAudioFocus() {
        if (focusRequested) return
        val result = audioManager.requestAudioFocus(focusRequest)
        focusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusRequested) {
            focusRetryCount = 0
            mainHandler.removeCallbacks(retryAudioFocus)
        } else {
            focusRetryCount = (focusRetryCount + 1).coerceAtMost(MAX_FOCUS_RETRY_EXPONENT)
            mainHandler.removeCallbacks(retryAudioFocus)
            mainHandler.postDelayed(
                retryAudioFocus,
                focusRetryDelayMillis(focusRetryCount),
            )
        }
        Log.i(
            TAG,
            "Android audio focus request session=$sessionId result=" +
                if (focusRequested) "granted" else "failed",
        )
    }

    private fun reconcileAudioFocus() {
        val audioServer = server
        val serverAvailable =
            isAudioServerAvailable(
                processAlive = audioServer?.isAlive == true,
                readinessMatches = readyServer === audioServer,
                socketExists = socket.exists(),
            )
        if (
            shouldRequestAudioFocus(
                hostActive,
                runtimeForeground,
                activePlaybackInputCount,
                focusInterrupted,
            ) && serverAvailable
        ) {
            requestAudioFocus()
        } else if (
            shouldAbandonAudioFocus(
                hostActive,
                runtimeForeground,
                activePlaybackInputCount,
                serverAvailable,
            )
        ) {
            focusRetryCount = 0
            mainHandler.removeCallbacks(retryAudioFocus)
            if (serverAvailable) {
                setPlaybackSuspended(true)
                if (playbackSuspended != true) return
            }
            abandonAudioFocus()
            return
        }
        reconcilePlaybackSuspension()
    }

    @Synchronized
    private fun retryAudioFocus() {
        if (!closing) reconcileAudioFocus()
    }

    @Synchronized
    private fun retryPlaybackSuspension() {
        reconcilePlaybackSuspension()
    }

    private fun schedulePlaybackControlRetry(): Boolean {
        if (playbackControlRetryCount >= MAX_CONTROL_RETRY_ATTEMPTS) return false
        playbackControlRetryCount =
            (playbackControlRetryCount + 1).coerceAtMost(MAX_CONTROL_RETRY_EXPONENT)
        mainHandler.removeCallbacks(retryPlaybackSuspension)
        mainHandler.postDelayed(
            retryPlaybackSuspension,
            controlRetryDelayMillis(playbackControlRetryCount),
        )
        return true
    }

    private fun retryPlaybackControlOrStopServer(audioServer: Process) {
        if (schedulePlaybackControlRetry()) return
        Log.w(TAG, "Pulse playback control retries exhausted session=$sessionId")
        synchronized(serverStateLock) {
            if (server === audioServer) readyServer = null
        }
        val terminated = stopProcessForcibly(audioServer, "audio server")
        synchronized(serverStateLock) {
            if (server === audioServer) {
                server = null
                readyServer = null
            }
            if (terminated) {
                if (unreapedServer === audioServer) unreapedServer = null
            } else {
                unreapedServer = audioServer
            }
        }
        synchronized(playbackControlLock) {
            playbackControlGeneration++
            playbackSuspensionRequested = null
            playbackControlFuture = null
            playbackSuspended = null
        }
        mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
        mainHandler.post(reconcileAudioFocusOnMain)
    }

    private fun reconcilePlaybackSuspension() {
        if (closing) return
        val audioServer = server
        if (
            !isAudioServerAvailable(
                processAlive = audioServer?.isAlive == true,
                readinessMatches = readyServer === audioServer,
                socketExists = socket.exists(),
            )
        ) {
            abandonAudioFocus()
            return
        }
        setPlaybackSuspended(
            shouldSuspendPlayback(
                hostActive,
                runtimeForeground,
                activePlaybackInputCount,
                focusRequested,
                focusInterrupted,
            ),
        )
    }

    private fun setPlaybackSuspended(
        suspended: Boolean,
        wait: Boolean = false,
    ) {
        if (closing) return
        val audioServer = server
        if (
            !isAudioServerAvailable(
                processAlive = audioServer?.isAlive == true,
                readinessMatches = readyServer === audioServer,
                socketExists = socket.exists(),
            )
        ) {
            synchronized(playbackControlLock) {
                playbackControlGeneration++
                playbackSuspended = null
                playbackSuspensionRequested = null
                playbackControlFuture = null
            }
            return
        }
        val readyAudioServer = audioServer ?: return
        var existingFuture: Future<*>? = null
        var generation = 0L
        var reused = false
        synchronized(playbackControlLock) {
            if (playbackSuspensionRequested == suspended) {
                reused = true
                existingFuture = playbackControlFuture
            } else {
                playbackSuspensionRequested = suspended
                playbackControlGeneration++
                generation = playbackControlGeneration
            }
        }
        if (reused) {
            if (wait) awaitPlaybackControl(existingFuture)
            return
        }
        val command = {
            applyPlaybackSuspension(readyAudioServer, suspended, generation)
        }
        val future =
            runCatching { playbackControlExecutor.submit(command) }.getOrElse { error ->
                Log.w(TAG, "Could not schedule Pulse playback control session=$sessionId", error)
                synchronized(playbackControlLock) {
                    if (
                        playbackControlGeneration == generation &&
                        playbackSuspensionRequested == suspended
                    ) {
                        playbackSuspensionRequested = null
                        playbackControlFuture = null
                    }
                }
                return
            }
        val retained =
            synchronized(playbackControlLock) {
                if (
                    playbackControlGeneration == generation &&
                    playbackSuspensionRequested == suspended
                ) {
                    playbackControlFuture = future
                    true
                } else {
                    false
                }
            }
        if (!retained) {
            future.cancel(false)
            return
        }
        if (wait) {
            awaitPlaybackControl(future)
        }
    }

    private fun awaitPlaybackControl(future: Future<*>?) {
        if (future == null) return
        try {
            future.get(CONTROL_TASK_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: TimeoutException) {
            Log.w(TAG, "Timed out waiting for Pulse playback control session=$sessionId")
        } catch (error: java.util.concurrent.ExecutionException) {
            Log.w(TAG, "Pulse playback control failed session=$sessionId", error.cause)
        }
    }

    private fun applyPlaybackSuspension(
        audioServer: Process,
        suspended: Boolean,
        generation: Long,
    ) {
        val reserved =
            synchronized(playbackControlLock) {
                if (
                    server !== audioServer ||
                    playbackControlGeneration != generation ||
                    playbackSuspensionRequested != suspended
                ) {
                    false
                } else if (
                    playbackControlStarting ||
                    playbackControlProcess?.isAlive == true
                ) {
                    Log.w(TAG, "Previous Pulse playback control is still alive session=$sessionId")
                    playbackSuspensionRequested = null
                    playbackSuspended = null
                    false
                } else {
                    playbackControlStarting = true
                    true
                }
            }
        if (!reserved) return
        val process =
            runCatching {
                val control = requireHelper(CONTROL)
                ProcessBuilder(
                        control.absolutePath,
                        "suspend-sink",
                        "archphene_output",
                        if (suspended) "1" else "0",
                    )
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
                        environment()["PULSE_SERVER"] = serverAddress
                        environment()["PULSE_RUNTIME_PATH"] = runtimeDirectory.absolutePath
                    }.start()
            }.getOrElse { error ->
                Log.w(TAG, "Could not start Pulse playback control session=$sessionId", error)
                var retry = false
                synchronized(playbackControlLock) {
                    playbackControlStarting = false
                    if (
                        server === audioServer &&
                        playbackControlGeneration == generation &&
                        playbackSuspensionRequested == suspended
                    ) {
                        playbackSuspensionRequested = null
                        playbackSuspended = null
                        retry = true
                    }
                }
                if (retry) retryPlaybackControlOrStopServer(audioServer)
                return
            }
        val published =
            synchronized(playbackControlLock) {
                if (
                    server !== audioServer ||
                    playbackControlGeneration != generation ||
                    playbackSuspensionRequested != suspended ||
                    playbackControlProcess?.isAlive == true
                ) {
                    false
                } else {
                    playbackControlProcess = process
                    playbackControlStarting = false
                    true
                }
            }
        if (!published) {
            val terminated = stopProcess(process, "Pulse playback control")
            synchronized(playbackControlLock) {
                if (!terminated && playbackControlProcess?.isAlive != true) {
                    playbackControlProcess = process
                }
                playbackControlStarting = false
            }
            return
        }
        val diagnostic =
            BoundedProcessDiagnostic(
                process.inputStream,
                MAX_CONTROL_DIAGNOSTIC_BYTES,
            )
        val completed =
            try {
                process.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        val terminated =
            if (completed) {
                true
            } else {
                process.destroyForcibly()
                try {
                    process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
        if (!completed) {
            runCatching { process.inputStream.close() }
        }
        if (terminated) {
            synchronized(playbackControlLock) {
                if (playbackControlProcess === process) playbackControlProcess = null
            }
        }
        val result = if (completed && terminated) process.exitValue() else -1
        val diagnosticText = diagnostic.awaitText(CONTROL_DIAGNOSTIC_JOIN_MILLIS)
        if (result == 0) {
            val applied =
                synchronized(playbackControlLock) {
                    if (
                        server === audioServer &&
                        playbackControlGeneration == generation &&
                        playbackSuspensionRequested == suspended
                    ) {
                        playbackSuspended = suspended
                        playbackControlRetryCount = 0
                        true
                    } else {
                        false
                    }
                }
            if (applied) mainHandler.removeCallbacks(retryPlaybackSuspension)
            if (suspended && applied) {
                mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
                mainHandler.post(reconcileAudioFocusOnMain)
            }
            Log.i(TAG, "Pulse playback suspended session=$sessionId suspended=$suspended")
        } else {
            val renderedDiagnostic =
                if (completed) {
                    diagnosticText
                } else {
                    "timeout"
                }
            Log.w(
                TAG,
                "Could not change Pulse playback suspension session=$sessionId " +
                    "suspended=$suspended result=$result diagnostic=$renderedDiagnostic",
            )
            var retry = false
            val unreaped = shouldStopServerForControlFailure(terminated)
            synchronized(playbackControlLock) {
                if (
                    server === audioServer &&
                    playbackControlGeneration == generation &&
                    playbackSuspensionRequested == suspended
                ) {
                    playbackSuspensionRequested = null
                    playbackSuspended = null
                    retry = terminated
                }
            }
            if (retry) retryPlaybackControlOrStopServer(audioServer)
            if (unreaped) {
                Log.w(TAG, "Pulse playback control did not terminate session=$sessionId")
                playbackControlRetryCount = MAX_CONTROL_RETRY_ATTEMPTS
                retryPlaybackControlOrStopServer(audioServer)
            }
        }
    }

    private fun abandonAudioFocus() {
        if (!focusRequested) return
        val result = audioManager.abandonAudioFocusRequest(focusRequest)
        focusRequested = false
        Log.i(
            TAG,
            "Android audio focus abandon session=$sessionId result=$result",
        )
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
        synchronized(serverStateLock) {
            server = process
            readyServer = null
        }
        synchronized(playbackControlLock) {
            playbackSuspended = null
            playbackSuspensionRequested = null
            playbackControlFuture = null
        }
        drain(process, sinkModule, observePlayback = true)
        val deadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
        while (
            (!socket.exists() || readyServer !== process) &&
                process.isAlive &&
                android.os.SystemClock.uptimeMillis() < deadline
        ) {
            android.os.SystemClock.sleep(START_POLL_MILLIS)
        }
        if (!socket.exists() || readyServer !== process || !process.isAlive) {
            throw IOException("Private PulseAudio server did not become ready")
        }
        android.os.SystemClock.sleep(CONTROL_START_DELAY_MILLIS)
        val controlDeadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS
        while (
            playbackSuspended != true &&
                process.isAlive &&
                android.os.SystemClock.uptimeMillis() < controlDeadline
        ) {
            setPlaybackSuspended(true, wait = true)
            if (playbackSuspended != true) {
                android.os.SystemClock.sleep(CONTROL_START_RETRY_MILLIS)
            }
        }
        if (playbackSuspended != true) {
            throw IOException("Could not suspend private PulseAudio output before launch")
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
        val inputProcess = input
        if (stopProcess(inputProcess, "microphone bridge")) {
            if (input === inputProcess) input = null
        }
        val process = server ?: unreapedServer
        synchronized(serverStateLock) {
            if (server === process) {
                readyServer = null
            }
        }
        synchronized(playbackControlLock) {
            playbackControlGeneration++
            playbackSuspended = null
            playbackSuspensionRequested = null
            playbackControlFuture = null
        }
        awaitPlaybackControlStart()
        val controlProcess = synchronized(playbackControlLock) { playbackControlProcess }
        if (stopProcess(controlProcess, "Pulse playback control")) {
            synchronized(playbackControlLock) {
                if (playbackControlProcess === controlProcess) playbackControlProcess = null
            }
        }
        playbackInputIds.fill(UNUSED_INPUT_ID)
        activePlaybackInputCount = 0
        untrackedPlaybackInputCount = 0
        abandonAudioFocus()
        val serverTerminated = stopProcessForcibly(process, "audio server")
        synchronized(serverStateLock) {
            if (server === process) server = null
            if (serverTerminated) {
                if (unreapedServer === process) unreapedServer = null
            } else {
                unreapedServer = process
            }
        }
    }

    private fun awaitDebugToneStart() {
        val deadline = android.os.SystemClock.uptimeMillis() + STOP_TIMEOUT_SECONDS * 1_000L
        while (
            synchronized(debugToneLock) { debugToneStarting } &&
                android.os.SystemClock.uptimeMillis() < deadline
        ) {
            android.os.SystemClock.sleep(START_POLL_MILLIS)
        }
        if (synchronized(debugToneLock) { debugToneStarting }) {
            Log.w(TAG, "Audio helper start did not finish label=output-probe")
        }
    }

    private fun awaitPlaybackControlStart() {
        val deadline = android.os.SystemClock.uptimeMillis() + STOP_TIMEOUT_SECONDS * 1_000L
        while (
            synchronized(playbackControlLock) { playbackControlStarting } &&
                android.os.SystemClock.uptimeMillis() < deadline
        ) {
            android.os.SystemClock.sleep(START_POLL_MILLIS)
        }
        if (synchronized(playbackControlLock) { playbackControlStarting }) {
            Log.w(TAG, "Audio helper start did not finish label=Pulse-playback-control")
        }
    }

    private fun stopProcess(
        process: Process?,
        label: String,
    ): Boolean {
        if (process == null) return true
        process.destroy()
        return try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "$label did not report exit after forced termination")
                    false
                } else {
                    true
                }
            } else {
                true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            !process.isAlive
        }
    }

    private fun stopProcessForcibly(
        process: Process?,
        label: String,
    ): Boolean {
        if (process == null) return true
        process.destroyForcibly()
        return try {
            if (process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                true
            } else {
                Log.w(TAG, "$label did not report exit after forced termination")
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            !process.isAlive
        }
    }

    private fun drain(
        process: Process,
        label: String,
        observePlayback: Boolean = false,
    ) {
        Thread(
                {
                    try {
                        drainBoundedUtf8Lines(
                            process.inputStream,
                            MAX_SERVER_LOG_LINE_BYTES,
                        ) { line ->
                            if (observePlayback && !line.truncated) {
                                if (isPulseServerReadyLine(line.text)) {
                                    synchronized(serverStateLock) {
                                        if (server === process) readyServer = process
                                    }
                                }
                                recordPlaybackInputEvent(process, line.text)
                            }
                            Log.i(TAG, "$label: ${line.text}")
                        }
                    } catch (error: IOException) {
                        Log.d(TAG, "$label log stream closed: ${error.message}")
                    } finally {
                        if (observePlayback) {
                            val currentServer =
                                synchronized(serverStateLock) {
                                    if (server === process) {
                                        readyServer = null
                                        true
                                    } else {
                                        false
                                    }
                                }
                            if (currentServer) {
                                mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
                                mainHandler.post(reconcileAudioFocusOnMain)
                            }
                        }
                    }
                },
                "ArchpheneAudioLog",
            )
            .apply { isDaemon = true }
            .start()
    }

    private fun recordPlaybackInputEvent(
        process: Process,
        line: String,
    ) {
        val event = pulsePlaybackInputEvent(line)
        if (event == 0L) return
        synchronized(this) {
            if (server !== process) return
            val input = kotlin.math.abs(event) - 1L
            val existing = playbackInputIds.indexOf(input)
            if (event > 0L && existing < 0) {
                val slot = playbackInputIds.indexOf(UNUSED_INPUT_ID)
                if (slot < 0) {
                    untrackedPlaybackInputCount++
                    activePlaybackInputCount++
                    Log.w(TAG, "Playback input registry full session=$sessionId")
                } else {
                    val wasIdle = activePlaybackInputCount == 0
                    playbackInputIds[slot] = input
                    activePlaybackInputCount++
                    if (wasIdle) focusInterrupted = false
                }
            } else if (event < 0L && existing >= 0) {
                playbackInputIds[existing] = UNUSED_INPUT_ID
                activePlaybackInputCount = (activePlaybackInputCount - 1).coerceAtLeast(0)
            } else if (event < 0L && untrackedPlaybackInputCount > 0) {
                untrackedPlaybackInputCount--
                activePlaybackInputCount = (activePlaybackInputCount - 1).coerceAtLeast(0)
            }
            mainHandler.removeCallbacks(reconcileAudioFocusOnMain)
            mainHandler.post(reconcileAudioFocusOnMain)
        }
    }

    private fun drainError(
        process: Process,
        label: String,
    ) {
        Thread(
                {
                    try {
                        drainBoundedUtf8Lines(
                            process.errorStream,
                            MAX_SERVER_LOG_LINE_BYTES,
                        ) { line -> Log.i(TAG, "$label: ${line.text}") }
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
        private const val CONTROL = "libarchphene_pulse_control.so"
        private const val RUNTIME_PREFIX = "audio-"
        private const val MAX_RUNTIME_ENTRIES = 160
        private const val MAX_RUNTIME_DEPTH = 3
        private const val MAX_STALE_DIRECTORIES = 32
        private const val UNIX_SOCKET_PATH_LIMIT = 100
        private const val MAX_BROKER_BYTES = 128
        private const val START_TIMEOUT_MILLIS = 20_000L
        private const val START_POLL_MILLIS = 25L
        private const val CONTROL_START_DELAY_MILLIS = 500L
        private const val CONTROL_START_RETRY_MILLIS = 250L
        private const val INACTIVE_RECONCILE_DELAY_MILLIS = 50L
        private const val INPUT_START_DELAY_MILLIS = 100L
        private const val STOP_TIMEOUT_SECONDS = 2L
        private const val DEBUG_PROBE_TIMEOUT_SECONDS = 5L
        private const val CONTROL_TIMEOUT_SECONDS = 2L
        private const val CONTROL_TASK_WAIT_SECONDS = 7L
        private const val MAX_CONTROL_DIAGNOSTIC_BYTES = 512
        private const val MAX_SERVER_LOG_LINE_BYTES = 512
        private const val CONTROL_DIAGNOSTIC_JOIN_MILLIS = 2_000L
        private const val MAX_FOCUS_RETRY_EXPONENT = 5
        private const val MAX_CONTROL_RETRY_EXPONENT = 5
        private const val MAX_CONTROL_RETRY_ATTEMPTS = 5
        private const val RETRY_BASE_DELAY_MILLIS = 250L
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
        private const val MAX_CONCURRENT_PLAYBACK_INPUTS = 64
        private const val MAX_PULSE_INPUT_ID = 0xffff_ffffL
        private const val UNUSED_INPUT_ID = -1L
        private const val CREATED_INPUT_MARKER = "sink-input.c: Created input "
        private const val FREED_INPUT_MARKER = "sink-input.c: Freeing input "
        private const val SERVER_READY_MARKER = "main.c: Daemon startup complete."

        internal fun newPlaybackControlExecutor(): ThreadPoolExecutor =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { command ->
                    Thread(command, "ArchpheneAudioControl").apply { isDaemon = true }
                },
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )

        internal fun readBoundedUtf8Diagnostic(
            input: InputStream,
            maximumBytes: Int,
        ): String {
            require(maximumBytes > 0)
            val retained = ByteArray(maximumBytes)
            val chunk = ByteArray(1024)
            var retainedBytes = 0
            input.use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    val copied = minOf(read, maximumBytes - retainedBytes)
                    if (copied > 0) {
                        System.arraycopy(chunk, 0, retained, retainedBytes, copied)
                        retainedBytes += copied
                    }
                }
            }
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            for (length in retainedBytes downTo 0) {
                val decoded =
                    runCatching {
                        decoder.reset().decode(ByteBuffer.wrap(retained, 0, length)).toString()
                    }.getOrNull()
                if (decoded != null) return decoded.trim()
            }
            return ""
        }

        internal fun shouldRequestAudioFocus(
            hostActive: Boolean,
            runtimeForeground: Boolean,
            activePlaybackInputCount: Int,
            focusInterrupted: Boolean,
        ): Boolean =
            hostActive &&
                runtimeForeground &&
                activePlaybackInputCount > 0 &&
                !focusInterrupted

        internal fun shouldAbandonAudioFocus(
            hostActive: Boolean,
            runtimeForeground: Boolean,
            activePlaybackInputCount: Int,
            serverAvailable: Boolean,
        ): Boolean =
            !hostActive ||
                !runtimeForeground ||
                activePlaybackInputCount <= 0 ||
                !serverAvailable

        internal fun isAudioServerAvailable(
            processAlive: Boolean,
            readinessMatches: Boolean,
            socketExists: Boolean,
        ): Boolean = processAlive && readinessMatches && socketExists

        internal fun shouldStopServerForControlFailure(terminated: Boolean): Boolean = !terminated

        internal fun focusRetryDelayMillis(attempt: Int): Long =
            retryDelayMillis(attempt, MAX_FOCUS_RETRY_EXPONENT)

        internal fun controlRetryDelayMillis(attempt: Int): Long =
            retryDelayMillis(attempt, MAX_CONTROL_RETRY_EXPONENT)

        private fun retryDelayMillis(
            attempt: Int,
            maximumExponent: Int,
        ): Long =
            RETRY_BASE_DELAY_MILLIS shl (attempt.coerceIn(1, maximumExponent) - 1)

        internal fun shouldSuspendPlayback(
            hostActive: Boolean,
            runtimeForeground: Boolean,
            activePlaybackInputCount: Int,
            focusRequested: Boolean,
            focusInterrupted: Boolean,
        ): Boolean =
            !hostActive ||
                !runtimeForeground ||
                activePlaybackInputCount <= 0 ||
                !focusRequested ||
                focusInterrupted

        internal fun pulsePlaybackInputEvent(line: String): Long {
            val created = line.indexOf(CREATED_INPUT_MARKER)
            val freed = line.indexOf(FREED_INPUT_MARKER)
            val marker: String
            val markerIndex: Int
            val direction: Int
            if (created >= 0 && (freed < 0 || created < freed)) {
                marker = CREATED_INPUT_MARKER
                markerIndex = created
                direction = 1
            } else if (freed >= 0) {
                marker = FREED_INPUT_MARKER
                markerIndex = freed
                direction = -1
            } else {
                return 0L
            }
            var index = markerIndex + marker.length
            var value = 0L
            val start = index
            while (index < line.length && line[index] in '0'..'9') {
                val digit = (line[index] - '0').toLong()
                if (value > (MAX_PULSE_INPUT_ID - digit) / 10L) return 0L
                value = value * 10L + digit
                index++
            }
            if (index == start) return 0L
            return direction.toLong() * (value + 1L)
        }

        internal fun isPulseServerReadyLine(line: String): Boolean =
            line.contains(SERVER_READY_MARKER)

        internal fun audioFocusChangeName(change: Int): String =
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> "gain"
                AudioManager.AUDIOFOCUS_LOSS -> "loss"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss-transient"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss-transient-can-duck"
                else -> "unknown-$change"
            }

        internal fun runtimeDirectoryName(
            sessionId: Int,
            runtimeIdentity: String,
        ): String {
            require(sessionId > 0)
            require(
                runtimeIdentity.length == 16 &&
                    runtimeIdentity.all { character ->
                        character in '0'..'9' || character in 'a'..'f'
                    },
            )
            return "audio-$sessionId-$runtimeIdentity"
        }

        fun cleanupStaleRuntimeDirectories(context: Context) {
            cleanupStaleRuntimeDirectories(context.cacheDir.toPath())
        }

        internal fun cleanupStaleRuntimeDirectories(
            cacheDirectory: Path,
            reportFailure: (String, Throwable) -> Unit = { message, error ->
                Log.w(TAG, message, error)
            },
        ) {
            if (!Files.isDirectory(cacheDirectory, LinkOption.NOFOLLOW_LINKS)) return
            runCatching {
                var visited = 0
                Files.newDirectoryStream(cacheDirectory, "$RUNTIME_PREFIX*").use { entries ->
                    for (entry in entries) {
                        if (visited++ >= MAX_STALE_DIRECTORIES) {
                            Log.w(TAG, "Audio stale-runtime cleanup reached its bounded limit")
                            break
                        }
                        deleteTree(entry, reportFailure)
                    }
                }
            }.onFailure { error ->
                reportFailure("Could not inspect stale audio runtime directories", error)
            }
        }

        private fun deleteTree(
            root: Path,
            reportFailure: (String, Throwable) -> Unit = { message, error ->
                Log.w(TAG, message, error)
            },
        ) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            runCatching {
                val postorder = ArrayList<Path>(MAX_RUNTIME_ENTRIES + 1)
                var visited = 0
                fun collect(
                    path: Path,
                    depth: Int,
                ) {
                    check(depth <= MAX_RUNTIME_DEPTH) {
                        "Audio runtime nesting exceeds its bound"
                    }
                    if (depth > 0) {
                        check(++visited <= MAX_RUNTIME_ENTRIES) {
                            "Audio runtime entry count exceeds its bound"
                        }
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.newDirectoryStream(path).use { entries ->
                            for (entry in entries) collect(entry, depth + 1)
                        }
                    }
                    postorder.add(path)
                }
                collect(root, 0)
                postorder.forEach(Files::deleteIfExists)
            }.onFailure { error ->
                reportFailure("Could not remove stale audio tree=${root.fileName}", error)
            }
        }
    }
}
