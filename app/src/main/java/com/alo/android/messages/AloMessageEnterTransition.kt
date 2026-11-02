package com.alo.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Telegram's message entrance (TextMessageEnterTransition with ChatListItemAnimator's timing):
 * a message you send lifts out of the composer into its place, moving sideways fast
 * (EASE_OUT over EASE_OUT_QUINT) and upwards on the chat curve, its bubble showing within the first
 * 40% of the 250ms; a new message from someone else rises from behind the composer.
 *
 * Positions are taken in window coordinates on every frame, so the list scrolling to the new message
 * at the same time (as Telegram's does) carries it along instead of adding to its movement.
 */

/** One chat's entrances: where the composer is, and which messages still have to come in. */
@Stable
internal class AloMessageEnterState {
    /** Top-left of the composer's text, in window coordinates. */
    var composerText: Offset = Offset.Unspecified
    /** Top of the composer: messages from others rise from behind it. */
    var composerTop: Float = Float.NaN

    /** Keys of messages that enter, and whether each comes out of the composer. */
    internal val entering = HashMap<Int, Boolean>()
    /** Keys whose entrance has run, so a message scrolled away and back does not replay it. */
    internal val played = HashSet<Int>()
    /** Sent messages on their way out of the composer, drawn above it by [AloMessageFlightOverlay]. */
    internal val flights = mutableStateListOf<AloMessageFlight>()

    private val known = HashSet<Int>()
    private var primed = false

    /**
     * Notes the messages on screen, given as stable keys in list order. The first non-empty list
     * is history; later, a few new keys at the bottom are messages arriving now. Bigger batches
     * (a page of history, an offline catch-up) just appear.
     */
    fun observe(keys: List<Int>, fromComposer: (index: Int) -> Boolean) {
        if (keys.isEmpty()) return
        if (!primed) {
            primed = true
            known += keys
            return
        }
        val fresh = keys.indices.filter { keys[it] !in known }
        known += keys
        if (fresh.isEmpty() || fresh.size > 3) return
        fresh.forEach { index ->
            if (index >= keys.size - 3) entering[keys[index]] = fromComposer(index)
        }
    }

    fun enterFor(key: Int): AloMessageEnter? =
        entering[key]?.takeIf { key !in played }?.let { AloMessageEnter(key, it, this) }
}

internal class AloMessageEnter(val key: Int, val fromComposer: Boolean, val state: AloMessageEnterState)

internal val LocalAloMessageEnterState = staticCompositionLocalOf<AloMessageEnterState?> { null }
internal val LocalAloMessageEnter = staticCompositionLocalOf<AloMessageEnter?> { null }

internal class AloEnterTrack {
    var coordinates: LayoutCoordinates? = null
    var start: Offset = Offset.Unspecified
    var placed by mutableStateOf(false)
}

/**
 * A sent message in flight. Telegram draws it above the input field
 * (MessageEnterTransitionContainer), so the bubble records itself into [layer] instead of drawing in
 * the list, and the overlay above the composer draws that recording where the bubble is now.
 */
internal class AloMessageFlight(
    val layer: GraphicsLayer,
    private val track: AloEnterTrack,
    private val progress: Animatable<Float, AnimationVector1D>
) {
    /** The bubble's top-left in window coordinates and its opacity, or null when there is nothing to draw. */
    fun frame(): Pair<Offset, Float>? {
        val t = progress.value
        val coordinates = track.coordinates
        if (t >= 1f || !track.placed || coordinates == null || !coordinates.isAttached) return null
        val here = coordinates.positionInWindow()
        val towardsY = AloTelegramChatEasing.transform(t)
        val towardsX = AloTelegramEaseOut.transform(AloTelegramEaseOutQuint.transform(t))
        val at = Offset(
            here.x + (track.start.x - here.x) * (1f - towardsX),
            here.y + (track.start.y - here.y) * (1f - towardsY)
        )
        return at to (t / 0.4f).coerceAtMost(1f)
    }
}

/** Draws sent messages in flight; it sits above the composer and takes no touches. */
@Composable
internal fun AloMessageFlightOverlay(state: AloMessageEnterState, modifier: Modifier = Modifier) {
    val origin = remember { floatArrayOf(0f, 0f) }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                origin[0] = position.x
                origin[1] = position.y
            }
            .drawBehind {
                state.flights.forEach { flight ->
                    val (at, alpha) = flight.frame() ?: return@forEach
                    flight.layer.alpha = alpha
                    translate(at.x - origin[0], at.y - origin[1]) { drawLayer(flight.layer) }
                }
            }
    )
}

/** The entrance for a message bubble; [enter] is null for messages already on screen. */
internal fun Modifier.aloMessageEnter(enter: AloMessageEnter?): Modifier =
    if (enter == null) this else composed {
        val track = remember(enter.key) { AloEnterTrack() }
        // The bubble's text lines up with the composer's: 11dp in, 8dp down.
        val textInset = with(LocalDensity.current) { Offset(11.dp.toPx(), 8.dp.toPx()) }
        val progress = remember(enter.key) { Animatable(if (enter.key in enter.state.played) 1f else 0f) }
        val flightLayer = if (enter.fromComposer) rememberGraphicsLayer() else null
        val flight = remember(enter.key, flightLayer) { flightLayer?.let { AloMessageFlight(it, track, progress) } }
        DisposableEffect(flight) {
            if (flight != null && progress.value < 1f) enter.state.flights += flight
            onDispose { flight?.let { enter.state.flights -= it } }
        }
        LaunchedEffect(enter.key) {
            if (progress.value >= 1f) return@LaunchedEffect
            snapshotFlow { track.placed }.first { it }
            progress.animateTo(1f, tween(AloChatMotionMs, easing = LinearEasing))
            flight?.let { enter.state.flights -= it }
            enter.state.played += enter.key
            enter.state.entering -= enter.key
        }
        this
            .onGloballyPositioned { coordinates ->
                track.coordinates = coordinates
                if (!track.placed) {
                    val here = coordinates.positionInWindow()
                    val state = enter.state
                    track.start = when {
                        enter.fromComposer && state.composerText != Offset.Unspecified -> state.composerText - textInset
                        !state.composerTop.isNaN() -> Offset(here.x, maxOf(state.composerTop, here.y))
                        else -> here
                    }
                    track.placed = true
                }
            }
            .graphicsLayer {
                val t = progress.value
                alpha = when {
                    t >= 1f -> 1f
                    !track.placed -> 0f
                    enter.fromComposer || track.start == track.coordinates?.positionInWindow() -> (t / 0.4f).coerceAtMost(1f)
                    else -> 1f
                }
            }
            .drawWithContent {
                val t = progress.value
                val coordinates = track.coordinates
                if (t >= 1f || !track.placed || coordinates == null || !coordinates.isAttached) {
                    drawContent()
                    return@drawWithContent
                }
                if (flightLayer != null) {
                    // Drawn above the composer by the overlay, from this recording.
                    flightLayer.record { this@drawWithContent.drawContent() }
                    return@drawWithContent
                }
                val here = coordinates.positionInWindow()
                val towardsY = AloTelegramChatEasing.transform(t)
                val towardsX = AloTelegramEaseOut.transform(AloTelegramEaseOutQuint.transform(t))
                translate(
                    left = if (enter.fromComposer) (track.start.x - here.x) * (1f - towardsX) else 0f,
                    top = (track.start.y - here.y) * (1f - towardsY)
                ) { this@drawWithContent.drawContent() }
            }
    }

/**
 * Follows a new message to the end of the list as Telegram does: once it is laid out, the list
 * scrolls by what hangs below the visible area, in 250ms on the chat curve; from further up it jumps.
 */
internal suspend fun LazyListState.aloScrollToNewMessage(itemCount: Int) {
    if (itemCount <= 0) return
    withTimeoutOrNull(300) { snapshotFlow { layoutInfo.totalItemsCount }.first { it >= itemCount } }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    if (last == null || last.index < itemCount - 1) {
        scrollToItem(itemCount - 1)
        return
    }
    val overflow = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
    if (overflow > 0) animateScrollBy(overflow.toFloat(), tween(AloChatMotionMs, easing = AloTelegramChatEasing))
}
