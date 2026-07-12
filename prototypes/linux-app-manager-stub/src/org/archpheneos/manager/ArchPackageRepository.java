package org.archpheneos.manager;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ArchPackageRepository {
    public static final class PackageResult {
        public final String name;
        public final String repository;
        public final String architecture;
        public final String version;
        public final String description;
        public final boolean flaggedOutOfDate;

        PackageResult(String name, String repository, String architecture, String version,
                String description, boolean flaggedOutOfDate) {
            this.name = name;
            this.repository = repository;
            this.architecture = architecture;
            this.version = version;
            this.description = description;
            this.flaggedOutOfDate = flaggedOutOfDate;
        }
    }

    private ArchPackageRepository() {}

    public static List<PackageResult> search(Context context, String query) throws Exception {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) return new ArrayList<>();
        String endpoint = "https://archlinux.org/packages/search/json/?q="
                + URLEncoder.encode(normalized, StandardCharsets.UTF_8.name());
        JSONObject root = new JSONObject(fetchCached(context, endpoint, 15 * 60_000L));
        JSONArray values = root.getJSONArray("results");
        ArrayList<PackageResult> result = new ArrayList<>();
        for (int i = 0; i < values.length() && result.size() < 50; i++) {
            JSONObject value = values.getJSONObject(i);
            String version = value.optString("pkgver", "");
            String release = value.optString("pkgrel", "");
            if (!release.isEmpty()) version += "-" + release;
            if (!VersionPolicy.allowed(context, version)) continue;
            result.add(new PackageResult(value.getString("pkgname"),
                    value.optString("repo", "unknown"), value.optString("arch", "unknown"),
                    version, value.optString("pkgdesc", ""),
                    !value.isNull("flag_date") || !value.isNull("flagged_date")));
        }
        return result;
    }

    public static List<String> versions(Context context, String packageName, String currentVersion)
            throws Exception {
        List<String> curated = curatedVersions(context, packageName);
        if (!curated.isEmpty()) return filterVersions(context, curated, currentVersion);
        String first = packageName.substring(0, 1).toLowerCase(java.util.Locale.ROOT);
        String endpoint = "https://archive.archlinux.org/packages/" + first + "/"
                + packageName + "/";
        String html = fetchCached(context, endpoint, 60 * 60_000L);
        Pattern pattern = Pattern.compile("href=\\\"" + Pattern.quote(packageName)
                + "-(.+?)-(?:x86_64|any)\\.pkg\\.tar\\.(?:zst|xz)\\\"");
        Matcher matcher = pattern.matcher(html);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        found.add(currentVersion);
        while (matcher.find()) found.add(matcher.group(1));
        ArrayList<String> all = new ArrayList<>(found);
        ArrayList<String> result = new ArrayList<>();
        result.add(currentVersion);
        for (int i = all.size() - 1; i >= 0 && result.size() < 20; i--) {
            String version = all.get(i);
            if (!result.contains(version) && VersionPolicy.allowed(context, version)) result.add(version);
        }
        return result;
    }

    private static List<String> filterVersions(Context context, List<String> versions,
            String installedVersion) {
        ArrayList<String> result = new ArrayList<>();
        if (installedVersion != null && !installedVersion.isEmpty()) result.add(installedVersion);
        for (String version : versions) {
            if (!result.contains(version) && VersionPolicy.allowed(context, version)) {
                result.add(version);
            }
        }
        return result;
    }

    private static List<String> curatedVersions(Context context, String packageName) {
        ArrayList<String> result = new ArrayList<>();
        try (InputStream in = context.getAssets().open("known-package-versions.json")) {
            JSONObject root = new JSONObject(read(in, 256 * 1024));
            JSONArray values = root.optJSONArray(packageName);
            if (values != null) {
                for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
            }
        } catch (Exception ignored) {
        }
        return result;
    }
    private static String fetchCached(Context context, String endpoint, long maxAge) throws Exception {
        File cache = new File(context.getCacheDir(), "arch-repository-" + digest(endpoint) + ".json");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < maxAge) {
            try (InputStream in = new FileInputStream(cache)) { return read(in, 4 * 1024 * 1024); }
        }
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || !("archlinux.org".equals(url.getHost())
                || "archive.archlinux.org".equals(url.getHost()))) {
            throw new SecurityException("Unsupported Arch repository endpoint");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json,text/html");
        connection.setRequestProperty("User-Agent", "Archphene/1.0");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Repository HTTP " + connection.getResponseCode());
            }
            String body = read(connection.getInputStream(), 4 * 1024 * 1024);
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IllegalStateException("Repository response is too large");
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(String.format("%02x", bytes[i] & 0xff));
        return out.toString();
    }
}