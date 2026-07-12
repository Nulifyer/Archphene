package org.archpheneos.manager;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class LinuxPackageManifest {
    private static final Pattern PACKAGE_NAME = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");
    public final String schema;
    public final String packageName;
    public final String name;
    public final String version;
    public final String arch;
    public final String linuxAbi;
    public final String entrypoint;
    public final String payloadAsset;
    public final String payloadInstallPath;
    public final String[] requires;
    public final String[] capabilities;

    private LinuxPackageManifest(
            String schema,
            String packageName,
            String name,
            String version,
            String arch,
            String linuxAbi,
            String entrypoint,
            String payloadAsset,
            String payloadInstallPath,
            String[] requires,
            String[] capabilities) {
        this.schema = schema;
        this.packageName = packageName;
        this.name = name;
        this.version = version;
        this.arch = arch;
        this.linuxAbi = linuxAbi;
        this.entrypoint = entrypoint;
        this.payloadAsset = payloadAsset;
        this.payloadInstallPath = payloadInstallPath;
        this.requires = requires;
        this.capabilities = capabilities;
    }

    public static LinuxPackageManifest loadSample(Context context) throws Exception {
        String json = readAsset(context, "sample-lapk.json");
        JSONObject root = new JSONObject(json);
        JSONObject payload = root.getJSONObject("payload");
        LinuxPackageManifest manifest = new LinuxPackageManifest(
                root.getString("schema"),
                root.getString("package"),
                root.getString("name"),
                root.getString("version"),
                root.getString("arch"),
                root.getString("linuxAbi"),
                root.getString("entrypoint"),
                payload.getString("asset"),
                payload.getString("installPath"),
                readStringArray(root.getJSONArray("requires")),
                readStringArray(root.getJSONArray("capabilities")));
        manifest.validate();
        return manifest;
    }

    private void validate() {
        if (!"org.archpheneos.lapk.v0".equals(schema)) {
            throw new IllegalArgumentException("Unsupported LAPK schema: " + schema);
        }
        if (!PACKAGE_NAME.matcher(packageName).matches()) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        requireText(name, "name");
        requireText(version, "version");
        requireText(arch, "arch");
        requireText(linuxAbi, "linuxAbi");
        requireText(payloadAsset, "payload.asset");
        requireText(payloadInstallPath, "payload.installPath");
        if (!entrypoint.startsWith("/") || entrypoint.contains("/../") || entrypoint.endsWith("/..")) {
            throw new IllegalArgumentException("Entrypoint must be an absolute normalized Linux path");
        }
        if (payloadAsset.contains("/") || payloadAsset.contains("\\") || payloadAsset.equals(".") || payloadAsset.equals("..")) {
            throw new IllegalArgumentException("Payload asset must be an asset filename");
        }
        if (payloadInstallPath.startsWith("/") || payloadInstallPath.startsWith("\\")
                || payloadInstallPath.contains("../") || payloadInstallPath.contains("..\\")) {
            throw new IllegalArgumentException("Payload install path must be relative and normalized");
        }
        rejectDuplicates(requires, "requires");
        rejectDuplicates(capabilities, "capabilities");
    }

    private static void requireText(String value, String field) {
        if (value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be empty"); }
    }

    private static void rejectDuplicates(String[] values, String field) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            requireText(value, field);
            if (!unique.add(value)) { throw new IllegalArgumentException("Duplicate " + field + " value: " + value); }
        }
    }

    public String renderForUi() {
        return "Sample LAPK parsed\n\n"
                + "Package: " + packageName + "\n"
                + "Name: " + name + "\n"
                + "Version: " + version + "\n"
                + "Arch: " + arch + "\n"
                + "Linux ABI: " + linuxAbi + "\n"
                + "Entrypoint: " + entrypoint + "\n"
                + "Payload asset: " + payloadAsset + "\n"
                + "Payload install path: " + payloadInstallPath + "\n\n"
                + "Requires:\n" + renderList(requires)
                + "\nCapabilities:\n" + renderList(capabilities);
    }

    private static String renderList(String[] values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append("- ").append(value).append('\n');
        }
        return out.toString();
    }

    private static String[] readStringArray(JSONArray array) throws Exception {
        String[] values = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.getString(i);
        }
        return values;
    }

    private static String readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}