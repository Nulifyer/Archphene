package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSyncProtocolTest {
    @Test
    fun requestEncodingMatchesUtf8WithoutIntermediateJoin() {
        val fields = arrayOf("push", "notes/é.txt", "rocket-\ud83d\ude80")
        val expected = fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
        val destination = ByteBuffer.allocate(expected.size)

        assertEquals(expected.size, putProjectSyncRequest(destination, *fields))
        assertEquals(expected.size, destination.position())
        assertArrayEquals(expected, destination.array())
    }

    @Test
    fun requestEncodingEnforcesProtocolAndDestinationLimitsBeforeWriting() {
        val exact = "a".repeat(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE)
        val exactDestination = ByteBuffer.allocate(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE)
        assertEquals(exact.length, putProjectSyncRequest(exactDestination, exact))
        val exactFourByte = "\ud83d\ude80".repeat(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE / 4)
        assertEquals(
            NativeRuntime.PROJECT_SYNC_BUFFER_SIZE,
            putProjectSyncRequest(
                ByteBuffer.allocate(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE),
                exactFourByte,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            putProjectSyncRequest(
                ByteBuffer.allocate(NativeRuntime.PROJECT_SYNC_BUFFER_SIZE),
                "$exactFourByte!",
            )
        }

        val unchanged = ByteBuffer.allocate(8).apply { put(0x5a.toByte()) }
        assertThrows(IllegalStateException::class.java) {
            putProjectSyncRequest(unchanged, "123456789")
        }
        assertEquals(1, unchanged.position())
        assertEquals(0x5a, unchanged.get(0).toInt())

        assertThrows(IllegalStateException::class.java) {
            putProjectSyncRequest(ByteBuffer.allocate(32), "broken\ud800")
        }
        assertThrows(IllegalStateException::class.java) {
            putProjectSyncRequest(ByteBuffer.allocate(32), "tab\tfield")
        }
        assertThrows(IllegalStateException::class.java) {
            putProjectSyncRequest(ByteBuffer.allocate(32), "")
        }
    }

    @Test
    fun fixedPlanEntryDecodesExactly() {
        val path = "src/main.rs".toByteArray(StandardCharsets.UTF_8)
        val source =
            ByteBuffer
                .allocate(SYNC_PLAN_HEADER_BYTES + path.size)
                .order(ByteOrder.LITTLE_ENDIAN)
        source.put("ASPE0001".toByteArray(StandardCharsets.US_ASCII))
        source.put(8, SYNC_ACTION_PULL_LINUX.toByte())
        source.put(9, SYNC_KIND_ABSENT.toByte())
        source.put(10, SYNC_KIND_FILE.toByte())
        source.put(11, SYNC_KIND_FILE.toByte())
        source.putInt(12, path.size)
        source.putLong(56, 4)
        source.putLong(96, 7)
        repeat(32) { index ->
            source.put(64 + index, index.toByte())
            source.put(104 + index, (255 - index).toByte())
        }
        source.position(SYNC_PLAN_HEADER_BYTES)
        source.put(path)

        val entry = decodeProjectSyncPlanEntry(source, source.capacity())

        assertEquals("src/main.rs", entry.path)
        assertEquals(SYNC_ACTION_PULL_LINUX, entry.action)
        assertNull(entry.baseline)
        assertEquals(4L, entry.linux?.bytes)
        assertEquals(7L, entry.android?.bytes)
        assertArrayEquals(ByteArray(32) { it.toByte() }, entry.linux?.sha256)
        assertArrayEquals(ByteArray(32) { (255 - it).toByte() }, entry.android?.sha256)
    }

    @Test
    fun malformedPlanFailsClosed() {
        val source =
            ByteBuffer
                .allocate(SYNC_PLAN_HEADER_BYTES + 2)
                .order(ByteOrder.LITTLE_ENDIAN)
        source.put("ASPE0001".toByteArray(StandardCharsets.US_ASCII))
        source.put(8, SYNC_ACTION_CONVERGED.toByte())
        source.put(9, SYNC_KIND_ABSENT.toByte())
        source.put(10, SYNC_KIND_ABSENT.toByte())
        source.put(11, SYNC_KIND_ABSENT.toByte())
        source.putInt(12, 2)
        source.position(SYNC_PLAN_HEADER_BYTES)
        source.put("..".toByteArray(StandardCharsets.UTF_8))
        assertThrows(IllegalStateException::class.java) {
            decodeProjectSyncPlanEntry(source, source.capacity())
        }
    }

    @Test
    fun fingerprintTextDecodesFilesAndDirectories() {
        val fingerprint =
            ProjectSyncFingerprint(
                SYNC_KIND_FILE,
                4096,
                ByteArray(32) { (it * 7).toByte() },
            )
        val decoded = decodeProjectSyncFingerprintText(fingerprint.encode())
        assertEquals(fingerprint.kind, decoded.kind)
        assertEquals(fingerprint.bytes, decoded.bytes)
        assertArrayEquals(fingerprint.sha256, decoded.sha256)

        val directory = decodeProjectSyncFingerprintText("d")
        assertEquals(SYNC_KIND_DIRECTORY, directory.kind)
        assertEquals(0L, directory.bytes)
        assertArrayEquals(ByteArray(32), directory.sha256)
    }

    @Test
    fun fingerprintTextRejectsMalformedFieldCountsAndColonFlood() {
        val digest = "01".repeat(32)
        listOf("", "f", "f:1", "f:1:$digest:extra", "f:1:" + ":".repeat(8 * 1024)).forEach { value ->
            assertThrows(IllegalStateException::class.java) {
                decodeProjectSyncFingerprintText(value)
            }
        }
    }

    @Test
    fun fingerprintTextRejectsInvalidDigestsAndSizes() {
        val digest = "01".repeat(32)
        listOf(
            "f:1:${"01".repeat(31)}0A",
            "f:1:${"01".repeat(31)}0g",
            "f:1:${"0".repeat(63)}",
            "f:1:${"0".repeat(65)}",
            "f:-1:$digest",
            "f:2147483649:$digest",
        ).forEach { value ->
            assertThrows(IllegalStateException::class.java) {
                decodeProjectSyncFingerprintText(value)
            }
        }
    }

    @Test
    fun providerNamesRejectTraversalSpoofingAndMalformedUnicode() {
        assertTrue(safeProjectSyncName("Project notes.txt"))
        assertTrue(safeProjectSyncName("notes-\ud83d\ude80.txt"))
        assertTrue(safeProjectSyncName("€".repeat(85)))
        assertFalse(safeProjectSyncName("€".repeat(86)))
        assertFalse(safeProjectSyncName(".."))
        assertFalse(safeProjectSyncName("nested/name"))
        assertFalse(safeProjectSyncName("report\u202etxt"))
        assertFalse(safeProjectSyncName("broken\ud800name"))
    }

    @Test
    fun projectPathsEnforceSafeSegmentsAndDepthWithoutSplitting() {
        assertTrue(safeProjectSyncPath(List(64) { "a" }.joinToString("/")))
        assertFalse(safeProjectSyncPath(List(65) { "a" }.joinToString("/")))
        assertFalse(safeProjectSyncPath("/leading"))
        assertFalse(safeProjectSyncPath("trailing/"))
        assertFalse(safeProjectSyncPath("double//slash"))
        assertFalse(safeProjectSyncPath("safe/../name"))
        assertFalse(safeProjectSyncPath("safe/./name"))
        assertFalse(safeProjectSyncPath("safe/report\u202etxt"))
        assertFalse(safeProjectSyncPath("a/".repeat(2_047) + "a"))
    }

    @Test
    fun providerDocumentIdsAndMimeTypesAreBoundedBeforeRetention() {
        assertTrue(safeProjectSyncDocumentId("a".repeat(4_096)))
        assertTrue(safeProjectSyncDocumentId("\ud83d\ude80".repeat(1_024)))
        assertFalse(safeProjectSyncDocumentId("a".repeat(4_097)))
        assertFalse(safeProjectSyncDocumentId("\ud83d\ude80".repeat(1_024) + "a"))
        assertFalse(safeProjectSyncDocumentId("unsafe\u202eid"))
        assertFalse(safeProjectSyncDocumentId("broken\ud800id"))

        assertTrue(safeProjectSyncMime("x".repeat(255)))
        assertFalse(safeProjectSyncMime("x".repeat(256)))
        assertFalse(safeProjectSyncMime("text/plain\nunsafe"))
        assertFalse(safeProjectSyncMime("broken\ud800mime"))
    }
}
