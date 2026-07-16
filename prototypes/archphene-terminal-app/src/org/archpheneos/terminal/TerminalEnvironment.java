package org.archpheneos.terminal;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
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
    private static final String RELEASE_METHOD =
            "org.archphene.runtime.RELEASE_LEASE_V1";
    private static final String TAG = "ArchpheneTerminal";
    private static final String SAFE_ID = "[a-zA-Z0-9@._+:-]{1,128}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String MATERIALIZATION_SCHEMA = "v3:";
    private static final int MAX_PACKAGES = 512;
    private static final int MAX_MODULES = 4096;
    private static final int MAX_DATA_FILES = 100000;
    private static final long MAX_DATA_BYTES = 2L * 1024 * 1024 * 1024;

    static final class Session {
        final File home;
        final File requestDirectory;
        final File responseDirectory;
        final File rc;
        final File launcher;
        final String shellName;
        final String[] environment;

        Session(File home, File requestDirectory, File responseDirectory, File rc,
                File launcher, String shellName, String[] environment) {
            this.home = home;
            this.requestDirectory = requestDirectory;
            this.responseDirectory = responseDirectory;
            this.rc = rc;
            this.launcher = launcher;
            this.shellName = shellName;
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
        final String interpreter;

        Command(String packId, String name, File program, File libraries,
                File runtimeRoot, File loader, String interpreter) {
            this.packId = packId;
            this.name = name;
            this.program = program;
            this.libraries = libraries;
            this.runtimeRoot = runtimeRoot;
            this.loader = loader;
            this.interpreter = interpreter;
        }
    }

    private static final class ProviderSession implements AutoCloseable {
        final ContentResolver resolver;
        final ContentProviderClient client;

        private ProviderSession(ContentResolver resolver, ContentProviderClient client) {
            this.resolver = resolver;
            this.client = client;
        }

        static ProviderSession open(Context context) {
            ContentResolver resolver = context.getContentResolver();
            ContentProviderClient client = resolver.acquireContentProviderClient(PROVIDER);
            if (client == null) {
                throw new IllegalStateException("Archphene runtime provider is unavailable");
            }
            return new ProviderSession(resolver, client);
        }

        Bundle catalog() throws Exception {
            return requireBundle(client.call(CATALOG_METHOD, null, null),
                    "Terminal catalog unavailable");
        }

        PackLease pack(String packId) throws Exception {
            Binder token = new Binder();
            Bundle request = new Bundle();
            request.putBinder("lease_token", token);
            try {
                Bundle modules = requireBundle(client.call(PACK_METHOD, packId, request),
                        "Terminal pack unavailable");
                return new PackLease(client, packId, token, modules);
            } catch (Exception error) {
                releaseLease(client, packId, token);
                throw error;
            }
        }

        @Override
        public void close() {
            client.release();
        }
    }

    private static final class PackLease implements AutoCloseable {
        final ContentProviderClient client;
        final String packId;
        final Binder token;
        final Bundle modules;
        private boolean closed;

        PackLease(ContentProviderClient client, String packId, Binder token, Bundle modules) {
            this.client = client;
            this.packId = packId;
            this.token = token;
            this.modules = modules;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            releaseLease(client, packId, token);
        }
    }

    private static void releaseLease(ContentProviderClient client, String packId,
            Binder token) {
        try {
            Bundle request = new Bundle();
            request.putBinder("lease_token", token);
            client.call(RELEASE_METHOD, packId, request);
        } catch (Exception error) {
            Log.w(TAG, "Could not release runtime pack lease " + packId, error);
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
        File requests = directory(runtime, "requests");
        File responses = directory(runtime, "responses");
        clearFiles(requests);
        clearFiles(responses);

        File packagedLoader = new File(context.getApplicationInfo().nativeLibraryDir,
                "libarchphene_ld.so");
        if (!packagedLoader.isFile() || !packagedLoader.canExecute()) {
            throw new SecurityException("APK-owned glibc loader is unavailable");
        }
        ArrayList<PackageEntry> packages = new ArrayList<>();
        Set<String> livePacks = new HashSet<>();
        LinkedHashMap<String, Command> commands = new LinkedHashMap<>();
        ArrayList<String> dataDirectories = new ArrayList<>();
        try (ProviderSession provider = ProviderSession.open(context)) {
            Bundle catalog = provider.catalog();
            String[] encoded = catalog.getStringArray("packages");
            if (encoded == null || encoded.length > MAX_PACKAGES) {
                throw new SecurityException("Terminal package catalog exceeds its limit");
            }
            for (String value : encoded) {
                PackageEntry entry = new PackageEntry(value);
                if (!livePacks.add(entry.packId)) {
                    throw new SecurityException("Duplicate Terminal runtime pack");
                }
                File packRoot;
                try (PackLease lease = provider.pack(entry.packId)) {
                    packRoot = materializePack(provider.resolver, packsRoot,
                            entry, lease.modules, packagedLoader);
                }
                File libraries = new File(packRoot, "lib");
                File dataRoot = new File(packRoot, "root");
                File loader = packagedLoader;
                dataDirectories.add(new File(dataRoot, "usr/share").getAbsolutePath());
                for (String commandName : entry.commands) {
                    File program = new File(new File(packRoot, "bin"), commandName);
                    Command command = new Command(entry.packId, commandName,
                            program, libraries, dataRoot, loader,
                            scriptInterpreter(program));
                    if (!command.program.isFile()) {
                        throw new SecurityException(
                                "Runtime pack is missing command " + commandName);
                    }
                    Command previous = commands.putIfAbsent(commandName, command);
                    if (previous != null && !previous.packId.equals(entry.packId)) {
                        throw new IllegalStateException("Command collision: " + commandName);
                    }
                }
                packages.add(entry);
            }
        }
        removeStalePacks(packsRoot, livePacks);

        File installed = new File(runtime, "installed.tsv");
        writeInstalledList(installed, packages);
        File rc = new File(runtime, "shell.rc");
        writeAtomically(rc, shellRc(home, config, cache, tmp, requests, responses,
                installed, rc, commands, dataDirectories));
        Command bash = commands.get("bash");
        if (bash != null && !bash.interpreter.isEmpty()) {
            throw new SecurityException("Managed Bash command is not an ELF executable");
        }
        File launcher = new File(runtime, "launch.sh");
        writeAtomically(launcher, launchScript(rc, bash));

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
        env.add("ARCHPHENE_MANAGER_REQUESTS=" + requests.getAbsolutePath());
        env.add("ARCHPHENE_MANAGER_RESPONSES=" + responses.getAbsolutePath());
        return new Session(home, requests, responses, rc, launcher,
                bash == null ? "Bionic sh" : "Arch Bash", env.toArray(new String[0]));
    }

    static File responseDirectory(Context context) throws IOException {
        return directory(directory(directory(context.getFilesDir(), "terminal"),
                "runtime"), "responses");
    }

    private static File materializePack(ContentResolver resolver, File packsRoot,
            PackageEntry entry, Bundle modules, File packagedLoader) throws Exception {
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
            Uri moduleUri = Uri.parse(uris[index]);
            requireTrustedModuleUri(moduleUri);
            File target;
            if ("program".equals(kinds[index])) {
                target = new File(bin, entry.executable);
            } else if ("command".equals(kinds[index])) {
                target = new File(bin, names[index]);
            } else if ("library".equals(kinds[index])) {
                target = new File(lib, names[index]);
            } else if ("loader".equals(kinds[index]) && !loader) {
                loader = true;
                verifyLocal(packagedLoader, hashes[index], sizes[index]);
                continue;
            } else if ("data".equals(kinds[index]) && !data) {
                data = true;
                target = new File(staging, "data.zip");
            } else {
                throw new SecurityException("Unsupported or duplicate Terminal module kind");
            }
            copyVerified(resolver, moduleUri, target, hashes[index], sizes[index]);
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
        requireTrustedModuleUri(uri);
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

    private static String scriptInterpreter(File program) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(program))) {
            int first = input.read();
            int second = input.read();
            int third = input.read();
            int fourth = input.read();
            if (first == 0x7f && second == 'E' && third == 'L' && fourth == 'F') return "";
            if (first != '#' || second != '!') {
                throw new SecurityException("Runtime command is neither ELF nor a shebang script: "
                        + program.getName());
            }
            StringBuilder line = new StringBuilder();
            if (third >= 0 && third != '\n') line.append((char) third);
            if (fourth >= 0 && fourth != '\n') line.append((char) fourth);
            int value;
            while ((value = input.read()) >= 0 && value != '\n') {
                if (value == 0 || value == '\r' || line.length() >= 255) {
                    throw new SecurityException("Runtime script has an unsafe shebang");
                }
                line.append((char) value);
            }
            if (value != '\n') throw new SecurityException("Runtime script has an invalid shebang");
            String[] tokens = line.toString().trim().split("\\s+");
            if (tokens.length == 0) throw new SecurityException("Runtime script has no interpreter");
            int index = 0;
            String interpreter = basename(tokens[index++]);
            if ("env".equals(interpreter)) {
                while (index < tokens.length && tokens[index].startsWith("-")) index++;
                if (index >= tokens.length) {
                    throw new SecurityException("Runtime env shebang has no interpreter");
                }
                interpreter = basename(tokens[index]);
            }
            if (!interpreter.matches(SAFE_ID)) {
                throw new SecurityException("Runtime script interpreter is unsafe");
            }
            return interpreter;
        }
    }

    private static String basename(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String managedInvocation(Command executable, File runtimeRoot,
            File script, File rc, boolean replaceProcess) {
        StringBuilder value = new StringBuilder();
        value.append("ARCHPHENE_RUNTIME_ROOT=").append(quote(runtimeRoot.getAbsolutePath()))
                .append(" LOCPATH=").append(quote(new File(runtimeRoot,
                        "usr/lib/locale").getAbsolutePath()))
                .append(" GLIBC_TUNABLES='glibc.pthread.rseq=0' LD_PRELOAD=")
                .append(quote(new File(executable.libraries,
                        "libarchphene_path_bridge.so").getAbsolutePath()));
        if (script != null && "bash".equals(executable.name)) {
            value.append(" BASH_ENV=").append(quote(rc.getAbsolutePath()));
        }
        value.append(replaceProcess ? " exec " : " ")
                .append(quote(executable.loader.getAbsolutePath()))
                .append(" --library-path ").append(quote(executable.libraries.getAbsolutePath()))
                .append(' ').append(quote(executable.program.getAbsolutePath()));
        if (script != null) value.append(' ').append(quote(script.getAbsolutePath()));
        return value.toString();
    }

    private static String launchScript(File rc, Command bash) {
        StringBuilder value = new StringBuilder("#!/system/bin/sh\n");
        if (bash == null) {
            return value.append("exec /system/bin/sh -i\n").toString();
        }
        value.append(managedInvocation(bash, bash.runtimeRoot, null, rc, true))
                .append(" --noprofile --rcfile ").append(quote(rc.getAbsolutePath()))
                .append(" -i\n");
        return value.toString();
    }
    private static String shellRc(File home, File config, File cache, File tmp,
            File requests, File responses, File installed, File rcFile,
            Map<String, Command> commands, List<String> dataDirectories) {
        StringBuilder rc = new StringBuilder();
        rc.append("export HOME=").append(quote(home.getAbsolutePath())).append('\n');
        rc.append("export TMPDIR=").append(quote(tmp.getAbsolutePath())).append('\n');
        rc.append("export ARCHPHENE_PROJECTS=").append(
                quote(new File(home, "Projects").getAbsolutePath())).append('\n');
        rc.append("export XDG_CONFIG_HOME=").append(quote(config.getAbsolutePath())).append('\n');
        rc.append("export XDG_CACHE_HOME=").append(quote(cache.getAbsolutePath())).append('\n');
        rc.append("export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8\n");
        rc.append("unset LD_PRELOAD\n");
        rc.append("case \"$-\" in *i*) _ap_interactive=1;; *) _ap_interactive=0;; esac\n");
        rc.append("[ -z \"$BASH_VERSION\" ] || shopt -s expand_aliases\n");
        rc.append("[ \"$_ap_interactive\" = 0 ] || export PS1='archphene \\w $ '\n");
        rc.append("_archphene_run() {\n  _ap_key=\"$1/$2\"; shift 2\n  case \"$_ap_key\" in\n");
        Command managedBash = commands.get("bash");
        for (Command command : commands.values()) {
            rc.append("    ").append(quote(command.packId + "/" + command.name)).append(") ");
            if (command.interpreter.isEmpty()) {
                rc.append(managedInvocation(command, command.runtimeRoot, null, rcFile, false));
            } else {
                Command interpreter = ("sh".equals(command.interpreter)
                        || "bash".equals(command.interpreter))
                        ? managedBash : commands.get(command.interpreter);
                if (interpreter != null && interpreter.interpreter.isEmpty()) {
                    rc.append(managedInvocation(interpreter, command.runtimeRoot,
                            command.program, rcFile, false));
                } else if ("sh".equals(command.interpreter)) {
                    rc.append("/system/bin/sh ").append(quote(command.program.getAbsolutePath()));
                } else {
                    rc.append("echo ").append(quote("archphene: interpreter "
                            + command.interpreter + " is not installed for " + command.name))
                            .append(" >&2; return 126; :");
                }
            }
            rc.append(" \"$@\" ;;\n");
        }
        rc.append("    *) echo \"archphene: unavailable command $_ap_key\" >&2; return 127 ;;\n  esac\n}\n");
        for (Command command : commands.values()) {
            rc.append("alias ").append(command.name).append("='_archphene_run ")
                    .append(command.packId).append(' ').append(command.name).append("'\n");
        }
        rc.append("export ARCHPHENE_MANAGER_REQUESTS=")
                .append(quote(requests.getAbsolutePath())).append('\n');
        rc.append("export ARCHPHENE_MANAGER_RESPONSES=")
                .append(quote(responses.getAbsolutePath())).append('\n');
        rc.append("_ap_request_sequence=0\n")
                .append("_archphene_manager_request() {\n")
                .append("  _ap_action=\"$1\"; shift; _ap_query=\"$*\"\n")
                .append("  _ap_request_sequence=$((_ap_request_sequence + 1))\n")
                .append("  _ap_id=\"$$-$(date +%s)-$_ap_request_sequence\"\n")
                .append("  _ap_tmp=\"$ARCHPHENE_MANAGER_REQUESTS/.$_ap_id.tmp\"\n")
                .append("  _ap_request=\"$ARCHPHENE_MANAGER_REQUESTS/$_ap_id.request\"\n")
                .append("  _ap_response=\"$ARCHPHENE_MANAGER_RESPONSES/$_ap_id.response\"\n")
                .append("  rm -f \"$_ap_response\"\n")
                .append("  printf 'v1\\t%s\\t%s\\t%s\\n' \"$_ap_id\" \"$_ap_action\" \"$_ap_query\" > \"$_ap_tmp\" || return 1\n")
                .append("  mv \"$_ap_tmp\" \"$_ap_request\" || return 1\n")
                .append("  _ap_last=0; _ap_elapsed=0\n")
                .append("  while [ \"$_ap_elapsed\" -lt 1800 ]; do\n")
                .append("    if [ -f \"$_ap_response\" ]; then\n")
                .append("      IFS=\"$(printf '\\t')\" read _ap_schema _ap_seq _ap_phase _ap_percent _ap_terminal _ap_outcome _ap_status < \"$_ap_response\"\n")
                .append("      if [ \"$_ap_schema\" = v1 ] && [ \"$_ap_seq\" != \"$_ap_last\" ]; then\n")
                .append("        _ap_last=\"$_ap_seq\"; printf '[%s%%] %s: %s\\n' \"$_ap_percent\" \"$_ap_phase\" \"$_ap_status\"\n")
                .append("        if [ \"$_ap_terminal\" = 1 ]; then\n")
                .append("          rm -f \"$_ap_response\"; case \"$_ap_outcome\" in success) return 0;; cancelled) return 130;; *) return 1;; esac\n")
                .append("        fi\n")
                .append("      fi\n")
                .append("    fi\n")
                .append("    sleep 1; _ap_elapsed=$((_ap_elapsed + 1))\n")
                .append("  done\n")
                .append("  rm -f \"$_ap_request\" \"$_ap_response\"; echo 'archphene: manager request timed out' >&2; return 124\n")
                .append("}\n");
        rc.append("archphene-import() {\n")
                .append("  _ap_target=\"$1\"\n")
                .append("  [ -n \"$_ap_target\" ] || _ap_target=Downloads\n")
                .append("  _archphene_manager_request import \"$_ap_target\"\n}\n");
        rc.append("archphene-export() {\n")
                .append("  [ \"$#\" -eq 1 ] || { echo 'usage: archphene-export <home-file>' >&2; return 2; }\n")
                .append("  _archphene_manager_request export \"$1\"\n}\n");
        rc.append("archphene-project() {\n")
                .append("  _ap_project_action=\"$1\"; shift 2>/dev/null || true\n")
                .append("  case \"$_ap_project_action\" in\n")
                .append("    add|sync|remove|path) [ \"$#\" -eq 1 ] || { echo 'usage: archphene-project {add|sync|remove|path} <alias>' >&2; return 2; }; _archphene_manager_request \"project-$_ap_project_action\" \"$1\" ;;\n")
                .append("    list) [ \"$#\" -eq 0 ] || { echo 'usage: archphene-project list' >&2; return 2; }; _archphene_manager_request project-list all ;;\n")
                .append("    *) echo 'usage: archphene-project {add|sync|list|path|remove} [alias]' >&2; return 2 ;;\n")
                .append("  esac\n}\n");
        rc.append("pacman() {\n  case \"$1\" in\n")
                .append("    -Q) cat ").append(quote(installed.getAbsolutePath())).append(" ;;\n")
                .append("    -Qs) shift; grep -i -- \"$*\" ").append(quote(installed.getAbsolutePath())).append(" || true ;;\n")
                .append("    -Qi) shift; grep -i -- \"^$1[[:space:]]\" ").append(quote(installed.getAbsolutePath())).append(" || return 1 ;;\n")
                .append("    -Ss) shift; _archphene_manager_request search \"$*\" ;;\n")
                .append("    -S) shift; [ \"$#\" -eq 1 ] || { echo 'pacman -S accepts one package per command' >&2; return 2; }; _archphene_manager_request install \"$1\" ;;\n")
                .append("    -R|-Rs|-Rns) shift; [ \"$#\" -eq 1 ] || { echo 'pacman -R accepts one package per command' >&2; return 2; }; _archphene_manager_request remove \"$1\" ;;\n")
                .append("    -Syu|-Syyu) _archphene_manager_request upgrade all ;;\n")
                .append("    *) echo 'Archphene pacman supports -Q, -Qi, -Qs, -Ss, -S, -R, and -Syu.' >&2; return 2 ;;\n  esac\n}\n");
        if (!dataDirectories.isEmpty()) {
            rc.append("export XDG_DATA_DIRS=").append(quote(String.join(":", dataDirectories))).append('\n');
            ArrayList<String> terminfo = new ArrayList<>();
            for (String dataDirectory : dataDirectories) terminfo.add(dataDirectory + "/terminfo");
            rc.append("export TERMINFO_DIRS=").append(quote(String.join(":", terminfo))).append('\n');
        }
        rc.append("if [ \"$_ap_interactive\" = 1 ]; then\n")
                .append("  if [ -n \"$BASH_VERSION\" ] && [ -f \"$HOME/.bashrc\" ]; then . \"$HOME/.bashrc\"; fi\n")
                .append("  echo 'Archphene Terminal - ").append(commands.size())
                .append(" managed command(s). Type pacman -Q to list packages.'\n")
                .append("fi\n");
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
    private static void requireTrustedModuleUri(Uri uri) {
        if (!"content".equals(uri.getScheme())
                || !PROVIDER.getAuthority().equals(uri.getAuthority())) {
            throw new SecurityException("Terminal module URI is untrusted");
        }
    }

    private static void verifyLocal(File file, String expectedHash, long expectedSize)
            throws Exception {
        if (!file.isFile() || file.length() != expectedSize) {
            throw new SecurityException("APK-owned Terminal loader does not match runtime");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        if (!expectedHash.equals(hex(digest.digest()))) {
            throw new SecurityException("APK-owned Terminal loader hash mismatch");
        }
    }
    private static File directory(File parent, String child) throws IOException {
        File result = new File(parent, child);
        if (!result.isDirectory() && !result.mkdirs()) throw new IOException("Could not create " + result);
        return result;
    }
    private static void clearFiles(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Could not list " + directory);
        for (File child : children) deleteRecursively(child);
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