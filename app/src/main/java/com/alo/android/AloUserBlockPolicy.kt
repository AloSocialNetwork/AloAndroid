package com.alo.android

internal object AloUserBlockPolicy {
    const val BLOCKED_PEER_MESSAGE = "Вы заблокировали этого пользователя."

    fun isBlockedUser(userId: Int, blockedUserIds: Set<Int>): Boolean =
        userId > 0 && userId in blockedUserIds

    fun blocksDirectCommunication(
        kind: String,
        peerId: Int,
        entityType: String,
        blockedUserIds: Set<Int>
    ): Boolean {
        return kind.equals("direct", ignoreCase = true) &&
            !entityType.equals("saved", ignoreCase = true) &&
            isBlockedUser(peerId, blockedUserIds)
    }

    fun updated(
        blockedUserIds: Set<Int>,
        userId: Int,
        blocked: Boolean
    ): Set<Int> {
        if (userId <= 0) return blockedUserIds
        return if (blocked) blockedUserIds + userId else blockedUserIds - userId
    }
}

internal fun AloConversationDetail.isBlockedDirectPeer(blockedUserIds: Set<Int>): Boolean =
    AloUserBlockPolicy.blocksDirectCommunication(
        kind = kind,
        peerId = peerId,
        entityType = entityType,
        blockedUserIds = blockedUserIds
    )

internal fun AloConversationSummary.isBlockedDirectPeer(blockedUserIds: Set<Int>): Boolean =
    AloUserBlockPolicy.blocksDirectCommunication(
        kind = kind,
        peerId = peerId,
        entityType = peer.entityType,
        blockedUserIds = blockedUserIds
    )
