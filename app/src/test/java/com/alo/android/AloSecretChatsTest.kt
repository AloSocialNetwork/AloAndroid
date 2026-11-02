package com.alo.android

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloSecretChatsTest {
    private val deviceId = "android-device-1"
    private val peerDeviceId = "web_peer_device_1"

    // ---- requests ----

    @Test
    fun theServerFlagAndTheRequestBodiesFollowTheContract() {
        assertTrue(
            AloMessageSecurityPolicy.fromConfig(JSONObject("""{"security":{"secretChats":true}}""")).secretChatsEnabled
        )
        assertFalse(AloMessageSecurityPolicy.fromConfig(JSONObject("""{"security":{}}""")).secretChatsEnabled)

        val start = AloSecretChatCodec.startBody(12, deviceId)
        assertEquals(12, start.getInt("peerUserId"))
        assertEquals(deviceId, start.getString("deviceId"))
        assertEquals(deviceId, AloSecretChatCodec.acceptBody(deviceId).getString("deviceId"))
        val read = AloSecretChatCodec.readBody(deviceId, 41)
        assertEquals(41, read.getInt("upToMessageId"))
        assertEquals(deviceId, read.getString("deviceId"))
        val timer = AloSecretChatCodec.timerBody(deviceId, 30)
        assertEquals(30, timer.getInt("seconds"))
        assertEquals(deviceId, timer.getString("deviceId"))
        assertEquals("/api/secret-chats?deviceId=android-device-1", AloSecretChatCodec.listPath(deviceId))
        assertEquals("/api/secret-chats/7?deviceId=a%2Fb", AloSecretChatCodec.deletePath(7, "a/b"))
        assertEquals(
            "/api/secret-chats/7/messages?deviceId=android-device-1&limit=100",
            AloSecretChatCodec.messagesPath(7, deviceId)
        )
        assertTrue(AloSecretChatCodec.messagesPath(7, deviceId, beforeId = 90).endsWith("&beforeId=90"))
    }

    @Test
    fun aSecretMessageBodyCarriesTheEnvelopeAndOnlyOpaqueFiles() {
        val envelope = envelope(listOf(recipient(3, peerDeviceId)))
        val file = AloOutgoingMessageAttachment(
            url = "/uploads/messages/sealed.bin",
            type = "image",
            name = "photo.jpg",
            mediaEncryption = mapOf("key" to "secret"),
            metadata = mapOf("width" to "10")
        )
        val body = AloSecretChatCodec.messageBody(deviceId, envelope, listOf(file), "cm-0123456789abcdef", 30)

        assertEquals(deviceId, body.getString("deviceId"))
        assertEquals(30, body.getInt("selfDestructSeconds"))
        assertEquals("cm-0123456789abcdef", body.getString("clientMessageId"))
        assertEquals(envelope.toString(), body.getJSONObject("encrypted").toString())
        val sent = body.getJSONArray("attachments").getJSONObject(0)
        assertEquals("e2ee", sent.getString("type"))
        assertEquals("/uploads/messages/sealed.bin", sent.getString("url"))
        // The server learns neither the real type, nor the keys, nor the size.
        assertFalse(sent.has("mediaEncryption"))
        assertFalse(sent.has("metadata"))
        assertFalse(body.toString().contains("secret"))
        assertFalse(AloSecretChatCodec.messageBody(deviceId, envelope, emptyList(), "cm-0123456789abcdef", 0).has("attachments"))
    }

    // ---- the one-recipient envelope ----

    private fun recipient(userId: Int, device: String) = JSONObject()
        .put("userId", userId)
        .put("deviceId", device)
        .put("protocolDeviceId", 2)
        .put("ciphertextType", 3)
        .put("ciphertext", "AAAA")

    private fun envelope(recipients: List<JSONObject>, purpose: String = "message", mode: String? = null) = JSONObject()
        .put("scheme", "alo-libsignal-v1")
        .put("protocolVersion", 1)
        .put("purpose", purpose)
        .put("contextId", "msg_0123456789abcdef")
        .put("senderUserId", 9)
        .put("senderDeviceId", deviceId)
        .put("senderProtocolDeviceId", 1)
        .put("recipients", JSONArray().also { array -> recipients.forEach(array::put) })
        .also { if (mode != null) it.put("mode", mode) }

    @Test
    fun theEnvelopeIsForExactlyTheDeviceAtTheOtherEnd() {
        assertTrue(
            AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, peerDeviceId))), 3, peerDeviceId, deviceId)
        )
        assertTrue(
            AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, peerDeviceId)), mode = "direct"), 3, peerDeviceId, deviceId)
        )
        // A copy for one of the sender's own devices, or for another device of the person, is refused.
        assertFalse(
            AloSecretChatCodec.isSingleRecipientEnvelope(
                envelope(listOf(recipient(3, peerDeviceId), recipient(9, "my-other-device"))), 3, peerDeviceId, deviceId
            )
        )
        assertFalse(
            AloSecretChatCodec.isSingleRecipientEnvelope(
                envelope(listOf(recipient(3, peerDeviceId), recipient(3, "web_peer_device_2"))), 3, peerDeviceId, deviceId
            )
        )
        assertFalse(AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, "web_peer_device_2"))), 3, peerDeviceId, deviceId))
        assertFalse(AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(4, peerDeviceId))), 3, peerDeviceId, deviceId))
        assertFalse(AloSecretChatCodec.isSingleRecipientEnvelope(envelope(emptyList()), 3, peerDeviceId, deviceId))
        assertFalse(
            AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, peerDeviceId)), purpose = "call"), 3, peerDeviceId, deviceId)
        )
        assertFalse(
            AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, peerDeviceId)), mode = "sender-key"), 3, peerDeviceId, deviceId)
        )
        assertFalse(
            AloSecretChatCodec.isSingleRecipientEnvelope(envelope(listOf(recipient(3, peerDeviceId))), 3, peerDeviceId, "another-sender")
        )
    }

    // ---- reading ----

    @Test
    fun theListOfTheServerBecomesChatsWithTheirRoles() {
        val json = JSONObject(
            """{"id":5,"status":"pending","role":"incoming","peerUserId":3,
                "peerUser":{"id":3,"name":"Борис","username":"boris","avatarEmoji":"/u/b.jpg","verified":true},
                "localDeviceId":"","remoteDeviceId":"web_peer_device_1","ttlSeconds":0,"unreadCount":0,
                "createdAt":"2026-10-25T10:00:00.000Z","acceptedAt":""}"""
        )
        val incoming = checkNotNull(AloSecretChatCodec.parseChat(json))
        assertTrue(incoming.isIncomingRequest)
        assertFalse(incoming.isWaiting)
        assertEquals("Борис", incoming.peerName)
        assertEquals("Запрос секретного чата", AloSecretChatList.preview(incoming))

        val waiting = checkNotNull(AloSecretChatCodec.parseChat(JSONObject(json.toString()).put("role", "started")))
        assertTrue(waiting.isWaiting)
        assertEquals("Ждём, пока Борис зайдёт в сеть…", AloSecretChatList.preview(waiting))

        val active = checkNotNull(AloSecretChatCodec.parseChat(JSONObject(json.toString()).put("role", "accepted").put("status", "active")))
        assertTrue(active.isActive)
        assertEquals("Секретный чат", AloSecretChatList.preview(active))
    }

    @Test
    fun anOpenedMessageKeepsItsTextAndRealFilesNotTheOpaqueOnes() {
        val message = AloSecretChatCodec.parseMessage(
            JSONObject(
                """{"id":8,"secretChatId":5,"senderUserId":3,"senderDeviceId":"x","body":"Привет",
                    "entities":[{"type":"bold","offset":0,"length":6}],
                    "attachments":[{"type":"e2ee","url":"/uploads/messages/a.bin","name":"encrypted.bin"},
                                   {"type":"image","url":"content://alo/media/1","name":"p.jpg","mediaEncryption":{"key":"k"}}],
                    "selfDestructSeconds":30,"createdAt":"2026-10-25T10:00:00.000Z","readAt":"","expiresAt":"",
                    "encryptedPayload":{"contextId":"msg_abcdef0123456789"}}"""
            )
        )
        assertEquals("Привет", message.body)
        assertEquals(listOf("bold"), message.entities.map { it.type })
        assertEquals(listOf("image"), message.attachments.map { it.type })
        assertEquals(30, message.selfDestructSeconds)
        assertEquals("msg_abcdef0123456789", message.contextId)
        assertFalse(message.unreadable)
        assertTrue(
            AloSecretChatCodec.parseMessage(
                JSONObject("""{"id":9,"body":"${AloSecretChatCodec.UNREADABLE_BEFORE_DEVICE}"}""")
            ).unreadable
        )
    }

    // ---- the list: merging and sorting ----

    private fun summary(peerId: Int, lastAt: String) = AloConversationSummary(
        peerId = peerId,
        kind = "direct",
        roomId = 0,
        memberCount = 0,
        lastAt = lastAt,
        lastBody = "hi",
        peer = AloConversationPeer(peerId, "Чат $peerId", "u$peerId", "", false)
    )

    private fun secret(id: Int, activity: String, role: String = "accepted", status: String = "active", unread: Int = 0) =
        AloSecretChat(
            id = id,
            status = status,
            role = role,
            peerUserId = 100 + id,
            peerName = "Тайный $id",
            createdAt = activity,
            unreadCount = unread
        )

    @Test
    fun secretChatsSitAmongTheOrdinaryOnesByLastActivityAndPinnedChatsStayOnTop() {
        val pinned = summary(1, "2026-10-20T10:00:00.000Z")
        val newest = summary(2, "2026-10-25T12:00:00.000Z")
        val middle = summary(3, "2026-10-25T09:00:00.000Z")
        val oldest = summary(4, "2026-10-01T09:00:00.000Z")
        val arranged = listOf(pinned, newest, middle, oldest)
        val pinnedKeys = setOf(AloChatListPrefs.key(pinned))
        val secrets = listOf(
            secret(10, "2026-10-25T10:00:00.000Z"),
            secret(11, "2026-10-25T13:00:00.000Z"),
            secret(12, "2026-09-01T09:00:00.000Z")
        )

        val merged = AloSecretChatList.merge(arranged, pinnedKeys, secrets)

        assertEquals(
            listOf("direct:1", "secret:11", "direct:2", "secret:10", "direct:3", "direct:4", "secret:12"),
            merged.map { it.key }
        )
    }

    @Test
    fun withoutSecretChatsTheListIsUntouched() {
        val arranged = listOf(summary(1, "2026-10-25T12:00:00.000Z"), summary(2, "2026-10-24T12:00:00.000Z"))
        val merged = AloSecretChatList.merge(arranged, emptySet(), emptyList())
        assertEquals(arranged, merged.map { it.summary })
        assertTrue(merged.all { it.secret == null })
    }

    @Test
    fun aSecretChatRowIsMarkedAndCannotBeMistakenForAPeerOrAGroup() {
        val request = secret(5, "2026-10-25T10:00:00.000Z", role = "incoming", status = "pending")
        val row = AloSecretChatList.toSummary(request)
        assertEquals("secret", row.kind)
        assertTrue(row.peerId < 0)
        assertEquals("Запрос секретного чата", row.lastBody)
        // A request shows a badge even though it has no unread messages.
        assertEquals(1, row.unreadCount)
        assertEquals(3, AloSecretChatList.toSummary(secret(6, "2026-10-25T10:00:00.000Z", unread = 3)).unreadCount)
    }

    @Test
    fun theListFromTheServerReplacesOursNewestFirstAndADeletedChatDisappears() {
        val fresh = listOf(secret(1, "2026-10-01T00:00:00.000Z"), secret(2, "2026-10-02T00:00:00.000Z"))
        val replaced = AloSecretChatList.replace(fresh)
        assertEquals(listOf(2, 1), replaced.map { it.id })
        assertEquals(listOf(1), AloSecretChatList.removing(replaced, 2).map { it.id })
        val upserted = AloSecretChatList.upserting(replaced, secret(1, "2026-10-05T00:00:00.000Z"))
        assertEquals(listOf(1, 2), upserted.map { it.id })
        assertEquals(2, upserted.size)
        assertTrue(AloSecretChatList.matches(secret(3, "2026-10-01T00:00:00.000Z"), "тайный"))
        assertFalse(AloSecretChatList.matches(secret(3, "2026-10-01T00:00:00.000Z"), "никто"))
    }

    // ---- timers ----

    private fun message(id: Int, from: Int, seconds: Int = 0, readAt: String = "", expiresAt: String = "") = AloSecretMessage(
        id = id,
        secretChatId = 5,
        senderUserId = from,
        selfDestructSeconds = seconds,
        createdAt = "2026-10-25T10:00:00.000Z",
        readAt = readAt,
        expiresAt = expiresAt
    )

    @Test
    fun theTimerOfAReceivedMessageStartsWhenItIsRead() {
        val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        val messages = listOf(
            message(1, from = 3, seconds = 60),
            message(2, from = 3, seconds = 0),
            message(3, from = 9, seconds = 60),
            message(4, from = 3, seconds = 30, readAt = "2026-10-25T11:00:00Z", expiresAt = "2026-10-25T11:00:30Z")
        )

        val result = AloSecretTimers.applyRead(messages, myUserId = 9, nowMillis = now)

        assertEquals(2, result.upToMessageId)
        val byId = result.messages.associateBy { it.id }
        assertEquals("2026-10-25T12:00:00Z", byId.getValue(1).readAt)
        assertEquals("2026-10-25T12:01:00Z", byId.getValue(1).expiresAt)
        // No timer: read, but it never ends.
        assertEquals("2026-10-25T12:00:00Z", byId.getValue(2).readAt)
        assertEquals("", byId.getValue(2).expiresAt)
        // Our own message starts its timer when the OTHER person reads it, not now.
        assertEquals("", byId.getValue(3).readAt)
        assertEquals("", byId.getValue(3).expiresAt)
        // Already read: untouched.
        assertEquals("2026-10-25T11:00:30Z", byId.getValue(4).expiresAt)

        val again = AloSecretTimers.applyRead(result.messages, myUserId = 9, nowMillis = now + 5_000)
        assertEquals(0, again.upToMessageId)
        assertEquals(result.messages, again.messages)
    }

    @Test
    fun aMessageDisappearsWhenItsTimeIsUp() {
        val now = Instant.parse("2026-10-25T12:00:30Z").toEpochMilli()
        val live = message(1, 3, expiresAt = "2026-10-25T12:00:31Z")
        val over = message(2, 3, expiresAt = "2026-10-25T12:00:30Z")
        val noTimer = message(3, 3)
        assertEquals(listOf(1, 3), AloSecretTimers.visible(listOf(live, over, noTimer), now).map { it.id })
        assertTrue(AloSecretTimers.isExpired(over, now))
        assertFalse(AloSecretTimers.isExpired(noTimer, now))
        assertEquals("", AloSecretTimers.expiresAtAfterRead(now, 0))
    }

    @Test
    fun theTimerMenuAndTheCountdownSpeakInPlainRussian() {
        assertEquals(listOf(0, 5, 30, 60, 3600, 86400, 604800), AloSecretChatCodec.TTL_CHOICES.map { it.first })
        assertTrue(AloSecretChatCodec.TTL_CHOICES.all { it.first in setOf(0, 1, 2, 3, 5, 10, 15, 30, 60, 300, 3600, 86400, 604800) })
        assertEquals("1 неделя", AloSecretChatCodec.timerLabel(604800))
        assertEquals("5 с", AloSecretChatCodec.countdownLabel(4_200))
        assertEquals("1 мин", AloSecretChatCodec.countdownLabel(60_000))
        assertEquals("2 ч", AloSecretChatCodec.countdownLabel(2L * 3_600_000 - 1))
        assertEquals("1 дн.", AloSecretChatCodec.countdownLabel(86_400_000))
        assertEquals("0 с", AloSecretChatCodec.countdownLabel(-5))
        assertEquals(30, AloSecretTimers.secondsForNewMessage(secret(1, "2026-10-01T00:00:00.000Z").copy(ttlSeconds = 30)))
    }

    // ---- live updates ----

    @Test
    fun everySecretChatEventRefreshesAndADeletionIsRecognised() {
        fun event(type: String) = AloChatStreamEvent(
            id = 7,
            name = "update",
            payload = JSONObject("""{"domain":"secret_chats","type":"$type","data":{"secretChatId":12}}""")
        )
        listOf("secret_chat_requested", "secret_chat_accepted", "secret_chat_message", "secret_chat_read", "secret_chat_timer").forEach { type ->
            assertEquals(AloUnifiedUpdateAction.RefreshSecretChats(12, closed = false), AloUnifiedUpdateRouter.action(event(type)))
        }
        assertEquals(AloUnifiedUpdateAction.RefreshSecretChats(12, closed = true), AloUnifiedUpdateRouter.action(event("secret_chat_closed")))
    }

    // ---- voice messages and circles (docs/qa/test-run-2026-10-26.md, P2) ----

    @Test
    fun aRecordedVoiceMessageIsDescribedLikeInACloudChat() {
        val draft = AloChatDraftAttachment(
            attachmentType = "voice",
            mimeType = "audio/mp4",
            name = "voice-1730000000000.m4a",
            durationSec = 4,
            waveform = List(48) { index -> (index % 8) / 8f }
        )
        val sealed = mapOf("algorithm" to "alo-aes-gcm-chunked-v1", "mime" to "audio/mp4")
        val attachment = AloSecretChatCodec.outgoingAttachment(draft, "/uploads/messages/a.bin", sealed)
        assertEquals("voice", attachment.type)
        assertEquals("voice-1730000000000.m4a", attachment.name)
        assertEquals("4", attachment.metadata["durationSeconds"])
        assertEquals(48, attachment.waveform.size)
        assertEquals(sealed, attachment.mediaEncryption)
        assertTrue(AloSecretChatCodec.isVoice(attachment))
        assertFalse(AloSecretChatCodec.isCircle(attachment))
    }

    @Test
    fun aRecordedCircleIsACircleWithItsLengthAndSquareSize() {
        val draft = AloChatDraftAttachment(
            attachmentType = "video",
            mimeType = "video/mp4",
            name = "VID_0001.mp4",
            durationSec = 9,
            isVideoCircle = true,
            width = 480,
            height = 480
        )
        val attachment = AloSecretChatCodec.outgoingAttachment(draft, "/uploads/messages/b.bin", emptyMap(), nowMillis = 42)
        assertEquals("circle", attachment.type)
        assertEquals("video-circle-42.mp4", attachment.name)
        assertEquals("9", attachment.metadata["durationSeconds"])
        assertEquals("480", attachment.metadata["width"])
        assertEquals("480", attachment.metadata["height"])
        assertTrue(attachment.waveform.isEmpty())
        assertTrue(AloSecretChatCodec.isCircle(attachment))
        // A circle from an older app: a `video` named like a circle.
        assertTrue(AloSecretChatCodec.isCircle(attachment.copy(type = "video")))
        // A photo stays a photo, an unknown file a file.
        assertEquals("image", AloSecretChatCodec.outgoingAttachment(draft.copy(isVideoCircle = false, attachmentType = "image"), "/u", emptyMap()).type)
        assertEquals("file", AloSecretChatCodec.outgoingAttachment(draft.copy(isVideoCircle = false, attachmentType = "document"), "/u", emptyMap()).type)
    }

    @Test
    fun theChatListNamesARecordingByWhatItIs() {
        fun last(type: String, name: String) = AloSecretMessage(
            id = 1, secretChatId = 2, senderUserId = 3,
            attachments = listOf(AloOutgoingMessageAttachment(url = "content://x", type = type, name = name))
        )
        assertEquals("Видеосообщение", AloSecretChatList.messagePreview(last("circle", "circle-1.mp4")))
        assertEquals("Видеосообщение", AloSecretChatList.messagePreview(last("video", "video-circle-1.mp4")))
        assertEquals("Голосовое сообщение", AloSecretChatList.messagePreview(last("voice", "voice-1.m4a")))
        assertEquals("Голосовое сообщение", AloSecretChatList.messagePreview(last("audio", "voice-1.m4a")))
        assertEquals("Видео", AloSecretChatList.messagePreview(last("video", "trip.mp4")))
        assertEquals("Голосовое · 0:04", secretDraftLabel(AloChatDraftAttachment(attachmentType = "voice", durationSec = 4)))
        assertEquals("Видеосообщение · 1:05", secretDraftLabel(AloChatDraftAttachment(isVideoCircle = true, durationSec = 65)))
    }
}
