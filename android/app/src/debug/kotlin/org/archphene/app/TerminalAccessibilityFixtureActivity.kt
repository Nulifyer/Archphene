package org.archphene.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only exact damage-protocol fixture for testing the production terminal
 * View with the device's real screen reader when a managed shell is absent.
 */
internal class TerminalAccessibilityFixtureActivity : Activity() {
    private lateinit var terminal: RuntimeSurfaceView
    private var lastAccessibilityFocused = false
    private var lastSelectionStart = Int.MIN_VALUE
    private var lastSelectionEnd = Int.MIN_VALUE
    private var accessibilityPolls = 0
    private val accessibilityStatePoll =
        object : Runnable {
            override fun run() {
                val node = terminal.createAccessibilityNodeInfo()
                val focused = node.isAccessibilityFocused
                val selectionStart = node.textSelectionStart
                val selectionEnd = node.textSelectionEnd
                if (
                    focused != lastAccessibilityFocused ||
                    selectionStart != lastSelectionStart ||
                    selectionEnd != lastSelectionEnd
                ) {
                    lastAccessibilityFocused = focused
                    lastSelectionStart = selectionStart
                    lastSelectionEnd = selectionEnd
                    Log.i(
                        TAG,
                        "Terminal accessibility state focused=$focused " +
                            "selection=$selectionStart..$selectionEnd " +
                            "granularities=${node.movementGranularities}",
                    )
                }
                accessibilityPolls++
                if (accessibilityPolls < MAX_ACCESSIBILITY_POLLS) {
                    terminal.postDelayed(this, ACCESSIBILITY_POLL_MILLIS)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        terminal = RuntimeSurfaceView(this, AUTOMATIC_TEXT_SIZE)
        val root =
            FrameLayout(this).apply {
                @Suppress("DEPRECATION")
                setOnApplyWindowInsetsListener { view, insets ->
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                    insets
                }
                addView(
                    terminal,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        setContentView(root)
        terminal.post {
            try {
                val damage = terminalFixture()
                val applyDamage =
                    RuntimeSurfaceView::class.java.getDeclaredMethod(
                        "applyDamage",
                        ByteBuffer::class.java,
                        Int::class.javaPrimitiveType,
                    )
                applyDamage.isAccessible = true
                check(applyDamage.invoke(terminal, damage, damage.capacity()) == true) {
                    "terminal rejected its accessibility fixture"
                }
                terminal.requestFocus()
                terminal.post(accessibilityStatePoll)
                Log.i(TAG, "Terminal accessibility fixture ready")
            } catch (error: Exception) {
                Log.e(TAG, "Terminal accessibility fixture failed", error)
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (::terminal.isInitialized) {
            terminal.removeCallbacks(accessibilityStatePoll)
        }
        super.onDestroy()
    }

    private fun terminalFixture(): ByteBuffer {
        val buffer =
            ByteBuffer
                .allocate(DAMAGE_HEADER_SIZE + ROWS * COLUMNS * DAMAGE_CELL_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, DAMAGE_MAGIC)
        buffer.putInt(4, DAMAGE_VERSION)
        buffer.putShort(8, ROWS.toShort())
        buffer.putShort(10, COLUMNS.toShort())
        buffer.putShort(12, CURSOR_ROW.toShort())
        buffer.putShort(14, CURSOR_COLUMN.toShort())
        buffer.putShort(16, 0)
        buffer.putShort(18, ROWS.toShort())
        buffer.putInt(20, CURSOR_VISIBLE_FLAG)
        buffer.putLong(24, 1L)
        buffer.putInt(32, 0)
        buffer.putInt(36, 0)
        buffer.putLong(40, 1L)
        buffer.putInt(48, CURSOR_COLOR)

        for (row in 0 until ROWS) {
            val content = CONTENT.getOrElse(row) { "" }
            for (column in 0 until COLUMNS) {
                val cell =
                    DAMAGE_HEADER_SIZE +
                        (row * COLUMNS + column) * DAMAGE_CELL_SIZE
                buffer.putInt(cell, content.getOrNull(column)?.code ?: ' '.code)
                buffer.putInt(cell + 64, DEFAULT_FOREGROUND)
                buffer.putInt(cell + 68, DEFAULT_BACKGROUND)
                buffer.put(cell + 72, 0)
                buffer.put(cell + 73, 1)
                buffer.put(cell + 74, 1)
                buffer.put(cell + 75, 0)
            }
        }
        return buffer
    }

    private companion object {
        private const val TAG = "ArchpheneTerminalA11y"
        private const val AUTOMATIC_TEXT_SIZE = 0
        private const val ROWS = 8
        private const val COLUMNS = 40
        private const val CURSOR_ROW = 4
        private const val CURSOR_COLUMN = 2
        private const val DAMAGE_MAGIC = 0x4d525441
        private const val DAMAGE_VERSION = 7
        private const val DAMAGE_HEADER_SIZE = 52
        private const val DAMAGE_CELL_SIZE = 76
        private const val CURSOR_VISIBLE_FLAG = 1
        private const val CURSOR_COLOR = 0x7dd3fc
        private const val DEFAULT_FOREGROUND = 7
        private const val DEFAULT_BACKGROUND = 0
        private const val ACCESSIBILITY_POLL_MILLIS = 100L
        private const val MAX_ACCESSIBILITY_POLLS = 300
        private val CONTENT =
            arrayOf(
                "Archphene terminal screen reader",
                "",
                "$ cargo --version",
                "cargo 1.88.0",
                "$ ",
            )
    }
}
