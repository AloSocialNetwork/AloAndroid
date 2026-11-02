package com.alo.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

data class LoginChallengeResult(
    val challengeToken: String,
    val message: String
)

data class CodeDispatchResult(
    val message: String,
    val nextWaitSec: Int = 0
)

data class AloEntitySummary(
    val id: Int,
    val name: String,
    val username: String,
    val avatarUrl: String,
    val verified: Boolean,
    val isChannel: Boolean = false,
    /** How this account relates to the person, when the server says (search, suggestions). */
    val relation: AloEntityRelation? = null
)

/** iOS's AloProfileRelation: following, friendship and pending requests. */
data class AloEntityRelation(
    val following: Boolean = false,
    val followedBy: Boolean = false,
    val friendStatus: String = "none",
    val incomingRequestId: Int = 0,
    val outgoingRequestId: Int = 0,
    /** Whether the person takes friend requests (people lists carry it); null from an older server. */
    val canSendFriendRequest: Boolean? = null
)

data class AloCurrentUser(
    val id: Int,
    val name: String,
    val username: String,
    val email: String,
    val bio: String,
    val avatarUrl: String,
    val avatarGallery: List<String>,
    val verified: Boolean,
    val registeredLabel: String,
    val coverStyle: String,
    val coverImageUrl: String,
    /** The reaction a double tap on a message sends, the same on every device of the account. */
    val favoriteReaction: String = "❤️",
    /** What this account may do in the moderation panel ("reports", "moderation"); empty for everyone else. */
    val adminCan: Set<String> = emptySet(),
    /** Where the account stands on the moderation ladder (owner, admin, lead moderator…). */
    val staffHierarchy: AloStaffHierarchy = AloStaffHierarchy.User
)

data class AloConversationPeer(
    val id: Int,
    val name: String,
    val username: String,
    val avatarUrl: String,
    val verified: Boolean,
    val entityType: String = "",
    val online: Boolean = false,
    val isTyping: Boolean = false,
    val lastSeenAt: String = ""
)

data class AloConversationSummary(
    val peerId: Int,
    val kind: String,
    val roomId: Int,
    val memberCount: Int,
    val lastAt: String,
    val lastBody: String,
    val peer: AloConversationPeer,
    /** The server's unread count; [hasServerUnreadCount] is false when an older server sent none. */
    val unreadCount: Int = 0,
    val hasServerUnreadCount: Boolean = false,
    /** Unread messages that mention you (Telegram's «@» before the counter). */
    val unreadMentionCount: Int = 0,
    val lastMessageId: Int = 0,
    val lastSenderId: Int = 0
)

/** A finished one-to-one call («Звонки»); missed and declined incoming calls show in red. */
data class AloCallHistoryItem(
    val id: Int,
    val isOutgoing: Boolean,
    val isVideo: Boolean,
    /** completed, missed, cancelled or declined. */
    val outcome: String,
    val createdAt: String,
    val durationSeconds: Int,
    val peer: AloEntitySummary
) {
    val isMissed: Boolean get() = !isOutgoing && (outcome == "missed" || outcome == "declined")
}

data class AloChatMessage(
    val id: Int,
    val roomId: Int,
    val senderId: Int,
    val recipientId: Int,
    val body: String,
    val attachmentUrl: String,
    val attachmentType: String,
    val attachmentName: String,
    val attachments: List<AloOutgoingMessageAttachment> = emptyList(),
    val createdAt: String,
    val editedAt: String,
    val readAt: String,
    val forwardFrom: AloMessageForwardSource? = null,
    val sender: AloConversationPeer? = null,
    val senderIsAnonymous: Boolean = false,
    val senderAdminTitle: String = "",
    val reactions: List<AloMessageReaction> = emptyList(),
    val entities: List<AloMessageTextEntity> = emptyList(),
    val selfDestructSeconds: Int = 0,
    val selfDestructStartedAt: String = "",
    val expiresAt: String = "",
    val topicId: Int = 0,
    val replyToMessageId: Int = 0,
    val channelPostId: Int = 0,
    val channelPostCommentCount: Int = 0,
    val channelPostCommentsEnabled: Boolean = false,
    val scheduledMessageId: Int = 0,
    val scheduledAt: String = "",
    val sendWhenOnline: Boolean = false,
    val silent: Boolean = false,
    val deliveryState: String = "",
    val deliveryError: String = "",
    /**
     * The local id a sent message had before the server numbered it, so the chat list keeps one
     * row for it (Telegram's stableId) instead of swapping the bubble when the send is confirmed.
     */
    val localKey: Int = 0,
    /** Stored as plain text on the server (a cloud chat): its entities came from the server, never from markup in the text. */
    val cloud: Boolean = false,
    /** The server keeps this message as an E2EE envelope; an edit of it has to stay encrypted. */
    val encrypted: Boolean = false
) {
    val isScheduled: Boolean
        get() = scheduledMessageId > 0 || sendWhenOnline || deliveryState == "scheduled"
}

data class AloMessageTextEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("offset", offset)
        .put("length", length)
        .also { value -> if (url.isNotBlank()) value.put("url", url) }
}

data class AloMessageMarkupResult(
    val body: String,
    val entities: List<AloMessageTextEntity>
)

data class AloPollOption(
    val id: String,
    val text: String
)

data class AloPollDefinition(
    val id: String,
    val question: String,
    val options: List<AloPollOption>,
    val allowsMultipleAnswers: Boolean,
    val isQuiz: Boolean,
    val correctOptionIds: Set<String>,
    val quizAnswerCommitment: String = "",
    val explanation: String = "",
    val isAnonymous: Boolean,
    val allowsRevoting: Boolean,
    val closesAt: String = ""
)

sealed interface AloPollEvent {
    data class Create(val definition: AloPollDefinition) : AloPollEvent
    data class Vote(val pollId: String, val optionIds: Set<String>) : AloPollEvent
    data class Reveal(val pollId: String, val correctOptionIds: Set<String>, val salt: String) : AloPollEvent
}

data class AloPollViewState(
    val definition: AloPollDefinition,
    val voteCounts: Map<String, Int>,
    val selectedOptionIds: Set<String>,
    val totalVoters: Int,
    val isClosed: Boolean = false,
    val allowsRevoting: Boolean = true,
    val correctOptionIds: Set<String> = definition.correctOptionIds,
    val publicVoters: List<AloPollPublicVoter> = emptyList(),
    val closesAt: String = definition.closesAt
)

data class AloPollPublicVoter(
    val userId: Int,
    val name: String,
    val username: String,
    val avatarUrl: String,
    val optionIds: Set<String>
)

data class AloCanonicalPollState(
    val id: String,
    val messageId: Int,
    val voteCounts: Map<String, Int>,
    val selectedOptionIds: Set<String>,
    val totalVoters: Int,
    val isClosed: Boolean,
    val allowsRevoting: Boolean,
    val correctOptionIds: Set<String>,
    val publicVoters: List<AloPollPublicVoter>,
    val closesAt: String,
    val closedAt: String
)

object AloPollCodec {
    private const val PREFIX = "alo.poll.v1:"

    fun encodeCreate(definition: AloPollDefinition): String? {
        if (!isValid(definition) || (definition.isQuiz &&
                !AloPollQuizCommitment.isValidCommitment(definition.quizAnswerCommitment))) return null
        val options = JSONArray()
        definition.options.forEach { option ->
            options.put(JSONObject().put("id", option.id).put("text", option.text))
        }
        val payload = JSONObject()
                .put("type", "create")
                .put("pollId", definition.id)
                .put("question", definition.question)
                .put("options", options)
                .put("multiple", definition.allowsMultipleAnswers)
                .put("quiz", definition.isQuiz)
                .put("anonymous", definition.isAnonymous)
                .put("revoting", definition.allowsRevoting)
        if (definition.explanation.isNotBlank()) payload.put("explanation", definition.explanation)
        if (definition.closesAt.isNotBlank()) payload.put("closesAt", definition.closesAt)
        if (definition.isQuiz) payload.put("quizAnswerCommitment", definition.quizAnswerCommitment)
        return encode(payload)
    }

    fun encodeVote(pollId: String, optionIds: Set<String>): String? {
        if (!isValidIdentifier(pollId) || optionIds.size > 10 ||
            optionIds.any { !isValidIdentifier(it) }
        ) return null
        val values = JSONArray()
        optionIds.sorted().forEach(values::put)
        return encode(
            JSONObject()
                .put("type", "vote")
                .put("pollId", pollId)
                .put("optionIds", values)
        )
    }

    fun encodeReveal(pollId: String, correctOptionIds: Set<String>, salt: String): String? {
        if (!isValidIdentifier(pollId) || correctOptionIds.size != 1 ||
            correctOptionIds.any { !isValidIdentifier(it) } ||
            !AloPollQuizCommitment.isValidSalt(salt)
        ) return null
        return encode(
            JSONObject()
                .put("type", "reveal")
                .put("pollId", pollId)
                .put("correctOptionIds", JSONArray().also { values ->
                    correctOptionIds.sorted().forEach(values::put)
                })
                .put("salt", salt)
        )
    }

    fun isPollBody(body: String): Boolean = body.startsWith(PREFIX)

    fun decode(body: String): AloPollEvent? {
        if (!body.startsWith(PREFIX)) return null
        val json = runCatching {
            val bytes = decodeHex(body.removePrefix(PREFIX)) ?: return null
            JSONObject(bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val pollId = json.optString("pollId")
        if (!isValidIdentifier(pollId)) return null
        if (json.optString("type") == "vote") {
            val values = json.optJSONArray("optionIds") ?: return null
            val optionIds = buildSet {
                repeat(values.length()) { index -> add(values.optString(index)) }
            }
            if (optionIds.size > 10 || optionIds.any { !isValidIdentifier(it) }) {
                return null
            }
            return AloPollEvent.Vote(pollId, optionIds)
        }
        if (json.optString("type") == "reveal") {
            val values = json.optJSONArray("correctOptionIds") ?: return null
            val correctOptionIds = buildSet {
                repeat(values.length()) { index -> add(values.optString(index)) }
            }
            val salt = json.optString("salt")
            if (correctOptionIds.size != 1 || correctOptionIds.any { !isValidIdentifier(it) } ||
                !AloPollQuizCommitment.isValidSalt(salt)
            ) return null
            return AloPollEvent.Reveal(pollId, correctOptionIds, salt)
        }
        if (json.optString("type") != "create") return null
        val rawOptions = json.optJSONArray("options") ?: return null
        val options = buildList {
            repeat(rawOptions.length()) { index ->
                val value = rawOptions.optJSONObject(index) ?: return@repeat
                add(AloPollOption(value.optString("id"), value.optString("text")))
            }
        }
        val correctValues = json.optJSONArray("correctOptionIds") ?: JSONArray()
        val definition = AloPollDefinition(
            id = pollId,
            question = json.optString("question"),
            options = options,
            allowsMultipleAnswers = json.optBoolean("multiple"),
            isQuiz = json.optBoolean("quiz"),
            correctOptionIds = buildSet {
                repeat(correctValues.length()) { index -> add(correctValues.optString(index)) }
            },
            quizAnswerCommitment = json.optString("quizAnswerCommitment"),
            explanation = json.optString("explanation"),
            isAnonymous = json.optBoolean("anonymous", true),
            allowsRevoting = json.optBoolean("revoting", !json.optBoolean("quiz")),
            closesAt = json.optString("closesAt")
        )
        return definition.takeIf(::isValid)?.let(AloPollEvent::Create)
    }

    private fun encode(payload: JSONObject): String = PREFIX + payload.toString()
        .toByteArray(Charsets.UTF_8)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun decodeHex(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        return runCatching {
            ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private fun isValid(definition: AloPollDefinition): Boolean {
        val optionIds = definition.options.map { it.id }.toSet()
        return isValidIdentifier(definition.id) && definition.question.trim().length in 1..300 &&
            definition.explanation.trim().length <= 500 &&
            (definition.isQuiz || definition.explanation.isBlank()) &&
            definition.options.size in 2..10 && optionIds.size == definition.options.size &&
            definition.options.all {
                isValidIdentifier(it.id) && it.text.trim().length in 1..100
            } && optionIds.containsAll(definition.correctOptionIds)
    }

    private fun isValidIdentifier(value: String): Boolean = value.length in 1..64 &&
        value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
}

object AloPollQuizCommitment {
    data class Prepared(val definition: AloPollDefinition, val salt: String)

    fun prepare(definition: AloPollDefinition): Prepared? {
        if (!definition.isQuiz || definition.correctOptionIds.size != 1 ||
            !definition.options.map { it.id }.toSet().containsAll(definition.correctOptionIds)
        ) return null
        val saltBytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val salt = java.util.Base64.getEncoder().encodeToString(saltBytes)
        return Prepared(
            definition.copy(quizAnswerCommitment = commitment(definition.id, definition.correctOptionIds, salt)),
            salt
        )
    }

    fun verifies(pollId: String, correctOptionIds: Set<String>, salt: String, commitment: String): Boolean {
        return isValidSalt(salt) && isValidCommitment(commitment) &&
            commitment(pollId, correctOptionIds, salt) == commitment.lowercase()
    }

    fun isValidCommitment(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}"))

    fun isValidSalt(value: String): Boolean = runCatching {
        val decoded = java.util.Base64.getDecoder().decode(value)
        decoded.size in 16..64 && java.util.Base64.getEncoder().encodeToString(decoded) == value
    }.getOrDefault(false)

    private fun commitment(pollId: String, correctOptionIds: Set<String>, salt: String): String {
        val source = "alo.poll.quiz.v1|$pollId|${correctOptionIds.sorted().joinToString(",")}|$salt"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}

// System events travel inside the normal E2EE message payload. They report a
// client-observed action; they are deliberately not treated as proof that the
// action happened outside the client.
sealed interface AloChatSystemEvent {
    data object ProtectedMediaScreenshot : AloChatSystemEvent
}

object AloChatSystemEventCodec {
    private const val PREFIX = "alo.system.v1:"
    private const val PROTECTED_MEDIA_SCREENSHOT = "protected_media_screenshot"

    fun encode(event: AloChatSystemEvent): String = when (event) {
        AloChatSystemEvent.ProtectedMediaScreenshot -> PREFIX + PROTECTED_MEDIA_SCREENSHOT
    }

    fun decode(body: String): AloChatSystemEvent? {
        if (!body.startsWith(PREFIX)) return null
        return when (body.removePrefix(PREFIX)) {
            PROTECTED_MEDIA_SCREENSHOT -> AloChatSystemEvent.ProtectedMediaScreenshot
            else -> null
        }
    }

    fun previewText(body: String, isMine: Boolean = false): String? = when (decode(body)) {
        AloChatSystemEvent.ProtectedMediaScreenshot -> if (isMine) {
            "Вы сделали снимок исчезающего медиа"
        } else {
            "Сделан снимок исчезающего медиа"
        }
        null -> null
    }
}

object AloPollProjection {
    fun states(messages: List<AloChatMessage>, currentUserId: Int): Map<String, AloPollViewState> {
        val definitions = linkedMapOf<String, Pair<AloPollDefinition, Int>>()
        val latestVotes = mutableMapOf<String, MutableMap<Int, Set<String>>>()
        val verifiedReveals = mutableMapOf<String, Set<String>>()
        messages.forEach { message ->
            when (val event = AloPollCodec.decode(message.body)) {
                is AloPollEvent.Create -> definitions[event.definition.id] = event.definition to message.senderId
                is AloPollEvent.Vote -> latestVotes
                    .getOrPut(event.pollId) { mutableMapOf() }[message.senderId] = event.optionIds
                is AloPollEvent.Reveal -> {
                    val created = definitions[event.pollId]
                    val optionIds = created?.first?.options?.map(AloPollOption::id)?.toSet().orEmpty()
                    if (created != null && created.second == message.senderId &&
                        event.correctOptionIds.all(optionIds::contains) &&
                        AloPollQuizCommitment.verifies(
                            pollId = event.pollId,
                            correctOptionIds = event.correctOptionIds,
                            salt = event.salt,
                            commitment = created.first.quizAnswerCommitment
                        )
                    ) {
                        verifiedReveals[event.pollId] = event.correctOptionIds
                    }
                }
                null -> Unit
            }
        }
        return definitions.mapValues { (_, created) ->
            val definition = created.first
            val validIds = definition.options.map { it.id }.toSet()
            val votes = latestVotes[definition.id].orEmpty().mapValues { (_, ids) ->
                val filtered = ids.intersect(validIds)
                if (definition.allowsMultipleAnswers) filtered else filtered.take(1).toSet()
            }
            val counts = definition.options.associate { it.id to 0 }.toMutableMap()
            votes.values.forEach { ids -> ids.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 } }
            AloPollViewState(
                definition = definition,
                voteCounts = counts,
                selectedOptionIds = votes[currentUserId].orEmpty(),
                totalVoters = votes.values.count { it.isNotEmpty() },
                correctOptionIds = verifiedReveals[definition.id] ?: definition.correctOptionIds
            )
        }
    }

    /**
     * Polls created in a cloud message. The server counts their votes (also the anonymous ones, which it never shows
     * to other people), and nobody sends vote events as messages there.
     */
    fun serverTalliedPollIds(messages: List<AloChatMessage>): Set<String> = buildSet {
        messages.forEach { message ->
            if (message.cloud) {
                (AloPollCodec.decode(message.body) as? AloPollEvent.Create)?.let { add(it.definition.id) }
            }
        }
    }

    fun applyingCanonical(
        canonical: Map<String, AloCanonicalPollState>,
        projected: Map<String, AloPollViewState>,
        serverTallied: Set<String> = emptySet()
    ): Map<String, AloPollViewState> = projected.mapValues { (pollId, state) ->
        val server = canonical[pollId] ?: return@mapValues state
        if (state.definition.isAnonymous && pollId !in serverTallied) {
            return@mapValues state.copy(
                isClosed = server.isClosed,
                allowsRevoting = server.allowsRevoting,
                correctOptionIds = if (server.isClosed) state.correctOptionIds else emptySet(),
                publicVoters = emptyList(),
                closesAt = server.closesAt.ifBlank { state.closesAt }
            )
        }
        state.copy(
            voteCounts = server.voteCounts,
            selectedOptionIds = server.selectedOptionIds,
            totalVoters = server.totalVoters,
            isClosed = server.isClosed,
            allowsRevoting = server.allowsRevoting,
            correctOptionIds = if (server.isClosed) state.correctOptionIds else emptySet(),
            publicVoters = server.publicVoters,
            closesAt = server.closesAt
        )
    }

    fun visibleMessages(messages: List<AloChatMessage>): List<AloChatMessage> = messages.filter {
        when (AloPollCodec.decode(it.body)) {
            is AloPollEvent.Vote, is AloPollEvent.Reveal -> false
            is AloPollEvent.Create, null -> true
        }
    }
}

data class AloResolvedAppLink(
    val kind: String,
    val id: Int,
    val handle: String
)

data class AloPostDiscussion(
    val roomId: Int,
    val peerId: Int,
    val topicId: Int
)

data class AloForumTopicLink(
    val roomId: Int,
    val peerId: Int,
    val topicId: Int
)

object AloMessageMarkup {
    private data class Marker(val token: String, val type: String)

    private val markers = listOf(
        Marker("**", "bold"),
        Marker("__", "italic"),
        Marker("++", "underline"),
        Marker("~~", "strikethrough"),
        Marker("||", "spoiler"),
        Marker("`", "code")
    )
    private val allowedTypes = setOf(
        "bold", "italic", "underline", "strikethrough", "spoiler", "code", "link", "mention", "pre", "blockquote"
    )
    private val linkExpression = Regex("""(?:https?://|alo://)[^\s<>()]+""", RegexOption.IGNORE_CASE)
    private val handleExpression = Regex("""(?<![A-Za-z0-9_])@[A-Za-z0-9_]{3,32}\b""")

    /**
     * Lines starting with "> " are a quote, and text between ``` fences (an optional language after
     * the opening one) is a code block, as in Telegram; inline markers work everywhere but in code.
     */
    fun parse(source: String): AloMessageMarkupResult {
        if (!source.contains("```") && !source.contains(">")) return parseInline(source)
        data class Segment(val kind: Int, val text: String, val language: String = "")
        val plainKind = 0
        val quoteKind = 1
        val codeKind = 2
        val segments = mutableListOf<Segment>()
        val lines = source.split("\n")
        val plain = mutableListOf<String>()
        fun flushPlain() {
            if (plain.isNotEmpty()) segments += Segment(plainKind, plain.joinToString("\n"))
            plain.clear()
        }
        fun isQuoteLine(line: String) = line == ">" || line.startsWith("> ")
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val close = if (line.startsWith("```")) {
                (index + 1 until lines.size).firstOrNull { lines[it].trim() == "```" }
            } else null
            if (close != null) {
                flushPlain()
                val language = line.drop(3).trim().take(24)
                segments += Segment(codeKind, lines.subList(index + 1, close).joinToString("\n"), language)
                index = close + 1
                continue
            }
            if (isQuoteLine(line)) {
                flushPlain()
                val quoted = mutableListOf<String>()
                while (index < lines.size && isQuoteLine(lines[index])) {
                    quoted += lines[index].drop(if (lines[index] == ">") 1 else 2)
                    index += 1
                }
                segments += Segment(quoteKind, quoted.joinToString("\n"))
                continue
            }
            // An unclosed fence is just text: its backticks are not inline code markers.
            plain += if (line.startsWith("```")) "\\`\\`\\`" + line.drop(3) else line
            index += 1
        }
        flushPlain()
        if (segments.none { it.kind != plainKind }) return parseInline(segments.joinToString("\n") { it.text })

        val body = StringBuilder()
        val entities = mutableListOf<AloMessageTextEntity>()
        segments.forEachIndexed { position, segment ->
            if (position > 0) body.append("\n")
            val offset = body.length
            if (segment.kind == codeKind) {
                body.append(segment.text)
                if (segment.text.isNotEmpty()) entities += AloMessageTextEntity("pre", offset, segment.text.length, segment.language)
            } else {
                val inline = parseInline(segment.text)
                body.append(inline.body)
                entities += inline.entities.map { it.copy(offset = it.offset + offset) }
                if (segment.kind == quoteKind && inline.body.isNotEmpty()) {
                    entities += AloMessageTextEntity("blockquote", offset, inline.body.length)
                }
            }
        }
        return AloMessageMarkupResult(body = body.toString(), entities = normalized(entities, body.length))
    }

    private fun parseInline(source: String): AloMessageMarkupResult {
        if (source.isEmpty()) return AloMessageMarkupResult("", emptyList())
        val output = StringBuilder()
        val entities = mutableListOf<AloMessageTextEntity>()
        val opened = mutableMapOf<String, Int>()
        var index = 0
        while (index < source.length) {
            if (source[index] == '\\' && index + 1 < source.length) {
                output.append(source[index + 1])
                index += 2
                continue
            }
            val marker = markers.firstOrNull { source.startsWith(it.token, index) }
            if (marker != null) {
                val afterMarker = index + marker.token.length
                val start = opened.remove(marker.token)
                if (start != null) {
                    val length = output.length - start
                    if (length > 0) {
                        entities += AloMessageTextEntity(marker.type, start, length)
                    }
                    index = afterMarker
                    continue
                }
                if (afterMarker < source.length && source.indexOf(marker.token, afterMarker) >= 0) {
                    opened[marker.token] = output.length
                    index = afterMarker
                    continue
                }
            }
            output.append(source[index])
            index += 1
        }
        linkExpression.findAll(output).forEach { match ->
            val clean = match.value.trimEnd('.', ',', '!', '?', ';', ':')
            entities += AloMessageTextEntity(
                type = "link",
                offset = match.range.first,
                length = clean.length,
                url = clean
            )
        }
        handleExpression.findAll(output).forEach { match ->
            if (entities.any { entity ->
                    val entityRange = entity.offset until (entity.offset + entity.length)
                    match.range.any(entityRange::contains)
                }) return@forEach
            val handle = match.value.removePrefix("@").lowercase()
            entities += AloMessageTextEntity(
                type = "mention",
                offset = match.range.first,
                length = match.value.length,
                url = buildAloLink("r", handle)
            )
        }
        return AloMessageMarkupResult(
            body = output.toString(),
            entities = normalized(entities, output.length)
        )
    }

    /**
     * Text that is not markup (a cloud-chat message): the entities the server kept, plus the links and @names
     * nobody marked up (the web client sends none). `**` in such a text is just two asterisks.
     */
    fun plain(body: String, explicit: List<AloMessageTextEntity>): AloMessageMarkupResult {
        val occupied = explicit.filter { it.type == "link" || it.type == "mention" }.toMutableList()
        val found = mutableListOf<AloMessageTextEntity>()
        fun isFree(start: Int, end: Int) = occupied.none { start < it.offset + it.length && it.offset < end }
        linkExpression.findAll(body).forEach { match ->
            val clean = match.value.trimEnd('.', ',', '!', '?', ';', ':')
            val start = match.range.first
            if (clean.isNotEmpty() && isFree(start, start + clean.length)) {
                val entity = AloMessageTextEntity(type = "link", offset = start, length = clean.length, url = clean)
                occupied += entity
                found += entity
            }
        }
        handleExpression.findAll(body).forEach { match ->
            val start = match.range.first
            if (isFree(start, start + match.value.length)) {
                val entity = AloMessageTextEntity(
                    type = "mention",
                    offset = start,
                    length = match.value.length,
                    url = buildAloLink("r", match.value.removePrefix("@").lowercase())
                )
                occupied += entity
                found += entity
            }
        }
        return AloMessageMarkupResult(body = body, entities = normalized(explicit + found, body.length))
    }

    fun source(body: String, entities: List<AloMessageTextEntity>): String {
        if (body.isEmpty() || entities.isEmpty()) return body
        val markerByType = markers.associate { it.type to it.token }
        val insertions = mutableMapOf<Int, MutableList<Pair<Boolean, String>>>()
        normalized(entities, body.length).forEach { entity ->
            when (entity.type) {
                "pre" -> {
                    // Opening fence first at its offset, closing fence last at its end.
                    insertions.getOrPut(entity.offset) { mutableListOf() }.add(0, false to "```${entity.url}\n")
                    insertions.getOrPut(entity.offset + entity.length) { mutableListOf() } += true to "\n```"
                }
                "blockquote" -> {
                    insertions.getOrPut(entity.offset) { mutableListOf() }.add(0, false to "> ")
                    for (position in entity.offset until entity.offset + entity.length) {
                        if (body[position] == '\n') insertions.getOrPut(position + 1) { mutableListOf() }.add(0, false to "> ")
                    }
                }
                else -> {
                    val token = markerByType[entity.type] ?: return@forEach
                    insertions.getOrPut(entity.offset) { mutableListOf() } += false to token
                    insertions.getOrPut(entity.offset + entity.length) { mutableListOf() } += true to token
                }
            }
        }
        val result = StringBuilder(body)
        insertions.keys.sortedDescending().forEach { position ->
            val values = insertions[position].orEmpty()
            val closing = values.filter { it.first }.asReversed().joinToString("") { it.second }
            val opening = values.filterNot { it.first }.joinToString("") { it.second }
            result.insert(position, closing + opening)
        }
        return result.toString()
    }

    fun parseEntities(array: JSONArray?, bodyLength: Int): List<AloMessageTextEntity> {
        if (array == null) return emptyList()
        val result = buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val type = value.optString("type").trim().lowercase()
                val offset = value.optInt("offset", -1)
                val length = value.optInt("length", 0)
                if (type in allowedTypes && offset >= 0 && length > 0) {
                    add(AloMessageTextEntity(type, offset, length, value.optString("url")))
                }
            }
        }
        return normalized(result, bodyLength)
    }

    fun normalized(
        entities: List<AloMessageTextEntity>,
        bodyLength: Int
    ): List<AloMessageTextEntity> = entities
        .asSequence()
        .filter { it.type in allowedTypes && it.offset >= 0 && it.length > 0 && it.offset + it.length <= bodyLength }
        .take(128)
        .sortedWith(compareBy<AloMessageTextEntity> { it.offset }.thenByDescending { it.length })
        .toList()
}

data class AloMessageReaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
    /** A group sends the first people who reacted (up to three); the chip shows their avatars in place of the counter. */
    val users: List<AloConversationPeer> = emptyList()
)

data class AloMessageSendAcknowledgement(
    val messageId: Int,
    val createdAt: String,
    val scheduled: Boolean,
    val scheduledMessageId: Int,
    val scheduledAt: String,
    val sendWhenOnline: Boolean
)

data class AloMessageExpiryState(
    val selfDestructSeconds: Int,
    val selfDestructStartedAt: String,
    val expiresAt: String
)

data class AloMessageForwardSource(
    val userId: Int,
    val messageId: Int,
    val userName: String,
    /**
     * What the forwarded message said, taken from the message on this device. A cloud chat sends it as the body of
     * the new message; an old encrypted message cannot be copied by the server, so this copy is all there is.
     */
    val body: String = "",
    val entities: List<AloMessageTextEntity> = emptyList(),
    val attachments: List<AloOutgoingMessageAttachment> = emptyList(),
    val encrypted: Boolean = false
) {
    val hasContent: Boolean get() = body.isNotBlank() || attachments.isNotEmpty()
}

data class AloGroupMember(
    val user: AloEntitySummary,
    val role: String,
    val adminTitle: String = "",
    val isAnonymous: Boolean = false,
    val adminRights: Map<String, Boolean> = emptyMap(),
    val restrictions: Map<String, Boolean> = emptyMap(),
    val restrictedUntil: String = ""
)

data class AloGroupBan(
    val user: AloEntitySummary,
    val reason: String,
    val createdAt: String,
    val expiresAt: String,
    val bannedBy: AloEntitySummary
)

data class AloGroupPolicy(
    val kind: String = "group",
    val username: String = "",
    val joinPolicy: String = "invite",
    val isForum: Boolean = false,
    val historyVisible: Boolean = true,
    val slowModeSeconds: Int = 0,
    val autoDeleteSeconds: Int = 0,
    val defaultPermissions: Map<String, Boolean> = emptyMap(),
    val memberAdminRights: Map<String, Boolean> = emptyMap(),
    val memberRestrictions: Map<String, Boolean> = emptyMap(),
    val memberRestrictedUntil: String = "",
    /** «Запретить копирование»: no forwarding, copying, saving or screenshots of the chat's messages. */
    val protectedContent: Boolean = false
)

fun AloGroupPolicy.normalizedCommunityPolicy(): AloGroupPolicy {
    val normalizedKind = if (kind.trim().equals("channel", ignoreCase = true)) "channel" else "group"
    return if (normalizedKind == "channel") {
        copy(
            kind = normalizedKind,
            isForum = false,
            historyVisible = true,
            slowModeSeconds = 0
        )
    } else {
        copy(kind = normalizedKind)
    }
}

data class AloGroupJoinRequest(
    val user: AloEntitySummary,
    val createdAt: String
)

data class AloGroupInviteLink(
    val id: Int,
    val label: String,
    val requiresApproval: Boolean,
    val usageLimit: Int,
    val usageCount: Int,
    val joinedCount: Int = 0,
    val pendingCount: Int = 0,
    val expiresAt: String,
    val revokedAt: String,
    val createdAt: String,
    val creator: AloEntitySummary?
) {
    val isActive: Boolean
        get() = revokedAt.isBlank() && (
            expiresAt.isBlank() ||
                runCatching { java.time.Instant.parse(expiresAt).isAfter(java.time.Instant.now()) }
                    .getOrDefault(false)
            )
}

data class AloGroupInviteParticipant(
    val user: AloEntitySummary,
    val createdAt: String
)

data class AloGroupInviteStats(
    val invite: AloGroupInviteLink,
    val joined: List<AloGroupInviteParticipant>,
    val pending: List<AloGroupInviteParticipant>
)

data class AloGroupAdminEvent(
    val id: Int,
    val action: String,
    val createdAt: String,
    val actor: AloEntitySummary,
    val target: AloEntitySummary?
)

data class AloGroupAdminLogPage(
    val events: List<AloGroupAdminEvent>,
    val nextBeforeId: Int?
)

data class AloForumTopic(
    val id: Int,
    val roomId: Int,
    val title: String,
    val iconEmoji: String,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val isHidden: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class AloForumTopicState(
    val topicId: Int,
    val cursor: Int,
    val lastReadMessageId: Int,
    val unreadCount: Int,
    val muted: Boolean,
    val draftRevision: Int,
    val stateVersion: Int,
    val draftUpdatedAt: String,
    val updatedAt: String,
    val draftText: String = ""
)

data class AloGroupManagementSnapshot(
    val policy: AloGroupPolicy,
    val members: List<AloGroupMember>,
    val requests: List<AloGroupJoinRequest>,
    val invites: List<AloGroupInviteLink> = emptyList(),
    val events: List<AloGroupAdminEvent>,
    val topics: List<AloForumTopic> = emptyList(),
    val bans: List<AloGroupBan> = emptyList(),
    val nextAdminLogBeforeId: Int? = null
)

data class AloGroupManagementDelta(
    val policy: AloGroupPolicy?,
    val membersUpserted: List<AloGroupMember>,
    val memberIdsRemoved: Set<Int>,
    val requestsUpserted: List<AloGroupJoinRequest>,
    val requestUserIdsRemoved: Set<Int>,
    val bansUpserted: List<AloGroupBan>,
    val banUserIdsRemoved: Set<Int>,
    val invitesUpserted: List<AloGroupInviteLink>,
    val topicsUpserted: List<AloForumTopic>,
    val topicIdsRemoved: Set<Int>,
    val adminEvent: AloGroupAdminEvent?
)

data class AloConversationDetail(
    val peerId: Int,
    val kind: String,
    val roomId: Int,
    val title: String,
    val subtitle: String,
    val username: String,
    val profileDescription: String = "",
    val lastSeenAt: String,
    val avatarUrl: String,
    val verified: Boolean,
    val online: Boolean,
    val isTyping: Boolean = false,
    /** What the other side does while [isTyping]: "typing", "voice" (records a voice message) or "circle" (a video message). */
    val typingAction: String = "",
    val memberCount: Int,
    val entityType: String,
    val memberRole: String = "",
    val canManage: Boolean = false,
    val members: List<AloGroupMember> = emptyList(),
    val items: List<AloChatMessage>,
    val groupPolicy: AloGroupPolicy? = null,
    val forumTopics: List<AloForumTopic> = emptyList(),
    val forumTopicStates: List<AloForumTopicState> = emptyList(),
    val pinnedMessageIds: List<Int> = emptyList(),
    val canonicalPolls: Map<String, AloCanonicalPollState> = emptyMap(),
    val autoDeleteSeconds: Int = 0
)

data class AloMediaItem(
    val type: String,
    val url: String,
    val posterUrl: String,
    val alt: String,
    val caption: String,
    val isSensitive: Boolean = false,
    /** Pixel size when the server knows it, so a post reserves the right height (as on iOS). */
    val width: Int = 0,
    val height: Int = 0
)

data class AloUploadedFile(
    val url: String,
    val type: String,
    val mime: String,
    val mediaEncryption: Map<String, String> = emptyMap()
)

/**
 * The repeat rule is deliberately part of the client contract instead of a UI-only
 * flag.  The server owns the durable schedule; Android only supplies the first
 * fire time and the rule.
 */
data class AloMessageRepeatSchedule(
    val startsAt: String,
    val intervalSeconds: Long,
    val occurrences: Int = 0
) {
    init {
        require(intervalSeconds >= 60L) { "Repeat interval must be at least one minute" }
        require(occurrences >= 0) { "Occurrences cannot be negative" }
    }
}

/** Result returned by the optional message-translation capability. */
data class AloMessageTranslationResult(
    val messageId: Int,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

/** Result returned by the optional voice-transcription capability. */
data class AloVoiceTranscriptionResult(
    val messageId: Int,
    val text: String,
    val language: String
)

/** Parameters for a server-backed live-location message. */
data class AloLiveLocationRequest(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val address: String = "",
    val livePeriodSeconds: Int
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Location coordinates are invalid"
        }
        require(livePeriodSeconds in 60..(24 * 60 * 60)) {
            "Live location period must be between one minute and one day"
        }
    }
}

/**
 * A media item returned by Alo's sticker or GIF catalog.  It intentionally
 * contains only a server-approved source URL; choosing it still goes through
 * the regular encrypted attachment pipeline before it is sent to a chat.
 */
data class AloCatalogExpressionMedia(
    val id: String,
    val kind: String,
    val assetUrl: String,
    val previewUrl: String,
    val title: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Double = 0.0
) {
    val isSticker: Boolean
        get() = kind == "sticker"

    val isGif: Boolean
        get() = kind == "gif"

    fun suggestedFileName(): String {
        val safeId = id
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(80)
            .ifBlank { "media" }
        val extension = Regex("\\.([A-Za-z0-9]{2,8})(?:[?#].*)?$")
            .find(assetUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?.takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "webm") }
            ?: if (isGif) "gif" else "webp"
        return "${if (isSticker) "sticker" else "gif"}-$safeId.$extension"
    }
}

data class AloStickerPack(
    val id: String,
    val title: String,
    val shortName: String,
    val description: String,
    val coverUrl: String,
    val official: Boolean,
    val installed: Boolean,
    val itemCount: Int,
    val items: List<AloCatalogExpressionMedia> = emptyList()
)

data class AloGifCatalogPage(
    val gifs: List<AloCatalogExpressionMedia>,
    val nextOffset: String = ""
)

/** A durable cursor for a resumable encrypted upload. */
/** Thrown between pieces when the person pauses an upload; the checkpoint lets it resume there. */
class AloUploadPausedException : java.util.concurrent.CancellationException("Загрузка приостановлена.")

/** How an upload is going: sent and total bytes, for the ring and «512 МБ из 1,48 ГБ». */
class AloUploadReporter(
    val onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    val shouldPause: () -> Boolean = { false }
)

data class AloResumableUploadCheckpoint(
    val uploadId: String,
    val offset: Long,
    val total: Long,
    val checksum: String
)

/** An encrypted attachment still on the server: the auto-download rules (or a failed download) left it for a tap. */
val AloOutgoingMessageAttachment.isUndownloaded: Boolean
    get() = mediaEncryption.isNotEmpty() && url.isNotBlank() &&
        !url.startsWith("content://", ignoreCase = true) && !url.startsWith("file://", ignoreCase = true)

fun AloChatMessage.hasUndownloadedMedia(): Boolean = attachments.any { it.isUndownloaded }

/** The plaintext size of what is still to download, 0 when the senders did not say. */
fun AloChatMessage.undownloadedBytes(): Long = attachments.filter { it.isUndownloaded }.sumOf {
    (it.mediaEncryption["plaintextSize"] ?: it.metadata["size"])?.toLongOrNull() ?: 0L
}

data class AloOutgoingMessageAttachment(
    val url: String,
    val type: String,
    val name: String,
    val mediaEncryption: Map<String, String> = emptyMap(),
    /** Data in this map is part of the E2EE payload, never the media envelope. */
    val metadata: Map<String, String> = emptyMap(),
    /** A preview picture (cloud chats): `/uploads/messages/…` or a sticker thumbnail. */
    val posterUrl: String = "",
    /** Bars of a voice message, 0…1 (cloud chats). Empty when the sender gave none. */
    val waveform: List<Float> = emptyList()
) {
    val isVirtualCard: Boolean
        get() = type == "contact" || type == "location"

    val isContactCard: Boolean
        get() = type == "contact"

    val isLocationCard: Boolean
        get() = type == "location"

    companion object {
        fun contact(entity: AloEntitySummary): AloOutgoingMessageAttachment {
            return AloOutgoingMessageAttachment(
                url = "",
                type = "contact",
                name = entity.name,
                metadata = mapOf(
                    "id" to "contact-${entity.id}",
                    "userId" to entity.id.toString(),
                    "displayName" to entity.name,
                    "username" to entity.username,
                    "avatarUrl" to entity.avatarUrl,
                    "verified" to entity.verified.toString()
                )
            )
        }

        fun location(
            latitude: Double,
            longitude: Double,
            title: String = "Текущее местоположение",
            address: String = "",
            livePeriodSeconds: Int = 0
        ): AloOutgoingMessageAttachment {
            val normalizedLatitude = "%.5f".format(java.util.Locale.US, latitude)
            val normalizedLongitude = "%.5f".format(java.util.Locale.US, longitude)
            return AloOutgoingMessageAttachment(
                url = "",
                type = "location",
                name = title,
                metadata = buildMap {
                    put("id", "location-$normalizedLatitude-$normalizedLongitude")
                    put("latitude", normalizedLatitude)
                    put("longitude", normalizedLongitude)
                    put("title", title)
                    if (address.isNotBlank()) put("address", address)
                    if (livePeriodSeconds > 0) {
                        put("kind", "live")
                        put("livePeriodSeconds", livePeriodSeconds.toString())
                        put("liveStartedAt", Instant.now().toString())
                    }
                }
            )
        }
    }
}

data class AloPost(
    val id: Int,
    val creatorId: Int,
    val body: String,
    val media: List<AloMediaItem>,
    val authorSignature: String,
    val commentsEnabled: Boolean,
    val discussionRoomId: Int,
    val discussionTopicId: Int,
    val messengerRoomId: Int,
    val likeCount: Int,
    val commentCount: Int,
    val repostCount: Int,
    val viewCount: Int,
    val viewerLiked: Boolean,
    val viewerReposted: Boolean,
    val createdAt: String,
    val author: AloEntitySummary,
    /** Why the feed shows it: own, own_channel, subscribed_channel, friend or popular. */
    val feedReason: String = "",
    /** When the author last changed it; empty for a post never edited. */
    val editedAt: String = ""
)

/** One page of a list the first screen only shows the top of: [nextOffset] is where the following page starts. */
data class AloPage<T>(val items: List<T>, val hasMore: Boolean, val nextOffset: Int)

/** A short-lived, author-owned item shown above the feed. */
data class AloStory(
    val id: Int,
    val userId: Int,
    val body: String,
    val media: List<AloMediaItem>,
    val createdAt: String,
    val expiresAt: String,
    val viewerHasSeen: Boolean,
    val viewCount: Int,
    /** everyone, friends, close_friends or selected. */
    val audience: String = "everyone"
)

data class AloStoryGroup(
    val user: AloEntitySummary,
    val stories: List<AloStory>,
    val hasUnseen: Boolean
)

/** A profile's «Актуальное»: a titled set of past stories (Telegram's highlights). */
data class AloStoryHighlight(
    val id: Int,
    val title: String,
    val coverStoryId: Int,
    val stories: List<AloStory>
) {
    val cover: AloStory? get() = stories.firstOrNull { it.id == coverStoryId } ?: stories.firstOrNull()
}

/** Your posts' reach for a period against the one before (Instagram's «Панель автора»). */
data class AloCreatorStats(
    val days: Int,
    val views: Metric,
    val likes: Metric,
    val comments: Metric,
    val reposts: Metric,
    val followers: Metric,
    val engagementRate: Double,
    val postsInPeriod: Int,
    val allTime: AllTime,
    val daily: List<Day>,
    val topPosts: List<AloPost>
) {
    data class Metric(val current: Int, val previous: Int) {
        /** Change against the period before, in percent; null when there is nothing to compare with. */
        val change: Double? get() = if (previous > 0) (current - previous) * 100.0 / previous else if (current > 0) null else 0.0
    }
    data class Day(val date: String, val views: Int, val likes: Int, val comments: Int)
    data class AllTime(val posts: Int, val views: Int, val likes: Int, val comments: Int, val reposts: Int, val followers: Int)
}

/** A saved, unpublished post: kept on the server, so it opens on any device. */
data class AloPostDraft(val id: Int, val body: String, val media: List<AloMediaItem>, val updatedAt: String)

sealed interface AloStoryLinkResult {
    data class Live(val group: AloStoryGroup) : AloStoryLinkResult
    data class Expired(val author: AloEntitySummary?) : AloStoryLinkResult
}

/** Who opened a story and when; only its author may ask. */
data class AloStoryViewerEntry(val user: AloEntitySummary, val viewedAt: String)

/** Your close friends and the people your stories are hidden from. */
data class AloStorySettings(
    val closeFriends: List<AloEntitySummary> = emptyList(),
    val hiddenFrom: List<AloEntitySummary> = emptyList()
)

/** Alo's own ad: always labelled «Реклама», always hideable. */
data class AloAd(
    val id: Int,
    val placement: String,
    val label: String,
    val advertiser: String,
    val title: String,
    val body: String,
    val ctaLabel: String,
    val ctaUrl: String,
    val why: String
)

data class AloBusinessProfile(
    val userId: Int,
    val enabled: Boolean,
    val businessName: String,
    val category: String,
    val hours: Map<String, String>,
    val greetingMessage: String,
    val autoReplyEnabled: Boolean,
    val autoReplyMessage: String,
    val updatedAt: String
)

data class AloBusinessQuickReply(
    val id: Int,
    val shortcut: String,
    val title: String,
    val body: String,
    val sortOrder: Int,
    val updatedAt: String
)

data class AloBusinessChatLabel(
    val id: Int,
    val chatKind: String,
    val peerId: Int,
    val roomId: Int,
    val label: String,
    val color: String,
    val updatedAt: String
)

data class AloBusinessProfileData(
    val profile: AloBusinessProfile,
    val quickReplies: List<AloBusinessQuickReply>,
    val chatLabels: List<AloBusinessChatLabel>
)

data class AloPostComment(
    val id: Int,
    val body: String,
    val media: List<AloMediaItem>,
    val createdAt: String,
    val editedAt: String = "",
    val likeCount: Int,
    val viewerLiked: Boolean,
    val author: AloEntitySummary,
    /** The root comment of the thread a reply belongs to; 0 for a root comment. */
    val parentId: Int = 0,
    /** Whom a reply answers. */
    val replyTo: AloEntitySummary? = null
)

data class AloCommentSubmitResult(
    val comment: AloPostComment?,
    val post: AloPost?
)

data class AloCommentDeleteResult(
    val commentId: Int,
    val post: AloPost?
)

data class AloUserRelationUpdate(
    val userId: Int,
    val following: Boolean,
    val followedBy: Boolean,
    val isFriend: Boolean,
    val friendStatus: String,
    val incomingRequestId: Int,
    val outgoingRequestId: Int,
    val canSendFriendRequest: Boolean,
    val stats: AloProfileStats
)

data class AloNotificationItem(
    val id: Int,
    val type: String,
    val text: String,
    val createdAt: String,
    val entityType: String,
    val entityId: Int,
    val friendRequestStatus: String = "",
    val friendRequestActionable: Boolean = false,
    val actor: AloEntitySummary?,
    val isRead: Boolean = false
)

data class AloProfileStats(
    val followingCount: Int,
    val followersCount: Int,
    val friendsCount: Int,
    val channelsOwnedCount: Int,
    val channelsSubscribedCount: Int
)

data class AloProfileRelations(
    val friendsCount: Int,
    val followingCount: Int,
    val followersCount: Int,
    val channelsCount: Int
)

data class AloProfileMetric(
    val label: String,
    val value: String
)

data class AloChannelAdminEvent(
    val id: Int,
    val action: String,
    val postId: Int?,
    val createdAt: String,
    val actor: AloEntitySummary,
    val target: AloEntitySummary?
)

data class AloChannelManagementDelta(
    val moderatorCount: Int,
    val commentsEnabled: Boolean?,
    val signMessages: Boolean?,
    val showAuthorProfiles: Boolean?,
    val discussionRoomId: Int?,
    val hasSettings: Boolean,
    val adminEvent: AloChannelAdminEvent?
)

data class AloViewedProfile(
    val kind: String,
    val id: Int,
    val title: String,
    val username: String,
    val avatarUrl: String,
    val avatarGallery: List<String>,
    val verified: Boolean,
    val coverStyle: String,
    val coverImageUrl: String,
    val status: String,
    val meta: String,
    val description: String,
    val metrics: List<AloProfileMetric>,
    val posts: List<AloPost>,
    val friendStatus: String = "none",
    val incomingFriendRequestId: Int = 0,
    val outgoingFriendRequestId: Int = 0,
    val canSendFriendRequest: Boolean = false,
    /** The viewer follows this person / this person follows the viewer (a follow back makes friends). */
    val following: Boolean = false,
    val followedBy: Boolean = false,
    val canMessage: Boolean = true,
    val isPrivate: Boolean = false,
    val canViewContent: Boolean = true,
    val canManage: Boolean = false,
    val isOwner: Boolean = false,
    val commentsEnabled: Boolean = true,
    val signMessages: Boolean = false,
    val showAuthorProfiles: Boolean = false,
    val discussionRoomId: Int = 0,
    val adminEvents: List<AloChannelAdminEvent> = emptyList()
)

data class AloNotificationSettings(
    val likesEnabled: Boolean = true,
    val commentsEnabled: Boolean = true,
    val repostsEnabled: Boolean = true,
    val followsEnabled: Boolean = true,
    val messagesEnabled: Boolean = true,
    val friendRequestsEnabled: Boolean = true,
    val friendAcceptsEnabled: Boolean = true,
    val channelSubscriptionsEnabled: Boolean = true,
    val commentLikesEnabled: Boolean = true
)

data class AloPrivacySettings(
    val allowFriendRequests: Boolean = true,
    val messageScope: String = "all",
    val allowMessageForwards: Boolean = true,
    val callScope: String = "all",
    val hideActivity: Boolean = false,
    val profileVisibility: String = "all"
)

data class AloBootstrapData(
    val me: AloCurrentUser,
    val feedPopular: List<AloPost>,
    val feedSubscriptions: List<AloPost>,
    val notifications: List<AloNotificationItem>,
    val searchUsers: List<AloEntitySummary>,
    val searchChannels: List<AloEntitySummary>,
    val profileStats: AloProfileStats,
    val profileRelations: AloProfileRelations,
    val profilePosts: List<AloPost>,
    val notificationSettings: AloNotificationSettings,
    val privacySettings: AloPrivacySettings,
    val messagesConversations: List<AloConversationSummary>,
    val messagesActivePeerId: Int,
    val messagesItems: List<AloChatMessage>
)

data class AloBootstrapSnapshot(
    val data: AloBootstrapData,
    val rawJson: JSONObject
)

data class AloRtcIceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)

data class AloRtcConfig(
    val iceServers: List<AloRtcIceServer> = emptyList()
)

data class AloDirectCallSession(
    val id: Int,
    val type: String,
    val callerId: Int,
    val calleeId: Int,
    val status: String,
    val createdAt: String
)

data class AloDirectCallSignal(
    val id: Int,
    val callId: Int,
    val fromUserId: Int,
    val toUserId: Int,
    val kind: String,
    val callType: String,
    val callStatus: String,
    val callerId: Int,
    val calleeId: Int,
    val fromUser: AloEntitySummary?,
    val payload: JSONObject,
    val createdAt: String
)

data class AloGroupCallSession(
    val id: Int,
    val roomId: Int,
    val type: String,
    val status: String,
    val roomTitle: String,
    val participants: List<AloEntitySummary> = emptyList()
)

data class AloGroupCallSfuJoin(
    val serverUrl: String,
    val roomName: String,
    val identity: String,
    val token: String,
    val expiresAt: String
)

data class AloGroupCallSignal(
    val id: Int,
    val callId: Int,
    val roomId: Int,
    val fromUserId: Int,
    val toUserId: Int,
    val kind: String,
    val payload: JSONObject,
    val createdAt: String
)

data class AloSecuritySession(
    val id: Int,
    val current: Boolean,
    val canRevoke: Boolean,
    val revokeAvailableAt: String,
    val createdAt: String,
    val lastSeenAt: String,
    val expiresAt: String,
    val ip: String,
    val suspicious: Boolean,
    val deviceLabel: String,
    val platform: String,
    val deviceType: String
)

data class AloSecurityEvent(
    val id: Int,
    val eventType: String,
    val riskLevel: String,
    val ip: String,
    val createdAt: String
)

data class AloPasskey(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String
)

data class AloQrLoginSession(
    val id: String,
    val secret: String,
    val qrPayload: String,
    val status: String,
    val expiresAt: String,
    val approvedAt: String = "",
    /** Device link only: the new device scanned the code and waits for the owner to confirm. */
    val claimed: Boolean = false
) {
    val isApproved: Boolean
        get() = status.equals("approved", ignoreCase = true) ||
            status.equals("complete", ignoreCase = true) ||
            status.equals("completed", ignoreCase = true)

    val isExpired: Boolean
        get() = status.lowercase() in setOf("expired", "cancelled", "canceled", "rejected", "consumed")
}

data class AloAccountExport(
    val fileName: String,
    val mimeType: String,
    val generatedAt: String
)

/**
 * App-private temporary representation of an account export. The file is deleted as
 * soon as Android's document picker finishes writing it to the user-selected URI.
 * It deliberately never enters Compose state or preferences.
 */
internal data class AloAccountExportPayload(
    val export: AloAccountExport,
    val file: File
)

private data class AloLibSignalRemoteDevice(
    val userId: Int,
    val deviceId: String,
    val protocolDeviceId: Int,
    val identityKey: ByteArray,
    val identityFingerprint: String,
    val preKeyBundle: AloLibSignalPreKeyBundle?
)

private data class AloLibSignalGroupRecipients(
    val roomId: Int,
    val epoch: Int,
    val complete: Boolean,
    val devices: List<AloLibSignalRemoteDevice>
)

data class AloConversationHistoryPage(
    val items: List<AloChatMessage>,
    val hasMore: Boolean,
    val nextBeforeId: Int
)

/**
 * `PUT /api/push/device-token`. [soundsEnabled] is «Уведомления со звуком»: the server sends this device's pushes
 * to the silent channel when it is false. Left out when null, the server keeps what it has.
 */
internal fun pushTokenRegistrationBody(
    token: String,
    deviceId: String,
    appVersion: String,
    soundsEnabled: Boolean?,
    previewsEnabled: Boolean? = null
): JSONObject = JSONObject()
    .put("platform", "android")
    .put("token", token.trim())
    .put("deviceId", deviceId.trim())
    .put("appVersion", appVersion.trim())
    .apply { if (soundsEnabled != null) put("soundsEnabled", soundsEnabled) }
    .apply { if (previewsEnabled != null) put("previewsEnabled", previewsEnabled) }

class AloApiException(
    val status: Int,
    val code: String?,
    override val message: String,
    val payload: JSONObject
) : IllegalStateException(message)

/** What the server issues before a passkey is made or used: the WebAuthn options, and the id that must come back with the answer. */
data class AloPasskeyChallenge(val requestId: String, val optionsJson: String)

internal fun parseAloPasskeys(response: JSONObject): List<AloPasskey> {
    val array = response.optJSONArray("credentials") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val json = array.optJSONObject(index) ?: return@mapNotNull null
        val id = json.optString("id").trim().takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
        AloPasskey(
            id = id,
            name = json.optString("label").ifBlank { "Ключ доступа" },
            createdAt = json.optString("createdAt"),
            lastUsedAt = json.optString("lastUsedAt")
        )
    }
}

internal fun parseAloPasskeyChallenge(response: JSONObject): AloPasskeyChallenge {
    val requestId = response.optString("requestId")
    val options = response.optJSONObject("options")
    require(requestId.isNotBlank() && options != null && options.length() > 0) { "Сервер не вернул параметры ключа доступа." }
    return AloPasskeyChallenge(requestId, options.toString())
}

internal fun aloPasskeyVerifyBody(challenge: AloPasskeyChallenge, credentialResponseJson: String): JSONObject =
    JSONObject().put("requestId", challenge.requestId).put("credential", JSONObject(credentialResponseJson))

class AloApiClient(
    private val baseUrl: String,
    private val applicationContext: Context? = null,
    private val e2eeCrypto: AloE2eeCrypto? = null,
    private val e2eeTrustStore: AloE2eeTrustStore? = null,
    private val e2eeMediaCacheDir: File? = null,
    private val libSignalPreferences: SharedPreferences? = null
) {
    private companion object {
        const val GROUP_CHAT_PEER_OFFSET = 1_000_000_000
        const val LIBSIGNAL_SCHEME = "alo-libsignal-v1"
        const val MAX_E2EE_MEDIA_CIPHERTEXT_BYTES =
            2L * 1_024L * 1_024L * 1_024L + 32L * 1_024L
        const val MAX_ACCOUNT_EXPORT_BYTES = 512L * 1_024L * 1_024L
        const val MAX_CATALOG_MEDIA_BYTES = 32L * 1_024L * 1_024L
        const val MAX_CATALOG_REDIRECTS = 3
        const val MESSAGE_POLICY_TTL_MS = 10L * 60L * 1000L
        private const val CALL_SIGNAL_RECIPIENTS_TTL_MS = 60_000L
        val ENCRYPTED_DIRECT_CALL_SIGNAL_KINDS = setOf(
            "security-commit",
            "security-response",
            "offer",
            "answer",
            "security-confirm",
            "reconnect-offer",
            "reconnect-answer",
            "ice",
            "media-state"
        )
    }

    private val cookieUrl = URL(URL(baseUrl), "/").toString()
    private val callSignalRecipientsCache = HashMap<Int, Pair<Long, List<AloLibSignalRemoteDevice>>>()
    /** Texts of call signals already opened (a poll can return one again); the newest few hundred are enough. */
    private val openedCallSignals = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 512
    }
    /** Stable per installation; intentionally separate from the E2EE device identifier. */
    private val installDeviceId: String = applicationContext
        ?.let(::AloSecureAccountStore)
        ?.installDeviceId()
        .orEmpty()
    private val releaseInsecureTransport = !BuildConfig.ALO_DEV_HOOKS && !baseUrl.startsWith("https://", ignoreCase = true)
    private var currentUserId: Int = 0
    private var libSignalUserId: Int = 0
    private var libSignalProtocolDeviceId: Int = 0
    private var libSignalStore: AloLibSignalStore? = null
    private var libSignalEngine: AloLibSignalSessionEngine? = null
    private var decodedMediaCacheCleaned = false
    var latestE2eeWarning: String? = null
        private set

    @Volatile
    private var cachedSecurityPolicy: AloMessageSecurityPolicy? = null
    @Volatile
    private var cachedSecurityPolicyAt = 0L
    /** Group id → (lower-case @name → user id), filled when a group chat is opened; cloud messages use it for `mentionedUserIds`. */
    private val groupMentionDirectory = java.util.concurrent.ConcurrentHashMap<Int, Map<String, Int>>()

    /**
     * The key warning of this device, unless every chat is a cloud chat: then no conversation depends on the
     * keys and the warning would be about nothing (calls keep their own errors).
     */
    val displayedE2eeWarning: String?
        get() = latestE2eeWarning?.takeUnless { cachedSecurityPolicy?.cloudEverywhere == true }

    /**
     * `GET /api/config` → `security.plaintext…MessagesAllowed`, kept for ten minutes. With no connection the last
     * answer is used; with none yet the call fails like any other request and the outbox tries again later.
     */
    suspend fun messageSecurityPolicy(forceRefresh: Boolean = false): AloMessageSecurityPolicy {
        val cached = cachedSecurityPolicy
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cachedSecurityPolicyAt < MESSAGE_POLICY_TTL_MS) return cached
        return try {
            val policy = AloMessageSecurityPolicy.fromConfig(request("GET", "/api/config"))
            cachedSecurityPolicy = policy
            cachedSecurityPolicyAt = now
            policy
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            cached ?: throw error
        }
    }

    private fun isGroupTarget(kind: String, peerId: Int, roomId: Int): Boolean =
        kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET

    /** True when a message to this chat is sent as plain text (a cloud chat) instead of through libsignal. */
    suspend fun cloudMessagesAllowed(kind: String, peerId: Int, roomId: Int = 0): Boolean =
        messageSecurityPolicy().allowsCloud(isGroupTarget(kind, peerId, roomId))

    /** An address of ours (or another https one) that a sticker card may carry; null means the sticker has to be uploaded. */
    fun stickerCardUrl(value: String): String? = AloCloudMessages.cardUrlOrNull(value, baseUrl)

    private fun groupRoomIdFrom(peerId: Int, roomId: Int): Int {
        if (roomId > 0) return roomId
        return if (peerId >= GROUP_CHAT_PEER_OFFSET) {
            peerId - GROUP_CHAT_PEER_OFFSET
        } else {
            0
        }
    }

    private fun JSONObject.putCaptchaToken(token: String?): JSONObject {
        val safeToken = token?.trim().orEmpty()
        if (safeToken.isNotBlank()) {
            put("captchaToken", safeToken)
        }
        return this
    }

    suspend fun hasSession(): Boolean {
        return try {
            request("GET", "/api/auth/me")
            true
        } catch (error: AloApiException) {
            if (error.status == 401) {
                false
            } else {
                throw error
            }
        }
    }

    suspend fun loadBootstrap(): AloBootstrapData {
        return loadBootstrapSnapshot().data
    }

    suspend fun loadBootstrapSnapshot(): AloBootstrapSnapshot {
        val response = request("GET", "/api/app/bootstrap")
        currentUserId = response.optJSONObject("data")?.optJSONObject("me")?.optInt("id") ?: 0
        registerE2eeDeviceKeyIfPossible(currentUserId)
        registerLibSignalDeviceIfPossible(currentUserId)
        // Cloud chats or libsignal: asked once here so the key warning below knows whether any chat needs the keys.
        runCatching { messageSecurityPolicy() }
        decryptBootstrapResponse(response)
        return AloBootstrapSnapshot(
            data = parseBootstrapResponse(response),
            rawJson = response
        )
    }

    internal fun parseBootstrapResponse(response: JSONObject): AloBootstrapData {
        val data = response.optJSONObject("data") ?: JSONObject()
        val meJson = data.optJSONObject("me") ?: JSONObject()
        val feedJson = data.optJSONObject("feed") ?: JSONObject()
        val searchJson = data.optJSONObject("search") ?: JSONObject()
        val profileJson = data.optJSONObject("profile") ?: JSONObject()
        val messagesJson = data.optJSONObject("messages") ?: JSONObject()

        return AloBootstrapData(
            me = parseCurrentUser(meJson),
            feedPopular = parsePosts(feedJson.optJSONArray("popular")),
            feedSubscriptions = parsePosts(feedJson.optJSONArray("subs")),
            notifications = parseNotifications(data.optJSONArray("notifications")),
            searchUsers = parseEntities(searchJson.optJSONArray("users")),
            searchChannels = parseEntities(searchJson.optJSONArray("channels"), forceChannel = true),
            profileStats = parseProfileStats(profileJson.optJSONObject("stats")),
            profileRelations = parseProfileRelations(profileJson.optJSONObject("relations")),
            profilePosts = parsePosts(profileJson.optJSONArray("posts")),
            notificationSettings = parseNotificationSettings(data.optJSONObject("notificationSettings")),
            privacySettings = parsePrivacySettings(data.optJSONObject("privacySettings")),
            messagesConversations = parseConversations(messagesJson.optJSONArray("conversations")),
            messagesActivePeerId = messagesJson.optInt("activePeerId"),
            messagesItems = parseChatMessages(messagesJson.optJSONArray("items"))
        )
    }

    suspend fun loadRtcConfig(): AloRtcConfig {
        val response = request("GET", "/api/config")
        val rtc = response.optJSONObject("rtc") ?: JSONObject()
        val items = mutableListOf<AloRtcIceServer>()
        val array = rtc.optJSONArray("iceServers")
        if (array != null) {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val urls = mutableListOf<String>()
                when (val rawUrls = json.opt("urls")) {
                    is JSONArray -> {
                        for (urlIndex in 0 until rawUrls.length()) {
                            val url = rawUrls.optString(urlIndex).trim()
                            if (url.isNotBlank()) urls += url
                        }
                    }
                    is String -> {
                        if (rawUrls.isNotBlank()) {
                            urls += rawUrls.trim()
                        }
                    }
                }
                if (urls.isEmpty()) continue
                items += AloRtcIceServer(
                    urls = urls,
                    username = json.optString("username"),
                    credential = json.optString("credential")
                )
            }
        }
        return AloRtcConfig(iceServers = items)
    }

    suspend fun registerPushToken(
        token: String,
        deviceId: String,
        appVersion: String,
        soundsEnabled: Boolean? = null,
        previewsEnabled: Boolean? = null
    ) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return
        request(
            method = "PUT",
            path = "/api/push/device-token",
            body = pushTokenRegistrationBody(safeToken, deviceId, appVersion, soundsEnabled, previewsEnabled)
        )
    }

    /** A small picture for a notification (an avatar): /uploads needs the session cookie. Null when it cannot be had. */
    suspend fun downloadNotificationImage(path: String, maxBytes: Int = 3_000_000): ByteArray? = withContext(Dispatchers.IO) {
        val safePath = path.trim()
        if (!Regex("^/uploads/[A-Za-z0-9/_-]+\\.(png|jpe?g|webp|gif|avif)$", RegexOption.IGNORE_CASE).matches(safePath)) {
            return@withContext null
        }
        val connection = (URL(URL(baseUrl), safePath).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
        }
        val cookieHeader = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(cookieUrl) }
        if (!cookieHeader.isNullOrBlank()) connection.setRequestProperty("Cookie", cookieHeader)
        try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > maxBytes) return@withContext null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > maxBytes) return@withContext null
                }
                output.toByteArray()
            }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun unregisterPushToken(
        token: String,
        deviceId: String
    ) {
        val safeToken = token.trim()
        if (safeToken.isBlank() && deviceId.isBlank()) return
        request(
            method = "DELETE",
            path = "/api/push/device-token",
            body = JSONObject()
                .put("platform", "android")
                .put("token", safeToken)
                .put("deviceId", deviceId.trim())
        )
    }

    suspend fun startDirectCall(
        peerId: Int,
        type: String = "voice",
        captchaToken: String? = null
    ): AloDirectCallSession {
        val body = JSONObject()
            .put("peerId", peerId)
            .put("type", if (type.equals("video", ignoreCase = true)) "video" else "voice")
            .putCaptchaToken(captchaToken)
        val response = request(
            method = "POST",
            path = "/api/calls/start",
            body = body
        )
        return parseDirectCallSession(response.optJSONObject("call"))
            ?: throw IllegalStateException("Сервер не вернул direct-call session.")
    }

    suspend fun acceptDirectCall(callId: Int) {
        request(
            method = "POST",
            path = "/api/calls/$callId/accept",
            body = JSONObject()
        )
    }

    suspend fun sendDirectCallSignal(
        callId: Int,
        kind: String,
        payload: JSONObject = JSONObject(),
        peerId: Int = 0
    ) {
        val normalizedKind = kind.trim().lowercase()
        val encrypted = peerId > 0 && ENCRYPTED_DIRECT_CALL_SIGNAL_KINDS.contains(normalizedKind)
        // Only the many small signals reuse the device list; the handshake, offer and answer always read a fresh one.
        val mayReuseRecipients = normalizedKind == "ice" || normalizedKind == "media-state"
        suspend fun secure(reuseRecipients: Boolean): JSONObject = if (encrypted) {
            withContext(Dispatchers.Default) {
                buildEncryptedDirectCallSignalPayload(
                    peerId = peerId,
                    payload = payload,
                    reuseRecipients = reuseRecipients
                )
            }
        } else {
            payload
        }
        suspend fun post(body: JSONObject) = request(
            method = "POST",
            path = "/api/calls/$callId/signal",
            body = JSONObject()
                .put("kind", normalizedKind)
                .put("payload", body)
        )
        var signalPayload = secure(reuseRecipients = mayReuseRecipients)
        try {
            post(signalPayload)
        } catch (error: AloApiException) {
            // The other person's devices changed (a new browser, a revoked phone): encrypt once more for the list the
            // server has now.
            if (!encrypted || error.status != 400) throw error
            forgetCallSignalRecipients(peerId)
            signalPayload = secure(reuseRecipients = false)
            post(signalPayload)
        }
        signalPayload.optJSONObject("encrypted")?.let { encrypted ->
            e2eeCrypto?.markRatchetSessionsConfirmed(encrypted)
        }
    }

    suspend fun endDirectCall(callId: Int, reason: String = "") {
        request(
            method = "POST",
            path = "/api/calls/$callId/end",
            body = JSONObject().put("reason", reason.trim().lowercase())
        )
    }

    suspend fun heartbeatDirectCall(callId: Int) {
        if (callId <= 0) return
        request(
            method = "POST",
            path = "/api/calls/$callId/heartbeat",
            body = JSONObject()
        )
    }

    suspend fun pollDirectCallSignals(
        afterId: Int,
        activeCallId: Int? = null
    ): List<AloDirectCallSignal> {
        val activeCallQuery = activeCallId
            ?.takeIf { it > 0 }
            ?.let { "&activeCallId=$it" }
            .orEmpty()
        val response = request(
            method = "GET",
            path = "/api/calls/poll?afterId=${afterId.coerceAtLeast(0)}$activeCallQuery"
        )
        val array = response.optJSONArray("signals")
        if (array == null) return emptyList()
        // Each signal is a libsignal message: opening it reads and writes the session store, which must not stall the UI.
        return withContext(Dispatchers.Default) { parseDirectCallSignals(array) }
    }

    private fun parseDirectCallSignals(array: JSONArray): List<AloDirectCallSignal> {
        val items = mutableListOf<AloDirectCallSignal>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            items += AloDirectCallSignal(
                id = json.optInt("id"),
                callId = json.optInt("callId"),
                fromUserId = json.optInt("fromUserId"),
                toUserId = json.optInt("toUserId"),
                kind = json.optString("kind"),
                callType = json.optString("callType").ifBlank { "voice" },
                callStatus = json.optString("callStatus"),
                callerId = json.optInt("callerId"),
                calleeId = json.optInt("calleeId"),
                fromUser = parseEntitySummary(json.optJSONObject("fromUser")),
                payload = decryptDirectCallSignalPayload(json.optJSONObject("payload") ?: JSONObject()),
                createdAt = json.optString("createdAt")
            )
        }
        return items
    }

    suspend fun startGroupCall(
        roomId: Int,
        type: String = "voice",
        captchaToken: String? = null
    ): AloGroupCallSession {
        val body = JSONObject()
            .put("roomId", roomId)
            .put("type", if (type.equals("video", ignoreCase = true)) "video" else "voice")
            .putCaptchaToken(captchaToken)
        val response = request(
            method = "POST",
            path = "/api/group-calls/start",
            body = body
        )
        return parseGroupCallSession(response)
            ?: throw IllegalStateException("Сервер не вернул group-call session.")
    }

    suspend fun sendGroupCallSignal(
        callId: Int,
        kind: String,
        payload: JSONObject = JSONObject(),
        toUserId: Int = 0
    ) {
        if (toUserId <= 0) return
        val securedPayload = buildEncryptedDirectCallSignalPayload(
            peerId = toUserId,
            payload = payload
        )
        val body = JSONObject()
            .put("kind", kind.trim().lowercase())
            .put("payload", securedPayload)
            .put("toUserId", toUserId)
        request(
            method = "POST",
            path = "/api/group-calls/$callId/signal",
            body = body
        )
    }

    suspend fun loadGroupCallSfuJoin(callId: Int): AloGroupCallSfuJoin {
        val response = request(
            method = "POST",
            path = "/api/group-calls/$callId/join-token",
            body = JSONObject()
        )
        val serverUrl = response.optString("serverUrl")
        val token = response.optString("token")
        if (serverUrl.isBlank() || token.isBlank()) {
            throw IllegalStateException("Сервер не вернул параметры медиакомнаты.")
        }
        return AloGroupCallSfuJoin(
            serverUrl = serverUrl,
            roomName = response.optString("roomName"),
            identity = response.optString("identity"),
            token = token,
            expiresAt = response.optString("expiresAt")
        )
    }

    suspend fun leaveGroupCall(callId: Int) {
        request(
            method = "POST",
            path = "/api/group-calls/$callId/leave",
            body = JSONObject()
        )
    }

    suspend fun heartbeatGroupCall(callId: Int) {
        if (callId <= 0) return
        request(
            method = "POST",
            path = "/api/group-calls/$callId/heartbeat",
            body = JSONObject()
        )
    }

    suspend fun pollGroupCallSignals(afterId: Int): List<AloGroupCallSignal> {
        val response = request(
            method = "GET",
            path = "/api/group-calls/poll?afterId=${afterId.coerceAtLeast(0)}"
        )
        val array = response.optJSONArray("signals")
        if (array == null) return emptyList()
        return withContext(Dispatchers.Default) { parseGroupCallSignals(array) }
    }

    private fun parseGroupCallSignals(array: JSONArray): List<AloGroupCallSignal> {
        val items = mutableListOf<AloGroupCallSignal>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            items += AloGroupCallSignal(
                id = json.optInt("id"),
                callId = json.optInt("callId"),
                roomId = json.optInt("roomId"),
                fromUserId = json.optInt("fromUserId"),
                toUserId = json.optInt("toUserId"),
                kind = json.optString("kind"),
                payload = decryptDirectCallSignalPayload(
                    json.optJSONObject("payload") ?: JSONObject()
                ),
                createdAt = json.optString("createdAt")
            )
        }
        return items
    }

    fun callEventStream(): Flow<AloChatStreamEvent> = flow {
        if (releaseInsecureTransport) {
            throw AloApiException(
                status = -1,
                code = "HTTPS_REQUIRED",
                message = "Release-сборка требует HTTPS адрес сервера.",
                payload = JSONObject()
            )
        }
        val connection = (
            URL(URL(baseUrl), "/api/calls/stream").openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
        }
        val cookieHeader = withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().getCookie(cookieUrl)
        }
        if (!cookieHeader.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
        val completionHandle = currentCoroutineContext().job.invokeOnCompletion {
            connection.disconnect()
        }
        try {
            val status = connection.responseCode
            syncCookies(connection)
            if (status !in 200..299) {
                val body = connection.readBodySafely().toJsonObject()
                throw AloApiException(
                    status = status,
                    code = body.optString("code").ifBlank {
                        if (status == 401) "UNAUTHORIZED" else "CALL_STREAM_FAILED"
                    },
                    message = body.optString("error").ifBlank {
                        if (status == 401) {
                            "Требуется авторизация."
                        } else {
                            "Realtime-соединение звонков недоступно."
                        }
                    },
                    payload = body
                )
            }

            val parser = AloSseEventParser()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    parser.accept(line)?.let { emit(it) }
                }
            }
            throw IOException("Call event stream closed")
        } finally {
            completionHandle.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun createPost(
        body: String,
        captchaToken: String? = null,
        media: List<AloMediaItem> = emptyList(),
        draftId: Int? = null,
        clientOperationId: String? = null,
        expectedOwnerId: Int? = null
    ): AloPost {
        val payload = JSONObject()
            .put("body", body.trim())
            .putCaptchaToken(captchaToken)
        if (media.isNotEmpty()) payload.put("media", JSONArray(media.map(::mediaPayload)))
        if (draftId != null && draftId > 0) payload.put("draftId", draftId)
        clientOperationId?.let { payload.put("clientOperationId", it) }
        expectedOwnerId?.let { payload.put("expectedOwnerId", it) }
        val response = request(
            method = "POST",
            path = "/api/posts",
            body = payload
        )

        return parsePost(response.optJSONObject("post"))?.takeIf { it.id > 0 }
            ?: throw IllegalStateException("Сервер не вернул пост после публикации.")
    }

    /** A media item as the server stores it in posts and drafts (iOS's AloMediaItem.payload). */
    private fun mediaPayload(item: AloMediaItem): JSONObject = JSONObject().apply {
        put("type", item.type)
        put("url", item.url)
        if (item.posterUrl.isNotBlank()) put("poster", item.posterUrl)
        if (item.caption.isNotBlank()) put("caption", item.caption)
        if (item.width > 0 && item.height > 0) { put("width", item.width); put("height", item.height) }
    }

    suspend fun loadPostDrafts(): List<AloPostDraft> =
        jsonObjectList(request(method = "GET", path = "/api/post-drafts").optJSONArray("drafts")).map(::parsePostDraft)

    /** Creates a draft, or replaces the text and media of [draftId]. */
    suspend fun savePostDraft(draftId: Int?, body: String, media: List<AloMediaItem>): AloPostDraft {
        val payload = JSONObject().put("body", body).put("media", JSONArray(media.map(::mediaPayload)))
        val response = if (draftId != null && draftId > 0) {
            request(method = "PATCH", path = "/api/post-drafts/$draftId", body = payload)
        } else {
            request(method = "POST", path = "/api/post-drafts", body = payload)
        }
        return parsePostDraft(response.optJSONObject("draft") ?: JSONObject())
    }

    suspend fun deletePostDraft(draftId: Int) {
        request(method = "DELETE", path = "/api/post-drafts/$draftId")
    }

    private fun parsePostDraft(json: JSONObject) = AloPostDraft(
        id = json.optInt("id"),
        body = json.optString("body"),
        media = parseMedia(json.optJSONArray("media")),
        updatedAt = json.optString("updatedAt").ifBlank { json.optString("createdAt") }
    )

    // ---- Feed curation, reports and Alo's own ads (same endpoints as iOS) ----

    suspend fun hideFeedPost(postId: Int, reason: String) {
        request(method = "POST", path = "/api/feed/posts/$postId/hide", body = JSONObject().put("reason", reason))
    }

    suspend fun unhideFeedPost(postId: Int) {
        request(method = "DELETE", path = "/api/feed/posts/$postId/hide")
    }

    /** targetType: post, comment, user or channel; the reason is kept as text for moderators. */
    suspend fun report(targetType: String, targetId: Int, reason: String, details: String) {
        request(
            method = "POST",
            path = "/api/reports",
            body = JSONObject()
                .put("targetType", targetType)
                .put("targetId", targetId)
                .put("reason", reason)
                .put("details", details)
        )
    }

    suspend fun loadFeedAds(): Pair<List<AloAd>, Int> {
        val response = request(method = "GET", path = "/api/ads/feed")
        val array = response.optJSONArray("ads") ?: JSONArray()
        val ads = buildList { for (index in 0 until array.length()) parseAd(array.optJSONObject(index))?.let(::add) }
        return ads to response.optInt("every", 6).coerceAtLeast(3)
    }

    suspend fun loadChannelAd(roomId: Int): AloAd? =
        parseAd(request(method = "GET", path = "/api/ads/channel?roomId=$roomId").optJSONObject("ad"))

    suspend fun recordAdEvent(adId: Int, kind: String, placement: String) {
        request(method = "POST", path = "/api/ads/$adId/$kind", body = JSONObject().put("placement", placement))
    }

    suspend fun hideAd(adId: Int, report: Boolean, placement: String) {
        request(
            method = "POST",
            path = "/api/ads/$adId/hide",
            body = JSONObject().put("reason", if (report) "report" else "hide").put("placement", placement)
        )
    }

    // ---- Moderation panel, appeals and ad campaigns (same endpoints as iOS) ----

    // Checkmarks (admin/AloVerification.kt).
    suspend fun loadVerificationQueue(status: String): List<AloVerificationRequest> =
        jsonObjectList(request(method = "GET", path = "/api/admin/verification?status=$status&limit=100").optJSONArray("requests"))
            .map(AloVerificationRequest::fromJson)

    suspend fun reviewVerification(id: Int, approve: Boolean, note: String) {
        request(method = "POST", path = "/api/admin/verification/$id/review",
            body = JSONObject().put("status", if (approve) "approved" else "rejected").put("note", note))
    }

    suspend fun setVerified(targetType: String, targetId: Int, verified: Boolean) {
        request(method = "POST", path = "/api/admin/verified",
            body = JSONObject().put("targetType", targetType).put("targetId", targetId).put("verified", verified))
    }

    suspend fun requestVerification(targetType: String, targetId: Int, note: String) {
        request(method = "POST", path = "/api/verification/request",
            body = JSONObject().put("targetType", targetType).put("targetId", targetId).put("note", note))
    }

    suspend fun loadMyVerificationRequests(): List<AloVerificationRequest> =
        jsonObjectList(request(method = "GET", path = "/api/verification/mine").optJSONArray("requests"))
            .map(AloVerificationRequest::fromJson)

    suspend fun loadModerationReports(status: String): List<AloModerationReport> =
        jsonObjectList(request(method = "GET", path = "/api/admin/reports?status=$status").optJSONArray("reports")).map { json ->
            val target = json.optInt("targetUserId")
            val post = json.optJSONObject("targetPost") ?: JSONObject()
            AloModerationReport(
                id = json.optInt("id"),
                reporter = parseEntitySummary(json.optJSONObject("reporter")) ?: unknownEntity(),
                targetType = json.optString("targetType"),
                targetId = json.optInt("targetId"),
                targetUserId = target.takeIf { it > 0 },
                targetPostBody = post.optString("body"),
                targetPostMediaCount = post.optInt("mediaCount"),
                reason = json.optString("reason"),
                details = json.optString("details"),
                status = json.optString("status"),
                resolutionAction = json.optString("resolutionAction"),
                createdAt = json.optString("createdAt"),
                target = json.optJSONObject("target")?.let(::parseReportedContent),
                otherPendingCount = json.optInt("otherPendingCount")
            )
        }

    /** The reported post, comment, person or channel as the server previews it. */
    internal fun parseReportedContent(json: JSONObject): AloReportedContent = AloReportedContent(
        kind = json.optString("kind"),
        id = json.optInt("id"),
        deleted = json.optBoolean("deleted"),
        author = parseEntitySummary(json.optJSONObject("author")),
        body = json.optString("body"),
        media = jsonObjectList(json.optJSONArray("media")).map { item ->
            AloMediaItem(
                type = item.optString("type").ifBlank { "image" },
                url = resolveUrl(item.optString("url")),
                posterUrl = resolveUrl(item.optString("poster")),
                alt = "", caption = "",
                width = item.optInt("width", 0).coerceIn(0, 10_000),
                height = item.optInt("height", 0).coerceIn(0, 10_000)
            )
        },
        createdAt = json.optString("createdAt"),
        postId = json.optInt("postId"),
        likeCount = json.optInt("likeCount"),
        commentCount = json.optInt("commentCount"),
        user = parseEntitySummary(json.optJSONObject("user")),
        bio = json.optString("bio"),
        hierarchy = AloStaffHierarchy.from(json.optString("hierarchy")) ?: AloStaffHierarchy.User,
        accountStatus = json.optString("accountStatus").ifBlank { "active" },
        title = json.optString("title"),
        slug = json.optString("slug"),
        description = json.optString("description"),
        avatar = resolveUrl(json.optString("avatar")),
        subscribersCount = json.optInt("subscribersCount")
    )

    suspend fun resolveModerationReport(id: Int, approve: Boolean, action: AloModerationAction, note: String, blockHours: Int = 24) {
        request(
            method = "POST",
            path = "/api/admin/reports/$id/resolve",
            body = JSONObject()
                .put("status", if (approve) "approved" else "rejected")
                .put("action", if (approve) action.raw else "none")
                .put("note", note)
                .put("blockHours", blockHours)
        )
    }

    // ---- Moderation team (iOS's AloModerationTeamViews) ----

    internal fun parseStaffMember(json: JSONObject): AloStaffMember = AloStaffMember(
        user = parseEntitySummary(json) ?: unknownEntity(),
        permissions = json.optJSONArray("permissions")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty(),
        hierarchy = AloStaffHierarchy.from(json.optString("hierarchy")) ?: AloStaffHierarchy.User,
        isOwner = json.optBoolean("isOwner"),
        accountStatus = json.optString("accountStatus").ifBlank { "active" },
        blockedUntil = json.optString("blockedUntil").takeIf { it != "null" }.orEmpty(),
        blockReason = json.optString("blockReason")
    )

    suspend fun loadModerationTeam(): Pair<List<AloStaffMember>, List<String>> {
        val json = request(method = "GET", path = "/api/admin/moderators")
        val assignable = json.optJSONArray("assignablePermissions")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        return jsonObjectList(json.optJSONArray("moderators")).map(::parseStaffMember) to assignable
    }

    suspend fun searchStaffCandidates(query: String): List<AloStaffMember> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return jsonObjectList(request(method = "GET", path = "/api/admin/users?query=$encoded").optJSONArray("users")).map(::parseStaffMember)
    }

    suspend fun grantModerator(userId: Int, permissions: List<String>) {
        request(method = "POST", path = "/api/admin/moderators",
            body = JSONObject().put("userId", userId).put("permissions", org.json.JSONArray(permissions)))
    }

    suspend fun updateModerator(userId: Int, permissions: List<String>) {
        request(method = "PATCH", path = "/api/admin/moderators/$userId",
            body = JSONObject().put("permissions", org.json.JSONArray(permissions)))
    }

    suspend fun revokeModerator(userId: Int) {
        request(method = "DELETE", path = "/api/admin/moderators/$userId")
    }

    suspend fun setAccountStatus(userId: Int, status: String, hours: Int, reason: String): AloStaffMember {
        val json = request(method = "POST", path = "/api/admin/users/$userId/account",
            body = JSONObject().put("status", status).put("hours", hours).put("reason", reason))
        return parseStaffMember(json.optJSONObject("user") ?: JSONObject())
    }

    suspend fun setAdministrator(userId: Int, granted: Boolean): AloStaffMember {
        val json = if (granted) {
            request(method = "POST", path = "/api/admin/admins", body = JSONObject().put("userId", userId))
        } else {
            request(method = "DELETE", path = "/api/admin/admins/$userId")
        }
        return parseStaffMember(json.optJSONObject("user") ?: JSONObject())
    }

    suspend fun loadModerationAppeals(status: String): List<AloModerationAppeal> =
        jsonObjectList(request(method = "GET", path = "/api/admin/appeals?status=$status").optJSONArray("appeals")).map { json ->
            val report = json.optInt("reportId")
            AloModerationAppeal(
                id = json.optInt("id"),
                kind = json.optString("kind"),
                reportId = report.takeIf { it > 0 },
                text = json.optString("text"),
                status = json.optString("status"),
                decisionNote = json.optString("decisionNote"),
                action = json.optString("action"),
                accountStatus = json.optString("accountStatus"),
                createdAt = json.optString("createdAt"),
                user = parseEntitySummary(json.optJSONObject("user"))
            )
        }

    suspend fun resolveModerationAppeal(id: Int, overturn: Boolean, note: String) {
        request(
            method = "POST",
            path = "/api/admin/appeals/$id/resolve",
            body = JSONObject().put("decision", if (overturn) "overturned" else "upheld").put("note", note)
        )
    }

    suspend fun loadModerationAudit(before: Int?): Pair<List<AloModerationAuditEntry>, Int?> {
        val json = request(method = "GET", path = "/api/admin/audit-log?limit=50" + (before?.let { "&before=$it" } ?: ""))
        val entries = jsonObjectList(json.optJSONArray("entries")).map { entry ->
            AloModerationAuditEntry(
                id = entry.optInt("id"),
                action = entry.optString("action"),
                details = entry.optString("details"),
                createdAt = entry.optString("createdAt"),
                actor = parseEntitySummary(entry.optJSONObject("actor")) ?: unknownEntity(),
                target = parseEntitySummary(entry.optJSONObject("target"))
            )
        }
        return entries to json.optInt("nextBefore").takeIf { it > 0 }
    }

    suspend fun appealModerationDecision(reportId: Int, text: String) {
        request(method = "POST", path = "/api/appeals", body = JSONObject().put("reportId", reportId).put("text", text))
    }

    /** A blocked account cannot sign in, so it appeals with its email and password. */
    suspend fun appealAccountBlock(email: String, password: String, text: String) {
        request(
            method = "POST",
            path = "/api/appeals/account",
            body = JSONObject().put("email", email).put("password", password).put("text", text)
        )
    }

    suspend fun loadAdCampaigns(): List<AloAdCampaign> =
        jsonObjectList(request(method = "GET", path = "/api/admin/ads").optJSONArray("campaigns")).mapNotNull(::parseAdCampaign)

    suspend fun saveAdCampaign(id: Int?, fields: AloAdCampaignFields): AloAdCampaign {
        val body = JSONObject()
            .put("name", fields.name)
            .put("advertiser", fields.advertiser)
            .put("title", fields.title)
            .put("body", fields.body)
            .put("ctaLabel", fields.ctaLabel)
            .put("ctaUrl", fields.ctaUrl)
            .put("placement", fields.placement)
            .put("status", fields.status)
            .put("impressionLimit", fields.impressionLimit)
        val response = if (id != null) {
            request(method = "PATCH", path = "/api/admin/ads/$id", body = body)
        } else {
            request(method = "POST", path = "/api/admin/ads", body = body)
        }
        return parseAdCampaign(response.optJSONObject("campaign") ?: JSONObject())
            ?: throw IllegalStateException("Сервер не вернул кампанию.")
    }

    internal fun parseAdCampaign(json: JSONObject): AloAdCampaign? {
        val ad = parseAd(json) ?: return null
        val stats = json.optJSONObject("stats") ?: JSONObject()
        return AloAdCampaign(
            ad = ad,
            name = json.optString("name"),
            status = json.optString("status"),
            impressionLimit = json.optInt("impressionLimit"),
            impressions = stats.optInt("impressions"),
            reach = stats.optInt("reach"),
            clicks = stats.optInt("clicks"),
            hides = stats.optInt("hides"),
            ctr = stats.optDouble("ctr", 0.0).takeUnless { it.isNaN() } ?: 0.0
        )
    }

    private fun unknownEntity() = AloEntitySummary(id = 0, name = "Пользователь", username = "", avatarUrl = "", verified = false)

    suspend fun toggleCommentLike(postId: Int, commentId: Int): AloPostComment? =
        parseComment(request(method = "POST", path = "/api/posts/$postId/comments/$commentId/like").optJSONObject("comment"))

    suspend fun mentionSuggestions(query: String): List<AloEntitySummary> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val array = request(method = "GET", path = "/api/mentions/suggest?q=$encoded").optJSONArray("people") ?: JSONArray()
        return buildList { for (index in 0 until array.length()) parseEntitySummary(array.optJSONObject(index))?.let(::add) }
    }

    private fun parseAd(json: JSONObject?): AloAd? {
        if (json == null || json.optInt("id") <= 0) return null
        return AloAd(
            id = json.optInt("id"),
            placement = json.optString("placement").ifBlank { "feed" },
            label = json.optString("label").ifBlank { "Реклама" },
            advertiser = json.optString("advertiser"),
            title = json.optString("title"),
            body = json.optString("body"),
            ctaLabel = json.optString("ctaLabel").ifBlank { "Подробнее" },
            ctaUrl = json.optString("ctaUrl"),
            why = json.optString("why")
        )
    }

    suspend fun loadStories(): List<AloStoryGroup> {
        val response = request(method = "GET", path = "/api/stories")
        return parseStoryGroups(response.optJSONArray("stories"))
    }

    suspend fun createStory(
        body: String,
        media: List<AloMediaItem> = emptyList(),
        lifetimeHours: Int = 24,
        /** everyone, friends, close_friends or selected. */
        audience: String = "everyone",
        audienceUserIds: List<Int> = emptyList(),
        clientOperationId: String? = null,
        expectedOwnerId: Int? = null
    ): AloStory {
        val payload = JSONObject()
            .put("audience", audience)
            .put("audienceUserIds", JSONArray(audienceUserIds))
            .put("body", body.trim())
            .put("lifetimeHours", lifetimeHours.coerceIn(1, 48))
            .put(
                "media",
                JSONArray().apply {
                    media.take(10).forEach { item ->
                        put(
                            JSONObject()
                                .put("type", item.type)
                                .put("url", item.url)
                                .put("poster", item.posterUrl)
                                .put("alt", item.alt)
                                .put("caption", item.caption)
                        )
                    }
                }
            )
        clientOperationId?.let { payload.put("clientOperationId", it) }
        expectedOwnerId?.let { payload.put("expectedOwnerId", it) }
        val response = request(method = "POST", path = "/api/stories", body = payload)
        return parseStory(response.optJSONObject("story"))?.takeIf { it.id > 0 }
            ?: throw IllegalStateException("Сервер не вернул историю после публикации.")
    }

    suspend fun markStoryViewed(storyId: Int) {
        request(method = "POST", path = "/api/stories/$storyId/view")
    }

    suspend fun deleteStory(storyId: Int) {
        request(method = "DELETE", path = "/api/stories/$storyId")
    }

    /** The story's share code (one per story); the app builds the link from it. */
    suspend fun createStoryLink(storyId: Int): String {
        val code = request(method = "POST", path = "/api/stories/$storyId/link", body = JSONObject()).optString("code")
        if (!Regex("^[A-Za-z0-9_-]{8,32}$").matches(code)) throw IllegalStateException("Не удалось создать ссылку.")
        return code
    }

    /** A story link: the live story with its author, or the author of one that has expired (410). */
    suspend fun resolveStoryLink(code: String): AloStoryLinkResult {
        if (!Regex("^[A-Za-z0-9_-]{8,32}$").matches(code)) return AloStoryLinkResult.Expired(null)
        return try {
            val json = request(method = "GET", path = "/api/stories/link/$code")
            val user = parseEntitySummary(json.optJSONObject("user"))
            val story = parseStory(json.optJSONObject("story"))
            if (user == null || story == null) AloStoryLinkResult.Expired(user)
            else AloStoryLinkResult.Live(AloStoryGroup(user, listOf(story), hasUnseen = !story.viewerHasSeen))
        } catch (error: AloApiException) {
            if (error.status != 410) throw error
            AloStoryLinkResult.Expired(parseEntitySummary(error.payload.optJSONObject("user")))
        }
    }

    suspend fun loadStoryViewers(storyId: Int): List<AloStoryViewerEntry> =
        jsonObjectList(request(method = "GET", path = "/api/stories/$storyId/viewers").optJSONArray("viewers")).mapNotNull { json ->
            parseEntitySummary(json.optJSONObject("user"))?.let { AloStoryViewerEntry(it, json.optString("viewedAt")) }
        }

    suspend fun loadCreatorStats(days: Int): AloCreatorStats {
        val json = request(method = "GET", path = "/api/creator/stats?days=$days").optJSONObject("stats") ?: JSONObject()
        fun metric(name: String) = json.optJSONObject(name).let { AloCreatorStats.Metric(it?.optInt("current") ?: 0, it?.optInt("previous") ?: 0) }
        val all = json.optJSONObject("allTime") ?: JSONObject()
        return AloCreatorStats(
            days = json.optInt("days", days),
            views = metric("views"), likes = metric("likes"), comments = metric("comments"),
            reposts = metric("reposts"), followers = metric("followers"),
            engagementRate = json.optDouble("engagementRate", 0.0),
            postsInPeriod = json.optInt("postsInPeriod"),
            allTime = AloCreatorStats.AllTime(
                all.optInt("posts"), all.optInt("views"), all.optInt("likes"),
                all.optInt("comments"), all.optInt("reposts"), all.optInt("followers")
            ),
            daily = jsonObjectList(json.optJSONArray("daily")).map {
                AloCreatorStats.Day(it.optString("date"), it.optInt("views"), it.optInt("likes"), it.optInt("comments"))
            },
            topPosts = parsePosts(json.optJSONArray("topPosts"))
        )
    }

    suspend fun loadChannelStats(channelId: Int, days: Int): AloChannelStats {
        val json = request(method = "GET", path = "/api/channels/$channelId/statistics?days=$days")
        val totals = json.optJSONObject("totals") ?: JSONObject()
        return AloChannelStats(
            days = json.optJSONObject("period")?.optInt("days", days) ?: days,
            subscribers = totals.optInt("subscribers"),
            posts = totals.optInt("posts"),
            views = totals.optInt("views"),
            likes = totals.optInt("likes"),
            comments = totals.optInt("comments"),
            reposts = totals.optInt("reposts"),
            growth = jsonObjectList(json.optJSONArray("audienceGrowth")).map { it.optString("day") to it.optInt("joined") },
            publications = jsonObjectList(json.optJSONArray("publications")).map {
                AloChannelStats.Publication(
                    it.optInt("postId"), it.optString("publishedAt"), it.optInt("views"),
                    it.optInt("likes"), it.optInt("comments"), it.optInt("reposts")
                )
            }
        )
    }

    // ---- Video-chat controls (same endpoints as iOS) ----

    suspend fun loadGroupCallControls(callId: Int): AloGroupCallControls {
        val json = request(method = "GET", path = "/api/group-calls/$callId/state").optJSONObject("state") ?: JSONObject()
        fun millis(raw: String) = runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
        val raised = mutableMapOf<Int, Long>()
        val muted = mutableSetOf<Int>()
        jsonObjectList(json.optJSONArray("members")).forEach { member ->
            val userId = member.optInt("userId")
            if (userId <= 0) return@forEach
            millis(member.optString("handRaisedAt"))?.let { raised[userId] = it }
            if (member.optBoolean("mutedByAdmin")) muted += userId
        }
        val recordingJson = json.optJSONObject("recording")
        val recording = recordingJson?.takeIf { it.optBoolean("active") }?.let { r ->
            millis(r.optString("startedAt"))?.let { AloGroupCallRecording(it, r.optInt("byUserId"), r.optString("title")) }
        }
        return AloGroupCallControls(
            canManage = json.optBoolean("canManage"),
            joinMuted = json.optBoolean("joinMuted"),
            raisedHands = raised,
            mutedByAdmin = muted,
            recording = recording
        )
    }

    suspend fun setGroupCallHand(callId: Int, raised: Boolean) {
        request(method = "POST", path = "/api/group-calls/$callId/hand", body = JSONObject().put("raised", raised))
    }

    suspend fun muteGroupCallParticipant(callId: Int, userId: Int, muted: Boolean) {
        request(method = "POST", path = "/api/group-calls/$callId/mute", body = JSONObject().put("userId", userId).put("muted", muted))
    }

    /** Mutes (or lets speak) everyone but admins; returns whom it changed. */
    suspend fun muteGroupCallEveryone(callId: Int, muted: Boolean): Set<Int> {
        val json = request(method = "POST", path = "/api/group-calls/$callId/mute", body = JSONObject().put("all", true).put("muted", muted))
        val ids = json.optJSONArray("userIds") ?: return emptySet()
        return (0 until ids.length()).map { ids.optInt(it) }.filter { it > 0 }.toSet()
    }

    suspend fun setGroupCallRecording(callId: Int, active: Boolean, title: String) {
        request(method = "POST", path = "/api/group-calls/$callId/recording", body = JSONObject().put("active", active).put("title", title))
    }

    suspend fun loadFriendsList(): List<AloEntitySummary> =
        parseEntities(request(method = "GET", path = "/api/friends").optJSONArray("friends"))

    suspend fun loadStorySettings(): AloStorySettings {
        val json = request(method = "GET", path = "/api/stories/settings")
        return AloStorySettings(parseEntities(json.optJSONArray("closeFriends")), parseEntities(json.optJSONArray("hiddenFrom")))
    }

    suspend fun updateCloseFriends(userIds: List<Int>): List<AloEntitySummary> = parseEntities(
        request(method = "PUT", path = "/api/stories/close-friends", body = JSONObject().put("userIds", JSONArray(userIds)))
            .optJSONArray("people")
    )

    suspend fun updateStoryHiddenFrom(userIds: List<Int>): List<AloEntitySummary> = parseEntities(
        request(method = "PUT", path = "/api/stories/hidden-from", body = JSONObject().put("userIds", JSONArray(userIds)))
            .optJSONArray("people")
    )

    /** Your own stories, expired ones too, newest first; `before` pages back. */
    suspend fun loadStoryArchive(before: String = ""): Pair<List<AloStory>, String> {
        val query = if (before.isBlank()) "" else "?before=" + java.net.URLEncoder.encode(before, "UTF-8")
        val json = request(method = "GET", path = "/api/stories/archive$query")
        val stories = jsonObjectList(json.optJSONArray("stories")).mapNotNull(::parseStory)
        return stories to json.optString("nextBefore")
    }

    suspend fun loadHighlights(userId: Int): List<AloStoryHighlight> =
        jsonObjectList(request(method = "GET", path = "/api/users/$userId/highlights").optJSONArray("highlights"))
            .mapNotNull(::parseHighlight)

    suspend fun createHighlight(title: String, storyIds: List<Int>): AloStoryHighlight {
        val json = request(
            method = "POST", path = "/api/stories/highlights",
            body = JSONObject().put("title", title).put("storyIds", JSONArray(storyIds))
        )
        return parseHighlight(json.optJSONObject("highlight")) ?: throw IllegalStateException("Сервер не вернул актуальное.")
    }

    /** Null when the change emptied the highlight and the server removed it. */
    suspend fun updateHighlight(
        id: Int,
        title: String? = null,
        addStoryIds: List<Int> = emptyList(),
        removeStoryIds: List<Int> = emptyList()
    ): AloStoryHighlight? {
        val body = JSONObject().put("addStoryIds", JSONArray(addStoryIds)).put("removeStoryIds", JSONArray(removeStoryIds))
        if (title != null) body.put("title", title)
        return parseHighlight(request(method = "PATCH", path = "/api/stories/highlights/$id", body = body).optJSONObject("highlight"))
    }

    suspend fun deleteHighlight(id: Int) {
        request(method = "DELETE", path = "/api/stories/highlights/$id")
    }

    private fun parseHighlight(json: JSONObject?): AloStoryHighlight? {
        if (json == null || json.optInt("id") <= 0) return null
        return AloStoryHighlight(
            id = json.optInt("id"),
            title = json.optString("title"),
            coverStoryId = json.optInt("coverStoryId"),
            stories = jsonObjectList(json.optJSONArray("stories")).mapNotNull(::parseStory)
        )
    }

    suspend fun loadBusinessProfile(): AloBusinessProfileData {
        val response = request(method = "GET", path = "/api/business/profile")
        return AloBusinessProfileData(
            profile = parseBusinessProfile(response.optJSONObject("profile")),
            quickReplies = parseBusinessQuickReplies(response.optJSONArray("quickReplies")),
            chatLabels = parseBusinessChatLabels(response.optJSONArray("chatLabels"))
        )
    }

    suspend fun updateBusinessProfile(
        enabled: Boolean,
        businessName: String,
        category: String,
        hours: Map<String, String>,
        greetingMessage: String,
        autoReplyEnabled: Boolean,
        autoReplyMessage: String
    ): AloBusinessProfile {
        val response = request(
            method = "PUT",
            path = "/api/business/profile",
            body = JSONObject()
                .put("enabled", enabled)
                .put("businessName", businessName.trim())
                .put("category", category.trim())
                .put("hours", JSONObject(hours))
                .put("greetingMessage", greetingMessage.trim())
                .put("autoReplyEnabled", autoReplyEnabled)
                .put("autoReplyMessage", autoReplyMessage.trim())
        )
        return parseBusinessProfile(response.optJSONObject("profile"))
    }

    suspend fun createBusinessQuickReply(
        shortcut: String,
        title: String,
        body: String,
        sortOrder: Int = 0
    ): AloBusinessQuickReply {
        val response = request(
            method = "POST",
            path = "/api/business/quick-replies",
            body = JSONObject()
                .put("shortcut", shortcut.trim())
                .put("title", title.trim())
                .put("body", body.trim())
                .put("sortOrder", sortOrder.coerceIn(0, 999))
        )
        return parseBusinessQuickReply(response.optJSONObject("quickReply"))
            ?: throw IllegalStateException("Сервер не вернул быстрый ответ.")
    }

    suspend fun deleteBusinessQuickReply(quickReplyId: Int) {
        request(method = "DELETE", path = "/api/business/quick-replies/$quickReplyId")
    }

    suspend fun saveBusinessChatLabel(
        chatKind: String,
        peerId: Int,
        roomId: Int,
        label: String,
        color: String
    ): AloBusinessChatLabel {
        val response = request(
            method = "PUT",
            path = "/api/business/chat-labels",
            body = JSONObject()
                .put("chatKind", chatKind)
                .put("peerId", peerId)
                .put("roomId", roomId)
                .put("label", label.trim())
                .put("color", color)
        )
        return parseBusinessChatLabel(response.optJSONObject("chatLabel"))
            ?: throw IllegalStateException("Сервер не вернул метку чата.")
    }

    suspend fun deleteBusinessChatLabel(chatLabelId: Int) {
        request(method = "DELETE", path = "/api/business/chat-labels/$chatLabelId")
    }

    suspend fun loadPost(postId: Int): AloPost {
        val response = request(method = "GET", path = "/api/posts/$postId")
        return parsePost(response.optJSONObject("post"))
            ?: throw IllegalStateException("Сервер не вернул пост.")
    }

    suspend fun resolvePushOpen(token: String): JSONObject {
        require(token.matches(Regex("[a-f0-9]{64}")))
        return request("GET", "/api/notifications/open/$token").getJSONObject("destination")
    }

    suspend fun resolveAppLink(handle: String): AloResolvedAppLink {
        val normalized = handle.trim().removePrefix("@").lowercase()
        val response = request(
            method = "GET",
            path = "/api/links/resolve/${java.net.URLEncoder.encode(normalized, Charsets.UTF_8.name())}"
        )
        val target = response.optJSONObject("target") ?: JSONObject()
        return AloResolvedAppLink(
            kind = target.optString("kind"),
            id = target.optInt("id"),
            handle = target.optString("handle").ifBlank { normalized }
        )
    }

    suspend fun resolveForumTopicLink(roomId: Int, topicId: Int): AloForumTopicLink {
        require(roomId > 0 && topicId > 0) { "Некорректная ссылка на тему." }
        val response = request(method = "GET", path = "/api/links/topics/$roomId/$topicId")
        val target = response.optJSONObject("target") ?: JSONObject()
        return AloForumTopicLink(
            roomId = target.optInt("roomId"),
            peerId = target.optInt("peerId"),
            topicId = target.optInt("topicId")
        ).also {
            check(it.roomId == roomId && it.peerId > 0 && it.topicId == topicId) {
                "Сервер не вернул доступную тему."
            }
        }
    }

    suspend fun openPostDiscussion(postId: Int): AloPostDiscussion {
        val response = request(method = "POST", path = "/api/posts/$postId/discussion")
        val discussion = response.optJSONObject("discussion") ?: JSONObject()
        return AloPostDiscussion(
            roomId = discussion.optInt("roomId"),
            peerId = discussion.optInt("peerId"),
            topicId = discussion.optInt("topicId")
        ).also {
            check(it.roomId > 0 && it.peerId > 0 && it.topicId > 0) {
                "Сервер не вернул связанную тему обсуждения."
            }
        }
    }

    suspend fun toggleLike(postId: Int, captchaToken: String? = null): AloPost {
        val response = request(
            method = "POST",
            path = "/api/posts/$postId/like",
            body = captchaToken
                ?.takeIf { it.isNotBlank() }
                ?.let { JSONObject().putCaptchaToken(it) }
        )

        return parsePost(response.optJSONObject("post"))
            ?: throw IllegalStateException("Сервер не вернул обновлённый пост.")
    }

    suspend fun toggleRepost(postId: Int, captchaToken: String? = null): AloPost {
        val response = request(
            method = "POST",
            path = "/api/posts/$postId/repost",
            body = captchaToken
                ?.takeIf { it.isNotBlank() }
                ?.let { JSONObject().putCaptchaToken(it) }
        )

        return parsePost(response.optJSONObject("post"))
            ?: throw IllegalStateException("Сервер не вернул обновлённый пост.")
    }

    suspend fun deletePost(postId: Int): Int {
        val response = request(
            method = "DELETE",
            path = "/api/posts/$postId"
        )
        return response.optInt("deletedPostId", postId)
    }

    /** Changes the text of a published post (the server keeps its time and sets `editedAt`). */
    suspend fun editPost(postId: Int, body: String): AloPost? {
        val response = request(method = "PATCH", path = "/api/posts/$postId", body = JSONObject().put("body", body))
        return parsePost(response.optJSONObject("post"))
    }

    private fun postPage(response: JSONObject, key: String = "posts") = AloPage(
        items = parsePosts(response.optJSONArray(key)),
        hasMore = response.optBoolean("hasMore"),
        nextOffset = response.optInt("nextOffset")
    )

    /** People by name or @username (the server keeps out blocked and hidden accounts). */
    suspend fun searchUsers(query: String): List<AloEntitySummary> {
        val response = request("GET", "/api/users/search?limit=20&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        return parseEntities(response.optJSONArray("users"))
    }

    suspend fun loadFeedPage(subscriptions: Boolean, offset: Int): AloPage<AloPost> =
        postPage(request("GET", "/api/feed/posts?mode=${if (subscriptions) "subs" else "popular"}&limit=50&offset=$offset"))

    suspend fun loadUserPostsPage(userId: Int, offset: Int): AloPage<AloPost> =
        postPage(request("GET", "/api/users/$userId/posts?limit=50&offset=$offset"))

    suspend fun loadChannelPostsPage(channelId: Int, offset: Int): AloPage<AloPost> =
        postPage(request("GET", "/api/channels/$channelId/posts?limit=50&offset=$offset"))

    /** Every post the person has liked, newest like first (the «Нравится» tab). */
    suspend fun loadLikedPostsPage(offset: Int): AloPage<AloPost> =
        postPage(request("GET", "/api/feed/liked?limit=50&offset=$offset"))

    suspend fun searchPostsPage(query: String, offset: Int): AloPage<AloPost> =
        postPage(request("GET", "/api/feed/search/posts?limit=20&offset=$offset&q=${java.net.URLEncoder.encode(query, "UTF-8")}"))

    suspend fun searchChannelsPage(query: String, offset: Int): AloPage<AloEntitySummary> {
        val response = request("GET", "/api/feed/search/channels?limit=20&offset=$offset&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        return AloPage(
            items = parseEntities(response.optJSONArray("channels"), forceChannel = true),
            hasMore = response.optBoolean("hasMore"),
            nextOffset = response.optInt("nextOffset")
        )
    }

    suspend fun loadNotificationsPage(offset: Int): AloPage<AloNotificationItem> {
        val response = request("GET", "/api/notifications?limit=50&offset=$offset")
        return AloPage(
            items = parseNotifications(response.optJSONArray("items")),
            hasMore = response.optBoolean("hasMore"),
            nextOffset = response.optInt("nextOffset")
        )
    }

    /** Name, @username and about text; only the fields given are sent, the server leaves the others as they are. */
    suspend fun updateProfileInfo(name: String, username: String, bio: String): AloCurrentUser {
        val response = request(
            method = "PATCH",
            path = "/api/profile",
            body = JSONObject().put("name", name).put("username", username).put("bio", bio)
        )
        return parseCurrentUser(response.optJSONObject("user") ?: JSONObject())
    }

    suspend fun loadComments(postId: Int): List<AloPostComment> {
        val response = request(
            method = "GET",
            path = "/api/posts/$postId/comments"
        )

        return parseComments(response.optJSONArray("comments"))
    }

    suspend fun createComment(
        postId: Int,
        body: String,
        replyToCommentId: Int = 0,
        captchaToken: String? = null
    ): AloCommentSubmitResult {
        val response = request(
            method = "POST",
            path = "/api/posts/$postId/comments",
            body = JSONObject()
                .put("body", body.trim())
                .apply { if (replyToCommentId > 0) put("parentId", replyToCommentId) }
                .putCaptchaToken(captchaToken)
        )

        return AloCommentSubmitResult(
            comment = parseComment(response.optJSONObject("comment")),
            post = parsePost(response.optJSONObject("post"))
        )
    }

    suspend fun updateComment(postId: Int, commentId: Int, body: String): AloCommentSubmitResult {
        val response = request(
            method = "PATCH",
            path = "/api/posts/$postId/comments/$commentId",
            body = JSONObject().put("body", body.trim())
        )

        return AloCommentSubmitResult(
            comment = parseComment(response.optJSONObject("comment")),
            post = parsePost(response.optJSONObject("post"))
        )
    }

    suspend fun deleteComment(postId: Int, commentId: Int): AloCommentDeleteResult {
        val response = request(
            method = "POST",
            path = "/api/posts/$postId/comments/$commentId/delete"
        )

        return AloCommentDeleteResult(
            commentId = response.optInt("commentId", commentId),
            post = parsePost(response.optJSONObject("post"))
        )
    }

    suspend fun updateNotificationSettings(settings: AloNotificationSettings): AloNotificationSettings {
        val response = request(
            method = "PATCH",
            path = "/api/notifications/settings",
            body = JSONObject()
                .put("likesEnabled", settings.likesEnabled)
                .put("commentsEnabled", settings.commentsEnabled)
                .put("repostsEnabled", settings.repostsEnabled)
                .put("followsEnabled", settings.followsEnabled)
                .put("messagesEnabled", settings.messagesEnabled)
                .put("friendRequestsEnabled", settings.friendRequestsEnabled)
                .put("friendAcceptsEnabled", settings.friendAcceptsEnabled)
                .put("channelSubscriptionsEnabled", settings.channelSubscriptionsEnabled)
                .put("commentLikesEnabled", settings.commentLikesEnabled)
        )

        return parseNotificationSettings(response.optJSONObject("settings"))
    }

    suspend fun chatMuteState(conversation: AloConversationDetail): Boolean {
        val chatKind = if (conversation.roomId > 0 || conversation.kind == "group") "group" else "direct"
        val targetId = if (chatKind == "group") conversation.roomId else conversation.peerId
        val response = request(
            method = "GET",
            path = "/api/chat/mute?kind=$chatKind&targetId=$targetId"
        )
        return response.optBoolean("muted")
    }

    suspend fun setChatMuted(
        muted: Boolean,
        conversation: AloConversationDetail
    ): Boolean {
        val chatKind = if (conversation.roomId > 0 || conversation.kind == "group") "group" else "direct"
        val targetId = if (chatKind == "group") conversation.roomId else conversation.peerId
        val response = request(
            method = "PUT",
            path = "/api/chat/mute",
            body = JSONObject()
                .put("kind", chatKind)
                .put("targetId", targetId)
                .put("muted", muted)
        )
        return response.optBoolean("muted")
    }

    /** The account's pinned chats ("direct:<userId>", "group:<roomId>"), top first, the same on every device. */
    suspend fun chatPins(): List<String> = parseChatPins(request(method = "GET", path = "/api/chat/pins"))

    /** The whole new list of pinned chats (pin, unpin or a new order); the server answers with what it saved. */
    suspend fun saveChatPins(keys: List<String>): List<String> = parseChatPins(
        request(method = "PUT", path = "/api/chat/pins", body = JSONObject().put("pinned", JSONArray(keys)))
    )

    /** Once per device: the pins it kept before the server did join the account's list. */
    suspend fun importChatPins(keys: List<String>): List<String> = parseChatPins(
        request(method = "POST", path = "/api/chat/pins/import", body = JSONObject().put("pinned", JSONArray(keys)))
    )

    suspend fun markNotificationsRead(
        notificationIds: List<Int> = emptyList(),
        all: Boolean = false
    ) {
        request(
            method = "PUT",
            path = "/api/notifications/read",
            body = JSONObject()
                .put("all", all)
                .put(
                    "notificationIds",
                    JSONArray(notificationIds.filter { it > 0 }.distinct())
                )
        )
    }

    suspend fun deleteNotification(notificationId: Int) {
        request(
            method = "DELETE",
            path = "/api/notifications/$notificationId"
        )
    }

    suspend fun updatePrivacySettings(settings: AloPrivacySettings): AloPrivacySettings {
        val response = request(
            method = "PATCH",
            path = "/api/privacy/settings",
            body = JSONObject()
                .put("allowFriendRequests", settings.allowFriendRequests)
                .put("messageScope", settings.messageScope)
                .put("allowMessageForwards", settings.allowMessageForwards)
                .put("callScope", settings.callScope)
                .put("profileVisibility", settings.profileVisibility)
                .put("hideActivity", settings.hideActivity)
        )

        return parsePrivacySettings(response.optJSONObject("settings"))
    }

    suspend fun loadSecuritySessions(): List<AloSecuritySession> {
        val response = request(method = "GET", path = "/api/security/sessions")
        val array = response.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val device = json.optJSONObject("device") ?: JSONObject()
                val current = json.optBoolean("current")
                add(
                    AloSecuritySession(
                        id = json.optInt("id"),
                        current = current,
                        canRevoke = current || if (json.has("canRevoke")) {
                            json.optBoolean("canRevoke")
                        } else {
                            true
                        },
                        revokeAvailableAt = json.optString("revokeAvailableAt"),
                        createdAt = json.optString("createdAt"),
                        lastSeenAt = json.optString("lastSeenAt"),
                        expiresAt = json.optString("expiresAt"),
                        ip = json.optString("ip"),
                        suspicious = json.optBoolean("isSuspicious"),
                        deviceLabel = device.optString("label").ifBlank { "Неизвестное устройство" },
                        platform = device.optString("platform"),
                        deviceType = device.optString("deviceType")
                    )
                )
            }
        }
    }

    suspend fun loadSecurityEvents(limit: Int = 12): List<AloSecurityEvent> {
        val safeLimit = limit.coerceIn(1, 50)
        val response = request(method = "GET", path = "/api/security/events?limit=$safeLimit")
        val array = response.optJSONArray("events") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    AloSecurityEvent(
                        id = json.optInt("id"),
                        eventType = json.optString("eventType"),
                        riskLevel = json.optString("riskLevel"),
                        ip = json.optString("ip"),
                        createdAt = json.optString("createdAt")
                    )
                )
            }
        }
    }

    suspend fun revokeOtherSecuritySessions(): Int {
        val response = request(
            method = "POST",
            path = "/api/security/sessions/revoke-others",
            body = JSONObject()
        )
        return response.optInt("revoked")
    }

    suspend fun revokeSecuritySession(sessionId: Int) {
        require(sessionId > 0)
        request(method = "DELETE", path = "/api/security/sessions/$sessionId")
    }

    suspend fun loadPasskeys(): List<AloPasskey> = parseAloPasskeys(request(method = "GET", path = "/api/security/passkeys"))

    suspend fun requestPasskeyRegistrationOptions(): AloPasskeyChallenge {
        val response = request(
            method = "POST",
            path = "/api/security/passkeys/registration/options",
            body = JSONObject()
        )
        return parseAloPasskeyChallenge(response)
    }

    suspend fun verifyPasskeyRegistration(challenge: AloPasskeyChallenge, credentialResponseJson: String) {
        request(
            method = "POST",
            path = "/api/security/passkeys/registration/verify",
            body = aloPasskeyVerifyBody(challenge, credentialResponseJson)
        )
    }

    suspend fun requestPasskeyAuthenticationOptions(): AloPasskeyChallenge {
        val response = request(
            method = "POST",
            path = "/api/auth/passkeys/authentication/options",
            body = JSONObject()
        )
        return parseAloPasskeyChallenge(response)
    }

    suspend fun verifyPasskeyAuthentication(challenge: AloPasskeyChallenge, credentialResponseJson: String) {
        request(
            method = "POST",
            path = "/api/auth/passkeys/authentication/verify",
            body = aloPasskeyVerifyBody(challenge, credentialResponseJson)
        )
    }

    suspend fun startQrLogin(): AloQrLoginSession {
        val response = request(
            method = "POST",
            path = "/api/auth/qr-login/start",
            body = JSONObject()
        )
        return parseQrLoginSession(response)
    }

    suspend fun getQrLoginStatus(id: String, secret: String): AloQrLoginSession {
        require(id.isNotBlank())
        require(secret.isNotBlank())
        val response = request(
            method = "GET",
            path = "/api/auth/qr-login/${id.trim()}?secret=${urlEncode(secret)}"
        )
        return parseQrLoginSession(response, fallbackId = id, fallbackSecret = secret)
    }

    /** Which device asks to sign in with this QR (browser, system, IP); also tells the website it was scanned. */
    suspend fun inspectQrLogin(id: String, secret: String): JSONObject {
        require(id.isNotBlank())
        require(secret.isNotBlank())
        return request(
            method = "POST",
            path = "/api/auth/qr-login/${id.trim()}/inspect",
            body = JSONObject().put("secret", secret)
        ).optJSONObject("device") ?: JSONObject()
    }

    suspend fun approveQrLogin(id: String, secret: String): AloQrLoginSession {
        require(id.isNotBlank())
        require(secret.isNotBlank())
        val response = request(
            method = "POST",
            path = "/api/auth/qr-login/${id.trim()}/approve",
            body = JSONObject().put("secret", secret)
        )
        return parseQrLoginSession(response, fallbackId = id, fallbackSecret = secret)
    }

    suspend fun completeQrLogin(id: String, secret: String): AloQrLoginSession {
        require(id.isNotBlank())
        require(secret.isNotBlank())
        val response = request(
            method = "POST",
            path = "/api/auth/qr-login/${id.trim()}/complete",
            body = JSONObject().put("secret", secret)
        )
        return parseQrLoginSession(response, fallbackId = id, fallbackSecret = secret)
    }

    // ---- Device link: a signed-in device shows the QR, a new device scans it (same endpoints as iOS) ----

    suspend fun startDeviceLink(): AloQrLoginSession =
        parseQrLoginSession(request(method = "POST", path = "/api/auth/device-link/start", body = JSONObject()))

    suspend fun claimDeviceLink(id: String, secret: String) {
        request(method = "POST", path = "/api/auth/device-link/${urlEncode(id.trim())}/claim", body = JSONObject().put("secret", secret))
    }

    suspend fun approveDeviceLink(id: String, secret: String) {
        request(method = "POST", path = "/api/auth/device-link/${urlEncode(id.trim())}/approve", body = JSONObject().put("secret", secret))
    }

    suspend fun denyDeviceLink(id: String, secret: String) {
        request(method = "POST", path = "/api/auth/device-link/${urlEncode(id.trim())}/deny", body = JSONObject().put("secret", secret))
    }

    internal suspend fun requestAccountExport(currentPassword: String): AloAccountExportPayload {
        require(currentPassword.isNotBlank()) { "Введите текущий пароль для экспорта." }
        return requestJsonAttachment(
            method = "POST",
            path = "/api/account/export",
            body = JSONObject().put("currentPassword", currentPassword),
            fallbackFileName = "alo-account-export-${Instant.now().toEpochMilli()}.json"
        )
    }

    suspend fun deleteAccount(currentPassword: String) {
        require(currentPassword.isNotBlank())
        request(
            method = "POST",
            path = "/api/account/delete",
            body = JSONObject()
                .put("currentPassword", currentPassword)
                .put("confirmation", "DELETE")
        )
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Int {
        val response = request(
            method = "POST",
            path = "/api/security/password/change",
            body = JSONObject()
                .put("currentPassword", currentPassword)
                .put("newPassword", newPassword)
        )
        return response.optInt("revokedSessions")
    }

    suspend fun loadConversation(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        /** False for background reads (search index): the server then leaves the messages unread. */
        markRead: Boolean = true
    ): AloConversationDetail {
        val isGroup = kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET
        val resolvedRequestRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
        val response = request(
            method = "GET",
            path = if (isGroup) {
                if (resolvedRequestRoomId > 0) {
                    "/api/group-chats/$resolvedRequestRoomId/messages"
                } else {
                    "/api/messages/$peerId"
                }
            } else {
                "/api/messages/$peerId" + if (markRead) "" else "?markRead=0"
            }
        )
        decryptConversationResponse(response)
        attachCanonicalPollStates(response)

        if (isGroup) {
            val room = response.optJSONObject("room") ?: JSONObject()
            val resolvedRoomId = room.optInt("id").takeIf { it > 0 } ?: resolvedRequestRoomId
            val resolvedPeerId = room.optInt("peerId").takeIf { it > 0 } ?: peerId
            val memberCount = room.optInt("memberCount")
            val groupPolicy = parseGroupPolicy(room.optJSONObject("policy"))
            val isChannel = groupPolicy?.kind.equals("channel", ignoreCase = true)
            val groupMembers = parseGroupMembers(room.optJSONArray("members"))
            if (resolvedRoomId > 0 && groupMembers.isNotEmpty()) {
                groupMentionDirectory[resolvedRoomId] = groupMembers
                    .filter { it.user.username.isNotBlank() && it.user.id > 0 }
                    .associate { it.user.username.lowercase() to it.user.id }
            }

            return AloConversationDetail(
                peerId = resolvedPeerId,
                kind = "group",
                roomId = resolvedRoomId,
                title = room.optString("title").ifBlank { if (isChannel) "Канал" else "Групповой чат" },
                subtitle = if (isChannel) buildChannelSubtitle(memberCount) else buildGroupSubtitle(memberCount),
                username = room.optString("slug").ifBlank { groupPolicy?.username.orEmpty() },
                profileDescription = room.optString("description"),
                lastSeenAt = "",
                avatarUrl = resolveUrl(room.optString("avatarEmoji")),
                verified = false,
                online = false,
                isTyping = response.optBoolean("typing") || response.optBoolean("isTyping"),
                typingAction = response.optString("typingAction"),
                memberCount = memberCount,
                entityType = if (isChannel) "channel" else "group",
                memberRole = room.optString("memberRole").ifBlank { "member" },
                canManage = room.optBoolean("canManage"),
                members = groupMembers,
                items = parseChatMessages(response.optJSONArray("items")),
                groupPolicy = groupPolicy,
                forumTopics = parseForumTopics(room.optJSONArray("topics")),
                forumTopicStates = parseForumTopicStates(room.optJSONArray("topicStates")),
                pinnedMessageIds = parsePinnedMessageIds(room.optJSONArray("pinnedMessages")),
                canonicalPolls = parseCanonicalPollStates(response.optJSONArray("polls"))
            )
        }

        val peer = parseConversationPeer(response.optJSONObject("peer"))
            ?: throw IllegalStateException("Сервер не вернул данные собеседника.")

        return AloConversationDetail(
            peerId = peerId,
            kind = "direct",
            roomId = 0,
            title = peer.name,
            subtitle = buildDirectSubtitle(peer),
            username = peer.username,
            profileDescription = "",
            lastSeenAt = peer.lastSeenAt,
            avatarUrl = peer.avatarUrl,
            verified = peer.verified,
            online = peer.online,
            isTyping = response.optBoolean("typing") || response.optBoolean("isTyping") || peer.isTyping,
            typingAction = response.optString("typingAction"),
            memberCount = 0,
            entityType = peer.entityType,
            memberRole = "",
            canManage = false,
            members = emptyList(),
            items = parseChatMessages(response.optJSONArray("items")),
            pinnedMessageIds = parsePinnedMessageIds(response.optJSONArray("pinnedMessages")),
            canonicalPolls = parseCanonicalPollStates(response.optJSONArray("polls")),
            autoDeleteSeconds = response.optJSONObject("autoDelete")
                ?.optInt("seconds")
                ?.coerceAtLeast(0)
                ?: 0
        )
    }

    suspend fun setDirectChatAutoDeleteSeconds(peerId: Int, seconds: Int): Int {
        require(peerId > 0) { "Некорректный собеседник." }
        val response = request(
            method = "PATCH",
            path = "/api/direct-chats/$peerId/settings",
            body = JSONObject().put("autoDeleteSeconds", seconds.coerceAtLeast(0))
        )
        return response.optInt("autoDeleteSeconds").coerceAtLeast(0)
    }

    suspend fun registerPoll(
        definition: AloPollDefinition,
        messageId: Int,
        kind: String,
        roomId: Int
    ): AloCanonicalPollState {
        val payload = pollRegistrationJson(definition)
            .put("chatKind", if (kind == "group") "group" else "direct")
            .put("roomId", roomId.coerceAtLeast(0))
            .put("messageId", messageId)
        val response = request(
            method = "POST",
            path = "/api/polls",
            body = payload
        )
        return parseCanonicalPollState(response.optJSONObject("poll") ?: JSONObject())
    }

    private fun pollRegistrationJson(definition: AloPollDefinition): JSONObject {
        val optionIds = JSONArray().also { array -> definition.options.forEach { array.put(it.id) } }
        return JSONObject()
            .put("pollId", definition.id)
            .put("optionIds", optionIds)
            .put("allowsMultipleAnswers", definition.allowsMultipleAnswers)
            .put("isQuiz", definition.isQuiz)
            .put("isAnonymous", definition.isAnonymous)
            .put("allowsRevoting", definition.allowsRevoting)
            .put("quizAnswerCommitment", definition.quizAnswerCommitment)
            .also { payload -> if (definition.closesAt.isNotBlank()) payload.put("closesAt", definition.closesAt) }
    }

    suspend fun votePoll(pollId: String, optionIds: Set<String>): AloCanonicalPollState {
        val values = JSONArray().also { array -> optionIds.sorted().forEach(array::put) }
        val response = request(
            method = "PUT",
            path = "/api/polls/$pollId/vote",
            body = JSONObject().put("optionIds", values)
        )
        return parseCanonicalPollState(response.optJSONObject("poll") ?: JSONObject())
    }

    suspend fun closePoll(pollId: String): AloCanonicalPollState {
        val response = request(
            method = "POST",
            path = "/api/polls/$pollId/close",
            body = JSONObject()
        )
        return parseCanonicalPollState(response.optJSONObject("poll") ?: JSONObject())
    }

    private suspend fun attachCanonicalPollStates(conversation: JSONObject) {
        val ids = buildSet {
            val items = conversation.optJSONArray("items") ?: JSONArray()
            repeat(items.length()) { index ->
                val body = items.optJSONObject(index)?.optString("body").orEmpty()
                val definition = (AloPollCodec.decode(body) as? AloPollEvent.Create)?.definition
                if (definition != null) add(definition.id)
            }
        }
        if (ids.isEmpty()) return
        runCatching {
            request(method = "GET", path = "/api/polls?ids=${ids.sorted().joinToString(",")}")
        }.getOrNull()?.optJSONArray("polls")?.let { conversation.put("polls", it) }
    }

    private fun parseCanonicalPollStates(array: JSONArray?): Map<String, AloCanonicalPollState> = buildMap {
        val values = array ?: return@buildMap
        repeat(values.length()) { index ->
            val state = parseCanonicalPollState(values.optJSONObject(index) ?: JSONObject())
            if (state.id.isNotBlank()) put(state.id, state)
        }
    }

    private fun parseCanonicalPollState(json: JSONObject): AloCanonicalPollState {
        val countsJson = json.optJSONObject("voteCounts") ?: JSONObject()
        val counts = buildMap {
            val keys = countsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, countsJson.optInt(key).coerceAtLeast(0))
            }
        }
        fun stringSet(name: String): Set<String> = buildSet {
            val values = json.optJSONArray(name) ?: JSONArray()
            repeat(values.length()) { index ->
                values.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        val publicVoters = buildList {
            val values = json.optJSONArray("publicVoters") ?: JSONArray()
            repeat(values.length()) { index ->
                val voter = values.optJSONObject(index) ?: return@repeat
                val selectedIds = buildSet {
                    val ids = voter.optJSONArray("optionIds") ?: JSONArray()
                    repeat(ids.length()) { optionIndex ->
                        ids.optString(optionIndex).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                add(
                    AloPollPublicVoter(
                        userId = voter.optInt("userId"),
                        name = voter.optString("name").ifBlank { "Пользователь" },
                        username = voter.optString("username"),
                        avatarUrl = voter.optString("avatarUrl"),
                        optionIds = selectedIds
                    )
                )
            }
        }
        return AloCanonicalPollState(
            id = json.optString("id"),
            messageId = json.optInt("messageId"),
            voteCounts = counts,
            selectedOptionIds = stringSet("selectedOptionIds"),
            totalVoters = json.optInt("totalVoters").coerceAtLeast(0),
            isClosed = json.optBoolean("isClosed"),
            allowsRevoting = json.optBoolean("allowsRevoting", true),
            correctOptionIds = stringSet("correctOptionIds"),
            publicVoters = publicVoters,
            closesAt = json.optString("closesAt"),
            closedAt = json.optString("closedAt")
        )
    }

    suspend fun loadConversationHistoryPage(
        peerId: Int,
        beforeId: Int,
        limit: Int = 200
    ): AloConversationHistoryPage {
        require(peerId > 0 && beforeId > 0)
        val safeLimit = limit.coerceIn(1, 500)
        val response = request(
            method = "GET",
            path = "/api/messages/$peerId/history?beforeId=$beforeId&limit=$safeLimit"
        )
        decryptConversationResponse(response)
        val page = response.optJSONObject("page") ?: JSONObject()
        return AloConversationHistoryPage(
            items = parseChatMessages(response.optJSONArray("items")),
            hasMore = page.optBoolean("hasMore"),
            nextBeforeId = page.optInt("nextBeforeId").coerceAtLeast(0)
        )
    }

    private fun parsePinnedMessageIds(array: JSONArray?): List<Int> = buildList {
        val source = array ?: return@buildList
        for (index in 0 until source.length()) {
            val id = source.optJSONObject(index)?.optInt("id") ?: 0
            if (id > 0 && id !in this) add(id)
        }
    }

    suspend fun loadConversationSummaries(): List<AloConversationSummary> {
        val response = request(method = "GET", path = "/api/chat/conversations")
        val wrapper = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "messages",
                    JSONObject()
                        .put("conversations", response.optJSONArray("conversations") ?: JSONArray())
                        .put("items", JSONArray())
                )
            )
        decryptBootstrapResponse(wrapper)
        return parseConversations(
            wrapper
                .optJSONObject("data")
                ?.optJSONObject("messages")
                ?.optJSONArray("conversations")
        )
    }

    fun updatesEventStream(afterCursor: Long): Flow<AloChatStreamEvent> = flow {
        if (releaseInsecureTransport) {
            throw AloApiException(
                status = -1,
                code = "HTTPS_REQUIRED",
                message = "Release-сборка требует HTTPS адрес сервера.",
                payload = JSONObject()
            )
        }
        val cursor = afterCursor.coerceAtLeast(0)
        val route = if (cursor > 0) {
            "/api/updates/stream?cursor=$cursor"
        } else {
            "/api/updates/stream"
        }
        val connection = (URL(URL(baseUrl), route).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
            if (cursor > 0) {
                setRequestProperty("Last-Event-ID", cursor.toString())
            }
        }
        val cookieHeader = withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().getCookie(cookieUrl)
        }
        if (!cookieHeader.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
        val completionHandle = currentCoroutineContext().job.invokeOnCompletion {
            connection.disconnect()
        }
        try {
            val status = connection.responseCode
            syncCookies(connection)
            if (status !in 200..299) {
                val body = connection.readBodySafely().toJsonObject()
                throw AloApiException(
                    status = status,
                    code = body.optString("code").ifBlank {
                        if (status == 401) "UNAUTHORIZED" else "UPDATES_STREAM_FAILED"
                    },
                    message = body.optString("error").ifBlank {
                        if (status == 401) "Требуется авторизация." else "Realtime-соединение недоступно."
                    },
                    payload = body
                )
            }

            val parser = AloSseEventParser()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    parser.accept(line)?.let { emit(it) }
                }
            }
            throw IOException("Updates event stream closed")
        } finally {
            completionHandle.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun acknowledgeUpdates(cursor: Long, deviceId: String) {
        request(
            method = "PUT",
            path = "/api/updates/cursor",
            body = JSONObject()
                .put("cursor", cursor.coerceAtLeast(0))
                .put("deviceId", deviceId.trim())
        )
    }

    suspend fun updateChatPresence(activePeerId: Int) {
        request(
            method = "POST",
            path = "/api/chat/presence",
            body = JSONObject().put("activePeerId", activePeerId.coerceAtLeast(0))
        )
    }

    /** [action]: "typing", "voice" (recording a voice message) or "circle" (recording a video message). */
    suspend fun sendTyping(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        action: String = "typing"
    ) {
        request(
            method = "POST",
            path = "/api/chat/typing",
            body = JSONObject()
                .put("kind", kind)
                .put("peerId", peerId)
                .put("roomId", roomId)
                .put("action", action)
        )
    }

    suspend fun loadBlockedUserIds(): Set<Int> {
        val response = request(
            method = "GET",
            path = "/api/users/blocks"
        )
        return parseBlockedUserIds(response)
    }

    suspend fun blockUser(userId: Int) {
        require(userId > 0)
        request(
            method = "POST",
            path = "/api/users/$userId/block",
            body = JSONObject()
        )
    }

    suspend fun unblockUser(userId: Int) {
        require(userId > 0)
        request(
            method = "DELETE",
            path = "/api/users/$userId/block"
        )
    }

    suspend fun loadUserProfile(userId: Int): AloViewedProfile {
        val response = request(
            method = "GET",
            path = "/api/users/$userId/profile"
        )

        val profile = response.optJSONObject("profile") ?: JSONObject()
        val user = profile.optJSONObject("user") ?: JSONObject()
        val stats = parseProfileStats(profile.optJSONObject("stats"))
        val relations = parseProfileRelations(profile.optJSONObject("relations"))
        val relation = profile.optJSONObject("relation") ?: JSONObject()
        val permissions = profile.optJSONObject("permissions") ?: JSONObject()
        val online = user.optBoolean("online")
        val lastSeenAt = user.optString("lastSeenAt")
        val status = when {
            online -> "в сети"
            lastSeenAt.isNotBlank() -> lastSeenAt
            else -> "Профиль Alo"
        }

        return AloViewedProfile(
            kind = "user",
            id = user.optInt("id", userId),
            title = user.optString("name").ifBlank { "Пользователь" },
            username = user.optString("username"),
            avatarUrl = resolveUrl(user.optString("avatarEmoji")),
            avatarGallery = parseUrlList(user.optJSONArray("avatarGallery"), user.optString("avatarEmoji")),
            verified = user.optBoolean("verified"),
            coverStyle = user.optString("coverStyle").ifBlank { "graphite" },
            coverImageUrl = resolveUrl(user.optString("coverImageUrl")),
            status = status,
            meta = user.optString("registeredLabel"),
            description = user.optString("bio"),
            metrics = listOf(
                AloProfileMetric("Подписки", stats.followingCount.toString()),
                AloProfileMetric("Подписчики", stats.followersCount.toString()),
                AloProfileMetric("Друзья", relations.friendsCount.toString()),
                AloProfileMetric("Каналы", relations.channelsCount.toString())
            ),
            posts = parsePosts(profile.optJSONArray("posts")),
            friendStatus = relation.optString("friendStatus").ifBlank { "none" },
            incomingFriendRequestId = relation.optInt("incomingRequestId"),
            outgoingFriendRequestId = relation.optInt("outgoingRequestId"),
            canSendFriendRequest = permissions.optBoolean("canSendFriendRequest", false),
            following = relation.optBoolean("following"),
            followedBy = relation.optBoolean("followedBy") || relation.optBoolean("followsYou"),
            canMessage = permissions.optBoolean("canMessage", true),
            isPrivate = permissions.optBoolean("isPrivate", false),
            canViewContent = permissions.optBoolean("canViewContent", true)
        )
    }

    suspend fun loadChannelProfile(channelId: Int): AloViewedProfile {
        val response = request(
            method = "GET",
            path = "/api/channels/$channelId/profile"
        )

        val profile = response.optJSONObject("profile") ?: JSONObject()
        val channel = profile.optJSONObject("channel") ?: JSONObject()
        val stats = profile.optJSONObject("stats") ?: JSONObject()
        val moderation = profile.optJSONObject("moderation") ?: JSONObject()
        val community = profile.optJSONObject("community") ?: JSONObject()
        val slug = channel.optString("slug")

        val viewedProfile = AloViewedProfile(
            kind = "channel",
            id = channel.optInt("id", channelId),
            title = channel.optString("name").ifBlank { "Канал" },
            username = channel.optString("username").ifBlank { slug },
            avatarUrl = resolveUrl(channel.optString("avatarEmoji")),
            avatarGallery = parseUrlList(channel.optJSONArray("avatarGallery"), channel.optString("avatarEmoji")),
            verified = channel.optBoolean("verified"),
            coverStyle = "graphite",
            coverImageUrl = resolveUrl(channel.optString("coverImageUrl")),
            status = "Канал Alo",
            meta = if (slug.isNotBlank()) "@$slug" else "Паблик",
            description = channel.optString("description").ifBlank {
                community.optString("aboutText")
            },
            metrics = listOf(
                AloProfileMetric("Подписчики", stats.optInt("subscribersCount").toString()),
                AloProfileMetric("Посты", stats.optInt("postsCount").toString()),
                AloProfileMetric("Контакты", (community.optJSONArray("contacts")?.length() ?: 0).toString()),
                AloProfileMetric("Модераторы", (moderation.optJSONArray("moderators")?.length() ?: 0).toString())
            ),
            posts = parsePosts(profile.optJSONArray("posts")),
            canManage = channel.optBoolean("canManage"),
            isOwner = channel.optBoolean("isOwner"),
            commentsEnabled = if (channel.has("commentsEnabled")) {
                channel.optBoolean("commentsEnabled")
            } else {
                true
            },
            signMessages = channel.optBoolean("signMessages"),
            showAuthorProfiles = channel.optBoolean("showAuthorProfiles"),
            discussionRoomId = channel.optInt("discussionRoomId")
        )
        if (!viewedProfile.canManage) return viewedProfile
        val events = runCatching {
            loadChannelAdminLog(channelId)
        }.getOrDefault(emptyList())
        return viewedProfile.copy(adminEvents = events)
    }

    suspend fun updateChannelSettings(
        channelId: Int,
        commentsEnabled: Boolean,
        signMessages: Boolean,
        showAuthorProfiles: Boolean
    ): AloViewedProfile {
        request(
            method = "PATCH",
            path = "/api/channels/$channelId",
            body = JSONObject()
                .put("commentsEnabled", commentsEnabled)
                .put("signMessages", signMessages)
                .put("showAuthorProfiles", showAuthorProfiles)
        )
        return loadChannelProfile(channelId)
    }

    suspend fun loadChannelAdminLog(
        channelId: Int,
        action: String? = null,
        actorUserId: Int? = null,
        beforeId: Int? = null,
        limit: Int = 100
    ): List<AloChannelAdminEvent> {
        val query = buildList {
            add("limit=${limit.coerceIn(1, 200)}")
            action
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add("action=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}") }
            actorUserId?.takeIf { it > 0 }?.let { add("actorUserId=$it") }
            beforeId?.takeIf { it > 0 }?.let { add("beforeId=$it") }
        }.joinToString("&")
        val response = request(
            method = "GET",
            path = "/api/channels/$channelId/admin-log?$query"
        )
        val events = response.optJSONArray("events") ?: JSONArray()
        return buildList {
            for (index in 0 until events.length()) {
                val item = events.optJSONObject(index) ?: continue
                val actor = parseEntitySummary(item.optJSONObject("actor"))
                    ?: continue
                add(
                    AloChannelAdminEvent(
                        id = item.optInt("id"),
                        action = item.optString("action"),
                        postId = item.optInt("postId")
                            .takeIf { it > 0 },
                        createdAt = item.optString("createdAt"),
                        actor = actor,
                        target = parseEntitySummary(
                            item.optJSONObject("target")
                        )
                    )
                )
            }
        }
    }

    suspend fun loadGroupProfile(roomId: Int): AloViewedProfile {
        val response = request(
            method = "GET",
            path = "/api/group-chats/$roomId/profile"
        )

        val room = response.optJSONObject("room") ?: JSONObject()
        val members = room.optJSONArray("members")
        val memberRole = room.optString("memberRole")
        val status = when (memberRole.lowercase()) {
            "owner" -> "Владелец группы"
            "admin" -> "Администратор группы"
            else -> "Групповой чат"
        }

        return AloViewedProfile(
            kind = "group",
            id = room.optInt("id", roomId),
            title = room.optString("title").ifBlank { "Групповой чат" },
            username = "",
            avatarUrl = resolveUrl(room.optString("avatarEmoji")),
            avatarGallery = parseUrlList(room.optJSONArray("avatarGallery"), room.optString("avatarEmoji")),
            verified = false,
            coverStyle = "graphite",
            coverImageUrl = "",
            status = status,
            meta = "",
            description = room.optString("description"),
            metrics = listOf(
                AloProfileMetric("Участники", (members?.length() ?: 0).toString()),
                AloProfileMetric("Роль", when (memberRole.lowercase()) {
                    "owner" -> "owner"
                    "admin" -> "admin"
                    else -> "member"
                })
            ),
            posts = emptyList()
        )
    }

    suspend fun updateGroupChatProfile(
        roomId: Int,
        title: String,
        avatarUrl: String? = null
    ) {
        val body = JSONObject().put("title", title.trim())
        if (avatarUrl != null) {
            body.put("avatarUrl", avatarUrl)
            body.put("avatarGallery", if (avatarUrl.isBlank()) JSONArray() else JSONArray().put(avatarUrl))
        }
        request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/profile",
            body = body
        )
    }

    suspend fun createGroupChat(
        title: String,
        description: String,
        kind: String,
        joinPolicy: String,
        username: String
    ): Pair<Int, Int> {
        val response = request(
            method = "POST",
            path = "/api/group-chats",
            body = JSONObject()
                .put("title", title.trim())
                .put("description", description.trim())
                .put("kind", if (kind == "channel") "channel" else "group")
                .put(
                    "joinPolicy",
                    joinPolicy.takeIf { it in setOf("invite", "request", "public") } ?: "invite"
                )
                .put(
                    "username",
                    username.trim().removePrefix("@").lowercase()
                )
        )
        val room = response.optJSONObject("room") ?: JSONObject()
        val roomId = room.optInt("id")
        val peerId = room.optInt("peerId")
        check(roomId > 0 && peerId > 0) {
            "Сервер не вернул созданное сообщество."
        }
        return roomId to peerId
    }

    suspend fun loadGroupManagement(roomId: Int): AloGroupManagementSnapshot {
        val profile = request(
            method = "GET",
            path = "/api/group-chats/$roomId/profile"
        )
        val room = profile.optJSONObject("room") ?: JSONObject()
        val requestsResponse = runCatching {
            request(method = "GET", path = "/api/group-chats/$roomId/join-requests")
        }.getOrNull()
        val invitesResponse = runCatching {
            request(method = "GET", path = "/api/group-chats/$roomId/invite-links")
        }.getOrNull()
        val eventsPage = runCatching { loadGroupAdminLog(roomId) }.getOrNull()
        val topicsResponse = runCatching {
            request(method = "GET", path = "/api/group-chats/$roomId/topics")
        }.getOrNull()
        val bansResponse = runCatching {
            request(method = "GET", path = "/api/group-chats/$roomId/bans")
        }.getOrNull()
        val policyJson = room.optJSONObject("policy") ?: JSONObject()
        return AloGroupManagementSnapshot(
            policy = AloGroupPolicy(
                kind = policyJson.optString("kind", "group"),
                username = policyJson.optString("username"),
                joinPolicy = policyJson.optString("joinPolicy", "invite"),
                isForum = policyJson.optBoolean("isForum"),
                historyVisible = if (policyJson.has("historyVisible")) {
                    policyJson.optBoolean("historyVisible")
                } else {
                    true
                },
                slowModeSeconds = policyJson.optInt("slowModeSeconds").coerceAtLeast(0),
                defaultPermissions = parseBooleanMap(policyJson.optJSONObject("defaultPermissions")),
                memberAdminRights = parseBooleanMap(policyJson.optJSONObject("memberAdminRights")),
                memberRestrictions = parseBooleanMap(policyJson.optJSONObject("memberRestrictions")),
                memberRestrictedUntil = policyJson.optString("memberRestrictedUntil"),
                protectedContent = policyJson.optBoolean("protectedContent")
            ).normalizedCommunityPolicy(),
            members = parseGroupMembers(room.optJSONArray("members")),
            requests = parseGroupJoinRequests(requestsResponse?.optJSONArray("requests")),
            invites = parseGroupInviteLinks(invitesResponse?.optJSONArray("invites")),
            events = eventsPage?.events.orEmpty(),
            topics = parseForumTopics(topicsResponse?.optJSONArray("topics")),
            bans = parseGroupBans(bansResponse?.optJSONArray("bans")),
            nextAdminLogBeforeId = eventsPage?.nextBeforeId
        )
    }

    suspend fun loadGroupAdminLog(
        roomId: Int,
        beforeId: Int? = null,
        action: String? = null,
        actorUserId: Int? = null,
        limit: Int = 100
    ): AloGroupAdminLogPage {
        val query = buildList {
            add("limit=${limit.coerceIn(1, 200)}")
            beforeId?.takeIf { it > 0 }?.let { add("beforeId=$it") }
            action?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("action=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
            actorUserId?.takeIf { it > 0 }?.let { add("actorUserId=$it") }
        }.joinToString("&")
        val response = request(
            method = "GET",
            path = "/api/group-chats/$roomId/admin-log?$query"
        )
        val nextBeforeId = response.optInt("nextBeforeId").takeIf { it > 0 }
        return AloGroupAdminLogPage(
            events = parseGroupAdminEvents(response.optJSONArray("events")),
            nextBeforeId = nextBeforeId
        )
    }

    // Joins and leaves of a group, drawn as lines among its messages (messages/AloGroupServiceLines.kt).
    suspend fun loadGroupServiceEvents(roomId: Int): List<AloGroupServiceEvent> {
        val response = request(method = "GET", path = "/api/group-chats/$roomId/service-events")
        val events = response.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { index -> events.optJSONObject(index)?.let(AloGroupServiceEvent::fromJson) }
    }

    suspend fun createGroupInviteLink(
        roomId: Int,
        label: String,
        requiresApproval: Boolean,
        usageLimit: Int,
        expiresAt: String = ""
    ): Pair<AloGroupInviteLink, String> {
        val response = request(
            method = "POST",
            path = "/api/group-chats/$roomId/invite-links",
            body = JSONObject()
                .put("label", label.trim())
                .put("requiresApproval", requiresApproval)
                .put("usageLimit", usageLimit.coerceAtLeast(0))
                .put("expiresAt", expiresAt.takeIf(String::isNotBlank) ?: JSONObject.NULL)
        )
        val json = response.optJSONObject("invite") ?: JSONObject()
        val invite = parseGroupInviteLink(json)
            ?: throw IllegalStateException("Сервер не вернул созданную ссылку.")
        return invite to json.optString("token")
    }

    suspend fun updateGroupInviteLink(
        roomId: Int,
        inviteId: Int,
        label: String,
        requiresApproval: Boolean,
        usageLimit: Int,
        expiresAt: String
    ): AloGroupInviteLink {
        val response = request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/invite-links/$inviteId",
            body = JSONObject()
                .put("label", label.trim())
                .put("requiresApproval", requiresApproval)
                .put("usageLimit", usageLimit.coerceAtLeast(0))
                .put("expiresAt", expiresAt.takeIf(String::isNotBlank) ?: JSONObject.NULL)
        )
        return parseGroupInviteLink(response.optJSONObject("invite"))
            ?: throw IllegalStateException("Сервер не вернул изменённую ссылку.")
    }

    suspend fun revokeGroupInviteLink(roomId: Int, inviteId: Int) {
        request(
            method = "DELETE",
            path = "/api/group-chats/$roomId/invite-links/$inviteId"
        )
    }

    suspend fun loadGroupInviteStats(roomId: Int, inviteId: Int): AloGroupInviteStats {
        val response = request(
            method = "GET",
            path = "/api/group-chats/$roomId/invite-links/$inviteId/stats"
        )
        val invite = parseGroupInviteLink(response.optJSONObject("invite"))
            ?: throw IllegalStateException("Сервер не вернул ссылку приглашения.")
        return AloGroupInviteStats(
            invite = invite,
            joined = parseGroupInviteParticipants(response.optJSONArray("joined")),
            pending = parseGroupInviteParticipants(response.optJSONArray("pending"))
        )
    }

    suspend fun joinGroupInvite(token: String): Pair<Int, Boolean> {
        val raw = token.trim()
        val normalized = runCatching {
            java.net.URI(raw).path.substringAfterLast('/').ifBlank { raw }
        }.getOrDefault(raw.substringAfterLast('/'))
        val response = request(
            method = "POST",
            path = "/api/group-invites/$normalized/join",
            body = JSONObject()
        )
        return response.optInt("peerId") to response.optBoolean("pendingApproval")
    }

    suspend fun joinPublicGroup(username: String): Pair<Int, Boolean> {
        val normalized = username.trim().removePrefix("@").lowercase()
        val response = request(
            method = "POST",
            path = "/api/group-public/$normalized/join",
            body = JSONObject()
        )
        return response.optInt("peerId") to response.optBoolean("pendingApproval")
    }

    suspend fun createGroupTopic(
        roomId: Int,
        title: String,
        emoji: String = ""
    ): AloForumTopic {
        val response = request(
            method = "POST",
            path = "/api/group-chats/$roomId/topics",
            body = JSONObject()
                .put("title", title.trim())
                .put("iconEmoji", emoji.trim())
        )
        return parseForumTopic(response.optJSONObject("topic"))
            ?: throw IllegalStateException("Сервер не вернул созданную тему.")
    }

    suspend fun updateGroupTopic(
        roomId: Int,
        topicId: Int,
        title: String? = null,
        emoji: String? = null,
        isClosed: Boolean? = null,
        isHidden: Boolean? = null
    ): AloForumTopic {
        val body = JSONObject()
        title?.let { body.put("title", it.trim()) }
        emoji?.let { body.put("iconEmoji", it.trim()) }
        isClosed?.let { body.put("isClosed", it) }
        isHidden?.let { body.put("isHidden", it) }
        val response = request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/topics/$topicId",
            body = body
        )
        return parseForumTopic(response.optJSONObject("topic"))
            ?: throw IllegalStateException("Сервер не вернул обновлённую тему.")
    }

    suspend fun deleteGroupTopic(roomId: Int, topicId: Int) {
        request(
            method = "DELETE",
            path = "/api/group-chats/$roomId/topics/$topicId"
        )
    }

    suspend fun mergeGroupTopic(
        roomId: Int,
        sourceTopicId: Int,
        targetTopicId: Int
    ): AloForumTopic {
        val response = request(
            method = "POST",
            path = "/api/group-chats/$roomId/topics/$sourceTopicId/merge",
            body = JSONObject().put("targetTopicId", targetTopicId)
        )
        return parseForumTopic(response.optJSONObject("targetTopic"))
            ?: throw IllegalStateException("Сервер не вернул объединённую тему.")
    }

    suspend fun loadGroupTopicStates(roomId: Int): List<AloForumTopicState> {
        val response = request(
            method = "GET",
            path = "/api/group-chats/$roomId/topics/state"
        )
        decryptForumTopicStates(response.optJSONArray("states"))
        return parseForumTopicStates(response.optJSONArray("states"))
    }

    suspend fun updateGroupTopicState(
        roomId: Int,
        topicId: Int,
        readThroughMessageId: Int? = null,
        muted: Boolean? = null,
        draftText: String? = null,
        updateDraft: Boolean = false,
        baseDraftRevision: Int = 0,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ): AloForumTopicState {
        val body = JSONObject().put("operationId", operationId)
        readThroughMessageId?.let { body.put("readThroughMessageId", it.coerceAtLeast(0)) }
        muted?.let { body.put("muted", it) }
        if (updateDraft) {
            body.put("baseDraftRevision", baseDraftRevision.coerceAtLeast(0))
            val normalized = draftText.orEmpty()
            body.put(
                "encryptedDraft",
                if (normalized.isBlank()) JSONObject.NULL else prepareEncryptedTopicDraft(normalized)
            )
        }
        val response = request(
            method = "PUT",
            path = "/api/group-chats/$roomId/topics/$topicId/state",
            body = body
        )
        val stateJson = response.optJSONObject("state")
            ?: throw IllegalStateException("Сервер не вернул состояние темы.")
        decryptForumTopicState(stateJson)
        return parseForumTopicState(stateJson)
            ?: throw IllegalStateException("Сервер вернул некорректное состояние темы.")
    }

    suspend fun updateGroupSettings(roomId: Int, policy: AloGroupPolicy): AloGroupPolicy {
        val normalizedPolicy = policy.normalizedCommunityPolicy()
        val response = request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/settings",
            body = JSONObject()
                .put("username", normalizedPolicy.username)
                .put("joinPolicy", normalizedPolicy.joinPolicy)
                .put("isForum", normalizedPolicy.isForum)
                .put("historyVisible", normalizedPolicy.historyVisible)
                .put("slowModeSeconds", normalizedPolicy.slowModeSeconds)
                .put("autoDeleteSeconds", normalizedPolicy.autoDeleteSeconds)
                .put("defaultPermissions", JSONObject(normalizedPolicy.defaultPermissions))
                .put("protectedContent", normalizedPolicy.protectedContent)
        )
        val json = response.optJSONObject("policy") ?: JSONObject()
        return AloGroupPolicy(
            kind = json.optString("kind", normalizedPolicy.kind),
            username = json.optString("username", normalizedPolicy.username),
            joinPolicy = json.optString("joinPolicy", normalizedPolicy.joinPolicy),
            isForum = json.optBoolean("isForum", normalizedPolicy.isForum),
            historyVisible = json.optBoolean("historyVisible", normalizedPolicy.historyVisible),
            slowModeSeconds = json.optInt("slowModeSeconds", normalizedPolicy.slowModeSeconds),
            autoDeleteSeconds = json.optInt("autoDeleteSeconds", normalizedPolicy.autoDeleteSeconds),
            defaultPermissions = parseBooleanMap(json.optJSONObject("defaultPermissions")),
            memberAdminRights = parseBooleanMap(json.optJSONObject("memberAdminRights")),
            memberRestrictions = parseBooleanMap(json.optJSONObject("memberRestrictions")),
            memberRestrictedUntil = json.optString("memberRestrictedUntil"),
            protectedContent = json.optBoolean("protectedContent", normalizedPolicy.protectedContent)
        ).normalizedCommunityPolicy()
    }

    suspend fun updateGroupMemberAccess(
        roomId: Int,
        userId: Int,
        role: String,
        adminTitle: String = "",
        isAnonymous: Boolean,
        adminRights: Map<String, Boolean>,
        restrictions: Map<String, Boolean>,
        changeRole: Boolean
    ) {
        if (changeRole) {
            request(
                method = "PATCH",
                path = "/api/group-chats/$roomId/members/$userId/role",
                body = JSONObject()
                    .put("role", role)
                    .put("adminTitle", adminTitle.trim())
                    .put("isAnonymous", isAnonymous)
                    .put("adminRights", JSONObject(adminRights))
            )
        }
        request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/members/$userId/permissions",
            body = JSONObject()
                .put("adminRights", JSONObject(adminRights))
                .put("isAnonymous", isAnonymous)
                .put("restrictions", JSONObject(restrictions))
                .put("restrictedUntil", JSONObject.NULL)
        )
    }

    suspend fun transferGroupOwnership(roomId: Int, userId: Int, currentPassword: String) {
        request(
            method = "POST",
            path = "/api/group-chats/$roomId/transfer-ownership",
            body = JSONObject()
                .put("userId", userId)
                .put("currentPassword", currentPassword)
        )
    }

    /** Removes (or bans) several members at once; the server skips those it may not touch: removed ids and the skipped count. */
    suspend fun batchRemoveGroupMembers(roomId: Int, userIds: List<Int>, ban: Boolean): Pair<List<Int>, Int> {
        val json = request(
            method = "POST",
            path = "/api/group-chats/$roomId/members/batch-remove",
            body = JSONObject().put("userIds", JSONArray(userIds)).put("ban", ban)
        )
        val removed = json.optJSONArray("removed")?.let { array -> (0 until array.length()).map { array.optInt(it) } }.orEmpty()
        return removed to (json.optJSONArray("skipped")?.length() ?: 0)
    }

    suspend fun banGroupMember(
        roomId: Int,
        userId: Int,
        reason: String,
        expiresAt: String = ""
    ) {
        request(
            method = "POST",
            path = "/api/group-chats/$roomId/bans/$userId",
            body = JSONObject()
                .put("reason", reason.trim())
                .put("expiresAt", expiresAt.takeIf(String::isNotBlank) ?: JSONObject.NULL)
        )
    }

    suspend fun unbanGroupMember(roomId: Int, userId: Int) {
        request(
            method = "DELETE",
            path = "/api/group-chats/$roomId/bans/$userId"
        )
    }

    suspend fun reviewGroupJoinRequest(roomId: Int, userId: Int, approve: Boolean) {
        request(
            method = "POST",
            path = "/api/group-chats/$roomId/join-requests/$userId/${if (approve) "approve" else "reject"}",
            body = JSONObject()
        )
    }

    suspend fun addGroupChatMember(
        roomId: Int,
        username: String
    ) {
        request(
            method = "POST",
            path = "/api/group-chats/$roomId/members",
            body = JSONObject().put("username", username.trim().removePrefix("@"))
        )
    }

    suspend fun removeGroupChatMember(
        roomId: Int,
        userId: Int
    ) {
        request(
            method = "DELETE",
            path = "/api/group-chats/$roomId/members/$userId"
        )
    }

    suspend fun loadCallHistory(beforeId: Int = 0): Pair<List<AloCallHistoryItem>, Boolean> {
        val response = request("GET", if (beforeId > 0) "/api/calls/history?beforeId=$beforeId" else "/api/calls/history")
        val items = jsonObjectList(response.optJSONArray("items")).mapNotNull { json ->
            val peer = parseEntitySummary(json.optJSONObject("peer")) ?: return@mapNotNull null
            AloCallHistoryItem(
                id = json.optInt("id"),
                isOutgoing = json.optString("direction") == "outgoing",
                isVideo = json.optString("type") == "video",
                outcome = json.optString("outcome").ifBlank { "completed" },
                createdAt = json.optString("createdAt"),
                durationSeconds = json.optInt("durationSeconds").coerceAtLeast(0),
                peer = peer
            )
        }
        return items to response.optBoolean("hasMore")
    }

    /** Deletes a one-to-one chat for this account only; the other person keeps their copy. */
    suspend fun deleteDirectChatForMe(peerId: Int) {
        request(method = "POST", path = "/api/messages/$peerId/clear-history", body = JSONObject())
    }

    suspend fun leaveGroupChat(roomId: Int) {
        request(
            method = "POST",
            path = "/api/group-chats/$roomId/leave",
            body = JSONObject()
        )
    }

    suspend fun deleteGroupChat(roomId: Int) {
        request(
            method = "DELETE",
            path = "/api/group-chats/$roomId"
        )
    }

    suspend fun updateGroupChatMemberRole(
        roomId: Int,
        userId: Int,
        role: String
    ) {
        request(
            method = "PATCH",
            path = "/api/group-chats/$roomId/members/$userId/role",
            body = JSONObject().put("role", role)
        )
    }

    suspend fun sendMessage(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        body: String,
        attachment: AloOutgoingMessageAttachment? = null,
        attachments: List<AloOutgoingMessageAttachment> = emptyList(),
        forwardFrom: AloMessageForwardSource? = null,
        captchaToken: String? = null,
        selfDestructSeconds: Int = 0,
        replyToMessageId: Int = 0,
        topicId: Int = 0,
        clientMessageId: String = UUID.randomUUID().toString().replace("-", "").lowercase(),
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false
    ) {
        val resolvedAttachments = attachments.ifEmpty { listOfNotNull(attachment) }
        if (forwardFrom != null && cloudMessagesAllowed(kind, peerId, roomId)) {
            sendCloudForward(
                kind = kind,
                peerId = peerId,
                roomId = roomId,
                comment = body,
                forwardFrom = forwardFrom,
                captchaToken = captchaToken,
                replyToMessageId = replyToMessageId,
                topicId = topicId,
                clientMessageId = clientMessageId,
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
            return
        }
        val encryptedPayload = prepareMessagePayload(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            body = body,
            attachments = resolvedAttachments,
            forwardFrom = forwardFrom
        )
        sendPreparedMessage(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            encryptedPayload = encryptedPayload,
            attachments = resolvedAttachments,
            forwardFrom = forwardFrom,
            captchaToken = captchaToken,
            selfDestructSeconds = selfDestructSeconds,
            replyToMessageId = replyToMessageId,
            topicId = topicId,
            clientMessageId = clientMessageId,
            silent = silent,
            scheduledAt = scheduledAt,
            sendWhenOnline = sendWhenOnline
        )
    }

    suspend fun sendPreparedMessage(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        encryptedPayload: JSONObject,
        attachments: List<AloOutgoingMessageAttachment> = emptyList(),
        forwardFrom: AloMessageForwardSource? = null,
        captchaToken: String? = null,
        selfDestructSeconds: Int = 0,
        replyToMessageId: Int = 0,
        topicId: Int = 0,
        clientMessageId: String,
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false,
        scheduledPoll: AloPollDefinition? = null
    ): AloMessageSendAcknowledgement {
        val cloud = AloCloudMessages.isCloudPayload(encryptedPayload)
        val payload = JSONObject()
            .put("clientMessageId", clientMessageId)
            .put("selfDestructSeconds", selfDestructSeconds.coerceAtLeast(0))
            .put("replyToMessageId", replyToMessageId.coerceAtLeast(0))
            .put("topicId", topicId.coerceAtLeast(0))
            .put("silent", silent)
            .put("sendWhenOnline", sendWhenOnline)
            .putCaptchaToken(captchaToken)
        if (cloud) {
            // A cloud message: plain body, entities and attachments next to each other, no envelope.
            AloCloudMessages.putContent(payload, encryptedPayload)
        } else {
            payload.put("encrypted", encryptedPayload)
        }
        if (scheduledAt.isNotBlank()) {
            payload.put("scheduledAt", scheduledAt)
            scheduledPoll?.let { payload.put("scheduledPoll", pollRegistrationJson(it)) }
        }
        if (!cloud && attachments.isNotEmpty()) {
            val array = JSONArray()
            attachments.filterNot { it.isVirtualCard }.forEach { item ->
                array.put(e2eeServerAttachmentJson(item))
            }
            if (array.length() > 0) payload.put("attachments", array)
        }
        forwardFrom?.let {
            payload.put(
                "forwardFrom",
                JSONObject()
                    .put("userId", it.userId)
                    .put("messageId", it.messageId)
            )
        }
        val resolvedRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
        val response = if (kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET) {
            if (resolvedRoomId <= 0) {
                throw IllegalStateException("Не удалось определить групповой чат.")
            }
            request(
                method = "POST",
                path = "/api/group-chats/$resolvedRoomId/messages/send",
                body = payload
            )
        } else {
            request(
                method = "POST",
                path = "/api/messages/send",
                body = payload.put("peerId", peerId)
            )
        }
        if (!cloud) e2eeCrypto?.markRatchetSessionsConfirmed(encryptedPayload)
        val message = response.optJSONObject("message") ?: JSONObject()
        return AloMessageSendAcknowledgement(
            messageId = message.optInt("id", 0),
            createdAt = message.optString("createdAt"),
            scheduled = response.optBoolean("scheduled"),
            scheduledMessageId = response.optJSONObject("scheduledMessage")?.optInt("id") ?: 0,
            scheduledAt = message.optString("scheduledAt").ifBlank {
                response.optJSONObject("scheduledMessage")?.optString("scheduledAt").orEmpty()
            },
            sendWhenOnline = message.optBoolean("sendWhenOnline") ||
                (response.optJSONObject("scheduledMessage")?.optBoolean("sendWhenOnline") == true) ||
                response.optBoolean("sendWhenOnline")
        )
    }

    // ---- Secret chats (docs/cloud-and-secret-chats.md, stage 2) ----

    /** The libsignal device of this install once the server knows it: only such a device can start or accept a secret chat. */
    fun secretDeviceId(): String? {
        val crypto = e2eeCrypto ?: return null
        val preferences = libSignalPreferences ?: return null
        if (libSignalEngine == null || currentUserId <= 0 || libSignalProtocolDeviceId !in 1..127) return null
        val registered = preferences.getBoolean(
            "alo.android.libsignal.v1.registered.$currentUserId.$libSignalProtocolDeviceId",
            false
        )
        return crypto.deviceId.takeIf { registered && it.isNotBlank() }
    }

    /** `security.secretChats` is on and this device has its libsignal device. */
    suspend fun secretChatsAvailable(): Boolean {
        if (secretDeviceId() == null) return false
        return try {
            messageSecurityPolicy().secretChatsEnabled
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun requireSecretDevice(): String = secretDeviceId() ?: throw AloApiException(
        status = -1,
        code = "SECRET_DEVICE_NOT_READY",
        message = "Шифрование на этом устройстве ещё не готово. Подождите секунду и повторите.",
        payload = JSONObject()
    )

    private suspend fun parseSecretChat(json: JSONObject): AloSecretChat? {
        val last = json.optJSONObject("lastMessage")?.let { openSecretMessage(it) }
        return AloSecretChatCodec.parseChat(json, last)?.let { it.copy(peerAvatarUrl = resolveUrl(it.peerAvatarUrl)) }
    }

    /**
     * Opens the envelope of a secret message with this device's libsignal state (our own messages come from the local
     * plaintext store, as in every encrypted chat). The ordinary reader waits for a tap when a message has a timer; here the
     * timer runs from the read mark, so it gets a copy without the timer and always fetches the files (the server drops
     * them when the timer ends).
     */
    private suspend fun openSecretMessage(json: JSONObject): AloSecretMessage {
        val shim = JSONObject(json.toString())
            .put("senderId", currentUserId)
            .put("selfDestructSeconds", 0)
            .put("expiresAt", "")
            .put("encryptionScheme", json.optJSONObject("encryptedPayload")?.optString("scheme").orEmpty())
        decryptE2eeMessageJson(shim)
        val opened = JSONObject(json.toString()).put("body", shim.optString("body"))
        shim.optJSONArray("entities")?.let { opened.put("entities", it) }
        shim.optJSONArray("attachments")?.let { opened.put("attachments", it) }
        return AloSecretChatCodec.parseMessage(opened)
    }

    /** A secret message that is gone (its timer ended, the chat was deleted) leaves nothing here: its text and its files. */
    fun forgetSecretMessages(messages: List<AloSecretMessage>) {
        messages.forEach { message ->
            if (message.contextId.isNotBlank()) libSignalStore?.forgetMessagePlaintext(message.contextId)
            if (message.attachments.isNotEmpty()) runCatching { purgeCachedMessageMedia(message.toChatMessage()) }
        }
    }

    /** The chats of this device and the requests nobody has accepted yet, newest activity first. */
    suspend fun loadSecretChats(): List<AloSecretChat> {
        val deviceId = requireSecretDevice()
        val response = request("GET", AloSecretChatCodec.listPath(deviceId))
        return AloSecretChatList.replace(
            jsonObjectList(response.optJSONArray("items")).mapNotNull { parseSecretChat(it) }
        )
    }

    suspend fun loadSecretMessages(id: Int): AloSecretConversation {
        val deviceId = requireSecretDevice()
        val response = request("GET", AloSecretChatCodec.messagesPath(id, deviceId))
        val messages = jsonObjectList(response.optJSONArray("items")).map { openSecretMessage(it) }
        return AloSecretConversation(
            chat = response.optJSONObject("secretChat")?.let { parseSecretChat(it) },
            messages = messages,
            hasMore = response.optJSONObject("page")?.optBoolean("hasMore") == true
        )
    }

    suspend fun startSecretChat(peerUserId: Int): AloSecretChat {
        val deviceId = requireSecretDevice()
        val response = request("POST", "/api/secret-chats", AloSecretChatCodec.startBody(peerUserId, deviceId))
        return response.optJSONObject("secretChat")?.let { parseSecretChat(it) }
            ?: throw IllegalStateException("Сервер не вернул секретный чат.")
    }

    /** The first device of the person that accepts is the other end; another one answers 409 `secret_chat_taken`. */
    suspend fun acceptSecretChat(id: Int): AloSecretChat {
        val deviceId = requireSecretDevice()
        val response = request("POST", "/api/secret-chats/$id/accept", AloSecretChatCodec.acceptBody(deviceId))
        return response.optJSONObject("secretChat")?.let { parseSecretChat(it) }
            ?: throw IllegalStateException("Сервер не вернул секретный чат.")
    }

    suspend fun markSecretChatRead(id: Int, upToMessageId: Int) {
        if (upToMessageId <= 0) return
        request("POST", "/api/secret-chats/$id/read", AloSecretChatCodec.readBody(requireSecretDevice(), upToMessageId))
    }

    suspend fun setSecretChatTimer(id: Int, seconds: Int) {
        request("PUT", "/api/secret-chats/$id/timer", AloSecretChatCodec.timerBody(requireSecretDevice(), seconds))
    }

    /** Deletes the chat for both people (or declines a request): every message and file goes from the server. */
    suspend fun deleteSecretChat(id: Int) {
        request("DELETE", AloSecretChatCodec.deletePath(id, requireSecretDevice()))
    }

    /**
     * One secret message: sealed for the ONE device at the other end of the chat. [attachments] are files already
     * uploaded as opaque `e2ee` files (uploadMessageE2eeAttachment), with the keys that stay inside the envelope.
     */
    suspend fun sendSecretMessage(
        chat: AloSecretChat,
        text: String,
        attachments: List<AloOutgoingMessageAttachment>,
        selfDestructSeconds: Int,
        clientMessageId: String = UUID.randomUUID().toString().replace("-", "").lowercase()
    ): AloSecretMessage {
        val deviceId = requireSecretDevice()
        if (!chat.isActive || chat.remoteDeviceId.isBlank()) {
            throw AloApiException(
                status = 409,
                code = "secret_chat_not_active",
                message = "Секретный чат ещё не принят.",
                payload = JSONObject()
            )
        }
        val envelope = prepareLibSignalSecretMessagePayload(chat.peerUserId, chat.remoteDeviceId, text, attachments)
        check(AloSecretChatCodec.isSingleRecipientEnvelope(envelope, chat.peerUserId, chat.remoteDeviceId, deviceId)) {
            "A secret message must be sealed for exactly one device"
        }
        val response = request(
            method = "POST",
            path = "/api/secret-chats/${chat.id}/messages",
            body = AloSecretChatCodec.messageBody(deviceId, envelope, attachments, clientMessageId, selfDestructSeconds),
            readTimeoutMs = 45_000
        )
        val message = response.optJSONObject("message") ?: throw IllegalStateException("Сервер не вернул сообщение.")
        return openSecretMessage(message)
    }

    /**
     * The libsignal envelope of a secret message: ONE recipient, the device at the other end of the chat. Unlike a
     * direct message it has no copies for the sender's other devices and none for the person's other devices.
     */
    private suspend fun prepareLibSignalSecretMessagePayload(
        peerUserId: Int,
        remoteDeviceId: String,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>
    ): JSONObject {
        val engine = libSignalEngine ?: throw AloApiException(
            status = -1,
            code = "E2EE_UNAVAILABLE",
            message = "Шифрование сообщений недоступно на этом устройстве.",
            payload = JSONObject()
        )
        var device = loadLibSignalUserDevices(peerUserId, includePreKeys = false)
            .firstOrNull { it.deviceId == remoteDeviceId }
            ?: throw AloApiException(
                status = 410,
                code = "SECRET_PEER_DEVICE_GONE",
                message = "Устройство собеседника больше не доступно: этот секретный чат закончился.",
                payload = JSONObject()
            )
        validateLibSignalIdentity(device)
        if (!engine.hasSession(peerUserId, device.protocolDeviceId)) {
            device = loadLibSignalUserDevices(peerUserId, includePreKeys = true, targets = listOf(device))
                .firstOrNull { it.deviceId == remoteDeviceId }
                ?: device
            validateLibSignalIdentity(device)
            val bundle = device.preKeyBundle ?: throw AloApiException(
                status = -1,
                code = "LIBSIGNAL_PREKEYS_MISSING",
                message = "У устройства собеседника закончились одноразовые ключи. Попросите открыть Alo.",
                payload = JSONObject()
            )
            engine.processRemotePreKeyBundle(peerUserId, bundle)
        }

        val contextId = "msg_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val markup = AloMessageMarkup.parse(body)
        val content = JSONObject()
            .put("v", 3)
            .put("body", markup.body)
            .put("entities", JSONArray().also { array -> markup.entities.forEach { array.put(it.toJson()) } })
            .put("attachments", JSONArray().also { array -> attachments.forEach { array.put(messageAttachmentJson(it)) } })
            .put("sentAt", java.time.Instant.now().toString())
        val plaintext = content.toString().toByteArray(Charsets.UTF_8)
        val ciphertext = engine.encrypt(peerUserId, device.protocolDeviceId, plaintext)
        val recipient = JSONObject()
            .put("userId", peerUserId)
            .put("deviceId", device.deviceId)
            .put("protocolDeviceId", device.protocolDeviceId)
            .put("ciphertextType", ciphertext.type)
            .put("ciphertext", libSignalBase64(ciphertext.bytes))
        if (ciphertext.type == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
            device.preKeyBundle?.let { bundle ->
                recipient.put("preKeyId", bundle.preKeyId)
                recipient.put("kyberPreKeyId", bundle.kyberPreKeyId)
            }
        }
        // Our own copy stays on this device only, the way every encrypted chat keeps what it sent.
        libSignalStore?.storeMessagePlaintext(contextId, plaintext)
        return JSONObject()
            .put("scheme", LIBSIGNAL_SCHEME)
            .put("protocolVersion", 1)
            .put("purpose", "message")
            .put("contextId", contextId)
            .put("senderUserId", currentUserId)
            .put("senderDeviceId", e2eeCrypto?.deviceId)
            .put("senderProtocolDeviceId", libSignalProtocolDeviceId)
            .put("recipients", JSONArray().put(recipient))
    }

    /**
     * The content of an outgoing message in the form its chat takes: plain text for a cloud chat (the server allows it
     * for direct chats and groups separately), an E2EE envelope otherwise. [forceEncrypted] is for the edit of an old
     * encrypted message, which the server only takes encrypted.
     */
    suspend fun prepareMessagePayload(
        kind: String,
        peerId: Int,
        roomId: Int,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource?,
        forceEncrypted: Boolean = false
    ): JSONObject {
        if (!forceEncrypted && cloudMessagesAllowed(kind, peerId, roomId)) {
            return prepareCloudMessagePayload(kind, peerId, roomId, body, attachments, forwardFrom)
        }
        return prepareEncryptedMessagePayload(kind, peerId, roomId, body, attachments, forwardFrom)
    }

    private fun prepareCloudMessagePayload(
        kind: String,
        peerId: Int,
        roomId: Int,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource?
    ): JSONObject {
        requireSendableCloudAttachments(attachments)
        val directory = if (isGroupTarget(kind, peerId, roomId)) {
            groupMentionDirectory[groupRoomIdFrom(peerId = peerId, roomId = roomId)].orEmpty()
        } else {
            emptyMap()
        }
        return AloCloudMessages.buildPayloadFromMarkup(
            source = body,
            attachments = attachments,
            forwardFrom = forwardFrom,
            mentionDirectory = directory,
            selfUserId = currentUserId,
            baseUrl = baseUrl
        )
    }

    /** The server drops an attachment it does not accept without a word, so an unsendable one stops the message here. */
    private fun requireSendableCloudAttachments(attachments: List<AloOutgoingMessageAttachment>) {
        if (attachments.all { AloCloudMessages.isSendableAttachment(it, baseUrl) }) return
        throw AloApiException(
            status = -1,
            code = "CLOUD_ATTACHMENT_INVALID",
            message = "Это вложение нельзя отправить в обычный чат. Отправьте файл заново.",
            payload = JSONObject()
        )
    }

    private fun cloudUploadTooLarge(limitBytes: Long): AloApiException = AloApiException(
        status = 413,
        code = "cloud_upload_too_large",
        message = "Файл больше ${limitBytes / (1024L * 1024L)} МБ: в обычных чатах такие файлы не отправляются.",
        payload = JSONObject()
    )

    /**
     * A forward in a cloud chat. The comment typed with it goes as a message of its own (as in Telegram). The forwarded
     * message carries what the source said: the server cannot copy an old encrypted message, and it drops the
     * formatting when it copies a plain one. A source without a copy of its content is copied by the server if it is plain.
     */
    private suspend fun sendCloudForward(
        kind: String,
        peerId: Int,
        roomId: Int,
        comment: String,
        forwardFrom: AloMessageForwardSource,
        captchaToken: String?,
        replyToMessageId: Int,
        topicId: Int,
        clientMessageId: String,
        silent: Boolean,
        scheduledAt: String,
        sendWhenOnline: Boolean
    ) {
        if (!forwardFrom.hasContent && forwardFrom.encrypted) {
            throw AloApiException(
                status = -1,
                code = "CLOUD_FORWARD_UNAVAILABLE",
                message = "Это сообщение нельзя переслать: откройте его в чате и перешлите снова.",
                payload = JSONObject()
            )
        }
        val hasComment = comment.isNotBlank()
        if (hasComment) {
            sendPreparedMessage(
                kind = kind,
                peerId = peerId,
                roomId = roomId,
                encryptedPayload = prepareCloudMessagePayload(kind, peerId, roomId, comment, emptyList(), null),
                captchaToken = captchaToken,
                replyToMessageId = replyToMessageId,
                topicId = topicId,
                clientMessageId = UUID.randomUUID().toString().replace("-", "").lowercase(),
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
        }
        val attachments = forwardFrom.attachments.map { cloudAttachmentForForward(it) }
        val content = if (forwardFrom.hasContent) {
            AloCloudMessages.trimmed(AloMessageMarkupResult(forwardFrom.body, forwardFrom.entities))
        } else {
            AloMessageMarkupResult("", emptyList())
        }
        sendPreparedMessage(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            encryptedPayload = AloCloudMessages.buildPayload(
                content = content,
                attachments = attachments,
                forwardFrom = forwardFrom,
                baseUrl = baseUrl
            ),
            forwardFrom = forwardFrom,
            captchaToken = captchaToken,
            replyToMessageId = if (hasComment) 0 else replyToMessageId,
            topicId = topicId,
            clientMessageId = clientMessageId,
            silent = silent,
            scheduledAt = scheduledAt,
            sendWhenOnline = sendWhenOnline
        )
    }

    /**
     * An attachment of a forwarded message as a plain file of ours. Plain ones go as they are; an attachment of an old
     * encrypted message is read from the copy this device has already downloaded and decrypted, and uploaded again.
     */
    private suspend fun cloudAttachmentForForward(attachment: AloOutgoingMessageAttachment): AloOutgoingMessageAttachment {
        if (AloCloudMessages.isSendableAttachment(attachment, baseUrl)) return attachment
        val context = applicationContext
        val source = attachment.url
        if (context == null || !(source.startsWith("content://", ignoreCase = true) || source.startsWith("file://", ignoreCase = true))) {
            throw AloApiException(
                status = -1,
                code = "CLOUD_FORWARD_MEDIA_UNAVAILABLE",
                message = "Сначала скачайте вложение, затем перешлите сообщение.",
                payload = JSONObject()
            )
        }
        val mime = attachment.mediaEncryption["mime"].orEmpty().ifBlank { guessMimeTypeFromName(attachment.name) }
        val limit = AloCloudMessages.maxUploadBytes(mime)
        val root = File(context.cacheDir, "alo-cloud-upload").apply { mkdirs() }
        val plain = File(root, "forward-${UUID.randomUUID().toString().replace("-", "")}.bin")
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(source))
                    ?: throw IOException("Не удалось прочитать вложение.")
                input.use { stream ->
                    java.io.FileOutputStream(plain).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > limit) throw cloudUploadTooLarge(limit)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val uploaded = uploadMessagePlainFile(plain, mime)
            return attachment.copy(url = uploaded.url, mediaEncryption = emptyMap(), posterUrl = "")
        } finally {
            plain.delete()
        }
    }

    suspend fun updateScheduledMessage(
        scheduledMessageId: Int,
        scheduledAt: String,
        silent: Boolean? = null,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ) {
        val body = JSONObject()
            .put("scheduledAt", scheduledAt)
            .put("operationId", operationId)
        silent?.let { body.put("silent", it) }
        request(
            method = "PATCH",
            path = "/api/messages/scheduled/$scheduledMessageId",
            body = body
        )
    }

    suspend fun cancelScheduledMessage(
        scheduledMessageId: Int,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ) {
        request(
            method = "DELETE",
            path = "/api/messages/scheduled/$scheduledMessageId",
            body = JSONObject().put("operationId", operationId)
        )
    }

    /**
     * Future capability contract: the service creates the durable schedule and
     * returns its identifier. The encrypted message payload never leaves the
     * normal message envelope in plaintext.
     * The server still takes only an `encrypted` envelope on the repeat routes (no cloud `body`), so this one
     * stays on libsignal even in a cloud chat.
     */
    suspend fun scheduleRepeatingMessage(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        body: String,
        repeat: AloMessageRepeatSchedule,
        replyToMessageId: Int = 0,
        topicId: Int = 0,
        clientMessageId: String = UUID.randomUUID().toString().replace("-", "").lowercase()
    ): Int {
        val encryptedPayload = prepareEncryptedMessagePayload(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            body = body,
            attachments = emptyList(),
            forwardFrom = null
        )
        val payload = JSONObject()
            .put("encrypted", encryptedPayload)
            .put("clientMessageId", clientMessageId)
            .put("replyToMessageId", replyToMessageId.coerceAtLeast(0))
            .put("topicId", topicId.coerceAtLeast(0))
            .put(
                "repeat",
                JSONObject()
                    .put("startsAt", repeat.startsAt)
                    .put("intervalSeconds", repeat.intervalSeconds)
                    .put("occurrences", repeat.occurrences)
            )
        val resolvedRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
        val response = if (kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET) {
            if (resolvedRoomId <= 0) throw IllegalStateException("Не удалось определить групповой чат.")
            request(
                method = "POST",
                path = "/api/group-chats/$resolvedRoomId/messages/repeat",
                body = payload
            )
        } else {
            request(
                method = "POST",
                path = "/api/messages/repeat",
                body = payload.put("peerId", peerId)
            )
        }
        return response.optJSONObject("scheduledMessage")?.optInt("id")
            ?: response.optInt("scheduledMessageId")
    }

    /** The pack list is server-backed. Empty results mean exactly that: no local fallback pack is invented. */
    suspend fun loadStickerPacks(
        query: String = "",
        limit: Int = 50
    ): List<AloStickerPack> {
        val parts = mutableListOf("limit=${limit.coerceIn(1, 100)}")
        query.trim().takeIf(String::isNotBlank)?.let { parts += "q=${urlEncode(it.take(80))}" }
        val response = request(method = "GET", path = "/api/stickers/packs?${parts.joinToString("&")}")
        return jsonObjectList(response.optJSONArray("packs")).map(::parseStickerPack)
    }

    suspend fun loadInstalledStickerPacks(): List<AloStickerPack> {
        val response = request(method = "GET", path = "/api/stickers/installed")
        return jsonObjectList(response.optJSONArray("packs")).map(::parseStickerPack)
    }

    suspend fun loadStickerPack(packId: String): AloStickerPack {
        val safeId = packId.trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: throw IllegalArgumentException("Некорректный идентификатор набора стикеров.")
        val response = request(method = "GET", path = "/api/stickers/packs/${urlEncode(safeId)}")
        return parseStickerPack(response.optJSONObject("pack") ?: response)
    }

    /** Searches only installed packs, as on the server. It never substitutes Unicode emoji for missing stickers. */
    suspend fun searchStickers(
        query: String,
        limit: Int = 48
    ): List<AloCatalogExpressionMedia> {
        val safeQuery = query.trim().take(80)
        if (safeQuery.isBlank()) return emptyList()
        val response = request(
            method = "GET",
            path = "/api/stickers/search?emoji=${urlEncode(safeQuery)}&limit=${limit.coerceIn(1, 100)}"
        )
        return jsonObjectList(response.optJSONArray("stickers"))
            .mapNotNull(::parseStickerMedia)
    }

    suspend fun installStickerPack(packId: String): AloStickerPack {
        val safeId = packId.trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: throw IllegalArgumentException("Некорректный идентификатор набора стикеров.")
        val response = request(
            method = "POST",
            path = "/api/stickers/packs/${urlEncode(safeId)}/install",
            body = JSONObject()
        )
        return parseStickerPack(response.optJSONObject("pack") ?: response)
    }

    suspend fun loadTrendingGifs(limit: Int = 24): AloGifCatalogPage {
        val response = request(
            method = "GET",
            path = "/api/gifs/trending?limit=${limit.coerceIn(1, 50)}"
        )
        return parseGifCatalogPage(response)
    }

    suspend fun searchGifs(
        query: String,
        limit: Int = 24,
        offset: String = ""
    ): AloGifCatalogPage {
        val safeQuery = query.trim().take(160)
        if (safeQuery.isBlank()) return AloGifCatalogPage(emptyList())
        val parts = mutableListOf(
            "q=${urlEncode(safeQuery)}",
            "limit=${limit.coerceIn(1, 50)}"
        )
        offset.trim().takeIf(String::isNotBlank)?.let { parts += "offset=${urlEncode(it.take(256))}" }
        val response = request(method = "GET", path = "/api/gifs/search?${parts.joinToString("&")}")
        return parseGifCatalogPage(response)
    }

    /**
     * Downloads catalog media into app-private cache before the existing E2EE
     * draft uploader encrypts it. Authentication headers are sent only back to
     * the Alo origin, never to an external GIF CDN.
     */
    suspend fun downloadCatalogMedia(media: AloCatalogExpressionMedia): File = withContext(Dispatchers.IO) {
        val context = applicationContext
            ?: throw IllegalStateException("Отправка стикеров и GIF доступна только в приложении Alo.")
        val directory = File(context.cacheDir, "catalog-media")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Не удалось подготовить защищённое хранилище медиа.")
        }
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }

        var currentUrl = checkedCatalogUrl(URL(media.assetUrl))
        var redirectCount = 0
        var connection: HttpURLConnection? = null
        var temporary: File? = null
        var downloadedFile: File? = null
        try {
            while (downloadedFile == null) {
                currentCoroutineContext().ensureActive()
                val isAloOrigin = isSameServerOrigin(currentUrl)
                connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "image/avif,image/webp,image/gif,image/*,video/*;q=0.8")
                    if (isAloOrigin) {
                        setRequestProperty("X-Requested-With", "AloAndroid")
                        applyInstallIdentityHeader()
                    }
                }
                if (isAloOrigin) {
                    val cookieHeader = withContext(Dispatchers.Main) {
                        CookieManager.getInstance().getCookie(cookieUrl)
                    }
                    if (!cookieHeader.isNullOrBlank()) {
                        connection.setRequestProperty("Cookie", cookieHeader)
                    }
                }

                val status = connection.responseCode
                if (isAloOrigin) syncCookies(connection)
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    connection.disconnect()
                    connection = null
                    if (location.isBlank() || redirectCount++ >= MAX_CATALOG_REDIRECTS) {
                        throw IOException("Не удалось получить медиа из каталога.")
                    }
                    currentUrl = checkedCatalogUrl(URL(currentUrl, location))
                    continue
                }
                if (status !in 200..299) {
                    val detail = if (isAloOrigin) {
                        connection.readBodySafely().toJsonObject().optString("error")
                    } else {
                        ""
                    }
                    throw AloApiException(
                        status = status,
                        code = null,
                        message = detail.ifBlank { "Не удалось загрузить медиа из каталога." },
                        payload = JSONObject()
                    )
                }

                val contentType = connection.contentType.orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase()
                if (!isSupportedCatalogMedia(contentType, currentUrl)) {
                    throw IOException("Этот формат стикера или GIF пока не поддерживается.")
                }
                if (connection.contentLengthLong > MAX_CATALOG_MEDIA_BYTES) {
                    throw IOException("Медиа из каталога слишком большое для отправки.")
                }
                val extension = catalogFileExtension(contentType, currentUrl, media)
                val outputName = media.suggestedFileName().substringBeforeLast('.', media.suggestedFileName()) + "." + extension
                temporary = File(directory, ".${UUID.randomUUID()}.part")
                val destination = File(directory, "${UUID.randomUUID()}-$outputName")
                var copied = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read.toLong()
                            if (copied > MAX_CATALOG_MEDIA_BYTES) {
                                throw IOException("Медиа из каталога слишком большое для отправки.")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (copied <= 0L) throw IOException("Сервис каталога вернул пустое медиа.")
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                temporary = null
                downloadedFile = destination
            }
        } catch (error: Throwable) {
            temporary?.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
        downloadedFile ?: throw IOException("Загрузка медиа завершилась без файла.")
    }

    /** What the server offers for translating a message and turning a voice message or circle into text (`/api/config` → `contentTools`). */
    suspend fun loadContentTools(): AloContentToolsConfig =
        AloContentToolsCodec.parseConfig(request("GET", "/api/config"))

    /**
     * Translates text the person has just chosen to send (docs/chat-content-tools-setup.md). The text was decrypted on this device; the server
     * only checks that the message is in a chat of this account and passes the text to the translation service.
     */
    suspend fun translateMessageText(
        kind: String,
        roomId: Int,
        messageId: Int,
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): AloMessageTranslationResult = AloContentToolsCodec.parseTranslation(
        request(
            method = "POST",
            path = AloContentToolsCodec.path(kind, roomId, messageId, "translate"),
            body = AloContentToolsCodec.translationBody(text, sourceLanguage, targetLanguage, kind, roomId),
            readTimeoutMs = 75_000
        ),
        messageId,
        targetLanguage
    )

    /** The audio of a voice message or circle, decrypted on this device, for the transcription service. */
    suspend fun transcribeMessageAudio(
        kind: String,
        roomId: Int,
        messageId: Int,
        audio: ByteArray,
        mimeType: String,
        language: String
    ): AloVoiceTranscriptionResult = AloContentToolsCodec.parseTranscription(
        request(
            method = "POST",
            path = AloContentToolsCodec.path(kind, roomId, messageId, "transcribe"),
            body = AloContentToolsCodec.transcriptionBody(audio, mimeType, language, kind, roomId),
            readTimeoutMs = 90_000
        ),
        messageId,
        language
    )

    /**
     * Future capability contract for live location. The coordinate card remains
     * encrypted as part of the message payload; only the normal routing data is
     * available to the service.
     * The live-location routes take only an `encrypted` envelope (no cloud `body`), so this one stays on libsignal
     * even in a cloud chat.
     */
    suspend fun sendLiveLocation(
        kind: String,
        peerId: Int,
        roomId: Int = 0,
        request: AloLiveLocationRequest,
        topicId: Int = 0,
        clientMessageId: String = UUID.randomUUID().toString().replace("-", "").lowercase()
    ) {
        val attachment = AloOutgoingMessageAttachment.location(
            latitude = request.latitude,
            longitude = request.longitude,
            title = request.title,
            address = request.address,
            livePeriodSeconds = request.livePeriodSeconds
        )
        val encryptedPayload = prepareEncryptedMessagePayload(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            body = "",
            attachments = listOf(attachment),
            forwardFrom = null
        )
        val payload = JSONObject()
            .put("encrypted", encryptedPayload)
            .put("clientMessageId", clientMessageId)
            .put("topicId", topicId.coerceAtLeast(0))
            .put(
                "liveLocation",
                JSONObject()
                    .put("latitude", request.latitude)
                    .put("longitude", request.longitude)
                    .put("title", request.title)
                    .put("address", request.address)
                    .put("livePeriodSeconds", request.livePeriodSeconds)
            )
        val resolvedRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
        if (kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET) {
            if (resolvedRoomId <= 0) throw IllegalStateException("Не удалось определить групповой чат.")
            request(
                method = "POST",
                path = "/api/group-chats/$resolvedRoomId/messages/location/live",
                body = payload
            )
        } else {
            request(
                method = "POST",
                path = "/api/messages/location/live",
                body = payload.put("peerId", peerId)
            )
        }
    }

    suspend fun uploadMessageAttachment(dataUrl: String): AloUploadedFile {
        return uploadAttachment(dataUrl = dataUrl, purpose = "message")
    }

    suspend fun uploadMessageE2eeAttachment(dataUrl: String): AloUploadedFile {
        val crypto = e2eeCrypto
            ?: throw IllegalStateException("Защищённое хранилище ключей недоступно. Вложение не отправлено.")
        val parsed = dataUrl.parseDataUrlForE2ee()
        val sealed = crypto.encryptMedia(parsed.bytes)
        val uploaded = uploadAttachmentBytes(
            bytes = sealed.data,
            purpose = "message_e2ee",
            mime = "application/octet-stream"
        )
        return uploaded.copy(
            mime = parsed.mime,
            mediaEncryption = mapOf(
                "algorithm" to AloE2eeCrypto.MEDIA_SCHEME,
                "key" to sealed.key,
                "iv" to sealed.iv,
                "mime" to parsed.mime
            )
        )
    }

    /** Streams a private draft through a chunked E2EE envelope. */
    suspend fun uploadMessageE2eeAttachment(file: File, mime: String, reporter: AloUploadReporter? = null): AloUploadedFile {
        e2eeCrypto
            ?: throw IllegalStateException("Защищённое хранилище ключей недоступно. Вложение не отправлено.")
        val context = applicationContext
            ?: throw IllegalStateException("Не удалось подготовить защищённую загрузку.")
        val outgoingRoot = File(context.cacheDir, "alo-e2ee-media/outgoing").apply { mkdirs() }
        val encryptedFile = File(
            outgoingRoot,
            "upload-${UUID.randomUUID().toString().replace("-", "")}.bin"
        )
        val sealed = withContext(Dispatchers.IO) {
            AloChunkedMediaCrypto.encrypt(
                source = file,
                destination = encryptedFile,
                mime = mime.ifBlank { "application/octet-stream" }
            )
        }
        try {
            return uploadMessageE2eeAttachment(
                encryptedFile = sealed.encryptedFile,
                mediaEncryption = sealed.metadata.asMap(),
                checksum = sealed.sha256,
                reporter = reporter
            )
        } finally {
            withContext(Dispatchers.IO) { encryptedFile.delete() }
        }
    }

    /**
     * Uploads a previously created opaque media envelope. This is used after
     * an offline restart: the source remains ciphertext on disk, so retrying
     * never recreates a multi-gigabyte ByteArray or exposes plaintext again.
     */
    suspend fun uploadMessageE2eeAttachment(
        encryptedFile: File,
        mediaEncryption: Map<String, String>,
        checksum: String,
        resumeCheckpoint: AloResumableUploadCheckpoint? = null,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null,
        reporter: AloUploadReporter? = null
    ): AloUploadedFile {
        e2eeCrypto
            ?: throw IllegalStateException("Защищённое хранилище ключей недоступно. Вложение не отправлено.")
        val metadata = AloChunkedMediaCrypto.parseMetadata(JSONObject(mediaEncryption))
            ?: throw IllegalArgumentException("Некорректные метаданные зашифрованного вложения.")
        val expectedSize = AloChunkedMediaCrypto.expectedCiphertextSize(
            metadata.plaintextSize,
            metadata.chunkSize
        )
        require(encryptedFile.isFile && encryptedFile.length() == expectedSize) {
            "Зашифрованное вложение повреждено или загружено не полностью."
        }
        val verifiedChecksum = checksum.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            ?: withContext(Dispatchers.IO) { sha256File(encryptedFile) }
        val response = uploadResumableFile(
            file = encryptedFile,
            purpose = "message_e2ee",
            mime = "application/octet-stream",
            checksum = verifiedChecksum.lowercase(),
            resumeCheckpoint = resumeCheckpoint,
            onCheckpoint = onCheckpoint,
            reporter = reporter
        )
        val uploaded = parseUploadedFile(response)
        return uploaded.copy(
            mime = metadata.mime.ifBlank { uploaded.mime },
            mediaEncryption = metadata.asMap()
        )
    }

    /**
     * Cloud chats keep a file as it is: one plain resumable upload with `purpose: message`.
     * The server takes up to 30 MB of video, 20 MB of voice and 25 MB of anything else this way.
     */
    suspend fun uploadMessagePlainFile(
        file: File,
        mime: String,
        resumeCheckpoint: AloResumableUploadCheckpoint? = null,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null,
        reporter: AloUploadReporter? = null,
        /** A song sent as a file: stays as it is (otherwise the server turns any audio into a voice message). */
        keepOriginal: Boolean = false
    ): AloUploadedFile {
        val safeMime = mime.ifBlank { "application/octet-stream" }
        val limit = if (keepOriginal) 25L * 1024 * 1024 else AloCloudMessages.maxUploadBytes(safeMime)
        if (file.length() > limit) throw cloudUploadTooLarge(limit)
        val checksum = withContext(Dispatchers.IO) { sha256File(file) }
        val response = uploadResumableFile(
            file = file,
            purpose = "message",
            mime = safeMime,
            checksum = checksum,
            resumeCheckpoint = resumeCheckpoint,
            onCheckpoint = onCheckpoint,
            reporter = reporter,
            keepOriginal = keepOriginal
        )
        val uploaded = parseUploadedFile(response)
        return uploaded.copy(mime = uploaded.mime.ifBlank { safeMime })
    }

    /**
     * A draft is kept sealed on the device (see AloDraftAttachmentStore). For a cloud chat it is opened into a
     * temporary plain file for the length of the upload only.
     */
    suspend fun uploadMessagePlainAttachment(
        encryptedFile: File,
        mediaEncryption: Map<String, String>,
        resumeCheckpoint: AloResumableUploadCheckpoint? = null,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null,
        reporter: AloUploadReporter? = null,
        keepOriginal: Boolean = false
    ): AloUploadedFile {
        val context = applicationContext
            ?: throw IllegalStateException("Не удалось подготовить загрузку.")
        val metadata = AloChunkedMediaCrypto.parseMetadata(JSONObject(mediaEncryption))
            ?: throw IllegalArgumentException("Некорректные метаданные вложения.")
        val limit = if (keepOriginal) 25L * 1024 * 1024 else AloCloudMessages.maxUploadBytes(metadata.mime)
        if (metadata.plaintextSize > limit) throw cloudUploadTooLarge(limit)
        val root = File(context.cacheDir, "alo-cloud-upload").apply { mkdirs() }
        val plain = File(root, "upload-${UUID.randomUUID().toString().replace("-", "")}.bin")
        try {
            withContext(Dispatchers.IO) {
                java.io.FileOutputStream(plain).use { output ->
                    AloChunkedMediaCrypto.decryptTo(encryptedFile, metadata, output)
                    output.fd.sync()
                }
            }
            return uploadMessagePlainFile(
                file = plain,
                mime = metadata.mime,
                resumeCheckpoint = resumeCheckpoint,
                onCheckpoint = onCheckpoint,
                reporter = reporter,
                keepOriginal = keepOriginal
            )
        } finally {
            plain.delete()
        }
    }

    suspend fun uploadProfileAvatar(dataUrl: String): AloUploadedFile {
        return uploadAttachment(dataUrl = dataUrl, purpose = "avatar")
    }

    suspend fun updateProfileAvatar(avatarUrl: String): AloCurrentUser {
        val response = request(
            method = "PATCH",
            path = "/api/profile",
            body = JSONObject().put("avatarEmoji", avatarUrl)
        )
        return parseCurrentUser(response.optJSONObject("user") ?: JSONObject())
    }

    /** The reaction a double tap sends; the server keeps it for the account and answers with the one it saved. */
    suspend fun setFavoriteReaction(emoji: String): String {
        val response = request(
            method = "PUT",
            path = "/api/profile/favorite-reaction",
            body = JSONObject().put("emoji", emoji)
        )
        return response.optString("favoriteReaction").ifBlank { emoji }
    }

    suspend fun updateProfileCoverStyle(coverStyle: String): AloCurrentUser {
        val response = request(
            method = "PATCH",
            path = "/api/profile/cover",
            body = JSONObject().put("coverStyle", coverStyle)
        )
        return parseCurrentUser(response.optJSONObject("user") ?: JSONObject())
    }

    /** Story and post media (iOS uploads them with purpose "post"). */
    suspend fun uploadPostMedia(bytes: ByteArray, mime: String): AloUploadedFile =
        uploadAttachmentBytes(bytes = bytes, purpose = "post", mime = mime)

    suspend fun uploadPostFile(file: File, mime: String, expectedOwnerId: Int? = null,
        checkpoint: AloResumableUploadCheckpoint? = null,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null,
        reporter: AloUploadReporter? = null): AloUploadedFile {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return parseUploadedFile(uploadResumableFile(file = file, purpose = "post", mime = mime, checksum = checksum,
            resumeCheckpoint = checkpoint, onCheckpoint = onCheckpoint, reporter = reporter, expectedOwnerId = expectedOwnerId))
    }

    private suspend fun uploadAttachment(dataUrl: String, purpose: String): AloUploadedFile {
        val parsed = dataUrl.parseDataUrlForE2ee()
        return uploadAttachmentBytes(
            bytes = parsed.bytes,
            purpose = purpose,
            mime = parsed.mime
        )
    }

    private suspend fun uploadAttachmentBytes(
        bytes: ByteArray,
        purpose: String,
        mime: String
    ): AloUploadedFile {
        val response = uploadResumable(
            bytes = bytes,
            purpose = purpose,
            mime = mime
        )

        return parseUploadedFile(response)
    }

    private fun parseUploadedFile(response: JSONObject): AloUploadedFile {
        val file = response.optJSONObject("file") ?: JSONObject()
        val url = file.optString("url").trim()
        if (url.isBlank()) {
            throw IllegalStateException("Сервер не вернул URL загруженного файла.")
        }
        return AloUploadedFile(
            url = url,
            type = file.optString("type").trim(),
            mime = file.optString("mime").trim()
        )
    }

    private suspend fun uploadResumable(
        bytes: ByteArray,
        purpose: String,
        mime: String
    ): JSONObject {
        if (bytes.isEmpty()) throw IllegalArgumentException("Файл пуст.")
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val created = request(
            method = "POST",
            path = "/api/uploads/resumable",
            body = JSONObject()
                .put("purpose", purpose)
                .put("mime", mime.ifBlank { "application/octet-stream" })
                .put("size", bytes.size)
                .put("sha256", checksum)
        )
        val upload = created.optJSONObject("upload") ?: JSONObject()
        val uploadId = upload.optString("id").trim()
        if (uploadId.isBlank()) throw IllegalStateException("Сервер не создал сессию загрузки.")
        val chunkSize = upload.optInt("chunkSize", 512 * 1024).coerceIn(64 * 1024, 5 * 1024 * 1024)
        var offset = upload.optInt("offset", 0).coerceAtLeast(0)
        var retryCount = 0

        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val endExclusive = minOf(bytes.size, offset + chunkSize)
            val chunk = bytes.copyOfRange(offset, endExclusive)
            try {
                val response = uploadResumableChunk(
                    uploadId = uploadId,
                    chunk = chunk,
                    start = offset.toLong(),
                    total = bytes.size.toLong()
                )
                if (response.optBoolean("complete")) {
                    val url = response.optJSONObject("file")?.optString("url").orEmpty().trim()
                    if (url.isBlank()) throw IllegalStateException("Сервер не вернул загруженный файл.")
                    return response
                }
                val acknowledged = response.optJSONObject("upload")?.optInt("offset", -1) ?: -1
                if (acknowledged <= offset || acknowledged > bytes.size) {
                    throw IllegalStateException("Сервер подтвердил некорректное смещение загрузки.")
                }
                offset = acknowledged
                retryCount = 0
            } catch (error: Exception) {
                retryCount += 1
                if (retryCount > 4) throw error
                delay(250L shl (retryCount - 1))
                val status = request(
                    method = "GET",
                    path = "/api/uploads/resumable/$uploadId"
                )
                val remoteUpload = status.optJSONObject("upload") ?: JSONObject()
                if (remoteUpload.optBoolean("complete")) {
                    val file = remoteUpload.optJSONObject("file")
                    if (!file?.optString("url").orEmpty().isBlank()) {
                        return JSONObject()
                            .put("ok", true)
                            .put("complete", true)
                            .put("file", file)
                    }
                }
                val acknowledged = remoteUpload.optInt("offset", -1)
                if (acknowledged !in 0..bytes.size) throw error
                offset = acknowledged
            }
        }
        throw IllegalStateException("Загрузка не была завершена.")
    }

    private suspend fun uploadResumableFile(
        file: File,
        purpose: String,
        mime: String,
        checksum: String,
        resumeCheckpoint: AloResumableUploadCheckpoint? = null,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null,
        reporter: AloUploadReporter? = null,
        expectedOwnerId: Int? = null,
        keepOriginal: Boolean = false
    ): JSONObject {
        val total = file.length()
        if (!file.isFile || total <= 0L) throw IllegalArgumentException("Файл пуст.")
        val normalizedChecksum = checksum.lowercase()
        val resumedUpload = resumeCheckpoint
            ?.takeIf {
                it.uploadId.matches(Regex("[a-fA-F0-9]{32}")) &&
                    it.total == total &&
                    it.checksum.equals(normalizedChecksum, ignoreCase = true)
            }
            ?.let { checkpoint ->
                try {
                    request(
                        method = "GET",
                        path = "/api/uploads/resumable/${checkpoint.uploadId}"
                    ).optJSONObject("upload")
                } catch (error: AloApiException) {
                    if (error.status == 404 || error.status == 410) null else throw error
                }
            }

        val upload = resumedUpload ?: request(
            method = "POST",
            path = "/api/uploads/resumable",
            body = JSONObject()
                .put("purpose", purpose)
                .put("mime", mime.ifBlank { "application/octet-stream" })
                .put("size", total)
                .put("sha256", normalizedChecksum)
                .also { body -> expectedOwnerId?.let { body.put("expectedOwnerId", it) } }
                .also { body -> if (keepOriginal) body.put("keepOriginal", true) }
        ).optJSONObject("upload") ?: JSONObject()
        val uploadId = upload.optString("id").trim()
        if (uploadId.isBlank()) throw IllegalStateException("Сервер не создал сессию загрузки.")
        val chunkSize = upload.optInt("chunkSize", 512 * 1024)
            .coerceIn(64 * 1024, 5 * 1024 * 1024)
        var offset = upload.optLong("offset", 0L).coerceAtLeast(0L)
        if (upload.optBoolean("complete")) {
            val remoteFile = upload.optJSONObject("file")
            if (!remoteFile?.optString("url").isNullOrBlank()) {
                return JSONObject().put("ok", true).put("complete", true).put("file", remoteFile)
            }
        }
        if (offset !in 0L..total) throw IllegalStateException("Сервер вернул неверное смещение загрузки.")
        var lastCheckpointOffset = Long.MIN_VALUE
        fun checkpoint(currentOffset: Long, force: Boolean = false) {
            if (!force && currentOffset - lastCheckpointOffset < 4L * 1024L * 1024L) return
            lastCheckpointOffset = currentOffset
            onCheckpoint?.invoke(
                AloResumableUploadCheckpoint(
                    uploadId = uploadId,
                    offset = currentOffset,
                    total = total,
                    checksum = normalizedChecksum
                )
            )
        }
        checkpoint(offset, force = true)
        reporter?.onProgress?.invoke(offset, total)
        var retryCount = 0

        while (offset < total) {
            currentCoroutineContext().ensureActive()
            if (reporter?.shouldPause?.invoke() == true) {
                checkpoint(offset, force = true)
                throw AloUploadPausedException()
            }
            val remaining = total - offset
            val chunk = withContext(Dispatchers.IO) {
                readFileChunk(file, offset, minOf(remaining, chunkSize.toLong()).toInt())
            }
            try {
                val response = uploadResumableChunk(
                    uploadId = uploadId,
                    chunk = chunk,
                    start = offset,
                    total = total
                )
                if (response.optBoolean("complete")) {
                    val url = response.optJSONObject("file")?.optString("url").orEmpty().trim()
                    if (url.isBlank()) throw IllegalStateException("Сервер не вернул URL загруженного файла.")
                    reporter?.onProgress?.invoke(total, total)
                    return response
                }
                val acknowledged = response.optJSONObject("upload")?.optLong("offset", -1L) ?: -1L
                if (acknowledged <= offset || acknowledged > total) {
                    throw IllegalStateException("Сервер подтвердил некорректное смещение загрузки.")
                }
                offset = acknowledged
                checkpoint(offset)
                reporter?.onProgress?.invoke(offset, total)
                retryCount = 0
            } catch (error: Exception) {
                retryCount += 1
                if (retryCount > 4) throw error
                delay(250L shl (retryCount - 1))
                val status = request(
                    method = "GET",
                    path = "/api/uploads/resumable/$uploadId"
                )
                val remoteUpload = status.optJSONObject("upload") ?: JSONObject()
                if (remoteUpload.optBoolean("complete")) {
                    val remoteFile = remoteUpload.optJSONObject("file")
                    if (!remoteFile?.optString("url").orEmpty().isBlank()) {
                        return JSONObject()
                            .put("ok", true)
                            .put("complete", true)
                            .put("file", remoteFile)
                    }
                }
                val acknowledged = remoteUpload.optLong("offset", -1L)
                if (acknowledged !in 0L..total) throw error
                offset = acknowledged
                checkpoint(offset, force = true)
            } finally {
                chunk.fill(0)
            }
        }
        throw IllegalStateException("Загрузка не была завершена.")
    }

    private fun readFileChunk(file: File, offset: Long, size: Int): ByteArray {
        require(size > 0)
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val chunk = ByteArray(size)
            input.readFully(chunk)
            return chunk
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        java.io.FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun uploadResumableChunk(
        uploadId: String,
        chunk: ByteArray,
        start: Long,
        total: Long
    ): JSONObject = withContext(Dispatchers.IO) {
        val fullUrl = URL(URL(baseUrl), "/api/uploads/resumable/$uploadId").toString()
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(chunk.size)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
            setRequestProperty("Content-Range", "bytes $start-${start + chunk.size - 1}/$total")
        }
        try {
            val cookieHeader = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(cookieUrl)
            }
            if (!cookieHeader.isNullOrBlank()) connection.setRequestProperty("Cookie", cookieHeader)
            connection.outputStream.use { output -> output.write(chunk) }
            val status = connection.responseCode
            syncCookies(connection)
            val rawBody = connection.readBodySafely()
            val json = rawBody.toJsonObject()
            if (status !in 200..299) {
                throw AloApiException(
                    status = status,
                    code = json.optString("code").takeIf { it.isNotBlank() },
                    message = json.optString("error").takeIf { it.isNotBlank() }
                        ?: "Не удалось продолжить загрузку.",
                    payload = json
                )
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun deleteMessage(
        kind: String,
        roomId: Int,
        messageId: Int,
        forEveryone: Boolean
    ) {
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/$messageId/delete"
        } else {
            "/api/messages/$messageId/delete"
        }
        request(
            method = "POST",
            path = path,
            body = JSONObject().put("mode", if (forEveryone) "everyone" else "me")
        )
    }

    suspend fun deleteMessages(
        kind: String,
        roomId: Int,
        messageIds: List<Int>,
        forEveryone: Boolean,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ) {
        val ids = messageIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/delete-batch"
        } else {
            "/api/messages/delete-batch"
        }
        request(
            method = "POST",
            path = path,
            body = JSONObject()
                .put("mode", if (forEveryone) "everyone" else "me")
                .put("messageIds", JSONArray(ids))
                .put("operationId", operationId)
        )
    }

    suspend fun prepareMessageMediaOpen(
        kind: String,
        roomId: Int,
        message: AloChatMessage
    ): AloChatMessage {
        if (message.selfDestructSeconds <= 0) {
            // A tap on an attachment the auto-download rules left on the server: download it now.
            if (!message.hasUndownloadedMedia()) return message
            val downloaded = message.withDownloadedMedia(message.attachments.map { attachment ->
                downloadedAttachment(attachment, expiresAt = message.expiresAt)
            })
            // A download that failed leaves the attachment on the server: report it instead of opening a broken viewer.
            if (downloaded.hasUndownloadedMedia()) throw java.io.IOException("Вложение не скачано.")
            return downloaded
        }
        if (message.id <= 0) return message
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/${message.id}/open"
        } else {
            "/api/messages/${message.id}/open"
        }
        val response = request(method = "POST", path = path, body = JSONObject())
        val expiry = response.optJSONObject("expiry") ?: JSONObject()
        val expiresAt = expiry.optString("expiresAt").ifBlank { message.expiresAt }
        val resolvedAttachments = message.attachments.map { attachment ->
            val resolved = resolveDecryptedMediaAttachment(
                messageAttachmentJson(attachment),
                expiresAt = expiresAt
            )
            attachment.copy(
                url = resolveUrl(resolved.optString("url")),
                type = resolved.optString("type").ifBlank { attachment.type },
                name = resolved.optString("name").ifBlank { attachment.name },
                mediaEncryption = parseStringMap(
                    resolved.optJSONObject("mediaEncryption") ?: resolved.optJSONObject("e2ee")
                ).ifEmpty { attachment.mediaEncryption }
            )
        }
        val primary = resolvedAttachments.firstOrNull()
        return message.copy(
            attachmentUrl = primary?.url ?: message.attachmentUrl,
            attachmentType = primary?.type ?: message.attachmentType,
            attachmentName = primary?.name ?: message.attachmentName,
            attachments = resolvedAttachments,
            selfDestructSeconds = expiry.optInt(
                "selfDestructSeconds",
                message.selfDestructSeconds
            ).coerceAtLeast(0),
            selfDestructStartedAt = expiry.optString("selfDestructStartedAt")
                .ifBlank { message.selfDestructStartedAt },
            expiresAt = expiresAt
        )
    }

    private suspend fun downloadedAttachment(
        attachment: AloOutgoingMessageAttachment,
        expiresAt: String
    ): AloOutgoingMessageAttachment {
        val resolved = resolveDecryptedMediaAttachment(messageAttachmentJson(attachment), expiresAt = expiresAt)
        // A copy keeps everything the sender gave (the waveform and the poster too), only the address changes.
        return attachment.copy(
            url = resolveUrl(resolved.optString("url")),
            type = resolved.optString("type").ifBlank { attachment.type },
            name = resolved.optString("name").ifBlank { attachment.name },
            mediaEncryption = parseStringMap(
                resolved.optJSONObject("mediaEncryption") ?: resolved.optJSONObject("e2ee")
            ).ifEmpty { attachment.mediaEncryption }
        )
    }

    private fun AloChatMessage.withDownloadedMedia(attachments: List<AloOutgoingMessageAttachment>): AloChatMessage {
        val primary = attachments.firstOrNull()
        return copy(
            attachmentUrl = primary?.url ?: attachmentUrl,
            attachmentType = primary?.type ?: attachmentType,
            attachmentName = primary?.name ?: attachmentName,
            attachments = attachments
        )
    }

    fun purgeCachedMessageMedia(message: AloChatMessage) {
        val attachments = if (message.attachments.isNotEmpty()) {
            message.attachments
        } else {
            listOf(
                AloOutgoingMessageAttachment(
                    url = message.attachmentUrl,
                    type = message.attachmentType,
                    name = message.attachmentName,
                    mediaEncryption = emptyMap()
                )
            )
        }
        attachments.forEach { attachment ->
            if (attachment.url.startsWith("content://", ignoreCase = true)) {
                runCatching {
                    applicationContext?.let { context ->
                        AloE2eeMediaContentProvider.unregister(
                            context = context,
                            uri = android.net.Uri.parse(attachment.url)
                        )
                    }
                }
            }
            if (attachment.url.isBlank() || attachment.mediaEncryption.isEmpty()) return@forEach
            val encryption = JSONObject(attachment.mediaEncryption)
            if (encryption.optString("algorithm") != AloE2eeCrypto.MEDIA_SCHEME) return@forEach
            runCatching {
                encryptedMediaCacheFile(
                    root = e2eeMediaCacheRoot(),
                    remoteUrl = attachment.url,
                    encryption = encryption
                ).delete()
            }
        }
    }

    suspend fun toggleMessageReaction(
        kind: String,
        roomId: Int,
        messageId: Int,
        desiredEmoji: String,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ): List<AloMessageReaction> {
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/$messageId/reactions/toggle"
        } else {
            "/api/messages/$messageId/reactions/toggle"
        }
        val response = request(
            method = "POST",
            path = path,
            body = JSONObject()
                .put("desiredEmoji", desiredEmoji)
                .put("operationId", operationId)
        )
        return parseMessageReactions(response.optJSONArray("reactions"))
    }

    suspend fun toggleMessagePin(
        kind: String,
        roomId: Int,
        messageId: Int,
        pinned: Boolean? = null,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ) {
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/$messageId/pin"
        } else {
            "/api/messages/$messageId/pin"
        }
        request(
            method = "POST",
            path = path,
            body = (if (kind == "group" || roomId > 0) JSONObject() else JSONObject().put("scope", "both"))
                .also { body ->
                    pinned?.let { body.put("pinned", it) }
                    body.put("operationId", operationId)
                }
        )
    }

    suspend fun sendPreparedMessageEdit(
        kind: String,
        roomId: Int,
        messageId: Int,
        encryptedPayload: JSONObject,
        operationId: String = UUID.randomUUID().toString().lowercase()
    ): String {
        val path = if (kind == "group" || roomId > 0) {
            "/api/group-chats/$roomId/messages/$messageId/edit"
        } else {
            "/api/messages/$messageId/edit"
        }
        val cloud = AloCloudMessages.isCloudPayload(encryptedPayload)
        val body = JSONObject().put("operationId", operationId)
        if (cloud) {
            // A cloud message is edited with plain `body` and `entities`.
            body.put("body", encryptedPayload.optString("body"))
            body.put("entities", encryptedPayload.optJSONArray("entities") ?: JSONArray())
        } else {
            body.put("encrypted", encryptedPayload)
        }
        val response = request(
            method = "POST",
            path = path,
            body = body
        )
        if (!cloud) e2eeCrypto?.markRatchetSessionsConfirmed(encryptedPayload)
        return response.optJSONObject("message")?.optString("editedAt").orEmpty()
    }

    suspend fun sendFriendRequest(userId: Int, captchaToken: String? = null): String {
        val response = request(
            method = "POST",
            path = "/api/friends/request",
            body = JSONObject()
                .put("userId", userId)
                .putCaptchaToken(captchaToken)
        )
        return response.optString("status").ifBlank { "outgoing" }
    }

    suspend fun acceptFriendRequest(requestId: Int): String {
        val response = request(
            method = "POST",
            path = "/api/friends/$requestId/accept",
            body = JSONObject()
        )
        return response.optString("status").ifBlank { "friends" }
    }

    /**
     * Follows a person or stops following. Only for someone who closed friend requests; a follow back makes both friends
     * on the server. Returns "friends", "following" or "none".
     */
    suspend fun toggleFollowUser(userId: Int): String {
        val response = request(method = "POST", path = "/api/users/$userId/follow", body = JSONObject())
        val relation = response.optJSONObject("relation")
        return when {
            relation?.optBoolean("isFriend") == true -> "friends"
            relation?.optBoolean("following") == true || response.optBoolean("following") -> "following"
            else -> "none"
        }
    }

    suspend fun cancelFriendRequest(requestId: Int) {
        request(method = "POST", path = "/api/friends/$requestId/cancel", body = JSONObject())
    }

    suspend fun removeFriend(userId: Int) {
        request(method = "POST", path = "/api/friends/remove", body = JSONObject().put("userId", userId))
    }

    suspend fun declineFriendRequest(requestId: Int): String {
        val response = request(
            method = "POST",
            path = "/api/friends/$requestId/decline",
            body = JSONObject()
        )
        return response.optString("status").ifBlank { "declined" }
    }

    suspend fun login(
        email: String,
        password: String,
        captchaToken: String? = null
    ): LoginChallengeResult {
        val response = request(
            method = "POST",
            path = "/api/auth/login",
            body = JSONObject()
                .put("email", email)
                .put("password", password)
                .putCaptchaToken(captchaToken)
        )

        return LoginChallengeResult(
            challengeToken = response.optString("challengeToken"),
            message = response.optString("message", "Код отправлен на почту.")
        )
    }

    suspend fun verifyLogin(
        email: String,
        code: String,
        challengeToken: String,
        verificationRequestId: String
    ) {
        request(
            method = "POST",
            path = "/api/auth/verify-login",
            body = JSONObject()
                .put("email", email)
                .put("code", code)
                .put("challengeToken", challengeToken)
                .put("verificationRequestId", verificationRequestId)
        )
    }

    suspend fun resendLoginCode(email: String, challengeToken: String): CodeDispatchResult {
        val response = request(
            method = "POST",
            path = "/api/auth/resend-login-code",
            body = JSONObject()
                .put("email", email)
                .put("challengeToken", challengeToken)
        )

        return CodeDispatchResult(
            message = response.optString("message", "Новый код отправлен."),
            nextWaitSec = response.optInt("nextWaitSec", 0)
        )
    }

    suspend fun register(
        name: String,
        username: String,
        email: String,
        password: String,
        captchaToken: String? = null
    ): CodeDispatchResult {
        val response = request(
            method = "POST",
            path = "/api/auth/register",
            body = JSONObject()
                .put("name", name)
                .put("username", username)
                .put("email", email)
                .put("password", password)
                .put("skipAvatar", true)
                .putCaptchaToken(captchaToken)
        )

        return CodeDispatchResult(
            message = response.optString("message", "Код отправлен на почту.")
        )
    }

    suspend fun verifyEmail(email: String, code: String) {
        request(
            method = "POST",
            path = "/api/auth/verify-email",
            body = JSONObject()
                .put("email", email)
                .put("code", code)
        )
    }

    suspend fun resendVerification(email: String): CodeDispatchResult {
        val response = request(
            method = "POST",
            path = "/api/auth/resend-verification",
            body = JSONObject().put("email", email)
        )

        return CodeDispatchResult(
            message = response.optString("message", "Новый код отправлен."),
            nextWaitSec = response.optInt("nextWaitSec", 0)
        )
    }

    suspend fun logout() {
        runCatching {
            request(
                method = "POST",
                path = "/api/auth/logout"
            )
        }
        clearCookies()
    }

    suspend fun clearCookies() {
        currentUserId = 0
        libSignalUserId = 0
        libSignalProtocolDeviceId = 0
        libSignalStore = null
        libSignalEngine = null
        withContext(Dispatchers.Main) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        /** A provider may need a minute for a long recording; ordinary calls keep 15 seconds. */
        readTimeoutMs: Int = 15_000
    ): JSONObject = withContext(Dispatchers.IO) {
        val fullUrl = URL(URL(baseUrl), path).toString()
        if (releaseInsecureTransport) {
            throw AloApiException(
                status = -1,
                code = "HTTPS_REQUIRED",
                message = "Release-сборка требует HTTPS адрес сервера.",
                payload = JSONObject()
            )
        }
        try {
            val connection = (URL(URL(baseUrl), path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Requested-With", "AloAndroid")
                applyInstallIdentityHeader()
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            val cookieHeader = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(cookieUrl)
            }
            if (!cookieHeader.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { output ->
                    output.write(bytes)
                }
            }

            val status = connection.responseCode
            syncCookies(connection)
            val rawBody = connection.readBodySafely()
            val json = rawBody.toJsonObject()

            if (status !in 200..299) {
                val code = json.optString("code").takeIf { it.isNotBlank() }
                val message = json.optString("error")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: "Ошибка сервера."

                Log.e("AloApiClient", "HTTP $status $method ${safeLogUrl(fullUrl)} :: $message :: $rawBody")
                throw AloApiException(
                    status = status,
                    code = code,
                    message = message,
                    payload = json
                )
            }

            Log.d("AloApiClient", "HTTP $status $method ${safeLogUrl(fullUrl)}")
            json
        } catch (error: Exception) {
            Log.e("AloApiClient", "request failed $method $fullUrl", error)
            throw error
        }
    }

    /**
     * Receives an account-export attachment into app-private storage.  The export
     * endpoint intentionally responds with the actual JSON document rather than a
     * job id or a download URL, so treating a 2xx as "ready" would be misleading.
     */
    private suspend fun requestJsonAttachment(
        method: String,
        path: String,
        body: JSONObject,
        fallbackFileName: String
    ): AloAccountExportPayload = withContext(Dispatchers.IO) {
        if (releaseInsecureTransport) {
            throw AloApiException(
                status = -1,
                code = "HTTPS_REQUIRED",
                message = "Release-сборка требует HTTPS адрес сервера.",
                payload = JSONObject()
            )
        }
        val context = applicationContext
            ?: throw IllegalStateException("Экспорт данных доступен только в приложении Alo.")
        val fullUrl = URL(URL(baseUrl), path).toString()
        val exportDirectory = File(context.cacheDir, "account-exports")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IOException("Не удалось подготовить защищённое хранилище экспорта.")
        }
        // The document is sensitive. Remove any interrupted export before creating a new one.
        exportDirectory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val temporary = File(exportDirectory, ".account-export-${UUID.randomUUID()}.part")
        val connection = (URL(URL(baseUrl), path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
        }
        try {
            val cookieHeader = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(cookieUrl)
            }
            if (!cookieHeader.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            syncCookies(connection)
            if (status !in 200..299) {
                val errorPayload = connection.readBodySafely().toJsonObject()
                val message = errorPayload.optString("error")
                    .ifBlank { errorPayload.optString("message") }
                    .ifBlank { "Не удалось экспортировать данные." }
                Log.e("AloApiClient", "HTTP $status $method ${safeLogUrl(fullUrl)} :: $message")
                throw AloApiException(
                    status = status,
                    code = errorPayload.optString("code").ifBlank { null },
                    message = message,
                    payload = errorPayload
                )
            }

            val advertisedSize = connection.contentLengthLong
            if (advertisedSize > MAX_ACCOUNT_EXPORT_BYTES) {
                throw IOException("Экспорт слишком большой для сохранения на этом устройстве.")
            }
            var copied = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_ACCOUNT_EXPORT_BYTES) {
                            throw IOException("Экспорт слишком большой для сохранения на этом устройстве.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (copied == 0L) {
                throw IOException("Сервер вернул пустой экспорт.")
            }
            val detectedFileName = attachmentFileName(connection.getHeaderField("Content-Disposition"))
                .ifBlank { fallbackFileName }
            val metadata = AloAccountExport(
                fileName = sanitizeExportFileName(detectedFileName),
                mimeType = connection.contentType
                    ?.substringBefore(';')
                    ?.takeIf { it.isNotBlank() }
                    ?: "application/json",
                generatedAt = Instant.now().toString()
            )
            val finalized = File(exportDirectory, "${UUID.randomUUID()}.json")
            if (!temporary.renameTo(finalized)) {
                temporary.copyTo(finalized, overwrite = true)
                temporary.delete()
            }
            Log.d("AloApiClient", "HTTP $status $method ${safeLogUrl(fullUrl)} :: account export received")
            AloAccountExportPayload(export = metadata, file = finalized)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun registerE2eeDeviceKeyIfPossible(userId: Int) {
        val crypto = e2eeCrypto ?: return
        if (userId <= 0) return
        runCatching {
            val response = request(
                method = "PUT",
                path = "/api/e2ee/device-key",
                body = JSONObject()
                    .put("deviceId", crypto.deviceId)
                    .put("publicKeyJwk", crypto.localPublicKeyJwk())
                    .put("signingPublicKeyJwk", crypto.localSigningPublicKeyJwk())
                    .put("identitySignature", crypto.localIdentitySignature())
                    .put("algorithm", AloE2eeCrypto.SCHEME)
            )
            latestE2eeWarning = if (response.optBoolean("keyChanged")) {
                "Ключ шифрования этого устройства изменился. Проверьте безопасность активных диалогов."
            } else {
                null
            }
            runCatching {
                publishE2eeOneTimePreKeysIfNeeded(
                    crypto = crypto,
                    serverAvailable = response.optInt("preKeysAvailable", 0),
                    recommendedMinimum = response.optInt("preKeysRecommendedMinimum", 12)
                )
            }.onFailure { error ->
                Log.w("AloApiClient", "E2EE prekey replenishment postponed", error)
            }
        }.onFailure { error ->
            if (error is AloApiException && (
                    error.code == "E2EE_DEVICE_LIMIT_REACHED" ||
                        error.code == "E2EE_IDENTITY_DOWNGRADE_BLOCKED"
                    )
            ) {
                latestE2eeWarning = error.message
            }
        }
    }

    private suspend fun registerLibSignalDeviceIfPossible(userId: Int) {
        val legacyCrypto = e2eeCrypto ?: return
        val preferences = libSignalPreferences ?: return
        if (userId <= 0) return
        runCatching {
            val slot = request(
                method = "PUT",
                path = "/api/e2ee/libsignal/device-slot",
                body = JSONObject().put("deviceId", legacyCrypto.deviceId)
            )
            val protocolDeviceId = slot.optInt("protocolDeviceId")
            if (protocolDeviceId !in 1..127) error("Invalid libsignal protocol device slot")

            if (
                libSignalEngine == null ||
                libSignalUserId != userId ||
                libSignalProtocolDeviceId != protocolDeviceId
            ) {
                val store = AloLibSignalStore(preferences, namespace = "user-$userId")
                libSignalStore = store
                libSignalEngine = AloLibSignalSessionEngine(
                    store = store,
                    localUserId = userId,
                    localDeviceId = protocolDeviceId
                )
                libSignalUserId = userId
                libSignalProtocolDeviceId = protocolDeviceId
            }

            val markerKey = "alo.android.libsignal.v1.registered.$userId.$protocolDeviceId"
            val localRegistrationKnown = preferences.getBoolean(markerKey, false)
            val serverAvailable = slot.optInt("preKeysAvailable", 0).coerceAtLeast(0)
            val recommended = slot.optInt("preKeysRecommendedMinimum", 20).coerceIn(1, 80)
            if (slot.optBoolean("registered") && localRegistrationKnown && serverAvailable >= recommended) {
                return@runCatching
            }

            val targetPool = recommended.coerceAtLeast(24)
            val amount = (targetPool - serverAvailable).coerceIn(1, 80)
            val bundle = checkNotNull(libSignalEngine).generateDeviceBundle(amount)
            val response = request(
                method = "PUT",
                path = "/api/e2ee/libsignal/device-bundle",
                body = JSONObject()
                    .put("deviceId", legacyCrypto.deviceId)
                    .put(
                        "bundle",
                        JSONObject()
                            .put("protocolDeviceId", bundle.protocolDeviceId)
                            .put("registrationId", bundle.registrationId)
                            .put("identityKey", libSignalBase64(bundle.identityKey))
                            .put("signedPreKeyId", bundle.signedPreKeyId)
                            .put("signedPreKey", libSignalBase64(bundle.signedPreKey))
                            .put("signedPreKeySignature", libSignalBase64(bundle.signedPreKeySignature))
                            .put(
                                "preKeys",
                                JSONArray().also { array ->
                                    bundle.preKeys.forEach { preKey ->
                                        array.put(
                                            JSONObject()
                                                .put("preKeyId", preKey.preKeyId)
                                                .put("preKey", libSignalBase64(preKey.preKey))
                                                .put("kyberPreKeyId", preKey.kyberPreKeyId)
                                                .put("kyberPreKey", libSignalBase64(preKey.kyberPreKey))
                                                .put(
                                                    "kyberPreKeySignature",
                                                    libSignalBase64(preKey.kyberPreKeySignature)
                                                )
                                        )
                                    }
                                }
                            )
                    )
            )
            check(preferences.edit().putBoolean(markerKey, true).commit()) {
                "Unable to persist libsignal registration marker"
            }
            if (response.optBoolean("keyChanged")) {
                latestE2eeWarning =
                    "Libsignal identity этого устройства изменилась. Проверьте safety number активных диалогов."
            }
        }.onFailure { error ->
            if (error is AloApiException && (
                    error.code == "E2EE_DEVICE_LIMIT_REACHED" ||
                        error.code == "LIBSIGNAL_DEVICE_SLOT_MISMATCH" ||
                        error.code == "LIBSIGNAL_PREKEY_ID_COLLISION"
                    )
            ) {
                latestE2eeWarning = error.message
            } else {
                Log.w("AloApiClient", "Libsignal device registration deferred", error)
            }
        }
    }

    private fun libSignalBase64(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun libSignalDecode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun parseLibSignalDevice(userId: Int, source: JSONObject): AloLibSignalRemoteDevice? {
        val deviceId = source.optString("deviceId").trim()
        val protocolDeviceId = source.optInt("protocolDeviceId")
        val registrationId = source.optInt("registrationId")
        val identityKey = runCatching { libSignalDecode(source.optString("identityKey")) }.getOrNull()
        val signedPreKeyId = source.optInt("signedPreKeyId")
        val signedPreKey = runCatching { libSignalDecode(source.optString("signedPreKey")) }.getOrNull()
        val signedPreKeySignature = runCatching {
            libSignalDecode(source.optString("signedPreKeySignature"))
        }.getOrNull()
        if (
            userId <= 0 ||
            deviceId.isBlank() ||
            protocolDeviceId !in 1..127 ||
            registrationId !in 1..16_380 ||
            identityKey?.size != 33 ||
            signedPreKeyId <= 0 ||
            signedPreKey?.size != 33 ||
            signedPreKeySignature?.size != 64
        ) {
            return null
        }
        val oneTime = source.optJSONObject("oneTimePreKey")
        val preKeyBundle = oneTime?.let { preKey ->
            val preKeyId = preKey.optInt("preKeyId")
            val preKeyBytes = runCatching { libSignalDecode(preKey.optString("preKey")) }.getOrNull()
            val kyberPreKeyId = preKey.optInt("kyberPreKeyId")
            val kyberPreKey = runCatching { libSignalDecode(preKey.optString("kyberPreKey")) }.getOrNull()
            val kyberSignature = runCatching {
                libSignalDecode(preKey.optString("kyberPreKeySignature"))
            }.getOrNull()
            if (
                preKeyId <= 0 ||
                preKeyBytes?.size != 33 ||
                kyberPreKeyId <= 0 ||
                kyberPreKey == null ||
                kyberPreKey.isEmpty() ||
                kyberSignature?.size != 64
            ) {
                null
            } else {
                AloLibSignalPreKeyBundle(
                    registrationId = registrationId,
                    deviceId = protocolDeviceId,
                    identityKey = identityKey,
                    preKeyId = preKeyId,
                    preKey = preKeyBytes,
                    signedPreKeyId = signedPreKeyId,
                    signedPreKey = signedPreKey,
                    signedPreKeySignature = signedPreKeySignature,
                    kyberPreKeyId = kyberPreKeyId,
                    kyberPreKey = kyberPreKey,
                    kyberPreKeySignature = kyberSignature
                )
            }
        }
        return AloLibSignalRemoteDevice(
            userId = userId,
            deviceId = deviceId,
            protocolDeviceId = protocolDeviceId,
            identityKey = identityKey,
            identityFingerprint = source.optString("identityFingerprint").trim().lowercase(),
            preKeyBundle = preKeyBundle
        )
    }

    private fun validateLibSignalIdentity(device: AloLibSignalRemoteDevice) {
        val preferences = libSignalPreferences ?: return
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(device.identityKey)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val computed = hex.take(60).chunked(5).joinToString("-")
        if (device.identityFingerprint.isNotBlank() && device.identityFingerprint != computed) {
            throw AloApiException(
                status = -1,
                code = "LIBSIGNAL_IDENTITY_FINGERPRINT_INVALID",
                message = "Отпечаток libsignal identity не прошёл локальную проверку.",
                payload = JSONObject()
            )
        }
        val storageKey =
            "alo.android.libsignal.v1.trust.$currentUserId.${device.userId}.${device.deviceId}"
        val previous = preferences.getString(storageKey, null)?.trim().orEmpty()
        if (previous.isNotBlank() && previous != computed) {
            throw AloApiException(
                status = -1,
                code = "E2EE_KEY_CHANGED",
                message = "Ключ безопасности устройства собеседника изменился. Проверьте safety number.",
                payload = JSONObject()
            )
        }
        check(preferences.edit().putString(storageKey, computed).commit()) {
            "Unable to persist libsignal identity trust"
        }
    }

    private suspend fun loadLibSignalUserDevices(
        userId: Int,
        includePreKeys: Boolean,
        targets: List<AloLibSignalRemoteDevice> = emptyList()
    ): List<AloLibSignalRemoteDevice> {
        val requesterDeviceId = e2eeCrypto?.deviceId ?: return emptyList()
        val targetQuery = targets.take(128).joinToString(",") { "${it.userId}:${it.deviceId}" }
        val path = buildString {
            append("/api/e2ee/libsignal/bundles/$userId")
            append("?requesterDeviceId=$requesterDeviceId")
            append("&includePreKeys=")
            append(if (includePreKeys) "1" else "0")
            if (targetQuery.isNotBlank()) append("&preKeyTargets=$targetQuery")
        }
        val response = request("GET", path)
        if (!response.optBoolean("migrationReady")) return emptyList()
        val devices = response.optJSONArray("devices") ?: JSONArray()
        return buildList {
            for (index in 0 until devices.length()) {
                parseLibSignalDevice(userId, devices.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private suspend fun loadLibSignalDirectRecipients(peerId: Int): List<AloLibSignalRemoteDevice> {
        val engine = libSignalEngine ?: return emptyList()
        if (peerId <= 0 || currentUserId <= 0) return emptyList()
        val peerBase = loadLibSignalUserDevices(peerId, includePreKeys = false)
        if (peerBase.isEmpty()) return emptyList()
        val selfBase = if (peerId == currentUserId) {
            emptyList()
        } else {
            loadLibSignalUserDevices(currentUserId, includePreKeys = false)
        }
        val base = (peerBase + selfBase).filterNot {
            it.userId == currentUserId &&
                it.deviceId == e2eeCrypto?.deviceId &&
                it.protocolDeviceId == libSignalProtocolDeviceId
        }
        // A session made with other keys than the server now shows (a new browser in a revoked one's slot) is dropped
        // by `sessionMatches`, so that device is fetched with a fresh bundle like one never written to.
        val missing = base.filterNot {
            engine.sessionMatches(it.userId, it.protocolDeviceId, it.identityKey)
        }
        if (missing.isEmpty()) return base

        val missingByUser = missing.groupBy { it.userId }
        val refreshed = mutableListOf<AloLibSignalRemoteDevice>()
        for ((userId, userTargets) in missingByUser) {
            refreshed += loadLibSignalUserDevices(userId, includePreKeys = true, targets = userTargets)
        }
        val refreshedByAddress = refreshed.associateBy { "${it.userId}:${it.deviceId}" }
        return base.map { device -> refreshedByAddress["${device.userId}:${device.deviceId}"] ?: device }
    }

    private suspend fun loadLibSignalGroupRecipients(roomId: Int): AloLibSignalGroupRecipients {
        val engine = libSignalEngine ?: return AloLibSignalGroupRecipients(roomId, 0, false, emptyList())
        val requesterDeviceId = e2eeCrypto?.deviceId.orEmpty()

        fun parse(response: JSONObject): AloLibSignalGroupRecipients {
            val members = response.optJSONArray("members") ?: JSONArray()
            val devices = mutableListOf<AloLibSignalRemoteDevice>()
            var complete = members.length() > 0
            for (memberIndex in 0 until members.length()) {
                val member = members.optJSONObject(memberIndex) ?: continue
                val userId = member.optInt("userId")
                if (!member.optBoolean("migrationReady")) complete = false
                val memberDevices = member.optJSONArray("devices") ?: JSONArray()
                var validDevices = 0
                for (deviceIndex in 0 until memberDevices.length()) {
                    parseLibSignalDevice(userId, memberDevices.optJSONObject(deviceIndex) ?: continue)?.let {
                        devices += it
                        validDevices += 1
                    }
                }
                if (validDevices == 0) complete = false
            }
            return AloLibSignalGroupRecipients(
                roomId = response.optInt("roomId", roomId),
                epoch = response.optInt("epoch"),
                complete = complete,
                devices = devices
            )
        }

        repeat(2) {
            val base = parse(
                request(
                    "GET",
                    "/api/e2ee/libsignal/group-bundles/$roomId?requesterDeviceId=$requesterDeviceId&includePreKeys=0"
                )
            )
            if (!base.complete || base.epoch <= 0) return base
            val activeDevices = base.devices.filterNot {
                it.userId == currentUserId &&
                    it.deviceId == requesterDeviceId &&
                    it.protocolDeviceId == libSignalProtocolDeviceId
            }
            val missing = activeDevices.filterNot {
                engine.hasSession(it.userId, it.protocolDeviceId)
            }
            if (missing.isEmpty()) return base.copy(devices = activeDevices)
            val targets = missing.take(128).joinToString(",") { "${it.userId}:${it.deviceId}" }
            val refreshed = parse(
                request(
                    "GET",
                    "/api/e2ee/libsignal/group-bundles/$roomId?requesterDeviceId=$requesterDeviceId" +
                        "&includePreKeys=1&preKeyTargets=$targets"
                )
            )
            if (refreshed.epoch != base.epoch) return@repeat
            return refreshed.copy(
                devices = refreshed.devices.filterNot {
                    it.userId == currentUserId &&
                        it.deviceId == requesterDeviceId &&
                        it.protocolDeviceId == libSignalProtocolDeviceId
                }
            )
        }
        throw AloApiException(
            status = -1,
            code = "LIBSIGNAL_GROUP_EPOCH_CHANGED",
            message = "Состав группы изменился во время подготовки сообщения. Повторите отправку.",
            payload = JSONObject()
        )
    }

    private suspend fun prepareLibSignalGroupMessagePayload(
        kind: String,
        peerId: Int,
        roomId: Int,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource?
    ): JSONObject? {
        val engine = libSignalEngine ?: return null
        val store = libSignalStore ?: return null
        val preferences = libSignalPreferences ?: return null
        if (kind != "group" && roomId <= 0 && peerId < GROUP_CHAT_PEER_OFFSET) return null
        val resolvedRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
        if (resolvedRoomId <= 0) return null
        val markerKey = "alo.android.libsignal.v1.required.group.$currentUserId.$resolvedRoomId"
        val state = try {
            loadLibSignalGroupRecipients(resolvedRoomId)
        } catch (error: Exception) {
            if (preferences.getBoolean(markerKey, false)) throw error
            return null
        }
        if (!state.complete || state.epoch <= 0 || state.devices.isEmpty() || state.devices.size > 128) {
            if (preferences.getBoolean(markerKey, false)) {
                throw AloApiException(
                    status = -1,
                    code = "LIBSIGNAL_GROUP_KEYS_MISSING",
                    message = "Не все участники группы зарегистрировали libsignal-устройства.",
                    payload = JSONObject()
                )
            }
            return null
        }
        state.devices.forEach(::validateLibSignalIdentity)
        state.devices.forEach { recipient ->
            if (!engine.hasSession(recipient.userId, recipient.protocolDeviceId)) {
                val bundle = recipient.preKeyBundle ?: throw AloApiException(
                    status = -1,
                    code = "LIBSIGNAL_PREKEYS_MISSING",
                    message = "На одном из устройств группы закончились PQXDH-ключи.",
                    payload = JSONObject()
                )
                engine.processRemotePreKeyBundle(recipient.userId, bundle)
            }
        }

        val distributionStorageKey =
            "alo.android.libsignal.v1.sender-key.$currentUserId.$libSignalProtocolDeviceId.$resolvedRoomId.${state.epoch}"
        val distributionId = preferences.getString(distributionStorageKey, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID().also { next ->
                check(preferences.edit().putString(distributionStorageKey, next.toString()).commit()) {
                    "Unable to persist Sender Key distribution id"
                }
            }
        val distribution = engine.createSenderKeyDistribution(distributionId)
        val contextId = "msg_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val markup = AloMessageMarkup.parse(body)
        val plaintext = JSONObject()
            .put("v", 3)
            .put("body", markup.body)
            .put("entities", JSONArray().also { array ->
                markup.entities.forEach { array.put(it.toJson()) }
            })
            .put("attachments", JSONArray().also { array ->
                attachments.forEach { array.put(messageAttachmentJson(it)) }
            })
            .put("sentAt", java.time.Instant.now().toString())
            .also { content ->
                forwardFrom?.let {
                    content.put(
                        "forwardFrom",
                        JSONObject().put("userId", it.userId).put("messageId", it.messageId)
                    )
                }
            }
            .toString()
            .toByteArray(Charsets.UTF_8)
        val distributionEnvelopes = JSONArray()
        state.devices.forEach { recipient ->
            val ciphertext = engine.encrypt(recipient.userId, recipient.protocolDeviceId, distribution)
            distributionEnvelopes.put(
                JSONObject()
                    .put("userId", recipient.userId)
                    .put("deviceId", recipient.deviceId)
                    .put("protocolDeviceId", recipient.protocolDeviceId)
                    .put("ciphertextType", ciphertext.type)
                    .put("ciphertext", libSignalBase64(ciphertext.bytes))
                    .also { envelope ->
                        // A session the other device has not answered yet keeps producing PreKey messages without a fresh
                        // bundle: the ids then stay out of the envelope and the server reads them from the message itself.
                        if (ciphertext.type == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
                            recipient.preKeyBundle?.let { bundle ->
                                envelope.put("preKeyId", bundle.preKeyId)
                                envelope.put("kyberPreKeyId", bundle.kyberPreKeyId)
                            }
                        }
                    }
            )
        }
        val groupCiphertext = engine.groupEncrypt(distributionId, plaintext)
        store.storeMessagePlaintext(contextId, plaintext)
        check(preferences.edit().putBoolean(markerKey, true).commit()) {
            "Unable to persist group libsignal downgrade protection"
        }
        return JSONObject()
            .put("scheme", LIBSIGNAL_SCHEME)
            .put("protocolVersion", 1)
            .put("mode", "sender-key")
            .put("purpose", "message")
            .put("contextId", contextId)
            .put("senderUserId", currentUserId)
            .put("senderDeviceId", e2eeCrypto?.deviceId)
            .put("senderProtocolDeviceId", libSignalProtocolDeviceId)
            .put(
                "group",
                JSONObject()
                    .put("roomId", resolvedRoomId)
                    .put("epoch", state.epoch)
                    .put("distributionId", distributionId.toString().lowercase())
                    .put("ciphertext", libSignalBase64(groupCiphertext))
            )
            .put("recipients", distributionEnvelopes)
    }

    private suspend fun prepareLibSignalDirectMessagePayload(
        kind: String,
        peerId: Int,
        roomId: Int,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource?
    ): JSONObject? {
        val engine = libSignalEngine ?: return null
        val preferences = libSignalPreferences ?: return null
        if (kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET) return null
        val requiredMarker = "alo.android.libsignal.v1.required.direct.$currentUserId.$peerId"
        val recipients = try {
            loadLibSignalDirectRecipients(peerId)
        } catch (error: Exception) {
            if (preferences.getBoolean(requiredMarker, false)) throw error
            Log.w("AloApiClient", "Libsignal recipient discovery deferred", error)
            return null
        }
        val peerRecipients = recipients.filter { it.userId == peerId }
        if (peerRecipients.isEmpty()) {
            if (preferences.getBoolean(requiredMarker, false)) {
                throw AloApiException(
                    status = -1,
                    code = "LIBSIGNAL_KEYS_MISSING",
                    message = "У собеседника пропали ранее подтверждённые libsignal-ключи.",
                    payload = JSONObject()
                )
            }
            return null
        }
        recipients.forEach(::validateLibSignalIdentity)

        val contextId = "msg_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val markup = AloMessageMarkup.parse(body)
        val content = JSONObject()
            .put("v", 3)
            .put("body", markup.body)
            .put("entities", JSONArray().also { array ->
                markup.entities.forEach { array.put(it.toJson()) }
            })
            .put("attachments", JSONArray().also { array ->
                attachments.forEach { array.put(messageAttachmentJson(it)) }
            })
            .put("sentAt", java.time.Instant.now().toString())
        forwardFrom?.let {
            content.put(
                "forwardFrom",
                JSONObject().put("userId", it.userId).put("messageId", it.messageId)
            )
        }
        val plaintext = content.toString().toByteArray(Charsets.UTF_8)
        val encryptedRecipients = JSONArray()
        recipients.forEach { recipient ->
            val hadSession = engine.hasSession(recipient.userId, recipient.protocolDeviceId)
            if (!hadSession) {
                val bundle = recipient.preKeyBundle ?: throw AloApiException(
                    status = -1,
                    code = "LIBSIGNAL_PREKEYS_MISSING",
                    message = "На устройстве получателя закончились одноразовые PQXDH-ключи.",
                    payload = JSONObject()
                )
                validateLibSignalIdentity(recipient)
                engine.processRemotePreKeyBundle(recipient.userId, bundle)
            }
            val ciphertext = engine.encrypt(recipient.userId, recipient.protocolDeviceId, plaintext)
            encryptedRecipients.put(
                JSONObject()
                    .put("userId", recipient.userId)
                    .put("deviceId", recipient.deviceId)
                    .put("protocolDeviceId", recipient.protocolDeviceId)
                    .put("ciphertextType", ciphertext.type)
                    .put("ciphertext", libSignalBase64(ciphertext.bytes))
                    .also { envelope ->
                        // A session the other device has not answered yet keeps producing PreKey messages without a fresh
                        // bundle: the ids then stay out of the envelope and the server reads them from the message itself.
                        if (ciphertext.type == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
                            recipient.preKeyBundle?.let { bundle ->
                                envelope.put("preKeyId", bundle.preKeyId)
                                envelope.put("kyberPreKeyId", bundle.kyberPreKeyId)
                            }
                        }
                    }
            )
        }
        check(preferences.edit().putBoolean(requiredMarker, true).commit()) {
            "Unable to persist libsignal downgrade protection"
        }
        libSignalStore?.storeMessagePlaintext(contextId, plaintext)
        return JSONObject()
            .put("scheme", LIBSIGNAL_SCHEME)
            .put("protocolVersion", 1)
            .put("purpose", "message")
            .put("contextId", contextId)
            .put("senderUserId", currentUserId)
            .put("senderDeviceId", e2eeCrypto?.deviceId)
            .put("senderProtocolDeviceId", libSignalProtocolDeviceId)
            .put("recipients", encryptedRecipients)
    }

    /** [persistPlaintext] is false for call signals: their text only guards against opening the same signal twice. */
    private fun decryptLibSignalPayload(
        encryptedPayload: JSONObject,
        persistPlaintext: Boolean = true
    ): ByteArray {
        val engine = libSignalEngine ?: error("Libsignal engine is not initialized")
        val store = libSignalStore ?: error("Libsignal store is not initialized")
        val contextId = encryptedPayload.optString("contextId").trim()
        if (!persistPlaintext) {
            synchronized(openedCallSignals) { openedCallSignals[contextId] }?.let { return it }
        }
        store.loadMessagePlaintext(contextId)?.let { return it }

        val senderUserId = encryptedPayload.optInt("senderUserId")
        val senderProtocolDeviceId = encryptedPayload.optInt("senderProtocolDeviceId")
        if (senderUserId <= 0 || senderProtocolDeviceId !in 1..127) {
            error("Invalid libsignal sender metadata")
        }
        val localDeviceId = e2eeCrypto?.deviceId.orEmpty()
        val recipients = encryptedPayload.optJSONArray("recipients") ?: JSONArray()
        var selected: JSONObject? = null
        for (index in 0 until recipients.length()) {
            val envelope = recipients.optJSONObject(index) ?: continue
            if (
                envelope.optInt("userId") == currentUserId &&
                envelope.optString("deviceId") == localDeviceId &&
                envelope.optInt("protocolDeviceId") == libSignalProtocolDeviceId
            ) {
                selected = envelope
                break
            }
        }
        val envelope = selected ?: error("No libsignal envelope for this device")
        val ciphertextType = envelope.optInt("ciphertextType")
        val ciphertext = libSignalDecode(envelope.optString("ciphertext"))
        val plaintext = if (encryptedPayload.optString("mode") == "sender-key") {
            val distributionContextId = "dist_$contextId"
            val distribution = store.loadMessagePlaintext(distributionContextId) ?: engine.decrypt(
                remoteUserId = senderUserId,
                remoteDeviceId = senderProtocolDeviceId,
                ciphertextType = ciphertextType,
                ciphertext = ciphertext
            ).also { opened ->
                store.storeMessagePlaintext(distributionContextId, opened)
            }
            engine.processSenderKeyDistribution(
                remoteUserId = senderUserId,
                remoteDeviceId = senderProtocolDeviceId,
                serialized = distribution
            )
            val group = encryptedPayload.optJSONObject("group") ?: error("Missing Sender Key payload")
            val distributionId = UUID.fromString(group.optString("distributionId"))
            check(distributionId.toString().equals(group.optString("distributionId"), ignoreCase = true))
            engine.groupDecrypt(
                remoteUserId = senderUserId,
                remoteDeviceId = senderProtocolDeviceId,
                ciphertext = libSignalDecode(group.optString("ciphertext"))
            )
        } else {
            engine.decrypt(
                remoteUserId = senderUserId,
                remoteDeviceId = senderProtocolDeviceId,
                ciphertextType = ciphertextType,
                ciphertext = ciphertext
            )
        }
        if (persistPlaintext) {
            store.storeMessagePlaintext(contextId, plaintext)
        } else {
            synchronized(openedCallSignals) { openedCallSignals[contextId] = plaintext }
        }
        return plaintext
    }

    /**
     * The devices a call signal is encrypted for. A call sends dozens of signals (every ICE candidate is one); fetching
     * both people's device lists for each of them queued the signals behind each other for tens of seconds on a phone
     * network, so ICE and media-state signals reuse the list for a short while; it is dropped as soon as a send fails.
     */
    private suspend fun callSignalRecipients(
        peerId: Int,
        reuseRecipients: Boolean
    ): List<AloLibSignalRemoteDevice> {
        val now = System.currentTimeMillis()
        if (reuseRecipients) {
            synchronized(callSignalRecipientsCache) {
                callSignalRecipientsCache[peerId]?.let { (loadedAt, recipients) ->
                    if (now - loadedAt < CALL_SIGNAL_RECIPIENTS_TTL_MS) return recipients
                }
            }
        }
        val recipients = loadLibSignalDirectRecipients(peerId)
        synchronized(callSignalRecipientsCache) {
            callSignalRecipientsCache[peerId] = now to recipients
        }
        return recipients
    }

    private fun forgetCallSignalRecipients(peerId: Int) {
        synchronized(callSignalRecipientsCache) { callSignalRecipientsCache.remove(peerId) }
    }

    private suspend fun prepareLibSignalDirectCallPayload(
        peerId: Int,
        payload: JSONObject,
        reuseRecipients: Boolean = false
    ): JSONObject? {
        val engine = libSignalEngine ?: return null
        val store = libSignalStore ?: return null
        val preferences = libSignalPreferences ?: return null
        val markerKey = "alo.android.libsignal.v1.required.direct.$currentUserId.$peerId"
        val recipients = try {
            callSignalRecipients(peerId, reuseRecipients)
        } catch (error: Exception) {
            if (preferences.getBoolean(markerKey, false)) throw error
            return null
        }
        if (recipients.none { it.userId == peerId }) {
            if (preferences.getBoolean(markerKey, false)) {
                throw AloApiException(
                    status = -1,
                    code = "LIBSIGNAL_KEYS_MISSING",
                    message = "У собеседника пропали ранее подтверждённые libsignal-ключи.",
                    payload = JSONObject()
                )
            }
            return null
        }
        recipients.forEach(::validateLibSignalIdentity)
        val contextId = "call_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val plaintext = JSONObject()
            .put("v", 3)
            .put("type", "directCallSignal")
            .put("payload", payload)
            .put("sentAt", java.time.Instant.now().toString())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val encryptedRecipients = JSONArray()
        var skippedKeyless = 0
        var reachesPeer = false
        recipients.forEach { recipient ->
            val hadSession = engine.hasSession(recipient.userId, recipient.protocolDeviceId)
            if (!hadSession) {
                // A device with no keys left (a forgotten browser, an app not opened for a long time) cannot start a
                // session. It is left out of this call and the person's other devices still ring, as on the website and
                // the iPhone; the call fails only when none of the person's devices can be reached (below).
                val bundle = recipient.preKeyBundle
                if (bundle == null) {
                    skippedKeyless += 1
                    return@forEach
                }
                validateLibSignalIdentity(recipient)
                engine.processRemotePreKeyBundle(recipient.userId, bundle)
            }
            if (recipient.userId == peerId) reachesPeer = true
            val ciphertext = engine.encrypt(recipient.userId, recipient.protocolDeviceId, plaintext)
            encryptedRecipients.put(
                JSONObject()
                    .put("userId", recipient.userId)
                    .put("deviceId", recipient.deviceId)
                    .put("protocolDeviceId", recipient.protocolDeviceId)
                    .put("ciphertextType", ciphertext.type)
                    .put("ciphertext", libSignalBase64(ciphertext.bytes))
                    .also { envelope ->
                        // A session the other device has not answered yet keeps producing PreKey messages without a fresh
                        // bundle: the ids then stay out of the envelope and the server reads them from the message itself.
                        if (ciphertext.type == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
                            recipient.preKeyBundle?.let { bundle ->
                                envelope.put("preKeyId", bundle.preKeyId)
                                envelope.put("kyberPreKeyId", bundle.kyberPreKeyId)
                            }
                        }
                    }
            )
        }
        if (!reachesPeer) {
            throw AloApiException(
                status = -1,
                code = "LIBSIGNAL_PREKEYS_MISSING",
                message = if (skippedKeyless > 0) {
                    "Ни одно устройство собеседника сейчас не может принять звонок: у них закончились ключи. Как только собеседник откроет Alo, звонок заработает."
                } else {
                    "Не удалось подготовить защищённый сигнал для собеседника."
                },
                payload = JSONObject()
            )
        }
        // A call signal is never shown again, so its text is not kept (each one used to rewrite the whole preferences file).
        if (!preferences.getBoolean(markerKey, false)) {
            check(preferences.edit().putBoolean(markerKey, true).commit()) {
                "Unable to persist libsignal downgrade protection"
            }
        }
        return JSONObject()
            .put("scheme", LIBSIGNAL_SCHEME)
            .put("protocolVersion", 1)
            .put("purpose", "call")
            .put("contextId", contextId)
            .put("senderUserId", currentUserId)
            .put("senderDeviceId", e2eeCrypto?.deviceId)
            .put("senderProtocolDeviceId", libSignalProtocolDeviceId)
            .put("recipients", encryptedRecipients)
    }

    private suspend fun publishE2eeOneTimePreKeysIfNeeded(
        crypto: AloE2eeCrypto,
        serverAvailable: Int,
        recommendedMinimum: Int
    ) {
        if (serverAvailable >= recommendedMinimum.coerceAtLeast(1)) return
        val preKeys = crypto.prepareOneTimePreKeysForUpload(
            serverAvailable = serverAvailable,
            targetCount = recommendedMinimum.coerceAtLeast(24)
        )
        if (preKeys.isEmpty()) return
        request(
            method = "PUT",
            path = "/api/e2ee/device-prekeys",
            body = JSONObject()
                .put("deviceId", crypto.deviceId)
                .put("keys", JSONArray().also { array ->
                    preKeys.forEach { preKey ->
                        array.put(
                            JSONObject()
                                .put("keyId", preKey.keyId)
                                .put("publicKeyJwk", preKey.publicKeyJwk)
                                .put("signature", preKey.signature)
                                .put("signatureVersion", preKey.signatureVersion)
                                .put("algorithm", preKey.algorithm)
                        )
                    }
                })
        )
        crypto.markOneTimePreKeysPublished(preKeys.map { it.keyId })
    }

    suspend fun prepareEncryptedMessagePayload(
        kind: String,
        peerId: Int,
        roomId: Int,
        body: String,
        attachments: List<AloOutgoingMessageAttachment>,
        forwardFrom: AloMessageForwardSource?
    ): JSONObject {
        val crypto = e2eeCrypto ?: throw AloApiException(
            status = -1,
            code = "E2EE_UNAVAILABLE",
            message = "Шифрование сообщений недоступно на этом устройстве.",
            payload = JSONObject()
        )
        if (currentUserId > 0) {
            registerE2eeDeviceKeyIfPossible(currentUserId)
            registerLibSignalDeviceIfPossible(currentUserId)
        }
        prepareLibSignalGroupMessagePayload(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            body = body,
            attachments = attachments,
            forwardFrom = forwardFrom
        )?.let { return it }
        prepareLibSignalDirectMessagePayload(
            kind = kind,
            peerId = peerId,
            roomId = roomId,
            body = body,
            attachments = attachments,
            forwardFrom = forwardFrom
        )?.let { return it }
        val recipientKeys = loadE2eeRecipientKeys(kind = kind, peerId = peerId, roomId = roomId)
        if (recipientKeys.isEmpty()) {
            throw AloApiException(
                status = -1,
                code = "E2EE_KEYS_MISSING",
                message = "Не удалось получить ключи шифрования для этого чата.",
                payload = JSONObject()
            )
        }
        try {
            e2eeTrustStore?.validate(recipientKeys, currentUserId)
        } catch (error: AloApiException) {
            latestE2eeWarning = error.message
            throw error
        }

        var useV2 = recipientKeys.all {
            it.signingPublicKeyJwk != null && it.identityFingerprint.isNotBlank()
        }
        val isSavedMessages = kind != "group" && roomId <= 0 && peerId == currentUserId
        val encryptionContextId = "msg_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val markup = AloMessageMarkup.parse(body)
        val content = JSONObject()
            .put("v", if (useV2) 2 else 1)
            .put("body", markup.body)
            .put("entities", JSONArray().also { array ->
                markup.entities.forEach { array.put(it.toJson()) }
            })
            .put("attachments", JSONArray().also { array ->
                attachments.forEach { array.put(messageAttachmentJson(it)) }
            })
            .put("sentAt", java.time.Instant.now().toString())
        forwardFrom?.let {
            content.put(
                "forwardFrom",
                JSONObject()
                    .put("userId", it.userId)
                    .put("messageId", it.messageId)
            )
        }

        val plaintext = content.toString().toByteArray(Charsets.UTF_8)
        val recipients = runCatching {
            recipientKeys.map { key ->
                crypto.encrypt(
                    plaintext = plaintext,
                    recipient = key,
                    senderUserId = currentUserId,
                    purpose = "message",
                    contextId = encryptionContextId,
                    useV2 = useV2,
                    allowRatchetBootstrap = !isSavedMessages
                )
            }
        }.getOrElse { error ->
            if (!isSavedMessages || !useV2) throw error
            useV2 = false
            recipientKeys.map { key ->
                crypto.encrypt(
                    plaintext = plaintext,
                    recipient = key.copy(oneTimePreKey = null),
                    senderUserId = currentUserId,
                    purpose = "message",
                    contextId = encryptionContextId,
                    useV2 = false
                )
            }
        }
        return JSONObject()
            .put("scheme", if (useV2) AloE2eeCrypto.MESSAGE_SCHEME_V2 else AloE2eeCrypto.SCHEME)
            .put("recipients", JSONArray().also { array -> recipients.forEach(array::put) })
    }

    private suspend fun prepareEncryptedTopicDraft(body: String): JSONObject {
        val crypto = e2eeCrypto ?: throw AloApiException(
            status = -1,
            code = "E2EE_UNAVAILABLE",
            message = "Шифрование черновика недоступно на этом устройстве.",
            payload = JSONObject()
        )
        if (currentUserId > 0) {
            registerE2eeDeviceKeyIfPossible(currentUserId)
        }
        val recipientKeys = loadE2eeRecipientKeys(
            kind = "direct",
            peerId = currentUserId,
            roomId = 0
        ).filter {
            it.userId == currentUserId &&
                it.signingPublicKeyJwk != null &&
                it.identityFingerprint.isNotBlank()
        }
        if (recipientKeys.isEmpty()) {
            throw AloApiException(
                status = -1,
                code = "E2EE_KEYS_MISSING",
                message = "Не удалось получить ключи собственных устройств.",
                payload = JSONObject()
            )
        }
        e2eeTrustStore?.validate(recipientKeys, currentUserId)
        val contextId = "draft_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val plaintext = JSONObject()
            .put("v", 1)
            .put("body", body)
            .put("updatedAt", Instant.now().toString())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val recipients = recipientKeys.map { key ->
            crypto.encrypt(
                plaintext = plaintext,
                recipient = key,
                senderUserId = currentUserId,
                purpose = "draft",
                contextId = contextId,
                useV2 = true,
                allowRatchetBootstrap = false
            )
        }
        return JSONObject()
            .put("scheme", AloE2eeCrypto.MESSAGE_SCHEME_V2)
            .put("recipients", JSONArray().also { array -> recipients.forEach(array::put) })
    }

    private suspend fun buildEncryptedDirectCallSignalPayload(
        peerId: Int,
        payload: JSONObject,
        reuseRecipients: Boolean = false
    ): JSONObject {
        val crypto = e2eeCrypto ?: throw AloApiException(
            status = -1,
            code = "E2EE_UNAVAILABLE",
            message = "Защищённый сигнал звонка недоступен на этом устройстве.",
            payload = JSONObject()
        )
        prepareLibSignalDirectCallPayload(peerId, payload, reuseRecipients)?.let { encrypted ->
            return JSONObject()
                .put("format", "alo-direct-call-signal-e2ee-v1")
                .put("encrypted", encrypted)
        }
        val recipientKeys = loadE2eeRecipientKeys(kind = "direct", peerId = peerId, roomId = 0)
        val verifiableRecipientKeys = recipientKeys.filter {
            it.signingPublicKeyJwk != null && it.identityFingerprint.isNotBlank()
        }
        val recipientUserIds = verifiableRecipientKeys.map { it.userId }.toSet()
        if (
            peerId !in recipientUserIds ||
            (currentUserId > 0 && currentUserId !in recipientUserIds)
        ) {
            throw AloApiException(
                status = -1,
                code = "E2EE_KEYS_MISSING",
                message = "Собеседник ещё регистрирует ключи защищённого звонка.",
                payload = JSONObject()
            )
        }
        try {
            e2eeTrustStore?.validate(verifiableRecipientKeys, currentUserId)
        } catch (error: AloApiException) {
            latestE2eeWarning = error.message
            throw error
        }
        val encryptionContextId = "call_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
        val content = JSONObject()
            .put("v", 2)
            .put("type", "directCallSignal")
            .put("payload", payload)
            .put("sentAt", java.time.Instant.now().toString())
        val plaintext = content.toString().toByteArray(Charsets.UTF_8)
        val encryptedPayload = JSONObject()
            .put("scheme", AloE2eeCrypto.MESSAGE_SCHEME_V2)
            .put("recipients", JSONArray().also { array ->
                verifiableRecipientKeys.forEach { key ->
                    array.put(
                        crypto.encrypt(
                            plaintext = plaintext,
                            recipient = key,
                            senderUserId = currentUserId,
                            purpose = "call",
                            contextId = encryptionContextId,
                            useV2 = true
                        )
                    )
                }
            })

        return JSONObject()
            .put("format", "alo-direct-call-signal-e2ee-v1")
            .put("encrypted", encryptedPayload)
    }

    private fun decryptDirectCallSignalPayload(payload: JSONObject): JSONObject {
        val encryptedPayload = payload.optJSONObject("encrypted") ?: return payload
        val crypto = e2eeCrypto ?: return JSONObject()
        return runCatching {
            val decryptedBytes = if (encryptedPayload.optString("scheme") == LIBSIGNAL_SCHEME) {
                decryptLibSignalPayload(encryptedPayload, persistPlaintext = false)
            } else {
                e2eeTrustStore?.validateEmbeddedSender(encryptedPayload, currentUserId)
                crypto.decrypt(encryptedPayload)
            }
            val decrypted = JSONObject(String(decryptedBytes, Charsets.UTF_8))
            decrypted.optJSONObject("payload") ?: decrypted
        }.getOrElse {
            JSONObject()
        }
    }

    private suspend fun loadE2eeRecipientKeys(
        kind: String,
        peerId: Int,
        roomId: Int
    ): List<AloE2eeRecipientKey> {
        val crypto = e2eeCrypto ?: throw AloApiException(
            status = -1,
            code = "E2EE_UNAVAILABLE",
            message = "Шифрование сообщений недоступно на этом устройстве.",
            payload = JSONObject()
        )

        fun parseGroupKeys(response: JSONObject): List<AloE2eeRecipientKey> {
            val members = response.optJSONArray("members") ?: JSONArray()
            return buildList {
                for (index in 0 until members.length()) {
                    val member = members.optJSONObject(index) ?: continue
                    val userId = member.optInt("userId")
                    val keys = member.optJSONArray("keys") ?: JSONArray()
                    for (keyIndex in 0 until keys.length()) {
                        val key = keys.optJSONObject(keyIndex) ?: continue
                        val deviceId = key.optString("deviceId")
                        val publicKeyJwk = key.optJSONObject("publicKeyJwk") ?: continue
                        if (userId > 0 && deviceId.isNotBlank()) {
                            add(
                                AloE2eeRecipientKey(
                                    userId = userId,
                                    deviceId = deviceId,
                                    publicKeyJwk = publicKeyJwk,
                                    fingerprint = key.optString("fingerprint").trim(),
                                    signingPublicKeyJwk = key.optJSONObject("signingPublicKeyJwk"),
                                    identityFingerprint = key.optString("identityFingerprint").trim(),
                                    oneTimePreKey = parseE2eeOneTimePreKey(key.optJSONObject("oneTimePreKey"))
                                )
                            )
                        }
                    }
                }
            }
        }

        fun parseUserKeys(userId: Int, response: JSONObject): List<AloE2eeRecipientKey> {
            val keys = response.optJSONArray("keys") ?: JSONArray()
            return buildList {
                for (index in 0 until keys.length()) {
                    val key = keys.optJSONObject(index) ?: continue
                    val deviceId = key.optString("deviceId")
                    val publicKeyJwk = key.optJSONObject("publicKeyJwk") ?: continue
                    if (userId > 0 && deviceId.isNotBlank()) {
                        add(
                            AloE2eeRecipientKey(
                                userId = userId,
                                deviceId = deviceId,
                                publicKeyJwk = publicKeyJwk,
                                fingerprint = key.optString("fingerprint").trim(),
                                signingPublicKeyJwk = key.optJSONObject("signingPublicKeyJwk"),
                                identityFingerprint = key.optString("identityFingerprint").trim(),
                                oneTimePreKey = parseE2eeOneTimePreKey(key.optJSONObject("oneTimePreKey"))
                            )
                        )
                    }
                }
            }
        }

        fun missingTargets(keys: List<AloE2eeRecipientKey>): List<AloE2eeRecipientKey> =
            keys.filter { key ->
                crypto.hasConfirmedRatchetSession(
                    key.userId,
                    key.deviceId,
                    useV2 = key.signingPublicKeyJwk != null && key.identityFingerprint.isNotBlank()
                ).not()
            }

        fun targetQuery(keys: List<AloE2eeRecipientKey>): String =
            keys.take(64).joinToString(",") { key -> "${key.userId}:${key.deviceId}" }

        val requesterDeviceId = crypto.deviceId

        val isGroup = kind == "group" || roomId > 0 || peerId >= GROUP_CHAT_PEER_OFFSET
        if (isGroup) {
            val resolvedRoomId = groupRoomIdFrom(peerId = peerId, roomId = roomId)
            val baseKeys = parseGroupKeys(request("GET", "/api/e2ee/group-keys/$resolvedRoomId"))
            val missing = missingTargets(baseKeys)
            if (missing.isEmpty()) return baseKeys
            val response = request(
                "GET",
                "/api/e2ee/group-keys/$resolvedRoomId?includePreKeys=1&requesterDeviceId=$requesterDeviceId&preKeyTargets=${targetQuery(missing)}"
            )
            return parseGroupKeys(response)
        }

        suspend fun loadUserKeys(userId: Int): List<AloE2eeRecipientKey> {
            val baseKeys = parseUserKeys(userId, request("GET", "/api/e2ee/keys/$userId"))
            val missing = missingTargets(baseKeys)
            if (missing.isEmpty()) return baseKeys
            return parseUserKeys(
                userId,
                request(
                    "GET",
                    "/api/e2ee/keys/$userId?includePreKeys=1&requesterDeviceId=$requesterDeviceId&preKeyTargets=${targetQuery(missing)}"
                )
            )
        }

        val collected = mutableListOf<AloE2eeRecipientKey>()
        collected += loadUserKeys(peerId)
        if (peerId == currentUserId) {
            val signedKeys = collected.filter { key ->
                key.signingPublicKeyJwk != null && key.identityFingerprint.isNotBlank()
            }
            if (signedKeys.isNotEmpty()) {
                collected.clear()
                collected += signedKeys
            }
        }
        if (currentUserId > 0 && currentUserId != peerId) {
            collected += loadUserKeys(currentUserId)
        }
        val userIds = collected.map { it.userId }.toSet()
        if (!userIds.contains(peerId) || (currentUserId > 0 && !userIds.contains(currentUserId))) {
            throw AloApiException(
                status = -1,
                code = "E2EE_KEYS_MISSING",
                message = "У одного из участников чата ещё нет ключа шифрования. Откройте Alo на обоих устройствах и попробуйте снова.",
                payload = JSONObject()
            )
        }
        return collected
    }

    private fun parseE2eeOneTimePreKey(json: JSONObject?): AloE2eeOneTimePreKey? {
        val source = json ?: return null
        val keyId = source.optString("keyId").trim()
        val publicKeyJwk = source.optJSONObject("publicKeyJwk") ?: return null
        val signature = source.optString("signature").trim()
        if (keyId.isBlank() || signature.isBlank()) return null
        return AloE2eeOneTimePreKey(
            keyId = keyId,
            publicKeyJwk = publicKeyJwk,
            signature = signature,
            signatureVersion = source.optInt("signatureVersion", 1),
            algorithm = source.optString("algorithm").ifBlank { AloE2eeCrypto.SCHEME }
        )
    }

    private suspend fun decryptBootstrapResponse(response: JSONObject) {
        val data = response.optJSONObject("data") ?: return
        val messages = data.optJSONObject("messages") ?: return
        decryptMessageArray(messages.optJSONArray("items"))
        val conversations = messages.optJSONArray("conversations") ?: return
        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            val lastMessage = conversation.optJSONObject("lastMessage") ?: continue
            decryptE2eeMessageJson(lastMessage)
            conversationPreview(lastMessage).takeIf(String::isNotBlank)?.let { preview ->
                conversation.put("lastBody", preview)
            }
        }
    }

    private fun conversationPreview(message: JSONObject): String {
        message.optString("body").trim().takeIf(String::isNotBlank)?.let { return it }
        val firstAttachment = message.optJSONArray("attachments")?.optJSONObject(0)
        val type = message.optString("attachmentType")
            .ifBlank { firstAttachment?.optString("type").orEmpty() }
            .trim()
            .lowercase()
        return when {
            type == "voice" || type.startsWith("audio") -> "Голосовое сообщение"
            type == "circle" || type == "video_circle" || type == "video-circle" -> "Видеосообщение"
            type == "call" -> "Звонок"
            AloCloudMessages.attachmentPreview(type) != null -> AloCloudMessages.attachmentPreview(type).orEmpty()
            type == "image" || type.startsWith("image/") -> "Фото"
            type == "video" || type.startsWith("video/") -> "Видео"
            // A document is named by its file, as on the website, the iPhone and in Telegram.
            type == "file" && firstAttachment?.optString("name").orEmpty().let { it.isNotBlank() && it != "encrypted.bin" } ->
                firstAttachment?.optString("name").orEmpty()
            type == "file" || type.isNotBlank() || firstAttachment != null -> "Вложение"
            else -> ""
        }
    }

    private suspend fun decryptConversationResponse(response: JSONObject) {
        decryptMessageArray(response.optJSONArray("items"))
        decryptForumTopicStates(response.optJSONObject("room")?.optJSONArray("topicStates"))
    }

    private suspend fun decryptForumTopicStates(states: JSONArray?) {
        val array = states ?: return
        for (index in 0 until array.length()) {
            decryptForumTopicState(array.optJSONObject(index) ?: continue)
        }
    }

    private fun decryptForumTopicState(state: JSONObject) {
        val encryptedDraft = state.optJSONObject("encryptedDraft") ?: return
        val crypto = e2eeCrypto ?: return
        runCatching {
            e2eeTrustStore?.validateEmbeddedSender(encryptedDraft, currentUserId)
            val decrypted = JSONObject(String(crypto.decrypt(encryptedDraft), Charsets.UTF_8))
            state.put("draftText", decrypted.optString("body"))
        }
    }

    private suspend fun decryptMessageArray(items: JSONArray?) {
        val array = items ?: return
        for (index in 0 until array.length()) {
            decryptE2eeMessageJson(array.optJSONObject(index) ?: continue)
        }
    }

    private suspend fun decryptE2eeMessageJson(message: JSONObject) {
        val crypto = e2eeCrypto ?: return
        val encryptedPayload = message.optJSONObject("encryptedPayload") ?: return
        runCatching {
            val decryptedBytes = if (encryptedPayload.optString("scheme") == LIBSIGNAL_SCHEME) {
                decryptLibSignalPayload(encryptedPayload)
            } else {
                e2eeTrustStore?.validateEmbeddedSender(encryptedPayload, currentUserId)
                crypto.decrypt(encryptedPayload)
            }
            val decrypted = JSONObject(String(decryptedBytes, Charsets.UTF_8))
            message.put("body", decrypted.optString("body"))
            decrypted.optJSONArray("entities")?.let { message.put("entities", it) }
            val attachments = decrypted.optJSONArray("attachments")
            if (attachments != null) {
                val shouldDeferDisappearingMedia =
                    message.optInt("selfDestructSeconds") > 0 &&
                        message.optString("selfDestructStartedAt").isBlank() &&
                        message.optString("expiresAt").isBlank()
                val resolvedAttachments = if (shouldDeferDisappearingMedia) {
                    attachments
                } else {
                    resolveDecryptedMediaAttachments(
                        attachments,
                        expiresAt = message.optString("expiresAt"),
                        // Your own attachments always load; other people's follow Settings → Данные и память.
                        userInitiated = message.optInt("senderId") == currentUserId
                    )
                }
                message.put("attachments", resolvedAttachments)
                val first = resolvedAttachments.optJSONObject(0)
                if (first != null) {
                    message.put("attachmentUrl", first.optString("url"))
                    message.put("attachmentType", first.optString("type"))
                    message.put("attachmentName", first.optString("name"))
                }
            }
            if (!message.has("forwardFrom") && decrypted.has("forwardFrom")) {
                message.put("forwardFrom", decrypted.optJSONObject("forwardFrom"))
            }
        }.onFailure { failure ->
            if (
                (message.optString("encryptionScheme") == AloE2eeCrypto.SCHEME ||
                    message.optString("encryptionScheme") == AloE2eeCrypto.MESSAGE_SCHEME_V2 ||
                    message.optString("encryptionScheme") == LIBSIGNAL_SCHEME) &&
                message.optString("body").isBlank()
            ) {
                // No envelope for this device: the message was sent before the device signed in and stays unreadable.
                val beforeThisDevice = failure.message.orEmpty().contains("envelope for this device")
                message.put(
                    "body",
                    if (beforeThisDevice) "Отправлено до подключения этого устройства" else "Не удалось расшифровать сообщение"
                )
            }
        }
    }

    private suspend fun resolveDecryptedMediaAttachments(
        attachments: JSONArray,
        expiresAt: String = "",
        userInitiated: Boolean = true
    ): JSONArray {
        val resolved = JSONArray()
        for (index in 0 until attachments.length()) {
            val item = attachments.optJSONObject(index)
            resolved.put(
                if (item == null) {
                    attachments.opt(index)
                } else {
                    resolveDecryptedMediaAttachment(item, expiresAt = expiresAt, userInitiated = userInitiated)
                }
            )
        }
        return resolved
    }

    /**
     * Downloads, registers and returns an encrypted attachment. An automatic call ([userInitiated] false) leaves it
     * untouched when the connection's auto-download preset does not allow a file of this size; a tap on it loads it.
     */
    private suspend fun resolveDecryptedMediaAttachment(
        attachment: JSONObject,
        expiresAt: String = "",
        userInitiated: Boolean = true
    ): JSONObject {
        e2eeCrypto ?: return attachment
        val appContext = applicationContext ?: return attachment
        val encryption = attachment.optJSONObject("mediaEncryption") ?: attachment.optJSONObject("e2ee") ?: return attachment
        val mediaScheme = encryption.optString("algorithm")
        if (
            mediaScheme != AloE2eeCrypto.MEDIA_SCHEME &&
            mediaScheme != AloChunkedMediaCrypto.SCHEME
        ) return attachment
        val remoteUrl = attachment.optString("url").trim()
        if (remoteUrl.isBlank()) return attachment
        val expectedCiphertextSize = if (mediaScheme == AloChunkedMediaCrypto.SCHEME) {
            AloChunkedMediaCrypto.parseMetadata(encryption)?.let { metadata ->
                AloChunkedMediaCrypto.expectedCiphertextSize(
                    plaintextSize = metadata.plaintextSize,
                    chunkSize = metadata.chunkSize
                )
            } ?: return attachment
        } else {
            null
        }

        val cacheRoot = e2eeMediaCacheRoot()
        cleanupLegacyDecodedMediaCacheIfNeeded(cacheRoot)
        val encryptedCacheFile = encryptedMediaCacheFile(
            root = cacheRoot,
            remoteUrl = remoteUrl,
            encryption = encryption
        )
        val isCached = encryptedCacheFile.isFile &&
            (expectedCiphertextSize == null || encryptedCacheFile.length() == expectedCiphertextSize)
        if (isCached) {
            AloMediaDiskCache.touch(encryptedCacheFile)
        } else if (!userInitiated) {
            val plaintextSize = encryption.optLong("plaintextSize", 0L).takeIf { it > 0L }
                ?: attachment.optJSONObject("metadata")?.optString("size")?.toLongOrNull()
            val kind = attachment.optString("type").ifBlank { encryption.optString("mime") }
            val allowed = AloMediaCacheSettings.load(appContext).permits(
                type = kind,
                size = plaintextSize,
                network = AloMediaCacheSettings.network(appContext),
                userInitiated = false
            )
            if (!allowed) return attachment
        }
        runCatching {
            if (!isCached) {
                encryptedCacheFile.delete()
                encryptedCacheFile.parentFile?.mkdirs()
                downloadRawFile(
                    path = remoteUrl,
                    destination = encryptedCacheFile,
                    expectedSize = expectedCiphertextSize
                )
                AloMediaDiskCache.trimIfNeeded(appContext)
            }
        }.onFailure {
            Log.w("AloApiClient", "Failed to cache E2EE media ${attachment.optString("url")}", it)
            return attachment
        }

        val localUrl = runCatching {
            AloE2eeMediaContentProvider.register(
                context = appContext,
                encryptedFile = encryptedCacheFile,
                encryption = encryption,
                type = attachment.optString("type"),
                name = attachment.optString("name"),
                expiresAt = expiresAt
            ).toString()
        }.getOrElse {
            Log.w("AloApiClient", "Failed to register E2EE media ${attachment.optString("url")}", it)
            return attachment
        }

        return JSONObject(attachment.toString())
            .put("url", localUrl)
            .also { next ->
                if (next.optString("type") == "image") {
                    next.put("posterUrl", localUrl)
                }
            }
    }

    private fun encryptedMediaCacheFile(
        root: File,
        remoteUrl: String,
        encryption: JSONObject
    ): File =
        File(File(root, "encrypted"), "${mediaCacheDigest(remoteUrl, encryption)}.bin")

    private fun mediaCacheDigest(remoteUrl: String, encryption: JSONObject): String {
        val digestInput = listOf(
            remoteUrl,
            encryption.optString("algorithm"),
            encryption.optString("key"),
            encryption.optString("iv"),
            encryption.optString("noncePrefix"),
            encryption.optString("plaintextSize"),
            encryption.optString("chunkSize")
        ).joinToString("|")
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(digestInput)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun e2eeMediaCacheRoot(): File =
        e2eeMediaCacheDir ?: File(System.getProperty("java.io.tmpdir"), "alo-e2ee-media")

    private fun cleanupLegacyDecodedMediaCacheIfNeeded(root: File) {
        if (decodedMediaCacheCleaned) return
        decodedMediaCacheCleaned = true

        File(root, "decoded").deleteRecursively()

        root.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    private suspend fun downloadRawFile(
        path: String,
        destination: File,
        expectedSize: Long? = null
    ) = withContext(Dispatchers.IO) {
        require(expectedSize == null || expectedSize in 1..MAX_E2EE_MEDIA_CIPHERTEXT_BYTES) {
            "Некорректный размер зашифрованного вложения."
        }
        val temporary = File(destination.parentFile, ".${destination.name}.download-${UUID.randomUUID()}.part")
        destination.parentFile?.mkdirs()
        temporary.delete()
        if (path.startsWith("file://", ignoreCase = true)) {
            try {
                File(java.net.URI(path)).inputStream().use { input ->
                    temporary.outputStream().use { output ->
                        copyEncryptedMediaWithLimit(input, output, expectedSize)
                    }
                }
                if (destination.exists()) destination.delete()
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                return@withContext
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
        val fullUrl = if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            URL(path)
        } else {
            URL(URL(baseUrl), path)
        }
        val connection = (fullUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("X-Requested-With", "AloAndroid")
            applyInstallIdentityHeader()
        }
        val cookieHeader = withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(cookieUrl)
        }
        if (!cookieHeader.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
        try {
            val status = connection.responseCode
            syncCookies(connection)
            if (status !in 200..299) {
                throw AloApiException(
                    status = status,
                    code = "MEDIA_DOWNLOAD_FAILED",
                    message = "Не удалось загрузить зашифрованное вложение.",
                    payload = JSONObject()
                )
            }
            val advertisedSize = connection.contentLengthLong
            if (advertisedSize > MAX_E2EE_MEDIA_CIPHERTEXT_BYTES ||
                (expectedSize != null && advertisedSize >= 0 && advertisedSize != expectedSize)
            ) {
                throw IOException("Сервер вернул некорректный размер зашифрованного вложения.")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    copyEncryptedMediaWithLimit(input, output, expectedSize)
                }
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun copyEncryptedMediaWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        expectedSize: Long?
    ) {
        val buffer = ByteArray(64 * 1_024)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            written += count.toLong()
            if (written > MAX_E2EE_MEDIA_CIPHERTEXT_BYTES) {
                throw IOException("Зашифрованное вложение превышает допустимый размер.")
            }
            output.write(buffer, 0, count)
        }
        if (expectedSize != null && written != expectedSize) {
            throw IOException("Размер зашифрованного вложения не совпадает с метаданными.")
        }
    }

    private fun messageAttachmentJson(attachment: AloOutgoingMessageAttachment): JSONObject =
        JSONObject()
            .put("url", attachment.url)
            .put("type", attachment.type)
            .put("name", attachment.name)
            .also { json ->
                if (attachment.mediaEncryption.isNotEmpty()) {
                    json.put("mediaEncryption", JSONObject(attachment.mediaEncryption))
                }
                if (attachment.metadata.isNotEmpty()) {
                    json.put("metadata", JSONObject(attachment.metadata))
                }
                // The bars of a voice message travel with it, as the iPhone and the website send them.
                if (attachment.waveform.isNotEmpty()) {
                    json.put("waveform", JSONArray().also { array ->
                        attachment.waveform.take(64).forEach { array.put(Math.round(it.coerceIn(0f, 1f) * 1000f) / 1000.0) }
                    })
                }
            }

    private fun e2eeServerAttachmentJson(attachment: AloOutgoingMessageAttachment): JSONObject =
        JSONObject()
            .put("url", attachment.url)
            .put("type", "e2ee")
            .put("name", attachment.name.ifBlank { "encrypted.bin" })

    private fun parseCurrentUser(json: JSONObject): AloCurrentUser {
        return AloCurrentUser(
            id = json.optInt("id"),
            name = json.optString("name"),
            username = json.optString("username"),
            email = json.optString("email"),
            bio = json.optString("bio"),
            avatarUrl = resolveUrl(json.optString("avatarEmoji")),
            avatarGallery = parseUrlList(json.optJSONArray("avatarGallery"), json.optString("avatarEmoji")),
            verified = json.optBoolean("verified"),
            registeredLabel = json.optString("registeredLabel"),
            coverStyle = json.optString("coverStyle"),
            coverImageUrl = resolveUrl(json.optString("coverImageUrl")),
            favoriteReaction = json.optString("favoriteReaction").ifBlank { "❤️" },
            adminCan = parseAdminCan(json),
            staffHierarchy = parseStaffHierarchy(json)
        )
    }

    private fun parseStaffHierarchy(json: JSONObject): AloStaffHierarchy =
        AloStaffHierarchy.from(json.optJSONObject("adminScope")?.optString("hierarchy"))
            ?: if (parseAdminCan(json).isEmpty()) AloStaffHierarchy.User else AloStaffHierarchy.Moderator

    private fun parseAdminCan(json: JSONObject): Set<String> {
        val can = json.optJSONObject("adminScope")?.optJSONObject("can") ?: return emptySet()
        return can.keys().asSequence().filter { can.optBoolean(it) }.toSet()
    }

    private fun parseDirectCallSession(json: JSONObject?): AloDirectCallSession? {
        if (json == null) return null
        val id = json.optInt("id")
        if (id <= 0) return null
        return AloDirectCallSession(
            id = id,
            type = json.optString("type").ifBlank { "voice" },
            callerId = json.optInt("callerId"),
            calleeId = json.optInt("calleeId"),
            status = json.optString("status"),
            createdAt = json.optString("createdAt")
        )
    }

    private fun parseGroupCallSession(root: JSONObject?): AloGroupCallSession? {
        val json = root?.optJSONObject("call") ?: root?.takeIf { it.optInt("id") > 0 } ?: return null
        val id = json.optInt("id")
        if (id <= 0) return null
        return AloGroupCallSession(
            id = id,
            roomId = json.optInt("roomId"),
            type = json.optString("type").ifBlank { "voice" },
            status = json.optString("status").ifBlank { "active" },
            roomTitle = json.optString("roomTitle"),
            participants = parseEntities(root?.optJSONArray("participants"))
        )
    }

    private fun parseProfileStats(json: JSONObject?): AloProfileStats {
        return AloProfileStats(
            followingCount = json?.optInt("followingCount") ?: 0,
            followersCount = json?.optInt("followersCount") ?: 0,
            friendsCount = json?.optInt("friendsCount") ?: 0,
            channelsOwnedCount = json?.optInt("channelsOwnedCount") ?: 0,
            channelsSubscribedCount = json?.optInt("channelsSubscribedCount") ?: 0
        )
    }

    private fun parseProfileRelations(json: JSONObject?): AloProfileRelations {
        val friendsCount = json?.optJSONArray("friends")?.length() ?: 0
        val followingCount = json?.optJSONArray("following")?.length() ?: 0
        val followersCount = json?.optJSONArray("followers")?.length() ?: 0
        val channelsCount =
            (json?.optJSONArray("ownedChannels")?.length() ?: 0) +
                (json?.optJSONArray("subscribedChannels")?.length() ?: 0)

        return AloProfileRelations(
            friendsCount = friendsCount,
            followingCount = followingCount,
            followersCount = followersCount,
            channelsCount = channelsCount
        )
    }

    private fun parseNotificationSettings(json: JSONObject?): AloNotificationSettings {
        return AloNotificationSettings(
            likesEnabled = json?.optBoolean("likesEnabled", true) ?: true,
            commentsEnabled = json?.optBoolean("commentsEnabled", true) ?: true,
            repostsEnabled = json?.optBoolean("repostsEnabled", true) ?: true,
            followsEnabled = json?.optBoolean("followsEnabled", true) ?: true,
            messagesEnabled = json?.optBoolean("messagesEnabled", true) ?: true,
            friendRequestsEnabled = json?.optBoolean("friendRequestsEnabled", true) ?: true,
            friendAcceptsEnabled = json?.optBoolean("friendAcceptsEnabled", true) ?: true,
            channelSubscriptionsEnabled = json?.optBoolean("channelSubscriptionsEnabled", true) ?: true,
            commentLikesEnabled = json?.optBoolean("commentLikesEnabled", true) ?: true
        )
    }

    private fun parsePrivacySettings(json: JSONObject?): AloPrivacySettings {
        return AloPrivacySettings(
            allowFriendRequests = json?.optBoolean("allowFriendRequests", true) ?: true,
            messageScope = json?.optString("messageScope").orEmpty().ifBlank { "all" },
            allowMessageForwards = json?.optBoolean("allowMessageForwards", true) ?: true,
            callScope = json?.optString("callScope").orEmpty().ifBlank { "all" },
            hideActivity = json?.optBoolean("hideActivity", false) ?: false,
            profileVisibility = json?.optString("profileVisibility").orEmpty().ifBlank { "all" }
        )
    }

    private fun parseEntities(
        array: JSONArray?,
        forceChannel: Boolean = false
    ): List<AloEntitySummary> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloEntitySummary>()
        for (index in 0 until array.length()) {
            val item = parseEntitySummary(array.optJSONObject(index), forceChannel)
            if (item != null) items += item
        }
        return items
    }

    private fun parseNotifications(array: JSONArray?): List<AloNotificationItem> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloNotificationItem>()
        for (index in 0 until array.length()) {
            parseNotificationUpdate(array.optJSONObject(index))?.let(items::add)
        }
        return items
    }

    fun parseNotificationUpdate(json: JSONObject?): AloNotificationItem? {
        if (json == null || json.optInt("id") <= 0) return null
        return AloNotificationItem(
            id = json.optInt("id"),
            type = json.optString("type"),
            text = json.optString("text"),
            createdAt = json.optString("createdAt"),
            entityType = json.optString("entityType"),
            entityId = json.optInt("entityId"),
            friendRequestStatus = json.optString("friendRequestStatus"),
            friendRequestActionable = json.optBoolean("friendRequestActionable", false),
            actor = parseEntitySummary(json.optJSONObject("actor")),
            isRead = json.optBoolean("isRead", false)
        )
    }

    fun parsePostUpdate(json: JSONObject?): AloPost? = parsePost(json)

    fun parseCommentUpdate(json: JSONObject?): AloPostComment? = parseComment(json)

    fun parseUserRelationUpdate(json: JSONObject?): AloUserRelationUpdate? {
        if (json == null) return null
        val userId = json.optInt("userId")
        val relation = json.optJSONObject("relation") ?: return null
        val stats = json.optJSONObject("stats") ?: return null
        if (userId <= 0) return null
        return AloUserRelationUpdate(
            userId = userId,
            following = relation.optBoolean("following"),
            followedBy = relation.optBoolean("followedBy"),
            isFriend = relation.optBoolean("isFriend"),
            friendStatus = relation.optString("friendStatus").ifBlank {
                if (relation.optBoolean("isFriend")) "friends" else "none"
            },
            incomingRequestId = relation.optInt("incomingRequestId"),
            outgoingRequestId = relation.optInt("outgoingRequestId"),
            canSendFriendRequest = json.optBoolean("canSendFriendRequest"),
            stats = parseProfileStats(stats)
        )
    }

    fun parseGroupPolicyUpdate(json: JSONObject?): AloGroupPolicy? = parseGroupPolicy(json)

    fun parseGroupMembersUpdate(json: JSONArray?): List<AloGroupMember> =
        parseGroupMembers(json)

    fun parseChannelManagementDelta(json: JSONObject?): AloChannelManagementDelta? {
        if (json?.optInt("version") != 1) return null
        val settings = json.optJSONObject("settings")
        val event = json.optJSONObject("adminEvent")
        val actor = parseEntitySummary(event?.optJSONObject("actor"))
        val adminEvent = if (event != null && event.optInt("id") > 0 && actor != null) {
            AloChannelAdminEvent(
                id = event.optInt("id"),
                action = event.optString("action"),
                postId = event.optInt("postId").takeIf { it > 0 },
                createdAt = event.optString("createdAt"),
                actor = actor,
                target = parseEntitySummary(event.optJSONObject("target"))
            )
        } else {
            null
        }
        return AloChannelManagementDelta(
            moderatorCount = json.optInt("moderatorCount").coerceAtLeast(0),
            commentsEnabled = settings?.optBoolean("commentsEnabled"),
            signMessages = settings?.optBoolean("signMessages"),
            showAuthorProfiles = settings?.optBoolean("showAuthorProfiles"),
            discussionRoomId = settings
                ?.optInt("discussionRoomId")
                ?.takeIf { it > 0 },
            hasSettings = settings != null,
            adminEvent = adminEvent
        )
    }

    fun parseGroupManagementDelta(json: JSONObject?): AloGroupManagementDelta? {
        if (json?.optInt("version") != 1) return null

        val memberChanges = json.optJSONArray("members")
        val membersUpserted = mutableListOf<AloGroupMember>()
        val memberIdsRemoved = linkedSetOf<Int>()
        for (index in 0 until (memberChanges?.length() ?: 0)) {
            val change = memberChanges?.optJSONObject(index) ?: continue
            if (change.optString("operation") == "remove") {
                change.optInt("userId").takeIf { it > 0 }?.let(memberIdsRemoved::add)
            } else if (change.optString("operation") == "upsert") {
                parseGroupMembers(JSONArray().put(change.optJSONObject("value")))
                    .firstOrNull()
                    ?.let(membersUpserted::add)
            }
        }

        val requestChanges = json.optJSONArray("requests")
        val requestsUpserted = mutableListOf<AloGroupJoinRequest>()
        val requestUserIdsRemoved = linkedSetOf<Int>()
        for (index in 0 until (requestChanges?.length() ?: 0)) {
            val change = requestChanges?.optJSONObject(index) ?: continue
            if (change.optString("operation") == "remove") {
                change.optInt("userId").takeIf { it > 0 }?.let(requestUserIdsRemoved::add)
            } else if (change.optString("operation") == "upsert") {
                parseGroupJoinRequests(JSONArray().put(change.optJSONObject("value")))
                    .firstOrNull()
                    ?.let(requestsUpserted::add)
            }
        }

        val banChanges = json.optJSONArray("bans")
        val bansUpserted = mutableListOf<AloGroupBan>()
        val banUserIdsRemoved = linkedSetOf<Int>()
        for (index in 0 until (banChanges?.length() ?: 0)) {
            val change = banChanges?.optJSONObject(index) ?: continue
            if (change.optString("operation") == "remove") {
                change.optInt("userId").takeIf { it > 0 }?.let(banUserIdsRemoved::add)
            } else if (change.optString("operation") == "upsert") {
                parseGroupBans(JSONArray().put(change.optJSONObject("value")))
                    .firstOrNull()
                    ?.let(bansUpserted::add)
            }
        }

        val invitesUpserted = buildList {
            val changes = json.optJSONArray("invites")
            for (index in 0 until (changes?.length() ?: 0)) {
                val change = changes?.optJSONObject(index) ?: continue
                if (change.optString("operation") == "upsert") {
                    parseGroupInviteLink(change.optJSONObject("value"))?.let(::add)
                }
            }
        }
        val topicsUpserted = buildList {
            val changes = json.optJSONArray("topics")
            for (index in 0 until (changes?.length() ?: 0)) {
                val change = changes?.optJSONObject(index) ?: continue
                if (change.optString("operation") == "upsert") {
                    parseForumTopic(change.optJSONObject("value"))?.let(::add)
                }
            }
        }
        val topicIdsRemoved = buildSet {
            val changes = json.optJSONArray("topics")
            for (index in 0 until (changes?.length() ?: 0)) {
                val change = changes?.optJSONObject(index) ?: continue
                if (change.optString("operation") == "remove") {
                    change.optInt("id").takeIf { it > 0 }?.let(::add)
                }
            }
        }

        return AloGroupManagementDelta(
            policy = parseGroupPolicy(json.optJSONObject("policy")),
            membersUpserted = membersUpserted,
            memberIdsRemoved = memberIdsRemoved,
            requestsUpserted = requestsUpserted,
            requestUserIdsRemoved = requestUserIdsRemoved,
            bansUpserted = bansUpserted,
            banUserIdsRemoved = banUserIdsRemoved,
            invitesUpserted = invitesUpserted,
            topicsUpserted = topicsUpserted,
            topicIdsRemoved = topicIdsRemoved,
            adminEvent = parseGroupAdminEvents(
                JSONArray().put(json.optJSONObject("adminEvent"))
            ).firstOrNull()
        )
    }

    fun parseCurrentUserUpdate(
        json: JSONObject?,
        previous: AloCurrentUser
    ): AloCurrentUser? {
        if (json == null || json.optInt("id") != previous.id) return null
        return AloCurrentUser(
            id = previous.id,
            name = json.optString("name").ifBlank { previous.name },
            username = json.optString("username").ifBlank { previous.username },
            email = json.optString("email").ifBlank { previous.email },
            bio = if (json.has("bio")) json.optString("bio") else previous.bio,
            avatarUrl = resolveUrl(
                json.optString("avatarEmoji").ifBlank { previous.avatarUrl }
            ),
            avatarGallery = if (json.has("avatarGallery")) {
                parseUrlList(
                    json.optJSONArray("avatarGallery"),
                    json.optString("avatarEmoji")
                )
            } else {
                previous.avatarGallery
            },
            verified = if (json.has("verified")) {
                json.optBoolean("verified")
            } else {
                previous.verified
            },
            registeredLabel = json.optString("registeredLabel")
                .ifBlank { previous.registeredLabel },
            coverStyle = json.optString("coverStyle").ifBlank { previous.coverStyle },
            coverImageUrl = if (json.has("coverImageUrl")) {
                resolveUrl(json.optString("coverImageUrl"))
            } else {
                previous.coverImageUrl
            },
            favoriteReaction = json.optString("favoriteReaction").ifBlank { previous.favoriteReaction },
            adminCan = if (json.has("adminScope")) parseAdminCan(json) else previous.adminCan,
            staffHierarchy = if (json.has("adminScope")) parseStaffHierarchy(json) else previous.staffHierarchy
        )
    }

    fun parseChannelProfileUpdate(json: JSONObject?): AloViewedProfile? {
        if (json == null) return null
        val channel = json.optJSONObject("channel") ?: return null
        val channelId = channel.optInt("id")
        if (channelId <= 0) return null
        val stats = json.optJSONObject("stats") ?: JSONObject()
        val slug = channel.optString("slug").ifBlank { channel.optString("username") }
        return AloViewedProfile(
            kind = "channel",
            id = channelId,
            title = channel.optString("name").ifBlank { "Канал" },
            username = channel.optString("username").ifBlank { slug },
            avatarUrl = resolveUrl(channel.optString("avatarEmoji")),
            avatarGallery = parseUrlList(
                channel.optJSONArray("avatarGallery"),
                channel.optString("avatarEmoji")
            ),
            verified = channel.optBoolean("verified"),
            coverStyle = "graphite",
            coverImageUrl = resolveUrl(channel.optString("coverImageUrl")),
            status = "Канал Alo",
            meta = if (slug.isNotBlank()) "@$slug" else "Паблик",
            description = channel.optString("description"),
            metrics = listOf(
                AloProfileMetric(
                    "Подписчики",
                    stats.optInt("subscribersCount").toString()
                ),
                AloProfileMetric("Посты", stats.optInt("postsCount").toString())
            ),
            posts = parsePosts(json.optJSONArray("posts")),
            canManage = channel.optBoolean("canManage"),
            isOwner = channel.optBoolean("isOwner"),
            commentsEnabled = if (channel.has("commentsEnabled")) {
                channel.optBoolean("commentsEnabled")
            } else {
                true
            },
            signMessages = channel.optBoolean("signMessages"),
            showAuthorProfiles = channel.optBoolean("showAuthorProfiles"),
            discussionRoomId = channel.optInt("discussionRoomId")
        )
    }

    fun parseGroupRoomUpdate(
        json: JSONObject?,
        existing: AloConversationSummary?
    ): AloConversationSummary? {
        if (json == null) return null
        val roomId = json.optInt("id")
        val peerId = json.optInt("peerId")
        if (roomId <= 0 || peerId <= 0) return null
        val existingPeer = existing?.peer
        val policy = json.optJSONObject("policy")
        val policyKind = policy?.optString("kind").orEmpty()
        val isChannel = policyKind.equals("channel", ignoreCase = true) ||
            existingPeer?.entityType.equals("channel", ignoreCase = true)
        return AloConversationSummary(
            peerId = peerId,
            kind = "group",
            roomId = roomId,
            memberCount = json.optInt("memberCount")
                .takeIf { it > 0 }
                ?: existing?.memberCount
                ?: 0,
            lastAt = existing?.lastAt.orEmpty(),
            lastBody = existing?.lastBody.orEmpty(),
            unreadCount = existing?.unreadCount ?: 0,
            hasServerUnreadCount = existing?.hasServerUnreadCount ?: false,
            unreadMentionCount = existing?.unreadMentionCount ?: 0,
            lastMessageId = existing?.lastMessageId ?: 0,
            lastSenderId = existing?.lastSenderId ?: 0,
            peer = AloConversationPeer(
                id = peerId,
                name = json.optString("title")
                    .ifBlank { existingPeer?.name ?: if (isChannel) "Канал" else "Групповой чат" },
                username = policy?.optString("username")
                    .orEmpty()
                    .ifBlank { existingPeer?.username.orEmpty() },
                avatarUrl = resolveUrl(
                    json.optString("avatarEmoji")
                        .ifBlank { existingPeer?.avatarUrl.orEmpty() }
                ),
                verified = existingPeer?.verified ?: false,
                entityType = if (isChannel) "channel" else "group",
                online = false,
                isTyping = false,
                lastSeenAt = existingPeer?.lastSeenAt.orEmpty()
            )
        )
    }

    private fun parseConversations(array: JSONArray?): List<AloConversationSummary> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloConversationSummary>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val peerId = json.optInt("peerId")
            if (peerId <= 0) continue
            val peer = parseConversationPeer(json.optJSONObject("peer")) ?: continue
            val kind = json.optString("kind").ifBlank { "direct" }
            val roomId = if (kind == "group") {
                groupRoomIdFrom(peerId = peerId, roomId = json.optInt("roomId"))
            } else {
                0
            }
            val lastMessage = json.optJSONObject("lastMessage")
            items += AloConversationSummary(
                peerId = peerId,
                kind = kind,
                roomId = roomId,
                memberCount = json.optInt("memberCount"),
                lastAt = json.optString("lastAt"),
                lastBody = aloReadableMessageText(json.optString("lastBody")),
                peer = peer,
                unreadCount = json.optInt("unreadCount").coerceAtLeast(0),
                hasServerUnreadCount = json.has("unreadCount"),
                unreadMentionCount = json.optInt("unreadMentionCount").coerceAtLeast(0),
                lastMessageId = lastMessage?.optInt("id") ?: 0,
                lastSenderId = lastMessage?.optInt("senderId") ?: 0
            )
        }
        return items
    }

    internal fun parseBlockedUserIds(response: JSONObject): Set<Int> {
        val arrays = buildList {
            add(response.optJSONArray("blocks"))
            add(response.optJSONArray("blockedUsers"))
            add(response.optJSONArray("blockedUserIds"))
            add(response.optJSONArray("blockedIds"))
            response.optJSONObject("data")?.let { data ->
                add(data.optJSONArray("blocks"))
                add(data.optJSONArray("blockedUsers"))
                add(data.optJSONArray("blockedUserIds"))
                add(data.optJSONArray("blockedIds"))
            }
        }
        return buildSet {
            arrays.forEach { array ->
                if (array == null) return@forEach
                for (index in 0 until array.length()) {
                    val raw = array.opt(index)
                    val userId = when (raw) {
                        is Number -> raw.toInt()
                        is JSONObject -> raw.blockedUserId()
                        else -> 0
                    }
                    if (userId > 0) add(userId)
                }
            }
        }
    }

    private fun JSONObject.blockedUserId(): Int {
        return optInt("blockedUserId").takeIf { it > 0 }
            ?: optInt("blockedId").takeIf { it > 0 }
            ?: optInt("targetUserId").takeIf { it > 0 }
            ?: optJSONObject("blockedUser")?.optInt("id")?.takeIf { it > 0 }
            ?: optJSONObject("user")?.optInt("id")?.takeIf { it > 0 }
            ?: optInt("id").takeIf { it > 0 }
            ?: 0
    }

    private fun parseChatMessages(array: JSONArray?): List<AloChatMessage> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloChatMessage>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val id = json.optInt("id")
            val scheduledMessageId = json.optInt("scheduledMessageId").coerceAtLeast(0)
            if (id == 0 || (id < 0 && scheduledMessageId <= 0)) continue
            val rawBody = json.optString("body")
            val explicitEntities = AloMessageMarkup.parseEntities(
                json.optJSONArray("entities"),
                rawBody.length
            )
            // A cloud message is plain text with its own entities; only an encrypted (or older) message can hold markup.
            val cloudMessage = AloCloudMessages.isCloudMessageJson(json)
            val encryptedMessage = json.optString("encryptionScheme").isNotBlank() ||
                json.optJSONObject("encryptedPayload") != null
            val markup = when {
                cloudMessage -> AloMessageMarkup.plain(rawBody, explicitEntities)
                explicitEntities.isEmpty() -> AloMessageMarkup.parse(rawBody)
                else -> null
            }
            val resolvedBody = markup?.body ?: rawBody
            items += AloChatMessage(
                id = id,
                roomId = json.optInt("roomId"),
                senderId = json.optInt("senderId"),
                recipientId = json.optInt("recipientId"),
                body = resolvedBody,
                attachmentUrl = resolveUrl(json.optString("attachmentUrl")),
                attachmentType = json.optString("attachmentType"),
                attachmentName = json.optString("attachmentName"),
                attachments = parseMessageAttachments(json.optJSONArray("attachments"), json),
                createdAt = json.optString("createdAt"),
                editedAt = json.optString("editedAt"),
                readAt = json.optString("readAt"),
                forwardFrom = parseMessageForwardSource(json.optJSONObject("forwardFrom")),
                sender = parseConversationPeer(json.optJSONObject("sender")),
                senderIsAnonymous = json.optBoolean("senderIsAnonymous"),
                senderAdminTitle = json.optString("senderAdminTitle"),
                reactions = parseMessageReactions(json.optJSONArray("reactions")),
                entities = AloMessageMarkup.normalized(
                    markup?.entities ?: explicitEntities,
                    resolvedBody.length
                ),
                selfDestructSeconds = json.optInt("selfDestructSeconds").coerceAtLeast(0),
                selfDestructStartedAt = json.optString("selfDestructStartedAt"),
                expiresAt = json.optString("expiresAt"),
                topicId = json.optInt("topicId").coerceAtLeast(0),
                replyToMessageId = json.optInt("replyToMessageId").coerceAtLeast(0),
                channelPostId = json.optInt("channelPostId").coerceAtLeast(0),
                channelPostCommentCount = json.optInt("channelPostCommentCount").coerceAtLeast(0),
                channelPostCommentsEnabled = json.optBoolean("channelPostCommentsEnabled"),
                scheduledMessageId = scheduledMessageId,
                scheduledAt = json.optString("scheduledAt"),
                sendWhenOnline = json.optBoolean("sendWhenOnline"),
                silent = json.optBoolean("silent"),
                deliveryState = json.optString("deliveryState"),
                deliveryError = json.optString("deliveryError"),
                cloud = cloudMessage,
                encrypted = encryptedMessage
            )
        }
        return items
    }

    private fun parseMessageReactions(array: JSONArray?): List<AloMessageReaction> {
        if (array == null) return emptyList()
        val items = mutableListOf<AloMessageReaction>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val emoji = json.optString("emoji").trim()
            if (emoji.isBlank()) continue
            items += AloMessageReaction(
                emoji = emoji,
                count = json.optInt("count").coerceAtLeast(0),
                reactedByMe = json.optBoolean("reactedByMe"),
                users = json.optJSONArray("users")?.let { voters ->
                    (0 until voters.length()).mapNotNull { parseConversationPeer(voters.optJSONObject(it)) }
                }.orEmpty()
            )
        }
        return items
    }

    private fun parseMessageForwardSource(json: JSONObject?): AloMessageForwardSource? {
        if (json == null) return null
        val user = json.optJSONObject("user") ?: JSONObject()
        val userId = json.optInt("userId").takeIf { it > 0 } ?: user.optInt("id")
        if (userId <= 0) return null
        return AloMessageForwardSource(
            userId = userId,
            messageId = json.optInt("messageId"),
            userName = user.optString("name").ifBlank { "Автор" }
        )
    }

    private fun parseMessageAttachments(array: JSONArray?, fallbackObject: JSONObject?): List<AloOutgoingMessageAttachment> {
        val items = mutableListOf<AloOutgoingMessageAttachment>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val url = resolveUrl(json.optString("url"))
                val type = json.optString("type")
                if (type.isBlank() || (url.isBlank() && type !in setOf("contact", "location"))) continue
                items += AloOutgoingMessageAttachment(
                    url = url,
                    type = type,
                    name = json.optString("name"),
                    mediaEncryption = parseStringMap(json.optJSONObject("mediaEncryption") ?: json.optJSONObject("e2ee")),
                    metadata = parseStringMap(json.optJSONObject("metadata")),
                    posterUrl = resolveUrl(json.optString("posterUrl")),
                    waveform = AloCloudMessages.waveformFrom(json.optJSONArray("waveform"))
                )
            }
        }
        if (items.isNotEmpty()) return items
        val fallbackUrl = resolveUrl(fallbackObject?.optString("attachmentUrl").orEmpty())
        val fallbackType = fallbackObject?.optString("attachmentType").orEmpty()
        if (fallbackUrl.isBlank() || fallbackType.isBlank()) return emptyList()
        return listOf(
            AloOutgoingMessageAttachment(
                url = fallbackUrl,
                type = fallbackType,
                name = fallbackObject?.optString("attachmentName").orEmpty(),
                mediaEncryption = parseStringMap(
                    fallbackObject?.optJSONObject("mediaEncryption") ?: fallbackObject?.optJSONObject("e2ee")
                )
            )
        )
    }

    private fun parseStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key)
        }
        return result
    }

    private fun parseGroupMembers(array: JSONArray?): List<AloGroupMember> {
        if (array == null) return emptyList()
        val items = mutableListOf<AloGroupMember>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val user = parseEntitySummary(json) ?: continue
            items += AloGroupMember(
                user = user,
                role = json.optString("role").ifBlank { "member" },
                adminTitle = json.optString("adminTitle"),
                isAnonymous = json.optBoolean("isAnonymous"),
                adminRights = parseBooleanMap(json.optJSONObject("adminRights")),
                restrictions = parseBooleanMap(json.optJSONObject("restrictions")),
                restrictedUntil = json.optString("restrictedUntil")
            )
        }
        return items
    }

    private fun parseGroupBans(array: JSONArray?): List<AloGroupBan> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val user = parseEntitySummary(item.optJSONObject("user")) ?: continue
                val bannedBy = parseEntitySummary(item.optJSONObject("bannedBy")) ?: continue
                add(
                    AloGroupBan(
                        user = user,
                        reason = item.optString("reason"),
                        createdAt = item.optString("createdAt"),
                        expiresAt = item.optString("expiresAt"),
                        bannedBy = bannedBy
                    )
                )
            }
        }
    }

    private fun parseGroupPolicy(json: JSONObject?): AloGroupPolicy? {
        if (json == null) return null
        return AloGroupPolicy(
            kind = json.optString("kind", "group"),
            username = json.optString("username"),
            joinPolicy = json.optString("joinPolicy", "invite"),
            isForum = json.optBoolean("isForum"),
            historyVisible = if (json.has("historyVisible")) {
                json.optBoolean("historyVisible")
            } else {
                true
            },
            slowModeSeconds = json.optInt("slowModeSeconds").coerceAtLeast(0),
            autoDeleteSeconds = json.optInt("autoDeleteSeconds").coerceAtLeast(0),
            defaultPermissions = parseBooleanMap(json.optJSONObject("defaultPermissions")),
            memberAdminRights = parseBooleanMap(json.optJSONObject("memberAdminRights")),
            memberRestrictions = parseBooleanMap(json.optJSONObject("memberRestrictions")),
            memberRestrictedUntil = json.optString("memberRestrictedUntil"),
            protectedContent = json.optBoolean("protectedContent")
        ).normalizedCommunityPolicy()
    }

    private fun parseForumTopic(json: JSONObject?): AloForumTopic? {
        if (json == null) return null
        val id = json.optInt("id")
        if (id <= 0) return null
        return AloForumTopic(
            id = id,
            roomId = json.optInt("roomId"),
            title = json.optString("title").ifBlank { "Тема" },
            iconEmoji = json.optString("iconEmoji").ifBlank { "💬" },
            isGeneral = json.optBoolean("isGeneral"),
            isClosed = json.optBoolean("isClosed"),
            isHidden = json.optBoolean("isHidden"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }

    private fun parseForumTopics(array: JSONArray?): List<AloForumTopic> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseForumTopic(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    fun parseForumTopicState(json: JSONObject?): AloForumTopicState? {
        val source = json ?: return null
        val topicId = source.optInt("topicId")
        if (topicId <= 0) return null
        return AloForumTopicState(
            topicId = topicId,
            cursor = source.optInt("cursor").coerceAtLeast(0),
            lastReadMessageId = source.optInt("lastReadMessageId").coerceAtLeast(0),
            unreadCount = source.optInt("unreadCount").coerceAtLeast(0),
            muted = source.optBoolean("muted"),
            draftRevision = source.optInt("draftRevision").coerceAtLeast(0),
            stateVersion = source.optInt("stateVersion").coerceAtLeast(0),
            draftUpdatedAt = source.optString("draftUpdatedAt"),
            updatedAt = source.optString("updatedAt"),
            draftText = source.optString("draftText")
        )
    }

    fun parseForumTopicStateUpdate(json: JSONObject?): AloForumTopicState? {
        val source = json ?: return null
        decryptForumTopicState(source)
        return parseForumTopicState(source)
    }

    private fun parseForumTopicStates(array: JSONArray?): List<AloForumTopicState> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseForumTopicState(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun parseGroupJoinRequests(array: JSONArray?): List<AloGroupJoinRequest> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val user = parseEntitySummary(item.optJSONObject("user")) ?: continue
                add(AloGroupJoinRequest(user = user, createdAt = item.optString("createdAt")))
            }
        }
    }

    private fun parseGroupInviteLink(json: JSONObject?): AloGroupInviteLink? {
        if (json == null) return null
        val id = json.optInt("id")
        if (id <= 0) return null
        return AloGroupInviteLink(
            id = id,
            label = json.optString("label"),
            requiresApproval = json.optBoolean("requiresApproval"),
            usageLimit = json.optInt("usageLimit").coerceAtLeast(0),
            usageCount = json.optInt("usageCount").coerceAtLeast(0),
            joinedCount = json.optInt("joinedCount").coerceAtLeast(0),
            pendingCount = json.optInt("pendingCount").coerceAtLeast(0),
            expiresAt = json.optString("expiresAt"),
            revokedAt = json.optString("revokedAt"),
            createdAt = json.optString("createdAt"),
            creator = parseEntitySummary(json.optJSONObject("creator"))
        )
    }

    private fun parseGroupInviteLinks(array: JSONArray?): List<AloGroupInviteLink> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseGroupInviteLink(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun parseGroupInviteParticipants(array: JSONArray?): List<AloGroupInviteParticipant> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val user = parseEntitySummary(item.optJSONObject("user")) ?: continue
                add(AloGroupInviteParticipant(user = user, createdAt = item.optString("createdAt")))
            }
        }
    }

    private fun parseGroupAdminEvents(array: JSONArray?): List<AloGroupAdminEvent> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val actor = parseEntitySummary(item.optJSONObject("actor")) ?: continue
                add(
                    AloGroupAdminEvent(
                        id = item.optInt("id"),
                        action = item.optString("action"),
                        createdAt = item.optString("createdAt"),
                        actor = actor,
                        target = parseEntitySummary(item.optJSONObject("target"))
                    )
                )
            }
        }
    }

    private fun parseBooleanMap(json: JSONObject?): Map<String, Boolean> {
        if (json == null) return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optBoolean(key))
            }
        }
    }

    private fun parsePosts(array: JSONArray?): List<AloPost> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloPost>()
        for (index in 0 until array.length()) {
            val item = parsePost(array.optJSONObject(index))
            if (item != null) items += item
        }
        return items
    }

    private fun parsePost(json: JSONObject?): AloPost? {
        if (json == null) return null

        val author = parseEntitySummary(json.optJSONObject("author")) ?: return null

        return AloPost(
            id = json.optInt("id"),
            creatorId = json.optInt("creatorId"),
            body = json.optString("body"),
            media = parseMedia(json.optJSONArray("media")),
            authorSignature = json.optString("authorSignature"),
            commentsEnabled = if (json.has("commentsEnabled")) {
                json.optBoolean("commentsEnabled")
            } else {
                true
            },
            discussionRoomId = json.optInt("discussionRoomId"),
            discussionTopicId = json.optInt("discussionTopicId"),
            messengerRoomId = json.optInt("messengerRoomId"),
            likeCount = json.optInt("likeCount"),
            commentCount = json.optInt("commentCount"),
            repostCount = json.optInt("repostCount"),
            viewCount = json.optInt("viewCount"),
            viewerLiked = json.optBoolean("viewerLiked"),
            viewerReposted = json.optBoolean("viewerReposted"),
            createdAt = json.optString("createdAt"),
            author = author,
            feedReason = json.optString("feedReason"),
            editedAt = json.optString("editedAt")
        )
    }

    private fun parseStoryGroups(array: JSONArray?): List<AloStoryGroup> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val user = parseEntitySummary(json.optJSONObject("user")) ?: continue
                val stories = buildList {
                    val storyArray = json.optJSONArray("stories") ?: return@buildList
                    for (storyIndex in 0 until storyArray.length()) {
                        parseStory(storyArray.optJSONObject(storyIndex))?.let(::add)
                    }
                }
                if (stories.isNotEmpty()) {
                    add(
                        AloStoryGroup(
                            user = user,
                            stories = stories,
                            hasUnseen = json.optBoolean("hasUnseen")
                        )
                    )
                }
            }
        }
    }

    private fun parseStory(json: JSONObject?): AloStory? {
        if (json == null) return null
        val id = json.optInt("id")
        val userId = json.optInt("userId")
        if (id <= 0 || userId <= 0) return null
        return AloStory(
            id = id,
            userId = userId,
            body = json.optString("body"),
            media = parseMedia(json.optJSONArray("media")),
            createdAt = json.optString("createdAt"),
            expiresAt = json.optString("expiresAt"),
            viewerHasSeen = json.optBoolean("viewerHasSeen"),
            viewCount = json.optInt("viewCount"),
            audience = json.optString("audience").ifBlank { "everyone" }
        )
    }

    private fun parseBusinessProfile(json: JSONObject?): AloBusinessProfile {
        return AloBusinessProfile(
            userId = json?.optInt("userId") ?: 0,
            enabled = json?.optBoolean("enabled") ?: false,
            businessName = json?.optString("businessName").orEmpty(),
            category = json?.optString("category").orEmpty(),
            hours = parseStringMap(json?.optJSONObject("hours")),
            greetingMessage = json?.optString("greetingMessage").orEmpty(),
            autoReplyEnabled = json?.optBoolean("autoReplyEnabled") ?: false,
            autoReplyMessage = json?.optString("autoReplyMessage").orEmpty(),
            updatedAt = json?.optString("updatedAt").orEmpty()
        )
    }

    private fun parseBusinessQuickReplies(array: JSONArray?): List<AloBusinessQuickReply> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseBusinessQuickReply(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun parseBusinessQuickReply(json: JSONObject?): AloBusinessQuickReply? {
        val id = json?.optInt("id") ?: 0
        if (id <= 0) return null
        return AloBusinessQuickReply(
            id = id,
            shortcut = json?.optString("shortcut").orEmpty(),
            title = json?.optString("title").orEmpty(),
            body = json?.optString("body").orEmpty(),
            sortOrder = json?.optInt("sortOrder") ?: 0,
            updatedAt = json?.optString("updatedAt").orEmpty()
        )
    }

    private fun parseBusinessChatLabels(array: JSONArray?): List<AloBusinessChatLabel> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseBusinessChatLabel(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun parseBusinessChatLabel(json: JSONObject?): AloBusinessChatLabel? {
        val id = json?.optInt("id") ?: 0
        if (id <= 0) return null
        return AloBusinessChatLabel(
            id = id,
            chatKind = json?.optString("chatKind").orEmpty(),
            peerId = json?.optInt("peerId") ?: 0,
            roomId = json?.optInt("roomId") ?: 0,
            label = json?.optString("label").orEmpty(),
            color = json?.optString("color").orEmpty(),
            updatedAt = json?.optString("updatedAt").orEmpty()
        )
    }

    private fun parseComments(array: JSONArray?): List<AloPostComment> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloPostComment>()
        for (index in 0 until array.length()) {
            val item = parseComment(array.optJSONObject(index))
            if (item != null) items += item
        }
        return items
    }

    private fun parseComment(json: JSONObject?): AloPostComment? {
        if (json == null) return null

        val author = parseEntitySummary(json.optJSONObject("author")) ?: return null

        return AloPostComment(
            id = json.optInt("id"),
            body = json.optString("body"),
            media = parseMedia(json.optJSONArray("media")),
            createdAt = json.optString("createdAt"),
            editedAt = json.optString("editedAt"),
            likeCount = json.optInt("likeCount"),
            viewerLiked = json.optBoolean("viewerLiked"),
            author = author,
            parentId = json.optInt("parentId"),
            replyTo = parseEntitySummary(json.optJSONObject("replyTo"))
        )
    }

    private fun parseMedia(array: JSONArray?): List<AloMediaItem> {
        if (array == null) return emptyList()

        val items = mutableListOf<AloMediaItem>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            items += AloMediaItem(
                type = json.optString("type"),
                url = resolveUrl(json.optString("url")),
                posterUrl = resolveUrl(json.optString("poster")),
                alt = json.optString("alt"),
                caption = json.optString("caption"),
                width = json.optInt("width"),
                height = json.optInt("height")
            )
        }
        return items
    }

    private fun parseEntitySummary(
        json: JSONObject?,
        forceChannel: Boolean = false
    ): AloEntitySummary? {
        if (json == null) return null

        val id = json.optInt("id")
        if (id <= 0) return null

        val isChannel = forceChannel || json.optBoolean("isChannel") || json.optString("entityType") == "channel"
        return AloEntitySummary(
            id = id,
            name = json.optString("name").ifBlank { json.optString("title") },
            username = json.optString("username").ifBlank { json.optString("slug") },
            avatarUrl = resolveUrl(json.optString("avatarEmoji")),
            verified = json.optBoolean("verified"),
            isChannel = isChannel,
            relation = json.optJSONObject("relation")?.let { relation ->
                val isFriend = relation.optBoolean("isFriend")
                AloEntityRelation(
                    following = relation.optBoolean("following"),
                    followedBy = relation.optBoolean("followedBy") || relation.optBoolean("followsYou"),
                    friendStatus = relation.optString("friendStatus").ifBlank { if (isFriend) "friends" else "none" },
                    incomingRequestId = relation.optInt("incomingRequestId"),
                    outgoingRequestId = relation.optInt("outgoingRequestId"),
                    canSendFriendRequest = when {
                        relation.has("canSendFriendRequest") -> relation.optBoolean("canSendFriendRequest")
                        json.has("canSendFriendRequest") -> json.optBoolean("canSendFriendRequest")
                        else -> null
                    }
                )
            }
        )
    }

    private fun parseConversationPeer(json: JSONObject?): AloConversationPeer? {
        if (json == null) return null

        val id = json.optInt("id")
        if (id <= 0) return null

        return AloConversationPeer(
            id = id,
            name = json.optString("name").ifBlank { json.optString("title") },
            username = json.optString("username"),
            avatarUrl = resolveUrl(json.optString("avatarEmoji")),
            verified = json.optBoolean("verified"),
            entityType = json.optString("entityType"),
            online = json.optBoolean("online"),
            isTyping = json.optBoolean("typing") || json.optBoolean("isTyping"),
            lastSeenAt = json.optString("lastSeenAt")
        )
    }

    private fun buildDirectSubtitle(peer: AloConversationPeer): String {
        if (peer.entityType == "saved") return "Личное облако"
        if (peer.online) return "в сети"
        if (peer.lastSeenAt.isNotBlank()) return peer.lastSeenAt
        return "был(а) недавно"
    }

    private fun buildGroupSubtitle(memberCount: Int): String {
        if (memberCount <= 0) return "Групповой чат"
        val mod10 = memberCount % 10
        val mod100 = memberCount % 100
        val label = when {
            mod10 == 1 && mod100 != 11 -> "участник"
            mod10 in 2..4 && mod100 !in 12..14 -> "участника"
            else -> "участников"
        }
        return "$memberCount $label"
    }

    private fun buildChannelSubtitle(memberCount: Int): String {
        if (memberCount <= 0) return "Канал"
        val mod10 = memberCount % 10
        val mod100 = memberCount % 100
        val label = when {
            mod10 == 1 && mod100 != 11 -> "подписчик"
            mod10 in 2..4 && mod100 !in 12..14 -> "подписчика"
            else -> "подписчиков"
        }
        return "$memberCount $label"
    }

    private fun resolveUrl(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("file://", ignoreCase = true) ||
            raw.startsWith("content://", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true)
        ) return raw
        return URL(URL(baseUrl), raw).toString()
    }

    private fun jsonObjectList(array: JSONArray?): List<JSONObject> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::add)
        }
    }

    private fun parseStickerPack(json: JSONObject): AloStickerPack {
        val items = jsonObjectList(json.optJSONArray("items"))
            .mapNotNull(::parseStickerMedia)
        return AloStickerPack(
            id = json.optString("id").trim(),
            title = json.optString("title").trim(),
            shortName = json.optString("shortName").trim(),
            description = json.optString("description").trim(),
            coverUrl = resolveUrl(json.optString("coverUrl")),
            official = json.optBoolean("official"),
            installed = json.optBoolean("installed"),
            itemCount = json.optInt("itemCount", items.size).coerceAtLeast(0),
            items = items
        )
    }

    private fun parseStickerMedia(json: JSONObject): AloCatalogExpressionMedia? {
        val assetUrl = resolveUrl(json.optString("assetUrl"))
        if (assetUrl.isBlank()) return null
        return AloCatalogExpressionMedia(
            id = json.optString("id").trim(),
            kind = "sticker",
            assetUrl = assetUrl,
            previewUrl = resolveUrl(json.optString("thumbnailUrl")).ifBlank { assetUrl },
            title = json.optString("alt").trim().ifBlank { json.optString("emoji").trim() },
            width = json.optInt("width", 0).coerceIn(0, 4096),
            height = json.optInt("height", 0).coerceIn(0, 4096)
        )
    }

    private fun parseGifCatalogPage(json: JSONObject): AloGifCatalogPage {
        val gifs = jsonObjectList(json.optJSONArray("gifs")).mapNotNull { item ->
            val assetUrl = resolveUrl(item.optString("url"))
            if (assetUrl.isBlank()) {
                null
            } else {
                AloCatalogExpressionMedia(
                    id = item.optString("id").trim(),
                    kind = "gif",
                    assetUrl = assetUrl,
                    previewUrl = resolveUrl(item.optString("previewUrl")).ifBlank { assetUrl },
                    title = item.optString("title").trim(),
                    width = item.optInt("width", 0).coerceIn(0, 4096),
                    height = item.optInt("height", 0).coerceIn(0, 4096),
                    durationSeconds = item.optDouble("durationSeconds", 0.0)
                        .coerceIn(0.0, 300.0)
                )
            }
        }
        return AloGifCatalogPage(
            gifs = gifs,
            nextOffset = json.optString("nextOffset").trim()
        )
    }

    private fun checkedCatalogUrl(value: URL): URL {
        val protocol = value.protocol.lowercase()
        val isAloOrigin = isSameServerOrigin(value)
        val allowed = protocol == "https" ||
            (BuildConfig.ALO_DEV_HOOKS && protocol == "http" && isAloOrigin)
        if (!allowed) {
            throw IOException("Каталог вернул небезопасную ссылку на медиа.")
        }
        if (value.userInfo != null || value.host.isBlank()) {
            throw IOException("Каталог вернул некорректную ссылку на медиа.")
        }
        return value
    }

    private fun isSameServerOrigin(value: URL): Boolean {
        val server = runCatching { URL(baseUrl) }.getOrNull() ?: return false
        fun port(url: URL): Int = when {
            url.port >= 0 -> url.port
            url.protocol.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return value.protocol.equals(server.protocol, ignoreCase = true) &&
            value.host.equals(server.host, ignoreCase = true) &&
            port(value) == port(server)
    }

    private fun isSupportedCatalogMedia(contentType: String, source: URL): Boolean {
        if (contentType.startsWith("image/") || contentType.startsWith("video/")) return true
        if (contentType.isNotBlank()) return false
        val extension = source.path.substringAfterLast('.', "").lowercase()
        return extension in setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "webm")
    }

    private fun catalogFileExtension(
        contentType: String,
        source: URL,
        media: AloCatalogExpressionMedia
    ): String {
        val fromType = when (contentType) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> ""
        }
        if (fromType.isNotBlank()) return fromType
        val fromUrl = source.path.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "webm") }
        if (fromUrl != null) return fromUrl
        return if (media.isGif) "gif" else "webp"
    }

    private fun parseQrLoginSession(
        response: JSONObject,
        fallbackId: String = "",
        fallbackSecret: String = ""
    ): AloQrLoginSession {
        val payload = response.optJSONObject("session")
            ?: response.optJSONObject("data")
            ?: response
        // The server answers {requestId, qrPayload: alo://login/qr/<id>/<secret>}: the secret travels only
        // inside the code, as iOS's AloQRCodeLoginPayload reads it.
        val fromCode = AloQrLoginPayload.parse(payload.optString("qrPayload"))
        val id = payload.optString("id")
            .ifBlank { payload.optString("sessionId") }
            .ifBlank { payload.optString("requestId") }
            .ifBlank { fromCode?.id.orEmpty() }
            .ifBlank { fallbackId }
            .trim()
        val secret = payload.optString("secret")
            .ifBlank { payload.optString("sessionSecret") }
            .ifBlank { fromCode?.secret.orEmpty() }
            .ifBlank { fallbackSecret }
            .trim()
        require(id.isNotBlank() && secret.isNotBlank()) { "Сервер не вернул данные QR-входа." }
        val defaultPayload = "alo://qr-login?id=${urlEncode(id)}&secret=${urlEncode(secret)}"
        return AloQrLoginSession(
            id = id,
            secret = secret,
            qrPayload = payload.optString("qrPayload")
                .ifBlank { payload.optString("qrUrl") }
                .ifBlank { payload.optString("url") }
                .ifBlank { defaultPayload },
            status = payload.optString("status").ifBlank { "pending" },
            expiresAt = payload.optString("expiresAt"),
            approvedAt = payload.optString("approvedAt"),
            claimed = payload.optBoolean("claimed")
        )
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun HttpURLConnection.applyInstallIdentityHeader() {
        if (installDeviceId.isNotBlank()) {
            setRequestProperty("X-Alo-Device-ID", installDeviceId)
        }
    }

    private fun safeLogUrl(value: String): String =
        value.replace(
            Regex("([?&](?:secret|token|challenge|code)=)[^&]*", RegexOption.IGNORE_CASE),
            "$1•••"
        )

    private fun attachmentFileName(contentDisposition: String?): String {
        val header = contentDisposition.orEmpty()
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
        if (!encoded.isNullOrBlank()) {
            return runCatching {
                URLDecoder.decode(encoded, Charsets.UTF_8.name())
            }.getOrDefault(encoded)
        }
        return Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun sanitizeExportFileName(value: String): String {
        val normalized = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim()
            .trim('.')
            .take(96)
            .ifBlank { "alo-account-export" }
        return if (normalized.endsWith(".json", ignoreCase = true)) normalized else "$normalized.json"
    }

    private fun parseUrlList(array: JSONArray?, fallback: String = ""): List<String> {
        val urls = mutableListOf<String>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val resolved = resolveUrl(array.optString(index))
                if (resolved.isNotBlank() && resolved !in urls) {
                    urls += resolved
                }
            }
        }
        val fallbackResolved = resolveUrl(fallback)
        if (fallbackResolved.isNotBlank() && fallbackResolved !in urls) {
            urls.add(0, fallbackResolved)
        }
        return urls
    }

    private suspend fun syncCookies(connection: HttpURLConnection) {
        val setCookies = connection.headerFields
            .filterKeys { key -> key != null && key.equals("Set-Cookie", ignoreCase = true) }
            .values
            .flatten()

        if (setCookies.isEmpty()) return

        withContext(Dispatchers.Main) {
            val cookieManager = CookieManager.getInstance()
            setCookies.forEach { cookie ->
                cookieManager.setCookie(cookieUrl, cookie)
            }
            cookieManager.flush()
        }
    }

    private fun HttpURLConnection.readBodySafely(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        if (stream == null) return ""

        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }

    private fun String.toJsonObject(): JSONObject {
        val trimmed = trim()
        if (trimmed.isEmpty()) return JSONObject()
        return runCatching { JSONObject(trimmed) }.getOrElse {
            JSONObject().put("raw", trimmed)
        }
    }

    private data class ParsedE2eeDataUrl(
        val mime: String,
        val bytes: ByteArray
    )

    private fun String.parseDataUrlForE2ee(): ParsedE2eeDataUrl {
        val trimmed = trim()
        val commaIndex = trimmed.indexOf(',')
        if (!trimmed.startsWith("data:", ignoreCase = true) || commaIndex < 0 || commaIndex == trimmed.lastIndex) {
            return ParsedE2eeDataUrl(
                mime = "application/octet-stream",
                bytes = trimmed.toByteArray(Charsets.UTF_8)
            )
        }
        val header = trimmed.substring(startIndex = 5, endIndex = commaIndex)
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        val payload = trimmed.substring(commaIndex + 1)
        return ParsedE2eeDataUrl(
            mime = mime,
            bytes = Base64.decode(payload, Base64.DEFAULT)
        )
    }
}

/** `pinned` of /api/chat/pins or of a `chat_pins_updated` update; null when the field is missing. */
internal fun parseChatPinsOrNull(json: JSONObject?): List<String>? {
    val array = json?.optJSONArray("pinned") ?: return null
    return (0 until array.length())
        .map { array.optString(it) }
        .filter { Regex("^(direct|group):\\d+$").matches(it) }
        .distinct()
}

internal fun parseChatPins(json: JSONObject?): List<String> = parseChatPinsOrNull(json).orEmpty()
