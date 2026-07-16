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
import android.util.Size;
import android.view.Surface;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded one-shot Camera2 capture for the Linux capability broker. */
final class AndroidCameraIntegration implements Closeable {
    private static final long CAPTURE_TIMEOUT_SECONDS = 20;
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

    private final Activity activity;
    private final AtomicBoolean closed = new AtomicBoolean();

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
    }
}
