package org.archphene.app.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.archphene.app.runtime.ArchpheneRuntimeService

class LauncherInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_RESULT) {
            return
        }
        val androidPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val generation = intent.getLongExtra(EXTRA_GENERATION, 0)
        val operation = intent.getIntExtra(EXTRA_OPERATION, 0)
        if (
            !PACKAGE.matches(androidPackage) ||
            generation !in 1..Int.MAX_VALUE.toLong() ||
            operation !in OPERATION_INSTALL..OPERATION_REMOVE
        ) {
            Log.e(TAG, "Rejected invalid launcher install result")
            return
        }
        when (
            intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    report(context, androidPackage, generation, operation, false)
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                report(context, androidPackage, generation, operation, true)
            }
            else -> {
                Log.w(
                    TAG,
                    "Android rejected launcher package=$androidPackage " +
                        "generation=$generation status=" +
                        intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE,
                        ),
                )
                report(context, androidPackage, generation, operation, false)
            }
        }
    }

    private fun report(
        context: Context,
        androidPackage: String,
        generation: Long,
        operation: Int,
        succeeded: Boolean,
    ) {
        val service =
            Intent(context, ArchpheneRuntimeService::class.java).apply {
                action =
                    if (!succeeded) {
                        ArchpheneRuntimeService.ACTION_LAUNCHER_FAILED
                    } else if (operation == OPERATION_INSTALL) {
                        ArchpheneRuntimeService.ACTION_LAUNCHER_INSTALLED
                    } else {
                        ArchpheneRuntimeService.ACTION_LAUNCHER_REMOVED
                    }
                putExtra(ArchpheneRuntimeService.EXTRA_LAUNCHER_PACKAGE, androidPackage)
                putExtra(ArchpheneRuntimeService.EXTRA_LAUNCHER_GENERATION, generation)
            }
        context.startService(service)
    }

    internal companion object {
        const val ACTION_RESULT = "org.archphene.app.action.LAUNCHER_INSTALL_RESULT"
        const val EXTRA_PACKAGE = "launcherPackage"
        const val EXTRA_GENERATION = "launcherGeneration"
        const val EXTRA_OPERATION = "launcherOperation"
        const val OPERATION_INSTALL = 1
        const val OPERATION_REMOVE = 2
        private const val TAG = "ArchpheneLauncherInstall"
        private val PACKAGE =
            Regex("org\\.archphene\\.linux\\.p[0-9a-f]{32}")
    }
}
