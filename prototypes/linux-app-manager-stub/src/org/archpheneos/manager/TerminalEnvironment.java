package org.archpheneos.manager;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds the private command view used by the Archphene terminal session. */
final class TerminalEnvironment {
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

    private static final class Command {
        final String packId;
        final String name;
        final File program;
        final File libraries;
        final File runtimeRoot;

        Command(String packId, String name, File program, File libraries, File runtimeRoot) {
            this.packId = packId;
            this.name = name;
            this.program = program;
            this.libraries = libraries;
            this.runtimeRoot = runtimeRoot;
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
        request.delete();

        List<ManagedPackageStore.Entry> installed = ManagedPackageStore.list(context);
        Set<String> livePacks = new HashSet<>();
        LinkedHashMap<String, Command> commands = new LinkedHashMap<>();
        ArrayList<String> dataDirectories = new ArrayList<>();
        for (ManagedPackageStore.Entry entry : installed) {
            RuntimePackStore.Pack pack = RuntimePackStore.load(context, entry.runtimePackId);
            livePacks.add(pack.id);
            File packRoot = materializePack(packsRoot, pack);
            File libraries = new File(packRoot, "lib");
            File dataRoot = new File(packRoot, "root");
            dataDirectories.add(new File(dataRoot, "usr/share").getAbsolutePath());
            Map<String, RuntimePackStore.Module> modules = commandModules(pack);
            for (String commandName : entry.commands) {
                RuntimePackStore.Module module = modules.get(commandName);
                if (module == null) {
                    throw new SecurityException("Runtime pack is missing command " + commandName);
                }
                Command previous = commands.putIfAbsent(commandName,
                        new Command(pack.id, commandName,
                                new File(new File(packRoot, "bin"), commandName),
                                libraries, dataRoot));
                if (previous != null && !previous.packId.equals(pack.id)) {
                    throw new IllegalStateException("Command collision: " + commandName
                            + " is provided by more than one installed package");
                }
            }
        }
        removeStalePacks(packsRoot, livePacks);

        File installedList = new File(runtime, "installed.tsv");
        writeInstalledList(installedList, installed);
        File rc = new File(runtime, "shell.rc");
        File loader = new File(context.getApplicationInfo().nativeLibraryDir,
                "libarchphene_ld.so").getCanonicalFile();
        if (!loader.isFile()) throw new IOException("Archphene glibc loader is unavailable");
        writeAtomically(rc, shellRc(loader, home, config, cache, tmp, request,
                installedList, commands, dataDirectories));

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

    private static File materializePack(File packsRoot, RuntimePackStore.Pack pack)
            throws Exception {
        File destination = new File(packsRoot, pack.id).getCanonicalFile();
        if (!destination.getParentFile().equals(packsRoot.getCanonicalFile())) {
            throw new SecurityException("Terminal pack path escaped its root");
        }
        File complete = new File(destination, ".complete");
        if (complete.isFile() && pack.id.equals(readSmall(complete))) return destination;
        deleteRecursively(destination);
        File staging = new File(packsRoot, "." + pack.id + ".staging");
        deleteRecursively(staging);
        File bin = directory(staging, "bin");
        File lib = directory(staging, "lib");
        File root = directory(staging, "root");
        Map<String, RuntimePackStore.Module> commands = commandModules(pack);
        for (Map.Entry<String, RuntimePackStore.Module> command : commands.entrySet()) {
            linkOrCopy(command.getValue().file, new File(bin, command.getKey()));
        }
        for (RuntimePackStore.Module module : pack.libraries()) {
            linkOrCopy(module.file, new File(lib, module.linkName));
        }
        RuntimePackStore.Module data = pack.data();
        if (data != null) extractData(data.file, root);
        writeFile(new File(staging, ".complete"), pack.id + "\n");
        if (!staging.renameTo(destination)) {
            throw new IOException("Could not publish terminal runtime view");
        }
        return destination;
    }

    private static Map<String, RuntimePackStore.Module> commandModules(
            RuntimePackStore.Pack pack) throws Exception {
        HashMap<String, RuntimePackStore.Module> result = new HashMap<>();
        result.put(pack.executableName, pack.requireKind("program"));
        for (RuntimePackStore.Module module : pack.commands()) {
            if (result.put(module.linkName, module) != null) {
                throw new SecurityException("Duplicate command module " + module.linkName);
            }
        }
        return result;
    }

    private static String shellRc(File loader, File home, File config, File cache, File tmp,
            File request, File installed, Map<String, Command> commands,
            List<String> dataDirectories) {
        StringBuilder rc = new StringBuilder();
        rc.append("export HOME=").append(quote(home.getAbsolutePath())).append('\n');
        rc.append("export TMPDIR=").append(quote(tmp.getAbsolutePath())).append('\n');
        rc.append("export XDG_CONFIG_HOME=").append(quote(config.getAbsolutePath())).append('\n');
        rc.append("export XDG_CACHE_HOME=").append(quote(cache.getAbsolutePath())).append('\n');
        rc.append("export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8\n");
        rc.append("export PS1='archphene \\w $ '\n");
        rc.append("_archphene_run() {\n");
        rc.append("  _ap_key=\"$1/$2\"; shift 2\n");
        rc.append("  case \"$_ap_key\" in\n");
        for (Command command : commands.values()) {
            String libraryPath = command.libraries.getAbsolutePath() + ":"
                    + loader.getParentFile().getAbsolutePath();
            rc.append("    ").append(quote(command.packId + "/" + command.name))
                    .append(") ARCHPHENE_RUNTIME_ROOT=")
                    .append(quote(command.runtimeRoot.getAbsolutePath()))
                    .append(" GLIBC_TUNABLES='glibc.pthread.rseq=0' LD_PRELOAD=")
                    .append(quote(new File(command.libraries,
                            "libarchphene_path_bridge.so").getAbsolutePath()))
                    .append(' ').append(quote(loader.getAbsolutePath()))
                    .append(" --library-path ").append(quote(libraryPath)).append(' ')
                    .append(quote(command.program.getAbsolutePath()))
                    .append(" \"$@\" ;;\n");
        }
        rc.append("    *) echo \"archphene: unavailable command $_ap_key\" >&2; return 127 ;;\n");
        rc.append("  esac\n}\n");
        for (Command command : commands.values()) {
            rc.append("alias ").append(command.name).append("='_archphene_run ")
                    .append(command.packId).append(' ').append(command.name).append("'\n");
        }
        rc.append("pacman() {\n");
        rc.append("  case \"$1\" in\n");
        rc.append("    -Q) cat ").append(quote(installed.getAbsolutePath())).append(" ;;\n");
        rc.append("    -Qs) shift; grep -i -- \"$*\" ").append(quote(installed.getAbsolutePath()))
                .append(" || true ;;\n");
        rc.append("    -Qi) shift; grep -i -- \"^$1[[:space:]]\" ")
                .append(quote(installed.getAbsolutePath())).append(" || return 1 ;;\n");
        rc.append("    -Ss) shift; printf 'search\\t%s\\n' \"$*\" > ")
                .append(quote(request.getAbsolutePath())).append(" ;;\n");
        rc.append("    -S) shift; printf 'install\\t%s\\n' \"$*\" > ")
                .append(quote(request.getAbsolutePath()))
                .append("; echo 'Review the install request in Archphene.' ;;\n");
        rc.append("    -R|-Rs|-Rns) shift; printf 'remove\\t%s\\n' \"$*\" > ")
                .append(quote(request.getAbsolutePath()))
                .append("; echo 'Review the removal request in Archphene.' ;;\n");
        rc.append("    -Syu|-Syyu) printf 'upgrade\\tall\\n' > ")
                .append(quote(request.getAbsolutePath()))
                .append("; echo 'Review available updates in Archphene.' ;;\n");
        rc.append("    *) echo 'Archphene pacman supports -Q, -Qi, -Qs, -Ss, -S, -R, and -Syu.' >&2; return 2 ;;\n");
        rc.append("  esac\n}\n");
        if (!dataDirectories.isEmpty()) {
            rc.append("export XDG_DATA_DIRS=")
                    .append(quote(String.join(":", dataDirectories))).append('\n');
            ArrayList<String> terminfo = new ArrayList<>();
            for (String data : dataDirectories) terminfo.add(data + "/terminfo");
            rc.append("export TERMINFO_DIRS=").append(quote(String.join(":", terminfo)))
                    .append('\n');
        }
        rc.append("echo 'Archphene Terminal - ").append(commands.size())
                .append(" managed command(s). Type pacman -Q to list packages.'\n");
        return rc.toString();
    }

    private static void writeInstalledList(File output,
            List<ManagedPackageStore.Entry> entries) throws IOException {
        StringBuilder value = new StringBuilder();
        for (ManagedPackageStore.Entry entry : entries) {
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
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("\u0000")) {
                    throw new SecurityException("Unsafe terminal data path");
                }
                File target = new File(root, name).getCanonicalFile();
                if (!target.getPath().startsWith(rootPath)) {
                    throw new SecurityException("Terminal data escaped its runtime root");
                }
                if (entry.isDirectory()) {
                    directory(target.getParentFile(), target.getName());
                    continue;
                }
                if (++files > MAX_DATA_FILES) throw new SecurityException("Too many data files");
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create terminal data directory");
                }
                try (BufferedOutputStream output = new BufferedOutputStream(
                        new FileOutputStream(target))) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        bytes += count;
                        if (bytes > MAX_DATA_BYTES) {
                            throw new SecurityException("Terminal data exceeds size limit");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static void linkOrCopy(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir failed");
        try {
            Os.link(source.getAbsolutePath(), destination.getAbsolutePath());
            return;
        } catch (ErrnoException ignored) {
            // Cross-filesystem development environments use a verified byte copy instead.
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void removeStalePacks(File root, Set<String> live) throws IOException {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !live.contains(child.getName())) deleteRecursively(child);
        }
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

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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