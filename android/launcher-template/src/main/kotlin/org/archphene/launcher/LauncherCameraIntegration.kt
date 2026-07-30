package org.archphene.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded Camera2 capture owned by one generated launcher UID.
 *
 * The streaming path preallocates three I420 frames and one wire header. When
 * the consumer is slower than Camera2, the newest complete frame replaces the
 * pending frame instead of allocating or growing a queue.
 */
internal class LauncherCameraIntegration(
    private val activity: Activity,
) : Closeable {
    internal data class CaptureResult(
        val width: Int,
        val height: Int,
        val bytes: Int,
    )

    private class FrameWriter(
        destination: FileDescriptor,
    ) : Closeable {
        private val destination = ParcelFileDescriptor.dup(destination)
        private val buffers = Array(STREAM_BUFFER_COUNT) { ByteArray(STREAM_FRAME_BYTES) }
        private val header = ByteArray(STREAM_HEADER_BYTES)
        private val done = CountDownLatch(1)
        private val thread = Thread(::writeLoop, "ArchpheneCameraFrames")
        private val monitor = Object()
        private var pending = -1
        private var writing = -1
        private var closed = false
        private var sequence = 0

        init {
            thread.start()
        }

        fun acquire(): ByteArray? =
            synchronized(monitor) {
                if (closed) return@synchronized null
                for (index in buffers.indices) {
                    if (index != pending && index != writing) {
                        return@synchronized buffers[index]
                    }
                }
                null
            }

        fun submit(buffer: ByteArray) {
            synchronized(monitor) {
                if (closed) return
                val index = buffers.indexOfFirst { candidate -> candidate === buffer }
                require(index >= 0 && index != writing) { "Camera frame is not writer-owned" }
                pending = index
                monitor.notifyAll()
            }
        }

        fun await() {
            done.await()
        }

        private fun writeLoop() {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    while (true) {
                        val index =
                            synchronized(monitor) {
                                while (!closed && pending < 0) {
                                    monitor.wait()
                                }
                                if (closed) return
                                pending.also {
                                    pending = -1
                                    writing = it
                                }
                            }
                        writeHeader(output, sequence++, System.nanoTime())
                        output.write(buffers[index])
                        output.flush()
                        synchronized(monitor) {
                            if (writing == index) writing = -1
                        }
                    }
                }
            } catch (_: IOException) {
                Log.i(TAG, "Camera frame consumer disconnected")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(monitor) {
                    closed = true
                    pending = -1
                    writing = -1
                    monitor.notifyAll()
                }
                done.countDown()
            }
        }

        private fun writeHeader(
            output: OutputStream,
            frameSequence: Int,
            timestampNanos: Long,
        ) {
            header[0] = 'A'.code.toByte()
            header[1] = 'P'.code.toByte()
            header[2] = 'C'.code.toByte()
            header[3] = 'F'.code.toByte()
            putInt(header, 4, STREAM_VERSION)
            putInt(header, 8, STREAM_WIDTH)
            putInt(header, 12, STREAM_HEIGHT)
            putInt(header, 16, STREAM_FORMAT_I420)
            putInt(header, 20, frameSequence)
            putInt(header, 24, STREAM_FRAME_BYTES)
            putLong(header, 28, timestampNanos)
            output.write(header)
        }

        override fun close() {
            synchronized(monitor) {
                if (closed) return
                closed = true
                pending = -1
                monitor.notifyAll()
            }
            kotlin.runCatching { destination.close() }
            thread.interrupt()
        }

        private companion object {
            fun putInt(
                output: ByteArray,
                offset: Int,
                value: Int,
            ) {
                output[offset] = value.toByte()
                output[offset + 1] = (value ushr 8).toByte()
                output[offset + 2] = (value ushr 16).toByte()
                output[offset + 3] = (value ushr 24).toByte()
            }

            fun putLong(
                output: ByteArray,
                offset: Int,
                value: Long,
            ) {
                for (index in 0 until Long.SIZE_BYTES) {
                    output[offset + index] = (value ushr (index * 8)).toByte()
                }
            }
        }
    }

    private val closed = AtomicBoolean()
    private val activeStream = AtomicReference<FrameWriter?>()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun captureJpeg(
        destination: FileDescriptor,
        requestedWidth: Int,
        requestedHeight: Int,
        frontFacing: Boolean,
    ): CaptureResult {
        checkOpen()
        require(
            requestedWidth in 1..MAX_DIMENSION &&
                requestedHeight in 1..MAX_DIMENSION,
        ) {
            "Camera dimensions are invalid"
        }
        val manager =
            activity.getSystemService(CameraManager::class.java)
                ?: throw IOException("Android camera service is unavailable")
        val cameraId = selectCamera(manager, frontFacing)
        val size =
            selectJpegSize(
                manager.getCameraCharacteristics(cameraId),
                requestedWidth,
                requestedHeight,
            )
        val thread = HandlerThread("ArchpheneCameraCapture").apply { start() }
        val handler = Handler(thread.looper)
        val executor = cameraExecutor(handler)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<CaptureResult?>()
        val camera = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        reader.setOnImageAvailableListener(
            { source ->
                try {
                    source.acquireNextImage()?.use { image ->
                        val buffer = image.planes[0].buffer
                        val bytes = buffer.remaining()
                        if (bytes !in 1..MAX_JPEG_BYTES) {
                            throw IOException("Camera JPEG size is invalid")
                        }
                        val jpeg = ByteArray(bytes)
                        buffer.get(jpeg)
                        val duplicate = ParcelFileDescriptor.dup(destination)
                        ParcelFileDescriptor.AutoCloseOutputStream(duplicate).use { output ->
                            output.write(jpeg)
                            output.flush()
                        }
                        result.set(CaptureResult(size.width, size.height, bytes))
                    } ?: throw IOException("Camera returned no image")
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    finished.countDown()
                }
            },
            handler,
        )
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera.set(device)
                        val surface = reader.surface
                        runCatching {
                            device.createCaptureSession(
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(surface)),
                                    executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        session.set(configured)
                                        runCatching {
                                            val request =
                                                device.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_STILL_CAPTURE,
                                                )
                                            request.addTarget(surface)
                                            request.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                            )
                                            configured.capture(
                                                request.build(),
                                                object :
                                                    CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureFailed(
                                                        session: CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        failureResult: CaptureFailure,
                                                    ) {
                                                        failure.compareAndSet(
                                                            null,
                                                            IOException(
                                                                "Camera capture failed: " +
                                                                    failureResult.reason,
                                                            ),
                                                        )
                                                        finished.countDown()
                                                    }

                                                    override fun onCaptureCompleted(
                                                        session: CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        result: TotalCaptureResult,
                                                    ) = Unit
                                                },
                                                handler,
                                            )
                                        }.onFailure { error ->
                                            failure.compareAndSet(null, error)
                                            finished.countDown()
                                        }
                                    }

                                    override fun onConfigureFailed(
                                        session: CameraCaptureSession,
                                    ) {
                                        session.close()
                                        failure.compareAndSet(
                                            null,
                                            IOException("Camera session configuration failed"),
                                        )
                                        finished.countDown()
                                    }
                                },
                                ),
                            )
                        }.onFailure { error ->
                            failure.compareAndSet(null, error)
                            finished.countDown()
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        failure.compareAndSet(null, IOException("Camera disconnected"))
                        finished.countDown()
                    }

                    override fun onError(
                        device: CameraDevice,
                        error: Int,
                    ) {
                        device.close()
                        failure.compareAndSet(null, IOException("Camera error $error"))
                        finished.countDown()
                    }
                },
                handler,
            )
            if (!finished.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting for Android camera capture")
            }
            failure.get()?.let(::throwCameraFailure)
            return result.get() ?: throw IOException("Camera produced no JPEG")
        } finally {
            session.get()?.close()
            camera.get()?.close()
            reader.close()
            thread.quitSafely()
            thread.join(THREAD_STOP_MILLIS)
        }
    }

    @SuppressLint("MissingPermission")
    fun streamI420(
        destination: FileDescriptor,
        requestedWidth: Int,
        requestedHeight: Int,
        frontFacing: Boolean,
    ) {
        checkOpen()
        require(requestedWidth == STREAM_WIDTH && requestedHeight == STREAM_HEIGHT) {
            "Camera stream currently requires ${STREAM_WIDTH}x$STREAM_HEIGHT"
        }
        val manager =
            activity.getSystemService(CameraManager::class.java)
                ?: throw IOException("Android camera service is unavailable")
        val cameraId = selectCamera(manager, frontFacing)
        val size =
            selectYuvSize(
                manager.getCameraCharacteristics(cameraId),
                requestedWidth,
                requestedHeight,
            )
        val writer = FrameWriter(destination)
        if (!activeStream.compareAndSet(null, writer)) {
            writer.close()
            throw IOException("Another camera stream is active")
        }
        val thread = HandlerThread("ArchpheneCameraStream").apply { start() }
        val handler = Handler(thread.looper)
        val executor = cameraExecutor(handler)
        val reader =
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        val configured = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val camera = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        reader.setOnImageAvailableListener(
            { source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        writer.acquire()?.let { frame ->
                            packI420(image, frame)
                            writer.submit(frame)
                        }
                    }
                } catch (error: Throwable) {
                    if (failure.compareAndSet(null, error)) {
                        Log.w(TAG, "Could not pack Android camera frame", error)
                    }
                    writer.close()
                }
            },
            handler,
        )
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera.set(device)
                        val surface = reader.surface
                        runCatching {
                            device.createCaptureSession(
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(surface)),
                                    executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(value: CameraCaptureSession) {
                                        session.set(value)
                                        runCatching {
                                            val request =
                                                device.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_RECORD,
                                                )
                                            request.addTarget(surface)
                                            request.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                            )
                                            value.setRepeatingRequest(
                                                request.build(),
                                                null,
                                                handler,
                                            )
                                        }.onFailure { error ->
                                            failure.compareAndSet(null, error)
                                        }
                                        configured.countDown()
                                    }

                                    override fun onConfigureFailed(
                                        session: CameraCaptureSession,
                                    ) {
                                        session.close()
                                        failure.compareAndSet(
                                            null,
                                            IOException("Camera stream configuration failed"),
                                        )
                                        configured.countDown()
                                    }
                                },
                                ),
                            )
                        }.onFailure { error ->
                            failure.compareAndSet(null, error)
                            configured.countDown()
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        failure.compareAndSet(null, IOException("Camera disconnected"))
                        configured.countDown()
                        writer.close()
                    }

                    override fun onError(
                        device: CameraDevice,
                        error: Int,
                    ) {
                        device.close()
                        failure.compareAndSet(null, IOException("Camera error $error"))
                        configured.countDown()
                        writer.close()
                    }
                },
                handler,
            )
            if (!configured.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Timed out configuring Android camera stream")
            }
            failure.get()?.let(::throwCameraFailure)
            writer.await()
        } finally {
            session.get()?.close()
            camera.get()?.close()
            reader.close()
            writer.close()
            activeStream.compareAndSet(writer, null)
            thread.quitSafely()
            thread.join(THREAD_STOP_MILLIS)
        }
    }

    override fun close() {
        closed.set(true)
        activeStream.getAndSet(null)?.close()
    }

    fun stopStream() {
        activeStream.get()?.close()
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("Camera integration is closed")
    }

    private fun packI420(
        image: Image,
        output: ByteArray,
    ) {
        val width = image.width
        val height = image.height
        if (
            width != STREAM_WIDTH ||
            height != STREAM_HEIGHT ||
            output.size != STREAM_FRAME_BYTES ||
            image.planes.size != 3
        ) {
            throw IOException("Camera returned an invalid stream frame")
        }
        copyPlane(image.planes[0], width, height, output, 0)
        copyPlane(image.planes[1], width / 2, height / 2, output, width * height)
        copyPlane(
            image.planes[2],
            width / 2,
            height / 2,
            output,
            width * height + STREAM_CHROMA_BYTES,
        )
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        initialOffset: Int,
    ) {
        val buffer = plane.buffer
        val start = buffer.position()
        val limit = buffer.limit()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputOffset = initialOffset
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = start + row * rowStride + column * pixelStride
                if (index !in start until limit || outputOffset !in output.indices) {
                    throw IOException("Camera plane exceeds its buffer")
                }
                output[outputOffset++] = buffer.get(index)
            }
        }
    }

    private fun selectCamera(
        manager: CameraManager,
        frontFacing: Boolean,
    ): String {
        val preferred =
            if (frontFacing) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (fallback == null) fallback = id
            if (facing == preferred) return id
        }
        return fallback ?: throw IOException("Android device has no camera")
    }

    private fun selectYuvSize(
        characteristics: CameraCharacteristics,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Size {
        val sizes =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
        return sizes
            ?.firstOrNull { candidate ->
                candidate.width == requestedWidth && candidate.height == requestedHeight
            }
            ?: throw IOException(
                "Camera does not provide required ${requestedWidth}x$requestedHeight YUV output",
            )
    }

    private fun selectJpegSize(
        characteristics: CameraCharacteristics,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Size {
        val sizes =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Camera does not provide JPEG output")
        return sizes
            .filter { candidate ->
                candidate.width <= requestedWidth && candidate.height <= requestedHeight
            }.maxByOrNull(::area)
            ?: sizes.minBy(::area)
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()

    private fun cameraExecutor(handler: Handler): Executor =
        Executor { command ->
            if (!handler.post(command)) {
                throw RejectedExecutionException("Camera handler has stopped")
            }
        }

    private fun throwCameraFailure(error: Throwable): Nothing {
        if (error is Exception) throw error
        throw IOException("Android camera operation failed", error)
    }

    private companion object {
        private const val TAG = "ArchpheneCamera"
        private const val CAPTURE_TIMEOUT_SECONDS = 20L
        private const val THREAD_STOP_MILLIS = 2_000L
        private const val MAX_JPEG_BYTES = 32 * 1024 * 1024
        private const val MAX_DIMENSION = 8_192
        private const val STREAM_WIDTH = 640
        private const val STREAM_HEIGHT = 480
        private const val STREAM_FORMAT_I420 = 1
        private const val STREAM_VERSION = 1
        private const val STREAM_HEADER_BYTES = 36
        private const val STREAM_CHROMA_BYTES = (STREAM_WIDTH / 2) * (STREAM_HEIGHT / 2)
        private const val STREAM_FRAME_BYTES =
            STREAM_WIDTH * STREAM_HEIGHT + 2 * STREAM_CHROMA_BYTES
        private const val STREAM_BUFFER_COUNT = 3
    }
}
