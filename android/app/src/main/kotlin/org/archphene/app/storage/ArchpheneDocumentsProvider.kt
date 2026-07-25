package org.archphene.app.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import org.archphene.app.R
import org.archphene.app.runtime.NativeRuntime

class ArchpheneDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_ROOT_PROJECTION
        val rows = MatrixCursor(columns, 1)
        val row = rows.newRow()
        put(row, rows, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        put(row, rows, DocumentsContract.Root.COLUMN_DOCUMENT_ID, HOME_ID)
        put(
            row,
            rows,
            DocumentsContract.Root.COLUMN_TITLE,
            providerContext().getString(R.string.documents_root_title),
        )
        put(
            row,
            rows,
            DocumentsContract.Root.COLUMN_SUMMARY,
            providerContext().getString(R.string.documents_root_summary),
        )
        put(
            row,
            rows,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_LOCAL_ONLY or
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
        )
        put(row, rows, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        val home = homeDirectoryOrNull()
        if (home != null) {
            put(row, rows, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, home.usableSpace)
        }
        return rows
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val rows = MatrixCursor(projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION, 1)
        include(rows, documentId, documentForId(documentId))
        return rows
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = documentForId(parentDocumentId)
        if (!parent.attributes.isDirectory) {
            throw missing("Document is not a directory: $parentDocumentId")
        }
        val visibleChildren =
            try {
                Files.newDirectoryStream(parent.file.toPath()).use { entries ->
                    entries
                        .asSequence()
                        .filter { path -> visibleName(path.fileName.toString()) }
                        .filterNot(Files::isSymbolicLink)
                        .take(MAX_VISIBLE_CHILDREN + 1)
                        .map { path -> path.toFile() }
                        .toList()
                }
            } catch (error: Exception) {
                throw missing("Could not list directory: $parentDocumentId", error)
            }
        if (visibleChildren.size > MAX_VISIBLE_CHILDREN) {
            throw missing("Directory exceeds the visible-entry limit")
        }
        val children =
            visibleChildren.sortedWith(
                compareBy<File>({ it.name.lowercase(Locale.ROOT) }, File::getName),
            )
        val rows =
            MatrixCursor(
                projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION,
                children.size,
            )
        children.forEach { child ->
            val childId =
                if (parentDocumentId == HOME_ID) {
                    "$HOME_ID/${child.name}"
                } else {
                    "$parentDocumentId/${child.name}"
                }
            include(rows, childId, documentForId(childId))
        }
        return rows
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val document = documentForId(documentId)
        if (!document.attributes.isRegularFile) {
            throw missing("Document is not a regular file: $documentId")
        }
        val nativeMode = nativeMode(mode)
        val descriptor =
            nativeOperation(listOf(homePath(), documentId)) { request, length, output ->
                NativeRuntime.nativeOpenHomeDocument(
                    request,
                    length,
                    nativeMode,
                    output,
                )
            }
        if (nativeMode and NativeRuntime.STORAGE_MODE_WRITE != 0) {
            notifyDocument(documentId)
        }
        return ParcelFileDescriptor.adoptFd(descriptor)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = documentForId(parentDocumentId)
        if (!parent.attributes.isDirectory) {
            throw missing("Document is not a directory: $parentDocumentId")
        }
        requireVisibleName(displayName)
        nativeOperation(listOf(homePath(), parentDocumentId, displayName)) {
                request,
                length,
                output,
            ->
            NativeRuntime.nativeCreateHomeDocument(
                request,
                length,
                mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                output,
            )
        }
        notifyChildren(parentDocumentId)
        return if (parentDocumentId == HOME_ID) {
            "$HOME_ID/$displayName"
        } else {
            "$parentDocumentId/$displayName"
        }
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        if (documentId == HOME_ID) {
            throw missing("Cannot rename Archphene Home")
        }
        documentForId(documentId)
        requireVisibleName(displayName)
        nativeOperation(listOf(homePath(), documentId, displayName)) {
                request,
                length,
                output,
            ->
            NativeRuntime.nativeRenameHomeDocument(request, length, output)
        }
        val separator = documentId.lastIndexOf('/')
        val parentId = documentId.substring(0, separator)
        val renamedId = "$parentId/$displayName"
        notifyChildren(parentId)
        notifyDocument(renamedId)
        return renamedId
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == HOME_ID) {
            throw missing("Cannot delete Archphene Home")
        }
        documentForId(documentId)
        nativeOperation(listOf(homePath(), documentId)) { request, length, output ->
            NativeRuntime.nativeDeleteHomeDocument(request, length, output)
        }
        notifyChildren(documentId.substringBeforeLast('/'))
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean =
        try {
            documentForId(parentDocumentId)
            documentForId(documentId)
            documentId == parentDocumentId ||
                documentId.startsWith("$parentDocumentId/")
        } catch (_: FileNotFoundException) {
            false
        }

    private fun include(
        rows: MatrixCursor,
        documentId: String,
        document: ResolvedDocument,
    ) {
        val attributes = document.attributes
        if (!attributes.isDirectory && !attributes.isRegularFile) {
            throw missing("Unsupported document type: $documentId")
        }
        var flags =
            if (attributes.isDirectory) {
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            }
        if (documentId != HOME_ID) {
            flags =
                flags or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }
        val row = rows.newRow()
        put(row, rows, DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
        put(
            row,
            rows,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            if (documentId == HOME_ID) {
                providerContext().getString(R.string.documents_home_name)
            } else {
                document.file.name
            },
        )
        put(
            row,
            rows,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            if (attributes.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                mimeType(document.file.name)
            },
        )
        put(row, rows, DocumentsContract.Document.COLUMN_FLAGS, flags)
        if (attributes.isRegularFile) {
            put(row, rows, DocumentsContract.Document.COLUMN_SIZE, attributes.size())
        }
        put(
            row,
            rows,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun documentForId(documentId: String): ResolvedDocument {
        val segments = parseDocumentId(documentId)
        var current = homeDirectory()
        if (Files.isSymbolicLink(current.toPath())) {
            throw missing("Archphene Home is unavailable")
        }
        for (segment in segments) {
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) {
                throw missing("Symbolic links are private")
            }
        }
        val attributes =
            try {
                Files.readAttributes(
                    current.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (error: Exception) {
                throw missing("Document is unavailable: $documentId", error)
            }
        return ResolvedDocument(current, attributes)
    }

    private fun parseDocumentId(documentId: String): List<String> {
        if (documentId.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_ID_BYTES) {
            throw missing("Document ID is too long")
        }
        if (documentId == HOME_ID) {
            return emptyList()
        }
        if (!documentId.startsWith("$HOME_ID/")) {
            throw missing("Unknown document: $documentId")
        }
        val segments = documentId.removePrefix("$HOME_ID/").split('/')
        if (segments.isEmpty() || segments.size > MAX_DOCUMENT_DEPTH) {
            throw missing("Invalid document depth")
        }
        segments.forEach(::requireVisibleName)
        return segments
    }

    private fun requireVisibleName(name: String) {
        if (!visibleName(name)) {
            throw missing("Private or invalid document name")
        }
    }

    private fun visibleName(name: String): Boolean =
        name.isNotEmpty() &&
            name.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_NAME_BYTES &&
            name != "." &&
            name != ".." &&
            !name.startsWith('.') &&
            '/' !in name &&
            '\\' !in name &&
            name.none { character ->
                character.isISOControl() || character.isBidirectionalControl()
            }

    private fun homeDirectory(): File =
        homeDirectoryOrNull()
            ?: throw missing("Open Archphene once before browsing its Linux home")

    private fun homeDirectoryOrNull(): File? {
        val home = File(providerContext().filesDir, HOME_RELATIVE_PATH)
        return try {
            val attributes =
                Files.readAttributes(
                    home.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            home.takeIf { attributes.isDirectory && !Files.isSymbolicLink(home.toPath()) }
        } catch (_: Exception) {
            null
        }
    }

    private fun homePath(): String = homeDirectory().absolutePath

    private fun nativeMode(mode: String): Int =
        when (mode) {
            "r" -> NativeRuntime.STORAGE_MODE_READ
            "w", "wt" ->
                NativeRuntime.STORAGE_MODE_WRITE or
                    NativeRuntime.STORAGE_MODE_TRUNCATE
            "wa" ->
                NativeRuntime.STORAGE_MODE_WRITE or
                    NativeRuntime.STORAGE_MODE_APPEND
            "rw" ->
                NativeRuntime.STORAGE_MODE_READ or
                    NativeRuntime.STORAGE_MODE_WRITE
            "rwt" ->
                NativeRuntime.STORAGE_MODE_READ or
                    NativeRuntime.STORAGE_MODE_WRITE or
                    NativeRuntime.STORAGE_MODE_TRUNCATE
            else -> throw missing("Unsupported document mode")
        }

    private fun nativeOperation(
        fields: List<String>,
        operation: (ByteBuffer, Int, ByteBuffer) -> Int,
    ): Int {
        val bytes = fields.joinToString("\t").toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_NATIVE_REQUEST_BYTES) {
            throw missing("Document request is too large")
        }
        val request = ByteBuffer.allocateDirect(bytes.size)
        request.put(bytes)
        val output = ByteBuffer.allocateDirect(NativeRuntime.STORAGE_OUTPUT_SIZE)
        val result = operation(request, bytes.size, output)
        if (result < 0) {
            output.position(0)
            val diagnostic = ByteArray(NativeRuntime.STORAGE_OUTPUT_SIZE)
            output.get(diagnostic)
            val length = diagnostic.indexOf(0).let { if (it < 0) diagnostic.size else it }
            val message =
                String(diagnostic, 0, length, StandardCharsets.UTF_8)
                    .ifEmpty { "native storage error $result" }
            throw missing(message)
        }
        return result
    }

    private fun notifyChildren(parentId: String) {
        providerContext().contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority(), parentId),
            null,
        )
    }

    private fun notifyDocument(documentId: String) {
        providerContext().contentResolver.notifyChange(
            DocumentsContract.buildDocumentUri(authority(), documentId),
            null,
        )
    }

    private fun authority(): String = "${providerContext().packageName}.documents"

    private fun providerContext() =
        context ?: throw IllegalStateException("DocumentsProvider is not attached")

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun Char.isBidirectionalControl(): Boolean =
        this == '\u061c' ||
            this == '\u200e' ||
            this == '\u200f' ||
            this in '\u202a'..'\u202e' ||
            this in '\u2066'..'\u2069'

    private data class ResolvedDocument(
        val file: File,
        val attributes: BasicFileAttributes,
    )

    private companion object {
        private const val ROOT_ID = "archphene-home"
        private const val HOME_ID = "home"
        private const val HOME_RELATIVE_PATH = "arch-root/home/archphene"
        private const val MAX_DOCUMENT_ID_BYTES = 1024
        private const val MAX_DOCUMENT_NAME_BYTES = 255
        private const val MAX_DOCUMENT_DEPTH = 32
        private const val MAX_VISIBLE_CHILDREN = 4096
        private const val MAX_NATIVE_REQUEST_BYTES = 4 * 1024

        private val DEFAULT_ROOT_PROJECTION =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            )
        private val DEFAULT_DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        private fun put(
            row: MatrixCursor.RowBuilder,
            cursor: MatrixCursor,
            column: String,
            value: Any?,
        ) {
            if (cursor.getColumnIndex(column) >= 0) {
                row.add(column, value)
            }
        }

        private fun missing(
            message: String,
            cause: Exception? = null,
        ): FileNotFoundException =
            FileNotFoundException(message).also { error ->
                if (cause != null) {
                    error.initCause(cause)
                }
            }
    }
}
