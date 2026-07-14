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
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Read-only, package-granted access to immutable runtime-pack files. */
public final class RuntimeModuleProvider extends ContentProvider {
    public static final String AUTHORITY = "org.archpheneos.manager.runtime";
    private final Map<String, File> verifiedModules = new HashMap<>();

    @Override
    public boolean onCreate() {
        try {
            RuntimeModuleCatalog.load(providerContext());
            return true;
        } catch (IOException error) {
            Log.e("ArchpheneRuntime", "Runtime module catalog rejected", error);
            return false;
        }
    }

    public static Uri uriForRole(Context context, String role) throws IOException {
        return RuntimeModuleCatalog.load(context).requireRole(role).uri();
    }

    public static String linkNameForRole(Context context, String role) throws IOException {
        return RuntimeModuleCatalog.load(context).requireRole(role).linkName;
    }

    public static void revokeAll(Context context) throws IOException {
        for (RuntimeModuleCatalog.Module module : RuntimeModuleCatalog.load(context).modules()) {
            context.revokeUriPermission(module.uri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
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

    @Override
    public String getType(Uri uri) {
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
        RuntimeModuleCatalog.Module expected;
        try {
            expected = RuntimeModuleCatalog.load(providerContext()).requireUri(uri);
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Runtime module catalog is unavailable");
            failure.initCause(error);
            throw failure;
        }
        File cached = verifiedModules.get(expected.hash);
        if (cached != null) return cached;
        File nativeRoot;
        File module;
        try {
            nativeRoot = new File(providerContext().getApplicationInfo().nativeLibraryDir)
                    .getCanonicalFile();
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

    private Context providerContext() throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Runtime provider is unavailable");
        return context;
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
