package org.archphene.app.storage

import java.io.File
import java.nio.charset.StandardCharsets
import org.archphene.app.runtime.readBoundedBytes

internal fun readBoundedDebugControl(file: File): String =
    file.inputStream().use { input ->
        input
            .readBoundedBytes(
                DEBUG_CONTROL_MAX_BYTES,
                "Debug control file is too large",
            ).toString(StandardCharsets.UTF_8)
    }

private const val DEBUG_CONTROL_MAX_BYTES = 256
