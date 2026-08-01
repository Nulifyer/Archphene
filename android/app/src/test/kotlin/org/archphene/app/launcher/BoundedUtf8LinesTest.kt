package org.archphene.app.launcher

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedUtf8LinesTest {
    @Test
    fun hostileLineIsFullyConsumedAndBounded() {
        val payload = ("x".repeat(64 * 1024) + "\nnext\n").toByteArray()
        val consumed = AtomicInteger(0)
        val input =
            object : ByteArrayInputStream(payload) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) consumed.addAndGet(count)
                    return count
                }
            }
        val lines = ArrayList<BoundedUtf8Line>()

        drainBoundedUtf8Lines(input, 512, lines::add)

        assertEquals(
            listOf(
                BoundedUtf8Line("x".repeat(512), truncated = true),
                BoundedUtf8Line("next", truncated = false),
            ),
            lines,
        )
        assertEquals(payload.size, consumed.get())
    }

    @Test
    fun preservesBufferedReaderDelimiterSemantics() {
        val lines = ArrayList<BoundedUtf8Line>()

        drainBoundedUtf8Lines(
            ByteArrayInputStream("first\rsecond\r\n\nlast".toByteArray()),
            16,
            lines::add,
        )

        assertEquals(
            listOf("first", "second", "", "last").map { line ->
                BoundedUtf8Line(line, truncated = false)
            },
            lines,
        )
    }

    @Test
    fun truncatedLineDropsIncompleteUtf8Tail() {
        val lines = ArrayList<BoundedUtf8Line>()

        drainBoundedUtf8Lines(
            ByteArrayInputStream("a€tail\n".toByteArray()),
            3,
            lines::add,
        )

        assertEquals(listOf(BoundedUtf8Line("a", truncated = true)), lines)
    }
}
