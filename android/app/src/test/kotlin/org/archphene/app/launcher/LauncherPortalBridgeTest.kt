package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherPortalBridgeTest {
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
}
