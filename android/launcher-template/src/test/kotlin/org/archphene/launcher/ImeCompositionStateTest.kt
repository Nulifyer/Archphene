package org.archphene.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeCompositionStateTest {
    @Test
    fun finishingCommitsTheLatestAcceptedUnicodePreedit() {
        val state = ImeCompositionState()

        state.replaceAcceptedPreedit("に")
        state.replaceAcceptedPreedit("日本語")

        assertEquals("日本語", state.finishCommit())
        state.clear()
        assertNull(state.finishCommit())
    }

    @Test
    fun emptyPreeditCancelsThePendingComposition() {
        val state = ImeCompositionState()

        state.replaceAcceptedPreedit("candidate")
        state.replaceAcceptedPreedit("")

        assertNull(state.finishCommit())
    }

    @Test
    fun explicitCommitClearsThePendingComposition() {
        val state = ImeCompositionState()

        state.replaceAcceptedPreedit("候補")
        state.clear()

        assertNull(state.finishCommit())
    }
}
