package org.archphene.launcher

internal data class AccessibilityDisplayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Exact compositor mapping for the currently presented Linux top-level.
 *
 * Source coordinates use the AT-SPI tree's own viewport. Destination
 * coordinates use the compositor's fitted content rectangle in its
 * presentation canvas. Android then maps that canvas to the Surface.
 */
internal data class AccessibilityViewportTransform(
    val presentationWidth: Int,
    val presentationHeight: Int,
    val destinationX: Int,
    val destinationY: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
)

internal fun mapAccessibilityDisplayBounds(
    sourceLeft: Int,
    sourceTop: Int,
    sourceRight: Int,
    sourceBottom: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    transform: AccessibilityViewportTransform,
    hostWidth: Int,
    hostHeight: Int,
): AccessibilityDisplayBounds {
    val targetWidth = hostWidth.coerceAtLeast(1)
    val targetHeight = hostHeight.coerceAtLeast(1)
    fun mapAxis(
        value: Int,
        sourceOrigin: Int,
        sourceExtent: Int,
        destinationOrigin: Int,
        destinationExtent: Int,
        outputExtent: Int,
        physicalExtent: Int,
    ): Int {
        val relative = value.toLong() - sourceOrigin
        val logical =
            destinationOrigin.toLong() +
                relative * destinationExtent / sourceExtent
        return (logical * physicalExtent / outputExtent)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }
    val left =
        mapAxis(
            sourceLeft,
            0,
            sourceWidth,
            transform.destinationX,
            transform.destinationWidth,
            transform.presentationWidth,
            targetWidth,
        ).coerceIn(0, targetWidth - 1)
    val top =
        mapAxis(
            sourceTop,
            0,
            sourceHeight,
            transform.destinationY,
            transform.destinationHeight,
            transform.presentationHeight,
            targetHeight,
        ).coerceIn(0, targetHeight - 1)
    val right =
        mapAxis(
            sourceRight,
            0,
            sourceWidth,
            transform.destinationX,
            transform.destinationWidth,
            transform.presentationWidth,
            targetWidth,
        ).coerceIn(left + 1, targetWidth)
    val bottom =
        mapAxis(
            sourceBottom,
            0,
            sourceHeight,
            transform.destinationY,
            transform.destinationHeight,
            transform.presentationHeight,
            targetHeight,
        ).coerceIn(top + 1, targetHeight)
    return AccessibilityDisplayBounds(left, top, right, bottom)
}

internal fun mapAccessibilityDisplayBoundsFallback(
    sourceLeft: Int,
    sourceTop: Int,
    sourceRight: Int,
    sourceBottom: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    hostWidth: Int,
    hostHeight: Int,
): AccessibilityDisplayBounds {
    val width = hostWidth.coerceAtLeast(1)
    val height = hostHeight.coerceAtLeast(1)
    val sourceWidth = viewportWidth.coerceAtLeast(1)
    val sourceHeight = viewportHeight.coerceAtLeast(1)
    val left =
        (sourceLeft.toLong() * width / sourceWidth)
            .toInt()
            .coerceIn(0, width - 1)
    val top =
        (sourceTop.toLong() * height / sourceHeight)
            .toInt()
            .coerceIn(0, height - 1)
    val right =
        (sourceRight.toLong() * width / sourceWidth)
            .toInt()
            .coerceIn(left + 1, width)
    val bottom =
        (sourceBottom.toLong() * height / sourceHeight)
            .toInt()
            .coerceIn(top + 1, height)
    return AccessibilityDisplayBounds(left, top, right, bottom)
}
