package org.archphene.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.FileObserver;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class AndroidDocumentSession {
    private static final int MAX_DOCUMENTS = 32;
    private static final long MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L * 1024L;

    private static final class Binding {
        final Uri uri;
        final File file;
        final boolean writable;
        String remoteHash;
        String localHash;

        Binding(Uri uri, File file, boolean writable, String remoteHash) {
            this.uri = uri;
            this.file = file;
            this.writable = writable;
            this.remoteHash = remoteHash;
            this.localHash = remoteHash;
        }
    }

    private final Activity activity;
    private final String logTag;
    private final Object syncLock = new Object();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private FileObserver fileObserver;
    private boolean closed;

    public AndroidDocumentSession(Activity activity, String logTag) {
        this.activity = activity;
        this.logTag = logTag;
    }

    public static boolean isDocumentIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_EDIT.equals(action)) {
            return false;
        }
        return intent.getData() != null
                || (intent.getClipData() != null && intent.getClipData().getItemCount() > 0);
    }

    public List<File> importDocuments(Intent intent) {
        if (!isDocumentIntent(intent)) return Collections.emptyList();
        List<Uri> uris = documentUris(intent);
        if (uris.isEmpty()) return Collections.emptyList();
        File imports = new File(activity.getFilesDir(), "linux-home/Documents/Android");
        if (!imports.isDirectory() && !imports.mkdirs()) {
            Log.e(logTag, "Could not create Android document import directory");
            return Collections.emptyList();
        }
        int grantFlags = intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        ArrayList<File> result = new ArrayList<>();
        synchronized (syncLock) {
            if (closed) return Collections.emptyList();
            startFileObserver(imports);
            for (Uri uri : uris) {
                File imported = importOne(uri, imports, grantFlags, true, false);
                if (imported != null) result.add(imported);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<File> importDragDocuments(ClipData clip) {
        List<Uri> uris = clipUris(clip);
        if (uris.isEmpty()) return Collections.emptyList();
        File imports = new File(activity.getFilesDir(), "linux-home/Documents/Android");
        if (!imports.isDirectory() && !imports.mkdirs()) {
            Log.e(logTag, "Could not create Android drag import directory");
            return Collections.emptyList();
        }
        ArrayList<File> result = new ArrayList<>();
        synchronized (syncLock) {
            if (closed) return Collections.emptyList();
            startFileObserver(imports);
            LinkedHashSet<String> existing = new LinkedHashSet<>(bindings.keySet());
            for (Uri uri : uris) {
                File imported = importOne(uri, imports, 0, false, true);
                if (imported != null) result.add(imported);
            }
            if (result.size() != uris.size()) {
                ArrayList<String> added = new ArrayList<>(bindings.keySet());
                added.removeAll(existing);
                for (String name : added) {
                    Binding binding = bindings.remove(name);
                    if (binding != null && !binding.file.delete()) {
                        Log.w(logTag, "Could not roll back failed drag import " + name);
                    }
                }
                return Collections.emptyList();
            }
        }
        return Collections.unmodifiableList(result);
    }

    private File importOne(Uri uri, File imports, int grantFlags,
            boolean persistPermission, boolean detectWritable) {
        if (uri == null || (!"content".equals(uri.getScheme())
                && !"file".equals(uri.getScheme()))) {
            Log.w(logTag, "Ignoring unsupported Android document URI");
            return null;
        }
        if (persistPermission && "content".equals(uri.getScheme()) && grantFlags != 0) {
            try {
                activity.getContentResolver().takePersistableUriPermission(uri, grantFlags);
            } catch (SecurityException ignored) {
                Log.i(logTag, "Document provider supplied a temporary URI grant");
            }
        }
        for (Binding binding : bindings.values()) {
            if (binding.uri.equals(uri)) return binding.file;
        }
        File target = uniqueTarget(imports, safeDocumentName(queryDisplayName(uri)));
        File temporary = new File(imports, "." + target.getName() + ".import-"
                + Long.toHexString(System.nanoTime()));
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
                FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) throw new IOException("Document provider returned no input stream");
            String hash = copyAndHash(input, output);
            output.getFD().sync();
            if (!temporary.renameTo(target)) {
                throw new IOException("Could not publish imported document");
            }
            boolean writable = detectWritable
                    ? canWrite(uri)
                    : (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            Binding binding = new Binding(uri, target, writable, hash);
            bindings.put(target.getName(), binding);
            Log.i(logTag, "Imported Android document uri=" + uri + " path="
                    + target.getAbsolutePath() + " writable=" + binding.writable);
            return target;
        } catch (Exception error) {
            temporary.delete();
            Log.e(logTag, "Could not import Android document " + uri, error);
            return null;
        }
    }

    private boolean canWrite(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path != null && new File(path).canWrite();
        }
        try (android.os.ParcelFileDescriptor descriptor =
                activity.getContentResolver().openFileDescriptor(uri, "rw")) {
            return descriptor != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startFileObserver(File directory) {
        if (fileObserver != null) return;
        fileObserver = new FileObserver(directory.getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                if (path == null) return;
                synchronized (syncLock) {
                    if (!bindings.containsKey(path)) return;
                }
                new Thread(() -> sync(path), "archphene-document-writeback").start();
            }
        };
        fileObserver.startWatching();
    }

    public void runConflictProbe(List<File> imported) throws Exception {
        if (imported == null || imported.size() < 2) {
            throw new IllegalStateException("Conflict probe requires two imported documents");
        }
        File first = imported.get(0);
        File second = imported.get(1);
        if (first.getName().equals(second.getName())) {
            throw new IllegalStateException("Same-name document collision was not preserved");
        }
        byte[] androidEdit = "android-concurrent-edit\n".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] linuxEdit = "linux-conflict-winner\n".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        synchronized (syncLock) {
            Binding binding = bindings.get(first.getName());
            if (binding == null || !binding.writable) {
                throw new IllegalStateException("Conflict probe document is not writable");
            }
            writeUri(binding.uri, androidEdit);
            writeFile(binding.file, linuxEdit);
            sync(first.getName());
            if (!java.util.Arrays.equals(linuxEdit, readUri(binding.uri))) {
                throw new SecurityException("Linux edit was not written to Android document");
            }
            File[] conflicts = binding.file.getParentFile().listFiles((directory, name) ->
                    name.startsWith(binding.file.getName() + ".android-conflict-"));
            if (conflicts == null || conflicts.length != 1
                    || !java.util.Arrays.equals(androidEdit, readFile(conflicts[0]))) {
                throw new SecurityException("Concurrent Android edit was not preserved");
            }
            if (!conflicts[0].delete()) {
                throw new IOException("Could not remove conflict probe artifact");
            }
        }
        Log.i(logTag, "Document conflict probe passed documents=" + imported.size());
    }

    public void close() {
        synchronized (syncLock) {
            closed = true;
        }
        syncAll();
        synchronized (syncLock) {
            if (fileObserver != null) {
                fileObserver.stopWatching();
                fileObserver = null;
            }
            bindings.clear();
        }
    }

    public void syncAsyncIfDirty() {
        synchronized (syncLock) {
            if (closed || bindings.isEmpty()) return;
        }
        new Thread(this::syncAll, "archphene-document-sync").start();
    }

    public void syncAll() {
        List<String> names;
        synchronized (syncLock) {
            names = new ArrayList<>(bindings.keySet());
        }
        for (String name : names) sync(name);
    }

    private void sync(String name) {
        synchronized (syncLock) {
            Binding binding = bindings.get(name);
            if (binding == null || !binding.writable || !binding.file.isFile()) return;
            try {
                String localHash = hashFile(binding.file);
                if (localHash.equals(binding.localHash)) return;
                String remoteHash = hashUri(binding.uri);
                if (!remoteHash.equals(binding.remoteHash)) {
                    File conflict = conflictTarget(binding.file, remoteHash);
                    try (InputStream input = activity.getContentResolver()
                            .openInputStream(binding.uri);
                            FileOutputStream output = new FileOutputStream(conflict, false)) {
                        if (input == null) {
                            throw new IOException("Document provider returned no conflict input");
                        }
                        String copiedHash = copyAndHash(input, output);
                        output.getFD().sync();
                        if (!remoteHash.equals(copiedHash)) {
                            conflict.delete();
                            throw new IOException("Android document changed during conflict copy");
                        }
                    }
                    Log.w(logTag, "Preserved concurrent Android edit at "
                            + conflict.getAbsolutePath());
                }
                try (InputStream input = new BufferedInputStream(
                        new FileInputStream(binding.file));
                        OutputStream output = activity.getContentResolver()
                                .openOutputStream(binding.uri, "rwt")) {
                    if (output == null) {
                        throw new IOException("Document provider returned no output stream");
                    }
                    copyBounded(input, output);
                }
                binding.localHash = localHash;
                binding.remoteHash = localHash;
                Log.i(logTag, "Synced Linux document to Android uri=" + binding.uri
                        + " bytes=" + binding.file.length());
            } catch (Exception error) {
                Log.e(logTag, "Could not sync Linux document to Android "
                        + binding.uri, error);
            }
        }
    }

    private List<Uri> documentUris(Intent intent) {
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        if (intent.getData() != null) unique.add(intent.getData());
        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() > MAX_DOCUMENTS) {
            Log.w(logTag, "Ignoring document intent with too many items");
            return Collections.emptyList();
        }
        unique.addAll(clipUris(clip));
        if (unique.size() > MAX_DOCUMENTS) return Collections.emptyList();
        return new ArrayList<>(unique);
    }

    private List<Uri> clipUris(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return Collections.emptyList();
        if (clip.getItemCount() > MAX_DOCUMENTS) {
            Log.w(logTag, "Ignoring Android clip with too many documents");
            return Collections.emptyList();
        }
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        for (int index = 0; index < clip.getItemCount(); index++) {
            Uri uri = clip.getItemAt(index).getUri();
            if (uri == null) return Collections.emptyList();
            unique.add(uri);
        }
        return new ArrayList<>(unique);
    }

    private File uniqueTarget(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists() && !bindings.containsKey(name)) return target;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index <= 999; index++) {
            String candidate = stem + " (" + index + ")" + extension;
            target = new File(directory, candidate);
            if (!target.exists() && !bindings.containsKey(candidate)) return target;
        }
        throw new IllegalStateException("Could not allocate Android document name");
    }

    private File conflictTarget(File source, String remoteHash) {
        String suffix = ".android-conflict-" + remoteHash.substring(0, 12);
        File target = new File(source.getParentFile(), source.getName() + suffix);
        for (int index = 2; target.exists(); index++) {
            if (index > 999) throw new IllegalStateException("Could not allocate conflict name");
            target = new File(source.getParentFile(), source.getName() + suffix + "-" + index);
        }
        return target;
    }

    private String queryDisplayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = activity.getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            } catch (Exception error) {
                Log.w(logTag, "Could not query Android document name", error);
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "android-document.txt" : segment;
    }

    private static String safeDocumentName(String name) {
        String safe = name == null ? "android-document.txt"
                : name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..") || safe.startsWith(".")) {
            safe = "android-document.txt";
        }
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    private String hashUri(Uri uri) throws Exception {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Document provider returned no input stream");
            return hash(input);
        }
    }

    private void writeUri(Uri uri, byte[] value) throws IOException {
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "rwt")) {
            if (output == null) throw new IOException("Document provider returned no output stream");
            output.write(value);
        }
    }

    private static void writeFile(File file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Document provider returned no input stream");
            return readBounded(input);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return readBounded(input);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copyBounded(input, output);
        return output.toByteArray();
    }

    private static String hashFile(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return hash(input);
        }
    }

    private static String hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total = Math.addExact(total, count);
            if (total > MAX_DOCUMENT_BYTES) throw new IOException("Document is too large");
            digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String copyAndHash(InputStream input, FileOutputStream output)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total = Math.addExact(total, count);
            if (total > MAX_DOCUMENT_BYTES) throw new IOException("Document is too large");
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
        }
        output.flush();
        return hex(digest.digest());
    }

    private static void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total = Math.addExact(total, count);
            if (total > MAX_DOCUMENT_BYTES) throw new IOException("Document is too large");
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) result.append(String.format("%02x", part & 0xff));
        return result.toString();
    }
}