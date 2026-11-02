package com.alo.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reaction a person has just chosen, until its chip appears and plays its effect. Chips are built from the server's list, so the
 * tap (here or in the action sheet) says «this one is new» and the chip that shows up for that message and emoji takes it.
 */
internal object AloReactionEffects {
    private var pending: Triple<Int, String, Long>? = null
    private const val LIFETIME_MS = 4_000L

    fun request(messageId: Int, emoji: String) {
        pending = Triple(messageId, emoji, System.currentTimeMillis())
    }

    fun take(messageId: Int, emoji: String): Boolean {
        val current = pending ?: return false
        if (current.first != messageId || current.second != emoji) return false
        pending = null
        return System.currentTimeMillis() - current.third <= LIFETIME_MS
    }
}

/**
 * Telegram's reaction effect: the emoji swells over its chip and floats up while a ring of small ones flies out and fades.
 * [trigger] counts the plays; it is drawn in a popup so it is not cut off by the bubble.
 */
@Composable
internal fun AloReactionBurst(emoji: String, trigger: Int) {
    if (trigger <= 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(durationMillis = 900, easing = LinearOutSlowInEasing)) }
    if (progress.value >= 1f) return
    Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        // The popup's window is as big as this box and clips what leaves it, so the box has room for the whole effect.
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            Text(
                emoji,
                fontSize = 22.sp,
                modifier = Modifier.graphicsLayer {
                    val p = progress.value
                    val scale = 1f + 2.4f * (1f - (1f - p) * (1f - p))
                    scaleX = scale
                    scaleY = scale
                    translationY = -84.dp.toPx() * p
                    alpha = (1f - p * p).coerceIn(0f, 1f)
                }
            )
            repeat(8) { index ->
                val angle = Math.toRadians(index * 45.0 + 22.5)
                Text(
                    emoji,
                    fontSize = 12.sp,
                    modifier = Modifier.graphicsLayer {
                        val p = progress.value
                        val distance = 64.dp.toPx() * p
                        translationX = (cos(angle) * distance).toFloat()
                        translationY = (sin(angle) * distance).toFloat() - 14.dp.toPx() * p
                        val scale = 1f - 0.55f * p
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - p).coerceIn(0f, 1f)
                    }
                )
            }
        }
    }
}
