package com.alo.android

import org.json.JSONObject

data class AloChatStreamEvent(
    val id: Long,
    val name: String,
    val payload: JSONObject
)

class AloSseEventParser {
    private var eventId: Long = 0
    private var eventName: String = "message"
    private val dataLines = mutableListOf<String>()

    fun accept(line: String): AloChatStreamEvent? {
        if (line.isEmpty()) {
            if (dataLines.isEmpty()) {
                reset()
                return null
            }
            val event = AloChatStreamEvent(
                id = eventId,
                name = eventName,
                payload = runCatching {
                    JSONObject(dataLines.joinToString(separator = "\n"))
                }.getOrDefault(JSONObject())
            )
            reset()
            return event
        }
        if (line.startsWith(":")) return null
        when {
            line.startsWith("id:") -> {
                eventId = line.substringAfter("id:").trim().toLongOrNull() ?: 0
            }
            line.startsWith("event:") -> {
                eventName = line.substringAfter("event:").trim().ifBlank { "message" }
            }
            line.startsWith("data:") -> {
                dataLines += line.substringAfter("data:").trimStart()
            }
        }
        return null
    }

    private fun reset() {
        eventId = 0
        eventName = "message"
        dataLines.clear()
    }
}

data class AloActiveConversationKey(
    val peerId: Int,
    val roomId: Int,
    val kind: String
)

sealed interface AloChatRealtimeDecision {
    data object Ignore : AloChatRealtimeDecision

    data class ResetCursor(
        val cursor: Long,
        val refreshConversations: Boolean,
        val refreshActiveConversation: Boolean
    ) : AloChatRealtimeDecision

    data class AdvanceCursor(
        val cursor: Long,
        val refreshConversations: Boolean,
        val refreshActiveConversation: Boolean
    ) : AloChatRealtimeDecision

    /**
     * The client received an update after a missing sequence. Keep the
     * persisted cursor unchanged and reconnect so the server can replay the
     * missing interval instead of silently dropping a mutation.
     */
    data class ReconnectFromStoredCursor(
        val refreshConversations: Boolean,
        val refreshActiveConversation: Boolean
    ) : AloChatRealtimeDecision
}

sealed interface AloUnifiedUpdateAction {
    data object None : AloUnifiedUpdateAction
    data object RefreshBootstrap : AloUnifiedUpdateAction
    data object ApplyNotification : AloUnifiedUpdateAction

    data class MarkNotificationsRead(
        val throughNotificationId: Int,
        val notificationIds: Set<Int>
    ) : AloUnifiedUpdateAction

    data class DeleteNotification(
        val notificationId: Int
    ) : AloUnifiedUpdateAction

    data class ApplyNotificationSettings(
        val settings: AloNotificationSettings
    ) : AloUnifiedUpdateAction

    data class ApplyChatMute(
        val chatKind: String,
        val targetId: Int,
        val muted: Boolean
    ) : AloUnifiedUpdateAction

    /** Another device pinned, unpinned or reordered chats; null pins mean "ask the server". */
    data class ApplyChatPins(val pinned: List<String>?) : AloUnifiedUpdateAction

    data class ApplyPost(val isNew: Boolean) : AloUnifiedUpdateAction
    /** Someone commented under a channel post: «N комментариев», the avatars and the dot change at once. */
    data class ApplyChannelPostComments(val data: JSONObject) : AloUnifiedUpdateAction
    data class DeletePost(val postId: Int) : AloUnifiedUpdateAction
    data class ApplyComment(val postId: Int, val isNew: Boolean) : AloUnifiedUpdateAction
    data class DeleteComment(val postId: Int, val commentId: Int) : AloUnifiedUpdateAction
    data object ApplyCurrentUser : AloUnifiedUpdateAction
    data class ApplyUserRelation(val userId: Int) : AloUnifiedUpdateAction
    data class ApplyChannel(val isNew: Boolean) : AloUnifiedUpdateAction
    data class DeleteChannel(val channelId: Int) : AloUnifiedUpdateAction
    data class ApplyChannelSubscription(
        val channelId: Int,
        val subscribed: Boolean,
        val subscribersCount: Int
    ) : AloUnifiedUpdateAction
    data class ApplyChannelSubscribers(
        val channelId: Int,
        val subscribersCount: Int
    ) : AloUnifiedUpdateAction
    data class ApplyChannelManagementDelta(val channelId: Int) : AloUnifiedUpdateAction
    data class RefreshChannelManagement(val channelId: Int) : AloUnifiedUpdateAction
    data class ApplyGroup(val isNew: Boolean) : AloUnifiedUpdateAction
    data class DeleteGroup(val roomId: Int) : AloUnifiedUpdateAction
    data class ApplyGroupPolicy(val roomId: Int) : AloUnifiedUpdateAction
    data class ApplyGroupMembers(val roomId: Int) : AloUnifiedUpdateAction
    data class ApplyGroupMemberCount(
        val roomId: Int,
        val memberCount: Int
    ) : AloUnifiedUpdateAction
    data class ApplyGroupMembership(
        val roomId: Int,
        val status: String
    ) : AloUnifiedUpdateAction
    data class ApplyGroupTopicState(val roomId: Int) : AloUnifiedUpdateAction
    data class ApplyGroupManagementDelta(val roomId: Int) : AloUnifiedUpdateAction
    data class RefreshGroupManagement(val roomId: Int) : AloUnifiedUpdateAction

    /**
     * Anything in the `secret_chats` domain (a request, an accepted chat, a message, a read mark, a new timer, a
     * deleted chat): the list and the open chat are read again. [closed] is a chat that is gone.
     */
    data class RefreshSecretChats(val secretChatId: Int, val closed: Boolean) : AloUnifiedUpdateAction
}

object AloUnifiedUpdateRouter {
    private val notificationSettingKeys = listOf(
        "likesEnabled",
        "commentsEnabled",
        "repostsEnabled",
        "followsEnabled",
        "messagesEnabled",
        "friendRequestsEnabled",
        "friendAcceptsEnabled",
        "channelSubscriptionsEnabled",
        "commentLikesEnabled"
    )

    fun action(event: AloChatStreamEvent): AloUnifiedUpdateAction {
        if (event.name == "updates_resync") {
            return AloUnifiedUpdateAction.RefreshBootstrap
        }
        if (event.name != "update") {
            return AloUnifiedUpdateAction.None
        }
        return when (event.payload.optString("domain")) {
            "feed" -> feedAction(event.payload)
            "users" -> userAction(event.payload)
            "channels" -> channelAction(event.payload)
            "groups" -> groupAction(event.payload)
            "notifications" -> notificationAction(event.payload)
            "settings" -> settingsAction(event.payload)
            "secret_chats" -> AloUnifiedUpdateAction.RefreshSecretChats(
                secretChatId = event.payload.optJSONObject("data")?.optInt("secretChatId") ?: 0,
                closed = event.payload.optString("type") == "secret_chat_closed"
            )
            else -> AloUnifiedUpdateAction.None
        }
    }

    private fun feedAction(payload: JSONObject): AloUnifiedUpdateAction {
        val data = payload.optJSONObject("data") ?: JSONObject()
        return when (payload.optString("type")) {
            "post_upsert" -> {
                if ((data.optJSONObject("post")?.optInt("id") ?: 0) > 0) {
                    AloUnifiedUpdateAction.ApplyPost(data.optBoolean("isNew"))
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "post_deleted" -> data.optInt("postId")
                .takeIf { it > 0 }
                ?.let(AloUnifiedUpdateAction::DeletePost)
                ?: AloUnifiedUpdateAction.RefreshBootstrap
            "comment_upsert" -> {
                val postId = data.optInt("postId")
                val commentId = data.optJSONObject("comment")?.optInt("id") ?: 0
                if (postId > 0 && commentId > 0) {
                    AloUnifiedUpdateAction.ApplyComment(postId, data.optBoolean("isNew"))
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "comment_deleted" -> {
                val postId = data.optInt("postId")
                val commentId = data.optInt("commentId")
                if (postId > 0 && commentId > 0) {
                    AloUnifiedUpdateAction.DeleteComment(postId, commentId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }

    private fun userAction(payload: JSONObject): AloUnifiedUpdateAction {
        val data = payload.optJSONObject("data") ?: JSONObject()
        return when (payload.optString("type")) {
            "user_upsert" -> {
                if ((data.optJSONObject("user")?.optInt("id") ?: 0) > 0) {
                    AloUnifiedUpdateAction.ApplyCurrentUser
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "user_relation_changed" -> data.optInt("userId")
                .takeIf { it > 0 }
                ?.let(AloUnifiedUpdateAction::ApplyUserRelation)
                ?: AloUnifiedUpdateAction.RefreshBootstrap
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }

    private fun channelAction(payload: JSONObject): AloUnifiedUpdateAction {
        val data = payload.optJSONObject("data") ?: JSONObject()
        return when (payload.optString("type")) {
            "channel_post_comments" -> AloUnifiedUpdateAction.ApplyChannelPostComments(data)
            "channel_upsert" -> {
                val channelId = data
                    .optJSONObject("profile")
                    ?.optJSONObject("channel")
                    ?.optInt("id")
                    ?: 0
                if (channelId > 0) {
                    AloUnifiedUpdateAction.ApplyChannel(data.optBoolean("isNew"))
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "channel_deleted" -> data.optInt("channelId")
                .takeIf { it > 0 }
                ?.let(AloUnifiedUpdateAction::DeleteChannel)
                ?: AloUnifiedUpdateAction.RefreshBootstrap
            "channel_subscription_changed" -> {
                val channelId = data.optInt("channelId")
                if (channelId > 0 && data.has("subscribed")) {
                    AloUnifiedUpdateAction.ApplyChannelSubscription(
                        channelId = channelId,
                        subscribed = data.optBoolean("subscribed"),
                        subscribersCount = data.optInt("subscribersCount").coerceAtLeast(0)
                    )
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "channel_subscribers_changed" -> {
                val channelId = data.optInt("channelId")
                if (channelId > 0) {
                    AloUnifiedUpdateAction.ApplyChannelSubscribers(
                        channelId = channelId,
                        subscribersCount = data.optInt("subscribersCount").coerceAtLeast(0)
                    )
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "channel_management_changed" -> {
                val channelId = data.optInt("channelId")
                if (
                    channelId > 0 &&
                    data.optJSONObject("delta")?.optInt("version") == 1
                ) {
                    AloUnifiedUpdateAction.ApplyChannelManagementDelta(channelId)
                } else if (channelId > 0) {
                    AloUnifiedUpdateAction.RefreshChannelManagement(channelId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }

    private fun groupAction(payload: JSONObject): AloUnifiedUpdateAction {
        val data = payload.optJSONObject("data") ?: JSONObject()
        return when (payload.optString("type")) {
            "group_upsert" -> {
                if ((data.optJSONObject("room")?.optInt("id") ?: 0) > 0) {
                    AloUnifiedUpdateAction.ApplyGroup(data.optBoolean("isNew"))
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_deleted" -> data.optInt("roomId")
                .takeIf { it > 0 }
                ?.let(AloUnifiedUpdateAction::DeleteGroup)
                ?: AloUnifiedUpdateAction.RefreshBootstrap
            "group_policy_updated" -> {
                val roomId = data.optInt("roomId")
                if (roomId > 0 && data.optJSONObject("policy") != null) {
                    AloUnifiedUpdateAction.ApplyGroupPolicy(roomId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_members_updated" -> {
                val roomId = data.optInt("roomId")
                if (roomId > 0 && data.optJSONArray("members") != null) {
                    AloUnifiedUpdateAction.ApplyGroupMembers(roomId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_member_count_changed" -> {
                val roomId = data.optInt("roomId")
                if (roomId > 0) {
                    AloUnifiedUpdateAction.ApplyGroupMemberCount(
                        roomId = roomId,
                        memberCount = data.optInt("memberCount").coerceAtLeast(0)
                    )
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_membership_changed" -> {
                val roomId = data.optInt("roomId")
                val status = data.optString("status")
                if (roomId > 0 && status.isNotBlank()) {
                    AloUnifiedUpdateAction.ApplyGroupMembership(roomId, status)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_topic_state_changed" -> {
                val roomId = data.optInt("roomId")
                val topicId = data.optJSONObject("state")?.optInt("topicId") ?: 0
                if (roomId > 0 && topicId > 0) {
                    AloUnifiedUpdateAction.ApplyGroupTopicState(roomId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "group_management_changed" -> {
                val roomId = data.optInt("roomId")
                if (
                    roomId > 0 &&
                    data.optJSONObject("delta")?.optInt("version") == 1
                ) {
                    AloUnifiedUpdateAction.ApplyGroupManagementDelta(roomId)
                } else if (roomId > 0) {
                    AloUnifiedUpdateAction.RefreshGroupManagement(roomId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }

    private fun notificationAction(payload: JSONObject): AloUnifiedUpdateAction {
        val data = payload.optJSONObject("data") ?: JSONObject()
        return when (payload.optString("type")) {
            "notification_created" -> {
                val notification = data.optJSONObject("notification")
                if ((notification?.optInt("id") ?: 0) > 0) {
                    AloUnifiedUpdateAction.ApplyNotification
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "notifications_read" -> {
                val throughNotificationId = data.optInt("throughNotificationId")
                val ids = buildSet {
                    val values = data.optJSONArray("notificationIds")
                    if (values != null) {
                        for (index in 0 until values.length()) {
                            values.optInt(index).takeIf { it > 0 }?.let(::add)
                        }
                    }
                }
                if (throughNotificationId > 0 || ids.isNotEmpty()) {
                    AloUnifiedUpdateAction.MarkNotificationsRead(
                        throughNotificationId,
                        ids
                    )
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            "notification_deleted" -> {
                val notificationId = data.optInt("notificationId")
                if (notificationId > 0) {
                    AloUnifiedUpdateAction.DeleteNotification(notificationId)
                } else {
                    AloUnifiedUpdateAction.RefreshBootstrap
                }
            }
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }

    private fun settingsAction(payload: JSONObject): AloUnifiedUpdateAction {
        return when (payload.optString("type")) {
            "chat_pins_updated" -> AloUnifiedUpdateAction.ApplyChatPins(parseChatPinsOrNull(payload.optJSONObject("data")))
            "chat_mute_changed" -> {
                val data = payload.optJSONObject("data")
                val chatKind = data?.optString("chatKind").orEmpty()
                val targetId = data?.optInt("targetId") ?: 0
                if (
                    data == null ||
                    chatKind !in setOf("direct", "group") ||
                    targetId <= 0 ||
                    data.opt("muted") !is Boolean
                ) {
                    AloUnifiedUpdateAction.RefreshBootstrap
                } else {
                    AloUnifiedUpdateAction.ApplyChatMute(
                        chatKind = chatKind,
                        targetId = targetId,
                        muted = data.optBoolean("muted")
                    )
                }
            }
            "notification_settings_changed" -> {
                val data = payload.optJSONObject("data")
                if (
                    data == null ||
                    notificationSettingKeys.any { key -> !data.has(key) || data.opt(key) !is Boolean }
                ) {
                    AloUnifiedUpdateAction.RefreshBootstrap
                } else {
                    AloUnifiedUpdateAction.ApplyNotificationSettings(
                        AloNotificationSettings(
                            likesEnabled = data.optBoolean("likesEnabled"),
                            commentsEnabled = data.optBoolean("commentsEnabled"),
                            repostsEnabled = data.optBoolean("repostsEnabled"),
                            followsEnabled = data.optBoolean("followsEnabled"),
                            messagesEnabled = data.optBoolean("messagesEnabled"),
                            friendRequestsEnabled = data.optBoolean("friendRequestsEnabled"),
                            friendAcceptsEnabled = data.optBoolean("friendAcceptsEnabled"),
                            channelSubscriptionsEnabled =
                                data.optBoolean("channelSubscriptionsEnabled"),
                            commentLikesEnabled = data.optBoolean("commentLikesEnabled")
                        )
                    )
                }
            }
            else -> AloUnifiedUpdateAction.RefreshBootstrap
        }
    }
}

object AloChatRealtimeReducer {
    fun reduce(
        event: AloChatStreamEvent,
        storedCursor: Long,
        activeConversation: AloActiveConversationKey?
    ): AloChatRealtimeDecision {
        if (event.name == "updates_ready") {
            val serverCursor = event.payload.optLong("latestCursor", 0).coerceAtLeast(0)
            return if (serverCursor != storedCursor) {
                AloChatRealtimeDecision.ResetCursor(
                    cursor = serverCursor,
                    refreshConversations = false,
                    refreshActiveConversation = false
                )
            } else {
                AloChatRealtimeDecision.Ignore
            }
        }
        if (event.name == "updates_resync") {
            return AloChatRealtimeDecision.ResetCursor(
                cursor = event.payload.optLong("latestCursor", 0).coerceAtLeast(0),
                refreshConversations = true,
                refreshActiveConversation = activeConversation != null
            )
        }
        if (event.name != "update" || event.id <= storedCursor) {
            return AloChatRealtimeDecision.Ignore
        }
        if (storedCursor > 0 && event.id - storedCursor > 1) {
            return AloChatRealtimeDecision.ReconnectFromStoredCursor(
                refreshConversations = true,
                refreshActiveConversation = activeConversation != null
            )
        }
        val isChatUpdate = event.payload.optString("domain") == "chat"
        return AloChatRealtimeDecision.AdvanceCursor(
            cursor = event.id,
            refreshConversations = isChatUpdate,
            refreshActiveConversation = isChatUpdate &&
                touchesActiveConversation(
                    event.payload.optJSONObject("data") ?: JSONObject(),
                    activeConversation
                )
        )
    }

    private fun touchesActiveConversation(
        payload: JSONObject,
        activeConversation: AloActiveConversationKey?
    ): Boolean {
        val active = activeConversation ?: return false
        return if (payload.optString("chatKind") == "group") {
            active.kind == "group" && active.roomId == payload.optInt("roomId")
        } else {
            active.kind == "direct" &&
                (
                    active.peerId == payload.optInt("senderId") ||
                        active.peerId == payload.optInt("recipientId")
                    )
        }
    }
}
