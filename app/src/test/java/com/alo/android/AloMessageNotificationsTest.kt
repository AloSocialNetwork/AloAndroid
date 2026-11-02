package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloMessageNotificationsTest {
    @Test
    fun oneChatHasOneKeyWhateverIdItCameWith() {
        assertEquals("direct:42", AloMessageNotifications.chatKey("direct", 42, 0))
        assertEquals("group:7", AloMessageNotifications.chatKey("group", 0, 7))
        assertEquals("group:7", AloMessageNotifications.chatKey("group", 1_000_000_007, 0))
        assertEquals("group:7", AloMessageNotifications.chatKey("group", 1_000_000_007, 7))
    }

    @Test
    fun summaryWordsFollowRussianPlurals() {
        assertEquals("новое сообщение", AloMessageNotifications.newMessages(1))
        assertEquals("новых сообщения", AloMessageNotifications.newMessages(3))
        assertEquals("новых сообщений", AloMessageNotifications.newMessages(5))
        assertEquals("новых сообщений", AloMessageNotifications.newMessages(12))
        assertEquals("новое сообщение", AloMessageNotifications.newMessages(21))
    }

    @Test
    fun avatarLettersAreTheFirstOfTwoWords() {
        assertEquals("ТА", AloMessageNotifications.initials("Тест Анна"))
        assertEquals("Д", AloMessageNotifications.initials("  дача "))
        assertEquals("", AloMessageNotifications.initials(""))
    }
}
