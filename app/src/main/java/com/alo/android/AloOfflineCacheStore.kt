package com.alo.android

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A result produced from the device-local encrypted message index. The search
 * query and decrypted message bodies never leave the handset.
 */
internal data class AloOfflineMessageSearchHit(
    val conversation: AloConversationDetail,
    val message: AloChatMessage
) {
    val peerId: Int
        get() = conversation.peerId

    val stableId: String
        get() = "${conversation.kind}:${conversation.peerId}:${conversation.roomId}:${message.id}"
}

internal class AloOfflineCacheStore(
    context: Context,
    private val prefs: SharedPreferences
) {
    private val cacheRoot = File(context.filesDir, CACHE_DIR_NAME)
    private val legacyBootstrapFile = File(cacheRoot, LEGACY_BOOTSTRAP_FILE_NAME)
    private val historyLock = Any()

    /**
     * Saving means decrypting a whole file, JSON both ways and encrypting it again, through the phone's hardware key store,
     * which takes far longer on a real phone than on an emulator. So it never happens on the main thread: changes wait here
     * for a moment, the latest one wins, and a single background writer saves them. A chat just saved is also kept in memory,
     * so opening it again needs no disk at all.
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pendingLock = Any()
    private var pendingMessages: AloBootstrapData? = null
    private val pendingConversations = LinkedHashMap<Int, Pair<AloConversationDetail, Int>>()
    private var pendingWrite: Job? = null
    private val recentConversations = object : LinkedHashMap<String, AloConversationDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AloConversationDetail>?) = size > RECENT_CONVERSATIONS
    }

    fun scheduleMessagesUpdate(bootstrap: AloBootstrapData) {
        synchronized(pendingLock) { pendingMessages = bootstrap }
        scheduleWrite()
    }

    fun scheduleConversationSave(conversation: AloConversationDetail, ownerUserId: Int) {
        if (ownerUserId <= 0 || conversation.peerId <= 0) return
        synchronized(pendingLock) {
            pendingConversations[conversation.peerId] = conversation to ownerUserId
            recentConversations["$ownerUserId:${conversation.peerId}"] = conversation
        }
        scheduleWrite()
    }

    /** The app goes to the background: don't wait, the process may be killed. */
    fun flushPendingWrites() {
        synchronized(pendingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
        }
        writer.launch { writePending() }
    }

    /** Whatever else touches the files, off the main thread and after the writes already waiting. */
    fun inBackground(work: AloOfflineCacheStore.() -> Unit) {
        writer.launch { runCatching { work() } }
    }

    /** The chat as last seen in this run of the app, without touching the disk. */
    fun recentConversation(peerId: Int, ownerUserId: Int): AloConversationDetail? =
        synchronized(pendingLock) { recentConversations["$ownerUserId:$peerId"] }

    private fun scheduleWrite() {
        synchronized(pendingLock) {
            if (pendingWrite?.isActive == true) return
            pendingWrite = writer.launch {
                delay(WRITE_DELAY_MS)
                synchronized(pendingLock) { pendingWrite = null }
                writePending()
            }
        }
    }

    private fun writePending() {
        val (messages, conversations) = synchronized(pendingLock) {
            val taken = pendingMessages to pendingConversations.values.toList()
            pendingMessages = null
            pendingConversations.clear()
            taken
        }
        messages?.let { runCatching { updateMessages(it) } }
        conversations.forEach { (conversation, ownerUserId) -> saveConversation(conversation, ownerUserId) }
    }

    fun saveBootstrap(rawJson: JSONObject) {
        writeBootstrap(rawJson)
    }

    fun readBootstrapRaw(ownerUserId: Int): JSONObject? {
        if (ownerUserId <= 0) return null
        val encrypted = bootstrapFile(ownerUserId)
        if (encrypted.isFile) {
            return runCatching {
                AloOfflineAccountPolicy.bootstrapForOwner(
                    JSONObject(String(readEncrypted(encrypted), Charsets.UTF_8)), ownerUserId
                )
            }.getOrNull()
        }
        return migrateLegacyBootstrap(ownerUserId)
    }

    fun updateMessages(bootstrap: AloBootstrapData) {
        val root = readBootstrapRaw(bootstrap.me.id) ?: return
        val data = root.optJSONObject("data") ?: JSONObject()
        data.put(
            "messages",
            JSONObject()
                .put("conversations", bootstrap.messagesConversations.toConversationsJsonArray())
                .put("activePeerId", bootstrap.messagesActivePeerId)
                .put("items", bootstrap.messagesItems.toMessagesJsonArray())
        )
        root.put("data", data)
        writeBootstrap(root)
    }

    fun saveConversation(conversation: AloConversationDetail, ownerUserId: Int) {
        if (ownerUserId <= 0 || conversation.peerId <= 0) return
        runCatching {
            writeEncrypted(
                conversationFile(conversation.peerId, ownerUserId),
                conversation.toJson().toString().toByteArray(Charsets.UTF_8)
            )
            mergeSearchHistory(conversation, ownerUserId, complete = false)
            prefs.edit().putInt(PREF_LAST_OWNER_USER_ID, ownerUserId).apply()
        }
    }

    fun readConversation(peerId: Int, ownerUserId: Int): AloConversationDetail? {
        if (peerId <= 0 || ownerUserId <= 0) return null
        val file = conversationFile(peerId, ownerUserId)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(String(readEncrypted(file), Charsets.UTF_8)).toConversationDetail()
        }.getOrNull()
    }

    fun saveBlockedUserIds(ownerUserId: Int, blockedUserIds: Set<Int>) {
        if (ownerUserId <= 0) return
        val payload = JSONObject().put(
            "userIds",
            JSONArray(blockedUserIds.filter { it > 0 }.sorted())
        )
        runCatching {
            writeEncrypted(
                blockedUsersFile(ownerUserId),
                payload.toString().toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun readBlockedUserIds(ownerUserId: Int): Set<Int> {
        if (ownerUserId <= 0) return emptySet()
        val file = blockedUsersFile(ownerUserId)
        if (!file.isFile) return emptySet()
        return runCatching {
            val json = JSONObject(String(readEncrypted(file), Charsets.UTF_8))
            val ids = json.optJSONArray("userIds") ?: JSONArray()
            buildSet {
                for (index in 0 until ids.length()) {
                    ids.optInt(index).takeIf { it > 0 }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun searchMessages(ownerUserId: Int, peerId: Int, query: String, limit: Int = 100): List<AloChatMessage> {
        val normalized = query.trim().lowercase()
        if (ownerUserId <= 0 || peerId <= 0 || normalized.isEmpty()) return emptyList()
        val detail = readSearchHistory(peerId, ownerUserId)
            ?: readConversation(peerId, ownerUserId)
            ?: return emptyList()
        return detail.items.asReversed()
            .asSequence()
            .filter { message -> matchesSearch(message, normalized) }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    /**
     * Searches all encrypted conversation indexes for the active account.
     * The directory names contain only numeric peer IDs; every candidate file
     * is authenticated and decrypted locally before it is searched.
     */
    fun searchAllMessages(ownerUserId: Int, query: String, limit: Int = 100): List<AloOfflineMessageSearchHit> {
        val normalized = query.trim().lowercase()
        if (ownerUserId <= 0 || normalized.isBlank()) return emptyList()

        return synchronized(historyLock) {
            val root = ownerRoot(ownerUserId)
            val peerIds = root.listFiles()
                .orEmpty()
                .mapNotNull { file -> peerIdForConversationFile(file.name) }
                .toSet()
            val seen = mutableSetOf<String>()
            val hits = mutableListOf<AloOfflineMessageSearchHit>()

            peerIds.sorted().forEach { peerId ->
                val detail = readSearchHistoryJson(peerId, ownerUserId)?.toConversationDetail()
                    ?: readConversation(peerId, ownerUserId)
                    ?: return@forEach
                detail.items.forEach messageLoop@ { message ->
                    if (!matchesSearch(message, normalized)) return@messageLoop
                    val key = "$peerId:${message.id}"
                    if (seen.add(key)) {
                        hits += AloOfflineMessageSearchHit(detail, message)
                    }
                }
            }

            hits
                .sortedWith(
                    compareByDescending<AloOfflineMessageSearchHit> { it.message.createdAt }
                        .thenByDescending { it.stableId }
                )
                .take(limit.coerceIn(1, 500))
        }
    }

    fun mergeSearchHistory(
        conversation: AloConversationDetail,
        ownerUserId: Int,
        complete: Boolean
    ) {
        if (ownerUserId <= 0 || conversation.peerId <= 0) return
        synchronized(historyLock) {
            val existingJson = readSearchHistoryJson(conversation.peerId, ownerUserId)
            val existing = existingJson?.toConversationDetail()
            val mergedItems = buildMap<Int, AloChatMessage> {
                existing?.items.orEmpty().forEach { message ->
                    if (message.id > 0) put(message.id, message)
                }
                conversation.items.forEach { message ->
                    if (message.id > 0) put(message.id, message)
                }
            }.values.sortedBy(AloChatMessage::id)
            val merged = conversation.copy(items = mergedItems)
            val payload = merged.toJson()
                .put("historyComplete", existingJson?.optBoolean("historyComplete") == true || complete)
            runCatching {
                writeEncrypted(
                    conversationSearchFile(conversation.peerId, ownerUserId),
                    payload.toString().toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    fun removeSearchMessages(peerId: Int, ownerUserId: Int, messageIds: Set<Int>) {
        if (peerId <= 0 || ownerUserId <= 0 || messageIds.isEmpty()) return
        updateSearchHistory(peerId, ownerUserId) { detail ->
            detail.copy(items = detail.items.filterNot { it.id in messageIds })
        }
    }

    fun reassignSearchHistoryTopic(
        peerId: Int,
        ownerUserId: Int,
        sourceTopicId: Int,
        targetTopicId: Int
    ) {
        if (
            peerId <= 0 || ownerUserId <= 0 || sourceTopicId <= 0 || targetTopicId <= 0 ||
            sourceTopicId == targetTopicId
        ) return
        updateSearchHistory(peerId, ownerUserId) { detail ->
            detail.copy(
                items = detail.items.map { message ->
                    if (message.topicId == sourceTopicId) {
                        message.copy(topicId = targetTopicId)
                    } else {
                        message
                    }
                }
            )
        }
    }

    fun isSearchHistoryComplete(peerId: Int, ownerUserId: Int): Boolean =
        synchronized(historyLock) {
            readSearchHistoryJson(peerId, ownerUserId)?.optBoolean("historyComplete") == true
        }

    fun markSearchHistoryComplete(peerId: Int, ownerUserId: Int) {
        if (peerId <= 0 || ownerUserId <= 0) return
        synchronized(historyLock) {
            val existing = readSearchHistoryJson(peerId, ownerUserId)
            val payload = JSONObject()
                .put("items", existing?.optJSONArray("items") ?: JSONArray())
                .put("historyComplete", true)
            runCatching {
                writeEncrypted(
                    conversationSearchFile(peerId, ownerUserId),
                    payload.toString().toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    fun oldestSearchMessageId(peerId: Int, ownerUserId: Int): Int =
        synchronized(historyLock) {
            readSearchHistoryJson(peerId, ownerUserId)
                ?.toConversationDetail()
                ?.items
                ?.asSequence()
                ?.map(AloChatMessage::id)
                ?.filter { it > 0 }
                ?.minOrNull()
                ?: 0
        }

    private fun updateSearchHistory(
        peerId: Int,
        ownerUserId: Int,
        transform: (AloConversationDetail) -> AloConversationDetail
    ) {
        synchronized(historyLock) {
            val existingJson = readSearchHistoryJson(peerId, ownerUserId) ?: return
            val complete = existingJson.optBoolean("historyComplete")
            val updated = transform(existingJson.toConversationDetail())
            val payload = updated.toJson().put("historyComplete", complete)
            runCatching {
                writeEncrypted(
                    conversationSearchFile(peerId, ownerUserId),
                    payload.toString().toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    fun clear(ownerUserId: Int) {
        if (ownerUserId <= 0) return
        // Nothing of this person may be written back after the sign-out.
        synchronized(pendingLock) {
            pendingMessages = pendingMessages?.takeIf { it.me.id != ownerUserId }
            pendingConversations.values.removeAll { it.second == ownerUserId }
            recentConversations.keys.removeAll { it.startsWith("$ownerUserId:") }
        }
        writer.launch { deleteOwnerFiles(ownerUserId) }
        deleteOwnerFiles(ownerUserId)
    }

    private fun deleteOwnerFiles(ownerUserId: Int) {
        runCatching { ownerRoot(ownerUserId).deleteRecursively() }
        runCatching {
            if (legacyBootstrapFile.isFile) {
                val legacy = JSONObject(legacyBootstrapFile.readText(Charsets.UTF_8))
                if (AloOfflineAccountPolicy.bootstrapForOwner(legacy, ownerUserId) != null) legacyBootstrapFile.delete()
            }
        }
        if (prefs.getInt(PREF_LAST_OWNER_USER_ID, 0) == ownerUserId) {
            prefs.edit()
                .remove(PREF_BOOTSTRAP_CACHED_AT)
                .remove(PREF_LAST_OWNER_USER_ID)
                .apply()
        }
    }

    private fun writeBootstrap(rawJson: JSONObject) {
        val ownerUserId = rawJson.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optInt("id", 0)
            ?: 0
        if (ownerUserId <= 0) return
        runCatching {
            writeEncrypted(
                bootstrapFile(ownerUserId),
                rawJson.toString().toByteArray(Charsets.UTF_8)
            )
            prefs.edit()
                .putLong(PREF_BOOTSTRAP_CACHED_AT, System.currentTimeMillis())
                .putInt(PREF_LAST_OWNER_USER_ID, ownerUserId)
                .apply()
        }
    }

    private fun readSearchHistory(peerId: Int, ownerUserId: Int): AloConversationDetail? =
        synchronized(historyLock) {
            readSearchHistoryJson(peerId, ownerUserId)?.toConversationDetail()
        }

    private fun readSearchHistoryJson(peerId: Int, ownerUserId: Int): JSONObject? {
        if (peerId <= 0 || ownerUserId <= 0) return null
        val file = conversationSearchFile(peerId, ownerUserId)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(String(readEncrypted(file), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun matchesSearch(message: AloChatMessage, normalizedQuery: String): Boolean =
        message.body.lowercase().contains(normalizedQuery) ||
            message.attachmentName.lowercase().contains(normalizedQuery) ||
            message.sender?.name.orEmpty().lowercase().contains(normalizedQuery)

    private fun peerIdForConversationFile(fileName: String): Int? {
        val prefix = "conversation-"
        if (!fileName.startsWith(prefix)) return null
        listOf("-search-v1.bin", "-v1.bin").forEach { suffix ->
            if (!fileName.endsWith(suffix)) return@forEach
            val value = fileName
                .removePrefix(prefix)
                .removeSuffix(suffix)
                .toIntOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    private fun migrateLegacyBootstrap(ownerUserId: Int): JSONObject? {
        if (!legacyBootstrapFile.isFile) return null
        val raw = runCatching {
            JSONObject(legacyBootstrapFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (AloOfflineAccountPolicy.bootstrapForOwner(raw, ownerUserId) == null) return null
        writeBootstrap(raw)
        if (bootstrapFile(ownerUserId).isFile) {
            legacyBootstrapFile.delete()
        }
        return raw
    }

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(bytes)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun readEncrypted(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.isNotEmpty())
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size > ivSize + 1)
        val iv = bytes.copyOfRange(1, ivSize + 1)
        val encrypted = bytes.copyOfRange(ivSize + 1, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    @Volatile private var loadedKey: SecretKey? = null

    private fun encryptionKey(): SecretKey = loadedKey ?: loadEncryptionKey().also { loadedKey = it }

    private fun loadEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun ownerRoot(ownerUserId: Int): File = File(cacheRoot, "user-$ownerUserId")

    private fun bootstrapFile(ownerUserId: Int): File = File(ownerRoot(ownerUserId), BOOTSTRAP_FILE_NAME)

    private fun conversationFile(peerId: Int, ownerUserId: Int): File =
        File(ownerRoot(ownerUserId), "conversation-$peerId-v1.bin")

    private fun conversationSearchFile(peerId: Int, ownerUserId: Int): File =
        File(ownerRoot(ownerUserId), "conversation-$peerId-search-v1.bin")

    private fun blockedUsersFile(ownerUserId: Int): File =
        File(ownerRoot(ownerUserId), BLOCKED_USERS_FILE_NAME)

    private fun List<AloConversationSummary>.toConversationsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { conversation ->
            array.put(conversation.toJson())
        }
        return array
    }

    private fun AloConversationSummary.toJson(): JSONObject {
        return JSONObject()
            .put("peerId", peerId)
            .put("kind", kind)
            .put("roomId", roomId)
            .put("memberCount", memberCount)
            .put("lastAt", lastAt)
            .put("lastBody", lastBody)
            .put("peer", peer.toJson())
    }

    private fun AloConversationPeer.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("username", username)
            .put("avatarEmoji", avatarUrl)
            .put("verified", verified)
            .put("entityType", entityType)
            .put("online", online)
            .put("typing", isTyping)
            .put("isTyping", isTyping)
            .put("lastSeenAt", lastSeenAt)
    }

    private fun AloConversationDetail.toJson(): JSONObject {
        return JSONObject()
            .put("peerId", peerId)
            .put("kind", kind)
            .put("roomId", roomId)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("username", username)
            .put("profileDescription", profileDescription)
            .put("lastSeenAt", lastSeenAt)
            .put("avatarUrl", avatarUrl)
            .put("verified", verified)
            .put("online", online)
            .put("isTyping", isTyping)
            .put("memberCount", memberCount)
            .put("entityType", entityType)
            .put("memberRole", memberRole)
            .put("canManage", canManage)
            .put("members", JSONArray().also { array ->
                members.forEach { member ->
                    // The admin's own title and anonymity too: without them a group reopened from the cache showed the
                    // generic «Админ» over an admin's messages until the network answered with «Модератор».
                    array.put(
                        JSONObject().put("role", member.role).put("user", member.user.toJson())
                            .put("adminTitle", member.adminTitle).put("isAnonymous", member.isAnonymous)
                    )
                }
            })
            .put("groupPolicy", groupPolicy?.toJson())
            .put("forumTopics", JSONArray().also { array ->
                forumTopics.forEach { array.put(it.toJson()) }
            })
            .put("forumTopicStates", JSONArray().also { array ->
                forumTopicStates.forEach { array.put(it.toJson()) }
            })
            .put("pinnedMessageIds", JSONArray(pinnedMessageIds))
            .put("autoDeleteSeconds", autoDeleteSeconds)
            .put("items", items.toMessagesJsonArray())
    }

    private fun AloGroupPolicy.toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("username", username)
        .put("joinPolicy", joinPolicy)
        .put("isForum", isForum)
        .put("historyVisible", historyVisible)
        .put("protectedContent", protectedContent)
        .put("slowModeSeconds", slowModeSeconds)
        .put("autoDeleteSeconds", autoDeleteSeconds)
        .put("defaultPermissions", JSONObject(defaultPermissions))
        .put("memberAdminRights", JSONObject(memberAdminRights))

    private fun AloForumTopic.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("roomId", roomId)
        .put("title", title)
        .put("iconEmoji", iconEmoji)
        .put("isGeneral", isGeneral)
        .put("isClosed", isClosed)
        .put("isHidden", isHidden)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun AloForumTopicState.toJson(): JSONObject = JSONObject()
        .put("topicId", topicId)
        .put("cursor", cursor)
        .put("lastReadMessageId", lastReadMessageId)
        .put("unreadCount", unreadCount)
        .put("muted", muted)
        .put("draftRevision", draftRevision)
        .put("stateVersion", stateVersion)
        .put("draftUpdatedAt", draftUpdatedAt)
        .put("updatedAt", updatedAt)
        .put("draftText", draftText)

    private fun AloEntitySummary.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("username", username)
            .put("avatarUrl", avatarUrl)
            .put("verified", verified)
            .put("isChannel", isChannel)
    }

    private fun List<AloChatMessage>.toMessagesJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { message ->
            array.put(message.toJson())
        }
        return array
    }

    internal fun encodeDraftReply(message: AloChatMessage): JSONObject = message.toJson()
        .put("senderIsAnonymous", message.senderIsAnonymous).put("senderAdminTitle", message.senderAdminTitle).put("localKey", message.localKey)
    internal fun decodeDraftReply(record: JSONObject): AloChatMessage = record.toChatMessage().copy(
        senderIsAnonymous = record.optBoolean("senderIsAnonymous"), senderAdminTitle = record.optString("senderAdminTitle"),
        localKey = record.optInt("localKey"), channelPostId = record.optInt("channelPostId"),
        channelPostCommentCount = record.optInt("channelPostCommentCount"), channelPostCommentsEnabled = record.optBoolean("channelPostCommentsEnabled"))

    private fun AloChatMessage.toJson(): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("roomId", roomId)
            .put("senderId", senderId)
            .put("recipientId", recipientId)
            .put("body", body)
            .put("attachmentUrl", attachmentUrl)
            .put("attachmentType", attachmentType)
            .put("attachmentName", attachmentName)
            .put("attachments", attachments.toAttachmentsJsonArray())
            .put("createdAt", createdAt)
            .put("editedAt", editedAt)
            .put("readAt", readAt)
            .put("selfDestructSeconds", selfDestructSeconds)
            .put("selfDestructStartedAt", selfDestructStartedAt)
            .put("expiresAt", expiresAt)
            .put("topicId", topicId)
            .put("replyToMessageId", replyToMessageId)
            .put("channelPostId", channelPostId)
            .put("channelPostCommentCount", channelPostCommentCount)
            .put("channelPostCommentsEnabled", channelPostCommentsEnabled)
            .put("scheduledMessageId", scheduledMessageId)
            .put("scheduledAt", scheduledAt)
            .put("sendWhenOnline", sendWhenOnline)
            .put("silent", silent)
            .put("deliveryState", deliveryState)
            .put("deliveryError", deliveryError)
            .put("reactions", reactions.toReactionsJsonArray())
            .put("entities", JSONArray().also { array ->
                entities.forEach { array.put(it.toJson()) }
            })
            .put("cloud", cloud)
            .put("encrypted", encrypted)
        sender?.let { json.put("sender", it.toJson()) }
        forwardFrom?.let { json.put("forwardFrom", it.toJson()) }
        return json
    }

    private fun List<AloOutgoingMessageAttachment>.toAttachmentsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { attachment ->
            array.put(AloCloudMessages.storedAttachmentJson(attachment))
        }
        return array
    }

    private fun List<AloMessageReaction>.toReactionsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { reaction ->
            array.put(
                JSONObject()
                    .put("emoji", reaction.emoji)
                    .put("count", reaction.count)
                    .put("reactedByMe", reaction.reactedByMe)
                    .put("users", JSONArray().apply {
                        reaction.users.forEach { user ->
                            put(
                                JSONObject()
                                    .put("id", user.id)
                                    .put("name", user.name)
                                    .put("username", user.username)
                                    .put("avatarUrl", user.avatarUrl)
                                    .put("verified", user.verified)
                            )
                        }
                    })
            )
        }
        return array
    }

    private fun AloMessageForwardSource.toJson(): JSONObject {
        return JSONObject()
            .put("userId", userId)
            .put("messageId", messageId)
            .put("user", JSONObject().put("id", userId).put("name", userName))
    }

    private fun JSONObject.toConversationDetail(): AloConversationDetail {
        return AloConversationDetail(
            peerId = optInt("peerId"),
            kind = optString("kind"),
            roomId = optInt("roomId"),
            title = optString("title"),
            subtitle = optString("subtitle"),
            username = optString("username"),
            profileDescription = optString("profileDescription"),
            lastSeenAt = optString("lastSeenAt"),
            avatarUrl = optString("avatarUrl"),
            verified = optBoolean("verified"),
            online = optBoolean("online"),
            isTyping = optBoolean("isTyping"),
            memberCount = optInt("memberCount"),
            entityType = optString("entityType"),
            memberRole = optString("memberRole"),
            canManage = optBoolean("canManage"),
            members = optJSONArray("members").toGroupMembers(),
            items = optJSONArray("items").toChatMessages(),
            groupPolicy = optJSONObject("groupPolicy").toGroupPolicy(),
            forumTopics = optJSONArray("forumTopics").toForumTopics(),
            forumTopicStates = optJSONArray("forumTopicStates").toForumTopicStates(),
            pinnedMessageIds = optJSONArray("pinnedMessageIds").toPositiveIntList(),
            autoDeleteSeconds = optInt("autoDeleteSeconds").coerceAtLeast(0)
        )
    }

    private fun JSONArray?.toPositiveIntList(): List<Int> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val value = source.optInt(index)
                if (value > 0 && value !in this) add(value)
            }
        }
    }

    private fun JSONObject?.toGroupPolicy(): AloGroupPolicy? {
        val source = this ?: return null
        val permissions = buildMap {
            val json = source.optJSONObject("defaultPermissions") ?: JSONObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optBoolean(key))
            }
        }
        val adminRights = buildMap {
            val json = source.optJSONObject("memberAdminRights") ?: JSONObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optBoolean(key))
            }
        }
        val restrictions = buildMap {
            val json = source.optJSONObject("memberRestrictions") ?: JSONObject()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optBoolean(key))
            }
        }
        return AloGroupPolicy(
            kind = source.optString("kind", "group"),
            username = source.optString("username"),
            joinPolicy = source.optString("joinPolicy", "invite"),
            isForum = source.optBoolean("isForum"),
            historyVisible = source.optBoolean("historyVisible", true),
            slowModeSeconds = source.optInt("slowModeSeconds").coerceAtLeast(0),
            autoDeleteSeconds = source.optInt("autoDeleteSeconds").coerceAtLeast(0),
            defaultPermissions = permissions,
            memberAdminRights = adminRights,
            memberRestrictions = restrictions,
            memberRestrictedUntil = source.optString("memberRestrictedUntil"),
            protectedContent = source.optBoolean("protectedContent")
        )
    }

    private fun JSONArray?.toForumTopics(): List<AloForumTopic> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val id = item.optInt("id")
                if (id <= 0) continue
                add(
                    AloForumTopic(
                        id = id,
                        roomId = item.optInt("roomId"),
                        title = item.optString("title").ifBlank { "Тема" },
                        iconEmoji = item.optString("iconEmoji").ifBlank { "💬" },
                        isGeneral = item.optBoolean("isGeneral"),
                        isClosed = item.optBoolean("isClosed"),
                        isHidden = item.optBoolean("isHidden"),
                        createdAt = item.optString("createdAt"),
                        updatedAt = item.optString("updatedAt")
                    )
                )
            }
        }
    }

    private fun JSONArray?.toForumTopicStates(): List<AloForumTopicState> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val topicId = item.optInt("topicId")
                if (topicId <= 0) continue
                add(
                    AloForumTopicState(
                        topicId = topicId,
                        cursor = item.optInt("cursor").coerceAtLeast(0),
                        lastReadMessageId = item.optInt("lastReadMessageId").coerceAtLeast(0),
                        unreadCount = item.optInt("unreadCount").coerceAtLeast(0),
                        muted = item.optBoolean("muted"),
                        draftRevision = item.optInt("draftRevision").coerceAtLeast(0),
                        stateVersion = item.optInt("stateVersion").coerceAtLeast(0),
                        draftUpdatedAt = item.optString("draftUpdatedAt"),
                        updatedAt = item.optString("updatedAt"),
                        draftText = item.optString("draftText")
                    )
                )
            }
        }
    }

    private fun JSONArray?.toGroupMembers(): List<AloGroupMember> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val user = item.optJSONObject("user") ?: continue
                val id = user.optInt("id")
                if (id <= 0) continue
                add(
                    AloGroupMember(
                        user = AloEntitySummary(
                            id = id,
                            name = user.optString("name"),
                            username = user.optString("username"),
                            avatarUrl = user.optString("avatarUrl"),
                            verified = user.optBoolean("verified"),
                            isChannel = user.optBoolean("isChannel")
                        ),
                        role = item.optString("role"),
                        adminTitle = item.optString("adminTitle"),
                        isAnonymous = item.optBoolean("isAnonymous")
                    )
                )
            }
        }
    }

    private fun JSONArray?.toChatMessages(): List<AloChatMessage> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                add(item.toChatMessage())
            }
        }
    }

    private fun JSONObject.toChatMessage(): AloChatMessage {
        val senderJson = optJSONObject("sender")
        val forwardJson = optJSONObject("forwardFrom")
        val rawBody = optString("body")
        val explicitEntities = AloMessageMarkup.parseEntities(optJSONArray("entities"), rawBody.length)
        val cloudMessage = optBoolean("cloud")
        val markup = when {
            cloudMessage -> AloMessageMarkup.plain(rawBody, explicitEntities)
            explicitEntities.isEmpty() -> AloMessageMarkup.parse(rawBody)
            else -> null
        }
        val resolvedBody = markup?.body ?: rawBody
        return AloChatMessage(
            id = optInt("id"),
            roomId = optInt("roomId"),
            senderId = optInt("senderId"),
            recipientId = optInt("recipientId"),
            body = resolvedBody,
            attachmentUrl = optString("attachmentUrl"),
            attachmentType = optString("attachmentType"),
            attachmentName = optString("attachmentName"),
            attachments = optJSONArray("attachments").toAttachments(),
            createdAt = optString("createdAt"),
            editedAt = optString("editedAt"),
            readAt = optString("readAt"),
            forwardFrom = forwardJson?.let {
                AloMessageForwardSource(
                    userId = it.optInt("userId"),
                    messageId = it.optInt("messageId"),
                    userName = it.optJSONObject("user")?.optString("name").orEmpty()
                )
            },
            sender = senderJson?.toConversationPeer(),
            reactions = optJSONArray("reactions").toReactions(),
            entities = AloMessageMarkup.normalized(
                markup?.entities ?: explicitEntities,
                resolvedBody.length
            ),
            selfDestructSeconds = optInt("selfDestructSeconds").coerceAtLeast(0),
            selfDestructStartedAt = optString("selfDestructStartedAt"),
            expiresAt = optString("expiresAt"),
            topicId = optInt("topicId").coerceAtLeast(0),
            replyToMessageId = optInt("replyToMessageId").coerceAtLeast(0),
            scheduledMessageId = optInt("scheduledMessageId").coerceAtLeast(0),
            scheduledAt = optString("scheduledAt"),
            sendWhenOnline = optBoolean("sendWhenOnline"),
            silent = optBoolean("silent"),
            deliveryState = optString("deliveryState"),
            deliveryError = optString("deliveryError"),
            cloud = cloudMessage,
            encrypted = optBoolean("encrypted")
        )
    }

    private fun JSONObject.toConversationPeer(): AloConversationPeer {
        return AloConversationPeer(
            id = optInt("id"),
            name = optString("name"),
            username = optString("username"),
            avatarUrl = optString("avatarEmoji").ifBlank { optString("avatarUrl") },
            verified = optBoolean("verified"),
            entityType = optString("entityType"),
            online = optBoolean("online"),
            isTyping = optBoolean("isTyping"),
            lastSeenAt = optString("lastSeenAt")
        )
    }

    private fun JSONArray?.toAttachments(): List<AloOutgoingMessageAttachment> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                add(
                    AloOutgoingMessageAttachment(
                        url = item.optString("url"),
                        type = item.optString("type"),
                        name = item.optString("name"),
                        mediaEncryption = AloCloudMessages.stringMap(item.optJSONObject("mediaEncryption")),
                        metadata = AloCloudMessages.stringMap(item.optJSONObject("metadata")),
                        posterUrl = item.optString("posterUrl"),
                        waveform = AloCloudMessages.waveformFrom(item.optJSONArray("waveform"))
                    )
                )
            }
        }
    }

    private fun JSONArray?.toReactions(): List<AloMessageReaction> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                add(
                    AloMessageReaction(
                        emoji = item.optString("emoji"),
                        count = item.optInt("count"),
                        reactedByMe = item.optBoolean("reactedByMe"),
                        users = item.optJSONArray("users")?.let { voters ->
                            (0 until voters.length()).mapNotNull { voters.optJSONObject(it)?.toConversationPeer() }
                        }.orEmpty()
                    )
                )
            }
        }
    }

    private companion object {
        private const val WRITE_DELAY_MS = 800L
        private const val RECENT_CONVERSATIONS = 12
        private const val CACHE_DIR_NAME = "alo-offline-cache"
        private const val BOOTSTRAP_FILE_NAME = "bootstrap-v2.bin"
        private const val BLOCKED_USERS_FILE_NAME = "blocked-users-v1.bin"
        private const val LEGACY_BOOTSTRAP_FILE_NAME = "bootstrap.json"
        private const val PREF_BOOTSTRAP_CACHED_AT = "offline_bootstrap_cached_at"
        private const val PREF_LAST_OWNER_USER_ID = "offline_bootstrap_owner_user_id"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "alo.android.offline-cache.v2"
    }
}
