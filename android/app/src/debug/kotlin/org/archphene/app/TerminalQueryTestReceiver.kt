package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class TerminalQueryTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_RUN) {
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
                    check(endpoint.sharedShellRunning) { "shared shell is not running" }
                    check(endpoint.submitLinuxInput(QUERY_COMMAND)) {
                        "terminal query command was rejected"
                    }
                    Log.i(TAG, "Submitted terminal query probe")
                } catch (error: Exception) {
                    Log.e(TAG, "Terminal query probe failed", error)
                } finally {
                    if (bound) {
                        applicationContext.unbindService(connection)
                    }
                    pending.finish()
                }
            },
            "ArchpheneTerminalQueryProbe",
        ).start()
    }

    private companion object {
        private const val TAG = "ArchpheneTerminalQueryProbe"
        private const val ACTION_RUN = "org.archphene.app.debug.action.RUN_TERMINAL_QUERY"
        private const val QUERY_COMMAND =
            "read -rsn7 -p \$'\\e[c' da; " +
                "read -rsn4 -p \$'\\e[5n' dsr; " +
                "read -rsn6 -p \$'\\e[3;5H\\e[6n' cpr; " +
                "if [[ \$da == \$'\\e[?1;2c' && \$dsr == \$'\\e[0n' " +
                "&& \$cpr == \$'\\e[3;5R' ]]; then " +
                "printf \$'\\e[2J\\e[Hterminal-query-pass\\n'; else " +
                "printf \$'\\e[2J\\e[Hterminal-query-fail\\n'; fi"
    }
}
