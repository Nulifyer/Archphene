package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;

/** One-shot request channel written only by the signature-protected bridge activity. */
final class TerminalRequestStore {
    private static final String PREFS = "archphene-terminal-request-v1";
    private static final String ACTION = "action";
    private static final String QUERY = "query";

    static final class Request {
        final String action;
        final String query;
        Request(String action, String query) {
            this.action = action;
            this.query = query;
        }
    }

    private TerminalRequestStore() {}

    static void put(Context context, String action, String query) {
        if (action == null || !action.matches("search|install|remove|upgrade")
                || query == null || query.length() > 512
                || query.contains("\n") || query.contains("\r")) {
            throw new SecurityException("Invalid Terminal manager request");
        }
        if (!preferences(context).edit().putString(ACTION, action)
                .putString(QUERY, query).commit()) {
            throw new IllegalStateException("Could not persist Terminal request");
        }
    }

    static Request take(Context context) {
        SharedPreferences preferences = preferences(context);
        String action = preferences.getString(ACTION, null);
        String query = preferences.getString(QUERY, null);
        if (action == null || query == null) return null;
        if (!preferences.edit().remove(ACTION).remove(QUERY).commit()) {
            throw new IllegalStateException("Could not consume Terminal request");
        }
        return new Request(action, query);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}