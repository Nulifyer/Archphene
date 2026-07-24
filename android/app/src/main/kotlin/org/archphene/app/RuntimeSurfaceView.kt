package org.archphene.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.nio.ByteBuffer
import kotlin.math.ceil
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.InputBatch
import org.archphene.app.runtime.NativeRuntime

internal class RuntimeSurfaceView(context: Context) : View(context) {
    private val inputBatch = InputBatch()
    private val terminalInputBytes = ByteArray(TERMINAL_INPUT_LIMIT)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    TERMINAL_TEXT_SP,
                    resources.displayMetrics,
                )
        }
    private val backgroundPaint = Paint()
    private var glyphs = CharArray(0)
    private var styles = IntArray(0)
    private var rowNodes = emptyArray<RenderNode>()
    private var rows = 0
    private var columns = 0
    private var cursorRow = 0
    private var cursorColumn = 0
    private var cursorVisible = false
    private var terminalFlags = 0
    private var terminalRevision = Long.MIN_VALUE
    private var sourceRevision = Long.MIN_VALUE
    private var needsFullSnapshot = true
    private var composingText = ""
    private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var cellWidth = ceil(textPaint.measureText("M").toDouble()).toFloat().coerceAtLeast(1f)
    private var cellHeight =
        ceil((textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toDouble())
            .toFloat()
            .coerceAtLeast(1f)

    var onTerminalSizeChanged: ((rows: Int, columns: Int) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(TERMINAL_BACKGROUND)
        contentDescription = context.getString(R.string.linux_session_display)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows == 0 || columns == 0) {
            textPaint.color = ANSI_COLORS[7]
            textPaint.isFakeBoldText = false
            canvas.drawText(
                context.getString(R.string.linux_session_display),
                CONTENT_PADDING,
                CONTENT_PADDING - textPaint.fontMetrics.ascent,
                textPaint,
            )
            return
        }
        for (node in rowNodes) {
            if (node.hasDisplayList()) {
                canvas.drawRenderNode(node)
            }
        }
        drawComposingText(canvas)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.isEditable = false
        info.isMultiLine = true
        if (rows != 0 && columns != 0) {
            info.text = accessibilitySnapshot()
        }
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
        return TerminalInputConnection()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        terminalSequence(keyCode, event.isShiftPressed)?.let { return sendSequence(it) }
        val altGraph = isAltGraph(event)
        val baseCodepoint = textCodepoint(event, altGraph)
        if (baseCodepoint == 0) {
            return super.onKeyDown(keyCode, event)
        }
        if (event.isCtrlPressed && !altGraph) {
            val control = controlCodepoint(baseCodepoint)
            if (control >= 0) {
                terminalInputBytes[0] = control.toByte()
                return submitTerminalInput(1)
            }
        }
        var offset = 0
        if (event.isAltPressed && !altGraph) {
            terminalInputBytes[offset++] = ESCAPE_BYTE
        }
        val encoded = encodeCodepoint(baseCodepoint, terminalInputBytes, offset)
        return encoded != 0 && submitTerminalInput(offset + encoded)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean =
        if (
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
            if (rows != 0) {
                clearTerminal()
            }
            return
        }
        runtimeBinder = binder
        val nextSourceRevision = binder.sharedShellTerminalRevision
        if (!needsFullSnapshot && nextSourceRevision == sourceRevision) {
            return
        }
        val length = binder.readSharedShellTerminalDamage(needsFullSnapshot)
        if (
            length < DAMAGE_HEADER_SIZE ||
            !applyDamage(binder.sharedShellTerminalDamageBuffer, length)
        ) {
            sourceRevision = nextSourceRevision
            return
        }
        sourceRevision = nextSourceRevision
        needsFullSnapshot = false
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
        if (
            nextRows !in MIN_ROWS..MAX_ROWS ||
            nextColumns !in MIN_COLUMNS..MAX_COLUMNS ||
            nextCursorRow !in 0 until nextRows ||
            nextCursorColumn !in 0 until nextColumns ||
            dirtyStart !in 0..nextRows ||
            dirtyEnd !in dirtyStart..nextRows
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
        if (terminalRevision != Long.MIN_VALUE && nextTerminalRevision < terminalRevision) {
            return false
        }
        if (nextRows != rows || nextColumns != columns) {
            rows = nextRows
            columns = nextColumns
            contentDescription =
                context.getString(
                    R.string.terminal_accessibility_dimensions,
                    columns,
                    rows,
                )
            glyphs = CharArray(rows * columns) { ' ' }
            styles = IntArray(rows * columns) { DEFAULT_STYLE }
            rowNodes = Array(rows) { row -> RenderNode("terminal-row-$row") }
            positionRowNodes()
        }
        cursorRow = nextCursorRow
        cursorColumn = nextCursorColumn
        terminalFlags = damageBuffer.getInt(20)
        cursorVisible = terminalFlags and CURSOR_VISIBLE_FLAG != 0
        terminalRevision = nextTerminalRevision
        var offset = DAMAGE_HEADER_SIZE
        for (row in dirtyStart until dirtyEnd) {
            val rowStart = row * columns
            for (column in 0 until columns) {
                val codepoint = damageBuffer.getInt(offset)
                glyphs[rowStart + column] =
                    if (codepoint in 0..Char.MAX_VALUE.code && codepoint !in SURROGATE_RANGE) {
                        codepoint.toChar()
                    } else {
                        REPLACEMENT_CHARACTER
                    }
                styles[rowStart + column] =
                    packStyle(
                        damageBuffer.get(offset + 4).toInt() and 0xff,
                        damageBuffer.get(offset + 5).toInt() and 0xff,
                        damageBuffer.get(offset + 6).toInt() and 0xff,
                    )
                offset += DAMAGE_CELL_SIZE
            }
        }
        if (dirtyStart < dirtyEnd) {
            for (row in dirtyStart until dirtyEnd) {
                recordRow(row)
            }
            invalidate()
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
            val style = styles[start + runStart]
            var runEnd = runStart + 1
            while (runEnd < columns && styles[start + runEnd] == style) {
                runEnd++
            }
            val foregroundIndex = style and COLOR_MASK
            val backgroundIndex = style ushr BACKGROUND_SHIFT and COLOR_MASK
            val attributes = style ushr ATTRIBUTE_SHIFT
            val foreground =
                if (attributes and ATTRIBUTE_INVERSE != 0) {
                    ANSI_COLORS[backgroundIndex.coerceIn(0, ANSI_COLORS.lastIndex)]
                } else {
                    ANSI_COLORS[foregroundIndex.coerceIn(0, ANSI_COLORS.lastIndex)]
                }
            val background =
                if (attributes and ATTRIBUTE_INVERSE != 0) {
                    ANSI_COLORS[foregroundIndex.coerceIn(0, ANSI_COLORS.lastIndex)]
                } else {
                    ANSI_COLORS[backgroundIndex.coerceIn(0, ANSI_COLORS.lastIndex)]
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
            canvas.drawText(
                glyphs,
                start + runStart,
                runEnd - runStart,
                CONTENT_PADDING + runStart * cellWidth,
                baseline,
                textPaint,
            )
            if (attributes and ATTRIBUTE_UNDERLINE != 0) {
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
        if (cursorVisible && row == cursorRow && cursorColumn in 0 until columns) {
            backgroundPaint.color = CURSOR_COLOR
            val left = CONTENT_PADDING + cursorColumn * cellWidth
            canvas.drawRect(
                left,
                cellHeight - CURSOR_HEIGHT,
                left + cellWidth,
                cellHeight,
                backgroundPaint,
            )
        }
        rowNodes[row].endRecording()
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
        backgroundPaint.color = CURSOR_COLOR
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
            output += encodeCodepoint(codepoint, terminalInputBytes, output)
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

    private fun submitTerminalInput(length: Int): Boolean =
        runtimeBinder?.submitTerminalInput(terminalInputBytes, length) == true

    private fun sendSequence(sequence: ByteArray): Boolean =
        runtimeBinder?.submitTerminalInput(sequence, sequence.size) == true

    private fun encodedLength(codepoint: Int): Int =
        when (codepoint) {
            in 0..0x7f -> 1
            in 0x80..0x7ff -> 2
            in 0x800..0xffff -> 3
            else -> 4
        }

    private fun encodeCodepoint(
        codepoint: Int,
        output: ByteArray,
        offset: Int,
    ): Int {
        val value =
            if (codepoint in 0..0x10ffff && codepoint !in SURROGATE_RANGE) {
                codepoint
            } else {
                REPLACEMENT_CHARACTER.code
            }
        return when (val length = encodedLength(value)) {
            1 -> {
                output[offset] = value.toByte()
                length
            }
            2 -> {
                output[offset] = (0xc0 or (value shr 6)).toByte()
                output[offset + 1] = (0x80 or (value and 0x3f)).toByte()
                length
            }
            3 -> {
                output[offset] = (0xe0 or (value shr 12)).toByte()
                output[offset + 1] = (0x80 or (value shr 6 and 0x3f)).toByte()
                output[offset + 2] = (0x80 or (value and 0x3f)).toByte()
                length
            }
            else -> {
                output[offset] = (0xf0 or (value shr 18)).toByte()
                output[offset + 1] = (0x80 or (value shr 12 and 0x3f)).toByte()
                output[offset + 2] = (0x80 or (value shr 6 and 0x3f)).toByte()
                output[offset + 3] = (0x80 or (value and 0x3f)).toByte()
                length
            }
        }
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
        rows = 0
        columns = 0
        cursorVisible = false
        terminalFlags = 0
        terminalRevision = Long.MIN_VALUE
        sourceRevision = Long.MIN_VALUE
        needsFullSnapshot = true
        composingText = ""
        glyphs = CharArray(0)
        styles = IntArray(0)
        rowNodes = emptyArray()
        contentDescription = context.getString(R.string.linux_session_display)
        invalidate()
    }

    private fun accessibilitySnapshot(): String {
        val rowsPerSnapshot =
            (ACCESSIBILITY_CHARACTER_LIMIT / (columns + 1)).coerceAtLeast(1)
        val firstRow = (cursorRow - rowsPerSnapshot + 1).coerceAtLeast(0)
        val builder =
            StringBuilder(
                ((cursorRow - firstRow + 1) * (columns + 1))
                    .coerceAtMost(ACCESSIBILITY_CHARACTER_LIMIT),
            )
        for (row in firstRow..cursorRow.coerceAtMost(rows - 1)) {
            val start = row * columns
            var end = columns
            while (end > 0 && glyphs[start + end - 1] == ' ') {
                end--
            }
            if (end != 0) {
                builder.append(glyphs, start, end)
            }
            if (row != cursorRow) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    private fun packStyle(
        foreground: Int,
        background: Int,
        attributes: Int,
    ): Int =
        (foreground and COLOR_MASK) or
            ((background and COLOR_MASK) shl BACKGROUND_SHIFT) or
            (attributes shl ATTRIBUTE_SHIFT)

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TERMINAL_TEXT_SP = 14f
        private const val CONTENT_PADDING = 8f
        private const val CURSOR_HEIGHT = 2f
        private const val UNDERLINE_HEIGHT = 1f
        private const val DAMAGE_MAGIC = 0x4d525441
        private const val DAMAGE_VERSION = 1
        private const val DAMAGE_HEADER_SIZE = 32
        private const val DAMAGE_CELL_SIZE = 8
        private const val MIN_ROWS = 2
        private const val MAX_ROWS = 200
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 400
        private const val ACCESSIBILITY_CHARACTER_LIMIT = 8 * 1024
        private const val TERMINAL_INPUT_LIMIT = 8 * 1024
        private const val MAX_COMPOSING_CHARACTERS = 2 * 1024
        private const val MAX_IME_DELETE = 64
        private const val CURSOR_VISIBLE_FLAG = 1
        private const val APPLICATION_CURSOR_FLAG = 1 shl 1
        private const val APPLICATION_KEYPAD_FLAG = 1 shl 2
        private const val NEW_LINE_MODE_FLAG = 1 shl 4
        private const val BACKARROW_KEY_FLAG = 1 shl 5
        private const val COLOR_MASK = 0x0f
        private const val BACKGROUND_SHIFT = 4
        private const val ATTRIBUTE_SHIFT = 8
        private const val ATTRIBUTE_BOLD = 1
        private const val ATTRIBUTE_UNDERLINE = 2
        private const val ATTRIBUTE_INVERSE = 4
        private const val TERMINAL_BACKGROUND = 0xff1f2326.toInt()
        private const val CURSOR_COLOR = 0xff7dd3fc.toInt()
        private const val COMPOSING_BACKGROUND = 0xff31363b.toInt()
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
        private val TAB = byteArrayOf(0x09)
        private val ESCAPE = byteArrayOf(0x1b)
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
            intArrayOf(
                TERMINAL_BACKGROUND,
                0xfff38ba8.toInt(),
                0xffa6e3a1.toInt(),
                0xfff9e2af.toInt(),
                0xff89b4fa.toInt(),
                0xffcba6f7.toInt(),
                0xff94e2d5.toInt(),
                0xffcdd6f4.toInt(),
                0xff585b70.toInt(),
                0xfff38ba8.toInt(),
                0xffa6e3a1.toInt(),
                0xfff9e2af.toInt(),
                0xff89b4fa.toInt(),
                0xffcba6f7.toInt(),
                0xff94e2d5.toInt(),
                Color.WHITE,
            )
        private val DEFAULT_STYLE = 7
    }
}
