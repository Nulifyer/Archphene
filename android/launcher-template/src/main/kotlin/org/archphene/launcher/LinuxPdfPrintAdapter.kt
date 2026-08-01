package org.archphene.launcher

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream

/** Serves one already-rendered, wrapper-private Linux PDF to Android's spooler. */
internal class LinuxPdfPrintAdapter(
    private val source: File,
    private val title: String,
    private val finished: () -> Unit,
) : PrintDocumentAdapter() {
    private val writeTask = SingleActiveTask()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder(title)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build(),
            false,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            runCatching { destination.close() }
            callback.onWriteCancelled()
            return
        }
        if (!writeTask.tryAcquire()) {
            runCatching { destination.close() }
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
            } else {
                callback.onWriteFailed("Another Linux PDF write is already active")
            }
            return
        }
        val cancellation = PrintWriteCancellation()
        val completion =
            SingleTaskCompletion(writeTask) { outcome ->
                when (outcome) {
                    SingleTaskOutcome.FINISHED ->
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    SingleTaskOutcome.CANCELLED -> callback.onWriteCancelled()
                    SingleTaskOutcome.FAILED ->
                        callback.onWriteFailed("Could not send the Linux PDF to Android")
                }
            }
        val output =
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination)
            } catch (_: Throwable) {
                runCatching { destination.close() }
                completion.complete(SingleTaskOutcome.FAILED)
                return
            }
        cancellation.attachOutput(output)
        try {
            cancellationSignal.setOnCancelListener(cancellation::cancel)
        } catch (_: Throwable) {
            if (cancellationSignal.isCanceled) cancellation.cancel()
            cancellation.close()
            val outcome = cancellation.commit(SingleTaskOutcome.FAILED)
            completion.complete(outcome)
            return
        }
        val worker =
            try {
                Thread(
                    {
                        var outcome = SingleTaskOutcome.FAILED
                        try {
                            if (cancellation.cancelled) {
                                outcome = SingleTaskOutcome.CANCELLED
                            } else {
                                val input = FileInputStream(source)
                                cancellation.attachInput(input)
                                val buffer = ByteArray(COPY_BUFFER_BYTES)
                                while (!cancellation.cancelled) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                if (cancellation.cancelled) {
                                    outcome = SingleTaskOutcome.CANCELLED
                                } else {
                                    output.flush()
                                    outcome = SingleTaskOutcome.FINISHED
                                }
                            }
                        } catch (_: Throwable) {
                            outcome =
                                if (cancellation.cancelled) {
                                    SingleTaskOutcome.CANCELLED
                                } else {
                                    SingleTaskOutcome.FAILED
                                }
                        } finally {
                            if (!cancellation.close() && outcome == SingleTaskOutcome.FINISHED) {
                                outcome = SingleTaskOutcome.FAILED
                            }
                            outcome = cancellation.commit(outcome)
                        }
                        try {
                            completion.complete(outcome)
                        } finally {
                            runCatching { cancellationSignal.setOnCancelListener(null) }
                        }
                    },
                    "ArchphenePrintWriter",
                ).apply { isDaemon = true }
            } catch (_: Throwable) {
                cancellation.close()
                val outcome = cancellation.commit(SingleTaskOutcome.FAILED)
                try {
                    completion.complete(outcome)
                } finally {
                    runCatching { cancellationSignal.setOnCancelListener(null) }
                }
                return
            }
        try {
            worker.start()
        } catch (_: Throwable) {
            cancellation.close()
            val outcome = cancellation.commit(SingleTaskOutcome.FAILED)
            try {
                completion.complete(outcome)
            } finally {
                runCatching { cancellationSignal.setOnCancelListener(null) }
            }
        }
    }

    override fun onFinish() {
        writeTask.close()
        finished()
    }

    private companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

}
