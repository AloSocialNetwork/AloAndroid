package com.alo.android

/**
 * Shared decision rule for an interactive right-swipe dismissal.
 * Small accidental pulls snap back. A deliberate distance or a fast fling
 * completes the transition, independent of the screen that uses it.
 */
internal object AloInteractiveDismissPolicy {
    fun shouldDismiss(
        offsetPx: Float,
        velocityPxPerSecond: Float,
        widthPx: Float
    ): Boolean {
        if (widthPx <= 0f) return false
        val progress = offsetPx.coerceAtLeast(0f) / widthPx
        val projectedProgress = (
            offsetPx.coerceAtLeast(0f) + velocityPxPerSecond.coerceAtLeast(0f) * 0.14f
        ) / widthPx
        return progress >= 0.30f || projectedProgress >= 0.46f || velocityPxPerSecond >= 980f
    }
}
