package org.archphene.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.util.Set;

/** Device probe host for the same camera broker used by generated wrappers. */
public final class CameraProbeActivity extends Activity {
    private final AndroidDesktopIntegration desktopIntegration =
            new AndroidDesktopIntegration();
    private AndroidCapabilityBroker broker;
    private File brokerFile;
    private File busFile;
    private File pipeWireSocket;
    private Process pipeWireProbe;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Set<String> capabilities = BridgeCapabilities.read(this);
            broker = new AndroidCapabilityBroker(this, capabilities);
            broker.start();
            File nativeDirectory = new File(getApplicationInfo().nativeLibraryDir);
            File socketProbe = new File(
                    nativeDirectory, "libarchphene_pipewire_socket_probe.so");
            if (!socketProbe.isFile()) {
                throw new IllegalStateException("PipeWire socket probe is missing");
            }
            pipeWireSocket = new File(getCacheDir(), "pipewire-camera-probe");
            if (pipeWireSocket.exists() && !pipeWireSocket.delete()) {
                throw new IllegalStateException("Could not remove stale PipeWire probe socket");
            }
            ProcessBuilder pipeWireBuilder = new ProcessBuilder(
                    socketProbe.getAbsolutePath(), pipeWireSocket.getAbsolutePath());
            pipeWireBuilder.redirectErrorStream(true);
            pipeWireProbe = pipeWireBuilder.start();
            long socketDeadline = android.os.SystemClock.uptimeMillis() + 5000;
            while (!pipeWireSocket.exists() && pipeWireProbe.isAlive()
                    && android.os.SystemClock.uptimeMillis() < socketDeadline) {
                android.os.SystemClock.sleep(25);
            }
            if (!pipeWireSocket.exists() || !pipeWireProbe.isAlive()) {
                throw new IllegalStateException("PipeWire socket probe did not start");
            }
            desktopIntegration.start(nativeDirectory, getCacheDir(),
                    "@" + broker.socketName(), "Archphene Camera Probe",
                    false, false, true, pipeWireSocket.getAbsolutePath());
            brokerFile = new File(getFilesDir(), "camera-broker-name");
            try (FileOutputStream output = new FileOutputStream(brokerFile)) {
                output.write(broker.socketName().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            busFile = new File(getFilesDir(), "camera-bus-address");
            try (FileOutputStream output = new FileOutputStream(busFile)) {
                output.write(desktopIntegration.busAddress()
                        .getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            TextView status = new TextView(this);
            status.setText("Archphene camera capability probe ready");
            status.setTextSize(18);
            status.setPadding(32, 48, 32, 32);
            setContentView(status);
        } catch (Exception error) {
            throw new IllegalStateException("Could not start camera probe", error);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (broker != null) broker.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onDestroy() {
        desktopIntegration.stop();
        if (pipeWireProbe != null) {
            pipeWireProbe.destroy();
            try {
                if (!pipeWireProbe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    pipeWireProbe.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                pipeWireProbe.destroyForcibly();
            }
        }
        if (broker != null) broker.close();
        if (brokerFile != null) brokerFile.delete();
        if (busFile != null) busFile.delete();
        if (pipeWireSocket != null) pipeWireSocket.delete();
        super.onDestroy();
    }
}
