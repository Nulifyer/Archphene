package org.archphene.bridge;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Executes immutable runtime modules supplied by Android content descriptors. */
public final class RuntimeFdLauncher {
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
        File links = new File(cacheRoot, "runtime-fd-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        if (!links.mkdir()) throw new IllegalStateException("Could not create runtime FD view");
        try (ParcelFileDescriptor program = openElf(resolver, programUri);
             ParcelFileDescriptor loader = openElf(resolver, loaderUri);
             ParcelFileDescriptor libc = openElf(resolver, libcUri)) {
            byte[] output = new byte[8192];
            int exitCode = nativeRunGlibc(program.getFd(), loader.getFd(), libc.getFd(),
                    links.getAbsolutePath().getBytes(StandardCharsets.UTF_8), output);
            return result(exitCode, output);
        } finally {
            File[] children = links.listFiles();
            if (children != null) {
                for (File child : children) child.delete();
            }
            links.delete();
        }
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
    private static native int nativeRunGlibc(int programFd, int loaderFd, int libcFd,
            byte[] linkDirectory, byte[] output);
}
