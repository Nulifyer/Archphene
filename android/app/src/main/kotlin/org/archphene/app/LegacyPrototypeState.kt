package org.archphene.app

import java.io.File

internal object LegacyPrototypeState {
    private val markers =
        arrayOf(
            "files/package-runtime",
            "files/runtime-packs",
            "files/archphene/payloads",
            "shared_prefs/linux-app-manager-state.xml",
            "shared_prefs/linux-app-manager-tracked.xml",
            "shared_prefs/linux-app-manager-repositories.xml",
            "shared_prefs/linux-package-install-jobs-v1.xml",
            "shared_prefs/archphene-managed-packages-v1.xml",
            "shared_prefs/archphene-terminal-request-v2.xml",
            "shared_prefs/archphene-terminal-command-jobs-v1.xml",
        )

    fun detectedMarker(dataDirectory: File): String? {
        if (!dataDirectory.isAbsolute) {
            return "<invalid data directory>"
        }
        for (marker in markers) {
            val candidate = File(dataDirectory, marker)
            val exists = runCatching(candidate::exists).getOrElse { return marker }
            if (exists) {
                return marker
            }
        }
        return null
    }
}
