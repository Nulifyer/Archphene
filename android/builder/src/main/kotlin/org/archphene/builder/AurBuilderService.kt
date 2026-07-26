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
import java.io.File

class AurBuilderService : Service() {
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
                output.use { outputDescriptor ->
                    val report = runProbe(callerUid, managerSentinel, outputDescriptor)
                    reply.writeNoException()
                    reply.writeInt(report.uid)
                    reply.writeInt(report.callingUid)
                    reply.writeBoolean(report.internetPermission)
                    reply.writeBoolean(report.directManagerDataReadable)
                    reply.writeBoolean(report.privateWorkspaceWritable)
                    reply.writeBoolean(report.outputWriteSucceeded)
                    reply.writeString(report.selinuxContext)
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

    private fun runProbe(
        callerUid: Int,
        managerSentinel: String,
        output: ParcelFileDescriptor,
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
        return ProbeReport(
            uid,
            callerUid,
            internetPermission,
            directManagerDataReadable,
            privateWorkspaceWritable,
            outputWriteSucceeded,
            selinuxContext,
        )
    }

    private data class ProbeReport(
        val uid: Int,
        val callingUid: Int,
        val internetPermission: Boolean,
        val directManagerDataReadable: Boolean,
        val privateWorkspaceWritable: Boolean,
        val outputWriteSucceeded: Boolean,
        val selinuxContext: String,
    )

    companion object {
        const val DESCRIPTOR = "org.archphene.builder.AurBuilder"
        const val TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION
    }
}
