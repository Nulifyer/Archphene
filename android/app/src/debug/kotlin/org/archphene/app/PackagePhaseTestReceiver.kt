package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService

internal class PackagePhaseTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action != ACTION_START &&
            intent.action != ACTION_START_INTERRUPTED_REMOVAL &&
            intent.action != ACTION_ARM_COMPATIBILITY_REVIEW &&
            intent.action != ACTION_CANCEL_COMPATIBILITY_REVIEW &&
            intent.action != ACTION_ARM_PACKAGE_WORKER &&
            intent.action != ACTION_ARM_PRE_TRANSACTION &&
            intent.action != ACTION_ARM_POST_TRANSACTION
        ) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val holdMillis = intent.getIntExtra(EXTRA_HOLD_MILLIS, DEFAULT_HOLD_MILLIS)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            packageName == null ||
            !PACKAGE.matches(packageName) ||
            holdMillis !in MIN_HOLD_MILLIS..MAX_HOLD_MILLIS
        ) {
            Log.e(TAG, "Rejected invalid package-phase fixture")
            return
        }
        val applicationContext = context.applicationContext
        val pending = goAsync()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    val binder = service as? ArchpheneRuntimeService.LocalBinder
                    val started =
                        when (intent.action) {
                            ACTION_START_INTERRUPTED_REMOVAL ->
                                binder?.startDebugInterruptedRemovalFixture(
                                    packageName,
                                    holdMillis.toLong(),
                                ) == true
                            ACTION_ARM_COMPATIBILITY_REVIEW ->
                                binder?.armDebugPackageCompatibilityReviewHold(
                                    holdMillis.toLong(),
                                ) == true
                            ACTION_CANCEL_COMPATIBILITY_REVIEW ->
                                binder?.cancelPackageOperation() == true
                            ACTION_ARM_PACKAGE_WORKER ->
                                binder?.armDebugPackageWorkerHold(holdMillis.toLong()) == true
                            ACTION_ARM_PRE_TRANSACTION ->
                                binder?.armDebugPackagePreTransactionHold(
                                    holdMillis.toLong(),
                                ) == true
                            ACTION_ARM_POST_TRANSACTION ->
                                binder?.armDebugPackagePostTransactionHold(
                                    holdMillis.toLong(),
                                ) == true
                            else ->
                                binder?.startDebugPackagePhaseFixture(
                                    packageName,
                                    holdMillis.toLong(),
                                ) == true
                        }
                    Log.i(TAG, "Started package phases=$started token=$token")
                    applicationContext.unbindService(this)
                    pending.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onNullBinding(name: ComponentName) {
                    Log.e(TAG, "Package-phase fixture received a null binding token=$token")
                    applicationContext.unbindService(this)
                    pending.finish()
                }
            }
        if (
            !applicationContext.bindService(
                Intent(applicationContext, ArchpheneRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            Log.e(TAG, "Could not bind package-phase fixture token=$token")
            pending.finish()
        }
    }

    private companion object {
        private const val TAG = "ArchphenePackagePhaseProbe"
        private const val ACTION_START = "org.archphene.app.debug.action.START_PACKAGE_PHASES"
        private const val ACTION_START_INTERRUPTED_REMOVAL =
            "org.archphene.app.debug.action.START_INTERRUPTED_PACKAGE_REMOVAL"
        private const val ACTION_ARM_COMPATIBILITY_REVIEW =
            "org.archphene.app.debug.action.ARM_PACKAGE_COMPATIBILITY_REVIEW"
        private const val ACTION_CANCEL_COMPATIBILITY_REVIEW =
            "org.archphene.app.debug.action.CANCEL_PACKAGE_COMPATIBILITY_REVIEW"
        private const val ACTION_ARM_PACKAGE_WORKER =
            "org.archphene.app.debug.action.ARM_PACKAGE_WORKER"
        private const val ACTION_ARM_PRE_TRANSACTION =
            "org.archphene.app.debug.action.ARM_PACKAGE_PRE_TRANSACTION"
        private const val ACTION_ARM_POST_TRANSACTION =
            "org.archphene.app.debug.action.ARM_PACKAGE_POST_TRANSACTION"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_HOLD_MILLIS = "hold-ms"
        private const val DEFAULT_HOLD_MILLIS = 1_500
        private const val MIN_HOLD_MILLIS = 750
        private const val MAX_HOLD_MILLIS = 30_000
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
    }
}
