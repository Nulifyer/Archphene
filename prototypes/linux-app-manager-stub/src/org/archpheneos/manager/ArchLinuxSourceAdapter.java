package org.archpheneos.manager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ArchLinuxSourceAdapter implements PackageSourceAdapter {
    @Override
    public boolean supports(URL metadataUrl) {
        return "https".equals(metadataUrl.getProtocol())
                && "archlinux.org".equals(metadataUrl.getHost())
                && metadataUrl.getPath().endsWith("/json/");
    }

    @Override
    public ArchPackageUpdateChecker.Result check(URL metadataUrl, String installedVersion)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) metadataUrl.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Archphene/1");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException("Arch package metadata HTTP " + status);
            }
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase().contains("json")) {
                throw new IllegalStateException("Arch package endpoint returned " + contentType);
            }
            String json;
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
            return new ArchPackageUpdateChecker.Result(available,
                    !available.equals(installedVersion));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public String name() {
        return "Arch Linux package metadata";
    }
}