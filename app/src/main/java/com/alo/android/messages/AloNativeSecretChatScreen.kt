package com.alo.android

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** What the profile and the chat details need to offer «Начать секретный чат». */
internal class AloSecretChatEntry(val available: Boolean, val start: (Int) -> Unit)

internal val LocalAloSecretChatEntry = staticCompositionLocalOf<AloSecretChatEntry?> { null }

/** The name of a secret chat is green, as in Telegram. */
internal val AloSecretGreen = Color(0xFF34C759)

/**
 * A secret chat: its own screen over the chat list. It belongs to this device and the device at the other end, so it
 * is not one of the ordinary chats; the window is secured (no screenshots) while it is open.
 */
@Composable
internal fun AloNativeSecretChatScreen(
    state: AloNativeAppUiState,
    onAction: (AloNativeAppAction) -> Unit
) {
    AloSecureWindowWhile(true)
    BackHandler { onAction(AloNativeAppAction.CloseSecretChat) }
    val chat = state.secretChats.firstOrNull { it.id == state.activeSecretChatId }
    val me = state.bootstrap?.me?.id ?: 0
    val gone = state.isSecretChatGone
    var confirmDelete by remember(state.activeSecretChatId) { mutableStateOf(false) }
    var input by remember(state.activeSecretChatId) { mutableStateOf("") }
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            clock.longValue = System.currentTimeMillis()
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.activeSecretMessages.size) {
        if (state.activeSecretMessages.isNotEmpty()) listState.animateScrollToItem(state.activeSecretMessages.size)
    }
    // One voice message and one circle play at a time, as in an ordinary chat (the ids of their messages).
    var activeVoiceId by remember(state.activeSecretChatId) { mutableIntStateOf(0) }
    var activeCircleId by remember(state.activeSecretChatId) { mutableIntStateOf(0) }
    val playback = SecretPlayback(
        activeVoiceId = activeVoiceId,
        activeCircleId = activeCircleId,
        onVoice = { id -> activeVoiceId = id },
        onCircle = { id -> activeCircleId = id }
    )

    // The wallpaper of ordinary chats (the global chat look): a secret chat differs only by the green name and the lock.
    val context = LocalContext.current
    val chatAppearance = remember(context) {
        AloChatAppearanceStore(context.getSharedPreferences("alo-android", Context.MODE_PRIVATE)).read(null)
    }
    var chatMenu by remember { mutableStateOf(false) }
    // The composer's held mic and the camera overlay of a circle being recorded.
    val circleControl = remember { AloCircleRecordingControl() }
    var secretComposerHeightPx by remember { mutableStateOf(0) }

    CompositionLocalProvider(LocalAloNativeChatAppearance provides chatAppearance) {
    Box(Modifier.fillMaxSize()) {
        ChatWallpaperBackground(Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // The header of an ordinary chat (MessagesChatHeader, after iOS): Back in a glass circle, the person in a
            // glass oval (green, with a lock) and their photo on the right. A secret chat has no details screen, so the
            // oval and the photo open its menu: the self-destruct timer and deleting the chat.
            val headerItem = 42.dp
            val menuEnabled = chat != null && !gone
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AloNativeGlassPanel(shape = CircleShape) {
                    Box(
                        Modifier
                            .size(headerItem)
                            .clip(CircleShape)
                            .clickable { onAction(AloNativeAppAction.CloseSecretChat) },
                        contentAlignment = Alignment.Center
                    ) {
                        AloChevronLeft(21.dp, AloNativeAppText, Modifier.semantics { contentDescription = "К чатам" }, AloChevronWeight.Regular)
                    }
                }
                Box(Modifier.weight(1f)) {
                    AloNativeGlassPanel(shape = RoundedCornerShape(percent = 50)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(headerItem)
                                .clickable(enabled = menuEnabled) { chatMenu = true }
                                .padding(horizontal = 16.dp)
                                .testTag("alo.secret.header"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.Lock, null, tint = AloSecretGreen, modifier = Modifier.size(15.dp))
                                Text(
                                    chat?.peerName ?: "Секретный чат",
                                    color = AloSecretGreen, fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                when {
                                    chat == null || gone -> ""
                                    chat.isIncomingRequest -> "ждёт вашего ответа"
                                    chat.isWaiting -> "ждёт ответа"
                                    chat.ttlSeconds > 0 -> "автоудаление: ${AloSecretChatCodec.timerLabel(chat.ttlSeconds)}"
                                    else -> "секретный чат"
                                },
                                color = AloNativeAppMuted, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 1
                            )
                        }
                    }
                    AloDropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                        if (chat != null && chat.isActive && !gone) {
                            Text(
                                "Автоудаление: сообщения исчезнут после прочтения",
                                color = AloNativeAppMuted, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            AloSecretChatCodec.TTL_CHOICES.forEach { (seconds, label) ->
                                DropdownMenuItem(
                                    text = { Text((if (chat.ttlSeconds == seconds) "✓ " else "") + label) },
                                    leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                                    onClick = {
                                        chatMenu = false
                                        onAction(AloNativeAppAction.SetSecretChatTimer(seconds))
                                    }
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(if (chat?.isIncomingRequest == true) "Отклонить запрос" else "Удалить секретный чат", color = Color(0xFFFF453A)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Color(0xFFFF453A)) },
                            onClick = {
                                chatMenu = false
                                confirmDelete = true
                            }
                        )
                    }
                }
                Box(
                    Modifier
                        .size(headerItem)
                        .clip(CircleShape)
                        .clickable(enabled = menuEnabled) { chatMenu = true }
                        .semantics { contentDescription = "Меню секретного чата" },
                    contentAlignment = Alignment.Center
                ) {
                    if (chat != null) {
                        AvatarBadge(
                            name = chat.peerName,
                            avatarUrl = chat.peerAvatarUrl,
                            verified = false,
                            size = headerItem,
                            colorSeed = chat.peerUserId.toString()
                        )
                    } else {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(AloNativeAppSurfaceRaised))
                    }
                    Box(Modifier.fillMaxSize().border(0.5.dp, AloNativeAppBorder, CircleShape))
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "note") {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = AloNativeAppMuted, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Сообщения шифруются и видны только на этом устройстве и на устройстве собеседника.",
                            color = AloNativeAppMuted, fontSize = 12.sp, lineHeight = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                item(key = "banner") {
                    SecretChatBanner(state = state, chat = chat, onAction = onAction)
                }
                items(state.activeSecretMessages, key = { it.id }) { message ->
                    SecretMessageBubble(
                        message = message,
                        mine = message.senderUserId == me,
                        nowMillis = clock.longValue,
                        playback = playback,
                        onAction = onAction
                    )
                }
            }

            if (chat != null && chat.isActive && !gone) {
                Box(Modifier.onSizeChanged { secretComposerHeightPx = it.height }) {
                    SecretComposer(
                        state = state,
                        text = input,
                        onTextChange = { input = it },
                        onSend = {
                            onAction(AloNativeAppAction.SendSecretMessage(input))
                            input = ""
                        },
                        onAction = onAction,
                        circleControl = circleControl
                    )
                }
            }
        }
        // The camera of a circle being recorded (the same overlay as an ordinary chat), driven by the composer's held
        // mic as in Telegram: release sends, the locked pause keeps it as a draft, sliding left cancels.
        if (state.secretCaptureState == AloChatCaptureState.Recording && state.secretRecorderMode == AloChatRecordMode.VideoCircle) {
            VideoCircleRecorderOverlay(
                bottomInset = with(LocalDensity.current) { secretComposerHeightPx.toDp() },
                onCancel = { onAction(AloNativeAppAction.StopSecretRecording(cancel = true)) },
                onRecorded = { uri, seconds -> onAction(AloNativeAppAction.CompleteSecretCircleRecording(uri, seconds, send = false)) },
                onRecordedSend = { uri, seconds -> onAction(AloNativeAppAction.CompleteSecretCircleRecording(uri, seconds, send = true)) },
                control = circleControl,
                veilStopsAtComposer = true
            )
        }
    }
    }

    if (confirmDelete) {
        val declining = chat?.isIncomingRequest == true
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text(if (declining) "Отклонить секретный чат?" else "Удалить секретный чат?", color = AloNativeAppText) },
            text = {
                Text(
                    if (declining) "Запрос исчезнет." else "Сообщения удалятся у вас и у собеседника, вернуть их нельзя.",
                    color = AloNativeAppMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onAction(AloNativeAppAction.DeleteSecretChat)
                }) { Text(if (declining) "Отклонить" else "Удалить", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена", color = AloNativeAppAccent) } }
        )
    }
}

@Composable
private fun SecretChatBanner(
    state: AloNativeAppUiState,
    chat: AloSecretChat?,
    onAction: (AloNativeAppAction) -> Unit
) {
    val name = chat?.peerName ?: "собеседник"
    when {
        state.isSecretChatGone -> SecretCard(
            title = "Секретный чат закрыт",
            text = "Его удалили, или он открыт на другом устройстве: секретный чат живёт только на двух устройствах.",
            actions = { SecretCardButton("К чатам") { onAction(AloNativeAppAction.CloseSecretChat) } }
        )
        chat == null -> if (state.isSecretChatLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = AloNativeAppAccent)
            }
        }
        chat.isIncomingRequest -> SecretCard(
            title = "$name предлагает секретный чат",
            text = "Он будет только на этом устройстве и на устройстве $name. Сообщения шифруются, на сервере они не читаются и не хранятся после удаления чата.",
            actions = {
                SecretCardButton("Принять") { onAction(AloNativeAppAction.AcceptSecretChat) }
                SecretCardButton("Отклонить", primary = false) { onAction(AloNativeAppAction.DeleteSecretChat) }
            }
        )
        chat.isWaiting -> SecretCard(
            title = "Ждём, пока $name зайдёт в сеть…",
            text = "Секретный чат начнётся, когда одно из устройств собеседника примет его."
        )
        chat.isActive && state.activeSecretMessages.isEmpty() && !state.isSecretChatLoading -> Text(
            "Здесь пока нет сообщений. Напишите первым!",
            color = AloNativeAppMuted, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        else -> Unit
    }
}

@Composable
private fun SecretCard(
    title: String,
    text: String,
    actions: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = AloNativeAppBackgroundSecondary,
        border = BorderStroke(1.dp, AloSecretGreen.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Lock, null, tint = AloSecretGreen, modifier = Modifier.size(18.dp))
                Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(text, color = AloNativeAppMuted, fontSize = 14.sp, lineHeight = 18.sp)
            if (actions != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
            }
        }
    }
}

@Composable
private fun SecretCardButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (primary) AloSecretGreen else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, AloNativeAppMuted.copy(alpha = 0.5f)),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (primary) Color.White else AloNativeAppText,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** Which voice message and which circle of the open secret chat play now (their message ids, 0 for none). */
private class SecretPlayback(
    val activeVoiceId: Int,
    val activeCircleId: Int,
    val onVoice: (Int) -> Unit,
    val onCircle: (Int) -> Unit
)

private fun AloOutgoingMessageAttachment.isLocalFile(): Boolean =
    url.startsWith("content://", ignoreCase = true) || url.startsWith("file://", ignoreCase = true)

@Composable
private fun SecretMessageBubble(
    message: AloSecretMessage,
    mine: Boolean,
    nowMillis: Long,
    playback: SecretPlayback,
    onAction: (AloNativeAppAction) -> Unit
) {
    // A circle has no bubble: the round video of an ordinary chat, its first frame showing, the time on a pill.
    val circles = message.attachments.filter { AloSecretChatCodec.isCircle(it) && it.isLocalFile() }
    val others = message.attachments.filterNot { it in circles }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            circles.forEach { attachment ->
                val single = message.copy(attachments = listOf(attachment)).toChatMessage()
                val media = single.toMediaItemOrNull()
                if (media != null) {
                    Box(Modifier.testTag("alo.secret.circle")) {
                        VideoCircleAttachment(
                            message = single,
                            isOutgoing = mine,
                            durationLabel = estimateAttachmentDurationLabel(single),
                            media = media,
                            title = if (mine) "Вы" else "Секретный чат",
                            onRequestPlayback = { playback.onCircle(message.id) },
                            onOpenMedia = { onAction(AloNativeAppAction.OpenMedia(it)) }
                        )
                    }
                }
            }
            if (others.isNotEmpty() || message.body.isNotBlank() || circles.isEmpty()) {
                SecretBubbleBody(message, others, mine, nowMillis, playback, onAction)
            } else {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.46f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    SecretMessageMeta(message, mine, nowMillis)
                }
            }
        }
    }
}

@Composable
private fun SecretBubbleBody(
    message: AloSecretMessage,
    attachments: List<AloOutgoingMessageAttachment>,
    mine: Boolean,
    nowMillis: Long,
    playback: SecretPlayback,
    onAction: (AloNativeAppAction) -> Unit
) {
    // A voice message alone shows its own time and ticks in its row, as in an ordinary chat: no second time line under it
    // (that line made the bubble deeper at the bottom than at the top and the side), and the same 10 × 6 dp insets.
    val voiceOnly = message.body.isBlank() && attachments.isNotEmpty() &&
        attachments.all { AloSecretChatCodec.isVoice(it) && it.isLocalFile() }
    val timed = message.expiresAt.isNotBlank() || message.selfDestructSeconds > 0
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (mine) AloNativeChatOutgoing else AloNativeChatIncoming,
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = if (voiceOnly) 6.dp else 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            attachments.forEach { attachment ->
                val local = attachment.isLocalFile()
                when {
                    !local -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = AloNativeAppMuted, modifier = Modifier.size(16.dp))
                        Text("Вложение недоступно", color = AloNativeAppMuted, fontSize = 14.sp)
                    }
                    // The voice bubble of an ordinary chat: play, the recorded bars, the length.
                    AloSecretChatCodec.isVoice(attachment) -> {
                        val single = message.copy(attachments = listOf(attachment)).toChatMessage()
                        VoiceMessageAttachment(
                            modifier = Modifier.widthIn(min = 220.dp).testTag("alo.secret.voice"),
                            message = single,
                            isOutgoing = mine,
                            fallbackDurationLabel = estimateAttachmentDurationLabel(single),
                            title = if (mine) "Вы" else "Секретный чат",
                            onRequestPlayback = { playback.onVoice(message.id) }
                        )
                    }
                    attachment.type == "image" -> AsyncImage(
                        model = attachment.url,
                        contentDescription = attachment.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 220.dp, height = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onAction(
                                    AloNativeAppAction.OpenMedia(
                                        AloMediaItem(
                                            type = "image",
                                            url = attachment.url,
                                            posterUrl = "",
                                            alt = attachment.name,
                                            caption = "",
                                            isSensitive = true
                                        )
                                    )
                                )
                            }
                    )
                    else -> Row(
                        Modifier.clickable {
                            onAction(AloNativeAppAction.OpenMessageAttachment(message.copy(attachments = listOf(attachment)).toChatMessage()))
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = AloNativeAppAccent, modifier = Modifier.size(26.dp))
                        Text(
                            attachment.name.ifBlank { "Файл" },
                            color = if (mine && AloNativeChatOutgoing.luminance() <= 0.5f) Color.White else AloNativeAppText, fontSize = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (message.body.isNotBlank()) {
                Text(
                    message.body,
                    color = when {
                        message.unreadable -> AloNativeAppMuted
                        mine -> Color.White
                        else -> AloNativeAppText
                    },
                    fontSize = 16.sp, lineHeight = 20.sp
                )
            }
            // Under a voice message only its timer, when it has one.
            if (!voiceOnly || timed) {
                Box(Modifier.align(Alignment.End)) { SecretMessageMeta(message, mine, nowMillis, showTime = !voiceOnly) }
            }
        }
    }
}

@Composable
private fun SecretMessageMeta(message: AloSecretMessage, mine: Boolean, nowMillis: Long, showTime: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val expiresAt = if (message.expiresAt.isBlank()) 0L else parseIsoTimestampMillis(message.expiresAt)
        val timerText = when {
            expiresAt > 0L -> AloSecretChatCodec.countdownLabel(expiresAt - nowMillis)
            message.selfDestructSeconds > 0 -> AloSecretChatCodec.timerLabel(message.selfDestructSeconds)
            else -> ""
        }
        if (timerText.isNotEmpty()) {
            Icon(Icons.Rounded.Timer, null, tint = AloSecretGreen, modifier = Modifier.size(12.dp))
            Text(timerText, color = AloSecretGreen, fontSize = 11.sp)
        }
        if (showTime) Text(formatMessageTime(message.createdAt), color = AloNativeAppMuted, fontSize = 11.sp)
        if (mine && showTime) {
            Icon(
                if (message.readAt.isNotBlank()) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                null,
                tint = if (message.readAt.isNotBlank()) AloSecretGreen else AloNativeAppMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SecretComposer(
    state: AloNativeAppUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAction: (AloNativeAppAction) -> Unit,
    circleControl: AloCircleRecordingControl
) {
    val canSend = (text.isNotBlank() || state.secretDraftAttachments.isNotEmpty()) && !state.isSendingSecret
    val isRecording = state.secretCaptureState == AloChatCaptureState.Recording
    val circleRecording = isRecording && state.secretRecorderMode == AloChatRecordMode.VideoCircle
    // Telegram's record button, as in an ordinary chat (AloRecordingGesture): a tap switches voice / circle, a hold
    // records, sliding left cancels, sliding up locks, letting go sends; a locked recording pauses into a draft.
    var mode by rememberSaveable { mutableStateOf(AloChatRecordMode.Voice) }
    var drag by remember { mutableStateOf(AloRecordingDrag()) }
    var held by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var startedAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            startedAtMs = System.currentTimeMillis()
        } else {
            held = false
            locked = false
            drag = AloRecordingDrag()
        }
    }
    val stopRecording: (Boolean) -> Unit = { cancel ->
        if (circleRecording) {
            circleControl.send(if (cancel) AloCircleCommand.Cancel else AloCircleCommand.Preview)
        } else {
            onAction(AloNativeAppAction.StopSecretRecording(cancel = cancel))
        }
    }
    val sendRecording: () -> Unit = {
        if (circleRecording) circleControl.send(AloCircleCommand.Send)
        else onAction(AloNativeAppAction.StopSecretRecording(cancel = false, send = true))
    }
    val haptics = LocalHapticFeedback.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val heldMic = rememberUpdatedState(
        AloHeldMicController(
            isRecording = isRecording,
            isLocked = locked,
            isVoice = mode == AloChatRecordMode.Voice,
            onTap = {
                mode = if (mode == AloChatRecordMode.Voice) AloChatRecordMode.VideoCircle else AloChatRecordMode.Voice
            },
            onStart = { onAction(AloNativeAppAction.StartSecretRecording(mode)) },
            onCancel = { stopRecording(true) },
            onSend = sendRecording,
            onStopToPreview = { stopRecording(false) },
            onDrag = { drag = it },
            onHeld = {
                held = it
                circleControl.isHeld = it
            },
            onLock = { locked = true },
            haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            cancelDistance = AloRecordingDrag.cancelDistanceFor(screenWidthDp)
        )
    )
    // Over the chat wallpaper, as the ordinary composer: no bar of its own, the field is a glass oval.
    Column(Modifier.fillMaxWidth()) {
        if (state.secretDraftAttachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.secretDraftAttachments.forEachIndexed { index, draft ->
                    Surface(shape = RoundedCornerShape(12.dp), color = AloNativeAppBackgroundSecondary) {
                        Row(
                            Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                secretDraftLabel(draft), color = AloNativeAppText, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)
                            )
                            IconButton(onClick = { onAction(AloNativeAppAction.RemoveSecretDraft(index)) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, "Убрать", tint = AloNativeAppMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isRecording) {
                IconButton(onClick = { onAction(AloNativeAppAction.PickSecretAttachment) }, enabled = !state.isSendingSecret) {
                    Icon(Icons.Rounded.AttachFile, "Прикрепить", tint = AloNativeAppMuted)
                }
            }
            if (isRecording) {
                // Telegram's record panel in place of the field: red dot, «0:03,45», «‹ Влево — отмена» / «ОТМЕНА».
                AloNativeGlassPanel(modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(22.dp)) {
                    AloVoiceRecordingPanel(
                        startedAtMs = startedAtMs,
                        isLocked = locked,
                        drag = drag,
                        onCancel = { stopRecording(true) },
                        modifier = Modifier.padding(start = 14.dp, end = 10.dp)
                    )
                }
            } else {
                AloNativeGlassPanel(modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp)) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Секретное сообщение", color = AloNativeAppMuted) },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions.Default,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = AloNativeAppText,
                        unfocusedTextColor = AloNativeAppText,
                        cursorColor = AloSecretGreen,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                }
            }
            Spacer(Modifier.size(6.dp))
            if (canSend || state.isSendingSecret) {
                Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (canSend) AloSecretGreen else AloNativeAppMuted.copy(alpha = 0.3f))
                    .clickable(enabled = canSend, onClick = onSend)
                    .testTag("alo.secret.send"),
                contentAlignment = Alignment.Center
            ) {
                if (state.isSendingSecret) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Rounded.ArrowUpward, "Отправить", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            } else {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    if (isRecording && held && !locked) {
                        val lockHeight = 50f - 14f * drag.lockProgress
                        AloRecordingLock(
                            progress = drag.lockProgress,
                            onLock = { locked = true },
                            modifier = Modifier.offset(y = (-112f + lockHeight / 2f + drag.orbY).dp).zIndex(2f)
                        )
                    }
                    if (isRecording && locked) {
                        AloRecordingPauseButton(
                            onClick = { stopRecording(false) },
                            modifier = Modifier.offset(y = (-78).dp).zIndex(2f)
                        )
                    }
                    if (isRecording && (held || locked)) {
                        AloRecordingOrb(
                            icon = when {
                                locked -> Icons.AutoMirrored.Rounded.Send
                                circleRecording -> Icons.Rounded.Videocam
                                else -> Icons.Rounded.Mic
                            },
                            color = AloSecretGreen,
                            level = LocalAloLiveInput.current.secretRecordingLevels.lastOrNull() ?: 0f,
                            scale = if (locked) 1f else drag.orbScale,
                            contentDescription = if (locked) "Отправить запись" else null,
                            modifier = Modifier
                                .offset(y = if (locked) 0.dp else drag.orbY.dp)
                                .zIndex(3f)
                                .then(if (locked) Modifier.clip(CircleShape).clickable(onClick = sendRecording) else Modifier)
                        )
                    }
                    // The mic itself hides under the orb but keeps the holding finger's gesture.
                    Box(
                        Modifier
                            .size(42.dp)
                            .graphicsLayer { alpha = if (isRecording && (held || locked)) 0f else 1f }
                            .clip(CircleShape)
                            .background(AloNativeAppBackgroundSecondary)
                            .aloHeldMic(heldMic)
                            .semantics {
                                contentDescription = if (mode == AloChatRecordMode.Voice) {
                                    "Записать голосовое сообщение. Нажмите, чтобы перейти к кружку"
                                } else {
                                    "Записать видеосообщение. Нажмите, чтобы перейти к голосовому"
                                }
                            }
                            .testTag("alo.secret.record"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (mode == AloChatRecordMode.Voice) Icons.Rounded.Mic else Icons.Rounded.Videocam,
                            null,
                            tint = AloNativeAppText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

/** A draft of the composer: a recording says what it is and how long, a file its name. */
internal fun secretDraftLabel(draft: AloChatDraftAttachment): String {
    val length = if (draft.durationSec > 0) " · %d:%02d".format(draft.durationSec / 60, draft.durationSec % 60) else ""
    return when {
        draft.isVideoCircle -> "Видеосообщение$length"
        draft.attachmentType == "voice" -> "Голосовое$length"
        else -> draft.name.ifBlank { "Файл" }
    }
}
