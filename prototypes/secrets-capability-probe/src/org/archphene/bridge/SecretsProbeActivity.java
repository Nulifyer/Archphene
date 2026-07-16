package org.archphene.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Device probe host for the same encrypted secret broker used by generated wrappers. */
public final class SecretsProbeActivity extends Activity {
    private AndroidCapabilityBroker broker;
    private final AndroidDesktopIntegration desktopIntegration = new AndroidDesktopIntegration();
    private File brokerFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            writeFixture("secret-input", "archphene-secret-value-284917");
            writeFixture("secret-updated", "archphene-updated-value-592641");
            try (FileOutputStream output = new FileOutputStream(
                    new File(getFilesDir(), "secret-oversized"), false)) {
                byte[] block = new byte[8192];
                for (int index = 0; index < 8; index++) output.write(block);
                output.write(0);
                output.getFD().sync();
            }
            String[] libsecretAssets = getAssets().list("libsecret");
            if (libsecretAssets != null && libsecretAssets.length != 0) {
                File libsecretRoot = new File(getFilesDir(), "libsecret-runtime");
                extractAssetTree("libsecret", libsecretRoot);
                for (String executable : new String[] {
                        "secret-tool", "gdbus", "kwalletd6", "kwallet-query"
                }) {
                    Os.chmod(new File(libsecretRoot, executable).getAbsolutePath(), 0700);
                }
                writeFixture("libsecret-runtime-root", libsecretRoot.getCanonicalPath());
            }
            Set<String> capabilities = BridgeCapabilities.read(this);
            broker = new AndroidCapabilityBroker(this, capabilities);
            broker.start();
            desktopIntegration.start(new File(getApplicationInfo().nativeLibraryDir),
                    getCacheDir(), "@" + broker.socketName(), "Archphene Secrets Probe", true, true);
            writeFixture("secrets-bus-address", desktopIntegration.busAddress());
            brokerFile = new File(getFilesDir(), "secrets-broker-name");
            try (FileOutputStream output = new FileOutputStream(brokerFile, false)) {
                output.write(broker.socketName().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            TextView status = new TextView(this);
            status.setText("Archphene secrets capability probe ready");
            status.setTextSize(18);
            status.setPadding(32, 48, 32, 32);
            setContentView(status);
        } catch (Exception error) {
            throw new IllegalStateException("Could not start secrets probe", error);
        }
    }

    private void extractAssetTree(String assetPath, File target) throws Exception {
        String[] children = getAssets().list(assetPath);
        if (children != null && children.length != 0) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IllegalStateException("Could not create libsecret fixture directory");
            }
            for (String child : children) {
                extractAssetTree(assetPath + "/" + child, new File(target, child));
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Could not create libsecret fixture parent");
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = getAssets().open(assetPath);
                FileOutputStream output = new FileOutputStream(target, false)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count != 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }
    private void writeFixture(String name, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
                new File(getFilesDir(), name), false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    @Override
    protected void onDestroy() {
        desktopIntegration.stop();
        if (broker != null) broker.close();
        if (brokerFile != null) brokerFile.delete();
        super.onDestroy();
    }
}
