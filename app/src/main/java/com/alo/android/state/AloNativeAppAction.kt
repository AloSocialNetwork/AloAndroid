package com.alo.android

sealed interface AloNativeAppAction {
    data object OpenProfileSettings : AloNativeAppAction
    data object CloseProfileSettings : AloNativeAppAction
    data object OpenModeration : AloNativeAppAction
    /** «Панель автора» in the settings, pushed over them as moderation is. */
    data object OpenCreatorStats : AloNativeAppAction
    data object CloseCreatorStats : AloNativeAppAction
    data object CloseLinkedStory : AloNativeAppAction
    /** The ring on a sending file: pause it, or resume from the last acknowledged piece. */
    data class ToggleUploadPause(val localId: Int) : AloNativeAppAction
    /** The gear on «Уведомления»: settings opened straight on the notification page. */
    data object OpenNotificationSettings : AloNativeAppAction
    data object CloseModeration : AloNativeAppAction
    data class OpenViewedProfile(val target: AloProfileTarget) : AloNativeAppAction
    data object CloseViewedProfile : AloNativeAppAction
    data class OpenMedia(val media: AloMediaItem) : AloNativeAppAction
    data class OpenMediaGallery(val items: List<AloMediaItem>, val startIndex: Int) : AloNativeAppAction
    data class PrepareMessageMediaOpen(
        val message: AloChatMessage,
        val onReady: (AloChatMessage) -> Unit
    ) : AloNativeAppAction
    data class OpenMessageAttachment(val message: AloChatMessage) : AloNativeAppAction
    data object CloseMedia : AloNativeAppAction
    data class SubmitHumanChallenge(val token: String) : AloNativeAppAction
    data object CloseHumanChallenge : AloNativeAppAction
    data class OpenComments(val postId: Int) : AloNativeAppAction
    data class OpenChannelPostDiscussion(val postId: Int) : AloNativeAppAction
    data object CloseComments : AloNativeAppAction
    data object OpenChatPeerProfile : AloNativeAppAction
    data object CloseChatPeerProfile : AloNativeAppAction
    data object ToggleActiveChatMuted : AloNativeAppAction
    data class UpdateDirectChatAutoDelete(val peerId: Int, val seconds: Int) : AloNativeAppAction
    data class UpdateUserBlock(val userId: Int, val blocked: Boolean) : AloNativeAppAction
    data object Refresh : AloNativeAppAction
    data object Logout : AloNativeAppAction
    data object ClearOfflineCache : AloNativeAppAction
    data object LoadSecuritySessions : AloNativeAppAction
    data class RevokeSecuritySession(val sessionId: Int) : AloNativeAppAction
    data object RevokeOtherSecuritySessions : AloNativeAppAction
    data object LoadSecurityAccounts : AloNativeAppAction
    data class SwitchSecurityAccount(val userId: Int) : AloNativeAppAction
    data object LoadPasskeys : AloNativeAppAction
    data object RegisterPasskey : AloNativeAppAction
    data object AuthenticateWithPasskey : AloNativeAppAction
    data object OpenQrLoginScanner : AloNativeAppAction
    /** Keep this account's session on the device and sign in to another, as iOS's addAnotherAccount. */
    data object AddAnotherAccount : AloNativeAppAction
    data object CloseQrLoginScanner : AloNativeAppAction
    data class ApproveQrLoginPayload(val rawPayload: String) : AloNativeAppAction
    data object ConfirmQrLogin : AloNativeAppAction
    data object CancelQrLogin : AloNativeAppAction
    data class ExportAccountData(val currentPassword: String) : AloNativeAppAction
    data class DeleteAccount(val currentPassword: String) : AloNativeAppAction
    data class ChangePassword(
        val currentPassword: String,
        val newPassword: String
    ) : AloNativeAppAction
    data object SubmitPost : AloNativeAppAction
    data object RefreshStories : AloNativeAppAction
    data class SubmitStory(val body: String) : AloNativeAppAction
    data class MarkStoryViewed(val storyId: Int) : AloNativeAppAction
    data object LoadBusinessProfile : AloNativeAppAction
    data class SaveBusinessProfile(
        val enabled: Boolean,
        val businessName: String,
        val category: String,
        val hours: Map<String, String>,
        val greetingMessage: String,
        val autoReplyEnabled: Boolean,
        val autoReplyMessage: String
    ) : AloNativeAppAction
    data class AddBusinessQuickReply(
        val shortcut: String,
        val title: String,
        val body: String
    ) : AloNativeAppAction
    data class DeleteBusinessQuickReply(val quickReplyId: Int) : AloNativeAppAction
    data class SaveBusinessChatLabel(
        val chatKind: String,
        val peerId: Int,
        val roomId: Int,
        val label: String,
        val color: String
    ) : AloNativeAppAction
    data class DeleteBusinessChatLabel(val chatLabelId: Int) : AloNativeAppAction
    data object SubmitComment : AloNativeAppAction
    data class StartEditComment(val commentId: Int, val body: String) : AloNativeAppAction
    data class DeleteComment(val commentId: Int) : AloNativeAppAction
    data class SelectTab(val tab: AloAppTab) : AloNativeAppAction
    data class SelectFeedMode(val mode: AloFeedMode) : AloNativeAppAction
    /** reason: hide, not_interested or report. */
    data class HideFeedPost(val postId: Int, val reason: String) : AloNativeAppAction
    data class UndoHideFeedPost(val postId: Int) : AloNativeAppAction
    /** onResult gets null when the report was sent, otherwise the error to show. */
    data class Report(
        val targetType: String,
        val targetId: Int,
        val reason: String,
        val details: String,
        val onResult: (String?) -> Unit
    ) : AloNativeAppAction
    data object LoadFeedAds : AloNativeAppAction
    data class RecordAdEvent(val adId: Int, val kind: String, val placement: String) : AloNativeAppAction
    data class HideAd(val adId: Int, val report: Boolean) : AloNativeAppAction
    data object DismissNotice : AloNativeAppAction
    /** Starts (or cancels, with null) a reply to a comment. */
    data class SetCommentReplyTo(val comment: AloPostComment?) : AloNativeAppAction
    data class ToggleCommentLike(val commentId: Int) : AloNativeAppAction
    /** People to suggest after «@»; an empty query clears them. */
    data class LoadMentionSuggestions(val query: String?) : AloNativeAppAction
    data class UpdateComposer(val value: String) : AloNativeAppAction
    data class UpdateCommentComposer(val value: String) : AloNativeAppAction
    data class UpdateSearchQuery(val value: String) : AloNativeAppAction
    data class UpdateMessagesQuery(val value: String) : AloNativeAppAction
    data class SelectConversation(val peerId: Int) : AloNativeAppAction
    data class OpenDirectConversation(val userId: Int) : AloNativeAppAction
    data object CloseConversation : AloNativeAppAction
    data class UpdateActiveChatAppearance(val appearance: AloChatAppearance) : AloNativeAppAction
    data class UpdateMessageComposer(val value: String) : AloNativeAppAction
    data class UpdateMessageSelfDestructSeconds(val seconds: Int) : AloNativeAppAction
    data class SelectForumTopic(val topicId: Int) : AloNativeAppAction
    data class ToggleForumTopicMuted(val topicId: Int) : AloNativeAppAction
    data object ToggleMessageRecorderMode : AloNativeAppAction
    data object StartMessageRecording : AloNativeAppAction
    /** [send]: Telegram's release of a held voice recording sends it straight away. */
    data class StopMessageRecording(val cancel: Boolean, val send: Boolean = false) : AloNativeAppAction
    data class CompleteVideoCircleRecording(val localUri: String, val durationSec: Int) : AloNativeAppAction
    data object DiscardMessageDraft : AloNativeAppAction
    data class RemoveMessageDraftAttachment(val localUri: String) : AloNativeAppAction
    data class SendCatalogExpression(val media: AloCatalogExpressionMedia) : AloNativeAppAction
    data object SendMessageDraft : AloNativeAppAction
    data object SendMessageWhenOnline : AloNativeAppAction
    data class ScheduleRepeatingMessage(val repeat: AloMessageRepeatSchedule) : AloNativeAppAction
    data class SendChatSystemEvent(
        val body: String,
        val pollDefinition: AloPollDefinition? = null
    ) : AloNativeAppAction
    data class CreatePoll(
        val definition: AloPollDefinition,
        val schedule: Boolean = false
    ) : AloNativeAppAction
    data class ClosePoll(val pollId: String) : AloNativeAppAction
    data object DiscardMessageForwardDraft : AloNativeAppAction
    data class StartReplyMessage(val message: AloChatMessage) : AloNativeAppAction
    data object CancelReplyMessage : AloNativeAppAction
    data class RetargetMessageForwardDraft(val target: AloConversationSummary) : AloNativeAppAction
    data object PickMessageAttachment : AloNativeAppAction
    data class PickMessageGalleryUri(val uri: String) : AloNativeAppAction
    data class PickMessageGalleryUris(val uris: List<String>) : AloNativeAppAction
    data class ShareContact(val contact: AloEntitySummary) : AloNativeAppAction
    data class ShareStaticLocation(
        val latitude: Double,
        val longitude: Double,
        val title: String = "Текущее местоположение",
        val address: String = ""
    ) : AloNativeAppAction
    data class ShareLiveLocation(val request: AloLiveLocationRequest) : AloNativeAppAction
    data object PickProfileAvatar : AloNativeAppAction
    data class PickGroupAvatar(val roomId: Int) : AloNativeAppAction
    data class UpdateProfileCoverStyle(val style: String) : AloNativeAppAction
    data class SetFavoriteReaction(val emoji: String) : AloNativeAppAction
    data class DeleteMessage(val messageId: Int, val forEveryone: Boolean) : AloNativeAppAction
    data class DeleteMessages(val messageIds: List<Int>, val forEveryone: Boolean) : AloNativeAppAction
    data class RescheduleMessage(val messageId: Int) : AloNativeAppAction
    data class CancelScheduledMessage(val messageId: Int) : AloNativeAppAction
    data class StartEditMessage(val messageId: Int) : AloNativeAppAction
    data object CancelEditMessage : AloNativeAppAction
    data class ToggleMessageReaction(val messageId: Int, val emoji: String) : AloNativeAppAction
    data class ToggleMessagePin(val messageId: Int) : AloNativeAppAction
    data class ForwardMessage(val message: AloChatMessage, val target: AloConversationSummary) : AloNativeAppAction
    data class ForwardMessages(val messages: List<AloChatMessage>, val target: AloConversationSummary) : AloNativeAppAction
    data class CreateGroupChat(
        val title: String,
        val description: String,
        val kind: String,
        val joinPolicy: String,
        val username: String
    ) : AloNativeAppAction
    data class UpdateGroupChatProfile(val roomId: Int, val title: String) : AloNativeAppAction
    data class AddGroupChatMember(val roomId: Int, val username: String) : AloNativeAppAction
    data class RemoveGroupChatMember(val roomId: Int, val userId: Int) : AloNativeAppAction
    data class LeaveGroupChat(val roomId: Int) : AloNativeAppAction
    data class DeleteGroupChat(val roomId: Int) : AloNativeAppAction
    data class UpdateGroupChatMemberRole(val roomId: Int, val userId: Int, val role: String) : AloNativeAppAction
    data class LoadGroupManagement(val roomId: Int) : AloNativeAppAction
    data class LoadEarlierGroupAdminLog(val roomId: Int) : AloNativeAppAction
    data class UpdateGroupSettings(val roomId: Int, val policy: AloGroupPolicy) : AloNativeAppAction
    data class CreateGroupTopic(
        val roomId: Int,
        val title: String,
        val emoji: String
    ) : AloNativeAppAction
    data class UpdateGroupTopic(
        val roomId: Int,
        val topicId: Int,
        val title: String? = null,
        val emoji: String? = null,
        val isClosed: Boolean? = null,
        val isHidden: Boolean? = null
    ) : AloNativeAppAction
    data class DeleteGroupTopic(
        val roomId: Int,
        val topicId: Int
    ) : AloNativeAppAction
    data class MergeGroupTopic(
        val roomId: Int,
        val sourceTopicId: Int,
        val targetTopicId: Int
    ) : AloNativeAppAction
    data class ReviewGroupJoinRequest(
        val roomId: Int,
        val userId: Int,
        val approve: Boolean
    ) : AloNativeAppAction
    data class CreateGroupInviteLink(
        val roomId: Int,
        val label: String,
        val requiresApproval: Boolean,
        val usageLimit: Int,
        val expiresAt: String
    ) : AloNativeAppAction
    data class UpdateGroupInviteLink(
        val roomId: Int,
        val inviteId: Int,
        val label: String,
        val requiresApproval: Boolean,
        val usageLimit: Int,
        val expiresAt: String
    ) : AloNativeAppAction
    data class RevokeGroupInviteLink(
        val roomId: Int,
        val inviteId: Int
    ) : AloNativeAppAction
    data class JoinGroupInvite(val token: String) : AloNativeAppAction
    data class UpdateGroupMemberAccess(
        val roomId: Int,
        val userId: Int,
        val role: String,
        val adminTitle: String,
        val isAnonymous: Boolean,
        val adminRights: Map<String, Boolean>,
        val restrictions: Map<String, Boolean>,
        val changeRole: Boolean
    ) : AloNativeAppAction
    data class TransferGroupOwnership(
        val roomId: Int,
        val userId: Int,
        val currentPassword: String
    ) : AloNativeAppAction
    data class BanGroupMember(
        val roomId: Int,
        val userId: Int,
        val reason: String,
        val expiresAt: String
    ) : AloNativeAppAction
    data class UnbanGroupMember(val roomId: Int, val userId: Int) : AloNativeAppAction
    data class SendFriendRequest(val userId: Int) : AloNativeAppAction
    data class RespondFriendRequest(val requestId: Int, val accept: Boolean) : AloNativeAppAction
    /** Follows / unfollows a person who closed friend requests (a follow back makes friends). */
    data class ToggleFollowUser(val userId: Int) : AloNativeAppAction
    data class CancelFriendRequest(val requestId: Int, val userId: Int) : AloNativeAppAction
    data class RemoveFriend(val userId: Int) : AloNativeAppAction
    data object MarkAllNotificationsRead : AloNativeAppAction
    data class DeleteNotification(val notificationId: Int) : AloNativeAppAction
    data class ToggleLike(val postId: Int) : AloNativeAppAction
    data class ToggleRepost(val postId: Int) : AloNativeAppAction
    data class DeletePost(val postId: Int) : AloNativeAppAction
    data class UpdateNotificationSettings(val settings: AloNotificationSettings) : AloNativeAppAction
    data class UpdatePrivacySettings(val settings: AloPrivacySettings) : AloNativeAppAction
    data class UpdateChannelSettings(
        val channelId: Int,
        val commentsEnabled: Boolean,
        val signMessages: Boolean,
        val showAuthorProfiles: Boolean
    ) : AloNativeAppAction
    data class UpdateThemeMode(val mode: AloThemeMode) : AloNativeAppAction
    data object StartVoiceCall : AloNativeAppAction
    data object StartVideoCall : AloNativeAppAction
    data object AcceptDirectCall : AloNativeAppAction
    data object DeclineDirectCall : AloNativeAppAction
    data object EndDirectCall : AloNativeAppAction
    data object MinimizeDirectCall : AloNativeAppAction
    data object ExpandDirectCall : AloNativeAppAction
    data object ToggleDirectCallMute : AloNativeAppAction
    data object ToggleDirectCallSpeaker : AloNativeAppAction
    data object ToggleDirectCallVideo : AloNativeAppAction
    data object ToggleDirectCallScreenShare : AloNativeAppAction
    data object FlipDirectCallCamera : AloNativeAppAction
    data class SelectDirectCallCamera(val frontCamera: Boolean) : AloNativeAppAction
    data object MinimizeGroupCall : AloNativeAppAction
    data object ExpandGroupCall : AloNativeAppAction
    data object ToggleGroupCallMute : AloNativeAppAction
    data object ToggleGroupCallSpeaker : AloNativeAppAction
    data object ToggleGroupCallVideo : AloNativeAppAction
    data object FlipGroupCallCamera : AloNativeAppAction
    data class SelectGroupCallCamera(val frontCamera: Boolean) : AloNativeAppAction
    data object LeaveGroupCall : AloNativeAppAction
    data class SetGroupCallMicrophone(val userId: Int, val allowed: Boolean) : AloNativeAppAction
    data class MuteGroupCallEveryone(val muted: Boolean) : AloNativeAppAction
    data object ToggleGroupCallRecording : AloNativeAppAction
    data object SendMessage : AloNativeAppAction
    data object SendMessageSilently : AloNativeAppAction
    data class ScheduleMessage(val scheduledAt: String = "") : AloNativeAppAction
    // The chat list (iOS's inbox): pins and archive live on this device, keyed by AloChatListPrefs.key.
    data class SetChatsPinned(val keys: List<String>, val pinned: Boolean) : AloNativeAppAction
    data class SetChatsArchived(val keys: List<String>, val archived: Boolean) : AloNativeAppAction
    data class MarkChatsRead(val keys: List<String>) : AloNativeAppAction
    data class ToggleChatMuted(val key: String) : AloNativeAppAction
    data class DeleteChats(val keys: List<String>) : AloNativeAppAction
    data object EstablishUnreadBaseline : AloNativeAppAction
    data class SetChatSelection(val active: Boolean) : AloNativeAppAction
    data class LoadCallHistory(val more: Boolean = false) : AloNativeAppAction
    data class CallBack(val peerId: Int, val video: Boolean) : AloNativeAppAction
    // Stories (iOS's viewer, viewers list, links, highlights and archive).
    data class LoadStoryViewers(val storyId: Int) : AloNativeAppAction
    data class CopyStoryLink(val storyId: Int, val onResult: (Boolean) -> Unit) : AloNativeAppAction
    data class DeleteStory(val storyId: Int, val onDone: () -> Unit = {}) : AloNativeAppAction
    data class LoadHighlights(val userId: Int) : AloNativeAppAction
    data class CreateHighlight(val title: String, val storyIds: List<Int>, val onDone: (Boolean) -> Unit = {}) : AloNativeAppAction
    data class AddToHighlight(val highlightId: Int, val storyIds: List<Int>, val onDone: (Boolean) -> Unit = {}) : AloNativeAppAction
    data class RemoveFromHighlight(val highlightId: Int, val storyId: Int) : AloNativeAppAction
    data class LoadStoryArchive(val more: Boolean = false) : AloNativeAppAction
    data object LoadStoryAudience : AloNativeAppAction
    data class UpdateCloseFriends(val userIds: List<Int>) : AloNativeAppAction
    data class UpdateStoryHiddenFrom(val userIds: List<Int>) : AloNativeAppAction
    data class LoadCreatorStats(val days: Int) : AloNativeAppAction
    data class RenameHighlight(val highlightId: Int, val title: String) : AloNativeAppAction
    data class DeleteHighlight(val highlightId: Int) : AloNativeAppAction
    data class PublishStory(
        val pages: List<AloStoryUploadPage>,
        val body: String,
        val lifetimeHours: Int,
        val audience: String,
        val audienceUserIds: List<Int>,
        val onResult: (String?) -> Unit
    ) : AloNativeAppAction
    // The post editor (iOS's FeedView composer): media, server drafts.
    data class PublishPost(
        val uploads: List<AloStoryUploadPage>,
        val draftMedia: List<AloMediaItem>,
        val draftId: Int?,
        val onResult: (String?) -> Unit
    ) : AloNativeAppAction
    data class SavePostDraft(
        val draftId: Int?,
        val uploads: List<AloStoryUploadPage>,
        val draftMedia: List<AloMediaItem>,
        val onResult: (String?) -> Unit
    ) : AloNativeAppAction
    data object LoadPostDrafts : AloNativeAppAction
    data class DeletePostDraft(val draftId: Int) : AloNativeAppAction
    data class LoadMore(val list: AloPagedList) : AloNativeAppAction
    data class EditPost(val postId: Int, val body: String, val onResult: (String?) -> Unit) : AloNativeAppAction
    data class UpdateProfileInfo(val name: String, val username: String, val bio: String, val onResult: (String?) -> Unit) : AloNativeAppAction
    data class SearchRemote(val query: String) : AloNativeAppAction
    data object LoadMoreSearchPosts : AloNativeAppAction
    /** «Начать секретный чат» in a profile or the details of a chat. */
    data class StartSecretChat(val userId: Int) : AloNativeAppAction
    data class OpenSecretChat(val id: Int) : AloNativeAppAction
    data object CloseSecretChat : AloNativeAppAction
    data object AcceptSecretChat : AloNativeAppAction
    /** Deletes the open secret chat for both people, or declines its request. */
    data object DeleteSecretChat : AloNativeAppAction
    data class SetSecretChatTimer(val seconds: Int) : AloNativeAppAction
    data class SendSecretMessage(val text: String) : AloNativeAppAction
    data object PickSecretAttachment : AloNativeAppAction
    data class RemoveSecretDraft(val index: Int) : AloNativeAppAction
    /** The microphone or the camera of the secret composer: a tap starts recording a voice message or a circle. */
    data class StartSecretRecording(val mode: AloChatRecordMode) : AloNativeAppAction
    /** Ends the voice recording: thrown away ([cancel]), or kept and, with [send], sent at once. */
    data class StopSecretRecording(val cancel: Boolean, val send: Boolean = false) : AloNativeAppAction
    /** The camera finished a circle ([localUri] is the square video): it is sealed and sent. */
    data class CompleteSecretCircleRecording(val localUri: String, val durationSec: Int) : AloNativeAppAction
}
