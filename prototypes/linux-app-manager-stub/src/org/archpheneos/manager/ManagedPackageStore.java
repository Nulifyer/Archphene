package org.archpheneos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit non-launcher packages installed into a shared Archphene environment. */
final class ManagedPackageStore {
    static final String TERMINAL = "terminal";
    private static final String PREFS = "archphene-managed-packages-v1";
    private static final String ENTRIES = "entries";
    private static final String SAFE_ID = "[a-zA-Z0-9@._+:-]{1,128}";
    private static final String HASH = "[0-9a-f]{64}";

    static final class Entry {
        final String kind;
        final String repository;
        final String name;
        final String architecture;
        final String version;
        final String executable;
        final List<String> commands;
        final String runtimePackId;
        final long installedAt;

        Entry(String kind, String repository, String name, String architecture, String version,
                String executable, List<String> commands, String runtimePackId,
                long installedAt) {
            this.kind = kind;
            this.repository = repository;
            this.name = name;
            this.architecture = architecture;
            this.version = version;
            this.executable = executable;
            this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
            this.runtimePackId = runtimePackId;
            this.installedAt = installedAt;
        }

        String identity() {
            return repository + "/" + name + "/" + architecture;
        }

        String stateKey() {
            return "org.archpheneos.managed.p" + digest(identity()).substring(0, 32);
        }

        ArchPackageRepository.PackageResult source() {
            return new ArchPackageRepository.PackageResult(name, repository, architecture,
                    version, "", false, "usr/bin/" + executable, executable);
        }
    }

    private ManagedPackageStore() {}

    static synchronized List<Entry> list(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences(context).getString(ENTRIES, "[]"));
            if (values.length() > 512) throw new SecurityException("Too many managed packages");
            for (int index = 0; index < values.length(); index++) {
                result.add(decode(values.getJSONObject(index)));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Managed package state is invalid", error);
        }
        return result;
    }

    static Entry install(Context context,
            ArchPackageRepository.PackageResult source,
            ArchPackageRuntime.StagedTransaction staged) throws Exception {
        if (staged.classification.kind != ArchPackageClassifier.Kind.TERMINAL) {
            throw new IllegalArgumentException("Only terminal packages use the shared environment");
        }
        RuntimePackStore.Pack pack = RuntimePackStore.load(context, staged.runtimePackId);
        if (!source.name.equals(pack.sourcePackage)
                || !staged.classification.executable.equals(pack.executableName)) {
            throw new SecurityException("Managed package runtime identity mismatch");
        }
        Entry entry = new Entry(TERMINAL, source.repository, source.name,
                source.architecture, staged.sourceVersion(), staged.classification.executable,
                staged.classification.commands, staged.runtimePackId,
                System.currentTimeMillis());
        synchronized (ManagedPackageStore.class) {
            ArrayList<Entry> entries = new ArrayList<>(list(context));
            entries.removeIf(value -> value.identity().equals(entry.identity()));
            entries.add(entry);
            entries.sort((left, right) -> left.identity().compareTo(right.identity()));
            write(context, entries);
        }
        return entry;
    }

    static void remove(Context context, Entry entry) throws Exception {
        boolean removed;
        synchronized (ManagedPackageStore.class) {
            ArrayList<Entry> entries = new ArrayList<>(list(context));
            removed = entries.removeIf(value -> value.identity().equals(entry.identity()));
            if (removed) write(context, entries);
        }
        if (removed) RuntimePackStore.releaseManagedPack(context, entry.runtimePackId);
    }

    static synchronized Set<String> packIds(Context context) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Entry entry : list(context)) result.add(entry.runtimePackId);
        return result;
    }

    static void verifyForTest(Context context) {
        SharedPreferences state = preferences(context);
        String previous = state.getString(ENTRIES, null);
        try {
            Entry fixture = new Entry(TERMINAL, "extra", "btop", "x86_64", "1.4.5-1",
                    "btop", java.util.Arrays.asList("btop", "btop-helper"),
                    repeat('a', 64), 1234L);
            write(context, Collections.singletonList(fixture));
            List<Entry> restored = list(context);
            if (restored.size() != 1 || !fixture.identity().equals(restored.get(0).identity())
                    || !fixture.commands.equals(restored.get(0).commands)
                    || !fixture.stateKey().equals(restored.get(0).stateKey())) {
                throw new IllegalStateException("Managed package state did not round-trip");
            }
        } finally {
            SharedPreferences.Editor editor = state.edit();
            if (previous == null) editor.remove(ENTRIES);
            else editor.putString(ENTRIES, previous);
            editor.commit();
        }
    }

    private static Entry decode(JSONObject value) throws Exception {
        String kind = value.getString("kind");
        String repository = value.getString("repository");
        String name = value.getString("name");
        String architecture = value.getString("architecture");
        String version = value.getString("version");
        String executable = value.getString("executable");
        String pack = value.getString("runtimePackId");
        JSONArray commandValues = value.getJSONArray("commands");
        if (!TERMINAL.equals(kind) || !repository.matches("[a-z0-9-]{1,32}")
                || !name.matches(SAFE_ID) || !architecture.matches(SAFE_ID)
                || !version.matches(SAFE_ID) || !executable.matches(SAFE_ID)
                || !pack.matches(HASH) || commandValues.length() < 1
                || commandValues.length() > 512) {
            throw new SecurityException("Malformed managed package entry");
        }
        ArrayList<String> commands = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < commandValues.length(); index++) {
            String command = commandValues.getString(index);
            if (!command.matches(SAFE_ID) || !unique.add(command)) {
                throw new SecurityException("Malformed managed package commands");
            }
            commands.add(command);
        }
        if (!commands.contains(executable)) {
            throw new SecurityException("Managed package executable is missing");
        }
        return new Entry(kind, repository, name, architecture, version, executable,
                commands, pack, value.getLong("installedAt"));
    }

    private static void write(Context context, List<Entry> entries) {
        try {
            JSONArray values = new JSONArray();
            for (Entry entry : entries) {
                JSONObject value = new JSONObject();
                value.put("kind", entry.kind);
                value.put("repository", entry.repository);
                value.put("name", entry.name);
                value.put("architecture", entry.architecture);
                value.put("version", entry.version);
                value.put("executable", entry.executable);
                value.put("commands", new JSONArray(entry.commands));
                value.put("runtimePackId", entry.runtimePackId);
                value.put("installedAt", entry.installedAt);
                values.put(value);
            }
            if (!preferences(context).edit().putString(ENTRIES, values.toString()).commit()) {
                throw new IllegalStateException("Could not commit managed package state");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist managed package state", error);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte part : hash) output.append(String.format(Locale.ROOT, "%02x", part & 0xff));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder output = new StringBuilder(count);
        for (int index = 0; index < count; index++) output.append(value);
        return output.toString();
    }
}