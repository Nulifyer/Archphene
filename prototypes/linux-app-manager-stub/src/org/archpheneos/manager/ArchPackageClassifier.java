package org.archpheneos.manager;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ArchPackageClassifier {
    enum Kind {
        DESKTOP,
        TERMINAL,
        DEPENDENCY
    }

    static final class Result {
        final Kind kind;
        final String executable;
        final String displayName;
        final String desktopFile;
        final boolean terminalDesktopEntry;
        final List<String> commands;

        Result(Kind kind, String executable, String displayName, String desktopFile,
                boolean terminalDesktopEntry, List<String> commands) {
            this.kind = kind;
            this.executable = executable;
            this.displayName = displayName;
            this.desktopFile = desktopFile;
            this.terminalDesktopEntry = terminalDesktopEntry;
            this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
        }
    }

    private static final int MAX_DESKTOP_FILES = 256;
    private static final long MAX_DESKTOP_BYTES = 512 * 1024L;

    private ArchPackageClassifier() {}

    static Result classify(File runtimeRoot, String packageName, String executableHint)
            throws Exception {
        return classify(runtimeRoot, packageName, executableHint, null);
    }

    static Result classify(File runtimeRoot, String packageName, String executableHint,
            java.util.Set<String> sourceCommands) throws Exception {
        File root = runtimeRoot.getCanonicalFile();
        File bin = child(root, "usr/bin");
        List<String> commands = commands(root, bin, sourceCommands);
        List<DesktopEntry> terminalEntries = new ArrayList<>();
        File applications = child(root, "usr/share/applications");
        File[] files = applications.isDirectory()
                ? applications.listFiles((directory, name) -> name.endsWith(".desktop")) : null;
        if (files != null) {
            ArrayList<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, files);
            sorted.sort((left, right) -> left.getName().compareTo(right.getName()));
            int examined = 0;
            for (File file : sorted) {
                if (examined++ >= MAX_DESKTOP_FILES) break;
                DesktopEntry entry = parse(root, file, commands);
                if (entry == null || entry.hidden || entry.noDisplay) continue;
                if (!entry.terminal) {
                    return new Result(Kind.DESKTOP, entry.executable, entry.name,
                            entry.relativePath, false, commands);
                }
                terminalEntries.add(entry);
            }
        }
        if (!terminalEntries.isEmpty()) {
            DesktopEntry entry = preferredEntry(terminalEntries, packageName, executableHint);
            return new Result(Kind.TERMINAL, entry.executable, entry.name,
                    entry.relativePath, true, commands);
        }
        String executable = preferredCommand(commands, packageName, executableHint);
        if (!executable.isEmpty()) {
            return new Result(Kind.TERMINAL, executable, packageName, "", false, commands);
        }
        return new Result(Kind.DEPENDENCY, "", packageName, "", false, commands);
    }

    private static DesktopEntry parse(File root, File file, List<String> commands)
            throws Exception {
        File canonical = file.getCanonicalFile();
        if (!inside(root, canonical) || !canonical.isFile() || canonical.length() <= 0
                || canonical.length() > MAX_DESKTOP_BYTES) return null;
        Map<String, String> values = new LinkedHashMap<>();
        boolean desktopGroup = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(canonical), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[")) {
                    desktopGroup = "[Desktop Entry]".equals(line.trim());
                    continue;
                }
                if (!desktopGroup || line.isEmpty() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator);
                if (!key.matches("[A-Za-z0-9-]{1,64}") || key.indexOf('[') >= 0) continue;
                values.putIfAbsent(key, line.substring(separator + 1));
            }
        }
        if (!"Application".equals(values.get("Type"))) return null;
        String name = values.getOrDefault("Name", "").trim();
        String executable = execProgram(values.getOrDefault("Exec", ""));
        if (name.isEmpty() || executable.isEmpty() || !commands.contains(executable)) return null;
        String tryExec = execProgram(values.getOrDefault("TryExec", ""));
        if (!tryExec.isEmpty() && !commands.contains(tryExec)) return null;
        return new DesktopEntry(executable, name,
                root.toPath().relativize(canonical.toPath()).toString().replace('\\', '/'),
                booleanValue(values.get("Terminal")),
                booleanValue(values.get("Hidden")),
                booleanValue(values.get("NoDisplay")));
    }

    static String execProgram(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) return "";
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(current) && !quoted) {
                break;
            } else {
                token.append(current);
            }
        }
        if (quoted || escaped) return "";
        String program = token.toString();
        if (program.isEmpty() || program.indexOf('%') >= 0 || program.indexOf('=') >= 0) return "";
        int slash = program.lastIndexOf('/');
        program = slash >= 0 ? program.substring(slash + 1) : program;
        return program.matches("[a-zA-Z0-9@._+:-]{1,128}") ? program : "";
    }

    private static List<String> commands(File root, File bin,
            java.util.Set<String> sourceCommands) throws Exception {
        ArrayList<String> result = new ArrayList<>();
        if (!bin.isDirectory()) return result;
        File[] files = bin.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (sourceCommands != null && !sourceCommands.contains(file.getName())) continue;
            File canonical = file.getCanonicalFile();
            if (inside(root, canonical) && canonical.isFile()
                    && file.getName().matches("[a-zA-Z0-9@._+:-]{1,128}")) {
                result.add(file.getName());
            }
        }
        Collections.sort(result);
        return result;
    }

    private static DesktopEntry preferredEntry(List<DesktopEntry> entries, String packageName,
            String executableHint) {
        for (DesktopEntry entry : entries) {
            if (entry.executable.equals(executableHint)) return entry;
        }
        for (DesktopEntry entry : entries) {
            if (entry.executable.equals(packageName)) return entry;
        }
        return entries.get(0);
    }

    private static String preferredCommand(List<String> commands, String packageName,
            String executableHint) {
        if (commands.contains(executableHint)) return executableHint;
        if (commands.contains(packageName)) return packageName;
        return commands.isEmpty() ? "" : commands.get(0);
    }

    private static boolean booleanValue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static File child(File root, String relative) throws Exception {
        File value = new File(root, relative).getCanonicalFile();
        if (!inside(root, value)) throw new SecurityException("Package path escaped runtime root");
        return value;
    }

    private static boolean inside(File root, File value) {
        return value.getPath().startsWith(root.getPath() + File.separator);
    }

    static void verifyForTest(Context context) throws Exception {
        File root = new File(context.getCacheDir(), "package-classifier-test");
        delete(root);
        File bin = new File(root, "usr/bin");
        File apps = new File(root, "usr/share/applications");
        if (!bin.mkdirs() || !apps.mkdirs()) throw new IllegalStateException("Classifier fixture failed");
        write(new File(bin, "editor"), "#!/bin/sh\n");
        write(new File(bin, "dependency-helper"), "#!/bin/sh\n");
        java.util.Set<String> sourceCommands = Collections.singleton("editor");
        write(new File(apps, "editor.desktop"), "[Desktop Entry]\nType=Application\n"
                + "Name=Editor\nExec=editor %F\nTerminal=false\n");
        Result desktop = classify(root, "editor", "editor", sourceCommands);
        write(new File(apps, "editor.desktop"), "[Desktop Entry]\nType=Application\n"
                + "Name=Editor\nExec=editor\nNoDisplay=true\n");
        Result terminal = classify(root, "editor", "editor", sourceCommands);
        Result dependency = classify(root, "library", "library", Collections.emptySet());
        delete(root);
        if (desktop.kind != Kind.DESKTOP || !"editor".equals(desktop.executable)
                || desktop.commands.size() != 1 || !desktop.commands.contains("editor")
                || terminal.kind != Kind.TERMINAL || dependency.kind != Kind.DEPENDENCY
                || !"quoted".equals(execProgram("\"/usr/bin/quoted\" %F"))) {
            throw new SecurityException("Package classification policy mismatch");
        }
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void delete(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static final class DesktopEntry {
        final String executable;
        final String name;
        final String relativePath;
        final boolean terminal;
        final boolean hidden;
        final boolean noDisplay;

        DesktopEntry(String executable, String name, String relativePath, boolean terminal,
                boolean hidden, boolean noDisplay) {
            this.executable = executable;
            this.name = name;
            this.relativePath = relativePath;
            this.terminal = terminal;
            this.hidden = hidden;
            this.noDisplay = noDisplay;
        }
    }
}