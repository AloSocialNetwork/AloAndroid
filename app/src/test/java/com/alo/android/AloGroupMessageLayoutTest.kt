package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloGroupMessageLayoutTest {
    private fun message(
        id: Int,
        senderId: Int,
        day: String = "2026-09-05",
        anonymous: Boolean = false,
        adminTitle: String = "",
        topicId: Int = 0
    ) = AloChatMessage(
        id = id, roomId = 1, senderId = senderId, recipientId = 0, body = "", attachmentUrl = "", attachmentType = "",
        attachmentName = "", createdAt = "${day}T10:00:00Z", editedAt = "", readAt = "",
        senderIsAnonymous = anonymous, senderAdminTitle = adminTitle, topicId = topicId
    )

    private fun positions(vararg messages: AloChatMessage, breaksRun: (AloChatMessage) -> Boolean = { false }) =
        messages.mapIndexed { index, current ->
            aloGroupRunPosition(
                previous = messages.getOrNull(index - 1),
                current = current,
                next = messages.getOrNull(index + 1),
                breaksRun = breaksRun,
                dayOf = { it.createdAt.take(10) }
            )
        }

    @Test
    fun aLoneMessageStartsAndEndsItsRun() {
        assertEquals(listOf(AloGroupRunPosition.Single), positions(message(1, 10)))
    }

    @Test
    fun aRunShowsTheNameOnItsFirstMessageAndTheAvatarOnItsLast() {
        val run = positions(message(1, 10), message(2, 10), message(3, 10))
        assertEquals(
            listOf(
                AloGroupRunPosition(startsRun = true, endsRun = false),
                AloGroupRunPosition(startsRun = false, endsRun = false),
                AloGroupRunPosition(startsRun = false, endsRun = true)
            ),
            run
        )
    }

    @Test
    fun anotherSenderStartsANewRun() {
        val run = positions(message(1, 10), message(2, 11), message(3, 10))
        assertTrue(run.all { it == AloGroupRunPosition.Single })
    }

    @Test
    fun aNewDaySplitsARun() {
        val run = positions(message(1, 10, day = "2026-09-05"), message(2, 10, day = "2026-09-06"))
        assertTrue(run.all { it == AloGroupRunPosition.Single })
    }

    @Test
    fun aSystemLineOrCallRecordInTheMiddleSplitsARun() {
        val run = positions(message(1, 10), message(2, 10), message(3, 10), breaksRun = { it.id == 2 })
        assertTrue(run.all { it == AloGroupRunPosition.Single })
    }

    @Test
    fun anotherForumTopicSplitsARun() {
        val run = positions(message(1, 10, topicId = 1), message(2, 10, topicId = 2))
        assertTrue(run.all { it == AloGroupRunPosition.Single })
    }

    @Test
    fun anAnonymousAdminAndTheSameUserWritingNormallyAreNotOneRun() {
        val run = positions(message(1, 10, anonymous = true), message(2, 10, anonymous = false))
        assertTrue(run.all { it == AloGroupRunPosition.Single })
    }

    private fun member(id: Int, role: String, adminTitle: String = "") = AloGroupMember(
        AloEntitySummary(id = id, name = "n$id", username = "", avatarUrl = "", verified = false),
        role = role, adminTitle = adminTitle
    )

    private val members = listOf(
        member(1, "owner"), member(2, "admin"), member(3, "admin", adminTitle = "Куратор"), member(4, "member")
    )

    @Test
    fun theOwnerAndAnAdminGetTheirDefaultLabels() {
        assertEquals("Владелец", aloGroupSenderRoleLabel(message(10, 1), members))
        assertEquals("Админ", aloGroupSenderRoleLabel(message(10, 2), members))
    }

    @Test
    fun anAdminsOwnTitleReplacesTheDefaultLabel() {
        assertEquals("Куратор", aloGroupSenderRoleLabel(message(10, 3), members))
        assertEquals("Модератор", aloGroupSenderRoleLabel(message(10, 2, adminTitle = "Модератор"), members))
    }

    @Test
    fun anOrdinaryMemberOrAStrangerHasNoTag() {
        assertEquals("", aloGroupSenderRoleLabel(message(10, 4), members))
        assertEquals("", aloGroupSenderRoleLabel(message(10, 99), members))
    }

    @Test
    fun anAnonymousAdminShowsOnlyTheTitleTheMessageCarries() {
        assertEquals("Куратор", aloGroupSenderRoleLabel(message(10, 1, anonymous = true, adminTitle = " Куратор "), members))
        assertEquals("", aloGroupSenderRoleLabel(message(10, 1, anonymous = true), members))
    }

    @Test
    fun aNameHasTheColourOfItsSendersAvatarAndKeepsItBetweenCalls() {
        assertEquals(aloSenderNameColor("990012", false), aloSenderNameColor("990012", false))
        // Numeric seeds pick the palette entry by id, so ids 7 apart share a colour and neighbours differ.
        assertEquals(aloSenderNameColor("7", false), aloSenderNameColor("14", false))
        assertNotEquals(aloSenderNameColor("1", false), aloSenderNameColor("2", false))
    }

    @Test
    fun aLightBubbleGetsADarkerNameThanADarkOne() {
        for (seed in listOf("0", "1", "2", "3", "4", "5", "6")) {
            val dark = aloSenderNameColor(seed, lightSurface = false)
            val light = aloSenderNameColor(seed, lightSurface = true)
            assertFalse(seed, dark == light)
            assertTrue(seed, luminance(light) < luminance(dark))
        }
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color) =
        0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

    @Test
    fun aQuoteTakesTheColourOfItsAuthorsNameAndKeepsTheFallbackWhenTheSourceIsGone() {
        val fallback = androidx.compose.ui.graphics.Color.Magenta
        val source = message(5, 990012)
        assertEquals(aloSenderNameColor("990012", false), aloReplyQuoteColor(source, lightSurface = false, fallback = fallback))
        assertEquals(aloSenderNameColor("990012", true), aloReplyQuoteColor(source, lightSurface = true, fallback = fallback))
        assertEquals(fallback, aloReplyQuoteColor(null, lightSurface = false, fallback = fallback))
    }
}
