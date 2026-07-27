package org.archphene.app.launcher

/**
 * Presence, rather than content, identifies each debug IME operation.
 *
 * This mirrors Android's InputConnection boundary: an empty preedit or commit
 * is still meaningful, while a missing value means that operation did not
 * occur. Keeping this policy pure makes the device-test bridge independently
 * regression-testable.
 */
internal data class LauncherSessionDebugImePlan(
    val preedit: Boolean,
    val commit: Boolean,
    val editorAction: Boolean,
) {
    val commandCount: Int
        get() =
            (if (preedit) 1 else 0) +
                (if (commit) 1 else 0) +
                (if (editorAction) 1 else 0)
}

internal fun launcherSessionDebugImePlan(
    composing: String?,
    committed: String?,
    submit: Boolean,
): LauncherSessionDebugImePlan =
    LauncherSessionDebugImePlan(
        preedit = composing != null,
        commit = committed != null,
        editorAction = submit,
    )
