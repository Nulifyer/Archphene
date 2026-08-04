package org.archphene.app.runtime

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal const val SYNC_JOURNAL_PUSH = 1
internal const val SYNC_JOURNAL_DELETE = 2
internal const val SYNC_JOURNAL_FILE = "project-sync-journal-v1"
internal const val SYNC_JOURNAL_PREPARED = 1
internal const val SYNC_JOURNAL_STAGED = 2
internal const val SYNC_JOURNAL_BACKED_UP = 3
internal const val SYNC_JOURNAL_PUBLISHED = 4
internal const val SYNC_JOURNAL_COMMITTED = 5

private const val MAX_JOURNAL_BYTES = 64 * 1024
private val JOURNAL_MAGIC = "APSJ0001".toByteArray(StandardCharsets.US_ASCII)

internal data class ProjectSyncJournal(
    val operation: Int,
    val phase: Int,
    val treeUri: String,
    val parentUri: String,
    val path: String,
    val targetName: String,
    val stagingName: String,
    val backupName: String,
    val expected: String,
    val hadOriginal: Boolean,
)

internal object ProjectSyncJournalCodec {
    fun encode(journal: ProjectSyncJournal): ByteArray {
        validate(journal)
        return encodeCrc32Bounded(
            MAX_JOURNAL_BYTES,
            "Project synchronization journal is oversized",
        ) { output ->
            with(output) {
                write(JOURNAL_MAGIC)
                writeInt(journal.operation)
                writeInt(journal.phase)
                writeBoolean(journal.hadOriginal)
                writeField(journal.treeUri)
                writeField(journal.parentUri)
                writeField(journal.path)
                writeField(journal.targetName)
                writeField(journal.stagingName)
                writeField(journal.backupName)
                writeField(journal.expected)
            }
        }
    }

    fun decode(encoded: ByteArray): ProjectSyncJournal {
        check(encoded.size in (JOURNAL_MAGIC.size + 17)..MAX_JOURNAL_BYTES) {
            "Project synchronization journal is truncated or oversized"
        }
        val bodyLength = encoded.size - 8
        val expectedChecksum =
            ByteBuffer
                .wrap(encoded, bodyLength, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .long
        val checksum = CRC32().apply { update(encoded, 0, bodyLength) }.value
        check(expectedChecksum == checksum) {
            "Project synchronization journal checksum differs"
        }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, bodyLength))
        val magic = ByteArray(JOURNAL_MAGIC.size)
        input.readFully(magic)
        check(magic.contentEquals(JOURNAL_MAGIC)) {
            "Project synchronization journal version differs"
        }
        val operation = input.readInt()
        val phase = input.readInt()
        val hadOriginal = input.readBoolean()
        val result =
            ProjectSyncJournal(
                operation,
                phase,
                input.readField(),
                input.readField(),
                input.readField(),
                input.readField(),
                input.readField(),
                input.readField(),
                input.readField(),
                hadOriginal,
            )
        validate(result)
        check(input.available() == 0) {
            "Project synchronization journal has trailing data"
        }
        return result
    }

    private fun validate(journal: ProjectSyncJournal) {
        check(journal.operation in SYNC_JOURNAL_PUSH..SYNC_JOURNAL_DELETE) {
            "Project synchronization journal operation is invalid"
        }
        check(journal.phase in SYNC_JOURNAL_PREPARED..SYNC_JOURNAL_COMMITTED) {
            "Project synchronization journal phase is invalid"
        }
    }

    private fun DataOutputStream.writeField(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
            "Project synchronization journal field is oversized"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readField(): String {
        val length = readInt()
        check(length in 0..NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
            "Project synchronization journal field is invalid"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}

internal class ProjectSyncJournalStore(private val file: File) {
    fun persist(journal: ProjectSyncJournal) {
        val bytes = ProjectSyncJournalCodec.encode(journal)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun load(): ProjectSyncJournal? {
        val bytes =
            readRecoveredAtomicBytes(
                file,
                MAX_JOURNAL_BYTES,
                "Project synchronization journal is oversized",
            ) ?: return null
        return ProjectSyncJournalCodec.decode(
            bytes,
        )
    }

    fun updatePhase(phase: Int) {
        val journal = load() ?: error("Project synchronization journal disappeared")
        persist(journal.copy(phase = phase))
    }

    fun clear() {
        AtomicFile(file).delete()
    }
}
