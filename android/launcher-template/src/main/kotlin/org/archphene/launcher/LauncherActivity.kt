package org.archphene.launcher

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

open class LauncherActivity :
    Activity(),
    SurfaceHolder.Callback {
    private data class ImeState(
        val active: Boolean,
        val revision: Int,
        val text: String,
        val cursor: Int,
        val anchor: Int,
        val hint: Int,
        val purpose: Int,
        val editorEvidence: Int = LauncherImeTouchPolicy.EDITOR_EVIDENCE_NONE,
    )

    private data class PendingImeState(
        val state: ImeState,
        val restartOnApply: Boolean,
        val deactivateBeforeApply: Boolean,
    )

    private data class PendingClipboard(
        val text: String?,
        val html: String?,
    )

    private data class PendingAppearance(
        val dark: Boolean,
        val background: Int,
        val foreground: Int,
    )

    private data class PendingPointerCapture(
        val active: Boolean,
        val releaseBeforeApply: Boolean,
    )

    private data class PendingStatus(
        val state: Int,
        val message: String,
    )

    private sealed interface PendingAction {
        val session: Int

        data class Document(
            override val session: Int,
            val requestId: Int,
            val operation: Int,
            val title: String,
            val suggestedName: String,
            val mimeType: String,
        ) : PendingAction

        data class OpenUri(
            override val session: Int,
            val uri: String,
        ) : PendingAction

        data class Notification(
            override val session: Int,
            val operation: Int,
            val id: String,
            val title: String,
            val body: String,
        ) : PendingAction

    }

    private sealed interface CursorUpdate {
        data class System(val icon: Int) : CursorUpdate

        data class BitmapCursor(
            val bitmap: Bitmap,
            val hotspotX: Int,
            val hotspotY: Int,
        ) : CursorUpdate
    }

    private data class OpenDocument(
        val displayName: String,
        val descriptor: ParcelFileDescriptor,
    )

    private data class IncomingDocumentRequest(
        val uri: Uri,
        val mimeType: String,
    )

    private data class PreparedIncomingDocument(
        val displayName: String,
        val mimeType: String,
        val descriptor: ParcelFileDescriptor,
    )

    private data class PendingNotification(
        val id: String,
        val title: String,
        val body: String,
    )

    private lateinit var status: TextView
    private lateinit var directoryProgress: ProgressBar
    private lateinit var surfaceView: LauncherSurfaceView
    private lateinit var content: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val documentThread = HandlerThread("ArchpheneDocument").apply { start() }
    private val documentHandler = Handler(documentThread.looper)
    private var remote: IBinder? = null
    private var sessionId = 0
    private var requestedToplevelId = 0
    private var latestIndependentToplevelIds = IntArray(0)
    private var attempts = 0
    private var binding = false
    @Volatile private var activityResumed = false
    @Volatile private var printingEnabled = false
    @Volatile private var microphoneEnabled = false
    @Volatile private var secretsEnabled = false
    @Volatile private var cameraEnabled = false
    private val secretStore by lazy { LauncherSecretStore(filesDir) }
    private val cameraIntegrationDelegate = lazy { LauncherCameraIntegration(this) }
    private val cameraIntegration by cameraIntegrationDelegate
    private val accessibilityProvider =
        LauncherAccessibilityProvider(
            submitAction = ::submitAccessibilityAction,
            submitMenuFallback = ::submitAccessibilityMenuFallback,
        )
    private val cameraLifecycleMonitor = Object()
    @Volatile private var cameraLifecycleClosed = false
    @Volatile private var cameraPermissionRequestInFlight = false
    private var notificationPermissionRequestInFlight = false
    private val pendingNotifications =
        arrayOfNulls<PendingNotification>(LauncherNotificationPolicy.MAX_PENDING)
    private var managerUid = -1
    private var remoteStatus = STATUS_STARTING
    private var linuxAppearanceDark: Boolean? = null
    private var linuxAppearanceBackground = 0
    private var linuxAppearanceForeground = 0
    private var attachedSurface: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var attachedLogicalWidth = 0
    private var attachedLogicalHeight = 0
    private var attachedDensityDpi = 0
    private var attachedFontScaleMillis = 0
    private var configurationSurfaceAttachFrames = 0
    private val attachSurfaceAfterConfigurationChange =
        object : Runnable {
            override fun run() {
                if (
                    configurationSurfaceAttachFrames <= 0 ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return
                }
                configurationSurfaceAttachFrames--
                attachSurface()
                if (configurationSurfaceAttachFrames > 0) {
                    surfaceView.postOnAnimation(this)
                }
            }
        }
    private var managerDeathSurfaceReset = false
    private var pointerButtonState = 0
    private var desktopTouchSequenceState = LauncherDesktopTouchPolicy.IDLE
    private var desktopTouchSlop = 0
    private var testInputDebug = false
    private var inputCoordinateLogsRemaining = 0
    private var inputKeyLogsRemaining = 0
    private var lastKeyEventTime = 0
    private var hasKeyEventTime = false
    private var desktopTouchDownX = 0f
    private var desktopTouchDownY = 0f
    private var pointerCaptureRequested = false
    private var pointerRecaptureAfterRelease = false
    private var pointerCaptureReleaseInFlight = false
    @Volatile private var callbackPointerCaptureActive = false
    private var launcherOrientationPolicy = LauncherOrientationPolicy.DEFAULT
    private var cursorSystemIcon = PointerIcon.TYPE_ARROW
    private var customCursorPointerIcon: PointerIcon? = null
    private var imeState = ImeState(false, 0, "", 0, 0, 0, 0)
    @Volatile private var callbackImeActive = false
    private val pendingImeState =
        LatestCallbackSlot<PendingImeState>(
            merge = { previous, next ->
                PendingImeState(
                    state = next.state,
                    restartOnApply =
                        previous.restartOnApply ||
                            (!previous.state.active && next.state.active),
                    deactivateBeforeApply =
                        previous.deactivateBeforeApply ||
                            (previous.state.active && !next.state.active),
                )
            },
        )
    private val applyPendingImeState =
        Runnable {
            pendingImeState.take()?.let { pending ->
                if (pending.deactivateBeforeApply && pending.state.active) {
                    applyImeState(
                        imeState.copy(
                            active = false,
                            text = "",
                            cursor = 0,
                            anchor = 0,
                            editorEvidence = LauncherImeTouchPolicy.EDITOR_EVIDENCE_NONE,
                        ),
                    )
                }
                applyImeState(pending.state, pending.restartOnApply)
            }
        }
    private val pendingCursor =
        LatestCallbackSlot<CursorUpdate>(
            discard = { update ->
                if (update is CursorUpdate.BitmapCursor) update.bitmap.recycle()
            },
        )
    private val pendingClipboardCallback = LatestCallbackSlot<PendingClipboard>()
    private val applyPendingClipboardCallback =
        Runnable {
            pendingClipboardCallback.take()?.let { clipboard ->
                applyLinuxClipboard(clipboard.text, clipboard.html)
            }
        }
    private val pendingPointerCapture =
        LatestCallbackSlot<PendingPointerCapture>(
            merge = { previous, next ->
                PendingPointerCapture(
                    active = next.active,
                    releaseBeforeApply =
                        previous.releaseBeforeApply ||
                            (previous.active && !next.active),
                )
            },
        )
    private val applyPendingPointerCapture =
        Runnable {
            pendingPointerCapture.take()?.let { pending ->
                when {
                    pending.releaseBeforeApply &&
                        pending.active &&
                        surfaceView.hasPointerCapture() -> {
                        pointerRecaptureAfterRelease = true
                        pointerCaptureReleaseInFlight = true
                        callbackPointerCaptureActive = false
                        pointerCaptureRequested = false
                        surfaceView.releasePointerCapture()
                    }
                    !pending.active -> {
                        pointerRecaptureAfterRelease = false
                        applyPointerCapture(false)
                    }
                    !pointerRecaptureAfterRelease -> applyPointerCapture(true)
                }
            }
        }
    private val pendingAppearance = LatestCallbackSlot<PendingAppearance>()
    private val applyPendingAppearance =
        Runnable {
            pendingAppearance.take()?.let { appearance ->
                applyLinuxAppearance(
                    appearance.dark,
                    appearance.background,
                    appearance.foreground,
                )
            }
        }
    private val pendingStatus = LatestCallbackSlot<PendingStatus>()
    private val applyPendingStatus =
        Runnable {
            pendingStatus.take()?.let { pending ->
                applyRemoteStatus(pending.state, pending.message)
            }
        }
    private val pendingActions =
        BoundedCallbackQueue(
            capacity = MAX_PENDING_ACTION_CALLBACKS,
            schedule = handler::post,
            consume = ::applyPendingAction,
            discard = ::discardPendingAction,
            reject = ::discardPendingAction,
        )
    private val applyPendingCursor =
        Runnable {
            when (val update = pendingCursor.take()) {
                is CursorUpdate.System -> applyCursorSystemIcon(update.icon)
                is CursorUpdate.BitmapCursor ->
                    applyCursorBitmap(update.bitmap, update.hotspotX, update.hotspotY)
                null -> Unit
            }
        }
    private var softImeRequested = false
    private var softImeExplicitlyRequestedForAmbiguousInput = false
    private var imeActivationTouchPending = false
    private var ambiguousImeLongPressEligible = false
    private val clearImeActivationTouchPending =
        Runnable { imeActivationTouchPending = false }
    private val showImeAfterTouch =
        Runnable {
            if (softImeRequested && imeState.active) {
                showIme(restart = true)
            }
        }
    private val suppressImplicitImeAfterHardwareKey =
        Runnable {
            if (
                LauncherImeTouchPolicy.suppressImplicitAfterHardwareKey(
                    imeState.active,
                    softImeRequested,
                )
            ) {
                hideIme()
            }
        }
    private var hasPendingLinuxClipboard = false
    private var pendingLinuxClipboardText: String? = null
    private var pendingLinuxClipboardHtml: String? = null
    private var pendingDocumentRequestId = 0
    private var pendingDocumentOperation = 0
    private var incomingDocumentRequest: IncomingDocumentRequest? = null
    private var preparedIncomingDocument: PreparedIncomingDocument? = null
    private var preparingIncomingDocument = false
    private var incomingDocumentRejected = false
    private var incomingDocumentGeneration = 0
    private var incomingDocumentCancellation: CancellationSignal? = null
    private val activeDirectoryWatchdog =
        AtomicReference<DirectoryProviderWatchdog?>()
    private val directoryStreamActive = AtomicBoolean(false)
    private var directoryProgressVisible = false
    private val showDirectoryProgress =
        Runnable {
            if (
                directoryStreamActive.get() &&
                remoteStatus == STATUS_RUNNING &&
                !isFinishing &&
                !isDestroyed
            ) {
                directoryProgressVisible = true
                status.setText(R.string.directory_import_in_progress)
                status.visibility = View.VISIBLE
                directoryProgress.visibility = View.VISIBLE
            }
        }
    private val clipboardManager by lazy {
        getSystemService(ClipboardManager::class.java)
    }
    private var clipboardListening = false
    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            if (hasWindowFocus()) {
                submitAndroidClipboard()
            }
        }
    private val inputDeviceListener =
        object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = reconcileWindowTaskPolicy()

            override fun onInputDeviceRemoved(deviceId: Int) = reconcileWindowTaskPolicy()

            override fun onInputDeviceChanged(deviceId: Int) = reconcileWindowTaskPolicy()
        }
    private val clipboardRetry =
        Runnable {
            if (hasWindowFocus()) {
                submitAndroidClipboard(retryOnUnavailable = false)
            }
        }
    private val clientToken =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (
                    code !in CALLBACK_STATUS..CALLBACK_WINDOWS ||
                    Binder.getCallingUid() != managerUid
                ) {
                    return super.onTransact(code, data, reply, flags)
                }
                check(Looper.myLooper() != Looper.getMainLooper()) {
                    "Manager callbacks must not run on Android's main thread"
                }
                return runCatching {
                    data.enforceInterface(CALLBACK_INTERFACE)
                    val version = data.readInt()
                    val callbackSession = data.readInt()
                    if (
                        version != PROTOCOL_VERSION ||
                        callbackSession != sessionId
                    ) {
                        return@runCatching false
                    }
                    when (code) {
                        CALLBACK_STATUS -> {
                            val state = data.readInt()
                            val message = data.readString().orEmpty()
                            if (
                                state !in STATUS_STARTING..STATUS_STOPPED ||
                                message.isEmpty() ||
                                message.length > 256 ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            if (
                                !pendingStatus.offer(PendingStatus(state, message)) {
                                    handler.post(applyPendingStatus)
                                }
                            ) {
                                return@runCatching false
                            }
                            true
                        }
                        CALLBACK_CLIPBOARD -> {
                            val present = data.readInt()
                            val text = if (present == 1) data.readString() else null
                            val htmlPresent = if (present == 1) data.readInt() else 0
                            val html = if (htmlPresent == 1) data.readString() else null
                            if (
                                present !in 0..1 ||
                                htmlPresent !in 0..1 ||
                                (present == 1 &&
                                    (text == null || text.length > MAX_CLIPBOARD_UTF16)) ||
                                (present == 0 && htmlPresent != 0) ||
                                (htmlPresent == 1 &&
                                    (html == null || html.length > MAX_CLIPBOARD_UTF16)) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            if (
                                !pendingClipboardCallback.offer(PendingClipboard(text, html)) {
                                    handler.post(applyPendingClipboardCallback)
                                }
                            ) {
                                return@runCatching false
                            }
                            true
                        }
                        CALLBACK_IME_STATE -> {
                            val active = data.readInt()
                            val revision = data.readInt()
                            val text = if (active == 1) data.readString() else null
                            val cursor = if (active == 1) data.readInt() else 0
                            val anchor = if (active == 1) data.readInt() else 0
                            val hint = if (active == 1) data.readInt() else 0
                            val purpose = if (active == 1) data.readInt() else 0
                            val editorEvidence =
                                if (active == 1) {
                                    data.readInt()
                                } else {
                                    LauncherImeTouchPolicy.EDITOR_EVIDENCE_NONE
                                }
                            if (
                                active !in 0..1 ||
                                (active == 1 &&
                                    (text == null ||
                                        text.length > MAX_IME_UTF16 ||
                                        cursor !in 0..text.length ||
                                        anchor !in 0..text.length ||
                                        hint < 0 ||
                                        purpose !in 0..MAX_IME_PURPOSE ||
                                        editorEvidence !in
                                            LauncherImeTouchPolicy.EDITOR_EVIDENCE_NONE..
                                            LauncherImeTouchPolicy.EDITOR_EVIDENCE_STRONG)) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            val next =
                                ImeState(
                                    active == 1,
                                    revision,
                                    text.orEmpty(),
                                    cursor,
                                    anchor,
                                    hint,
                                    purpose,
                                    editorEvidence,
                                )
                            val pending =
                                PendingImeState(
                                    state = next,
                                    restartOnApply = next.active && !callbackImeActive,
                                    deactivateBeforeApply = !next.active && callbackImeActive,
                                )
                            if (!pendingImeState.offer(pending) { handler.post(applyPendingImeState) }) {
                                return@runCatching false
                            }
                            true
                        }
                        CALLBACK_DOCUMENT_REQUEST -> {
                            val requestId = data.readInt()
                            val operation = data.readInt()
                            val title = data.readString()
                            val suggestedName = data.readString()
                            val mimeType = data.readString()
                            if (
                                requestId <= 0 ||
                                operation !in
                                    DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY ||
                                title.isNullOrBlank() ||
                                title.length > MAX_DOCUMENT_TITLE_UTF16 ||
                                suggestedName == null ||
                                (operation == DOCUMENT_OPERATION_SAVE &&
                                    !LauncherDocumentNamePolicy.valid(suggestedName)) ||
                                (operation != DOCUMENT_OPERATION_SAVE &&
                                    suggestedName.isNotEmpty()) ||
                                mimeType == null ||
                                DocumentMimePolicy.parse(mimeType) == null ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            pendingActions.offer(
                                PendingAction.Document(
                                    callbackSession,
                                    requestId,
                                    operation,
                                    title,
                                    suggestedName,
                                    mimeType,
                                ),
                            )
                        }
                        CALLBACK_POINTER_CAPTURE -> {
                            val active = data.readInt()
                            if (active !in 0..1 || data.dataAvail() != 0) {
                                return@runCatching false
                            }
                            if (
                                !pendingPointerCapture.offer(
                                    PendingPointerCapture(
                                        active = active == 1,
                                        releaseBeforeApply =
                                            active == 0 && callbackPointerCaptureActive,
                                    ),
                                ) {
                                    handler.post(applyPendingPointerCapture)
                                }
                            ) {
                                return@runCatching false
                            }
                            true
                        }
                        CALLBACK_CURSOR -> {
                            when (data.readInt()) {
                                CURSOR_KIND_SYSTEM -> {
                                    val systemIcon = data.readInt()
                                    if (
                                        !validCursorSystemIcon(systemIcon) ||
                                        data.dataAvail() != 0
                                    ) {
                                        return@runCatching false
                                    }
                                    if (!pendingCursor.offer(CursorUpdate.System(systemIcon)) {
                                            handler.post(applyPendingCursor)
                                        }
                                    ) {
                                        return@runCatching false
                                    }
                                    true
                                }
                                CURSOR_KIND_BITMAP -> {
                                    val width = data.readInt()
                                    val height = data.readInt()
                                    val hotspotX = data.readInt()
                                    val hotspotY = data.readInt()
                                    if (
                                        !validCursorBitmapMetadata(
                                            width,
                                            height,
                                            hotspotX,
                                            hotspotY,
                                        )
                                    ) {
                                        return@runCatching false
                                    }
                                    val bitmap = Bitmap.CREATOR.createFromParcel(data)
                                    if (
                                        bitmap.width != width ||
                                        bitmap.height != height ||
                                        bitmap.config != Bitmap.Config.ARGB_8888 ||
                                        data.dataAvail() != 0
                                    ) {
                                        bitmap.recycle()
                                        return@runCatching false
                                    }
                                    if (!pendingCursor.offer(
                                            CursorUpdate.BitmapCursor(bitmap, hotspotX, hotspotY),
                                        ) {
                                            handler.post(applyPendingCursor)
                                        }
                                    ) {
                                        return@runCatching false
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        CALLBACK_OPEN_URI -> {
                            val uri = data.readString()
                            if (
                                uri == null ||
                                !LauncherBrowserUriPolicy.valid(uri) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            pendingActions.offer(PendingAction.OpenUri(callbackSession, uri))
                        }
                        CALLBACK_NOTIFICATION -> {
                            val operation = data.readInt()
                            val id = data.readString()
                            val title = data.readString()
                            val body = data.readString()
                            if (
                                operation !in
                                    NOTIFICATION_OPERATION_POST..NOTIFICATION_OPERATION_WITHDRAW ||
                                id == null ||
                                title == null ||
                                body == null ||
                                (
                                    operation == NOTIFICATION_OPERATION_POST &&
                                        !LauncherNotificationPolicy.valid(id, title, body)
                                ) ||
                                (
                                    operation == NOTIFICATION_OPERATION_WITHDRAW &&
                                        (
                                            !LauncherNotificationPolicy.validId(id) ||
                                                title.isNotEmpty() ||
                                                body.isNotEmpty()
                                        )
                                ) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            pendingActions.offer(
                                PendingAction.Notification(
                                    callbackSession,
                                    operation,
                                    id,
                                    title,
                                    body,
                                ),
                            )
                        }
                        CALLBACK_PRINT_PDF -> {
                            val title = data.readString()
                            val descriptor =
                                runCatching {
                                    ParcelFileDescriptor.CREATOR.createFromParcel(data)
                                }.getOrNull()
                            val valid =
                                title != null &&
                                    descriptor != null &&
                                    data.dataAvail() == 0 &&
                                    reply != null
                            val accepted =
                                if (valid) {
                                    stagePrintPdf(title!!, descriptor!!)
                                } else {
                                    runCatching { descriptor?.close() }
                                    false
                                }
                            reply?.writeNoException()
                            reply?.writeInt(if (accepted) RESULT_OK else RESULT_NOT_READY)
                            valid
                        }
                        CALLBACK_MICROPHONE_PERMISSION -> {
                            val permissionIntent =
                                runCatching {
                                    PendingIntent.CREATOR.createFromParcel(data)
                                }.getOrNull()
                            if (
                                !microphoneEnabled ||
                                permissionIntent == null ||
                                data.dataAvail() != 0
                            ) {
                                permissionIntent?.cancel()
                                return@runCatching false
                            }
                            handler.post {
                                beginMicrophonePermission(permissionIntent)
                            }
                            true
                        }
                        CALLBACK_SECRET -> {
                            if (!secretsEnabled || reply == null) {
                                return@runCatching false
                            }
                            val operation = data.readInt()
                            val argumentCount = data.readInt()
                            if (argumentCount !in 0..MAX_SECRET_ARGUMENTS) {
                                return@runCatching false
                            }
                            val arguments = ArrayList<String>(argumentCount)
                            repeat(argumentCount) {
                                val argument = data.readString()
                                if (
                                    argument == null ||
                                    argument.length > MAX_SECRET_ARGUMENT_UTF16
                                ) {
                                    return@runCatching false
                                }
                                arguments.add(argument)
                            }
                            val descriptorPresent = data.readInt()
                            val descriptor =
                                if (descriptorPresent == 1) {
                                    runCatching {
                                        ParcelFileDescriptor.CREATOR.createFromParcel(data)
                                    }.getOrNull()
                                } else {
                                    null
                                }
                            if (
                                descriptorPresent !in 0..1 ||
                                (descriptorPresent == 1 && descriptor == null) ||
                                data.dataAvail() != 0 ||
                                !validSecretCallback(operation, arguments, descriptor)
                            ) {
                                runCatching { descriptor?.close() }
                                return@runCatching false
                            }
                            val response =
                                descriptor.use {
                                    runCatching {
                                        handleSecretCallback(operation, arguments, it)
                                    }.getOrElse { error ->
                                        if (error is IllegalArgumentException) {
                                            Log.w(
                                                TAG,
                                                "Rejected invalid secret operation=$operation",
                                            )
                                            "ERROR\tINVALID_REQUEST"
                                        } else {
                                            Log.e(
                                                TAG,
                                                "Secret operation failed operation=$operation",
                                                error,
                                            )
                                            "ERROR\tFAILED"
                                        }
                                    }
                                }
                            reply.writeNoException()
                            reply.writeString(response)
                            true
                        }
                        CALLBACK_CAMERA -> {
                            if (!cameraEnabled || reply == null) {
                                return@runCatching false
                            }
                            val operation = data.readInt()
                            val width = data.readInt()
                            val height = data.readInt()
                            val facing = data.readInt()
                            val descriptorPresent = data.readInt()
                            val descriptor =
                                if (descriptorPresent == 1) {
                                    runCatching {
                                        ParcelFileDescriptor.CREATOR.createFromParcel(data)
                                    }.getOrNull()
                                } else {
                                    null
                                }
                            if (
                                descriptorPresent !in 0..1 ||
                                (descriptorPresent == 1 && descriptor == null) ||
                                data.dataAvail() != 0 ||
                                !validCameraCallback(
                                    operation,
                                    width,
                                    height,
                                    facing,
                                    descriptor,
                                )
                            ) {
                                runCatching { descriptor?.close() }
                                return@runCatching false
                            }
                            val response =
                                descriptor.use {
                                    runCatching {
                                        handleCameraCallback(
                                            operation,
                                            width,
                                            height,
                                            facing,
                                            it,
                                        )
                                    }.getOrElse { error ->
                                        if (error is IllegalArgumentException) {
                                            Log.w(
                                                TAG,
                                                "Rejected invalid camera operation=$operation",
                                            )
                                            "ERROR\tINVALID_REQUEST"
                                        } else {
                                            Log.e(
                                                TAG,
                                                "Camera operation failed operation=$operation",
                                                error,
                                            )
                                            "ERROR\tFAILED"
                                        }
                                    }
                                }
                            reply.writeNoException()
                            reply.writeString(response)
                            true
                        }
                        CALLBACK_APPEARANCE -> {
                            val dark = data.readInt()
                            val background = data.readInt()
                            val foreground = data.readInt()
                            if (
                                dark !in 0..1 ||
                                Color.alpha(background) != 0xff ||
                                Color.alpha(foreground) != 0xff ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            if (
                                !pendingAppearance.offer(
                                    PendingAppearance(dark == 1, background, foreground),
                                ) {
                                    handler.post(applyPendingAppearance)
                                }
                            ) {
                                return@runCatching false
                            }
                            true
                        }
                        CALLBACK_ACCESSIBILITY -> {
                            val operation = data.readInt()
                            val accepted =
                                when (operation) {
                                    ACCESSIBILITY_CALLBACK_TREE -> {
                                        val descriptor =
                                            ParcelFileDescriptor.CREATOR.createFromParcel(data)
                                        if (data.dataAvail() != 0) {
                                            descriptor.close()
                                            false
                                        } else {
                                            accessibilityProvider.publish(descriptor)
                                        }
                                    }
                                    ACCESSIBILITY_CALLBACK_EVENT,
                                    ACCESSIBILITY_CALLBACK_MENU,
                                    -> {
                                        val nodeId = data.readInt()
                                        val type = data.readString().orEmpty()
                                        val transition = data.readInt()
                                        val minimumNodeId =
                                            if (
                                                operation == ACCESSIBILITY_CALLBACK_EVENT
                                            ) {
                                                0
                                            } else {
                                                1
                                            }
                                        if (
                                            nodeId !in minimumNodeId..
                                            MAX_ACCESSIBILITY_NODE_ID ||
                                            transition !in 0..1 ||
                                            data.dataAvail() != 0
                                        ) {
                                            false
                                        } else if (operation == ACCESSIBILITY_CALLBACK_EVENT) {
                                            transition == 0 &&
                                                accessibilityProvider.sendNamedEvent(nodeId, type)
                                        } else {
                                            type.isEmpty() &&
                                                accessibilityProvider.activateMenuFallback(
                                                    nodeId,
                                                    transition == 1,
                                                )
                                        }
                                    }
                                    else -> false
                                }
                            reply?.writeNoException()
                            reply?.writeInt(if (accepted) RESULT_OK else RESULT_INVALID)
                            true
                        }
                        CALLBACK_ACCESSIBILITY_VIEWPORT -> {
                            val presentationWidth = data.readInt()
                            val presentationHeight = data.readInt()
                            val destinationX = data.readInt()
                            val destinationY = data.readInt()
                            val destinationWidth = data.readInt()
                            val destinationHeight = data.readInt()
                            if (
                                presentationWidth !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
                                presentationHeight !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
                                destinationX !in -MAX_ACCESSIBILITY_VIEWPORT..
                                MAX_ACCESSIBILITY_VIEWPORT ||
                                destinationY !in -MAX_ACCESSIBILITY_VIEWPORT..
                                MAX_ACCESSIBILITY_VIEWPORT ||
                                destinationWidth !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
                                destinationHeight !in 1..MAX_ACCESSIBILITY_VIEWPORT ||
                                data.dataAvail() != 0
                            ) {
                                false
                            } else {
                                accessibilityProvider.updateViewportTransform(
                                    AccessibilityViewportTransform(
                                        presentationWidth,
                                        presentationHeight,
                                        destinationX,
                                        destinationY,
                                        destinationWidth,
                                        destinationHeight,
                                    ),
                                )
                                true
                            }
                        }
                        CALLBACK_WINDOWS -> {
                            val count = data.readInt()
                            if (count !in 0..MAX_INDEPENDENT_WINDOWS) {
                                return@runCatching false
                            }
                            val ids = IntArray(count)
                            val unique = HashSet<Int>(count)
                            repeat(count) { index ->
                                val id = data.readInt()
                                if (id <= 0 || !unique.add(id)) {
                                    return@runCatching false
                                }
                                ids[index] = id
                            }
                            if (data.dataAvail() != 0) return@runCatching false
                            handler.post { reconcileIndependentWindows(ids) }
                            true
                        }
                        else -> false
                    }
                }.getOrDefault(false)
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                remote = service
                managerDeathSurfaceReset = false
                attempts = 0
                openSession()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
                sessionId = 0
                pendingActions.clear()
                remoteStatus = STATUS_STARTING
                resetSurfaceAttachment()
                recreateSurfaceView()
                pointerButtonState = 0
                desktopTouchSequenceState = LauncherDesktopTouchPolicy.IDLE
                applyPointerCapture(false)
                applyCursorSystemIcon(PointerIcon.TYPE_ARROW)
                softImeRequested = false
                softImeExplicitlyRequestedForAmbiguousInput = false
                imeActivationTouchPending = false
                ambiguousImeLongPressEligible = false
                handler.removeCallbacks(clearImeActivationTouchPending)
                hasPendingLinuxClipboard = false
                pendingLinuxClipboardText = null
                pendingLinuxClipboardHtml = null
                pendingDocumentRequestId = 0
                pendingDocumentOperation = 0
                linuxAppearanceDark = null
                linuxAppearanceBackground = 0
                linuxAppearanceForeground = 0
                accessibilityProvider.clear()
                applySystemBarAppearance()
                applyImeState(ImeState(false, 0, "", 0, 0, 0, 0))
                status.setText(R.string.launcher_disconnected)
                status.visibility = View.VISIBLE
            }

            override fun onBindingDied(name: ComponentName) {
                resetDeadBinding()
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        bindManager()
                    }
                }
            }

            override fun onNullBinding(name: ComponentName) {
                resetDeadBinding()
                showUnavailable()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        val managerPackage = applicationMetadata().getString(MANAGER_PACKAGE).orEmpty()
        val managerIsDebuggable =
            SAFE_PACKAGE.matches(managerPackage) &&
                runCatching {
                    packageManager.getApplicationInfo(managerPackage, 0).flags and
                        ApplicationInfo.FLAG_DEBUGGABLE != 0
                }.getOrDefault(false)
        testInputDebug =
            managerIsDebuggable && intent.getBooleanExtra(TEST_INPUT_DEBUG_EXTRA, false)
        requestedToplevelId =
            intent.getIntExtra(EXTRA_TOPLEVEL_ID, 0).takeIf { id -> id > 0 } ?: 0
        if (requestedToplevelId > 0) {
            synchronized(ACTIVE_TOPLEVELS) {
                ACTIVE_TOPLEVELS.add(requestedToplevelId)
            }
        }
        inputCoordinateLogsRemaining = if (testInputDebug) 16 else 0
        inputKeyLogsRemaining = if (testInputDebug) 64 else 0
        desktopTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        runCatching { cleanupStalePrintFiles() }
            .onFailure { error ->
                Log.w(TAG, "Could not clean private print staging", error)
            }
        acceptIncomingIntent(intent)
        surfaceView = createSurfaceView()
        status =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                textSize = 18f
                setTextColor(getColor(R.color.launcher_text))
                setBackgroundColor(getColor(R.color.launcher_background))
                text = getString(R.string.launcher_opening, appLabel())
            }
        directoryProgress =
            ProgressBar(this).apply {
                isIndeterminate = true
                visibility = View.GONE
            }
        content =
            FrameLayout(this).apply {
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    status,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    directoryProgress,
                    FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                        gravity = Gravity.CENTER
                        bottomMargin = dp(112)
                    },
                )
                setOnApplyWindowInsetsListener { view, insets ->
                    val safe =
                        if (Build.VERSION.SDK_INT >= 30) {
                            insets.getInsets(
                                WindowInsets.Type.systemBars() or
                                    WindowInsets.Type.displayCutout() or
                                    WindowInsets.Type.ime(),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            android.graphics.Insets.of(
                                insets.systemWindowInsetLeft,
                                insets.systemWindowInsetTop,
                                insets.systemWindowInsetRight,
                                insets.systemWindowInsetBottom,
                            )
                        }
                    if (
                        view.paddingLeft != safe.left ||
                        view.paddingTop != safe.top ||
                        view.paddingRight != safe.right ||
                        view.paddingBottom != safe.bottom
                    ) {
                        view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    }
                    insets
                }
            }
        setContentView(content)
        content.requestApplyInsets()
        surfaceView.requestFocus()
        applySystemBarAppearance()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarAppearance() {
        val systemDark =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val dark = linuxAppearanceDark ?: systemDark
        val light = !dark
        val background =
            if (linuxAppearanceDark == null) {
                getColor(R.color.launcher_background)
            } else {
                linuxAppearanceBackground
            }
        val foreground =
            if (linuxAppearanceDark == null) {
                getColor(R.color.launcher_text)
            } else {
                linuxAppearanceForeground
            }
        status.setTextColor(foreground)
        status.setBackgroundColor(background)
        content.setBackgroundColor(background)
        window.decorView.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= 30) {
            val mask =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            val mask =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility =
                if (light) {
                    window.decorView.systemUiVisibility or mask
                } else {
                    window.decorView.systemUiVisibility and mask.inv()
                }
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(InputManager::class.java)
            ?.registerInputDeviceListener(inputDeviceListener, handler)
        reconcileWindowTaskPolicy()
        pendingActions.resume()
        if (remote != null) {
            if (sessionId > 0) {
                attachSurface()
            } else {
                openSession()
            }
            return
        }
        if (binding) {
            return
        }
        bindManager()
    }

    override fun onResume() {
        super.onResume()
        synchronized(cameraLifecycleMonitor) {
            activityResumed = true
            cameraLifecycleMonitor.notifyAll()
        }
        maybeRequestNotificationPermission()
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        accessibilityProvider.refreshBoundsAfterTransition()
    }

    override fun onPause() {
        synchronized(cameraLifecycleMonitor) {
            activityResumed = false
        }
        if (cameraIntegrationDelegate.isInitialized()) {
            cameraIntegration.stopStream()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST -> {
                notificationPermissionRequestInFlight = false
                val granted =
                    permissions.size == 1 &&
                        permissions[0] == Manifest.permission.POST_NOTIFICATIONS &&
                        grantResults.size == 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    for (index in pendingNotifications.indices) {
                        pendingNotifications[index]?.let(::postLinuxNotification)
                        pendingNotifications[index] = null
                    }
                } else {
                    pendingNotifications.fill(null)
                    Log.i(TAG, "Linux notification permission denied")
                }
            }
            CAMERA_PERMISSION_REQUEST -> {
                cameraPermissionRequestInFlight = false
                val granted =
                    permissions.size == 1 &&
                        permissions[0] == Manifest.permission.CAMERA &&
                        grantResults.size == 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "Linux camera permission ${if (granted) "granted" else "denied"}")
            }
        }
    }

    private fun applyLinuxAppearance(
        dark: Boolean,
        background: Int,
        foreground: Int,
    ) {
        linuxAppearanceDark = dark
        linuxAppearanceBackground = background
        linuxAppearanceForeground = foreground
        applySystemBarAppearance()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            closeSession()
            preparedIncomingDocument?.descriptor?.close()
            preparedIncomingDocument = null
            preparingIncomingDocument = false
            acceptIncomingIntent(intent)
            attempts = 0
            remoteStatus = STATUS_STARTING
            if (incomingDocumentRejected) {
                status.setText(R.string.launcher_document_rejected)
                status.visibility = View.VISIBLE
            } else {
                status.text = getString(R.string.launcher_opening, appLabel())
                status.visibility = View.VISIBLE
                openSession()
            }
            return
        }
        if (remoteStatus != STATUS_STOPPED) {
            return
        }
        closeSession()
        attempts = 0
        remoteStatus = STATUS_STARTING
        status.text = getString(R.string.launcher_opening, appLabel())
        status.visibility = View.VISIBLE
        openSession()
    }

    private fun bindManager() {
        if (binding || remote != null || isFinishing || isDestroyed) {
            return
        }
        val metadata = applicationMetadata()
        val capabilities = metadata.getString(CAPABILITIES).orEmpty()
        if (capabilities !in VALID_CAPABILITIES_V10) {
            status.setText(R.string.launcher_capabilities_invalid)
            status.visibility = View.VISIBLE
            return
        }
        printingEnabled = capabilities.contains(",printing")
        microphoneEnabled = capabilities.contains(",audio-input")
        secretsEnabled = capabilities.contains(",secrets")
        cameraEnabled = ",camera," in capabilities
        val manager = metadata.getString(MANAGER_PACKAGE).orEmpty()
        if (!SAFE_PACKAGE.matches(manager)) {
            status.setText(R.string.launcher_invalid)
            status.visibility = View.VISIBLE
            return
        }
        managerUid =
            runCatching { packageManager.getApplicationInfo(manager, 0).uid }
                .getOrElse {
                    showUnavailable()
                    return
                }
        val intent =
            Intent(BIND_ACTION).apply {
                setPackage(manager)
            }
        /*
         * The visible launcher and the manager intentionally use separate
         * Android UIDs. Propagate foreground capabilities while this Activity
         * is visible so the manager-owned AAudio stream can request focus
         * under Android 15+'s foreground-audio hardening.
         */
        binding =
            bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
            )
        if (!binding) {
            status.setText(R.string.launcher_unavailable)
            status.visibility = View.VISIBLE
        }
    }

    private fun resetDeadBinding() {
        remote = null
        sessionId = 0
        pendingActions.clear()
        remoteStatus = STATUS_STARTING
        accessibilityProvider.clear()
        resetSurfaceAttachment()
        recreateSurfaceView()
        pointerButtonState = 0
        desktopTouchSequenceState = LauncherDesktopTouchPolicy.IDLE
        applyPointerCapture(false)
        applyCursorSystemIcon(PointerIcon.TYPE_ARROW)
        softImeRequested = false
        softImeExplicitlyRequestedForAmbiguousInput = false
        imeActivationTouchPending = false
        ambiguousImeLongPressEligible = false
        handler.removeCallbacks(clearImeActivationTouchPending)
        hasPendingLinuxClipboard = false
        pendingLinuxClipboardText = null
        pendingLinuxClipboardHtml = null
        applyImeState(ImeState(false, 0, "", 0, 0, 0, 0))
        if (binding) {
            runCatching { unbindService(connection) }
            binding = false
        }
        status.setText(R.string.launcher_disconnected)
        status.visibility = View.VISIBLE
    }

    private fun createSurfaceView(): LauncherSurfaceView =
        LauncherSurfaceView(this).apply {
            holder.addCallback(this@LauncherActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            pointerIcon =
                customCursorPointerIcon
                    ?: PointerIcon.getSystemIcon(this@LauncherActivity, cursorSystemIcon)
            accessibilityProvider.attach(this)
        }

    private fun recreateSurfaceView() {
        if (
            managerDeathSurfaceReset ||
            !::content.isInitialized ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        managerDeathSurfaceReset = true
        val previous = surfaceView
        previous.holder.removeCallback(this)
        accessibilityProvider.detach(previous)
        content.removeView(previous)
        surfaceView = createSurfaceView()
        content.addView(
            surfaceView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        surfaceView.requestFocus()
        Log.i(TAG, "Recreated launcher Surface after manager disconnect")
    }

    private fun resetSurfaceAttachment() {
        attachedSurface = null
        attachedWidth = 0
        attachedHeight = 0
        attachedLogicalWidth = 0
        attachedLogicalHeight = 0
        attachedDensityDpi = 0
        attachedFontScaleMillis = 0
    }

    override fun onStop() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDeviceListener)
        configurationSurfaceAttachFrames = 0
        surfaceView.removeCallbacks(attachSurfaceAfterConfigurationChange)
        handler.removeCallbacksAndMessages(null)
        pendingImeState.clear()
        pendingCursor.clear()
        pendingClipboardCallback.clear()
        pendingPointerCapture.clear()
        pendingAppearance.clear()
        pendingStatus.clear()
        pendingActions.pause()
        if (
            cameraPermissionRequestInFlight &&
            !getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                .getBoolean(CAMERA_PERMISSION_REQUESTED, false)
        ) {
            cameraPermissionRequestInFlight = false
        }
        stopClipboardListening()
        softImeRequested = false
        softImeExplicitlyRequestedForAmbiguousInput = false
        imeActivationTouchPending = false
        ambiguousImeLongPressEligible = false
        handler.removeCallbacks(clearImeActivationTouchPending)
        hideIme()
        applyPointerCapture(false)
        submitHostActive(false)
        detachSurface()
        super.onStop()
    }

    override fun onDestroy() {
        synchronized(cameraLifecycleMonitor) {
            cameraLifecycleClosed = true
            cameraLifecycleMonitor.notifyAll()
        }
        handler.removeCallbacksAndMessages(null)
        pendingImeState.close()
        pendingCursor.close()
        pendingClipboardCallback.close()
        pendingPointerCapture.close()
        pendingAppearance.close()
        pendingStatus.close()
        pendingActions.close()
        pendingNotifications.fill(null)
        stopClipboardListening()
        cancelPendingDocumentRequest()
        activeDirectoryWatchdog.getAndSet(null)?.close()
        detachSurface()
        closeSession()
        incomingDocumentCancellation?.cancel()
        incomingDocumentCancellation = null
        incomingDocumentGeneration++
        preparedIncomingDocument?.descriptor?.close()
        preparedIncomingDocument = null
        accessibilityProvider.clear()
        accessibilityProvider.detach(surfaceView)
        if (cameraIntegrationDelegate.isInitialized()) {
            cameraIntegration.close()
        }
        remote = null
        if (binding) {
            unbindService(connection)
            binding = false
        }
        documentThread.quitSafely()
        customCursorPointerIcon = null
        if (requestedToplevelId > 0) {
            synchronized(ACTIVE_TOPLEVELS) {
                ACTIVE_TOPLEVELS.remove(requestedToplevelId)
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(
            TAG,
            "Android activity result request=$requestCode result=$resultCode " +
                "documentPending=${pendingDocumentRequestId > 0}",
        )
        if (
            requestCode != DOCUMENT_SAVE_REQUEST_CODE &&
            requestCode != DOCUMENT_OPEN_REQUEST_CODE
        ) {
            return
        }
        val requestId = pendingDocumentRequestId
        val operation = pendingDocumentOperation
        pendingDocumentRequestId = 0
        pendingDocumentOperation = 0
        if (
            requestId <= 0 ||
            operation !in DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY
        ) {
            return
        }
        if (
            resultCode != Activity.RESULT_OK ||
            data == null ||
            (data.data == null && data.clipData == null)
        ) {
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_CANCELLED,
                null,
                "",
                false,
            )
            return
        }
        val resultIntent = checkNotNull(data)
        documentHandler.post {
            if (operation == DOCUMENT_OPERATION_SAVE) {
                val uri = resultIntent.data
                if (uri == null) {
                    sendDocumentResult(
                        requestId,
                        operation,
                        DOCUMENT_RESULT_FAILED,
                        null,
                        "",
                        false,
                    )
                    return@post
                }
                val displayName = queryDocumentName(uri)
                if (displayName == null) {
                    sendDocumentResult(
                        requestId,
                        operation,
                        DOCUMENT_RESULT_FAILED,
                        null,
                        "",
                        false,
                    )
                    return@post
                }
                val descriptor =
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "w")
                    }.getOrElse { error ->
                        Log.w(TAG, "Could not open Android document destination", error)
                        null
                    }
                if (descriptor == null) {
                    sendDocumentResult(
                        requestId,
                        operation,
                        DOCUMENT_RESULT_FAILED,
                        null,
                        "",
                        false,
                    )
                } else {
                    descriptor.use {
                        sendDocumentResult(
                            requestId,
                            operation,
                            DOCUMENT_RESULT_SUCCESS,
                            it,
                            displayName,
                            true,
                        )
                    }
                }
            } else if (operation == DOCUMENT_OPERATION_DIRECTORY) {
                sendDirectoryResult(requestId, resultIntent)
            } else {
                sendOpenDocumentResults(requestId, operation, resultIntent)
            }
        }
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        applyLauncherOrientationPolicy(configuration)
        applyStatusAppearance()
        applySystemBarAppearance()
        attachSurface()
        configurationSurfaceAttachFrames = CONFIGURATION_SURFACE_ATTACH_FRAMES
        surfaceView.removeCallbacks(attachSurfaceAfterConfigurationChange)
        surfaceView.postOnAnimation(attachSurfaceAfterConfigurationChange)
        reconcileWindowTaskPolicy()
    }

    private fun applyLauncherOrientationPolicy(configuration: Configuration) {
        requestedOrientation =
            LauncherOrientationPolicy.requestedOrientation(
                launcherOrientationPolicy,
                configuration.smallestScreenWidthDp,
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            surfaceView.requestFocus()
            if (pointerCaptureRequested && !surfaceView.hasPointerCapture()) {
                surfaceView.requestPointerCapture()
            }
            startClipboardListening()
            if (!applyPendingLinuxClipboard()) {
                handler.removeCallbacks(clipboardRetry)
                handler.postDelayed(clipboardRetry, CLIPBOARD_FOCUS_RETRY_MILLIS)
            }
            if (imeState.active && softImeRequested) {
                showIme(restart = true)
            }
        } else {
            stopClipboardListening()
            hideIme()
        }
        submitHostActive(hasFocus)
    }

    private fun applyPointerCapture(active: Boolean) {
        if (!active) {
            pointerRecaptureAfterRelease = false
        }
        callbackPointerCaptureActive = active
        pointerCaptureRequested = active
        if (active && pointerCaptureReleaseInFlight) {
            pointerRecaptureAfterRelease = true
            pointerCaptureRequested = false
        } else if (active && hasWindowFocus()) {
            surfaceView.requestFocus()
            if (!surfaceView.hasPointerCapture()) {
                surfaceView.requestPointerCapture()
            }
        } else if (!active && surfaceView.hasPointerCapture()) {
            pointerCaptureReleaseInFlight = true
            surfaceView.releasePointerCapture()
        }
    }

    private fun applyCursorSystemIcon(systemIcon: Int) {
        if (!validCursorSystemIcon(systemIcon)) {
            return
        }
        customCursorPointerIcon = null
        cursorSystemIcon = systemIcon
        surfaceView.pointerIcon = PointerIcon.getSystemIcon(this, systemIcon)
    }

    private fun applyCursorBitmap(
        bitmap: Bitmap,
        hotspotX: Int,
        hotspotY: Int,
    ) {
        if (
            !validCursorBitmapMetadata(bitmap.width, bitmap.height, hotspotX, hotspotY) ||
            bitmap.config != Bitmap.Config.ARGB_8888
        ) {
            bitmap.recycle()
            return
        }
        val pointerIcon =
            runCatching {
                PointerIcon.create(bitmap, hotspotX.toFloat(), hotspotY.toFloat())
            }.getOrNull()
        if (pointerIcon == null) {
            bitmap.recycle()
            return
        }
        customCursorPointerIcon = pointerIcon
        cursorSystemIcon = CUSTOM_CURSOR_ICON
        surfaceView.pointerIcon = pointerIcon
        // PointerIcon and ViewRootImpl may compare the previous bitmap on a
        // later frame. Dropping our old PointerIcon reference lets Android
        // reclaim it when safe; explicitly recycling it here makes that
        // deferred comparison crash on rapid cursor changes.
    }

    private fun validCursorSystemIcon(systemIcon: Int): Boolean =
        systemIcon == PointerIcon.TYPE_NULL ||
            systemIcon in PointerIcon.TYPE_ARROW..PointerIcon.TYPE_WAIT ||
            systemIcon in PointerIcon.TYPE_CELL..PointerIcon.TYPE_GRABBING

    private fun validCursorBitmapMetadata(
        width: Int,
        height: Int,
        hotspotX: Int,
        hotspotY: Int,
    ): Boolean =
        width in 1..MAX_CURSOR_DIMENSION &&
            height in 1..MAX_CURSOR_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_CURSOR_PIXELS &&
            hotspotX in 0 until width &&
            hotspotY in 0 until height

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        attachSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return submitPointer(event) || super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Desktop clients commonly retain a logical text focus across
            // launches. Do not let that alone pop Android's soft keyboard and
            // resize a newly starting application. Toolkit editors publish
            // surrounding-text, content-type, or cursor geometry evidence.
            // SDL clients that keep text input globally active must instead
            // receive a deliberate long press before Android shows the IME.
            val requestImeOnDown =
                LauncherImeTouchPolicy.requestOnDown(
                    imeState.active,
                    imeState.editorEvidence,
                )
            if (!imeState.active) {
                softImeRequested = false
                softImeExplicitlyRequestedForAmbiguousInput = false
            } else if (requestImeOnDown) {
                softImeRequested = true
                softImeExplicitlyRequestedForAmbiguousInput = false
            } else if (!softImeExplicitlyRequestedForAmbiguousInput) {
                softImeRequested = false
            }
            ambiguousImeLongPressEligible =
                imeState.active &&
                    imeState.editorEvidence != LauncherImeTouchPolicy.EDITOR_EVIDENCE_STRONG
            imeActivationTouchPending = !imeState.active
            handler.removeCallbacks(clearImeActivationTouchPending)
            if (imeActivationTouchPending) {
                handler.postDelayed(
                    clearImeActivationTouchPending,
                    SOFT_IME_TOUCH_DELAY_MILLIS,
                )
            }
            handler.removeCallbacks(showImeAfterTouch)
            if (imeState.active && softImeRequested) {
                handler.postDelayed(showImeAfterTouch, SOFT_IME_TOUCH_DELAY_MILLIS)
            }
        } else {
            if (ambiguousImeLongPressEligible) {
                ambiguousImeLongPressEligible =
                    LauncherImeTouchPolicy.retainLongPressEligibility(
                        eligible = true,
                        movedBeyondTouchSlop =
                            event.actionMasked == MotionEvent.ACTION_MOVE &&
                                LauncherDesktopTouchPolicy.beginsDrag(
                                    event.x - desktopTouchDownX,
                                    event.y - desktopTouchDownY,
                                    desktopTouchSlop,
                                ),
                        pointerCount = event.pointerCount,
                    )
            }
            if (
                event.actionMasked == MotionEvent.ACTION_UP &&
                LauncherImeTouchPolicy.requestOnUp(
                    ambiguousImeLongPressEligible,
                    imeState.active,
                    imeState.editorEvidence,
                    event.eventTime - event.downTime,
                )
            ) {
                softImeRequested = true
                softImeExplicitlyRequestedForAmbiguousInput = true
                handler.removeCallbacks(showImeAfterTouch)
                handler.post(showImeAfterTouch)
            }
        }
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            ambiguousImeLongPressEligible = false
        }
        val desktopTouchRoute =
            LauncherDesktopTouchPolicy.route(
                desktopTouchSequenceState,
                event.actionMasked,
            )
        val previousDesktopTouchSequenceState = desktopTouchSequenceState
        desktopTouchSequenceState =
            LauncherDesktopTouchPolicy.stateAfter(
                desktopTouchSequenceState,
                event.actionMasked,
            )
        if (desktopTouchRoute == LauncherDesktopTouchPolicy.POINTER) {
            return submitDesktopTouchPointer(event) || super.dispatchTouchEvent(event)
        }
        if (desktopTouchRoute == LauncherDesktopTouchPolicy.CANCEL_POINTER) {
            val promoted = submitPointerState(event, 0) && submitNativeTouchStart(event)
            if (!promoted) {
                desktopTouchSequenceState = previousDesktopTouchSequenceState
            }
            return true
        }
        if (desktopTouchRoute == LauncherDesktopTouchPolicy.CONSUME) {
            return true
        }
        val count =
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> event.pointerCount.coerceAtMost(MAX_INPUT_RECORDS)
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL,
                -> 1
                else -> 0
            }
        if (count == 0 || sessionId <= 0) {
            return super.dispatchTouchEvent(event)
        }
        val data = beginInputParcel(count)
        val reply = Parcel.obtain()
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                -> {
                    val index = event.actionIndex
                    writeInputRecord(
                        data,
                        INPUT_TOUCH_DOWN,
                        event.getPointerId(index),
                        surfaceX(event, index),
                        surfaceY(event, index),
                        event.eventTime.toInt(),
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    repeat(count) { index ->
                        writeInputRecord(
                            data,
                            INPUT_TOUCH_MOTION,
                            event.getPointerId(index),
                            surfaceX(event, index),
                            surfaceY(event, index),
                            event.eventTime.toInt(),
                        )
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                -> {
                    val index = event.actionIndex
                    writeInputRecord(
                        data,
                        INPUT_TOUCH_UP,
                        event.getPointerId(index),
                        event.eventTime.toInt(),
                    )
                }
                MotionEvent.ACTION_CANCEL -> {
                    writeInputRecord(data, INPUT_TOUCH_CANCEL)
                }
            }
            return sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return super.dispatchGenericMotionEvent(event)
        }
        return submitPointer(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun submitPointer(event: MotionEvent): Boolean {
        return submitPointerState(event, pointerButtonsAfter(event))
    }

    private fun submitDesktopTouchPointer(event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                desktopTouchDownX = event.x
                desktopTouchDownY = event.y
                // Establish Wayland pointer focus before a tap becomes a
                // button event. Some desktop toolkits intentionally discard
                // a click delivered in the same batch as pointer enter.
                submitPointerState(event, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val beginDrag =
                    pointerButtonState == 0 &&
                        LauncherDesktopTouchPolicy.beginsDrag(
                            event.x - desktopTouchDownX,
                            event.y - desktopTouchDownY,
                            desktopTouchSlop,
                        )
                submitPointerState(
                    event,
                    if (beginDrag || pointerButtonState != 0) {
                        pointerButtonState or MotionEvent.BUTTON_PRIMARY
                    } else {
                        0
                    },
                )
            }
            MotionEvent.ACTION_UP ->
                if (pointerButtonState == 0) {
                    submitPointerTap(event)
                } else {
                    submitPointerState(event, 0)
                }
            MotionEvent.ACTION_CANCEL -> submitPointerState(event, 0)
            else -> false
        }

    private fun submitPointerTap(event: MotionEvent): Boolean {
        if (sessionId <= 0) return false
        val data = beginInputParcel(3)
        val reply = Parcel.obtain()
        try {
            writeInputRecord(
                data,
                INPUT_POINTER_MOTION,
                surfaceX(event),
                surfaceY(event),
                event.eventTime.toInt(),
            )
            writeInputRecord(
                data,
                INPUT_POINTER_BUTTON_V2,
                MotionEvent.BUTTON_PRIMARY,
                1,
                event.eventTime.toInt(),
            )
            writeInputRecord(
                data,
                INPUT_POINTER_BUTTON_V2,
                MotionEvent.BUTTON_PRIMARY,
                0,
                event.eventTime.toInt(),
            )
            val submitted = sendInputParcel(data, reply)
            if (submitted) pointerButtonState = 0
            return submitted
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun submitNativeTouchStart(event: MotionEvent): Boolean {
        if (sessionId <= 0) return false
        val count = event.pointerCount.coerceAtMost(MAX_INPUT_RECORDS)
        if (count < 2) return false
        val data = beginInputParcel(count)
        val reply = Parcel.obtain()
        try {
            repeat(count) { index ->
                writeInputRecord(
                    data,
                    INPUT_TOUCH_DOWN,
                    event.getPointerId(index),
                    surfaceX(event, index),
                    surfaceY(event, index),
                    event.eventTime.toInt(),
                )
            }
            return sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun submitPointerState(
        event: MotionEvent,
        nextButtons: Int,
    ): Boolean {
        val supportedAction =
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL ||
                event.actionMasked == MotionEvent.ACTION_SCROLL ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        if (sessionId <= 0 || !supportedAction) {
            return false
        }
        val boundedNextButtons = nextButtons and POINTER_BUTTON_MASK
        val changedButtons = pointerButtonState xor boundedNextButtons
        val horizontal = axisToFixed(event.getAxisValue(MotionEvent.AXIS_HSCROLL))
        val vertical = axisToFixed(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        val hasAxis =
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
                (horizontal != 0 || vertical != 0)
        val count =
            1 +
                Integer.bitCount(changedButtons) +
                if (hasAxis) 1 else 0
        if (count > MAX_INPUT_RECORDS) {
            return false
        }
        if (inputCoordinateLogsRemaining > 0) {
            inputCoordinateLogsRemaining--
            Log.i(
                TAG,
                "Input coordinates action=${event.actionMasked} source=0x${event.source.toString(16)} " +
                    "raw=${event.x},${event.y} view=${surfaceView.left},${surfaceView.top} " +
                    "${surfaceView.width}x${surfaceView.height} " +
                    "logical=${attachedLogicalWidth}x$attachedLogicalHeight " +
                    "mapped=${surfaceX(event)},${surfaceY(event)}",
            )
        }
        val data = beginInputParcel(count)
        val reply = Parcel.obtain()
        try {
            writeInputRecord(
                data,
                INPUT_POINTER_MOTION,
                surfaceX(event),
                surfaceY(event),
                event.eventTime.toInt(),
            )
            for (button in POINTER_BUTTONS) {
                if (changedButtons and button != 0) {
                    writeInputRecord(
                        data,
                        INPUT_POINTER_BUTTON_V2,
                        button,
                        if (boundedNextButtons and button != 0) 1 else 0,
                        event.eventTime.toInt(),
                    )
                }
            }
            if (hasAxis) {
                writeInputRecord(
                    data,
                    INPUT_POINTER_AXIS,
                    horizontal,
                    vertical,
                    event.eventTime.toInt(),
                )
            }
            val submitted = sendInputParcel(data, reply)
            if (submitted) {
                pointerButtonState = boundedNextButtons
            }
            return submitted
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun submitCapturedPointer(event: MotionEvent): Boolean {
        if (sessionId <= 0 || !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false
        }
        val nextButtons = pointerButtonsAfter(event)
        val changedButtons = pointerButtonState xor nextButtons
        val relativeX =
            capturedAxis(
                event,
                MotionEvent.AXIS_RELATIVE_X,
                MotionEvent.AXIS_X,
                surfaceView.width,
                attachedLogicalWidth,
            )
        val relativeY =
            capturedAxis(
                event,
                MotionEvent.AXIS_RELATIVE_Y,
                MotionEvent.AXIS_Y,
                surfaceView.height,
                attachedLogicalHeight,
            )
        val horizontal = axisToFixed(event.getAxisValue(MotionEvent.AXIS_HSCROLL))
        val vertical = axisToFixed(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        val hasMotion = relativeX != 0 || relativeY != 0
        val hasAxis =
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
                (horizontal != 0 || vertical != 0)
        val count =
            (if (hasMotion) 1 else 0) +
                Integer.bitCount(changedButtons) +
                if (hasAxis) 1 else 0
        if (count == 0 || count > MAX_INPUT_RECORDS) {
            return false
        }
        val data = beginInputParcel(count)
        val reply = Parcel.obtain()
        try {
            if (hasMotion) {
                writeInputRecord(
                    data,
                    INPUT_POINTER_RELATIVE,
                    relativeX,
                    relativeY,
                    relativeX,
                    relativeY,
                    event.eventTime.toInt(),
                )
            }
            for (button in POINTER_BUTTONS) {
                if (changedButtons and button != 0) {
                    writeInputRecord(
                        data,
                        INPUT_POINTER_BUTTON_V2,
                        button,
                        if (nextButtons and button != 0) 1 else 0,
                        event.eventTime.toInt(),
                    )
                }
            }
            if (hasAxis) {
                writeInputRecord(
                    data,
                    INPUT_POINTER_AXIS,
                    horizontal,
                    vertical,
                    event.eventTime.toInt(),
                )
            }
            val submitted = sendInputParcel(data, reply)
            if (submitted) {
                pointerButtonState = nextButtons
            }
            return submitted
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun capturedAxis(
        event: MotionEvent,
        relativeAxis: Int,
        fallbackAxis: Int,
        viewExtent: Int,
        bufferExtent: Int,
    ): Int {
        val relative = event.getAxisValue(relativeAxis)
        val value = if (relative != 0f) relative else event.getAxisValue(fallbackAxis)
        val bufferValue =
            LauncherSurfaceCoordinatePolicy.relative(value, viewExtent, bufferExtent)
        return (
            bufferValue.coerceIn(-MAX_RELATIVE_PIXELS, MAX_RELATIVE_PIXELS) *
                RELATIVE_FIXED_SCALE
        ).roundToInt()
    }

    private fun submitPointerCaptureLost() {
        if (sessionId <= 0) {
            return
        }
        val data = beginInputParcel(1)
        val reply = Parcel.obtain()
        try {
            writeInputRecord(data, INPUT_POINTER_CAPTURE_LOST)
            sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun surfaceX(
        event: MotionEvent,
        pointerIndex: Int = 0,
    ): Int =
        LauncherSurfaceCoordinatePolicy.absolute(
            event.getX(pointerIndex),
            surfaceView.left,
            surfaceView.width,
            attachedLogicalWidth,
        )

    private fun surfaceY(
        event: MotionEvent,
        pointerIndex: Int = 0,
    ): Int =
        LauncherSurfaceCoordinatePolicy.absolute(
            event.getY(pointerIndex),
            surfaceView.top,
            surfaceView.height,
            attachedLogicalHeight,
        )

    private fun pointerButtonsAfter(event: MotionEvent): Int {
        val reported = event.buttonState and POINTER_BUTTON_MASK
        val actionButton = event.actionButton and POINTER_BUTTON_MASK
        return when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> reported or actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> reported and actionButton.inv()
            MotionEvent.ACTION_DOWN ->
                if (reported != 0) reported else pointerButtonState or MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> 0
            MotionEvent.ACTION_CANCEL -> 0
            MotionEvent.ACTION_MOVE ->
                if (reported != 0) reported else pointerButtonState
            else -> reported
        } and POINTER_BUTTON_MASK
    }

    private fun axisToFixed(value: Float): Int {
        if (!value.isFinite()) {
            return 0
        }
        return (value.coerceIn(-MAX_AXIS_STEPS, MAX_AXIS_STEPS) * AXIS_FIXED_SCALE).roundToInt()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputKeyLogsRemaining > 0) {
            inputKeyLogsRemaining--
            Log.i(
                TAG,
                "Input key action=${event.action} code=${event.keyCode} repeat=${event.repeatCount} " +
                    "source=0x${event.source.toString(16)} flags=0x${event.flags.toString(16)} " +
                    "time=${event.eventTime}",
            )
        }
        if (
            sessionId <= 0 ||
            event.keyCode == KeyEvent.KEYCODE_BACK ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
        ) {
            return super.dispatchKeyEvent(event)
        }
        val data = beginInputParcel(1)
        val reply = Parcel.obtain()
        try {
            val keyAction =
                if (event.action == KeyEvent.ACTION_UP) {
                    KEY_RELEASED
                } else if (event.repeatCount > 0) {
                    KEY_REPEATED
                } else {
                    KEY_PRESSED
                }
            val keyEventTime =
                LauncherInputTimestampPolicy.next(
                    event.eventTime.toInt(),
                    lastKeyEventTime,
                    hasKeyEventTime,
                )
            lastKeyEventTime = keyEventTime
            hasKeyEventTime = true
            writeInputRecord(
                data,
                INPUT_KEY,
                event.keyCode,
                keyAction,
                keyEventTime,
                event.metaState,
            )
            val sent = sendInputParcel(data, reply)
            if (testInputDebug) {
                Log.i(
                    TAG,
                    "Submitted input key code=${event.keyCode} action=$keyAction accepted=$sent",
                )
            }
            if (
                LauncherImeTouchPolicy.suppressImplicitAfterHardwareKey(
                    imeState.active,
                    softImeRequested,
                )
            ) {
                softImeRequested = false
                handler.removeCallbacks(showImeAfterTouch)
                getSystemService(InputMethodManager::class.java).restartInput(surfaceView)
                hideIme()
                handler.removeCallbacks(suppressImplicitImeAfterHardwareKey)
                handler.postDelayed(
                    suppressImplicitImeAfterHardwareKey,
                    IMPLICIT_IME_SUPPRESSION_DELAY_MILLIS,
                )
                handler.postDelayed(
                    suppressImplicitImeAfterHardwareKey,
                    IMPLICIT_IME_SUPPRESSION_SECOND_DELAY_MILLIS,
                )
                handler.postDelayed(
                    suppressImplicitImeAfterHardwareKey,
                    IMPLICIT_IME_SUPPRESSION_THIRD_DELAY_MILLIS,
                )
                handler.postDelayed(
                    suppressImplicitImeAfterHardwareKey,
                    IMPLICIT_IME_SUPPRESSION_FOURTH_DELAY_MILLIS,
                )
                handler.postDelayed(
                    suppressImplicitImeAfterHardwareKey,
                    IMPLICIT_IME_SUPPRESSION_FINAL_DELAY_MILLIS,
                )
            }
            return sent
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun beginInputParcel(count: Int): Parcel =
        Parcel.obtain().apply {
            writeInterfaceToken(INTERFACE)
            writeInt(PROTOCOL_VERSION)
            writeInt(sessionId)
            writeInt(count)
        }

    private fun writeInputRecord(
        data: Parcel,
        kind: Int,
        a: Int = 0,
        b: Int = 0,
        c: Int = 0,
        d: Int = 0,
        e: Int = 0,
    ) {
        data.writeInt(kind)
        data.writeInt(a)
        data.writeInt(b)
        data.writeInt(c)
        data.writeInt(d)
        data.writeInt(e)
    }

    private fun sendInputParcel(
        data: Parcel,
        reply: Parcel,
    ): Boolean {
        val service = remote ?: return false
        return try {
            service.transact(TRANSACTION_INPUT, data, reply, 0) &&
                run {
                    reply.readException()
                    reply.readInt() == RESULT_OK
                }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit launcher input", error)
            false
        }
    }

    private fun submitAccessibilityAction(
        nodeId: Int,
        action: String,
        text: String,
    ): Boolean {
        val service = remote ?: return false
        val internalRefresh = nodeId == 0 && action == "refresh"
        if (
            sessionId <= 0 ||
            (!internalRefresh && nodeId !in 1..MAX_ACCESSIBILITY_NODE_ID) ||
            (action == "refresh" && !internalRefresh) ||
            (internalRefresh && text.isNotEmpty()) ||
            action !in ACCESSIBILITY_ACTIONS ||
            text.length > MAX_ACCESSIBILITY_TEXT_UTF16 ||
            !LauncherUtf8Policy.lengthAtMost(text, MAX_ACCESSIBILITY_TEXT_BYTES) ||
            (action != "set-text" && text.isNotEmpty())
        ) {
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(sessionId)
            data.writeInt(nodeId)
            data.writeString(action)
            data.writeString(text)
            service.transact(
                TRANSACTION_ACCESSIBILITY_ACTION,
                data,
                reply,
                0,
            ) &&
                run {
                    reply.readException()
                    reply.readInt() == RESULT_OK && reply.dataAvail() == 0
                }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit accessibility action", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun submitAccessibilityMenuFallback(
        x: Int,
        y: Int,
        transition: Boolean,
    ): Boolean {
        if (
            sessionId <= 0 ||
            x !in 0 until surfaceView.width.coerceAtLeast(1) ||
            y !in 0 until surfaceView.height.coerceAtLeast(1)
        ) {
            return false
        }
        val eventTime = SystemClock.uptimeMillis().toInt()
        val data = beginInputParcel(2)
        val reply = Parcel.obtain()
        return try {
            writeInputRecord(
                data,
                INPUT_TOUCH_DOWN,
                ACCESSIBILITY_TOUCH_ID,
                x,
                y,
                eventTime,
            )
            writeInputRecord(
                data,
                INPUT_TOUCH_UP,
                ACCESSIBILITY_TOUCH_ID,
                eventTime + if (transition) ACCESSIBILITY_MENU_HOLD_MILLIS else 1,
            )
            sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun validSecretCallback(
        operation: Int,
        arguments: List<String>,
        descriptor: ParcelFileDescriptor?,
    ): Boolean =
        when (operation) {
            SECRET_OPERATION_STORE -> arguments.size == 4 && descriptor != null
            SECRET_OPERATION_READ -> arguments.size == 1 && descriptor != null
            SECRET_OPERATION_DELETE -> arguments.size == 1 && descriptor == null
            SECRET_OPERATION_LIST,
            SECRET_OPERATION_CATALOG,
            -> arguments.isEmpty() && descriptor != null
            else -> false
        }

    private fun handleSecretCallback(
        operation: Int,
        arguments: List<String>,
        descriptor: ParcelFileDescriptor?,
    ): String =
        when (operation) {
            SECRET_OPERATION_STORE -> {
                secretStore.store(
                    arguments[0],
                    arguments[1],
                    arguments[2],
                    arguments[3],
                    checkNotNull(descriptor).fileDescriptor,
                )
                "OK"
            }
            SECRET_OPERATION_READ -> {
                val result =
                    secretStore.read(
                        arguments[0],
                        checkNotNull(descriptor).fileDescriptor,
                    )
                if (result == null) {
                    "ERROR\tNOT_FOUND"
                } else {
                    "OK\t${encodeSecretField(result.label)}\t" +
                        "${encodeSecretField(result.attributes)}\t${result.secretBytes}"
                }
            }
            SECRET_OPERATION_DELETE ->
                if (secretStore.delete(arguments[0])) "OK" else "ERROR\tFAILED"
            SECRET_OPERATION_LIST ->
                "OK\t${secretStore.list(checkNotNull(descriptor).fileDescriptor)}"
            SECRET_OPERATION_CATALOG ->
                "OK\t${secretStore.catalog(checkNotNull(descriptor).fileDescriptor)}"
            else -> "ERROR\tINVALID_REQUEST"
        }

    private fun validCameraCallback(
        operation: Int,
        width: Int,
        height: Int,
        facing: Int,
        descriptor: ParcelFileDescriptor?,
    ): Boolean =
        when (operation) {
            CAMERA_OPERATION_REQUEST,
            CAMERA_OPERATION_CHECK,
            -> width == 0 && height == 0 && facing == 0 && descriptor == null
            CAMERA_OPERATION_CAPTURE,
            CAMERA_OPERATION_STREAM,
            -> width in 1..MAX_CAMERA_DIMENSION &&
                height in 1..MAX_CAMERA_DIMENSION &&
                facing in CAMERA_FACING_BACK..CAMERA_FACING_FRONT &&
                descriptor != null
            else -> false
        }

    private fun handleCameraCallback(
        operation: Int,
        width: Int,
        height: Int,
        facing: Int,
        descriptor: ParcelFileDescriptor?,
    ): String =
        when (operation) {
            CAMERA_OPERATION_REQUEST -> requestCameraPermission()
            CAMERA_OPERATION_CHECK -> cameraPermissionState()
            CAMERA_OPERATION_CAPTURE -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermissionState()
                } else {
                    val file = checkNotNull(descriptor).fileDescriptor
                    val stat = Os.fstat(file)
                    require(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG) {
                        "Camera capture requires a regular output file"
                    }
                    Os.ftruncate(file, 0)
                    Os.lseek(file, 0, OsConstants.SEEK_SET)
                    val result =
                        cameraIntegration.captureJpeg(
                            file,
                            width,
                            height,
                            facing == CAMERA_FACING_FRONT,
                        )
                    Log.i(
                        TAG,
                        "Captured Linux camera JPEG ${result.width}x${result.height} " +
                            "bytes=${result.bytes}",
                    )
                    "OK\t${result.width}\t${result.height}\t${result.bytes}"
                }
            }
            CAMERA_OPERATION_STREAM -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermissionState()
                } else if (!awaitCameraForeground()) {
                    "ERROR\tNOT_READY"
                } else {
                    val file = checkNotNull(descriptor).fileDescriptor
                    val type = Os.fstat(file).st_mode and OsConstants.S_IFMT
                    require(type == OsConstants.S_IFSOCK || type == OsConstants.S_IFIFO) {
                        "Camera stream requires a socket or pipe"
                    }
                    Log.i(TAG, "Starting Linux camera stream ${width}x$height")
                    cameraIntegration.streamI420(
                        file,
                        width,
                        height,
                        facing == CAMERA_FACING_FRONT,
                    )
                    Log.i(TAG, "Linux camera stream stopped")
                    "OK"
                }
            }
            else -> "ERROR\tINVALID_REQUEST"
        }

    private fun awaitCameraForeground(): Boolean =
        synchronized(cameraLifecycleMonitor) {
            try {
                while (!activityResumed && !cameraLifecycleClosed) {
                    cameraLifecycleMonitor.wait()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized false
            }
            activityResumed && !cameraLifecycleClosed
        }

    private fun requestCameraPermission(): String {
        if (
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        ) {
            return "ERROR\tUNAVAILABLE"
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return "OK"
        }
        synchronized(this) {
            if (cameraPermissionRequestInFlight) {
                return "ERROR\tPERMISSION_REQUESTED"
            }
            if (
                getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                    .getBoolean(CAMERA_PERMISSION_REQUESTED, false)
            ) {
                return "ERROR\tPERMISSION_DENIED"
            }
            if (!activityResumed) return "ERROR\tNOT_READY"
            cameraPermissionRequestInFlight = true
        }
        handler.post {
            if (isFinishing || isDestroyed) {
                cameraPermissionRequestInFlight = false
                return@post
            }
            runCatching {
                getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(CAMERA_PERMISSION_REQUESTED, true)
                    .apply()
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST,
                )
            }.onFailure { error ->
                cameraPermissionRequestInFlight = false
                getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(CAMERA_PERMISSION_REQUESTED, false)
                    .apply()
                Log.e(TAG, "Could not request Linux camera permission", error)
            }
        }
        Log.i(TAG, "Requested Android camera permission for Linux camera access")
        return "ERROR\tPERMISSION_REQUESTED"
    }

    private fun cameraPermissionState(): String {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return "ERROR\tUNAVAILABLE"
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return "OK"
        }
        if (cameraPermissionRequestInFlight) {
            return "ERROR\tPERMISSION_REQUESTED"
        }
        return if (
            getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                .getBoolean(CAMERA_PERMISSION_REQUESTED, false)
        ) {
            "ERROR\tPERMISSION_DENIED"
        } else {
            "ERROR\tPERMISSION_NOT_REQUESTED"
        }
    }

    private fun encodeSecretField(value: String): String =
        Base64.encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun openSession() {
        val service = remote ?: return
        if (incomingDocumentRejected) {
            status.setText(R.string.launcher_document_rejected)
            status.visibility = View.VISIBLE
            return
        }
        if (incomingDocumentRequest != null && preparedIncomingDocument == null) {
            prepareIncomingDocument()
            return
        }
        remoteStatus = STATUS_STARTING
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeStrongBinder(clientToken)
            val incoming = preparedIncomingDocument
            data.writeInt(if (incoming == null) 0 else 1)
            if (incoming != null) {
                data.writeString(incoming.displayName)
                data.writeString(incoming.mimeType)
                incoming.descriptor.writeToParcel(data, 0)
            }
            data.writeInt(requestedToplevelId)
            if (!service.transact(TRANSACTION_OPEN, data, reply, 0)) {
                showUnavailable()
                return
            }
            reply.readException()
            when (reply.readInt()) {
                RESULT_OK -> {
                    preparedIncomingDocument?.descriptor?.close()
                    preparedIncomingDocument = null
                    incomingDocumentRequest = null
                    sessionId = reply.readInt()
                    val label = reply.readString().orEmpty().take(256)
                    reply.readInt()
                    launcherOrientationPolicy =
                        if (reply.dataAvail() >= Int.SIZE_BYTES) {
                            reply.readInt()
                        } else {
                            LauncherOrientationPolicy.DEFAULT
                        }
                    if (sessionId <= 0 || label.isEmpty()) {
                        showUnavailable()
                        return
                    }
                    applyLauncherOrientationPolicy(resources.configuration)
                    Log.i(
                        TAG,
                        "Applied orientation policy=$launcherOrientationPolicy " +
                            "requested=$requestedOrientation " +
                            "smallestWidthDp=${resources.configuration.smallestScreenWidthDp}",
                    )
                    status.text = getString(R.string.launcher_connected, label)
                    Log.i(TAG, "Authenticated session=$sessionId")
                    attachSurface()
                    if (hasWindowFocus()) {
                        startClipboardListening()
                        submitAndroidClipboard()
                    }
                }
                RESULT_NOT_READY -> {
                    reply.readInt()
                    reply.readString()
                    reply.readInt()
                    if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt()
                    retryOpen()
                }
                else -> {
                    reply.readInt()
                    reply.readString()
                    reply.readInt()
                    if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt()
                    status.setText(R.string.launcher_rejected)
                }
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Launcher session failed", error)
            showUnavailable()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun acceptIncomingIntent(value: Intent) {
        incomingDocumentCancellation?.cancel()
        incomingDocumentCancellation = null
        incomingDocumentGeneration++
        incomingDocumentRejected = false
        if (value.action != Intent.ACTION_VIEW && value.action != Intent.ACTION_SEND) {
            incomingDocumentRequest = null
            return
        }
        val declared =
            applicationMetadata()
                .getString(MIME_TYPES)
                ?.takeIf { spec -> spec.startsWith("m:") }
                ?.drop(2)
                ?.let(LauncherIntentMimePolicy::parseSpec)
        val mimeType = value.type.orEmpty()
        @Suppress("DEPRECATION")
        val uri =
            if (value.action == Intent.ACTION_VIEW) {
                value.data
            } else {
                value.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        if (
            declared == null ||
            uri?.scheme != "content" ||
            !LauncherIntentMimePolicy.matches(declared, mimeType)
        ) {
            incomingDocumentRequest = null
            incomingDocumentRejected = true
            return
        }
        incomingDocumentRequest = IncomingDocumentRequest(uri, mimeType)
    }

    private fun prepareIncomingDocument() {
        val request = incomingDocumentRequest ?: return
        if (preparingIncomingDocument) return
        preparingIncomingDocument = true
        val generation = incomingDocumentGeneration
        val cancellation = CancellationSignal()
        incomingDocumentCancellation = cancellation
        documentHandler.post {
            val prepared =
                runCatching {
                    val providerType = contentResolver.getType(request.uri)
                    val declared =
                        applicationMetadata()
                            .getString(MIME_TYPES)
                            ?.removePrefix("m:")
                            ?.let(LauncherIntentMimePolicy::parseSpec)
                            ?: error("Missing signed MIME policy")
                    if (
                        providerType != null &&
                        !LauncherIntentMimePolicy.matches(declared, providerType)
                    ) {
                        error("Provider MIME type is not declared")
                    }
                    val name =
                        queryDocumentName(request.uri)
                            ?: "Android document"
                    val descriptor =
                        contentResolver.openFileDescriptor(request.uri, "r", cancellation)
                            ?: error("Provider returned no descriptor")
                    PreparedIncomingDocument(name, request.mimeType, descriptor)
                }.getOrNull()
            handler.post {
                if (
                    generation != incomingDocumentGeneration ||
                    incomingDocumentRequest != request
                ) {
                    prepared?.descriptor?.close()
                    return@post
                }
                incomingDocumentCancellation = null
                preparingIncomingDocument = false
                if (prepared == null || isFinishing || isDestroyed) {
                    prepared?.descriptor?.close()
                    incomingDocumentRejected = true
                    status.setText(R.string.launcher_document_rejected)
                    status.visibility = View.VISIBLE
                } else {
                    preparedIncomingDocument = prepared
                    openSession()
                }
            }
        }
    }

    private fun attachSurface() {
        val service = remote ?: return
        val activeSession = sessionId
        val surface = surfaceView.holder.surface
        val width = surfaceView.width
        val height = surfaceView.height
        val configuration = resources.configuration
        val densityDpi = configuration.densityDpi.coerceIn(MIN_DENSITY_DPI, MAX_DENSITY_DPI)
        val fontScaleMillis =
            (configuration.fontScale * 1_000f)
                .toInt()
                .coerceIn(MIN_FONT_SCALE_MILLIS, MAX_FONT_SCALE_MILLIS)
        if (
            activeSession <= 0 ||
            !surface.isValid ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }
        if (
            attachedSurface === surface &&
            attachedWidth == width &&
            attachedHeight == height &&
            attachedDensityDpi == densityDpi &&
            attachedFontScaleMillis == fontScaleMillis
        ) {
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            data.writeInt(width)
            data.writeInt(height)
            surface.writeToParcel(data, 0)
            data.writeInt(densityDpi)
            data.writeInt(fontScaleMillis)
            if (service.transact(TRANSACTION_ATTACH_SURFACE, data, reply, 0)) {
                reply.readException()
                if (reply.readInt() == RESULT_OK) {
                    val logicalWidth = reply.readInt()
                    val logicalHeight = reply.readInt()
                    check(logicalWidth in 1..MAX_LOGICAL_SURFACE_DIMENSION)
                    check(logicalHeight in 1..MAX_LOGICAL_SURFACE_DIMENSION)
                    attachedSurface = surface
                    attachedWidth = width
                    attachedHeight = height
                    attachedLogicalWidth = logicalWidth
                    attachedLogicalHeight = logicalHeight
                    attachedDensityDpi = densityDpi
                    attachedFontScaleMillis = fontScaleMillis
                    Log.i(
                        TAG,
                        "Attached Surface session=$activeSession size=${width}x$height " +
                            "logical=${logicalWidth}x$logicalHeight",
                    )
                    accessibilityProvider.refreshBoundsAfterTransition()
                    submitHostActive(hasWindowFocus())
                    if (hasWindowFocus()) {
                        submitAndroidClipboard()
                    }
                }
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not attach launcher Surface", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun detachSurface() {
        val service = remote
        val activeSession = sessionId
        val wasAttached = attachedSurface != null
        resetSurfaceAttachment()
        pointerButtonState = 0
        desktopTouchSequenceState = LauncherDesktopTouchPolicy.IDLE
        if (service == null || activeSession <= 0 || !wasAttached) {
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            if (service.transact(TRANSACTION_DETACH_SURFACE, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not detach launcher Surface", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
        status.visibility = View.VISIBLE
    }

    private fun submitHostActive(active: Boolean) {
        if (sessionId <= 0) {
            return
        }
        val data = beginInputParcel(1)
        val reply = Parcel.obtain()
        try {
            writeInputRecord(
                data,
                INPUT_HOST_ACTIVE,
                if (active) 1 else 0,
                android.os.SystemClock.uptimeMillis().toInt(),
            )
            sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
        if (!active) {
            pointerButtonState = 0
            desktopTouchSequenceState = LauncherDesktopTouchPolicy.IDLE
        }
    }

    private fun startClipboardListening() {
        if (!clipboardListening) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            clipboardListening = true
        }
    }

    private fun stopClipboardListening() {
        handler.removeCallbacks(clipboardRetry)
        if (clipboardListening) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            clipboardListening = false
        }
    }

    private fun submitAndroidClipboard(retryOnUnavailable: Boolean = true) {
        val service = remote ?: return
        val activeSession = sessionId
        if (activeSession <= 0 || !hasWindowFocus()) {
            return
        }
        val clip =
            runCatching { clipboardManager.primaryClip }
                .getOrElse {
                    Log.w(TAG, "Android clipboard is unavailable while launcher is focused", it)
                    if (retryOnUnavailable && hasWindowFocus()) {
                        handler.removeCallbacks(clipboardRetry)
                        handler.postDelayed(clipboardRetry, CLIPBOARD_FOCUS_RETRY_MILLIS)
                    }
                    return
                }
        val item =
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)
            } else {
                null
            }
        val text = item?.text?.toString()
        val html = item?.htmlText
        val boundedText =
            if (text == null || text.length <= MAX_CLIPBOARD_UTF16) {
                text
            } else {
                Log.w(TAG, "Android clipboard text exceeds launcher limit")
                null
            }
        val boundedHtml =
            if (boundedText != null && html != null && html.length <= MAX_CLIPBOARD_UTF16) {
                html
            } else {
                if (html != null && html.length > MAX_CLIPBOARD_UTF16) {
                    Log.w(TAG, "Android clipboard HTML exceeds launcher limit")
                }
                null
            }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            data.writeInt(if (boundedText == null) 0 else 1)
            if (boundedText != null) {
                data.writeString(boundedText)
                data.writeInt(if (boundedHtml == null) 0 else 1)
                if (boundedHtml != null) {
                    data.writeString(boundedHtml)
                }
            }
            if (service.transact(TRANSACTION_CLIPBOARD, data, reply, 0)) {
                reply.readException()
                val result = reply.readInt()
                if (result != RESULT_OK && result != RESULT_NOT_READY) {
                    Log.w(TAG, "Manager rejected Android clipboard result=$result")
                }
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit Android clipboard", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun applyLinuxClipboard(
        text: String?,
        html: String?,
    ) {
        if (!hasWindowFocus()) {
            hasPendingLinuxClipboard = true
            pendingLinuxClipboardText = text
            pendingLinuxClipboardHtml = html
            return
        }
        publishLinuxClipboard(text, html)
    }

    private fun applyPendingLinuxClipboard(): Boolean {
        if (!hasPendingLinuxClipboard) {
            return false
        }
        val text = pendingLinuxClipboardText
        val html = pendingLinuxClipboardHtml
        hasPendingLinuxClipboard = false
        pendingLinuxClipboardText = null
        pendingLinuxClipboardHtml = null
        publishLinuxClipboard(text, html)
        return true
    }

    private fun publishLinuxClipboard(
        text: String?,
        html: String?,
    ) {
        runCatching {
            if (text == null) {
                clipboardManager.clearPrimaryClip()
            } else if (html != null) {
                clipboardManager.setPrimaryClip(ClipData.newHtmlText(appLabel(), text, html))
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(appLabel(), text))
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not publish Linux clipboard to Android", error)
        }
    }

    private inner class LauncherSurfaceView(
        context: Context,
    ) : SurfaceView(context) {
        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider =
            accessibilityProvider

        override fun onCapturedPointerEvent(event: MotionEvent): Boolean =
            submitCapturedPointer(event) || super.onCapturedPointerEvent(event)

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            if (!hasCapture) {
                pointerCaptureReleaseInFlight = false
            }
            if (!hasCapture && pointerRecaptureAfterRelease) {
                pointerRecaptureAfterRelease = false
                applyPointerCapture(true)
                return
            }
            if (!hasCapture && pointerCaptureRequested && hasWindowFocus()) {
                pointerCaptureRequested = false
                submitPointerCaptureLost()
            }
        }

        override fun onCheckIsTextEditor(): Boolean = imeState.active && softImeRequested

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val state = imeState
            if (!state.active || !softImeRequested) {
                return null
            }
            outAttrs.inputType = androidInputType(state.hint, state.purpose)
            outAttrs.imeOptions =
                androidImeOptions(state.hint, state.purpose) or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
            outAttrs.initialSelStart = state.anchor
            outAttrs.initialSelEnd = state.cursor
            if (Build.VERSION.SDK_INT >= 30) {
                outAttrs.setInitialSurroundingSubText(state.text, 0)
            }
            return LauncherInputConnection(this, state)
        }
    }

    private inner class LauncherInputConnection(
        target: View,
        state: ImeState,
    ) : BaseInputConnection(target, true) {
        private val composition = ImeCompositionState()
        private val editorBuffer: Editable = checkNotNull(editable)

        init {
            editorBuffer.clear()
            editorBuffer.append(state.text)
            Selection.setSelection(
                editorBuffer,
                state.anchor.coerceIn(0, editorBuffer.length),
                state.cursor.coerceIn(0, editorBuffer.length),
            )
        }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            val value = text?.toString().orEmpty()
            val cursorUtf16 =
                if (newCursorPosition > 0) {
                    value.length + newCursorPosition - 1
                } else {
                    newCursorPosition
                }.coerceIn(0, value.length)
            val cursorBytes = utf8Length(value, 0, cursorUtf16)
            if (
                value.length > MAX_IME_UTF16 ||
                cursorBytes < 0 ||
                utf8Length(value, 0, value.length) > MAX_IME_BYTES
            ) {
                return false
            }
            val submitted =
                submitIme(
                    IME_PREEDIT,
                    value,
                    cursorBytes,
                    cursorBytes,
                )
            if (submitted) {
                composition.replaceAcceptedPreedit(value)
            }
            return submitted && super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            val acceptedPreedit = composition.finishCommit()
            val submitted =
                acceptedPreedit == null ||
                    submitIme(IME_COMMIT, acceptedPreedit, 0, 0)
            if (submitted) {
                composition.clear()
            }
            return submitted && super.finishComposingText()
        }

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            val value = text?.toString().orEmpty()
            val length = utf8Length(value, 0, value.length)
            if (
                value.length > MAX_IME_UTF16 ||
                length < 0 ||
                length > MAX_IME_BYTES
            ) {
                return false
            }
            val submitted = submitIme(IME_COMMIT, value, 0, 0)
            if (submitted) {
                composition.clear()
            }
            return submitted && super.commitText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            if (
                beforeLength < 0 ||
                afterLength < 0 ||
                beforeLength + afterLength > MAX_IME_UTF16
            ) {
                return false
            }
            val cursor =
                Selection.getSelectionEnd(editorBuffer).coerceIn(0, editorBuffer.length)
            val beforeStart = (cursor - beforeLength).coerceAtLeast(0)
            val afterEnd = (cursor + afterLength).coerceAtMost(editorBuffer.length)
            val beforeBytes = utf8Length(editorBuffer, beforeStart, cursor)
            val afterBytes = utf8Length(editorBuffer, cursor, afterEnd)
            if (
                beforeBytes < 0 ||
                afterBytes < 0 ||
                beforeBytes + afterBytes > MAX_IME_BYTES
            ) {
                return false
            }
            return submitIme(IME_DELETE, null, beforeBytes, afterBytes) &&
                super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            if (
                beforeLength < 0 ||
                afterLength < 0 ||
                beforeLength + afterLength > MAX_IME_UTF16
            ) {
                return false
            }
            val cursor =
                Selection.getSelectionEnd(editorBuffer).coerceIn(0, editorBuffer.length)
            val beforeStart =
                runCatching {
                    Character.offsetByCodePoints(editorBuffer, cursor, -beforeLength)
                }.getOrElse { 0 }
            val afterEnd =
                runCatching {
                    Character.offsetByCodePoints(editorBuffer, cursor, afterLength)
                }.getOrElse { editorBuffer.length }
            val beforeBytes = utf8Length(editorBuffer, beforeStart, cursor)
            val afterBytes = utf8Length(editorBuffer, cursor, afterEnd)
            if (
                beforeBytes < 0 ||
                afterBytes < 0 ||
                beforeBytes + afterBytes > MAX_IME_BYTES
            ) {
                return false
            }
            return submitIme(IME_DELETE, null, beforeBytes, afterBytes) &&
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

        override fun performEditorAction(actionCode: Int): Boolean =
            actionCode in 0..MAX_IME_ACTION &&
                submitIme(IME_EDITOR_ACTION, null, actionCode, 0)

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            this@LauncherActivity.dispatchKeyEvent(event)
    }

    private fun submitIme(
        operation: Int,
        text: String?,
        a: Int,
        b: Int,
    ): Boolean {
        val service = remote ?: return false
        val activeSession = sessionId
        if (activeSession <= 0 || !imeState.active) {
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            data.writeInt(operation)
            if (operation == IME_COMMIT || operation == IME_PREEDIT) {
                data.writeString(text ?: return false)
            }
            data.writeInt(a)
            data.writeInt(b)
            if (!service.transact(TRANSACTION_IME, data, reply, 0)) {
                return false
            }
            reply.readException()
            return reply.readInt() == RESULT_OK
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit launcher IME command", error)
            return false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun applyImeState(
        next: ImeState,
        restartOnApply: Boolean = false,
    ) {
        val previous = imeState
        imeState = next
        callbackImeActive = next.active
        val requestedAfterTouch =
            LauncherImeTouchPolicy.requestOnActivationAfterTouch(
                imeActivationTouchPending,
                next.active,
                next.editorEvidence,
            )
        val retainedSoftImeRequest =
            LauncherImeTouchPolicy.retainSoftImeRequest(
                softImeRequested,
                softImeExplicitlyRequestedForAmbiguousInput,
                next.active,
                next.editorEvidence,
            )
        if (softImeRequested && !retainedSoftImeRequest && next.active && hasWindowFocus()) {
            hideIme()
        }
        softImeRequested = retainedSoftImeRequest
        if (requestedAfterTouch) {
            softImeRequested = true
            softImeExplicitlyRequestedForAmbiguousInput = false
        }
        imeActivationTouchPending =
            LauncherImeTouchPolicy.activationTouchPendingAfterState(
                imeActivationTouchPending,
                next.active,
                next.editorEvidence,
            )
        if (!imeActivationTouchPending) {
            handler.removeCallbacks(clearImeActivationTouchPending)
        }
        if (!hasWindowFocus()) {
            return
        }
        if (!next.active) {
            softImeRequested = false
            softImeExplicitlyRequestedForAmbiguousInput = false
            imeActivationTouchPending = false
            ambiguousImeLongPressEligible = false
            handler.removeCallbacks(clearImeActivationTouchPending)
            handler.removeCallbacks(showImeAfterTouch)
            if (previous.active) {
                hideIme()
            }
            return
        }
        val restart =
            restartOnApply ||
                !previous.active ||
                previous.hint != next.hint ||
                previous.purpose != next.purpose
        if (restart) {
            val input = getSystemService(InputMethodManager::class.java)
            surfaceView.requestFocus()
            input.restartInput(surfaceView)
            if (softImeRequested) {
                showIme(restart = false)
            }
        } else {
            getSystemService(InputMethodManager::class.java)
                .updateSelection(surfaceView, next.cursor, next.anchor, -1, -1)
            if (requestedAfterTouch) {
                showIme(restart = false)
            }
        }
    }

    private fun showIme(restart: Boolean) {
        if (!imeState.active || !hasWindowFocus()) {
            return
        }
        surfaceView.requestFocus()
        val input = getSystemService(InputMethodManager::class.java)
        if (restart) {
            input.restartInput(surfaceView)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            surfaceView.windowInsetsController?.show(WindowInsets.Type.ime())
        }
        input.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme() {
        val input = getSystemService(InputMethodManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            surfaceView.windowInsetsController?.hide(WindowInsets.Type.ime())
        }
        input.hideSoftInputFromWindow(surfaceView.windowToken, 0)
    }

    private fun utf8Length(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Int {
        if (start !in 0..end || end > text.length) {
            return -1
        }
        var bytes = 0
        var index = start
        while (index < end) {
            val character = text[index]
            when {
                character.code <= 0x7f -> {
                    bytes++
                    index++
                }
                character.code <= 0x7ff -> {
                    bytes += 2
                    index++
                }
                Character.isHighSurrogate(character) -> {
                    if (
                        index + 1 >= end ||
                        !Character.isLowSurrogate(text[index + 1])
                    ) {
                        return -1
                    }
                    bytes += 4
                    index += 2
                }
                Character.isLowSurrogate(character) -> return -1
                else -> {
                    bytes += 3
                    index++
                }
            }
        }
        return bytes
    }

    private fun androidInputType(
        hint: Int,
        purpose: Int,
    ): Int {
        var type =
            when (purpose) {
                2 -> InputType.TYPE_CLASS_NUMBER
                3 ->
                    InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                4 -> InputType.TYPE_CLASS_PHONE
                5 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                6 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                7 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                8 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                9 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                10 -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                11 -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
                12 -> InputType.TYPE_CLASS_DATETIME
                13 ->
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
        if (hint and 2 != 0) type = type or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        if (hint and 4 != 0) type = type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (hint and 16 != 0) type = type or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        if (hint and 32 != 0) type = type or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        if (hint and 512 != 0) type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (hint and 128 != 0 || purpose == 13) {
            type = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        return type
    }

    private fun androidImeOptions(
        hint: Int,
        purpose: Int,
    ): Int =
        if (hint and 512 != 0) {
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        } else {
            when (purpose) {
                5 -> EditorInfo.IME_ACTION_GO
                6 -> EditorInfo.IME_ACTION_SEND
                else -> EditorInfo.IME_ACTION_DONE
            }
        }

    private fun retryOpen() {
        if (++attempts > MAX_OPEN_ATTEMPTS) {
            showUnavailable()
            return
        }
        status.setText(R.string.launcher_preparing)
        handler.postDelayed(::openSession, OPEN_RETRY_MILLIS)
    }

    private fun closeSession() {
        val activeSession = sessionId
        detachSurface()
        pendingActions.clear()
        sessionId = 0
        remoteStatus = STATUS_STARTING
        val service = remote
        if (service == null || activeSession <= 0) {
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            if (service.transact(TRANSACTION_CLOSE, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not close launcher session", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun beginDocumentSave(
        requestId: Int,
        title: String,
        suggestedName: String,
        mimeSpec: String,
    ) {
        val mimeTypes = DocumentMimePolicy.parse(mimeSpec)
        if (
            requestId <= 0 ||
            sessionId <= 0 ||
            remote == null ||
            pendingDocumentRequestId != 0 ||
            isFinishing ||
            isDestroyed ||
            mimeTypes == null
        ) {
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_SAVE,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        pendingDocumentRequestId = requestId
        pendingDocumentOperation = DOCUMENT_OPERATION_SAVE
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                applyDocumentMimeTypes(this, mimeTypes)
                putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        try {
            // ACTION_CREATE_DOCUMENT already resolves to Android's
            // authoritative DocumentsUI. Wrapping it in ACTION_CHOOSER adds a
            // second task layer on some Android releases and can lose the
            // cancellation result when that resolver task finishes.
            startActivityForResult(intent, DOCUMENT_SAVE_REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "No Android document provider is available", error)
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_SAVE,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        } catch (error: RuntimeException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "Could not open Android document chooser", error)
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_SAVE,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        }
    }

    private fun beginDirectoryOpen(
        requestId: Int,
        title: String,
    ) {
        if (
            requestId <= 0 ||
            sessionId <= 0 ||
            title.isBlank() ||
            title.length > MAX_DOCUMENT_TITLE_UTF16
        ) {
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_DIRECTORY,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        pendingDocumentRequestId = requestId
        pendingDocumentOperation = DOCUMENT_OPERATION_DIRECTORY
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(Intent.EXTRA_TITLE, title)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
        try {
            startActivityForResult(intent, DOCUMENT_OPEN_REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "No Android directory provider is available", error)
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_DIRECTORY,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        } catch (error: RuntimeException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "Could not open Android directory chooser", error)
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_DIRECTORY,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        }
    }

    private fun beginDocumentOpen(
        requestId: Int,
        title: String,
        mimeSpec: String,
        multiple: Boolean,
    ) {
        val operation =
            if (multiple) DOCUMENT_OPERATION_OPEN_MULTIPLE else DOCUMENT_OPERATION_OPEN
        val mimeTypes = DocumentMimePolicy.parse(mimeSpec)
        if (
            requestId <= 0 ||
            sessionId <= 0 ||
            remote == null ||
            pendingDocumentRequestId != 0 ||
            isFinishing ||
            isDestroyed ||
            mimeTypes == null
        ) {
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        pendingDocumentRequestId = requestId
        pendingDocumentOperation = operation
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                applyDocumentMimeTypes(this, mimeTypes)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                putExtra(Intent.EXTRA_TITLE, title)
                if (multiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        try {
            startActivityForResult(intent, DOCUMENT_OPEN_REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "No Android document provider is available", error)
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        } catch (error: RuntimeException) {
            pendingDocumentRequestId = 0
            pendingDocumentOperation = 0
            Log.w(TAG, "Could not open Android document chooser", error)
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
        }
    }

    private fun applyDocumentMimeTypes(
        intent: Intent,
        mimeTypes: List<String>,
    ) {
        val baseType = DocumentMimePolicy.androidBaseType(mimeTypes)
        intent.type = baseType
        if (mimeTypes.size > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
        Log.i(
            TAG,
            "Opening Android document chooser baseMime=$baseType mimeCount=${mimeTypes.size}",
        )
    }

    private fun cancelPendingDocumentRequest() {
        val requestId = pendingDocumentRequestId
        val operation = pendingDocumentOperation
        pendingDocumentRequestId = 0
        pendingDocumentOperation = 0
        if (
            requestId > 0 &&
            operation in DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY
        ) {
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_CANCELLED,
                null,
                "",
                false,
            )
        }
    }

    private fun sendOpenDocumentResults(
        requestId: Int,
        operation: Int,
        resultIntent: Intent,
    ) {
        if (
            operation != DOCUMENT_OPERATION_OPEN &&
            operation != DOCUMENT_OPERATION_OPEN_MULTIPLE
        ) {
            return
        }
        val uris = ArrayList<Uri>(MAX_OPEN_DOCUMENTS)
        val clip = resultIntent.clipData
        if (clip != null) {
            if (clip.itemCount !in 1..MAX_OPEN_DOCUMENTS) {
                sendDocumentResult(
                    requestId,
                    operation,
                    DOCUMENT_RESULT_FAILED,
                    null,
                    "",
                    false,
                )
                return
            }
            repeat(clip.itemCount) { index ->
                val uri = clip.getItemAt(index).uri
                if (uri == null || uri in uris) {
                    sendDocumentResult(
                        requestId,
                        operation,
                        DOCUMENT_RESULT_FAILED,
                        null,
                        "",
                        false,
                    )
                    return
                }
                uris += uri
            }
        } else {
            resultIntent.data?.let(uris::add)
        }
        if (
            uris.isEmpty() ||
            (operation == DOCUMENT_OPERATION_OPEN && uris.size != 1)
        ) {
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        val documents = ArrayList<OpenDocument>(uris.size)
        try {
            for (uri in uris) {
                val displayName =
                    queryDocumentName(uri)
                        ?: error("Android document has no safe display name")
                val descriptor =
                    contentResolver.openFileDescriptor(uri, "r")
                        ?: error("Android provider returned no document descriptor")
                documents += OpenDocument(displayName, descriptor)
            }
        } catch (error: Exception) {
            documents.forEach { document ->
                runCatching { document.descriptor.close() }
            }
            Log.w(TAG, "Could not open Android document sources", error)
            sendDocumentResult(
                requestId,
                operation,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        try {
            sendOpenDocumentBatch(
                requestId,
                operation,
                documents,
            )
        } finally {
            documents.forEach { document ->
                runCatching { document.descriptor.close() }
            }
        }
    }

    private fun sendDirectoryResult(
        requestId: Int,
        resultIntent: Intent,
    ) {
        val treeUri = resultIntent.data
        val rootUri =
            treeUri?.let { uri ->
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(
                        uri,
                        DocumentsContract.getTreeDocumentId(uri),
                    )
                }.getOrNull()
            }
        val displayName = rootUri?.let(::queryDocumentName)
        if (
            treeUri == null ||
            !DocumentsContract.isTreeUri(treeUri) ||
            displayName == null
        ) {
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_DIRECTORY,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        val pipe =
            runCatching { ParcelFileDescriptor.createPipe() }
                .getOrElse { error ->
                    Log.w(TAG, "Could not create Android directory stream", error)
                    sendDocumentResult(
                        requestId,
                        DOCUMENT_OPERATION_DIRECTORY,
                        DOCUMENT_RESULT_FAILED,
                        null,
                        "",
                        false,
                    )
                    return
                }
        val reader = pipe[0]
        val writer = pipe[1]
        val producer =
            Thread(
                {
                    var progressStarted = false
                    try {
                        beginDirectoryStreamProgress()
                        progressStarted = true
                        runCatching {
                            ParcelFileDescriptor.AutoCloseOutputStream(writer).use { stream ->
                                DataOutputStream(
                                    BufferedOutputStream(stream, DIRECTORY_BUFFER_BYTES),
                                ).use { output ->
                                    writeDirectoryStream(treeUri, output)
                                }
                            }
                        }.onFailure { error ->
                            if (error !is IOException || error.message != "Broken pipe") {
                                Log.w(TAG, "Android directory stream failed", error)
                            }
                            reportDirectoryStreamFailure()
                        }
                    } finally {
                        if (progressStarted) {
                            endDirectoryStreamProgress()
                        } else {
                            runCatching { writer.close() }
                        }
                    }
                },
                "ArchpheneDirectoryStream",
            ).apply {
                isDaemon = true
            }
        try {
            producer.start()
        } catch (error: RuntimeException) {
            reader.close()
            writer.close()
            Log.w(TAG, "Could not start Android directory stream", error)
            sendDocumentResult(
                requestId,
                DOCUMENT_OPERATION_DIRECTORY,
                DOCUMENT_RESULT_FAILED,
                null,
                "",
                false,
            )
            return
        }
        reader.use {
            if (
                !sendDocumentResult(
                    requestId,
                    DOCUMENT_OPERATION_DIRECTORY,
                    DOCUMENT_RESULT_SUCCESS,
                    it,
                    displayName,
                    false,
                )
            ) {
                Log.w(TAG, "Could not submit Android directory stream")
            }
        }
    }

    private fun writeDirectoryStream(
        treeUri: Uri,
        output: DataOutputStream,
    ) {
        val watchdog =
            DirectoryProviderWatchdog(
                handler,
                DIRECTORY_PROVIDER_DEADLINE_MILLIS,
                DIRECTORY_PROVIDER_FATAL_GRACE_MILLIS,
            ) { operation ->
                Log.e(
                    TAG,
                    "Android directory provider remained blocked while attempting to $operation",
                )
                Process.killProcess(Process.myPid())
            }
        check(activeDirectoryWatchdog.compareAndSet(null, watchdog)) {
            "Another Android directory stream is active"
        }
        try {
            output.write(DIRECTORY_STREAM_MAGIC)
            val visited = HashSet<String>()
            val counters = longArrayOf(0L, 0L)
            val buffer = ByteArray(DIRECTORY_BUFFER_BYTES)
            writeDirectoryChildren(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
                "",
                0,
                visited,
                counters,
                buffer,
                output,
                watchdog,
            )
            output.writeByte(DIRECTORY_RECORD_END)
            output.flush()
        } finally {
            activeDirectoryWatchdog.compareAndSet(watchdog, null)
            watchdog.close()
        }
    }

    private fun beginDirectoryStreamProgress() {
        check(directoryStreamActive.compareAndSet(false, true)) {
            "Another Android directory stream is active"
        }
        handler.postDelayed(showDirectoryProgress, DIRECTORY_PROGRESS_DELAY_MILLIS)
    }

    private fun endDirectoryStreamProgress() {
        directoryStreamActive.set(false)
        handler.post {
            handler.removeCallbacks(showDirectoryProgress)
            if (directoryProgressVisible) {
                directoryProgressVisible = false
                directoryProgress.visibility = View.GONE
                if (remoteStatus == STATUS_RUNNING) {
                    status.visibility = View.GONE
                }
            }
        }
    }

    private fun reportDirectoryStreamFailure() {
        handler.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(
                    this,
                    R.string.directory_import_failed,
                    Toast.LENGTH_LONG,
                ).show()
                Log.i(TAG, "Reported Android directory stream failure")
            }
        }
    }

    private fun writeDirectoryChildren(
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String,
        depth: Int,
        visited: MutableSet<String>,
        counters: LongArray,
        buffer: ByteArray,
        output: DataOutputStream,
        watchdog: DirectoryProviderWatchdog,
    ) {
        check(depth <= MAX_DIRECTORY_DEPTH) { "Android directory is too deep" }
        check(visited.add(parentDocumentId)) { "Android directory contains a cycle" }
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val childRecords =
            watchdog.cancellable("list Android folder") { signal ->
                queryDirectoryChildren(children, signal, prefix, depth, counters)
            }
        val directories = ArrayList<DirectoryChild>()
        for (child in childRecords) {
            if (child.directory) {
                writeDirectoryPath(
                    output,
                    DIRECTORY_RECORD_DIRECTORY,
                    child.relativePathBytes,
                )
                directories += child
            } else {
                writeDirectoryPath(output, DIRECTORY_RECORD_FILE, child.relativePathBytes)
                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                watchdog
                    .cancellable("open Android file") { signal ->
                        contentResolver.openFileDescriptor(documentUri, "r", signal)
                            ?: error("Android provider returned no file descriptor")
                    }.use { descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            var fileBytes = 0L
                            while (true) {
                                val count =
                                    watchdog.read("read Android file", descriptor) {
                                        input.read(buffer)
                                    }
                                if (count < 0) break
                                if (count == 0) continue
                                fileBytes = Math.addExact(fileBytes, count.toLong())
                                counters[1] = Math.addExact(counters[1], count.toLong())
                                check(fileBytes <= MAX_DIRECTORY_FILE_BYTES) {
                                    "Android directory file is too large"
                                }
                                check(counters[1] <= MAX_DIRECTORY_TOTAL_BYTES) {
                                    "Android directory is too large"
                                }
                                output.writeByte(DIRECTORY_RECORD_DATA)
                                output.writeInt(count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                output.writeByte(DIRECTORY_RECORD_FILE_END)
            }
        }
        for (directory in directories) {
            writeDirectoryChildren(
                treeUri,
                directory.documentId,
                directory.relativePath,
                depth + 1,
                visited,
                counters,
                buffer,
                output,
                watchdog,
            )
        }
    }

    private fun queryDirectoryChildren(
        children: Uri,
        signal: CancellationSignal,
        prefix: String,
        depth: Int,
        counters: LongArray,
    ): List<DirectoryChild> {
        val records = ArrayList<DirectoryChild>()
        contentResolver
            .query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
                signal,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    check(depth < MAX_DIRECTORY_DEPTH) {
                        "Android directory is too deep"
                    }
                    counters[0]++
                    check(counters[0] <= MAX_DIRECTORY_ENTRIES) {
                        "Android directory has too many entries"
                    }
                    val documentId =
                        cursor.getString(0)?.takeIf(String::isNotEmpty)
                            ?: error("Android provider returned no document ID")
                    val name =
                        cursor.getString(1)?.takeIf(::safePortalFolderName)
                            ?: error("Android provider returned an unsafe name")
                    val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                    val pathBytes = relativePath.toByteArray(Charsets.UTF_8)
                    check(pathBytes.size in 1..MAX_DIRECTORY_PATH_BYTES) {
                        "Android directory path is too long"
                    }
                    records +=
                        DirectoryChild(
                            documentId,
                            relativePath,
                            pathBytes,
                            cursor.getString(2).orEmpty() ==
                                DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                }
            } ?: error("Android provider did not return directory children")
        return records
    }

    private fun writeDirectoryPath(
        output: DataOutputStream,
        record: Int,
        path: ByteArray,
    ) {
        output.writeByte(record)
        output.writeShort(path.size)
        output.write(path)
    }

    private fun safePortalFolderName(name: String): Boolean =
        LauncherDocumentNamePolicy.valid(name) &&
            name.none { character ->
                character == '\u061c' ||
                    character == '\u200e' ||
                    character == '\u200f' ||
                    character in '\u202a'..'\u202e' ||
                    character in '\u2066'..'\u2069'
            }

    private data class DirectoryChild(
        val documentId: String,
        val relativePath: String,
        val relativePathBytes: ByteArray,
        val directory: Boolean,
    )

    private fun sendOpenDocumentBatch(
        requestId: Int,
        operation: Int,
        documents: List<OpenDocument>,
    ): Boolean {
        val service = remote ?: return false
        val activeSession = sessionId
        if (
            activeSession <= 0 ||
            requestId <= 0 ||
            operation !in DOCUMENT_OPERATION_OPEN..DOCUMENT_OPERATION_OPEN_MULTIPLE ||
            documents.size !in 1..MAX_OPEN_DOCUMENTS ||
            (operation == DOCUMENT_OPERATION_OPEN && documents.size != 1) ||
            documents.any { document -> !LauncherDocumentNamePolicy.valid(document.displayName) }
        ) {
            return false
        }
        val parcel = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(INTERFACE)
            parcel.writeInt(PROTOCOL_VERSION)
            parcel.writeInt(activeSession)
            parcel.writeInt(requestId)
            parcel.writeInt(DOCUMENT_RESULT_SUCCESS)
            if (operation == DOCUMENT_OPERATION_OPEN_MULTIPLE) {
                parcel.writeInt(documents.size)
            }
            for (document in documents) {
                parcel.writeString(document.displayName)
                parcel.writeInt(0)
                document.descriptor.writeToParcel(parcel, 0)
            }
            service.transact(TRANSACTION_DOCUMENT_RESULT, parcel, reply, 0) &&
                run {
                    reply.readException()
                    reply.readInt() == RESULT_OK
                }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit Android document batch", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Android document batch was rejected", error)
            false
        } finally {
            reply.recycle()
            parcel.recycle()
        }
    }

    private fun queryDocumentName(uri: Uri): String? =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) return@use null
                cursor.getString(index)?.takeIf(LauncherDocumentNamePolicy::valid)
            }
        }.getOrNull()

    private fun sendDocumentResult(
        requestId: Int,
        operation: Int,
        result: Int,
        descriptor: ParcelFileDescriptor?,
        displayName: String,
        writable: Boolean,
    ): Boolean {
        val service = remote ?: return false
        val activeSession = sessionId
        if (
            activeSession <= 0 ||
            requestId <= 0 ||
            operation !in DOCUMENT_OPERATION_SAVE..DOCUMENT_OPERATION_DIRECTORY ||
            result !in DOCUMENT_RESULT_SUCCESS..DOCUMENT_RESULT_FAILED ||
            (result == DOCUMENT_RESULT_SUCCESS) != (descriptor != null) ||
            (operation == DOCUMENT_OPERATION_OPEN_MULTIPLE &&
                result == DOCUMENT_RESULT_SUCCESS) ||
            (operation == DOCUMENT_OPERATION_OPEN &&
                result == DOCUMENT_RESULT_SUCCESS &&
                !LauncherDocumentNamePolicy.valid(displayName)) ||
            (operation == DOCUMENT_OPERATION_SAVE &&
                result == DOCUMENT_RESULT_SUCCESS &&
                !LauncherDocumentNamePolicy.valid(displayName)) ||
            (operation == DOCUMENT_OPERATION_DIRECTORY &&
                result == DOCUMENT_RESULT_SUCCESS &&
                !LauncherDocumentNamePolicy.valid(displayName))
        ) {
            return false
        }
        val parcel = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(INTERFACE)
            parcel.writeInt(PROTOCOL_VERSION)
            parcel.writeInt(activeSession)
            parcel.writeInt(requestId)
            parcel.writeInt(result)
            if (descriptor != null) {
                if (
                    operation == DOCUMENT_OPERATION_OPEN ||
                    operation == DOCUMENT_OPERATION_SAVE
                ) {
                    parcel.writeString(displayName)
                    if (operation == DOCUMENT_OPERATION_OPEN) {
                        parcel.writeInt(if (writable) 1 else 0)
                    }
                } else if (operation == DOCUMENT_OPERATION_DIRECTORY) {
                    parcel.writeString(displayName)
                }
                descriptor.writeToParcel(parcel, 0)
            }
            service.transact(TRANSACTION_DOCUMENT_RESULT, parcel, reply, 0) &&
                run {
                    reply.readException()
                    reply.readInt() == RESULT_OK
                }
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not submit Android document result", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Android document result was rejected", error)
            false
        } finally {
            reply.recycle()
            parcel.recycle()
        }
    }

    private fun showUnavailable() {
        status.setText(R.string.launcher_unavailable)
        status.visibility = View.VISIBLE
    }

    private fun applyPendingAction(action: PendingAction) {
        if (action.session != sessionId || remote == null) {
            discardPendingAction(action)
            return
        }
        when (action) {
            is PendingAction.Document ->
                when (action.operation) {
                    DOCUMENT_OPERATION_SAVE ->
                        beginDocumentSave(
                            action.requestId,
                            action.title,
                            action.suggestedName,
                            action.mimeType,
                        )
                    DOCUMENT_OPERATION_DIRECTORY ->
                        beginDirectoryOpen(action.requestId, action.title)
                    else ->
                        beginDocumentOpen(
                            action.requestId,
                            action.title,
                            action.mimeType,
                            action.operation == DOCUMENT_OPERATION_OPEN_MULTIPLE,
                        )
                }
            is PendingAction.OpenUri -> openAndroidUri(action.uri)
            is PendingAction.Notification ->
                handleLinuxNotification(
                    action.operation,
                    action.id,
                    action.title,
                    action.body,
                )
        }
    }

    private fun discardPendingAction(action: PendingAction) {
        when (action) {
            is PendingAction.Document ->
                if (action.session == sessionId && remote != null) {
                    sendDocumentResult(
                        action.requestId,
                        action.operation,
                        DOCUMENT_RESULT_CANCELLED,
                        null,
                        "",
                        false,
                    )
                }
            is PendingAction.OpenUri,
            is PendingAction.Notification,
            -> Unit
        }
    }

    private fun applyRemoteStatus(
        state: Int,
        message: String,
    ) {
        remoteStatus = state
        if (state == STATUS_RUNNING) {
            status.visibility = View.GONE
        } else {
            status.text = message
            status.visibility = View.VISIBLE
        }
    }

    private fun applyStatusAppearance() {
        status.setTextColor(getColor(R.color.launcher_text))
        status.setBackgroundColor(getColor(R.color.launcher_background))
    }

    private fun openAndroidUri(value: String) {
        if (!LauncherBrowserUriPolicy.valid(value) || isFinishing || isDestroyed) {
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(value))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
            Log.i(TAG, "Opened Android browser for Linux URI")
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "No Android browser is available", error)
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Log.w(TAG, "Android rejected Linux URI", error)
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun beginMicrophonePermission(permissionIntent: PendingIntent) {
        if (
            !microphoneEnabled ||
            !activityResumed ||
            isFinishing ||
            isDestroyed
        ) {
            permissionIntent.cancel()
            Log.w(TAG, "Dropped Linux microphone request without a visible launcher")
            return
        }
        runCatching {
            permissionIntent.send(
                this,
                0,
                null,
                null,
                null,
                null,
                microphonePermissionSenderOptions(),
            )
        }.onSuccess {
            Log.i(TAG, "Opened Archphene microphone consent for Linux input")
        }.onFailure { error ->
            Log.w(TAG, "Could not open Archphene microphone consent", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun microphonePermissionSenderOptions() =
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    },
                ).toBundle()
        } else {
            null
        }

    private fun handleLinuxNotification(
        operation: Int,
        id: String,
        title: String,
        body: String,
    ) {
        if (operation == NOTIFICATION_OPERATION_WITHDRAW) {
            for (index in pendingNotifications.indices) {
                if (pendingNotifications[index]?.id == id) {
                    pendingNotifications[index] = null
                }
            }
            getSystemService(NotificationManager::class.java)
                ?.cancel(id, LINUX_NOTIFICATION_ID)
            return
        }
        val pending = PendingNotification(id, title, body)
        if (hasNotificationPermission()) {
            postLinuxNotification(pending)
            return
        }
        if (
            getSharedPreferences(NOTIFICATION_PREFERENCES, MODE_PRIVATE)
                .getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false) &&
            !notificationPermissionRequestInFlight
        ) {
            Log.i(TAG, "Dropped Linux notification after permission denial id=$id")
            return
        }
        var empty = -1
        for (index in pendingNotifications.indices) {
            val existing = pendingNotifications[index]
            if (existing?.id == id) {
                pendingNotifications[index] = pending
                maybeRequestNotificationPermission()
                return
            }
            if (existing == null && empty < 0) empty = index
        }
        if (empty < 0) {
            Log.w(TAG, "Linux notification queue is full")
            return
        }
        pendingNotifications[empty] = pending
        maybeRequestNotificationPermission()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT < 33 ||
            hasNotificationPermission() ||
            !activityResumed ||
            notificationPermissionRequestInFlight ||
            pendingNotifications.none { it != null }
        ) {
            return
        }
        val preferences = getSharedPreferences(NOTIFICATION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)) {
            pendingNotifications.fill(null)
            return
        }
        notificationPermissionRequestInFlight = true
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        runCatching {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }.onFailure { error ->
            notificationPermissionRequestInFlight = false
            pendingNotifications.fill(null)
            Log.w(TAG, "Could not request Linux notification permission", error)
        }
    }

    private fun postLinuxNotification(pending: PendingNotification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                LINUX_NOTIFICATION_CHANNEL,
                getString(R.string.linux_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val launch =
            Intent(this, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val contentIntent =
            PendingIntent.getActivity(
                this,
                LINUX_NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, LINUX_NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(pending.title)
                .setContentText(pending.body)
                .setStyle(Notification.BigTextStyle().bigText(pending.body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
        manager.notify(pending.id, LINUX_NOTIFICATION_ID, notification)
        Log.i(TAG, "Posted Linux notification id=${pending.id}")
    }

    private fun stagePrintPdf(
        title: String,
        descriptor: ParcelFileDescriptor,
    ): Boolean {
        if (
            !printingEnabled ||
            !activityResumed ||
            isFinishing ||
            isDestroyed ||
            title.isBlank() ||
            title.length > MAX_PRINT_TITLE_UTF16 ||
            !LauncherUtf8Policy.lengthAtMost(title, MAX_PRINT_TITLE_BYTES) ||
            title.any { character -> character.isISOControl() } ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)
        ) {
            descriptor.close()
            return false
        }
        val stat =
            runCatching { Os.fstat(descriptor.fileDescriptor) }
                .getOrElse {
                    descriptor.close()
                    return false
                }
        if (
            stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG ||
            stat.st_size !in MIN_PRINT_BYTES..MAX_PRINT_BYTES
        ) {
            descriptor.close()
            return false
        }
        runCatching {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        }.getOrElse {
            descriptor.close()
            return false
        }
        val directory =
            runCatching { preparePrintDirectory() }
                .getOrElse { error ->
                    descriptor.close()
                    Log.w(TAG, "Private print directory is unavailable", error)
                    return false
                }
        val document =
            runCatching {
                File.createTempFile("linux-", ".pdf", directory).canonicalFile.also { file ->
                    check(file.parentFile == directory)
                    check(!Files.isSymbolicLink(file.toPath()))
                }
            }.getOrElse { error ->
                descriptor.close()
                Log.w(TAG, "Could not reserve private print document", error)
                return false
            }
        synchronized(ACTIVE_PRINT_FILES) {
            if (ACTIVE_PRINT_FILES.size >= MAX_PENDING_PRINTS) {
                descriptor.close()
                document.delete()
                return false
            }
            ACTIVE_PRINT_FILES += document
        }
        var accepted = false
        try {
            copyPrintPdf(descriptor, document)
            validatePrintPdf(document)
            if (!activityResumed || isFinishing || isDestroyed) return false
            handler.post { openPrintUi(document, title) }
            accepted = true
            Log.i(TAG, "Accepted Linux print document bytes=${document.length()}")
            return true
        } catch (error: Exception) {
            Log.w(TAG, "Rejected Linux print document", error)
            return false
        } finally {
            if (!accepted) finishPrint(document)
        }
    }

    private fun validatePrintPdf(document: File) {
        ParcelFileDescriptor.open(document, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount in 1..MAX_PRINT_PAGES) {
                    "Print document page count is invalid"
                }
                renderer.openPage(0).use { page ->
                    check(
                        page.width in 1..MAX_PRINT_PAGE_DIMENSION &&
                            page.height in 1..MAX_PRINT_PAGE_DIMENSION,
                    ) {
                        "Print document page geometry is invalid"
                    }
                }
            }
        }
    }

    private fun copyPrintPdf(
        descriptor: ParcelFileDescriptor,
        target: File,
    ) {
        descriptor.use { owned ->
            ParcelFileDescriptor.dup(owned.fileDescriptor).use { duplicate ->
                ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                    FileOutputStream(target, false).use { output ->
                        val header = ByteArray(PDF_HEADER.size)
                        var headerLength = 0
                        while (headerLength < header.size) {
                            val count =
                                input.read(
                                    header,
                                    headerLength,
                                    header.size - headerLength,
                                )
                            if (count < 0) break
                            headerLength += count
                        }
                        check(
                            headerLength == header.size &&
                                header.contentEquals(PDF_HEADER),
                        ) {
                            "Print document is not a PDF"
                        }
                        output.write(header)
                        var total = header.size.toLong()
                        val buffer = ByteArray(PRINT_COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total = Math.addExact(total, count.toLong())
                            check(total <= MAX_PRINT_BYTES) {
                                "Print document exceeds the size limit"
                            }
                            output.write(buffer, 0, count)
                        }
                        check(total >= MIN_PRINT_BYTES)
                        output.fd.sync()
                    }
                }
            }
        }
    }

    private fun openPrintUi(
        document: File,
        title: String,
    ) {
        if (
            !activityResumed ||
            isFinishing ||
            isDestroyed ||
            !document.isFile ||
            Files.isSymbolicLink(document.toPath())
        ) {
            finishPrint(document)
            return
        }
        runCatching {
            val manager =
                getSystemService(PrintManager::class.java)
                    ?: error("Android print manager is unavailable")
            manager.print(
                title,
                LinuxPdfPrintAdapter(document, title) { finishPrint(document) },
                null,
            )
            Log.i(TAG, "Opened Android print UI")
        }.onFailure { error ->
            Log.w(TAG, "Could not open Android print UI", error)
            finishPrint(document)
        }
    }

    private fun preparePrintDirectory(): File {
        val requestedDirectory = File(cacheDir, PRINT_DIRECTORY)
        check(!Files.isSymbolicLink(requestedDirectory.toPath()))
        val directory = requestedDirectory.canonicalFile
        check(directory.parentFile == cacheDir.canonicalFile)
        check(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdir())
        cleanupStalePrintFiles()
        return directory
    }

    private fun cleanupStalePrintFiles() {
        val directory = File(cacheDir, PRINT_DIRECTORY)
        if (!directory.exists()) return
        val canonicalDirectory = directory.canonicalFile
        check(
            canonicalDirectory.parentFile == cacheDir.canonicalFile &&
                !Files.isSymbolicLink(directory.toPath()) &&
                canonicalDirectory.isDirectory,
        )
        synchronized(ACTIVE_PRINT_FILES) {
            cleanupBoundedRegularFiles(
                canonicalDirectory,
                PRINT_FILE_NAME,
                MAX_STALE_PRINT_FILES,
                ACTIVE_PRINT_FILES,
                "Unsafe private print staging entry",
                "Private print staging limit exceeded",
            ) { _ ->
                Log.w(TAG, "Could not delete stale private print document")
            }
        }
    }

    private fun finishPrint(document: File) {
        synchronized(ACTIVE_PRINT_FILES) {
            ACTIVE_PRINT_FILES.remove(document)
        }
        if (document.exists() && !document.delete()) {
            Log.w(TAG, "Could not delete private print document")
        }
    }

    private fun reconcileIndependentWindows(ids: IntArray) {
        if (isFinishing || isDestroyed) return
        if (requestedToplevelId > 0) {
            if (ids.none { id -> id == requestedToplevelId }) {
                finishAndRemoveTask()
            }
            return
        }
        latestIndependentToplevelIds = ids.copyOf()
        val taskPolicyEnabled = desktopWindowTasksEnabled()
        Log.i(
            TAG,
            "Independent Linux windows=${ids.size} Android tasks=$taskPolicyEnabled " +
                "smallestWidthDp=${resources.configuration.smallestScreenWidthDp}",
        )
        if (!taskPolicyEnabled) return
        for (id in ids) {
            val launch =
                synchronized(ACTIVE_TOPLEVELS) {
                    ACTIVE_TOPLEVELS.add(id)
                }
            if (!launch) continue
            if (hasExistingIndependentTask(id)) {
                Log.i(TAG, "Retained existing Android task for Linux window=$id")
                continue
            }
            val intent =
                Intent(this, LauncherWindowActivity::class.java)
                    .setAction(ACTION_OPEN_TOPLEVEL)
                    .setData(Uri.parse("archphene-window://$packageName/$id"))
                    .putExtra(EXTRA_TOPLEVEL_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            runCatching { startActivity(intent) }
                .onSuccess { Log.i(TAG, "Opened Android task for Linux window=$id") }
                .onFailure { error ->
                    synchronized(ACTIVE_TOPLEVELS) {
                        ACTIVE_TOPLEVELS.remove(id)
                    }
                    Log.w(TAG, "Could not open Android task for Linux window=$id", error)
                }
        }
    }

    private fun hasExistingIndependentTask(id: Int): Boolean {
        val expectedData = Uri.parse("archphene-window://$packageName/$id")
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.appTasks
            .asSequence()
            .take(MAX_APP_TASK_INSPECTION)
            .any { task ->
                val info = task.taskInfo
                val base = info.baseIntent
                info.numActivities > 0 &&
                    base.component?.className == LauncherWindowActivity::class.java.name &&
                    base.data == expectedData
            }
    }

    private fun reconcileWindowTaskPolicy() {
        if (requestedToplevelId == 0 && latestIndependentToplevelIds.isNotEmpty()) {
            reconcileIndependentWindows(latestIndependentToplevelIds)
        }
    }

    private fun desktopWindowTasksEnabled(): Boolean {
        val configuration = resources.configuration
        val displayId =
            if (Build.VERSION.SDK_INT >= 30) {
                display?.displayId
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.displayId
            }
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val (widthPixels, heightPixels) =
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
            }
        val precisePointer =
            InputDevice.getDeviceIds().any { id ->
                ((InputDevice.getDevice(id)?.sources ?: 0) and InputDevice.SOURCE_MOUSE) != 0
            }
        return LauncherWindowTaskPolicy.useIndependentTasks(
            smallestWidthDp = configuration.smallestScreenWidthDp,
            displayId = displayId ?: android.view.Display.DEFAULT_DISPLAY,
            defaultDisplayId = android.view.Display.DEFAULT_DISPLAY,
            precisePointer = precisePointer,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            density = density,
        )
    }

    private fun applicationMetadata(): Bundle =
        packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData ?: Bundle.EMPTY

    private fun appLabel(): String =
        packageManager.getApplicationLabel(applicationInfo).toString().take(256)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val TAG = "ArchpheneLauncher"
        private const val MANAGER_PACKAGE = "org.archphene.launcher.MANAGER_PACKAGE"
        private const val CAPABILITIES = "org.archphene.launcher.CAPABILITIES"
        private const val MIME_TYPES = "org.archphene.launcher.MIME_TYPES"
        private const val CAPABILITIES_V4 =
            "c:wayland,input,ime,clipboard,documents,open-uri,notifications"
        private const val CAPABILITIES_PRINTING_V5 = "$CAPABILITIES_V4,printing"
        private const val CAPABILITIES_AUDIO_V6 = "$CAPABILITIES_V4,audio-output"
        private const val CAPABILITIES_AUDIO_PRINTING_V6 =
            "$CAPABILITIES_V4,audio-output,printing"
        private const val CAPABILITIES_AUDIO_INPUT_V7 =
            "$CAPABILITIES_V4,audio-output,audio-input"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_V7 =
            "$CAPABILITIES_V4,audio-output,audio-input,printing"
        private const val CAPABILITIES_SECRETS_V8 =
            "$CAPABILITIES_V4,secrets"
        private const val CAPABILITIES_PRINTING_SECRETS_V8 =
            "$CAPABILITIES_V4,printing,secrets"
        private const val CAPABILITIES_AUDIO_SECRETS_V8 =
            "$CAPABILITIES_V4,audio-output,secrets"
        private const val CAPABILITIES_AUDIO_PRINTING_SECRETS_V8 =
            "$CAPABILITIES_V4,audio-output,printing,secrets"
        private const val CAPABILITIES_AUDIO_INPUT_SECRETS_V8 =
            "$CAPABILITIES_V4,audio-output,audio-input,secrets"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V8 =
            "$CAPABILITIES_V4,audio-output,audio-input,printing,secrets"
        private const val CAPABILITIES_CAMERA_V9 = "$CAPABILITIES_V4,camera"
        private const val CAPABILITIES_PRINTING_CAMERA_V9 =
            "$CAPABILITIES_PRINTING_V5,camera"
        private const val CAPABILITIES_AUDIO_CAMERA_V9 = "$CAPABILITIES_AUDIO_V6,camera"
        private const val CAPABILITIES_AUDIO_PRINTING_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_PRINTING_V6,camera"
        private const val CAPABILITIES_AUDIO_INPUT_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_INPUT_V7,camera"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_V7,camera"
        private const val CAPABILITIES_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_SECRETS_V8,camera"
        private const val CAPABILITIES_PRINTING_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_PRINTING_SECRETS_V8,camera"
        private const val CAPABILITIES_AUDIO_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_SECRETS_V8,camera"
        private const val CAPABILITIES_AUDIO_PRINTING_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_PRINTING_SECRETS_V8,camera"
        private const val CAPABILITIES_AUDIO_INPUT_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_INPUT_SECRETS_V8,camera"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_CAMERA_V9 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V8,camera"
        private const val CAPABILITIES_V10 = "$CAPABILITIES_V4,accessibility"
        private const val CAPABILITIES_PRINTING_V10 =
            "$CAPABILITIES_PRINTING_V5,accessibility"
        private const val CAPABILITIES_AUDIO_V10 = "$CAPABILITIES_AUDIO_V6,accessibility"
        private const val CAPABILITIES_AUDIO_PRINTING_V10 =
            "$CAPABILITIES_AUDIO_PRINTING_V6,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_V10 =
            "$CAPABILITIES_AUDIO_INPUT_V7,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_V10 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_V7,accessibility"
        private const val CAPABILITIES_SECRETS_V10 =
            "$CAPABILITIES_SECRETS_V8,accessibility"
        private const val CAPABILITIES_PRINTING_SECRETS_V10 =
            "$CAPABILITIES_PRINTING_SECRETS_V8,accessibility"
        private const val CAPABILITIES_AUDIO_SECRETS_V10 =
            "$CAPABILITIES_AUDIO_SECRETS_V8,accessibility"
        private const val CAPABILITIES_AUDIO_PRINTING_SECRETS_V10 =
            "$CAPABILITIES_AUDIO_PRINTING_SECRETS_V8,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_SECRETS_V10 =
            "$CAPABILITIES_AUDIO_INPUT_SECRETS_V8,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V10 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V8,accessibility"
        private const val CAPABILITIES_CAMERA_V10 =
            "$CAPABILITIES_CAMERA_V9,accessibility"
        private const val CAPABILITIES_PRINTING_CAMERA_V10 =
            "$CAPABILITIES_PRINTING_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_PRINTING_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_PRINTING_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_INPUT_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_CAMERA_V9,accessibility"
        private const val CAPABILITIES_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_SECRETS_CAMERA_V9,accessibility"
        private const val CAPABILITIES_PRINTING_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_PRINTING_SECRETS_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_SECRETS_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_PRINTING_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_PRINTING_SECRETS_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_INPUT_SECRETS_CAMERA_V9,accessibility"
        private const val CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_CAMERA_V10 =
            "$CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_CAMERA_V9,accessibility"
        private val VALID_CAPABILITIES_V10 =
            setOf(
                CAPABILITIES_V10,
                CAPABILITIES_PRINTING_V10,
                CAPABILITIES_AUDIO_V10,
                CAPABILITIES_AUDIO_PRINTING_V10,
                CAPABILITIES_AUDIO_INPUT_V10,
                CAPABILITIES_AUDIO_INPUT_PRINTING_V10,
                CAPABILITIES_SECRETS_V10,
                CAPABILITIES_PRINTING_SECRETS_V10,
                CAPABILITIES_AUDIO_SECRETS_V10,
                CAPABILITIES_AUDIO_PRINTING_SECRETS_V10,
                CAPABILITIES_AUDIO_INPUT_SECRETS_V10,
                CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_V10,
                CAPABILITIES_CAMERA_V10,
                CAPABILITIES_PRINTING_CAMERA_V10,
                CAPABILITIES_AUDIO_CAMERA_V10,
                CAPABILITIES_AUDIO_PRINTING_CAMERA_V10,
                CAPABILITIES_AUDIO_INPUT_CAMERA_V10,
                CAPABILITIES_AUDIO_INPUT_PRINTING_CAMERA_V10,
                CAPABILITIES_SECRETS_CAMERA_V10,
                CAPABILITIES_PRINTING_SECRETS_CAMERA_V10,
                CAPABILITIES_AUDIO_SECRETS_CAMERA_V10,
                CAPABILITIES_AUDIO_PRINTING_SECRETS_CAMERA_V10,
                CAPABILITIES_AUDIO_INPUT_SECRETS_CAMERA_V10,
                CAPABILITIES_AUDIO_INPUT_PRINTING_SECRETS_CAMERA_V10,
            )
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV2"
        private const val PROTOCOL_VERSION = 20
        private const val TEST_INPUT_DEBUG_EXTRA = "archphene_test_input_debug"
        private const val ACTION_OPEN_TOPLEVEL = "org.archphene.action.OPEN_TOPLEVEL"
        private const val EXTRA_TOPLEVEL_ID = "org.archphene.extra.TOPLEVEL_ID"
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
        private const val MAX_INDEPENDENT_WINDOWS = 8
        private const val MAX_APP_TASK_INSPECTION = MAX_INDEPENDENT_WINDOWS + 1
        private const val ACCESSIBILITY_CALLBACK_TREE = 1
        private const val ACCESSIBILITY_CALLBACK_EVENT = 2
        private const val ACCESSIBILITY_CALLBACK_MENU = 3
        private const val MAX_ACCESSIBILITY_NODE_ID = 1_000_000
        private const val MAX_ACCESSIBILITY_VIEWPORT = 16_384
        private const val MAX_ACCESSIBILITY_TEXT_UTF16 = 1_024
        private const val MAX_ACCESSIBILITY_TEXT_BYTES = 4_096
        private const val ACCESSIBILITY_TOUCH_ID = 31
        private const val ACCESSIBILITY_MENU_HOLD_MILLIS = 40
        private const val CONFIGURATION_SURFACE_ATTACH_FRAMES = 12
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
        private const val CAMERA_OPERATION_REQUEST = 1
        private const val CAMERA_OPERATION_CHECK = 2
        private const val CAMERA_OPERATION_CAPTURE = 3
        private const val CAMERA_OPERATION_STREAM = 4
        private const val CAMERA_FACING_BACK = 0
        private const val CAMERA_FACING_FRONT = 1
        private const val MAX_CAMERA_DIMENSION = 8_192
        private const val MAX_SURFACE_DIMENSION = 8_192
        private const val MAX_LOGICAL_SURFACE_DIMENSION = 32_768
        private const val NOTIFICATION_OPERATION_POST = 1
        private const val NOTIFICATION_OPERATION_WITHDRAW = 2
        private const val NOTIFICATION_PERMISSION_REQUEST = 7_001
        private const val CAMERA_PERMISSION_REQUEST = 7_002
        private const val CAMERA_PREFERENCES = "archphene-camera"
        private const val CAMERA_PERMISSION_REQUESTED = "permission-requested"
        private const val NOTIFICATION_PREFERENCES = "archphene-notifications"
        private const val NOTIFICATION_PERMISSION_REQUESTED = "permission-requested"
        private const val LINUX_NOTIFICATION_CHANNEL = "linux-app"
        private const val LINUX_NOTIFICATION_ID = 1
        private const val MAX_PRINT_TITLE_UTF16 = 256
        private const val MAX_PRINT_TITLE_BYTES = 512
        private const val PRINT_DIRECTORY = "print"
        private val PRINT_FILE_NAME = Regex(".*\\.pdf", RegexOption.DOT_MATCHES_ALL)
        private const val MIN_PRINT_BYTES = 5L
        private const val MAX_PRINT_BYTES = 256L * 1024 * 1024
        private const val MAX_PENDING_PRINTS = 4
        private const val MAX_PENDING_ACTION_CALLBACKS = 32
        private const val MAX_STALE_PRINT_FILES = 32
        private const val MAX_PRINT_PAGES = 10_000
        private const val MAX_PRINT_PAGE_DIMENSION = 100_000
        private const val PRINT_COPY_BUFFER_BYTES = 64 * 1024
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val RESULT_INVALID = 4
        private const val MAX_OPEN_ATTEMPTS = 120
        private const val OPEN_RETRY_MILLIS = 250L
        private const val MIN_DENSITY_DPI = 72
        private const val MAX_DENSITY_DPI = 1_000
        private const val MIN_FONT_SCALE_MILLIS = 500
        private const val MAX_FONT_SCALE_MILLIS = 3_000
        private const val MAX_INPUT_RECORDS = 32
        private const val CURSOR_KIND_SYSTEM = 0
        private const val CURSOR_KIND_BITMAP = 1
        private const val CUSTOM_CURSOR_ICON = -1
        private const val MAX_CURSOR_DIMENSION = 256
        private const val MAX_CURSOR_PIXELS = 65_536L
        private const val SOFT_IME_TOUCH_DELAY_MILLIS = 300L
        private const val IMPLICIT_IME_SUPPRESSION_DELAY_MILLIS = 100L
        private const val IMPLICIT_IME_SUPPRESSION_SECOND_DELAY_MILLIS = 500L
        private const val IMPLICIT_IME_SUPPRESSION_THIRD_DELAY_MILLIS = 1_000L
        private const val IMPLICIT_IME_SUPPRESSION_FOURTH_DELAY_MILLIS = 1_500L
        private const val IMPLICIT_IME_SUPPRESSION_FINAL_DELAY_MILLIS = 2_500L
        private val PDF_HEADER =
            byteArrayOf(
                '%'.code.toByte(),
                'P'.code.toByte(),
                'D'.code.toByte(),
                'F'.code.toByte(),
                '-'.code.toByte(),
            )
        private val ACTIVE_PRINT_FILES = HashSet<File>(MAX_PENDING_PRINTS)
        private val ACTIVE_TOPLEVELS = HashSet<Int>(MAX_INDEPENDENT_WINDOWS)
        private const val INPUT_TOUCH_DOWN = 1
        private const val INPUT_TOUCH_MOTION = 2
        private const val INPUT_TOUCH_UP = 3
        private const val INPUT_TOUCH_CANCEL = 4
        private const val INPUT_KEY = 5
        private const val INPUT_POINTER_MOTION = 6
        private const val INPUT_POINTER_BUTTON_V2 = 8
        private const val INPUT_POINTER_AXIS = 9
        private const val INPUT_HOST_ACTIVE = 10
        private const val INPUT_POINTER_RELATIVE = 11
        private const val INPUT_POINTER_CAPTURE_LOST = 12
        private const val KEY_RELEASED = 0
        private const val KEY_PRESSED = 1
        private const val KEY_REPEATED = 2
        private const val AXIS_FIXED_SCALE = 1000f
        private const val MAX_AXIS_STEPS = 120f
        private const val RELATIVE_FIXED_SCALE = 1000f
        private const val MAX_RELATIVE_PIXELS = 16_384f
        private const val MAX_CLIPBOARD_UTF16 = 16_384
        private const val CLIPBOARD_FOCUS_RETRY_MILLIS = 150L
        private const val MAX_IME_UTF16 = 4_096
        private const val MAX_IME_BYTES = 16_384
        // Activity results retain only the low 16 request-code bits on some
        // Android releases. Keep this explicit launcher code in that range.
        private const val DOCUMENT_SAVE_REQUEST_CODE = 7_143
        private const val DOCUMENT_OPEN_REQUEST_CODE = 7_144
        private const val DOCUMENT_OPERATION_SAVE = 1
        private const val DOCUMENT_OPERATION_OPEN = 2
        private const val DOCUMENT_OPERATION_OPEN_MULTIPLE = 3
        private const val DOCUMENT_OPERATION_DIRECTORY = 4
        private const val DOCUMENT_RESULT_SUCCESS = 1
        private const val DOCUMENT_RESULT_CANCELLED = 2
        private const val DOCUMENT_RESULT_FAILED = 3
        private const val MAX_OPEN_DOCUMENTS = 32
        private const val MAX_DOCUMENT_TITLE_UTF16 = 128
        private const val MAX_DIRECTORY_ENTRIES = 10_000L
        private const val MAX_DIRECTORY_DEPTH = 64
        private const val MAX_DIRECTORY_PATH_BYTES = 4 * 1024
        private const val MAX_DIRECTORY_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_DIRECTORY_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
        private const val DIRECTORY_BUFFER_BYTES = 64 * 1024
        private const val DIRECTORY_PROVIDER_DEADLINE_MILLIS = 30_000L
        private const val DIRECTORY_PROVIDER_FATAL_GRACE_MILLIS = 2_000L
        private const val DIRECTORY_PROGRESS_DELAY_MILLIS = 500L
        private const val DIRECTORY_RECORD_END = 0
        private const val DIRECTORY_RECORD_DIRECTORY = 1
        private const val DIRECTORY_RECORD_FILE = 2
        private const val DIRECTORY_RECORD_DATA = 3
        private const val DIRECTORY_RECORD_FILE_END = 4
        private val DIRECTORY_STREAM_MAGIC =
            byteArrayOf(
                'A'.code.toByte(),
                'R'.code.toByte(),
                'C'.code.toByte(),
                'F'.code.toByte(),
                'O'.code.toByte(),
                'L'.code.toByte(),
                'D'.code.toByte(),
                '1'.code.toByte(),
            )
        private const val MAX_IME_ACTION = 64
        private const val MAX_IME_PURPOSE = 13
        private const val IME_COMMIT = 1
        private const val IME_PREEDIT = 2
        private const val IME_DELETE = 3
        private const val IME_EDITOR_ACTION = 4
        private const val POINTER_BUTTON_MASK =
            MotionEvent.BUTTON_PRIMARY or
                MotionEvent.BUTTON_SECONDARY or
                MotionEvent.BUTTON_TERTIARY or
                MotionEvent.BUTTON_BACK or
                MotionEvent.BUTTON_FORWARD
        private val POINTER_BUTTONS =
            intArrayOf(
                MotionEvent.BUTTON_PRIMARY,
                MotionEvent.BUTTON_SECONDARY,
                MotionEvent.BUTTON_TERTIARY,
                MotionEvent.BUTTON_BACK,
                MotionEvent.BUTTON_FORWARD,
            )
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private val SAFE_PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,7}")
    }
}
