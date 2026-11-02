package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** A story as a thumbnail: its picture, or its text on the story gradient. */
@Composable
internal fun AloStoryThumbnail(story: AloStory, modifier: Modifier = Modifier, textSize: Int = 13) {
    val media = story.media.firstOrNull()
    Box(
        modifier.background(Brush.linearGradient(listOf(AloNativeAppAccent, Color(0xFF6B45ED)))),
        contentAlignment = Alignment.Center
    ) {
        if (media != null) {
            AloRemoteMediaImage(
                model = media.posterUrl.ifBlank { media.url }, contentDescription = "История",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Text(story.body, color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}

/** What a profile avatar needs to hold stories: the app state, who is signed in, and actions. */
internal class AloProfileStoriesContext(
    val state: AloNativeAppUiState,
    val meId: Int,
    val onAction: (AloNativeAppAction) -> Unit
)

internal val LocalAloProfileStories = compositionLocalOf<AloProfileStoriesContext?> { null }

private val AloStoryRingGreen = Color(0xFF33C759)
private val AloStoryRingMint = Color(0xFF29D1A3)
private val AloStoryRingLive = Brush.linearGradient(listOf(AloStoryRingGreen, AloStoryRingMint))

/**
 * iOS's AloProfileStoryAvatar: the avatar holds the person's stories. A green ring shows there is
 * something to watch (bright for a live story, softer when only highlights are left) and a tap
 * opens them; your own avatar always opens them, so the archive stays one tap away. The ring is
 * drawn outside the avatar, so the avatar keeps its size.
 */
@Composable
internal fun AloProfileStoryAvatar(
    user: AloEntitySummary,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    /** `true` when a tap opens the stories, so the avatar itself should not react to it. */
    avatar: @Composable (opensStories: Boolean) -> Unit
) {
    val context = LocalAloProfileStories.current
    if (context == null) {
        Box(modifier) { avatar(false) }
        return
    }
    val highlights = context.state.highlights[user.id].orEmpty()
    val active = context.state.stories.firstOrNull { it.user.id == user.id && it.stories.isNotEmpty() }
    var isOpen by remember(user.id) { mutableStateOf(false) }
    LaunchedEffect(user.id) { context.onAction(AloNativeAppAction.LoadHighlights(user.id)) }
    val ring: Brush? = when {
        active != null && (active.hasUnseen || isOwn) -> AloStoryRingLive
        active != null || highlights.isNotEmpty() -> SolidColor(AloStoryRingGreen.copy(alpha = 0.5f))
        else -> null
    }
    val gap = AloNativeAppBackground
    Box(
        modifier
            .drawBehind {
                if (ring != null) {
                    val outset = 5.dp.toPx()
                    val width = 3.dp.toPx()
                    val radius = size.minDimension / 2 + outset
                    drawCircle(gap, radius = radius)
                    drawCircle(ring, radius = radius - width / 2, style = Stroke(width))
                }
            }
            .clip(CircleShape)
            .clickable(enabled = isOwn || ring != null) { isOpen = true }
            .semantics { contentDescription = if (ring != null) "Истории: ${user.name}" else user.name }
    ) { avatar(isOwn || ring != null) }
    if (isOpen) {
        AloProfileStoriesSheet(user = user, isOwn = isOwn, active = active, context = context, onDismiss = { isOpen = false })
    }
}

/**
 * iOS's AloProfileStoriesSheet: the live story, the highlights («Актуальное») as story cards, and
 * on your own profile the archive, where new highlights are made.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloProfileStoriesSheet(
    user: AloEntitySummary,
    isOwn: Boolean,
    active: AloStoryGroup?,
    context: AloProfileStoriesContext,
    onDismiss: () -> Unit
) {
    val state = context.state
    val onAction = context.onAction
    val highlights = state.highlights[user.id].orEmpty()
    var watchingActive by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<AloStoryHighlight?>(null) }
    var archiveMode by remember { mutableStateOf<Boolean?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<AloStoryHighlight?>(null) }

    AloFormSheet(title = if (isOwn) "Мои истории" else "Истории", onDismiss = onDismiss, leading = "Закрыть") {
        if (active != null) {
            AloStoriesSectionTitle("Сейчас")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(AloSquircle(26.dp))
                    .background(AloFormSectionColor)
                    .clickable { watchingActive = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                active.stories.firstOrNull()?.let { first ->
                    AloStoryThumbnail(
                        first,
                        Modifier.size(width = 54.dp, height = 72.dp).clip(AloSquircle(12.dp))
                            .border(2.dp, AloStoryRingLive, AloSquircle(12.dp)),
                        textSize = 9
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val count = active.stories.size
                    Text(if (count == 1) "История" else "$count ${aloStoryCountNoun(count)}", color = AloNativeAppText,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Исчезнет через сутки после публикации", color = AloNativeAppMuted, fontSize = 14.sp)
                }
                Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = AloNativeAppAccent, modifier = Modifier.size(22.dp))
            }
        }
        if (highlights.isNotEmpty()) {
            AloStoriesSectionTitle("Актуальное")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                highlights.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { highlight ->
                            Box(Modifier.weight(1f)) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(AloSquircle(14.dp))
                                        .combinedClickable(onClick = { playing = highlight }, onLongClick = { if (isOwn) menuFor = highlight.id }),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(AloSquircle(14.dp))
                                            .border(1.dp, AloNativeAppBorder, AloSquircle(14.dp))
                                    ) {
                                        highlight.cover?.let { AloStoryThumbnail(it, Modifier.fillMaxSize(), textSize = 11) }
                                            ?: Box(Modifier.fillMaxSize().background(AloNativeAppSurfaceRaised))
                                    }
                                    Text(highlight.title, color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                AloDropdownMenu(expanded = menuFor == highlight.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menuFor = null; renaming = highlight })
                                    DropdownMenuItem(text = { Text("Удалить актуальное", color = Color(0xFFFF453A)) }, onClick = {
                                        menuFor = null; onAction(AloNativeAppAction.DeleteHighlight(highlight.id))
                                    })
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else if (isOwn) {
            AloStoriesSectionTitle("Актуальное")
            Text(
                "Соберите истории из архива в подборки: они останутся в профиле и после того, как истории исчезнут из ленты.",
                color = AloNativeAppMuted, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (isOwn) {
            AloFormSection {
                AloStoriesActionRow("Архив историй", Icons.Outlined.Archive) { archiveMode = false }
                AloFormDivider()
                AloStoriesActionRow("Новое актуальное", Icons.Rounded.AddCircleOutline) { archiveMode = true }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (watchingActive && active != null) {
        AloStoryViewer(
            groups = listOf(active), startUserId = user.id, meId = context.meId, state = state, onAction = onAction,
            onDismiss = { watchingActive = false }
        )
    }
    playing?.let { highlight ->
        AloStoryViewer(
            groups = listOf(AloStoryGroup(user = user, stories = highlight.stories, hasUnseen = false)),
            startUserId = user.id,
            meId = context.meId,
            state = state,
            onAction = onAction,
            onDismiss = { playing = null },
            highlight = highlight
        )
    }
    archiveMode?.let { startsPicking ->
        AloStoryArchiveScreen(
            startsPicking = startsPicking,
            state = state,
            me = user,
            onAction = onAction,
            onDismiss = { archiveMode = null }
        )
    }
    renaming?.let { highlight ->
        var title by remember(highlight.id) { mutableStateOf(highlight.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text("Название актуального", color = AloNativeAppText) },
            text = {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(AloSquircle(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    BasicTextField(title, { title = it.take(32) }, singleLine = true,
                        textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp), cursorBrush = SolidColor(AloNativeAppAccent),
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    if (title.isNotBlank()) onAction(AloNativeAppAction.RenameHighlight(highlight.id, title.trim()))
                }) { Text("Сохранить", color = AloNativeAppAccent) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена", color = AloNativeAppMuted) } }
        )
    }
}

@Composable
private fun AloStoriesSectionTitle(text: String) {
    Text(
        text.uppercase(), color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun AloStoriesActionRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = AloNativeAppAccent, modifier = Modifier.size(22.dp))
        Text(title, color = AloNativeAppText, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** 2 истории, 5 историй. */
internal fun aloStoryCountNoun(count: Int): String {
    val tens = count % 100
    val units = count % 10
    return when {
        tens in 11..14 -> "историй"
        units == 1 -> "история"
        units in 2..4 -> "истории"
        else -> "историй"
    }
}

/**
 * iOS's AloStoryArchiveScreen: your stories, expired ones too, newest first, three to a row. A tap
 * plays one; «Выбрать» picks several (numbered in order) to add to a highlight.
 */
@Composable
private fun AloStoryArchiveScreen(
    startsPicking: Boolean,
    state: AloNativeAppUiState,
    me: AloEntitySummary,
    onAction: (AloNativeAppAction) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var isSelecting by remember { mutableStateOf(startsPicking) }
        var selected by remember { mutableStateOf(listOf<AloStory>()) }
        var watching by remember { mutableStateOf<AloStory?>(null) }
        var addOpen by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { onAction(AloNativeAppAction.LoadStoryArchive()) }
        BackHandler(onBack = onDismiss)
        Column(
            Modifier
                .fillMaxSize()
                .background(AloNativeAppBackground)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // iOS: AloChatCircleButton with a cross.
                AloGlassCircleButton(onClick = onDismiss, description = "Закрыть", close = true)
                Text(
                    if (startsPicking) "Новое актуальное" else "Архив историй", color = AloNativeAppText, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
                Text(
                    if (isSelecting) "Отмена" else "Выбрать", color = AloNativeAppAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp).clip(AloSquircle(10.dp)).clickable {
                        if (isSelecting && startsPicking) onDismiss() else { isSelecting = !isSelecting; selected = emptyList() }
                    }.padding(vertical = 12.dp)
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    state.isStoryArchiveLoading && state.storyArchive.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AloNativeAppAccent)
                    }
                    state.storyArchive.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.Archive, null, tint = AloNativeAppMuted, modifier = Modifier.size(40.dp))
                        Text("Архив пуст", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        Text("Здесь хранятся ваши истории после того, как они исчезнут из ленты.", color = AloNativeAppMuted, fontSize = 14.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(state.storyArchive, key = { it.id }) { story ->
                            val order = selected.indexOfFirst { it.id == story.id }
                            Box(
                                Modifier.aspectRatio(9f / 16f).clip(AloSquircle(6.dp)).clickable {
                                    if (isSelecting) selected = if (order >= 0) selected.filter { it.id != story.id } else selected + story
                                    else watching = story
                                }
                            ) {
                                AloStoryThumbnail(story, Modifier.fillMaxSize())
                                Text(archiveDay(story.createdAt), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                                if (isSelecting) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                                            .background(if (order >= 0) AloNativeAppAccent else Color.Black.copy(alpha = 0.25f))
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { if (order >= 0) Text("${order + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }
            if (isSelecting) {
                val enabled = selected.isNotEmpty()
                Box(
                    Modifier.padding(16.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(percent = 50))
                        .background(if (enabled) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                        .clickable(enabled = enabled) { addOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (enabled) "Добавить в актуальное (${selected.size})" else "Выберите истории",
                        color = if (enabled) Color.White else AloNativeAppMuted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        watching?.let { story ->
            AloStoryViewer(
                groups = listOf(AloStoryGroup(user = me, stories = listOf(story), hasUnseen = false)),
                startUserId = me.id, meId = me.id, state = state, onAction = onAction,
                onDismiss = { watching = null },
                highlight = AloStoryHighlight(id = -story.id, title = "", coverStoryId = story.id, stories = listOf(story))
            )
        }
        if (addOpen) {
            AloAddToHighlightSheet(
                highlights = state.highlights[me.id].orEmpty(),
                storyIds = selected.map { it.id },
                onAction = onAction,
                onDismiss = { addOpen = false },
                onAdded = { addOpen = false; onDismiss() }
            )
        }
    }
}

private fun archiveDay(iso: String): String = runCatching {
    SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(requireParsedDate(iso))
}.getOrDefault("")

/** iOS's AloCreatorStatsScreen: an overview against the period before, charts, all-time totals and top posts. */
@Composable
internal fun AloCreatorStatsScreen(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onBack: () -> Unit
) {
    var days by remember { mutableStateOf(30) }
    LaunchedEffect(days) { onAction(AloNativeAppAction.LoadCreatorStats(days)) }
    // Back is the swipe of the page around it (AloSwipeBackPage in AloNativeAppScreen), which follows the finger.
    val stats = state.creatorStats
    Column(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
        AloSettingsNavigationBar(title = "Панель автора", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = AloBottomBarOverlayPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(34.dp).clip(AloSquircle(10.dp)).background(Color.White.copy(alpha = 0.10f)).padding(2.dp)) {
                listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней").forEach { (value, title) ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(AloSquircle(8.dp))
                            .background(if (days == value) Color.White.copy(alpha = 0.28f) else Color.Transparent)
                            .clickable { days = value },
                        contentAlignment = Alignment.Center
                    ) { Text(title, color = AloNativeAppText, fontSize = 13.sp, fontWeight = if (days == value) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
            when {
                stats != null -> {
                    AloStatsSection("Обзор") {
                        val tiles = listOf(
                            "Просмотры" to stats.views, "Отметки «Нравится»" to stats.likes, "Комментарии" to stats.comments,
                            "Репосты" to stats.reposts, "Новые подписчики" to stats.followers
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            (tiles.chunked(2)).forEachIndexed { rowIndex, row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { (title, metric) -> AloStatTile(title, metric, Modifier.weight(1f)) }
                                    if (rowIndex == 2) {
                                        Column(Modifier.weight(1f)) {
                                            Text(String.format(Locale.US, "%.1f%%", stats.engagementRate), color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                            Text("Вовлечённость", color = AloNativeAppMuted, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AloStatsSection("Просмотры") { AloViewsChart(stats.daily) }
                    AloStatsSection("Реакции") { AloReactionsChart(stats.daily) }
                    AloStatsSection("За всё время") {
                        Row {
                            listOf(stats.allTime.posts to "публикаций", stats.allTime.followers to "подписчиков",
                                stats.allTime.views to "просмотров", stats.allTime.likes to "«Нравится»").forEach { (value, title) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatCompactCount(value), color = AloNativeAppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(title, color = AloNativeAppMuted, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (stats.topPosts.isNotEmpty()) {
                        AloStatsSection("Лучшие публикации") {
                            stats.topPosts.forEachIndexed { index, post ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onAction(AloNativeAppAction.OpenComments(post.id)) }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(post.body.ifBlank { if (post.media.isEmpty()) "Публикация" else "Фото или видео" },
                                            color = AloNativeAppText, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            AloStatLabel(Icons.Outlined.Visibility, formatCompactCount(post.viewCount))
                                            AloStatLabel(Icons.Outlined.FavoriteBorder, formatCompactCount(post.likeCount))
                                            AloStatLabel(Icons.Outlined.ChatBubbleOutline, formatCompactCount(post.commentCount))
                                            Text(formatPostTimestamp(post.createdAt), color = AloNativeAppMuted, fontSize = 12.sp)
                                        }
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                                }
                                if (index < stats.topPosts.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppBorder))
                            }
                        }
                    }
                }
                state.creatorStatsError.isNotBlank() -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Статистика недоступна", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.creatorStatsError, color = AloNativeAppMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
                else -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AloNativeAppAccent) }
            }
        }
    }
}

@Composable
private fun AloStatsSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.55f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title.uppercase(Locale.forLanguageTag("ru")), color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun AloStatTile(title: String, metric: AloCreatorStats.Metric, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatCompactCount(metric.current), color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val change = metric.change
            if (change != null && metric.current != metric.previous) {
                Text(
                    (if (change >= 0) "+" else "−") + String.format(Locale.US, "%.0f%%", abs(change)),
                    color = if (change >= 0) Color(0xFF32D74B) else Color(0xFFFF453A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        Text(title, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AloStatLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, tint = AloNativeAppMuted, modifier = Modifier.size(13.dp))
        Text(value, color = AloNativeAppMuted, fontSize = 12.sp)
    }
}

private fun chartDayLabels(daily: List<AloCreatorStats.Day>): List<Pair<Int, String>> {
    if (daily.isEmpty()) return emptyList()
    val format = SimpleDateFormat("d MMM", Locale.forLanguageTag("ru"))
    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val step = (daily.size / 4).coerceAtLeast(1)
    return daily.indices.filter { it % step == 0 }.take(4).map { index ->
        index to runCatching { format.format(parse.parse(daily[index].date)!!) }.getOrDefault("")
    }
}

/** Views per day: a filled area under a smooth line, the values on the trailing edge. */
@Composable
private fun AloViewsChart(daily: List<AloCreatorStats.Day>) {
    val accent = AloNativeAppAccent
    val grid = AloNativeAppBorder
    val maxValue = (daily.maxOfOrNull { it.views } ?: 0).coerceAtLeast(1)
    Column {
        Row {
            Canvas(Modifier.weight(1f).height(150.dp)) {
                if (daily.size < 2) return@Canvas
                repeat(4) { line ->
                    val y = size.height * line / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val points = daily.mapIndexed { index, day ->
                    Offset(size.width * index / (daily.size - 1), size.height * (1f - day.views / maxValue.toFloat()))
                }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val previous = points[i - 1]
                        val current = points[i]
                        val midX = (previous.x + current.x) / 2
                        cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                    }
                }
                val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))))
                drawPath(line, accent, style = Stroke(width = 2.dp.toPx()))
            }
            Column(Modifier.height(150.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(maxValue, maxValue * 2 / 3, maxValue / 3, 0).forEach { Text("$it", color = AloNativeAppMuted, fontSize = 10.sp) }
            }
        }
        AloChartAxis(daily)
    }
}

/** Likes and comments per day as side-by-side bars, with a legend below. */
@Composable
private fun AloReactionsChart(daily: List<AloCreatorStats.Day>) {
    val likes = Color(0xFFFF3361)
    val comments = AloNativeAppAccent
    val grid = AloNativeAppBorder
    val maxValue = (daily.maxOfOrNull { maxOf(it.likes, it.comments) } ?: 0).coerceAtLeast(1)
    Column {
        Row {
            Canvas(Modifier.weight(1f).height(130.dp)) {
                repeat(4) { line ->
                    val y = size.height * line / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (daily.isEmpty()) return@Canvas
                val slot = size.width / daily.size
                val bar = (slot * 0.36f).coerceAtLeast(1f)
                daily.forEachIndexed { index, day ->
                    val x = slot * index + slot * 0.12f
                    val likeHeight = size.height * day.likes / maxValue
                    val commentHeight = size.height * day.comments / maxValue
                    drawRect(likes, Offset(x, size.height - likeHeight), Size(bar, likeHeight))
                    drawRect(comments, Offset(x + bar, size.height - commentHeight), Size(bar, commentHeight))
                }
            }
            Column(Modifier.height(130.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(maxValue, maxValue * 2 / 3, maxValue / 3, 0).forEach { Text("$it", color = AloNativeAppMuted, fontSize = 10.sp) }
            }
        }
        AloChartAxis(daily)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(likes to "Нравится", comments to "Комментарии").forEach { (color, title) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(title, color = AloNativeAppMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AloChartAxis(daily: List<AloCreatorStats.Day>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        chartDayLabels(daily).forEach { (_, label) -> Text(label, color = AloNativeAppMuted, fontSize = 10.sp) }
    }
}
