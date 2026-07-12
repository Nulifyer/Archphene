package org.archphene.bridgeprobe;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String AUTHORITY = "org.archphene.bridgeprobe.documents";
    private TextView report;
    private int configurationChanges;

    static {
        System.loadLibrary("archphene_wayland_client_android");
        System.loadLibrary("archphene_arm64_probe");
    }

    private static native String runNativeChecks(String filesDir);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configurationChanges = state == null ? 0 : state.getInt("configurationChanges", 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 24, 32, 32);

        TextView title = new TextView(this);
        title.setText("Archphene ARM64 bridge probe");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title);

        Button rerun = new Button(this);
        rerun.setText("Run bridge checks");
        rerun.setOnClickListener(view -> updateReport());
        content.addView(rerun);

        report = new TextView(this);
        report.setTextSize(15);
        report.setTextIsSelectable(true);
        content.addView(report, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        updateReport();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        configurationChanges++;
        updateReport();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("configurationChanges", configurationChanges);
        super.onSaveInstanceState(state);
    }

    private void updateReport() {
        report.post(() -> {
            View root = report.getRootView();
            WindowInsets insets = root.getRootWindowInsets();
            int insetWidth = root.getWidth();
            int insetHeight = root.getHeight();
            if (insets != null) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetWidth -= bars.left + bars.right;
                insetHeight -= bars.top + bars.bottom;
            }

            String provider = runProviderRoundTrip();
            String nativeResult;
            try {
                nativeResult = runNativeChecks(getFilesDir().getAbsolutePath());
            } catch (Throwable error) {
                nativeResult = "FAIL: " + error;
            }
            String value = "Android API: " + Build.VERSION.SDK_INT
                    + "\nABIs: " + Arrays.toString(Build.SUPPORTED_ABIS)
                    + "\nViewport: " + root.getWidth() + "x" + root.getHeight()
                    + "\nUsable viewport: " + insetWidth + "x" + insetHeight
                    + "\nConfiguration changes: " + configurationChanges
                    + "\nDocumentsProvider: " + provider
                    + "\nArch Linux ARM glibc: " + runGlibcProbe()
                    + "\nNative checks:\n" + nativeResult;
            report.setText(value);
            android.util.Log.i("ArchpheneArm64Probe", value.replace('\n', ' '));
        });
    }

    private String runProviderRoundTrip() {
        try {
            File documents = new File(getFilesDir(), "linux-home/Documents");
            if (!documents.isDirectory() && !documents.mkdirs()) {
                return "FAIL create Linux Home";
            }
            File probe = new File(documents, "arm64-probe.txt");
            if (!probe.isFile() && !probe.createNewFile()) {
                return "FAIL create provider document";
            }
            String marker = "archphene-arm64-provider-ok";
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, "home/Documents/arm64-probe.txt");
            ContentResolver resolver = getContentResolver();
            try (OutputStream output = resolver.openOutputStream(uri, "rwt")) {
                if (output == null) return "FAIL output stream";
                output.write(marker.getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return "FAIL input stream";
                bytes = input.readAllBytes();
            }
            return marker.equals(new String(bytes, StandardCharsets.UTF_8))
                    ? "PASS read/write" : "FAIL content mismatch";
        } catch (Throwable error) {
            return "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
    private String runGlibcProbe() {
        try {
            File runtime = new File(getApplicationInfo().nativeLibraryDir);
            File loader = new File(runtime, "libarchphene_glibc_loader.so");
            File libc = new File(runtime, "libarchphene_glibc_libc.so");
            File probe = new File(runtime, "libarchphene_glibc_probe.so");
            Process process = new ProcessBuilder(loader.getAbsolutePath(), "--preload",
                    libc.getAbsolutePath(), "--library-path", runtime.getAbsolutePath(),
                    probe.getAbsolutePath())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "FAIL timeout";
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int marker = output.lastIndexOf("ARCHPHENE_GLIBC_PASS");
            if (process.exitValue() == 0 && marker >= 0) {
                int lineEnd = output.indexOf('\n', marker);
                return "PASS " + output.substring(marker,
                        lineEnd < 0 ? output.length() : lineEnd).trim();
            }
            String tail = output.length() <= 800 ? output : output.substring(output.length() - 800);
            return "FAIL exit=" + process.exitValue() + " " + tail;
        } catch (Throwable error) {
            return "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
}
