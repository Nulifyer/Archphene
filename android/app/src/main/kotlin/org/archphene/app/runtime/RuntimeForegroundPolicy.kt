package org.archphene.app.runtime

import android.content.pm.ServiceInfo

internal fun runtimeForegroundServiceType(audioProcessCount: Int): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
        if (audioProcessCount > 0) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
