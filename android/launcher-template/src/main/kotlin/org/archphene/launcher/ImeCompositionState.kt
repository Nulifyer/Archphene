package org.archphene.launcher

/**
 * Retains only the latest Android preedit that Wayland has accepted.
 *
 * Android's finishComposingText() leaves the composing text in the editor.
 * Wayland preedit is not part of the client's committed surrounding text, so
 * the launcher must commit this exact value when Android finishes composition.
 */
internal class ImeCompositionState {
    private var acceptedPreedit: String? = null

    fun replaceAcceptedPreedit(value: String) {
        acceptedPreedit = if (value.isEmpty()) null else value
    }

    fun finishCommit(): String? = acceptedPreedit

    fun clear() {
        acceptedPreedit = null
    }
}
