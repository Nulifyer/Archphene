package org.archphene.app.runtime

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException

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

internal data class PlannedPackageRemoval(
    val name: String,
    val version: String,
)

internal class PackageReplacementReviewRequiredException(
    val removals: List<PlannedPackageRemoval>,
) : IllegalStateException(
        removals.joinToString(
            prefix = "would remove ",
            separator = ", ",
        ) { removal -> "${removal.name} ${removal.version}" },
) {
    init {
        require(removals.isNotEmpty() && removals.size <= 48)
    }
}

internal object PackageInstallPlanCodec {
    private val packageName = Regex("[A-Za-z0-9@+._-]{1,128}")

    fun decode(bytes: ByteArray): List<PlannedPackageRemoval> {
        require(bytes.isNotEmpty() && bytes.size <= 16 * 1024)
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        require(text.endsWith('\n'))
        val lines = text.dropLast(1).split('\n')
        require(lines.firstOrNull() == "org.archphene.package-install-plan.v1")
        val summary = lines.getOrNull(1)?.split('\t').orEmpty()
        val count = summary.getOrNull(1)?.toIntOrNull()
        require(
            summary.size == 2 &&
                summary[0] == "removals" &&
                count != null &&
                count in 0..48 &&
                lines.size == count + 2,
        )
        val removals = ArrayList<PlannedPackageRemoval>(count)
        lines.drop(2).forEach { line ->
            val fields = line.split('\t')
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
        return removals
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
            error is PackageReplacementReviewRequiredException ->
                "Replacement confirmation is required: $detail. " +
                    "No Linux packages were changed."
            error is InsufficientPackageStorageException ->
                "Not enough Linux storage: " +
                    "${formatStorageBytes(error.requiredBytes)} is required and " +
                    "${formatStorageBytes(error.availableBytes)} is available. " +
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

    private fun formatStorageBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KiB"
            bytes < 1024L * 1024 * 1024 ->
                "${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MiB"
            else ->
                "${(bytes + 1024L * 1024 * 1024 - 1) / (1024L * 1024 * 1024)} GiB"
        }
}
