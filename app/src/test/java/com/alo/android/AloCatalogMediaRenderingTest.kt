package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloCatalogMediaRenderingTest {
    @Test
    fun stickerAttachmentKeepsStickerVisualType() {
        val visual = messageWith(
            AloOutgoingMessageAttachment(
                url = "content://com.alo.android.e2ee/sticker",
                type = "sticker",
                name = "sticker-wave.webp"
            )
        ).toVisualMediaItems().single()

        assertEquals("sticker", visual.type)
    }

    @Test
    fun animatedGifAttachmentKeepsImagePipelineType() {
        val visual = messageWith(
            AloOutgoingMessageAttachment(
                url = "https://cdn.example/reaction.gif",
                type = "gif",
                name = "reaction.gif",
                mediaEncryption = mapOf("mime" to "image/gif")
            )
        ).toVisualMediaItems().single()

        assertEquals("gif", visual.type)
    }

    @Test
    fun videoBackedGifUsesVideoPipeline() {
        val visual = messageWith(
            AloOutgoingMessageAttachment(
                url = "https://cdn.example/reaction",
                type = "gif",
                name = "reaction.webm",
                mediaEncryption = mapOf("mime" to "video/webm")
            )
        ).toVisualMediaItems().single()

        assertEquals("video", visual.type)
    }

    @Test
    fun legacyPrimaryStickerStillRendersVisually() {
        val visual = AloChatMessage(
            id = 9,
            roomId = 0,
            senderId = 2,
            recipientId = 1,
            body = "",
            attachmentUrl = "https://cdn.example/legacy.webp",
            attachmentType = "sticker",
            attachmentName = "legacy.webp",
            createdAt = "2026-09-04T12:00:00Z",
            editedAt = "",
            readAt = ""
        ).toVisualMediaItems().single()

        assertEquals("sticker", visual.type)
    }

    private fun messageWith(attachment: AloOutgoingMessageAttachment): AloChatMessage {
        return AloChatMessage(
            id = 7,
            roomId = 0,
            senderId = 2,
            recipientId = 1,
            body = "",
            attachmentUrl = attachment.url,
            attachmentType = attachment.type,
            attachmentName = attachment.name,
            attachments = listOf(attachment),
            createdAt = "2026-09-04T12:00:00Z",
            editedAt = "",
            readAt = ""
        )
    }
}
