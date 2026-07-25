package org.archphene.app.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import java.io.FileInputStream
import java.security.MessageDigest

internal object LauncherPackageInstaller {
    fun submit(
        context: Context,
        generated: GeneratedLauncherApk,
    ): Int {
        val installer = context.packageManager.packageInstaller
        val parameters =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(generated.androidPackage)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                setSize(generated.apk.length())
            }
        val sessionId = installer.createSession(parameters)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(generated.apk).use { input ->
                    session.openWrite("base.apk", 0, generated.apk.length()).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        check(MessageDigest.isEqual(digest.digest(), generated.apkSha256)) {
                            "Generated launcher changed before installer handoff"
                        }
                        session.fsync(output)
                    }
                }
                session.commit(
                    resultSender(
                        context,
                        generated.androidPackage,
                        generated.generation,
                        LauncherInstallResultReceiver.OPERATION_INSTALL,
                        sessionId,
                    ),
                )
            }
            return sessionId
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    fun uninstall(
        context: Context,
        androidPackage: String,
        generation: Long,
    ) {
        context.packageManager.packageInstaller.uninstall(
            androidPackage,
            resultSender(
                context,
                androidPackage,
                generation,
                LauncherInstallResultReceiver.OPERATION_REMOVE,
                androidPackage.hashCode(),
            ),
        )
    }

    private fun resultSender(
        context: Context,
        androidPackage: String,
        generation: Long,
        operation: Int,
        requestCode: Int,
    ) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, LauncherInstallResultReceiver::class.java).apply {
                action = LauncherInstallResultReceiver.ACTION_RESULT
                data =
                    Uri.Builder()
                        .scheme("archphene-launcher")
                        .authority(androidPackage)
                        .appendPath(generation.toString())
                        .appendPath(operation.toString())
                        .build()
                putExtra(LauncherInstallResultReceiver.EXTRA_PACKAGE, androidPackage)
                putExtra(LauncherInstallResultReceiver.EXTRA_GENERATION, generation)
                putExtra(LauncherInstallResultReceiver.EXTRA_OPERATION, operation)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
}
