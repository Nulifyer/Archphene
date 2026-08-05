package org.archphene.app

import android.app.Application
import android.util.Log
import java.io.File

open class ArchpheneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val legacyMarker = LegacyPrototypeState.detectedMarker(File(applicationInfo.dataDir))
        if (legacyMarker == null) {
            ArchphenePreferences.start(this)
        } else {
            Log.w(TAG, "Blocked greenfield initialization for retained prototype state")
        }
    }

    private companion object {
        const val TAG = "ArchpheneApplication"
    }
}
