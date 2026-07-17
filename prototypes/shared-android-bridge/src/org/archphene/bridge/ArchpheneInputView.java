package org.archphene.bridge;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.ImageView;
import java.nio.charset.StandardCharsets;

/** Android editor endpoint for the focused Wayland text-input-v3 resource. */
public final class ArchpheneInputView extends ImageView {
    public interface InputSink {
        void preedit(String text, int cursorBeginBytes, int cursorEndBytes);
        void commit(String text);
        void deleteSurrounding(int beforeLength, int afterLength);
        void editorAction(int actionId);
        boolean key(KeyEvent event);
    }

    public static final class EditorState {
        public final String surroundingText;
        public final int selectionStart;
        public final int selectionEnd;
        public final int contentHint;
        public final int contentPurpose;

        public EditorState(
                String surroundingText,
                int selectionStart,
                int selectionEnd,
                int contentHint,
                int contentPurpose) {
            this.surroundingText = surroundingText;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
            this.contentHint = contentHint;
            this.contentPurpose = contentPurpose;
        }
    }

    private final InputSink sink;
    private ArchpheneAccessibilityBridge accessibilityBridge;
    private AccessibilityNodeProvider accessibilityProvider;
    private int accessibilityWindowId;
    private EditorState editorState = new EditorState("", 0, 0, 0, 0);

    public ArchpheneInputView(Context context, InputSink sink) {
        super(context);
        this.sink = sink;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setEditorState(EditorState state) {
        editorState = state;
    }

    void setAccessibilityBridge(ArchpheneAccessibilityBridge bridge) {
        if (accessibilityBridge != null) accessibilityBridge.detach(this);
        accessibilityBridge = bridge;
        accessibilityProvider = bridge == null
                ? null : bridge.attach(this, accessibilityWindowId);
    }

    void setAccessibilityWindowId(int windowId) {
        if (accessibilityWindowId == windowId) return;
        accessibilityWindowId = windowId;
        if (accessibilityBridge != null) {
            accessibilityBridge.detach(this);
            accessibilityProvider = accessibilityBridge.attach(this, windowId);
        }
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return accessibilityBridge == null
                ? super.getAccessibilityNodeProvider() : accessibilityProvider;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return sink.key(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        EditorState state = editorState;
        outAttrs.inputType = androidInputType(state.contentHint, state.contentPurpose);
        outAttrs.imeOptions = androidImeOptions(state.contentHint, state.contentPurpose)
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.initialSelStart = state.selectionStart;
        outAttrs.initialSelEnd = state.selectionEnd;
        if (Build.VERSION.SDK_INT >= 30) {
            outAttrs.setInitialSurroundingSubText(state.surroundingText, 0);
        }
        return new WaylandInputConnection(state);
    }

    private final class WaylandInputConnection extends BaseInputConnection {
        private boolean composing;

        WaylandInputConnection(EditorState state) {
            super(ArchpheneInputView.this, true);
            Editable editable = getEditable();
            editable.clear();
            editable.append(state.surroundingText);
            int start = Math.max(0, Math.min(state.selectionStart, editable.length()));
            int end = Math.max(0, Math.min(state.selectionEnd, editable.length()));
            Selection.setSelection(editable, start, end);
        }


        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String value = text == null ? "" : text.toString();
            int cursorBytes = value.getBytes(StandardCharsets.UTF_8).length;
            sink.preedit(value, cursorBytes, cursorBytes);
            composing = !value.isEmpty();
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean finishComposingText() {
            if (composing) {
                sink.preedit("", 0, 0);
                composing = false;
            }
            return super.finishComposingText();
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String value = text == null ? "" : text.toString();
            sink.commit(value);
            composing = false;
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength < 0 || afterLength < 0) {
                return false;
            }
            sink.deleteSurrounding(beforeLength, afterLength);
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            sink.editorAction(actionCode);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return ArchpheneInputView.this.dispatchKeyEvent(event);
        }
    }

    private static int androidInputType(int hint, int purpose) {
        int inputType = switch (purpose) {
            case 2 -> InputType.TYPE_CLASS_NUMBER;
            case 3 -> InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED;
            case 4 -> InputType.TYPE_CLASS_PHONE;
            case 5 -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
            case 6 -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            case 7 -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
            case 8 -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            case 9 -> InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            case 10 -> InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE;
            case 11 -> InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
            case 12 -> InputType.TYPE_CLASS_DATETIME;
            case 13 -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            default -> InputType.TYPE_CLASS_TEXT;
        };
        if ((hint & 2) != 0) inputType |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        if ((hint & 4) != 0) inputType |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if ((hint & 16) != 0) inputType |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        if ((hint & 32) != 0) inputType |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        if ((hint & 512) != 0) inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if ((hint & 128) != 0 || purpose == 13) {
            inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        return inputType;
    }

    private static int androidImeOptions(int hint, int purpose) {
        if ((hint & 512) != 0) return EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        return switch (purpose) {
            case 5 -> EditorInfo.IME_ACTION_GO;
            case 6 -> EditorInfo.IME_ACTION_SEND;
            default -> EditorInfo.IME_ACTION_DONE;
        };
    }
}