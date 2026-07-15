package org.archpheneos.manager;

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Immutable, content-addressed runtime packs derived from verified package closures. */
final class RuntimePackStore {
    private static final String SCHEMA = "# org.archphene.runtime-pack.v1";
    private static final int MAX_PACKAGES = 2048;
    private static final int MAX_MODULES = 4096;
    private static final int MAX_LINE = 1024;
    private static final long MAX_MODULE_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long MAX_PACK_SIZE = 8L * 1024 * 1024 * 1024;
    private static final int MAX_DATA_FILES = 100000;
    private static final long MAX_DATA_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long UNBOUND_PACK_GRACE_MS = 24L * 60 * 60 * 1000;
    private static final String SAFE_ID = "[a-zA-Z0-9@._+:-]{1,128}";
    private static final String SAFE_REPOSITORY = "[a-z0-9-]{1,32}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String SAFE_FILE = "[A-Za-z0-9._+-]{1,128}";
    private static final Map<String, Pack> VERIFIED_PACKS = new HashMap<>();

    static final class Module {
        final String kind;
        final String hash;
        final long size;
        final String linkName;
        final File file;

        Module(String kind, String hash, long size, String linkName, File file) {
            this.kind = kind;
            this.hash = hash;
            this.size = size;
            this.linkName = linkName;
            this.file = file;
        }

        Uri uri(String packId) {
            return Uri.parse("content://" + RuntimeModuleProvider.AUTHORITY
                    + "/pack/v1/" + packId + "/" + hash);
        }
    }

    static final class Pack {
        final String id;
        final String sourcePackage;
        final String executableName;
        final List<ArchPackageRuntime.ResolvedPackage> packages;
        final List<Module> modules;
        final File root;

        Pack(String id, String sourcePackage, String executableName,
                List<ArchPackageRuntime.ResolvedPackage> packages,
                List<Module> modules, File root) {
            this.id = id;
            this.sourcePackage = sourcePackage;
            this.executableName = executableName;
            this.packages = Collections.unmodifiableList(packages);
            this.modules = Collections.unmodifiableList(modules);
            this.root = root;
        }

        Module requireKind(String kind) throws FileNotFoundException {
            for (Module module : modules) {
                if (kind.equals(module.kind)) return module;
            }
            throw new FileNotFoundException("Runtime pack has no " + kind + " module");
        }

        List<Module> libraries() {
            ArrayList<Module> result = new ArrayList<>();
            for (Module module : modules) {
                if ("library".equals(module.kind)) result.add(module);
            }
            return result;
        }

        Module data() {
            for (Module module : modules) {
                if ("data".equals(module.kind)) return module;
            }
            return null;
        }

        String toolkit() {
            for (Module module : modules) {
                if (module.linkName.startsWith("libQt6")) return "qt6";
            }
            for (Module module : modules) {
                if (module.linkName.startsWith("libgtk-3")) return "gtk3";
            }
            return "wayland";
        }
    }

    private static final class PendingModule {
        final String kind;
        final String linkName;
        final File source;

        PendingModule(String kind, String linkName, File source) {
            this.kind = kind;
            this.linkName = linkName;
            this.source = source;
        }
    }

    private RuntimePackStore() {}

    static synchronized Pack build(Context context, String sourcePackage,
            List<ArchPackageRuntime.ResolvedPackage> packages, File stagedRoot) throws Exception {
        return build(context, sourcePackage, sourcePackage, packages, stagedRoot);
    }

    static synchronized Pack build(Context context, String sourcePackage, String executableName,
            List<ArchPackageRuntime.ResolvedPackage> packages, File stagedRoot) throws Exception {
        if (stagedRoot == null || sourcePackage == null || !sourcePackage.matches(SAFE_ID)
                || executableName == null || !executableName.matches(SAFE_ID)) {
            throw new IllegalArgumentException("Invalid staged package transaction");
        }
        File canonicalRoot = stagedRoot.getCanonicalFile();
        File executable = new File(canonicalRoot,
                "usr/bin/" + executableName).getCanonicalFile();
        if (!executable.isFile() || !inside(canonicalRoot, executable)) {
            throw new SecurityException("Runtime pack executable escapes staging root");
        }
        File managerNative = new File(context.getApplicationInfo().nativeLibraryDir)
                .getCanonicalFile();
        File loader = new File(managerNative, "libarchphene_ld.so").getCanonicalFile();
        if (!loader.isFile() || !loader.getParentFile().equals(managerNative)) {
            throw new SecurityException("Runtime pack loader is unavailable");
        }
        File pathBridge = new File(managerNative, "libarchphene_path_bridge.so")
                .getCanonicalFile();
        if (!pathBridge.isFile() || !pathBridge.getParentFile().equals(managerNative)) {
            throw new SecurityException("Runtime path bridge is unavailable");
        }
        Map<String, File> closure = ArchWrapperAssembler.collectNativeFiles(
                context, canonicalRoot, sourcePackage, executableName);
        File gtkSvgLoader = null;
        if (closure.containsKey("libgtk-3.so.0")) {
            File gtkPixbuf = new File(managerNative, "libarchphene_gtk3_pixbuf.so")
                    .getCanonicalFile();
            File gtkRsvg = new File(managerNative, "libarchphene_gtk3_rsvg.so")
                    .getCanonicalFile();
            gtkSvgLoader = new File(managerNative,
                    "libarchphene_gtk3_pixbufloader_svg.so").getCanonicalFile();
            for (File bridge : new File[] {gtkPixbuf, gtkRsvg, gtkSvgLoader}) {
                if (!bridge.isFile() || !bridge.getParentFile().equals(managerNative)) {
                    throw new SecurityException("GTK3 compatibility bridge is unavailable");
                }
            }
            closure.put("libgdk_pixbuf-2.0.so.0", gtkPixbuf);
            closure.put("librsvg-2.so.2", gtkRsvg);
        }
        ArrayList<PendingModule> pending = new ArrayList<>();
        pending.add(new PendingModule("program", "program", executable));
        pending.add(new PendingModule("loader", "loader", loader));
        Set<String> names = new HashSet<>();
        names.add("libarchphene_path_bridge.so");
        pending.add(new PendingModule("library", "libarchphene_path_bridge.so", pathBridge));
        if (gtkSvgLoader != null) {
            names.add("libarchphene_pixbufloader_svg.so");
            pending.add(new PendingModule("library", "libarchphene_pixbufloader_svg.so",
                    gtkSvgLoader));
        }
        for (Map.Entry<String, File> entry : closure.entrySet()) {
            File source = entry.getValue().getCanonicalFile();
            if (source.equals(executable) || source.equals(loader)) continue;
            if (!safeFileName(entry.getKey()) || !names.add(entry.getKey())) {
                throw new SecurityException("Runtime closure has an unsafe or duplicate link name");
            }
            pending.add(new PendingModule("library", entry.getKey(), source));
        }
        File store = directory(context.getFilesDir(), "runtime-packs");
        File stagingRoot = directory(store, "staging");
        File packsRoot = directory(store, "packs");
        File temporary = new File(stagingRoot, "pack-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        File moduleRoot = new File(temporary, "modules");
        if (!moduleRoot.mkdirs()) throw new IOException("Could not create runtime-pack staging");
        boolean published = false;
        File dataArchive = null;
        try {
            File dataRoot = new File(canonicalRoot, "usr/share");
            if (dataRoot.isDirectory()) {
                dataArchive = File.createTempFile("runtime-data-", ".zip", stagingRoot);
                createDataArchive(canonicalRoot, dataRoot, dataArchive);
                if (dataArchive.length() > 0) {
                    pending.add(new PendingModule("data", "root.zip", dataArchive));
                }
            }
            if (pending.size() < 3 || pending.size() > MAX_MODULES) {
                throw new SecurityException("Runtime closure has an invalid module count");
            }
            pending.sort(Comparator.comparing((PendingModule value) -> kindOrder(value.kind))
                    .thenComparing(value -> value.linkName));
            ArrayList<Module> modules = new ArrayList<>();
            long total = 0;
            for (PendingModule value : pending) {
                CopiedModule copied = copyModule(value.source, moduleRoot);
                total = Math.addExact(total, copied.size);
                if (total > MAX_PACK_SIZE) throw new SecurityException("Runtime pack is too large");
                modules.add(new Module(value.kind, copied.hash, copied.size,
                        value.linkName, copied.file));
            }
            byte[] manifest = manifest(sourcePackage, executableName, packages, modules);
            String packId = sha256(manifest);
            File manifestFile = new File(temporary, "manifest.tsv");
            writeSynced(manifestFile, manifest);
            File destination = new File(packsRoot, packId);
            if (destination.isDirectory()) {
                deleteRecursively(temporary);
                return load(context, packId);
            }
            if (destination.exists() || !temporary.renameTo(destination)) {
                throw new IOException("Could not atomically publish runtime pack");
            }
            syncDirectory(packsRoot);
            published = true;
            return load(context, packId);
        } finally {
            if (dataArchive != null) dataArchive.delete();
            if (!published && temporary.exists()) deleteRecursively(temporary);
        }
    }

    static synchronized void activate(Context context, String androidPackage, String packId)
            throws Exception {
        if (androidPackage == null || !androidPackage.matches("[a-zA-Z0-9._]{3,255}")) {
            throw new IllegalArgumentException("Invalid Android package binding");
        }
        Pack pack = load(context, packId);
        File bindings = directory(directory(context.getFilesDir(), "runtime-packs"), "bindings");
        String bindingName = sha256(androidPackage.getBytes(StandardCharsets.UTF_8));
        File target = new File(bindings, bindingName + ".tsv");
        String value = "# org.archphene.runtime-binding.v1\n"
                + "package\t" + androidPackage + "\npack\t" + pack.id + "\n";
        writeAtomically(target, value.getBytes(StandardCharsets.UTF_8));
    }

    static synchronized Pack active(Context context, String androidPackage) throws Exception {
        if (androidPackage == null || !androidPackage.matches("[a-zA-Z0-9._]{3,255}")) {
            throw new IllegalArgumentException("Invalid Android package binding");
        }
        File bindings = directory(directory(context.getFilesDir(), "runtime-packs"), "bindings");
        File binding = new File(bindings,
                sha256(androidPackage.getBytes(StandardCharsets.UTF_8)) + ".tsv");
        if (!binding.isFile()) throw new FileNotFoundException("No active runtime pack");
        List<String> lines = readLines(binding, 4, MAX_LINE);
        if (lines.size() != 3 || !"# org.archphene.runtime-binding.v1".equals(lines.get(0))
                || !("package\t" + androidPackage).equals(lines.get(1))
                || !lines.get(2).startsWith("pack\t")) {
            throw new SecurityException("Malformed runtime-pack binding");
        }
        return load(context, lines.get(2).substring(5));
    }

    static Module requireUri(Context context, Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())
                || !RuntimeModuleProvider.AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("Invalid runtime-pack URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 4 || !"pack".equals(segments.get(0))
                || !"v1".equals(segments.get(1))) {
            throw new FileNotFoundException("Invalid runtime-pack URI");
        }
        Pack pack = load(context, segments.get(2));
        for (Module module : pack.modules) {
            if (module.hash.equals(segments.get(3)) && module.uri(pack.id).equals(uri)) {
                return module;
            }
        }
        throw new FileNotFoundException("Runtime-pack module is unavailable");
    }

    static synchronized void grantActive(Context context, String androidPackage) throws Exception {
        Pack pack = active(context, androidPackage);
        for (Module module : pack.modules) {
            context.grantUriPermission(androidPackage, module.uri(pack.id),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    static synchronized void removeBinding(Context context, String androidPackage)
            throws Exception {
        Pack active = null;
        try {
            active = active(context, androidPackage);
        } catch (FileNotFoundException ignored) {}
        File bindings = directory(directory(context.getFilesDir(), "runtime-packs"), "bindings");
        File binding = new File(bindings,
                sha256(androidPackage.getBytes(StandardCharsets.UTF_8)) + ".tsv");
        if (binding.exists() && !binding.delete()) {
            throw new IOException("Could not remove runtime-pack binding");
        }
        syncDirectory(bindings);
        if (active != null) {
            for (Module module : active.modules) {
                context.revokeUriPermission(module.uri(active.id),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        garbageCollect(context);
    }

    static synchronized int garbageCollect(Context context) throws Exception {
        File store = directory(context.getFilesDir(), "runtime-packs");
        File bindings = directory(store, "bindings");
        File packs = directory(store, "packs");
        Set<String> live = new HashSet<>();
        File[] bindingFiles = bindings.listFiles();
        if (bindingFiles != null) {
            for (File binding : bindingFiles) {
                try {
                    List<String> lines = readLines(binding, 4, MAX_LINE);
                    if (lines.size() != 3 || !lines.get(1).startsWith("package\t")
                            || !lines.get(2).startsWith("pack\t")) {
                        throw new IOException("Malformed binding");
                    }
                    String androidPackage = lines.get(1).substring(8);
                    String packId = lines.get(2).substring(5);
                    if (context.getPackageManager().getLaunchIntentForPackage(androidPackage)
                            == null) {
                        binding.delete();
                    } else {
                        load(context, packId);
                        live.add(packId);
                    }
                } catch (Exception invalid) {
                    binding.delete();
                }
            }
        }
        int removed = 0;
        File[] packDirectories = packs.listFiles();
        if (packDirectories != null) {
            long oldestRemovable = System.currentTimeMillis() - UNBOUND_PACK_GRACE_MS;
            for (File pack : packDirectories) {
                if (!live.contains(pack.getName()) && pack.lastModified() < oldestRemovable) {
                    VERIFIED_PACKS.remove(pack.getName());
                    deleteRecursively(pack);
                    removed++;
                }
            }
        }
        syncDirectory(bindings);
        syncDirectory(packs);
        return removed;
    }

    static Pack load(Context context, String packId) throws Exception {
        if (packId == null || !packId.matches(HASH)) {
            throw new FileNotFoundException("Invalid runtime-pack identity");
        }
        File packs = directory(directory(context.getFilesDir(), "runtime-packs"), "packs");
        File root = new File(packs, packId).getCanonicalFile();
        if (!root.isDirectory() || !root.getParentFile().equals(packs.getCanonicalFile())) {
            throw new FileNotFoundException("Runtime pack is unavailable");
        }
        File manifest = new File(root, "manifest.tsv").getCanonicalFile();
        if (!manifest.isFile() || !manifest.getParentFile().equals(root)) {
            throw new SecurityException("Runtime-pack manifest is missing");
        }
        byte[] bytes = readBounded(manifest, 4 * 1024 * 1024);
        if (!packId.equals(sha256(bytes))) {
            throw new SecurityException("Runtime-pack manifest identity mismatch");
        }
        Pack cached = VERIFIED_PACKS.get(packId);
        if (cached != null && cached.root.equals(root)) return cached;
        Pack parsed = parse(packId, root, bytes);
        VERIFIED_PACKS.put(packId, parsed);
        return parsed;
    }

    static void verifyParserForTest() throws Exception {
        String hashA = repeat('a', 64);
        String hashB = repeat('b', 64);
        String hashC = repeat('c', 64);
        String valid = SCHEMA + "\nsource\tkcalc\nexecutable\tkcalc\n"
                + "package\textra\tkcalc\t1.0-1\n"
                + "module\tprogram\t" + hashA + "\t100\tprogram\n"
                + "module\tloader\t" + hashB + "\t200\tloader\n"
                + "module\tlibrary\t" + hashC + "\t300\tlibc.so.6\n";
        parse("d" + repeat('0', 63), new File("."),
                valid.getBytes(StandardCharsets.UTF_8), false);
        expectRejected(valid.replace("libc.so.6", "../libc.so.6"));
        expectRejected(valid + "module\tlibrary\t" + hashA + "\t100\tlibc.so.6\n");
        expectRejected(valid.replace("module\tloader", "module\tlibrary"));
        expectRejected(valid.replace("module\tloader", "module\tprogram"));
        expectRejected(valid.replace("module\tlibrary", "module\tunknown"));
        expectRejected(valid.replace("\t300\t", "\t0\t"));
        expectRejected(valid.replace(SCHEMA, "# org.archphene.runtime-pack.v2"));
    }

    private static Pack parse(String packId, File root, byte[] manifest) throws Exception {
        return parse(packId, root, manifest, true);
    }

    private static Pack parse(String packId, File root, byte[] manifest, boolean requireFiles)
            throws Exception {
        ArrayList<ArchPackageRuntime.ResolvedPackage> packages = new ArrayList<>();
        ArrayList<Module> modules = new ArrayList<>();
        Set<String> links = new HashSet<>();
        String sourcePackage = null;
        String executableName = null;
        int programs = 0;
        int loaders = 0;
        int dataModules = 0;
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(manifest), StandardCharsets.UTF_8))) {
            if (!SCHEMA.equals(reader.readLine())) {
                throw new IOException("Unsupported runtime-pack schema");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE) throw new IOException("Runtime-pack line is too long");
                String[] fields = line.split("\\t", -1);
                if (fields.length == 2 && "source".equals(fields[0])) {
                    if (sourcePackage != null || !fields[1].matches(SAFE_ID)) {
                        throw new IOException("Malformed runtime-pack source");
                    }
                    sourcePackage = fields[1];
                } else if (fields.length == 2 && "executable".equals(fields[0])) {
                    if (executableName != null || !fields[1].matches(SAFE_ID)) {
                        throw new IOException("Malformed runtime-pack executable");
                    }
                    executableName = fields[1];
                } else if (fields.length == 4 && "package".equals(fields[0])) {
                    if (!fields[1].matches(SAFE_REPOSITORY) || !fields[2].matches(SAFE_ID)
                            || !fields[3].matches(SAFE_ID) || packages.size() >= MAX_PACKAGES) {
                        throw new IOException("Malformed runtime-pack package");
                    }
                    packages.add(new ArchPackageRuntime.ResolvedPackage(fields[2], fields[3],
                            fields[1], "", ""));
                } else if (fields.length == 5 && "module".equals(fields[0])) {
                    String kind = fields[1];
                    if (!("program".equals(kind) || "loader".equals(kind)
                            || "library".equals(kind) || "data".equals(kind))
                            || !fields[2].matches(HASH)
                            || !safeLinkName(kind, fields[4]) || !links.add(fields[4])
                            || modules.size() >= MAX_MODULES) {
                        throw new IOException("Malformed runtime-pack module");
                    }
                    long size;
                    try {
                        size = Long.parseLong(fields[3]);
                    } catch (NumberFormatException error) {
                        throw new IOException("Malformed runtime-pack module size", error);
                    }
                    if (size <= 0 || size > MAX_MODULE_SIZE) {
                        throw new IOException("Runtime-pack module exceeds bounds");
                    }
                    if ("program".equals(kind)) programs++;
                    if ("loader".equals(kind)) loaders++;
                    if ("data".equals(kind)) dataModules++;
                    total = Math.addExact(total, size);
                    if (total > MAX_PACK_SIZE) throw new IOException("Runtime pack is too large");
                    File file = new File(new File(root, "modules"), fields[2]);
                    if (requireFiles) verifyModule(root, file, fields[2], size);
                    modules.add(new Module(kind, fields[2], size, fields[4], file));
                } else {
                    throw new IOException("Malformed runtime-pack manifest entry");
                }
            }
        }
        if (sourcePackage == null || packages.isEmpty() || modules.size() < 3
                || programs != 1 || loaders != 1 || dataModules > 1) {
            throw new IOException("Runtime-pack manifest is incomplete");
        }
        if (executableName == null) executableName = sourcePackage;
        return new Pack(packId, sourcePackage, executableName, packages, modules, root);
    }

    private static byte[] manifest(String sourcePackage, String executableName,
            List<ArchPackageRuntime.ResolvedPackage> packages, List<Module> modules)
            throws Exception {
        if (!sourcePackage.matches(SAFE_ID) || executableName == null
                || !executableName.matches(SAFE_ID) || packages.isEmpty()
                || packages.size() > MAX_PACKAGES) {
            throw new SecurityException("Invalid runtime-pack package graph");
        }
        ArrayList<ArchPackageRuntime.ResolvedPackage> sortedPackages = new ArrayList<>(packages);
        sortedPackages.sort(Comparator.comparing((ArchPackageRuntime.ResolvedPackage value)
                -> value.repository).thenComparing(value -> value.name)
                .thenComparing(value -> value.version));
        StringBuilder value = new StringBuilder(SCHEMA).append('\n')
                .append("source\t").append(sourcePackage).append('\n')
                .append("executable\t").append(executableName).append('\n');
        for (ArchPackageRuntime.ResolvedPackage entry : sortedPackages) {
            if (!entry.repository.matches(SAFE_REPOSITORY) || !entry.name.matches(SAFE_ID)
                    || !entry.version.matches(SAFE_ID)) {
                throw new SecurityException("Invalid runtime-pack package metadata");
            }
            value.append("package\t").append(entry.repository).append('\t')
                    .append(entry.name).append('\t').append(entry.version).append('\n');
        }
        for (Module module : modules) {
            value.append("module\t").append(module.kind).append('\t').append(module.hash)
                    .append('\t').append(module.size).append('\t')
                    .append(module.linkName).append('\n');
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class DataBudget {
        int files;
        long bytes;
    }

    private static void createDataArchive(File stagedRoot, File dataRoot, File output)
            throws Exception {
        File canonicalRoot = stagedRoot.getCanonicalFile();
        DataBudget budget = new DataBudget();
        try (FileOutputStream file = new FileOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            addDataTree(canonicalRoot, dataRoot, "usr/share", zip, budget, new HashSet<>());
            zip.finish();
            zip.flush();
            file.getFD().sync();
        }
    }

    private static void addDataTree(File stagedRoot, File source, String relative,
            ZipOutputStream zip, DataBudget budget, Set<String> stack) throws Exception {
        File effective = source.getCanonicalFile();
        if (!effective.equals(stagedRoot) && !inside(stagedRoot, effective)) {
            throw new SecurityException("Runtime data symlink escapes staging root");
        }
        if (effective.isDirectory()) {
            String canonical = effective.getPath();
            if (!stack.add(canonical)) {
                throw new SecurityException("Runtime data contains a symlink cycle");
            }
            ZipEntry directory = new ZipEntry(relative + "/");
            directory.setTime(1577836800000L);
            zip.putNextEntry(directory);
            zip.closeEntry();
            File[] children = effective.listFiles();
            if (children == null) throw new IOException("Could not enumerate runtime data");
            java.util.Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                addDataTree(stagedRoot, child, relative + "/" + child.getName(),
                        zip, budget, stack);
            }
            stack.remove(canonical);
            return;
        }
        if (!effective.isFile()) {
            throw new SecurityException("Runtime data contains an unsupported file type");
        }
        budget.files++;
        budget.bytes = Math.addExact(budget.bytes, effective.length());
        if (budget.files > MAX_DATA_FILES || budget.bytes > MAX_DATA_BYTES) {
            throw new SecurityException("Runtime data exceeds archive bounds");
        }
        ZipEntry entry = new ZipEntry(relative);
        entry.setTime(1577836800000L);
        zip.putNextEntry(entry);
        try (InputStream input = new FileInputStream(effective)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }
    private static final class CopiedModule {
        final String hash;
        final long size;
        final File file;

        CopiedModule(String hash, long size, File file) {
            this.hash = hash;
            this.size = size;
            this.file = file;
        }
    }

    private static CopiedModule copyModule(File source, File moduleRoot) throws Exception {
        if (!source.isFile() || source.length() <= 0 || source.length() > MAX_MODULE_SIZE) {
            throw new SecurityException("Runtime-pack module has an invalid size");
        }
        File temporary = File.createTempFile("module-", ".new", moduleRoot);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > MAX_MODULE_SIZE) throw new SecurityException("Runtime module is too large");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        String hash = hex(digest.digest());
        File target = new File(moduleRoot, hash);
        if (target.isFile()) {
            temporary.delete();
        } else if (!temporary.renameTo(target)) {
            throw new IOException("Could not publish runtime-pack module");
        }
        syncDirectory(moduleRoot);
        return new CopiedModule(hash, size, target);
    }

    private static void verifyModule(File root, File file, String hash, long size)
            throws Exception {
        File canonicalRoot = root.getCanonicalFile();
        File moduleRoot = new File(canonicalRoot, "modules").getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!canonical.isFile() || !canonical.getParentFile().equals(moduleRoot)
                || canonical.length() != size || !hash.equals(sha256(canonical))) {
            throw new SecurityException("Runtime-pack module verification failed");
        }
    }

    private static boolean safeFileName(String value) {
        return value != null && value.matches(SAFE_FILE)
                && !".".equals(value) && !"..".equals(value)
                && !"program".equals(value) && !"loader".equals(value);
    }

    private static boolean safeLinkName(String kind, String value) {
        if ("program".equals(kind)) return "program".equals(value);
        if ("loader".equals(kind)) return "loader".equals(value);
        if ("data".equals(kind)) return "root.zip".equals(value);
        return "library".equals(kind) && safeFileName(value);
    }

    private static int kindOrder(String kind) {
        if ("program".equals(kind)) return 0;
        if ("loader".equals(kind)) return 1;
        if ("library".equals(kind)) return 2;
        return 3;
    }

    private static boolean inside(File root, File value) throws Exception {
        return value.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    private static File directory(File parent, String name) {
        File value = new File(parent, name);
        if (!value.isDirectory() && !value.mkdirs()) {
            throw new IllegalStateException("Could not create runtime-pack directory");
        }
        return value;
    }

    private static void writeAtomically(File target, byte[] value) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".new");
        writeSynced(temporary, value);
        Os.rename(temporary.getPath(), target.getPath());
        syncDirectory(target.getParentFile());
    }

    private static void writeSynced(File target, byte[] value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private static List<String> readLines(File file, int maxLines, int maxLine) throws Exception {
        ArrayList<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > maxLine || result.size() >= maxLines) {
                    throw new IOException("Runtime-pack state exceeds bounds");
                }
                result.add(line);
            }
        }
        return result;
    }

    private static byte[] readBounded(File file, int limit) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > limit) {
            throw new IOException("Runtime-pack file exceeds bounds");
        }
        byte[] result = new byte[(int) file.length()];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < result.length) {
                int read = input.read(result, offset, result.length - offset);
                if (read < 0) throw new IOException("Truncated runtime-pack file");
                offset += read;
            }
            if (input.read() != -1) throw new IOException("Runtime-pack file grew during read");
        }
        return result;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) result.append(String.format("%02x", current & 0xff));
        return result.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static void syncDirectory(File directory) throws Exception {
        FileDescriptor descriptor = Os.open(directory.getPath(),
                OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    private static void expectRejected(String manifest) throws Exception {
        try {
            parse("d" + repeat('0', 63), new File("."),
                    manifest.getBytes(StandardCharsets.UTF_8), false);
            throw new SecurityException("Malformed runtime pack was accepted");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static void deleteRecursively(File value) {
        File[] children = value.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        value.delete();
    }
}
