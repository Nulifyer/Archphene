package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.archphene.app.runtime.NativeRuntime
import org.archphene.app.runtime.PackageFailureDiagnostics

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
        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: "install"
        val failure = intent.getStringExtra(EXTRA_FAILURE)
        val cacheFixture = intent.getBooleanExtra(EXTRA_CACHE_FIXTURE, false)
        val cacheEntries = intent.getIntExtra(EXTRA_CACHE_ENTRIES, DEFAULT_CACHE_ENTRIES)
        val cacheHoldMillis = intent.getIntExtra(EXTRA_CACHE_HOLD_MILLIS, 0)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            packageName == null ||
            !PACKAGE.matches(packageName) ||
            terminalState !in setOf("complete", "failed", "cancelled") ||
            operation !in setOf("install", "remove") ||
            (
                failure != null &&
                    (terminalState != "failed" || failure !in FAILURE_CLASSES)
            ) ||
            (
                cacheFixture &&
                    (
                        terminalState != "failed" ||
                            failure != "storage" ||
                            cacheEntries !in DEFAULT_CACHE_ENTRIES..MAX_CACHE_ENTRIES ||
                            cacheHoldMillis !in 0..MAX_CACHE_HOLD_MILLIS
                    )
            ) ||
            (
                !cacheFixture &&
                    (
                        cacheEntries != DEFAULT_CACHE_ENTRIES ||
                            cacheHoldMillis != 0
                    )
            )
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
                    if (cacheFixture) {
                        seedPackageCache(context, cacheEntries)
                        if (cacheHoldMillis != 0) {
                            check(
                                context
                                    .getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
                                    .edit()
                                    .putLong(TEST_CACHE_HOLD_MILLIS, cacheHoldMillis.toLong())
                                    .commit(),
                            ) {
                                "could not save package-cache completion gate"
                            }
                        }
                    }
                    val requestBytes = "extra\t$packageName".toByteArray(StandardCharsets.UTF_8)
                    val requestBuffer =
                        ByteBuffer.allocateDirect(requestBytes.size).put(requestBytes)
                    val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val jobId =
                        NativeRuntime.nativeQueuePackageJob(
                            handle,
                            if (operation == "remove") {
                                NativeRuntime.JOB_OPERATION_REMOVE
                            } else {
                                NativeRuntime.JOB_OPERATION_INSTALL
                            },
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
                                if (failure == "mutation" || failure == "refresh-failed") 4 else 0,
                                if (failure == "mutation" || failure == "refresh-failed") 97 else 0,
                                failureMessage(operation, failure ?: "network"),
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

    private fun seedPackageCache(
        context: Context,
        entries: Int,
    ) {
        val cache = File(context.filesDir, "arch-root/var/cache/pacman/pkg")
        check(cache.isDirectory) { "package cache directory is unavailable" }
        File(cache, "fixture-1.0-1-any.pkg.tar.zst").writeBytes(ByteArray(2048) { 1 })
        File(cache, "fixture-1.0-1-any.pkg.tar.zst.sig").writeBytes(ByteArray(1024) { 2 })
        File(cache, "dependency-1.0-1-any.pkg.tar.zst.part").writeBytes(ByteArray(512) { 3 })
        val payload = ByteArray(1024) { 4 }
        for (index in DEFAULT_CACHE_ENTRIES until entries) {
            File(cache, "fixture-$index-1.0-1-any.pkg.tar.zst").writeBytes(payload)
        }
    }

    private fun failureMessage(
        operation: String,
        failure: String,
    ): String {
        val error =
            when (failure) {
                "network" -> UnknownHostException("fixture.invalid")
                "storage" -> IOException("No space left on device")
                "trust" -> SecurityException("invalid package signature")
                "changed" -> SecurityException("Target version changed; open Details again")
                "catalog" -> IllegalStateException("invalid package repository catalog")
                else -> IllegalStateException("pacman exited with status 1")
            }
        val mutationStarted = failure == "mutation" || failure == "refresh-failed"
        val installedStateRefreshed = failure != "refresh-failed"
        return if (operation == "remove") {
            PackageFailureDiagnostics.removal(
                error,
                mutationStarted,
                installedStateRefreshed,
            )
        } else {
            PackageFailureDiagnostics.install(
                error,
                mutationStarted,
                installedStateRefreshed,
            )
        }
    }

    private companion object {
        private const val TAG = "ArchphenePackageJobProbe"
        private const val ACTION_SEED = "org.archphene.app.debug.action.SEED_PACKAGE_JOB"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_OPERATION = "operation"
        private const val EXTRA_FAILURE = "failure"
        private const val EXTRA_CACHE_FIXTURE = "cache-fixture"
        private const val EXTRA_CACHE_ENTRIES = "cache-entries"
        private const val EXTRA_CACHE_HOLD_MILLIS = "cache-hold-ms"
        private const val DEFAULT_CACHE_ENTRIES = 3
        private const val MAX_CACHE_ENTRIES = 4096
        private const val MAX_CACHE_HOLD_MILLIS = 5_000
        private const val TEST_PREFERENCES = "package_job_test"
        private const val TEST_CACHE_HOLD_MILLIS = "cache_hold_ms"
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
        private val FAILURE_CLASSES =
            setOf(
                "network",
                "storage",
                "trust",
                "changed",
                "catalog",
                "mutation",
                "refresh-failed",
                "generic",
            )
    }
}
