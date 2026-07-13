package org.archpheneos.manager;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GitHubReleaseClient {
    private static final String API =
            "https://api.github.com/repos/Nulifyer/Archphene/releases?per_page=30";
    private static final int JSON_LIMIT = 4 * 1024 * 1024;
    private static final int CHECKSUM_LIMIT = 4096;
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static List<Artifact> cachedVersions;
    private static long cachedAt;
    private static boolean cachedPrereleases;

    public static final class Artifact {
        public final String version;
        public final String tag;
        public final String apkUrl;
        public final String sha256;
        public final boolean prerelease;
        public final long size;
        private final String checksumUrl;
        private final String apiDigest;

        Artifact(String version, String tag, String apkUrl, String sha256,
                boolean prerelease, long size, String checksumUrl, String apiDigest) {
            this.version = version;
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.prerelease = prerelease;
            this.size = size;
            this.checksumUrl = checksumUrl;
            this.apiDigest = apiDigest;
        }
    }

    private GitHubReleaseClient() {}

    public static Artifact latest(Context context) throws Exception {
        List<Artifact> releases = versions(context);
        if (releases.isEmpty()) throw new IllegalStateException("No eligible GitHub Releases");
        return releases.get(0);
    }

    public static Artifact find(Context context, String version) throws Exception {
        for (Artifact artifact : versions(context)) {
            if (artifact.version.equals(version)) return resolveChecksum(artifact);
        }
        throw new IllegalStateException("GitHub Release " + version + " is not available");
    }

    public static synchronized List<Artifact> versions(Context context) throws Exception {
        boolean allowPrereleases = ManagerStateStore.allowPrereleases(context);
        long now = System.currentTimeMillis();
        if (cachedVersions != null && cachedPrereleases == allowPrereleases
                && now - cachedAt < CACHE_MILLIS) {
            return new ArrayList<>(cachedVersions);
        }
        List<Artifact> result = parseReleaseResponse(
                fetch(new URL(API), JSON_LIMIT, true), allowPrereleases);
        for (Artifact artifact : result) {
            ManagerStateStore.setVersionPrerelease(context, context.getPackageName(),
                    artifact.version, artifact.prerelease);
        }
        cachedVersions = Collections.unmodifiableList(new ArrayList<>(result));
        cachedPrereleases = allowPrereleases;
        cachedAt = now;
        return new ArrayList<>(cachedVersions);
    }

    static List<Artifact> parseReleaseResponse(String response, boolean allowPrereleases)
            throws Exception {
        JSONArray releases = new JSONArray(response);
        ArrayList<Artifact> result = new ArrayList<>();
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.getJSONObject(index);
            if (release.optBoolean("draft", true)) continue;
            boolean prerelease = release.optBoolean("prerelease", false);
            if (prerelease && !allowPrereleases) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.matches("v[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"
                    + "(?:[-+][0-9A-Za-z.-]+)?")) continue;
            String version = tag.substring(1);
            String apkName = "Archphene-" + version + ".apk";
            String checksumName = apkName + ".sha256";
            JSONObject apk = null;
            JSONObject checksum = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.getJSONObject(assetIndex);
                if (!"uploaded".equals(asset.optString("state", ""))) continue;
                if (apkName.equals(asset.optString("name", ""))) apk = asset;
                if (checksumName.equals(asset.optString("name", ""))) checksum = asset;
            }
            if (apk == null || checksum == null) continue;
            long size = apk.optLong("size", -1);
            if (size <= 0 || size > 512L * 1024 * 1024) continue;
            URL apkUrl = validatedBrowserUrl(apk.getString("browser_download_url"), tag, apkName);
            URL checksumUrl = validatedBrowserUrl(
                    checksum.getString("browser_download_url"), tag, checksumName);
            long checksumSize = checksum.optLong("size", -1);
            if (checksumSize <= 0 || checksumSize > CHECKSUM_LIMIT) continue;
            result.add(new Artifact(version, tag, apkUrl.toString(), "", prerelease, size,
                    checksumUrl.toString(), apk.optString("digest", "")));
        }
        Collections.sort(result, (left, right) -> compareVersions(right.version, left.version));
        return result;
    }

    static void verifyParserForTest() throws Exception {
        String stable = "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v1.2.3\","
                + "\"assets\":["
                + "{\"state\":\"uploaded\",\"name\":\"Archphene-1.2.3.apk\","
                + "\"size\":123,\"browser_download_url\":"
                + "\"https://github.com/Nulifyer/Archphene/releases/download/v1.2.3/"
                + "Archphene-1.2.3.apk\",\"digest\":\"sha256:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
                + "{\"state\":\"uploaded\",\"name\":\"Archphene-1.2.3.apk.sha256\","
                + "\"size\":90,\"browser_download_url\":"
                + "\"https://github.com/Nulifyer/Archphene/releases/download/v1.2.3/"
                + "Archphene-1.2.3.apk.sha256\"}]}";
        String prerelease = stable.replace("v1.2.3", "v1.3.0-rc1")
                .replace("1.2.3.apk", "1.3.0-rc1.apk")
                .replace("\"prerelease\":false", "\"prerelease\":true");
        List<Artifact> stableOnly = parseReleaseResponse(
                "[" + stable + "," + prerelease + "]", false);
        List<Artifact> withPrerelease = parseReleaseResponse(
                "[" + stable + "," + prerelease + "]", true);
        if (stableOnly.size() != 1 || !"1.2.3".equals(stableOnly.get(0).version)
                || withPrerelease.size() != 2
                || !"1.3.0-rc1".equals(withPrerelease.get(0).version)) {
            throw new SecurityException("GitHub release parser policy mismatch");
        }
    }
    private static Artifact resolveChecksum(Artifact artifact) throws Exception {
        String apkName = "Archphene-" + artifact.version + ".apk";
        String checksumText = fetch(new URL(artifact.checksumUrl),
                CHECKSUM_LIMIT, false).trim();
        String hash = parseChecksum(checksumText, apkName);
        if (!artifact.apiDigest.isEmpty()
                && !artifact.apiDigest.equalsIgnoreCase("sha256:" + hash)) {
            throw new SecurityException("GitHub asset digest does not match checksum asset");
        }
        return new Artifact(artifact.version, artifact.tag, artifact.apkUrl, hash,
                artifact.prerelease, artifact.size, artifact.checksumUrl,
                artifact.apiDigest);
    }

    static String parseChecksum(String value, String apkName) {
        String[] fields = value.trim().split("\\s+");
        if (fields.length != 2 || !fields[0].matches("[0-9a-fA-F]{64}")
                || !fields[1].replaceFirst("^\\*", "").equals(apkName)) {
            throw new SecurityException("Malformed GitHub Release checksum");
        }
        return fields[0].toLowerCase(Locale.ROOT);
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[-+]", 2)[0].split("\\.");
        String[] rightParts = right.split("[-+]", 2)[0].split("\\.");
        for (int index = 0; index < 3; index++) {
            int compared = Long.compare(Long.parseLong(leftParts[index]),
                    Long.parseLong(rightParts[index]));
            if (compared != 0) return compared;
        }
        boolean leftPrerelease = left.contains("-");
        boolean rightPrerelease = right.contains("-");
        if (leftPrerelease != rightPrerelease) return leftPrerelease ? -1 : 1;
        return left.compareTo(right);
    }

    private static URL validatedBrowserUrl(String value, String tag, String assetName)
            throws Exception {
        URL url = new URL(value);
        String expected = "/Nulifyer/Archphene/releases/download/" + tag + "/" + assetName;
        if (!"https".equals(url.getProtocol()) || !"github.com".equals(url.getHost())
                || !expected.equals(url.getPath()) || url.getQuery() != null
                || url.getRef() != null) {
            throw new SecurityException("Unexpected GitHub Release asset URL");
        }
        return url;
    }

    private static String fetch(URL initial, int limit, boolean api) throws Exception {
        URL current = initial;
        for (int redirects = 0; redirects <= 4; redirects++) {
            validateFetchUrl(current, api, redirects > 0);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Archphene/1.0");
            connection.setRequestProperty("Accept",
                    api ? "application/vnd.github+json" : "text/plain");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new SecurityException("GitHub redirect has no target");
                    current = new URL(current, location);
                    continue;
                }
                if (status != 200) {
                    throw new IllegalStateException("GitHub Releases HTTP " + status);
                }
                long declared = connection.getContentLengthLong();
                if (declared > limit) throw new SecurityException("GitHub response is too large");
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() + read > limit) {
                            throw new SecurityException("GitHub response is too large");
                        }
                        output.write(buffer, 0, read);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new SecurityException("Too many GitHub Release redirects");
    }

    private static void validateFetchUrl(URL url, boolean api, boolean redirected) {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || url.getRef() != null) {
            throw new SecurityException("Unsafe GitHub URL");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (api) {
            if (!"api.github.com".equals(host)
                    || !"/repos/Nulifyer/Archphene/releases".equals(url.getPath())) {
                throw new SecurityException("Unexpected GitHub API URL");
            }
            return;
        }
        if (!redirected) {
            if (!"github.com".equals(host)
                    || !url.getPath().startsWith("/Nulifyer/Archphene/releases/download/")) {
                throw new SecurityException("Unexpected GitHub asset URL");
            }
        } else if (!"release-assets.githubusercontent.com".equals(host)
                && !"objects.githubusercontent.com".equals(host)
                && !"github.com".equals(host)) {
            throw new SecurityException("Unexpected GitHub asset redirect");
        }
    }
}
