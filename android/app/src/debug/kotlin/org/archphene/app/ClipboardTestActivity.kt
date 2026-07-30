package org.archphene.app

import android.app.Activity
import android.os.Bundle

internal class ClipboardTestActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ClipboardTestController.supports(intent.action)) {
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            ClipboardTestController.handle(this, intent)
            finish()
        }
    }
}
