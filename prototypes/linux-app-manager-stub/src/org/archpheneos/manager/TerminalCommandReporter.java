package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** Correlates persisted package jobs with the Terminal command that invoked them. */
final class TerminalCommandReporter {
    private static final Uri PROVIDER = Uri.parse("content://org.archpheneos.terminal.commands");
    private static final String METHOD = "org.archphene.terminal.REPORT_V1";
    private static final String PREFS = "archphene-terminal-command-jobs-v1";
    private static final String PREFIX = "request:";
    private static final String SAFE_REQUEST = "[a-zA-Z0-9._-]{1,64}";

    private TerminalCommandReporter() {}

    static synchronized void bind(Context context, String jobId, String requestId) {
        requireRequestId(requestId);
        if (!preferences(context).edit().putString(PREFIX + jobId, requestId).commit()) {
            throw new IllegalStateException("Could not correlate Terminal package request");
        }
    }

    static void reportJob(Context context, PackageInstallJobStore.Snapshot snapshot,
            boolean terminal) {
        String requestId = preferences(context).getString(PREFIX + snapshot.id, null);
        if (requestId == null) return;
        String outcome = "running";
        if (terminal) {
            if (PackageInstallJobStore.COMPLETE.equals(snapshot.state)) outcome = "success";
            else if (PackageInstallJobStore.CANCELLED.equals(snapshot.state)) outcome = "cancelled";
            else outcome = "error";
        }
        String status = snapshot.error.isEmpty() ? snapshot.status
                : snapshot.status + ": " + snapshot.error;
        boolean delivered = report(context, requestId,
                snapshot.phase.name().toLowerCase(java.util.Locale.ROOT),
                snapshot.percent, terminal, outcome, status);
        if (terminal && delivered) {
            preferences(context).edit().remove(PREFIX + snapshot.id).commit();
        }
    }

    static boolean report(Context context, String requestId, String phase, int percent,
            boolean terminal, String outcome, String status) {
        requireRequestId(requestId);
        try {
            Bundle request = new Bundle();
            request.putString("phase", phase);
            request.putInt("percent", percent);
            request.putBoolean("terminal", terminal);
            request.putString("outcome", outcome);
            request.putString("status", status);
            Bundle result = context.getContentResolver().call(
                    PROVIDER, METHOD, requestId, request);
            if (result == null || !result.getBoolean("accepted", false)) {
                throw new IllegalStateException("Terminal rejected command result");
            }
            return true;
        } catch (Exception error) {
            android.util.Log.e("ArchpheneTerminal",
                    "Could not report command result " + requestId, error);
            return false;
        }
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !requestId.matches(SAFE_REQUEST)) {
            throw new SecurityException("Invalid Terminal request identifier");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
