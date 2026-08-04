package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPortalBridgeTest {
    private class ControlledProcess(
        waitResults: List<Boolean>,
    ) : Process() {
        private val waits = ArrayDeque(waitResults)
        private var alive = true
        var gracefulDestroyCalled = false
        var forcedDestroyCalled = false
        var timedWaitCalls = 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = error("Unbounded waitFor() must not be called")

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            require(timeout > 0)
            timedWaitCalls++
            val terminated = waits.removeFirst()
            if (terminated) alive = false
            return terminated
        }

        override fun exitValue(): Int {
            check(!alive)
            return 0
        }

        override fun destroy() {
            gracefulDestroyCalled = true
        }

        override fun destroyForcibly(): Process {
            forcedDestroyCalled = true
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    @Test
    fun portalRequestFieldsAreBoundedDuringSplitting() {
        assertEquals(
            listOf(
                "ARCHPHENE/3",
                "STORE_SECRET",
                "id",
                "label",
                "attributes",
                "content-type",
            ),
            LauncherPortalBridge.splitPortalRequest(
                "ARCHPHENE/3\tSTORE_SECRET\tid\tlabel\tattributes\tcontent-type",
            ),
        )
        assertNull(LauncherPortalBridge.splitPortalRequest("a\tb\tc\td\te\tf\tg"))

        val hostile = List(8_192) { "x" }.joinToString("\t")
        assertEquals(16_383, hostile.length)
        assertNull(LauncherPortalBridge.splitPortalRequest(hostile))
    }

    @Test
    fun portalResponseFieldsAreBoundedDuringSplitting() {
        assertEquals(
            listOf("OK", "label", "attributes", "65536"),
            LauncherPortalBridge.splitPortalFields("OK\tlabel\tattributes\t65536", 4),
        )
        assertEquals(
            listOf("OK", "0", "refresh", ""),
            LauncherPortalBridge.splitPortalFields("OK\t0\trefresh\t", 4),
        )
        assertNull(LauncherPortalBridge.splitPortalFields("OK\ta\tb\tc\td", 4))
        assertNull(LauncherPortalBridge.splitPortalFields("OK\t1\textra", 2))

        val hostile = List(8_192) { "x" }.joinToString("\t") + "\t"
        assertEquals(16_384, hostile.length)
        assertNull(LauncherPortalBridge.splitPortalFields(hostile, 4))
        assertNull(LauncherPortalBridge.splitPortalFields(hostile, 2))
    }

    @Test
    fun portalLogDrainBoundsHostileLineAndContinues() {
        val payload =
            ("x".repeat(64 * 1024) + "\nnext\rprogress\r\nlast\n").toByteArray()
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
        LauncherPortalBridge.drainBoundedUtf8Lines(input, 512, lines::add)
        assertEquals(listOf("x".repeat(512), "next", "progress", "last"), lines)
        assertEquals(payload.size, consumed.get())
    }

    @Test
    fun portalProcessStopReturnsAfterTwoBoundedTimeouts() {
        val process = ControlledProcess(listOf(false, false))
        assertFalse(LauncherPortalBridge.stopProcessBoundedly(process, 25))
        assertTrue(process.gracefulDestroyCalled)
        assertTrue(process.forcedDestroyCalled)
        assertEquals(2, process.timedWaitCalls)
        assertTrue(process.isAlive)
    }

    @Test
    fun portalProcessStopAcceptsForcedReap() {
        val process = ControlledProcess(listOf(false, true))
        assertTrue(LauncherPortalBridge.stopProcessBoundedly(process, 25))
        assertTrue(process.forcedDestroyCalled)
        assertEquals(2, process.timedWaitCalls)
        assertFalse(process.isAlive)
    }

    @Test
    fun portalRecoveryExcludesProcessOwnedPaths() {
        val owned = Path.of("/cache/p7-0123456789abcdef")
        val stale = Path.of("/cache/p8-fedcba9876543210")
        assertFalse(LauncherPortalBridge.shouldRecoverPortalPath(owned, setOf(owned)))
        assertTrue(LauncherPortalBridge.shouldRecoverPortalPath(stale, setOf(owned)))
    }

    @Test
    fun portalCleanupWaitsForImportsMirroringAndSaveFinalization() {
        val stopped =
            PortalCloseReadiness(
                brokerStopped = true,
                clientsStopped = true,
                importsStopped = true,
                mirrorStopped = true,
                processesStopped = true,
                drainersStopped = true,
                saveFinalizerStopped = true,
                directoryCancelStopped = true,
                savesFinalized = true,
            )
        assertTrue(stopped.canCleanup)
        assertFalse(stopped.copy(importsStopped = false).canCleanup)
        assertFalse(stopped.copy(mirrorStopped = false).canCleanup)
        assertFalse(stopped.copy(saveFinalizerStopped = false).canCleanup)
        assertFalse(stopped.copy(savesFinalized = false).canCleanup)
    }

    @Test
    fun portalStagingIsPreservedWhenRequiredFinalCopyFails() {
        assertFalse(
            canDiscardPortalStaging(
                copyRequired = true,
                copySucceeded = false,
                recovered = false,
            ),
        )
        assertTrue(
            canDiscardPortalStaging(
                copyRequired = true,
                copySucceeded = true,
                recovered = false,
            ),
        )
        assertTrue(
            canDiscardPortalStaging(
                copyRequired = true,
                copySucceeded = false,
                recovered = true,
            ),
        )
        assertTrue(
            canDiscardPortalStaging(
                copyRequired = false,
                copySucceeded = false,
                recovered = false,
            ),
        )
    }

    @Test
    fun failedPortalSaveRecoveryAtomicallyMovesToBoundedName() {
        val root = Files.createTempDirectory("archphene-portal-save-recovery")
        try {
            val source = root.resolve("staging").toFile()
            source.writeText("recover me")
            val imports = root.resolve("imports").toFile()
            assertTrue(imports.mkdir())
            val name = LauncherPortalBridge.recoverPortalSaveFile(source, imports)
            assertTrue(name.toByteArray().size <= 255)
            assertEquals("recover me", File(imports, name).readText())
            assertFalse(source.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedPortalSaveRecoveryRejectsBoundedDirectoryCapacity() {
        val root = Files.createTempDirectory("archphene-portal-save-capacity")
        try {
            val source = root.resolve("staging").toFile()
            source.writeText("retain me")
            val recovery = root.resolve("recovery").toFile()
            assertTrue(recovery.mkdir())
            repeat(32) { index ->
                File(recovery, "Recovered portal save ${index.toString(16).padStart(32, '0')}")
                    .createNewFile()
            }
            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverPortalSaveFile(source, recovery)
            }
            assertTrue(source.isFile)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
