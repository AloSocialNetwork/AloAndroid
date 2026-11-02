package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val AloSwipePin = Color(0xFF338CF2)
private val AloSwipeMute = Color(0xFFF5991A)
private val AloSwipeDelete = Color(0xFFFF453A)
private val AloSwipeArchive = Color(0xFF6B7385)

/** iOS's square.and.pencil: a square open at the corner with a pencil over it. */
private val AloComposeIcon: ImageVector by lazy {
    ImageVector.Builder("AloCompose", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(11f, 4f); horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, false, false, 4f, 6f); verticalLineTo(18f)
            arcTo(2f, 2f, 0f, false, false, 6f, 20f); horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, false, false, 20f, 18f); verticalLineTo(13f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(18.5f, 2.5f)
            arcTo(2.12f, 2.12f, 0f, false, true, 21.5f, 5.5f)
            lineTo(12f, 15f); lineTo(8f, 16f); lineTo(9f, 12f); close()
        }
    }.build()
}

private fun chatPreviewText(body: String): String = AloChatSystemEventCodec.previewText(body) ?: body

/**
 * iOS's chat list (MessagesView inbox): «Чаты» with «Изменить», calls and a new chat; a search
 * field above the list, the archive entry, pinned chats first, unread counters, Telegram swipe
 * actions and a selection mode whose toolbar takes the tab bar's place.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AloChatInbox(
    state: AloNativeAppUiState,
    data: AloBootstrapData,
    onAction: (AloNativeAppAction) -> Unit,
    onCreateCommunity: () -> Unit
) {
    val model = LocalAloChatListModel.current
    val owner = data.me.id
    // Device prefs change outside the app state; their revision counters re-read them.
    val prefsState = remember(state.chatPrefsRevision, owner, model) { model?.state(owner) ?: AloChatListPrefs.State() }
    var showsArchive by rememberSaveable { mutableStateOf(false) }
    var showsCalls by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var openedSwipeId by remember { mutableStateOf<String?>(null) }
    var pendingDeletion by remember { mutableStateOf<List<AloConversationSummary>?>(null) }
    var previewConversation by remember { mutableStateOf<AloConversationSummary?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val isSelecting = state.isSelectingChats
    val all = data.messagesConversations
    // The archive is its own page over the list (AloSwipeBackPage), so the list underneath always holds the ordinary chats.
    val arranged = remember(all, prefsState) { arrangeConversations(all, prefsState, archived = false) }
    val archivedChats = remember(all, prefsState) { arrangeConversations(all, prefsState, archived = true) }
    val archivedCount = archivedChats.size
    val unreadRevision = state.unreadRevision
    fun isUnread(c: AloConversationSummary) = unreadRevision >= 0 && model?.isUnread(owner, c) == true
    fun unreadCount(c: AloConversationSummary) = if (unreadRevision >= 0) model?.unreadCount(owner, c) ?: 0 else 0
    val trimmedQuery = query.trim().lowercase(Locale.getDefault())
    val shown = if (trimmedQuery.isBlank()) arranged else all.filter {
        it.peer.name.lowercase(Locale.getDefault()).contains(trimmedQuery) ||
            it.peer.username.lowercase(Locale.getDefault()).contains(trimmedQuery) ||
            chatPreviewText(it.lastBody).lowercase(Locale.getDefault()).contains(trimmedQuery)
    }
    // Secret chats sit among the ordinary ones by last activity (not in the archive, not while chats are selected).
    val secretRows = if (!state.secretChatsAvailable) {
        emptyList()
    } else {
        state.secretChats.filter { AloSecretChatList.matches(it, trimmedQuery) }
    }
    val entries = remember(shown, secretRows, prefsState) {
        AloSecretChatList.merge(shown, prefsState.pinned.toSet(), secretRows)
    }
    val archivedEntries = remember(archivedChats, prefsState) {
        AloSecretChatList.merge(archivedChats, prefsState.pinned.toSet(), emptyList())
    }

    LaunchedEffect(all.map { it.lastMessageId }) { onAction(AloNativeAppAction.EstablishUnreadBaseline) }
    fun endSelection() {
        selectedKeys = emptySet()
        onAction(AloNativeAppAction.SetChatSelection(false))
    }
    BackHandler(enabled = isSelecting) { endSelection() }

    // The search field sits above the first row, scrolled out of view until pulled down (as on iOS).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 1)

    @Composable
    fun InboxEntry(entry: AloInboxEntry) {
                val conversation = entry.summary
                val secret = entry.secret
                if (secret != null) {
                    AloChatListRow(
                        conversation = conversation,
                        isUnread = secret.unreadCount > 0 || secret.isIncomingRequest,
                        unreadCount = conversation.unreadCount,
                        isPinned = false,
                        isSelecting = false,
                        isSelected = false,
                        onOpen = {
                            if (openedSwipeId != null) openedSwipeId = null
                            else onAction(AloNativeAppAction.OpenSecretChat(secret.id))
                        },
                        onLongPress = {}
                    )
                    return
                }
                val key = AloChatListPrefs.key(conversation)
                val pinned = key in prefsState.pinned
                val archived = key in prefsState.archived
                AloSwipeActionRow(
                    id = key,
                    openedId = openedSwipeId,
                    onOpenedIdChange = { openedSwipeId = it },
                    enabled = !isSelecting,
                    leading = listOf(
                        AloSwipeAction(
                            if (pinned) "Открепить" else "Закрепить",
                            Icons.Rounded.PushPin, AloSwipePin
                        ) { onAction(AloNativeAppAction.SetChatsPinned(listOf(key), !pinned)) }
                    ),
                    trailing = listOf(
                        AloSwipeAction("Звук", Icons.Rounded.NotificationsOff, AloSwipeMute) {
                            onAction(AloNativeAppAction.ToggleChatMuted(key))
                        },
                        AloSwipeAction("Удалить", Icons.Rounded.Delete, AloSwipeDelete) { pendingDeletion = listOf(conversation) },
                        AloSwipeAction(
                            if (archived) "Вернуть" else "Архив",
                            if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, AloSwipeArchive
                        ) { onAction(AloNativeAppAction.SetChatsArchived(listOf(key), !archived)) }
                    )
                ) {
                    AloChatListRow(
                        conversation = conversation,
                        isUnread = isUnread(conversation),
                        unreadCount = unreadCount(conversation),
                        isPinned = pinned,
                        isSelecting = isSelecting,
                        isSelected = key in selectedKeys,
                        onOpen = {
                            when {
                                openedSwipeId != null -> openedSwipeId = null
                                isSelecting -> selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                                else -> onAction(AloNativeAppAction.SelectConversation(conversation.peerId))
                            }
                        },
                        onLongPress = { if (!isSelecting) previewConversation = conversation }
                    )
                }
    }

    Box(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
        Column(Modifier.fillMaxSize()) {
            AloChatInboxHeader(
                title = when {
                    isSelecting && selectedKeys.isEmpty() -> "Выберите чаты"
                    isSelecting -> "Выбрано: ${selectedKeys.size}"
                    else -> "Чаты"
                },
                showsBack = false,
                isSelecting = isSelecting,
                canEdit = arranged.isNotEmpty(),
                onBack = {},
                onEdit = {
                    openedSwipeId = null
                    selectedKeys = emptySet()
                    onAction(AloNativeAppAction.SetChatSelection(true))
                },
                onDone = ::endSelection,
                onCalls = {
                    showsCalls = true
                    onAction(AloNativeAppAction.LoadCallHistory())
                },
                onCompose = onCreateCommunity
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(bottom = AloBottomBarOverlayPadding + 12.dp)
            ) {
                item(key = "search") { AloChatSearchField(query) { query = it } }
                if (trimmedQuery.isBlank() && !isSelecting && archivedCount > 0) {
                    item(key = "archive") {
                        AloArchiveEntryRow(count = archivedCount) { showsArchive = true }
                    }
                }
                items(entries, key = { "chat-" + it.key }) { entry -> InboxEntry(entry) }
                if (trimmedQuery.isNotBlank() && shown.isEmpty()) {
                    item(key = "search-empty") {
                        Text(
                            "Ничего не найдено", color = AloNativeAppMuted, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else if (all.isEmpty() && secretRows.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            EmptyPanelBody(title = "Диалогов пока нет", message = "Когда появятся сообщения, они будут здесь.")
                        }
                    }
                }
            }
        }

        // The archive, pushed over the chats as a page of its own: it follows the back swipe with the chats underneath.
        AloSwipeBackPage(
            visible = showsArchive,
            onClosed = { showsArchive = false },
            backEnabled = !isSelecting && !showsCalls
        ) { page ->
            Column(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
                AloChatInboxHeader(
                    title = when {
                        isSelecting && selectedKeys.isEmpty() -> "Выберите чаты"
                        isSelecting -> "Выбрано: ${selectedKeys.size}"
                        else -> "Архив"
                    },
                    showsBack = !isSelecting,
                    isSelecting = isSelecting,
                    canEdit = archivedChats.isNotEmpty(),
                    onBack = page::close,
                    onEdit = {
                        openedSwipeId = null
                        selectedKeys = emptySet()
                        onAction(AloNativeAppAction.SetChatSelection(true))
                    },
                    onDone = ::endSelection,
                    onCalls = {
                        showsCalls = true
                        onAction(AloNativeAppAction.LoadCallHistory())
                    },
                    onCompose = onCreateCommunity
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = AloBottomBarOverlayPadding + 12.dp)
                ) {
                    items(archivedEntries, key = { "archived-" + it.key }) { entry -> InboxEntry(entry) }
                    if (archivedEntries.isEmpty()) {
                        item(key = "archive-empty") {
                            Text(
                                "В архиве пусто", color = AloNativeAppMuted, fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isSelecting,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            val chats = all.filter { AloChatListPrefs.key(it) in selectedKeys }
            val allPinned = chats.isNotEmpty() && chats.all { AloChatListPrefs.key(it) in prefsState.pinned }
            val allArchived = chats.isNotEmpty() && chats.all { AloChatListPrefs.key(it) in prefsState.archived }
            val hasUnread = chats.any(::isUnread)
            AloChatSelectionToolbar(
                hasUnread = hasUnread,
                allPinned = allPinned,
                allArchived = allArchived,
                enabled = chats.isNotEmpty(),
                onRead = { onAction(AloNativeAppAction.MarkChatsRead(selectedKeys.toList())); endSelection() },
                onPin = { onAction(AloNativeAppAction.SetChatsPinned(selectedKeys.toList(), !allPinned)); endSelection() },
                onArchive = { onAction(AloNativeAppAction.SetChatsArchived(selectedKeys.toList(), !allArchived)); endSelection() },
                onDelete = { pendingDeletion = chats }
            )
        }

        AnimatedVisibility(
            visible = previewConversation != null,
            enter = fadeIn() + scaleIn(initialScale = 0.985f),
            exit = fadeOut() + scaleOut(targetScale = 0.985f)
        ) {
            previewConversation?.let { conversation ->
                ConversationPreviewOverlay(
                    conversation = conversation,
                    onOpen = {
                        previewConversation = null
                        onAction(AloNativeAppAction.SelectConversation(conversation.peerId))
                    },
                    onDismiss = { previewConversation = null }
                )
            }
        }

        AloSwipeBackPage(visible = showsCalls, onClosed = { showsCalls = false }) { page ->
            AloCallHistoryPage(
                state = state,
                onBack = page::close,
                onAction = onAction
            )
        }
    }

    pendingDeletion?.let { chats ->
        val single = chats.singleOrNull()
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            containerColor = AloNativeAppBackgroundSecondary,
            title = {
                Text(
                    when {
                        single != null && single.kind == "group" -> "Покинуть «${single.peer.name}»?"
                        single != null -> "Удалить чат «${single.peer.name}»?"
                        else -> "Удалить чаты: ${chats.size}?"
                    },
                    color = AloNativeAppText
                )
            },
            text = {
                Text(
                    if (chats.all { it.kind == "group" }) "Вы выйдете из группы и она пропадёт из списка."
                    else "История удалится только у вас. У собеседника переписка останется.",
                    color = AloNativeAppMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(AloNativeAppAction.DeleteChats(chats.map(AloChatListPrefs::key)))
                    pendingDeletion = null
                    endSelection()
                }) {
                    Text(if (single?.kind == "group") "Покинуть" else "Удалить у меня", color = AloSwipeDelete, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Отмена", color = AloNativeAppAccent) } }
        )
    }
}

@Composable
private fun AloChatInboxHeader(
    title: String,
    showsBack: Boolean,
    isSelecting: Boolean,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onCalls: () -> Unit,
    onCompose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AloScreenChrome.HeaderHeight)
            .padding(start = if (showsBack) AloScreenChrome.BackStart else AloScreenChrome.SideInset, end = AloScreenChrome.ActionBoxEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showsBack) {
            AloGlassCircleButton(onClick = onBack, description = "Назад к чатам", diameter = AloScreenChrome.BackDiameter)
        }
        Text(
            title, color = AloNativeAppText, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (isSelecting) {
            Text(
                "Готово", color = AloNativeAppAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(AloSquircle(10.dp)).clickable(onClick = onDone).padding(horizontal = 4.dp, vertical = 11.dp)
            )
        } else {
            if (canEdit) {
                Text(
                    "Изменить", color = AloNativeAppAccent, fontSize = 17.sp,
                    modifier = Modifier.clip(AloSquircle(10.dp)).clickable(onClick = onEdit)
                        .padding(start = 4.dp, end = 10.dp, top = 11.dp, bottom = 11.dp)
                )
            }
            AloInboxGlassButton(Icons.Outlined.Phone, "Звонки", onCalls)
            AloInboxGlassButton(AloComposeIcon, "Новое сообщение", onCompose)
        }
    }
}

@Composable
private fun AloInboxGlassButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(38.dp)
                .aloLiquidGlass(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = AloNativeAppAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AloChatSearchField(value: String, onChange: (String) -> Unit) {
    // The same capsule as the search tab's field (AloSocialSearchField): 44dp, a 16dp inset, the glass look.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AloScreenChrome.SideInset, end = AloScreenChrome.SideInset, top = AloScreenChrome.FieldGap, bottom = 8.dp)
            .height(AloScreenChrome.FieldHeight)
            .aloLiquidGlass(RoundedCornerShape(percent = 50))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск", color = AloNativeAppMuted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp),
                cursorBrush = SolidColor(AloNativeAppAccent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AloArchiveEntryRow(count: Int, onOpen: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .heightIn(min = 76.dp)
                .padding(start = 11.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(AloSwipeArchive), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Archive, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column {
                Text("Архив", color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Чатов: $count", color = AloNativeAppMuted, fontSize = 15.sp)
            }
        }
    }
}

/**
 * A chat in the list, at Telegram Android's sizes (DialogCell, the two-line-preview layout): a 76dp
 * row, a 56dp avatar 11dp from the edge, text from 78dp, a 16sp medium name 10dp from the top, a 12sp
 * time, a 15sp preview of up to two lines from 32dp, and the 20.67dp counter level with it at 42dp.
 * The divider starts at 72dp.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AloChatListRow(
    conversation: AloConversationSummary,
    isUnread: Boolean,
    unreadCount: Int,
    isPinned: Boolean,
    isSelecting: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val status: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (conversation.unreadMentionCount > 0) {
                Box(Modifier.size(20.67.dp).clip(CircleShape).background(AloNativeAppAccent), contentAlignment = Alignment.Center) {
                    Text("@", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            when {
                unreadCount > 0 -> Box(
                    Modifier
                        .heightIn(min = 20.67.dp)
                        .widthIn(min = 20.67.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(AloNativeAppAccent)
                        .padding(horizontal = 6.33.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (unreadCount > 999) "999+" else "$unreadCount", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                isUnread -> Box(Modifier.size(10.dp).clip(CircleShape).background(AloNativeAppAccent))
                isPinned -> Icon(
                    Icons.Rounded.PushPin, "Закреплён", tint = AloNativeAppMuted,
                    modifier = Modifier.size(16.dp).rotate(45f)
                )
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    isSelected -> AloNativeAppAccent.copy(alpha = 0.12f)
                    isPinned -> Color.White.copy(alpha = 0.035f)
                    else -> Color.Transparent
                }
            )
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 11.dp, end = 14.dp, top = 10.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                AnimatedVisibility(visible = isSelecting, enter = fadeIn() + slideInHorizontally { -it }, exit = fadeOut() + slideOutHorizontally { -it }) {
                    Box(
                        Modifier
                            .padding(top = 16.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .then(
                                if (isSelected) Modifier.background(AloNativeAppAccent)
                                else Modifier.border(1.5.dp, AloNativeAppMuted.copy(alpha = 0.6f), CircleShape)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                if (conversation.peer.entityType == "saved") {
                    SavedMessagesAvatar(size = 56.dp)
                } else {
                    AvatarBadge(
                        name = conversation.peer.name,
                        avatarUrl = conversation.peer.avatarUrl,
                        verified = false,
                        size = 56.dp,
                        colorSeed = conversation.peer.id.toString()
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val isChannel = conversation.peer.entityType == "channel"
                        if (conversation.kind == "secret") {
                            Icon(Icons.Rounded.Lock, null, tint = AloSecretGreen, modifier = Modifier.size(15.dp))
                        } else if (conversation.kind == "group" || isChannel) {
                            Icon(
                                if (isChannel) Icons.Rounded.Campaign else Icons.Rounded.People, null,
                                tint = AloNativeAppText.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            if (conversation.peer.entityType == "saved") "Избранное" else conversation.peer.name,
                            color = if (conversation.kind == "secret") AloSecretGreen else AloNativeAppText,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(formatMessageTime(conversation.lastAt), color = AloNativeAppMuted, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            chatPreviewText(conversation.lastBody).ifBlank { "Сообщение" },
                            color = if (isUnread) AloNativeAppText.copy(alpha = 0.85f) else AloNativeAppMuted,
                            fontSize = 15.sp, lineHeight = 18.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        // Keeps the preview clear of the counter, drawn at 42dp on the right.
                        Box(Modifier.alpha(0f)) { status() }
                    }
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 14.dp)) { status() }
        }
        Box(
            Modifier
                .padding(start = if (isSelecting) 107.dp else 72.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AloNativeAppSeparator)
        )
    }
}

internal class AloSwipeAction(val title: String, val icon: ImageVector, val color: Color, val perform: () -> Unit)

/**
 * Telegram-style row swipe (iOS's AloSwipeActionRow): drag left for the trailing actions, right for
 * the leading ones; a long drag past the middle runs the primary action. One row open at a time.
 */
@Composable
internal fun AloSwipeActionRow(
    id: String,
    openedId: String?,
    onOpenedIdChange: (String?) -> Unit,
    enabled: Boolean,
    leading: List<AloSwipeAction>,
    trailing: List<AloSwipeAction>,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val actionWidth = with(density) { 80.dp.toPx() }
    val springSpec = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    // The gesture outlives recompositions (opening this row changes openedId); read the latest values.
    val currentOpenedId by rememberUpdatedState(openedId)
    val setOpenedId by rememberUpdatedState(onOpenedIdChange)
    val currentLeading by rememberUpdatedState(leading)
    val currentTrailing by rememberUpdatedState(trailing)
    fun close() {
        scope.launch { offset.animateTo(0f, springSpec) }
        if (currentOpenedId == id) setOpenedId(null)
    }
    LaunchedEffect(openedId) { if (openedId != id && offset.value != 0f) offset.animateTo(0f, springSpec) }
    LaunchedEffect(enabled) { if (!enabled) offset.snapTo(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .clipToBounds()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val tracker = VelocityTracker()
                var dragOffset = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        dragOffset = offset.value
                        setOpenedId(id)
                    },
                    onDragEnd = {
                        val leading = currentLeading
                        val trailing = currentTrailing
                        val onOpenedIdChange = setOpenedId
                        val velocity = tracker.calculateVelocity().x
                        val current = dragOffset
                        val predicted = current + velocity * 0.18f
                        val openTrailing = actionWidth * trailing.size
                        val openLeading = actionWidth * leading.size
                        scope.launch {
                            when {
                                current < -width * 0.55f && trailing.isNotEmpty() -> {
                                    offset.animateTo(0f, springSpec); onOpenedIdChange(null); trailing.last().perform()
                                }
                                current > width * 0.55f && leading.isNotEmpty() -> {
                                    offset.animateTo(0f, springSpec); onOpenedIdChange(null); leading.first().perform()
                                }
                                trailing.isNotEmpty() && (current < -openTrailing * 0.4f || predicted < -openTrailing * 1.2f) ->
                                    offset.animateTo(-openTrailing, springSpec, initialVelocity = velocity)
                                leading.isNotEmpty() && (current > openLeading * 0.4f || predicted > openLeading * 1.2f) ->
                                    offset.animateTo(openLeading, springSpec, initialVelocity = velocity)
                                else -> { offset.animateTo(0f, springSpec); onOpenedIdChange(null) }
                            }
                        }
                    },
                    onDragCancel = { close() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragOffset = (dragOffset + amount).coerceIn(
                            if (currentTrailing.isEmpty()) 0f else -width,
                            if (currentLeading.isEmpty()) 0f else width
                        )
                        val next = dragOffset
                        scope.launch { offset.snapTo(next) }
                    }
                )
            }
    ) {
        val revealed = abs(offset.value)
        if (revealed > 0.5f) {
            val actions = if (offset.value > 0) leading else trailing
            Row(
                Modifier
                    .matchParentSize()
                    .layoutStripe(with(density) { revealed.toDp() }, alignEnd = offset.value < 0)
            ) {
                actions.forEach { action ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(action.color)
                            .clickable { close(); action.perform() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text(action.title, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(AloNativeAppBackground)
        ) {
            content()
            if (revealed > 0.5f) {
                // While open, a tap closes the row instead of opening the chat.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                )
            }
        }
    }
}

/** The action strip is as wide as the drag, pinned to the side it comes from. */
private fun Modifier.layoutStripe(width: androidx.compose.ui.unit.Dp, alignEnd: Boolean): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val stripe = width.roundToPx().coerceIn(0, constraints.maxWidth)
            val placeable = measurable.measure(constraints.copy(minWidth = stripe, maxWidth = stripe))
            layout(constraints.maxWidth, placeable.height) {
                placeable.place(if (alignEnd) constraints.maxWidth - stripe else 0, 0)
            }
        }
    )

@Composable
private fun AloChatSelectionToolbar(
    hasUnread: Boolean,
    allPinned: Boolean,
    allArchived: Boolean,
    enabled: Boolean,
    onRead: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(60.dp)
            .aloLiquidGlass(shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        @Composable
        fun item(title: String, icon: ImageVector, active: Boolean, tint: Color = AloNativeAppAccent, onClick: () -> Unit) {
            Column(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(shape)
                    .clickable(enabled = active, onClick = onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val color = if (active) tint else AloNativeAppMuted.copy(alpha = 0.6f)
                Icon(icon, null, tint = color, modifier = Modifier.size(23.dp))
                Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
        item("Прочитано", Icons.Outlined.MarkChatRead, hasUnread, onClick = onRead)
        item(if (allPinned) "Открепить" else "Закрепить", Icons.Outlined.PushPin, enabled, onClick = onPin)
        item(if (allArchived) "Вернуть" else "В архив", if (allArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, enabled, onClick = onArchive)
        item("Удалить", Icons.Outlined.Delete, enabled, tint = AloSwipeDelete, onClick = onDelete)
    }
}

/** «Звонки» (iOS's AloCallHistoryView): finished one-to-one calls, missed ones in red, call back on the right. */
@Composable
private fun AloCallHistoryPage(
    state: AloNativeAppUiState,
    onBack: () -> Unit,
    onAction: (AloNativeAppAction) -> Unit
) {
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    // Back is the page's own swipe (AloSwipeBackPage around it), which follows the finger.
    val shown = if (missedOnly) state.callHistory.filter { it.isMissed } else state.callHistory
    Column(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
        Row(
            Modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight).padding(start = AloScreenChrome.BackStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AloGlassCircleButton(onClick = onBack, description = "Назад к чатам", diameter = AloScreenChrome.BackDiameter)
            Text("Звонки", color = AloNativeAppText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        AloSegmentPill(
            titles = listOf("Все", "Пропущенные"),
            selected = if (missedOnly) 1 else 0,
            onSelect = { missedOnly = it == 1 },
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 6.dp)
        )
        when {
            state.isCallHistoryLoading && state.callHistory.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AloNativeAppAccent)
            }
            state.callHistoryError != null && state.callHistory.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.callHistoryError, color = AloNativeAppMuted, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { onAction(AloNativeAppAction.LoadCallHistory()) }) { Text("Повторить", color = AloNativeAppAccent) }
            }
            shown.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(if (missedOnly) Icons.Outlined.CallReceived else Icons.Outlined.Phone, null, tint = AloNativeAppMuted, modifier = Modifier.size(40.dp))
                Text(
                    if (missedOnly) "Пропущенных звонков нет" else "Здесь появятся ваши звонки",
                    color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "Позвонить можно из профиля собеседника или из чата.", color = AloNativeAppMuted, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AloBottomBarOverlayPadding + 12.dp)) {
                items(shown, key = { "call-${it.id}" }) { item ->
                    AloCallHistoryRow(item = item, onAction = onAction)
                }
                if (state.callHistoryHasMore && !missedOnly) {
                    item(key = "more") {
                        LaunchedEffect(state.callHistory.size) { onAction(AloNativeAppAction.LoadCallHistory(more = true)) }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AloNativeAppAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AloCallHistoryRow(item: AloCallHistoryItem, onAction: (AloNativeAppAction) -> Unit) {
    val subtitle = when (item.outcome) {
        "missed" -> "Пропущенный"
        "declined" -> if (item.isOutgoing) "Отклонён" else "Вы отклонили"
        "cancelled" -> "Отменённый"
        else -> {
            val base = if (item.isOutgoing) "Исходящий" else "Входящий"
            if (item.durationSeconds > 0) "$base · ${formatCallDuration(item.durationSeconds)}" else base
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarBadge(
                name = item.peer.name, avatarUrl = item.peer.avatarUrl, verified = false, size = 44.dp,
                colorSeed = item.peer.id.toString(),
                onClick = { onAction(AloNativeAppAction.OpenViewedProfile(AloProfileTarget.User(item.peer.id))) }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.peer.name, color = if (item.isMissed) AloSwipeDelete else AloNativeAppText,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(if (item.isOutgoing) Icons.Outlined.CallMade else Icons.Outlined.CallReceived, null,
                        tint = AloNativeAppMuted, modifier = Modifier.size(13.dp))
                    Text(subtitle, color = AloNativeAppMuted, fontSize = 14.sp, maxLines = 1)
                }
            }
            Text(formatPostTimestamp(item.createdAt), color = AloNativeAppMuted, fontSize = 14.sp)
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onAction(AloNativeAppAction.CallBack(item.peer.id, item.isVideo)) },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Phone,
                    if (item.isVideo) "Перезвонить по видео" else "Перезвонить", tint = AloNativeAppAccent, modifier = Modifier.size(21.dp))
            }
        }
        Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
    }
}

private fun formatCallDuration(seconds: Int): String =
    if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

/** iOS's AloSocialSegments: a glass oval with a sliding selection. */
@Composable
private fun AloSegmentPill(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = AloSquircle(19.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .aloLiquidGlass(shape)
            .padding(3.dp)
    ) {
        val part = maxWidth / titles.size
        val offset by androidx.compose.animation.core.animateDpAsState(part * selected, spring(dampingRatio = 0.86f, stiffness = 520f), label = "segment")
        // An offset, not a start padding: the spring overshoots 0 on the way back and a negative padding throws.
        Box(Modifier.offset(x = offset).width(part).fillMaxHeight().clip(AloSquircle(16.dp)).background(Color.White.copy(alpha = 0.14f)))
        Row(Modifier.fillMaxSize()) {
            titles.forEachIndexed { index, title ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(AloSquircle(16.dp)).clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title, color = if (index == selected) AloNativeAppText else AloNativeAppMuted, fontSize = 14.sp,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
