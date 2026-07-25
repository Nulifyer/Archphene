package org.archphene.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
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
    private lateinit var storageStatusView: TextView
    private lateinit var folderStatusView: TextView
    private lateinit var runtimeSurface: RuntimeSurfaceView
    private lateinit var managerPanel: LinearLayout
    private lateinit var runtimePanel: FrameLayout
    private lateinit var installButton: Button
    private lateinit var removeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var commandButton: Button
    private lateinit var ptyButton: Button
    private lateinit var importButton: Button
    private lateinit var folderButton: Button
    private lateinit var folderMirrorButton: Button
    private lateinit var folderDisconnectButton: Button
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
    private var pendingImportUri: Uri? = null
    private var pendingFolderUri: Uri? = null
    private var pendingFolderFlags = 0

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                runtimeBinder = service as? ArchpheneRuntimeService.LocalBinder
                if (frameCallbackActive) {
                    transitionRuntime(NativeRuntime.LIFECYCLE_RUNNING)
                }
                runtimeSurface.synchronizeTerminalSize(runtimeBinder)
                dispatchPendingImport()
                dispatchPendingFolderGrant()
                updateStatus()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runtimeBinder = null
                statusView.setText(R.string.runtime_unavailable)
                catalogStatusView.setText(R.string.package_catalog_unavailable)
                searchStatusView.setText(R.string.package_search_unavailable)
                jobStatusView.setText(R.string.package_job_unavailable)
                commandStatusView.setText(R.string.linux_command_unavailable)
                storageStatusView.setText(R.string.document_import_unavailable)
                folderStatusView.setText(R.string.folder_grant_unavailable)
                installButton.isEnabled = false
                removeButton.isEnabled = false
                cancelButton.isEnabled = false
                commandButton.isEnabled = false
                ptyButton.isEnabled = false
                importButton.isEnabled = false
                folderButton.isEnabled = false
                folderMirrorButton.isEnabled = false
                folderDisconnectButton.isEnabled = false
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
                    if (runtimeBinder?.installPackage(searchInput.text.toString()) == true) {
                        cancelButton.isEnabled = true
                    }
                }
            }
        removeButton =
            Button(this).apply {
                setText(R.string.remove)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(searchInput)
                    if (runtimeBinder?.removePackage(searchInput.text.toString()) == true) {
                        cancelButton.isEnabled = true
                    }
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
        cancelButton =
            Button(this).apply {
                setText(R.string.cancel)
                isEnabled = false
                setOnClickListener {
                    isEnabled = false
                    runtimeBinder?.cancelPackageOperation()
                }
            }
        val jobRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    jobStatusView,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    cancelButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        storageStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                setText(R.string.document_import_prompt)
                setBackgroundColor(Color.rgb(24, 28, 31))
                maxLines = 2
            }
        importButton =
            Button(this).apply {
                setText(R.string.import_file)
                setOnClickListener { openAndroidDocument() }
            }
        val storageRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    storageStatusView,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    importButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        folderStatusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                setText(R.string.folder_grant_disconnected)
                setBackgroundColor(Color.rgb(31, 35, 38))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        folderButton =
            Button(this).apply {
                setText(R.string.connect_folder)
                setOnClickListener { openAndroidFolder() }
            }
        folderDisconnectButton =
            Button(this).apply {
                setText(R.string.disconnect_folder)
                isEnabled = false
                setOnClickListener {
                    isEnabled = false
                    runtimeBinder?.disconnectAndroidFolder()
                }
            }
        folderMirrorButton =
            Button(this).apply {
                setText(R.string.mirror_folder)
                isEnabled = false
                setOnClickListener {
                    isEnabled = false
                    runtimeBinder?.mirrorAndroidFolder()
                }
            }
        val folderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(31, 35, 38))
                addView(
                    folderStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        addView(
                            folderButton,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        addView(
                            folderMirrorButton,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        addView(
                            folderDisconnectButton,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ),
                )
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
                    jobRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64),
                    ),
                )
                addView(
                    storageRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64),
                    ),
                )
                addView(
                    folderRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(104),
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
        val restoredImport =
            savedInstanceState
                ?.getString(PENDING_IMPORT_URI_STATE)
                ?.let(Uri::parse)
        if (restoredImport != null) {
            queueDocumentImport(restoredImport)
        } else {
            queueIncomingImport(intent)
        }
        val restoredFolder =
            savedInstanceState
                ?.getString(PENDING_FOLDER_URI_STATE)
                ?.let(Uri::parse)
        if (restoredFolder != null) {
            queueFolderGrant(
                restoredFolder,
                savedInstanceState.getInt(PENDING_FOLDER_FLAGS_STATE),
            )
        }
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
        keepServiceAfterFinish =
            runtimeBinder?.let { binder ->
                binder.sharedShellRunning ||
                    binder.documentImportRunning ||
                    binder.folderGrantRunning
            } == true
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueIncomingImport(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImportUri?.let { uri ->
            outState.putString(PENDING_IMPORT_URI_STATE, uri.toString())
        }
        pendingFolderUri?.let { uri ->
            outState.putString(PENDING_FOLDER_URI_STATE, uri.toString())
            outState.putInt(PENDING_FOLDER_FLAGS_STATE, pendingFolderFlags)
        }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Android's framework result callback is used without an AndroidX dependency")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            return
        }
        when (requestCode) {
            IMPORT_DOCUMENT_REQUEST -> data?.data?.let(::queueDocumentImport)
            FOLDER_GRANT_REQUEST -> {
                val uri = data?.data ?: return
                queueFolderGrant(uri, data.flags)
            }
        }
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
        dispatchPendingImport()
        dispatchPendingFolderGrant()
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
            setTextIfChanged(
                storageStatusView,
                runtimeBinder?.documentImportStatus ?: getString(
                    R.string.document_import_unavailable,
                ),
            )
            setTextIfChanged(
                folderStatusView,
                runtimeBinder?.folderGrantStatus ?: getString(
                    R.string.folder_grant_unavailable,
                ),
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
        setTextIfChanged(
            storageStatusView,
            runtimeBinder?.documentImportStatus ?: getString(
                R.string.document_import_unavailable,
            ),
        )
        setTextIfChanged(
            folderStatusView,
            runtimeBinder?.folderGrantStatus ?: getString(
                R.string.folder_grant_unavailable,
            ),
        )
        updatePackageActions()
    }

    private fun updatePackageActions() {
        val binder = runtimeBinder
        if (binder == null) {
            installButton.isEnabled = false
            removeButton.isEnabled = false
            cancelButton.isEnabled = false
            commandButton.isEnabled = false
            ptyButton.isEnabled = false
            importButton.isEnabled = false
            folderButton.isEnabled = false
            folderMirrorButton.isEnabled = false
            folderDisconnectButton.isEnabled = false
            shellSpinner.isEnabled = false
            updateShellPresentation(false)
            return
        }
        updateShellPresentation(binder.sharedShellRunning)
        updateShellSelector(binder)
        setTextIfChanged(installButton, binder.packagePrimaryActionLabel)
        installButton.isEnabled = binder.packagePrimaryActionAvailable
        removeButton.isEnabled = binder.packageRemoveAvailable
        cancelButton.isEnabled = binder.packageCancellationAvailable
        setTextIfChanged(commandButton, binder.linuxInputActionLabel)
        commandButton.isEnabled = binder.linuxCommandAvailable
        setTextIfChanged(ptyButton, binder.sharedShellActionLabel)
        ptyButton.isEnabled = binder.sharedShellActionAvailable
        importButton.isEnabled = binder.documentImportAvailable && pendingImportUri == null
        setTextIfChanged(folderButton, binder.folderGrantActionLabel)
        folderButton.isEnabled = binder.folderGrantAvailable && pendingFolderUri == null
        setTextIfChanged(folderMirrorButton, binder.folderMirrorActionLabel)
        folderMirrorButton.isEnabled = binder.folderMirrorAvailable
        folderDisconnectButton.isEnabled = binder.folderDisconnectAvailable
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

    private fun openAndroidDocument() {
        val picker =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, IMPORT_DOCUMENT_REQUEST)
    }

    private fun openAndroidFolder() {
        val picker =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, FOLDER_GRANT_REQUEST)
    }

    private fun queueFolderGrant(
        uri: Uri,
        resultFlags: Int,
    ) {
        val encodedBytes = uri.toString().toByteArray(Charsets.UTF_8).size
        val grantedFlags =
            resultFlags and
                (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
        if (
            uri.scheme != "content" ||
            encodedBytes !in 1..MAX_FOLDER_URI_BYTES ||
            !DocumentsContract.isTreeUri(uri) ||
            grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            grantedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0
        ) {
            setTextIfChanged(
                folderStatusView,
                getString(R.string.folder_grant_invalid),
            )
            return
        }
        if (pendingFolderUri != null) {
            setTextIfChanged(
                folderStatusView,
                getString(R.string.folder_grant_already_queued),
            )
            return
        }
        pendingFolderUri = uri
        pendingFolderFlags = grantedFlags
        setTextIfChanged(folderStatusView, getString(R.string.folder_grant_queued))
        dispatchPendingFolderGrant()
    }

    private fun dispatchPendingFolderGrant() {
        val uri = pendingFolderUri ?: return
        val binder = runtimeBinder ?: return
        if (binder.connectAndroidFolder(uri, pendingFolderFlags)) {
            pendingFolderUri = null
            pendingFolderFlags = 0
        }
    }

    private fun queueIncomingImport(source: Intent) {
        val uri =
            when (source.action) {
                Intent.ACTION_VIEW -> source.data
                Intent.ACTION_SEND ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        source.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                else -> null
            }
        if (uri != null) {
            source.action = null
            source.data = null
            source.removeExtra(Intent.EXTRA_STREAM)
            queueDocumentImport(uri)
        }
    }

    private fun queueDocumentImport(uri: Uri) {
        if (uri.scheme != "content") {
            setTextIfChanged(
                storageStatusView,
                getString(R.string.document_import_content_only),
            )
            return
        }
        if (pendingImportUri != null) {
            setTextIfChanged(
                storageStatusView,
                getString(R.string.document_import_already_queued),
            )
            return
        }
        pendingImportUri = uri
        setTextIfChanged(storageStatusView, getString(R.string.document_import_queued))
        dispatchPendingImport()
    }

    private fun dispatchPendingImport() {
        val uri = pendingImportUri ?: return
        val binder = runtimeBinder ?: return
        if (binder.importAndroidDocument(uri)) {
            pendingImportUri = null
        }
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
        private const val IMPORT_DOCUMENT_REQUEST = 0x4153
        private const val FOLDER_GRANT_REQUEST = 0x4154
        private const val PENDING_IMPORT_URI_STATE = "pending_import_uri"
        private const val PENDING_FOLDER_URI_STATE = "pending_folder_uri"
        private const val PENDING_FOLDER_FLAGS_STATE = "pending_folder_flags"
        private const val MAX_FOLDER_URI_BYTES = 4 * 1024
        private var activityGeneration = 0
    }
}
