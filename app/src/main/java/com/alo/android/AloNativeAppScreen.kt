package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import livekit.org.webrtc.SurfaceViewRenderer as LiveKitSurfaceViewRenderer

internal val AloBottomBarOverlayPadding = 100.dp
/** The tab bar's height above the system navigation bar: 6dp top padding, 4dp margins and the 60dp capsule. */
internal val AloBottomBarFloatingHeight = 74.dp

@Composable
internal fun AloNativeAppScreen(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onLoadGroupInviteStats: suspend (Int, Int) -> AloGroupInviteStats,
    contentTools: AloContentTools? = null,
    onBindDirectCallLocalRenderer: (AloCallRenderer?) -> Unit = {},
    onBindDirectCallRemoteRenderer: (AloCallRenderer?) -> Unit = {},
    onUnbindDirectCallLocalRenderer: (AloCallRenderer) -> Unit = {},
    onUnbindDirectCallRemoteRenderer: (AloCallRenderer) -> Unit = {},
    onBindGroupCallLocalRenderer: (AloCallRenderer?) -> Unit = {},
    onBindGroupCallRemoteRenderer: (AloCallRenderer?) -> Unit = {},
    onUnbindGroupCallLocalRenderer: (AloCallRenderer) -> Unit = {},
    onUnbindGroupCallRemoteRenderer: (AloCallRenderer) -> Unit = {},
    onBindGroupSfuRenderer: (Int, LiveKitSurfaceViewRenderer?) -> Unit = { _, _ -> },
    onUnbindGroupSfuRenderer: (Int, LiveKitSurfaceViewRenderer) -> Unit = { _, _ -> }

) {
    // A post's «Изменить» opens its editor from any list that shows posts.
    AloPostEditHost(onAction = onAction) {
        AloNativeAppScreenContent(
            state = state,
            onAction = onAction,
            onLoadGroupInviteStats = onLoadGroupInviteStats,
            contentTools = contentTools,
            onBindDirectCallLocalRenderer = onBindDirectCallLocalRenderer,
            onBindDirectCallRemoteRenderer = onBindDirectCallRemoteRenderer,
            onUnbindDirectCallLocalRenderer = onUnbindDirectCallLocalRenderer,
            onUnbindDirectCallRemoteRenderer = onUnbindDirectCallRemoteRenderer,
            onBindGroupCallLocalRenderer = onBindGroupCallLocalRenderer,
            onBindGroupCallRemoteRenderer = onBindGroupCallRemoteRenderer,
            onUnbindGroupCallLocalRenderer = onUnbindGroupCallLocalRenderer,
            onUnbindGroupCallRemoteRenderer = onUnbindGroupCallRemoteRenderer,
            onBindGroupSfuRenderer = onBindGroupSfuRenderer,
            onUnbindGroupSfuRenderer = onUnbindGroupSfuRenderer
        )
    }
}

@Composable
private fun AloNativeAppScreenContent(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit,
    onLoadGroupInviteStats: suspend (Int, Int) -> AloGroupInviteStats,
    contentTools: AloContentTools? = null,
    onBindDirectCallLocalRenderer: (AloCallRenderer?) -> Unit = {},
    onBindDirectCallRemoteRenderer: (AloCallRenderer?) -> Unit = {},
    onUnbindDirectCallLocalRenderer: (AloCallRenderer) -> Unit = {},
    onUnbindDirectCallRemoteRenderer: (AloCallRenderer) -> Unit = {},
    onBindGroupCallLocalRenderer: (AloCallRenderer?) -> Unit = {},
    onBindGroupCallRemoteRenderer: (AloCallRenderer?) -> Unit = {},
    onUnbindGroupCallLocalRenderer: (AloCallRenderer) -> Unit = {},
    onUnbindGroupCallRemoteRenderer: (AloCallRenderer) -> Unit = {},
    onBindGroupSfuRenderer: (Int, LiveKitSurfaceViewRenderer?) -> Unit = { _, _ -> },
    onUnbindGroupSfuRenderer: (Int, LiveKitSurfaceViewRenderer) -> Unit = { _, _ -> }
) {
    val data = state.bootstrap
    var searchListOpen by remember { mutableStateOf(false) }
    val showBottomBar = data != null && !searchListOpen && !(state.activeTab == AloAppTab.Messages && (state.activeConversationPeerId > 0 || state.activeSecretChatId > 0 || state.isSelectingChats))
    // What the floating tab bar blurs: the screen under it.
    val glassBackdrop = rememberAloGlassBackdrop()
    var profileEditorOpen by remember { mutableStateOf(false) }
    val compactDirectCall = state.activeDirectCall?.takeIf { it.isMinimized }
    val compactGroupCall = state.activeGroupCall?.takeIf {
        it.isMinimized && compactDirectCall == null
    }

    BackHandler(enabled = state.activeMedia != null) {
        onAction(AloNativeAppAction.CloseMedia)
    }

    BackHandler(enabled = state.humanChallenge != null) {
        onAction(AloNativeAppAction.CloseHumanChallenge)
    }

    BackHandler(enabled = state.commentsPostId > 0) {
        onAction(AloNativeAppAction.CloseComments)
    }

    BackHandler(enabled = state.isChatPeerProfileOpen) {
        onAction(AloNativeAppAction.CloseChatPeerProfile)
    }

    BackHandler(enabled = state.activeViewedProfileTarget != null || state.isViewedProfileLoading) {
        onAction(AloNativeAppAction.CloseViewedProfile)
    }

    BackHandler(enabled = state.isProfileSettingsOpen) {
        onAction(AloNativeAppAction.CloseProfileSettings)
    }

    BackHandler(enabled = state.activeDirectCall != null && state.activeDirectCall.isMinimized.not()) {
        onAction(AloNativeAppAction.MinimizeDirectCall)
    }

    BackHandler(enabled = state.activeGroupCall != null) {
        // Во время группового звонка системный back не закрывает overlay.
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        ) {
            if (state.activeDirectCall != null || state.activeGroupCall != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    when {
                        compactDirectCall != null -> DirectCallCompactBar(
                            state = compactDirectCall,
                            onExpand = { onAction(AloNativeAppAction.ExpandDirectCall) }
                        )

                        compactGroupCall != null -> GroupCallCompactBar(
                            state = compactGroupCall,
                            onExpand = { onAction(AloNativeAppAction.ExpandGroupCall) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AloNativeAppBackground)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 0.dp)
                        .aloGlassSource(glassBackdrop)
                ) {
                    when {
                        data == null && state.isRefreshing -> {
                            AloNativeLoadingSkeleton(
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        data == null -> {
                            AloNativeStartupUnavailable(
                                message = state.errorMessage
                                    ?: "Не удалось загрузить данные. Проверь подключение к серверу.",
                                onRetry = { onAction(AloNativeAppAction.Refresh) }
                            )
                        }

                        else -> {
                            AnimatedContent(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aloPullToRefresh(
                                        enabled = !(state.activeTab == AloAppTab.Messages && (state.activeConversationPeerId > 0 || state.activeSecretChatId > 0)),
                                        isRefreshing = state.isRefreshing,
                                        onRefresh = { onAction(AloNativeAppAction.Refresh) }
                                    ),
                                targetState = state.activeTab,
                                // Telegram's main tabs (ViewPagerActivity): the pages slide past each other
                                // across the whole width, 320ms on EASE_OUT_QUINT, without fading.
                                transitionSpec = {
                                    val forward = targetState.ordinal > initialState.ordinal
                                    val motion = tween<IntOffset>(320, easing = AloTelegramEaseOutQuint)
                                    slideInHorizontally(motion) { if (forward) it else -it } togetherWith
                                        slideOutHorizontally(motion) { if (forward) -it else it }
                                },
                                label = "rootTabNavigation"
                            ) { activeTab ->
                                when (activeTab) {
                                    AloAppTab.Feed -> FeedScreen(
                                        state = state,
                                        data = data,
                                        onAction = onAction
                                    )

                                    AloAppTab.Search -> AloSearchScreen(
                                        state = state,
                                        data = data,
                                        onAction = onAction,
                                        onFullScreenChange = { searchListOpen = it }
                                    )

                                    AloAppTab.Messages -> MessagesScreen(
                                        state = state,
                                        data = data,
                                        onAction = onAction,
                                        onLoadGroupInviteStats = onLoadGroupInviteStats,
                                        contentTools = contentTools
                                    )

                                    AloAppTab.Alerts -> AlertsScreen(
                                        state = state,
                                        data = data,
                                        onAction = onAction
                                    )

                                    AloAppTab.Profile -> ProfileScreen(
                                        state = state,
                                        data = data,
                                        onAction = onAction
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.publicationQueueStatus.isNotBlank() && state.notice == null) {
                    Text(state.publicationQueueStatus, color = AloNativeAppText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(start = 20.dp, end = 20.dp, bottom = AloBottomBarOverlayPadding + 40.dp)
                            .aloLiquidGlass(AloSquircle(18.dp), backdrop = glassBackdrop)
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Повторить отправку публикаций") { onAction(AloNativeAppAction.Refresh) }
                            .padding(horizontal = 14.dp, vertical = 10.dp))
                }
                // iOS's short notice above the tab bar («Реклама скрыта», «Жалоба отправлена»…).
                state.notice?.let { notice ->
                    LaunchedEffect(notice) {
                        kotlinx.coroutines.delay(2_000)
                        onAction(AloNativeAppAction.DismissNotice)
                    }
                    Text(
                        text = notice,
                        color = AloNativeAppText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 24.dp, end = 24.dp, bottom = AloBottomBarOverlayPadding + 40.dp)
                            .aloLiquidGlass(AloSquircle(22.dp), backdrop = glassBackdrop)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                if (showBottomBar) {
                    CompositionLocalProvider(LocalAloGlassBackdrop provides glassBackdrop) {
                        // iOS's AloSocialBottomShade: what scrolls under the tab bar dissolves into the edge.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(110.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                                .aloEdgeShade(top = false, peak = 0.85f, solid = 0.3f, backdrop = glassBackdrop)
                        )
                        AloBottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            activeTab = state.activeTab,
                            onSelect = { tab -> onAction(AloNativeAppAction.SelectTab(tab)) },
                            profileBadge = state.accountUnread.values.sum(),
                            onProfileLongPress = { onAction(AloNativeAppAction.OpenAccountSwitcher) }
                        )
                    }
                }

                if (data != null && state.isAccountSwitcherOpen) {
                    AloAccountSwitcherSheet(
                        me = data.me,
                        accounts = state.savedAccounts,
                        unread = state.accountUnread,
                        onSwitch = { onAction(AloNativeAppAction.SwitchSecurityAccount(it)) },
                        onAdd = { onAction(AloNativeAppAction.AddAnotherAccount) },
                        onOpenProfile = {
                            onAction(AloNativeAppAction.CloseAccountSwitcher)
                            onAction(AloNativeAppAction.SelectTab(AloAppTab.Profile))
                        },
                        onDismiss = { onAction(AloNativeAppAction.CloseAccountSwitcher) }
                    )
                }

                if (data != null && profileEditorOpen) {
                    AloProfileEditSheet(me = data.me, onDismiss = { profileEditorOpen = false }, onAction = onAction)
                }

                if (data != null && state.isProfileSettingsOpen) {
                    ProfileSettingsSheet(
                        startPage = state.profileSettingsStartPage,
                        me = data.me,
                        onSetFavoriteReaction = { onAction(AloNativeAppAction.SetFavoriteReaction(it)) },
                        notificationSettings = data.notificationSettings,
                        privacySettings = data.privacySettings,
                        blockedUserIds = state.blockedUserIds,
                        blockedUserPeers = state.blockedUserPeers + data.messagesConversations.map { it.peer },
                        blockActionUserId = state.blockActionUserId,
                        themeMode = state.themeMode,
                        isRefreshing = state.isRefreshing,
                        securitySessions = state.securitySessions,
                        securityEvents = state.securityEvents,
                        isSecurityLoading = state.isSecuritySessionsLoading,
                        securityNotice = state.securityNotice,
                        savedAccounts = state.savedAccounts,
                        accountUnread = state.accountUnread,
                        isAccountSecurityLoading = state.isAccountSecurityLoading,
                        passkeys = state.passkeys,
                        isPasskeyLoading = state.isPasskeyLoading,
                        businessProfileData = state.businessProfileData,
                        isBusinessProfileLoading = state.isBusinessProfileLoading,
                        conversations = data.messagesConversations,
                        onDismiss = { onAction(AloNativeAppAction.CloseProfileSettings) },
                        onRefresh = {
                            onAction(AloNativeAppAction.CloseProfileSettings)
                            onAction(AloNativeAppAction.Refresh)
                        },
                        onNotificationSettingsChange = {
                            onAction(AloNativeAppAction.UpdateNotificationSettings(it))
                        },
                        onPrivacySettingsChange = {
                            onAction(AloNativeAppAction.UpdatePrivacySettings(it))
                        },
                        onSetUserBlocked = { userId, blocked ->
                            onAction(AloNativeAppAction.UpdateUserBlock(userId, blocked))
                        },
                        onThemeModeChange = {
                            onAction(AloNativeAppAction.UpdateThemeMode(it))
                        },
                        onClearCache = {
                            onAction(AloNativeAppAction.ClearOfflineCache)
                        },
                        onLoadSecuritySessions = {
                            onAction(AloNativeAppAction.LoadSecuritySessions)
                        },
                        onRevokeSecuritySession = {
                            onAction(AloNativeAppAction.RevokeSecuritySession(it))
                        },
                        onRevokeOtherSecuritySessions = {
                            onAction(AloNativeAppAction.RevokeOtherSecuritySessions)
                        },
                        onChangePassword = { currentPassword, newPassword ->
                            onAction(
                                AloNativeAppAction.ChangePassword(
                                    currentPassword = currentPassword,
                                    newPassword = newPassword
                                )
                            )
                        },
                        onLoadSecurityAccounts = {
                            onAction(AloNativeAppAction.LoadSecurityAccounts)
                        },
                        onSwitchSecurityAccount = { userId ->
                            onAction(AloNativeAppAction.SwitchSecurityAccount(userId))
                        },
                        onLoadPasskeys = {
                            onAction(AloNativeAppAction.LoadPasskeys)
                        },
                        onRegisterPasskey = {
                            onAction(AloNativeAppAction.RegisterPasskey)
                        },
                        onOpenQrLoginScanner = {
                            onAction(AloNativeAppAction.OpenQrLoginScanner)
                        },
                        onAddAccount = {
                            onAction(AloNativeAppAction.AddAnotherAccount)
                        },
                        onExportAccountData = { currentPassword ->
                            onAction(AloNativeAppAction.ExportAccountData(currentPassword))
                        },
                        onDeleteAccount = { currentPassword ->
                            onAction(AloNativeAppAction.DeleteAccount(currentPassword))
                        },
                        onSaveBusinessProfile = {
                                enabled,
                                businessName,
                                category,
                                hours,
                                greetingMessage,
                                autoReplyEnabled,
                                autoReplyMessage ->
                            onAction(
                                AloNativeAppAction.SaveBusinessProfile(
                                    enabled = enabled,
                                    businessName = businessName,
                                    category = category,
                                    hours = hours,
                                    greetingMessage = greetingMessage,
                                    autoReplyEnabled = autoReplyEnabled,
                                    autoReplyMessage = autoReplyMessage
                                )
                            )
                        },
                        onAddBusinessQuickReply = { shortcut, title, body ->
                            onAction(AloNativeAppAction.AddBusinessQuickReply(shortcut, title, body))
                        },
                        onDeleteBusinessQuickReply = { quickReplyId ->
                            onAction(AloNativeAppAction.DeleteBusinessQuickReply(quickReplyId))
                        },
                        onSaveBusinessChatLabel = { chatKind, peerId, roomId, label, color ->
                            onAction(
                                AloNativeAppAction.SaveBusinessChatLabel(
                                    chatKind = chatKind,
                                    peerId = peerId,
                                    roomId = roomId,
                                    label = label,
                                    color = color
                                )
                            )
                        },
                        onDeleteBusinessChatLabel = { chatLabelId ->
                            onAction(AloNativeAppAction.DeleteBusinessChatLabel(chatLabelId))
                        },
                        onLogout = {
                            onAction(AloNativeAppAction.Logout)
                        },
                        onOpenModeration = if (data.me.adminCan.isNotEmpty()) {
                            { onAction(AloNativeAppAction.OpenModeration) }
                        } else null,
                        onOpenCreatorStats = { onAction(AloNativeAppAction.OpenCreatorStats) },
                        onEditProfile = { profileEditorOpen = true }
                    )
                }

                // A story link opens over whatever is on screen, as iOS's fullScreenCover.
                if (data != null) {
                    state.linkedStory?.let { group ->
                        AloStoryViewer(
                            groups = listOf(group),
                            startUserId = group.user.id,
                            meId = data.me.id,
                            state = state,
                            onAction = onAction,
                            onDismiss = { onAction(AloNativeAppAction.CloseLinkedStory) }
                        )
                    }
                    state.expiredStoryLink?.let { expired ->
                        AloStoryExpiredSheet(
                            author = expired.author,
                            onOpenProfile = { author ->
                                onAction(AloNativeAppAction.CloseLinkedStory)
                                onAction(AloNativeAppAction.OpenViewedProfile(AloProfileTarget.User(author.id)))
                            },
                            onDismiss = { onAction(AloNativeAppAction.CloseLinkedStory) }
                        )
                    }
                }

                // Pushed over the settings it was opened from, with Telegram's page motion; the back swipe follows the finger.
                AloSwipeBackPage(
                    visible = data != null && state.isModerationOpen,
                    onClosed = { onAction(AloNativeAppAction.CloseModeration) }
                ) { page ->
                    data?.let { AloModerationPanel(me = it.me, onBack = page::close) }
                }
                AloSwipeBackPage(
                    visible = data != null && state.isCreatorStatsOpen,
                    onClosed = { onAction(AloNativeAppAction.CloseCreatorStats) }
                ) { page ->
                    AloCreatorStatsScreen(state = state, onAction = onAction, onBack = page::close)
                }

                if (state.isQrScannerOpen) {
                    AloQrLoginScannerOverlay(
                        onScanned = { payload ->
                            onAction(AloNativeAppAction.ApproveQrLoginPayload(payload))
                        },
                        onDismiss = { onAction(AloNativeAppAction.CloseQrLoginScanner) }
                    )
                }

                state.qrLoginConfirmation?.let { confirmation ->
                    AlertDialog(
                        onDismissRequest = { if (!confirmation.isApproving) onAction(AloNativeAppAction.CancelQrLogin) },
                        containerColor = AloNativeAppBackgroundSecondary,
                        title = {
                            Text(
                                if (confirmation.deviceLabel.isBlank()) "Подключить новое устройство?"
                                // «Chrome · macOS» stays on one line.
                                else "Войти на устройстве ${confirmation.deviceLabel.replace(' ', '\u00A0')}?",
                                color = AloNativeAppText
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    (if (confirmation.ip.isBlank()) "" else "IP ${confirmation.ip} · ") + "запрос только что",
                                    color = AloNativeAppText,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "На этом устройстве откроется ваш аккаунт Alo. Подтверждайте вход, только если сами сканируете код со своего компьютера.",
                                    color = AloNativeAppMuted
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !confirmation.isApproving,
                                onClick = { onAction(AloNativeAppAction.ConfirmQrLogin) }
                            ) { Text(if (confirmation.isApproving) "Подтверждаем…" else "Подтвердить вход", color = AloNativeAppAccent, fontWeight = FontWeight.SemiBold) }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !confirmation.isApproving,
                                onClick = { onAction(AloNativeAppAction.CancelQrLogin) }
                            ) { Text("Отмена", color = AloNativeAppMuted) }
                        }
                    )
                }

                if (state.activeViewedProfileTarget != null || state.isViewedProfileLoading || state.viewedProfile != null) {
                    ViewedProfileOverlay(
                        profile = state.viewedProfile,
                        isLoading = state.isViewedProfileLoading,
                        errorMessage = state.errorMessage,
                        currentUserId = data?.me?.id ?: 0,
                        pendingLikePostId = state.pendingLikePostId,
                        pendingRepostPostId = state.pendingRepostPostId,
                        blockedUserIds = state.blockedUserIds,
                        blockActionUserId = state.blockActionUserId,
                        onDismiss = { onAction(AloNativeAppAction.CloseViewedProfile) },
                        onOpenComments = { onAction(AloNativeAppAction.OpenComments(it)) },
                        onToggleRepost = { onAction(AloNativeAppAction.ToggleRepost(it)) },
                        onOpenMedia = { onAction(AloNativeAppAction.OpenMedia(it)) },
                        onOpenMediaGallery = { items, startIndex ->
                            onAction(AloNativeAppAction.OpenMediaGallery(items, startIndex))
                        },
                        onLike = { onAction(AloNativeAppAction.ToggleLike(it)) },
                        onDeletePost = { onAction(AloNativeAppAction.DeletePost(it)) },
                        onUpdateChannelSettings = {
                            channelId,
                            commentsEnabled,
                            signMessages,
                            showAuthorProfiles ->
                            onAction(
                                AloNativeAppAction.UpdateChannelSettings(
                                    channelId = channelId,
                                    commentsEnabled = commentsEnabled,
                                    signMessages = signMessages,
                                    showAuthorProfiles = showAuthorProfiles
                                )
                            )
                        },
                        onUpdateUserBlock = { userId, blocked ->
                            onAction(AloNativeAppAction.UpdateUserBlock(userId, blocked))
                        },
                        onPrimaryAction = { profile ->
                            if (profile.kind == "channel") {
                                onAction(AloNativeAppAction.ToggleChannelSubscription(profile.id))
                            } else if (profile.kind == "user") {
                                // The hero has asked already before «В друзьях» / «Заявка отправлена» are undone.
                                when (profile.friendStatus) {
                                    "incoming" -> {
                                        if (profile.incomingFriendRequestId > 0) {
                                            onAction(AloNativeAppAction.RespondFriendRequest(profile.incomingFriendRequestId, true))
                                        }
                                    }
                                    "friends" -> onAction(AloNativeAppAction.RemoveFriend(profile.id))
                                    "outgoing" -> {
                                        if (profile.outgoingFriendRequestId > 0) {
                                            onAction(AloNativeAppAction.CancelFriendRequest(profile.outgoingFriendRequestId, profile.id))
                                        }
                                    }
                                    "none" -> {
                                        if (profile.canSendFriendRequest) {
                                            onAction(AloNativeAppAction.SendFriendRequest(profile.id))
                                        } else {
                                            // Friend requests closed: a follow (a follow back makes friends).
                                            onAction(AloNativeAppAction.ToggleFollowUser(profile.id))
                                        }
                                    }
                                }
                            }
                        },
                        hasMorePosts = AloPagedList.ViewedProfilePosts !in state.pagedListsEnded,
                        isLoadingMorePosts = AloPagedList.ViewedProfilePosts in state.pagedListsLoading,
                        onLoadMorePosts = { onAction(AloNativeAppAction.LoadMore(AloPagedList.ViewedProfilePosts)) },
                        onMessageAction = { profile ->
                            when (profile.kind) {
                                "user" -> {
                                    if (
                                        profile.canMessage &&
                                        !AloUserBlockPolicy.isBlockedUser(profile.id, state.blockedUserIds)
                                    ) {
                                        onAction(AloNativeAppAction.OpenDirectConversation(profile.id))
                                    }
                                }
                                "group" -> onAction(AloNativeAppAction.SelectConversation(profile.id))
                                "channel" -> if (profile.messengerRoomId > 0) onAction(AloNativeAppAction.OpenChannelChat(profile.messengerRoomId))
                            }
                        }
                    )
                }

                if (state.commentsPostId > 0) {
                    var commentReportId by remember { mutableStateOf<Int?>(null) }
                    val commentsLiveInput = LocalAloLiveInput.current
                    AloPostDetailPage(
                        post = state.commentsPost,
                        comments = state.comments,
                        focusCommentId = state.commentsFocusId,
                        value = { commentsLiveInput.commentText },
                        meId = data?.me?.id ?: 0,
                        editingCommentId = state.commentsEditingCommentId,
                        replyTo = state.commentsReplyTo,
                        mentionSuggestions = state.mentionSuggestions,
                        isLoading = state.isCommentsLoading,
                        isSubmitting = state.isSubmittingComment,
                        pendingCommentLikeIds = state.pendingCommentLikeIds,
                        onBack = { onAction(AloNativeAppAction.CloseComments) },
                        onValueChange = { onAction(AloNativeAppAction.UpdateCommentComposer(it)) },
                        onSend = { onAction(AloNativeAppAction.SubmitComment) },
                        onLikePost = { state.commentsPost?.let { onAction(AloNativeAppAction.ToggleLike(it.id)) } },
                        onRepostPost = { state.commentsPost?.let { onAction(AloNativeAppAction.ToggleRepost(it.id)) } },
                        onLikeComment = { onAction(AloNativeAppAction.ToggleCommentLike(it.id)) },
                        onReply = { onAction(AloNativeAppAction.SetCommentReplyTo(it)) },
                        onEditComment = { comment ->
                            onAction(AloNativeAppAction.StartEditComment(comment.id, comment.body))
                        },
                        onDeleteComment = { comment ->
                            onAction(AloNativeAppAction.DeleteComment(comment.id))
                        },
                        onReportComment = { commentReportId = it.id },
                        onMentionQuery = { onAction(AloNativeAppAction.LoadMentionSuggestions(it)) },
                        onOpenAuthorProfile = { author ->
                            onAction(AloNativeAppAction.CloseComments)
                            if (!author.isChannel && author.id == data?.me?.id) {
                                onAction(AloNativeAppAction.SelectTab(AloAppTab.Profile))
                            } else {
                                onAction(AloNativeAppAction.OpenViewedProfile(author.toProfileTarget()))
                            }
                        },
                        onOpenMediaGallery = { items, startIndex ->
                            onAction(AloNativeAppAction.OpenMediaGallery(items, startIndex))
                        }
                    )
                    commentReportId?.let { id ->
                        AloReportSheet(
                            title = "Жалоба на комментарий",
                            onDismiss = { commentReportId = null },
                            onSend = { reason, details ->
                                val result = kotlinx.coroutines.CompletableDeferred<String?>()
                                onAction(AloNativeAppAction.Report("comment", id, reason, details) { result.complete(it) })
                                result.await()
                            }
                        )
                    }
                }

                state.activeMedia?.let { viewer ->
                    MediaViewerOverlay(
                        viewer = viewer,
                        onDismiss = { onAction(AloNativeAppAction.CloseMedia) }
                    )
                }

                AloHumanChallengeSheet(
                    state = state.humanChallenge,
                    onSolved = { token -> onAction(AloNativeAppAction.SubmitHumanChallenge(token)) },
                    onDismiss = { onAction(AloNativeAppAction.CloseHumanChallenge) }
                )
            }
        }
            // Calls cover the whole screen, status bar included, as on iOS.
                state.activeDirectCall?.let { call ->
                    AnimatedContent(
                        targetState = !call.isMinimized,
                        transitionSpec = {
                            (slideInVertically(
                                animationSpec = tween(190, easing = FastOutSlowInEasing),
                                initialOffsetY = { -it / 8 }
                            ) + fadeIn(tween(120))) togetherWith
                                (slideOutVertically(
                                    animationSpec = tween(190, easing = FastOutSlowInEasing),
                                    targetOffsetY = { -it }
                                ) + fadeOut(tween(120)))
                        },
                        label = "directCallVisibility"
                    ) { visible ->
                        if (visible) {
                            DirectCallOverlay(
                                state = call,
                                onAccept = { onAction(AloNativeAppAction.AcceptDirectCall) },
                                onDecline = { onAction(AloNativeAppAction.DeclineDirectCall) },
                                onEnd = { onAction(AloNativeAppAction.EndDirectCall) },
                                onMinimize = { onAction(AloNativeAppAction.MinimizeDirectCall) },
                                onToggleMute = { onAction(AloNativeAppAction.ToggleDirectCallMute) },
                                onToggleSpeaker = { onAction(AloNativeAppAction.ToggleDirectCallSpeaker) },
                                onToggleVideo = { onAction(AloNativeAppAction.ToggleDirectCallVideo) },
                                onToggleScreenShare = {
                                    onAction(AloNativeAppAction.ToggleDirectCallScreenShare)
                                },
                                onFlipCamera = { onAction(AloNativeAppAction.FlipDirectCallCamera) },
                                onSelectCamera = {
                                    onAction(AloNativeAppAction.SelectDirectCallCamera(it))
                                },
                                onBindLocalRenderer = onBindDirectCallLocalRenderer,
                                onBindRemoteRenderer = onBindDirectCallRemoteRenderer,
                                onUnbindLocalRenderer = onUnbindDirectCallLocalRenderer,
                                onUnbindRemoteRenderer = onUnbindDirectCallRemoteRenderer
                            )
                        }
                    }
                }

                state.activeGroupCall?.let { call ->
                    AnimatedContent(
                        targetState = !call.isMinimized,
                        transitionSpec = {
                            (slideInVertically(
                                animationSpec = tween(190, easing = FastOutSlowInEasing),
                                initialOffsetY = { -it / 8 }
                            ) + fadeIn(tween(120))) togetherWith
                                (slideOutVertically(
                                    animationSpec = tween(190, easing = FastOutSlowInEasing),
                                    targetOffsetY = { -it }
                                ) + fadeOut(tween(120)))
                        },
                        label = "groupCallVisibility"
                    ) { visible ->
                        if (visible) {
                            GroupCallOverlay(
                                state = call,
                                onMinimize = { onAction(AloNativeAppAction.MinimizeGroupCall) },
                                onToggleMute = { onAction(AloNativeAppAction.ToggleGroupCallMute) },
                                onToggleSpeaker = { onAction(AloNativeAppAction.ToggleGroupCallSpeaker) },
                                onToggleVideo = { onAction(AloNativeAppAction.ToggleGroupCallVideo) },
                                onSelectCamera = {
                                    onAction(AloNativeAppAction.SelectGroupCallCamera(it))
                                },
                                onBindLocalRenderer = onBindGroupCallLocalRenderer,
                                onBindRemoteRenderer = onBindGroupCallRemoteRenderer,
                                onUnbindLocalRenderer = onUnbindGroupCallLocalRenderer,
                                onUnbindRemoteRenderer = onUnbindGroupCallRemoteRenderer,
                                onBindSfuRenderer = onBindGroupSfuRenderer,
                                onUnbindSfuRenderer = onUnbindGroupSfuRenderer,
                                onLeave = { onAction(AloNativeAppAction.LeaveGroupCall) },
                                onSetMicrophone = { userId, allowed -> onAction(AloNativeAppAction.SetGroupCallMicrophone(userId, allowed)) },
                                onMuteEveryone = { onAction(AloNativeAppAction.MuteGroupCallEveryone(it)) },
                                onToggleRecording = { onAction(AloNativeAppAction.ToggleGroupCallRecording) }
                            )
                        }
                    }
                }

}
}

@Composable
private fun AloNativeLoadingSkeleton(
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Alo",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = AloNativeAppText
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = AloNativeAppAccent
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AloSquircle(28.dp),
                color = AloNativeAppSurface.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SkeletonCircle(46.dp)
                        SkeletonPill(modifier = Modifier.weight(1f), height = 46.dp)
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SkeletonCircle(42.dp)
                        SkeletonPill(width = 148.dp, height = 46.dp)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(999.dp),
                color = AloNativeAppSurface.copy(alpha = 0.90f)
            ) {
                Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SkeletonPill(modifier = Modifier.weight(1f), height = 42.dp)
                    SkeletonPill(modifier = Modifier.weight(1f), height = 42.dp)
                }
            }
        }
        items(4) { index ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AloSquircle(24.dp),
                color = AloNativeAppSurface.copy(alpha = 0.94f)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonCircle(42.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                            SkeletonPill(width = 132.dp, height = 15.dp)
                            SkeletonPill(width = 92.dp, height = 11.dp)
                        }
                        SkeletonPill(width = 46.dp, height = 11.dp)
                    }
                    SkeletonPill(width = if (index % 2 == 0) 210.dp else 150.dp, height = 17.dp)
                    if (index != 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (index == 0) 210.dp else 150.dp)
                                .clip(AloSquircle(18.dp))
                                .background(AloNativeAppSurfaceRaised.copy(alpha = 0.82f))
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonPill(width = 44.dp, height = 16.dp)
                        SkeletonPill(width = 44.dp, height = 16.dp)
                        SkeletonPill(width = 44.dp, height = 16.dp)
                        Box(modifier = Modifier.weight(1f))
                        SkeletonPill(width = 44.dp, height = 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AloNativeStartupUnavailable(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        InlineErrorPanel(
            message = message,
            onRetry = onRetry
        )
    }
}

@Composable
private fun SkeletonCircle(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AloNativeAppSurfaceRaised.copy(alpha = 0.82f))
    )
}

@Composable
private fun SkeletonPill(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    height: androidx.compose.ui.unit.Dp
) {
    val sized = if (width != null) modifier.width(width) else modifier
    Box(
        modifier = sized
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(AloNativeAppSurfaceRaised.copy(alpha = 0.82f))
    )
}

private fun Modifier.aloPullToRefresh(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(isRefreshing) {
        var pullDistance = 0f
        detectVerticalDragGestures(
            onDragStart = { pullDistance = 0f },
            onVerticalDrag = { _, dragAmount ->
                pullDistance = (pullDistance + dragAmount).coerceAtLeast(0f)
                if (!isRefreshing && pullDistance > 150f) {
                    pullDistance = 0f
                    onRefresh()
                }
            },
            onDragEnd = { pullDistance = 0f },
            onDragCancel = { pullDistance = 0f }
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FeedScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit
) {
    val posts = when (state.feedMode) {
        AloFeedMode.Popular -> data.feedPopular
        AloFeedMode.Subscriptions -> data.feedSubscriptions
    }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var awaitingPostResult by remember { mutableStateOf(false) }
    var storyComposerOpen by rememberSaveable { mutableStateOf(false) }
    var selectedStoryGroup by remember { mutableStateOf<AloStoryGroup?>(null) }

    val liveInput = LocalAloLiveInput.current
    LaunchedEffect(
        awaitingPostResult,
        state.isSubmittingPost,
        // Only while a publication is on its way: typing in the editor is no reason to recompose the feed.
        if (awaitingPostResult) liveInput.postText else "",
        state.errorMessage
    ) {
        if (!awaitingPostResult || state.isSubmittingPost) return@LaunchedEffect

        if (liveInput.postText.isBlank()) {
            // Keep the editor on screen while the request is running, then
            // close it only after the store confirms a successful publish.
            composerOpen = false
            awaitingPostResult = false
        } else if (!state.errorMessage.isNullOrBlank()) {
            // A failed request must leave the draft editable for retry.
            awaitingPostResult = false
        }
    }

    val feedBackdrop = rememberAloGlassBackdrop()
    var whyShownPost by remember { mutableStateOf<AloPost?>(null) }
    var reportTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var whyAd by remember { mutableStateOf<AloAd?>(null) }
    val uriHandler = LocalUriHandler.current
    val visibleAds = state.feedAds.filterNot { it.id in state.hiddenAdIds }
    val every = state.feedAdEvery.coerceAtLeast(3)
    LaunchedEffect(Unit) { onAction(AloNativeAppAction.LoadFeedAds) }

    // iOS's feed: the timeline scrolls under a floating header and a glass segmented pill.
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .aloGlassSource(feedBackdrop),
            contentPadding = PaddingValues(top = AloScreenChrome.HeaderHeight + AloScreenChrome.FieldGap + AloScreenChrome.FieldHeight + 16.dp, bottom = AloBottomBarOverlayPadding + AloNativeLayout.spacingLarge)
        ) {
            item(key = "stories") {
                StoryTray(
                    me = data.me,
                    stories = state.stories,
                    isLoading = state.isStoriesLoading,
                    onAddStory = { storyComposerOpen = true },
                    onOpenStory = { group -> selectedStoryGroup = group }
                )
            }
            if (posts.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (state.feedMode == AloFeedMode.Subscriptions) "Пока нет публикаций" else "Лента пока пуста",
                            color = AloNativeAppText, style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (state.feedMode == AloFeedMode.Subscriptions) "Здесь появятся посты людей, на которых ты подписан."
                            else "Создай первый пост кнопкой +.",
                            color = AloNativeAppMuted, style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            posts.forEachIndexed { index, post ->
                item(key = "post-${post.id}") {
                    val hidden = state.hiddenFeedPosts[post.id]
                    if (hidden != null) {
                        AloHiddenPostRow(reason = hidden, onUndo = { onAction(AloNativeAppAction.UndoHideFeedPost(post.id)) })
                    } else {
                        AloTimelinePostRow(
                            post = post,
                            isMine = post.creatorId == data.me.id || post.author.id == data.me.id,
                            isLikePending = state.pendingLikePostId == post.id,
                            isRepostPending = state.pendingRepostPostId == post.id,
                            onAuthor = {
                                if (!post.author.isChannel && post.author.id == data.me.id) {
                                    onAction(AloNativeAppAction.SelectTab(AloAppTab.Profile))
                                } else {
                                    onAction(AloNativeAppAction.OpenViewedProfile(post.author.toProfileTarget()))
                                }
                            },
                            onComments = { onAction(AloNativeAppAction.OpenComments(post.id)) },
                            onRepost = { onAction(AloNativeAppAction.ToggleRepost(post.id)) },
                            onLike = { onAction(AloNativeAppAction.ToggleLike(post.id)) },
                            onOpenMedia = { items, start -> onAction(AloNativeAppAction.OpenMediaGallery(items, start)) },
                            onDelete = { onAction(AloNativeAppAction.DeletePost(post.id)) },
                            onReaderAction = { action ->
                                when (action) {
                                    AloPostReaderAction.WhyShown -> whyShownPost = post
                                    AloPostReaderAction.NotInterested -> onAction(AloNativeAppAction.HideFeedPost(post.id, "not_interested"))
                                    AloPostReaderAction.Hide -> onAction(AloNativeAppAction.HideFeedPost(post.id, "hide"))
                                    AloPostReaderAction.Report -> reportTarget = "post" to post.id
                                }
                            }
                        )
                    }
                }
                // Alo's own ad after every few posts, labelled and hideable.
                val slot = (index + 1) / every - 1
                if ((index + 1) % every == 0 && slot in visibleAds.indices) {
                    val ad = visibleAds[slot]
                    item(key = "ad-${ad.id}") {
                        LaunchedEffect(ad.id) { onAction(AloNativeAppAction.RecordAdEvent(ad.id, "impression", "feed")) }
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            AloSponsoredCard(
                                ad = ad,
                                onOpen = {
                                    onAction(AloNativeAppAction.RecordAdEvent(ad.id, "click", "feed"))
                                    runCatching { uriHandler.openUri(ad.ctaUrl) }
                                },
                                onWhy = { whyAd = ad },
                                onHide = { onAction(AloNativeAppAction.HideAd(ad.id, report = false)) },
                                onReport = { onAction(AloNativeAppAction.HideAd(ad.id, report = true)) }
                            )
                        }
                    }
                }
            }
            val pagedFeed = if (state.feedMode == AloFeedMode.Subscriptions) AloPagedList.FeedSubscriptions else AloPagedList.FeedPopular
            aloLoadMoreItem(
                key = "feed-more",
                hasMore = pagedFeed !in state.pagedListsEnded,
                isLoading = pagedFeed in state.pagedListsLoading,
                loaded = posts.size,
                onNeedMore = { onAction(AloNativeAppAction.LoadMore(pagedFeed)) }
            )
        }

        // The floating header: avatar, «ало», settings; then the glass «Для вас / Подписки» pill.
        // The timeline dissolves under it (iOS's AloSocialTitleShade), reaching 16dp below the pill.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aloEdgeShade(top = true, peak = 0.94f, solid = 0.3f, backdrop = feedBackdrop)
                .padding(bottom = 26.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight).padding(horizontal = AloScreenChrome.SideInset)) {
                Box(Modifier.align(Alignment.CenterStart).size(44.dp), contentAlignment = Alignment.Center) {
                    AvatarBadge(
                        name = data.me.name, avatarUrl = data.me.avatarUrl, verified = false, size = 34.dp,
                        colorSeed = data.me.id.toString(),
                        onClick = { onAction(AloNativeAppAction.SelectTab(AloAppTab.Profile)) }
                    )
                }
                Text(
                    "ало",
                    modifier = Modifier.align(Alignment.Center),
                    color = AloNativeAppText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(38.dp)
                        .aloLiquidGlass(CircleShape, tint = AloGlassOverShadeTint, backdrop = feedBackdrop)
                        .clickable { onAction(AloNativeAppAction.OpenProfileSettings) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Settings, "Настройки", tint = AloNativeAppText, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.height(AloScreenChrome.FieldGap))
            AloFeedModePill(
                feedMode = state.feedMode,
                backdrop = feedBackdrop,
                onSelectMode = { onAction(AloNativeAppAction.SelectFeedMode(it)) }
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Above the floating tab bar (its 60dp capsule with 4dp margins), which itself sits on the system
                // navigation bar: a fixed distance from the screen edge let the bar cover the button on 3-button phones.
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = AloBottomBarFloatingHeight + 14.dp)
                .size(52.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(AloNativeAppAccent)
                .clickable { composerOpen = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Создать пост", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }

    whyShownPost?.let { post ->
        AloWhyShownSheet(post = post, onDismiss = { whyShownPost = null }, onHide = { reason ->
            whyShownPost = null
            onAction(AloNativeAppAction.HideFeedPost(post.id, reason))
        })
    }
    reportTarget?.let { (type, id) ->
        AloReportSheet(
            title = if (type == "comment") "Жалоба на комментарий" else "Жалоба на публикацию",
            onDismiss = { reportTarget = null },
            onSend = { reason, details ->
                val result = kotlinx.coroutines.CompletableDeferred<String?>()
                onAction(AloNativeAppAction.Report(type, id, reason, details) { result.complete(it) })
                result.await()
            }
        )
    }
    whyAd?.let { ad ->
        AlertDialog(
            onDismissRequest = { whyAd = null },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text("Почему я вижу эту рекламу", color = AloNativeAppText) },
            text = { Text(ad.why.ifBlank { "Это реклама Alo. Её показывают всем в этом разделе, без учёта ваших переписок и интересов." }, color = AloNativeAppMuted) },
            confirmButton = { TextButton(onClick = { whyAd = null }) { Text("Понятно", color = AloNativeAppAccent) } },
            dismissButton = {
                TextButton(onClick = { whyAd = null; onAction(AloNativeAppAction.HideAd(ad.id, report = false)) }) {
                    Text("Скрыть рекламу", color = AloNativeAppMuted)
                }
            }
        )
    }

    if (composerOpen) {
        AloPostEditor(state = state, me = data.me, onAction = onAction, onDismiss = { composerOpen = false })
    }

    if (storyComposerOpen) {
        AloStoryEditor(state = state, onAction = onAction, onDismiss = { storyComposerOpen = false })
    }

    selectedStoryGroup?.let { group ->
        AloStoryViewer(
            groups = state.stories,
            startUserId = group.user.id,
            meId = data.me.id,
            state = state,
            onAction = onAction,
            onDismiss = { selectedStoryGroup = null }
        )
    }
}

@Composable
private fun StoryTray(
    me: AloCurrentUser,
    stories: List<AloStoryGroup>,
    isLoading: Boolean,
    onAddStory: () -> Unit,
    onOpenStory: (AloStoryGroup) -> Unit
) {
    // iOS's tray (FeedView.storiesTray): 68dp columns 10dp apart, a 56dp «История» avatar with a
    // plus, 62dp rings around 54dp avatars, 12sp labels, and a hairline under the row.
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
        ) {
            item(key = "create-story") {
                Column(
                    modifier = Modifier
                        .width(68.dp)
                        .clip(AloSquircle(12.dp))
                        .clickable(onClick = onAddStory),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                        AvatarBadge(
                            name = me.name,
                            avatarUrl = me.avatarUrl,
                            verified = false,
                            size = 56.dp,
                            colorSeed = me.id.toString()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 1.dp, bottom = 1.dp)
                                .size(22.dp)
                                .border(2.dp, AloNativeAppBackground, CircleShape)
                                .padding(1.dp)
                                .clip(CircleShape)
                                .background(AloNativeAppAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Добавить историю",
                                modifier = Modifier.size(14.dp),
                                tint = Color.White
                            )
                        }
                    }
                    Text(
                        text = "История",
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AloNativeAppMuted,
                        maxLines = 1
                    )
                }
            }

            items(stories, key = { "story-${it.user.id}" }) { group ->
                Column(
                    modifier = Modifier
                        .width(68.dp)
                        .clip(AloSquircle(12.dp))
                        .clickable { onOpenStory(group) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .border(2.dp, if (group.hasUnseen) AloNativeAppAccent else AloNativeAppBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarBadge(
                            name = group.user.name,
                            avatarUrl = group.user.avatarUrl,
                            verified = false,
                            size = 54.dp,
                            colorSeed = group.user.id.toString()
                        )
                    }
                    Text(
                        text = group.user.name.ifBlank { "Пользователь" },
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AloNativeAppText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isLoading && stories.isEmpty()) {
                item(key = "stories-loading") {
                    Box(modifier = Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AloNativeAppAccent
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.15f)))
    }
}

@Composable
private fun MessagesScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit,
    onLoadGroupInviteStats: suspend (Int, Int) -> AloGroupInviteStats,
    contentTools: AloContentTools? = null
) {
    val liveInput = LocalAloLiveInput.current
    val chatOpen = state.activeConversationPeerId > 0
    var retainedConversation by remember { mutableStateOf<AloConversationDetail?>(null) }
    var retainedChatAppearance by remember { mutableStateOf(state.activeChatAppearance) }
    if (chatOpen) {
        state.activeConversation?.let { retainedConversation = it }
        retainedChatAppearance = state.activeChatAppearance
    }
    val displayedConversation = state.activeConversation ?: retainedConversation
    val activeChatKey = "${displayedConversation?.kind}:${displayedConversation?.peerId}:${displayedConversation?.roomId}:${state.messageForwardDraft.size}"

    // How much of the inbox a chat being swiped away still covers: Telegram shrinks the list under
    // it to 95% from the left edge and dims it, both easing off as the chat leaves.
    var chatCover by remember { mutableFloatStateOf(0f) }
    val pageShiftPx = rememberAloPageShiftPx()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AloNativeAppBackground)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val cover = chatCover
                    val scale = 1f - 0.05f * cover
                    scaleX = scale
                    scaleY = scale
                    translationX = -4.dp.toPx() * cover
                    transformOrigin = TransformOrigin(0f, 0.12f)
                }
                .drawWithContent {
                    drawContent()
                    val cover = chatCover
                    if (cover > 0f) drawRect(Color.Black.copy(alpha = 120f / 255f * cover.coerceAtMost(0.8f)))
                }
        ) {
            // Under an open chat nobody sees the list: it keeps what it last showed and costs nothing while messages
            // arrive and things change in the chat. It catches up as soon as the chat starts to move away.
            // chatCover stays 0 while the chat simply sits open; it is above 0 only during a swipe back.
            val inboxHidden by remember { derivedStateOf { chatCover == 0f } }
            val inboxState = remember { arrayOf(state) }
            val inboxData = remember { arrayOf(data) }
            if (!(chatOpen && inboxHidden)) {
                inboxState[0] = state
                inboxData[0] = data
            }
            MessagesInboxScreen(
                state = inboxState[0],
                data = inboxData[0],
                onAction = onAction
            )
        }

        // Telegram's page push: the chat fades in sliding 48dp from the right; it closes itself.
        AloDeferredPageEnter(visible = chatOpen, shiftPx = pageShiftPx) {
            key(activeChatKey) {
                // The chat follows the app's theme: its bubbles, wallpaper and glass take the light or the dark palette (AloChatContrastTest).
                MessagesConversationScreen(
                    me = data.me,
                    conversations = data.messagesConversations,
                    shareableContacts = data.searchUsers.filter { user ->
                        !user.isChannel && user.id > 0 && user.id != data.me.id
                    },
                    detail = displayedConversation,
                    chatAppearance = retainedChatAppearance,
                    isLoading = state.isLoadingConversation,
                    isOfflineCached = state.isOfflineCached,
                    blockedUserIds = state.blockedUserIds,
                    isPeerBlocked = displayedConversation?.isBlockedDirectPeer(state.blockedUserIds) == true,
                    isBlockActionInProgress = displayedConversation?.peerId == state.blockActionUserId,
                    pendingMessageIds = state.pendingChatMessageIds,
                    composerValue = { liveInput.messageText },
                    recorderMode = state.messageRecorderMode,
                    captureState = state.messageCaptureState,
                    recordingDurationSec = { liveInput.messageRecordingSeconds },
                    recordingLevels = { liveInput.messageRecordingLevels },
                    draftAttachment = state.messageDraftAttachment,
                    draftAttachments = state.messageDraftAttachments,
                    selfDestructSeconds = state.messageSelfDestructSeconds,
                    activeForumTopicId = state.activeForumTopicId,
                    forwardDraft = state.messageForwardDraft,
                    editingMessageId = state.messageEditingId,
                    replyingToMessage = state.messageReplyTo,
                    isSending = state.isSendingMessage,
                    showPeerProfile = state.isChatPeerProfileOpen,
                    isChatMuted = state.activeChatMuted,
                    groupManagement = state.groupManagement,
                    isGroupManagementLoading = state.isGroupManagementLoading,
                    contentTools = contentTools,
                    onBack = {
                        if (state.isChatPeerProfileOpen) {
                            onAction(AloNativeAppAction.CloseChatPeerProfile)
                        } else {
                            onAction(AloNativeAppAction.CloseConversation)
                        }
                    },
                    onSwipeCover = { chatCover = it },
                    onClosePeerProfile = {
                        onAction(AloNativeAppAction.CloseChatPeerProfile)
                    },
                    onOpenPeerProfile = { onAction(AloNativeAppAction.OpenChatPeerProfile) },
                    onToggleChatMuted = { onAction(AloNativeAppAction.ToggleActiveChatMuted) },
                    onLoadChatMuted = { onAction(AloNativeAppAction.LoadActiveChatMuted) },
                    commentsThread = state.commentsThread?.takeIf { it.roomId == state.activeConversation?.roomId },
                    onJoinChannel = { onAction(AloNativeAppAction.JoinPreviewedChannel) },
                    onUpdateDirectChatAutoDelete = { peerId, seconds ->
                        onAction(AloNativeAppAction.UpdateDirectChatAutoDelete(peerId, seconds))
                    },
                    onUpdateChatAppearance = { onAction(AloNativeAppAction.UpdateActiveChatAppearance(it)) },
                    onUpdateUserBlock = { userId, blocked ->
                        onAction(AloNativeAppAction.UpdateUserBlock(userId, blocked))
                    },
                    onOpenFullProfile = {
                        state.activeConversation?.let {
                            onAction(AloNativeAppAction.OpenViewedProfile(it.toProfileTarget()))
                        }
                    },
                    onOpenProfile = { target ->
                        onAction(AloNativeAppAction.OpenViewedProfile(target))
                    },
                    onComposerChange = { onAction(AloNativeAppAction.UpdateMessageComposer(it)) },
                    onStartReply = { onAction(AloNativeAppAction.StartReplyMessage(it)) },
                    onCancelReply = { onAction(AloNativeAppAction.CancelReplyMessage) },
                onSend = { onAction(AloNativeAppAction.SendMessage) },
                onSendSilently = { onAction(AloNativeAppAction.SendMessageSilently) },
                onSendWhenOnline = { onAction(AloNativeAppAction.SendMessageWhenOnline) },
                onScheduleSend = { scheduledAt ->
                    onAction(AloNativeAppAction.ScheduleMessage(scheduledAt))
                },
                onScheduleRepeat = { repeat ->
                    onAction(AloNativeAppAction.ScheduleRepeatingMessage(repeat))
                },
                onSendChatSystemEvent = { body, pollDefinition ->
                    onAction(AloNativeAppAction.SendChatSystemEvent(body, pollDefinition))
                },
                onCreatePoll = { definition, schedule ->
                    onAction(AloNativeAppAction.CreatePoll(definition, schedule))
                },
                onClosePoll = { pollId ->
                    onAction(AloNativeAppAction.ClosePoll(pollId))
                },
                onPickAttachment = { onAction(AloNativeAppAction.PickMessageAttachment) },
                onPickGalleryUri = { uri -> onAction(AloNativeAppAction.PickMessageGalleryUri(uri)) },
                    onPickGalleryUris = { uris -> onAction(AloNativeAppAction.PickMessageGalleryUris(uris)) },
                    onShareContact = { contact -> onAction(AloNativeAppAction.ShareContact(contact)) },
                    onShareStaticLocation = { latitude, longitude, title, address ->
                        onAction(AloNativeAppAction.ShareStaticLocation(latitude, longitude, title, address))
                    },
                    onShareLiveLocation = { request ->
                        onAction(AloNativeAppAction.ShareLiveLocation(request))
                    },
                    onToggleRecorderMode = { onAction(AloNativeAppAction.ToggleMessageRecorderMode) },
                    onStartRecording = { onAction(AloNativeAppAction.StartMessageRecording) },
                    onStopRecording = { onAction(AloNativeAppAction.StopMessageRecording(it)) },
                    onSendRecording = { onAction(AloNativeAppAction.StopMessageRecording(cancel = false, send = true)) },
                    onCompleteVideoCircleRecording = { uri, durationSec ->
                        onAction(AloNativeAppAction.CompleteVideoCircleRecording(uri, durationSec))
                    },
                    onSendVideoCircleRecording = { uri, durationSec ->
                        onAction(AloNativeAppAction.CompleteVideoCircleRecording(uri, durationSec, send = true))
                    },
                    onDiscardDraft = { onAction(AloNativeAppAction.DiscardMessageDraft) },
                    onRemoveDraftAttachment = { localUri ->
                        onAction(AloNativeAppAction.RemoveMessageDraftAttachment(localUri))
                    },
                    onSelfDestructSecondsChange = { seconds ->
                        onAction(AloNativeAppAction.UpdateMessageSelfDestructSeconds(seconds))
                    },
                    onSelectForumTopic = { topicId ->
                        onAction(AloNativeAppAction.SelectForumTopic(topicId))
                    },
                    onToggleForumTopicMuted = { topicId ->
                        onAction(AloNativeAppAction.ToggleForumTopicMuted(topicId))
                    },
                    onSendDraft = { onAction(AloNativeAppAction.SendMessageDraft) },
                    onDiscardForwardDraft = { onAction(AloNativeAppAction.DiscardMessageForwardDraft) },
                    onCancelEdit = { onAction(AloNativeAppAction.CancelEditMessage) },
                    onRetargetForwardDraft = { target -> onAction(AloNativeAppAction.RetargetMessageForwardDraft(target)) },
                    onSendCatalogExpression = { media ->
                        onAction(AloNativeAppAction.SendCatalogExpression(media))
                    },
                    onOpenMedia = { onAction(AloNativeAppAction.OpenMedia(it)) },
                    onOpenMediaGallery = { items, startIndex ->
                        onAction(AloNativeAppAction.OpenMediaGallery(items, startIndex))
                    },
                    onPrepareMediaOpen = { message, onReady ->
                        onAction(AloNativeAppAction.PrepareMessageMediaOpen(message, onReady))
                    },
                    onOpenAttachment = { message ->
                        onAction(AloNativeAppAction.OpenMessageAttachment(message))
                    },
                    onOpenChannelPostDiscussion = { postId ->
                        onAction(AloNativeAppAction.OpenChannelPostDiscussion(postId))
                    },
                onDeleteMessage = { messageId, forEveryone ->
                    onAction(AloNativeAppAction.DeleteMessage(messageId, forEveryone))
                },
                onDeleteMessages = { messageIds, forEveryone ->
                    onAction(AloNativeAppAction.DeleteMessages(messageIds, forEveryone))
                },
                    onRescheduleMessage = { messageId ->
                        onAction(AloNativeAppAction.RescheduleMessage(messageId))
                    },
                    onCancelScheduledMessage = { messageId ->
                        onAction(AloNativeAppAction.CancelScheduledMessage(messageId))
                    },
                    onEditMessage = { messageId ->
                        onAction(AloNativeAppAction.StartEditMessage(messageId))
                    },
                    onToggleReaction = { messageId, emoji ->
                        onAction(AloNativeAppAction.ToggleMessageReaction(messageId, emoji))
                    },
                    onToggleMessagePin = { messageId ->
                        onAction(AloNativeAppAction.ToggleMessagePin(messageId))
                    },
                onForwardMessage = { message, target ->
                        onAction(AloNativeAppAction.ForwardMessage(message, target))
                    },
                    onForwardMessages = { messages, target ->
                        onAction(AloNativeAppAction.ForwardMessages(messages, target))
                    },
                    onUpdateGroupTitle = { roomId, title ->
                        onAction(AloNativeAppAction.UpdateGroupChatProfile(roomId, title))
                    },
                    onPickGroupAvatar = { roomId ->
                        onAction(AloNativeAppAction.PickGroupAvatar(roomId))
                    },
                    onAddGroupMember = { roomId, username ->
                        onAction(AloNativeAppAction.AddGroupChatMember(roomId, username))
                    },
                    onRemoveGroupMember = { roomId, userId ->
                        onAction(AloNativeAppAction.RemoveGroupChatMember(roomId, userId))
                    },
                    onLeaveGroupChat = { roomId ->
                        onAction(AloNativeAppAction.LeaveGroupChat(roomId))
                    },
                    onDeleteGroupChat = { roomId ->
                        onAction(AloNativeAppAction.DeleteGroupChat(roomId))
                    },
                    onUpdateGroupMemberRole = { roomId, userId, role ->
                        onAction(AloNativeAppAction.UpdateGroupChatMemberRole(roomId, userId, role))
                    },
                    onLoadGroupManagement = { roomId ->
                        onAction(AloNativeAppAction.LoadGroupManagement(roomId))
                    },
                    onLoadEarlierGroupAdminLog = { roomId ->
                        onAction(AloNativeAppAction.LoadEarlierGroupAdminLog(roomId))
                    },
                    onUpdateGroupSettings = { roomId, policy ->
                        onAction(AloNativeAppAction.UpdateGroupSettings(roomId, policy))
                    },
                    onCreateGroupTopic = { roomId, title, emoji ->
                        onAction(AloNativeAppAction.CreateGroupTopic(roomId, title, emoji))
                    },
                    onUpdateGroupTopic = { roomId, topicId, title, emoji, isClosed, isHidden ->
                        onAction(
                            AloNativeAppAction.UpdateGroupTopic(
                                roomId = roomId,
                                topicId = topicId,
                                title = title,
                                emoji = emoji,
                                isClosed = isClosed,
                                isHidden = isHidden
                            )
                        )
                    },
                    onDeleteGroupTopic = { roomId, topicId ->
                        onAction(AloNativeAppAction.DeleteGroupTopic(roomId, topicId))
                    },
                    onMergeGroupTopic = { roomId, sourceTopicId, targetTopicId ->
                        onAction(AloNativeAppAction.MergeGroupTopic(roomId, sourceTopicId, targetTopicId))
                    },
                    onReviewGroupJoinRequest = { roomId, userId, approve ->
                        onAction(AloNativeAppAction.ReviewGroupJoinRequest(roomId, userId, approve))
                    },
                    onCreateGroupInviteLink = { roomId, label, requiresApproval, usageLimit, expiresAt ->
                        onAction(
                            AloNativeAppAction.CreateGroupInviteLink(
                                roomId = roomId,
                                label = label,
                                requiresApproval = requiresApproval,
                                usageLimit = usageLimit,
                                expiresAt = expiresAt
                            )
                        )
                    },
                    onUpdateGroupInviteLink = {
                            roomId,
                            inviteId,
                            label,
                            requiresApproval,
                            usageLimit,
                            expiresAt ->
                        onAction(
                            AloNativeAppAction.UpdateGroupInviteLink(
                                roomId = roomId,
                                inviteId = inviteId,
                                label = label,
                                requiresApproval = requiresApproval,
                                usageLimit = usageLimit,
                                expiresAt = expiresAt
                            )
                        )
                    },
                    onRevokeGroupInviteLink = { roomId, inviteId ->
                        onAction(AloNativeAppAction.RevokeGroupInviteLink(roomId, inviteId))
                    },
                    onLoadGroupInviteStats = onLoadGroupInviteStats,
                    onUpdateGroupMemberAccess = {
                            roomId,
                            userId,
                            role,
                            adminTitle,
                            isAnonymous,
                            rights,
                            restrictions,
                            changeRole ->
                        onAction(
                            AloNativeAppAction.UpdateGroupMemberAccess(
                                roomId = roomId,
                                userId = userId,
                                role = role,
                                adminTitle = adminTitle,
                                isAnonymous = isAnonymous,
                                adminRights = rights,
                                restrictions = restrictions,
                                changeRole = changeRole
                            )
                        )
                    },
                    onTransferGroupOwnership = { roomId, userId, currentPassword ->
                        onAction(
                            AloNativeAppAction.TransferGroupOwnership(
                                roomId,
                                userId,
                                currentPassword
                            )
                        )
                    },
                    onBanGroupMember = { roomId, userId, reason, expiresAt ->
                        onAction(
                            AloNativeAppAction.BanGroupMember(
                                roomId,
                                userId,
                                reason,
                                expiresAt
                            )
                        )
                    },
                    onUnbanGroupMember = { roomId, userId ->
                        onAction(AloNativeAppAction.UnbanGroupMember(roomId, userId))
                    },
                    onStartVoiceCall = { onAction(AloNativeAppAction.StartVoiceCall) },
                    onStartVideoCall = { onAction(AloNativeAppAction.StartVideoCall) }
                )
            }
        }

        // A secret chat is not an ordinary chat: its own screen over the list (and over a chat it was started from).
        AloDeferredPageEnter(visible = state.activeSecretChatId > 0, shiftPx = pageShiftPx) {
            AloNativeSecretChatScreen(state = state, onAction = onAction)
        }
    }
}

@Composable
private fun MessagesInboxScreen(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit
) {
    var showCommunityCreation by rememberSaveable { mutableStateOf(false) }
    var showSecretPicker by rememberSaveable { mutableStateOf(false) }

    AloChatInbox(
        state = state,
        data = data,
        onAction = onAction,
        onCreateCommunity = { showCommunityCreation = true }
    )

    // «Новый секретный чат», as in Telegram's compose screen: the person picked gets a secret chat, not the ordinary one.
    if (showSecretPicker) {
        val people = data.messagesConversations
            .filter { it.kind == "direct" && it.peer.id > 0 && it.peer.id != data.me.id && it.peer.entityType != "saved" }
            .distinctBy { it.peer.id }
        AlertDialog(
            onDismissRequest = { showSecretPicker = false },
            title = { Text("Новый секретный чат") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Чат будет только на этом устройстве и на устройстве собеседника, которое его примет.",
                        color = AloNativeAppMuted
                    )
                    if (people.isEmpty()) {
                        Text("Начните обычный чат с человеком, и он появится здесь.", color = AloNativeAppMuted)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(people, key = { it.peer.id }) { conversation ->
                            Text(
                                conversation.peer.name.ifBlank { "@" + conversation.peer.username },
                                color = AloNativeAppText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSecretPicker = false
                                        onAction(AloNativeAppAction.StartSecretChat(conversation.peer.id))
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSecretPicker = false }) { Text("Отмена") }
            }
        )
    }

    if (showCommunityCreation) {
        CommunityCreationDialog(
            onNewSecret = if (state.secretChatsAvailable) {
                {
                    showCommunityCreation = false
                    showSecretPicker = true
                }
            } else {
                null
            },
            onDismiss = { showCommunityCreation = false },
            onJoin = { token ->
                showCommunityCreation = false
                onAction(AloNativeAppAction.JoinGroupInvite(token))
            },
            onCreate = { title, description, kind, joinPolicy, username ->
                showCommunityCreation = false
                onAction(
                    AloNativeAppAction.CreateGroupChat(
                        title = title,
                        description = description,
                        kind = kind,
                        joinPolicy = joinPolicy,
                        username = username
                    )
                )
            }
        )
    }
}

@Composable
private fun CommunityCreationDialog(
    onNewSecret: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
    onCreate: (String, String, String, String, String) -> Unit
) {
    var kind by rememberSaveable { mutableStateOf("group") }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var joinPolicy by rememberSaveable { mutableStateOf("invite") }
    var username by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    val normalizedUsername = username.trim().removePrefix("@").lowercase(Locale.ROOT)
    val usernameValid = Regex("^[a-z0-9_]{5,32}$").matches(normalizedUsername)
    val canCreate = title.trim().length >= 2 && (joinPolicy != "public" || usernameValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (kind == "channel") "Новый канал" else "Новая группа")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (onNewSecret != null) {
                    TextButton(onClick = onNewSecret, modifier = Modifier.fillMaxWidth()) {
                        Text("🔒 Новый секретный чат", color = AloSecretGreen)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { kind = "group" }
                    ) {
                        Text(
                            "Группа",
                            color = if (kind == "group") AloNativeAppAccent else AloNativeAppMuted
                        )
                    }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { kind = "channel" }
                    ) {
                        Text(
                            "Канал",
                            color = if (kind == "channel") AloNativeAppAccent else AloNativeAppMuted
                        )
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = {
                        Text(if (kind == "channel") "Название канала" else "Название группы")
                    }
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = { description = it },
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Описание") }
                )
                Text("Доступ", color = AloNativeAppMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "invite" to "Ссылка",
                        "request" to "Заявки",
                        "public" to "Открытое"
                    ).forEach { (value, label) ->
                        // Small side padding and one line: with the button's default 12dp sides «Открытое» broke in two
                        // («Открыто» / «е») in the dialog's width.
                        TextButton(
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            onClick = { joinPolicy = value }
                        ) {
                            Text(
                                label,
                                color = if (joinPolicy == value) AloNativeAppAccent else AloNativeAppMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                if (joinPolicy == "public") {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        isError = username.isNotBlank() && !usernameValid,
                        prefix = { Text("@") },
                        label = { Text("Публичный адрес") },
                        supportingText = {
                            Text("5–32 латинских символа, цифры или _")
                        }
                    )
                }
                Text("Вступить по приглашению", color = AloNativeAppMuted)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = inviteCode,
                    onValueChange = { inviteCode = it },
                    singleLine = true,
                    label = { Text("Код приглашения") },
                    trailingIcon = {
                        TextButton(
                            enabled = inviteCode.trim().isNotEmpty(),
                            onClick = { onJoin(inviteCode.trim()) }
                        ) {
                            Text("Открыть")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    onCreate(
                        title.trim(),
                        description.trim(),
                        kind,
                        joinPolicy,
                        normalizedUsername
                    )
                }
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = AloNativeAppText
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted
                )
            }
        }

        GhostCircleButton(
            onClick = onRefresh,
            icon = Icons.Rounded.Refresh,
            loading = isRefreshing
        )
    }
}


