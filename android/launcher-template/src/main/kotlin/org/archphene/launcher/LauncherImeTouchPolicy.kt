package org.archphene.launcher

internal object LauncherImeTouchPolicy {
    const val EDITOR_EVIDENCE_NONE = 0
    const val EDITOR_EVIDENCE_EMPTY_ONLY = 1
    const val EDITOR_EVIDENCE_STRONG = 2
    private const val AMBIGUOUS_TEXT_LONG_PRESS_MILLIS = 500L

    fun requestOnDown(
        active: Boolean,
        editorEvidence: Int,
    ): Boolean = active && editorEvidence == EDITOR_EVIDENCE_STRONG

    fun requestOnActivationAfterTouch(
        touchPending: Boolean,
        active: Boolean,
        editorEvidence: Int,
    ): Boolean = touchPending && active && editorEvidence != EDITOR_EVIDENCE_NONE

    fun activationTouchPendingAfterState(
        touchPending: Boolean,
        active: Boolean,
        editorEvidence: Int,
    ): Boolean = touchPending && active && editorEvidence == EDITOR_EVIDENCE_NONE

    fun retainSoftImeRequest(
        requested: Boolean,
        explicitlyRequestedForAmbiguousInput: Boolean,
        active: Boolean,
        editorEvidence: Int,
    ): Boolean =
        requested &&
            active &&
            (editorEvidence != EDITOR_EVIDENCE_NONE || explicitlyRequestedForAmbiguousInput)

    fun requestOnUp(
        longPressEligible: Boolean,
        active: Boolean,
        editorEvidence: Int,
        pressDurationMillis: Long,
    ): Boolean =
        longPressEligible &&
        active &&
            editorEvidence != EDITOR_EVIDENCE_STRONG &&
            pressDurationMillis >= AMBIGUOUS_TEXT_LONG_PRESS_MILLIS

    fun retainLongPressEligibility(
        eligible: Boolean,
        movedBeyondTouchSlop: Boolean,
        pointerCount: Int,
    ): Boolean = eligible && !movedBeyondTouchSlop && pointerCount == 1

    fun suppressImplicitAfterHardwareKey(
        active: Boolean,
        softImeRequested: Boolean,
    ): Boolean = active && !softImeRequested
}
