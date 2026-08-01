package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
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
}
