package com.alo.android

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateMapOf
import android.content.Context
import androidx.compose.material.icons.rounded.VolumeOff

@Composable
internal fun VideoCircleAttachment(
    message: AloChatMessage,
    isOutgoing: Boolean,
    durationLabel: String,
    media: AloMediaItem,
    /** Who sent it: the name on the player bar. */
    title: String = "",
    /** Told when this circle starts (a secret chat marks it). */
    onRequestPlayback: () -> Unit = {},
    onOpenMedia: (AloMediaItem) -> Unit,
    mediaAccessGranted: Boolean = true,
    onRequestMediaAccess: () -> Unit = {}
) {
    val context = LocalContext.current
    var previewAttemptFinished by remember(message.id, message.attachmentUrl) { mutableStateOf(false) }
    // The circle plays in the app-wide player (AloCirclePlayback.kt), so its sound goes on when the chat closes; the
    // bubble shows its picture while it is on screen.
    val playerMounted = AloCirclePlayer.isCurrent(message.id, message.attachmentUrl)
    val isPlaying = playerMounted && AloCirclePlayer.playing
    val playerReady = playerMounted && AloCirclePlayer.ready
    var controlsVisible by remember(message.id, message.attachmentUrl) { mutableStateOf(true) }
    val playerPositionMs = if (playerMounted) AloCirclePlayer.positionMs else 0
    val playerDurationMs = if (playerMounted) AloCirclePlayer.durationMs else 0
    var isScrubbing by remember(message.id, message.attachmentUrl) { mutableStateOf(false) }
    var playAfterMediaAccess by remember(message.id) { mutableStateOf(false) }
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        message.id,
        message.attachmentUrl,
        mediaAccessGranted
    ) {
        value = null
        previewAttemptFinished = false
        if (!mediaAccessGranted) {
            previewAttemptFinished = true
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (message.attachmentUrl.startsWith("http://") || message.attachmentUrl.startsWith("https://")) {
                        retriever.setDataSource(
                            message.attachmentUrl,
                            aloAuthenticatedMediaHeaders(message.attachmentUrl)
                        )
                    } else {
                        retriever.setDataSource(context, Uri.parse(message.attachmentUrl))
                    }
                    retriever.getFrameAtTime(250_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }
        previewAttemptFinished = true
    }

    val progressTrackColor = Color.White.copy(alpha = if (isOutgoing) 0.28f else 0.22f)
    val progressActiveColor = Color(0xFF6DBDFF)
    val ringStroke = 5.dp
    val ringThumbRadius = 4.5.dp
    val circleSize = 224.dp
    val ringInset = 14.dp
    val progress = if (playerDurationMs > 0) {
        (playerPositionMs.toFloat() / playerDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = if (isScrubbing) 0 else 90, easing = LinearOutSlowInEasing),
        label = "videoCircleProgress"
    )
    val showPlayButton = previewAttemptFinished && (!playerMounted || !isPlaying || controlsVisible)
    val playButtonAlpha by animateFloatAsState(
        targetValue = if (showPlayButton) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "videoCirclePlayAlpha"
    )
    val displayedDuration = if (playerDurationMs > 0 && playerMounted) {
        formatPlaybackDuration(playerPositionMs)
    } else {
        durationLabel
    }

    LaunchedEffect(isPlaying, playerReady, playerMounted, isScrubbing) {
        if (playerMounted && isPlaying && playerReady && !isScrubbing) {
            controlsVisible = true
            delay(850)
            if (isPlaying && !isScrubbing) {
                controlsVisible = false
            }
        } else {
            controlsVisible = true
        }
    }
    LaunchedEffect(
        mediaAccessGranted,
        previewAttemptFinished,
        playAfterMediaAccess,
        message.attachmentUrl
    ) {
        if (!mediaAccessGranted || !previewAttemptFinished || !playAfterMediaAccess) {
            return@LaunchedEffect
        }
        playAfterMediaAccess = false
        onRequestPlayback()
        AloCirclePlayer.toggle(context, message.id, message.attachmentUrl, title)
        controlsVisible = true
    }
    fun togglePlayback() {
        if (!mediaAccessGranted) {
            playAfterMediaAccess = true
            onRequestMediaAccess()
            return
        }
        if (previewAttemptFinished) {
            if (!isPlaying) onRequestPlayback()
            AloCirclePlayer.toggle(context, message.id, message.attachmentUrl, title)
            controlsVisible = true
        }
    }
    fun seekFromOffset(offset: Offset, width: Float, height: Float) {
        if (playerMounted && playerDurationMs > 0) {
            val fraction = videoCircleSeekFraction(offset, width, height)
            AloCirclePlayer.seekTo((playerDurationMs * fraction).toInt().coerceIn(0, playerDurationMs))
            controlsVisible = true
        }
    }

    // The circle is clipped round; its length stands outside it, under it on the left (Telegram on iPhone).
    Box(modifier = Modifier.size(circleSize)) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(CircleShape)
            .background(if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming)
            .border(1.dp, if (isOutgoing) AloNativeChatOutgoingBorder else AloNativeChatIncomingBorder, CircleShape)
    ) {
        if (playerMounted) {
            CircleTextureVideoPlayer(modifier = Modifier.matchParentSize())
        } else {
            previewBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = media.alt.ifBlank { media.caption },
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x22000000),
                            Color(0x66000000)
                        )
                    )
                )
        )

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .padding(ringInset)
        ) {
            drawArc(
                color = progressTrackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
            )
            if (animatedProgress > 0f) {
                drawArc(
                    color = progressActiveColor,
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
                )
                val radius = minOf(size.width, size.height) / 2f
                val angle = ((animatedProgress * 360f - 90f) * PI / 180.0).toFloat()
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = progressActiveColor,
                    radius = ringThumbRadius.toPx(),
                    center = Offset(
                        x = center.x + cos(angle) * radius,
                        y = center.y + sin(angle) * radius
                    )
                )
            }
        }

        if (!previewAttemptFinished || (playerMounted && !playerReady)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0x42000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.4.dp,
                    color = Color.White.copy(alpha = 0.92f)
                )
            }
        }

        if (playButtonAlpha > 0f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .graphicsLayer { alpha = playButtonAlpha },
                shape = CircleShape,
                color = Color(0x54000000)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(playerDurationMs, playerReady, playerMounted, previewAttemptFinished, isPlaying) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastPosition = down.position
                        var totalDx = 0f
                        var totalDy = 0f
                        var dragged = false
                        val canScrub = videoCircleRingHitTest(
                            position = down.position,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )
                        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                break
                            }

                            val dx = change.position.x - lastPosition.x
                            val dy = change.position.y - lastPosition.y
                            totalDx += dx
                            totalDy += dy
                            if (canScrub && !dragged && (totalDx * totalDx + totalDy * totalDy) > touchSlopSquared) {
                                dragged = true
                                isScrubbing = true
                                seekFromOffset(change.position, size.width.toFloat(), size.height.toFloat())
                            }
                            if (dragged) {
                                seekFromOffset(change.position, size.width.toFloat(), size.height.toFloat())
                                change.consume()
                            }
                            lastPosition = change.position
                        }

                        if (dragged) {
                            isScrubbing = false
                            if (isPlaying) {
                                controlsVisible = false
                            }
                        } else {
                            togglePlayback()
                        }
                    }
                }
        )

        // As Telegram on iPhone (the owner's choice, 2026-10-29): a crossed-out speaker in a small dark circle at the
        // top middle while the circle is not playing.
        if (!isPlaying) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(30.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.42f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VolumeOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(7.dp)
                )
            }
        }
    }
        // Its length under it on the left, the right edge 56dp left of the centre, «00:03», a dot while unwatched.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width((circleSize / 2 - 56.dp).coerceAtLeast(0.dp))
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = aloCircleClock(displayedDuration),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
                color = AloNativeAppText.copy(alpha = 0.9f),
                maxLines = 1,
                softWrap = false
            )
            if (!isOutgoing && !AloCircleSeen.isSeen(context, message.attachmentUrl)) {
                Box(
                    modifier = Modifier
                        .padding(start = 5.dp)
                        .size(6.dp)
                        .background(AloNativeAppText.copy(alpha = 0.9f), CircleShape)
                )
            }
        }
    }
    LaunchedEffect(isPlaying) {
        if (isPlaying && !isOutgoing) AloCircleSeen.markSeen(context, message.attachmentUrl)
    }
}

/** «0:03» → «00:03», as Telegram on iPhone writes a circle's length. */
internal fun aloCircleClock(label: String): String = label.replace(Regex("^(\\d):"), "0$1:")

/** Circles of other people played on this device: the dot after the length goes (Telegram's «unseen»). */
private object AloCircleSeen {
    private const val PREFS = "alo-circles-seen"
    private val seen = mutableStateMapOf<String, Boolean>()
    private var loaded = false

    fun isSeen(context: Context, key: String): Boolean {
        load(context)
        return seen[key] == true
    }

    fun markSeen(context: Context, key: String) {
        load(context)
        if (key.isBlank() || seen[key] == true) return
        seen[key] = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (prefs.getString("urls", "").orEmpty().split('\n').filter { it.isNotBlank() } + key).takeLast(300)
        prefs.edit().putString("urls", list.joinToString("\n")).apply()
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("urls", "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.forEach { seen[it] = true }
    }
}

/** The picture of the playing circle (AloCirclePlayer): this view lends it a surface while it is on screen. */
@Composable
private fun CircleTextureVideoPlayer(modifier: Modifier = Modifier) {
    val videoWidth = AloCirclePlayer.videoWidth
    val videoHeight = AloCirclePlayer.videoHeight
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }

                val textureView = TextureView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                addView(textureView)
                textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyCircleVideoCrop(textureView, AloCirclePlayer.videoWidth, AloCirclePlayer.videoHeight)
                }

                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        val next = Surface(surfaceTexture)
                        surface = next
                        AloCirclePlayer.attachSurface(next)
                        applyCircleVideoCrop(textureView, AloCirclePlayer.videoWidth, AloCirclePlayer.videoHeight)
                    }

                    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        applyCircleVideoCrop(textureView, AloCirclePlayer.videoWidth, AloCirclePlayer.videoHeight)
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        surface?.let { gone ->
                            AloCirclePlayer.detachSurface(gone)
                            gone.release()
                        }
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                }
            }
        },
        update = { frame ->
            (frame.getChildAt(0) as? TextureView)?.let { applyCircleVideoCrop(it, videoWidth, videoHeight) }
        }
    )
}

private fun applyCircleVideoCrop(textureView: TextureView, videoWidth: Int, videoHeight: Int) {
    val viewWidth = textureView.width
    val viewHeight = textureView.height
    if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

    val baseScaleX = viewWidth.toFloat() / videoWidth.toFloat()
    val baseScaleY = viewHeight.toFloat() / videoHeight.toFloat()
    val fillScale = maxOf(baseScaleX, baseScaleY) * 1.08f
    val matrix = Matrix()
    matrix.setScale(
        fillScale / baseScaleX,
        fillScale / baseScaleY,
        viewWidth / 2f,
        viewHeight / 2f
    )
    textureView.setTransform(matrix)
}

private fun videoCircleSeekFraction(position: Offset, width: Float, height: Float): Float {
    if (width <= 0f || height <= 0f) return 0f
    val centerX = width / 2f
    val centerY = height / 2f
    val angle = atan2(position.y - centerY, position.x - centerX)
    val normalized = ((angle + (PI / 2.0)) / (PI * 2.0)).toFloat()
    return if (normalized < 0f) normalized + 1f else normalized.coerceIn(0f, 1f)
}

private fun videoCircleRingHitTest(position: Offset, width: Float, height: Float): Boolean {
    if (width <= 0f || height <= 0f) return false
    val radius = minOf(width, height) / 2f
    val dx = position.x - width / 2f
    val dy = position.y - height / 2f
    val distanceSquared = dx * dx + dy * dy
    val innerRadius = radius * 0.66f
    val outerRadius = radius * 1.06f
    return distanceSquared in (innerRadius * innerRadius)..(outerRadius * outerRadius)
}
