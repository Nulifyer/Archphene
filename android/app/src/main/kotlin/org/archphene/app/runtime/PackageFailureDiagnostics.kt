package org.archphene.app.runtime

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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
