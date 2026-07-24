package org.archphene.app

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.SurfaceView
import org.archphene.app.runtime.InputBatch
import org.archphene.app.runtime.NativeRuntime

internal class RuntimeSurfaceView(context: Context) : SurfaceView(context) {
    private val inputBatch = InputBatch()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(31, 35, 38))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> InputBatch.KIND_TOUCH_DOWN
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
        return true
    }

    fun flushInput(handle: Long) {
        val submitted = inputBatch.flush(handle)
        if (submitted > 0) {
            NativeRuntime.nativeDrainInput(handle, submitted)
        }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
