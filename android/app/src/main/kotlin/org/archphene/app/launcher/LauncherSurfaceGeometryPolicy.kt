package org.archphene.app.launcher

internal object LauncherSurfaceGeometryPolicy {
    data class LogicalSize(
        val width: Int,
        val height: Int,
    )

    fun logicalSize(
        width: Int,
        height: Int,
        androidDensityDpi: Int,
        geometryPercent: Int,
    ): LogicalSize {
        require(width > 0 && height > 0)
        require(androidDensityDpi in 72..1_000)
        require(geometryPercent == 0 || geometryPercent in setOf(75, 100, 125, 150))
        val densityForMinimum =
            (width.coerceAtMost(height).toLong() * 160L / MIN_DESKTOP_LOGICAL_EXTENT)
                .toInt()
        val automaticDensity = androidDensityDpi.coerceAtMost(densityForMinimum).coerceIn(72, 1_000)
        val resolvedDensity =
            if (geometryPercent == 0) {
                automaticDensity
            } else {
                ((automaticDensity.toLong() * geometryPercent + 50L) / 100L)
                    .toInt()
                    .coerceIn(72, 1_000)
            }
        return LogicalSize(
            logicalExtent(width, resolvedDensity),
            logicalExtent(height, resolvedDensity),
        )
    }

    private fun logicalExtent(
        physical: Int,
        densityDpi: Int,
    ): Int =
        ((physical.toLong() * 160L + densityDpi / 2L) / densityDpi)
            .toInt()
            .coerceAtLeast(1)

    private const val MIN_DESKTOP_LOGICAL_EXTENT = 432L
}
