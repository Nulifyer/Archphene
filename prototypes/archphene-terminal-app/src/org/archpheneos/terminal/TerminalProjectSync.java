package org.archpheneos.terminal;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.webkit.MimeTypeMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Non-destructive two-way synchronization between a SAF tree and a local POSIX mirror. */
final class TerminalProjectSync {
    interface Progress {
        void report(int percent, String status) throws Exception;
    }

    static final class Result {
        final int pulled;
        final int pushed;
        final int conflicts;
        final int deferredDeletes;

        Result(int pulled, int pushed, int conflicts, int deferredDeletes) {
            this.pulled = pulled;
            this.pushed = pushed;
            this.conflicts = conflicts;
            this.deferredDeletes = deferredDeletes;
        }

        String summary() {
            return "synced " + (pulled + pushed) + " file(s): " + pulled + " pulled, "
                    + pushed + " pushed, " + conflicts + " conflict(s), "
                    + deferredDeletes + " deletion(s) deferred";
        }
    }

    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_DEPTH = 64;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR;

    private final ContentResolver resolver;
    private int entries;
    private long bytes;

    TerminalProjectSync(ContentResolver resolver) {
        this.resolver = resolver;
    }

    Result synchronize(TerminalProjectStore.Project project, File manifest, Progress progress)
            throws Exception {
        entries = 0;
        bytes = 0;
        progress.report(5, "scanning Android folder");
        Map<String, RemoteEntry> remote = scanRemote(project.treeUri);
        entries = 0;
        bytes = 0;
        progress.report(25, "scanning local project mirror");
        Map<String, LocalEntry> local = scanLocal(project.mirror);
        Map<String, String> previous = readManifest(manifest);

        ArrayList<String> paths = new ArrayList<>();
        paths.addAll(remote.keySet());
        for (String path : local.keySet()) if (!remote.containsKey(path)) paths.add(path);
        Collections.sort(paths);

        int pulled = 0;
        int pushed = 0;
        int conflicts = 0;
        int deferredDeletes = 0;
        HashSet<String> unresolved = new HashSet<>();
        int complete = 0;
        for (String path : paths) {
            RemoteEntry remoteEntry = remote.get(path);
            LocalEntry localEntry = local.get(path);
            if ((remoteEntry != null && remoteEntry.directory)
                    || (localEntry != null && localEntry.directory)) {
                if (remoteEntry != null && localEntry != null
                        && remoteEntry.directory != localEntry.directory) {
                    throw new IOException("file/directory conflict at " + path);
                }
                if (remoteEntry == null) ensureRemoteDirectory(project.treeUri, remote, path);
                if (localEntry == null) ensureLocalDirectory(project.mirror, path);
                continue;
            }
            String oldHash = previous.get(path);
            String remoteHash = remoteEntry == null ? null : hashRemote(remoteEntry);
            String localHash = localEntry == null ? null : hashLocal(localEntry.file);

            if (remoteEntry == null && localEntry != null) {
                if (oldHash == null) {
                    push(project.treeUri, remote, path, localEntry.file);
                    pushed++;
                } else {
                    deferredDeletes++;
                    unresolved.add(path);
                }
            } else if (localEntry == null && remoteEntry != null) {
                if (oldHash == null) {
                    pull(project.mirror, path, remoteEntry);
                    pulled++;
                } else {
                    deferredDeletes++;
                    unresolved.add(path);
                }
            } else if (remoteEntry != null && localEntry != null
                    && !remoteHash.equals(localHash)) {
                boolean remoteChanged = oldHash == null || !oldHash.equals(remoteHash);
                boolean localChanged = oldHash == null || !oldHash.equals(localHash);
                if (oldHash != null && remoteChanged && !localChanged) {
                    pull(project.mirror, path, remoteEntry);
                    pulled++;
                } else if (oldHash != null && localChanged && !remoteChanged) {
                    writeRemote(remoteEntry.uri, localEntry.file);
                    pushed++;
                } else {
                    String conflictPath = conflictPath(path, remoteHash, remote, local);
                    if (conflictPath != null) pull(project.mirror, conflictPath, remoteEntry);
                    conflicts++;
                    unresolved.add(path);
                }
            }
            complete++;
            if (complete % 25 == 0 && !paths.isEmpty()) {
                progress.report(30 + (int) (60L * complete / paths.size()),
                        "synchronizing " + complete + " of " + paths.size());
            }
        }

        progress.report(92, "recording project state");
        entries = 0;
        bytes = 0;
        Map<String, String> snapshot = snapshotHashes(project, remote, unresolved);
        for (String path : unresolved) {
            String oldHash = previous.get(path);
            if (oldHash != null) snapshot.put(path, oldHash);
        }
        writeManifest(manifest, snapshot);
        return new Result(pulled, pushed, conflicts, deferredDeletes);
    }

    private Map<String, RemoteEntry> scanRemote(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        LinkedHashMap<String, RemoteEntry> result = new LinkedHashMap<>();
        scanRemoteChildren(treeUri, rootId, "", 0, result);
        return result;
    }

    private void scanRemoteChildren(Uri treeUri, String parentId, String prefix, int depth,
            Map<String, RemoteEntry> result) throws Exception {
        if (depth > MAX_DEPTH) throw new SecurityException("project tree exceeds maximum depth");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<RemoteEntry> directories = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("document provider returned no project listing");
            while (cursor.moveToNext()) {
                if (++entries > MAX_ENTRIES) throw new SecurityException(
                        "project exceeds " + MAX_ENTRIES + " entries");
                String id = cursor.getString(0);
                String name = requireDocumentName(cursor.getString(1));
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : Math.max(0, cursor.getLong(3));
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                if (result.containsKey(path)) {
                    throw new SecurityException("document provider returned duplicate path " + path);
                }
                RemoteEntry entry = new RemoteEntry(path, id,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id), mime, size,
                        DIRECTORY_MIME.equals(mime));
                result.put(path, entry);
                if (entry.directory) directories.add(entry);
                else addBytes(size);
            }
        }
        for (RemoteEntry directory : directories) {
            scanRemoteChildren(treeUri, directory.documentId, directory.path,
                    depth + 1, result);
        }
    }

    private Map<String, LocalEntry> scanLocal(File root) throws Exception {
        LinkedHashMap<String, LocalEntry> result = new LinkedHashMap<>();
        scanLocalChildren(root.getCanonicalFile(), root.getCanonicalFile(), "", 0, result);
        return result;
    }

    private void scanLocalChildren(File root, File directory, String prefix, int depth,
            Map<String, LocalEntry> result) throws Exception {
        if (depth > MAX_DEPTH) throw new SecurityException("local project exceeds maximum depth");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("could not list local project " + prefix);
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (++entries > MAX_ENTRIES) throw new SecurityException(
                    "project exceeds " + MAX_ENTRIES + " entries per side");
            StructStat stat = Os.lstat(child.getAbsolutePath());
            if ((stat.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK) {
                throw new SecurityException("symbolic links are not supported in project mirrors: "
                        + child.getName());
            }
            File canonical = child.getCanonicalFile();
            requireWithin(root, canonical);
            String path = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                result.put(path, new LocalEntry(canonical, true));
                scanLocalChildren(root, canonical, path, depth + 1, result);
            } else if (child.isFile()) {
                addBytes(child.length());
                result.put(path, new LocalEntry(canonical, false));
            } else {
                throw new SecurityException("unsupported local project entry: " + path);
            }
        }
    }

    private Map<String, String> snapshotHashes(TerminalProjectStore.Project project,
            Map<String, RemoteEntry> remote, Set<String> excluded) throws Exception {
        Map<String, LocalEntry> local = scanLocal(project.mirror);
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        ArrayList<String> paths = new ArrayList<>(local.keySet());
        Collections.sort(paths);
        for (String path : paths) {
            LocalEntry entry = local.get(path);
            if (!entry.directory && remote.containsKey(path) && !excluded.contains(path)) {
                hashes.put(path, hashLocal(entry.file));
            }
        }
        return hashes;
    }

    private void pull(File root, String path, RemoteEntry source) throws Exception {
        File destination = localPath(root, path);
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("could not create local folder");
        File staging = File.createTempFile(".archphene-sync-", ".tmp", parent);
        boolean published = false;
        try (InputStream input = resolver.openInputStream(source.uri);
                OutputStream output = new BufferedOutputStream(new FileOutputStream(staging))) {
            if (input == null) throw new IOException("document provider returned no input stream");
            copyBounded(input, output);
            Os.rename(staging.getAbsolutePath(), destination.getAbsolutePath());
            published = true;
        } finally {
            if (!published) staging.delete();
        }
    }

    private RemoteEntry push(Uri treeUri, Map<String, RemoteEntry> remote, String path,
            File source) throws Exception {
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        Uri parent = ensureRemoteDirectory(treeUri, remote, parentPath);
        Uri target = DocumentsContract.createDocument(resolver, parent, mime(name), name);
        if (target == null) throw new IOException("document provider could not create " + path);
        try {
            writeRemote(target, source);
        } catch (Exception error) {
            try {
                DocumentsContract.deleteDocument(resolver, target);
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        String id = DocumentsContract.getDocumentId(target);
        RemoteEntry entry = new RemoteEntry(path, id, target, mime(name), source.length(), false);
        remote.put(path, entry);
        return entry;
    }

    private void writeRemote(Uri target, File source) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                OutputStream output = resolver.openOutputStream(target, "rwt")) {
            if (output == null) throw new IOException("document provider returned no output stream");
            copyBounded(input, output);
        }
    }

    private Uri ensureRemoteDirectory(Uri treeUri, Map<String, RemoteEntry> remote,
            String path) throws Exception {
        if (path.isEmpty()) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
        }
        RemoteEntry existing = remote.get(path);
        if (existing != null) {
            if (!existing.directory) throw new IOException("project path is not a directory: " + path);
            return existing.uri;
        }
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        Uri parent = ensureRemoteDirectory(treeUri, remote, parentPath);
        Uri created = DocumentsContract.createDocument(resolver, parent, DIRECTORY_MIME, name);
        if (created == null) throw new IOException("document provider could not create " + path);
        String id = DocumentsContract.getDocumentId(created);
        remote.put(path, new RemoteEntry(path, id, created, DIRECTORY_MIME, 0, true));
        return created;
    }

    private static void ensureLocalDirectory(File root, String path) throws Exception {
        File directory = localPath(root, path);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create local directory " + path);
        }
    }

    private static File localPath(File root, String path) throws Exception {
        File result = new File(root, path.replace('/', File.separatorChar)).getCanonicalFile();
        requireWithin(root.getCanonicalFile(), result);
        return result;
    }

    private static void requireWithin(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("project entry escaped its mirror");
        }
    }

    private String hashRemote(RemoteEntry entry) throws Exception {
        try (InputStream input = resolver.openInputStream(entry.uri)) {
            if (input == null) throw new IOException("document provider returned no input stream");
            return hash(input, entry.size);
        }
    }

    private static String hashLocal(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return hash(input, file.length());
        }
    }

    private static String hash(InputStream input, long expectedSize) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > MAX_FILE_BYTES) throw new IOException("project file exceeds 2 GiB");
            digest.update(buffer, 0, count);
        }
        if (expectedSize > 0 && total != expectedSize) {
            throw new IOException("project file changed while it was being read");
        }
        StringBuilder value = new StringBuilder(64);
        for (byte part : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        return value.toString();
    }

    private static Map<String, String> readManifest(File file) throws Exception {
        if (!file.isFile()) return Collections.emptyMap();
        if (file.length() > 2 * 1024 * 1024) throw new SecurityException("project manifest is too large");
        byte[] data = new byte[(int) file.length()];
        int offset = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        if (offset != data.length) throw new IOException("could not read complete project manifest");
        JSONObject object = new JSONObject(new String(data, StandardCharsets.UTF_8));
        HashMap<String, String> result = new HashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String path = keys.next();
            String hash = object.getString(path);
            if (hash.matches("[0-9a-f]{64}")) result.put(path, hash);
        }
        return result;
    }

    private static void writeManifest(File file, Map<String, String> hashes) throws Exception {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, String> entry : hashes.entrySet()) object.put(entry.getKey(), entry.getValue());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(object.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Os.rename(temporary.getAbsolutePath(), file.getAbsolutePath());
    }

    private String conflictPath(String path, String remoteHash,
            Map<String, RemoteEntry> remote, Map<String, LocalEntry> local) throws Exception {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = slash < 0 ? path : path.substring(slash + 1);
        String base = parent + name + ".android-conflict-" + remoteHash.substring(0, 12);
        for (int index = 0; index < 1000; index++) {
            String candidate = base + (index == 0 ? "" : "-" + index);
            LocalEntry localEntry = local.get(candidate);
            if (localEntry != null && !localEntry.directory
                    && remoteHash.equals(hashLocal(localEntry.file))) return null;
            if (!remote.containsKey(candidate) && localEntry == null) return candidate;
        }
        throw new IllegalStateException("could not allocate project conflict name");
    }
    private void addBytes(long value) {
        if (value > MAX_FILE_BYTES) throw new SecurityException("project file exceeds 2 GiB");
        if (value < 0 || value > MAX_TOTAL_BYTES - bytes) {
            throw new SecurityException("project exceeds the 2 GiB per-side limit");
        }
        bytes += value;
    }

    private static void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > MAX_FILE_BYTES) throw new IOException("project file exceeds 2 GiB");
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static String requireDocumentName(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)
                || value.length() > 255 || value.indexOf('/') >= 0 || value.indexOf((char) 0) >= 0) {
            throw new SecurityException("document provider returned an unsafe name");
        }
        return value;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return value == null ? "application/octet-stream" : value;
    }

    private static final class RemoteEntry {
        final String path;
        final String documentId;
        final Uri uri;
        final String mime;
        final long size;
        final boolean directory;

        RemoteEntry(String path, String documentId, Uri uri, String mime, long size,
                boolean directory) {
            this.path = path;
            this.documentId = documentId;
            this.uri = uri;
            this.mime = mime;
            this.size = size;
            this.directory = directory;
        }
    }

    private static final class LocalEntry {
        final File file;
        final boolean directory;

        LocalEntry(File file, boolean directory) {
            this.file = file;
            this.directory = directory;
        }
    }
}
