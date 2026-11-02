package com.alo.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Where a picture sat on screen when it was tapped, in the app's root coordinates, and how round its corners were. */
internal data class AloMediaOrigin(val bounds: Rect, val cornerRadiusPx: Float)

/**
 * Hands the tapped picture's place to the viewer that is about to open, so the viewer can grow out of it (Telegram's PhotoViewer opens
 * from the thumbnail in 200 ms, its crop and corners fading away) and shrink back into it. Set on a tap, taken once by the viewer.
 */
internal object AloMediaTransition {
    private var pending: AloMediaOrigin? = null

    fun offer(origin: AloMediaOrigin?) {
        pending = origin
    }

    fun take(): AloMediaOrigin? = pending.also { pending = null }
}

/** Follows where one picture is on screen; [offer] publishes it for the viewer the tap opens. */
internal class AloMediaOriginTracker {
    var bounds: Rect? = null

    fun offer(cornerRadiusPx: Float = 0f) {
        AloMediaTransition.offer(bounds?.takeIf { it.width > 1f && it.height > 1f }?.let { AloMediaOrigin(it, cornerRadiusPx) })
    }
}

@Composable
internal fun rememberAloMediaOriginTracker(): AloMediaOriginTracker = remember { AloMediaOriginTracker() }

internal fun Modifier.trackMediaOrigin(tracker: AloMediaOriginTracker): Modifier =
    onGloballyPositioned { tracker.bounds = it.boundsInRoot() }
