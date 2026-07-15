package org.archpheneos.manager;

import java.util.Locale;

/** Deterministic user-facing ranking shared by package and installed-app search. */
final class SearchRanking {
    static final int NO_MATCH = Integer.MAX_VALUE;

    private SearchRanking() {}

    static int score(String query, String primary, String alternate, String description) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return 0;
        String name = normalize(primary);
        String other = normalize(alternate);
        String detail = normalize(description);
        String combined = name + " " + other + " " + detail;
        for (String term : normalized.split("\\s+")) {
            if (!combined.contains(term)) return NO_MATCH;
        }
        if (name.equals(normalized)) return 0;
        if (basename(other).equals(normalized)) return 5;
        if (name.startsWith(normalized)) return 10;
        if (wordStarts(name, normalized)) return 20;
        if (name.contains(normalized)) return 30;
        if (wordStarts(other, normalized)) return 40;
        if (other.contains(normalized)) return 50;
        return 60;
    }

    static void verifyForTest() {
        if (!(score("kcalc", "kcalc", "", "")
                < score("kcalc", "kcalc-mobile", "", ""))) {
            throw new IllegalStateException("Exact package ranking failed");
        }
        if (!(score("glmark2-es2-wayland", "glmark2", "usr/bin/glmark2-es2-wayland", "")
                < score("glmark2-es2-wayland", "unrelated", "",
                        "glmark2-es2-wayland demo"))) {
            throw new IllegalStateException("Executable ranking failed");
        }
        if (score("text editor", "mousepad", "extra/mousepad", "Simple text editor")
                == NO_MATCH) {
            throw new IllegalStateException("Multi-term app search failed");
        }
        if (score("video editor", "kcalc", "extra/kcalc", "calculator") != NO_MATCH) {
            throw new IllegalStateException("Unmatched query was accepted");
        }
    }

    private static boolean wordStarts(String value, String query) {
        int index = value.indexOf(query);
        while (index >= 0) {
            if (index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1))) return true;
            index = value.indexOf(query, index + 1);
        }
        return false;
    }

    private static String basename(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}