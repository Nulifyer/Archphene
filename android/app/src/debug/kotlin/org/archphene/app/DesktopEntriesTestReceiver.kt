package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.Log
import java.io.File

internal class DesktopEntriesTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEED && intent.action != ACTION_CLEAN) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token == null || !TOKEN.matches(token)) {
            Log.e(TAG, "Rejected invalid desktop-entry fixture")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    if (intent.action == ACTION_SEED) {
                        seed(context)
                        Log.i(TAG, "Seeded desktop entries token=$token")
                    } else {
                        clean(context)
                        Log.i(TAG, "Cleaned desktop entries token=$token")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Desktop-entry fixture failed token=$token", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchpheneDesktopEntriesProbe",
        ).start()
    }

    private fun seed(context: Context) {
        clean(context)
        val root = File(context.filesDir, "arch-root")
        val applications = File(root, "usr/share/applications")
        val binaries = File(root, "usr/bin")
        check(applications.mkdirs() || applications.isDirectory)
        check(binaries.mkdirs() || binaries.isDirectory)
        for (binary in BINARIES) {
            val file = File(binaries, binary)
            check(!file.exists()) { "fixture path already exists: $binary" }
            file.writeText("#!archphene-desktop-fixture\n")
            check(file.setExecutable(true, true))
        }
        writeDesktop(
            applications,
            "org.archphene.fixture.kate.desktop",
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Kate\n" +
                "Exec=archphene-fixture-kate --startanon %U\n" +
                "TryExec=archphene-fixture-kate\n" +
                "Icon=kate\n" +
                "MimeType=text/plain;application/json;\n",
        )
        writeDesktop(
            applications,
            "org.archphene.fixture.foot.desktop",
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Foot\n" +
                "Exec=archphene-fixture-foot\n" +
                "Terminal=true\n",
        )
        writeDesktop(
            applications,
            "org.archphene.fixture.hidden.desktop",
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Hidden\n" +
                "Exec=archphene-fixture-kate\n" +
                "Hidden=true\n",
        )
        writeDesktop(
            applications,
            "org.archphene.fixture.invalid.desktop",
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Invalid\n" +
                "Exec=missing\n",
        )
        val escape = File(applications, "org.archphene.fixture.escape.desktop")
        if (!escape.exists()) {
            Os.symlink("/system/build.prop", escape.absolutePath)
        }
    }

    private fun clean(context: Context) {
        val root = File(context.filesDir, "arch-root")
        val applications = File(root, "usr/share/applications")
        for (name in DESKTOP_FILES) {
            val file = File(applications, name)
            check(file.delete() || !file.exists()) { "could not delete fixture $name" }
        }
        val binaries = File(root, "usr/bin")
        for (name in BINARIES) {
            val file = File(binaries, name)
            check(file.delete() || !file.exists()) { "could not delete fixture $name" }
        }
    }

    private fun writeDesktop(
        directory: File,
        name: String,
        contents: String,
    ) {
        val file = File(directory, name)
        check(!file.exists()) { "fixture path already exists: $name" }
        file.writeText(contents)
    }

    private companion object {
        private const val TAG = "ArchpheneDesktopEntriesProbe"
        private const val ACTION_SEED =
            "org.archphene.app.debug.action.SEED_DESKTOP_ENTRIES"
        private const val ACTION_CLEAN =
            "org.archphene.app.debug.action.CLEAN_DESKTOP_ENTRIES"
        private const val EXTRA_TOKEN = "token"
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
        private val BINARIES =
            arrayOf(
                "archphene-fixture-foot",
                "archphene-fixture-kate",
            )
        private val DESKTOP_FILES =
            arrayOf(
                "org.archphene.fixture.kate.desktop",
                "org.archphene.fixture.foot.desktop",
                "org.archphene.fixture.hidden.desktop",
                "org.archphene.fixture.invalid.desktop",
                "org.archphene.fixture.escape.desktop",
            )
    }
}
