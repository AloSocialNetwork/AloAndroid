package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloCommunityPolicyTest {
    @Test
    fun channelIdentityTakesPriorityOverStaleManagementPolicy() {
        val channel = conversation(
            entityType = "channel",
            policy = AloGroupPolicy(kind = "group", isForum = true, slowModeSeconds = 60)
        )

        assertTrue(channel.isChannelCommunity())
        assertTrue(channel.toProfileTarget() is AloProfileTarget.Channel)
    }

    @Test
    fun savingChannelSettingsCannotTurnItIntoAGroupOrKeepGroupOnlyOptions() {
        val channel = conversation(entityType = "channel", policy = AloGroupPolicy(kind = "channel"))

        val saved = channel.withImmutableCommunityKind(
            AloGroupPolicy(
                kind = "group",
                isForum = true,
                historyVisible = false,
                slowModeSeconds = 300
            )
        )

        assertEquals("channel", saved.kind)
        assertFalse(saved.isForum)
        assertTrue(saved.historyVisible)
        assertEquals(0, saved.slowModeSeconds)
    }

    @Test
    fun groupSettingsRemainGroupSettings() {
        val group = conversation(entityType = "group", policy = AloGroupPolicy(kind = "group"))

        val saved = group.withImmutableCommunityKind(AloGroupPolicy(kind = "channel", isForum = true))

        assertFalse(group.isChannelCommunity())
        assertEquals("group", saved.kind)
        assertTrue(saved.isForum)
        assertTrue(group.toProfileTarget() is AloProfileTarget.Group)
    }

    private fun conversation(entityType: String, policy: AloGroupPolicy): AloConversationDetail {
        return AloConversationDetail(
            peerId = 101,
            kind = "group",
            roomId = 77,
            title = "Сообщество",
            subtitle = "",
            username = "",
            lastSeenAt = "",
            avatarUrl = "",
            verified = false,
            online = false,
            memberCount = 1,
            entityType = entityType,
            items = emptyList(),
            groupPolicy = policy
        )
    }
}
