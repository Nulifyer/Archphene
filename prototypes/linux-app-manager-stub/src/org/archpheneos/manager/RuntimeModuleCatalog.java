package org.archpheneos.manager;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses the APK-signature-protected runtime module catalog. */
final class RuntimeModuleCatalog {
    private static final String SCHEMA = "# org.archphene.runtime-modules.v1";
    private static final int MAX_MODULES = 512;
    private static final int MAX_LINE_LENGTH = 1024;
    private static final long MAX_MODULE_SIZE = 2L * 1024 * 1024 * 1024;
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,128}");
    private static volatile RuntimeModuleCatalog cached;

    static final class Module {
        final String role;
        final String hash;
        final long size;
        final String library;
        final String linkName;

        Module(String role, String hash, long size, String library, String linkName) {
            this.role = role;
            this.hash = hash;
            this.size = size;
            this.library = library;
            this.linkName = linkName;
        }

        Uri uri() {
            return Uri.parse("content://" + RuntimeModuleProvider.AUTHORITY + "/v1/" + hash);
        }
    }

    private final Map<String, Module> byRole;
    private final Map<String, Module> byHash;
    private final List<Module> modules;

    private RuntimeModuleCatalog(Map<String, Module> byRole, Map<String, Module> byHash,
            List<Module> modules) {
        this.byRole = Collections.unmodifiableMap(byRole);
        this.byHash = Collections.unmodifiableMap(byHash);
        this.modules = Collections.unmodifiableList(modules);
    }

    static RuntimeModuleCatalog load(Context context) throws IOException {
        RuntimeModuleCatalog result = cached;
        if (result != null) return result;
        synchronized (RuntimeModuleCatalog.class) {
            if (cached == null) cached = parse(context);
            return cached;
        }
    }

    static void verifyParserForTest() throws Exception {
        String program = "program\t" + repeat('a', 64)
                + "\t4096\tlibprogram.so\tprogram\n";
        String valid = SCHEMA + "\n" + program
                + "libc\t" + repeat('b', 64)
                + "\t8192\tlibc-runtime.so\tlibc.so.6\n";
        RuntimeModuleCatalog parsed = parse(new BufferedReader(new StringReader(valid)));
        if (parsed.modules().size() != 2 || !"libc.so.6".equals(
                parsed.requireRole("libc").linkName)) {
            throw new SecurityException("Runtime catalog parser lost a valid module");
        }
        expectRejected(valid + program);
        expectRejected(SCHEMA + "\nprogram\t" + repeat('a', 64)
                + "\t4096\t../escape.so\tprogram\n");
        expectRejected(SCHEMA + "\nprogram\tshort\t4096\tlibprogram.so\tprogram\n");
        expectRejected(SCHEMA + "\nprogram\t" + repeat('a', 64)
                + "\t4096\tlibprogram.so\t..\n");
        expectRejected(SCHEMA + "\nprogram\t" + repeat('a', 64)
                + "\t0\tlibprogram.so\tprogram\n");
        expectRejected("# org.archphene.runtime-modules.v2\n" + program);
    }

    Module requireRole(String role) throws FileNotFoundException {
        Module module = byRole.get(role);
        if (module == null) throw new FileNotFoundException("Unknown runtime module role");
        return module;
    }

    Module requireUri(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme())
                || !RuntimeModuleProvider.AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2
                || !"v1".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Unknown runtime module URI");
        }
        Module module = byHash.get(uri.getPathSegments().get(1));
        if (module == null || !module.uri().equals(uri)) {
            throw new FileNotFoundException("Unknown runtime module URI");
        }
        return module;
    }

    List<Module> modules() {
        return modules;
    }

    private static RuntimeModuleCatalog parse(Context context) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ArchRuntimePolicy.current().catalogAsset()),
                StandardCharsets.UTF_8))) {
            return parse(reader);
        }
    }

    private static RuntimeModuleCatalog parse(BufferedReader reader) throws IOException {
        Map<String, Module> roles = new HashMap<>();
        Map<String, Module> hashes = new HashMap<>();
        List<Module> modules = new ArrayList<>();
        String header = reader.readLine();
        if (!SCHEMA.equals(header)) throw new IOException("Unsupported runtime catalog schema");
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() > MAX_LINE_LENGTH) {
                throw new IOException("Runtime module catalog exceeds bounds");
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (modules.size() >= MAX_MODULES) {
                throw new IOException("Runtime module catalog exceeds bounds");
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5 || !ROLE.matcher(fields[0]).matches()
                    || !HASH.matcher(fields[1]).matches()
                    || !safeFileName(fields[3])
                    || !safeFileName(fields[4])) {
                throw new IOException("Malformed runtime module catalog entry");
            }
            long size;
            try {
                size = Long.parseLong(fields[2]);
            } catch (NumberFormatException error) {
                throw new IOException("Malformed runtime module size", error);
            }
            if (size <= 0 || size > MAX_MODULE_SIZE) {
                throw new IOException("Runtime module size exceeds bounds");
            }
            Module module = new Module(fields[0], fields[1], size, fields[3], fields[4]);
            if (roles.put(module.role, module) != null
                    || hashes.put(module.hash, module) != null) {
                throw new IOException("Duplicate runtime module catalog entry");
            }
            modules.add(module);
        }
        if (modules.isEmpty()) throw new IOException("Runtime module catalog is empty");
        return new RuntimeModuleCatalog(roles, hashes, modules);
    }

    private static boolean safeFileName(String value) {
        return FILE_NAME.matcher(value).matches()
                && !".".equals(value) && !"..".equals(value);
    }

    private static void expectRejected(String catalog) throws Exception {
        try {
            parse(new BufferedReader(new StringReader(catalog)));
            throw new SecurityException("Malformed runtime catalog was accepted");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
