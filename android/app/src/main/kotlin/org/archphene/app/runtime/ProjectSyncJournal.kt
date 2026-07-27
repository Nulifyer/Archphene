package org.archphene.app.runtime

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            output.write(JOURNAL_MAGIC)
            output.writeInt(journal.operation)
            output.writeInt(journal.phase)
            output.writeBoolean(journal.hadOriginal)
            output.writeField(journal.treeUri)
            output.writeField(journal.parentUri)
            output.writeField(journal.path)
            output.writeField(journal.targetName)
            output.writeField(journal.stagingName)
            output.writeField(journal.backupName)
            output.writeField(journal.expected)
        }
        val bodyBytes = body.toByteArray()
        val checksum = CRC32().apply { update(bodyBytes) }.value
        val encoded = ByteArrayOutputStream(bodyBytes.size + 8)
        DataOutputStream(encoded).use { output ->
            output.write(bodyBytes)
            output.writeLong(checksum)
        }
        return encoded.toByteArray().also {
            check(it.size <= MAX_JOURNAL_BYTES) {
                "Project synchronization journal is oversized"
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
        if (!file.exists()) {
            return null
        }
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Project synchronization journal is not a regular file"
        }
        check(file.length() in 1..MAX_JOURNAL_BYTES.toLong()) {
            "Project synchronization journal is oversized"
        }
        return ProjectSyncJournalCodec.decode(AtomicFile(file).openRead().use { it.readBytes() })
    }

    fun updatePhase(phase: Int) {
        val journal = load() ?: error("Project synchronization journal disappeared")
        persist(journal.copy(phase = phase))
    }

    fun clear() {
        AtomicFile(file).delete()
    }
}
