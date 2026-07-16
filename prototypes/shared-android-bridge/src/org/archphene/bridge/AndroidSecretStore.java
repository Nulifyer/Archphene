package org.archphene.bridge;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.Os;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-wrapper encrypted secret records backed by an Android Keystore key. */
final class AndroidSecretStore {
    private static final String KEY_ALIAS = "archphene-secret-store-v1";
    private static final int MAGIC = 0x41505331;
    private static final int VERSION = 2;
    private static final int IV_BYTES = 12;
    private static final int MAX_SECRET_BYTES = 64 * 1024;
    private static final int MAX_ATTRIBUTES_BYTES = 8 * 1024;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_ID = 128;
    private static final int MAX_LABEL = 256;
    private static final int MAX_CONTENT_TYPE = 128;
    private static final int MAX_ATTRIBUTE_KEY = 128;
    private static final int MAX_ATTRIBUTE_VALUE = 512;

    static final class ReadResult {
        final String label;
        final String attributes;
        final String contentType;
        final int secretBytes;

        ReadResult(String label, String attributes, String contentType, int secretBytes) {
            this.label = label;
            this.attributes = attributes;
            this.contentType = contentType;
            this.secretBytes = secretBytes;
        }
    }

    private static final class Record {
        final String id;
        final String label;
        final String attributes;
        final String contentType;
        final byte[] secret;

        Record(String id, String label, String attributes, String contentType, byte[] secret) {
            this.id = id;
            this.label = label;
            this.attributes = attributes;
            this.contentType = contentType;
            this.secret = secret;
        }
    }

    private final File directory;
    private final SecureRandom random = new SecureRandom();

    AndroidSecretStore(File filesDirectory) {
        directory = new File(filesDirectory, "secret-store");
    }

    synchronized void store(String id, String label, String attributes,
            String contentType,
            FileDescriptor secretDescriptor) throws Exception {
        validateText(id, "secret ID", MAX_ID, false);
        validateText(label, "secret label", MAX_LABEL, true);
        String canonicalAttributes = canonicalAttributes(attributes);
        validateText(contentType, "secret content type", MAX_CONTENT_TYPE, false);
        byte[] secret = readSecret(secretDescriptor);
        byte[] plaintext = null;
        File temporary = null;
        try {
            ensureDirectory();
            File target = recordFile(id);
            if (!target.isFile() && recordFiles().size() >= MAX_ITEMS) {
                throw new IllegalStateException("Secret store item limit reached");
            }
            plaintext = encodeRecord(new Record(id, label, canonicalAttributes, contentType, secret));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new IOException("Android Keystore returned an invalid GCM nonce");
            }
            cipher.updateAAD(target.getName().getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plaintext);
            temporary = new File(directory, target.getName() + ".tmp-"
                    + Long.toUnsignedString(random.nextLong(), 16));
            try (FileOutputStream fileOutput = new FileOutputStream(temporary, false);
                    DataOutputStream output = new DataOutputStream(fileOutput)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(iv.length);
                output.writeInt(encrypted.length);
                output.write(iv);
                output.write(encrypted);
                output.flush();
                fileOutput.getFD().sync();
            }
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
            java.util.Arrays.fill(secret, (byte)0);
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte)0);
        }
    }
    synchronized ReadResult read(String id, FileDescriptor outputDescriptor) throws Exception {
        validateText(id, "secret ID", MAX_ID, false);
        File file = recordFile(id);
        if (!file.isFile()) return null;
        Record record = decrypt(file);
        try {
            if (!id.equals(record.id)) {
                throw new SecurityException("Secret record identity mismatch");
            }
            writeOutput(outputDescriptor, record.secret);
            return new ReadResult(record.label, record.attributes, record.contentType, record.secret.length);
        } finally {
            java.util.Arrays.fill(record.secret, (byte)0);
        }
    }
    synchronized boolean delete(String id) throws Exception {
        validateText(id, "secret ID", MAX_ID, false);
        File file = recordFile(id);
        return !file.exists() || file.delete();
    }

    synchronized int list(FileDescriptor outputDescriptor) throws Exception {
        ensureDirectory();
        JSONArray result = new JSONArray();
        for (File file : recordFiles()) {
            Record record = decrypt(file);
            try {
                JSONObject item = new JSONObject();
                item.put("id", record.id);
                item.put("label", record.label);
                item.put("attributes", new JSONObject(record.attributes));
                item.put("contentType", record.contentType);
                result.put(item);
            } finally {
                java.util.Arrays.fill(record.secret, (byte)0);
            }
        }
        byte[] encoded = result.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 1024 * 1024) throw new IOException("Secret index is too large");
        writeOutput(outputDescriptor, encoded);
        return result.length();
    }
    synchronized int catalog(FileDescriptor outputDescriptor) throws Exception {
        ensureDirectory();
        List<File> files = recordFiles();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x41504331);
            output.writeByte(2);
            output.writeShort(files.size());
            for (File file : files) {
                Record record = decrypt(file);
                try {
                    writeCatalogString(output, record.id);
                    writeCatalogString(output, record.label);
                    writeCatalogString(output, record.contentType);
                    JSONObject attributes = new JSONObject(record.attributes);
                    ArrayList<String> keys = new ArrayList<>();
                    Iterator<String> iterator = attributes.keys();
                    while (iterator.hasNext()) keys.add(iterator.next());
                    Collections.sort(keys);
                    output.writeByte(keys.size());
                    for (String key : keys) {
                        writeCatalogString(output, key);
                        writeCatalogString(output, attributes.getString(key));
                    }
                    output.writeInt(record.secret.length);
                } finally {
                    java.util.Arrays.fill(record.secret, (byte)0);
                }
            }
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > 1024 * 1024) throw new IOException("Secret catalog is too large");
        writeOutput(outputDescriptor, encoded);
        return files.size();
    }

    private static void writeCatalogString(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) throw new IOException("Secret catalog string is too large");
        output.writeShort(encoded.length);
        output.write(encoded);
    }
    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey)existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder parameters = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (Build.VERSION.SDK_INT >= 28) parameters.setUnlockedDeviceRequired(true);
        generator.init(parameters.build());
        return generator.generateKey();
    }

    private Record decrypt(File file) throws Exception {
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
            throw new SecurityException("Secret record escaped private storage");
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Secret record header is invalid");
            }
            int version = input.readUnsignedByte();
            if (version != 1 && version != VERSION) {
                throw new IOException("Secret record version is unsupported");
            }
            int ivLength = input.readUnsignedByte();
            int encryptedLength = input.readInt();
            if (ivLength != IV_BYTES || encryptedLength < 16
                    || encryptedLength > MAX_SECRET_BYTES + MAX_ATTRIBUTES_BYTES + 4096
                    || file.length() != 10L + ivLength + encryptedLength) {
                throw new IOException("Secret record length is invalid");
            }
            byte[] iv = new byte[ivLength];
            byte[] encrypted = new byte[encryptedLength];
            input.readFully(iv);
            input.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(file.getName().getBytes(StandardCharsets.US_ASCII));
            byte[] plaintext = cipher.doFinal(encrypted);
            try {
                return decodeRecord(plaintext, version);
            } finally {
                java.util.Arrays.fill(plaintext, (byte)0);
            }
        }
    }

    private static byte[] encodeRecord(Record record) throws IOException {
        byte[] id = record.id.getBytes(StandardCharsets.UTF_8);
        byte[] label = record.label.getBytes(StandardCharsets.UTF_8);
        byte[] attributes = record.attributes.getBytes(StandardCharsets.UTF_8);
        byte[] contentType = record.contentType.getBytes(StandardCharsets.UTF_8);
        int length;
        try {
            length = Math.addExact(20, Math.addExact(record.secret.length,
                    Math.addExact(contentType.length,
                    Math.addExact(id.length, Math.addExact(label.length, attributes.length)))));
        } catch (ArithmeticException error) {
            throw new IOException("Secret record size overflow", error);
        }
        ByteBuffer output = ByteBuffer.allocate(length);
        output.putInt(id.length).put(id);
        output.putInt(label.length).put(label);
        output.putInt(attributes.length).put(attributes);
        output.putInt(contentType.length).put(contentType);
        output.putInt(record.secret.length).put(record.secret);
        return output.array();
    }
    private static Record decodeRecord(byte[] encoded, int version) throws Exception {
        byte[] secret = null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            String id = readString(input, MAX_ID * 4);
            String label = readString(input, MAX_LABEL * 4);
            String attributes = readString(input, MAX_ATTRIBUTES_BYTES);
            String contentType = version >= 2
                    ? readString(input, MAX_CONTENT_TYPE * 4) : "text/plain";
            int secretLength = input.readInt();
            if (secretLength < 0 || secretLength > MAX_SECRET_BYTES
                    || secretLength != input.available()) {
                throw new IOException("Secret payload length is invalid");
            }
            secret = new byte[secretLength];
            input.readFully(secret);
            validateText(id, "secret ID", MAX_ID, false);
            validateText(label, "secret label", MAX_LABEL, true);
            attributes = canonicalAttributes(attributes);
            validateText(contentType, "secret content type", MAX_CONTENT_TYPE, false);
            return new Record(id, label, attributes, contentType, secret);
        } catch (Exception error) {
            if (secret != null) java.util.Arrays.fill(secret, (byte)0);
            throw error;
        }
    }

    private static byte[] readSecret(FileDescriptor descriptor) throws Exception {
        android.system.StructStat stat = Os.fstat(descriptor);
        if ((stat.st_mode & android.system.OsConstants.S_IFMT)
                != android.system.OsConstants.S_IFREG
                || stat.st_size < 0 || stat.st_size > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("Secret input must be a bounded regular file");
        }
        byte[] value = new byte[(int)stat.st_size];
        ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor);
        Os.lseek(duplicate.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(duplicate)) {
            int offset = 0;
            while (offset < value.length) {
                int read = input.read(value, offset, value.length - offset);
                if (read < 0) throw new IOException("Secret input ended early");
                offset += read;
            }
            if (input.read() != -1) throw new IOException("Secret input changed while reading");
            return value;
        } catch (Exception error) {
            java.util.Arrays.fill(value, (byte)0);
            throw error;
        }
    }
    private static void writeOutput(FileDescriptor descriptor, byte[] value) throws Exception {
        android.system.StructStat stat = Os.fstat(descriptor);
        if ((stat.st_mode & android.system.OsConstants.S_IFMT)
                != android.system.OsConstants.S_IFREG) {
            throw new IllegalArgumentException("Secret output must be a regular file");
        }
        ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor);
        Os.ftruncate(duplicate.getFileDescriptor(), 0);
        Os.lseek(duplicate.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(duplicate)) {
            output.write(value);
            output.flush();
        }
    }

    private static String canonicalAttributes(String encoded) throws Exception {
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 2 || bytes.length > MAX_ATTRIBUTES_BYTES) {
            throw new IllegalArgumentException("Secret attributes size is invalid");
        }
        JSONObject source = new JSONObject(encoded);
        TreeMap<String, String> sorted = new TreeMap<>();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.get(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Secret attributes must be strings");
            }
            validateText(key, "attribute key", MAX_ATTRIBUTE_KEY, false);
            validateText((String)value, "attribute value", MAX_ATTRIBUTE_VALUE, true);
            sorted.put(key, (String)value);
            if (sorted.size() > 32) throw new IllegalArgumentException("Too many attributes");
        }
        JSONObject result = new JSONObject();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result.toString();
    }

    private File recordFile(String id) throws Exception {
        ensureDirectory();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                id.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value));
        File root = directory.getCanonicalFile();
        File result = new File(root, name + ".secret").getCanonicalFile();
        if (!root.equals(result.getParentFile())) {
            throw new SecurityException("Secret record escaped private storage");
        }
        return result;
    }

    private List<File> recordFiles() {
        File[] files = directory.listFiles((parent, name) ->
                name.matches("[0-9a-f]{64}\\.secret"));
        if (files == null) return Collections.emptyList();
        ArrayList<File> result = new ArrayList<>(java.util.Arrays.asList(files));
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private secret store");
        }
        File[] stale = directory.listFiles((parent, name) ->
                name.matches("[0-9a-f]{64}\\.secret\\.tmp-[0-9a-f]{1,16}"));
        if (stale == null) throw new IOException("Could not inspect private secret store");
        for (File file : stale) {
            if (!file.delete()) throw new IOException("Could not remove stale secret record");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) {
            throw new IOException("Secret record string length is invalid");
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        String value = new String(encoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(encoded, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Secret record string is not UTF-8");
        }
        return value;
    }

    private static void validateText(String value, String label, int maximum, boolean empty)
            throws IOException {
        if (value == null || (!empty && value.isEmpty()) || value.length() > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(label + " contains control characters");
            }
        }
    }
}
