package org.archpheneos.manager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One Android document root that proxies the visible homes of installed GUI wrappers. */
public final class ManagerDocumentsProvider extends DocumentsProvider {
    private static final String ROOT_ID = "archphene-apps";
    private static final String ROOT_DOCUMENT_ID = "apps";
    private static final String APP_PREFIX = "app/";
    private static final String WRAPPER_PERMISSION =
            "org.archpheneos.permission.GUI_DOCUMENTS";
    private static final String PACKAGE_PATTERN = "org\\.archphene\\.linux\\.p[a-f0-9]{32}";

    private static final class AppHome {
        final String packageName;
        final String label;
        final String authority;

        AppHome(String packageName, String label, String authority) {
            this.packageName = packageName;
            this.label = label;
            this.authority = authority;
        }
    }

    private static final class ParsedId {
        final AppHome app;
        final String remoteId;

        ParsedId(AppHome app, String remoteId) {
            this.app = app;
            this.remoteId = remoteId;
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor rows = new MatrixCursor(rootProjection(projection));
        rows.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, "Archphene Apps")
                .add(DocumentsContract.Root.COLUMN_SUMMARY, "Linux application documents")
                .add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        return rows;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(documentProjection(projection));
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            addVirtualRoot(rows);
            return rows;
        }
        ParsedId parsed = parse(documentId);
        try (Cursor source = resolver().query(remoteUri(parsed), projection,
                null, null, null)) {
            copySingle(source, rows, parsed, "home".equals(parsed.remoteId));
            return rows;
        } catch (FileNotFoundException error) {
            throw error;
        } catch (Exception error) {
            throw missing("Could not query " + documentId, error);
        }
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(documentProjection(projection));
        if (ROOT_DOCUMENT_ID.equals(parentId)) {
            for (AppHome app : appHomes()) addAppRoot(rows, app);
            return rows;
        }
        ParsedId parsed = parse(parentId);
        Uri children = brokerChildrenUri(parsed.app, parsed.remoteId);
        try (Cursor source = resolver().query(children, projection, null, null, sortOrder)) {
            if (source == null) throw missing("Wrapper returned no children", null);
            while (source.moveToNext()) copyRow(source, rows, parsed.app, false);
            return rows;
        } catch (FileNotFoundException error) {
            throw error;
        } catch (Exception error) {
            throw missing("Could not query children for " + parentId, error);
        }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        ParsedId parsed = parse(documentId);
        ParcelFileDescriptor descriptor = resolver().openFileDescriptor(
                remoteUri(parsed), mode, signal);
        if (descriptor == null) throw missing("Wrapper returned no descriptor", null);
        return descriptor;
    }

    @Override public String createDocument(String parentId, String mimeType, String displayName)
            throws FileNotFoundException {
        ParsedId parsed = parse(parentId);
        try {
            ContentValues values = new ContentValues();
            values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType);
            values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName);
            Uri created = resolver().insert(
                    brokerChildrenUri(parsed.app, parsed.remoteId), values);
            if (created == null) throw missing("Wrapper did not create document", null);
            return managerId(parsed.app, created.getLastPathSegment());
        } catch (FileNotFoundException error) {
            throw error;
        } catch (Exception error) {
            throw missing("Could not create " + displayName, error);
        }
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        ParsedId parsed = parse(documentId);
        if ("home".equals(parsed.remoteId)) throw missing("Cannot delete app home", null);
        try {
            if (resolver().delete(remoteUri(parsed), null, null) != 1) {
                throw missing("Wrapper did not delete document", null);
            }
        } catch (FileNotFoundException error) {
            throw error;
        } catch (Exception error) {
            throw missing("Could not delete " + documentId, error);
        }
    }

    @Override public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        ParsedId parsed = parse(documentId);
        if ("home".equals(parsed.remoteId)) throw missing("Cannot rename app home", null);
        try {
            ContentValues values = new ContentValues();
            values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName);
            if (resolver().update(remoteUri(parsed), values, null, null) != 1) {
                throw missing("Wrapper did not rename document", null);
            }
            int slash = parsed.remoteId.lastIndexOf('/');
            String renamed = (slash < 0 ? "" : parsed.remoteId.substring(0, slash + 1))
                    + displayName;
            return managerId(parsed.app, renamed);
        } catch (FileNotFoundException error) {
            throw error;
        } catch (Exception error) {
            throw missing("Could not rename " + documentId, error);
        }
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        if (ROOT_DOCUMENT_ID.equals(parentId)) {
            try {
                parse(documentId);
                return true;
            } catch (FileNotFoundException ignored) {
                return false;
            }
        }
        try {
            ParsedId parent = parse(parentId);
            ParsedId child = parse(documentId);
            return parent.app.packageName.equals(child.app.packageName)
                    && (parent.remoteId.equals(child.remoteId)
                            || child.remoteId.startsWith(parent.remoteId + "/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void addVirtualRoot(MatrixCursor rows) {
        MatrixCursor.RowBuilder row = rows.newRow();
        put(row, rows, DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        put(row, rows, DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Archphene Apps");
        put(row, rows, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.MIME_TYPE_DIR);
        put(row, rows, DocumentsContract.Document.COLUMN_FLAGS, 0);
    }

    private void addAppRoot(MatrixCursor rows, AppHome app) {
        MatrixCursor.RowBuilder row = rows.newRow();
        put(row, rows, DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                managerId(app, "home"));
        put(row, rows, DocumentsContract.Document.COLUMN_DISPLAY_NAME, app.label);
        put(row, rows, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.MIME_TYPE_DIR);
        put(row, rows, DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE);
    }

    private void copySingle(Cursor source, MatrixCursor target, ParsedId parsed,
            boolean appRoot) throws FileNotFoundException {
        if (source == null || !source.moveToFirst()) {
            throw missing("Wrapper returned no document", null);
        }
        copyRow(source, target, parsed.app, appRoot);
        if (source.moveToNext()) throw missing("Wrapper returned duplicate documents", null);
    }

    private void copyRow(Cursor source, MatrixCursor target, AppHome app, boolean appRoot)
            throws FileNotFoundException {
        MatrixCursor.RowBuilder row = target.newRow();
        for (String column : target.getColumnNames()) {
            int index = source.getColumnIndex(column);
            Object value = index < 0 || source.isNull(index) ? null : cursorValue(source, index);
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                if (!(value instanceof String)) {
                    throw missing("Wrapper document ID is missing", null);
                }
                value = managerId(app, (String) value);
            } else if (appRoot
                    && DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) {
                value = app.label;
            }
            row.add(column, value);
        }
    }

    private static Object cursorValue(Cursor source, int index) {
        switch (source.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER: return source.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT: return source.getDouble(index);
            case Cursor.FIELD_TYPE_BLOB: return source.getBlob(index);
            case Cursor.FIELD_TYPE_STRING: return source.getString(index);
            default: return null;
        }
    }

    private ParsedId parse(String id) throws FileNotFoundException {
        if (id == null || !id.startsWith(APP_PREFIX) || id.length() > 1024) {
            throw missing("Unknown document " + id, null);
        }
        int separator = id.indexOf('/', APP_PREFIX.length());
        if (separator < 0) throw missing("Malformed app document", null);
        String packageName = id.substring(APP_PREFIX.length(), separator);
        String remoteId = id.substring(separator + 1);
        if (!packageName.matches(PACKAGE_PATTERN) || remoteId.isEmpty()) {
            throw missing("Malformed app document", null);
        }
        for (AppHome app : appHomes()) {
            if (app.packageName.equals(packageName)) return new ParsedId(app, remoteId);
        }
        throw missing("App document is unavailable", null);
    }

    private List<AppHome> appHomes() {
        ArrayList<AppHome> result = new ArrayList<>();
        PackageManager packages = getContext().getPackageManager();
        try {
            for (InstalledLinuxAppCatalog.Entry entry :
                    InstalledLinuxAppCatalog.query(getContext())) {
                if (!entry.packageName.matches(PACKAGE_PATTERN)) continue;
                String authority = entry.packageName + ".documents";
                ProviderInfo provider = packages.resolveContentProvider(
                        authority, PackageManager.GET_META_DATA);
                if (provider == null || !provider.exported
                        || !entry.packageName.equals(provider.packageName)
                        || !WRAPPER_PERMISSION.equals(provider.readPermission)
                        || !WRAPPER_PERMISSION.equals(provider.writePermission)) {
                    continue;
                }
                ApplicationInfo app = packages.getApplicationInfo(
                        entry.packageName, PackageManager.GET_META_DATA);
                if (app.metaData == null
                        || !app.metaData.getBoolean("org.archphene.linux_app", false)) {
                    continue;
                }
                result.add(new AppHome(entry.packageName, entry.label, authority));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not enumerate GUI document brokers", error);
        }
        result.sort(Comparator.comparing(value -> value.label,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private ContentResolver resolver() { return getContext().getContentResolver(); }

    private static Uri remoteUri(ParsedId parsed) {
        return new Uri.Builder().scheme("content").authority(parsed.app.authority)
                .appendPath("document").appendPath(parsed.remoteId).build();
    }

    private static Uri brokerChildrenUri(AppHome app, String remoteId) {
        return new Uri.Builder().scheme("content").authority(app.authority)
                .appendPath("children").appendPath(remoteId).build();
    }

    private static String managerId(AppHome app, String remoteId) {
        return APP_PREFIX + app.packageName + "/" + remoteId;
    }

    private static void put(MatrixCursor.RowBuilder row, MatrixCursor cursor,
            String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }

    private static FileNotFoundException missing(String message, Exception cause) {
        FileNotFoundException error = new FileNotFoundException(message);
        if (cause != null) error.initCause(cause);
        return error;
    }

    private static String[] rootProjection(String[] value) {
        return value != null ? value : new String[] {
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES };
    }

    private static String[] documentProjection(String[] value) {
        return value != null ? value : new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    }
}