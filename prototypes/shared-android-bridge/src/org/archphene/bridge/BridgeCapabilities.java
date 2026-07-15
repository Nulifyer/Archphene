package org.archphene.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Validated feature contract between generated wrappers and Android bridge brokers. */
final class BridgeCapabilities {
    static final String META = "org.archphene.bridge.capabilities";
    static final String DOCUMENTS = "documents";
    private static final Set<String> REQUIRED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("wayland", "input", "ime", "clipboard", "runtime-pack",
                    "home-documents")));
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("wayland", "input", "ime", "clipboard", "runtime-pack",
                    "home-documents", DOCUMENTS)));
    private static final String LEGACY =
            "wayland,input,ime,clipboard,runtime-pack,home-documents,documents";

    private BridgeCapabilities() {}

    static Set<String> read(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            String raw = info.metaData == null ? LEGACY : info.metaData.getString(META, LEGACY);
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String candidate : raw.split(",")) {
                String value = candidate.trim();
                if (!ALLOWED.contains(value) || !result.add(value)) {
                    throw new SecurityException("Invalid bridge capability: " + value);
                }
            }
            if (!result.containsAll(REQUIRED)) {
                throw new SecurityException("Required bridge capabilities are missing");
            }
            return Collections.unmodifiableSet(result);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Could not read bridge capabilities", error);
        }
    }
}
