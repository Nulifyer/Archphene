package org.archpheneos.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App-private registry for user-approved Android project trees. */
final class TerminalProjectStore {
    static final int MAX_PROJECTS = 32;
    private static final String PREFERENCES = "archphene-terminal-projects-v1";
    private static final String PREFIX = "project.";
    private static final String SAFE_ALIAS = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    static final class Project {
        final String alias;
        final Uri treeUri;
        final File mirror;
        final boolean permissionGranted;

        Project(String alias, Uri treeUri, File mirror, boolean permissionGranted) {
            this.alias = alias;
            this.treeUri = treeUri;
            this.mirror = mirror;
            this.permissionGranted = permissionGranted;
        }
    }

    private final Context context;
    private final File projectsRoot;
    private final File stateRoot;
    private final SharedPreferences preferences;

    TerminalProjectStore(Context context, File home) throws IOException {
        this.context = context.getApplicationContext();
        projectsRoot = directory(home, "Projects").getCanonicalFile();
        stateRoot = directory(new File(context.getFilesDir(), "terminal"),
                "project-state").getCanonicalFile();
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static String requireAlias(String value) {
        String alias = value == null ? "" : value.trim();
        if (!alias.matches(SAFE_ALIAS) || ".".equals(alias) || "..".equals(alias)) {
            throw new IllegalArgumentException(
                    "project alias must be 1-64 letters, numbers, dots, dashes, or underscores");
        }
        return alias;
    }

    synchronized Project put(String aliasValue, Uri treeUri) throws IOException {
        String alias = requireAlias(aliasValue);
        Map<String, String> stored = storedMappings();
        String existing = stored.get(alias);
        if (existing != null && !existing.equals(treeUri.toString())) {
            throw new IllegalStateException("project alias is already mapped; remove it before remapping");
        }
        if (!stored.containsKey(alias) && stored.size() >= MAX_PROJECTS) {
            throw new IllegalStateException("at most " + MAX_PROJECTS + " projects may be mapped");
        }
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (!entry.getKey().equals(alias) && entry.getValue().equals(treeUri.toString())) {
                throw new IllegalStateException("that Android folder is already mapped as "
                        + entry.getKey());
            }
        }
        if (!preferences.edit().putString(PREFIX + alias, treeUri.toString()).commit()) {
            throw new IOException("could not persist project mapping");
        }
        return project(alias, treeUri);
    }

    synchronized Project require(String aliasValue) throws IOException {
        String alias = requireAlias(aliasValue);
        String value = preferences.getString(PREFIX + alias, null);
        if (value == null) throw new IllegalArgumentException("unknown project: " + alias);
        Uri uri = Uri.parse(value);
        if (!hasGrant(uri)) {
            throw new SecurityException("Android folder permission was revoked for " + alias
                    + "; remove and add the project again");
        }
        return project(alias, uri);
    }

    synchronized List<Project> list() throws IOException {
        ArrayList<Project> projects = new ArrayList<>();
        for (Map.Entry<String, String> entry : storedMappings().entrySet()) {
            projects.add(project(entry.getKey(), Uri.parse(entry.getValue())));
        }
        Collections.sort(projects, (left, right) -> left.alias.compareTo(right.alias));
        return projects;
    }

    synchronized boolean remove(String aliasValue) throws IOException {
        String alias = requireAlias(aliasValue);
        String value = preferences.getString(PREFIX + alias, null);
        if (value == null) return false;
        File privateState = new File(stateRoot, alias).getCanonicalFile();
        requireChild(stateRoot, privateState);
        deleteRecursively(privateState);
        if (!preferences.edit().remove(PREFIX + alias).commit()) {
            throw new IOException("could not remove project mapping");
        }
        Uri uri = Uri.parse(value);
        try {
            context.getContentResolver().releasePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The provider or user may already have revoked this permission.
        }
        return true;
    }

    File manifest(Project project) throws IOException {
        File directory = directory(stateRoot, project.alias).getCanonicalFile();
        requireChild(stateRoot, directory);
        return new File(directory, "manifest.json");
    }

    private Project project(String alias, Uri uri) throws IOException {
        File mirror = directory(projectsRoot, alias).getCanonicalFile();
        requireChild(projectsRoot, mirror);
        return new Project(alias, uri, mirror, hasGrant(uri));
    }

    private Map<String, String> storedMappings() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || !(entry.getValue() instanceof String)) continue;
            String alias = entry.getKey().substring(PREFIX.length());
            if (alias.matches(SAFE_ALIAS)) result.put(alias, (String) entry.getValue());
        }
        return result;
    }

    private boolean hasGrant(Uri uri) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()
                    && permission.isWritePermission()) return true;
        }
        return false;
    }

    private static File directory(File parent, String name) throws IOException {
        File value = new File(parent, name);
        if (!value.isDirectory() && !value.mkdirs()) {
            throw new IOException("could not create " + value);
        }
        if (!value.isDirectory()) throw new IOException("project path is not a directory");
        return value;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("could not list " + file);
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("could not delete " + file);
    }

    private static void requireChild(File parent, File child) throws IOException {
        String root = parent.getCanonicalPath();
        String path = child.getCanonicalPath();
        if (!path.startsWith(root + File.separator)) {
            throw new SecurityException("project path escaped its private root");
        }
    }
}
