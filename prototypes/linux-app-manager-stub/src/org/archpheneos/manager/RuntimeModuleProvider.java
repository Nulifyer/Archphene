package org.archpheneos.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Read-only, package-granted access to immutable runtime-pack files. */
public final class RuntimeModuleProvider extends ContentProvider {
    public static final String AUTHORITY = "org.archpheneos.manager.runtime";
    public static final String PROBE_HASH =
            "76136d0afafb480c67517dea36450ec28b120ab4b73c29e036c74c6a2c00101c";
    public static final Uri PROBE_URI = Uri.parse(
            "content://" + AUTHORITY + "/v1/" + PROBE_HASH);
    private static final String PROBE_LIBRARY = "libarchphene_runtime_probe.so";
    private File verifiedProbe;

    @Override public boolean onCreate() { return true; }

    public static void revokeProbe(Context context) {
        context.revokeUriPermission(PROBE_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new SecurityException("Runtime modules are read-only");
        }
        File module = moduleFor(uri);
        return ParcelFileDescriptor.open(module, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File module;
        try {
            module = moduleFor(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(PROBE_HASH);
            else if (OpenableColumns.SIZE.equals(column)) row.add(module.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override public String getType(Uri uri) {
        try {
            moduleFor(uri);
            return "application/vnd.archphene.runtime-module";
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }

    private synchronized File moduleFor(Uri uri) throws FileNotFoundException {
        if (!PROBE_URI.equals(uri)) throw new FileNotFoundException("Unknown runtime module");
        if (verifiedProbe != null) return verifiedProbe;
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Runtime provider is unavailable");
        File nativeRoot;
        File module;
        try {
            nativeRoot = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            module = new File(nativeRoot, PROBE_LIBRARY).getCanonicalFile();
        } catch (Exception error) {
            throw new FileNotFoundException("Runtime module path is invalid");
        }
        if (!module.getParentFile().equals(nativeRoot) || !module.isFile()
                || module.length() != 1593506L || !PROBE_HASH.equals(sha256(module))) {
            throw new FileNotFoundException("Verified runtime module is missing");
        }
        verifiedProbe = module;
        return module;
    }

    private static String sha256(File file) throws FileNotFoundException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) value.append(String.format("%02x", part & 0xff));
            return value.toString();
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Could not verify runtime module");
            failure.initCause(error);
            throw failure;
        }
    }
}
