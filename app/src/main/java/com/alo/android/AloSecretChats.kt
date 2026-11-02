package com.alo.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/**
 * Secret chats, the way Telegram does them (docs/cloud-and-secret-chats.md, stage 2). A secret chat belongs to two
 * devices: this one and the device at the other end. Its messages are libsignal envelopes for that one device, its
 * files are sealed before they are uploaded, and all of it is deleted from the server with the chat.
 * The id of a secret chat lives in its own space: it is never a `peerId` or a group id.
 */
data class AloSecretMessage(
    val id: Int,
    val secretChatId: Int,
    val senderUserId: Int,
    val senderDeviceId: String = "",
    val body: String = "",
    val entities: List<AloMessageTextEntity> = emptyList(),
    val attachments: List<AloOutgoingMessageAttachment> = emptyList(),
    val clientMessageId: String = "",
    val selfDestructSeconds: Int = 0,
    val createdAt: String = "",
    val readAt: String = "",
    val expiresAt: String = "",
    /** The envelope's id: the key of this device's own copy of the text, deleted with the message. */
    val contextId: String = "",
    /** This device cannot open the envelope (sent before it joined, or damaged): [body] says so. */
    val unreadable: Boolean = false
)

data class AloSecretChat(
    val id: Int,
    /** `pending` (nobody accepted yet) or `active`. */
    val status: String,
    /** `started` by this device, `accepted` by this device, `incoming` (a request nobody accepted yet). */
    val role: String,
    val peerUserId: Int,
    val peerName: String,
    val peerUsername: String = "",
    val peerAvatarUrl: String = "",
    val peerVerified: Boolean = false,
    val localDeviceId: String = "",
    val remoteDeviceId: String = "",
    val ttlSeconds: Int = 0,
    val createdAt: String = "",
    val acceptedAt: String = "",
    val unreadCount: Int = 0,
    val lastMessage: AloSecretMessage? = null
) {
    val isIncomingRequest: Boolean get() = status == "pending" && role == "incoming"
    val isWaiting: Boolean get() = status == "pending" && role == "started"
    val isActive: Boolean get() = status == "active"

    /** What orders the chat in the list: its newest message, otherwise when it began. */
    val lastActivityAt: String
        get() = lastMessage?.createdAt?.takeIf { it.isNotBlank() } ?: acceptedAt.ifBlank { createdAt }
}

/** A secret chat with its messages as the server holds them now. */
data class AloSecretConversation(
    val chat: AloSecretChat?,
    val messages: List<AloSecretMessage>,
    val hasMore: Boolean
)

/** One row of the chat list: an ordinary conversation, or a secret chat dressed as a summary. */
data class AloInboxEntry(
    val summary: AloConversationSummary,
    val secret: AloSecretChat? = null
) {
    val key: String get() = if (secret != null) "secret:${secret.id}" else AloChatListPrefs.key(summary)
}

internal object AloSecretChatCodec {
    const val UNREADABLE_BEFORE_DEVICE = "Отправлено до подключения этого устройства"
    const val UNREADABLE_DAMAGED = "Не удалось расшифровать сообщение"

    /** What the timer menu offers (0 is off); the server takes 0, 1, 2, 3, 5, 10, 15, 30, 60, 300, 3600, 86400, 604800. */
    val TTL_CHOICES: List<Pair<Int, String>> = listOf(
        0 to "Выключен",
        5 to "5 секунд",
        30 to "30 секунд",
        60 to "1 минута",
        3600 to "1 час",
        86400 to "1 день",
        604800 to "1 неделя"
    )

    fun timerLabel(seconds: Int): String =
        TTL_CHOICES.firstOrNull { it.first == seconds }?.second ?: "$seconds с"

    /** «5 с», «3 мин», «2 ч», «4 дн.»: how long a message that has been read still lives. */
    fun countdownLabel(millisLeft: Long): String {
        val seconds = ((millisLeft.coerceAtLeast(0L) + 999L) / 1000L)
        return when {
            seconds >= 86_400L -> "${(seconds + 86_399L) / 86_400L} дн."
            seconds >= 3_600L -> "${(seconds + 3_599L) / 3_600L} ч"
            seconds >= 60L -> "${(seconds + 59L) / 60L} мин"
            else -> "$seconds с"
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---- requests ----

    fun listPath(deviceId: String) = "/api/secret-chats?deviceId=${encode(deviceId)}"

    fun messagesPath(id: Int, deviceId: String, limit: Int = 100, beforeId: Int = 0) = buildString {
        append("/api/secret-chats/$id/messages?deviceId=${encode(deviceId)}&limit=${limit.coerceIn(1, 200)}")
        if (beforeId > 0) append("&beforeId=$beforeId")
    }

    fun deletePath(id: Int, deviceId: String) = "/api/secret-chats/$id?deviceId=${encode(deviceId)}"

    fun startBody(peerUserId: Int, deviceId: String): JSONObject =
        JSONObject().put("peerUserId", peerUserId).put("deviceId", deviceId)

    fun acceptBody(deviceId: String): JSONObject = JSONObject().put("deviceId", deviceId)

    fun readBody(deviceId: String, upToMessageId: Int): JSONObject =
        JSONObject().put("deviceId", deviceId).put("upToMessageId", upToMessageId)

    fun timerBody(deviceId: String, seconds: Int): JSONObject =
        JSONObject().put("deviceId", deviceId).put("seconds", seconds)

    /** Files of a secret chat are opaque: the server sees `e2ee` and a name, never a type or a size. */
    fun messageBody(
        deviceId: String,
        encrypted: JSONObject,
        attachments: List<AloOutgoingMessageAttachment>,
        clientMessageId: String,
        selfDestructSeconds: Int
    ): JSONObject {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("encrypted", encrypted)
            .put("clientMessageId", clientMessageId)
            .put("selfDestructSeconds", selfDestructSeconds.coerceAtLeast(0))
        if (attachments.isNotEmpty()) {
            body.put(
                "attachments",
                JSONArray().also { array ->
                    attachments.forEach { item ->
                        array.put(
                            JSONObject()
                                .put("type", "e2ee")
                                .put("url", item.url)
                                .put("name", item.name.ifBlank { "encrypted.bin" }.take(AloCloudMessages.MAX_ATTACHMENT_NAME))
                        )
                    }
                }
            )
        }
        return body
    }

    /**
     * The server takes a secret message only as ONE envelope for the ONE device at the other end: no copy for the
     * sender's other devices, none for the other devices of the person.
     */
    fun isSingleRecipientEnvelope(
        envelope: JSONObject,
        peerUserId: Int,
        peerDeviceId: String,
        senderDeviceId: String
    ): Boolean {
        val recipients = envelope.optJSONArray("recipients") ?: return false
        if (recipients.length() != 1) return false
        val recipient = recipients.optJSONObject(0) ?: return false
        val mode = envelope.optString("mode")
        return envelope.optString("purpose") == "message" &&
            (mode.isBlank() || mode == "direct") &&
            envelope.optString("senderDeviceId") == senderDeviceId &&
            recipient.optInt("userId") == peerUserId &&
            recipient.optString("deviceId") == peerDeviceId
    }

    // ---- reading ----

    /** A message as the server sends it, or after its envelope was opened (then `body`, `entities`, `attachments` are plain). */
    fun parseMessage(json: JSONObject): AloSecretMessage {
        val body = json.optString("body")
        val attachments = json.optJSONArray("attachments")
        return AloSecretMessage(
            id = json.optInt("id"),
            secretChatId = json.optInt("secretChatId"),
            senderUserId = json.optInt("senderUserId"),
            senderDeviceId = json.optString("senderDeviceId"),
            body = body,
            entities = AloMessageMarkup.parseEntities(json.optJSONArray("entities"), body.length),
            attachments = buildList {
                if (attachments != null) {
                    for (index in 0 until attachments.length()) {
                        val item = attachments.optJSONObject(index) ?: continue
                        // The opaque files the server holds are not what is shown: the opened envelope lists the real ones.
                        if (item.optString("type") == "e2ee") continue
                        AloCloudMessages.storedAttachmentFrom(item)?.let(::add)
                    }
                }
            },
            clientMessageId = json.optString("clientMessageId"),
            selfDestructSeconds = json.optInt("selfDestructSeconds").coerceAtLeast(0),
            createdAt = json.optString("createdAt"),
            readAt = json.optString("readAt"),
            expiresAt = json.optString("expiresAt"),
            contextId = json.optJSONObject("encryptedPayload")?.optString("contextId").orEmpty(),
            unreadable = body == UNREADABLE_BEFORE_DEVICE || body == UNREADABLE_DAMAGED
        )
    }

    private val CIRCLE_NAME = Regex("video[-_ ]?circle|circle[-_ ]?video|круж", RegexOption.IGNORE_CASE)

    /** A circle, whichever way the sender said it: `circle`, or (older apps) a `video` named `video-circle-…`. */
    fun isCircle(attachment: AloOutgoingMessageAttachment): Boolean {
        val type = attachment.type.lowercase()
        return type == "circle" || type == "video_circle" || type == "video-circle" ||
            (type == "video" && CIRCLE_NAME.containsMatchIn(attachment.name))
    }

    fun isVoice(attachment: AloOutgoingMessageAttachment): Boolean =
        attachment.type.lowercase().let { it == "voice" || it == "audio" }

    /**
     * What the envelope says about one sealed file, the way a cloud chat says it: a recording is `voice` with its bars
     * and length, a circle is `circle` (a square MP4 named `video-circle-…`) with its length and size, so every app
     * draws a voice bubble and a round video rather than a file. `durationSeconds` is a whole number ("4").
     */
    fun outgoingAttachment(
        draft: AloChatDraftAttachment,
        url: String,
        mediaEncryption: Map<String, String>,
        nowMillis: Long = System.currentTimeMillis()
    ): AloOutgoingMessageAttachment {
        val type = when {
            draft.isVideoCircle -> "circle"
            draft.attachmentType == "voice" || draft.attachmentType == "audio" -> "voice"
            draft.attachmentType == "image" || draft.attachmentType == "video" -> draft.attachmentType
            else -> "file"
        }
        val name = when {
            type == "circle" && !CIRCLE_NAME.containsMatchIn(draft.name) -> "video-circle-$nowMillis.mp4"
            type == "voice" && draft.name.isBlank() -> "voice-$nowMillis.m4a"
            else -> draft.name.ifBlank { "file" }
        }
        val timed = type == "voice" || type == "circle" || type == "video"
        return AloOutgoingMessageAttachment(
            url = url,
            type = type,
            name = name,
            mediaEncryption = mediaEncryption,
            metadata = if (timed && draft.durationSec > 0) {
                draft.mediaMetadata + ("durationSeconds" to draft.durationSec.toString())
            } else {
                draft.mediaMetadata
            },
            waveform = if (type == "voice") draft.waveform.take(64) else emptyList()
        )
    }

    fun parseChat(json: JSONObject, lastMessage: AloSecretMessage? = null): AloSecretChat? {
        val id = json.optInt("id")
        if (id <= 0) return null
        val peer = json.optJSONObject("peerUser")
        return AloSecretChat(
            id = id,
            status = json.optString("status").ifBlank { "pending" },
            role = json.optString("role").ifBlank { "incoming" },
            peerUserId = json.optInt("peerUserId").takeIf { it > 0 } ?: peer?.optInt("id") ?: 0,
            peerName = peer?.optString("name").orEmpty().ifBlank { "Собеседник" },
            peerUsername = peer?.optString("username").orEmpty(),
            peerAvatarUrl = peer?.optString("avatarEmoji").orEmpty(),
            peerVerified = peer?.optBoolean("verified") == true,
            localDeviceId = json.optString("localDeviceId"),
            remoteDeviceId = json.optString("remoteDeviceId"),
            ttlSeconds = json.optInt("ttlSeconds").coerceAtLeast(0),
            createdAt = json.optString("createdAt"),
            acceptedAt = json.optString("acceptedAt"),
            unreadCount = json.optInt("unreadCount").coerceAtLeast(0),
            lastMessage = lastMessage
        )
    }
}

/** The chats of this device, merged and shown. */
internal object AloSecretChatList {
    /** The line under the name in the chat list. */
    fun preview(chat: AloSecretChat): String = when {
        chat.isIncomingRequest -> "Запрос секретного чата"
        chat.isWaiting -> "Ждём, пока ${chat.peerName} зайдёт в сеть…"
        else -> messagePreview(chat.lastMessage) ?: "Секретный чат"
    }

    fun messagePreview(message: AloSecretMessage?): String? {
        message ?: return null
        if (message.body.isNotBlank()) return message.body
        val first = message.attachments.firstOrNull() ?: return null
        if (AloSecretChatCodec.isCircle(first)) return "Видеосообщение"
        if (AloSecretChatCodec.isVoice(first)) return "Голосовое сообщение"
        return when (first.type.lowercase()) {
            "image" -> "Фото"
            "video" -> "Видео"
            else -> "Файл"
        }
    }

    /** A secret chat as a row of the ordinary list. Its id is negative so it can never be mistaken for a peer id. */
    fun toSummary(chat: AloSecretChat): AloConversationSummary = AloConversationSummary(
        peerId = -chat.id,
        kind = "secret",
        roomId = 0,
        memberCount = 0,
        lastAt = chat.lastActivityAt,
        lastBody = preview(chat),
        peer = AloConversationPeer(
            id = chat.peerUserId,
            name = chat.peerName,
            username = chat.peerUsername,
            avatarUrl = chat.peerAvatarUrl,
            verified = chat.peerVerified,
            entityType = "secret"
        ),
        unreadCount = if (chat.isIncomingRequest) maxOf(1, chat.unreadCount) else chat.unreadCount,
        hasServerUnreadCount = true
    )

    /**
     * The list the person sees: the conversations as the list arranged them (pinned first) with the secret chats among
     * the rest by last activity. Secret chats are never pinned or archived.
     */
    fun merge(
        arranged: List<AloConversationSummary>,
        pinnedKeys: Set<String>,
        secrets: List<AloSecretChat>
    ): List<AloInboxEntry> {
        val pinned = arranged.takeWhile { AloChatListPrefs.key(it) in pinnedKeys }
        val rest = arranged.drop(pinned.size)
        val ordered = secrets.sortedByDescending { parseIsoTimestampMillis(it.lastActivityAt) }
        val result = mutableListOf<AloInboxEntry>()
        pinned.forEach { result += AloInboxEntry(it) }
        var chatIndex = 0
        var secretIndex = 0
        while (chatIndex < rest.size || secretIndex < ordered.size) {
            val nextChat = rest.getOrNull(chatIndex)
            val nextSecret = ordered.getOrNull(secretIndex)
            val takeSecret = nextSecret != null && (
                nextChat == null ||
                    parseIsoTimestampMillis(nextSecret.lastActivityAt) > parseIsoTimestampMillis(nextChat.lastAt)
                )
            if (takeSecret && nextSecret != null) {
                result += AloInboxEntry(toSummary(nextSecret), nextSecret)
                secretIndex += 1
            } else if (nextChat != null) {
                result += AloInboxEntry(nextChat)
                chatIndex += 1
            }
        }
        return result
    }

    /** Search in the list: the name, the username and the preview of a secret chat. */
    fun matches(chat: AloSecretChat, normalizedQuery: String): Boolean =
        normalizedQuery.isBlank() ||
            chat.peerName.lowercase().contains(normalizedQuery) ||
            chat.peerUsername.lowercase().contains(normalizedQuery)

    /** The list from the server replaces ours, newest activity first. */
    fun replace(fresh: List<AloSecretChat>): List<AloSecretChat> =
        fresh.sortedByDescending { parseIsoTimestampMillis(it.lastActivityAt) }

    fun removing(current: List<AloSecretChat>, id: Int): List<AloSecretChat> = current.filterNot { it.id == id }

    /** The chat as the server shows it now, merged into ours (a message list answers with the chat too). */
    fun upserting(current: List<AloSecretChat>, chat: AloSecretChat): List<AloSecretChat> =
        (listOf(chat) + current.filterNot { it.id == chat.id }).sortedByDescending { parseIsoTimestampMillis(it.lastActivityAt) }
}

/** Timers of a secret chat: they start when the message is read, and the message disappears on both sides. */
internal object AloSecretTimers {
    fun expiresAtAfterRead(nowMillis: Long, seconds: Int): String =
        if (seconds > 0) Instant.ofEpochMilli(nowMillis + seconds * 1000L).toString() else ""

    data class ReadResult(val messages: List<AloSecretMessage>, val upToMessageId: Int)

    /**
     * Opening a chat reads what the other person sent: those messages get their read mark and, with a timer, their end.
     * [ReadResult.upToMessageId] is what to tell the server (0 when nothing was unread).
     */
    fun applyRead(messages: List<AloSecretMessage>, myUserId: Int, nowMillis: Long): ReadResult {
        val unread = messages.filter { it.senderUserId != myUserId && it.readAt.isBlank() }
        if (unread.isEmpty()) return ReadResult(messages, 0)
        val readAt = Instant.ofEpochMilli(nowMillis).toString()
        val unreadIds = unread.map { it.id }.toSet()
        return ReadResult(
            messages = messages.map { message ->
                if (message.id in unreadIds) {
                    message.copy(
                        readAt = readAt,
                        expiresAt = message.expiresAt.ifBlank { expiresAtAfterRead(nowMillis, message.selfDestructSeconds) }
                    )
                } else {
                    message
                }
            },
            upToMessageId = unread.maxOf { it.id }
        )
    }

    fun isExpired(message: AloSecretMessage, nowMillis: Long): Boolean =
        message.expiresAt.isNotBlank() && parseIsoTimestampMillis(message.expiresAt) <= nowMillis

    fun visible(messages: List<AloSecretMessage>, nowMillis: Long): List<AloSecretMessage> =
        messages.filterNot { isExpired(it, nowMillis) }

    /** The chat's own timer goes into each new message (the server holds a default for the chat too). */
    fun secondsForNewMessage(chat: AloSecretChat): Int = chat.ttlSeconds.coerceAtLeast(0)
}
