package com.alo.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AloGroupChatCoordinator(
    private val apiClient: AloApiClient,
    private val scope: CoroutineScope,
    private val getAppState: () -> AloNativeAppUiState,
    private val setAppState: (AloNativeAppUiState) -> Unit,
    private val updateBootstrap: ((AloBootstrapData) -> AloBootstrapData) -> Unit,
    private val handleSessionExpired: () -> Unit
) {
    private var appState: AloNativeAppUiState
        get() = getAppState()
        set(value) = setAppState(value)

    fun updateProfile(roomId: Int, title: String) {
        val activeConversation = appState.activeConversation ?: return
        if (roomId <= 0 || title.trim().length < 2) return
        if (activeConversation.roomId != roomId) return

        scope.launch {
            try {
                apiClient.updateGroupChatProfile(roomId = roomId, title = title)
                reloadActiveConversationAfterGroupChange("Настройки группы сохранены.")
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось сохранить настройки группы.")
            }
        }
    }

    fun addMember(roomId: Int, username: String) {
        if (roomId <= 0 || username.trim().isBlank()) return
        scope.launch {
            try {
                apiClient.addGroupChatMember(roomId, username)
                reloadActiveConversationAfterGroupChange("Участник добавлен.")
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось добавить участника.")
            }
        }
    }

    fun removeMember(roomId: Int, userId: Int) {
        if (roomId <= 0 || userId <= 0) return
        scope.launch {
            try {
                apiClient.removeGroupChatMember(roomId, userId)
                reloadActiveConversationAfterGroupChange("Участник удален.")
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось удалить участника.")
            }
        }
    }

    fun updateMemberRole(roomId: Int, userId: Int, role: String) {
        if (roomId <= 0 || userId <= 0) return
        scope.launch {
            try {
                apiClient.updateGroupChatMemberRole(roomId, userId, role)
                reloadActiveConversationAfterGroupChange("Роль участника обновлена.")
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить роль.")
            }
        }
    }

    fun loadManagement(roomId: Int) {
        if (roomId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true)
        scope.launch {
            try {
                refreshGroupManagement(roomId)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isGroupManagementLoading = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось загрузить управление группой."
                )
            }
        }
    }

    fun updateSettings(roomId: Int, policy: AloGroupPolicy) {
        if (roomId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true)
        scope.launch {
            try {
                apiClient.updateGroupSettings(roomId, policy)
                refreshGroupManagement(roomId)
            } catch (error: AloApiException) {
                handleManagementError(error, "Не удалось сохранить настройки группы.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось сохранить настройки группы."
                )
            }
        }
    }

    fun reviewJoinRequest(roomId: Int, userId: Int, approve: Boolean) {
        if (roomId <= 0 || userId <= 0) return
        scope.launch {
            try {
                apiClient.reviewGroupJoinRequest(roomId, userId, approve)
                refreshGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange(
                    if (approve) "Заявка одобрена." else "Заявка отклонена."
                )
            } catch (error: AloApiException) {
                handleManagementError(error, "Не удалось обработать заявку.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обработать заявку.")
            }
        }
    }

    fun updateMemberAccess(
        roomId: Int,
        userId: Int,
        role: String,
        adminRights: Map<String, Boolean>,
        restrictions: Map<String, Boolean>,
        changeRole: Boolean
    ) {
        if (roomId <= 0 || userId <= 0) return
        scope.launch {
            try {
                apiClient.updateGroupMemberAccess(
                    roomId = roomId,
                    userId = userId,
                    role = role,
                    adminTitle = "",
                    isAnonymous = false,
                    adminRights = adminRights,
                    restrictions = restrictions,
                    changeRole = changeRole
                )
                refreshGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Права участника обновлены.")
            } catch (error: AloApiException) {
                handleManagementError(error, "Не удалось обновить права участника.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить права участника.")
            }
        }
    }

    private suspend fun refreshGroupManagement(roomId: Int) {
        val snapshot = apiClient.loadGroupManagement(roomId)
        appState = appState.copy(
            groupManagement = snapshot,
            isGroupManagementLoading = false,
            errorMessage = null
        )
    }

    private fun handleManagementError(error: AloApiException, fallback: String) {
        if (error.status == 401) {
            handleSessionExpired()
        } else {
            appState = appState.copy(
                isGroupManagementLoading = false,
                errorMessage = error.message.ifBlank { fallback }
            )
        }
    }

    private suspend fun reloadActiveConversationAfterGroupChange(message: String) {
        val activeConversation = appState.activeConversation ?: return
        val refreshed = apiClient.loadConversation(
            kind = activeConversation.kind,
            peerId = activeConversation.peerId,
            roomId = activeConversation.roomId
        )
        appState = appState.copy(
            activeConversation = refreshed,
            errorMessage = message
        )
        updateBootstrap { data ->
            data.copy(
                messagesConversations = data.messagesConversations.map { conversation ->
                    if (conversation.roomId == refreshed.roomId || conversation.peerId == refreshed.peerId) {
                        conversation.copy(
                            memberCount = refreshed.memberCount,
                            peer = conversation.peer.copy(name = refreshed.title)
                        )
                    } else {
                        conversation
                    }
                }
            )
        }
    }
}
