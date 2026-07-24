package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

internal class ClipboardTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SET_CLIPBOARD) {
            return
        }
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        if (text.length > MAX_TEXT_CHARACTERS) {
            return
        }
        context
            .getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    private companion object {
        private const val ACTION_SET_CLIPBOARD =
            "org.archphene.app.debug.action.SET_TEST_CLIPBOARD"
        private const val EXTRA_TEXT = "text"
        private const val MAX_TEXT_CHARACTERS = 2 * 1024
        private const val CLIP_LABEL = "Archphene debug test"
    }
}
