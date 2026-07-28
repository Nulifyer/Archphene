package org.archphene.app

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAccessibilityNavigatorTest {
    private val navigator = TerminalAccessibilityNavigator()

    @Test
    fun character_traversal_keeps_a_combining_grapheme_together() {
        val text = "Ame\u0301lie 👍🏽"

        assertTrue(
            navigator.move(
                text,
                2,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                forward = true,
            ),
        )
        assertEquals("e\u0301", text.substring(navigator.start, navigator.end))
        assertTrue(
            navigator.move(
                text,
                navigator.end,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                forward = false,
            ),
        )
        assertEquals("e\u0301", text.substring(navigator.start, navigator.end))
    }

    @Test
    fun word_traversal_skips_terminal_punctuation_and_whitespace() {
        val text = "cargo --version\nnext_value"

        assertTrue(
            navigator.move(
                text,
                5,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                forward = true,
            ),
        )
        assertEquals("version", text.substring(navigator.start, navigator.end))
        assertTrue(
            navigator.move(
                text,
                navigator.start,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                forward = false,
            ),
        )
        assertEquals("cargo", text.substring(navigator.start, navigator.end))
    }

    @Test
    fun line_traversal_is_bounded_by_terminal_newlines() {
        val text = "first line\nsecond line\nthird"

        assertTrue(
            navigator.move(
                text,
                0,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                forward = true,
            ),
        )
        assertEquals("first line", text.substring(navigator.start, navigator.end))
        assertTrue(
            navigator.move(
                text,
                22,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                forward = false,
            ),
        )
        assertEquals("second line", text.substring(navigator.start, navigator.end))
    }

    @Test
    fun traversal_rejects_edges_and_unsupported_granularity() {
        assertFalse(
            navigator.move(
                "text",
                4,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                forward = true,
            ),
        )
        assertFalse(
            navigator.move(
                "text",
                0,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                forward = false,
            ),
        )
        assertFalse(navigator.move("text", 0, 0, forward = true))
    }
}
