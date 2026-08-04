package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherCameraBridgeTest {
    @Test
    fun cameraRuntimeRegistryRetainsFailedCloseAndBlocksReplacement() {
        val registry = RuntimeLifecycleRegistry<String>()
        registry.claim("active")
        registry.finish("active", terminated = false)
        assertTrue(registry.hasUnreaped())
        assertEquals(listOf("active"), registry.unreapedSnapshot())
        assertEquals(listOf("active"), registry.ownedSnapshot())
    }

    @Test
    fun cameraRuntimeRegistryReleasesRuntimeAfterEventualReap() {
        val registry = RuntimeLifecycleRegistry<String>()
        registry.claim("retained")
        registry.finish("retained", terminated = false)
        registry.finish("retained", terminated = true)
        assertFalse(registry.hasUnreaped())
        assertTrue(registry.unreapedSnapshot().isEmpty())
        assertTrue(registry.ownedSnapshot().isEmpty())
    }

    @Test
    fun cameraRuntimeRegistryBlocksOwnedSameSessionUntilCloseFinishes() {
        data class Runtime(val sessionId: Int)

        val registry = RuntimeLifecycleRegistry<Runtime>()
        val oldRuntime = Runtime(7)
        registry.claim(oldRuntime)
        assertTrue(registry.hasOwnedMatching { runtime -> runtime.sessionId == 7 })
        assertFalse(registry.hasOwnedMatching { runtime -> runtime.sessionId == 8 })
        registry.finish(oldRuntime, terminated = true)
        assertFalse(registry.hasOwnedMatching { runtime -> runtime.sessionId == 7 })
    }

    @Test
    fun staleCleanupExcludesOwnedRuntimeDirectory() {
        val owned = Path.of("/cache/camera-7-0123456789abcdef")
        val stale = Path.of("/cache/camera-8-fedcba9876543210")
        assertFalse(LauncherCameraBridge.shouldCleanupRuntimeDirectory(owned, setOf(owned)))
        assertTrue(LauncherCameraBridge.shouldCleanupRuntimeDirectory(stale, setOf(owned)))
    }

    @Test
    fun cameraLogDrainDiscardsAnOverlongLineWithoutStoppingDrainage() {
        val payload = ("a".repeat(64 * 1024) + "\nnext\r\n").toByteArray()
        val consumed = AtomicInteger(0)
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
                    val count = source.read(buffer, offset, length)
                    if (count > 0) consumed.addAndGet(count)
                    return count
                }
            }
        val lines = mutableListOf<String>()
        LauncherCameraBridge.drainBoundedUtf8Lines(input, 512, lines::add)
        assertEquals(listOf("a".repeat(512), "next"), lines)
        assertEquals(payload.size, consumed.get())
    }

    @Test
    fun cameraLogDrainPublishesBoundedFinalLineWithoutNewline() {
        val lines = mutableListOf<String>()
        LauncherCameraBridge.drainBoundedUtf8Lines(
            ByteArrayInputStream("final".toByteArray()),
            512,
            lines::add,
        )
        assertEquals(listOf("final"), lines)
    }

    @Test
    fun runtimeDirectoryNamesUseTheExactManagerOwnedShape() {
        assertTrue(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdef",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdeg",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera-7-0123456789abcdef-extra",
            ),
        )
        assertFalse(
            LauncherCameraBridge.isRuntimeDirectoryName(
                "camera--0123456789abcdef",
            ),
        )
        assertFalse(LauncherCameraBridge.isRuntimeDirectoryName("unrelated-cache"))
    }

    @Test
    fun validCameraRuntimeIsRemoved() {
        val cache = Files.createTempDirectory("camera-cleanup-valid")
        val root = cache.resolve("camera-7-0123456789abcdef")
        try {
            Files.createDirectories(root.resolve("spa-0.2/support"))
            Files.write(root.resolve("spa-0.2/support/libspa-support.so"), byteArrayOf(1, 2, 3))
            val failures = mutableListOf<Throwable>()

            LauncherCameraBridge.cleanupRuntimeDirectory(
                root,
                log = false,
                reportFailure = failures::add,
            )

            assertTrue(failures.isEmpty())
            assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        } finally {
            if (Files.exists(cache)) {
                Files.walk(cache).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    @Test
    fun cameraRuntimeOverEntryLimitIsRetainedExactly() {
        val cache = Files.createTempDirectory("camera-cleanup-entry-limit")
        val root = Files.createDirectory(cache.resolve("camera-7-0123456789abcdef"))
        val expected =
            (0 until 161).associate { index ->
                "entry-$index" to "payload-$index".toByteArray()
            }
        try {
            expected.forEach { (name, bytes) -> Files.write(root.resolve(name), bytes) }
            val failures = mutableListOf<Throwable>()

            LauncherCameraBridge.cleanupRuntimeDirectory(
                root,
                log = false,
                reportFailure = failures::add,
            )

            assertEquals(1, failures.size)
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
    fun cameraRuntimeOverDepthLimitIsRetainedExactly() {
        val cache = Files.createTempDirectory("camera-cleanup-depth-limit")
        val root = Files.createDirectory(cache.resolve("camera-7-0123456789abcdef"))
        val marker = "retained".toByteArray()
        try {
            Files.write(root.resolve("marker"), marker)
            Files.createDirectories(root.resolve("depth-1/depth-2/depth-3/depth-4"))
            val failures = mutableListOf<Throwable>()

            LauncherCameraBridge.cleanupRuntimeDirectory(
                root,
                log = false,
                reportFailure = failures::add,
            )

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
}
