package org.archphene.bridge;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Executes immutable runtime modules supplied by Android content descriptors. */
public final class RuntimeFdLauncher {
    private static final int MAX_LIBRARIES = 510;
    private static final AtomicBoolean STALE_VIEWS_PURGED = new AtomicBoolean();
    private static final AtomicLong NEXT_EXECUTION_ID = new AtomicLong(1);

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    public static final class Execution {
        private final long id;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private Execution(long id) {
            this.id = id;
        }

        private long begin() {
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("Runtime execution was already started");
            }
            if (cancelled.get()) {
                completed.set(true);
                throw new java.util.concurrent.CancellationException(
                        "Runtime execution was cancelled");
            }
            return id;
        }

        public void cancel() {
            cancelled.set(true);
            if (started.get() && !completed.get()) nativeCancelGlibc(id);
        }

        private void throwIfCancelled() {
            if (cancelled.get()) {
                throw new java.util.concurrent.CancellationException(
                        "Runtime execution was cancelled");
            }
        }

        private void finish() {
            completed.set(true);
            nativeForgetGlibc(id);
        }
    }

    public static Execution newExecution() {
        long id = NEXT_EXECUTION_ID.getAndIncrement();
        if (id <= 0) throw new IllegalStateException("Runtime execution IDs exhausted");
        return new Execution(id);
    }

    public static int terminateUidProcesses() {
        return nativeTerminateUidProcesses();
    }

    static { System.loadLibrary("archphene_compositor"); }
    private RuntimeFdLauncher() {}

    public static Result run(ContentResolver resolver, Uri uri) throws Exception {
        try (ParcelFileDescriptor descriptor = openElf(resolver, uri)) {
            byte[] output = new byte[64 * 1024];
            int exitCode = nativeRunFd(descriptor.getFd(), output);
            return result(exitCode, output);
        }
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri libcUri, File cacheRoot) throws Exception {
        return runGlibc(resolver, programUri, loaderUri, new Uri[] {libcUri},
                new String[] {"libc.so.6"}, cacheRoot);
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot)
            throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, java.util.Collections.emptyMap());
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment) throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, environment, "program");
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment, String programName) throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, environment, programName, java.util.Collections.emptyList());
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment, String programName, List<String> arguments)
            throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, environment, programName, arguments, null);
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment, String programName, List<String> arguments,
            Execution execution) throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, environment, programName, arguments, execution, false);
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment, String programName, List<String> arguments,
            Execution execution, boolean descriptorLibraries) throws Exception {
        return runGlibc(resolver, programUri, loaderUri, libraryUris, libraryNames,
                cacheRoot, environment, programName, arguments, execution,
                descriptorLibraries, new String[0], new String[0]);
    }

    public static Result runGlibc(ContentResolver resolver, Uri programUri,
            Uri loaderUri, Uri[] libraryUris, String[] libraryNames, File cacheRoot,
            Map<String, String> environment, String programName, List<String> arguments,
            Execution execution, boolean descriptorLibraries, String[] commandUriValues,
            String[] commandNames) throws Exception {
        if (programName == null || !programName.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid runtime program name");
        }
        if (libraryUris == null || libraryNames == null
                || libraryUris.length != libraryNames.length
                || libraryUris.length == 0 || libraryUris.length > MAX_LIBRARIES) {
            throw new IllegalArgumentException("Invalid runtime library set");
        }
        if (commandUriValues == null || commandNames == null
                || commandUriValues.length != commandNames.length
                || commandUriValues.length > 512) {
            throw new IllegalArgumentException("Invalid runtime command set");
        }
        purgeStaleViews(cacheRoot);
        File links = new File(cacheRoot, "runtime-fd-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        if (!links.mkdir()) throw new IllegalStateException("Could not create runtime FD view");
        List<ParcelFileDescriptor> descriptors = new ArrayList<>(
                libraryUris.length + commandUriValues.length + 2);
        long executionId = 0;
        try {
            if (execution != null) executionId = execution.begin();
            // Android procfs does not permit glibc to reopen the executable through
            // /proc/self/fd. Keep only the main program as a private named file;
            // Descriptor-library mode still avoids materializing the runtime closure.
            ParcelFileDescriptor program = materializeElf(resolver, programUri,
                    new File(links, ".program"), execution);
            descriptors.add(program);
            ParcelFileDescriptor loader = openElf(resolver, loaderUri);
            descriptors.add(loader);
            StringBuilder manifest = new StringBuilder();
            Set<String> linkNames = new HashSet<>();
            for (int index = 0; index < libraryUris.length; index++) {
                if (!safeLinkName(libraryNames[index])
                        || !linkNames.add(libraryNames[index])) {
                    throw new SecurityException("Unsafe or duplicate runtime library name");
                }
                ParcelFileDescriptor library = descriptorLibraries
                        ? openElf(resolver, libraryUris[index])
                        : materializeElf(resolver, libraryUris[index],
                                new File(links, ".library-" + index), execution);
                descriptors.add(library);
                manifest.append(library.getFd()).append('\t')
                        .append(libraryNames[index]).append('\n');
            }
            Map<String, String> launchEnvironment = new HashMap<>(environment);
            File commandDirectory = new File(links, "commands");
            if (!commandDirectory.mkdir()) {
                throw new java.io.IOException("Could not create runtime command view");
            }
            Set<String> runtimeCommands = new HashSet<>();
            for (int index = 0; index < commandUriValues.length; index++) {
                String name = commandNames[index];
                if (!safeLinkName(name) || !runtimeCommands.add(name)
                        || commandUriValues[index] == null) {
                    throw new SecurityException("Unsafe or duplicate runtime command name");
                }
                ParcelFileDescriptor command = materializeElf(resolver,
                        Uri.parse(commandUriValues[index]),
                        new File(commandDirectory, name), execution);
                descriptors.add(command);
            }
            launchEnvironment.put("ARCHPHENE_RUNTIME_COMMAND_DIR",
                    commandDirectory.getAbsolutePath());
            launchEnvironment.put("ARCHPHENE_RUNTIME_LOADER",
                    new File(links, "loader").getAbsolutePath());
            launchEnvironment.put("ARCHPHENE_RUNTIME_LIB", links.getAbsolutePath());
            launchEnvironment.putIfAbsent("LIBGL_DRIVERS_PATH", links.getAbsolutePath());
            if (linkNames.contains("libarchphene_path_bridge.so")) {
                launchEnvironment.put("LD_PRELOAD",
                        new File(links, "libarchphene_path_bridge.so").getAbsolutePath());
            }
            boolean gstreamer = false;
            for (String name : linkNames) {
                if (name.startsWith("libgst") && name.endsWith(".so")) {
                    gstreamer = true;
                    break;
                }
            }
            if (gstreamer) {
                launchEnvironment.put("GST_PLUGIN_PATH",
                        new File(links, "gstreamer-1.0").getAbsolutePath());
                launchEnvironment.put("GST_PLUGIN_SYSTEM_PATH", "");
                launchEnvironment.put("GST_REGISTRY_FORK", "no");
                launchEnvironment.put("GST_REGISTRY_1_0",
                        new File(cacheRoot, "gstreamer-registry.bin").getAbsolutePath());
            }
            if (launchEnvironment.containsKey("QT_QPA_PLATFORM_PLUGIN_PATH")) {
                launchEnvironment.put("QT_QPA_PLATFORM_PLUGIN_PATH",
                        new File(links, "platforms").getAbsolutePath());
                String fallback = launchEnvironment.get("QT_PLUGIN_PATH");
                launchEnvironment.put("QT_PLUGIN_PATH", links.getAbsolutePath()
                        + (fallback == null || fallback.isEmpty() ? "" : ":" + fallback));
            }
            byte[] output = new byte[64 * 1024];
            int exitCode = nativeRunGlibc(program.getFd(), loader.getFd(),
                    manifest.toString().getBytes(StandardCharsets.UTF_8),
                    links.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                    encodeEnvironment(launchEnvironment),
                    programName.getBytes(StandardCharsets.UTF_8),
                    encodeArguments(arguments), executionId, descriptorLibraries ? 1 : 0, output);
            return result(exitCode, output);
        } finally {
            if (execution != null) execution.finish();
            for (int index = descriptors.size() - 1; index >= 0; index--) {
                try {
                    descriptors.get(index).close();
                } catch (Exception ignored) {
                    // Continue closing the remaining descriptors.
                }
            }
            File[] children = links.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
            links.delete();
        }
    }

    private static void purgeStaleViews(File cacheRoot) {
        if (!STALE_VIEWS_PURGED.compareAndSet(false, true)) return;
        File[] entries = cacheRoot.listFiles((directory, name) -> name.startsWith("runtime-fd-"));
        if (entries == null) return;
        for (File entry : entries) deleteRecursively(entry);
    }

    private static void deleteRecursively(File path) {
        File[] children = path.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        path.delete();
    }

    private static byte[] encodeArguments(List<String> arguments) {
        if (arguments == null || arguments.size() > 32) {
            throw new IllegalArgumentException("Invalid runtime argument list");
        }
        StringBuilder manifest = new StringBuilder();
        for (String argument : arguments) {
            if (argument == null || argument.length() > 4096
                    || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0
                    || argument.indexOf('\0') >= 0) {
                throw new SecurityException("Unsafe runtime argument");
            }
            manifest.append(argument).append('\n');
            if (manifest.length() > 32 * 1024) {
                throw new SecurityException("Runtime arguments exceed bounds");
            }
        }
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeEnvironment(Map<String, String> environment) {
        if (environment == null || environment.size() > 64) {
            throw new IllegalArgumentException("Invalid runtime environment");
        }
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
                    || value == null || value.length() > 4096
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\0') >= 0) {
                throw new SecurityException("Unsafe runtime environment entry");
            }
            manifest.append(key).append('=').append(value).append('\n');
            if (manifest.length() > 32 * 1024) {
                throw new SecurityException("Runtime environment exceeds bounds");
            }
        }
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean safeLinkName(String value) {
        if (value == null || value.isEmpty() || value.length() > 128
                || ".".equals(value) || "..".equals(value)
                || "program".equals(value) || "loader".equals(value)) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!(current >= 'A' && current <= 'Z')
                    && !(current >= 'a' && current <= 'z')
                    && !(current >= '0' && current <= '9')
                    && current != '.' && current != '_' && current != '+' && current != '-') {
                return false;
            }
        }
        return true;
    }
    private static ParcelFileDescriptor materializeElf(ContentResolver resolver, Uri uri,
            File temporary, Execution execution) throws Exception {
        try (ParcelFileDescriptor source = openElf(resolver, uri);
                ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(
                        source.getFileDescriptor());
                FileInputStream input = new FileInputStream(duplicate.getFileDescriptor());
                java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (execution != null) execution.throwIfCancelled();
                total += count;
                if (total > 2L * 1024 * 1024 * 1024) {
                    throw new SecurityException("Runtime module exceeds bounds");
                }
                output.write(buffer, 0, count);
            }
            if (total <= 0) throw new SecurityException("Runtime module is empty");
            output.getFD().sync();
        }
        android.system.Os.chmod(temporary.getAbsolutePath(), 0500);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                temporary, ParcelFileDescriptor.MODE_READ_ONLY);
        // Android app SELinux domains cannot execute manager-owned data through an
        // inherited descriptor. Keep a bounded wrapper-private view for late dlopen().
        return descriptor;
    }

    private static ParcelFileDescriptor openElf(ContentResolver resolver, Uri uri)
            throws Exception {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IllegalStateException("Runtime provider returned no FD");
        try {
            byte[] magic = new byte[4];
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(
                         descriptor.getFileDescriptor());
                 FileInputStream input = new FileInputStream(duplicate.getFileDescriptor())) {
                if (input.read(magic) != magic.length
                        || !Arrays.equals(magic, new byte[] {0x7f, 'E', 'L', 'F'})) {
                    throw new SecurityException("Runtime module is not an ELF file");
                }
            }
            android.system.Os.lseek(descriptor.getFileDescriptor(), 0,
                    android.system.OsConstants.SEEK_SET);
            return descriptor;
        } catch (Exception error) {
            descriptor.close();
            throw error;
        }
    }

    private static Result result(int exitCode, byte[] output) {
        int length = 0;
        while (length < output.length && output[length] != 0) length++;
        return new Result(exitCode,
                new String(output, 0, length, StandardCharsets.UTF_8).trim());
    }

    private static native int nativeRunFd(int fd, byte[] output);
    private static native int nativeRunGlibc(int programFd, int loaderFd,
            byte[] libraryManifest, byte[] linkDirectory, byte[] environment,
            byte[] programName, byte[] arguments, long executionId,
            int descriptorLibraries, byte[] output);
    private static native void nativeCancelGlibc(long executionId);
    private static native void nativeForgetGlibc(long executionId);
    private static native int nativeTerminateUidProcesses();
}
