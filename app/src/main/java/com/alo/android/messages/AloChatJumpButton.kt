package com.alo.android

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Android's DecelerateInterpolator(), which Telegram's side buttons use: 1 − (1 − t)². */
private val AloDecelerate = Easing { t -> 1f - (1f - t) * (1f - t) }

/**
 * Telegram's page-down button (ChatActivitySideControlsButtonsLayout): a round glass button above
 * the composer once you have scrolled up, rising 80dp from 70% while it fades in over 280ms, with
 * the number of messages that arrived below you on top of it.
 */
@Composable
internal fun AloJumpDownButton(visible: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(280, easing = AloDecelerate), label = "jumpDown")
    if (shown <= 0.001f) return
    Box(
        modifier.graphicsLayer {
            alpha = shown
            val scale = 0.7f + 0.3f * shown
            scaleX = scale
            scaleY = scale
            translationY = 80.dp.toPx() * (1f - shown)
            transformOrigin = TransformOrigin(0.5f, 0.6f)
        }
    ) {
        Box(
            Modifier
                // The button is 56dp with a 6dp click margin (`CLICK_ZONE_MARGIN`): the circle itself is 44dp (`BUTTON_SIZE`).
                .size(AloTelegramMetrics.SideButtons.CircleSize)
                .aloLiquidGlass(CircleShape)
                .clickable(enabled = visible, onClick = onClick)
                .semantics { contentDescription = "К последним сообщениям" },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = AloNativeAppText, modifier = Modifier.size(28.dp))
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    // CounterView: a pill 23dp high (radius 11.5), its centre on the button's top edge, 13sp text.
                    .offset(y = -AloTelegramMetrics.SideButtons.CounterHeight / 2)
                    .heightIn(min = AloTelegramMetrics.SideButtons.CounterHeight)
                    .widthIn(min = AloTelegramMetrics.SideButtons.CounterHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(AloNativeAppAccent)
                    .padding(horizontal = AloTelegramMetrics.SideButtons.CounterPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(if (count > 999) "999+" else "$count", color = Color.White, fontSize = AloTelegramMetrics.SideButtons.CounterTextSize, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** To the newest message: a long way jumps close first, then the rest scrolls, as Telegram does. */
internal suspend fun LazyListState.aloScrollToLatest(itemCount: Int) {
    if (itemCount <= 0) return
    val last = itemCount - 1
    val firstVisible = firstVisibleItemIndex
    if (last - firstVisible > 20) scrollToItem((last - 8).coerceAtLeast(0))
    animateScrollToItem(last)
}
