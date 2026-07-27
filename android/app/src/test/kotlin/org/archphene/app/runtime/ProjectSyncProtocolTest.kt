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
    fun malformedPlanAndFingerprintTextFailClosed() {
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
        assertThrows(IllegalStateException::class.java) {
            decodeProjectSyncFingerprintText("f:1:not-a-digest")
        }
    }

    @Test
    fun fingerprintTextRoundTripsWithoutPlatformState() {
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
    }

    @Test
    fun providerNamesRejectTraversalSpoofingAndMalformedUnicode() {
        assertTrue(safeProjectSyncName("Project notes.txt"))
        assertTrue(safeProjectSyncName("notes-\ud83d\ude80.txt"))
        assertFalse(safeProjectSyncName(".."))
        assertFalse(safeProjectSyncName("nested/name"))
        assertFalse(safeProjectSyncName("report\u202etxt"))
        assertFalse(safeProjectSyncName("broken\ud800name"))
    }
}
