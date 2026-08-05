package org.archphene.app.launcher

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import android.text.Html
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.inputmethod.EditorInfo
import org.archphene.app.appearance.LinuxAppearanceOverrides
import org.archphene.app.appearance.LinuxAppearancePreferences
import org.archphene.app.performance.PerformanceMetrics
import org.archphene.app.runtime.checkedNativeOutputLength
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.archphene.app.ArchphenePreferences
import org.archphene.app.MainActivity
import org.archphene.app.R
import org.archphene.app.boundedUtf8Text
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.LauncherAuthorization
import org.archphene.app.utf8LengthAtMost

class LauncherSessionService : Service() {
    private val preferenceAppearanceListener =
        ArchphenePreferences.AppearanceListener {
            requestAppearanceUpdate()
        }
    private data class PendingDocumentRequest(
        val id: Int,
        val operation: Int,
        val title: String,
        val suggestedName: String,
        val mimeType: String,
        val debugPayload: ByteArray?,
        val portalCompletion: PortalDocumentCompletion?,
    )

    private data class AccessibilityAction(
        val nodeId: Int,
        val action: String,
        val text: String,
    )

    private class PortalDocumentCompletion {
        val latch = CountDownLatch(1)
        private val completed = AtomicBoolean(false)
        @Volatile var result = DOCUMENT_RESULT_FAILED
        @Volatile var descriptor: ParcelFileDescriptor? = null
        @Volatile var documents = emptyList<LauncherPortalOpenDocument>()
        @Volatile var displayName = ""

        fun complete(
            result: Int,
            descriptor: ParcelFileDescriptor?,
            documents: List<LauncherPortalOpenDocument>,
            displayName: String,
        ): Boolean {
            if (!completed.compareAndSet(false, true)) return false
            this.result = result
            this.descriptor = descriptor
            this.documents = documents
            this.displayName = displayName
            latch.countDown()
            return true
        }
    }

    private class ClipboardPayload(
        val plainText: ByteArray,
        val html: ByteArray?,
    ) {
        fun contentEquals(other: ClipboardPayload?): Boolean =
            other != null &&
                plainText.contentEquals(other.plainText) &&
                when {
                    html == null -> other.html == null
                    other.html == null -> false
                    else -> html.contentEquals(other.html)
                }
    }

    private data class AndroidClipboardUpdate(val payload: ClipboardPayload?)

    private data class SurfaceAttachment(
        val surface: Surface,
        val releaseBefore: Surface?,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val fontScaleMillis: Int,
    )

    private class Session(
        val id: Int,
        val uid: Int,
        val protocolVersion: Int,
        val identity: VerifiedLauncherIdentity,
        val clientToken: IBinder,
        val authorization: LauncherAuthorization,
        val appearanceOverrides: LinuxAppearanceOverrides,
        val reducedIsolationElectron: Boolean,
        val quickLaunch: Boolean,
        val compositorSocketName: String,
        val rootSessionId: Int,
        val toplevelId: Int,
    ) {
        var surface: Surface? = null
        var surfaceAttachments: LatestDispatchSlot<SurfaceAttachment>? = null
        @Volatile var active = true
        @Volatile var clientActive = true
        var compositor: NativeLauncherCompositor? = null
        var compositorSocket: File? = null
        var linuxHandle = 0L
        var terminalMessage: String? = null
        var portalBridge: LauncherPortalBridge? = null
        var gpuBridge: AndroidGpuBridge? = null
        var audioBridge: LauncherAudioBridge? = null
        var cameraBridge: LauncherCameraBridge? = null
        var hostActive = false
        var audioStartInProgress = false
        var audioStartComplete = false
        var microphonePermissionState = MICROPHONE_PERMISSION_NONE
        var microphonePermissionToken: String? = null
        var gpuRecoveryStage = 0
        var nextProcessStatusMillis = 0L
        var pumpStarted = false
        var clientLogged = false
        var frameLogged = false
        var surfaceWidth = 0
        var surfaceHeight = 0
        var logicalWidth = 0
        var logicalHeight = 0
        var densityDpi = DEFAULT_DENSITY_DPI
        var fontScaleMillis = DEFAULT_FONT_SCALE_MILLIS
        var attachmentFramesLogged = 0
        var cursorChangesLogged = 0
        val presentationBuffer =
            ByteBuffer
                .allocateDirect(
                    NativeLauncherCompositor.PRESENTATION_COMPONENTS * Int.SIZE_BYTES,
                ).order(ByteOrder.LITTLE_ENDIAN)
        var inputKindsLogged = 0
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
        var androidClipboard: ClipboardPayload? = null
        var androidClipboardUpdates: LatestDispatchSlot<AndroidClipboardUpdate>? = null
        var offeredAndroidClipboard: ClipboardPayload? = null
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
        var lastImeDiagnosticActive = false
        var lastImeDiagnosticEvidence = LauncherImeEvidencePolicy.NONE
        var imeLogged = false
        var pendingDocumentRequest: PendingDocumentRequest? = null
        var pendingLaunchDocument: LauncherPortalOpenDocument? = null
        var launchDocumentImportStarted = false
        var launchDocumentWritable = false
        val accessibilityActions =
            ArrayBlockingQueue<AccessibilityAction>(MAX_ACCESSIBILITY_ACTIONS)
        var launchDocumentPath: String? = null
        val availableToplevelIds = IntArray(MAX_PUBLISHED_WINDOWS)
        var availableToplevelCount = 0
        var reconnectGeneration = 0
    }

    private val sessions = HashMap<Int, Session>(MAX_SESSIONS)
    private val nextSessionId = AtomicInteger(1)
    private val nextDocumentRequestId = AtomicInteger(1)
    private val permissionRandom = SecureRandom()
    private val sessionBinder = SessionBinder()
    private lateinit var surfaceThread: HandlerThread
    private lateinit var surfaceHandler: Handler
    private lateinit var appearanceUpdates: LatestDispatchSlot<Unit>
    private lateinit var clipboardThread: HandlerThread
    private lateinit var clipboardHandler: Handler
    private var wallpaperManager: WallpaperManager? = null
    private val microphoneForegroundSessions = HashSet<Int>()
    @Volatile private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var runtimeBound = false

    @Synchronized
    private fun rootSession(session: Session): Session? =
        sessions[session.rootSessionId]?.takeIf { root ->
            root.rootSessionId == root.id && root.active
        }

    @Synchronized
    private fun callbackSession(session: Session): Session? {
        val root = rootSession(session) ?: return null
        if (
            session.clientActive &&
            session.hostActive &&
            session.clientToken.isBinderAlive
        ) {
            return session
        }
        return sessions.values
            .asSequence()
            .filter { candidate ->
                candidate.rootSessionId == root.id &&
                    candidate.clientActive &&
                    candidate.clientToken.isBinderAlive
            }.sortedWith(
                compareByDescending<Session> { candidate -> candidate.hostActive }
                    .thenBy { candidate -> candidate.toplevelId != 0 }
                    .thenBy(Session::id),
            ).firstOrNull()
    }

    @Synchronized
    private fun activateSessionWindow(
        session: Session,
        compositor: NativeLauncherCompositor,
    ): Boolean {
        val token =
            when {
                session.toplevelId != 0 -> session.id
                session.id != session.rootSessionId -> session.id
                sessions.values.any { candidate ->
                    candidate.rootSessionId == session.id &&
                        candidate.toplevelId != 0 &&
                        candidate.clientActive &&
                        candidate.surface != null
                } -> 0
                else -> return true
            }
        return compositor.activateWindow(token)
    }
    private val wallpaperColorsChanged =
        WallpaperManager.OnColorsChangedListener { _, _ ->
            requestAppearanceUpdate()
        }

    private val runtimeConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                runtimeBinder = service as? ArchpheneRuntimeService.LocalBinder
                Log.i(TAG, "Shared runtime connected")
                requestAppearanceUpdate()
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
        appearanceUpdates =
            LatestDispatchSlot(
                schedule = surfaceHandler::post,
                cancel = surfaceHandler::removeCallbacks,
                consume = { publishPortalAppearance() },
            )
        ArchphenePreferences.setAppearanceListener(preferenceAppearanceListener)
        clipboardThread = HandlerThread("ArchpheneLauncherClipboard").apply { start() }
        clipboardHandler = Handler(clipboardThread.looper)
        /*
         * Queue disk-backed recovery before any launcher work can reach the
         * compositor thread. Service creation runs on Android's main thread,
         * where walking runtime directories both violates StrictMode and
         * delays the first launcher window.
         */
        surfaceHandler.post {
            AndroidGpuBridge.cleanupStaleRuntimeDirectories(this)
            LauncherAudioBridge.cleanupStaleRuntimeDirectories(this)
            LauncherCameraBridge.cleanupStaleRuntimeDirectories(this)
            if (stalePortalSavesRecovered.compareAndSet(false, true)) {
                runCatching {
                    LauncherPortalBridge.recoverStaleRuntime(cacheDir)
                    LauncherPortalBridge.recoverStaleSaves(File(filesDir, "arch-root"))
                }.onFailure { error ->
                    Log.e(TAG, "Could not recover stale portal state", error)
                }
            }
        }
        wallpaperManager =
            getSystemService(WallpaperManager::class.java)?.also { manager ->
                manager.addOnColorsChangedListener(wallpaperColorsChanged, surfaceHandler)
            }
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action != ACTION_MICROPHONE_RESULT) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val sessionId = intent.getIntExtra(EXTRA_MICROPHONE_SESSION, 0)
        val token = intent.getStringExtra(EXTRA_MICROPHONE_TOKEN).orEmpty()
        val granted = intent.getBooleanExtra(EXTRA_MICROPHONE_GRANTED, false)
        completeMicrophonePermission(sessionId, token, granted)
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestAppearanceUpdate()
    }

    override fun onDestroy() {
        ArchphenePreferences.clearAppearanceListener(preferenceAppearanceListener)
        appearanceUpdates.close()
        LauncherSessionDebugBridge.detach(this)
        wallpaperManager?.removeOnColorsChangedListener(wallpaperColorsChanged)
        wallpaperManager = null
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
            releaseMicrophoneForeground(session)
            session.clientToken.unlinkToDeath(sessionBinder, 0)
            session.active = false
            releaseSessionResources(session, session.surface, closeCompositor = true)
            session.surface = null
        }
        sessions.clear()
    }

    @Synchronized
    private fun removeSession(
        sessionId: Int,
        closeWindow: Boolean = true,
    ) {
        val session = sessions[sessionId] ?: return
        if (!session.clientActive) return
        releaseMicrophoneForeground(session)
        session.clientToken.unlinkToDeath(sessionBinder, 0)
        session.clientActive = false
        val root = rootSession(session) ?: session
        val otherClients =
            sessions.values.any { candidate ->
                candidate.id != session.id &&
                    candidate.rootSessionId == root.id &&
                    candidate.clientActive
            }
        if (session.rootSessionId == session.id && (otherClients || !closeWindow)) {
            val surface = session.surface
            session.surface = null
            session.surfaceAttachments?.clear()
            session.hostActive = false
            val cleanup = Runnable {
                root.compositor?.detach()
                surface?.release()
            }
            if (!surfaceHandler.post(cleanup)) surface?.release()
            if (!otherClients) scheduleRootFinalization(root)
            Log.i(TAG, "Retained Linux application session=${root.id} for remaining windows")
            return
        }
        if (session.rootSessionId != session.id) {
            sessions.remove(session.id)
            session.active = false
            session.surfaceAttachments?.close()
            session.androidClipboardUpdates?.close()
            session.accessibilityActions.clear()
            val surface = session.surface
            session.surface = null
            val cleanup = Runnable {
                root.compositor?.detachWindow(session.id)
                if (closeWindow && session.toplevelId > 0) {
                    root.compositor?.closeWindow(session.toplevelId)
                    root.compositor?.dispatchAndPresent(SystemClock.uptimeMillis().toInt())
                }
                surface?.release()
            }
            if (!surfaceHandler.post(cleanup)) surface?.release()
            if (!root.clientActive) {
                val remaining =
                    sessions.values.any { candidate ->
                        candidate.rootSessionId == root.id && candidate.clientActive
                    }
                if (!remaining) {
                    if (closeWindow) finalizeRootSession(root) else scheduleRootFinalization(root)
                }
            }
            return
        }
        finalizeRootSession(root)
    }

    @Synchronized
    private fun finalizeRootSession(session: Session) {
        session.reconnectGeneration++
        sessions.remove(session.id)
        session.active = false
        releaseSessionResources(session, session.surface, closeCompositor = true)
        session.surface = null
    }

    @Synchronized
    private fun scheduleRootFinalization(root: Session) {
        root.reconnectGeneration++
        val generation = root.reconnectGeneration
        surfaceHandler.postDelayed(
            {
                synchronized(this) {
                    if (
                        sessions[root.id] === root &&
                        root.active &&
                        root.reconnectGeneration == generation &&
                        sessions.values.none { candidate ->
                            candidate.rootSessionId == root.id && candidate.clientActive
                        }
                    ) {
                        Log.i(TAG, "Launcher reconnect grace expired root=${root.id}")
                        finalizeRootSession(root)
                    }
                }
            },
            SESSION_RECONNECT_GRACE_MILLIS,
        )
    }

    private fun releaseSessionResources(
        session: Session,
        surface: Surface?,
        closeCompositor: Boolean,
    ) {
        if (closeCompositor) {
            session.surfaceAttachments?.close()
        } else {
            session.surfaceAttachments?.clear()
        }
        synchronized(session) {
            session.imeTexts.fill(null)
            session.imeHead = 0
            session.imeSize = 0
            session.imePosted = false
            if (closeCompositor) {
                session.androidClipboardUpdates?.close()
                session.accessibilityActions.clear()
                session.pendingDocumentRequest?.portalCompletion?.complete(
                    DOCUMENT_RESULT_FAILED,
                    null,
                    emptyList(),
                    "",
                )
                session.pendingDocumentRequest = null
                session.pendingLaunchDocument?.descriptor?.close()
                session.pendingLaunchDocument = null
            }
        }
        val runtime = runtimeBinder
        val cleanup = Runnable {
            Log.i(
                TAG,
                "Releasing launcher resources session=${session.id} close=$closeCompositor",
            )
            session.hostActive = false
            session.audioBridge?.setHostActive(false, closing = closeCompositor)
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
                session.gpuBridge?.close()
                session.gpuBridge = null
                session.audioBridge?.close()
                session.audioBridge = null
                session.cameraBridge?.close()
                session.cameraBridge = null
                compositor?.close()
                session.compositor = null
                session.compositorSocket = null
                session.pumpStarted = false
            } else {
                session.compositor?.setHostActive(false)
                session.compositor?.setClipboardActive(false)
                session.compositor?.detach()
            }
            surface?.release()
        }
        if (!surfaceHandler.post(cleanup)) {
            Log.e(TAG, "Could not schedule launcher resource cleanup session=${session.id}")
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
                TRANSACTION_ACCESSIBILITY_ACTION -> {
                    transactAccessibilityAction(data, reply)
                    true
                }
                TRANSACTION_ACTIVATE_NEXT_WINDOW -> {
                    transactActivateNextWindow(data, reply)
                    true
                }
                TRANSACTION_RELEASE_WINDOW_TASK -> {
                    transactReleaseWindowTask(data, reply)
                    true
                }
                TRANSACTION_CLOSE_APPLICATION -> {
                    transactCloseApplication(data, reply)
                    true
                }
                TRANSACTION_OPEN_QUICK -> {
                    transactOpenQuick(data, reply)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        override fun binderDied() {
            synchronized(this@LauncherSessionService) {
                val dead =
                    sessions.values
                        .filter { session ->
                            session.clientActive && !session.clientToken.isBinderAlive
                        }
                        .map(Session::id)
                for (sessionId in dead) {
                    Log.i(TAG, "Client Binder died for launcher session=$sessionId")
                    removeSession(sessionId, closeWindow = false)
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
                    val hasDocument = data.readInt()
                    val document =
                        if (hasDocument == 1) {
                            val displayName = data.readString().orEmpty()
                            val mimeType = data.readString().orEmpty()
                            val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                            val writableValue =
                                if (version >= EDIT_DOCUMENT_PROTOCOL_VERSION) {
                                    data.readInt()
                                } else {
                                    0
                                }
                            if (
                                !safeDocumentName(displayName) ||
                                !LauncherIntentMimePolicy.valid(mimeType) ||
                                writableValue !in 0..1
                            ) {
                                descriptor.close()
                                return@runCatching OpenResult(RESULT_INVALID, 0, null)
                            }
                            PendingLaunchDocument(
                                LauncherPortalOpenDocument(descriptor, displayName, false),
                                mimeType,
                                writableValue == 1,
                            )
                        } else if (hasDocument == 0) {
                            null
                        } else {
                            return@runCatching OpenResult(RESULT_INVALID, 0, null)
                        }
                    val requestedToplevelId =
                        if (version >= MULTI_WINDOW_PROTOCOL_VERSION) {
                            data.readInt()
                        } else {
                            0
                        }
                    if (
                        !supportedProtocolVersion(version) ||
                        token == null ||
                        requestedToplevelId < 0 ||
                        data.dataAvail() != 0
                    ) {
                        document?.document?.descriptor?.close()
                        return@runCatching OpenResult(RESULT_INVALID, 0, null)
                    }
                    openSession(
                        Binder.getCallingUid(),
                        version,
                        token,
                        document,
                        requestedToplevelId,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher open", error)
                    OpenResult(RESULT_INVALID, 0, null)
                }
            reply.writeNoException()
            reply.writeInt(result.result)
            reply.writeInt(result.sessionId)
            reply.writeString(result.authorization?.label)
            reply.writeInt(if (result.authorization?.terminal == true) 1 else 0)
            reply.writeInt(
                if (result.authorization?.prefersPhoneLandscape == true) {
                    ORIENTATION_POLICY_SDL_PHONE
                } else {
                    ORIENTATION_POLICY_DEFAULT
                },
            )
        }

        private fun transactOpenQuick(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    val version = data.readInt()
                    val token = data.readStrongBinder()
                    val androidPackage = data.readString().orEmpty()
                    val descriptorIdHex = data.readString().orEmpty()
                    val generation = data.readLong()
                    if (
                        !supportedProtocolVersion(version) ||
                        token == null ||
                        androidPackage.length != 53 ||
                        !androidPackage.startsWith(LAUNCHER_PACKAGE_PREFIX) ||
                        !androidPackage.drop(LAUNCHER_PACKAGE_PREFIX.length).all { character ->
                            character.isDigit() || character in 'a'..'f'
                        } ||
                        descriptorIdHex.length != 64 ||
                        !descriptorIdHex.all { character ->
                            character.isDigit() || character in 'a'..'f'
                        } ||
                        generation !in 1..Int.MAX_VALUE.toLong() ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching OpenResult(RESULT_INVALID, 0, null)
                    }
                    openSession(
                        Binder.getCallingUid(),
                        version,
                        token,
                        pendingDocument = null,
                        requestedToplevelId = 0,
                        quickLaunchRequest =
                            QuickLaunchRequest(androidPackage, descriptorIdHex, generation),
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed Quick launch open", error)
                    OpenResult(RESULT_INVALID, 0, null)
                }
            reply.writeNoException()
            reply.writeInt(result.result)
            reply.writeInt(result.sessionId)
            reply.writeString(result.authorization?.label)
            reply.writeInt(if (result.authorization?.terminal == true) 1 else 0)
            reply.writeInt(
                if (result.authorization?.prefersPhoneLandscape == true) {
                    ORIENTATION_POLICY_SDL_PHONE
                } else {
                    ORIENTATION_POLICY_DEFAULT
                },
            )
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
                        !supportedProtocolVersion(version) ||
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
                        !supportedProtocolVersion(version) ||
                        sessionId <= 0 ||
                        width !in 1..MAX_SURFACE_DIMENSION ||
                        height !in 1..MAX_SURFACE_DIMENSION ||
                        width.toLong() * height > MAX_SURFACE_PIXELS ||
                        densityDpi !in MIN_DENSITY_DPI..MAX_DENSITY_DPI ||
                        fontScaleMillis !in MIN_FONT_SCALE_MILLIS..MAX_FONT_SCALE_MILLIS ||
                        surface?.isValid != true ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching SurfaceAttachResult(RESULT_INVALID)
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
                        if (attachResult.status == RESULT_OK) {
                            surface = null
                        }
                    }
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed launcher Surface", error)
                    SurfaceAttachResult(RESULT_INVALID)
                }
            surface?.release()
            reply.writeNoException()
            reply.writeInt(result.status)
            if (result.status == RESULT_OK) {
                reply.writeInt(result.logicalWidth)
                reply.writeInt(result.logicalHeight)
            }
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
                        !supportedProtocolVersion(version) ||
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
                        !supportedProtocolVersion(version) ||
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
                    val htmlPresent = if (present == 1) data.readInt() else 0
                    val html = if (htmlPresent == 1) data.readString() else null
                    if (
                        !supportedProtocolVersion(version) ||
                        sessionId <= 0 ||
                        present !in 0..1 ||
                        htmlPresent !in 0..1 ||
                        (present == 1 && (text == null || text.length > MAX_CLIPBOARD_UTF16)) ||
                        (present == 0 && htmlPresent != 0) ||
                        (htmlPresent == 1 &&
                            (html == null || html.length > MAX_CLIPBOARD_UTF16)) ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    submitAndroidClipboard(
                        Binder.getCallingUid(),
                        sessionId,
                        text,
                        html,
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
                        !supportedProtocolVersion(version) ||
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
            var directoryName = ""
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
                        } else if (
                            operation == DOCUMENT_OPERATION_DIRECTORY ||
                            operation == DOCUMENT_OPERATION_SAVE
                        ) {
                            directoryName = data.readString().orEmpty()
                            check(safeDocumentName(directoryName))
                            descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                        } else {
                            descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                        }
                    }
                    val openOperation =
                        operation == DOCUMENT_OPERATION_OPEN ||
                            operation == DOCUMENT_OPERATION_OPEN_MULTIPLE
                    val successShape =
                        when {
                            openOperation ->
                                descriptor == null &&
                                    directoryName.isEmpty() &&
                                    openDocuments.isNotEmpty() &&
                                    (operation == DOCUMENT_OPERATION_OPEN_MULTIPLE ||
                                        openDocuments.size == 1)
                            operation == DOCUMENT_OPERATION_DIRECTORY ->
                                descriptor != null &&
                                    openDocuments.isEmpty() &&
                                    safeDocumentName(directoryName)
                            operation == DOCUMENT_OPERATION_SAVE ->
                                descriptor != null &&
                                    openDocuments.isEmpty() &&
                                    safeDocumentName(directoryName)
                            else ->
                                descriptor != null &&
                                    openDocuments.isEmpty() &&
                                    directoryName.isEmpty()
                        }
                    if (
                        !supportedProtocolVersion(version) ||
                        sessionId <= 0 ||
                        requestId <= 0 ||
                        operation !in
                            DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY ||
                        documentResult !in DOCUMENT_RESULT_SUCCESS..DOCUMENT_RESULT_FAILED ||
                        (documentResult == DOCUMENT_RESULT_SUCCESS) != successShape ||
                        (documentResult != DOCUMENT_RESULT_SUCCESS &&
                            (
                                descriptor != null ||
                                    openDocuments.isNotEmpty() ||
                                    directoryName.isNotEmpty()
                            )) ||
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
                        directoryName,
                    ).also { completionResult ->
                        if (
                            completionResult == RESULT_OK &&
                            documentResult == DOCUMENT_RESULT_SUCCESS
                        ) {
                            descriptor = null
                            openDocuments.clear()
                            directoryName = ""
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

        private fun transactAccessibilityAction(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                runCatching {
                    data.enforceInterface(INTERFACE)
                    if (data.dataAvail() > MAX_ACCESSIBILITY_ACTION_PARCEL_BYTES) {
                        return@runCatching RESULT_INVALID
                    }
                    val version = data.readInt()
                    val sessionId = data.readInt()
                    val nodeId = data.readInt()
                    val action = data.readString().orEmpty()
                    val text = data.readString().orEmpty()
                    val internalRefresh = nodeId == 0 && action == "refresh"
                    if (
                        !supportedProtocolVersion(version) ||
                        sessionId <= 0 ||
                        (!internalRefresh && nodeId !in 1..MAX_ACCESSIBILITY_NODE_ID) ||
                        (action == "refresh" && !internalRefresh) ||
                        (internalRefresh && text.isNotEmpty()) ||
                        action !in ACCESSIBILITY_ACTIONS ||
                        text.length > MAX_ACCESSIBILITY_TEXT_UTF16 ||
                        !utf8LengthAtMost(text, MAX_ACCESSIBILITY_TEXT_BYTES) ||
                        (action != "set-text" && text.isNotEmpty()) ||
                        !hasWellFormedUtf16(text) ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching RESULT_INVALID
                    }
                    submitAccessibilityAction(
                        Binder.getCallingUid(),
                        sessionId,
                        nodeId,
                        action,
                        text,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Rejected malformed accessibility action", error)
                    RESULT_INVALID
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactActivateNextWindow(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                transactSessionOnly(data, "window switch") { callingUid, sessionId ->
                    activateNextWindow(callingUid, sessionId)
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactReleaseWindowTask(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                transactSessionOnly(data, "window task release") { callingUid, sessionId ->
                    releaseWindowTask(callingUid, sessionId)
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactCloseApplication(
            data: Parcel,
            reply: Parcel,
        ) {
            val result =
                transactSessionOnly(data, "application replacement") { callingUid, sessionId ->
                    closeApplication(callingUid, sessionId)
                }
            reply.writeNoException()
            reply.writeInt(result)
        }

        private fun transactSessionOnly(
            data: Parcel,
            operation: String,
            action: (Int, Int) -> Int,
        ): Int =
            runCatching {
                data.enforceInterface(INTERFACE)
                val version = data.readInt()
                val sessionId = data.readInt()
                if (
                    !supportedProtocolVersion(version) ||
                    sessionId <= 0 ||
                    data.dataAvail() != 0
                ) {
                    return@runCatching RESULT_INVALID
                }
                action(Binder.getCallingUid(), sessionId)
            }.getOrElse { error ->
                Log.w(TAG, "Rejected malformed launcher $operation", error)
                RESULT_INVALID
            }
    }

    @Synchronized
    private fun activateNextWindow(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.toplevelId != 0 || session.surface == null) return RESULT_INVALID
        val root = rootSession(session) ?: return RESULT_NOT_READY
        return if (
            surfaceHandler.post {
                synchronized(this) {
                    if (!session.clientActive || session.surface == null) return@post
                }
                val compositor = root.compositor ?: return@post
                val activated = compositor.activateNextWindow()
                if (activated) {
                    compositor.dispatchAndPresent(SystemClock.uptimeMillis().toInt())
                }
                Log.i(TAG, "Compact Linux window switch root=${root.id} activated=$activated")
            }
        ) {
            RESULT_OK
        } else {
            RESULT_NOT_READY
        }
    }

    @Synchronized
    private fun releaseWindowTask(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.toplevelId <= 0) return RESULT_INVALID
        removeSession(sessionId, closeWindow = false)
        Log.i(TAG, "Released Android task session=$sessionId without closing its Linux window")
        return RESULT_OK
    }

    @Synchronized
    private fun closeApplication(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val root = rootSession(session) ?: return RESULT_NOT_READY
        return closeRootApplication(root, "incoming Android document")
    }

    private fun closeRootApplication(
        root: Session,
        reason: String,
    ): Int {
        notifyLaunchDocumentWriteback(root)
        val children =
            sessions.values.filter { candidate ->
                candidate.rootSessionId == root.id && candidate.id != root.id
            }
        for (child in children) {
            releaseMicrophoneForeground(child)
            child.clientToken.unlinkToDeath(sessionBinder, 0)
            child.clientActive = false
            child.active = false
            sessions.remove(child.id)
            child.surfaceAttachments?.close()
            child.androidClipboardUpdates?.close()
            child.accessibilityActions.clear()
            val surface = child.surface
            child.surface = null
            if (surface != null && !surfaceHandler.post { surface.release() }) {
                surface.release()
            }
        }
        releaseMicrophoneForeground(root)
        root.clientToken.unlinkToDeath(sessionBinder, 0)
        root.clientActive = false
        root.active = false
        sessions.remove(root.id)
        releaseSessionResources(root, root.surface, closeCompositor = true)
        root.surface = null
        Log.i(
            TAG,
            "Closed Linux application root=${root.id} attachments=${children.size + 1} " +
                "for $reason",
        )
        return RESULT_OK
    }

    private data class OpenResult(
        val result: Int,
        val sessionId: Int,
        val authorization: LauncherAuthorization?,
    )

    private data class SurfaceAttachResult(
        val status: Int,
        val logicalWidth: Int = 0,
        val logicalHeight: Int = 0,
    )

    private data class PendingLaunchDocument(
        val document: LauncherPortalOpenDocument,
        val mimeType: String,
        val writable: Boolean,
    )

    private data class QuickLaunchRequest(
        val androidPackage: String,
        val descriptorIdHex: String,
        val generation: Long,
    )

    @Synchronized
    private fun openSession(
        callingUid: Int,
        protocolVersion: Int,
        clientToken: IBinder,
        pendingDocument: PendingLaunchDocument?,
        requestedToplevelId: Int,
        quickLaunchRequest: QuickLaunchRequest? = null,
    ): OpenResult {
        fun reject(result: Int): OpenResult {
            pendingDocument?.document?.descriptor?.close()
            return OpenResult(result, 0, null)
        }
        val runtime = runtimeBinder ?: return reject(RESULT_NOT_READY)
        if (runtime.runtimeHandle == 0L) {
            return reject(RESULT_NOT_READY)
        }
        if (!ArchphenePreferences.isReady()) {
            return reject(RESULT_NOT_READY)
        }
        val identity: VerifiedLauncherIdentity
        val authorization: LauncherAuthorization
        if (quickLaunchRequest != null) {
            if (
                callingUid != applicationInfo.uid ||
                pendingDocument != null ||
                requestedToplevelId != 0
            ) {
                return reject(RESULT_UNAUTHORIZED)
            }
            authorization =
                runtime.authorizeQuickLauncher(
                    quickLaunchRequest.androidPackage,
                    quickLaunchRequest.descriptorIdHex,
                    quickLaunchRequest.generation,
                ) ?: return reject(RESULT_UNAUTHORIZED)
            identity =
                VerifiedLauncherIdentity(
                    quickLaunchRequest.androidPackage,
                    quickLaunchRequest.descriptorIdHex,
                    quickLaunchRequest.generation,
                    authorization.mimeTypes,
                )
        } else {
            identity =
                LauncherIdentityVerifier.verify(this, callingUid)
                    ?: run {
                        Log.w(TAG, "Rejected launcher UID=$callingUid before registry lookup")
                        return reject(RESULT_UNAUTHORIZED)
                    }
            authorization =
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
                    return reject(RESULT_UNAUTHORIZED)
                }
        }
        if (identity.mimeTypes != authorization.mimeTypes) {
            Log.w(TAG, "Rejected launcher whose signed MIME declaration is stale")
            return reject(RESULT_UNAUTHORIZED)
        }
        if (
            pendingDocument != null &&
            !LauncherIntentMimePolicy.matches(
                authorization.mimeTypes,
                pendingDocument.mimeType,
            )
        ) {
            return reject(RESULT_UNAUTHORIZED)
        }
        val existing =
            sessions.values.firstOrNull { session ->
                session.clientActive &&
                    session.uid == callingUid &&
                    session.clientToken === clientToken
            }
        if (existing != null) {
            pendingDocument?.document?.descriptor?.close()
            return if (existing.protocolVersion == protocolVersion) {
                OpenResult(RESULT_OK, existing.id, authorization)
            } else {
                OpenResult(RESULT_INVALID, 0, null)
            }
        }
        var existingRoot =
            sessions.values.firstOrNull { session ->
                session.rootSessionId == session.id &&
                    session.active &&
                    session.uid == callingUid &&
                    session.identity == identity
            }
        if (requestedToplevelId == 0 && pendingDocument != null && existingRoot != null) {
            closeRootApplication(existingRoot, "cold Android document launch")
            existingRoot = null
        }
        val root =
            when {
                requestedToplevelId == 0 && existingRoot == null -> null
                requestedToplevelId == 0 -> {
                    existingRoot?.takeIf { candidate ->
                        pendingDocument == null &&
                            sessions.values.none { active ->
                                active.rootSessionId == candidate.id &&
                                    active.toplevelId == 0 &&
                                    active.clientActive
                            }
                    } ?: return reject(RESULT_BUSY)
                }
                else -> {
                    existingRoot?.takeIf { candidate ->
                        pendingDocument == null &&
                            (0 until candidate.availableToplevelCount).any { index ->
                                candidate.availableToplevelIds[index] == requestedToplevelId
                            }
                    } ?: return reject(RESULT_NOT_READY)
                }
            }
        if (
            root != null &&
            sessions.values.any { candidate ->
                candidate.rootSessionId == root.id &&
                    candidate.toplevelId == requestedToplevelId &&
                    candidate.clientActive
            }
        ) {
            return reject(RESULT_BUSY)
        }
        if (sessions.size >= MAX_SESSIONS) {
            return reject(RESULT_BUSY)
        }
        val sessionId = nextSessionId.getAndUpdate { value -> if (value == Int.MAX_VALUE) 1 else value + 1 }
        if (sessionId <= 0 || sessions.containsKey(sessionId)) {
            return reject(RESULT_BUSY)
        }
        val preferences = ArchphenePreferences.snapshot()
        val session =
            Session(
                sessionId,
                callingUid,
                protocolVersion,
                identity,
                clientToken,
                authorization,
                preferences.appearance,
                preferences.reducedIsolationElectron,
                quickLaunchRequest != null,
                root?.compositorSocketName ?: newCompositorSocketName(sessionId),
                root?.id ?: sessionId,
                requestedToplevelId,
            )
        session.inputDrain = Runnable { drainInput(session) }
        session.imeDrain = Runnable { drainIme(session) }
        session.androidClipboardUpdates =
            LatestDispatchSlot(
                schedule = surfaceHandler::post,
                cancel = surfaceHandler::removeCallbacks,
                consume = { update -> drainAndroidClipboard(session, update) },
            )
        session.surfaceAttachments =
            LatestDispatchSlot(
                schedule = surfaceHandler::post,
                cancel = surfaceHandler::removeCallbacks,
                consume = { attachment -> applySurfaceAttachment(session, attachment) },
                merge = { previous, next ->
                    next.copy(releaseBefore = previous.releaseBefore)
                },
                discardReplaced = { attachment -> attachment.surface.release() },
                discardCleared = { attachment ->
                    attachment.releaseBefore?.let(::releaseSurfaceOnHandler)
                },
            )
        try {
            clientToken.linkToDeath(sessionBinder, 0)
        } catch (_: RemoteException) {
            return reject(RESULT_INVALID)
        }
        sessions[sessionId] = session
        root?.let { owner -> owner.reconnectGeneration++ }
        session.pendingLaunchDocument = pendingDocument?.document
        session.launchDocumentWritable = pendingDocument?.writable == true
        Log.i(
            TAG,
            "Authorized launcher package=${identity.androidPackage} " +
                "generation=${identity.generation} session=$sessionId " +
                "root=${session.rootSessionId} toplevel=$requestedToplevelId",
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
    ): SurfaceAttachResult {
        val session =
            authorizedSession(callingUid, sessionId)
                ?: return SurfaceAttachResult(RESULT_UNAUTHORIZED)
        val logicalSize =
            LauncherSurfaceGeometryPolicy.logicalSize(
                width,
                height,
                densityDpi,
                session.appearanceOverrides.geometryPercent,
            )
        val attachment =
            SurfaceAttachment(
                surface,
                session.surface,
                width,
                height,
                densityDpi,
                fontScaleMillis,
            )
        if (!checkNotNull(session.surfaceAttachments).offer(attachment)) {
            surface.release()
            return SurfaceAttachResult(RESULT_NOT_READY)
        }
        session.surface = surface
        session.surfaceWidth = width
        session.surfaceHeight = height
        session.logicalWidth = logicalSize.width
        session.logicalHeight = logicalSize.height
        session.densityDpi = densityDpi
        session.fontScaleMillis = fontScaleMillis
        Log.i(TAG, "Attached launcher Surface session=$sessionId size=${width}x$height")
        return SurfaceAttachResult(
            RESULT_OK,
            logicalSize.width,
            logicalSize.height,
        )
    }

    private fun applySurfaceAttachment(
        session: Session,
        attachment: SurfaceAttachment,
    ) {
        val root =
            synchronized(this) {
                if (
                    !session.active ||
                    sessions[session.id] !== session ||
                    session.surface !== attachment.surface
                ) {
                    null
                } else {
                    rootSession(session)
                }
            }
        if (root == null) {
            attachment.releaseBefore?.release()
            return
        }
        root.portalBridge?.let { bridge ->
            val appearance = resolvedAppearance(root)
            notifyAppearance(session, appearance)
            bridge.updateAppearance(appearance.dark, appearance.accent)
        }
        if (session.rootSessionId != session.id) {
            val compositor = root.compositor
            attachment.releaseBefore?.release()
            if (
                compositor == null ||
                !compositor.attachWindow(
                    session.id,
                    session.toplevelId,
                    attachment.surface,
                    attachment.width,
                    attachment.height,
                    attachment.densityDpi,
                    root.appearanceOverrides.geometryPercent,
                )
            ) {
                notifyStatus(session, STATUS_STOPPED, "Could not attach this Linux window.")
                return
            }
            session.hostActive = true
            notifyStatus(session, STATUS_RUNNING, root.authorization.label)
            Log.i(
                TAG,
                "Attached independent Linux window session=${session.id} " +
                    "root=${root.id} toplevel=${session.toplevelId}",
            )
            return
        }
        root.compositor?.detach()
        attachment.releaseBefore?.release()
        attachCompositor(
            root,
            attachment.surface,
            attachment.width,
            attachment.height,
            attachment.densityDpi,
        )
    }

    private fun releaseSurfaceOnHandler(surface: Surface) {
        if (!surfaceHandler.post { surface.release() }) {
            surface.release()
        }
    }

    @Synchronized
    private fun detachSurface(
        callingUid: Int,
        sessionId: Int,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val surface = session.surface
        session.surface = null
        if (session.rootSessionId != session.id) {
            session.surfaceAttachments?.clear()
            val root = rootSession(session)
            val cleanup = Runnable {
                root?.compositor?.detachWindow(session.id)
                surface?.release()
            }
            if (!surfaceHandler.post(cleanup)) {
                surface?.release()
            }
            Log.i(TAG, "Detached independent Linux window session=$sessionId")
            return RESULT_OK
        }
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
        val runtime = runtimeBinder ?: return null
        if (session.quickLaunch) {
            if (
                !session.clientActive ||
                callingUid != applicationInfo.uid ||
                callingUid != session.uid ||
                runtime.authorizeQuickLauncher(
                    session.identity.androidPackage,
                    session.identity.descriptorIdHex,
                    session.identity.generation,
                ) == null
            ) {
                return null
            }
            return session
        }
        val current = LauncherIdentityVerifier.verify(this, callingUid) ?: return null
        if (
            !session.clientActive ||
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
    private fun submitAccessibilityAction(
        callingUid: Int,
        sessionId: Int,
        nodeId: Int,
        action: String,
        text: String,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val root = rootSession(session) ?: return RESULT_NOT_READY
        return if (
            root.accessibilityActions.offer(
                AccessibilityAction(nodeId, action, text),
            )
        ) {
            RESULT_OK
        } else {
            RESULT_BUSY
        }
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
                        maxOf(
                            MAX_INPUT_COORDINATE,
                            session.surfaceWidth,
                            session.surfaceHeight,
                            session.logicalWidth,
                            session.logicalHeight,
                        ),
                    )
                ) {
                    return RESULT_INVALID
                }
                if (session.protocolVersion < INPUT_LOGICAL_COORDINATE_PROTOCOL_VERSION) {
                    scaleLegacyInputRecord(
                        session.inputRecords,
                        start,
                        session.logicalWidth,
                        session.logicalHeight,
                        session.surfaceWidth,
                        session.surfaceHeight,
                    )
                }
                val eventTime =
                    inputEventTime(
                        session.inputRecords[start],
                        session.inputRecords[start + 1],
                        session.inputRecords[start + 2],
                        session.inputRecords[start + 3],
                        session.inputRecords[start + 4],
                        session.inputRecords[start + 5],
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
        html: String?,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.surface == null) {
            return RESULT_NOT_READY
        }
        val bytes = text?.toByteArray(StandardCharsets.UTF_8)
        val htmlBytes = html?.toByteArray(StandardCharsets.UTF_8)
        if (
            (bytes != null && bytes.size > MAX_CLIPBOARD_BYTES) ||
            (htmlBytes != null && htmlBytes.size > MAX_CLIPBOARD_BYTES) ||
            (bytes == null && htmlBytes != null)
        ) {
            return RESULT_INVALID
        }
        val payload = bytes?.let { ClipboardPayload(it, htmlBytes) }
        val previous = session.androidClipboard
        if ((previous == null && payload == null) || payload?.contentEquals(previous) == true) {
            return RESULT_OK
        }
        session.androidClipboard = payload
        if (!session.clipboardLogged) {
            session.clipboardLogged = true
            Log.i(
                TAG,
                    "Accepted first bounded Android clipboard session=$sessionId " +
                    "present=${payload != null} textBytes=${bytes?.size ?: 0} " +
                    "htmlBytes=${htmlBytes?.size ?: 0}",
            )
        }
        if (
            !checkNotNull(session.androidClipboardUpdates).offer(AndroidClipboardUpdate(payload))
        ) {
            session.androidClipboard = previous
            return RESULT_NOT_READY
        }
        return RESULT_OK
    }

    private fun drainAndroidClipboard(
        session: Session,
        update: AndroidClipboardUpdate,
    ) {
        val root =
            synchronized(this) {
                if (!session.active || session.surface == null) return
                session.androidClipboard = update.payload
                rootSession(session) ?: return
            }
        val compositor = root.compositor ?: return
        if (!activateSessionWindow(session, compositor)) return
        root.clipboardRevision = root.clipboardRevision.inc().coerceAtLeast(1)
        root.offeredAndroidClipboard = update.payload
        if (update.payload == null) {
            compositor.clearAndroidClipboard()
        } else {
            compositor.offerAndroidClipboardText(update.payload.html != null)
        }
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
        if (session.surface == null || rootSession(session)?.compositor == null) {
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
            val compositor = rootSession(session)?.compositor ?: return
            if (!activateSessionWindow(session, compositor)) return
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
            if (
                candidate.active &&
                candidate.rootSessionId == candidate.id &&
                candidate.identity.androidPackage == androidPackage
            ) {
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
            !PortalMimePolicy.valid(mimeType) ||
            payload.size > MAX_DEBUG_DOCUMENT_BYTES
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-document")
        }
        var session: Session? = null
        var matching = 0
        for (candidate in sessions.values) {
            if (
                candidate.active &&
                candidate.rootSessionId == candidate.id &&
                candidate.identity.androidPackage == androidPackage
            ) {
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

    @Synchronized
    internal fun debugPrintPdf(
        androidPackage: String,
        title: String,
        payload: ByteArray,
        nonRegular: Boolean,
    ): LauncherSessionDebugResult {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return LauncherSessionDebugResult(false, 0, "release-build")
        }
        if (
            !isValidLauncherPackage(androidPackage) ||
            title.isBlank() ||
            title.length > MAX_PRINT_TITLE_UTF16 ||
            !utf8LengthAtMost(title, MAX_PRINT_TITLE_BYTES) ||
            payload.size > MAX_DEBUG_DOCUMENT_BYTES
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-print")
        }
        var session: Session? = null
        var matching = 0
        for (candidate in sessions.values) {
            if (
                candidate.active &&
                candidate.rootSessionId == candidate.id &&
                candidate.identity.androidPackage == androidPackage
            ) {
                session = candidate
                matching++
            }
        }
        if (matching != 1) {
            return LauncherSessionDebugResult(
                false,
                0,
                "active-session-count-$matching",
            )
        }
        val activeSession = checkNotNull(session)
        if (
            activeSession.surface == null ||
            activeSession.authorization.bridgeCapabilities and BRIDGE_PRINTING == 0
        ) {
            return LauncherSessionDebugResult(false, activeSession.id, "printing-not-ready")
        }
        val bridge =
            activeSession.portalBridge
                ?: return LauncherSessionDebugResult(
                    false,
                    activeSession.id,
                    "portal-not-ready",
                )
        val response =
            runCatching { bridge.debugPrintPdf(title, payload, nonRegular) }
                .getOrElse { error ->
                    Log.e(TAG, "Debug print probe failed session=${activeSession.id}", error)
                    return LauncherSessionDebugResult(
                        false,
                        activeSession.id,
                        "probe-failed",
                    )
                }
        return LauncherSessionDebugResult(response == "OK", activeSession.id, response)
    }

    internal fun debugPlayAudio(androidPackage: String): LauncherSessionDebugResult {
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
                !isValidLauncherPackage(androidPackage)
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-audio-probe")
        }
        val activeSession =
            synchronized(this) {
                sessions.values.singleOrNull { session ->
                    session.active &&
                        session.rootSessionId == session.id &&
                        session.identity.androidPackage == androidPackage
                }
            } ?: return LauncherSessionDebugResult(false, 0, "audio-session-not-ready")
        if (activeSession.authorization.bridgeCapabilities and BRIDGE_AUDIO_OUTPUT == 0) {
            return LauncherSessionDebugResult(
                false,
                activeSession.id,
                "audio-capability-denied",
            )
        }
        val bridge =
            synchronized(this) {
                activeSession.audioBridge?.takeIf { it.isReady() }
            } ?: return LauncherSessionDebugResult(false, activeSession.id, "audio-bridge-not-ready")
        return if (runCatching { bridge.playDebugTone() }.getOrDefault(false)) {
            LauncherSessionDebugResult(true, activeSession.id, "played")
        } else {
            LauncherSessionDebugResult(false, activeSession.id, "playback-failed")
        }
    }

    internal fun debugCaptureMicrophone(androidPackage: String): LauncherSessionDebugResult {
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
                !isValidLauncherPackage(androidPackage)
        ) {
            return LauncherSessionDebugResult(false, 0, "invalid-microphone-probe")
        }
        val activeSession =
            synchronized(this) {
                sessions.values.singleOrNull { session ->
                    session.active &&
                        session.rootSessionId == session.id &&
                        session.identity.androidPackage == androidPackage
                }
            } ?: return LauncherSessionDebugResult(false, 0, "microphone-session-not-ready")
        if (activeSession.authorization.bridgeCapabilities and BRIDGE_AUDIO_INPUT == 0) {
            return LauncherSessionDebugResult(
                false,
                activeSession.id,
                "microphone-capability-denied",
            )
        }
        val bridge =
            synchronized(this) {
                activeSession.audioBridge?.takeIf { it.isReady() }
            } ?: return LauncherSessionDebugResult(
                false,
                activeSession.id,
                "microphone-bridge-not-ready",
            )
        val capture =
            runCatching { bridge.captureDebugMicrophone() }
                .getOrElse {
                    return LauncherSessionDebugResult(
                        false,
                        activeSession.id,
                        "microphone-capture-failed",
                    )
                }
        val accepted =
            capture.bytes >= DEBUG_MICROPHONE_MINIMUM_BYTES &&
                capture.nonzeroBytes >= DEBUG_MICROPHONE_MINIMUM_NONZERO_BYTES
        return LauncherSessionDebugResult(
            accepted,
            activeSession.id,
            "captured-${capture.bytes}-${capture.nonzeroBytes}",
        )
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
        maxCoordinate: Int,
    ): Boolean =
        when (kind) {
            INPUT_TOUCH_DOWN,
            INPUT_TOUCH_MOTION,
            -> {
                a in 0 until MAX_TOUCHES &&
                    b in MIN_INPUT_COORDINATE..maxCoordinate &&
                    c in MIN_INPUT_COORDINATE..maxCoordinate &&
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
                a in MIN_INPUT_COORDINATE..maxCoordinate &&
                    b in MIN_INPUT_COORDINATE..maxCoordinate &&
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
            INPUT_POINTER_RELATIVE -> {
                (a != 0 || b != 0) &&
                    a in -MAX_RELATIVE_FIXED..MAX_RELATIVE_FIXED &&
                    b in -MAX_RELATIVE_FIXED..MAX_RELATIVE_FIXED &&
                    c in -MAX_RELATIVE_FIXED..MAX_RELATIVE_FIXED &&
                    d in -MAX_RELATIVE_FIXED..MAX_RELATIVE_FIXED
            }
            INPUT_POINTER_CAPTURE_LOST ->
                a == 0 && b == 0 && c == 0 && d == 0 && e == 0
            INPUT_HOST_ACTIVE -> a in 0..1 && c == 0 && d == 0 && e == 0
            else -> false
        }

    private fun inputEventTime(
        kind: Int,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
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
            INPUT_POINTER_RELATIVE -> e
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
        var hostActive: Boolean? = null
        var firstUserKind = 0
        var firstUserA = 0
        var firstUserB = 0
        var userKinds = 0
        var pointerButtonStates = 0
        val count =
            synchronized(session) {
                val count = session.inputCount
                session.inputBuffer.clear()
                for (record in 0 until count) {
                    val offset = record * INPUT_FIELDS
                    val kind = session.inputRecords[offset]
                    for (field in 0 until INPUT_FIELDS) {
                        session.inputBuffer.putInt(session.inputRecords[offset + field])
                    }
                    if (kind == INPUT_HOST_ACTIVE) {
                        hostActive = session.inputRecords[offset + 1] != 0
                    } else if (kind in INPUT_TOUCH_DOWN..INPUT_POINTER_CAPTURE_LOST) {
                        userKinds = userKinds or (1 shl kind)
                        if (kind == INPUT_POINTER_BUTTON) {
                            pointerButtonStates =
                                pointerButtonStates or
                                    if (session.inputRecords[offset + 2] != 0) {
                                        POINTER_BUTTON_STATE_PRESSED
                                    } else {
                                        POINTER_BUTTON_STATE_RELEASED
                                    }
                        }
                        if (firstUserKind == 0) {
                            firstUserKind = kind
                            firstUserA = session.inputRecords[offset + 1]
                            firstUserB = session.inputRecords[offset + 2]
                        }
                    }
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
        val root = rootSession(session) ?: return
        val compositor = root.compositor ?: return
        if ((userKinds != 0 || hostActive == true) && !activateSessionWindow(session, compositor)) {
            return
        }
        val result = compositor.submitInput(session.inputBuffer, count)
        hostActive?.let { active ->
            session.hostActive = active
            root.audioBridge?.setHostActive(active)
        }
        val newInputKinds = userKinds and session.inputKindsLogged.inv()
        if (newInputKinds != 0) {
            session.inputKindsLogged = session.inputKindsLogged or newInputKinds
            Log.i(
                TAG,
                "Delivered new bounded input kinds=0x${newInputKinds.toString(16)} " +
                    "first=$firstUserKind session=${session.id} " +
                    "a=$firstUserA b=$firstUserB " +
                    "buttonStates=0x${pointerButtonStates.toString(16)} " +
                    "records=$count result=$result",
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
                            "arch-root/run/${session.compositorSocketName}",
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
                                Log.i(
                                    TAG,
                                    "Surface release mode=" +
                                        if (it.usesReleaseAwareBuffers()) {
                                            "API36 per-buffer callback"
                                        } else {
                                            "legacy transaction completion"
                                        },
                                )
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
            session.hostActive = true
            session.audioBridge?.setHostActive(true)
            compositor.setClipboardActive(true)
            session.clipboardRevision = session.clipboardRevision.inc().coerceAtLeast(1)
            val clipboard = synchronized(this) { session.androidClipboard }
            session.offeredAndroidClipboard = clipboard
            if (clipboard == null) {
                compositor.clearAndroidClipboard()
            } else {
                compositor.offerAndroidClipboardText(clipboard.html != null)
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
        allowGpuBridge: Boolean = true,
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
        val cameraEnabled =
            session.authorization.bridgeCapabilities and BRIDGE_CAMERA != 0
        val cameraBridge =
            if (cameraEnabled) {
                session.cameraBridge
                    ?: LauncherCameraBridge(this, session.id).also {
                        session.cameraBridge = it
                    }
            } else {
                null
            }
        val portalBridge =
            session.portalBridge
                ?: runCatching {
                    LauncherPortalBridge(
                        context = this,
                        sessionId = session.id,
                        appName = session.authorization.label,
                        archRoot = File(filesDir, "arch-root"),
                        initialDark = appearance.dark,
                        initialAccent = appearance.accent,
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
                        requestDirectory = { title ->
                            requestPortalDirectoryOpen(session, title)
                        },
                        requestOpenUri = { uri ->
                            notifyOpenUri(session, uri)
                        },
                        requestNotification = { id, title, body ->
                            notifyLinuxNotification(
                                session,
                                NOTIFICATION_OPERATION_POST,
                                id,
                                title,
                                body,
                            )
                        },
                        withdrawNotification = { id ->
                            notifyLinuxNotification(
                                session,
                                NOTIFICATION_OPERATION_WITHDRAW,
                                id,
                                "",
                                "",
                            )
                        },
                        audioInputEnabled =
                            session.authorization.bridgeCapabilities and BRIDGE_AUDIO_INPUT != 0,
                        requestAudioInput = { request ->
                            microphonePermissionResponse(session, request)
                        },
                        printingEnabled =
                            session.authorization.bridgeCapabilities and BRIDGE_PRINTING != 0,
                        requestPrint = { title, descriptor ->
                            notifyPrintPdf(session, title, descriptor)
                        },
                        secretsEnabled =
                            session.authorization.bridgeCapabilities and BRIDGE_SECRETS != 0,
                        requestSecret = { operation, arguments, descriptor ->
                            notifySecret(session, operation, arguments, descriptor)
                        },
                        cameraEnabled = cameraEnabled,
                        cameraPipeWireSocket = cameraBridge?.socketPath,
                        requestCamera = { operation, width, height, front, descriptor ->
                            notifyCamera(
                                session,
                                operation,
                                width,
                                height,
                                front,
                                descriptor,
                            )
                        },
                        accessibilityEnabled = true,
                        publishAccessibilityTree = { descriptor ->
                            notifyAccessibilityTree(session, descriptor)
                        },
                        publishAccessibilityEvent = { nodeId, type ->
                            notifyAccessibilityEvent(session, nodeId, type)
                        },
                        takeAccessibilityAction = { timeoutMillis ->
                            takeAccessibilityAction(session, timeoutMillis)
                        },
                        requestAccessibilityMenu = { nodeId, transition ->
                            notifyAccessibilityMenu(session, nodeId, transition)
                        },
                        importDirectory = { displayName, descriptor, cancellationToken ->
                            runtime.importPortalFolder(displayName, descriptor, cancellationToken)
                        },
                        cancelDirectoryImport = { cancellationToken ->
                            runtime.cancelPortalFolderImport(cancellationToken)
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
        if (!prepareCameraBridge(session, attachedSurface, portalBridge.brokerAddress)) return
        if (!prepareAudioBridge(session, portalBridge.brokerAddress)) return
        val pendingLaunchDocument = session.pendingLaunchDocument
        if (pendingLaunchDocument != null && session.launchDocumentPath == null) {
            if (session.launchDocumentImportStarted) return
            session.launchDocumentImportStarted = true
            Thread(
                    {
                        val imported =
                            runCatching {
                                portalBridge.importLaunchDocument(pendingLaunchDocument)
                            }
                        surfaceHandler.post {
                            session.pendingLaunchDocument = null
                            if (!session.active || session.portalBridge !== portalBridge) {
                                imported.getOrNull()?.let { path ->
                                    Log.i(TAG, "Discarded completed import for closed session path=$path")
                                }
                                return@post
                            }
                            imported
                                .onSuccess { path ->
                                    session.launchDocumentPath = path
                                    Log.i(
                                        TAG,
                                        "Imported Android launch document session=${session.id}",
                                    )
                                    startLinuxProcess(session, attachedSurface, allowGpuBridge)
                                }.onFailure { error ->
                                    Log.e(
                                        TAG,
                                        "Could not import Android launch document session=${session.id}",
                                        error,
                                    )
                                    stopCompositorForStatus(
                                        session,
                                        attachedSurface,
                                        "Could not open the Android document.",
                                    )
                                }
                        }
                    },
                    "ArchpheneLaunchImport-${session.id}",
                ).start()
            return
        }
        val gpuSocket =
            if (allowGpuBridge && session.authorization.usesGraphicsBridge) {
                val bridge =
                    session.gpuBridge
                        ?: AndroidGpuBridge(this, session.id).also {
                            session.gpuBridge = it
                        }
                bridge.start().also { socket ->
                    if (socket == null) {
                        bridge.close()
                        session.gpuBridge = null
                    }
                }
            } else {
                null
            }
        val linuxHandle =
            if (session.quickLaunch) {
                runtime.openQuickLauncherProcess(
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
                    session.reducedIsolationElectron,
                    gpuSocket?.absolutePath,
                    session.audioBridge?.takeIf { it.isReady() }?.serverAddress,
                )
            } else {
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
                    session.reducedIsolationElectron,
                    gpuSocket?.absolutePath,
                    session.launchDocumentPath,
                    session.audioBridge?.takeIf { it.isReady() }?.serverAddress,
                )
            }
        if (linuxHandle == 0L) {
            session.portalBridge?.close()
            session.portalBridge = null
            session.gpuBridge?.close()
            session.gpuBridge = null
            session.audioBridge?.close()
            session.audioBridge = null
            session.cameraBridge?.close()
            session.cameraBridge = null
            Log.e(TAG, "Could not start descriptor process session=${session.id}")
            stopCompositorForStatus(
                session,
                attachedSurface,
                "Could not start ${session.authorization.label}.",
            )
            return
        }
        session.linuxHandle = linuxHandle
        session.audioBridge?.setRuntimeForeground(true)
        session.nextProcessStatusMillis =
            SystemClock.uptimeMillis() + PROCESS_STATUS_DELAY_MILLIS
        Log.i(TAG, "Started manager-owned Linux process session=${session.id}")
    }

    private fun prepareCameraBridge(
        session: Session,
        attachedSurface: Surface,
        brokerAddress: String,
    ): Boolean {
        if (session.authorization.bridgeCapabilities and BRIDGE_CAMERA == 0) return true
        val bridge =
            session.cameraBridge
                ?: LauncherCameraBridge(this, session.id).also {
                    session.cameraBridge = it
                }
        if (bridge.isReady() || bridge.start(brokerAddress)) return true
        bridge.close()
        session.cameraBridge = null
        session.portalBridge?.close()
        session.portalBridge = null
        stopCompositorForStatus(
            session,
            attachedSurface,
            "Could not start ${session.authorization.label}'s camera bridge.",
        )
        return false
    }

    private fun prepareAudioBridge(
        session: Session,
        brokerAddress: String,
    ): Boolean {
        if (session.authorization.bridgeCapabilities and BRIDGE_AUDIO_OUTPUT == 0) return true
        session.audioBridge?.let { bridge ->
            if (bridge.isReady()) return true
            bridge.close()
            session.audioBridge = null
            session.audioStartComplete = false
        }
        if (session.audioStartComplete) return true
        if (session.audioStartInProgress) return false
        session.audioStartInProgress = true
        Thread(
                {
                    val started =
                        runCatching {
                            LauncherAudioBridge(
                                this,
                                session.id,
                                session.compositorSocketName
                                    .substringAfterLast('-')
                                    .substringBeforeLast('.'),
                                session.authorization.bridgeCapabilities and
                                    BRIDGE_AUDIO_INPUT != 0,
                                brokerAddress,
                            ).also { bridge ->
                                bridge.start()
                            }
                        }
                    surfaceHandler.post {
                        session.audioStartInProgress = false
                        session.audioStartComplete = true
                        if (!session.active) {
                            started.getOrNull()?.close()
                            return@post
                        }
                        started
                            .onSuccess { bridge ->
                                session.audioBridge = bridge
                                bridge.setHostActive(session.hostActive)
                            }.onFailure { error ->
                                Log.e(
                                    TAG,
                                    "Audio output unavailable session=${session.id}; " +
                                        "starting Linux app without PulseAudio",
                                    error,
                                )
                            }
                        session.surface?.let { currentSurface ->
                            startLinuxProcess(session, currentSurface)
                        }
                    }
                },
                "ArchpheneAudioStart-${session.id}",
            )
            .start()
        return false
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
            !PortalMimePolicy.valid(mimeType)
        ) {
            return LauncherPortalSaveResult(null, "", false)
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
                    return LauncherPortalSaveResult(null, "", false)
                }
                val requestId =
                    nextDocumentRequestId.getAndUpdate { value ->
                        if (value == Int.MAX_VALUE) 1 else value + 1
                    }
                if (requestId <= 0) {
                    return LauncherPortalSaveResult(null, "", false)
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
                        return LauncherPortalSaveResult(null, "", false)
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
            return LauncherPortalSaveResult(null, "", false)
        }
        return LauncherPortalSaveResult(
            completion.descriptor,
            completion.displayName,
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
            !PortalMimePolicy.valid(mimeType)
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

    private fun requestPortalDirectoryOpen(
        session: Session,
        title: String,
    ): LauncherPortalDirectoryResult {
        if (title.isBlank() || title.length > MAX_DOCUMENT_TITLE_UTF16) {
            return LauncherPortalDirectoryResult(null, "", false)
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
                    return LauncherPortalDirectoryResult(null, "", false)
                }
                val requestId =
                    nextDocumentRequestId.getAndUpdate { value ->
                        if (value == Int.MAX_VALUE) 1 else value + 1
                    }
                if (requestId <= 0) {
                    return LauncherPortalDirectoryResult(null, "", false)
                }
                PendingDocumentRequest(
                    requestId,
                    DOCUMENT_OPERATION_DIRECTORY,
                    title,
                    "",
                    "*/*",
                    null,
                    completion,
                ).also { request ->
                    session.pendingDocumentRequest = request
                    if (!notifyDocumentRequest(session, request)) {
                        session.pendingDocumentRequest = null
                        return LauncherPortalDirectoryResult(null, "", false)
                    }
                }
            }
        Log.i(
            TAG,
            "Portal requested Android directory source session=${session.id} " +
                "request=${pending.id}",
        )
        if (!completion.latch.await(DOCUMENT_REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            synchronized(this) {
                if (session.pendingDocumentRequest === pending) {
                    session.pendingDocumentRequest = null
                }
            }
            return LauncherPortalDirectoryResult(null, "", false)
        }
        return LauncherPortalDirectoryResult(
            completion.descriptor,
            completion.displayName,
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
        val systemDark =
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val liveOverrides = ArchphenePreferences.snapshot().appearance
        val dark =
            LinuxAppearancePreferences.resolveDark(
                systemDark,
                liveOverrides.themeMode,
            )
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
            if (Build.VERSION.SDK_INT >= 31 && liveOverrides.materialYou) {
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
                "theme=${liveOverrides.themeMode} dark=$dark " +
                "materialYou=${liveOverrides.materialYou} " +
                "font=$fontPercent controls=${visualDp}dp target=${targetDp}dp",
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

    @Synchronized
    private fun publishPortalAppearance() {
        val session = sessions.values.firstOrNull() ?: return
        val appearance = resolvedAppearance(session)
        for (activeSession in sessions.values) {
            notifyAppearance(activeSession, appearance)
            activeSession.portalBridge?.updateAppearance(appearance.dark, appearance.accent)
        }
        if (
            runtimeBinder?.updateGuiColors(
                appearance.dark,
                appearance.accent,
                appearance.background,
                appearance.foreground,
            ) == false
        ) {
            Log.w(TAG, "Shared runtime did not accept live Linux appearance")
        }
    }

    private fun requestAppearanceUpdate() {
        if (::appearanceUpdates.isInitialized) appearanceUpdates.offer(Unit)
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
            if (result and NativeLauncherCompositor.FLAG_POINTER_CAPTURE_CHANGED != 0) {
                notifyPointerCapture(session, compositor.pointerCaptureActive())
            }
            if (result and NativeLauncherCompositor.FLAG_CURSOR_CHANGED != 0) {
                val nativeIcon = compositor.cursorSystemIcon()
                val published =
                    if (nativeIcon == CUSTOM_CURSOR_ICON) {
                        notifyCursorBitmap(session, compositor)
                    } else {
                        notifyCursorIcon(session, nativeIcon)
                    }
                if (session.cursorChangesLogged < MAX_CURSOR_CHANGE_LOGS) {
                    session.cursorChangesLogged += 1
                    Log.i(
                        TAG,
                        "Wayland cursor changed session=${session.id} " +
                            "source=${if (nativeIcon < 0) "surface" else "shape"} " +
                            "published=$published" +
                            if (nativeIcon == CUSTOM_CURSOR_ICON) {
                                " size=${compositor.cursorWidth()}x${compositor.cursorHeight()} " +
                                    "hotspot=${compositor.cursorHotspot(0)}," +
                                    compositor.cursorHotspot(1)
                            } else {
                                ""
                            },
                    )
                }
            }
            if (result and NativeLauncherCompositor.FLAG_WINDOWS_CHANGED != 0) {
                publishIndependentWindows(session, compositor)
            }
            pollLinuxProcess(session)
            surfaceHandler.postDelayed(
                this,
                if (clientConnected) COMPOSITOR_ACTIVE_DELAY_MILLIS else COMPOSITOR_IDLE_DELAY_MILLIS,
            )
        }
    }

    private fun publishIndependentWindows(
        session: Session,
        compositor: NativeLauncherCompositor,
    ) {
        val ids = IntArray(MAX_PUBLISHED_WINDOWS)
        var count = 0
        val nativeCount = compositor.windowCount().coerceIn(0, MAX_NATIVE_WINDOWS)
        val diagnostics = StringBuilder(nativeCount.coerceAtMost(MAX_PUBLISHED_WINDOWS) * 24)
        for (index in 0 until nativeCount) {
            val id = compositor.windowComponent(index, WINDOW_COMPONENT_ID)
            val parent = compositor.windowComponent(index, WINDOW_COMPONENT_PARENT)
            val mapped = compositor.windowComponent(index, WINDOW_COMPONENT_MAPPED)
            val active = compositor.windowComponent(index, WINDOW_COMPONENT_ACTIVE)
            val primary = compositor.windowComponent(index, WINDOW_COMPONENT_PRIMARY)
            if (index < MAX_PUBLISHED_WINDOWS) {
                if (diagnostics.isNotEmpty()) diagnostics.append(';')
                diagnostics.append(id).append(',').append(parent).append(',')
                    .append(mapped).append(',').append(active).append(',').append(primary)
            }
            if (id <= 0 || parent != 0 || mapped != 1 || primary == 1) continue
            if (count >= ids.size) break
            ids[count++] = id
        }
        val targets =
            synchronized(this) {
                val root = rootSession(session) ?: return
                root.availableToplevelIds.fill(0)
                ids.copyInto(root.availableToplevelIds, endIndex = count)
                root.availableToplevelCount = count
                sessions.values.filter { candidate ->
                    candidate.rootSessionId == root.id &&
                        candidate.clientActive &&
                        candidate.protocolVersion >= MULTI_WINDOW_PROTOCOL_VERSION
                }
            }
        for (target in targets) {
            notifyWindowList(target, ids, count)
        }
        Log.i(
            TAG,
            "Published Linux windows root=${session.rootSessionId} total=$nativeCount " +
                "independent=$count clients=${targets.size} entries=$diagnostics",
        )
    }

    private fun notifyWindowList(
        session: Session,
        ids: IntArray,
        count: Int,
    ) {
        if (!session.active || !session.clientActive || count !in 0..ids.size) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(session.protocolVersion)
            data.writeInt(session.id)
            data.writeInt(count)
            repeat(count) { index -> data.writeInt(ids[index]) }
            session.clientToken.transact(
                CALLBACK_WINDOWS,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not publish Linux windows session=${session.id}", error)
        } finally {
            data.recycle()
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
        notifyAccessibilityViewport(session)
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
                "${session.presentationComponent(31)} " +
                "presentation=${session.presentationComponent(32)}x" +
                "${session.presentationComponent(33)} " +
                "windowStates=${session.presentationComponent(34)} " +
                "graphics=shmSnapshot:${session.presentationComponent(35)}," +
                "cpuConversion:${session.presentationComponent(39)}," +
                "gpuReadback:${session.presentationComponent(36)}," +
                "textureUpload:${session.presentationComponent(37)}," +
                "gpuComposition:${session.presentationComponent(38)}," +
                "directAhbSubmit:${session.presentationComponent(40)}," +
                "surfaceFlingerRelease:${session.presentationComponent(41)}",
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
            publishLinuxClipboard(session, compositor, revision, null, null)
        }
        if (
            !session.linuxCopyInFlight &&
            events and NativeLauncherCompositor.FLAG_LINUX_COPY_PENDING != 0
        ) {
            val format = compositor.linuxCopyFormat()
            val descriptor = compositor.takeLinuxCopyFd()
            if (
                descriptor >= 0 &&
                format in
                    NativeLauncherCompositor.CLIPBOARD_FORMAT_PLAIN_TEXT..
                    NativeLauncherCompositor.CLIPBOARD_FORMAT_HTML
            ) {
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
                    val admittedLength =
                        if (length < 0) {
                            null
                        } else {
                            runCatching {
                                checkedNativeOutputLength(
                                    length,
                                    session.clipboardReadBuffer.capacity(),
                                    MAX_CLIPBOARD_BYTES,
                                )
                            }.getOrNull()
                        }
                    val content =
                        if (admittedLength != null) {
                            session.clipboardReadBuffer.position(0)
                            ByteArray(admittedLength)
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
                        } else if (content == null) {
                            Log.w(TAG, "Rejected invalid Linux clipboard text session=${session.id}")
                        } else {
                            if (!session.linuxCopyLogged) {
                                session.linuxCopyLogged = true
                                Log.i(
                                    TAG,
                                    "Read first Linux clipboard transfer session=${session.id} " +
                                        "format=$format bytes=$length on $transferThread",
                                )
                            }
                            if (format == NativeLauncherCompositor.CLIPBOARD_FORMAT_HTML) {
                                val plainText = plainTextFromHtml(content)
                                if (plainText == null) {
                                    Log.w(
                                        TAG,
                                        "Rejected Linux HTML clipboard fallback session=${session.id}",
                                    )
                                } else {
                                    publishLinuxClipboard(
                                        session,
                                        compositor,
                                        revision,
                                        plainText,
                                        content,
                                    )
                                }
                            } else {
                                publishLinuxClipboard(
                                    session,
                                    compositor,
                                    revision,
                                    content,
                                    null,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (
            !session.androidPasteInFlight &&
            events and NativeLauncherCompositor.FLAG_ANDROID_PASTE_PENDING != 0
        ) {
            val clipboard = session.offeredAndroidClipboard
            if (clipboard != null) {
                val format = compositor.androidPasteFormat()
                val descriptor = compositor.takeAndroidPasteFd()
                val bytes =
                    when (format) {
                        NativeLauncherCompositor.CLIPBOARD_FORMAT_PLAIN_TEXT ->
                            clipboard.plainText
                        NativeLauncherCompositor.CLIPBOARD_FORMAT_HTML -> clipboard.html
                        else -> null
                    }
                if (descriptor >= 0 && bytes != null) {
                    session.clipboardWriteBuffer.clear()
                    session.clipboardWriteBuffer.put(bytes)
                    session.clipboardWriteBuffer.position(0)
                    session.androidPasteInFlight = true
                    clipboardHandler.post {
                        val result =
                            compositor.writeClipboardFd(
                                descriptor,
                                session.clipboardWriteBuffer,
                                bytes.size,
                                CLIPBOARD_IO_TIMEOUT_MILLIS,
                            )
                        val transferThread = Thread.currentThread().name
                        surfaceHandler.post {
                            session.androidPasteInFlight = false
                            if (result != bytes.size) {
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
                                        "format=$format bytes=$result on $transferThread",
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
            if (session.lastImeDiagnosticActive) {
                Log.i(TAG, "Linux IME state session=${session.id} active=false")
            }
            session.lastImeDiagnosticActive = false
            session.lastImeDiagnosticEvidence = LauncherImeEvidencePolicy.NONE
            notifyImeState(
                session,
                changeSerial,
                null,
                0,
                0,
                0,
                0,
                LauncherImeEvidencePolicy.NONE,
            )
            return
        }
        val byteLength = compositor.imeSurroundingTextLength()
        val hint = compositor.imeStateComponent(IME_COMPONENT_HINT).coerceAtLeast(0)
        val purpose = compositor.imeStateComponent(IME_COMPONENT_PURPOSE).coerceAtLeast(0)
        val cursorRectangleWidth =
            compositor.imeStateComponent(IME_COMPONENT_CURSOR_RECTANGLE_WIDTH)
        val cursorRectangleHeight =
            compositor.imeStateComponent(IME_COMPONENT_CURSOR_RECTANGLE_HEIGHT)
        val editorEvidence =
            LauncherImeEvidencePolicy.classify(
                byteLength,
                hint,
                purpose,
                cursorRectangleWidth,
                cursorRectangleHeight,
            )
        if (
            !session.lastImeDiagnosticActive ||
            session.lastImeDiagnosticEvidence != editorEvidence
        ) {
            Log.i(
                TAG,
                "Linux IME state session=${session.id} active=true " +
                    "editorEvidence=$editorEvidence",
            )
        }
        session.lastImeDiagnosticActive = true
        session.lastImeDiagnosticEvidence = editorEvidence
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
                    notifyImeState(
                        session,
                        changeSerial,
                        "",
                        0,
                        0,
                        hint,
                        purpose,
                        editorEvidence,
                    )
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
                    notifyImeState(
                        session,
                        changeSerial,
                        "",
                        0,
                        0,
                        hint,
                        purpose,
                        editorEvidence,
                    )
                    return
                }
                session.imeBuffer.position(0)
                session.imeBuffer.limit(copied)
                session.imeDecoder.reset()
                runCatching { session.imeDecoder.decode(session.imeBuffer).toString() }
                    .getOrElse {
                        Log.w(TAG, "Rejected invalid IME surrounding text session=${session.id}")
                        notifyImeState(
                            session,
                            changeSerial,
                            "",
                            0,
                            0,
                            hint,
                            purpose,
                            editorEvidence,
                        )
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
            notifyImeState(
                session,
                changeSerial,
                "",
                0,
                0,
                hint,
                purpose,
                editorEvidence,
            )
            return
        }
        notifyImeState(
            session,
            changeSerial,
            text,
            cursor,
            anchor,
            hint,
            purpose,
            editorEvidence,
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

    private fun plainTextFromHtml(html: String): String? =
        runCatching {
            Html
                .fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .takeIf {
                    it.length <= MAX_CLIPBOARD_UTF16 &&
                        utf8LengthAtMost(it, MAX_CLIPBOARD_BYTES)
                }
        }.getOrNull()

    private fun publishLinuxClipboard(
        session: Session,
        compositor: NativeLauncherCompositor,
        revision: Int,
        text: String?,
        html: String?,
    ) {
        val bytes = text?.toByteArray(StandardCharsets.UTF_8)
        val htmlBytes = html?.toByteArray(StandardCharsets.UTF_8)
        if (
            (bytes != null && bytes.size > MAX_CLIPBOARD_BYTES) ||
            (htmlBytes != null && htmlBytes.size > MAX_CLIPBOARD_BYTES) ||
            (bytes == null && htmlBytes != null)
        ) {
            return
        }
        val payload = bytes?.let { ClipboardPayload(it, htmlBytes) }
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
                    session.androidClipboard = payload
                    true
                }
            }
        if (current) {
            Log.i(
                TAG,
                "Accepted bounded Linux clipboard session=${session.id} " +
                    "present=${payload != null} textBytes=${bytes?.size ?: 0} " +
                    "htmlBytes=${htmlBytes?.size ?: 0}",
            )
            notifyClipboard(session, text, html)
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
        if (recoverFromGpuHelperLoss(session, runtime, handle)) {
            return
        }
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
        session.gpuBridge?.close()
        session.gpuBridge = null
        session.audioBridge?.close()
        session.audioBridge = null
        session.cameraBridge?.close()
        session.cameraBridge = null
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

    private fun recoverFromGpuHelperLoss(
        session: Session,
        runtime: ArchpheneRuntimeService.LocalBinder,
        handle: Long,
    ): Boolean {
        val bridge = session.gpuBridge ?: return false
        if (!bridge.failedUnexpectedly()) return false
        val surface = session.surface ?: return false
        if (!runtime.closeLauncherProcess(handle)) {
            Log.e(TAG, "Could not close Linux process after GPU helper loss session=${session.id}")
            return false
        }
        session.linuxHandle = 0L
        bridge.close()
        session.gpuBridge = null
        session.clientLogged = false
        session.frameLogged = false
        session.attachmentFramesLogged = 0
        if (session.gpuRecoveryStage == 0) {
            session.gpuRecoveryStage = 1
            Log.w(TAG, "GPU helper exited; starting one replacement session=${session.id}")
            startLinuxProcess(session, surface)
        } else {
            session.gpuRecoveryStage = 2
            Log.w(TAG, "Replacement GPU helper exited; using llvmpipe session=${session.id}")
            startLinuxProcess(session, surface, allowGpuBridge = false)
        }
        return true
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
        session.compositorSocket = null
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
        val target = callbackSession(session) ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(state)
            data.writeString(message.take(256))
            target.clientToken.transact(CALLBACK_STATUS, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver launcher status session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun notifyAppearance(
        session: Session,
        appearance: ResolvedAppearance,
    ) {
        if (!session.active || !session.clientActive) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(session.protocolVersion)
            data.writeInt(session.id)
            data.writeInt(if (appearance.dark) 1 else 0)
            data.writeInt(appearance.background)
            data.writeInt(appearance.foreground)
            session.clientToken.transact(
                CALLBACK_APPEARANCE,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(
                TAG,
                "Could not deliver launcher appearance session=${session.id}",
                error,
            )
        } finally {
            data.recycle()
        }
    }

    private fun notifyClipboard(
        session: Session,
        text: String?,
        html: String?,
    ) {
        if (
            !session.active ||
            (text != null && text.length > MAX_CLIPBOARD_UTF16) ||
            (html != null && html.length > MAX_CLIPBOARD_UTF16) ||
            (text == null && html != null)
        ) {
            return
        }
        val target = callbackSession(session) ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(if (text == null) 0 else 1)
            if (text != null) {
                data.writeString(text)
                data.writeInt(if (html == null) 0 else 1)
                if (html != null) {
                    data.writeString(html)
                }
            }
            target.clientToken.transact(CALLBACK_CLIPBOARD, data, null, IBinder.FLAG_ONEWAY)
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
        editorEvidence: Int,
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
        val target = callbackSession(session) ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(if (text == null) 0 else 1)
            data.writeInt(revision)
            if (text != null) {
                data.writeString(text)
                data.writeInt(cursor)
                data.writeInt(anchor)
                data.writeInt(hint)
                data.writeInt(purpose)
                if (target.protocolVersion >= IME_EDITOR_EVIDENCE_LEVEL_PROTOCOL_VERSION) {
                    data.writeInt(editorEvidence)
                } else if (target.protocolVersion >= IME_EDITOR_EVIDENCE_PROTOCOL_VERSION) {
                    data.writeInt(if (editorEvidence == LauncherImeEvidencePolicy.STRONG) 1 else 0)
                }
            }
            target.clientToken.transact(CALLBACK_IME_STATE, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver launcher IME state session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun notifyPointerCapture(
        session: Session,
        active: Boolean,
    ) {
        if (!session.active) {
            return
        }
        val target = callbackSession(session) ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(if (active) 1 else 0)
            target.clientToken.transact(
                CALLBACK_POINTER_CAPTURE,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver pointer-capture state session=${session.id}", error)
        } finally {
            data.recycle()
        }
    }

    private fun notifyCursorIcon(
        session: Session,
        systemIcon: Int,
    ): Boolean {
        if (!session.active || !validCursorSystemIcon(systemIcon)) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        return try {
            writeCursorCallbackHeader(data, target, CURSOR_KIND_SYSTEM)
            data.writeInt(systemIcon)
            target.clientToken.transact(
                CALLBACK_CURSOR,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver cursor icon session=${session.id}", error)
            false
        } finally {
            data.recycle()
        }
    }

    private fun notifyCursorBitmap(
        session: Session,
        compositor: NativeLauncherCompositor,
    ): Boolean {
        if (!session.active) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val width = compositor.cursorWidth()
        val height = compositor.cursorHeight()
        if (
            width !in 1..MAX_CURSOR_DIMENSION ||
            height !in 1..MAX_CURSOR_DIMENSION ||
            width.toLong() * height.toLong() > MAX_CURSOR_PIXELS
        ) {
            return notifyCursorIcon(session, if (width == 0 || height == 0) 0 else ANDROID_CURSOR_ARROW)
        }
        val bitmap =
            runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return notifyCursorIcon(session, ANDROID_CURSOR_ARROW)
        if (!compositor.copyCursor(bitmap)) {
            bitmap.recycle()
            return notifyCursorIcon(session, ANDROID_CURSOR_ARROW)
        }
        val hotspotX = compositor.cursorHotspot(0).coerceIn(0, width - 1)
        val hotspotY = compositor.cursorHotspot(1).coerceIn(0, height - 1)
        val data = Parcel.obtain()
        return try {
            writeCursorCallbackHeader(data, target, CURSOR_KIND_BITMAP)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(hotspotX)
            data.writeInt(hotspotY)
            bitmap.writeToParcel(data, 0)
            target.clientToken.transact(
                CALLBACK_CURSOR,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver cursor bitmap session=${session.id}", error)
            false
        } finally {
            data.recycle()
            bitmap.recycle()
        }
    }

    @Synchronized
    private fun microphonePermissionResponse(
        session: Session,
        request: Boolean,
    ): String {
        if (
            !session.active ||
            session.authorization.bridgeCapabilities and BRIDGE_AUDIO_INPUT == 0
        ) {
            return "ERROR\tUNSUPPORTED"
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return "ERROR\tUNAVAILABLE"
        }
        if (
            session.microphonePermissionState == MICROPHONE_PERMISSION_GRANTED &&
            microphoneForegroundSessions.contains(session.id) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            synchronized(this) {
                session.microphonePermissionToken = null
            }
            return "OK"
        }
        synchronized(this) {
            when (session.microphonePermissionState) {
                MICROPHONE_PERMISSION_PENDING -> return "ERROR\tPERMISSION_REQUESTED"
                MICROPHONE_PERMISSION_DENIED -> return "ERROR\tPERMISSION_DENIED"
            }
            if (!request) return "ERROR\tPERMISSION_NOT_REQUESTED"
            val token = randomPermissionToken()
            session.microphonePermissionState = MICROPHONE_PERMISSION_PENDING
            session.microphonePermissionToken = token
            val permissionIntent =
                Intent(this, MicrophonePermissionActivity::class.java)
                    .setAction(ACTION_MICROPHONE_PERMISSION)
                    .setData(Uri.parse("archphene://microphone/$token"))
                    .putExtra(EXTRA_MICROPHONE_SESSION, session.id)
                    .putExtra(EXTRA_MICROPHONE_TOKEN, token)
                    .putExtra(EXTRA_MICROPHONE_LABEL, session.authorization.label)
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    session.id,
                    permissionIntent,
                    PendingIntent.FLAG_ONE_SHOT or
                        PendingIntent.FLAG_CANCEL_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE,
                    microphonePermissionCreatorOptions(),
                )
            if (!notifyMicrophonePermission(session, pendingIntent)) {
                pendingIntent.cancel()
                session.microphonePermissionState = MICROPHONE_PERMISSION_NONE
                session.microphonePermissionToken = null
                return "ERROR\tFAILED"
            }
        }
        Log.i(TAG, "Requested manager microphone consent session=${session.id}")
        return "ERROR\tPERMISSION_REQUESTED"
    }

    @Synchronized
    private fun completeMicrophonePermission(
        sessionId: Int,
        token: String,
        reportedGranted: Boolean,
    ) {
        val session = sessions[sessionId] ?: return
        val expected = session.microphonePermissionToken ?: return
        val matching =
            MessageDigest.isEqual(
                token.toByteArray(StandardCharsets.US_ASCII),
                expected.toByteArray(StandardCharsets.US_ASCII),
            )
        if (
            !matching ||
            session.microphonePermissionState != MICROPHONE_PERMISSION_PENDING
        ) {
            Log.e(TAG, "Rejected stale microphone result session=$sessionId")
            return
        }
        var granted =
            reportedGranted &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            granted =
                runCatching {
                    startMicrophoneForeground(session)
                    true
                }.getOrElse { error ->
                    Log.e(TAG, "Could not start microphone foreground session=$sessionId", error)
                    false
                }
        }
        session.microphonePermissionState =
            if (granted) {
                MICROPHONE_PERMISSION_GRANTED
            } else {
                MICROPHONE_PERMISSION_DENIED
            }
        session.microphonePermissionToken = null
        Log.i(
            TAG,
            "Manager microphone consent session=$sessionId " +
                "result=${if (granted) "granted" else "denied"}",
        )
    }

    private fun startMicrophoneForeground(session: Session) {
        val channel =
            NotificationChannel(
                MICROPHONE_NOTIFICATION_CHANNEL,
                getString(R.string.microphone_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val openManager =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, MICROPHONE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_archphene)
                .setContentTitle(getString(R.string.microphone_notification_title))
                .setContentText(
                    getString(
                        R.string.microphone_notification_message,
                        session.authorization.label,
                    ),
                ).setContentIntent(openManager)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build()
        startForeground(
            MICROPHONE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        microphoneForegroundSessions.add(session.id)
        Log.i(TAG, "Microphone foreground active session=${session.id}")
    }

    private fun releaseMicrophoneForeground(session: Session) {
        if (!microphoneForegroundSessions.remove(session.id)) return
        session.microphonePermissionState = MICROPHONE_PERMISSION_NONE
        session.microphonePermissionToken = null
        if (microphoneForegroundSessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "Microphone foreground stopped")
        }
    }

    private fun notifyMicrophonePermission(
        session: Session,
        permissionIntent: PendingIntent,
    ): Boolean {
        if (
            !session.active ||
            session.authorization.bridgeCapabilities and BRIDGE_AUDIO_INPUT == 0
        ) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            permissionIntent.writeToParcel(data, 0)
            target.clientToken.transact(
                CALLBACK_MICROPHONE_PERMISSION,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver microphone consent session=${session.id}", error)
            false
        } finally {
            data.recycle()
        }
    }

    private fun randomPermissionToken(): String =
        ByteArray(MICROPHONE_TOKEN_BYTES).also(permissionRandom::nextBytes)
            .joinToString("") { value ->
                "%02x".format(value.toInt() and 0xff)
            }

    @Suppress("DEPRECATION")
    private fun microphonePermissionCreatorOptions() =
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    },
                ).toBundle()
        } else {
            null
        }

    private fun notifyOpenUri(
        session: Session,
        uri: String,
    ): Boolean {
        if (!session.active || !PortalUriPolicy.valid(uri)) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeString(uri)
            target.clientToken.transact(
                CALLBACK_OPEN_URI,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver Android URI session=${session.id}", error)
            false
        } finally {
            data.recycle()
        }
    }

    private fun notifyLinuxNotification(
        session: Session,
        operation: Int,
        id: String,
        title: String,
        body: String,
    ): Boolean {
        if (
            !session.active ||
            operation !in NOTIFICATION_OPERATION_POST..NOTIFICATION_OPERATION_WITHDRAW
        ) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(operation)
            data.writeString(id)
            data.writeString(title)
            data.writeString(body)
            target.clientToken.transact(
                CALLBACK_NOTIFICATION,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver Linux notification session=${session.id}", error)
            false
        } finally {
            data.recycle()
        }
    }

    private fun notifyPrintPdf(
        session: Session,
        title: String,
        descriptor: ParcelFileDescriptor,
    ): Boolean {
        if (
            !session.active ||
            session.authorization.bridgeCapabilities and BRIDGE_PRINTING == 0 ||
            title.isBlank() ||
            title.length > MAX_PRINT_TITLE_UTF16 ||
            !utf8LengthAtMost(title, MAX_PRINT_TITLE_BYTES) ||
            title.any { character -> character.isISOControl() }
        ) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeString(title)
            descriptor.writeToParcel(data, 0)
            if (
                !target.clientToken.transact(
                    CALLBACK_PRINT_PDF,
                    data,
                    reply,
                    0,
                )
            ) {
                false
            } else {
                reply.readException()
                reply.readInt() == RESULT_OK && reply.dataAvail() == 0
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver Linux print request session=${session.id}", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun notifySecret(
        session: Session,
        operation: String,
        arguments: List<String>,
        descriptor: ParcelFileDescriptor?,
    ): String {
        if (
            !session.active ||
            session.authorization.bridgeCapabilities and BRIDGE_SECRETS == 0
        ) {
            return "ERROR\tFAILED"
        }
        val target = callbackSession(session) ?: return "ERROR\tFAILED"
        val operationCode =
            when (operation) {
                "STORE_SECRET" -> SECRET_OPERATION_STORE
                "READ_SECRET" -> SECRET_OPERATION_READ
                "DELETE_SECRET" -> SECRET_OPERATION_DELETE
                "LIST_SECRETS" -> SECRET_OPERATION_LIST
                "CATALOG_SECRETS" -> SECRET_OPERATION_CATALOG
                else -> return "ERROR\tINVALID_REQUEST"
            }
        val descriptorRequired = operationCode != SECRET_OPERATION_DELETE
        if (
            descriptorRequired != (descriptor != null) ||
            arguments.size > MAX_SECRET_ARGUMENTS ||
            arguments.any { it.length > MAX_SECRET_ARGUMENT_UTF16 }
        ) {
            return "ERROR\tINVALID_REQUEST"
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(operationCode)
            data.writeInt(arguments.size)
            for (argument in arguments) data.writeString(argument)
            data.writeInt(if (descriptor == null) 0 else 1)
            descriptor?.writeToParcel(data, 0)
            if (
                data.dataSize() > MAX_SECRET_CALLBACK_PARCEL_BYTES ||
                !target.clientToken.transact(CALLBACK_SECRET, data, reply, 0)
            ) {
                "ERROR\tFAILED"
            } else {
                reply.readException()
                val response = reply.readString()
                if (
                    response == null ||
                    response.length > MAX_SECRET_RESPONSE_BYTES ||
                    reply.dataAvail() != 0
                ) {
                    "ERROR\tFAILED"
                } else {
                    response
                }
            }
        } catch (error: RemoteException) {
            Log.w(
                TAG,
                "Could not deliver Linux secret request session=${session.id} " +
                    "operation=$operation",
                error,
            )
            "ERROR\tFAILED"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun notifyCamera(
        session: Session,
        operation: String,
        width: Int,
        height: Int,
        front: Boolean,
        descriptor: ParcelFileDescriptor?,
    ): String {
        if (
            !session.active ||
            session.authorization.bridgeCapabilities and BRIDGE_CAMERA == 0
        ) {
            return "ERROR\tFAILED"
        }
        val target = callbackSession(session) ?: return "ERROR\tFAILED"
        val operationCode =
            when (operation) {
                "REQUEST_CAMERA" -> CAMERA_OPERATION_REQUEST
                "CHECK_CAMERA" -> CAMERA_OPERATION_CHECK
                "CAPTURE_CAMERA_JPEG" -> CAMERA_OPERATION_CAPTURE
                "STREAM_CAMERA_I420" -> CAMERA_OPERATION_STREAM
                else -> return "ERROR\tINVALID_REQUEST"
            }
        val descriptorRequired =
            operationCode == CAMERA_OPERATION_CAPTURE ||
                operationCode == CAMERA_OPERATION_STREAM
        if (
            descriptorRequired != (descriptor != null) ||
            (
                descriptorRequired &&
                    (width !in 1..MAX_CAMERA_DIMENSION || height !in 1..MAX_CAMERA_DIMENSION)
            ) ||
            (!descriptorRequired && (width != 0 || height != 0 || front))
        ) {
            return "ERROR\tINVALID_REQUEST"
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(operationCode)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(if (front) CAMERA_FACING_FRONT else CAMERA_FACING_BACK)
            data.writeInt(if (descriptor == null) 0 else 1)
            descriptor?.writeToParcel(data, 0)
            if (
                data.dataSize() > MAX_CAMERA_CALLBACK_PARCEL_BYTES ||
                !target.clientToken.transact(CALLBACK_CAMERA, data, reply, 0)
            ) {
                "ERROR\tFAILED"
            } else {
                reply.readException()
                val response = reply.readString()
                if (
                    response == null ||
                    response.length > MAX_CAMERA_RESPONSE_BYTES ||
                    reply.dataAvail() != 0
                ) {
                    "ERROR\tFAILED"
                } else {
                    response
                }
            }
        } catch (error: RemoteException) {
            Log.w(
                TAG,
                "Could not deliver Linux camera request session=${session.id} " +
                    "operation=$operation",
                error,
            )
            "ERROR\tFAILED"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun notifyAccessibilityTree(
        session: Session,
        descriptor: ParcelFileDescriptor,
    ): Boolean {
        if (!session.active) return false
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(ACCESSIBILITY_CALLBACK_TREE)
            descriptor.writeToParcel(data, 0)
            if (
                data.dataSize() > MAX_ACCESSIBILITY_CALLBACK_PARCEL_BYTES ||
                !target.clientToken.transact(
                    CALLBACK_ACCESSIBILITY,
                    data,
                    reply,
                    0,
                )
            ) {
                false
            } else {
                reply.readException()
                reply.readInt() == RESULT_OK && reply.dataAvail() == 0
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not publish accessibility tree session=${session.id}", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun notifyAccessibilityViewport(session: Session) {
        if (!session.active) return
        val target = callbackSession(session) ?: return
        val independent = target.toplevelId != 0
        val presentationWidth =
            if (independent) target.logicalWidth else session.presentationComponent(32)
        val presentationHeight =
            if (independent) target.logicalHeight else session.presentationComponent(33)
        val destinationX = if (independent) 0 else session.presentationComponent(28)
        val destinationY = if (independent) 0 else session.presentationComponent(29)
        val destinationWidth =
            if (independent) target.logicalWidth else session.presentationComponent(30)
        val destinationHeight =
            if (independent) target.logicalHeight else session.presentationComponent(31)
        if (
            presentationWidth !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
            presentationHeight !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
            destinationX !in -MAX_ACCESSIBILITY_VIEWPORT..MAX_ACCESSIBILITY_VIEWPORT ||
            destinationY !in -MAX_ACCESSIBILITY_VIEWPORT..MAX_ACCESSIBILITY_VIEWPORT ||
            destinationWidth !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
            destinationHeight !in 1..MAX_ACCESSIBILITY_VIEWPORT
        ) {
            return
        }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(presentationWidth)
            data.writeInt(presentationHeight)
            data.writeInt(destinationX)
            data.writeInt(destinationY)
            data.writeInt(destinationWidth)
            data.writeInt(destinationHeight)
            target.clientToken.transact(
                CALLBACK_ACCESSIBILITY_VIEWPORT,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
        } catch (error: RemoteException) {
            Log.w(
                TAG,
                "Could not publish accessibility viewport session=${session.id}",
                error,
            )
        } finally {
            data.recycle()
        }
    }

    private fun notifyAccessibilityEvent(
        session: Session,
        nodeId: Int,
        type: String,
    ): Boolean =
        notifyAccessibilityControl(
            session,
            ACCESSIBILITY_CALLBACK_EVENT,
            nodeId,
            type,
            false,
        )

    private fun notifyAccessibilityMenu(
        session: Session,
        nodeId: Int,
        transition: Boolean,
    ): Boolean =
        notifyAccessibilityControl(
            session,
            ACCESSIBILITY_CALLBACK_MENU,
            nodeId,
            "",
            transition,
        )

    private fun notifyAccessibilityControl(
        session: Session,
        operation: Int,
        nodeId: Int,
        type: String,
        transition: Boolean,
    ): Boolean {
        val minimumNodeId =
            if (operation == ACCESSIBILITY_CALLBACK_EVENT) 0 else 1
        if (
            !session.active ||
            nodeId !in minimumNodeId..MAX_ACCESSIBILITY_NODE_ID
        ) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(operation)
            data.writeInt(nodeId)
            data.writeString(type)
            data.writeInt(if (transition) 1 else 0)
            if (
                data.dataSize() > MAX_ACCESSIBILITY_CALLBACK_PARCEL_BYTES ||
                !target.clientToken.transact(
                    CALLBACK_ACCESSIBILITY,
                    data,
                    reply,
                    0,
                )
            ) {
                false
            } else {
                reply.readException()
                reply.readInt() == RESULT_OK && reply.dataAvail() == 0
            }
        } catch (error: RemoteException) {
            Log.w(
                TAG,
                "Could not publish accessibility callback session=${session.id}",
                error,
            )
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun takeAccessibilityAction(
        session: Session,
        timeoutMillis: Int,
    ): String {
        if (
            !session.active ||
            timeoutMillis !in 0..MAX_ACCESSIBILITY_POLL_MILLIS
        ) {
            return "ERROR\tEMPTY"
        }
        val action =
            if (timeoutMillis == 0) {
                session.accessibilityActions.poll()
            } else {
                session.accessibilityActions.poll(
                    timeoutMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
            } ?: return "ERROR\tEMPTY"
        val encoded =
            Base64.encodeToString(
                action.text.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        return "OK\t${action.nodeId}\t${action.action}\t$encoded"
    }

    private fun writeCursorCallbackHeader(
        data: Parcel,
        session: Session,
        kind: Int,
    ) {
        data.writeInterfaceToken(CALLBACK_INTERFACE)
        data.writeInt(session.protocolVersion)
        data.writeInt(session.id)
        data.writeInt(kind)
    }

    private fun validCursorSystemIcon(systemIcon: Int): Boolean =
        systemIcon == 0 || systemIcon in 1000..1004 || systemIcon in 1006..1021

    private fun notifyDocumentRequest(
        session: Session,
        request: PendingDocumentRequest,
    ): Boolean {
        if (
            !session.active ||
            request.id <= 0 ||
            request.title.isBlank() ||
            request.operation !in
                DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY ||
            (request.operation == DOCUMENT_OPERATION_SAVE && request.suggestedName.isBlank()) ||
            !PortalMimePolicy.valid(request.mimeType)
        ) {
            return false
        }
        val target = callbackSession(session) ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(target.protocolVersion)
            data.writeInt(target.id)
            data.writeInt(request.id)
            data.writeInt(request.operation)
            data.writeString(request.title)
            data.writeString(request.suggestedName)
            data.writeString(request.mimeType)
            target.clientToken.transact(
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

    private fun notifyLaunchDocumentWriteback(session: Session): Boolean {
        if (
            session.protocolVersion < EDIT_DOCUMENT_PROTOCOL_VERSION ||
            !session.launchDocumentWritable ||
            !session.clientActive
        ) {
            return false
        }
        val path = session.launchDocumentPath ?: return false
        val bridge = session.portalBridge ?: return false
        val descriptor =
            runCatching { bridge.openLaunchDocumentForWriteback(path) }
                .getOrElse { error ->
                    Log.e(TAG, "Could not open edited launch document session=${session.id}", error)
                    return false
                }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CALLBACK_INTERFACE)
            data.writeInt(session.protocolVersion)
            data.writeInt(session.id)
            descriptor.writeToParcel(data, 0)
            val delivered =
                session.clientToken.transact(
                    CALLBACK_EDIT_WRITEBACK,
                    data,
                    reply,
                    0,
                )
            val accepted =
                if (delivered) {
                    reply.readException()
                    reply.readInt() == RESULT_OK && reply.dataAvail() == 0
                } else {
                    false
                }
            if (accepted) {
                Log.i(TAG, "Requested Android edit writeback session=${session.id}")
            } else {
                Log.w(TAG, "Could not request Android edit writeback session=${session.id}")
            }
            accepted
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not request Android edit writeback session=${session.id}", error)
            false
        } finally {
            descriptor.close()
            reply.recycle()
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
        val request = rootSession(session)?.pendingDocumentRequest ?: return 0
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
        displayName: String,
    ): Int {
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        val root = rootSession(session) ?: return RESULT_NOT_READY
        val request = root.pendingDocumentRequest ?: return RESULT_INVALID
        val openOperation =
            request.operation == DOCUMENT_OPERATION_OPEN ||
                request.operation == DOCUMENT_OPERATION_OPEN_MULTIPLE
        val successShape =
            when {
                openOperation ->
                    descriptor == null &&
                        displayName.isEmpty() &&
                        documents.size in 1..MAX_OPEN_DOCUMENTS &&
                        documents.all { document -> safeDocumentName(document.displayName) } &&
                        (request.operation == DOCUMENT_OPERATION_OPEN_MULTIPLE ||
                            documents.size == 1)
                request.operation == DOCUMENT_OPERATION_DIRECTORY ->
                    descriptor != null &&
                        documents.isEmpty() &&
                        safeDocumentName(displayName)
                request.operation == DOCUMENT_OPERATION_SAVE ->
                    descriptor != null &&
                        documents.isEmpty() &&
                        safeDocumentName(displayName)
                else -> descriptor != null && documents.isEmpty() && displayName.isEmpty()
            }
        if (
            request.id != requestId ||
            result !in DOCUMENT_RESULT_SUCCESS..DOCUMENT_RESULT_FAILED ||
            (result == DOCUMENT_RESULT_SUCCESS) != successShape ||
            (result != DOCUMENT_RESULT_SUCCESS &&
                (descriptor != null || documents.isNotEmpty() || displayName.isNotEmpty()))
        ) {
            return RESULT_INVALID
        }
        root.pendingDocumentRequest = null
        val portalCompletion = request.portalCompletion
        if (portalCompletion != null) {
            if (
                !portalCompletion.complete(
                    result,
                    if (result == DOCUMENT_RESULT_SUCCESS) descriptor else null,
                    if (result == DOCUMENT_RESULT_SUCCESS) documents else emptyList(),
                    if (result == DOCUMENT_RESULT_SUCCESS) displayName else "",
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
            boundedUtf8Text(name, MAX_DOCUMENT_NAME_BYTES) &&
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

    private fun newCompositorSocketName(sessionId: Int): String {
        val randomBytes = ByteArray(COMPOSITOR_SOCKET_TOKEN_BYTES)
        permissionRandom.nextBytes(randomBytes)
        val token = CharArray(randomBytes.size * 2)
        for (index in randomBytes.indices) {
            val value = randomBytes[index].toInt() and 0xff
            token[index * 2] = HEX_DIGITS[value ushr 4]
            token[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return "launcher-$sessionId-${String(token)}.sock"
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
        val session = authorizedSession(callingUid, sessionId) ?: return RESULT_UNAUTHORIZED
        if (session.rootSessionId == session.id) {
            notifyLaunchDocumentWriteback(session)
        }
        removeSession(sessionId)
        Log.i(TAG, "Closed launcher session=$sessionId")
        return RESULT_OK
    }

    companion object {
        private const val TAG = "ArchpheneLauncherSession"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val LAUNCHER_PACKAGE_PREFIX = "org.archphene.linux.p"
        private const val INTERFACE = "org.archphene.launcher.ISessionV2"
        private const val PROTOCOL_VERSION = 21
        private const val MINIMUM_PROTOCOL_VERSION = 16
        private const val MULTI_WINDOW_PROTOCOL_VERSION = 20
        private const val EDIT_DOCUMENT_PROTOCOL_VERSION = 21
        private const val INPUT_LOGICAL_COORDINATE_PROTOCOL_VERSION = 18
        private const val IME_EDITOR_EVIDENCE_PROTOCOL_VERSION = 17
        private const val IME_EDITOR_EVIDENCE_LEVEL_PROTOCOL_VERSION = 19
        private const val ORIENTATION_POLICY_DEFAULT = 0
        private const val ORIENTATION_POLICY_SDL_PHONE = 1
        private const val TRANSACTION_OPEN = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_ATTACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_DETACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_INPUT = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_IME = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_DOCUMENT_RESULT = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val TRANSACTION_ACCESSIBILITY_ACTION =
            IBinder.FIRST_CALL_TRANSACTION + 8
        private const val TRANSACTION_ACTIVATE_NEXT_WINDOW =
            IBinder.FIRST_CALL_TRANSACTION + 9
        private const val TRANSACTION_RELEASE_WINDOW_TASK =
            IBinder.FIRST_CALL_TRANSACTION + 10
        private const val TRANSACTION_CLOSE_APPLICATION =
            IBinder.FIRST_CALL_TRANSACTION + 11
        private const val TRANSACTION_OPEN_QUICK = IBinder.FIRST_CALL_TRANSACTION + 12
        private const val CALLBACK_INTERFACE = "org.archphene.launcher.IClientV2"
        private const val CALLBACK_STATUS = IBinder.FIRST_CALL_TRANSACTION
        private const val CALLBACK_CLIPBOARD = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CALLBACK_IME_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CALLBACK_DOCUMENT_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CALLBACK_POINTER_CAPTURE = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val CALLBACK_CURSOR = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val CALLBACK_OPEN_URI = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val CALLBACK_NOTIFICATION = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val CALLBACK_PRINT_PDF = IBinder.FIRST_CALL_TRANSACTION + 8
        private const val CALLBACK_MICROPHONE_PERMISSION =
            IBinder.FIRST_CALL_TRANSACTION + 9
        private const val CALLBACK_SECRET = IBinder.FIRST_CALL_TRANSACTION + 10
        private const val CALLBACK_CAMERA = IBinder.FIRST_CALL_TRANSACTION + 11
        private const val CALLBACK_APPEARANCE = IBinder.FIRST_CALL_TRANSACTION + 12
        private const val CALLBACK_ACCESSIBILITY = IBinder.FIRST_CALL_TRANSACTION + 13
        private const val CALLBACK_ACCESSIBILITY_VIEWPORT =
            IBinder.FIRST_CALL_TRANSACTION + 14
        private const val CALLBACK_WINDOWS = IBinder.FIRST_CALL_TRANSACTION + 15
        private const val CALLBACK_EDIT_WRITEBACK = IBinder.FIRST_CALL_TRANSACTION + 16
        private const val ACCESSIBILITY_CALLBACK_TREE = 1
        private const val ACCESSIBILITY_CALLBACK_EVENT = 2
        private const val ACCESSIBILITY_CALLBACK_MENU = 3
        private const val MAX_ACCESSIBILITY_ACTIONS = 64
        private const val MAX_ACCESSIBILITY_NODE_ID = 1_000_000
        private const val MAX_ACCESSIBILITY_VIEWPORT = 16_384
        private const val MAX_ACCESSIBILITY_POLL_MILLIS = 250
        private const val MAX_ACCESSIBILITY_TEXT_UTF16 = 1_024
        private const val MAX_ACCESSIBILITY_TEXT_BYTES = 4_096
        private const val MAX_ACCESSIBILITY_ACTION_PARCEL_BYTES = 16 * 1_024
        private const val MAX_ACCESSIBILITY_CALLBACK_PARCEL_BYTES = 1_024
        private val ACCESSIBILITY_ACTIONS =
            setOf(
                "click",
                "focus",
                "set-text",
                "scroll-forward",
                "scroll-backward",
                "refresh",
            )
        private const val SECRET_OPERATION_STORE = 1
        private const val SECRET_OPERATION_READ = 2
        private const val SECRET_OPERATION_DELETE = 3
        private const val SECRET_OPERATION_LIST = 4
        private const val SECRET_OPERATION_CATALOG = 5
        private const val MAX_SECRET_ARGUMENTS = 4
        private const val MAX_SECRET_ARGUMENT_UTF16 = 8 * 1_024
        private const val MAX_SECRET_CALLBACK_PARCEL_BYTES = 48 * 1_024
        private const val MAX_SECRET_RESPONSE_BYTES = 16 * 1_024
        private const val CAMERA_OPERATION_REQUEST = 1
        private const val CAMERA_OPERATION_CHECK = 2
        private const val CAMERA_OPERATION_CAPTURE = 3
        private const val CAMERA_OPERATION_STREAM = 4
        private const val CAMERA_FACING_BACK = 0
        private const val CAMERA_FACING_FRONT = 1
        private const val MAX_CAMERA_DIMENSION = 8_192
        private const val MAX_CAMERA_CALLBACK_PARCEL_BYTES = 1_024
        private const val MAX_CAMERA_RESPONSE_BYTES = 128
        private const val BRIDGE_AUDIO_OUTPUT = 1 shl 0
        private const val BRIDGE_PRINTING = 1 shl 1
        private const val BRIDGE_CAMERA = 1 shl 2
        private const val BRIDGE_SECRETS = 1 shl 3
        private const val BRIDGE_AUDIO_INPUT = 1 shl 4
        private const val MICROPHONE_PERMISSION_NONE = 0
        private const val MICROPHONE_PERMISSION_PENDING = 1
        private const val MICROPHONE_PERMISSION_DENIED = 2
        private const val MICROPHONE_PERMISSION_GRANTED = 3
        private const val MICROPHONE_TOKEN_BYTES = 16
        private const val COMPOSITOR_SOCKET_TOKEN_BYTES = 8
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val MICROPHONE_NOTIFICATION_ID = 7_202
        private const val MICROPHONE_NOTIFICATION_CHANNEL = "linux-microphone"
        private const val DEBUG_MICROPHONE_MINIMUM_BYTES = 76_800
        private const val DEBUG_MICROPHONE_MINIMUM_NONZERO_BYTES = 1_000
        internal const val ACTION_MICROPHONE_PERMISSION =
            "org.archphene.action.MICROPHONE_PERMISSION"
        internal const val ACTION_MICROPHONE_RESULT =
            "org.archphene.action.MICROPHONE_RESULT"
        internal const val EXTRA_MICROPHONE_SESSION =
            "org.archphene.extra.MICROPHONE_SESSION"
        internal const val EXTRA_MICROPHONE_TOKEN =
            "org.archphene.extra.MICROPHONE_TOKEN"
        internal const val EXTRA_MICROPHONE_LABEL =
            "org.archphene.extra.MICROPHONE_LABEL"
        internal const val EXTRA_MICROPHONE_GRANTED =
            "org.archphene.extra.MICROPHONE_GRANTED"
        private const val NOTIFICATION_OPERATION_POST = 1
        private const val NOTIFICATION_OPERATION_WITHDRAW = 2
        private const val MAX_SESSIONS = 16
        private const val MAX_NATIVE_WINDOWS = 32
        private const val MAX_PUBLISHED_WINDOWS = 8
        private const val WINDOW_COMPONENT_ID = 0
        private const val WINDOW_COMPONENT_PARENT = 1
        private const val WINDOW_COMPONENT_MAPPED = 2
        private const val WINDOW_COMPONENT_ACTIVE = 3
        private const val WINDOW_COMPONENT_PRIMARY = 4
        private const val MAX_PRINT_TITLE_UTF16 = 256
        private const val MAX_PRINT_TITLE_BYTES = 512
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
        private const val SESSION_RECONNECT_GRACE_MILLIS = 15_000L
        private const val PROCESS_STATUS_DELAY_MILLIS = 500L
        private const val PROCESS_LOGCAT_CHUNK_LENGTH = 1800
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private const val MAX_INPUT_RECORDS = 32
        private const val MAX_ATTACHMENT_FRAME_LOGS = 4
        private const val MAX_CURSOR_CHANGE_LOGS = 4
        private const val CUSTOM_CURSOR_ICON = -1
        private const val ANDROID_CURSOR_ARROW = 1000
        private const val CURSOR_KIND_SYSTEM = 0
        private const val CURSOR_KIND_BITMAP = 1
        private const val MAX_CURSOR_DIMENSION = 256
        private const val MAX_CURSOR_PIXELS = 65_536L
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
        private const val MAX_RELATIVE_FIXED = 16_384_000
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
        private const val INPUT_POINTER_RELATIVE = 11
        private const val INPUT_POINTER_CAPTURE_LOST = 12
        private const val POINTER_BUTTON_STATE_PRESSED = 1
        private const val POINTER_BUTTON_STATE_RELEASED = 2

        internal fun supportedProtocolVersion(version: Int): Boolean =
            version in MINIMUM_PROTOCOL_VERSION..PROTOCOL_VERSION

        internal fun scaleLegacyInputRecord(
            records: IntArray,
            offset: Int,
            logicalWidth: Int,
            logicalHeight: Int,
            physicalWidth: Int,
            physicalHeight: Int,
        ) {
            require(offset >= 0 && offset <= records.size - INPUT_FIELDS)
            when (records[offset]) {
                INPUT_TOUCH_DOWN,
                INPUT_TOUCH_MOTION,
                -> {
                    records[offset + 2] =
                        scaleLegacyInputCoordinate(
                            records[offset + 2],
                            logicalWidth,
                            physicalWidth,
                        )
                    records[offset + 3] =
                        scaleLegacyInputCoordinate(
                            records[offset + 3],
                            logicalHeight,
                            physicalHeight,
                        )
                }
                INPUT_POINTER_MOTION -> {
                    records[offset + 1] =
                        scaleLegacyInputCoordinate(
                            records[offset + 1],
                            logicalWidth,
                            physicalWidth,
                        )
                    records[offset + 2] =
                        scaleLegacyInputCoordinate(
                            records[offset + 2],
                            logicalHeight,
                            physicalHeight,
                        )
                }
                INPUT_POINTER_RELATIVE -> {
                    records[offset + 1] =
                        scaleLegacyInputCoordinate(
                            records[offset + 1],
                            logicalWidth,
                            physicalWidth,
                        )
                    records[offset + 2] =
                        scaleLegacyInputCoordinate(
                            records[offset + 2],
                            logicalHeight,
                            physicalHeight,
                        )
                    records[offset + 3] =
                        scaleLegacyInputCoordinate(
                            records[offset + 3],
                            logicalWidth,
                            physicalWidth,
                        )
                    records[offset + 4] =
                        scaleLegacyInputCoordinate(
                            records[offset + 4],
                            logicalHeight,
                            physicalHeight,
                        )
                }
            }
        }

        private fun scaleLegacyInputCoordinate(
            value: Int,
            logical: Int,
            physical: Int,
        ): Int {
            if (logical <= 0 || physical <= 0) return value
            val numerator = value.toLong() * logical.toLong()
            val half = physical.toLong() / 2L
            val rounded = if (numerator >= 0L) numerator + half else numerator - half
            return (rounded / physical.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
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
        private const val IME_COMPONENT_CURSOR_RECTANGLE_WIDTH = 6
        private const val IME_COMPONENT_CURSOR_RECTANGLE_HEIGHT = 7
        private const val DOCUMENT_OPERATION_SAVE = 1
        private const val DOCUMENT_OPERATION_OPEN = 2
        private const val DOCUMENT_OPERATION_OPEN_MULTIPLE = 3
        private const val DOCUMENT_OPERATION_DIRECTORY = 4
        private const val DOCUMENT_RESULT_SUCCESS = 1
        private const val DOCUMENT_RESULT_CANCELLED = 2
        private const val DOCUMENT_RESULT_FAILED = 3
        private const val DOCUMENT_REQUEST_TIMEOUT_MINUTES = 10L
        private const val MAX_DOCUMENT_TITLE_UTF16 = 128
        private const val MAX_DOCUMENT_NAME_UTF16 = 255
        private const val MAX_DOCUMENT_NAME_BYTES = 255
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
