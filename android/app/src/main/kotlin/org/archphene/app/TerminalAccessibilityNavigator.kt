package org.archphene.app

import android.view.accessibility.AccessibilityNodeInfo
import java.text.BreakIterator
import java.util.Locale

/**
 * Finds the exact text segment traversed by an Android screen reader.
 *
 * The iterators are retained because traversal happens on the UI thread and
 * should not construct locale machinery for every TalkBack gesture.
 */
internal class TerminalAccessibilityNavigator(
    locale: Locale = Locale.ROOT,
) {
    private val characterIterator = BreakIterator.getCharacterInstance(locale)
    private val wordIterator = BreakIterator.getWordInstance(locale)

    var start = 0
        private set
    var end = 0
        private set

    fun move(
        text: String,
        offset: Int,
        granularity: Int,
        forward: Boolean,
    ): Boolean {
        if (offset !in 0..text.length || text.isEmpty()) {
            return false
        }
        return when (granularity) {
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER ->
                moveWithIterator(
                    characterIterator,
                    text,
                    offset,
                    forward,
                    requireWord = false,
                )
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD ->
                moveWithIterator(
                    wordIterator,
                    text,
                    offset,
                    forward,
                    requireWord = true,
                )
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE ->
                moveLine(text, offset, forward)
            else -> false
        }
    }

    private fun moveWithIterator(
        iterator: BreakIterator,
        text: String,
        offset: Int,
        forward: Boolean,
        requireWord: Boolean,
    ): Boolean {
        iterator.setText(text)
        if (forward) {
            var segmentStart =
                if (iterator.isBoundary(offset)) {
                    offset
                } else {
                    iterator.preceding(offset)
                }
            if (segmentStart == BreakIterator.DONE) {
                segmentStart = 0
            }
            var segmentEnd = iterator.following(segmentStart)
            while (segmentEnd != BreakIterator.DONE) {
                if (
                    segmentEnd > offset &&
                    (!requireWord || containsWordCharacter(text, segmentStart, segmentEnd))
                ) {
                    return publish(segmentStart, segmentEnd)
                }
                segmentStart = segmentEnd
                segmentEnd = iterator.following(segmentStart)
            }
            return false
        }

        var segmentEnd =
            if (iterator.isBoundary(offset)) {
                offset
            } else {
                iterator.following(offset)
            }
        if (segmentEnd == BreakIterator.DONE) {
            segmentEnd = text.length
        }
        var segmentStart = iterator.preceding(segmentEnd)
        while (segmentStart != BreakIterator.DONE) {
            if (
                segmentStart < offset &&
                (!requireWord || containsWordCharacter(text, segmentStart, segmentEnd))
            ) {
                return publish(segmentStart, segmentEnd)
            }
            segmentEnd = segmentStart
            segmentStart = iterator.preceding(segmentEnd)
        }
        return false
    }

    private fun moveLine(
        text: String,
        offset: Int,
        forward: Boolean,
    ): Boolean {
        if (forward) {
            if (offset == text.length) {
                return false
            }
            var segmentStart = offset
            while (segmentStart < text.length && text[segmentStart] == '\n') {
                segmentStart++
            }
            if (segmentStart == text.length) {
                return false
            }
            val newline = text.indexOf('\n', segmentStart)
            return publish(
                segmentStart,
                if (newline < 0) text.length else newline,
            )
        }

        if (offset == 0) {
            return false
        }
        var segmentEnd = offset
        while (segmentEnd > 0 && text[segmentEnd - 1] == '\n') {
            segmentEnd--
        }
        if (segmentEnd == 0) {
            return false
        }
        val newline = text.lastIndexOf('\n', segmentEnd - 1)
        return publish(newline + 1, segmentEnd)
    }

    private fun publish(
        segmentStart: Int,
        segmentEnd: Int,
    ): Boolean {
        if (segmentStart < 0 || segmentEnd <= segmentStart) {
            return false
        }
        start = segmentStart
        end = segmentEnd
        return true
    }

    private fun containsWordCharacter(
        text: String,
        segmentStart: Int,
        segmentEnd: Int,
    ): Boolean {
        var offset = segmentStart
        while (offset < segmentEnd) {
            val codepoint = text.codePointAt(offset)
            if (Character.isLetterOrDigit(codepoint) || codepoint == '_'.code) {
                return true
            }
            offset += Character.charCount(codepoint)
        }
        return false
    }
}
