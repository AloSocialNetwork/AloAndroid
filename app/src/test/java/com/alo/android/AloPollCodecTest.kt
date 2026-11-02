package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloPollCodecTest {
    @Test
    fun encryptedPollProjectionKeepsLatestVotePerSender() {
        val definition = AloPollDefinition(
            id = "poll_42",
            question = "Куда пойдём?",
            options = listOf(
                AloPollOption("a", "Парк"),
                AloPollOption("b", "Кино")
            ),
            allowsMultipleAnswers = false,
            isQuiz = false,
            correctOptionIds = emptySet(),
            isAnonymous = true,
            allowsRevoting = true,
            closesAt = "2026-09-01T12:00:00Z"
        )
        val create = checkNotNull(AloPollCodec.encodeCreate(definition))
        val firstVote = checkNotNull(AloPollCodec.encodeVote(definition.id, setOf("a")))
        val changedVote = checkNotNull(AloPollCodec.encodeVote(definition.id, setOf("b")))

        assertEquals(AloPollEvent.Create(definition), AloPollCodec.decode(create))
        assertEquals("Опрос: Куда пойдём?", buildConversationPreview(message(-9, 7, create)))
        val messages = listOf(
            message(-1, 7, create),
            message(-2, 7, firstVote),
            message(-3, 7, changedVote)
        )
        val state = checkNotNull(AloPollProjection.states(messages, currentUserId = 7)[definition.id])
        assertEquals(0, state.voteCounts["a"])
        assertEquals(1, state.voteCounts["b"])
        assertEquals(setOf("b"), state.selectedOptionIds)
        assertEquals("2026-09-01T12:00:00Z", state.closesAt)
        assertEquals(listOf(-1), AloPollProjection.visibleMessages(messages).map { it.id })
        assertTrue(AloPollCodec.decode("ordinary message") == null)
    }

    @Test
    fun encryptedPollCanClearTheLastVote() {
        val body = checkNotNull(AloPollCodec.encodeVote("poll_clear", emptySet()))
        assertEquals(AloPollEvent.Vote("poll_clear", emptySet()), AloPollCodec.decode(body))
    }

    @Test
    fun anonymousPollKeepsE2eeProjectionWhenCanonicalStateDiffers() {
        val definition = AloPollDefinition(
            id = "private_poll",
            question = "Анонимно?",
            options = listOf(AloPollOption("yes", "Да"), AloPollOption("no", "Нет")),
            allowsMultipleAnswers = false,
            isQuiz = false,
            correctOptionIds = emptySet(),
            isAnonymous = true,
            allowsRevoting = true
        )
        val create = checkNotNull(AloPollCodec.encodeCreate(definition))
        val vote = checkNotNull(AloPollCodec.encodeVote(definition.id, setOf("yes")))
        val projected = AloPollProjection.states(
            listOf(message(-1, 7, create), message(-2, 7, vote)),
            currentUserId = 7
        )
        val canonical = AloCanonicalPollState(
            id = definition.id,
            messageId = 41,
            voteCounts = mapOf("yes" to 99, "no" to 1),
            selectedOptionIds = setOf("no"),
            totalVoters = 100,
            isClosed = false,
            allowsRevoting = true,
            correctOptionIds = emptySet(),
            publicVoters = emptyList(),
            closesAt = "",
            closedAt = ""
        )

        val state = checkNotNull(
            AloPollProjection.applyingCanonical(mapOf(definition.id to canonical), projected)[definition.id]
        )
        assertEquals(mapOf("yes" to 1, "no" to 0), state.voteCounts)
        assertEquals(setOf("yes"), state.selectedOptionIds)
        assertEquals(1, state.totalVoters)
        assertTrue(state.publicVoters.isEmpty())
    }

    @Test
    fun encryptedQuizPayloadDoesNotRevealTheCorrectAnswer() {
        val definition = AloPollDefinition(
            id = "quiz_secret",
            question = "Выберите ответ",
            options = listOf(
                AloPollOption("a", "Первый"),
                AloPollOption("b", "Второй")
            ),
            allowsMultipleAnswers = false,
            isQuiz = true,
            correctOptionIds = setOf("b"),
            explanation = "Верный вариант объясняется после ответа.",
            isAnonymous = false,
            allowsRevoting = false
        )

        val prepared = checkNotNull(AloPollQuizCommitment.prepare(definition))
        val body = checkNotNull(AloPollCodec.encodeCreate(prepared.definition))
        val decoded = AloPollCodec.decode(body) as AloPollEvent.Create

        assertTrue(decoded.definition.correctOptionIds.isEmpty())
        assertEquals(prepared.definition.quizAnswerCommitment, decoded.definition.quizAnswerCommitment)
        assertTrue(
            AloPollQuizCommitment.verifies(
                pollId = definition.id,
                correctOptionIds = definition.correctOptionIds,
                salt = prepared.salt,
                commitment = decoded.definition.quizAnswerCommitment
            )
        )
        assertTrue(
            AloPollQuizCommitment.verifies(
                pollId = "quiz_vector",
                correctOptionIds = setOf("answer_b"),
                salt = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldY",
                commitment = "15ed66537e2f5d394deb9f0e2f04e0fca43595638d139382f62e0b6938fac691"
            )
        )
        assertEquals("Верный вариант объясняется после ответа.", decoded.definition.explanation)
        assertTrue(!body.contains("correctOptionIds"))
        assertEquals(listOf("Первый", "Второй"), decoded.definition.options.map { it.text })
    }

    @Test
    fun quizAcceptsOnlyTheCreatorRevealMatchingItsCommitment() {
        val original = AloPollDefinition(
            id = "quiz_reveal",
            question = "Выберите ответ",
            options = listOf(AloPollOption("a", "Первый"), AloPollOption("b", "Второй")),
            allowsMultipleAnswers = false,
            isQuiz = true,
            correctOptionIds = setOf("b"),
            isAnonymous = true,
            allowsRevoting = false
        )
        val prepared = checkNotNull(AloPollQuizCommitment.prepare(original))
        val create = checkNotNull(AloPollCodec.encodeCreate(prepared.definition))
        val reveal = checkNotNull(
            AloPollCodec.encodeReveal(original.id, original.correctOptionIds, prepared.salt)
        )
        val canonical = AloCanonicalPollState(
            id = original.id,
            messageId = 1,
            voteCounts = mapOf("a" to 0, "b" to 0),
            selectedOptionIds = emptySet(),
            totalVoters = 0,
            isClosed = true,
            allowsRevoting = false,
            correctOptionIds = emptySet(),
            publicVoters = emptyList(),
            closesAt = "",
            closedAt = "2026-08-27T00:00:00Z"
        )
        val forged = AloPollProjection.applyingCanonical(
            mapOf(original.id to canonical),
            AloPollProjection.states(
                listOf(message(-1, 7, create), message(-2, 8, reveal)),
                currentUserId = 8
            )
        )
        assertEquals(emptySet<String>(), forged[original.id]?.correctOptionIds)

        val messages = listOf(message(-1, 7, create), message(-2, 7, reveal))
        val verified = AloPollProjection.applyingCanonical(
            mapOf(original.id to canonical),
            AloPollProjection.states(messages, currentUserId = 8)
        )
        assertEquals(setOf("b"), verified[original.id]?.correctOptionIds)
        assertEquals(listOf(-1), AloPollProjection.visibleMessages(messages).map { it.id })
    }

    @Test
    fun protectedMediaScreenshotEventHasAStableOpaqueCodec() {
        val body = AloChatSystemEventCodec.encode(AloChatSystemEvent.ProtectedMediaScreenshot)

        assertEquals(AloChatSystemEvent.ProtectedMediaScreenshot, AloChatSystemEventCodec.decode(body))
        assertEquals("Сделан снимок исчезающего медиа", AloChatSystemEventCodec.previewText(body))
        assertEquals(
            "Вы сделали снимок исчезающего медиа",
            AloChatSystemEventCodec.previewText(body, isMine = true)
        )
        assertTrue(AloChatSystemEventCodec.decode("alo.system.v1:unknown") == null)
    }

    private fun message(id: Int, senderId: Int, body: String) = AloChatMessage(
        id = id,
        roomId = 0,
        senderId = senderId,
        recipientId = 8,
        body = body,
        attachmentUrl = "",
        attachmentType = "",
        attachmentName = "",
        createdAt = id.toString(),
        editedAt = "",
        readAt = ""
    )

    @Test
    fun theChatListNeverShowsCodedPolls() {
        val definition = AloPollDefinition(
            id = "poll_43",
            question = "Куда пойдём?",
            options = listOf(AloPollOption("a", "Парк"), AloPollOption("b", "Кино")),
            allowsMultipleAnswers = false,
            isQuiz = false,
            correctOptionIds = emptySet(),
            isAnonymous = true,
            allowsRevoting = true,
            closesAt = ""
        )
        val create = checkNotNull(AloPollCodec.encodeCreate(definition))
        assertEquals("Опрос: Куда пойдём?", aloReadableMessageText(create))
        assertEquals("Опрос", aloReadableMessageText(checkNotNull(AloPollCodec.encodeVote(definition.id, setOf("a")))))
        // A summary cut short by the server cannot be decoded, and is still not shown as code.
        assertEquals("Опрос", aloReadableMessageText(create.take(create.length / 2)))
        assertEquals("Обычный текст", aloReadableMessageText("Обычный текст"))
        assertEquals("", aloReadableMessageText(""))
    }
}
