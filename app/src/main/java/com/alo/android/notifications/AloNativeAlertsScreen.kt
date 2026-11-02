package com.alo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS's AlertsView: «Уведомления» with the settings gear, «Все / Ответы / Реакции», days as sections,
// an unread dot that stays for the visit, the post's text and photo, friend requests and appeals.

private enum class AloNotificationFilter(val title: String) {
    All("Все"), Replies("Ответы"), Reactions("Реакции");

    fun includes(item: AloNotificationItem): Boolean = when (this) {
        All -> true
        Replies -> item.type in setOf("comment", "reply", "comment_reply", "message")
        Reactions -> item.type in setOf("like", "comment_like", "repost", "reaction")
    }
}

private data class AloNotificationSection(val title: String, val items: List<AloNotificationItem>)

private fun groupedNotifications(items: List<AloNotificationItem>, filter: AloNotificationFilter): List<AloNotificationSection> =
    items.filter(filter::includes)
        .sortedByDescending { it.createdAt }
        .groupBy { formatMessageDateLabel(it.createdAt) }
        .map { (title, rows) -> AloNotificationSection(title, rows) }

@Composable
internal fun AlertsScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit
) {
    var filterName by rememberSaveable { mutableStateOf(AloNotificationFilter.All.name) }
    val filter = AloNotificationFilter.valueOf(filterName)
    // Acknowledge on the server at once, but keep the dots for this visit, as iOS does.
    var initiallyUnread by remember { mutableStateOf(emptySet<Int>()) }
    var appealReportId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(data.notifications.map { it.id }) {
        initiallyUnread = initiallyUnread + data.notifications.filter { !it.isRead }.map { it.id }
        onAction(AloNativeAppAction.MarkAllNotificationsRead)
    }
    val posts = remember(data) { (data.feedPopular + data.feedSubscriptions + data.profilePosts).associateBy { it.id } }
    val backdrop = rememberAloGlassBackdrop()
    var headerHeight by remember { mutableStateOf(120.dp) }
    val density = LocalDensity.current

    AloNaturalLineHeight {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = filter,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
            label = "alertsFilter",
            modifier = Modifier.fillMaxSize().aloGlassSource(backdrop)
        ) { shown ->
            val sections = groupedNotifications(data.notifications, shown)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = headerHeight + 8.dp, bottom = AloBottomBarOverlayPadding)
            ) {
                if (sections.isEmpty()) {
                    item(key = "empty") {
                        AloUnavailableView(
                            Icons.Outlined.NotificationsNone,
                            "Пока нет уведомлений",
                            if (shown == AloNotificationFilter.All) "Новые события появятся здесь." else "В этой категории пока нет событий.",
                            Modifier.padding(top = 48.dp)
                        )
                    }
                }
                sections.forEach { section ->
                    item(key = "section-${section.title}") {
                        Text(
                            section.title, color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)
                        )
                    }
                    items(section.items, key = { it.id }) { item ->
                        AloNotificationRow(
                            item = item,
                            post = posts[item.entityId].takeIf { item.entityType == "post" },
                            isUnread = item.id in initiallyUnread,
                            onAction = onAction,
                            onAppeal = { appealReportId = item.entityId }
                        )
                        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
                    }
                }
                aloLoadMoreItem(
                    key = "notifications-more",
                    hasMore = AloPagedList.Notifications !in state.pagedListsEnded,
                    isLoading = AloPagedList.Notifications in state.pagedListsLoading,
                    loaded = data.notifications.size,
                    onNeedMore = { onAction(AloNativeAppAction.LoadMore(AloPagedList.Notifications)) }
                )
            }
        }

        // The title and the filters float over the list, which fades under them.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to AloNativeAppBackground, 0.6f to AloNativeAppBackground.copy(alpha = 0.9f), 1f to Color.Transparent))
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .padding(bottom = 8.dp)
        ) {
            AloSocialHeader(
                title = "Уведомления",
                subtitle = if (state.isRefreshing) "Обновление…" else "",
                settingsLabel = "Настройки уведомлений",
                backdrop = backdrop,
                onSettings = { onAction(AloNativeAppAction.OpenNotificationSettings) }
            )
            Spacer(Modifier.height(AloScreenChrome.FieldGap))
            AloSocialSegments(
                titles = AloNotificationFilter.entries.map { it.title },
                selected = filter.ordinal,
                backdrop = backdrop,
                onSelect = { filterName = AloNotificationFilter.entries[it].name },
                modifier = Modifier.padding(horizontal = AloScreenChrome.SideInset)
            )
        }
    }
    }

    appealReportId?.let { reportId ->
        val moderation = LocalAloModeration.current
        AloAppealSheet(title = "Обжалование", onDismiss = { appealReportId = null }) { text ->
            moderation?.appealDecision(reportId, text) ?: error("Нет связи с сервером.")
        }
    }
}

/** iOS's AloSocialHeader: a 28pt title, an optional status beside it, and a glass settings button. */
@Composable
internal fun AloSocialHeader(title: String, subtitle: String, settingsLabel: String, backdrop: AloGlassBackdrop, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight).padding(start = AloScreenChrome.SideInset, end = AloScreenChrome.ActionBoxEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = AloNativeAppText, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onSettings).semantics { contentDescription = settingsLabel }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(38.dp).aloLiquidGlass(CircleShape, backdrop = backdrop),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Settings, null, tint = AloNativeAppText, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** iOS's AloSocialSegments: a 44pt glass capsule with a sliding glass selection. */
@Composable
internal fun AloSocialSegments(titles: List<String>, selected: Int, backdrop: AloGlassBackdrop, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .aloLiquidGlass(shape, backdrop = backdrop)
    ) {
        val part = maxWidth / titles.size
        val offset by animateDpAsState(part * selected, spring(dampingRatio = 1f, stiffness = 420f), label = "socialSegment")
        Box(
            Modifier.offset(x = offset).width(part).fillMaxHeight().padding(4.dp).clip(shape)
                .background(Color.White.copy(alpha = 0.12f)).border(0.5.dp, Color.White.copy(alpha = 0.16f), shape)
        )
        Row(Modifier.fillMaxSize()) {
            titles.forEachIndexed { index, title ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).clickable { onSelect(index) }.semantics { this.selected = index == selected },
                    contentAlignment = Alignment.Center
                ) {
                    Text(title, color = if (index == selected) AloNativeAppText else AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloNotificationRow(
    item: AloNotificationItem,
    post: AloPost?,
    isUnread: Boolean,
    onAction: (AloNativeAppAction) -> Unit,
    onAppeal: () -> Unit
) {
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    fun open() {
        val actor = item.actor
        when {
            item.entityType == "post" && item.entityId > 0 -> onAction(AloNativeAppAction.OpenComments(item.entityId))
            actor != null && item.type == "message" -> {
                onAction(AloNativeAppAction.SelectTab(AloAppTab.Messages))
                onAction(AloNativeAppAction.SelectConversation(actor.id))
            }
            actor != null -> onAction(AloNativeAppAction.OpenViewedProfile(AloProfileTarget.User(actor.id)))
        }
    }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.padding(top = 20.dp).size(6.dp).clip(CircleShape).background(if (isUnread) AloNativeAppAccent else Color.Transparent))
            Box {
                AvatarBadge(
                    name = item.actor?.name ?: "Alo", avatarUrl = item.actor?.avatarUrl.orEmpty(), verified = false, size = 46.dp,
                    colorSeed = item.actor?.id?.toString() ?: "Alo",
                    onClick = item.actor?.let { actor -> { onAction(AloNativeAppAction.OpenViewedProfile(AloProfileTarget.User(actor.id))) } }
                )
                if (item.type == "like" || item.type == "comment_like") {
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).clip(CircleShape).background(AloNativeAppBackground).padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFFF3361), modifier = Modifier.size(15.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .combinedClickable(onClick = ::open, onLongClick = { menuOpen = true }),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        buildAnnotatedString {
                            val actor = item.actor
                            if (actor != null) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(actor.name) }
                                append(" ")
                            }
                            append(item.text)
                        },
                        color = AloNativeAppText, fontSize = 16.sp
                    )
                    if (post != null && post.body.isNotEmpty()) {
                        Text(post.body, color = AloNativeAppMuted, fontSize = 15.sp, maxLines = 2)
                    }
                    Text(
                        if (formatMessageDateLabel(item.createdAt) == "Вчера") "Вчера" else formatPostTimestamp(item.createdAt),
                        color = AloNativeAppMuted, fontSize = 12.sp
                    )
                }
                // A moderation decision about you can be appealed from its notice.
                if (item.entityType == "moderation" && item.entityId > 0) {
                    AloRequestButton("Обжаловать", prominent = false, onClick = onAppeal)
                }
                if (item.type == "friend_request" && item.entityType == "friend_request" && item.entityId > 0 && item.friendRequestActionable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AloRequestButton("Принять", prominent = true) { onAction(AloNativeAppAction.RespondFriendRequest(item.entityId, true)) }
                        AloRequestButton("Отклонить", prominent = false) { onAction(AloNativeAppAction.RespondFriendRequest(item.entityId, false)) }
                    }
                }
            }
            post?.media?.firstOrNull()?.let { media ->
                AloRemoteMediaImage(
                    model = media.posterUrl.ifBlank { media.url },
                    contentDescription = "Открыть публикацию",
                    modifier = Modifier.size(48.dp).clip(AloSquircle(8.dp)).background(AloNativeAppSurfaceRaised).clickable(onClick = ::open),
                    contentScale = ContentScale.Crop,
                    isVideo = media.type.startsWith("video")
                )
            }
        }
        AloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = AloNativeAppSurface) {
            AloMenuItem("Удалить уведомление", Icons.Outlined.Delete, destructive = true) {
                menuOpen = false
                onAction(AloNativeAppAction.DeleteNotification(item.id))
            }
        }
    }
}

/** iOS's AloSocialRequestButtonStyle: a 32pt button inside a 44pt touch target. */
@Composable
private fun AloRequestButton(title: String, prominent: Boolean, onClick: () -> Unit) {
    val shape = AloSquircle(10.dp)
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.height(32.dp).clip(shape)
                // The app's accent, not the outgoing bubble's colour: in the light theme that is a pale #D8EBFF, and
                // the white «Принять» on it could not be read.
                .background(if (prominent) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                .then(if (prominent) Modifier else Modifier.border(1.dp, AloNativeAppBorder, shape))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(title, color = if (prominent) AloNativeAppButtonText else AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
