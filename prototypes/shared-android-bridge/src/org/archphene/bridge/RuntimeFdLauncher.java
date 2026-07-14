package org.archphene.bridge;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Executes an immutable runtime module supplied by an Android content descriptor. */
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
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IllegalStateException("Runtime provider returned no FD");
            byte[] magic = new byte[4];
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(
                         descriptor.getFileDescriptor());
                 FileInputStream input = new FileInputStream(duplicate.getFileDescriptor())) {
                if (input.read(magic) != magic.length
                        || !Arrays.equals(magic, new byte[] {0x7f, 'E', 'L', 'F'})) {
                    throw new SecurityException("Runtime module is not an ELF file");
                }
            }
            byte[] output = new byte[8192];
            int exitCode = nativeRunFd(descriptor.getFd(), output);
            int length = 0;
            while (length < output.length && output[length] != 0) length++;
            return new Result(exitCode,
                    new String(output, 0, length, StandardCharsets.UTF_8).trim());
        }
    }

    private static native int nativeRunFd(int fd, byte[] output);
}
