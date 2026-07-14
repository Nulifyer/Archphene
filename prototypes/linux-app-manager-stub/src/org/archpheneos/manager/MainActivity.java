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

public final class MainActivity extends Activity {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        LinuxAppManagerService.schedule(this, ManagerStateStore.backgroundChecksEnabled(this));
        showAppsPage();
        if (ManagerStateStore.checkOnLaunch(this)) checkAll();
        handleTestInstallIntent();
        handleTestPackageRuntimeIntent();
        handleTestWrapperSigningIntent();
        handleTestWrapperAssemblyIntent();
        handleTestGitHubReleaseIntent();
        handleTestRuntimeModuleIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTestRuntimeModuleIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null && currentPage == 0) loadCatalog();
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

        TextView title = text("Apps", 28, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
        searchRow.addView(filter, new LinearLayout.LayoutParams(dp(52), dp(44)));        page.addView(searchRow, matchWrap());

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

    private void renderAppList() {
        if (appList == null) return;
        appList.removeAllViews();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        String listFilter = ManagerStateStore.listFilter(this);
        Set<String> installedSources = new HashSet<>();
        for (InstalledLinuxAppCatalog.Entry app : apps) {
            ManagerStateStore.Snapshot state = ManagerStateStore.read(this, app.packageName);
            boolean matches = normalized.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(normalized)
                    || app.sourceId.toLowerCase(Locale.ROOT).contains(normalized)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(normalized);
            installedSources.add(app.sourceId);
            boolean hiddenByFilter = "updates".equals(listFilter) && !state.updateAvailable
                    || "pinned".equals(listFilter) && !ManagerStateStore.isPinned(this, app.packageName);
            if (!matches || hiddenByFilter) continue;
            appList.addView(createAppRow(app, state), spacedWrap(dp(6)));
            shown++;
        }
        for (ArchPackageRepository.PackageResult tracked : TrackedPackageStore.list(this)) {
            if (installedSources.contains(tracked.name)) continue;
            boolean matches = normalized.isEmpty()
                    || tracked.name.toLowerCase(Locale.ROOT).contains(normalized)
                    || tracked.description.toLowerCase(Locale.ROOT).contains(normalized);
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
        int iconSize = dp(44);
        top.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(app.label, 15, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        details.addView(name, matchWrap());
        details.addView(text(app.sourceType + " | " + app.sourceId,
                11, COLOR_MUTED), matchWrap());
        String runtime = app.runtimeAbi;
        String pinnedVersion = ManagerStateStore.pinnedVersion(this, app.packageName);
        TextView runtimeView = text(runtime, 10,
                pinnedVersion.isEmpty() ? COLOR_MUTED : COLOR_WARNING);
        if (!pinnedVersion.isEmpty()) {
            runtimeView.setText(runtime + " | " + pinnedVersion
                    + ("bad".equals(ManagerStateStore.versionHealth(this, app.packageName,
                    pinnedVersion)) ? " | Reported bad" : ""));
            runtimeView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.version_pinned, 0, 0, 0);
            runtimeView.setCompoundDrawablePadding(dp(4));
            runtimeView.setContentDescription("Pinned to " + pinnedVersion + ". " + runtime);
        }
        details.addView(runtimeView, matchWrap());
        top.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean installing = app.packageName.equals(activeInstallPackage)
                && activeInstallOperation != null;
        if (installing) {
            top.addView(installingAction(activeInstallPhase == ApkUpdateInstaller.Phase.DOWNLOAD
                    ? "Updating " + activeInstallPercent + "%" : "Updating..."),
                    new LinearLayout.LayoutParams(dp(126), dp(42)));
        } else {
            Button version = actionButton(versionButtonText(app, state), versionButtonIcon(state));
            version.setAllCaps(false);
            version.setContentDescription(versionButtonDescription(app, state));
            version.setEnabled(!app.updateUrl.isEmpty() && !"checking".equals(state.status));
            version.setOnClickListener(view -> checkOne(app));
            styleVersionButton(version, state);
            top.addView(version, new LinearLayout.LayoutParams(dp(126), dp(42)));
        }
        card.addView(top, matchWrap());
        if (installing) {
            LinearLayout transfer = new LinearLayout(this);
            transfer.setGravity(Gravity.CENTER_VERTICAL);
            TwoStageProgressView progress = new TwoStageProgressView(this,
                    COLOR_PRIMARY, COLOR_MUTED);
            progress.setState(activeInstallPhase, activeInstallPercent, activeInstallStatus);
            transfer.addView(progress, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (activeInstallOperation.canCancel()) {
                Button cancel = actionButton("Cancel", android.R.drawable.ic_menu_close_clear_cancel);
                cancel.setOnClickListener(view -> {
                    if (activeInstallOperation != null) activeInstallOperation.cancel();
                });
                transfer.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(38)));
            }
            card.addView(transfer, spacedWrap(dp(4)));
        }

        return card;
    }

    private View createTrackedRow(ArchPackageRepository.PackageResult app) {
        String stateKey = "tracked:" + app.name + ":" + app.architecture;
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
        details.addView(text(pinned.isEmpty() ? "Not installed" : "Not installed | Pinned " + pinned,
                10, pinned.isEmpty() ? COLOR_MUTED : COLOR_WARNING), matchWrap());
        top.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button version = actionButton(app.version, android.R.drawable.stat_sys_download_done);
        version.setEnabled(false);
        top.addView(version, new LinearLayout.LayoutParams(dp(126), dp(42)));
        card.addView(top, matchWrap());
        if (stateKey.equals(activeInstallPackage) && activeInstallOperation != null) {
            LinearLayout transfer = new LinearLayout(this);
            transfer.setGravity(Gravity.CENTER_VERTICAL);
            TwoStageProgressView progress = new TwoStageProgressView(this,
                    COLOR_PRIMARY, COLOR_MUTED);
            progress.setState(activeInstallPhase, activeInstallPercent, activeInstallStatus);
            transfer.addView(progress, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (activeInstallOperation.canCancel()) {
                Button cancel = actionButton("Cancel", android.R.drawable.ic_menu_close_clear_cancel);
                cancel.setOnClickListener(view -> activeInstallOperation.cancel());
                transfer.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(38)));
            }
            card.addView(transfer, spacedWrap(dp(4)));
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
        currentPage = 3;
        setAddVisible(false);
        setNavigationSelection(true);
        content.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), 0);
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
    }

    private void searchPackages(String query, LinearLayout results, ProgressBar progress) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            Toast.makeText(this, "Enter at least two characters", Toast.LENGTH_SHORT).show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        results.removeAllViews();
        new Thread(() -> {
            try {
                List<ArchPackageRepository.PackageResult> found =
                        ArchPackageRepository.search(this, normalized);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
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
                    progress.setVisibility(View.GONE);
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
        if (app.flaggedOutOfDate) row.addView(text("Flagged out of date", 11, COLOR_WARNING), matchWrap());
        row.setOnClickListener(view -> showPackageResultDetail(app, false));
        return row;
    }

    private void showPackageResultDetail(ArchPackageRepository.PackageResult app, boolean tracked) {
        currentPage = 4;
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
            if (tracked) TrackedPackageStore.remove(this, app.name, app.architecture);
            else TrackedPackageStore.add(this, app);
            showAppsPage();
        });
        page.addView(action, spacedWrap(dp(12)));
        Button install = actionButton("Install", android.R.drawable.stat_sys_download_done);
        boolean supported = "x86_64".equals(app.architecture)
                && java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("x86_64");
        install.setEnabled(supported);
        install.setContentDescription(supported
                ? "Resolve, verify, build, and install " + app.name
                : app.name + " is not available for this device architecture");
        install.setOnClickListener(view -> startOnDevicePackageInstall(app));
        page.addView(install, spacedWrap(dp(8)));
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
                    } else if (currentPage == 0) {
                        renderAppList();
                    }
                });
        renderAppList();
    }
    private void startOnDevicePackageInstall(ArchPackageRepository.PackageResult source) {
        TrackedPackageStore.add(this, source);
        showAppsPage();
        String stateKey = "tracked:" + source.name + ":" + source.architecture;
        ApkUpdateInstaller.Operation buildOperation = new ApkUpdateInstaller.Operation();
        activeInstallPackage = stateKey;
        activeInstallOperation = buildOperation;
        activeInstallPhase = ApkUpdateInstaller.Phase.DOWNLOAD;
        activeInstallPercent = 5;
        activeInstallStatus = "Resolving signed Arch transaction";
        renderAppList();
        Thread worker = new Thread(() -> {
            try {
                ArchPackageRuntime.StagedTransaction staged =
                        ArchPackageRuntime.stageTransaction(this, source.name);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                runOnUiThread(() -> {
                    activeInstallPercent = 70;
                    activeInstallStatus = "Building Android wrapper";
                    if (currentPage == 0) renderAppList();
                });
                ArchWrapperAssembler.Result result = ArchWrapperAssembler.assembleQt(
                        this, source.repository, source.name, staged.root);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                runOnUiThread(() -> {
                    activeInstallPhase = ApkUpdateInstaller.Phase.INSTALL;
                    activeInstallPercent = 0;
                    activeInstallStatus = "Verifying generated APK";
                    activeInstallOperation = ApkUpdateInstaller.installWithProgress(this,
                            result.apk.toURI().toString(), result.apkSha256,
                            result.packageName, result.signerSha256,
                            (phase, percent, status, terminal) -> {
                                activeInstallPhase = phase;
                                activeInstallPercent = percent;
                                activeInstallStatus = status;
                                if (terminal) {
                                    if (phase == ApkUpdateInstaller.Phase.COMPLETE) {
                                        TrackedPackageStore.remove(this, source.name,
                                                source.architecture);
                                    }
                                    activeInstallOperation = null;
                                    activeInstallPackage = "";
                                    showBanner(status, phase == ApkUpdateInstaller.Phase.ERROR);
                                    loadCatalog();
                                } else if (currentPage == 0) {
                                    renderAppList();
                                }
                            });
                    if (currentPage == 0) renderAppList();
                });
            } catch (InterruptedException cancelled) {
                runOnUiThread(() -> {
                    activeInstallOperation = null;
                    activeInstallPackage = "";
                    showBanner("Package install cancelled", false);
                    if (currentPage == 0) renderAppList();
                });
            } catch (Exception error) {
                android.util.Log.e("ArchpheneManager", "On-device package install failed", error);
                runOnUiThread(() -> {
                    activeInstallOperation = null;
                    activeInstallPackage = "";
                    showBanner("Install failed: " + error.getMessage(), true);
                    if (currentPage == 0) renderAppList();
                });
            }
        }, "archphene-package-install");
        buildOperation.setCancellationHook(worker::interrupt);
        worker.start();
    }
    private View installingAction(String label) {
        LinearLayout action = new LinearLayout(this);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(8), 0, dp(8), 0);
        action.setBackground(rounded(COLOR_SURFACE_ACTIVE, 18));
        ProgressBar spinner = new ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        action.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView status = text(label, 11, COLOR_PRIMARY);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(6);
        action.addView(status, textParams);
        action.setContentDescription(label);
        return action;
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
        currentPage = 2;
        setAddVisible(false);
        content.removeAllViews();
        ManagerStateStore.Snapshot state = ManagerStateStore.read(this, app.packageName);
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
        installVersion.setOnClickListener(view -> installSelectedVersion(app, selectedVersion[0]));
        versions.addView(installVersion, spacedWrap(dp(8)));
        versionSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                selectedVersion[0] = versionValues.get(position);
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
        LinearLayout source = verticalSection();
        source.addView(detailLine("Package source", app.sourceId));
        source.addView(detailLine("Runtime", app.runtimeAbi));
        source.addView(detailLine("Android package", app.packageName));
        page.addView(source, spacedWrap(dp(6)));

        LinearLayout actions = verticalSection();
        Button launch = actionButton("Launch", android.R.drawable.ic_media_play);
        stylePrimaryButton(launch);
        launch.setOnClickListener(view -> startActivity(app.launchIntent));
        actions.addView(launch, matchWrap());
        Button check = actionButton("Check for update", android.R.drawable.ic_popup_sync);
        check.setOnClickListener(view -> LinuxAppUpdateCoordinator.checkOne(this, app,
                (updated, next, completed, total) -> {
                    if (completed == total) showAppDetail(app);
                }));
        actions.addView(check, spacedWrap(dp(8)));
        Button androidSettings = actionButton("Android app settings", android.R.drawable.ic_menu_manage);
        androidSettings.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.packageName))));
        actions.addView(androidSettings, spacedWrap(dp(8)));
        Button uninstall = actionButton("Uninstall app", android.R.drawable.ic_menu_delete);
        uninstall.setTextColor(COLOR_ERROR);
        uninstall.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + app.packageName))));
        actions.addView(uninstall, spacedWrap(dp(8)));
        page.addView(actions, spacedWrap(dp(6)));
        scroll.addView(page);
        content.addView(scroll, frameMatch());
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
        if (version.equals(availableVersion)) return prefix + "Current repository version";
        if (version.equals(installedVersion)) return prefix + "Installed version";
        return prefix + "Archived version; compatibility not verified";
    }

    private void installSelectedVersion(InstalledLinuxAppCatalog.Entry app, String version) {
        String pinned = ManagerStateStore.pinnedVersion(this, app.packageName);
        if (app.sourceVersion.equals(version)) {
            if (!pinned.isEmpty() && !pinned.equals(version)) {
                ManagerStateStore.setPinnedVersion(this, app.packageName, version);
            }
            showBanner(app.label + " " + version + " is already installed", false);
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
                    } else if (currentPage == 0) {
                        renderAppList();
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
                    } else if (currentPage == 0) {
                        renderAppList();
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
            ManagedRepositoryStore.clearCache(this);
            showAppsPage();
            checkAll();
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

    private void handleTestRuntimeModuleIntent() {
        String packageName = getIntent().getStringExtra(
                "archphene_test_runtime_module_package");
        if (packageName == null) return;
        String action = getIntent().getStringExtra("archphene_test_runtime_module_action");
        try {
            if ("launch".equals(action)) {
                Intent launch = new Intent(Intent.ACTION_VIEW, RuntimeModuleProvider.PROBE_URI);
                launch.setClassName(packageName, packageName + ".MainActivity");
                launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch.putExtra("archphene_test_runtime_module_uri",
                        RuntimeModuleProvider.PROBE_URI.toString());
                startActivity(launch);
                android.util.Log.i("ArchpheneRuntime", "Launched runtime module for "
                        + packageName);
            } else if ("revoke".equals(action)) {
                RuntimeModuleProvider.revokeProbe(this);
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
    private void handleTestWrapperAssemblyIntent() {
        String sourcePackage = getIntent().getStringExtra("archphene_test_assemble_qt");
        if (sourcePackage == null) return;
        new Thread(() -> {
            try {
                java.io.File runtimeRoot = null;
                if (getIntent().getBooleanExtra("archphene_test_stage_transaction", false)) {
                    runOnUiThread(() -> showBanner("Resolving and verifying " + sourcePackage, false));
                    runtimeRoot = ArchPackageRuntime.stageTransaction(this, sourcePackage).root;
                }
                ArchWrapperAssembler.Result result = ArchWrapperAssembler.assembleQt(
                        this, "extra", sourcePackage, runtimeRoot);
                if (getIntent().getBooleanExtra("archphene_test_install_assembled", false)) {
                    runOnUiThread(() -> {
                        showBanner("Generated " + result.packageName + "\nOpening Android installer", false);
                        ApkUpdateInstaller.installWithProgress(this, result.apk.toURI().toString(),
                                result.apkSha256, result.packageName, result.signerSha256,
                                (phase, percent, status, terminal) -> {
                                    if (terminal) showBanner(status,
                                            phase == ApkUpdateInstaller.Phase.ERROR);
                                });
                    });
                } else {
                    runOnUiThread(() -> showBanner("Generated wrapper APK\nPackage "
                            + result.packageName + "\nSigner " + result.signerSha256, false));
                }
            } catch (Exception e) {
                android.util.Log.e("ArchpheneManager", "Wrapper assembly failed", e);
                runOnUiThread(() -> showBanner("Wrapper assembly failed: " + e.getMessage(), true));
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

    private void handleTestPackageRuntimeIntent() {
        if (!getIntent().getBooleanExtra("archphene_test_package_runtime", false)) return;
        new Thread(() -> {
            try {
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
                        runOnUiThread(() -> showBanner("Downloaded and verified " + resolvePackage
                                + "\nSigner " + verification.signerFingerprint, false));
                    } else {
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
                    runOnUiThread(() -> showBanner("Package runtime exit " + result.exitCode
                            + "\n" + firstLine, result.exitCode != 0));
                }
            } catch (Exception e) {
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
                        }
                        if (currentPage == 0) renderAppList();
                    });
        }
    }

    private void showBanner(String message, boolean error) {
        statusBanner.setText(message);
        statusBanner.setTextColor(error ? Color.WHITE : COLOR_TEXT);
        statusBanner.setBackgroundColor(error ? COLOR_ERROR : COLOR_SURFACE_ACTIVE);
        statusBanner.setVisibility(View.VISIBLE);
    }

    private void updateNavigationBadge() {
        int updates = countUpdates();
        appsNav.setText(updates == 0 ? "Apps" : "Apps (" + updates + ")");
    }

    private int countUpdates() {
        int count = 0;
        for (InstalledLinuxAppCatalog.Entry entry : apps) {
            if (ManagerStateStore.read(this, entry.packageName).updateAvailable) count++;
        }
        return count;
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
        item.setPadding(0, dp(4), 0, 0);
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
        button.setTextColor(COLOR_PRIMARY);
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
        button.setAllCaps(false);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(7));
        styleTonalButton(button);
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