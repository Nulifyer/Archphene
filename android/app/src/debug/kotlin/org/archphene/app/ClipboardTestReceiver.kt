package org.archphene.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class ClipboardTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ClipboardTestController.supports(intent.action)) {
            return
        }
        context.startActivity(
            Intent(context, ClipboardTestActivity::class.java)
                .setAction(intent.action)
                .putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }
}

internal object ClipboardTestController {
    fun supports(action: String?): Boolean =
        action == ACTION_SET_CLIPBOARD || action == ACTION_RESTORE_CLIPBOARD

    fun handle(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_RESTORE_CLIPBOARD) {
            restoreClipboard(context)
            return
        }
        if (intent.action != ACTION_SET_CLIPBOARD) {
            return
        }
        val encoded = intent.getStringExtra(EXTRA_TEXT_BASE64)
        val text =
            if (encoded == null) {
                intent.getStringExtra(EXTRA_TEXT) ?: return
            } else {
                decodeUtf8(encoded) ?: return
            }
        if (text.length > MAX_TEXT_CHARACTERS) {
            return
        }
        val encodedHtml = intent.getStringExtra(EXTRA_HTML_BASE64)
        val html =
            if (encodedHtml == null) {
                intent.getStringExtra(EXTRA_HTML)
            } else {
                decodeUtf8(encodedHtml) ?: return
            }
        if (html != null && html.length > MAX_TEXT_CHARACTERS) {
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        if (intent.getBooleanExtra(EXTRA_SAVE_EXISTING, false) && !clipboardSaved) {
            val existing =
                runCatching {
                    SavedClipboard(
                        hadPrimaryClip = clipboard.hasPrimaryClip(),
                        clip = clipboard.primaryClip,
                    )
                }.getOrNull() ?: return
            savedClipboard = existing
            clipboardSaved = true
            Log.i(TAG, "Saved Android clipboard before debug test")
        }
        clipboard.setPrimaryClip(
            if (html == null) {
                ClipData.newPlainText(CLIP_LABEL, text)
            } else {
                ClipData.newHtmlText(CLIP_LABEL, text, html)
            },
        )
        Log.i(
            TAG,
            "Set Android debug clipboard textChars=${text.length} htmlChars=${html?.length ?: 0}",
        )
    }

    private fun restoreClipboard(context: Context) {
        if (!clipboardSaved) {
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val saved = savedClipboard
        if (saved?.hadPrimaryClip == true && saved.clip != null) {
            clipboard.setPrimaryClip(saved.clip)
        } else {
            clipboard.clearPrimaryClip()
        }
        savedClipboard = null
        clipboardSaved = false
        Log.i(TAG, "Restored Android clipboard after debug test")
    }

    private fun decodeUtf8(encoded: String): String? {
        if (encoded.length > MAX_BASE64_CHARACTERS) {
            return null
        }
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size > MAX_TEXT_BYTES) {
            return null
        }
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    private const val ACTION_SET_CLIPBOARD =
        "org.archphene.app.debug.action.SET_TEST_CLIPBOARD"
    private const val ACTION_RESTORE_CLIPBOARD =
        "org.archphene.app.debug.action.RESTORE_TEST_CLIPBOARD"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_TEXT_BASE64 = "text_base64"
    private const val EXTRA_HTML = "html"
    private const val EXTRA_HTML_BASE64 = "html_base64"
    private const val EXTRA_SAVE_EXISTING = "save_existing"
    private const val MAX_TEXT_CHARACTERS = 2 * 1024
    private const val MAX_TEXT_BYTES = 8 * 1024
    private const val MAX_BASE64_CHARACTERS = 12 * 1024
    private const val CLIP_LABEL = "Archphene debug test"
    private const val TAG = "ArchpheneClipboardProbe"
    private var savedClipboard: SavedClipboard? = null
    private var clipboardSaved = false

    private data class SavedClipboard(
        val hadPrimaryClip: Boolean,
        val clip: ClipData?,
    )
}
