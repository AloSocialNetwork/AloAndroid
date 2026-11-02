package com.alo.android

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Full-screen video in the media viewer, after Telegram and the iPhone app (AloVideoPlayerView): the picture keeps its
 * proportions, a tap shows or hides the controls (they hide by themselves while it plays), a double tap on a side skips
 * 10 s, the bar shows what is played and what is already downloaded and can be dragged, the speed and the sound are one
 * tap away. A spinner shows while the video loads. It plays once (no loop); at the end the button starts it again.
 */
@Composable
internal fun AloVideoPlayer(
    sourceUrl: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    /** Width / height from the message, so the player has the clip's shape from the first frame (0 = unknown). */
    knownAspect: Float = 0f,
    /** The preview picture shown until the clip draws its first frame (blank = the clip's own first frame). */
    posterUrl: String = ""
) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var aspect by remember { mutableFloatStateOf(if (knownAspect > 0f) knownAspect else 0f) }
    var rendering by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableFloatStateOf(0f) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var skipFlash by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun applySpeed(rate: Float) {
        val mp = player ?: return
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(rate) }
        // Setting the speed resumes a paused MediaPlayer: keep it paused.
        if (!playing) runCatching { mp.pause() }
    }

    fun play() {
        val video = view ?: return
        if (ended) {
            video.seekTo(0)
            ended = false
        }
        video.start()
        if (speed != 1f) applySpeed(speed)
        playing = true
        interaction++
    }

    fun pause() {
        view?.pause()
        playing = false
        interaction++
    }

    fun seekTo(ms: Int) {
        val video = view ?: return
        val target = ms.coerceIn(0, duration.coerceAtLeast(0))
        video.seekTo(target)
        position = target
        if (ended && target < duration - 300) ended = false
        interaction++
    }

    // The page that is not on screen stops.
    LaunchedEffect(active, prepared) {
        if (!prepared) return@LaunchedEffect
        if (active) play() else if (playing) pause()
    }
    // Time and the downloaded part of the bar.
    LaunchedEffect(view, prepared) {
        val video = view ?: return@LaunchedEffect
        while (true) {
            if (prepared) {
                if (scrub == null) position = runCatching { video.currentPosition }.getOrDefault(position)
                loaded = (runCatching { video.bufferPercentage }.getOrDefault(0) / 100f).coerceIn(0f, 1f)
            }
            delay(200)
        }
    }
    // Controls hide 3 s after the last touch while the video plays.
    LaunchedEffect(interaction, playing, scrub) {
        if (!playing || scrub != null) return@LaunchedEffect
        delay(3000)
        showControls = false
    }
    LaunchedEffect(skipFlash) {
        if (skipFlash != null) {
            delay(650)
            skipFlash = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { view?.stopPlayback() } }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val headers = aloAuthenticatedMediaHeaders(sourceUrl)
                    if (headers.isEmpty()) setVideoURI(Uri.parse(sourceUrl)) else setVideoURI(Uri.parse(sourceUrl), headers)
                    setOnPreparedListener { mp ->
                        player = mp
                        mp.isLooping = false
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
                        duration = mp.duration.coerceAtLeast(0)
                        if (muted) mp.setVolume(0f, 0f)
                        mp.setOnInfoListener { _, what, _ ->
                            when (what) {
                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                                MediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                                    buffering = false
                                    rendering = true
                                }
                            }
                            false
                        }
                        buffering = false
                        prepared = true
                    }
                    setOnCompletionListener {
                        playing = false
                        ended = true
                        position = duration
                        showControls = true
                    }
                    setOnErrorListener { _, _, _ ->
                        buffering = false
                        playing = false
                        true
                    }
                    view = this
                }
            },
            // Fits the page both ways (a tall clip is as high as the page, not as wide); an unknown size waits for the
            // file without showing a box of the wrong shape.
            modifier = Modifier
                .aspectRatio(aspect.takeIf { it > 0f }?.coerceIn(0.3f, 3f) ?: (16f / 9f))
                .graphicsLayer { alpha = if (aspect > 0f) 1f else 0f }
        )
        // Until the first frame is drawn: the preview (or the clip's first frame) in the same box, never a black one.
        if (!rendering && aspect > 0f) {
            AloRemoteMediaImage(
                model = posterUrl.ifBlank { sourceUrl },
                contentDescription = null,
                modifier = Modifier.aspectRatio(aspect.coerceIn(0.3f, 3f)),
                contentScale = ContentScale.Fit,
                isVideo = true
            )
        }

        // Tap zones over the whole page: one tap toggles the controls, a double tap on a side skips.
        Row(modifier = Modifier.fillMaxSize()) {
            listOf(-1, 1).forEach { side ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .pointerInput(side, prepared) {
                            detectTapGestures(
                                onTap = {
                                    showControls = !showControls
                                    interaction++
                                },
                                onDoubleTap = {
                                    if (prepared) {
                                        seekTo(position + side * 10_000)
                                        skipFlash = side to ((skipFlash?.second ?: 0) + 1)
                                    }
                                }
                            )
                        }
                )
            }
        }

        skipFlash?.let { (side, _) ->
            Row(
                modifier = Modifier
                    .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(if (side < 0) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(if (side < 0) "−10 с" else "+10 с", color = Color.White, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            }
        }

        if (buffering && !ended) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        }

        AnimatedVisibility(
            visible = showControls && !(buffering && !prepared),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)), CircleShape)
                        .clickable { if (playing) pause() else play() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            ended -> Icons.Rounded.Replay
                            playing -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (playing) "Пауза" else "Воспроизвести",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val shown = scrub?.let { (it * duration).toInt() } ?: position
                    AloVideoScrubber(
                        played = if (duration > 0) shown.toFloat() / duration else 0f,
                        loaded = loaded,
                        dragging = scrub != null,
                        onScrub = { fraction ->
                            scrub = fraction
                            interaction++
                        },
                        onEnd = { fraction ->
                            seekTo((fraction * duration).toInt())
                            scrub = null
                        }
                    )
                    val label = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(aloVideoClock(shown), style = label)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×",
                            style = label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.5f)
                                    speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                                    applySpeed(speed)
                                    interaction++
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        Icon(
                            imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = if (muted) "Включить звук" else "Выключить звук",
                            tint = Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    muted = !muted
                                    val volume = if (muted) 0f else 1f
                                    runCatching { player?.setVolume(volume, volume) }
                                    interaction++
                                }
                                .padding(5.dp)
                        )
                        Text("−" + aloVideoClock((duration - shown).coerceAtLeast(0)), style = label)
                    }
                }
            }
        }
    }
}

/** The seek bar: what is downloaded (lighter), what is played (white), a knob that grows while it is dragged. */
@Composable
private fun AloVideoScrubber(
    played: Float,
    loaded: Float,
    dragging: Boolean,
    onScrub: (Float) -> Unit,
    onEnd: (Float) -> Unit
) {
    var last by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onEnd((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                    onDragEnd = { onEnd(last) },
                    onDragCancel = { onEnd(last) }
                )
            }
    ) {
        val track = 4.dp.toPx()
        val top = (size.height - track) / 2f
        val radius = CornerRadius(track / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, track), radius)
        drawRoundRect(Color.White.copy(alpha = 0.42f), Offset(0f, top), Size(size.width * loaded.coerceIn(0f, 1f), track), radius)
        val x = size.width * played.coerceIn(0f, 1f)
        drawRoundRect(Color.White, Offset(0f, top), Size(x, track), radius)
        drawCircle(Color.White, radius = (if (dragging) 8.dp else 6.dp).toPx(), center = Offset(x, size.height / 2f))
    }
}

internal fun aloVideoClock(ms: Int): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
