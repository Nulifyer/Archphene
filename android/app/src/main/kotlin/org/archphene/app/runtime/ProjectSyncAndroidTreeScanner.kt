package org.archphene.app.runtime

import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal data class ProjectSyncRemoteEntry(
    val documentId: String,
    val uri: Uri,
    val mime: String,
    val directory: Boolean,
)

internal class ProjectSyncAndroidTreeScanner(
    private val provider: ProjectSyncProvider,
    private val checkCancellation: () -> Unit,
    private val publishProgress: (entries: Int, bytes: Long) -> Unit,
) {
    private data class Directory(
        val documentId: String,
        val relativePath: String,
    )

    fun scan(
        activeHandle: Long,
        treeUri: Uri,
        request: ByteBuffer,
        output: ByteBuffer,
        ignoredDocumentIds: Set<String> = emptySet(),
    ): LinkedHashMap<String, ProjectSyncRemoteEntry> {
        publishProgress(0, 0)
        val result = LinkedHashMap<String, ProjectSyncRemoteEntry>()
        val progress = Progress()
        scanChildren(
            activeHandle = activeHandle,
            treeUri = treeUri,
            parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
            prefix = "",
            depth = 0,
            projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
            request = request,
            output = output,
            progress = progress,
            ignoredDocumentIds = ignoredDocumentIds,
            result = result,
        )
        return result
    }

    private fun scanChildren(
        activeHandle: Long,
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String,
        depth: Int,
        projection: Array<String>,
        request: ByteBuffer,
        output: ByteBuffer,
        progress: Progress,
        ignoredDocumentIds: Set<String>,
        result: LinkedHashMap<String, ProjectSyncRemoteEntry>,
    ) {
        checkCancellation()
        if (depth > MAX_DEPTH) {
            throw SecurityException("Android project exceeds $MAX_DEPTH levels")
        }
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val directories = ArrayList<Directory>()
        provider.query(childrenUri, projection, "list the Android project directory") { cursor ->
            while (cursor.moveToNext()) {
                checkCancellation()
                val documentId =
                    cursor.getString(0)
                        ?.takeIf(String::isNotEmpty)
                        ?: throw SecurityException("Android provider returned no document ID")
                if (documentId in ignoredDocumentIds) {
                    continue
                }
                progress.entries++
                if (progress.entries > MAX_ENTRIES) {
                    throw SecurityException("Android project exceeds $MAX_ENTRIES entries")
                }
                val name =
                    cursor.getString(1)
                        ?.takeIf(::safeProjectSyncName)
                        ?: throw SecurityException("Android provider returned an unsafe name")
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (projectSyncUtf8Length(relativePath) > MAX_PATH_BYTES) {
                    throw SecurityException("Android project path is too long")
                }
                val mime = cursor.getString(2) ?: "application/octet-stream"
                val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                check(
                    result.put(
                        relativePath,
                        ProjectSyncRemoteEntry(documentId, documentUri, mime, directory),
                    ) == null,
                ) {
                    "Android provider returned a duplicate project path"
                }
                val length = putProjectSyncRequest(request, relativePath)
                output.clear()
                if (directory) {
                    requireSuccess(
                        NativeRuntime.nativeAddProjectSyncAndroidDirectory(
                            activeHandle,
                            request,
                            length,
                            output,
                        ).toLong(),
                        output,
                        "record Android project directory",
                    )
                    directories.add(Directory(documentId, relativePath))
                } else {
                    val expectedBytes =
                        if (cursor.isNull(3) || cursor.getLong(3) < 0) -1L else cursor.getLong(3)
                    provider.open(documentUri, "r", "open an Android project file").use { source ->
                        requireSuccess(
                            NativeRuntime.nativeAddProjectSyncAndroidFile(
                                activeHandle,
                                request,
                                length,
                                source.fd,
                                expectedBytes,
                                output,
                            ).toLong(),
                            output,
                            "fingerprint Android project file",
                        )
                    }
                    if (expectedBytes > 0) {
                        progress.bytes = Math.addExact(progress.bytes, expectedBytes)
                    }
                }
                if (progress.entries % PROGRESS_INTERVAL == 0) {
                    publishProgress(progress.entries, progress.bytes)
                }
            }
        }
        directories.forEach { directory ->
            scanChildren(
                activeHandle,
                treeUri,
                directory.documentId,
                directory.relativePath,
                depth + 1,
                projection,
                request,
                output,
                progress,
                ignoredDocumentIds,
                result,
            )
        }
    }

    private fun requireSuccess(
        result: Long,
        output: ByteBuffer,
        operation: String,
    ) {
        if (result >= 0) {
            return
        }
        var length = 0
        while (length < output.capacity() && output.get(length) != 0.toByte()) {
            length++
        }
        val bytes = ByteArray(length)
        output.position(0)
        output.get(bytes)
        throw IllegalStateException(
            String(bytes, StandardCharsets.UTF_8).ifEmpty { "$operation failed ($result)" },
        )
    }

    private class Progress {
        var entries = 0
        var bytes = 0L
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_DEPTH = 64
        const val MAX_PATH_BYTES = 4 * 1024
        const val PROGRESS_INTERVAL = 25
    }
}
