package org.archphene.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipDescription;
import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;

/** Focus-scoped Android clipboard broker that never reads content on change callbacks. */
public final class ArchpheneClipboardBroker implements AutoCloseable {
    public interface Listener {
        void onExternalClipboardChanged();
    }

    private final Context context;
    private final ClipboardManager clipboard;
    private final Listener listener;
    private final AtomicInteger contentReadCount = new AtomicInteger();
    private final ClipboardManager.OnPrimaryClipChangedListener platformListener;
    private final String ownClipLabel;
    private volatile boolean active;

    public ArchpheneClipboardBroker(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ownClipLabel = "Archphene Linux app:" + System.identityHashCode(this);
        platformListener = () -> {
            if (!active) return;
            ClipDescription description = clipboard.getPrimaryClipDescription();
            String label = description == null || description.getLabel() == null
                    ? "" : description.getLabel().toString();
            if (ownClipLabel.equals(label)) return;
            listener.onExternalClipboardChanged();
        };
    }

    public void start() {
        if (active) return;
        active = true;
        clipboard.addPrimaryClipChangedListener(platformListener);
    }

    public void publishLinuxText(String text) {
        clipboard.setPrimaryClip(ClipData.newPlainText(ownClipLabel, text));
    }

    public String readTextForWaylandPaste() {
        contentReadCount.incrementAndGet();
        if (!clipboard.hasPrimaryClip()) return "";
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        return text == null ? "" : text.toString();
    }

    public int contentReadCount() {
        return contentReadCount.get();
    }


    @Override
    public void close() {
        if (!active) return;
        active = false;
        clipboard.removePrimaryClipChangedListener(platformListener);
    }
}