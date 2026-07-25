package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

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
            intent.action != ACTION_CLEAN
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
        private const val EXTRA_TOKEN = "token"
        private const val STORAGE_PREFERENCES = "storage"
        private const val FOLDER_URI = "folder_tree_uri"
        private const val FOLDER_LABEL = "folder_label"
        private const val FOLDER_STATE = "folder_state"
        private val TOKEN = Regex("[a-f0-9]{8}")
    }
}
