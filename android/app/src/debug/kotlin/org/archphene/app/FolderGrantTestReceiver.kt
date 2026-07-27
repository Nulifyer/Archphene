package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

internal class FolderGrantTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action != ACTION_VERIFY &&
            intent.action != ACTION_VERIFY_READ_ONLY &&
            intent.action != ACTION_VERIFY_ABSENT &&
            intent.action != ACTION_DOWNGRADE &&
            intent.action != ACTION_REVOKE &&
            intent.action != ACTION_CLEAN &&
            intent.action != ACTION_PREPARE_MIRROR &&
            intent.action != ACTION_VERIFY_MIRROR &&
            intent.action != ACTION_VERIFY_MIRROR_ABSENT &&
            intent.action != ACTION_CLEAN_MIRROR &&
            intent.action != ACTION_HOLD_SYNC
        ) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token == null || !TOKEN.matches(token)) {
            Log.e(TAG, "Rejected invalid folder grant test token")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    when (intent.action) {
                        ACTION_VERIFY -> verify(context, token)
                        ACTION_VERIFY_READ_ONLY -> verifyReadOnly(context, token)
                        ACTION_VERIFY_ABSENT -> verifyAbsent(context, token)
                        ACTION_DOWNGRADE -> downgrade(context, token)
                        ACTION_REVOKE -> revoke(context, token)
                        ACTION_CLEAN -> clean(context, token)
                        ACTION_PREPARE_MIRROR -> prepareMirror(context, token)
                        ACTION_VERIFY_MIRROR -> verifyMirror(context, token)
                        ACTION_VERIFY_MIRROR_ABSENT -> verifyMirrorAbsent(context, token)
                        ACTION_CLEAN_MIRROR -> cleanMirror(context, token)
                        ACTION_HOLD_SYNC -> holdSync(context, intent)
                    }
                    Log.i(TAG, "Folder grant ${intent.action} passed token=$token")
                } catch (error: Exception) {
                    Log.e(TAG, "Folder grant ${intent.action} failed token=$token", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchpheneFolderGrantProbe",
        ).start()
    }

    private fun verify(
        context: Context,
        token: String,
    ) {
        val matches =
            context.contentResolver.persistedUriPermissions.filter { permission ->
                permission.uri.toString().contains(token)
            }
        check(matches.size == 1) { "expected one matching persisted folder grant" }
        val permission = matches.single()
        check(permission.isReadPermission) { "persisted folder grant is not readable" }
        check(permission.isWritePermission) { "persisted folder grant is not writable" }
        val savedUri =
            context
                .getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
                .getString(FOLDER_URI, null)
        check(savedUri == permission.uri.toString()) {
            "saved folder URI does not match the persisted grant"
        }
    }

    private fun verifyAbsent(
        context: Context,
        token: String,
    ) {
        check(
            context.contentResolver.persistedUriPermissions.none { permission ->
                permission.uri.toString().contains(token)
            },
        ) {
            "matching persisted folder grant remains"
        }
        val savedUri =
            context
                .getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
                .getString(FOLDER_URI, null)
        check(savedUri?.contains(token) != true) { "matching saved folder URI remains" }
    }

    private fun verifyReadOnly(
        context: Context,
        token: String,
    ) {
        val permission =
            context.contentResolver.persistedUriPermissions.singleOrNull { candidate ->
                candidate.uri.toString().contains(token)
            } ?: error("matching persisted folder grant not found")
        check(permission.isReadPermission) { "persisted folder grant is not readable" }
        check(!permission.isWritePermission) { "persisted folder grant remains writable" }
        val savedUri =
            context
                .getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
                .getString(FOLDER_URI, null)
        check(savedUri == permission.uri.toString()) {
            "saved folder URI does not match the read-only grant"
        }
    }

    private fun downgrade(
        context: Context,
        token: String,
    ) {
        val permission =
            context.contentResolver.persistedUriPermissions.singleOrNull { candidate ->
                candidate.uri.toString().contains(token)
            } ?: error("matching persisted folder grant not found")
        check(permission.isReadPermission && permission.isWritePermission) {
            "matching persisted folder grant is not read/write"
        }
        context.contentResolver.releasePersistableUriPermission(
            permission.uri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private fun revoke(
        context: Context,
        token: String,
    ) {
        val permission =
            context.contentResolver.persistedUriPermissions.singleOrNull { candidate ->
                candidate.uri.toString().contains(token)
            } ?: error("matching persisted folder grant not found")
        release(context, permission.uri, permission.isReadPermission, permission.isWritePermission)
    }

    private fun clean(
        context: Context,
        token: String,
    ) {
        context.contentResolver.persistedUriPermissions
            .filter { permission -> permission.uri.toString().contains(token) }
            .forEach { permission ->
                runCatching {
                    release(
                        context,
                        permission.uri,
                        permission.isReadPermission,
                        permission.isWritePermission,
                    )
                }
            }
        val preferences =
            context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(FOLDER_URI, null)?.contains(token) == true) {
            check(
                preferences
                    .edit()
                    .remove(FOLDER_URI)
                    .remove(FOLDER_LABEL)
                    .remove(FOLDER_STATE)
                    .commit(),
            ) {
                "could not clear folder grant test state"
            }
        }
    }

    private fun prepareMirror(
        context: Context,
        token: String,
    ) {
        cleanMirror(context, token)
        val staging = File(projectsRoot(context), STAGING_NAME)
        check(staging.mkdir()) { "could not create stale mirror staging" }
        File(staging, "partial").writeText("stale-$token", StandardCharsets.UTF_8)
    }

    private fun verifyMirror(
        context: Context,
        token: String,
    ) {
        val project = File(projectsRoot(context), mirrorName(token))
        check(project.isDirectory && !Files.isSymbolicLink(project.toPath())) {
            "project mirror is unavailable or symbolic"
        }
        check(File(project, "main.txt").readText(StandardCharsets.UTF_8) == "root-$token") {
            "root mirror content differs"
        }
        check(
            File(project, "src/nested.txt").readText(StandardCharsets.UTF_8) ==
                "nested-$token",
        ) {
            "nested mirror content differs"
        }
        check(
            File(project, ".git/config").readText(StandardCharsets.UTF_8) ==
                "git-$token",
        ) {
            "hidden mirror content differs"
        }
        check(File(project, "empty.bin").length() == 0L) { "empty mirror file differs" }
        check(!File(projectsRoot(context), STAGING_NAME).exists()) {
            "mirror staging remains after publication"
        }
        verifyMirrorBaseline(context, token, project)
    }

    private fun verifyMirrorBaseline(
        context: Context,
        token: String,
        project: File,
    ) {
        val preferences =
            context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
        val mappingId =
            preferences
                .getString(FOLDER_MAPPING_ID, null)
                ?.takeIf(MAPPING_ID::matches)
                ?: error("project mirror mapping identity is unavailable")
        val manifest = File(syncStateRoot(context), "$mappingId.v1")
        check(manifest.isFile && !Files.isSymbolicLink(manifest.toPath())) {
            "project mirror baseline is unavailable or symbolic"
        }
        val encoded = manifest.readBytes()
        check(encoded.size in 36..MAX_MANIFEST_BYTES) { "project mirror baseline is not bounded" }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        input.get(magic)
        check(magic.contentEquals("ARCSYNC1".toByteArray(StandardCharsets.US_ASCII))) {
            "project mirror baseline magic differs"
        }
        check(input.int == 1) { "project mirror baseline version differs" }
        val entryCount = input.int
        check(entryCount == 6) { "project mirror baseline entry count differs" }
        val encodedMapping = ByteArray(16)
        input.get(encodedMapping)
        check(encodedMapping.toHex() == mappingId) {
            "project mirror baseline mapping identity differs"
        }
        val projectLength = input.short.toInt() and 0xffff
        check(input.short.toInt() == 0) { "project mirror baseline header is not canonical" }
        val projectName = input.takeUtf8(projectLength)
        check(projectName == mirrorName(token)) { "project mirror baseline name differs" }

        val expectedPaths =
            setOf(".git", ".git/config", "empty.bin", "main.txt", "src", "src/nested.txt")
        val observedPaths = linkedSetOf<String>()
        var previousPath = ""
        repeat(entryCount) {
            check(input.remaining() >= 44) { "project mirror baseline entry is truncated" }
            val pathLength = input.short.toInt() and 0xffff
            val kind = input.get().toInt() and 0xff
            check(input.get().toInt() == 0) { "project mirror baseline entry is not canonical" }
            val bytes = input.long
            val sha256 = ByteArray(32)
            input.get(sha256)
            val relativePath = input.takeUtf8(pathLength)
            check(relativePath > previousPath) { "project mirror baseline is not sorted" }
            check(relativePath in expectedPaths && observedPaths.add(relativePath)) {
                "project mirror baseline path differs"
            }
            previousPath = relativePath
            val local = File(project, relativePath)
            check(!Files.isSymbolicLink(local.toPath())) { "baseline path is symbolic" }
            if (local.isDirectory) {
                check(kind == 1 && bytes == 0L && sha256.all { it == 0.toByte() }) {
                    "directory baseline differs"
                }
            } else {
                check(local.isFile && kind == 2 && bytes == local.length()) {
                    "file baseline metadata differs"
                }
                check(
                    sha256.contentEquals(
                        MessageDigest.getInstance("SHA-256").digest(local.readBytes()),
                    ),
                ) {
                    "file baseline digest differs"
                }
            }
        }
        check(observedPaths == expectedPaths && !input.hasRemaining()) {
            "project mirror baseline content differs"
        }
    }

    private fun cleanMirror(
        context: Context,
        token: String,
    ) {
        deleteFixture(File(projectsRoot(context), mirrorName(token)))
        deleteFixture(File(projectsRoot(context), STAGING_NAME))
        val preferences =
            context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
        if (
            preferences.getString(FOLDER_MIRROR_NAME, null) == mirrorName(token)
        ) {
            preferences
                .getString(FOLDER_MAPPING_ID, null)
                ?.takeIf(MAPPING_ID::matches)
                ?.let { mappingId ->
                    deleteFixture(File(syncStateRoot(context), "$mappingId.v1"))
                    deleteFixture(File(syncStateRoot(context), ".$mappingId.v1.tmp"))
                }
            check(
                preferences
                    .edit()
                    .remove(FOLDER_MIRROR_URI)
                    .remove(FOLDER_MIRROR_NAME)
                    .remove(FOLDER_MAPPING_ID)
                    .commit(),
            ) {
                "could not clear mirror test state"
            }
        }
        context
            .getSharedPreferences(SYNC_TEST_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AtomicFile(File(context.filesDir, SYNC_JOURNAL_FILE)).delete()
    }

    private fun holdSync(
        context: Context,
        intent: Intent,
    ) {
        val phase =
            intent.getStringExtra(EXTRA_PHASE)
                ?.takeIf { it == SYNC_PHASE_BACKED_UP || it == SYNC_PHASE_COMMITTED }
                ?: error("project sync hold phase is invalid")
        val holdMillis = intent.getLongExtra(EXTRA_HOLD_MILLIS, 0)
        check(holdMillis in 5_000L..30_000L) { "project sync hold duration is invalid" }
        check(
            context
                .getSharedPreferences(SYNC_TEST_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(SYNC_TEST_PHASE, phase)
                .putLong(SYNC_TEST_HOLD_MILLIS, holdMillis)
                .commit(),
        ) {
            "could not save project sync hold"
        }
    }

    private fun verifyMirrorAbsent(
        context: Context,
        token: String,
    ) {
        check(!File(projectsRoot(context), mirrorName(token)).exists()) {
            "cancelled project mirror was published"
        }
        check(!File(projectsRoot(context), STAGING_NAME).exists()) {
            "cancelled project mirror retained staging"
        }
    }

    private fun projectsRoot(context: Context): File =
        File(context.filesDir, "arch-root/home/archphene/Projects")

    private fun syncStateRoot(context: Context): File =
        File(context.filesDir, "arch-root/var/lib/archphene/storage")

    private fun ByteBuffer.takeUtf8(length: Int): String {
        check(length in 1..remaining()) { "project mirror baseline string is invalid" }
        val bytes = ByteArray(length)
        get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
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

    private fun deleteFixture(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) {
            return
        }
        check(!Files.isSymbolicLink(file.toPath())) { "refusing to clean symbolic test fixture" }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteFixture)
                ?: error("could not list mirror test fixture")
        }
        check(file.delete()) { "could not delete mirror test fixture" }
    }

    private fun release(
        context: Context,
        uri: Uri,
        readable: Boolean,
        writable: Boolean,
    ) {
        var flags = 0
        if (readable) {
            flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (writable) {
            flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        check(flags != 0) { "persisted folder grant has no access flags" }
        context.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    private companion object {
        private const val TAG = "ArchpheneFolderGrantTest"
        private const val ACTION_VERIFY =
            "org.archphene.app.debug.action.VERIFY_FOLDER_GRANT"
        private const val ACTION_VERIFY_READ_ONLY =
            "org.archphene.app.debug.action.VERIFY_FOLDER_GRANT_READ_ONLY"
        private const val ACTION_VERIFY_ABSENT =
            "org.archphene.app.debug.action.VERIFY_FOLDER_GRANT_ABSENT"
        private const val ACTION_DOWNGRADE =
            "org.archphene.app.debug.action.DOWNGRADE_FOLDER_GRANT"
        private const val ACTION_REVOKE =
            "org.archphene.app.debug.action.REVOKE_FOLDER_GRANT"
        private const val ACTION_CLEAN =
            "org.archphene.app.debug.action.CLEAN_FOLDER_GRANT"
        private const val ACTION_PREPARE_MIRROR =
            "org.archphene.app.debug.action.PREPARE_FOLDER_MIRROR"
        private const val ACTION_VERIFY_MIRROR =
            "org.archphene.app.debug.action.VERIFY_FOLDER_MIRROR"
        private const val ACTION_VERIFY_MIRROR_ABSENT =
            "org.archphene.app.debug.action.VERIFY_FOLDER_MIRROR_ABSENT"
        private const val ACTION_CLEAN_MIRROR =
            "org.archphene.app.debug.action.CLEAN_FOLDER_MIRROR"
        private const val ACTION_HOLD_SYNC =
            "org.archphene.app.debug.action.HOLD_PROJECT_SYNC"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_HOLD_MILLIS = "holdMillis"
        private const val STORAGE_PREFERENCES = "storage"
        private const val FOLDER_URI = "folder_tree_uri"
        private const val FOLDER_LABEL = "folder_label"
        private const val FOLDER_STATE = "folder_state"
        private const val FOLDER_MIRROR_URI = "folder_mirror_uri"
        private const val FOLDER_MIRROR_NAME = "folder_mirror_name"
        private const val FOLDER_MAPPING_ID = "folder_mapping_id"
        private const val STAGING_NAME = ".archphene-mirror-pending"
        private const val SYNC_TEST_PREFERENCES = "project_sync_test"
        private const val SYNC_TEST_PHASE = "hold_phase"
        private const val SYNC_TEST_HOLD_MILLIS = "hold_ms"
        private const val SYNC_PHASE_BACKED_UP = "backed-up"
        private const val SYNC_PHASE_COMMITTED = "committed"
        private const val SYNC_JOURNAL_FILE = "project-sync-journal-v1"
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private val TOKEN = Regex("[a-f0-9]{8}")
        private val MAPPING_ID = Regex("[a-f0-9]{32}")

        private fun mirrorName(token: String): String = "Archphene-Mirror-$token"
    }
}
