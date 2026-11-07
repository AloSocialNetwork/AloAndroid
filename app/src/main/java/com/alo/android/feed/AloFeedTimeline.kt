package com.alo.android

import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The feed as iOS draws it: Alo's approved timeline (avatar at 14, content beside it, no card),
// the reader's tools (why, not interested, hide, report) and Alo's own ads.

/** iOS's like colour. */
private val AloLikeRed = Color(0xFFFF3361)
private val AloRepostGreen = Color(0xFF33C773)

enum class AloPostReaderAction { WhyShown, NotInterested, Hide, Report }

/** iOS's AloTimelinePost header (points = dp): the name block, 2pt down, shares a 24pt row with the «…» button, whose 44pt frame overhangs the row by 10. */
internal object AloTimelineMetrics {
    val HeaderHeight = 24.dp
    val MenuOverhang = 10.dp
    val MenuWidth = 44.dp - MenuOverhang

    /** The dots sit 12 from the row's end (22 - 10); a 44dp circle placed in the 34dp box is centred 17 from it, so it moves 5 towards the end. */
    val MenuShift = MenuWidth / 2 - (44.dp / 2 - MenuOverhang)
}

@Composable
internal fun AloTimelinePostRow(
    post: AloPost,
    isMine: Boolean,
    isLikePending: Boolean,
    isRepostPending: Boolean,
    onAuthor: () -> Unit,
    onComments: () -> Unit,
    onRepost: () -> Unit,
    onLike: () -> Unit,
    onOpenMedia: (List<AloMediaItem>, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onReaderAction: ((AloPostReaderAction) -> Unit)?,
    /** The opened post (iOS's «Публикация»): text, media and actions span the full width under the author. */
    expanded: Boolean = false
) {
    var menuOpen by remember(post.id) { mutableStateOf(false) }
    var confirmsDelete by remember(post.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val content: @Composable () -> Unit = {
        if (post.body.isNotBlank()) {
            AloLinkedText(
                text = post.body,
                modifier = Modifier.padding(top = 3.dp),
                // iOS: 15pt text with its natural 18pt line.
                style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 18.sp),
                color = AloNativeAppText
            )
        }
        if (post.media.isNotEmpty()) {
            AloTimelineMediaGrid(
                media = post.media,
                modifier = Modifier.padding(top = 10.dp),
                onOpen = { index -> onOpenMedia(post.media, index) }
            )
        }
        if (post.authorSignature.isNotBlank()) {
            Text(post.authorSignature, color = AloNativeAppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AloPostActionLabel(
                count = post.likeCount,
                icon = if (post.viewerLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                selected = post.viewerLiked,
                tint = AloLikeRed,
                enabled = !isLikePending,
                modifier = Modifier.weight(1f),
                onClick = onLike
            )
            AloPostActionLabel(
                count = post.commentCount,
                icon = if (post.commentsEnabled) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Lock,
                enabled = post.commentsEnabled,
                modifier = Modifier.weight(1f),
                onClick = onComments
            )
            AloPostActionLabel(
                count = post.repostCount,
                icon = Icons.Rounded.Repeat,
                selected = post.viewerReposted,
                tint = AloRepostGreen,
                enabled = !isRepostPending,
                modifier = Modifier.weight(1f),
                onClick = onRepost
            )
            AloPostActionLabel(
                count = post.viewCount,
                icon = Icons.Rounded.Visibility,
                alignEnd = true,
                modifier = Modifier.weight(1f),
                onClick = null
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (expanded) 16.dp else 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = if (expanded) Alignment.CenterVertically else Alignment.Top
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                AvatarBadge(
                    name = post.author.name,
                    avatarUrl = post.author.avatarUrl,
                    verified = false,
                    size = 42.dp,
                    colorSeed = post.author.id.toString(),
                    onClick = onAuthor,
                    modifier = Modifier.aloPremiumFrame(if (post.author.isChannel) 0 else post.author.id, 42.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 2.dp)
                            .clickable(onClick = onAuthor),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        AloPremiumName(
                            text = post.author.name,
                            userId = if (post.author.isChannel) 0 else post.author.id,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                        if (post.author.verified) {
                            Icon(Icons.Rounded.Verified, null, tint = AloNativeAppAccent, modifier = Modifier.size(14.dp))
                        }
                        AloPremiumMark(if (post.author.isChannel) 0 else post.author.id, 14.dp)
                        Text(
                            text = listOf(
                                post.author.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                                formatPostTimestamp(post.createdAt),
                                if (post.editedAt.isNotBlank()) "изменено" else null
                            ).filterNotNull().joinToString(" · "),
                            color = AloNativeAppMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // iOS: a 44pt button with -10 vertical and trailing padding, so the header row is 24pt tall and the dots' centre is 12pt from the row's end.
                    Box(Modifier.size(width = AloTimelineMetrics.MenuWidth, height = AloTimelineMetrics.HeaderHeight)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = AloTimelineMetrics.MenuShift)
                                .requiredSize(44.dp)
                                .clip(CircleShape)
                                .clickable { menuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "Действия с постом", tint = AloNativeAppMuted, modifier = Modifier.size(22.dp))
                        }
                        AloDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = AloNativeAppSurface
                        ) {
                            AloMenuItem("Скопировать ссылку", Icons.Rounded.Link) {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(AloDeepLinkTarget.postUrl(post)))
                            }
                            val editPost = LocalAloPostEditor.current
                            if (isMine && editPost != null) {
                                AloMenuItem("Изменить", Icons.Rounded.Edit) {
                                    menuOpen = false
                                    editPost(post)
                                }
                            }
                            if (isMine && onDelete != null) {
                                AloMenuItem("Удалить пост", Icons.Rounded.Delete, destructive = true) {
                                    menuOpen = false
                                    confirmsDelete = true
                                }
                            } else if (onReaderAction != null) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                if (post.feedReason.isNotBlank()) {
                                    AloMenuItem("Почему я это вижу", Icons.Rounded.Info) {
                                        menuOpen = false; onReaderAction(AloPostReaderAction.WhyShown)
                                    }
                                }
                                AloMenuItem("Не интересно", Icons.Rounded.ThumbDown) {
                                    menuOpen = false; onReaderAction(AloPostReaderAction.NotInterested)
                                }
                                AloMenuItem("Скрыть", Icons.Rounded.VisibilityOff) {
                                    menuOpen = false; onReaderAction(AloPostReaderAction.Hide)
                                }
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                AloMenuItem("Пожаловаться", Icons.Rounded.Report, destructive = true) {
                                    menuOpen = false; onReaderAction(AloPostReaderAction.Report)
                                }
                            }
                        }
                    }
                }
                if (!expanded) content()
            }
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 2.dp)) { content() }
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.15f))
    }
    if (confirmsDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmsDelete = false },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text("Удалить пост?", color = AloNativeAppText) },
            confirmButton = {
                TextButton(onClick = { confirmsDelete = false; onDelete?.invoke() }) {
                    Text("Удалить пост", color = Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmsDelete = false }) { Text("Отмена", color = AloNativeAppMuted) } }
        )
    }
}

@Composable
internal fun AloMenuItem(title: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) Color(0xFFFF453A) else AloNativeAppText
    DropdownMenuItem(
        text = { Text(title, color = color) },
        leadingIcon = { Icon(icon, null, tint = color) },
        onClick = onClick
    )
}

/** Like first, then comment and repost; views on the trailing edge (iOS's AloPostActionLabel). */
@Composable
private fun AloPostActionLabel(
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = AloNativeAppAccent,
    enabled: Boolean = true,
    alignEnd: Boolean = false,
    onClick: (() -> Unit)?
) {
    val color = if (selected) tint else AloNativeAppMuted
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // iOS's 17–19pt symbols draw about 21pt; Material glyphs need a 22dp box for the same weight.
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(4.dp))
        Text(formatCompactCount(count), color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * iOS's AloPostMediaGrid: one picture at its own ratio within Telegram's limits (3:4 … 2:1), and an
 * album of up to ten on Telegram's mosaic, every photo close to its own proportions. A photo the
 * server sent no size for is laid out again once it has loaded.
 */
@Composable
internal fun AloTimelineMediaGrid(media: List<AloMediaItem>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    val shape = AloSquircle(14.dp)
    val shown = media.take(AloMediaMosaic.MAXIMUM_ITEMS)
    var sizeRevision by remember(media) { mutableIntStateOf(0) }
    fun key(item: AloMediaItem) = item.posterUrl.ifBlank { item.url }
    fun knownSize(item: AloMediaItem): Pair<Int, Int>? =
        if (item.width > 0 && item.height > 0) item.width to item.height else AloCachedImageSize[key(item)]
    fun onLoaded(item: AloMediaItem): ((Int, Int) -> Unit)? =
        if (item.width > 0 && item.height > 0) null else { w, h ->
            if (AloCachedImageSize[key(item)] != (w to h)) {
                AloCachedImageSize[key(item)] = w to h
                sizeRevision += 1
            }
        }
    Box(modifier = modifier.fillMaxWidth().clip(shape)) {
        @Suppress("UNUSED_EXPRESSION") sizeRevision
        if (shown.size == 1) {
            val item = shown[0]
            val ratio = knownSize(item)?.let { (w, h) -> AloPostMediaSizing.ratio(w, h) } ?: AloPostMediaSizing.DEFAULT_RATIO
            AloTimelineMediaCell(item, Modifier.fillMaxWidth().aspectRatio(ratio), onImageSize = onLoaded(item)) { onOpen(0) }
        } else if (shown.isNotEmpty()) {
            val ratios = shown.map { item -> knownSize(item)?.let { (w, h) -> w.toFloat() / h } ?: 1f }
            Layout(
                content = {
                    shown.forEachIndexed { index, item ->
                        AloTimelineMediaCell(item, Modifier, onImageSize = onLoaded(item)) { onOpen(index) }
                    }
                }
            ) { measurables, constraints ->
                val widthPx = constraints.maxWidth
                val (frames, size) = AloPostMediaSizing.album(ratios, widthPx / density)
                val placeables = measurables.mapIndexed { index, measurable ->
                    val frame = frames.getOrNull(index) ?: AloMosaicFrame(0f, 0f, 0f, 0f)
                    measurable.measure(Constraints.fixed((frame.width * density).roundToInt().coerceAtLeast(0),
                        (frame.height * density).roundToInt().coerceAtLeast(0)))
                }
                layout(widthPx, (size.second * density).roundToInt()) {
                    placeables.forEachIndexed { index, placeable ->
                        val frame = frames.getOrNull(index) ?: return@forEachIndexed
                        placeable.place((frame.x * density).roundToInt(), (frame.y * density).roundToInt())
                    }
                }
            }
        }
    }
}

/** Decoded sizes of post images, so a revisited post keeps its proportions without a jump. */
internal object AloCachedImageSize {
    private val sizes = object : LinkedHashMap<String, Pair<Int, Int>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?) = size > 512
    }
    @Synchronized operator fun get(url: String): Pair<Int, Int>? = sizes[url]
    @Synchronized operator fun set(url: String, size: Pair<Int, Int>) { sizes[url] = size }
}

@Composable
private fun AloTimelineMediaCell(
    item: AloMediaItem,
    modifier: Modifier,
    onImageSize: ((Int, Int) -> Unit)? = null,
    onOpen: () -> Unit
) {
    val isVideo = item.type.startsWith("video")
    val originTracker = rememberAloMediaOriginTracker()
    Box(
        modifier = modifier
            .trackMediaOrigin(originTracker)
            .background(AloNativeAppBackground)
            .clickable {
                // The viewer grows out of this picture, which is clipped to the post's own corners.
                if (!isVideo) originTracker.offer()
                onOpen()
            },
        contentAlignment = Alignment.Center
    ) {
        AloRemoteMediaImage(
            model = if (isVideo) item.posterUrl.ifBlank { item.url } else item.url,
            contentDescription = item.alt.ifBlank { "Изображение в посте" },
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            isVideo = isVideo,
            onImageSize = onImageSize
        )
        if (isVideo) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** What stays where a hidden post was until the next refresh, with «Вернуть». */
@Composable
internal fun AloHiddenPostRow(reason: String, onUndo: () -> Unit) {
    val title = when (reason) {
        "not_interested" -> "Будем реже показывать такие публикации"
        "report" -> "Спасибо! Жалоба отправлена модераторам"
        else -> "Публикация скрыта"
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(AloNativeAppSurfaceRaised), contentAlignment = Alignment.Center) {
                Icon(if (reason == "report") Icons.Rounded.CheckCircle else Icons.Rounded.VisibilityOff, null, tint = AloNativeAppMuted, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (reason == "not_interested") "Публикация скрыта из ленты." else "Её больше не будет в вашей ленте.",
                    color = AloNativeAppMuted,
                    fontSize = 13.sp
                )
            }
            TextButton(onClick = onUndo) { Text("Вернуть", color = AloNativeAppAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.15f))
    }
}

/** Alo's own ad: labelled «Реклама», with why / hide / report. */
@Composable
internal fun AloSponsoredCard(ad: AloAd, onOpen: () -> Unit, onWhy: () -> Unit, onHide: () -> Unit, onReport: () -> Unit) {
    var menuOpen by remember(ad.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(16.dp))
            .background(AloNativeAppSurfaceRaised.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ad.label, color = AloNativeAppAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(" · ${ad.advertiser}", color = AloNativeAppMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Box {
                Box(Modifier.size(36.dp, 28.dp).clickable { menuOpen = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, "Действия с рекламой", tint = AloNativeAppMuted, modifier = Modifier.size(18.dp))
                }
                AloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = AloNativeAppSurface) {
                    AloMenuItem("Почему я это вижу", Icons.Rounded.Info) { menuOpen = false; onWhy() }
                    AloMenuItem("Скрыть рекламу", Icons.Rounded.VisibilityOff) { menuOpen = false; onHide() }
                    AloMenuItem("Пожаловаться", Icons.Rounded.Report, destructive = true) { menuOpen = false; onReport() }
                }
            }
        }
        if (ad.title.isNotBlank()) Text(ad.title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(ad.body, color = AloNativeAppText, fontSize = 15.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(AloSquircle(10.dp))
                .background(AloNativeAppAccent.copy(alpha = 0.14f))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            Text(ad.ctaLabel, color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Instagram's «Почему вы видите эту публикацию». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AloWhyShownSheet(post: AloPost, onDismiss: () -> Unit, onHide: (String) -> Unit) {
    val handle = post.author.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: post.author.name
    val rows = buildList {
        when (post.feedReason) {
            "own" -> add(Triple(Icons.Rounded.People, "Это ваша публикация", "Свои публикации вы видите первыми."))
            "own_channel" -> add(Triple(Icons.Rounded.People, "Это ваш канал «${post.author.name}»", "Публикации ваших каналов стоят в ленте выше остальных."))
            "subscribed_channel" -> add(Triple(Icons.Rounded.People, "Вы подписаны на канал «${post.author.name}»", "Каналы из подписок идут выше рекомендаций."))
            "friend" -> add(Triple(Icons.Rounded.People, "Вы подписаны на $handle", "Люди из ваших подписок идут выше рекомендаций."))
            else -> {
                add(Triple(Icons.Rounded.Whatshot, "Популярно в Alo",
                    "${formatCompactCount(post.viewCount)} просмотров · ${formatCompactCount(post.likeCount)} «Нравится» · ${formatCompactCount(post.commentCount)} комментариев"))
                add(Triple(Icons.Rounded.People, "Вы не подписаны на $handle", "Это рекомендация. Подпишитесь, чтобы видеть публикации чаще."))
            }
        }
        add(Triple(Icons.Rounded.Schedule, "Опубликовано ${formatPostTimestamp(post.createdAt)}", "Среди похожих свежие публикации стоят выше."))
    }
    AloModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AloNativeAppBackground) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AvatarBadge(name = post.author.name, avatarUrl = post.author.avatarUrl, verified = false, size = 64.dp, colorSeed = post.author.id.toString())
            Text("Почему вы видите эту публикацию", color = AloNativeAppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Лента складывается из ваших подписок и того, что сейчас популярно в Alo.", color = AloNativeAppMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
            AloSheetGroup {
                rows.forEachIndexed { index, (icon, title, detail) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(icon, null, tint = AloNativeAppText, modifier = Modifier.size(22.dp))
                        Column {
                            Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(detail, color = AloNativeAppMuted, fontSize = 14.sp)
                        }
                    }
                    if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp), color = Color.White.copy(alpha = 0.09f))
                }
            }
            if (post.feedReason != "own" && post.feedReason != "own_channel") {
                AloSheetGroup {
                    AloSheetAction("Не интересно", "Скроем публикацию и реже будем рекомендовать $handle", Icons.Rounded.ThumbDown) { onHide("not_interested") }
                    HorizontalDivider(Modifier.padding(start = 50.dp), color = Color.White.copy(alpha = 0.09f))
                    AloSheetAction("Скрыть публикацию", "Только эту, автор останется в ленте", Icons.Rounded.VisibilityOff) { onHide("hide") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AloSheetGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.7f))) { content() }
}

@Composable
private fun AloSheetAction(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    AloSheetItem(title = title, subtitle = detail, icon = icon, bleed = 16.dp, onClick = onClick)
}

/** Telegram's report reasons, kept as text for moderators. */
internal val AloReportReasons = listOf(
    "Спам", "Насилие", "Жестокое обращение с детьми", "Порнография", "Нарушение авторских прав",
    "Личные данные", "Незаконные товары", "Мошенничество", "Другое"
)

/** Telegram's report flow: a reason, an optional note, then a thank-you. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AloReportSheet(title: String, onDismiss: () -> Unit, onSend: suspend (String, String) -> String?) {
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(request) {
        val chosen = reason ?: return@LaunchedEffect
        if (request == 0) return@LaunchedEffect
        sending = true
        val failure = onSend(chosen, details.trim())
        sending = false
        if (failure == null) sent = true else error = failure
    }
    AloModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AloNativeAppBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                sent -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF30D158), modifier = Modifier.size(58.dp))
                        Text("Спасибо! Жалоба отправлена", color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium)
                        Text("Модераторы проверят её и примут меры, если найдут нарушение.", color = AloNativeAppMuted,
                            fontSize = 15.sp, textAlign = TextAlign.Center)
                        AloPrimaryPillButton("Готово", onClick = onDismiss)
                    }
                }
                reason == null -> {
                    AloSheetTitle(title, bleed = 16.dp)
                    Text("Что не так?", color = AloNativeAppMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 5.dp))
                    Column(Modifier.fillMaxWidth()) {
                        AloReportReasons.forEach { item -> AloSheetItem(title = item, bleed = 16.dp, onClick = { reason = item }) }
                    }
                    Text("Жалоба анонимна: автор не узнает, кто её отправил.", color = AloNativeAppMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 5.dp, bottom = 8.dp))
                }
                else -> {
                    Text(reason.orEmpty(), color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(500) },
                        placeholder = { Text(if (reason == "Другое") "Опишите, что не так" else "Подробности (необязательно)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AloNativeAppText, unfocusedTextColor = AloNativeAppText,
                            focusedBorderColor = AloNativeAppAccent, unfocusedBorderColor = AloNativeAppBorder
                        )
                    )
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF453A), fontSize = 14.sp)
                    AloPrimaryPillButton(
                        if (sending) "Отправка…" else "Отправить жалобу",
                        enabled = !sending && (reason != "Другое" || details.trim().length >= 3)
                    ) { error = ""; request++ }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun AloPrimaryPillButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(AloSquircle(26.dp))
            .background(if (enabled) AloNativeAppAccent else AloNativeAppSurfaceRaised)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (enabled) Color.White else AloNativeAppMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** iOS's floating «Для вас / Подписки» oval: glass with a sliding selection. */
@Composable
internal fun AloFeedModePill(feedMode: AloFeedMode, backdrop: AloGlassBackdrop, onSelectMode: (AloFeedMode) -> Unit) {
    val shape = AloSquircle(22.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .padding(horizontal = AloScreenChrome.SideInset)
            .fillMaxWidth()
            .height(AloScreenChrome.FieldHeight)
            .aloLiquidGlass(shape, tint = AloGlassOverShadeTint, backdrop = backdrop)
            .padding(3.dp)
    ) {
        val half = maxWidth / 2
        val offset by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (feedMode == AloFeedMode.Subscriptions) half else 0.dp,
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 520f),
            label = "feedModePill"
        )
        Box(
            Modifier
                // An offset, not a start padding: the spring overshoots 0 on the way back and a negative padding throws.
                .offset(x = offset)
                .width(half)
                .fillMaxSize()
                .clip(AloSquircle(19.dp))
                .background(Color.White.copy(alpha = 0.14f))
        )
        Row(Modifier.fillMaxSize()) {
            listOf(AloFeedMode.Popular to "Для вас", AloFeedMode.Subscriptions to "Подписки").forEach { (mode, title) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(AloSquircle(19.dp))
                        .clickable { onSelectMode(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        color = if (feedMode == mode) AloNativeAppText else AloNativeAppMuted,
                        fontSize = 15.sp,
                        fontWeight = if (feedMode == mode) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
