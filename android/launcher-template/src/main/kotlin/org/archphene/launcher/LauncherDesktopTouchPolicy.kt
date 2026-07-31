package org.archphene.launcher

import android.view.MotionEvent

internal object LauncherDesktopTouchPolicy {
    const val PASS = 0
    const val POINTER = 1
    const val CONSUME = 2
    const val CANCEL_POINTER = 3

    const val IDLE = 0
    const val ACTIVE = 1
    const val NATIVE_TOUCH = 2

    fun route(
        sequenceState: Int,
        actionMasked: Int,
    ): Int =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> POINTER
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            ->
                when (sequenceState) {
                    ACTIVE -> POINTER
                    NATIVE_TOUCH -> PASS
                    else -> PASS
                }
            MotionEvent.ACTION_POINTER_DOWN ->
                when (sequenceState) {
                    ACTIVE -> CANCEL_POINTER
                    NATIVE_TOUCH -> PASS
                    else -> PASS
                }
            MotionEvent.ACTION_POINTER_UP ->
                if (sequenceState == ACTIVE) CONSUME else PASS
            else -> PASS
        }

    fun stateAfter(
        sequenceState: Int,
        actionMasked: Int,
    ): Int =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> ACTIVE
            MotionEvent.ACTION_POINTER_DOWN ->
                if (sequenceState == ACTIVE) NATIVE_TOUCH else sequenceState
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> IDLE
            else -> sequenceState
        }

    fun beginsDrag(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Int,
    ): Boolean {
        if (!deltaX.isFinite() || !deltaY.isFinite() || touchSlop < 0) {
            return false
        }
        val limit = touchSlop.toFloat()
        return deltaX * deltaX + deltaY * deltaY > limit * limit
    }
}
