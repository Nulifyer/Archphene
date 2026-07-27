package org.archphene.app.launcher

import android.view.Surface
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Narrow Kotlin owner for one manager-side Wayland compositor.
 *
 * Every method is called from the launcher's dedicated compositor thread.
 * Rendered pixels stay native and are posted directly through ANativeWindow.
 */
internal class NativeLauncherCompositor(
    socketPath: String,
    width: Int,
    height: Int,
    densityDpi: Int,
    geometryPercent: Int,
) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private var handle =
        nativeCreate(
            socketPath.toByteArray(StandardCharsets.UTF_8),
            width,
            height,
            densityDpi,
            geometryPercent,
        )

    init {
        check(handle != 0L) { "Could not create launcher compositor" }
    }

    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometryPercent: Int,
    ): Boolean {
        val current = ownerHandle()
        return nativeAttachSurface(
            current,
            surface,
            width,
            height,
            densityDpi,
            geometryPercent,
        ) == 0
    }

    fun detach() {
        val current = ownerHandle()
        if (current != 0L) {
            nativeDetachSurface(current)
        }
    }

    fun requestClose(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeRequestClose(current)
        }
    }

    fun setHostActive(active: Boolean) {
        val current = ownerHandle()
        if (current != 0L) {
            nativeSetHostActive(current, active)
        }
    }

    fun setClipboardActive(active: Boolean) {
        val current = ownerHandle()
        if (current != 0L) {
            nativeSetClipboardActive(current, active)
        }
    }

    fun offerAndroidClipboardText(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeOfferAndroidClipboardText(current)
        }
    }

    fun clearAndroidClipboard(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeClearAndroidClipboard(current)
        }
    }

    fun takeAndroidPasteFd(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeAndroidPasteFd(current)
        }
    }

    fun takeLinuxCopyFd(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeLinuxCopyFd(current)
        }
    }

    fun takeLinuxClipboardClear(): Boolean {
        val current = ownerHandle()
        return current != 0L && nativeTakeLinuxClipboardClear(current)
    }

    fun imeChangeSerial(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeChangeSerial(current)
        }
    }

    fun imeActive(): Boolean {
        val current = ownerHandle()
        return current != 0L && nativeImeActive(current)
    }

    fun imeSurroundingTextLength(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeSurroundingTextLength(current)
        }
    }

    fun copyImeSurroundingText(
        output: ByteBuffer,
        capacity: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeCopyImeSurroundingText(current, output, capacity)
        }
    }

    fun imeStateComponent(component: Int): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeStateComponent(current, component)
        }
    }

    fun submitImeText(
        operation: Int,
        input: ByteBuffer,
        length: Int,
        cursorBegin: Int,
        cursorEnd: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeText(current, operation, input, length, cursorBegin, cursorEnd)
        }
    }

    fun deleteImeSurrounding(
        beforeBytes: Int,
        afterBytes: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeDeleteSurrounding(current, beforeBytes, afterBytes)
        }
    }

    fun submitImeEditorAction(
        action: Int,
        timeMillis: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeEditorAction(current, action, timeMillis)
        }
    }

    fun readClipboardFd(
        descriptor: Int,
        output: ByteBuffer,
        capacity: Int,
        timeoutMillis: Int,
    ): Int {
        requireClipboardThread()
        return nativeReadClipboardFd(descriptor, output, capacity, timeoutMillis)
    }

    fun writeClipboardFd(
        descriptor: Int,
        input: ByteBuffer,
        length: Int,
        timeoutMillis: Int,
    ): Int {
        requireClipboardThread()
        return nativeWriteClipboardFd(descriptor, input, length, timeoutMillis)
    }

    /**
     * Dispatches pending Wayland traffic and presents only when a new surface
     * commit is available. The return flags are stable for diagnostics.
     */
    fun dispatchAndPresent(timeMillis: Int): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeDispatchAndPresent(current, timeMillis)
        }
    }

    fun presentationComponent(component: Int): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativePresentationComponent(current, component)
        }
    }

    fun submitInput(
        input: ByteBuffer,
        recordCount: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeInputBatch(current, input, recordCount)
        }
    }

    override fun close() {
        val current = ownerHandle()
        handle = 0L
        if (current != 0L) {
            nativeDestroy(current)
        }
    }

    private fun ownerHandle(): Long {
        check(Thread.currentThread() === ownerThread) {
            "Launcher compositor state accessed outside its owner thread"
        }
        return handle
    }

    private fun requireClipboardThread() {
        check(Thread.currentThread() !== ownerThread) {
            "Blocking clipboard descriptor I/O must not run on the compositor thread"
        }
    }

    private external fun nativeCreate(
        socketPath: ByteArray,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometryPercent: Int,
    ): Long

    private external fun nativeAttachSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometryPercent: Int,
    ): Int

    private external fun nativeDetachSurface(handle: Long)

    private external fun nativeRequestClose(handle: Long): Int

    private external fun nativeSetHostActive(
        handle: Long,
        active: Boolean,
    )

    private external fun nativeSetClipboardActive(
        handle: Long,
        active: Boolean,
    )

    private external fun nativeOfferAndroidClipboardText(handle: Long): Int

    private external fun nativeClearAndroidClipboard(handle: Long): Int

    private external fun nativeTakeAndroidPasteFd(handle: Long): Int

    private external fun nativeTakeLinuxCopyFd(handle: Long): Int

    private external fun nativeTakeLinuxClipboardClear(handle: Long): Boolean

    private external fun nativeImeChangeSerial(handle: Long): Int

    private external fun nativeImeActive(handle: Long): Boolean

    private external fun nativeImeSurroundingTextLength(handle: Long): Int

    private external fun nativeCopyImeSurroundingText(
        handle: Long,
        output: ByteBuffer,
        capacity: Int,
    ): Int

    private external fun nativeImeStateComponent(
        handle: Long,
        component: Int,
    ): Int

    private external fun nativeImeText(
        handle: Long,
        operation: Int,
        input: ByteBuffer,
        length: Int,
        cursorBegin: Int,
        cursorEnd: Int,
    ): Int

    private external fun nativeImeDeleteSurrounding(
        handle: Long,
        beforeBytes: Int,
        afterBytes: Int,
    ): Int

    private external fun nativeImeEditorAction(
        handle: Long,
        action: Int,
        timeMillis: Int,
    ): Int

    private external fun nativeReadClipboardFd(
        descriptor: Int,
        output: ByteBuffer,
        capacity: Int,
        timeoutMillis: Int,
    ): Int

    private external fun nativeWriteClipboardFd(
        descriptor: Int,
        input: ByteBuffer,
        length: Int,
        timeoutMillis: Int,
    ): Int

    private external fun nativeDispatchAndPresent(
        handle: Long,
        timeMillis: Int,
    ): Int

    private external fun nativePresentationComponent(
        handle: Long,
        component: Int,
    ): Int

    private external fun nativeInputBatch(
        handle: Long,
        input: ByteBuffer,
        recordCount: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        const val RESULT_CLOSED = -1
        const val FLAG_CLIENT_CONNECTED = 1
        const val FLAG_FRAME_PRESENTED = 1 shl 1

        init {
            System.loadLibrary("archphene_compositor")
        }
    }
}
