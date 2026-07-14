package org.archphene.bridge;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes immutable runtime modules supplied by Android content descriptors. */
public final class RuntimeFdLauncher {
    private static final int MAX_LIBRARIES = 510;

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    static { System.loadLibrary("archphene_compositor"); }
    private RuntimeFdLauncher() {}

    public static Result run(ContentResolver resolver, Uri uri) throws Exception {
        try (ParcelFileDescriptor descriptor = openElf(resolver, uri)) {
            byte[] output = new byte[8192];
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
        if (libraryUris == null || libraryNames == null
                || libraryUris.length != libraryNames.length
                || libraryUris.length == 0 || libraryUris.length > MAX_LIBRARIES) {
            throw new IllegalArgumentException("Invalid runtime library set");
        }
        File links = new File(cacheRoot, "runtime-fd-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        if (!links.mkdir()) throw new IllegalStateException("Could not create runtime FD view");
        List<ParcelFileDescriptor> descriptors = new ArrayList<>(libraryUris.length + 2);
        try {
            ParcelFileDescriptor program = openElf(resolver, programUri);
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
                ParcelFileDescriptor library = openElf(resolver, libraryUris[index]);
                descriptors.add(library);
                manifest.append(library.getFd()).append('\t')
                        .append(libraryNames[index]).append('\n');
            }
            byte[] output = new byte[8192];
            int exitCode = nativeRunGlibc(program.getFd(), loader.getFd(),
                    manifest.toString().getBytes(StandardCharsets.UTF_8),
                    links.getAbsolutePath().getBytes(StandardCharsets.UTF_8), output);
            return result(exitCode, output);
        } finally {
            for (int index = descriptors.size() - 1; index >= 0; index--) {
                try {
                    descriptors.get(index).close();
                } catch (Exception ignored) {
                    // Continue closing the remaining descriptors.
                }
            }
            File[] children = links.listFiles();
            if (children != null) {
                for (File child : children) child.delete();
            }
            links.delete();
        }
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
            byte[] libraryManifest, byte[] linkDirectory, byte[] output);
}
