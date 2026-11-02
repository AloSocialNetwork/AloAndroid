package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloTypingStatusTest {
    private fun direct(typing: Boolean, action: String) = AloConversationDetail(
        peerId = 5,
        kind = "direct",
        roomId = 0,
        title = "Тест Анна",
        subtitle = "",
        username = "",
        lastSeenAt = "",
        avatarUrl = "",
        verified = false,
        online = true,
        isTyping = typing,
        typingAction = action,
        memberCount = 0,
        entityType = "user",
        items = emptyList()
    )

    @Test
    fun theSubtitleSaysWhatTheOtherSideIsDoing() {
        assertEquals("печатает...", buildConversationHeaderSubtitle(direct(true, "typing"), isLoading = false))
        assertEquals("печатает...", buildConversationHeaderSubtitle(direct(true, ""), isLoading = false))
        assertEquals("записывает голосовое...", buildConversationHeaderSubtitle(direct(true, "voice"), isLoading = false))
        assertEquals("записывает видеосообщение...", buildConversationHeaderSubtitle(direct(true, "circle"), isLoading = false))
        assertEquals("в сети", buildConversationHeaderSubtitle(direct(false, "voice"), isLoading = false))
    }
}
