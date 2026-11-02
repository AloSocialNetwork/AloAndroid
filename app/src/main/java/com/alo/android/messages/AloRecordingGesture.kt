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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A held mic, as Telegram (TGModernConversationInputMicButton) and the iOS app (AloRecordingDrag)
 * read it: sliding left 150dp cancels, sliding up 110dp locks the recording so the finger can let
 * go, and releasing sends. Distances are in dp, velocities in dp per second.
 */
internal data class AloRecordingDrag(val dx: Float = 0f, val dy: Float = 0f) {
    val cancelProgress: Float get() = (-dx / CANCEL_DISTANCE).coerceIn(0f, 1f)
    val lockProgress: Float get() = (-dy / LOCK_DISTANCE).coerceIn(0f, 1f)
    val shouldLock: Boolean get() = -dy > LOCK_DISTANCE && -dy > abs(dx) * 1.2f
    val shouldCancel: Boolean get() = -dx > CANCEL_DISTANCE && !shouldLock

    /** The orb under the finger, kept within the cancel and lock reach. */
    val orbX: Float get() = dx.coerceIn(-CANCEL_DISTANCE, 0f)
    val orbY: Float get() = dy.coerceIn(-LOCK_DISTANCE, 0f)

    enum class Release { Finish, Cancel, Lock }

    /** What letting go means: the dominant direction first, then a deliberate flick. */
    fun release(velocityX: Float, velocityY: Float): Release {
        val horizontal = abs(dx) > abs(dy)
        if ((horizontal && dx < -100f) || velocityX < -400f) return Release.Cancel
        if ((!horizontal && dy < -60f) || velocityY < -400f) return Release.Lock
        return Release.Finish
    }

    companion object {
        const val CANCEL_DISTANCE = 150f
        const val LOCK_DISTANCE = 110f
        /** How long a press waits before it becomes a recording (the iOS app's 0.19 s). */
        const val HOLD_MS = 190L
    }
}

/** The big circle under a holding finger: it breathes with the voice and shrinks towards cancel. */
@Composable
internal fun AloRecordingOrb(icon: ImageVector, color: Color, level: Float, cancelProgress: Float, modifier: Modifier = Modifier) {
    val swell by animateFloatAsState(1f + level.coerceIn(0f, 1f) * (50f / 110f), tween(120), label = "orbSwell")
    Box(
        modifier
            .requiredSize(110.dp)
            .graphicsLayer {
                val scale = 1f - cancelProgress * 0.5f
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .requiredSize(110.dp)
                .graphicsLayer { scaleX = swell; scaleY = swell }
                .clip(CircleShape)
                .background(color.copy(alpha = 0.18f))
        )
        Box(Modifier.requiredSize(110.dp).clip(CircleShape).background(color))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/** The lock above a holding finger: it fills as the finger rises, and a tap locks it too. */
@Composable
internal fun AloRecordingLock(progress: Float, onLock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 44.dp, height = 72.dp)
            .aloLiquidGlass(RoundedCornerShape(percent = 50))
            .clickable(onClick = onLock)
            .semantics { contentDescription = "Зафиксировать запись" },
        contentAlignment = Alignment.Center
    ) {
        val tint = if (progress > 0.6f) AloNativeAppAccent else AloNativeAppText
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (progress > 0.85f) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = tint, modifier = Modifier.size(18.dp))
            Icon(
                Icons.Rounded.KeyboardArrowUp, null, tint = tint,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        alpha = 1f - progress
                        translationY = -progress * 8.dp.toPx()
                    }
            )
        }
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
    val haptic: () -> Unit
)

private val AloMicTapCancelled = Any()

/**
 * Telegram's mic button: a tap switches the mode, a hold records while the finger stays down,
 * sliding left cancels, sliding up locks, and letting go sends. A locked recording sends on a tap.
 * Round videos record in their own overlay, so for them the hold only starts the camera.
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
        if (!controller.value.isVoice) {
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        controller.value.onHeld(true)
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var drag = AloRecordingDrag()
        var resolved = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - down.position
                drag = AloRecordingDrag(delta.x.toDp().value, delta.y.toDp().value)
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
        val velocity = tracker.calculateVelocity()
        when (drag.release(velocity.x.toDp().value, velocity.y.toDp().value)) {
            AloRecordingDrag.Release.Cancel -> now.onCancel()
            AloRecordingDrag.Release.Lock -> {
                now.haptic()
                now.onLock()
            }
            AloRecordingDrag.Release.Finish -> now.onSend()
        }
    }
}
