package org.archphene.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Signature-protected wrapper endpoint used only by the manager DocumentsProvider. */
public final class LinuxHomeBrokerProvider extends ContentProvider {
    private static final String MANAGER_PACKAGE = "org.archpheneos.manager";
    private static final String BROKER_PERMISSION =
            "org.archpheneos.permission.GUI_DOCUMENTS";
    private static final String HOME_ID = "home";
    private static final String DOCUMENT = "document";
    private static final String CHILDREN = "children";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceManagerCaller();
        try {
            List<String> segments = uri.getPathSegments();
            MatrixCursor rows = new MatrixCursor(documentProjection(projection));
            if (segments.size() != 2) throw missing("Malformed broker URI", null);
            if (DOCUMENT.equals(segments.get(0))) {
                String id = segments.get(1);
                include(rows, id, fileForId(id));
                return rows;
            }
            if (CHILDREN.equals(segments.get(0))) {
                File[] children = fileForId(segments.get(1)).listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!child.getName().startsWith(".")) {
                            include(rows, idForFile(child), child);
                        }
                    }
                }
                return rows;
            }
            throw missing("Unknown broker URI", null);
        } catch (FileNotFoundException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        enforceManagerCaller();
        return ParcelFileDescriptor.open(fileForDocumentUri(uri),
                ParcelFileDescriptor.parseMode(mode));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        enforceManagerCaller();
        try {
            String parentId = idForUri(uri, CHILDREN);
            String displayName = validName(values.getAsString(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME));
            String mimeType = values.getAsString(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (mimeType == null || mimeType.isEmpty()) {
                throw missing("Document MIME type is missing", null);
            }
            File child = new File(fileForId(parentId), displayName);
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? child.mkdir() : child.createNewFile();
            if (!created) throw missing("Document already exists", null);
            Uri result = documentUri(idForFile(child));
            notifyChanged(uri, result);
            return result;
        } catch (FileNotFoundException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        } catch (IOException error) {
            throw new IllegalStateException("Could not create brokered document", error);
        }
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        enforceManagerCaller();
        try {
            String id = idForUri(uri, DOCUMENT);
            if (HOME_ID.equals(id)) throw missing("Cannot rename home", null);
            String displayName = validName(values.getAsString(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME));
            File source = fileForId(id);
            File target = new File(source.getParentFile(), displayName);
            if (!source.renameTo(target)) throw missing("Could not rename document", null);
            notifyChanged(uri, documentUri(idForFile(target)));
            return 1;
        } catch (FileNotFoundException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceManagerCaller();
        try {
            String id = idForUri(uri, DOCUMENT);
            if (HOME_ID.equals(id)) throw missing("Cannot delete home", null);
            File file = fileForId(id);
            if (!file.delete()) throw missing("Could not delete document", null);
            notifyChanged(uri, childrenUri(parentId(id)));
            return 1;
        } catch (FileNotFoundException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    @Override public String getType(Uri uri) {
        enforceManagerCaller();
        try {
            File file = fileForDocumentUri(uri);
            return file.isDirectory()
                    ? DocumentsContract.Document.MIME_TYPE_DIR : mime(file.getName());
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    private void enforceManagerCaller() {
        String caller = getCallingPackage();

        if (!MANAGER_PACKAGE.equals(caller)
                || getContext().getPackageManager().checkPermission(
                        BROKER_PERMISSION, caller)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Only the Archphene manager may access Linux home");
        }
    }
    private File fileForDocumentUri(Uri uri) throws FileNotFoundException {
        return fileForId(idForUri(uri, DOCUMENT));
    }

    private String idForUri(Uri uri, String expected) throws FileNotFoundException {
        if (uri == null || !getAuthority().equals(uri.getAuthority())) {
            throw missing("Unknown broker authority", null);
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !expected.equals(segments.get(0))) {
            throw missing("Malformed broker URI", null);
        }
        return segments.get(1);
    }

    private String getAuthority() {
        return getContext().getPackageName() + ".documents";
    }

    private Uri documentUri(String id) {
        return new Uri.Builder().scheme("content").authority(getAuthority())
                .appendPath(DOCUMENT).appendPath(id).build();
    }

    private Uri childrenUri(String id) {
        return new Uri.Builder().scheme("content").authority(getAuthority())
                .appendPath(CHILDREN).appendPath(id).build();
    }

    private void notifyChanged(Uri first, Uri second) {
        getContext().getContentResolver().notifyChange(first, null);
        if (!first.equals(second)) getContext().getContentResolver().notifyChange(second, null);
    }

    private void include(MatrixCursor rows, String id, File file) {
        boolean directory = file.isDirectory();
        int flags = directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        if (!HOME_ID.equals(id)) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        }
        rows.newRow()
                .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        HOME_ID.equals(id) ? "Home" : file.getName())
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE,
                        directory ? DocumentsContract.Document.MIME_TYPE_DIR
                                : mime(file.getName()))
                .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                .add(DocumentsContract.Document.COLUMN_SIZE, directory ? null : file.length())
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private File root() {
        File root = new File(getContext().getFilesDir(), "linux-home");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Could not create Linux home");
        }
        return root;
    }

    private File fileForId(String id) throws FileNotFoundException {
        try {
            File root = root().getCanonicalFile();
            if (HOME_ID.equals(id)) return root;
            if (id == null || !id.startsWith(HOME_ID + "/") || id.length() > 1024) {
                throw missing("Unknown document " + id, null);
            }
            String relative = id.substring(HOME_ID.length() + 1);
            for (String segment : relative.split("/")) {
                if (segment.isEmpty() || segment.startsWith(".")) {
                    throw missing("Private document " + id, null);
                }
            }
            File file = new File(root, relative).getCanonicalFile();
            if (!file.exists() || !file.getPath().startsWith(root.getPath() + File.separator)) {
                throw missing("Unknown document " + id, null);
            }
            return file;
        } catch (IOException error) {
            throw missing("Invalid document " + id, error);
        }
    }

    private String idForFile(File file) throws FileNotFoundException {
        try {
            String root = root().getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return HOME_ID;
            if (!path.startsWith(root + File.separator)) throw missing("Outside home", null);
            return HOME_ID + "/" + path.substring(root.length() + 1)
                    .replace(File.separatorChar, '/');
        } catch (IOException error) {
            throw missing("Invalid file", error);
        }
    }

    private static String parentId(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? HOME_ID : id.substring(0, slash);
    }

    private static String validName(String name) throws FileNotFoundException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.length() > 255 || name.startsWith(".")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw missing("Invalid display name", null);
        }
        return name;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return value == null ? "application/octet-stream" : value;
    }

    private static FileNotFoundException missing(String message, Exception cause) {
        FileNotFoundException error = new FileNotFoundException(message);
        if (cause != null) error.initCause(cause);
        return error;
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