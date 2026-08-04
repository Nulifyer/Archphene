package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersistedAurOutputCodecTest {
    @Test
    fun zeroRowsAllowTerminalNewlines() {
        listOf("", "\n", "\n\n\n").forEach { suffix ->
            assertEquals(emptyList<List<String>>(), decode("ABCY0001\t0$suffix", 0))
        }
    }

    @Test
    fun twoRowsPreserveFieldsWithOptionalTerminalNewlines() {
        val first = listOf("pkg", "", "  version  ", "\r", "雪", "0", "")
        val second = listOf("two", "name", "1", "file", "url", "hash", "signature")
        val manifest = "ABCY0001\t2\n${first.joinToString("\t")}\n${second.joinToString("\t")}"

        listOf("", "\n", "\n\n\n").forEach { suffix ->
            assertEquals(listOf(first, second), decode(manifest + suffix, 2))
        }
    }

    @Test
    fun exactPackageBoundIsAccepted() {
        val manifest = buildString {
            append("ABCY0001\t256\n")
            repeat(256) { index ->
                if (index > 0) append('\n')
                append(row(index.toString()))
            }
        }

        assertEquals(256, decode(manifest, 256).size)
    }

    @Test
    fun extraAndMissingRowsAreRejected() {
        assertRejected("ABCY0001\t1\n${row("a")}\n${row("b")}", 1)
        assertRejected("ABCY0001\t2\n${row("a")}", 2)
    }

    @Test
    fun wrongHeaderAndCountAreRejected() {
        assertRejected("ABCY0002\t0", 0)
        assertRejected("ABCY0001\t1", 0)
        assertRejected("ABCY0001\t0", 1)
    }

    @Test
    fun headerFieldUnderflowAndOverflowAreRejected() {
        assertRejected("ABCY0001", 0)
        assertRejected("ABCY0001\t0\textra", 0)
    }

    @Test
    fun rowFieldUnderflowAndOverflowAreRejected() {
        assertRejected("ABCY0001\t1\na\tb\tc\td\te\tf", 1)
        assertRejected("ABCY0001\t1\na\tb\tc\td\te\tf\tg\th", 1)
    }

    @Test
    fun internalEmptyRowIsRejected() {
        assertRejected("ABCY0001\t2\n${row("a")}\n\n${row("b")}", 2)
    }

    @Test
    fun invalidExpectedCountsAreRejected() {
        assertRejected("ABCY0001\t0", -1)
        assertRejected("ABCY0001\t257", 257)
    }

    @Test
    fun invalidOutputSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedAurOutputCodec.decode(ByteArray(0), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersistedAurOutputCodec.decode(
                ByteArray(NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE + 1),
                0,
            )
        }
    }

    @Test
    fun exactLimitDelimiterFloodsAreRejected() {
        val limit = NativeRuntime.AUR_BUILD_CLOSURE_OUTPUT_SIZE

        assertRejected(ByteArray(limit) { '\n'.code.toByte() }, 0)
        assertRejected(ByteArray(limit) { '\t'.code.toByte() }, 0)
    }

    private fun decode(
        manifest: String,
        expectedPackageCount: Int,
    ): List<List<String>> =
        PersistedAurOutputCodec.decode(manifest.toByteArray(StandardCharsets.UTF_8), expectedPackageCount)

    private fun assertRejected(
        manifest: String,
        expectedPackageCount: Int,
    ) {
        assertRejected(manifest.toByteArray(StandardCharsets.UTF_8), expectedPackageCount)
    }

    private fun assertRejected(
        manifest: ByteArray,
        expectedPackageCount: Int,
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedAurOutputCodec.decode(manifest, expectedPackageCount)
        }
    }

    private fun row(id: String): String = listOf(id, "name", "version", "file", "url", "hash", "signature").joinToString("\t")
}
