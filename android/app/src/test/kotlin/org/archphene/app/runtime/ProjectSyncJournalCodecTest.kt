package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectSyncJournalCodecTest {
    private val journal =
        ProjectSyncJournal(
            operation = SYNC_JOURNAL_PUSH,
            phase = SYNC_JOURNAL_PUBLISHED,
            treeUri = "content://provider/tree/root",
            parentUri = "content://provider/document/root",
            path = "src/main.rs",
            targetName = "main.rs",
            stagingName = ".archphene-stage-123",
            backupName = ".archphene-backup-123",
            expected = "f:4:${"01".repeat(32)}",
            hadOriginal = true,
        )

    @Test
    fun journalRoundTripsExactly() {
        assertEquals(journal, ProjectSyncJournalCodec.decode(ProjectSyncJournalCodec.encode(journal)))
    }

    @Test
    fun checksumCorruptionFailsClosed() {
        val encoded = ProjectSyncJournalCodec.encode(journal)
        encoded[21] = (encoded[21].toInt() xor 1).toByte()

        assertThrows(IllegalStateException::class.java) {
            ProjectSyncJournalCodec.decode(encoded)
        }
    }

    @Test
    fun invalidPhaseWithValidChecksumFailsClosed() {
        val encoded = ProjectSyncJournalCodec.encode(journal)
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(12, Int.MAX_VALUE)
        refreshChecksum(encoded)

        assertThrows(IllegalStateException::class.java) {
            ProjectSyncJournalCodec.decode(encoded)
        }
    }

    @Test
    fun malformedUtf8WithValidChecksumFailsClosed() {
        val encoded = ProjectSyncJournalCodec.encode(journal)
        encoded[21] = 0xc0.toByte()
        refreshChecksum(encoded)

        assertThrows(Exception::class.java) {
            ProjectSyncJournalCodec.decode(encoded)
        }
    }

    @Test
    fun trailingDataAndSizeBoundsFailClosed() {
        val encoded = ProjectSyncJournalCodec.encode(journal)
        val withTrailingData =
            encoded.copyOf(encoded.size + 1).also {
                System.arraycopy(encoded, encoded.size - 8, it, encoded.size - 7, 8)
                it[encoded.size - 8] = 1
                refreshChecksum(it)
            }

        assertThrows(IllegalStateException::class.java) {
            ProjectSyncJournalCodec.decode(withTrailingData)
        }
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncJournalCodec.decode(ByteArray(64 * 1024 + 1))
        }
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncJournalCodec.decode(ByteArray(8))
        }
    }

    private fun refreshChecksum(encoded: ByteArray) {
        val bodyLength = encoded.size - 8
        val checksum = CRC32().apply { update(encoded, 0, bodyLength) }.value
        ByteBuffer
            .wrap(encoded)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(bodyLength, checksum)
    }
}
