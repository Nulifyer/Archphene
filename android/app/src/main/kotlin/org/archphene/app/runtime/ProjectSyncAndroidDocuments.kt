package org.archphene.app.runtime

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal class ProjectSyncAndroidDocuments(private val provider: ProjectSyncProvider) {
    fun queryName(uri: Uri): String {
        val name =
            provider.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                "read an Android project document name",
            ) {
                check(it.moveToFirst()) {
                    "Android provider did not return the project document"
                }
                it.getString(0)
            }
        return name
            ?.takeIf(String::isNotEmpty)
            ?: error("Android provider returned no project document name")
    }

    fun verifyFingerprint(
        activeHandle: Long,
        uri: Uri,
        expected: ProjectSyncFingerprint,
        output: ByteBuffer,
    ) {
        verifyFingerprint(activeHandle, uri, expected, output, requireActiveSync = true)
    }

    fun verifyFingerprintAfterCommit(
        activeHandle: Long,
        uri: Uri,
        expected: ProjectSyncFingerprint,
        output: ByteBuffer,
    ) {
        verifyFingerprint(activeHandle, uri, expected, output, requireActiveSync = false)
    }

    private fun verifyFingerprint(
        activeHandle: Long,
        uri: Uri,
        expected: ProjectSyncFingerprint,
        output: ByteBuffer,
        requireActiveSync: Boolean,
    ) {
        provider.open(uri, "r", "open an Android project file for verification").use { source ->
            output.clear()
            val length =
                if (requireActiveSync) {
                    NativeRuntime.nativeFingerprintProjectSyncFile(
                        activeHandle,
                        source.fd,
                        -1,
                        output,
                    )
                } else {
                    NativeRuntime.nativeFingerprintFile(
                        activeHandle,
                        source.fd,
                        -1,
                        output,
                    )
                }
            requireSuccess(length.toLong(), output, "verify Android project file")
            if (readCString(output) != expected.encode()) {
                throw ProjectSyncFingerprintMismatch()
            }
        }
    }

    fun findChild(
        parentUri: Uri,
        name: String,
    ): ProjectSyncRemoteEntry? {
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri),
            )
        return provider.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            "list Android project recovery documents",
        ) {
            var match: ProjectSyncRemoteEntry? = null
            while (it.moveToNext()) {
                if (it.getString(1) != name) continue
                check(match == null) { "Android provider returned duplicate recovery names" }
                val id = it.getString(0) ?: error("Android recovery document has no ID")
                val mime = it.getString(2) ?: "application/octet-stream"
                match =
                    ProjectSyncRemoteEntry(
                        id,
                        DocumentsContract.buildDocumentUriUsingTree(parentUri, id),
                        mime,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
            }
            match
        }
    }

    private fun requireSuccess(
        result: Long,
        output: ByteBuffer,
        operation: String,
    ) {
        if (result < 0) {
            throw IllegalStateException(readCString(output).ifEmpty { "$operation failed ($result)" })
        }
    }

    private fun readCString(buffer: ByteBuffer): String {
        var length = 0
        while (length < buffer.capacity() && buffer.get(length) != 0.toByte()) {
            length++
        }
        val bytes = ByteArray(length)
        buffer.position(0)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
