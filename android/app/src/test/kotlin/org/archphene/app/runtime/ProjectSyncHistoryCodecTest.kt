package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectSyncHistoryCodecTest {
    private val entry =
        ProjectSyncHistoryEntry(
            timestampMillis = 1_722_000_000_000,
            outcome = SYNC_HISTORY_SUCCESS,
            project = "Example \ud83d\ude80",
            mappingId = "0123456789abcdef".repeat(2),
            pulled = 2,
            pushed = 3,
            deferredDeletes = 1,
            message = "Synced 5 changes \ud83c\udf89",
            conflictPaths = listOf("src/main.rs", "notes/plan-\ud83d\ude80.txt"),
        )

    @Test
    fun historyRoundTripsExactly() {
        val entries =
            listOf(
                entry,
                entry.copy(
                    timestampMillis = entry.timestampMillis + 1,
                    outcome = SYNC_HISTORY_FAILED,
                    message = "Provider stopped responding",
                ),
            )

        assertEquals(entries, ProjectSyncHistoryCodec.decode(ProjectSyncHistoryCodec.encode(entries)))
    }

    @Test
    fun corruptionAndTrailingDataFailClosed() {
        val corrupted = ProjectSyncHistoryCodec.encode(listOf(entry))
        corrupted[20] = (corrupted[20].toInt() xor 1).toByte()
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncHistoryCodec.decode(corrupted)
        }

        val encoded = ProjectSyncHistoryCodec.encode(listOf(entry))
        val trailing =
            encoded.copyOf(encoded.size + 1).also {
                System.arraycopy(encoded, encoded.size - 8, it, encoded.size - 7, 8)
                it[encoded.size - 8] = 1
                refreshChecksum(it)
            }
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncHistoryCodec.decode(trailing)
        }
    }

    @Test
    fun unsafeConflictPathAndInvalidOutcomeFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncHistoryCodec.encode(
                listOf(entry.copy(conflictPaths = listOf("../outside"))),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ProjectSyncHistoryCodec.encode(listOf(entry.copy(outcome = 99)))
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
