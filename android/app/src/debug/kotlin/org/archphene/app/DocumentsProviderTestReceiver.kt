package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

internal class DocumentsProviderTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action != ACTION_RUN &&
            intent.action != ACTION_CLEAN &&
            intent.action != ACTION_CREATE_IMPORT_SOURCE &&
            intent.action != ACTION_CLEAN_IMPORT_SOURCE &&
            intent.action != ACTION_VERIFY_IMPORTS
        ) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token == null || !TOKEN.matches(token)) {
            Log.e(TAG, "Rejected invalid document test token")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    when (intent.action) {
                        ACTION_CLEAN -> {
                            cleanFixture(context, token)
                            Log.i(TAG, "DocumentsProvider cleanup passed token=$token")
                        }
                        ACTION_CREATE_IMPORT_SOURCE -> {
                            val uri = createImportSource(context, token)
                            Log.i(TAG, "Document import source ready token=$token uri=$uri")
                        }
                        ACTION_CLEAN_IMPORT_SOURCE -> {
                            cleanImportSource(context, token)
                            Log.i(TAG, "Document import cleanup passed token=$token")
                        }
                        ACTION_VERIFY_IMPORTS -> {
                            verifyImports(context, token)
                            Log.i(TAG, "Document imports verified token=$token")
                        }
                        else -> {
                            runProbe(
                                context,
                                token,
                                intent.getBooleanExtra(EXTRA_RETAIN_VISUAL, false),
                            )
                            Log.i(TAG, "DocumentsProvider probe passed token=$token")
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "DocumentsProvider probe failed token=$token", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchpheneDocumentProbe",
        ).start()
    }

    private fun runProbe(
        context: Context,
        token: String,
        retainVisual: Boolean,
    ) {
        cleanFixture(context, token)
        val resolver = context.contentResolver
        val authority = "${context.packageName}.documents"
        val root = DocumentsContract.buildDocumentUri(authority, HOME_ID)
        val shellStartup =
            DocumentsContract.buildDocumentUri(authority, SHELL_STARTUP_ID)
        val bashrc =
            DocumentsContract.buildDocumentUri(authority, BASHRC_DOCUMENT_ID)
        val directoryName = "$PROBE_PREFIX$token"
        check(DocumentsContract.isChildDocument(resolver, root, shellStartup)) {
            "shell startup directory is not a child of Archphene Home"
        }
        check(DocumentsContract.isChildDocument(resolver, shellStartup, bashrc)) {
            ".bashrc is not a child of the shell startup directory"
        }
        val startupNames = mutableSetOf<String>()
        resolver
            .query(
                DocumentsContract.buildChildDocumentsUri(authority, SHELL_STARTUP_ID),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )!!
            .use { children ->
                while (children.moveToNext()) {
                    startupNames += children.getString(0)
                }
            }
        check(
            startupNames ==
                setOf(
                    "Edit .bashrc",
                    "Edit .bash_profile",
                ),
        ) {
            "provider exposed an unexpected shell startup set: $startupNames"
        }
        val directBashrc =
            File(context.filesDir, "$HOME_RELATIVE_PATH/.bashrc").readBytes()
        check(
            resolver.openInputStream(bashrc)!!.use { stream ->
                stream.readBytes().contentEquals(directBashrc)
            },
        ) {
            "reviewed .bashrc content does not match the Linux home"
        }
        resolver.openFileDescriptor(bashrc, "rw")!!.close()
        expectNoDocument("startup create") {
            DocumentsContract.createDocument(resolver, shellStartup, "text/plain", "extra")
        }
        expectFailure("startup rename") {
            DocumentsContract.renameDocument(resolver, bashrc, "renamed")
        }
        expectFailure("startup delete") {
            DocumentsContract.deleteDocument(resolver, bashrc)
        }
        expectUnavailable("unreviewed startup alias") {
            resolver.query(
                DocumentsContract.buildDocumentUri(
                    authority,
                    "$SHELL_STARTUP_ID/secret",
                ),
                null,
                null,
                null,
                null,
            )
        }
        expectNoDocument("traversing create") {
            DocumentsContract.createDocument(
                resolver,
                root,
                "text/plain",
                "../escape",
            )
        }
        expectNoDocument("bidirectional filename control") {
            DocumentsContract.createDocument(
                resolver,
                root,
                "text/plain",
                "spoof\u202etxt",
            )
        }
        val directory =
            DocumentsContract.createDocument(
                resolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                directoryName,
            ) ?: error("provider did not create the probe directory")
        var renamed: Uri? = null
        var collision: Uri? = null
        val symlink =
            File(
                context.filesDir,
                "$HOME_RELATIVE_PATH/$directoryName/$SYMLINK_NAME",
            )
        try {
            val document =
                DocumentsContract.createDocument(
                    resolver,
                    directory,
                    "text/plain",
                    SOURCE_NAME,
                ) ?: error("provider did not create the probe document")
            resolver.openOutputStream(document, "wt")!!.use { output ->
                output.write(MARKER)
            }
            check(resolver.openInputStream(document)!!.use { it.readBytes() }.contentEquals(MARKER)) {
                "provider read/write content mismatch"
            }
            check(DocumentsContract.isChildDocument(resolver, directory, document)) {
                "provider did not report its child relationship"
            }

            val renamedDocument =
                DocumentsContract.renameDocument(resolver, document, RENAMED_NAME)
                    ?: error("provider did not return the renamed document")
            renamed = renamedDocument
            val collisionDocument =
                DocumentsContract.createDocument(
                    resolver,
                    directory,
                    "text/plain",
                    COLLISION_NAME,
                ) ?: error("provider did not create the collision document")
            collision = collisionDocument
            resolver.openOutputStream(collisionDocument, "wt")!!.use { output ->
                output.write(COLLISION_MARKER)
            }
            expectFailure("rename replacement") {
                DocumentsContract.renameDocument(
                    resolver,
                    renamedDocument,
                    COLLISION_NAME,
                )
            }
            check(
                resolver
                    .openInputStream(renamedDocument)!!
                    .use { it.readBytes() }
                    .contentEquals(MARKER),
            ) {
                "failed rename changed the source"
            }
            check(
                resolver
                    .openInputStream(collisionDocument)
                    .use { it!!.readBytes() }
                    .contentEquals(COLLISION_MARKER),
            ) {
                "failed rename replaced the destination"
            }

            Os.symlink("../.bashrc", symlink.absolutePath)
            expectUnavailable("hidden file") {
                resolver
                    .query(
                        DocumentsContract.buildDocumentUri(authority, "home/.bashrc"),
                        null,
                        null,
                        null,
                        null,
                    )
            }
            expectUnavailable("path traversal") {
                resolver
                    .query(
                        DocumentsContract.buildDocumentUri(
                            authority,
                            "home/../etc/passwd",
                        ),
                        null,
                        null,
                        null,
                        null,
                    )
            }
            expectUnavailable("symlink access") {
                resolver
                    .openInputStream(
                        DocumentsContract.buildDocumentUri(
                            authority,
                            "home/$directoryName/$SYMLINK_NAME",
                        ),
                    )
            }
            val children =
                resolver.query(
                    DocumentsContract.buildChildDocumentsUri(
                        authority,
                        "home/$directoryName",
                    ),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                ) ?: error("provider returned no child cursor")
            children.use {
                while (it.moveToNext()) {
                    check(it.getString(0) != SYMLINK_NAME) {
                        "provider enumerated a symbolic link"
                    }
                }
            }

            check(DocumentsContract.deleteDocument(resolver, collisionDocument)) {
                "provider did not delete the collision document"
            }
            collision = null
            check(DocumentsContract.deleteDocument(resolver, renamedDocument)) {
                "provider did not delete the renamed document"
            }
            renamed = null
            check(symlink.delete()) { "could not remove the probe symbolic link" }
            if (retainVisual) {
                val visual =
                    DocumentsContract.createDocument(
                        resolver,
                        directory,
                        "text/plain",
                        VISUAL_NAME,
                    ) ?: error("provider did not create the visual fixture")
                resolver.openOutputStream(visual, "wt")!!.use { output ->
                    output.write(VISUAL_MARKER)
                }
            } else {
                check(DocumentsContract.deleteDocument(resolver, directory)) {
                    "provider did not delete the probe directory"
                }
            }
        } finally {
            if (symlink.exists() || symlink.isSymbolicLink()) {
                symlink.delete()
            }
            collision?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            renamed?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }

    private fun cleanFixture(
        context: Context,
        token: String,
    ) {
        val directory = File(context.filesDir, "$HOME_RELATIVE_PATH/$PROBE_PREFIX$token")
        for (name in arrayOf(SOURCE_NAME, RENAMED_NAME, COLLISION_NAME, SYMLINK_NAME, VISUAL_NAME)) {
            val entry = File(directory, name)
            if (entry.exists() || entry.isSymbolicLink()) {
                check(entry.delete()) { "could not delete stale fixture $name" }
            }
        }
        if (directory.exists()) {
            check(directory.delete()) { "could not delete stale probe directory" }
        }
    }

    private fun createImportSource(
        context: Context,
        token: String,
    ): Uri {
        cleanImportSource(context, token)
        val directory =
            File(context.filesDir, "$HOME_RELATIVE_PATH/$IMPORT_SOURCE_PREFIX$token")
        check(directory.mkdir()) { "could not create import source directory" }
        val source = File(directory, importName(token))
        source.outputStream().buffered().use { output ->
            repeat(IMPORT_MARKER_REPETITIONS) {
                output.write(IMPORT_MARKER)
            }
        }
        val authority = "${context.packageName}.documents"
        return DocumentsContract.buildDocumentUri(
            authority,
            "home/$IMPORT_SOURCE_PREFIX$token/${importName(token)}",
        )
    }

    private fun cleanImportSource(
        context: Context,
        token: String,
    ) {
        val home = File(context.filesDir, HOME_RELATIVE_PATH)
        val sourceDirectory = File(home, "$IMPORT_SOURCE_PREFIX$token")
        val source = File(sourceDirectory, importName(token))
        if (source.exists()) {
            check(source.delete()) { "could not delete import source" }
        }
        if (sourceDirectory.exists()) {
            check(sourceDirectory.delete()) { "could not delete import source directory" }
        }
        val downloads = File(home, "Downloads")
        for (
            name in
            arrayOf(
                importName(token),
                importCollisionName(token),
                importThirdName(token),
            )
        ) {
            val imported = File(downloads, name)
            if (imported.exists()) {
                check(imported.delete()) { "could not delete imported file $name" }
            }
        }
        context.getSharedPreferences("storage", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun verifyImports(
        context: Context,
        token: String,
    ) {
        val downloads = File(context.filesDir, "$HOME_RELATIVE_PATH/Downloads")
        for (name in arrayOf(importName(token), importCollisionName(token))) {
            File(downloads, name).inputStream().buffered().use { input ->
                val buffer = ByteArray(IMPORT_MARKER.size)
                repeat(IMPORT_MARKER_REPETITIONS) {
                    var offset = 0
                    while (offset < buffer.size) {
                        val count = input.read(buffer, offset, buffer.size - offset)
                        check(count > 0) { "imported file ended early: $name" }
                        offset += count
                    }
                    check(buffer.contentEquals(IMPORT_MARKER)) {
                        "imported file content differs: $name"
                    }
                }
                check(input.read() == -1) { "imported file has trailing content: $name" }
            }
        }
        check(!File(downloads, importThirdName(token)).exists()) {
            "incoming import intent was replayed"
        }
    }

    private fun File.isSymbolicLink(): Boolean =
        runCatching { android.system.Os.readlink(absolutePath) }.isSuccess

    private fun expectFailure(
        label: String,
        operation: () -> Unit,
    ) {
        check(runCatching(operation).isFailure) { "provider accepted $label" }
    }

    private fun expectUnavailable(
        label: String,
        operation: () -> AutoCloseable?,
    ) {
        val result = runCatching(operation)
        val resource = result.getOrNull()
        resource?.close()
        check(result.isFailure || resource == null) { "provider accepted $label" }
    }

    private fun expectNoDocument(
        label: String,
        operation: () -> Uri?,
    ) {
        val result = runCatching(operation)
        check(result.isFailure || result.getOrNull() == null) {
            "provider accepted $label"
        }
    }

    private companion object {
        private const val TAG = "ArchpheneDocumentsTest"
        private const val ACTION_RUN =
            "org.archphene.app.debug.action.RUN_DOCUMENTS_PROVIDER_TEST"
        private const val ACTION_CLEAN =
            "org.archphene.app.debug.action.CLEAN_DOCUMENTS_PROVIDER_TEST"
        private const val ACTION_CREATE_IMPORT_SOURCE =
            "org.archphene.app.debug.action.CREATE_DOCUMENT_IMPORT_SOURCE"
        private const val ACTION_CLEAN_IMPORT_SOURCE =
            "org.archphene.app.debug.action.CLEAN_DOCUMENT_IMPORT_SOURCE"
        private const val ACTION_VERIFY_IMPORTS =
            "org.archphene.app.debug.action.VERIFY_DOCUMENT_IMPORTS"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_RETAIN_VISUAL = "retain_visual"
        private const val HOME_ID = "home"
        private const val SHELL_STARTUP_ID = "shell-startup"
        private const val BASHRC_DOCUMENT_ID = "$SHELL_STARTUP_ID/bashrc"
        private const val HOME_RELATIVE_PATH = "arch-root/home/archphene"
        private const val PROBE_PREFIX = "Archphene-Documents-"
        private const val SOURCE_NAME = "source.txt"
        private const val RENAMED_NAME = "renamed.txt"
        private const val COLLISION_NAME = "existing.txt"
        private const val SYMLINK_NAME = "private-link"
        private const val VISUAL_NAME = "Welcome.txt"
        private const val IMPORT_SOURCE_PREFIX = "Archphene-Import-Source-"
        private const val IMPORT_MARKER_REPETITIONS = 2048
        private val TOKEN = Regex("[a-f0-9]{8}")
        private val MARKER = "Archphene provider exact read/write\n".toByteArray(StandardCharsets.UTF_8)
        private val COLLISION_MARKER =
            "Archphene existing destination\n".toByteArray(StandardCharsets.UTF_8)
        private val VISUAL_MARKER =
            "This file is shared from Archphene Home.\n".toByteArray(StandardCharsets.UTF_8)
        private val IMPORT_MARKER =
            "Android-to-Archphene import payload\n".toByteArray(StandardCharsets.UTF_8)

        private fun importName(token: String): String = "Android-$token.txt"

        private fun importCollisionName(token: String): String = "Android-$token (2).txt"

        private fun importThirdName(token: String): String = "Android-$token (3).txt"
    }
}
