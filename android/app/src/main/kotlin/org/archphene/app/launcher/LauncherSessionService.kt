package org.archphene.app.launcher

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class LauncherSessionService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == BIND_ACTION) {
            binder
        } else {
            null
        }

    private companion object {
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
    }
}
