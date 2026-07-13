package org.archphene.compositorprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    static { System.loadLibrary("archphene_compositor"); }

    private static native int nativeProtocolVersion();
    private static native long nativeCreateCore();
    private static native int nativeAdoptClient(long handle, int fd);
    private static native int nativeSendShmPoolRequest(
            int socketFd, int poolId, int poolSize, int callbackId);
    private static native int nativeDispatchOnce(long handle);
    private static native int nativeCompositorBindCount(long handle);
    private static native int nativeXdgWmBaseBindCount(long handle);
    private static native int nativeXdgSurfaceCount(long handle);
    private static native int nativeXdgToplevelCount(long handle);
    private static native int nativeXdgAckCount(long handle);
    private static native int nativeShmBindCount(long handle);
    private static native int nativeShmPoolCount(long handle);
    private static native int nativeShmBufferCount(long handle);
    private static native int nativeLastBufferChecksum(long handle);
    private static native int nativeSurfaceCount(long handle);
    private static native int nativeSurfaceCommitCount(long handle);
    private static native int nativeLastFrameWidth(long handle);
    private static native int nativeLastFrameHeight(long handle);
    private static native int nativeLastFrameChecksum(long handle);
    private static native int nativeCopyLastFrameToBitmap(long handle, Bitmap bitmap);
    private static native void nativeDestroyCore(long handle);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(24, 24, 24, 24);
        ImageView frameView = new ImageView(this);
        frameView.setBackgroundColor(Color.DKGRAY);
        frameView.setScaleType(ImageView.ScaleType.FIT_XY);
        frameView.setContentDescription("No committed Wayland frame");
        content.addView(frameView, new LinearLayout.LayoutParams(320, 160));
        TextView result = new TextView(this);
        result.setGravity(Gravity.CENTER);
        result.setTextSize(20);
        content.addView(result);
        setContentView(content);
        new Thread(() -> runProbe(result, frameView), "native-compositor-probe").start();
    }

    private void runProbe(TextView result, ImageView frameView) {
        String message;
        boolean passed = false;
        Bitmap renderedFrame = null;
        long core = 0;
        try {
            if (nativeProtocolVersion() != 1) throw new IllegalStateException("protocol version");
            core = nativeCreateCore();
            if (core == 0) throw new IllegalStateException("display creation");
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
            try (ParcelFileDescriptor client = pair[0];
                    FileInputStream input = new FileInputStream(client.getFileDescriptor());
                    FileOutputStream output = new FileOutputStream(client.getFileDescriptor())) {
                int serverFd = pair[1].detachFd();
                pair[1].close();
                if (nativeAdoptClient(core, serverFd) != 0) {
                    throw new IllegalStateException("client adoption");
                }

                output.write(getRegistryAndSyncRequest());
                output.flush();
                dispatch(core);

                RegistryGlobals globals = readGlobals(input, 3);
                output.write(bindGlobalAndSyncRequest(
                        globals.compositor, "wl_compositor", 4, 5));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 5);
                if (nativeCompositorBindCount(core) != 1) {
                    throw new IllegalStateException("wl_compositor bind was not dispatched");
                }

                output.write(bindGlobalAndSyncRequest(globals.shm, "wl_shm", 6, 7));
                output.flush();
                dispatch(core);
                readShmFormatsUntilCallback(input, 6, 7);
                if (nativeShmBindCount(core) != 1) {
                    throw new IllegalStateException("wl_shm bind was not dispatched");
                }

                output.write(createSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 9);
                if (nativeSurfaceCount(core) != 1) {
                    throw new IllegalStateException("wl_surface creation was not dispatched");
                }

                if (nativeSendShmPoolRequest(client.getFd(), 10, 40, 11) != 0) {
                    throw new IllegalStateException("SHM pool FD transfer");
                }
                dispatch(core);
                readUntilCallback(input, 11);
                if (nativeShmPoolCount(core) != 1) {
                    throw new IllegalStateException("wl_shm_pool creation was not dispatched");
                }

                output.write(createShmBufferAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 13);
                if (nativeShmBufferCount(core) != 1 || nativeLastBufferChecksum(core) != 820) {
                    throw new IllegalStateException("wl_buffer creation did not expose SHM pixels");
                }

                output.write(commitShmFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readFrameCommitUntilCallback(input, 12, 14, 15);
                if (nativeSurfaceCommitCount(core) != 1
                        || nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("wl_surface commit did not snapshot the SHM frame");
                }
                renderedFrame = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
                if (nativeCopyLastFrameToBitmap(core, renderedFrame) != 0
                        || renderedFrame.getPixel(0, 0) != 0xff030201
                        || renderedFrame.getPixel(0, 1) != 0xff1b1a19) {
                    throw new IllegalStateException("Android bitmap did not match the XRGB SHM frame");
                }

                output.write(destroyShmResourcesAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 16);
                if (nativeShmBufferCount(core) != 0 || nativeShmPoolCount(core) != 0) {
                    throw new IllegalStateException("SHM resources were not destroyed");
                }

                output.write(destroySurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 17);
                if (nativeSurfaceCount(core) != 0) {
                    throw new IllegalStateException("wl_surface destruction was not dispatched");
                }

                output.write(bindGlobalAndSyncRequest(
                        globals.xdgWmBase, "xdg_wm_base", 18, 19));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 19);
                if (nativeXdgWmBaseBindCount(core) != 1) {
                    throw new IllegalStateException("xdg_wm_base bind was not dispatched");
                }

                output.write(createSecondSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 21);

                output.write(createXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                int configureSerial = readXdgConfigureUntilCallback(input, 22, 23, 24);
                if (nativeXdgSurfaceCount(core) != 1 || nativeXdgToplevelCount(core) != 1) {
                    throw new IllegalStateException("xdg toplevel role was not constructed");
                }

                output.write(ackXdgConfigureAndSyncRequest(configureSerial));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 25);
                if (nativeXdgAckCount(core) != 1) {
                    throw new IllegalStateException("xdg configure serial was not acknowledged");
                }

                if (nativeSendShmPoolRequest(client.getFd(), 26, 40, 27) != 0) {
                    throw new IllegalStateException("second SHM pool FD transfer");
                }
                dispatch(core);
                readUntilCallback(input, 27);
                output.write(createSecondShmBufferAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 29);

                output.write(commitXdgFrameAndSyncRequest());
                output.flush();
                dispatch(core);
                readFrameCommitUntilCallback(input, 28, 30, 31);
                if (nativeSurfaceCommitCount(core) != 3
                        || nativeLastFrameWidth(core) != 4
                        || nativeLastFrameHeight(core) != 2
                        || nativeLastFrameChecksum(core) != 656) {
                    throw new IllegalStateException("configured xdg frame was not committed");
                }

                output.write(destroySecondShmResourcesAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 32);
                output.write(destroyXdgToplevelAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 33);
                if (nativeXdgSurfaceCount(core) != 0
                        || nativeXdgToplevelCount(core) != 0
                        || nativeSurfaceCount(core) != 0
                        || nativeShmBufferCount(core) != 0
                        || nativeShmPoolCount(core) != 0) {
                    throw new IllegalStateException("xdg destruction lifecycle failed");
                }
            }
            passed = true;
            message = "Native Wayland compositor passed\n"
                    + "registry, Android bitmap, and xdg toplevel lifecycle complete";
        } catch (Exception error) {
            message = "Native compositor probe failed\n" + error.getMessage();
        } finally {
            if (core != 0) nativeDestroyCore(core);
        }
        boolean finalPassed = passed;
        String finalMessage = message;
        Bitmap finalRenderedFrame = renderedFrame;
        Log.i("ArchpheneCompositorProbe", finalMessage.replace('\n', ' '));
        runOnUiThread(() -> {
            if (finalRenderedFrame != null) {
                frameView.setImageBitmap(finalRenderedFrame);
                frameView.setContentDescription("Committed Wayland XRGB frame");
            }
            result.setText(finalMessage);
            result.setContentDescription(finalPassed
                    ? "Native compositor probe passed"
                    : "Native compositor probe failed");
        });
    }

    private static byte[] getRegistryAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 1, 1, 12);
        request.putInt(2);
        putHeader(request, 1, 0, 12);
        request.putInt(3);
        return request.array();
    }

    private static byte[] bindGlobalAndSyncRequest(
            Global global, String interfaceValue, int objectId, int callbackId) {
        byte[] interfaceName = interfaceValue.getBytes(StandardCharsets.UTF_8);
        int stringLength = interfaceName.length + 1;
        int paddedLength = (stringLength + 3) & ~3;
        int bindSize = 8 + 4 + 4 + paddedLength + 4 + 4;
        ByteBuffer request = buffer(bindSize + 12);
        putHeader(request, 2, 0, bindSize);
        request.putInt(global.name);
        request.putInt(stringLength);
        request.put(interfaceName);
        request.put((byte) 0);
        while (request.position() < 8 + 4 + 4 + paddedLength) request.put((byte) 0);
        request.putInt(Math.min(global.version, 6));
        request.putInt(objectId);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] createSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(8);
        putHeader(request, 1, 0, 12);
        request.putInt(9);
        return request.array();
    }

    private static byte[] destroySurfaceAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 8, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(17);
        return request.array();
    }

    private static byte[] createSecondSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(20);
        putHeader(request, 1, 0, 12);
        request.putInt(21);
        return request.array();
    }

    private static byte[] createXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(48);
        putHeader(request, 18, 2, 16);
        request.putInt(22);
        request.putInt(20);
        putHeader(request, 22, 1, 12);
        request.putInt(23);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(24);
        return request.array();
    }

    private static byte[] ackXdgConfigureAndSyncRequest(int configureSerial) {
        ByteBuffer request = buffer(24);
        putHeader(request, 22, 4, 12);
        request.putInt(configureSerial);
        putHeader(request, 1, 0, 12);
        request.putInt(25);
        return request.array();
    }

    private static byte[] createSecondShmBufferAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 26, 0, 32);
        request.putInt(28);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        request.putInt(24);
        request.putInt(1);
        putHeader(request, 1, 0, 12);
        request.putInt(29);
        return request.array();
    }

    private static byte[] commitXdgFrameAndSyncRequest() {
        ByteBuffer request = buffer(76);
        putHeader(request, 20, 1, 20);
        request.putInt(28);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 20, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 20, 3, 12);
        request.putInt(30);
        putHeader(request, 20, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(31);
        return request.array();
    }

    private static byte[] destroySecondShmResourcesAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 28, 0, 8);
        putHeader(request, 26, 1, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(32);
        return request.array();
    }

    private static byte[] destroyXdgToplevelAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 23, 0, 8);
        putHeader(request, 22, 0, 8);
        putHeader(request, 20, 0, 8);
        putHeader(request, 18, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(33);
        return request.array();
    }

    private static byte[] createShmBufferAndSyncRequest() {
        ByteBuffer request = buffer(44);
        putHeader(request, 10, 0, 32);
        request.putInt(12);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        request.putInt(24);
        request.putInt(1);
        putHeader(request, 1, 0, 12);
        request.putInt(13);
        return request.array();
    }

    private static byte[] commitShmFrameAndSyncRequest() {
        ByteBuffer request = buffer(76);
        putHeader(request, 8, 1, 20);
        request.putInt(12);
        request.putInt(0);
        request.putInt(0);
        putHeader(request, 8, 9, 24);
        request.putInt(0);
        request.putInt(0);
        request.putInt(4);
        request.putInt(2);
        putHeader(request, 8, 3, 12);
        request.putInt(14);
        putHeader(request, 8, 6, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(15);
        return request.array();
    }

    private static byte[] destroyShmResourcesAndSyncRequest() {
        ByteBuffer request = buffer(28);
        putHeader(request, 12, 0, 8);
        putHeader(request, 10, 1, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(16);
        return request.array();
    }

    private static RegistryGlobals readGlobals(FileInputStream input, int callbackId)
            throws Exception {
        Global compositor = null;
        Global shm = null;
        Global xdgWmBase = null;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == 2 && message.opcode == 0) {
                ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
                int name = body.getInt();
                int length = body.getInt();
                if (length <= 0 || length > body.remaining()) {
                    throw new IllegalStateException("invalid registry interface");
                }
                byte[] encoded = new byte[length];
                body.get(encoded);
                int padding = ((length + 3) & ~3) - length;
                body.position(body.position() + padding);
                int version = body.getInt();
                String interfaceName = new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
                if ("wl_compositor".equals(interfaceName)) {
                    compositor = new Global(name, version);
                } else if ("wl_shm".equals(interfaceName)) {
                    shm = new Global(name, version);
                } else if ("xdg_wm_base".equals(interfaceName)) {
                    xdgWmBase = new Global(name, version);
                }
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (compositor == null || shm == null || xdgWmBase == null) {
                    throw new IllegalStateException("required Wayland globals not advertised");
                }
                return new RegistryGlobals(compositor, shm, xdgWmBase);
            }
        }
    }

    private static void readUntilCallback(FileInputStream input, int callbackId) throws Exception {
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == callbackId && message.opcode == 0) return;
        }
    }

    private static int readXdgConfigureUntilCallback(
            FileInputStream input, int xdgSurfaceId, int toplevelId, int callbackId)
            throws Exception {
        boolean toplevelConfigured = false;
        Integer configureSerial = null;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == toplevelId && message.opcode == 0) {
                if (message.body.length < 12) {
                    throw new IllegalStateException("invalid xdg_toplevel.configure event");
                }
                toplevelConfigured = true;
            } else if (message.objectId == xdgSurfaceId && message.opcode == 0) {
                if (message.body.length != 4) {
                    throw new IllegalStateException("invalid xdg_surface.configure event");
                }
                configureSerial = ByteBuffer.wrap(message.body)
                        .order(ByteOrder.nativeOrder()).getInt();
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!toplevelConfigured || configureSerial == null || configureSerial == 0) {
                    throw new IllegalStateException("xdg configure sequence was incomplete");
                }
                return configureSerial;
            }
        }
    }

    private static void readFrameCommitUntilCallback(
            FileInputStream input, int bufferId, int frameCallbackId, int syncCallbackId)
            throws Exception {
        boolean bufferReleased = false;
        boolean frameDone = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            bufferReleased |= message.objectId == bufferId && message.opcode == 0;
            frameDone |= message.objectId == frameCallbackId && message.opcode == 0;
            if (message.objectId == syncCallbackId && message.opcode == 0) {
                if (!bufferReleased || !frameDone) {
                    throw new IllegalStateException("frame commit events were incomplete");
                }
                return;
            }
        }
    }

    private static void readShmFormatsUntilCallback(
            FileInputStream input, int shmId, int callbackId) throws Exception {
        boolean foundArgb8888 = false;
        boolean foundXrgb8888 = false;
        while (true) {
            Message message = readMessage(input);
            throwIfDisplayError(message);
            if (message.objectId == shmId && message.opcode == 0 && message.body.length == 4) {
                int format = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder()).getInt();
                foundArgb8888 |= format == 0;
                foundXrgb8888 |= format == 1;
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (!foundArgb8888 || !foundXrgb8888) {
                    throw new IllegalStateException("required wl_shm formats not advertised");
                }
                return;
            }
        }
    }

    private static void throwIfDisplayError(Message message) {
        if (message.objectId != 1 || message.opcode != 0) return;
        ByteBuffer body = ByteBuffer.wrap(message.body).order(ByteOrder.nativeOrder());
        int failedObject = body.getInt();
        int code = body.getInt();
        int length = body.getInt();
        if (length <= 0 || length > body.remaining()) {
            throw new IllegalStateException("Wayland protocol error " + code
                    + " on object " + failedObject);
        }
        byte[] encoded = new byte[length];
        body.get(encoded);
        String detail = new String(encoded, 0, length - 1, StandardCharsets.UTF_8);
        throw new IllegalStateException("Wayland protocol error " + code
                + " on object " + failedObject + ": " + detail);
    }
    private static Message readMessage(FileInputStream input) throws Exception {
        ByteBuffer header = ByteBuffer.wrap(readExact(input, 8)).order(ByteOrder.nativeOrder());
        int objectId = header.getInt();
        int word = header.getInt();
        int opcode = word & 0xffff;
        int size = word >>> 16;
        if (size < 8 || (size & 3) != 0 || size > 65535) {
            throw new IllegalStateException("invalid Wayland message size " + size);
        }
        return new Message(objectId, opcode, readExact(input, size - 8));
    }

    private static byte[] readExact(FileInputStream input, int length) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) throw new IllegalStateException("Wayland server closed the socket");
            offset += read;
        }
        return result;
    }

    private static void dispatch(long core) {
        if (nativeDispatchOnce(core) < 0) throw new IllegalStateException("native dispatch");
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
    }

    private static void putHeader(ByteBuffer buffer, int objectId, int opcode, int size) {
        buffer.putInt(objectId);
        buffer.putInt((size << 16) | opcode);
    }

    private static final class RegistryGlobals {
        final Global compositor;
        final Global shm;
        final Global xdgWmBase;

        RegistryGlobals(Global compositor, Global shm, Global xdgWmBase) {
            this.compositor = compositor;
            this.shm = shm;
            this.xdgWmBase = xdgWmBase;
        }
    }
    private static final class Global {
        final int name;
        final int version;

        Global(int name, int version) {
            this.name = name;
            this.version = version;
        }
    }

    private static final class Message {
        final int objectId;
        final int opcode;
        final byte[] body;

        Message(int objectId, int opcode, byte[] body) {
            this.objectId = objectId;
            this.opcode = opcode;
            this.body = body;
        }
    }
}
