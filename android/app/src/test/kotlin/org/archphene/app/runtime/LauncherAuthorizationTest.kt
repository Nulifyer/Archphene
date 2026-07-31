package org.archphene.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAuthorizationTest {
    @Test
    fun graphicsBridgeCapabilityComesOnlyFromVerifiedStackTopology() {
        assertTrue(
            LauncherAuthorization(
                label = "OpenGL app",
                terminal = false,
                integrationTopology = 1 shl 10,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).usesGraphicsBridge,
        )
        assertFalse(
            LauncherAuthorization(
                label = "Chromium app",
                terminal = false,
                integrationTopology = 1 shl 6,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).usesGraphicsBridge,
        )
        assertFalse(
            LauncherAuthorization(
                label = "Wayland app",
                terminal = false,
                integrationTopology = 1 shl 8,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).usesGraphicsBridge,
        )
    }

    @Test
    fun phoneLandscapePreferenceComesOnlyFromVerifiedSdlTopology() {
        assertTrue(
            LauncherAuthorization(
                label = "SDL 3 app",
                terminal = false,
                integrationTopology = 1 shl 5,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).prefersPhoneLandscape,
        )
        assertTrue(
            LauncherAuthorization(
                label = "SDL 2 app",
                terminal = false,
                integrationTopology = 1 shl 4,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).prefersPhoneLandscape,
        )
        assertFalse(
            LauncherAuthorization(
                label = "Wayland app",
                terminal = false,
                integrationTopology = 1 shl 8,
                bridgeCapabilities = 0,
                mimeTypes = emptyList(),
            ).prefersPhoneLandscape,
        )
    }
}
