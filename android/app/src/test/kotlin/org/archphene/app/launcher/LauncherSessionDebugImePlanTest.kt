package org.archphene.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSessionDebugImePlanTest {
    @Test
    fun repeatedPreeditDoesNotImplyAnEmptyCommit() {
        val plan = launcherSessionDebugImePlan("日本語変換", null, false)

        assertTrue(plan.preedit)
        assertFalse(plan.commit)
        assertFalse(plan.editorAction)
        assertEquals(1, plan.commandCount)
    }

    @Test
    fun emptyOperationsRemainExplicitWhenPresent() {
        val plan = launcherSessionDebugImePlan("", "", true)

        assertTrue(plan.preedit)
        assertTrue(plan.commit)
        assertTrue(plan.editorAction)
        assertEquals(3, plan.commandCount)
    }

    @Test
    fun committedCommandAndEditorActionAreIndependentOfPreedit() {
        val plan = launcherSessionDebugImePlan(null, "printf test", true)

        assertFalse(plan.preedit)
        assertTrue(plan.commit)
        assertTrue(plan.editorAction)
        assertEquals(2, plan.commandCount)
    }
}
