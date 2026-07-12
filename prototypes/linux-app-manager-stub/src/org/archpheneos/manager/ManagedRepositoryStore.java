package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ManagedRepositoryStore {
    private static final String PREFS = "linux-app-manager-repositories";
    private static final String REPOSITORIES = "repositories";

    public static final class Repository {
        public final String id;
        public final String name;
        public final String packageSearchUrl;
        public final String wrapperCatalogUrl;
        public final boolean builtIn;
        public final boolean enabled;

        Repository(String id, String name, String packageSearchUrl, String wrapperCatalogUrl,
                boolean builtIn, boolean enabled) {
            this.id = id;
            this.name = name;
            this.packageSearchUrl = packageSearchUrl;
            this.wrapperCatalogUrl = wrapperCatalogUrl;
            this.builtIn = builtIn;
            this.enabled = enabled;
        }
    }

    private ManagedRepositoryStore() {}

    public static synchronized List<Repository> list(Context context) {
        ArrayList<Repository> result = new ArrayList<>();
        result.add(new Repository("arch-official", "Arch Linux official",
                "https://archlinux.org/packages/search/json/", "", true, true));
        String raw = preferences(context).getString(REPOSITORIES, "[]");
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                result.add(new Repository(value.getString("id"), value.getString("name"),
                        value.optString("packageSearchUrl", ""),
                        value.optString("wrapperCatalogUrl", ""), false,
                        value.optBoolean("enabled", true)));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static synchronized void addWrapperRepository(Context context, String name, String url)
            throws Exception {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Repository name is required");
        java.net.URL parsed = new java.net.URL(url);
        if (!"https".equals(parsed.getProtocol())) {
            throw new SecurityException("Wrapper repositories require HTTPS");
        }
        JSONArray values = customValues(context);
        JSONObject value = new JSONObject();
        value.put("id", "custom-" + System.currentTimeMillis());
        value.put("name", name.trim());
        value.put("wrapperCatalogUrl", parsed.toString());
        value.put("enabled", true);
        values.put(value);
        preferences(context).edit().putString(REPOSITORIES, values.toString()).apply();
    }

    public static synchronized void remove(Context context, String id) {
        JSONArray current = customValues(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            JSONObject value = current.optJSONObject(i);
            if (value != null && !id.equals(value.optString("id"))) next.put(value);
        }
        preferences(context).edit().putString(REPOSITORIES, next.toString()).apply();
    }

    public static void clearCache(Context context) {
        java.io.File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            if (file.getName().startsWith("arch-repository-")) file.delete();
        }
    }

    private static JSONArray customValues(Context context) {
        try {
            return new JSONArray(preferences(context).getString(REPOSITORIES, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}