package com.alo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloChatRealtimeTest {
    @Test
    fun `SSE parser combines data lines and resets between events`() {
        val parser = AloSseEventParser()
        assertEquals(null, parser.accept("id: 41"))
        assertEquals(null, parser.accept("event: update"))
        assertEquals(null, parser.accept("data: {\"domain\":\"chat\","))
        assertEquals(null, parser.accept("data: \"data\":{\"senderId\":2}}"))
        val event = parser.accept("")

        assertEquals(41L, event?.id)
        assertEquals("update", event?.name)
        assertEquals(2, event?.payload?.optJSONObject("data")?.optInt("senderId"))
        assertEquals(null, parser.accept(": ping 1"))
    }

    @Test
    fun `duplicate cursor is ignored and direct peer update refreshes active chat`() {
        val active = AloActiveConversationKey(peerId = 2, roomId = 0, kind = "direct")
        val duplicate = AloChatRealtimeReducer.reduce(
            event = event(id = 8, senderId = 2, recipientId = 1),
            storedCursor = 8,
            activeConversation = active
        )
        val fresh = AloChatRealtimeReducer.reduce(
            event = event(id = 9, senderId = 2, recipientId = 1),
            storedCursor = 8,
            activeConversation = active
        )

        assertTrue(duplicate is AloChatRealtimeDecision.Ignore)
        assertEquals(
            AloChatRealtimeDecision.AdvanceCursor(
                cursor = 9,
                refreshConversations = true,
                refreshActiveConversation = true
            ),
            fresh
        )
    }

    @Test
    fun `expired cursor requests full gap recovery`() {
        val decision = AloChatRealtimeReducer.reduce(
            event = AloChatStreamEvent(
                id = 22,
                name = "updates_resync",
                payload = JSONObject()
                    .put("reason", "cursor_expired")
                    .put("latestCursor", 22)
            ),
            storedCursor = 3,
            activeConversation = AloActiveConversationKey(2, 0, "direct")
        )

        assertEquals(
            AloChatRealtimeDecision.ResetCursor(
                cursor = 22,
                refreshConversations = true,
                refreshActiveConversation = true
            ),
            decision
        )
    }

    @Test
    fun `server restart resets a cursor without forcing redundant refresh`() {
        val decision = AloChatRealtimeReducer.reduce(
            event = AloChatStreamEvent(
                id = 0,
                name = "updates_ready",
                payload = JSONObject().put("latestCursor", 4)
            ),
            storedCursor = 91,
            activeConversation = null
        )

        assertEquals(
            AloChatRealtimeDecision.ResetCursor(
                cursor = 4,
                refreshConversations = false,
                refreshActiveConversation = false
            ),
            decision
        )
    }

    private fun event(
        id: Long,
        senderId: Int,
        recipientId: Int
    ) = AloChatStreamEvent(
        id = id,
        name = "update",
        payload = JSONObject()
            .put("domain", "chat")
            .put(
                "data",
                JSONObject()
                    .put("chatKind", "direct")
                    .put("senderId", senderId)
                    .put("recipientId", recipientId)
            )
    )

    @Test
    fun `SSE parser preserves production 64 bit cursors`() {
        val parser = AloSseEventParser()
        assertEquals(null, parser.accept("id: 1785512399000123"))
        assertEquals(null, parser.accept("event: update"))
        assertEquals(null, parser.accept("data: {\"domain\":\"calls\"}"))
        assertEquals(1_785_512_399_000_123L, parser.accept("")?.id)
    }

    @Test
    fun `non chat updates advance cursor without reloading conversations`() {
        val decision = AloChatRealtimeReducer.reduce(
            event = AloChatStreamEvent(
                id = 44,
                name = "update",
                payload = JSONObject().put("domain", "calls")
            ),
            storedCursor = 43,
            activeConversation = AloActiveConversationKey(2, 0, "direct")
        )

        assertEquals(
            AloChatRealtimeDecision.AdvanceCursor(
                cursor = 44,
                refreshConversations = false,
                refreshActiveConversation = false
            ),
            decision
        )
    }

    @Test
    fun `a missing SSE sequence reconnects without advancing the durable cursor`() {
        val decision = AloChatRealtimeReducer.reduce(
            event = event(id = 12, senderId = 2, recipientId = 1),
            storedCursor = 10,
            activeConversation = AloActiveConversationKey(2, 0, "direct")
        )

        assertEquals(
            AloChatRealtimeDecision.ReconnectFromStoredCursor(
                refreshConversations = true,
                refreshActiveConversation = true
            ),
            decision
        )
    }

    @Test
    fun `notification settings update is applied without bootstrap refresh`() {
        val data = JSONObject()
            .put("likesEnabled", false)
            .put("commentsEnabled", true)
            .put("repostsEnabled", false)
            .put("followsEnabled", true)
            .put("messagesEnabled", true)
            .put("friendRequestsEnabled", false)
            .put("friendAcceptsEnabled", true)
            .put("channelSubscriptionsEnabled", false)
            .put("commentLikesEnabled", true)
        val action = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 45,
                name = "update",
                payload = JSONObject()
                    .put("domain", "settings")
                    .put("type", "notification_settings_changed")
                    .put("data", data)
            )
        )

        assertEquals(
            AloUnifiedUpdateAction.ApplyNotificationSettings(
                AloNotificationSettings(
                    likesEnabled = false,
                    commentsEnabled = true,
                    repostsEnabled = false,
                    followsEnabled = true,
                    messagesEnabled = true,
                    friendRequestsEnabled = false,
                    friendAcceptsEnabled = true,
                    channelSubscriptionsEnabled = false,
                    commentLikesEnabled = true
                )
            ),
            action
        )
    }

    @Test
    fun `chat mute update carries the changed conversation state`() {
        val action = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 46,
                name = "update",
                payload = JSONObject()
                    .put("domain", "settings")
                    .put("type", "chat_mute_changed")
                    .put(
                        "data",
                        JSONObject()
                            .put("chatKind", "group")
                            .put("targetId", 31)
                            .put("muted", true)
                    )
            )
        )

        assertEquals(
            AloUnifiedUpdateAction.ApplyChatMute(
                chatKind = "group",
                targetId = 31,
                muted = true
            ),
            action
        )
    }

    @Test
    fun `pinned chats from another device are applied in their order`() {
        fun pinsEvent(data: JSONObject) = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 48,
                name = "update",
                payload = JSONObject()
                    .put("domain", "settings")
                    .put("type", "chat_pins_updated")
                    .put("data", data)
            )
        )

        assertEquals(
            AloUnifiedUpdateAction.ApplyChatPins(listOf("group:31", "direct:7")),
            pinsEvent(JSONObject().put("pinned", org.json.JSONArray(listOf("group:31", "bad", "direct:7", "group:31"))))
        )
        assertEquals(AloUnifiedUpdateAction.ApplyChatPins(null), pinsEvent(JSONObject()))
    }

    @Test
    fun `malformed notification settings fall back to bootstrap refresh`() {
        val action = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 47,
                name = "update",
                payload = JSONObject()
                    .put("domain", "settings")
                    .put("type", "notification_settings_changed")
                    .put("data", JSONObject().put("likesEnabled", false))
            )
        )

        assertEquals(AloUnifiedUpdateAction.RefreshBootstrap, action)
    }

    @Test
    fun `complete notification event is applied without bootstrap refresh`() {
        val action = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 48,
                name = "update",
                payload = JSONObject()
                    .put("domain", "notifications")
                    .put("type", "notification_created")
                    .put(
                        "data",
                        JSONObject().put(
                            "notification",
                            JSONObject()
                                .put("id", 91)
                                .put("type", "follow")
                                .put("text", "Новая подписка")
                        )
                    )
            )
        )

        assertEquals(AloUnifiedUpdateAction.ApplyNotification, action)
    }

    @Test
    fun `incomplete notification event falls back to bootstrap refresh`() {
        val action = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 49,
                name = "update",
                payload = JSONObject()
                    .put("domain", "notifications")
                    .put("type", "notification_created")
                    .put("data", JSONObject())
            )
        )

        assertEquals(AloUnifiedUpdateAction.RefreshBootstrap, action)
    }

    @Test
    fun `notification read and delete events are applied locally`() {
        val read = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 50,
                name = "update",
                payload = JSONObject()
                    .put("domain", "notifications")
                    .put("type", "notifications_read")
                    .put(
                        "data",
                        JSONObject()
                            .put("throughNotificationId", 0)
                            .put("notificationIds", org.json.JSONArray().put(91).put(92))
                    )
            )
        )
        val deleted = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 51,
                name = "update",
                payload = JSONObject()
                    .put("domain", "notifications")
                    .put("type", "notification_deleted")
                    .put("data", JSONObject().put("notificationId", 91))
            )
        )
        val readThroughBoundary = AloUnifiedUpdateRouter.action(
            AloChatStreamEvent(
                id = 52,
                name = "update",
                payload = JSONObject()
                    .put("domain", "notifications")
                    .put("type", "notifications_read")
                    .put(
                        "data",
                        JSONObject()
                            .put("throughNotificationId", 120)
                            .put("notificationIds", org.json.JSONArray())
                    )
            )
        )

        assertEquals(
            AloUnifiedUpdateAction.MarkNotificationsRead(
                throughNotificationId = 0,
                notificationIds = setOf(91, 92)
            ),
            read
        )
        assertEquals(AloUnifiedUpdateAction.DeleteNotification(91), deleted)
        assertEquals(
            AloUnifiedUpdateAction.MarkNotificationsRead(
                throughNotificationId = 120,
                notificationIds = emptySet()
            ),
            readThroughBoundary
        )
    }

    @Test
    fun `resource updates are routed without bootstrap refresh`() {
        val postUpsert = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 60,
                domain = "feed",
                type = "post_upsert",
                data = JSONObject()
                    .put("isNew", true)
                    .put("post", JSONObject().put("id", 10))
            )
        )
        val postDelete = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 61,
                domain = "feed",
                type = "post_deleted",
                data = JSONObject().put("postId", 10)
            )
        )
        val userUpsert = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 62,
                domain = "users",
                type = "user_upsert",
                data = JSONObject().put("user", JSONObject().put("id", 4))
            )
        )
        val channelUpsert = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 63,
                domain = "channels",
                type = "channel_upsert",
                data = JSONObject()
                    .put("isNew", false)
                    .put(
                        "profile",
                        JSONObject().put("channel", JSONObject().put("id", 7))
                    )
            )
        )
        val channelDelete = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 64,
                domain = "channels",
                type = "channel_deleted",
                data = JSONObject().put("channelId", 7)
            )
        )
        val groupUpsert = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 65,
                domain = "groups",
                type = "group_upsert",
                data = JSONObject()
                    .put("isNew", true)
                    .put("room", JSONObject().put("id", 8))
            )
        )
        val groupDelete = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 66,
                domain = "groups",
                type = "group_deleted",
                data = JSONObject().put("roomId", 8)
            )
        )
        val commentUpsert = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 67,
                domain = "feed",
                type = "comment_upsert",
                data = JSONObject()
                    .put("postId", 10)
                    .put("isNew", true)
                    .put("comment", JSONObject().put("id", 21))
            )
        )
        val relation = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 68,
                domain = "users",
                type = "user_relation_changed",
                data = JSONObject().put("userId", 9)
            )
        )
        val subscription = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 69,
                domain = "channels",
                type = "channel_subscription_changed",
                data = JSONObject()
                    .put("channelId", 7)
                    .put("subscribed", true)
                    .put("subscribersCount", 12)
            )
        )
        val groupPolicy = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 70,
                domain = "groups",
                type = "group_policy_updated",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("policy", JSONObject().put("joinPolicy", "request"))
            )
        )
        val groupMembers = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 71,
                domain = "groups",
                type = "group_members_updated",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("members", org.json.JSONArray())
            )
        )
        val channelSubscribers = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 72,
                domain = "channels",
                type = "channel_subscribers_changed",
                data = JSONObject()
                    .put("channelId", 7)
                    .put("subscribersCount", 13)
            )
        )
        val channelManagement = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 73,
                domain = "channels",
                type = "channel_management_changed",
                data = JSONObject()
                    .put("channelId", 7)
                    .put("delta", JSONObject().put("version", 1))
            )
        )
        val groupMemberCount = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 74,
                domain = "groups",
                type = "group_member_count_changed",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("memberCount", 14)
            )
        )
        val groupMembership = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 75,
                domain = "groups",
                type = "group_membership_changed",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("status", "removed")
            )
        )
        val groupManagement = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 76,
                domain = "groups",
                type = "group_management_changed",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("delta", JSONObject().put("version", 1))
                )
        )
        val groupTopicState = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 77,
                domain = "groups",
                type = "group_topic_state_changed",
                data = JSONObject()
                    .put("roomId", 8)
                    .put("state", JSONObject().put("topicId", 12))
            )
        )

        assertEquals(AloUnifiedUpdateAction.ApplyPost(isNew = true), postUpsert)
        assertEquals(AloUnifiedUpdateAction.DeletePost(10), postDelete)
        assertEquals(AloUnifiedUpdateAction.ApplyCurrentUser, userUpsert)
        assertEquals(AloUnifiedUpdateAction.ApplyChannel(isNew = false), channelUpsert)
        assertEquals(AloUnifiedUpdateAction.DeleteChannel(7), channelDelete)
        assertEquals(AloUnifiedUpdateAction.ApplyGroup(isNew = true), groupUpsert)
        assertEquals(AloUnifiedUpdateAction.DeleteGroup(8), groupDelete)
        assertEquals(AloUnifiedUpdateAction.ApplyComment(10, true), commentUpsert)
        assertEquals(AloUnifiedUpdateAction.ApplyUserRelation(9), relation)
        assertEquals(
            AloUnifiedUpdateAction.ApplyChannelSubscription(7, true, 12),
            subscription
        )
        assertEquals(AloUnifiedUpdateAction.ApplyGroupPolicy(8), groupPolicy)
        assertEquals(AloUnifiedUpdateAction.ApplyGroupMembers(8), groupMembers)
        assertEquals(
            AloUnifiedUpdateAction.ApplyChannelSubscribers(7, 13),
            channelSubscribers
        )
        assertEquals(
            AloUnifiedUpdateAction.ApplyChannelManagementDelta(7),
            channelManagement
        )
        assertEquals(
            AloUnifiedUpdateAction.ApplyGroupMemberCount(8, 14),
            groupMemberCount
        )
        assertEquals(
            AloUnifiedUpdateAction.ApplyGroupMembership(8, "removed"),
            groupMembership
        )
        assertEquals(
            AloUnifiedUpdateAction.ApplyGroupManagementDelta(8),
            groupManagement
        )
        assertEquals(
            AloUnifiedUpdateAction.ApplyGroupTopicState(8),
            groupTopicState
        )
    }

    @Test
    fun `incomplete resource update falls back to bootstrap refresh`() {
        val action = AloUnifiedUpdateRouter.action(
            resourceEvent(
                id = 77,
                domain = "groups",
                type = "group_upsert",
                data = JSONObject().put("room", JSONObject())
            )
        )

        assertEquals(AloUnifiedUpdateAction.RefreshBootstrap, action)
    }

    @Test
    fun `group management delta parses bounded upserts removals and audit event`() {
        val user = JSONObject()
            .put("id", 4)
            .put("name", "Участник")
            .put("username", "member")
        val member = JSONObject(user.toString())
            .put("role", "admin")
            .put("adminTitle", "Модератор")
        val request = JSONObject()
            .put("user", user)
            .put("createdAt", "2026-08-01T10:00:00.000Z")
        val event = JSONObject()
            .put("id", 91)
            .put("action", "join_request_approved")
            .put("createdAt", "2026-08-01T10:01:00.000Z")
            .put("actor", JSONObject().put("id", 1).put("name", "Владелец"))
            .put("target", user)
        val delta = JSONObject()
            .put("version", 1)
            .put(
                "members",
                JSONArray().put(
                    JSONObject()
                        .put("operation", "upsert")
                        .put("userId", 4)
                        .put("value", member)
                )
            )
            .put(
                "requests",
                JSONArray().put(
                    JSONObject()
                        .put("operation", "remove")
                        .put("userId", 4)
                ).put(
                    JSONObject()
                        .put("operation", "upsert")
                        .put("userId", 5)
                        .put(
                            "value",
                            request.put(
                                "user",
                                JSONObject().put("id", 5).put("name", "Заявитель")
                            )
                        )
                )
            )
            .put("bans", JSONArray())
            .put("invites", JSONArray())
            .put(
                "topics",
                JSONArray().put(
                    JSONObject()
                        .put("operation", "upsert")
                        .put(
                            "value",
                            JSONObject()
                                .put("id", 12)
                                .put("roomId", 8)
                                .put("title", "Архив")
                                .put("iconEmoji", "📦")
                                .put("isGeneral", false)
                                .put("isClosed", true)
                                .put("isHidden", true)
                                .put("createdAt", "2026-08-01T09:00:00.000Z")
                                .put("updatedAt", "2026-08-01T10:01:00.000Z")
                        )
                ).put(
                    JSONObject()
                        .put("operation", "remove")
                        .put("id", 13)
                )
            )
            .put("adminEvent", event)

        val parsed = AloApiClient("http://localhost")
            .parseGroupManagementDelta(delta)

        assertEquals(4, parsed?.membersUpserted?.single()?.user?.id)
        assertEquals("admin", parsed?.membersUpserted?.single()?.role)
        assertEquals(setOf(4), parsed?.requestUserIdsRemoved)
        assertEquals(5, parsed?.requestsUpserted?.single()?.user?.id)
        assertEquals(true, parsed?.topicsUpserted?.single()?.isHidden)
        assertEquals(true, parsed?.topicsUpserted?.single()?.isClosed)
        assertEquals(setOf(13), parsed?.topicIdsRemoved)
        assertEquals("join_request_approved", parsed?.adminEvent?.action)
    }

    @Test
    fun `channel management delta parses settings moderator count and audit event`() {
        val delta = JSONObject()
            .put("version", 1)
            .put("moderatorCount", 3)
            .put(
                "settings",
                JSONObject()
                    .put("commentsEnabled", false)
                    .put("signMessages", true)
                    .put("showAuthorProfiles", true)
                    .put("discussionRoomId", 18)
            )
            .put(
                "adminEvent",
                JSONObject()
                    .put("id", 92)
                    .put("action", "channel_settings_updated")
                    .put("createdAt", "2026-08-01T10:02:00.000Z")
                    .put(
                        "actor",
                        JSONObject().put("id", 1).put("name", "Владелец")
                    )
            )

        val parsed = AloApiClient("http://localhost")
            .parseChannelManagementDelta(delta)

        assertEquals(3, parsed?.moderatorCount)
        assertEquals(false, parsed?.commentsEnabled)
        assertEquals(true, parsed?.signMessages)
        assertEquals(18, parsed?.discussionRoomId)
        assertEquals("channel_settings_updated", parsed?.adminEvent?.action)
    }

    private fun resourceEvent(
        id: Long,
        domain: String,
        type: String,
        data: JSONObject
    ) = AloChatStreamEvent(
        id = id,
        name = "update",
        payload = JSONObject()
            .put("domain", domain)
            .put("type", type)
            .put("data", data)
    )
}
