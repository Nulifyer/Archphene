package org.archphene.launcher

internal object LauncherInputTimestampPolicy {
    fun next(
        candidate: Int,
        previous: Int,
        hasPrevious: Boolean,
    ): Int {
        if (!hasPrevious || Integer.compareUnsigned(candidate, previous) > 0) {
            return candidate
        }
        return previous + 1
    }
}
