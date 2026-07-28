package org.archphene.launcher

import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException

/**
 * Applies a sliding deadline to one directory-stream producer.
 *
 * SAF query/open calls receive a CancellationSignal. Pipe-backed reads also
 * close their descriptor when the deadline expires. A provider that ignores
 * both mechanisms reaches [onFatalBlock], allowing the wrapper process to
 * terminate while the manager rolls back Rust-owned staging.
 */
internal class DirectoryProviderWatchdog(
    private val handler: Handler,
    private val deadlineMillis: Long,
    private val fatalGraceMillis: Long,
    private val onFatalBlock: (String) -> Unit,
) : Closeable {
    private var active = false
    private var timedOut = false
    private var label = ""
    private var signal: CancellationSignal? = null
    private var descriptor: ParcelFileDescriptor? = null

    private val fatal =
        Runnable {
            val blockedLabel =
                synchronized(this) {
                    if (!active || !timedOut) return@Runnable
                    label
                }
            onFatalBlock(blockedLabel)
        }

    private val timeout =
        Runnable {
            val cancellation: CancellationSignal?
            val blockedDescriptor: ParcelFileDescriptor?
            synchronized(this) {
                if (!active) return@Runnable
                timedOut = true
                cancellation = signal
                blockedDescriptor = descriptor
            }
            cancellation?.cancel()
            runCatching { blockedDescriptor?.close() }
            handler.postDelayed(fatal, fatalGraceMillis)
        }

    fun <T> cancellable(
        operationLabel: String,
        operation: (CancellationSignal) -> T,
    ): T {
        val cancellation = CancellationSignal()
        begin(operationLabel, cancellation, null)
        var failure: Throwable? = null
        return try {
            operation(cancellation)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (finish()) throw timeoutError(operationLabel, failure)
        }
    }

    fun read(
        operationLabel: String,
        sourceDescriptor: ParcelFileDescriptor,
        operation: () -> Int,
    ): Int {
        begin(operationLabel, null, sourceDescriptor)
        var failure: Throwable? = null
        return try {
            operation()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (finish()) throw timeoutError(operationLabel, failure)
        }
    }

    @Synchronized
    private fun begin(
        operationLabel: String,
        cancellation: CancellationSignal?,
        sourceDescriptor: ParcelFileDescriptor?,
    ) {
        check(!active) { "Directory provider operations cannot overlap" }
        check(deadlineMillis > 0 && fatalGraceMillis > 0)
        active = true
        timedOut = false
        label = operationLabel
        signal = cancellation
        descriptor = sourceDescriptor
        handler.postDelayed(timeout, deadlineMillis)
    }

    @Synchronized
    private fun finish(): Boolean {
        val expired = timedOut
        active = false
        signal = null
        descriptor = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(fatal)
        return expired
    }

    override fun close() {
        val cancellation: CancellationSignal?
        val blockedDescriptor: ParcelFileDescriptor?
        synchronized(this) {
            cancellation = signal
            blockedDescriptor = descriptor
            active = false
            signal = null
            descriptor = null
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(fatal)
        }
        cancellation?.cancel()
        runCatching { blockedDescriptor?.close() }
    }

    private fun timeoutError(
        operationLabel: String,
        cause: Throwable? = null,
    ): IOException =
        IOException(
            "Android provider timed out while attempting to $operationLabel",
            cause,
        )
}
