package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class LinuxCommandTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_RUN) {
            return
        }
        val command =
            try {
                intent.getStringExtra(EXTRA_COMMAND_BASE64)
                    ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) }
                    ?.toString(Charsets.UTF_8)
                    .orEmpty()
            } catch (_: IllegalArgumentException) {
                ""
            }
        if (
            command.isEmpty() ||
            command.length > MAX_COMMAND_CHARACTERS ||
            command.any { character -> character == '\n' || character == '\r' || character < ' ' }
        ) {
            Log.e(TAG, "Rejected malformed Linux command probe")
            return
        }
        val applicationContext = context.applicationContext
        val pending = goAsync()
        Thread(
            {
                val connected = CountDownLatch(1)
                var runtime: ArchpheneRuntimeService.LocalBinder? = null
                val connection =
                    object : ServiceConnection {
                        override fun onServiceConnected(
                            name: ComponentName?,
                            service: IBinder?,
                        ) {
                            runtime = service as? ArchpheneRuntimeService.LocalBinder
                            connected.countDown()
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            runtime = null
                        }
                    }
                var bound = false
                try {
                    bound =
                        applicationContext.bindService(
                            Intent(applicationContext, ArchpheneRuntimeService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    check(bound && connected.await(10, TimeUnit.SECONDS)) {
                        "runtime service did not bind"
                    }
                    val endpoint = checkNotNull(runtime) { "runtime binder is unavailable" }
                    check(!endpoint.sharedShellRunning) {
                        "direct Linux command probe requires an inactive shared shell"
                    }
                    check(endpoint.submitLinuxInput(command)) {
                        "direct Linux command probe was rejected"
                    }
                    Log.i(TAG, "Submitted Linux command probe")
                } catch (error: Exception) {
                    Log.e(TAG, "Linux command probe failed", error)
                } finally {
                    if (bound) {
                        applicationContext.unbindService(connection)
                    }
                    pending.finish()
                }
            },
            "ArchpheneLinuxCommandProbe",
        ).start()
    }

    private companion object {
        private const val TAG = "ArchpheneLinuxCommandProbe"
        private const val ACTION_RUN = "org.archphene.app.debug.action.RUN_LINUX_COMMAND"
        private const val EXTRA_COMMAND_BASE64 = "command_base64"
        private const val MAX_COMMAND_CHARACTERS = 256
    }
}
