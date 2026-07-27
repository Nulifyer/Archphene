package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
    val segments = path.split('/')
    check(
        segments.isNotEmpty() &&
            segments.size <= MAX_DEPTH &&
            segments.all(::safeProjectSyncName),
    ) {
        "Native synchronization path is unsafe"
    }
    return ProjectSyncPlanEntry(path, action, baseline, linux, android)
}

internal fun decodeProjectSyncFingerprintText(value: String): ProjectSyncFingerprint {
    if (value == "d") {
        return ProjectSyncFingerprint(SYNC_KIND_DIRECTORY, 0, ByteArray(32))
    }
    val fields = value.split(':')
    check(fields.size == 3 && fields[0] == "f") {
        "Project synchronization journal fingerprint is invalid"
    }
    val bytes =
        fields[1].toLongOrNull()?.takeIf { it in 0..MAX_FILE_BYTES }
            ?: error("Project synchronization journal size is invalid")
    val digest = fields[2]
    check(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Project synchronization journal digest is invalid"
    }
    val sha256 = ByteArray(32)
    repeat(32) { index ->
        sha256[index] = digest.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return ProjectSyncFingerprint(SYNC_KIND_FILE, bytes, sha256)
}

internal fun putProjectSyncRequest(
    destination: ByteBuffer,
    vararg fields: String,
): Int {
    check(fields.isNotEmpty() && fields.none { it.isEmpty() || '\t' in it }) {
        "Project synchronization request is invalid"
    }
    val encoded = fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
    check(encoded.size <= NativeRuntime.PROJECT_SYNC_BUFFER_SIZE) {
        "Project synchronization request is too long"
    }
    destination.clear()
    destination.put(encoded)
    return encoded.size
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
        projectSyncUtf8Length(name) <= MAX_NAME_BYTES &&
        '/' !in name &&
        '\\' !in name &&
        '\u0000' !in name &&
        '\t' !in name &&
        safeProjectSyncCharacters(name)

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
