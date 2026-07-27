package org.archphene.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.archphene.app.appearance.LinuxAppearanceOverrides
import org.archphene.app.appearance.LinuxAppearancePreferences

internal data class ArchphenePreferenceSnapshot(
    val managerSection: Int = 0,
    val terminalTextSp: Int = 0,
    val appearance: LinuxAppearanceOverrides = LinuxAppearanceOverrides(0, 0, 0),
)

/**
 * Keeps preference-file I/O off Android's main thread while exposing one
 * immutable, allocation-light snapshot to UI construction and launchers.
 */
internal object ArchphenePreferences {
    private const val MANAGER_PREFERENCES = "manager_navigation"
    private const val MANAGER_SECTION = "selected_section"
    private const val TERMINAL_PREFERENCES = "terminal_display"
    private const val TERMINAL_TEXT_SP = "text_sp"
    private const val SHELL_PREFERENCES = "terminal_shell"
    private const val SHELL_ID = "selected_shell_id"
    private const val STORAGE_PREFERENCES = "storage"
    private const val STORAGE_ONBOARDING_SEEN = "folder_onboarding_seen"

    private const val DIRTY_MANAGER = 1
    private const val DIRTY_TERMINAL = 1 shl 1
    private const val DIRTY_GEOMETRY = 1 shl 2
    private const val DIRTY_FONT = 1 shl 3
    private const val DIRTY_CONTROLS = 1 shl 4

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ArchphenePreferences").apply { isDaemon = true }
        }
    private val readyCallbacks = ArrayList<(ArchphenePreferenceSnapshot) -> Unit>(2)

    @Volatile private var current = ArchphenePreferenceSnapshot()
    private var applicationContext: Context? = null
    private var started = false
    private var ready = false
    private var dirty = 0

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (started) {
                return
            }
            started = true
            applicationContext = appContext
        }
        io.execute {
            val manager = readInt(appContext, MANAGER_PREFERENCES, MANAGER_SECTION)
            val terminal = readInt(appContext, TERMINAL_PREFERENCES, TERMINAL_TEXT_SP)
            val appearance =
                try {
                    LinuxAppearancePreferences.read(appContext)
                } catch (error: RuntimeException) {
                    android.util.Log.e(TAG, "Could not read Linux appearance preferences", error)
                    LinuxAppearanceOverrides(0, 0, 0)
                }
            val callbacks: Array<(ArchphenePreferenceSnapshot) -> Unit>
            val loaded: ArchphenePreferenceSnapshot
            synchronized(lock) {
                val prior = current
                loaded =
                    ArchphenePreferenceSnapshot(
                        if (dirty and DIRTY_MANAGER == 0) manager else prior.managerSection,
                        if (dirty and DIRTY_TERMINAL == 0) terminal else prior.terminalTextSp,
                        LinuxAppearanceOverrides(
                            if (dirty and DIRTY_GEOMETRY == 0) {
                                appearance.geometryPercent
                            } else {
                                prior.appearance.geometryPercent
                            },
                            if (dirty and DIRTY_FONT == 0) {
                                appearance.fontPercent
                            } else {
                                prior.appearance.fontPercent
                            },
                            if (dirty and DIRTY_CONTROLS == 0) {
                                appearance.controlVisualDp
                            } else {
                                prior.appearance.controlVisualDp
                            },
                        ),
                    )
                current = loaded
                ready = true
                callbacks = readyCallbacks.toTypedArray()
                readyCallbacks.clear()
            }
            if (callbacks.isNotEmpty()) {
                mainHandler.post {
                    callbacks.forEach { callback -> callback(loaded) }
                }
            }
        }
    }

    fun snapshot(): ArchphenePreferenceSnapshot = current

    fun isReady(): Boolean = synchronized(lock) { ready }

    fun whenReady(callback: (ArchphenePreferenceSnapshot) -> Unit) {
        val snapshot =
            synchronized(lock) {
                if (!ready) {
                    readyCallbacks.add(callback)
                    return
                }
                current
            }
        mainHandler.post { callback(snapshot) }
    }

    fun setManagerSection(section: Int) {
        synchronized(lock) {
            current = current.copy(managerSection = section)
            dirty = dirty or DIRTY_MANAGER
        }
        writePreference(MANAGER_PREFERENCES, MANAGER_SECTION, section)
    }

    fun setTerminalTextSp(textSp: Int) {
        synchronized(lock) {
            current = current.copy(terminalTextSp = textSp)
            dirty = dirty or DIRTY_TERMINAL
        }
        writePreference(TERMINAL_PREFERENCES, TERMINAL_TEXT_SP, textSp)
    }

    fun setAppearance(
        key: String,
        value: Int,
    ) {
        synchronized(lock) {
            val appearance = current.appearance
            current =
                current.copy(
                    appearance =
                        when (key) {
                            LinuxAppearancePreferences.GEOMETRY_PERCENT ->
                                appearance.copy(geometryPercent = value)
                            LinuxAppearancePreferences.FONT_PERCENT ->
                                appearance.copy(fontPercent = value)
                            LinuxAppearancePreferences.CONTROL_VISUAL_DP ->
                                appearance.copy(controlVisualDp = value)
                            else -> throw IllegalArgumentException("Unknown appearance preference")
                        },
                )
            dirty =
                dirty or
                when (key) {
                    LinuxAppearancePreferences.GEOMETRY_PERCENT -> DIRTY_GEOMETRY
                    LinuxAppearancePreferences.FONT_PERCENT -> DIRTY_FONT
                    else -> DIRTY_CONTROLS
                }
        }
        writePreference(LinuxAppearancePreferences.PREFERENCES, key, value)
    }

    fun setShellId(shellId: String) {
        val context = initializedContext()
        io.execute {
            val saved =
                context
                    .getSharedPreferences(SHELL_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(SHELL_ID, shellId)
                    .commit()
            if (!saved) {
                android.util.Log.e(TAG, "Could not persist $SHELL_PREFERENCES/$SHELL_ID")
            }
        }
    }

    fun setStorageOnboardingSeen() {
        val context = initializedContext()
        io.execute {
            val saved =
                context
                    .getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(STORAGE_ONBOARDING_SEEN, true)
                    .commit()
            if (!saved) {
                android.util.Log.e(
                    TAG,
                    "Could not persist $STORAGE_PREFERENCES/$STORAGE_ONBOARDING_SEEN",
                )
            }
        }
    }

    private fun writePreference(
        preferences: String,
        key: String,
        value: Int,
    ) {
        val context = initializedContext()
        io.execute {
            val editor =
                context
                    .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                    .edit()
            if (value == 0) {
                editor.remove(key)
            } else {
                editor.putInt(key, value)
            }
            if (!editor.commit()) {
                android.util.Log.e(TAG, "Could not persist $preferences/$key")
            }
        }
    }

    private fun initializedContext(): Context =
        synchronized(lock) {
            checkNotNull(applicationContext) { "Preferences are not initialized" }
        }

    private fun readInt(
        context: Context,
        preferences: String,
        key: String,
    ): Int =
        try {
            context
                .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                .getInt(key, 0)
        } catch (error: RuntimeException) {
            android.util.Log.e(TAG, "Could not read $preferences/$key", error)
            0
        }

    private const val TAG = "ArchphenePreferences"
}
