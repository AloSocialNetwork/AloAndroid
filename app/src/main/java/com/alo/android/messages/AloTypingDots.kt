package com.alo.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Telegram's TypingDotsDrawable: three dots 6dp apart, each swelling from 1.33dp to 2.33dp over
 * 320ms (decelerating) and back, then resting, on an 800ms cycle; they start 150ms apart.
 */
@Composable
internal fun AloTypingDots(color: Color, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        for (dot in 0 until 3) {
            val since = elapsed - dot * 150L
            val swell = if (since < 0) 0f else aloTypingDotSwell((since % 800L).toFloat())
            drawCircle(color, radius = (1.33f + swell).dp.toPx(), center = Offset((3 + dot * 6).dp.toPx(), size.height / 2f))
        }
    }
}

/** How far a dot has swelled [t] ms into its 800ms cycle, 0…1. */
internal fun aloTypingDotSwell(t: Float): Float {
    fun decelerate(x: Float) = 1f - (1f - x) * (1f - x)
    return when {
        t <= 320f -> decelerate(t / 320f)
        t <= 640f -> 1f - decelerate((t - 320f) / 320f)
        else -> 0f
    }
}
