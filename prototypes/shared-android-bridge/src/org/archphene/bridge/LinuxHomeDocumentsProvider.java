package org.archphene.bridge;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class LinuxHomeDocumentsProvider extends DocumentsProvider {
    private static final String ROOT_ID = "archphene-home";
    private static final String HOME_ID = "home";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor rows = new MatrixCursor(rootProjection(projection));
        rows.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, HOME_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, "Archphene Home")
                .add(DocumentsContract.Root.COLUMN_SUMMARY, "Linux app documents")
                .add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                                | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        return rows;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(documentProjection(projection));
        include(rows, documentId, fileForId(documentId));
        return rows;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(documentProjection(projection));
        File[] children = fileForId(parentId).listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.getName().startsWith(".")) include(rows, idForFile(child), child);
            }
        }
        return rows;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileForId(documentId),
                ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File child = new File(fileForId(parentId), validName(displayName));
        try {
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? child.mkdir() : child.createNewFile();
            if (!created) throw new IOException("already exists");
            return idForFile(child);
        } catch (IOException e) {
            throw missing("Could not create " + displayName, e);
        }
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (HOME_ID.equals(documentId)) throw missing("Cannot delete home", null);
        if (!fileForId(documentId).delete()) throw missing("Could not delete " + documentId, null);
    }

    @Override public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        if (HOME_ID.equals(documentId)) throw missing("Cannot rename home", null);
        File source = fileForId(documentId);
        File target = new File(source.getParentFile(), validName(displayName));
        if (!source.renameTo(target)) throw missing("Could not rename " + documentId, null);
        return idForFile(target);
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        try {
            File parent = fileForId(parentId);
            File child = fileForId(documentId);
            return child.equals(parent) || child.getPath().startsWith(parent.getPath() + File.separator);
        } catch (FileNotFoundException e) {
            return false;
        }
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
                        directory ? DocumentsContract.Document.MIME_TYPE_DIR : mime(file.getName()))
                .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                .add(DocumentsContract.Document.COLUMN_SIZE, directory ? null : file.length())
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private File root() {
        File root = new File(getContext().getFilesDir(), "linux-home");
        root.mkdirs();
        return root;
    }

    private File fileForId(String id) throws FileNotFoundException {
        try {
            File root = root().getCanonicalFile();
            if (HOME_ID.equals(id)) return root;
            if (!id.startsWith(HOME_ID + "/")) throw missing("Unknown document " + id, null);
            String relative = id.substring(HOME_ID.length() + 1);
            for (String segment : relative.split("/")) {
                if (segment.isEmpty() || segment.startsWith("."))
                    throw missing("Private document " + id, null);
            }
            File file = new File(root, relative).getCanonicalFile();
            if (!file.exists() || !file.getPath().startsWith(root.getPath() + File.separator))
                throw missing("Unknown document " + id, null);
            return file;
        } catch (IOException e) {
            throw missing("Invalid document " + id, e);
        }
    }

    private String idForFile(File file) throws FileNotFoundException {
        try {
            String root = root().getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return HOME_ID;
            if (!path.startsWith(root + File.separator)) throw missing("Outside home", null);
            return HOME_ID + "/" + path.substring(root.length() + 1).replace(File.separatorChar, '/');
        } catch (IOException e) {
            throw missing("Invalid file", e);
        }
    }

    private static String validName(String name) throws FileNotFoundException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw missing("Invalid display name", null);
        return name;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return value == null ? "application/octet-stream" : value;
    }

    private static FileNotFoundException missing(String message, Exception cause) {
        FileNotFoundException error = new FileNotFoundException(message);
        if (cause != null) error.initCause(cause);
        return error;
    }

    private static String[] rootProjection(String[] value) {
        return value != null ? value : new String[] {
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES };
    }

    private static String[] documentProjection(String[] value) {
        return value != null ? value : new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    }
}
