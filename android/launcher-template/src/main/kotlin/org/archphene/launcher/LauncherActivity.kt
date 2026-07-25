package org.archphene.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.TextView

class LauncherActivity : Activity() {
    private lateinit var status: TextView
    private var binding = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                status.text = getString(R.string.launcher_connected, appLabel())
            }

            override fun onServiceDisconnected(name: ComponentName) {
                status.text = getString(R.string.launcher_disconnected)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                textSize = 18f
                setTextColor(getColor(R.color.launcher_text))
                setBackgroundColor(getColor(R.color.launcher_background))
                text = getString(R.string.launcher_opening, appLabel())
            }
        setContentView(status)
    }

    override fun onStart() {
        super.onStart()
        val manager = applicationMetadata().getString(MANAGER_PACKAGE).orEmpty()
        if (!SAFE_PACKAGE.matches(manager)) {
            status.setText(R.string.launcher_invalid)
            return
        }
        val intent =
            Intent(BIND_ACTION).apply {
                setPackage(manager)
            }
        binding = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!binding) {
            status.setText(R.string.launcher_unavailable)
        }
    }

    override fun onStop() {
        if (binding) {
            unbindService(connection)
            binding = false
        }
        super.onStop()
    }

    private fun applicationMetadata(): Bundle =
        packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData ?: Bundle.EMPTY

    private fun appLabel(): String =
        packageManager.getApplicationLabel(applicationInfo).toString().take(256)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val MANAGER_PACKAGE = "org.archphene.launcher.MANAGER_PACKAGE"
        private const val BIND_ACTION = "org.archphene.action.BIND_LAUNCHER"
        private val SAFE_PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,7}")
    }
}
