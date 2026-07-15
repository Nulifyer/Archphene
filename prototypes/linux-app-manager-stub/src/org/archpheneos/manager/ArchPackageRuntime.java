package org.archpheneos.manager;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ArchPackageRuntime {
    private static final int OUTPUT_LIMIT = 16 * 1024 * 1024;
    private static final String ASSET_ROOT = "package-runtime/";
    private static final ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS =
            new ConcurrentHashMap<>();

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    public static final class Verification {
        public final String signerFingerprint;
        public final String primaryFingerprint;
        public final String output;

        Verification(String signerFingerprint, String primaryFingerprint, String output) {
            this.signerFingerprint = signerFingerprint;
            this.primaryFingerprint = primaryFingerprint;
            this.output = output;
        }
    }

    public static final class ResolvedPackage {
        public final String name;
        public final String version;
        public final String repository;
        public final String url;
        public final String filename;

        ResolvedPackage(String name, String version, String repository, String url,
                String filename) {
            this.name = name;
            this.version = version;
            this.repository = repository;
            this.url = url;
            this.filename = filename;
        }
    }

    public static final class FileOwner {
        public final String repository;
        public final String name;
        public final String version;
        public final String path;

        FileOwner(String repository, String name, String version, String path) {
            this.repository = repository;
            this.name = name;
            this.version = version;
            this.path = path;
        }
    }
    public static final class StagedTransaction {
        public final String sourcePackage;
        public final List<ResolvedPackage> packages;
        public final File root;
        public final String runtimePackId;
        public final ArchPackageClassifier.Result classification;
        public final String toolkit;

        StagedTransaction(String sourcePackage, List<ResolvedPackage> packages, File root,
                String runtimePackId, ArchPackageClassifier.Result classification,
                String toolkit) {
            this.sourcePackage = sourcePackage;
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
            this.root = root;
            this.runtimePackId = runtimePackId;
            this.classification = classification;
            this.toolkit = toolkit;
        }

        String sourceVersion() {
            for (ResolvedPackage value : packages) {
                if (sourcePackage.equals(value.name)) return value.version;
            }
            throw new IllegalStateException("Resolved transaction source package is missing");
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent, String status);
    }

    private ArchPackageRuntime() {}

    public static boolean available(Context context) {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libarchphene_ld.so").isFile()
                && new File(nativeDir, "libarchphene_pacman.so").isFile()
                && new File(nativeDir, "libarchphene_gpg.so").isFile()
                && new File(nativeDir, "libarchphene_gpgv.so").isFile();
    }

    public static Result pacman(Context context, String... arguments) throws Exception {
        return runTool(context, "libarchphene_pacman.so", Arrays.asList(arguments));
    }

    public static synchronized void refreshDatabases(Context context) throws Exception {
        File sync = directory(directory(state(context), "db"), "sync");
        download("https://geo.mirror.pkgbuild.com/core/os/x86_64/core.db",
                new File(sync, "core.db"), 4 * 1024 * 1024L);
        download("https://geo.mirror.pkgbuild.com/extra/os/x86_64/extra.db",
                new File(sync, "extra.db"), 32 * 1024 * 1024L);
    }

    public static synchronized List<FileOwner> searchFileOwners(Context context, String query)
            throws Exception {
        String normalized = query == null ? "" : query.trim();
        if (!normalized.matches("[a-zA-Z0-9@._+:-]{2,128}")) {
            throw new IllegalArgumentException("Invalid executable or file search");
        }
        File state = state(context);
        File database = directory(state, "db");
        File sync = directory(database, "sync");
        refreshFileDatabaseIfNeeded("core", new File(sync, "core.files"), 8L * 1024 * 1024);
        refreshFileDatabaseIfNeeded("extra", new File(sync, "extra.files"), 64L * 1024 * 1024);
        File root = directory(state, "root");
        File config = new File(state, "pacman.conf");
        writePacmanConfig(config);
        Result result = pacman(context, "--config", config.getPath(), "--root", root.getPath(),
                "--dbpath", database.getPath(), "-F", "--machinereadable", normalized);
        if (result.exitCode != 0 && result.output.trim().length() > 0) {
            throw new IllegalStateException("Arch file search failed\n" + result.output);
        }
        return parseFileOwners(result.output);
    }

    private static void refreshFileDatabaseIfNeeded(String repository, File target, long limit)
            throws Exception {
        if (target.isFile() && target.length() > 0
                && System.currentTimeMillis() - target.lastModified() < 24L * 60 * 60_000) return;
        download("https://geo.mirror.pkgbuild.com/" + repository + "/os/x86_64/"
                + repository + ".files", target, limit);
    }

    static List<FileOwner> parseFileOwners(String output) {
        ArrayList<FileOwner> owners = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String line : output.split("\\r?\\n")) {
            String[] fields = line.split("\\u0000", -1);
            if (fields.length != 4 || !fields[0].matches("(?:core|extra)")
                    || !fields[1].matches("[a-zA-Z0-9@._+:-]{1,128}")
                    || fields[2].isEmpty() || !fields[3].matches("[a-zA-Z0-9@._+:/-]{1,512}")) {
                continue;
            }
            String identity = fields[0] + "/" + fields[1];
            if (seen.add(identity)) {
                owners.add(new FileOwner(fields[0], fields[1], fields[2], fields[3]));
            }
            if (owners.size() >= 50) break;
        }
        return owners;
    }
    public static synchronized List<ResolvedPackage> resolve(Context context, String packageName)
            throws Exception {
        return resolve(context, packageName, new String[0]);
    }

    private static synchronized List<ResolvedPackage> resolve(Context context,
            String packageName, String... bridgePackages) throws Exception {
        if (packageName == null || !packageName.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Arch package name");
        }
        File state = state(context);
        File database = directory(state, "db");
        if (!new File(database, "sync/core.db").isFile()
                || !new File(database, "sync/extra.db").isFile()) {
            refreshDatabases(context);
        }
        File root = directory(state, "root");
        File cache = directory(state, "cache");
        File config = new File(state, "pacman.conf");
        writePacmanConfig(config);
        String separator = "__ARCHPHENE__";
        ArrayList<String> arguments = new ArrayList<>(Arrays.asList(
                "--config", config.getPath(), "--root", root.getPath(),
                "--dbpath", database.getPath(), "--cachedir", cache.getPath(), "-Sp",
                "--print-format", "%n" + separator + "%v" + separator + "%r" + separator
                        + "%l" + separator + "%f", packageName));
        for (String bridgePackage : bridgePackages) {
            if (bridgePackage == null || !bridgePackage.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
                throw new IllegalArgumentException("Invalid bridge package name");
            }
            arguments.add(bridgePackage);
        }
        Result result = pacman(context, arguments.toArray(new String[0]));
        if (result.exitCode != 0) {
            throw new IllegalStateException("Arch dependency resolution failed\n" + result.output);
        }
        ArrayList<ResolvedPackage> packages = new ArrayList<>();
        for (String line : result.output.split("\\r?\\n")) {
            String[] fields = line.trim().split(separator, -1);
            if (fields.length != 5) continue;
            validatePackageUrl(fields[3], fields[2]);
            if (!fields[4].matches("[a-zA-Z0-9@._+:-]+\\.pkg\\.tar\\.(?:zst|xz)")) {
                throw new SecurityException("Invalid Arch package filename");
            }
            packages.add(new ResolvedPackage(fields[0], fields[1], fields[2], fields[3], fields[4]));
            if (packages.size() > 2048) throw new SecurityException("Arch transaction is too large");
        }
        boolean containsRequested = false;
        for (ResolvedPackage value : packages) {
            if (value.name.equals(packageName)) { containsRequested = true; break; }
        }
        if (!containsRequested) {
            throw new IllegalStateException("Arch transaction did not contain requested package");
        }
        return packages;
    }

    private static void writePacmanConfig(File config) throws Exception {
        writeText(config, "[options]\nArchitecture = x86_64\n"
                + "SigLevel = Required DatabaseOptional\nLocalFileSigLevel = Required\n\n"
                + "[core]\nServer = https://geo.mirror.pkgbuild.com/core/os/x86_64\n\n"
                + "[extra]\nServer = https://geo.mirror.pkgbuild.com/extra/os/x86_64\n");
    }
    public static Verification downloadAndVerify(Context context, ResolvedPackage value)
            throws Exception {
        Object lock = DOWNLOAD_LOCKS.computeIfAbsent(value.filename, ignored -> new Object());
        synchronized (lock) {
            return downloadAndVerifyLocked(context, value);
        }
    }

    private static Verification downloadAndVerifyLocked(Context context,
            ResolvedPackage value) throws Exception {
        validatePackageUrl(value.url, value.repository);
        File downloads = directory(state(context), "downloads");
        String safeName = value.filename.replace(':', '_');
        File packageFile = new File(downloads, safeName);
        File signatureFile = new File(downloads, safeName + ".sig");
        if (packageFile.isFile() && signatureFile.isFile()) {
            try {
                return verifyPackage(context, packageFile, signatureFile);
            } catch (Exception invalidCache) {
                packageFile.delete();
                signatureFile.delete();
            }
        }
        download(value.url, packageFile, 1024L * 1024 * 1024);
        download(value.url + ".sig", signatureFile, 1024L * 1024);
        return verifyPackage(context, packageFile, signatureFile);
    }

    public static StagedTransaction stageTransaction(Context context, String packageName)
            throws Exception {
        return stageTransaction(context, packageName, (percent, status) -> {});
    }

    public static StagedTransaction stageTransaction(Context context, String packageName,
            ProgressCallback progress) throws Exception {
        return stageTransaction(context, packageName, packageName, progress);
    }

    public static StagedTransaction stageTransaction(Context context, String packageName,
            String executableName, ProgressCallback progress) throws Exception {
        if (progress == null) throw new IllegalArgumentException("Progress callback is required");
        if (executableName == null
                || !executableName.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid desktop executable name");
        }
        progress.onProgress(5, "Resolving signed Arch transaction");
        List<ResolvedPackage> packages = resolve(context, packageName);
        ArrayList<String> bridgePackages = new ArrayList<>();
        if (containsPackage(packages, "qt6-base")
                && !containsPackage(packages, "qt6-wayland")) {
            bridgePackages.add("qt6-wayland");
        }
        if (containsPackage(packages, "gtk3")) {
            if (!containsPackage(packages, "libjpeg-turbo")) {
                bridgePackages.add("libjpeg-turbo");
            }
            if (!containsPackage(packages, "libtiff")) bridgePackages.add("libtiff");
            if (!containsPackage(packages, "shared-mime-info")) {
                bridgePackages.add("shared-mime-info");
            }
        }
        if (!bridgePackages.isEmpty()) {
            packages = resolve(context, packageName,
                    bridgePackages.toArray(new String[0]));
        }
        File staging = new File(state(context), "staging/" + packageName);
        deleteRecursively(staging);
        File root = new File(staging, "root");
        if (!root.mkdirs()) throw new IllegalStateException("Could not create transaction root");
        File downloads = directory(state(context), "downloads");
        LinkedHashSet<String> sourceCommands = new LinkedHashSet<>();
        for (int index = 0; index < packages.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ResolvedPackage value = packages.get(index);
            int base = 10 + index * 50 / packages.size();
            progress.onProgress(base, "Downloading and verifying " + value.name
                    + " (" + (index + 1) + "/" + packages.size() + ")");
            android.util.Log.i("ArchphenePackages", "staging " + value.repository + "/"
                    + value.name + " " + value.version);
            downloadAndVerify(context, value);
            progress.onProgress(Math.min(59, base + 1), "Staging verified " + value.name);
            File archive = new File(downloads, value.filename.replace(':', '_'));
            stageVerifiedArchive(context, archive, root,
                    value.name.equals(packageName), sourceCommands);
        }
        progress.onProgress(60, "Preparing shared toolkit data");
        prepareSharedData(context, root);
        ArchPackageClassifier.Result classification = ArchPackageClassifier.classify(
                root, packageName, executableName, sourceCommands);
        android.util.Log.i("ArchphenePackages", "classified " + packageName + " as "
                + classification.kind + " executable=" + classification.executable
                + " commands=" + classification.commands.size());
        if (classification.kind == ArchPackageClassifier.Kind.DEPENDENCY) {
            deleteRecursively(staging);
            throw new IllegalArgumentException(packageName
                    + " does not provide a desktop entry or terminal command");
        }
        executableName = classification.executable;
        File executable = new File(root, "usr/bin/" + executableName).getCanonicalFile();
        if (!executable.isFile() || !executable.getPath().startsWith(root.getCanonicalPath()
                + File.separator)) {
            deleteRecursively(staging);
            throw new SecurityException("Resolved package has no safe desktop executable");
        }
        progress.onProgress(62, "Publishing verified runtime pack");
        RuntimePackStore.Pack pack = RuntimePackStore.build(
                context, packageName, executableName, packages,
                classification.commands, root);
        progress.onProgress(65, "Runtime pack ready");
        return new StagedTransaction(packageName, packages, root, pack.id, classification,
                pack.toolkit());
    }

    private static void stageVerifiedArchive(Context context, File archive, File root,
            boolean sourceArchive, Set<String> sourceCommands)
            throws Exception {
        Result namesResult = runTool(context, "libarchphene_bsdtar.so",
                Arrays.asList("-tf", archive.getPath()));
        if (namesResult.exitCode != 0) {
            throw new SecurityException("Could not inspect verified Arch package archive");
        }
        String[] names = namesResult.output.split("\\r?\\n");
        boolean hasSourceCommand = false;
        for (String entry : names) {
            validateArchivePath(entry);
            if (sourceArchive && isDirectUsrBinEntry(entry)) {
                sourceCommands.add(entry.substring("usr/bin/".length()));
                hasSourceCommand = true;
            }
        }
        boolean hasSelectedEntry = hasSourceCommand;
        if (!hasSelectedEntry) {
            for (String entry : names) {
                if (isRuntimeLibraryEntry(entry) || isRuntimeToolEntry(entry)
                        || isSharedRuntimeDataEntry(entry)) {
                    hasSelectedEntry = true;
                    break;
                }
            }
        }
        if (!hasSelectedEntry) return;
        Result verboseResult = runTool(context, "libarchphene_bsdtar.so",
                Arrays.asList("-tvf", archive.getPath()));
        if (verboseResult.exitCode != 0) {
            throw new SecurityException("Could not inspect verified Arch package metadata");
        }
        String[] verbose = verboseResult.output.split("\\r?\\n");
        if (names.length != verbose.length) {
            throw new SecurityException("Arch package archive listing mismatch");
        }
        ArrayList<String[]> symlinks = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            String entry = names[index];
            validateArchivePath(entry);
            boolean selected = isRuntimeLibraryEntry(entry)
                    || isRuntimeToolEntry(entry)
                    || sourceArchive && isDirectUsrBinEntry(entry)
                    || sourceArchive && hasSourceCommand && isSourceRuntimeDataEntry(entry)
                    || isSharedRuntimeDataEntry(entry);
            if (!selected) continue;
            char type = verbose[index].isEmpty() ? '?' : verbose[index].charAt(0);
            File destination = safeStagingFile(root, entry);
            if (type == 'd') {
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IllegalStateException("Could not create staged directory");
                }
            } else if (type == '-' || type == 'h') {
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("Could not create staged parent");
                }
                extractArchiveFile(context, archive, entry, destination);
            } else if (type == 'l') {
                int marker = verbose[index].lastIndexOf(" -> ");
                if (marker < 0) throw new SecurityException("Malformed archive symlink");
                String target = normalizeSymlinkTarget(root, entry,
                        verbose[index].substring(marker + 4));
                symlinks.add(new String[] {entry, target});
            } else {
                throw new SecurityException("Unsupported selected archive entry type: " + type);
            }
        }
        for (String[] symlink : symlinks) {
            File destination = safeStagingFile(root, symlink[0]);
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create staged symlink parent");
            }
            if (destination.exists()) throw new SecurityException("Archive symlink collision");
            Os.symlink(symlink[1], destination.getPath());
        }
    }

    private static boolean isDirectUsrBinEntry(String entry) {
        if (!entry.startsWith("usr/bin/")) return false;
        String name = entry.substring("usr/bin/".length());
        return name.matches("[a-zA-Z0-9@._+:-]{1,128}");
    }
    private static boolean isRuntimeLibraryEntry(String entry) {
        if (!entry.startsWith("usr/lib/") || entry.startsWith("usr/lib/getconf/")
                || entry.startsWith("usr/lib/gconv/") || entry.startsWith("usr/lib/audit/")) {
            return false;
        }
        if (entry.endsWith("/")) return true;
        String name = entry.substring(entry.lastIndexOf('/') + 1);
        return name.endsWith(".so") || name.contains(".so.");
    }
    private static boolean isSourceRuntimeDataEntry(String entry) {
        return entry.equals("usr/share/") || entry.startsWith("usr/share/");
    }

    private static boolean isRuntimeToolEntry(String entry) {
        return "usr/bin/glib-compile-schemas".equals(entry)
                || "usr/bin/update-mime-database".equals(entry);
    }

    private static boolean isSharedRuntimeDataEntry(String entry) {
        return entry.startsWith("usr/share/glvnd/egl_vendor.d/")
                || entry.startsWith("usr/share/vulkan/icd.d/")
                || entry.startsWith("usr/share/drirc.d/")
                || entry.startsWith("usr/share/glib-2.0/schemas/")
                || entry.startsWith("usr/share/X11/xkb/")
                || entry.startsWith("usr/share/xkeyboard-config-2/")
                || entry.startsWith("usr/share/mime/")
                || entry.startsWith("usr/share/themes/")
                || entry.startsWith("usr/share/icons/Adwaita/")
                || entry.startsWith("usr/share/gtksourceview-");
    }

    private static void prepareSharedData(Context context, File root) throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir)
                .getCanonicalFile();
        File libraryPath = new File(root, "usr/lib").getCanonicalFile();

        File schemas = new File(root, "usr/share/glib-2.0/schemas").getCanonicalFile();
        File[] schemaFiles = schemas.isDirectory()
                ? schemas.listFiles((directory, name) -> name.endsWith(".xml")) : null;
        if (schemaFiles != null && schemaFiles.length > 0) {
            File compiler = new File(root, "usr/bin/glib-compile-schemas").getCanonicalFile();
            if (!compiler.isFile()) {
                throw new SecurityException("Verified closure has schemas but no schema compiler");
            }
            String output = runSharedDataTool(context, root, nativeDir, libraryPath,
                    Arrays.asList(compiler.getPath(), "--strict", schemas.getPath()));
            File compiled = new File(schemas, "gschemas.compiled");
            if (!compiled.isFile() || compiled.length() <= 0
                    || compiled.length() > 16L * 1024 * 1024) {
                throw new SecurityException("Could not compile verified GSettings schemas "
                        + output);
            }
            android.util.Log.i("ArchphenePackages", "compiled " + schemaFiles.length
                    + " verified GSettings schemas");
        }

        File mime = new File(root, "usr/share/mime").getCanonicalFile();
        File mimePackages = new File(mime, "packages");
        File[] mimeFiles = mimePackages.isDirectory()
                ? mimePackages.listFiles((directory, name) -> name.endsWith(".xml")) : null;
        if (mimeFiles != null && mimeFiles.length > 0) {
            File updater = new File(root, "usr/bin/update-mime-database").getCanonicalFile();
            if (!updater.isFile()) {
                throw new SecurityException("Verified closure has MIME data but no cache builder");
            }
            String output = runSharedDataTool(context, root, nativeDir, libraryPath,
                    Arrays.asList(updater.getPath(), mime.getPath()));
            File cache = new File(mime, "mime.cache");
            if (!cache.isFile() || cache.length() <= 0
                    || cache.length() > 32L * 1024 * 1024) {
                throw new SecurityException("Could not compile verified shared MIME database "
                        + output);
            }
            android.util.Log.i("ArchphenePackages", "compiled " + mimeFiles.length
                    + " verified shared MIME definitions");
        }
    }

    private static String runSharedDataTool(Context context, File root, File nativeDir,
            File libraryPath, List<String> arguments) throws Exception {
        File loader = executable(nativeDir, "libarchphene_ld.so");
        ArrayList<String> command = new ArrayList<>();
        command.add(loader.getPath());
        command.add("--library-path");
        command.add(libraryPath.getPath());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(state(context));
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("HOME", directory(state(context), "home").getPath());
        environment.put("TMPDIR", directory(state(context), "tmp").getPath());
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        environment.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        environment.put("LD_PRELOAD", executable(nativeDir,
                "libarchphene_path_bridge.so").getPath());
        environment.put("ARCHPHENE_RUNTIME_ROOT", root.getPath());
        Process process = builder.start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = readBounded(input, 1024 * 1024);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new SecurityException("Verified shared-data tool failed (exit "
                    + exitCode + ") " + output);
        }
        return output;
    }    private static boolean containsPackage(List<ResolvedPackage> packages, String name) {
        for (ResolvedPackage value : packages) {
            if (name.equals(value.name)) return true;
        }
        return false;
    }
    private static void extractArchiveFile(Context context, File archive, String entry,
            File destination) throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        File loader = executable(nativeDir, "libarchphene_ld.so");
        File tool = executable(nativeDir, "libarchphene_bsdtar.so");
        List<String> command = new ArrayList<>(Arrays.asList(loader.getPath(),
                "--library-path", nativeDir.getPath(), tool.getPath(),
                "-xOf", archive.getPath(), entry));
        File error = new File(state(context), "bsdtar-extract-"
                + Thread.currentThread().getId() + ".err");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(state(context));
        builder.redirectOutput(destination);
        builder.redirectError(error);
        Map<String, String> environment = builder.environment();
        environment.put("HOME", directory(state(context), "home").getPath());
        environment.put("TMPDIR", directory(state(context), "tmp").getPath());
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        int exitCode = builder.start().waitFor();
        String diagnostics = error.isFile() ? readText(error, OUTPUT_LIMIT) : "";
        error.delete();
        if (exitCode != 0 || !destination.isFile()
                || destination.length() > 256L * 1024 * 1024) {
            destination.delete();
            throw new SecurityException("Could not decode archive entry " + entry
                    + " (exit " + exitCode + ") " + diagnostics);
        }
    }

    private static void validateArchivePath(String entry) {
        if (entry.isEmpty() || entry.startsWith("/") || entry.indexOf('\\') >= 0
                || entry.equals("..") || entry.startsWith("../")
                || entry.contains("/../") || entry.endsWith("/..")) {
            throw new SecurityException("Arch package contains an unsafe path");
        }
    }

    private static File safeStagingFile(File root, String entry) throws Exception {
        File value = new File(root, entry).getCanonicalFile();
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!value.getPath().startsWith(rootPath)) {
            throw new SecurityException("Arch package path escapes staging root");
        }
        return value;
    }

    private static String normalizeSymlinkTarget(File root, String entry, String target)
            throws Exception {
        if (target.isEmpty() || target.indexOf('\\') >= 0) {
            throw new SecurityException("Arch package contains an unsafe symlink");
        }
        String normalized = target;
        if (target.startsWith("/")) {
            if (!target.startsWith("/usr/lib/") && !target.startsWith("/usr/share/")) {
                throw new SecurityException("Arch package contains an unsafe absolute symlink");
            }
            java.nio.file.Path logicalParent = java.nio.file.Paths.get("/" + entry).getParent();
            normalized = logicalParent.relativize(java.nio.file.Paths.get(target).normalize())
                    .toString();
        }
        File parent = new File(root, entry).getParentFile();
        File resolved = new File(parent, normalized).getCanonicalFile();
        if (!resolved.getPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new SecurityException("Arch package symlink escapes staging root");
        }
        return normalized;
    }
    public static synchronized Verification verifyPackage(Context context, File packageFile,
            File signatureFile) throws Exception {
        requireManagedFile(context, packageFile);
        requireManagedFile(context, signatureFile);
        File state = state(context);
        File gnupg = ensureTrustDatabase(context, state);
        File keybox = new File(gnupg, "pubring.kbx");
        Result result = runTool(context, "libarchphene_gpgv.so", Arrays.asList(
                "--homedir", gnupg.getPath(), "--keyring", keybox.getPath(),
                "--status-fd", "1", signatureFile.getCanonicalPath(),
                packageFile.getCanonicalPath()));
        if (result.exitCode != 0 || hasRejectedStatus(result.output)) {
            throw new SecurityException("Arch package signature verification failed\n" + result.output);
        }
        String signer = "";
        String primary = "";
        for (String line : result.output.split("\\r?\\n")) {
            String marker = "[GNUPG:] VALIDSIG ";
            int index = line.indexOf(marker);
            if (index < 0) continue;
            String[] fields = line.substring(index + marker.length()).trim().split("\\s+");
            if (fields.length > 0) signer = fields[0].toUpperCase(Locale.ROOT);
            if (fields.length > 9) primary = fields[fields.length - 1].toUpperCase(Locale.ROOT);
        }
        if (!signer.matches("[0-9A-F]{40,64}")) {
            throw new SecurityException("Arch package signature returned no valid fingerprint");
        }
        Set<String> revoked = readFingerprintSet(new File(state, "trust/archlinux-revoked"));
        if (revoked.contains(signer) || revoked.contains(primary)) {
            throw new SecurityException("Arch package signer is explicitly revoked");
        }
        return new Verification(signer, primary, result.output);
    }

    private static File ensureTrustDatabase(Context context, File state) throws Exception {
        File trust = directory(state, "trust");
        File anchor = new File(trust, "archlinux.gpg");
        File revoked = new File(trust, "archlinux-revoked");
        String assetHash;
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "archlinux.gpg")) {
            assetHash = copyAndHash(input, anchor);
        }
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "archlinux-revoked")) {
            copyAndHash(input, revoked);
        }
        File marker = new File(trust, "anchor.sha256");
        File gnupg = new File(state, "gnupg");
        String installedHash = marker.isFile() ? readText(marker, 256).trim() : "";
        if (assetHash.equals(installedHash) && new File(gnupg, "pubring.kbx").isFile()) {
            return gnupg;
        }
        deleteRecursively(gnupg);
        if (!gnupg.mkdirs()) throw new IllegalStateException("Could not create package trust database");
        Result imported = runTool(context, "libarchphene_gpg.so", Arrays.asList(
                "--homedir", gnupg.getPath(), "--batch", "--no-autostart",
                "--no-auto-check-trustdb", "--import", anchor.getPath()));
        if (imported.exitCode != 0 || !new File(gnupg, "pubring.kbx").isFile()) {
            deleteRecursively(gnupg);
            throw new SecurityException("Could not import Arch trust anchor\n" + imported.output);
        }
        writeText(marker, assetHash + "\n");
        return gnupg;
    }

    private static Result runTool(Context context, String toolName, List<String> arguments)
            throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        File loader = executable(nativeDir, "libarchphene_ld.so");
        File tool = executable(nativeDir, toolName);
        File state = state(context);
        File home = directory(state, "home");
        File temp = directory(state, "tmp");
        List<String> command = new ArrayList<>();

        command.addAll(Arrays.asList(loader.getPath(),
                "--library-path", nativeDir.getPath(), tool.getPath()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(state);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("HOME", home.getPath());
        environment.put("TMPDIR", temp.getPath());
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        Process process = builder.start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = readBounded(input);
        }
        int exitCode = process.waitFor();
        if ("libarchphene_bsdtar.so".equals(toolName)) {
            android.util.Log.i("ArchphenePackages", "bsdtar exit=" + exitCode
                    + " args=" + arguments + " outputChars=" + output.length());
        }
        return new Result(exitCode, output);
    }

    private static void download(String endpoint, File target, long limit) throws Exception {
        URL url = new URL(endpoint);
        validatePackageHost(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "Archphene/1.0");
        File temporary = new File(target.getParentFile(), target.getName() + ".new");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Arch repository HTTP " + connection.getResponseCode());
            }
            long declared = connection.getContentLengthLong();
            if (declared < 0 || declared > limit) {
                throw new SecurityException("Arch repository object has invalid size");
            }
            long total = 0;
            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) throw new SecurityException("Arch repository object is too large");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (total == 0 || declared != total) throw new SecurityException("Incomplete Arch repository object");
            if (target.exists() && !target.delete()) throw new IllegalStateException("Could not replace download");
            if (!temporary.renameTo(target)) throw new IllegalStateException("Could not install download");
        } finally {
            connection.disconnect();
            if (temporary.exists() && !temporary.equals(target)) temporary.delete();
        }
    }

    private static void validatePackageUrl(String endpoint, String repository) throws Exception {
        URL url = new URL(endpoint);
        validatePackageHost(url);
        String expected = "/" + repository + "/os/x86_64/";
        if (!("core".equals(repository) || "extra".equals(repository))
                || !url.getPath().startsWith(expected)) {
            throw new SecurityException("Package URL does not match its Arch repository");
        }
    }

    private static void validatePackageHost(URL url) {
        if (!"https".equals(url.getProtocol())
                || !"geo.mirror.pkgbuild.com".equals(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new SecurityException("Unsupported Arch package endpoint");
        }
    }

    private static File state(Context context) {
        return directory(context.getFilesDir(), "package-runtime");
    }

    private static File executable(File nativeDir, String name) throws Exception {
        File file = new File(nativeDir, name).getCanonicalFile();
        if (!file.getParentFile().equals(nativeDir) || !file.isFile()) {
            throw new SecurityException("Package runtime executable is unavailable: " + name);
        }
        return file;
    }

    private static void requireManagedFile(Context context, File input) throws Exception {
        File file = input.getCanonicalFile();
        String path = file.getPath();
        String files = context.getFilesDir().getCanonicalPath() + File.separator;
        String cache = context.getCacheDir().getCanonicalPath() + File.separator;
        if (!file.isFile() || !(path.startsWith(files) || path.startsWith(cache))) {
            throw new SecurityException("Package input must be an app-private regular file");
        }
    }

    private static boolean hasRejectedStatus(String output) {
        return output.contains("[GNUPG:] BADSIG ") || output.contains("[GNUPG:] ERRSIG ")
                || output.contains("[GNUPG:] REVKEYSIG ")
                || output.contains("[GNUPG:] EXPKEYSIG ")
                || output.contains("[GNUPG:] EXPSIG ");
    }

    private static Set<String> readFingerprintSet(File file) throws Exception {
        Set<String> result = new HashSet<>();
        if (!file.isFile()) return result;
        for (String line : readText(file, 64 * 1024).split("\\r?\\n")) {
            String value = line.trim().toUpperCase(Locale.ROOT);
            if (value.matches("[0-9A-F]{40,64}")) result.add(value);
        }
        return result;
    }

    private static File directory(File parent, String name) {
        File directory = new File(parent, name);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create package runtime " + name);
        }
        return directory;
    }

    private static String copyAndHash(InputStream input, File target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File temporary = new File(target.getParentFile(), target.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Could not replace trust file");
        if (!temporary.renameTo(target)) throw new IllegalStateException("Could not install trust file");
        return hex(digest.digest());
    }

    private static String readText(File file, int limit) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return readBounded(input, limit); }
    }

    private static void writeText(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String readBounded(InputStream input) throws Exception {
        return readBounded(input, OUTPUT_LIMIT);
    }

    private static String readBounded(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) throw new IllegalStateException("Bounded input exceeded limit");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    static void releaseStaging(Context context, StagedTransaction staged) {
        if (staged == null || staged.root == null) return;
        try {
            File stagingRoot = new File(state(context), "staging").getCanonicalFile();
            File transaction = staged.root.getParentFile().getCanonicalFile();
            if (!transaction.getParentFile().equals(stagingRoot)) {
                throw new SecurityException("Transaction staging path is outside package state");
            }
            deleteRecursively(transaction);
        } catch (Exception error) {
            android.util.Log.w("ArchphenePackages", "Could not release transaction staging", error);
        }
    }
    private static void deleteRecursively(File file) {
        StructStat stat;
        try {
            stat = Os.lstat(file.getPath());
        } catch (ErrnoException missing) {
            if (missing.errno == OsConstants.ENOENT) return;
            throw new IllegalStateException("Could not inspect staging path: " + file, missing);
        }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IllegalStateException("Could not remove staging path: " + file);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }
}