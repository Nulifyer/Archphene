package org.archpheneos.manager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ArchWrapperAssembler {
    private static final String QT_TEMPLATE = "package-runtime/qt-wrapper-template.apk";
    private static final String PACKAGE_PLACEHOLDER =
            "org.archphene.linux.p00000000000000000000000000000000";
    private static final int ENTRY_LIMIT = 256 * 1024 * 1024;
    private static final long ZIP_EPOCH_MILLIS = 1577836800000L;

    public static final class Result {
        public final String packageName;
        public final File apk;
        public final String apkSha256;
        public final String signerSha256;

        Result(String packageName, File apk, String apkSha256, String signerSha256) {
            this.packageName = packageName;
            this.apk = apk;
            this.apkSha256 = apkSha256;
            this.signerSha256 = signerSha256;
        }
    }

    private ArchWrapperAssembler() {}

    public static Result assembleQt(Context context, String repository, String sourcePackage)
            throws Exception {
        return assembleQt(context, repository, sourcePackage, null);
    }

    public static Result assembleQt(Context context, String repository, String sourcePackage,
            File runtimeRoot) throws Exception {
        if (!repository.matches("[a-z0-9-]{1,32}")
                || !sourcePackage.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid wrapper source identity");
        }
        String packageName = packageNameFor(repository, sourcePackage);
        File output = new File(context.getCacheDir(), "generated-" + sourcePackage + ".apk");
        File unsigned = new File(context.getCacheDir(), "generated-" + sourcePackage + ".unsigned.apk");
        rebuildTemplate(context, packageName, sourcePackage, runtimeRoot, unsigned);
        verifyStoredEntryAlignment(unsigned, "resources.arsc", 4);
        ArchWrapperSigner.Result signed = ArchWrapperSigner.sign(context, unsigned, output);
        PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(output.getPath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (parsed == null || !packageName.equals(parsed.packageName)) {
            output.delete();
            throw new SecurityException("Generated wrapper package identity mismatch");
        }
        unsigned.delete();
        return new Result(packageName, output, sha256(output), signed.signerSha256);
    }

    static String packageNameFor(String repository, String sourcePackage) throws Exception {
        if (repository == null || sourcePackage == null
                || !repository.matches("[a-z0-9-]{1,32}")
                || !sourcePackage.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid wrapper source identity");
        }
        return "org.archphene.linux.p"
                + sha256((repository + "/" + sourcePackage).getBytes(StandardCharsets.UTF_8))
                        .substring(0, 32);
    }
    private static void rebuildTemplate(Context context, String packageName, String sourcePackage,
            File runtimeRoot, File output) throws Exception {
        byte[] placeholder = PACKAGE_PLACEHOLDER.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = packageName.getBytes(StandardCharsets.UTF_8);
        byte[] placeholderUtf16 = PACKAGE_PLACEHOLDER.getBytes(StandardCharsets.UTF_16LE);
        byte[] replacementUtf16 = packageName.getBytes(StandardCharsets.UTF_16LE);
        if (placeholder.length != replacement.length
                || placeholderUtf16.length != replacementUtf16.length) {
            throw new IllegalStateException("Generated package identity has invalid length");
        }
        boolean patched = false;
        try (InputStream raw = context.getAssets().open(QT_TEMPLATE);
                ZipInputStream input = new ZipInputStream(raw);
                CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(output));
                ZipOutputStream zip = new ZipOutputStream(counted)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;
                byte[] value = readEntry(input);
                if ("AndroidManifest.xml".equals(name)) {
                    int replacements = replaceAll(value, placeholder, replacement)
                            + replaceAll(value, placeholderUtf16, replacementUtf16);
                    if (replacements < 1) throw new SecurityException("Wrapper template package marker is missing");
                    patched = true;
                }
                ZipEntry next = new ZipEntry(name);
                next.setTime(ZIP_EPOCH_MILLIS);
                if (entry.getMethod() == ZipEntry.STORED || "resources.arsc".equals(name)) {
                    CRC32 crc = new CRC32();
                    crc.update(value);
                    next.setMethod(ZipEntry.STORED);
                    next.setSize(value.length);
                    next.setCompressedSize(value.length);
                    next.setCrc(crc.getValue());
                    alignStoredEntry(next, counted.count(), name, 4);
                }
                zip.putNextEntry(next);
                zip.write(value);
                zip.closeEntry();
            }
            if (runtimeRoot != null) {
                for (Map.Entry<String, File> nativeFile : collectNativeFiles(
                        context, runtimeRoot, sourcePackage).entrySet()) {
                    ZipEntry next = new ZipEntry("lib/x86_64/" + nativeFile.getKey());
                    next.setTime(ZIP_EPOCH_MILLIS);
                    zip.putNextEntry(next);
                    try (InputStream file = new FileInputStream(nativeFile.getValue())) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = file.read(buffer)) != -1) zip.write(buffer, 0, read);
                    }
                    zip.closeEntry();
                }
            }
        }
        if (!patched) {
            output.delete();
            throw new SecurityException("Wrapper template manifest is missing");
        }
    }

    static Map<String, File> collectNativeFiles(Context context, File runtimeRoot,
            String sourcePackage) throws Exception {
        File canonicalRoot = runtimeRoot.getCanonicalFile();
        File libraryRoot = new File(canonicalRoot, "usr/lib").getCanonicalFile();
        File executable = new File(canonicalRoot, "usr/bin/" + sourcePackage).getCanonicalFile();
        if (!libraryRoot.getPath().startsWith(canonicalRoot.getPath() + File.separator)
                || !executable.isFile()) {
            throw new SecurityException("Invalid staged runtime root");
        }
        TreeMap<String, File> candidates = new TreeMap<>();
        collectElfFiles(canonicalRoot, libraryRoot, candidates, new HashSet<>());
        TreeMap<String, File> result = new TreeMap<>();
        TreeMap<String, File> patched = new TreeMap<>();
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        for (String name : new String[] {"libarchphene_ld.so", "libc.so.6", "libm.so.6",
                "libdl.so.2", "libpthread.so.0", "librt.so.1", "libresolv.so.2",
                "libutil.so.1", "libanl.so.1", "libnss_dns.so.2", "libnss_files.so.2"}) {
            File value = new File(nativeDir, name);
            if (value.isFile()) {
                patched.put(name, value.getCanonicalFile());
                result.put(name, value.getCanonicalFile());
            }
        }
        ArrayDeque<File> pending = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        addNative(result, "libarchphene_kcalc.so", executable);
        pending.add(executable);
        for (String pluginName : new String[] {"libqwayland.so", "libxdg-shell.so"}) {
            File plugin = candidates.get(pluginName);
            if (plugin == null) {
                throw new SecurityException("Missing Qt bridge plugin " + pluginName);
            }
            addNative(result, pluginName, plugin);
            pending.add(plugin);
        }
        while (!pending.isEmpty()) {
            File current = pending.removeFirst().getCanonicalFile();
            if (!visited.add(current.getPath())) continue;
            for (String needed : elfNeeded(current)) {
                if (result.containsKey(needed)) continue;
                File dependency = candidates.get(needed);
                if (dependency == null) {
                    throw new SecurityException("Missing ELF dependency " + needed
                            + " required by " + current.getName());
                }
                addNative(result, needed, dependency);
                pending.add(dependency);
            }
        }
        return result;
    }

    private static void collectElfFiles(File root, File directory, Map<String, File> result,
            Set<String> visited) throws Exception {
        File canonical = directory.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)
                || !visited.add(canonical.getPath())) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = child.getCanonicalFile();
            if (!target.getPath().startsWith(root.getPath() + File.separator)) {
                throw new SecurityException("Runtime symlink escapes package root");
            }
            if (target.isDirectory()) {
                collectElfFiles(root, target, result, visited);
            } else if (target.isFile() && isElf(target)) {
                addNative(result, child.getName(), target);
            }
        }
    }

    private static Set<String> elfNeeded(File file) throws Exception {
        HashSet<String> result = new HashSet<>();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] ident = new byte[16];
            input.readFully(ident);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F'
                    || ident[4] != 2 || ident[5] != 1) {
                throw new SecurityException("Unsupported ELF format: " + file.getName());
            }
            input.seek(0x28);
            long sectionOffset = readLongLe(input);
            input.seek(0x3a);
            int sectionEntrySize = readUnsignedShortLe(input);
            int sectionCount = readUnsignedShortLe(input);
            if (sectionOffset <= 0 || sectionEntrySize < 64 || sectionCount <= 0
                    || sectionOffset + (long) sectionEntrySize * sectionCount > input.length()) {
                throw new SecurityException("Invalid ELF section table: " + file.getName());
            }
            long dynamicOffset = -1;
            long dynamicSize = 0;
            int stringSection = -1;
            for (int index = 0; index < sectionCount; index++) {
                long header = sectionOffset + (long) index * sectionEntrySize;
                input.seek(header + 4);
                int type = readIntLe(input);
                if (type != 6) continue;
                input.seek(header + 24);
                dynamicOffset = readLongLe(input);
                dynamicSize = readLongLe(input);
                input.seek(header + 40);
                stringSection = readIntLe(input);
                break;
            }
            if (dynamicOffset < 0) return result;
            if (stringSection < 0 || stringSection >= sectionCount
                    || dynamicOffset + dynamicSize > input.length()) {
                throw new SecurityException("Invalid ELF dynamic table: " + file.getName());
            }
            long stringHeader = sectionOffset + (long) stringSection * sectionEntrySize;
            input.seek(stringHeader + 24);
            long stringOffset = readLongLe(input);
            long stringSize = readLongLe(input);
            if (stringOffset < 0 || stringOffset + stringSize > input.length()) {
                throw new SecurityException("Invalid ELF string table: " + file.getName());
            }
            for (long offset = 0; offset + 16 <= dynamicSize; offset += 16) {
                input.seek(dynamicOffset + offset);
                long tag = readLongLe(input);
                long value = readLongLe(input);
                if (tag == 0) break;
                if (tag != 1) continue;
                if (value < 0 || value >= stringSize) {
                    throw new SecurityException("Invalid ELF dependency name offset");
                }
                input.seek(stringOffset + value);
                StringBuilder name = new StringBuilder();
                int part;
                while ((part = input.read()) > 0 && name.length() <= 255) {
                    name.append((char) part);
                }
                if (part != 0 || !name.toString().matches("[a-zA-Z0-9@._+-]{1,255}")) {
                    throw new SecurityException("Invalid ELF dependency name");
                }
                result.add(name.toString());
            }
        }
        return result;
    }

    private static int readUnsignedShortLe(RandomAccessFile input) throws Exception {
        int low = input.readUnsignedByte();
        return low | input.readUnsignedByte() << 8;
    }

    private static int readIntLe(RandomAccessFile input) throws Exception {
        return input.readUnsignedByte() | input.readUnsignedByte() << 8
                | input.readUnsignedByte() << 16 | input.readUnsignedByte() << 24;
    }

    private static long readLongLe(RandomAccessFile input) throws Exception {
        return Integer.toUnsignedLong(readIntLe(input))
                | Integer.toUnsignedLong(readIntLe(input)) << 32;
    }
    private static void addNative(Map<String, File> result, String name, File file)
            throws Exception {
        if (!name.matches("[a-zA-Z0-9@._+-]{1,255}")) {
            throw new SecurityException("Invalid native runtime filename");
        }
        File previous = result.get(name);
        if (previous != null && !previous.getCanonicalFile().equals(file.getCanonicalFile())) {
            throw new SecurityException("Conflicting flattened runtime filename: " + name);
        }
        result.put(name, file.getCanonicalFile());
    }

    private static boolean isElf(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return input.read() == 0x7f && input.read() == 'E'
                    && input.read() == 'L' && input.read() == 'F';
        }
    }

    private static void verifyStoredEntryAlignment(File archive, String target, int alignment)
            throws Exception {
        if (!archive.isFile() || archive.length() <= 0 || archive.length() > 512L * 1024 * 1024) {
            throw new SecurityException("Generated wrapper archive exceeds bounds");
        }
        byte[] expected = target.getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            long limit = input.length() - 30;
            for (long offset = 0; offset <= limit; offset++) {
                input.seek(offset);
                if (readUnsignedIntLe(input) != 0x04034b50L) continue;
                input.skipBytes(4);
                int method = readUnsignedShortLe(input);
                input.skipBytes(16);
                int nameLength = readUnsignedShortLe(input);
                int extraLength = readUnsignedShortLe(input);
                if (nameLength != expected.length || nameLength > 4096 || extraLength > 65535) {
                    continue;
                }
                byte[] name = new byte[nameLength];
                input.readFully(name);
                if (!java.util.Arrays.equals(name, expected)) continue;
                long dataOffset = offset + 30L + nameLength + extraLength;
                if (method != ZipEntry.STORED || dataOffset % alignment != 0) {
                    throw new SecurityException("Generated wrapper entry is not safely aligned");
                }
                return;
            }
        }
        throw new SecurityException("Generated wrapper resources entry is missing");
    }

    private static long readUnsignedIntLe(RandomAccessFile input) throws Exception {
        return (long) input.readUnsignedByte()
                | (long) input.readUnsignedByte() << 8
                | (long) input.readUnsignedByte() << 16
                | (long) input.readUnsignedByte() << 24;
    }

    private static void alignStoredEntry(ZipEntry entry, long offset, String name, int alignment) {
        int nameLength = name.getBytes(StandardCharsets.UTF_8).length;
        int payload = (int) ((alignment - ((offset + 30 + nameLength + 4) % alignment)) % alignment);
        byte[] extra = new byte[4 + payload];
        extra[0] = 0x35;
        extra[1] = (byte) 0xd9;
        extra[2] = (byte) (payload & 0xff);
        extra[3] = (byte) ((payload >>> 8) & 0xff);
        entry.setExtra(extra);
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream output) { super(output); }

        long count() { return count; }

        @Override
        public void write(int value) throws java.io.IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws java.io.IOException {
            out.write(value, offset, length);
            count += length;
        }
    }

    private static byte[] readEntry(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > ENTRY_LIMIT) throw new SecurityException("Wrapper template entry is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static int replaceAll(byte[] value, byte[] match, byte[] replacement) {
        int count = 0;
        for (int offset = 0; offset <= value.length - match.length; offset++) {
            boolean equal = true;
            for (int index = 0; index < match.length; index++) {
                if (value[offset + index] != match[index]) { equal = false; break; }
            }
            if (!equal) continue;
            System.arraycopy(replacement, 0, value, offset, replacement.length);
            count++;
            offset += match.length - 1;
        }
        return count;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder();
        for (byte part : value) output.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        return output.toString();
    }
}