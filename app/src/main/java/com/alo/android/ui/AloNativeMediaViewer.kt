package com.alo.android

import android.net.Uri
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Keeps the window out of screenshots and screen recordings while [secure] holds. Holders are counted,
 * so a media viewer closing inside a protected chat leaves the chat protected.
 */
@Composable
internal fun AloSecureWindowWhile(secure: Boolean) {
    val context = LocalContext.current
    DisposableEffect(secure) {
        // A debug build on the cross-client stand may allow screenshots (AloDebugServer.allowScreenshots).
        val window = if (secure && !(BuildConfig.DEBUG && AloDebugServer.allowScreenshots)) context.findActivity()?.window else null
        if (window != null) {
            if (secureHolders++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null && --secureHolders == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private var secureHolders = 0

/**
 * Telegram's zoom of a photo in the viewer: two fingers pinch around the point between them (and move it), a double
 * tap zooms in where it was tapped and back out, a zoomed photo is dragged with one finger and cannot leave the screen;
 * while it is zoomed the pages do not turn and a drag down does not close the viewer. One finger on a photo that is not
 * zoomed is left to the pager and to the drag down.
 */
private class AloPhotoZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    /** Width / height of the decoded picture (it is letterboxed inside the page). */
    var pictureAspect by mutableStateOf<Float?>(null)
    private var settling: Job? = null
    val zoomed: Boolean get() = scale > 1.01f

    private fun bounds(size: IntSize, scale: Float): Offset {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val aspect = pictureAspect ?: (width / height.coerceAtLeast(1f))
        val pictureWidth = minOf(width, height * aspect)
        val pictureHeight = minOf(height, width / aspect)
        return Offset(((pictureWidth * scale - width) / 2f).coerceAtLeast(0f), ((pictureHeight * scale - height) / 2f).coerceAtLeast(0f))
    }

    private fun clamped(size: IntSize, scale: Float, offset: Offset): Offset {
        val limit = bounds(size, scale)
        return Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y))
    }

    /** The point under `centroid` stays under the fingers while the scale changes by `zoomChange`; `pan` moves it. */
    fun transform(size: IntSize, centroid: Offset, zoomChange: Float, pan: Offset) {
        settling?.cancel()
        val center = Offset(size.width / 2f, size.height / 2f)
        val next = (scale * zoomChange).coerceIn(0.7f, MAX_SCALE * 1.5f)
        val point = (centroid - center - offset) / scale
        scale = next
        offset = centroid - center - point * next + pan
        // Inside the edges while dragging; a pinch may go a little past them and springs back on release.
        if (zoomChange == 1f) offset = clamped(size, scale, offset)
    }

    private fun animateTo(scope: CoroutineScope, targetScale: Float, targetOffset: Offset) {
        settling?.cancel()
        val fromScale = scale
        val fromOffset = offset
        settling = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)) { f, _ ->
                scale = fromScale + (targetScale - fromScale) * f
                offset = fromOffset + (targetOffset - fromOffset) * f
            }
        }
    }

    fun settle(scope: CoroutineScope, size: IntSize) {
        if (scale <= 1.02f) return animateTo(scope, 1f, Offset.Zero)
        val target = scale.coerceAtMost(MAX_SCALE)
        // The point between the fingers keeps its place when a pinch past the limit springs back.
        animateTo(scope, target, clamped(size, target, offset * (target / scale)))
    }

    fun toggle(scope: CoroutineScope, size: IntSize, tap: Offset) {
        if (zoomed) return animateTo(scope, 1f, Offset.Zero)
        val center = Offset(size.width / 2f, size.height / 2f)
        animateTo(scope, DOUBLE_TAP_SCALE, clamped(size, DOUBLE_TAP_SCALE, (center - tap) * (DOUBLE_TAP_SCALE - 1f)))
    }

    companion object {
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

private fun Modifier.aloPhotoZoom(zoom: AloPhotoZoom, scope: CoroutineScope): Modifier = this
    .pointerInput(zoom) {
        detectTapGestures(onDoubleTap = { tap -> zoom.toggle(scope, size, tap) })
    }
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var moved = false
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || zoom.zoomed) {
                    val zoomChange = if (fingers >= 2) event.calculateZoom() else 1f
                    val pan = event.calculatePan()
                    if (zoomChange != 1f || pan != Offset.Zero) {
                        zoom.transform(size, event.calculateCentroid(useCurrent = true), zoomChange, pan)
                        moved = true
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (moved) zoom.settle(scope, size)
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offset.x
        translationY = zoom.offset.y
    }

@Composable
internal fun MediaViewerOverlay(
    viewer: AloMediaViewerState,
    onDismiss: () -> Unit
) {
    if (viewer.items.isEmpty()) return
    val context = LocalContext.current
    AloSecureWindowWhile(viewer.isSensitive)
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val pagerState = rememberPagerState(
        initialPage = viewer.startIndex.coerceIn(0, viewer.items.lastIndex),
        pageCount = { viewer.items.size }
    )
    val entranceOffset by animateFloatAsState(
        targetValue = if (visible) 0f else 180f,
        animationSpec = tween(durationMillis = 240),
        label = "mediaEntrance"
    )
    val animatedDragOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(durationMillis = 180),
        label = "mediaDrag"
    )
    // The picture the person tapped, when the viewer can grow out of it (a still picture the viewer opens on).
    val origin = remember { AloMediaTransition.take() }
    val useOrigin = origin != null && pagerState.currentPage == viewer.startIndex &&
        viewer.items.getOrNull(viewer.startIndex)?.type?.startsWith("video") != true
    val originProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "mediaOrigin"
    )
    var rootSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // A zoomed photo holds the pages and the drag down (AloPhotoZoom).
    var pagesLocked by remember { mutableStateOf(false) }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "mediaScrim"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    fun dismissWithAnimation() {
        visible = false
        dragOffset = 0f
        scope.launch {
            delay(if (useOrigin) 230 else 180)
            onDismiss()
        }
    }

    BackHandler {
        dismissWithAnimation()
    }

    val activeMedia = viewer.items[pagerState.currentPage.coerceIn(0, viewer.items.lastIndex)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(Color.Black.copy(alpha = 0.92f * scrimAlpha))
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = ::dismissWithAnimation)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = ::dismissWithAnimation)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.42f))
                )
                if (viewer.items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${viewer.items.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.86f)
                    )
                }
            }
        }

        val contentModifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 12.dp, vertical = 28.dp)
            .graphicsLayer {
                if (useOrigin) {
                    // The picture itself grows out of its thumbnail (below); the box around it only follows a drag.
                    translationY = animatedDragOffset
                } else {
                    translationY = entranceOffset + animatedDragOffset
                    alpha = 1f - ((entranceOffset + animatedDragOffset) / 1200f).coerceIn(0f, 0.35f)
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (pagesLocked) return@detectVerticalDragGestures
                        change.consume()
                        dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        if (dragOffset > 180f) {
                            dismissWithAnimation()
                        } else {
                            dragOffset = 0f
                        }
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    }
                )
            }

        HorizontalPager(
            state = pagerState,
            modifier = contentModifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !pagesLocked
        ) { page ->
            val media = viewer.items[page]
            val sourceUrl = media.url.ifBlank { media.posterUrl }
            if (media.type.startsWith("video")) {
                // Telegram-style player (AloNativeVideoPlayer.kt): keeps the video's proportions, plays the page on screen.
                AloVideoPlayer(
                    sourceUrl = sourceUrl,
                    active = page == pagerState.currentPage,
                    knownAspect = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 0f,
                    posterUrl = media.posterUrl
                )
            } else {
                // The first picture grows out of the thumbnail the person tapped: from its place and its crop, with its corners, to full size.
                // The viewer's image box is as tall as the screen, so the picture's own height comes from its proportions once loaded.
                var imageCenter by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                var imageAspect by remember { mutableStateOf<Float?>(null) }
                val photoZoom = remember { AloPhotoZoom() }
                LaunchedEffect(photoZoom.zoomed, pagerState.currentPage) {
                    if (page == pagerState.currentPage) pagesLocked = photoZoom.zoomed
                }
                val zoom = if (useOrigin && origin != null && page == viewer.startIndex && imageCenter != null) {
                    val center = imageCenter!!
                    Modifier.graphicsLayer {
                        val f = originProgress
                        val width = size.width.coerceAtLeast(1f)
                        val height = size.height.coerceAtLeast(1f)
                        val startScale = (origin.bounds.width / width).coerceIn(0.05f, 1f)
                        val scale = startScale + (1f - startScale) * f
                        scaleX = scale
                        scaleY = scale
                        translationX = (origin.bounds.center.x - center.x) * (1f - f)
                        translationY = (origin.bounds.center.y - center.y) * (1f - f)
                        // The window starts as the thumbnail's shape (cropped from the middle) and opens to the whole picture.
                        val aspect = imageAspect ?: (origin.bounds.width / origin.bounds.height)
                        val pictureHeight = (width / aspect).coerceAtMost(height)
                        val startHeight = (width * origin.bounds.height / origin.bounds.width).coerceAtMost(pictureHeight)
                        val visibleHeight = startHeight + (pictureHeight - startHeight) * f
                        val top = (height - visibleHeight) / 2f
                        val radius = (origin.cornerRadiusPx / scale) * (1f - f)
                        // Only while it grows: afterwards a zoomed photo must not be cut to its unzoomed frame.
                        clip = f < 0.999f
                        shape = object : androidx.compose.ui.graphics.Shape {
                            override fun createOutline(
                                size: androidx.compose.ui.geometry.Size,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                density: androidx.compose.ui.unit.Density
                            ) = androidx.compose.ui.graphics.Outline.Rounded(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = 0f, top = top, right = size.width, bottom = top + visibleHeight,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
                                )
                            )
                        }
                    }
                } else {
                    Modifier
                }
                AloRemoteMediaImage(
                    model = sourceUrl,
                    contentDescription = media.alt.ifBlank { media.caption },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            if (page == viewer.startIndex && coordinates.size.width > 0) imageCenter = coordinates.boundsInRoot().center
                        }
                        .then(zoom)
                        .aloPhotoZoom(photoZoom, scope),
                    contentScale = ContentScale.Fit,
                    isVideo = false,
                    onImageSize = { width, height ->
                        if (height > 0) photoZoom.pictureAspect = width.toFloat() / height
                        if (page == viewer.startIndex && height > 0) imageAspect = width.toFloat() / height
                    }
                )
            }
        }

        if (activeMedia.caption.isNotBlank()) {
            Text(
                text = activeMedia.caption,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 28.dp)
                    .navigationBarsPadding(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
