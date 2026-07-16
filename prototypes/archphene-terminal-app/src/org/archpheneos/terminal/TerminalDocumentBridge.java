package org.archpheneos.terminal;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Copies explicitly selected Android documents across the Terminal home boundary. */
final class TerminalDocumentBridge {
    interface Reporter {
        void report(String message);
    }

    private static final int REQUEST_IMPORT = 4101;
    private static final int REQUEST_EXPORT = 4102;
    private static final int REQUEST_PROJECT_TREE = 4103;
    private static final long MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String STATE_OPERATION = "archphene_document_operation";
    private static final String STATE_PATH = "archphene_document_path";
    private static final String STATE_ARGUMENT = "archphene_document_argument";
    private static final String STATE_REQUEST = "archphene_document_request";

    private final Activity activity;
    private final File home;
    private final Reporter reporter;
    private final TerminalProjectStore projects;
    private static final Set<String> SYNCING_PROJECTS = new HashSet<>();
    private String pendingOperation;
    private String pendingRequestId;
    private String pendingArgument;
    private File pendingPath;

    TerminalDocumentBridge(Activity activity, File home, Reporter reporter, Bundle state)
            throws IOException {
        this.activity = activity;
        this.home = home.getCanonicalFile();
        this.reporter = reporter;
        projects = new TerminalProjectStore(activity, this.home);
        if (state != null) {
            String operation = state.getString(STATE_OPERATION);
            String requestId = state.getString(STATE_REQUEST);
            String argument = state.getString(STATE_ARGUMENT);
            String path = state.getString(STATE_PATH);
            if (operation != null && requestId != null) {
                pendingOperation = operation;
                pendingRequestId = requestId;
                pendingArgument = argument;
                if (path != null) pendingPath = visiblePath(path, "import".equals(operation));
            }
        }
    }

    void saveState(Bundle state) {
        if (pendingOperation == null || pendingRequestId == null) return;
        state.putString(STATE_OPERATION, pendingOperation);
        state.putString(STATE_REQUEST, pendingRequestId);
        state.putString(STATE_ARGUMENT, pendingArgument);
        if (pendingPath != null) state.putString(STATE_PATH, pendingPath.getAbsolutePath());
    }

    void request(String requestId, String operation, String argument) {
        activity.runOnUiThread(() -> {
            try {
                if ("import".equals(operation) || "export".equals(operation)
                        || "project-add".equals(operation)) {
                    if (pendingOperation != null) {
                        throw new IllegalStateException("another document picker is already open");
                    }
                    pendingRequestId = requestId;
                    pendingArgument = argument;
                    if ("import".equals(operation)) launchImport(argument);
                    else if ("export".equals(operation)) launchExport(argument);
                    else launchProjectTree(argument);
                    return;
                }
                new Thread(() -> runProjectOperation(requestId, operation, argument),
                        "archphene-" + operation).start();
            } catch (Exception error) {
                fail(requestId, operation, error);
            }
        });
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT && requestCode != REQUEST_EXPORT
                && requestCode != REQUEST_PROJECT_TREE) return false;
        String operation = pendingOperation;
        String requestId = pendingRequestId;
        String argument = pendingArgument;
        File path = pendingPath;
        clearPending();
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            finish(requestId, operation == null ? "document" : operation, 0,
                    "cancelled", "Android picker cancelled");
            return true;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                if (requestCode == REQUEST_IMPORT && "import".equals(operation)) {
                    importDocument(uri, path);
                    finish(requestId, operation, 100, "success",
                            "imported document into " + relative(path));
                } else if (requestCode == REQUEST_EXPORT && "export".equals(operation)) {
                    exportDocument(uri, path);
                    finish(requestId, operation, 100, "success",
                            "exported " + relative(path));
                } else if (requestCode == REQUEST_PROJECT_TREE
                        && "project-add".equals(operation)) {
                    addProject(requestId, argument, uri, data.getFlags());
                } else {
                    throw new SecurityException("stale document picker result");
                }
            } catch (Exception error) {
                fail(requestId, operation, error);
            }
        }, "archphene-document-" + (operation == null ? "result" : operation)).start();
        return true;
    }

    private void launchImport(String argument) throws Exception {
        File directory = visiblePath(empty(argument) ? "Downloads" : argument, true);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + relative(directory));
        }
        if (!directory.isDirectory()) throw new IOException("import destination is not a directory");
        pendingOperation = "import";
        pendingPath = directory;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(picker, REQUEST_IMPORT);
        publish(pendingRequestId, "import", 0, false, "running",
                "choose an Android document to import into " + relative(directory));
    }

    private void launchExport(String argument) throws Exception {
        if (empty(argument)) throw new IllegalArgumentException("usage: archphene-export <home-file>");
        File source = visiblePath(argument, false);
        if (!source.isFile()) throw new IOException("export source is not a file");
        pendingOperation = "export";
        pendingPath = source;
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime(source.getName()))
                .putExtra(Intent.EXTRA_TITLE, source.getName())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.startActivityForResult(picker, REQUEST_EXPORT);
        publish(pendingRequestId, "export", 0, false, "running",
                "choose an Android location for " + relative(source));
    }

    private void launchProjectTree(String aliasValue) throws Exception {
        String alias = TerminalProjectStore.requireAlias(aliasValue);
        pendingOperation = "project-add";
        pendingArgument = alias;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        activity.startActivityForResult(picker, REQUEST_PROJECT_TREE);
        publish(pendingRequestId, "project-add", 0, false, "running",
                "choose the Android folder to map as $HOME/Projects/" + alias);
    }

    private void addProject(String requestId, String aliasValue, Uri uri, int resultFlags)
            throws Exception {
        String alias = TerminalProjectStore.requireAlias(aliasValue);
        int grants = resultFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (grants != required) throw new SecurityException(
                "the selected provider did not grant read and write access");
        boolean alreadyPersisted = hasPersistedGrant(uri);
        activity.getContentResolver().takePersistableUriPermission(uri, required);
        TerminalProjectStore.Project project;
        try {
            project = projects.put(alias, uri);
        } catch (Exception error) {
            if (!alreadyPersisted) {
                try {
                    activity.getContentResolver().releasePersistableUriPermission(uri, required);
                } catch (SecurityException ignored) {
                    // The provider may already have withdrawn a failed new grant.
                }
            }
            throw error;
        }
        synchronize(requestId, project);
    }

    private boolean hasPersistedGrant(Uri uri) {
        for (android.content.UriPermission permission
                : activity.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()
                    && permission.isWritePermission()) return true;
        }
        return false;
    }

    private void runProjectOperation(String requestId, String operation, String argument) {
        try {
            if ("project-sync".equals(operation)) {
                synchronize(requestId, projects.require(argument));
            } else if ("project-list".equals(operation)) {
                List<TerminalProjectStore.Project> mapped = projects.list();
                StringBuilder status = new StringBuilder();
                for (TerminalProjectStore.Project project : mapped) {
                    if (status.length() > 0) status.append("; ");
                    status.append(project.alias).append("=$HOME/Projects/").append(project.alias);
                    if (!project.permissionGranted) status.append(" (Android permission revoked)");
                }
                finish(requestId, operation, 100, "success",
                        status.length() == 0 ? "no projects mapped" : status.toString());
            } else if ("project-path".equals(operation)) {
                TerminalProjectStore.Project project = projects.require(argument);
                finish(requestId, operation, 100, "success",
                        "$HOME/Projects/" + project.alias);
            } else if ("project-remove".equals(operation)) {
                String alias = TerminalProjectStore.requireAlias(argument);
                synchronized (SYNCING_PROJECTS) {
                    if (SYNCING_PROJECTS.contains(alias)) {
                        throw new IllegalStateException("project is currently synchronizing");
                    }
                }
                boolean removed = projects.remove(alias);
                finish(requestId, operation, 100, "success", removed
                        ? "removed mapping for " + alias + "; local files were kept"
                        : "project was not mapped: " + alias);
            } else {
                throw new IllegalArgumentException("unsupported project operation");
            }
        } catch (Exception error) {
            fail(requestId, operation, error);
        }
    }

    private void synchronize(String requestId, TerminalProjectStore.Project project)
            throws Exception {
        synchronized (SYNCING_PROJECTS) {
            if (!SYNCING_PROJECTS.add(project.alias)) {
                throw new IllegalStateException("project is already synchronizing");
            }
        }
        try {
            publish(requestId, "project-sync", 2, false, "running",
                    "synchronizing " + project.alias);
            TerminalProjectSync.Result result = new TerminalProjectSync(
                    activity.getContentResolver()).synchronize(project,
                    projects.manifest(project), (percent, status) -> publish(requestId,
                            "project-sync", percent, false, "running", status));
            finish(requestId, "project-sync", 100, "success",
                    project.alias + ": " + result.summary());
        } finally {
            synchronized (SYNCING_PROJECTS) {
                SYNCING_PROJECTS.remove(project.alias);
            }
        }
    }

    private void clearPending() {
        pendingOperation = null;
        pendingRequestId = null;
        pendingArgument = null;
        pendingPath = null;
    }

    private void fail(String requestId, String operation, Throwable error) {
        String phase = operation == null ? "document" : operation;
        String status = safeMessage(error);
        reporter.report(phase + " failed: " + status);
        finish(requestId, phase, 0, "error", status);
    }

    private void finish(String requestId, String phase, int percent, String outcome,
            String status) {
        try {
            publish(requestId, phase, percent, true, outcome, status);
        } catch (Exception error) {
            reporter.report("could not return command result: " + safeMessage(error));
        }
    }

    private void publish(String requestId, String phase, int percent, boolean terminal,
            String outcome, String status) throws Exception {
        TerminalCommandProvider.publish(activity, requestId, phase, percent,
                terminal, outcome, status);
    }
    private void importDocument(Uri uri, File directory) throws Exception {
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("import destination disappeared");
        }
        String name = safeName(displayName(uri));
        File destination = uniqueDestination(directory, name);
        File staging = File.createTempFile(".archphene-import-", ".tmp", directory);
        boolean published = false;
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
                OutputStream output = new FileOutputStream(staging)) {
            if (input == null) throw new IOException("document provider returned no input stream");
            copyBounded(input, output);
            if (!staging.renameTo(destination)) throw new IOException("could not publish imported file");
            published = true;
            reporter.report("imported " + relative(destination));
        } finally {
            if (!published && staging.exists()) staging.delete();
        }
    }

    private void exportDocument(Uri uri, File source) throws Exception {
        if (source == null || !source.isFile()) throw new IOException("export source disappeared");
        try (InputStream input = new FileInputStream(source);
                OutputStream output = activity.getContentResolver().openOutputStream(uri, "rwt")) {
            if (output == null) throw new IOException("document provider returned no output stream");
            copyBounded(input, output);
        }
        reporter.report("exported " + relative(source));
    }

    private File visiblePath(String value, boolean directory) throws IOException {
        File candidate = new File(value);
        if (!candidate.isAbsolute()) candidate = new File(home, value);
        candidate = candidate.getCanonicalFile();
        String homePath = home.getPath();
        if (!candidate.equals(home)
                && !candidate.getPath().startsWith(homePath + File.separator)) {
            throw new SecurityException("path is outside Archphene Home");
        }
        String relative = candidate.equals(home) ? "" :
                candidate.getPath().substring(homePath.length() + 1);
        for (String segment : relative.split(java.util.regex.Pattern.quote(File.separator))) {
            if (segment.startsWith(".")) {
                throw new SecurityException("hidden Terminal paths are private");
            }
        }
        if (!directory && candidate.equals(home)) {
            throw new SecurityException("home directory is not a file");
        }
        return candidate;
    }

    private String relative(File file) throws IOException {
        String path = file.getCanonicalPath();
        if (path.equals(home.getPath())) return "$HOME";
        if (!path.startsWith(home.getPath() + File.separator)) {
            throw new SecurityException("path escaped Archphene Home");
        }
        return "$HOME/" + path.substring(home.getPath().length() + 1)
                .replace(File.separatorChar, '/');
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // A provider may omit metadata; the URI segment remains a bounded fallback.
        }
        String segment = uri.getLastPathSegment();
        return empty(segment) ? "android-document" : segment;
    }

    private static File uniqueDestination(File directory, String name) throws IOException {
        File candidate = new File(directory, name).getCanonicalFile();
        if (!candidate.getParentFile().equals(directory.getCanonicalFile())) {
            throw new SecurityException("document name escaped its destination");
        }
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index <= 9999; index++) {
            candidate = new File(directory, stem + " (" + index + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("too many files with the same name");
    }

    private static String safeName(String value) {
        String safe = value == null ? "android-document"
                : value.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..") || safe.startsWith(".")) {
            safe = "android-document";
        }
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return value == null ? "application/octet-stream" : value;
    }

    private static void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > MAX_TRANSFER_BYTES) {
                throw new IOException("document exceeds the 2 GiB transfer limit");
            }
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return empty(value) ? error.getClass().getSimpleName() : value;
    }
}
