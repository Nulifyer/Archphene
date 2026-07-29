package org.archphene.app.launcher

import android.view.Surface
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.archphene.app.performance.PerformanceMetrics

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
        socketPath.toByteArray(StandardCharsets.UTF_8).let { socketBytes ->
            nativeCreate(
                socketBytes,
                width,
                height,
                densityDpi,
                geometryPercent,
            ).also {
                PerformanceMetrics.recordCompositorJni(
                    arrayCopyBytes = socketBytes.size,
                )
            }
        }

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
        val result =
            nativeAttachSurface(
                current,
                surface,
                width,
                height,
                densityDpi,
                geometryPercent,
            )
        PerformanceMetrics.recordCompositorJni()
        return result == 0
    }

    fun detach() {
        val current = ownerHandle()
        if (current != 0L) {
            nativeDetachSurface(current)
            PerformanceMetrics.recordCompositorJni()
        }
    }

    fun requestClose(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeRequestClose(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun setHostActive(active: Boolean) {
        val current = ownerHandle()
        if (current != 0L) {
            nativeSetHostActive(current, active)
            PerformanceMetrics.recordCompositorJni()
        }
    }

    fun setClipboardActive(active: Boolean) {
        val current = ownerHandle()
        if (current != 0L) {
            nativeSetClipboardActive(current, active)
            PerformanceMetrics.recordCompositorJni()
        }
    }

    fun offerAndroidClipboardText(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeOfferAndroidClipboardText(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun clearAndroidClipboard(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeClearAndroidClipboard(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun takeAndroidPasteFd(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeAndroidPasteFd(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun takeLinuxCopyFd(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeLinuxCopyFd(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun takeLinuxClipboardClear(): Boolean {
        val current = ownerHandle()
        if (current == 0L) {
            return false
        }
        val result = nativeTakeLinuxClipboardClear(current)
        PerformanceMetrics.recordCompositorJni()
        return result
    }

    fun imeChangeSerial(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeChangeSerial(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun imeActive(): Boolean {
        val current = ownerHandle()
        if (current == 0L) {
            return false
        }
        val result = nativeImeActive(current)
        PerformanceMetrics.recordCompositorJni()
        return result
    }

    fun pointerCaptureActive(): Boolean {
        val current = ownerHandle()
        if (current == 0L) {
            return false
        }
        val result = nativePointerCaptureActive(current)
        PerformanceMetrics.recordCompositorJni()
        return result
    }

    fun imeSurroundingTextLength(): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeSurroundingTextLength(current).also {
                PerformanceMetrics.recordCompositorJni()
            }
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
            nativeCopyImeSurroundingText(current, output, capacity).also { result ->
                PerformanceMetrics.recordCompositorJni(
                    directOutputBytes = result.coerceAtLeast(0),
                )
            }
        }
    }

    fun imeStateComponent(component: Int): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeImeStateComponent(current, component).also {
                PerformanceMetrics.recordCompositorJni()
            }
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
            nativeImeText(current, operation, input, length, cursorBegin, cursorEnd).also {
                PerformanceMetrics.recordCompositorJni(
                    directInputBytes = length,
                )
            }
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
            nativeImeDeleteSurrounding(current, beforeBytes, afterBytes).also {
                PerformanceMetrics.recordCompositorJni()
            }
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
            nativeImeEditorAction(current, action, timeMillis).also {
                PerformanceMetrics.recordCompositorJni()
            }
        }
    }

    fun readClipboardFd(
        descriptor: Int,
        output: ByteBuffer,
        capacity: Int,
        timeoutMillis: Int,
    ): Int {
        requireClipboardThread()
        return nativeReadClipboardFd(descriptor, output, capacity, timeoutMillis).also { result ->
            PerformanceMetrics.recordCompositorJni(
                directOutputBytes = result.coerceAtLeast(0),
            )
        }
    }

    fun writeClipboardFd(
        descriptor: Int,
        input: ByteBuffer,
        length: Int,
        timeoutMillis: Int,
    ): Int {
        requireClipboardThread()
        return nativeWriteClipboardFd(descriptor, input, length, timeoutMillis).also {
            PerformanceMetrics.recordCompositorJni(
                directInputBytes = length,
            )
        }
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
            nativeDispatchAndPresent(current, timeMillis).also {
                PerformanceMetrics.recordCompositorJni(
                    kind = PerformanceMetrics.COMPOSITOR_DISPATCH,
                )
            }
        }
    }

    fun copyPresentationSnapshot(output: ByteBuffer): Boolean {
        val current = ownerHandle()
        if (current == 0L) {
            return false
        }
        val result = nativeCopyPresentationSnapshot(current, output)
        PerformanceMetrics.recordCompositorJni(
            directOutputBytes = result.coerceAtLeast(0) * Int.SIZE_BYTES,
            kind = PerformanceMetrics.COMPOSITOR_SNAPSHOT,
        )
        return result == PRESENTATION_COMPONENTS
    }

    fun submitInput(
        input: ByteBuffer,
        recordCount: Int,
    ): Int {
        val current = ownerHandle()
        return if (current == 0L) {
            RESULT_CLOSED
        } else {
            nativeInputBatch(current, input, recordCount).also {
                PerformanceMetrics.recordCompositorJni(
                    directInputBytes = recordCount * INPUT_RECORD_BYTES,
                    kind = PerformanceMetrics.COMPOSITOR_INPUT,
                )
            }
        }
    }

    override fun close() {
        val current = ownerHandle()
        handle = 0L
        if (current != 0L) {
            nativeDestroy(current)
            PerformanceMetrics.recordCompositorJni()
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

    private external fun nativePointerCaptureActive(handle: Long): Boolean

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

    private external fun nativeCopyPresentationSnapshot(
        handle: Long,
        output: ByteBuffer,
    ): Int

    private external fun nativeInputBatch(
        handle: Long,
        input: ByteBuffer,
        recordCount: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val INPUT_RECORD_BYTES = 6 * Int.SIZE_BYTES
        const val PRESENTATION_COMPONENTS = 32
        const val RESULT_CLOSED = -1
        const val FLAG_CLIENT_CONNECTED = 1
        const val FLAG_FRAME_PRESENTED = 1 shl 1
        const val FLAG_PRESENTATION_CHANGED = 1 shl 2
        const val FLAG_LINUX_CLIPBOARD_CLEAR = 1 shl 3
        const val FLAG_LINUX_COPY_PENDING = 1 shl 4
        const val FLAG_ANDROID_PASTE_PENDING = 1 shl 5
        const val FLAG_IME_CHANGED = 1 shl 6
        const val FLAG_POINTER_CAPTURE_CHANGED = 1 shl 7

        init {
            System.loadLibrary("archphene_compositor")
        }
    }
}
