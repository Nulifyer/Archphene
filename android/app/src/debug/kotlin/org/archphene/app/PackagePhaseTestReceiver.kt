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
        if (intent.action != ACTION_START) {
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
                        binder?.startDebugPackagePhaseFixture(
                            packageName,
                            holdMillis.toLong(),
                        ) == true
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
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_HOLD_MILLIS = "hold-ms"
        private const val DEFAULT_HOLD_MILLIS = 1_500
        private const val MIN_HOLD_MILLIS = 750
        private const val MAX_HOLD_MILLIS = 5_000
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
    }
}
