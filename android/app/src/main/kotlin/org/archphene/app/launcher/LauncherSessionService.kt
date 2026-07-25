package org.archphene.app.launcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.LauncherAuthorization

class LauncherSessionService : Service() {
    private class Session(
        val id: Int,
        val uid: Int,
        val identity: VerifiedLauncherIdentity,
        val clientToken: IBinder,
        val authorization: LauncherAuthorization,
    ) {
        var surface: Surface? = null
    }

    private val sessions = HashMap<Int, Session>(MAX_SESSIONS)
    private val nextSessionId = AtomicInteger(1)
    private val sessionBinder = SessionBinder()
    private lateinit var surfaceThread: HandlerThread
    private lateinit var surfaceHandler: Handler
    @Volatile private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var runtimeBound = false

    private val runtimeConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                runtimeBinder = service as? ArchpheneRuntimeService.LocalBinder
                Log.i(TAG, "Shared runtime connected")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runtimeBinder = null
                clearSessions()
                Log.w(TAG, "Shared runtime disconnected")
            }
        }

    override fun onCreate() {
        super.onCreate()
        surfaceThread = HandlerThread("ArchpheneLauncherSurface").apply { start() }
        surfaceHandler = Handler(surfaceThread.looper)
        runtimeBound =
            bindService(
                Intent(this, ArchpheneRuntimeService::class.java),
                runtimeConnection,
                Context.BIND_AUTO_CREATE,
            )
        if (!runtimeBound) {
            Log.e(TAG, "Could not bind the shared runtime")
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == BIND_ACTION) {
            sessionBinder
        } else {
            null
        }

    override fun onDestroy() {
        clearSessions()
        runtimeBinder = null
        if (runtimeBound) {
            unbindService(runtimeConnection)
            runtimeBound = false
        }
        surfaceThread.quitSafely()
        super.onDestroy()
    }

    @Synchronized
    private fun clearSessions() {
        for (session in sessions.values) {
            session.clientToken.unlinkToDeath(sessionBinder, 0)
            releaseSurface(session.surface)
            session.surface = null
        }
        sessions.clear()
    }

    @Synchronized
    private fun removeSession(sessionId: Int) {
        val session = sessions.remove(sessionId) ?: return
        session.clientToken.unlinkToDeath(sessionBinder, 0)
        releaseSurface(session.surface)
        session.surface = null
    }

    private fun releaseSurface(surface: Surface?) {
        if (surface != null) {
            surfaceHandler.post(surface::release)
        }
    }

    private inner class SessionBinder :
        Binder(),
        IBinder.DeathRecipient {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(INTERFACE)
                return true
            }
            if (reply == null || flags and FLAG_ONEWAY != 0) {
                return false
            }
            return when (code) {
                TRANSACTION_OPEN -> {
                    transactOpen(data, reply)
                    true
                }
                TRANSACTION_CLOSE -> {
                    transactClose(data, reply)
                    true
                }
                TRANSACTION_ATTACH_SURFACE -> {
                    transactAttachSurface(data, reply)
                    true
                }
                TRANSACTION_DETACH_SURFACE -> {
                    transactDetachSurface(data, reply)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        override fun binderDied() {
            synchronized(this@LauncherSessionService) {
                val dead =
                    sessions.values
                        .filter { session -> !session.clientToken.isBinderAlive }
                        .map(Session::id)
                for (sessionId in dead) {
                    Log.i(TAG, "Client Binder died for launcher session=$sessionId")
                    removeSession(sessionId)
                }
            }
        }

        private fun transactOpen(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val token = data.readStrongBinder()
                    if (
                        version != PROTOCOL_VERSION ||
                        token == null ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching OpenResult(RESULT_INVALID, 0, null)
                    }
                    openSession(Binder.getCallingUid(), token)
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher open", error)
                    OpenResult(RESULT_INVALID, 0, null)
                }
            reply.writeNoException()
            reply.writeInt(result.result)
            reply.writeInt(result.sessionId)
            reply.writeString(result.authorization?.label)
            reply.writeInt(if (result.authorization?.terminal == true) 1 else 0)
        }

        private fun transactClose(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    closeSession(Binder.getCallingUid(), sessionId)
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher close", error)
                    RESULT_INVALID
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactAttachSurface(
            data: Parcel,
            reply: Parcel,
        ) {
            var surface: Surface? = null
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val width = data.readInt()
                    val height = data.readInt()
                    surface = Surface.CREATOR.createFromParcel(data)
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        width !in 1..MAX_SURFACE_DIMENSION ||
                        height !in 1..MAX_SURFACE_DIMENSION ||
                        width.toLong() * height > MAX_SURFACE_PIXELS ||
                        surface?.isValid != true ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    attachSurface(
                        Binder.getCallingUid(),
                        sessionId,
                        surface!!,
                        width,
                        height,
                    ).also { attachResult ->
                        if (attachResult == RESULT_OK) {
                            surface = null
                        }
                    }
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher Surface", error)
                    RESULT_INVALID
                }
            surface?.release()
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactDetachSurface(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    detachSurface(Binder.getCallingUid(), sessionId)
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher Surface detach", error)
                    RESULT_INVALID
                }
            reply.writeNoException()
            reply.writeInt(result)
        }
    }

    private data class OpenResult(
        val result: Int,
        val sessionId: Int,
        val authorization: LauncherAuthorization?,
    )

    @Synchronized
    private fun openSession(
        callingUid: Int,
        clientToken: IBinder,
    ): OpenResult {
        val identity =
            LauncherIdentityVerifier.verify(this, callingUid)
                ?: run {
                    Log.w(TAG, "Rejected launcher UID=$callingUid before registry lookup")
                    return OpenResult(RESULT_UNAUTHORIZED, 0, null)
                }
        val runtime = runtimeBinder ?: return OpenResult(RESULT_NOT_READY, 0, null)
        if (runtime.runtimeHandle == 0L) {
            return OpenResult(RESULT_NOT_READY, 0, null)
        }
        val authorization =
            runtime.authorizeLauncher(
                identity.androidPackage,
                identity.descriptorIdHex,
                identity.generation,
            ) ?: run {
                Log.w(
                    TAG,
                    "Rejected non-current registry descriptor package=${identity.androidPackage} " +
                        "generation=${identity.generation}",
                )
                return OpenResult(RESULT_UNAUTHORIZED, 0, null)
            }
        if (sessions.size >= MAX_SESSIONS) {
            return OpenResult(RESULT_BUSY, 0, null)
        }
        val existing =
            sessions.values.firstOrNull { session ->
                session.uid == callingUid && session.clientToken === clientToken
            }
        if (existing != null) {
            return OpenResult(RESULT_OK, existing.id, authorization)
        }
        val sessionId = nextSessionId.getAndUpdate { value -> if (value == Int.MAX_VALUE) 1 else value + 1 }
        if (sessionId <= 0 || sessions.containsKey(sessionId)) {
            return OpenResult(RESULT_BUSY, 0, null)
        }
        val session = Session(sessionId, callingUid, identity, clientToken, authorization)
        try {
            clientToken.linkToDeath(sessionBinder, 0)
        } catch (_: RemoteException) {
            return OpenResult(RESULT_INVALID, 0, null)
        }
        sessions[sessionId] = session
        Log.i(
            TAG,
            "Authorized launcher package=${identity.androidPackage} " +
                "generation=${identity.generation} session=$sessionId",
        )
        return OpenResult(RESULT_OK, sessionId, authorization)
    }

    @Synchronized
    private fun attachSurface(
        callingUid: Int,
        sessionId: Int,
        surface: Surface,
        width: Int,
        height: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val previous = session.surface
        session.surface = surface
        releaseSurface(previous)
        surfaceHandler.post {
            renderAuthenticatedSurface(sessionId, surface, width, height)
        }
        Log.i(TAG, "Attached launcher Surface session=$sessionId size=${width}x$height")
        return RESULT_OK
    }

    @Synchronized
    private fun detachSurface(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val surface = session.surface
        session.surface = null
        releaseSurface(surface)
        Log.i(TAG, "Detached launcher Surface session=$sessionId")
        return RESULT_OK
    }

    @Synchronized
    private fun authorizedSession(
        callingUid: Int,
        sessionId: Int,
    ): Session? {
        val session = sessions[sessionId] ?: return null
        val current = LauncherIdentityVerifier.verify(this, callingUid) ?: return null
        val runtime = runtimeBinder ?: return null
        if (
            callingUid != session.uid ||
            current != session.identity ||
            runtime.authorizeLauncher(
                current.androidPackage,
                current.descriptorIdHex,
                current.generation,
            ) == null
        ) {
            return null
        }
        return session
    }

    private fun renderAuthenticatedSurface(
        sessionId: Int,
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        val label =
            synchronized(this) {
                sessions[sessionId]
                    ?.takeIf { session -> session.surface === surface }
                    ?.authorization
                    ?.label
            } ?: return
        if (!surface.isValid) {
            return
        }
        var canvas: android.graphics.Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            val dark =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            canvas.drawColor(if (dark) 0xff10191e.toInt() else 0xfff7f9fa.toInt())
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dark) 0xfff4f7f8.toInt() else 0xff182025.toInt()
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans", Typeface.NORMAL)
                    textSize = (minOf(width, height) / 22f).coerceIn(20f, 48f)
                }
            canvas.drawText(
                "Starting $label…",
                width / 2f,
                height / 2f - (paint.ascent() + paint.descent()) / 2f,
                paint,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not render launcher Surface session=$sessionId", error)
        } finally {
            if (canvas != null) {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    @Synchronized
    private fun closeSession(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        removeSession(sessionId)
        Log.i(TAG, "Closed launcher session=$sessionId")
        return RESULT_OK
    }

    private companion object {
        private const val TAG = "ArchpheneLauncherSession"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV1"
        private const val PROTOCOL_VERSION = 1
        private const val TRANSACTION_OPEN = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_ATTACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_DETACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val MAX_SESSIONS = 16
        private const val MAX_SURFACE_DIMENSION = 8192
        private const val MAX_SURFACE_PIXELS = 33_554_432L
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val RESULT_UNAUTHORIZED = 2
        private const val RESULT_BUSY = 3
        private const val RESULT_INVALID = 4
    }
}
