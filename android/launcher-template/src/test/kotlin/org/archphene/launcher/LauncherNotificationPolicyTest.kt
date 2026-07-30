package org.archphene.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNotificationPolicyTest {
    @Test
    fun accepts_bounded_desktop_notifications() {
        assertTrue(
            LauncherNotificationPolicy.valid(
                "classic-42",
                "Build complete",
                "The application is ready.\nTap to return.",
            ),
        )
    }

    @Test
    fun rejects_empty_control_and_oversized_fields() {
        assertFalse(LauncherNotificationPolicy.valid("", "Title", "Body"))
        assertFalse(LauncherNotificationPolicy.valid("id", "Bad\nTitle", "Body"))
        assertFalse(LauncherNotificationPolicy.valid("id", "Title", "x".repeat(4_097)))
        assertFalse(LauncherNotificationPolicy.valid("id", "Title", "\ud800"))
    }

    @Test
    fun counts_utf8_without_allocating_an_encoded_copy() {
        assertTrue(LauncherNotificationPolicy.valid("portal-a", "✓", "🙂".repeat(2_048)))
        assertFalse(LauncherNotificationPolicy.valid("portal-a", "✓", "🙂".repeat(2_049)))
    }
}
