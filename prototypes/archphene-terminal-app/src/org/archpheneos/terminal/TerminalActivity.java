package org.archpheneos.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** PTY-backed launcher surface for managed command-line packages. */
public final class TerminalActivity extends Activity
        implements TerminalViewClient, TerminalService.Client {
    private static final String TAG = "ArchpheneTerminal";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 70;
    private LinearLayout root;
    private LinearLayout toolbar;
    private LinearLayout tabs;
    private HorizontalScrollView tabScroller;
    private TerminalView terminalView;
    private TextView title;
    private TextView addButton;
    private TextView closeButton;
    private TerminalService terminalService;
    private boolean serviceBound;
    private FileObserver requestObserver;
    private TerminalDocumentBridge documentBridge;
    private Bundle restoredState;
    private int fontPixels;
    private int background;
    private int foreground;
    private int surface;
    private int activeSurface;
    private boolean debugSessionTestStarted;
    private boolean debugCommandTestStarted;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            terminalService = ((TerminalService.LocalBinder) binder).service();
            serviceBound = true;
            terminalService.attachClient(TerminalActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            terminalService = null;
            stopRequestObserver();
            renderServiceState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredState = savedInstanceState;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        applyPalette();
        configureTerminalColors(isDark());
        buildUi();
        requestNotificationPermission();
        startAndBindService();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(surface);
        title = new TextView(this);
        title.setText("Preparing Archphene Terminal");
        title.setTextSize(15);
        title.setTextColor(foreground);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(14), 0, dp(8), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        addButton = toolbarAction("+", "New terminal session", 24);
        addButton.setOnClickListener(view -> {
            if (terminalService == null) return;
            try {
                terminalService.createSession();
            } catch (Exception error) {
                reportBridgeMessage(safeMessage(error));
            }
        });
        toolbar.addView(addButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        closeButton = toolbarAction("x", "Close current terminal session", 19);
        closeButton.setOnClickListener(view -> {
            if (terminalService == null || terminalService.activeHandle() == null) return;
            terminalService.close(terminalService.activeHandle());
        });
        toolbar.addView(closeButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        tabScroller = new HorizontalScrollView(this);
        tabScroller.setHorizontalScrollBarEnabled(false);
        tabScroller.setFillViewport(true);
        tabScroller.setBackgroundColor(surface);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(8), dp(4), dp(8), dp(4));
        tabScroller.addView(tabs, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(this);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setBackgroundColor(isDark() ? Color.rgb(13, 15, 17) : Color.WHITE);
        fontPixels = Math.round(16f * getResources().getDisplayMetrics().scaledDensity);
        terminalView.setTextSize(fontPixels);
        root.addView(terminalView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private TextView toolbarAction(String text, String description, float textSize) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextSize(textSize);
        action.setTextColor(foreground);
        action.setGravity(Gravity.CENTER);
        action.setContentDescription(description);
        action.setFocusable(true);
        action.setClickable(true);
        action.setBackgroundColor(Color.TRANSPARENT);
        return action;
    }

    private void startAndBindService() {
        Intent service = new Intent(this, TerminalService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            bindService(service, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception error) {
            Log.e(TAG, "Could not start terminal service", error);
            title.setText("Terminal unavailable: " + safeMessage(error));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyPalette();
        configureTerminalColors(isDark());
        root.setBackgroundColor(background);
        toolbar.setBackgroundColor(surface);
        tabScroller.setBackgroundColor(surface);
        title.setTextColor(foreground);
        addButton.setTextColor(foreground);
        closeButton.setTextColor(foreground);
        terminalView.setBackgroundColor(isDark() ? Color.rgb(13, 15, 17) : Color.WHITE);
        renderServiceState();
        terminalView.invalidate();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (documentBridge != null) documentBridge.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (documentBridge == null
                || !documentBridge.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        stopRequestObserver();
        if (terminalService != null) terminalService.detachClient(this);
        if (serviceBound) unbindService(serviceConnection);
        serviceBound = false;
        terminalService = null;
        super.onDestroy();
    }

    @Override
    public void onServiceStateChanged() {
        renderServiceState();
        if (terminalService == null || terminalService.home() == null) return;
        if (documentBridge == null) {
            try {
                documentBridge = new TerminalDocumentBridge(this, terminalService.home(),
                        this::reportBridgeMessage, restoredState);
                restoredState = null;
            } catch (java.io.IOException error) {
                Log.e(TAG, "Could not prepare document bridge", error);
                reportBridgeMessage("Document bridge unavailable: " + safeMessage(error));
            }
        }
        File requests = terminalService.requestDirectory();
        if (requestObserver == null && requests != null) observeManagerRequests(requests);
        runDebugSessionTestIfRequested();
        runDebugCommandTestIfRequested();
    }

    private void renderServiceState() {
        if (title == null) return;
        tabs.removeAllViews();
        if (terminalService == null) {
            title.setText("Connecting to Archphene Terminal");
            closeButton.setEnabled(false);
            return;
        }
        Throwable error = terminalService.preparationError();
        if (error != null) {
            title.setText("Terminal unavailable: " + safeMessage(error));
            closeButton.setEnabled(false);
            return;
        }
        List<TerminalService.SessionInfo> infos = terminalService.sessionInfos();
        String active = terminalService.activeHandle();
        TerminalService.SessionInfo activeInfo = null;
        for (TerminalService.SessionInfo info : infos) {
            boolean selected = info.handle.equals(active);
            if (selected) activeInfo = info;
            TextView tab = new TextView(this);
            tab.setText(info.title);
            tab.setTextSize(14);
            tab.setTextColor(foreground);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.setMinWidth(dp(72));
            tab.setMaxWidth(dp(160));
            tab.setPadding(dp(14), 0, dp(14), 0);
            tab.setContentDescription((selected ? "Current session " : "Switch to ")
                    + info.title);
            tab.setBackground(new ColorDrawable(selected ? activeSurface : Color.TRANSPARENT));
            tab.setOnClickListener(view -> terminalService.activate(info.handle));
            LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            parameters.setMarginEnd(dp(4));
            tabs.addView(tab, parameters);
        }
        TerminalSession session = terminalService.activeSession();
        terminalView.attachSession(session);
        if (activeInfo != null) {
            title.setText(activeInfo.title);
        } else if (terminalService.isPreparing()) {
            title.setText("Preparing Archphene Terminal");
        } else {
            title.setText("No terminal sessions");
        }
        closeButton.setEnabled(session != null);
        terminalView.requestFocus();
    }

    private void runDebugSessionTestIfRequested() {
        if (debugSessionTestStarted || terminalService == null
                || (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        int requested = getIntent().getIntExtra("archphene_test_terminal_sessions", 0);
        if (requested < 1) return;
        requested = Math.min(requested, 8);
        debugSessionTestStarted = true;
        while (terminalService.sessionInfos().size() < requested) {
            terminalService.createSession();
        }
        int expected = requested;
        root.postDelayed(() -> {
            List<TerminalService.SessionInfo> infos = terminalService == null
                    ? java.util.Collections.emptyList() : terminalService.sessionInfos();
            StringBuilder pids = new StringBuilder();
            for (TerminalService.SessionInfo info : infos) {
                if (pids.length() > 0) pids.append(',');
                pids.append(info.pid);
            }
            Log.i(TAG, "Terminal session probe count=" + infos.size()
                    + " expected=" + expected + " pids=" + pids);
            if (getIntent().getBooleanExtra(
                    "archphene_test_finish_activity", false)) finish();
        }, 1500);
    }

    private void runDebugCommandTestIfRequested() {
        if (debugCommandTestStarted || terminalService == null
                || (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String command = getIntent().getStringExtra("archphene_test_terminal_command");
        if (command == null || command.isEmpty()) return;
        if (command.length() > 512 || command.indexOf((char) 10) >= 0
                || command.indexOf((char) 13) >= 0) {
            throw new SecurityException("Invalid Terminal test command");
        }
        TerminalSession session = terminalService.activeSession();
        if (session == null || session.getEmulator() == null) return;
        debugCommandTestStarted = true;
        int delay = Math.max(1000, Math.min(300000,
                getIntent().getIntExtra("archphene_test_terminal_capture_delay_ms", 30000)));
        session.write(command + (char) 13);
        root.postDelayed(() -> {
            TerminalSession active = terminalService == null
                    ? null : terminalService.activeSession();
            if (active == null || active.getEmulator() == null) {
                Log.e(TAG, "Terminal command probe lost its session");
                return;
            }
            String transcript = active.getEmulator().getScreen().getTranscriptText();
            Log.i(TAG, "Terminal command probe transcript=" + transcript);
        }, delay);
    }
    private void observeManagerRequests(File requestDirectory) {
        stopRequestObserver();
        requestObserver = new FileObserver(requestDirectory.getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                if (path == null || !path.matches("[a-zA-Z0-9._-]{1,64}\\.request")) return;
                File request = new File(requestDirectory, path);
                try {
                    File canonicalRequest = request.getCanonicalFile();
                    if (!requestDirectory.getCanonicalFile().equals(
                            canonicalRequest.getParentFile())) {
                        throw new SecurityException("Request escaped its directory");
                    }
                    request = canonicalRequest;
                    if (!request.isFile() || request.length() > 4096) {
                        throw new SecurityException("Request is too large");
                    }
                    byte[] bytes;
                    try (FileInputStream input = new FileInputStream(request)) {
                        bytes = new byte[(int) request.length()];
                        int offset = 0;
                        while (offset < bytes.length) {
                            int count = input.read(bytes, offset, bytes.length - offset);
                            if (count < 0) break;
                            offset += count;
                        }
                    }
                    if (!request.delete()) throw new IllegalStateException("Could not consume request");
                    String value = new String(bytes, StandardCharsets.UTF_8).trim();
                    String[] fields = value.split("\\t", 4);
                    String fileId = path.substring(0, path.length() - ".request".length());
                    if (fields.length != 4 || !"v1".equals(fields[0])
                            || !fileId.equals(fields[1])
                            || !fields[1].matches("[a-zA-Z0-9._-]{1,64}")
                            || !fields[2].matches("search|install|remove|upgrade|import|export")
                            || fields[3].length() > 512
                            || fields[3].contains("\n") || fields[3].contains("\r")) {
                        throw new SecurityException("Invalid terminal request");
                    }
                    runOnUiThread(() -> dispatchManagerRequest(
                            fields[1], fields[2], fields[3]));
                } catch (Exception error) {
                    Log.e(TAG, "Rejected terminal manager request", error);
                }
            }
        };
        requestObserver.startWatching();
    }

    private void dispatchManagerRequest(String requestId, String action, String query) {
        try {
            if ("import".equals(action) || "export".equals(action)) {
                if (documentBridge == null) {
                    throw new IllegalStateException("Document bridge is unavailable");
                }
                documentBridge.request(action, query);
                TerminalCommandProvider.publish(this, requestId, action, 100,
                        true, "success", "Android document picker opened");
            } else {
                Intent manager = new Intent("org.archpheneos.action.TERMINAL_REQUEST")
                        .setClassName("org.archpheneos.manager",
                                "org.archpheneos.manager.TerminalRequestActivity")
                        .putExtra("archphene_terminal_request_id", requestId)
                        .putExtra("archphene_terminal_action", action)
                        .putExtra("archphene_terminal_query", query)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(manager);
            }
        } catch (Exception error) {
            Log.e(TAG, "Rejected terminal manager request", error);
            try {
                TerminalCommandProvider.publish(this, requestId, action, 0,
                        true, "error", safeMessage(error));
            } catch (Exception publishError) {
                Log.e(TAG, "Could not publish Terminal request failure", publishError);
            }
        }
    }
    private void stopRequestObserver() {
        if (requestObserver != null) requestObserver.stopWatching();
        requestObserver = null;
    }

    private void reportBridgeMessage(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> {
            TerminalSession session = terminalService == null
                    ? null : terminalService.activeSession();
            if (session == null || session.getEmulator() == null) return;
            byte[] output = ("\r\narchphene: " + message + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            session.getEmulator().append(output, output.length);
            terminalView.onScreenUpdated();
        });
    }

    private void applyPalette() {
        boolean dark = isDark();
        background = dark ? Color.rgb(17, 20, 23) : Color.rgb(248, 250, 252);
        foreground = dark ? Color.rgb(240, 245, 247) : Color.rgb(31, 37, 41);
        surface = dark ? Color.rgb(29, 34, 38) : Color.rgb(232, 237, 240);
        activeSurface = dark ? Color.rgb(22, 58, 77) : Color.rgb(216, 238, 248);
    }

    private void configureTerminalColors(boolean dark) {
        Properties colors = new Properties();
        colors.setProperty("background", dark ? "#0d0f11" : "#ffffff");
        colors.setProperty("foreground", dark ? "#f0f5f7" : "#1f2529");
        colors.setProperty("cursor", "#1793d1");
        TerminalColors.COLOR_SCHEME.updateWith(colors);
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void showKeyboard() {
        terminalView.requestFocus();
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public void onTerminalTextChanged(TerminalSession session) {
        if (terminalService != null && session == terminalService.activeSession()) {
            terminalView.onScreenUpdated();
        }
    }
    @Override public void onTerminalCopyRequested(TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }
    @Override public void onTerminalPasteRequested(TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text != null) session.write(text.toString());
        }
    }
    @Override public void onTerminalColorsChanged(TerminalSession session) {
        if (terminalService != null && session == terminalService.activeSession()) {
            terminalView.invalidate();
        }
    }

    @Override public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            float density = getResources().getDisplayMetrics().scaledDensity;
            int minimum = Math.round(10 * density);
            int maximum = Math.round(32 * density);
            fontPixels = Math.max(minimum, Math.min(maximum,
                    fontPixels + (scale > 1 ? Math.round(density) : -Math.round(density))));
            terminalView.setTextSize(fontPixels);
            return 1f;
        }
        return scale;
    }
    @Override public void onSingleTapUp(MotionEvent event) { showKeyboard(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return terminalView.hasFocus(); }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
        return false;
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean control, TerminalSession session) {
        return false;
    }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {
        Log.e(tag, message, error);
    }
    @Override public void logStackTrace(String tag, Exception error) { Log.e(tag, "", error); }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
