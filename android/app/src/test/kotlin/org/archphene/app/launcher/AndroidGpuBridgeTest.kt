package org.archphene.app.launcher

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGpuBridgeTest {
    @Test
    fun forcedGpuHelperShutdownWaitsForReaping() {
        val child =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                "trap '' TERM; echo ready; while :; do sleep 1; done",
            ).redirectErrorStream(true)
                .start()
        try {
            assertEquals("ready", child.inputStream.bufferedReader().readLine())
            assertTrue(AndroidGpuBridge.stopProcess(child, 250))
            assertFalse(child.isAlive)
        } finally {
            child.destroyForcibly()
            child.waitFor()
        }
    }

    @Test
    fun forcedGpuHelperTimeoutReportsThatCleanupMustRemainTracked() {
        val child = StubbornProcess()
        assertFalse(AndroidGpuBridge.stopProcess(child, 1))
        assertTrue(child.destroyed)
        assertTrue(child.forced)
    }

    @Test
    fun interruptedGpuHelperShutdownRestoresInterruptAfterForcedWait() {
        val child =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                "trap '' TERM; echo ready; while :; do sleep 1; done",
            ).redirectErrorStream(true)
                .start()
        try {
            assertEquals("ready", child.inputStream.bufferedReader().readLine())
            Thread.currentThread().interrupt()
            assertTrue(AndroidGpuBridge.stopProcess(child, 1_000))
            assertFalse(child.isAlive)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            child.destroyForcibly()
            child.waitFor()
        }
    }

    @Test
    fun runtimeDirectoryNamesUseTheExactManagerOwnedShape() {
        assertTrue(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdef",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdeg",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu-42-0123456789abcdef-extra",
            ),
        )
        assertFalse(
            AndroidGpuBridge.isRuntimeDirectoryName(
                "gpu--0123456789abcdef",
            ),
        )
        assertFalse(AndroidGpuBridge.isRuntimeDirectoryName("unrelated-cache"))
    }

    private class StubbornProcess : Process() {
        var destroyed = false
        var forced = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = throw InterruptedException("still running")

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false

        override fun exitValue(): Int = throw IllegalThreadStateException("still running")

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forced = true
            return this
        }

        override fun isAlive(): Boolean = true
    }
}
