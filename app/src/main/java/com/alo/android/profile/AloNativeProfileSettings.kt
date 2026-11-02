package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun settingsTextFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = AloNativeAppText,
    unfocusedTextColor = AloNativeAppText,
    focusedContainerColor = AloNativeAppSurfaceRaised,
    unfocusedContainerColor = AloNativeAppSurfaceRaised,
    focusedIndicatorColor = AloNativeAppAccent,
    unfocusedIndicatorColor = AloNativeAppSeparator,
    focusedLabelColor = AloNativeAppAccent,
    unfocusedLabelColor = AloNativeAppMuted,
    cursorColor = AloNativeAppAccent
)

private enum class AloSettingsPage {
    Root,
    Privacy,
    BlockedUsers,
    Notifications,
    DataStorage,
    Theme,
    Business,
    Security,
    PasswordChange,
    Accounts,
    Passkeys,
    ExportData,
    DeleteAccount
}

@Composable
internal fun ProfileSettingsSheet(
    /** Opened on this page, Back from it closes the settings, as iOS's AloSocialSettingsSheet(initialPage:). */
    startPage: String = "Root",
    me: AloCurrentUser,
    notificationSettings: AloNotificationSettings,
    privacySettings: AloPrivacySettings,
    blockedUserIds: Set<Int>,
    blockedUserPeers: List<AloConversationPeer>,
    blockActionUserId: Int?,
    themeMode: AloThemeMode,
    isRefreshing: Boolean,
    securitySessions: List<AloSecuritySession>,
    securityEvents: List<AloSecurityEvent>,
    isSecurityLoading: Boolean,
    securityNotice: String,
    savedAccounts: List<AloStoredAccount>,
    isAccountSecurityLoading: Boolean,
    passkeys: List<AloPasskey>,
    isPasskeyLoading: Boolean,
    businessProfileData: AloBusinessProfileData?,
    isBusinessProfileLoading: Boolean,
    conversations: List<AloConversationSummary>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onNotificationSettingsChange: (AloNotificationSettings) -> Unit,
    onPrivacySettingsChange: (AloPrivacySettings) -> Unit,
    onSetUserBlocked: (userId: Int, blocked: Boolean) -> Unit,
    onThemeModeChange: (AloThemeMode) -> Unit,
    onClearCache: () -> Unit,
    onLoadSecuritySessions: () -> Unit,
    onRevokeSecuritySession: (Int) -> Unit,
    onRevokeOtherSecuritySessions: () -> Unit,
    onChangePassword: (String, String) -> Unit,
    onLoadSecurityAccounts: () -> Unit,
    onSwitchSecurityAccount: (Int) -> Unit,
    onLoadPasskeys: () -> Unit,
    onRegisterPasskey: () -> Unit,
    onOpenQrLoginScanner: () -> Unit,
    /** «Добавить аккаунт»: keep this session on the device and sign in to another. */
    onAddAccount: () -> Unit = {},
    onExportAccountData: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onSaveBusinessProfile: (
        enabled: Boolean,
        businessName: String,
        category: String,
        hours: Map<String, String>,
        greetingMessage: String,
        autoReplyEnabled: Boolean,
        autoReplyMessage: String
    ) -> Unit,
    onAddBusinessQuickReply: (shortcut: String, title: String, body: String) -> Unit,
    onDeleteBusinessQuickReply: (Int) -> Unit,
    onSaveBusinessChatLabel: (
        chatKind: String,
        peerId: Int,
        roomId: Int,
        label: String,
        color: String
    ) -> Unit,
    onDeleteBusinessChatLabel: (Int) -> Unit,
    onLogout: () -> Unit,
    /** Admins and moderators only. */
    onOpenModeration: (() -> Unit)? = null,
    /** «Панель автора»: the creator's reach, kept here rather than on the profile. */
    onOpenCreatorStats: (() -> Unit)? = null,
    /** The reaction a double tap on a message sends, in every app of the account (a row after «Уведомления», as on iOS). */
    onSetFavoriteReaction: (String) -> Unit = {},
    /** «Редактировать профиль»: name, @username and about text. */
    onEditProfile: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(startPage) }
    var favoriteReactionOpen by remember { mutableStateOf(false) }
    val deviceLinkService = LocalAloDeviceLink.current
    var isDeviceLinkOpen by rememberSaveable { mutableStateOf(false) }
    if (isDeviceLinkOpen && deviceLinkService != null) {
        AloDeviceLinkSheet(service = deviceLinkService, onDismiss = { isDeviceLinkOpen = false })
    }
    var currentNotifications by remember(notificationSettings) { mutableStateOf(notificationSettings) }
    var currentPrivacy by remember(privacySettings) { mutableStateOf(privacySettings) }
    val appContext = LocalContext.current.applicationContext
    var notificationSoundsEnabled by remember { mutableStateOf(AloPushRegistration.soundsEnabled(appContext)) }
    var notificationPreviewsEnabled by remember { mutableStateOf(AloPushRegistration.previewsEnabled(appContext)) }
    var cacheNotice by rememberSaveable { mutableStateOf("") }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var exportPassword by remember { mutableStateOf("") }
    var deleteAccountPassword by remember { mutableStateOf("") }
    var deleteAccountConfirmation by remember { mutableStateOf("") }
    var deleteAccountConfirmationPresented by rememberSaveable { mutableStateOf(false) }
    var pendingSessionRevocation by remember { mutableStateOf<AloSecuritySession?>(null) }
    var revokeOtherSessionsConfirmationPresented by rememberSaveable { mutableStateOf(false) }
    var businessEnabled by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.enabled ?: false)
    }
    var businessName by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.businessName.orEmpty())
    }
    var businessCategory by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.category.orEmpty())
    }
    var businessHours by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.hours?.let { it["summary"] ?: it["Основные"] }.orEmpty())
    }
    var greetingMessage by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.greetingMessage.orEmpty())
    }
    var autoReplyEnabled by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.autoReplyEnabled ?: false)
    }
    var autoReplyMessage by remember(businessProfileData?.profile) {
        mutableStateOf(businessProfileData?.profile?.autoReplyMessage.orEmpty())
    }
    var quickReplyShortcut by rememberSaveable { mutableStateOf("") }
    var quickReplyTitle by rememberSaveable { mutableStateOf("") }
    var quickReplyBody by rememberSaveable { mutableStateOf("") }
    var businessLabelTargetKey by rememberSaveable { mutableStateOf("") }
    var businessLabelText by rememberSaveable { mutableStateOf("") }
    var businessLabelColor by rememberSaveable { mutableStateOf("blue") }
    var businessLabelTargetMenuExpanded by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // Telegram opens Settings as a page: it fades in sliding 48dp from the right, and back reverses it.
    val pageShiftPx = rememberAloPageShiftPx()
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AloPageMotionMs, easing = AloTelegramDecelerate),
        label = "settingsEntrance"
    )
    val activePage = runCatching { AloSettingsPage.valueOf(page) }.getOrDefault(AloSettingsPage.Root)
    val hasPendingPrivacyChanges = currentPrivacy != privacySettings
    val hasPendingNotificationChanges = currentNotifications != notificationSettings
    val hasPendingSettingsChanges = when (activePage) {
        AloSettingsPage.Privacy -> hasPendingPrivacyChanges
        AloSettingsPage.Notifications -> hasPendingNotificationChanges
        else -> false
    }
    val blockedUsers = remember(blockedUserIds, blockedUserPeers) {
        blockedUserIds.sorted().map { userId ->
            userId to blockedUserPeers.firstOrNull { it.id == userId }
        }
    }
    val businessLabelConversations = remember(conversations) {
        conversations.distinctBy { "${it.kind}:${it.peerId}:${it.roomId}" }
    }
    val selectedBusinessLabelConversation = businessLabelConversations.firstOrNull {
        "${it.kind}:${it.peerId}:${it.roomId}" == businessLabelTargetKey
    }
    val businessChatLabels = businessProfileData?.chatLabels.orEmpty()

    LaunchedEffect(businessLabelConversations) {
        if (selectedBusinessLabelConversation == null) {
            businessLabelTargetKey = businessLabelConversations.firstOrNull()?.let {
                "${it.kind}:${it.peerId}:${it.roomId}"
            }.orEmpty()
        }
    }

    fun updateNotifications(transform: (AloNotificationSettings) -> AloNotificationSettings) {
        currentNotifications = transform(currentNotifications)
    }

    val allNotificationsEnabled = currentNotifications.likesEnabled &&
        currentNotifications.commentsEnabled &&
        currentNotifications.repostsEnabled &&
        currentNotifications.followsEnabled &&
        currentNotifications.messagesEnabled &&
        currentNotifications.friendRequestsEnabled &&
        currentNotifications.friendAcceptsEnabled &&
        currentNotifications.channelSubscriptionsEnabled &&
        currentNotifications.commentLikesEnabled

    fun setAllNotifications(enabled: Boolean) {
        updateNotifications {
            it.copy(
                likesEnabled = enabled,
                commentsEnabled = enabled,
                repostsEnabled = enabled,
                followsEnabled = enabled,
                messagesEnabled = enabled,
                friendRequestsEnabled = enabled,
                friendAcceptsEnabled = enabled,
                channelSubscriptionsEnabled = enabled,
                commentLikesEnabled = enabled
            )
        }
    }

    fun updatePrivacy(transform: (AloPrivacySettings) -> AloPrivacySettings) {
        currentPrivacy = transform(currentPrivacy)
    }

    fun updateExclusiveChoice(select: String, currentValue: String, onSelect: (String) -> Unit) {
        onSelect(if (currentValue == select) currentValue else select)
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    LaunchedEffect(activePage) {
        if (
            activePage == AloSettingsPage.Security &&
            (securitySessions.isEmpty() || securityEvents.isEmpty()) &&
            !isSecurityLoading
        ) {
            onLoadSecuritySessions()
        }
        if (activePage == AloSettingsPage.Accounts) {
            onLoadSecurityAccounts()
        }
        if (activePage == AloSettingsPage.Passkeys && passkeys.isEmpty() && !isPasskeyLoading) {
            onLoadPasskeys()
        }
    }

    LaunchedEffect(securityNotice, activePage) {
        if (securityNotice.startsWith("Пароль изменён")) {
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
            if (activePage == AloSettingsPage.PasswordChange) {
                page = AloSettingsPage.Security.name
            }
        }
        if (securityNotice.startsWith("Экспорт сохранён")) {
            exportPassword = ""
        }
    }

    fun dismissWithAnimation() {
        visible = false
        scope.launch {
            delay(AloPageMotionMs.toLong() + 10)
            onDismiss()
        }
    }

    /** The page a back step returns to, or null on the page settings were opened at (back then closes them). */
    fun parentOf(current: AloSettingsPage): AloSettingsPage? = when {
        current.name == startPage -> null
        current == AloSettingsPage.BlockedUsers -> AloSettingsPage.Privacy
        current == AloSettingsPage.PasswordChange || current == AloSettingsPage.Accounts ||
            current == AloSettingsPage.Passkeys || current == AloSettingsPage.ExportData ||
            current == AloSettingsPage.DeleteAccount -> AloSettingsPage.Security
        else -> AloSettingsPage.Root
    }

    fun navigateBack() {
        val parent = parentOf(activePage)
        if (parent == null) dismissWithAnimation() else page = parent.name
    }

    if (favoriteReactionOpen) {
        FavoriteReactionSheet(
            current = me.favoriteReaction,
            onChoose = onSetFavoriteReaction,
            onDismiss = { favoriteReactionOpen = false }
        )
    }

    // The system gesture and the visible «Назад» follow the same settings hierarchy. Between pages the back swipe is seekable:
    // the page leaves and the one under it comes back with the finger (the transition is driven by the gesture's progress),
    // and only then is the step taken; before, the page switched at once when the finger was released.
    val pageTransition = remember { SeekableTransitionState(activePage) }
    LaunchedEffect(activePage) { pageTransition.animateTo(activePage) }
    val parentPage = parentOf(activePage)
    PredictiveBackHandler(enabled = !isDeviceLinkOpen && parentPage != null) { events ->
        val parent = parentPage ?: return@PredictiveBackHandler
        try {
            events.collect { event -> pageTransition.seekTo(event.progress, targetState = parent) }
            page = parent.name
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            pageTransition.animateTo(activePage)
        }
    }
    BackHandler(enabled = !isDeviceLinkOpen && parentPage == null, onBack = ::navigateBack)

    fun saveActiveSettings() {
        when (activePage) {
            AloSettingsPage.Privacy -> if (hasPendingPrivacyChanges) {
                onPrivacySettingsChange(currentPrivacy)
            }
            AloSettingsPage.Notifications -> if (hasPendingNotificationChanges) {
                onNotificationSettingsChange(currentNotifications)
            }
            else -> Unit
        }
    }

    pendingSessionRevocation?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingSessionRevocation = null },
            title = { Text("Завершить сеанс?") },
            text = { Text("На этом устройстве потребуется войти в Alo заново.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSessionRevocation = null
                    onRevokeSecuritySession(session.id)
                }) {
                    Text("Завершить", color = Color(0xFFFF5C68))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSessionRevocation = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (revokeOtherSessionsConfirmationPresented) {
        AlertDialog(
            onDismissRequest = { revokeOtherSessionsConfirmationPresented = false },
            title = { Text("Завершить остальные сеансы?") },
            text = { Text("Все другие устройства будут отключены от аккаунта. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    revokeOtherSessionsConfirmationPresented = false
                    onRevokeOtherSecuritySessions()
                }) {
                    Text("Завершить остальные", color = Color(0xFFFF5C68))
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeOtherSessionsConfirmationPresented = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (deleteAccountConfirmationPresented) {
        AlertDialog(
            onDismissRequest = { deleteAccountConfirmationPresented = false },
            title = { Text("Удалить аккаунт?") },
            text = {
                Text(
                    "Будут удалены данные аккаунта и текущая сессия на этом устройстве. Это действие нельзя отменить."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAccountConfirmationPresented = false
                    onDeleteAccount(deleteAccountPassword)
                    deleteAccountPassword = ""
                    deleteAccountConfirmation = ""
                }) {
                    Text("Удалить аккаунт", color = AloNativeAppDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAccountConfirmationPresented = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    AloNaturalLineHeight {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = pageShiftPx * (1f - entrance)
                    alpha = entrance
                },
            shape = RoundedCornerShape(0.dp),
            color = AloNativeAppBackground,
            border = BorderStroke(0.dp, Color.Transparent),
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                AloSettingsNavigationBar(
                    onBack = ::navigateBack,
                    title = when (activePage) {
                        AloSettingsPage.Root -> "Настройки"
                        AloSettingsPage.Privacy -> "Приватность"
                        AloSettingsPage.BlockedUsers -> "Чёрный список"
                        AloSettingsPage.Notifications -> "Уведомления"
                        AloSettingsPage.DataStorage -> "Данные и память"
                        AloSettingsPage.Theme -> "Оформление"
                        AloSettingsPage.Business -> "Бизнес-профиль"
                        AloSettingsPage.Security -> "Безопасность"
                        AloSettingsPage.PasswordChange -> "Смена пароля"
                        AloSettingsPage.Accounts -> "Аккаунты"
                        AloSettingsPage.Passkeys -> "Ключи доступа"
                        AloSettingsPage.ExportData -> "Экспорт данных"
                        AloSettingsPage.DeleteAccount -> "Удалить аккаунт"
                    }
                ) {
                    if (activePage == AloSettingsPage.Privacy || activePage == AloSettingsPage.Notifications) {
                        Box(
                            modifier = Modifier
                                .widthIn(min = 82.dp)
                                .heightIn(min = 48.dp)
                                .clickable(enabled = hasPendingSettingsChanges, onClick = ::saveActiveSettings),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = "Сохранить",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (hasPendingSettingsChanges) AloNativeAppAccent else AloNativeAppMuted.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                rememberTransition(pageTransition, label = "settingsPageContent").AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { aloPageTransform(targetState.ordinal > initialState.ordinal, pageShiftPx) }
                ) {
                    when (it) {
                        AloSettingsPage.Root -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                item {
                                    SettingsSection(title = "") {
                                        if (onEditProfile != null) {
                                            RootSettingsRow(
                                                icon = Icons.Outlined.Edit,
                                                title = "Редактировать профиль",
                                                onClick = onEditProfile
                                            )
                                        }
                                        RootSettingsRow(
                                            icon = Icons.Outlined.Shield,
                                            title = "Безопасность",
                                            onClick = { page = AloSettingsPage.Security.name }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.PanTool,
                                            title = "Приватность",
                                            onClick = { page = AloSettingsPage.Privacy.name }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.Notifications,
                                            title = "Уведомления",
                                            onClick = { page = AloSettingsPage.Notifications.name }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.Palette,
                                            title = "Оформление",
                                            detail = when (themeMode) {
                                                AloThemeMode.Light -> "Светлая"
                                                AloThemeMode.System -> "Система"
                                                AloThemeMode.Dark -> "Тёмная"
                                            },
                                            onClick = { page = AloSettingsPage.Theme.name }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.EmojiEmotions,
                                            title = "Любимая реакция",
                                            value = me.favoriteReaction,
                                            onClick = { favoriteReactionOpen = true }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.Storage,
                                            title = "Данные и память",
                                            onClick = { page = AloSettingsPage.DataStorage.name }
                                        )
                                        RootSettingsRow(
                                            icon = Icons.Outlined.BusinessCenter,
                                            title = "Бизнес-профиль",
                                            onClick = { page = AloSettingsPage.Business.name }
                                        )
                                        if (onOpenCreatorStats != null) {
                                            RootSettingsRow(
                                                icon = Icons.Outlined.BarChart,
                                                title = "Панель автора",
                                                onClick = onOpenCreatorStats
                                            )
                                        }
                                        if (onOpenModeration != null) {
                                            RootSettingsRow(
                                                icon = Icons.Outlined.VerifiedUser,
                                                title = "Модерация",
                                                onClick = onOpenModeration
                                            )
                                        }
                                    }
                                }
                                item {
                                    val updater = LocalAloAppUpdater.current
                                    Text(
                                        text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { updater?.checkNow() }
                                            .padding(top = 22.dp),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        color = AloNativeAppMuted
                                    )
                                }
                            }
                        }

                        AloSettingsPage.DataStorage -> DataStorageSettingsPage()

                        AloSettingsPage.Notifications -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(22.dp)
                            ) {
                                item {
                                    SettingsSection(title = "Основные") {
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.Notifications,
                                            title = "Уведомления",
                                            subtitle = "Включение или отключение всех уведомлений",
                                            checked = allNotificationsEnabled,
                                            onCheckedChange = {
                                                setAllNotifications(it)
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.NotificationsActive,
                                            title = "Уведомления со звуком",
                                            subtitle = "Воспроизводить звуки уведомлений",
                                            checked = notificationSoundsEnabled,
                                            onCheckedChange = {
                                                notificationSoundsEnabled = it
                                                AloPushRegistration.setSoundsEnabled(appContext, it)
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.Visibility,
                                            title = "Показывать текст",
                                            subtitle = "Имя и текст сообщения в уведомлении. Из секретных чатов — только «Новое сообщение»",
                                            checked = notificationPreviewsEnabled,
                                            onCheckedChange = {
                                                notificationPreviewsEnabled = it
                                                AloPushRegistration.setPreviewsEnabled(appContext, it)
                                            }
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Пользователи") {
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.PersonAdd,
                                            title = "Подписки",
                                            subtitle = "Уведомления о новых подписчиках",
                                            checked = currentNotifications.followsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(followsEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.GroupAdd,
                                            title = "Заявки в друзья",
                                            subtitle = "Уведомления о новых заявках в друзья",
                                            checked = currentNotifications.friendRequestsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(friendRequestsEnabled = it) }
                                            }
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Посты") {
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.Favorite,
                                            iconTint = Color(0xFFFF475C),
                                            title = "Лайки и реакции",
                                            subtitle = "Уведомления о реакциях на ваши посты и комментарии",
                                            checked = currentNotifications.likesEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(likesEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.Forum,
                                            title = "Комментарии и ответы",
                                            subtitle = "Уведомления о новых комментариях и ответах",
                                            checked = currentNotifications.commentsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(commentsEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.AlternateEmail,
                                            iconTint = AloNativeAppSuccess,
                                            title = "Упоминания",
                                            subtitle = "Уведомления, когда вас упоминают в постах",
                                            checked = currentNotifications.repostsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(repostsEnabled = it) }
                                            }
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Дополнительно") {
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.ChatBubble,
                                            title = "Сообщения",
                                            subtitle = "Новые личные и групповые сообщения",
                                            checked = currentNotifications.messagesEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(messagesEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.ThumbUp,
                                            iconTint = Color(0xFF7D6BF5),
                                            title = "Лайки комментариев",
                                            subtitle = "Реакции на ваши комментарии",
                                            checked = currentNotifications.commentLikesEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(commentLikesEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.HowToReg,
                                            iconTint = AloNativeAppSuccess,
                                            title = "Принятые заявки",
                                            subtitle = "Когда другой пользователь принимает вашу заявку",
                                            checked = currentNotifications.friendAcceptsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(friendAcceptsEnabled = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            icon = Icons.Rounded.Campaign,
                                            iconTint = AloNativeAppSuccess,
                                            title = "Сообщества",
                                            subtitle = "События сообществ, на которые вы подписаны",
                                            checked = currentNotifications.channelSubscriptionsEnabled,
                                            onCheckedChange = {
                                                updateNotifications { value -> value.copy(channelSubscriptionsEnabled = it) }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        AloSettingsPage.Privacy -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item {
                                    ReferencePrivacyChoiceCard(
                                        title = "Личные сообщения",
                                        subtitle = "Кто может писать вам личные сообщения",
                                        options = listOf(
                                            "all" to "Все",
                                            "friends" to "Только друзья",
                                            "friends_of_friends" to "Друзья и друзья друзей"
                                        ),
                                        selected = currentPrivacy.messageScope,
                                        onSelect = {
                                            updateExclusiveChoice(it, currentPrivacy.messageScope) { chosen ->
                                                updatePrivacy { value -> value.copy(messageScope = chosen) }
                                            }
                                        }
                                    )
                                }
                                item {
                                    ReferencePrivacyChoiceCard(
                                        title = "Профиль",
                                        subtitle = "Кому доступен ваш профиль",
                                        options = listOf(
                                            "all" to "Все",
                                            "friends" to "Только друзья"
                                        ),
                                        selected = currentPrivacy.profileVisibility,
                                        onSelect = {
                                            updateExclusiveChoice(it, currentPrivacy.profileVisibility) { chosen ->
                                                updatePrivacy { value -> value.copy(profileVisibility = chosen) }
                                            }
                                        }
                                    )
                                }
                                item {
                                    ReferencePrivacyToggleCard(
                                        title = "Онлайн-статус",
                                        subtitle = "Показывать время последнего визита",
                                        checked = !currentPrivacy.hideActivity,
                                        onCheckedChange = {
                                            updatePrivacy { value -> value.copy(hideActivity = !it) }
                                        }
                                    )
                                }
                                item {
                                    ReferencePrivacyActionCard(
                                        icon = Icons.Rounded.Block,
                                        title = "Чёрный список",
                                        onClick = { page = AloSettingsPage.BlockedUsers.name }
                                    )
                                }
                                item {
                                    SettingsSection(title = "Дополнительно") {
                                        SettingsToggleRow(
                                            title = "Заявки в друзья",
                                            subtitle = "Разрешать другим отправлять запросы",
                                            checked = currentPrivacy.allowFriendRequests,
                                            onCheckedChange = {
                                                updatePrivacy { value -> value.copy(allowFriendRequests = it) }
                                            }
                                        )
                                        SettingsToggleRow(
                                            title = "Пересылка сообщений",
                                            subtitle = "Разрешать другим пересылать ваши сообщения",
                                            checked = currentPrivacy.allowMessageForwards,
                                            onCheckedChange = {
                                                updatePrivacy { value -> value.copy(allowMessageForwards = it) }
                                            }
                                        )
                                        SettingsChoiceCard(
                                            title = "Кто может звонить",
                                            options = listOf(
                                                "friends" to "Друзья",
                                                "friends_of_friends" to "Друзья и друзья друзей",
                                                "all" to "Все, включая незнакомых",
                                                "none" to "Никто"
                                            ),
                                            selected = currentPrivacy.callScope,
                                            onSelect = {
                                                updateExclusiveChoice(it, currentPrivacy.callScope) { chosen ->
                                                    updatePrivacy { value -> value.copy(callScope = chosen) }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        AloSettingsPage.BlockedUsers -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(22.dp)
                            ) {
                                item {
                                    SettingsSection(title = "") {
                                        if (blockedUsers.isEmpty()) {
                                            SettingsInfoRow(
                                                title = "Нет заблокированных пользователей",
                                                value = "Здесь появятся пользователи, которых вы заблокировали."
                                            )
                                        } else {
                                            blockedUsers.forEach { (userId, peer) ->
                                                BlockedUserRow(
                                                    userId = userId,
                                                    peer = peer,
                                                    isUpdating = blockActionUserId == userId,
                                                    onUnblock = { onSetUserBlocked(userId, false) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AloSettingsPage.Theme -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(22.dp)
                            ) {
                                item {
                                    Text(
                                        "Тема",
                                        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                                        color = AloNativeAppMuted,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    SettingsThemeCards(
                                        options = listOf(
                                            AloThemeMode.System.name to "Как в системе",
                                            AloThemeMode.Dark.name to "Тёмная",
                                            AloThemeMode.Light.name to "Светлая"
                                        ),
                                        selected = themeMode.name,
                                        onSelect = { id ->
                                            onThemeModeChange(runCatching { AloThemeMode.valueOf(id) }.getOrDefault(AloThemeMode.System))
                                        }
                                    )
                                    Text(
                                        "«Как в системе» меняет тему вместе с телефоном.",
                                        modifier = Modifier.padding(start = 2.dp, top = 10.dp),
                                        color = AloNativeAppMuted,
                                        fontSize = 13.5.sp
                                    )
                                }
                            }
                        }

                        AloSettingsPage.Business -> {
                            // iOS's BusinessProfileSettingsContent: labelled fields on 20pt cards, one big Save.
                            val autoReplyMissing = autoReplyEnabled && autoReplyMessage.isBlank()
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                item {
                                    BusinessCard {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Включить бизнес-профиль", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            AloSwitch(checked = businessEnabled, onCheckedChange = { businessEnabled = it }, onColor = AloNativeAppAccent)
                                        }
                                        Text(
                                            "Покажите клиентам актуальные часы, приветствие и ответы без изменения личных чатов.",
                                            color = AloNativeAppMuted, fontSize = 13.5.sp
                                        )
                                    }
                                }
                                item { BusinessHeader("ПРОФИЛЬ") }
                                item {
                                    BusinessCard {
                                        BusinessField("Название", businessName, "Например, Alo Studio") { businessName = it.take(96) }
                                        BusinessDivider()
                                        BusinessField("Категория", businessCategory, "Услуги, магазин, кафе…") { businessCategory = it.take(64) }
                                        BusinessDivider()
                                        BusinessField("Часы работы", businessHours, "Пн–Пт 10:00–19:00") { businessHours = it.take(120) }
                                    }
                                }
                                item { BusinessHeader("СООБЩЕНИЯ") }
                                item {
                                    BusinessCard {
                                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Text("Приветствие", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                            BusinessTextInput(greetingMessage, "Здравствуйте! Чем можем помочь?", minLines = 2, maxLines = 4) {
                                                greetingMessage = it.take(500)
                                            }
                                        }
                                        BusinessDivider()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Автоответ", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            AloSwitch(checked = autoReplyEnabled, onCheckedChange = { autoReplyEnabled = it }, onColor = AloNativeAppAccent)
                                        }
                                        if (autoReplyEnabled) {
                                            BusinessTextInput(autoReplyMessage, "Мы ответим в рабочее время", minLines = 2, maxLines = 4) {
                                                autoReplyMessage = it.take(500)
                                            }
                                        }
                                        Text(
                                            "Автоответ хранится в профиле. В зашифрованных чатах он отправляется только с авторизованного устройства владельца.",
                                            color = AloNativeAppMuted, fontSize = 12.5.sp
                                        )
                                    }
                                }
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .graphicsLayer { alpha = if (autoReplyMissing) 0.46f else 1f }
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(AloNativeAppAccent)
                                            .clickable(enabled = !isBusinessProfileLoading && !autoReplyMissing) {
                                                onSaveBusinessProfile(
                                                    businessEnabled,
                                                    businessName,
                                                    businessCategory,
                                                    businessHours.takeIf { it.isNotBlank() }?.let { mapOf("summary" to it) }.orEmpty(),
                                                    greetingMessage,
                                                    autoReplyEnabled,
                                                    autoReplyMessage
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isBusinessProfileLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                        } else {
                                            Text("Сохранить бизнес-профиль", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                item { BusinessHeader("БЫСТРЫЕ ОТВЕТЫ") }
                                item {
                                    BusinessCard {
                                        val quickReplies = businessProfileData?.quickReplies.orEmpty()
                                        quickReplies.forEach { reply ->
                                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text("/${reply.shortcut}", color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                    Text(reply.body, color = AloNativeAppText, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                BusinessTrashButton(enabled = !isBusinessProfileLoading) { onDeleteBusinessQuickReply(reply.id) }
                                            }
                                            BusinessDivider()
                                        }
                                        BusinessTextInput(quickReplyShortcut, "Команда, например price") { quickReplyShortcut = it.take(24) }
                                        BusinessDivider()
                                        BusinessTextInput(quickReplyBody, "Текст быстрого ответа", minLines = 2, maxLines = 4) { quickReplyBody = it.take(500) }
                                        val canAdd = !isBusinessProfileLoading && quickReplyShortcut.isNotBlank() && quickReplyBody.isNotBlank()
                                        BusinessLinkButton(Icons.Rounded.Add, "Добавить быстрый ответ", enabled = canAdd) {
                                            onAddBusinessQuickReply(quickReplyShortcut, quickReplyShortcut, quickReplyBody)
                                            quickReplyShortcut = ""
                                            quickReplyTitle = ""
                                            quickReplyBody = ""
                                        }
                                    }
                                }
                                item { BusinessHeader("МЕТКИ ЧАТОВ") }
                                item {
                                    BusinessCard {
                                        businessChatLabels.forEach { chatLabel ->
                                            val targetName = businessLabelConversations.firstOrNull { conversation ->
                                                conversation.kind == chatLabel.chatKind &&
                                                    conversation.peerId == chatLabel.peerId &&
                                                    conversation.roomId == chatLabel.roomId
                                            }?.peer?.name?.ifBlank { "Чат" }
                                                ?: if (chatLabel.chatKind == "group") "Группа #${chatLabel.roomId}" else "Диалог #${chatLabel.peerId}"
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                                Box(Modifier.size(12.dp).clip(CircleShape).background(businessLabelColorValue(chatLabel.color)))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(chatLabel.label, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(targetName, color = AloNativeAppMuted, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                BusinessTrashButton(enabled = !isBusinessProfileLoading) { onDeleteBusinessChatLabel(chatLabel.id) }
                                            }
                                            BusinessDivider()
                                        }
                                        if (businessLabelConversations.isEmpty()) {
                                            Text("Чтобы добавить метку, сначала откройте реальный диалог или группу.", color = AloNativeAppMuted, fontSize = 13.5.sp)
                                        } else {
                                            Box {
                                                Row(
                                                    Modifier.fillMaxWidth().clickable { businessLabelTargetMenuExpanded = true },
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        selectedBusinessLabelConversation?.peer?.name?.ifBlank { "Выбрать чат" } ?: "Выбрать чат",
                                                        color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                                    )
                                                    Icon(Icons.Rounded.UnfoldMore, null, tint = AloNativeAppMuted, modifier = Modifier.size(16.dp))
                                                }
                                                AloDropdownMenu(
                                                    expanded = businessLabelTargetMenuExpanded,
                                                    onDismissRequest = { businessLabelTargetMenuExpanded = false },
                                                    containerColor = AloNativeAppSurface
                                                ) {
                                                    businessLabelConversations.forEach { conversation ->
                                                        val key = "${conversation.kind}:${conversation.peerId}:${conversation.roomId}"
                                                        DropdownMenuItem(
                                                            text = { Text(conversation.peer.name.ifBlank { "Чат" }, color = AloNativeAppText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                            leadingIcon = {
                                                                Box(Modifier.size(20.dp)) {
                                                                    if (key == businessLabelTargetKey) Icon(Icons.Rounded.Check, null, tint = AloNativeAppText, modifier = Modifier.size(20.dp))
                                                                }
                                                            },
                                                            onClick = {
                                                                businessLabelTargetKey = key
                                                                businessLabelTargetMenuExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            BusinessDivider()
                                            BusinessTextInput(businessLabelText, "Название метки") { businessLabelText = it.take(40) }
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                businessLabelColors.forEach { (name, color) ->
                                                    Box(
                                                        Modifier
                                                            .size(28.dp)
                                                            .clip(CircleShape)
                                                            .background(color)
                                                            .then(if (businessLabelColor == name) Modifier.border(2.dp, AloNativeAppText, CircleShape) else Modifier)
                                                            .clickable { businessLabelColor = name }
                                                    )
                                                }
                                            }
                                            BusinessLinkButton(
                                                Icons.AutoMirrored.Outlined.Label,
                                                "Сохранить метку",
                                                enabled = !isBusinessProfileLoading && selectedBusinessLabelConversation != null && businessLabelText.isNotBlank()
                                            ) {
                                                selectedBusinessLabelConversation?.let { conversation ->
                                                    onSaveBusinessChatLabel(conversation.kind, conversation.peerId, conversation.roomId, businessLabelText, businessLabelColor)
                                                    businessLabelText = ""
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AloSettingsPage.Security -> {
                            // iOS's securityPage with AloAccountSecurityControls, card by card.
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(22.dp)
                            ) {
                                item {
                                    SettingsSection(title = "") {
                                        SettingsNavRow(
                                            icon = Icons.Rounded.Key,
                                            title = "Сменить пароль",
                                            subtitle = "После смены пароля другие устройства будут отключены",
                                            onClick = { page = AloSettingsPage.PasswordChange.name }
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Аккаунты на устройстве", compactTitle = true) {
                                        if (savedAccounts.isEmpty()) {
                                            SettingsNoteRow("Сеанс появится здесь после безопасного сохранения входа.")
                                        } else {
                                            savedAccounts.forEach { account ->
                                                SavedAccountRow(
                                                    account = account,
                                                    isActive = account.userId == me.id,
                                                    enabled = !isAccountSecurityLoading,
                                                    onClick = { onSwitchSecurityAccount(account.userId) }
                                                )
                                            }
                                        }
                                        SettingsSecurityRow(
                                            icon = Icons.Outlined.AddCircleOutline,
                                            title = "Добавить аккаунт",
                                            subtitle = "Войти в другой аккаунт без выхода из текущего",
                                            enabled = !isAccountSecurityLoading,
                                            onClick = onAddAccount
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Ключ доступа", compactTitle = true) {
                                        if (isPasskeyLoading && passkeys.isEmpty()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AloNativeAppAccent)
                                                Text("Проверяем ключи доступа…", color = AloNativeAppMuted, fontSize = 13.5.sp)
                                            }
                                        } else {
                                            val configured = passkeys.isNotEmpty()
                                            SettingsSecurityRow(
                                                icon = Icons.Rounded.Key,
                                                title = if (configured) "Ключ доступа настроен" else "Ключ доступа не настроен",
                                                subtitle = when {
                                                    !configured -> "Используйте отпечаток, лицо или код устройства вместо пароля"
                                                    passkeys.size == 1 -> "1 ключ защищает этот аккаунт"
                                                    else -> "${passkeys.size} ключа защищают этот аккаунт"
                                                },
                                                showsChevron = false,
                                                trailing = if (configured) "Готово" else null
                                            )
                                            SettingsTextButtonRow(
                                                title = if (configured) "Добавить ещё один ключ" else "Настроить ключ доступа",
                                                enabled = !isPasskeyLoading,
                                                onClick = onRegisterPasskey
                                            )
                                        }
                                    }
                                }
                                item {
                                    SettingsSection(title = "Вход с другого устройства", compactTitle = true) {
                                        SettingsSecurityRow(
                                            icon = Icons.Rounded.QrCodeScanner,
                                            title = "Подключить устройство",
                                            subtitle = "Наведите камеру на QR-код на странице входа Alo на компьютере",
                                            onClick = onOpenQrLoginScanner
                                        )
                                        if (deviceLinkService != null) {
                                            SettingsSecurityRow(
                                                icon = Icons.Rounded.QrCode2,
                                                title = "Показать QR для входа",
                                                subtitle = "Новое устройство отсканирует его, а вы подтвердите вход здесь",
                                                onClick = { isDeviceLinkOpen = true }
                                            )
                                        }
                                    }
                                }
                                item {
                                    SettingsSection(title = "Ваши данные", compactTitle = true) {
                                        SettingsSecurityRow(
                                            icon = Icons.Outlined.FileDownload,
                                            title = "Экспортировать данные",
                                            subtitle = "Скачать JSON профиля и контента",
                                            onClick = { page = AloSettingsPage.ExportData.name }
                                        )
                                    }
                                }
                                item {
                                    SettingsSection(title = "Опасная зона", compactTitle = true) {
                                        SettingsSecurityRow(
                                            icon = Icons.Outlined.Delete,
                                            title = "Удалить аккаунт",
                                            subtitle = "Необратимо удалить профиль и данные после подтверждения",
                                            tint = AloNativeAppDanger,
                                            onClick = { page = AloSettingsPage.DeleteAccount.name }
                                        )
                                    }
                                }

                                if (securityNotice.isNotBlank()) {
                                    item {
                                        SettingsInfoRow(title = "Безопасность", value = securityNotice)
                                    }
                                }

                                item {
                                    SettingsSection(title = "Активные устройства") {
                                        when {
                                            isSecurityLoading && securitySessions.isEmpty() -> {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AloNativeAppAccent)
                                                    Text("Загружаем устройства…", color = AloNativeAppMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            securitySessions.isEmpty() -> {
                                                Text(
                                                    text = "Активных сеансов не найдено.",
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                                    color = AloNativeAppMuted,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            else -> securitySessions.forEachIndexed { index, session ->
                                                SecuritySessionRow(
                                                    session = session,
                                                    isLoading = isSecurityLoading,
                                                    onRevoke = { pendingSessionRevocation = it }
                                                )
                                                if (index < securitySessions.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 14.dp),
                                                        thickness = 0.5.dp,
                                                        color = AloNativeAppSeparator
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (securitySessions.any { !it.current && !it.canRevoke }) {
                                    item {
                                        SettingsInfoRow(
                                            title = "Защита нового устройства",
                                            value = "Сеансы, созданные до этого входа, нельзя завершить в течение первых 24 часов."
                                        )
                                    }
                                }

                                if (securitySessions.any { !it.current }) {
                                    item {
                                        val waits = securitySessions.any { !it.current && !it.canRevoke }
                                        SettingsSection(title = "") {
                                            SettingsNavRow(
                                                icon = Icons.Rounded.PhonelinkErase,
                                                iconTint = Color(0xFFFF3B30),
                                                title = "Завершить остальные сеансы",
                                                subtitle = if (waits) "Станет доступно после периода защиты нового входа" else "Оставить активным только это устройство",
                                                enabled = !isSecurityLoading && !waits,
                                                onClick = { revokeOtherSessionsConfirmationPresented = true }
                                            )
                                        }
                                    }
                                }

                                if (securityEvents.isNotEmpty()) {
                                    item {
                                        SettingsSection(title = "Недавняя активность") {
                                            securityEvents.take(6).forEachIndexed { index, event ->
                                                SecurityEventRow(event = event)
                                                if (index < minOf(securityEvents.size, 6) - 1) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 14.dp),
                                                        thickness = 0.5.dp,
                                                        color = AloNativeAppSeparator
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    SettingsSection(title = "") {
                                        SettingsNavRow(
                                            icon = Icons.AutoMirrored.Rounded.Logout,
                                            iconTint = AloNativeAppDanger,
                                            title = "Выйти из аккаунта",
                                            subtitle = "Завершить текущий сеанс на этом устройстве",
                                            onClick = {
                                                dismissWithAnimation()
                                                onLogout()
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        AloSettingsPage.Accounts -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingMedium)
                            ) {
                                item {
                                    SettingsSection(title = "Сохранённые сеансы") {
                                        when {
                                            isAccountSecurityLoading && savedAccounts.isEmpty() -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 18.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp,
                                                        color = AloNativeAppAccent
                                                    )
                                                    Text(
                                                        text = "Загружаем аккаунты…",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = AloNativeAppMuted
                                                    )
                                                }
                                            }
                                            savedAccounts.isEmpty() -> {
                                                Text(
                                                    text = "На этом устройстве пока нет других сохранённых сеансов.",
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AloNativeAppMuted
                                                )
                                            }
                                            else -> savedAccounts.forEachIndexed { index, account ->
                                                SavedAccountRow(
                                                    account = account,
                                                    isActive = account.userId == me.id,
                                                    enabled = !isAccountSecurityLoading,
                                                    onClick = { onSwitchSecurityAccount(account.userId) }
                                                )
                                                if (index < savedAccounts.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(start = 62.dp),
                                                        color = AloNativeAppSeparator
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text(
                                        text = "Сеансы шифруются ключом Android Keystore. Пароли на устройстве не сохраняются.",
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AloNativeAppMuted
                                    )
                                }
                                item {
                                    GhostTextButton(
                                        text = if (isAccountSecurityLoading) "Обновляется…" else "Обновить аккаунты",
                                        enabled = !isAccountSecurityLoading,
                                        onClick = onLoadSecurityAccounts
                                    )
                                }
                            }
                        }

                        AloSettingsPage.Passkeys -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingMedium)
                            ) {
                                item {
                                    SettingsSection(title = "Ключи доступа") {
                                        when {
                                            isPasskeyLoading && passkeys.isEmpty() -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 18.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp,
                                                        color = AloNativeAppAccent
                                                    )
                                                    Text(
                                                        text = "Проверяем ключи доступа…",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = AloNativeAppMuted
                                                    )
                                                }
                                            }
                                            passkeys.isEmpty() -> {
                                                Text(
                                                    text = "Ключи доступа не добавлены.",
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AloNativeAppMuted
                                                )
                                            }
                                            else -> passkeys.forEachIndexed { index, passkey ->
                                                PasskeyRow(passkey = passkey)
                                                if (index < passkeys.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(start = 14.dp),
                                                        color = AloNativeAppSeparator
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (securityNotice.isNotBlank()) {
                                    item {
                                        SettingsInfoRow(title = "Ключи доступа", value = securityNotice)
                                    }
                                }
                                item {
                                    GhostTextButton(
                                        text = if (isPasskeyLoading) "Открываем Credential Manager…" else "Добавить ключ доступа",
                                        enabled = !isPasskeyLoading,
                                        onClick = onRegisterPasskey
                                    )
                                }
                                item {
                                    GhostTextButton(
                                        text = if (isPasskeyLoading) "Обновляется…" else "Обновить список",
                                        enabled = !isPasskeyLoading,
                                        onClick = onLoadPasskeys
                                    )
                                }
                                item {
                                    Text(
                                        text = "Если Credential Manager или подходящий ключ недоступны, приложение сообщит об этом — вход не имитируется.",
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AloNativeAppMuted
                                    )
                                }
                            }
                        }

                        AloSettingsPage.ExportData -> {
                            val exportReady = exportPassword.isNotBlank() && !isAccountSecurityLoading
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingMedium)
                            ) {
                                item {
                                    SettingsSection(title = "Подтверждение") {
                                        Text(
                                            text = "После проверки текущего пароля сервер вернёт JSON-выгрузку. Затем Android предложит выбрать место сохранения.",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted
                                        )
                                        SecurityPasswordField(
                                            value = exportPassword,
                                            label = "Текущий пароль",
                                            onValueChange = { exportPassword = it }
                                        )
                                        HorizontalDivider(color = AloNativeAppSeparator)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp)
                                                .clickable(
                                                    enabled = exportReady,
                                                    onClick = { onExportAccountData(exportPassword) }
                                                )
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isAccountSecurityLoading) "Получаем экспорт…" else "Сохранить экспорт",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (exportReady) AloNativeAppAccent else AloNativeAppMuted
                                            )
                                        }
                                    }
                                }
                                if (securityNotice.isNotBlank()) {
                                    item {
                                        SettingsInfoRow(title = "Экспорт данных", value = securityNotice)
                                    }
                                }
                            }
                        }

                        AloSettingsPage.DeleteAccount -> {
                            val deleteReady = deleteAccountPassword.isNotBlank() &&
                                deleteAccountConfirmation == "DELETE" &&
                                !isAccountSecurityLoading
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingMedium)
                            ) {
                                item {
                                    SettingsSection(title = "Необратимое действие") {
                                        Text(
                                            text = "Удаление деактивирует аккаунт и завершит текущую сессию. Введите пароль и слово DELETE, чтобы продолжить.",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted
                                        )
                                        SecurityPasswordField(
                                            value = deleteAccountPassword,
                                            label = "Текущий пароль",
                                            onValueChange = { deleteAccountPassword = it }
                                        )
                                        HorizontalDivider(color = AloNativeAppSeparator)
                                        TextField(
                                            value = deleteAccountConfirmation,
                                            onValueChange = { deleteAccountConfirmation = it.take(6).uppercase() },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("Введите DELETE") },
                                            singleLine = true,
                                            colors = settingsTextFieldColors()
                                        )
                                        HorizontalDivider(color = AloNativeAppSeparator)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp)
                                                .clickable(
                                                    enabled = deleteReady,
                                                    onClick = { deleteAccountConfirmationPresented = true }
                                                )
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isAccountSecurityLoading) "Удаляем…" else "Удалить аккаунт",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (deleteReady) AloNativeAppDanger else AloNativeAppMuted
                                            )
                                        }
                                    }
                                }
                                if (securityNotice.isNotBlank()) {
                                    item {
                                        SettingsInfoRow(title = "Удаление аккаунта", value = securityNotice)
                                    }
                                }
                            }
                        }

                        AloSettingsPage.PasswordChange -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(AloNativeLayout.spacingMedium)
                            ) {
                                item {
                                    SettingsSection(title = "Новый пароль") {
                                        SecurityPasswordFields(
                                            currentPassword = currentPassword,
                                            newPassword = newPassword,
                                            confirmPassword = confirmPassword,
                                            isLoading = isSecurityLoading,
                                            onCurrentPasswordChange = { currentPassword = it },
                                            onNewPasswordChange = { newPassword = it },
                                            onConfirmPasswordChange = { confirmPassword = it },
                                            onSubmit = { onChangePassword(currentPassword, newPassword) }
                                        )
                                    }
                                }

                                if (securityNotice.isNotBlank()) {
                                    item {
                                        SettingsInfoRow(
                                            title = "Безопасность",
                                            value = securityNotice
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** iOS's businessCard: 16pt insets, 13pt between rows, 20pt corners. */
@Composable
private fun BusinessCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(20.dp)).background(AloNativeAppSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        content = content
    )
}

@Composable
private fun BusinessHeader(title: String) {
    Text(title, color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun BusinessDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
}

@Composable
private fun BusinessTextInput(value: String, placeholder: String, minLines: Int = 1, maxLines: Int = 1, onChange: (String) -> Unit) {
    Box {
        if (value.isEmpty()) Text(placeholder, color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 16.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp),
            cursorBrush = SolidColor(AloNativeAppAccent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }
        )
    }
}

/** A grey label over a 17pt field, as iOS's businessField. */
@Composable
private fun BusinessField(title: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = AloNativeAppMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Box {
            if (value.isEmpty()) Text(placeholder, color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 17.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp),
                cursorBrush = SolidColor(AloNativeAppAccent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
            )
        }
    }
}

@Composable
private fun BusinessTrashButton(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Delete, "Удалить", tint = AloNativeAppDanger, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BusinessLinkButton(icon: ImageVector, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val color = if (enabled) AloNativeAppAccent else AloNativeAppAccent.copy(alpha = 0.45f)
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val businessLabelColors = listOf(
    "blue" to Color(0xFF0A84FF),
    "green" to Color(0xFF30D158),
    "orange" to Color(0xFFFF9F0A),
    "purple" to Color(0xFFBF5AF2),
    "pink" to Color(0xFFFF375F),
    "gray" to Color(0xFF8E8E93)
)

private fun businessLabelColorValue(name: String): Color =
    businessLabelColors.firstOrNull { it.first == name }?.second ?: businessLabelColors.first().second

@Composable
private fun SecurityPasswordFields(
    currentPassword: String,
    newPassword: String,
    confirmPassword: String,
    isLoading: Boolean,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val passwordsMatch = confirmPassword.isEmpty() || confirmPassword == newPassword
    val passwordReady = currentPassword.isNotEmpty() &&
        newPassword.length >= 8 &&
        passwordsMatch &&
        !isLoading

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "После смены пароля другие устройства будут отключены.",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AloNativeAppMuted
        )
        SecurityPasswordField(
            value = currentPassword,
            label = "Текущий пароль",
            onValueChange = onCurrentPasswordChange
        )
        HorizontalDivider(color = AloNativeAppSeparator)
        SecurityPasswordField(
            value = newPassword,
            label = "Новый пароль",
            onValueChange = onNewPasswordChange
        )
        HorizontalDivider(color = AloNativeAppSeparator)
        SecurityPasswordField(
            value = confirmPassword,
            label = "Повторите новый пароль",
            onValueChange = onConfirmPasswordChange,
            isError = !passwordsMatch
        )
        if (!passwordsMatch) {
            Text(
                text = "Пароли не совпадают",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = AloNativeAppDanger
            )
        }
        HorizontalDivider(color = AloNativeAppSeparator)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clickable(enabled = passwordReady, onClick = onSubmit)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isLoading) "Сохраняем…" else "Изменить пароль",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (passwordReady) AloNativeAppAccent else AloNativeAppMuted
            )
        }
    }
}

@Composable
private fun SecurityPasswordField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        colors = TextFieldDefaults.colors(
            focusedTextColor = AloNativeAppText,
            unfocusedTextColor = AloNativeAppText,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = AloNativeAppAccent,
            unfocusedLabelColor = AloNativeAppMuted,
            errorLabelColor = AloNativeAppDanger,
            cursorColor = AloNativeAppAccent
        )
    )
}

@Composable
private fun SavedAccountRow(
    account: AloStoredAccount,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && !isActive, onClick = onClick)
                .heightIn(min = 58.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBadge(
                name = account.displayName,
                avatarUrl = account.avatarUrl,
                verified = false,
                size = 38.dp,
                colorSeed = account.userId.toString()
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.displayName, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    account.username.takeIf(String::isNotBlank)?.let { "@$it" } ?: "Сохранённый сеанс",
                    color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Text("Сейчас", color = AloNativeAppAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            } else {
                SettingsChevron(17)
            }
        }
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
    }
}

@Composable
private fun PasskeyRow(passkey: AloPasskey) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = passkey.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AloNativeAppText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val detail = passkey.lastUsedAt.takeIf(String::isNotBlank)
            ?.let { "Последнее использование: ${formatMessageDateLabel(it)}" }
            ?: passkey.createdAt.takeIf(String::isNotBlank)
                ?.let { "Добавлен: ${formatMessageDateLabel(it)}" }
            ?: "Ключ доступа"
        Text(
            text = detail,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AloNativeAppMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BlockedUserRow(
    userId: Int,
    peer: AloConversationPeer?,
    isUpdating: Boolean,
    onUnblock: () -> Unit
) {
    val displayName = peer?.name?.ifBlank { null } ?: "Пользователь #$userId"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AvatarBadge(
                name = displayName,
                avatarUrl = peer?.avatarUrl.orEmpty(),
                verified = peer?.verified == true,
                size = 36.dp,
                colorSeed = peer?.username?.ifBlank { displayName } ?: displayName
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeAppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val username = peer?.username?.takeIf { it.isNotBlank() }
                if (username != null) {
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = if (isUpdating) "…" else "Разблокировать",
                modifier = Modifier
                    .clip(AloSquircle(10.dp))
                    .clickable(enabled = !isUpdating, onClick = onUnblock)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isUpdating) AloNativeAppMuted else AloNativeAppAccent
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp),
            color = AloNativeAppSeparator
        )
    }
}

@Composable
private fun SecuritySessionRow(
    session: AloSecuritySession,
    isLoading: Boolean,
    onRevoke: (AloSecuritySession) -> Unit
) {
    val statusParts = buildList {
        if (session.lastSeenAt.isNotBlank()) {
            add("${formatMessageDateLabel(session.lastSeenAt)}, ${formatMessageTime(session.lastSeenAt)}")
        }
        if (session.ip.isNotBlank()) add(session.ip)
        if (session.suspicious) add("требует внимания")
        if (!session.current && !session.canRevoke) {
            add(
                if (session.revokeAvailableAt.isBlank()) {
                    "защищён новым входом"
                } else {
                    "можно завершить ${formatMessageDateLabel(session.revokeAvailableAt)}, ${formatMessageTime(session.revokeAvailableAt)}"
                }
            )
        }
    }.joinToString(" · ").ifBlank { session.platform }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = session.deviceLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (session.suspicious) Color(0xFFFF5C68) else AloNativeAppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (session.current) {
                    Text(
                        text = "это устройство",
                        style = MaterialTheme.typography.labelSmall,
                        color = AloNativeAppAccent
                    )
                }
            }
            Text(
                text = statusParts.ifBlank { "Активный сеанс" },
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (session.suspicious) Color(0xFFFF7B86) else AloNativeAppMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!session.current && session.canRevoke) {
            GhostTextButton(
                text = "Завершить",
                enabled = !isLoading,
                onClick = {
                    if (!isLoading) onRevoke(session)
                }
            )
        } else if (!session.current) {
            Text(
                text = "Защищён",
                style = MaterialTheme.typography.labelMedium,
                color = AloNativeAppMuted
            )
        }
    }
}

@Composable
private fun SecurityEventRow(event: AloSecurityEvent) {
    val title = when (event.eventType) {
        "login_verified" -> "Вход с нового устройства"
        "password_changed" -> "Пароль изменён"
        "session_revoked" -> "Сеанс завершён"
        "sessions_revoked_others" -> "Завершены другие сеансы"
        "session_revoked_current" -> "Выполнен выход с устройства"
        "login_failed" -> "Неверный пароль при входе"
        "login_attack_alert" -> "Попытка подобрать пароль"
        "password_reset" -> "Пароль восстановлен по почте"
        "login_requires_verification" -> "Запрошен код входа"
        "session_invalidated" -> "Сеанс завершён: сменилось устройство"
        else -> "Событие безопасности"
    }
    val subtitle = listOf(
        formatMessageDateLabel(event.createdAt),
        formatMessageTime(event.createdAt),
        event.ip
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(14.dp),
        color = AloNativeAppBackgroundSecondary,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (event.riskLevel == "high") Color(0xFFFF7B86) else AloNativeAppText
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
