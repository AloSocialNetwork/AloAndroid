package com.alo.android

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal sealed interface AloDeepLinkTarget {
    data class Invite(val token: String) : AloDeepLinkTarget
    data class Community(val username: String) : AloDeepLinkTarget
    data class Post(val postId: Int) : AloDeepLinkTarget
    /** A public post link https://ало.online/p/<random code>; the post is looked up by its code. */
    data class PostCode(val code: String) : AloDeepLinkTarget
    data class User(val userId: Int) : AloDeepLinkTarget
    data class Channel(val channelId: Int) : AloDeepLinkTarget
    data class ForumTopic(val roomId: Int, val topicId: Int) : AloDeepLinkTarget
    data class Handle(val handle: String) : AloDeepLinkTarget
    /** A story's share link, alo://s/<code>: the story, or «История истекла». */
    data class Story(val code: String) : AloDeepLinkTarget
    /** The website's sign-in QR read by the phone camera: «Войти на устройстве …?». */
    data class QrLogin(val payload: AloQrLoginPayload) : AloDeepLinkTarget

    companion object {
        private val handlePattern = Regex("^[a-z0-9_][a-z0-9_-]{1,48}[a-z0-9_]$")
        private val communityPattern = Regex("^[a-z0-9_]{5,32}$")
        private val invitePattern = Regex("^[A-Za-z0-9_-]{16,128}$")
        private val storyCodePattern = Regex("^[A-Za-z0-9_-]{8,32}$")
        // 12 letters, digits, "-" or "_", never digits only (the server's post-share-code.js).
        private val postCodePattern = Regex("^(?![0-9]+$)[A-Za-z0-9_-]{12}$")

        fun isPostCode(value: String): Boolean = postCodePattern.matches(value)

        /** The link to share: by the post's random code, so nobody can walk through posts by number. */
        fun postUrl(post: AloPost): String =
            buildAloLink("p", post.shareCode.takeIf(::isPostCode) ?: post.id.toString())

        fun parse(uri: Uri?): AloDeepLinkTarget? = parse(uri?.toString())

        internal fun parse(
            rawUrl: String?,
            configuredWebHost: String = AloServerConfiguration.appLinkHost
        ): AloDeepLinkTarget? {
            val raw = rawUrl?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme != "alo" && scheme != "https") return null

            // https://<site>/qr#<id>.<secret> (also from the test server, not only the app-link host) or alo://login/qr/….
            AloQrLoginPayload.parse(raw)?.takeIf { !it.isDeviceLink && it.isFromTrustedHost }?.let { return QrLogin(it) }

            val configuredHost = configuredWebHost.trim().lowercase()
            if (scheme == "https") {
                if (configuredHost.isEmpty() || configuredHost.endsWith(".invalid")) return null
                if (uri.host?.lowercase() != configuredHost) return null
            }

            val pathParts = uri.rawPath.orEmpty()
                .split('/')
                .filter(String::isNotEmpty)
                .map(::decodePathSegment)
            val parts = buildList {
                if (scheme == "alo") {
                    (uri.host ?: uri.rawAuthority)
                        ?.takeIf(String::isNotBlank)
                        ?.let(::decodePathSegment)
                        ?.let(::add)
                }
                addAll(pathParts)
            }

            if (scheme == "https" && parts.size == 1) {
                return normalizedHandle(parts[0])?.let(::Handle)
            }
            if (
                parts.size == 3 &&
                parts[0].lowercase() in setOf("t", "topic", "forum")
            ) {
                val roomId = positiveId(parts[1])
                val topicId = positiveId(parts[2])
                return if (roomId != null && topicId != null) {
                    ForumTopic(roomId, topicId)
                } else {
                    null
                }
            }
            if (parts.size != 2) return null
            val value = parts[1]
            return when (parts[0].lowercase()) {
                "invite", "join", "+" -> value.takeIf(invitePattern::matches)?.let(::Invite)
                "c", "channel", "community" -> normalizedCommunity(value)?.let(::Community)
                "p", "post", "posts" -> positiveId(value)?.let(::Post)
                    ?: value.takeIf(::isPostCode)?.let(::PostCode)
                "u", "user", "users" -> positiveId(value)?.let(::User)
                "ch", "feed-channel" -> positiveId(value)?.let(::Channel)
                "r", "resolve" -> normalizedHandle(value)?.let(::Handle)
                "s", "story" -> value.takeIf(storyCodePattern::matches)?.let(::Story)
                else -> null
            }
        }

        fun inviteUrl(token: String): String = buildAloLink("join", token)

        fun forumTopicUrl(roomId: Int, topicId: Int): String? {
            if (roomId <= 0 || topicId <= 0) return null
            return AloServerConfiguration.appLinkPath("t/$roomId/$topicId")
        }

        private fun positiveId(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }

        private fun normalizedHandle(value: String): String? = value
            .removePrefix("@")
            .lowercase()
            .takeIf(handlePattern::matches)

        private fun normalizedCommunity(value: String): String? = value
            .removePrefix("@")
            .lowercase()
            .takeIf(communityPattern::matches)

        private fun decodePathSegment(value: String): String = runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
