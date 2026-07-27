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

internal const val SYNC_HISTORY_FILE = "project-sync-history-v1"
internal const val SYNC_HISTORY_SUCCESS = 1
internal const val SYNC_HISTORY_FAILED = 2
internal const val SYNC_HISTORY_CANCELLED = 3
private const val MAX_HISTORY_ENCODED_BYTES = 128 * 1024

internal data class ProjectSyncHistoryEntry(
    val timestampMillis: Long,
    val outcome: Int,
    val project: String,
    val mappingId: String,
    val pulled: Int,
    val pushed: Int,
    val deferredDeletes: Int,
    val message: String,
    val conflictPaths: List<String>,
)

internal object ProjectSyncHistoryCodec {
    fun encode(entries: List<ProjectSyncHistoryEntry>): ByteArray {
        check(entries.size <= MAX_ENTRIES) {
            "Project synchronization history has too many entries"
        }
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            output.write(MAGIC)
            output.writeInt(entries.size)
            entries.forEach { entry ->
                validate(entry)
                output.writeLong(entry.timestampMillis)
                output.writeInt(entry.outcome)
                output.writeInt(entry.pulled)
                output.writeInt(entry.pushed)
                output.writeInt(entry.deferredDeletes)
                output.writeField(entry.project, MAX_PROJECT_BYTES)
                output.writeField(entry.mappingId, MAPPING_ID_BYTES)
                output.writeField(entry.message, MAX_MESSAGE_BYTES)
                output.writeInt(entry.conflictPaths.size)
                entry.conflictPaths.forEach { output.writeField(it, MAX_PATH_BYTES) }
            }
        }
        val bodyBytes = body.toByteArray()
        val checksum = CRC32().apply { update(bodyBytes) }.value
        val encoded = ByteArrayOutputStream(bodyBytes.size + CHECKSUM_BYTES)
        DataOutputStream(encoded).use { output ->
            output.write(bodyBytes)
            output.writeLong(checksum)
        }
        return encoded.toByteArray().also {
            check(it.size <= MAX_HISTORY_ENCODED_BYTES) {
                "Project synchronization history is oversized"
            }
        }
    }

    fun decode(encoded: ByteArray): List<ProjectSyncHistoryEntry> {
        check(encoded.size in MIN_ENCODED_BYTES..MAX_HISTORY_ENCODED_BYTES) {
            "Project synchronization history is truncated or oversized"
        }
        val bodyLength = encoded.size - CHECKSUM_BYTES
        val expectedChecksum =
            ByteBuffer
                .wrap(encoded, bodyLength, CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .long
        val checksum = CRC32().apply { update(encoded, 0, bodyLength) }.value
        check(checksum == expectedChecksum) {
            "Project synchronization history checksum differs"
        }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, bodyLength))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        check(magic.contentEquals(MAGIC)) {
            "Project synchronization history version differs"
        }
        val count = input.readInt()
        check(count in 0..MAX_ENTRIES) {
            "Project synchronization history count is invalid"
        }
        val entries = ArrayList<ProjectSyncHistoryEntry>(count)
        repeat(count) {
            val timestampMillis = input.readLong()
            val outcome = input.readInt()
            val pulled = input.readInt()
            val pushed = input.readInt()
            val deferredDeletes = input.readInt()
            val project = input.readField(MAX_PROJECT_BYTES)
            val mappingId = input.readField(MAPPING_ID_BYTES)
            val message = input.readField(MAX_MESSAGE_BYTES)
            val conflictCount = input.readInt()
            check(conflictCount in 0..MAX_CONFLICTS) {
                "Project synchronization conflict count is invalid"
            }
            val conflicts = ArrayList<String>(conflictCount)
            repeat(conflictCount) {
                conflicts.add(input.readField(MAX_PATH_BYTES))
            }
            entries.add(
                ProjectSyncHistoryEntry(
                    timestampMillis,
                    outcome,
                    project,
                    mappingId,
                    pulled,
                    pushed,
                    deferredDeletes,
                    message,
                    conflicts,
                ).also(::validate),
            )
        }
        check(input.available() == 0) {
            "Project synchronization history has trailing data"
        }
        return entries
    }

    private fun validate(entry: ProjectSyncHistoryEntry) {
        check(entry.timestampMillis > 0) {
            "Project synchronization history time is invalid"
        }
        check(entry.outcome in SYNC_HISTORY_SUCCESS..SYNC_HISTORY_CANCELLED) {
            "Project synchronization history outcome is invalid"
        }
        check(entry.pulled in 0..MAX_CHANGE_COUNT && entry.pushed in 0..MAX_CHANGE_COUNT) {
            "Project synchronization history change count is invalid"
        }
        check(entry.deferredDeletes in 0..MAX_CHANGE_COUNT) {
            "Project synchronization history deletion count is invalid"
        }
        check(
            entry.project.isNotEmpty() &&
                encodedLength(entry.project) <= MAX_PROJECT_BYTES &&
                safeDisplayText(entry.project, allowLineBreaks = false),
        ) {
            "Project synchronization history project is invalid"
        }
        check(
            entry.mappingId.length == MAPPING_ID_BYTES &&
                entry.mappingId.all { it in '0'..'9' || it in 'a'..'f' },
        ) {
            "Project synchronization history mapping is invalid"
        }
        check(
            entry.message.isNotEmpty() &&
                encodedLength(entry.message) <= MAX_MESSAGE_BYTES &&
                safeDisplayText(entry.message, allowLineBreaks = true),
        ) {
            "Project synchronization history message is invalid"
        }
        check(entry.conflictPaths.size <= MAX_CONFLICTS) {
            "Project synchronization history has too many conflicts"
        }
        entry.conflictPaths.forEach { path ->
            check(
                encodedLength(path) <= MAX_PATH_BYTES &&
                    path.split('/').all(::safeProjectSyncName),
            ) {
                "Project synchronization history path is unsafe"
            }
        }
    }

    private fun DataOutputStream.writeField(
        value: String,
        maximumBytes: Int,
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= maximumBytes) {
            "Project synchronization history field is oversized"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readField(maximumBytes: Int): String {
        val length = readInt()
        check(length in 0..maximumBytes) {
            "Project synchronization history field is invalid"
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

    private fun encodedLength(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun safeDisplayText(
        value: String,
        allowLineBreaks: Boolean,
    ): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                Character.isHighSurrogate(character) &&
                (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))
            ) {
                return false
            }
            if (Character.isLowSurrogate(character)) {
                return false
            }
            val codePoint = Character.codePointAt(value, index)
            if (
                !(
                    allowLineBreaks &&
                        (codePoint == '\n'.code || codePoint == '\t'.code)
                ) &&
                (
                    Character.isISOControl(codePoint) ||
                        codePoint == 0x061c ||
                        codePoint == 0x200e ||
                        codePoint == 0x200f ||
                        codePoint in 0x202a..0x202e ||
                        codePoint in 0x2066..0x2069
                )
            ) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }

    private const val MAX_ENTRIES = 16
    private const val MAX_CONFLICTS = 64
    private const val MAX_CHANGE_COUNT = 10_000
    private const val MAX_PROJECT_BYTES = 128
    private const val MAPPING_ID_BYTES = 32
    private const val MAX_MESSAGE_BYTES = 1024
    private const val MAX_PATH_BYTES = 4 * 1024
    private const val CHECKSUM_BYTES = 8
    private val MAGIC = "APSH0001".toByteArray(StandardCharsets.US_ASCII)
    private val MIN_ENCODED_BYTES = MAGIC.size + 4 + CHECKSUM_BYTES
}

internal class ProjectSyncHistoryStore(private val file: File) {
    fun load(): List<ProjectSyncHistoryEntry> {
        if (!file.exists()) {
            return emptyList()
        }
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Project synchronization history is not a regular file"
        }
        check(file.length() in 1..MAX_HISTORY_ENCODED_BYTES.toLong()) {
            "Project synchronization history is oversized"
        }
        return ProjectSyncHistoryCodec.decode(AtomicFile(file).openRead().use { it.readBytes() })
    }

    fun append(entry: ProjectSyncHistoryEntry): List<ProjectSyncHistoryEntry> {
        val current = load()
        val retained =
            ArrayList<ProjectSyncHistoryEntry>(minOf(16, current.size + 1)).apply {
                val first = maxOf(0, current.size - 15)
                for (index in first until current.size) {
                    add(current[index])
                }
                add(entry)
            }
        persist(retained)
        return retained
    }

    private fun persist(entries: List<ProjectSyncHistoryEntry>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(ProjectSyncHistoryCodec.encode(entries))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
