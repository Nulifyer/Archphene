package org.archphene.bridge;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Private PulseAudio playback server backed by Android audio APIs. */
final class AndroidAudioIntegration {
    private static final String TAG = "ArchpheneAudio";
    private static final String SERVER = "libarchphene_pulseaudio.so";
    private static final String AAUDIO = "libarchphene_pulse_module_aaudio_sink.so";
    private static final String SLES = "libarchphene_pulse_module_sles_sink.so";
    private static final String NATIVE = "libarchphene_pulse_module_native_protocol_unix.so";
    private static final long START_TIMEOUT_MILLIS = 5000;

    private Process server;
    private File socket;
    private String serverAddress;

    synchronized void start(File nativeLibraryDir, File cacheDirectory) throws IOException {
        stop();
        File serverFile = requireHelper(nativeLibraryDir, SERVER);
        File runtime = new File(cacheDirectory, "pa");
        File modules = new File(runtime, "m");
        File state = new File(runtime, "state");
        if ((!modules.isDirectory() && !modules.mkdirs())
                || (!state.isDirectory() && !state.mkdirs())) {
            throw new IOException("Could not create private audio directories");
        }
        linkModule(nativeLibraryDir, modules, AAUDIO, "module-aaudio-sink.so");
        linkModule(nativeLibraryDir, modules, SLES, "module-sles-sink.so");
        linkModule(nativeLibraryDir, modules, NATIVE, "module-native-protocol-unix.so");

        socket = new File(runtime, "s");
        String socketPath = socket.getCanonicalPath();
        if (socketPath.getBytes(StandardCharsets.UTF_8).length >= 100) {
            throw new IOException("PulseAudio socket path is too long");
        }
        serverAddress = "unix:" + socketPath;
        IOException firstFailure;
        try {
            launch(serverFile, modules, state, runtime, socketPath, "module-aaudio-sink");
            Log.i(TAG, "Private AAudio PulseAudio server ready");
            return;
        } catch (IOException error) {
            firstFailure = error;
            Log.w(TAG, "AAudio server startup failed; trying OpenSL ES", error);
            stopProcess(server);
            server = null;
            if (socket.exists()) socket.delete();
        }
        try {
            launch(serverFile, modules, state, runtime, socketPath, "module-sles-sink");
            Log.i(TAG, "Private OpenSL ES PulseAudio server ready");
        } catch (IOException fallbackFailure) {
            fallbackFailure.addSuppressed(firstFailure);
            stop();
            throw fallbackFailure;
        }
    }

    synchronized void applyEnvironment(Map<String, String> environment) {
        if (serverAddress == null || server == null || !server.isAlive()) return;
        environment.put("PULSE_SERVER", serverAddress);
        environment.put("PULSE_RUNTIME_PATH", socket.getParentFile().getAbsolutePath());
    }

    synchronized void stop() {
        stopProcess(server);
        server = null;
        if (socket != null) socket.delete();
        socket = null;
        serverAddress = null;
    }

    private void launch(File serverFile, File modules, File state, File runtime,
            String socketPath, String sinkModule) throws IOException {
        File config = new File(runtime, "default.pa");
        String configuration = "load-module " + sinkModule
                + " sink_name=archphene_output\n"
                + "load-module module-native-protocol-unix socket=" + socketPath
                + " auth-anonymous=1\n";
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(configuration.getBytes(StandardCharsets.UTF_8));
        }
        if (socket.exists() && !socket.delete()) {
            throw new IOException("Could not remove stale PulseAudio socket");
        }
        ProcessBuilder builder = new ProcessBuilder(
                serverFile.getAbsolutePath(),
                "--daemonize=no", "--fail=yes", "--use-pid-file=no", "--system=no",
                "--exit-idle-time=-1", "--disallow-exit=yes", "--disable-shm=yes",
                "--log-target=stderr", "--log-level=info",
                "--dl-search-path=" + modules.getAbsolutePath(),
                "--file=" + config.getAbsolutePath());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("LD_LIBRARY_PATH", serverFile.getParentFile().getAbsolutePath());
        environment.put("HOME", runtime.getAbsolutePath());
        environment.put("XDG_RUNTIME_DIR", runtime.getAbsolutePath());
        environment.put("PULSE_RUNTIME_PATH", runtime.getAbsolutePath());
        environment.put("PULSE_STATE_PATH", state.getAbsolutePath());
        environment.put("TMPDIR", runtime.getAbsolutePath());
        server = builder.start();
        drain(server, sinkModule);
        waitForSocket();
    }

    private void waitForSocket() throws IOException {
        long deadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
        while (!socket.exists() && server.isAlive()
                && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(25);
        }
        if (!socket.exists() || !server.isAlive()) {
            throw new IOException("Private PulseAudio server did not become ready");
        }
    }

    private static void linkModule(File nativeDirectory, File moduleDirectory,
            String helperName, String moduleName) throws IOException {
        File helper = requireHelper(nativeDirectory, helperName);
        File module = new File(moduleDirectory, moduleName);
        unlinkIfPresent(module, moduleName);
        try {
            Os.symlink(helper.getAbsolutePath(), module.getAbsolutePath());
        } catch (Exception error) {
            throw new IOException("Could not publish audio module: " + moduleName, error);
        }
    }

    private static void unlinkIfPresent(File path, String moduleName) throws IOException {
        if (path.delete()) return;
        try {
            Os.lstat(path.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return;
            throw new IOException("Could not inspect stale audio module: " + moduleName, error);
        }
        throw new IOException("Could not replace stale audio module: " + moduleName);
    }

    private static File requireHelper(File directory, String name) throws IOException {
        File helper = new File(directory, name);
        if (!helper.isFile()) throw new IOException("Audio helper is missing: " + name);
        return helper;
    }

    private static void stopProcess(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void drain(Process process, String label) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) Log.i(TAG, label + ": " + line);
            } catch (IOException error) {
                Log.d(TAG, label + " log stream closed: " + error.getMessage());
            }
        }, "archphene-audio-log");
        thread.setDaemon(true);
        thread.start();
    }
}
