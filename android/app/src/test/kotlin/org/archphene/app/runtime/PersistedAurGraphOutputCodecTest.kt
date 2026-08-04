package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersistedAurGraphOutputCodecTest {
    @Test
    fun oneRowAllowsTerminalNewlineRunsAndPreservesFields() {
        val fields = listOf("base", "", "  file  ", "\r", "雪", "1", "hash", "")
        val manifest = "ABGY0001\t1\n${fields.joinToString("\t")}"

        listOf("", "\n", "\n\n\n").forEach { suffix ->
            assertEquals(PersistedAurGraphOutput(1, listOf(fields)), decode(manifest + suffix))
        }
    }

    @Test
    fun twoRowsAllowTerminalNewlineRuns() {
        val rows = listOf(rowFields("a"), rowFields("b"))
        val manifest = "ABGY0001\t2\n${rows.joinToString("\n") { it.joinToString("\t") }}"

        listOf("", "\n", "\n\n\n").forEach { suffix ->
            assertEquals(PersistedAurGraphOutput(2, rows), decode(manifest + suffix))
        }
    }

    @Test
    fun exactOutputBoundIsAccepted() {
        val manifest = buildString {
            append("ABGY0001\t256\n")
            repeat(256) { index ->
                if (index > 0) append('\n')
                append(row(index.toString()))
            }
        }

        assertEquals(256, decode(manifest).rows.size)
    }

    @Test
    fun countsOutsideProducerBoundAreRejected() {
        assertRejected("ABGY0001\t0")
        assertRejected("ABGY0001\t257")
    }

    @Test
    fun wrongHeaderAndMalformedCountAreRejected() {
        assertRejected("ABGY0002\t1\n${row("a")}")
        assertRejected("ABGY0001\tone\n${row("a")}")
    }

    @Test
    fun extraMissingAndInternalBlankRowsAreRejected() {
        assertRejected("ABGY0001\t1\n${row("a")}\n${row("b")}")
        assertRejected("ABGY0001\t2\n${row("a")}")
        assertRejected("ABGY0001\t2\n${row("a")}\n\n${row("b")}")
    }

    @Test
    fun headerFieldUnderflowAndOverflowAreRejected() {
        assertRejected("ABGY0001")
        assertRejected("ABGY0001\t1\textra\n${row("a")}")
    }

    @Test
    fun rowFieldUnderflowAndOverflowAreRejected() {
        assertRejected("ABGY0001\t1\na\tb\tc\td\te\tf\tg")
        assertRejected("ABGY0001\t1\na\tb\tc\td\te\tf\tg\th\ti")
    }

    @Test
    fun invalidOutputSizesAreRejected() {
        assertRejected(ByteArray(0))
        assertRejected(ByteArray(NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE + 1))
    }

    @Test
    fun exactLimitDelimiterFloodsAreRejected() {
        val limit = NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE

        assertRejected(ByteArray(limit) { '\n'.code.toByte() })
        assertRejected(ByteArray(limit) { '\t'.code.toByte() })
    }

    private fun decode(manifest: String): PersistedAurGraphOutput =
        PersistedAurGraphOutputCodec.decode(manifest.toByteArray(StandardCharsets.UTF_8))

    private fun assertRejected(manifest: String) {
        assertRejected(manifest.toByteArray(StandardCharsets.UTF_8))
    }

    private fun assertRejected(manifest: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedAurGraphOutputCodec.decode(manifest)
        }
    }

    private fun row(id: String): String = rowFields(id).joinToString("\t")

    private fun rowFields(id: String): List<String> =
        listOf(id, "name", "filename", "10", "20", "1", "hash", "/path")
}
