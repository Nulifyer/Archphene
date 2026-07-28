package org.archphene.app

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.archphene.app.performance.PerformanceMetrics
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.InputBatch
import org.archphene.app.runtime.NativeRuntime

internal class RuntimeSurfaceView(
    context: Context,
    persistedTextSp: Int,
) : View(context) {
    private val inputBatch = InputBatch()
    private val terminalInputBytes = ByteArray(TERMINAL_INPUT_LIMIT)
    private var automaticTextSize = persistedTextSp == AUTOMATIC_TEXT_SIZE
    private var terminalTextSp =
        persistedTextSp
            .takeUnless { it == AUTOMATIC_TEXT_SIZE }
            ?.coerceIn(MIN_TERMINAL_TEXT_SP, MAX_TERMINAL_TEXT_SP)
            ?: AUTOMATIC_TERMINAL_TEXT_SP
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface =
                Typeface.createFromAsset(
                    context.assets,
                    TERMINAL_FONT_ASSET,
                )
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    terminalTextSp.toFloat(),
                    resources.displayMetrics,
                )
        }
    private val backgroundPaint = Paint()
    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onScroll(
                    first: MotionEvent?,
                    current: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (
                        scaleGestureDetector.isInProgress ||
                        selectionDragging ||
                        historyRows == 0
                    ) {
                        return false
                    }
                    scrollRowRemainder += distanceY / cellHeight
                    val rowsToScroll = scrollRowRemainder.toInt()
                    if (rowsToScroll == 0) {
                        return true
                    }
                    scrollRowRemainder -= rowsToScroll
                    setViewportOffset(viewportOffset + rowsToScroll)
                    return true
                }

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    clearSelection()
                    performClick()
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    startSelection(event.x, event.y)
                }
            },
        )
    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pendingPinchTextSp = terminalTextSp.toFloat()
                    pinchPreviewScale = 1f
                    pinchFocusX = detector.focusX
                    pinchFocusY = detector.focusY
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pendingPinchTextSp =
                        (pendingPinchTextSp * detector.scaleFactor)
                            .coerceIn(
                                MIN_TERMINAL_TEXT_SP.toFloat(),
                                MAX_TERMINAL_TEXT_SP.toFloat(),
                            )
                    pinchPreviewScale = pendingPinchTextSp / terminalTextSp
                    pinchFocusX = detector.focusX
                    pinchFocusY = detector.focusY
                    invalidate()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    pinchPreviewScale = 1f
                    setTerminalTextSize(pendingPinchTextSp.roundToInt(), false)
                }
            },
        )
    private var glyphCodepoints = IntArray(0)
    private var glyphLengths = ByteArray(0)
    private var glyphWidths = ByteArray(0)
    private val rowGlyphScratch = CharArray(MAX_COLUMNS * MAX_GRAPHEME_UTF16_UNITS)
    private var styledForegroundColors = IntArray(0)
    private var backgroundColors = IntArray(0)
    private var blinkingRows = BooleanArray(0)
    private var blinkingRowCount = 0
    private var rowNodes = emptyArray<RenderNode>()
    private var rows = 0
    private var columns = 0
    private var cursorRow = 0
    private var cursorColumn = 0
    private var cursorVisible = false
    private var cursorColor = DEFAULT_CURSOR_COLOR
    private var terminalFlags = 0
    private var cursorBlinkPhaseVisible = true
    private var textBlinkPhaseVisible = true
    private var blinkAnimationPosted = false
    private var mousePointerId = NO_MOUSE_POINTER
    private var mouseLocalSelectionOverride = false
    private var mousePressedButton = MOUSE_BUTTON_PRIMARY
    private var lastMouseColumn = NO_MOUSE_POSITION
    private var lastMouseRow = NO_MOUSE_POSITION
    private var lastMousePixelX = NO_MOUSE_POSITION
    private var lastMousePixelY = NO_MOUSE_POSITION
    private val mousePositionScratch = MousePosition()
    private var terminalFocusStateKnown = false
    private var terminalFocused = false
    private var terminalRevision = Long.MIN_VALUE
    private var sourceRevision = Long.MIN_VALUE
    private var synchronizedOutputPending = false
    private var historyRows = 0
    private var historyOriginEpoch = 0L
    private var viewportOffset = 0
    private var scrollRowRemainder = 0f
    private var selectionStart = NO_SELECTION
    private var selectionEnd = NO_SELECTION
    private var selectionInitialStart = NO_SELECTION
    private var selectionInitialEnd = NO_SELECTION
    private var selectionOriginEpoch = 0L
    private var selectionDragEndpoint = SELECTION_DRAG_NONE
    private var selectionDragging = false
    private var selectionAutoScrollDirection = 0
    private var selectionAutoScrollFrame = 0
    private var selectionAutoScrollX = 0f
    private val accessibilityManager =
        context.getSystemService(AccessibilityManager::class.java)
    private var accessibilityEventPosted = false
    private var accessibilityTextChanged = false
    private var accessibilityScrolled = false
    private val accessibilityEventRunnable =
        Runnable {
            accessibilityEventPosted = false
            if (!accessibilityManager.isEnabled || !isAttachedToWindow) {
                accessibilityTextChanged = false
                accessibilityScrolled = false
                return@Runnable
            }
            if (accessibilityTextChanged) {
                accessibilityTextChanged = false
                sendTerminalAccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                ) { event ->
                    event.contentChangeTypes =
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                }
            }
            if (accessibilityScrolled) {
                accessibilityScrolled = false
                sendTerminalAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) { event ->
                    val firstVisibleRow = (historyRows - viewportOffset).coerceAtLeast(0)
                    val totalRows = historyRows + rows
                    event.fromIndex = firstVisibleRow
                    event.toIndex = (firstVisibleRow + rows - 1).coerceAtMost(totalRows - 1)
                    event.itemCount = totalRows
                    event.scrollY = firstVisibleRow
                    event.maxScrollY = historyRows
                }
            }
        }
    private var needsFullSnapshot = true
    private var composingText = ""
    private val terminalInputConnection by lazy(LazyThreadSafetyMode.NONE) {
        TerminalInputConnection()
    }
    private var pastePopup: PopupMenu? = null
    private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var pendingPinchTextSp = terminalTextSp.toFloat()
    private var pinchPreviewScale = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var cellWidth = ceil(textPaint.measureText("M").toDouble()).toFloat().coerceAtLeast(1f)
    private var cellHeight =
        ceil((textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toDouble())
            .toFloat()
            .coerceAtLeast(1f)
    private val cursorStrokeWidth =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CURSOR_STROKE_WIDTH_DP,
            resources.displayMetrics,
        )
    private val selectionHandleRadius =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            SELECTION_HANDLE_RADIUS_DP,
            resources.displayMetrics,
        )
    private val selectionHandleTouchRadius =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            SELECTION_HANDLE_TOUCH_RADIUS_DP,
            resources.displayMetrics,
        )
    private val selectionAutoScrollRunnable = Runnable { runSelectionAutoScroll() }
    private val blinkAnimationRunnable =
        object : Runnable {
            override fun run() {
                blinkAnimationPosted = false
                val blinkCursor = shouldBlinkCursor()
                val blinkText = shouldBlinkText()
                if (!blinkCursor && !blinkText) {
                    stopBlinkAnimation(revealContent = true)
                    return
                }
                if (blinkCursor) {
                    cursorBlinkPhaseVisible = !cursorBlinkPhaseVisible
                }
                if (blinkText) {
                    textBlinkPhaseVisible = !textBlinkPhaseVisible
                }
                for (row in blinkingRows.indices) {
                    if (blinkingRows[row]) {
                        recordRow(row)
                    }
                }
                if (
                    blinkCursor &&
                    (cursorRow !in blinkingRows.indices || !blinkingRows[cursorRow])
                ) {
                    recordRow(cursorRow)
                }
                invalidate()
                scheduleBlinkAnimation()
            }
        }

    var onTerminalSizeChanged: ((rows: Int, columns: Int) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        setBackgroundColor(TERMINAL_BACKGROUND)
        contentDescription = context.getString(R.string.linux_session_display)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val previewingPinch = pinchPreviewScale != 1f
        if (previewingPinch) {
            canvas.save()
            canvas.scale(pinchPreviewScale, pinchPreviewScale, pinchFocusX, pinchFocusY)
        }
        if (rows == 0 || columns == 0) {
            textPaint.color = ANSI_COLORS[7]
            textPaint.isFakeBoldText = false
            canvas.drawText(
                context.getString(R.string.linux_session_display),
                CONTENT_PADDING,
                CONTENT_PADDING - textPaint.fontMetrics.ascent,
                textPaint,
            )
            if (previewingPinch) {
                canvas.restore()
            }
            return
        }
        for (node in rowNodes) {
            if (node.hasDisplayList()) {
                canvas.drawRenderNode(node)
            }
        }
        drawSelectionHandles(canvas)
        drawComposingText(canvas)
        if (previewingPinch) {
            canvas.restore()
        }
        PerformanceMetrics.noteTerminalFrame(SystemClock.uptimeMillis())
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        positionRowNodes()
        if (width > 0) {
            for (row in rowNodes.indices) {
                recordRow(row)
            }
            if (rowNodes.isNotEmpty()) {
                invalidate()
            }
        }
        publishTerminalSize(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlinkAnimation()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            restartBlinkAnimation()
        } else {
            stopBlinkAnimation(revealContent = true)
        }
        reportTerminalFocusChange()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            restartBlinkAnimation()
        } else {
            stopBlinkAnimation(revealContent = true)
        }
        reportTerminalFocusChange()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        reportTerminalFocusChange()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            mouseLocalSelectionOverride =
                terminalMouseReportingActive() &&
                    event.metaState and KeyEvent.META_SHIFT_ON != 0
        }
        val mouseReporting =
            terminalMouseReportingActive() &&
                !mouseLocalSelectionOverride
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scrollRowRemainder = 0f
            if (!mouseReporting) {
                beginSelectionHandleDrag(event.x, event.y)
            }
        }
        val selectionOwnedGesture = selectionDragging
        if (!selectionOwnedGesture && !mouseReporting) {
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }
        val kind =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    InputBatch.KIND_TOUCH_DOWN
                MotionEvent.ACTION_MOVE -> InputBatch.KIND_TOUCH_MOVE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> InputBatch.KIND_TOUCH_UP
                MotionEvent.ACTION_CANCEL -> InputBatch.KIND_TOUCH_CANCEL
                else -> return false
            }
        val pointerIndex = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        inputBatch.append(
            kind = kind,
            flags = event.getPointerId(pointerIndex),
            timeNanos = event.eventTime * NANOS_PER_MILLISECOND,
            argument0 = event.getX(pointerIndex).toRawBits(),
            argument1 = event.getY(pointerIndex).toRawBits(),
        )
        if (mouseReporting) {
            handleTerminalMouseEvent(event)
            return true
        }
        if (selectionDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    extendSelection(event.x, event.y)
                    updateSelectionAutoScroll(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    extendSelection(event.getX(pointerIndex), event.getY(pointerIndex))
                    stopSelectionAutoScroll()
                    selectionDragging = false
                    selectionDragEndpoint = SELECTION_DRAG_NONE
                    showTerminalContextMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopSelectionAutoScroll()
                    selectionDragging = false
                    selectionDragEndpoint = SELECTION_DRAG_NONE
                }
            }
        }
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            mouseLocalSelectionOverride = false
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (
                vertical != 0f &&
                terminalMouseReportingActive() &&
                event.metaState and KeyEvent.META_SHIFT_ON == 0
            ) {
                val position = terminalMousePosition(event.x, event.y) ?: return true
                sendTerminalMouseReport(
                    button =
                        if (vertical > 0f) {
                            MOUSE_BUTTON_WHEEL_UP
                        } else {
                            MOUSE_BUTTON_WHEEL_DOWN
                        },
                    column = position.column,
                    row = position.row,
                    pixelX = position.pixelX,
                    pixelY = position.pixelY,
                    modifiers = mouseModifiers(event),
                    release = false,
                    motion = false,
                    eventTimeMillis = event.eventTime,
                )
                return true
            }
            if (vertical != 0f && historyRows != 0) {
                setViewportOffset(
                    viewportOffset + (vertical * SCROLL_WHEEL_ROWS).roundToInt(),
                )
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (
            terminalMouseReportingActive() &&
            terminalMouseTrackingMode() == MOUSE_TRACKING_ANY_EVENT &&
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        ) {
            val position = terminalMousePosition(event.x, event.y) ?: return true
            if (!mousePositionChanged(position)) {
                return true
            }
            if (
                sendTerminalMouseReport(
                    button = MOUSE_BUTTON_NONE,
                    column = position.column,
                    row = position.row,
                    pixelX = position.pixelX,
                    pixelY = position.pixelY,
                    modifiers = mouseModifiers(event),
                    release = false,
                    motion = true,
                    eventTimeMillis = event.eventTime,
                )
            ) {
                rememberMousePosition(position)
            }
            return true
        }
        return super.onHoverEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        return true
    }

    override fun performLongClick(): Boolean = showTerminalContextMenu()

    private fun showTerminalContextMenu(): Boolean {
        if (runtimeBinder == null) {
            return false
        }
        requestFocus()
        pastePopup?.dismiss()
        val popup = PopupMenu(context, this)
        popup.menu.add(0, android.R.id.copy, 0, android.R.string.copy).apply {
            isEnabled = hasSelection()
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        popup.menu.add(0, android.R.id.paste, 1, android.R.string.paste).apply {
            isEnabled =
                context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        popup.menu.add(0, MENU_TEXT_SMALLER, 2, R.string.terminal_text_smaller).apply {
            isEnabled = terminalTextSp > MIN_TERMINAL_TEXT_SP
        }
        popup.menu.add(
            0,
            MENU_TEXT_RESET,
            3,
            if (automaticTextSize) {
                context.getString(R.string.terminal_text_size_automatic, terminalTextSp)
            } else {
                context.getString(R.string.terminal_text_size_explicit, terminalTextSp)
            },
        )
        popup.menu.add(0, MENU_TEXT_LARGER, 4, R.string.terminal_text_larger).apply {
            isEnabled = terminalTextSp < MAX_TERMINAL_TEXT_SP
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                android.R.id.copy -> copySelection()
                android.R.id.paste -> pasteClipboard()
                MENU_TEXT_SMALLER -> setTerminalTextSize(terminalTextSp - 1, false)
                MENU_TEXT_RESET -> setTerminalTextSize(AUTOMATIC_TERMINAL_TEXT_SP, true)
                MENU_TEXT_LARGER -> setTerminalTextSize(terminalTextSp + 1, false)
                else -> false
            }
        }
        popup.setOnDismissListener {
            if (pastePopup === popup) {
                pastePopup = null
            }
        }
        pastePopup = popup
        popup.show()
        return true
    }

    override fun onDetachedFromWindow() {
        stopSelectionAutoScroll()
        stopBlinkAnimation(revealContent = false)
        removeCallbacks(accessibilityEventRunnable)
        accessibilityEventPosted = false
        accessibilityTextChanged = false
        accessibilityScrolled = false
        pastePopup?.dismiss()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.isEditable = false
        info.isMultiLine = true
        info.isScrollable = historyRows > 0
        info.isSelected = hasSelection()
        if (rows != 0 && columns != 0) {
            val snapshot = accessibilitySnapshot()
            info.text =
                if (composingText.isEmpty()) {
                    snapshot
                } else {
                    "$snapshot\n${context.getString(
                        R.string.terminal_composing_text,
                        composingText,
                    )}"
                }
            terminalAccessibilitySelection(snapshot.length)?.let { selection ->
                info.setTextSelection(selection.first, selection.last)
            }
            if (snapshot.isNotEmpty()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
            }
        }
        if (hasSelection()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        }
        if (
            runtimeBinder != null &&
            context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
        ) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        }
        if (viewportOffset < historyRows) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        if (viewportOffset > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
    }

    override fun performAccessibilityAction(
        action: Int,
        arguments: Bundle?,
    ): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_COPY -> copySelection()
            AccessibilityNodeInfo.ACTION_PASTE -> pasteClipboard()
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                setViewportOffset(viewportOffset + rows.coerceAtLeast(1))
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                setViewportOffset(viewportOffset - rows.coerceAtLeast(1))
            AccessibilityNodeInfo.ACTION_SET_SELECTION ->
                setAccessibilitySelection(arguments)
            else -> super.performAccessibilityAction(action, arguments)
        }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions =
            EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return terminalInputConnection
    }

    internal fun debugSetComposingText(text: String): Boolean =
        terminalInputConnection.setComposingText(text, 1)

    internal fun debugFinishComposingText(): Boolean =
        terminalInputConnection.finishComposingText()

    internal fun debugCommitText(text: String): Boolean =
        terminalInputConnection.commitText(text, 1)

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        when (terminalTextSizeShortcut(keyCode, event)) {
            TEXT_SIZE_SMALLER -> return setTerminalTextSize(terminalTextSp - 1, false)
            TEXT_SIZE_RESET -> return setTerminalTextSize(AUTOMATIC_TERMINAL_TEXT_SP, true)
            TEXT_SIZE_LARGER -> return setTerminalTextSize(terminalTextSp + 1, false)
        }
        if (event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_UP ->
                    return setViewportOffset(viewportOffset + rows.coerceAtLeast(1))
                KeyEvent.KEYCODE_PAGE_DOWN ->
                    return setViewportOffset(viewportOffset - rows.coerceAtLeast(1))
            }
        }
        if (isPasteShortcut(keyCode, event)) {
            return pasteClipboard()
        }
        if (isCopyShortcut(keyCode, event)) {
            return copySelection()
        }
        if (sendModifiedTerminalSequence(keyCode, event)) {
            return true
        }
        terminalSequence(keyCode, event.isShiftPressed)?.let {
            return sendSequence(it, event.eventTime)
        }
        val altGraph = isAltGraph(event)
        val baseCodepoint = textCodepoint(event, altGraph)
        if (baseCodepoint == 0) {
            return super.onKeyDown(keyCode, event)
        }
        var inputCodepoint = baseCodepoint
        if (event.isCtrlPressed && !altGraph) {
            val control = controlCodepoint(baseCodepoint)
            if (control >= 0) {
                inputCodepoint = control
            }
        }
        val encoded =
            TerminalKeyEncoder.encodeModifiedCodepoint(
                inputCodepoint,
                meta = event.isAltPressed && !altGraph,
                eightBitMeta = terminalFlags and EIGHT_BIT_META_FLAG != 0,
                output = terminalInputBytes,
            )
        return encoded != 0 && submitTerminalInput(encoded, event.eventTime)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean =
        if (
            terminalTextSizeShortcut(keyCode, event) != TEXT_SIZE_UNCHANGED ||
            isPasteShortcut(keyCode, event) ||
            isCopyShortcut(keyCode, event) ||
            terminalSequence(keyCode, event.isShiftPressed) != null ||
            textCodepoint(event, isAltGraph(event)) != 0
        ) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }

    fun flushInput(handle: Long) {
        val submitted = inputBatch.flush(handle)
        if (submitted > 0) {
            NativeRuntime.nativeDrainInput(handle, submitted)
        }
    }

    fun renderFrame(binder: ArchpheneRuntimeService.LocalBinder?) {
        if (binder?.sharedShellRunning != true) {
            runtimeBinder = null
            synchronizedOutputPending = false
            if (rows != 0) {
                clearTerminal()
            }
            return
        }
        runtimeBinder = binder
        val nextSourceRevision = binder.sharedShellTerminalRevision
        if (
            !needsFullSnapshot &&
            !synchronizedOutputPending &&
            nextSourceRevision == sourceRevision
        ) {
            return
        }
        val length =
            binder.readSharedShellTerminalDamage(
                needsFullSnapshot,
                viewportOffset,
            )
        if (length == 0) {
            synchronizedOutputPending = true
            sourceRevision = nextSourceRevision
            return
        }
        synchronizedOutputPending = false
        needsFullSnapshot = false
        if (
            length < DAMAGE_HEADER_SIZE ||
            !applyDamage(binder.sharedShellTerminalDamageBuffer, length)
        ) {
            needsFullSnapshot = true
            sourceRevision = nextSourceRevision
            return
        }
        sourceRevision = nextSourceRevision
    }

    fun synchronizeTerminalSize(binder: ArchpheneRuntimeService.LocalBinder?) {
        if (width > 0 && height > 0) {
            binder?.resizeSharedShell(preferredRows(height), preferredColumns(width))
        }
    }

    private fun applyDamage(
        damageBuffer: ByteBuffer,
        length: Int,
    ): Boolean {
        if (
            length > damageBuffer.capacity() ||
            damageBuffer.getInt(0) != DAMAGE_MAGIC ||
            damageBuffer.getInt(4) != DAMAGE_VERSION
        ) {
            return false
        }
        val nextRows = damageBuffer.getShort(8).toInt() and 0xffff
        val nextColumns = damageBuffer.getShort(10).toInt() and 0xffff
        val nextCursorRow = damageBuffer.getShort(12).toInt() and 0xffff
        val nextCursorColumn = damageBuffer.getShort(14).toInt() and 0xffff
        val dirtyStart = damageBuffer.getShort(16).toInt() and 0xffff
        val dirtyEnd = damageBuffer.getShort(18).toInt() and 0xffff
        val nextTerminalFlags = damageBuffer.getInt(20)
        val previousTerminalFlags = terminalFlags
        val nextHistoryRows = damageBuffer.getInt(32)
        val nextViewportOffset = damageBuffer.getInt(36)
        val nextCursorColor = damageBuffer.getInt(48)
        if (
            nextRows !in MIN_ROWS..MAX_ROWS ||
            nextColumns !in MIN_COLUMNS..MAX_COLUMNS ||
            nextCursorRow !in 0 until nextRows ||
            nextCursorColumn !in 0 until nextColumns ||
            dirtyStart !in 0..nextRows ||
            dirtyEnd !in dirtyStart..nextRows ||
            nextHistoryRows < 0 ||
            nextViewportOffset !in 0..nextHistoryRows ||
            damageBuffer.getLong(40) <= 0L ||
            nextCursorColor and RGB_MASK != nextCursorColor
        ) {
            return false
        }
        val expected =
            DAMAGE_HEADER_SIZE +
                (dirtyEnd - dirtyStart) * nextColumns * DAMAGE_CELL_SIZE
        if (length != expected) {
            return false
        }
        val nextTerminalRevision = damageBuffer.getLong(24)
        val contentChanged =
            terminalRevision == Long.MIN_VALUE ||
                nextTerminalRevision != terminalRevision ||
                dirtyStart < dirtyEnd
        val nextHistoryOriginEpoch = damageBuffer.getLong(40)
        if (terminalRevision != Long.MIN_VALUE && nextTerminalRevision < terminalRevision) {
            return false
        }
        if (hasSelection()) {
            val selectedEndRow = selectionEnd / columns
            val changedSelectedScreen =
                nextTerminalRevision > terminalRevision &&
                    selectedEndRow >= historyRows.toLong()
            if (
                nextHistoryOriginEpoch != selectionOriginEpoch ||
                nextRows != rows ||
                nextColumns != columns ||
                nextHistoryRows < historyRows ||
                changedSelectedScreen
            ) {
                clearSelection()
            }
        }
        if (viewportOffset > 0 && nextHistoryRows > historyRows) {
            val anchoredOffset =
                (nextViewportOffset.toLong() + nextHistoryRows - historyRows)
                    .coerceAtMost(nextHistoryRows.toLong())
                    .toInt()
            if (anchoredOffset != nextViewportOffset) {
                historyRows = nextHistoryRows
                historyOriginEpoch = nextHistoryOriginEpoch
                viewportOffset = anchoredOffset
                needsFullSnapshot = true
                return true
            }
        }
        val previousCursorRow = cursorRow
        val cursorPresentationChanged =
            nextCursorRow != cursorRow ||
                nextCursorColumn != cursorColumn ||
                nextCursorColor != cursorColor ||
                (nextTerminalFlags and CURSOR_PRESENTATION_FLAGS) !=
                (terminalFlags and CURSOR_PRESENTATION_FLAGS)
        if (nextRows != rows || nextColumns != columns) {
            rows = nextRows
            columns = nextColumns
            contentDescription =
                context.getString(
                    R.string.terminal_accessibility_dimensions,
                    columns,
                    rows,
                )
            glyphCodepoints = IntArray(rows * columns * MAX_GRAPHEME_CODEPOINTS)
            glyphLengths = ByteArray(rows * columns) { 1 }
            glyphWidths = ByteArray(rows * columns) { 1 }
            for (cell in 0 until rows * columns) {
                glyphCodepoints[cell * MAX_GRAPHEME_CODEPOINTS] = ' '.code
            }
            styledForegroundColors = IntArray(rows * columns) { DEFAULT_FOREGROUND }
            backgroundColors = IntArray(rows * columns) { DEFAULT_BACKGROUND }
            blinkingRows = BooleanArray(rows)
            blinkingRowCount = 0
            rowNodes = Array(rows) { row -> RenderNode("terminal-row-$row") }
            positionRowNodes()
        }
        cursorRow = nextCursorRow
        cursorColumn = nextCursorColumn
        cursorColor = nextCursorColor
        terminalFlags = nextTerminalFlags
        cursorVisible = terminalFlags and CURSOR_VISIBLE_FLAG != 0
        terminalRevision = nextTerminalRevision
        historyRows = nextHistoryRows
        historyOriginEpoch = nextHistoryOriginEpoch
        viewportOffset = nextViewportOffset
        var offset = DAMAGE_HEADER_SIZE
        for (row in dirtyStart until dirtyEnd) {
            val rowStart = row * columns
            var rowBlinks = false
            for (column in 0 until columns) {
                val cell = rowStart + column
                val glyphStart = cell * MAX_GRAPHEME_CODEPOINTS
                val graphemeLength =
                    (damageBuffer.get(offset + 74).toInt() and 0xff)
                        .coerceIn(0, MAX_GRAPHEME_CODEPOINTS)
                for (codepointIndex in 0 until MAX_GRAPHEME_CODEPOINTS) {
                    val codepoint = damageBuffer.getInt(offset + codepointIndex * 4)
                    glyphCodepoints[glyphStart + codepointIndex] =
                        if (
                            codepointIndex < graphemeLength &&
                            codepoint in 0..MAX_UNICODE_CODEPOINT &&
                            codepoint !in SURROGATE_RANGE
                        ) {
                            codepoint
                        } else if (codepointIndex < graphemeLength) {
                            REPLACEMENT_CODEPOINT
                        } else {
                            0
                        }
                }
                glyphLengths[cell] = graphemeLength.toByte()
                val glyphWidth =
                    (damageBuffer.get(offset + 73).toInt() and 0xff)
                        .coerceIn(0, 2)
                val attributes = damageBuffer.get(offset + 72).toInt() and 0xff
                styledForegroundColors[cell] =
                    damageBuffer.getInt(offset + 64) or
                        ((attributes and ATTRIBUTE_STYLE_MASK) shl ATTRIBUTE_SHIFT)
                backgroundColors[cell] = damageBuffer.getInt(offset + 68)
                if (attributes and ATTRIBUTE_BLINK != 0) {
                    glyphWidths[cell] = (glyphWidth or GLYPH_BLINK_FLAG).toByte()
                    rowBlinks = true
                } else {
                    glyphWidths[cell] = glyphWidth.toByte()
                }
                offset += DAMAGE_CELL_SIZE
            }
            if (blinkingRows[row] != rowBlinks) {
                blinkingRows[row] = rowBlinks
                blinkingRowCount += if (rowBlinks) 1 else -1
            }
        }
        val cursorContentChanged =
            cursorRow in dirtyStart until dirtyEnd
        if (cursorPresentationChanged || cursorContentChanged) {
            cancelBlinkAnimation()
            cursorBlinkPhaseVisible = true
        }
        if (dirtyStart < dirtyEnd) {
            for (row in dirtyStart until dirtyEnd) {
                recordRow(row)
            }
            invalidate()
        }
        if (cursorPresentationChanged) {
            if (previousCursorRow !in dirtyStart until dirtyEnd) {
                recordRow(previousCursorRow)
            }
            if (cursorRow !in dirtyStart until dirtyEnd) {
                recordRow(cursorRow)
            }
            invalidate()
        }
        if (
            (previousTerminalFlags xor terminalFlags) and
                (MOUSE_TRACKING_MASK or MOUSE_ENCODING_MASK) != 0
        ) {
            resetMouseGesture()
            if (terminalMouseReportingActive()) {
                clearSelection()
            }
        }
        if (
            (previousTerminalFlags xor terminalFlags) and
                FOCUS_REPORTING_FLAG != 0
        ) {
            terminalFocusStateKnown =
                terminalFlags and FOCUS_REPORTING_FLAG != 0
            terminalFocused = terminalHasInputFocus()
        }
        updateBlinkAnimationScheduling()
        if (contentChanged) {
            scheduleTerminalAccessibilityEvent(textChanged = true)
        }
        return true
    }

    private fun recordRow(row: Int) {
        if (row !in rowNodes.indices || width <= 0) {
            return
        }
        val recordingHeight = ceil(cellHeight.toDouble()).toInt().coerceAtLeast(1)
        val canvas = rowNodes[row].beginRecording(width, recordingHeight)
        canvas.drawColor(TERMINAL_BACKGROUND)
        val start = row * columns
        val baseline = -textPaint.fontMetrics.ascent
        var runStart = 0
        while (runStart < columns) {
            val styledForeground = styledForegroundColors[start + runStart]
            val backgroundColor = backgroundColors[start + runStart]
            val blinking = cellBlinks(start + runStart)
            val attributes = styledForeground ushr ATTRIBUTE_SHIFT
            var runEnd = runStart + 1
            while (
                runEnd < columns &&
                styledForegroundColors[start + runEnd] == styledForeground &&
                backgroundColors[start + runEnd] == backgroundColor &&
                cellBlinks(start + runEnd) == blinking
            ) {
                runEnd++
            }
            val blinkHidden = blinking && !textBlinkPhaseVisible
            val foregroundColor = styledForeground and COLOR_VALUE_MASK
            val inverse =
                (attributes and ATTRIBUTE_INVERSE != 0) xor
                    (terminalFlags and REVERSE_SCREEN_FLAG != 0)
            val baseForeground =
                if (inverse) {
                    resolveTerminalColor(backgroundColor)
                } else {
                    resolveTerminalColor(foregroundColor)
                }
            val background =
                if (inverse) {
                    resolveTerminalColor(foregroundColor)
                } else {
                    resolveTerminalColor(backgroundColor)
                }
            val foreground =
                when {
                    attributes and ATTRIBUTE_HIDDEN != 0 -> background
                    attributes and ATTRIBUTE_FAINT != 0 ->
                        blendTerminalColors(baseForeground, background)
                    else -> baseForeground
                }
            if (background != TERMINAL_BACKGROUND) {
                backgroundPaint.color = background
                canvas.drawRect(
                    CONTENT_PADDING + runStart * cellWidth,
                    0f,
                    CONTENT_PADDING + runEnd * cellWidth,
                    cellHeight,
                    backgroundPaint,
                )
            }
            textPaint.color = foreground
            textPaint.isFakeBoldText = attributes and ATTRIBUTE_BOLD != 0
            textPaint.textSkewX =
                if (attributes and ATTRIBUTE_ITALIC != 0) ITALIC_TEXT_SKEW else 0f
            textPaint.isStrikeThruText =
                !blinkHidden && attributes and ATTRIBUTE_STRIKE != 0
            if (!blinkHidden) {
                for (column in runStart until runEnd) {
                    if (isBlankGlyph(start + column)) {
                        continue
                    }
                    val glyphCount = packGlyphRun(start + column, start + column + 1)
                    canvas.drawText(
                        rowGlyphScratch,
                        0,
                        glyphCount,
                        CONTENT_PADDING + column * cellWidth,
                        baseline,
                        textPaint,
                    )
                }
            }
            if (!blinkHidden && attributes and ATTRIBUTE_UNDERLINE != 0) {
                backgroundPaint.color = foreground
                canvas.drawRect(
                    CONTENT_PADDING + runStart * cellWidth,
                    cellHeight - UNDERLINE_HEIGHT,
                    CONTENT_PADDING + runEnd * cellWidth,
                    cellHeight,
                    backgroundPaint,
                )
            }
            runStart = runEnd
        }
        if (hasSelection()) {
            val documentRow = visibleDocumentRow(row)
            val rowFirst = documentRow * columns
            val rowLast = rowFirst + columns - 1
            if (selectionStart <= rowLast && selectionEnd >= rowFirst) {
                val firstColumn = (selectionStart - rowFirst).coerceAtLeast(0).toInt()
                val lastColumn =
                    (selectionEnd - rowFirst).coerceAtMost((columns - 1).toLong()).toInt()
                backgroundPaint.color = SELECTION_OVERLAY
                canvas.drawRect(
                    CONTENT_PADDING + firstColumn * cellWidth,
                    0f,
                    CONTENT_PADDING + (lastColumn + 1) * cellWidth,
                    cellHeight,
                    backgroundPaint,
                )
            }
        }
        if (
            cursorVisible &&
            (terminalFlags and CURSOR_BLINK_FLAG == 0 || cursorBlinkPhaseVisible) &&
            row == cursorRow &&
            cursorColumn in 0 until columns
        ) {
            val left = CONTENT_PADDING + cursorColumn * cellWidth
            when (
                (terminalFlags and CURSOR_STYLE_MASK) ushr
                    CURSOR_STYLE_SHIFT
            ) {
                CURSOR_STYLE_BLOCK -> {
                    backgroundPaint.color = CURSOR_BLOCK_ALPHA or cursorColor
                    canvas.drawRect(
                        left,
                        0f,
                        left + cellWidth,
                        cellHeight,
                        backgroundPaint,
                    )
                }
                CURSOR_STYLE_BAR -> {
                    backgroundPaint.color = OPAQUE_ALPHA or cursorColor
                    canvas.drawRect(
                        left,
                        0f,
                        left + cursorStrokeWidth.coerceAtMost(cellWidth),
                        cellHeight,
                        backgroundPaint,
                    )
                }
                else -> {
                    backgroundPaint.color = OPAQUE_ALPHA or cursorColor
                    canvas.drawRect(
                        left,
                        (cellHeight - cursorStrokeWidth).coerceAtLeast(0f),
                        left + cellWidth,
                        cellHeight,
                        backgroundPaint,
                    )
                }
            }
        }
        rowNodes[row].endRecording()
    }

    private fun canAnimateBlink(): Boolean =
        ValueAnimator.areAnimatorsEnabled() &&
        isAttachedToWindow &&
            hasWindowFocus() &&
            windowVisibility == VISIBLE &&
            isShown

    private fun shouldBlinkCursor(): Boolean =
        canAnimateBlink() &&
            cursorVisible &&
            terminalFlags and CURSOR_BLINK_FLAG != 0

    private fun shouldBlinkText(): Boolean =
        canAnimateBlink() && blinkingRowCount > 0

    private fun scheduleBlinkAnimation() {
        if (!blinkAnimationPosted && (shouldBlinkCursor() || shouldBlinkText())) {
            blinkAnimationPosted = true
            postDelayed(blinkAnimationRunnable, BLINK_INTERVAL_MILLIS)
        }
    }

    private fun cancelBlinkAnimation() {
        if (blinkAnimationPosted) {
            removeCallbacks(blinkAnimationRunnable)
            blinkAnimationPosted = false
        }
    }

    private fun stopBlinkAnimation(revealContent: Boolean) {
        cancelBlinkAnimation()
        var needsRedraw = false
        var cursorRowRecorded = false
        if (revealContent && !cursorBlinkPhaseVisible) {
            cursorBlinkPhaseVisible = true
            recordRow(cursorRow)
            needsRedraw = true
            cursorRowRecorded = true
        }
        if (revealContent && !textBlinkPhaseVisible) {
            textBlinkPhaseVisible = true
            for (row in blinkingRows.indices) {
                if (blinkingRows[row] && (row != cursorRow || !cursorRowRecorded)) {
                    recordRow(row)
                }
            }
            needsRedraw = true
        }
        if (needsRedraw) {
            invalidate()
        }
    }

    private fun restartBlinkAnimation() {
        stopBlinkAnimation(revealContent = true)
        scheduleBlinkAnimation()
    }

    private fun updateBlinkAnimationScheduling() {
        if (shouldBlinkCursor() || shouldBlinkText()) {
            scheduleBlinkAnimation()
        } else {
            stopBlinkAnimation(revealContent = true)
        }
    }

    private fun handleTerminalMouseEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = terminalMousePosition(event.x, event.y) ?: return
                requestFocus()
                clearSelection()
                mousePointerId = event.getPointerId(0)
                mousePressedButton = mouseButton(event)
                if (
                    sendTerminalMouseReport(
                        button = mousePressedButton,
                        column = position.column,
                        row = position.row,
                        pixelX = position.pixelX,
                        pixelY = position.pixelY,
                        modifiers = mouseModifiers(event),
                        release = false,
                        motion = false,
                        eventTimeMillis = event.eventTime,
                    )
                ) {
                    rememberMousePosition(position)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val tracking = terminalMouseTrackingMode()
                if (
                    tracking != MOUSE_TRACKING_BUTTON_EVENT &&
                    tracking != MOUSE_TRACKING_ANY_EVENT
                ) {
                    return
                }
                val pointerIndex = event.findPointerIndex(mousePointerId)
                if (pointerIndex < 0) {
                    return
                }
                val position =
                    terminalMousePosition(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    ) ?: return
                if (!mousePositionChanged(position)) {
                    return
                }
                if (
                    sendTerminalMouseReport(
                        button = mousePressedButton,
                        column = position.column,
                        row = position.row,
                        pixelX = position.pixelX,
                        pixelY = position.pixelY,
                        modifiers = mouseModifiers(event),
                        release = false,
                        motion = true,
                        eventTimeMillis = event.eventTime,
                    )
                ) {
                    rememberMousePosition(position)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (
                    event.getPointerId(event.actionIndex) != mousePointerId
                ) {
                    return
                }
                val position =
                    terminalMousePosition(
                        event.getX(event.actionIndex),
                        event.getY(event.actionIndex),
                    )
                if (
                    terminalMouseTrackingMode() != MOUSE_TRACKING_X10 &&
                    (position != null || lastMouseColumn != NO_MOUSE_POSITION)
                ) {
                    sendTerminalMouseReport(
                        button = mousePressedButton,
                        column = position?.column ?: lastMouseColumn,
                        row = position?.row ?: lastMouseRow,
                        pixelX = position?.pixelX ?: lastMousePixelX,
                        pixelY = position?.pixelY ?: lastMousePixelY,
                        modifiers = mouseModifiers(event),
                        release = true,
                        motion = false,
                        eventTimeMillis = event.eventTime,
                    )
                }
                resetMouseGesture()
                if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                ) {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (
                    terminalMouseTrackingMode() != MOUSE_TRACKING_X10 &&
                    lastMouseColumn != NO_MOUSE_POSITION
                ) {
                    sendTerminalMouseReport(
                        button = mousePressedButton,
                        column = lastMouseColumn,
                        row = lastMouseRow,
                        pixelX = lastMousePixelX,
                        pixelY = lastMousePixelY,
                        modifiers = mouseModifiers(event),
                        release = true,
                        motion = false,
                        eventTimeMillis = event.eventTime,
                    )
                }
                resetMouseGesture()
            }
        }
    }

    private fun terminalMouseReportingActive(): Boolean =
        viewportOffset == 0 &&
            rows != 0 &&
            columns != 0 &&
            terminalMouseTrackingMode() != MOUSE_TRACKING_NONE

    private fun terminalMouseTrackingMode(): Int =
        (terminalFlags and MOUSE_TRACKING_MASK) ushr
            MOUSE_TRACKING_SHIFT

    private fun terminalMouseEncoding(): Int =
        (terminalFlags and MOUSE_ENCODING_MASK) ushr
            MOUSE_ENCODING_SHIFT

    private fun terminalMousePosition(
        x: Float,
        y: Float,
    ): MousePosition? {
        if (
            x < CONTENT_PADDING ||
            y < CONTENT_PADDING ||
            x >= CONTENT_PADDING + columns * cellWidth ||
            y >= CONTENT_PADDING + rows * cellHeight
        ) {
            return null
        }
        mousePositionScratch.column =
            ((x - CONTENT_PADDING) / cellWidth).toInt() + 1
        mousePositionScratch.row =
            ((y - CONTENT_PADDING) / cellHeight).toInt() + 1
        mousePositionScratch.pixelX =
            x.roundToInt().coerceIn(0, width.coerceAtLeast(1) - 1) + 1
        mousePositionScratch.pixelY =
            y.roundToInt().coerceIn(0, height.coerceAtLeast(1) - 1) + 1
        return mousePositionScratch
    }

    private fun mouseButton(event: MotionEvent): Int =
        when {
            event.actionButton == MotionEvent.BUTTON_TERTIARY ||
                event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 ->
                MOUSE_BUTTON_MIDDLE
            event.actionButton == MotionEvent.BUTTON_SECONDARY ||
                event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 ->
                MOUSE_BUTTON_SECONDARY
            else -> MOUSE_BUTTON_PRIMARY
        }

    private fun mouseModifiers(event: MotionEvent): Int {
        var result = 0
        if (event.metaState and KeyEvent.META_SHIFT_ON != 0) {
            result = result or MOUSE_MODIFIER_SHIFT
        }
        if (
            event.metaState and
                (KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0
        ) {
            result = result or MOUSE_MODIFIER_META
        }
        if (event.metaState and KeyEvent.META_CTRL_ON != 0) {
            result = result or MOUSE_MODIFIER_CONTROL
        }
        return result
    }

    private fun mousePositionChanged(position: MousePosition): Boolean =
        if (
            terminalMouseEncoding() ==
            TerminalMouseEncoder.ENCODING_SGR_PIXELS
        ) {
            position.pixelX != lastMousePixelX ||
                position.pixelY != lastMousePixelY
        } else {
            position.column != lastMouseColumn ||
                position.row != lastMouseRow
        }

    private fun rememberMousePosition(position: MousePosition) {
        lastMouseColumn = position.column
        lastMouseRow = position.row
        lastMousePixelX = position.pixelX
        lastMousePixelY = position.pixelY
    }

    private fun resetMouseGesture() {
        mousePointerId = NO_MOUSE_POINTER
        mouseLocalSelectionOverride = false
        mousePressedButton = MOUSE_BUTTON_PRIMARY
        lastMouseColumn = NO_MOUSE_POSITION
        lastMouseRow = NO_MOUSE_POSITION
        lastMousePixelX = NO_MOUSE_POSITION
        lastMousePixelY = NO_MOUSE_POSITION
    }

    private fun sendTerminalMouseReport(
        button: Int,
        column: Int,
        row: Int,
        pixelX: Int,
        pixelY: Int,
        modifiers: Int,
        release: Boolean,
        motion: Boolean,
        eventTimeMillis: Long,
    ): Boolean {
        val encoding = terminalMouseEncoding()
        val length =
            TerminalMouseEncoder.encode(
                destination = terminalInputBytes,
                encoding = encoding,
                button = button,
                x =
                    if (
                        encoding ==
                        TerminalMouseEncoder.ENCODING_SGR_PIXELS
                    ) {
                        pixelX
                    } else {
                        column
                    },
                y =
                    if (
                        encoding ==
                        TerminalMouseEncoder.ENCODING_SGR_PIXELS
                    ) {
                        pixelY
                    } else {
                        row
                    },
                modifiers = modifiers,
                release = release,
                motion = motion,
            )
        if (length == 0) {
            return false
        }
        val accepted =
            runtimeBinder?.submitTerminalInput(
                terminalInputBytes,
                length,
            ) == true
        if (accepted) {
            PerformanceMetrics.noteTerminalInput(eventTimeMillis)
        }
        return accepted
    }

    private fun terminalHasInputFocus(): Boolean =
        isAttachedToWindow &&
            hasFocus() &&
            hasWindowFocus() &&
            windowVisibility == VISIBLE &&
            isShown

    private fun reportTerminalFocusChange() {
        if (terminalFlags and FOCUS_REPORTING_FLAG == 0) {
            terminalFocusStateKnown = false
            return
        }
        val focused = terminalHasInputFocus()
        if (!terminalFocusStateKnown) {
            terminalFocusStateKnown = true
            terminalFocused = focused
            return
        }
        if (terminalFocused == focused) {
            return
        }
        terminalFocused = focused
        val sequence = if (focused) FOCUS_IN else FOCUS_OUT
        if (
            runtimeBinder?.submitTerminalInput(
                sequence,
                sequence.size,
            ) == true
        ) {
            PerformanceMetrics.noteTerminalInput(SystemClock.uptimeMillis())
        }
    }

    private fun packGlyphRun(
        start: Int,
        end: Int,
    ): Int {
        var output = 0
        for (cell in start until end) {
            if (terminalCellWidth(cell) == 0) {
                continue
            }
            val glyphStart = cell * MAX_GRAPHEME_CODEPOINTS
            val glyphLength = glyphLengths[cell].toInt() and 0xff
            for (index in 0 until glyphLength) {
                val codepoint = glyphCodepoints[glyphStart + index]
                if (codepoint <= Char.MAX_VALUE.code) {
                    rowGlyphScratch[output++] = codepoint.toChar()
                } else {
                    val supplementary = codepoint - 0x10000
                    rowGlyphScratch[output++] = ((supplementary ushr 10) + 0xd800).toChar()
                    rowGlyphScratch[output++] = ((supplementary and 0x3ff) + 0xdc00).toChar()
                }
            }
        }
        return output
    }

    private fun drawComposingText(canvas: Canvas) {
        if (
            composingText.isEmpty() ||
            cursorRow !in 0 until rows ||
            cursorColumn !in 0 until columns
        ) {
            return
        }
        val left = CONTENT_PADDING + cursorColumn * cellWidth
        val top = CONTENT_PADDING + cursorRow * cellHeight
        val right =
            (left + textPaint.measureText(composingText))
                .coerceAtMost(width.toFloat())
        backgroundPaint.color = COMPOSING_BACKGROUND
        canvas.drawRect(left, top, right, top + cellHeight, backgroundPaint)
        textPaint.color = ANSI_COLORS[7]
        textPaint.isFakeBoldText = false
        canvas.drawText(composingText, left, top - textPaint.fontMetrics.ascent, textPaint)
        backgroundPaint.color = OPAQUE_ALPHA or cursorColor
        canvas.drawRect(
            left,
            top + cellHeight - UNDERLINE_HEIGHT,
            right,
            top + cellHeight,
            backgroundPaint,
        )
    }

    private fun sendText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) {
            return true
        }
        var input = 0
        var output = 0
        while (input < text.length) {
            val first = text[input++]
            if (first == '\n') {
                val required =
                    if (terminalFlags and NEW_LINE_MODE_FLAG != 0) 2 else 1
                if (output + required > terminalInputBytes.size) {
                    return false
                }
                terminalInputBytes[output++] = CARRIAGE_RETURN_BYTE
                if (required == 2) {
                    terminalInputBytes[output++] = LINE_FEED_BYTE
                }
                continue
            }
            val codepoint =
                if (first.isHighSurrogate()) {
                    if (input < text.length && text[input].isLowSurrogate()) {
                        Character.toCodePoint(first, text[input++])
                    } else {
                        REPLACEMENT_CHARACTER.code
                    }
                } else if (first.isLowSurrogate()) {
                    REPLACEMENT_CHARACTER.code
                } else {
                    first.code
                }
            val required = encodedLength(codepoint)
            if (output + required > terminalInputBytes.size) {
                return false
            }
            output += TerminalKeyEncoder.encodeCodepoint(codepoint, terminalInputBytes, output)
        }
        return output == 0 || submitTerminalInput(output)
    }

    private fun pasteClipboard(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) {
            return false
        }
        val text = clip.getItemAt(0).coerceToText(context) ?: return false
        return sendClipboardText(text)
    }

    private fun copySelection(): Boolean {
        if (!hasSelection()) {
            return false
        }
        val firstRow = (selectionStart / columns).toInt()
        val lastRow = (selectionEnd / columns).toInt()
        val text =
            runtimeBinder?.copySharedShellTerminalSelection(
                selectionOriginEpoch,
                firstRow,
                (selectionStart % columns).toInt(),
                lastRow,
                (selectionEnd % columns).toInt(),
            ) ?: return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.app_name), text),
        )
        clearSelection()
        return true
    }

    private fun startSelection(
        x: Float,
        y: Float,
    ) {
        val cell = cellAtPosition(x, y) ?: return
        val rowStart = cell / columns * columns
        val rowEnd = rowStart + columns
        var first = cell
        var last = cell
        if (!isBlankGlyph(cell)) {
            while (first > rowStart && !isBlankGlyph(first - 1)) {
                first--
            }
            while (last + 1 < rowEnd && !isBlankGlyph(last + 1)) {
                last++
            }
        }
        val documentRow = visibleDocumentRow(cell / columns)
        val documentRowStart = documentRow * columns
        selectionStart = documentRowStart + first % columns
        selectionEnd = documentRowStart + last % columns
        selectionInitialStart = selectionStart
        selectionInitialEnd = selectionEnd
        selectionOriginEpoch = historyOriginEpoch
        selectionDragEndpoint = SELECTION_DRAG_WORD
        selectionDragging = true
        recordSelectionRows()
    }

    private fun extendSelection(
        x: Float,
        y: Float,
    ) {
        val cell = documentCellAtPosition(x, y) ?: return
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        when (selectionDragEndpoint) {
            SELECTION_DRAG_WORD -> {
                when {
                    cell < selectionInitialStart -> {
                        selectionStart = cell
                        selectionEnd = selectionInitialEnd
                    }
                    cell > selectionInitialEnd -> {
                        selectionStart = selectionInitialStart
                        selectionEnd = cell
                    }
                    else -> {
                        selectionStart = selectionInitialStart
                        selectionEnd = selectionInitialEnd
                    }
                }
            }
            SELECTION_DRAG_START -> {
                if (cell <= selectionEnd) {
                    selectionStart = cell
                } else {
                    selectionStart = selectionEnd
                    selectionEnd = cell
                    selectionDragEndpoint = SELECTION_DRAG_END
                }
            }
            SELECTION_DRAG_END -> {
                if (cell >= selectionStart) {
                    selectionEnd = cell
                } else {
                    selectionEnd = selectionStart
                    selectionStart = cell
                    selectionDragEndpoint = SELECTION_DRAG_START
                }
            }
        }
        if (selectionStart == oldStart && selectionEnd == oldEnd) {
            return
        }
        recordSelectionRows()
    }

    private fun beginSelectionHandleDrag(
        x: Float,
        y: Float,
    ): Boolean {
        if (!hasSelection()) {
            return false
        }
        selectionDragEndpoint =
            when {
                isNearSelectionHandle(selectionStart, true, x, y) -> SELECTION_DRAG_START
                isNearSelectionHandle(selectionEnd, false, x, y) -> SELECTION_DRAG_END
                else -> return false
            }
        selectionInitialStart = selectionStart
        selectionInitialEnd = selectionEnd
        selectionDragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        if (!hasSelection()) {
            return
        }
        backgroundPaint.color = SELECTION_HANDLE_COLOR
        drawSelectionHandle(canvas, selectionStart, true)
        if (selectionEnd != selectionStart) {
            drawSelectionHandle(canvas, selectionEnd, false)
        }
    }

    private fun drawSelectionHandle(
        canvas: Canvas,
        cell: Long,
        start: Boolean,
    ) {
        val documentRow = cell / columns
        val visibleRow = documentRow - firstVisibleDocumentRow()
        if (visibleRow !in 0 until rows.toLong()) {
            return
        }
        val column = (cell % columns).toInt()
        val visibleCell = visibleRow.toInt() * columns + column
        val cellSpan =
            if (!start && terminalCellWidth(visibleCell) == 2) {
                2
            } else {
                1
            }
        val x =
            (
                CONTENT_PADDING +
                    (column + if (start) 0 else cellSpan) * cellWidth
            ).coerceIn(
                selectionHandleRadius,
                (width - selectionHandleRadius).coerceAtLeast(selectionHandleRadius),
            )
        val y =
            CONTENT_PADDING +
                (visibleRow + 1).toFloat() * cellHeight -
                selectionHandleRadius
        canvas.drawCircle(x, y, selectionHandleRadius, backgroundPaint)
    }

    private fun isNearSelectionHandle(
        cell: Long,
        start: Boolean,
        x: Float,
        y: Float,
    ): Boolean {
        val documentRow = cell / columns
        val visibleRow = documentRow - firstVisibleDocumentRow()
        if (visibleRow !in 0 until rows.toLong()) {
            return false
        }
        val column = (cell % columns).toInt()
        val visibleCell = visibleRow.toInt() * columns + column
        val cellSpan =
            if (!start && terminalCellWidth(visibleCell) == 2) {
                2
            } else {
                1
            }
        val handleX =
            (
                CONTENT_PADDING +
                    (column + if (start) 0 else cellSpan) * cellWidth
            ).coerceIn(
                selectionHandleRadius,
                (width - selectionHandleRadius).coerceAtLeast(selectionHandleRadius),
            )
        val handleY =
            CONTENT_PADDING +
                (visibleRow + 1).toFloat() * cellHeight -
                selectionHandleRadius
        val deltaX = x - handleX
        val deltaY = y - handleY
        return deltaX * deltaX + deltaY * deltaY <=
            selectionHandleTouchRadius * selectionHandleTouchRadius
    }

    private fun updateSelectionAutoScroll(
        x: Float,
        y: Float,
    ) {
        val direction =
            when {
                y < CONTENT_PADDING && viewportOffset < historyRows -> 1
                y > height - CONTENT_PADDING && viewportOffset > 0 -> -1
                else -> 0
            }
        selectionAutoScrollX = x
        if (direction == selectionAutoScrollDirection) {
            return
        }
        removeCallbacks(selectionAutoScrollRunnable)
        selectionAutoScrollDirection = direction
        selectionAutoScrollFrame = 0
        if (direction != 0) {
            postOnAnimation(selectionAutoScrollRunnable)
        }
    }

    private fun runSelectionAutoScroll() {
        val direction = selectionAutoScrollDirection
        if (!selectionDragging || direction == 0) {
            return
        }
        selectionAutoScrollFrame++
        if (selectionAutoScrollFrame < SELECTION_AUTO_SCROLL_FRAME_INTERVAL) {
            postOnAnimation(selectionAutoScrollRunnable)
            return
        }
        selectionAutoScrollFrame = 0
        val oldOffset = viewportOffset
        setViewportOffset(viewportOffset + direction)
        if (viewportOffset == oldOffset) {
            stopSelectionAutoScroll()
            return
        }
        extendSelection(
            selectionAutoScrollX,
            if (direction > 0) CONTENT_PADDING else height - CONTENT_PADDING,
        )
        postOnAnimation(selectionAutoScrollRunnable)
    }

    private fun stopSelectionAutoScroll() {
        selectionAutoScrollDirection = 0
        selectionAutoScrollFrame = 0
        removeCallbacks(selectionAutoScrollRunnable)
    }

    private fun cellAtPosition(
        x: Float,
        y: Float,
    ): Int? {
        if (rows == 0 || columns == 0) {
            return null
        }
        val row =
            ((y - CONTENT_PADDING) / cellHeight)
                .toInt()
                .coerceIn(0, rows - 1)
        var column =
            ((x - CONTENT_PADDING) / cellWidth)
                .toInt()
                .coerceIn(0, columns - 1)
        val rowStart = row * columns
        if (terminalCellWidth(rowStart + column) == 0 && column > 0) {
            column--
        }
        return rowStart + column
    }

    private fun documentCellAtPosition(
        x: Float,
        y: Float,
    ): Long? {
        val visibleCell = cellAtPosition(x, y) ?: return null
        return visibleDocumentRow(visibleCell / columns) * columns +
            visibleCell % columns
    }

    private fun firstVisibleDocumentRow(): Long =
        historyRows.toLong() - viewportOffset

    private fun visibleDocumentRow(row: Int): Long =
        firstVisibleDocumentRow() + row

    private fun hasSelection(): Boolean =
        selectionStart != NO_SELECTION &&
            selectionEnd != NO_SELECTION &&
            rows != 0 &&
            columns != 0 &&
            selectionOriginEpoch == historyOriginEpoch &&
            selectionStart <= selectionEnd &&
            selectionEnd < (historyRows.toLong() + rows) * columns

    private fun clearSelection() {
        if (!hasSelection() && !selectionDragging) {
            return
        }
        stopSelectionAutoScroll()
        selectionStart = NO_SELECTION
        selectionEnd = NO_SELECTION
        selectionInitialStart = NO_SELECTION
        selectionInitialEnd = NO_SELECTION
        selectionOriginEpoch = 0L
        selectionDragEndpoint = SELECTION_DRAG_NONE
        selectionDragging = false
        recordSelectionRows()
    }

    private fun recordSelectionRows() {
        for (row in rowNodes.indices) {
            recordRow(row)
        }
        invalidate()
    }

    private fun setTerminalTextSize(
        requestedSp: Int,
        automatic: Boolean,
        persist: Boolean = true,
    ): Boolean {
        clearSelection()
        val nextSp = requestedSp.coerceIn(MIN_TERMINAL_TEXT_SP, MAX_TERMINAL_TEXT_SP)
        if (nextSp == terminalTextSp && automatic == automaticTextSize) {
            invalidate()
            return true
        }
        terminalTextSp = nextSp
        automaticTextSize = automatic
        if (persist) {
            ArchphenePreferences.setTerminalTextSp(
                if (automatic) AUTOMATIC_TEXT_SIZE else nextSp,
            )
        }
        textPaint.textSize =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                nextSp.toFloat(),
                resources.displayMetrics,
            )
        cellWidth = ceil(textPaint.measureText("M").toDouble()).toFloat().coerceAtLeast(1f)
        cellHeight =
            ceil((textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toDouble())
                .toFloat()
                .coerceAtLeast(1f)
        positionRowNodes()
        for (row in rowNodes.indices) {
            recordRow(row)
        }
        publishTerminalSize(width, height)
        invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        return true
    }

    internal fun applyPersistedTextSize(persistedTextSp: Int) {
        setTerminalTextSize(
            persistedTextSp
                .takeUnless { it == AUTOMATIC_TEXT_SIZE }
                ?.coerceIn(MIN_TERMINAL_TEXT_SP, MAX_TERMINAL_TEXT_SP)
                ?: AUTOMATIC_TERMINAL_TEXT_SP,
            persistedTextSp == AUTOMATIC_TEXT_SIZE,
            persist = false,
        )
    }

    private fun sendClipboardText(text: CharSequence): Boolean {
        if (text.length > MAX_CLIPBOARD_CHARACTERS) {
            return false
        }
        val bracketed = terminalFlags and BRACKETED_PASTE_FLAG != 0
        var output = 0
        if (bracketed) {
            System.arraycopy(
                BRACKETED_PASTE_START,
                0,
                terminalInputBytes,
                output,
                BRACKETED_PASTE_START.size,
            )
            output += BRACKETED_PASTE_START.size
        }
        var input = 0
        val reserved = if (bracketed) BRACKETED_PASTE_END.size else 0
        while (input < text.length) {
            val first = text[input++]
            val codepoint =
                if (first.isHighSurrogate()) {
                    if (input < text.length && text[input].isLowSurrogate()) {
                        Character.toCodePoint(first, text[input++])
                    } else {
                        REPLACEMENT_CHARACTER.code
                    }
                } else if (first.isLowSurrogate()) {
                    REPLACEMENT_CHARACTER.code
                } else {
                    first.code
                }
            val required = encodedLength(codepoint)
            if (output + required + reserved > terminalInputBytes.size) {
                return false
            }
            output += TerminalKeyEncoder.encodeCodepoint(codepoint, terminalInputBytes, output)
        }
        if (bracketed) {
            System.arraycopy(
                BRACKETED_PASTE_END,
                0,
                terminalInputBytes,
                output,
                BRACKETED_PASTE_END.size,
            )
            output += BRACKETED_PASTE_END.size
        }
        return output == 0 || submitTerminalInput(output)
    }

    private fun encodedTextLength(text: CharSequence): Int {
        var input = 0
        var output = 0
        while (input < text.length) {
            val first = text[input++]
            if (first == '\n') {
                output += if (terminalFlags and NEW_LINE_MODE_FLAG != 0) 2 else 1
                if (output > terminalInputBytes.size) {
                    return -1
                }
                continue
            }
            val codepoint =
                if (first.isHighSurrogate()) {
                    if (input < text.length && text[input].isLowSurrogate()) {
                        Character.toCodePoint(first, text[input++])
                    } else {
                        REPLACEMENT_CHARACTER.code
                    }
                } else if (first.isLowSurrogate()) {
                    REPLACEMENT_CHARACTER.code
                } else {
                    first.code
                }
            output += encodedLength(codepoint)
            if (output > terminalInputBytes.size) {
                return -1
            }
        }
        return output
    }

    private fun submitTerminalInput(
        length: Int,
        eventTimeMillis: Long = SystemClock.uptimeMillis(),
    ): Boolean {
        clearSelection()
        returnToLiveView()
        val accepted = runtimeBinder?.submitTerminalInput(terminalInputBytes, length) == true
        if (accepted) {
            PerformanceMetrics.noteTerminalInput(eventTimeMillis)
        }
        return accepted
    }

    private fun sendSequence(
        sequence: ByteArray,
        eventTimeMillis: Long = SystemClock.uptimeMillis(),
    ): Boolean {
        clearSelection()
        returnToLiveView()
        val accepted = runtimeBinder?.submitTerminalInput(sequence, sequence.size) == true
        if (accepted) {
            PerformanceMetrics.noteTerminalInput(eventTimeMillis)
        }
        return accepted
    }

    private fun encodedLength(codepoint: Int): Int =
        when (codepoint) {
            in 0..0x7f -> 1
            in 0x80..0x7ff -> 2
            in 0x800..0xffff -> 3
            else -> 4
        }

    private fun controlCodepoint(codepoint: Int): Int =
        when (codepoint) {
            in 'a'.code..'z'.code -> codepoint - 'a'.code + 1
            in '@'.code..'_'.code -> codepoint and 0x1f
            ' '.code -> 0
            '?'.code -> 0x7f
            else -> -1
        }

    private fun isAltGraph(event: KeyEvent): Boolean =
        event.isCtrlPressed &&
            (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

    private fun isPasteShortcut(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean =
        keyCode == KeyEvent.KEYCODE_V &&
            event.isCtrlPressed &&
            event.isShiftPressed &&
            !event.isAltPressed

    private fun isCopyShortcut(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean =
        keyCode == KeyEvent.KEYCODE_C &&
            event.isCtrlPressed &&
            event.isShiftPressed &&
            !event.isAltPressed

    private fun terminalTextSizeShortcut(
        keyCode: Int,
        event: KeyEvent,
    ): Int {
        if (!event.isCtrlPressed || event.isAltPressed) {
            return TEXT_SIZE_UNCHANGED
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_MINUS -> TEXT_SIZE_SMALLER
            KeyEvent.KEYCODE_0 -> TEXT_SIZE_RESET
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> TEXT_SIZE_LARGER
            else -> TEXT_SIZE_UNCHANGED
        }
    }

    private fun textCodepoint(
        event: KeyEvent,
        altGraph: Boolean,
    ): Int {
        var metaState = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        if (event.isAltPressed && !altGraph) {
            metaState = metaState and KeyEvent.META_ALT_MASK.inv()
        }
        return event.getUnicodeChar(metaState)
    }

    private fun terminalSequence(
        keyCode: Int,
        shift: Boolean = false,
    ): ByteArray? {
        if (terminalFlags and APPLICATION_KEYPAD_FLAG != 0) {
            applicationKeypadSequence(keyCode)?.let { return it }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (terminalFlags and NEW_LINE_MODE_FLAG != 0) {
                    CARRIAGE_RETURN_LINE_FEED
                } else {
                    CARRIAGE_RETURN
                }
            KeyEvent.KEYCODE_DEL ->
                if (terminalFlags and BACKARROW_KEY_FLAG != 0) ERASE_BACKSPACE else BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
            KeyEvent.KEYCODE_TAB -> if (shift) BACK_TAB else TAB
            KeyEvent.KEYCODE_ESCAPE -> ESCAPE
            KeyEvent.KEYCODE_DPAD_UP ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_UP
                } else {
                    CURSOR_UP
                }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_DOWN
                } else {
                    CURSOR_DOWN
                }
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_RIGHT
                } else {
                    CURSOR_RIGHT
                }
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_LEFT
                } else {
                    CURSOR_LEFT
                }
            KeyEvent.KEYCODE_MOVE_HOME ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_HOME
                } else {
                    CURSOR_HOME
                }
            KeyEvent.KEYCODE_MOVE_END ->
                if (terminalFlags and APPLICATION_CURSOR_FLAG != 0) {
                    APPLICATION_CURSOR_END
                } else {
                    CURSOR_END
                }
            KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> INSERT
            KeyEvent.KEYCODE_F1 -> FUNCTION_1
            KeyEvent.KEYCODE_F2 -> FUNCTION_2
            KeyEvent.KEYCODE_F3 -> FUNCTION_3
            KeyEvent.KEYCODE_F4 -> FUNCTION_4
            KeyEvent.KEYCODE_F5 -> FUNCTION_5
            KeyEvent.KEYCODE_F6 -> FUNCTION_6
            KeyEvent.KEYCODE_F7 -> FUNCTION_7
            KeyEvent.KEYCODE_F8 -> FUNCTION_8
            KeyEvent.KEYCODE_F9 -> FUNCTION_9
            KeyEvent.KEYCODE_F10 -> FUNCTION_10
            KeyEvent.KEYCODE_F11 -> FUNCTION_11
            KeyEvent.KEYCODE_F12 -> FUNCTION_12
            else -> null
        }
    }

    private fun sendModifiedTerminalSequence(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val modifier =
            1 +
                (if (event.isShiftPressed) 1 else 0) +
                (if (event.isAltPressed) 2 else 0) +
                (if (event.isCtrlPressed) 4 else 0)
        if (modifier == 1) {
            return false
        }
        val parameter: Int
        val finalByte: Byte
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                parameter = 1
                finalByte = 'A'.code.toByte()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                parameter = 1
                finalByte = 'B'.code.toByte()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                parameter = 1
                finalByte = 'C'.code.toByte()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                parameter = 1
                finalByte = 'D'.code.toByte()
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                parameter = 1
                finalByte = 'H'.code.toByte()
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                parameter = 1
                finalByte = 'F'.code.toByte()
            }
            KeyEvent.KEYCODE_INSERT -> {
                parameter = 2
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                parameter = 3
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                parameter = 5
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                parameter = 6
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F1 -> {
                parameter = 1
                finalByte = 'P'.code.toByte()
            }
            KeyEvent.KEYCODE_F2 -> {
                parameter = 1
                finalByte = 'Q'.code.toByte()
            }
            KeyEvent.KEYCODE_F3 -> {
                parameter = 1
                finalByte = 'R'.code.toByte()
            }
            KeyEvent.KEYCODE_F4 -> {
                parameter = 1
                finalByte = 'S'.code.toByte()
            }
            KeyEvent.KEYCODE_F5 -> {
                parameter = 15
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F6 -> {
                parameter = 17
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F7 -> {
                parameter = 18
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F8 -> {
                parameter = 19
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F9 -> {
                parameter = 20
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F10 -> {
                parameter = 21
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F11 -> {
                parameter = 23
                finalByte = '~'.code.toByte()
            }
            KeyEvent.KEYCODE_F12 -> {
                parameter = 24
                finalByte = '~'.code.toByte()
            }
            else -> return false
        }
        var output = 0
        terminalInputBytes[output++] = ESCAPE_BYTE
        terminalInputBytes[output++] = '['.code.toByte()
        if (parameter >= 10) {
            terminalInputBytes[output++] = ('0'.code + parameter / 10).toByte()
        }
        terminalInputBytes[output++] = ('0'.code + parameter % 10).toByte()
        terminalInputBytes[output++] = ';'.code.toByte()
        terminalInputBytes[output++] = ('0'.code + modifier).toByte()
        terminalInputBytes[output++] = finalByte
        return submitTerminalInput(output, event.eventTime)
    }

    private fun applicationKeypadSequence(keyCode: Int): ByteArray? =
        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_0 -> KEYPAD_0
            KeyEvent.KEYCODE_NUMPAD_1 -> KEYPAD_1
            KeyEvent.KEYCODE_NUMPAD_2 -> KEYPAD_2
            KeyEvent.KEYCODE_NUMPAD_3 -> KEYPAD_3
            KeyEvent.KEYCODE_NUMPAD_4 -> KEYPAD_4
            KeyEvent.KEYCODE_NUMPAD_5 -> KEYPAD_5
            KeyEvent.KEYCODE_NUMPAD_6 -> KEYPAD_6
            KeyEvent.KEYCODE_NUMPAD_7 -> KEYPAD_7
            KeyEvent.KEYCODE_NUMPAD_8 -> KEYPAD_8
            KeyEvent.KEYCODE_NUMPAD_9 -> KEYPAD_9
            KeyEvent.KEYCODE_NUMPAD_DOT -> KEYPAD_DOT
            KeyEvent.KEYCODE_NUMPAD_COMMA -> KEYPAD_COMMA
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KEYPAD_DIVIDE
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KEYPAD_MULTIPLY
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KEYPAD_SUBTRACT
            KeyEvent.KEYCODE_NUMPAD_ADD -> KEYPAD_ADD
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> KEYPAD_EQUALS
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KEYPAD_ENTER
            else -> null
        }

    private inner class TerminalInputConnection :
        BaseInputConnection(this@RuntimeSurfaceView, true) {
        override fun setComposingText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            if (
                text.length > MAX_COMPOSING_CHARACTERS ||
                encodedTextLength(text) < 0
            ) {
                return false
            }
            composingText = text.toString()
            invalidate()
            return super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            val pending = composingText
            composingText = ""
            invalidate()
            val accepted = super.finishComposingText()
            editable?.clear()
            return accepted && sendText(pending)
        }

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            if (encodedTextLength(text) < 0) {
                return false
            }
            composingText = ""
            invalidate()
            val accepted = super.commitText(text, newCursorPosition)
            editable?.clear()
            return accepted && sendText(text)
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            if (
                beforeLength < 0 ||
                afterLength < 0 ||
                beforeLength > MAX_IME_DELETE ||
                afterLength > MAX_IME_DELETE ||
                beforeLength + afterLength > MAX_IME_DELETE
            ) {
                return false
            }
            if (beforeLength == 0 && afterLength == 0) {
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            var output = 0
            val backwardByte =
                if (terminalFlags and BACKARROW_KEY_FLAG != 0) {
                    ERASE_BACKSPACE_BYTE
                } else {
                    BACKSPACE_BYTE
                }
            terminalInputBytes.fill(backwardByte, output, beforeLength)
            output += beforeLength
            repeat(afterLength) {
                System.arraycopy(DELETE, 0, terminalInputBytes, output, DELETE.size)
                output += DELETE.size
            }
            super.deleteSurroundingText(beforeLength, afterLength)
            return submitTerminalInput(output)
        }

        override fun performEditorAction(actionCode: Int): Boolean =
            sendSequence(
                if (terminalFlags and NEW_LINE_MODE_FLAG != 0) {
                    CARRIAGE_RETURN_LINE_FEED
                } else {
                    CARRIAGE_RETURN
                },
            )

        override fun performContextMenuAction(id: Int): Boolean =
            when (id) {
                android.R.id.copy -> copySelection()
                android.R.id.paste -> pasteClipboard()
                else -> super.performContextMenuAction(id)
            }

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            this@RuntimeSurfaceView.dispatchKeyEvent(event)
    }

    private fun positionRowNodes() {
        val recordingHeight = ceil(cellHeight.toDouble()).toInt().coerceAtLeast(1)
        for (row in rowNodes.indices) {
            val top = (CONTENT_PADDING + row * cellHeight).toInt()
            rowNodes[row].setPosition(0, top, width.coerceAtLeast(0), top + recordingHeight)
        }
    }

    private fun publishTerminalSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) {
            return
        }
        onTerminalSizeChanged?.invoke(preferredRows(height), preferredColumns(width))
    }

    private fun preferredRows(height: Int): Int =
        ((height - CONTENT_PADDING * 2) / cellHeight).toInt().coerceIn(MIN_ROWS, MAX_ROWS)

    private fun preferredColumns(width: Int): Int =
        ((width - CONTENT_PADDING * 2) / cellWidth).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    private fun clearTerminal() {
        stopBlinkAnimation(revealContent = false)
        cursorBlinkPhaseVisible = true
        textBlinkPhaseVisible = true
        resetMouseGesture()
        terminalFocusStateKnown = false
        terminalFocused = false
        rows = 0
        columns = 0
        cursorVisible = false
        cursorColor = DEFAULT_CURSOR_COLOR
        terminalFlags = 0
        terminalRevision = Long.MIN_VALUE
        sourceRevision = Long.MIN_VALUE
        synchronizedOutputPending = false
        historyRows = 0
        historyOriginEpoch = 0L
        viewportOffset = 0
        scrollRowRemainder = 0f
        stopSelectionAutoScroll()
        selectionStart = NO_SELECTION
        selectionEnd = NO_SELECTION
        selectionInitialStart = NO_SELECTION
        selectionInitialEnd = NO_SELECTION
        selectionOriginEpoch = 0L
        selectionDragEndpoint = SELECTION_DRAG_NONE
        selectionDragging = false
        needsFullSnapshot = true
        composingText = ""
        glyphCodepoints = IntArray(0)
        glyphLengths = ByteArray(0)
        glyphWidths = ByteArray(0)
        styledForegroundColors = IntArray(0)
        backgroundColors = IntArray(0)
        blinkingRows = BooleanArray(0)
        blinkingRowCount = 0
        rowNodes = emptyArray()
        contentDescription = context.getString(R.string.linux_session_display)
        invalidate()
    }

    private fun accessibilitySnapshot(): String {
        val rowsPerSnapshot =
            (ACCESSIBILITY_CHARACTER_LIMIT / (columns + 1)).coerceAtLeast(1)
        val lastRow = accessibilityLastRow()
        val firstRow = (lastRow - rowsPerSnapshot + 1).coerceAtLeast(0)
        val builder =
            StringBuilder(
                ((lastRow - firstRow + 1) * (columns + 1))
                    .coerceAtMost(ACCESSIBILITY_CHARACTER_LIMIT),
            )
        for (row in firstRow..lastRow) {
            val start = row * columns
            var end = columns
            while (end > 0 && isBlankGlyph(start + end - 1)) {
                end--
            }
            for (cell in start until start + end) {
                if (terminalCellWidth(cell) == 0) {
                    continue
                }
                val glyphStart = cell * MAX_GRAPHEME_CODEPOINTS
                val glyphLength = glyphLengths[cell].toInt() and 0xff
                for (index in 0 until glyphLength) {
                    val codepoint = glyphCodepoints[glyphStart + index]
                    if (
                        builder.length + Character.charCount(codepoint) >
                        ACCESSIBILITY_CHARACTER_LIMIT
                    ) {
                        return builder.toString()
                    }
                    builder.appendCodePoint(codepoint)
                }
            }
            if (row != lastRow) {
                if (builder.length >= ACCESSIBILITY_CHARACTER_LIMIT) {
                    return builder.toString()
                }
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    private fun accessibilityLastRow(): Int {
        if (viewportOffset > 0) {
            return rows - 1
        }
        var lastRow = cursorRow.coerceIn(0, rows - 1)
        for (row in lastRow + 1 until rows) {
            val start = row * columns
            if ((start until start + columns).any { cell -> !isBlankGlyph(cell) }) {
                lastRow = row
            }
        }
        return lastRow
    }

    private fun terminalAccessibilitySelection(snapshotLength: Int): IntRange? {
        if (viewportOffset == 0 && !hasSelection()) {
            val cursorOffset =
                accessibilityOffsetForCell(cursorRow, cursorColumn)
                    ?: return null
            return cursorOffset
                .takeIf { offset -> offset <= snapshotLength }
                ?.let { offset -> offset..offset }
        }
        if (!hasSelection()) {
            return null
        }
        val firstDocumentRow = firstVisibleDocumentRow()
        val startRow = selectionStart / columns - firstDocumentRow
        val endRow = selectionEnd / columns - firstDocumentRow
        if (startRow !in 0 until rows.toLong() || endRow !in 0 until rows.toLong()) {
            return null
        }
        val startOffset =
            accessibilityOffsetForCell(
                startRow.toInt(),
                (selectionStart % columns).toInt(),
            ) ?: return null
        val endOffset =
            accessibilityOffsetForCell(
                endRow.toInt(),
                (selectionEnd % columns).toInt() + 1,
            ) ?: return null
        return if (startOffset <= endOffset && endOffset <= snapshotLength) {
            startOffset..endOffset
        } else {
            null
        }
    }

    private fun accessibilityOffsetForCell(
        targetRow: Int,
        targetColumn: Int,
    ): Int? {
        val lastRow = accessibilityLastRow()
        val rowsPerSnapshot =
            (ACCESSIBILITY_CHARACTER_LIMIT / (columns + 1)).coerceAtLeast(1)
        val firstRow = (lastRow - rowsPerSnapshot + 1).coerceAtLeast(0)
        if (targetRow !in firstRow..lastRow) {
            return null
        }
        var offset = 0
        for (row in firstRow..lastRow) {
            val start = row * columns
            var end = columns
            while (end > 0 && isBlankGlyph(start + end - 1)) {
                end--
            }
            if (row == targetRow) {
                val requestedEnd = targetColumn.coerceIn(0, end)
                for (column in 0 until requestedEnd) {
                    val cell = start + column
                    if (terminalCellWidth(cell) != 0) {
                        offset += glyphUtf16Length(cell)
                    }
                }
                return offset.coerceAtMost(ACCESSIBILITY_CHARACTER_LIMIT)
            }
            for (column in 0 until end) {
                val cell = start + column
                if (terminalCellWidth(cell) != 0) {
                    offset += glyphUtf16Length(cell)
                }
            }
            if (row != lastRow) {
                offset++
            }
            if (offset > ACCESSIBILITY_CHARACTER_LIMIT) {
                return null
            }
        }
        return null
    }

    private fun accessibilityCellForOffset(requestedOffset: Int): Long? {
        if (requestedOffset < 0) {
            return null
        }
        val lastRow = accessibilityLastRow()
        val rowsPerSnapshot =
            (ACCESSIBILITY_CHARACTER_LIMIT / (columns + 1)).coerceAtLeast(1)
        val firstRow = (lastRow - rowsPerSnapshot + 1).coerceAtLeast(0)
        var offset = 0
        var lastCell: Long? = null
        for (row in firstRow..lastRow) {
            val start = row * columns
            var end = columns
            while (end > 0 && isBlankGlyph(start + end - 1)) {
                end--
            }
            for (column in 0 until end) {
                val cell = start + column
                if (terminalCellWidth(cell) == 0) {
                    continue
                }
                val documentCell =
                    visibleDocumentRow(row) * columns + column
                val nextOffset = offset + glyphUtf16Length(cell)
                if (requestedOffset < nextOffset) {
                    return documentCell
                }
                offset = nextOffset
                lastCell = documentCell
            }
            if (row != lastRow) {
                if (requestedOffset == offset) {
                    return lastCell
                }
                offset++
            }
        }
        return lastCell.takeIf { requestedOffset == offset }
    }

    private fun glyphUtf16Length(cell: Int): Int {
        val glyphStart = cell * MAX_GRAPHEME_CODEPOINTS
        val glyphLength = glyphLengths[cell].toInt() and 0xff
        var length = 0
        for (index in 0 until glyphLength) {
            length += Character.charCount(glyphCodepoints[glyphStart + index])
        }
        return length
    }

    private fun setAccessibilitySelection(arguments: Bundle?): Boolean {
        val start =
            arguments?.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                -1,
            ) ?: return false
        val end =
            arguments.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                -1,
            )
        val snapshotLength = accessibilitySnapshot().length
        if (start < 0 || end < start || end > snapshotLength) {
            return false
        }
        if (start == end) {
            clearSelection()
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            return true
        }
        val startCell = accessibilityCellForOffset(start) ?: return false
        val endCell = accessibilityCellForOffset(end - 1) ?: return false
        selectionStart = minOf(startCell, endCell)
        selectionEnd = maxOf(startCell, endCell)
        selectionInitialStart = selectionStart
        selectionInitialEnd = selectionEnd
        selectionOriginEpoch = historyOriginEpoch
        selectionDragEndpoint = SELECTION_DRAG_NONE
        selectionDragging = false
        recordSelectionRows()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    private fun isBlankGlyph(cell: Int): Boolean =
        terminalCellWidth(cell) == 1 &&
            glyphLengths[cell].toInt() == 1 &&
            glyphCodepoints[cell * MAX_GRAPHEME_CODEPOINTS] == ' '.code

    private fun terminalCellWidth(cell: Int): Int =
        glyphWidths[cell].toInt() and GLYPH_WIDTH_MASK

    private fun cellBlinks(cell: Int): Boolean =
        glyphWidths[cell].toInt() and GLYPH_BLINK_FLAG != 0

    private fun setViewportOffset(requestedOffset: Int): Boolean {
        val nextOffset = requestedOffset.coerceIn(0, historyRows)
        if (nextOffset == viewportOffset) {
            return true
        }
        viewportOffset = nextOffset
        needsFullSnapshot = true
        scheduleTerminalAccessibilityEvent(scrolled = true)
        invalidate()
        return true
    }

    private fun scheduleTerminalAccessibilityEvent(
        textChanged: Boolean = false,
        scrolled: Boolean = false,
    ) {
        if (!accessibilityManager.isEnabled) {
            return
        }
        accessibilityTextChanged = accessibilityTextChanged || textChanged
        accessibilityScrolled = accessibilityScrolled || scrolled
        if (!accessibilityEventPosted) {
            accessibilityEventPosted = true
            postDelayed(accessibilityEventRunnable, ACCESSIBILITY_EVENT_DELAY_MILLIS)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendTerminalAccessibilityEvent(
        type: Int,
        configure: (AccessibilityEvent) -> Unit,
    ) {
        val event = AccessibilityEvent.obtain(type)
        event.className = "android.widget.TextView"
        event.packageName = context.packageName
        event.isEnabled = isEnabled
        configure(event)
        sendAccessibilityEventUnchecked(event)
    }

    private fun returnToLiveView() {
        if (viewportOffset != 0) {
            viewportOffset = 0
            needsFullSnapshot = true
        }
    }

    private fun resolveTerminalColor(color: Int): Int =
        if (color and DIRECT_COLOR_FLAG != 0) {
            0xff000000.toInt() or (color and RGB_MASK)
        } else {
            ANSI_COLORS[color.coerceIn(0, ANSI_COLORS.lastIndex)]
        }

    private fun blendTerminalColors(
        foreground: Int,
        background: Int,
    ): Int {
        val red =
            (((foreground ushr 16) and 0xff) * FAINT_FOREGROUND_WEIGHT +
                ((background ushr 16) and 0xff) * FAINT_BACKGROUND_WEIGHT) /
                FAINT_TOTAL_WEIGHT
        val green =
            (((foreground ushr 8) and 0xff) * FAINT_FOREGROUND_WEIGHT +
                ((background ushr 8) and 0xff) * FAINT_BACKGROUND_WEIGHT) /
                FAINT_TOTAL_WEIGHT
        val blue =
            ((foreground and 0xff) * FAINT_FOREGROUND_WEIGHT +
                (background and 0xff) * FAINT_BACKGROUND_WEIGHT) /
                FAINT_TOTAL_WEIGHT
        return 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private class MousePosition {
        var column = 0
        var row = 0
        var pixelX = 0
        var pixelY = 0
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val AUTOMATIC_TEXT_SIZE = 0
        private const val AUTOMATIC_TERMINAL_TEXT_SP = 16
        private const val TERMINAL_FONT_ASSET =
            "JetBrainsMonoNLNerdFontMono-Regular.ttf"
        private const val MIN_TERMINAL_TEXT_SP = 10
        private const val MAX_TERMINAL_TEXT_SP = 32
        private const val TEXT_SIZE_UNCHANGED = -1
        private const val TEXT_SIZE_SMALLER = 0
        private const val TEXT_SIZE_RESET = 1
        private const val TEXT_SIZE_LARGER = 2
        private const val NO_SELECTION = -1L
        private const val NO_MOUSE_POINTER = -1
        private const val NO_MOUSE_POSITION = -1
        private const val SELECTION_DRAG_NONE = 0
        private const val SELECTION_DRAG_WORD = 1
        private const val SELECTION_DRAG_START = 2
        private const val SELECTION_DRAG_END = 3
        private const val SELECTION_AUTO_SCROLL_FRAME_INTERVAL = 3
        private const val SELECTION_HANDLE_RADIUS_DP = 7f
        private const val SELECTION_HANDLE_TOUCH_RADIUS_DP = 24f
        private const val MENU_TEXT_SMALLER = 0x415201
        private const val MENU_TEXT_RESET = 0x415202
        private const val MENU_TEXT_LARGER = 0x415203
        private const val CONTENT_PADDING = 8f
        private const val CURSOR_STROKE_WIDTH_DP = 2f
        private const val UNDERLINE_HEIGHT = 1f
        private const val ITALIC_TEXT_SKEW = -0.2f
        private const val FAINT_FOREGROUND_WEIGHT = 3
        private const val FAINT_BACKGROUND_WEIGHT = 2
        private const val FAINT_TOTAL_WEIGHT =
            FAINT_FOREGROUND_WEIGHT + FAINT_BACKGROUND_WEIGHT
        private const val DAMAGE_MAGIC = 0x4d525441
        private const val DAMAGE_VERSION = 7
        private const val DAMAGE_HEADER_SIZE = 52
        private const val DAMAGE_CELL_SIZE = 76
        private const val MAX_GRAPHEME_CODEPOINTS = 16
        private const val MAX_GRAPHEME_UTF16_UNITS = MAX_GRAPHEME_CODEPOINTS * 2
        private const val MAX_UNICODE_CODEPOINT = 0x10ffff
        private const val REPLACEMENT_CODEPOINT = 0xfffd
        private const val MIN_ROWS = 2
        private const val MAX_ROWS = 200
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 400
        private const val ACCESSIBILITY_CHARACTER_LIMIT = 8 * 1024
        private const val ACCESSIBILITY_EVENT_DELAY_MILLIS = 100L
        private const val BLINK_INTERVAL_MILLIS = 500L
        private const val TERMINAL_INPUT_LIMIT = 8 * 1024
        private const val MAX_COMPOSING_CHARACTERS = 2 * 1024
        private const val MAX_CLIPBOARD_CHARACTERS = 2 * 1024
        private const val MAX_IME_DELETE = 64
        private const val SCROLL_WHEEL_ROWS = 3f
        private const val CURSOR_VISIBLE_FLAG = 1
        private const val APPLICATION_CURSOR_FLAG = 1 shl 1
        private const val APPLICATION_KEYPAD_FLAG = 1 shl 2
        private const val BRACKETED_PASTE_FLAG = 1 shl 3
        private const val NEW_LINE_MODE_FLAG = 1 shl 4
        private const val BACKARROW_KEY_FLAG = 1 shl 5
        private const val REVERSE_SCREEN_FLAG = 1 shl 6
        private const val CURSOR_BLINK_FLAG = 1 shl 7
        private const val CURSOR_STYLE_SHIFT = 8
        private const val CURSOR_STYLE_MASK = 0x3 shl CURSOR_STYLE_SHIFT
        private const val CURSOR_STYLE_BLOCK = 1
        private const val CURSOR_STYLE_BAR = 2
        private const val MOUSE_TRACKING_SHIFT = 10
        private const val MOUSE_TRACKING_MASK = 0x7 shl MOUSE_TRACKING_SHIFT
        private const val MOUSE_TRACKING_NONE = 0
        private const val MOUSE_TRACKING_X10 = 1
        private const val MOUSE_TRACKING_BUTTON_EVENT = 3
        private const val MOUSE_TRACKING_ANY_EVENT = 4
        private const val FOCUS_REPORTING_FLAG = 1 shl 13
        private const val MOUSE_ENCODING_SHIFT = 14
        private const val MOUSE_ENCODING_MASK = 0x7 shl MOUSE_ENCODING_SHIFT
        private const val EIGHT_BIT_META_FLAG = 1 shl 17
        private const val CURSOR_PRESENTATION_FLAGS =
            CURSOR_VISIBLE_FLAG or CURSOR_BLINK_FLAG or CURSOR_STYLE_MASK
        private const val MOUSE_BUTTON_PRIMARY = 0
        private const val MOUSE_BUTTON_MIDDLE = 1
        private const val MOUSE_BUTTON_SECONDARY = 2
        private const val MOUSE_BUTTON_NONE = 3
        private const val MOUSE_BUTTON_WHEEL_UP = 64
        private const val MOUSE_BUTTON_WHEEL_DOWN = 65
        private const val MOUSE_MODIFIER_SHIFT = 4
        private const val MOUSE_MODIFIER_META = 8
        private const val MOUSE_MODIFIER_CONTROL = 16
        private const val DIRECT_COLOR_FLAG = 1 shl 24
        private const val RGB_MASK = 0x00ffffff
        private const val COLOR_VALUE_MASK = 0x01ffffff
        private const val ATTRIBUTE_SHIFT = 25
        private const val ATTRIBUTE_BOLD = 1
        private const val ATTRIBUTE_UNDERLINE = 2
        private const val ATTRIBUTE_INVERSE = 4
        private const val ATTRIBUTE_FAINT = 1 shl 3
        private const val ATTRIBUTE_ITALIC = 1 shl 4
        private const val ATTRIBUTE_STRIKE = 1 shl 5
        private const val ATTRIBUTE_HIDDEN = 1 shl 6
        private const val ATTRIBUTE_BLINK = 1 shl 7
        private const val ATTRIBUTE_STYLE_MASK = 0x7f
        private const val GLYPH_WIDTH_MASK = 0x7f
        private const val GLYPH_BLINK_FLAG = 0x80
        private const val TERMINAL_BACKGROUND = 0xff1f2326.toInt()
        private const val DEFAULT_CURSOR_COLOR = 0x7dd3fc
        private const val OPAQUE_ALPHA = 0xff000000.toInt()
        private const val CURSOR_BLOCK_ALPHA = 0x99000000.toInt()
        private const val COMPOSING_BACKGROUND = 0xff31363b.toInt()
        private const val SELECTION_OVERLAY = 0x667dd3fc
        private const val SELECTION_HANDLE_COLOR = 0xff7dd3fc.toInt()
        private const val REPLACEMENT_CHARACTER = '\ufffd'
        private val ESCAPE_BYTE = 0x1b.toByte()
        private val CARRIAGE_RETURN_BYTE = 0x0d.toByte()
        private val LINE_FEED_BYTE = 0x0a.toByte()
        private val BACKSPACE_BYTE = 0x7f.toByte()
        private val ERASE_BACKSPACE_BYTE = 0x08.toByte()
        private val CARRIAGE_RETURN = byteArrayOf(0x0d)
        private val CARRIAGE_RETURN_LINE_FEED = byteArrayOf(0x0d, 0x0a)
        private val BACKSPACE = byteArrayOf(0x7f)
        private val ERASE_BACKSPACE = byteArrayOf(0x08)
        private val BRACKETED_PASTE_START = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e)
        private val BRACKETED_PASTE_END = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e)
        private val TAB = byteArrayOf(0x09)
        private val ESCAPE = byteArrayOf(0x1b)
        private val FOCUS_IN = byteArrayOf(0x1b, 0x5b, 0x49)
        private val FOCUS_OUT = byteArrayOf(0x1b, 0x5b, 0x4f)
        private val CURSOR_UP = byteArrayOf(0x1b, 0x5b, 0x41)
        private val CURSOR_DOWN = byteArrayOf(0x1b, 0x5b, 0x42)
        private val CURSOR_RIGHT = byteArrayOf(0x1b, 0x5b, 0x43)
        private val CURSOR_LEFT = byteArrayOf(0x1b, 0x5b, 0x44)
        private val CURSOR_HOME = byteArrayOf(0x1b, 0x5b, 0x48)
        private val CURSOR_END = byteArrayOf(0x1b, 0x5b, 0x46)
        private val APPLICATION_CURSOR_UP = byteArrayOf(0x1b, 0x4f, 0x41)
        private val APPLICATION_CURSOR_DOWN = byteArrayOf(0x1b, 0x4f, 0x42)
        private val APPLICATION_CURSOR_RIGHT = byteArrayOf(0x1b, 0x4f, 0x43)
        private val APPLICATION_CURSOR_LEFT = byteArrayOf(0x1b, 0x4f, 0x44)
        private val APPLICATION_CURSOR_HOME = byteArrayOf(0x1b, 0x4f, 0x48)
        private val APPLICATION_CURSOR_END = byteArrayOf(0x1b, 0x4f, 0x46)
        private val BACK_TAB = byteArrayOf(0x1b, 0x5b, 0x5a)
        private val INSERT = byteArrayOf(0x1b, 0x5b, 0x32, 0x7e)
        private val DELETE = byteArrayOf(0x1b, 0x5b, 0x33, 0x7e)
        private val PAGE_UP = byteArrayOf(0x1b, 0x5b, 0x35, 0x7e)
        private val PAGE_DOWN = byteArrayOf(0x1b, 0x5b, 0x36, 0x7e)
        private val FUNCTION_1 = byteArrayOf(0x1b, 0x4f, 0x50)
        private val FUNCTION_2 = byteArrayOf(0x1b, 0x4f, 0x51)
        private val FUNCTION_3 = byteArrayOf(0x1b, 0x4f, 0x52)
        private val FUNCTION_4 = byteArrayOf(0x1b, 0x4f, 0x53)
        private val FUNCTION_5 = byteArrayOf(0x1b, 0x5b, 0x31, 0x35, 0x7e)
        private val FUNCTION_6 = byteArrayOf(0x1b, 0x5b, 0x31, 0x37, 0x7e)
        private val FUNCTION_7 = byteArrayOf(0x1b, 0x5b, 0x31, 0x38, 0x7e)
        private val FUNCTION_8 = byteArrayOf(0x1b, 0x5b, 0x31, 0x39, 0x7e)
        private val FUNCTION_9 = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x7e)
        private val FUNCTION_10 = byteArrayOf(0x1b, 0x5b, 0x32, 0x31, 0x7e)
        private val FUNCTION_11 = byteArrayOf(0x1b, 0x5b, 0x32, 0x33, 0x7e)
        private val FUNCTION_12 = byteArrayOf(0x1b, 0x5b, 0x32, 0x34, 0x7e)
        private val KEYPAD_0 = byteArrayOf(0x1b, 0x4f, 0x70)
        private val KEYPAD_1 = byteArrayOf(0x1b, 0x4f, 0x71)
        private val KEYPAD_2 = byteArrayOf(0x1b, 0x4f, 0x72)
        private val KEYPAD_3 = byteArrayOf(0x1b, 0x4f, 0x73)
        private val KEYPAD_4 = byteArrayOf(0x1b, 0x4f, 0x74)
        private val KEYPAD_5 = byteArrayOf(0x1b, 0x4f, 0x75)
        private val KEYPAD_6 = byteArrayOf(0x1b, 0x4f, 0x76)
        private val KEYPAD_7 = byteArrayOf(0x1b, 0x4f, 0x77)
        private val KEYPAD_8 = byteArrayOf(0x1b, 0x4f, 0x78)
        private val KEYPAD_9 = byteArrayOf(0x1b, 0x4f, 0x79)
        private val KEYPAD_DOT = byteArrayOf(0x1b, 0x4f, 0x6e)
        private val KEYPAD_COMMA = byteArrayOf(0x1b, 0x4f, 0x6c)
        private val KEYPAD_DIVIDE = byteArrayOf(0x1b, 0x4f, 0x6f)
        private val KEYPAD_MULTIPLY = byteArrayOf(0x1b, 0x4f, 0x6a)
        private val KEYPAD_SUBTRACT = byteArrayOf(0x1b, 0x4f, 0x6d)
        private val KEYPAD_ADD = byteArrayOf(0x1b, 0x4f, 0x6b)
        private val KEYPAD_EQUALS = byteArrayOf(0x1b, 0x4f, 0x58)
        private val KEYPAD_ENTER = byteArrayOf(0x1b, 0x4f, 0x4d)
        private val SURROGATE_RANGE = 0xd800..0xdfff
        private val ANSI_COLORS =
            IntArray(256).apply {
                this[0] = TERMINAL_BACKGROUND
                this[1] = 0xfff38ba8.toInt()
                this[2] = 0xffa6e3a1.toInt()
                this[3] = 0xfff9e2af.toInt()
                this[4] = 0xff89b4fa.toInt()
                this[5] = 0xffcba6f7.toInt()
                this[6] = 0xff94e2d5.toInt()
                this[7] = 0xffcdd6f4.toInt()
                this[8] = 0xff585b70.toInt()
                this[9] = 0xfff38ba8.toInt()
                this[10] = 0xffa6e3a1.toInt()
                this[11] = 0xfff9e2af.toInt()
                this[12] = 0xff89b4fa.toInt()
                this[13] = 0xffcba6f7.toInt()
                this[14] = 0xff94e2d5.toInt()
                this[15] = Color.WHITE
                for (red in 0..5) {
                    for (green in 0..5) {
                        for (blue in 0..5) {
                            this[16 + 36 * red + 6 * green + blue] =
                                Color.rgb(
                                    ansiCubeComponent(red),
                                    ansiCubeComponent(green),
                                    ansiCubeComponent(blue),
                                )
                        }
                    }
                }
                for (index in 232..255) {
                    val level = 8 + (index - 232) * 10
                    this[index] = Color.rgb(level, level, level)
                }
            }
        private const val DEFAULT_FOREGROUND = 7
        private const val DEFAULT_BACKGROUND = 0

        private fun ansiCubeComponent(index: Int): Int =
            when (index) {
                0 -> 0
                1 -> 95
                2 -> 135
                3 -> 175
                4 -> 215
                else -> 255
            }
    }
}
