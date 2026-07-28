package org.archphene.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Debug-only framework consumer for exact-device terminal accessibility gates.
 *
 * This deliberately lives outside the production source set. It exercises the
 * same public AccessibilityService boundary used by a screen reader without
 * altering vendor TalkBack onboarding on a physical test device.
 */
internal class TerminalAccessibilityTestService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var actionsScheduled = false
    private var actionsSubmitted = false
    private var observedFocus = false
    private var observedSelection = false
    private var observedTraversal = false
    private var terminalWindowId = Int.MIN_VALUE
    private var actionAttempts = 0

    override fun onServiceConnected() {
        Log.i(TAG, "Terminal accessibility test service connected")
        handler.postDelayed({ scheduleActions() }, ACTION_RETRY_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != packageName) {
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                observedFocus = true
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                observedSelection = true
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> {
                observedTraversal =
                    event.movementGranularity ==
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD &&
                    event.action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            }
        }
        if (!actionsSubmitted) {
            scheduleActions()
        } else {
            scheduleResultCheck()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Terminal accessibility test service interrupted")
    }

    private fun scheduleActions() {
        if (actionsScheduled) {
            return
        }
        actionsScheduled = true
        handler.post {
            actionsScheduled = false
            val terminal = findTerminal(rootInActiveWindow)
            if (terminal == null) {
                actionAttempts++
                if (actionAttempts < MAX_ACTION_ATTEMPTS) {
                    handler.postDelayed({ scheduleActions() }, ACTION_RETRY_MILLIS)
                } else {
                    Log.e(TAG, "Terminal accessibility service failed: terminal not found")
                }
                return@post
            }
            val text = terminal.text?.toString().orEmpty()
            val expectedGranularities =
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
            check(text.contains("cargo 1.88.0")) {
                "terminal accessibility text is incomplete"
            }
            check(
                terminal.movementGranularities and expectedGranularities ==
                    expectedGranularities,
            ) {
                "terminal movement granularities are incomplete"
            }
            terminalWindowId = terminal.windowId
            val focused =
                terminal.isAccessibilityFocused ||
                    terminal.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            val selectionArguments =
                Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        0,
                    )
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        9,
                    )
                }
            val selected =
                terminal.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    selectionArguments,
                )
            val traversalArguments =
                Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                    )
                    putBoolean(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
                        true,
                    )
                }
            val traversed =
                terminal.performAction(
                    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                    traversalArguments,
                )
            if (!focused || !selected || !traversed) {
                Log.e(
                    TAG,
                    "Terminal accessibility service failed: action rejected " +
                        "focus=$focused selection=$selected traversal=$traversed",
                )
                return@post
            }
            actionsSubmitted = true
            scheduleResultCheck()
        }
    }

    private fun scheduleResultCheck() {
        handler.removeCallbacks(resultCheck)
        handler.postDelayed(resultCheck, RESULT_SETTLE_MILLIS)
    }

    private val resultCheck =
        Runnable {
            val terminal = findTerminal(rootInActiveWindow)
            val selected =
                terminal != null &&
                    terminal.windowId == terminalWindowId &&
                    terminal.isAccessibilityFocused &&
                    terminal.textSelectionStart == 0 &&
                    terminal.textSelectionEnd > terminal.textSelectionStart
            if (observedFocus && observedSelection && observedTraversal && selected) {
                Log.i(
                    TAG,
                    "Terminal accessibility service pass: " +
                        "focus, selection, word traversal, and exact events observed",
                )
            } else {
                Log.e(
                    TAG,
                    "Terminal accessibility service failed: " +
                        "focus=$observedFocus selection=$observedSelection " +
                        "traversal=$observedTraversal selected=$selected",
                )
            }
        }

    private fun findTerminal(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (
            node.contentDescription
                ?.toString()
                ?.startsWith(TERMINAL_DESCRIPTION_PREFIX) == true &&
            node.className?.toString() == TextView::class.java.name
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            val terminal = findTerminal(node.getChild(index))
            if (terminal != null) {
                return terminal
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "ArchpheneTerminalA11yService"
        private const val TERMINAL_DESCRIPTION_PREFIX = "Linux terminal, "
        private const val RESULT_SETTLE_MILLIS = 500L
        private const val ACTION_RETRY_MILLIS = 100L
        private const val MAX_ACTION_ATTEMPTS = 50
    }
}
