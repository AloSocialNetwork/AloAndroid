package com.alo.android

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PHOTO_SECONDS = 5f

/** A story with several media shows each as its own frame; a story without media is one frame. */
private data class AloStoryFrame(val story: AloStory, val media: AloMediaItem?)

internal fun aloViewsWord(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "просмотр"
        mod10 in 2..4 && mod100 !in 12..14 -> "просмотра"
        else -> "просмотров"
    }
}

private fun ruPlural(count: Long, one: String, few: String, many: String): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod10 == 1L && mod100 != 11L -> one
        mod10 in 2L..4L && mod100 !in 12L..14L -> few
        else -> many
    }
}

/** iOS's RelativeDateTimeFormatter in Russian, full style: «7 секунд назад», «1 час назад». */
internal fun storyAge(iso: String): String = runCatching {
    val seconds = ((System.currentTimeMillis() - requireParsedDate(iso).time) / 1000).coerceAtLeast(0)
    val (value, word) = when {
        seconds < 60 -> seconds to ruPlural(seconds, "секунду", "секунды", "секунд")
        seconds < 3_600 -> (seconds / 60).let { it to ruPlural(it, "минуту", "минуты", "минут") }
        seconds < 86_400 -> (seconds / 3_600).let { it to ruPlural(it, "час", "часа", "часов") }
        else -> (seconds / 86_400).let { it to ruPlural(it, "день", "дня", "дней") }
    }
    "$value $word назад"
}.getOrDefault("")

/** «только что», «5 мин назад», then the time, Telegram-style. */
private fun viewedText(iso: String): String = runCatching {
    val date = requireParsedDate(iso)
    val minutes = ((System.currentTimeMillis() - date.time) / 60_000).toInt()
    when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        else -> {
            val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(date)
            val day = Calendar.getInstance().apply { this.time = date }
            val today = Calendar.getInstance()
            val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            if (sameDay) "сегодня в $time" else "вчера в $time"
        }
    }
}.getOrDefault("")

internal fun AloStory.isExpired(): Boolean =
    runCatching { requireParsedDate(expiresAt).time < System.currentTimeMillis() }.getOrDefault(false)

/**
 * iOS's AloStoryViewer (Instagram-like): a rounded 9:16 card with progress segments, the author and
 * time, «⋯» and close; a third on the left goes back, the rest forward, a finger holds the story;
 * swipe down closes, sideways changes person. Your own story shows its views; others' offer a reply.
 */
@Composable
internal fun AloStoryViewer(
    groups: List<AloStoryGroup>,
    startUserId: Int,
    meId: Int,
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit,
    highlight: AloStoryHighlight? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The story is immersive: the status bar hides, as on iOS.
        val view = LocalView.current
        LaunchedEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            // The whole screen, the camera cutout too: a dialog window stops below the cutout by default, and the strip
            // above the story showed the app under the dialog's dim (grey over the light theme instead of black).
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        AloStoryViewerContent(groups, startUserId, meId, state, onAction, onDismiss, highlight)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AloStoryViewerContent(
    groups: List<AloStoryGroup>,
    startUserId: Int,
    meId: Int,
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit,
    highlight: AloStoryHighlight?
) {
    var groupIndex by remember { mutableIntStateOf(groups.indexOfFirst { it.user.id == startUserId }.coerceAtLeast(0)) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var viewersOpen by remember { mutableStateOf(false) }
    var addToHighlightOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val density = LocalDensity.current

    val group = groups.getOrNull(groupIndex)
    val frames = remember(group) {
        group?.stories.orEmpty().flatMap { story ->
            if (story.media.isEmpty()) listOf(AloStoryFrame(story, null)) else story.media.map { AloStoryFrame(story, it) }
        }
    }
    val frame = frames.getOrNull(frameIndex)
    val isOwn = group?.user?.id == meId
    val isPaused = isHolding || menuOpen || viewersOpen || addToHighlightOpen || confirmDelete ||
        dragX.value != 0f || dragY.value != 0f

    fun nextGroup() {
        if (groupIndex + 1 >= groups.size) { onDismiss(); return }
        groupIndex += 1; frameIndex = 0; progress = 0f
    }
    fun previousGroup() {
        if (groupIndex > 0) { groupIndex -= 1; frameIndex = 0; progress = 0f }
    }
    fun next() {
        if (frameIndex + 1 < frames.size) { frameIndex += 1; progress = 0f } else nextGroup()
    }
    fun previous() {
        if (frameIndex > 0) frameIndex -= 1 else if (groupIndex > 0) { groupIndex -= 1; frameIndex = 0 }
        progress = 0f
    }
    fun showToast(text: String) {
        scope.launch { toast = text; delay(1_600); toast = null }
    }

    LaunchedEffect(groupIndex, frameIndex) {
        val current = frame ?: return@LaunchedEffect
        if (!isOwn && highlight == null) onAction(AloNativeAppAction.MarkStoryViewed(current.story.id))
    }
    // Photos and text advance on a timer; videos report their own progress.
    LaunchedEffect(groupIndex, frameIndex, isPaused) {
        if (isPaused || frame == null || frame.media?.type?.startsWith("video") == true) return@LaunchedEffect
        // Measured against the clock, not counted in ticks: a slow frame must not stretch the story.
        val startProgress = progress
        val startedAt = System.nanoTime()
        while (true) {
            delay(32)
            progress = startProgress + (System.nanoTime() - startedAt) / 1_000_000_000f / PHOTO_SECONDS
            if (progress >= 1f) { next(); return@LaunchedEffect }
        }
    }
    BackHandler(onBack = onDismiss)

    val backdropAlpha = 1f - (dragY.value.coerceAtLeast(0f) / with(density) { 500.dp.toPx() }).coerceAtMost(0.6f)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    ) {
        val width = maxWidth
        val cardHeight = minOf(maxHeight - 70.dp, width * 16f / 9f)
        val widthPx = with(density) { width.toPx() }
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(dragX.value.roundToInt(), dragY.value.coerceAtLeast(0f).roundToInt()) }
                .graphicsLayer {
                    val scale = 1f - (dragY.value.coerceAtLeast(0f) / 2_000f).coerceAtMost(0.12f)
                    scaleX = scale; scaleY = scale
                }
                .pointerInput(groupIndex) {
                    var vertical: Boolean? = null
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { vertical = null; dx = 0f; dy = 0f },
                        onDragEnd = {
                            val isVertical = vertical == true
                            scope.launch {
                                when {
                                    isVertical && dy > 120.dp.toPx() -> onDismiss()
                                    !isVertical && dx < -widthPx / 4 -> { nextGroup(); dragX.snapTo(0f) }
                                    !isVertical && dx > widthPx / 4 -> { previousGroup(); dragX.snapTo(0f) }
                                    else -> {
                                        launch { dragX.animateTo(0f, spring(dampingRatio = 0.86f)) }
                                        dragY.animateTo(0f, spring(dampingRatio = 0.86f))
                                    }
                                }
                            }
                        },
                        onDragCancel = { scope.launch { dragX.snapTo(0f); dragY.snapTo(0f) } },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x; dy += amount.y
                            if (vertical == null && (abs(dx) > 16.dp.toPx() || abs(dy) > 16.dp.toPx())) vertical = abs(dy) > abs(dx)
                            scope.launch {
                                if (vertical == true) dragY.snapTo(dy) else if (vertical == false) dragX.snapTo(dx * 0.6f)
                            }
                        }
                    )
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(AloSquircle(18.dp))
                    .background(Color.Black)
            ) {
                frame?.let { current ->
                    AloStoryFrameContent(
                        frame = current,
                        isPaused = isPaused,
                        onProgress = { progress = it },
                        onEnd = ::next
                    )
                }
                // A third on the left goes back, the rest goes forward; a finger on the story holds it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(groupIndex, frameIndex) {
                            detectTapGestures(
                                onPress = {
                                    isHolding = true
                                    tryAwaitRelease()
                                    isHolding = false
                                },
                                onTap = { offset -> if (offset.x < size.width / 3f) previous() else next() }
                            )
                        }
                )
                AloStoryChrome(
                    frames = frames.size,
                    frameIndex = frameIndex,
                    progress = progress,
                    title = highlight?.title?.takeIf { it.isNotBlank() } ?: if (isOwn) "Ваша история" else group?.user?.name.orEmpty(),
                    user = group?.user,
                    age = frame?.let { storyAge(it.story.createdAt) }.orEmpty(),
                    menu = {
                        // iOS's popover: a dark glass card of pill buttons.
                        AloDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = AloSquircle(26.dp),
                            containerColor = Color(0xFF0B1A2E).copy(alpha = 0.82f),
                        ) {
                            val story = frame?.story
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (story != null && !story.isExpired()) {
                                    AloStoryMenuPill("Копировать ссылку") {
                                        menuOpen = false
                                        onAction(AloNativeAppAction.CopyStoryLink(story.id) { ok -> if (ok) showToast("Ссылка скопирована") })
                                    }
                                }
                                if (isOwn && story != null) {
                                    if (highlight != null) {
                                        AloStoryMenuPill("Убрать из актуального", destructive = true) {
                                            menuOpen = false
                                            onAction(AloNativeAppAction.RemoveFromHighlight(highlight.id, story.id))
                                            onDismiss()
                                        }
                                    } else {
                                        AloStoryMenuPill("Добавить в актуальное") { menuOpen = false; addToHighlightOpen = true }
                                        AloStoryMenuPill("Удалить историю", destructive = true) { menuOpen = false; confirmDelete = true }
                                    }
                                }
                            }
                        }
                    },
                    onMore = { menuOpen = true },
                    onClose = onDismiss
                )
            }
            Box(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (isOwn && frame != null) {
                    // Telegram's viewers button: the count opens who has seen the story.
                    Row(
                        Modifier
                            .height(40.dp)
                            .aloLiquidGlass(RoundedCornerShape(percent = 50))
                            .clickable {
                                viewersOpen = true
                                onAction(AloNativeAppAction.LoadStoryViewers(frame.story.id))
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("${frame.story.viewCount} ${aloViewsWord(frame.story.viewCount)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else if (group != null && highlight == null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
                            .clickable {
                                onDismiss()
                                onAction(AloNativeAppAction.OpenDirectConversation(group.user.id))
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ответить…", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.Send, "Ответить", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            Row(
                Modifier
                    .height(44.dp)
                    .aloLiquidGlass(RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(if (toast?.contains("актуальное") == true) Icons.Rounded.Star else Icons.Rounded.Link, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(toast.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (viewersOpen && frame != null) {
        AloStoryViewersSheet(
            story = frame.story,
            state = state,
            onDismiss = { viewersOpen = false },
            onOpenProfile = { user ->
                viewersOpen = false
                onDismiss()
                onAction(AloNativeAppAction.OpenViewedProfile(AloProfileTarget.User(user.id)))
            }
        )
    }
    if (addToHighlightOpen && frame != null) {
        AloAddToHighlightSheet(
            highlights = state.highlights[meId].orEmpty(),
            storyIds = listOf(frame.story.id),
            onAction = onAction,
            onDismiss = { addToHighlightOpen = false },
            onAdded = { addToHighlightOpen = false; showToast("Добавлено в актуальное") }
        )
    }
    if (confirmDelete && frame != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text("Удалить историю?", color = AloNativeAppText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onAction(AloNativeAppAction.DeleteStory(frame.story.id) { onDismiss() })
                }) { Text("Удалить", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена", color = AloNativeAppAccent) } }
        )
    }
}

@Composable
private fun AloStoryMenuPill(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .widthIn(min = 236.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (destructive) Color(0xFFFF453A) else Color.White, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun AloStoryChrome(
    frames: Int,
    frameIndex: Int,
    progress: Float,
    title: String,
    user: AloEntitySummary?,
    age: String,
    menu: @Composable () -> Unit,
    onMore: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(frames.coerceAtLeast(1)) { index ->
                val fill = when {
                    index < frameIndex -> 1f
                    index == frameIndex -> progress.coerceIn(0f, 1f)
                    else -> 0f
                }
                Box(Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (user != null) {
                AvatarBadge(name = user.name, avatarUrl = user.avatarUrl, verified = false, size = 34.dp, colorSeed = user.id.toString())
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (age.isNotBlank()) Text(age, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1)
            }
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, "Ещё", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                menu()
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Закрыть историю", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun AloStoryFrameContent(
    frame: AloStoryFrame,
    isPaused: Boolean,
    onProgress: (Float) -> Unit,
    onEnd: () -> Unit
) {
    val media = frame.media
    Box(Modifier.fillMaxSize()) {
        when {
            media != null && media.type.startsWith("video") -> AloStoryVideo(media.url, isPaused, onProgress, onEnd)
            media != null -> AloRemoteMediaImage(
                model = media.posterUrl.ifBlank { media.url },
                contentDescription = "История",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(AloNativeAppAccent, Color(0xFF6B45ED))))
            )
        }
        if (frame.story.body.isNotBlank()) {
            val isTextStory = media == null
            if (isTextStory) {
                Text(
                    frame.story.body, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp)
                )
            } else {
                Text(
                    frame.story.body, color = Color.White, fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    }
}

/** A story video: fills the frame, plays once, reports progress and its end; pauses with the viewer. */
@Composable
private fun AloStoryVideo(url: String, isPaused: Boolean, onProgress: (Float) -> Unit, onEnd: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(url), aloAuthenticatedMediaHeaders(url))
                setOnCompletionListener { onEnd() }
                setOnPreparedListener { start() }
                view = this
            }
        },
        onRelease = { it.stopPlayback() }
    )
    LaunchedEffect(isPaused, view) {
        val player = view ?: return@LaunchedEffect
        if (isPaused) { player.pause(); return@LaunchedEffect }
        player.start()
        while (true) {
            val duration = player.duration
            if (duration > 0) onProgress(player.currentPosition.toFloat() / duration)
            delay(100)
        }
    }
}

/** Telegram's story viewers list: who opened the story and when, with search and a sort menu. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AloStoryViewersSheet(
    story: AloStory,
    state: AloNativeAppUiState,
    onDismiss: () -> Unit,
    onOpenProfile: (AloEntitySummary) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val needle = query.trim().lowercase(Locale.getDefault())
    val found = state.storyViewers.filter {
        needle.isBlank() || it.user.name.lowercase(Locale.getDefault()).contains(needle) || it.user.username.lowercase(Locale.getDefault()).contains(needle)
    }
    val shown = if (byName) found.sortedBy { it.user.name.lowercase(Locale.getDefault()) } else found
    val count = if (state.isStoryViewersLoading) story.viewCount else state.storyViewers.size
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = AloNativeAppBackground,
        dragHandle = { Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 420.dp).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Закрыть", color = AloNativeAppText, fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
                Text("$count ${aloViewsWord(count)}", color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).clickable { sortOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.SwapVert, "Порядок", tint = AloNativeAppText, modifier = Modifier.size(24.dp))
                    }
                    AloDropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        DropdownMenuItem(text = { Text((if (!byName) "✓ " else "") + "Сначала новые") }, onClick = { byName = false; sortOpen = false })
                        DropdownMenuItem(text = { Text((if (byName) "✓ " else "") + "По имени") }, onClick = { byName = true; sortOpen = false })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = AloNativeAppSeparator)
            when {
                state.isStoryViewersLoading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AloNativeAppAccent)
                }
                state.storyViewersFailed -> AloSheetEmpty("Не удалось загрузить", "Проверьте соединение и откройте список ещё раз.")
                state.storyViewers.isEmpty() -> AloSheetEmpty("Пока никто не посмотрел", "Здесь появятся те, кто откроет историю.")
                else -> {
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.user.id }) { entry ->
                            AloUserCell(
                                name = entry.user.name,
                                status = viewedText(entry.viewedAt),
                                avatarUrl = entry.user.avatarUrl,
                                colorSeed = entry.user.id.toString(),
                                style = AloUserCellStyle.Contact,
                                onClick = { onOpenProfile(entry.user) }
                            )
                        }
                        if (shown.isEmpty()) item { AloSheetEmpty("Ничего не найдено", "Попробуйте другое имя.") }
                    }
                    AloSheetSearchField(query, { query = it }, Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
internal fun AloSheetSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск", color = AloNativeAppMuted, fontSize = 17.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp),
                cursorBrush = SolidColor(AloNativeAppAccent), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AloSheetEmpty(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = AloNativeAppMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** «Добавить в актуальное»: one of your highlights, or a new one with a title. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AloAddToHighlightSheet(
    highlights: List<AloStoryHighlight>,
    storyIds: List<Int>,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    AloModalBottomSheet(onDismissRequest = onDismiss, containerColor = AloNativeAppBackground) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Добавить в актуальное", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            highlights.forEach { highlight ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !saving) {
                        saving = true
                        onAction(AloNativeAppAction.AddToHighlight(highlight.id, storyIds) { ok -> saving = false; if (ok) onAdded() })
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AloHighlightCover(highlight.cover, 48.dp)
                    Text(highlight.title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Новое актуальное", color = AloNativeAppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(AloSquircle(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (title.isEmpty()) Text("Название", color = AloNativeAppMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = title, onValueChange = { title = it.take(32) }, singleLine = true,
                        textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp), cursorBrush = SolidColor(AloNativeAppAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "Создать", color = if (title.isNotBlank() && !saving) AloNativeAppAccent else AloNativeAppMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(AloSquircle(10.dp)).clickable(enabled = title.isNotBlank() && !saving) {
                        saving = true
                        onAction(AloNativeAppAction.CreateHighlight(title.trim(), storyIds) { ok -> saving = false; if (ok) onAdded() })
                    }.padding(10.dp)
                )
            }
        }
    }
}

/** A highlight's round cover: the cover story's picture, or its text on the story gradient. */
@Composable
internal fun AloHighlightCover(story: AloStory?, size: androidx.compose.ui.unit.Dp) {
    val media = story?.media?.firstOrNull()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, AloNativeAppBackground, CircleShape)
            .background(Brush.linearGradient(listOf(AloNativeAppAccent, Color(0xFF6B45ED)))),
        contentAlignment = Alignment.Center
    ) {
        if (media != null) {
            AloRemoteMediaImage(
                model = media.posterUrl.ifBlank { media.url }, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else if (story != null) {
            Text(story.body, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center,
                modifier = Modifier.padding(6.dp))
        }
    }
}
