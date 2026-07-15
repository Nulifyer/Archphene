package org.archpheneos.terminal;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds a hash-verified command view in the isolated Terminal UID. */
final class TerminalEnvironment {
    private static final Uri PROVIDER = Uri.parse("content://org.archpheneos.manager.runtime");
    private static final String CATALOG_METHOD = "org.archphene.runtime.TERMINAL_CATALOG_V1";
    private static final String PACK_METHOD = "org.archphene.runtime.TERMINAL_PACK_V1";
    private static final String SAFE_ID = "[a-zA-Z0-9@._+:-]{1,128}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String MATERIALIZATION_SCHEMA = "v2:";
    private static final int MAX_PACKAGES = 512;
    private static final int MAX_MODULES = 4096;
    private static final int MAX_DATA_FILES = 100000;
    private static final long MAX_DATA_BYTES = 2L * 1024 * 1024 * 1024;

    static final class Session {
        final File home;
        final File request;
        final File rc;
        final String[] environment;

        Session(File home, File request, File rc, String[] environment) {
            this.home = home;
            this.request = request;
            this.rc = rc;
            this.environment = environment;
        }
    }

    private static final class PackageEntry {
        final String packId;
        final String name;
        final String version;
        final String repository;
        final String executable;
        final String[] commands;

        PackageEntry(String encoded) {
            String[] fields = encoded.split("\\t", -1);
            if (fields.length != 6 || !fields[0].matches(HASH)
                    || !fields[1].matches(SAFE_ID) || !fields[2].matches(SAFE_ID)
                    || !fields[3].matches("[a-z0-9-]{1,32}")
                    || !fields[4].matches(SAFE_ID)) {
                throw new SecurityException("Malformed Terminal package catalog");
            }
            packId = fields[0];
            name = fields[1];
            version = fields[2];
            repository = fields[3];
            executable = fields[4];
            commands = fields[5].split(",", -1);
            if (commands.length < 1 || commands.length > 512) {
                throw new SecurityException("Malformed Terminal command list");
            }
            HashSet<String> unique = new HashSet<>();
            for (String command : commands) {
                if (!command.matches(SAFE_ID) || !unique.add(command)) {
                    throw new SecurityException("Malformed Terminal command name");
                }
            }
            if (!unique.contains(executable)) {
                throw new SecurityException("Terminal executable is not in its command list");
            }
        }
    }

    private static final class Command {
        final String packId;
        final String name;
        final File program;
        final File libraries;
        final File runtimeRoot;
        final File loader;

        Command(String packId, String name, File program, File libraries,
                File runtimeRoot, File loader) {
            this.packId = packId;
            this.name = name;
            this.program = program;
            this.libraries = libraries;
            this.runtimeRoot = runtimeRoot;
            this.loader = loader;
        }
    }

    private TerminalEnvironment() {}

    static synchronized Session prepare(Context context) throws Exception {
        File terminal = directory(context.getFilesDir(), "terminal");
        File home = directory(terminal, "home");
        File config = directory(home, ".config");
        File cache = directory(home, ".cache");
        File runtime = directory(terminal, "runtime");
        File packsRoot = directory(runtime, "packs");
        File tmp = directory(context.getCacheDir(), "terminal-tmp");
        File request = new File(runtime, "manager-request.tsv");
        if (request.exists() && !request.delete()) throw new IOException("Could not reset request");

        Bundle catalog = requireBundle(context.getContentResolver().call(
                PROVIDER, CATALOG_METHOD, null, null), "Terminal catalog unavailable");
        String[] encoded = catalog.getStringArray("packages");
        if (encoded == null || encoded.length > MAX_PACKAGES) {
            throw new SecurityException("Terminal package catalog exceeds its limit");
        }
        ArrayList<PackageEntry> packages = new ArrayList<>();
        Set<String> livePacks = new HashSet<>();
        LinkedHashMap<String, Command> commands = new LinkedHashMap<>();
        ArrayList<String> dataDirectories = new ArrayList<>();
        for (String value : encoded) {
            PackageEntry entry = new PackageEntry(value);
            if (!livePacks.add(entry.packId)) {
                throw new SecurityException("Duplicate Terminal runtime pack");
            }
            Bundle modules = requireBundle(context.getContentResolver().call(
                    PROVIDER, PACK_METHOD, entry.packId, null), "Terminal pack unavailable");
            File packRoot = materializePack(context.getContentResolver(), packsRoot,
                    entry, modules);
            File libraries = new File(packRoot, "lib");
            File dataRoot = new File(packRoot, "root");
            File loader = new File(packRoot, "loader");
            dataDirectories.add(new File(dataRoot, "usr/share").getAbsolutePath());
            for (String commandName : entry.commands) {
                Command command = new Command(entry.packId, commandName,
                        new File(new File(packRoot, "bin"), commandName), libraries,
                        dataRoot, loader);
                if (!command.program.isFile()) {
                    throw new SecurityException("Runtime pack is missing command " + commandName);
                }
                Command previous = commands.putIfAbsent(commandName, command);
                if (previous != null && !previous.packId.equals(entry.packId)) {
                    throw new IllegalStateException("Command collision: " + commandName);
                }
            }
            packages.add(entry);
        }
        removeStalePacks(packsRoot, livePacks);

        File installed = new File(runtime, "installed.tsv");
        writeInstalledList(installed, packages);
        File rc = new File(runtime, "shell.rc");
        writeAtomically(rc, shellRc(home, config, cache, tmp, request,
                installed, commands, dataDirectories));

        ArrayList<String> env = new ArrayList<>();
        env.add("HOME=" + home.getAbsolutePath());
        env.add("TMPDIR=" + tmp.getAbsolutePath());
        env.add("XDG_CONFIG_HOME=" + config.getAbsolutePath());
        env.add("XDG_CACHE_HOME=" + cache.getAbsolutePath());
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("LANG=C.UTF-8");
        env.add("LC_ALL=C.UTF-8");
        env.add("PATH=/system/bin:/system/xbin");
        env.add("ENV=" + rc.getAbsolutePath());
        env.add("ARCHPHENE_MANAGER_REQUEST=" + request.getAbsolutePath());
        return new Session(home, request, rc, env.toArray(new String[0]));
    }

    private static File materializePack(ContentResolver resolver, File packsRoot,
            PackageEntry entry, Bundle modules) throws Exception {
        String[] kinds = modules.getStringArray("kinds");
        String[] names = modules.getStringArray("names");
        String[] uris = modules.getStringArray("uris");
        String[] hashes = modules.getStringArray("hashes");
        long[] sizes = modules.getLongArray("sizes");
        int count = kinds == null ? -1 : kinds.length;
        if (count < 1 || count > MAX_MODULES || names == null || uris == null
                || hashes == null || sizes == null || names.length != count
                || uris.length != count || hashes.length != count || sizes.length != count) {
            throw new SecurityException("Malformed Terminal runtime pack");
        }
        File destination = new File(packsRoot, entry.packId).getCanonicalFile();
        if (!destination.getParentFile().equals(packsRoot.getCanonicalFile())) {
            throw new SecurityException("Terminal pack path escaped its root");
        }
        File complete = new File(destination, ".complete");
        if (complete.isFile() && (MATERIALIZATION_SCHEMA + entry.packId).equals(
                readSmall(complete))) return destination;
        deleteRecursively(destination);
        File staging = new File(packsRoot, "." + entry.packId + ".staging");
        deleteRecursively(staging);
        File bin = directory(staging, "bin");
        File lib = directory(staging, "lib");
        File root = directory(staging, "root");
        HashSet<String> moduleNames = new HashSet<>();
        boolean loader = false;
        boolean data = false;
        for (int index = 0; index < count; index++) {
            if (!hashes[index].matches(HASH) || sizes[index] <= 0
                    || sizes[index] > 2L * 1024 * 1024 * 1024
                    || !names[index].matches(SAFE_ID) || !moduleNames.add(kinds[index] + "/" + names[index])) {
                throw new SecurityException("Unsafe Terminal runtime module");
            }
            File target;
            if ("program".equals(kinds[index])) {
                target = new File(bin, entry.executable);
            } else if ("command".equals(kinds[index])) {
                target = new File(bin, names[index]);
            } else if ("library".equals(kinds[index])) {
                target = new File(lib, names[index]);
            } else if ("loader".equals(kinds[index]) && !loader) {
                loader = true;
                target = new File(staging, "loader");
            } else if ("data".equals(kinds[index]) && !data) {
                data = true;
                target = new File(staging, "data.zip");
            } else {
                throw new SecurityException("Unsupported or duplicate Terminal module kind");
            }
            copyVerified(resolver, Uri.parse(uris[index]), target, hashes[index], sizes[index]);
            if (("program".equals(kinds[index]) || "command".equals(kinds[index])
                    || "loader".equals(kinds[index]))
                    && !target.setExecutable(true, true)) {
                throw new IOException("Could not make Terminal module executable");
            }
        }
        if (!loader || !new File(bin, entry.executable).isFile()) {
            throw new SecurityException("Terminal runtime pack is incomplete");
        }
        File archive = new File(staging, "data.zip");
        if (archive.isFile()) {
            extractData(archive, root);
            if (!archive.delete()) throw new IOException("Could not remove Terminal archive");
        }
        writeFile(new File(staging, ".complete"), MATERIALIZATION_SCHEMA + entry.packId + "\n");
        if (!staging.renameTo(destination)) throw new IOException("Could not publish Terminal pack");
        return destination;
    }

    private static void copyVerified(ContentResolver resolver, Uri uri, File target,
            String expectedHash, long expectedSize) throws Exception {
        if (!"content".equals(uri.getScheme())
                || !PROVIDER.getAuthority().equals(uri.getAuthority())) {
            throw new SecurityException("Terminal module URI is untrusted");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new BufferedInputStream(resolver.openInputStream(uri));
                FileOutputStream output = new FileOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > expectedSize) throw new SecurityException("Terminal module exceeds size");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (total != expectedSize || !expectedHash.equals(hex(digest.digest()))) {
            if (!target.delete()) target.deleteOnExit();
            throw new SecurityException("Terminal module verification failed");
        }
        if (!target.setReadOnly()) throw new IOException("Could not protect Terminal module");
    }

    private static String shellRc(File home, File config, File cache, File tmp,
            File request, File installed, Map<String, Command> commands,
            List<String> dataDirectories) {
        StringBuilder rc = new StringBuilder();
        rc.append("export HOME=").append(quote(home.getAbsolutePath())).append('\n');
        rc.append("export TMPDIR=").append(quote(tmp.getAbsolutePath())).append('\n');
        rc.append("export XDG_CONFIG_HOME=").append(quote(config.getAbsolutePath())).append('\n');
        rc.append("export XDG_CACHE_HOME=").append(quote(cache.getAbsolutePath())).append('\n');
        rc.append("export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8\n");
        rc.append("export PS1='archphene \\w $ '\n");
        rc.append("_archphene_run() {\n  _ap_key=\"$1/$2\"; shift 2\n  case \"$_ap_key\" in\n");
        for (Command command : commands.values()) {
            String libraryPath = command.libraries.getAbsolutePath();
            rc.append("    ").append(quote(command.packId + "/" + command.name))
                    .append(") ARCHPHENE_RUNTIME_ROOT=").append(quote(command.runtimeRoot.getAbsolutePath()))
                    .append(" GLIBC_TUNABLES='glibc.pthread.rseq=0' LD_PRELOAD=")
                    .append(quote(new File(command.libraries, "libarchphene_path_bridge.so").getAbsolutePath()))
                    .append(' ').append(quote(command.loader.getAbsolutePath()))
                    .append(" --library-path ").append(quote(libraryPath)).append(' ')
                    .append(quote(command.program.getAbsolutePath())).append(" \"$@\" ;;\n");
        }
        rc.append("    *) echo \"archphene: unavailable command $_ap_key\" >&2; return 127 ;;\n  esac\n}\n");
        for (Command command : commands.values()) {
            rc.append("alias ").append(command.name).append("='_archphene_run ")
                    .append(command.packId).append(' ').append(command.name).append("'\n");
        }
        rc.append("pacman() {\n  case \"$1\" in\n")
                .append("    -Q) cat ").append(quote(installed.getAbsolutePath())).append(" ;;\n")
                .append("    -Qs) shift; grep -i -- \"$*\" ").append(quote(installed.getAbsolutePath())).append(" || true ;;\n")
                .append("    -Qi) shift; grep -i -- \"^$1[[:space:]]\" ").append(quote(installed.getAbsolutePath())).append(" || return 1 ;;\n")
                .append("    -Ss) shift; printf 'search\\t%s\\n' \"$*\" > ").append(quote(request.getAbsolutePath())).append(" ;;\n")
                .append("    -S) shift; printf 'install\\t%s\\n' \"$*\" > ").append(quote(request.getAbsolutePath())).append("; echo 'Review the install request in Archphene.' ;;\n")
                .append("    -R|-Rs|-Rns) shift; printf 'remove\\t%s\\n' \"$*\" > ").append(quote(request.getAbsolutePath())).append("; echo 'Review the removal request in Archphene.' ;;\n")
                .append("    -Syu|-Syyu) printf 'upgrade\\tall\\n' > ").append(quote(request.getAbsolutePath())).append("; echo 'Review available updates in Archphene.' ;;\n")
                .append("    *) echo 'Archphene pacman supports -Q, -Qi, -Qs, -Ss, -S, -R, and -Syu.' >&2; return 2 ;;\n  esac\n}\n");
        if (!dataDirectories.isEmpty()) {
            rc.append("export XDG_DATA_DIRS=").append(quote(String.join(":", dataDirectories))).append('\n');
            ArrayList<String> terminfo = new ArrayList<>();
            for (String dataDirectory : dataDirectories) terminfo.add(dataDirectory + "/terminfo");
            rc.append("export TERMINFO_DIRS=").append(quote(String.join(":", terminfo))).append('\n');
        }
        rc.append("echo 'Archphene Terminal - ").append(commands.size())
                .append(" managed command(s). Type pacman -Q to list packages.'\n");
        return rc.toString();
    }

    private static void writeInstalledList(File output, List<PackageEntry> entries) throws IOException {
        StringBuilder value = new StringBuilder();
        for (PackageEntry entry : entries) {
            value.append(entry.name).append('\t').append(entry.version).append('\t')
                    .append(entry.repository).append('\t')
                    .append(String.join(",", entry.commands)).append('\n');
        }
        writeAtomically(output, value.toString());
    }

    private static void extractData(File archive, File root) throws Exception {
        int files = 0;
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        String rootPath = root.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("\u0000")) {
                    throw new SecurityException("Unsafe Terminal data path");
                }
                File target = new File(root, name).getCanonicalFile();
                if (!target.getPath().startsWith(rootPath)) throw new SecurityException("Terminal data escaped root");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create data directory");
                    continue;
                }
                if (++files > MAX_DATA_FILES) throw new SecurityException("Too many Terminal data files");
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create data directory");
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        bytes += count;
                        if (bytes > MAX_DATA_BYTES) throw new SecurityException("Terminal data exceeds limit");
                        output.write(buffer, 0, count);
                    }
                }
                if (!target.setReadOnly()) throw new IOException("Could not protect Terminal data");
            }
        }
    }

    private static Bundle requireBundle(Bundle value, String message) {
        if (value == null) throw new IllegalStateException(message);
        value.setClassLoader(TerminalEnvironment.class.getClassLoader());
        return value;
    }
    private static void removeStalePacks(File root, Set<String> live) throws IOException {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) if (child.isDirectory() && !live.contains(child.getName())) deleteRecursively(child);
    }
    private static File directory(File parent, String child) throws IOException {
        File result = new File(parent, child);
        if (!result.isDirectory() && !result.mkdirs()) throw new IOException("Could not create " + result);
        return result;
    }
    private static void writeAtomically(File file, String value) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        writeFile(temporary, value);
        if (file.exists() && !file.delete()) throw new IOException("Could not replace " + file);
        if (!temporary.renameTo(file)) throw new IOException("Could not publish " + file);
    }
    private static void writeFile(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
    private static String readSmall(File file) throws IOException {
        if (file.length() > 128) throw new IOException("Terminal marker is invalid");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new String(data, StandardCharsets.UTF_8).trim();
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte part : value) output.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff));
        return output.toString();
    }
    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not list " + file);
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Could not delete " + file);
    }
}