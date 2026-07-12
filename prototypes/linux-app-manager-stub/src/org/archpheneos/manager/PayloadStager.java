package org.archpheneos.manager;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

public final class PayloadStager {
    public static final class Result {
        public final File file;
        public final long sizeBytes;
        public final String sha256;
        public final boolean executable;
        public final boolean readable;
        public final boolean writable;

        private Result(File file, long sizeBytes, String sha256) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.readable = file.canRead();
            this.writable = file.canWrite();
            this.executable = file.canExecute();
        }

        public String renderForUi() {
            return "Payload staged\n\n"
                    + "Path: " + file.getAbsolutePath() + "\n"
                    + "Size: " + sizeBytes + " bytes\n"
                    + "SHA-256: " + sha256 + "\n"
                    + "Readable: " + readable + "\n"
                    + "Writable: " + writable + "\n"
                    + "Executable: " + executable + "\n"
                    + "Launch status: staged-only\n";
        }
    }

    public static Result stage(Context context, LinuxPackageManifest manifest) throws Exception {
        File payloadRoot = new File(context.getFilesDir(), "archphene/payloads").getCanonicalFile();
        File packageRoot = new File(payloadRoot, manifest.packageName).getCanonicalFile();
        File target = new File(packageRoot, manifest.payloadInstallPath).getCanonicalFile();
        if (!isWithin(packageRoot, target)) {
            throw new SecurityException("Payload install path escapes package root");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create " + parent);
        }

        try (InputStream in = context.getAssets().open(manifest.payloadAsset);
                FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        target.setReadable(true, true);
        target.setWritable(true, true);
        target.setExecutable(true, true);

        return new Result(target, target.length(), sha256(target));
    }

    private static boolean isWithin(File root, File child) {
        String rootPath = root.getPath() + File.separator;
        return child.getPath().startsWith(rootPath);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }
}