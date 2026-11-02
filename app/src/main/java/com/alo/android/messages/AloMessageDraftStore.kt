package com.alo.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun isolateMessageDraftOwner(previous: AloNativeAppUiState, next: AloNativeAppUiState): AloNativeAppUiState {
    val owner = previous.bootstrap?.me?.id ?: return next
    if (owner == next.bootstrap?.me?.id) return next
    return next.copy(messageComposerText = "", messageDraftAttachment = null, messageDraftAttachments = emptyList(),
        messageForwardDraft = emptyList(), messageReplyTo = null, messageEditingId = null,
        messageRecorderMode = AloChatRecordMode.Voice, messageCaptureState = AloChatCaptureState.Idle,
        messageRecordingDurationSec = 0, messageRecordingLevels = emptyList(), messageSelfDestructSeconds = 0)
}

internal class AloMessageDraftStore(context: Context, private val codec: AloOfflineCacheStore,
    private val persistToDisk: () -> Boolean = { true },
    private val onFailure: (Throwable) -> Unit = {}) {
    private val secure = AloSecureOutboxStore(context.applicationContext)
    private val attachments = AloDraftAttachmentStore(context.applicationContext)
    private val owners = synchronized(MEMORY) {
        val key = (if (persistToDisk()) "disk:" else "preview:") + context.filesDir.canonicalPath
        MEMORY.getOrPut(key) { mutableMapOf() }
    }
    suspend fun flush() { writer.launch {}.join() }

    private fun drafts(owner: Int): MutableMap<String, PersistedMessageDraft> {
        require(owner > 0)
        return owners.getOrPut(owner) {
            val records = if (persistToDisk()) secure.loadDocument("drafts", owner) else JSONArray()
            mutableMapOf<String, PersistedMessageDraft>().apply {
                repeat(records.length()) { index ->
                    val record = records.getJSONObject(index)
                    require(record.getInt("owner") == owner)
                    put(record.getString("key"), decode(record))
                }
            }
        }
    }

    fun restoredFor(owner: Int, detail: AloConversationDetail, topicId: Int = 0): PersistedMessageDraft? =
        runCatching { drafts(owner)[conversationDraftKey(detail, topicId)] }.onFailure(onFailure).getOrNull()

    fun put(owner: Int, detail: AloConversationDetail, draft: PersistedMessageDraft, topicId: Int = 0) {
        update(owner, conversationDraftKey(detail, topicId), draft)
    }

    fun persistFrom(state: AloNativeAppUiState) {
        val owner = state.bootstrap?.me?.id?.takeIf { it > 0 } ?: return
        val detail = state.activeConversation ?: return
        if (state.messageEditingId != null || state.messageCaptureState == AloChatCaptureState.Recording) return
        val draft = PersistedMessageDraft(state.messageComposerText, state.messageRecorderMode,
            state.messageDraftAttachment, state.messageDraftAttachments, state.messageForwardDraft,
            state.messageReplyTo, state.messageSelfDestructSeconds)
        update(owner, conversationDraftKey(detail, state.activeForumTopicId), draft.takeUnless {
            it.text.isBlank() && it.attachment == null && it.attachments.isEmpty() && it.forwardDraft.isEmpty() && it.replyTo == null
        })
    }

    fun clearActive(state: AloNativeAppUiState) {
        val owner = state.bootstrap?.me?.id?.takeIf { it > 0 } ?: return
        val detail = state.activeConversation ?: return
        update(owner, conversationDraftKey(detail, state.activeForumTopicId), null)
    }

    private fun update(owner: Int, key: String, draft: PersistedMessageDraft?) {
        runCatching {
            val map = drafts(owner)
            if (map[key] == draft) return
            if (draft == null) map.remove(key) else map[key] = draft
            if (!persistToDisk()) return
            val snapshot = map.toMap()
            // Ordered, off-main IO independent of Activity destruction.
            writer.launch {
                try {
                    val records = JSONArray().also { array -> snapshot.forEach { (k, value) -> array.put(encode(value).put("key", k).put("owner", owner)) } }
                    secure.saveDocument("drafts", records, owner)
                }
                catch (error: Exception) { withContext(Dispatchers.Main) { onFailure(error) } }
            }
        }.onFailure(onFailure)
    }

    private fun encode(draft: PersistedMessageDraft): JSONObject = JSONObject()
        .put("text", draft.text).put("recorderMode", draft.recorderMode.name)
        .put("attachment", draft.attachment?.let(::attachmentJson))
        .put("attachments", JSONArray(draft.attachments.map(::attachmentJson)))
        .put("forward", JSONArray(draft.forwardDraft.map(AloCloudMessages::forwardSourceJson)))
        .put("reply", draft.replyTo?.let(codec::encodeDraftReply)).put("selfDestructSeconds", draft.selfDestructSeconds)

    private fun attachmentJson(value: AloChatDraftAttachment): JSONObject = JSONObject()
        .put("localUri", value.localUri).put("previewUri", value.previewUri)
        .put("attachmentType", value.attachmentType).put("mimeType", value.mimeType).put("name", value.name)
        .put("durationSec", value.durationSec).put("isVideoCircle", value.isVideoCircle)
        .put("encryptedSourcePath", value.encryptedSourcePath).put("mediaEncryption", JSONObject(value.mediaEncryption))
        .put("encryptedChecksum", value.encryptedChecksum).put("width", value.width).put("height", value.height)

    private fun restoreAttachment(record: JSONObject): AloChatDraftAttachment? {
        if (record.optString("encryptedSourcePath").isNotEmpty()) return attachments.restoreEncryptedDraft(record)
        return AloChatDraftAttachment(localUri = record.optString("localUri"), previewUri = record.optString("previewUri"),
            attachmentType = record.optString("attachmentType"), mimeType = record.optString("mimeType"), name = record.optString("name"),
            durationSec = record.optInt("durationSec"), isVideoCircle = record.optBoolean("isVideoCircle"))
    }

    private fun decode(record: JSONObject): PersistedMessageDraft {
        val array = record.optJSONArray("attachments") ?: JSONArray()
        val forward = record.optJSONArray("forward") ?: JSONArray()
        return PersistedMessageDraft(text = record.optString("text"),
            recorderMode = AloChatRecordMode.entries.firstOrNull { it.name == record.optString("recorderMode") } ?: AloChatRecordMode.Voice,
            attachment = record.optJSONObject("attachment")?.let(::restoreAttachment),
            attachments = (0 until array.length()).mapNotNull { restoreAttachment(array.getJSONObject(it)) },
            forwardDraft = (0 until forward.length()).map { AloCloudMessages.forwardSourceFrom(forward.getJSONObject(it)) },
            replyTo = record.optJSONObject("reply")?.let(codec::decodeDraftReply), selfDestructSeconds = record.optInt("selfDestructSeconds"))
    }

    private companion object {
        // Keep ordering and the newest draft across Activity recreation too.
        val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val MEMORY = mutableMapOf<String, MutableMap<Int, MutableMap<String, PersistedMessageDraft>>>()
    }
}
