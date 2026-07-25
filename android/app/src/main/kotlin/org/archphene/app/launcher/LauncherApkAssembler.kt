package org.archphene.app.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class LauncherApkRequest(
    val androidPackage: String,
    val descriptorIdHex: String,
    val generation: Long,
    val label: String,
)

internal data class GeneratedLauncherApk(
    val androidPackage: String,
    val generation: Long,
    val apk: File,
    val apkSha256: ByteArray,
)

internal object LauncherApkAssembler {
    private const val TEMPLATE_ASSET = "launcher/launcher-template.apk"
    private const val TEMPLATE_PACKAGE =
        "org.archphene.linux.p00000000000000000000000000000000"
    private const val TEMPLATE_DESCRIPTOR =
        "d:0000000000000000000000000000000000000000000000000000000000000000"
    private const val TEMPLATE_GENERATION = "g:00000000000000000001"
    private const val TEMPLATE_MANAGER = "org.archphene.app.template"
    private const val TEMPLATE_LABEL = "Archphene Linux App"
    private const val MANIFEST = "AndroidManifest.xml"
    private const val ZIP_EPOCH_MILLIS = 1_577_836_800_000L
    private const val ENTRY_LIMIT = 4 * 1024 * 1024
    private const val ARCHIVE_LIMIT = 8 * 1024 * 1024
    private const val ENTRY_COUNT_LIMIT = 64
    private val PACKAGE =
        Regex("org\\.archphene\\.linux\\.p[0-9a-f]{32}")
    private val DESCRIPTOR = Regex("[0-9a-f]{64}")
    private val MANAGER_PACKAGE =
        Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,7}")

    @Synchronized
    fun assembleAndSign(
        context: Context,
        request: LauncherApkRequest,
    ): GeneratedLauncherApk {
        validateRequest(context, request)
        val directory = File(context.filesDir, "launcher-apks")
        check(directory.mkdirs() || directory.isDirectory) {
            "Could not create launcher output directory"
        }
        check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile) {
            "Unsafe launcher output directory"
        }
        val unsigned = File(directory, "${request.androidPackage}.unsigned.apk")
        val output = File(directory, "${request.androidPackage}.apk")
        val expectedEntries = buildUnsigned(context, request, unsigned)
        try {
            val signed = LauncherApkSigner.sign(context, unsigned, output)
            verifyArchiveEntries(signed.apk, expectedEntries)
            verifyPackage(context, request, signed)
            return GeneratedLauncherApk(
                request.androidPackage,
                request.generation,
                signed.apk,
                sha256(signed.apk),
            )
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            unsigned.delete()
        }
    }

    private fun validateRequest(
        context: Context,
        request: LauncherApkRequest,
    ) {
        require(PACKAGE.matches(request.androidPackage)) {
            "Invalid launcher package"
        }
        require(DESCRIPTOR.matches(request.descriptorIdHex)) {
            "Invalid launcher descriptor"
        }
        require(request.generation in 1..Int.MAX_VALUE.toLong()) {
            "Launcher generation exceeds Android's version range"
        }
        require(validLabel(request.label)) {
            "Invalid launcher label"
        }
        require(MANAGER_PACKAGE.matches(context.packageName)) {
            "Invalid manager package"
        }
    }

    private fun validLabel(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 128 &&
            value.toByteArray(StandardCharsets.UTF_8).size <= 512 &&
            value.none { character ->
                character.isISOControl() ||
                    character == '\u061c' ||
                    character == '\u200e' ||
                    character == '\u200f' ||
                    character in '\u202a'..'\u202e' ||
                    character in '\u2066'..'\u2069'
            }

    private fun buildUnsigned(
        context: Context,
        request: LauncherApkRequest,
        output: File,
    ): Map<String, ByteArray> {
        check(!output.exists() || output.delete()) {
            "Could not reset unsigned launcher"
        }
        val expected = TreeMap<String, ByteArray>()
        val fileOutput = FileOutputStream(output)
        val counted = CountingOutputStream(fileOutput)
        var total = 0
        var entries = 0
        var manifestFound = false
        try {
            ZipInputStream(context.assets.open(TEMPLATE_ASSET)).use { input ->
                ZipOutputStream(counted).use { zip ->
                    while (true) {
                        val source = input.nextEntry ?: break
                        val name = source.name
                        if (name.startsWith("META-INF/")) {
                            continue
                        }
                        check(safeEntryName(name)) {
                            "Unsafe launcher-template entry"
                        }
                        check(++entries <= ENTRY_COUNT_LIMIT && !expected.containsKey(name)) {
                            "Launcher template exceeds its entry limit"
                        }
                        var value = readBounded(input)
                        total = Math.addExact(total, value.size)
                        check(total <= ARCHIVE_LIMIT) {
                            "Launcher template exceeds its size limit"
                        }
                        if (name == MANIFEST) {
                            value =
                                BinaryAndroidManifest(value)
                                    .replaceString(TEMPLATE_PACKAGE, request.androidPackage)
                                    .replaceString(
                                        TEMPLATE_DESCRIPTOR,
                                        "d:${request.descriptorIdHex}",
                                    )
                                    .replaceString(
                                        TEMPLATE_GENERATION,
                                        "g:${request.generation.toString().padStart(20, '0')}",
                                    ).replaceString(TEMPLATE_MANAGER, context.packageName)
                                    .replaceString(TEMPLATE_LABEL, request.label)
                                    .setVersionCode(request.generation.toInt())
                                    .bytes
                            manifestFound = true
                        }
                        expected[name] = sha256(value)
                        val target = ZipEntry(name)
                        target.time = ZIP_EPOCH_MILLIS
                        if (source.method == ZipEntry.STORED || name == "resources.arsc") {
                            val crc = CRC32().apply { update(value) }
                            target.method = ZipEntry.STORED
                            target.size = value.size.toLong()
                            target.compressedSize = value.size.toLong()
                            target.crc = crc.value
                            alignStoredEntry(target, counted.count, name, 4)
                        }
                        zip.putNextEntry(target)
                        zip.write(value)
                        zip.closeEntry()
                    }
                }
            }
            FileOutputStream(output, true).use { synced ->
                synced.fd.sync()
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            fileOutput.close()
        }
        check(manifestFound && output.length() in 1..ARCHIVE_LIMIT.toLong()) {
            "Launcher template manifest is missing"
        }
        return expected
    }

    private fun verifyArchiveEntries(
        apk: File,
        expected: Map<String, ByteArray>,
    ) {
        check(apk.isFile && apk.length() in 1..ARCHIVE_LIMIT.toLong()) {
            "Signed launcher exceeds its size limit"
        }
        val found = TreeMap<String, ByteArray>()
        FileInputStream(apk).use { raw ->
            ZipInputStream(raw).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith("META-INF/")) {
                        continue
                    }
                    check(
                        safeEntryName(name) &&
                            found.size < ENTRY_COUNT_LIMIT &&
                            !found.containsKey(name),
                    ) {
                        "Signed launcher contains an unsafe entry"
                    }
                    found[name] = sha256(readBounded(input))
                }
            }
        }
        check(found.keys == expected.keys) {
            "Signed launcher entry set changed"
        }
        for ((name, digest) in expected) {
            check(MessageDigest.isEqual(found.getValue(name), digest)) {
                "Signed launcher content changed"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(
        context: Context,
        request: LauncherApkRequest,
        signed: SignedLauncherApk,
    ) {
        val flags =
            PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNING_CERTIFICATES
        val parsed =
            context.packageManager.getPackageArchiveInfo(signed.apk.path, flags)
                ?: error("Android rejected the generated launcher")
        check(
            parsed.packageName == request.androidPackage &&
                parsed.longVersionCode == request.generation &&
                parsed.applicationInfo != null,
        ) {
            "Generated launcher package identity changed"
        }
        val application = checkNotNull(parsed.applicationInfo)
        application.sourceDir = signed.apk.path
        application.publicSourceDir = signed.apk.path
        check(
            application.loadLabel(context.packageManager).toString() == request.label,
        ) {
            "Generated launcher label changed"
        }
        val metadata = application.metaData ?: error("Generated launcher metadata is missing")
        check(
            metadata.getString("org.archphene.launcher.DESCRIPTOR_ID") ==
                "d:${request.descriptorIdHex}" &&
                metadata.getString("org.archphene.launcher.GENERATION") ==
                "g:${request.generation.toString().padStart(20, '0')}" &&
                metadata.getString("org.archphene.launcher.MANAGER_PACKAGE") ==
                context.packageName,
        ) {
            "Generated launcher metadata changed"
        }
        val certificates =
            parsed.signingInfo?.apkContentsSigners
                ?: error("Generated launcher signer is missing")
        check(certificates.size == 1) {
            "Generated launcher has an unsupported signer set"
        }
        check(
            MessageDigest.isEqual(
                sha256(certificates.single().toByteArray()),
                signed.signerSha256,
            ),
        ) {
            "Generated launcher signer changed"
        }
    }

    private fun safeEntryName(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 240 &&
            !value.startsWith('/') &&
            !value.endsWith('/') &&
            !value.contains('\\') &&
            value.split('/').all { part ->
                part.isNotEmpty() && part != "." && part != ".."
            }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            check(output.size() + read <= ENTRY_LIMIT) {
                "Launcher entry exceeds its size limit"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun alignStoredEntry(
        entry: ZipEntry,
        offset: Long,
        name: String,
        alignment: Int,
    ) {
        val nameLength = name.toByteArray(StandardCharsets.UTF_8).size
        val payload =
            ((alignment - ((offset + 30 + nameLength + 4) % alignment)) % alignment)
                .toInt()
        entry.extra =
            ByteArray(4 + payload).also {
                it[0] = 0x35
                it[1] = 0xd9.toByte()
                it[2] = payload.toByte()
                it[3] = (payload ushr 8).toByte()
            }
    }

    private fun sha256(file: File): ByteArray =
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            digest.digest()
        }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private class CountingOutputStream(
        output: OutputStream,
    ) : FilterOutputStream(output) {
        var count: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(
            value: ByteArray,
            offset: Int,
            length: Int,
        ) {
            out.write(value, offset, length)
            count += length
        }
    }
}

private class BinaryAndroidManifest(
    val bytes: ByteArray,
) {
    fun replaceString(
        match: String,
        replacement: String,
    ): BinaryAndroidManifest {
        check(u16(bytes, 0) == XML_TYPE && i32(bytes, 4) == bytes.size) {
            "Launcher manifest is not valid binary XML"
        }
        var offset = u16(bytes, 2)
        while (offset + 8 <= bytes.size) {
            val type = u16(bytes, offset)
            val size = i32(bytes, offset + 4)
            check(size >= 8 && offset + size <= bytes.size) {
                "Launcher manifest contains an invalid chunk"
            }
            if (type == STRING_POOL_TYPE) {
                return BinaryAndroidManifest(
                    replaceStringPoolEntry(bytes, offset, match, replacement),
                )
            }
            offset += size
        }
        error("Launcher manifest string pool is missing")
    }

    fun setVersionCode(versionCode: Int): BinaryAndroidManifest {
        val strings = stringPool(bytes)
        val manifestIndex = strings.values.indexOf("manifest")
        val versionCodeIndex = strings.values.indexOf("versionCode")
        check(manifestIndex >= 0 && versionCodeIndex >= 0) {
            "Launcher manifest version marker is missing"
        }
        var offset = u16(bytes, 2)
        var changed = false
        while (offset + 16 <= bytes.size) {
            val type = u16(bytes, offset)
            val size = i32(bytes, offset + 4)
            check(size >= 8 && offset + size <= bytes.size) {
                "Launcher manifest contains an invalid XML chunk"
            }
            if (type == START_ELEMENT_TYPE && i32(bytes, offset + 20) == manifestIndex) {
                val attributeStart = u16(bytes, offset + 24)
                val attributeSize = u16(bytes, offset + 26)
                val attributeCount = u16(bytes, offset + 28)
                check(attributeSize >= 20 && attributeCount <= 256) {
                    "Launcher manifest attributes are invalid"
                }
                val start = offset + 16 + attributeStart
                for (index in 0 until attributeCount) {
                    val attribute = start + index * attributeSize
                    check(attribute + 20 <= offset + size) {
                        "Launcher manifest attribute exceeds its chunk"
                    }
                    if (i32(bytes, attribute + 4) == versionCodeIndex) {
                        check(!changed && bytes[attribute + 15].toInt() and 0xff == TYPE_INT_DEC) {
                            "Launcher manifest version marker is ambiguous"
                        }
                        putI32(bytes, attribute + 16, versionCode)
                        changed = true
                    }
                }
            }
            offset += size
        }
        check(changed) {
            "Launcher manifest version marker is missing"
        }
        return this
    }

    private data class Pool(
        val values: List<String>,
    )

    private data class DecodedString(
        val value: String,
        val end: Int,
    )

    private fun stringPool(xml: ByteArray): Pool {
        var offset = u16(xml, 2)
        while (offset + 8 <= xml.size) {
            val type = u16(xml, offset)
            val size = i32(xml, offset + 4)
            check(size >= 8 && offset + size <= xml.size)
            if (type == STRING_POOL_TYPE) {
                val headerSize = u16(xml, offset + 2)
                val stringCount = i32(xml, offset + 8)
                val flags = i32(xml, offset + 16)
                val stringsStart = i32(xml, offset + 20)
                check(stringCount in 1..100_000)
                val utf8 = flags and UTF8_FLAG != 0
                val values = ArrayList<String>(stringCount)
                for (index in 0 until stringCount) {
                    val relative = i32(xml, offset + headerSize + index * 4)
                    values.add(
                        decodePoolString(
                            xml,
                            offset + stringsStart + relative,
                            utf8,
                            offset + size,
                        ).value,
                    )
                }
                return Pool(values)
            }
            offset += size
        }
        error("Launcher manifest string pool is missing")
    }

    private fun replaceStringPoolEntry(
        xml: ByteArray,
        poolOffset: Int,
        match: String,
        replacement: String,
    ): ByteArray {
        val headerSize = u16(xml, poolOffset + 2)
        val poolSize = i32(xml, poolOffset + 4)
        val stringCount = i32(xml, poolOffset + 8)
        val flags = i32(xml, poolOffset + 16)
        val stringsStart = i32(xml, poolOffset + 20)
        val stylesStart = i32(xml, poolOffset + 24)
        check(
            headerSize >= 28 &&
                stringCount in 1..100_000 &&
                stringsStart >= headerSize + stringCount * 4 &&
                poolOffset + poolSize <= xml.size,
        ) {
            "Launcher manifest has an invalid string pool"
        }
        val utf8 = flags and UTF8_FLAG != 0
        var foundIndex = -1
        var oldStart = -1
        var oldEnd = -1
        for (index in 0 until stringCount) {
            val relative = i32(xml, poolOffset + headerSize + index * 4)
            val start = poolOffset + stringsStart + relative
            val decoded = decodePoolString(xml, start, utf8, poolOffset + poolSize)
            if (decoded.value == match) {
                check(foundIndex < 0) {
                    "Launcher manifest string marker is ambiguous"
                }
                foundIndex = index
                oldStart = start
                oldEnd = decoded.end
            }
        }
        check(foundIndex >= 0) {
            "Launcher manifest string marker is missing"
        }
        val encoded = encodePoolString(replacement, utf8)
        val rawDelta = encoded.size - (oldEnd - oldStart)
        val unalignedPoolSize = poolSize + rawDelta
        val padding = (4 - (unalignedPoolSize and 3)) and 3
        val totalDelta = rawDelta + padding
        val result = ByteArray(xml.size + totalDelta)
        xml.copyInto(result, 0, 0, oldStart)
        encoded.copyInto(result, oldStart)
        val afterString = oldStart + encoded.size
        val oldPoolEnd = poolOffset + poolSize
        xml.copyInto(result, afterString, oldEnd, oldPoolEnd)
        val newPoolEnd = oldPoolEnd + totalDelta
        xml.copyInto(result, newPoolEnd, oldPoolEnd)
        putI32(result, 4, xml.size + totalDelta)
        putI32(result, poolOffset + 4, poolSize + totalDelta)
        val replacedOffset = i32(xml, poolOffset + headerSize + foundIndex * 4)
        for (index in 0 until stringCount) {
            val position = poolOffset + headerSize + index * 4
            val relative = i32(xml, position)
            putI32(result, position, if (relative > replacedOffset) relative + rawDelta else relative)
        }
        if (stylesStart != 0) {
            putI32(result, poolOffset + 24, stylesStart + rawDelta)
        }
        return result
    }

    private fun decodePoolString(
        value: ByteArray,
        offset: Int,
        utf8: Boolean,
        limit: Int,
    ): DecodedString {
        if (utf8) {
            val utf16Length = readLength8(value, offset, limit)
            val byteLength = readLength8(value, utf16Length.second, limit)
            val start = byteLength.second
            val end = start + byteLength.first
            check(end < limit && value[end].toInt() == 0) {
                "Launcher manifest has a malformed UTF-8 string"
            }
            return DecodedString(
                String(value, start, byteLength.first, StandardCharsets.UTF_8),
                end + 1,
            )
        }
        val length = readLength16(value, offset, limit)
        val byteCount = Math.multiplyExact(length.first, 2)
        val end = length.second + byteCount
        check(end + 1 < limit && value[end].toInt() == 0 && value[end + 1].toInt() == 0) {
            "Launcher manifest has a malformed UTF-16 string"
        }
        return DecodedString(
            String(value, length.second, byteCount, StandardCharsets.UTF_16LE),
            end + 2,
        )
    }

    private fun encodePoolString(
        value: String,
        utf8: Boolean,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        if (utf8) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            writeLength8(output, value.length)
            writeLength8(output, encoded.size)
            output.write(encoded)
            output.write(0)
        } else {
            val encoded = value.toByteArray(StandardCharsets.UTF_16LE)
            writeLength16(output, value.length)
            output.write(encoded)
            output.write(0)
            output.write(0)
        }
        return output.toByteArray()
    }

    private fun readLength8(
        value: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<Int, Int> {
        check(offset < limit) { "Truncated launcher string length" }
        val first = value[offset].toInt() and 0xff
        if (first and 0x80 == 0) {
            return first to offset + 1
        }
        check(offset + 1 < limit) { "Truncated launcher string length" }
        return (((first and 0x7f) shl 8) or (value[offset + 1].toInt() and 0xff)) to
            offset + 2
    }

    private fun readLength16(
        value: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<Int, Int> {
        check(offset + 1 < limit) { "Truncated launcher string length" }
        val first = u16(value, offset)
        if (first and 0x8000 == 0) {
            return first to offset + 2
        }
        check(offset + 3 < limit) { "Truncated launcher string length" }
        return (((first and 0x7fff) shl 16) or u16(value, offset + 2)) to offset + 4
    }

    private fun writeLength8(
        output: ByteArrayOutputStream,
        length: Int,
    ) {
        check(length <= 0x7fff) { "Launcher string is too long" }
        if (length > 0x7f) {
            output.write((length shr 8) or 0x80)
        }
        output.write(length and 0xff)
    }

    private fun writeLength16(
        output: ByteArrayOutputStream,
        length: Int,
    ) {
        check(length >= 0) { "Launcher string is too long" }
        if (length > 0x7fff) {
            output.write((length shr 16) and 0xff)
            output.write(((length shr 24) and 0x7f) or 0x80)
        }
        output.write(length and 0xff)
        output.write((length shr 8) and 0xff)
    }

    private fun u16(
        value: ByteArray,
        offset: Int,
    ): Int {
        check(offset >= 0 && offset + 2 <= value.size) {
            "Launcher manifest read exceeds bounds"
        }
        return (value[offset].toInt() and 0xff) or
            ((value[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun i32(
        value: ByteArray,
        offset: Int,
    ): Int {
        check(offset >= 0 && offset + 4 <= value.size) {
            "Launcher manifest read exceeds bounds"
        }
        return (value[offset].toInt() and 0xff) or
            ((value[offset + 1].toInt() and 0xff) shl 8) or
            ((value[offset + 2].toInt() and 0xff) shl 16) or
            (value[offset + 3].toInt() shl 24)
    }

    private fun putI32(
        value: ByteArray,
        offset: Int,
        current: Int,
    ) {
        check(offset >= 0 && offset + 4 <= value.size) {
            "Launcher manifest write exceeds bounds"
        }
        value[offset] = current.toByte()
        value[offset + 1] = (current shr 8).toByte()
        value[offset + 2] = (current shr 16).toByte()
        value[offset + 3] = (current shr 24).toByte()
    }

    private companion object {
        private const val XML_TYPE = 0x0003
        private const val STRING_POOL_TYPE = 0x0001
        private const val START_ELEMENT_TYPE = 0x0102
        private const val UTF8_FLAG = 0x100
        private const val TYPE_INT_DEC = 0x10
    }
}
