package org.archpheneos.manager;

import android.content.Context;
import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Restores Linux soname aliases for libraries packaged under Android-safe names. */
final class ManagerNativeRuntime {
    private static final int ENTRY_LIMIT = 512;
    private static File preparedRoot;

    private ManagerNativeRuntime() {}

    static synchronized File prepare(Context context) throws Exception {
        File nativeRoot = new File(context.getApplicationInfo().nativeLibraryDir)
                .getCanonicalFile();
        String asset = "package-runtime/manager-native-"
                + ArchRuntimePolicy.current().architecture + ".tsv";
        List<Entry> entries = readEntries(context, asset);
        MessageDigest identity = MessageDigest.getInstance("SHA-256");
        identity.update(asset.getBytes(StandardCharsets.UTF_8));
        identity.update((byte) 0);
        identity.update(nativeRoot.getPath().getBytes(StandardCharsets.UTF_8));
        for (Entry entry : entries) {
            identity.update((byte) 0);
            identity.update(entry.encoded().getBytes(StandardCharsets.UTF_8));
        }
        String directoryName = hex(identity.digest()).substring(0, 32);
        File parent = directory(new File(context.getFilesDir(), "package-runtime"),
                "manager-native");
        File root = new File(parent, directoryName).getCanonicalFile();
        if (!inside(parent, root)) throw new SecurityException("Invalid manager runtime root");
        if (root.equals(preparedRoot) && root.isDirectory()) return root;
        if (!valid(root, nativeRoot, entries)) {
            deleteRecursively(root);
            File staging = new File(parent, directoryName + ".tmp-"
                    + android.os.Process.myPid()).getCanonicalFile();
            if (!inside(parent, staging)) throw new SecurityException("Invalid staging root");
            deleteRecursively(staging);
            if (!staging.mkdir()) throw new IllegalStateException("Could not stage runtime links");
            try {
                for (Entry entry : entries) {
                    File source = verifiedSource(nativeRoot, entry);
                    File target = new File(staging, entry.logicalName);
                    Os.symlink(source.getPath(), target.getPath());
                }
                if (!staging.renameTo(root) && !valid(root, nativeRoot, entries)) {
                    throw new IllegalStateException("Could not publish manager runtime links");
                }
            } finally {
                deleteRecursively(staging);
            }
        }
        preparedRoot = root;
        cleanup(parent, root);
        return root;
    }

    static String libraryPath(Context context) throws Exception {
        File nativeRoot = new File(context.getApplicationInfo().nativeLibraryDir)
                .getCanonicalFile();
        return prepare(context).getPath() + File.pathSeparator + nativeRoot.getPath();
    }

    static File library(Context context, String logicalName) throws Exception {
        if (!safeName(logicalName)) throw new SecurityException("Unsafe runtime library name");
        File nativeRoot = new File(context.getApplicationInfo().nativeLibraryDir)
                .getCanonicalFile();
        File direct = new File(nativeRoot, logicalName).getCanonicalFile();
        if (direct.getParentFile().equals(nativeRoot) && direct.isFile()) return direct;
        File root = prepare(context);
        File link = new File(root, logicalName);
        if (!link.isFile()) throw new SecurityException("Manager runtime library is missing: "
                + logicalName);
        return link;
    }

    private static List<Entry> readEntries(Context context, String asset) throws Exception {
        ArrayList<Entry> result = new ArrayList<>();
        Set<String> logicalNames = new HashSet<>();
        Set<String> packagedNames = new HashSet<>();
        try (InputStream input = context.getAssets().open(asset);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 4 || result.size() >= ENTRY_LIMIT
                        || !safeName(fields[0]) || !androidLibraryName(fields[1])
                        || !fields[2].matches("[0-9a-f]{64}")) {
                    throw new SecurityException("Invalid manager runtime catalog");
                }
                long size;
                try {
                    size = Long.parseLong(fields[3]);
                } catch (NumberFormatException error) {
                    throw new SecurityException("Invalid manager runtime size", error);
                }
                if (size <= 0 || size > 256L * 1024 * 1024
                        || !logicalNames.add(fields[0]) || !packagedNames.add(fields[1])) {
                    throw new SecurityException("Duplicate or invalid manager runtime entry");
                }
                result.add(new Entry(fields[0], fields[1], fields[2], size));
            }
        }
        if (result.isEmpty()) throw new SecurityException("Manager runtime catalog is empty");
        return result;
    }

    private static boolean valid(File root, File nativeRoot, List<Entry> entries) {
        if (!root.isDirectory()) return false;
        try {
            for (Entry entry : entries) {
                File expected = verifiedSource(nativeRoot, entry);
                File link = new File(root, entry.logicalName);
                if (!link.isFile() || !link.getCanonicalFile().equals(expected)) return false;
            }
            File[] files = root.listFiles();
            return files != null && files.length == entries.size();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static File verifiedSource(File nativeRoot, Entry entry) throws Exception {
        File source = new File(nativeRoot, entry.packagedName).getCanonicalFile();
        if (!source.getParentFile().equals(nativeRoot) || !source.isFile()
                || source.length() != entry.size || !entry.hash.equals(sha256(source))) {
            throw new SecurityException("Packaged manager runtime library failed verification");
        }
        return source;
    }

    private static void cleanup(File parent, File active) {
        File[] entries = parent.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.equals(active)) deleteRecursively(entry);
        }
    }

    private static File directory(File parent, String name) throws Exception {
        File result = new File(parent, name).getCanonicalFile();
        if (!result.isDirectory() && !result.mkdirs()) {
            throw new IllegalStateException("Could not create manager runtime directory");
        }
        return result;
    }

    private static boolean inside(File parent, File child) throws Exception {
        File canonicalParent = parent.getCanonicalFile();
        File canonicalChild = child.getCanonicalFile();
        return canonicalChild.getPath().startsWith(canonicalParent.getPath() + File.separator);
    }

    private static boolean safeName(String value) {
        return value != null && value.matches("[A-Za-z0-9@._+-]{1,128}")
                && !value.equals(".") && !value.equals("..");
    }

    private static boolean androidLibraryName(String value) {
        return value != null && value.matches("lib[A-Za-z0-9_]+\\.so");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class Entry {
        final String logicalName;
        final String packagedName;
        final String hash;
        final long size;

        Entry(String logicalName, String packagedName, String hash, long size) {
            this.logicalName = logicalName;
            this.packagedName = packagedName;
            this.hash = hash;
            this.size = size;
        }

        String encoded() {
            return logicalName + "\t" + packagedName + "\t" + hash + "\t" + size;
        }
    }
}
