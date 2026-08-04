package org.archphene.app.launcher

import org.archphene.app.utf8LengthAtMost

internal fun fitsLauncherUnixSocketPath(
    path: String,
    nativePathBytes: Int,
): Boolean = nativePathBytes > 0 && utf8LengthAtMost(path, nativePathBytes - 1)
