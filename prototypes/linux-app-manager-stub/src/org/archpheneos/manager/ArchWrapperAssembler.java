package org.archpheneos.manager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    private static final String QT_DOCUMENT_TEMPLATE =
            "package-runtime/qt-document-wrapper-template.apk";
    private static final int MIME_SLOT_COUNT = 16;
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
        return assembleQt(context, repository, sourcePackage, "0", currentArchitecture(), "qt6", sourcePackage,
                displayName(sourcePackage), "", Collections.emptyList(), null, false);
    }

    public static Result assembleQt(Context context, String repository, String sourcePackage,
            File runtimeRoot) throws Exception {
        return assembleQt(context, repository, sourcePackage, "0", currentArchitecture(), "qt6", sourcePackage,
                displayName(sourcePackage), "", Collections.emptyList(), runtimeRoot, true);
    }

    public static Result assembleQt(Context context, String repository, String sourcePackage,
            String sourceVersion) throws Exception {
        return assembleQt(context, repository, sourcePackage, sourceVersion, currentArchitecture(), "qt6", sourcePackage,
                displayName(sourcePackage), "", Collections.emptyList(), null, false);
    }

    public static Result assembleQt(Context context, String repository, String sourcePackage,
            String sourceVersion, String executableName) throws Exception {
        return assembleQt(context, repository, sourcePackage, sourceVersion, currentArchitecture(), "qt6", executableName,
                displayName(sourcePackage), "", Collections.emptyList(), null, false);
    }

    public static Result assembleQtFromRuntimePack(Context context, String repository,
            String sourcePackage, String sourceVersion, String executableName, File runtimeRoot)
            throws Exception {
        if (runtimeRoot == null) {
            throw new IllegalArgumentException("Runtime-pack staging root is required");
        }
        return assembleQt(context, repository, sourcePackage, sourceVersion, currentArchitecture(), "qt6", executableName,
                displayName(sourcePackage), "", Collections.emptyList(), runtimeRoot, false);
    }

    static Result assembleDesktopFromRuntimePack(Context context, String repository,
            String sourcePackage, String sourceVersion, String architecture, String toolkit,
            ArchPackageClassifier.Result classification, File runtimeRoot) throws Exception {
        if (runtimeRoot == null || classification == null
                || classification.kind != ArchPackageClassifier.Kind.DESKTOP) {
            throw new IllegalArgumentException("Desktop runtime-pack metadata is required");
        }
        return assembleQt(context, repository, sourcePackage, sourceVersion, architecture,
                toolkit, classification.executable, classification.displayName, classification.iconName,
                classification.mimeTypes, runtimeRoot, false);
    }

    private static Result assembleQt(Context context, String repository, String sourcePackage,
            String sourceVersion, String architecture, String toolkit, String executableName,
            String appLabel, String iconName,
            List<String> mimeTypes, File runtimeRoot, boolean embedNativeClosure) throws Exception {
        if (!repository.matches("[a-z0-9-]{1,32}")
                || !sourcePackage.matches("[a-zA-Z0-9@._+:-]{1,128}")
                || sourceVersion == null
                || !sourceVersion.matches("[a-zA-Z0-9@._+:-]{1,128}")
                || !ArchRuntimePolicy.supports(architecture)
                || !("qt6".equals(toolkit) || "gtk3".equals(toolkit)
                        || "wayland".equals(toolkit))
                || executableName == null
                || !executableName.matches("[a-zA-Z0-9@._+:-]{1,128}")
                || !validDisplayName(appLabel) || mimeTypes == null) {
            throw new IllegalArgumentException("Invalid wrapper source identity");
        }
        List<String> documentMimeTypes = normalizedMimeTypes(mimeTypes);
        String packageName = packageNameFor(repository, sourcePackage);
        File output = new File(context.getCacheDir(), "generated-" + sourcePackage + ".apk");
        File unsigned = new File(context.getCacheDir(), "generated-" + sourcePackage + ".unsigned.apk");
        rebuildTemplate(context, packageName, repository, sourcePackage, sourceVersion,
                architecture, toolkit, executableName, appLabel, iconName, documentMimeTypes,
                runtimeRoot, embedNativeClosure, unsigned);
        verifyStoredEntryAlignment(unsigned, "resources.arsc", 4);
        ArchWrapperSigner.Result signed = ArchWrapperSigner.sign(context, unsigned, output);
        PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(output.getPath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (parsed == null || !packageName.equals(parsed.packageName)
                || parsed.applicationInfo == null) {
            output.delete();
            throw new SecurityException("Generated wrapper package identity mismatch");
        }
        parsed.applicationInfo.sourceDir = output.getPath();
        parsed.applicationInfo.publicSourceDir = output.getPath();
        CharSequence generatedLabel = parsed.applicationInfo.loadLabel(
                context.getPackageManager());
        if (generatedLabel == null || !appLabel.contentEquals(generatedLabel)) {
            output.delete();
            throw new SecurityException("Generated wrapper label mismatch");
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
    private static void rebuildTemplate(Context context, String packageName, String repository,
            String sourcePackage, String sourceVersion, String architecture, String toolkit,
            String executableName, String appLabel,
            String iconName, List<String> mimeTypes, File runtimeRoot,
            boolean embedNativeClosure, File output)
            throws Exception {
        byte[] placeholder = PACKAGE_PLACEHOLDER.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = packageName.getBytes(StandardCharsets.UTF_8);
        byte[] placeholderUtf16 = PACKAGE_PLACEHOLDER.getBytes(StandardCharsets.UTF_16LE);
        byte[] replacementUtf16 = packageName.getBytes(StandardCharsets.UTF_16LE);
        if (placeholder.length != replacement.length
                || placeholderUtf16.length != replacementUtf16.length) {
            throw new IllegalStateException("Generated package identity has invalid length");
        }
        boolean patched = false;
        boolean iconPatched = false;
        byte[] launcherIcon = buildLauncherIcon(context, sourcePackage,
                executableName, iconName, runtimeRoot);
        String templateAsset = mimeTypes.isEmpty() ? QT_TEMPLATE : QT_DOCUMENT_TEMPLATE;
        android.util.Log.i("ArchphenePackages", "Wrapper template " + templateAsset
                + " for " + sourcePackage + " with " + mimeTypes.size() + " MIME types");
        try (InputStream raw = context.getAssets().open(templateAsset);
                ZipInputStream input = new ZipInputStream(raw);
                CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(output));
                ZipOutputStream zip = new ZipOutputStream(counted)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;
                byte[] value = readEntry(input);
                if (name.endsWith("/linux_app_icon_png.png")) {
                    value = launcherIcon;
                    iconPatched = true;
                }
                if ("AndroidManifest.xml".equals(name)) {
                    value = replaceBinaryXmlString(value, "KCalc", appLabel);
                    value = replaceBinaryXmlString(value, "extra/kcalc",
                            repository + "/" + sourcePackage);
                    value = replaceBinaryXmlString(value, "26.04.3-1", sourceVersion);
                    value = replaceBinaryXmlString(value,
                            "https://archlinux.org/packages/extra/x86_64/kcalc/json/",
                            packageMetadataUrl(repository, architecture, sourcePackage));
                    value = replaceBinaryXmlString(value, "glibc-x86_64",
                            "glibc-" + architecture);
                    value = replaceBinaryXmlString(value, "qt6", toolkit);
                    value = replaceBinaryXmlString(value,
                            "wayland,input,ime,clipboard,runtime-pack,home-documents,documents",
                            capabilityMetadata(mimeTypes));
                    value = replaceBinaryXmlString(value, "archphene-executable-placeholder",
                            executableName);
                    value = replaceBinaryXmlString(value, "ArchpheneKCalc", "ArchpheneLinuxApp");
                    if (!mimeTypes.isEmpty()) {
                        String fallbackMime = mimeTypes.get(0);
                        for (int index = 0; index < MIME_SLOT_COUNT; index++) {
                            String mime = index < mimeTypes.size()
                                    ? mimeTypes.get(index) : fallbackMime;
                            value = replaceBinaryXmlString(value, String.format(Locale.ROOT,
                                    "application/x-archphene-mime-%02d", index), mime);
                        }
                    }
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
            if (embedNativeClosure && runtimeRoot != null) {
                for (Map.Entry<String, File> nativeFile : collectNativeFiles(
                        context, runtimeRoot, sourcePackage).entrySet()) {
                    ZipEntry next = new ZipEntry("lib/" + androidAbi(architecture) + "/"
                            + nativeFile.getKey());
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
        if (!patched || !iconPatched) {
            output.delete();
            throw new SecurityException(!patched
                    ? "Wrapper template manifest is missing"
                    : "Wrapper template launcher icon marker is missing");
        }
    }

    private static String currentArchitecture() {
        return ArchRuntimePolicy.current().architecture;
    }

    private static String androidAbi(String architecture) {
        if (ArchRuntimePolicy.X86_64.equals(architecture)) return "x86_64";
        if (ArchRuntimePolicy.AARCH64.equals(architecture)) return "arm64-v8a";
        throw new IllegalArgumentException("Unsupported wrapper architecture");
    }

    private static String packageMetadataUrl(String repository, String architecture,
            String sourcePackage) {
        if (ArchRuntimePolicy.AARCH64.equals(architecture)) {
            return "https://archlinuxarm.org/packages/aarch64/" + sourcePackage;
        }
        return "https://archlinux.org/packages/" + repository + "/x86_64/"
                + sourcePackage + "/json/";
    }

    static Map<String, File> collectNativeFiles(Context context, File runtimeRoot,
            String sourcePackage) throws Exception {
        return collectNativeFiles(context, runtimeRoot, sourcePackage, sourcePackage);
    }

    static Map<String, File> collectNativeFiles(Context context, File runtimeRoot,
            String sourcePackage, String executableName) throws Exception {
        if (sourcePackage == null || !sourcePackage.matches("[a-zA-Z0-9@._+-]{1,128}")
                || executableName == null
                || !executableName.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid desktop package identity");
        }
        File canonicalRoot = runtimeRoot.getCanonicalFile();
        File libraryRoot = new File(canonicalRoot, "usr/lib").getCanonicalFile();
        File executable = new File(canonicalRoot, "usr/bin/" + executableName).getCanonicalFile();
        if (!libraryRoot.getPath().startsWith(canonicalRoot.getPath() + File.separator)
                || !executable.isFile()) {
            throw new SecurityException("Invalid staged runtime root");
        }
        if (!isElf(executable)) return Collections.emptyMap();
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
        resolveDependencies(result, candidates, pending, visited);
        for (String dynamicName : new String[] {
                "libEGL.so", "libEGL.so.1", "libGLESv2.so", "libGLESv2.so.2"}) {
            if (!containsAscii(executable, dynamicName)) continue;
            File dynamic = candidates.get(dynamicName);
            if (dynamic == null) {
                throw new SecurityException("Missing runtime-loaded ELF dependency "
                        + dynamicName + " required by " + executable.getName());
            }
            addNative(result, dynamicName, dynamic);
            pending.add(dynamic);
        }
        resolveDependencies(result, candidates, pending, visited);
        if (result.containsKey("libQt6Core.so.6")) {
            for (String pluginName : new String[] {"libqwayland.so", "libxdg-shell.so"}) {
                File plugin = candidates.get(pluginName);
                if (plugin == null) {
                    throw new SecurityException("Missing Qt bridge plugin " + pluginName);
                }
                addNative(result, pluginName, plugin);
                pending.add(plugin);
            }
            resolveDependencies(result, candidates, pending, visited);
        }
        if (result.containsKey("libgtk-3.so.0")) {
            for (String compatibilityName : new String[] {
                    "libpng16.so.16", "libjpeg.so.8", "libtiff.so.6"}) {
                if (result.containsKey(compatibilityName)) continue;
                File compatibilityLibrary = candidates.get(compatibilityName);
                if (compatibilityLibrary == null) {
                    throw new SecurityException("Missing GTK3 bridge dependency "
                            + compatibilityName);
                }
                addNative(result, compatibilityName, compatibilityLibrary);
                pending.add(compatibilityLibrary);
            }
            resolveDependencies(result, candidates, pending, visited);
            File pluginRoot = new File(libraryRoot, sourcePackage + "/plugins")
                    .getCanonicalFile();
            String pluginPrefix = pluginRoot.getPath() + File.separator;
            if (pluginRoot.isDirectory()
                    && pluginPrefix.startsWith(libraryRoot.getPath() + File.separator)) {
                for (Map.Entry<String, File> entry : candidates.entrySet()) {
                    File candidate = entry.getValue().getCanonicalFile();
                    if (!candidate.getPath().startsWith(pluginPrefix)) continue;
                    TreeMap<String, File> trial = new TreeMap<>(result);
                    HashSet<String> trialVisited = new HashSet<>(visited);
                    ArrayDeque<File> trialPending = new ArrayDeque<>();
                    try {
                        addNative(trial, entry.getKey(), candidate);
                        trialPending.add(candidate);
                        resolveDependencies(trial, candidates, trialPending, trialVisited);
                        result.clear();
                        result.putAll(trial);
                        visited.clear();
                        visited.addAll(trialVisited);
                    } catch (SecurityException unavailableOptionalDependency) {
                        android.util.Log.i("ArchphenePackages", "Skipping optional plugin "
                                + entry.getKey() + ": "
                                + unavailableOptionalDependency.getMessage());
                    }
                }
            }
        }        if (result.containsKey("libEGL.so.1") || result.containsKey("libEGL.so")) {
            for (String providerName : new String[] {
                    "libEGL_mesa.so.0", "swrast_dri.so", "kms_swrast_dri.so",
                    "virtio_gpu_dri.so"}) {
                File provider = candidates.get(providerName);
                if (provider == null) continue;
                addNative(result, providerName, provider);
                pending.add(provider);
            }
            resolveDependencies(result, candidates, pending, visited);
        }
        return result;
    }

    private static void resolveDependencies(Map<String, File> result,
            Map<String, File> candidates, ArrayDeque<File> pending, Set<String> visited)
            throws Exception {
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

    private static boolean containsAscii(File file, String value) throws Exception {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        int matched = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    byte current = buffer[index];
                    if (current == expected[matched]) {
                        matched++;
                        if (matched == expected.length) return true;
                    } else {
                        matched = current == expected[0] ? 1 : 0;
                    }
                }
            }
        }
        return false;
    }

    static boolean isElf(File file) throws Exception {
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

    private static byte[] buildLauncherIcon(Context context, String sourcePackage,
            String executableName, String iconName, File runtimeRoot) throws Exception {
        Bitmap source = runtimeRoot == null ? null
                : loadDesktopPng(runtimeRoot, executableName, iconName);
        if (source == null) {
            int fallback = "kcalc".equals(sourcePackage)
                    ? R.drawable.package_kcalc_icon : R.drawable.manager_icon;
            Drawable drawable = context.getDrawable(fallback);
            if (drawable == null) throw new SecurityException("Launcher icon fallback is missing");
            source = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(source);
            drawable.setBounds(0, 0, 160, 160);
            drawable.draw(canvas);
        }
        Bitmap output = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        int sourceWidth = Math.max(1, source.getWidth());
        int sourceHeight = Math.max(1, source.getHeight());
        float scale = Math.min(160f / sourceWidth, 160f / sourceHeight);
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        Rect target = new Rect((192 - width) / 2, (192 - height) / 2,
                (192 + width) / 2, (192 + height) / 2);
        canvas.drawBitmap(source, null, target,
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!output.compress(Bitmap.CompressFormat.PNG, 100, encoded)
                || encoded.size() <= 0 || encoded.size() > 4 * 1024 * 1024) {
            throw new SecurityException("Could not encode launcher icon");
        }
        source.recycle();
        output.recycle();
        return encoded.toByteArray();
    }

    private static Bitmap loadDesktopPng(File runtimeRoot, String executableName, String iconName)
            throws Exception {
        File root = runtimeRoot.getCanonicalFile();
        String selectedIcon = iconName == null ? "" : iconName.trim();
        if (!selectedIcon.matches("[a-zA-Z0-9@._+-]{1,128}")) {
            selectedIcon = desktopIconName(root, executableName);
        }
        if (selectedIcon == null) return null;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(new File(root, "usr/share/icons"));
        pending.add(new File(root, "usr/share/pixmaps"));
        HashSet<String> visited = new HashSet<>();
        File best = null;
        long bestArea = -1;
        int examined = 0;
        while (!pending.isEmpty() && examined++ < 20000) {
            File candidate = pending.removeFirst();
            File canonical;
            try {
                canonical = candidate.getCanonicalFile();
            } catch (Exception ignored) {
                continue;
            }
            if (!canonical.getPath().startsWith(root.getPath() + File.separator)
                    || !visited.add(canonical.getPath())) continue;
            if (canonical.isDirectory()) {
                File[] children = canonical.listFiles();
                if (children != null) {
                    for (File child : children) pending.addLast(child);
                }
                continue;
            }
            if (!canonical.getName().equals(selectedIcon + ".png")
                    || canonical.length() <= 0 || canonical.length() > 8L * 1024 * 1024) {
                continue;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(canonical.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 4096 || bounds.outHeight > 4096) continue;
            long area = (long) bounds.outWidth * bounds.outHeight;
            if (area > bestArea) {
                best = canonical;
                bestArea = area;
            }
        }
        if (best == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bestArea / ((long) options.inSampleSize * options.inSampleSize)
                > 1024L * 1024) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(best.getPath(), options);
    }

    private static String desktopIconName(File root, String executableName) throws Exception {
        File applications = new File(root, "usr/share/applications").getCanonicalFile();
        if (!applications.isDirectory()
                || !applications.getPath().startsWith(root.getPath() + File.separator)) {
            return executableName;
        }
        File[] entries = applications.listFiles((directory, name) -> name.endsWith(".desktop"));
        if (entries == null) return executableName;
        for (File entry : entries) {
            if (entry.length() <= 0 || entry.length() > 1024 * 1024) continue;
            String executable = null;
            String icon = null;
            boolean desktopEntry = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("[")) {
                        desktopEntry = "[Desktop Entry]".equals(line);
                    } else if (desktopEntry && line.startsWith("Exec=")) {
                        String command = line.substring(5).trim();
                        int space = command.indexOf(' ');
                        if (space >= 0) command = command.substring(0, space);
                        int slash = command.lastIndexOf('/');
                        executable = slash >= 0 ? command.substring(slash + 1) : command;
                    } else if (desktopEntry && line.startsWith("Icon=")) {
                        icon = line.substring(5).trim();
                    }
                }
            }
            if (executableName.equals(executable) && icon != null
                    && icon.matches("[a-zA-Z0-9@._+-]{1,128}")) {
                return icon;
            }
        }
        return executableName;
    }

    private static String capabilityMetadata(List<String> mimeTypes) {
        String base = "wayland,input,ime,clipboard,runtime-pack,home-documents";
        return mimeTypes.isEmpty() ? base : base + ",documents";
    }
    private static List<String> normalizedMimeTypes(List<String> values) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            String mime = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!mime.matches("[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+*-]{1,64}")
                    || mime.startsWith("application/x-archphene-mime-")
                    || result.contains(mime)) {
                continue;
            }
            result.add(mime);
            if (result.size() >= MIME_SLOT_COUNT) break;
        }
        return Collections.unmodifiableList(result);
    }
    private static boolean validDisplayName(String value) {
        if (value == null || value.isBlank() || value.length() > 128) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    private static String displayName(String packageName) {
        if ("kcalc".equals(packageName)) return "KCalc";
        if ("glmark2".equals(packageName)) return "GLMark2";
        String[] words = packageName.replace('_', '-').split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.length() == 0 ? packageName : result.toString();
    }

    private static byte[] replaceBinaryXmlString(byte[] xml, String match, String replacement)
            throws Exception {
        if (getU16(xml, 0) != 0x0003 || getI32(xml, 4) != xml.length) {
            throw new SecurityException("Wrapper template manifest is not valid binary XML");
        }
        int offset = getU16(xml, 2);
        while (offset + 8 <= xml.length) {
            int type = getU16(xml, offset);
            int size = getI32(xml, offset + 4);
            if (size < 8 || offset + size > xml.length) {
                throw new SecurityException("Wrapper template contains an invalid XML chunk");
            }
            if (type == 0x0001) {
                return replaceStringPoolEntry(xml, offset, match, replacement);
            }
            offset += size;
        }
        throw new SecurityException("Wrapper template string pool is missing");
    }

    private static byte[] replaceStringPoolEntry(byte[] xml, int poolOffset,
            String match, String replacement) throws Exception {
        int headerSize = getU16(xml, poolOffset + 2);
        int poolSize = getI32(xml, poolOffset + 4);
        int stringCount = getI32(xml, poolOffset + 8);
        int flags = getI32(xml, poolOffset + 16);
        int stringsStart = getI32(xml, poolOffset + 20);
        int stylesStart = getI32(xml, poolOffset + 24);
        if (headerSize < 28 || stringCount <= 0 || stringCount > 100000
                || stringsStart < headerSize + stringCount * 4
                || poolOffset + poolSize > xml.length) {
            throw new SecurityException("Wrapper template has an invalid string pool");
        }
        boolean utf8 = (flags & 0x100) != 0;
        int foundIndex = -1;
        int oldStart = -1;
        int oldEnd = -1;
        for (int index = 0; index < stringCount; index++) {
            int relative = getI32(xml, poolOffset + headerSize + index * 4);
            int start = poolOffset + stringsStart + relative;
            DecodedString decoded = decodePoolString(xml, start, utf8,
                    poolOffset + poolSize);
            if (match.equals(decoded.value)) {
                if (foundIndex >= 0) {
                    throw new SecurityException("Wrapper template string marker is ambiguous");
                }
                foundIndex = index;
                oldStart = start;
                oldEnd = decoded.end;
            }
        }
        if (foundIndex < 0) {
            throw new SecurityException("Wrapper template string marker is missing: " + match);
        }
        byte[] encoded = encodePoolString(replacement, utf8);
        int rawDelta = encoded.length - (oldEnd - oldStart);
        int unalignedPoolSize = poolSize + rawDelta;
        int padding = (4 - (unalignedPoolSize & 3)) & 3;
        int totalDelta = rawDelta + padding;
        byte[] result = new byte[xml.length + totalDelta];
        System.arraycopy(xml, 0, result, 0, oldStart);
        System.arraycopy(encoded, 0, result, oldStart, encoded.length);
        int afterString = oldStart + encoded.length;
        int oldPoolEnd = poolOffset + poolSize;
        System.arraycopy(xml, oldEnd, result, afterString, oldPoolEnd - oldEnd);
        int newPoolEnd = oldPoolEnd + totalDelta;
        System.arraycopy(xml, oldPoolEnd, result, newPoolEnd, xml.length - oldPoolEnd);
        putI32(result, 4, xml.length + totalDelta);
        putI32(result, poolOffset + 4, poolSize + totalDelta);
        int replacedOffset = getI32(xml, poolOffset + headerSize + foundIndex * 4);
        for (int index = 0; index < stringCount; index++) {
            int position = poolOffset + headerSize + index * 4;
            int relative = getI32(xml, position);
            putI32(result, position, relative > replacedOffset ? relative + rawDelta : relative);
        }
        if (stylesStart != 0) putI32(result, poolOffset + 24, stylesStart + rawDelta);
        return result;
    }

    private static final class DecodedString {
        final String value;
        final int end;

        DecodedString(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static DecodedString decodePoolString(byte[] value, int offset, boolean utf8,
            int limit) throws Exception {
        if (utf8) {
            int[] utf16Length = readLength8(value, offset, limit);
            int[] byteLength = readLength8(value, utf16Length[1], limit);
            int start = byteLength[1];
            int end = start + byteLength[0];
            if (end >= limit || value[end] != 0) {
                throw new SecurityException("Wrapper template has a malformed UTF-8 string");
            }
            return new DecodedString(new String(value, start, byteLength[0],
                    StandardCharsets.UTF_8), end + 1);
        }
        int[] length = readLength16(value, offset, limit);
        int bytes = Math.multiplyExact(length[0], 2);
        int end = length[1] + bytes;
        if (end + 1 >= limit || value[end] != 0 || value[end + 1] != 0) {
            throw new SecurityException("Wrapper template has a malformed UTF-16 string");
        }
        return new DecodedString(new String(value, length[1], bytes,
                StandardCharsets.UTF_16LE), end + 2);
    }

    private static byte[] encodePoolString(String value, boolean utf8) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (utf8) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeLength8(output, value.length());
            writeLength8(output, bytes.length);
            output.write(bytes);
            output.write(0);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
            writeLength16(output, value.length());
            output.write(bytes);
            output.write(0);
            output.write(0);
        }
        return output.toByteArray();
    }

    private static int[] readLength8(byte[] value, int offset, int limit) {
        if (offset >= limit) throw new SecurityException("Truncated string length");
        int first = value[offset] & 0xff;
        if ((first & 0x80) == 0) return new int[] {first, offset + 1};
        if (offset + 1 >= limit) throw new SecurityException("Truncated string length");
        return new int[] {((first & 0x7f) << 8) | (value[offset + 1] & 0xff), offset + 2};
    }

    private static int[] readLength16(byte[] value, int offset, int limit) {
        if (offset + 1 >= limit) throw new SecurityException("Truncated string length");
        int first = getU16(value, offset);
        if ((first & 0x8000) == 0) return new int[] {first, offset + 2};
        if (offset + 3 >= limit) throw new SecurityException("Truncated string length");
        return new int[] {((first & 0x7fff) << 16) | getU16(value, offset + 2), offset + 4};
    }

    private static void writeLength8(ByteArrayOutputStream output, int length) {
        if (length > 0x7fff) throw new SecurityException("String is too long");
        if (length > 0x7f) output.write((length >> 8) | 0x80);
        output.write(length & 0xff);
    }

    private static void writeLength16(ByteArrayOutputStream output, int length) {
        if (length > 0x7fffffff) throw new SecurityException("String is too long");
        if (length > 0x7fff) {
            output.write((length >> 16) & 0xff);
            output.write(((length >> 24) & 0x7f) | 0x80);
        }
        output.write(length & 0xff);
        output.write((length >> 8) & 0xff);
    }

    private static int getU16(byte[] value, int offset) {
        if (offset < 0 || offset + 2 > value.length) {
            throw new SecurityException("Binary XML read exceeds bounds");
        }
        return (value[offset] & 0xff) | (value[offset + 1] & 0xff) << 8;
    }

    private static int getI32(byte[] value, int offset) {
        if (offset < 0 || offset + 4 > value.length) {
            throw new SecurityException("Binary XML read exceeds bounds");
        }
        return (value[offset] & 0xff) | (value[offset + 1] & 0xff) << 8
                | (value[offset + 2] & 0xff) << 16 | value[offset + 3] << 24;
    }

    private static void putI32(byte[] value, int offset, int current) {
        if (offset < 0 || offset + 4 > value.length) {
            throw new SecurityException("Binary XML write exceeds bounds");
        }
        value[offset] = (byte) current;
        value[offset + 1] = (byte) (current >> 8);
        value[offset + 2] = (byte) (current >> 16);
        value[offset + 3] = (byte) (current >> 24);
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