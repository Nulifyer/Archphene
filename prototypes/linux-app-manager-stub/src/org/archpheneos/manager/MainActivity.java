package org.archpheneos.manager;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);

        TextView title = new TextView(this);
        title.setText("Archphene Linux Apps");
        title.setTextSize(24);

        TextView body = new TextView(this);
        body.setTextSize(16);
        body.setPadding(0, 28, 0, 0);
        body.setText("Installed Arch packages run as separate Android apps with their own UID, permissions, storage, and lifecycle.");

        root.addView(title);
        root.addView(body);
        renderInstalledApps(root);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        String testUpdateUrl = getIntent().getStringExtra("archphene_test_apk_url");
        String testUpdateHash = getIntent().getStringExtra("archphene_test_apk_sha256");
        String testUpdatePackage = getIntent().getStringExtra("archphene_test_apk_package");
        if (testUpdateUrl != null && testUpdateHash != null && testUpdatePackage != null) {
            body.setText("Preparing verified Android package update");
            ApkUpdateInstaller.install(this, testUpdateUrl, testUpdateHash, testUpdatePackage,
                    (status, terminal) -> body.setText(status));
        }
    }

    private void renderInstalledApps(LinearLayout root) {
        try {
            java.util.List<InstalledLinuxAppCatalog.Entry> apps = InstalledLinuxAppCatalog.query(this);
            if (apps.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No generated Linux app APKs installed.");
                empty.setPadding(0, 36, 0, 0);
                root.addView(empty);
                return;
            }
            for (InstalledLinuxAppCatalog.Entry app : apps) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 36, 0, 24);

                ImageView icon = new ImageView(this);
                Drawable drawable = getPackageManager().getApplicationIcon(app.packageName);
                icon.setImageDrawable(drawable);
                row.addView(icon, new LinearLayout.LayoutParams(96, 96));

                TextView details = new TextView(this);
                String baseDetails = app.label + "\n" + app.sourceType + ": " + app.sourceId
                        + " " + app.sourceVersion + "\nRuntime: " + app.runtimeAbi;
                details.setText(baseDetails);
                details.setPadding(24, 0, 16, 0);
                row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.VERTICAL);
                Button launch = new Button(this);
                launch.setText("Launch");
                launch.setOnClickListener(view -> startActivity(app.launchIntent));
                actions.addView(launch);

                Button check = new Button(this);
                check.setText("Check");
                check.setEnabled(!app.updateUrl.isEmpty());
                check.setOnClickListener(view -> {
                    check.setEnabled(false);
                    check.setText("Checking");
                    new Thread(() -> {
                        try {
                            ArchPackageUpdateChecker.Result update =
                                    ArchPackageUpdateChecker.check(app.updateUrl, app.sourceVersion);
                            runOnUiThread(() -> details.setText(baseDetails + "\nAvailable: "
                                    + update.availableVersion + "\n"
                                    + (update.updateAvailable ? "Update available" : "Up to date")));
                        } catch (Exception e) {
                            runOnUiThread(() -> details.setText(baseDetails + "\nUpdate check failed: " + e.getMessage()));
                        } finally {
                            runOnUiThread(() -> {
                                check.setText("Check");
                                check.setEnabled(true);
                            });
                        }
                    }, "archphene-update-check").start();
                });
                actions.addView(check);
                row.addView(actions);
                root.addView(row);
            }
        } catch (Exception e) {
            TextView error = new TextView(this);
            error.setText("Could not query installed Linux apps: " + e);
            error.setPadding(0, 36, 0, 0);
            root.addView(error);
        }
    }
}