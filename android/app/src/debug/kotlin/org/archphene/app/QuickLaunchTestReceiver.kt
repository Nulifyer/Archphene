package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService

internal class QuickLaunchTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_LAUNCH) return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            packageName == null ||
            !PACKAGE.matches(packageName)
        ) {
            Log.e(TAG, "Rejected invalid Quick launch probe")
            return
        }
        val application = context.applicationContext
        val pending = goAsync()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    val binder = service as? ArchpheneRuntimeService.LocalBinder
                    Thread(
                        {
                            try {
                                val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
                                var candidate = binder?.quickLaunchCandidate(packageName)
                                while (candidate == null && SystemClock.uptimeMillis() < deadline) {
                                    SystemClock.sleep(POLL_MILLIS)
                                    candidate = binder?.quickLaunchCandidate(packageName)
                                }
                                checkNotNull(candidate) { "Quick launch candidate did not become ready" }
                                Log.i(
                                    TAG,
                                    "Resolved Quick launch package=$packageName " +
                                        "android=${candidate.androidPackage} " +
                                        "descriptor=${candidate.descriptorIdHex} " +
                                        "generation=${candidate.generation} label=${candidate.label} " +
                                        "token=$token",
                                )
                            } catch (error: Exception) {
                                Log.e(TAG, "Quick launch probe failed token=$token", error)
                            } finally {
                                application.unbindService(this)
                                pending.finish()
                            }
                        },
                        "ArchpheneQuickLaunchProbe",
                    ).start()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onNullBinding(name: ComponentName) {
                    Log.e(TAG, "Quick launch probe received a null binding token=$token")
                    application.unbindService(this)
                    pending.finish()
                }
            }
        if (
            !application.bindService(
                Intent(application, ArchpheneRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            Log.e(TAG, "Could not bind Quick launch probe token=$token")
            pending.finish()
        }
    }

    private companion object {
        private const val TAG = "ArchpheneQuickLaunchProbe"
        private const val ACTION_LAUNCH = "org.archphene.app.debug.action.QUICK_LAUNCH"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val TIMEOUT_MILLIS = 60_000L
        private const val POLL_MILLIS = 250L
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
    }
}
