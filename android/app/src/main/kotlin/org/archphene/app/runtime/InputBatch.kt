package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class InputBatch {
    private val buffer =
        ByteBuffer.allocateDirect(EVENT_SIZE * MAX_EVENTS).order(ByteOrder.LITTLE_ENDIAN)

    var droppedEvents: Long = 0
        private set

    fun append(kind: Int, flags: Int, timeNanos: Long, argument0: Int, argument1: Int): Boolean {
        if (buffer.remaining() < EVENT_SIZE) {
            droppedEvents++
            return false
        }
        buffer.putInt(kind)
        buffer.putInt(flags)
        buffer.putLong(timeNanos)
        buffer.putInt(argument0)
        buffer.putInt(argument1)
        return true
    }

    fun flush(handle: Long): Int {
        val byteCount = buffer.position()
        if (byteCount == 0 || handle == 0L) {
            return 0
        }
        val result = NativeRuntime.nativeSubmitEvents(handle, buffer, byteCount)
        if (result >= 0) {
            buffer.clear()
        }
        return result
    }

    companion object {
        const val KIND_TOUCH_DOWN = 1
        const val KIND_TOUCH_MOVE = 2
        const val KIND_TOUCH_UP = 3
        const val KIND_TOUCH_CANCEL = 4

        private const val EVENT_SIZE = 24
        private const val MAX_EVENTS = 64
    }
}
