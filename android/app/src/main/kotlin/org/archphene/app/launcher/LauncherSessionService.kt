package org.archphene.app.launcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
        @Volatile var active = true
        var compositor: NativeLauncherCompositor? = null
        var compositorSocket: File? = null
        var linuxHandle = 0L
        var nextProcessStatusMillis = 0L
        var pumpStarted = false
        var clientLogged = false
        var frameLogged = false
        var inputLogged = false
        var clipboardLogged = false
        val inputRecords = IntArray(MAX_INPUT_RECORDS * INPUT_FIELDS)
        val inputBuffer =
            ByteBuffer
                .allocateDirect(MAX_INPUT_RECORDS * INPUT_FIELDS * Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        var inputCount = 0
        var inputPosted = false
        var inputDrain: Runnable? = null
        var androidClipboard: ByteArray? = null
        val clipboardReadBuffer = ByteBuffer.allocateDirect(MAX_CLIPBOARD_BYTES)
        val clipboardWriteBuffer = ByteBuffer.allocateDirect(MAX_CLIPBOARD_BYTES)
        var linuxCopyInFlight = false
        var androidPasteInFlight = false
        var clipboardRevision = 0
        val imeBuffer = ByteBuffer.allocateDirect(MAX_IME_BYTES)
        val imeEncoder =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val imeDecoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val imeOperations = IntArray(MAX_IME_COMMANDS)
        val imeTexts = arrayOfNulls<String>(MAX_IME_COMMANDS)
        val imeA = IntArray(MAX_IME_COMMANDS)
        val imeB = IntArray(MAX_IME_COMMANDS)
        var imeHead = 0
        var imeSize = 0
        var imePosted = false
        var imeDrain: Runnable? = null
        var lastImeChangeSerial = Int.MIN_VALUE
        var imeLogged = false
    }

    private val sessions = HashMap<Int, Session>(MAX_SESSIONS)
    private val nextSessionId = AtomicInteger(1)
    private val sessionBinder = SessionBinder()
    private lateinit var surfaceThread: HandlerThread
    private lateinit var surfaceHandler: Handler
    private lateinit var clipboardThread: HandlerThread
    private lateinit var clipboardHandler: Handler
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
        clipboardThread = HandlerThread("ArchpheneLauncherClipboard").apply { start() }
        clipboardHandler = Handler(clipboardThread.looper)
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
        surfaceHandler.post {
            Log.i(TAG, "Launcher compositor thread drained")
            surfaceThread.quitSafely()
        }
        clipboardHandler.post {
            Log.i(TAG, "Launcher clipboard thread drained")
            clipboardThread.quitSafely()
        }
        super.onDestroy()
    }

    @Synchronized
    private fun clearSessions() {
        for (session in sessions.values) {
            session.clientToken.unlinkToDeath(sessionBinder, 0)
            session.active = false
            releaseSessionResources(session, session.surface, closeCompositor = true)
            session.surface = null
        }
        sessions.clear()
    }

    @Synchronized
    private fun removeSession(sessionId: Int) {
        val session = sessions.remove(sessionId) ?: return
        session.clientToken.unlinkToDeath(sessionBinder, 0)
        session.active = false
        releaseSessionResources(session, session.surface, closeCompositor = true)
        session.surface = null
    }

    private fun releaseSessionResources(
        session: Session,
        surface: Surface?,
        closeCompositor: Boolean,
    ) {
        synchronized(session) {
            session.imeTexts.fill(null)
            session.imeHead = 0
            session.imeSize = 0
            session.imePosted = false
        }
        val runtime = runtimeBinder
        surfaceHandler.post {
            Log.i(
                TAG,
                "Releasing launcher resources session=${session.id} close=$closeCompositor",
            )
            if (closeCompositor) {
                val linuxHandle = session.linuxHandle
                session.linuxHandle = 0L
                if (linuxHandle != 0L && runtime?.closeLauncherProcess(linuxHandle) != true) {
                    Log.w(TAG, "Could not close Linux process session=${session.id}")
                }
                session.compositor?.close()
                session.compositor = null
                val socket = session.compositorSocket
                session.compositorSocket = null
                if (socket != null && socket.exists() && !socket.delete()) {
                    Log.w(TAG, "Could not remove compositor socket session=${session.id}")
                }
                session.pumpStarted = false
            } else {
                session.compositor?.setHostActive(false)
                session.compositor?.setClipboardActive(false)
                session.compositor?.detach()
            }
            surface?.release()
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
                TRANSACTION_INPUT -> {
                    transactInput(data, reply)
                    true
                }
                TRANSACTION_CLIPBOARD -> {
                    transactClipboard(data, reply)
                    true
                }
                TRANSACTION_IME -> {
                    transactIme(data, reply)
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
                    val densityDpi =
                        if (data.dataAvail() >= Int.SIZE_BYTES) {
                            data.readInt()
                        } else {
                            DEFAULT_DENSITY_DPI
                        }
                    val fontScaleMillis =
                        if (data.dataAvail() >= Int.SIZE_BYTES) {
                            data.readInt()
                        } else {
                            DEFAULT_FONT_SCALE_MILLIS
                        }
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        width !in 1..MAX_SURFACE_DIMENSION ||
                        height !in 1..MAX_SURFACE_DIMENSION ||
                        width.toLong() * height > MAX_SURFACE_PIXELS ||
                        densityDpi !in MIN_DENSITY_DPI..MAX_DENSITY_DPI ||
                        fontScaleMillis !in MIN_FONT_SCALE_MILLIS..MAX_FONT_SCALE_MILLIS ||
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
                        densityDpi,
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

        private fun transactInput(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val count = data.readInt()
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        count !in 1..MAX_INPUT_RECORDS ||
                        data.dataAvail() != count * INPUT_FIELDS * Int.SIZE_BYTES
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    submitInput(Binder.getCallingUid(), sessionId, count, data)
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher input", error)
                    RESULT_INVALID
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactClipboard(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    if (data.dataAvail() > MAX_CLIPBOARD_PARCEL_BYTES) {
                        return@runCatching RESULT_INVALID
                    }
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val present = data.readInt()
                    val text = if (present == 1) data.readString() else null
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        present !in 0..1 ||
                        (present == 1 && (text == null || text.length > MAX_CLIPBOARD_UTF16)) ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    submitAndroidClipboard(
                        Binder.getCallingUid(),
                        sessionId,
                        text,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher clipboard", error)
                    RESULT_INVALID
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactIme(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    if (data.dataAvail() > MAX_IME_PARCEL_BYTES) {
                        return@runCatching RESULT_INVALID
                    }
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val operation = data.readInt()
                    val text =
                        if (operation == IME_COMMIT || operation == IME_PREEDIT) {
                            data.readString()
                        } else {
                            null
                        }
                    val a = data.readInt()
                    val b = data.readInt()
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        operation !in IME_COMMIT..IME_EDITOR_ACTION ||
                        ((operation == IME_COMMIT || operation == IME_PREEDIT) &&
                            (text == null ||
                                text.length > MAX_IME_UTF16 ||
                                !hasWellFormedUtf16(text))) ||
                        (operation == IME_COMMIT && (a != 0 || b != 0)) ||
                        (operation == IME_PREEDIT &&
                            (a !in 0..MAX_IME_BYTES ||
                                b !in 0..MAX_IME_BYTES ||
                                utf8OffsetToUtf16(checkNotNull(text), a) < 0 ||
                                utf8OffsetToUtf16(checkNotNull(text), b) < 0)) ||
                        (operation == IME_DELETE &&
                            (a !in 0..MAX_IME_BYTES ||
                                b !in 0..MAX_IME_BYTES ||
                                a + b > MAX_IME_BYTES)) ||
                        (operation == IME_EDITOR_ACTION &&
                            (a !in 0..MAX_IME_ACTION || b != 0)) ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    submitIme(
                        Binder.getCallingUid(),
                        sessionId,
                        operation,
                        text,
                        a,
                        b,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher IME command", error)
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
        val existing =
            sessions.values.firstOrNull { session ->
                session.uid == callingUid && session.clientToken === clientToken
            }
        if (existing != null) {
            return OpenResult(RESULT_OK, existing.id, authorization)
        }
        if (sessions.size >= MAX_SESSIONS) {
            return OpenResult(RESULT_BUSY, 0, null)
        }
        val sessionId = nextSessionId.getAndUpdate { value -> if (value == Int.MAX_VALUE) 1 else value + 1 }
        if (sessionId <= 0 || sessions.containsKey(sessionId)) {
            return OpenResult(RESULT_BUSY, 0, null)
        }
        val session = Session(sessionId, callingUid, identity, clientToken, authorization)
        session.inputDrain = Runnable { drainInput(session) }
        session.imeDrain = Runnable { drainIme(session) }
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
        densityDpi: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val previous = session.surface
        session.surface = surface
        surfaceHandler.post {
            val current =
                synchronized(this) {
                    session.active && sessions[sessionId] === session && session.surface === surface
                }
            if (current) {
                session.compositor?.detach()
                previous?.release()
                attachCompositor(session, surface, width, height, densityDpi)
            } else {
                previous?.release()
            }
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
        releaseSessionResources(session, surface, closeCompositor = false)
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

    @Synchronized
    private fun submitInput(
        callingUid: Int,
        sessionId: Int,
        count: Int,
        data: Parcel,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        synchronized(session) {
            if (count > MAX_INPUT_RECORDS - session.inputCount) {
                return RESULT_BUSY
            }
            var destination = session.inputCount * INPUT_FIELDS
            repeat(count) {
                val start = destination
                repeat(INPUT_FIELDS) {
                    session.inputRecords[destination++] = data.readInt()
                }
                if (
                    !validInputRecord(
                        session.inputRecords[start],
                        session.inputRecords[start + 1],
                        session.inputRecords[start + 2],
                        session.inputRecords[start + 3],
                        session.inputRecords[start + 4],
                        session.inputRecords[start + 5],
                    )
                ) {
                    return RESULT_INVALID
                }
            }
            session.inputCount += count
            if (!session.inputPosted) {
                session.inputPosted = true
                surfaceHandler.post(checkNotNull(session.inputDrain))
            }
        }
        return RESULT_OK
    }

    @Synchronized
    private fun submitAndroidClipboard(
        callingUid: Int,
        sessionId: Int,
        text: String?,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.surface == null) {
            return RESULT_NOT_READY
        }
        val bytes = text?.toByteArray(StandardCharsets.UTF_8)
        if (bytes != null && bytes.size > MAX_CLIPBOARD_BYTES) {
            return RESULT_INVALID
        }
        val previous = session.androidClipboard
        if (
            (previous == null && bytes == null) ||
            (previous != null && bytes != null && previous.contentEquals(bytes))
        ) {
            return RESULT_OK
        }
        session.androidClipboard = bytes
        if (!session.clipboardLogged) {
            session.clipboardLogged = true
            Log.i(
                TAG,
                "Accepted first bounded Android clipboard session=$sessionId " +
                    "present=${bytes != null} bytes=${bytes?.size ?: 0}",
            )
        }
        surfaceHandler.post {
            if (!session.active || session.surface == null) {
                return@post
            }
            val compositor = session.compositor ?: return@post
            session.clipboardRevision = session.clipboardRevision.inc().coerceAtLeast(1)
            if (bytes == null) {
                compositor.clearAndroidClipboard()
            } else {
                compositor.offerAndroidClipboardText()
            }
        }
        return RESULT_OK
    }

    @Synchronized
    private fun submitIme(
        callingUid: Int,
        sessionId: Int,
        operation: Int,
        text: String?,
        a: Int,
        b: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.surface == null || session.compositor == null) {
            return RESULT_NOT_READY
        }
        synchronized(session) {
            if (session.imeSize >= MAX_IME_COMMANDS) {
                return RESULT_BUSY
            }
            val index = (session.imeHead + session.imeSize) % MAX_IME_COMMANDS
            session.imeOperations[index] = operation
            session.imeTexts[index] = text
            session.imeA[index] = a
            session.imeB[index] = b
            session.imeSize++
            if (!session.imePosted) {
                session.imePosted = true
                surfaceHandler.post(checkNotNull(session.imeDrain))
            }
        }
        return RESULT_OK
    }

    private fun drainIme(session: Session) {
        while (session.active) {
            var operation = 0
            var text: String? = null
            var a = 0
            var b = 0
            synchronized(session) {
                if (session.imeSize == 0) {
                    session.imePosted = false
                    return
                }
                val index = session.imeHead
                operation = session.imeOperations[index]
                text = session.imeTexts[index]
                a = session.imeA[index]
                b = session.imeB[index]
                session.imeTexts[index] = null
                session.imeHead = (index + 1) % MAX_IME_COMMANDS
                session.imeSize--
            }
            val compositor = session.compositor ?: return
            val result =
                when (operation) {
                    IME_COMMIT,
                    IME_PREEDIT,
                    -> {
                        val length = encodeImeText(session, checkNotNull(text))
                        if (length < 0) {
                            -2
                        } else {
                            compositor.submitImeText(
                                operation,
                                session.imeBuffer,
                                length,
                                a,
                                b,
                            )
                        }
                    }
                    IME_DELETE -> compositor.deleteImeSurrounding(a, b)
                    IME_EDITOR_ACTION ->
                        compositor.submitImeEditorAction(
                            a,
                            SystemClock.uptimeMillis().toInt(),
                        )
                    else -> RESULT_INVALID
                }
            if (!session.imeLogged && result >= 0) {
                session.imeLogged = true
                Log.i(
                    TAG,
                    "Delivered first bounded IME command session=${session.id} " +
                        "operation=$operation",
                )
            }
            if (result <= 0) {
                Log.w(
                    TAG,
                    "Native compositor rejected IME command session=${session.id} " +
                        "operation=$operation result=$result",
                )
            }
        }
    }

    private fun encodeImeText(
        session: Session,
        text: String,
    ): Int {
        session.imeBuffer.clear()
        session.imeEncoder.reset()
        val encoded = session.imeEncoder.encode(CharBuffer.wrap(text), session.imeBuffer, true)
        if (encoded.isError || encoded.isOverflow) {
            return -1
        }
        val flushed = session.imeEncoder.flush(session.imeBuffer)
        if (flushed.isError || flushed.isOverflow) {
            return -1
        }
        val length = session.imeBuffer.position()
        session.imeBuffer.position(0)
        return length
    }

    private fun validInputRecord(
        kind: Int,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
    ): Boolean =
        when (kind) {
            INPUT_TOUCH_DOWN,
            INPUT_TOUCH_MOTION,
            -> {
                a in 0 until MAX_TOUCHES &&
                    b in MIN_INPUT_COORDINATE..MAX_INPUT_COORDINATE &&
                    c in MIN_INPUT_COORDINATE..MAX_INPUT_COORDINATE &&
                    e == 0
            }
            INPUT_TOUCH_UP -> a in 0 until MAX_TOUCHES && c == 0 && d == 0 && e == 0
            INPUT_TOUCH_CANCEL -> a == 0 && b == 0 && c == 0 && d == 0 && e == 0
            INPUT_KEY -> {
                a in 1..MAX_ANDROID_KEY_CODE &&
                    b in KEY_RELEASED..KEY_REPEATED &&
                    d >= 0 &&
                    e == 0
            }
            INPUT_POINTER_MOTION -> {
                a in MIN_INPUT_COORDINATE..MAX_INPUT_COORDINATE &&
                    b in MIN_INPUT_COORDINATE..MAX_INPUT_COORDINATE &&
                    d == 0 &&
                    e == 0
            }
            INPUT_POINTER_BUTTON_LEGACY -> a in 0..1 && c == 0 && d == 0 && e == 0
            INPUT_POINTER_BUTTON -> {
                a in 1..MAX_POINTER_BUTTON &&
                    a and (a - 1) == 0 &&
                    b in 0..1 &&
                    d == 0 &&
                    e == 0
            }
            INPUT_POINTER_AXIS -> {
                (a != 0 || b != 0) &&
                    a in -MAX_AXIS_FIXED..MAX_AXIS_FIXED &&
                    b in -MAX_AXIS_FIXED..MAX_AXIS_FIXED &&
                    d == 0 &&
                    e == 0
            }
            INPUT_HOST_ACTIVE -> a in 0..1 && c == 0 && d == 0 && e == 0
            else -> false
        }

    private fun drainInput(session: Session) {
        val count =
            synchronized(session) {
                val count = session.inputCount
                session.inputBuffer.clear()
                for (index in 0 until count * INPUT_FIELDS) {
                    session.inputBuffer.putInt(session.inputRecords[index])
                }
                session.inputBuffer.position(0)
                session.inputCount = 0
                session.inputPosted = false
                count
            }
        if (count == 0 || !session.active) {
            return
        }
        val result = session.compositor?.submitInput(session.inputBuffer, count) ?: return
        if (!session.inputLogged) {
            session.inputLogged = true
            Log.i(
                TAG,
                "Delivered first bounded input batch session=${session.id} records=$count result=$result",
            )
        }
        if (result < 0) {
            Log.w(TAG, "Native compositor rejected input session=${session.id} result=$result")
        }
    }

    private fun attachCompositor(
        session: Session,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        try {
            val compositor =
                session.compositor
                    ?: File(
                            filesDir,
                            "arch-root/run/launcher-${session.id}.sock",
                        ).let { socket ->
                            NativeLauncherCompositor(
                                socket.absolutePath,
                                width,
                                height,
                                densityDpi,
                            ).also {
                                session.compositor = it
                                session.compositorSocket = socket
                                session.lastImeChangeSerial = Int.MIN_VALUE
                            }
                        }
            check(compositor.attach(surface, width, height, densityDpi)) {
                "ANativeWindow attachment failed"
            }
            compositor.setHostActive(true)
            compositor.setClipboardActive(true)
            session.clipboardRevision = session.clipboardRevision.inc().coerceAtLeast(1)
            val clipboard = synchronized(this) { session.androidClipboard }
            if (clipboard == null) {
                compositor.clearAndroidClipboard()
            } else {
                compositor.offerAndroidClipboardText()
            }
            if (!session.pumpStarted) {
                session.pumpStarted = true
                surfaceHandler.post(CompositorPump(session))
            }
            Log.i(TAG, "Native Wayland compositor ready for session=${session.id}")
            notifyStatus(
                session,
                if (session.frameLogged) STATUS_RUNNING else STATUS_STARTING,
                if (session.frameLogged) {
                    session.authorization.label
                } else {
                    "Starting ${session.authorization.label}…"
                },
            )
            startLinuxProcess(session, surface)
        } catch (error: Exception) {
            session.compositor?.close()
            session.compositor = null
            session.compositorSocket?.delete()
            session.compositorSocket = null
            session.pumpStarted = false
            notifyStatus(
                session,
                STATUS_STOPPED,
                "Could not start ${session.authorization.label}.",
            )
            Log.e(TAG, "Could not start native compositor session=${session.id}", error)
        }
    }

    private fun startLinuxProcess(
        session: Session,
        attachedSurface: Surface,
    ) {
        if (session.linuxHandle != 0L) {
            return
        }
        val runtime = runtimeBinder
        val socket = session.compositorSocket
        if (runtime == null || socket == null) {
            Log.e(TAG, "Shared runtime unavailable for Linux session=${session.id}")
            return
        }
        val linuxHandle =
            runtime.openLauncherProcess(
                session.identity.androidPackage,
                session.identity.descriptorIdHex,
                session.identity.generation,
                socket.name,
            )
        if (linuxHandle == 0L) {
            Log.e(TAG, "Could not start descriptor process session=${session.id}")
            stopCompositorForStatus(
                session,
                attachedSurface,
                "Could not start ${session.authorization.label}.",
            )
            return
        }
        session.linuxHandle = linuxHandle
        session.nextProcessStatusMillis =
            SystemClock.uptimeMillis() + PROCESS_STATUS_DELAY_MILLIS
        Log.i(TAG, "Started manager-owned Linux process session=${session.id}")
    }

    private inner class CompositorPump(
        private val session: Session,
    ) : Runnable {
        override fun run() {
            if (!session.active) {
                session.pumpStarted = false
                return
            }
            val compositor = session.compositor
            if (compositor == null) {
                session.pumpStarted = false
                return
            }
            val result = compositor.dispatchAndPresent(SystemClock.uptimeMillis().toInt())
            if (result < 0) {
                Log.e(TAG, "Native compositor dispatch failed session=${session.id} result=$result")
                val surface = session.surface
                if (surface != null) {
                    stopCompositorForStatus(
                        session,
                        surface,
                        "${session.authorization.label} stopped.",
                    )
                } else {
                    stopCompositor(session)
                }
                return
            }
            val clientConnected =
                result and NativeLauncherCompositor.FLAG_CLIENT_CONNECTED != 0
            if (clientConnected && !session.clientLogged) {
                session.clientLogged = true
                Log.i(TAG, "Linux Wayland client connected session=${session.id}")
            }
            if (
                result and NativeLauncherCompositor.FLAG_FRAME_PRESENTED != 0 &&
                !session.frameLogged
            ) {
                session.frameLogged = true
                notifyStatus(session, STATUS_RUNNING, session.authorization.label)
                Log.i(
                    TAG,
                    "Presented first Linux frame session=${session.id} " +
                        "presentation=" +
                        (0..9).joinToString(",") {
                            compositor.presentationComponent(it).toString()
                        },
                )
            }
            pumpClipboardTransfers(session, compositor)
            pumpImeState(session, compositor)
            pollLinuxProcess(session)
            surfaceHandler.postDelayed(
                this,
                if (clientConnected) COMPOSITOR_ACTIVE_DELAY_MILLIS else COMPOSITOR_IDLE_DELAY_MILLIS,
            )
        }
    }

    private fun pumpClipboardTransfers(
        session: Session,
        compositor: NativeLauncherCompositor,
    ) {
        if (compositor.takeLinuxClipboardClear()) {
            val revision = session.clipboardRevision.inc().coerceAtLeast(1)
            session.clipboardRevision = revision
            publishLinuxClipboard(session, compositor, revision, null)
        }
        if (!session.linuxCopyInFlight) {
            val descriptor = compositor.takeLinuxCopyFd()
            if (descriptor >= 0) {
                val revision = session.clipboardRevision.inc().coerceAtLeast(1)
                session.clipboardRevision = revision
                session.linuxCopyInFlight = true
                clipboardHandler.post {
                    session.clipboardReadBuffer.clear()
                    val length =
                        compositor.readClipboardFd(
                            descriptor,
                            session.clipboardReadBuffer,
                            MAX_CLIPBOARD_BYTES,
                            CLIPBOARD_IO_TIMEOUT_MILLIS,
                        )
                    val text =
                        if (length >= 0) {
                            session.clipboardReadBuffer.position(0)
                            ByteArray(length)
                                .also { bytes -> session.clipboardReadBuffer.get(bytes) }
                                .let(::decodeClipboardText)
                        } else {
                            null
                        }
                    surfaceHandler.post {
                        session.linuxCopyInFlight = false
                        if (length < 0) {
                            Log.w(
                                TAG,
                                "Linux clipboard read failed session=${session.id} result=$length",
                            )
                        } else if (text == null) {
                            Log.w(TAG, "Rejected invalid Linux clipboard text session=${session.id}")
                        } else {
                            publishLinuxClipboard(session, compositor, revision, text)
                        }
                    }
                }
            }
        }

        if (!session.androidPasteInFlight) {
            val clipboard = synchronized(this) { session.androidClipboard }
            if (clipboard != null) {
                val descriptor = compositor.takeAndroidPasteFd()
                if (descriptor >= 0) {
                    session.clipboardWriteBuffer.clear()
                    session.clipboardWriteBuffer.put(clipboard)
                    session.clipboardWriteBuffer.position(0)
                    session.androidPasteInFlight = true
                    clipboardHandler.post {
                        val result =
                            compositor.writeClipboardFd(
                                descriptor,
                                session.clipboardWriteBuffer,
                                clipboard.size,
                                CLIPBOARD_IO_TIMEOUT_MILLIS,
                            )
                        surfaceHandler.post {
                            session.androidPasteInFlight = false
                            if (result != clipboard.size) {
                                Log.w(
                                    TAG,
                                    "Android clipboard write failed session=${session.id} " +
                                        "result=$result",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pumpImeState(
        session: Session,
        compositor: NativeLauncherCompositor,
    ) {
        val changeSerial = compositor.imeChangeSerial()
        if (changeSerial == session.lastImeChangeSerial) {
            return
        }
        session.lastImeChangeSerial = changeSerial
        if (!compositor.imeActive()) {
            notifyImeState(session, changeSerial, null, 0, 0, 0, 0)
            return
        }
        val byteLength = compositor.imeSurroundingTextLength()
        val text =
            if (byteLength < 0) {
                ""
            } else {
                if (byteLength > MAX_IME_SURROUNDING_BYTES) {
                    Log.w(
                        TAG,
                        "Rejected oversized IME surrounding text session=${session.id} " +
                            "bytes=$byteLength",
                    )
                    notifyImeState(session, changeSerial, "", 0, 0, 0, 0)
                    return
                }
                session.imeBuffer.clear()
                val copied =
                    compositor.copyImeSurroundingText(
                        session.imeBuffer,
                        MAX_IME_SURROUNDING_BYTES,
                    )
                if (copied != byteLength) {
                    Log.w(
                        TAG,
                        "Could not snapshot IME surrounding text session=${session.id} " +
                            "expected=$byteLength copied=$copied",
                    )
                    notifyImeState(session, changeSerial, "", 0, 0, 0, 0)
                    return
                }
                session.imeBuffer.position(0)
                session.imeBuffer.limit(copied)
                session.imeDecoder.reset()
                runCatching { session.imeDecoder.decode(session.imeBuffer).toString() }
                    .getOrElse {
                        Log.w(TAG, "Rejected invalid IME surrounding text session=${session.id}")
                        notifyImeState(session, changeSerial, "", 0, 0, 0, 0)
                        return
                    }
            }
        val cursor =
            utf8OffsetToUtf16(
                text,
                compositor.imeStateComponent(IME_COMPONENT_CURSOR),
            )
        val anchor =
            utf8OffsetToUtf16(
                text,
                compositor.imeStateComponent(IME_COMPONENT_ANCHOR),
            )
        if (cursor < 0 || anchor < 0) {
            Log.w(TAG, "Rejected invalid IME selection offsets session=${session.id}")
            notifyImeState(session, changeSerial, "", 0, 0, 0, 0)
            return
        }
        notifyImeState(
            session,
            changeSerial,
            text,
            cursor,
            anchor,
            compositor.imeStateComponent(IME_COMPONENT_HINT).coerceAtLeast(0),
            compositor.imeStateComponent(IME_COMPONENT_PURPOSE).coerceAtLeast(0),
        )
    }

    private fun decodeClipboardText(bytes: ByteArray): String? =
        runCatching {
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            text.takeIf { it.length <= MAX_CLIPBOARD_UTF16 }
        }.getOrNull()

    private fun publishLinuxClipboard(
        session: Session,
        compositor: NativeLauncherCompositor,
        revision: Int,
        text: String?,
    ) {
        val bytes = text?.toByteArray(StandardCharsets.UTF_8)
        val current =
            synchronized(this) {
                if (
                    !session.active ||
                    session.surface == null ||
                    session.compositor !== compositor ||
                    session.clipboardRevision != revision
                ) {
                    false
                } else {
                    session.androidClipboard = bytes
                    true
                }
            }
        if (current) {
            Log.i(
                TAG,
                "Accepted bounded Linux clipboard session=${session.id} " +
                    "present=${bytes != null} bytes=${bytes?.size ?: 0}",
            )
            notifyClipboard(session, text)
        }
    }

    private fun pollLinuxProcess(session: Session) {
        val now = SystemClock.uptimeMillis()
        val handle = session.linuxHandle
        if (handle == 0L || now < session.nextProcessStatusMillis) {
            return
        }
        session.nextProcessStatusMillis = now + PROCESS_STATUS_DELAY_MILLIS
        val runtime = runtimeBinder ?: return
        val exitStatus = runtime.launcherProcessExitStatus(handle) ?: return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val output = runtime.launcherProcessLog(handle).trim()
            if (output.isNotEmpty()) {
                val safeOutput =
                    output
                        .take(2048)
                        .filter { character ->
                            character == '\n' || character == '\t' || character >= ' '
                        }
                Log.d(TAG, "Linux process final output session=${session.id}: $safeOutput")
            }
        }
        runtime.closeLauncherProcess(handle)
        session.linuxHandle = 0L
        val message =
            if (exitStatus == 0) {
                "${session.authorization.label} closed."
            } else {
                "${session.authorization.label} stopped (exit $exitStatus)."
            }
        val surface = session.surface
        if (surface != null) {
            stopCompositorForStatus(
                session,
                surface,
                message,
            )
        } else {
            stopCompositor(session)
        }
        Log.i(TAG, "Linux process exited session=${session.id} status=$exitStatus")
    }

    private fun stopCompositorForStatus(
        session: Session,
        surface: Surface,
        message: String,
    ) {
        stopCompositor(session)
        val current =
            synchronized(this) {
                session.active && session.surface === surface
            }
        if (current) {
            notifyStatus(session, STATUS_STOPPED, message)
        }
    }

    private fun stopCompositor(session: Session) {
        session.compositor?.close()
        session.compositor = null
        val socket = session.compositorSocket
        session.compositorSocket = null
        if (socket != null && socket.exists() && !socket.delete()) {
            Log.w(TAG, "Could not remove stopped compositor socket session=${session.id}")
        }
        session.pumpStarted = false
    }

    private fun notifyStatus(
        session: Session,
        state: Int,
        message: String,
    ) {
        if (!session.active || state !in STATUS_STARTING..STATUS_STOPPED) {
            return
        }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(session.id)
            data.writeInt(state)
            data.writeString(message.take(256))
            session.clientToken.transact(CALLBACK_STATUS, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver launcher status session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun notifyClipboard(
        session: Session,
        text: String?,
    ) {
        if (!session.active || (text != null && text.length > MAX_CLIPBOARD_UTF16)) {
            return
        }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(session.id)
            data.writeInt(if (text == null) 0 else 1)
            if (text != null) {
                data.writeString(text)
            }
            session.clientToken.transact(CALLBACK_CLIPBOARD, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver Linux clipboard session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun notifyImeState(
        session: Session,
        revision: Int,
        text: String?,
        cursor: Int,
        anchor: Int,
        hint: Int,
        purpose: Int,
    ) {
        if (
            !session.active ||
            (text != null &&
                (text.length > MAX_IME_UTF16 ||
                    cursor !in 0..text.length ||
                    anchor !in 0..text.length))
        ) {
            return
        }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(session.id)
            data.writeInt(if (text == null) 0 else 1)
            data.writeInt(revision)
            if (text != null) {
                data.writeString(text)
                data.writeInt(cursor)
                data.writeInt(anchor)
                data.writeInt(hint)
                data.writeInt(purpose)
            }
            session.clientToken.transact(CALLBACK_IME_STATE, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver launcher IME state session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun utf8OffsetToUtf16(
        text: String,
        byteOffset: Int,
    ): Int {
        if (byteOffset < 0) {
            return if (text.isEmpty()) 0 else -1
        }
        var bytes = 0
        var utf16 = 0
        while (utf16 < text.length) {
            if (bytes == byteOffset) {
                return utf16
            }
            val codePoint = text.codePointAt(utf16)
            bytes +=
                when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
            utf16 += Character.charCount(codePoint)
            if (bytes > byteOffset) {
                return -1
            }
        }
        return if (bytes == byteOffset) utf16 else -1
    }

    private fun hasWellFormedUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (
                        index + 1 >= text.length ||
                        !Character.isLowSurrogate(text[index + 1])
                    ) {
                        return false
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index++
            }
        }
        return true
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
        private const val TRANSACTION_INPUT = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_IME = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val CALLBACK_INTERFACE = "org.archphene.launcher.IClientV1"
        private const val CALLBACK_STATUS = IBinder.FIRST_CALL_TRANSACTION
        private const val CALLBACK_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CALLBACK_IME_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val MAX_SESSIONS = 16
        private const val MAX_SURFACE_DIMENSION = 8192
        private const val MAX_SURFACE_PIXELS = 33_554_432L
        private const val DEFAULT_DENSITY_DPI = 160
        private const val MIN_DENSITY_DPI = 72
        private const val MAX_DENSITY_DPI = 1_000
        private const val DEFAULT_FONT_SCALE_MILLIS = 1_000
        private const val MIN_FONT_SCALE_MILLIS = 500
        private const val MAX_FONT_SCALE_MILLIS = 3_000
        private const val COMPOSITOR_ACTIVE_DELAY_MILLIS = 8L
        private const val COMPOSITOR_IDLE_DELAY_MILLIS = 50L
        private const val PROCESS_STATUS_DELAY_MILLIS = 500L
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private const val MAX_INPUT_RECORDS = 32
        private const val INPUT_FIELDS = 6
        private const val MAX_TOUCHES = 32
        private const val MIN_INPUT_COORDINATE = -8192
        private const val MAX_INPUT_COORDINATE = 16384
        private const val MAX_ANDROID_KEY_CODE = 512
        private const val MAX_AXIS_FIXED = 120_000
        private const val INPUT_TOUCH_DOWN = 1
        private const val INPUT_TOUCH_MOTION = 2
        private const val INPUT_TOUCH_UP = 3
        private const val INPUT_TOUCH_CANCEL = 4
        private const val INPUT_KEY = 5
        private const val INPUT_POINTER_MOTION = 6
        private const val INPUT_POINTER_BUTTON_LEGACY = 7
        private const val INPUT_POINTER_BUTTON = 8
        private const val INPUT_POINTER_AXIS = 9
        private const val INPUT_HOST_ACTIVE = 10
        private const val KEY_RELEASED = 0
        private const val KEY_REPEATED = 2
        private const val MAX_POINTER_BUTTON = 16
        private const val MAX_CLIPBOARD_UTF16 = 16_384
        private const val MAX_CLIPBOARD_BYTES = 65_536
        private const val MAX_CLIPBOARD_PARCEL_BYTES = 131_200
        private const val CLIPBOARD_IO_TIMEOUT_MILLIS = 2_000
        private const val MAX_IME_COMMANDS = 32
        private const val MAX_IME_UTF16 = 4_096
        private const val MAX_IME_BYTES = 16_384
        private const val MAX_IME_SURROUNDING_BYTES = 4_000
        private const val MAX_IME_PARCEL_BYTES = 32_896
        private const val MAX_IME_ACTION = 64
        private const val IME_COMMIT = 1
        private const val IME_PREEDIT = 2
        private const val IME_DELETE = 3
        private const val IME_EDITOR_ACTION = 4
        private const val IME_COMPONENT_CURSOR = 0
        private const val IME_COMPONENT_ANCHOR = 1
        private const val IME_COMPONENT_HINT = 2
        private const val IME_COMPONENT_PURPOSE = 3
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val RESULT_UNAUTHORIZED = 2
        private const val RESULT_BUSY = 3
        private const val RESULT_INVALID = 4
    }
}
