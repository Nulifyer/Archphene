package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

internal class InstalledPackagesTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEED) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token == null || !TOKEN.matches(token)) {
            Log.e(TAG, "Rejected invalid installed-package fixture")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    val local =
                        File(context.filesDir, "arch-root/var/lib/pacman/local")
                    check(local.mkdirs() || local.isDirectory) {
                        "could not create local package database"
                    }
                    File(local, "ALPM_DB_VERSION").writeText("9\n")
                    writePackage(local, "dotnet-sdk", "10.0.100.sdk100-1", true)
                    writePackage(local, "glibc", "2.42+r33+gde5fe48316ed-1", false)
                    repeat(FIXTURE_PACKAGE_COUNT) { index ->
                        writePackage(
                            local,
                            "fixture-${index.toString().padStart(3, '0')}",
                            "1.0.$index-1",
                            index % 2 == 0,
                        )
                    }
                    Log.i(TAG, "Seeded installed packages token=$token")
                } catch (error: Exception) {
                    Log.e(TAG, "Installed-package fixture failed token=$token", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchpheneInstalledPackagesProbe",
        ).start()
    }

    private fun writePackage(
        local: File,
        name: String,
        version: String,
        explicit: Boolean,
    ) {
        val directory = File(local, "$name-$version")
        check(directory.mkdir()) { "could not create package entry for $name" }
        File(directory, "desc").writeText(
            "%NAME%\n$name\n\n%VERSION%\n$version\n\n%REASON%\n" +
                if (explicit) "0\n" else "1\n",
        )
    }

    private companion object {
        private const val TAG = "ArchpheneInstalledPackagesProbe"
        private const val ACTION_SEED =
            "org.archphene.app.debug.action.SEED_INSTALLED_PACKAGES"
        private const val EXTRA_TOKEN = "token"
        private const val FIXTURE_PACKAGE_COUNT = 65
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
    }
}
