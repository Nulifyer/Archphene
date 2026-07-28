package org.archphene.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

internal class DocumentImportTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = queryFixture(uri, projection)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        queryArgs: android.os.Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        cancellationSignal?.throwIfCanceled()
        return queryFixture(uri, projection)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = openFile(uri, mode, null)

    override fun openFile(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Debug import provider is read-only")
        }
        val fixture = fixture(uri)
        if (fixture.mode == MODE_STALL_OPEN) {
            Log.i(TAG, "Stalling descriptor open token=${fixture.token}")
            val deadline = SystemClock.elapsedRealtime() + STALL_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                signal?.throwIfCanceled()
                SystemClock.sleep(CANCELLATION_POLL_MILLIS)
            }
        } else if (fixture.mode == MODE_IGNORE_OPEN) {
            Log.i(TAG, "Ignoring descriptor open cancellation token=${fixture.token}")
            SystemClock.sleep(IGNORED_OPEN_MILLIS)
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = pipe[0]
        val writer = pipe[1]
        val producer =
            Thread(
                {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                            when (fixture.mode) {
                                MODE_STALL_READ -> {
                                    Log.i(TAG, "Stalling descriptor read token=${fixture.token}")
                                    SystemClock.sleep(STALL_MILLIS)
                                }
                                MODE_PACED_READ -> {
                                    providerChunks(fixture.token).forEachIndexed { index, chunk ->
                                        output.write(chunk)
                                        output.flush()
                                        if (index + 1 < PROVIDER_CHUNK_COUNT) {
                                            SystemClock.sleep(PACED_CHUNK_DELAY_MILLIS)
                                        }
                                    }
                                }
                                else -> output.write(providerContent(fixture.token))
                            }
                        }
                    } catch (error: Exception) {
                        Log.i(TAG, "Debug import provider stream closed", error)
                    }
                },
                "ArchpheneImportTestProvider",
            ).apply {
                isDaemon = true
            }
        try {
            producer.start()
        } catch (error: RuntimeException) {
            reader.close()
            writer.close()
            throw FileNotFoundException("Could not start debug provider: ${error.message}")
        }
        return reader
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun queryFixture(
        uri: Uri,
        projection: Array<out String>?,
    ): Cursor {
        val fixture = fixture(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> fixture.displayName
                        OpenableColumns.SIZE -> providerContent(fixture.token).size.toLong()
                        else -> null
                    }
                },
            )
        }
    }

    private fun fixture(uri: Uri): Fixture {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] !in MODES || !TOKEN.matches(segments[1])) {
            throw FileNotFoundException("Invalid debug import fixture")
        }
        val mode = segments[0]
        val token = segments[1]
        val prefix =
            when (mode) {
                MODE_STALL_OPEN -> "Provider-open-"
                MODE_IGNORE_OPEN -> "Provider-ignore-"
                MODE_STALL_READ -> "Provider-read-"
                MODE_PACED_READ -> "Provider-paced-"
                else -> "Provider-"
            }
        return Fixture(mode, token, "$prefix$token.txt")
    }

    private data class Fixture(
        val mode: String,
        val token: String,
        val displayName: String,
    )

    companion object {
        private const val TAG = "ArchpheneImportProvider"
        private const val MODE_NORMAL = "normal"
        private const val MODE_STALL_OPEN = "stall-open"
        private const val MODE_IGNORE_OPEN = "ignore-open"
        private const val MODE_STALL_READ = "stall-read"
        private const val MODE_PACED_READ = "paced-read"
        private val MODES =
            setOf(
                MODE_NORMAL,
                MODE_STALL_OPEN,
                MODE_IGNORE_OPEN,
                MODE_STALL_READ,
                MODE_PACED_READ,
            )
        private val TOKEN = Regex("[a-f0-9]{8}")
        private const val STALL_MILLIS = 2_000L
        private const val IGNORED_OPEN_MILLIS = 5_000L
        private const val CANCELLATION_POLL_MILLIS = 20L
        private const val PACED_CHUNK_DELAY_MILLIS = 300L
        private const val PROVIDER_CHUNK_COUNT = 3

        fun providerContent(token: String): ByteArray =
            providerChunks(token).fold(ByteArray(0)) { content, chunk -> content + chunk }

        private fun providerChunks(token: String): List<ByteArray> =
            List(PROVIDER_CHUNK_COUNT) { index ->
                "Archphene provider deadline fixture $token chunk ${index + 1}\n"
                    .toByteArray(StandardCharsets.UTF_8)
            }
    }
}
