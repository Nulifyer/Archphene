package org.archphene.launcher

import kotlin.math.roundToInt

internal object LauncherSurfaceCoordinatePolicy {
    fun absolute(
        windowCoordinate: Float,
        viewOffset: Int,
        viewExtent: Int,
        bufferExtent: Int,
    ): Int {
        if (!windowCoordinate.isFinite()) return 0
        val safeViewExtent = viewExtent.coerceAtLeast(1)
        val safeBufferExtent = bufferExtent.takeIf { it > 0 } ?: safeViewExtent
        val local =
            (windowCoordinate - viewOffset)
                .coerceIn(0f, (safeViewExtent - 1).coerceAtLeast(0).toFloat())
        if (safeViewExtent == 1 || safeBufferExtent == 1) return 0
        return (
            local.toDouble() *
                (safeBufferExtent - 1).toDouble() /
                (safeViewExtent - 1).toDouble()
        ).roundToInt().coerceIn(0, (safeBufferExtent - 1).coerceAtLeast(0))
    }

    fun relative(
        viewDelta: Float,
        viewExtent: Int,
        bufferExtent: Int,
    ): Float {
        if (!viewDelta.isFinite()) return 0f
        val safeViewExtent = viewExtent.coerceAtLeast(1)
        val safeBufferExtent = bufferExtent.takeIf { it > 0 } ?: safeViewExtent
        return viewDelta * safeBufferExtent.toFloat() / safeViewExtent.toFloat()
    }
}
