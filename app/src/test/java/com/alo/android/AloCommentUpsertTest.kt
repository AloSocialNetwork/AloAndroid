package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloCommentUpsertTest {
    private val author = AloEntitySummary(1, "Тест Автор", "test_author", "", false)

    private fun comment(id: Int, body: String = "текст") =
        AloPostComment(id, body, emptyList(), "2026-11-04T12:00:00Z", likeCount = 0, viewerLiked = false, author = author)

    @Test
    fun realtimeFirstThenSendReplyKeepsOneComment() {
        // The realtime event added comment 65, then the reply to «Отправить» brings it again.
        val afterRealtime = aloUpsertComment(listOf(comment(64)), comment(65))
        val afterReply = aloUpsertComment(afterRealtime, comment(65, "с сервера"))
        assertEquals(listOf(64, 65), afterReply.map { it.id })
        assertEquals("с сервера", afterReply.last().body)
    }

    @Test
    fun newCommentIsAppended() {
        assertEquals(listOf(1, 2, 3), aloUpsertComment(listOf(comment(1), comment(2)), comment(3)).map { it.id })
    }
}
