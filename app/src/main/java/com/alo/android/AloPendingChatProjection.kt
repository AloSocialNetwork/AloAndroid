package com.alo.android

internal object AloPendingChatProjection {
    fun removingDeletedMessages(
        messages: List<AloChatMessage>,
        deletedMessageIds: Set<Int>
    ): List<AloChatMessage> {
        if (deletedMessageIds.isEmpty()) return messages
        return messages.filterNot { it.id in deletedMessageIds }
    }

    fun mergingPendingMessages(
        serverMessages: List<AloChatMessage>,
        pendingMessages: List<AloChatMessage>
    ): List<AloChatMessage> {
        if (pendingMessages.isEmpty()) return serverMessages
        val serverIds = serverMessages.mapTo(mutableSetOf()) { it.id }
        return serverMessages + pendingMessages.filterNot { it.id in serverIds }
    }

    fun applyingScheduledChanges(
        messages: List<AloChatMessage>,
        canceledScheduledMessageIds: Set<Int>,
        scheduledAtByMessageId: Map<Int, String>
    ): List<AloChatMessage> {
        return messages.mapNotNull { message ->
            val scheduledMessageId = message.scheduledMessageId
            if (scheduledMessageId > 0 && scheduledMessageId in canceledScheduledMessageIds) {
                return@mapNotNull null
            }
            val scheduledAt = scheduledAtByMessageId[scheduledMessageId]
            if (scheduledMessageId > 0 && !scheduledAt.isNullOrBlank()) {
                message.copy(scheduledAt = scheduledAt)
            } else {
                message
            }
        }
    }

    fun applyingPendingEdits(
        messages: List<AloChatMessage>,
        bodyByMessageId: Map<Int, String>,
        editedAt: String
    ): List<AloChatMessage> {
        if (bodyByMessageId.isEmpty()) return messages
        return messages.map { message ->
            val body = bodyByMessageId[message.id] ?: return@map message
            val markup = AloMessageMarkup.parse(body)
            message.copy(
                body = markup.body,
                entities = markup.entities,
                editedAt = editedAt
            )
        }
    }

    /** [me] keeps the avatars on the chips right: the local user joins or leaves the people who reacted. */
    fun applyingDesiredReactions(
        messages: List<AloChatMessage>,
        desiredEmojiByMessageId: Map<Int, String>,
        me: AloConversationPeer? = null
    ): List<AloChatMessage> {
        if (desiredEmojiByMessageId.isEmpty()) return messages
        return messages.map { message ->
            val desiredEmoji = desiredEmojiByMessageId[message.id] ?: return@map message
            message.copy(reactions = reactionsAfterSetting(desiredEmoji, message.reactions, me))
        }
    }

    private fun reactionsAfterSetting(
        desiredEmoji: String,
        reactions: List<AloMessageReaction>,
        me: AloConversationPeer?
    ): List<AloMessageReaction> {
        fun List<AloConversationPeer>.withoutMe() = if (me == null) this else filterNot { it.id == me.id }
        fun List<AloConversationPeer>.withMe() = if (me == null || any { it.id == me.id }) this else this + me
        val updated = reactions.mapNotNull { reaction ->
            when {
                reaction.reactedByMe && (desiredEmoji.isBlank() || reaction.emoji != desiredEmoji) -> {
                    val count = (reaction.count - 1).coerceAtLeast(0)
                    if (count == 0) null else reaction.copy(count = count, reactedByMe = false, users = reaction.users.withoutMe())
                }
                !reaction.reactedByMe && desiredEmoji.isNotBlank() && reaction.emoji == desiredEmoji -> {
                    reaction.copy(count = reaction.count + 1, reactedByMe = true, users = reaction.users.withMe())
                }
                else -> reaction
            }
        }.toMutableList()
        if (desiredEmoji.isNotBlank() && reactions.none { it.emoji == desiredEmoji }) {
            updated += AloMessageReaction(emoji = desiredEmoji, count = 1, reactedByMe = true, users = emptyList<AloConversationPeer>().withMe())
        }
        return updated
    }
}
