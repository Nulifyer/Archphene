package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService

internal class AurReviewTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_PUBLISH && intent.action != ACTION_CLEAR) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            packageName == null ||
            !PACKAGE.matches(packageName)
        ) {
            Log.e(TAG, "Rejected invalid AUR review fixture")
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
                    val completed =
                        if (intent.action == ACTION_CLEAR) {
                            binder?.clearDebugAurReviewFixture(packageName) == true
                        } else {
                            binder?.publishDebugAurReviewFixture(packageName) == true
                        }
                    Log.i(
                        TAG,
                        if (intent.action == ACTION_CLEAR) {
                            "Cleared AUR review=$completed token=$token"
                        } else {
                            "Published AUR review=$completed token=$token"
                        },
                    )
                    applicationContext.unbindService(this)
                    pending.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onNullBinding(name: ComponentName) {
                    Log.e(TAG, "AUR review fixture received a null binding token=$token")
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
            Log.e(TAG, "Could not bind AUR review fixture token=$token")
            pending.finish()
        }
    }

    private companion object {
        private const val TAG = "ArchpheneAurReviewProbe"
        private const val ACTION_PUBLISH =
            "org.archphene.app.debug.action.PUBLISH_AUR_REVIEW"
        private const val ACTION_CLEAR =
            "org.archphene.app.debug.action.CLEAR_AUR_REVIEW"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val PACKAGE = Regex("[a-z0-9@._+\\-]{1,96}")
    }
}
