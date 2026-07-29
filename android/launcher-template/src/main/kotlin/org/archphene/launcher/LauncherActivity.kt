package org.archphene.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.Selection
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
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class LauncherActivity :
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
    )

    private data class OpenDocument(
        val displayName: String,
        val descriptor: ParcelFileDescriptor,
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
    private var attempts = 0
    private var binding = false
    private var managerUid = -1
    private var remoteStatus = STATUS_STARTING
    private var attachedSurface: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var attachedDensityDpi = 0
    private var attachedFontScaleMillis = 0
    private var managerDeathSurfaceReset = false
    private var pointerButtonState = 0
    private var pointerCaptureRequested = false
    private var cursorSystemIcon = PointerIcon.TYPE_ARROW
    private var customCursorPointerIcon: PointerIcon? = null
    private var imeState = ImeState(false, 0, "", 0, 0, 0, 0)
    private var softImeRequested = false
    private val showImeAfterTouch =
        Runnable {
            if (softImeRequested && imeState.active) {
                showIme(restart = true)
            }
        }
    private var hasPendingLinuxClipboard = false
    private var pendingLinuxClipboardText: String? = null
    private var pendingDocumentRequestId = 0
    private var pendingDocumentOperation = 0
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
    private val clientToken =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (
                    code !in CALLBACK_STATUS..CALLBACK_OPEN_URI ||
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
                            handler.post { applyRemoteStatus(state, message) }
                            true
                        }
                        CALLBACK_CLIPBOARD -> {
                            val present = data.readInt()
                            val text = if (present == 1) data.readString() else null
                            if (
                                present !in 0..1 ||
                                (present == 1 &&
                                    (text == null || text.length > MAX_CLIPBOARD_UTF16)) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            handler.post { applyLinuxClipboard(text) }
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
                            if (
                                active !in 0..1 ||
                                (active == 1 &&
                                    (text == null ||
                                        text.length > MAX_IME_UTF16 ||
                                        cursor !in 0..text.length ||
                                        anchor !in 0..text.length ||
                                        hint < 0 ||
                                        purpose !in 0..MAX_IME_PURPOSE)) ||
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
                                )
                            handler.post { applyImeState(next) }
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
                                    (suggestedName.isBlank() ||
                                        suggestedName.length > MAX_DOCUMENT_NAME_UTF16 ||
                                        suggestedName.indexOf('/') >= 0 ||
                                        suggestedName.indexOf('\\') >= 0 ||
                                        suggestedName.indexOf('\u0000') >= 0)) ||
                                (operation != DOCUMENT_OPERATION_SAVE &&
                                    suggestedName.isNotEmpty()) ||
                                mimeType == null ||
                                DocumentMimePolicy.parse(mimeType) == null ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            handler.post {
                                    when (operation) {
                                        DOCUMENT_OPERATION_SAVE ->
                                            beginDocumentSave(
                                                requestId,
                                                title,
                                                suggestedName,
                                                mimeType,
                                            )
                                        DOCUMENT_OPERATION_DIRECTORY ->
                                            beginDirectoryOpen(requestId, title)
                                        else ->
                                            beginDocumentOpen(
                                                requestId,
                                                title,
                                                mimeType,
                                                operation == DOCUMENT_OPERATION_OPEN_MULTIPLE,
                                            )
                                    }
                            }
                            true
                        }
                        CALLBACK_POINTER_CAPTURE -> {
                            val active = data.readInt()
                            if (active !in 0..1 || data.dataAvail() != 0) {
                                return@runCatching false
                            }
                            handler.post { applyPointerCapture(active == 1) }
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
                                    handler.post { applyCursorSystemIcon(systemIcon) }
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
                                    handler.post {
                                        applyCursorBitmap(bitmap, hotspotX, hotspotY)
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
                                !validBrowserUri(uri) ||
                                data.dataAvail() != 0
                            ) {
                                return@runCatching false
                            }
                            handler.post { openAndroidUri(uri) }
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
                remoteStatus = STATUS_STARTING
                resetSurfaceAttachment()
                recreateSurfaceView()
                pointerButtonState = 0
                applyPointerCapture(false)
                applyCursorSystemIcon(PointerIcon.TYPE_ARROW)
                softImeRequested = false
                hasPendingLinuxClipboard = false
                pendingLinuxClipboardText = null
                pendingDocumentRequestId = 0
                pendingDocumentOperation = 0
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
        val light =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                Configuration.UI_MODE_NIGHT_YES
        val background = getColor(R.color.launcher_background)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        if (metadata.getString(CAPABILITIES) != CAPABILITIES_V3) {
            status.setText(R.string.launcher_capabilities_invalid)
            status.visibility = View.VISIBLE
            return
        }
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
        binding = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!binding) {
            status.setText(R.string.launcher_unavailable)
            status.visibility = View.VISIBLE
        }
    }

    private fun resetDeadBinding() {
        remote = null
        sessionId = 0
        remoteStatus = STATUS_STARTING
        resetSurfaceAttachment()
        recreateSurfaceView()
        pointerButtonState = 0
        applyPointerCapture(false)
        applyCursorSystemIcon(PointerIcon.TYPE_ARROW)
        softImeRequested = false
        hasPendingLinuxClipboard = false
        pendingLinuxClipboardText = null
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
        attachedDensityDpi = 0
        attachedFontScaleMillis = 0
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        stopClipboardListening()
        softImeRequested = false
        hideIme()
        applyPointerCapture(false)
        submitHostActive(false)
        detachSurface()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopClipboardListening()
        cancelPendingDocumentRequest()
        activeDirectoryWatchdog.getAndSet(null)?.close()
        detachSurface()
        closeSession()
        remote = null
        if (binding) {
            unbindService(connection)
            binding = false
        }
        documentThread.quitSafely()
        customCursorPointerIcon = null
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
                            "",
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
        applyStatusAppearance()
        applySystemBarAppearance()
        attachSurface()
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
                submitAndroidClipboard()
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
        pointerCaptureRequested = active
        if (active && hasWindowFocus()) {
            surfaceView.requestFocus()
            if (!surfaceView.hasPointerCapture()) {
                surfaceView.requestPointerCapture()
            }
        } else if (!active && surfaceView.hasPointerCapture()) {
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
            // resize a newly starting application. Give the Wayland client a
            // short chance to deactivate text input when the touch targets a
            // menu or another non-editor control before showing Android's IME.
            softImeRequested = true
            handler.removeCallbacks(showImeAfterTouch)
            if (imeState.active) {
                handler.postDelayed(showImeAfterTouch, SOFT_IME_TOUCH_DELAY_MILLIS)
            }
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
        val supportedAction =
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_SCROLL ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        if (sessionId <= 0 || !supportedAction) {
            return false
        }
        val nextButtons = pointerButtonsAfter(event)
        val changedButtons = pointerButtonState xor nextButtons
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

    private fun submitCapturedPointer(event: MotionEvent): Boolean {
        if (sessionId <= 0 || !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false
        }
        val nextButtons = pointerButtonsAfter(event)
        val changedButtons = pointerButtonState xor nextButtons
        val relativeX = capturedAxis(event, MotionEvent.AXIS_RELATIVE_X, MotionEvent.AXIS_X)
        val relativeY = capturedAxis(event, MotionEvent.AXIS_RELATIVE_Y, MotionEvent.AXIS_Y)
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
    ): Int {
        val relative = event.getAxisValue(relativeAxis)
        val value = if (relative != 0f) relative else event.getAxisValue(fallbackAxis)
        if (!value.isFinite()) {
            return 0
        }
        return (
            value.coerceIn(-MAX_RELATIVE_PIXELS, MAX_RELATIVE_PIXELS) *
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
    ): Int = surfaceCoordinate(event.getX(pointerIndex), surfaceView.left, surfaceView.width)

    private fun surfaceY(
        event: MotionEvent,
        pointerIndex: Int = 0,
    ): Int = surfaceCoordinate(event.getY(pointerIndex), surfaceView.top, surfaceView.height)

    private fun surfaceCoordinate(
        windowCoordinate: Float,
        surfaceOffset: Int,
        surfaceExtent: Int,
    ): Int =
        (windowCoordinate - surfaceOffset)
            .roundToInt()
            .coerceIn(0, (surfaceExtent - 1).coerceAtLeast(0))

    private fun pointerButtonsAfter(event: MotionEvent): Int {
        val reported = event.buttonState and POINTER_BUTTON_MASK
        val actionButton = event.actionButton and POINTER_BUTTON_MASK
        return when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> reported or actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> reported and actionButton.inv()
            MotionEvent.ACTION_DOWN ->
                if (reported != 0) reported else pointerButtonState or MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> 0
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
            writeInputRecord(
                data,
                INPUT_KEY,
                event.keyCode,
                keyAction,
                event.eventTime.toInt(),
                event.metaState,
            )
            return sendInputParcel(data, reply)
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

    private fun openSession() {
        val service = remote ?: return
        remoteStatus = STATUS_STARTING
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeStrongBinder(clientToken)
            if (!service.transact(TRANSACTION_OPEN, data, reply, 0)) {
                showUnavailable()
                return
            }
            reply.readException()
            when (reply.readInt()) {
                RESULT_OK -> {
                    sessionId = reply.readInt()
                    val label = reply.readString().orEmpty().take(256)
                    reply.readInt()
                    if (sessionId <= 0 || label.isEmpty()) {
                        showUnavailable()
                        return
                    }
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
                    retryOpen()
                }
                else -> {
                    reply.readInt()
                    reply.readString()
                    reply.readInt()
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
                    attachedSurface = surface
                    attachedWidth = width
                    attachedHeight = height
                    attachedDensityDpi = densityDpi
                    attachedFontScaleMillis = fontScaleMillis
                    Log.i(TAG, "Attached Surface session=$activeSession size=${width}x$height")
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
        }
    }

    private fun startClipboardListening() {
        if (!clipboardListening) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            clipboardListening = true
        }
    }

    private fun stopClipboardListening() {
        if (clipboardListening) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            clipboardListening = false
        }
    }

    private fun submitAndroidClipboard() {
        val service = remote ?: return
        val activeSession = sessionId
        if (activeSession <= 0 || !hasWindowFocus()) {
            return
        }
        val clip =
            runCatching { clipboardManager.primaryClip }
                .getOrElse {
                    Log.w(TAG, "Android clipboard is unavailable while launcher is focused", it)
                    return
                }
        val text =
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else {
                null
            }
        val boundedText =
            if (text == null || text.length <= MAX_CLIPBOARD_UTF16) {
                text
            } else {
                Log.w(TAG, "Android clipboard text exceeds launcher limit")
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

    private fun applyLinuxClipboard(text: String?) {
        if (!hasWindowFocus()) {
            hasPendingLinuxClipboard = true
            pendingLinuxClipboardText = text
            return
        }
        publishLinuxClipboard(text)
    }

    private fun applyPendingLinuxClipboard(): Boolean {
        if (!hasPendingLinuxClipboard) {
            return false
        }
        val text = pendingLinuxClipboardText
        hasPendingLinuxClipboard = false
        pendingLinuxClipboardText = null
        publishLinuxClipboard(text)
        return true
    }

    private fun publishLinuxClipboard(text: String?) {
        runCatching {
            if (text == null) {
                clipboardManager.clearPrimaryClip()
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
        override fun onCapturedPointerEvent(event: MotionEvent): Boolean =
            submitCapturedPointer(event) || super.onCapturedPointerEvent(event)

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            if (!hasCapture && pointerCaptureRequested && hasWindowFocus()) {
                pointerCaptureRequested = false
                submitPointerCaptureLost()
            }
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val state = imeState
            if (!state.active) {
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

    private fun applyImeState(next: ImeState) {
        val previous = imeState
        imeState = next
        if (!hasWindowFocus()) {
            return
        }
        if (!next.active) {
            softImeRequested = false
            handler.removeCallbacks(showImeAfterTouch)
            if (previous.active) {
                hideIme()
            }
            return
        }
        val restart =
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
        safeDocumentName(name) &&
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
            documents.any { document -> !safeDocumentName(document.displayName) }
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
                cursor.getString(index)?.takeIf(::safeDocumentName)
            }
        }.getOrNull()

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
            }

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
                !safeDocumentName(displayName)) ||
            (operation == DOCUMENT_OPERATION_DIRECTORY &&
                result == DOCUMENT_RESULT_SUCCESS &&
                !safeDocumentName(displayName))
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
                if (operation == DOCUMENT_OPERATION_OPEN) {
                    parcel.writeString(displayName)
                    parcel.writeInt(if (writable) 1 else 0)
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
        if (!validBrowserUri(value) || isFinishing || isDestroyed) {
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

    private fun validBrowserUri(value: String): Boolean {
        if (
            value.isBlank() ||
            value.toByteArray(StandardCharsets.UTF_8).size > MAX_BROWSER_URI_BYTES ||
            value.any { character -> character.isISOControl() }
        ) {
            return false
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        return !uri.isOpaque &&
            (
                scheme.equals("http", ignoreCase = true) ||
                    scheme.equals("https", ignoreCase = true)
            ) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535)
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
        private const val CAPABILITIES_V3 = "c:wayland,input,ime,clipboard,documents,open-uri"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV2"
        private const val PROTOCOL_VERSION = 8
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
        private const val CALLBACK_POINTER_CAPTURE = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val CALLBACK_CURSOR = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val CALLBACK_OPEN_URI = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val MAX_BROWSER_URI_BYTES = 4_096
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
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
        private const val MAX_DOCUMENT_NAME_UTF16 = 255
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
