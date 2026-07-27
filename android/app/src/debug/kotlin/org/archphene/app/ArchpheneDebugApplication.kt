package org.archphene.app

import android.os.StrictMode
import android.util.Log

/**
 * Makes accidental Android main-thread I/O and leaked closeable resources
 * visible in debug device logs. Release builds do not install this policy.
 */
class ArchpheneDebugApplication : ArchpheneApplication() {
    override fun onCreate() {
        // Samsung's framework performs its FlipFont bootstrap inside
        // Application.onCreate(). Enable the app policy immediately after that
        // framework-owned work so device logs identify Archphene violations
        // rather than OEM implementation details.
        super.onCreate()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
        Log.i(TAG, "StrictMode main-thread I/O and resource-leak diagnostics enabled")
    }

    private companion object {
        const val TAG = "ArchpheneStrictMode"
    }
}
