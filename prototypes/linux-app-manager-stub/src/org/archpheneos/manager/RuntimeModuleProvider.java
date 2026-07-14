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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Read-only, package-granted access to immutable runtime-pack files. */
public final class RuntimeModuleProvider extends ContentProvider {
    public static final String AUTHORITY = "org.archpheneos.manager.runtime";
    public static final String PROBE_HASH =
            "76136d0afafb480c67517dea36450ec28b120ab4b73c29e036c74c6a2c00101c";
    public static final String DYNAMIC_PROBE_HASH =
            "6adbf15a76ef673ee66b8af66b3717383cbefea55c9d65809d909c7597fe099b";
    public static final String LOADER_HASH =
            "d1763646c97e95ed93ad72c43365cab8747a83170c849002002c7675749a1915";
    public static final String LIBC_HASH =
            "1e31d1a9cb4ddf13d1bb61ed0be1e4e04309b32d1f6f1f0a68820f2e3099101a";
    public static final Uri PROBE_URI = moduleUri(PROBE_HASH);
    public static final Uri DYNAMIC_PROBE_URI = moduleUri(DYNAMIC_PROBE_HASH);
    public static final Uri LOADER_URI = moduleUri(LOADER_HASH);
    public static final Uri LIBC_URI = moduleUri(LIBC_HASH);
    private final Map<String, File> verifiedModules = new HashMap<>();

    private static final class Module {
        final String hash;
        final String library;
        final long size;

        Module(String hash, String library, long size) {
            this.hash = hash;
            this.library = library;
            this.size = size;
        }
    }

    @Override public boolean onCreate() { return true; }

    public static void revokeAll(Context context) {
        for (Uri uri : new Uri[] {PROBE_URI, DYNAMIC_PROBE_URI, LOADER_URI, LIBC_URI}) {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new SecurityException("Runtime modules are read-only");
        }
        return ParcelFileDescriptor.open(moduleFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
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
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(uri.getLastPathSegment());
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

    private static Uri moduleUri(String hash) {
        return Uri.parse("content://" + AUTHORITY + "/v1/" + hash);
    }

    private static Module describe(Uri uri) throws FileNotFoundException {
        if (PROBE_URI.equals(uri)) {
            return new Module(PROBE_HASH, "libarchphene_runtime_probe.so", 1593506L);
        }
        if (DYNAMIC_PROBE_URI.equals(uri)) {
            return new Module(DYNAMIC_PROBE_HASH, "libarchphene_dynamic_probe.so", 14384L);
        }
        if (LOADER_URI.equals(uri)) {
            return new Module(LOADER_HASH, "libarchphene_ld.so", 1388712L);
        }
        if (LIBC_URI.equals(uri)) {
            return new Module(LIBC_HASH, "libarchphene_runtime_libc.so", 11770888L);
        }
        throw new FileNotFoundException("Unknown runtime module");
    }

    private synchronized File moduleFor(Uri uri) throws FileNotFoundException {
        Module expected = describe(uri);
        File cached = verifiedModules.get(expected.hash);
        if (cached != null) return cached;
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Runtime provider is unavailable");
        File nativeRoot;
        File module;
        try {
            nativeRoot = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            module = new File(nativeRoot, expected.library).getCanonicalFile();
        } catch (Exception error) {
            throw new FileNotFoundException("Runtime module path is invalid");
        }
        if (!module.getParentFile().equals(nativeRoot) || !module.isFile()
                || module.length() != expected.size || !expected.hash.equals(sha256(module))) {
            throw new FileNotFoundException("Verified runtime module is missing");
        }
        verifiedModules.put(expected.hash, module);
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
