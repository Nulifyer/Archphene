package org.archphene.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.NativeRuntime
import org.archphene.app.runtime.RuntimeSnapshot

class MainActivity : Activity(), Choreographer.FrameCallback {
    private lateinit var statusView: TextView
    private lateinit var catalogStatusView: TextView
    private lateinit var searchStatusView: TextView
    private lateinit var jobStatusView: TextView
    private lateinit var commandStatusView: TextView
    private lateinit var runtimeSurface: RuntimeSurfaceView
    private lateinit var managerPanel: LinearLayout
    private lateinit var runtimePanel: FrameLayout
    private lateinit var installButton: Button
    private lateinit var removeButton: Button
    private lateinit var commandButton: Button
    private lateinit var ptyButton: Button
    private lateinit var shellSpinner: Spinner
    private lateinit var shellAdapter: ArrayAdapter<String>
    private val snapshot = RuntimeSnapshot()
    private val statusText = StringBuilder(128)
    private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var serviceBound = false
    private var frameCallbackActive = false
    private var statusFrameCountdown = 0
    private var keepServiceAfterFinish = false
    private var shellCatalogRevision = Int.MIN_VALUE

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                runtimeBinder = service as? ArchpheneRuntimeService.LocalBinder
                if (frameCallbackActive) {
                    transitionRuntime(NativeRuntime.LIFECYCLE_RUNNING)
                }
                runtimeSurface.synchronizeTerminalSize(runtimeBinder)
                updateStatus()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runtimeBinder = null
                statusView.setText(R.string.runtime_unavailable)
                catalogStatusView.setText(R.string.package_catalog_unavailable)
                searchStatusView.setText(R.string.package_search_unavailable)
                jobStatusView.setText(R.string.package_job_unavailable)
                commandStatusView.setText(R.string.linux_command_unavailable)
                installButton.isEnabled = false
                removeButton.isEnabled = false
                commandButton.isEnabled = false
                ptyButton.isEnabled = false
                shellSpinner.isEnabled = false
                updateShellPresentation(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityGeneration++
        Log.i(TAG, "Activity created generation=$activityGeneration")
        statusView =
            TextView(this).apply {
                setText(R.string.runtime_starting)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setBackgroundColor(Color.rgb(7, 152, 209))
                maxLines = 3
            }
        runtimeSurface = RuntimeSurfaceView(this)
        runtimeSurface.onTerminalSizeChanged = { rows, columns ->
            runtimeBinder?.resizeSharedShell(rows, columns)
        }
        runtimePanel =
            FrameLayout(this).apply {
                visibility = View.GONE
                addView(
                    runtimeSurface,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        catalogStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                setText(R.string.package_catalog_unavailable)
            }
        val refreshCatalogButton =
            Button(this).apply {
                setText(R.string.refresh_catalogs)
                setOnClickListener {
                    if (runtimeBinder?.refreshPackageCatalogs() != true) {
                        catalogStatusView.setText(R.string.catalog_refresh_busy)
                    }
                }
            }
        val catalogRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.rgb(31, 35, 38))
                addView(
                    refreshCatalogButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    catalogStatusView,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
            }
        val searchInput =
            EditText(this).apply {
                setHint(R.string.package_name_hint)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
            }
        val searchButton =
            Button(this).apply {
                setText(R.string.search)
                setOnClickListener {
                    hideKeyboard(searchInput)
                    runtimeBinder?.searchPackages(searchInput.text.toString())
                }
            }
        val detailsButton =
            Button(this).apply {
                setText(R.string.details)
                setOnClickListener {
                    hideKeyboard(searchInput)
                    runtimeBinder?.resolvePackage(searchInput.text.toString())
                }
            }
        installButton =
            Button(this).apply {
                setText(R.string.install)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(searchInput)
                    runtimeBinder?.installPackage(searchInput.text.toString())
                }
            }
        removeButton =
            Button(this).apply {
                setText(R.string.remove)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(searchInput)
                    runtimeBinder?.removePackage(searchInput.text.toString())
                }
            }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(searchInput)
                runtimeBinder?.searchPackages(searchInput.text.toString())
                true
            } else {
                false
            }
        }
        val actionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    searchButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    detailsButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    installButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    removeButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
            }
        searchStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setText(R.string.package_search_prompt)
                setTextIsSelectable(true)
                setBackgroundColor(Color.rgb(24, 28, 31))
            }
        val searchResults =
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    searchStatusView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        jobStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setText(R.string.package_job_empty)
                setBackgroundColor(Color.rgb(31, 35, 38))
                maxLines = 2
            }
        shellAdapter =
            ArrayAdapter<String>(this, R.layout.shell_spinner_item).apply {
                setDropDownViewResource(R.layout.shell_spinner_dropdown_item)
            }
        shellSpinner =
            Spinner(this).apply {
                adapter = shellAdapter
                backgroundTintList = getColorStateList(R.color.shell_spinner_tint)
                contentDescription = getString(R.string.installed_shell)
                isEnabled = false
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            val binder = runtimeBinder ?: return
                            if (position != binder.selectedSharedShellIndex) {
                                binder.selectSharedShell(position)
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
            }
        val shellRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(31, 35, 38))
                addView(
                    TextView(this@MainActivity).apply {
                        setText(R.string.installed_shell)
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), 0, dp(8), 0)
                    },
                    LinearLayout.LayoutParams(
                        dp(96),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    shellSpinner,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
            }
        val commandInput =
            EditText(this).apply {
                setHint(R.string.linux_command_hint)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_GO
            }
        commandButton =
            Button(this).apply {
                setText(R.string.run_command)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(commandInput)
                    if (runtimeBinder?.submitLinuxInput(commandInput.text.toString()) == true) {
                        commandInput.text.clear()
                    }
                }
            }
        ptyButton =
            Button(this).apply {
                setText(R.string.start_shell)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(commandInput)
                    if (runtimeBinder?.sharedShellRunning == false) {
                        requestSessionNotificationPermission()
                    }
                    runtimeBinder?.toggleSharedShell()
                }
            }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                hideKeyboard(commandInput)
                if (runtimeBinder?.submitLinuxInput(commandInput.text.toString()) == true) {
                    commandInput.text.clear()
                }
                true
            } else {
                false
            }
        }
        val commandRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    commandInput,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    commandButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    ptyButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        commandStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setText(R.string.linux_command_unavailable)
                setBackgroundColor(Color.rgb(24, 28, 31))
                maxLines = 2
                setTextIsSelectable(true)
            }
        managerPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    catalogRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ),
                )
                addView(
                    searchInput,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52),
                    ),
                )
                addView(
                    actionRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ),
                )
                addView(
                    searchResults,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                addView(
                    jobStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64),
                    ),
                )
            }
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    statusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(72),
                    ),
                )
                addView(
                    managerPanel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                addView(
                    shellRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ),
                )
                addView(
                    commandRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52),
                    ),
                )
                addView(
                    commandStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52),
                    ),
                )
                addView(
                    runtimePanel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        applySystemBarInsets(layout)
        setContentView(layout)
        startService(Intent(this, ArchpheneRuntimeService::class.java))
    }

    override fun onStart() {
        super.onStart()
        serviceBound =
            bindService(
                Intent(this, ArchpheneRuntimeService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
    }

    override fun onResume() {
        super.onResume()
        frameCallbackActive = true
        transitionRuntime(NativeRuntime.LIFECYCLE_RUNNING)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        frameCallbackActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        transitionRuntime(NativeRuntime.LIFECYCLE_SUSPENDED)
        super.onPause()
    }

    override fun onStop() {
        keepServiceAfterFinish = runtimeBinder?.sharedShellRunning == true
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        runtimeBinder = null
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !keepServiceAfterFinish) {
            stopService(Intent(this, ArchpheneRuntimeService::class.java))
        }
        super.onDestroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!frameCallbackActive) {
            return
        }
        val handle = runtimeBinder?.runtimeHandle ?: 0L
        runtimeSurface.flushInput(handle)
        runtimeSurface.renderFrame(runtimeBinder)
        if (statusFrameCountdown <= 0) {
            updateStatus()
            statusFrameCountdown = STATUS_FRAME_INTERVAL
        } else {
            statusFrameCountdown--
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateStatus() {
        val handle = runtimeBinder?.runtimeHandle ?: 0L
        if (!snapshot.read(handle)) {
            setTextIfChanged(statusView, getString(R.string.runtime_starting))
            setTextIfChanged(
                catalogStatusView,
                runtimeBinder?.packageCatalogStatus ?: "Package catalog unavailable"
            )
            setTextIfChanged(
                searchStatusView,
                runtimeBinder?.packageSearchStatus ?: "Package search unavailable",
            )
            setTextIfChanged(
                jobStatusView,
                runtimeBinder?.packageJobStatus ?: "Package operation unavailable",
            )
            setTextIfChanged(
                commandStatusView,
                runtimeBinder?.linuxCommandStatus ?: "Linux command environment unavailable",
            )
            updatePackageActions()
            return
        }
        statusText.setLength(0)
        statusText
            .append("Rust runtime ")
            .append(snapshot.generation)
            .append(" · state ")
            .append(snapshot.lifecycle)
            .append('\n')
            .append("Root ")
            .append(if (snapshot.archRootReady) "ready" else "pending")
            .append(" · jobs ")
            .append(if (snapshot.jobStoreReady) "ready" else "pending")
            .append('\n')
            .append("Pacman ")
            .append(if (snapshot.packageRuntimeReady) "ready" else "pending")
            .append(" · events ")
            .append(snapshot.drainedEvents)
        setTextIfChanged(statusView, statusText)
        setTextIfChanged(
            catalogStatusView,
            runtimeBinder?.packageCatalogStatus?.let { status ->
                if (snapshot.packageCatalogReady && status == "Package catalog not downloaded") {
                    "Package catalog ready"
                } else {
                    status
                }
            } ?: "Package catalog unavailable",
        )
        setTextIfChanged(
            searchStatusView,
            runtimeBinder?.packageSearchStatus ?: "Package search unavailable",
        )
        setTextIfChanged(
            jobStatusView,
            runtimeBinder?.packageJobStatus ?: "Package operation unavailable",
        )
        setTextIfChanged(
            commandStatusView,
            if (
                snapshot.sessionInterrupted &&
                runtimeBinder?.sharedShellRunning != true
            ) {
                getString(R.string.session_interrupted)
            } else {
                runtimeBinder?.linuxCommandStatus ?: "Linux command environment unavailable"
            },
        )
        updatePackageActions()
    }

    private fun updatePackageActions() {
        val binder = runtimeBinder
        if (binder == null) {
            installButton.isEnabled = false
            removeButton.isEnabled = false
            commandButton.isEnabled = false
            ptyButton.isEnabled = false
            shellSpinner.isEnabled = false
            updateShellPresentation(false)
            return
        }
        updateShellPresentation(binder.sharedShellRunning)
        updateShellSelector(binder)
        setTextIfChanged(installButton, binder.packagePrimaryActionLabel)
        installButton.isEnabled = binder.packagePrimaryActionAvailable
        removeButton.isEnabled = binder.packageRemoveAvailable
        setTextIfChanged(commandButton, binder.linuxInputActionLabel)
        commandButton.isEnabled = binder.linuxCommandAvailable
        setTextIfChanged(ptyButton, binder.sharedShellActionLabel)
        ptyButton.isEnabled = binder.sharedShellActionAvailable
    }

    private fun updateShellSelector(binder: ArchpheneRuntimeService.LocalBinder) {
        val revision = binder.shellCatalogRevision
        if (revision != shellCatalogRevision) {
            shellCatalogRevision = revision
            shellAdapter.clear()
            val labels = binder.supportedShellLabels
            if (labels.isEmpty()) {
                shellAdapter.add(getString(R.string.no_supported_shell))
            } else {
                shellAdapter.addAll(labels.asList())
            }
            val selected = binder.selectedSharedShellIndex
            if (selected in 0 until shellAdapter.count) {
                shellSpinner.setSelection(selected, false)
            }
        }
        shellSpinner.isEnabled = binder.sharedShellSelectionAvailable
    }

    private fun updateShellPresentation(shellRunning: Boolean) {
        val managerVisibility = if (shellRunning) View.GONE else View.VISIBLE
        val terminalVisibility = if (shellRunning) View.VISIBLE else View.GONE
        if (managerPanel.visibility != managerVisibility) {
            managerPanel.visibility = managerVisibility
        }
        if (runtimePanel.visibility != terminalVisibility) {
            runtimePanel.visibility = terminalVisibility
            if (shellRunning) {
                runtimePanel.post {
                    runtimeSurface.synchronizeTerminalSize(runtimeBinder)
                    runtimeSurface.requestFocus()
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hideKeyboard(view: android.view.View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun requestSessionNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                SESSION_NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun setTextIfChanged(
        view: TextView,
        text: CharSequence,
    ) {
        if (!TextUtils.equals(view.text, text)) {
            view.text = text
        }
    }

    private fun transitionRuntime(lifecycle: Int) {
        val handle = runtimeBinder?.runtimeHandle ?: return
        NativeRuntime.nativeTransition(handle, lifecycle)
    }

    private fun applySystemBarInsets(root: ViewGroup) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                if (
                    view.paddingLeft != bars.left ||
                    view.paddingTop != bars.top ||
                    view.paddingRight != bars.right ||
                    view.paddingBottom != bars.bottom
                ) {
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
    }

    companion object {
        private const val TAG = "ArchpheneActivity"
        private const val STATUS_FRAME_INTERVAL = 30
        private const val SESSION_NOTIFICATION_PERMISSION_REQUEST = 0x4152
        private var activityGeneration = 0
    }
}
