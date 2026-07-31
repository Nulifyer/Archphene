package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherImeEvidencePolicyTest {
    @Test
    fun distinguishesAbsentEmptyAndStrongEditorEvidence() {
        assertEquals(
            LauncherImeEvidencePolicy.NONE,
            LauncherImeEvidencePolicy.classify(-1, 0, 0, -1, -1),
        )
        assertEquals(
            LauncherImeEvidencePolicy.EMPTY_ONLY,
            LauncherImeEvidencePolicy.classify(0, 0, 0, -1, -1),
        )
        assertEquals(
            LauncherImeEvidencePolicy.STRONG,
            LauncherImeEvidencePolicy.classify(1, 0, 0, -1, -1),
        )
        assertEquals(
            LauncherImeEvidencePolicy.STRONG,
            LauncherImeEvidencePolicy.classify(0, 0, 0, 0, 0),
        )
    }
}
