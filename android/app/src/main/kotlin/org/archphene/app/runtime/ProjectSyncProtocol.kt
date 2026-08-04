package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.archphene.app.putTabSeparatedUtf8

internal const val SYNC_PLAN_HEADER_BYTES = 136
internal const val SYNC_KIND_ABSENT = 0
internal const val SYNC_KIND_DIRECTORY = 1
internal const val SYNC_KIND_FILE = 2
internal const val SYNC_ACTION_CONVERGED = 0
internal const val SYNC_ACTION_PUSH_ANDROID = 1
internal const val SYNC_ACTION_PULL_LINUX = 2
internal const val SYNC_ACTION_DELETE_ANDROID = 3
internal const val SYNC_ACTION_DELETE_LINUX = 4
internal const val SYNC_ACTION_CONFLICT = 5
internal const val SYNC_LOCAL_OPEN_FILE = 1
internal const val SYNC_LOCAL_PULL_FILE = 2
internal const val SYNC_LOCAL_DELETE = 3
internal const val SYNC_LOCAL_CREATE_DIRECTORY = 4
internal const val SYNC_LOCAL_PRESERVE_CONFLICT = 5

private const val MAX_PATH_BYTES = 4 * 1024
private const val MAX_DEPTH = 64
private const val MAX_NAME_BYTES = 255
private const val MAX_PROVIDER_DOCUMENT_ID_BYTES = 4 * 1024
private const val MAX_PROVIDER_MIME_BYTES = 255
private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
private val PLAN_MAGIC = "ASPE0001".toByteArray(StandardCharsets.US_ASCII)

internal data class ProjectSyncFingerprint(
    val kind: Int,
    val bytes: Long,
    val sha256: ByteArray,
) {
    fun encode(): String =
        when (kind) {
            SYNC_KIND_DIRECTORY -> "d"
            SYNC_KIND_FILE -> "f:$bytes:${sha256.toHex()}"
            else -> "n"
        }
}

internal data class ProjectSyncPlanEntry(
    val path: String,
    val action: Int,
    val baseline: ProjectSyncFingerprint?,
    val linux: ProjectSyncFingerprint?,
    val android: ProjectSyncFingerprint?,
)

internal class ProjectSyncFingerprintMismatch :
    IllegalStateException("Android project file changed during synchronization")

internal fun decodeProjectSyncPlanEntry(
    source: ByteBuffer,
    length: Int,
): ProjectSyncPlanEntry {
    check(length in SYNC_PLAN_HEADER_BYTES..source.capacity()) {
        "Native synchronization plan entry is not bounded"
    }
    val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    input.clear()
    input.limit(length)
    val magic = ByteArray(8)
    input.get(magic)
    check(magic.contentEquals(PLAN_MAGIC)) {
        "Native synchronization plan entry has invalid magic"
    }
    val action = input.get(8).toInt() and 0xff
    check(action in SYNC_ACTION_CONVERGED..SYNC_ACTION_CONFLICT) {
        "Native synchronization action is invalid"
    }
    val pathLength = input.getInt(12)
    check(pathLength in 1..MAX_PATH_BYTES) {
        "Native synchronization path length is invalid"
    }
    check(length == SYNC_PLAN_HEADER_BYTES + pathLength) {
        "Native synchronization plan entry length differs"
    }
    val baseline = decodeProjectSyncFingerprint(input, input.get(9).toInt() and 0xff, 16)
    val linux = decodeProjectSyncFingerprint(input, input.get(10).toInt() and 0xff, 56)
    val android = decodeProjectSyncFingerprint(input, input.get(11).toInt() and 0xff, 96)
    val pathBytes = ByteArray(pathLength)
    input.position(SYNC_PLAN_HEADER_BYTES)
    input.get(pathBytes)
    val path =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(pathBytes))
            .toString()
    check(safeProjectSyncPath(path)) {
        "Native synchronization path is unsafe"
    }
    return ProjectSyncPlanEntry(path, action, baseline, linux, android)
}

internal fun decodeProjectSyncFingerprintText(value: String): ProjectSyncFingerprint {
    if (value == "d") {
        return ProjectSyncFingerprint(SYNC_KIND_DIRECTORY, 0, ByteArray(32))
    }
    val sizeEnd = value.indexOf(':', 2)
    check(
        value.length >= 2 &&
            value[0] == 'f' &&
            value[1] == ':' &&
            sizeEnd >= 2 &&
            value.indexOf(':', sizeEnd + 1) < 0,
    ) {
        "Project synchronization journal fingerprint is invalid"
    }
    val bytes =
        value.substring(2, sizeEnd).toLongOrNull()?.takeIf { it in 0..MAX_FILE_BYTES }
            ?: error("Project synchronization journal size is invalid")
    val digestStart = sizeEnd + 1
    check(value.length - digestStart == 64) {
        "Project synchronization journal digest is invalid"
    }
    val sha256 = ByteArray(32)
    repeat(32) { index ->
        val high = value[digestStart + index * 2]
        val low = value[digestStart + index * 2 + 1]
        check((high in '0'..'9' || high in 'a'..'f') && (low in '0'..'9' || low in 'a'..'f')) {
            "Project synchronization journal digest is invalid"
        }
        val highNibble = if (high <= '9') high - '0' else high - 'a' + 10
        val lowNibble = if (low <= '9') low - '0' else low - 'a' + 10
        sha256[index] = ((highNibble shl 4) or lowNibble).toByte()
    }
    return ProjectSyncFingerprint(SYNC_KIND_FILE, bytes, sha256)
}

internal fun putProjectSyncRequest(
    destination: ByteBuffer,
    vararg fields: String,
): Int {
    return checkNotNull(
        putTabSeparatedUtf8(
            destination,
            fields,
            NativeRuntime.PROJECT_SYNC_BUFFER_SIZE,
        ),
    ) {
        "Project synchronization request is invalid or too long"
    }
}

private fun decodeProjectSyncFingerprint(
    source: ByteBuffer,
    kind: Int,
    offset: Int,
): ProjectSyncFingerprint? {
    val bytes = source.getLong(offset)
    val sha256 = ByteArray(32)
    val digest = source.duplicate()
    digest.position(offset + 8)
    digest.get(sha256)
    return when (kind) {
        SYNC_KIND_ABSENT -> {
            check(bytes == 0L && sha256.all { it == 0.toByte() }) {
                "Absent synchronization fingerprint has data"
            }
            null
        }
        SYNC_KIND_DIRECTORY -> {
            check(bytes == 0L && sha256.all { it == 0.toByte() }) {
                "Directory synchronization fingerprint has data"
            }
            ProjectSyncFingerprint(kind, 0, sha256)
        }
        SYNC_KIND_FILE -> {
            check(bytes in 0..MAX_FILE_BYTES) {
                "File synchronization fingerprint is oversized"
            }
            ProjectSyncFingerprint(kind, bytes, sha256)
        }
        else -> error("Native synchronization fingerprint kind is invalid")
    }
}

internal fun safeProjectSyncName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        projectSyncUtf8LengthAtMost(name, MAX_NAME_BYTES) &&
        '/' !in name &&
        '\\' !in name &&
        '\u0000' !in name &&
        '\t' !in name &&
        safeProjectSyncCharacters(name)

internal fun safeProjectSyncPath(path: String): Boolean {
    if (path.isEmpty()) {
        return false
    }
    var segmentStart = 0
    var segmentCount = 0
    while (true) {
        val segmentEnd = path.indexOf('/', segmentStart)
        segmentCount++
        if (segmentCount > MAX_DEPTH) {
            return false
        }
        if (segmentEnd < 0) {
            return safeProjectSyncName(path.substring(segmentStart))
        }
        if (!safeProjectSyncName(path.substring(segmentStart, segmentEnd))) {
            return false
        }
        segmentStart = segmentEnd + 1
    }
}

internal fun safeProjectSyncDocumentId(documentId: String): Boolean =
    documentId.isNotEmpty() &&
        projectSyncUtf8LengthAtMost(documentId, MAX_PROVIDER_DOCUMENT_ID_BYTES) &&
        safeProjectSyncCharacters(documentId)

internal fun safeProjectSyncMime(mime: String): Boolean =
    mime.isNotEmpty() &&
        projectSyncUtf8LengthAtMost(mime, MAX_PROVIDER_MIME_BYTES) &&
        safeProjectSyncCharacters(mime)

private fun projectSyncUtf8LengthAtMost(
    value: String,
    maximum: Int,
): Boolean = org.archphene.app.utf8EncodedLength(value, maximum) != null

private fun safeProjectSyncCharacters(value: String): Boolean {
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
            Character.isISOControl(codePoint) ||
            codePoint == 0x061c ||
            codePoint == 0x200e ||
            codePoint == 0x200f ||
            codePoint in 0x202a..0x202e ||
            codePoint in 0x2066..0x2069
        ) {
            return false
        }
        index += Character.charCount(codePoint)
    }
    return true
}

internal fun projectSyncUtf8Length(value: String): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        bytes +=
            when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
        index += Character.charCount(codePoint)
    }
    return bytes
}

private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    val encoded = CharArray(size * 2)
    forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xff
        encoded[index * 2] = alphabet[unsigned ushr 4]
        encoded[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return encoded.concatToString()
}
