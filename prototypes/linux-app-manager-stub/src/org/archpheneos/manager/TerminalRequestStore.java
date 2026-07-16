package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Per-ID one-shot requests written only by the signature-protected bridge activity. */
final class TerminalRequestStore {
    private static final String PREFS = "archphene-terminal-request-v2";
    private static final String PREFIX = "request:";
    private static final int MAX_PENDING = 32;

    static final class Request {
        final String id;
        final String action;
        final String query;

        Request(String id, String action, String query) {
            this.id = id;
            this.action = action;
            this.query = query;
        }
    }

    private TerminalRequestStore() {}

    static Request validate(String id, String action, String query) {
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,64}")
                || action == null || !action.matches("search|install|remove|upgrade")
                || query == null || query.length() > 512
                || query.contains("\n") || query.contains("\r") || query.contains("\t")) {
            throw new SecurityException("Invalid Terminal manager request");
        }
        return new Request(id, action, query);
    }

    static synchronized void put(Context context, String id, String action, String query) {
        Request request = validate(id, action, query);
        SharedPreferences preferences = preferences(context);
        int pending = 0;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(PREFIX)) pending++;
        }
        if (pending >= MAX_PENDING && !preferences.contains(PREFIX + request.id)) {
            throw new IllegalStateException("Too many pending Terminal requests");
        }
        String value = request.action + "\t" + request.query;
        if (!preferences.edit().putString(PREFIX + request.id, value).commit()) {
            throw new IllegalStateException("Could not persist Terminal request");
        }
    }

    static synchronized Request take(Context context, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,64}")) return null;
        SharedPreferences preferences = preferences(context);
        String key = PREFIX + id;
        String value = preferences.getString(key, null);
        if (value == null) return null;
        if (!preferences.edit().remove(key).commit()) {
            throw new IllegalStateException("Could not consume Terminal request");
        }
        String[] fields = value.split("\t", 2);
        if (fields.length != 2) throw new SecurityException("Saved Terminal request is invalid");
        return validate(id, fields[0], fields[1]);
    }

    static synchronized void discard(Context context, String id) {
        if (id != null && id.matches("[a-zA-Z0-9._-]{1,64}")) {
            preferences(context).edit().remove(PREFIX + id).commit();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}