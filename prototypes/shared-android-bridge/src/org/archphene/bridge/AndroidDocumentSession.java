package org.archphene.bridge;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.FileObserver;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AndroidDocumentSession {
    private final Activity activity;
    private final String logTag;
    private final Object syncLock = new Object();
    private volatile Uri documentUri;
    private volatile File documentFile;
    private volatile boolean writable;
    private volatile long syncedModified;
    private FileObserver fileObserver;

    public AndroidDocumentSession(Activity activity, String logTag) {
        this.activity = activity;
        this.logTag = logTag;
    }

    public static boolean isDocumentIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action);
    }

    public File importDocument(Intent intent) {
        if (!isDocumentIntent(intent)) {
            return null;
        }
        Uri uri = intent.getData();
        int grantFlags = intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ("content".equals(uri.getScheme()) && grantFlags != 0) {
            try {
                activity.getContentResolver().takePersistableUriPermission(uri, grantFlags);
            } catch (SecurityException ignored) {
                Log.i(logTag, "Document provider supplied a temporary URI grant");
            }
        }
        File imports = new File(activity.getFilesDir(), "linux-home/Documents/Android");
        if (!imports.isDirectory() && !imports.mkdirs()) {
            Log.e(logTag, "Could not create Android document import directory");
            return null;
        }
        File target = new File(imports, safeDocumentName(queryDisplayName(uri)));
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(target, false)) {
            if (input == null) {
                throw new IOException("Document provider returned no input stream");
            }
            copy(input, output);
            documentUri = uri;
            documentFile = target;
            writable = (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            syncedModified = target.lastModified();
            startFileObserver(imports, target.getName());
            Log.i(logTag, "Imported Android document uri=" + uri + " path="
                    + target.getAbsolutePath() + " writable=" + writable);
            return target;
        } catch (Exception e) {
            Log.e(logTag, "Could not import Android document " + uri, e);
            return null;
        }
    }

    private void startFileObserver(File directory, String fileName) {
        if (fileObserver != null) {
            fileObserver.stopWatching();
        }
        fileObserver = new FileObserver(directory.getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (fileName.equals(path)) {
                    new Thread(AndroidDocumentSession.this::sync,
                            "archphene-document-writeback").start();
                }
            }
        };
        fileObserver.startWatching();
    }

    public void close() {
        sync();
        if (fileObserver != null) {
            fileObserver.stopWatching();
            fileObserver = null;
        }
    }

    public void syncAsyncIfDirty() {
        File file = documentFile;
        if (writable && file != null && file.lastModified() != syncedModified) {
            new Thread(this::sync, "archphene-document-sync").start();
        }
    }

    public void sync() {
        synchronized (syncLock) {
            Uri uri = documentUri;
            File file = documentFile;
            if (!writable || uri == null || file == null || !file.isFile()
                    || file.lastModified() == syncedModified) {
                return;
            }
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = activity.getContentResolver().openOutputStream(uri, "rwt")) {
                if (output == null) {
                    throw new IOException("Document provider returned no output stream");
                }
                copy(input, output);
                syncedModified = file.lastModified();
                Log.i(logTag, "Synced Linux document to Android uri=" + uri
                        + " bytes=" + file.length());
            } catch (Exception e) {
                Log.e(logTag, "Could not sync Linux document to Android " + uri, e);
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = activity.getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            } catch (Exception e) {
                Log.w(logTag, "Could not query Android document name", e);
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "android-document.txt" : segment;
    }

    private static String safeDocumentName(String name) {
        String safe = name == null ? "android-document.txt"
                : name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            safe = "android-document.txt";
        }
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        output.flush();
    }
}