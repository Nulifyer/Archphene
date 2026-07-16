package org.archphene.urigrantprobe;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final String TAG = "ArchpheneUriGrantProbe";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        try (InputStream input = uri == null ? null
                : getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("provider returned no stream");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() + read > 1024 * 1024) {
                    throw new SecurityException("provider response is too large");
                }
                output.write(buffer, 0, read);
            }
            Log.i(TAG, "URI read passed "
                    + new String(output.toByteArray(), StandardCharsets.UTF_8).trim());
        } catch (Exception error) {
            Log.i(TAG, "URI read denied " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        } finally {
            finish();
        }
    }
}