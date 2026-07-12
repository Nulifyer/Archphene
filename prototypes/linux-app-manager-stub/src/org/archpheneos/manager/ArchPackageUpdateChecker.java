package org.archpheneos.manager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ArchPackageUpdateChecker {
    public static final class Result {
        public final String availableVersion;
        public final boolean updateAvailable;

        Result(String availableVersion, boolean updateAvailable) {
            this.availableVersion = availableVersion;
            this.updateAvailable = updateAvailable;
        }
    }

    private ArchPackageUpdateChecker() {}

    public static Result check(String updateUrl, String installedVersion) throws Exception {
        URL parsed = new URL(updateUrl);
        if (!"https".equals(parsed.getProtocol()) || !"archlinux.org".equals(parsed.getHost())) {
            throw new SecurityException("Update metadata must use the official Arch Linux HTTPS host");
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Arch package metadata HTTP " + connection.getResponseCode());
            }
            String json;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > 1024 * 1024) {
                        throw new IllegalStateException("Arch package metadata exceeds 1 MiB");
                    }
                    out.write(buffer, 0, read);
                }
                json = out.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject root = new JSONObject(json);
            String available = root.getString("pkgver") + "-" + root.getString("pkgrel");
            return new Result(available, !available.equals(installedVersion));
        } finally {
            connection.disconnect();
        }
    }
}