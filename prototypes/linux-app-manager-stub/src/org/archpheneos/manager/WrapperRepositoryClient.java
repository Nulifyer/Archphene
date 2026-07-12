package org.archpheneos.manager;

import android.content.Context;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WrapperRepositoryClient {
    public static final class Artifact {
        public final String packageName;
        public final String sourcePackage;
        public final String signerSha256;
        public final String architecture;
        public final String version;
        public final long versionCode;
        public final String apkUrl;
        public final String sha256;
        public final String health;
        public final boolean prerelease;

        Artifact(String packageName, String sourcePackage, String signerSha256,
                String architecture, String version, long versionCode, String apkUrl,
                String sha256, String health, boolean prerelease) {
            this.packageName = packageName;
            this.sourcePackage = sourcePackage;
            this.signerSha256 = signerSha256;
            this.architecture = architecture;
            this.version = version;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.health = health;
            this.prerelease = prerelease;
        }
    }

    private WrapperRepositoryClient() {}

    public static List<Artifact> versions(Context context, String packageName) throws Exception {
        ArrayList<Artifact> result = new ArrayList<>();
        Exception lastError = null;
        for (ManagedRepositoryStore.Repository repository : ManagedRepositoryStore.list(context)) {
            if (!repository.enabled || repository.wrapperCatalogUrl.isEmpty()) continue;
            try {
                JSONObject root = new JSONObject(fetch(context, repository.wrapperCatalogUrl));
                if (!"org.archphene.wrapper-repository.v1".equals(root.optString("schema"))) {
                    throw new SecurityException("Unsupported wrapper repository schema");
                }
                JSONArray packages = root.getJSONArray("packages");
                for (int i = 0; i < packages.length(); i++) {
                    JSONObject app = packages.getJSONObject(i);
                    if (!packageName.equals(app.getString("packageName"))) continue;
                    String sourcePackage = app.optString("sourcePackage", "");
                    String architecture = app.optString("architecture", "any");
                    if (!supportsArchitecture(architecture)) continue;
                    String signerSha256 = app.getString("signerSha256");
                    if (!signerSha256.matches("(?i)[0-9a-f]{64}")) {
                        throw new SecurityException("Invalid wrapper signing certificate digest");
                    }
                    JSONArray versions = app.getJSONArray("versions");
                    for (int j = 0; j < versions.length(); j++) {
                        JSONObject value = versions.getJSONObject(j);
                        String apkUrl = value.getString("apkUrl");
                        URL parsed = new URL(apkUrl);
                        if (!"https".equals(parsed.getProtocol())) {
                            throw new SecurityException("Wrapper artifacts require HTTPS");
                        }
                        String hash = value.getString("sha256");
                        if (!hash.matches("(?i)[0-9a-f]{64}")) {
                            throw new SecurityException("Invalid wrapper SHA-256");
                        }
                        String version = value.getString("version");
                        boolean prerelease = value.has("prerelease")
                                ? value.getBoolean("prerelease")
                                : VersionPolicy.isPrerelease(version);
                        ManagerStateStore.setVersionPrerelease(context, packageName, version, prerelease);
                        if (prerelease && !ManagerStateStore.allowPrereleases(context)) continue;
                        result.add(new Artifact(packageName, sourcePackage, signerSha256,
                                architecture, version, value.getLong("versionCode"),
                                apkUrl, hash, value.optString("health", "unknown"), prerelease));
                    }
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (result.isEmpty() && lastError != null) throw lastError;
        result.sort((left, right) -> Long.compare(right.versionCode, left.versionCode));
        return result;
    }

    public static Artifact latestForSource(Context context, String sourcePackage)
            throws Exception {
        Artifact latest = null;
        for (ManagedRepositoryStore.Repository repository : ManagedRepositoryStore.list(context)) {
            if (!repository.enabled || repository.wrapperCatalogUrl.isEmpty()) continue;
            JSONObject root = new JSONObject(fetch(context, repository.wrapperCatalogUrl));
            if (!"org.archphene.wrapper-repository.v1".equals(root.optString("schema"))) continue;
            JSONArray packages = root.getJSONArray("packages");
            for (int i = 0; i < packages.length(); i++) {
                JSONObject app = packages.getJSONObject(i);
                if (!sourcePackage.equals(app.optString("sourcePackage"))) continue;
                List<Artifact> artifacts = versions(context, app.getString("packageName"));
                if (!artifacts.isEmpty() && (latest == null
                        || artifacts.get(0).versionCode > latest.versionCode)) {
                    latest = artifacts.get(0);
                }
            }
        }
        if (latest == null) throw new IllegalStateException(
                "No signed wrapper repository publishes " + sourcePackage);
        return latest;
    }
    public static Artifact latest(Context context, String packageName) throws Exception {
        List<Artifact> versions = versions(context, packageName);
        if (versions.isEmpty()) throw new IllegalStateException("No signed wrapper repository publishes " + packageName);
        return versions.get(0);
    }

    public static Artifact find(Context context, String packageName, String version)
            throws Exception {
        for (Artifact artifact : versions(context, packageName)) {
            if (artifact.version.equals(version)) return artifact;
        }
        throw new IllegalStateException("No signed wrapper artifact for " + version);
    }

    private static boolean supportsArchitecture(String architecture) {
        if ("any".equals(architecture)) return true;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("x86_64".equals(architecture) && "x86_64".equals(abi)) return true;
            if ("aarch64".equals(architecture) && "arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
    private static String fetch(Context context, String endpoint) throws Exception {
        File cache = new File(context.getCacheDir(), "arch-repository-wrapper-"
                + digest(endpoint) + ".json");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 15 * 60_000L) {
            try (InputStream in = new FileInputStream(cache)) { return read(in); }
        }
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol())) throw new SecurityException("Wrapper repositories require HTTPS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Archphene/1.0");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Wrapper repository HTTP " + connection.getResponseCode());
            }
            String body = read(connection.getInputStream());
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > 2 * 1024 * 1024) {
                throw new IllegalStateException("Wrapper repository exceeds 2 MiB");
            }
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