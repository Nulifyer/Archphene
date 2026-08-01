package org.archphene.app.runtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputTest {
    @Test
    fun acceptsEmptyAndExactLimitInputs() {
        assertArrayEquals(
            ByteArray(0),
            ByteArrayInputStream(ByteArray(0)).readBoundedBytes(8, "oversized"),
        )
        val exact = ByteArray(8) { it.toByte() }
        assertArrayEquals(
            exact,
            ByteArrayInputStream(exact).readBoundedBytes(8, "oversized"),
        )
    }

    @Test
    fun rejectsLimitPlusOneWithoutReadingAnUnboundedTail() {
        val input = CountingInputStream(ByteArray(64))
        assertThrows(IllegalStateException::class.java) {
            input.readBoundedBytes(8, "oversized")
        }
        org.junit.Assert.assertEquals(9, input.bytesRead)
    }

    @Test
    fun consumesShortChunkedStreamsExactly() {
        val expected = ByteArray(31) { (it + 1).toByte() }
        val chunked =
            object : InputStream() {
                var offset = 0

                override fun read(): Int =
                    if (offset >= expected.size) -1 else expected[offset++].toInt() and 0xff

                override fun read(
                    target: ByteArray,
                    targetOffset: Int,
                    length: Int,
                ): Int {
                    if (offset >= expected.size) return -1
                    val count = minOf(3, length, expected.size - offset)
                    expected.copyInto(target, targetOffset, offset, offset + count)
                    offset += count
                    return count
                }
            }
        assertArrayEquals(expected, chunked.readBoundedBytes(32, "oversized"))
    }

    @Test
    fun zeroProgressBulkReadFallsBackToOneBoundedByte() {
        val expected = byteArrayOf(1, 2, 3)
        val stream =
            object : InputStream() {
                var offset = 0
                var returnedZero = false

                override fun read(): Int =
                    if (offset >= expected.size) -1 else expected[offset++].toInt() and 0xff

                override fun read(
                    target: ByteArray,
                    targetOffset: Int,
                    length: Int,
                ): Int {
                    if (!returnedZero) {
                        returnedZero = true
                        return 0
                    }
                    if (offset >= expected.size) return -1
                    val count = minOf(length, expected.size - offset)
                    expected.copyInto(target, targetOffset, offset, offset + count)
                    offset += count
                    return count
                }
            }
        assertArrayEquals(expected, stream.readBoundedBytes(3, "oversized"))
    }

    @Test
    fun backupOnlyAtomicStateIsOpenedAndValidated() {
        val directory = Files.createTempDirectory("archphene-atomic-input").toFile()
        try {
            val base = directory.resolve("state")
            val recovered = byteArrayOf(7, 8, 9)
            directory.resolve("state.bak").writeBytes(recovered)
            assertArrayEquals(
                recovered,
                readRecoveredAtomicBytes(
                    base,
                    3,
                    "oversized",
                ),
            )
            assertArrayEquals(recovered, base.readBytes())
            assertFalse(directory.resolve("state.bak").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validBackupReplacesOversizedBaseBeforeReading() {
        val directory = Files.createTempDirectory("archphene-atomic-replace").toFile()
        try {
            val base = directory.resolve("state").apply { writeBytes(ByteArray(64)) }
            val recovered = byteArrayOf(4, 5, 6)
            directory.resolve("state.bak").writeBytes(recovered)
            assertArrayEquals(
                recovered,
                readRecoveredAtomicBytes(base, 3, "oversized"),
            )
            assertArrayEquals(recovered, base.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveredBackupStillEnforcesStreamLimit() {
        val directory = Files.createTempDirectory("archphene-atomic-limit").toFile()
        try {
            val base = directory.resolve("state")
            directory.resolve("state.bak").writeBytes(ByteArray(9))
            assertThrows(IllegalStateException::class.java) {
                readRecoveredAtomicBytes(
                    base,
                    8,
                    "oversized",
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingStateReturnsNullWithoutOpening() {
        val directory = Files.createTempDirectory("archphene-atomic-missing").toFile()
        try {
            assertNull(
                readRecoveredAtomicBytes(
                    directory.resolve("state"),
                    8,
                    "oversized",
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun symbolicLinkCandidateIsRejectedWithoutFollowingTarget() {
        val directory = Files.createTempDirectory("archphene-atomic-link").toFile()
        try {
            val target = directory.resolve("target").apply { writeBytes(byteArrayOf(1)) }
            val base = directory.resolve("state")
            Files.createSymbolicLink(base.toPath(), target.toPath())
            assertThrows(IllegalStateException::class.java) {
                readRecoveredAtomicBytes(base, 8, "oversized")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class CountingInputStream(private val bytes: ByteArray) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int =
            if (bytesRead >= bytes.size) -1 else bytes[bytesRead++].toInt() and 0xff

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (bytesRead >= bytes.size) return -1
            val count = minOf(length, bytes.size - bytesRead)
            bytes.copyInto(target, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }
    }
}
