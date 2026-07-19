package org.archphene.bridge;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Runs the per-app Bionic virgl renderer used by glibc Mesa clients. */
final class AndroidGpuBridge {
    private static final String TAG = "ArchpheneGpu";
    private static final String HELPER = "libarchphene_virgl_server.so";
    private static final long START_TIMEOUT_MILLIS = 3000;

    private Process process;
    private File socket;
    private boolean stopping;
    private boolean unexpectedExit;

    synchronized File start(File nativeLibraryDir, File runtimeDir) {
        stop();
        stopping = false;
        unexpectedExit = false;
        File helper = new File(nativeLibraryDir, HELPER);
        if (!helper.isFile()) {
            Log.w(TAG, "GPU helper is unavailable; using software rendering");
            return null;
        }
        if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs()) {
            Log.w(TAG, "GPU socket directory is unavailable; using software rendering");
            return null;
        }
        socket = new File(runtimeDir, ".vg");
        if (socket.getAbsolutePath().getBytes(StandardCharsets.UTF_8).length >= 104) {
            Log.w(TAG, "GPU socket path is too long; using software rendering");
            socket = null;
            return null;
        }
        if (socket.exists() && !socket.delete()) {
            Log.w(TAG, "Could not remove stale GPU socket; using software rendering");
            socket = null;
            return null;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    helper.getAbsolutePath(),
                    "--no-fork",
                    "--use-egl-surfaceless",
                    "--use-gles",
                    "--socket-path",
                    socket.getAbsolutePath());
            builder.redirectErrorStream(true);
            process = builder.start();
            startLogDrain(process);
            long deadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
            while (!socket.exists() && processAlive(process)
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(25);
            }
            if (socket.exists() && processAlive(process)) {
                startExitMonitor(process);
                Log.i(TAG, "GPU bridge ready socket=" + socket.getAbsolutePath());
                return socket;
            }
            Log.w(TAG, "GPU helper did not become ready; using software rendering");
        } catch (Throwable error) {
            Log.w(TAG, "GPU helper startup failed; using software rendering", error);
        }
        stop();
        return null;
    }

    synchronized void stop() {
        Process current = process;
        stopping = true;
        process = null;
        if (current != null) {
            current.destroy();
            try {
                current.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (socket != null) {
            socket.delete();
            socket = null;
        }
    }

    synchronized boolean failedUnexpectedly() {
        return unexpectedExit || (process != null && !stopping && !processAlive(process));
    }

    private static boolean processAlive(Process value) {
        try {
            value.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private static void startLogDrain(Process value) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    value.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "renderer: " + line);
                }
            } catch (Exception error) {
                Log.d(TAG, "GPU helper log stream closed: " + error.getMessage());
            }
        }, "archphene-gpu-log");
        thread.setDaemon(true);
        thread.start();
    }

    private void startExitMonitor(Process value) {
        Thread thread = new Thread(() -> {
            int exitCode;
            try {
                exitCode = value.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (AndroidGpuBridge.this) {
                if (process != value || stopping) return;
                unexpectedExit = true;
                if (socket != null) socket.delete();
            }
            Log.e(TAG, "GPU helper exited unexpectedly code=" + exitCode);
        }, "archphene-gpu-exit");
        thread.setDaemon(true);
        thread.start();
    }
}
