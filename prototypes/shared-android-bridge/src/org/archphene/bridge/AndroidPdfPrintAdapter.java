package org.archphene.bridge;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Serves one already-rendered Linux PDF to Android's print spooler. */
final class AndroidPdfPrintAdapter extends PrintDocumentAdapter {
    private final File source;
    private final String title;
    private final Runnable finished;

    AndroidPdfPrintAdapter(File source, String title, Runnable finished) {
        this.source = source;
        this.title = title;
        this.finished = finished;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
            CancellationSignal cancellation, LayoutResultCallback callback, Bundle extras) {
        if (cancellation.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(title)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();
        callback.onLayoutFinished(info, false);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
            CancellationSignal cancellation, WriteResultCallback callback) {
        Thread worker = new Thread(() -> {
            try (FileInputStream input = new FileInputStream(source);
                    FileOutputStream output = new FileOutputStream(
                            destination.getFileDescriptor())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancellation.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
            } catch (IOException error) {
                callback.onWriteFailed("Could not send the Linux PDF to Android");
            }
        }, "archphene-print-writer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onFinish() {
        finished.run();
    }
}