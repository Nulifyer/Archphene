package org.archphene.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.archphene.app.appearance.LinuxAppearanceOverrides
import org.archphene.app.appearance.LinuxAppearancePreferences

internal class LatestTaskExecutor<K>(
    maximumPendingKeys: Int,
    threadName: String,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val maximumPendingKeys = maximumPendingKeys.also { require(it > 0) }
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val pending = LinkedHashMap<K, Runnable>(maximumPendingKeys)
    private var running = true
    private val worker =
        Thread(::runTasks, threadName).apply {
            isDaemon = true
            start()
        }

    fun execute(
        key: K,
        task: Runnable,
    ) {
        lock.withLock {
            if (!running) throw RejectedExecutionException("Executor is closed")
            if (!pending.containsKey(key) && pending.size >= maximumPendingKeys) {
                throw RejectedExecutionException("Pending task key limit reached")
            }
            pending.remove(key)
            pending[key] = task
            available.signal()
        }
    }

    internal fun pendingTaskCount(): Int = lock.withLock { pending.size }

    internal fun isWorkerAlive(): Boolean = worker.isAlive

    private fun runTasks() {
        while (true) {
            val task =
                try {
                    lock.withLock {
                        while (running && pending.isEmpty()) available.await()
                        if (!running) return
                        val entry = pending.entries.iterator().next()
                        pending.remove(entry.key)
                        entry.value
                    }
                } catch (_: InterruptedException) {
                    if (lock.withLock { !running }) return
                    continue
                }
            try {
                task.run()
            } catch (error: Throwable) {
                try {
                    onFailure(error)
                } catch (_: Throwable) {
                    // Keep the bounded worker available even if failure reporting fails.
                }
            }
        }
    }

    override fun close() {
        lock.withLock {
            running = false
            pending.clear()
            available.signalAll()
        }
        if (worker !== Thread.currentThread()) {
            worker.interrupt()
            worker.join(WORKER_STOP_MILLIS)
            check(!worker.isAlive) { "Executor worker did not terminate" }
        }
    }

    private companion object {
        const val WORKER_STOP_MILLIS = 2_000L
    }
}

internal data class ArchphenePreferenceSnapshot(
    val managerSection: Int = 0,
    val terminalTextSp: Int = 0,
    val appearance: LinuxAppearanceOverrides = LinuxAppearanceOverrides(0, 0, 0, 0, true),
    val reducedIsolationElectron: Boolean = false,
)

/**
 * Keeps preference-file I/O off Android's main thread while exposing one
 * immutable, allocation-light snapshot to UI construction and launchers.
 */
internal object ArchphenePreferences {
    internal fun interface AppearanceListener {
        fun onAppearanceChanged()
    }

    private const val MANAGER_PREFERENCES = "manager_navigation"
    private const val MANAGER_SECTION = "selected_section"
    private const val TERMINAL_PREFERENCES = "terminal_display"
    private const val TERMINAL_TEXT_SP = "text_sp"
    private const val SHELL_PREFERENCES = "terminal"
    private const val SHELL_ID = "shared_shell_id"
    private const val LEGACY_SHELL_PREFERENCES = "terminal_shell"
    private const val LEGACY_SHELL_ID = "selected_shell_id"
    private const val STORAGE_PREFERENCES = "storage"
    private const val STORAGE_ONBOARDING_SEEN = "folder_onboarding_seen"
    private const val COMPATIBILITY_PREFERENCES = "linux_compatibility"
    private const val REDUCED_ISOLATION_ELECTRON = "reduced_isolation_electron"

    private const val DIRTY_MANAGER = 1
    private const val DIRTY_TERMINAL = 1 shl 1
    private const val DIRTY_GEOMETRY = 1 shl 2
    private const val DIRTY_FONT = 1 shl 3
    private const val DIRTY_CONTROLS = 1 shl 4
    private const val DIRTY_THEME = 1 shl 5
    private const val DIRTY_MATERIAL_YOU = 1 shl 6
    private const val DIRTY_ELECTRON = 1 shl 7

    private enum class TaskKey {
        STARTUP,
        MANAGER_SECTION,
        TERMINAL_TEXT,
        APPEARANCE_GEOMETRY,
        APPEARANCE_FONT,
        APPEARANCE_CONTROLS,
        APPEARANCE_THEME,
        MATERIAL_YOU,
        ELECTRON,
        STORAGE_ONBOARDING,
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io =
        LatestTaskExecutor<TaskKey>(TaskKey.entries.size, "ArchphenePreferences") { error ->
            android.util.Log.e(TAG, "Preference I/O task failed", error)
        }
    private val readyCallbacks = ArrayList<(ArchphenePreferenceSnapshot) -> Unit>(2)

    @Volatile private var current = ArchphenePreferenceSnapshot()
    @Volatile private var appearanceListener: AppearanceListener? = null
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
        io.execute(TaskKey.STARTUP) {
            val manager = readInt(appContext, MANAGER_PREFERENCES, MANAGER_SECTION)
            val terminal = readInt(appContext, TERMINAL_PREFERENCES, TERMINAL_TEXT_SP)
            shellId(appContext)
            val reducedIsolationElectron =
                readBoolean(
                    appContext,
                    COMPATIBILITY_PREFERENCES,
                    REDUCED_ISOLATION_ELECTRON,
                )
            val appearance =
                try {
                    LinuxAppearancePreferences.read(appContext)
                } catch (error: RuntimeException) {
                    android.util.Log.e(TAG, "Could not read Linux appearance preferences", error)
                    LinuxAppearanceOverrides(0, 0, 0, 0, true)
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
                            if (dirty and DIRTY_THEME == 0) {
                                appearance.themeMode
                            } else {
                                prior.appearance.themeMode
                            },
                            if (dirty and DIRTY_MATERIAL_YOU == 0) {
                                appearance.materialYou
                            } else {
                                prior.appearance.materialYou
                            },
                        ),
                        if (dirty and DIRTY_ELECTRON == 0) {
                            reducedIsolationElectron
                        } else {
                            prior.reducedIsolationElectron
                        },
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
            writePreference(TaskKey.MANAGER_SECTION, MANAGER_PREFERENCES, MANAGER_SECTION, section)
        }
    }

    fun setTerminalTextSp(textSp: Int) {
        synchronized(lock) {
            current = current.copy(terminalTextSp = textSp)
            dirty = dirty or DIRTY_TERMINAL
            writePreference(TaskKey.TERMINAL_TEXT, TERMINAL_PREFERENCES, TERMINAL_TEXT_SP, textSp)
        }
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
                            LinuxAppearancePreferences.THEME_MODE ->
                                appearance.copy(themeMode = value)
                            else -> throw IllegalArgumentException("Unknown appearance preference")
                        },
                )
            dirty =
                dirty or
                when (key) {
                    LinuxAppearancePreferences.GEOMETRY_PERCENT -> DIRTY_GEOMETRY
                    LinuxAppearancePreferences.FONT_PERCENT -> DIRTY_FONT
                    LinuxAppearancePreferences.CONTROL_VISUAL_DP -> DIRTY_CONTROLS
                    else -> DIRTY_THEME
                }
            val taskKey =
                when (key) {
                    LinuxAppearancePreferences.GEOMETRY_PERCENT -> TaskKey.APPEARANCE_GEOMETRY
                    LinuxAppearancePreferences.FONT_PERCENT -> TaskKey.APPEARANCE_FONT
                    LinuxAppearancePreferences.CONTROL_VISUAL_DP -> TaskKey.APPEARANCE_CONTROLS
                    else -> TaskKey.APPEARANCE_THEME
                }
            writePreference(taskKey, LinuxAppearancePreferences.PREFERENCES, key, value)
        }
        if (key == LinuxAppearancePreferences.THEME_MODE) {
            notifyAppearanceChanged()
        }
    }

    fun setMaterialYou(enabled: Boolean) {
        synchronized(lock) {
            current =
                current.copy(
                    appearance = current.appearance.copy(materialYou = enabled),
                )
            dirty = dirty or DIRTY_MATERIAL_YOU
            writeDefaultTrueBooleanPreference(
                TaskKey.MATERIAL_YOU,
                LinuxAppearancePreferences.PREFERENCES,
                LinuxAppearancePreferences.MATERIAL_YOU,
                enabled,
            )
        }
        notifyAppearanceChanged()
    }

    fun setAppearanceListener(listener: AppearanceListener) {
        appearanceListener = listener
    }

    fun clearAppearanceListener(listener: AppearanceListener) {
        if (appearanceListener === listener) {
            appearanceListener = null
        }
    }

    private fun notifyAppearanceChanged() {
        mainHandler.post {
            appearanceListener?.onAppearanceChanged()
        }
    }

    fun setShellId(shellId: String) {
        val context = initializedContext()
        context
            .getSharedPreferences(SHELL_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SHELL_ID, shellId)
            .apply()
    }

    fun shellId(context: Context): String =
        try {
            context
                .getSharedPreferences(SHELL_PREFERENCES, Context.MODE_PRIVATE)
                .getString(SHELL_ID, null)
                .orEmpty()
                .ifEmpty {
                    context
                        .getSharedPreferences(LEGACY_SHELL_PREFERENCES, Context.MODE_PRIVATE)
                        .getString(LEGACY_SHELL_ID, "bash")
                        .orEmpty()
                }
                .ifEmpty { "bash" }
        } catch (error: RuntimeException) {
            android.util.Log.e(TAG, "Could not read the selected shell", error)
            "bash"
        }

    fun setStorageOnboardingSeen() {
        val context = initializedContext()
        io.execute(TaskKey.STORAGE_ONBOARDING) {
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

    fun setReducedIsolationElectron(enabled: Boolean) {
        synchronized(lock) {
            current = current.copy(reducedIsolationElectron = enabled)
            dirty = dirty or DIRTY_ELECTRON
            writeBooleanPreference(
                TaskKey.ELECTRON,
                COMPATIBILITY_PREFERENCES,
                REDUCED_ISOLATION_ELECTRON,
                enabled,
            )
        }
    }

    private fun writePreference(
        taskKey: TaskKey,
        preferences: String,
        key: String,
        value: Int,
    ) {
        val context = initializedContext()
        io.execute(taskKey) {
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

    private fun writeBooleanPreference(
        taskKey: TaskKey,
        preferences: String,
        key: String,
        value: Boolean,
    ) {
        val context = initializedContext()
        io.execute(taskKey) {
            if (
                !context
                    .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(key, value)
                    .commit()
            ) {
                android.util.Log.e(TAG, "Could not persist $preferences/$key")
            }
        }
    }

    private fun writeDefaultTrueBooleanPreference(
        taskKey: TaskKey,
        preferences: String,
        key: String,
        value: Boolean,
    ) {
        val context = initializedContext()
        io.execute(taskKey) {
            val editor =
                context
                    .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                    .edit()
            if (value) {
                editor.remove(key)
            } else {
                editor.putBoolean(key, false)
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

    private fun readBoolean(
        context: Context,
        preferences: String,
        key: String,
    ): Boolean =
        try {
            context
                .getSharedPreferences(preferences, Context.MODE_PRIVATE)
                .getBoolean(key, false)
        } catch (error: RuntimeException) {
            android.util.Log.e(TAG, "Could not read $preferences/$key", error)
            false
        }

    private const val TAG = "ArchphenePreferences"
}
