package com.alo.android

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MessageComposer(
    modifier: Modifier = Modifier,
    value: String,
    recordMode: AloChatRecordMode,
    captureState: AloChatCaptureState,
    recordingDurationSec: Int,
    recordingLevels: List<Float>,
    draftAttachment: AloChatDraftAttachment?,
    draftAttachments: List<AloChatDraftAttachment>,
    selfDestructSeconds: Int,
    forwardDraft: List<AloMessageForwardSource>,
    editingMessage: AloChatMessage?,
    replyMessage: AloChatMessage?,
    mentionMembers: List<AloGroupMember>,
    isSending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendSilently: () -> Unit,
    canSendWhenOnline: Boolean,
    onSendWhenOnline: () -> Unit,
    onScheduleSend: (String) -> Unit,
    onScheduleRepeat: (AloMessageRepeatSchedule) -> Unit,
    onPickAttachment: () -> Unit,
    onToggleRecorderMode: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (Boolean) -> Unit,
    /** Stops a voice recording and sends it at once: Telegram's release of a held mic. */
    onSendRecording: () -> Unit = {},
    /** The finger is on the mic of a recording (Telegram: a circle at its minute is then sent, not previewed). */
    onRecordingHeldChange: (Boolean) -> Unit = {},
    onDiscardDraft: () -> Unit,
    onRemoveDraftAttachment: (String) -> Unit,
    onSelfDestructSecondsChange: (Int) -> Unit,
    onOpenDraftMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onSendDraft: () -> Unit,
    onDiscardForwardDraft: () -> Unit,
    onCancelEditing: () -> Unit,
    onCancelReply: () -> Unit,
    onChangeForwardTarget: () -> Unit,
    /** Telegram's emoji button: opens the panel that takes the keyboard's place, and is a keyboard key while it is open. */
    expressionPanelOpen: Boolean,
    onToggleExpressionPanel: () -> Unit,
    /** The field was tapped while the panel was open: the panel gives way to the keyboard. */
    onFieldTapped: () -> Unit
) {
    var sendOptionsExpanded by remember { mutableStateOf(false) }
    val attachmentInteraction = remember { MutableInteractionSource() }
    val fieldInteraction = remember { MutableInteractionSource() }
    val actionInteraction = remember { MutableInteractionSource() }
    val attachmentPressed by attachmentInteraction.collectIsPressedAsState()
    val fieldPressed by fieldInteraction.collectIsPressedAsState()
    val actionPressed by actionInteraction.collectIsPressedAsState()
    val attachmentScale by animateFloatAsState(
        targetValue = if (attachmentPressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "composerAttachmentPress"
    )
    val currentOnFieldTapped by rememberUpdatedState(onFieldTapped)
    LaunchedEffect(fieldPressed, expressionPanelOpen) {
        if (fieldPressed && expressionPanelOpen) currentOnFieldTapped()
    }
    val fieldScale by animateFloatAsState(
        targetValue = if (fieldPressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.66f, stiffness = Spring.StiffnessMedium),
        label = "composerFieldPress"
    )
    val actionScale by animateFloatAsState(
        targetValue = if (actionPressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMedium),
        label = "composerActionPress"
    )
    val hasText = value.isNotBlank()
    val isEditing = editingMessage != null
    val canSubmitEdit = isEditing && (hasText || editingMessage?.attachments?.isNotEmpty() == true)
    val hasForwardDraft = forwardDraft.isNotEmpty()
    // Debug-only screenshot fixture of the voice states (`--es alo_ui_voice held|slide|lift|locked|preview`).
    val voiceFixture = rememberAloVoiceRecordingFixture()
    val recordMode = if (voiceFixture != null) AloChatRecordMode.Voice else recordMode
    val isRecording = captureState == AloChatCaptureState.Recording || (voiceFixture != null && voiceFixture != "preview")
    val recordingLevels = if (voiceFixture != null && recordingLevels.isEmpty()) AloVoiceFixtureLevels else recordingLevels
    val fixtureContext = LocalContext.current
    val draftAttachment = draftAttachment ?: if (voiceFixture == "preview") remember {
        AloChatDraftAttachment(
            localUri = AloDesignFixture.sampleVoiceUri(fixtureContext), attachmentType = "voice",
            mimeType = "audio/wav", name = "voice.wav", durationSec = 7, waveform = AloVoiceFixtureLevels
        )
    } else null
    // When the recording began, for Telegram's «0:03,45» timer.
    var recordingStartedAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (isRecording) recordingStartedAtMs = System.currentTimeMillis() - if (voiceFixture != null) 3_420L else 0L
    }
    // Telegram Android records a round video with the same bar, lock and circle as a voice message.
    val voiceRecording = isRecording
    val circleRecording = isRecording && recordMode == AloChatRecordMode.VideoCircle
    // Telegram's held mic (AloRecordingGesture): where the holding finger has gone, whether it is
    // still down, and whether the recording was locked so it could let go.
    var recordDrag by remember { mutableStateOf(AloRecordingDrag()) }
    var recordingHeld by remember { mutableStateOf(false) }
    var recordingLocked by remember { mutableStateOf(false) }
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            recordingHeld = false
            recordingLocked = false
            recordDrag = AloRecordingDrag()
        }
    }
    LaunchedEffect(voiceFixture) {
        when (voiceFixture) {
            "held" -> recordingHeld = true
            "slide" -> { recordingHeld = true; recordDrag = AloRecordingDrag(dx = -60f) }
            "lift" -> { recordingHeld = true; recordDrag = AloRecordingDrag(dy = -34f) }
            "locked" -> recordingLocked = true
        }
    }
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    val hasMultiAttachmentPreview = draftAttachments.isNotEmpty()
    val hasRecordingPreview = draftAttachment != null || hasMultiAttachmentPreview
    val previewDurationSec = draftAttachment?.durationSec ?: 0
    val previewType = draftAttachment?.attachmentType.orEmpty()
    val isVoicePreview = previewType == "voice"
    val isCirclePreview = draftAttachment?.isVideoCircle == true
    val hasCaptionableAttachment = hasMultiAttachmentPreview ||
        (draftAttachment != null && !isVoicePreview && !isCirclePreview)
    val estimatedComposerLines = value
        .split('\n')
        .sumOf { line -> maxOf(1, (line.length + 33) / 34) }
        .coerceIn(1, AloTelegramMetrics.Composer.MaxLines)
    val composerControlSize = 44.dp
    // Telegram's input field (ChatActivityEnterView): 44dp, growing by a 22dp line up to six lines.
    val textComposerHeight = (AloTelegramMetrics.Composer.Height.value + (estimatedComposerLines - 1) * 22).dp
    val usesCompactComposerCorners = estimatedComposerLines > 1
    val actionIcon = when {
        hasText || hasRecordingPreview || hasForwardDraft || canSubmitEdit -> Icons.AutoMirrored.Rounded.Send
        recordMode == AloChatRecordMode.Voice -> Icons.Rounded.Mic
        else -> Icons.Rounded.Videocam
    }
    val glassTone = AloNativeChatGlassSurface
    // With nothing to send, the button is Telegram's mic: tap to switch, hold to record.
    val micGesture = !isSending && !hasText && !hasRecordingPreview && !hasForwardDraft && !canSubmitEdit && !isEditing
    val haptics = LocalHapticFeedback.current
    val heldMic = rememberUpdatedState(
        AloHeldMicController(
            isRecording = isRecording,
            isLocked = recordingLocked,
            isVoice = recordMode == AloChatRecordMode.Voice,
            onTap = onToggleRecorderMode,
            onStart = onStartRecording,
            onCancel = { onStopRecording(true) },
            onSend = onSendRecording,
            onStopToPreview = { onStopRecording(false) },
            onDrag = { recordDrag = it },
            onHeld = {
                recordingHeld = it
                onRecordingHeldChange(it)
            },
            onLock = { recordingLocked = true },
            haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            cancelDistance = AloRecordingDrag.cancelDistanceFor(screenWidthDp)
        )
    )
    val buttonColor by animateColorAsState(
        targetValue = when {
            (hasText || hasRecordingPreview || hasForwardDraft || canSubmitEdit) && !isSending -> AloNativeChatSendAccent
            isRecording && recordingLocked -> AloNativeChatSendAccent
            isRecording -> Color(0xFFFF5D73)
            else -> glassTone
        },
        animationSpec = tween(durationMillis = 180),
        label = "composerActionColor"
    )
    val pulse = rememberInfiniteTransition(label = "recordPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordPulseScale"
    )
    val displayedDurationSec = if (hasRecordingPreview) previewDurationSec else recordingDurationSec
    val durationLabel = "%d:%02d".format(
        displayedDurationSec / 60,
        displayedDurationSec % 60
    )
    val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = AloNativeAppText,
        fontSize = AloTelegramMetrics.Composer.TextSize,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val mentionQuery = remember(value) { activeMentionQuery(value) }
    val mentionCandidates = remember(mentionQuery, mentionMembers) {
        mentionCandidates(mentionMembers, mentionQuery)
    }

    // Where a sent message lifts out from, and where others rise from behind (AloMessageEnterTransition).
    val messageEnterState = LocalAloMessageEnterState.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { messageEnterState?.composerTop = it.positionInWindow().y }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (editingMessage != null) {
            EditingMessagePreview(
                message = editingMessage,
                onCancel = onCancelEditing
            )
        }

        if (replyMessage != null && editingMessage == null) {
            ReplyMessagePreview(
                message = replyMessage,
                onCancel = onCancelReply
            )
        }

        if (mentionCandidates.isNotEmpty()) {
            MentionSuggestions(
                members = mentionCandidates,
                onSelect = { member ->
                    onValueChange(replaceActiveMention(value, member.user.username))
                }
            )
        }

        if (hasForwardDraft) {
            ForwardDraftPreview(
                forwardDraft = forwardDraft,
                onRemoveForward = onDiscardForwardDraft,
                onChangeTarget = onChangeForwardTarget
            )
        }

        if (hasRecordingPreview && !isEditing && !hasForwardDraft) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                MessageSelfDestructMenu(
                    seconds = selfDestructSeconds,
                    onChange = onSelfDestructSecondsChange
                )
            }
        }

        if (hasMultiAttachmentPreview) {
            DraftMediaPreviewPanel(
                attachments = draftAttachments,
                onAdd = onPickAttachment,
                onOpen = { index ->
                    onOpenDraftMediaGallery(draftAttachments.map(AloChatDraftAttachment::toDraftMediaItem), index)
                },
                onRemove = { onRemoveDraftAttachment(it.localUri) }
            )
        }

        if (hasCaptionableAttachment) {
            AloNativeGlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp),
                shape = AloSquircle(17.dp),
                tint = glassTone,
                borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(textComposerHeight.coerceAtMost(88.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    textStyle = composerTextStyle,
                    cursorBrush = SolidColor(AloNativeAppAccentStrong),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (value.isBlank()) {
                                Text(
                                    text = "Добавить подпись",
                                    style = composerTextStyle,
                                    color = AloNativeAppMuted
                                )
                            }
                            Box(modifier = Modifier.padding(end = if (value.isBlank()) 0.dp else 34.dp)) {
                                innerTextField()
                            }
                            if (value.isNotBlank()) {
                                MessageFormattingMenu(
                                    value = value,
                                    onValueChange = onValueChange,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val leadingIcon = when {
                isVoicePreview && !isEditing -> Icons.Rounded.Delete
                isEditing || hasRecordingPreview || isRecording -> Icons.Rounded.Close
                else -> Icons.Rounded.AttachFile
            }
            // Telegram: while a voice message records, the record panel takes the whole row up to the mic.
            androidx.compose.animation.AnimatedVisibility(
                visible = !voiceRecording,
                enter = fadeIn(tween(180)) + androidx.compose.animation.expandHorizontally(tween(220)),
                exit = fadeOut(tween(140)) + androidx.compose.animation.shrinkHorizontally(tween(220))
            ) {
            AloNativeGlassPanel(
                modifier = Modifier.scale(attachmentScale),
                shape = CircleShape,
                tint = glassTone,
                borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
            ) {
                Box(
                    modifier = Modifier
                        .size(composerControlSize)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = attachmentInteraction,
                            indication = null
                        ) {
                            when {
                                isEditing -> onCancelEditing()
                                hasRecordingPreview -> onDiscardDraft()
                                isRecording -> onStopRecording(true)
                                else -> onPickAttachment()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        // TalkBack reads what the button does now (it was a nameless button).
                        contentDescription = when {
                            isEditing -> "Отменить изменение"
                            hasRecordingPreview -> "Удалить запись"
                            isRecording -> "Отменить запись"
                            else -> "Прикрепить"
                        },
                        tint = AloNativeAppMuted,
                        modifier = Modifier.size(if (leadingIcon == Icons.Rounded.Close) 19.dp else 22.dp)
                    )
                }
            }
            }

            AloNativeGlassPanel(
                modifier = Modifier
                    .weight(1f)
                    .scale(fieldScale),
                shape = RoundedCornerShape(
                    if (!hasRecordingPreview && usesCompactComposerCorners) 16.dp else 22.dp
                ),
                tint = glassTone,
                borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
            ) {
                AnimatedContent(
                    modifier = Modifier.fillMaxWidth(),
                    targetState = when {
                        isRecording -> "voice_record"
                        hasMultiAttachmentPreview -> "gallery_preview"
                        isVoicePreview -> "voice_preview"
                        isCirclePreview -> "circle_preview"
                        hasRecordingPreview -> "attachment_preview"
                        else -> "text"
                    },
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) + slideInHorizontally(
                            animationSpec = tween(180),
                            initialOffsetX = { it / 5 }
                        )).togetherWith(
                            fadeOut(animationSpec = tween(140)) + slideOutHorizontally(
                                animationSpec = tween(140),
                                targetOffsetX = { -it / 6 }
                            )
                        )
                    },
                    label = "composerContent"
                ) { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (mode == "text") Modifier.height(textComposerHeight)
                                else Modifier.height(48.dp)
                            )
                            .padding(
                                start = if (mode == "voice_preview") 6.dp else 14.dp,
                                end = if (mode == "voice_preview") 6.dp else 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (mode) {
                            "voice_record" -> {
                                AloVoiceRecordingPanel(
                                    startedAtMs = recordingStartedAtMs,
                                    isLocked = recordingLocked,
                                    drag = recordDrag,
                                    onCancel = { onStopRecording(true) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            "voice_preview" -> {
                                AloVoiceDraftPreview(
                                    uri = draftAttachment?.localUri.orEmpty(),
                                    levels = draftAttachment?.waveform.orEmpty().ifEmpty { recordingLevels },
                                    durationSec = displayedDurationSec,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            "circle_preview" -> {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RecordingCircleIndicator(
                                        pulseScale = 1f,
                                        isPreview = true,
                                        previewUri = (draftAttachment?.previewUri ?: "").ifBlank {
                                            draftAttachment?.localUri.orEmpty()
                                        }
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Видео-кружок",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = AloNativeAppText
                                        )
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 6.dp)
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x18FFFFFF))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.58f)
                                                    .height(3.dp)
                                                    .clip(CircleShape)
                                                    .background(AloNativeAppText.copy(alpha = 0.72f))
                                            )
                                        }
                                    }
                                    Text(
                                        text = durationLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AloNativeAppMuted
                                    )
                                }
                            }

                            "gallery_preview" -> {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        draftAttachments.take(3).forEachIndexed { index, item ->
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(AloSquircle(12.dp))
                                                    .background(AloNativeAppBackgroundSecondary)
                                            ) {
                                                AsyncImage(
                                                    model = item.previewUri.ifBlank { item.localUri },
                                                    contentDescription = item.name,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                                if (index == 2 && draftAttachments.size > 3) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.42f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "+${draftAttachments.size - 3}",
                                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                            color = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (draftAttachments.size == 1) "1 вложение" else "${draftAttachments.size} вложения",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = AloNativeAppText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Фото и видео отправятся одним сообщением",
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            "attachment_preview" -> {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(32.dp),
                                        shape = AloSquircle(12.dp),
                                        color = AloNativeAppBackgroundSecondary
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = when {
                                                    previewType == "image" -> Icons.Rounded.Add
                                                    previewType == "video" -> Icons.Rounded.Videocam
                                                    else -> Icons.Rounded.AttachFile
                                                },
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = draftAttachment?.name?.ifBlank { "Вложение" } ?: "Вложение",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = AloNativeAppText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = when (previewType) {
                                                "image" -> "Изображение"
                                                "video" -> "Видео"
                                                else -> "Файл"
                                            },
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AloNativeAppMuted
                                        )
                                    }
                                }
                            }

                            else -> {
                                BasicTextField(
                                    value = value,
                                    onValueChange = onValueChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(textComposerHeight),
                                    textStyle = composerTextStyle,
                                    cursorBrush = SolidColor(AloNativeAppAccentStrong),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                                    maxLines = AloTelegramMetrics.Composer.MaxLines,
                                    interactionSource = fieldInteraction,
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (value.isBlank()) {
                                                Text(
                                                    text = if (isEditing) "Изменить сообщение" else "Сообщение",
                                                    style = composerTextStyle,
                                                    color = AloNativeAppMuted
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(
                                                        end = if (value.isBlank()) 36.dp else 68.dp,
                                                        bottom = 1.dp
                                                    )
                                                    .onGloballyPositioned { messageEnterState?.composerText = it.positionInWindow() },
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                innerTextField()
                                            }
                                            if (value.isNotBlank()) {
                                                MessageFormattingMenu(
                                                    value = value,
                                                    onValueChange = onValueChange,
                                                    modifier = Modifier
                                                        .align(Alignment.CenterEnd)
                                                        .padding(end = 34.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = onToggleExpressionPanel,
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .size(34.dp)
                                            ) {
                                                // iOS's face.smiling: an outlined smiley at the size of the other controls.
                                                Icon(
                                                    imageVector = if (expressionPanelOpen) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                                                    contentDescription = if (expressionPanelOpen) "Клавиатура" else "Эмодзи и стикеры",
                                                    tint = AloNativeAppMuted,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.size(composerControlSize),
                contentAlignment = Alignment.Center
            ) {
                if (isRecording && !hasText && !recordingHeld && !recordingLocked && !voiceRecording) {
                    Box(
                        modifier = Modifier
                            .size(composerControlSize)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Color(0x24FF5D73))
                    )
                }
                // Telegram Android: the lock 112dp above the mic rises with the finger; the 82dp circle
                // follows it up and shrinks towards cancel. Once locked the circle stays and sends,
                // and the lock turns into a pause button that stops into the preview.
                if (voiceRecording && recordingHeld && !recordingLocked) {
                    val lockHeight = 50f - 14f * recordDrag.lockProgress
                    AloRecordingLock(
                        progress = recordDrag.lockProgress,
                        onLock = { recordingLocked = true },
                        modifier = Modifier
                            .offset(y = (-112f + lockHeight / 2f + recordDrag.orbY).dp)
                            .zIndex(2f)
                    )
                }
                if (voiceRecording && recordingLocked) {
                    AloRecordingPauseButton(
                        onClick = { onStopRecording(false) },
                        modifier = Modifier.offset(y = (-78).dp).zIndex(2f)
                    )
                }
                if (voiceRecording && (recordingHeld || recordingLocked)) {
                    AloRecordingOrb(
                        icon = when {
                            recordingLocked -> Icons.AutoMirrored.Rounded.Send
                            circleRecording -> Icons.Rounded.Videocam
                            else -> Icons.Rounded.Mic
                        },
                        color = AloNativeChatSendAccent,
                        level = recordingLevels.lastOrNull() ?: 0f,
                        scale = if (recordingLocked) 1f else recordDrag.orbScale,
                        contentDescription = if (recordingLocked) "Отправить запись" else null,
                        modifier = Modifier
                            .offset(y = if (recordingLocked) 0.dp else recordDrag.orbY.dp)
                            .zIndex(3f)
                            .then(
                                if (recordingLocked) Modifier
                                    .clip(CircleShape)
                                    .clickable(onClick = onSendRecording)
                                else Modifier
                            )
                    )
                }
                val actionTint = if (buttonColor == AloNativeChatSendAccent || buttonColor == Color(0xFFFF5D73)) {
                    buttonColor
                } else {
                    glassTone
                }
                // Under Telegram's record circle the mic itself is hidden; it keeps the holding finger's gesture.
                val hideActionUnderOrb = voiceRecording && (recordingHeld || recordingLocked)
                AloNativeGlassPanel(
                    modifier = Modifier
                        .scale(actionScale)
                        .graphicsLayer { alpha = if (hideActionUnderOrb) 0f else 1f },
                    shape = CircleShape,
                    tint = actionTint,
                    borderColor = if (actionTint == glassTone) AloNativeAppBorder.copy(alpha = 0.05f) else buttonColor.copy(alpha = 0.32f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(composerControlSize)
                            .clip(CircleShape)
                            // Send / stop is a filled circle, as iOS's send button: inside a chat the glass panel draws
                            // the wallpaper through itself and ignores its tint, so the white arrow sat on white glass
                            // in the light theme and could not be seen.
                            .background(if (buttonColor != glassTone) buttonColor else Color.Transparent)
                            .then(if (micGesture) Modifier.aloHeldMic(heldMic) else Modifier.combinedClickable(
                                interactionSource = actionInteraction,
                                indication = null,
                                enabled = !isSending,
                                onClick = {
                                    when {
                                        hasRecordingPreview -> {
                                            onSendDraft()
                                        }
                                        hasText || canSubmitEdit -> onSend()
                                        hasForwardDraft -> onSend()
                                        isRecording -> {
                                            onStopRecording(false)
                                        }
                                        else -> {
                                            onToggleRecorderMode()
                                        }
                                    }
                                },
                                onLongClick = {
                                    val canSend = hasText || hasRecordingPreview || hasForwardDraft
                                    if (!isEditing && canSend && !isRecording) {
                                        sendOptionsExpanded = true
                                    } else if (!isEditing && !hasText && !isRecording && !hasRecordingPreview) {
                                        onStartRecording()
                                    }
                                }
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF10210D)
                            )
                        } else {
                            AnimatedContent(
                                targetState = when {
                                    isRecording && recordingLocked -> Icons.AutoMirrored.Rounded.Send
                                    isRecording -> Icons.Rounded.Stop
                                    hasText || hasRecordingPreview || hasForwardDraft || canSubmitEdit -> Icons.AutoMirrored.Rounded.Send
                                    else -> actionIcon
                                },
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(160)) + slideInHorizontally(
                                        animationSpec = tween(160),
                                        initialOffsetX = { it / 3 }
                                    )).togetherWith(
                                        fadeOut(animationSpec = tween(140)) + slideOutHorizontally(
                                            animationSpec = tween(140),
                                            targetOffsetX = { -it / 4 }
                                        )
                                    )
                                },
                                label = "composerActionIcon"
                            ) { currentIcon ->
                                Icon(
                                    imageVector = currentIcon,
                                    contentDescription = null,
                                    tint = when {
                                        currentIcon == Icons.AutoMirrored.Rounded.Send -> Color.White
                                        currentIcon == Icons.Rounded.Stop -> Color.White
                                        else -> if (buttonColor == AloNativeChatSendAccent) Color(0xFFD7DEEA) else AloNativeAppMuted
                                    },
                                    modifier = Modifier.size(if (currentIcon == Icons.AutoMirrored.Rounded.Send) 20.dp else 22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sendOptionsExpanded) {
            ChatSendOptionsSheet(
                canSendWhenOnline = canSendWhenOnline,
                onDismiss = { sendOptionsExpanded = false },
                onSendSilently = {
                    sendOptionsExpanded = false
                    onSendSilently()
                },
                onSendWhenOnline = {
                    sendOptionsExpanded = false
                    onSendWhenOnline()
                },
                onOpenSchedulePicker = {
                    sendOptionsExpanded = false
                    onScheduleSend("")
                },
                onScheduleAt = { scheduledAt ->
                    sendOptionsExpanded = false
                    onScheduleSend(scheduledAt)
                },
                onScheduleRepeat = { repeat ->
                    sendOptionsExpanded = false
                    onScheduleRepeat(repeat)
                }
            )
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSendOptionsSheet(
    canSendWhenOnline: Boolean,
    onDismiss: () -> Unit,
    onSendSilently: () -> Unit,
    onSendWhenOnline: () -> Unit,
    onOpenSchedulePicker: () -> Unit,
    onScheduleAt: (String) -> Unit,
    onScheduleRepeat: (AloMessageRepeatSchedule) -> Unit
) {
    var step by remember { mutableStateOf(ChatSendOptionsStep.Root) }
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        
        containerColor = AloNativeAppBackgroundSecondary,
        contentColor = AloNativeAppText,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 11.dp, bottom = 7.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(AloNativeAppMuted.copy(alpha = 0.55f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            AloSheetTitle(
                text = when (step) {
                    ChatSendOptionsStep.Root -> "Отправка сообщения"
                    ChatSendOptionsStep.ScheduleOnce -> "Отправить позже"
                    ChatSendOptionsStep.Repeat -> "Повторная отправка"
                }
            )

            Column {
                when (step) {
                    ChatSendOptionsStep.ScheduleOnce -> {
                    ChatSendOptionRow(
                        title = "Через час",
                        subtitle = scheduleLabelFor(System.currentTimeMillis() + 3_600_000L),
                        icon = Icons.Rounded.Schedule,
                        onClick = {
                            onScheduleAt(Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000L).toString())
                        }
                    )
                    ChatSendOptionRow(
                        title = "Сегодня вечером",
                        subtitle = "В 20:00",
                        icon = Icons.Rounded.Schedule,
                        onClick = { onScheduleAt(nextScheduleAt(hour = 20, minute = 0)) }
                    )
                    ChatSendOptionRow(
                        title = "Завтра утром",
                        subtitle = "В 09:00",
                        icon = Icons.Rounded.Schedule,
                        onClick = { onScheduleAt(nextScheduleAt(hour = 9, minute = 0, forceTomorrow = true)) }
                    )
                    ChatSendOptionRow(
                        title = "Выбрать дату и время",
                        subtitle = "Точное время отправки",
                        icon = Icons.Rounded.Schedule,
                        onClick = onOpenSchedulePicker
                    )
                    }

                    ChatSendOptionsStep.Repeat -> {
                        ChatSendOptionRow(
                            title = "Каждый день",
                            subtitle = "Начать завтра в 09:00",
                            icon = Icons.Rounded.Schedule,
                            onClick = {
                                onScheduleRepeat(
                                    AloMessageRepeatSchedule(
                                        startsAt = nextScheduleAt(9, 0, forceTomorrow = true),
                                        intervalSeconds = 24L * 60L * 60L
                                    )
                                )
                            }
                        )
                        ChatSendOptionRow(
                            title = "Каждую неделю",
                            subtitle = "Первый раз завтра в 09:00",
                            icon = Icons.Rounded.Schedule,
                            onClick = {
                                onScheduleRepeat(
                                    AloMessageRepeatSchedule(
                                        startsAt = nextScheduleAt(9, 0, forceTomorrow = true),
                                        intervalSeconds = 7L * 24L * 60L * 60L
                                    )
                                )
                            }
                        )
                        ChatSendOptionRow(
                            title = "Раз в месяц",
                            subtitle = "Первый раз через 30 дней в 09:00",
                            icon = Icons.Rounded.Schedule,
                            onClick = {
                                onScheduleRepeat(
                                    AloMessageRepeatSchedule(
                                        startsAt = nextMonthlyScheduleAt(),
                                        intervalSeconds = 30L * 24L * 60L * 60L
                                    )
                                )
                            }
                        )
                    }

                    ChatSendOptionsStep.Root -> {
                    ChatSendOptionRow(
                        title = "Отправить без звука",
                        subtitle = "Получатель увидит сообщение без уведомления",
                        icon = Icons.Rounded.NotificationsOff,
                        onClick = onSendSilently
                    )
                    if (canSendWhenOnline) {
                        ChatSendOptionRow(
                            title = "Когда появится в сети",
                            subtitle = "Сообщение уйдёт, когда получатель будет онлайн",
                            icon = Icons.Rounded.Schedule,
                            onClick = onSendWhenOnline
                        )
                    }
                    ChatSendOptionRow(
                        title = "Отправить позже",
                        subtitle = "Выбрать дату и время",
                        icon = Icons.Rounded.Schedule,
                        onClick = { step = ChatSendOptionsStep.ScheduleOnce }
                    )
                    ChatSendOptionRow(
                        title = "Повторять сообщение",
                        subtitle = "Каждый день, неделю или месяц",
                        icon = Icons.Rounded.Schedule,
                        onClick = { step = ChatSendOptionsStep.Repeat }
                    )
                    }
                }
            }
        }
    }
}

private enum class ChatSendOptionsStep { Root, ScheduleOnce, Repeat }

private fun nextScheduleAt(hour: Int, minute: Int, forceTomorrow: Boolean = false): String {
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    var candidate = now
        .withHour(hour)
        .withMinute(minute)
        .withSecond(0)
        .withNano(0)
    if (forceTomorrow || candidate.toInstant() <= now.toInstant().plusSeconds(60)) {
        candidate = candidate.plusDays(1)
    }
    return candidate.toInstant().toString()
}

private fun nextMonthlyScheduleAt(): String {
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    return now
        .plusDays(30)
        .withHour(9)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .toInstant()
        .toString()
}

private fun scheduleLabelFor(epochMillis: Long): String {
    val value = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
    return "Сегодня в %02d:%02d".format(value.hour, value.minute)
}

@Composable
private fun ChatSendOptionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    AloSheetItem(title = title, subtitle = subtitle, icon = icon, onClick = onClick)
}

@Composable
private fun MessageSelfDestructMenu(
    seconds: Int,
    onChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .clip(AloSquircle(14.dp))
                .clickable { expanded = true },
            color = AloNativeChatGlassSurface,
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.07f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = if (seconds > 0) AloNativeAppAccentStrong else AloNativeAppMuted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = selfDestructLabel(seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (seconds > 0) AloNativeAppText else AloNativeAppMuted
                )
            }
        }
        AloDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(
                0 to "Не удалять",
                1 to "Один просмотр",
                5 to "Через 5 секунд",
                10 to "Через 10 секунд",
                30 to "Через 30 секунд",
                60 to "Через 1 минуту"
            ).forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(if (seconds == value) "✓  $label" else label)
                    },
                    onClick = {
                        onChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun selfDestructLabel(seconds: Int): String = when (seconds) {
    1 -> "Один просмотр"
    5, 10, 30 -> "$seconds с"
    60 -> "1 мин"
    else -> "Без таймера"
}

@Composable
private fun MessageFormattingMenu(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Форматирование",
                tint = AloNativeAppMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        AloDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(
                "Жирный" to "**",
                "Курсив" to "__",
                "Подчёркнутый" to "++",
                "Зачёркнутый" to "~~",
                "Скрытый текст" to "||",
                "Моноширинный" to "`"
            ).forEach { (label, marker) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(toggleMessageMarkup(value, marker))
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun toggleMessageMarkup(value: String, marker: String): String {
    return if (
        value.startsWith(marker) &&
        value.endsWith(marker) &&
        value.length >= marker.length * 2
    ) {
        value.removePrefix(marker).removeSuffix(marker)
    } else {
        marker + value + marker
    }
}

@Composable
private fun EditingMessagePreview(
    message: AloChatMessage,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackgroundSecondary.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(AloNativeChatSendAccent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Изменение",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeChatSendAccent
                )
                Text(
                    text = message.body.ifBlank { message.attachmentName.ifBlank { "Вложение" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Отменить изменение",
                    tint = AloNativeAppMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

private fun activeMentionQuery(value: String): String? {
    val atIndex = value.lastIndexOf('@')
    if (atIndex < 0) return null
    if (atIndex > 0 && !value[atIndex - 1].isWhitespace()) return null
    val query = value.substring(atIndex + 1)
    if (query.length > 32 || query.any { !(it.isLetterOrDigit() || it == '_') }) return null
    return query
}

private fun mentionCandidates(
    members: List<AloGroupMember>,
    query: String?
): List<AloGroupMember> {
    if (query == null) return emptyList()
    return members.asSequence()
        .filter { it.user.username.isNotBlank() }
        .filter {
            query.isEmpty() || it.user.username.contains(query, ignoreCase = true) ||
                it.user.name.contains(query, ignoreCase = true)
        }
        .take(6)
        .toList()
}

private fun replaceActiveMention(value: String, username: String): String {
    val atIndex = value.lastIndexOf('@')
    return if (atIndex >= 0 && username.isNotBlank()) {
        value.substring(0, atIndex) + "@$username "
    } else {
        value
    }
}

@Composable
private fun MentionSuggestions(
    members: List<AloGroupMember>,
    onSelect: (AloGroupMember) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(members, key = { it.user.id }) { member ->
            Surface(
                modifier = Modifier
                    .height(42.dp)
                    .clip(AloSquircle(21.dp))
                    .clickable { onSelect(member) },
                shape = AloSquircle(21.dp),
                color = AloNativeAppBackgroundSecondary.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = member.user.name.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppAccentStrong
                    )
                    Column {
                        Text(
                            text = member.user.name,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${member.user.username}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AloNativeAppMuted,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyMessagePreview(
    message: AloChatMessage,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackgroundSecondary.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(AloNativeChatSendAccent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.sender?.name?.ifBlank { "Сообщение" } ?: "Сообщение",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeChatSendAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = message.body.ifBlank { message.attachmentName.ifBlank { "Вложение" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Отменить ответ",
                    tint = AloNativeAppMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun ForwardDraftPreview(
    forwardDraft: List<AloMessageForwardSource>,
    onRemoveForward: () -> Unit,
    onChangeTarget: () -> Unit
) {
    val names = forwardDraft
        .map { it.userName.ifBlank { "Автор" } }
        .distinct()
        .take(2)
        .joinToString(", ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(18.dp),
        color = AloNativeAppBackgroundSecondary.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(AloNativeChatSendAccent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (forwardDraft.size > 1) "Переслать ${forwardDraft.size} сообщения" else "Переслать сообщение",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeAppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "от $names",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "Кому",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onChangeTarget)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
                color = AloNativeChatSendAccent
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemoveForward),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = AloNativeAppMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DraftMediaPreviewPanel(
    attachments: List<AloChatDraftAttachment>,
    onAdd: () -> Unit,
    onOpen: (Int) -> Unit,
    onRemove: (AloChatDraftAttachment) -> Unit
) {
    AloNativeGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(22.dp),
        tint = AloNativeChatGlassSurface,
        borderColor = AloNativeAppBorder.copy(alpha = 0.06f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (attachments.size > 1) "Альбом" else "Медиа",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText
                    )
                    Text(
                        text = draftMediaPreviewSubtitle(attachments),
                        modifier = Modifier.padding(top = 1.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AloNativeChatSendAccent)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = attachments,
                    key = { _, attachment -> attachment.localUri }
                ) { index, attachment ->
                    DraftMediaPreviewTile(
                        attachment = attachment,
                        index = index,
                        onOpen = { onOpen(index) },
                        onRemove = { onRemove(attachment) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftMediaPreviewTile(
    attachment: AloChatDraftAttachment,
    index: Int,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val isVideo = attachment.attachmentType == "video"
    val previewModel = remember(attachment.localUri, attachment.previewUri, isVideo) {
        when {
            attachment.previewUri.isNotBlank() -> attachment.previewUri
            isVideo -> ""
            else -> attachment.localUri
        }
    }
    Box(
        modifier = Modifier
            .size(width = 82.dp, height = 88.dp)
            .clip(AloSquircle(16.dp))
            .background(AloNativeAppBackgroundSecondary)
            .clickable(onClick = onOpen)
    ) {
        if (previewModel.isNotBlank()) {
            AsyncImage(
                model = previewModel,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                AloNativeChatSendAccent.copy(alpha = 0.32f),
                                AloNativeAppBackgroundSecondary,
                                Color.Black.copy(alpha = 0.36f)
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.36f)
                        )
                    )
                )
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = "${index + 1}",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 7.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.92f)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(25.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

private fun draftMediaCountLabel(attachments: List<AloChatDraftAttachment>): String {
    val photos = attachments.count { it.attachmentType == "image" }
    val videos = attachments.count { it.attachmentType == "video" }
    return when {
        photos > 0 && videos > 0 -> "$photos фото, $videos видео"
        photos > 0 -> if (photos == 1) "1 фото" else "$photos фото"
        videos > 0 -> if (videos == 1) "1 видео" else "$videos видео"
        attachments.size == 1 -> "1 вложение"
        else -> "${attachments.size} вложений"
    }
}

private fun draftMediaPreviewSubtitle(attachments: List<AloChatDraftAttachment>): String {
    val count = draftMediaCountLabel(attachments)
    return if (attachments.size > 1) {
        "$count · одним сообщением"
    } else {
        "$count · с подписью"
    }
}

private fun AloChatDraftAttachment.toDraftMediaItem(): AloMediaItem {
    return AloMediaItem(
        type = attachmentType,
        url = localUri,
        posterUrl = previewUri,
        alt = name,
        caption = name
    )
}

@Composable
private fun RecordingCircleIndicator(
    pulseScale: Float,
    isPreview: Boolean = false,
    previewUri: String = ""
) {
    Box(
        modifier = Modifier.size(52.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isPreview) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(Color(0x24FF5D73))
            )
        }
        Box(
            modifier = Modifier
                .size(if (isPreview) 52.dp else 48.dp)
                .clip(CircleShape)
                .background(AloNativeAppSurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            if (isPreview && previewUri.isNotBlank()) {
                AsyncImage(
                    model = previewUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0x3A132030)
                                )
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF40434C),
                                    Color(0xFF2C2F37),
                                    Color(0xFF1F2127)
                                )
                            )
                        )
                )
            }
            Icon(
                imageVector = Icons.Rounded.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** The length of a voice message or circle as the sender recorded it ("0:07"), or "" when it is not known yet. */
internal fun estimateAttachmentDurationLabel(message: AloChatMessage): String {
    val seconds = attachmentDurationSeconds(message)
    return if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else ""
}

@Composable
private fun RecordingWaveform(
    modifier: Modifier = Modifier,
    tint: Color,
    levels: List<Float> = emptyList()
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 640),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveformPhase"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fallbackBars = listOf(8f, 14f, 10f, 18f, 12f, 16f, 9f, 15f, 11f, 17f, 9f, 14f)
        val bars = levels.takeLast(18).map { 6f + (it.coerceIn(0.08f, 1f) * 18f) }.ifEmpty { fallbackBars }
        bars.forEachIndexed { index, baseHeight ->
            val scale = if (levels.isNotEmpty()) 1f else if (index % 2 == 0) phase else (1.18f - (phase * 0.4f))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((baseHeight * scale).dp)
                    .clip(CircleShape)
                    .background(tint)
            )
        }
    }
}
