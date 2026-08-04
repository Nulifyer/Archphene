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
        val container = Files.createTempDirectory("archphene-save-recovery")
        val root = container.resolve("arch-root").createDirectories()
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
            val recovered = container.resolve("portal-save-recovery").toFile().listFiles()
            assertTrue(recovered != null && recovered.size == 2)
        } finally {
            container.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAStagingSlotWithMoreThanOneFile() {
        val container = Files.createTempDirectory("archphene-save-recovery-hostile")
        val root = container.resolve("arch-root").createDirectories()
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
            container.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsStaleSaveDirectoryOverflowBeforeRecovery() {
        val container = Files.createTempDirectory("archphene-save-directory-overflow")
        val root = container.resolve("arch-root").createDirectories()
        try {
            val base =
                root.resolve("home/archphene/.cache/archphene/portal-save")
                    .createDirectories()
            repeat(129) { index ->
                base.resolve("${index + 1}-0123456789abcdef").createDirectories()
            }

            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverStaleSaves(root.toFile())
            }
            assertTrue(Files.isDirectory(base.resolve("1-0123456789abcdef")))
            assertTrue(Files.isDirectory(base.resolve("129-0123456789abcdef")))
        } finally {
            container.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsStaleSaveEntryOverflowBeforeRecovery() {
        val container = Files.createTempDirectory("archphene-save-entry-overflow")
        val root = container.resolve("arch-root").createDirectories()
        try {
            val directory =
                root.resolve(
                    "home/archphene/.cache/archphene/portal-save/" +
                        "9-0123456789abcdef",
                ).createDirectories()
            repeat(9) { index ->
                directory.resolve("${index + 1}-fedcba9876543210-$index.txt").createFile()
            }

            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverStaleSaves(root.toFile())
            }
            repeat(9) { index ->
                assertTrue(
                    Files.isRegularFile(
                        directory.resolve("${index + 1}-fedcba9876543210-$index.txt"),
                    ),
                )
            }
        } finally {
            container.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsStaleRuntimeEntryOverflowBeforeCleanup() {
        val cache = Files.createTempDirectory("archphene-runtime-entry-overflow")
        try {
            val runtime = cache.resolve("p1-0123456789abcdef").createDirectories()
            repeat(5) { index -> runtime.resolve("entry-$index").createFile() }

            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverStaleRuntime(cache.toFile())
            }
            repeat(5) { index ->
                assertTrue(Files.isRegularFile(runtime.resolve("entry-$index")))
            }
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPortalCacheEntryOverflowBeforeCleanup() {
        val cache = Files.createTempDirectory("archphene-runtime-cache-overflow")
        try {
            val runtime = cache.resolve("p1-0123456789abcdef").createDirectories()
            val marker = runtime.resolve("marker").createFile()
            repeat(4_096) { index -> cache.resolve("unrelated-$index").createFile() }

            assertThrows(IllegalStateException::class.java) {
                LauncherPortalBridge.recoverStaleRuntime(cache.toFile())
            }
            assertTrue(Files.isRegularFile(marker))
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
