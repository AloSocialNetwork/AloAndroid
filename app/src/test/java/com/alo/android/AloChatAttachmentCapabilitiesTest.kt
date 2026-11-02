package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloChatAttachmentCapabilitiesTest {
    @Test
    fun directChatsAllowSupportedAttachments() {
        val result = AloChatAttachmentCapabilities.resolve(
            kind = "direct",
            roomId = 0,
            isChannel = false,
            memberRole = "",
            policy = null
        )

        assertTrue(result.canSendMessages)
        assertTrue(result.canSendMedia)
        assertTrue(result.canCreatePoll)
    }

    @Test
    fun channelMemberCannotSeePublishingActions() {
        val result = AloChatAttachmentCapabilities.resolve(
            kind = "group",
            roomId = 42,
            isChannel = true,
            memberRole = "member",
            policy = AloGroupPolicy(kind = "channel")
        )

        assertFalse(result.canSendMessages)
        assertFalse(result.canSendMedia)
        assertFalse(result.canCreatePoll)
    }

    @Test
    fun channelAdminNeedsPostPermissionWhenRightsAreExplicit() {
        val denied = AloChatAttachmentCapabilities.resolve(
            kind = "group",
            roomId = 42,
            isChannel = true,
            memberRole = "admin",
            policy = AloGroupPolicy(
                kind = "channel",
                memberAdminRights = mapOf("postMessages" to false)
            )
        )
        val allowed = AloChatAttachmentCapabilities.resolve(
            kind = "group",
            roomId = 42,
            isChannel = true,
            memberRole = "admin",
            policy = AloGroupPolicy(
                kind = "channel",
                memberAdminRights = mapOf("postMessages" to true)
            )
        )

        assertFalse(denied.canSendMessages)
        assertTrue(allowed.canSendMessages)
    }

    @Test
    fun groupMediaRestrictionDoesNotHideTextAndPolls() {
        val result = AloChatAttachmentCapabilities.resolve(
            kind = "group",
            roomId = 7,
            isChannel = false,
            memberRole = "member",
            policy = AloGroupPolicy(
                defaultPermissions = mapOf("sendMessages" to true, "sendMedia" to true),
                memberRestrictions = mapOf("sendMedia" to true)
            )
        )

        assertTrue(result.canSendMessages)
        assertFalse(result.canSendMedia)
        assertTrue(result.canCreatePoll)
    }
}
