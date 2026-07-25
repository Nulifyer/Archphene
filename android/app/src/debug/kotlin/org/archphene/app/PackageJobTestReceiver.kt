package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.archphene.app.runtime.NativeRuntime

internal class PackageJobTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEED) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val terminalState = intent.getStringExtra(EXTRA_STATE)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            packageName == null ||
            !PACKAGE.matches(packageName) ||
            terminalState !in setOf("complete", "failed", "cancelled")
        ) {
            Log.e(TAG, "Rejected invalid package-job fixture")
            return
        }
        val pending = goAsync()
        Thread(
            {
                var handle = 0L
                try {
                    handle = NativeRuntime.nativeCreate()
                    check(handle != 0L) { "could not create native runtime" }
                    val rootBytes =
                        File(context.filesDir, "arch-root")
                            .absolutePath
                            .toByteArray(StandardCharsets.UTF_8)
                    val rootBuffer = ByteBuffer.allocateDirect(rootBytes.size).put(rootBytes)
                    check(
                        NativeRuntime.nativeBootstrapArchRoot(
                            handle,
                            rootBuffer,
                            rootBytes.size,
                            System.currentTimeMillis(),
                        ) >= 0,
                    ) {
                        "could not bootstrap package-job fixture root"
                    }
                    val requestBytes = "extra\t$packageName".toByteArray(StandardCharsets.UTF_8)
                    val requestBuffer =
                        ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
                    val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val jobId =
                        NativeRuntime.nativeQueuePackageJob(
                            handle,
                            NativeRuntime.JOB_OPERATION_INSTALL,
                            requestBuffer,
                            requestBytes.size,
                            System.currentTimeMillis(),
                            outputBuffer,
                        )
                    check(jobId > 0) { "could not queue package-job fixture: $jobId" }
                    when (terminalState) {
                        "complete" -> {
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_RESOLVING,
                                1,
                                10,
                                "Resolving signed dependency closure",
                                outputBuffer,
                            )
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_DOWNLOADING,
                                2,
                                55,
                                "Downloading verified package archives",
                                outputBuffer,
                            )
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_VERIFYING,
                                3,
                                90,
                                "Verifying package signatures",
                                outputBuffer,
                            )
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_INSTALLING,
                                4,
                                97,
                                "Installing verified packages",
                                outputBuffer,
                            )
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_COMPLETE,
                                5,
                                100,
                                "Installed $packageName 1.0.0",
                                outputBuffer,
                            )
                        }
                        "failed" ->
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_FAILED,
                                0,
                                0,
                                "Network unavailable; retry is required",
                                outputBuffer,
                            )
                        else ->
                            update(
                                handle,
                                jobId,
                                NativeRuntime.JOB_CANCELLED,
                                0,
                                0,
                                "Cancelled before package mutation",
                                outputBuffer,
                            )
                    }
                    Log.i(TAG, "Seeded package job state=$terminalState token=$token")
                } catch (error: Exception) {
                    Log.e(TAG, "Package-job fixture failed token=$token", error)
                } finally {
                    if (handle != 0L) {
                        NativeRuntime.nativeDestroy(handle)
                    }
                    pending.finish()
                }
            },
            "ArchphenePackageJobProbe",
        ).start()
    }

    private fun update(
        handle: Long,
        jobId: Long,
        state: Int,
        phase: Int,
        progress: Int,
        message: String,
        outputBuffer: ByteBuffer,
    ) {
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        val messageBuffer = ByteBuffer.allocateDirect(messageBytes.size).put(messageBytes)
        check(
            NativeRuntime.nativeUpdatePackageJob(
                handle,
                jobId,
                state,
                phase,
                progress,
                messageBuffer,
                messageBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            ) == 0,
        ) {
            "could not update package-job fixture to state=$state"
        }
    }

    private companion object {
        private const val TAG = "ArchphenePackageJobProbe"
        private const val ACTION_SEED = "org.archphene.app.debug.action.SEED_PACKAGE_JOB"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_STATE = "state"
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
    }
}
