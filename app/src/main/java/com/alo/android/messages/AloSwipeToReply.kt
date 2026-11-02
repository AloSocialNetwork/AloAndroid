package com.alo.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Telegram's swipe to reply (ChatActivity's slidingView): drag a message left, at most 80dp. From
 * 20dp a 32dp reply badge appears at the right edge and its ring fills by 50dp, where the phone
 * ticks; letting go past 50dp replies, and the message slides back in 180ms. An outgoing message's
 * badge travels half as far, as Telegram's does.
 */
internal fun Modifier.aloSwipeToReply(enabled: Boolean, outgoing: Boolean, onReply: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val currentReply by rememberUpdatedState(onReply)
        val maxDrag = with(density) { 80.dp.toPx() }
        val showAt = with(density) { 20.dp.toPx() }
        val replyAt = with(density) { 50.dp.toPx() }
        val startSlop = with(density) { 15.dp.toPx() } // Telegram's 0.4 cm
        var offset by remember { mutableFloatStateOf(0f) }
        val badge = remember { Animatable(0f) }
        val fill = remember { Animatable(0f) }
        val arrow = rememberVectorPainter(Icons.AutoMirrored.Rounded.Reply)
        this
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var tracking = false
                    var ticked = false
                    var shown = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        dx += delta.x
                        dy += delta.y
                        if (!tracking) {
                            if (change.isConsumed) break
                            if (abs(dy) > startSlop && abs(dy) > abs(dx)) break
                            if (dx > startSlop) break
                            if (dx <= -startSlop && abs(dx) / 3f > abs(dy)) tracking = true else continue
                        }
                        change.consume()
                        offset = dx.coerceIn(-maxDrag, 0f)
                        val past = -offset >= replyAt
                        if (past != ticked) {
                            if (past) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch { fill.animateTo(if (past) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
                        }
                        ticked = past
                        val show = -offset >= showAt
                        if (show != shown) {
                            scope.launch { badge.animateTo(if (show) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        }
                        shown = show
                    }
                    if (!tracking) return@awaitEachGesture
                    if (-offset >= replyAt) currentReply()
                    val from = offset
                    scope.launch {
                        val back = Animatable(from)
                        back.animateTo(0f, tween(180, easing = AloTelegramDecelerate)) { offset = value }
                    }
                    scope.launch { badge.animateTo(0f, tween(180)) }
                    scope.launch { fill.animateTo(0f, tween(180)) }
                }
            }
            .drawWithContent {
                val x = offset
                translate(left = x) { this@drawWithContent.drawContent() }
                val visible = badge.value
                if (visible <= 0.01f) return@drawWithContent
                val radius = 16.dp.toPx()
                val centre = Offset(size.width + x * (if (outgoing) 0.5f else 1f) - radius * 0.2f, size.height / 2f)
                val ring = ((-x - showAt) / (replyAt - showAt)).coerceIn(0f, 1f)
                val filled = fill.value
                // Past 50dp the badge pops a little (Telegram's fill spring).
                val scale = visible * (1f + 0.12f * filled * (1f - filled) * 4f)
                val r = radius * scale
                drawCircle(Color.Black.copy(alpha = (0.32f * visible * (0.6f * ring + 0.4f * filled)).coerceAtMost(0.32f)), r, centre)
                if (filled < 0.99f) {
                    val stroke = 2.dp.toPx()
                    drawArc(
                        color = Color.White.copy(alpha = 0.85f * visible),
                        startAngle = -90f,
                        sweepAngle = 360f * ring,
                        useCenter = false,
                        topLeft = Offset(centre.x - r + stroke / 2f, centre.y - r + stroke / 2f),
                        size = Size((r - stroke / 2f) * 2f, (r - stroke / 2f) * 2f),
                        style = Stroke(stroke)
                    )
                }
                val icon = 18.dp.toPx() * scale
                translate(centre.x - icon / 2f, centre.y - icon / 2f) {
                    with(arrow) { draw(Size(icon, icon), alpha = visible, colorFilter = ColorFilter.tint(Color.White)) }
                }
            }
    }
