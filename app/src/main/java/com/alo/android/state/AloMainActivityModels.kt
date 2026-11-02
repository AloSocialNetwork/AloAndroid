package com.alo.android

internal sealed interface RootScreen {
    data object Loading : RootScreen
    data object Auth : RootScreen
    data object App : RootScreen
}

internal enum class PickerTarget {
    MessageAttachment,
    ProfileAvatar,
    GroupAvatar,
    SecretAttachment
}

internal data class PersistedMessageDraft(
    val text: String,
    val recorderMode: AloChatRecordMode,
    val attachment: AloChatDraftAttachment?,
    val attachments: List<AloChatDraftAttachment> = emptyList(),
    val forwardDraft: List<AloMessageForwardSource> = emptyList(),
    val replyTo: AloChatMessage? = null,
    val selfDestructSeconds: Int = 0
)
