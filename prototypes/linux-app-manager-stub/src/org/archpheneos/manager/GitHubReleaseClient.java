package org.archpheneos.manager;

import android.content.Context;
import android.os.Build;
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
        private final String assetName;
        private final String checksumUrl;
        private final String apiDigest;

        Artifact(String version, String tag, String apkUrl, String sha256,
                boolean prerelease, long size, String assetName, String checksumUrl,
                String apiDigest) {
            this.version = version;
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.prerelease = prerelease;
            this.size = size;
            this.assetName = assetName;
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
                fetch(new URL(API), JSON_LIMIT, true), allowPrereleases, releaseAbi());
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
        return parseReleaseResponse(response, allowPrereleases, releaseAbi());
    }

    static List<Artifact> parseReleaseResponse(String response, boolean allowPrereleases,
            String releaseAbi) throws Exception {
        if (!releaseAbi.matches("(?:x86_64|arm64-v8a)")) {
            throw new IllegalArgumentException("Unsupported release ABI " + releaseAbi);
        }
        JSONArray releases = new JSONArray(response);
        ArrayList<Artifact> result = new ArrayList<>();
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.getJSONObject(index);
            if (release.optBoolean("draft", true)) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.matches("v[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"
                    + "(?:[-+][0-9A-Za-z.-]+)?")) continue;
            String version = tag.substring(1);
            boolean prerelease = release.optBoolean("prerelease", false)
                    || VersionPolicy.isPrerelease(version);
            if (prerelease && !allowPrereleases) continue;
            String apkName = "Archphene-" + releaseAbi + "-" + version + ".apk";
            String checksumName = apkName + ".sha256";
            JSONObject apk = null;
            JSONObject checksum = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.getJSONObject(assetIndex);
                if (!"uploaded".equals(asset.optString("state", ""))) continue;
                String name = asset.optString("name", "");
                if (apkName.equals(name)) apk = asset;
                if (checksumName.equals(name)) checksum = asset;
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
                    apkName, checksumUrl.toString(), apk.optString("digest", "")));
        }
        Collections.sort(result, (left, right) -> compareVersions(right.version, left.version));
        return result;
    }

    static void verifyParserForTest() throws Exception {
        String stable = "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v1.2.3\","
                + "\"assets\":["
                + "{\"state\":\"uploaded\",\"name\":\"Archphene-x86_64-1.2.3.apk\","
                + "\"size\":123,\"browser_download_url\":"
                + "\"https://github.com/Nulifyer/Archphene/releases/download/v1.2.3/"
                + "Archphene-x86_64-1.2.3.apk\",\"digest\":\"sha256:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
                + "{\"state\":\"uploaded\",\"name\":\"Archphene-x86_64-1.2.3.apk.sha256\","
                + "\"size\":90,\"browser_download_url\":"
                + "\"https://github.com/Nulifyer/Archphene/releases/download/v1.2.3/"
                + "Archphene-x86_64-1.2.3.apk.sha256\"}]}";
        String prerelease = stable.replace("v1.2.3", "v1.3.0-rc1")
                .replace("1.2.3.apk", "1.3.0-rc1.apk");
        List<Artifact> stableOnly = parseReleaseResponse(
                "[" + stable + "," + prerelease + "]", false, "x86_64");
        List<Artifact> withPrerelease = parseReleaseResponse(
                "[" + stable + "," + prerelease + "]", true, "x86_64");
        List<Artifact> x86Only = parseReleaseResponse("[" + stable + "]",
                false, "x86_64");
        List<Artifact> incompatible = parseReleaseResponse("[" + stable + "]",
                false, "arm64-v8a");
        String universal = stable.replace(
                "Archphene-x86_64-1.2.3", "Archphene-1.2.3");
        List<Artifact> universalX86 = parseReleaseResponse("[" + universal + "]",
                false, "x86_64");
        List<Artifact> universalArm = parseReleaseResponse("[" + universal + "]",
                false, "arm64-v8a");
        String mixed = stable.substring(0, stable.length() - 2) + ","
                + universal.substring(universal.indexOf("{\"state\""),
                        universal.length() - 2) + "]}";
        List<Artifact> mixedX86 = parseReleaseResponse("[" + mixed + "]",
                false, "x86_64");
        if (stableOnly.size() != 1 || !"1.2.3".equals(stableOnly.get(0).version)
                || withPrerelease.size() != 2
                || !"1.3.0-rc1".equals(withPrerelease.get(0).version)
                || x86Only.size() != 1
                || !"Archphene-x86_64-1.2.3.apk".equals(x86Only.get(0).assetName)
                || !incompatible.isEmpty() || !universalX86.isEmpty()
                || !universalArm.isEmpty() || mixedX86.size() != 1
                || !"Archphene-x86_64-1.2.3.apk".equals(mixedX86.get(0).assetName)) {
            throw new SecurityException("GitHub release parser policy mismatch");
        }
        if (compareVersions("1.0.0-rc.10", "1.0.0-rc.2") <= 0
                || compareVersions("1.0.0+build.2", "1.0.0+build.1") != 0
                || compareVersions("1.0.0-1", "1.0.0-alpha") >= 0) {
            throw new SecurityException("Semantic release ordering mismatch");
        }
    }
    private static Artifact resolveChecksum(Artifact artifact) throws Exception {
        String checksumText = fetch(new URL(artifact.checksumUrl),
                CHECKSUM_LIMIT, false).trim();
        String hash = parseChecksum(checksumText, artifact.assetName);
        if (!artifact.apiDigest.isEmpty()
                && !artifact.apiDigest.equalsIgnoreCase("sha256:" + hash)) {
            throw new SecurityException("GitHub asset digest does not match checksum asset");
        }
        return new Artifact(artifact.version, artifact.tag, artifact.apkUrl, hash,
                artifact.prerelease, artifact.size, artifact.assetName, artifact.checksumUrl,
                artifact.apiDigest);
    }

    private static String releaseAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("x86_64".equals(abi) || "arm64-v8a".equals(abi)) return abi;
        }
        throw new IllegalStateException("No supported 64-bit Archphene release ABI");
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
        String leftPrecedence = left.split("\\+", 2)[0];
        String rightPrecedence = right.split("\\+", 2)[0];
        String[] leftParts = leftPrecedence.split("-", 2)[0].split("\\.");
        String[] rightParts = rightPrecedence.split("-", 2)[0].split("\\.");
        for (int index = 0; index < 3; index++) {
            int compared = Long.compare(Long.parseLong(leftParts[index]),
                    Long.parseLong(rightParts[index]));
            if (compared != 0) return compared;
        }
        boolean leftPrerelease = leftPrecedence.contains("-");
        boolean rightPrerelease = rightPrecedence.contains("-");
        if (leftPrerelease != rightPrerelease) return leftPrerelease ? -1 : 1;
        if (!leftPrerelease) return 0;
        String[] leftIdentifiers = leftPrecedence.split("-", 2)[1].split("\\.");
        String[] rightIdentifiers = rightPrecedence.split("-", 2)[1].split("\\.");
        int shared = Math.min(leftIdentifiers.length, rightIdentifiers.length);
        for (int index = 0; index < shared; index++) {
            int compared = comparePrereleaseIdentifier(
                    leftIdentifiers[index], rightIdentifiers[index]);
            if (compared != 0) return compared;
        }
        return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
    }

    private static int comparePrereleaseIdentifier(String left, String right) {
        boolean leftNumeric = left.matches("[0-9]+");
        boolean rightNumeric = right.matches("[0-9]+");
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        if (!leftNumeric) return left.compareTo(right);
        String normalizedLeft = left.replaceFirst("^0+(?!$)", "");
        String normalizedRight = right.replaceFirst("^0+(?!$)", "");
        int length = Integer.compare(normalizedLeft.length(), normalizedRight.length());
        return length != 0 ? length : normalizedLeft.compareTo(normalizedRight);
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
