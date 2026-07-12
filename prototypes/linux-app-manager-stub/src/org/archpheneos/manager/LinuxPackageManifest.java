package org.archpheneos.manager;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class LinuxPackageManifest {
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
        return new LinuxPackageManifest(
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