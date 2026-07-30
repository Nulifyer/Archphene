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
import java.io.FileOutputStream

/** Serves one already-rendered, wrapper-private Linux PDF to Android's spooler. */
internal class LinuxPdfPrintAdapter(
    private val source: File,
    private val title: String,
    private val finished: () -> Unit,
) : PrintDocumentAdapter() {
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
        Thread(
            {
                runCatching {
                    FileInputStream(source).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                if (cancellationSignal.isCanceled) {
                                    callback.onWriteCancelled()
                                    return@Thread
                                }
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                }.onSuccess {
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }.onFailure {
                    callback.onWriteFailed("Could not send the Linux PDF to Android")
                }
            },
            "ArchphenePrintWriter",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun onFinish() {
        finished()
    }

    private companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
