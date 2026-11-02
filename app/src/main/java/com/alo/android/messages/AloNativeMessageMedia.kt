package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One photo or video in a chat keeps the picture's shape, as in Telegram and on the other clients: a tall clip is a tall
 * tile, at most 360 dp high, on its sender's side. Without a known size (or for a sticker) the tile has a fixed height.
 */
internal fun Modifier.aloSingleMediaShape(item: AloMediaItem?, unknownHeight: Dp, isOutgoing: Boolean): Modifier {
    if (item == null || item.width <= 0 || item.height <= 0) return fillMaxWidth().height(unknownHeight)
    val ratio = (item.width.toFloat() / item.height).coerceIn(0.5f, 2.2f)
    return fillMaxWidth()
        .wrapContentWidth(if (isOutgoing) Alignment.End else Alignment.Start)
        .widthIn(max = 360.dp * ratio)
        .fillMaxWidth()
        .aspectRatio(ratio)
}

@Composable
internal fun MessageMediaCollage(
    items: List<AloMediaItem>,
    modifier: Modifier = Modifier,
    borderColor: Color,
    onOpenAt: (Int) -> Unit,
    onLongPress: () -> Unit
) {
    if (items.isEmpty()) return
    val visibleItems = items.take(4)
    val extraCount = (items.size - visibleItems.size).coerceAtLeast(0)
    val outerShape = AloSquircle(16.dp)
    val spacing = 1.dp

    @Composable
    fun MediaTile(
        item: AloMediaItem,
        index: Int,
        tileModifier: Modifier = Modifier,
        overlayLabel: String? = null
    ) {
        val originTracker = rememberAloMediaOriginTracker()
        val originCornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
        Box(
            modifier = tileModifier
                .trackMediaOrigin(originTracker)
                .background(Color(0x16000000))
                .combinedClickable(
                    onClick = {
                        originTracker.offer(originCornerPx)
                        onOpenAt(index)
                    },
                    onLongClick = onLongPress
                ),
            contentAlignment = Alignment.Center
        ) {
            AloRemoteMediaImage(
                model = item.posterUrl.ifBlank { item.url },
                contentDescription = item.alt,
                isVideo = item.type == "video",
                modifier = Modifier.fillMaxSize()
            )
            if (item.type == "video") {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.46f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(18.dp)
                    )
                }
            }

            if (!overlayLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = overlayLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(outerShape)
            .border(0.85.dp, borderColor, outerShape)
            .background(borderColor.copy(alpha = 0.35f))
            .height(
                when (visibleItems.size) {
                    2 -> 190.dp
                    3 -> 230.dp
                    else -> 252.dp
                }
            )
    ) {
        when (visibleItems.size) {
            2 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    MediaTile(
                        visibleItems[0],
                        0,
                        tileModifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MediaTile(
                        visibleItems[1],
                        1,
                        tileModifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }

            3 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    MediaTile(
                        visibleItems[0],
                        0,
                        tileModifier = Modifier.weight(1.12f).fillMaxHeight()
                    )
                    Column(
                        modifier = Modifier.weight(0.88f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        MediaTile(
                            visibleItems[1],
                            1,
                            tileModifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        MediaTile(
                            visibleItems[2],
                            2,
                            tileModifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        MediaTile(
                            visibleItems[0],
                            0,
                            tileModifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        MediaTile(
                            visibleItems[1],
                            1,
                            tileModifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        MediaTile(
                            visibleItems[2],
                            2,
                            tileModifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        MediaTile(
                            visibleItems[3],
                            3,
                            tileModifier = Modifier.weight(1f).fillMaxHeight(),
                            overlayLabel = if (extraCount > 0) "+$extraCount" else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AloRemoteMediaImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    isVideo: Boolean = false,
    /** The decoded image's pixel size, for layouts that follow the real proportions (as on iOS). */
    onImageSize: ((width: Int, height: Int) -> Unit)? = null
) {
    var isLoading by remember(model) { mutableStateOf(true) }
    var isFailed by remember(model) { mutableStateOf(false) }
    val imageModel = model.takeIf { it.isNotBlank() }
    val shouldLoadImage = imageModel != null && !(isVideo && imageModel.looksLikeVideoFile())
    // A video without a poster shows its own first frame, as Telegram does, instead of an empty grey card.
    val context = LocalContext.current
    val firstFrame by produceState<Bitmap?>(initialValue = AloVideoFrames.cached(model), model, shouldLoadImage) {
        if (shouldLoadImage || imageModel == null || value != null) return@produceState
        value = AloVideoFrames.load(context, imageModel)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        firstFrame?.let { frame ->
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        }
        if (shouldLoadImage) {
            AsyncImage(
                model = imageModel,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onLoading = {
                    isLoading = true
                    isFailed = false
                },
                onSuccess = { success ->
                    isLoading = false
                    isFailed = false
                    val image = success.result.image
                    if (image.width > 0 && image.height > 0) onImageSize?.invoke(image.width, image.height)
                },
                onError = {
                    isLoading = false
                    isFailed = true
                }
            )
        }

        if (firstFrame == null && (!shouldLoadImage || isLoading || isFailed)) {
            MediaLoadingPlaceholder(
                isVideo = isVideo,
                isFailed = shouldLoadImage && isFailed || imageModel == null,
                isPosterMissing = imageModel != null && !shouldLoadImage
            )
        }
    }
}

@Composable
private fun MediaLoadingPlaceholder(
    isVideo: Boolean,
    isFailed: Boolean,
    isPosterMissing: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = 0.08f))
                .blur(12.dp)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (isPosterMissing) {
                // The frame is still being read; the caller's play sign is enough (a second one drew on top of it).
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            } else if (isFailed) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (isVideo) 0.74f else 0.36f),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Нет связи",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.78f)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.86f)
                )
                Text(
                    text = "Загрузка",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun String.looksLikeVideoFile(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mp4") ||
        clean.endsWith(".mov") ||
        clean.endsWith(".m4v") ||
        clean.endsWith(".webm")
}

@Composable
internal fun MessageSelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(22.dp),
        shape = CircleShape,
        color = if (selected) AloNativeAppAccent else AloNativeAppSurfaceRaised,
        border = BorderStroke(1.dp, if (selected) AloNativeAppAccent.copy(alpha = 0.55f) else AloNativeAppBorder.copy(alpha = 0.08f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = AloNativeAppButtonText
                )
            }
        }
    }
}


/** First frames of message videos, read once per address (a small in-memory cache). */
internal object AloVideoFrames {
    private val frames = LruCache<String, Bitmap>(24)

    fun cached(url: String): Bitmap? = url.takeIf { it.isNotBlank() }?.let { frames.get(it) }

    suspend fun load(context: android.content.Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        frames.get(url)?.let { return@withContext it }
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    retriever.setDataSource(url, aloAuthenticatedMediaHeaders(url))
                } else {
                    retriever.setDataSource(context, Uri.parse(url))
                }
                // getScaledFrameAtTime is Android 8.1+; older phones take the full-size frame.
                (if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(250_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
                } else {
                    null
                }) ?: retriever.getFrameAtTime(250_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(0)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.also { frames.put(url, it) }
    }
}
