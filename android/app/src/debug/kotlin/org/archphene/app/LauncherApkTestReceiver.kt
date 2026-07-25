package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.archphene.app.launcher.LauncherApkAssembler
import org.archphene.app.launcher.LauncherApkRequest

internal class LauncherApkTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_BUILD || intent.getStringExtra(EXTRA_TOKEN) != TOKEN) {
            Log.e(TAG, "Rejected launcher APK fixture")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    val generated =
                        LauncherApkAssembler.assembleAndSign(
                            context,
                            LauncherApkRequest(
                                "org.archphene.linux.p11111111111111111111111111111111",
                                "2222222222222222222222222222222222222222222222222222222222222222",
                                7,
                                "Archphene Fixture",
                            ),
                        )
                    Log.i(
                        TAG,
                        "Built launcher package=${generated.androidPackage} " +
                            "generation=${generated.generation} bytes=${generated.apk.length()}",
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Launcher APK fixture failed", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchpheneLauncherApkProbe",
        ).start()
    }

    private companion object {
        private const val TAG = "ArchpheneLauncherApkProbe"
        private const val ACTION_BUILD = "org.archphene.app.debug.action.BUILD_LAUNCHER_APK"
        private const val EXTRA_TOKEN = "token"
        private const val TOKEN = "launcher-apk-gate"
    }
}
