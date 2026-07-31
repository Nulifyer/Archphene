package org.archphene.app.runtime

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeForegroundPolicyTest {
    @Test
    fun graphicalAudioSessionAddsMediaPlaybackType() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            runtimeForegroundServiceType(1),
        )
    }

    @Test
    fun nonAudioWorkUsesOnlySpecialUseType() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            runtimeForegroundServiceType(0),
        )
    }
}
