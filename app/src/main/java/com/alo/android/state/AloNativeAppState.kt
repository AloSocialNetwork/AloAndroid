package com.alo.android

data class AloNativeAppUiState(
    val bootstrap: AloBootstrapData? = null,
    val themeMode: AloThemeMode = AloThemeMode.Dark,
    val activeTab: AloAppTab = AloAppTab.Feed,
    val feedMode: AloFeedMode = AloFeedMode.Popular,
    val composeText: String = "",
    val stories: List<AloStoryGroup> = emptyList(),
    val isStoriesLoading: Boolean = false,
    val isSubmittingStory: Boolean = false,
    val publicationQueueStatus: String = "",
    val businessProfileData: AloBusinessProfileData? = null,
    val isBusinessProfileLoading: Boolean = false,
    val searchQuery: String = "",
    val messagesQuery: String = "",
    val activeConversationPeerId: Int = 0,
    val activeConversation: AloConversationDetail? = null,
    val groupManagement: AloGroupManagementSnapshot? = null,
    val isGroupManagementLoading: Boolean = false,
    val activeChatAppearance: AloChatAppearance = AloChatAppearance(),
    val activeChatMuted: Boolean = false,
    val blockedUserIds: Set<Int> = emptySet(),
    val blockActionUserId: Int? = null,
    val isChatPeerProfileOpen: Boolean = false,
    val activeViewedProfileTarget: AloProfileTarget? = null,
    val viewedProfile: AloViewedProfile? = null,
    val isViewedProfileLoading: Boolean = false,
    val activeMedia: AloMediaViewerState? = null,
    val commentsPostId: Int = 0,
    val commentsFocusId: Int = 0,
    val commentsPost: AloPost? = null,
    val comments: List<AloPostComment> = emptyList(),
    val messageComposerText: String = "",
    val messageRecorderMode: AloChatRecordMode = AloChatRecordMode.Voice,
    val messageCaptureState: AloChatCaptureState = AloChatCaptureState.Idle,
    val messageRecordingDurationSec: Int = 0,
    val messageRecordingLevels: List<Float> = emptyList(),
    val messageDraftAttachment: AloChatDraftAttachment? = null,
    val messageDraftAttachments: List<AloChatDraftAttachment> = emptyList(),
    val messageSelfDestructSeconds: Int = 0,
    val activeForumTopicId: Int = 0,
    val messageForwardDraft: List<AloMessageForwardSource> = emptyList(),
    val messageReplyTo: AloChatMessage? = null,
    val messageEditingId: Int? = null,
    val commentsComposerText: String = "",
    val commentsEditingCommentId: Int? = null,
    /** The comment a reply is being written to («Ответ для …»). */
    val commentsReplyTo: AloPostComment? = null,
    /** Posts hidden from the feed this session (hide, not_interested, report), kept as a «Вернуть» row. */
    val hiddenFeedPosts: Map<Int, String> = emptyMap(),
    /** Alo's own ads for the feed and how many posts go between two of them. */
    val feedAds: List<AloAd> = emptyList(),
    val feedAdEvery: Int = 6,
    val hiddenAdIds: Set<Int> = emptySet(),
    /** A short confirmation shown at the bottom («Реклама скрыта», «Жалоба отправлена»…). */
    val notice: String? = null,
    val mentionSuggestions: List<AloEntitySummary> = emptyList(),
    val pendingCommentLikeIds: Set<Int> = emptySet(),
    /** Bumped when pinned/archived chats or read marks change on this device. */
    val chatPrefsRevision: Int = 0,
    val unreadRevision: Int = 0,
    /** «Изменить» in the chat list: the selection toolbar replaces the tab bar. */
    val isSelectingChats: Boolean = false,
    val callHistory: List<AloCallHistoryItem> = emptyList(),
    val callHistoryHasMore: Boolean = false,
    val isCallHistoryLoading: Boolean = false,
    val callHistoryError: String? = null,
    val storyViewers: List<AloStoryViewerEntry> = emptyList(),
    val isStoryViewersLoading: Boolean = false,
    val storyViewersFailed: Boolean = false,
    /** Highlights by profile owner id. */
    val highlights: Map<Int, List<AloStoryHighlight>> = emptyMap(),
    val storyArchive: List<AloStory> = emptyList(),
    val storyArchiveNext: String = "",
    val isStoryArchiveLoading: Boolean = false,
    val storySettings: AloStorySettings = AloStorySettings(),
    val friends: List<AloEntitySummary> = emptyList(),
    val postDrafts: List<AloPostDraft> = emptyList(),
    val creatorStats: AloCreatorStats? = null,
    val isCreatorStatsLoading: Boolean = false,
    val creatorStatsError: String = "",
    val isRefreshing: Boolean = false,
    val isSubmittingPost: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val isLoadingConversation: Boolean = false,
    val isCommentsLoading: Boolean = false,
    val isSendingMessage: Boolean = false,
    val pendingChatMessageIds: Set<Int> = emptySet(),
    /** Files uploading in sending messages, by the message's local id. */
    val uploadProgress: Map<Int, AloUploadProgress> = emptyMap(),
    /** Sending messages whose files the person paused. */
    val pausedUploadIds: Set<Int> = emptySet(),
    val isProfileSettingsOpen: Boolean = false,
    /** A story opened from its link, over whatever is on screen. */
    val linkedStory: AloStoryGroup? = null,
    /** A story link whose story has gone: «История истекла», with its author when known. */
    val expiredStoryLink: AloStoryLinkResult.Expired? = null,
    /** The settings page to open on: "Root", or "Notifications" from the notifications screen. */
    val profileSettingsStartPage: String = "Root",
    /** The moderation panel, over the settings it was opened from. */
    val isModerationOpen: Boolean = false,
    val isCreatorStatsOpen: Boolean = false,
    val securitySessions: List<AloSecuritySession> = emptyList(),
    val securityEvents: List<AloSecurityEvent> = emptyList(),
    val isSecuritySessionsLoading: Boolean = false,
    val securityNotice: String = "",
    val savedAccounts: List<AloStoredAccount> = emptyList(),
    val isAccountSecurityLoading: Boolean = false,
    val passkeys: List<AloPasskey> = emptyList(),
    val isPasskeyLoading: Boolean = false,
    val qrLoginSession: AloQrLoginSession? = null,
    val isQrScannerOpen: Boolean = false,
    /** A scanned sign-in QR waiting for «Подтвердить вход»: which device asks (from the server). */
    val qrLoginConfirmation: AloQrLoginConfirmation? = null,
    val accountExport: AloAccountExport? = null,
    val isOfflineCached: Boolean = false,
    val activeDirectCall: AloDirectCallUiState? = null,
    val activeGroupCall: AloGroupCallUiState? = null,
    val humanChallenge: AloHumanChallengeUiState? = null,
    /** Lists with no later page left to ask for, and the ones being asked for now. */
    val pagedListsEnded: Set<AloPagedList> = emptySet(),
    val pagedListsLoading: Set<AloPagedList> = emptySet(),
    /** «Нравится»: every post the person liked, from the server (the bootstrap only carries the newest). */
    val likedPosts: List<AloPost> = emptyList(),
    /** What the search box found on the server for [searchRemoteQuery]: people, communities and posts. */
    val searchRemoteQuery: String = "",
    val searchRemoteUsers: List<AloEntitySummary> = emptyList(),
    val searchRemoteChannels: List<AloEntitySummary> = emptyList(),
    val searchRemotePosts: List<AloPost> = emptyList(),
    val searchRemotePostsHasMore: Boolean = false,
    val isSearchingRemote: Boolean = false,
    val pendingLikePostId: Int? = null,
    val pendingRepostPostId: Int? = null,
    /** Secret chats: the server offers them and this device has its libsignal device. */
    val secretChatsAvailable: Boolean = false,
    /** The secret chats of this device and the requests nobody has accepted yet. */
    val secretChats: List<AloSecretChat> = emptyList(),
    /** The open secret chat (its own id space: never a peer id), 0 when none. */
    val activeSecretChatId: Int = 0,
    val activeSecretMessages: List<AloSecretMessage> = emptyList(),
    val isSecretChatLoading: Boolean = false,
    /** The open secret chat is gone: deleted, or taken by another device of this account. */
    val isSecretChatGone: Boolean = false,
    val isSendingSecret: Boolean = false,
    val secretDraftAttachments: List<AloChatDraftAttachment> = emptyList(),
    /**
     * A voice message or circle being recorded in the open secret chat. Kept apart from the ordinary chat's recording
     * ([messageCaptureState]): the secret chat lies over the chat it was started from, which must not start its camera.
     */
    val secretRecorderMode: AloChatRecordMode = AloChatRecordMode.Voice,
    val secretCaptureState: AloChatCaptureState = AloChatCaptureState.Idle,
    val secretRecordingDurationSec: Int = 0,
    val secretRecordingLevels: List<Float> = emptyList(),
    val errorMessage: String? = null
)

data class AloHumanChallengeUiState(
    val url: String,
    val message: String = "Подтвердите, что вы не робот."
)

/** «Войти на устройстве Chrome · macOS?» after a scan or the phone camera's link. */
data class AloQrLoginConfirmation(
    val requestId: String,
    val secret: String,
    val deviceLabel: String,
    val ip: String,
    val deviceType: String,
    val isApproving: Boolean = false
)
