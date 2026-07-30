package org.archphene.app.launcher

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPortalSaveRecoveryTest {
    @Test
    fun recoversDisplayNameSlotsAndLegacyFlatStaging() {
        val root = Files.createTempDirectory("archphene-save-recovery")
        try {
            val base =
                root.resolve("home/archphene/.cache/archphene/portal-save")
                    .createDirectories()
            val current = base.resolve("7-0123456789abcdef").createDirectories()
            current.resolve("1-fedcba9876543210").createDirectories()
                .resolve("chosen name \u03bb.txt").createFile()
            current.resolve("2-deadbeefcafebabe-old-name.txt").createFile()

            LauncherPortalBridge.recoverStaleSaves(root.toFile())

            assertTrue(Files.isDirectory(base))
            assertFalse(Files.exists(current))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAStagingSlotWithMoreThanOneFile() {
        val root = Files.createTempDirectory("archphene-save-recovery-hostile")
        try {
            val slot =
                root.resolve(
                    "home/archphene/.cache/archphene/portal-save/" +
                        "8-0123456789abcdef/1-fedcba9876543210",
                ).createDirectories()
            slot.resolve("first.txt").createFile()
            slot.resolve("second.txt").createFile()

            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverStaleSaves(root.toFile())
            }
            assertTrue(Files.exists(slot.resolve("first.txt")))
            assertTrue(Files.exists(slot.resolve("second.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
