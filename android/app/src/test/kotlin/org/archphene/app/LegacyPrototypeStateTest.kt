package org.archphene.app

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPrototypeStateTest {
    @Test
    fun acceptsEmptyAndGreenfieldDataDirectories() {
        val data = Files.createTempDirectory("archphene-greenfield").toFile().absoluteFile
        assertNull(LegacyPrototypeState.detectedMarker(data))
        data.resolve("files/arch-root").mkdirs()
        assertNull(LegacyPrototypeState.detectedMarker(data))
        data.deleteRecursively()
    }

    @Test
    fun detectsPrototypeRuntimeAndPreferenceMarkers() {
        val markers =
            listOf(
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
        for (marker in markers) {
            val data = Files.createTempDirectory("archphene-prototype").toFile().absoluteFile
            val candidate = data.resolve(marker)
            requireNotNull(candidate.parentFile).mkdirs()
            if (marker.startsWith("files/")) {
                candidate.mkdirs()
            } else {
                candidate.writeText("legacy")
            }
            assertEquals(marker, LegacyPrototypeState.detectedMarker(data))
            data.deleteRecursively()
        }
    }

    @Test
    fun failsClosedForInvalidDataDirectory() {
        assertEquals(
            "<invalid data directory>",
            LegacyPrototypeState.detectedMarker(java.io.File("relative")),
        )
    }
}
