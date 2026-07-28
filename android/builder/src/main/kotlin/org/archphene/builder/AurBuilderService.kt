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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AurBuilderService : Service() {
    private val nativeOutputBuffer =
        ByteBuffer
            .allocateDirect(NativeBuilder.ERROR_OUTPUT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
    private var provisionPackageCount = 0

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
                            TRANSACTION_SCAN_PROVISION_BATCH ->
                                scanProvisionBatch(data, reply)
                            TRANSACTION_PREPARE_PROVISION_ROOT ->
                                prepareProvisionRoot(reply)
                            TRANSACTION_EXTRACT_PROVISION_BATCH ->
                                extractProvisionBatch(data, reply)
                            TRANSACTION_FINISH_PROVISION -> finishProvision(reply)
                            TRANSACTION_ABORT_PROVISION -> abortProvision(reply)
                            TRANSACTION_PROBE_RUNTIME -> probeRuntime(reply)
                            TRANSACTION_PREPARE_RECIPE -> prepareRecipe(data, reply)
                            TRANSACTION_BEGIN_AUR_DEPENDENCIES ->
                                beginAurDependencies(data, reply)
                            TRANSACTION_STAGE_AUR_DEPENDENCY_BATCH ->
                                stageAurDependencyBatch(data, reply)
                            TRANSACTION_FINISH_AUR_DEPENDENCIES ->
                                finishAurDependencies(reply)
                            TRANSACTION_ABORT_AUR_DEPENDENCIES ->
                                abortAurDependencies(reply)
                            TRANSACTION_INSTALL_AUR_DEPENDENCIES ->
                                installAurDependencies(data, reply)
                            TRANSACTION_START_BUILD -> startBuild(data, reply)
                            TRANSACTION_POLL_BUILD -> pollBuild(reply)
                            TRANSACTION_CANCEL_BUILD -> cancelBuild(reply)
                            TRANSACTION_VERIFY_OUTPUT -> verifyOutput(data, reply)
                            TRANSACTION_STORAGE_USAGE -> storageUsage(reply, clear = false)
                            TRANSACTION_CLEAR_STORAGE -> storageUsage(reply, clear = true)
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
        provisionPackageCount = 0
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
        check(report.packageCount in 1..MAX_CLOSURE_PACKAGES)
        check(report.entryCount == 0L && report.expandedBytes == 0L)
        provisionPackageCount = report.packageCount
        writeExtractionReport(reply, report)
    }

    @Synchronized
    private fun scanProvisionBatch(
        data: Parcel,
        reply: Parcel,
    ) {
        val maximumPackages = data.readInt()
        require(maximumPackages in 1..MAX_PACKAGE_BATCH)
        check(provisionPackageCount in 1..MAX_CLOSURE_PACKAGES)
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeScanProvisionBatch(
                maximumPackages,
                nativeOutputBuffer,
            )
        val report = readExtractionReport(result)
        check(report.packageCount in 1..provisionPackageCount)
        if (report.packageCount == provisionPackageCount) {
            val requiredBytes =
                runCatching {
                    Math.addExact(report.expandedBytes, BUILD_ROOT_STORAGE_RESERVE_BYTES)
                }.getOrElse {
                    NativeBuilder.nativeAbortProvision()
                    provisionPackageCount = 0
                    throw IllegalStateException("Builder root storage estimate overflowed")
                }
            if (requiredBytes > filesDir.usableSpace) {
                NativeBuilder.nativeAbortProvision()
                provisionPackageCount = 0
                throw IllegalStateException(
                    "Not enough Builder-private storage for the isolated build root",
                )
            }
        }
        writeExtractionReport(reply, report)
    }

    @Synchronized
    private fun prepareProvisionRoot(reply: Parcel) {
        check(provisionPackageCount in 1..MAX_CLOSURE_PACKAGES)
        nativeOutputBuffer.clear()
        val result = NativeBuilder.nativePrepareProvisionRoot(nativeOutputBuffer)
        val report = readExtractionReport(result)
        check(report.packageCount == provisionPackageCount)
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
        val report = readExtractionReport(result)
        provisionPackageCount = 0
        writeExtractionReport(reply, report)
    }

    @Synchronized
    private fun abortProvision(reply: Parcel) {
        NativeBuilder.nativeAbortProvision()
        provisionPackageCount = 0
        reply.writeNoException()
    }

    @Synchronized
    private fun probeRuntime(reply: Parcel) {
        val manifest = readRuntimeManifest()
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

    @Synchronized
    private fun prepareRecipe(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageBase = data.readString().orEmpty()
        val version = data.readString().orEmpty()
        val inputManifestSha256 = data.readString().orEmpty()
        val closureSha256 = data.readString().orEmpty()
        require(inputManifestSha256.matches(SHA256) && closureSha256.matches(SHA256))
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativePrepareRecipeWorkspace(
                filesDir.absolutePath,
                packageBase,
                version,
                inputManifestSha256,
                closureSha256,
                nativeOutputBuffer,
            )
        check(result == NativeBuilder.RECIPE_WORKSPACE_REPORT_BYTES) {
            "Builder could not prepare the reviewed recipe: " +
                readNativeMessage(nativeOutputBuffer, result)
        }
        val source =
            nativeOutputBuffer
                .duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        source.position(0)
        source.get(magic)
        check(String(magic, Charsets.US_ASCII) == "ABRW0001")
        val recipeEntries = source.getLong(8)
        val recipeBytes = source.getLong(16)
        val sourceBytes = source.getLong(24)
        check(recipeEntries > 0 && recipeBytes > 0 && sourceBytes >= 0)
        reply.writeNoException()
        reply.writeLong(recipeEntries)
        reply.writeLong(recipeBytes)
        reply.writeLong(sourceBytes)
    }

    @Synchronized
    private fun beginAurDependencies(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageCount = data.readInt()
        require(packageCount in 1..MAX_AUR_DEPENDENCY_PACKAGES)
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeBeginAurDependencyArchives(
                filesDir.absolutePath,
                packageCount,
                nativeOutputBuffer,
            )
        check(result == 0) {
            "Builder could not begin AUR dependency staging: " +
                readNativeMessage(nativeOutputBuffer, result)
        }
        reply.writeNoException()
    }

    @Synchronized
    private fun stageAurDependencyBatch(
        data: Parcel,
        reply: Parcel,
    ) {
        val count = data.readInt()
        require(count in 1..MAX_PACKAGE_BATCH)
        val inputs = ArrayList<AurDependencyInput>(count)
        try {
            repeat(count) {
                val input =
                    AurDependencyInput(
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readLong(),
                        data.readString().orEmpty(),
                        data.readFileDescriptor()
                            ?: throw IllegalArgumentException(
                                "Missing AUR dependency archive descriptor",
                            ),
                    )
                require(
                    input.packageBase.matches(PACKAGE_NAME) &&
                        input.packageName.matches(PACKAGE_NAME) &&
                        input.version.length in 1..128 &&
                        (
                            input.architecture == "aarch64" ||
                                input.architecture == "x86_64"
                        ) &&
                        input.filename.matches(PACKAGE_ARCHIVE_NAME) &&
                        input.archiveBytes > 0 &&
                        input.sha256.matches(SHA256),
                )
                inputs += input
            }
            require(inputs.map(AurDependencyInput::packageName).toSet().size == inputs.size)
            inputs.forEach { input ->
                nativeOutputBuffer.clear()
                val result =
                    NativeBuilder.nativeStageAurDependencyArchive(
                        input.packageBase,
                        input.packageName,
                        input.version,
                        input.architecture,
                        input.filename,
                        input.archiveBytes,
                        input.sha256,
                        input.descriptor.fd,
                        nativeOutputBuffer,
                    )
                check(result == 0) {
                    "Builder rejected AUR dependency ${input.packageName}: " +
                        readNativeMessage(nativeOutputBuffer, result)
                }
            }
            reply.writeNoException()
            reply.writeInt(inputs.size)
        } finally {
            inputs.forEach { input -> runCatching { input.descriptor.close() } }
        }
    }

    @Synchronized
    private fun finishAurDependencies(reply: Parcel) {
        nativeOutputBuffer.clear()
        val result = NativeBuilder.nativeFinishAurDependencyArchives(nativeOutputBuffer)
        val report =
            readAurDependencyReport(
                nativeOutputBuffer,
                result,
                "ABDS0001",
                expectedRequirementCount = 0,
            )
        reply.writeNoException()
        writeAurDependencyReport(reply, report)
    }

    @Synchronized
    private fun abortAurDependencies(reply: Parcel) {
        NativeBuilder.nativeAbortAurDependencyArchives()
        reply.writeNoException()
    }

    @Synchronized
    private fun installAurDependencies(
        data: Parcel,
        reply: Parcel,
    ) {
        val manifestSha256 = data.readString().orEmpty()
        val requirementCount = data.readInt()
        require(manifestSha256.matches(SHA256))
        require(requirementCount in 1..MAX_AUR_DEPENDENCY_REQUIREMENTS)
        val requirements = ArrayList<String>(requirementCount)
        repeat(requirementCount) {
            val requirement = data.readString().orEmpty()
            require(
                requirement.length in 1..MAX_AUR_REQUIREMENT_BYTES &&
                    requirement.matches(AUR_REQUIREMENT) &&
                    requirement !in requirements,
            )
            requirements += requirement
        }
        requirements.sort()
        val requirementBytes =
            requirements.joinToString(separator = "\n", postfix = "\n")
                .toByteArray(Charsets.US_ASCII)
        require(requirementBytes.size <= NativeBuilder.AUR_REQUIREMENTS_MAX_BYTES)
        val requirementsBuffer = ByteBuffer.allocateDirect(requirementBytes.size)
        requirementsBuffer.put(requirementBytes)
        val runtimeManifest = readRuntimeManifest()
        val runtimeBuffer = ByteBuffer.allocateDirect(runtimeManifest.size).put(runtimeManifest)
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeInstallAurDependencies(
                filesDir.absolutePath,
                applicationInfo.nativeLibraryDir,
                runtimeBuffer,
                runtimeManifest.size,
                manifestSha256,
                requirementsBuffer,
                requirementBytes.size,
                nativeOutputBuffer,
            )
        val report =
            readAurDependencyReport(
                nativeOutputBuffer,
                result,
                "ABDI0002",
                expectedRequirementCount = requirements.size,
            )
        reply.writeNoException()
        writeAurDependencyReport(reply, report)
    }

    @Synchronized
    private fun startBuild(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageBase = data.readString().orEmpty()
        val version = data.readString().orEmpty()
        val inputManifestSha256 = data.readString().orEmpty()
        val closureSha256 = data.readString().orEmpty()
        require(inputManifestSha256.matches(SHA256) && closureSha256.matches(SHA256))
        val runtimeManifest = readRuntimeManifest()
        val runtimeBuffer = ByteBuffer.allocateDirect(runtimeManifest.size).put(runtimeManifest)
        nativeOutputBuffer.clear()
        val result =
            NativeBuilder.nativeStartBuild(
                filesDir.absolutePath,
                applicationInfo.nativeLibraryDir,
                runtimeBuffer,
                runtimeManifest.size,
                packageBase,
                version,
                inputManifestSha256,
                closureSha256,
                nativeOutputBuffer,
            )
        check(result == 0) {
            "Builder could not start the reviewed recipe: " +
                readNativeMessage(nativeOutputBuffer, result)
        }
        reply.writeNoException()
    }

    @Synchronized
    private fun pollBuild(reply: Parcel) {
        val output =
            ByteBuffer
                .allocateDirect(NativeBuilder.BUILD_POLL_OUTPUT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        val result = NativeBuilder.nativePollBuild(output)
        check(result in 16..NativeBuilder.BUILD_POLL_OUTPUT_BYTES) {
            "Builder execution failed: ${readNativeMessage(output, result)}"
        }
        val magic = ByteArray(8)
        output.position(0)
        output.get(magic)
        check(String(magic, Charsets.US_ASCII) == "ABBP0001")
        val exitStatus = output.getInt(8)
        val logLength = output.getInt(12)
        check(
            exitStatus >= -1 &&
                logLength in 0..(NativeBuilder.BUILD_POLL_OUTPUT_BYTES - 16) &&
                result == 16 + logLength,
        )
        val logs = ByteArray(logLength)
        output.position(16)
        output.get(logs)
        reply.writeNoException()
        reply.writeInt(exitStatus)
        reply.writeByteArray(logs)
    }

    @Synchronized
    private fun cancelBuild(reply: Parcel) {
        val cancelled = NativeBuilder.nativeCancelBuild()
        reply.writeNoException()
        reply.writeBoolean(cancelled)
    }

    @Synchronized
    private fun verifyOutput(
        data: Parcel,
        reply: Parcel,
    ) {
        val packageBase = data.readString().orEmpty()
        val packageName = data.readString().orEmpty()
        val version = data.readString().orEmpty()
        val architecture = data.readString().orEmpty()
        val closureSha256 = data.readString().orEmpty()
        require(closureSha256.matches(SHA256))
        val destination =
            data.readFileDescriptor()
                ?: throw IllegalArgumentException("Missing verified-output descriptor")
        destination.use { descriptor ->
            val output =
                ByteBuffer
                    .allocateDirect(NativeBuilder.BUILT_PACKAGE_REPORT_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            val result =
                NativeBuilder.nativeVerifyAndCopyBuiltPackage(
                    filesDir.absolutePath,
                    packageBase,
                    packageName,
                    version,
                    architecture,
                    closureSha256,
                    descriptor.fd,
                    output,
                )
            check(result in 65..NativeBuilder.BUILT_PACKAGE_REPORT_BYTES) {
                "Builder output verification failed: ${readNativeMessage(output, result)}"
            }
            val magic = ByteArray(8)
            output.position(0)
            output.get(magic)
            check(String(magic, Charsets.US_ASCII) == "ABOP0001")
            val archiveBytes = output.getLong(8)
            val installedBytes = output.getLong(16)
            val buildPackageCount = output.getInt(24)
            val filenameLength = output.getInt(28)
            val sha256 = ByteArray(32)
            output.position(32)
            output.get(sha256)
            check(
                archiveBytes > 0L &&
                    installedBytes > 0L &&
                    buildPackageCount > 0 &&
                    filenameLength in 1..(NativeBuilder.BUILT_PACKAGE_REPORT_BYTES - 64) &&
                    result == 64 + filenameLength,
            )
            val filename = ByteArray(filenameLength)
            output.position(64)
            output.get(filename)
            reply.writeNoException()
            reply.writeString(String(filename, Charsets.UTF_8))
            reply.writeLong(archiveBytes)
            reply.writeLong(installedBytes)
            reply.writeInt(buildPackageCount)
            reply.writeString(hexSha256(sha256))
        }
    }

    @Synchronized
    private fun storageUsage(
        reply: Parcel,
        clear: Boolean,
    ) {
        val output = ByteBuffer.allocateDirect(NativeBuilder.ERROR_OUTPUT_BYTES)
        val result =
            if (clear) {
                NativeBuilder.nativeClearStorage(filesDir.absolutePath, output)
            } else {
                NativeBuilder.nativeReadStorageUsage(filesDir.absolutePath, output)
            }
        check(result in 7 until NativeBuilder.ERROR_OUTPUT_BYTES) {
            "Builder storage inventory failed: ${readNativeMessage(output, result)}"
        }
        val fields =
            ByteArray(result)
                .also { bytes ->
                    output.position(0)
                    output.get(bytes)
                }.toString(Charsets.US_ASCII)
                .trimEnd('\n')
                .split('\t')
        check(fields.size == 3 && fields[0] == "B1")
        val entries = fields[1].toLongOrNull() ?: -1L
        val bytes = fields[2].toLongOrNull() ?: -1L
        check(entries >= 0L && bytes >= 0L)
        reply.writeNoException()
        reply.writeLong(entries)
        reply.writeLong(bytes)
    }

    private fun hexSha256(value: ByteArray): String {
        require(value.size == 32)
        return buildString(64) {
            value.forEach { byte ->
                append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
                append(HEX_DIGITS[byte.toInt() and 0x0f])
            }
        }
    }

    private fun readRuntimeManifest(): ByteArray {
        val architecture =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64"
                "arm64-v8a" -> "aarch64"
                else -> throw IllegalStateException("Unsupported Builder ABI")
            }
        return assets.open("builder-runtime-$architecture.tsv").use { input ->
            input.readBytes()
        }.also { manifest ->
            require(manifest.isNotEmpty() && manifest.size <= MAX_RUNTIME_MANIFEST_BYTES)
        }
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

    private fun readAurDependencyReport(
        buffer: ByteBuffer,
        result: Int,
        expectedMagic: String,
        expectedRequirementCount: Int,
    ): AurDependencyReport {
        check(result == NativeBuilder.AUR_DEPENDENCY_REPORT_BYTES) {
            "Builder AUR dependency operation failed: " +
                readNativeMessage(buffer, result)
        }
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        source.position(0)
        source.get(magic)
        check(String(magic, Charsets.US_ASCII) == expectedMagic)
        val digest = ByteArray(32)
        source.position(32)
        source.get(digest)
        val report =
            AurDependencyReport(
                source.getInt(8),
                source.getInt(12),
                source.getLong(16),
                source.getLong(24),
                digest.toHex(),
            )
        check(
            report.packageCount in 1..MAX_AUR_DEPENDENCY_PACKAGES &&
                report.requirementCount == expectedRequirementCount &&
                report.archiveBytes > 0 &&
                report.installedBytes > 0 &&
                report.manifestSha256.matches(SHA256),
        )
        return report
    }

    private fun writeAurDependencyReport(
        reply: Parcel,
        report: AurDependencyReport,
    ) {
        reply.writeInt(report.packageCount)
        reply.writeInt(report.requirementCount)
        reply.writeLong(report.archiveBytes)
        reply.writeLong(report.installedBytes)
        reply.writeString(report.manifestSha256)
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
        val privateWorkspaceWritable = staged.first > 0 && staged.second.length == 64
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
        nativeOutputBuffer.clear()
        val begin =
            NativeBuilder.nativeBeginReviewedInputs(
                filesDir.absolutePath,
                packageBase,
                version,
                inputs.size,
                nativeOutputBuffer,
            )
        check(begin == 0) {
            "Builder rejected reviewed inputs: " +
                readNativeMessage(nativeOutputBuffer, begin)
        }
        var finished = false
        try {
            inputs
                .sortedWith(
                    compareBy<BuildInput> { input -> input.role }
                        .thenBy { input -> input.filename },
                ).forEach { input ->
                    val result =
                        NativeBuilder.nativeStageReviewedInput(
                            input.role,
                            input.filename,
                            input.bytes,
                            input.sha256,
                            input.descriptor.fd,
                        )
                    check(result == 0) {
                        "Builder rejected reviewed input ${input.filename} ($result)"
                    }
                }
            val output =
                ByteBuffer
                    .allocateDirect(NativeBuilder.REVIEWED_INPUT_REPORT_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            val result = NativeBuilder.nativeFinishReviewedInputs(output)
            check(result == NativeBuilder.REVIEWED_INPUT_REPORT_BYTES) {
                "Builder could not publish reviewed inputs ($result)"
            }
            val magic = ByteArray(8)
            output.position(0)
            output.get(magic)
            check(String(magic, Charsets.US_ASCII) == "ABIR0001")
            val inputCount = output.getInt(8)
            val inputBytes = output.getLong(16)
            val manifestSha256 = ByteArray(32)
            output.position(24)
            output.get(manifestSha256)
            check(
                inputCount == inputs.size &&
                    inputBytes > 0 &&
                    manifestSha256.any { byte -> byte.toInt() != 0 },
            )
            finished = true
            return inputBytes to manifestSha256.toHex()
        } finally {
            if (!finished) {
                NativeBuilder.nativeAbortReviewedInputs()
            }
        }
    }

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

    private data class AurDependencyInput(
        val packageBase: String,
        val packageName: String,
        val version: String,
        val architecture: String,
        val filename: String,
        val archiveBytes: Long,
        val sha256: String,
        val descriptor: ParcelFileDescriptor,
    )

    private data class AurDependencyReport(
        val packageCount: Int,
        val requirementCount: Int,
        val archiveBytes: Long,
        val installedBytes: Long,
        val manifestSha256: String,
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
        const val TRANSACTION_PREPARE_RECIPE = IBinder.FIRST_CALL_TRANSACTION + 10
        const val TRANSACTION_START_BUILD = IBinder.FIRST_CALL_TRANSACTION + 11
        const val TRANSACTION_POLL_BUILD = IBinder.FIRST_CALL_TRANSACTION + 12
        const val TRANSACTION_CANCEL_BUILD = IBinder.FIRST_CALL_TRANSACTION + 13
        const val TRANSACTION_VERIFY_OUTPUT = IBinder.FIRST_CALL_TRANSACTION + 14
        const val TRANSACTION_STORAGE_USAGE = IBinder.FIRST_CALL_TRANSACTION + 15
        const val TRANSACTION_CLEAR_STORAGE = IBinder.FIRST_CALL_TRANSACTION + 16
        const val TRANSACTION_SCAN_PROVISION_BATCH = IBinder.FIRST_CALL_TRANSACTION + 17
        const val TRANSACTION_PREPARE_PROVISION_ROOT = IBinder.FIRST_CALL_TRANSACTION + 18
        const val TRANSACTION_BEGIN_AUR_DEPENDENCIES = IBinder.FIRST_CALL_TRANSACTION + 19
        const val TRANSACTION_STAGE_AUR_DEPENDENCY_BATCH =
            IBinder.FIRST_CALL_TRANSACTION + 20
        const val TRANSACTION_FINISH_AUR_DEPENDENCIES = IBinder.FIRST_CALL_TRANSACTION + 21
        const val TRANSACTION_ABORT_AUR_DEPENDENCIES = IBinder.FIRST_CALL_TRANSACTION + 22
        const val TRANSACTION_INSTALL_AUR_DEPENDENCIES = IBinder.FIRST_CALL_TRANSACTION + 23
        private const val ROLE_SNAPSHOT = 0
        private const val ROLE_SOURCE = 1
        private const val MAX_INPUTS = 65
        private const val MAX_CLOSURE_PACKAGES = 512
        private const val MAX_CLOSURE_MANIFEST_BYTES = 512 * 1024
        private const val MAX_PACKAGE_BATCH = 8
        private const val MAX_AUR_DEPENDENCY_PACKAGES = 256
        private const val MAX_AUR_DEPENDENCY_REQUIREMENTS = 256
        private const val MAX_AUR_REQUIREMENT_BYTES = 4 * 1024
        private const val BUILD_ROOT_STORAGE_RESERVE_BYTES = 512L * 1024 * 1024
        private const val MAX_RUNTIME_MANIFEST_BYTES = 32 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val PACKAGE_NAME = Regex("[A-Za-z0-9@+._-]{1,128}")
        private val PACKAGE_ARCHIVE_NAME =
            Regex("[A-Za-z0-9@+:._-]{1,240}\\.pkg\\.tar\\.(xz|zst)")
        private val AUR_REQUIREMENT =
            Regex("[A-Za-z0-9@+._-]{1,128}([<>=]{1,2}[A-Za-z0-9@+:._-]+)?")
    }
}
