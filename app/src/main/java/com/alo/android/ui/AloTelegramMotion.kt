package com.alo.android

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/*
 * Telegram Android's motion, so screens and messages move as they do in the Telegram client.
 *
 * Pages (ActionBarLayout.startLayoutAnimation): a pushed page fades in while sliding 48dp from the
 * right, 150ms on DecelerateInterpolator(1.5); the page under it stays put. Closing does the reverse
 * on the top page. A swipe from the edge drags the page with the finger and settles on
 * AccelerateDecelerate in 50–200ms (closing) or 120–320ms (returning), by the distance left.
 *
 * Chat items (ChatListItemAnimator): 250ms on Telegram's chat curve, which also carries a sent
 * message from the composer into its bubble (TextMessageEnterTransition).
 */

/** DecelerateInterpolator(1.5): 1 − (1 − t)³. */
internal val AloTelegramDecelerate = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }

/** Android's AccelerateDecelerateInterpolator, the default of Telegram's swipe-back animators. */
internal val AloTelegramAccelerateDecelerate = Easing { t -> (cos((t + 1f) * PI) / 2.0 + 0.5).toFloat() }

/** ChatListItemAnimator.DEFAULT_INTERPOLATOR. */
internal val AloTelegramChatEasing = CubicBezierEasing(0.19919473f, 0.010644531f, 0.27920938f, 0.9102539f)

/** CubicBezierInterpolator.EASE_OUT and EASE_OUT_QUINT. */
internal val AloTelegramEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
internal val AloTelegramEaseOutQuint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

internal const val AloPageMotionMs = 150
internal const val AloChatMotionMs = 250
internal val AloPageShift = 48.dp

@Composable
internal fun rememberAloPageShiftPx(): Int = with(LocalDensity.current) { AloPageShift.roundToPx() }

internal fun aloPageEnter(shiftPx: Int): EnterTransition =
    fadeIn(tween(AloPageMotionMs, easing = AloTelegramDecelerate)) +
        slideInHorizontally(tween(AloPageMotionMs, easing = AloTelegramDecelerate)) { shiftPx }

/**
 * Telegram's page push (48dp and a fade, [AloPageMotionMs]) for a page that is heavy to build, as a chat is. The page is
 * composed, laid out and drawn once while still invisible, and the motion starts on the frame after. With AnimatedVisibility
 * the motion started in the same frame as the build, and a chat's first build (hundreds of ms on a cold open) swallowed
 * it: 4 of the 5 frames were late and the chat just appeared. Hiding is immediate, as before (a chat animates its own
 * swipe away).
 */
@Composable
internal fun AloDeferredPageEnter(visible: Boolean, shiftPx: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.snapTo(0f)
        if (!visible) return@LaunchedEffect
        // The first frame builds the page (however long it takes), the second draws it once, nearly transparent: so the
        // first visible frame of the motion has nothing left to build, upload or compile.
        withFrameNanos { }
        withFrameNanos { }
        progress.animateTo(1f, tween(AloPageMotionMs, easing = AloTelegramDecelerate))
    }
    if (visible) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val value = progress.value
                    // Not 0: a fully transparent layer is skipped by the renderer, and the page would then be drawn
                    // for the first time in the middle of the motion.
                    alpha = value.coerceAtLeast(0.004f)
                    translationX = shiftPx * (1f - value)
                }
        ) { content() }
    }
}

/** What a page shown by [AloSwipeBackPage] calls to leave: it slides out first, then the page's owner hides it. */
internal fun interface AloPageClose {
    fun close()
}

/**
 * A page pushed over the one it was opened from (Archive, calls, …), as a chat is: it comes in with Telegram's 48dp and fade
 * (built first, invisibly, as [AloDeferredPageEnter]), follows the finger of the system back swipe one to one with the page
 * underneath showing through, settles on release (Telegram's ActionBarLayout timing) and slides out when closed by its own
 * button ([AloPageClose]). Before, these pages were a BackHandler: the swipe showed the system arrow and then the page
 * vanished at once. [backEnabled] off leaves the back gesture to something inside the page (a selection, a sheet).
 */
@Composable
internal fun AloSwipeBackPage(
    visible: Boolean,
    onClosed: () -> Unit,
    backEnabled: Boolean = true,
    content: @Composable (AloPageClose) -> Unit
) {
    val shiftPx = rememberAloPageShiftPx()
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val enter = remember { Animatable(0f) }
    val swipe = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestOnClosed by rememberUpdatedState(onClosed)

    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            swipe.snapTo(0f)
            enter.snapTo(0f)
            withFrameNanos { }
            withFrameNanos { }
            enter.animateTo(1f, tween(AloPageMotionMs, easing = AloTelegramDecelerate))
        } else if (shown) {
            // Hidden from outside (or after our own close): finish the slide out if it is not finished yet.
            if (swipe.value < widthPx && enter.value > 0f) enter.animateTo(0f, tween(AloPageMotionMs, easing = AloTelegramDecelerate))
            shown = false
        }
    }

    fun slideOut(fromSwipe: Boolean) {
        scope.launch {
            if (fromSwipe && swipe.value > 0f) {
                swipe.animateTo(widthPx, tween(aloSwipeSettleMs(widthPx - swipe.value, widthPx, closing = true), easing = AloTelegramAccelerateDecelerate))
            } else {
                enter.animateTo(0f, tween(AloPageMotionMs, easing = AloTelegramDecelerate))
            }
            latestOnClosed()
        }
    }

    if (shown) {
        // Registered when the page appears, not with the screen it lives in: the system gives back to the handler added last,
        // so a handler composed at start lost to the settings the page was opened from («Панель автора» stayed and the
        // settings under it closed instead). Handlers inside the page are added after this one and still come first.
        PredictiveBackHandler(enabled = visible && backEnabled) { events ->
            var start = Float.NaN
            var followed = false
            try {
                events.collect { event ->
                    if (start.isNaN()) start = event.touchX
                    followed = true
                    // Either edge: the distance the finger has travelled.
                    swipe.snapTo(kotlin.math.abs(event.touchX - start).coerceIn(0f, widthPx))
                }
                slideOut(fromSwipe = followed)
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                scope.launch {
                    swipe.animateTo(0f, tween(aloSwipeSettleMs(swipe.value, widthPx, closing = false), easing = AloTelegramAccelerateDecelerate))
                }
            }
        }
        val close = remember { AloPageClose { slideOut(fromSwipe = false) } }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val value = enter.value
                    alpha = value.coerceAtLeast(0.004f)
                    translationX = swipe.value + shiftPx * (1f - value)
                    shadowElevation = if (swipe.value > 0f) 18f else 0f
                }
        ) { content(close) }
    }
}

internal fun aloPageExit(shiftPx: Int): ExitTransition =
    fadeOut(tween(AloPageMotionMs, easing = AloTelegramDecelerate)) +
        slideOutHorizontally(tween(AloPageMotionMs, easing = AloTelegramDecelerate)) { shiftPx }

/**
 * A push ([forward]) or a pop between the pages of an AnimatedContent: the top page moves as in
 * Telegram (48dp and a fade), drawn over the page it covers or uncovers. Telegram's pages are opaque,
 * so the page underneath simply stays; ours are often transparent over a shared background, so it
 * fades in step instead of vanishing at the end.
 */
internal fun aloPageTransform(forward: Boolean, shiftPx: Int): ContentTransform =
    if (forward) {
        ContentTransform(aloPageEnter(shiftPx), fadeOut(tween(AloPageMotionMs, easing = AloTelegramDecelerate)), targetContentZIndex = 1f)
    } else {
        ContentTransform(fadeIn(tween(AloPageMotionMs, easing = AloTelegramDecelerate)), aloPageExit(shiftPx), targetContentZIndex = -1f)
    }

/** Telegram's swipe-back settle time: by the distance left, within the bounds above. */
internal fun aloSwipeSettleMs(distancePx: Float, widthPx: Float, closing: Boolean): Int {
    if (widthPx <= 0f) return if (closing) 200 else 320
    return if (closing) {
        (200f / widthPx * distancePx).toInt().coerceAtLeast(50)
    } else {
        (320f / widthPx * distancePx).toInt().coerceAtLeast(120)
    }
}

/**
 * Telegram's swipe-back decision (ActionBarLayout.onTouchEvent): the page closes once it is a third
 * of the way out, or on a fast flick that is more sideways than vertical.
 */
internal fun aloTelegramSwipeCloses(offsetPx: Float, velocityX: Float, velocityY: Float, widthPx: Float): Boolean {
    if (widthPx <= 0f) return false
    val stays = offsetPx < widthPx / 3f && (velocityX < 3500f || kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY))
    return !stays
}
