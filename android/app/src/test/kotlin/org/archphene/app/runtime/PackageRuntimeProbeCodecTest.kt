package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageRuntimeProbeCodecTest {
    @Test
    fun findsAndTrimsFirstPacmanVersionLine() {
        assertEquals(
            "Pacman v7.0.0 - libalpm v15.0.0",
            PackageRuntimeProbeCodec.firstPacmanVersion(
                "header\n  Pacman v7.0.0 - libalpm v15.0.0  \nPacman v8",
            ),
        )
    }

    @Test
    fun supportsLfCrLfCrAndFinalUnterminatedLines() {
        for (delimiter in listOf("\n", "\r\n", "\r")) {
            assertEquals(
                "Pacman v7",
                PackageRuntimeProbeCodec.firstPacmanVersion("one${delimiter}Pacman v7${delimiter}three"),
            )
        }
        assertEquals("Pacman v7", PackageRuntimeProbeCodec.firstPacmanVersion("one\nPacman v7"))
    }

    @Test
    fun rejectsEmptyMissingAndCrossLineMarkers() {
        for (output in listOf("", "pacman v7", "Pacman\nv7", "one\ntwo\n")) {
            assertNull(PackageRuntimeProbeCodec.firstPacmanVersion(output))
        }
    }

    @Test
    fun scansExact16KiBLineFloodWithoutConstructingEachLine() {
        val output = "x\n".repeat(8_187) + "Pacman v7\n"

        assertEquals(16 * 1024, output.length)
        assertEquals("Pacman v7", PackageRuntimeProbeCodec.firstPacmanVersion(output))
    }
}
