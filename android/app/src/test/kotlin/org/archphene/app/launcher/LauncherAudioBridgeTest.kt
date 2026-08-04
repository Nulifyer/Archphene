package org.archphene.app.launcher

import android.media.AudioManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherAudioBridgeTest {
    @Test
    fun playbackControlExecutorKeepsOnlyLatestOfOneHundredPendingTasks() {
        val executor = LauncherAudioBridge.newPlaybackControlExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger(-1)
        try {
            executor.submit {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) { value -> executor.submit { completed.set(value) } }
            assertEquals(1, executor.queue.size)
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(99, completed.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun controlDiagnosticDrainsInputWhileRetainingOnlyBoundedBytes() {
        val consumed = AtomicInteger(0)
        val payload = "a".repeat(64 * 1024).toByteArray()
        val input =
            object : InputStream() {
                private val source = ByteArrayInputStream(payload)

                override fun read(): Int {
                    val value = source.read()
                    if (value >= 0) consumed.incrementAndGet()
                    return value
                }

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val read = source.read(buffer, offset, length)
                    if (read > 0) consumed.addAndGet(read)
                    return read
                }
            }
        assertEquals(
            "a".repeat(512),
            LauncherAudioBridge.readBoundedUtf8Diagnostic(input, 512),
        )
        assertEquals(payload.size, consumed.get())
    }

    @Test
    fun processDiagnosticDrainsConcurrentlyBeforeAwait() {
        val input = PipedInputStream(1024)
        val output = PipedOutputStream(input)
        val written = CountDownLatch(1)
        val diagnostic = BoundedProcessDiagnostic(input, 512)
        val writer =
            Thread {
                runCatching {
                    output.use { stream -> stream.write("b".repeat(64 * 1024).toByteArray()) }
                    written.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            assertTrue(written.await(2, TimeUnit.SECONDS))
            writer.join(2_000)
            assertEquals("b".repeat(512), diagnostic.awaitText(2_000))
        } finally {
            runCatching { output.close() }
            runCatching { input.close() }
            writer.join(2_000)
        }
        assertEquals(false, writer.isAlive)
    }

    @Test
    fun controlDiagnosticDropsAnIncompleteUtf8Tail() {
        val payload = ("a".repeat(511) + "é").toByteArray(Charsets.UTF_8)
        assertEquals(
            "a".repeat(511),
            LauncherAudioBridge.readBoundedUtf8Diagnostic(
                ByteArrayInputStream(payload),
                512,
            ),
        )
    }

    @Test
    fun namesEveryAndroidAudioFocusTransition() {
        assertEquals(
            "gain",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_GAIN),
        )
        assertEquals(
            "loss",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS),
        )
        assertEquals(
            "loss-transient",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            "loss-transient-can-duck",
            LauncherAudioBridge.audioFocusChangeName(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            ),
        )
        assertEquals("unknown-17", LauncherAudioBridge.audioFocusChangeName(17))
    }

    @Test
    fun audioRuntimeDirectoryIncludesServiceLifetimeIdentity() {
        assertEquals(
            "audio-7-0123456789abcdef",
            LauncherAudioBridge.runtimeDirectoryName(
                7,
                "0123456789abcdef",
            ),
        )
    }

    @Test
    fun audioRuntimeDirectoryRejectsUnsafeIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            LauncherAudioBridge.runtimeDirectoryName(7, "../shared")
        }
    }

    @Test
    fun staleAudioRuntimeOverEntryLimitIsRetainedExactly() {
        val cache = Files.createTempDirectory("audio-cleanup-entry-limit")
        val root = Files.createDirectory(cache.resolve("audio-stale"))
        val expected =
            (0 until 161).associate { index ->
                "entry-$index" to "payload-$index".toByteArray()
            }
        try {
            expected.forEach { (name, bytes) -> Files.write(root.resolve(name), bytes) }
            val failures = mutableListOf<Throwable>()

            LauncherAudioBridge.cleanupStaleRuntimeDirectories(cache) { _, error ->
                failures.add(error)
            }

            assertEquals(1, failures.size)
            assertTrue(Files.isDirectory(root))
            val retainedNames = mutableSetOf<String>()
            Files.newDirectoryStream(root).use { entries ->
                entries.forEach { retainedNames.add(it.fileName.toString()) }
            }
            assertEquals(expected.keys, retainedNames)
            expected.forEach { (name, bytes) ->
                assertArrayEquals(bytes, Files.readAllBytes(root.resolve(name)))
            }
        } finally {
            Files.walk(cache).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun staleAudioRuntimeOverDepthLimitIsRetainedExactly() {
        val cache = Files.createTempDirectory("audio-cleanup-depth-limit")
        val root = Files.createDirectory(cache.resolve("audio-stale"))
        val marker = "retained".toByteArray()
        try {
            Files.write(root.resolve("marker"), marker)
            Files.createDirectories(root.resolve("depth-1/depth-2/depth-3/depth-4"))
            val failures = mutableListOf<Throwable>()

            LauncherAudioBridge.cleanupStaleRuntimeDirectories(cache) { _, error ->
                failures.add(error)
            }

            assertEquals(1, failures.size)
            val retainedPaths = mutableSetOf<String>()
            Files.walk(root).use { paths ->
                paths.forEach { retainedPaths.add(root.relativize(it).toString()) }
            }
            assertEquals(
                setOf(
                    "",
                    "marker",
                    "depth-1",
                    "depth-1/depth-2",
                    "depth-1/depth-2/depth-3",
                    "depth-1/depth-2/depth-3/depth-4",
                ),
                retainedPaths,
            )
            assertArrayEquals(marker, Files.readAllBytes(root.resolve("marker")))
        } finally {
            Files.walk(cache).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun staleAudioRuntimeWithinLimitsIsRemoved() {
        val cache = Files.createTempDirectory("audio-cleanup-valid")
        val root = cache.resolve("audio-stale")
        try {
            Files.createDirectories(root.resolve("modules/nested"))
            Files.write(root.resolve("modules/nested/module.so"), byteArrayOf(1, 2, 3))
            val failures = mutableListOf<Throwable>()

            LauncherAudioBridge.cleanupStaleRuntimeDirectories(cache) { _, error ->
                failures.add(error)
            }

            assertTrue(failures.isEmpty())
            assertFalse(Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            if (Files.exists(cache)) {
                Files.walk(cache).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    @Test
    fun audioFocusRequiresRealForegroundPlayback() {
        assertEquals(
            false,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 0,
                focusInterrupted = false,
            ),
        )
        assertEquals(
            false,
            LauncherAudioBridge.shouldAbandonAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                serverAvailable = true,
            ),
        )
        assertEquals(
            true,
            LauncherAudioBridge.shouldAbandonAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                serverAvailable = false,
            ),
        )
        assertEquals(
            false,
            LauncherAudioBridge.isAudioServerAvailable(
                processAlive = true,
                readinessMatches = false,
                socketExists = true,
            ),
        )
        assertEquals(
            true,
            LauncherAudioBridge.isAudioServerAvailable(
                processAlive = true,
                readinessMatches = true,
                socketExists = true,
            ),
        )
        assertEquals(true, LauncherAudioBridge.shouldStopServerForControlFailure(false))
        assertEquals(false, LauncherAudioBridge.shouldStopServerForControlFailure(true))
        assertEquals(
            true,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusInterrupted = false,
            ),
        )
        assertEquals(
            false,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusInterrupted = true,
            ),
        )
    }

    @Test
    fun playbackRemainsSuspendedWithoutUsableAudioFocus() {
        assertEquals(
            false,
            LauncherAudioBridge.shouldSuspendPlayback(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusRequested = true,
                focusInterrupted = false,
            ),
        )
        for (state in listOf(0, 1, 2, 3, 4)) {
            assertEquals(
                true,
                LauncherAudioBridge.shouldSuspendPlayback(
                    hostActive = state != 0,
                    runtimeForeground = state != 1,
                    activePlaybackInputCount = if (state == 2) 0 else 1,
                    focusRequested = state != 3,
                    focusInterrupted = state == 4,
                ),
            )
        }
        assertEquals(250L, LauncherAudioBridge.focusRetryDelayMillis(1))
        assertEquals(4_000L, LauncherAudioBridge.focusRetryDelayMillis(5))
        assertEquals(4_000L, LauncherAudioBridge.focusRetryDelayMillis(50))
        assertEquals(250L, LauncherAudioBridge.controlRetryDelayMillis(1))
    }

    @Test
    fun parsesOnlyBoundedPulsePlaybackInputLifecycleLines() {
        assertEquals(
            18L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 17 \"Playback Stream\" on archphene_output",
            ),
        )
        assertEquals(
            -18L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Freeing input 17 \"Playback Stream\"",
            ),
        )
        assertEquals(0L, LauncherAudioBridge.pulsePlaybackInputEvent("Created source output 17"))
        assertEquals(
            4_097L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4096 \"unbounded\"",
            ),
        )
        assertEquals(
            4_294_967_296L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4294967295 \"rolled over\"",
            ),
        )
        assertEquals(
            0L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4294967296 \"out of range\"",
            ),
        )
    }

    @Test
    fun recognizesOnlyCompletedPulseServerStartup() {
        assertEquals(
            true,
            LauncherAudioBridge.isPulseServerReadyLine(
                "I: [pulseaudio] main.c: Daemon startup complete.",
            ),
        )
        assertEquals(
            false,
            LauncherAudioBridge.isPulseServerReadyLine(
                "I: [pulseaudio] main.c: Starting daemon.",
            ),
        )
    }
}
