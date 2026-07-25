package org.archphene.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import android.widget.TextView
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

    private lateinit var status: TextView
    private lateinit var surfaceView: LauncherSurfaceView
    private lateinit var content: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var sessionId = 0
    private var attempts = 0
    private var binding = false
    private var managerUid = -1
    private var attachedSurface: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var attachedDensityDpi = 0
    private var attachedFontScaleMillis = 0
    private var pointerButtonState = 0
    private var imeState = ImeState(false, 0, "", 0, 0, 0, 0)
    private var hasPendingLinuxClipboard = false
    private var pendingLinuxClipboardText: String? = null
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
                    code !in CALLBACK_STATUS..CALLBACK_IME_STATE ||
                    Binder.getCallingUid() != managerUid
                ) {
                    return super.onTransact(code, data, reply, flags)
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
                attempts = 0
                openSession()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
                sessionId = 0
                attachedSurface = null
                attachedWidth = 0
                attachedHeight = 0
                attachedDensityDpi = 0
                attachedFontScaleMillis = 0
                pointerButtonState = 0
                hasPendingLinuxClipboard = false
                pendingLinuxClipboardText = null
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
        surfaceView =
            LauncherSurfaceView(this).apply {
                holder.addCallback(this@LauncherActivity)
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        status =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                textSize = 18f
                setTextColor(getColor(R.color.launcher_text))
                setBackgroundColor(getColor(R.color.launcher_background))
                text = getString(R.string.launcher_opening, appLabel())
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

    private fun bindManager() {
        if (binding || remote != null || isFinishing || isDestroyed) {
            return
        }
        val manager = applicationMetadata().getString(MANAGER_PACKAGE).orEmpty()
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
        attachedSurface = null
        attachedWidth = 0
        attachedHeight = 0
        attachedDensityDpi = 0
        attachedFontScaleMillis = 0
        pointerButtonState = 0
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

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        stopClipboardListening()
        hideIme()
        submitHostActive(false)
        detachSurface()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopClipboardListening()
        detachSurface()
        closeSession()
        remote = null
        if (binding) {
            unbindService(connection)
            binding = false
        }
        super.onDestroy()
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
            startClipboardListening()
            if (!applyPendingLinuxClipboard()) {
                submitAndroidClipboard()
            }
            if (imeState.active) {
                showIme(restart = true)
            }
        } else {
            stopClipboardListening()
            hideIme()
        }
        submitHostActive(hasFocus)
    }

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
                        event.getX(index).roundToInt(),
                        event.getY(index).roundToInt(),
                        event.eventTime.toInt(),
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    repeat(count) { index ->
                        writeInputRecord(
                            data,
                            INPUT_TOUCH_MOTION,
                            event.getPointerId(index),
                            event.getX(index).roundToInt(),
                            event.getY(index).roundToInt(),
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
                event.x.roundToInt(),
                event.y.roundToInt(),
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

    private fun pointerButtonsAfter(event: MotionEvent): Int {
        val reported = event.buttonState and POINTER_BUTTON_MASK
        val actionButton = event.actionButton and POINTER_BUTTON_MASK
        return when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> reported or actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> reported and actionButton.inv()
            MotionEvent.ACTION_DOWN ->
                if (reported != 0) reported else pointerButtonState or MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> 0
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
        attachedSurface = null
        attachedWidth = 0
        attachedHeight = 0
        attachedDensityDpi = 0
        attachedFontScaleMillis = 0
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
        private var composing = false
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
                composing = value.isNotEmpty()
            }
            return submitted && super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            val submitted = !composing || submitIme(IME_PREEDIT, "", 0, 0)
            if (submitted) {
                composing = false
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
                composing = false
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
            showIme(restart = true)
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
        val service = remote ?: return
        val activeSession = sessionId
        sessionId = 0
        if (activeSession <= 0) {
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

    private fun showUnavailable() {
        status.setText(R.string.launcher_unavailable)
        status.visibility = View.VISIBLE
    }

    private fun applyRemoteStatus(
        state: Int,
        message: String,
    ) {
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
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val MAX_OPEN_ATTEMPTS = 120
        private const val OPEN_RETRY_MILLIS = 250L
        private const val MIN_DENSITY_DPI = 72
        private const val MAX_DENSITY_DPI = 1_000
        private const val MIN_FONT_SCALE_MILLIS = 500
        private const val MAX_FONT_SCALE_MILLIS = 3_000
        private const val MAX_INPUT_RECORDS = 32
        private const val INPUT_TOUCH_DOWN = 1
        private const val INPUT_TOUCH_MOTION = 2
        private const val INPUT_TOUCH_UP = 3
        private const val INPUT_TOUCH_CANCEL = 4
        private const val INPUT_KEY = 5
        private const val INPUT_POINTER_MOTION = 6
        private const val INPUT_POINTER_BUTTON_V2 = 8
        private const val INPUT_POINTER_AXIS = 9
        private const val INPUT_HOST_ACTIVE = 10
        private const val KEY_RELEASED = 0
        private const val KEY_PRESSED = 1
        private const val KEY_REPEATED = 2
        private const val AXIS_FIXED_SCALE = 1000f
        private const val MAX_AXIS_STEPS = 120f
        private const val MAX_CLIPBOARD_UTF16 = 16_384
        private const val MAX_IME_UTF16 = 4_096
        private const val MAX_IME_BYTES = 16_384
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
