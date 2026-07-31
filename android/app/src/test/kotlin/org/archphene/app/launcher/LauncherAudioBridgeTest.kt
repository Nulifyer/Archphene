package org.archphene.app.launcher

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherAudioBridgeTest {
    @Test
    fun namesEveryAndroidAudioFocusTransition() {
        assertEquals(
            "gain",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_GAIN),
        )
        assertEquals(
            "loss",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS),
        )
        assertEquals(
            "loss-transient",
            LauncherAudioBridge.audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            "loss-transient-can-duck",
            LauncherAudioBridge.audioFocusChangeName(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            ),
        )
        assertEquals("unknown-17", LauncherAudioBridge.audioFocusChangeName(17))
    }

    @Test
    fun audioRuntimeDirectoryIncludesServiceLifetimeIdentity() {
        assertEquals(
            "audio-7-0123456789abcdef",
            LauncherAudioBridge.runtimeDirectoryName(
                7,
                "0123456789abcdef",
            ),
        )
    }

    @Test
    fun audioRuntimeDirectoryRejectsUnsafeIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            LauncherAudioBridge.runtimeDirectoryName(7, "../shared")
        }
    }

    @Test
    fun audioFocusRequiresRealForegroundPlayback() {
        assertEquals(
            false,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 0,
                focusInterrupted = false,
            ),
        )
        assertEquals(
            true,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusInterrupted = false,
            ),
        )
        assertEquals(
            false,
            LauncherAudioBridge.shouldRequestAudioFocus(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusInterrupted = true,
            ),
        )
    }

    @Test
    fun playbackRemainsSuspendedWithoutUsableAudioFocus() {
        assertEquals(
            false,
            LauncherAudioBridge.shouldSuspendPlayback(
                hostActive = true,
                runtimeForeground = true,
                activePlaybackInputCount = 1,
                focusRequested = true,
                focusInterrupted = false,
            ),
        )
        for (state in listOf(0, 1, 2, 3, 4)) {
            assertEquals(
                true,
                LauncherAudioBridge.shouldSuspendPlayback(
                    hostActive = state != 0,
                    runtimeForeground = state != 1,
                    activePlaybackInputCount = if (state == 2) 0 else 1,
                    focusRequested = state != 3,
                    focusInterrupted = state == 4,
                ),
            )
        }
        assertEquals(250L, LauncherAudioBridge.focusRetryDelayMillis(1))
        assertEquals(4_000L, LauncherAudioBridge.focusRetryDelayMillis(5))
        assertEquals(4_000L, LauncherAudioBridge.focusRetryDelayMillis(50))
        assertEquals(250L, LauncherAudioBridge.controlRetryDelayMillis(1))
    }

    @Test
    fun parsesOnlyBoundedPulsePlaybackInputLifecycleLines() {
        assertEquals(
            18L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 17 \"Playback Stream\" on archphene_output",
            ),
        )
        assertEquals(
            -18L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Freeing input 17 \"Playback Stream\"",
            ),
        )
        assertEquals(0L, LauncherAudioBridge.pulsePlaybackInputEvent("Created source output 17"))
        assertEquals(
            4_097L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4096 \"unbounded\"",
            ),
        )
        assertEquals(
            4_294_967_296L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4294967295 \"rolled over\"",
            ),
        )
        assertEquals(
            0L,
            LauncherAudioBridge.pulsePlaybackInputEvent(
                "I: sink-input.c: Created input 4294967296 \"out of range\"",
            ),
        )
    }
}
