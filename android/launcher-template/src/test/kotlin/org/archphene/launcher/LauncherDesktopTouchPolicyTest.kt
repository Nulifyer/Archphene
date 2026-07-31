package org.archphene.launcher

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherDesktopTouchPolicyTest {
    @Test
    fun oneFingerSequenceUsesDesktopPointerSemantics() {
        assertEquals(
            LauncherDesktopTouchPolicy.POINTER,
            LauncherDesktopTouchPolicy.route(
                LauncherDesktopTouchPolicy.IDLE,
                MotionEvent.ACTION_DOWN,
            ),
        )
        assertEquals(
            LauncherDesktopTouchPolicy.ACTIVE,
            LauncherDesktopTouchPolicy.stateAfter(
                LauncherDesktopTouchPolicy.IDLE,
                MotionEvent.ACTION_DOWN,
            ),
        )
        assertEquals(
            LauncherDesktopTouchPolicy.POINTER,
            LauncherDesktopTouchPolicy.route(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_MOVE,
            ),
        )
        assertEquals(
            LauncherDesktopTouchPolicy.POINTER,
            LauncherDesktopTouchPolicy.route(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_UP,
            ),
        )
        assertEquals(
            LauncherDesktopTouchPolicy.IDLE,
            LauncherDesktopTouchPolicy.stateAfter(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_UP,
            ),
        )
    }

    @Test
    fun secondaryPointerPromotesSequenceToNativeTouch() {
        assertEquals(
            LauncherDesktopTouchPolicy.CANCEL_POINTER,
            LauncherDesktopTouchPolicy.route(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_POINTER_DOWN,
            ),
        )
        val nativeTouch =
            LauncherDesktopTouchPolicy.stateAfter(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_POINTER_DOWN,
            )
        assertEquals(LauncherDesktopTouchPolicy.NATIVE_TOUCH, nativeTouch)
        listOf(
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
        ).forEach { action ->
            assertEquals(
                LauncherDesktopTouchPolicy.PASS,
                LauncherDesktopTouchPolicy.route(nativeTouch, action),
            )
        }
        assertEquals(
            LauncherDesktopTouchPolicy.IDLE,
            LauncherDesktopTouchPolicy.stateAfter(nativeTouch, MotionEvent.ACTION_UP),
        )
    }

    @Test
    fun cancellationReleasesTheDesktopPointerSequence() {
        assertEquals(
            LauncherDesktopTouchPolicy.POINTER,
            LauncherDesktopTouchPolicy.route(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_CANCEL,
            ),
        )
        assertEquals(
            LauncherDesktopTouchPolicy.IDLE,
            LauncherDesktopTouchPolicy.stateAfter(
                LauncherDesktopTouchPolicy.ACTIVE,
                MotionEvent.ACTION_CANCEL,
            ),
        )
    }

    @Test
    fun dragStartsOnlyAfterAndroidTouchSlop() {
        assertFalse(LauncherDesktopTouchPolicy.beginsDrag(3f, 4f, 8))
        assertFalse(LauncherDesktopTouchPolicy.beginsDrag(8f, 0f, 8))
        assertTrue(LauncherDesktopTouchPolicy.beginsDrag(9f, 0f, 8))
        assertFalse(LauncherDesktopTouchPolicy.beginsDrag(Float.NaN, 9f, 8))
        assertFalse(LauncherDesktopTouchPolicy.beginsDrag(9f, 0f, -1))
    }
}
