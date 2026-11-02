package com.alo.android

import java.time.Instant

internal data class AloChatAttachmentCapabilities(
    val canSendMessages: Boolean,
    val canSendMedia: Boolean,
    val canCreatePoll: Boolean
) {
    companion object {
        fun resolve(detail: AloConversationDetail?): AloChatAttachmentCapabilities {
            if (detail == null) return AloChatAttachmentCapabilities(false, false, false)
            return resolve(
                kind = detail.kind,
                roomId = detail.roomId,
                isChannel = detail.isChannelCommunity(),
                memberRole = detail.memberRole,
                policy = detail.groupPolicy
            )
        }

        fun resolve(
            kind: String,
            roomId: Int,
            isChannel: Boolean,
            memberRole: String,
            policy: AloGroupPolicy?
        ): AloChatAttachmentCapabilities {
            if (kind != "group" && roomId <= 0) {
                return AloChatAttachmentCapabilities(true, true, true)
            }

            val role = memberRole.lowercase()
            if (role == "owner") return AloChatAttachmentCapabilities(true, true, true)

            if (isChannel) {
                val mayPost = role == "admin" && (
                    policy?.memberAdminRights.isNullOrEmpty() ||
                        policy?.memberAdminRights?.get("postMessages") == true
                    )
                return AloChatAttachmentCapabilities(mayPost, mayPost, mayPost)
            }

            if (role == "admin") {
                return AloChatAttachmentCapabilities(true, true, true)
            }

            val restrictionsActive = policy?.memberRestrictedUntil.isNullOrBlank() ||
                runCatching { Instant.parse(policy?.memberRestrictedUntil).isAfter(Instant.now()) }
                    .getOrDefault(true)
            val restrictions = if (restrictionsActive) policy?.memberRestrictions.orEmpty() else emptyMap()
            val maySend = restrictions["sendMessages"] != true &&
                policy?.defaultPermissions?.get("sendMessages") != false
            val maySendMedia = maySend && restrictions["sendMedia"] != true &&
                policy?.defaultPermissions?.get("sendMedia") != false
            return AloChatAttachmentCapabilities(maySend, maySendMedia, maySend)
        }
    }
}
