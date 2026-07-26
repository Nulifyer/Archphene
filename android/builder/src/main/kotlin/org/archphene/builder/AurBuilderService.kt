package org.archphene.builder

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AurBuilderService : Service() {
    private val transferBuffer = ByteArray(64 * 1024)
    private val nativeOutputBuffer =
        ByteBuffer
            .allocateDirect(NativeBuilder.ERROR_OUTPUT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

    private val endpoint =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                if (reply == null) {
                    return super.onTransact(code, data, reply, flags)
                }
                if (code != TRANSACTION_PROBE) {
                    data.enforceInterface(DESCRIPTOR)
                    enforceManagerCaller(Binder.getCallingUid())
                    try {
                        when (code) {
                            TRANSACTION_BEGIN_PACKAGE_CLOSURE -> beginPackageClosure(data, reply)
                            TRANSACTION_STAGE_PACKAGE_BATCH -> stagePackageBatch(data, reply)
                            TRANSACTION_FINISH_PACKAGE_CLOSURE -> finishPackageClosure(reply)
                            TRANSACTION_ABORT_PACKAGE_CLOSURE -> abortPackageClosure(reply)
                            TRANSACTION_BEGIN_PROVISION -> beginProvision(data, reply)
                            TRANSACTION_EXTRACT_PROVISION_BATCH ->
                                extractProvisionBatch(data, reply)
                            TRANSACTION_FINISH_PROVISION -> finishProvision(reply)
                            TRANSACTION_ABORT_PROVISION -> abortProvision(reply)
                            TRANSACTION_PROBE_RUNTIME -> probeRuntime(reply)
                            else -> return super.onTransact(code, data, reply, flags)
                        }
                    } catch (error: Exception) {
                        reply.writeException(error)
                    }
                    return true
                }
                data.enforceInterface(DESCRIPTOR)
                val callerUid = Binder.getCallingUid()
                enforceManagerCaller(callerUid)
                val managerSentinel = data.readString().orEmpty()
                val output = data.readFileDescriptor()
                if (output == null) {
                    reply.writeException(IllegalArgumentException("Missing output descriptor"))
                    return true
                }
                val packageBase = data.readString().orEmpty()
                val version = data.readString().orEmpty()
                val inputCount = data.readInt()
                if (inputCount !in 1..MAX_INPUTS) {
                    output.close()
                    reply.writeException(IllegalArgumentException("Invalid build input count"))
                    return true
                }
                val inputs = ArrayList<BuildInput>(inputCount)
                try {
                    repeat(inputCount) {
                        val role = data.readInt()
                        val filename = data.readString().orEmpty()
                        val sha256 = data.readString().orEmpty()
                        val bytes = data.readLong()
                        val descriptor =
                            data.readFileDescriptor()
                                ?: throw IllegalArgumentException("Missing build input descriptor")
                        inputs += BuildInput(role, filename, sha256, bytes, descriptor)
                    }
                } catch (error: Exception) {
                    inputs.forEach { input -> input.descriptor.close() }
                    output.close()
                    reply.writeException(error)
                    return true
                }
                output.use { outputDescriptor ->
                    try {
                        val report =
                            runProbeAndStage(
                                callerUid,
                                managerSentinel,
                                outputDescriptor,
                                packageBase,
                                version,
                                inputs,
                            )
                        reply.writeNoException()
                        reply.writeInt(report.uid)
                        reply.writeInt(report.callingUid)
                        reply.writeBoolean(report.internetPermission)
                        reply.writeBoolean(report.directManagerDataReadable)
                        reply.writeBoolean(report.privateWorkspaceWritable)
                        reply.writeBoolean(report.outputWriteSucceeded)
                        reply.writeString(report.selinuxContext)
                        reply.writeLong(report.stagedBytes)
                        reply.writeString(report.inputManifestSha256)
                    } finally {
                        inputs.forEach { input ->
                            runCatching { input.descriptor.close() }
                        }
                    }
                }
                return true
            }
        }

    override fun onBind(intent: Intent?): IBinder = endpoint

    @Synchronized
    private fun beginPackageClosure(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageBase = data.readString().orEmpty()
        val version = data.readString().orEmpty()
        val manifest =
            data.createByteArray()
                ?: throw IllegalArgumentException("Missing package-closure manifest")
        val manifestSha256 = data.readString().orEmpty()
        require(manifest.isNotEmpty() && manifest.size <= MAX_CLOSURE_MANIFEST_BYTES)
        require(manifestSha256.matches(SHA256))
        val manifestBuffer =
            ByteBuffer
                .allocateDirect(manifest.size)
                .put(manifest)
        nativeOutputBuffer.clear()
        val packageCount =
            NativeBuilder.nativeBeginPackageClosure(
                filesDir.absolutePath,
                packageBase,
                version,
                manifestBuffer,
                manifest.size,
                manifestSha256,
                nativeOutputBuffer,
            )
        check(packageCount in 1..MAX_CLOSURE_PACKAGES) {
            "Builder rejected the verified package closure: " +
                readNativeMessage(nativeOutputBuffer, packageCount)
        }
        reply.writeNoException()
        reply.writeInt(packageCount)
    }

    @Synchronized
    private fun stagePackageBatch(
        data: Parcel,
        reply: Parcel,
    ) {
        val count = data.readInt()
        require(count in 1..MAX_PACKAGE_BATCH)
        val inputs = ArrayList<PackageInput>(count)
        try {
            repeat(count) {
                val index = data.readInt()
                require(index in 0 until MAX_CLOSURE_PACKAGES)
                val archive =
                    data.readFileDescriptor()
                        ?: throw IllegalArgumentException("Missing package archive descriptor")
                try {
                    val signature =
                        data.readFileDescriptor()
                            ?: throw IllegalArgumentException(
                                "Missing package signature descriptor",
                            )
                    inputs += PackageInput(index, archive, signature)
                } catch (error: Exception) {
                    archive.close()
                    throw error
                }
            }
            require(inputs.map { input -> input.index }.toSet().size == inputs.size)
            inputs.forEach { input ->
                val result =
                    NativeBuilder.nativeStagePackage(
                        input.index,
                        input.archive.fd,
                        input.signature.fd,
                    )
                check(result == 0) {
                    "Builder rejected verified package ${input.index} ($result)"
                }
            }
            reply.writeNoException()
            reply.writeInt(inputs.size)
        } finally {
            inputs.forEach { input ->
                runCatching { input.archive.close() }
                runCatching { input.signature.close() }
            }
        }
    }

    @Synchronized
    private fun finishPackageClosure(reply: Parcel) {
        val output =
            ByteBuffer
                .allocateDirect(NativeBuilder.CLOSURE_REPORT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        val result = NativeBuilder.nativeFinishPackageClosure(output)
        check(result == NativeBuilder.CLOSURE_REPORT_BYTES) {
            "Builder could not publish the verified package closure ($result)"
        }
        val magic = ByteArray(8)
        output.position(0)
        output.get(magic)
        check(String(magic, Charsets.US_ASCII) == "ABCR0001")
        val packageCount = output.getInt(8)
        val archiveBytes = output.getLong(16)
        val signatureBytes = output.getLong(24)
        val digest = ByteArray(32)
        output.position(32)
        output.get(digest)
        reply.writeNoException()
        reply.writeInt(packageCount)
        reply.writeLong(archiveBytes)
        reply.writeLong(signatureBytes)
        reply.writeString(digest.toHex())
    }

    @Synchronized
    private fun abortPackageClosure(reply: Parcel) {
        NativeBuilder.nativeAbortPackageClosure()
        reply.writeNoException()
    }

    @Synchronized
    private fun beginProvision(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageBase = data.readString().orEmpty()
        val version = data.readString().orEmpty()
        val manifestSha256 = data.readString().orEmpty()
        require(manifestSha256.matches(SHA256))
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeBeginProvision(
                filesDir.absolutePath,
                packageBase,
                version,
                manifestSha256,
                nativeOutputBuffer,
            )
        val report = readExtractionReport(result)
        val requiredBytes =
            runCatching {
                Math.addExact(report.expandedBytes, BUILD_ROOT_STORAGE_RESERVE_BYTES)
            }.getOrElse {
                NativeBuilder.nativeAbortProvision()
                throw IllegalStateException("Builder root storage estimate overflowed")
            }
        if (requiredBytes > filesDir.usableSpace) {
            NativeBuilder.nativeAbortProvision()
            throw IllegalStateException(
                "Not enough Builder-private storage for the isolated build root",
            )
        }
        writeExtractionReport(reply, report)
    }

    @Synchronized
    private fun extractProvisionBatch(
        data: Parcel,
        reply: Parcel,
    ) {
        val maximumPackages = data.readInt()
        require(maximumPackages in 1..MAX_PACKAGE_BATCH)
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeExtractProvisionBatch(
                maximumPackages,
                nativeOutputBuffer,
            )
        writeExtractionReport(reply, readExtractionReport(result))
    }

    @Synchronized
    private fun finishProvision(reply: Parcel) {
        nativeOutputBuffer.clear()
        val result = NativeBuilder.nativeFinishProvision(nativeOutputBuffer)
        writeExtractionReport(reply, readExtractionReport(result))
    }

    @Synchronized
    private fun abortProvision(reply: Parcel) {
        NativeBuilder.nativeAbortProvision()
        reply.writeNoException()
    }

    @Synchronized
    private fun probeRuntime(reply: Parcel) {
        val architecture =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64"
                "arm64-v8a" -> "aarch64"
                else -> throw IllegalStateException("Unsupported Builder ABI")
            }
        val manifest =
            assets.open("builder-runtime-$architecture.tsv").use { input ->
                input.readBytes()
            }
        require(manifest.isNotEmpty() && manifest.size <= MAX_RUNTIME_MANIFEST_BYTES)
        val manifestBuffer = ByteBuffer.allocateDirect(manifest.size).put(manifest)
        val output =
            ByteBuffer
                .allocateDirect(NativeBuilder.RUNTIME_OUTPUT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        val result =
            NativeBuilder.nativeProbeRuntime(
                filesDir.absolutePath,
                applicationInfo.nativeLibraryDir,
                manifestBuffer,
                manifest.size,
                output,
            )
        check(result in 1..NativeBuilder.RUNTIME_OUTPUT_BYTES) {
            "Builder execution runtime failed: ${readNativeMessage(output, result)}"
        }
        val bytes = ByteArray(result)
        output.position(0)
        output.get(bytes)
        val version =
            String(bytes, Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { line -> line.isNotBlank() }
                .orEmpty()
                .trim()
        check(
            version.isNotEmpty() &&
                version.length <= 256 &&
                version.none { character ->
                    character == '\u0000' ||
                        character.isISOControl() && character !in "\n\r\t"
                },
        ) {
            "Builder execution runtime returned invalid output"
        }
        reply.writeNoException()
        reply.writeString(version)
    }

    private fun readExtractionReport(result: Int): ExtractionReport {
        check(result == NativeBuilder.EXTRACTION_REPORT_BYTES) {
            "Builder root provisioning failed: " +
                readNativeMessage(nativeOutputBuffer, result)
        }
        val source =
            nativeOutputBuffer
                .duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        source.position(0)
        source.get(magic)
        check(String(magic, Charsets.US_ASCII) == "ABPE0001")
        val report =
            ExtractionReport(
                source.getInt(8),
                source.getLong(16),
                source.getLong(24),
            )
        check(
            report.packageCount in 0..MAX_CLOSURE_PACKAGES &&
                report.entryCount >= 0 &&
                report.expandedBytes >= 0,
        )
        return report
    }

    private fun writeExtractionReport(
        reply: Parcel,
        report: ExtractionReport,
    ) {
        reply.writeNoException()
        reply.writeInt(report.packageCount)
        reply.writeLong(report.entryCount)
        reply.writeLong(report.expandedBytes)
    }

    private fun enforceManagerCaller(callerUid: Int) {
        if (
            packageManager.checkSignatures(callerUid, Process.myUid()) !=
            PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Builder caller signer does not match")
        }
        val packages = packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty()
        if (
            "org.archphene.app" !in packages &&
            "org.archphene.app.debug" !in packages
        ) {
            throw SecurityException("Builder caller is not Archphene manager")
        }
    }

    @Synchronized
    private fun runProbeAndStage(
        callerUid: Int,
        managerSentinel: String,
        output: ParcelFileDescriptor,
        packageBase: String,
        version: String,
        inputs: List<BuildInput>,
    ): ProbeReport {
        val uid = Process.myUid()
        val internetPermission =
            checkSelfPermission(Manifest.permission.INTERNET) ==
                PackageManager.PERMISSION_GRANTED
        val directManagerDataReadable =
            managerSentinel.isNotEmpty() &&
                runCatching {
                    File(managerSentinel).readBytes()
                }.isSuccess
        val privateWorkspaceWritable =
            runCatching {
                val workspace = File(filesDir, "aur-build-workspace")
                require(workspace.exists() || workspace.mkdir())
                val marker = File(workspace, "builder-owned")
                marker.writeText("builder:$uid\n", Charsets.US_ASCII)
                marker.readText(Charsets.US_ASCII) == "builder:$uid\n"
            }.getOrDefault(false)
        val outputWriteSucceeded =
            runCatching {
                val bytes = "builder-output:$uid\n".toByteArray(Charsets.US_ASCII)
                Os.ftruncate(output.fileDescriptor, 0)
                require(Os.write(output.fileDescriptor, bytes, 0, bytes.size) == bytes.size)
                Os.fsync(output.fileDescriptor)
            }.isSuccess
        val selinuxContext =
            runCatching {
                File("/proc/self/attr/current").readText().trimEnd('\u0000', '\n')
            }.getOrDefault("unavailable")
        val staged = stageReviewedInputs(packageBase, version, inputs)
        return ProbeReport(
            uid,
            callerUid,
            internetPermission,
            directManagerDataReadable,
            privateWorkspaceWritable,
            outputWriteSucceeded,
            selinuxContext,
            staged.first,
            staged.second,
        )
    }

    private fun stageReviewedInputs(
        packageBase: String,
        version: String,
        inputs: List<BuildInput>,
    ): Pair<Long, String> {
        require(packageBase.matches(PACKAGE_NAME))
        require(version.length in 1..128 && version.all { it.code in 0x21..0x7e })
        require(inputs.count { input -> input.role == ROLE_SNAPSHOT } == 1)
        require(inputs.all { input -> input.role == ROLE_SNAPSHOT || input.role == ROLE_SOURCE })
        val names = HashSet<String>(inputs.size)
        var totalBytes = 0L
        inputs.forEach { input ->
            require(input.filename.matches(SAFE_FILENAME) && names.add(input.filename))
            require(input.sha256.matches(SHA256))
            require(input.bytes in 1..MAX_INPUT_BYTES)
            totalBytes = Math.addExact(totalBytes, input.bytes)
            require(totalBytes <= MAX_TOTAL_BYTES)
            require(OsConstants.S_ISREG(Os.fstat(input.descriptor.fileDescriptor).st_mode))
        }
        val workspace = requirePrivateDirectory(File(filesDir, "aur-build-workspace"))
        val inputDirectory = requirePrivateDirectory(File(workspace, "reviewed-inputs"))
        val manifest = StringBuilder(1024 + inputs.size * 192)
        manifest
            .append("ABIN0001\n")
            .append("package=")
            .append(packageBase)
            .append('\n')
            .append("version=")
            .append(version)
            .append('\n')
        inputs.sortedWith(compareBy<BuildInput> { it.role }.thenBy { it.filename }).forEach { input ->
            val prefix = if (input.role == ROLE_SNAPSHOT) "snapshot-" else "source-"
            val destination = File(inputDirectory, "$prefix${input.sha256}-${input.filename}")
            publishVerifiedInput(input, destination)
            manifest
                .append(if (input.role == ROLE_SNAPSHOT) "snapshot" else "source")
                .append('\t')
                .append(input.filename)
                .append('\t')
                .append(input.bytes)
                .append('\t')
                .append(input.sha256)
                .append('\n')
        }
        val manifestBytes = manifest.toString().toByteArray(Charsets.US_ASCII)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES)
        val manifestDigest = sha256(manifestBytes)
        publishBytes(File(inputDirectory, "manifest"), manifestBytes)
        val directoryDescriptor =
            Os.open(
                inputDirectory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0,
            )
        try {
            Os.fsync(directoryDescriptor)
        } finally {
            Os.close(directoryDescriptor)
        }
        return totalBytes to manifestDigest
    }

    private fun requirePrivateDirectory(directory: File): File {
        val path = directory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path),
            )
        } else {
            Files.createDirectory(path)
        }
        return directory
    }

    private fun publishVerifiedInput(
        input: BuildInput,
        destination: File,
    ) {
        if (verifyRegularFile(destination, input.bytes, input.sha256)) {
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        prepareRegularOutput(temporary)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(input.descriptor).use { source ->
            FileOutputStream(temporary).use { output ->
                while (true) {
                    val count = source.read(transferBuffer)
                    if (count < 0) {
                        break
                    }
                    total = Math.addExact(total, count.toLong())
                    require(total <= input.bytes)
                    output.write(transferBuffer, 0, count)
                    digest.update(transferBuffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(total == input.bytes && digest.digest().toHex() == input.sha256)
        Os.chmod(temporary.absolutePath, 0x180)
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun publishBytes(
        destination: File,
        bytes: ByteArray,
    ) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        prepareRegularOutput(temporary)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Os.chmod(temporary.absolutePath, 0x180)
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun prepareRegularOutput(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        Files.delete(path)
    }

    private fun verifyRegularFile(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        require(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path),
        )
        if (Files.size(path) != expectedBytes) {
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(transferBuffer)
                if (count < 0) {
                    break
                }
                digest.update(transferBuffer, 0, count)
            }
        }
        return digest.digest().toHex() == expectedSha256
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_DIGITS[value ushr 4]
            output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(output)
    }

    private fun readNativeMessage(
        buffer: ByteBuffer,
        result: Int,
    ): String {
        val source = buffer.duplicate()
        source.position(0)
        val bytes = ByteArray(minOf(source.remaining(), NativeBuilder.ERROR_OUTPUT_BYTES))
        source.get(bytes)
        val length = bytes.indexOf(0).let { index -> if (index < 0) bytes.size else index }
        return String(bytes, 0, length, Charsets.UTF_8).ifEmpty { "native error $result" }
    }

    private data class BuildInput(
        val role: Int,
        val filename: String,
        val sha256: String,
        val bytes: Long,
        val descriptor: ParcelFileDescriptor,
    )

    private data class PackageInput(
        val index: Int,
        val archive: ParcelFileDescriptor,
        val signature: ParcelFileDescriptor,
    )

    private data class ExtractionReport(
        val packageCount: Int,
        val entryCount: Long,
        val expandedBytes: Long,
    )

    private data class ProbeReport(
        val uid: Int,
        val callingUid: Int,
        val internetPermission: Boolean,
        val directManagerDataReadable: Boolean,
        val privateWorkspaceWritable: Boolean,
        val outputWriteSucceeded: Boolean,
        val selinuxContext: String,
        val stagedBytes: Long,
        val inputManifestSha256: String,
    )

    companion object {
        const val DESCRIPTOR = "org.archphene.builder.AurBuilder"
        const val TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_BEGIN_PACKAGE_CLOSURE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_STAGE_PACKAGE_BATCH = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_FINISH_PACKAGE_CLOSURE = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_ABORT_PACKAGE_CLOSURE = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_BEGIN_PROVISION = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_EXTRACT_PROVISION_BATCH = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_FINISH_PROVISION = IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_ABORT_PROVISION = IBinder.FIRST_CALL_TRANSACTION + 8
        const val TRANSACTION_PROBE_RUNTIME = IBinder.FIRST_CALL_TRANSACTION + 9
        private const val ROLE_SNAPSHOT = 0
        private const val ROLE_SOURCE = 1
        private const val MAX_INPUTS = 65
        private const val MAX_CLOSURE_PACKAGES = 512
        private const val MAX_CLOSURE_MANIFEST_BYTES = 512 * 1024
        private const val MAX_PACKAGE_BATCH = 8
        private const val BUILD_ROOT_STORAGE_RESERVE_BYTES = 512L * 1024 * 1024
        private const val MAX_INPUT_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private const val MAX_RUNTIME_MANIFEST_BYTES = 32 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
        private val PACKAGE_NAME = Regex("[A-Za-z0-9@._+-]{1,128}")
        private val SAFE_FILENAME = Regex("[A-Za-z0-9@+,._-]{1,240}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
