package org.archphene.app.runtime

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ProjectSyncProviderTimeout(message: String) : Exception(message)

/**
 * Bounded SAF access for the project synchronizer.
 *
 * Queries and descriptor opens first use Android cancellation signals. If a
 * provider ignores cancellation, the watchdog terminates the manager process
 * after a short grace period. Mutations do not expose a cancellation signal,
 * so their watchdog terminates immediately; the on-disk synchronization
 * journal makes a possibly completed mutation recoverable on the next Sync.
 */
internal class ProjectSyncProvider(
    private val resolver: ContentResolver,
    private val handler: Handler,
    private val deadlineMillis: Long,
    private val onMutationTimeout: (String) -> Unit,
) {
    private val activeSignal = AtomicReference<CancellationSignal?>()

    fun cancel() {
        activeSignal.get()?.cancel()
    }

    fun <T> query(
        uri: Uri,
        projection: Array<String>,
        label: String,
        read: (Cursor) -> T,
    ): T =
        cancellable(label) { signal ->
            val cursor =
                resolver.query(uri, projection, null, null, null, signal)
                    ?: error("Android provider returned no $label result")
            cursor.use(read)
        }

    fun open(
        uri: Uri,
        mode: String,
        label: String,
    ): ParcelFileDescriptor =
        cancellable(label) { signal ->
            resolver.openFileDescriptor(uri, mode, signal)
                ?: error("Android provider returned no $label descriptor")
        }

    fun create(
        parent: Uri,
        mime: String,
        name: String,
        label: String,
    ): Uri? =
        mutation(label) {
            DocumentsContract.createDocument(resolver, parent, mime, name)
        }

    fun rename(
        document: Uri,
        name: String,
        label: String,
    ): Uri? =
        mutation(label) {
            DocumentsContract.renameDocument(resolver, document, name)
        }

    fun delete(
        document: Uri,
        label: String,
    ): Boolean =
        mutation(label) {
            DocumentsContract.deleteDocument(resolver, document)
        }

    private fun <T> cancellable(
        label: String,
        operation: (CancellationSignal) -> T,
    ): T {
        val signal = CancellationSignal()
        val previous = activeSignal.getAndSet(signal)
        val timedOut = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val forceTimeout =
            Runnable {
                if (!completed.get()) {
                    onMutationTimeout(label)
                }
            }
        val timeout =
            Runnable {
                timedOut.set(true)
                signal.cancel()
                handler.postDelayed(forceTimeout, CANCELLATION_GRACE_MILLIS)
            }
        handler.postDelayed(timeout, deadlineMillis)
        try {
            val result = operation(signal)
            if (timedOut.get()) {
                throw ProjectSyncProviderTimeout(
                    "Android provider timed out while attempting to $label",
                )
            }
            return result
        } catch (error: OperationCanceledException) {
            if (timedOut.get()) {
                throw ProjectSyncProviderTimeout(
                    "Android provider timed out while attempting to $label",
                )
            }
            throw InterruptedException("Android provider operation was cancelled")
        } finally {
            completed.set(true)
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(forceTimeout)
            activeSignal.compareAndSet(signal, previous)
        }
    }

    private fun <T> mutation(
        label: String,
        operation: () -> T,
    ): T {
        val completed = AtomicBoolean(false)
        val timeout =
            Runnable {
                if (completed.compareAndSet(false, true)) {
                    onMutationTimeout(label)
                }
            }
        handler.postDelayed(timeout, deadlineMillis)
        try {
            val result = operation()
            if (!completed.compareAndSet(false, true)) {
                throw ProjectSyncProviderTimeout(
                    "Android provider timed out while attempting to $label",
                )
            }
            return result
        } finally {
            completed.set(true)
            handler.removeCallbacks(timeout)
        }
    }

    private companion object {
        private const val CANCELLATION_GRACE_MILLIS = 2_000L
    }
}
