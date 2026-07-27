package org.archphene.app.launcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.inputmethod.EditorInfo
import org.archphene.app.appearance.LinuxAppearanceOverrides
import org.archphene.app.appearance.LinuxAppearancePreferences
import org.archphene.app.performance.PerformanceMetrics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.archphene.app.ArchphenePreferences
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.LauncherAuthorization

class LauncherSessionService : Service() {
    private data class PendingDocumentRequest(
        val id: Int,
        val operation: Int,
        val title: String,
        val suggestedName: String,
        val mimeType: String,
        val debugPayload: ByteArray?,
        val portalCompletion: PortalDocumentCompletion?,
    )

    private class PortalDocumentCompletion {
        val latch = CountDownLatch(1)
        private val completed = AtomicBoolean(false)
        @Volatile var result = DOCUMENT_RESULT_FAILED
        @Volatile var descriptor: ParcelFileDescriptor? = null
        @Volatile var documents = emptyList<LauncherPortalOpenDocument>()

        fun complete(
            result: Int,
            descriptor: ParcelFileDescriptor?,
            documents: List<LauncherPortalOpenDocument>,
        ): Boolean {
            if (!completed.compareAndSet(false, true)) return false
            this.result = result
            this.descriptor = descriptor
            this.documents = documents
            latch.countDown()
            return true
        }
    }

    private class Session(
        val id: Int,
        val uid: Int,
        val identity: VerifiedLauncherIdentity,
        val clientToken: IBinder,
        val authorization: LauncherAuthorization,
        val appearanceOverrides: LinuxAppearanceOverrides,
    ) {
        var surface: Surface? = null
        @Volatile var active = true
        var compositor: NativeLauncherCompositor? = null
        var compositorSocket: File? = null
        var linuxHandle = 0L
        var terminalMessage: String? = null
        var portalBridge: LauncherPortalBridge? = null
        var nextProcessStatusMillis = 0L
        var pumpStarted = false
        var clientLogged = false
        var frameLogged = false
        var surfaceWidth = 0
        var surfaceHeight = 0
        var densityDpi = DEFAULT_DENSITY_DPI
        var fontScaleMillis = DEFAULT_FONT_SCALE_MILLIS
        var attachmentFramesLogged = 0
        val presentationBuffer =
            ByteBuffer
                .allocateDirect(
                    NativeLauncherCompositor.PRESENTATION_COMPONENTS * Int.SIZE_BYTES,
                ).order(ByteOrder.LITTLE_ENDIAN)
        var inputLogged = false
        var clipboardLogged = false
        var androidPasteLogged = false
        var linuxCopyLogged = false
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
        var pendingDocumentRequest: PendingDocumentRequest? = null
    }

    private val sessions = HashMap<Int, Session>(MAX_SESSIONS)
    private val nextSessionId = AtomicInteger(1)
    private val nextDocumentRequestId = AtomicInteger(1)
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
        if (stalePortalSavesRecovered.compareAndSet(false, true)) {
            runCatching {
                LauncherPortalBridge.recoverStaleRuntime(cacheDir)
                LauncherPortalBridge.recoverStaleSaves(File(filesDir, "arch-root"))
            }.onFailure { error ->
                Log.e(TAG, "Could not recover stale portal state", error)
            }
        }
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
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            LauncherSessionDebugBridge.attach(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == BIND_ACTION) {
            sessionBinder
        } else {
            null
        }

    override fun onDestroy() {
        LauncherSessionDebugBridge.detach(this)
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
            if (closeCompositor) {
                session.pendingDocumentRequest?.portalCompletion?.complete(
                    DOCUMENT_RESULT_FAILED,
                    null,
                    emptyList(),
                )
                session.pendingDocumentRequest = null
            }
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
                val compositor = session.compositor
                if (linuxHandle != 0L && compositor != null && compositor.requestClose() > 0) {
                    // Flush xdg_toplevel.close before the runtime begins its
                    // bounded graceful-exit wait. This lets desktop clients
                    // persist their normal session state instead of treating
                    // every Android task close as a crash.
                    compositor.dispatchAndPresent(SystemClock.uptimeMillis().toInt())
                }
                if (linuxHandle != 0L && runtime?.closeLauncherProcess(linuxHandle) != true) {
                    Log.w(TAG, "Could not close Linux process session=${session.id}")
                }
                session.portalBridge?.close()
                session.portalBridge = null
                compositor?.close()
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
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Launcher Binder requests must not run on Android's main thread"
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
                TRANSACTION_DOCUMENT_RESULT -> {
                    transactDocumentResult(data, reply)
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
                        fontScaleMillis,
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

        private fun transactDocumentResult(
            data: Parcel,
            reply: Parcel,
        ) {
            var descriptor: ParcelFileDescriptor? = null
            val openDocuments = ArrayList<LauncherPortalOpenDocument>(MAX_OPEN_DOCUMENTS)
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val requestId = data.readInt()
                    val documentResult = data.readInt()
                    val operation =
                        pendingDocumentOperation(
                            Binder.getCallingUid(),
                            sessionId,
                            requestId,
                        )
                    if (documentResult == DOCUMENT_RESULT_SUCCESS) {
                        if (
                            operation == DOCUMENT_OPERATION_OPEN ||
                            operation == DOCUMENT_OPERATION_OPEN_MULTIPLE
                        ) {
                            val count =
                                if (operation == DOCUMENT_OPERATION_OPEN) 1 else data.readInt()
                            check(count in 1..MAX_OPEN_DOCUMENTS)
                            repeat(count) {
                                val displayName = data.readString().orEmpty()
                                val writableValue = data.readInt()
                                check(
                                    safeDocumentName(displayName) &&
                                        writableValue in 0..1,
                                )
                                openDocuments +=
                                    LauncherPortalOpenDocument(
                                        ParcelFileDescriptor.CREATOR.createFromParcel(data),
                                        displayName,
                                        writableValue == 1,
                                    )
                            }
                        } else {
                            descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                        }
                    }
                    val openOperation =
                        operation == DOCUMENT_OPERATION_OPEN ||
                            operation == DOCUMENT_OPERATION_OPEN_MULTIPLE
                    val successShape =
                        if (openOperation) {
                            descriptor == null &&
                                openDocuments.isNotEmpty() &&
                                (operation == DOCUMENT_OPERATION_OPEN_MULTIPLE ||
                                    openDocuments.size == 1)
                        } else {
                            descriptor != null && openDocuments.isEmpty()
                        }
                    if (
                        version != PROTOCOL_VERSION ||
                        sessionId <= 0 ||
                        requestId <= 0 ||
                        operation !in
                            DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_OPEN_MULTIPLE ||
                        documentResult !in DOCUMENT_RESULT_SUCCESS..DOCUMENT_RESULT_FAILED ||
                        (documentResult == DOCUMENT_RESULT_SUCCESS) != successShape ||
                        (documentResult != DOCUMENT_RESULT_SUCCESS &&
                            (descriptor != null || openDocuments.isNotEmpty())) ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    completeDocumentRequest(
                        Binder.getCallingUid(),
                        sessionId,
                        requestId,
                        documentResult,
                        descriptor,
                        openDocuments.toList(),
                    ).also { completionResult ->
                        if (
                            completionResult == RESULT_OK &&
                            documentResult == DOCUMENT_RESULT_SUCCESS
                        ) {
                            descriptor = null
                            openDocuments.clear()
                        }
                    }
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed Android document result", error)
                    RESULT_INVALID
                }
            descriptor?.close()
            openDocuments.forEach { document -> document.descriptor.close() }
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
        if (!ArchphenePreferences.isReady()) {
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
        val session =
            Session(
                sessionId,
                callingUid,
                identity,
                clientToken,
                authorization,
                ArchphenePreferences.snapshot().appearance,
            )
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
        fontScaleMillis: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val previous = session.surface
        session.surface = surface
        session.surfaceWidth = width
        session.surfaceHeight = height
        session.densityDpi = densityDpi
        session.fontScaleMillis = fontScaleMillis
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
        var latestInputTimeMillis = 0L
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
                val eventTime =
                    inputEventTime(
                        session.inputRecords[start],
                        session.inputRecords[start + 1],
                        session.inputRecords[start + 2],
                        session.inputRecords[start + 3],
                        session.inputRecords[start + 4],
                    )
                if (eventTime != 0) {
                    latestInputTimeMillis = expandInputEventTime(eventTime)
                }
            }
            session.inputCount += count
            if (!session.inputPosted) {
                session.inputPosted = true
                surfaceHandler.post(checkNotNull(session.inputDrain))
            }
        }
        PerformanceMetrics.noteLauncherInput(latestInputTimeMillis)
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
            appendImeCommand(session, operation, text, a, b)
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

    /**
     * Debug-build device tests use the manager as the session-control boundary.
     * This deliberately does not expose test extras from generated launcher
     * Activities, and it cannot be reached from a release-build component.
     */
    @Synchronized
    internal fun debugInjectIme(
        androidPackage: String,
        composing: String?,
        committed: String?,
        submit: Boolean,
    ): LauncherSessionDebugResult {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return LauncherSessionDebugResult(false, 0, "release-build")
        }
        if (
            androidPackage.length != 53 ||
            !androidPackage.startsWith(LAUNCHER_PACKAGE_PREFIX)
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-package")
        }
        for (index in LAUNCHER_PACKAGE_PREFIX.length until androidPackage.length) {
            val character = androidPackage[index]
            if (character !in '0'..'9' && character !in 'a'..'f') {
                return LauncherSessionDebugResult(false, 0, "invalid-package")
            }
        }
        val plan = launcherSessionDebugImePlan(composing, committed, submit)
        if (plan.commandCount == 0) {
            return LauncherSessionDebugResult(false, 0, "missing-operation")
        }
        if (
            (composing != null &&
                (composing.length > MAX_IME_UTF16 || !hasWellFormedUtf16(composing))) ||
            (committed != null &&
                (committed.length > MAX_IME_UTF16 || !hasWellFormedUtf16(committed)))
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-text")
        }
        val composingBytes = composing?.let(::utf8Length) ?: 0
        val committedBytes = committed?.let(::utf8Length) ?: 0
        if (
            composingBytes !in 0..MAX_IME_BYTES ||
            committedBytes !in 0..MAX_IME_BYTES
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-text")
        }
        var session: Session? = null
        var matching = 0
        for (candidate in sessions.values) {
            if (candidate.active && candidate.identity.androidPackage == androidPackage) {
                session = candidate
                matching++
            }
        }
        if (matching != 1) {
            return LauncherSessionDebugResult(false, 0, "active-session-count-$matching")
        }
        val activeSession = checkNotNull(session)
        if (activeSession.surface == null || activeSession.compositor == null) {
            return LauncherSessionDebugResult(false, activeSession.id, "surface-not-ready")
        }
        synchronized(activeSession) {
            if (plan.commandCount > MAX_IME_COMMANDS - activeSession.imeSize) {
                return LauncherSessionDebugResult(false, activeSession.id, "queue-busy")
            }
            if (composing != null) {
                appendImeCommand(
                    activeSession,
                    IME_PREEDIT,
                    composing,
                    composingBytes,
                    composingBytes,
                )
            }
            if (committed != null) {
                appendImeCommand(activeSession, IME_COMMIT, committed, 0, 0)
            }
            if (submit) {
                appendImeCommand(
                    activeSession,
                    IME_EDITOR_ACTION,
                    null,
                    EditorInfo.IME_ACTION_DONE,
                    0,
                )
            }
            if (!activeSession.imePosted) {
                activeSession.imePosted = true
                surfaceHandler.post(checkNotNull(activeSession.imeDrain))
            }
        }
        return LauncherSessionDebugResult(true, activeSession.id, "accepted")
    }

    @Synchronized
    internal fun debugRequestDocumentSave(
        androidPackage: String,
        title: String,
        suggestedName: String,
        mimeType: String,
        payload: ByteArray,
    ): LauncherSessionDebugResult {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return LauncherSessionDebugResult(false, 0, "release-build")
        }
        if (!isValidLauncherPackage(androidPackage)) {
            return LauncherSessionDebugResult(false, 0, "invalid-package")
        }
        if (
            title.isBlank() ||
            title.length > MAX_DOCUMENT_TITLE_UTF16 ||
            suggestedName.isBlank() ||
            suggestedName.length > MAX_DOCUMENT_NAME_UTF16 ||
            suggestedName.indexOf('/') >= 0 ||
            suggestedName.indexOf('\u0000') >= 0 ||
            mimeType.isBlank() ||
            mimeType.length > MAX_DOCUMENT_MIME_UTF16 ||
            mimeType.indexOf('/') <= 0 ||
            mimeType.indexOf('\u0000') >= 0 ||
            payload.size > MAX_DEBUG_DOCUMENT_BYTES
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-document")
        }
        var session: Session? = null
        var matching = 0
        for (candidate in sessions.values) {
            if (candidate.active && candidate.identity.androidPackage == androidPackage) {
                session = candidate
                matching++
            }
        }
        if (matching != 1) {
            return LauncherSessionDebugResult(false, 0, "active-session-count-$matching")
        }
        val activeSession = checkNotNull(session)
        if (activeSession.surface == null) {
            return LauncherSessionDebugResult(false, activeSession.id, "surface-not-ready")
        }
        if (activeSession.pendingDocumentRequest != null) {
            return LauncherSessionDebugResult(false, activeSession.id, "document-request-busy")
        }
        val requestId =
            nextDocumentRequestId.getAndUpdate { value ->
                if (value == Int.MAX_VALUE) 1 else value + 1
            }
        if (requestId <= 0) {
            return LauncherSessionDebugResult(false, activeSession.id, "request-id-busy")
        }
        activeSession.pendingDocumentRequest =
            PendingDocumentRequest(
                requestId,
                DOCUMENT_OPERATION_SAVE,
                title,
                suggestedName,
                mimeType,
                payload.copyOf(),
                null,
            )
        if (!notifyDocumentRequest(activeSession, checkNotNull(activeSession.pendingDocumentRequest))) {
            activeSession.pendingDocumentRequest = null
            return LauncherSessionDebugResult(false, activeSession.id, "callback-failed")
        }
        Log.i(
            TAG,
            "Requested Android document destination session=${activeSession.id} " +
                "request=$requestId name=$suggestedName",
        )
        return LauncherSessionDebugResult(true, activeSession.id, "accepted")
    }

    private fun isValidLauncherPackage(androidPackage: String): Boolean {
        if (
            androidPackage.length != 53 ||
            !androidPackage.startsWith(LAUNCHER_PACKAGE_PREFIX)
        ) {
            return false
        }
        for (index in LAUNCHER_PACKAGE_PREFIX.length until androidPackage.length) {
            val character = androidPackage[index]
            if (character !in '0'..'9' && character !in 'a'..'f') {
                return false
            }
        }
        return true
    }

    private fun appendImeCommand(
        session: Session,
        operation: Int,
        text: String?,
        a: Int,
        b: Int,
    ) {
        val index = (session.imeHead + session.imeSize) % MAX_IME_COMMANDS
        session.imeOperations[index] = operation
        session.imeTexts[index] = text
        session.imeA[index] = a
        session.imeB[index] = b
        session.imeSize++
    }

    private fun utf8Length(text: String): Int {
        var utf16 = 0
        var bytes = 0
        while (utf16 < text.length) {
            val codePoint = text.codePointAt(utf16)
            bytes +=
                when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
            utf16 += Character.charCount(codePoint)
        }
        return bytes
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

    private fun inputEventTime(
        kind: Int,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): Int =
        when (kind) {
            INPUT_TOUCH_DOWN,
            INPUT_TOUCH_MOTION,
            -> d
            INPUT_TOUCH_UP -> b
            INPUT_KEY,
            INPUT_POINTER_MOTION,
            INPUT_POINTER_BUTTON,
            INPUT_POINTER_AXIS,
            -> c
            INPUT_POINTER_BUTTON_LEGACY -> b
            else -> 0
        }

    private fun expandInputEventTime(lowBits: Int): Long {
        val now = SystemClock.uptimeMillis()
        val low = lowBits.toLong() and UINT_MASK
        var candidate = (now and UINT_HIGH_MASK) or low
        if (candidate > now + INT_RANGE_MILLIS) {
            candidate -= UINT_RANGE_MILLIS
        } else if (candidate < now - INT_RANGE_MILLIS) {
            candidate += UINT_RANGE_MILLIS
        }
        return candidate
    }

    private fun drainInput(session: Session) {
        val count =
            synchronized(session) {
                val count = session.inputCount
                session.inputBuffer.clear()
                for (index in 0 until count * INPUT_FIELDS) {
                    session.inputBuffer.putInt(session.inputRecords[index])
                }
                PerformanceMetrics.recordCompositorKotlinCopy(
                    count * INPUT_FIELDS * Int.SIZE_BYTES,
                )
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
        val terminalMessage = session.terminalMessage
        if (terminalMessage != null) {
            notifyStatus(session, STATUS_STOPPED, terminalMessage)
            return
        }
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
                                session.appearanceOverrides.geometryPercent,
                            ).also {
                                session.compositor = it
                                session.compositorSocket = socket
                                session.lastImeChangeSerial = Int.MIN_VALUE
                            }
                        }
            check(
                compositor.attach(
                    surface,
                    width,
                    height,
                    densityDpi,
                    session.appearanceOverrides.geometryPercent,
                ),
            ) {
                "ANativeWindow attachment failed"
            }
            session.attachmentFramesLogged = 0
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
        if (session.linuxHandle != 0L || session.terminalMessage != null) {
            return
        }
        val runtime = runtimeBinder
        val socket = session.compositorSocket
        if (runtime == null || socket == null) {
            Log.e(TAG, "Shared runtime unavailable for Linux session=${session.id}")
            return
        }
        val appearance = resolvedAppearance(session)
        val portalBridge =
            session.portalBridge
                ?: runCatching {
                    LauncherPortalBridge(
                        context = this,
                        sessionId = session.id,
                        appName = session.authorization.label,
                        archRoot = File(filesDir, "arch-root"),
                        dark = appearance.dark,
                        accent = appearance.accent,
                        requestSave = { title, suggestedName, mimeType ->
                            requestPortalDocumentSave(
                                session,
                                title,
                                suggestedName,
                                mimeType,
                            )
                        },
                        requestOpen = { title, mimeType, multiple ->
                            requestPortalDocumentOpen(session, title, mimeType, multiple)
                        },
                    ).also { bridge ->
                        bridge.start()
                        session.portalBridge = bridge
                    }
                }.getOrElse { error ->
                    Log.e(TAG, "Could not start private portal session=${session.id}", error)
                    stopCompositorForStatus(
                        session,
                        attachedSurface,
                        "Could not start ${session.authorization.label}.",
                    )
                    return
                }
        val linuxHandle =
            runtime.openLauncherProcess(
                session.identity.androidPackage,
                session.identity.descriptorIdHex,
                session.identity.generation,
                socket.name,
                appearance.dark,
                appearance.fontPercent,
                appearance.controlVisualDp,
                appearance.controlTargetDp,
                appearance.accent,
                appearance.background,
                appearance.foreground,
                portalBridge.busAddress,
            )
        if (linuxHandle == 0L) {
            session.portalBridge?.close()
            session.portalBridge = null
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

    private fun requestPortalDocumentSave(
        session: Session,
        title: String,
        suggestedName: String,
        mimeType: String,
    ): LauncherPortalSaveResult {
        if (
            title.isBlank() ||
            title.length > MAX_DOCUMENT_TITLE_UTF16 ||
            suggestedName.isBlank() ||
            suggestedName.length > MAX_DOCUMENT_NAME_UTF16 ||
            suggestedName.indexOf('/') >= 0 ||
            suggestedName.indexOf('\\') >= 0 ||
            suggestedName.indexOf('\u0000') >= 0 ||
            mimeType.isBlank() ||
            mimeType.length > MAX_DOCUMENT_MIME_UTF16 ||
            mimeType.indexOf('/') <= 0 ||
            mimeType.indexOf('\u0000') >= 0
        ) {
            return LauncherPortalSaveResult(null, false)
        }
        val completion = PortalDocumentCompletion()
        val pending =
            synchronized(this) {
                if (
                    !session.active ||
                    session.surface == null ||
                    sessions[session.id] !== session ||
                    session.pendingDocumentRequest != null
                ) {
                    return LauncherPortalSaveResult(null, false)
                }
                val requestId =
                    nextDocumentRequestId.getAndUpdate { value ->
                        if (value == Int.MAX_VALUE) 1 else value + 1
                    }
                if (requestId <= 0) {
                    return LauncherPortalSaveResult(null, false)
                }
                PendingDocumentRequest(
                    requestId,
                    DOCUMENT_OPERATION_SAVE,
                    title,
                    suggestedName,
                    mimeType,
                    null,
                    completion,
                ).also { request ->
                    session.pendingDocumentRequest = request
                    if (!notifyDocumentRequest(session, request)) {
                        session.pendingDocumentRequest = null
                        return LauncherPortalSaveResult(null, false)
                    }
                }
            }
        Log.i(
            TAG,
            "Portal requested Android document destination session=${session.id} " +
                "request=${pending.id} name=$suggestedName",
        )
        if (!completion.latch.await(DOCUMENT_REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            synchronized(this) {
                if (session.pendingDocumentRequest === pending) {
                    session.pendingDocumentRequest = null
                }
            }
            return LauncherPortalSaveResult(null, false)
        }
        return LauncherPortalSaveResult(
            completion.descriptor,
            completion.result == DOCUMENT_RESULT_CANCELLED,
        )
    }

    private fun requestPortalDocumentOpen(
        session: Session,
        title: String,
        mimeType: String,
        multiple: Boolean,
    ): LauncherPortalOpenResult {
        if (
            title.isBlank() ||
            title.length > MAX_DOCUMENT_TITLE_UTF16 ||
            mimeType.isBlank() ||
            mimeType.length > MAX_DOCUMENT_MIME_UTF16 ||
            mimeType.indexOf('/') <= 0 ||
            mimeType.indexOf('\u0000') >= 0
        ) {
            return LauncherPortalOpenResult(emptyList(), false)
        }
        val operation =
            if (multiple) DOCUMENT_OPERATION_OPEN_MULTIPLE else DOCUMENT_OPERATION_OPEN
        val completion = PortalDocumentCompletion()
        val pending =
            synchronized(this) {
                if (
                    !session.active ||
                    session.surface == null ||
                    sessions[session.id] !== session ||
                    session.pendingDocumentRequest != null
                ) {
                    return LauncherPortalOpenResult(emptyList(), false)
                }
                val requestId =
                    nextDocumentRequestId.getAndUpdate { value ->
                        if (value == Int.MAX_VALUE) 1 else value + 1
                    }
                if (requestId <= 0) {
                    return LauncherPortalOpenResult(emptyList(), false)
                }
                PendingDocumentRequest(
                    requestId,
                    operation,
                    title,
                    "",
                    mimeType,
                    null,
                    completion,
                ).also { request ->
                    session.pendingDocumentRequest = request
                    if (!notifyDocumentRequest(session, request)) {
                        session.pendingDocumentRequest = null
                        return LauncherPortalOpenResult(emptyList(), false)
                    }
                }
            }
        Log.i(
            TAG,
            "Portal requested Android document source session=${session.id} " +
                "request=${pending.id} multiple=$multiple",
        )
        if (!completion.latch.await(DOCUMENT_REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            synchronized(this) {
                if (session.pendingDocumentRequest === pending) {
                    session.pendingDocumentRequest = null
                }
            }
            return LauncherPortalOpenResult(emptyList(), false)
        }
        return LauncherPortalOpenResult(
            completion.documents,
            completion.result == DOCUMENT_RESULT_CANCELLED,
        )
    }

    private data class ResolvedAppearance(
        val dark: Boolean,
        val fontPercent: Int,
        val controlVisualDp: Int,
        val controlTargetDp: Int,
        val accent: Int,
        val background: Int,
        val foreground: Int,
    )

    private fun resolvedAppearance(session: Session): ResolvedAppearance {
        val configuration = resources.configuration
        val dark =
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val shortPixels = minOf(session.surfaceWidth, session.surfaceHeight).coerceAtLeast(1)
        val densityForMinimum = shortPixels.toLong().times(160).div(432).toInt()
        val effectiveDensity = minOf(session.densityDpi, densityForMinimum).coerceIn(72, 1_000)
        val logicalShort = shortPixels.toLong().times(160).div(effectiveDensity).toInt()
        val phone = logicalShort < 600
        val visualDp =
            session.appearanceOverrides.controlVisualDp.takeIf {
                it != LinuxAppearancePreferences.AUTO
            } ?: if (phone) 20 else 18
        val targetDp = maxOf(if (phone) 32 else 28, visualDp)
        val fontPercent =
            session.appearanceOverrides.fontPercent.takeIf {
                it != LinuxAppearancePreferences.AUTO
            } ?: (
                    session.fontScaleMillis
                        .toLong()
                        .plus(5)
                        .div(10)
                ).toInt().coerceIn(100, 200)
        val material =
            if (Build.VERSION.SDK_INT >= 31) {
                intArrayOf(
                    getColor(
                        if (dark) {
                            android.R.color.system_accent1_200
                        } else {
                            android.R.color.system_accent1_600
                        },
                    ),
                    getColor(
                        if (dark) {
                            android.R.color.system_neutral1_900
                        } else {
                            android.R.color.system_neutral1_10
                        },
                    ),
                    getColor(
                        if (dark) {
                            android.R.color.system_neutral1_10
                        } else {
                            android.R.color.system_neutral1_900
                        },
                    ),
                )
            } else {
                intArrayOf(
                    if (dark) Color.rgb(86, 188, 236) else Color.rgb(23, 147, 209),
                    if (dark) Color.rgb(35, 38, 41) else Color.rgb(239, 240, 241),
                    if (dark) Color.rgb(239, 240, 241) else Color.rgb(35, 38, 41),
                )
            }
        Log.i(
            TAG,
            "Resolved launcher appearance session=${session.id} " +
                "geometry=${session.appearanceOverrides.geometryPercent.takeIf { it != 0 } ?: "auto"} " +
                "dark=$dark font=$fontPercent controls=${visualDp}dp target=${targetDp}dp",
        )
        return ResolvedAppearance(
            dark,
            fontPercent,
            visualDp,
            targetDp,
            material[0],
            material[1],
            material[2],
        )
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
            }
            if (result and NativeLauncherCompositor.FLAG_FRAME_PRESENTED != 0) {
                PerformanceMetrics.noteLauncherFrame(SystemClock.uptimeMillis())
                if (
                    session.attachmentFramesLogged < MAX_ATTACHMENT_FRAME_LOGS ||
                    result and NativeLauncherCompositor.FLAG_PRESENTATION_CHANGED != 0
                ) {
                    logPresentationSnapshot(session, compositor)
                }
            }
            pumpClipboardTransfers(session, compositor, result)
            if (result and NativeLauncherCompositor.FLAG_IME_CHANGED != 0) {
                pumpImeState(session, compositor)
            }
            pollLinuxProcess(session)
            surfaceHandler.postDelayed(
                this,
                if (clientConnected) COMPOSITOR_ACTIVE_DELAY_MILLIS else COMPOSITOR_IDLE_DELAY_MILLIS,
            )
        }
    }

    private fun Session.presentationComponent(component: Int): Int =
        presentationBuffer.getInt(component * Int.SIZE_BYTES)

    private fun logPresentationSnapshot(
        session: Session,
        compositor: NativeLauncherCompositor,
    ) {
        session.presentationBuffer.clear()
        if (!compositor.copyPresentationSnapshot(session.presentationBuffer)) {
            session.attachmentFramesLogged = MAX_ATTACHMENT_FRAME_LOGS
            Log.w(TAG, "Could not read presentation snapshot session=${session.id}")
            return
        }
        session.attachmentFramesLogged += 1
        val selectedWidth = session.presentationComponent(0)
        val selectedHeight = session.presentationComponent(1)
        val surfaceWidth = session.presentationComponent(2)
        val surfaceHeight = session.presentationComponent(3)
        val originalWidth = session.presentationComponent(4)
        val originalHeight = session.presentationComponent(5)
        val logicalWidth = session.presentationComponent(6)
        val logicalHeight = session.presentationComponent(7)
        val bufferScale = session.presentationComponent(8)
        Log.i(
            TAG,
            "Presented Linux frame session=${session.id} " +
                "attachmentFrame=${session.attachmentFramesLogged} " +
                "selected=${selectedWidth}x$selectedHeight " +
                "surface=${surfaceWidth}x$surfaceHeight " +
                "original=${originalWidth}x$originalHeight " +
                "logical=${logicalWidth}x$logicalHeight " +
                "reasons=$bufferScale," +
                "${session.presentationComponent(9)} " +
                "output=${session.presentationComponent(10)}x" +
                "${session.presentationComponent(11)} " +
                "mode=${session.presentationComponent(12)}x" +
                "${session.presentationComponent(13)} " +
                "commit=${session.presentationComponent(14)} " +
                "ack=${session.presentationComponent(15)} " +
                "serial=${session.presentationComponent(16)} " +
                "pending=${session.presentationComponent(17)} " +
                "outputEvents=${session.presentationComponent(18)} " +
                "outputBinds=${session.presentationComponent(19)} " +
                "geometry=${session.presentationComponent(20)}," +
                "${session.presentationComponent(21)} " +
                "${session.presentationComponent(22)}x" +
                "${session.presentationComponent(23)} " +
                "root=${session.presentationComponent(24)}," +
                "${session.presentationComponent(25)} " +
                "${session.presentationComponent(26)}x" +
                "${session.presentationComponent(27)} " +
                "content=${session.presentationComponent(28)}," +
                "${session.presentationComponent(29)} " +
                "${session.presentationComponent(30)}x" +
                "${session.presentationComponent(31)}",
        )
    }

    private fun pumpClipboardTransfers(
        session: Session,
        compositor: NativeLauncherCompositor,
        events: Int,
    ) {
        if (
            events and NativeLauncherCompositor.FLAG_LINUX_CLIPBOARD_CLEAR != 0 &&
            compositor.takeLinuxClipboardClear()
        ) {
            val revision = session.clipboardRevision.inc().coerceAtLeast(1)
            session.clipboardRevision = revision
            publishLinuxClipboard(session, compositor, revision, null)
        }
        if (
            !session.linuxCopyInFlight &&
            events and NativeLauncherCompositor.FLAG_LINUX_COPY_PENDING != 0
        ) {
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
                    val transferThread = Thread.currentThread().name
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
                            if (!session.linuxCopyLogged) {
                                session.linuxCopyLogged = true
                                Log.i(
                                    TAG,
                                    "Read first Linux clipboard transfer session=${session.id} " +
                                        "bytes=$length on $transferThread",
                                )
                            }
                            publishLinuxClipboard(session, compositor, revision, text)
                        }
                    }
                }
            }
        }

        if (
            !session.androidPasteInFlight &&
            events and NativeLauncherCompositor.FLAG_ANDROID_PASTE_PENDING != 0
        ) {
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
                        val transferThread = Thread.currentThread().name
                        surfaceHandler.post {
                            session.androidPasteInFlight = false
                            if (result != clipboard.size) {
                                Log.w(
                                    TAG,
                                    "Android clipboard write failed session=${session.id} " +
                                    "result=$result",
                                )
                            } else if (!session.androidPasteLogged) {
                                session.androidPasteLogged = true
                                Log.i(
                                    TAG,
                                    "Wrote first Android clipboard transfer session=${session.id} " +
                                        "bytes=$result on $transferThread",
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
                        .filter { character ->
                            character == '\n' || character == '\t' || character >= ' '
                        }
                safeOutput.chunked(PROCESS_LOGCAT_CHUNK_LENGTH)
                    .forEachIndexed { index, chunk ->
                        Log.d(
                            TAG,
                            "Linux process final output session=${session.id} " +
                                "chunk=${index + 1}: $chunk",
                        )
                    }
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
        session.terminalMessage = message
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

    private fun notifyDocumentRequest(
        session: Session,
        request: PendingDocumentRequest,
    ): Boolean {
        if (
            !session.active ||
            request.id <= 0 ||
            request.title.isBlank() ||
            request.operation !in
                DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_OPEN_MULTIPLE ||
            (request.operation == DOCUMENT_OPERATION_SAVE && request.suggestedName.isBlank()) ||
            request.mimeType.isBlank()
        ) {
            return false
        }
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(session.id)
            data.writeInt(request.id)
            data.writeInt(request.operation)
            data.writeString(request.title)
            data.writeString(request.suggestedName)
            data.writeString(request.mimeType)
            session.clientToken.transact(
                CALLBACK_DOCUMENT_REQUEST,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver Android document request session=${session.id}", error)
            false
        } finally {
            data.recycle()
        }
    }

    @Synchronized
    private fun pendingDocumentOperation(
        callingUid: Int,
        sessionId: Int,
        requestId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return 0
        val request = session.pendingDocumentRequest ?: return 0
        return if (request.id == requestId) request.operation else 0
    }

    @Synchronized
    private fun completeDocumentRequest(
        callingUid: Int,
        sessionId: Int,
        requestId: Int,
        result: Int,
        descriptor: ParcelFileDescriptor?,
        documents: List<LauncherPortalOpenDocument>,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val request = session.pendingDocumentRequest ?: return RESULT_INVALID
        val openOperation =
            request.operation == DOCUMENT_OPERATION_OPEN ||
                request.operation == DOCUMENT_OPERATION_OPEN_MULTIPLE
        val successShape =
            if (openOperation) {
                descriptor == null &&
                    documents.size in 1..MAX_OPEN_DOCUMENTS &&
                    documents.all { document -> safeDocumentName(document.displayName) } &&
                    (request.operation == DOCUMENT_OPERATION_OPEN_MULTIPLE ||
                        documents.size == 1)
            } else {
                descriptor != null && documents.isEmpty()
            }
        if (
            request.id != requestId ||
            result !in DOCUMENT_RESULT_SUCCESS..DOCUMENT_RESULT_FAILED ||
            (result == DOCUMENT_RESULT_SUCCESS) != successShape ||
            (result != DOCUMENT_RESULT_SUCCESS &&
                (descriptor != null || documents.isNotEmpty()))
        ) {
            return RESULT_INVALID
        }
        session.pendingDocumentRequest = null
        val portalCompletion = request.portalCompletion
        if (portalCompletion != null) {
            if (
                !portalCompletion.complete(
                    result,
                    if (result == DOCUMENT_RESULT_SUCCESS) descriptor else null,
                    if (result == DOCUMENT_RESULT_SUCCESS) documents else emptyList(),
                )
            ) {
                return RESULT_INVALID
            }
            Log.i(
                TAG,
                "Portal document request ended session=$sessionId request=$requestId result=$result",
            )
            return RESULT_OK
        }
        if (result != DOCUMENT_RESULT_SUCCESS) {
            Log.i(
                TAG,
                "Android document request ended session=$sessionId request=$requestId result=$result",
            )
            return RESULT_OK
        }
        val destination = checkNotNull(descriptor)
        val payload = request.debugPayload ?: return RESULT_INVALID
        clipboardHandler.post {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    output.write(payload)
                    output.flush()
                    destination.fileDescriptor.sync()
                }
            }.onSuccess {
                Log.i(
                    TAG,
                    "Android document save completed session=$sessionId request=$requestId " +
                        "bytes=${payload.size}",
                )
            }.onFailure { error ->
                runCatching { destination.close() }
                Log.e(
                    TAG,
                    "Android document save failed session=$sessionId request=$requestId",
                    error,
                )
            }
        }
        return RESULT_OK
    }

    private fun safeDocumentName(name: String): Boolean =
        name.length in 1..MAX_DOCUMENT_NAME_UTF16 &&
            name != "." &&
            name != ".." &&
            name.none { character ->
                character == '/' ||
                    character == '\\' ||
                    character == '\u0000' ||
                    character.code < 32 ||
                    character.code == 127
            } &&
            hasWellFormedUtf16(name)

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
        private const val LAUNCHER_PACKAGE_PREFIX = "org.archphene.linux.p"
        private const val INTERFACE = "org.archphene.launcher.ISessionV2"
        private const val PROTOCOL_VERSION = 3
        private const val TRANSACTION_OPEN = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_ATTACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_DETACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_INPUT = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_IME = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_DOCUMENT_RESULT = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val CALLBACK_INTERFACE = "org.archphene.launcher.IClientV2"
        private const val CALLBACK_STATUS = IBinder.FIRST_CALL_TRANSACTION
        private const val CALLBACK_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CALLBACK_IME_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CALLBACK_DOCUMENT_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 3
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
        private const val PROCESS_LOGCAT_CHUNK_LENGTH = 1800
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private const val MAX_INPUT_RECORDS = 32
        private const val MAX_ATTACHMENT_FRAME_LOGS = 4
        private const val INPUT_FIELDS = 6
        private const val UINT_MASK = 0xffff_ffffL
        private const val UINT_HIGH_MASK = -0x1_0000_0000L
        private const val UINT_RANGE_MILLIS = 0x1_0000_0000L
        private const val INT_RANGE_MILLIS = 0x8000_0000L
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
        private const val DOCUMENT_OPERATION_SAVE = 1
        private const val DOCUMENT_OPERATION_OPEN = 2
        private const val DOCUMENT_OPERATION_OPEN_MULTIPLE = 3
        private const val DOCUMENT_RESULT_SUCCESS = 1
        private const val DOCUMENT_RESULT_CANCELLED = 2
        private const val DOCUMENT_RESULT_FAILED = 3
        private const val DOCUMENT_REQUEST_TIMEOUT_MINUTES = 10L
        private const val MAX_DOCUMENT_TITLE_UTF16 = 128
        private const val MAX_DOCUMENT_NAME_UTF16 = 255
        private const val MAX_DOCUMENT_MIME_UTF16 = 128
        private const val MAX_OPEN_DOCUMENTS = 32
        private const val MAX_DEBUG_DOCUMENT_BYTES = 65_536
        private val stalePortalSavesRecovered = AtomicBoolean(false)
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val RESULT_UNAUTHORIZED = 2
        private const val RESULT_BUSY = 3
        private const val RESULT_INVALID = 4
    }
}
