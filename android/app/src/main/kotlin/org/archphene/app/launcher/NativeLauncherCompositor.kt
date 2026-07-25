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
) : AutoCloseable {
    private var handle =
        nativeCreate(
            socketPath.toByteArray(StandardCharsets.UTF_8),
            width,
            height,
        )

    init {
        check(handle != 0L) { "Could not create launcher compositor" }
    }

    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
    ): Boolean = nativeAttachSurface(handle, surface, width, height) == 0

    fun detach() {
        if (handle != 0L) {
            nativeDetachSurface(handle)
        }
    }

    fun setHostActive(active: Boolean) {
        if (handle != 0L) {
            nativeSetHostActive(handle, active)
        }
    }

    fun setClipboardActive(active: Boolean) {
        if (handle != 0L) {
            nativeSetClipboardActive(handle, active)
        }
    }

    fun offerAndroidClipboardText(): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeOfferAndroidClipboardText(handle)
        }

    fun clearAndroidClipboard(): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeClearAndroidClipboard(handle)
        }

    fun takeAndroidPasteFd(): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeAndroidPasteFd(handle)
        }

    fun takeLinuxCopyFd(): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeTakeLinuxCopyFd(handle)
        }

    fun takeLinuxClipboardClear(): Boolean =
        handle != 0L && nativeTakeLinuxClipboardClear(handle)

    fun readClipboardFd(
        descriptor: Int,
        output: ByteBuffer,
        capacity: Int,
        timeoutMillis: Int,
    ): Int = nativeReadClipboardFd(descriptor, output, capacity, timeoutMillis)

    fun writeClipboardFd(
        descriptor: Int,
        input: ByteBuffer,
        length: Int,
        timeoutMillis: Int,
    ): Int = nativeWriteClipboardFd(descriptor, input, length, timeoutMillis)

    /**
     * Dispatches pending Wayland traffic and presents only when a new surface
     * commit is available. The return flags are stable for diagnostics.
     */
    fun dispatchAndPresent(timeMillis: Int): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeDispatchAndPresent(handle, timeMillis)
        }

    fun submitInput(
        input: ByteBuffer,
        recordCount: Int,
    ): Int =
        if (handle == 0L) {
            RESULT_CLOSED
        } else {
            nativeInputBatch(handle, input, recordCount)
        }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            nativeDestroy(current)
        }
    }

    private external fun nativeCreate(
        socketPath: ByteArray,
        width: Int,
        height: Int,
    ): Long

    private external fun nativeAttachSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
    ): Int

    private external fun nativeDetachSurface(handle: Long)

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
