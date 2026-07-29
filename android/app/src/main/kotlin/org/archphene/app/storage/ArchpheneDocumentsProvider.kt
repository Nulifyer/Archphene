package org.archphene.app.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
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
        failDebugLauncherOperation(DEBUG_PROVIDER_QUERY)
        holdDebugLauncherQuery()
        return queryChildDocumentsInternal(parentDocumentId, projection)
    }

    private fun queryChildDocumentsInternal(
        parentDocumentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val parent = documentForId(parentDocumentId)
        if (!parent.isDirectory) {
            throw missing("Document is not a directory: $parentDocumentId")
        }
        if (parent.kind == DocumentKind.SHELL_STARTUP_DIRECTORY) {
            val rows =
                MatrixCursor(
                    projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION,
                    SHELL_STARTUP_DOCUMENT_IDS.size,
                )
            SHELL_STARTUP_DOCUMENT_IDS.forEach { documentId ->
                include(rows, documentId, documentForId(documentId))
            }
            return rows
        }
        val parentFile =
            parent.file ?: throw missing("Document has no backing directory: $parentDocumentId")
        val maximumChildren =
            if (parentDocumentId == HOME_ID) {
                MAX_VISIBLE_CHILDREN - 1
            } else {
                MAX_VISIBLE_CHILDREN
            }
        val visibleChildren =
            try {
                Files.newDirectoryStream(parentFile.toPath()).use { entries ->
                    entries
                        .asSequence()
                        .filter { path -> visibleName(path.fileName.toString()) }
                        .filterNot(Files::isSymbolicLink)
                        .take(maximumChildren + 1)
                        .map { path -> path.toFile() }
                        .toList()
                }
            } catch (error: Exception) {
                throw missing("Could not list directory: $parentDocumentId", error)
            }
        if (visibleChildren.size > maximumChildren) {
            throw missing("Directory exceeds the visible-entry limit")
        }
        val children =
            visibleChildren.sortedWith(
                compareBy<File>({ it.name.lowercase(Locale.ROOT) }, File::getName),
            )
        val rows =
            MatrixCursor(
                projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION,
                children.size + if (parentDocumentId == HOME_ID) 1 else 0,
            )
        if (parentDocumentId == HOME_ID) {
            include(rows, SHELL_STARTUP_ID, documentForId(SHELL_STARTUP_ID))
        }
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

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
    ): Cursor {
        failDebugLauncherOperation(DEBUG_PROVIDER_QUERY)
        holdDebugLauncherQuery()
        return queryChildDocumentsInternal(parentDocumentId, projection)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        failDebugLauncherOperation(DEBUG_PROVIDER_OPEN)
        signal?.throwIfCanceled()
        val document = documentForId(documentId)
        if (!document.isRegularFile) {
            throw missing("Document is not a regular file: $documentId")
        }
        val nativeMode = nativeMode(mode)
        debugSlowLauncherRead(document, nativeMode)?.let { descriptor ->
            return descriptor
        }
        val descriptor =
            if (document.kind == DocumentKind.SHELL_STARTUP_FILE) {
                nativeOperation(
                    listOf(
                        homePath(),
                        document.startupId
                            ?: throw missing("Startup document identity is unavailable"),
                    ),
                ) { request, length, output ->
                    NativeRuntime.nativeOpenShellStartupDocument(
                        request,
                        length,
                        nativeMode,
                        output,
                    )
                }
            } else {
                nativeOperation(listOf(homePath(), documentId)) { request, length, output ->
                    NativeRuntime.nativeOpenHomeDocument(
                        request,
                        length,
                        nativeMode,
                        output,
                    )
                }
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
        if (!parent.isDirectory) {
            throw missing("Document is not a directory: $parentDocumentId")
        }
        if (parent.kind != DocumentKind.PHYSICAL) {
            throw missing("Cannot create shell startup documents")
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
        if (documentForId(documentId).kind != DocumentKind.PHYSICAL) {
            throw missing("Cannot rename shell startup documents")
        }
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
        if (documentForId(documentId).kind != DocumentKind.PHYSICAL) {
            throw missing("Cannot delete shell startup documents")
        }
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
                documentId.startsWith("$parentDocumentId/") ||
                (
                    parentDocumentId == HOME_ID &&
                        (
                            documentId == SHELL_STARTUP_ID ||
                                documentId.startsWith("$SHELL_STARTUP_ID/")
                        )
                )
        } catch (_: FileNotFoundException) {
            false
        }

    private fun include(
        rows: MatrixCursor,
        documentId: String,
        document: ResolvedDocument,
    ) {
        if (!document.isDirectory && !document.isRegularFile) {
            throw missing("Unsupported document type: $documentId")
        }
        var flags =
            if (document.isDirectory) {
                if (document.kind == DocumentKind.PHYSICAL) {
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                } else {
                    0
                }
            } else {
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            }
        if (documentId != HOME_ID && document.kind == DocumentKind.PHYSICAL) {
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
            document.displayName,
        )
        put(
            row,
            rows,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            if (document.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else if (document.kind == DocumentKind.SHELL_STARTUP_FILE) {
                "text/plain"
            } else {
                mimeType(document.file?.name ?: document.displayName)
            },
        )
        put(row, rows, DocumentsContract.Document.COLUMN_FLAGS, flags)
        if (document.isRegularFile) {
            put(
                row,
                rows,
                DocumentsContract.Document.COLUMN_SIZE,
                document.attributes?.size(),
            )
        }
        put(
            row,
            rows,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            document.attributes?.lastModifiedTime()?.toMillis() ?: 0L,
        )
    }

    private fun documentForId(documentId: String): ResolvedDocument {
        if (documentId == SHELL_STARTUP_ID) {
            homeDirectory()
            return ResolvedDocument(
                file = null,
                attributes = null,
                displayName = providerContext().getString(R.string.shell_startup_files),
                kind = DocumentKind.SHELL_STARTUP_DIRECTORY,
            )
        }
        SHELL_STARTUP_FILES[documentId]?.let { startup ->
            var file = homeDirectory()
            startup.pathSegments.forEach { segment ->
                file = File(file, segment)
                if (Files.isSymbolicLink(file.toPath())) {
                    throw missing("Shell startup path is unavailable: ${startup.displayName}")
                }
            }
            return resolvePhysicalDocument(
                file,
                startup.displayName,
                DocumentKind.SHELL_STARTUP_FILE,
                startup.id,
            )
        }
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
        return resolvePhysicalDocument(
            current,
            if (documentId == HOME_ID) {
                providerContext().getString(R.string.documents_home_name)
            } else {
                current.name
            },
            DocumentKind.PHYSICAL,
        )
    }

    private fun resolvePhysicalDocument(
        file: File,
        displayName: String,
        kind: DocumentKind,
        startupId: String? = null,
    ): ResolvedDocument {
        val attributes =
            try {
                Files.readAttributes(
                    file.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (error: Exception) {
                throw missing("Document is unavailable: $displayName", error)
            }
        if (
            kind == DocumentKind.SHELL_STARTUP_FILE &&
            (!attributes.isRegularFile || Files.isSymbolicLink(file.toPath()))
        ) {
            throw missing("Shell startup file is unavailable: $displayName")
        }
        return ResolvedDocument(file, attributes, displayName, kind, startupId)
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

    private fun holdDebugLauncherQuery() {
        val context = providerContext()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }
        val delay =
            runCatching {
                File(context.cacheDir, PORTAL_FOLDER_PROVIDER_DELAY_FILE)
                    .readText()
                    .trim()
                    .toLong()
            }.getOrNull()
                ?.takeIf { milliseconds -> milliseconds in 1..MAX_TEST_PROVIDER_DELAY_MILLIS }
                ?: return
        val caller = debugLauncherCaller(context) ?: return
        Log.i(TAG, "Portal folder query delay requested caller=$caller")
        val deadline = SystemClock.elapsedRealtime() + delay
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }
    }

    private fun failDebugLauncherOperation(operation: String) {
        val context = providerContext()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }
        val configuredOperation =
            runCatching {
                File(context.cacheDir, PORTAL_FOLDER_PROVIDER_FAILURE_FILE)
                    .readText()
                    .trim()
            }.getOrNull()
                ?: return
        if (configuredOperation != operation) {
            return
        }
        val caller = debugLauncherCaller(context) ?: return
        Log.i(TAG, "Portal folder failure requested operation=$operation caller=$caller")
        throw missing("Debug provider failure while attempting to $operation")
    }

    private fun debugSlowLauncherRead(
        document: ResolvedDocument,
        nativeMode: Int,
    ): ParcelFileDescriptor? {
        val context = providerContext()
        if (
            nativeMode != NativeRuntime.STORAGE_MODE_READ ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
        ) {
            return null
        }
        val delay =
            runCatching {
                File(context.cacheDir, PORTAL_FOLDER_PROVIDER_READ_DELAY_FILE)
                    .readText()
                    .trim()
                    .toLong()
            }.getOrNull()
                ?.takeIf { milliseconds ->
                    milliseconds in 1..MAX_TEST_PROVIDER_READ_DELAY_MILLIS
                }
                ?: return null
        val caller = debugLauncherCaller(context) ?: return null
        val source = document.file ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = pipe[0]
        val writer = pipe[1]
        Log.i(TAG, "Portal folder slow read requested delay=$delay caller=$caller")
        val producer =
            Thread(
                {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                            FileInputStream(source).use { input ->
                                val buffer = ByteArray(TEST_PROVIDER_READ_CHUNK_BYTES)
                                var count = input.read(buffer)
                                while (count >= 0) {
                                    output.write(buffer, 0, count)
                                    count = input.read(buffer)
                                    if (count >= 0) {
                                        SystemClock.sleep(delay)
                                    }
                                }
                            }
                        }
                    } catch (error: IOException) {
                        Log.i(TAG, "Portal folder slow read ended early", error)
                    }
                },
                "ArchpheneProviderSlowRead",
            ).apply {
                isDaemon = true
            }
        try {
            producer.start()
        } catch (error: RuntimeException) {
            reader.close()
            writer.close()
            throw missing("Could not start debug provider stream", error)
        }
        return reader
    }

    private fun debugLauncherCaller(context: Context): String? {
        val callingUid = Binder.getCallingUid()
        return context.packageManager
            .getPackagesForUid(callingUid)
            .orEmpty()
            .firstOrNull { packageName -> packageName.startsWith(LAUNCHER_PACKAGE_PREFIX) }
    }

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

    private enum class DocumentKind {
        PHYSICAL,
        SHELL_STARTUP_DIRECTORY,
        SHELL_STARTUP_FILE,
    }

    private data class ResolvedDocument(
        val file: File?,
        val attributes: BasicFileAttributes?,
        val displayName: String,
        val kind: DocumentKind,
        val startupId: String? = null,
    ) {
        val isDirectory: Boolean
            get() =
                kind == DocumentKind.SHELL_STARTUP_DIRECTORY ||
                    attributes?.isDirectory == true
        val isRegularFile: Boolean
            get() = attributes?.isRegularFile == true
    }

    private class ShellStartupFile(
        val id: String,
        val pathSegments: Array<String>,
        val displayName: String,
    )

    private companion object {
        private const val ROOT_ID = "archphene-home"
        private const val TAG = "ArchpheneDocuments"
        private const val HOME_ID = "home"
        private const val SHELL_STARTUP_ID = "shell-startup"
        private const val BASHRC_DOCUMENT_ID = "$SHELL_STARTUP_ID/bashrc"
        private const val BASH_PROFILE_DOCUMENT_ID = "$SHELL_STARTUP_ID/bash-profile"
        private const val ZSHRC_DOCUMENT_ID = "$SHELL_STARTUP_ID/zshrc"
        private const val FISH_CONFIG_DOCUMENT_ID = "$SHELL_STARTUP_ID/fish-config"
        private const val HOME_RELATIVE_PATH = "arch-root/home/archphene"
        private const val MAX_DOCUMENT_ID_BYTES = 1024
        private const val MAX_DOCUMENT_NAME_BYTES = 255
        private const val MAX_DOCUMENT_DEPTH = 32
        private const val MAX_VISIBLE_CHILDREN = 4096
        private const val MAX_NATIVE_REQUEST_BYTES = 4 * 1024
        private const val LAUNCHER_PACKAGE_PREFIX = "org.archphene.linux.p"
        private const val PORTAL_FOLDER_PROVIDER_DELAY_FILE =
            "portal-folder-provider-delay-ms"
        private const val PORTAL_FOLDER_PROVIDER_FAILURE_FILE =
            "portal-folder-provider-failure"
        private const val PORTAL_FOLDER_PROVIDER_READ_DELAY_FILE =
            "portal-folder-provider-read-delay-ms"
        private const val DEBUG_PROVIDER_QUERY = "query"
        private const val DEBUG_PROVIDER_OPEN = "open"
        private const val MAX_TEST_PROVIDER_DELAY_MILLIS = 60_000L
        private const val MAX_TEST_PROVIDER_READ_DELAY_MILLIS = 25_000L
        private const val TEST_PROVIDER_READ_CHUNK_BYTES = 16
        private val SHELL_STARTUP_FILES =
            mapOf(
                BASHRC_DOCUMENT_ID to
                    ShellStartupFile(
                        id = "bashrc",
                        pathSegments = arrayOf(".bashrc"),
                        displayName = "Edit .bashrc",
                    ),
                BASH_PROFILE_DOCUMENT_ID to
                    ShellStartupFile(
                        id = "bash-profile",
                        pathSegments = arrayOf(".bash_profile"),
                        displayName = "Edit .bash_profile",
                    ),
                ZSHRC_DOCUMENT_ID to
                    ShellStartupFile(
                        id = "zshrc",
                        pathSegments = arrayOf(".zshrc"),
                        displayName = "Edit .zshrc",
                    ),
                FISH_CONFIG_DOCUMENT_ID to
                    ShellStartupFile(
                        id = "fish-config",
                        pathSegments = arrayOf(".config", "fish", "config.fish"),
                        displayName = "Edit Fish config",
                    ),
            )
        private val SHELL_STARTUP_DOCUMENT_IDS =
            arrayOf(
                BASHRC_DOCUMENT_ID,
                BASH_PROFILE_DOCUMENT_ID,
                ZSHRC_DOCUMENT_ID,
                FISH_CONFIG_DOCUMENT_ID,
            )

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
