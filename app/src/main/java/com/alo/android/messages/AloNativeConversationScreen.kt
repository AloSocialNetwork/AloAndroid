package com.alo.android

import com.alo.android.LocalAloGlassBackdrop
import com.alo.android.rememberAloGlassBackdrop
import com.alo.android.aloGlassSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import com.alo.android.ui.theme.LocalAloUiPalette
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.alo.android.AloInteractiveDismissPolicy
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun MessagesConversationScreen(
    me: AloCurrentUser,
    conversations: List<AloConversationSummary>,
    shareableContacts: List<AloEntitySummary>,
    detail: AloConversationDetail?,
    chatAppearance: AloChatAppearance,
    isLoading: Boolean,
    isOfflineCached: Boolean,
    blockedUserIds: Set<Int>,
    isPeerBlocked: Boolean,
    isBlockActionInProgress: Boolean,
    pendingMessageIds: Set<Int>,
    composerValue: () -> String,
    recorderMode: AloChatRecordMode,
    captureState: AloChatCaptureState,
    recordingDurationSec: () -> Int,
    recordingLevels: () -> List<Float>,
    draftAttachment: AloChatDraftAttachment?,
    draftAttachments: List<AloChatDraftAttachment>,
    selfDestructSeconds: Int,
    activeForumTopicId: Int,
    forwardDraft: List<AloMessageForwardSource>,
    editingMessageId: Int?,
    replyingToMessage: AloChatMessage?,
    isSending: Boolean,
    showPeerProfile: Boolean,
    isChatMuted: Boolean,
    groupManagement: AloGroupManagementSnapshot?,
    isGroupManagementLoading: Boolean,
    onBack: () -> Unit,
    /** Translation and voice-to-text for this chat; `null` where there is no server (nothing then offers either). */
    contentTools: AloContentTools? = null,
    /** How much of the inbox the chat still covers while it is swiped away, for Telegram's parallax. */
    onSwipeCover: (Float) -> Unit = {},
    onClosePeerProfile: () -> Unit,
    onOpenPeerProfile: () -> Unit,
    onToggleChatMuted: () -> Unit,
    onLoadChatMuted: () -> Unit = {},
    /** Set when this group chat is a channel post's comments (Telegram): the post on top, «N комментариев» on top. */
    commentsThread: AloCommentsThread? = null,
    onJoinChannel: () -> Unit = {},
    onUpdateDirectChatAutoDelete: (Int, Int) -> Unit,
    onUpdateChatAppearance: (AloChatAppearance) -> Unit,
    onUpdateUserBlock: (Int, Boolean) -> Unit,
    onOpenFullProfile: () -> Unit,
    onOpenProfile: (AloProfileTarget) -> Unit,
    onComposerChange: (String) -> Unit,
    onStartReply: (AloChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    onSendSilently: () -> Unit,
    onSendWhenOnline: () -> Unit,
    onScheduleSend: (String) -> Unit,
    onScheduleRepeat: (AloMessageRepeatSchedule) -> Unit,
    onSendChatSystemEvent: (String, AloPollDefinition?) -> Unit,
    onCreatePoll: (AloPollDefinition, Boolean) -> Unit,
    onClosePoll: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onPickGalleryUri: (String) -> Unit,
    onPickGalleryUris: (List<String>) -> Unit,
    onShareContact: (AloEntitySummary) -> Unit,
    onShareStaticLocation: (Double, Double, String, String) -> Unit,
    onShareLiveLocation: (AloLiveLocationRequest) -> Unit,
    onToggleRecorderMode: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (Boolean) -> Unit,
    onSendRecording: () -> Unit = {},
    onCompleteVideoCircleRecording: (String, Int) -> Unit,
    /** A circle released on the mic: recorded and sent at once (Telegram). */
    onSendVideoCircleRecording: (String, Int) -> Unit = { _, _ -> },
    onDiscardDraft: () -> Unit,
    onRemoveDraftAttachment: (String) -> Unit,
    onSelfDestructSecondsChange: (Int) -> Unit,
    onSelectForumTopic: (Int) -> Unit,
    onToggleForumTopicMuted: (Int) -> Unit,
    onSendDraft: () -> Unit,
    onDiscardForwardDraft: () -> Unit,
    onCancelEdit: () -> Unit,
    onRetargetForwardDraft: (AloConversationSummary) -> Unit,
    onSendCatalogExpression: (AloCatalogExpressionMedia) -> Unit,
    onOpenMedia: (AloMediaItem) -> Unit,
    onOpenMediaGallery: (List<AloMediaItem>, Int) -> Unit,
    onPrepareMediaOpen: (AloChatMessage, (AloChatMessage) -> Unit) -> Unit,
    onOpenAttachment: (AloChatMessage) -> Unit,
    onOpenChannelPostDiscussion: (Int) -> Unit,
    onDeleteMessage: (Int, Boolean) -> Unit,
    onDeleteMessages: (List<Int>, Boolean) -> Unit,
    onRescheduleMessage: (Int) -> Unit,
    onCancelScheduledMessage: (Int) -> Unit,
    onEditMessage: (Int) -> Unit,
    onToggleReaction: (Int, String) -> Unit,
    onToggleMessagePin: (Int) -> Unit,
    onForwardMessage: (AloChatMessage, AloConversationSummary) -> Unit,
    onForwardMessages: (List<AloChatMessage>, AloConversationSummary) -> Unit,
    onUpdateGroupTitle: (Int, String) -> Unit,
    onPickGroupAvatar: (Int) -> Unit,
    onAddGroupMember: (Int, String) -> Unit,
    onRemoveGroupMember: (Int, Int) -> Unit,
    onLeaveGroupChat: (Int) -> Unit,
    onDeleteGroupChat: (Int) -> Unit,
    onUpdateGroupMemberRole: (Int, Int, String) -> Unit,
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
    onStartVideoCall: () -> Unit
) {
    // Reuse the same nullable identity values. Repeated safe-call expressions
    // in this large composable cause exponential FIR data-flow analysis.
    val conversationPeerKey = detail?.peerId
    val conversationKindKey = detail?.kind
    val conversationRoomKey = detail?.roomId
    val messagesListState = rememberLazyListState()
    val isChannelConversation = detail?.isChannelCommunity() == true
    val attachmentCapabilities = remember(detail) {
        AloChatAttachmentCapabilities.resolve(detail)
    }
    val canComposeMessages = !isPeerBlocked && attachmentCapabilities.canSendMessages
    val canSendWhenOnline = detail?.kind == "direct" &&
        detail.peerId > 0 &&
        detail.peerId != me.id
    val forwardableConversations = remember(conversations, blockedUserIds) {
        conversations.filterNot { conversation ->
            conversation.isBlockedDirectPeer(blockedUserIds)
        }
    }
    fun hasAdministrativeRight(right: String): Boolean {
        if (detail?.memberRole == "owner") return true
        if (detail?.memberRole != "admin") return false
        val rights = detail.groupPolicy?.memberAdminRights.orEmpty()
        return rights.isEmpty() || rights[right] == true
    }
    fun canEditMessage(message: AloChatMessage): Boolean {
        if (
            message.id <= 0 ||
            message.toCallLogMeta() != null ||
            AloPollCodec.decode(message.body) != null ||
            message.body.isBlank()
        ) return false
        if (message.senderId == me.id) return true
        return isChannelConversation && hasAdministrativeRight("editMessages")
    }
    fun canDeleteMessageForEveryone(message: AloChatMessage): Boolean {
        if (message.id <= 0) return false
        // A private chat: only the author removes a message for both (the server refuses the rest).
        if (detail?.kind != "group" && (detail?.roomId ?: 0) <= 0) return message.senderId == me.id
        if (message.senderId == me.id) return true
        return hasAdministrativeRight("deleteMessages")
    }
    val canPinMessages = when {
        detail?.kind != "group" && (detail?.roomId ?: 0) <= 0 -> true
        hasAdministrativeRight("pinMessages") -> true
        else -> detail?.groupPolicy?.defaultPermissions?.get("pinMessages") == true
    }
    val allPinnedMessages = detail?.pinnedMessageIds.orEmpty().mapNotNull { messageId ->
        detail?.items?.firstOrNull { it.id == messageId }
    }
    val pinnedMessages = if (detail?.groupPolicy?.isForum == true && activeForumTopicId > 0) {
        allPinnedMessages.filter { it.topicId == activeForumTopicId }
    } else {
        allPinnedMessages
    }
    val pinnedMessage = pinnedMessages.firstOrNull()
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val safeBottomPx = WindowInsets.safeDrawing.getBottom(density)
    val imeVisible = imeBottomPx > 0
    // Telegram's emoji panel takes the keyboard's place: as tall as the keyboard was, so the chat does not jump when one gives way to the other.
    val panelContext = LocalContext.current
    var expressionPanelOpen by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var expressionSearching by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var expressionTabName by rememberSaveable { mutableStateOf(AloExpressionTab.Emoji.name) }
    val expressionTab = AloExpressionTab.entries.firstOrNull { it.name == expressionTabName } ?: AloExpressionTab.Emoji
    var lastKeyboardPx by remember {
        mutableIntStateOf(panelContext.getSharedPreferences("alo-android", android.content.Context.MODE_PRIVATE).getInt("last_keyboard_px", 0))
    }
    // The keyboard's height once it has settled (not while it slides in or out), so the panel can be exactly as tall.
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0 && imeBottomPx != lastKeyboardPx) {
            delay(300)
            lastKeyboardPx = imeBottomPx
            panelContext.getSharedPreferences("alo-android", android.content.Context.MODE_PRIVATE).edit().putInt("last_keyboard_px", imeBottomPx).apply()
        }
    }
    val imeDp = with(density) { imeBottomPx.toDp() }
    val lastKeyboardDp = with(density) { lastKeyboardPx.toDp() }
        .let { if (it < 220.dp) AloExpressionMetrics.DefaultKeyboardHeight else it.coerceAtMost(440.dp) }
    val panelBaseDp by animateDpAsState(
        targetValue = if (expressionPanelOpen) lastKeyboardDp else 0.dp,
        animationSpec = tween(240, easing = AloTelegramDecelerate),
        label = "expressionPanelHeight"
    )
    // In search the panel is the field and a row of results above the keyboard, which follows the keyboard up.
    val panelDp = if (expressionPanelOpen && expressionSearching) {
        maxOf(lastKeyboardDp, imeDp + AloExpressionMetrics.SearchHeight)
    } else {
        panelBaseDp
    }
    val bottomPanelVisible = imeVisible || panelDp > 0.dp
    val composerSystemBottomPadding = with(density) {
        if (bottomPanelVisible) maxOf(imeBottomPx.toDp(), panelDp) + 6.dp else safeBottomPx.toDp()
    }
    var isForumSearchVisible by rememberSaveable(conversationRoomKey) { mutableStateOf(false) }
    var forumSearchText by rememberSaveable(conversationRoomKey, activeForumTopicId) { mutableStateOf("") }
    val forumSelectorVisible = detail?.groupPolicy?.isForum == true &&
        !detail.forumTopics.isNullOrEmpty() && commentsThread == null
    val forumSelectorHeight = if (forumSelectorVisible) {
        if (isForumSearchVisible) 84.dp else 42.dp
    } else {
        0.dp
    }
    // The voice message playing anywhere in the app (AloVoicePlayback.kt): it goes on when this chat closes.
    val activeVoiceMessageId = AloVoicePlayer.messageId
    // A circle with sound has the same bar (AloCirclePlayback.kt); one plays at a time.
    val activeCircleMessageId = AloCirclePlayer.messageId
    // The list makes room for the pinned bar gently, not with a jump, as Telegram's top panels do (ChatActivityTopPanelLayout animates its height).
    val pinnedBarHeight by animateDpAsState(
        targetValue = if (pinnedMessage != null) AloChatOverlayMetrics.PinnedBlock else 0.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "pinnedBarHeight"
    )
    // The bar keeps its text while it slides out after the last pin was removed.
    val pinnedHolder = remember { arrayOfNulls<AloChatMessage>(1) }
    if (pinnedMessage != null) pinnedHolder[0] = pinnedMessage
    // The voice player bar is a top panel too: it takes its own place under the header and the pinned bar, so the floating
    // date and the first messages move below it instead of hiding behind it.
    val voiceBarHeight by animateDpAsState(
        targetValue = if (activeVoiceMessageId != null || activeCircleMessageId != null) AloChatOverlayMetrics.VoiceBlock else 0.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "voiceBarHeight"
    )
    // A song that plays: its oval takes the same place (AloMusic.kt) in every chat, not only the one it came from (the
    // owner, 2026-10-31: it used to vanish on switching chats); a voice message playing wins.
    val musicChatKey = "${detail?.kind}:${detail?.peerId}:${detail?.roomId}"
    val musicBarShown = AloMusicPlayer.track != null && activeVoiceMessageId == null && activeCircleMessageId == null
    val musicBarHeight by animateDpAsState(
        targetValue = if (musicBarShown) AloChatOverlayMetrics.VoiceBlock + 14.dp else 0.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "musicBarHeight"
    )
    val headerOverlayHeight = (if (bottomPanelVisible) AloChatOverlayMetrics.ListTopWithKeyboard else AloChatOverlayMetrics.ListTop) +
        pinnedBarHeight + forumSelectorHeight + voiceBarHeight + musicBarHeight
    // The wallpaper and the message list, blurred under the header and composer glass.
    val chatBackdrop = rememberAloGlassBackdrop()
    // The composer's held mic and the camera overlay of a circle being recorded.
    val circleControl = remember { AloCircleRecordingControl() }
    val recordingCircle = recorderMode == AloChatRecordMode.VideoCircle && captureState == AloChatCaptureState.Recording
    val composerOverlayHeight = if (bottomPanelVisible) 68.dp else 80.dp
    val messagesBottomPadding = composerOverlayHeight + composerSystemBottomPadding
    // The composer's real height (it grows with a reply bar, attachments or several lines): the jump button rides just above it, as Telegram's does.
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val jumpButtonBottomTarget = if (composerHeightPx > 0) {
        composerSystemBottomPadding + with(density) { composerHeightPx.toDp() } + AloChatOverlayMetrics.JumpButtonGap
    } else {
        messagesBottomPadding + 12.dp
    }
    val jumpButtonBottom by animateDpAsState(
        targetValue = jumpButtonBottomTarget,
        animationSpec = tween(160, easing = AloTelegramDecelerate),
        label = "jumpButtonBottom"
    )
    var selectedMessageForMenu by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf<AloChatMessage?>(null) }
    var forwardingMessages by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf<List<AloChatMessage>>(emptyList()) }
    val selectedMessages = remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateMapOf<Int, AloChatMessage>() }
    var isAttachmentSheetOpen by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var isPollComposerOpen by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var isScheduledMessagesOpen by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    val scheduledMessages = detail?.items
        ?.filter { it.isScheduled }
        ?.sortedWith(compareBy<AloChatMessage> { it.scheduledAt }.thenBy { it.scheduledMessageId })
        .orEmpty()

    LaunchedEffect(isPeerBlocked) {
        if (isPeerBlocked) isAttachmentSheetOpen = false
    }
    var isForwardRecipientSheetOpen by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var floatingDateVisible by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf(false) }
    var floatingDateLabel by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf("") }
    // A voice message and a song never play together: the one started last stops the other (AloVoicePlayer, AloMusicPlayer).
    val configuration = LocalConfiguration.current
    val viewConfiguration = LocalViewConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val chatSwipeMaxOffset = screenWidthPx
    val chatSwipeStartSlop = viewConfiguration.touchSlop
    var chatSwipeOffset by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableFloatStateOf(0f) }
    // Telegram's close from the back button: the chat fades while sliding 48dp right.
    var chatCloseProgress by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableFloatStateOf(0f) }
    val pageShiftPx = rememberAloPageShiftPx()
    // Telegram drops the keyboard as soon as a chat starts to close, by button or by swipe.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun dropKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        expressionPanelOpen = false
        expressionSearching = false
    }
    // The emoji button: the panel in place of the keyboard, and the keyboard back in place of the panel.
    fun toggleExpressionPanel() {
        if (expressionPanelOpen) {
            expressionPanelOpen = false
            expressionSearching = false
            keyboardController?.show()
        } else {
            expressionPanelOpen = true
            keyboardController?.hide()
        }
    }
    fun closeExpressionPanelForKeyboard() {
        expressionPanelOpen = false
        expressionSearching = false
        keyboardController?.show()
    }
    LaunchedEffect(chatSwipeMaxOffset) {
        snapshotFlow { chatSwipeOffset }.collect { offset ->
            onSwipeCover(if (offset > 0f && chatSwipeMaxOffset > 0f) 1f - offset / chatSwipeMaxOffset else 0f)
        }
    }
    DisposableEffect(Unit) { onDispose { onSwipeCover(0f) } }
    var chatSwipeSettleJob by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf<Job?>(null) }
    var suppressPeerProfileExitAnimation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isSelectionMode = selectedMessages.isNotEmpty()
    // The post on top of its comments is the list's first row: message N is list row N + this.
    val listHeaderItems = if (commentsThread?.post != null) 1 else 0
    val firstVisibleMessageIndex by remember {
        derivedStateOf { messagesListState.firstVisibleItemIndex }
    }
    val isNearMessageListBottom by remember {
        derivedStateOf {
            val info = messagesListState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 0) {
                true
            } else {
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 2
            }
        }
    }
    // Telegram's page-down counter: messages that arrived below while you were reading above.
    var unseenBelow by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableIntStateOf(0) }
    LaunchedEffect(isNearMessageListBottom) { if (isNearMessageListBottom) unseenBelow = 0 }
    // Telegram's jump to a message (from a quoted reply or the pinned bar): it scrolls there and the
    // row lights up in the selection colour for 0.7 s, then fades in 0.3 s.
    var highlightedMessage by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) { mutableStateOf<Pair<Int, Int>?>(null) }
    val laidOutMessageCount by remember {
        derivedStateOf { messagesListState.layoutInfo.totalItemsCount }
    }
    var wasNearBottomBeforeIme by remember(conversationPeerKey, conversationKindKey, conversationRoomKey) {
        mutableStateOf(false)
    }
    val visibleMessages = remember(
        detail?.items,
        detail?.forumTopics,
        detail?.groupPolicy?.isForum,
        activeForumTopicId,
        isForumSearchVisible,
        forumSearchText
    ) {
        val all = detail?.items.orEmpty()
        val topicFiltered = if (
            detail?.groupPolicy?.isForum == true && activeForumTopicId > 0
        ) {
            val generalTopicId = detail.forumTopics.firstOrNull { it.isGeneral }?.id ?: 0
            all.filter { message ->
                val messageTopicId = message.topicId.takeIf { it > 0 } ?: generalTopicId
                messageTopicId == activeForumTopicId
            }
        } else {
            all
        }
        val displayable = AloPollProjection.visibleMessages(topicFiltered)
        val pollStates = AloPollProjection.applyingCanonical(
            detail?.canonicalPolls.orEmpty(),
            AloPollProjection.states(topicFiltered, me.id),
            AloPollProjection.serverTalliedPollIds(topicFiltered)
        )
        val query = forumSearchText.trim()
        if (!isForumSearchVisible || query.isBlank()) {
            displayable
        } else {
            displayable.filter { message ->
                val poll = (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
                    ?.definition
                    ?.let { pollStates[it.id] }
                poll?.definition?.question?.contains(query, ignoreCase = true) == true ||
                    poll?.definition?.options?.any { it.text.contains(query, ignoreCase = true) } == true ||
                    message.body.contains(query, ignoreCase = true) ||
                    message.attachmentName.contains(query, ignoreCase = true) ||
                    message.attachments.any { it.name.contains(query, ignoreCase = true) }
            }
        }
    }
    // Joins and leaves of a group between its messages, as Telegram shows them (AloGroupServiceLines.kt).
    var groupServiceEvents by remember(conversationRoomKey) { mutableStateOf(emptyList<AloGroupServiceEvent>()) }
    val showsServiceLines = detail?.kind == "group" && !isChannelConversation && activeForumTopicId <= 0
    LaunchedEffect(conversationRoomKey, detail?.items?.size, detail?.members?.size, showsServiceLines) {
        val roomId = detail?.roomId ?: 0
        if (!showsServiceLines || roomId <= 0) return@LaunchedEffect
        if (groupServiceEvents.isNotEmpty()) delay(600)
        val events = try {
            AloGroupServiceLines.loader?.invoke(roomId)
        } catch (error: java.io.IOException) {
            null
        } catch (error: AloApiException) {
            null
        } ?: return@LaunchedEffect
        if (events != groupServiceEvents) groupServiceEvents = events
    }
    val serviceLines = remember(groupServiceEvents, visibleMessages, showsServiceLines, me.id) {
        if (showsServiceLines) {
            AloGroupServiceLines.place(groupServiceEvents, visibleMessages, hasOlder = visibleMessages.size >= 30, meId = me.id)
        } else {
            AloGroupServicePlacement()
        }
    }
    val musicQueue = remember(visibleMessages, musicChatKey) {
        AloMusicQueue(musicChatKey, visibleMessages.mapNotNull { AloMusic.trackOf(it) })
    }
    fun jumpToMessage(messageId: Int) {
        val index = visibleMessages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        scope.launch {
            val viewport = messagesListState.layoutInfo.viewportSize.height
            messagesListState.animateScrollToItem(index + listHeaderItems, scrollOffset = -viewport / 3)
            highlightedMessage = messageId to ((highlightedMessage?.second ?: 0) + 1)
        }
    }
    // Rows keep the key a sent message had before the server numbered it (Telegram's stableId), and
    // new rows at the bottom make Telegram's entrance: out of the composer for your text, from
    // behind it for everything else.
    val messagesById = remember(detail?.items) { detail?.items.orEmpty().associateBy { it.id } }
    val messageEnterState = remember(conversationKindKey, conversationPeerKey, conversationRoomKey, activeForumTopicId, isForumSearchVisible) {
        AloMessageEnterState()
    }
    val messageKeyAliases = remember(conversationKindKey, conversationPeerKey, conversationRoomKey) { HashMap<Int, Int>() }
    val messageKeys = remember(visibleMessages) {
        val used = HashSet<Int>()
        visibleMessages.map { message ->
            if (message.localKey != 0) messageKeyAliases[message.id] = message.localKey
            val key = messageKeyAliases[message.id] ?: message.id
            if (used.add(key)) key else message.id.also { used.add(it) }
        }
    }
    messageEnterState.observe(messageKeys) { index ->
        val message = visibleMessages[index]
        message.senderId == me.id && message.id < 0 && message.body.isNotBlank() &&
            message.attachments.isEmpty() && message.attachmentType.isBlank() &&
            AloChatSystemEventCodec.decode(message.body) == null && AloPollCodec.decode(message.body) == null
    }
    val pollStates = remember(detail?.items, detail?.canonicalPolls, me.id) {
        AloPollProjection.applyingCanonical(
            detail?.canonicalPolls.orEmpty(),
            AloPollProjection.states(detail?.items.orEmpty(), me.id),
            AloPollProjection.serverTalliedPollIds(detail?.items.orEmpty())
        )
    }

    LaunchedEffect(isNearMessageListBottom, imeVisible, laidOutMessageCount) {
        if (!imeVisible && laidOutMessageCount > 0) {
            wasNearBottomBeforeIme = isNearMessageListBottom
        }
    }

    LaunchedEffect(firstVisibleMessageIndex, visibleMessages, listHeaderItems) {
        visibleMessages.getOrNull(firstVisibleMessageIndex - listHeaderItems)?.let { message ->
            floatingDateLabel = formatMessageDateLabel(message.createdAt)
        }
    }

    // The list's own day pill sits at the top edge when its day has just begun there: the floating copy would draw over it a few pixels off.
    val floatingDateCoincides by remember(visibleMessages, listHeaderItems) {
        derivedStateOf {
            val firstIndex = messagesListState.firstVisibleItemIndex - listHeaderItems
            val first = visibleMessages.getOrNull(firstIndex) ?: return@derivedStateOf false
            val previous = visibleMessages.getOrNull(firstIndex - 1)
            val startsDay = previous == null || formatMessageDayKey(previous.createdAt) != formatMessageDayKey(first.createdAt)
            startsDay && messagesListState.firstVisibleItemScrollOffset < 60
        }
    }

    LaunchedEffect(messagesListState.isScrollInProgress) {
        if (messagesListState.isScrollInProgress) {
            floatingDateVisible = true
        } else if (floatingDateVisible) {
            delay(1_000)
            floatingDateVisible = false
        }
    }

    // The channel bar «Убрать звук» / «Включить звук» shows the server's mute state of this channel.
    var profileOpensSearch by remember(detail?.peerId) { mutableStateOf(false) }
    val showsChannelBar = isChannelConversation && !canComposeMessages && detail?.isMember != false
    LaunchedEffect(detail?.peerId, showsChannelBar) {
        if (showsChannelBar) onLoadChatMuted()
    }
    LaunchedEffect(showPeerProfile) {
        if (!showPeerProfile) profileOpensSearch = false
    }
    LaunchedEffect(showPeerProfile) {
        if (showPeerProfile) {
            suppressPeerProfileExitAnimation = false
        }
    }

    // «Запретить копирование»: Telegram keeps a protected chat out of screenshots and recordings.
    val isProtectedChat = detail?.groupPolicy?.protectedContent == true
    AloSecureWindowWhile(isProtectedChat)
    BackHandler(enabled = isSelectionMode && forwardingMessages.isEmpty()) {
        selectedMessages.clear()
    }

    // Telegram's swipe-back settle (ActionBarLayout.animateBackEndAnimation): the time left is
    // proportional to the distance left, on AccelerateDecelerate.
    fun settleChatSwipe(shouldClose: Boolean) {
        chatSwipeSettleJob?.cancel()
        chatSwipeSettleJob = scope.launch {
            val target = if (shouldClose) chatSwipeMaxOffset else 0f
            animate(
                initialValue = chatSwipeOffset,
                targetValue = target,
                animationSpec = tween(
                    durationMillis = aloSwipeSettleMs(kotlin.math.abs(target - chatSwipeOffset), chatSwipeMaxOffset, shouldClose),
                    easing = AloTelegramAccelerateDecelerate
                )
            ) { value, _ -> chatSwipeOffset = value }
            if (shouldClose) onBack()
        }
    }

    fun closeChatWithSlide() {
        dropKeyboard()
        if (chatSwipeOffset > 0f) {
            settleChatSwipe(shouldClose = true)
            return
        }
        chatSwipeSettleJob?.cancel()
        chatSwipeSettleJob = scope.launch {
            animate(
                initialValue = chatCloseProgress,
                targetValue = 1f,
                animationSpec = tween(AloPageMotionMs, easing = AloTelegramDecelerate)
            ) { value, _ -> chatCloseProgress = value }
            onBack()
        }
    }

    BackHandler(enabled = !isSelectionMode && showPeerProfile) { onClosePeerProfile() }
    // A swipe from the screen's edge is the system's back gesture, so the chat follows that finger too, as it does Telegram's own swipe
    // (ActionBarLayout.onBackProgress): one to one with the finger, settled on release. The back button sends no progress and slides out as before.
    PredictiveBackHandler(enabled = !isSelectionMode && !showPeerProfile) { backEvents ->
        chatSwipeSettleJob?.cancel()
        var startX = Float.NaN
        var followed = false
        try {
            backEvents.collect { event ->
                if (startX.isNaN()) startX = event.touchX
                if (!followed) {
                    followed = true
                    dropKeyboard()
                }
                chatSwipeOffset = (event.touchX - startX).coerceIn(0f, chatSwipeMaxOffset)
            }
            if (followed) settleChatSwipe(shouldClose = true) else closeChatWithSlide()
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            settleChatSwipe(shouldClose = false)
        }
    }

    // Back first closes the emoji panel (or its search), then the chat.
    BackHandler(enabled = expressionPanelOpen && !isSelectionMode) {
        if (expressionSearching) {
            keyboardController?.hide()
            expressionSearching = false
        } else {
            expressionPanelOpen = false
        }
    }

    fun toggleMessageSelection(message: AloChatMessage) {
        if (selectedMessages.containsKey(message.id)) {
            selectedMessages.remove(message.id)
        } else {
            selectedMessages[message.id] = message
        }
    }

    fun orderedSelectedMessages(): List<AloChatMessage> {
        val items = detail?.items.orEmpty()
        return items.filter { selectedMessages.containsKey(it.id) }
    }

    LaunchedEffect(contentTools, me.id) {
        contentTools?.bindAccount(me.id)
        contentTools?.refreshConfig()
    }
    CompositionLocalProvider(
        LocalAloNativeChatAppearance provides chatAppearance,
        LocalAloMessageEnterState provides messageEnterState,
        LocalAloContentTools provides contentTools,
        LocalAloFavoriteReaction provides me.favoriteReaction
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 0.dp)
                .graphicsLayer {
                    translationX = chatSwipeOffset + pageShiftPx * chatCloseProgress
                    alpha = 1f - chatCloseProgress
                    shadowElevation = if (chatSwipeOffset > 0f) 18f else 0f
                    // Unclipped: the wallpaper and the top fade run up under the status bar.
                    clip = false
                }
                .pointerInput(showPeerProfile, isSelectionMode, selectedMessageForMenu?.id) {
                    if (showPeerProfile || isSelectionMode || selectedMessageForMenu != null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        chatSwipeSettleJob?.cancel()
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
                                // A nested horizontal control (reactions, media pager,
                                // attachment preview) consumed this movement. It owns the
                                // gesture; do not turn it into a conversation dismissal.
                                if (change.isConsumed) {
                                    cancelled = true
                                    break
                                }
                                val horizontalEnough = totalX > chatSwipeStartSlop &&
                                    kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 1.15f
                                val verticalWins = kotlin.math.abs(totalY) > chatSwipeStartSlop &&
                                    kotlin.math.abs(totalY) >= kotlin.math.abs(totalX)
                                if (verticalWins || totalX < -chatSwipeStartSlop) {
                                    cancelled = true
                                    break
                                }
                                if (!horizontalEnough) continue
                                started = true
                                dropKeyboard()
                            }

                            if (started) {
                                change.consume()
                                chatSwipeOffset = (chatSwipeOffset + delta.x).coerceIn(0f, chatSwipeMaxOffset)
                            }
                        }

                        if (started && !cancelled) {
                            val velocity = velocityTracker.calculateVelocity()
                            settleChatSwipe(
                                shouldClose = aloTelegramSwipeCloses(
                                    offsetPx = chatSwipeOffset,
                                    velocityX = velocity.x,
                                    velocityY = velocity.y,
                                    widthPx = screenWidthPx
                                )
                            )
                        } else if (cancelled) {
                            settleChatSwipe(shouldClose = false)
                        }
                    }
                }
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!showPeerProfile) {
                ChatWallpaperBackground(
                    modifier = Modifier
                        .matchParentSize()
                        .aloUnderStatusBar()
                        .blur(if (selectedMessageForMenu != null) 10.dp else 0.dp)
                        .aloGlassSource(chatBackdrop, order = 0)
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(if (selectedMessageForMenu != null) 10.dp else 0.dp)
                        .background(AloNativeAppBackground)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(if (selectedMessageForMenu != null) 10.dp else 0.dp)
                ) {
                when {
                    detail == null && isLoading -> {
                        ChatConversationLoadingSkeleton(
                            modifier = Modifier.fillMaxSize(),
                            topPadding = headerOverlayHeight,
                            bottomPadding = messagesBottomPadding
                        )
                    }

                    detail == null -> {
                        ChatConversationLoadingSkeleton(
                            modifier = Modifier.fillMaxSize(),
                            topPadding = headerOverlayHeight,
                            bottomPadding = messagesBottomPadding
                        )
                    }

                    else -> {
                        var previousItemsCount by remember(detail.kind, detail.peerId, detail.roomId) {
                            mutableStateOf(visibleMessages.size)
                        }
                        LaunchedEffect(
                            detail.kind,
                            detail.peerId,
                            detail.roomId,
                            activeForumTopicId
                        ) {
                            if (visibleMessages.isNotEmpty()) {
                                messagesListState.scrollToItem(visibleMessages.lastIndex + listHeaderItems)
                                previousItemsCount = visibleMessages.size
                                wasNearBottomBeforeIme = true
                            }
                        }
                        LaunchedEffect(visibleMessages.size) {
                            if (visibleMessages.size > previousItemsCount && visibleMessages.isNotEmpty()) {
                                val lastMessage = visibleMessages.lastOrNull()
                                val shouldFollowNewMessage = lastMessage?.senderId == me.id || isNearMessageListBottom
                                if (shouldFollowNewMessage) {
                                    messagesListState.aloScrollToNewMessage(visibleMessages.size + listHeaderItems)
                                } else {
                                    unseenBelow += visibleMessages.size - previousItemsCount
                                }
                            }
                            previousItemsCount = visibleMessages.size
                        }
                        // New messages are followed above, with Telegram's motion; this keeps the end in
                        // view while the keyboard opens.
                        LaunchedEffect(imeBottomPx) {
                            if (imeBottomPx > 0 && visibleMessages.isNotEmpty() && wasNearBottomBeforeIme) {
                                messagesListState.scrollToItem(visibleMessages.lastIndex + listHeaderItems)
                            }
                        }
                        AnimatedContent(
                        // Only the messages are blurred under the glass; the header and composer sit beside them.
                        modifier = Modifier.fillMaxSize().aloGlassSource(chatBackdrop, order = 1),
                        targetState = showPeerProfile,
                        transitionSpec = {
                            if (!targetState && suppressPeerProfileExitAnimation) {
                                fadeIn(animationSpec = tween(1)) togetherWith fadeOut(animationSpec = tween(1))
                            } else {
                                aloPageTransform(forward = targetState, shiftPx = pageShiftPx)
                            }
                        },
                        label = "messagesPeerPane"
                    ) { peerOpened ->
                        if (peerOpened) {
                            ChatPeerProfilePane(
                                ownerUserId = me.id,
                                detail = detail,
                                currentAppearance = chatAppearance,
                                isMuted = isChatMuted,
                                isPeerBlocked = isPeerBlocked,
                                startsWithSearch = profileOpensSearch,
                                onBack = onClosePeerProfile,
                                onInteractiveBack = {
                                    suppressPeerProfileExitAnimation = true
                                    onClosePeerProfile()
                                },
                                onOpenFullProfile = onOpenFullProfile,
                                onUpdateAppearance = onUpdateChatAppearance,
                                onToggleMuted = onToggleChatMuted,
                                onUpdateDirectChatAutoDelete = onUpdateDirectChatAutoDelete,
                                onUpdateGroupTitle = onUpdateGroupTitle,
                                onPickGroupAvatar = onPickGroupAvatar,
                                onAddGroupMember = onAddGroupMember,
                                onRemoveGroupMember = onRemoveGroupMember,
                                onLeaveGroupChat = onLeaveGroupChat,
                                onDeleteGroupChat = onDeleteGroupChat,
                                onUpdateGroupMemberRole = onUpdateGroupMemberRole,
                                groupManagement = groupManagement,
                                isGroupManagementLoading = isGroupManagementLoading,
                                onLoadGroupManagement = onLoadGroupManagement,
                                onLoadEarlierGroupAdminLog = onLoadEarlierGroupAdminLog,
                                onUpdateGroupSettings = onUpdateGroupSettings,
                                onCreateGroupTopic = onCreateGroupTopic,
                                onUpdateGroupTopic = onUpdateGroupTopic,
                                onDeleteGroupTopic = onDeleteGroupTopic,
                                onMergeGroupTopic = onMergeGroupTopic,
                                onReviewGroupJoinRequest = onReviewGroupJoinRequest,
                                onCreateGroupInviteLink = onCreateGroupInviteLink,
                                onUpdateGroupInviteLink = onUpdateGroupInviteLink,
                                onRevokeGroupInviteLink = onRevokeGroupInviteLink,
                                onLoadGroupInviteStats = onLoadGroupInviteStats,
                                onUpdateGroupMemberAccess = onUpdateGroupMemberAccess,
                                onTransferGroupOwnership = onTransferGroupOwnership,
                                onBanGroupMember = onBanGroupMember,
                                onUnbanGroupMember = onUnbanGroupMember,
                                onStartVoiceCall = onStartVoiceCall,
                                onStartDirectVideoCall = onStartVideoCall,
                                onStartVideoChat = onStartVideoCall,
                                onOpenMedia = onOpenMedia,
                                onOpenAttachment = onOpenAttachment
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                            ) {
                                if (visibleMessages.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                start = 18.dp,
                                                end = 18.dp,
                                                top = headerOverlayHeight,
                                                bottom = messagesBottomPadding
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ChatConversationEmptyState(
                                            isSaved = detail.entityType == "saved",
                                            isChannel = isChannelConversation
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize(),
                                        state = messagesListState,
                                        contentPadding = PaddingValues(
                                            start = 12.dp,
                                            top = headerOverlayHeight,
                                            end = 12.dp,
                                            bottom = messagesBottomPadding
                                        ),
                                        // Each message brings its own gap above it: the chat's spacing between runs, a closer one inside a run.
                                        verticalArrangement = Arrangement.Bottom
                                    ) {
                                        commentsThread?.post?.let { threadPost ->
                                            item(key = "comments-thread-post") {
                                                CommentsThreadPostHeader(post = threadPost, hasComments = visibleMessages.isNotEmpty(), bubbleColor = AloNativeChatIncoming)
                                            }
                                        }
                                        itemsIndexed(visibleMessages, key = { index, message -> messageKeys.getOrNull(index) ?: message.id }) { index, message ->
                                            val messageEnter = messageEnterState.enterFor(messageKeys.getOrNull(index) ?: message.id)
                                            // Telegram's ChatListItemAnimator: rows shift in 250ms on its chat curve;
                                            // an entering message makes its own way in instead of fading.
                                            val itemMotion = Modifier.animateItem(
                                                fadeInSpec = if (messageEnter != null) null else tween(180, easing = FastOutSlowInEasing),
                                                placementSpec = tween(AloChatMotionMs, easing = AloTelegramChatEasing),
                                                fadeOutSpec = tween(220, easing = LinearOutSlowInEasing)
                                            )
                                            val highlight = remember(message.id) { Animatable(0f) }
                                            val highlightRequest = highlightedMessage?.takeIf { it.first == message.id }
                                            LaunchedEffect(highlightRequest) {
                                                if (highlightRequest != null) {
                                                    highlight.snapTo(1f)
                                                    delay(700)
                                                    highlight.animateTo(0f, tween(300))
                                                }
                                            }
                                            val highlightColor = AloNativeAppAccent.copy(alpha = 0.12f)
                                            // Telegram's pinned top / bottom: a sender's consecutive messages form a run (not in channels, whose posts stand alone).
                                            val senderRun = if (isChannelConversation) {
                                                AloGroupRunPosition.Single
                                            } else {
                                                aloGroupRunPosition(
                                                    previous = visibleMessages.getOrNull(index - 1),
                                                    current = message,
                                                    next = visibleMessages.getOrNull(index + 1),
                                                    breaksRun = ::aloBreaksGroupRun,
                                                    dayOf = { formatMessageDayKey(it.createdAt) }
                                                )
                                            }
                                            val gapAbove = when {
                                                index == 0 -> 0.dp
                                                !senderRun.startsRun -> AloSeriesMetrics.GapInRun
                                                chatAppearance.compactSpacing -> 4.dp
                                                else -> 8.dp
                                            }
                                            CompositionLocalProvider(LocalAloMessageEnter provides messageEnter, LocalAloMusicQueue provides musicQueue) {
                                            Column(
                                                // At the item's root, where the list sees it: a removed message fades out and the rest slide into its place.
                                                modifier = itemMotion.padding(top = gapAbove),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (
                                                    detail.groupPolicy?.isForum == true &&
                                                    message.topicId > 0 &&
                                                    commentsThread == null &&
                                                    (
                                                        index == 0 ||
                                                            visibleMessages[index - 1].topicId != message.topicId
                                                        )
                                                ) {
                                                    detail.forumTopics.firstOrNull { it.id == message.topicId }?.let { topic ->
                                                        Box(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            ForumTopicPill(topic = topic)
                                                        }
                                                    }
                                                }
                                                if (
                                                    index == 0 ||
                                                    formatMessageDayKey(visibleMessages[index - 1].createdAt) !=
                                                    formatMessageDayKey(message.createdAt)
                                                ) {
                                                    Box(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ChatDatePill(label = formatMessageDateLabel(message.createdAt))
                                                    }
                                                }
                                                serviceLines.before[message.id]?.forEach { line ->
                                                    Box(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ChatDatePill(label = line.text)
                                                    }
                                                }
                                                // Only the message is lit, not the day pill or topic label above it; across the whole width, past the list's 12dp side padding.
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().drawBehind {
                                                        val h = highlight.value
                                                        if (h > 0f) {
                                                            val side = 12.dp.toPx()
                                                            drawRect(
                                                                highlightColor.copy(alpha = highlightColor.alpha * h),
                                                                topLeft = androidx.compose.ui.geometry.Offset(-side, 0f),
                                                                size = androidx.compose.ui.geometry.Size(size.width + side * 2, size.height)
                                                            )
                                                        }
                                                    }
                                                ) {
                                                val systemEvent = AloChatSystemEventCodec.decode(message.body)
                                                val groupCallMeta = message.toCallLogMeta()
                                                    ?.takeIf { it.scope == "group" }
                                                if (systemEvent != null) {
                                                    ChatSystemEventPill(
                                                        event = systemEvent,
                                                        isMine = message.senderId == me.id,
                                                        modifier = Modifier
                                                    )
                                                } else if (groupCallMeta != null) {
                                                    GroupCallHistoryEvent(
                                                        message = message,
                                                        meta = groupCallMeta,
                                                        modifier = Modifier
                                                    )
                                                } else {
                                                    MessageBubble(
                                                // Telegram's swipe left to reply.
                                                modifier = Modifier.aloSwipeToReply(
                                                    enabled = canComposeMessages && !isSelectionMode && !isPeerBlocked && message.id > 0,
                                                    outgoing = message.senderId == me.id && !isChannelConversation,
                                                    onReply = { onStartReply(message) }
                                                ),
                                                me = me,
                                                detail = detail,
                                                message = message,
                                                replyToMessage = messagesById[message.replyToMessageId],
                                                pollState = (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
                                                    ?.definition
                                                    ?.let { pollStates[it.id] },
                                                forceIncoming = isChannelConversation,
                                                // The first of a sender's run shows the name, the last the avatar (groups); the run also joins the bubbles' corners.
                                                groupRun = senderRun,
                                                // The avatar of a run is drawn over the list, sticky (AloStickyRunAvatars below).
                                                drawsGroupAvatar = false,
                                                isPending = pendingMessageIds.contains(message.id),
                                                onOpenMedia = onOpenMedia,
                                                onOpenMediaGallery = onOpenMediaGallery,
                                                onPrepareMediaOpen = onPrepareMediaOpen,
                                                onOpenAttachment = onOpenAttachment,
                                                onOpenDiscussion = onOpenChannelPostDiscussion,
                                                onOpenProfile = onOpenProfile,
                                                onOpenReply = { jumpToMessage(it) },
                                                onVoicePlaybackRequested = {},
                                                onReact = { emoji ->
                                                    AloReactionEffects.request(message.id, emoji)
                                                    onToggleReaction(message.id, emoji)
                                                },
                                                onPollVote = { optionId ->
                                                    val state = (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
                                                        ?.definition
                                                        ?.let { pollStates[it.id] }
                                                    if (
                                                        state != null &&
                                                        !state.isClosed &&
                                                        (state.allowsRevoting || state.selectedOptionIds.isEmpty())
                                                    ) {
                                                        val selected = state.selectedOptionIds.toMutableSet()
                                                        if (state.definition.allowsMultipleAnswers) {
                                                            if (!selected.add(optionId)) selected.remove(optionId)
                                                        } else {
                                                            if (selected == setOf(optionId)) selected.clear()
                                                            else {
                                                                selected.clear()
                                                                selected.add(optionId)
                                                            }
                                                        }
                                                        AloPollCodec.encodeVote(state.definition.id, selected)
                                                            ?.let { body ->
                                                                onSendChatSystemEvent(body, state.definition)
                                                            }
                                                    }
                                                },
                                                canClosePoll = message.senderId == me.id,
                                                onClosePoll = {
                                                    val pollId = (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
                                                        ?.definition
                                                        ?.id
                                                    if (!pollId.isNullOrBlank()) onClosePoll(pollId)
                                                },
                                                selectionMode = isSelectionMode,
                                                selected = selectedMessages.containsKey(message.id),
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        toggleMessageSelection(message)
                                                    }
                                                },
                                                onLongPress = {
                                                    if (isSelectionMode) {
                                                        toggleMessageSelection(message)
                                                    } else {
                                                        selectedMessageForMenu = message
                                                    }
                                                }
                                                )
                                                }
                                                }
                                            }
                                            }
                                        }
                                        // A join or leave newer than every message goes under the last one.
                                        if (serviceLines.after.isNotEmpty()) {
                                            item(key = "group-service-tail") {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    serviceLines.after.forEach { line -> ChatDatePill(label = line.text) }
                                                }
                                            }
                                        }
                                    }
                                    // Group members' avatars over the list, sticky to the bottom while a run goes on below it.
                                    if (detail.kind == "group" && !isChannelConversation && !isSelectionMode) {
                                        AloStickyRunAvatars(
                                            listState = messagesListState,
                                            messages = visibleMessages,
                                            listHeaderItems = listHeaderItems,
                                            meId = me.id,
                                            dayOf = { formatMessageDayKey(it.createdAt) },
                                            onOpenProfile = onOpenProfile
                                        )
                                    }
                                }

                            }
                        }
                    }
                    }
                    }

                    if (!showPeerProfile) {
                        ChatScreenEdgeFades(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(3.5f),
                            topHeight = 96.dp,
                            bottomHeight = if (imeVisible) 54.dp else 72.dp
                        )
                    }

                    AnimatedVisibility(
                        visible = floatingDateVisible &&
                            floatingDateLabel.isNotBlank() &&
                            !floatingDateCoincides &&
                            selectedMessageForMenu == null &&
                            !showPeerProfile,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(3.8f)
                            .padding(top = headerOverlayHeight + 4.dp),
                        enter = fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(150, easing = LinearOutSlowInEasing)),
                        label = "floatingChatDate"
                    ) {
                        ChatDatePill(label = floatingDateLabel)
                    }

                    CompositionLocalProvider(LocalAloGlassBackdrop provides chatBackdrop.takeIf { !showPeerProfile }) {
                    if (
                        detail != null &&
                        !showPeerProfile &&
                        !isAttachmentSheetOpen
                    ) {
                        if (isPeerBlocked) {
                            BlockedPeerComposer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(4f)
                                    .fillMaxWidth()
                                    .padding(bottom = composerSystemBottomPadding)
                                    .onSizeChanged { composerHeightPx = it.height },
                                isUpdating = isBlockActionInProgress,
                                onUnblock = {
                                    detail?.takeIf { it.kind == "direct" }?.let {
                                        onUpdateUserBlock(it.peerId, false)
                                    }
                                }
                            )
                        } else if (canComposeMessages) {
                            AloLiveText(composerValue) { composerText ->
                            MessageComposer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(4f)
                                    .fillMaxWidth()
                                    .padding(bottom = composerSystemBottomPadding)
                                    .onSizeChanged { composerHeightPx = it.height },
                                value = composerText,
                                recordMode = recorderMode,
                                captureState = captureState,
                                recordingDurationSec = recordingDurationSec(),
                                recordingLevels = recordingLevels(),
                                draftAttachment = draftAttachment,
                                selfDestructSeconds = selfDestructSeconds,
                                forwardDraft = forwardDraft,
                                editingMessage = detail.items.firstOrNull { it.id == editingMessageId },
                                replyMessage = replyingToMessage,
                                mentionMembers = if (detail.kind == "group") detail.members else emptyList(),
                                isSending = isSending,
                                onValueChange = onComposerChange,
                                onSend = onSend,
                                onSendSilently = onSendSilently,
                                canSendWhenOnline = canSendWhenOnline,
                                onSendWhenOnline = onSendWhenOnline,
                                onScheduleSend = onScheduleSend,
                                onScheduleRepeat = onScheduleRepeat,
                                onPickAttachment = {
                                    expressionPanelOpen = false
                                    expressionSearching = false
                                    isAttachmentSheetOpen = true
                                },
                                draftAttachments = draftAttachments,
                                onToggleRecorderMode = onToggleRecorderMode,
                                onStartRecording = onStartRecording,
                                onStopRecording = { cancel ->
                                    if (recordingCircle) {
                                        circleControl.send(if (cancel) AloCircleCommand.Cancel else AloCircleCommand.Preview)
                                    } else {
                                        onStopRecording(cancel)
                                    }
                                },
                                onSendRecording = {
                                    if (recordingCircle) circleControl.send(AloCircleCommand.Send) else onSendRecording()
                                },
                                onRecordingHeldChange = { circleControl.isHeld = it },
                                onDiscardDraft = onDiscardDraft,
                                onRemoveDraftAttachment = onRemoveDraftAttachment,
                                onSelfDestructSecondsChange = onSelfDestructSecondsChange,
                                onOpenDraftMediaGallery = onOpenMediaGallery,
                                onSendDraft = onSendDraft,
                                onDiscardForwardDraft = onDiscardForwardDraft,
                                onCancelEditing = onCancelEdit,
                                onCancelReply = onCancelReply,
                                onChangeForwardTarget = { isForwardRecipientSheetOpen = true },
                                expressionPanelOpen = expressionPanelOpen,
                                onToggleExpressionPanel = ::toggleExpressionPanel,
                                onFieldTapped = ::closeExpressionPanelForKeyboard
                            )
                            }
                        } else if (isChannelConversation) {
                            ChannelSubscriberBar(
                                isMuted = isChatMuted,
                                isMember = detail?.isMember != false,
                                giftRecipient = detail?.members?.firstOrNull { it.role == "owner" }?.let { it.user.id to it.user.name },
                                onJoin = onJoinChannel,
                                onToggleMuted = onToggleChatMuted,
                                onSearch = {
                                    profileOpensSearch = true
                                    onOpenPeerProfile()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(4f)
                                    .fillMaxWidth()
                                    .padding(bottom = composerSystemBottomPadding)
                                    .onSizeChanged { composerHeightPx = it.height }
                            )
                        } else {
                            ChannelReadOnlyComposer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(4f)
                                    .fillMaxWidth()
                                    .padding(bottom = composerSystemBottomPadding)
                                    .onSizeChanged { composerHeightPx = it.height }
                            )
                        }
                    }

                    if (panelDp > 0.dp && detail != null && !showPeerProfile && !isAttachmentSheetOpen) {
                        val panelFullDp = maxOf(lastKeyboardDp, panelDp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(3.9f)
                                .fillMaxWidth()
                                .height(panelDp)
                                .clipToBounds()
                        ) {
                            AloExpressionPanel(
                                modifier = Modifier
                                    .wrapContentHeight(Alignment.Top, unbounded = true)
                                    .requiredHeight(panelFullDp)
                                    .offset { IntOffset(0, (panelFullDp - panelDp).coerceAtLeast(0.dp).roundToPx()) },
                                tab = expressionTab,
                                onTabChange = {
                                    expressionTabName = it.name
                                    expressionSearching = false
                                },
                                searching = expressionSearching,
                                onSearchingChange = { expressionSearching = it },
                                onInsertEmoji = { emoji -> onComposerChange(composerValue() + emoji) },
                                onBackspace = { onComposerChange(aloDropLastGrapheme(composerValue())) },
                                onSendCatalogExpression = onSendCatalogExpression
                            )
                        }
                    }

                    // A sent message flies above the composer into its place, as in Telegram.
                    AloMessageFlightOverlay(messageEnterState, Modifier.matchParentSize().zIndex(4.05f))

                    AloJumpDownButton(
                        visible = !isNearMessageListBottom && visibleMessages.isNotEmpty() && !isSelectionMode &&
                            !showPeerProfile && captureState != AloChatCaptureState.Recording && selectedMessageForMenu == null,
                        count = unseenBelow,
                        onClick = { scope.launch { messagesListState.aloScrollToLatest(visibleMessages.size) } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .zIndex(3.95f)
                            .padding(end = 12.dp, bottom = jumpButtonBottom)
                    )

                    if (isSelectionMode) {
                        MessagesSelectionHeader(
                            selectedCount = selectedMessages.size,
                            onCancel = { selectedMessages.clear() },
                            onForward = if (isProtectedChat) null else {
                                { forwardingMessages = orderedSelectedMessages() }
                            },
                            onDelete = {
                                onDeleteMessages(orderedSelectedMessages().map { it.id }, false)
                                selectedMessages.clear()
                            }
                        )
                    } else if (!showPeerProfile) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(4f)
                        ) {
                            MessagesChatHeader(
                                detail = detail,
                                threadTitle = commentsThread?.let { aloCommentsTitle(visibleMessages.count { it.id > 0 }) },
                                threadSubtitle = commentsThread?.post?.author?.name,
                                isLoading = isLoading,
                                isOfflineCached = isOfflineCached,
                                isPeerBlocked = isPeerBlocked,
                                isBlockActionInProgress = isBlockActionInProgress,
                                onBack = {
                                    if (showPeerProfile) {
                                        onBack()
                                    } else {
                                        closeChatWithSlide()
                                    }
                                },
                                onOpenPeerProfile = onOpenPeerProfile,
                                onTogglePeerBlock = {
                                    detail?.takeIf { it.kind == "direct" }?.let {
                                        onUpdateUserBlock(it.peerId, !isPeerBlocked)
                                    }
                                },
                                onStartVoiceCall = onStartVoiceCall,
                                onStartVideoCall = onStartVideoCall
                            )
                        }
                    }
                    }

                    AnimatedVisibility(
                        visible = pinnedMessage != null && !showPeerProfile && !isSelectionMode,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(4.1f),
                        enter = fadeIn(tween(200)) + slideInVertically(tween(280, easing = AloTelegramDecelerate)) { -it / 2 },
                        exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 2 }
                    ) {
                        val shownPinned = pinnedMessage ?: pinnedHolder[0]
                        if (shownPinned != null) {
                            ChatPinnedMessageBar(
                                message = shownPinned,
                                count = pinnedMessages.size,
                                modifier = Modifier
                                    .padding(top = AloChatOverlayMetrics.OverlayTop, start = AloChatOverlayMetrics.SideInset, end = AloChatOverlayMetrics.SideInset),
                                onClick = {
                                    val messageIndex = visibleMessages.indexOfFirst { it.id == shownPinned.id }
                                    if (messageIndex >= 0) jumpToMessage(shownPinned.id)
                                }
                            )
                        }
                    }

                    if (
                        forumSelectorVisible &&
                        !showPeerProfile &&
                        !isSelectionMode
                    ) {
                        ForumTopicSelector(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(4.1f)
                                .padding(top = AloChatOverlayMetrics.OverlayTop + pinnedBarHeight),
                            topics = detail.forumTopics,
                            states = detail.forumTopicStates,
                            activeTopicId = activeForumTopicId,
                            onSelect = onSelectForumTopic,
                            onToggleMuted = onToggleForumTopicMuted,
                            onCopyActiveTopicLink = {
                                detail.forumTopics
                                    .firstOrNull { topic ->
                                        topic.id == activeForumTopicId && !topic.isHidden
                                    }
                                    ?.let { topic ->
                                        AloDeepLinkTarget.forumTopicUrl(detail.roomId, topic.id)
                                            ?.let { url ->
                                                clipboardManager.setText(AnnotatedString(url))
                                            }
                                    }
                            },
                            isSearchVisible = isForumSearchVisible,
                            onToggleSearch = {
                                isForumSearchVisible = !isForumSearchVisible
                                if (!isForumSearchVisible) forumSearchText = ""
                            }
                        )
                    }

                    if (
                        forumSelectorVisible &&
                        isForumSearchVisible &&
                        !showPeerProfile &&
                        !isSelectionMode
                    ) {
                        ForumTopicSearchField(
                            value = forumSearchText,
                            onValueChange = { forumSearchText = it },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(4.1f)
                                .padding(top = AloChatOverlayMetrics.OverlayTop + 42.dp + pinnedBarHeight, start = AloChatOverlayMetrics.SideInset, end = AloChatOverlayMetrics.SideInset)
                        )
                    }

                    AnimatedVisibility(
                        visible = activeVoiceMessageId != null || activeCircleMessageId != null,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(3.9f),
                        enter = fadeIn(animationSpec = tween(140, easing = FastOutSlowInEasing)) +
                            slideInVertically(
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                initialOffsetY = { -it / 2 }
                            ),
                        exit = fadeOut(animationSpec = tween(120, easing = LinearOutSlowInEasing)) +
                            slideOutVertically(
                                animationSpec = tween(160, easing = LinearOutSlowInEasing),
                                targetOffsetY = { -it / 2 }
                            ),
                        label = "activeVoicePlaybackBar"
                    ) {
                        val barModifier = Modifier.padding(
                            top = AloChatOverlayMetrics.OverlayTop + pinnedBarHeight + forumSelectorHeight,
                            start = AloChatOverlayMetrics.SideInset,
                            end = AloChatOverlayMetrics.SideInset
                        )
                        if (activeCircleMessageId != null) {
                            ActiveVoicePlaybackBar(
                                modifier = barModifier,
                                title = AloCirclePlayer.title,
                                subtitle = if (AloCirclePlayer.durationMs > 0) {
                                    "Видеосообщение · ${formatPlaybackDuration(AloCirclePlayer.positionMs)} / ${formatPlaybackDuration(AloCirclePlayer.durationMs)}"
                                } else {
                                    "Видеосообщение"
                                },
                                isPlaying = AloCirclePlayer.playing,
                                speedLabel = null,
                                onTogglePlayback = { if (AloCirclePlayer.playing) AloCirclePlayer.pause() else AloCirclePlayer.resume() },
                                onSpeedClick = {},
                                onClose = { AloCirclePlayer.stop() }
                            )
                        } else {
                            ActiveVoicePlaybackBar(
                                modifier = barModifier,
                                title = AloVoicePlayer.title,
                                subtitle = if (AloVoicePlayer.durationMs > 0) {
                                    "Голосовое · ${formatPlaybackDuration(AloVoicePlayer.positionMs)} / ${formatPlaybackDuration(AloVoicePlayer.durationMs)}"
                                } else {
                                    "Голосовое сообщение"
                                },
                                isPlaying = AloVoicePlayer.playing,
                                speedLabel = formatVoicePlaybackSpeed(AloVoicePlayer.speed),
                                onTogglePlayback = { if (AloVoicePlayer.playing) AloVoicePlayer.pause() else AloVoicePlayer.resume() },
                                onSpeedClick = { AloVoicePlayer.cycleSpeed() },
                                onClose = { AloVoicePlayer.stop() }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = musicBarShown,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(3.9f),
                        enter = fadeIn(animationSpec = tween(140, easing = FastOutSlowInEasing)) +
                            slideInVertically(animationSpec = tween(180, easing = FastOutSlowInEasing), initialOffsetY = { -it / 2 }),
                        exit = fadeOut(animationSpec = tween(120, easing = LinearOutSlowInEasing)) +
                            slideOutVertically(animationSpec = tween(160, easing = LinearOutSlowInEasing), targetOffsetY = { -it / 2 }),
                        label = "musicPlaybackBar"
                    ) {
                        AloMusicPlaybackBar(
                            modifier = Modifier.padding(
                                top = AloChatOverlayMetrics.OverlayTop + pinnedBarHeight + forumSelectorHeight,
                                start = AloChatOverlayMetrics.SideInset,
                                end = AloChatOverlayMetrics.SideInset
                            ),
                            onOpenMessage = { jumpToMessage(it) }
                        )
                    }

                    // Under the composer (zIndex 4), as Telegram puts the round camera between the chat and the input panel.
                    if (!showPeerProfile && recorderMode == AloChatRecordMode.VideoCircle && captureState == AloChatCaptureState.Recording) {
                        VideoCircleRecorderOverlay(
                            bottomInset = composerOverlayHeight,
                            onCancel = { onStopRecording(true) },
                            onRecorded = onCompleteVideoCircleRecording,
                            onRecordedSend = onSendVideoCircleRecording,
                            control = circleControl,
                            backdrop = chatBackdrop
                        )
                    }

                    if (isAttachmentSheetOpen) {
                        ChatAttachmentSheet(
                            capabilities = attachmentCapabilities,
                            contacts = shareableContacts,
                            onDismiss = { isAttachmentSheetOpen = false },
                            onPickGalleryUri = { uri ->
                                isAttachmentSheetOpen = false
                                onPickGalleryUri(uri)
                            },
                            onPickGalleryUris = { uris ->
                                isAttachmentSheetOpen = false
                                onPickGalleryUris(uris)
                            },
                            onPickFile = {
                                isAttachmentSheetOpen = false
                                onPickAttachment()
                            },
                            onShareContact = { contact ->
                                isAttachmentSheetOpen = false
                                onShareContact(contact)
                            },
                            onShareStaticLocation = { latitude, longitude, title, address ->
                                isAttachmentSheetOpen = false
                                onShareStaticLocation(latitude, longitude, title, address)
                            },
                            onShareLiveLocation = { request ->
                                isAttachmentSheetOpen = false
                                onShareLiveLocation(request)
                            },
                            onStartVoice = {
                                isAttachmentSheetOpen = false
                                if (recorderMode != AloChatRecordMode.Voice) {
                                    onToggleRecorderMode()
                                }
                                onStartRecording()
                            },
                            onStartCircle = {
                                isAttachmentSheetOpen = false
                                if (recorderMode != AloChatRecordMode.VideoCircle) {
                                    onToggleRecorderMode()
                                }
                                onStartRecording()
                            },
                            onCreatePoll = {
                                isAttachmentSheetOpen = false
                                isPollComposerOpen = true
                            }
                        )
                    }

                    if (isPollComposerOpen) {
                        ChatPollComposerSheet(
                            onDismiss = { isPollComposerOpen = false },
                            onCreate = { definition, schedule ->
                                onCreatePoll(definition, schedule)
                                isPollComposerOpen = false
                            }
                        )
                    }
                }

                    selectedMessageForMenu?.let { selectedMessage ->
                        ChatMessageActionSheet(
                            message = selectedMessage,
                            isMine = selectedMessage.senderId == me.id,
                            canEdit = canEditMessage(selectedMessage),
                            canDeleteForEveryone = canDeleteMessageForEveryone(selectedMessage),
                            canPin = canPinMessages,
                            isPinned = detail?.pinnedMessageIds?.contains(selectedMessage.id) == true,
                            isProtected = isProtectedChat,
                            bottomInset = composerOverlayHeight,
                            onDismiss = { selectedMessageForMenu = null },
                            onCopy = {
                                val copyText = buildConversationPreview(selectedMessage)
                                clipboardManager.setText(AnnotatedString(copyText))
                                selectedMessageForMenu = null
                            },
                            onDeleteForMe = {
                                onDeleteMessage(selectedMessage.id, false)
                                selectedMessageForMenu = null
                            },
                            onDeleteForEveryone = {
                                onDeleteMessage(selectedMessage.id, true)
                                selectedMessageForMenu = null
                            },
                            onReact = { emoji ->
                                AloReactionEffects.request(selectedMessage.id, emoji)
                                onToggleReaction(selectedMessage.id, emoji)
                                selectedMessageForMenu = null
                            },
                            onEdit = {
                                onEditMessage(selectedMessage.id)
                                selectedMessageForMenu = null
                            },
                            onReply = {
                                onStartReply(selectedMessage)
                                selectedMessageForMenu = null
                            },
                            onForward = {
                                forwardingMessages = listOf(selectedMessage)
                                selectedMessageForMenu = null
                            },
                            onPin = {
                                onToggleMessagePin(selectedMessage.id)
                                selectedMessageForMenu = null
                            },
                            onSelect = {
                                selectedMessages[selectedMessage.id] = selectedMessage
                                selectedMessageForMenu = null
                            },
                            onReschedule = {
                                onRescheduleMessage(selectedMessage.id)
                                selectedMessageForMenu = null
                            },
                            onViewScheduled = {
                                selectedMessageForMenu = null
                                isScheduledMessagesOpen = true
                            },
                            onCancelScheduled = {
                                onCancelScheduledMessage(selectedMessage.id)
                                selectedMessageForMenu = null
                            },
                            canTranslate = contentTools?.canTranslate(selectedMessage, isProtectedChat) == true,
                            hasTranslation = contentTools?.translations?.containsKey(selectedMessage.id) == true,
                            onTranslate = { message ->
                                selectedMessageForMenu = null
                                contentTools?.languagePickerFor = message
                            },
                            onHideTranslation = { message ->
                                selectedMessageForMenu = null
                                contentTools?.hideTranslation(message.id)
                            }
                        )
                    }

                    if (contentTools != null) {
                        contentTools.languagePickerFor?.let { message ->
                            AloTranslationLanguageSheet(
                                languages = contentTools.pickerLanguages(),
                                onDismiss = { contentTools.languagePickerFor = null },
                                onPick = { language ->
                                    contentTools.translate(message, language, detail?.kind.orEmpty(), detail?.roomId ?: 0)
                                }
                            )
                        }
                        contentTools.consentRequest?.let { request ->
                            AloContentToolsConsentDialog(
                                transcription = request.transcription,
                                onConfirm = contentTools::confirmConsent,
                                onDismiss = contentTools::declineConsent
                            )
                        }
                    }

                    if (isScheduledMessagesOpen) {
                        ChatScheduledMessagesSheet(
                            messages = scheduledMessages,
                            onDismiss = { isScheduledMessagesOpen = false },
                            onReschedule = { message ->
                                isScheduledMessagesOpen = false
                                onRescheduleMessage(message.id)
                            },
                            onCancel = { message ->
                                onCancelScheduledMessage(message.id)
                            }
                        )
                    }

                    if (forwardingMessages.isNotEmpty()) {
                        ForwardMessageTargetSheet(
                            conversations = forwardableConversations,
                            currentKind = detail?.kind.orEmpty(),
                            currentPeerId = detail?.peerId ?: 0,
                            currentRoomId = detail?.roomId ?: 0,
                            selectedCount = forwardingMessages.size,
                            bottomInset = composerOverlayHeight,
                            onDismiss = {
                                val preservedMessages = forwardingMessages
                                selectedMessages.clear()
                                preservedMessages.forEach { preserved ->
                                    selectedMessages[preserved.id] = preserved
                                }
                                forwardingMessages = emptyList()
                            },
                            onSelect = { target ->
                                if (forwardingMessages.size == 1) {
                                    onForwardMessage(forwardingMessages.first(), target)
                                } else {
                                    onForwardMessages(forwardingMessages, target)
                                }
                                forwardingMessages = emptyList()
                                selectedMessages.clear()
                            }
                        )
                    }

                    if (isForwardRecipientSheetOpen) {
                        ForwardMessageTargetSheet(
                            conversations = forwardableConversations,
                            currentKind = detail?.kind.orEmpty(),
                            currentPeerId = detail?.peerId ?: 0,
                            currentRoomId = detail?.roomId ?: 0,
                            selectedCount = forwardDraft.size.coerceAtLeast(1),
                            bottomInset = composerOverlayHeight,
                            onDismiss = { isForwardRecipientSheetOpen = false },
                            onSelect = { target ->
                                isForwardRecipientSheetOpen = false
                                onRetargetForwardDraft(target)
                            }
                        )
                    }

                }
            }
        }
    }
}

/**
 * A subscriber's bar under a channel, as in Telegram (ChatChannelSubscriberInputPanelNode, same numbers on every Alo
 * client): a glass capsule at least 165dp wide and 40dp tall with «Убрать звук» / «Включить звук» (17sp medium),
 * centred on the screen, and a 40dp round search button on the right.
 */
@Composable
private fun ChannelSubscriberBar(
    isMuted: Boolean,
    onToggleMuted: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    isMember: Boolean = true,
    /** The channel's author (id, name): the gift button gives them Alo Премиум, shown once Премиум is on. */
    giftRecipient: Pair<Int, String>? = null,
    onJoin: () -> Unit = {}
) {
    var giftOpen by remember { mutableStateOf(false) }
    if (giftOpen && giftRecipient != null) {
        val context = LocalContext.current
        val giftApi = remember { AloApiClient(baseUrl = AloServerConfiguration.apiBaseUrl, applicationContext = context.applicationContext) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { giftOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
                AloPremiumSettingsPage(api = giftApi, onOpenLook = {}, giftRecipient = giftRecipient)
                Text(
                    "Закрыть",
                    color = AloNativeAppAccent,
                    fontSize = 17.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).clickable { giftOpen = false }
                )
            }
        }
    }
    Box(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (giftRecipient != null && AloPremiumDirectory.enabled) {
            AloNativeGlassPanel(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { giftOpen = true },
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Rounded.CardGiftcard,
                    contentDescription = "Подарить Премиум автору",
                    tint = AloNativeAppText,
                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                )
            }
        }
        if (!isMember) {
            // A channel read before subscribing: «Подписаться» in the accent colour (Telegram's join panel).
            Box(
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .heightIn(min = 40.dp)
                    .widthIn(min = 165.dp)
                    .clip(RoundedCornerShape(50))
                    .background(AloNativeAppAccent)
                    .clickable(onClick = onJoin),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Подписаться",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                )
            }
        } else
        AloNativeGlassPanel(
            modifier = Modifier
                .padding(horizontal = 48.dp)
                .heightIn(min = 40.dp)
                .widthIn(min = 165.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onToggleMuted),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = if (isMuted) "Включить звук" else "Убрать звук",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = AloNativeAppText,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            )
        }
        AloNativeGlassPanel(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSearch),
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Поиск по каналу",
                tint = AloNativeAppText,
                modifier = Modifier.align(Alignment.Center).size(24.dp)
            )
        }
    }
}

@Composable
private fun ChannelReadOnlyComposer(modifier: Modifier = Modifier) {
    AloNativeGlassPanel(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        shape = AloSquircle(22.dp),
        tint = AloNativeAppSurface.copy(alpha = 0.94f),
        borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "У вас нет права публиковать в этом канале",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BlockedPeerComposer(
    modifier: Modifier = Modifier,
    isUpdating: Boolean,
    onUnblock: () -> Unit
) {
    AloNativeGlassPanel(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        shape = AloSquircle(22.dp),
        tint = AloNativeAppSurface.copy(alpha = 0.94f),
        borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Пользователь заблокирован",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(enabled = !isUpdating, onClick = onUnblock) {
                Text("Разблокировать")
            }
        }
    }
}

@Composable
private fun ForumTopicSelector(
    topics: List<AloForumTopic>,
    states: List<AloForumTopicState>,
    activeTopicId: Int,
    onSelect: (Int) -> Unit,
    onToggleMuted: (Int) -> Unit,
    onCopyActiveTopicLink: () -> Unit,
    isSearchVisible: Boolean,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        topics.filterNot { it.isHidden }.forEach { topic ->
            val active = topic.id == activeTopicId
            val state = states.firstOrNull { it.topicId == topic.id }
            Surface(
                modifier = Modifier
                    .height(34.dp)
                    .clip(AloSquircle(17.dp))
                    .clickable(enabled = !topic.isClosed) { onSelect(topic.id) },
                shape = AloSquircle(17.dp),
                color = if (active) {
                    AloNativeAppAccent.copy(alpha = 0.96f)
                } else {
                    AloNativeAppSurface.copy(alpha = 0.88f)
                },
                border = BorderStroke(
                    1.dp,
                    if (active) Color.White.copy(alpha = 0.12f)
                    else AloNativeAppBorder.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (topic.iconEmoji.isNotBlank()) {
                        Text(text = topic.iconEmoji, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = when {
                            topic.isClosed -> AloNativeAppMuted
                            active -> AloNativeAppButtonText
                            else -> AloNativeAppText
                        },
                        maxLines = 1
                    )
                    if (topic.isClosed) {
                        Text(
                            text = "закрыта",
                            style = MaterialTheme.typography.labelSmall,
                            color = AloNativeAppMuted
                        )
                    }
                    if (state?.muted == true) {
                        Text(
                            text = "🔕",
                            modifier = Modifier.clickable {
                                onToggleMuted(topic.id)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else if (active) {
                        Text(
                            text = "🔔",
                            modifier = Modifier.clickable {
                                onToggleMuted(topic.id)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if ((state?.unreadCount ?: 0) > 0) {
                        Surface(
                            shape = CircleShape,
                            color = if (active) {
                                Color.White.copy(alpha = 0.22f)
                            } else {
                                AloNativeAppAccent
                            }
                        ) {
                            Text(
                                text = state?.unreadCount.toString(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AloNativeAppButtonText
                            )
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onCopyActiveTopicLink),
            shape = CircleShape,
            color = AloNativeAppSurface.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Скопировать ссылку на тему",
                    modifier = Modifier.size(17.dp),
                    tint = AloNativeAppText
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleSearch),
            shape = CircleShape,
            color = if (isSearchVisible) AloNativeAppAccent else AloNativeAppSurface.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isSearchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (isSearchVisible) "Закрыть поиск" else "Поиск в теме",
                    modifier = Modifier.size(17.dp),
                    tint = if (isSearchVisible) AloNativeAppButtonText else AloNativeAppText
                )
            }
        }
    }
}

@Composable
private fun ForumTopicSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        shape = AloSquircle(18.dp),
        color = AloNativeAppSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = AloNativeAppMuted
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AloNativeAppText),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text("Поиск в теме", color = AloNativeAppMuted)
                        }
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Очистить",
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    tint = AloNativeAppMuted
                )
            }
        }
    }
}

@Composable
private fun ChatPinnedMessageBar(
    message: AloChatMessage,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val preview = message.body.ifBlank {
        when {
            message.toCallLogMeta() != null -> "Звонок"
            message.attachmentName.isNotBlank() -> message.attachmentName
            message.attachmentUrl.isNotBlank() -> "Вложение"
            else -> "Сообщение"
        }
    }
    AloNativeGlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(AloSquircle(17.dp))
            .clickable(onClick = onClick),
        shape = AloSquircle(17.dp),
        tint = AloNativeAppSurface.copy(alpha = 0.92f),
        borderColor = AloNativeAppBorder.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(AloNativeAppAccent)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (count > 1) "Закреплённые сообщения · $count" else "Закреплённое сообщение",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AloNativeAppAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = AloNativeAppMuted
            )
        }
    }
}

@Composable
private fun ForumTopicPill(topic: AloForumTopic) {
    Surface(
        shape = AloSquircle(14.dp),
        color = AloNativeAppSurface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
    ) {
        Text(
            text = buildString {
                if (topic.iconEmoji.isNotBlank()) append(topic.iconEmoji).append(' ')
                append(topic.title)
                if (topic.isClosed) append(" · закрыта")
            },
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (topic.isClosed) AloNativeAppMuted else AloNativeAppText
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessagesChatHeader(
    detail: AloConversationDetail?,
    threadTitle: String? = null,
    threadSubtitle: String? = null,
    isLoading: Boolean,
    isOfflineCached: Boolean,
    isPeerBlocked: Boolean,
    isBlockActionInProgress: Boolean,
    onBack: () -> Unit,
    /** How much of the inbox the chat still covers while it is swiped away, for Telegram's parallax. */
    onSwipeCover: (Float) -> Unit = {},
    onOpenPeerProfile: () -> Unit,
    onTogglePeerBlock: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit
) {
    val isLightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val isChannelConversation = detail?.isChannelCommunity() == true
    val isDirectUserConversation = detail?.kind == "direct" && detail.entityType != "saved"
    val showCallActions = detail?.entityType != "saved" &&
        !isChannelConversation &&
        !(isDirectUserConversation && isPeerBlocked)
    var peerActionsExpanded by remember(detail?.peerId) { mutableStateOf(false) }
    val headerInteraction = remember { MutableInteractionSource() }
    val headerPressed by headerInteraction.collectIsPressedAsState()
    val headerScale by animateFloatAsState(
        targetValue = if (headerPressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.64f, stiffness = Spring.StiffnessMedium),
        label = "chatHeaderPress"
    )
    val subtitleColor = when {
        detail?.online == true && !isLightSurface -> AloNativeAppAccent
        detail?.online == true && isLightSurface -> Color(0xFF2D8B57)
        else -> AloNativeAppMuted
    }
    val opensProfile = detail != null && detail.entityType != "saved"
    // iOS's chat header (MessagesView.header) in Telegram Android's sizes (ChatAvatarContainer): a
    // 56dp bar where the round glass back button, the title capsule and the 42dp avatar share one
    // height; title 18sp medium, subtitle 14sp. A long press offers calls, as iOS's context menu.
    val itemSize = 42.dp
    Row(
        modifier = Modifier
            .zIndex(3f)
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AloNativeGlassPanel(shape = CircleShape) {
            Box(
                modifier = Modifier
                    .size(itemSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                // iOS AloChatCircleButton: chevron.left, 21 pt regular, on the glass circle.
                AloChevronLeft(21.dp, AloNativeAppText, Modifier.semantics { contentDescription = "Назад к чатам" }, AloChevronWeight.Regular)
            }
        }
        // The capsule hugs its text, centred on the screen, as on the iPhone and the site; a long title stops at the buttons.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AloNativeGlassPanel(
                modifier = Modifier.graphicsLayer {
                    scaleX = headerScale
                    scaleY = headerScale
                },
                shape = RoundedCornerShape(percent = 50)
            ) {
                Column(
                    modifier = Modifier
                        .height(itemSize)
                        .combinedClickable(
                            interactionSource = headerInteraction,
                            indication = null,
                            enabled = opensProfile,
                            onClick = onOpenPeerProfile,
                            onLongClick = { peerActionsExpanded = true }
                        )
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Alo Премиум: the person's colour, then the star or the emoji status (direct chats only).
                    val premiumId = if (detail != null && detail.roomId == 0 && detail.kind != "group" && detail.entityType !in setOf("channel", "saved")) detail.peerId else 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AloPremiumName(
                            text = threadTitle ?: detail?.title ?: "Загрузка чата",
                            userId = premiumId,
                            fontSize = 18.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        AloPremiumMark(premiumId, 16.dp)
                    }
                    when {
                        threadTitle != null -> Text(
                            text = threadSubtitle.orEmpty(),
                            style = AloChatHeaderSubtitleStyle,
                            color = AloNativeAppMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        isOfflineCached && detail?.kind != "group" -> ConnectingSubtitle()
                        detail?.isTyping == true -> TypingSubtitle(action = detail.typingAction)
                        else -> Text(
                            text = if (isPeerBlocked) "Заблокирован" else buildConversationHeaderSubtitle(detail, isLoading, isOfflineCached),
                            style = AloChatHeaderSubtitleStyle,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            AloDropdownMenu(
                expanded = peerActionsExpanded,
                onDismissRequest = { peerActionsExpanded = false }
            ) {
                if (showCallActions) {
                    DropdownMenuItem(
                        text = { Text("Аудиозвонок") },
                        leadingIcon = { Icon(Icons.Rounded.Call, null) },
                        onClick = { peerActionsExpanded = false; onStartVoiceCall() }
                    )
                    DropdownMenuItem(
                        text = { Text("Видеозвонок") },
                        leadingIcon = { Icon(Icons.Rounded.Videocam, null) },
                        onClick = { peerActionsExpanded = false; onStartVideoCall() }
                    )
                }
                if (isDirectUserConversation) {
                    DropdownMenuItem(
                        text = { Text(if (isPeerBlocked) "Разблокировать" else "Заблокировать") },
                        enabled = !isBlockActionInProgress,
                        leadingIcon = { Icon(Icons.Rounded.MoreVert, null) },
                        onClick = { peerActionsExpanded = false; onTogglePeerBlock() }
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(itemSize)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = opensProfile,
                    onClick = onOpenPeerProfile,
                    onLongClick = { peerActionsExpanded = true }
                )
                .semantics { contentDescription = "Профиль собеседника" },
            contentAlignment = Alignment.Center
        ) {
            when {
                detail == null -> Box(Modifier.fillMaxSize().clip(CircleShape).background(AloNativeAppSurfaceRaised))
                detail.entityType == "saved" -> SavedMessagesAvatar(size = itemSize)
                else -> AvatarBadge(
                    name = detail.title,
                    avatarUrl = detail.avatarUrl,
                    verified = false,
                    size = itemSize,
                    colorSeed = detail.peerId.toString()
                )
            }
            Box(Modifier.fillMaxSize().border(0.5.dp, AloNativeAppBorder, CircleShape))
        }
    }
}


/**
 * The app lays its screens out below the status bar; a chat's wallpaper and its top fade reach up
 * under it anyway, as on iOS, instead of leaving a black strip above the header.
 */
private fun Modifier.aloUnderStatusBar(): Modifier = composed {
    val inset = WindowInsets.statusBars.getTop(LocalDensity.current)
    layout { measurable, constraints ->
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = constraints.minHeight + inset,
                maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + inset else constraints.maxHeight
            )
        )
        layout(placeable.width, (placeable.height - inset).coerceAtLeast(0)) { placeable.place(0, -inset) }
    }
}

@Composable
internal fun ChatWallpaperBackground(modifier: Modifier = Modifier) {
    val appearance = LocalAloNativeChatAppearance.current
    val context = LocalContext.current
    val patternResource = if (appearance.patternStyle == "line") {
        R.drawable.alo_chat_pattern
    } else {
        R.drawable.alo_chat_pattern_ink
    }
    val showsPattern = appearance.patternStyle != "none" && appearance.patternIntensity > 0f
    val pattern = if (showsPattern) {
        remember(context.applicationContext, patternResource) {
            AloChatPatternCache.get(context.applicationContext, patternResource)
        }
    } else {
        null
    }
    val wallpaperHue = appearance.wallpaperHue.takeIf { it >= 0f }
    val wallpaperBrightness = appearance.wallpaperBrightness.coerceIn(0.02f, 0.55f)
    val neutralDarkWallpaper = appearance.wallpaper.lowercase(Locale.getDefault()) in setOf("classic", "black", "linen") &&
        wallpaperBrightness <= 0.12f
    val lightTheme = LocalAloUiPalette.current.background.luminance() > 0.5f
    val gradientColors = if (lightTheme) {
        aloLightWallpaperColors(appearance.wallpaper, wallpaperHue)
    } else if (neutralDarkWallpaper) {
        val top = wallpaperBrightness.coerceIn(0.018f, 0.10f)
        val bottom = (wallpaperBrightness + 0.028f).coerceIn(0.03f, 0.13f)
        listOf(Color(top, top, top, 1f), Color(bottom, bottom, bottom, 1f))
    } else if (wallpaperHue != null) {
        listOf(
            Color.hsv(wallpaperHue, 0.72f, wallpaperBrightness),
            Color.hsv((wallpaperHue + 16f) % 360f, 0.56f, (wallpaperBrightness + 0.06f).coerceAtMost(1f)),
            Color.hsv((wallpaperHue + 24f) % 360f, 0.42f, (wallpaperBrightness + 0.10f).coerceAtMost(1f))
        )
    } else {
        when (appearance.wallpaper.lowercase(Locale.getDefault())) {
            "aurora" -> listOf(Color(0xFF0C202D), Color(0xFF113852), Color(0xFF145E58))
            "sunset" -> listOf(Color(0xFF24151A), Color(0xFF462820), Color(0xFF3D254E))
            "linen" -> listOf(Color(0xFF090B10), Color(0xFF0D1118), Color(0xFF111823))
            else -> listOf(Color(0xFF080A0E), Color(0xFF0B1017), Color(0xFF101722))
        }
    }
    val backgroundBrush = chatWallpaperGradient(appearance.gradientStyle, gradientColors)
    // The stock dark chat is the website's and the iPhone's (web/css/chat.css `.chat-wall`): #050810 with a blue glow
    // at the top right and a violet one at the bottom left, the doodles in light blue at 10 %.
    val webClassic = !lightTheme && neutralDarkWallpaper && appearance.wallpaper.lowercase(Locale.getDefault()) == "classic"
    Box(modifier = modifier.background(if (webClassic) Brush.verticalGradient(listOf(Color(0xFF050810), Color(0xFF050810))) else backgroundBrush)) {
        if (webClassic) Canvas(modifier = Modifier.matchParentSize()) {
            val glowRadius = 0.6f * maxOf(size.width * 1.2f, size.height * 0.7f)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(23, 125, 255, (0.16f * 255).toInt()), Color.Transparent),
                    center = Offset(size.width * 0.85f, -size.height * 0.10f),
                    radius = glowRadius
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(120, 90, 255, (0.10f * 255).toInt()), Color.Transparent),
                    center = Offset(0f, size.height),
                    radius = 0.6f * maxOf(size.width * 0.9f, size.height * 0.6f)
                )
            )
        }
        if (pattern != null) Canvas(modifier = Modifier.matchParentSize()) {
            val scale = maxOf(
                size.width / pattern.width.toFloat(),
                size.height / pattern.height.toFloat()
            )
            val destinationWidth = ceil(pattern.width * scale).toInt()
            val destinationHeight = ceil(pattern.height * scale).toInt()

            drawImage(
                image = pattern,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(pattern.width, pattern.height),
                dstOffset = IntOffset(
                    x = ((size.width - destinationWidth) / 2f).roundToInt(),
                    y = ((size.height - destinationHeight) / 2f).roundToInt()
                ),
                dstSize = IntSize(destinationWidth, destinationHeight),
                alpha = if (webClassic) 0.10f else appearance.patternIntensity.coerceIn(0.03f, 0.18f) * (if (lightTheme) 1.5f else 1f),
                // The line art is white on the dark wallpaper and a soft ink on the light one; light blue on the website's look.
                colorFilter = ColorFilter.tint(
                    when {
                        lightTheme -> Color(0xFF3E5A78)
                        webClassic -> Color(130, 175, 255)
                        else -> Color.White
                    },
                    BlendMode.SrcIn
                )
            )
        }
    }
}

/** The wallpaper of a light chat: pale tints of the dark presets' hues, so a chosen wallpaper keeps its character. */
internal fun aloLightWallpaperColors(wallpaper: String, hue: Float?): List<Color> {
    if (hue != null) {
        return listOf(
            Color.hsv(hue, 0.14f, 0.97f),
            Color.hsv((hue + 16f) % 360f, 0.11f, 0.95f),
            Color.hsv((hue + 24f) % 360f, 0.08f, 0.93f)
        )
    }
    return when (wallpaper.lowercase(Locale.ROOT)) {
        "aurora" -> listOf(Color(0xFFE3F3F6), Color(0xFFD5EBF3), Color(0xFFD2EFE6))
        "sunset" -> listOf(Color(0xFFF8E9E3), Color(0xFFF4DDD3), Color(0xFFECE2F3))
        else -> listOf(Color(0xFFF2F5F9), Color(0xFFEAF0F7), Color(0xFFE4ECF6))
    }
}

private fun chatWallpaperGradient(style: String, colors: List<Color>): Brush = when (style) {
    "vertical" -> Brush.verticalGradient(colors)
    "glow" -> Brush.radialGradient(colors.reversed())
    else -> Brush.linearGradient(colors)
}

/**
 * The wallpaper's line art, decoded once per process. It is a 1440×2960 picture whose colour is always replaced by the
 * tint (SrcIn), so only its alpha is kept: an 8-bit mask is 4 MB instead of 17 MB, decodes off the main thread at start
 * ([warm]) and uploads to the GPU four times faster. Decoding it on the first chat open used to cost ~90 ms of the
 * first frame on top of the 17 MB texture upload, part of why the chat's opening animation was lost.
 */
internal object AloChatPatternCache {
    private val bitmaps = java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>()

    fun get(context: android.content.Context, resource: Int): ImageBitmap =
        bitmaps[resource] ?: decode(context, resource).also { bitmaps.putIfAbsent(resource, it) }

    /** Decodes both patterns on a background thread, so the first chat finds them ready. */
    fun warm(context: android.content.Context) {
        val app = context.applicationContext
        Thread({
            listOf(R.drawable.alo_chat_pattern_ink, R.drawable.alo_chat_pattern).forEach { resource ->
                if (!bitmaps.containsKey(resource)) runCatching { bitmaps.putIfAbsent(resource, decode(app, resource)) }
            }
        }, "alo-chat-pattern").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun decode(context: android.content.Context, resource: Int): ImageBitmap {
        val full = BitmapFactory.decodeResource(context.resources, resource)
        val mask = full.extractAlpha()
        full.recycle()
        return mask.asImageBitmap()
    }
}

@Composable
private fun ChatScreenEdgeFades(
    modifier: Modifier = Modifier,
    topHeight: androidx.compose.ui.unit.Dp,
    bottomHeight: androidx.compose.ui.unit.Dp
) {
    // Dark edges on the dark wallpaper, pale ones on the light wallpaper, so the glass bars above and below sit on a calm ground.
    val light = LocalAloUiPalette.current.background.luminance() > 0.5f
    val scrim = if (light) Color.White else Color.Black
    val edge = if (light) 0.62f else 0.20f
    val soft = if (light) 0.24f else 0.07f
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topHeight)
                .aloUnderStatusBar()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to scrim.copy(alpha = edge),
                            0.46f to scrim.copy(alpha = soft),
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomHeight)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.54f to scrim.copy(alpha = soft * 0.85f),
                            1f to scrim.copy(alpha = edge)
                        )
                    )
                )
        )
    }
}

@Composable
private fun ChatDatePill(label: String) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.48f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 1
        )
    }
}

@Composable
private fun GroupCallHistoryEvent(
    message: AloChatMessage,
    meta: AloCallLogMeta,
    modifier: Modifier = Modifier
) {
    val actor = message.sender?.name?.takeIf { it.isNotBlank() } ?: "Участник"
    val action = if (meta.callType == "video") {
        "завершил(а) видеочат"
    } else {
        "завершил(а) голосовой чат"
    }
    val duration = if (meta.durationSec > 0) {
        " (${meta.durationSec.toLong().formatCallDuration()})"
    } else {
        ""
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.48f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
        ) {
            Text(
                text = "$actor $action$duration",
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Telegram Android's chat subtitle: 14sp under the 18sp title, both inside the 42dp avatar height. */
private val AloChatHeaderSubtitleStyle = TextStyle(fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = 0.sp)

@Composable
private fun TypingSubtitle(modifier: Modifier = Modifier, action: String = "typing") {
    // Telegram's typing status: the three swelling dots, then the word.
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AloTypingDots(color = AloNativeChatAccentBlue)
        Text(
            text = typingStatusText(action),
            style = AloChatHeaderSubtitleStyle,
            color = AloNativeChatAccentBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectingSubtitle(modifier: Modifier = Modifier) {
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(460)
            dots = if (dots == 3) 1 else dots + 1
        }
    }
    Text(
        text = "соединение" + ".".repeat(dots),
        modifier = modifier,
        style = AloChatHeaderSubtitleStyle,
        color = AloNativeAppMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ActiveVoicePlaybackBar(
    modifier: Modifier = Modifier,
    title: String,
    /** «Голосовое · 0:04 / 0:12», as on the website and the iPhone. */
    subtitle: String,
    isPlaying: Boolean,
    /** `null`: no speed button (a circle). */
    speedLabel: String?,
    onTogglePlayback: () -> Unit,
    onSpeedClick: () -> Unit,
    onClose: () -> Unit
) {
    // The same glass as the chat header: the messages under the bar are blurred, not readable through it.
    AloNativeGlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = AloSquircle(22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTogglePlayback),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = AloNativeAppText,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = AloNativeAppText,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted,
                    maxLines = 1
                )
            }
            if (speedLabel != null) Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onSpeedClick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black),
                    color = AloNativeAppText
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
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
private fun ChatConversationLoadingSkeleton(
    modifier: Modifier = Modifier,
    topPadding: Dp,
    bottomPadding: Dp
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.Bottom
        ) {
            ChatSkeletonBubble(width = 150.dp, height = 38.dp, outgoing = true, modifier = Modifier.align(Alignment.End))
            ChatSkeletonBubble(width = 210.dp, height = 42.dp, outgoing = true, modifier = Modifier.align(Alignment.End))
            ChatSkeletonBubble(width = 128.dp, height = 36.dp, outgoing = false, modifier = Modifier.align(Alignment.Start))
            ChatSkeletonBubble(width = 238.dp, height = 54.dp, outgoing = true, modifier = Modifier.align(Alignment.End))
            ChatSkeletonBubble(width = 176.dp, height = 40.dp, outgoing = false, modifier = Modifier.align(Alignment.Start))
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatSkeletonCircle(size = 46.dp)
            ChatSkeletonPill(
                modifier = Modifier.weight(1f),
                height = 46.dp,
                radius = 23.dp
            )
            ChatSkeletonCircle(size = 46.dp)
        }
    }
}

@Composable
private fun ChatSkeletonBubble(
    width: Dp,
    height: Dp,
    outgoing: Boolean,
    modifier: Modifier = Modifier
) {
    ChatSkeletonPill(
        modifier = modifier
            .padding(vertical = 4.dp)
            .width(width),
        height = height,
        radius = if (height > 44.dp) 20.dp else 18.dp,
        tint = if (outgoing) AloNativeChatOutgoing.copy(alpha = 0.28f) else AloNativeAppSurfaceRaised.copy(alpha = 0.80f)
    )
}

@Composable
private fun ChatSystemEventPill(
    event: AloChatSystemEvent,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val title = when (event) {
        AloChatSystemEvent.ProtectedMediaScreenshot -> if (isMine) {
            "Вы сделали снимок исчезающего медиа"
        } else {
            "Сделан снимок исчезающего медиа"
        }
    }
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = AloSquircle(16.dp),
            color = Color.Black.copy(alpha = 0.58f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChatSkeletonCircle(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AloNativeAppSurfaceRaised.copy(alpha = 0.84f))
    )
}

@Composable
private fun ChatSkeletonPill(
    modifier: Modifier = Modifier,
    height: Dp,
    radius: Dp,
    tint: Color = AloNativeAppSurfaceRaised.copy(alpha = 0.84f)
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(tint)
    )
}

@Composable
private fun ChatConversationEmptyState(
    isSaved: Boolean,
    isChannel: Boolean,
    modifier: Modifier = Modifier
) {
    AloNativeGlassPanel(
        modifier = modifier.widthIn(max = 270.dp),
        shape = AloSquircle(20.dp),
        tint = AloNativeAppSurface.copy(alpha = 0.76f),
        borderColor = Color.White.copy(alpha = 0.10f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isSaved) {
                SavedMessagesAvatar(size = 62.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(AloNativeAppSurfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = if (isSaved) {
                    "Избранное"
                } else if (isChannel) {
                    "Публикаций пока нет"
                } else {
                    "Сообщений пока нет"
                },
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AloNativeAppText
            )
            Text(
                text = if (isSaved) {
                    "Личное облако для сообщений, фотографий и файлов."
                } else if (isChannel) {
                    "Новые публикации канала появятся здесь."
                } else {
                    "Отправьте первое сообщение, чтобы начать диалог."
                },
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AloNativeAppMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun formatVoicePlaybackSpeed(speed: Float): String = when {
    speed < 1.25f -> "1x"
    speed < 1.75f -> "1.5x"
    else -> "2x"
}

@Composable
private fun MessagesSelectionHeader(
    selectedCount: Int,
    onCancel: () -> Unit,
    /** null in a chat that forbids forwarding. */
    onForward: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = AloSquircle(24.dp),
        color = AloNativeAppSurface,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.025f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlainBackHitButton(
                onClick = onCancel,
                size = 38.dp,
                iconSize = 27.dp
            )
            Text(
                text = "Выбрано: $selectedCount",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AloNativeAppText,
                maxLines = 1
            )
            if (onForward != null) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = AloNativeAppSurfaceRaised,
                    border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.04f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .clickable(onClick = onForward),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            tint = AloNativeAppText,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0x22FF6B7A),
                border = BorderStroke(1.dp, Color(0x33FF6B7A))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF6B7A),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

/** «N комментариев» (or «Комментарии» before the first one), the title of a channel post's comments. */
internal fun aloCommentsTitle(count: Int): String {
    if (count <= 0) return "Комментарии"
    val word = when {
        count % 100 in 11..14 -> "комментариев"
        count % 10 == 1 -> "комментарий"
        count % 10 in 2..4 -> "комментария"
        else -> "комментариев"
    }
    return "$count $word"
}

/** The post on top of its comments, as Telegram shows it: the channel's bubble, then «Начало обсуждения». */
@Composable
private fun CommentsThreadPostHeader(post: AloPost, hasComments: Boolean, bubbleColor: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AvatarBadge(
                name = post.author.name,
                avatarUrl = post.author.avatarUrl,
                verified = false,
                size = 34.dp,
                colorSeed = post.author.id.toString()
            )
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(AloSquircle(18.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(post.author.name, color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (post.body.isNotBlank()) {
                    Text(post.body, color = AloNativeAppText, fontSize = 16.sp, lineHeight = 21.sp)
                }
                Text(
                    formatMessageTime(post.createdAt),
                    color = AloNativeAppMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 11.dp, vertical = 5.dp)
        ) {
            Text(
                if (hasComments) "Начало обсуждения" else "Комментариев пока нет",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
