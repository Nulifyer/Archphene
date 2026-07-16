package org.archphene.bridge;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded Camera2 capture and latest-frame streaming for the Linux capability broker. */
final class AndroidCameraIntegration implements Closeable {
    private static final String TAG = "ArchpheneCamera";
    private static final long CAPTURE_TIMEOUT_SECONDS = 20;
    private static final int STREAM_HEADER_BYTES = 36;
    private static final int MAX_STREAM_BYTES = 32 * 1024 * 1024;
    private static final int MAX_JPEG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;

    static final class CaptureResult {
        final int width;
        final int height;
        final int bytes;

        CaptureResult(int width, int height, int bytes) {
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }

    private static final class FrameWriter implements Closeable {
        private final ParcelFileDescriptor destination;
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private byte[] pending;
        private boolean closed;
        private int sequence;

        FrameWriter(FileDescriptor destination) throws IOException {
            this.destination = ParcelFileDescriptor.dup(destination);
            thread = new Thread(this::writeLoop, "archphene-camera-frames");
            thread.start();
        }

        synchronized void submit(byte[] frame) {
            if (closed) return;
            pending = frame;
            notifyAll();
        }

        void await() throws InterruptedException {
            done.await();
        }

        private void writeLoop() {
            try (OutputStream output =
                    new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
                while (true) {
                    byte[] frame;
                    synchronized (this) {
                        while (!closed && pending == null) wait();
                        if (closed) return;
                        frame = pending;
                        pending = null;
                    }
                    ByteBuffer header = ByteBuffer.allocate(STREAM_HEADER_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    header.put((byte) 'A').put((byte) 'P').put((byte) 'C').put((byte) 'F');
                    header.putInt(1);
                    header.putInt(640);
                    header.putInt(480);
                    header.putInt(1);
                    header.putInt(sequence++);
                    header.putInt(frame.length);
                    header.putLong(System.nanoTime());
                    output.write(header.array());
                    output.write(frame);
                    output.flush();
                }
            } catch (IOException error) {
                Log.i(TAG, "Camera frame consumer disconnected");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (this) {
                    closed = true;
                    pending = null;
                    notifyAll();
                }
                done.countDown();
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                closed = true;
                pending = null;
                notifyAll();
            }
            try {
                destination.close();
            } catch (IOException ignored) {
                // The writer already owns descriptor cleanup.
            }
            thread.interrupt();
        }
    }

    private final Activity activity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<FrameWriter> activeStream = new AtomicReference<>();

    AndroidCameraIntegration(Activity activity) {
        this.activity = activity;
    }

    synchronized CaptureResult captureJpeg(FileDescriptor destination, int requestedWidth,
            int requestedHeight, boolean frontFacing) throws Exception {
        if (closed.get()) throw new IOException("Camera integration is closed");
        if (requestedWidth < 1 || requestedHeight < 1
                || requestedWidth > MAX_DIMENSION || requestedHeight > MAX_DIMENSION) {
            throw new IllegalArgumentException("Camera dimensions are invalid");
        }
        CameraManager manager = activity.getSystemService(CameraManager.class);
        if (manager == null) throw new IOException("Android camera service is unavailable");
        String cameraId = selectCamera(manager, frontFacing);
        Size size = selectJpegSize(manager.getCameraCharacteristics(cameraId),
                requestedWidth, requestedHeight);
        HandlerThread thread = new HandlerThread("archphene-camera");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        ImageReader reader = ImageReader.newInstance(
                size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        AtomicReference<CameraDevice> camera = new AtomicReference<>();
        AtomicReference<CameraCaptureSession> session = new AtomicReference<>();
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireNextImage()) {
                if (image == null) throw new IOException("Camera returned no image");
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                int bytes = buffer.remaining();
                if (bytes < 1 || bytes > MAX_JPEG_BYTES) {
                    throw new IOException("Camera JPEG size is invalid");
                }
                byte[] jpeg = new byte[bytes];
                buffer.get(jpeg);
                ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(destination);
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(duplicate)) {
                    output.write(jpeg);
                    output.flush();
                }
                result.set(new CaptureResult(size.getWidth(), size.getHeight(), bytes));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                finished.countDown();
            }
        }, handler);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera.set(device);
                    Surface surface = reader.getSurface();
                    try {
                        device.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession configured) {
                                        session.set(configured);
                                        try {
                                            CaptureRequest.Builder request = device.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            request.addTarget(surface);
                                            request.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            configured.capture(request.build(),
                                                    new CameraCaptureSession.CaptureCallback() {
                                                        @Override
                                                        public void onCaptureFailed(
                                                                CameraCaptureSession ignored,
                                                                CaptureRequest captureRequest,
                                                                CaptureFailure captureFailure) {
                                                            failure.compareAndSet(null,
                                                                    new IOException("Camera capture failed: "
                                                                            + captureFailure.getReason()));
                                                            finished.countDown();
                                                        }

                                                        @Override
                                                        public void onCaptureCompleted(
                                                                CameraCaptureSession ignored,
                                                                CaptureRequest captureRequest,
                                                                TotalCaptureResult captureResult) {
                                                            // ImageReader completes the operation.
                                                        }
                                                    }, handler);
                                        } catch (Throwable error) {
                                            failure.compareAndSet(null, error);
                                            finished.countDown();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession ignored) {
                                        failure.compareAndSet(null,
                                                new IOException("Camera session configuration failed"));
                                        finished.countDown();
                                    }
                                }, handler);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        finished.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    failure.compareAndSet(null, new IOException("Camera disconnected"));
                    finished.countDown();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    failure.compareAndSet(null, new IOException("Camera error " + error));
                    finished.countDown();
                }
            }, handler);
            if (!finished.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for Android camera capture");
            }
            Throwable error = failure.get();
            if (error != null) {
                if (error instanceof Exception) throw (Exception) error;
                throw new IOException("Android camera capture failed", error);
            }
            CaptureResult captured = result.get();
            if (captured == null) throw new IOException("Camera produced no JPEG");
            return captured;
        } finally {
            CameraCaptureSession currentSession = session.get();
            if (currentSession != null) currentSession.close();
            CameraDevice currentCamera = camera.get();
            if (currentCamera != null) currentCamera.close();
            reader.close();
            thread.quitSafely();
            thread.join(2000);
        }
    }

    void streamI420(FileDescriptor destination, int requestedWidth,
            int requestedHeight, boolean frontFacing) throws Exception {
        if (closed.get()) throw new IOException("Camera integration is closed");
        if (requestedWidth != 640 || requestedHeight != 480) {
            throw new IllegalArgumentException("Camera stream currently requires 640x480");
        }
        CameraManager manager = activity.getSystemService(CameraManager.class);
        if (manager == null) throw new IOException("Android camera service is unavailable");
        String cameraId = selectCamera(manager, frontFacing);
        Size size = selectYuvSize(manager.getCameraCharacteristics(cameraId),
                requestedWidth, requestedHeight);
        FrameWriter writer = new FrameWriter(destination);
        if (!activeStream.compareAndSet(null, writer)) {
            writer.close();
            throw new IOException("Another camera stream is active");
        }
        HandlerThread thread = new HandlerThread("archphene-camera-stream");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        ImageReader reader = ImageReader.newInstance(
                size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
        CountDownLatch configured = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<CameraDevice> camera = new AtomicReference<>();
        AtomicReference<CameraCaptureSession> session = new AtomicReference<>();
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image != null) writer.submit(packI420(image));
            } catch (Throwable error) {
                if (failure.compareAndSet(null, error)) {
                    Log.w(TAG, "Could not pack Android camera frame", error);
                }
            }
        }, handler);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera.set(device);
                    Surface surface = reader.getSurface();
                    try {
                        device.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession value) {
                                        session.set(value);
                                        try {
                                            CaptureRequest.Builder request =
                                                    device.createCaptureRequest(
                                                            CameraDevice.TEMPLATE_RECORD);
                                            request.addTarget(surface);
                                            request.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                            value.setRepeatingRequest(request.build(), null, handler);
                                        } catch (Throwable error) {
                                            failure.compareAndSet(null, error);
                                        } finally {
                                            configured.countDown();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession ignored) {
                                        failure.compareAndSet(null,
                                                new IOException(
                                                        "Camera stream configuration failed"));
                                        configured.countDown();
                                    }
                                }, handler);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        configured.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    failure.compareAndSet(null, new IOException("Camera disconnected"));
                    configured.countDown();
                    writer.close();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    failure.compareAndSet(null, new IOException("Camera error " + error));
                    configured.countDown();
                    writer.close();
                }
            }, handler);
            if (!configured.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out configuring Android camera stream");
            }
            Throwable setupFailure = failure.get();
            if (setupFailure != null) {
                if (setupFailure instanceof Exception) throw (Exception) setupFailure;
                throw new IOException("Android camera stream failed", setupFailure);
            }
            writer.await();
        } finally {
            CameraCaptureSession currentSession = session.get();
            if (currentSession != null) currentSession.close();
            CameraDevice currentCamera = camera.get();
            if (currentCamera != null) currentCamera.close();
            reader.close();
            writer.close();
            activeStream.compareAndSet(writer, null);
            thread.quitSafely();
            thread.join(2000);
        }
    }

    private static byte[] packI420(Image image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        int bytes = width * height + 2 * ((width / 2) * (height / 2));
        if (width != 640 || height != 480 || bytes < 1 || bytes > MAX_STREAM_BYTES) {
            throw new IOException("Camera returned an invalid stream frame");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IOException("Camera YUV frame has invalid planes");
        byte[] result = new byte[bytes];
        copyPlane(planes[0], width, height, result, 0);
        int chroma = (width / 2) * (height / 2);
        copyPlane(planes[1], width / 2, height / 2, result, width * height);
        copyPlane(planes[2], width / 2, height / 2, result, width * height + chroma);
        return result;
    }

    private static void copyPlane(Image.Plane plane, int width, int height,
            byte[] output, int outputOffset) throws IOException {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int start = buffer.position();
        int limit = buffer.limit();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = start + row * rowStride + column * pixelStride;
                if (index < start || index >= limit || outputOffset >= output.length) {
                    throw new IOException("Camera plane exceeds its buffer");
                }
                output[outputOffset++] = buffer.get(index);
            }
        }
    }

    private static Size selectYuvSize(CameraCharacteristics characteristics,
            int requestedWidth, int requestedHeight) throws IOException {
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes != null) {
            for (Size candidate : sizes) {
                if (candidate.getWidth() == requestedWidth
                        && candidate.getHeight() == requestedHeight) return candidate;
            }
        }
        throw new IOException("Camera does not provide required 640x480 YUV output");
    }

    private static String selectCamera(CameraManager manager, boolean frontFacing)
            throws CameraAccessException, IOException {
        int preferred = frontFacing
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == preferred) return id;
        }
        if (fallback == null) throw new IOException("Android device has no camera");
        return fallback;
    }

    private static Size selectJpegSize(CameraCharacteristics characteristics,
            int requestedWidth, int requestedHeight) throws IOException {
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            throw new IOException("Camera does not provide JPEG output");
        }
        Size selected = null;
        for (Size candidate : sizes) {
            boolean fits = candidate.getWidth() <= requestedWidth
                    && candidate.getHeight() <= requestedHeight;
            if (fits && (selected == null || area(candidate) > area(selected))) {
                selected = candidate;
            }
        }
        if (selected != null) return selected;
        selected = sizes[0];
        for (Size candidate : sizes) {
            if (area(candidate) < area(selected)) selected = candidate;
        }
        return selected;
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    @Override
    public void close() {
        closed.set(true);
        FrameWriter writer = activeStream.getAndSet(null);
        if (writer != null) writer.close();
    }
}
