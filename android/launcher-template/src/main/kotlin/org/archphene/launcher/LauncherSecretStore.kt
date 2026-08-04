package org.archphene.launcher

import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * A bounded, per-launcher secret store.
 *
 * Each generated launcher has a distinct Android UID and Keystore namespace. Secret bytes cross
 * Binder only through a regular file descriptor and are encrypted before reaching app-private
 * storage.
 */
internal class LauncherSecretStore(filesDirectory: File) {
    internal data class ReadResult(
        val label: String,
        val attributes: String,
        val contentType: String,
        val secretBytes: Int,
    )

    internal class BoundedIndexBuffer(maximumBytes: Int) {
        internal val bytes = ByteArray(maximumBytes)
        internal var size: Int = 0
            private set

        internal fun append(value: Int) {
            requireCapacity(1)
            bytes[size++] = value.toByte()
        }

        internal fun append(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            append(encoded)
        }

        internal fun append(value: ByteArray) {
            requireCapacity(value.size)
            value.copyInto(bytes, size)
            size += value.size
        }

        internal fun appendUnsignedShort(value: Int) {
            if (value !in 0..0xffff) throw IOException("Secret index value is out of range")
            requireCapacity(2)
            bytes[size++] = (value ushr 8).toByte()
            bytes[size++] = value.toByte()
        }

        internal fun appendInt(value: Int) {
            requireCapacity(4)
            bytes[size++] = (value ushr 24).toByte()
            bytes[size++] = (value ushr 16).toByte()
            bytes[size++] = (value ushr 8).toByte()
            bytes[size++] = value.toByte()
        }

        internal fun requireCapacity(additionalBytes: Int) {
            if (additionalBytes < 0 || additionalBytes > bytes.size - size) {
                throw IOException("Secret index is too large")
            }
        }
    }

    private data class Record(
        val id: String,
        val label: String,
        val attributes: String,
        val contentType: String,
        val secret: ByteArray,
    )

    private val directory = File(filesDirectory, STORE_DIRECTORY)
    private val random = SecureRandom()

    @Synchronized
    fun store(
        id: String,
        label: String,
        attributes: String,
        contentType: String,
        secretDescriptor: FileDescriptor,
    ) {
        validateText(id, "secret ID", MAX_ID, allowEmpty = false)
        validateText(label, "secret label", MAX_LABEL, allowEmpty = true)
        val canonicalAttributes = canonicalAttributes(attributes)
        validateText(contentType, "secret content type", MAX_CONTENT_TYPE, allowEmpty = false)
        val secret = readSecret(secretDescriptor)
        var plaintext: ByteArray? = null
        var temporary: File? = null
        try {
            ensureDirectory()
            val target = recordFile(id)
            if (!target.isFile && recordFiles().size >= MAX_ITEMS) {
                throw IllegalStateException("Secret store item limit reached")
            }
            plaintext =
                encodeRecord(Record(id, label, canonicalAttributes, contentType, secret))
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) {
                throw IOException("Android Keystore returned an invalid GCM nonce")
            }
            cipher.updateAAD(target.name.toByteArray(StandardCharsets.US_ASCII))
            val encrypted = cipher.doFinal(plaintext)
            temporary =
                File(
                    directory,
                    "${target.name}.tmp-${java.lang.Long.toUnsignedString(random.nextLong(), 16)}",
                )
            FileOutputStream(temporary, false).use { fileOutput ->
                DataOutputStream(fileOutput).use { output ->
                    output.writeInt(MAGIC)
                    output.writeByte(VERSION)
                    output.writeByte(iv.size)
                    output.writeInt(encrypted.size)
                    output.write(iv)
                    output.write(encrypted)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            Os.chmod(temporary.absolutePath, 0x180)
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            temporary?.takeIf(File::exists)?.delete()
            Arrays.fill(secret, 0)
            plaintext?.let { Arrays.fill(it, 0) }
        }
    }

    @Synchronized
    fun read(
        id: String,
        outputDescriptor: FileDescriptor,
    ): ReadResult? {
        validateText(id, "secret ID", MAX_ID, allowEmpty = false)
        val file = recordFile(id)
        if (!file.isFile) return null
        val record = decrypt(file)
        try {
            if (id != record.id) throw SecurityException("Secret record identity mismatch")
            writeOutput(outputDescriptor, record.secret)
            return ReadResult(
                record.label,
                record.attributes,
                record.contentType,
                record.secret.size,
            )
        } finally {
            Arrays.fill(record.secret, 0)
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        validateText(id, "secret ID", MAX_ID, allowEmpty = false)
        val file = recordFile(id)
        return !file.exists() || file.delete()
    }

    @Synchronized
    fun list(outputDescriptor: FileDescriptor): Int {
        ensureDirectory()
        val result = BoundedIndexBuffer(MAX_INDEX_BYTES)
        result.append('['.code)
        var count = 0
        for (file in recordFiles()) {
            val record = decrypt(file)
            try {
                count =
                    appendIndexRecord(
                        result,
                        count,
                        record.id,
                        record.label,
                        record.attributes,
                        record.contentType,
                    )
            } finally {
                Arrays.fill(record.secret, 0)
            }
        }
        result.append(']'.code)
        writeOutput(outputDescriptor, result.bytes, result.size)
        return count
    }

    @Synchronized
    fun catalog(outputDescriptor: FileDescriptor): Int {
        ensureDirectory()
        val files = recordFiles()
        val output = BoundedIndexBuffer(MAX_INDEX_BYTES)
        output.appendInt(CATALOG_MAGIC)
        output.append(CATALOG_VERSION)
        output.appendUnsignedShort(files.size)
        for (file in files) {
            val record = decrypt(file)
            try {
                writeCatalogString(output, record.id)
                writeCatalogString(output, record.label)
                writeCatalogString(output, record.contentType)
                val attributes = JSONObject(record.attributes)
                val keys = ArrayList<String>(attributes.length())
                val iterator = attributes.keys()
                while (iterator.hasNext()) keys.add(iterator.next())
                keys.sort()
                output.append(keys.size)
                for (key in keys) {
                    writeCatalogString(output, key)
                    writeCatalogString(output, attributes.getString(key))
                }
                output.appendInt(record.secret.size)
            } finally {
                Arrays.fill(record.secret, 0)
            }
        }
        writeOutput(outputDescriptor, output.bytes, output.size)
        return files.size
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER)
        store.load(null)
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val parameters =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 28) parameters.setUnlockedDeviceRequired(true)
        generator.init(parameters.build())
        return generator.generateKey()
    }

    private fun decrypt(file: File): Record {
        if (
            Files.isSymbolicLink(file.toPath()) ||
            file.canonicalFile.parentFile != directory.canonicalFile
        ) {
            throw SecurityException("Secret record escaped private storage")
        }
        DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Secret record header is invalid")
            val version = input.readUnsignedByte()
            if (version != 1 && version != VERSION) {
                throw IOException("Secret record version is unsupported")
            }
            val ivLength = input.readUnsignedByte()
            val encryptedLength = input.readInt()
            if (
                ivLength != IV_BYTES ||
                encryptedLength < GCM_TAG_BYTES ||
                encryptedLength > MAX_ENCRYPTED_BYTES ||
                file.length() != HEADER_BYTES + ivLength + encryptedLength
            ) {
                throw IOException("Secret record length is invalid")
            }
            val iv = ByteArray(ivLength)
            val encrypted = ByteArray(encryptedLength)
            input.readFully(iv)
            input.readFully(encrypted)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(file.name.toByteArray(StandardCharsets.US_ASCII))
            val plaintext = cipher.doFinal(encrypted)
            try {
                return decodeRecord(plaintext, version)
            } finally {
                Arrays.fill(plaintext, 0)
            }
        }
    }

    private fun recordFile(id: String): File {
        ensureDirectory()
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray(StandardCharsets.UTF_8))
        val name = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            name[index * 2] = HEX[value ushr 4]
            name[index * 2 + 1] = HEX[value and 0x0f]
        }
        val root = directory.canonicalFile
        val result = File(root, "${String(name)}.secret").canonicalFile
        if (result.parentFile != root) {
            throw SecurityException("Secret record escaped private storage")
        }
        return result
    }

    private fun recordFiles(): List<File> =
        collectBoundedRegularFiles(
            directory,
            RECORD_NAME,
            STORE_ENTRY_NAME,
            MAX_ITEMS,
            MAX_DIRECTORY_ENTRIES,
            "Unsafe secret record",
            "Secret store item limit exceeded",
        ).also { files -> files.sortBy(File::getName) }

    private fun ensureDirectory() {
        if (
            directory.exists() &&
            (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()))
        ) {
            throw IOException("Private secret store is unsafe")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create private secret store")
        }
        Os.chmod(directory.absolutePath, 0x1c0)
        visitBoundedRegularFiles(
            directory,
            TEMPORARY_NAME,
            STORE_ENTRY_NAME,
            MAX_STALE_TEMPORARY_FILES,
            MAX_DIRECTORY_ENTRIES,
            "Unsafe stale secret record",
            "Stale secret record limit exceeded",
        ) { file ->
            if (!file.delete()) {
                throw IOException("Could not remove stale secret record")
            }
        }
    }

    internal companion object {
        private const val STORE_DIRECTORY = "secret-store"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "archphene-secret-store-v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val MAGIC = 0x41505331
        private const val VERSION = 2
        private const val CATALOG_MAGIC = 0x41504331
        private const val CATALOG_VERSION = 2
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val HEADER_BYTES = 10L
        private const val MAX_SECRET_BYTES = 64 * 1024
        private const val MAX_ATTRIBUTES_BYTES = 8 * 1024
        private const val MAX_ENCRYPTED_BYTES =
            MAX_SECRET_BYTES + MAX_ATTRIBUTES_BYTES + 4_096
        private const val MAX_INDEX_BYTES = 1024 * 1024
        private const val MAX_ITEMS = 256
        private const val MAX_STALE_TEMPORARY_FILES = 256
        private const val MAX_DIRECTORY_ENTRIES = 513
        private const val MAX_ID = 128
        private const val MAX_LABEL = 256
        private const val MAX_CONTENT_TYPE = 128
        private const val MAX_ATTRIBUTE_KEY = 128
        private const val MAX_ATTRIBUTE_VALUE = 512
        private const val MAX_ATTRIBUTES = 32
        private const val HEX = "0123456789abcdef"
        private val RECORD_NAME = Regex("[0-9a-f]{64}\\.secret")
        private val TEMPORARY_NAME =
            Regex("[0-9a-f]{64}\\.secret\\.tmp-[0-9a-f]{1,16}")
        private val STORE_ENTRY_NAME =
            Regex("(?:${RECORD_NAME.pattern})|(?:${TEMPORARY_NAME.pattern})")

        internal fun appendIndexRecord(
            output: BoundedIndexBuffer,
            count: Int,
            id: String,
            label: String,
            attributes: String,
            contentType: String,
        ): Int {
            if (count != 0) output.append(','.code)
            output.append(
                "{\"id\":${jsonString(id)},\"label\":${jsonString(label)}," +
                    "\"attributes\":$attributes,\"contentType\":${jsonString(contentType)}}",
            )
            return Math.addExact(count, 1)
        }

        private fun jsonString(value: String): String =
            buildString(value.length + 2) {
                append('"')
                for (character in value) {
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (character.code < 0x20) {
                                append("\\u")
                                append(character.code.toString(16).padStart(4, '0'))
                            } else {
                                append(character)
                            }
                        }
                    }
                }
                append('"')
            }

        private fun encodeRecord(record: Record): ByteArray {
            val id = record.id.toByteArray(StandardCharsets.UTF_8)
            val label = record.label.toByteArray(StandardCharsets.UTF_8)
            val attributes = record.attributes.toByteArray(StandardCharsets.UTF_8)
            val contentType = record.contentType.toByteArray(StandardCharsets.UTF_8)
            val length =
                try {
                    Math.addExact(
                        20,
                        Math.addExact(
                            record.secret.size,
                            Math.addExact(
                                contentType.size,
                                Math.addExact(
                                    id.size,
                                    Math.addExact(label.size, attributes.size),
                                ),
                            ),
                        ),
                    )
                } catch (error: ArithmeticException) {
                    throw IOException("Secret record size overflow", error)
                }
            return ByteBuffer.allocate(length)
                .putInt(id.size).put(id)
                .putInt(label.size).put(label)
                .putInt(attributes.size).put(attributes)
                .putInt(contentType.size).put(contentType)
                .putInt(record.secret.size).put(record.secret)
                .array()
        }

        private fun decodeRecord(
            encoded: ByteArray,
            version: Int,
        ): Record {
            var secret: ByteArray? = null
            try {
                DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                    val id = readString(input, MAX_ID * 4)
                    val label = readString(input, MAX_LABEL * 4)
                    val attributes = canonicalAttributes(readString(input, MAX_ATTRIBUTES_BYTES))
                    val contentType =
                        if (version >= 2) {
                            readString(input, MAX_CONTENT_TYPE * 4)
                        } else {
                            "text/plain"
                        }
                    val secretLength = input.readInt()
                    if (
                        secretLength < 0 ||
                        secretLength > MAX_SECRET_BYTES ||
                        secretLength != input.available()
                    ) {
                        throw IOException("Secret payload length is invalid")
                    }
                    secret = ByteArray(secretLength)
                    input.readFully(secret)
                    validateText(id, "secret ID", MAX_ID, allowEmpty = false)
                    validateText(label, "secret label", MAX_LABEL, allowEmpty = true)
                    validateText(
                        contentType,
                        "secret content type",
                        MAX_CONTENT_TYPE,
                        allowEmpty = false,
                    )
                    return Record(id, label, attributes, contentType, secret)
                }
            } catch (error: Exception) {
                secret?.let { Arrays.fill(it, 0) }
                throw error
            }
        }

        private fun readSecret(descriptor: FileDescriptor): ByteArray {
            val stat = Os.fstat(descriptor)
            if (
                stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG ||
                stat.st_size < 0 ||
                stat.st_size > MAX_SECRET_BYTES
            ) {
                throw IllegalArgumentException("Secret input must be a bounded regular file")
            }
            val value = ByteArray(stat.st_size.toInt())
            val duplicate = ParcelFileDescriptor.dup(descriptor)
            try {
                Os.lseek(duplicate.fileDescriptor, 0, OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                    var offset = 0
                    while (offset < value.size) {
                        val count = input.read(value, offset, value.size - offset)
                        if (count < 0) throw IOException("Secret input ended early")
                        offset += count
                    }
                    if (input.read() != -1) {
                        throw IOException("Secret input changed while reading")
                    }
                }
                return value
            } catch (error: Exception) {
                runCatching { duplicate.close() }
                Arrays.fill(value, 0)
                throw error
            }
        }

        private fun writeOutput(
            descriptor: FileDescriptor,
            value: ByteArray,
            length: Int = value.size,
        ) {
            if (length !in 0..value.size) throw IllegalArgumentException("Invalid output length")
            val stat = Os.fstat(descriptor)
            if (stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG) {
                throw IllegalArgumentException("Secret output must be a regular file")
            }
            val duplicate = ParcelFileDescriptor.dup(descriptor)
            try {
                Os.ftruncate(duplicate.fileDescriptor, 0)
                Os.lseek(duplicate.fileDescriptor, 0, OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseOutputStream(duplicate).use { output ->
                    output.write(value, 0, length)
                    output.flush()
                }
            } catch (error: Exception) {
                runCatching { duplicate.close() }
                throw error
            }
        }

        private fun canonicalAttributes(encoded: String): String {
            val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size !in 2..MAX_ATTRIBUTES_BYTES) {
                throw IllegalArgumentException("Secret attributes size is invalid")
            }
            val source = JSONObject(encoded)
            val sorted = TreeMap<String, String>()
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = source.get(key)
                if (value !is String) {
                    throw IllegalArgumentException("Secret attributes must be strings")
                }
                validateText(key, "attribute key", MAX_ATTRIBUTE_KEY, allowEmpty = false)
                validateText(
                    value,
                    "attribute value",
                    MAX_ATTRIBUTE_VALUE,
                    allowEmpty = true,
                )
                sorted[key] = value
                if (sorted.size > MAX_ATTRIBUTES) {
                    throw IllegalArgumentException("Too many attributes")
                }
            }
            val result = JSONObject()
            for ((key, value) in sorted) result.put(key, value)
            return result.toString()
        }

        internal fun writeCatalogString(
            output: BoundedIndexBuffer,
            value: String,
        ) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            if (encoded.size > 0xffff) throw IOException("Secret catalog string is too large")
            output.requireCapacity(2 + encoded.size)
            output.appendUnsignedShort(encoded.size)
            output.append(encoded)
        }

        private fun readString(
            input: DataInputStream,
            maximum: Int,
        ): String {
            val length = input.readInt()
            if (length < 0 || length > maximum || length > input.available()) {
                throw IOException("Secret record string length is invalid")
            }
            val encoded = ByteArray(length)
            input.readFully(encoded)
            val value = String(encoded, StandardCharsets.UTF_8)
            if (!encoded.contentEquals(value.toByteArray(StandardCharsets.UTF_8))) {
                throw IOException("Secret record string is not UTF-8")
            }
            return value
        }

        private fun validateText(
            value: String,
            label: String,
            maximum: Int,
            allowEmpty: Boolean,
        ) {
            if ((!allowEmpty && value.isEmpty()) || value.length > maximum) {
                throw IllegalArgumentException("$label is invalid")
            }
            if (value.any(Char::isISOControl)) {
                throw IllegalArgumentException("$label contains control characters")
            }
        }
    }
}
