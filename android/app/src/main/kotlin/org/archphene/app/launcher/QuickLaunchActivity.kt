package org.archphene.app.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import org.archphene.app.R
import org.archphene.app.runtime.QuickLaunchCandidate

class QuickLaunchActivity : Activity(), SurfaceHolder.Callback {
    private data class InputRecord(
        val kind: Int,
        val a: Int = 0,
        val b: Int = 0,
        val c: Int = 0,
        val d: Int = 0,
        val e: Int = 0,
    )

    private val destroyed = AtomicBoolean()
    private val mainHandler by lazy { Handler(mainLooper) }
    private lateinit var binderThread: HandlerThread
    private lateinit var binderHandler: Handler
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private var bound = false
    @Volatile private var remote: IBinder? = null
    @Volatile private var sessionId = 0
    @Volatile private var logicalWidth = 0
    @Volatile private var logicalHeight = 0
    private var pointerButtonState = 0
    private lateinit var candidate: QuickLaunchCandidate

    private val clientToken =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != CALLBACK_STATUS) return false
                return runCatching {
                    data.enforceInterface(CALLBACK_INTERFACE)
                    val version = data.readInt()
                    val callbackSession = data.readInt()
                    val state = data.readInt()
                    val message = data.readString().orEmpty().take(MAX_STATUS_LENGTH)
                    if (
                        version != PROTOCOL_VERSION ||
                        callbackSession != sessionId ||
                        state !in STATUS_STARTING..STATUS_STOPPED ||
                        data.dataAvail() != 0
                    ) {
                        return@runCatching false
                    }
                    mainHandler.post {
                        if (!destroyed.get()) {
                            statusView.text = message.ifEmpty { candidate.label }
                            statusView.visibility =
                                if (state == STATUS_RUNNING) TextView.GONE else TextView.VISIBLE
                        }
                    }
                    true
                }.getOrDefault(false)
            }
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                remote = service
                binderHandler.post(::openSession)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                sessionId = 0
                showStatus(R.string.quick_launch_unavailable)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = parseCandidate(intent)
        if (parsed == null) {
            finish()
            return
        }
        candidate = parsed
        title = getString(R.string.quick_launch_title, candidate.label)
        binderThread = HandlerThread("ArchpheneQuickLaunchBinder").also(HandlerThread::start)
        binderHandler = Handler(binderThread.looper)
        surfaceView =
            SurfaceView(this).apply {
                holder.addCallback(this@QuickLaunchActivity)
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
            }
        statusView =
            TextView(this).apply {
                setText(R.string.quick_launch_starting)
                setTextColor(getColor(R.color.archphene_on_surface))
                setBackgroundColor(getColor(R.color.archphene_surface))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), dp(12))
            }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(getColor(R.color.archphene_surface))
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    statusView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP,
                    ),
                )
            },
        )
        bound =
            bindService(
                Intent(this, LauncherSessionService::class.java).setAction(BIND_ACTION),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        if (!bound) showStatus(R.string.quick_launch_unavailable)
    }

    override fun onDestroy() {
        destroyed.set(true)
        val closeRemote = remote
        val closeSession = sessionId
        if (::binderHandler.isInitialized) {
            binderHandler.post {
                if (closeRemote != null && closeSession > 0) {
                    transactSession(closeRemote, TRANSACTION_CLOSE, closeSession)
                }
                binderThread.quitSafely()
            }
        }
        sessionId = 0
        remote = null
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceView.requestFocus()
        attachSurface(holder.surface, surfaceView.width, surfaceView.height)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        attachSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val service = remote ?: return
        val activeSession = sessionId
        if (activeSession > 0) {
            binderHandler.post {
                transactSession(service, TRANSACTION_DETACH_SURFACE, activeSession)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return submitPointerEvent(event) || super.dispatchTouchEvent(event)
        }
        val activeSession = sessionId
        if (activeSession <= 0 || logicalWidth <= 0 || logicalHeight <= 0) {
            return super.dispatchTouchEvent(event)
        }
        val records =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                -> {
                    val index = event.actionIndex
                    listOf(
                        InputRecord(
                            INPUT_TOUCH_DOWN,
                            event.getPointerId(index),
                            surfaceX(event, index),
                            surfaceY(event, index),
                            event.eventTime.toInt(),
                        ),
                    )
                }
                MotionEvent.ACTION_MOVE ->
                    List(event.pointerCount.coerceAtMost(MAX_INPUT_RECORDS)) { index ->
                        InputRecord(
                            INPUT_TOUCH_MOTION,
                            event.getPointerId(index),
                            surfaceX(event, index),
                            surfaceY(event, index),
                            event.eventTime.toInt(),
                        )
                    }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                -> {
                    val index = event.actionIndex
                    listOf(
                        InputRecord(
                            INPUT_TOUCH_UP,
                            event.getPointerId(index),
                            event.eventTime.toInt(),
                        ),
                    )
                }
                MotionEvent.ACTION_CANCEL -> listOf(InputRecord(INPUT_TOUCH_CANCEL))
                else -> return super.dispatchTouchEvent(event)
            }
        submitInput(activeSession, records)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) || sessionId <= 0) {
            return super.dispatchGenericMotionEvent(event)
        }
        return submitPointerEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            sessionId <= 0 ||
            event.keyCode == KeyEvent.KEYCODE_BACK ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
        ) {
            return super.dispatchKeyEvent(event)
        }
        submitInput(
            sessionId,
            listOf(
                InputRecord(
                    INPUT_KEY,
                    event.keyCode,
                    when {
                        event.action == KeyEvent.ACTION_UP -> KEY_RELEASED
                        event.repeatCount > 0 -> KEY_REPEATED
                        else -> KEY_PRESSED
                    },
                    event.eventTime.toInt(),
                    event.metaState,
                ),
            ),
        )
        return true
    }

    private fun openSession() {
        if (destroyed.get() || sessionId > 0) return
        val service = remote ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeStrongBinder(clientToken)
            data.writeString(candidate.androidPackage)
            data.writeString(candidate.descriptorIdHex)
            data.writeLong(candidate.generation)
            if (!service.transact(TRANSACTION_OPEN_QUICK, data, reply, 0)) {
                showStatus(R.string.quick_launch_unavailable)
                return
            }
            reply.readException()
            when (reply.readInt()) {
                RESULT_OK -> {
                    val openedSession = reply.readInt()
                    val label = reply.readString().orEmpty().take(MAX_STATUS_LENGTH)
                    val terminal = reply.readInt()
                    if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt()
                    if (openedSession <= 0 || label.isEmpty() || terminal != 0) {
                        showStatus(R.string.quick_launch_unavailable)
                        return
                    }
                    sessionId = openedSession
                    mainHandler.post {
                        if (!destroyed.get()) {
                            statusView.text = getString(R.string.quick_launch_connected, label)
                        }
                    }
                    mainHandler.post {
                        if (!destroyed.get()) {
                            val surface = surfaceView.holder.surface
                            if (surface.isValid) {
                                attachSurface(surface, surfaceView.width, surfaceView.height)
                            }
                        }
                    }
                }
                RESULT_NOT_READY -> {
                    consumeOpenReply(reply)
                    if (!destroyed.get()) binderHandler.postDelayed(::openSession, RETRY_MILLIS)
                }
                else -> {
                    consumeOpenReply(reply)
                    showStatus(R.string.quick_launch_rejected)
                }
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Quick launch session failed", error)
            showStatus(R.string.quick_launch_unavailable)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun consumeOpenReply(reply: Parcel) {
        reply.readInt()
        reply.readString()
        reply.readInt()
        if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt()
    }

    private fun attachSurface(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        if (!surface.isValid || width <= 0 || height <= 0) return
        val service = remote ?: return
        val activeSession = sessionId
        if (activeSession <= 0) return
        val densityDpi = resources.configuration.densityDpi
        val fontScaleMillis =
            (resources.configuration.fontScale * 1_000f).roundToInt().coerceIn(500, 3_000)
        binderHandler.post {
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
                    val result = reply.readInt()
                    if (result == RESULT_OK) {
                        logicalWidth = reply.readInt()
                        logicalHeight = reply.readInt()
                        Log.i(
                            TAG,
                            "Attached Quick launch surface session=$activeSession " +
                                "logical=${logicalWidth}x$logicalHeight",
                        )
                    } else {
                        Log.w(TAG, "Quick launch surface rejected result=$result")
                    }
                } else {
                    Log.w(TAG, "Quick launch surface transaction was not handled")
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not attach Quick launch surface", error)
            } catch (error: RemoteException) {
                Log.w(TAG, "Quick launch surface service failed", error)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun submitInput(
        activeSession: Int,
        records: List<InputRecord>,
    ) {
        if (records.isEmpty() || records.size > MAX_INPUT_RECORDS) return
        val service = remote ?: return
        binderHandler.post {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(INTERFACE)
                data.writeInt(PROTOCOL_VERSION)
                data.writeInt(activeSession)
                data.writeInt(records.size)
                records.forEach { record ->
                    data.writeInt(record.kind)
                    data.writeInt(record.a)
                    data.writeInt(record.b)
                    data.writeInt(record.c)
                    data.writeInt(record.d)
                    data.writeInt(record.e)
                }
                if (service.transact(TRANSACTION_INPUT, data, reply, 0)) reply.readException()
            } catch (error: RemoteException) {
                Log.w(TAG, "Could not submit Quick launch input", error)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun submitPointerEvent(event: MotionEvent): Boolean {
        val activeSession = sessionId
        val supportedAction =
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL ||
                event.actionMasked == MotionEvent.ACTION_SCROLL ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        if (activeSession <= 0 || !supportedAction) return false
        val nextButtons = pointerButtonsAfter(event)
        val changedButtons = pointerButtonState xor nextButtons
        val horizontal = axisToFixed(event.getAxisValue(MotionEvent.AXIS_HSCROLL))
        val vertical = axisToFixed(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        val records =
            ArrayList<InputRecord>(
                1 +
                    Integer.bitCount(changedButtons) +
                    if (horizontal != 0 || vertical != 0) 1 else 0,
            )
        records +=
            InputRecord(
                INPUT_POINTER_MOTION,
                surfaceX(event),
                surfaceY(event),
                event.eventTime.toInt(),
            )
        for (button in POINTER_BUTTONS) {
            if (changedButtons and button != 0) {
                records +=
                    InputRecord(
                        INPUT_POINTER_BUTTON,
                        button,
                        if (nextButtons and button != 0) 1 else 0,
                        event.eventTime.toInt(),
                    )
            }
        }
        if (horizontal != 0 || vertical != 0) {
            records +=
                InputRecord(
                    INPUT_POINTER_AXIS,
                    horizontal,
                    vertical,
                    event.eventTime.toInt(),
                )
        }
        submitInput(activeSession, records)
        pointerButtonState = nextButtons
        return true
    }

    private fun pointerButtonsAfter(event: MotionEvent): Int {
        val reported = event.buttonState and POINTER_BUTTON_MASK
        val actionButton = event.actionButton and POINTER_BUTTON_MASK
        return when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> reported or actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> reported and actionButton.inv()
            MotionEvent.ACTION_DOWN ->
                if (reported != 0) reported else pointerButtonState or MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> 0
            MotionEvent.ACTION_MOVE -> if (reported != 0) reported else pointerButtonState
            else -> reported
        } and POINTER_BUTTON_MASK
    }

    private fun transactSession(
        service: IBinder,
        transaction: Int,
        activeSession: Int,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE)
            data.writeInt(PROTOCOL_VERSION)
            data.writeInt(activeSession)
            if (service.transact(transaction, data, reply, 0)) reply.readException()
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not update Quick launch session", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun surfaceX(
        event: MotionEvent,
        index: Int = 0,
    ): Int =
        mapCoordinate(event.getX(index), surfaceView.width, logicalWidth)

    private fun surfaceY(
        event: MotionEvent,
        index: Int = 0,
    ): Int =
        mapCoordinate(event.getY(index), surfaceView.height, logicalHeight)

    private fun mapCoordinate(
        value: Float,
        viewExtent: Int,
        logicalExtent: Int,
    ): Int {
        if (!value.isFinite() || viewExtent <= 0 || logicalExtent <= 0) return 0
        return (value.coerceIn(0f, viewExtent.toFloat()) * logicalExtent / viewExtent)
            .roundToInt()
            .coerceIn(0, logicalExtent)
    }

    private fun axisToFixed(value: Float): Int {
        if (!value.isFinite()) return 0
        return (value.coerceIn(-MAX_AXIS_STEPS, MAX_AXIS_STEPS) * AXIS_FIXED_SCALE).roundToInt()
    }

    private fun showStatus(message: Int) {
        mainHandler.post {
            if (!destroyed.get()) {
                statusView.setText(message)
                statusView.visibility = TextView.VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun parseCandidate(intent: Intent): QuickLaunchCandidate? {
        val androidPackage = intent.getStringExtra(EXTRA_ANDROID_PACKAGE).orEmpty()
        val descriptorIdHex = intent.getStringExtra(EXTRA_DESCRIPTOR_ID).orEmpty()
        val generation = intent.getLongExtra(EXTRA_GENERATION, 0)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        if (
            androidPackage.length != 53 ||
            !androidPackage.startsWith(LAUNCHER_PACKAGE_PREFIX) ||
            !androidPackage.drop(LAUNCHER_PACKAGE_PREFIX.length).all { character ->
                character.isDigit() || character in 'a'..'f'
            } ||
            descriptorIdHex.length != 64 ||
            !descriptorIdHex.all { character -> character.isDigit() || character in 'a'..'f' } ||
            generation !in 1..Int.MAX_VALUE.toLong() ||
            label.isEmpty() ||
            label.length > MAX_STATUS_LENGTH ||
            label.any(Char::isISOControl)
        ) {
            return null
        }
        return QuickLaunchCandidate(androidPackage, descriptorIdHex, generation, label)
    }

    companion object {
        private const val TAG = "ArchpheneQuickLaunch"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private const val INTERFACE = "org.archphene.launcher.ISessionV2"
        private const val CALLBACK_INTERFACE = "org.archphene.launcher.IClientV2"
        private const val PROTOCOL_VERSION = 21
        private const val LAUNCHER_PACKAGE_PREFIX = "org.archphene.linux.p"
        private const val EXTRA_ANDROID_PACKAGE = "org.archphene.extra.QUICK_ANDROID_PACKAGE"
        private const val EXTRA_DESCRIPTOR_ID = "org.archphene.extra.QUICK_DESCRIPTOR_ID"
        private const val EXTRA_GENERATION = "org.archphene.extra.QUICK_GENERATION"
        private const val EXTRA_LABEL = "org.archphene.extra.QUICK_LABEL"
        private const val TRANSACTION_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_ATTACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_DETACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_INPUT = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_OPEN_QUICK = IBinder.FIRST_CALL_TRANSACTION + 12
        private const val CALLBACK_STATUS = IBinder.FIRST_CALL_TRANSACTION
        private const val RESULT_OK = 0
        private const val RESULT_NOT_READY = 1
        private const val STATUS_STARTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_STOPPED = 3
        private const val INPUT_TOUCH_DOWN = 1
        private const val INPUT_TOUCH_MOTION = 2
        private const val INPUT_TOUCH_UP = 3
        private const val INPUT_TOUCH_CANCEL = 4
        private const val INPUT_KEY = 5
        private const val INPUT_POINTER_MOTION = 6
        private const val INPUT_POINTER_BUTTON = 8
        private const val INPUT_POINTER_AXIS = 9
        private const val KEY_RELEASED = 0
        private const val KEY_PRESSED = 1
        private const val KEY_REPEATED = 2
        private const val MAX_INPUT_RECORDS = 32
        private const val MAX_STATUS_LENGTH = 256
        private const val AXIS_FIXED_SCALE = 1000f
        private const val MAX_AXIS_STEPS = 120f
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
        private const val RETRY_MILLIS = 300L

        internal fun createIntent(
            context: Context,
            candidate: QuickLaunchCandidate,
        ): Intent =
            Intent(context, QuickLaunchActivity::class.java)
                .putExtra(EXTRA_ANDROID_PACKAGE, candidate.androidPackage)
                .putExtra(EXTRA_DESCRIPTOR_ID, candidate.descriptorIdHex)
                .putExtra(EXTRA_GENERATION, candidate.generation)
                .putExtra(EXTRA_LABEL, candidate.label)
    }
}
