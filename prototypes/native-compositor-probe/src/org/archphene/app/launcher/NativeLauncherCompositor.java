package org.archphene.app.launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Exact JNI-name probe for the generated launcher's opaque native handles. */
public final class NativeLauncherCompositor {
    private static final int MAX_HANDLES = 4;

    private native long nativeCreate(
            byte[] socketPath,
            int width,
            int height,
            int densityDpi,
            int geometryPercent);

    private native int nativeRequestClose(long handle);

    private native void nativeDestroy(long handle);

    public static void verifyHandleRegistry(File cacheDirectory) {
        NativeLauncherCompositor nativeBridge = new NativeLauncherCompositor();
        long[] handles = new long[MAX_HANDLES];
        for (int index = 0; index < handles.length; index++) {
            handles[index] = nativeBridge.nativeCreate(
                    socketPath(cacheDirectory, index),
                    1080,
                    2205,
                    420,
                    100);
            if (handles[index] <= 0) {
                throw new IllegalStateException("launcher handle allocation " + index);
            }
        }
        if (nativeBridge.nativeCreate(
                        socketPath(cacheDirectory, MAX_HANDLES),
                        1080,
                        2205,
                        420,
                        100)
                != 0) {
            throw new IllegalStateException("launcher handle registry exceeded its bound");
        }

        long stale = handles[0];
        nativeBridge.nativeDestroy(stale);
        if (nativeBridge.nativeRequestClose(stale) != -1) {
            throw new IllegalStateException("destroyed launcher handle remained usable");
        }
        long replacement = nativeBridge.nativeCreate(
                socketPath(cacheDirectory, MAX_HANDLES + 1),
                1080,
                2205,
                420,
                100);
        if (replacement <= 0 || replacement == stale) {
            throw new IllegalStateException("launcher slot reuse omitted a new generation");
        }
        nativeBridge.nativeDestroy(stale);
        if (nativeBridge.nativeRequestClose(replacement) != 0) {
            throw new IllegalStateException("stale destroy affected replacement launcher");
        }

        nativeBridge.nativeDestroy(replacement);
        for (int index = 1; index < handles.length; index++) {
            nativeBridge.nativeDestroy(handles[index]);
        }
    }

    private static byte[] socketPath(File cacheDirectory, int index) {
        return new File(cacheDirectory, "launcher-handle-" + index + ".sock")
                .getAbsolutePath()
                .getBytes(StandardCharsets.UTF_8);
    }
}
