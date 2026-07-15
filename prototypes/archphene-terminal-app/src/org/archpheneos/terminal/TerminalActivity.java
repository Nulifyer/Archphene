package org.archpheneos.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.FileObserver;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** PTY-backed launcher surface for managed command-line packages. */
public final class TerminalActivity extends Activity
        implements TerminalSessionClient, TerminalViewClient {
    private static final String TAG = "ArchpheneTerminal";
    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private TextView title;
    private FileObserver requestObserver;
    private final AtomicBoolean handlingRequest = new AtomicBoolean();
    private int fontPixels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        boolean dark = isDark();
        int background = dark ? Color.rgb(17, 20, 23) : Color.rgb(248, 250, 252);
        int foreground = dark ? Color.rgb(240, 245, 247) : Color.rgb(31, 37, 41);
        int surface = dark ? Color.rgb(29, 34, 38) : Color.rgb(232, 237, 240);
        configureTerminalColors(dark);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        title = new TextView(this);
        title.setText("Archphene Terminal");
        title.setTextSize(14);
        title.setTextColor(foreground);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), 0, dp(14), 0);
        title.setBackgroundColor(surface);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(this);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setBackgroundColor(dark ? Color.rgb(13, 15, 17) : Color.WHITE);
        fontPixels = Math.round(16f * getResources().getDisplayMetrics().scaledDensity);
        terminalView.setTextSize(fontPixels);
        root.addView(terminalView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        try {
            TerminalEnvironment.Session environment = TerminalEnvironment.prepare(this);
            terminalSession = new TerminalSession("/system/bin/sh",
                    environment.home.getAbsolutePath(), new String[] {"sh", "-i"},
                    environment.environment, 10000, this);
            terminalView.attachSession(terminalSession);
            observeManagerRequests(environment.request);
            terminalView.requestFocus();
        } catch (Exception error) {
            Log.e(TAG, "Could not prepare terminal", error);
            title.setText("Terminal unavailable: " + safeMessage(error));
        }
    }

    @Override
    protected void onDestroy() {
        if (requestObserver != null) requestObserver.stopWatching();
        if (terminalSession != null) terminalSession.finishIfRunning();
        super.onDestroy();
    }

    private void observeManagerRequests(File request) {
        requestObserver = new FileObserver(request.getParentFile().getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                if (path == null || !request.getName().equals(path)
                        || !handlingRequest.compareAndSet(false, true)) return;
                try {
                    byte[] bytes;
                    try (FileInputStream input = new FileInputStream(request)) {
                        if (request.length() > 4096) throw new SecurityException("Request is too large");
                        bytes = new byte[(int) request.length()];
                        int offset = 0;
                        while (offset < bytes.length) {
                            int count = input.read(bytes, offset, bytes.length - offset);
                            if (count < 0) break;
                            offset += count;
                        }
                    }
                    request.delete();
                    String value = new String(bytes, StandardCharsets.UTF_8).trim();
                    String[] fields = value.split("\\t", 2);
                    if (fields.length != 2 || !fields[0].matches("search|install|remove|upgrade")
                            || fields[1].length() > 512
                            || fields[1].contains("\n") || fields[1].contains("\r")) {
                        throw new SecurityException("Invalid terminal manager request");
                    }
                    Intent manager = new Intent("org.archpheneos.action.TERMINAL_REQUEST")
                            .setClassName("org.archpheneos.manager",
                                    "org.archpheneos.manager.TerminalRequestActivity")
                            .putExtra("archphene_terminal_action", fields[0])
                            .putExtra("archphene_terminal_query", fields[1])
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(manager);
                } catch (Exception error) {
                    Log.e(TAG, "Rejected terminal manager request", error);
                } finally {
                    handlingRequest.set(false);
                }
            }
        };
        requestObserver.startWatching();
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

    @Override public void onTextChanged(TerminalSession session) {
        terminalView.onScreenUpdated();
    }
    @Override public void onTitleChanged(TerminalSession session) {
        String value = session.getTitle();
        title.setText(value == null || value.isEmpty() ? "Archphene Terminal" : value);
    }
    @Override public void onSessionFinished(TerminalSession session) {}
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }
    @Override public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text != null) session.write(text.toString());
        }
    }
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) { terminalView.invalidate(); }
    @Override public void onTerminalCursorStateChange(boolean state) { terminalView.invalidate(); }
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {
        Log.i(TAG, "PTY shell pid=" + pid);
    }
    @Override public Integer getTerminalCursorStyle() { return null; }

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