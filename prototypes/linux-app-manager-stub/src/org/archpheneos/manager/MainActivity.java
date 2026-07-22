package org.archpheneos.manager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_UNINSTALL_LINUX_APP = 0x4150;
    private static int COLOR_BACKGROUND = Color.rgb(248, 250, 252);
    private static int COLOR_SURFACE = Color.rgb(240, 243, 245);
    private static int COLOR_SURFACE_ACTIVE = Color.rgb(216, 238, 248);
    private static int COLOR_PRIMARY = Color.rgb(23, 147, 209);
    private static int COLOR_TEXT = Color.rgb(31, 37, 41);
    private static int COLOR_MUTED = Color.rgb(92, 103, 110);
    private static int COLOR_SUCCESS = Color.rgb(35, 113, 76);
    private static int COLOR_WARNING = Color.rgb(153, 89, 0);
    private static int COLOR_ERROR = Color.rgb(176, 42, 55);

    private FrameLayout content;
    private TextView appsNav;
    private TextView settingsNav;
    private Button addFab;
    private TextView statusBanner;
    private LinearLayout appList;
    private EditText search;
    private Button filter;
    private PullToRefreshLayout pullToRefresh;
    private List<InstalledLinuxAppCatalog.Entry> apps = new ArrayList<>();
    private String query = "";
    private int currentPage;
    private boolean darkTheme;
    private String activeInstallPackage = "";
    private ApkUpdateInstaller.Operation activeInstallOperation;
    private ApkUpdateInstaller.Phase activeInstallPhase = ApkUpdateInstaller.Phase.DOWNLOAD;
    private int activeInstallPercent;
    private String activeInstallStatus = "";
    private LinearLayout packageJobDetail;
    private String packageJobDetailId = "";
    private InstalledLinuxAppCatalog.Entry packageJobDetailApp;
    private final AtomicInteger packageSearchGeneration = new AtomicInteger();
    private static final long PROGRESS_RENDER_INTERVAL_MILLIS = 1500;
    private long nextProgressRenderAt;
    private boolean progressRenderPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTestAppearancePreferences();
        String requestedTheme = ManagerStateStore.themeMode(this);
        if ("dark".equals(requestedTheme)) setTheme(android.R.style.Theme_Material_NoActionBar);
        if ("light".equals(requestedTheme)) setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        applySystemPalette();
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        if (Build.VERSION.SDK_INT >= 23) {
            int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) lightBars |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0 : lightBars);
        }
        buildShell();
        PackageInstallJobStore.recoverInterruptedOnce(this);
        LinuxAppManagerService.schedule(this, ManagerStateStore.backgroundChecksEnabled(this));
        new Thread(() -> {
            try {
                int transactions = ArchPackageRuntime.cleanupAbandonedStaging(this);
                int removed = RuntimePackStore.garbageCollect(this);
                if (transactions > 0) android.util.Log.i("ArchphenePackages",
                        "removed " + transactions + " abandoned package transactions");
                if (removed > 0) android.util.Log.i("ArchpheneRuntime",
                        "garbage-collected " + removed + " unreferenced runtime packs");
            } catch (Exception error) {
                android.util.Log.e("ArchpheneRuntime", "Runtime-pack cleanup failed", error);
            }
        }, "archphene-runtime-pack-gc").start();
        showAppsPage();
        showPackageCompatibilityNotice();
        handleTerminalRequestIntent();
        if (ManagerStateStore.checkOnLaunch(this) && currentPage == 0) checkAll();
        handleTestIntents();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTestIntents();
        handleTerminalRequestIntent();
    }

    private void handleTestIntents() {
        if (!testHooksEnabled()) return;
        handleTestInstallIntent();
        handleTestPackageRuntimeIntent();
        handleTestWrapperSigningIntent();
        handleTestWrapperAssemblyIntent();
        handleTestGitHubReleaseIntent();
        handleTestRuntimeModuleIntent();
        handleTestGuiDocumentsIntent();
        handleTestDocumentSessionIntent();
        handleTestMicrophonePreferenceIntent();
    }

    private boolean testHooksEnabled() {
        return (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void applyTestAppearancePreferences() {
        if (!testHooksEnabled()) return;
        Intent intent = getIntent();
        String managerTheme = intent.getStringExtra("archphene_test_manager_theme");
        if ("system".equals(managerTheme) || "dark".equals(managerTheme)
                || "light".equals(managerTheme)) {
            ManagerStateStore.setThemeMode(this, managerTheme);
        }
        String linuxTheme = intent.getStringExtra("archphene_test_linux_theme");
        if ("system".equals(linuxTheme) || "dark".equals(linuxTheme)
                || "light".equals(linuxTheme)) {
            ManagerStateStore.setLinuxThemeMode(this, linuxTheme);
        }
        if (intent.hasExtra("archphene_test_material_you")) {
            ManagerStateStore.setMaterialYou(this,
                    intent.getBooleanExtra("archphene_test_material_you", false));
        }
        intent.removeExtra("archphene_test_manager_theme");
        intent.removeExtra("archphene_test_linux_theme");
        intent.removeExtra("archphene_test_material_you");
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (content != null && currentPage == 0) loadCatalog();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UNINSTALL_LINUX_APP) return;
        String uninstalledPackage = ManagerStateStore.takePendingUninstallPackage(this);
        ArchPackageRepository.PackageResult reinstall =
                ManagerStateStore.takePendingReinstall(this);
        loadCatalog();
        if (resultCode != RESULT_OK) {
            if (reinstall != null) showBanner("Wrapper migration cancelled", false);
            return;
        }
        showBanner(reinstall == null ? "App uninstalled" : "Preparing replacement app", false);
        if (!uninstalledPackage.isEmpty()) {
            new Thread(() -> {
                try {
                    RuntimePackStore.removeBinding(this, uninstalledPackage);
                } catch (Exception error) {
                    android.util.Log.e("ArchpheneRuntime",
                            "Could not release uninstalled app runtime", error);
                }
            }, "archphene-uninstall-runtime-release").start();
        }
        if (reinstall != null) {
            content.postDelayed(() -> startOnDevicePackageInstall(reinstall), 300);
        }
    }
    private void handleTerminalRequestIntent() {
        Intent intent = getIntent();
        String requestId = intent.getStringExtra("archphene_terminal_request_id");
        intent.removeExtra("archphene_terminal_request_id");
        TerminalRequestStore.Request terminalRequest =
                TerminalRequestStore.take(this, requestId);
        if (terminalRequest == null) return;
        String action = terminalRequest.action;
        String query = terminalRequest.query.trim();
        TerminalCommandReporter.report(this, terminalRequest.id, "request", 1,
                false, "running", "Accepted " + action + " request");
        if (("install".equals(action) || "search".equals(action))
                && query.matches("[a-zA-Z0-9@._+:-]{2,128}")) {
            if ("search".equals(action)) {
                showPackageSearchPage(query);
                reportTerminalPackageSearch(terminalRequest.id, query);
            } else {
                resolveTerminalPackageInstall(terminalRequest.id, query);
            }
            return;
        }
        if ("remove".equals(action) && query.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            confirmTerminalPackageRemoval(terminalRequest.id, query);
            return;
        }
        if ("upgrade".equals(action) && "all".equals(query)) {
            showAppsPage();
            checkAll();
            TerminalCommandReporter.report(this, terminalRequest.id, "upgrade", 100,
                    true, "success",
                    "Update check started; pinned versions remain unchanged");
            return;
        }
        TerminalCommandReporter.report(this, terminalRequest.id, "request", 0,
                true, "error", "Unsupported Terminal package request");
    }

    private void reportTerminalPackageSearch(String requestId, String query) {
        new Thread(() -> {
            try {
                List<ArchPackageRepository.PackageResult> found =
                        ArchPackageRepository.search(this, query);
                int compatible = 0;
                for (ArchPackageRepository.PackageResult result : found) {
                    if (ArchRuntimePolicy.supports(result.architecture)) compatible++;
                }
                TerminalCommandReporter.report(this, requestId, "search", 100,
                        true, "success", "Found " + compatible
                                + " compatible package result(s) in Archphene");
            } catch (Exception error) {
                TerminalCommandReporter.report(this, requestId, "search", 0,
                        true, "error", "Repository search failed: " + safeMessage(error));
            }
        }, "archphene-terminal-search").start();
    }

    private void resolveTerminalPackageInstall(String requestId, String packageName) {
        showAppsPage();
        showBanner("Resolving " + packageName + " for Terminal", false);
        new Thread(() -> {
            try {
                ArchPackageRepository.PackageResult exact = null;
                for (ArchPackageRepository.PackageResult result
                        : ArchPackageRepository.search(this, packageName)) {
                    if (packageName.equals(result.name)
                            && ArchRuntimePolicy.supports(result.architecture)) {
                        exact = result;
                        break;
                    }
                }
                if (exact == null) {
                    throw new IllegalStateException(
                            "No compatible exact package named " + packageName);
                }
                ArchPackageRepository.PackageResult selected = exact;
                runOnUiThread(() -> startOnDevicePackageInstall(selected, requestId));
            } catch (Exception error) {
                TerminalCommandReporter.report(this, requestId, "resolve", 0,
                        true, "error", safeMessage(error));
                runOnUiThread(() -> showBanner(
                        "Could not resolve " + packageName + ": " + safeMessage(error), true));
            }
        }, "archphene-terminal-resolve").start();
    }

    private void confirmTerminalPackageRemoval(String requestId, String packageName) {
        ManagedPackageStore.Entry target = null;
        try {
            for (ManagedPackageStore.Entry entry : ManagedPackageStore.list(this)) {
                if (packageName.equals(entry.name)) {
                    target = entry;
                    break;
                }
            }
        } catch (Exception error) {
            TerminalCommandReporter.report(this, requestId, "remove", 0,
                    true, "error", "Could not read Terminal packages: " + safeMessage(error));
            return;
        }
        if (target == null) {
            TerminalCommandReporter.report(this, requestId, "remove", 0,
                    true, "error", packageName + " is not installed in Terminal");
            return;
        }
        ManagedPackageStore.Entry selected = target;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + packageName + "?")
                .setMessage("This removes the package from the shared Terminal environment. "
                        + "Terminal home files are kept.")
                .setNegativeButton("Cancel", (dialog, which) ->
                        TerminalCommandReporter.report(this, requestId, "remove", 0,
                                true, "cancelled", "Package removal cancelled"))
                .setPositiveButton("Remove", (dialog, which) -> new Thread(() -> {
                    try {
                        ManagedPackageStore.remove(this, selected);
                        PackageInstallJobStore.clear(this,
                                PackageInstallJobStore.key(selected.source()));
                        TerminalCommandReporter.report(this, requestId, "remove", 100,
                                true, "success", packageName + " removed from Terminal");
                        runOnUiThread(() -> {
                            showBanner(packageName + " removed from Terminal", false);
                            showAppsPage();
                        });
                    } catch (Exception error) {
                        TerminalCommandReporter.report(this, requestId, "remove", 0,
                                true, "error", "Removal failed: " + safeMessage(error));
                    }
                }, "archphene-terminal-remove").start())
                .setOnCancelListener(dialog ->
                        TerminalCommandReporter.report(this, requestId, "remove", 0,
                                true, "cancelled", "Package removal cancelled"))
                .show();
    }
    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isEmpty()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : value;
    }
    private void applySystemPalette() {
        String requestedTheme = ManagerStateStore.themeMode(this);
        darkTheme = "dark".equals(requestedTheme) || !"light".equals(requestedTheme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (darkTheme) {
            COLOR_BACKGROUND = Color.rgb(17, 20, 23);
            COLOR_SURFACE = Color.rgb(29, 34, 38);
            COLOR_SURFACE_ACTIVE = Color.rgb(22, 58, 77);
            COLOR_PRIMARY = Color.rgb(86, 188, 236);
            COLOR_TEXT = Color.rgb(240, 245, 247);
            COLOR_MUTED = Color.rgb(180, 191, 197);
            COLOR_SUCCESS = Color.rgb(100, 205, 151);
            COLOR_WARNING = Color.rgb(255, 184, 92);
            COLOR_ERROR = Color.rgb(255, 180, 184);
        } else {
            COLOR_BACKGROUND = Color.rgb(248, 250, 252);
            COLOR_SURFACE = Color.rgb(240, 243, 245);
            COLOR_SURFACE_ACTIVE = Color.rgb(216, 238, 248);
            COLOR_PRIMARY = Color.rgb(23, 147, 209);
            COLOR_TEXT = Color.rgb(31, 37, 41);
            COLOR_MUTED = Color.rgb(92, 103, 110);
            COLOR_SUCCESS = Color.rgb(35, 113, 76);
            COLOR_WARNING = Color.rgb(153, 89, 0);
            COLOR_ERROR = Color.rgb(176, 42, 55);
        }
        if (ManagerStateStore.materialYou(this) && Build.VERSION.SDK_INT >= 31) {
            COLOR_PRIMARY = getColor(darkTheme ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_600);
            COLOR_BACKGROUND = getColor(darkTheme ? android.R.color.system_neutral1_900
                    : android.R.color.system_neutral1_10);
            COLOR_SURFACE = getColor(darkTheme ? android.R.color.system_neutral1_800
                    : android.R.color.system_neutral1_50);
            COLOR_SURFACE_ACTIVE = getColor(darkTheme ? android.R.color.system_accent1_900
                    : android.R.color.system_accent1_50);
        }
    }
    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        statusBanner = text("", 14, COLOR_TEXT);
        statusBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        statusBanner.setVisibility(View.GONE);
        root.addView(statusBanner, matchWrap());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout navigationHolder = new FrameLayout(this);
        navigationHolder.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(8), dp(2), dp(8), dp(3));
        appsNav = navigationItem(R.drawable.nav_apps_outlined, "Apps", () -> showAppsPage());
        settingsNav = navigationItem(R.drawable.nav_settings_outlined, "Settings", () -> showSettingsPage());
        navigation.addView(appsNav, new LinearLayout.LayoutParams(0, dp(52), 1));
        navigation.addView(new Space(this), new LinearLayout.LayoutParams(dp(104), dp(52)));
        navigation.addView(settingsNav, new LinearLayout.LayoutParams(0, dp(52), 1));
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM);
        navigationHolder.addView(navigation, navigationParams);

        addFab = new Button(this);
        addFab.setText("+");
        addFab.setTextSize(30);
        addFab.setTextColor(darkTheme ? Color.rgb(15, 35, 44) : Color.WHITE);
        addFab.setContentDescription("Add Linux app");
        addFab.setPadding(0, 0, 0, dp(3));
        addFab.setMinHeight(0);
        addFab.setMinWidth(0);
        addFab.setStateListAnimator(null);
        addFab.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x44ffffff), oval(COLOR_PRIMARY), null));
        addFab.setOnClickListener(view -> showPackageSearchPage());
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        navigationHolder.addView(addFab, addParams);
        root.addView(navigationHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        setContentView(root);
    }

    private void showAppsPage() {
        currentPage = 0;
        setAddVisible(true);
        setNavigationSelection(true);
        content.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();

        TextView title = text("Apps", 26, COLOR_TEXT);
        page.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(12), 0, dp(4), 0);
        searchRow.setBackground(rounded(COLOR_SURFACE, 8));
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchRow.addView(searchIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setText(query);
        search.setTextSize(16);
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_MUTED);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                renderAppList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        filter = actionButton("", android.R.drawable.ic_menu_sort_by_size);
        filter.setContentDescription("Filter and sort apps");
        filter.setOnClickListener(view -> showFilterSortDialog());
        searchRow.addView(filter, new LinearLayout.LayoutParams(dp(52), dp(44)));
        page.addView(searchRow, matchWrap());

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, 0, 0, dp(20));
        scroll.addView(appList);
        pullToRefresh = new PullToRefreshLayout(this);
        pullToRefresh.setIndicatorColor(COLOR_PRIMARY);
        pullToRefresh.setOnRefreshListener(this::checkAll);
        pullToRefresh.addView(scroll, 0, frameMatch());
        page.addView(pullToRefresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(page, frameMatch());
        loadCatalog();
    }

    private void loadCatalog() {
        try {
            apps = InstalledLinuxAppCatalog.query(this);
            for (InstalledLinuxAppCatalog.Entry app : apps) {
                ManagerStateStore.reconcileInstalledVersion(this, app.packageName,
                        app.sourceVersion);
            }
            sortApps();
            renderAppList();
        } catch (Exception e) {
            apps = new ArrayList<>();
            showBanner("Could not query installed Linux apps: " + e.getMessage(), true);
            renderAppList();
        }
    }

    private void sortApps() {
        final String mode = ManagerStateStore.sortMode(this);
        final boolean ascending = ManagerStateStore.sortAscending(this);
        Collections.sort(apps, new Comparator<InstalledLinuxAppCatalog.Entry>() {
            @Override
            public int compare(InstalledLinuxAppCatalog.Entry left,
                    InstalledLinuxAppCatalog.Entry right) {
                boolean leftUpdate = ManagerStateStore.read(MainActivity.this,
                        left.packageName).updateAvailable;
                boolean rightUpdate = ManagerStateStore.read(MainActivity.this,
                        right.packageName).updateAvailable;
                int result;
                if ("source".equals(mode)) {
                    result = left.sourceId.compareToIgnoreCase(right.sourceId);
                } else if ("update".equals(mode) && leftUpdate != rightUpdate) {
                    result = leftUpdate ? -1 : 1;
                } else {
                    result = left.label.compareToIgnoreCase(right.label);
                }
                if (result == 0) result = left.label.compareToIgnoreCase(right.label);
                return ascending ? result : -result;
            }
        });
    }

    private boolean requiresWrapperSignerMigration(
            InstalledLinuxAppCatalog.Entry app) {
        if (!app.managedKind.isEmpty() || getPackageName().equals(app.packageName)) return false;
        try {
            return !ArchWrapperSigner.signerSha256().equalsIgnoreCase(
                    ApkUpdateInstaller.installedSignerSha256(this, app.packageName));
        } catch (Exception error) {
            android.util.Log.e("ArchpheneManager",
                    "Could not verify wrapper signer for " + app.packageName, error);
            return true;
        }
    }

    private void renderAppList() {
        if (appList == null) return;
        appList.removeAllViews();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        String listFilter = ManagerStateStore.listFilter(this);
        Set<String> installedSources = new HashSet<>();
        ArrayList<InstalledLinuxAppCatalog.Entry> visibleApps = new ArrayList<>(apps);
        if (!normalized.isEmpty()) {
            Collections.sort(visibleApps, Comparator
                    .comparingInt((InstalledLinuxAppCatalog.Entry app) -> SearchRanking.score(
                            normalized, app.label, app.sourceId, app.packageName))
                    .thenComparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
        }
        for (InstalledLinuxAppCatalog.Entry app : visibleApps) {
            ManagerStateStore.Snapshot state = ManagerStateStore.read(this, app.packageName);
            boolean matches = normalized.isEmpty() || SearchRanking.score(normalized,
                    app.label, app.sourceId, app.packageName) != SearchRanking.NO_MATCH;
            installedSources.add(InstalledLinuxAppCatalog.sourceKey(
                    app.sourceType, app.sourceId, app.runtimeAbi));
            boolean hiddenByFilter = "updates".equals(listFilter) && !state.updateAvailable
                    || "pinned".equals(listFilter) && !ManagerStateStore.isPinned(this, app.packageName);
            if (!matches || hiddenByFilter) continue;
            appList.addView(createAppRow(app, state), spacedWrap(dp(6)));
            shown++;
        }
        for (ArchPackageRepository.PackageResult tracked : TrackedPackageStore.list(this)) {
            if (installedSources.contains(InstalledLinuxAppCatalog.pacmanSourceKey(
                    tracked.repository, tracked.name, tracked.architecture))) continue;
            boolean matches = normalized.isEmpty() || SearchRanking.score(normalized,
                    tracked.name, tracked.repository, tracked.description)
                    != SearchRanking.NO_MATCH;
            String stateKey = "tracked:" + tracked.name + ":" + tracked.architecture;
            boolean hiddenByFilter = "updates".equals(listFilter)
                    || "pinned".equals(listFilter) && !ManagerStateStore.isPinned(this, stateKey);
            if (!matches || hiddenByFilter) continue;
            appList.addView(createTrackedRow(tracked), spacedWrap(dp(6)));
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(apps.isEmpty() && TrackedPackageStore.list(this).isEmpty()
                    ? "No Linux apps added."
                    : "updates".equals(listFilter) ? "No updates available."
                    : "pinned".equals(listFilter) ? "No pinned versions."
                    : "No apps match your search.", 16, COLOR_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_menu_info_details, 0, 0);
            empty.setCompoundDrawablePadding(dp(16));
            empty.setPadding(dp(24), dp(64), dp(24), dp(40));
            appList.addView(empty, matchWrap());
        }
        updateNavigationBadge();
    }

    private View createAppRow(InstalledLinuxAppCatalog.Entry app,
            ManagerStateStore.Snapshot state) {
        String jobId = PackageInstallJobStore.key(app);
        PackageInstallJobStore.Snapshot job = jobId.isEmpty()
                ? null : PackageInstallJobStore.read(this, jobId);
        boolean packageInstalling = job != null && job.active();
        boolean artifactInstalling = app.packageName.equals(activeInstallPackage)
                && activeInstallOperation != null;
        boolean installing = packageInstalling || artifactInstalling;
        boolean signerMigration = requiresWrapperSignerMigration(app);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        card.setBackground(rounded(COLOR_SURFACE, 8));
        card.setOnClickListener(view -> showAppDetail(app));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(app.packageName));
        } catch (Exception ignored) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        top.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(app.label, 15, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        details.addView(name, matchWrap());
        details.addView(text(app.sourceType + " | " + app.sourceId,
                11, COLOR_MUTED), matchWrap());
        String pinnedVersion = ManagerStateStore.pinnedVersion(this, app.packageName);
        details.addView(text(app.runtimeAbi, 10, COLOR_MUTED), matchWrap());
        top.addView(details, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (installing) {
            ApkUpdateInstaller.Phase phase = packageInstalling ? job.phase : activeInstallPhase;
            int percent = packageInstalling ? job.percent : activeInstallPercent;
            String label = packageInstalling ? compactPackageOperationLabel(job)
                    : phase == ApkUpdateInstaller.Phase.DOWNLOAD
                    ? "Downloading " + percent + "%" : "Installing...";
            ApkUpdateInstaller.Operation operation = packageInstalling
                    ? PackageInstallCoordinator.operation(jobId) : activeInstallOperation;
            Runnable cancel = operation != null && operation.canCancel() ? () -> {
                if (packageInstalling) PackageInstallCoordinator.cancel(jobId);
                else if (activeInstallOperation != null) activeInstallOperation.cancel();
            } : null;
            View action = installingAction(label, cancel);
            top.addView(action, new LinearLayout.LayoutParams(dp(126), dp(42)));
        } else if (job != null && job.retryable()) {
            Button retry = actionButton("Retry", android.R.drawable.stat_notify_error);
            retry.setTextColor(COLOR_ERROR);
            retry.setContentDescription("Retry " + app.label + ". " + job.status
                    + (job.error.isEmpty() ? "" : ". " + job.error));
            retry.setOnClickListener(view -> startOnDevicePackageInstall(packageSource(app)));
            top.addView(retry, new LinearLayout.LayoutParams(dp(126), dp(42)));
        } else if (signerMigration) {
            Button replace = actionButton("Replace", android.R.drawable.stat_notify_error);
            replace.setContentDescription("Replace " + app.label
                    + " wrapper signed by an unavailable Archphene key");
            replace.setOnClickListener(view -> installSelectedVersion(
                    app, app.sourceVersion));
            styleMigrationButton(replace);
            top.addView(replace, new LinearLayout.LayoutParams(dp(126), dp(42)));
        } else {
            Button version = actionButton(pinnedVersion.isEmpty()
                    ? versionButtonText(app, state) : pinnedVersion,
                    pinnedVersion.isEmpty() ? versionButtonIcon(state)
                            : R.drawable.version_pinned);
            version.setAllCaps(false);
            version.setContentDescription(pinnedVersion.isEmpty()
                    ? versionButtonDescription(app, state)
                    : pinnedVersionDescription(app, state, pinnedVersion));
            version.setEnabled(!app.updateUrl.isEmpty() && !"checking".equals(state.status));
            version.setOnClickListener(view -> activateVersionButton(app, state, pinnedVersion));
            if (pinnedVersion.isEmpty()) styleVersionButton(version, state);
            else stylePinnedVersionButton(version);
            top.addView(version, new LinearLayout.LayoutParams(dp(126), dp(42)));
        }
        card.addView(top, matchWrap());
        if (installing) {
            ApkUpdateInstaller.Phase phase = packageInstalling ? job.phase : activeInstallPhase;
            int percent = packageInstalling ? job.percent : activeInstallPercent;
            String status = packageInstalling ? job.status : activeInstallStatus;
            TwoStageProgressView progress = new TwoStageProgressView(this,
                    COLOR_PRIMARY, COLOR_MUTED);
            progress.setState(phase, percent, status);
            card.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        } else if (job != null && job.retryable()) {
            TextView error = text(job.error.isEmpty() ? job.status : job.error, 11, COLOR_ERROR);
            error.setMaxLines(2);
            card.addView(error, spacedWrap(dp(4)));
        }

        return card;
    }

    private ArchPackageRepository.PackageResult packageSource(
            InstalledLinuxAppCatalog.Entry app) {
        int separator = app.sourceId.indexOf('/');
        if (separator <= 0 || separator == app.sourceId.length() - 1
                || !app.runtimeAbi.toLowerCase(Locale.ROOT).startsWith("glibc-")) {
            throw new IllegalArgumentException("Installed app has no pacman source identity");
        }
        return new ArchPackageRepository.PackageResult(
                app.sourceId.substring(separator + 1), app.sourceId.substring(0, separator),
                app.runtimeAbi.substring(6), app.sourceVersion, "", false,
                "usr/bin/" + app.executable, app.executable);
    }
    private View createTrackedRow(ArchPackageRepository.PackageResult app) {
        String stateKey = "tracked:" + app.name + ":" + app.architecture;
        String jobId = PackageInstallJobStore.key(app);
        PackageInstallJobStore.Snapshot job = PackageInstallJobStore.read(this, jobId);
        boolean artifactInstalling = stateKey.equals(activeInstallPackage)
                && activeInstallOperation != null;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        card.setBackground(rounded(COLOR_SURFACE, 8));
        card.setOnClickListener(view -> showPackageResultDetail(app, true));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        top.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(app.name, 15, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        details.addView(name, matchWrap());
        details.addView(text(app.repository + " | " + app.architecture, 11, COLOR_MUTED), matchWrap());
        String pinned = ManagerStateStore.pinnedVersion(this, stateKey);
        details.addView(text("Not installed", 10, COLOR_MUTED), matchWrap());
        top.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (job.active() || artifactInstalling) {
            String label = artifactInstalling
                    ? activeInstallPhase == ApkUpdateInstaller.Phase.DOWNLOAD
                    ? "Downloading " + activeInstallPercent + "%" : "Installing..."
                    : PackageInstallJobStore.QUEUED.equals(job.state) ? "Queued"
                    : job.phase == ApkUpdateInstaller.Phase.DOWNLOAD
                    ? "Preparing " + job.percent + "%" : "Installing...";
            ApkUpdateInstaller.Operation operation = artifactInstalling
                    ? activeInstallOperation : PackageInstallCoordinator.operation(jobId);
            Runnable cancel = operation != null && operation.canCancel() ? () -> {
                if (artifactInstalling) operation.cancel();
                else PackageInstallCoordinator.cancel(jobId);
            } : null;
            View action = installingAction(label, cancel);
            top.addView(action, new LinearLayout.LayoutParams(dp(126), dp(42)));
        } else {
            String label = PackageInstallJobStore.ERROR.equals(job.state) ? "Failed"
                    : PackageInstallJobStore.CANCELLED.equals(job.state) ? "Cancelled"
                    : pinned.isEmpty() ? app.version : pinned;
            int iconId = job.retryable() ? android.R.drawable.stat_notify_error
                    : pinned.isEmpty() ? android.R.drawable.stat_sys_download_done
                    : R.drawable.version_pinned;
            Button version = actionButton(label, iconId);
            if (job.retryable()) {
                version.setTextColor(COLOR_ERROR);
                version.setContentDescription("Retry " + app.name + ". " + job.status
                        + (job.error.isEmpty() ? "" : ". " + job.error));
                version.setOnClickListener(view -> startOnDevicePackageInstall(app));
            } else {
                version.setContentDescription(pinned.isEmpty()
                        ? "Install " + app.name + " " + app.version
                        : "Install " + app.name + " pinned to " + pinned);
                version.setOnClickListener(view -> startOnDevicePackageInstall(app));
                if (!pinned.isEmpty()) stylePinnedVersionButton(version);
            }
            top.addView(version, new LinearLayout.LayoutParams(dp(126), dp(42)));
        }
        card.addView(top, matchWrap());
        if (job.active() || artifactInstalling) {
            TwoStageProgressView progress = new TwoStageProgressView(this,
                    COLOR_PRIMARY, COLOR_MUTED);
            progress.setState(artifactInstalling ? activeInstallPhase : job.phase,
                    artifactInstalling ? activeInstallPercent : job.percent,
                    artifactInstalling ? activeInstallStatus : job.status);
            card.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        } else if (job.retryable()) {
            String failure = job.error.isEmpty() ? job.status : job.error;
            TextView error = text(failure, 11, COLOR_ERROR);
            error.setMaxLines(2);
            card.addView(error, spacedWrap(dp(4)));
        }
        return card;
    }
    private void showFilterSortDialog() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(8), dp(20), 0);
        controls.addView(text("Show", 13, COLOR_MUTED), matchWrap());
        Spinner filterChoice = new Spinner(this);
        String[] filterLabels = {"All apps", "Updates available", "Pinned versions"};
        String[] filterValues = {"all", "updates", "pinned"};
        filterChoice.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, filterLabels));
        String currentFilter = ManagerStateStore.listFilter(this);
        for (int i = 0; i < filterValues.length; i++) {
            if (filterValues[i].equals(currentFilter)) filterChoice.setSelection(i);
        }
        controls.addView(filterChoice, matchWrap());
        controls.addView(text("Sort by", 13, COLOR_MUTED), spacedWrap(dp(12)));
        Spinner sortChoice = new Spinner(this);
        String[] sortLabels = {"Name", "Package source", "Update status"};
        String[] sortValues = {"name", "source", "update"};
        sortChoice.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, sortLabels));
        String currentSort = ManagerStateStore.sortMode(this);
        for (int i = 0; i < sortValues.length; i++) {
            if (sortValues[i].equals(currentSort)) sortChoice.setSelection(i);
        }
        controls.addView(sortChoice, matchWrap());
        Switch ascending = new Switch(this);
        ascending.setText("Ascending");
        ascending.setTextColor(COLOR_TEXT);
        ascending.setChecked(ManagerStateStore.sortAscending(this));
        controls.addView(ascending, spacedWrap(dp(10)));

        new AlertDialog.Builder(this)
                .setTitle("Filter and sorting")
                .setView(controls)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    ManagerStateStore.setListFilter(this,
                            filterValues[filterChoice.getSelectedItemPosition()]);
                    ManagerStateStore.setSortMode(this,
                            sortValues[sortChoice.getSelectedItemPosition()]);
                    ManagerStateStore.setSortAscending(this, ascending.isChecked());
                    sortApps();
                    renderAppList();
                })
                .show();
    }

    private void showPackageSearchPage() {
        showPackageSearchPage("");
    }

    private void showPackageSearchPage(String initialQuery) {
        String compatibilityIssue = ArchRuntimePolicy.packageTransactionIssue();
        if (!compatibilityIssue.isEmpty()) {
            showAppsPage();
            showBanner(compatibilityIssue, false);
            return;
        }
        currentPage = 3;
        setAddVisible(false);
        setNavigationSelection(true);
        content.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        Button back = subtleBackButton();
        back.setOnClickListener(view -> showAppsPage());
        page.addView(back, new LinearLayout.LayoutParams(dp(80), dp(38)));
        TextView title = text("Add packages", 26, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title, spacedWrap(dp(8)));

        LinearLayout searchControls = new LinearLayout(this);
        searchControls.setGravity(Gravity.CENTER_VERTICAL);
        EditText packageSearch = new EditText(this);
        packageSearch.setHint("Search official Arch packages");
        packageSearch.setSingleLine(true);
        packageSearch.setTextColor(COLOR_TEXT);
        packageSearch.setHintTextColor(COLOR_MUTED);
        searchControls.addView(packageSearch, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button submit = actionButton("", android.R.drawable.ic_menu_search);
        submit.setContentDescription("Search package repositories");
        searchControls.addView(submit, new LinearLayout.LayoutParams(dp(52), dp(44)));
        page.addView(searchControls, spacedWrap(dp(8)));

        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        page.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        submit.setOnClickListener(view -> searchPackages(packageSearch.getText().toString(),
                results, progress));
        packageSearch.setOnEditorActionListener((view, action, event) -> {
            searchPackages(packageSearch.getText().toString(), results, progress);
            return true;
        });
        content.addView(page, frameMatch());
        if (initialQuery != null && !initialQuery.trim().isEmpty()) {
            packageSearch.setText(initialQuery.trim());
            packageSearch.setSelection(packageSearch.length());
            packageSearch.post(() -> searchPackages(packageSearch.getText().toString(),
                    results, progress));
        }
    }

    private void searchPackages(String query, LinearLayout results, ProgressBar progress) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            Toast.makeText(this, "Enter at least two characters", Toast.LENGTH_SHORT).show();
            return;
        }
        int generation = packageSearchGeneration.incrementAndGet();
        progress.setVisibility(View.VISIBLE);
        results.removeAllViews();
        TextView loading = text("Searching repositories...", 14, COLOR_MUTED);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(40), 0, 0);
        results.addView(loading, matchWrap());
        new Thread(() -> {
            try {
                List<ArchPackageRepository.PackageResult> found =
                        ArchPackageRepository.search(this, normalized);
                runOnUiThread(() -> {
                    if (generation != packageSearchGeneration.get()) return;
                    progress.setVisibility(View.GONE);
                    results.removeAllViews();
                    for (ArchPackageRepository.PackageResult app : found) {
                        results.addView(createSearchResultRow(app), spacedWrap(dp(6)));
                    }
                    if (found.isEmpty()) {
                        TextView empty = text("No packages found", 15, COLOR_MUTED);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(40), 0, 0);
                        results.addView(empty, matchWrap());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (generation != packageSearchGeneration.get()) return;
                    progress.setVisibility(View.GONE);
                    results.removeAllViews();
                    TextView error = text("Repository search failed", 15, COLOR_ERROR);
                    error.setGravity(Gravity.CENTER);
                    error.setPadding(0, dp(40), 0, dp(8));
                    results.addView(error, matchWrap());
                    Button retry = actionButton("Retry", android.R.drawable.ic_popup_sync);
                    retry.setOnClickListener(view -> searchPackages(normalized,
                            results, progress));
                    results.addView(retry, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
                    showBanner("Repository search failed: " + e.getMessage(), true);
                });
            }
        }, "arch-package-search").start();
    }

    private View createSearchResultRow(ArchPackageRepository.PackageResult app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(rounded(COLOR_SURFACE, 8));
        TextView name = text(app.name + "  " + app.version, 15, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(name, matchWrap());
        row.addView(text(app.repository + " | " + app.architecture, 11, COLOR_PRIMARY), matchWrap());
        if (!app.description.isEmpty()) row.addView(text(app.description, 12, COLOR_MUTED), matchWrap());
        if (!app.matchedFile.isEmpty()) {
            row.addView(text("Matched file: /" + app.matchedFile, 11, COLOR_MUTED), matchWrap());
        }
        if (app.flaggedOutOfDate) row.addView(text("Flagged out of date", 11, COLOR_WARNING), matchWrap());
        row.setOnClickListener(view -> showPackageResultDetail(app, false));
        return row;
    }

    private void showPackageResultDetail(ArchPackageRepository.PackageResult app, boolean tracked) {
        currentPage = 4;
        String jobId = PackageInstallJobStore.key(app);
        PackageInstallJobStore.Snapshot job = PackageInstallJobStore.read(this, jobId);
        setAddVisible(false);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(24));
        Button back = subtleBackButton();
        back.setOnClickListener(view -> {
            if (tracked) showAppsPage(); else showPackageSearchPage();
        });
        page.addView(back, new LinearLayout.LayoutParams(dp(80), dp(38)));
        TextView title = text(app.name, 24, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title, spacedWrap(dp(12)));
        LinearLayout details = verticalSection();
        details.addView(detailLine("Version", app.version));
        details.addView(detailLine("Repository", app.repository));
        details.addView(detailLine("Architecture", app.architecture));
        details.addView(detailLine("Arch status", app.flaggedOutOfDate ? "Flagged out of date" : "Current"));
        TextView wrapperAvailability = text("Wrapper: built and signed on this device",
                12, COLOR_MUTED);
        details.addView(wrapperAvailability, spacedWrap(dp(8)));
        page.addView(details, spacedWrap(dp(10)));
        if (!app.description.isEmpty()) {
            TextView description = text(app.description, 14, COLOR_MUTED);
            description.setPadding(dp(8), dp(12), dp(8), dp(12));
            page.addView(description, matchWrap());
        }
        Button action = actionButton(tracked ? "Remove from apps" : "Add to apps",
                tracked ? android.R.drawable.ic_menu_delete : android.R.drawable.ic_input_add);
        action.setOnClickListener(view -> {
            if (tracked) TrackedPackageStore.remove(this, app.repository, app.name,
                    app.architecture);
            else TrackedPackageStore.add(this, app);
            showAppsPage();
        });
        page.addView(action, spacedWrap(dp(12)));
        if (!PackageInstallJobStore.IDLE.equals(job.state)
                && !PackageInstallJobStore.COMPLETE.equals(job.state)) {
            LinearLayout jobDetails = verticalSection();
            jobDetails.addView(detailLine("Install state", job.state.replace('_', ' ')));
            jobDetails.addView(detailLine("Current phase", job.status));
            if (!job.error.isEmpty()) {
                TextView error = text(job.error, 12, COLOR_ERROR);
                jobDetails.addView(error, spacedWrap(dp(6)));
            }
            addPackageDiagnostics(jobDetails, job);
            if (job.active()) {
                TwoStageProgressView progress = new TwoStageProgressView(this,
                        COLOR_PRIMARY, COLOR_MUTED);
                progress.setState(job.phase, job.percent, job.status);
                jobDetails.addView(progress, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

            }
            page.addView(jobDetails, spacedWrap(dp(10)));
        }
        boolean supported = ArchRuntimePolicy.supports(app.architecture);
        if (job.active()) {
            ApkUpdateInstaller.Operation operation =
                    PackageInstallCoordinator.operation(jobId);
            Runnable cancel = operation != null && operation.canCancel()
                    ? () -> PackageInstallCoordinator.cancel(jobId) : null;
            page.addView(installingAction(packageOperationLabel(job), cancel),
                    spacedWrap(dp(8)));
        } else {
            String installLabel = job.retryable() ? "Retry install" : "Install";
            Button install = actionButton(installLabel,
                    job.retryable() ? android.R.drawable.stat_notify_error
                            : android.R.drawable.stat_sys_download_done);
            install.setEnabled(supported);
            install.setContentDescription(supported
                    ? "Resolve, verify, build, and install " + app.name
                    : app.name + " is not available for this device architecture");
            install.setOnClickListener(view -> startOnDevicePackageInstall(app));
            page.addView(install, spacedWrap(dp(8)));
        }
        scroll.addView(page);
        content.addView(scroll, frameMatch());
    }
    private void startTrackedArtifactInstall(ArchPackageRepository.PackageResult source,
            WrapperRepositoryClient.Artifact artifact) {
        TrackedPackageStore.add(this, source);
        showAppsPage();
        activeInstallPackage = "tracked:" + source.name + ":" + source.architecture;
        activeInstallOperation = ApkUpdateInstaller.installWithProgress(this,
                artifact.apkUrl, artifact.sha256, artifact.packageName,
                artifact.signerSha256, (phase, percent, status, terminal) -> {
                    activeInstallPhase = phase;
                    activeInstallPercent = percent;
                    activeInstallStatus = status;
                    if (terminal) {
                        activeInstallOperation = null;
                        activeInstallPackage = "";
                        showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                        loadCatalog();
                    } else {
                        renderProgressAtStableCadence(() -> {
                            if (currentPage == 0) renderAppList();
                        });
                    }
                });
        renderAppList();
    }
    private void requestWrapperSignerMigration(ArchPackageRepository.PackageResult source,
            String packageName) {
        new AlertDialog.Builder(this)
                .setTitle("Replace older wrapper?")
                .setMessage("Android cannot update this app because it was signed by an older "
                        + "Archphene prototype. Replacing it removes app-private settings and "
                        + "cache. Files saved through Android documents remain available.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace", (dialog, which) -> {
                    ManagerStateStore.setPendingUninstallPackage(this, packageName);
                    ManagerStateStore.setPendingReinstall(this, source);
                    Intent request = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                            Uri.parse("package:" + packageName));
                    request.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    showAppsPage();
                    startActivityForResult(request, REQUEST_UNINSTALL_LINUX_APP);
                })
                .show();
    }
    private void startOnDevicePackageInstall(ArchPackageRepository.PackageResult source) {
        startOnDevicePackageInstall(source, null);
    }

    private void startOnDevicePackageInstall(ArchPackageRepository.PackageResult source,
            String terminalRequestId) {
        String compatibilityIssue = ArchRuntimePolicy.packageTransactionIssue();
        if (!compatibilityIssue.isEmpty()) {
            showBanner(compatibilityIssue, false);
            if (terminalRequestId != null) {
                TerminalCommandReporter.report(this, terminalRequestId, "preflight", 0,
                        true, "error", compatibilityIssue);
            }
            return;
        }
        try {
            InstalledLinuxAppCatalog.Entry installed = InstalledLinuxAppCatalog.findBySource(this,
                    source.repository, source.name, source.architecture);
            String generatedPackage = ArchWrapperAssembler.packageNameFor(
                    source.repository, source.name);
            if (installed != null && installed.managedKind.isEmpty()
                    && !generatedPackage.equals(installed.packageName)) {
                String message = source.name + " is already installed as " + installed.packageName
                        + ". Uninstall that legacy wrapper before installing it again.";
                showBanner(message, true);
                if (terminalRequestId != null) {
                    TerminalCommandReporter.report(this, terminalRequestId, "preflight", 0,
                            true, "error", message);
                }
                return;
            }
            if (installed != null && installed.managedKind.isEmpty()
                    && !ArchWrapperSigner.signerSha256().equalsIgnoreCase(
                    ApkUpdateInstaller.installedSignerSha256(this, installed.packageName))) {
                requestWrapperSignerMigration(source, installed.packageName);
                if (terminalRequestId != null) {
                    TerminalCommandReporter.report(this, terminalRequestId, "preflight", 0,
                            true, "error",
                            "Legacy wrapper migration requires confirmation in Archphene");
                }
                return;
            }
        } catch (Exception error) {
            String message = "Could not verify existing installs: " + safeMessage(error);
            showBanner(message, true);
            if (terminalRequestId != null) {
                TerminalCommandReporter.report(this, terminalRequestId, "preflight", 0,
                        true, "error", message);
            }
            return;
        }
        TrackedPackageStore.add(this, source);
        boolean started = PackageInstallCoordinator.start(this, source, terminalRequestId, (state, terminal) -> {
            if (terminal) {
                boolean failed = PackageInstallJobStore.ERROR.equals(state.state);
                String detail = state.error.isEmpty() ? state.status
                        : state.status + ": " + state.error;
                if (currentPage == 2 && state.id.equals(packageJobDetailId)) {
                    renderPackageJobDetail();
                }
                showBanner(detail, failed);
                loadCatalog();
            } else {
                renderPackageProgress(source, state);
            }
        });
        if (!started) {
            showBanner(source.name + " already has an active install job", false);
            if (terminalRequestId != null) {
                TerminalCommandReporter.report(this, terminalRequestId, "queue", 0,
                        true, "error", source.name + " already has an active install job");
            }
        }
        showAppsPage();
    }
    private void renderPackageProgress(ArchPackageRepository.PackageResult source,
            PackageInstallJobStore.Snapshot state) {
        renderProgressAtStableCadence(() -> {
            if (currentPage == 0) {
                renderAppList();
            } else if (currentPage == 2 && state.id.equals(packageJobDetailId)) {
                renderPackageJobDetail();
            } else if (currentPage == 4) {
                showPackageResultDetail(source, true);
            }
        });
    }

    private void renderProgressAtStableCadence(Runnable render) {
        long now = SystemClock.uptimeMillis();
        long delay = nextProgressRenderAt - now;
        if (delay <= 0) {
            nextProgressRenderAt = now + PROGRESS_RENDER_INTERVAL_MILLIS;
            render.run();
            return;
        }
        if (progressRenderPending) return;
        progressRenderPending = true;
        content.postDelayed(() -> {
            progressRenderPending = false;
            nextProgressRenderAt = SystemClock.uptimeMillis()
                    + PROGRESS_RENDER_INTERVAL_MILLIS;
            render.run();
        }, delay);
    }

    private View installingAction(String label, Runnable cancel) {
        LinearLayout action = new LinearLayout(this);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(8), 0, dp(8), 0);
        action.setBackground(new RippleDrawable(
                ColorStateList.valueOf((COLOR_PRIMARY & 0x00ffffff) | 0x33000000),
                rounded(COLOR_SURFACE_ACTIVE, 18), null));
        ProgressBar spinner = new ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        action.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView status = text(label, 11, COLOR_PRIMARY);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(6);
        action.addView(status, textParams);
        boolean canCancel = cancel != null;
        action.setClickable(canCancel);
        action.setFocusable(canCancel);
        if (canCancel) action.setOnClickListener(view -> cancel.run());
        action.setContentDescription(label + (canCancel ? ". Tap to cancel" : ""));
        return action;
    }

    private String packageOperationLabel(PackageInstallJobStore.Snapshot job) {
        if (PackageInstallJobStore.QUEUED.equals(job.state)) return "Queued";
        String status = job.status == null ? "" : job.status.trim();
        if (!status.isEmpty()) {
            return job.percent > 0 && !status.contains("%")
                    ? status + " " + job.percent + "%" : status;
        }
        return job.phase == ApkUpdateInstaller.Phase.DOWNLOAD
                ? "Preparing " + job.percent + "%" : "Installing...";
    }

    private String compactPackageOperationLabel(PackageInstallJobStore.Snapshot job) {
        if (PackageInstallJobStore.QUEUED.equals(job.state)) return "Queued";
        String status = job.status == null ? "" : job.status.trim();
        int separator = status.indexOf(' ');
        String stage = status.isEmpty() ? "Preparing"
                : status.substring(0, separator < 0 ? status.length() : separator);
        return job.percent > 0 ? stage + " " + job.percent + "%" : stage;
    }

    private String pinnedVersionDescription(InstalledLinuxAppCatalog.Entry app,
            ManagerStateStore.Snapshot state, String pinnedVersion) {
        String description = "Pinned to " + pinnedVersion + ". " + app.runtimeAbi;
        if ("bad".equals(ManagerStateStore.versionHealth(this, app.packageName,
                pinnedVersion))) {
            description += ". Reported bad";
        }
        if ("update".equals(state.status) && !state.availableVersion.isEmpty()
                && !pinnedVersion.equals(state.availableVersion)) {
            description += ". Newer version " + state.availableVersion + " available";
        }
        return description + ". Check for updates";
    }

    private void styleMigrationButton(Button button) {
        int foreground = COLOR_WARNING;
        int fill = darkTheme ? Color.rgb(66, 45, 22) : Color.rgb(255, 241, 216);
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((foreground & 0x00ffffff) | 0x33000000),
                rounded(fill, 18), null));
    }

    private void stylePinnedVersionButton(Button button) {
        int foreground = COLOR_WARNING;
        int fill = darkTheme ? Color.rgb(66, 45, 22) : Color.rgb(255, 241, 216);
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((foreground & 0x00ffffff) | 0x33000000),
                rounded(fill, 18), null));
    }

    private String versionButtonText(InstalledLinuxAppCatalog.Entry app,
            ManagerStateStore.Snapshot state) {
        if ("checking".equals(state.status)) return "Checking";
        return state.availableVersion.isEmpty() ? app.sourceVersion : state.availableVersion;
    }

    private int versionButtonIcon(ManagerStateStore.Snapshot state) {
        switch (state.status) {
            case "current": return R.drawable.version_current;
            case "update": return android.R.drawable.stat_sys_download_done;
            case "error": return android.R.drawable.stat_notify_error;
            default: return android.R.drawable.ic_popup_sync;
        }
    }

    private String versionButtonDescription(InstalledLinuxAppCatalog.Entry app,
            ManagerStateStore.Snapshot state) {
        switch (state.status) {
            case "checking": return "Checking " + app.label + " for updates";
            case "current": return app.label + " " + versionButtonText(app, state)
                    + " is up to date. Check again";
            case "update": return app.label + " update " + versionButtonText(app, state)
                    + " available. Installed " + app.sourceVersion;
            case "error": return app.label + " update check failed. Installed "
                    + app.sourceVersion + ". Check again";
            default: return "Check " + app.label + " for updates. Installed "
                    + app.sourceVersion + ". Not checked";
        }
    }

    private void styleVersionButton(Button button, ManagerStateStore.Snapshot state) {
        int foreground;
        int fill;
        switch (state.status) {
            case "current":
                foreground = COLOR_SUCCESS;
                fill = darkTheme ? Color.rgb(25, 55, 41) : Color.rgb(229, 245, 235);
                break;
            case "update":
                foreground = COLOR_WARNING;
                fill = darkTheme ? Color.rgb(66, 45, 22) : Color.rgb(255, 241, 216);
                break;
            case "error":
                foreground = COLOR_ERROR;
                fill = darkTheme ? Color.rgb(68, 31, 36) : Color.rgb(252, 231, 233);
                break;
            case "checking":
                foreground = COLOR_PRIMARY;
                fill = COLOR_SURFACE_ACTIVE;
                break;
            default:
                foreground = COLOR_MUTED;
                fill = darkTheme ? Color.rgb(41, 47, 52) : Color.WHITE;
        }
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((foreground & 0x00ffffff) | 0x33000000),
                rounded(fill, 18), null));
    }

    private void activateVersionButton(InstalledLinuxAppCatalog.Entry app,
            ManagerStateStore.Snapshot state, String pinnedVersion) {
        if (shouldInstallFromVersionButton(state, pinnedVersion)) {
            if ("pacman".equals(app.sourceType)) {
                startOnDevicePackageInstall(packageSource(app));
            } else {
                installSelectedVersion(app, state.availableVersion);
            }
            return;
        }
        checkOne(app);
    }

    static boolean shouldInstallFromVersionButton(ManagerStateStore.Snapshot state,
            String pinnedVersion) {
        return pinnedVersion.isEmpty() && state.updateAvailable
                && "update".equals(state.status) && !state.availableVersion.isEmpty();
    }

    static void verifyVersionButtonPolicyForTest() {
        ManagerStateStore.Snapshot update = new ManagerStateStore.Snapshot(
                "2.0-1", "update", "", 1L, true);
        ManagerStateStore.Snapshot current = new ManagerStateStore.Snapshot(
                "1.0-1", "current", "", 1L, false);
        if (!shouldInstallFromVersionButton(update, "")
                || shouldInstallFromVersionButton(update, "1.0-1")
                || shouldInstallFromVersionButton(current, "")) {
            throw new SecurityException("Version button action policy mismatch");
        }
    }

    private void checkOne(InstalledLinuxAppCatalog.Entry app) {
        LinuxAppUpdateCoordinator.checkOne(this, app, (updated, state, completed, total) -> {
            renderAppList();
            if (completed == total && "error".equals(state.status)) {
                showBanner(statusText(state), true);
            }
        });
    }

    private void checkAll() {
        if (apps.isEmpty()) {
            if (pullToRefresh != null) pullToRefresh.setRefreshing(false);
            return;
        }
        if (pullToRefresh != null) pullToRefresh.setRefreshing(true);
        LinuxAppUpdateCoordinator.checkAll(this, apps, (app, state, completed, total) -> {
            renderAppList();
            if (completed == total) {
                if (pullToRefresh != null) pullToRefresh.setRefreshing(false);
            }
        });
    }

    private void showAppDetail(InstalledLinuxAppCatalog.Entry app) {
        boolean managed = !app.managedKind.isEmpty();
        boolean signerMigration = requiresWrapperSignerMigration(app);
        currentPage = 2;
        setAddVisible(false);
        content.removeAllViews();
        ManagerStateStore.Snapshot state = ManagerStateStore.read(this, app.packageName);
        String packageJobId = PackageInstallJobStore.key(app);
        PackageInstallJobStore.Snapshot packageJob = packageJobId.isEmpty()
                ? null : PackageInstallJobStore.read(this, packageJobId);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(24));

        Button back = subtleBackButton();
        back.setOnClickListener(view -> showAppsPage());
        page.addView(back, new LinearLayout.LayoutParams(dp(80), dp(38)));

        LinearLayout identity = section();
        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(app.packageName)); }
        catch (Exception ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
        identity.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(16), 0, 0, 0);
        TextView title = text(app.label, 22, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(title);
        labels.addView(text(app.sourceType + " source", 14, COLOR_MUTED));
        identity.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(identity, spacedWrap(dp(14)));

        LinearLayout versions = verticalSection();
        versions.addView(detailLine("Installed package", app.sourceVersion));
        String availableVersion = state.availableVersion.isEmpty()
                ? "Not checked" : state.availableVersion;
        String pinnedVersion = ManagerStateStore.pinnedVersion(this, app.packageName);
        boolean newerThanPin = !pinnedVersion.isEmpty()
                && !state.availableVersion.isEmpty()
                && !pinnedVersion.equals(state.availableVersion);
        versions.addView(detailLine("Available", availableVersion,
                state.updateAvailable || newerThanPin ? R.drawable.version_newer : 0));
        TextView selectedLabel = text("Install version", 13, COLOR_MUTED);
        versions.addView(selectedLabel, spacedWrap(dp(8)));
        ArrayList<String> versionValues = new ArrayList<>();
        versionValues.add(app.sourceVersion);
        if (!state.availableVersion.isEmpty() && !versionValues.contains(state.availableVersion)) {
            versionValues.add(state.availableVersion);
        }
        Spinner versionSelector = new Spinner(this);
        versionSelector.setContentDescription("Version selector, loading");
        ArrayAdapter<String> versionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, versionValues);
        versionSelector.setAdapter(versionAdapter);
        versions.addView(versionSelector, matchWrap());
        TextView versionHealth = text("", 12, COLOR_MUTED);
        versions.addView(versionHealth, spacedWrap(dp(4)));
        String[] selectedVersion = {app.sourceVersion};
        Switch pin = new Switch(this);
        pin.setText("Pin selected version");
        pin.setTextColor(COLOR_TEXT);
        String pinned = pinnedVersion;
        pin.setChecked(!pinned.isEmpty());
        versions.addView(pin, spacedWrap(dp(8)));
        Button installVersion = actionButton("Install selected version",
                android.R.drawable.stat_sys_download_done);
        ApkUpdateInstaller.Operation packageOperation = packageJob != null && packageJob.active()
                ? PackageInstallCoordinator.operation(packageJobId) : null;
        boolean packageCanCancel = packageOperation != null && packageOperation.canCancel();
        if (packageJob != null && packageJob.active()) {
            installVersion.setText(packageOperationLabel(packageJob));
            installVersion.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0);
            installVersion.setEnabled(packageCanCancel);
            installVersion.setContentDescription(packageOperationLabel(packageJob)
                    + (packageCanCancel ? ". Tap to cancel" : ""));
            if (packageCanCancel) {
                installVersion.setOnClickListener(view ->
                        PackageInstallCoordinator.cancel(packageJobId));
            }
        } else {
            installVersion.setOnClickListener(view ->
                    installSelectedVersion(app, selectedVersion[0]));
        }
        versions.addView(installVersion, spacedWrap(dp(8)));
        versionSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                selectedVersion[0] = versionValues.get(position);
                if (packageJob != null && packageJob.active()) {
                    installVersion.setEnabled(packageCanCancel);
                    installVersion.setText(packageOperationLabel(packageJob));
                    return;
                }
                boolean rollback = managerRollbackUnavailable(app, selectedVersion[0]);
                installVersion.setEnabled(!rollback);
                installVersion.setText(rollback ? "Android rollback unavailable"
                        : signerMigration && selectedVersion[0].equals(app.sourceVersion)
                        ? "Replace older wrapper"
                        : selectedVersion[0].equals(app.sourceVersion)
                        ? "Repair installed version" : "Install selected version");
                versionHealth.setText(versionHealthLabel(app.packageName,
                        selectedVersion[0], app.sourceVersion, state.availableVersion));

            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        pin.setOnCheckedChangeListener((button, checked) -> {
            ManagerStateStore.setPinnedVersion(this, app.packageName,
                    checked ? selectedVersion[0] : "");
        });
        if (!pinned.isEmpty() && versionValues.contains(pinned)) {
            versionSelector.setSelection(versionValues.indexOf(pinned));
        }
        page.addView(versions, spacedWrap(dp(10)));
        populateVersionSelector(app, versionValues, versionAdapter, versionSelector,
                versionHealth, selectedVersion, pinned);
        packageJobDetail = verticalSection();
        packageJobDetailId = packageJobId;
        packageJobDetailApp = app;
        renderPackageJobDetail();
        page.addView(packageJobDetail, spacedWrap(dp(8)));
        LinearLayout source = verticalSection();
        source.addView(detailLine("Package source", app.sourceId));
        source.addView(detailLine("Runtime", app.runtimeAbi));
        if (managed) {
            source.addView(detailLine("Environment", "Archphene Terminal"));
            source.addView(detailLine("Commands", commandSummary(app.commands)));
        } else {
            source.addView(detailLine("Android package", app.packageName));
        }
        page.addView(source, spacedWrap(dp(6)));

        boolean currentAudioOutput = installedBridgeCapability(app.packageName, "audio-output");
        if (!managed && (currentAudioOutput
                || ManagerStateStore.microphoneInputEnabled(this, app.packageName))) {
            LinearLayout permissions = verticalSection();
            permissions.addView(text("Android permissions", 13, COLOR_MUTED));
            Switch microphone = new Switch(this);
            microphone.setText("Microphone input");
            microphone.setTextColor(COLOR_TEXT);
            boolean desiredMicrophone = ManagerStateStore.microphoneInputEnabled(
                    this, app.packageName);
            boolean currentMicrophone = installedBridgeCapability(
                    app.packageName, "audio-input");
            microphone.setChecked(desiredMicrophone);
            permissions.addView(microphone, spacedWrap(dp(8)));
            TextView microphoneStatus = text(currentMicrophone
                    ? "Enabled. Android asks when the Linux app first records."
                    : "Disabled. Enabling rebuilds the wrapper before Android can ask.",
                    12, COLOR_MUTED);
            permissions.addView(microphoneStatus, spacedWrap(dp(4)));
            microphone.setOnCheckedChangeListener((button, enabled) -> {
                ManagerStateStore.setMicrophoneInputEnabled(
                        this, app.packageName, enabled);
                showBanner((enabled ? "Enabling" : "Disabling")
                        + " microphone access for " + app.label, false);
                installSelectedVersion(app, app.sourceVersion);
            });
            page.addView(permissions, spacedWrap(dp(6)));
        }

        LinearLayout actions = verticalSection();
        Button launch = actionButton(signerMigration ? "Replace wrapper"
                : managed ? "Open Terminal" : "Launch",
                signerMigration ? android.R.drawable.stat_notify_error
                : android.R.drawable.ic_media_play);
        if (signerMigration) styleMigrationButton(launch);
        else stylePrimaryButton(launch);
        launch.setEnabled(signerMigration || managed || app.launchIntent != null);
        if (signerMigration) {
            launch.setOnClickListener(view -> installSelectedVersion(
                    app, app.sourceVersion));
        } else if (managed) {
            launch.setOnClickListener(view -> {
                launch.setEnabled(false);
                launch.setText("Preparing Terminal...");
                TerminalCompanionInstaller.ensureInstalled(this,
                        (phase, percent, status, terminal) -> {
                            showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                            if (phase == ApkUpdateInstaller.Phase.COMPLETE) {
                                launch.setEnabled(true);
                                launch.setText("Open Terminal");
                                try {
                                    TerminalCompanionInstaller.launch(this);
                                } catch (Exception error) {
                                    showBanner("Could not open Terminal: " + error.getMessage(), true);
                                }
                            } else if (phase == ApkUpdateInstaller.Phase.ERROR
                                    || phase == ApkUpdateInstaller.Phase.CANCELLED) {
                                launch.setEnabled(true);
                                launch.setText("Open Terminal");
                            } else {
                                launch.setText(status + (percent > 0 ? " " + percent + "%" : ""));
                            }
                        });
            });
        } else if (app.launchIntent != null) {
            launch.setOnClickListener(view -> startActivity(app.launchIntent));
        }
        actions.addView(launch, matchWrap());
        Button check = actionButton("Check for update", android.R.drawable.ic_popup_sync);
        check.setOnClickListener(view -> LinuxAppUpdateCoordinator.checkOne(this, app,
                (updated, next, completed, total) -> {
                    if (completed == total) showAppDetail(app);
                }));
        actions.addView(check, spacedWrap(dp(8)));
        if (managed) {
            Button remove = actionButton("Remove package", android.R.drawable.ic_menu_delete);
            remove.setTextColor(COLOR_ERROR);
            remove.setOnClickListener(view -> confirmManagedPackageRemoval(app));
            actions.addView(remove, spacedWrap(dp(8)));
        } else {
            Button androidSettings = actionButton("Android app settings",
                    android.R.drawable.ic_menu_manage);
            androidSettings.setOnClickListener(view -> startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.packageName))));
            actions.addView(androidSettings, spacedWrap(dp(8)));
            Button uninstall = actionButton("Uninstall app", android.R.drawable.ic_menu_delete);
            uninstall.setTextColor(COLOR_ERROR);
            uninstall.setOnClickListener(view -> {
                ManagerStateStore.setPendingUninstallPackage(this, app.packageName);
                Intent request = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                        Uri.parse("package:" + app.packageName));
                request.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                showAppsPage();
                startActivityForResult(request, REQUEST_UNINSTALL_LINUX_APP);
            });
            actions.addView(uninstall, spacedWrap(dp(8)));
        }
        page.addView(actions, spacedWrap(dp(6)));
        scroll.addView(page);
        content.addView(scroll, frameMatch());
    }

    private boolean installedBridgeCapability(String packageName, String capability) {
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(
                    packageName, PackageManager.GET_META_DATA);
            String values = info.metaData == null ? "" : info.metaData.getString(
                    "org.archphene.bridge.capabilities", "");
            for (String value : values.split(",")) {
                if (capability.equals(value.trim())) return true;
            }
        } catch (Exception ignored) {
            // Managed Terminal entries and removed packages do not expose wrapper metadata.
        }
        return false;
    }
    private String commandSummary(List<String> commands) {
        int shown = Math.min(8, commands.size());
        String value = android.text.TextUtils.join(", ", commands.subList(0, shown));
        return commands.size() > shown ? value + " +" + (commands.size() - shown) : value;
    }

    private void confirmManagedPackageRemoval(InstalledLinuxAppCatalog.Entry app) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + app.label + "?")
                .setMessage("This removes the package from the shared Terminal environment. "
                        + "Terminal home files are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> new Thread(() -> {
                    try {
                        ManagedPackageStore.Entry target = null;
                        for (ManagedPackageStore.Entry entry : ManagedPackageStore.list(this)) {
                            if (entry.stateKey().equals(app.packageName)) {
                                target = entry;
                                break;
                            }
                        }
                        if (target == null) throw new IllegalStateException("Package is not installed");
                        ManagedPackageStore.remove(this, target);
                        PackageInstallJobStore.clear(this, PackageInstallJobStore.key(app));
                        runOnUiThread(() -> {
                            showBanner(app.label + " removed from Terminal", false);
                            showAppsPage();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> showBanner("Could not remove " + app.label + ": "
                                + error.getMessage(), true));
                    }
                }, "archphene-managed-package-remove").start())
                .show();
    }
    private void renderPackageJobDetail() {
        if (packageJobDetail == null) return;
        if (packageJobDetailId.isEmpty()) {
            packageJobDetail.setVisibility(View.GONE);
            return;
        }
        PackageInstallJobStore.Snapshot job = PackageInstallJobStore.read(
                this, packageJobDetailId);
        packageJobDetail.removeAllViews();
        if (!job.active() && !job.retryable()) {
            packageJobDetail.setVisibility(View.GONE);
            return;
        }
        packageJobDetail.setVisibility(View.VISIBLE);
        packageJobDetail.addView(detailLine("Install phase", job.status));
        addPackageDiagnostics(packageJobDetail, job);
        if (job.active()) {
            TwoStageProgressView progress = new TwoStageProgressView(this,
                    COLOR_PRIMARY, COLOR_MUTED);
            progress.setState(job.phase, job.percent, job.status);
            packageJobDetail.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            return;

        }
        TextView failure = text(job.error.isEmpty() ? job.status : job.error,
                12, COLOR_ERROR);
        packageJobDetail.addView(failure, spacedWrap(dp(6)));
        if (packageJobDetailApp != null) {
            Button retry = actionButton("Retry install",
                    android.R.drawable.stat_notify_error);
            retry.setOnClickListener(view ->
                    startOnDevicePackageInstall(packageSource(packageJobDetailApp)));
            packageJobDetail.addView(retry, spacedWrap(dp(8)));
        }
    }

    private void addPackageDiagnostics(LinearLayout parent,
            PackageInstallJobStore.Snapshot job) {
        String summary = PackageInstallJobStore.diagnosticSummary(job);
        if (summary.isEmpty()) return;
        TextView title = text("Recent phases", 12, COLOR_MUTED);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(title, spacedWrap(dp(8)));
        TextView history = text(summary, 11, COLOR_MUTED);
        history.setTextIsSelectable(true);
        parent.addView(history, spacedWrap(dp(4)));
    }

    private void populateVersionSelector(InstalledLinuxAppCatalog.Entry app,
            ArrayList<String> versions, ArrayAdapter<String> adapter, Spinner selector,
            TextView health, String[] selectedVersion, String pinned) {
        new Thread(() -> {
            try {
                List<String> found;
                if ("archphene".equals(app.sourceType)) {
                    found = new ArrayList<>();
                    found.add(app.sourceVersion);
                    for (GitHubReleaseClient.Artifact artifact
                            : GitHubReleaseClient.versions(this)) {
                        if (!found.contains(artifact.version)) found.add(artifact.version);
                    }
                } else {
                    String packageName = app.sourceId.substring(app.sourceId.lastIndexOf('/') + 1);
                    found = ArchPackageRepository.versions(this,
                            packageName, app.sourceVersion);
                }
                runOnUiThread(() -> {
                    if (currentPage != 2) return;
                    versions.clear();
                    versions.addAll(found);
                    adapter.notifyDataSetChanged();
                    selector.setContentDescription("Version selector, " + versions.size() + " versions");
                    String preferred = !pinned.isEmpty() && versions.contains(pinned)
                            ? pinned : selectedVersion[0];
                    int position = versions.indexOf(preferred);
                    selector.setSelection(position < 0 ? 0 : position);
                    health.setText(versionHealthLabel(app.packageName,
                            versions.get(selector.getSelectedItemPosition()),
                            app.sourceVersion, ManagerStateStore.read(this,
                                    app.packageName).availableVersion));
                });
            } catch (Exception e) {
                runOnUiThread(() -> health.setText("Version history unavailable"));
            }
        }, "arch-version-history").start();
    }

    private String versionHealthLabel(String packageName, String version,
            String installedVersion, String availableVersion) {
        String health = ManagerStateStore.versionHealth(this, packageName, version);
        boolean prerelease = ManagerStateStore.versionPrerelease(this, packageName, version);
        String prefix = prerelease ? "Pre-release | " : "";
        if ("bad".equals(health)) return prefix + "Reported problematic version";
        if (getPackageName().equals(packageName)
                && GitHubReleaseClient.compareVersions(version, installedVersion) < 0) {
            return prefix + "Older manager version; Android rollback is unavailable";
        }
        if (version.equals(availableVersion)) return prefix + "Current repository version";
        if (version.equals(installedVersion)) return prefix + "Installed version";
        return prefix + "Archived version; compatibility not verified";
    }

    private void installSelectedVersion(InstalledLinuxAppCatalog.Entry app, String version) {
        if (managerRollbackUnavailable(app, version)) {
            showBanner("Android does not allow Archphene to replace itself with an older build",
                    true);
            return;
        }
        String pinned = ManagerStateStore.pinnedVersion(this, app.packageName);
        if (app.sourceVersion.equals(version)) {
            if (!pinned.isEmpty() && !pinned.equals(version)) {
                ManagerStateStore.setPinnedVersion(this, app.packageName, version);
            }
            if ("pacman".equals(app.sourceType)) {
                startOnDevicePackageInstall(packageSource(app));
            } else {
                showBanner(app.label + " " + version + " is already installed", false);
            }
            return;
        }
        new Thread(() -> {
            try {
                if ("archphene".equals(app.sourceType)) {
                    GitHubReleaseClient.Artifact release =
                            GitHubReleaseClient.find(this, version);
                    runOnUiThread(() -> startManagerReleaseInstall(app, release));
                    return;
                }
                WrapperRepositoryClient.Artifact artifact = WrapperRepositoryClient.find(
                        this, app.packageName, version);
                runOnUiThread(() -> {
                    if (!pinned.isEmpty() && !pinned.equals(version)) {
                        ManagerStateStore.setPinnedVersion(this, app.packageName, version);
                    }
                    startArtifactInstall(app, artifact);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showBanner("No signed wrapper artifact for "
                        + app.label + " " + version + " in the configured repositories", true));
            }
        }, "wrapper-artifact-lookup").start();
    }

    private boolean managerRollbackUnavailable(InstalledLinuxAppCatalog.Entry app,
            String version) {
        return "archphene".equals(app.sourceType)
                && GitHubReleaseClient.compareVersions(version, app.sourceVersion) < 0;
    }
    private void startManagerReleaseInstall(InstalledLinuxAppCatalog.Entry app,
            GitHubReleaseClient.Artifact release) {
        showAppsPage();
        activeInstallPackage = app.packageName;
        activeInstallOperation = ApkUpdateInstaller.installWithProgress(this,
                release.apkUrl, release.sha256, app.packageName,
                (phase, percent, status, terminal) -> {
                    activeInstallPhase = phase;
                    activeInstallPercent = percent;
                    activeInstallStatus = status;
                    if (terminal) {
                        activeInstallOperation = null;
                        activeInstallPackage = "";
                        showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                        loadCatalog();
                    } else {
                        renderProgressAtStableCadence(() -> {
                            if (currentPage == 0) renderAppList();
                        });
                    }
                });
        renderAppList();
    }
    private void startArtifactInstall(InstalledLinuxAppCatalog.Entry app,
            WrapperRepositoryClient.Artifact artifact) {
        showAppsPage();
        activeInstallPackage = app.packageName;
        activeInstallOperation = ApkUpdateInstaller.installWithProgress(this,
                artifact.apkUrl, artifact.sha256, app.packageName,
                (phase, percent, status, terminal) -> {
                    activeInstallPhase = phase;
                    activeInstallPercent = percent;
                    activeInstallStatus = status;
                    if (terminal) {
                        activeInstallOperation = null;
                        activeInstallPackage = "";
                        showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                        loadCatalog();
                    } else {
                        renderProgressAtStableCadence(() -> {
                            if (currentPage == 0) renderAppList();
                        });
                    }
                });
        renderAppList();
    }
    private void showSettingsPage() {
        currentPage = 1;
        setAddVisible(false);
        setNavigationSelection(false);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(20), dp(16), dp(28));
        TextView title = text("Settings", 28, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title, matchWrap());

        page.addView(sectionLabel("Update checks"), matchWrap());
        LinearLayout updates = verticalSection();
        updates.addView(settingToggle("Background update checks",
                "Use Android's scheduler and notify when versions change",
                ManagerStateStore.backgroundChecksEnabled(this), (button, checked) -> {
                    boolean active = LinuxAppManagerService.schedule(this, checked);
                    ManagerStateStore.setBackgroundChecksEnabled(this, checked && active);
                    if (checked && !active) {
                        button.setChecked(false);
                        showBanner("Android could not schedule background checks", true);
                        return;
                    }
                    if (checked && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 100);
                    }
                }));
        updates.addView(settingToggle("Check when manager opens",
                "Refresh package metadata after launch",
                ManagerStateStore.checkOnLaunch(this), (button, checked) ->
                        ManagerStateStore.setCheckOnLaunch(this, checked)));
        updates.addView(settingToggle("Allow pre-release versions",
                "Include alpha, beta, release candidate, preview, and nightly builds",
                ManagerStateStore.allowPrereleases(this), (button, checked) -> {
                    ManagerStateStore.setAllowPrereleases(this, checked);
                    showBanner(checked ? "Pre-release versions enabled"
                            : "Pre-release versions hidden", false);
                }));
        updates.addView(settingToggle("Unmetered network only",
                "Avoid scheduled checks on metered mobile data",
                ManagerStateStore.wifiOnly(this), (button, checked) -> {
                    ManagerStateStore.setWifiOnly(this, checked);
                    rescheduleBackgroundChecks();
                }));
        updates.addView(settingToggle("While charging only",
                "Require charging for scheduled metadata checks",
                ManagerStateStore.chargingOnly(this), (button, checked) -> {
                    ManagerStateStore.setChargingOnly(this, checked);
                    rescheduleBackgroundChecks();
                }));
        Button interval = actionButton(intervalLabel(), android.R.drawable.ic_menu_recent_history);
        interval.setAllCaps(false);
        interval.setOnClickListener(view -> {
            int current = ManagerStateStore.updateIntervalHours(this);
            int next = current == 6 ? 12 : current == 12 ? 24 : current == 24 ? 72 : 6;
            ManagerStateStore.setUpdateIntervalHours(this, next);
            interval.setText(intervalLabel());
            rescheduleBackgroundChecks();
        });
        updates.addView(interval, matchWrap());
        page.addView(updates, spacedWrap(dp(6)));
        page.addView(sectionLabel("Terminal"), matchWrap());
        LinearLayout terminalSection = verticalSection();
        TerminalCompanionInstaller.Status terminalStatus =
                TerminalCompanionInstaller.status(this);
        terminalSection.addView(detailLine("Archphene Terminal", terminalStatus.summary()));
        Button terminalAction = actionButton(terminalStatus.action(),
                android.R.drawable.ic_media_play);
        terminalAction.setOnClickListener(view -> {
            TerminalCompanionInstaller.Status current =
                    TerminalCompanionInstaller.status(this);
            if (current.ready) {
                try {
                    TerminalCompanionInstaller.launch(this);
                } catch (Exception error) {
                    showBanner("Could not open Terminal: " + error.getMessage(), true);
                }
                return;
            }
            if (current.installed && !current.sameSigner) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + TerminalCompanionInstaller.PACKAGE)));
                return;
            }
            terminalAction.setEnabled(false);
            TerminalCompanionInstaller.ensureInstalled(this,
                    (phase, percent, status, terminal) -> {
                        terminalAction.setText(status
                                + (percent > 0 && percent < 100 ? " " + percent + "%" : ""));
                        if (!terminal) return;
                        showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                        showSettingsPage();
                    });
        });
        terminalSection.addView(terminalAction, spacedWrap(dp(6)));
        page.addView(terminalSection, spacedWrap(dp(6)));

        page.addView(sectionLabel("Appearance"), matchWrap());
        LinearLayout appearance = verticalSection();
        Button theme = actionButton(themeLabel(), android.R.drawable.ic_menu_day);
        theme.setOnClickListener(view -> {
            String current = ManagerStateStore.themeMode(this);
            String next = "system".equals(current) ? "dark"
                    : "dark".equals(current) ? "light" : "system";
            ManagerStateStore.setThemeMode(this, next);
            recreate();
        });
        appearance.addView(theme, matchWrap());
        Button linuxTheme = actionButton(linuxThemeLabel(), android.R.drawable.ic_menu_day);
        linuxTheme.setOnClickListener(view -> {
            String current = ManagerStateStore.linuxThemeMode(this);
            String next = "system".equals(current) ? "dark"
                    : "dark".equals(current) ? "light" : "system";
            ManagerStateStore.setLinuxThemeMode(this, next);
            linuxTheme.setText(linuxThemeLabel());
            showBanner("Linux app appearance applies the next time an app starts", false);
        });
        appearance.addView(linuxTheme, spacedWrap(dp(6)));
        Button linuxScale = actionButton(linuxScaleLabel(), android.R.drawable.ic_menu_view);
        linuxScale.setOnClickListener(view -> {
            int current = ManagerStateStore.linuxScalePercent(this);
            int next = current == 0 ? 100 : current == 100 ? 125
                    : current == 125 ? 150 : current == 150 ? 175
                    : current == 175 ? 200 : 0;
            ManagerStateStore.setLinuxScalePercent(this, next);
            linuxScale.setText(linuxScaleLabel());
            showBanner("Linux app appearance applies the next time an app starts", false);
        });
        appearance.addView(linuxScale, spacedWrap(dp(6)));
        Button linuxFont = actionButton(linuxFontLabel(), android.R.drawable.ic_menu_zoom);
        linuxFont.setOnClickListener(view -> {
            int current = ManagerStateStore.linuxFontPercent(this);
            boolean phone = getResources().getConfiguration().smallestScreenWidthDp < 600;
            int next = phone
                    ? current == 100 ? 110 : current == 110 ? 120 : 100
                    : current == 100 ? 110 : current == 110 ? 125
                            : current == 125 ? 150 : 100;
            ManagerStateStore.setLinuxFontPercent(this, next);
            linuxFont.setText(linuxFontLabel());
            showBanner("Linux app appearance applies the next time an app starts", false);
        });
        appearance.addView(linuxFont, spacedWrap(dp(6)));
        appearance.addView(settingToggle("Material You colors",
                "Use Android system colors when available",
                ManagerStateStore.materialYou(this), (button, checked) -> {
                    ManagerStateStore.setMaterialYou(this, checked);
                    recreate();
                }));
        page.addView(appearance, spacedWrap(dp(6)));

        page.addView(sectionLabel("Repository management"), matchWrap());
        LinearLayout repositories = verticalSection();
        for (ManagedRepositoryStore.Repository repository : ManagedRepositoryStore.list(this)) {
            if (repository.builtIn) {
                repositories.addView(detailLine(repository.name, "Package metadata"));
            } else {
                Button repositoryButton = actionButton(repository.name,
                        android.R.drawable.ic_menu_manage);
                repositoryButton.setContentDescription("Manage repository " + repository.name);
                repositoryButton.setOnClickListener(view -> showRepositoryDialog(repository));
                repositories.addView(repositoryButton, spacedWrap(dp(6)));
            }
        }
        Button addRepository = actionButton("Add repository", android.R.drawable.ic_input_add);
        addRepository.setOnClickListener(view -> showAddRepositoryDialog());
        repositories.addView(addRepository, spacedWrap(dp(8)));
        Button clear = actionButton("Clear cache and refresh all",
                android.R.drawable.ic_popup_sync);
        clear.setOnClickListener(view -> {
            if (PackageInstallCoordinator.hasActiveOperations()) {
                Toast.makeText(this, "Wait for package operations to finish",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            clear.setEnabled(false);
            ManagedRepositoryStore.clearCache(this);
            new Thread(() -> {
                try {
                    int archives = ArchPackageRuntime.clearDownloadCache(this);
                    int removed = RuntimePackStore.garbageCollectNow(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Cache cleared: " + archives
                                + " package files and " + removed
                                + " unused runtime packs removed", Toast.LENGTH_SHORT).show();
                        showAppsPage();
                        checkAll();
                    });
                } catch (Exception error) {
                    android.util.Log.e("ArchpheneRuntime",
                            "Manual runtime-pack cleanup failed", error);
                    runOnUiThread(() -> {
                        clear.setEnabled(true);
                        Toast.makeText(this, "Could not clear runtime cache",
                                Toast.LENGTH_LONG).show();
                    });
                }
            }, "archphene-manual-cache-clear").start();
        });
        repositories.addView(clear, spacedWrap(dp(8)));
        page.addView(repositories, spacedWrap(dp(6)));
        scroll.addView(page);
        content.addView(scroll, frameMatch());
    }

    private View settingToggle(String title, String subtitle, boolean value,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, COLOR_TEXT), matchWrap());
        labels.addView(text(subtitle, 12, COLOR_MUTED), matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);
        return row;
    }

    private TextView sectionLabel(String label) {
        TextView view = text(label, 14, COLOR_PRIMARY);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(4), dp(22), 0, dp(7));
        return view;
    }

    private String intervalLabel() {
        int hours = ManagerStateStore.updateIntervalHours(this);
        return hours < 24 ? "Check interval: every " + hours + " hours"
                : "Check interval: every " + (hours / 24) + (hours == 24 ? " day" : " days");
    }

    private String themeLabel() {
        String mode = ManagerStateStore.themeMode(this);
        return "Theme: " + ("dark".equals(mode) ? "Dark"
                : "light".equals(mode) ? "Light" : "System default");
    }

    private String linuxThemeLabel() {
        String mode = ManagerStateStore.linuxThemeMode(this);
        return "Linux app theme: " + ("dark".equals(mode) ? "Dark"
                : "light".equals(mode) ? "Light" : "Follow Android");
    }

    private String linuxScaleLabel() {
        int percent = ManagerStateStore.linuxScalePercent(this);
        return "Linux app scale: " + (percent == 0 ? "Automatic" : percent + "%");
    }

    private String linuxFontLabel() {
        return "Linux app text: " + ManagerStateStore.linuxFontPercent(this) + "%";
    }

    private void showRepositoryDialog(ManagedRepositoryStore.Repository repository) {
        new AlertDialog.Builder(this)
                .setTitle(repository.name)
                .setMessage(repository.wrapperCatalogUrl)
                .setNegativeButton("Close", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    ManagedRepositoryStore.remove(this, repository.id);
                    ManagedRepositoryStore.clearCache(this);
                    showSettingsPage();
                })
                .show();
    }
    private void showAddRepositoryDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(4), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("Repository name");
        name.setSingleLine(true);
        fields.addView(name, matchWrap());
        EditText url = new EditText(this);
        url.setHint("HTTPS wrapper catalog URL");
        url.setSingleLine(true);
        fields.addView(url, spacedWrap(dp(6)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add repository")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ManagedRepositoryStore.addWrapperRepository(this,
                                name.getText().toString(), url.getText().toString());
                        dialog.dismiss();
                        showSettingsPage();
                    } catch (Exception e) {
                        url.setError(e.getMessage());
                    }
                }));
        dialog.show();
    }
    private void rescheduleBackgroundChecks() {
        if (ManagerStateStore.backgroundChecksEnabled(this)) {
            LinuxAppManagerService.schedule(this, true);
        }
    }

    private Intent runtimeModuleLaunchIntent(String packageName, Uri module) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null || launch.getComponent() == null) {
            throw new IllegalArgumentException("Runtime wrapper has no launcher Activity");
        }
        launch.setAction(Intent.ACTION_VIEW);
        launch.setData(module);
        return launch;
    }

    private void handleTestRuntimeModuleIntent() {
        String packageName = getIntent().getStringExtra(
                "archphene_test_runtime_module_package");
        if (packageName == null) return;
        String action = getIntent().getStringExtra("archphene_test_runtime_module_action");
        try {
            if ("verify_catalog".equals(action)) {
                RuntimeModuleCatalog.verifyParserForTest();
                android.util.Log.i("ArchpheneRuntime", "Runtime catalog parser passed");
            } else if ("launch".equals(action)) {
                Uri probe = RuntimeModuleProvider.uriForRole(this, "static-probe");
                Intent launch = runtimeModuleLaunchIntent(packageName, probe);
                launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch.putExtra("archphene_test_runtime_module_uri", probe.toString());
                startActivity(launch);
                android.util.Log.i("ArchpheneRuntime", "Launched runtime module for "
                        + packageName);
            } else if ("launch_dynamic".equals(action)
                    || "launch_dynamic_transitive".equals(action)) {
                boolean transitive = "launch_dynamic_transitive".equals(action);
                Uri program = RuntimeModuleProvider.uriForRole(this,
                        transitive ? "transitive-probe" : "dynamic-probe");
                Uri loader = RuntimeModuleProvider.uriForRole(this, "glibc-loader");
                String[] libraryRoles = transitive
                        ? new String[] {"glibc-libc", "transitive-probe-library"}
                        : new String[] {"glibc-libc"};
                String[] libraryUris = new String[libraryRoles.length];
                String[] libraryNames = new String[libraryRoles.length];
                Intent launch = runtimeModuleLaunchIntent(packageName, program);
                android.content.ClipData modules = android.content.ClipData.newUri(
                        getContentResolver(), "Archphene runtime modules", program);
                modules.addItem(new android.content.ClipData.Item(loader));
                for (int index = 0; index < libraryRoles.length; index++) {
                    Uri library = RuntimeModuleProvider.uriForRole(this, libraryRoles[index]);
                    libraryUris[index] = library.toString();
                    libraryNames[index] = RuntimeModuleProvider.linkNameForRole(
                            this, libraryRoles[index]);
                    modules.addItem(new android.content.ClipData.Item(library));
                }
                launch.setClipData(modules);
                launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch.putExtra("archphene_test_runtime_module_uri", program.toString());
                launch.putExtra("archphene_test_runtime_loader_uri", loader.toString());
                launch.putExtra("archphene_test_runtime_library_uris", libraryUris);
                launch.putExtra("archphene_test_runtime_library_names", libraryNames);
                startActivity(launch);
                android.util.Log.i("ArchpheneRuntime", "Launched glibc runtime set with "
                        + libraryRoles.length + " libraries for " + packageName);
            } else if ("launch_pack".equals(action)) {
                RuntimePackStore.Pack pack = RuntimePackStore.active(this, packageName);
                RuntimePackStore.Module program = pack.requireKind("program");
                pack.requireKind("loader");
                Uri loader = RuntimeModuleProvider.uriForRole(this, "glibc-loader");
                List<RuntimePackStore.Module> libraries = pack.libraries();
                String[] libraryUris = new String[libraries.size()];
                String[] libraryNames = new String[libraries.size()];
                Intent launch = runtimeModuleLaunchIntent(packageName, program.uri(pack.id));
                android.content.ClipData modules = android.content.ClipData.newUri(
                        getContentResolver(), "Archphene runtime pack", program.uri(pack.id));
                modules.addItem(new android.content.ClipData.Item(loader));
                for (int index = 0; index < libraries.size(); index++) {
                    RuntimePackStore.Module library = libraries.get(index);
                    libraryUris[index] = library.uri(pack.id).toString();
                    libraryNames[index] = library.linkName;
                    modules.addItem(new android.content.ClipData.Item(library.uri(pack.id)));
                }
                launch.setClipData(modules);
                launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch.putExtra("archphene_test_runtime_module_uri",
                        program.uri(pack.id).toString());
                launch.putExtra("archphene_test_runtime_loader_uri",
                        loader.toString());
                launch.putExtra("archphene_test_runtime_library_uris", libraryUris);
                launch.putExtra("archphene_test_runtime_library_names", libraryNames);
                launch.putExtra("archphene_runtime_gui", true);
                startActivity(launch);
                android.util.Log.i("ArchpheneRuntime", "Launched pack " + pack.id
                        + " with " + libraries.size() + " libraries for " + packageName);
            } else if ("revoke".equals(action)) {
                RuntimeModuleProvider.revokeAll(this);
                android.util.Log.i("ArchpheneRuntime", "Revoked runtime module from "
                        + packageName);
                showBanner("Runtime module access revoked from " + packageName, false);
            } else {
                throw new IllegalArgumentException("Unknown runtime module action");
            }
        } catch (Exception error) {
            android.util.Log.e("ArchpheneRuntime", "Runtime module grant failed", error);
            showBanner("Runtime module grant failed: " + error.getMessage(), true);
        }
    }

    private void handleTestGuiDocumentsIntent() {
        String packageName = getIntent().getStringExtra("archphene_test_gui_documents");
        getIntent().removeExtra("archphene_test_gui_documents");
        if (packageName == null) return;
        new Thread(() -> {
            Uri created = null;
            try {
                String authority = "org.archpheneos.manager.documents";
                String wanted = "app/" + packageName + "/home";
                Uri children = android.provider.DocumentsContract.buildChildDocumentsUri(
                        authority, "apps");
                boolean found = false;
                try (android.database.Cursor cursor = getContentResolver().query(children,
                        new String[] {
                                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                        null, null, null)) {
                    while (cursor != null && cursor.moveToNext()) {
                        if (wanted.equals(cursor.getString(0))) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) throw new SecurityException("GUI app home is not brokered");
                Uri home = android.provider.DocumentsContract.buildDocumentUri(authority, wanted);
                String name = "archphene-broker-" + System.currentTimeMillis() + ".txt";
                created = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), home, "text/plain", name);
                if (created == null) throw new IllegalStateException("Create returned no URI");
                byte[] expected = "manager-broker-round-trip\n".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream output = getContentResolver()
                        .openOutputStream(created, "rwt")) {
                    if (output == null) throw new IllegalStateException("Open returned no output");
                    output.write(expected);
                }
                byte[] actual;
                try (java.io.InputStream input = getContentResolver().openInputStream(created);
                        java.io.ByteArrayOutputStream output =
                                new java.io.ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Open returned no input");
                    input.transferTo(output);
                    actual = output.toByteArray();
                }
                if (!java.util.Arrays.equals(expected, actual)) {
                    throw new SecurityException("Brokered document content mismatch");
                }
                String renamedName = name.replace(".txt", "-renamed.txt");
                Uri renamed = android.provider.DocumentsContract.renameDocument(
                        getContentResolver(), created, renamedName);
                if (renamed == null) throw new IllegalStateException("Rename returned no URI");
                created = renamed;
                try (android.database.Cursor cursor = getContentResolver().query(renamed,
                        new String[] {
                                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                        null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()
                            || !renamedName.equals(cursor.getString(0))) {
                        throw new SecurityException("Brokered rename was not visible");
                    }
                }
                if (!android.provider.DocumentsContract.deleteDocument(
                        getContentResolver(), renamed)) {
                    throw new IllegalStateException("Delete returned false");
                }
                created = null;
                android.util.Log.i("ArchpheneDocuments",
                        "GUI document broker passed package=" + packageName
                                + " bytes=" + actual.length);
                runOnUiThread(() -> showBanner("GUI document broker passed", false));
            } catch (Exception error) {
                android.util.Log.e("ArchpheneDocuments", "GUI document broker failed", error);
                runOnUiThread(() -> showBanner(
                        "GUI document broker failed: " + safeMessage(error), true));
            } finally {
                if (created != null) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(
                                getContentResolver(), created);
                    } catch (Exception ignored) {}
                }
            }
        }, "archphene-gui-documents-test").start();
    }
    private void handleTestDocumentSessionIntent() {
        String sourcePackage = getIntent().getStringExtra(
                "archphene_test_document_session_source");
        String targetPackage = getIntent().getStringExtra(
                "archphene_test_document_session_target");
        getIntent().removeExtra("archphene_test_document_session_source");
        getIntent().removeExtra("archphene_test_document_session_target");
        if (sourcePackage == null || targetPackage == null) return;
        new Thread(() -> {
            try {
                String authority = "org.archpheneos.manager.documents";
                Uri home = android.provider.DocumentsContract.buildDocumentUri(authority,
                        "app/" + sourcePackage + "/home");
                String suffix = Long.toHexString(System.currentTimeMillis());
                Uri firstDir = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), home,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                        "document-probe-a-" + suffix);
                Uri secondDir = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), home,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                        "document-probe-b-" + suffix);
                if (firstDir == null || secondDir == null) {
                    throw new IllegalStateException("Could not create probe directories");
                }
                Uri first = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), firstDir, "text/plain", "same-name.txt");
                Uri second = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), secondDir, "text/plain", "same-name.txt");
                if (first == null || second == null) {
                    throw new IllegalStateException("Could not create probe documents");
                }
                writeTestDocument(first, "first-source\n");
                writeTestDocument(second, "second-source\n");
                launchTestDocumentSession(targetPackage, first, second, false);
                Thread.sleep(3000);
                Uri thirdDir = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), home,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                        "document-probe-c-" + suffix);
                Uri fourthDir = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), home,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                        "document-probe-d-" + suffix);
                if (thirdDir == null || fourthDir == null) {
                    throw new IllegalStateException(
                            "Could not create running-restart probe directories");
                }
                Uri third = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), thirdDir, "text/plain", "same-name.txt");
                Uri fourth = android.provider.DocumentsContract.createDocument(
                        getContentResolver(), fourthDir, "text/plain", "same-name.txt");
                if (third == null || fourth == null) {
                    throw new IllegalStateException(
                            "Could not create running-restart probe documents");
                }
                writeTestDocument(third, "third-source\n");
                writeTestDocument(fourth, "fourth-source\n");
                launchTestDocumentSession(targetPackage, third, fourth, true);
                android.util.Log.i("ArchpheneDocuments",
                        "Launched running document restart probe source=" + sourcePackage
                                + " target=" + targetPackage);
            } catch (Exception error) {
                android.util.Log.e("ArchpheneDocuments",
                        "Could not launch document conflict probe", error);
                runOnUiThread(() -> showBanner(
                        "Document conflict probe failed: " + safeMessage(error), true));
            }
        }, "archphene-document-session-test").start();
    }

    private void launchTestDocumentSession(
            String targetPackage, Uri first, Uri second, boolean runningRestart) {
        Intent launch = new Intent(Intent.ACTION_EDIT);
        Intent launcher = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (launcher == null || launcher.getComponent() == null) {
            throw new IllegalStateException("Target wrapper has no launcher Activity");
        }
        launch.setComponent(launcher.getComponent());
        launch.setDataAndType(first, "text/plain");
        android.content.ClipData documents = android.content.ClipData.newUri(
                getContentResolver(), "Archphene document session", first);
        documents.addItem(new android.content.ClipData.Item(second));
        launch.setClipData(documents);
        launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (runningRestart) {
            launch.putExtra("archphene_test_document_conflict", true);
            launch.putExtra("archphene_test_confirm_document_restart", true);
            launch.putExtra("archphene_test_require_active_document_restart", true);
        }
        grantUriPermission(targetPackage, first,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        grantUriPermission(targetPackage, second,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivity(launch);
    }

    private void writeTestDocument(Uri uri, String value) throws java.io.IOException {
        try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "rwt")) {
            if (output == null) throw new java.io.IOException("Document is not writable");
            output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private void handleTestGitHubReleaseIntent() {
        if (!getIntent().getBooleanExtra("archphene_test_github_releases", false)) return;
        new Thread(() -> {
            try {
                GitHubReleaseClient.verifyParserForTest();
                String testHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
                if (!testHash.equals(GitHubReleaseClient.parseChecksum(
                        testHash + "  Archphene-1.0.0.apk",
                        "Archphene-1.0.0.apk"))) {
                    throw new SecurityException("Checksum parser mismatch");
                }
                if (GitHubReleaseClient.compareVersions("1.1.0", "1.0.9") <= 0
                        || GitHubReleaseClient.compareVersions("1.0.0-rc1", "1.0.0") >= 0) {
                    throw new SecurityException("Release version ordering mismatch");
                }
                int count = GitHubReleaseClient.versions(this).size();
                runOnUiThread(() -> showBanner("GitHub Releases discovery passed: "
                        + count + " eligible releases", false));
            } catch (Exception error) {
                android.util.Log.e("ArchpheneManager", "GitHub Releases discovery failed", error);
                runOnUiThread(() -> showBanner("GitHub Releases discovery failed: "
                        + error.getMessage(), true));
            }
        }, "archphene-github-release-test").start();
    }
    private void handleTestMicrophonePreferenceIntent() {
        String packageName = getIntent().getStringExtra(
                "archphene_test_microphone_package");
        if (packageName == null) return;
        getIntent().removeExtra("archphene_test_microphone_package");
        if (!packageName.matches("org\\.archphene\\.linux\\.p[0-9a-f]{32}")) {
            throw new SecurityException("Invalid microphone test package");
        }
        boolean enabled = getIntent().getBooleanExtra(
                "archphene_test_microphone_enabled", false);
        ManagerStateStore.setMicrophoneInputEnabled(this, packageName, enabled);
        android.util.Log.i("ArchpheneManager", "Microphone wrapper preference "
                + packageName + " enabled=" + enabled);
    }
    private void handleTestWrapperAssemblyIntent() {
        String sourcePackage = getIntent().getStringExtra("archphene_test_assemble_qt");
        if (sourcePackage == null) return;
        new Thread(() -> {
            ArchPackageRuntime.StagedTransaction staged = null;
            try {
                if (getIntent().getBooleanExtra("archphene_test_stage_transaction", false)) {
                    runOnUiThread(() -> showBanner("Resolving and verifying " + sourcePackage, false));
                    staged = ArchPackageRuntime.stageTransaction(this, sourcePackage);
                    android.util.Log.i("ArchpheneRuntime", "published pack "
                            + staged.runtimePackId + " for " + sourcePackage);
                }
                boolean waylandCandidate = getIntent().getBooleanExtra(
                        "archphene_test_wayland_candidate", false);
                String waylandExecutable = getIntent().getStringExtra(
                        "archphene_test_wayland_executable");
                ArchWrapperAssembler.Result result = staged == null
                        ? ArchWrapperAssembler.assembleQt(this, "extra", sourcePackage)
                        : waylandCandidate
                                ? ArchWrapperAssembler.assembleWaylandCandidateFromRuntimePack(
                                        this, "extra", sourcePackage, staged.sourceVersion(),
                                        ArchRuntimePolicy.current().architecture,
                                        staged.classification, staged.root,
                                        waylandExecutable == null
                                                ? staged.classification.executable
                                                : waylandExecutable)
                                : ArchWrapperAssembler.assembleDesktopFromRuntimePack(
                                        this, "extra", sourcePackage, staged.sourceVersion(),
                                        ArchRuntimePolicy.current().architecture, staged.toolkit,
                                        staged.classification, staged.root);
                if (getIntent().getBooleanExtra("archphene_test_install_assembled", false)) {
                    ArchPackageRuntime.StagedTransaction installedStaged = staged;
                    staged = null;
                    runOnUiThread(() -> {
                        showBanner("Generated " + result.packageName + "\nOpening Android installer", false);
                        ApkUpdateInstaller.installWithProgress(this, result.apk.toURI().toString(),
                                result.apkSha256, result.packageName, result.signerSha256,
                                (phase, percent, status, terminal) -> {
                                    if (!terminal) return;
                                    boolean failed = phase != ApkUpdateInstaller.Phase.COMPLETE;
                                    String finalStatus = status;
                                    if (!failed && installedStaged != null) {
                                        try {
                                            RuntimePackStore.activate(this, result.packageName,
                                                    installedStaged.runtimePackId);
                                            RuntimePackStore.grantActive(this, result.packageName);
                                            finalStatus = "Android package installed and runtime activated";
                                            android.util.Log.i("ArchpheneRuntime",
                                                    "activated generated wrapper " + result.packageName
                                                    + " pack=" + installedStaged.runtimePackId);
                                        } catch (Exception activationError) {
                                            failed = true;
                                            finalStatus = "Installed APK but runtime activation failed: "
                                                    + activationError.getMessage();
                                            android.util.Log.e("ArchpheneRuntime",
                                                    "Generated wrapper activation failed", activationError);
                                        }
                                    }
                                    if (installedStaged != null) {
                                        ArchPackageRuntime.releaseStaging(this, installedStaged);
                                    }
                                    showBanner(finalStatus, failed);
                                });
                    });
                } else {
                    runOnUiThread(() -> showBanner("Generated wrapper APK\nPackage "
                            + result.packageName + "\nSigner " + result.signerSha256, false));
                }
            } catch (Exception e) {
                android.util.Log.e("ArchpheneManager", "Wrapper assembly failed", e);
                runOnUiThread(() -> showBanner("Wrapper assembly failed: " + e.getMessage(), true));
            } finally {
                if (staged != null) ArchPackageRuntime.releaseStaging(this, staged);
            }
        }, "archphene-wrapper-assembly-test").start();
    }

    private void handleTestWrapperSigningIntent() {
        String inputPath = getIntent().getStringExtra("archphene_test_sign_apk_file");
        if (inputPath == null) return;
        new Thread(() -> {
            try {
                java.io.File output = new java.io.File(getFilesDir(),
                        "package-runtime/generated-wrapper-test.apk");
                ArchWrapperSigner.Result result = ArchWrapperSigner.sign(this,
                        new java.io.File(inputPath), output);
                runOnUiThread(() -> showBanner("Signed generated APK\nSigner "
                        + result.signerSha256 + "\nv2=" + result.verifiedV2
                        + " v3=" + result.verifiedV3, false));
            } catch (Exception e) {
                runOnUiThread(() -> showBanner("APK signing failed: " + e.getMessage(), true));
            }
        }, "archphene-wrapper-signing-test").start();
    }

    private void verifyConcurrentPackageIsolation() throws Exception {
        ArchPackageRepository.PackageResult success = null;
        Set<String> installedNames = new HashSet<>();
        for (ManagedPackageStore.Entry entry : ManagedPackageStore.list(this)) {
            installedNames.add(entry.name);
        }
        for (String candidate : new String[] {"tree", "jq", "ripgrep"}) {
            if (installedNames.contains(candidate)) continue;
            for (ArchPackageRepository.PackageResult value
                    : ArchPackageRepository.search(this, candidate)) {
                if (candidate.equals(value.name)
                        && ArchRuntimePolicy.supports(value.architecture)) {
                    success = value;
                    break;
                }
            }
            if (success != null) break;
        }
        if (success == null) {
            throw new IllegalStateException("No isolated CLI test package is available");
        }
        ArchPackageRepository.PackageResult failure = new ArchPackageRepository.PackageResult(
                "archphene-package-does-not-exist", "extra", "x86_64", "0",
                "Intentional failure-isolation fixture", false);
        String successId = PackageInstallJobStore.key(success);
        String failureId = PackageInstallJobStore.key(failure);
        PackageInstallJobStore.clear(this, successId);
        PackageInstallJobStore.clear(this, failureId);
        CountDownLatch terminal = new CountDownLatch(2);
        Set<String> completed = Collections.synchronizedSet(new HashSet<>());
        PackageInstallCoordinator.Listener listener = (state, isTerminal) -> {
            if (isTerminal && (successId.equals(state.id) || failureId.equals(state.id))
                    && completed.add(state.id)) {
                terminal.countDown();
            }
        };
        boolean successStarted = false;
        boolean failureStarted = false;
        ManagedPackageStore.Entry installedByTest = null;
        try {
            successStarted = PackageInstallCoordinator.start(this, success, listener);
            failureStarted = PackageInstallCoordinator.start(this, failure, listener);
            if (!successStarted || !failureStarted) {
                throw new IllegalStateException("Concurrent package jobs did not start");
            }
            if (!terminal.await(4, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Concurrent package jobs timed out");
            }
            PackageInstallJobStore.Snapshot succeeded = PackageInstallJobStore.read(this, successId);
            PackageInstallJobStore.Snapshot failed = PackageInstallJobStore.read(this, failureId);
            if (!PackageInstallJobStore.COMPLETE.equals(succeeded.state)
                    || !PackageInstallJobStore.ERROR.equals(failed.state)
                    || failed.error.isEmpty()
                    || PackageInstallCoordinator.operation(successId) != null
                    || PackageInstallCoordinator.operation(failureId) != null) {
                throw new IllegalStateException("Package failure escaped its transaction boundary");
            }
            for (ManagedPackageStore.Entry entry : ManagedPackageStore.list(this)) {
                if (entry.name.equals(success.name)) {
                    installedByTest = entry;
                    break;
                }
            }
            if (installedByTest == null) {
                throw new IllegalStateException("Successful CLI transaction was not published");
            }
        } finally {
            if (successStarted) PackageInstallCoordinator.cancel(successId);
            if (failureStarted) PackageInstallCoordinator.cancel(failureId);
            if (installedByTest != null) ManagedPackageStore.remove(this, installedByTest);
            PackageInstallJobStore.clear(this, successId);
            PackageInstallJobStore.clear(this, failureId);
        }
    }
    private void handleTestPackageRuntimeIntent() {
        if (!getIntent().getBooleanExtra("archphene_test_package_runtime", false)) return;
        new Thread(() -> {
            try {
                if (getIntent().getBooleanExtra("archphene_test_concurrent_packages", false)) {
                    verifyConcurrentPackageIsolation();
                    runOnUiThread(() -> showBanner(
                            "Concurrent package failure isolation passed", false));
                    return;
                }
                if (getIntent().getBooleanExtra("archphene_test_package_jobs", false)) {
                    SearchRanking.verifyForTest();
                    ArchRuntimePolicy.verifyForTest();
                    ArchPackageRuntime.verifySearchParserForTest();
                    ArchPackageRuntime.verifyArchivePathPolicyForTest();
                    PackageInstallJobStore.verifyForTest(this);
                    PackageInstallCoordinator.verifySchedulingForTest();
                    InstalledLinuxAppCatalog.verifyPacmanMetadataForTest();
                    verifyVersionButtonPolicyForTest();
                    ArchPackageClassifier.verifyForTest(this);
                    ManagerStateStore.verifyPendingReinstallForTest(this);
                    ManagedPackageStore.verifyForTest(this);
                    RuntimePackStore.verifyParserForTest();
                    RuntimePackStore.verifyStorageForTest(this);
                    ArchPackageRuntime.verifyStagingCleanupForTest(this);
                    ArchPackageRuntime.verifyDownloadCleanupForTest(this);
                    RuntimePackLeaseRegistry.verifyForTest(this);
                    runOnUiThread(() -> showBanner(
                            "Package job persistence and scheduler passed", false));
                    return;
                }
                if (getIntent().getBooleanExtra("archphene_test_runtime_pack_parser", false)) {
                    android.util.Log.i("ArchphenePackages", "Runtime-pack self-test started");
                    RuntimePackStore.verifyParserForTest();
                    RuntimePackStore.verifyStorageForTest(this);
                    ArchPackageRuntime.verifyStagingCleanupForTest(this);
                    ArchPackageRuntime.verifyDownloadCleanupForTest(this);
                    RuntimePackLeaseRegistry.verifyForTest(this);
                    android.util.Log.i("ArchphenePackages",
                            "Runtime-pack parser, blob storage, staging cleanup, and leases passed");
                    runOnUiThread(() -> showBanner("Runtime-pack parser passed", false));
                    return;
                }
                String removeBinding = getIntent().getStringExtra(
                        "archphene_test_remove_binding");
                if (removeBinding != null) {
                    RuntimePackStore.removeBinding(this, removeBinding);
                    runOnUiThread(() -> showBanner(
                            "Removed runtime binding for " + removeBinding, false));
                    return;
                }
                String statusPack = getIntent().getStringExtra(
                        "archphene_test_runtime_pack_status");
                if (statusPack != null) {
                    boolean exists;
                    try {
                        RuntimePackStore.load(this, statusPack);
                        exists = true;
                    } catch (java.io.FileNotFoundException missing) {
                        exists = false;
                    }
                    boolean leased = RuntimePackLeaseRegistry.isLeased(statusPack);
                    boolean found = exists;
                    runOnUiThread(() -> showBanner("Runtime pack status exists=" + found
                            + " leased=" + leased, false));
                    return;
                }
                if (getIntent().getBooleanExtra(
                        "archphene_test_collect_runtime_packs", false)) {
                    int removed = RuntimePackStore.garbageCollectNow(this);
                    runOnUiThread(() -> showBanner(
                            "Collected " + removed + " runtime packs", false));
                    return;
                }
                String bindPack = getIntent().getStringExtra("archphene_test_bind_pack");
                String bindPackage = getIntent().getStringExtra("archphene_test_bind_package");
                if (bindPack != null || bindPackage != null) {
                    if (bindPack == null || bindPackage == null) {
                        throw new IllegalArgumentException("Runtime-pack binding test is incomplete");
                    }
                    RuntimePackStore.activate(this, bindPackage, bindPack);
                    RuntimePackStore.grantActive(this, bindPackage);
                    runOnUiThread(() -> showBanner("Bound runtime pack to " + bindPackage,
                            false));
                    return;
                }
                String searchPackage = getIntent().getStringExtra("archphene_test_search_package");
                if (searchPackage != null) {
                    List<ArchPackageRepository.PackageResult> matches =
                            ArchPackageRepository.search(this, searchPackage);
                    String first = matches.isEmpty() ? "none"
                            : matches.get(0).repository + "/" + matches.get(0).name + " "
                            + matches.get(0).version + " " + matches.get(0).architecture;
                    android.util.Log.i("ArchphenePackages", "search " + searchPackage + " matches="
                            + matches.size() + " first=" + first);
                    runOnUiThread(() -> showBanner("Search " + searchPackage + " returned "
                            + matches.size() + " packages\n" + first, matches.isEmpty()));
                    return;
                }
                String stagePackage = getIntent().getStringExtra("archphene_test_stage_package");
                if (stagePackage != null) {
                    ArchPackageRuntime.StagedTransaction staged =
                            ArchPackageRuntime.stageTransaction(this, stagePackage);
                    RuntimePackStore.Pack pack = RuntimePackStore.load(this, staged.runtimePackId);
                    if (getIntent().getBooleanExtra(
                            "archphene_test_publish_terminal", false)) {
                        String repository = "";
                        for (ArchPackageRuntime.ResolvedPackage value : staged.packages) {
                            if (stagePackage.equals(value.name)) {
                                repository = value.repository;
                                break;
                            }
                        }
                        if (repository.isEmpty()) {
                            throw new SecurityException("Staged source repository is missing");
                        }
                        ArchPackageRepository.PackageResult source =
                                new ArchPackageRepository.PackageResult(stagePackage, repository,
                                        ArchRuntimePolicy.current().architecture,
                                        staged.sourceVersion(), "", false,
                                        "usr/bin/" + staged.classification.executable,
                                        staged.classification.executable);
                        ManagedPackageStore.Entry entry = ManagedPackageStore.install(
                                this, source, staged);
                        android.util.Log.i("ArchphenePackages", "Terminal catalog published "
                                + entry.identity() + " pack=" + entry.runtimePackId);
                    }
                    long openedBytes = 0;
                    for (RuntimePackStore.Module module : pack.modules) {
                        try (android.os.ParcelFileDescriptor descriptor =
                                getContentResolver().openFileDescriptor(
                                        module.uri(pack.id), "r")) {
                            if (descriptor == null || descriptor.getStatSize() != module.size) {
                                throw new SecurityException("Provider returned wrong module size");
                            }
                            openedBytes += module.size;
                        }
                    }
                    String targetPackage = getIntent().getStringExtra(
                            "archphene_test_activate_android_package");
                    if (targetPackage != null) {
                        RuntimePackStore.activate(this, targetPackage, pack.id);
                        RuntimePackStore.grantActive(this, targetPackage);
                    }
                    long verifiedBytes = openedBytes;
                    android.util.Log.i("ArchphenePackages", "published " + stagePackage
                            + " pack=" + pack.id + " modules=" + pack.modules.size()
                            + " bytes=" + verifiedBytes + " architecture="
                            + ArchRuntimePolicy.current().architecture);
                    runOnUiThread(() -> showBanner("Published " + stagePackage + " runtime pack\n"
                            + pack.modules.size() + " modules, " + verifiedBytes
                            + " bytes verified through provider\n" + pack.id, false));
                    return;
                }
                String resolvePackage = getIntent().getStringExtra("archphene_test_resolve_package");
                String packagePath = getIntent().getStringExtra("archphene_test_package_file");
                String signaturePath = getIntent().getStringExtra("archphene_test_signature_file");
                if (resolvePackage != null) {
                    ArchPackageRuntime.refreshDatabases(this);
                    List<ArchPackageRuntime.ResolvedPackage> resolved =
                            ArchPackageRuntime.resolve(this, resolvePackage);
                    if (getIntent().getBooleanExtra("archphene_test_download_target", false)) {
                        ArchPackageRuntime.ResolvedPackage target = null;
                        for (ArchPackageRuntime.ResolvedPackage value : resolved) {
                            if (value.name.equals(resolvePackage)) { target = value; break; }
                        }
                        if (target == null) throw new IllegalStateException("Resolved target is missing");
                        ArchPackageRuntime.Verification verification =
                                ArchPackageRuntime.downloadAndVerify(this, target);
                        android.util.Log.i("ArchphenePackages", "verified " + resolvePackage
                                + " signer=" + verification.signerFingerprint + " architecture="
                                + ArchRuntimePolicy.current().architecture);
                        runOnUiThread(() -> showBanner("Downloaded and verified " + resolvePackage
                                + "\nSigner " + verification.signerFingerprint, false));
                    } else {
                        android.util.Log.i("ArchphenePackages", "resolved " + resolvePackage
                                + " packages=" + resolved.size() + " architecture="
                                + ArchRuntimePolicy.current().architecture);
                        runOnUiThread(() -> showBanner("Resolved " + resolvePackage + "\n"
                                + resolved.size() + " packages through libalpm", false));
                    }
                } else if (packagePath != null && signaturePath != null) {
                    ArchPackageRuntime.Verification verification = ArchPackageRuntime.verifyPackage(
                            this, new java.io.File(packagePath), new java.io.File(signaturePath));
                    runOnUiThread(() -> showBanner("Verified Arch package\nSigner "
                            + verification.signerFingerprint, false));
                } else {
                    ArchPackageRuntime.Result result = ArchPackageRuntime.pacman(this, "--version");
                    String firstLine = result.output.replace('\r', '\n').trim();
                    android.util.Log.i("ArchphenePackages", "pacman exit=" + result.exitCode
                            + " output=" + firstLine);
                    runOnUiThread(() -> showBanner("Package runtime exit " + result.exitCode
                            + "\n" + firstLine, result.exitCode != 0));
                }
            } catch (Exception e) {
                android.util.Log.e("ArchphenePackages", "Package runtime test failed: "
                        + e.getClass().getName() + ": " + e.getMessage(), e);
                runOnUiThread(() -> showBanner("Package runtime failed: " + e.getMessage(), true));
            }
        }, "archphene-package-runtime-test").start();
    }

    private void handleTestInstallIntent() {
        String url = getIntent().getStringExtra("archphene_test_apk_url");
        String hash = getIntent().getStringExtra("archphene_test_apk_sha256");
        String packageName = getIntent().getStringExtra("archphene_test_apk_package");
        if (url != null && hash != null && packageName != null) {
            activeInstallPackage = packageName;
            activeInstallOperation = ApkUpdateInstaller.installWithProgress(this, url, hash,
                    packageName, (phase, percent, status, terminal) -> {
                        activeInstallPhase = phase;
                        activeInstallPercent = percent;
                        activeInstallStatus = status;
                        if (terminal) {
                            activeInstallOperation = null;
                            activeInstallPackage = "";
                            showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                            if (currentPage == 0) renderAppList();
                        } else {
                            renderProgressAtStableCadence(() -> {
                                if (currentPage == 0) renderAppList();
                            });
                        }
                    });
        }
    }

    private void showBanner(String message, boolean error) {
        statusBanner.setText(message);
        statusBanner.setTextColor(error ? Color.WHITE : COLOR_TEXT);
        statusBanner.setBackgroundColor(error ? COLOR_ERROR : COLOR_SURFACE_ACTIVE);
        statusBanner.setVisibility(View.VISIBLE);
    }

    private void showPackageCompatibilityNotice() {
        String issue = ArchRuntimePolicy.packageTransactionIssue();
        if (!issue.isEmpty()) showBanner(issue, false);
    }
    private void updateNavigationBadge() {
        appsNav.setText("Apps");
    }

    private void setNavigationSelection(boolean appsSelected) {
        appsNav.setBackground(rounded(appsSelected ? COLOR_SURFACE_ACTIVE : Color.TRANSPARENT, 12));
        settingsNav.setBackground(rounded(appsSelected ? Color.TRANSPARENT : COLOR_SURFACE_ACTIVE, 12));
        appsNav.setTextColor(appsSelected ? COLOR_PRIMARY : COLOR_MUTED);
        settingsNav.setTextColor(appsSelected ? COLOR_MUTED : COLOR_PRIMARY);
        setTopDrawable(appsNav, appsSelected ? R.drawable.nav_apps : R.drawable.nav_apps_outlined);
        setTopDrawable(settingsNav, appsSelected
                ? R.drawable.nav_settings : R.drawable.nav_settings_outlined);
        tintTopDrawable(appsNav, appsSelected ? COLOR_PRIMARY : COLOR_MUTED);
        tintTopDrawable(settingsNav, appsSelected ? COLOR_MUTED : COLOR_PRIMARY);
    }

    private void setAddVisible(boolean visible) {
        if (addFab != null) addFab.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void setTopDrawable(TextView view, int icon) {
        Drawable drawable = getDrawable(icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setBounds(0, 0, dp(19), dp(19));
        }
        view.setCompoundDrawables(null, drawable, null, null);
    }

    private void tintTopDrawable(TextView view, int color) {
        Drawable drawable = view.getCompoundDrawables()[1];
        if (drawable != null) drawable.setTint(color);
    }
    private TextView navigationItem(int icon, String label, Runnable action) {
        TextView item = text(label, 11, COLOR_MUTED);
        Drawable drawable = getDrawable(icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setBounds(0, 0, dp(19), dp(19));
            item.setCompoundDrawables(null, drawable, null, null);
        }
        item.setCompoundDrawablePadding(dp(2));
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(8), 0, 0);
        item.setOnClickListener(view -> action.run());
        return item;
    }

    private LinearLayout section() {
        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(dp(16), dp(16), dp(16), dp(16));
        section.setBackground(rounded(COLOR_SURFACE, 8));
        return section;
    }

    private LinearLayout verticalSection() {
        LinearLayout section = section();
        section.setOrientation(LinearLayout.VERTICAL);
        section.setGravity(Gravity.NO_GRAVITY);
        return section;
    }

    private View detailLine(String label, String value) {
        return detailLine(label, value, 0);
    }

    private View detailLine(String label, String value, int icon) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        TextView key = text(label, 14, COLOR_MUTED);
        row.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView data = text(value, 14, icon == 0 ? COLOR_TEXT : COLOR_WARNING);
        data.setGravity(Gravity.END);
        data.setMaxLines(2);
        if (icon != 0) {
            data.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
            data.setCompoundDrawablePadding(dp(5));
            data.setContentDescription(value + ", newer version available");
        }
        row.addView(data, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private Button subtleBackButton() {
        Button button = new Button(this);
        button.setText("Apps");
        button.setTextSize(13);
        button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.back_arrow, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(5));
        button.setAllCaps(false);
        button.setTextColor(COLOR_MUTED);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(COLOR_MUTED));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((COLOR_PRIMARY & 0x00ffffff) | 0x22000000),
                rounded(Color.TRANSPARENT, 4), null));
        return button;
    }
    private Button actionButton(String label, int icon) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 26) {
            button.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        button.setAllCaps(false);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(7));
        styleTonalButton(button);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private void styleTonalButton(Button button) {
        int fill = darkTheme ? Color.rgb(41, 47, 52) : Color.WHITE;
        button.setTextColor(COLOR_PRIMARY);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((COLOR_PRIMARY & 0x00ffffff) | 0x33000000),
                rounded(fill, 18), null));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    private void stylePrimaryButton(Button button) {
        int foreground = darkTheme ? Color.rgb(15, 35, 44) : Color.WHITE;
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((foreground & 0x00ffffff) | 0x33000000),
                rounded(COLOR_PRIMARY, 18), null));
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private String statusText(ManagerStateStore.Snapshot state) {
        switch (state.status) {
            case "checking": return "Checking official package source...";
            case "update": return "Update available: " + state.availableVersion;
            case "current": return "Available: " + state.availableVersion + " | Up to date";
            case "error": return "Update check failed: " + state.error;
            default: return state.checkedAt == 0 ? "Not checked yet" : "Last result unavailable";
        }
    }

    private int stateColor(ManagerStateStore.Snapshot state) {
        switch (state.status) {
            case "update": return COLOR_WARNING;
            case "current": return COLOR_SUCCESS;
            case "error": return COLOR_ERROR;
            default: return COLOR_MUTED;
        }
    }

    private String formatCheckedAt(long checkedAt) {
        if (checkedAt == 0) return "Never";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(checkedAt));
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spacedWrap(int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        return params;
    }

    private FrameLayout.LayoutParams frameMatch() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int width = widthDp >= 600 ? dp(Math.min(720, widthDp))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }
}
