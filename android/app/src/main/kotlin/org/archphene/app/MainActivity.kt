package org.archphene.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.archphene.app.runtime.ArchpheneRuntimeService
import org.archphene.app.runtime.AvailablePackageSnapshot
import org.archphene.app.runtime.InstalledPackageSnapshot
import org.archphene.app.runtime.NativeRuntime
import org.archphene.app.runtime.RuntimeSnapshot

class MainActivity : Activity(), Choreographer.FrameCallback {
    private lateinit var statusView: TextView
    private lateinit var catalogStatusView: TextView
    private lateinit var packageSearchInput: EditText
    private lateinit var searchStatusView: TextView
    private lateinit var packageSearchResults: ScrollView
    private lateinit var installedPackagePanel: LinearLayout
    private lateinit var installedPackageStatusView: TextView
    private lateinit var desktopEntryStatusView: TextView
    private lateinit var installedPackageList: ListView
    private lateinit var installedPackageAdapter: InstalledPackageAdapter
    private lateinit var installedPackagesButton: Button
    private lateinit var searchResultsButton: Button
    private lateinit var availablePackagePanel: LinearLayout
    private lateinit var availablePackageStatusView: TextView
    private lateinit var availablePackageList: ListView
    private lateinit var availablePackageAdapter: AvailablePackageAdapter
    private lateinit var jobStatusView: TextView
    private lateinit var packageJobTitleView: TextView
    private lateinit var packageJobActivityView: TextView
    private lateinit var packageJobProgressTrack: LinearLayout
    private lateinit var packageJobProgressFill: View
    private lateinit var packageJobProgressRemainder: View
    private lateinit var packageJobContainer: View
    private var packageJobRenderedProgress = 0
    private lateinit var commandStatusView: TextView
    private lateinit var storageStatusView: TextView
    private lateinit var folderStatusView: TextView
    private lateinit var runtimeSurface: RuntimeSurfaceView
    private lateinit var managerPanel: FrameLayout
    private lateinit var packagePanel: LinearLayout
    private lateinit var filesPanel: ScrollView
    private lateinit var terminalEmptyPanel: LinearLayout
    private lateinit var terminalControls: LinearLayout
    private lateinit var runtimePanel: FrameLayout
    private lateinit var packagesNavigationButton: Button
    private lateinit var filesNavigationButton: Button
    private lateinit var terminalNavigationButton: Button
    private lateinit var installButton: Button
    private lateinit var removeButton: Button
    private lateinit var aurReviewButton: Button
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
    private var debugRuntimeEvidenceEnabled = false
    private var debugStatusGeneration = Long.MIN_VALUE
    private var debugStatusLifecycle = Int.MIN_VALUE
    private var debugStatusRootReady = false
    private var debugStatusJobsReady = false
    private var debugStatusPacmanReady = false
    private var debugStatusDrainedEvents = Long.MIN_VALUE
    private var runtimeBinder: ArchpheneRuntimeService.LocalBinder? = null
    private var serviceBound = false
    private var frameCallbackActive = false
    private var statusFrameCountdown = 0
    private var keepServiceAfterFinish = false
    private var shellCatalogRevision = Int.MIN_VALUE
    private var installedPackageRevision = Int.MIN_VALUE
    private var availablePackageRevision = Int.MIN_VALUE
    private var packageJobListRevision = Int.MIN_VALUE
    private var storageOnboardingDialog: AlertDialog? = null
    private var launcherPermissionDialog: AlertDialog? = null
    private var launcherCancellationDialog: AlertDialog? = null
    private var launcherPermissionDeferred = false
    private var pendingImportUri: Uri? = null
    private var pendingFolderUri: Uri? = null
    private var pendingFolderFlags = 0
    private var selectedManagerSection = MANAGER_SECTION_PACKAGES
    private var wideManagerLayout = false
    private var showingInstalledPackages = true
    private var showingPackageDetails = false
    private var lastSubmittedSearchQuery = ""

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
                runtimeBinder?.resumeLauncherPublisher()
                updateStatus()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runtimeBinder = null
                statusView.contentDescription = null
                debugStatusGeneration = Long.MIN_VALUE
                statusView.setText(R.string.runtime_unavailable)
                catalogStatusView.setText(R.string.package_catalog_unavailable)
                searchStatusView.setText(R.string.package_search_unavailable)
                installedPackageStatusView.setText(R.string.installed_packages_unavailable)
                desktopEntryStatusView.setText(R.string.desktop_entries_unavailable)
                availablePackageStatusView.setText(R.string.available_packages_unavailable)
                jobStatusView.setText(R.string.package_job_unavailable)
                commandStatusView.setText(R.string.linux_command_unavailable)
                storageStatusView.setText(R.string.document_import_unavailable)
                folderStatusView.setText(R.string.folder_grant_unavailable)
                installButton.isEnabled = false
                removeButton.isEnabled = false
                cancelButton.isEnabled = false
                cancelButton.visibility = View.GONE
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
        selectedManagerSection =
            (
                savedInstanceState?.getInt(
                    MANAGER_SECTION_STATE,
                    MANAGER_SECTION_PACKAGES,
                ) ?: MANAGER_SECTION_PACKAGES
            ).coerceIn(MANAGER_SECTION_PACKAGES, MANAGER_SECTION_TERMINAL)
        showingInstalledPackages =
            savedInstanceState?.getBoolean(PACKAGE_RESULT_MODE_STATE, true) ?: true
        showingPackageDetails =
            savedInstanceState?.getBoolean(PACKAGE_DETAILS_MODE_STATE, false) ?: false
        lastSubmittedSearchQuery =
            savedInstanceState?.getString(PACKAGE_SEARCH_QUERY_STATE).orEmpty()
        wideManagerLayout =
            resources.configuration.screenWidthDp >= WIDE_MANAGER_BREAKPOINT_DP
        debugRuntimeEvidenceEnabled =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.i(TAG, "Activity created generation=$activityGeneration")
        statusView =
            TextView(this).apply {
                setText(R.string.runtime_starting)
                setTextColor(getColor(R.color.archphene_on_primary))
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(4))
                setBackgroundColor(getColor(R.color.archphene_primary))
                maxLines = 1
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
                setTextColor(getColor(R.color.archphene_on_surface))
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
                setBackgroundColor(getColor(R.color.archphene_surface_variant))
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
        packageSearchInput = searchInput
        savedInstanceState
            ?.getString(PACKAGE_QUERY_STATE)
            ?.let(searchInput::setText)
        val searchButton =
            Button(this).apply {
                setText(R.string.search)
                setOnClickListener {
                    hideKeyboard(searchInput)
                    val query = searchInput.text.toString()
                    lastSubmittedSearchQuery = query.trim()
                    showPackageSearchResults(restoreQuery = false)
                    runtimeBinder?.searchPackages(query)
                }
            }
        val detailsButton =
            Button(this).apply {
                setText(R.string.details)
                setOnClickListener {
                    hideKeyboard(searchInput)
                    showPackageDetails()
                    runtimeBinder?.resolvePackage(searchInput.text.toString())
                }
            }
        aurReviewButton =
            Button(this).apply {
                setText(R.string.aur)
                contentDescription = getString(R.string.aur_review_description)
                setOnClickListener {
                    hideKeyboard(searchInput)
                    showPackageDetails()
                    val packageName = searchInput.text.toString().trim()
                    val binder = runtimeBinder
                    val accepted =
                        if (
                            binder?.aurBuildAvailable == true &&
                            binder.aurReviewedPackage == packageName
                        ) {
                            binder.buildAurPackage(packageName)
                        } else if (
                            binder?.aurSourcesAvailable == true &&
                            binder.aurReviewedPackage == packageName
                        ) {
                            binder.verifyAurSources(packageName)
                        } else {
                            binder?.reviewAurPackage(packageName) == true
                        }
                    if (!accepted) {
                        searchStatusView.setText(R.string.aur_review_unavailable)
                    }
                }
            }
        installButton =
            Button(this).apply {
                setText(R.string.install)
                isEnabled = false
                setOnClickListener {
                    hideKeyboard(searchInput)
                    if (runtimeBinder?.installPackage(searchInput.text.toString()) == true) {
                        updateStatus()
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
                        updateStatus()
                    }
                }
            }
        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(text: Editable?) {
                    updatePackageSelectionActions()
                }
            },
        )
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(searchInput)
                val query = searchInput.text.toString()
                lastSubmittedSearchQuery = query.trim()
                showPackageSearchResults(restoreQuery = false)
                runtimeBinder?.searchPackages(query)
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
                    aurReviewButton,
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
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setText(R.string.package_search_prompt)
                setTextIsSelectable(true)
                setBackgroundColor(getColor(R.color.archphene_surface))
            }
        packageSearchResults =
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
        installedPackageStatusView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface_muted))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setText(R.string.installed_packages_loading)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        desktopEntryStatusView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface_muted))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setText(R.string.desktop_entries_loading)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        installedPackageAdapter = InstalledPackageAdapter()
        installedPackageList =
            ListView(this).apply {
                adapter = installedPackageAdapter
                dividerHeight = 1
                setBackgroundColor(getColor(R.color.archphene_surface))
                setOnItemClickListener { _, _, position, _ ->
                    val packageName = installedPackageAdapter.packageName(position) ?: return@setOnItemClickListener
                    searchInput.setText(packageName)
                    searchInput.setSelection(packageName.length)
                    showPackageDetails()
                    runtimeBinder?.resolvePackage(packageName)
                }
            }
        installedPackagePanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    installedPackageStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(40),
                    ),
                )
                addView(
                    desktopEntryStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56),
                    ),
                )
                addView(
                    installedPackageList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        availablePackageStatusView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface_muted))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setText(R.string.package_search_prompt)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        availablePackageAdapter = AvailablePackageAdapter()
        availablePackageList =
            ListView(this).apply {
                adapter = availablePackageAdapter
                dividerHeight = 1
                setBackgroundColor(getColor(R.color.archphene_surface))
                setOnItemClickListener { _, _, position, _ ->
                    val packageName =
                        availablePackageAdapter.packageName(position)
                            ?: return@setOnItemClickListener
                    searchInput.setText(packageName)
                    searchInput.setSelection(packageName.length)
                    showPackageDetails()
                    runtimeBinder?.resolvePackage(packageName)
                }
            }
        availablePackagePanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    availablePackageStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(40),
                    ),
                )
                addView(
                    availablePackageList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        installedPackagesButton =
            Button(this).apply {
                setText(R.string.installed_packages)
                setOnClickListener { showPackageResultMode(true) }
            }
        searchResultsButton =
            Button(this).apply {
                setText(R.string.search_results)
                setOnClickListener { showPackageSearchResults() }
            }
        val resultModeRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    installedPackagesButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(
                    searchResultsButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
            }
        val packageResultSurface =
            FrameLayout(this).apply {
                addView(
                    packageSearchResults,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    availablePackagePanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    installedPackagePanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        jobStatusView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), dp(4))
                setText(R.string.package_job_empty)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
        packageJobTitleView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
                setText(R.string.package_activity_heading)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        packageJobActivityView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface_muted))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        packageJobProgressFill =
            View(this).apply {
                setBackgroundColor(getColor(R.color.archphene_primary))
            }
        packageJobProgressRemainder =
            View(this).apply {
                setBackgroundColor(getColor(R.color.archphene_surface))
            }
        packageJobProgressTrack =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.INVISIBLE
                addView(
                    packageJobProgressFill,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f),
                )
                addView(
                    packageJobProgressRemainder,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f),
                )
            }
        cancelButton =
            Button(this).apply {
                setText(R.string.cancel)
                isEnabled = false
                visibility = View.GONE
                setOnClickListener {
                    val binder = runtimeBinder ?: return@setOnClickListener
                    when {
                        binder.aurBuildCancellationAvailable -> {
                            isEnabled = false
                            binder.cancelAurBuild()
                        }
                        binder.packageCancellationAvailable -> {
                            isEnabled = false
                            binder.cancelPackageOperation()
                        }
                        binder.packageCacheRecoveryAvailable -> {
                            isEnabled = false
                            if (binder.clearPackageCache()) {
                                updateStatus()
                            }
                        }
                        binder.packageRecoveryAvailable -> {
                            val packageName = binder.packageJobName
                            packageSearchInput.setText(packageName)
                            packageSearchInput.setSelection(packageName.length)
                            showPackageDetails()
                            binder.resolvePackage(packageName)
                        }
                    }
                }
            }
        val jobRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.archphene_surface_variant))
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(
                                    packageJobTitleView,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        0,
                                        1f,
                                    ),
                                )
                                addView(
                                    packageJobActivityView,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        0,
                                        1f,
                                    ),
                                )
                            },
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
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64),
                    ),
                )
                addView(
                    packageJobProgressTrack,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(6),
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    },
                )
                addView(
                    jobStatusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        packageJobContainer = jobRow
        storageStatusView =
            TextView(this).apply {
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                setText(R.string.document_import_prompt)
                setBackgroundColor(getColor(R.color.archphene_surface))
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
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                setText(R.string.folder_grant_disconnected)
                setBackgroundColor(getColor(R.color.archphene_surface_variant))
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
                    runtimeBinder?.mirrorAndroidFolder()
                }
            }
        val folderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.archphene_surface_variant))
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
                setBackgroundColor(getColor(R.color.archphene_surface_variant))
                addView(
                    TextView(this@MainActivity).apply {
                        setText(R.string.installed_shell)
                        setTextColor(getColor(R.color.archphene_on_surface))
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
                setTextColor(getColor(R.color.archphene_on_surface))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setText(R.string.linux_command_unavailable)
                setBackgroundColor(getColor(R.color.archphene_surface))
                maxLines = 2
                setTextIsSelectable(true)
            }
        packagePanel =
            LinearLayout(this).apply {
                orientation =
                    if (wideManagerLayout) {
                        LinearLayout.HORIZONTAL
                    } else {
                        LinearLayout.VERTICAL
                    }
                setBackgroundColor(getColor(R.color.archphene_background))
                if (wideManagerLayout) {
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                managerSectionHeading(R.string.packages),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(56),
                                ),
                            )
                            addView(
                                catalogRow,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(64),
                                ),
                            )
                            addView(
                                searchInput,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(56),
                                ),
                            )
                            addView(
                                actionRow,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(64),
                                ),
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f),
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), 0, 0, 0)
                            addView(
                                managerSectionHeading(R.string.package_results_heading),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(56),
                                ),
                            )
                            addView(
                                resultModeRow,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(48),
                                ),
                            )
                            addView(
                                packageResultSurface,
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
                                    dp(140),
                                ),
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f),
                    )
                } else {
                    addView(
                        managerSectionHeading(R.string.packages),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(56),
                        ),
                    )
                    addView(
                        catalogRow,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(56),
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
                            dp(56),
                        ),
                    )
                    addView(
                        resultModeRow,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(48),
                        ),
                    )
                    addView(
                        packageResultSurface,
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
                            dp(140),
                        ),
                    )
                }
            }
        showPackageResultMode(showingInstalledPackages)
        filesPanel =
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(getColor(R.color.archphene_background))
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(16))
                        addView(
                            managerSectionHeading(R.string.files_heading),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(56),
                            ),
                        )
                        addView(
                            TextView(this@MainActivity).apply {
                                setText(R.string.files_description)
                                setTextColor(getColor(R.color.archphene_on_surface_muted))
                                textSize = 15f
                                setPadding(0, 0, 0, dp(12))
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        if (wideManagerLayout) {
                            (importButton.layoutParams as LinearLayout.LayoutParams).apply {
                                height = dp(64)
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            addView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    addView(
                                        storageRow,
                                        LinearLayout.LayoutParams(
                                            0,
                                            dp(WIDE_FILE_CARD_HEIGHT_DP),
                                            1f,
                                        ),
                                    )
                                    addView(
                                        folderRow,
                                        LinearLayout.LayoutParams(
                                            0,
                                            dp(WIDE_FILE_CARD_HEIGHT_DP),
                                            1f,
                                        ).apply {
                                            marginStart = dp(16)
                                        },
                                    )
                                },
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(WIDE_FILE_CARD_HEIGHT_DP),
                                ),
                            )
                        } else {
                            addView(
                                storageRow,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(72),
                                ),
                            )
                            addView(
                                folderRow,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(120),
                                ).apply {
                                    topMargin = dp(12)
                                },
                            )
                        }
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        terminalEmptyPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(getColor(R.color.archphene_background))
                addView(
                    TextView(this@MainActivity).apply {
                        setText(R.string.terminal_heading)
                        setTextColor(getColor(R.color.archphene_on_surface))
                        textSize = 22f
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                if (
                    resources.configuration.screenHeightDp >=
                    MIN_TERMINAL_DESCRIPTION_HEIGHT_DP
                ) {
                    addView(
                        TextView(this@MainActivity).apply {
                            setText(R.string.terminal_description)
                            setTextColor(getColor(R.color.archphene_on_surface_muted))
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setPadding(0, dp(12), 0, 0)
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            }
        managerPanel =
            FrameLayout(this).apply {
                setBackgroundColor(getColor(R.color.archphene_background))
                addView(
                    packagePanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    filesPanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    terminalEmptyPanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    runtimePanel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        terminalControls =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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
            }
        packagesNavigationButton =
            managerNavigationButton(R.string.packages, MANAGER_SECTION_PACKAGES)
        filesNavigationButton =
            managerNavigationButton(R.string.files, MANAGER_SECTION_FILES)
        terminalNavigationButton =
            managerNavigationButton(R.string.terminal, MANAGER_SECTION_TERMINAL)
        val navigationSurface =
            LinearLayout(this).apply {
                orientation =
                    if (wideManagerLayout) {
                        LinearLayout.VERTICAL
                    } else {
                        LinearLayout.HORIZONTAL
                    }
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(getColor(R.color.archphene_surface))
                if (wideManagerLayout) {
                    addView(
                        TextView(this@MainActivity).apply {
                            setText(R.string.app_name)
                            setTextColor(getColor(R.color.archphene_on_surface))
                            textSize = 18f
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), 0, dp(12), 0)
                            maxLines = 1
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ),
                    )
                    addView(
                        packagesNavigationButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ),
                    )
                    addView(
                        filesNavigationButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ),
                    )
                    addView(
                        terminalNavigationButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ),
                    )
                } else {
                    addView(
                        packagesNavigationButton,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                    )
                    addView(
                        filesNavigationButton,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                    )
                    addView(
                        terminalNavigationButton,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                    )
                }
            }
        val contentColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    statusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64),
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
                    terminalControls,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(152),
                    ),
                )
                if (!wideManagerLayout) {
                    addView(
                        navigationSurface,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(64),
                        ),
                    )
                }
            }
        val layout =
            if (wideManagerLayout) {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        navigationSurface,
                        LinearLayout.LayoutParams(
                            dp(WIDE_NAVIGATION_WIDTH_DP),
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    addView(
                        contentColumn,
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1f,
                        ),
                    )
                }
            } else {
                contentColumn
            }
        applySystemBarInsets(layout)
        setContentView(layout)
        updateShellPresentation(false)
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
        runtimeBinder?.resumeLauncherPublisher()
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
        val binder = runtimeBinder
        keepServiceAfterFinish = binder?.serviceRetentionRequired == true
        if (isFinishing && keepServiceAfterFinish) {
            binder?.releaseWhenIdle()
        }
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        runtimeBinder = null
        super.onStop()
    }

    override fun onDestroy() {
        storageOnboardingDialog?.setOnDismissListener(null)
        storageOnboardingDialog?.dismiss()
        storageOnboardingDialog = null
        launcherPermissionDialog?.setOnDismissListener(null)
        launcherPermissionDialog?.dismiss()
        launcherPermissionDialog = null
        launcherCancellationDialog?.setOnDismissListener(null)
        launcherCancellationDialog?.dismiss()
        launcherCancellationDialog = null
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
        outState.putInt(MANAGER_SECTION_STATE, selectedManagerSection)
        outState.putBoolean(PACKAGE_RESULT_MODE_STATE, showingInstalledPackages)
        outState.putBoolean(PACKAGE_DETAILS_MODE_STATE, showingPackageDetails)
        outState.putString(PACKAGE_QUERY_STATE, packageSearchInput.text.toString())
        outState.putString(PACKAGE_SEARCH_QUERY_STATE, lastSubmittedSearchQuery)
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
        updateInstalledPackages(runtimeBinder)
        updateAvailablePackages(runtimeBinder)
        val handle = runtimeBinder?.runtimeHandle ?: 0L
        if (!snapshot.read(handle)) {
            statusView.contentDescription = null
            debugStatusGeneration = Long.MIN_VALUE
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
                packageJobDisplayMessage(),
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
            updatePackageActivity()
            updatePackageActions()
            return
        }
        updateDebugRuntimeEvidence()
        setTextIfChanged(statusView, getString(R.string.runtime_ready))
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
            packageJobDisplayMessage(),
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
        updatePackageActivity()
        updatePackageActions()
        maybeShowStorageOnboarding()
        maybeShowLauncherCancellation()
        maybeShowLauncherPermission()
    }

    private fun updateDebugRuntimeEvidence() {
        if (!debugRuntimeEvidenceEnabled) {
            return
        }
        val rootReady = snapshot.archRootReady
        val jobsReady = snapshot.jobStoreReady
        val pacmanReady = snapshot.packageRuntimeReady
        if (
            debugStatusGeneration == snapshot.generation &&
            debugStatusLifecycle == snapshot.lifecycle &&
            debugStatusRootReady == rootReady &&
            debugStatusJobsReady == jobsReady &&
            debugStatusPacmanReady == pacmanReady &&
            debugStatusDrainedEvents == snapshot.drainedEvents
        ) {
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
            .append(if (rootReady) "ready" else "pending")
            .append(" · jobs ")
            .append(if (jobsReady) "ready" else "pending")
            .append('\n')
            .append("Pacman ")
            .append(if (pacmanReady) "ready" else "pending")
            .append(" · events ")
            .append(snapshot.drainedEvents)
        statusView.contentDescription = statusText.toString()
        debugStatusGeneration = snapshot.generation
        debugStatusLifecycle = snapshot.lifecycle
        debugStatusRootReady = rootReady
        debugStatusJobsReady = jobsReady
        debugStatusPacmanReady = pacmanReady
        debugStatusDrainedEvents = snapshot.drainedEvents
    }

    private fun maybeShowStorageOnboarding() {
        val binder = runtimeBinder ?: return
        if (
            !binder.storageOnboardingRequired ||
            storageOnboardingDialog != null ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.storage_onboarding_title)
                .setMessage(R.string.storage_onboarding_message)
                .setPositiveButton(R.string.storage_onboarding_choose) { _, _ ->
                    binder.completeStorageOnboarding()
                    openAndroidFolder()
                }.setNegativeButton(R.string.storage_onboarding_skip) { _, _ ->
                    binder.completeStorageOnboarding()
                }.setOnCancelListener {
                    binder.completeStorageOnboarding()
                }.create()
        dialog.setOnDismissListener {
            if (storageOnboardingDialog === dialog) {
                storageOnboardingDialog = null
            }
        }
        storageOnboardingDialog = dialog
        dialog.show()
    }

    private fun maybeShowLauncherPermission() {
        val binder = runtimeBinder ?: return
        if (
            !binder.launcherInstallPermissionRequired ||
            launcherPermissionDialog != null ||
            launcherPermissionDeferred ||
            storageOnboardingDialog != null ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.launcher_permission_title)
                .setMessage(R.string.launcher_permission_message)
                .setPositiveButton(R.string.launcher_permission_settings) { _, _ ->
                    launcherPermissionDeferred = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }.setNegativeButton(R.string.launcher_permission_later) { _, _ ->
                    launcherPermissionDeferred = true
                }
                .create()
        dialog.setOnDismissListener {
            if (launcherPermissionDialog === dialog) {
                launcherPermissionDialog = null
            }
        }
        launcherPermissionDialog = dialog
        dialog.show()
    }

    private fun maybeShowLauncherCancellation() {
        val binder = runtimeBinder ?: return
        val count = binder.cancelledLauncherCount
        if (
            count == 0 ||
            launcherCancellationDialog != null ||
            storageOnboardingDialog != null ||
            launcherPermissionDialog != null ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.launcher_cancelled_title)
                .setMessage(
                    resources.getQuantityString(
                        R.plurals.launcher_cancelled_message,
                        count,
                        count,
                    ),
                ).setPositiveButton(R.string.launcher_cancelled_retry) { _, _ ->
                    binder.retryCancelledLauncher()
                }.setNegativeButton(R.string.launcher_cancelled_dismiss) { _, _ ->
                    binder.dismissCancelledLauncher()
                }.setCancelable(false)
                .create()
        dialog.setOnDismissListener {
            if (launcherCancellationDialog === dialog) {
                launcherCancellationDialog = null
            }
        }
        launcherCancellationDialog = dialog
        dialog.show()
    }

    private fun showPackageResultMode(installedPackages: Boolean) {
        showingInstalledPackages = installedPackages
        installedPackagePanel.visibility =
            if (installedPackages) View.VISIBLE else View.GONE
        packageSearchResults.visibility =
            if (!installedPackages && showingPackageDetails) View.VISIBLE else View.GONE
        availablePackagePanel.visibility =
            if (!installedPackages && !showingPackageDetails) View.VISIBLE else View.GONE
        installedPackagesButton.isSelected = installedPackages
        searchResultsButton.isSelected = !installedPackages
        installedPackagesButton.setTextColor(
            getColor(
                if (installedPackages) {
                    R.color.archphene_primary
                } else {
                    R.color.archphene_on_surface
                },
            ),
        )
        searchResultsButton.setTextColor(
            getColor(
                if (installedPackages) {
                    R.color.archphene_on_surface
                } else {
                    R.color.archphene_primary
                },
            ),
        )
    }

    private fun showPackageSearchResults(restoreQuery: Boolean = true) {
        if (
            restoreQuery &&
            lastSubmittedSearchQuery.isNotEmpty() &&
            packageSearchInput.text.toString() != lastSubmittedSearchQuery
        ) {
            packageSearchInput.setText(lastSubmittedSearchQuery)
            packageSearchInput.setSelection(lastSubmittedSearchQuery.length)
        }
        showingPackageDetails = false
        showPackageResultMode(false)
    }

    private fun showPackageDetails() {
        showingPackageDetails = true
        showPackageResultMode(false)
    }

    private fun updateInstalledPackages(binder: ArchpheneRuntimeService.LocalBinder?) {
        if (binder == null) {
            setTextIfChanged(
                installedPackageStatusView,
                getString(R.string.installed_packages_unavailable),
            )
            return
        }
        val packages = binder.installedPackages
        val desktopEntries = binder.desktopEntries
        setTextIfChanged(installedPackageStatusView, packages.status)
        setTextIfChanged(desktopEntryStatusView, desktopEntries.status)
        if (packages.revision == installedPackageRevision) {
            return
        }
        val previousCount = installedPackageAdapter.count
        val hadSnapshot = installedPackageRevision != Int.MIN_VALUE
        installedPackageRevision = packages.revision
        installedPackageAdapter.submit(packages)
        if (hadSnapshot && previousCount != packages.names.size) {
            showPackageResultMode(true)
        }
    }

    private fun updateAvailablePackages(binder: ArchpheneRuntimeService.LocalBinder?) {
        if (binder == null) {
            setTextIfChanged(
                availablePackageStatusView,
                getString(R.string.available_packages_unavailable),
            )
            return
        }
        val packages = binder.availablePackages
        setTextIfChanged(availablePackageStatusView, packages.status)
        if (packages.revision != availablePackageRevision) {
            availablePackageRevision = packages.revision
            availablePackageAdapter.submit(packages)
        }
        if (binder.packageJobRevision != packageJobListRevision) {
            packageJobListRevision = binder.packageJobRevision
            availablePackageAdapter.submitJob(
                binder.packageJobName,
                binder.packageJobActivityLabel,
                binder.packageJobMessage,
                binder.packageJobState,
            )
            if (binder.packageJobState == NativeRuntime.JOB_QUEUED) {
                showPackageSearchResults()
            }
        }
    }

    private inner class AvailablePackageAdapter : BaseAdapter() {
        private var packages: AvailablePackageSnapshot? = null
        private var jobPackage = ""
        private var jobLabel = ""
        private var jobMessage = ""
        private var jobState = 0
        private var jobPackageIndex = -1

        fun submit(snapshot: AvailablePackageSnapshot) {
            packages = snapshot
            jobPackageIndex = snapshot.names.indexOf(jobPackage)
            notifyDataSetChanged()
        }

        fun submitJob(
            packageName: String,
            label: String,
            message: String,
            state: Int,
        ) {
            jobPackage = packageName
            jobLabel = label
            jobMessage = message
            jobState = state
            jobPackageIndex = packages?.names?.indexOf(packageName) ?: -1
            notifyDataSetChanged()
        }

        fun packageName(position: Int): String? {
            val snapshot = packages
            if (snapshot != null && position in snapshot.names.indices) {
                return snapshot.names[position]
            }
            return jobPackage.takeIf { position == snapshot?.names?.size && appendActiveJob() }
        }

        override fun getCount(): Int {
            val packageCount = packages?.names?.size ?: 0
            return packageCount + if (appendActiveJob()) 1 else 0
        }

        override fun getItem(position: Int): Any? = packageName(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row: LinearLayout
            val views: AvailablePackageRowViews
            if (convertView is LinearLayout && convertView.tag is AvailablePackageRowViews) {
                row = convertView
                views = convertView.tag as AvailablePackageRowViews
            } else {
                val name =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface))
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                val version =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface_muted))
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                val description =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface_muted))
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                val state =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface_muted))
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        maxLines = 2
                    }
                row =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(76)
                        setPadding(dp(16), dp(6), dp(16), dp(6))
                        setBackgroundColor(getColor(R.color.archphene_surface))
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(name)
                                addView(version)
                                addView(description)
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ),
                        )
                        addView(
                            state,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ).apply {
                                marginStart = dp(12)
                            },
                        )
                    }
                views = AvailablePackageRowViews(name, version, description, state)
                row.tag = views
            }
            val snapshot = packages
            if (snapshot != null && position in snapshot.names.indices) {
                setTextIfChanged(views.name, snapshot.names[position])
                setTextIfChanged(views.version, snapshot.versions[position])
                setTextIfChanged(views.description, snapshot.descriptions[position])
                setTextIfChanged(
                    views.state,
                    if (position == jobPackageIndex && jobLabel.isNotEmpty()) {
                        jobLabel
                    } else {
                        snapshot.repositories[position]
                    },
                )
            } else {
                setTextIfChanged(views.name, jobPackage)
                setTextIfChanged(views.version, "")
                setTextIfChanged(views.description, jobMessage)
                setTextIfChanged(views.state, jobLabel)
            }
            return row
        }

        private fun appendActiveJob(): Boolean =
            jobPackage.isNotEmpty() &&
                jobPackageIndex < 0 &&
                isActivePackageJobState(jobState)
    }

    private fun isActivePackageJobState(state: Int): Boolean =
        state == NativeRuntime.JOB_QUEUED ||
            state == NativeRuntime.JOB_RESOLVING ||
            state == NativeRuntime.JOB_DOWNLOADING ||
            state == NativeRuntime.JOB_VERIFYING ||
            state == NativeRuntime.JOB_PUBLISHING ||
            state == NativeRuntime.JOB_BUILDING ||
            state == NativeRuntime.JOB_INSTALLING ||
            state == NativeRuntime.JOB_AWAITING_CONFIRMATION

    private inner class InstalledPackageAdapter : BaseAdapter() {
        private var packages: InstalledPackageSnapshot? = null

        fun submit(snapshot: InstalledPackageSnapshot) {
            packages = snapshot
            notifyDataSetChanged()
        }

        fun packageName(position: Int): String? =
            packages?.names?.getOrNull(position)

        override fun getCount(): Int = packages?.names?.size ?: 0

        override fun getItem(position: Int): Any? = packageName(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row: LinearLayout
            val views: InstalledPackageRowViews
            if (convertView is LinearLayout && convertView.tag is InstalledPackageRowViews) {
                row = convertView
                views = convertView.tag as InstalledPackageRowViews
            } else {
                val name =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface))
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                val version =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface_muted))
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                val reason =
                    TextView(this@MainActivity).apply {
                        setTextColor(getColor(R.color.archphene_on_surface_muted))
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        maxLines = 1
                    }
                row =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(64)
                        setPadding(dp(16), dp(6), dp(16), dp(6))
                        setBackgroundColor(getColor(R.color.archphene_surface))
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    name,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        0,
                                        1f,
                                    ),
                                )
                                addView(
                                    version,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        0,
                                        1f,
                                    ),
                                )
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                1f,
                            ),
                        )
                        addView(
                            reason,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ).apply {
                                marginStart = dp(12)
                            },
                        )
                    }
                views = InstalledPackageRowViews(name, version, reason)
                row.tag = views
            }
            val snapshot = packages
            if (snapshot != null && position in snapshot.names.indices) {
                setTextIfChanged(views.name, snapshot.names[position])
                setTextIfChanged(views.version, snapshot.versions[position])
                views.reason.setText(
                    if (snapshot.explicitlyInstalled[position]) {
                        R.string.package_reason_explicit
                    } else {
                        R.string.package_reason_dependency
                    },
                )
            }
            return row
        }
    }

    private class InstalledPackageRowViews(
        val name: TextView,
        val version: TextView,
        val reason: TextView,
    )

    private class AvailablePackageRowViews(
        val name: TextView,
        val version: TextView,
        val description: TextView,
        val state: TextView,
    )

    private fun updatePackageActions() {
        val binder = runtimeBinder
        if (binder == null) {
            installButton.isEnabled = false
            removeButton.isEnabled = false
            aurReviewButton.isEnabled = false
            cancelButton.isEnabled = false
            cancelButton.visibility = View.GONE
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
        setTextIfChanged(removeButton, binder.packageRemoveActionLabel)
        updatePackageSelectionActions()
        val packageActionAvailable =
            binder.aurBuildCancellationAvailable ||
                binder.packageCancellationAvailable ||
                binder.packageRecoveryAvailable
        setTextIfChanged(cancelButton, binder.packageActivityActionLabel)
        cancelButton.isEnabled = packageActionAvailable
        setVisibilityIfChanged(
            cancelButton,
            if (packageActionAvailable) View.VISIBLE else View.GONE,
        )
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

    private fun updatePackageSelectionActions() {
        val binder = runtimeBinder
        if (binder == null) {
            installButton.isEnabled = false
            removeButton.isEnabled = false
            aurReviewButton.isEnabled = false
            return
        }
        val exactSelection =
            packageSearchInput.text.toString().trim() == binder.resolvedPackageName
        installButton.isEnabled = exactSelection && binder.packagePrimaryActionAvailable
        removeButton.isEnabled = exactSelection && binder.packageRemoveAvailable
        val aurPackage = packageSearchInput.text.toString().trim()
        val sourcesAvailable =
            binder.aurSourcesAvailable && binder.aurReviewedPackage == aurPackage
        val buildAvailable =
            binder.aurBuildAvailable && binder.aurReviewedPackage == aurPackage
        val builtAvailable =
            binder.aurInstallAvailable && binder.aurReviewedPackage == aurPackage
        aurReviewButton.setText(
            when {
                builtAvailable -> R.string.built
                buildAvailable -> R.string.build
                sourcesAvailable -> R.string.verify_sources
                else -> R.string.aur
            },
        )
        aurReviewButton.contentDescription =
            getString(
                when {
                    builtAvailable -> R.string.built_aur_description
                    buildAvailable -> R.string.build_aur_description
                    sourcesAvailable -> R.string.verify_sources_description
                    else -> R.string.aur_review_description
                },
            )
        aurReviewButton.isEnabled =
            aurPackage.isNotEmpty() &&
                when {
                    builtAvailable -> false
                    buildAvailable -> binder.aurBuildAvailable
                    sourcesAvailable -> binder.aurSourcesAvailable
                    else -> binder.aurReviewAvailable
                }
    }

    private fun updatePackageActivity() {
        val binder = runtimeBinder
        val packageName = binder?.packageJobName.orEmpty()
        val terminal =
            when (binder?.packageJobState) {
                NativeRuntime.JOB_COMPLETE,
                NativeRuntime.JOB_FAILED,
                NativeRuntime.JOB_CANCELLED,
                -> true
                else -> false
            }
        val selectedPackage = packageSearchInput.text.toString().trim()
        val visible =
            binder != null &&
                packageName.isNotEmpty() &&
                (
                    !terminal ||
                        (
                            binder.packageTerminalActivityVisible &&
                                selectedPackage == packageName
                        )
                )
        if (packageJobContainer.visibility != if (visible) View.VISIBLE else View.GONE) {
            packageJobContainer.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (!visible) {
            setTextIfChanged(packageJobTitleView, getString(R.string.package_activity_heading))
            setTextIfChanged(packageJobActivityView, "")
            if (packageJobProgressTrack.visibility != View.INVISIBLE) {
                packageJobProgressTrack.visibility = View.INVISIBLE
            }
            if (packageJobRenderedProgress != 0) {
                setPackageJobProgress(0)
            }
            return
        }
        setTextIfChanged(packageJobTitleView, packageName)
        setTextIfChanged(packageJobActivityView, binder.packageJobActivityLabel)
        setTextIfChanged(jobStatusView, binder.packageJobMessage)
        resetHorizontalScroll(packageJobTitleView)
        resetHorizontalScroll(packageJobActivityView)
        resetHorizontalScroll(jobStatusView)
        if (packageJobProgressTrack.visibility != View.VISIBLE) {
            packageJobProgressTrack.visibility = View.VISIBLE
        }
        val progress = binder.packageJobProgress.coerceIn(0, 100)
        if (packageJobRenderedProgress != progress) {
            setPackageJobProgress(progress)
        }
    }

    private fun packageJobDisplayMessage(): String {
        val binder = runtimeBinder ?: return "Package operation unavailable"
        return if (binder.packageJobName.isEmpty()) {
            binder.packageJobStatus
        } else {
            binder.packageJobMessage
        }
    }

    private fun setPackageJobProgress(progress: Int) {
        packageJobRenderedProgress = progress
        (packageJobProgressFill.layoutParams as LinearLayout.LayoutParams).weight =
            progress.toFloat()
        (packageJobProgressRemainder.layoutParams as LinearLayout.LayoutParams).weight =
            (100 - progress).toFloat()
        packageJobProgressFill.requestLayout()
        packageJobProgressRemainder.requestLayout()
        packageJobProgressTrack.contentDescription = "$progress%"
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
        val packagesVisible =
            selectedManagerSection == MANAGER_SECTION_PACKAGES
        val filesVisible =
            selectedManagerSection == MANAGER_SECTION_FILES
        val terminalSelected =
            selectedManagerSection == MANAGER_SECTION_TERMINAL
        setVisibilityIfChanged(
            packagePanel,
            if (packagesVisible) View.VISIBLE else View.GONE,
        )
        setVisibilityIfChanged(
            filesPanel,
            if (filesVisible) View.VISIBLE else View.GONE,
        )
        setVisibilityIfChanged(
            terminalEmptyPanel,
            if (terminalSelected && !shellRunning) View.VISIBLE else View.GONE,
        )
        setVisibilityIfChanged(
            terminalControls,
            if (terminalSelected) View.VISIBLE else View.GONE,
        )
        val terminalVisibility =
            if (terminalSelected && shellRunning) View.VISIBLE else View.GONE
        if (runtimePanel.visibility != terminalVisibility) {
            runtimePanel.visibility = terminalVisibility
            if (terminalVisibility == View.VISIBLE) {
                runtimePanel.post {
                    runtimeSurface.synchronizeTerminalSize(runtimeBinder)
                    runtimeSurface.requestFocus()
                }
            }
        }
        setSelectedIfChanged(packagesNavigationButton, packagesVisible)
        setSelectedIfChanged(filesNavigationButton, filesVisible)
        setSelectedIfChanged(terminalNavigationButton, terminalSelected)
    }

    private fun setVisibilityIfChanged(view: View, visibility: Int) {
        if (view.visibility != visibility) {
            view.visibility = visibility
        }
    }

    private fun setSelectedIfChanged(view: View, selected: Boolean) {
        if (view.isSelected != selected) {
            view.isSelected = selected
        }
    }

    private fun managerSectionHeading(textResource: Int): TextView =
        TextView(this).apply {
            setText(textResource)
            setTextColor(getColor(R.color.archphene_on_surface))
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            maxLines = 1
        }

    private fun managerNavigationButton(
        textResource: Int,
        section: Int,
    ): Button =
        Button(this).apply {
            setText(textResource)
            textSize = 14f
            backgroundTintList =
                getColorStateList(R.color.manager_navigation_background)
            setTextColor(getColorStateList(R.color.manager_navigation_text))
            setOnClickListener {
                selectedManagerSection = section
                updateShellPresentation(runtimeBinder?.sharedShellRunning == true)
            }
        }

    private fun selectManagerSection(section: Int) {
        if (selectedManagerSection == section) {
            return
        }
        selectedManagerSection = section
        if (::packagePanel.isInitialized) {
            updateShellPresentation(runtimeBinder?.sharedShellRunning == true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hideKeyboard(view: android.view.View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun openAndroidDocument() {
        selectManagerSection(MANAGER_SECTION_FILES)
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
        selectManagerSection(MANAGER_SECTION_FILES)
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
        selectManagerSection(MANAGER_SECTION_FILES)
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
        selectManagerSection(MANAGER_SECTION_FILES)
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

    private fun resetHorizontalScroll(view: TextView) {
        if (view.scrollX != 0) {
            view.scrollTo(0, view.scrollY)
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
        private const val MANAGER_SECTION_STATE = "manager_section"
        private const val PACKAGE_RESULT_MODE_STATE = "package_result_mode"
        private const val PACKAGE_DETAILS_MODE_STATE = "package_details_mode"
        private const val PACKAGE_QUERY_STATE = "package_query"
        private const val PACKAGE_SEARCH_QUERY_STATE = "package_search_query"
        private const val MANAGER_SECTION_PACKAGES = 0
        private const val MANAGER_SECTION_FILES = 1
        private const val MANAGER_SECTION_TERMINAL = 2
        private const val MIN_TERMINAL_DESCRIPTION_HEIGHT_DP = 480
        private const val WIDE_MANAGER_BREAKPOINT_DP = 840
        private const val WIDE_NAVIGATION_WIDTH_DP = 176
        private const val WIDE_FILE_CARD_HEIGHT_DP = 136
        private const val MAX_FOLDER_URI_BYTES = 4 * 1024
        private var activityGeneration = 0
    }
}
