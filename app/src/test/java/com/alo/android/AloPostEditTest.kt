package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloPostEditTest {
    private fun post(creatorId: Int, authorId: Int, isChannel: Boolean = false, editedAt: String = "") = AloPost(
        id = 1, creatorId = creatorId, body = "Текст", media = emptyList(), authorSignature = "", commentsEnabled = true,
        discussionRoomId = 0, discussionTopicId = 0, messengerRoomId = 0, likeCount = 0, commentCount = 0, repostCount = 0,
        viewCount = 0, viewerLiked = false, viewerReposted = false, createdAt = "2026-10-24T08:00:00Z",
        author = AloEntitySummary(id = authorId, name = "Автор", username = "author", avatarUrl = "", verified = false, isChannel = isChannel),
        editedAt = editedAt
    )

    @Test
    fun onlyThePublisherMayEdit() {
        assertTrue(post(creatorId = 7, authorId = 7).canBeEditedBy(7))
        assertFalse(post(creatorId = 7, authorId = 7).canBeEditedBy(8))
        assertFalse(post(creatorId = 7, authorId = 7).canBeEditedBy(0))
    }

    @Test
    fun aCommunityPostIsEditedByWhoPublishedIt_notByTheCommunityId() {
        // The author of a community post is the community (id 7 here); a person with the same number is not its publisher.
        assertFalse(post(creatorId = 3, authorId = 7, isChannel = true).canBeEditedBy(7))
        assertTrue(post(creatorId = 3, authorId = 7, isChannel = true).canBeEditedBy(3))
    }

    @Test
    fun usernameKeepsWhatTheServerAccepts() {
        assertEquals("ivan_petrov1", cleanUsernameInput("@Ivan Petrov-1".replace(" ", "_").replace("-", "")))
        assertEquals("abc", cleanUsernameInput("@a b!c"))
        assertEquals("", cleanUsernameInput("@Привет"))
        assertEquals(32, cleanUsernameInput("a".repeat(50)).length)
    }

    @Test
    fun editedPostsAreMarkedInTheirMeta() {
        assertFalse(buildPostMeta(post(7, 7)).contains("изменено"))
        assertTrue(buildPostMeta(post(7, 7, editedAt = "2026-10-24T09:00:00Z")).endsWith("· изменено"))
    }
}
