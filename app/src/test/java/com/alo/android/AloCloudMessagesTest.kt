package com.alo.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AloCloudMessagesTest {
    private val base = "https://api.example.com/"

    // ---- what /api/config allows ----

    @Test
    fun serverFlagsDecideWhichChatsAreCloudChats() {
        val config = JSONObject(
            """{"security":{"plaintextDirectMessagesAllowed":true,"plaintextGroupMessagesAllowed":false}}"""
        )
        val policy = AloMessageSecurityPolicy.fromConfig(config)
        assertTrue(policy.allowsCloud(isGroup = false))
        assertFalse(policy.allowsCloud(isGroup = true))
        assertFalse(policy.cloudEverywhere)
        assertTrue(
            AloMessageSecurityPolicy.fromConfig(
                JSONObject("""{"security":{"plaintextDirectMessagesAllowed":true,"plaintextGroupMessagesAllowed":true}}""")
            ).cloudEverywhere
        )
    }

    @Test
    fun anOlderServerWithoutTheFlagsKeepsLibsignal() {
        val policy = AloMessageSecurityPolicy.fromConfig(JSONObject("""{"security":{"e2eeScheme":"x"}}"""))
        assertFalse(policy.allowsCloud(isGroup = false))
        assertFalse(policy.allowsCloud(isGroup = true))
        assertFalse(AloMessageSecurityPolicy.fromConfig(JSONObject()).allowsCloud(isGroup = false))
    }

    // ---- sending ----

    @Test
    fun formattedTextTravelsAsBodyAndEntitiesNextToEachOther() {
        val payload = AloCloudMessages.buildPayloadFromMarkup("😀 **bold** __italic__", emptyList())

        assertTrue(AloCloudMessages.isCloudPayload(payload))
        assertFalse(payload.has("encrypted"))
        assertEquals("😀 bold italic", payload.getString("body"))
        val entities = payload.getJSONArray("entities")
        assertEquals(2, entities.length())
        assertEquals("bold", entities.getJSONObject(0).getString("type"))
        // UTF-16 offsets: the emoji takes two units.
        assertEquals(3, entities.getJSONObject(0).getInt("offset"))
        assertEquals(4, entities.getJSONObject(0).getInt("length"))
        assertEquals("italic", entities.getJSONObject(1).getString("type"))
    }

    @Test
    fun theServerTrimsTheBodySoTheOffsetsAreMovedBeforeSending() {
        val content = AloCloudMessages.textContent("  **hi** there \n")

        assertEquals("hi there", content.body)
        assertEquals(1, content.entities.size)
        assertEquals(0, content.entities.single().offset)
        assertEquals(2, content.entities.single().length)
    }

    @Test
    fun anEntityOutsideTheTrimmedTextIsDropped() {
        val trimmed = AloCloudMessages.trimmed(
            AloMessageMarkupResult(" a ", listOf(AloMessageTextEntity("bold", 0, 1), AloMessageTextEntity("italic", 1, 1)))
        )
        assertEquals("a", trimmed.body)
        assertEquals(listOf(AloMessageTextEntity("italic", 0, 1)), trimmed.entities)
    }

    @Test
    fun theServerKeepsAtMostOneHundredEntities() {
        val body = "x".repeat(200)
        val entities = (0 until 150).map { AloMessageTextEntity("bold", it, 1) }
        assertEquals(AloCloudMessages.MAX_ENTITIES, AloCloudMessages.trimmed(AloMessageMarkupResult(body, entities)).entities.size)
    }

    @Test
    fun pollEventsStayWholeAsThePlainBody() {
        val definition = AloPollDefinition(
            id = "poll_cloud",
            question = "Куда пойдём?",
            options = listOf(AloPollOption("a", "Парк"), AloPollOption("b", "Кино")),
            allowsMultipleAnswers = false,
            isQuiz = false,
            correctOptionIds = emptySet(),
            isAnonymous = true,
            allowsRevoting = true
        )
        val body = checkNotNull(AloPollCodec.encodeCreate(definition))

        val payload = AloCloudMessages.buildPayloadFromMarkup(body, emptyList())

        assertEquals(body, payload.getString("body"))
        assertEquals(0, payload.getJSONArray("entities").length())
        assertEquals(AloPollEvent.Create(definition), AloPollCodec.decode(payload.getString("body")))
    }

    @Test
    fun theSendRequestCarriesPlainFieldsAndNoEnvelope() {
        val voice = AloOutgoingMessageAttachment(
            url = "/uploads/messages/voice.ogg",
            type = "voice",
            name = "voice.ogg",
            metadata = mapOf("durationSeconds" to "7"),
            waveform = listOf(0.1f, 0.5f)
        )
        val payload = AloCloudMessages.buildPayloadFromMarkup(
            source = "**hi**",
            attachments = listOf(voice),
            forwardFrom = AloMessageForwardSource(userId = 3, messageId = 55, userName = "A")
        )
        val request = JSONObject().put("clientMessageId", "abc")

        AloCloudMessages.putContent(request, payload)

        assertFalse(request.has("encrypted"))
        assertEquals("hi", request.getString("body"))
        assertEquals("bold", request.getJSONArray("entities").getJSONObject(0).getString("type"))
        assertEquals("voice", request.getJSONArray("attachments").getJSONObject(0).getString("type"))
        assertEquals(55, request.getJSONObject("forwardFrom").getInt("messageId"))
        assertFalse(request.has("mentionedUserIds"))
    }

    @Test
    fun groupMentionsBecomeMemberIdsAndNeverTheSender() {
        val directory = mapOf("alice" to 5, "bob" to 9, "mike" to 3)
        val content = AloCloudMessages.textContent("hi @Alice, @bob and @mike, also @stranger and @alice")

        assertEquals(listOf(5, 9), AloCloudMessages.mentionedUserIds(content, directory, selfUserId = 3))
        val payload = AloCloudMessages.buildPayloadFromMarkup(
            "hi @alice", emptyList(), mentionDirectory = directory, selfUserId = 3
        )
        assertEquals(5, payload.getJSONArray("mentionedUserIds").getInt(0))
        assertTrue(AloCloudMessages.mentionedUserIds(content, emptyMap(), selfUserId = 3).isEmpty())
    }

    // ---- attachments ----

    @Test
    fun voiceKeepsItsWaveformAndDurationButNoEncryptionData() {
        val json = AloCloudMessages.attachmentJson(
            AloOutgoingMessageAttachment(
                url = "/uploads/messages/v.ogg",
                type = "voice",
                name = "v.ogg",
                metadata = mapOf("durationSeconds" to "7"),
                waveform = listOf(0.1f, 0.5f, 2f)
            ),
            base
        )

        assertEquals("voice", json.getString("type"))
        assertEquals("/uploads/messages/v.ogg", json.getString("url"))
        assertEquals("7", json.getJSONObject("metadata").getString("durationSeconds"))
        assertFalse(json.has("mediaEncryption"))
        val waveform = json.getJSONArray("waveform")
        assertEquals(3, waveform.length())
        assertEquals(0.5, waveform.getDouble(1), 0.0001)
        assertEquals(1.0, waveform.getDouble(2), 0.0001)
    }

    @Test
    fun videoCirclesAndGifsUseTheTypesTheServerKnows() {
        fun type(type: String, name: String) =
            AloCloudMessages.serverType(AloOutgoingMessageAttachment("/uploads/messages/x", type, name))
        assertEquals("circle", type("video", "video-circle-1700.mp4"))
        assertEquals("video", type("video", "holiday.mp4"))
        assertEquals("circle", type("circle", "c.mp4"))
        assertEquals("image", type("gif", "gif-7.gif"))
        assertEquals("video", type("gif", "gif-7.mp4"))
        assertEquals("file", type("application/pdf", "doc.pdf"))
        assertEquals("sticker", type("sticker", "s.webp"))
    }

    @Test
    fun anAddressOnOurServerIsSentAsAPath() {
        assertEquals("/uploads/messages/a.jpg", AloCloudMessages.serverUrl("https://api.example.com/uploads/messages/a.jpg", base))
        assertEquals("https://cdn.example.org/s.webp", AloCloudMessages.serverUrl("https://cdn.example.org/s.webp", base))
        assertEquals("/assets/stickers/cat.webp", AloCloudMessages.serverUrl("/assets/stickers/cat.webp", base))
    }

    @Test
    fun cardsNeedNoUploadAndFilesNeedAnUploadedAddress() {
        val location = AloOutgoingMessageAttachment.location(55.75, 37.61, title = "Москва")
        val contact = AloOutgoingMessageAttachment.contact(AloEntitySummary(4, "Иван", "ivan", "", false))
        val sticker = AloOutgoingMessageAttachment("https://api.example.com/assets/stickers/cat.webp", "sticker", "Кот")
        val photo = AloOutgoingMessageAttachment("/uploads/messages/p.jpg", "image", "p.jpg")

        assertTrue(AloCloudMessages.isSendableAttachment(location, base))
        assertTrue(AloCloudMessages.isSendableAttachment(contact, base))
        assertTrue(AloCloudMessages.isSendableAttachment(sticker, base))
        assertTrue(AloCloudMessages.isSendableAttachment(photo, base))
        assertEquals("55.75000", AloCloudMessages.attachmentJson(location, base).getJSONObject("metadata").getString("latitude"))
        assertEquals("", AloCloudMessages.attachmentJson(location, base).getString("url"))
        assertEquals("/assets/stickers/cat.webp", AloCloudMessages.attachmentJson(sticker, base).getString("url"))
    }

    @Test
    fun anAttachmentTheServerWouldDropIsRefusedBeforeSending() {
        val sealed = AloOutgoingMessageAttachment(
            "/uploads/messages/e.bin", "image", "e.jpg", mediaEncryption = mapOf("algorithm" to "x")
        )
        val local = AloOutgoingMessageAttachment("content://alo/media/1", "image", "a.jpg")
        val insecureSticker = AloOutgoingMessageAttachment("http://192.168.1.2/assets/s.webp", "sticker", "s")

        assertFalse(AloCloudMessages.isSendableAttachment(sealed, base))
        assertFalse(AloCloudMessages.isSendableAttachment(local, base))
        assertFalse(AloCloudMessages.isSendableAttachment(insecureSticker, base))
        assertNull(AloCloudMessages.cardUrlOrNull("http://192.168.1.2/assets/s.webp", base))
        assertEquals("/assets/s.webp", AloCloudMessages.cardUrlOrNull("https://api.example.com/assets/s.webp", base))
    }

    @Test
    fun theServerSizeLimitsDependOnTheKindOfFile() {
        assertEquals(30L * 1024 * 1024, AloCloudMessages.maxUploadBytes("video/mp4"))
        assertEquals(160L * 1024 * 1024, AloCloudMessages.maxUploadBytes("audio/ogg"))
        assertEquals(25L * 1024 * 1024, AloCloudMessages.maxUploadBytes("application/pdf"))
    }

    @Test
    fun anAttachmentSurvivesTheOutboxRoundTrip() {
        val original = AloOutgoingMessageAttachment(
            url = "/uploads/messages/v.ogg",
            type = "voice",
            name = "v.ogg",
            metadata = mapOf("durationSeconds" to "7"),
            posterUrl = "/uploads/messages/p.jpg",
            waveform = listOf(0.25f, 0.75f)
        )
        assertEquals(original, AloCloudMessages.storedAttachmentFrom(AloCloudMessages.storedAttachmentJson(original)))
    }

    @Test
    fun aForwardedMessageKeepsItsContentInTheDraft() {
        val source = AloMessageForwardSource(
            userId = 3,
            messageId = 55,
            userName = "Анна",
            body = "bold text",
            entities = listOf(AloMessageTextEntity("bold", 0, 4)),
            attachments = listOf(AloOutgoingMessageAttachment("/uploads/messages/p.jpg", "image", "p.jpg")),
            encrypted = true
        )
        val restored = AloCloudMessages.forwardSourceFrom(AloCloudMessages.forwardSourceJson(source))
        assertEquals(source, restored)
        assertTrue(restored.hasContent)
        // A draft written before this version has no content: the server copies a plain message.
        val old = AloCloudMessages.forwardSourceFrom(JSONObject("""{"userId":3,"messageId":55,"userName":"Анна"}"""))
        assertFalse(old.hasContent)
    }

    // ---- reading ----

    @Test
    fun aMessageWithoutSchemeAndEnvelopeIsACloudMessage() {
        assertTrue(AloCloudMessages.isCloudMessageJson(JSONObject("""{"id":1,"body":"hi","encryptionScheme":"","encryptedPayload":null}""")))
        assertFalse(
            AloCloudMessages.isCloudMessageJson(
                JSONObject("""{"id":1,"body":"","encryptionScheme":"alo-libsignal-v1","encryptedPayload":{"scheme":"alo-libsignal-v1"}}""")
            )
        )
        assertFalse(AloCloudMessages.isCloudMessageJson(JSONObject("""{"id":1,"encryptionScheme":"","channelPostId":4}""")))
        assertFalse(AloCloudMessages.isCloudMessageJson(JSONObject("""{"id":1,"encryptionScheme":"","attachmentType":"call"}""")))
    }

    @Test
    fun cloudTextIsNotMarkupButItsLinksAndNamesStillWork() {
        val result = AloMessageMarkup.plain("a **b** https://alo.example/p. hi @alice", emptyList())

        assertEquals("a **b** https://alo.example/p. hi @alice", result.body)
        assertEquals(listOf("link", "mention"), result.entities.map { it.type })
        assertEquals("https://alo.example/p", result.entities.first().url)
    }

    @Test
    fun serverEntitiesWinOverDetectedOnes() {
        val body = "see https://alo.example"
        val explicit = listOf(AloMessageTextEntity("link", 4, 19, "https://alo.example"), AloMessageTextEntity("bold", 0, 3))

        val result = AloMessageMarkup.plain(body, explicit)

        assertEquals(2, result.entities.size)
        assertEquals(1, result.entities.count { it.type == "link" })
    }

    // ---- polls the server counts ----

    @Test
    fun anAnonymousCloudPollShowsTheServerCountAndAnEncryptedOneKeepsItsEvents() {
        val cloudPoll = poll("cloud_poll")
        val oldPoll = poll("old_poll")
        val messages = listOf(
            message(1, checkNotNull(AloPollCodec.encodeCreate(cloudPoll)), cloud = true),
            message(2, checkNotNull(AloPollCodec.encodeCreate(oldPoll)), cloud = false),
            message(-3, checkNotNull(AloPollCodec.encodeVote(oldPoll.id, setOf("yes"))), cloud = false)
        )
        val canonical = mapOf(
            cloudPoll.id to canonicalState(cloudPoll.id, yes = 2, no = 1, selected = setOf("no")),
            oldPoll.id to canonicalState(oldPoll.id, yes = 99, no = 1, selected = setOf("no"))
        )

        val states = AloPollProjection.applyingCanonical(
            canonical,
            AloPollProjection.states(messages, currentUserId = 7),
            AloPollProjection.serverTalliedPollIds(messages)
        )

        assertEquals(setOf(cloudPoll.id), AloPollProjection.serverTalliedPollIds(messages))
        val fromServer = checkNotNull(states[cloudPoll.id])
        assertEquals(mapOf("yes" to 2, "no" to 1), fromServer.voteCounts)
        assertEquals(setOf("no"), fromServer.selectedOptionIds)
        assertEquals(3, fromServer.totalVoters)
        assertTrue(fromServer.publicVoters.isEmpty())
        val fromEvents = checkNotNull(states[oldPoll.id])
        assertEquals(mapOf("yes" to 1, "no" to 0), fromEvents.voteCounts)
        assertEquals(setOf("yes"), fromEvents.selectedOptionIds)
    }

    @Test
    fun voteAndRevealEventsStayOutOfTheVisibleCloudChat() {
        val definition = poll("cloud_quiz")
        val messages = listOf(
            message(1, checkNotNull(AloPollCodec.encodeCreate(definition)), cloud = true),
            message(2, "plain text", cloud = true),
            message(3, checkNotNull(AloPollCodec.encodeVote(definition.id, setOf("yes"))), cloud = true)
        )
        assertEquals(listOf(1, 2), AloPollProjection.visibleMessages(messages).map { it.id })
    }

    private fun poll(id: String) = AloPollDefinition(
        id = id,
        question = "Анонимно?",
        options = listOf(AloPollOption("yes", "Да"), AloPollOption("no", "Нет")),
        allowsMultipleAnswers = false,
        isQuiz = false,
        correctOptionIds = emptySet(),
        isAnonymous = true,
        allowsRevoting = true
    )

    private fun canonicalState(id: String, yes: Int, no: Int, selected: Set<String>) = AloCanonicalPollState(
        id = id,
        messageId = 41,
        voteCounts = mapOf("yes" to yes, "no" to no),
        selectedOptionIds = selected,
        totalVoters = yes + no,
        isClosed = false,
        allowsRevoting = true,
        correctOptionIds = emptySet(),
        publicVoters = emptyList(),
        closesAt = "",
        closedAt = ""
    )

    private fun message(id: Int, body: String, cloud: Boolean) = AloChatMessage(
        id = id,
        roomId = 0,
        senderId = 7,
        recipientId = 8,
        body = body,
        attachmentUrl = "",
        attachmentType = "",
        attachmentName = "",
        createdAt = id.toString(),
        editedAt = "",
        readAt = "",
        cloud = cloud,
        encrypted = !cloud
    )
}
