package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherImeTouchPolicyTest {
    private val none = LauncherImeTouchPolicy.EDITOR_EVIDENCE_NONE
    private val emptyOnly = LauncherImeTouchPolicy.EDITOR_EVIDENCE_EMPTY_ONLY
    private val strong = LauncherImeTouchPolicy.EDITOR_EVIDENCE_STRONG

    @Test
    fun ordinaryToolkitEditorRequestsImeOnTouch() {
        assertTrue(LauncherImeTouchPolicy.requestOnDown(active = true, editorEvidence = strong))
    }

    @Test
    fun inactiveTextInputDoesNotPrimeAnUnknownFutureClient() {
        assertFalse(LauncherImeTouchPolicy.requestOnDown(active = false, editorEvidence = none))
        assertFalse(
            LauncherImeTouchPolicy.requestOnActivationAfterTouch(
                touchPending = true,
                active = true,
                editorEvidence = none,
            ),
        )
    }

    @Test
    fun newlyFocusedEditorCanEnableImeAfterTouch() {
        assertTrue(
            LauncherImeTouchPolicy.requestOnActivationAfterTouch(
                touchPending = true,
                active = true,
                editorEvidence = strong,
            ),
        )
    }

    @Test
    fun activeTextInputRetainsPendingTouchUntilEditorEvidenceArrives() {
        assertTrue(
            LauncherImeTouchPolicy.activationTouchPendingAfterState(
                touchPending = true,
                active = true,
                editorEvidence = none,
            ),
        )
        assertFalse(
            LauncherImeTouchPolicy.activationTouchPendingAfterState(
                touchPending = true,
                active = true,
                editorEvidence = strong,
            ),
        )
    }

    @Test
    fun editorRequestDoesNotLeakIntoAmbiguousSdlInput() {
        assertFalse(
            LauncherImeTouchPolicy.retainSoftImeRequest(
                requested = true,
                explicitlyRequestedForAmbiguousInput = false,
                active = true,
                editorEvidence = none,
            ),
        )
        assertTrue(
            LauncherImeTouchPolicy.retainSoftImeRequest(
                requested = true,
                explicitlyRequestedForAmbiguousInput = true,
                active = true,
                editorEvidence = none,
            ),
        )
    }

    @Test
    fun ambiguousSdlTapDoesNotOpenIme() {
        assertFalse(LauncherImeTouchPolicy.requestOnDown(active = true, editorEvidence = emptyOnly))
        assertFalse(
            LauncherImeTouchPolicy.requestOnUp(
                longPressEligible = true,
                active = true,
                editorEvidence = emptyOnly,
                pressDurationMillis = 100,
            ),
        )
    }

    @Test
    fun ambiguousSdlLongPressCanOpenIme() {
        assertTrue(
            LauncherImeTouchPolicy.requestOnUp(
                longPressEligible = true,
                active = true,
                editorEvidence = emptyOnly,
                pressDurationMillis = 500,
            ),
        )
    }

    @Test
    fun dragOrSecondPointerCancelsAmbiguousLongPress() {
        assertFalse(
            LauncherImeTouchPolicy.retainLongPressEligibility(
                eligible = true,
                movedBeyondTouchSlop = true,
                pointerCount = 1,
            ),
        )
        assertFalse(
            LauncherImeTouchPolicy.retainLongPressEligibility(
                eligible = true,
                movedBeyondTouchSlop = false,
                pointerCount = 2,
            ),
        )
    }

    @Test
    fun textInputActivatedDuringAnOldPressCannotBecomeALongPress() {
        assertFalse(
            LauncherImeTouchPolicy.requestOnUp(
                longPressEligible = false,
                active = true,
                editorEvidence = emptyOnly,
                pressDurationMillis = 1_000,
            ),
        )
    }

    @Test
    fun hardwareKeySuppressesOnlyAmbiguousImplicitIme() {
        assertTrue(
            LauncherImeTouchPolicy.suppressImplicitAfterHardwareKey(
                active = true,
                softImeRequested = false,
            ),
        )
        assertFalse(
            LauncherImeTouchPolicy.suppressImplicitAfterHardwareKey(
                active = false,
                softImeRequested = false,
            ),
        )
        assertFalse(
            LauncherImeTouchPolicy.suppressImplicitAfterHardwareKey(
                active = true,
                softImeRequested = true,
            ),
        )
    }

    @Test
    fun emptyEditorCanActivateAfterTouchAndRetainExistingRequest() {
        assertTrue(
            LauncherImeTouchPolicy.requestOnActivationAfterTouch(
                touchPending = true,
                active = true,
                editorEvidence = emptyOnly,
            ),
        )
        assertTrue(
            LauncherImeTouchPolicy.retainSoftImeRequest(
                requested = true,
                explicitlyRequestedForAmbiguousInput = false,
                active = true,
                editorEvidence = emptyOnly,
            ),
        )
    }
}
