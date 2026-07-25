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
