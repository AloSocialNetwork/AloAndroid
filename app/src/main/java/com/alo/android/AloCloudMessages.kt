package com.alo.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * What `GET /api/config` → `security` says about plain ("cloud") messages. A flag that is missing
 * (an older server) counts as false, so the app keeps sending libsignal messages there.
 * Direct chats and Saved Messages follow the direct flag, groups the group flag.
 */
data class AloMessageSecurityPolicy(
    val plaintextDirectAllowed: Boolean = false,
    val plaintextGroupAllowed: Boolean = false,
    /** The server can do secret chats (`security.secretChats`). */
    val secretChatsEnabled: Boolean = false
) {
    fun allowsCloud(isGroup: Boolean): Boolean = if (isGroup) plaintextGroupAllowed else plaintextDirectAllowed

    /** True when no chat needs end-to-end encryption, so E2EE key warnings would warn about nothing. */
    val cloudEverywhere: Boolean get() = plaintextDirectAllowed && plaintextGroupAllowed

    companion object {
        fun fromConfig(config: JSONObject?): AloMessageSecurityPolicy {
            val security = config?.optJSONObject("security") ?: return AloMessageSecurityPolicy()
            return AloMessageSecurityPolicy(
                plaintextDirectAllowed = security.optBoolean("plaintextDirectMessagesAllowed", false),
                plaintextGroupAllowed = security.optBoolean("plaintextGroupMessagesAllowed", false),
                secretChatsEnabled = security.optBoolean("secretChats", false)
            )
        }
    }
}

/**
 * Cloud chats (docs/cloud-and-secret-chats.md, stage 1): the message is stored as plain text on the server,
 * so `body`, `entities` and `attachments` travel as separate JSON fields instead of an `encrypted` envelope.
 *
 * A prepared cloud message is a JSON object tagged with [SCHEME]. It takes the place of the E2EE envelope in the
 * outbox, so a queued message survives a restart exactly like an encrypted one.
 */
internal object AloCloudMessages {
    const val SCHEME = "alo-cloud-v1"
    const val MAX_ENTITIES = 100
    const val MAX_MENTIONS = 50
    const val MAX_ATTACHMENT_NAME = 120
    const val MAX_WAVEFORM = 64
    private const val MEGABYTE = 1024L * 1024L

    private val circleName = Regex("video[-_ ]?circle|circle[-_ ]?video|круж", RegexOption.IGNORE_CASE)
    private val videoName = Regex("\\.(mp4|webm|mov|m4v)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    private val uploadUrl = Regex("^/uploads/messages/[A-Za-z0-9._-]+$")
    private val cardUrl = Regex("^(?:/(?:uploads|assets)/(?!.*\\.\\.)[A-Za-z0-9._/-]{1,300}|https://[^\\s\"'<>]{1,480})$")
    private val fileTypes = setOf("image", "video", "file", "voice", "circle")
    private val cardTypes = setOf("location", "contact", "sticker")

    fun isCloudPayload(payload: JSONObject): Boolean = payload.optString("scheme") == SCHEME

    /**
     * A message JSON from the server is a cloud message when it has no E2EE scheme and no envelope.
     * Channel posts and call log entries are plain too, but they are not chat text with entities of their own.
     */
    fun isCloudMessageJson(json: JSONObject): Boolean =
        json.optString("encryptionScheme").isBlank() &&
            json.optJSONObject("encryptedPayload") == null &&
            json.optInt("channelPostId") <= 0 &&
            !json.optString("attachmentType").equals("call", ignoreCase = true)

    private fun isTrimmable(char: Char): Boolean = char.isWhitespace() || char == '﻿'

    /**
     * The server trims `body` and keeps the entities as sent, so the offsets must already refer to the trimmed text.
     * At most [MAX_ENTITIES] are kept (the server keeps no more either).
     */
    fun trimmed(value: AloMessageMarkupResult): AloMessageMarkupResult {
        val body = value.body
        var start = 0
        var end = body.length
        while (start < end && isTrimmable(body[start])) start += 1
        while (end > start && isTrimmable(body[end - 1])) end -= 1
        val entities = if (start == 0 && end == body.length) {
            value.entities
        } else {
            value.entities.mapNotNull { entity ->
                val from = maxOf(entity.offset, start)
                val to = minOf(entity.offset + entity.length, end)
                if (to <= from) null else entity.copy(offset = from - start, length = to - from)
            }
        }
        return AloMessageMarkupResult(body.substring(start, end), entities.take(MAX_ENTITIES))
    }

    /** Text typed with Alo's markup (`**bold**`, `> quote`…) as the server stores it. Poll and system events stay as they are. */
    fun textContent(source: String): AloMessageMarkupResult {
        if (AloPollCodec.decode(source) != null || AloChatSystemEventCodec.decode(source) != null) {
            return AloMessageMarkupResult(source.trim(), emptyList())
        }
        return trimmed(AloMessageMarkup.parse(source))
    }

    /** `@name` entities → ids of group members, so the server can notify them (the server does not read the text). */
    fun mentionedUserIds(
        content: AloMessageMarkupResult,
        directory: Map<String, Int>,
        selfUserId: Int
    ): List<Int> {
        if (directory.isEmpty()) return emptyList()
        return content.entities.asSequence()
            .filter { it.type == "mention" && it.offset >= 0 && it.offset + it.length <= content.body.length }
            .mapNotNull { entity ->
                val handle = content.body.substring(entity.offset, entity.offset + entity.length)
                    .removePrefix("@")
                    .lowercase()
                directory[handle]
            }
            .filter { it > 0 && it != selfUserId }
            .distinct()
            .take(MAX_MENTIONS)
            .toList()
    }

    fun buildPayloadFromMarkup(
        source: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource? = null,
        mentionDirectory: Map<String, Int> = emptyMap(),
        selfUserId: Int = 0,
        baseUrl: String = ""
    ): JSONObject = buildPayload(textContent(source), attachments, forwardFrom, mentionDirectory, selfUserId, baseUrl)

    fun buildPayload(
        content: AloMessageMarkupResult,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource? = null,
        mentionDirectory: Map<String, Int> = emptyMap(),
        selfUserId: Int = 0,
        baseUrl: String = ""
    ): JSONObject {
        val payload = JSONObject()
            .put("scheme", SCHEME)
            .put("body", content.body)
            .put("entities", JSONArray().also { array -> content.entities.forEach { array.put(it.toJson()) } })
            .put(
                "attachments",
                JSONArray().also { array ->
                    attachments.forEach { array.put(attachmentJson(it, baseUrl)) }
                }
            )
        val mentions = mentionedUserIds(content, mentionDirectory, selfUserId)
        if (mentions.isNotEmpty()) payload.put("mentionedUserIds", JSONArray().also { array -> mentions.forEach(array::put) })
        forwardFrom?.let {
            payload.put("forwardFrom", JSONObject().put("userId", it.userId).put("messageId", it.messageId))
        }
        return payload
    }

    /** Copies what the send request needs from a prepared cloud message. */
    fun putContent(request: JSONObject, payload: JSONObject) {
        request.put("body", payload.optString("body"))
        request.put("entities", payload.optJSONArray("entities") ?: JSONArray())
        payload.optJSONArray("attachments")?.takeIf { it.length() > 0 }?.let { request.put("attachments", it) }
        payload.optJSONArray("mentionedUserIds")?.takeIf { it.length() > 0 }?.let { request.put("mentionedUserIds", it) }
        payload.optJSONObject("forwardFrom")?.let { request.put("forwardFrom", it) }
    }

    /** The attachment type the server knows: circles and GIFs are not types of their own on the Android side. */
    fun serverType(attachment: AloOutgoingMessageAttachment): String {
        val type = attachment.type.trim().lowercase()
        return when {
            type in fileTypes || type in cardTypes -> {
                if (type == "video" && circleName.containsMatchIn(attachment.name)) "circle" else type
            }
            type == "gif" -> {
                if (videoName.containsMatchIn(attachment.name) || videoName.containsMatchIn(attachment.url)) "video" else "image"
            }
            type == "audio" -> "voice"
            else -> "file"
        }
    }

    /** `https://api.host/uploads/x` of our own server → `/uploads/x`, the form the server accepts. */
    fun serverUrl(value: String, baseUrl: String): String {
        val raw = value.trim()
        if (raw.isEmpty() || raw.startsWith("/") || baseUrl.isBlank()) return raw
        return runCatching {
            val base = URL(baseUrl)
            val url = URL(raw)
            val basePort = if (base.port == -1) base.defaultPort else base.port
            val urlPort = if (url.port == -1) url.defaultPort else url.port
            if (
                url.protocol.equals(base.protocol, ignoreCase = true) &&
                url.host.equals(base.host, ignoreCase = true) &&
                basePort == urlPort
            ) {
                url.file
            } else {
                raw
            }
        }.getOrDefault(raw)
    }

    /** An address the server takes for a sticker (or other card) without an upload, or null. */
    fun cardUrlOrNull(value: String, baseUrl: String): String? =
        serverUrl(value, baseUrl).takeIf { cardUrl.matches(it) }

    fun isPlainUploadUrl(serverUrl: String): Boolean = uploadUrl.matches(serverUrl)

    /** The server refuses (and silently drops) anything else, so an unsendable attachment is caught here. */
    fun isSendableAttachment(attachment: AloOutgoingMessageAttachment, baseUrl: String): Boolean {
        if (attachment.mediaEncryption.isNotEmpty()) return false
        val type = serverType(attachment)
        val url = serverUrl(attachment.url, baseUrl)
        return when (type) {
            in fileTypes -> uploadUrl.matches(url)
            else -> url.isEmpty() || cardUrl.matches(url)
        }
    }

    fun attachmentJson(attachment: AloOutgoingMessageAttachment, baseUrl: String = ""): JSONObject {
        val type = serverType(attachment)
        val json = JSONObject()
            .put("type", type)
            .put("url", serverUrl(attachment.url, baseUrl))
            .put("name", attachment.name.take(MAX_ATTACHMENT_NAME))
        serverUrl(attachment.posterUrl, baseUrl)
            .takeIf { it.startsWith("/") || it.startsWith("https://") }
            ?.let { json.put("posterUrl", it) }
        if (type == "voice" && attachment.waveform.isNotEmpty()) {
            json.put(
                "waveform",
                JSONArray().also { array ->
                    attachment.waveform.take(MAX_WAVEFORM).forEach { value ->
                        array.put(Math.round(value.coerceIn(0f, 1f) * 1000f) / 1000.0)
                    }
                }
            )
        }
        if (attachment.metadata.isNotEmpty()) json.put("metadata", JSONObject(attachment.metadata))
        return json
    }

    /** The longest voice message: 100 minutes, as Telegram Desktop. */
    const val MAX_VOICE_SECONDS = 100 * 60

    /** The largest plain file the server takes by upload type (video, voice, everything else). */
    fun maxUploadBytes(mime: String): Long {
        val normalized = mime.trim().lowercase()
        return when {
            normalized.startsWith("video/") -> 30L * MEGABYTE
            normalized.startsWith("audio/") -> 160L * MEGABYTE
            else -> 25L * MEGABYTE
        }
    }

    /** Short label for the chat list when a message has no text. */
    fun attachmentPreview(type: String): String? = when (type.trim().lowercase()) {
        "sticker" -> "Стикер"
        "location" -> "Геопозиция"
        "contact" -> "Контакт"
        else -> null
    }

    // ---- storage of attachments and forward sources (outbox, drafts) ----

    fun storedAttachmentJson(attachment: AloOutgoingMessageAttachment): JSONObject {
        val json = JSONObject()
            .put("url", attachment.url)
            .put("type", attachment.type)
            .put("name", attachment.name)
            .put("mediaEncryption", JSONObject(attachment.mediaEncryption))
            .put("metadata", JSONObject(attachment.metadata))
        if (attachment.posterUrl.isNotBlank()) json.put("posterUrl", attachment.posterUrl)
        if (attachment.waveform.isNotEmpty()) {
            json.put("waveform", JSONArray().also { array -> attachment.waveform.forEach { array.put(it.toDouble()) } })
        }
        return json
    }

    fun storedAttachmentFrom(record: JSONObject): AloOutgoingMessageAttachment? {
        val url = record.optString("url")
        val type = record.optString("type")
        if (type.isBlank() || (url.isBlank() && type !in setOf("contact", "location"))) return null
        return AloOutgoingMessageAttachment(
            url = url,
            type = type,
            name = record.optString("name"),
            mediaEncryption = stringMap(record.optJSONObject("mediaEncryption")),
            metadata = stringMap(record.optJSONObject("metadata")),
            posterUrl = record.optString("posterUrl"),
            waveform = waveformFrom(record.optJSONArray("waveform"))
        )
    }

    fun stringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key)
        }
        return result
    }

    fun waveformFrom(array: JSONArray?): List<Float> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_WAVEFORM)) {
                val value = array.optDouble(index, Double.NaN)
                if (!value.isNaN()) add(value.toFloat().coerceIn(0f, 1f))
            }
        }
    }

    fun forwardSourceJson(source: AloMessageForwardSource): JSONObject = JSONObject()
        .put("userId", source.userId)
        .put("messageId", source.messageId)
        .put("userName", source.userName)
        .put("body", source.body)
        .put("encrypted", source.encrypted)
        .put("entities", JSONArray().also { array -> source.entities.forEach { array.put(it.toJson()) } })
        .put("attachments", JSONArray().also { array -> source.attachments.forEach { array.put(storedAttachmentJson(it)) } })

    fun forwardSourceFrom(record: JSONObject): AloMessageForwardSource {
        val body = record.optString("body")
        val attachments = record.optJSONArray("attachments")
        return AloMessageForwardSource(
            userId = record.optInt("userId"),
            messageId = record.optInt("messageId"),
            userName = record.optString("userName"),
            body = body,
            entities = AloMessageMarkup.parseEntities(record.optJSONArray("entities"), body.length),
            attachments = buildList {
                if (attachments != null) {
                    for (index in 0 until attachments.length()) {
                        attachments.optJSONObject(index)?.let(::storedAttachmentFrom)?.let(::add)
                    }
                }
            },
            encrypted = record.optBoolean("encrypted")
        )
    }
}
