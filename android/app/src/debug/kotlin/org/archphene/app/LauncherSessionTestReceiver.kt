package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import android.util.Log
import org.archphene.app.launcher.LauncherSessionDebugBridge
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class LauncherSessionTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getStringExtra(EXTRA_TOKEN) != TOKEN) {
            Log.e(TAG, "Rejected launcher-session probe")
            return
        }
        if (intent.action == ACTION_INJECT_IME) {
            injectIme(intent)
            return
        }
        if (intent.action != ACTION_PROBE) {
            Log.e(TAG, "Rejected unknown launcher-session operation")
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

    private fun injectIme(intent: Intent) {
        val androidPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val encodedComposing = intent.getStringExtra(EXTRA_COMPOSING_BASE64)
        val composing =
            if (encodedComposing == null) {
                ""
            } else {
                decodeUtf8(encodedComposing) ?: return
            }
        val committed =
            intent.getStringExtra(EXTRA_COMMITTED_BASE64)?.let(::decodeUtf8)
                ?: run {
                    Log.e(TAG, "Rejected missing launcher-session IME commit")
                    return
                }
        val submit = intent.getBooleanExtra(EXTRA_SUBMIT, false)
        val result =
            LauncherSessionDebugBridge.injectIme(
                androidPackage,
                composing,
                committed,
                submit,
            )
        val message =
            "Manager session IME package=$androidPackage session=${result.sessionId} " +
                "preeditBytes=${composing.toByteArray(StandardCharsets.UTF_8).size} " +
                "commitBytes=${committed.toByteArray(StandardCharsets.UTF_8).size} " +
                "submit=$submit result=${result.reason}"
        if (result.accepted) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun decodeUtf8(encoded: String): String? {
        if (encoded.length > MAX_BASE64_CHARACTERS) {
            Log.e(TAG, "Rejected invalid launcher-session IME payload")
            return null
        }
        val bytes =
            runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                .getOrElse {
                    Log.e(TAG, "Rejected invalid launcher-session IME base64", it)
                    return null
                }
        if (bytes.size > MAX_IME_BYTES) {
            Log.e(TAG, "Rejected oversized launcher-session IME payload")
            return null
        }
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse {
                Log.e(TAG, "Rejected malformed launcher-session UTF-8", it)
                null
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
        private const val ACTION_INJECT_IME =
            "org.archphene.app.debug.action.INJECT_LAUNCHER_IME"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_COMPOSING_BASE64 = "composing_base64"
        private const val EXTRA_COMMITTED_BASE64 = "committed_base64"
        private const val EXTRA_SUBMIT = "submit"
        private const val TOKEN = "launcher-session-gate"
        private const val MAX_IME_BYTES = 16_384
        private const val MAX_BASE64_CHARACTERS = 24_000
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV1"
        private const val PROTOCOL_VERSION = 1
        private const val RESULT_UNAUTHORIZED = 2
        private const val RESULT_INVALID = 4
    }
}
