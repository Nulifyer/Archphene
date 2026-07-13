package org.archphene.compositorprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
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
    private static native int nativeDispatchOnce(long handle);
    private static native int nativeCompositorBindCount(long handle);
    private static native int nativeSurfaceCount(long handle);
    private static native void nativeDestroyCore(long handle);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView result = new TextView(this);
        result.setGravity(Gravity.CENTER);
        result.setTextSize(20);
        setContentView(result);
        new Thread(() -> runProbe(result), "native-compositor-probe").start();
    }

    private void runProbe(TextView result) {
        String message;
        boolean passed = false;
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

                Global compositor = readCompositorGlobal(input, 3);
                output.write(bindCompositorAndSyncRequest(compositor, 5));
                output.flush();
                dispatch(core);
                readUntilCallback(input, 5);

                if (nativeCompositorBindCount(core) != 1) {
                    throw new IllegalStateException("wl_compositor bind was not dispatched");
                }

                output.write(createSurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 7);
                if (nativeSurfaceCount(core) != 1) {
                    throw new IllegalStateException("wl_surface creation was not dispatched");
                }

                output.write(destroySurfaceAndSyncRequest());
                output.flush();
                dispatch(core);
                readUntilCallback(input, 8);
                if (nativeSurfaceCount(core) != 0) {
                    throw new IllegalStateException("wl_surface destruction was not dispatched");
                }
            }
            passed = true;
            message = "Native Wayland compositor passed\n"
                    + "registry, compositor bind, and surface lifecycle complete";
        } catch (Exception error) {
            message = "Native compositor probe failed\n" + error.getMessage();
        } finally {
            if (core != 0) nativeDestroyCore(core);
        }
        boolean finalPassed = passed;
        String finalMessage = message;
        Log.i("ArchpheneCompositorProbe", finalMessage.replace('\n', ' '));
        runOnUiThread(() -> {
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

    private static byte[] bindCompositorAndSyncRequest(Global global, int callbackId) {
        byte[] interfaceName = "wl_compositor".getBytes(StandardCharsets.UTF_8);
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
        request.putInt(4);
        putHeader(request, 1, 0, 12);
        request.putInt(callbackId);
        return request.array();
    }

    private static byte[] createSurfaceAndSyncRequest() {
        ByteBuffer request = buffer(24);
        putHeader(request, 4, 0, 12);
        request.putInt(6);
        putHeader(request, 1, 0, 12);
        request.putInt(7);
        return request.array();
    }

    private static byte[] destroySurfaceAndSyncRequest() {
        ByteBuffer request = buffer(20);
        putHeader(request, 6, 0, 8);
        putHeader(request, 1, 0, 12);
        request.putInt(8);
        return request.array();
    }

    private static Global readCompositorGlobal(FileInputStream input, int callbackId)
            throws Exception {
        Global compositor = null;
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
                }
            }
            if (message.objectId == callbackId && message.opcode == 0) {
                if (compositor == null) {
                    throw new IllegalStateException("wl_compositor global not advertised");
                }
                return compositor;
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
