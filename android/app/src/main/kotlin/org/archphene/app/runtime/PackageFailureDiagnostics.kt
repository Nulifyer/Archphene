package org.archphene.app.runtime

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException

internal fun boundedPackageJobMessage(message: String): String {
    val output = StringBuilder(minOf(message.length, 192))
    var bytes = 0
    var index = 0
    while (index < message.length && output.length < 192) {
        val character = message[index]
        if (
            Character.isHighSurrogate(character) &&
            (index + 1 >= message.length || !Character.isLowSurrogate(message[index + 1]))
        ) {
            return "Package operation"
        }
        if (Character.isLowSurrogate(character)) return "Package operation"
        val codePoint =
            when (character) {
                '\t', '\r', '\n' -> ' '.code
                else -> Character.codePointAt(message, index)
            }
        val characterCount = Character.charCount(codePoint)
        val encodedBytes =
            when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
        if (bytes + encodedBytes > 192 || output.length + characterCount > 192) break
        output.appendCodePoint(codePoint)
        bytes += encodedBytes
        index += characterCount
    }
    return output.toString().ifEmpty { "Package operation" }
}

internal class InsufficientPackageStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IllegalStateException(
        "package install requires $requiredBytes bytes with $availableBytes bytes available",
    ) {
    init {
        require(requiredBytes > 0L)
        require(availableBytes >= 0L)
        require(availableBytes < requiredBytes)
    }
}

internal class UnsupportedPackageCompatibilityException(
    detail: String,
) : IllegalStateException(detail)

internal class PackageFileConflictException(
    detail: String,
) : IllegalStateException(detail)

internal data class PlannedPackageRemoval(
    val name: String,
    val version: String,
)

internal data class PendingPackageMutation(
    val packageName: String,
    val status: String,
    val install: Boolean,
)

internal object PendingPackageMutationCodec {
    private val packageName = Regex("[A-Za-z0-9@+._-]{1,128}")

    fun decode(bytes: ByteArray): PendingPackageMutation {
        require(bytes.isNotEmpty() && bytes.size <= 16 * 1024)
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val fields = splitMutationFields(text)
        require(
            fields != null &&
                packageName.matches(fields[0]) &&
                fields[2].length in 1..128 &&
                fields[2].none { character ->
                    character.isWhitespace() || character.isISOControl()
                },
        )
        val install =
            when (fields[1]) {
                "install" -> {
                    require(fields.size == 3 || fields[3] == "rollback")
                    true
                }
                "remove" -> {
                    require(fields.size == 3)
                    false
                }
                else -> throw IllegalArgumentException("Invalid pending package mutation operation")
            }
        return PendingPackageMutation(
            packageName = fields[0],
            status = text.substring(fields[0].length + 1),
            install = install,
        )
    }

    private fun splitMutationFields(value: String): List<String>? {
        val fields = ArrayList<String>(4)
        var start = 0
        while (true) {
            val delimiter = value.indexOf('\t', start)
            if (delimiter < 0) {
                fields.add(value.substring(start))
                return fields.takeIf { it.size in 3..4 }
            }
            if (fields.size == 3) return null
            fields.add(value.substring(start, delimiter))
            start = delimiter + 1
        }
    }
}

internal object StorageSizeFormatter {
    fun format(bytes: Long): String {
        require(bytes >= 0L)
        if (bytes < 1024L) {
            return "$bytes B"
        }
        val unitBytes: Long
        val suffix: String
        when {
            bytes < 1024L * 1024L -> {
                unitBytes = 1024L
                suffix = "KiB"
            }
            bytes < 1024L * 1024L * 1024L -> {
                unitBytes = 1024L * 1024L
                suffix = "MiB"
            }
            else -> {
                unitBytes = 1024L * 1024L * 1024L
                suffix = "GiB"
            }
        }
        val whole = bytes / unitBytes
        val remainder = bytes % unitBytes
        if (remainder == 0L) {
            return "$whole $suffix"
        }
        if (whole >= 10L) {
            val rounded = whole + if (remainder >= (unitBytes + 1L) / 2L) 1L else 0L
            return "$rounded $suffix"
        }
        val fractionalTenth =
            (remainder * 10L + unitBytes / 2L) / unitBytes
        if (fractionalTenth == 10L) {
            return "${whole + 1L} $suffix"
        }
        return "$whole.$fractionalTenth $suffix"
    }
}

internal object PackageInstallPlanCodec {
    fun decode(bytes: ByteArray): List<PlannedPackageRemoval> =
        PackageRemovalListCodec.decode(bytes, "org.archphene.package-install-plan.v1")
}

internal object AurPackageInstallPlanCodec {
    private const val HEADER = "org.archphene.aur-install-plan.v1"
    private val headerBytes = HEADER.toByteArray(StandardCharsets.US_ASCII)

    fun isPlan(bytes: ByteArray): Boolean =
        bytes.size > headerBytes.size &&
            headerBytes.indices.all { index -> bytes[index] == headerBytes[index] } &&
            bytes[headerBytes.size] == '\n'.code.toByte()

    fun decode(bytes: ByteArray): List<PlannedPackageRemoval> =
        PackageRemovalListCodec.decode(bytes, HEADER)
}

internal object PackageRemovalPlanCodec {
    fun decode(bytes: ByteArray): List<PlannedPackageRemoval> =
        PackageRemovalListCodec.decode(bytes, "org.archphene.package-removal-plan.v1")
}

private object PackageRemovalListCodec {
    private val packageName = Regex("[A-Za-z0-9@+._-]{1,128}")

    fun decode(
        bytes: ByteArray,
        expectedHeader: String,
    ): List<PlannedPackageRemoval> {
        require(bytes.isNotEmpty() && bytes.size <= 16 * 1024)
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        require(text.endsWith('\n'))
        var offset = 0
        fun nextLine(): String? {
            val end = text.indexOf('\n', offset)
            if (end < 0) return null
            return text.substring(offset, end).also { offset = end + 1 }
        }
        require(nextLine() == expectedHeader)
        val summary = nextLine()?.let { splitExactFields(it, 2) }.orEmpty()
        val count = summary.getOrNull(1)?.toIntOrNull()
        require(
            summary.size == 2 &&
                summary[0] == "removals" &&
                count != null &&
                count in 0..48,
        )
        val removals = ArrayList<PlannedPackageRemoval>(count)
        repeat(count) {
            val fields = nextLine()?.let { splitExactFields(it, 3) }.orEmpty()
            require(
                fields.size == 3 &&
                    fields[0] == "remove" &&
                    packageName.matches(fields[1]) &&
                    fields[2].length in 1..128 &&
                    fields[2].none { character ->
                        character.isWhitespace() || character == '\u0000'
                    } &&
                    removals.none { removal -> removal.name == fields[1] },
            )
            removals += PlannedPackageRemoval(fields[1], fields[2])
        }
        require(offset == text.length)
        return removals
    }

    private fun splitExactFields(
        value: String,
        expectedFields: Int,
    ): List<String>? {
        val fields = ArrayList<String>(expectedFields)
        var start = 0
        while (fields.size < expectedFields) {
            val delimiter = value.indexOf('\t', start)
            if (delimiter < 0) {
                fields.add(value.substring(start))
                return fields.takeIf { it.size == expectedFields }
            }
            fields.add(value.substring(start, delimiter))
            start = delimiter + 1
        }
        return null
    }
}

internal object PackageFailureDiagnostics {
    fun install(
        error: Exception,
        mutationStarted: Boolean,
        installedStateRefreshed: Boolean,
    ): String =
        describe(
            action = "Install",
            error = error,
            mutationStarted = mutationStarted,
            installedStateRefreshed = installedStateRefreshed,
        )

    fun removal(
        error: Exception,
        mutationStarted: Boolean,
        installedStateRefreshed: Boolean,
    ): String =
        describe(
            action = "Removal",
            error = error,
            mutationStarted = mutationStarted,
            installedStateRefreshed = installedStateRefreshed,
        )

    private fun describe(
        action: String,
        error: Exception,
        mutationStarted: Boolean,
        installedStateRefreshed: Boolean,
    ): String {
        if (error is PackageFileConflictException) {
            val detail =
                (error.message ?: "A package file is already owned")
                    .replace('\t', ' ')
                    .replace('\r', ' ')
                    .replace('\n', ' ')
            return "$detail No Linux packages were changed. Review before retrying."
        }
        if (mutationStarted) {
            return if (installedStateRefreshed) {
                "$action did not finish. Installed state was refreshed; Review before continuing."
            } else {
                "$action did not finish and installed state could not be refreshed. " +
                    "Restart Archphene, then Review."
            }
        }

        val detail =
            (error.message ?: error.javaClass.simpleName)
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
        val normalized = detail.lowercase()
        return when {
            error is UnsupportedPackageCompatibilityException ->
                "Unsupported on this device: $detail. No Linux packages were changed."
            error is InsufficientPackageStorageException ->
                "Not enough Linux storage: " +
                    "${StorageSizeFormatter.format(error.requiredBytes)} is required and " +
                    "${StorageSizeFormatter.format(error.availableBytes)} is available. " +
                    "Clear unrelated downloads or free Android storage, then Review."
            normalized.contains("no space left") ||
                normalized.contains("enospc") ||
                normalized.contains("disk quota") ->
                "Not enough Linux storage. Free space, then Review."
            normalized.contains("changed") ||
                normalized.contains("current package before") ->
                "Repository or installed state changed. Review the current package before retrying."
            normalized.contains("catalog") ->
                "Package catalog is unavailable or invalid. Refresh catalogs, then Review."
            error is SecurityException ||
                error is SSLException ||
                normalized.contains("signature") ||
                normalized.contains("keyring") ||
                normalized.contains("trust") ->
                "Package trust verification failed. Refresh catalogs, then Review."
            error is UnknownHostException ||
                error is ConnectException ||
                error is SocketException ||
                error is SocketTimeoutException ->
                "Download failed. Check the connection, then Review."
            else -> "$action failed before package mutation: $detail. Review before retrying."
        }
    }

}
