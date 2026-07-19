package org.archpheneos.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Foreground owner for persistent PTY sessions. */
public final class TerminalService extends Service implements TerminalSessionClient {
    private static final String TAG = "ArchpheneTerminal";
    private static final String CHANNEL_ID = "archphene_terminal_sessions";
    private static final String ACTION_STOP_ALL =
            "org.archpheneos.terminal.action.STOP_ALL";
    private static final int NOTIFICATION_ID = 0x4150;
    private static final int MAX_SESSIONS = 8;

    interface Client {
        void onServiceStateChanged();
        void onTerminalTextChanged(TerminalSession session);
        void onTerminalCopyRequested(TerminalSession session, String text);
        void onTerminalPasteRequested(TerminalSession session);
        void onTerminalColorsChanged(TerminalSession session);
    }

    static final class SessionInfo {
        final String handle;
        final String title;
        final int pid;
        final boolean running;

        SessionInfo(String handle, String title, int pid, boolean running) {
            this.handle = handle;
            this.title = title;
            this.pid = pid;
            this.running = running;
        }
    }

    private static final class Record {
        final TerminalSession session;
        final TerminalEnvironment.Session environment;
        String title;

        Record(TerminalSession session, TerminalEnvironment.Session environment,
                String title) {
            this.session = session;
            this.environment = environment;
            this.title = title;
        }
    }

    final class LocalBinder extends Binder {
        TerminalService service() {
            return TerminalService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Record> sessions = new ArrayList<>();
    private final ArrayList<Client> clients = new ArrayList<>();
    private TerminalEnvironment.Session environment;
    private Throwable preparationError;
    private Client client;
    private String activeHandle;
    private boolean preparing;
    private boolean refreshing;
    private boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        promoteToForeground();
        prepareEnvironment();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            stopAllSessions();
            return START_NOT_STICKY;
        }
        prepareEnvironment();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        prepareEnvironment();
        return binder;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        for (Record record : new ArrayList<>(sessions)) {
            record.session.finishIfRunning();
        }
        sessions.clear();
        super.onDestroy();
    }

    void attachClient(Client value) {
        clients.remove(value);
        clients.add(value);
        client = value;
        notifyClient();
    }

    void detachClient(Client value) {
        clients.remove(value);
        if (client == value) {
            client = clients.isEmpty() ? null : clients.get(clients.size() - 1);
        }
    }

    List<SessionInfo> sessionInfos() {
        ArrayList<SessionInfo> result = new ArrayList<>(sessions.size());
        for (Record record : sessions) {
            result.add(new SessionInfo(record.session.mHandle, record.title,
                    record.session.getPid(), record.session.isRunning()));
        }
        return Collections.unmodifiableList(result);
    }

    TerminalSession activeSession() {
        Record record = find(activeHandle);
        return record == null ? null : record.session;
    }

    String activeHandle() {
        return activeHandle;
    }

    File home() {
        return environment == null ? null : environment.home;
    }

    File requestDirectory() {
        return environment == null ? null : environment.requestDirectory;
    }

    Throwable preparationError() {
        return preparationError;
    }

    boolean isPreparing() {
        return preparing;
    }

    void createSession() {
        if (environment == null) {
            if (preparationError != null) throw new IllegalStateException(
                    "Terminal environment is unavailable", preparationError);
            return;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("At most " + MAX_SESSIONS
                    + " terminal sessions may run at once");
        }
        boolean first = sessions.isEmpty();
        int number = 1;
        for (;;) {
            String candidate = "Terminal " + number;
            boolean used = false;
            for (Record record : sessions) {
                if (candidate.equals(record.title)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                TerminalSession session = new TerminalSession("/system/bin/sh",
                        environment.home.getAbsolutePath(),
                        new String[] {"sh", environment.launcher.getAbsolutePath()},
                        environment.environment, 10000, this);
                session.mSessionName = candidate;
                sessions.add(new Record(session, environment, candidate));
                activeHandle = session.mHandle;
                Log.i(TAG, "Created terminal session " + candidate
                        + " shell=" + environment.shellName
                        + " handle=" + session.mHandle);
                if (first) promoteToForeground();
                else updateNotification();
                notifyClient();
                return;
            }
            number++;
        }
    }

    void activate(String handle) {
        if (find(handle) == null) throw new IllegalArgumentException("Unknown terminal session");
        activeHandle = handle;
        notifyClient();
    }

    void close(String handle) {
        Record record = find(handle);
        if (record == null) return;
        int index = sessions.indexOf(record);
        sessions.remove(record);
        record.session.finishIfRunning();
        if (handle.equals(activeHandle)) {
            if (sessions.isEmpty()) activeHandle = null;
            else activeHandle = sessions.get(Math.min(index, sessions.size() - 1)).session.mHandle;
        }
        Log.i(TAG, "Closed terminal session handle=" + handle);
        updateNotification();
        notifyClient();
        pruneUnusedEnvironment();
        if (sessions.isEmpty()) stopForegroundAndSelf();
    }

    private void prepareEnvironment() {
        if (preparing || environment != null || preparationError != null || stopping) return;
        preparing = true;
        notifyClient();
        new Thread(() -> {
            try {
                TerminalEnvironment.Session prepared = TerminalEnvironment.prepare(this);
                main.post(() -> {
                    if (stopping) return;
                    environment = prepared;
                    preparing = false;
                    createSession();
                });
            } catch (Throwable error) {
                Log.e(TAG, "Could not prepare terminal environment", error);
                main.post(() -> {
                    preparationError = error;
                    preparing = false;
                    updateNotification();
                    notifyClient();
                });
            }
        }, "archphene-terminal-prepare").start();
    }

    void refreshEnvironmentIfChanged() {
        if (preparing || refreshing || environment == null || stopping) return;
        refreshing = true;
        String previous = environment.catalogId;
        new Thread(() -> {
            try {
                String current = TerminalEnvironment.catalogId(this);
                if (previous.equals(current)) {
                    main.post(() -> refreshing = false);
                    return;
                }
                TerminalEnvironment.Session prepared = TerminalEnvironment.refresh(this);
                main.post(() -> {
                    refreshing = false;
                    if (stopping) return;
                    environment = prepared;
                    if (sessions.size() < MAX_SESSIONS) {
                        createSession();
                        Log.i(TAG, "Terminal catalog changed " + previous + " -> "
                                + prepared.catalogId + "; opened " + activeHandle);
                    } else {
                        Log.w(TAG, "Terminal catalog changed " + previous + " -> "
                                + prepared.catalogId
                                + "; close a tab before opening the refreshed environment");
                        notifyClient();
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Could not refresh terminal environment", error);
                main.post(() -> refreshing = false);
            }
        }, "archphene-terminal-refresh").start();
    }

    private void pruneUnusedEnvironment() {
        if (environment == null) return;
        HashSet<String> generations = new HashSet<>();
        HashSet<String> packs = new HashSet<>();
        generations.add(environment.catalogId);
        packs.addAll(environment.packIds);
        for (Record record : sessions) {
            generations.add(record.environment.catalogId);
            packs.addAll(record.environment.packIds);
        }
        new Thread(() -> {
            try {
                TerminalEnvironment.prune(this, generations, packs);
            } catch (Exception error) {
                Log.w(TAG, "Could not prune stale Terminal environments", error);
            }
        }, "archphene-terminal-prune").start();
    }

    private Record find(String handle) {
        if (handle == null) return null;
        for (Record record : sessions) {
            if (handle.equals(record.session.mHandle)) return record;
        }
        return null;
    }

    private void stopAllSessions() {
        stopping = true;
        for (Record record : new ArrayList<>(sessions)) {
            record.session.finishIfRunning();
        }
        sessions.clear();
        activeHandle = null;
        notifyClient();
        stopForegroundAndSelf();
    }

    private void stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void notifyClient() {
        for (Client observer : new ArrayList<>(clients)) {
            observer.onServiceStateChanged();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Terminal sessions", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps active Archphene terminal sessions running");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void promoteToForeground() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification());
    }

    private Notification notification() {
        Intent open = new Intent(this, TerminalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TerminalService.class).setAction(ACTION_STOP_ALL);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String status;
        if (preparationError != null) status = "Terminal environment unavailable";
        else if (preparing || environment == null) status = "Preparing terminal environment";
        else status = sessions.size() + (sessions.size() == 1
                ? " active session" : " active sessions");
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.terminal_notification)
                .setContentTitle("Archphene Terminal")
                .setContentText(status)
                .setContentIntent(content)
                .setOngoing(!sessions.isEmpty() || preparing)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(0, "Stop", stopAction).build())
                .build();
    }

    @Override public void onTextChanged(TerminalSession session) {
        if (client != null) client.onTerminalTextChanged(session);
    }
    @Override public void onTitleChanged(TerminalSession session) {
        Record record = find(session.mHandle);
        if (record != null) {
            String title = session.getTitle();
            record.title = title == null || title.trim().isEmpty()
                    ? session.mSessionName : title.trim();
        }
        notifyClient();
    }
    @Override public void onSessionFinished(TerminalSession session) {
        Record record = find(session.mHandle);
        if (record != null) record.title = session.mSessionName + " (exited)";
        Log.i(TAG, "Terminal session exited handle=" + session.mHandle
                + " status=" + session.getExitStatus());
        updateNotification();
        notifyClient();
    }
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {
        if (client != null) client.onTerminalCopyRequested(session, text);
        else {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text));
        }
    }
    @Override public void onPasteTextFromClipboard(TerminalSession session) {
        if (client != null) client.onTerminalPasteRequested(session);
    }
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) {
        if (client != null) client.onTerminalColorsChanged(session);
    }
    @Override public void onTerminalCursorStateChange(boolean state) {
        TerminalSession session = activeSession();
        if (client != null && session != null) client.onTerminalColorsChanged(session);
    }
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {
        Log.i(TAG, "PTY shell pid=" + pid + " handle=" + session.mHandle);
        updateNotification();
        notifyClient();
    }
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {
        Log.e(tag, message, error);
    }
    @Override public void logStackTrace(String tag, Exception error) { Log.e(tag, "", error); }
}
