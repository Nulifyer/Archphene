package org.archpheneos.manager;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TrackedPackageStore {
    private static final String PREFS = "linux-app-manager-tracked";
    private static final String PACKAGES = "packages";

    private TrackedPackageStore() {}

    public static synchronized List<ArchPackageRepository.PackageResult> list(Context context) {
        ArrayList<ArchPackageRepository.PackageResult> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PACKAGES, "[]"));
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                result.add(new ArchPackageRepository.PackageResult(value.getString("name"),
                        value.getString("repository"), value.getString("architecture"),
                        value.getString("version"), value.optString("description", ""),
                        value.optBoolean("flaggedOutOfDate", false)));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static synchronized void add(Context context, ArchPackageRepository.PackageResult entry) {
        List<ArchPackageRepository.PackageResult> current = list(context);
        for (ArchPackageRepository.PackageResult value : current) {
            if (value.name.equals(entry.name) && value.architecture.equals(entry.architecture)) return;
        }
        current.add(entry);
        write(context, current);
    }

    public static synchronized void remove(Context context, String name, String architecture) {
        List<ArchPackageRepository.PackageResult> current = list(context);
        current.removeIf(value -> value.name.equals(name) && value.architecture.equals(architecture));
        write(context, current);
    }

    private static void write(Context context, List<ArchPackageRepository.PackageResult> entries) {
        JSONArray values = new JSONArray();
        try {
            for (ArchPackageRepository.PackageResult entry : entries) {
                JSONObject value = new JSONObject();
                value.put("name", entry.name);
                value.put("repository", entry.repository);
                value.put("architecture", entry.architecture);
                value.put("version", entry.version);
                value.put("description", entry.description);
                value.put("flaggedOutOfDate", entry.flaggedOutOfDate);
                values.put(value);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PACKAGES, values.toString()).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist tracked package", e);
        }
    }
}