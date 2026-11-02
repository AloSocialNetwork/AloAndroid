package com.alo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ChatMessageActionSheet(
    message: AloChatMessage,
    isMine: Boolean,
    canEdit: Boolean,
    canDeleteForEveryone: Boolean,
    canPin: Boolean,
    isPinned: Boolean,
    /** The chat forbids copying and forwarding («Запретить копирование»). */
    isProtected: Boolean = false,
    bottomInset: Dp = 0.dp,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onPin: () -> Unit,
    onSelect: () -> Unit,
    onReschedule: () -> Unit,
    onViewScheduled: () -> Unit,
    onCancelScheduled: () -> Unit,
    /** The server translates, and this message has text and may leave the chat. */
    canTranslate: Boolean,
    hasTranslation: Boolean,
    onTranslate: (AloChatMessage) -> Unit,
    onHideTranslation: (AloChatMessage) -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val dimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.34f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "messageActionDim"
    )
    val isCallLog = message.toCallLogMeta() != null
    val isPoll = AloPollCodec.decode(message.body) != null
    LaunchedEffect(Unit) {
        visible = true
    }
    Box(
        modifier = Modifier
            .zIndex(40f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = dimAlpha))
            .clickable {
                visible = false
                onDismiss()
            },
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                scaleIn(animationSpec = tween(180, easing = FastOutSlowInEasing), initialScale = 0.985f),
            exit = fadeOut(animationSpec = tween(120, easing = LinearOutSlowInEasing)) +
                scaleOut(animationSpec = tween(140, easing = LinearOutSlowInEasing), targetScale = 0.992f),
            label = "messageActionSheet"
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .navigationBarsPadding()
                    .padding(
                        start = if (isMine) 24.dp else 10.dp,
                        end = if (isMine) 10.dp else 24.dp,
                        top = 10.dp,
                        bottom = bottomInset + 10.dp
                    )
                    .clickable(onClick = {}),
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isCallLog && !message.isScheduled) {
                    AloNativeGlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AloSquircle(28.dp)),
                        shape = AloSquircle(28.dp),
                        tint = AloNativeAppSurface.copy(alpha = 0.88f),
                        borderColor = AloNativeAppBorder.copy(alpha = 0.06f)
                    ) {
                        ChatActionReactionRow(
                            selectedEmoji = message.reactions.firstOrNull { it.reactedByMe }?.emoji,
                            onReact = onReact,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                ChatActionMessagePreview(
                    message = message,
                    isMine = isMine
                )

                AloNativeGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AloSquircle(26.dp),
                    tint = AloNativeAppSurface.copy(alpha = 0.90f),
                    borderColor = AloNativeAppBorder.copy(alpha = 0.05f)
                ) {
                    if (message.isScheduled) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (!isPoll && (message.body.isNotBlank() || message.attachmentName.isNotBlank())) {
                                ChatMessageActionRow(
                                    "Копировать",
                                    icon = Icons.Rounded.ContentCopy,
                                    onClick = onCopy
                                )
                            }
                            ChatMessageActionRow("Изменить время", icon = Icons.Rounded.Schedule, onClick = onReschedule)
                            ChatMessageActionRow("Все отложенные", icon = Icons.Rounded.Bookmark, onClick = onViewScheduled)
                            ChatMessageActionRow(
                                "Отменить отправку",
                                icon = Icons.Rounded.Delete,
                                destructive = true,
                                onClick = onCancelScheduled
                            )
                        }
                    } else {
                        AnimatedContent(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            targetState = confirmDelete,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) + slideInHorizontally(
                                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                                    initialOffsetX = { it / 8 }
                                )) togetherWith
                                    (fadeOut(animationSpec = tween(140, easing = LinearOutSlowInEasing)) + slideOutHorizontally(
                                        animationSpec = tween(140, easing = LinearOutSlowInEasing),
                                        targetOffsetX = { -it / 10 }
                                    ))
                            },
                            label = "messageDeleteChoice"
                        ) { deleting ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (deleting) {
                                    ChatMessageActionRow("Удалить у меня", icon = Icons.Rounded.Delete, destructive = true, onClick = onDeleteForMe)
                                    if (canDeleteForEveryone) {
                                        ChatMessageActionRow("Удалить у всех", icon = Icons.Rounded.Delete, destructive = true, onClick = onDeleteForEveryone)
                                    }
                                    ChatMessageActionRow("Назад", icon = Icons.Rounded.ArrowBack, onClick = { confirmDelete = false })
                                } else {
                                    if (!isProtected) {
                                        ChatMessageActionRow(
                                            "Копировать",
                                            icon = Icons.Rounded.ContentCopy,
                                            enabled = !isPoll && message.selfDestructSeconds <= 0 &&
                                                (message.body.isNotBlank() || message.attachmentName.isNotBlank()),
                                            onClick = onCopy
                                        )
                                    }
                                    if (canEdit && !isCallLog && message.body.isNotBlank()) {
                                        ChatMessageActionRow("Изменить", icon = Icons.Rounded.Edit, onClick = onEdit)
                                    }
                                    if (!isCallLog) {
                                        ChatMessageActionRow("Ответить", icon = Icons.AutoMirrored.Rounded.Reply, onClick = onReply)
                                        if (message.selfDestructSeconds <= 0 && !isProtected) {
                                            ChatMessageActionRow("Переслать", icon = Icons.Rounded.Share, onClick = onForward)
                                        }
                                    }
                                    if (canTranslate && !isCallLog && !isPoll) {
                                        ChatMessageActionRow(
                                            "Перевести",
                                            icon = Icons.Rounded.Translate,
                                            onClick = { onTranslate(message) }
                                        )
                                        if (hasTranslation) {
                                            ChatMessageActionRow(
                                                "Скрыть перевод",
                                                icon = Icons.Rounded.Translate,
                                                onClick = { onHideTranslation(message) }
                                            )
                                        }
                                    }
                                    if (canPin && !isCallLog) {
                                        ChatMessageActionRow(
                                            if (isPinned) "Открепить" else "Закрепить",
                                            icon = Icons.Rounded.Bookmark,
                                            onClick = onPin
                                        )
                                    }
                                    ChatMessageActionRow("Выбрать", icon = Icons.Rounded.Done, onClick = onSelect)
                                    ChatMessageActionRow("Удалить", icon = Icons.Rounded.Delete, destructive = true, onClick = { confirmDelete = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatScheduledMessagesSheet(
    messages: List<AloChatMessage>,
    onDismiss: () -> Unit,
    onReschedule: (AloChatMessage) -> Unit,
    onCancel: (AloChatMessage) -> Unit
) {
    Box(
        modifier = Modifier
            .zIndex(45f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        AloNativeGlassPanel(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clickable(onClick = {}),
            shape = AloSquircle(26.dp),
            tint = AloNativeAppSurface.copy(alpha = 0.96f),
            borderColor = AloNativeAppBorder.copy(alpha = 0.12f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
                Text(
                    text = "Отложенные сообщения",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AloNativeAppText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                if (messages.isEmpty()) {
                    Text(
                        text = "Нет отложенных сообщений",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AloNativeAppMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 22.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 430.dp)) {
                        items(messages, key = { it.scheduledMessageId }) { message ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AloSquircle(18.dp))
                                    .clickable { onReschedule(message) }
                                    .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = AloNativeAppAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp)
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = scheduledMessagePreview(message),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = AloNativeAppText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = scheduledMessageDateLabel(message),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AloNativeAppMuted
                                    )
                                    if (message.deliveryError.isNotBlank()) {
                                        Text(
                                            text = "Не удалось отправить: ${message.deliveryError}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { onCancel(message) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Отменить отправку",
                                        tint = Color(0xFFFF6B7A),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                ChatMessageActionRow("Закрыть", icon = Icons.Rounded.Done, onClick = onDismiss)
            }
        }
    }
}

private fun scheduledMessagePreview(message: AloChatMessage): String {
    if (message.body.isNotBlank()) {
        val poll = AloPollCodec.decode(message.body)
        return if (poll is AloPollEvent.Create) poll.definition.question else message.body
    }
    val attachments = message.attachments
    if (isVoiceAttachment(message)) return "Голосовое сообщение"
    if (isVideoCircleAttachment(message)) return "Кружок"
    return attachments.firstOrNull()?.name?.ifBlank { "Вложение" } ?: "Сообщение"
}

private fun scheduledMessageDateLabel(message: AloChatMessage): String {
    if (message.sendWhenOnline) return "Когда появится в сети"
    val value = message.scheduledAt
    return runCatching {
        val date = Instant.parse(value).atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofPattern("d MMM, HH:mm").format(date)
    }.getOrDefault("Время отправки не определено")
}

@Composable
private fun ChatActionReactionRow(
    selectedEmoji: String?,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (expanded) 22.dp else 24.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (change.pressed) change.consume()
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            aloMessageReactionEmojis.take(6).forEach { emoji ->
                ChatActionReactionButton(
                    emoji = emoji,
                    selected = emoji == selectedEmoji,
                    size = 40.dp,
                    onClick = { onReact(emoji) }
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.07f))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.Add,
                    contentDescription = if (expanded) "Свернуть реакции" else "Все реакции",
                    tint = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(140)) + slideInVertically(tween(160)) { -it / 5 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { -it / 6 }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                aloMessageReactionEmojis.drop(6).chunked(9).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEach { emoji ->
                            ChatActionReactionButton(
                                emoji = emoji,
                                selected = emoji == selectedEmoji,
                                size = 32.dp,
                                onClick = { onReact(emoji) }
                            )
                        }
                        repeat(9 - row.size) { Box(modifier = Modifier.size(32.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatActionReactionButton(
    emoji: String,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    val interaction = remember(emoji) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMedium),
        label = "actionReactionPress"
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(if (selected) AloNativeAppAccent.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.22f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.28f else 0.08f),
                shape = CircleShape
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
    }
}

/** The reactions the server accepts (it refuses 😮). */
internal val aloMessageReactionEmojis = listOf(
    "❤️", "🔥", "😂", "👏", "👍", "👎", "🥰",
    "😁", "🤩", "🤔", "🤯", "😱", "😢", "😭", "😡", "🎉",
    "💯", "🤝", "🙏", "👌", "😎", "👀", "🤗", "🫡",
    "🤪", "😴", "🤓", "👻", "🎃", "💔", "⚡", "🏆",
    "💘", "❤️‍🔥", "🍓", "💅", "🕊", "🍾", "🤮", "💩", "🤡", "🥱", "😉", "😍"
)

@Composable
private fun ChatActionMessagePreview(
    message: AloChatMessage,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val visualMedia = message.toVisualMediaItems()
    val media = visualMedia.firstOrNull()
    val isVoice = isVoiceAttachment(message)
    val callLogMeta = message.toCallLogMeta()
    val pollPreview = (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
        ?.definition
        ?.let { "Опрос: ${it.question}" }
    val bubbleColor = if (isMine) AloNativeChatOutgoing else AloNativeChatIncoming
    // White ink only on the dark theme's blue own bubble; on the light theme's pale one it could not be read.
    val inkOnBlue = isMine && bubbleColor.luminance() <= 0.5f
    val maxBubbleWidth = when {
        isVoice -> 252.dp
        callLogMeta != null -> 212.dp
        media != null -> 300.dp
        else -> 320.dp
    }
    Column(
        modifier = modifier
            .widthIn(max = maxBubbleWidth)
            .clip(
                RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isMine) 20.dp else 10.dp,
                    bottomEnd = if (isMine) 10.dp else 20.dp
                )
            )
            .background(bubbleColor)
            .padding(
                horizontal = when {
                    isVoice -> 10.dp
                    callLogMeta != null -> 8.dp
                    else -> 12.dp
                },
                vertical = when {
                    isVoice -> 6.dp
                    callLogMeta != null -> 7.dp
                    else -> 12.dp
                }
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            callLogMeta != null -> CallLogAttachment(
                meta = callLogMeta,
                isOutgoing = isMine,
                message = message
            )
            media != null -> {
                if (visualMedia.size > 1) {
                    MessageMediaCollage(
                        items = visualMedia,
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color.White.copy(alpha = 0.10f),
                        onOpenAt = {},
                        onLongPress = {}
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(AloSquircle(15.dp))
                            .background(Color.Black.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AloRemoteMediaImage(
                            model = media.posterUrl.ifBlank { media.url },
                            contentDescription = media.alt.ifBlank { message.body },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            isVideo = media.type.startsWith("video")
                        )
                        if (media.type.startsWith("video")) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
                if (message.body.isNotBlank()) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            isVoice -> VoiceMessageAttachment(
                message = message,
                isOutgoing = isMine,
                fallbackDurationLabel = estimateAttachmentDurationLabel(message),
                onLongPress = null
            )
            else -> Text(
                text = pollPreview ?: message.body.ifBlank { message.attachmentName.ifBlank { "Сообщение" } },
                style = MaterialTheme.typography.bodyLarge,
                color = if (inkOnBlue) Color.White else AloNativeAppText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (message.reactions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Bottom
            ) {
                MessageReactionStrip(
                    reactions = message.reactions,
                    isOutgoing = isMine,
                    onReact = {}
                )
            }
        }
        if (callLogMeta == null && !isVoice) {
            Text(
                text = buildMessageMetaLabel(message.createdAt, message.editedAt),
                modifier = Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = if (inkOnBlue) Color.White.copy(alpha = 0.78f) else AloNativeAppMuted
            )
        }
    }
}

@Composable
private fun ChatActionVoicePreview(isMine: Boolean) {
    // White ink only on the dark theme's blue own bubble.
    val inkOnBlue = isMine && AloNativeChatOutgoing.luminance() <= 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (inkOnBlue) Color.White.copy(alpha = 0.18f) else AloNativeAppSurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = if (inkOnBlue) Color.White else AloNativeAppText,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(10, 18, 13, 24, 17, 27, 12, 21, 15, 23, 11, 18, 13, 20).forEach { height ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(height.dp)
                        .clip(CircleShape)
                        .background(if (inkOnBlue) Color.White.copy(alpha = 0.56f) else AloNativeAppMuted.copy(alpha = 0.58f))
                )
            }
        }
    }
}

@Composable
internal fun ForwardMessageTargetSheet(
    conversations: List<AloConversationSummary>,
    currentKind: String,
    currentPeerId: Int,
    currentRoomId: Int,
    selectedCount: Int,
    bottomInset: Dp = 0.dp,
    onDismiss: () -> Unit,
    onSelect: (AloConversationSummary) -> Unit
) {
    val currentIsGroup = currentKind == "group" || currentRoomId > 0
    val targets = conversations.filterNot { conversation ->
        val targetIsGroup = conversation.kind == "group" || conversation.roomId > 0
        if (currentIsGroup || targetIsGroup) {
            targetIsGroup && currentIsGroup && conversation.roomId > 0 && conversation.roomId == currentRoomId
        } else {
            conversation.peerId == currentPeerId
        }
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    Box(
        modifier = Modifier
            .zIndex(40f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .clickable {
                visible = false
                onDismiss()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 3 }
            ),
            exit = fadeOut(animationSpec = tween(140)) + slideOutVertically(
                animationSpec = tween(180, easing = LinearOutSlowInEasing),
                targetOffsetY = { it / 4 }
            ),
            label = "forwardTargetSheet"
        ) {
            AloNativeGlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = bottomInset + 10.dp)
                    .clickable(onClick = {}),
                shape = AloSquircle(26.dp),
                tint = AloNativeAppSurface.copy(alpha = 0.88f),
                borderColor = AloNativeAppBorder.copy(alpha = 0.05f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 10.dp)
                            .width(42.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(AloNativeAppMuted.copy(alpha = 0.5f))
                    )
                    Text(
                        text = if (selectedCount > 1) "Переслать $selectedCount сообщения" else "Переслать",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText
                    )
                    if (targets.isEmpty()) {
                        EmptyPanelBody(
                            title = "Некуда переслать",
                            message = "Других чатов пока нет."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(targets, key = { it.peerId }) { conversation ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AloSquircle(16.dp))
                                        .clickable { onSelect(conversation) }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (conversation.peer.entityType == "saved") {
                                        SavedMessagesAvatar(size = 42.dp)
                                    } else {
                                        AvatarBadge(
                                            name = conversation.peer.name,
                                            avatarUrl = conversation.peer.avatarUrl,
                                            verified = conversation.peer.verified,
                                            size = 42.dp,
                                            colorSeed = conversation.peer.id.toString()
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conversation.peer.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = AloNativeAppText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = conversation.lastBody.ifBlank { "Чат" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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

@Composable
internal fun ChatMessageActionRow(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    // A softer red reads on the dark menu; on the light theme's white one it was too pale, so it takes the system red.
    val dangerInk = if (AloNativeAppBackground.luminance() > 0.5f) Color(0xFFE5303F) else Color(0xFFFF6B7A)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.66f, stiffness = Spring.StiffnessMedium),
        label = "messageActionPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .scale(scale)
            .clip(AloSquircle(16.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !enabled -> AloNativeAppMuted.copy(alpha = 0.08f)
                            destructive -> dangerInk.copy(alpha = 0.14f)
                            else -> AloNativeAppText.copy(alpha = 0.07f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when {
                        !enabled -> AloNativeAppMuted.copy(alpha = 0.45f)
                        destructive -> dangerInk
                        else -> AloNativeAppText
                    },
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(modifier = Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                !enabled -> AloNativeAppMuted.copy(alpha = 0.45f)
                destructive -> dangerInk
                else -> AloNativeAppText
            }
        )
    }
}
