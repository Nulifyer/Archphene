package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

internal class LauncherSessionTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_PROBE || intent.getStringExtra(EXTRA_TOKEN) != TOKEN) {
            Log.e(TAG, "Rejected launcher-session probe")
            return
        }
        val pending = goAsync()
        val application = context.applicationContext
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    try {
                        check(open(service, PROTOCOL_VERSION) == RESULT_UNAUTHORIZED) {
                            "manager UID was not rejected"
                        }
                        check(open(service, PROTOCOL_VERSION + 1) == RESULT_INVALID) {
                            "invalid protocol version was not rejected"
                        }
                        Log.i(TAG, "Untrusted Binder caller and malformed version rejected")
                    } catch (error: Exception) {
                        Log.e(TAG, "Launcher-session rejection probe failed", error)
                    } finally {
                        application.unbindService(this)
                        pending.finish()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.e(TAG, "Launcher-session service disconnected during probe")
                    pending.finish()
                }
            }
        val bound =
            application.bindService(
                Intent(BIND_ACTION).setPackage(application.packageName),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        if (!bound) {
            Log.e(TAG, "Could not bind launcher-session probe")
            pending.finish()
        }
    }

    private fun open(
        service: IBinder,
        version: Int,
    ): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(version)
            data.writeStrongBinder(Binder())
            check(service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.readException()
            val result = reply.readInt()
            reply.readInt()
            reply.readString()
            reply.readInt()
            result
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        private const val TAG = "ArchpheneLauncherSessionProbe"
        private const val ACTION_PROBE =
            "org.archphene.app.debug.action.PROBE_LAUNCHER_SESSION"
        private const val EXTRA_TOKEN = "token"
        private const val TOKEN = "launcher-session-gate"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV1"
        private const val PROTOCOL_VERSION = 1
        private const val RESULT_UNAUTHORIZED = 2
        private const val RESULT_INVALID = 4
    }
}
