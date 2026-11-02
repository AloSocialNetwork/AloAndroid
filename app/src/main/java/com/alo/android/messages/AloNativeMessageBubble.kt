package com.alo.android

import androidx.compose.ui.text.TextLayoutResult

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.gestures.waitForUpOrCancellation

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun MessageBubble(
    modifier: Modifier = Modifier,
    me: AloCurrentUser,
    detail: AloConversationDetail,
    message: AloChatMessage,
    replyToMessage: AloChatMessage?,
    pollState: AloPollViewState?,
    forceIncoming: Boolean = false,
    /** Where this message sits in a run from one sender: the first shows the name, the last the avatar (groups). */
    groupRun: AloGroupRunPosition = AloGroupRunPosition.Single,
    /** The conversation list draws a run's avatar itself, sticky to the bottom ([AloStickyRunAvatars]). */
    drawsGroupAvatar: Boolean = true,
    isPending: Boolean,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onPrepareMediaOpen: (AloChatMessage, (AloChatMessage) -> Unit) -> Unit,
    onOpenAttachment: (AloChatMessage) -> Unit,
    onOpenDiscussion: (Int) -> Unit,
    onOpenProfile: (AloProfileTarget) -> Unit,
    /** A tap on the quoted reply: Telegram scrolls to the original and highlights it. */
    onOpenReply: (Int) -> Unit = {},
    onVoicePlaybackRequested: (Int) -> Unit,
    onReact: (String) -> Unit,
    onPollVote: (String) -> Unit,
    canClosePoll: Boolean,
    onClosePoll: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var preparedMediaMessage by remember(message.id, message.expiresAt) {
        mutableStateOf(message)
    }
    var hasPreparedMediaAccess by remember(message.id, message.expiresAt) {
        mutableStateOf(
            message.selfDestructSeconds <= 0 ||
                message.selfDestructStartedAt.isNotBlank() ||
                message.expiresAt.isNotBlank()
        )
    }
    var longPressPulse by remember(message.id) { mutableStateOf(false) }
    var suppressMediaOpenUntilMs by remember(message.id) { mutableStateOf(0L) }
    val messageScale by animateFloatAsState(
        targetValue = if (longPressPulse) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "messageLongPressPulse"
    )
    fun isMediaOpenSuppressed(): Boolean {
        return SystemClock.elapsedRealtime() < suppressMediaOpenUntilMs
    }
    val openMessageActions = {
        suppressMediaOpenUntilMs = SystemClock.elapsedRealtime() + 760L
        longPressPulse = true
        scope.launch {
            delay(90)
            longPressPulse = false
        }
        onLongPress()
    }
    val isLightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val isOutgoing = !forceIncoming && message.senderId == me.id
    val isVoice = isVoiceAttachment(preparedMediaMessage)
    val callLogMeta = message.toCallLogMeta()
    val visualAttachments = preparedMediaMessage.toVisualMediaItems()
    val isStandaloneSticker = visualAttachments.size == 1 && visualAttachments.first().type == "sticker"
    val contactAttachment = preparedMediaMessage.attachments.firstOrNull { it.isContactCard }
    val locationAttachment = preparedMediaMessage.attachments.firstOrNull { it.isLocationCard }
    val isCircle = visualAttachments.size <= 1 && isVideoCircleAttachment(preparedMediaMessage)
    val hasVisualMedia = visualAttachments.isNotEmpty()
    val mediaAttachment = preparedMediaMessage.toMediaItemOrNull()
    val durationLabel = estimateAttachmentDurationLabel(preparedMediaMessage)
    // Encrypted media the auto-download rules (Settings → Данные и память) left on the server waits for a tap.
    val downloadPending = message.selfDestructSeconds <= 0 && preparedMediaMessage.hasUndownloadedMedia()
    val mediaAccessGranted = !downloadPending && (message.selfDestructSeconds <= 0 ||
        hasPreparedMediaAccess ||
        preparedMediaMessage.selfDestructStartedAt.isNotBlank() ||
        preparedMediaMessage.expiresAt.isNotBlank())
    fun prepareMedia(onReady: (AloChatMessage) -> Unit) {
        if (mediaAccessGranted) {
            onReady(preparedMediaMessage)
        } else {
            onPrepareMediaOpen(message) { prepared ->
                preparedMediaMessage = prepared
                hasPreparedMediaAccess = true
                onReady(prepared)
            }
        }
    }
    val compactVisualBubble = hasVisualMedia && message.body.isBlank()
    // A group chat: incoming bubbles leave a 52dp gutter for the sender's avatar (Telegram's `drawAvatar`).
    val inGroup = detail.kind == "group" && !isOutgoing && !forceIncoming
    val groupSender = message.sender?.takeIf { inGroup && it.name.isNotBlank() }
    val senderNameColor = if (message.senderIsAnonymous) {
        AloNativeAppAccent
    } else {
        // Contrast with the bubble the name sits in, not with the app's background: the chat has its own palette.
        aloSenderNameColor(groupSender?.id?.toString().orEmpty(), lightSurface = AloNativeChatIncoming.luminance() > 0.5f)
    }
    val senderRoleLabel = if (groupSender != null) aloGroupSenderRoleLabel(message, detail.members) else ""
    val showGroupName = groupSender != null && groupRun.startsRun
    // The name goes first inside a bubble; a picture or a circle without a bubble has it above.
    val nameAboveBubble = showGroupName && (compactVisualBubble || isCircle)
    val nameInsideBubble = showGroupName && !nameAboveBubble
    val textLikeBubble = !hasVisualMedia && !isVoice && pollState == null && callLogMeta == null &&
        contactAttachment == null && locationAttachment == null && message.attachmentType.isBlank()
    // The tag sits at the bubble's right end: in a text bubble by sizing it to its widest line, a picture's bubble is full width anyway.
    val roleAtRightEnd = senderRoleLabel.isNotBlank() && (textLikeBubble || hasVisualMedia)
    // A text bubble with reactions puts its time on the last row of chips (Telegram's `lastLineX + timeWidth` check), so the column is as wide as the bubble.
    val reactionsCarryMeta = textLikeBubble && message.reactions.isNotEmpty() && !isCircle
    // Telegram Android's bubble: 17dp corners (its default "message corners"), 6dp on the tail side.
    val mainCornerRadius = 17.dp
    val tailCornerRadius = 6.dp
    val mediaStrokeColor = if (isOutgoing) {
        AloNativeChatOutgoingBorder.copy(alpha = 0.18f)
    } else if (isLightSurface) {
        Color.Black.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val bubbleColor = when {
        compactVisualBubble -> Color.Transparent
        isOutgoing -> AloNativeChatOutgoing
        else -> AloNativeChatIncoming
    }
    // Text colours follow the bubble that carries them (a light bubble gets dark ink, a dark one light), not only the app theme.
    val outgoingBubbleIsLight = AloNativeChatOutgoing.luminance() > 0.5f
    val bubbleBorder = if (compactVisualBubble) {
        null
    } else if (isOutgoing) {
        BorderStroke(1.dp, AloNativeChatOutgoingBorder.copy(alpha = 0.14f))
    } else {
        null
    }
    val messageTextColor = when {
        isOutgoing && outgoingBubbleIsLight -> AloChatInk.onLightBubble
        isOutgoing -> Color.White
        else -> AloNativeAppText
    }
    val metaTextColor = when {
        isOutgoing && outgoingBubbleIsLight -> AloChatInk.metaOnLightBubble
        isOutgoing -> Color(0xBFE4EEFA)
        isLightSurface -> AloChatInk.metaOnLightIncoming
        else -> Color(0xFFA8AFBB)
    }
    // Translation and voice-to-text (Telegram's «Перевести» and «Aa»): present only where the server offers them and the chat lets content leave.
    val contentTools = LocalAloContentTools.current
    // A double tap sends the reaction chosen in the settings (the same on every device), the heart until then.
    val favoriteReaction = LocalAloFavoriteReaction.current
    val protectedChat = detail.groupPolicy?.protectedContent == true
    val translationState = contentTools?.translations?.get(message.id)
    val hearsAsText = isVoice || isCircle
    val transcriptState = if (contentTools != null && hearsAsText) contentTools.transcriptState(message) else null
    val canTranscribe = contentTools != null && hearsAsText && contentTools.canTranscribe(message, protectedChat)
    val rendersInlineMeta = callLogMeta == null &&
        translationState == null &&
        // A message with quotes or code blocks puts its time on a line of its own, as on iOS.
        !aloMessageHasBlocks(message) &&
        message.body.isNotBlank() &&
        !hasVisualMedia &&
        !isVoice &&
        message.attachmentType.isBlank() &&
        contactAttachment == null &&
        locationAttachment == null &&
        message.reactions.isEmpty()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) AloNativeAppAccent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 1.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        if (selectionMode && !isOutgoing) {
            MessageSelectionMark(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 8.dp)
                    .clickable(onClick = onClick)
            )
        }
        if (inGroup) {
            Box(
                modifier = Modifier
                    .width(AloGroupMessageMetrics.GutterWidth)
                    .align(Alignment.Bottom)
            ) {
                if (groupRun.endsRun && groupSender != null && drawsGroupAvatar) {
                    AvatarBadge(
                        // 6dp from the screen's edge, which is left of the list's own padding.
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .offset(x = AloGroupMessageMetrics.AvatarOffsetInList),
                        name = groupSender.name,
                        avatarUrl = groupSender.avatarUrl,
                        verified = false,
                        size = AloGroupMessageMetrics.AvatarSize,
                        colorSeed = groupSender.id.toString(),
                        onClick = if (!message.senderIsAnonymous && groupSender.id > 0) {
                            { onOpenProfile(AloProfileTarget.User(groupSender.id)) }
                        } else null
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                // Telegram's entrance for a message that has just arrived (AloMessageEnterTransition).
                .aloMessageEnter(LocalAloMessageEnter.current)
                .graphicsLayer {
                    scaleX = messageScale
                    scaleY = messageScale
                }
                .widthIn(
                    max = when {
	                    isStandaloneSticker -> 220.dp
	                    isCircle -> 236.dp
	                    isVoice -> 244.dp
	                    callLogMeta != null -> 196.dp
	                    // iOS's AloChatMetrics.messageWidth: the row width less 40 on the far side and 8 near.
	                    // A group's incoming bubbles also give up the avatar's gutter.
	                    else -> (LocalConfiguration.current.screenWidthDp.dp - 77.dp - if (inGroup) AloGroupMessageMetrics.GutterWidth else 0.dp)
	                        .coerceAtLeast(240.dp)
                    }
                )
        ) {
            if (nameAboveBubble && groupSender != null) {
                GroupSenderNameRow(
                    name = groupSender.name,
                    roleLabel = senderRoleLabel,
                    color = senderNameColor,
                    roleAtRightEnd = false,
                    onClick = if (!message.senderIsAnonymous && groupSender.id > 0) {
                        { onOpenProfile(AloProfileTarget.User(groupSender.id)) }
                    } else null,
                    modifier = Modifier.padding(start = 8.dp, bottom = 3.dp)
                )
            }
            if (isCircle && mediaAttachment != null) {
                Box(
                    modifier = Modifier
                        .padding(bottom = if (message.reactions.isNotEmpty()) 12.dp else 0.dp)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = openMessageActions,
                            onDoubleClick = { onReact(favoriteReaction) }
                        )
                ) {
                    VideoCircleAttachment(
                        message = preparedMediaMessage,
                        isOutgoing = isOutgoing,
                        durationLabel = durationLabel,
                        media = mediaAttachment,
                        title = if (isOutgoing) "Вы" else message.sender?.name?.takeIf { it.isNotBlank() } ?: detail.title,
                        onOpenMedia = onOpenMedia,
                        mediaAccessGranted = mediaAccessGranted,
                        onRequestMediaAccess = {
                            prepareMedia { preparedMediaMessage = it }
                        }
                    )
                    MessageReactionBadge(
                        reactions = message.reactions.take(3),
                        isOutgoing = isOutgoing,
                        onReact = onReact,
                        messageId = message.id,
                        modifier = Modifier.align(if (isOutgoing) Alignment.BottomStart else Alignment.BottomEnd)
                    )
                    if (canTranscribe) {
                        // Telegram puts «Aa» of a circle at its edge, on a dark pill so it reads over the picture.
                        Box(
                            modifier = Modifier
                                .align(if (isOutgoing) Alignment.TopStart else Alignment.TopEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color.Black.copy(alpha = 0.34f))
                        ) {
                            AloTranscriptButton(
                                state = transcriptState,
                                isOutgoing = true,
                                onClick = { prepareMedia { prepared -> contentTools?.toggleTranscript(prepared, detail.kind, detail.roomId) } }
                            )
                        }
                    }
                }
                if (transcriptState != null && contentTools != null) {
                    // A circle has no bubble: its text gets a small one.
                    AloTranscriptBlock(
                        state = transcriptState,
                        isOutgoing = isOutgoing,
                        textColor = messageTextColor,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(AloSquircle(14.dp))
                            .background(if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming)
                            .padding(start = 11.dp, end = 11.dp, bottom = 8.dp)
                    )
                }
            } else {
                val surfaceModifier = if (isVoice) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = openMessageActions,
                        onDoubleClick = { onReact(favoriteReaction) }
                    )
                }
                Box {
                    Surface(
                        modifier = surfaceModifier.animateContentSize(
                            animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow)
                        ),
                        shape = RoundedCornerShape(
                            // Telegram's isTopNear: in a run, the top corner on the tail side is the small one too.
                            topStart = if (!isOutgoing && !groupRun.startsRun) AloSeriesMetrics.NearCorner else mainCornerRadius,
                            topEnd = if (isOutgoing && !groupRun.startsRun) AloSeriesMetrics.NearCorner else mainCornerRadius,
                            bottomStart = if (isOutgoing) mainCornerRadius else tailCornerRadius,
                            bottomEnd = if (isOutgoing) tailCornerRadius else mainCornerRadius
                        ),
                        color = bubbleColor,
                        border = bubbleBorder
                    ) {
	                        Column(
	                            modifier = (if ((nameInsideBubble && roleAtRightEnd && textLikeBubble) || reactionsCarryMeta) Modifier.width(IntrinsicSize.Max) else Modifier).padding(
		                                // Telegram Android's ChatMessageCell: text 11dp from the bubble's sides, 8dp from its top.
		                                start = when {
                                            compactVisualBubble -> 3.dp
                                            isVoice -> 10.dp
                                            callLogMeta != null -> 8.dp
                                            else -> 11.dp
                                        },
		                                end = when {
                                            compactVisualBubble -> 3.dp
                                            isVoice -> 10.dp
                                            callLogMeta != null -> 8.dp
                                            else -> 11.dp
                                        },
		                                top = when {
                                            compactVisualBubble -> 3.dp
                                            isVoice -> 6.dp
                                            callLogMeta != null -> 7.dp
                                            else -> 8.dp
                                        },
		                                bottom = when {
                                            compactVisualBubble -> 3.dp
                                            isVoice -> 6.dp
                                            callLogMeta != null -> 7.dp
                                            else -> 7.dp
                                        }
	                            )
	                        ) {
                            if (nameInsideBubble && groupSender != null) {
                                GroupSenderNameRow(
                                    name = groupSender.name,
                                    roleLabel = senderRoleLabel,
                                    color = senderNameColor,
                                    roleAtRightEnd = roleAtRightEnd,
                                    onClick = if (!message.senderIsAnonymous && groupSender.id > 0) {
                                        { onOpenProfile(AloProfileTarget.User(groupSender.id)) }
                                    } else null,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                            message.forwardFrom?.let { forward ->
                                Text(
                                    text = "Переслано от ${forward.userName}",
                                    modifier = Modifier
                                        .padding(bottom = if (message.body.isNotBlank() || message.attachmentType.isNotBlank()) 6.dp else 0.dp)
                                        .clickable(enabled = forward.userId > 0) {
                                            onOpenProfile(AloProfileTarget.User(forward.userId))
                                        },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOutgoing && !outgoingBubbleIsLight) Color.White.copy(alpha = 0.86f) else AloNativeChatAccentBlue
                                )
                            }
                            if (message.replyToMessageId > 0) {
                                ReplyQuote(
                                    source = replyToMessage,
                                    isOutgoing = isOutgoing,
                                    authorColor = aloReplyQuoteColor(
                                        replyToMessage,
                                        lightSurface = AloNativeChatIncoming.luminance() > 0.5f,
                                        fallback = AloNativeChatAccentBlue
                                    ),
                                    nameAboveQuote = nameInsideBubble,
                                    onClick = if (replyToMessage != null) {
                                        { onOpenReply(message.replyToMessageId) }
                                    } else null
                                )
                            }
                            // Telegram's file row, before the caption; while sending, its icon is the upload ring.
                            val isFileMessage = pollState == null && callLogMeta == null && message.attachmentType.isNotBlank() &&
                                !hasVisualMedia && !isVoice && contactAttachment == null && locationAttachment == null
                            // A song: Telegram's track row (AloMusic.kt); while it uploads it keeps the file row's ring.
                            val songTrack = if (isFileMessage && !isPending) AloMusic.trackOf(preparedMediaMessage) else null
                            if (songTrack != null) {
                                val musicQueue = LocalAloMusicQueue.current
                                AloMusicTrackRow(
                                    track = songTrack,
                                    queue = musicQueue.tracks,
                                    chatKey = musicQueue.chatKey,
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor,
                                    modifier = Modifier.padding(bottom = if (message.body.isNotBlank()) 6.dp else 0.dp),
                                    onLongPress = openMessageActions
                                )
                            } else if (isFileMessage) {
                                val file = message.attachments.firstOrNull()
                                AloFileAttachmentRow(
                                    name = message.attachmentName.ifBlank { file?.name.orEmpty() },
                                    sizeBytes = file?.let { it.mediaEncryption["plaintextSize"] ?: it.metadata["size"] }?.toLongOrNull(),
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor,
                                    uploadLocalId = message.id.takeIf { isOutgoing && isPending },
                                    modifier = Modifier.padding(bottom = if (message.body.isNotBlank()) 6.dp else 0.dp),
                                    onOpen = { onOpenAttachment(preparedMediaMessage) },
                                    onLongPress = openMessageActions
                                )
                            }
                            if (pollState != null) {
                                PollMessageContent(
                                    state = pollState,
                                    message = message,
                                    isOutgoing = isOutgoing,
                                    isPending = isPending,
                                    onVote = onPollVote,
                                    canClosePoll = canClosePoll,
                                    onClosePoll = onClosePoll
                                )
                            } else if (callLogMeta != null) {
                                CallLogAttachment(
                                    meta = callLogMeta,
                                    isOutgoing = isOutgoing,
                                    message = message
                                )
                            } else if (rendersInlineMeta) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    AloRichMessageText(
                                        message = message,
                                        modifier = Modifier.weight(1f, fill = false),
                                        color = messageTextColor
                                    )
                                    MessageInlineMeta(
                                        message = message,
                                        isOutgoing = isOutgoing,
                                        isPending = isPending,
                                        color = metaTextColor
                                    )
                                }
                            } else if (message.body.isNotBlank() && !hasVisualMedia) {
                                AloRichMessageText(
                                    message = message,
                                    color = messageTextColor
                                )
                            }

                            if (visualAttachments.isNotEmpty()) {
                                if (!mediaAccessGranted) {
                                    ExpiringMediaPlaceholder(
                                        itemCount = visualAttachments.size,
                                        selfDestructSeconds = message.selfDestructSeconds,
                                        downloadBytes = if (downloadPending) preparedMediaMessage.undownloadedBytes() else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (visualAttachments.size > 1) 244.dp else 220.dp)
                                            .combinedClickable(
                                                onClick = {
                                                    if (!isMediaOpenSuppressed()) {
                                                        if (selectionMode) {
                                                            onClick()
                                                        } else {
                                                            prepareMedia { prepared ->
                                                                val items = prepared.toVisualMediaItems()
                                                                if (items.size == 1) {
                                                                    onOpenMedia(items.first())
                                                                } else if (items.isNotEmpty()) {
                                                                    onOpenMediaGallery(items, 0)
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                onLongClick = openMessageActions
                                            )
                                    )
                                } else if (visualAttachments.size == 1) {
                                    val visual = visualAttachments.first()
                                    if (visual.type != "video") {
                                        val visualShape = if (isStandaloneSticker) {
                                            RoundedCornerShape(0.dp)
                                        } else {
                                            AloSquircle(16.dp)
                                        }
                                        val originTracker = rememberAloMediaOriginTracker()
                                        val originCornerPx = with(LocalDensity.current) { 16.dp.toPx() }
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 0.dp)
                                                .aloSingleMediaShape(visual.takeUnless { isStandaloneSticker }, if (isStandaloneSticker) 196.dp else 220.dp, isOutgoing)
                                                .trackMediaOrigin(originTracker)
                                                .clip(visualShape)
                                                .border(
                                                    if (isStandaloneSticker) 0.dp else 0.85.dp,
                                                    if (isStandaloneSticker) Color.Transparent else mediaStrokeColor,
                                                    visualShape
                                                )
                                                .background(if (isStandaloneSticker) Color.Transparent else Color(0x12000000))
                                                .combinedClickable(
                                                    onClick = {
                                                        if (!isMediaOpenSuppressed()) {
                                                            if (selectionMode) {
                                                                onClick()
                                                            } else {
                                                                if (!isStandaloneSticker) originTracker.offer(originCornerPx)
                                                                prepareMedia { prepared ->
                                                                    prepared.toVisualMediaItems()
                                                                        .firstOrNull()
                                                                        ?.let(onOpenMedia)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onLongClick = openMessageActions
                                                )
                                        ) {
                                            AloRemoteMediaImage(
                                                model = visual.posterUrl.ifBlank { visual.url },
                                                contentDescription = visual.alt.ifBlank { message.body },
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = if (visual.type == "sticker") ContentScale.Fit else ContentScale.Crop,
                                                isVideo = false
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 0.dp)
                                                .aloSingleMediaShape(visual, 212.dp, isOutgoing)
                                                .clip(AloSquircle(16.dp))
                                                .background(Color(0x16000000))
                                                .border(0.85.dp, mediaStrokeColor, AloSquircle(16.dp))
                                                .combinedClickable(
                                                    onClick = {
                                                        if (!isMediaOpenSuppressed()) {
                                                            if (selectionMode) {
                                                                onClick()
                                                            } else {
                                                                prepareMedia { prepared ->
                                                                    prepared.toVisualMediaItems()
                                                                        .firstOrNull()
                                                                        ?.let(onOpenMedia)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onLongClick = openMessageActions
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AloRemoteMediaImage(
                                                model = visual.posterUrl.ifBlank { visual.url },
                                                contentDescription = visual.alt.ifBlank { message.body },
                                                modifier = Modifier.fillMaxSize(),
                                                isVideo = true
                                            )
                                            // One play sign over the frame (the picture itself names nothing: the file name
                                            // used to sit on top of the placeholder's own «Видео» label).
                                            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.42f)) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = visual.alt.ifBlank { "Видео" },
                                                    tint = Color.White,
                                                    modifier = Modifier.padding(9.dp).size(30.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    MessageMediaCollage(
                                        items = visualAttachments,
                                        modifier = Modifier.fillMaxWidth(),
                                        borderColor = mediaStrokeColor,
                                        onOpenAt = { index ->
                                            if (!isMediaOpenSuppressed()) {
                                                if (selectionMode) {
                                                    onClick()
                                                } else {
                                                    prepareMedia { prepared ->
                                                        val items = prepared.toVisualMediaItems()
                                                        if (items.isNotEmpty()) {
                                                            onOpenMediaGallery(
                                                                items,
                                                                index.coerceIn(0, items.lastIndex)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onLongPress = openMessageActions
                                    )
                                }
                                if (message.body.isNotBlank()) {
                                    AloRichMessageText(
                                        message = message,
                                        modifier = Modifier.padding(top = 10.dp),
                                        color = messageTextColor
                                    )
                                }
                            } else if (isVoice) {
                                VoiceMessageAttachment(
                                    modifier = Modifier.padding(top = if (message.body.isNotBlank()) 6.dp else 0.dp),
                                    message = preparedMediaMessage,
                                    isOutgoing = isOutgoing,
                                    fallbackDurationLabel = durationLabel,
                                    title = if (isOutgoing) "Вы" else message.sender?.name?.takeIf { it.isNotBlank() } ?: detail.title,
                                    onRequestPlayback = { onVoicePlaybackRequested(message.id) },
                                    onLongPress = openMessageActions,
                                    mediaAccessGranted = mediaAccessGranted,
                                    downloadPending = downloadPending,
                                    onRequestMediaAccess = {
                                        prepareMedia { preparedMediaMessage = it }
                                    },
                                    transcriptState = transcriptState,
                                    onTranscribe = if (canTranscribe) {
                                        { prepareMedia { prepared -> contentTools?.toggleTranscript(prepared, detail.kind, detail.roomId) } }
                                    } else null
                                )
                                AloTranscriptBlock(
                                    state = transcriptState,
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (contactAttachment != null) {
                                ContactMessageAttachmentCard(
                                    attachment = contactAttachment,
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor,
                                    onOpenProfile = onOpenProfile
                                )
                            } else if (locationAttachment != null) {
                                LocationMessageAttachmentCard(
                                    attachment = locationAttachment,
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor
                                )
                            }

                            if (contentTools != null && message.body.isNotBlank()) {
                                AloTranslationBlock(
                                    state = translationState,
                                    isOutgoing = isOutgoing,
                                    textColor = messageTextColor,
                                    onDismissError = { contentTools.hideTranslation(message.id) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (
                                message.channelPostId > 0 &&
                                message.channelPostCommentsEnabled &&
                                callLogMeta == null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clickable { onOpenDiscussion(message.channelPostId) }
                                        .padding(vertical = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = if (isOutgoing && !outgoingBubbleIsLight) Color.White.copy(alpha = 0.88f) else AloNativeChatAccentBlue,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = channelDiscussionLabel(message.channelPostCommentCount),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isOutgoing && !outgoingBubbleIsLight) Color.White.copy(alpha = 0.88f) else AloNativeChatAccentBlue
                                    )
                                }
                            }

                            if (message.selfDestructSeconds > 0) {
                                MessageExpiryBadge(
                                    message = preparedMediaMessage,
                                    isOutgoing = isOutgoing,
                                    modifier = Modifier.padding(
                                        top = if (isVoice || compactVisualBubble) 4.dp else 6.dp
                                    )
                                )
                            }

                            AnimatedVisibility(
                                visible = message.reactions.isNotEmpty(),
                                enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), Alignment.Top) +
                                    scaleIn(initialScale = 0.8f, transformOrigin = TransformOrigin(0f, 0.5f)),
                                exit = fadeOut(tween(140)) + shrinkVertically(tween(180), Alignment.Top)
                            ) {
                                MessageReactionStrip(
                                    modifier = Modifier.padding(
                                        top = when {
                                            reactionsCarryMeta -> AloReactionChipMetrics.TopGap
                                            isVoice || compactVisualBubble -> 5.dp
                                            else -> 7.dp
                                        },
                                        bottom = if (reactionsCarryMeta) AloReactionChipMetrics.BottomGap else 0.dp
                                    ),
                                    reactions = message.reactions,
                                    isOutgoing = isOutgoing,
                                    onReact = onReact,
                                    alwaysShowCount = detail.kind == "group",
                                    messageId = message.id,
                                    style = if (compactVisualBubble) AloReactionChipStyle.Floating else AloReactionChipStyle.Bubble,
                                    accent = if (inGroup) senderNameColor else Color.Unspecified,
                                    bubbleColor = bubbleColor,
                                    meta = if (reactionsCarryMeta) {
                                        {
                                            MessageInlineMeta(
                                                message = message,
                                                isOutgoing = isOutgoing,
                                                isPending = isPending,
                                                color = metaTextColor
                                            )
                                        }
                                    } else null
                                )
                            }

                            if (!rendersInlineMeta && !reactionsCarryMeta && callLogMeta == null && !isVoice) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(top = if (isVoice) 0.dp else 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = buildMessageMetaLabel(message.createdAt, message.editedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = metaTextColor
                                    )
                                    if (isOutgoing) {
                                        MessageStatusIcon(
                                            read = message.readAt.isNotBlank(),
                                            isPending = isPending,
                                            isScheduled = message.isScheduled,
                                            color = metaTextColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selectionMode && isOutgoing) {
            MessageSelectionMark(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 8.dp)
                    .clickable(onClick = onClick)
            )
        }
    }
}

@Composable
private fun ContactMessageAttachmentCard(
    attachment: AloOutgoingMessageAttachment,
    isOutgoing: Boolean,
    textColor: Color,
    onOpenProfile: (AloProfileTarget) -> Unit
) {
    // White ink only on the dark theme's blue own bubble; the light theme's pale one takes the incoming colours.
    val inkOnBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val metadata = attachment.metadata
    val name = metadata["displayName"].orEmpty().ifBlank { attachment.name.ifBlank { "Контакт" } }
    val username = metadata["username"].orEmpty().removePrefix("@")
    val avatarUrl = metadata["avatarUrl"].orEmpty()
    val userId = metadata["userId"]?.toIntOrNull() ?: 0
    val verified = metadata["verified"].equals("true", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(14.dp))
            .clickable(enabled = userId > 0) {
                onOpenProfile(AloProfileTarget.User(userId))
            }
            .background(if (inkOnBlue) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = AloNativeChatAccentBlue.copy(alpha = 0.82f)
        ) {
            if (avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = name.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (verified) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeChatAccentBlue
                    )
                }
            }
            Text(
                text = if (username.isBlank()) "Контакт" else "@$username",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOutgoing) textColor.copy(alpha = 0.74f) else AloNativeAppMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LocationMessageAttachmentCard(
    attachment: AloOutgoingMessageAttachment,
    isOutgoing: Boolean,
    textColor: Color
) {
    // White ink only on the dark theme's blue own bubble; the light theme's pale one takes the incoming colours.
    val inkOnBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val uriHandler = LocalUriHandler.current
    val latitude = attachment.metadata["latitude"]?.toDoubleOrNull()
    val longitude = attachment.metadata["longitude"]?.toDoubleOrNull()
    val title = attachment.metadata["title"].orEmpty().ifBlank {
        attachment.name.ifBlank { "Геолокация" }
    }
    val address = attachment.metadata["address"].orEmpty()
    val livePeriodSeconds = attachment.metadata["livePeriodSeconds"]?.toIntOrNull() ?: 0
    val liveStartedAt = attachment.metadata["liveStartedAt"]
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val liveRemainingMinutes = if (livePeriodSeconds > 0 && liveStartedAt != null) {
        ((liveStartedAt + livePeriodSeconds * 1_000L - System.currentTimeMillis()) / 60_000L)
            .coerceAtLeast(0L)
    } else {
        0L
    }
    val coordinateLabel = when {
        livePeriodSeconds > 0 && liveRemainingMinutes > 0 -> "Живая геопозиция · ещё $liveRemainingMinutes мин"
        livePeriodSeconds > 0 -> "Трансляция завершена"
        address.isNotBlank() -> address
        latitude != null && longitude != null ->
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        else -> "Координаты недоступны"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(14.dp))
            .clickable(enabled = latitude != null && longitude != null) {
                uriHandler.openUri("https://maps.google.com/?q=$latitude,$longitude")
            }
            .background(if (inkOnBlue) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = if (inkOnBlue) Color.White.copy(alpha = 0.15f) else AloNativeChatAccentBlue.copy(alpha = 0.82f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = if (isOutgoing) Color.White else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = coordinateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (isOutgoing) textColor.copy(alpha = 0.74f) else AloNativeAppMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PollMessageContent(
    state: AloPollViewState,
    message: AloChatMessage,
    isOutgoing: Boolean,
    isPending: Boolean,
    onVote: (String) -> Unit,
    canClosePoll: Boolean,
    onClosePoll: () -> Unit
) {
    // White ink only on the dark theme's blue own bubble; the light theme's pale one takes the incoming colours.
    val inkOnBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val foreground = if (inkOnBlue) Color.White else AloNativeAppText
    val secondary = if (inkOnBlue) Color.White.copy(alpha = 0.7f) else AloNativeAppMuted
    val votingEnabled = !state.isClosed &&
        (state.allowsRevoting || state.selectedOptionIds.isEmpty())
    val showsResults = state.isClosed || state.selectedOptionIds.isNotEmpty()

    Column(
        modifier = Modifier.width(270.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (state.definition.isQuiz) "Викторина" else "Опрос",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = secondary
        )
        Text(
            text = state.definition.question,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = foreground
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.definition.options.forEach { option ->
                val selected = state.selectedOptionIds.contains(option.id)
                val count = state.voteCounts[option.id] ?: 0
                val fraction = if (state.totalVoters > 0) {
                    count.toFloat() / state.totalVoters.toFloat()
                } else {
                    0f
                }
                val animatedFraction by animateFloatAsState(
                    targetValue = if (showsResults) fraction.coerceIn(0f, 1f) else 0f,
                    animationSpec = spring(
                        dampingRatio = 1f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "pollOptionProgress_${state.definition.id}_${option.id}"
                )
                val isCorrect = state.definition.isQuiz && state.isClosed &&
                    state.correctOptionIds.contains(option.id)
                val isIncorrect = state.definition.isQuiz && state.isClosed && selected && !isCorrect
                val accent = when {
                    isCorrect -> AloNativeAppSuccess
                    isIncorrect -> Color(0xFFFF5A5F)
                    else -> foreground
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(AloSquircle(10.dp))
                        .background(if (showsResults) foreground.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(enabled = votingEnabled) { onVote(option.id) }
                ) {
                    if (showsResults && animatedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(
                                    accent.copy(alpha = if (selected || isCorrect) 0.28f else 0.14f)
                                )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val indicator = when {
                            isIncorrect -> "✕"
                            selected || isCorrect -> "✓"
                            else -> ""
                        }
                        Surface(
                            modifier = Modifier.size(20.dp),
                            shape = CircleShape,
                            color = if (indicator.isNotEmpty()) accent else Color.Transparent,
                            border = BorderStroke(
                                1.5.dp,
                                if (indicator.isNotEmpty()) accent else secondary
                            )
                        ) {
                            if (indicator.isNotEmpty()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = indicator,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Black
                                        ),
                                        color = if (isOutgoing) AloNativeChatOutgoing else AloNativeAppSurface
                                    )
                                }
                            }
                        }
                        Text(
                            text = option.text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (showsResults) {
                            Text(
                                text = "${(fraction * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = secondary
                            )
                        }
                    }
                }
            }
        }
        if (state.definition.isQuiz && state.definition.explanation.isNotBlank() &&
            showsResults
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AloSquircle(10.dp),
                color = foreground.copy(alpha = 0.12f)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Пояснение",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = secondary
                    )
                    Text(
                        text = state.definition.explanation,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = foreground
                    )
                }
            }
        }
        if (showsResults && state.publicVoters.isNotEmpty()) {
            val names = state.publicVoters.take(3).map(AloPollPublicVoter::name)
            val remaining = (state.totalVoters - names.size).coerceAtLeast(0)
            Text(
                text = "Ответили: ${names.joinToString(", ")}" +
                    if (remaining > 0) " и ещё $remaining" else "",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (state.isClosed) {
            Text(
                text = "Опрос завершён",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = secondary
            )
        } else {
            pollCloseLabel(state.closesAt)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = secondary
                )
            }
            if (canClosePoll) {
                TextButton(
                    onClick = onClosePoll,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = "Завершить",
                        color = foreground,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = if (state.isClosed) {
                    "Опрос завершён · ${state.totalVoters} ${pollVoteCountLabel(state.totalVoters)}"
                } else {
                    "${state.totalVoters} ${pollVoteCountLabel(state.totalVoters)}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = secondary
            )
            if (!state.isClosed && state.definition.allowsMultipleAnswers) {
                Text(
                    text = "Несколько ответов",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = secondary
                )
            }
            MessageInlineMeta(
                message = message,
                isOutgoing = isOutgoing,
                isPending = isPending,
                color = secondary
            )
        }
    }
}

private fun pollVoteCountLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "голосов"
        mod10 == 1 -> "голос"
        mod10 in 2..4 -> "голоса"
        else -> "голосов"
    }
}

private fun pollCloseLabel(closesAt: String): String? {
    if (closesAt.isBlank()) return null
    val date = runCatching { Date.from(Instant.parse(closesAt)) }.getOrNull() ?: return null
    val formatter = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
        Locale("ru", "RU")
    )
    return "Завершится ${formatter.format(date)}"
}

/** A group sender's name row (Telegram's `nameLayout` and `adminLayout`): the name at 14sp, the role tag at the right end. */
@Composable
private fun GroupSenderNameRow(
    name: String,
    roleLabel: String,
    color: Color,
    roleAtRightEnd: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = if (roleAtRightEnd) modifier.fillMaxWidth() else modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier
                .weight(1f, fill = roleAtRightEnd)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            style = TextStyle(
                fontSize = AloGroupMessageMetrics.NameSize,
                lineHeight = AloGroupMessageMetrics.NameLineHeight,
                fontWeight = FontWeight.Medium
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (roleLabel.isNotBlank()) {
            Text(
                text = roleLabel,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f))
                    .padding(horizontal = AloGroupMessageMetrics.RoleHorizontalPadding, vertical = 1.dp),
                style = TextStyle(
                    fontSize = AloGroupMessageMetrics.RoleSize,
                    lineHeight = AloGroupMessageMetrics.RoleLineHeight,
                    fontWeight = FontWeight.Medium
                ),
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReplyQuote(
    source: AloChatMessage?,
    isOutgoing: Boolean,
    authorColor: Color,
    /** A sender's name sits above the quote (a group): its far corners are small, as at the bar. */
    nameAboveQuote: Boolean,
    onClick: (() -> Unit)? = null
) {
    // White ink only on the dark theme's blue own bubble; the light theme's pale one takes the incoming colours.
    val inkOnBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val title = source?.sender?.name?.ifBlank { "Сообщение" } ?: "Ответ на сообщение"
    val preview = source?.let(::buildConversationPreview) ?: "Исходное сообщение недоступно"
    // Our palette: the author's colour on a received bubble; on a sent (blue) one the bar and name stay light.
    val barColor = if (inkOnBlue) Color.White.copy(alpha = 0.86f) else authorColor
    val nameColor = if (inkOnBlue) Color.White.copy(alpha = 0.92f) else authorColor
    val farCorner = if (nameAboveQuote) AloReplyQuoteMetrics.Corner else AloReplyQuoteMetrics.FarCornerWithoutName
    val textStyle = TextStyle(fontSize = AloReplyQuoteMetrics.TextSize, lineHeight = AloReplyQuoteMetrics.LineHeight)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (nameAboveQuote) 0.dp else AloReplyQuoteMetrics.TopWhenFirst,
                bottom = AloReplyQuoteMetrics.GapBelow
            )
            .height(IntrinsicSize.Min)
            .clip(
                RoundedCornerShape(
                    topStart = AloReplyQuoteMetrics.Corner,
                    bottomStart = AloReplyQuoteMetrics.Corner,
                    topEnd = farCorner,
                    bottomEnd = farCorner
                )
            )
            .background(if (inkOnBlue) Color.White.copy(alpha = 0.13f) else authorColor.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .width(AloReplyQuoteMetrics.BarWidth)
                .fillMaxHeight()
                .background(barColor)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = AloReplyQuoteMetrics.TextStart,
                    end = AloReplyQuoteMetrics.TextEnd,
                    top = AloReplyQuoteMetrics.NameTop,
                    bottom = AloReplyQuoteMetrics.BottomPadding
                )
        ) {
            Text(
                text = title,
                style = textStyle.copy(fontWeight = FontWeight.Medium),
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                modifier = Modifier.padding(top = AloReplyQuoteMetrics.TextTopGap),
                style = textStyle,
                color = if (inkOnBlue) Color.White.copy(alpha = 0.72f) else AloNativeAppMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExpiringMediaPlaceholder(
    itemCount: Int,
    selfDestructSeconds: Int,
    /** Not null: the media is only waiting to be downloaded (its size, 0 when unknown), not to be opened once. */
    downloadBytes: Long? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(AloSquircle(16.dp))
            .border(0.85.dp, Color.White.copy(alpha = 0.12f), AloSquircle(16.dp))
            .background(Color(0xFF171A20)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (downloadBytes != null) Icons.Rounded.Download else Icons.Rounded.Timer,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Text(
                text = if (downloadBytes != null) {
                    if (downloadBytes > 0L) "Скачать · ${aloFileSize(downloadBytes)}" else "Скачать"
                } else if (selfDestructSeconds == 1) {
                    "Открыть один раз"
                } else {
                    "Открыть на ${formatExpiryDuration(selfDestructSeconds)}"
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            if (itemCount > 1) {
                Text(
                    text = "$itemCount медиа",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
        }
    }
}

@Composable
private fun MessageExpiryBadge(
    message: AloChatMessage,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    // White ink only on a dark bubble (the dark theme's blue own one): on a light one it could not be read.
    val inkOnBlue = isOutgoing && AloNativeChatOutgoing.luminance() <= 0.5f
    val bubbleIsDark = (if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming).luminance() <= 0.5f
    val badgeInk = if (bubbleIsDark) Color.White.copy(alpha = 0.82f) else AloNativeAppText.copy(alpha = 0.72f)
    var nowMillis by remember(message.id, message.expiresAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(message.id, message.expiresAt) {
        if (message.expiresAt.isBlank()) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            val expiryMillis = runCatching {
                Instant.parse(message.expiresAt).toEpochMilli()
            }.getOrNull() ?: return@LaunchedEffect
            if (expiryMillis <= nowMillis) return@LaunchedEffect
            delay(250)
        }
    }
    val remainingSeconds = remember(message.expiresAt, nowMillis) {
        val expiryMillis = runCatching {
            Instant.parse(message.expiresAt).toEpochMilli()
        }.getOrNull()
        expiryMillis?.let {
            ceil(((it - nowMillis).coerceAtLeast(0L)) / 1000.0).toInt()
        }
    }
    val label = when {
        remainingSeconds != null -> formatExpiryDuration(remainingSeconds)
        message.selfDestructSeconds == 1 -> "Один просмотр"
        else -> formatExpiryDuration(message.selfDestructSeconds)
    }
    Row(
        modifier = modifier
            .clip(AloSquircle(10.dp))
            .background(
                if (inkOnBlue) Color.White.copy(alpha = 0.13f)
                else Color.Black.copy(alpha = 0.08f)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Timer,
            contentDescription = null,
            tint = badgeInk,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = badgeInk
        )
    }
}

private fun formatExpiryDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return when {
        safe >= 86_400 && safe % 86_400 == 0 -> "${safe / 86_400} д."
        safe >= 3_600 && safe % 3_600 == 0 -> "${safe / 3_600} ч."
        safe >= 60 && safe % 60 == 0 -> "${safe / 60} мин."
        else -> "$safe с"
    }
}

@Composable
private fun AloRichMessageText(
    message: AloChatMessage,
    color: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var revealsSpoilers by remember(message.id, message.body) { mutableStateOf(false) }
    val source = remember(message.body, message.entities) {
        if (message.entities.isEmpty()) AloMessageMarkup.parse(message.body)
        else AloMessageMarkupResult(message.body, message.entities)
    }
    val value = remember(source, revealsSpoilers, color) {
        AnnotatedString.Builder(source.body).apply {
            source.entities.forEach { entity ->
                val start = entity.offset
                val end = entity.offset + entity.length
                if (start < 0 || end > source.body.length || start >= end) return@forEach
                val style = when (entity.type) {
                    "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    // Shades of the text's own colour, not of white: on the light theme's white bubble a white
                    // background was invisible, so neither inline code nor a hidden spoiler could be seen.
                    "code" -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = color.copy(alpha = 0.13f)
                    )
                    "pre" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    "link", "mention" -> SpanStyle(
                        color = color,
                        textDecoration = TextDecoration.Underline
                    )
                    "spoiler" -> if (revealsSpoilers) {
                        SpanStyle(color = color)
                    } else {
                        SpanStyle(
                            color = Color.Transparent,
                            background = color.copy(alpha = 0.28f)
                        )
                    }
                    else -> null
                }
                if (style != null) addStyle(style, start, end)
                if ((entity.type == "link" || entity.type == "mention") && entity.url.isNotBlank()) {
                    addStringAnnotation("ALO_URL", entity.url, start, end)
                }
            }
        }.toAnnotatedString()
    }
    // Links and spoilers answer a tap; a long press anywhere on the text still opens the bubble's
    // menu. ClickableText consumed the press, so the menu only opened from the bubble's margins.
    val hasSpoilers = source.entities.any { it.type == "spoiler" }
    fun clickableText(text: AnnotatedString, textModifier: Modifier, style: TextStyle) = @Composable {
        val hasLinks = text.getStringAnnotations("ALO_URL", 0, text.length).isNotEmpty()
        if (!hasLinks && !(hasSpoilers && !revealsSpoilers)) {
            Text(text, modifier = textModifier, style = style)
        } else {
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = text,
                style = style,
                onTextLayout = { layout = it },
                modifier = textModifier.pointerInput(text) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        } ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val offset = layout?.getOffsetForPosition(up.position) ?: return@awaitEachGesture
                        val url = text.getStringAnnotations("ALO_URL", offset, offset).firstOrNull()?.item
                        if (!url.isNullOrBlank()) {
                            up.consume()
                            openAloOrExternalLink(context, uriHandler, url)
                        } else if (hasSpoilers && !revealsSpoilers) {
                            up.consume()
                            revealsSpoilers = true
                        }
                    }
                }
            )
        }
    }
    // Telegram's 16sp message text at its natural line height (Material's bodyLarge would add 24sp lines).
    val baseStyle = MaterialTheme.typography.bodyLarge.copy(color = color, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.sp)
    val blocks = remember(source) { aloMessageBlocks(source.body, source.entities) }
    if (blocks == null) {
        clickableText(value, modifier, baseStyle)()
        return
    }
    val clipboard = LocalClipboardManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            val slice = value.subSequence(block.start, block.end)
            when (block.kind) {
                AloMessageBlockKind.Plain -> clickableText(slice, Modifier, baseStyle)()
                // Telegram's quote: a tinted block with a bar on the left and a quote mark.
                AloMessageBlockKind.Quote -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(AloSquircle(6.dp))
                        .background(color.copy(alpha = 0.12f))
                        .drawBehind { drawRect(color.copy(alpha = 0.9f), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                ) {
                    clickableText(slice, Modifier.padding(start = 11.dp, end = 22.dp, top = 5.dp, bottom = 5.dp), baseStyle)()
                    Text("”", color = color.copy(alpha = 0.75f), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp))
                }
                AloMessageBlockKind.Code -> {
                    var menuOpen by remember(block.start) { mutableStateOf(false) }
                    Box {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(AloSquircle(8.dp))
                                .background(Color.Black.copy(alpha = 0.24f))
                                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (block.language.isNotBlank()) {
                                Text(block.language, color = color.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                slice,
                                style = baseStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                        AloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Скопировать код") }, onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(slice.text))
                            })
                        }
                    }
                }
            }
        }
    }
}

internal enum class AloMessageBlockKind { Plain, Quote, Code }

internal data class AloMessageBlock(val kind: AloMessageBlockKind, val start: Int, val end: Int, val language: String = "")

internal fun aloMessageHasBlocks(message: AloChatMessage): Boolean {
    val entities = if (message.entities.isEmpty()) AloMessageMarkup.parse(message.body).entities else message.entities
    return entities.any { it.type == "pre" || it.type == "blockquote" }
}

/** The message cut into plain runs, quotes and code blocks; null when it has neither (iOS's AloRichMessageText.blocks). */
internal fun aloMessageBlocks(text: String, entities: List<AloMessageTextEntity>): List<AloMessageBlock>? {
    val blockEntities = entities.filter { it.type == "blockquote" || it.type == "pre" }.sortedBy { it.offset }
    if (blockEntities.isEmpty()) return null
    val result = mutableListOf<AloMessageBlock>()
    var cursor = 0
    fun appendPlain(from: Int, to: Int) {
        var start = from
        var end = to
        // Newlines around a block are its own spacing.
        while (start < end && text[start] == '\n') start++
        while (end > start && text[end - 1] == '\n') end--
        if (end > start) result += AloMessageBlock(AloMessageBlockKind.Plain, start, end)
    }
    blockEntities.forEach { entity ->
        if (entity.offset < cursor || entity.offset + entity.length > text.length) return@forEach
        appendPlain(cursor, entity.offset)
        result += AloMessageBlock(
            if (entity.type == "pre") AloMessageBlockKind.Code else AloMessageBlockKind.Quote,
            entity.offset, entity.offset + entity.length, entity.url
        )
        cursor = entity.offset + entity.length
    }
    appendPlain(cursor, text.length)
    return result.ifEmpty { null }
}

@Composable
private fun MessageInlineMeta(
    message: AloChatMessage,
    isOutgoing: Boolean,
    isPending: Boolean,
    color: Color
) {
    Row(
        modifier = Modifier.offset(y = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = buildMessageMetaLabel(message.createdAt, message.editedAt),
            // Telegram's 12sp time, without Material's 0.5sp letter spacing.
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
            color = color
        )
        if (isOutgoing) {
            MessageStatusIcon(
                read = message.readAt.isNotBlank(),
                isPending = isPending,
                isScheduled = message.isScheduled,
                color = color
            )
        }
    }
}

@Composable
private fun MessageStatusIcon(
    read: Boolean,
    isPending: Boolean,
    isScheduled: Boolean,
    color: Color
) {
    if (isScheduled) {
        Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = "Отложено",
            tint = color,
            modifier = Modifier
                .size(15.dp)
                .offset(y = 1.dp)
        )
        return
    }
    if (isPending) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(12.dp)
                .offset(y = 1.dp),
            strokeWidth = 1.45.dp,
            color = color.copy(alpha = 0.82f)
        )
        return
    }
    Icon(
        imageVector = if (read) Icons.Rounded.DoneAll else Icons.Rounded.Done,
        contentDescription = null,
        tint = color,
        modifier = Modifier
            .size(if (read) 17.dp else 15.dp)
            .offset(y = 1.dp)
    )
}

@Composable
private fun MessageReactionBadge(
    reactions: List<AloMessageReaction>,
    isOutgoing: Boolean,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    messageId: Int = 0
) {
    if (reactions.isEmpty()) return
    MessageReactionStrip(
        reactions = reactions,
        isOutgoing = isOutgoing,
        onReact = onReact,
        messageId = messageId,
        style = AloReactionChipStyle.Floating,
        modifier = modifier.offset(
            x = if (isOutgoing) (-5).dp else 5.dp,
            y = 12.dp
        )
    )
}

private fun channelDiscussionLabel(count: Int): String {
    if (count <= 0) return "Обсудить"
    val word = when {
        count % 100 in 11..14 -> "комментариев"
        count % 10 == 1 -> "комментарий"
        count % 10 in 2..4 -> "комментария"
        else -> "комментариев"
    }
    return "$count $word"
}


/** The reaction a double tap on a message sends: the account's favorite, provided by the conversation screen. */
internal val LocalAloFavoriteReaction = staticCompositionLocalOf { "❤️" }
