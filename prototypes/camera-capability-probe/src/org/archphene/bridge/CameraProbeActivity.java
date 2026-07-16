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
    private AndroidCapabilityBroker broker;
    private File brokerFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Set<String> capabilities = BridgeCapabilities.read(this);
            broker = new AndroidCapabilityBroker(this, capabilities);
            broker.start();
            brokerFile = new File(getFilesDir(), "camera-broker-name");
            try (FileOutputStream output = new FileOutputStream(brokerFile)) {
                output.write(broker.socketName().getBytes(StandardCharsets.UTF_8));
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
        if (broker != null) broker.close();
        if (brokerFile != null) brokerFile.delete();
        super.onDestroy();
    }
}
