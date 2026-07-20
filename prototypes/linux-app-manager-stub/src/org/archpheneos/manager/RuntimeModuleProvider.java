package org.archpheneos.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Read-only, package-granted access to immutable runtime-pack files. */
public final class RuntimeModuleProvider extends ContentProvider {
    public static final String AUTHORITY = "org.archpheneos.manager.runtime";
    public static final String ACTIVE_PACK_METHOD = "org.archphene.runtime.ACTIVE_PACK_V1";
    public static final String RELEASE_LEASE_METHOD =
            "org.archphene.runtime.RELEASE_LEASE_V1";
    public static final String APPEARANCE_METHOD =
            "org.archphene.runtime.APPEARANCE_V1";
    public static final String TERMINAL_CATALOG_METHOD =
            "org.archphene.runtime.TERMINAL_CATALOG_V2";
    public static final String TERMINAL_PACK_METHOD =
            "org.archphene.runtime.TERMINAL_PACK_V1";
    private static final String TERMINAL_PACKAGE = "org.archpheneos.terminal";
    private final Map<String, File> verifiedModules = new HashMap<>();

    @Override
    public boolean onCreate() {
        try {
            RuntimeModuleCatalog.load(providerContext());
            return true;
        } catch (IOException error) {
            Log.e("ArchpheneRuntime", "Runtime module catalog rejected", error);
            return false;
        }
    }

    public static Uri uriForRole(Context context, String role) throws IOException {
        return RuntimeModuleCatalog.load(context).requireRole(role).uri();
    }

    public static String linkNameForRole(Context context, String role) throws IOException {
        return RuntimeModuleCatalog.load(context).requireRole(role).linkName;
    }

    public static void revokeAll(Context context) throws IOException {
        for (RuntimeModuleCatalog.Module module : RuntimeModuleCatalog.load(context).modules()) {
            context.revokeUriPermission(module.uri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!ACTIVE_PACK_METHOD.equals(method) && !RELEASE_LEASE_METHOD.equals(method)
                && !APPEARANCE_METHOD.equals(method)
                && !TERMINAL_CATALOG_METHOD.equals(method)
                && !TERMINAL_PACK_METHOD.equals(method)) {
            throw new UnsupportedOperationException("Unsupported runtime provider method");
        }
        try {
            if (APPEARANCE_METHOD.equals(method)) return appearanceBundle();
            if (TERMINAL_CATALOG_METHOD.equals(method)) return terminalCatalogBundle();
            if (RELEASE_LEASE_METHOD.equals(method)) {
                String caller = isTerminalCaller() ? requireTerminalCaller()
                        : requireWrapperCaller();
                IBinder token = extras == null ? null : extras.getBinder("lease_token");
                RuntimePackLeaseRegistry.release(caller, arg, token);
                return new Bundle();
            }
            if (TERMINAL_PACK_METHOD.equals(method)) {
                String caller = requireTerminalCaller();
                IBinder token = extras == null ? null : extras.getBinder("lease_token");
                RuntimePackLeaseRegistry.acquire(providerContext(), caller, arg, token);
                try {
                    return terminalPackBundle(arg);
                } catch (Exception error) {
                    RuntimePackLeaseRegistry.release(caller, arg, token);
                    throw error;
                }
            }
            String caller = requireWrapperCaller();
            boolean external = Binder.getCallingUid() != android.os.Process.myUid();
            IBinder token = extras == null ? null : extras.getBinder("lease_token");
            RuntimePackStore.Pack pack;
            synchronized (RuntimePackStore.class) {
                pack = RuntimePackStore.active(providerContext(), caller);
                if (external) {
                    RuntimePackLeaseRegistry.acquire(
                            providerContext(), caller, pack.id, token);
                }
            }
            try {
                RuntimePackStore.grantActive(providerContext(), caller);
                SelectedProgram selected = selectWrapperProgram(caller, pack);
                RuntimePackStore.Module program = selected.module;
                RuntimePackStore.Module data = pack.data();
                Uri loader = uriForRole(providerContext(), "glibc-loader");
                providerContext().grantUriPermission(caller, loader,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                List<RuntimePackStore.Module> libraries = pack.libraries();
                String[] libraryUris = new String[libraries.size()];
                String[] libraryNames = new String[libraries.size()];
                for (int index = 0; index < libraries.size(); index++) {
                    RuntimePackStore.Module library = libraries.get(index);
                    libraryUris[index] = library.uri(pack.id).toString();
                    libraryNames[index] = library.linkName;
                }
                ArrayList<RuntimePackStore.Module> commands = new ArrayList<>();
                ArrayList<String> commandNames = new ArrayList<>();
                RuntimePackStore.Module primary = pack.requireKind("program");
                commands.add(primary);
                commandNames.add(pack.executableName);
                for (RuntimePackStore.Module command : pack.commands()) {
                    commands.add(command);
                    commandNames.add(command.linkName);
                }
                String[] commandUris = new String[commands.size()];
                for (int index = 0; index < commands.size(); index++) {
                    commandUris[index] = commands.get(index).uri(pack.id).toString();
                }
                Bundle result = new Bundle();
                result.putString("pack_id", pack.id);
                result.putString("program_uri", program.uri(pack.id).toString());
                result.putString("program_name", selected.name);
                result.putString("loader_uri", loader.toString());
                result.putStringArray("library_uris", libraryUris);
                result.putStringArray("library_names", libraryNames);
                result.putStringArray("command_uris", commandUris);
                result.putStringArray("command_names", commandNames.toArray(new String[0]));
                result.putString("toolkit", pack.toolkit());
                if (data != null) {
                    result.putString("data_uri", data.uri(pack.id).toString());
                }
                return result;
            } catch (Exception error) {
                if (external) RuntimePackLeaseRegistry.release(caller, pack.id, token);
                throw error;
            }
        } catch (Exception error) {
            SecurityException failure = new SecurityException(
                    "Caller has no active Archphene runtime pack");
            failure.initCause(error);
            throw failure;
        }
    }
    private Bundle terminalCatalogBundle() throws Exception {
        requireTerminalCaller();
        List<ManagedPackageStore.Entry> entries = ManagedPackageStore.list(providerContext());
        if (entries.size() > 512) throw new SecurityException("Too many Terminal packages");
        String[] encoded = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            ManagedPackageStore.Entry entry = entries.get(index);
            encoded[index] = entry.kind + "\t" + entry.runtimePackId + "\t"
                    + entry.name + "\t" + entry.version + "\t" + entry.repository + "\t"
                    + entry.executable + "\t" + String.join(",", entry.commands);
        }
        Bundle result = new Bundle();
        result.putStringArray("packages", encoded);
        return result;
    }
    private Bundle terminalPackBundle(String packId) throws Exception {
        requireTerminalCaller();
        if (packId == null || !ManagedPackageStore.packIds(providerContext()).contains(packId)) {
            throw new SecurityException("Terminal runtime pack is not managed");
        }
        RuntimePackStore.Pack pack = RuntimePackStore.load(providerContext(), packId);
        if (pack.modules.size() > 4096) throw new SecurityException("Terminal pack is too large");
        int count = pack.modules.size();
        String[] kinds = new String[count];
        String[] names = new String[count];
        String[] uris = new String[count];
        String[] hashes = new String[count];
        long[] sizes = new long[count];
        for (int index = 0; index < count; index++) {
            RuntimePackStore.Module module = pack.modules.get(index);
            kinds[index] = module.kind;
            names[index] = "program".equals(module.kind) ? pack.executableName : module.linkName;
            uris[index] = module.uri(pack.id).toString();
            hashes[index] = module.hash;
            sizes[index] = module.size;
        }
        Bundle result = new Bundle();
        result.putStringArray("kinds", kinds);
        result.putStringArray("names", names);
        result.putStringArray("uris", uris);
        result.putStringArray("hashes", hashes);
        result.putLongArray("sizes", sizes);
        return result;
    }
    private String requireTerminalCaller() {
        Context context;
        try {
            context = providerContext();
        } catch (FileNotFoundException error) {
            throw new SecurityException("Runtime provider is unavailable", error);
        }
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        boolean ownsTerminal = false;
        if (packages != null) {
            for (String value : packages) {
                if (TERMINAL_PACKAGE.equals(value)) {
                    ownsTerminal = true;
                    break;
                }
            }
        }
        if (!ownsTerminal || context.getPackageManager().checkSignatures(
                context.getPackageName(), TERMINAL_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Terminal caller is not signed by this Archphene manager");
        }
        return TERMINAL_PACKAGE;
    }
    private boolean isTerminalCaller() {
        try {
            requireTerminalCaller();
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
    private Bundle appearanceBundle() throws FileNotFoundException {
        Context context = providerContext();
        Bundle result = new Bundle();
        result.putString("theme_mode", ManagerStateStore.linuxThemeMode(context));
        result.putInt("scale_percent", ManagerStateStore.linuxScalePercent(context));
        result.putInt("font_percent", ManagerStateStore.linuxFontPercent(context));
        result.putBoolean("material_you", ManagerStateStore.materialYou(context));
        return result;
    }
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new SecurityException("Runtime modules are read-only");
        }
        return ParcelFileDescriptor.open(moduleForAuthorized(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File module;
        try {
            module = moduleForAuthorized(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(uri.getLastPathSegment());
            else if (OpenableColumns.SIZE.equals(column)) row.add(module.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        try {
            moduleForAuthorized(uri);
            return "application/vnd.archphene.runtime-module";
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Runtime modules are immutable");
    }

    private synchronized File moduleFor(Uri uri) throws FileNotFoundException {
        if (uri != null && uri.getPathSegments().size() >= 2
                && "pack".equals(uri.getPathSegments().get(0))) {
            try {
                return RuntimePackStore.requireUri(providerContext(), uri).file;
            } catch (Exception error) {
                FileNotFoundException failure = new FileNotFoundException(
                        "Runtime-pack module is unavailable");
                failure.initCause(error);
                throw failure;
            }
        }
        RuntimeModuleCatalog.Module expected;
        try {
            expected = RuntimeModuleCatalog.load(providerContext()).requireUri(uri);
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Runtime module catalog is unavailable");
            failure.initCause(error);
            throw failure;
        }
        File cached = verifiedModules.get(expected.hash);
        if (cached != null) return cached;
        File nativeRoot;
        File module;
        try {
            nativeRoot = new File(providerContext().getApplicationInfo().nativeLibraryDir)
                    .getCanonicalFile();
            module = new File(nativeRoot, expected.library).getCanonicalFile();
        } catch (Exception error) {
            throw new FileNotFoundException("Runtime module path is invalid");
        }
        if (!module.getParentFile().equals(nativeRoot) || !module.isFile()
                || module.length() != expected.size || !expected.hash.equals(sha256(module))) {
            throw new FileNotFoundException("Verified runtime module is missing");
        }
        verifiedModules.put(expected.hash, module);
        return module;
    }

    private File moduleForAuthorized(Uri uri) throws FileNotFoundException {
        if (Binder.getCallingUid() == android.os.Process.myUid()) return moduleFor(uri);
        try {
            if (isTerminalCaller()) {
                List<String> segments = uri == null ? java.util.Collections.emptyList()
                        : uri.getPathSegments();
                if (segments.size() != 4 || !"pack".equals(segments.get(0))
                        || !"v1".equals(segments.get(1))
                        || !ManagedPackageStore.packIds(providerContext()).contains(segments.get(2))) {
                    throw new SecurityException("Terminal requested an unmanaged runtime pack");
                }
                return RuntimePackStore.requireUri(providerContext(), uri).file;
            }
            String caller = requireWrapperCaller();
            RuntimePackStore.Pack pack = RuntimePackStore.active(providerContext(), caller);
            if (uriForRole(providerContext(), "glibc-loader").equals(uri)) {
                return moduleFor(uri);
            }
            List<String> segments = uri == null ? java.util.Collections.emptyList()
                    : uri.getPathSegments();
            if (segments.size() != 4 || !"pack".equals(segments.get(0))
                    || !"v1".equals(segments.get(1)) || !pack.id.equals(segments.get(2))) {
                throw new SecurityException("Wrapper requested a runtime outside its binding");
            }
            return RuntimePackStore.requireUri(providerContext(), uri).file;
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Runtime module is unavailable to this caller");
            failure.initCause(error);
            throw failure;
        }
    }

    private SelectedProgram selectWrapperProgram(String caller, RuntimePackStore.Pack pack)
            throws Exception {
        ApplicationInfo app = providerContext().getPackageManager().getApplicationInfo(
                caller, PackageManager.GET_META_DATA);
        String requested = app.metaData == null ? null
                : app.metaData.getString("org.archphene.source.executable");
        if (requested == null || !requested.matches("[a-zA-Z0-9@._+:-]{1,128}")) {
            throw new SecurityException("Wrapper has no valid runtime command");
        }
        if (requested.equals(pack.executableName)) {
            return new SelectedProgram(requested, pack.requireKind("program"));
        }
        for (RuntimePackStore.Module command : pack.commands()) {
            if (requested.equals(command.linkName)) {
                return new SelectedProgram(requested, command);
            }
        }
        throw new SecurityException("Wrapper command is not in its verified runtime pack");
    }

    private static final class SelectedProgram {
        final String name;
        final RuntimePackStore.Module module;

        SelectedProgram(String name, RuntimePackStore.Module module) {
            this.name = name;
            this.module = module;
        }
    }
    private String requireWrapperCaller() throws Exception {
        Context context = providerContext();
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return context.getPackageName();
        String caller = getCallingPackage();
        if (caller == null) throw new SecurityException("Runtime caller is unknown");
        boolean ownedByUid = false;
        String[] uidPackages = context.getPackageManager().getPackagesForUid(uid);
        if (uidPackages != null) {
            for (String value : uidPackages) {
                if (caller.equals(value)) {
                    ownedByUid = true;
                    break;
                }
            }
        }
        if (!ownedByUid) throw new SecurityException("Runtime caller UID mismatch");

        PackageInfo info;
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            info = context.getPackageManager().getPackageInfo(
                    caller, PackageManager.GET_SIGNING_CERTIFICATES);
            signatures = info.signingInfo == null ? null
                    : info.signingInfo.getApkContentsSigners();
        } else {
            info = context.getPackageManager().getPackageInfo(
                    caller, PackageManager.GET_SIGNATURES);
            signatures = info.signatures;
        }
        String expected = ArchWrapperSigner.signerSha256();
        if (signatures != null) {
            for (Signature signature : signatures) {
                if (expected.equals(sha256(signature.toByteArray()))) return caller;
            }
        }
        throw new SecurityException("Runtime caller is not signed by this Archphene manager");
    }
    private Context providerContext() throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Runtime provider is unavailable");
        return context;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(64);
        for (byte part : digest) output.append(String.format("%02x", part & 0xff));
        return output.toString();
    }
    private static String sha256(File file) throws FileNotFoundException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) value.append(String.format("%02x", part & 0xff));
            return value.toString();
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Could not verify runtime module");
            failure.initCause(error);
            throw failure;
        }
    }
}
