package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ChatPeerProfileTab {
    Photos,
    Video,
    Voice,
    Files
}

@Composable
internal fun ChatPeerProfilePane(
    ownerUserId: Int,
    detail: AloConversationDetail,
    currentAppearance: AloChatAppearance,
    isMuted: Boolean,
    isPeerBlocked: Boolean,
    /** Opened by the search button under a channel: the search sheet shows at once. */
    startsWithSearch: Boolean = false,
    onBack: () -> Unit,
    onInteractiveBack: () -> Unit,
    onOpenFullProfile: () -> Unit,
    onUpdateAppearance: (AloChatAppearance) -> Unit,
    onToggleMuted: () -> Unit,
    onUpdateDirectChatAutoDelete: (Int, Int) -> Unit,
    onUpdateGroupTitle: (Int, String) -> Unit,
    onPickGroupAvatar: (Int) -> Unit,
    onAddGroupMember: (Int, String) -> Unit,
    onRemoveGroupMember: (Int, Int) -> Unit,
    onLeaveGroupChat: (Int) -> Unit,
    onDeleteGroupChat: (Int) -> Unit,
    onUpdateGroupMemberRole: (Int, Int, String) -> Unit,
    groupManagement: AloGroupManagementSnapshot?,
    isGroupManagementLoading: Boolean,
    onLoadGroupManagement: (Int) -> Unit,
    onLoadEarlierGroupAdminLog: (Int) -> Unit,
    onUpdateGroupSettings: (Int, AloGroupPolicy) -> Unit,
    onCreateGroupTopic: (Int, String, String) -> Unit,
    onUpdateGroupTopic: (Int, Int, String?, String?, Boolean?, Boolean?) -> Unit,
    onDeleteGroupTopic: (Int, Int) -> Unit,
    onMergeGroupTopic: (Int, Int, Int) -> Unit,
    onReviewGroupJoinRequest: (Int, Int, Boolean) -> Unit,
    onCreateGroupInviteLink: (Int, String, Boolean, Int, String) -> Unit,
    onUpdateGroupInviteLink: (Int, Int, String, Boolean, Int, String) -> Unit,
    onRevokeGroupInviteLink: (Int, Int) -> Unit,
    onLoadGroupInviteStats: suspend (Int, Int) -> AloGroupInviteStats,
    onUpdateGroupMemberAccess: (
        Int,
        Int,
        String,
        String,
        Boolean,
        Map<String, Boolean>,
        Map<String, Boolean>,
        Boolean
    ) -> Unit,
    onTransferGroupOwnership: (Int, Int, String) -> Unit,
    onBanGroupMember: (Int, Int, String, String) -> Unit,
    onUnbanGroupMember: (Int, Int) -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartDirectVideoCall: () -> Unit,
    onStartVideoChat: () -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenAttachment: (AloChatMessage) -> Unit
) {
    val secretEntry = LocalAloSecretChatEntry.current
    var activeTab by rememberSaveable(detail.peerId) {
        mutableStateOf(ChatPeerProfileTab.Photos.name)
    }
    var isGroupSettingsOpen by rememberSaveable(detail.peerId) {
        mutableStateOf(false)
    }
    var isAppearanceSettingsOpen by rememberSaveable(detail.peerId) {
        mutableStateOf(false)
    }
    var isSearchSheetOpen by rememberSaveable(detail.peerId) {
        mutableStateOf(startsWithSearch)
    }
    var isAutoDeleteDialogOpen by rememberSaveable(detail.peerId) {
        mutableStateOf(false)
    }
    var pendingCommunityAction by rememberSaveable(detail.peerId, detail.roomId) {
        mutableStateOf<String?>(null)
    }
    val isOwner = detail.memberRole.equals("owner", ignoreCase = true)
    val memberAdminRights = detail.groupPolicy?.memberAdminRights.orEmpty()
    val canStartChannelVideoChat = isOwner || (
        detail.memberRole.equals("admin", ignoreCase = true) &&
            (memberAdminRights.isEmpty() || memberAdminRights["manageVideoChats"] == true)
        )
    val selectedTab = runCatching { ChatPeerProfileTab.valueOf(activeTab) }
        .getOrDefault(ChatPeerProfileTab.Photos)
    val isChannelConversation = detail.isChannelCommunity()
    val photos = remember(detail) {
        detail.items.asReversed().mapNotNull { message ->
            message.toMediaItemOrNull()
                ?.takeIf { it.type == "image" }
                ?.let { message.id to it }
        }
    }
    val videos = remember(detail) {
        detail.items.asReversed().mapNotNull { message ->
            message.toMediaItemOrNull()
                ?.takeIf { it.type == "video" && !isVideoCircleAttachment(message) }
                ?.let { message.id to it }
        }
    }
    val voices = remember(detail) {
        detail.items.asReversed().filter { isVoiceAttachment(it) || isVideoCircleAttachment(it) }
    }
    val files = remember(detail) {
        detail.items.asReversed().filter { message ->
            message.attachmentType.isNotBlank() &&
                !isVoiceAttachment(message) &&
                message.toMediaItemOrNull() == null
        }
    }
    val hasStandaloneProfile = detail.kind != "group" && detail.entityType != "group"
    val openFirstSharedTab = {
        activeTab = when {
            photos.isNotEmpty() -> ChatPeerProfileTab.Photos.name
            videos.isNotEmpty() -> ChatPeerProfileTab.Video.name
            voices.isNotEmpty() -> ChatPeerProfileTab.Voice.name
            else -> ChatPeerProfileTab.Files.name
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val viewConfiguration = LocalViewConfiguration.current
    val profileWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    var profileSwipeOffset by remember(detail.peerId, detail.roomId) { mutableFloatStateOf(0f) }
    var profileSettleJob by remember(detail.peerId, detail.roomId) { mutableStateOf<Job?>(null) }
    val profileScope = rememberCoroutineScope()
    val profileModalOpen = isGroupSettingsOpen || isAppearanceSettingsOpen ||
        isSearchSheetOpen || pendingCommunityAction != null

    BackHandler(enabled = profileModalOpen) {
        when {
            pendingCommunityAction != null -> pendingCommunityAction = null
            isSearchSheetOpen -> isSearchSheetOpen = false
            isAppearanceSettingsOpen -> isAppearanceSettingsOpen = false
            isGroupSettingsOpen -> isGroupSettingsOpen = false
        }
    }
    // The system back swipe moves the profile with the finger, as its own swipe below does (the chat shows under it); before,
    // it was the chat's BackHandler and the profile vanished at once. The back button sends no progress: the page pops.
    PredictiveBackHandler(enabled = !profileModalOpen) { events ->
        profileSettleJob?.cancel()
        var start = Float.NaN
        var followed = false
        try {
            events.collect { event ->
                if (start.isNaN()) start = event.touchX
                followed = true
                profileSwipeOffset = kotlin.math.abs(event.touchX - start).coerceIn(0f, profileWidthPx)
            }
            if (followed && profileSwipeOffset > 0f) {
                profileSettleJob = profileScope.launch {
                    animate(
                        initialValue = profileSwipeOffset,
                        targetValue = profileWidthPx,
                        animationSpec = tween(aloSwipeSettleMs(profileWidthPx - profileSwipeOffset, profileWidthPx, closing = true), easing = AloTelegramAccelerateDecelerate)
                    ) { value, _ -> profileSwipeOffset = value }
                    onInteractiveBack()
                }
            } else {
                onBack()
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            profileSettleJob = profileScope.launch {
                animate(
                    initialValue = profileSwipeOffset,
                    targetValue = 0f,
                    animationSpec = tween(aloSwipeSettleMs(profileSwipeOffset, profileWidthPx, closing = false), easing = AloTelegramAccelerateDecelerate)
                ) { value, _ -> profileSwipeOffset = value }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
            .graphicsLayer { translationX = profileSwipeOffset }
            .pointerInput(detail.peerId, detail.roomId, profileModalOpen) {
                if (profileModalOpen) return@pointerInput
                val startSlop = viewConfiguration.touchSlop
                val closeThreshold = maxOf(82.dp.toPx(), profileWidthPx * 0.24f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    profileSettleJob?.cancel()
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var pointerId = down.id
                    var totalX = 0f
                    var totalY = 0f
                    var started = false
                    var cancelled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull { it.pressed }
                            ?: break
                        pointerId = change.id
                        if (!change.pressed) break

                        val delta = change.positionChange()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        totalX += delta.x
                        totalY += delta.y

                        if (!started) {
                            val horizontalWins = totalX > startSlop &&
                                kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 1.12f
                            val verticalWins = kotlin.math.abs(totalY) > startSlop * 1.7f &&
                                kotlin.math.abs(totalY) > kotlin.math.abs(totalX)
                            if (verticalWins || totalX < -startSlop) {
                                cancelled = true
                                break
                            }
                            if (!horizontalWins) continue
                            started = true
                        }

                        change.consume()
                        profileSwipeOffset = (profileSwipeOffset + delta.x).coerceIn(0f, profileWidthPx)
                    }

                    if (started && !cancelled) {
                        val velocityX = velocityTracker.calculateVelocity().x
                        val projectedOffset = profileSwipeOffset + velocityX.coerceAtLeast(0f) * 0.14f
                        if (
                            profileSwipeOffset > closeThreshold ||
                            projectedOffset > profileWidthPx * 0.42f ||
                            velocityX > 1_250f
                        ) {
                            profileSettleJob = profileScope.launch {
                                animate(
                                    initialValue = profileSwipeOffset,
                                    targetValue = profileWidthPx,
                                    animationSpec = tween(190, easing = FastOutSlowInEasing)
                                ) { value, _ -> profileSwipeOffset = value }
                                onInteractiveBack()
                            }
                        } else {
                            profileSettleJob = profileScope.launch {
                                animate(
                                    initialValue = profileSwipeOffset,
                                    targetValue = 0f,
                                    animationSpec = tween(190, easing = FastOutSlowInEasing)
                                ) { value, _ -> profileSwipeOffset = value }
                            }
                        }
                    } else if (cancelled) {
                        profileSettleJob = profileScope.launch {
                            animate(
                                initialValue = profileSwipeOffset,
                                targetValue = 0f,
                                animationSpec = tween(170, easing = FastOutSlowInEasing)
                            ) { value, _ -> profileSwipeOffset = value }
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AloTelegramMetrics.Profile.HeaderHeight)
                    ) {
                        // The standard back button, as on every other full screen.
                        AloScreenBackButton(
                            onClick = onBack,
                            description = "Назад в чат",
                            // The list insets its rows by 8dp on every side; the button stands where it does on the other screens.
                            modifier = Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = (-8).dp)
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(top = 24.dp, start = 64.dp, end = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            AvatarBadge(
                                name = detail.title,
                                avatarUrl = detail.avatarUrl,
                                verified = detail.verified,
                                size = AloTelegramMetrics.Profile.AvatarSize,
                                colorSeed = detail.peerId.toString()
                            )
                            Text(
                                text = detail.title,
                                fontSize = AloTelegramMetrics.Profile.NameSize,
                                fontWeight = FontWeight.Medium,
                                color = AloNativeAppText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildConversationHeaderSubtitle(detail, false),
                                fontSize = AloTelegramMetrics.Profile.StatusSize,
                                color = AloNativeAppMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (detail.canManage || hasStandaloneProfile) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 10.dp, end = 4.dp),
                                shape = RoundedCornerShape(999.dp),
                                color = AloNativeAppSurfaceRaised
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            if (detail.canManage) isGroupSettingsOpen = true else onOpenFullProfile()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (detail.canManage) "Изм." else "Ещё",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = AloNativeAppText
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AloTelegramMetrics.Profile.ActionsSideInset - 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(AloTelegramMetrics.Profile.ActionsGap)
                    ) {
                        if (isChannelConversation) {
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = if (isMuted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                                label = if (isMuted) "Со звуком" else "Звук",
                                onClick = onToggleMuted
                            )
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.Search,
                                label = "Поиск",
                                onClick = { isSearchSheetOpen = true }
                            )
                            if (canStartChannelVideoChat) {
                                PeerProfileCompactAction(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Rounded.PlayArrow,
                                    label = "Видеочат",
                                    onClick = onStartVideoChat
                                )
                            }
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.AttachFile,
                                label = "Медиа",
                                onClick = openFirstSharedTab
                            )
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.MoreHoriz,
                                label = "Ещё",
                                onClick = {
                                    if (detail.canManage) isGroupSettingsOpen = true else isAppearanceSettingsOpen = true
                                }
                            )
                        } else {
                            if (!isPeerBlocked) {
                                PeerProfileCompactAction(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Rounded.Phone,
                                    label = "Звонок",
                                    onClick = onStartVoiceCall
                                )
                                PeerProfileCompactAction(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Rounded.Videocam,
                                    label = "Видео",
                                    onClick = onStartDirectVideoCall
                                )
                            }
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = if (isMuted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                                label = if (isMuted) "Со звуком" else "Звук",
                                onClick = onToggleMuted
                            )
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.Search,
                                label = "Поиск",
                                onClick = { isSearchSheetOpen = true }
                            )
                            PeerProfileCompactAction(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.MoreHoriz,
                                label = "Ещё",
                                onClick = if (hasStandaloneProfile) onOpenFullProfile else ({ isAppearanceSettingsOpen = true })
                            )
                        }
                    }
                }

                if (detail.username.isNotBlank() || detail.profileDescription.isNotBlank() || detail.kind == "direct") {
                    item {
                        PeerProfileInfoCard(
                            username = detail.username,
                            description = detail.profileDescription,
                            status = if (detail.kind == "direct") buildConversationHeaderSubtitle(detail, false) else "",
                            isChannel = isChannelConversation,
                            autoDeleteSeconds = detail.autoDeleteSeconds.takeIf {
                                detail.kind == "direct" && detail.entityType != "saved"
                            },
                            onAutoDeleteClick = { isAutoDeleteDialogOpen = true }
                        )
                    }
                }

                if (
                    secretEntry != null && secretEntry.available && detail.kind == "direct" &&
                    detail.entityType != "saved" && !isPeerBlocked && detail.peerId > 0 && detail.peerId != ownerUserId
                ) {
                    item {
                        PeerProfileAction(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AloTelegramMetrics.Profile.ActionsSideInset - 8.dp),
                            icon = Icons.Rounded.Lock,
                            label = "Начать секретный чат",
                            onClick = { secretEntry.start(detail.peerId) }
                        )
                    }
                }

                if (detail.roomId > 0) {
                    item {
                        if (isOwner) {
                            PeerProfileDangerAction(
                                icon = Icons.Rounded.Delete,
                                label = if (isChannelConversation) "Удалить канал" else "Удалить группу",
                                onClick = { pendingCommunityAction = "delete" }
                            )
                        } else {
                            PeerProfileDangerAction(
                                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                                label = if (isChannelConversation) "Покинуть канал" else "Покинуть группу",
                                onClick = { pendingCommunityAction = "leave" }
                            )
                        }
                    }
                }

                item {
                    Surface(
                        shape = AloSquircle(22.dp),
                        color = AloNativeAppBackgroundSecondary,
                        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.025f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                ChatPeerProfileTab.Photos to "Фото",
                                ChatPeerProfileTab.Video to "Видео",
                                ChatPeerProfileTab.Voice to "Голосовые",
                                ChatPeerProfileTab.Files to "Файлы"
                            ).forEach { (tab, label) ->
                                val selected = selectedTab == tab
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = AloSquircle(18.dp),
                                    color = if (selected) AloNativeAppSurfaceRaised else Color.Transparent
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(AloSquircle(18.dp))
                                            .clickable { activeTab = tab.name }
                                            .padding(horizontal = 4.dp, vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (selected) AloNativeAppText else AloNativeAppMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when (selectedTab) {
                    ChatPeerProfileTab.Photos -> {
                        if (photos.isEmpty()) {
                            item {
                                EmptyPanelBody(
                                    title = "Фотографий пока нет",
                                    message = if (isChannelConversation) {
                                        "Опубликованные в канале изображения будут здесь."
                                    } else {
                                        "Когда в чате появятся изображения, они будут здесь."
                                    }
                                )
                            }
                        } else {
                            items(photos.chunked(2)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { (_, media) ->
                                        AsyncImage(
                                            model = media.url,
                                            contentDescription = media.alt,
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.72f)
                                                .clip(AloSquircle(14.dp))
                                                .clickable { onOpenMedia(media) },
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    if (rowItems.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    ChatPeerProfileTab.Video -> {
                        if (videos.isEmpty()) {
                            item {
                                EmptyPanelBody(
                                    title = "Видео пока нет",
                                    message = if (isChannelConversation) {
                                        "Видео из публикаций канала появятся здесь."
                                    } else {
                                        "Видео из переписки появятся здесь."
                                    }
                                )
                            }
                        } else {
                            items(videos.chunked(2)) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { (_, media) ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.72f)
                                                .clip(AloSquircle(14.dp))
                                                .background(AloNativeAppSurfaceRaised)
                                                .clickable { onOpenMedia(media) }
                                        ) {
                                            AsyncImage(
                                                model = media.posterUrl.ifBlank { media.url },
                                                contentDescription = media.alt,
                                                modifier = Modifier.matchParentSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0x66000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (rowItems.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    ChatPeerProfileTab.Voice -> {
                        if (voices.isEmpty()) {
                            item {
                                EmptyPanelBody(
                                    title = "Голосовых пока нет",
                                    message = if (isChannelConversation) {
                                        "Голосовые публикации и кружки канала появятся здесь."
                                    } else {
                                        "Голосовые сообщения из этого чата появятся здесь."
                                    }
                                )
                            }
                        } else {
                            items(voices, key = { it.id }) { message ->
                                ChatPeerAttachmentRow(
                                    icon = if (isVideoCircleAttachment(message)) Icons.Rounded.PlayArrow else Icons.Rounded.Mic,
                                    title = if (isVideoCircleAttachment(message)) "Видео-кружок" else "Голосовое сообщение",
                                    subtitle = formatFeedDate(message.createdAt),
                                    onClick = {
                                        message.toMediaItemOrNull()?.let(onOpenMedia)
                                    }
                                )
                            }
                        }
                    }

                    ChatPeerProfileTab.Files -> {
                        if (files.isEmpty()) {
                            item {
                                EmptyPanelBody(
                                    title = "Файлов пока нет",
                                    message = if (isChannelConversation) {
                                        "Документы и остальные файлы канала появятся здесь."
                                    } else {
                                        "Файлы из переписки появятся здесь."
                                    }
                                )
                            }
                        } else {
                            items(files, key = { it.id }) { message ->
                                ChatPeerAttachmentRow(
                                    icon = Icons.Rounded.AttachFile,
                                    title = message.attachmentName.ifBlank { "Файл" },
                                    subtitle = formatFeedDate(message.createdAt),
                                    onClick = { onOpenAttachment(message) }
                                )
                            }
                        }
                    }
                }
        }

        if (isGroupSettingsOpen) {
            GroupChatSettingsSheet(
                detail = detail,
                onDismiss = { isGroupSettingsOpen = false },
                onPickAvatar = { onPickGroupAvatar(detail.roomId) },
                onSaveTitle = { title ->
                    onUpdateGroupTitle(detail.roomId, title)
                    isGroupSettingsOpen = false
                },
                onAddMember = { username -> onAddGroupMember(detail.roomId, username) },
                onRemoveMember = { userId -> onRemoveGroupMember(detail.roomId, userId) },
                onUpdateMemberRole = { userId, role -> onUpdateGroupMemberRole(detail.roomId, userId, role) },
                management = groupManagement,
                isManagementLoading = isGroupManagementLoading,
                onLoadManagement = { onLoadGroupManagement(detail.roomId) },
                onLoadEarlierAdminEvents = { onLoadEarlierGroupAdminLog(detail.roomId) },
                onUpdateSettings = { policy -> onUpdateGroupSettings(detail.roomId, policy) },
                onCreateTopic = { title, emoji ->
                    onCreateGroupTopic(detail.roomId, title, emoji)
                },
                onUpdateTopic = { topicId, title, emoji, isClosed, isHidden ->
                    onUpdateGroupTopic(detail.roomId, topicId, title, emoji, isClosed, isHidden)
                },
                onDeleteTopic = { topicId ->
                    onDeleteGroupTopic(detail.roomId, topicId)
                },
                onMergeTopic = { sourceTopicId, targetTopicId ->
                    onMergeGroupTopic(detail.roomId, sourceTopicId, targetTopicId)
                },
                onReviewJoinRequest = { userId, approve ->
                    onReviewGroupJoinRequest(detail.roomId, userId, approve)
                },
                onCreateInviteLink = { label, requiresApproval, usageLimit, expiresAt ->
                    onCreateGroupInviteLink(
                        detail.roomId,
                        label,
                        requiresApproval,
                        usageLimit,
                        expiresAt
                    )
                },
                onUpdateInviteLink = { inviteId, label, requiresApproval, usageLimit, expiresAt ->
                    onUpdateGroupInviteLink(
                        detail.roomId,
                        inviteId,
                        label,
                        requiresApproval,
                        usageLimit,
                        expiresAt
                    )
                },
                onRevokeInviteLink = { inviteId ->
                    onRevokeGroupInviteLink(detail.roomId, inviteId)
                },
                onLoadInviteStats = { inviteId ->
                    onLoadGroupInviteStats(detail.roomId, inviteId)
                },
                onUpdateMemberAccess = { userId, role, adminTitle, isAnonymous, rights, restrictions ->
                    onUpdateGroupMemberAccess(
                        detail.roomId,
                        userId,
                        role,
                        adminTitle,
                        isAnonymous,
                        rights,
                        restrictions,
                        detail.memberRole.equals("owner", ignoreCase = true)
                    )
                },
                onTransferOwnership = { userId, currentPassword ->
                    onTransferGroupOwnership(detail.roomId, userId, currentPassword)
                },
                onBanMember = { userId, reason, expiresAt ->
                    onBanGroupMember(detail.roomId, userId, reason, expiresAt)
                },
                onUnbanMember = { userId ->
                    onUnbanGroupMember(detail.roomId, userId)
                }
            )
        }
        if (isAutoDeleteDialogOpen) {
            ChatAutoDeleteDialog(
                currentSeconds = detail.autoDeleteSeconds,
                onDismiss = { isAutoDeleteDialogOpen = false },
                onSelect = { seconds ->
                    isAutoDeleteDialogOpen = false
                    onUpdateDirectChatAutoDelete(detail.peerId, seconds)
                }
            )
        }
        if (isSearchSheetOpen) {
            ChatPeerSearchSheet(
                ownerUserId = ownerUserId,
                detail = detail,
                onDismiss = { isSearchSheetOpen = false },
                onOpenMedia = onOpenMedia
            )
        }
        AloSwipeBackPage(visible = isAppearanceSettingsOpen, onClosed = { isAppearanceSettingsOpen = false }) { page ->
            ChatAppearanceScreen(
                currentAppearance = currentAppearance,
                onDismiss = page::close,
                onApply = { appearance ->
                    onUpdateAppearance(appearance)
                    page.close()
                }
            )
        }
    }

    pendingCommunityAction?.let { action ->
        val deleting = action == "delete"
        val entityName = if (isChannelConversation) "канал" else "группу"
        AlertDialog(
            onDismissRequest = { pendingCommunityAction = null },
            title = {
                Text(
                    text = if (deleting) {
                        "Удалить $entityName?"
                    } else {
                        "Покинуть $entityName?"
                    }
                )
            },
            text = {
                Text(
                    text = when {
                        deleting && isChannelConversation ->
                            "Канал, его публикации и список подписчиков будут удалены без возможности восстановления."
                        deleting ->
                            "Группа, её сообщения и список участников будут удалены без возможности восстановления."
                        isChannelConversation && detail.groupPolicy?.joinPolicy != "public" ->
                            "Чтобы вернуться в закрытый канал, потребуется новая ссылка-приглашение."
                        isChannelConversation ->
                            "Вы сможете снова подписаться на публичный канал."
                        else ->
                            "Сообщество исчезнет из списка ваших чатов."
                    },
                    color = AloNativeAppMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCommunityAction = null
                        if (deleting) onDeleteGroupChat(detail.roomId) else onLeaveGroupChat(detail.roomId)
                    }
                ) {
                    Text(
                        text = if (deleting) "Удалить" else "Покинуть",
                        color = Color(0xFFFF5364)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCommunityAction = null }) {
                    Text("Отмена")
                }
            },
            containerColor = AloNativeAppSurface
        )
    }
}

@Composable
private fun ChatPeerSearchSheet(
    ownerUserId: Int,
    detail: AloConversationDetail,
    onDismiss: () -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit
) {
    val context = LocalContext.current
    val cacheStore = remember(context) {
        AloOfflineCacheStore(
            context.applicationContext,
            context.getSharedPreferences("alo-android", android.content.Context.MODE_PRIVATE)
        )
    }
    var query by rememberSaveable(ownerUserId, detail.peerId, detail.roomId) { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    var matches by remember(ownerUserId, detail.peerId, detail.roomId) { mutableStateOf(emptyList<AloChatMessage>()) }
    LaunchedEffect(ownerUserId, detail.peerId, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        matches = withContext(Dispatchers.IO) {
            cacheStore.searchMessages(ownerUserId, detail.peerId, normalizedQuery, limit = 100)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clickable(onClick = {}),
            shape = AloSquircle(28.dp),
            color = AloNativeAppSurface,
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.035f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 42.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(AloNativeAppMuted.copy(alpha = 0.45f))
                )
                Text(
                    text = "Поиск в чате",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AloNativeAppText
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AloSquircle(18.dp),
                    color = AloNativeAppBackgroundSecondary,
                    border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.025f))
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 14.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AloNativeAppText,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        cursorBrush = SolidColor(AloNativeChatAccentBlue),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (query.isBlank()) {
                                    Text(
                                        text = if (detail.isChannelCommunity()) {
                                            "Публикации и файлы"
                                        } else {
                                            "Сообщения, файлы, участники"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AloNativeAppMuted
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                if (matches.isEmpty()) {
                    EmptyPanelBody(
                        title = "Ничего не найдено",
                        message = "Попробуй другой запрос."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(matches, key = { it.id }) { message ->
                            ChatPeerSearchResultRow(
                                message = message,
                                onOpenMedia = onOpenMedia
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPeerSearchResultRow(
    message: AloChatMessage,
    onOpenMedia: (AloMediaItem) -> Unit
) {
    val media = remember(message) { message.toMediaItemOrNull() }
    val title = message.body.ifBlank {
        message.attachmentName.ifBlank {
            when {
                isVoiceAttachment(message) -> "Голосовое сообщение"
                isVideoCircleAttachment(message) -> "Видео-кружок"
                media != null -> "Медиа"
                else -> "Сообщение"
            }
        }
    }
    val subtitle = buildString {
        append(formatFeedDate(message.createdAt))
        message.sender?.name?.takeIf { it.isNotBlank() }?.let { senderName ->
            append(" · ")
            append(senderName)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(16.dp))
            .clickable(enabled = media != null) {
                media?.let(onOpenMedia)
            }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AloNativeAppSurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (media != null) Icons.Rounded.AttachFile else Icons.Rounded.Search,
                contentDescription = null,
                tint = AloNativeAppMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = AloNativeAppMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun PeerProfileCompactAction(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // ProfileActionsView: a 54dp tile (radius 16) with a 24dp icon and the label (11sp medium) inside it.
    Surface(
        modifier = modifier.height(AloTelegramMetrics.Profile.ActionHeight).clip(AloSquircle(AloTelegramMetrics.Profile.ActionRadius))
            .alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
        shape = AloSquircle(AloTelegramMetrics.Profile.ActionRadius),
        color = AloNativeAppBackgroundSecondary
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = AloTelegramMetrics.Profile.ActionIconTop, start = 2.dp, end = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = AloNativeAppAccent, modifier = Modifier.size(AloTelegramMetrics.Profile.ActionIconSize))
            Spacer(Modifier.height(AloTelegramMetrics.Profile.ActionTextGap))
            Text(
                text = label,
                fontSize = AloTelegramMetrics.Profile.ActionTextSize,
                fontWeight = FontWeight.Medium,
                color = AloNativeAppAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PeerProfileInfoCard(
    username: String,
    description: String,
    status: String,
    isChannel: Boolean,
    autoDeleteSeconds: Int?,
    onAutoDeleteClick: () -> Unit
) {
    val normalizedUsername = username.trim().trimStart('@')
    Surface(
        shape = AloSquircle(22.dp),
        color = AloNativeAppBackgroundSecondary,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            if (normalizedUsername.isNotBlank()) {
                PeerProfileInfoRow(
                    title = if (isChannel) "ссылка" else "имя пользователя",
                    value = if (isChannel) {
                        AloServerConfiguration.publicLinkLabel(normalizedUsername)
                    } else {
                        "@$normalizedUsername"
                    },
                    accent = true
                )
            }
            if (description.isNotBlank()) {
                if (normalizedUsername.isNotBlank()) {
                    HorizontalDivider(color = AloNativeAppBorder.copy(alpha = AloNativeAppBorder.alpha * 0.7f))
                }
                PeerProfileInfoRow(title = "описание", value = description, accent = false)
            } else if (status.isNotBlank()) {
                if (normalizedUsername.isNotBlank()) {
                    HorizontalDivider(color = AloNativeAppBorder.copy(alpha = AloNativeAppBorder.alpha * 0.7f))
                }
                PeerProfileInfoRow(title = "статус", value = status, accent = false)
            }
            autoDeleteSeconds?.let { seconds ->
                if (normalizedUsername.isNotBlank() || description.isNotBlank() || status.isNotBlank()) {
                    HorizontalDivider(color = AloNativeAppBorder.copy(alpha = AloNativeAppBorder.alpha * 0.7f))
                }
                PeerProfileInfoActionRow(
                    title = "автоудаление сообщений",
                    value = chatAutoDeleteLabel(seconds),
                    onClick = onAutoDeleteClick
                )
            }
        }
    }
}

@Composable
private fun PeerProfileInfoActionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = AloNativeAppMuted)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = AloNativeAppText)
        }
        Text("Изменить", style = MaterialTheme.typography.labelMedium, color = AloNativeAppAccent)
    }
}

@Composable
private fun ChatAutoDeleteDialog(
    currentSeconds: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val choices = listOf(
        0 to "Выключено",
        86400 to "1 день",
        604800 to "1 неделя",
        2592000 to "1 месяц",
        7776000 to "3 месяца",
        31536000 to "1 год"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Автоудаление сообщений") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Настройка действует только на новые сообщения. Старая история останется без изменений.",
                    color = AloNativeAppMuted
                )
                choices.forEach { (seconds, label) ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(seconds) }
                    ) {
                        Text(
                            text = if (seconds == currentSeconds) "$label  ✓" else label,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (seconds == currentSeconds) AloNativeAppAccent else AloNativeAppText
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = AloNativeAppSurface
    )
}

private fun chatAutoDeleteLabel(seconds: Int): String = when (seconds) {
    86400 -> "1 день"
    604800 -> "1 неделя"
    2592000 -> "1 месяц"
    7776000 -> "3 месяца"
    31536000 -> "1 год"
    else -> "Выключено"
}

@Composable
private fun PeerProfileInfoRow(
    title: String,
    value: String,
    accent: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = AloNativeAppMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (accent) AloNativeAppAccent else AloNativeAppText
        )
    }
}

@Composable
private fun PeerProfileAction(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackgroundSecondary,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.025f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AloSquircle(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AloNativeAppText,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = AloNativeAppText
            )
        }
    }
}

@Composable
private fun PeerProfileDangerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackgroundSecondary,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.025f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AloSquircle(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFFF5364),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFFF5364)
            )
        }
    }
}

@Composable
private fun ChatPeerAttachmentRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.AttachFile,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(18.dp))
            .clickable(onClick = onClick)
            .background(AloNativeAppBackgroundSecondary)
            .border(1.dp, AloNativeAppBorder.copy(alpha = 0.025f), AloSquircle(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AloNativeAppMuted,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = AloNativeAppText
                )
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = AloNativeAppMuted
                )
            }
        }
        HorizontalDivider(color = AloNativeAppBorder.copy(alpha = 0.04f))
    }
}
