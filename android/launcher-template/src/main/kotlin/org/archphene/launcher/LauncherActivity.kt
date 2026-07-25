package org.archphene.launcher

import android.app.Activity
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
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

class LauncherActivity :
    Activity(),
    SurfaceHolder.Callback {
    private lateinit var status: TextView
    private lateinit var surfaceView: SurfaceView
    private val handler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var sessionId = 0
    private var attempts = 0
    private var binding = false
    private var managerUid = -1
    private var attachedSurface: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private val clientToken =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != CALLBACK_STATUS || Binder.getCallingUid() != managerUid) {
                    return super.onTransact(code, data, reply, flags)
                }
                return runCatching {
                    data.enforceInterface(CALLBACK_INTERFACE)
                    val version = data.readInt()
                    val callbackSession = data.readInt()
                    val state = data.readInt()
                    val message = data.readString().orEmpty()
                    if (
                        version != PROTOCOL_VERSION ||
                        callbackSession != sessionId ||
                        state !in STATUS_STARTING..STATUS_STOPPED ||
                        message.isEmpty() ||
                        message.length > 256 ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching false
                    }
                    handler.post { applyRemoteStatus(state, message) }
                    true
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
            SurfaceView(this).apply {
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
        setContentView(
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
            },
        )
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
        if (binding) {
            runCatching { unbindService(connection) }
            binding = false
        }
        status.setText(R.string.launcher_disconnected)
        status.visibility = View.VISIBLE
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        detachSurface()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
            return submitPrimaryPointer(event) || super.dispatchTouchEvent(event)
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
        return submitPrimaryPointer(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun submitPrimaryPointer(event: MotionEvent): Boolean {
        val supportedAction =
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        val button =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_BUTTON_PRESS,
                -> true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_BUTTON_RELEASE,
                -> false
                else -> null
            }
        val primaryButton =
            event.actionButton == MotionEvent.BUTTON_PRIMARY ||
                event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 ||
                (event.actionMasked == MotionEvent.ACTION_UP && event.actionButton == 0)
        if (
            sessionId <= 0 ||
            !supportedAction ||
            (button != null && !primaryButton)
        ) {
            return false
        }
        val count = if (button == null) 1 else 2
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
            if (count == 2) {
                writeInputRecord(
                    data,
                    INPUT_POINTER_BUTTON,
                    if (button == true) 1 else 0,
                    event.eventTime.toInt(),
                )
            }
            return sendInputParcel(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
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
            writeInputRecord(
                data,
                INPUT_KEY,
                event.keyCode,
                if (event.action == KeyEvent.ACTION_DOWN) 1 else 0,
                event.eventTime.toInt(),
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
            attachedHeight == height
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
            if (service.transact(TRANSACTION_ATTACH_SURFACE, data, reply, 0)) {
                reply.readException()
                if (reply.readInt() == RESULT_OK) {
                    attachedSurface = surface
                    attachedWidth = width
                    attachedHeight = height
                    Log.i(TAG, "Attached Surface session=$activeSession size=${width}x$height")
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
        private const val CALLBACK_INTERFACE = "org.archphene.launcher.IClientV1"
        private const val CALLBACK_STATUS = IBinder.FIRST_CALL_TRANSACTION
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val MAX_OPEN_ATTEMPTS = 120
        private const val OPEN_RETRY_MILLIS = 250L
        private const val MAX_INPUT_RECORDS = 32
        private const val INPUT_TOUCH_DOWN = 1
        private const val INPUT_TOUCH_MOTION = 2
        private const val INPUT_TOUCH_UP = 3
        private const val INPUT_TOUCH_CANCEL = 4
        private const val INPUT_KEY = 5
        private const val INPUT_POINTER_MOTION = 6
        private const val INPUT_POINTER_BUTTON = 7
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private val SAFE_PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,7}")
    }
}
