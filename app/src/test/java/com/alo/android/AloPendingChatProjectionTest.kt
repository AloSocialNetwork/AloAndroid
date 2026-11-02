package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloPendingChatProjectionTest {
    @Test
    fun pendingDeleteTombstonesHideServerMessagesUntilAcknowledged() {
        val messages = listOf(
            message(id = 101, body = "keep"),
            message(id = 102, body = "delete offline")
        )

        val visible = AloPendingChatProjection.removingDeletedMessages(
            messages = messages,
            deletedMessageIds = setOf(102)
        )

        assertEquals(listOf(101), visible.map { it.id })
    }

    @Test
    fun pendingMessageSurvivesConversationReopenBeforeAcknowledgement() {
        val reopened = AloPendingChatProjection.mergingPendingMessages(
            serverMessages = listOf(message(id = 101, body = "server")),
            pendingMessages = listOf(message(id = -1, body = "still sending"))
        )

        assertEquals(listOf(101, -1), reopened.map { it.id })
        assertEquals("still sending", reopened.last().body)
    }

    @Test
    fun pendingEditSurvivesConversationReopenBeforeAcknowledgement() {
        val projected = AloPendingChatProjection.applyingPendingEdits(
            messages = listOf(message(id = 201, body = "before edit")),
            bodyByMessageId = mapOf(201 to "after offline edit"),
            editedAt = "2026-07-28T10:01:00Z"
        )

        assertEquals("after offline edit", projected.single().body)
        assertEquals("2026-07-28T10:01:00Z", projected.single().editedAt)
    }

    @Test
    fun pendingReactionSurvivesConversationReopenBeforeAcknowledgement() {
        val serverMessage = message(id = 202, body = "reaction target").copy(
            reactions = listOf(
                AloMessageReaction(emoji = "❤️", count = 2, reactedByMe = false),
                AloMessageReaction(emoji = "👍", count = 1, reactedByMe = true)
            )
        )

        val projected = AloPendingChatProjection.applyingDesiredReactions(
            messages = listOf(serverMessage),
            desiredEmojiByMessageId = mapOf(202 to "🔥")
        )

        assertEquals(
            listOf(
                AloMessageReaction(emoji = "❤️", count = 2, reactedByMe = false),
                AloMessageReaction(emoji = "🔥", count = 1, reactedByMe = true)
            ),
            projected.single().reactions
        )
    }

    @Test
    fun pendingRescheduleSurvivesConversationReopen() {
        val projected = AloPendingChatProjection.applyingScheduledChanges(
            messages = listOf(
                message(
                    id = 103,
                    body = "later",
                    scheduledMessageId = 501,
                    scheduledAt = "2026-07-28T11:00:00Z"
                )
            ),
            canceledScheduledMessageIds = emptySet(),
            scheduledAtByMessageId = mapOf(501 to "2026-07-29T15:30:00Z")
        )

        assertEquals("2026-07-29T15:30:00Z", projected.single().scheduledAt)
    }

    @Test
    fun sendWhenOnlineMessageStaysScheduledUntilDelivery() {
        val local = message(
            id = -7,
            body = "when you are online",
            sendWhenOnline = true
        )

        assertEquals(true, local.sendWhenOnline)
        assertEquals(true, local.isScheduled)
    }

    @Test
    fun pendingScheduledCancelSurvivesConversationReopen() {
        val projected = AloPendingChatProjection.applyingScheduledChanges(
            messages = listOf(
                message(
                    id = 104,
                    body = "cancel",
                    scheduledMessageId = 502,
                    scheduledAt = "2026-07-28T11:00:00Z"
                ),
                message(id = 105, body = "keep")
            ),
            canceledScheduledMessageIds = setOf(502),
            scheduledAtByMessageId = emptyMap()
        )

        assertEquals(listOf(105), projected.map { it.id })
    }

    private fun message(
        id: Int,
        body: String,
        scheduledMessageId: Int = 0,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false
    ) = AloChatMessage(
        id = id,
        roomId = 0,
        senderId = 1,
        recipientId = 2,
        body = body,
        attachmentUrl = "",
        attachmentType = "",
        attachmentName = "",
        createdAt = "2026-07-28T10:00:00Z",
        editedAt = "",
        readAt = "",
        scheduledMessageId = scheduledMessageId,
        scheduledAt = scheduledAt,
        sendWhenOnline = sendWhenOnline
    )
}
