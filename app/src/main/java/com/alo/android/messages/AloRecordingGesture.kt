package com.alo.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A held mic, as Telegram Android reads it (ChatActivityEnterView): sliding left by 35 % of the
 * screen width, at most 140dp, cancels; sliding up 57dp locks the recording so the finger can let
 * go (only while it has slid less than 30 % towards cancel); letting go sends. Distances in dp.
 */
internal data class AloRecordingDrag(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val cancelDistance: Float = MAX_CANCEL_DISTANCE
) {
    val cancelProgress: Float get() = (-dx / cancelDistance).coerceIn(0f, 1f)
    val lockProgress: Float get() = (-dy / LOCK_DISTANCE).coerceIn(0f, 1f)
    val shouldLock: Boolean get() = -dy >= LOCK_DISTANCE && cancelProgress < 0.3f
    val shouldCancel: Boolean get() = cancelProgress >= 1f && !shouldLock

    /** The circle rises with the finger up to the lock; sideways it stays and shrinks. */
    val orbY: Float get() = dy.coerceIn(-LOCK_DISTANCE, 0f)

    /** Telegram's circle: 0.7 + 0.3 × (1 − cancel progress). */
    val orbScale: Float get() = 0.7f + 0.3f * (1f - cancelProgress)

    enum class Release { Finish, Cancel, Lock }

    /** Telegram Android sends on release; cancel and lock happen while the finger moves. */
    fun release(): Release = when {
        shouldLock -> Release.Lock
        shouldCancel -> Release.Cancel
        else -> Release.Finish
    }

    companion object {
        const val MAX_CANCEL_DISTANCE = 140f
        const val LOCK_DISTANCE = 57f
        /** How long a press waits before it becomes a recording (the iOS app's 0.19 s). */
        const val HOLD_MS = 190L

        fun cancelDistanceFor(screenWidthDp: Float): Float = minOf(MAX_CANCEL_DISTANCE, screenWidthDp * 0.35f)
    }
}

/** Telegram Android's record circle: 41dp radius, two translucent waves (47–62dp) breathing with the voice. */
@Composable
internal fun AloRecordingOrb(
    icon: ImageVector,
    color: Color,
    level: Float,
    scale: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val voice by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "orbVoice")
    Box(
        modifier
            .requiredSize(124.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .requiredSize((100f + 24f * voice).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
        )
        Box(
            Modifier
                .requiredSize((94f + 22f * voice).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.3f))
        )
        Box(Modifier.requiredSize(82.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * Telegram Android's lock above a held mic: a 36dp pill (radius 18) that is 50dp tall at rest and
 * shrinks to a 36dp circle as the finger rises; the shackle closes and the arrow above fades.
 */
@Composable
internal fun AloRecordingLock(progress: Float, onLock: () -> Unit, modifier: Modifier = Modifier) {
    val height = 50f - 14f * progress
    Box(
        modifier
            .size(width = 36.dp, height = height.dp)
            .aloLiquidGlass(RoundedCornerShape(18.dp))
            .clickable(onClick = onLock)
            .semantics { contentDescription = "Зафиксировать запись" },
        contentAlignment = Alignment.Center
    ) {
        val tint = if (progress > 0.6f) AloNativeAppAccent else AloNativeAppText
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.KeyboardArrowUp, null, tint = tint,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        alpha = (1f - progress * 1.6f).coerceAtLeast(0f)
                        translationY = -progress * 6.dp.toPx()
                    }
            )
            Icon(if (progress > 0.85f) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

/** After locking, Telegram turns the lock into a 36dp pause button that stops into a preview. */
@Composable
internal fun AloRecordingPauseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .aloLiquidGlass(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Завершить и прослушать запись" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Pause, null, tint = AloNativeAppText, modifier = Modifier.size(20.dp))
    }
}

/** What the held mic may do, rebuilt with the composer's latest state on every recomposition. */
internal class AloHeldMicController(
    val isRecording: Boolean,
    val isLocked: Boolean,
    val isVoice: Boolean,
    /** A tap switches between voice and round video, as in Telegram. */
    val onTap: () -> Unit,
    val onStart: () -> Unit,
    val onCancel: () -> Unit,
    /** Release: stop and send. */
    val onSend: () -> Unit,
    /** A tap on a recording that was not held (or a locked one's stop): stop to a preview. */
    val onStopToPreview: () -> Unit,
    val onDrag: (AloRecordingDrag) -> Unit,
    val onHeld: (Boolean) -> Unit,
    val onLock: () -> Unit,
    val haptic: () -> Unit,
    /** Telegram: 35 % of the screen width, at most 140dp. */
    val cancelDistance: Float = AloRecordingDrag.MAX_CANCEL_DISTANCE
)

private val AloMicTapCancelled = Any()

/**
 * Telegram's mic button: a tap switches the mode, a hold records while the finger stays down,
 * sliding left cancels, sliding up locks, and letting go sends. A locked recording sends on a tap.
 * Round videos are held the same way; their camera overlay takes the send, preview and cancel.
 */
internal fun Modifier.aloHeldMic(controller: State<AloHeldMicController>): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (controller.value.isRecording) {
            if (waitForUpOrCancellation() != null) {
                val now = controller.value
                if (now.isLocked) now.onSend() else now.onStopToPreview()
            }
            return@awaitEachGesture
        }
        val early = withTimeoutOrNull(AloRecordingDrag.HOLD_MS) { waitForUpOrCancellation() ?: AloMicTapCancelled }
        if (early === AloMicTapCancelled) return@awaitEachGesture
        if (early != null) {
            controller.value.onTap()
            return@awaitEachGesture
        }
        controller.value.haptic()
        controller.value.onStart()
        // A round video is held, slid, locked and released exactly like a voice message (Telegram Android).
        controller.value.onHeld(true)
        var drag = AloRecordingDrag(cancelDistance = controller.value.cancelDistance)
        var resolved = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                drag = AloRecordingDrag(delta.x.toDp().value, delta.y.toDp().value, controller.value.cancelDistance)
                if (!resolved) {
                    val now = controller.value
                    when {
                        now.isLocked -> resolved = true
                        drag.shouldLock -> {
                            resolved = true
                            now.onDrag(AloRecordingDrag())
                            now.haptic()
                            now.onLock()
                        }
                        drag.shouldCancel -> {
                            resolved = true
                            now.onDrag(AloRecordingDrag())
                            now.onCancel()
                        }
                        else -> now.onDrag(drag)
                    }
                }
                if (change.pressed) {
                    change.consume()
                } else {
                    released = true
                    break
                }
            }
        } finally {
            controller.value.onHeld(false)
            controller.value.onDrag(AloRecordingDrag())
        }
        val now = controller.value
        if (resolved || now.isLocked) return@awaitEachGesture
        // Taken away by the system (a gesture, a dialog): drop it rather than send it.
        if (!released) {
            now.onCancel()
            return@awaitEachGesture
        }
        when (drag.release()) {
            AloRecordingDrag.Release.Cancel -> now.onCancel()
            AloRecordingDrag.Release.Lock -> {
                now.haptic()
                now.onLock()
            }
            AloRecordingDrag.Release.Finish -> now.onSend()
        }
    }
}
