package org.archphene.builder

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AurBuilderService : Service() {
    private val transferBuffer = ByteArray(64 * 1024)

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
                if (code != TRANSACTION_PROBE || reply == null) {
                    return super.onTransact(code, data, reply, flags)
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

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class BuildInput(
        val role: Int,
        val filename: String,
        val sha256: String,
        val bytes: Long,
        val descriptor: ParcelFileDescriptor,
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
        private const val ROLE_SNAPSHOT = 0
        private const val ROLE_SOURCE = 1
        private const val MAX_INPUTS = 65
        private const val MAX_INPUT_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private val PACKAGE_NAME = Regex("[A-Za-z0-9@._+-]{1,128}")
        private val SAFE_FILENAME = Regex("[A-Za-z0-9@+,._-]{1,240}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
