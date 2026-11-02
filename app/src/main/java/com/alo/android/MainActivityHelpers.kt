package com.alo.android

import android.util.Patterns
import android.webkit.MimeTypeMap
import java.util.Locale

internal fun conversationDraftKey(detail: AloConversationDetail, topicId: Int = 0): String {
    val conversationKey = if (detail.kind == "group" || detail.roomId > 0) {
        "group:${detail.roomId}"
    } else {
        "direct:${detail.peerId}"
    }
    return if (topicId > 0) "$conversationKey:topic:$topicId" else conversationKey
}

internal fun chatAppearanceKey(detail: AloConversationDetail): String {
    return if (detail.kind == "group" || detail.roomId > 0) {
        "group:${detail.roomId}"
    } else {
        "direct:${detail.peerId}"
    }
}

internal fun resolveActiveConversationPeerId(
    bootstrap: AloBootstrapData,
    preferredPeerId: Int
): Int {
    if (preferredPeerId > 0 && bootstrap.messagesConversations.any { it.peerId == preferredPeerId }) {
        return preferredPeerId
    }
    return 0
}

/**
 * What the chat list says for a message text. Polls and system events travel as coded text; the server's summaries bring them
 * as they are, sometimes cut short, and a person must never see `alo.poll.v1:7b22…`.
 */
internal fun aloReadableMessageText(body: String): String {
    when (val poll = AloPollCodec.decode(body)) {
        is AloPollEvent.Create -> return "Опрос: ${poll.definition.question}"
        is AloPollEvent.Vote -> return "Опрос"
        is AloPollEvent.Reveal -> return "Викторина"
        null -> Unit
    }
    if (AloPollCodec.isPollBody(body)) return "Опрос"
    return AloChatSystemEventCodec.previewText(body) ?: body
}

internal fun buildConversationPreview(message: AloChatMessage): String {
    if (message.body.isNotBlank()) return aloReadableMessageText(message.body)
    if (
        message.attachmentType.equals("video", ignoreCase = true) &&
        message.attachmentName.contains("video-circle", ignoreCase = true)
    ) {
        return "Видео-кружок"
    }
    if (message.attachmentType.startsWith("image")) return "Фотография"
    if (message.attachmentType.startsWith("video")) return "Видео"
    if (message.attachmentType.equals("voice", ignoreCase = true)) return "Голосовое сообщение"
    if (message.attachmentType.equals("circle", ignoreCase = true)) return "Видео-кружок"
    AloCloudMessages.attachmentPreview(message.attachmentType)?.let { return it }
    if (message.attachmentType.isNotBlank()) return "Вложение"
    return "Сообщение"
}

internal fun formatMemberCount(count: Int): String {
    if (count <= 0) return "Групповой чат"
    val mod10 = count % 10
    val mod100 = count % 100
    val label = when {
        mod10 == 1 && mod100 != 11 -> "участник"
        mod10 in 2..4 && mod100 !in 12..14 -> "участника"
        else -> "участников"
    }
    return "$count $label"
}

internal fun formatSubscriberCount(count: Int): String {
    val normalizedCount = count.coerceAtLeast(0)
    val mod10 = normalizedCount % 10
    val mod100 = normalizedCount % 100
    val label = when {
        mod10 == 1 && mod100 != 11 -> "подписчик"
        mod10 in 2..4 && mod100 !in 12..14 -> "подписчика"
        else -> "подписчиков"
    }
    return "$normalizedCount $label"
}

internal fun prependPost(posts: List<AloPost>, post: AloPost): List<AloPost> {
    return listOf(post) + posts.filterNot { it.id == post.id }
}

internal fun replacePost(posts: List<AloPost>, post: AloPost): List<AloPost> {
    return posts.map { existing ->
        if (existing.id == post.id) post else existing
    }
}

internal fun removePost(posts: List<AloPost>, postId: Int): List<AloPost> {
    return posts.filterNot { it.id == postId }
}

internal fun findPostById(data: AloBootstrapData, postId: Int): AloPost? {
    return sequenceOf(
        data.feedPopular,
        data.feedSubscriptions,
        data.profilePosts
    ).flatten().firstOrNull { it.id == postId }
}

internal fun isValidEmail(value: String): Boolean {
    return Patterns.EMAIL_ADDRESS.matcher(value).matches()
}

internal fun normalizeEmail(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

internal fun normalizeUsername(value: String): String {
    return value.trim().replaceFirst(Regex("^@+"), "").lowercase(Locale.ROOT)
}

internal fun buildAppUrl(path: String): String {
    return AloServerConfiguration.apiUrl(path)
}

internal fun buildAloLink(kind: String, value: String): String {
    return AloServerConfiguration.appLink(kind, value)
}

internal fun aloAuthenticatedMediaHeaders(rawUrl: String): Map<String, String> {
    return AloServerConfiguration.authenticatedMediaHeaders(rawUrl)
}

internal fun guessMimeTypeFromName(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

internal fun extensionFromMime(mimeType: String): String {
    return MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimeType.substringBefore(';').trim().lowercase(Locale.ROOT))
        .orEmpty()
}

internal fun sanitizeFileName(name: String): String {
    return name
        .trim()
        .replace(Regex("[^a-zA-Z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "attachment-${System.currentTimeMillis()}" }
        .take(96)
}

internal fun buildVideoCircleFileName(mimeType: String): String {
    val extension = extensionFromMime(mimeType).ifBlank { "mp4" }
    return "video-circle-${System.currentTimeMillis()}.$extension"
}
