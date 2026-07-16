package org.archpheneos.terminal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Signature-protected result channel from the manager to an invoking terminal command. */
public final class TerminalCommandProvider extends ContentProvider {
    static final String METHOD = "org.archphene.terminal.REPORT_V1";
    private static final String MANAGER_PACKAGE = "org.archpheneos.manager";
    private static final String SAFE_REQUEST = "[a-zA-Z0-9._-]{1,64}";
    private static final Object WRITE_LOCK = new Object();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (!METHOD.equals(method) || argument == null || !argument.matches(SAFE_REQUEST)
                || extras == null) throw new SecurityException("Invalid Terminal result request");
        requireManagerCaller();
        String phase = bounded(extras.getString("phase", "unknown"), 64);
        String status = bounded(extras.getString("status", ""), 8192);
        String outcome = bounded(extras.getString("outcome", "running"), 16);
        int percent = Math.max(0, Math.min(100, extras.getInt("percent", 0)));
        if (!phase.matches("[a-zA-Z0-9._ -]{1,64}")
                || !outcome.matches("running|success|error|cancelled")) {
            throw new SecurityException("Invalid Terminal result fields");
        }
        status = status.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        try {
            publish(providerContext(), argument, phase, percent,
                    extras.getBoolean("terminal", false), outcome, status);
            Bundle result = new Bundle();
            result.putBoolean("accepted", true);
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Could not publish Terminal result", error);
        }
    }

    static void publish(Context context, String requestId, String phase, int percent,
            boolean terminal, String outcome, String status) throws Exception {
        if (requestId == null || !requestId.matches(SAFE_REQUEST)) {
            throw new SecurityException("Invalid Terminal request identifier");
        }
        File responses = TerminalEnvironment.responseDirectory(context);
        File target = new File(responses, requestId + ".response");
        synchronized (WRITE_LOCK) {
            int sequence = SEQUENCE.updateAndGet(
                    value -> value == Integer.MAX_VALUE ? 1 : value + 1);
            String safePhase = bounded(phase, 64).replace((char) 9, ' ')
                    .replace((char) 10, ' ').replace((char) 13, ' ');
            String safeStatus = bounded(status, 8192).replace((char) 9, ' ')
                    .replace((char) 10, ' ').replace((char) 13, ' ');
            String line = new StringBuilder("v1").append((char) 9)
                    .append(sequence).append((char) 9).append(safePhase)
                    .append((char) 9).append(Math.max(0, Math.min(100, percent)))
                    .append((char) 9).append(terminal ? "1" : "0")
                    .append((char) 9).append(outcome).append((char) 9)
                    .append(safeStatus).append((char) 10).toString();
            File temporary = new File(responses, requestId + ".response.tmp");
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("Could not reset Terminal result");
            }
            FileDescriptor descriptor = Os.open(temporary.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                            | OsConstants.O_NOFOLLOW, 0600);
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (Exception error) {
                temporary.delete();
                throw error;
            }
            if (target.exists() && !target.delete()) {
                temporary.delete();
                throw new IllegalStateException("Could not replace Terminal result");
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IllegalStateException("Could not publish Terminal result");
            }
        }
    }
    private void requireManagerCaller() {
        Context context = providerContext();
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        boolean ownsManager = false;
        if (packages != null) for (String value : packages) {
            if (MANAGER_PACKAGE.equals(value)) ownsManager = true;
        }
        if (!ownsManager || context.getPackageManager().checkSignatures(
                context.getPackageName(), MANAGER_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Caller is not the signed Archphene manager");
        }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Terminal context unavailable");
        return context;
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { throw new UnsupportedOperationException(); }
}