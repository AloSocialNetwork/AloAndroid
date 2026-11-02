package com.alo.android

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secret chats of this device (docs/cloud-and-secret-chats.md, stage 2): the list, the open chat, sending, read marks,
 * timers and the chat being deleted. State lives in [AloNativeAppUiState]; this class only changes it.
 */
internal class AloSecretChatCoordinator(
    private val apiClient: AloApiClient,
    private val scope: CoroutineScope,
    private val draftStore: AloDraftAttachmentStore,
    private val getState: () -> AloNativeAppUiState,
    private val setState: (AloNativeAppUiState) -> Unit,
    private val pickAttachment: () -> Unit,
    private val handleSessionExpired: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var ticker: Job? = null
    private var ownerUserId = 0

    private fun update(transform: (AloNativeAppUiState) -> AloNativeAppUiState) = setState(transform(getState()))

    private fun myId(): Int = getState().bootstrap?.me?.id ?: 0

    fun handle(action: AloNativeAppAction) {
        when (action) {
            is AloNativeAppAction.StartSecretChat -> start(action.userId)
            is AloNativeAppAction.OpenSecretChat -> open(action.id)
            AloNativeAppAction.CloseSecretChat -> close()
            AloNativeAppAction.AcceptSecretChat -> accept()
            AloNativeAppAction.DeleteSecretChat -> delete()
            is AloNativeAppAction.SetSecretChatTimer -> setTimer(action.seconds)
            is AloNativeAppAction.SendSecretMessage -> send(action.text)
            AloNativeAppAction.PickSecretAttachment -> pickAttachment()
            is AloNativeAppAction.RemoveSecretDraft -> removeDraft(action.index)
            else -> Unit
        }
    }

    // ---- list ----

    /** At start, when the app returns to the front and on every `secret_chats` event: the list, and the open chat. */
    fun refreshAll() {
        val me = myId()
        if (me <= 0) return
        scope.launch {
            try {
                if (ownerUserId != me) {
                    ownerUserId = me
                    update { it.copy(secretChats = emptyList(), activeSecretChatId = 0, activeSecretMessages = emptyList()) }
                }
                val available = apiClient.secretChatsAvailable()
                if (!available) {
                    update { it.copy(secretChatsAvailable = false) }
                    return@launch
                }
                val chats = apiClient.loadSecretChats()
                update { it.copy(secretChatsAvailable = true, secretChats = chats) }
                val activeId = getState().activeSecretChatId
                if (activeId > 0) {
                    if (chats.none { it.id == activeId }) {
                        update { it.copy(isSecretChatGone = true, isSecretChatLoading = false) }
                    } else {
                        loadActive()
                    }
                }
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
            } catch (_: Exception) {
                // The list stays as it was; the next event or the next start reads it again.
            }
        }
    }

    /** A `secret_chats` event: a deleted chat disappears at once, anything else is read again. */
    fun onUpdate(secretChatId: Int, closed: Boolean) {
        if (closed && secretChatId > 0) {
            removeEverywhere(secretChatId)
            return
        }
        refreshAll()
    }

    private fun removeEverywhere(id: Int) {
        val state = getState()
        if (state.activeSecretChatId == id) {
            purgeMedia(state.activeSecretMessages)
            update {
                it.copy(
                    secretChats = AloSecretChatList.removing(it.secretChats, id),
                    activeSecretMessages = emptyList(),
                    isSecretChatGone = true,
                    isSecretChatLoading = false
                )
            }
        } else {
            state.secretChats.firstOrNull { it.id == id }?.lastMessage?.let { purgeMedia(listOf(it)) }
            update { it.copy(secretChats = AloSecretChatList.removing(it.secretChats, id)) }
        }
    }

    // ---- open chat ----

    fun open(id: Int) {
        if (id <= 0) return
        update {
            it.copy(
                activeTab = AloAppTab.Messages,
                activeSecretChatId = id,
                activeSecretMessages = emptyList(),
                isSecretChatLoading = true,
                isSecretChatGone = false,
                isSendingSecret = false,
                secretDraftAttachments = emptyList(),
                errorMessage = null
            )
        }
        startTicker()
        scope.launch { loadActive() }
    }

    fun close() {
        val state = getState()
        ticker?.cancel()
        ticker = null
        // Leaving the screen keeps the messages: their text exists nowhere else on this device but the local copy.
        state.secretDraftAttachments.forEach(draftStore::deleteDraftAttachmentFiles)
        update {
            it.copy(
                activeSecretChatId = 0,
                activeSecretMessages = emptyList(),
                isSecretChatLoading = false,
                isSecretChatGone = false,
                isSendingSecret = false,
                secretDraftAttachments = emptyList()
            )
        }
    }

    private suspend fun loadActive() {
        val id = getState().activeSecretChatId
        if (id <= 0) return
        val chat = getState().secretChats.firstOrNull { it.id == id }
        // A request nobody has accepted has no messages for this device to read.
        if (chat != null && chat.isIncomingRequest) {
            update { if (it.activeSecretChatId == id) it.copy(isSecretChatLoading = false) else it }
            return
        }
        try {
            val conversation = apiClient.loadSecretMessages(id)
            if (getState().activeSecretChatId != id) return
            val visible = AloSecretTimers.visible(conversation.messages, now())
            // A message whose time ran out while this device was away is gone for good, text and files.
            purgeMedia(conversation.messages.filter { AloSecretTimers.isExpired(it, now()) })
            update {
                it.copy(
                    activeSecretMessages = visible,
                    isSecretChatLoading = false,
                    isSecretChatGone = false,
                    secretChats = conversation.chat?.let { fresh -> AloSecretChatList.upserting(it.secretChats, fresh) }
                        ?: it.secretChats
                )
            }
            markRead()
        } catch (error: AloApiException) {
            when {
                error.status == 401 -> handleSessionExpired()
                error.status == 404 -> removeEverywhere(id)
                else -> update { it.copy(isSecretChatLoading = false, errorMessage = error.message) }
            }
        } catch (_: Exception) {
            update { it.copy(isSecretChatLoading = false) }
        }
    }

    /** Opening a chat reads what the other person sent: their timers start now, and they are told. */
    private fun markRead() {
        val state = getState()
        val id = state.activeSecretChatId
        if (id <= 0) return
        val result = AloSecretTimers.applyRead(state.activeSecretMessages, myId(), now())
        if (result.upToMessageId <= 0) return
        update {
            it.copy(
                activeSecretMessages = result.messages,
                secretChats = it.secretChats.map { chat -> if (chat.id == id) chat.copy(unreadCount = 0) else chat }
            )
        }
        scope.launch {
            try {
                apiClient.markSecretChatRead(id, result.upToMessageId)
            } catch (_: Exception) {
                // The server marks them at the next open; the local timer already runs.
            }
        }
    }

    // ---- start, accept, delete ----

    fun start(userId: Int) {
        if (userId <= 0) return
        scope.launch {
            try {
                if (!apiClient.secretChatsAvailable()) {
                    update { it.copy(errorMessage = "Секретные чаты пока недоступны на этом устройстве. Подождите секунду, ключи создаются.") }
                    return@launch
                }
                val chat = apiClient.startSecretChat(userId)
                update {
                    it.copy(
                        activeViewedProfileTarget = null,
                        viewedProfile = null,
                        isViewedProfileLoading = false,
                        isChatPeerProfileOpen = false,
                        secretChatsAvailable = true,
                        secretChats = AloSecretChatList.upserting(it.secretChats, chat)
                    )
                }
                open(chat.id)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    // 409 peer_not_ready (no device yet), 403 (closed for messages): the server's words say why.
                    update { it.copy(errorMessage = error.message) }
                }
            } catch (_: Exception) {
                update { it.copy(errorMessage = "Не удалось начать секретный чат.") }
            }
        }
    }

    private fun accept() {
        val id = getState().activeSecretChatId
        if (id <= 0) return
        scope.launch {
            try {
                val chat = apiClient.acceptSecretChat(id)
                update { it.copy(secretChats = AloSecretChatList.upserting(it.secretChats, chat)) }
                loadActive()
            } catch (error: AloApiException) {
                when {
                    error.status == 401 -> handleSessionExpired()
                    // Another device of this account took the request first: it is not ours any more.
                    error.code == "secret_chat_taken" -> {
                        removeEverywhere(id)
                        update { it.copy(errorMessage = error.message) }
                    }
                    else -> update { it.copy(errorMessage = error.message) }
                }
            } catch (_: Exception) {
                update { it.copy(errorMessage = "Не удалось принять секретный чат.") }
            }
        }
    }

    private fun delete() {
        val id = getState().activeSecretChatId
        if (id <= 0) return
        scope.launch {
            try {
                apiClient.deleteSecretChat(id)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                    return@launch
                }
                // 404: it is gone already, which is what was asked.
                if (error.status != 404) {
                    update { it.copy(errorMessage = error.message) }
                    return@launch
                }
            } catch (_: Exception) {
                update { it.copy(errorMessage = "Не удалось удалить секретный чат.") }
                return@launch
            }
            purgeMedia(getState().activeSecretMessages)
            update { it.copy(secretChats = AloSecretChatList.removing(it.secretChats, id)) }
            close()
        }
    }

    // ---- timer ----

    private fun setTimer(seconds: Int) {
        val id = getState().activeSecretChatId
        if (id <= 0) return
        scope.launch {
            try {
                apiClient.setSecretChatTimer(id, seconds)
                update { state ->
                    state.copy(secretChats = state.secretChats.map { if (it.id == id) it.copy(ttlSeconds = seconds) else it })
                }
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else update { it.copy(errorMessage = error.message) }
            } catch (_: Exception) {
                update { it.copy(errorMessage = "Не удалось сменить таймер.") }
            }
        }
    }

    /** Messages whose time is up disappear from the screen without waiting for the server; their files go with them. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000L)
                val state = getState()
                if (state.activeSecretChatId <= 0) break
                val visible = AloSecretTimers.visible(state.activeSecretMessages, now())
                if (visible.size != state.activeSecretMessages.size) {
                    val gone = state.activeSecretMessages.filter { message -> visible.none { it.id == message.id } }
                    purgeMedia(gone)
                    update { it.copy(activeSecretMessages = visible) }
                }
            }
        }
    }

    // ---- sending ----

    fun attach(uri: Uri) {
        if (getState().secretDraftAttachments.size >= MAX_DRAFTS) return
        scope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftStore.buildDraftFromContentUri(uri = uri, forcedName = null, forceVideoCircle = false)
                }
                update { it.copy(secretDraftAttachments = (it.secretDraftAttachments + draft).take(MAX_DRAFTS)) }
            } catch (_: Exception) {
                update { it.copy(errorMessage = "Не удалось подготовить файл.") }
            }
        }
    }

    private fun removeDraft(index: Int) {
        val drafts = getState().secretDraftAttachments
        val removed = drafts.getOrNull(index) ?: return
        draftStore.deleteDraftAttachmentFiles(removed)
        update { it.copy(secretDraftAttachments = drafts.filterIndexed { position, _ -> position != index }) }
    }

    private fun send(text: String) {
        val state = getState()
        val id = state.activeSecretChatId
        val chat = state.secretChats.firstOrNull { it.id == id } ?: return
        val body = text.trim()
        val drafts = state.secretDraftAttachments
        if (state.isSendingSecret || (body.isEmpty() && drafts.isEmpty()) || !chat.isActive) return
        update { it.copy(isSendingSecret = true, errorMessage = null) }
        scope.launch {
            try {
                val attachments = drafts.map { uploadDraft(it) }
                val message = apiClient.sendSecretMessage(
                    chat = chat,
                    text = body,
                    attachments = attachments,
                    selfDestructSeconds = AloSecretTimers.secondsForNewMessage(chat)
                )
                drafts.forEach(draftStore::deleteDraftAttachmentFiles)
                update {
                    if (it.activeSecretChatId != id) {
                        it.copy(isSendingSecret = false)
                    } else {
                        it.copy(
                            isSendingSecret = false,
                            secretDraftAttachments = emptyList(),
                            activeSecretMessages = (it.activeSecretMessages.filterNot { old -> old.id == message.id } + message)
                                .sortedBy { m -> m.id },
                            secretChats = it.secretChats.map { c -> if (c.id == id) c.copy(lastMessage = message) else c }
                        )
                    }
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (error.status == 404 || error.status == 410) {
                    removeEverywhere(id)
                }
                update { it.copy(isSendingSecret = false, errorMessage = error.message) }
            } catch (_: Exception) {
                update { it.copy(isSendingSecret = false, errorMessage = "Не удалось отправить секретное сообщение.") }
            }
        }
    }

    /** A file of a secret chat is sealed on this device (the draft already is) and uploaded as an opaque `e2ee` file. */
    private suspend fun uploadDraft(draft: AloChatDraftAttachment): AloOutgoingMessageAttachment {
        val encryptedFile = draftStore.encryptedFileForDraft(draft)
            ?: throw IllegalStateException("Вложение больше недоступно.")
        val uploaded = apiClient.uploadMessageE2eeAttachment(
            encryptedFile = encryptedFile,
            mediaEncryption = draft.mediaEncryption,
            checksum = draft.encryptedChecksum
        )
        return AloSecretChatCodec.outgoingAttachment(draft, uploaded.url, uploaded.mediaEncryption)
    }

    /**
     * A voice message or circle the recorder finished in this chat: it waits in the composer like a picked file, or
     * ([sendNow]) goes out at once. A recording that ended after the chat was closed is thrown away.
     */
    fun addRecordedDraft(chatId: Int, draft: AloChatDraftAttachment, sendNow: Boolean) {
        val state = getState()
        val chat = state.secretChats.firstOrNull { it.id == chatId }
        if (state.activeSecretChatId != chatId || chat == null || !chat.isActive || state.isSecretChatGone) {
            draftStore.deleteDraftAttachmentFiles(draft)
            return
        }
        // One recording at a time, as in an ordinary chat: a new one replaces the one waiting.
        val recorded = state.secretDraftAttachments.filter { it.isVideoCircle || it.attachmentType == "voice" }
        recorded.forEach(draftStore::deleteDraftAttachmentFiles)
        update { it.copy(secretDraftAttachments = (it.secretDraftAttachments - recorded.toSet() + draft).takeLast(MAX_DRAFTS)) }
        if (sendNow) send("")
    }

    /** What a secret message left on the device (its own copy of the text, the decrypted files) goes when the message does. */
    private fun purgeMedia(messages: List<AloSecretMessage>) {
        if (messages.isNotEmpty()) apiClient.forgetSecretMessages(messages)
    }

    private companion object {
        const val MAX_DRAFTS = 10
    }
}

/** A secret message in the shape the media viewer and file opener take. */
internal fun AloSecretMessage.toChatMessage(): AloChatMessage {
    val first = attachments.firstOrNull()
    return AloChatMessage(
        id = id,
        roomId = 0,
        senderId = senderUserId,
        recipientId = 0,
        body = body,
        attachmentUrl = first?.url.orEmpty(),
        attachmentType = first?.type.orEmpty(),
        attachmentName = first?.name.orEmpty(),
        attachments = attachments,
        createdAt = createdAt,
        editedAt = "",
        readAt = readAt,
        entities = entities,
        expiresAt = expiresAt
    )
}
