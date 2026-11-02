package com.alo.android

import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.border

import androidx.compose.ui.text.style.TextAlign


import androidx.compose.material.icons.rounded.Verified

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon

import androidx.compose.foundation.layout.size

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.clickable

import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.width

import androidx.compose.material.icons.rounded.History

import androidx.compose.material.icons.rounded.BarChart

import androidx.compose.material.icons.rounded.Tune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewedProfileOverlay(
    profile: AloViewedProfile?,
    isLoading: Boolean,
    errorMessage: String?,
    currentUserId: Int,
    pendingLikePostId: Int?,
    pendingRepostPostId: Int?,
    blockedUserIds: Set<Int>,
    blockActionUserId: Int?,
    onDismiss: () -> Unit,
    onOpenComments: (Int) -> Unit,
    onToggleRepost: (Int) -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onLike: (Int) -> Unit,
    onDeletePost: (Int) -> Unit,
    onUpdateChannelSettings: (Int, Boolean, Boolean, Boolean) -> Unit,
    onUpdateUserBlock: (Int, Boolean) -> Unit,
    onPrimaryAction: (AloViewedProfile) -> Unit,
    onMessageAction: (AloViewedProfile) -> Unit,
    /** The posts after the ones the profile came with: the end of the list asks for them. */
    hasMorePosts: Boolean = false,
    isLoadingMorePosts: Boolean = false,
    onLoadMorePosts: () -> Unit = {}
) {
    // Only the posts, under «Записи», as on the website (the owner, 2026-10-28: no «Посты / Нравится» switch).
    val profileTab = "posts"
    val tabsBackdrop = rememberAloGlassBackdrop()
    val listState = rememberLazyListState()
    val tabsPinned = rememberTabsPinned(listState, "profile-tabs", 1)
    // The tab change keeps the page where it is (see ProfileScreen): a floor under the body and a smooth park of the pinned switcher.
    val tabScope = rememberCoroutineScope()
    var tabsHeightPx by remember { mutableIntStateOf(0) }
    val tabScroll = rememberAloProfileTabScroll(
        listState = listState,
        tabsIndex = 1,
        topPadding = 4.dp,
        bottomPadding = 20.dp + 6.dp,
        tabsHeightPx = tabsHeightPx
    )
    val selectTab: (String) -> Unit = { _ -> }
    var avatarSheetOpen by remember(profile?.id, profile?.avatarGallery) { mutableStateOf(false) }
    var channelSettingsOpen by remember(profile?.id) { mutableStateOf(false) }
    var channelAdminLogOpen by remember(profile?.id) { mutableStateOf(false) }
    var channelStatsOpen by remember(profile?.id) { mutableStateOf(false) }
    var channelAdminLogFilter by rememberSaveable(profile?.id) { mutableStateOf("all") }
    var commentsEnabled by remember(profile?.id, profile?.commentsEnabled) {
        mutableStateOf(profile?.commentsEnabled ?: true)
    }
    var signMessages by remember(profile?.id, profile?.signMessages) {
        mutableStateOf(profile?.signMessages ?: false)
    }
    var showAuthorProfiles by remember(profile?.id, profile?.showAuthorProfiles) {
        mutableStateOf(profile?.showAuthorProfiles ?: false)
    }
    val isProfileBlocked = profile?.let {
        it.kind == "user" && AloUserBlockPolicy.isBlockedUser(it.id, blockedUserIds)
    } == true
    var pendingBlockChange by remember(profile?.id) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    var visible by remember { mutableStateOf(false) }
    val slideOffset by animateFloatAsState(
        targetValue = if (visible) 0f else screenWidthPx,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "viewedProfileSlide"
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    fun dismissWithAnimation() {
        visible = false
        scope.launch {
            delay(220)
            onDismiss()
        }
    }

    BackHandler {
        dismissWithAnimation()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = slideOffset
                shadowElevation = if (slideOffset > 0f && slideOffset < screenWidthPx) 18f else 0f
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AloNativeAppBackground
        ) {
            when {
                isLoading && profile == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AloNativeAppAccent)
                    }
                }

                profile == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        AloScreenBackButton(onClick = ::dismissWithAnimation)
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyPanelBody(
                                title = "Профиль недоступен",
                                message = errorMessage ?: "Не удалось загрузить данные аккаунта."
                            )
                        }
                    }
                }

                else -> {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .aloGlassSource(tabsBackdrop),
                        contentPadding = PaddingValues(start = 0.dp, end = 0.dp, top = 4.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // A community has the same profile page as a person (iOS's banner, avatar and name), with its own counts and tools.
                        item {
                            ViewedProfileHeroPanel(
                                profile = profile,
                                onBack = ::dismissWithAnimation,
                                onPrimaryAction = { onPrimaryAction(profile) },
                                onOpenAvatarSheet = { avatarSheetOpen = true },
                                isBlocked = isProfileBlocked,
                                isBlockActionInProgress = blockActionUserId == profile.id,
                                onToggleBlock = { pendingBlockChange = !isProfileBlocked },
                                onMessageAction = {
                                    visible = false
                                    scope.launch {
                                        delay(180)
                                        onMessageAction(profile)
                                    }
                                }
                            )
                        }

                        if (profile.kind == "channel" && profile.canManage) {
                            item {
                                // iOS's three bordered buttons: settings, statistics and the admin log.
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = AloNativeLayout.pageInset),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ChannelAdminButton(Icons.Rounded.Tune, "Управление", Modifier.weight(1f)) { channelSettingsOpen = true }
                                    ChannelAdminButton(Icons.Rounded.BarChart, "Статистика", Modifier.weight(1f)) { channelStatsOpen = true }
                                    ChannelAdminButton(Icons.Rounded.History, "Журнал", Modifier.weight(1f)) { channelAdminLogOpen = true }
                                }
                            }
                        }

                        // A channel shows only its publications, as iOS's ChannelProfileDetailView.
                        if (profile.kind != "channel") item(key = "profile-tabs") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { tabsHeightPx = it.height }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                ProfilePostsTitle()
                            }
                        }

                        if (!errorMessage.isNullOrBlank()) {
                            item {
                                InlineErrorPanel(
                                    message = errorMessage,
                                    onRetry = {}
                                )
                            }
                        }

                        item {
                            AloTabSwitchContent(profileTab, minHeight = tabScroll.minBody) { tab ->
                            if (!profile.canViewContent) {
                                EmptyPanelBody(
                                    title = "Профиль закрыт",
                                    message = "Посты и медиа доступны только друзьям."
                                )
                            } else if (profile.posts.isEmpty()) {
                                EmptyPanelBody(
                                    title = when (profile.kind) {
                                        "group" -> "В группе пока нет отдельного профиля постов"
                                        "channel" -> "Публикаций пока нет"
                                        else -> "Постов пока нет"
                                    },
                                    message = when (profile.kind) {
                                        "group" -> "Это экран участников и информации о чате."
                                        "channel" -> "Когда в канале появятся публикации, они будут здесь."
                                        else -> "Когда пользователь что-то опубликует, это появится здесь."
                                    }
                                )
                            } else {
                                PostsPanel(
                                    title = if (tab == "likes") "Нравится" else if (profile.kind == "channel") "Публикации" else "Посты",
                                    posts = if (tab == "likes") profile.posts.filter { it.viewerLiked } else profile.posts,
                                    currentUserId = currentUserId,
                                    pendingLikePostId = pendingLikePostId,
                                    pendingRepostPostId = pendingRepostPostId,
                                    emptyTitle = if (tab == "likes") "Нет понравившихся постов" else "Пока пусто",
                                    emptyMessage = if (tab == "likes") "Лайкнутые посты появятся здесь." else "Контент появится позже.",
                                    onOpenComments = { onOpenComments(it) },
                                    onToggleRepost = { onToggleRepost(it) },
                                    onOpenMedia = onOpenMedia,
                                    onOpenMediaGallery = onOpenMediaGallery,
                                    onLike = { onLike(it) },
                                    onDeletePost = onDeletePost
                                )
                            }
                            }
                        }
                        if (profile.canViewContent && profileTab != "likes") {
                            aloLoadMoreItem(
                                key = "viewed-more",
                                hasMore = hasMorePosts,
                                isLoading = isLoadingMorePosts,
                                loaded = profile.posts.size,
                                onNeedMore = onLoadMorePosts
                            )
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = false,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            ProfilePostsTabs(
                                postsTitle = "Посты",
                                selectedTab = profileTab,
                                onSelect = selectTab,
                                backdrop = tabsBackdrop
                            )
                        }
                    }
                    }
                }
            }
        }

        if (avatarSheetOpen && profile != null) {
            ProfileAvatarSheet(
                title = profile.title,
                avatarUrls = profile.avatarGallery.ifEmpty { listOf(profile.avatarUrl).filter { it.isNotBlank() } },
                canUpload = false,
                onDismiss = { avatarSheetOpen = false },
                onUploadNew = {},
                onOpenMediaGallery = { items, startIndex ->
                    avatarSheetOpen = false
                    onOpenMediaGallery(items, startIndex)
                }
            )
        }

        pendingBlockChange?.let { blocked ->
            val target = profile?.takeIf { it.kind == "user" }
            if (target != null) {
                AlertDialog(
                    onDismissRequest = { pendingBlockChange = null },
                    containerColor = AloNativeAppBackgroundSecondary,
                    title = {
                        Text(
                            text = if (blocked) "Заблокировать пользователя?" else "Разблокировать пользователя?",
                            color = AloNativeAppText
                        )
                    },
                    text = {
                        Text(
                            text = if (blocked) {
                                "Вы больше не сможете писать этому пользователю или звонить ему."
                            } else {
                                "Переписка и звонки снова будут доступны."
                            },
                            color = AloNativeAppMuted
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = blockActionUserId != target.id,
                            onClick = {
                                onUpdateUserBlock(target.id, blocked)
                                pendingBlockChange = null
                            }
                        ) {
                            Text(if (blocked) "Заблокировать" else "Разблокировать")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingBlockChange = null }) {
                            Text("Отмена", color = AloNativeAppMuted)
                        }
                    }
                )
            }
        }

        if (channelSettingsOpen && profile != null) {
            AlertDialog(
                onDismissRequest = { channelSettingsOpen = false },
                containerColor = AloNativeAppBackgroundSecondary,
                title = {
                    Text(
                        text = "Управление каналом",
                        color = AloNativeAppText
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChannelSettingToggle(
                            title = "Комментарии",
                            checked = commentsEnabled,
                            onCheckedChange = { commentsEnabled = it }
                        )
                        ChannelSettingToggle(
                            title = "Подписывать сообщения",
                            checked = signMessages,
                            onCheckedChange = {
                                signMessages = it
                                if (!it) showAuthorProfiles = false
                            }
                        )
                        ChannelSettingToggle(
                            title = "Показывать профиль автора",
                            checked = showAuthorProfiles,
                            enabled = signMessages,
                            onCheckedChange = { showAuthorProfiles = it }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onUpdateChannelSettings(
                                profile.id,
                                commentsEnabled,
                                signMessages,
                                showAuthorProfiles
                            )
                            channelSettingsOpen = false
                        }
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { channelSettingsOpen = false }) {
                        Text("Отмена", color = AloNativeAppMuted)
                    }
                }
            )
        }

        if (channelAdminLogOpen && profile != null) {
            val filteredAdminEvents = profile.adminEvents.filter { event ->
                when (channelAdminLogFilter) {
                    "comments" -> event.action == "post_comments_enabled" ||
                        event.action == "post_comments_disabled"
                    "moderators" -> event.action == "moderator_added" ||
                        event.action == "moderator_removed"
                    "posts" -> event.action == "post_published"
                    "settings" -> event.action == "channel_settings_updated"
                    else -> true
                }
            }
            AlertDialog(
                onDismissRequest = { channelAdminLogOpen = false },
                title = {
                    Text(
                        text = "Журнал действий",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "all" to "Все",
                                "posts" to "Посты",
                                "comments" to "Комментарии",
                                "moderators" to "Админы"
                            ).forEach { (value, title) ->
                                TextButton(onClick = { channelAdminLogFilter = value }) {
                                    Text(
                                        text = title,
                                        color = if (channelAdminLogFilter == value) {
                                            AloNativeAppAccent
                                        } else {
                                            AloNativeAppMuted
                                        }
                                    )
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (filteredAdminEvents.isEmpty()) {
                                item {
                                    Text(
                                        text = "Действий по этому фильтру пока нет.",
                                        color = AloNativeAppMuted
                                    )
                                }
                            } else {
                                items(
                                    items = filteredAdminEvents,
                                    key = { it.id }
                                ) { event ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = event.actor.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AloNativeAppText
                                        )
                                        Text(
                                            text = channelAdminActionTitle(event.action),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AloNativeAppMuted
                                        )
                                        Text(
                                            text = event.createdAt
                                                .take(16)
                                                .replace("T", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { channelAdminLogOpen = false }) {
                        Text("Готово")
                    }
                }
            )
        }
    if (channelStatsOpen && profile != null) {
        AloChannelStatsScreen(channelId = profile.id, posts = profile.posts, onDismiss = { channelStatsOpen = false })
    }
    }
}

/** A tinted capsule with a symbol over its title, as iOS's .bordered buttons in a row. */
@Composable
private fun ChannelAdminButton(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(AloNativeAppAccent.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AloNativeAppAccent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = AloNativeAppAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

private fun channelAdminActionTitle(action: String): String {
    return when (action) {
        "channel_created" -> "Создал(а) канал"
        "channel_settings_updated" -> "Изменил(а) настройки"
        "post_published" -> "Опубликовал(а) запись"
        "post_comments_enabled" -> "Открыл(а) комментарии"
        "post_comments_disabled" -> "Закрыл(а) комментарии"
        "moderator_added" -> "Назначил(а) администратора"
        "moderator_removed" -> "Снял(а) администратора"
        else -> action
    }
}

@Composable
private fun ChannelSettingToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = if (enabled) AloNativeAppText else AloNativeAppMuted
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
internal fun ViewedProfileMetricTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val isLightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Surface(
        modifier = modifier,
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackground.copy(alpha = if (isLightSurface) 0.26f else 0.16f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppMuted
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
        }
    }
}
