package com.alo.android

import java.util.Locale

private val AloAudioFileExtRegex =
    Regex("\\.(mp3|m4a|aac|wav|flac|ogg|opus|webm|oga)$", RegexOption.IGNORE_CASE)

private val AloVideoCircleRegex =
    Regex("video[-_ ]?circle|circle[-_ ]?video|круж", RegexOption.IGNORE_CASE)

private val AloCatalogVideoFileRegex =
    Regex("\\.(mp4|webm|mov|m4v)(?:[?#].*)?$", RegexOption.IGNORE_CASE)

private fun normalizedVisualType(
    rawType: String,
    name: String,
    url: String,
    mime: String = ""
): String? {
    val type = rawType.trim().lowercase(Locale.ROOT)
    return when (type) {
        "image" -> "image"
        "video" -> "video"
        "sticker" -> "sticker"
        "gif" -> if (
            mime.trim().lowercase(Locale.ROOT).startsWith("video/") ||
            AloCatalogVideoFileRegex.containsMatchIn("$name $url")
        ) {
            "video"
        } else {
            "gif"
        }
        else -> null
    }
}

internal fun AloEntitySummary.toProfileTarget(): AloProfileTarget {
    return if (isChannel) {
        AloProfileTarget.Channel(id)
    } else {
        AloProfileTarget.User(id)
    }
}

internal fun AloConversationDetail.toProfileTarget(): AloProfileTarget {
    return when {
        isChannelCommunity() -> AloProfileTarget.Channel(peerId)
        kind.equals("group", ignoreCase = true) || entityType.equals("group", ignoreCase = true) -> {
            AloProfileTarget.Group(roomId)
        }
        else -> AloProfileTarget.User(peerId)
    }
}

/** A channel remains a channel regardless of stale management data returned by the server. */
internal fun AloConversationDetail.isChannelCommunity(): Boolean {
    return entityType.equals("channel", ignoreCase = true) ||
        groupPolicy?.kind.equals("channel", ignoreCase = true)
}

/** Keeps the community kind immutable while applying editable policy fields from the settings UI. */
internal fun AloConversationDetail.withImmutableCommunityKind(policy: AloGroupPolicy): AloGroupPolicy {
    return policy.copy(kind = if (isChannelCommunity()) "channel" else "group")
        .normalizedCommunityPolicy()
}

internal fun isVoiceAttachment(message: AloChatMessage): Boolean {
    val attachmentType = message.attachmentType.trim().lowercase(Locale.getDefault())
    val lookupValue = "${message.attachmentName} ${message.attachmentUrl}"
    return attachmentType == "voice" ||
        attachmentType == "audio" ||
        // An old voice message stored as a file; a song (AloMusic.kt) is an audio file of another name.
        (attachmentType == "file" && AloAudioFileExtRegex.containsMatchIn(lookupValue) &&
            message.attachmentName.trim().lowercase(Locale.getDefault()).startsWith("voice") && AloMusic.trackOf(message) == null)
}

internal fun AloChatMessage.toCallLogMeta(): AloCallLogMeta? {
    val normalizedType = attachmentType.trim().lowercase(Locale.getDefault())
    val raw = when {
        normalizedType == "call" -> attachmentName.trim()
        body.trim().startsWith("{") &&
            (body.contains("\"kind\"") && body.contains("\"call\"") || body.contains("\"callType\"")) -> body.trim()
        attachmentName.trim().startsWith("{") &&
            (attachmentName.contains("\"kind\"") && attachmentName.contains("\"call\"") || attachmentName.contains("\"callType\"")) -> attachmentName.trim()
        else -> return null
    }
    if (!raw.startsWith("{")) {
        return AloCallLogMeta(callType = "voice", status = "ended", durationSec = 0, scope = if (roomId > 0) "group" else "direct")
    }
    return runCatching {
        val json = org.json.JSONObject(raw)
        AloCallLogMeta(
            callType = json.optString("callType").ifBlank { "voice" },
            status = json.optString("status").ifBlank { "ended" },
            durationSec = json.optInt("durationSec", 0).coerceAtLeast(0),
            scope = json.optString("scope").ifBlank { if (roomId > 0) "group" else "direct" }
        )
    }.getOrNull()
}

internal fun isVideoCircleAttachment(message: AloChatMessage): Boolean {
    val attachmentType = message.attachmentType.trim().lowercase(Locale.getDefault())
    val lookupValue = buildString {
        append(message.attachmentName)
        append(' ')
        append(message.attachmentUrl)
        message.attachments.forEach { attachment ->
            append(' ')
            append(attachment.name)
            append(' ')
            append(attachment.url)
            append(' ')
            append(attachment.type)
        }
    }
    return attachmentType == "circle" || AloVideoCircleRegex.containsMatchIn(lookupValue)
}

internal fun AloChatMessage.toVisualMediaItems(): List<AloMediaItem> {
    val fromAttachments = attachments.mapNotNull { attachment ->
        val type = normalizedVisualType(
            rawType = attachment.type,
            name = attachment.name,
            url = attachment.url,
            mime = attachment.mediaEncryption["mime"].orEmpty()
        )
        when {
            attachment.url.isBlank() -> null
            type == "image" || type == "sticker" || type == "gif" -> AloMediaItem(
                type = type,
                url = attachment.url,
                posterUrl = "",
                alt = attachment.name,
                caption = body,
                isSensitive = selfDestructSeconds > 0,
                width = attachment.metadata["width"]?.toDoubleOrNull()?.toInt() ?: 0,
                height = attachment.metadata["height"]?.toDoubleOrNull()?.toInt() ?: 0
            )
            // The size from the message: the viewer's player has the clip's shape before the file arrives.
            type == "video" -> AloMediaItem(
                type = "video",
                url = attachment.url,
                posterUrl = attachment.posterUrl,
                alt = attachment.name,
                caption = body,
                isSensitive = selfDestructSeconds > 0,
                width = attachment.metadata["width"]?.toDoubleOrNull()?.toInt() ?: 0,
                height = attachment.metadata["height"]?.toDoubleOrNull()?.toInt() ?: 0
            )
            else -> null
        }
    }
    if (fromAttachments.isNotEmpty()) return fromAttachments
    return listOfNotNull(toMediaItemOrNull())
}

internal fun AloChatMessage.toMediaItemOrNull(): AloMediaItem? {
    if (attachmentUrl.isBlank()) return null
    val attachmentType = normalizedVisualType(
        rawType = attachmentType,
        name = attachmentName,
        url = attachmentUrl
    )
    return when {
        attachmentType == "image" || attachmentType == "sticker" || attachmentType == "gif" -> {
            AloMediaItem(
                type = attachmentType,
                url = attachmentUrl,
                posterUrl = "",
                alt = attachmentName,
                caption = body,
                isSensitive = selfDestructSeconds > 0
            )
        }

        attachmentType == "video" || isVideoCircleAttachment(this) -> {
            AloMediaItem(
                type = "video",
                url = attachmentUrl,
                posterUrl = "",
                alt = attachmentName,
                caption = body,
                isSensitive = selfDestructSeconds > 0
            )
        }

        else -> null
    }
}
