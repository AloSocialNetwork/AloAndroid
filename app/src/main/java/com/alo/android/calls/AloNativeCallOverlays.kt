package com.alo.android

import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import livekit.org.webrtc.SurfaceViewRenderer as LiveKitSurfaceViewRenderer
import kotlin.math.PI
import kotlin.math.sin

private val AloCallMicrophoneActive = Color(0xFF25BD5A)
private val AloCallMicrophoneMuted = Color(0xFF087FF5)
private val AloCallInterrupted = Color(0xFF7B35A8)

@Composable
internal fun DirectCallOverlay(
    state: AloDirectCallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onFlipCamera: () -> Unit,
    onSelectCamera: (Boolean) -> Unit,
    onBindLocalRenderer: (AloCallRenderer?) -> Unit,
    onBindRemoteRenderer: (AloCallRenderer?) -> Unit,
    onUnbindLocalRenderer: (AloCallRenderer) -> Unit,
    onUnbindRemoteRenderer: (AloCallRenderer) -> Unit
) {
    var minimizeDragOffset by remember(state.callId) { mutableFloatStateOf(0f) }
    var isLocalVideoPrimary by remember(state.callId) { mutableStateOf(false) }
    var showVideoSourcePicker by remember(state.callId) { mutableStateOf(false) }
    val minimizeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(state.hasRemoteVideo, state.hasLocalVideo) {
        if (!state.hasRemoteVideo || !state.hasLocalVideo) {
            isLocalVideoPrimary = false
        }
    }
    val elapsedSeconds by produceState(
        initialValue = 0L,
        key1 = state.callId,
        key2 = state.phase,
        key3 = state.startedAtMillis
    ) {
        if (state.phase != AloDirectCallPhase.Active || state.startedAtMillis <= 0L) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = ((System.currentTimeMillis() - state.startedAtMillis) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val subtitle = when (state.phase) {
        AloDirectCallPhase.Incoming -> if (state.type == "video") "Входящий видеозвонок" else "Входящий звонок"
        AloDirectCallPhase.Outgoing -> if (state.type == "video") "Вызываем видео..." else "Вызываем..."
        AloDirectCallPhase.Connecting -> if (state.type == "video") "Подключаем видео..." else "Подключаем аудио..."
        AloDirectCallPhase.Reconnecting -> "Восстановление связи..."
        AloDirectCallPhase.Active -> elapsedSeconds.formatCallDuration()
        AloDirectCallPhase.NoAnswer -> "Нет ответа"
        AloDirectCallPhase.Interrupted -> "Связь прервана"
        AloDirectCallPhase.Busy -> "Занято"
        AloDirectCallPhase.Declined -> "Вызов отклонён"
    }
    val isVideoCall = state.hasVideoSurface &&
        state.phase != AloDirectCallPhase.Incoming &&
        !state.phase.isTerminal
    val primaryIsLocal = isVideoCall && isLocalVideoPrimary && state.hasLocalVideo
    val previewIsLocal = !primaryIsLocal
    val showVideoPreview = isVideoCall &&
        if (previewIsLocal) state.hasLocalVideo else state.hasRemoteVideo
    // Where the small video sits: dragged by the finger, then back to a corner (offsets from the top-right corner).
    val previewScope = rememberCoroutineScope()
    val previewOffsetX = remember(state.callId) { Animatable(0f) }
    val previewOffsetY = remember(state.callId) { Animatable(0f) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    val previewMaxX = with(density) { (configuration.screenWidthDp.dp - 112.dp - 36.dp).toPx() }.coerceAtLeast(0f)
    // The bottom corner stays above the call buttons.
    val previewMaxY = with(density) { (configuration.screenHeightDp.dp - 168.dp - 36.dp - 230.dp).toPx() }.coerceAtLeast(0f)
    val palette = directCallPalette(state)
    val onVideoButton: () -> Unit = {
        when {
            state.isScreenSharing -> onToggleScreenShare()
            state.isVideoEnabled -> onToggleVideo()
            else -> showVideoSourcePicker = true
        }
    }
    val backgroundStart by animateColorAsState(
        targetValue = palette.first,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "directCallBackgroundStart"
    )
    val backgroundEnd by animateColorAsState(
        targetValue = palette.second,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "directCallBackgroundEnd"
    )
    val localVideoAlpha by animateFloatAsState(
        targetValue = if (state.isCameraSwitching) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "directCallCameraTransition"
    )
    val localVideoScale by animateFloatAsState(
        targetValue = if (state.isCameraSwitching) 1.015f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "directCallCameraScale"
    )

    Box(
        modifier = Modifier
            .zIndex(20f)
            .fillMaxSize()
            .graphicsLayer { translationY = minimizeDragOffset }
            .pointerInput(state.callId) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        minimizeDragOffset += dragAmount
                    },
                    onDragEnd = {
                        val shouldMinimize = AloCallMinimizePolicy.shouldMinimize(
                            translationPx = minimizeDragOffset,
                            thresholdPx = minimizeThreshold
                        )
                        if (shouldMinimize) {
                            onMinimize()
                        } else {
                            minimizeDragOffset = 0f
                        }
                    },
                    onDragCancel = { minimizeDragOffset = 0f }
                )
            }
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (isVideoCall) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AloCallVideoRenderer(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (primaryIsLocal) localVideoAlpha else 1f
                            if (primaryIsLocal) {
                                scaleX = localVideoScale
                                scaleY = localVideoScale
                            }
                        },
                    rendererKey = "direct-primary-${state.callId}-${if (primaryIsLocal) "local" else "remote"}",
                    onBind = if (primaryIsLocal) onBindLocalRenderer else onBindRemoteRenderer,
                    onUnbind = if (primaryIsLocal) onUnbindLocalRenderer else onUnbindRemoteRenderer
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 46.dp, start = 132.dp, end = 132.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = state.peerName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        DirectCallSafetyBadge(
                            emojis = state.safetyEmojis,
                            isConfirmed = state.isSecurityConfirmed,
                            compact = true
                        )
                    }
                }
                if (showVideoPreview) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 36.dp, end = 18.dp)
                            .offset { IntOffset(previewOffsetX.value.toInt(), previewOffsetY.value.toInt()) }
                            .size(width = 112.dp, height = 168.dp)
                            .pointerInput(state.callId) {
                                // Drag the small video anywhere; on release it settles in the nearest corner (Telegram).
                                detectDragGestures(
                                    onDrag = { change, amount ->
                                        change.consume()
                                        previewScope.launch {
                                            previewOffsetX.snapTo((previewOffsetX.value + amount.x).coerceIn(-previewMaxX, 0f))
                                            previewOffsetY.snapTo((previewOffsetY.value + amount.y).coerceIn(0f, previewMaxY))
                                        }
                                    },
                                    onDragEnd = {
                                        previewScope.launch {
                                            launch { previewOffsetX.animateTo(if (previewOffsetX.value < -previewMaxX / 2) -previewMaxX else 0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
                                            previewOffsetY.animateTo(if (previewOffsetY.value > previewMaxY / 2) previewMaxY else 0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                                        }
                                    }
                                )
                            }
                            .clickable(
                                enabled = state.hasLocalVideo && state.hasRemoteVideo
                            ) {
                                isLocalVideoPrimary = !isLocalVideoPrimary
                            },
                        shape = AloSquircle(24.dp),
                        color = Color.Black.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AloCallVideoRenderer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = if (previewIsLocal) localVideoAlpha else 1f
                                        if (previewIsLocal) {
                                            scaleX = localVideoScale
                                            scaleY = localVideoScale
                                        }
                                    },
                                rendererKey = "direct-preview-${state.callId}-${if (previewIsLocal) "local" else "remote"}",
                                onBind = if (previewIsLocal) onBindLocalRenderer else onBindRemoteRenderer,
                                onUnbind = if (previewIsLocal) onUnbindLocalRenderer else onUnbindRemoteRenderer
                            )
                            // The camera turns from the small picture itself, as in Telegram.
                            if (previewIsLocal && state.isVideoEnabled && !state.isScreenSharing) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .size(36.dp),
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.45f),
                                    onClick = onFlipCamera
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Cameraswitch,
                                            contentDescription = "Перевернуть камеру",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DirectCallControls(
                    state = state,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onAccept = onAccept,
                    onDecline = onDecline,
                    onEnd = onEnd,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onVideo = onVideoButton
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                backgroundStart,
                                backgroundEnd,
                                Color.Black.copy(alpha = 0.76f)
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 104.dp, start = 26.dp, end = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    AvatarBadge(
                        name = state.peerName,
                        avatarUrl = state.peerAvatarUrl,
                        verified = state.verified,
                        size = 124.dp,
                        colorSeed = state.peerId.toString()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = state.peerName,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }

                DirectCallControls(
                    state = state,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onAccept = onAccept,
                    onDecline = onDecline,
                    onEnd = onEnd,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onVideo = onVideoButton
                )
            }
        }
        // Like the iPhone and Telegram: «свернуть» at the top left, the encryption key at the top of the screen
        // (on a video call the key stays under the name, out of the way of the small preview).
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMinimize),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Свернуть звонок",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            if (!isVideoCall) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DirectCallSafetyBadge(
                        emojis = state.safetyEmojis,
                        isConfirmed = state.isSecurityConfirmed
                    )
                }
                Spacer(modifier = Modifier.size(40.dp))
            }
        }
        if (showVideoSourcePicker) {
            AloCallVideoSourcePicker(
                state = state,
                onDismiss = { showVideoSourcePicker = false },
                onSelect = { source ->
                    showVideoSourcePicker = false
                    when (source) {
                        AloCallVideoSource.Screen -> {
                            if (!state.isScreenSharing) onToggleScreenShare()
                        }
                        AloCallVideoSource.FrontCamera,
                        AloCallVideoSource.BackCamera -> {
                            // The camera chosen here is the one that turns on (before, the back camera started as the front one).
                            onSelectCamera(source == AloCallVideoSource.FrontCamera)
                        }
                    }
                }
            )
        }
    }
}

private enum class AloCallVideoSource(val title: String) {
    Screen("Экран"),
    FrontCamera("Передняя"),
    BackCamera("Задняя")
}

@Composable
private fun AloCallVideoSourcePicker(
    state: AloDirectCallUiState,
    onDismiss: () -> Unit,
    onSelect: (AloCallVideoSource) -> Unit
) {
    val sources = AloCallVideoSource.entries
    var selectedIndex by remember(state.callId, state.isScreenSharing, state.isFrontCamera) {
        mutableStateOf(
            when {
                state.isScreenSharing -> 0
                state.isFrontCamera -> 1
                else -> 2
            }
        )
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val panelOffset by animateFloatAsState(
        targetValue = dragY.coerceAtLeast(0f),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "callVideoSourcePanelOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(40f)
            .background(Color.Black.copy(alpha = 0.42f))
            .pointerInput(state.callId) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY = (dragY + amount.y).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        when {
                            dragY > 90f -> onDismiss()
                            dragX < -55f -> selectedIndex = (selectedIndex + 1).coerceAtMost(sources.lastIndex)
                            dragX > 55f -> selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    }
                )
            }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = panelOffset
                    alpha = (1f - panelOffset / 420f).coerceIn(0.35f, 1f)
                },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            color = Color(0xF2181A20),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 42.dp, height = 5.dp)
                        .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(3.dp))
                )
                Text(
                    text = "Источник видео",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sources.forEachIndexed { index, source ->
                        val selected = index == selectedIndex
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1f else 0.94f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "callVideoSourceScale"
                        )
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clickable { selectedIndex = index },
                            shape = AloSquircle(22.dp),
                            color = if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when (source) {
                                        AloCallVideoSource.Screen -> Icons.Rounded.ScreenShare
                                        AloCallVideoSource.FrontCamera -> Icons.Rounded.Videocam
                                        AloCallVideoSource.BackCamera -> Icons.Rounded.Refresh
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(25.dp)
                                )
                                Text(
                                    text = source.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                DirectCallActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Продолжить",
                    accent = AloNativeAppAccent,
                    icon = Icons.Rounded.Videocam,
                    onClick = { onSelect(sources[selectedIndex]) }
                )
            }
        }
    }
}

private fun directCallPalette(state: AloDirectCallUiState): Pair<Color, Color> {
    if (state.phase == AloDirectCallPhase.Reconnecting || state.phase.isTerminal) {
        return AloCallInterrupted to Color(0xFF351450)
    }
    if (state.isMuted) {
        return AloCallMicrophoneMuted to Color(0xFF09295F)
    }
    return AloCallMicrophoneActive to Color(0xFF075B49)
}

@Composable
private fun DirectCallSafetyBadge(
    emojis: List<String>,
    isConfirmed: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (!isConfirmed || emojis.isEmpty()) return
    // As on iPhone and in Telegram: only the four emoji, in an oval of the same glass and height as the round
    // «Свернуть» next to it (the word «Зашифровано» on a dark plate looked foreign on the call's gradient).
    Surface(
        modifier = modifier
            .height(if (compact) 30.dp else 40.dp)
            .semantics { contentDescription = "Код безопасности звонка: ${emojis.joinToString(" ")}" },
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emojis.forEach { emoji ->
                Text(text = emoji, fontSize = if (compact) 15.sp else 20.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun DirectCallCompactBar(
    state: AloDirectCallUiState,
    onExpand: () -> Unit
) {
    val elapsedSeconds by produceState(
        initialValue = 0L,
        key1 = state.callId,
        key2 = state.phase,
        key3 = state.startedAtMillis
    ) {
        if (state.phase != AloDirectCallPhase.Active || state.startedAtMillis <= 0L) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = ((System.currentTimeMillis() - state.startedAtMillis) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val subtitle = when (state.phase) {
        AloDirectCallPhase.Incoming -> "Входящий"
        AloDirectCallPhase.Outgoing -> "Вызов..."
        AloDirectCallPhase.Connecting -> "Соединение..."
        AloDirectCallPhase.Reconnecting -> "Восстановление связи..."
        AloDirectCallPhase.Active -> elapsedSeconds.formatCallDuration()
        AloDirectCallPhase.NoAnswer -> "Нет ответа"
        AloDirectCallPhase.Interrupted -> "Связь прервана"
        AloDirectCallPhase.Busy -> "Занято"
        AloDirectCallPhase.Declined -> "Вызов отклонён"
    }
    val isConnectionInterrupted =
        state.phase == AloDirectCallPhase.Reconnecting || state.phase.isTerminal
    val isMicrophoneActive = !state.isMuted && !isConnectionInterrupted

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onExpand)
    ) {
        AloCallStatusBackground(
            phase = state.phase,
            isMuted = state.isMuted,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.peerName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.phase == AloDirectCallPhase.Active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = state.isMuted,
                            transitionSpec = {
                                (fadeIn(tween(150)) + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut(tween(100)) + slideOutVertically { -it / 3 })
                            },
                            label = "directCallMicrophoneStatus"
                        ) { muted ->
                            Text(
                                text = if (muted) "Микрофон выключен" else "Микрофон включён",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1
                            )
                        }
                        Text(
                            text = " · $subtitle",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector = if (state.hasVideoSurface) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
                    .padding(5.dp)
            )
        }
    }
}

/** Telegram's voice-chat colours (iOS's AloGroupCallTheme). */
private object AloGroupCallTheme {
    val background = Color(0xFF121217)
    val panel = Color(0xFF24242B)
    val live = Color(0xFF33CC78)
    val hand = Color(0xFF5C9EFF)
    val recording = Color(0xFFFF4545)
    /** VideoChatActionButtonComponent: the idle button, an active one while muted / live / asking
     * to speak, and the dark red of Leave. */
    val control = Color(0xFF25272E).copy(alpha = 0.72f)
    val controlMuted = Color(0xFF21477D)
    val controlLive = Color(0xFF34C659)
    val controlHand = Color(0xFF3252EF)
    val leave = Color(0xFF330D0B)
    val muted = listOf(Color(0xFF3B8CF5), Color(0xFF5959D9))
    val speaking = listOf(Color(0xFF33CC78), Color(0xFF30B0C7))
    val raiseHand = listOf(Color(0xFF9C59F5), Color(0xFFF56B9C))
}

private fun groupCallParticipantsLabel(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "участник"
        mod10 in 2..4 && mod100 !in 12..14 -> "участника"
        else -> "участников"
    }
    return "$count $word"
}

private fun groupCallClock(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return if (total >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
}

/**
 * Telegram's video chat (iOS's AloGroupCallOverlay): minimise, the title with the count and the
 * options menu; the recording banner; everyone listed as speaking, listening or wanting to speak;
 * video and speaker on the left, the big microphone in the middle, leave on the right.
 */
@Composable
internal fun GroupCallOverlay(
    state: AloGroupCallUiState,
    onMinimize: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSelectCamera: (Boolean) -> Unit,
    onBindLocalRenderer: (AloCallRenderer?) -> Unit,
    onBindRemoteRenderer: (AloCallRenderer?) -> Unit,
    onUnbindLocalRenderer: (AloCallRenderer) -> Unit,
    onUnbindRemoteRenderer: (AloCallRenderer) -> Unit,
    onBindSfuRenderer: (Int, LiveKitSurfaceViewRenderer?) -> Unit,
    onUnbindSfuRenderer: (Int, LiveKitSurfaceViewRenderer) -> Unit,
    onLeave: () -> Unit,
    onSetMicrophone: (Int, Boolean) -> Unit = { _, _ -> },
    onMuteEveryone: (Boolean) -> Unit = {},
    onToggleRecording: () -> Unit = {}
) {
    var minimizeDragOffset by remember(state.callId) { mutableFloatStateOf(0f) }
    var showCameraSourcePicker by remember(state.callId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val minimizeThreshold = with(LocalDensity.current) { 90.dp.toPx() }
    val showsVideo = state.type == "video" &&
        (state.usesSfu || state.participants.size <= 2) &&
        (state.isVideoEnabled || state.videoParticipantIds.isNotEmpty())
    val controls = state.controls

    Box(
        modifier = Modifier
            .zIndex(20f)
            .fillMaxSize()
            .graphicsLayer { translationY = minimizeDragOffset.coerceAtLeast(0f) }
            .pointerInput(state.callId) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        minimizeDragOffset = (minimizeDragOffset + dragAmount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        if (AloCallMinimizePolicy.shouldMinimize(translationPx = minimizeDragOffset, thresholdPx = minimizeThreshold)) onMinimize()
                        minimizeDragOffset = 0f
                    },
                    onDragCancel = { minimizeDragOffset = 0f }
                )
            }
            .background(AloGroupCallTheme.background)
    ) {
        if (showsVideo) {
            if (state.usesSfu) {
                AloGroupSfuVideoGrid(state = state, onBind = onBindSfuRenderer, onUnbind = onUnbindSfuRenderer, modifier = Modifier.fillMaxSize())
            } else {
                AloCallVideoRenderer(
                    modifier = Modifier.fillMaxSize(), rendererKey = "group-remote-${state.callId}",
                    onBind = onBindRemoteRenderer, onUnbind = onUnbindRemoteRenderer
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, Color.Black.copy(alpha = 0.62f)))
                )
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Minimise, the title with the count, and the options menu.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AloGroupCallHeaderButton(Icons.Rounded.KeyboardArrowDown, "Свернуть", onMinimize)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (controls.recording != null) Box(Modifier.size(8.dp).clip(CircleShape).background(AloGroupCallTheme.recording))
                        Text(state.roomTitle.ifBlank { "Видеочат" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(groupCallParticipantsLabel(state.participants.size), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                Box {
                    AloGroupCallHeaderButton(Icons.Rounded.MoreHoriz, "Настройки видеочата") { menuOpen = true }
                    AloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (controls.canManage) {
                            Text("Администратор", color = AloNativeAppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            DropdownMenuItem(
                                text = { Text(if (controls.joinMuted) "Разрешить всем говорить" else "Выключить микрофон всем") },
                                leadingIcon = { Icon(if (controls.joinMuted) Icons.Rounded.Mic else Icons.Rounded.MicOff, null) },
                                onClick = { menuOpen = false; onMuteEveryone(!controls.joinMuted) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.isRecordingHere) "Остановить запись" else "Начать запись",
                                        color = if (state.isRecordingHere) Color(0xFFFF453A) else Color.Unspecified)
                                },
                                leadingIcon = { Icon(if (state.isRecordingHere) Icons.Rounded.StopCircle else Icons.Rounded.FiberManualRecord, null) },
                                enabled = controls.recording == null || state.isRecordingHere,
                                onClick = { menuOpen = false; onToggleRecording() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Свернуть") },
                            leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
                            onClick = { menuOpen = false; onMinimize() }
                        )
                    }
                }
            }

            controls.recording?.let { recording ->
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(recording.startedAtMillis) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
                val elapsed = (now - recording.startedAtMillis) / 1_000
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(AloGroupCallTheme.recording.copy(alpha = 0.22f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(AloGroupCallTheme.recording.copy(alpha = if (elapsed % 2 == 0L) 1f else 0.35f)))
                    Text(
                        if (state.isRecordingHere) "Запись · ${groupCallClock(elapsed)}" else "Идёт запись видеочата",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (showsVideo) {
                Spacer(Modifier.weight(1f))
            } else {
                // The panel is as tall as its rows, scrolling only when they don't fit.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 14.dp)) {
                    AloGroupCallParticipantList(state = state, onSetMicrophone = onSetMicrophone, modifier = Modifier.fillMaxWidth())
                }
            }

            if (state.isVideoEnabled && !state.usesSfu) {
                Box(
                    Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .size(112.dp, 158.dp)
                        .clip(AloSquircle(18.dp))
                        .background(Color.Black.copy(alpha = 0.28f))
                ) {
                    AloCallVideoRenderer(
                        modifier = Modifier.fillMaxSize(), rendererKey = "group-local-${state.callId}",
                        onBind = onBindLocalRenderer, onUnbind = onUnbindLocalRenderer
                    )
                }
            }

            // Telegram's video-chat controls (VideoChatScreen): one row of equal 56dp round buttons —
            // speaker, video, microphone, leave — centred up to 33dp apart, each titled underneath,
            // the circles 48dp above the bottom.
            val mic = aloGroupCallMicState(state)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 26.dp)) {
            // Telegram: min(33, the room left over / the gaps).
            val gap = ((maxWidth - 56.dp * 4) / 3).coerceIn(8.dp, 33.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top
            ) {
                AloGroupCallActionButton(
                    "Динамик", if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                    background = aloGroupCallActiveColor(state.isSpeakerOn, mic.kind),
                    description = if (state.isSpeakerOn) "Динамик включён" else "Динамик выключен",
                    onClick = onToggleSpeaker
                )
                AloGroupCallActionButton(
                    "Видео", if (state.isVideoEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                    background = aloGroupCallActiveColor(state.isVideoEnabled, mic.kind),
                    loading = state.isCameraSwitching
                ) {
                    if (state.isVideoEnabled) onToggleVideo() else showCameraSourcePicker = true
                }
                AloGroupCallMicrophone(state, mic, onToggleMute)
                AloGroupCallActionButton("Выйти", Icons.Rounded.Close, background = AloGroupCallTheme.leave, onClick = onLeave)
            }
            }
        }
        if (showCameraSourcePicker) {
            AloGroupCameraSourcePicker(
                isFrontCamera = state.isFrontCamera,
                onDismiss = { showCameraSourcePicker = false },
                onSelect = { frontCamera ->
                    showCameraSourcePicker = false
                    onSelectCamera(frontCamera)
                }
            )
        }
    }
}

@Composable
private fun AloGroupCallHeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Everyone in the call as Telegram lists them: raised hands first (oldest first), then in join order. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloGroupCallParticipantList(
    state: AloGroupCallUiState,
    onSetMicrophone: (Int, Boolean) -> Unit,
    modifier: Modifier
) {
    val raised = state.controls.raisedHands
    val ordered = state.participants.sortedWith(compareBy { raised[it.id] ?: Long.MAX_VALUE })
    var menuFor by remember { mutableStateOf<Int?>(null) }
    LazyColumn(modifier.clip(AloSquircle(16.dp)).background(AloGroupCallTheme.panel)) {
        items(ordered, key = { it.id }) { participant ->
            val isMe = participant.id == state.localUserId
            val speaking = participant.id in state.speakingParticipantIds && !(isMe && state.isMuted)
            val muted = if (isMe) state.isMuted else participant.id in state.mutedParticipantIds
            val byAdmin = participant.id in state.controls.mutedByAdmin
            val hand = participant.id in raised
            val (status, statusColor) = when {
                hand -> "хочет говорить" to AloGroupCallTheme.hand
                speaking -> "говорит" to AloGroupCallTheme.live
                byAdmin -> "микрофон выключен" to Color.White.copy(alpha = 0.5f)
                else -> "слушает" to Color.White.copy(alpha = 0.5f)
            }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { if (state.controls.canManage && !isMe) menuFor = participant.id })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(50.dp).border(if (speaking) 2.5.dp else 0.dp, if (speaking) AloGroupCallTheme.live else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarBadge(name = participant.name, avatarUrl = participant.avatarUrl, verified = false, size = 44.dp, colorSeed = participant.id.toString())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (isMe) "${participant.name} (вы)" else participant.name, color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (hand) Icon(Icons.Rounded.PanTool, null, tint = statusColor, modifier = Modifier.size(12.dp))
                            Text(status, color = statusColor, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    Icon(
                        when { speaking -> Icons.Rounded.Mic; muted || byAdmin -> Icons.Rounded.MicOff; else -> Icons.Rounded.MicNone },
                        null,
                        tint = when { speaking -> AloGroupCallTheme.live; byAdmin -> AloGroupCallTheme.recording; else -> Color.White.copy(alpha = 0.55f) },
                        modifier = Modifier.size(22.dp)
                    )
                }
                AloDropdownMenu(expanded = menuFor == participant.id, onDismissRequest = { menuFor = null }) {
                    if (byAdmin || hand) {
                        DropdownMenuItem(text = { Text("Разрешить говорить") }, leadingIcon = { Icon(Icons.Rounded.Mic, null) },
                            onClick = { menuFor = null; onSetMicrophone(participant.id, true) })
                    }
                    if (!byAdmin) {
                        DropdownMenuItem(text = { Text("Выключить микрофон", color = Color(0xFFFF453A)) }, leadingIcon = { Icon(Icons.Rounded.MicOff, null) },
                            onClick = { menuFor = null; onSetMicrophone(participant.id, false) })
                    }
                }
            }
            if (participant.id != ordered.last().id) {
                Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
            }
        }
    }
}

/** What the microphone button shows: muted, live, or (muted by an admin) raise / lower your hand. */
private enum class AloGroupMicKind { Muted, Live, RaiseHand }

private class AloGroupMicState(
    val kind: AloGroupMicKind,
    val colors: List<Color>,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    /** The word under Telegram's round microphone button. */
    val shortTitle: String
)

private fun aloGroupCallMicState(state: AloGroupCallUiState): AloGroupMicState {
    val mutedByAdmin = state.localUserId in state.controls.mutedByAdmin && !state.controls.canManage
    val handRaised = state.localUserId in state.controls.raisedHands
    return when {
        mutedByAdmin && state.isMuted && handRaised -> AloGroupMicState(
            AloGroupMicKind.RaiseHand, AloGroupCallTheme.raiseHand, Icons.Rounded.PanTool,
            "Вы подняли руку", "Ждём разрешения администратора", "Рука поднята"
        )
        mutedByAdmin && state.isMuted -> AloGroupMicState(
            AloGroupMicKind.RaiseHand, AloGroupCallTheme.raiseHand, Icons.Outlined.PanTool,
            "Поднять руку", "Микрофон выключен администратором", "Поднять руку"
        )
        state.isMuted -> AloGroupMicState(
            AloGroupMicKind.Muted, AloGroupCallTheme.muted, Icons.Rounded.MicOff,
            "Микрофон выключен", "Нажмите, чтобы говорить", "Включить"
        )
        else -> AloGroupMicState(
            AloGroupMicKind.Live, AloGroupCallTheme.speaking, Icons.Rounded.Mic,
            "Вы в эфире", "Нажмите, чтобы выключить микрофон", "Выключить"
        )
    }
}

/** Telegram's colours for an action button that is on: blue while muted, green while live. */
private fun aloGroupCallActiveColor(isOn: Boolean, kind: AloGroupMicKind): Color = when {
    !isOn -> AloGroupCallTheme.control
    kind == AloGroupMicKind.Live -> AloGroupCallTheme.controlLive
    kind == AloGroupMicKind.RaiseHand -> AloGroupCallTheme.controlHand
    else -> AloGroupCallTheme.controlMuted
}

@Composable
private fun AloGroupCallMicrophone(state: AloGroupCallUiState, mic: AloGroupMicState, onToggleMute: () -> Unit) {
    val speaking = state.localUserId in state.speakingParticipantIds && !state.isMuted
    // While you speak the button breathes, as Telegram's blob does.
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val haloScale by pulse.animateFloat(
        initialValue = 1f, targetValue = if (speaking) 1.3f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "halo"
    )
    AloGroupCallButtonColumn(mic.shortTitle, Modifier.semantics { contentDescription = mic.title }, onToggleMute) {
        Box(Modifier.size(56.dp).graphicsLayer { scaleX = haloScale; scaleY = haloScale; alpha = 0.3f }.clip(CircleShape).background(Brush.linearGradient(mic.colors)))
        Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(mic.colors)), contentAlignment = Alignment.Center) {
            Icon(mic.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun AloGroupCallActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    loading: Boolean = false,
    description: String = title,
    onClick: () -> Unit
) {
    AloGroupCallButtonColumn(title, Modifier.semantics { contentDescription = description }, onClick) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** A 56dp round button with its 13sp title 5dp below; a long title may run past the circle. */
@Composable
private fun AloGroupCallButtonColumn(
    title: String,
    modifier: Modifier,
    onClick: () -> Unit,
    circle: @Composable BoxScope.() -> Unit
) {
    Column(
        Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center, content = circle)
        Text(
            title, color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
        )
    }
}

@Composable
private fun AloGroupCameraSourcePicker(
    isFrontCamera: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Boolean) -> Unit
) {
    val sources = listOf(true to "Передняя", false to "Задняя")
    var selectedIndex by remember(isFrontCamera) {
        mutableStateOf(if (isFrontCamera) 0 else 1)
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val panelOffset by animateFloatAsState(
        targetValue = dragY.coerceAtLeast(0f),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "groupCameraSourcePanelOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(40f)
            .background(Color.Black.copy(alpha = 0.42f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY = (dragY + amount.y).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        when {
                            dragY > 90f -> onDismiss()
                            dragX < -55f -> selectedIndex = (selectedIndex + 1).coerceAtMost(sources.lastIndex)
                            dragX > 55f -> selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    }
                )
            }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = panelOffset
                    alpha = (1f - panelOffset / 420f).coerceIn(0.35f, 1f)
                },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            color = Color(0xF2181A20),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 42.dp, height = 5.dp)
                        .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(3.dp))
                )
                Text(
                    text = "Выберите камеру",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sources.forEachIndexed { index, source ->
                        val selected = index == selectedIndex
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1f else 0.94f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "groupCameraSourceScale"
                        )
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clickable { selectedIndex = index },
                            shape = AloSquircle(22.dp),
                            color = if (selected) {
                                Color.White.copy(alpha = 0.16f)
                            } else {
                                Color.White.copy(alpha = 0.07f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) {
                                    Color.White.copy(alpha = 0.28f)
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (source.first) {
                                        Icons.Rounded.Videocam
                                    } else {
                                        Icons.Rounded.Refresh
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(25.dp)
                                )
                                Text(
                                    text = source.second,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                DirectCallActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    label = "Продолжить",
                    accent = AloNativeAppAccent,
                    icon = Icons.Rounded.Videocam,
                    onClick = { onSelect(sources[selectedIndex].first) }
                )
            }
        }
    }
}

@Composable
internal fun GroupCallCompactBar(
    state: AloGroupCallUiState,
    onExpand: () -> Unit
) {
    val isMicrophoneActive = !state.isMuted

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onExpand)
    ) {
        AloCallStatusBackground(
            phase = AloDirectCallPhase.Active,
            isMuted = !isMicrophoneActive,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.roomTitle.ifBlank { "Групповой звонок" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = state.isMuted,
                        transitionSpec = {
                            (fadeIn(tween(150)) + slideInVertically { it / 3 }) togetherWith
                                (fadeOut(tween(100)) + slideOutVertically { -it / 3 })
                        },
                        label = "groupCallMicrophoneStatus"
                    ) { muted ->
                        Text(
                            text = if (muted) "Микрофон выключен" else "Микрофон включён",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                    Text(
                        text = " · ${state.participants.size} уч.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector = if (state.type == "video") Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
                    .padding(5.dp)
            )
        }
    }
}

@Composable
private fun AloCallStatusBackground(
    phase: AloDirectCallPhase,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val isConnectionInterrupted = phase == AloDirectCallPhase.Reconnecting || phase.isTerminal
    val isMicrophoneActive = !isMuted && !isConnectionInterrupted
    val startColor by animateColorAsState(
        targetValue = when {
            isConnectionInterrupted -> Color(0xFF8A3DB8)
            isMicrophoneActive -> Color(0xFF21C15C)
            else -> Color(0xFF007FFF)
        },
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "callStatusStartColor"
    )
    val endColor by animateColorAsState(
        targetValue = when {
            isConnectionInterrupted -> Color(0xFF552080)
            isMicrophoneActive -> Color(0xFF079A78)
            else -> Color(0xFF00AFE8)
        },
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "callStatusEndColor"
    )
    val transition = rememberInfiniteTransition(label = "callStatusWave")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "callStatusWavePhase"
    )

    Canvas(modifier = modifier) {
        drawRect(brush = Brush.horizontalGradient(listOf(startColor, endColor)))
        if (!isMicrophoneActive) return@Canvas

        repeat(2) { layer ->
            val baseline = size.height * if (layer == 0) 0.58f else 0.72f
            val amplitude = size.height * if (layer == 0) 0.17f else 0.10f
            val frequency = if (layer == 0) 2.1f else 2.8f
            val path = Path().apply {
                moveTo(0f, size.height)
                for (step in 0..48) {
                    val progress = step / 48f
                    val x = size.width * progress
                    val y = baseline + sin(
                        progress * (2f * PI.toFloat()) * frequency
                            + wavePhase
                            + layer.toFloat()
                    ) * amplitude
                    lineTo(x, y)
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = if (layer == 0) 0.11f else 0.07f)
            )
        }
    }
}

@Composable
private fun AloCallVideoRenderer(
    modifier: Modifier,
    rendererKey: String,
    onBind: (AloCallRenderer?) -> Unit,
    onUnbind: (AloCallRenderer) -> Unit
) {
    val context = LocalContext.current
    val renderer = remember(rendererKey) {
        AloCallRenderer(context)
    }
    AndroidView(
        modifier = modifier,
        factory = { renderer },
        update = {}
    )
    DisposableEffect(renderer) {
        onBind(renderer)
        onDispose {
            onUnbind(renderer)
        }
    }
}

@Composable
private fun AloGroupSfuVideoGrid(
    state: AloGroupCallUiState,
    onBind: (Int, LiveKitSurfaceViewRenderer?) -> Unit,
    onUnbind: (Int, LiveKitSurfaceViewRenderer) -> Unit,
    modifier: Modifier = Modifier
) {
    val videoIds = state.videoParticipantIds.toList().sorted()
    if (videoIds.size == 1) {
        AloGroupSfuVideoTile(
            userId = videoIds.first(),
            participant = state.participants.firstOrNull { it.id == videoIds.first() },
            isSpeaking = videoIds.first() in state.speakingParticipantIds,
            onBind = onBind,
            onUnbind = onUnbind,
            modifier = modifier
        )
        return
    }

    val columns = when {
        videoIds.size <= 4 -> 2
        else -> 3
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        gridItems(videoIds, key = { it }) { userId ->
            AloGroupSfuVideoTile(
                userId = userId,
                participant = state.participants.firstOrNull { it.id == userId },
                isSpeaking = userId in state.speakingParticipantIds,
                onBind = onBind,
                onUnbind = onUnbind,
                modifier = Modifier.aspectRatio(if (videoIds.size <= 4) 0.82f else 1f)
            )
        }
    }
}

@Composable
private fun AloGroupSfuVideoTile(
    userId: Int,
    participant: AloEntitySummary?,
    isSpeaking: Boolean,
    onBind: (Int, LiveKitSurfaceViewRenderer?) -> Unit,
    onUnbind: (Int, LiveKitSurfaceViewRenderer) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSpeaking) Color(0xFF45D483) else Color.Transparent
    Box(
        modifier = modifier
            .background(Color(0xFF101217))
            .border(2.dp, borderColor)
    ) {
        AloGroupSfuVideoRenderer(
            userId = userId,
            onBind = onBind,
            onUnbind = onUnbind,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = participant?.name?.ifBlank { "Участник" } ?: "Участник",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.42f), AloSquircle(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AloGroupSfuVideoRenderer(
    userId: Int,
    onBind: (Int, LiveKitSurfaceViewRenderer?) -> Unit,
    onUnbind: (Int, LiveKitSurfaceViewRenderer) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(userId) {
        LiveKitSurfaceViewRenderer(context)
    }
    AndroidView(
        modifier = modifier,
        factory = { renderer },
        update = {}
    )
    DisposableEffect(userId, renderer) {
        onBind(userId, renderer)
        onDispose {
            onUnbind(userId, renderer)
        }
    }
}

@Composable
internal fun CallLogAttachment(
    meta: AloCallLogMeta,
    isOutgoing: Boolean,
    message: AloChatMessage
) {
    val icon = if (meta.callType == "video") Icons.Rounded.Videocam else Icons.Rounded.Call
    val isGroupCall = message.roomId > 0 || meta.scope == "group"
    val title = when (meta.status) {
        "missed" -> if (isOutgoing) "Звонок без ответа" else "Пропущенный звонок"
        "declined" -> if (isOutgoing) "Звонок отклонён" else "Отклонённый звонок"
        else -> if (isOutgoing) {
            when {
                isGroupCall && meta.callType == "video" -> "Групповой видеозвонок"
                isGroupCall -> "Групповой звонок"
                meta.callType == "video" -> "Исходящий видеозвонок"
                else -> "Исходящий звонок"
            }
        } else {
            when {
                isGroupCall && meta.callType == "video" -> "Групповой видеозвонок"
                isGroupCall -> "Групповой звонок"
                meta.callType == "video" -> "Входящий видеозвонок"
                else -> "Входящий звонок"
            }
        }
    }
    // Colours follow the bubble, not the direction: in the light theme an outgoing bubble is pale too, and the white title
    // on it could not be read (the same rule as the voice message).
    val lightBubble = (if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming).luminance() > 0.5f
    val accent = when (meta.status) {
        "missed", "declined" -> Color(0xFFFF5A5A)
        else -> if (lightBubble) AloNativeChatAccentBlue else Color.White.copy(alpha = 0.92f)
    }
    val titleColor = if (lightBubble) AloNativeAppText else Color.White
    val metaColor = if (lightBubble) AloNativeAppMuted else Color.White.copy(alpha = 0.72f)
    val duration = if (meta.durationSec > 0) meta.durationSec.toLong().formatCallDuration() else ""
    // Like the iPhone and the website: the title takes the first line; the duration and the time share the second.
    Row(
        modifier = Modifier.widthIn(min = 176.dp, max = 250.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (lightBubble) 0.12f else 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(9.dp))
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // softWrap = false: the bubble sizes itself by its smallest width, and a wrapping title offered only its
            // longest word («Исходящий видеозвон…» was cut); unwrapped, the whole title sets the bubble's width.
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (duration.isNotEmpty()) {
                    Text(text = duration, style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
                Spacer(modifier = Modifier.weight(1f).widthIn(min = 12.dp))
                Text(text = formatMessageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
        }
    }
}

@Composable
private fun DirectCallActionButton(
    modifier: Modifier = Modifier,
    label: String,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(AloSquircle(22.dp))
            .clickable(onClick = onClick),
        shape = AloSquircle(22.dp),
        color = accent.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
        }
    }
}

@Composable
private fun DirectCallToggleButton(
    modifier: Modifier = Modifier,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    activeAccent: Color = AloNativeAppAccent,
    inactiveAccent: Color = AloNativeAppMuted,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val accent by animateColorAsState(
        targetValue = if (active) activeAccent else inactiveAccent,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "callToggleAccent"
    )
    Surface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.58f)
            .clip(AloSquircle(22.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = AloSquircle(22.dp),
        color = AloNativeAppSurfaceRaised.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
        }
    }
}

@Composable
private fun DirectCallRoundControl(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    selected: Boolean = false,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    diameter: androidx.compose.ui.unit.Dp = 58.dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(
            durationMillis = if (isPressed) 80 else 180,
            easing = FastOutSlowInEasing
        ),
        label = "callRoundControlScale"
    )
    val fillColor by animateColorAsState(
        targetValue = when {
            destructive -> Color(0xFFFF3B30)
            primary -> AloCallMicrophoneActive
            selected -> Color.White.copy(alpha = 0.96f)
            else -> Color.White.copy(alpha = 0.18f)
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "callRoundControlFill"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected && !destructive && !primary) Color(0xFF111216) else Color.White,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "callRoundControlIcon"
    )

    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.52f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(diameter)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = CircleShape,
            color = fillColor,
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0.22f else 0.10f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(if (diameter >= 64.dp) 27.dp else 24.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The call buttons, the same four as on the iPhone (and in Telegram): speaker, video, microphone and the red receiver.
 * An incoming call has «Отклонить» and «Ответить». While the microphone is off a note says so above the buttons.
 */
@Composable
private fun DirectCallControls(
    state: AloDirectCallUiState,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onVideo: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (state.isMuted && state.phase != AloDirectCallPhase.Incoming && !state.phase.isTerminal) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Вы выключили микрофон",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }
        if (state.phase == AloDirectCallPhase.Incoming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DirectCallRoundControl(
                    label = "Отклонить",
                    icon = Icons.Rounded.CallEnd,
                    destructive = true,
                    diameter = 68.dp,
                    onClick = onDecline
                )
                DirectCallRoundControl(
                    label = "Ответить",
                    icon = if (state.type == "video") Icons.Rounded.Videocam else Icons.Rounded.Call,
                    primary = true,
                    diameter = 68.dp,
                    onClick = onAccept
                )
            }
        } else if (!state.phase.isTerminal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                DirectCallRoundControl(
                    modifier = Modifier.weight(1f),
                    // Which way the sound goes now, as on the iPhone: the loudspeaker or the phone's earpiece.
                    label = if (state.isSpeakerOn) "Динамик" else "Телефон",
                    icon = if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.Call,
                    selected = state.isSpeakerOn,
                    onClick = onToggleSpeaker
                )
                DirectCallRoundControl(
                    modifier = Modifier.weight(1f),
                    label = if (state.isScreenSharing) "Экран" else "Видео",
                    icon = when {
                        state.isScreenSharing -> Icons.Rounded.ScreenShare
                        state.isVideoEnabled -> Icons.Rounded.Videocam
                        else -> Icons.Rounded.VideocamOff
                    },
                    selected = state.isVideoEnabled || state.isScreenSharing,
                    enabled = !state.isCameraSwitching && !state.isScreenShareStarting,
                    onClick = onVideo
                )
                DirectCallRoundControl(
                    modifier = Modifier.weight(1f),
                    label = if (state.isMuted) "Включить звук" else "Убрать звук",
                    icon = if (state.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    selected = state.isMuted,
                    onClick = onToggleMute
                )
                DirectCallRoundControl(
                    modifier = Modifier.weight(1f),
                    label = if (state.phase == AloDirectCallPhase.Outgoing) "Отменить" else "Завершить",
                    icon = Icons.Rounded.CallEnd,
                    destructive = true,
                    onClick = onEnd
                )
            }
        }
    }
}

internal fun Long.formatCallDuration(): String {
    val minutes = this / 60L
    val seconds = this % 60L
    return "%d:%02d".format(minutes, seconds)
}
