package org.archphene.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.nio.ByteBuffer
import kotlin.math.ceil
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.InputBatch
import org.archphene.app.runtime.NativeRuntime

internal class RuntimeSurfaceView(context: Context) : View(context) {
    private val inputBatch = InputBatch()
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
    private var terminalRevision = Long.MIN_VALUE
    private var sourceRevision = Long.MIN_VALUE
    private var needsFullSnapshot = true
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

    fun flushInput(handle: Long) {
        val submitted = inputBatch.flush(handle)
        if (submitted > 0) {
            NativeRuntime.nativeDrainInput(handle, submitted)
        }
    }

    fun renderFrame(binder: ArchpheneRuntimeService.LocalBinder?) {
        if (binder?.sharedShellRunning != true) {
            if (rows != 0) {
                clearTerminal()
            }
            return
        }
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
        cursorVisible = damageBuffer.getInt(20) and CURSOR_VISIBLE_FLAG != 0
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
        terminalRevision = Long.MIN_VALUE
        sourceRevision = Long.MIN_VALUE
        needsFullSnapshot = true
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
        private const val CURSOR_VISIBLE_FLAG = 1
        private const val COLOR_MASK = 0x0f
        private const val BACKGROUND_SHIFT = 4
        private const val ATTRIBUTE_SHIFT = 8
        private const val ATTRIBUTE_BOLD = 1
        private const val ATTRIBUTE_UNDERLINE = 2
        private const val ATTRIBUTE_INVERSE = 4
        private const val TERMINAL_BACKGROUND = 0xff1f2326.toInt()
        private const val CURSOR_COLOR = 0xff7dd3fc.toInt()
        private const val REPLACEMENT_CHARACTER = '\ufffd'
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
