package com.alo.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Voice recording in the chat composer, laid out as Telegram Android 13 (ChatActivityEnterView):
 * the record panel takes the input field's place — a 10dp red dot in a 28dp box 13dp in, the timer
 * («0:03,45», 15sp) 6dp after it, and «‹ Влево — отмена» centred, swaying ±6dp; once locked the
 * hint turns into an accent «ОТМЕНА». The recorded preview is the accent strip with play, waveform
 * and time. Sizes: docs in the side-session notes (voice-recording-telegram-metrics.md).
 */

private val RecordDotColor = Color(0xFFFF2D55)

/** «0:03,45»: Telegram shows hundredths while recording. */
internal fun aloRecordingClockLabel(elapsedMs: Long): String {
    val ms = elapsedMs.coerceAtLeast(0)
    val seconds = ms / 1000
    val hundredths = (ms % 1000) / 10
    return if (seconds >= 3600) {
        "%d:%02d:%02d,%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60, hundredths)
    } else {
        "%d:%02d,%02d".format(seconds / 60, seconds % 60, hundredths)
    }
}

/**
 * Debug builds only: `--es alo_ui_voice held|slide|lift|locked|preview` together with
 * `--es alo_ui_preview chat` shows a recording state without a microphone (screenshots).
 */
@Composable
internal fun rememberAloVoiceRecordingFixture(): String? {
    val context = LocalContext.current
    return remember(context) {
        if (!BuildConfig.DEBUG) return@remember null
        var current: Context? = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        (current as? Activity)?.intent?.getStringExtra("alo_ui_voice")?.takeIf { it.isNotBlank() }
    }
}

internal val AloVoiceFixtureLevels: List<Float> = List(48) { index ->
    (0.25f + 0.65f * kotlin.math.abs(kotlin.math.sin(index * 0.55f)) * (0.6f + 0.4f * kotlin.math.sin(index * 0.17f))).coerceIn(0.08f, 1f)
}

/** The record panel inside the field: dot + timer at the start, the cancel hint centred. */
@Composable
internal fun AloVoiceRecordingPanel(
    startedAtMs: Long,
    isLocked: Boolean,
    drag: AloRecordingDrag,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var timerRight by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = constraints.maxWidth.toFloat()
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .onSizeChanged { timerRight = it.width.toFloat() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            AloRecordDot()
            Spacer(Modifier.width(6.dp))
            AloRecordingClock(startedAtMs)
        }
        if (isLocked) {
            Text(
                text = "ОТМЕНА",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
                color = AloNativeChatSendAccent,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onCancel)
                    .semantics { contentDescription = "Удалить запись" }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        } else {
            AloSlideToCancelHint(
                drag = drag,
                panelWidth = panelWidth,
                timerRight = timerRight,
                onCancel = onCancel,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** Telegram's dot: 10dp in a 28dp box, fading out and in over 600ms each way. */
@Composable
private fun AloRecordDot() {
    val blink = rememberInfiniteTransition(label = "recordDot")
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "recordDotAlpha"
    )
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(RecordDotColor)
        )
    }
}

@Composable
private fun AloRecordingClock(startedAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            withFrameMillis { }
            now = System.currentTimeMillis()
            delay(33)
        }
    }
    Text(
        text = aloRecordingClockLabel(now - startedAtMs),
        style = TextStyle(fontSize = 15.sp, fontFeatureSettings = "tnum"),
        color = AloNativeAppText,
        maxLines = 1
    )
}

/**
 * «‹ Влево — отмена», 15sp grey: it sways ±6dp (3dp per 250ms), slides a quarter of the panel to the
 * left as the finger does, and fades out before it reaches the timer.
 */
@Composable
private fun AloSlideToCancelHint(
    drag: AloRecordingDrag,
    panelWidth: Float,
    timerRight: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sway = rememberInfiniteTransition(label = "slideSway")
    val swayDp by sway.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "slideSwayDp"
    )
    val density = LocalDensity.current
    var hintWidth by remember { mutableFloatStateOf(0f) }
    val slideProgress = 1f - drag.cancelProgress
    val muted = AloNativeAppMuted
    Row(
        modifier = modifier
            .onSizeChanged { hintWidth = it.width.toFloat() }
            .graphicsLayer {
                // SlideTextView sits 45dp in and draws 5 + 16dp right of its centre: ~43dp right of the panel's.
                val slide = -panelWidth / 4f * (1f - slideProgress)
                translationX = with(density) { (43f + swayDp * slideProgress).dp.toPx() } + slide
                val left = (panelWidth - hintWidth) / 2f + translationX
                // Telegram clips the hint at the timer; fading over the last 12dp reads the same.
                alpha = ((left - timerRight + with(density) { 8.dp.toPx() }) / with(density) { 12.dp.toPx() }).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onCancel)
            .semantics { contentDescription = "Отменить запись" }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(width = 6.dp, height = 12.dp)) {
            val mid = size.height / 2f
            val path = Path().apply {
                moveTo(4.dp.toPx() + 1f, mid - 5.dp.toPx())
                lineTo(1f, mid)
                lineTo(4.dp.toPx() + 1f, mid + 5.dp.toPx())
            }
            drawPath(path, muted, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Влево — отмена",
            style = TextStyle(fontSize = 15.sp),
            color = muted,
            maxLines = 1
        )
    }
}

/** Plays a recorded draft before it is sent; released with the composable. */
@Stable
internal class AloDraftVoicePlayer(private val context: Context, private val uri: String) {
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var player: MediaPlayer? = null

    fun toggle() {
        val current = player ?: runCatching {
            MediaPlayer().apply {
                if (uri.startsWith("/")) setDataSource(uri) else setDataSource(context, Uri.parse(uri))
                setOnCompletionListener {
                    this@AloDraftVoicePlayer.isPlaying = false
                    progress = 0f
                    runCatching { seekTo(0) }
                }
                prepare()
            }
        }.getOrNull()?.also { player = it } ?: return
        if (current.isPlaying) {
            current.pause()
            isPlaying = false
        } else {
            current.start()
            isPlaying = true
        }
    }

    fun tick() {
        val current = player ?: return
        val duration = runCatching { current.duration }.getOrDefault(0)
        if (duration > 0 && isPlaying) progress = (current.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }
}

/**
 * Telegram's recorded preview inside the field: an accent strip with play/pause, the white
 * waveform (played part solid, the rest at 45 %) and the length.
 */
@Composable
internal fun AloVoiceDraftPreview(
    uri: String,
    levels: List<Float>,
    durationSec: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(uri) { AloDraftVoicePlayer(context.applicationContext, uri) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, player.isPlaying) {
        while (player.isPlaying) {
            player.tick()
            withFrameMillis { }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AloNativeChatSendAccent)
            .clickable(onClick = player::toggle)
            .semantics {
                contentDescription = if (player.isPlaying) "Приостановить предпросмотр" else "Прослушать запись"
            }
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(6.dp))
        val bars = levels.ifEmpty { AloVoiceFixtureLevels.map { it * 0.2f } }
        val progress = player.progress
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            val barWidth = 2.dp.toPx()
            val step = 3.dp.toPx()
            val count = (size.width / step).toInt().coerceAtLeast(1)
            for (index in 0 until count) {
                val level = bars[(index * bars.size / count).coerceIn(0, bars.size - 1)].coerceIn(0.08f, 1f)
                val height = (14.dp.toPx() * level).coerceAtLeast(2.dp.toPx())
                val played = index.toFloat() / count < progress
                drawRoundRect(
                    color = Color.White.copy(alpha = if (played) 1f else 0.45f),
                    topLeft = Offset(index * step, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%d:%02d".format(durationSec / 60, durationSec % 60),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = Color.White,
            maxLines = 1
        )
    }
}
