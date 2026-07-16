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

/** Private session D-Bus and standard desktop adapters for one Android app UID. */
final class AndroidDesktopIntegration {
    private static final String TAG = "ArchpheneDesktop";
    private static final String DAEMON = "libarchphene_dbus_daemon.so";
    private static final String PORTAL = "libarchphene_portal_service.so";
    private static final String XDG_OPEN = "libarchphene_xdg_open.so";
    private static final long START_TIMEOUT_MILLIS = 5000;

    private Process daemon;
    private Process portal;
    private File socket;
    private File binDirectory;
    private String busAddress;

    synchronized void start(File nativeLibraryDir, File cacheDirectory,
            String brokerSocket, String appName, boolean secretsEnabled) throws IOException {
        start(nativeLibraryDir, cacheDirectory, brokerSocket, appName,
                secretsEnabled, false, false, null);
    }

    synchronized void start(File nativeLibraryDir, File cacheDirectory,
            String brokerSocket, String appName, boolean secretsEnabled,
            boolean traceSecrets) throws IOException {
        start(nativeLibraryDir, cacheDirectory, brokerSocket, appName,
                secretsEnabled, traceSecrets, false, null);
    }

    synchronized void start(File nativeLibraryDir, File cacheDirectory,
            String brokerSocket, String appName, boolean secretsEnabled,
            boolean traceSecrets, boolean cameraEnabled, String pipeWireSocket)
            throws IOException {
        if (cameraEnabled && (pipeWireSocket == null || pipeWireSocket.isBlank())) {
            throw new IOException("Camera portal requires a private PipeWire socket");
        }
        stop();
        File daemonFile = requireHelper(nativeLibraryDir, DAEMON);
        File portalFile = requireHelper(nativeLibraryDir, PORTAL);
        File xdgOpenFile = requireHelper(nativeLibraryDir, XDG_OPEN);
        File runtime = new File(cacheDirectory, "desktop-integration");
        if (!runtime.isDirectory() && !runtime.mkdirs()) {
            throw new IOException("Could not create desktop integration directory");
        }
        socket = new File(runtime, "bus");
        if (socket.exists() && !socket.delete()) {
            throw new IOException("Could not remove stale session bus socket");
        }
        File config = new File(runtime, "session.conf");
        String socketPath = socket.getCanonicalPath();
        if (socketPath.getBytes(StandardCharsets.UTF_8).length >= 100) {
            throw new IOException("Session bus socket path is too long");
        }
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(busConfiguration(socketPath).getBytes(StandardCharsets.UTF_8));
        }

        ProcessBuilder daemonBuilder = new ProcessBuilder(
                daemonFile.getAbsolutePath(),
                "--config-file=" + config.getAbsolutePath(),
                "--nofork", "--nopidfile");
        daemonBuilder.redirectErrorStream(true);
        daemon = daemonBuilder.start();
        drain(daemon, "dbus");
        waitForSocket();

        busAddress = "unix:path=" + socketPath;
        ProcessBuilder portalBuilder = new ProcessBuilder(portalFile.getAbsolutePath());
        portalBuilder.redirectErrorStream(true);
        Map<String, String> portalEnvironment = portalBuilder.environment();
        portalEnvironment.put("DBUS_SESSION_BUS_ADDRESS", busAddress);
        portalEnvironment.put("ARCHPHENE_ANDROID_BROKER", brokerSocket);
        portalEnvironment.put("ARCHPHENE_APP_NAME", appName);
        portalEnvironment.put("ARCHPHENE_ENABLE_SECRETS", secretsEnabled ? "1" : "0");
        portalEnvironment.put("ARCHPHENE_ENABLE_CAMERA", cameraEnabled ? "1" : "0");
        if (cameraEnabled) {
            portalEnvironment.put("ARCHPHENE_PIPEWIRE_SOCKET", pipeWireSocket);
        }
        if (traceSecrets) portalEnvironment.put("ARCHPHENE_SECRET_TRACE", "1");
        portal = portalBuilder.start();
        drain(portal, "portal");
        android.os.SystemClock.sleep(100);
        if (!portal.isAlive()) throw new IOException("Desktop portal service exited");

        binDirectory = new File(runtime, "bin");
        if (!binDirectory.isDirectory() && !binDirectory.mkdirs()) {
            throw new IOException("Could not create desktop command directory");
        }
        File xdgOpen = new File(binDirectory, "xdg-open");
        unlinkIfPresent(xdgOpen);
        try {
            Os.symlink(xdgOpenFile.getAbsolutePath(), xdgOpen.getAbsolutePath());
        } catch (Exception error) {
            throw new IOException("Could not publish xdg-open adapter", error);
        }
        Log.i(TAG, "Private session bus and desktop adapters ready");
    }

    synchronized String busAddress() {
        if (busAddress == null) throw new IllegalStateException("Desktop integration is not running");
        return busAddress;
    }

    synchronized void applyEnvironment(Map<String, String> environment) {
        if (busAddress == null || binDirectory == null) {
            throw new IllegalStateException("Desktop integration is not running");
        }
        environment.put("DBUS_SESSION_BUS_ADDRESS", busAddress);
        environment.put("GIO_USE_PORTALS", "1");
        environment.put("NOTIFY_FORCE_PORTAL", "1");
        String existingPath = environment.get("PATH");
        environment.put("PATH", binDirectory.getAbsolutePath()
                + (existingPath == null || existingPath.isBlank() ? "" : ":" + existingPath));
    }

    synchronized void stop() {
        stopProcess(portal);
        portal = null;
        stopProcess(daemon);
        daemon = null;
        if (socket != null) socket.delete();
        socket = null;
        binDirectory = null;
        busAddress = null;
    }

    private static void unlinkIfPresent(File path) throws IOException {
        if (path.delete()) return;
        try {
            Os.lstat(path.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return;
            throw new IOException("Could not inspect stale xdg-open adapter", error);
        }
        throw new IOException("Could not replace stale xdg-open adapter");
    }

    private void waitForSocket() throws IOException {
        long deadline = android.os.SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
        while (!socket.exists() && daemon.isAlive()
                && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(25);
        }
        if (!socket.exists() || !daemon.isAlive()) {
            throw new IOException("Private session bus did not become ready");
        }
    }

    private static File requireHelper(File directory, String name) throws IOException {
        File helper = new File(directory, name);
        if (!helper.isFile()) throw new IOException("Desktop helper is missing: " + name);
        return helper;
    }

    private static String busConfiguration(String socketPath) {
        String escaped = socketPath.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE busconfig PUBLIC \"-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN\" "
                + "\"http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd\">\n"
                + "<busconfig><type>session</type><listen>unix:path=" + escaped + "</listen>"
                + "<auth>EXTERNAL</auth><policy context=\"default\">"
                + "<allow own=\"*\"/><allow send_destination=\"*\"/>"
                + "<allow receive_sender=\"*\"/></policy>"
                + "<limit name=\"max_completed_connections\">64</limit>"
                + "<limit name=\"max_connections_per_user\">64</limit>"
                + "<limit name=\"max_message_size\">1048576</limit>"
                + "<limit name=\"max_incoming_bytes\">4194304</limit>"
                + "<limit name=\"max_outgoing_bytes\">4194304</limit>"
                + "</busconfig>\n";
    }

    private static void stopProcess(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
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
        }, "archphene-" + label + "-log");
        thread.setDaemon(true);
        thread.start();
    }
}
