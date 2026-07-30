package org.archphene.app.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import org.archphene.app.R

/**
 * Manager-owned foreground consent for microphone access requested by an authenticated launcher.
 *
 * The thin launcher receives only a one-shot PendingIntent. It cannot grant itself authority,
 * choose another session, or report the result to the manager.
 */
internal class MicrophonePermissionActivity : Activity() {
    private var sessionId = 0
    private var token = ""
    private var label = ""
    private var requestStarted = false
    private var resultReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        sessionId = intent.getIntExtra(LauncherSessionService.EXTRA_MICROPHONE_SESSION, 0)
        token =
            intent.getStringExtra(LauncherSessionService.EXTRA_MICROPHONE_TOKEN)
                .orEmpty()
        label =
            intent.getStringExtra(LauncherSessionService.EXTRA_MICROPHONE_LABEL)
                .orEmpty()
                .take(MAX_LABEL_CHARACTERS)
        requestStarted = savedInstanceState?.getBoolean(STATE_REQUEST_STARTED) == true
        if (
            intent.action != LauncherSessionService.ACTION_MICROPHONE_PERMISSION ||
            sessionId <= 0 ||
            !TOKEN.matches(token) ||
            label.isBlank()
        ) {
            Log.e(TAG, "Rejected invalid microphone permission launch")
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            reportResult(true)
            finish()
            return
        }
        if (!requestStarted) {
            showExplanation()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_REQUEST_STARTED, requestStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode != REQUEST_MICROPHONE ||
            permissions.size != 1 ||
            permissions[0] != Manifest.permission.RECORD_AUDIO
        ) {
            return
        }
        val granted =
            grantResults.size == 1 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        reportResult(granted)
        finish()
    }

    private fun showExplanation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.microphone_permission_title, label))
            .setMessage(R.string.microphone_permission_message)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                requestStarted = true
                runCatching {
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_MICROPHONE,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Could not request Android microphone permission", error)
                    reportResult(false)
                    finish()
                }
            }.setNegativeButton(R.string.not_now) { _, _ ->
                reportResult(false)
                finish()
            }.setOnCancelListener {
                reportResult(false)
                finish()
            }.show()
    }

    private fun reportResult(granted: Boolean) {
        if (resultReported) return
        resultReported = true
        val result =
            Intent(this, LauncherSessionService::class.java)
                .setAction(LauncherSessionService.ACTION_MICROPHONE_RESULT)
                .putExtra(LauncherSessionService.EXTRA_MICROPHONE_SESSION, sessionId)
                .putExtra(LauncherSessionService.EXTRA_MICROPHONE_TOKEN, token)
                .putExtra(LauncherSessionService.EXTRA_MICROPHONE_GRANTED, granted)
        runCatching { startService(result) }
            .onFailure { error ->
                Log.e(TAG, "Could not report microphone permission result", error)
            }
    }

    private companion object {
        private const val TAG = "ArchpheneMicrophone"
        private const val REQUEST_MICROPHONE = 7_201
        private const val STATE_REQUEST_STARTED = "request-started"
        private const val MAX_LABEL_CHARACTERS = 256
        private val TOKEN = Regex("[0-9a-f]{32}")
    }
}
