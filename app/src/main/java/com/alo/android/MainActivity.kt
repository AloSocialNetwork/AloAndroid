package com.alo.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.webkit.PermissionRequest
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alo.android.ui.theme.AloAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private data class PendingChatTarget(
        val peerId: Int,
        val roomId: Int,
        val kind: String
    )

    private class UpdatesCursorGapException : IOException()

    private sealed interface PendingChatOperation {
        val target: PendingChatTarget

        data class SendText(
            val localId: Int,
            override val target: PendingChatTarget,
            val body: String,
            val createdAt: String,
            val replyToMessageId: Int = 0,
            val topicId: Int = 0,
            val clientMessageId: String,
            val encryptedPayload: String = "",
            val pollDefinition: AloPollDefinition? = null,
            val silent: Boolean = false,
            val scheduledAt: String = "",
            val sendWhenOnline: Boolean = false,
            /** A vote in a poll the server counts (created in a cloud message): `PUT /api/polls/:id/vote`, no message. */
            val pollOnServer: Boolean = false
        ) : PendingChatOperation

        data class SendDraft(
            val localId: Int,
            override val target: PendingChatTarget,
            val body: String,
            val createdAt: String,
            val attachments: List<AloChatDraftAttachment>,
            val uploadedAttachments: List<AloOutgoingMessageAttachment> = emptyList(),
            val selfDestructSeconds: Int = 0,
            val replyToMessageId: Int = 0,
            val topicId: Int = 0,
            val clientMessageId: String,
            val encryptedPayload: String = "",
            val uploadCheckpoint: AloResumableUploadCheckpoint? = null,
            val silent: Boolean = false,
            val scheduledAt: String = "",
            val sendWhenOnline: Boolean = false
        ) : PendingChatOperation

        data class Reaction(
            override val target: PendingChatTarget,
            val messageId: Int,
            val emoji: String,
            val operationId: String
        ) : PendingChatOperation

        data class Edit(
            override val target: PendingChatTarget,
            val messageId: Int,
            val originalBody: String,
            val body: String,
            val attachments: List<AloOutgoingMessageAttachment>,
            val forwardFrom: AloMessageForwardSource?,
            val operationId: String,
            val encryptedPayload: String = "",
            /** The message is stored as an E2EE envelope on the server, so its edit has to be encrypted too. */
            val sourceEncrypted: Boolean = false
        ) : PendingChatOperation

        data class Delete(
            override val target: PendingChatTarget,
            val messageIds: List<Int>,
            val forEveryone: Boolean,
            val operationId: String
        ) : PendingChatOperation

        data class Pin(
            override val target: PendingChatTarget,
            val messageId: Int,
            val pinned: Boolean,
            val operationId: String
        ) : PendingChatOperation

        data class Reschedule(
            override val target: PendingChatTarget,
            val messageId: Int,
            val scheduledMessageId: Int,
            val originalScheduledAt: String,
            val scheduledAt: String,
            val silent: Boolean,
            val operationId: String
        ) : PendingChatOperation

        data class CancelScheduled(
            override val target: PendingChatTarget,
            val messageId: Int,
            val scheduledMessageId: Int,
            val operationId: String
        ) : PendingChatOperation
    }

    private val apiClient by lazy {
        AloApiClient(
            baseUrl = AloServerConfiguration.apiBaseUrl,
            applicationContext = applicationContext,
            e2eeCrypto = AloE2eeCrypto(appPrefs),
            e2eeTrustStore = AloE2eeTrustStore(appPrefs),
            e2eeMediaCacheDir = File(cacheDir, "alo-e2ee-media"),
            libSignalPreferences = appPrefs
        )
    }
    private val secretChats by lazy {
        AloSecretChatCoordinator(
            apiClient = apiClient,
            scope = lifecycleScope,
            draftStore = draftAttachmentStore,
            getState = { appState },
            setState = { appState = it },
            pickAttachment = ::pickSecretAttachment,
            handleSessionExpired = ::handleSessionExpired
        )
    }
    private var rootScreen by mutableStateOf<RootScreen>(RootScreen.Loading)
    private var authState by mutableStateOf(AuthUiState())
    @Volatile private var appStateValue = AloNativeAppUiState()
    /**
     * What the screens draw: the same object as before while only typed text or recording levels change. Those reach the
     * composers through [liveInput], so a typed letter does not recompose the whole app.
     */
    private var appStateForUi by mutableStateOf(appStateValue)
    private val liveInput = AloLiveInput()
    private var appState: AloNativeAppUiState
        get() = appStateValue
        set(value) {
            val old = appStateValue
            val sameTarget = old.bootstrap?.me?.id == value.bootstrap?.me?.id &&
                old.activeConversationPeerId == value.activeConversationPeerId && old.activeForumTopicId == value.activeForumTopicId
            // Persist edits, not navigation placeholders. Destinations restore
            // their own draft explicitly; never save the previous chat under a new key.
            val compositionChanged = old.messageComposerText != value.messageComposerText ||
                old.messageDraftAttachment != value.messageDraftAttachment || old.messageDraftAttachments != value.messageDraftAttachments ||
                old.messageForwardDraft != value.messageForwardDraft || old.messageReplyTo != value.messageReplyTo ||
                old.messageSelfDestructSeconds != value.messageSelfDestructSeconds || old.messageRecorderMode != value.messageRecorderMode
            if (old.bootstrap != null && sameTarget && compositionChanged) {
                messageDraftStore.persistFrom(value)
            }
            val next = isolateMessageDraftOwner(old, value)
            appStateValue = next
            liveInput.update(next)
            val drawn = appStateForUi
            if (next.withLiveInputOf(drawn) != drawn) appStateForUi = next
        }
    private var authHumanChallenge by mutableStateOf<AloHumanChallengeUiState?>(null)
    private val callCoordinator by lazy {
        AloCallCoordinator(
            activity = this,
            apiClient = apiClient,
            scope = lifecycleScope,
            getRootScreen = { rootScreen },
            getAppState = { appState },
            setAppState = { appState = it },
            isUserBlocked = { userId ->
                AloUserBlockPolicy.isBlockedUser(userId, appState.blockedUserIds)
            },
            requestAppPermissions = ::requestAppPermissions,
            requestScreenCapturePermission = ::requestScreenCapturePermission,
            handleSessionExpired = ::handleSessionExpired
        ).apply {
            onGroupCallRecording = { file, seconds, title -> saveCallRecording(file, seconds, title) }
        }
    }

    private var resendCountdownJob: Job? = null
    private var sessionRetryJob: Job? = null
    private var messageRecordingTickerJob: Job? = null
    private var secretRecordingTickerJob: Job? = null
    private var chatRealtimeJob: Job? = null
    private var chatRealtimeRefreshJob: Job? = null
    private var updatesBootstrapRefreshJob: Job? = null
    private var updatesAcknowledgementJob: Job? = null
    private var pendingUpdatesAcknowledgementCursor: Long = 0
    private var chatRealtimeOwnerUserId: Int = 0
    private var chatRealtimeNeedsActiveRefresh: Boolean = false
    private var pendingChatRetryJob: Job? = null
    private var forumTopicDraftSyncJob: Job? = null
    private var qrLoginPollingJob: Job? = null
    private val conversationHistoryBackfillJobs = mutableMapOf<Int, Job>()
    private var globalConversationSearchIndexJob: Job? = null
    private val pendingChatOperations = mutableListOf<PendingChatOperation>()
    private var pendingChatOperationsOwnerUserId: Int? = null
    private var isProcessingPendingChatOperations = false
    /** Read by the uploader between pieces, so it is a thread-safe set rather than app state. */
    private val pausedUploadIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var nextLocalMessageId = -1
    private var lastTypingSignalAtMs: Long = 0L

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingAppPermissionsCallback: ((Boolean) -> Unit)? = null
    private var pendingAppPermissions: Array<String> = emptyArray()
    private var pendingScreenCaptureCallback: ((Intent?) -> Unit)? = null
    private var activeAudioRecorder: MediaRecorder? = null
    private var activeAudioRecordingFile: File? = null
    /** Every loudness sample of the voice message being recorded (the composer shows only the last 28). */
    private val voiceRecordingSamples = mutableListOf<Float>()
    private var pendingAccountExportPayload: AloAccountExportPayload? = null
    private val draftAttachmentStore by lazy { AloDraftAttachmentStore(this) }
    private val appPrefs by lazy { getSharedPreferences("alo-android", MODE_PRIVATE) }
    private val appUpdater by lazy { AloAppUpdater(this, lifecycleScope) }
    private val secureAccountStore by lazy { AloSecureAccountStore(this) }
    private val passkeyClient by lazy { AloPasskeyClient(this) }
    private val offlineCacheStore by lazy { AloOfflineCacheStore(this, appPrefs) }
    private val secureOutboxStore by lazy { AloSecureOutboxStore(this) }
    private val messageDraftStore by lazy {
        AloMessageDraftStore(this, offlineCacheStore, persistToDisk = { designPreview == null }) {
            android.widget.Toast.makeText(this, "Не удалось сохранить черновик на устройстве.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private val chatAppearanceStore by lazy { AloChatAppearanceStore(appPrefs) }
    private val publicationQueue by lazy {
        AloPublicationQueue(this, apiClient, lifecycleScope, ::currentCookieHeader,
            onStatus = { owner, status ->
                if (appState.bootstrap?.me?.id == owner) appState = appState.copy(publicationQueueStatus = status)
            },
            onPost = { owner, post, draftId ->
                if (appState.bootstrap?.me?.id == owner) {
                    appState = appState.copy(postDrafts = appState.postDrafts.filter { it.id != draftId })
                    updateBootstrap { data -> data.copy(
                    feedPopular = prependPost(data.feedPopular, post), feedSubscriptions = prependPost(data.feedSubscriptions, post),
                    profilePosts = prependPost(data.profilePosts, post)) }
                }
            },
            onStory = { owner, story ->
                val me = appState.bootstrap?.me
                if (me?.id == owner) {
                    val existing = appState.stories.firstOrNull { it.user.id == owner }
                    val group = AloStoryGroup(user = AloEntitySummary(me.id, me.name, me.username, me.avatarUrl, me.verified),
                        stories = listOf(story.copy(viewerHasSeen = true)) + existing?.stories.orEmpty().filter { it.id != story.id }, hasUnseen = false)
                    appState = appState.copy(stories = listOf(group) + appState.stories.filter { it.user.id != owner })
                }
            },
            onChallenge = { owner, error, retry ->
                appState.bootstrap?.me?.id == owner && handleHumanChallengeIfNeeded(error, retry)
            })
    }
    private var pickerTarget: PickerTarget = PickerTarget.MessageAttachment
    private var pendingGroupAvatarRoomId: Int = 0
    private var pendingHumanChallengeRetry: ((String) -> Unit)? = null
    private var isProcessingDeepLink = false
    private var isProcessingPushOpen = false
    /** A tapped push of another account saved on this phone: the app switches to it, then opens the push there. */
    private var pendingPushAccountId = 0
    private val pushOpenStore by lazy { AloPendingPushOpenStore(appPrefs) }
    /** Set in debug builds started with [AloDesignFixture.EXTRA]: the app runs on fixtures, offline. */
    private var designPreview: String? = null

    /** Translation of messages and transcription of voice messages and circles; a design preview answers from fixed sample text instead. */
    private val contentTools: AloContentTools by lazy {
        if (designPreview != null) {
            AloContentTools(this, lifecycleScope, AloContentToolsPreviewBackend(), readAudio = { _, _, _ -> AloAudioPayload(ByteArray(16) { 1 }, "audio/mp4") })
        } else {
            AloContentTools(this, lifecycleScope, AloContentToolsApiBackend(apiClient))
        }
    }
    // Pinned/archived chats and read marks, on this device per account (a separate file for fixtures).
    private lateinit var chatListPrefs: AloChatListPrefs
    private lateinit var unreadTracker: AloUnreadTracker
    private lateinit var chatListModel: AloChatListModel
    /** The server, or the offline fixture in design previews. */
    private lateinit var moderationService: AloModerationService
    private lateinit var deviceLinkService: AloDeviceLinkService
    private lateinit var channelStatsService: AloChannelStatsService

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileChooserCallback = null
        }

    private val appPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val callback = pendingAppPermissionsCallback
            pendingAppPermissionsCallback = null
            if (callback == null) return@registerForActivityResult
            val requestedPermissions = pendingAppPermissions
            pendingAppPermissions = emptyArray()
            val allGranted = requestedPermissions.all { permission ->
                grants[permission] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        permission
                    ) == PackageManager.PERMISSION_GRANTED
            }
            callback(allGranted)
            if (!allGranted && requestedPermissions.any { permission ->
                    ContextCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED &&
                        !shouldShowRequestPermissionRationale(permission)
                }) {
                showAppPermissionSettingsDialog(requestedPermissions)
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingScreenCaptureCallback
            pendingScreenCaptureCallback = null
            val permissionData = result.data?.takeIf { result.resultCode == Activity.RESULT_OK }
            if (permissionData == null || callback == null) {
                AloScreenShareService.stop(this)
            }
            callback?.invoke(permissionData)
        }

    private val messageAttachmentPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            when (pickerTarget) {
                PickerTarget.MessageAttachment -> preparePickedMessageAttachment(uri)
                PickerTarget.ProfileAvatar -> preparePickedProfileAvatar(uri)
                PickerTarget.GroupAvatar -> preparePickedGroupAvatar(uri)
                PickerTarget.SecretAttachment -> secretChats.attach(uri)
            }
            pendingGroupAvatarRoomId = 0
            pickerTarget = PickerTarget.MessageAttachment
        }

    private val accountExportDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
            val payload = pendingAccountExportPayload ?: return@registerForActivityResult
            pendingAccountExportPayload = null
            if (destination == null) {
                payload.file.delete()
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    securityNotice = "Экспорт не сохранён: выбор места был отменён."
                )
                return@registerForActivityResult
            }
            appState = appState.copy(isAccountSecurityLoading = true, securityNotice = "")
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(destination)
                            ?: throw IOException("Не удалось открыть выбранный файл для записи.")
                        output.use { stream ->
                            payload.file.inputStream().use { input -> input.copyTo(stream) }
                        }
                    }
                    appState = appState.copy(
                        accountExport = payload.export,
                        isAccountSecurityLoading = false,
                        securityNotice = "Экспорт сохранён в выбранный файл."
                    )
                } catch (error: Exception) {
                    appState = appState.copy(
                        isAccountSecurityLoading = false,
                        securityNotice = securityErrorMessage(error, "Не удалось сохранить экспорт данных.")
                    )
                } finally {
                    payload.file.delete()
                }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            val resources = supportedResources(request)
            val requiredPermissions = requiredAndroidPermissions(resources)
            val allGranted = requiredPermissions.all { permission ->
                grants[permission] == true ||
                    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                request.grant(resources)
            } else {
                request.deny()
            }

            pendingPermissionRequest = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AloGroupServiceLines.loader = { roomId -> apiClient.loadGroupServiceEvents(roomId) }
        // The chat wallpaper's line art, decoded in the background so the first chat opens without waiting for it.
        AloChatPatternCache.warm(this)

        AloCookies.acceptCookies()
        // Cross-client checks: the test person's session on the throwaway server (see AloDebugServer).
        if (BuildConfig.ALO_DEV_HOOKS && AloDebugServer.baseUrl.isNotBlank() && AloDebugServer.sessionToken.isNotBlank() &&
            !currentCookieHeader().contains("alo_session=${AloDebugServer.sessionToken}")
        ) {
            restoreCookieHeader("alo_session=${AloDebugServer.sessionToken}")
        }

        val previewContent = intent?.getStringExtra(AloDesignFixture.EXTRA)?.takeIf { BuildConfig.ALO_DEV_HOOKS }
        val storeSuffix = if (previewContent != null) ".preview" else ""
        chatListPrefs = AloChatListPrefs(this, "alo.chatlist.prefs.v1$storeSuffix")
        unreadTracker = AloUnreadTracker(this, "alo.unread.v1$storeSuffix")
        chatListModel = AloDeviceChatListModel(chatListPrefs, unreadTracker)
        moderationService = if (previewContent != null) AloDesignFixture.moderationService(AloDesignFixture.sampleImageUri(this)) else AloApiModerationService(apiClient)
        deviceLinkService = if (previewContent != null) AloDesignFixture.deviceLinkService() else AloApiDeviceLinkService(apiClient)
        channelStatsService = if (previewContent != null) AloDesignFixture.channelStatsService()
            else AloChannelStatsService { channelId, days -> apiClient.loadChannelStats(channelId, days) }

        setContent {
            val themedMode = appStateForUi.themeMode
            CompositionLocalProvider(
                LocalAloChatListModel provides chatListModel,
                LocalAloSecretChatEntry provides AloSecretChatEntry(
                    available = appStateForUi.secretChatsAvailable
                ) { userId -> handleAppAction(AloNativeAppAction.StartSecretChat(userId)) },
                LocalAloModeration provides moderationService,
                LocalAloStaffRights provides (appStateForUi.bootstrap?.me?.adminCan ?: emptySet()),
                LocalAloVerifiedChanged provides { _, id, verified ->
                    appState.viewedProfile?.takeIf { it.id == id }?.let { viewed ->
                        appState = appState.copy(viewedProfile = viewed.copy(verified = verified))
                    }
                },
                LocalAloDeviceLink provides deviceLinkService,
                LocalAloAppUpdater provides appUpdater,
                LocalAloLiveInput provides liveInput,
                LocalAloChannelStats provides channelStatsService,
                LocalAloGroupMemberBatch provides ::batchRemoveGroupMembers,
                LocalAloProfileStories provides AloProfileStoriesContext(appStateForUi, appStateForUi.bootstrap?.me?.id ?: 0, ::handleAppAction),
                LocalAloUploads provides AloUploadsState(appStateForUi.uploadProgress, appStateForUi.pausedUploadIds) {
                    handleAppAction(AloNativeAppAction.ToggleUploadPause(it))
                }
            ) {
            AloAndroidTheme(themeMode = themedMode) {
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    when (val screen = rootScreen) {
                        RootScreen.Loading -> LoadingScreen()
                        RootScreen.Auth -> AuthFlowScreen(
                            state = authState,
                            onAction = ::handleAuthAction,
                            onAppealAccountBlock = { text ->
                                apiClient.appealAccountBlock(authState.email, authState.password, text)
                            }
                        )
                        RootScreen.App -> AloNativeAppScreen(
                            state = appStateForUi,
                            onAction = ::handleAppAction,
                            contentTools = contentTools,
                            onLoadGroupInviteStats = apiClient::loadGroupInviteStats,
                            onBindDirectCallLocalRenderer = callCoordinator::bindDirectCallLocalRenderer,
                            onBindDirectCallRemoteRenderer = callCoordinator::bindDirectCallRemoteRenderer,
                            onUnbindDirectCallLocalRenderer = callCoordinator::unbindDirectCallLocalRenderer,
                            onUnbindDirectCallRemoteRenderer = callCoordinator::unbindDirectCallRemoteRenderer,
                            onBindGroupCallLocalRenderer = callCoordinator::bindGroupCallLocalRenderer,
                            onBindGroupCallRemoteRenderer = callCoordinator::bindGroupCallRemoteRenderer,
                            onUnbindGroupCallLocalRenderer = callCoordinator::unbindGroupCallLocalRenderer,
                            onUnbindGroupCallRemoteRenderer = callCoordinator::unbindGroupCallRemoteRenderer,
                            onBindGroupSfuRenderer = callCoordinator::bindGroupSfuRenderer,
                            onUnbindGroupSfuRenderer = callCoordinator::unbindGroupSfuRenderer
                        )
                    }
                    AloHumanChallengeSheet(
                        state = authHumanChallenge,
                        onSolved = { token -> submitHumanChallenge(token) },
                        onDismiss = ::closeHumanChallenge
                    )
                    // Never over a call.
                    if (appStateForUi.activeDirectCall == null && appStateForUi.activeGroupCall == null) {
                        AloAppUpdateDialog(appUpdater)
                    }
                }
            }
            }
        }

        appState = appState.copy(themeMode = readSavedThemeMode())
        if (previewContent != null) {
            installDesignPreview(previewContent)
            return
        }
        // Restore only the previously selected encrypted session. No password is
        // persisted and no account is silently merged into the WebView store.
        // The hardware key store fails on some phones now and then: the screen must still open (a tap on a push lands here).
        runCatching { secureAccountStore.installDeviceId() }
        runCatching { restoreActiveAccountCookiesIfNeeded() }
        runCatching { secureOutboxStore.discardLegacyUnscopedData() }
        captureDeepLink(intent)
        requestStartupPermissionsIfNeeded()
        checkSession()
    }

    override fun onStart() {
        super.onStart()
        appUpdater.onAppOpened()
        // Back from the background: the secret chats and requests that came meanwhile.
        if (appState.bootstrap != null) secretChats.refreshAll()
    }

    override fun onResume() {
        super.onResume()
        appUpdater.onAppResumed()
        AloGlassEffects.plain = AloDebugServer.plainGlass || getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
        // The chat on screen rings nothing (as in Telegram); the ringing notification of a call gives way to the app.
        AloMessageNotifications.foregroundChatKey = {
            appState.activeConversation?.takeIf { appState.activeConversationPeerId > 0 }?.let {
                AloMessageNotifications.chatKey(it.kind, it.peerId, it.roomId)
            }
        }
        AloMessageNotifications.cancelAllCalls(applicationContext)
        if (rootScreen == RootScreen.App) refreshAccountSummaries()
        appState.activeConversation?.takeIf { appState.activeConversationPeerId == it.peerId && chatPresenceJob?.isActive != true }
            ?.let(::startActiveConversationRefresh)
    }

    override fun onStop() {
        appUpdater.onAppHidden()
        offlineCacheStore.flushPendingWrites()
        super.onStop()
    }

    override fun onPause() {
        AloMessageNotifications.foregroundChatKey = null
        // In the background the chat is not being read: pushes for it come back.
        if (chatPresenceJob != null) {
            chatPresenceJob?.cancel()
            chatPresenceJob = null
            lifecycleScope.launch { runCatching { apiClient.updateChatPresence(0) } }
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLink(intent)
        processPendingDeepLinkIfReady()
    }

    override fun onDestroy() {
        resendCountdownJob?.cancel()
        sessionRetryJob?.cancel()
        qrLoginPollingJob?.cancel()
        messageRecordingTickerJob?.cancel()
        conversationHistoryBackfillJobs.values.forEach { it.cancel() }
        conversationHistoryBackfillJobs.clear()
        globalConversationSearchIndexJob?.cancel()
        globalConversationSearchIndexJob = null
        stopChatRealtime()
        callCoordinator.stop()
        stopAudioRecorder(cancel = true)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        pendingAppPermissionsCallback = null
        pendingScreenCaptureCallback?.invoke(null)
        pendingScreenCaptureCallback = null
        pendingAccountExportPayload?.file?.delete()
        pendingAccountExportPayload = null
        super.onDestroy()
    }

    private fun checkSession() {
        sessionRetryJob?.cancel()
        lifecycleScope.launch { checkSessionFromCache() }
    }

    private suspend fun checkSessionFromCache() {
        if (restoreCachedBootstrap()) {
            appState = appState.copy(isRefreshing = true, isOfflineCached = true)
            lifecycleScope.launch {
                try {
                    if (apiClient.hasSession()) {
                        loadNativeApp(isInitial = false)
                    } else {
                        showAuthFlow()
                    }
                } catch (_error: Exception) {
                    appState = appState.copy(
                        isRefreshing = false,
                        isOfflineCached = true,
                        errorMessage = null
                    )
                }
            }
            return
        }

        rootScreen = RootScreen.Loading

        lifecycleScope.launch {
            try {
                if (apiClient.hasSession()) {
                    openNativeApp()
                } else {
                    showAuthFlow()
                }
            } catch (_error: Exception) {
                if (!restoreCachedBootstrap()) {
                    appState = appState.copy(
                        bootstrap = null,
                        isRefreshing = false,
                        isOfflineCached = false,
                        errorMessage = "Не удалось загрузить данные. Проверь подключение и попробуй снова."
                    )
                    rootScreen = RootScreen.App
                }
            }
        }
    }

    /** The offline design fixture, as on iOS: no session, no account, nothing persisted. */
    private fun installDesignPreview(content: String) {
        designPreview = content
        if (content == "auth" || content == "auth-register" || content == "auth-challenge") {
            // The sign-in screen for design references; the saved session is neither read nor touched.
            authState = AuthUiState(step = if (content == "auth-register") AuthStep.RegisterName else AuthStep.LoginEmail)
            rootScreen = RootScreen.Auth
            // The server's Turnstile page as sign-in shows it; the token goes nowhere.
            if (content == "auth-challenge") requestHumanChallenge("") { }
            return
        }
        val imageUri = AloDesignFixture.sampleImageUri(this)
        val bootstrap = AloDesignFixture.bootstrap(apiClient, content, imageUri)
        val tab = when (content) {
            "notifications", "appeal" -> AloAppTab.Alerts
            "own-profile", "moderation", "moderation-settings" -> AloAppTab.Profile
            "search-results" -> AloAppTab.Search
            "chat", "chat-protected", "upload", "group-owner", "group-chat", "group-chat-long", "chat-tools", "chat-list", "formatting", "group-call", "group-call-member" -> AloAppTab.Messages
            else -> AloAppTab.Feed
        }
        if (content == "chat-list") AloDesignFixture.installChatList(chatListPrefs, unreadTracker)
        if (content == "group-call" || content == "group-call-member") {
            // Set after the app state below, so the call is not overwritten.
            lifecycleScope.launch { callCoordinator.installGroupCallPreview(AloDesignFixture.ME_ID, member = content == "group-call-member") }
        }
        val conversation = if (content == "chat" || content == "chat-protected" || content == "upload" || content == "group-owner" || content == "group-chat" || content == "group-chat-long" || content == "chat-tools" || content == "formatting") {
            buildConversationDetailFromBootstrap(bootstrap, bootstrap.messagesActivePeerId)
                ?.let { if (content == "chat-protected") it.copy(groupPolicy = AloGroupPolicy(protectedContent = true)) else it }
                ?.let { detail ->
                    if (content != "group-chat" && content != "group-chat-long" && content != "chat-tools") return@let detail
                    // A busy group where I am an ordinary member: several senders, an owner and an admin, reactions, a quote.
                    detail.copy(
                        kind = "group", roomId = AloDesignFixture.GROUP_ROOM_ID, title = "Семья", subtitle = "5 участников", memberCount = 5,
                        entityType = "group", memberRole = "member", canManage = false,
                        groupPolicy = AloGroupPolicy(
                            kind = "group", joinPolicy = "invite", historyVisible = true,
                            defaultPermissions = mapOf("sendMessages" to true, "sendMedia" to true)
                        ),
                        members = AloDesignFixture.groupChatMembers(),
                        items = when (content) {
                            "group-chat-long" -> AloDesignFixture.groupChatLongItems(imageUri)
                            "chat-tools" -> AloDesignFixture.chatToolsItems(AloDesignFixture.sampleVoiceUri(this), AloDesignFixture.sampleCircleUri(this))
                            else -> AloDesignFixture.groupChatItems(imageUri)
                        },
                        isTyping = content == "group-chat-long",
                        pinnedMessageIds = if (content == "chat-tools") emptyList() else listOf(AloDesignFixture.GROUP_PINNED_MESSAGE_ID)
                    )
                }
                ?.let { detail ->
                    if (content != "group-owner") return@let detail
                    // iOS's «group-owner»: «Семья», which the signed-in fixture user created.
                    val people = listOf(
                        AloDesignFixture.ME_ID to "Тест интерфейса", 990010 to "Маша", 990011 to "Александр", 990012 to "Даша", 990013 to "Слава"
                    )
                    detail.copy(
                        kind = "group", roomId = 505, title = "Семья", subtitle = "5 участников", memberCount = 5,
                        entityType = "group", memberRole = "owner", canManage = true,
                        groupPolicy = AloGroupPolicy(
                            kind = "group", joinPolicy = "invite", historyVisible = true,
                            defaultPermissions = mapOf(
                                "sendMessages" to true, "sendMedia" to true, "addMembers" to false,
                                "pinMessages" to false, "changeInfo" to false
                            )
                        ),
                        members = people.map { (id, name) ->
                            AloGroupMember(
                                AloEntitySummary(id = id, name = name, username = "", avatarUrl = "", verified = false),
                                role = if (id == AloDesignFixture.ME_ID) "owner" else if (id == 990010) "admin" else "member"
                            )
                        }
                    )
                }
                ?.let { detail ->
                    if (content != "upload") return@let detail
                    // iOS's «upload» fixture: a large file on its way out, with the ring and pause.
                    val mine = detail.items.lastOrNull { it.senderId == AloDesignFixture.ME_ID } ?: return@let detail
                    val file = mine.copy(
                        id = UPLOAD_PREVIEW_MESSAGE_ID, body = "Лекции за семестр", entities = emptyList(),
                        attachmentType = "file", attachmentName = "Лекции-семестр.zip", attachmentUrl = "",
                        attachments = listOf(AloOutgoingMessageAttachment(url = "", type = "file", name = "Лекции-семестр.zip"))
                    )
                    detail.copy(items = listOfNotNull(detail.items.firstOrNull(), file))
                }
        } else null
        appState = appState.copy(
            bootstrap = bootstrap,
            activeTab = tab,
            stories = AloDesignFixture.stories(imageUri),
            feedAds = AloDesignFixture.feedAds,
            feedAdEvery = AloDesignFixture.FEED_AD_EVERY,
            activeConversationPeerId = conversation?.let { bootstrap.messagesActivePeerId } ?: 0,
            activeConversation = conversation,
            isProfileSettingsOpen = content.startsWith("moderation"),
            isModerationOpen = content == "moderation",
            viewedProfile = when (content) {
                "channel-profile" -> AloDesignFixture.channelProfile(bootstrap.feedPopular)
                "user-profile" -> AloDesignFixture.userProfile(bootstrap.feedPopular)
                else -> null
            },
            activeViewedProfileTarget = when (content) {
                "channel-profile" -> AloProfileTarget.Channel(990600)
                "user-profile" -> AloProfileTarget.User(990011)
                else -> null
            },
            // «search-results»: what the server found for a typed text (no server in a preview).
            searchQuery = if (content == "search-results") "байкал" else "",
            searchRemoteQuery = if (content == "search-results") "байкал" else "",
            searchRemoteUsers = if (content == "search-results") listOf(
                AloEntitySummary(id = 990020, name = "Байкал Тур", username = "baikal_tour", avatarUrl = "", verified = true)
            ) else emptyList(),
            searchRemoteChannels = if (content == "search-results") listOf(
                AloEntitySummary(id = 990021, name = "Клуб путешественников", username = "travel_club", avatarUrl = "", verified = false, isChannel = true)
            ) else emptyList(),
            searchRemotePosts = if (content == "search-results") bootstrap.feedPopular.take(3).mapIndexed { index, post ->
                post.copy(body = listOf(
                    "Поездка на Байкал летом: лёд ещё не сошёл, но вода уже прозрачная до самого дна.",
                    "Что взять с собой на Байкал в октябре? Составил список по своему опыту.",
                    "Байкал ночью: фото без обработки."
                )[index], media = emptyList())
            } else emptyList(),
            pendingChatMessageIds = if (content == "upload") setOf(UPLOAD_PREVIEW_MESSAGE_ID) else emptySet(),
            uploadProgress = if (content == "upload") {
                mapOf(UPLOAD_PREVIEW_MESSAGE_ID to AloUploadProgress(512_000_000, 1_480_000_000))
            } else emptyMap(),
            linkedStory = if (content == "story-link") AloDesignFixture.stories(imageUri).firstOrNull { it.user.id != AloDesignFixture.ME_ID } else null,
            expiredStoryLink = if (content == "story-expired") AloStoryLinkResult.Expired(
                AloEntitySummary(id = 990010, name = "Маша", username = "masha", avatarUrl = "", verified = false)
            ) else null,
            isRefreshing = false
        )
        rootScreen = RootScreen.App
    }

    private fun showAuthFlow(noticeMessage: String? = null) {
        resendCountdownJob?.cancel()
        qrLoginPollingJob?.cancel()
        stopChatRealtime()
        callCoordinator.stop()
        appState = AloNativeAppUiState(themeMode = appState.themeMode, savedAccounts = secureAccountStore.accounts())
        authState = AuthUiState(noticeMessage = noticeMessage, savedAccounts = secureAccountStore.accounts())
        rootScreen = RootScreen.Auth
    }

    private fun openNativeApp() {
        lifecycleScope.launch {
            sessionRetryJob?.cancel()
            resendCountdownJob?.cancel()
            qrLoginPollingJob?.cancel()
            appState = AloNativeAppUiState(
                themeMode = appState.themeMode,
                isRefreshing = true
            )
            rootScreen = RootScreen.Loading
            loadNativeApp(isInitial = true)
        }
    }

    private fun handleSessionExpired() {
        if (designPreview != null) return
        stopChatRealtime()
        globalConversationSearchIndexJob?.cancel()
        globalConversationSearchIndexJob = null
        lifecycleScope.launch {
            apiClient.clearCookies()
            secureAccountStore.removeAccount(
                appState.bootstrap?.me?.id?.takeIf { it > 0 }
                    ?: secureAccountStore.activeAccountId()
            )
            showAuthFlow("Сессия завершена. Войдите снова.")
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            stopActiveRecordingForNavigation()
            callCoordinator.stop()
            AloPushRegistration.unregister(this@MainActivity, apiClient)
            runCatching { apiClient.logout() }
            secureAccountStore.removeAccount(
                appState.bootstrap?.me?.id?.takeIf { it > 0 }
                    ?: secureAccountStore.activeAccountId()
            )
            apiClient.clearCookies()
            stopChatRealtime()
            globalConversationSearchIndexJob?.cancel()
            globalConversationSearchIndexJob = null
            clearPendingChatOutboxForLogout()
            val savedThemeMode = appState.themeMode
            messageDraftStore.clearActive(appState)
            appState = AloNativeAppUiState(themeMode = savedThemeMode)
            authState = AuthUiState(noticeMessage = "Вы вышли из аккаунта.", savedAccounts = secureAccountStore.accounts())
            rootScreen = RootScreen.Auth
            // As in Telegram: the next account saved on this phone opens instead of the sign-in screen.
            secureAccountStore.accounts().firstOrNull()?.let { switchSecurityAccount(it.userId) }
        }
    }

    private fun handleAuthAction(action: AuthAction) {
        when (action) {
            AuthAction.ChooseLogin -> {
                resendCountdownJob?.cancel()
                authState = AuthUiState(
                    step = AuthStep.LoginEmail,
                    email = authState.email,
                    savedAccounts = authState.savedAccounts
                )
            }
            AuthAction.ChooseRegister -> {
                resendCountdownJob?.cancel()
                authState = AuthUiState(
                    step = AuthStep.RegisterName,
                    email = authState.email,
                    savedAccounts = authState.savedAccounts
                )
            }
            AuthAction.ChoosePasskey -> authenticateWithPasskey()
            AuthAction.ChooseQrLogin -> startQrLogin()
            AuthAction.ScanDeviceLink -> requestAppPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                authState = if (granted) {
                    authState.copy(isDeviceLinkScannerOpen = true, errorMessage = null)
                } else {
                    authState.copy(errorMessage = "Для сканирования QR-кода разрешите Alo доступ к камере.")
                }
            }
            AuthAction.CloseDeviceLinkScanner -> authState = authState.copy(isDeviceLinkScannerOpen = false)
            is AuthAction.DeviceLinkScanned -> signInWithDeviceLink(action.rawPayload)
            is AuthAction.SwitchToStoredAccount -> switchSecurityAccount(action.userId)
            AuthAction.Back -> navigateBack()
            AuthAction.Submit -> submitCurrentStep()
            AuthAction.ResendCode -> resendCode()
            AuthAction.ForgotPassword -> requestPasswordReset()
            is AuthAction.UpdateNewPassword -> updateAuthState { copy(newPassword = action.value) }
            is AuthAction.UpdateName -> updateAuthState { copy(name = action.value) }
            is AuthAction.UpdateUsername -> updateAuthState { copy(username = action.value) }
            is AuthAction.UpdateEmail -> updateAuthState { copy(email = action.value) }
            is AuthAction.UpdatePassword -> updateAuthState { copy(password = action.value) }
            is AuthAction.UpdateCode -> updateAuthState { copy(code = action.value.filter(Char::isDigit).take(6)) }
        }
    }

    private fun handleAppAction(action: AloNativeAppAction) {
        when (action) {
            is AloNativeAppAction.StartSecretChat,
            is AloNativeAppAction.OpenSecretChat,
            AloNativeAppAction.CloseSecretChat,
            AloNativeAppAction.AcceptSecretChat,
            AloNativeAppAction.DeleteSecretChat,
            is AloNativeAppAction.SetSecretChatTimer,
            is AloNativeAppAction.SendSecretMessage,
            AloNativeAppAction.PickSecretAttachment,
            is AloNativeAppAction.RemoveSecretDraft -> {
                // Leaving, deleting or switching the secret chat throws away a recording that is still going.
                val leaving = action is AloNativeAppAction.StartSecretChat || action is AloNativeAppAction.OpenSecretChat ||
                    action == AloNativeAppAction.CloseSecretChat || action == AloNativeAppAction.DeleteSecretChat
                if (leaving) stopSecretRecording(cancel = true, send = false)
                secretChats.handle(action)
            }
            is AloNativeAppAction.StartSecretRecording -> startSecretRecording(action.mode)
            is AloNativeAppAction.StopSecretRecording -> stopSecretRecording(action.cancel, action.send)
            is AloNativeAppAction.CompleteSecretCircleRecording ->
                completeSecretCircleRecording(action.localUri, action.durationSec, action.send)
            AloNativeAppAction.OpenNotificationSettings -> appState = appState.copy(isProfileSettingsOpen = true, profileSettingsStartPage = "Notifications")
            AloNativeAppAction.OpenProfileSettings -> {
                appState = appState.copy(isProfileSettingsOpen = true, profileSettingsStartPage = "Root")
                loadSecuritySessions()
                loadSecurityAccounts()
                loadPasskeys()
                loadBusinessProfile()
            }
            AloNativeAppAction.CloseProfileSettings -> appState = appState.copy(isProfileSettingsOpen = false)
            AloNativeAppAction.OpenModeration -> appState = appState.copy(isModerationOpen = true)
            AloNativeAppAction.OpenCreatorStats -> appState = appState.copy(isCreatorStatsOpen = true)
            AloNativeAppAction.CloseCreatorStats -> appState = appState.copy(isCreatorStatsOpen = false)
            is AloNativeAppAction.ToggleUploadPause -> {
                val id = action.localId
                if (id in appState.pausedUploadIds) {
                    pausedUploadIds.remove(id)
                    appState = appState.copy(pausedUploadIds = appState.pausedUploadIds - id)
                    if (designPreview == null) processPendingChatOperations()
                } else if (id in appState.pendingChatMessageIds) {
                    pausedUploadIds.add(id)
                    appState = appState.copy(pausedUploadIds = appState.pausedUploadIds + id)
                }
            }
            AloNativeAppAction.CloseLinkedStory -> appState = appState.copy(linkedStory = null, expiredStoryLink = null)
            AloNativeAppAction.CloseModeration -> appState = appState.copy(isModerationOpen = false)
            is AloNativeAppAction.OpenViewedProfile -> openViewedProfile(action.target)
            AloNativeAppAction.CloseViewedProfile -> {
                appState = appState.copy(
                    activeViewedProfileTarget = null,
                    viewedProfile = null,
                    isViewedProfileLoading = false
                )
            }
            is AloNativeAppAction.OpenMedia -> {
                appState = appState.copy(
                    activeMedia = AloMediaViewerState(items = listOf(action.media), startIndex = 0)
                )
            }
            is AloNativeAppAction.OpenMediaGallery -> {
                val items = action.items.filter { it.url.isNotBlank() || it.posterUrl.isNotBlank() }
                if (items.isEmpty()) return
                appState = appState.copy(
                    activeMedia = AloMediaViewerState(
                        items = items,
                        startIndex = action.startIndex.coerceIn(0, items.lastIndex)
                    )
                )
            }
            is AloNativeAppAction.PrepareMessageMediaOpen -> prepareMessageMediaOpen(action.message, action.onReady)
            is AloNativeAppAction.OpenMessageAttachment -> openMessageAttachment(action.message)
            AloNativeAppAction.CloseMedia -> appState = appState.copy(activeMedia = null)
            is AloNativeAppAction.SubmitHumanChallenge -> submitHumanChallenge(action.token)
            AloNativeAppAction.CloseHumanChallenge -> closeHumanChallenge()
            AloNativeAppAction.OpenChatPeerProfile -> {
                appState = appState.copy(
                    isChatPeerProfileOpen = true,
                    activeMedia = null,
                    activeChatMuted = false
                )
                loadActiveChatMuteState()
            }
            AloNativeAppAction.CloseChatPeerProfile -> {
                appState = appState.copy(
                    isChatPeerProfileOpen = false,
                    activeMedia = null
                )
            }
            AloNativeAppAction.ToggleActiveChatMuted -> toggleActiveChatMuted()
            AloNativeAppAction.JoinPreviewedChannel -> {
                val conversation = appState.activeConversation
                if (conversation != null && !conversation.isMember && conversation.feedChannelId > 0) {
                    lifecycleScope.launch {
                        try {
                            apiClient.toggleChannelSubscription(conversation.feedChannelId)
                            openNativeConversation(appState.activeConversationPeerId)
                            loadNativeApp(isInitial = false)
                            Toast.makeText(this@MainActivity, "Вы подписались на канал.", Toast.LENGTH_SHORT).show()
                        } catch (error: AloApiException) {
                            if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
                        } catch (_error: Exception) {
                            appState = appState.copy(errorMessage = "Не удалось подписаться.")
                        }
                    }
                }
            }
            is AloNativeAppAction.OpenChannelChat -> {
                if (action.roomId > 0) {
                    messageDraftStore.persistFrom(appState)
                    stopActiveRecordingForNavigation()
                    appState = appState.copy(
                        activeTab = AloAppTab.Messages,
                        activeViewedProfileTarget = null,
                        viewedProfile = null,
                        isViewedProfileLoading = false,
                        activeMedia = null
                    )
                    openNativeConversation(1_000_000_000 + action.roomId, fallbackKind = "group", fallbackRoomId = action.roomId)
                }
            }
            is AloNativeAppAction.ToggleChannelSubscription -> {
                lifecycleScope.launch {
                    try {
                        val subscribed = apiClient.toggleChannelSubscription(action.channelId)
                        appState.viewedProfile?.takeIf { it.kind == "channel" && it.id == action.channelId }?.let { profile ->
                            appState = appState.copy(viewedProfile = profile.copy(following = subscribed))
                        }
                        loadNativeApp(isInitial = false)
                    } catch (error: AloApiException) {
                        if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
                    } catch (_error: Exception) {
                        appState = appState.copy(errorMessage = "Не удалось изменить подписку.")
                    }
                }
            }
            AloNativeAppAction.LoadActiveChatMuted -> loadActiveChatMuteState()
            is AloNativeAppAction.UpdateDirectChatAutoDelete -> updateDirectChatAutoDelete(action.peerId, action.seconds)
            is AloNativeAppAction.UpdateUserBlock -> updateUserBlock(action.userId, action.blocked)
            is AloNativeAppAction.OpenComments -> openPostComments(action.postId)
            is AloNativeAppAction.OpenChannelPostDiscussion -> {
                lifecycleScope.launch {
                    try {
                        openPostComments(apiClient.loadPost(action.postId))
                    } catch (error: AloApiException) {
                        appState = appState.copy(errorMessage = error.message)
                    } catch (_error: Exception) {
                        appState = appState.copy(errorMessage = "Не удалось открыть обсуждение публикации.")
                    }
                }
            }
            AloNativeAppAction.CloseComments -> {
                appState = appState.copy(
                    commentsPostId = 0,
                    commentsPost = null,
                    comments = emptyList(),
                    commentsComposerText = "",
                    commentsEditingCommentId = null,
                    isCommentsLoading = false,
                    isSubmittingComment = false
                )
            }
            AloNativeAppAction.Refresh -> {
                publicationQueue.retry()
                lifecycleScope.launch { loadNativeApp(isInitial = false) }
            }
            AloNativeAppAction.Logout -> logout()
            AloNativeAppAction.ClearOfflineCache -> clearOfflineCache()
            AloNativeAppAction.LoadSecuritySessions -> loadSecuritySessions()
            is AloNativeAppAction.RevokeSecuritySession -> revokeSecuritySession(action.sessionId)
            AloNativeAppAction.RevokeOtherSecuritySessions -> revokeOtherSecuritySessions()
            AloNativeAppAction.LoadSecurityAccounts -> loadSecurityAccounts()
            is AloNativeAppAction.SwitchSecurityAccount -> {
                appState = appState.copy(isAccountSwitcherOpen = false)
                switchSecurityAccount(action.userId)
            }
            AloNativeAppAction.LoadPasskeys -> loadPasskeys()
            AloNativeAppAction.RegisterPasskey -> registerPasskey()
            AloNativeAppAction.AuthenticateWithPasskey -> authenticateWithPasskey()
            AloNativeAppAction.OpenQrLoginScanner -> openQrLoginScanner()
            AloNativeAppAction.AddAnotherAccount -> {
                appState = appState.copy(isAccountSwitcherOpen = false)
                addAnotherAccount()
            }
            AloNativeAppAction.OpenAccountSwitcher -> {
                appState = appState.copy(isAccountSwitcherOpen = true, savedAccounts = secureAccountStore.accounts())
                refreshAccountSummaries()
            }
            AloNativeAppAction.CloseAccountSwitcher -> appState = appState.copy(isAccountSwitcherOpen = false)
            AloNativeAppAction.CloseQrLoginScanner -> appState = appState.copy(isQrScannerOpen = false)
            is AloNativeAppAction.ApproveQrLoginPayload -> approveQrLoginPayload(action.rawPayload)
            AloNativeAppAction.ConfirmQrLogin -> confirmQrLogin()
            AloNativeAppAction.CancelQrLogin -> appState = appState.copy(qrLoginConfirmation = null)
            is AloNativeAppAction.ExportAccountData -> exportAccountData(action.currentPassword)
            is AloNativeAppAction.DeleteAccount -> deleteCurrentAccount(action.currentPassword)
            is AloNativeAppAction.ChangePassword -> {
                changePassword(
                    currentPassword = action.currentPassword,
                    newPassword = action.newPassword
                )
            }
            AloNativeAppAction.SubmitPost -> submitNativePost()
            is AloNativeAppAction.PublishPost -> publishPost(action)
            is AloNativeAppAction.SavePostDraft -> savePostDraft(action)
            AloNativeAppAction.LoadPostDrafts -> {
                if (designPreview != null) {
                    if (appState.postDrafts.isEmpty()) appState = appState.copy(postDrafts = AloDesignFixture.postDrafts(AloDesignFixture.sampleImageUri(this)))
                } else lifecycleScope.launch {
                    runCatching { apiClient.loadPostDrafts() }.onSuccess { appState = appState.copy(postDrafts = it) }
                }
            }
            is AloNativeAppAction.DeletePostDraft -> {
                appState = appState.copy(postDrafts = appState.postDrafts.filter { it.id != action.draftId })
                if (designPreview == null) lifecycleScope.launch {
                    runCatching { apiClient.deletePostDraft(action.draftId) }
                        .onFailure { appState = appState.copy(notice = "Не удалось удалить черновик.") }
                }
            }
            AloNativeAppAction.RefreshStories -> refreshStories()
            is AloNativeAppAction.SubmitStory -> submitStory(action.body)
            is AloNativeAppAction.PublishStory -> publishStory(action)
            is AloNativeAppAction.MarkStoryViewed -> markStoryViewed(action.storyId)
            is AloNativeAppAction.LoadStoryViewers -> loadStoryViewers(action.storyId)
            is AloNativeAppAction.CopyStoryLink -> copyStoryLink(action.storyId, action.onResult)
            is AloNativeAppAction.DeleteStory -> deleteStory(action.storyId, action.onDone)
            is AloNativeAppAction.LoadHighlights -> loadHighlights(action.userId)
            is AloNativeAppAction.CreateHighlight -> {
                changeHighlights(action.onDone) {
                    apiClient.createHighlight(action.title, action.storyIds)
                }
            }
            is AloNativeAppAction.AddToHighlight -> {
                changeHighlights(action.onDone) {
                    apiClient.updateHighlight(action.highlightId, addStoryIds = action.storyIds)
                }
            }
            is AloNativeAppAction.RemoveFromHighlight -> {
                changeHighlights({}) {
                    apiClient.updateHighlight(action.highlightId, removeStoryIds = listOf(action.storyId))
                }
            }
            is AloNativeAppAction.LoadStoryArchive -> loadStoryArchive(action.more)
            AloNativeAppAction.LoadStoryAudience -> loadStoryAudience()
            is AloNativeAppAction.UpdateCloseFriends -> updateCloseFriends(action.userIds)
            is AloNativeAppAction.LoadCreatorStats -> loadCreatorStats(action.days)
            is AloNativeAppAction.RenameHighlight -> {
                changeHighlights({}) {
                    apiClient.updateHighlight(action.highlightId, title = action.title)
                }
            }
            is AloNativeAppAction.DeleteHighlight -> {
                val owner = appState.bootstrap?.me?.id
                if (owner != null) appState = appState.copy(
                    highlights = appState.highlights + (owner to appState.highlights[owner].orEmpty().filter { it.id != action.highlightId })
                )
                if (designPreview == null) lifecycleScope.launch {
                    runCatching { apiClient.deleteHighlight(action.highlightId) }
                        .onFailure { appState = appState.copy(notice = "Не удалось удалить актуальное.") }
                }
            }
            is AloNativeAppAction.UpdateStoryHiddenFrom -> {
                val chosen = appState.friends.filter { it.id in action.userIds }
                appState = appState.copy(storySettings = appState.storySettings.copy(hiddenFrom = chosen))
                if (designPreview == null) lifecycleScope.launch {
                    runCatching { apiClient.updateStoryHiddenFrom(action.userIds) }
                        .onSuccess { appState = appState.copy(storySettings = appState.storySettings.copy(hiddenFrom = it)) }
                        .onFailure { appState = appState.copy(notice = "Не удалось сохранить список.") }
                }
            }
            AloNativeAppAction.LoadBusinessProfile -> loadBusinessProfile()
            is AloNativeAppAction.SaveBusinessProfile -> {
                saveBusinessProfile(
                    enabled = action.enabled,
                    businessName = action.businessName,
                    category = action.category,
                    hours = action.hours,
                    greetingMessage = action.greetingMessage,
                    autoReplyEnabled = action.autoReplyEnabled,
                    autoReplyMessage = action.autoReplyMessage
                )
            }
            is AloNativeAppAction.AddBusinessQuickReply -> {
                addBusinessQuickReply(
                    shortcut = action.shortcut,
                    title = action.title,
                    body = action.body
                )
            }
            is AloNativeAppAction.DeleteBusinessQuickReply -> deleteBusinessQuickReply(action.quickReplyId)
            is AloNativeAppAction.SaveBusinessChatLabel -> {
                saveBusinessChatLabel(
                    chatKind = action.chatKind,
                    peerId = action.peerId,
                    roomId = action.roomId,
                    label = action.label,
                    color = action.color
                )
            }
            is AloNativeAppAction.DeleteBusinessChatLabel -> deleteBusinessChatLabel(action.chatLabelId)
            AloNativeAppAction.SubmitComment -> submitPostComment()
            is AloNativeAppAction.StartEditComment -> {
                appState = appState.copy(
                    commentsEditingCommentId = action.commentId,
                    commentsComposerText = action.body,
                    errorMessage = null
                )
            }
            is AloNativeAppAction.DeleteComment -> deletePostComment(action.commentId)
            AloNativeAppAction.SendMessage -> sendNativeMessage()
            AloNativeAppAction.SendMessageSilently -> sendCurrentMessage(silent = true)
            AloNativeAppAction.SendMessageWhenOnline -> sendCurrentMessage(sendWhenOnline = true)
            is AloNativeAppAction.ScheduleRepeatingMessage -> scheduleRepeatingCurrentMessage(action.repeat)
            is AloNativeAppAction.ScheduleMessage -> {
                if (action.scheduledAt.isBlank()) {
                    showScheduleMessagePicker()
                } else {
                    sendCurrentMessage(scheduledAt = action.scheduledAt)
                }
            }
            AloNativeAppAction.ToggleMessageRecorderMode -> toggleMessageRecorderMode()
            AloNativeAppAction.StartMessageRecording -> startMessageRecording()
            is AloNativeAppAction.StopMessageRecording -> {
                stopMessageRecording(action.cancel)
                if (action.send && !action.cancel && appState.messageDraftAttachment != null) sendMessageDraft()
            }
            is AloNativeAppAction.CompleteVideoCircleRecording -> completeVideoCircleRecording(action.localUri, action.durationSec, action.send)
            AloNativeAppAction.DiscardMessageDraft -> discardMessageDraft()
            is AloNativeAppAction.RemoveMessageDraftAttachment -> removeMessageDraftAttachment(action.localUri)
            is AloNativeAppAction.SendCatalogExpression -> sendCatalogExpression(action.media)
            is AloNativeAppAction.ShareContact -> shareContact(action.contact)
            is AloNativeAppAction.ShareStaticLocation -> {
                shareStaticLocation(
                    latitude = action.latitude,
                    longitude = action.longitude,
                    title = action.title,
                    address = action.address
                )
            }
            is AloNativeAppAction.ShareLiveLocation -> shareLiveLocation(action.request)
            AloNativeAppAction.SendMessageDraft -> sendMessageDraft()
            is AloNativeAppAction.SendChatSystemEvent -> {
                val conversation = appState.activeConversation
                if (
                    conversation != null &&
                    (AloPollCodec.decode(action.body) != null ||
                        AloChatSystemEventCodec.decode(action.body) != null)
                ) {
                    // The server counts the votes of a poll created in a cloud message: a vote goes to /api/polls, not into the chat.
                    val vote = AloPollCodec.decode(action.body) as? AloPollEvent.Vote
                    val serverTallied = vote != null && conversation.items.any { message ->
                        message.cloud &&
                            (AloPollCodec.decode(message.body) as? AloPollEvent.Create)?.definition?.id == vote.pollId
                    }
                    sendNativeMessageOptimistically(
                        body = action.body,
                        conversation = conversation,
                        preserveComposer = true,
                        pollDefinition = action.pollDefinition,
                        pollOnServer = serverTallied
                    )
                }
            }
            is AloNativeAppAction.CreatePoll -> {
                val conversation = appState.activeConversation
                if (conversation != null) {
                    val submitPoll: (String) -> Unit = submit@{ scheduledAt ->
                        var definition = action.definition.adjustCloseDeadlineForSchedule(scheduledAt)
                        if (definition.isQuiz) {
                            val prepared = AloPollQuizCommitment.prepare(definition)
                            val ownerUserId = appState.bootstrap?.me?.id ?: 0
                            if (prepared == null || ownerUserId <= 0) {
                                appState = appState.copy(errorMessage = "Не удалось подготовить защищённую викторину")
                                return@submit
                            }
                            try {
                                secureOutboxStore.saveQuizReveal(
                                    pollId = definition.id,
                                    correctOptionIds = definition.correctOptionIds,
                                    salt = prepared.salt,
                                    ownerUserId = ownerUserId
                                )
                                definition = prepared.definition
                            } catch (_: Exception) {
                                appState = appState.copy(errorMessage = "Не удалось безопасно сохранить ответ викторины")
                                return@submit
                            }
                        }
                        val body = AloPollCodec.encodeCreate(definition)
                        if (body != null) {
                            sendNativeMessageOptimistically(
                                body = body,
                                conversation = conversation,
                                preserveComposer = true,
                                pollDefinition = definition,
                                scheduledAt = scheduledAt
                            )
                        }
                    }
                    if (action.schedule) {
                        showScheduleMessagePicker(onSelected = submitPoll)
                    } else {
                        submitPoll("")
                    }
                }
            }
            is AloNativeAppAction.ClosePoll -> {
                val conversation = appState.activeConversation ?: return
                val target = pendingTarget(conversation)
                val pollDefinition = conversation.items.firstNotNullOfOrNull { message ->
                    (AloPollCodec.decode(message.body) as? AloPollEvent.Create)
                        ?.definition
                        ?.takeIf { it.id == action.pollId }
                }
                val ownerUserId = appState.bootstrap?.me?.id ?: 0
                val reveal = if (pollDefinition?.isQuiz == true) {
                    secureOutboxStore.loadQuizReveal(action.pollId, ownerUserId)
                } else {
                    null
                }
                if (pollDefinition?.isQuiz == true && reveal == null) {
                    appState = appState.copy(
                        errorMessage = "Закрыть викторину с раскрытием ответа можно только на устройстве автора"
                    )
                    return
                }
                lifecycleScope.launch {
                    try {
                        apiClient.closePoll(action.pollId)
                        if (reveal != null) {
                            AloPollCodec.encodeReveal(
                                pollId = action.pollId,
                                correctOptionIds = reveal.correctOptionIds,
                                salt = reveal.salt
                            )?.let { body ->
                                sendNativeMessageOptimistically(
                                    body = body,
                                    conversation = conversation,
                                    preserveComposer = true
                                )
                            }
                        }
                        refreshConversationIfCurrent(target)
                    } catch (error: AloApiException) {
                        appState = appState.copy(
                            errorMessage = error.message ?: "Не удалось завершить опрос"
                        )
                    } catch (_: Exception) {
                        appState = appState.copy(errorMessage = "Не удалось завершить опрос")
                    }
                }
            }
            AloNativeAppAction.DiscardMessageForwardDraft -> discardMessageForwardDraft()
            is AloNativeAppAction.StartReplyMessage -> startReplyingToMessage(action.message)
            AloNativeAppAction.CancelReplyMessage -> cancelReplyingToMessage()
            is AloNativeAppAction.RetargetMessageForwardDraft -> retargetMessageForwardDraft(action.target)
            AloNativeAppAction.PickMessageAttachment -> pickMessageAttachment()
            is AloNativeAppAction.PickMessageGalleryUri -> preparePickedMessageAttachment(Uri.parse(action.uri))
            is AloNativeAppAction.PickMessageGalleryUris -> preparePickedMessageAttachments(action.uris.map(Uri::parse))
            AloNativeAppAction.PickProfileAvatar -> pickProfileAvatar()
            is AloNativeAppAction.PickGroupAvatar -> pickGroupAvatar(action.roomId)
            is AloNativeAppAction.UpdateProfileCoverStyle -> updateProfileCoverStyle(action.style)
            is AloNativeAppAction.SetFavoriteReaction -> setFavoriteReaction(action.emoji)
            is AloNativeAppAction.DeleteMessage -> deleteChatMessage(action.messageId, action.forEveryone)
            is AloNativeAppAction.DeleteMessages -> deleteChatMessages(action.messageIds, action.forEveryone)
            is AloNativeAppAction.RescheduleMessage -> {
                val message = appState.activeConversation?.items?.firstOrNull { it.id == action.messageId }
                if (message != null && message.isScheduled && message.scheduledMessageId > 0) {
                    val initialTimestamp = runCatching { Instant.parse(message.scheduledAt).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis() + 3_600_000L)
                    showScheduleMessagePicker(initialTimestamp) { scheduledAt ->
                        rescheduleChatMessage(message, scheduledAt)
                    }
                }
            }
            is AloNativeAppAction.CancelScheduledMessage -> cancelScheduledChatMessage(action.messageId)
            is AloNativeAppAction.StartEditMessage -> startEditingChatMessage(action.messageId)
            AloNativeAppAction.CancelEditMessage -> cancelEditingChatMessage()
            is AloNativeAppAction.ToggleMessageReaction -> toggleChatMessageReaction(action.messageId, action.emoji)
            is AloNativeAppAction.ToggleMessagePin -> toggleChatMessagePin(action.messageId)
            is AloNativeAppAction.ForwardMessage -> forwardChatMessage(action.message, action.target)
            is AloNativeAppAction.ForwardMessages -> forwardChatMessages(action.messages, action.target)
            is AloNativeAppAction.CreateGroupChat -> createGroupChat(action)
            is AloNativeAppAction.UpdateGroupChatProfile -> updateGroupChatProfile(action.roomId, action.title)
            is AloNativeAppAction.AddGroupChatMember -> addGroupChatMember(action.roomId, action.username)
            is AloNativeAppAction.RemoveGroupChatMember -> removeGroupChatMember(action.roomId, action.userId)
            is AloNativeAppAction.LeaveGroupChat -> leaveGroupChat(action.roomId, delete = false)
            is AloNativeAppAction.DeleteGroupChat -> leaveGroupChat(action.roomId, delete = true)
            is AloNativeAppAction.UpdateGroupChatMemberRole -> updateGroupChatMemberRole(action.roomId, action.userId, action.role)
            is AloNativeAppAction.LoadGroupManagement -> loadGroupManagement(action.roomId)
            is AloNativeAppAction.LoadEarlierGroupAdminLog -> loadEarlierGroupAdminLog(action.roomId)
            is AloNativeAppAction.UpdateGroupSettings -> updateGroupSettings(action.roomId, action.policy)
            is AloNativeAppAction.CreateGroupTopic -> {
                createGroupTopic(
                    roomId = action.roomId,
                    title = action.title,
                    emoji = action.emoji
                )
            }
            is AloNativeAppAction.UpdateGroupTopic -> {
                updateGroupTopic(
                    roomId = action.roomId,
                    topicId = action.topicId,
                    title = action.title,
                    emoji = action.emoji,
                    isClosed = action.isClosed,
                    isHidden = action.isHidden
                )
            }
            is AloNativeAppAction.DeleteGroupTopic -> {
                deleteGroupTopic(
                    roomId = action.roomId,
                    topicId = action.topicId
                )
            }
            is AloNativeAppAction.MergeGroupTopic -> {
                mergeGroupTopic(
                    roomId = action.roomId,
                    sourceTopicId = action.sourceTopicId,
                    targetTopicId = action.targetTopicId
                )
            }
            is AloNativeAppAction.ReviewGroupJoinRequest -> {
                reviewGroupJoinRequest(
                    action.roomId,
                    action.userId,
                    action.approve
                )
            }
            is AloNativeAppAction.CreateGroupInviteLink -> createGroupInviteLink(action)
            is AloNativeAppAction.UpdateGroupInviteLink -> updateGroupInviteLink(action)
            is AloNativeAppAction.RevokeGroupInviteLink -> {
                revokeGroupInviteLink(
                    action.roomId,
                    action.inviteId
                )
            }
            is AloNativeAppAction.JoinGroupInvite -> joinGroupInvite(action.token)
            is AloNativeAppAction.UpdateGroupMemberAccess -> {
                updateGroupMemberAccess(
                    roomId = action.roomId,
                    userId = action.userId,
                    role = action.role,
                    adminTitle = action.adminTitle,
                    isAnonymous = action.isAnonymous,
                    adminRights = action.adminRights,
                    restrictions = action.restrictions,
                    changeRole = action.changeRole
                )
            }
            is AloNativeAppAction.TransferGroupOwnership -> {
                transferGroupOwnership(
                    action.roomId,
                    action.userId,
                    action.currentPassword
                )
            }
            is AloNativeAppAction.BanGroupMember -> {
                banGroupMember(
                    action.roomId,
                    action.userId,
                    action.reason,
                    action.expiresAt
                )
            }
            is AloNativeAppAction.UnbanGroupMember -> {
                unbanGroupMember(
                    action.roomId,
                    action.userId
                )
            }
            is AloNativeAppAction.SendFriendRequest -> sendFriendRequest(action.userId)
            is AloNativeAppAction.RespondFriendRequest -> respondFriendRequest(action.requestId, action.accept)
            is AloNativeAppAction.ToggleFollowUser -> changeRelation("Не удалось изменить подписку.") {
                when (apiClient.toggleFollowUser(action.userId)) {
                    "friends" -> "Вы подписались друг на друга — теперь вы друзья."
                    "following" -> "Вы подписаны."
                    else -> "Подписка отменена."
                }
            }
            is AloNativeAppAction.CancelFriendRequest -> changeRelation("Не удалось отменить заявку.") {
                apiClient.cancelFriendRequest(action.requestId)
                "Заявка отменена."
            }
            is AloNativeAppAction.RemoveFriend -> changeRelation("Не удалось удалить из друзей.") {
                apiClient.removeFriend(action.userId)
                "Удалено из друзей."
            }
            AloNativeAppAction.MarkAllNotificationsRead -> markAllNotificationsRead()
            is AloNativeAppAction.DeleteNotification -> deleteNotification(action.notificationId)
            is AloNativeAppAction.UpdateActiveChatAppearance -> {
                appState.activeConversation?.let { conversation ->
                    chatAppearanceStore.save(conversation, action.appearance)
                }
                appState = appState.copy(
                    activeChatAppearance = action.appearance,
                    errorMessage = null
                )
            }
            is AloNativeAppAction.SelectTab -> {
                messageDraftStore.persistFrom(appState)
                stopActiveRecordingForNavigation()
                if (action.tab != AloAppTab.Messages) {
                    stopActiveConversationRefresh()
                } else {
                    appState.activeConversation?.let(::startActiveConversationRefresh)
                }
                appState = appState.copy(
                    activeTab = action.tab,
                    activeViewedProfileTarget = null,
                    viewedProfile = null,
                    isViewedProfileLoading = false,
                    isChatPeerProfileOpen = false,
                    isProfileSettingsOpen = false,
                    activeMedia = null,
                    commentsPostId = 0,
                    commentsPost = null,
                    comments = emptyList(),
                    commentsComposerText = "",
                    commentsEditingCommentId = null,
                    isCommentsLoading = false,
                    isSubmittingComment = false
                )
                if (action.tab == AloAppTab.Alerts) {
                    markAllNotificationsRead()
                }
            }
            is AloNativeAppAction.SelectFeedMode -> appState = appState.copy(feedMode = action.mode)
            is AloNativeAppAction.HideFeedPost -> hideFeedPost(action.postId, action.reason)
            is AloNativeAppAction.UndoHideFeedPost -> undoHideFeedPost(action.postId)
            is AloNativeAppAction.Report -> reportContent(action)
            AloNativeAppAction.LoadFeedAds -> loadFeedAds()
            is AloNativeAppAction.SetChatsPinned -> {
                val owner = appState.bootstrap?.me?.id ?: return
                val saved = chatListPrefs.setPinned(owner, action.keys, action.pinned)
                appState = appState.copy(
                    chatPrefsRevision = appState.chatPrefsRevision + 1,
                    notice = if (saved) appState.notice else "Можно закрепить не больше ${AloChatListPrefs.MAXIMUM_PINNED} чатов."
                )
                if (saved) uploadChatPins(owner)
            }
            is AloNativeAppAction.SetChatsArchived -> {
                val owner = appState.bootstrap?.me?.id ?: return
                chatListPrefs.setArchived(owner, action.keys, action.archived)
                appState = appState.copy(
                    chatPrefsRevision = appState.chatPrefsRevision + 1,
                    notice = if (action.archived) "Чат перенесён в архив." else "Чат возвращён из архива."
                )
            }
            is AloNativeAppAction.MarkChatsRead -> {
                val owner = appState.bootstrap?.me?.id ?: return
                chatsByKeys(action.keys).forEach { unreadTracker.markRead(owner, it) }
                appState = appState.copy(unreadRevision = appState.unreadRevision + 1)
            }
            is AloNativeAppAction.ToggleChatMuted -> toggleChatMuted(action.key)
            is AloNativeAppAction.DeleteChats -> deleteChats(action.keys)
            AloNativeAppAction.EstablishUnreadBaseline -> {
                val data = appState.bootstrap ?: return
                if (unreadTracker.establishBaseline(data.me.id, data.messagesConversations)) {
                    appState = appState.copy(unreadRevision = appState.unreadRevision + 1)
                }
            }
            is AloNativeAppAction.SetChatSelection -> appState = appState.copy(isSelectingChats = action.active)
            is AloNativeAppAction.LoadCallHistory -> loadCallHistory(action.more)
            is AloNativeAppAction.CallBack -> callBack(action.peerId, action.video)
            is AloNativeAppAction.RecordAdEvent -> {
                lifecycleScope.launch {
                    runCatching { apiClient.recordAdEvent(action.adId, action.kind, action.placement) }
                }
            }
            is AloNativeAppAction.HideAd -> {
                appState = appState.copy(
                    hiddenAdIds = appState.hiddenAdIds + action.adId,
                    notice = if (action.report) "Спасибо! Мы проверим это объявление" else "Реклама скрыта"
                )
                lifecycleScope.launch { runCatching { apiClient.hideAd(action.adId, action.report, "feed") } }
            }
            AloNativeAppAction.DismissNotice -> appState = appState.copy(notice = null)
            is AloNativeAppAction.SetCommentReplyTo -> setCommentReplyTo(action.comment)
            is AloNativeAppAction.ToggleCommentLike -> toggleCommentLike(action.commentId)
            is AloNativeAppAction.LoadMentionSuggestions -> loadMentionSuggestions(action.query)
            is AloNativeAppAction.UpdateComposer -> appState = appState.copy(composeText = action.value, errorMessage = null)
            is AloNativeAppAction.UpdateCommentComposer -> appState = appState.copy(commentsComposerText = action.value, errorMessage = null)
            is AloNativeAppAction.UpdateSearchQuery -> appState = appState.copy(searchQuery = action.value)
            is AloNativeAppAction.UpdateMessagesQuery -> appState = appState.copy(messagesQuery = action.value)
            is AloNativeAppAction.SelectForumTopic -> {
                val conversation = appState.activeConversation
                val topic = conversation?.forumTopics?.firstOrNull {
                    it.id == action.topicId && !it.isClosed && !it.isHidden
                }
                if (conversation != null && topic != null) {
                    val previousTopicId = appState.activeForumTopicId
                    val previousText = appState.messageComposerText
                    forumTopicDraftSyncJob?.cancel()
                    messageDraftStore.persistFrom(appState)
                    if (previousTopicId > 0) {
                        lifecycleScope.launch {
                            persistForumTopicDraft(conversation, previousTopicId, previousText)
                        }
                    }
                    val localDraft = messageDraftStore.restoredFor(appState.bootstrap?.me?.id ?: 0, conversation, topic.id)
                    val remoteDraft = conversation.forumTopicStates
                        .firstOrNull { it.topicId == topic.id }
                        ?.draftText
                        .orEmpty()
                    appState = appState.copy(
                        activeForumTopicId = topic.id,
                        messageComposerText = localDraft?.text ?: remoteDraft,
                        messageRecorderMode = localDraft?.recorderMode ?: AloChatRecordMode.Voice,
                        messageCaptureState = if (
                            localDraft?.attachment != null ||
                            !localDraft?.attachments.isNullOrEmpty()
                        ) {
                            AloChatCaptureState.Preview
                        } else {
                            AloChatCaptureState.Idle
                        },
                        messageDraftAttachment = localDraft?.attachment,
                        messageDraftAttachments = localDraft?.attachments.orEmpty(),
                        messageForwardDraft = localDraft?.forwardDraft.orEmpty(),
                        messageReplyTo = localDraft?.replyTo,
                        messageSelfDestructSeconds = localDraft?.selfDestructSeconds ?: 0,
                        errorMessage = null
                    )
                    markForumTopicRead(conversation, topic.id)
                }
            }
            is AloNativeAppAction.ToggleForumTopicMuted -> {
                val conversation = appState.activeConversation
                val currentState = conversation?.forumTopicStates?.firstOrNull {
                    it.topicId == action.topicId
                }
                if (
                    conversation != null &&
                    conversation.kind == "group" &&
                    conversation.roomId > 0 &&
                    currentState != null
                ) {
                    lifecycleScope.launch {
                        runCatching {
                            apiClient.updateGroupTopicState(
                                roomId = conversation.roomId,
                                topicId = action.topicId,
                                muted = !currentState.muted
                            )
                        }.getOrNull()?.let { state ->
                            applyForumTopicState(conversation.roomId, state)
                        }
                    }
                }
            }
            is AloNativeAppAction.SelectConversation -> {
                messageDraftStore.persistFrom(appState)
                stopActiveRecordingForNavigation()
                appState = appState.copy(activeMedia = null)
                markConversationRead(action.peerId)
                openNativeConversation(action.peerId)
            }
            is AloNativeAppAction.OpenDirectConversation -> {
                if (isBlockedUser(action.userId)) {
                    showBlockedPeerError()
                    return
                }
                messageDraftStore.persistFrom(appState)
                stopActiveRecordingForNavigation()
                appState = appState.copy(
                    activeTab = AloAppTab.Messages,
                    activeViewedProfileTarget = null,
                    viewedProfile = null,
                    isViewedProfileLoading = false,
                    activeMedia = null
                )
                openNativeConversation(action.userId, fallbackKind = "direct", fallbackRoomId = 0)
            }
            AloNativeAppAction.CloseConversation -> {
                val thread = appState.commentsThread
                val open = appState.activeConversation
                if (thread != null && open != null && open.roomId == thread.roomId) {
                    // Leaving a post's comments: mark them read (the dot under the post goes away) and go back to the channel.
                    val latest = open.items.filter { it.topicId == thread.topicId && it.id > 0 }.maxOfOrNull { it.id } ?: 0
                    if (latest > 0) lifecycleScope.launch {
                        runCatching { apiClient.updateGroupTopicState(thread.roomId, thread.topicId, readThroughMessageId = latest) }
                    }
                    val back = appState.commentsReturnPeerId
                    appState = appState.copy(commentsThread = null, commentsReturnPeerId = 0)
                    if (back > 0) {
                        messageDraftStore.persistFrom(appState)
                        stopActiveRecordingForNavigation()
                        appState = appState.copy(activeMedia = null)
                        openNativeConversation(back)
                        return
                    }
                } else if (thread != null) {
                    appState = appState.copy(commentsThread = null, commentsReturnPeerId = 0)
                }
                // Messages may have arrived while the chat was open: mark the newest summary read.
                markConversationRead(appState.activeConversationPeerId)
                messageDraftStore.persistFrom(appState)
                stopActiveRecordingForNavigation()
                stopActiveConversationRefresh()
                appState = appState.copy(
                    activeConversationPeerId = 0,
                    activeConversation = null,
                    activeChatAppearance = AloChatAppearance(),
                    activeViewedProfileTarget = null,
                    viewedProfile = null,
                    isViewedProfileLoading = false,
                    isChatPeerProfileOpen = false,
                    activeMedia = null,
                    isLoadingConversation = false,
                    isSendingMessage = false,
        	                    messageComposerText = "",
        	                    messageCaptureState = AloChatCaptureState.Idle,
        	                    messageRecordingDurationSec = 0,
        	                    messageRecordingLevels = emptyList(),
                    messageDraftAttachment = null,
                    messageDraftAttachments = emptyList(),
                    activeForumTopicId = 0,
                    messageForwardDraft = emptyList(),
                    messageEditingId = null,
                    messageReplyTo = null,
                    errorMessage = null
                )
            }
            is AloNativeAppAction.UpdateMessageComposer -> {
                appState = appState.copy(messageComposerText = action.value, errorMessage = null)
                sendTypingSignalIfNeeded(action.value)
                scheduleForumTopicDraftSync(action.value)
            }
            is AloNativeAppAction.UpdateMessageSelfDestructSeconds -> {
                val allowed = setOf(0, 1, 3, 5, 10, 30, 60, 300, 3600, 86400)
                appState = appState.copy(
                    messageSelfDestructSeconds = action.seconds.takeIf(allowed::contains) ?: 0,
                    errorMessage = null
                )
            }
            is AloNativeAppAction.ToggleLike -> toggleNativePostLike(action.postId)
            is AloNativeAppAction.ToggleRepost -> toggleNativePostRepost(action.postId)
            is AloNativeAppAction.DeletePost -> deleteNativePost(action.postId)
            is AloNativeAppAction.LoadMore -> loadMorePosts(action.list)
            is AloNativeAppAction.EditPost -> editNativePost(action.postId, action.body, action.onResult)
            is AloNativeAppAction.UpdateProfileInfo -> updateProfileInfo(action.name, action.username, action.bio, action.onResult)
            is AloNativeAppAction.SearchRemote -> searchRemote(action.query)
            is AloNativeAppAction.LoadMoreSearchPosts -> loadMoreSearchPosts()
            is AloNativeAppAction.UpdateNotificationSettings -> updateNotificationSettings(action.settings)
            is AloNativeAppAction.UpdatePrivacySettings -> updatePrivacySettings(action.settings)
            is AloNativeAppAction.UpdateChannelSettings -> updateChannelSettings(action)
            is AloNativeAppAction.UpdateThemeMode -> updateThemeMode(action.mode)
            AloNativeAppAction.StartVoiceCall -> {
                val detail = appState.activeConversation
                val isChannel = detail?.isChannelCommunity() == true
                if (isBlockedDirectConversation(detail)) {
                    showBlockedPeerError()
                } else if (!isChannel) {
                    if (detail?.kind == "group" || detail?.roomId ?: 0 > 0) {
                        callCoordinator.startGroupVoiceCall()
                    } else {
                        callCoordinator.startDirectVoiceCall()
                    }
                }
            }
            AloNativeAppAction.StartVideoCall -> {
                val detail = appState.activeConversation
                val isChannel = detail?.isChannelCommunity() == true
                val role = detail?.memberRole.orEmpty()
                val rights = detail?.groupPolicy?.memberAdminRights.orEmpty()
                val canStartChannelVideoChat = role.equals("owner", ignoreCase = true) || (
                    role.equals("admin", ignoreCase = true) &&
                        (rights.isEmpty() || rights["manageVideoChats"] == true)
                    )
                if (isBlockedDirectConversation(detail)) {
                    showBlockedPeerError()
                } else if (isChannel && !canStartChannelVideoChat) {
                    appState = appState.copy(
                        errorMessage = "Начать видеочат канала может только администратор с соответствующим правом."
                    )
                } else if (detail?.kind == "group" || detail?.roomId ?: 0 > 0) {
                    callCoordinator.startGroupVideoCall()
                } else {
                    callCoordinator.startDirectVideoCall()
                }
            }
            is AloNativeAppAction.SetGroupCallMicrophone -> callCoordinator.setGroupCallMicrophone(action.userId, action.allowed)
            is AloNativeAppAction.MuteGroupCallEveryone -> callCoordinator.muteGroupCallEveryone(action.muted)
            AloNativeAppAction.ToggleGroupCallRecording -> callCoordinator.toggleGroupCallRecording()
            AloNativeAppAction.AcceptDirectCall -> callCoordinator.acceptDirectCall()
            AloNativeAppAction.DeclineDirectCall -> callCoordinator.declineDirectCall()
            AloNativeAppAction.EndDirectCall -> callCoordinator.endDirectCall()
            AloNativeAppAction.MinimizeDirectCall -> {
                appState.activeDirectCall?.let { call ->
                    appState = appState.copy(activeDirectCall = call.copy(isMinimized = true))
                }
            }
            AloNativeAppAction.ExpandDirectCall -> {
                appState.activeDirectCall?.let { call ->
                    appState = appState.copy(activeDirectCall = call.copy(isMinimized = false))
                }
            }
            AloNativeAppAction.ToggleDirectCallMute -> callCoordinator.toggleDirectCallMute()
            AloNativeAppAction.ToggleDirectCallSpeaker -> callCoordinator.toggleDirectCallSpeaker()
            AloNativeAppAction.ToggleDirectCallVideo -> callCoordinator.toggleDirectCallVideo()
            AloNativeAppAction.ToggleDirectCallScreenShare -> callCoordinator.toggleDirectCallScreenShare()
            AloNativeAppAction.FlipDirectCallCamera -> callCoordinator.flipDirectCallCamera()
            is AloNativeAppAction.SelectDirectCallCamera -> callCoordinator.startDirectCallCamera(action.frontCamera)
            AloNativeAppAction.MinimizeGroupCall -> {
                appState.activeGroupCall?.let { call ->
                    appState = appState.copy(activeGroupCall = call.copy(isMinimized = true))
                }
            }
            AloNativeAppAction.ExpandGroupCall -> {
                appState.activeGroupCall?.let { call ->
                    appState = appState.copy(activeGroupCall = call.copy(isMinimized = false))
                }
            }
            AloNativeAppAction.ToggleGroupCallMute -> callCoordinator.toggleGroupCallMute()
            AloNativeAppAction.ToggleGroupCallSpeaker -> callCoordinator.toggleGroupCallSpeaker()
            AloNativeAppAction.ToggleGroupCallVideo -> callCoordinator.toggleGroupCallVideo()
            AloNativeAppAction.FlipGroupCallCamera -> callCoordinator.flipGroupCallCamera()
            is AloNativeAppAction.SelectGroupCallCamera -> callCoordinator.selectGroupCallCamera(action.frontCamera)
            AloNativeAppAction.LeaveGroupCall -> callCoordinator.leaveGroupCall()
        }
    }

    private fun navigateBack() {
        resendCountdownJob?.cancel()
        authState = when (authState.step) {
            AuthStep.LoginEmail, AuthStep.RegisterName -> authState
            AuthStep.LoginPassword -> authState.copy(
                step = AuthStep.LoginEmail,
                password = "",
                errorMessage = null,
                noticeMessage = null
            )
            AuthStep.LoginCode -> authState.copy(
                step = AuthStep.LoginPassword,
                code = "",
                challengeToken = "",
                verificationRequestId = "",
                errorMessage = null
            )
            AuthStep.RegisterUsername -> authState.copy(
                step = AuthStep.RegisterName,
                errorMessage = null,
                noticeMessage = null
            )
            AuthStep.RegisterEmail -> authState.copy(
                step = AuthStep.RegisterUsername,
                errorMessage = null,
                noticeMessage = null
            )
            AuthStep.RegisterPassword -> authState.copy(
                step = AuthStep.RegisterEmail,
                password = "",
                errorMessage = null,
                noticeMessage = null
            )
            AuthStep.VerifyEmailCode -> {
                if (authState.emailVerificationOrigin == EmailVerificationOrigin.ExistingAccount) {
                    authState.copy(
                        step = AuthStep.LoginPassword,
                        code = "",
                        errorMessage = null
                    )
                } else {
                    authState.copy(
                        step = AuthStep.RegisterPassword,
                        code = "",
                        errorMessage = null
                    )
                }
            }
            AuthStep.QrLogin, AuthStep.DeviceLinkWaiting -> {
                qrLoginPollingJob?.cancel()
                AuthUiState(savedAccounts = authState.savedAccounts)
            }
            AuthStep.ResetCode -> authState.copy(
                step = AuthStep.LoginPassword,
                code = "",
                newPassword = "",
                errorMessage = null,
                noticeMessage = null,
                resendCooldownSec = 0
            )
            AuthStep.ResetPassword -> authState.copy(
                step = AuthStep.ResetCode,
                newPassword = "",
                errorMessage = null
            )
        }
    }

    private fun submitCurrentStep() {
        when (authState.step) {
            AuthStep.LoginEmail -> {
                val email = normalizeEmail(authState.email)
                if (!isValidEmail(email)) {
                    showError("Введите корректную почту.")
                    return
                }

                authState = authState.copy(
                    step = AuthStep.LoginPassword,
                    email = email,
                    errorMessage = null,
                    noticeMessage = null
                )
            }
            AuthStep.LoginPassword -> {
                if (authState.password.length < 8) {
                    showError("Пароль должен быть не меньше 8 символов.")
                    return
                }
                submitLogin()
            }
            AuthStep.LoginCode -> {
                if (authState.code.length != 6) {
                    showError("Введите 6-значный код.")
                    return
                }
                verifyLogin()
            }
            AuthStep.RegisterName -> {
                val name = authState.name.trim()
                if (name.length < 2) {
                    showError("Введите имя минимум из 2 символов.")
                    return
                }
                authState = authState.copy(step = AuthStep.RegisterUsername, name = name, errorMessage = null, noticeMessage = null)
            }
            AuthStep.RegisterUsername -> {
                val name = authState.name.trim()
                val username = normalizeUsername(authState.username)
                if (!USERNAME_REGEX.matches(username)) {
                    showError("Username: 3-24 символа, только латиница, цифры и _.")
                    return
                }

                authState = authState.copy(
                    step = AuthStep.RegisterEmail,
                    name = name,
                    username = username,
                    errorMessage = null,
                    noticeMessage = null
                )
            }
            AuthStep.RegisterEmail -> {
                val email = normalizeEmail(authState.email)
                if (!isValidEmail(email)) {
                    showError("Введите корректную почту.")
                    return
                }

                authState = authState.copy(
                    step = AuthStep.RegisterPassword,
                    email = email,
                    errorMessage = null,
                    noticeMessage = null
                )
            }
            AuthStep.RegisterPassword -> {
                if (authState.password.length < 8) {
                    showError("Пароль должен быть не меньше 8 символов.")
                    return
                }
                submitRegistration()
            }
            AuthStep.VerifyEmailCode -> {
                if (authState.code.length != 6) {
                    showError("Введите 6-значный код.")
                    return
                }
                verifyEmail()
            }
            AuthStep.ResetCode -> {
                if (authState.code.length != 6) {
                    showError("Код должен состоять из 6 цифр.")
                    return
                }
                authState = authState.copy(step = AuthStep.ResetPassword, errorMessage = null, noticeMessage = null)
            }
            AuthStep.ResetPassword -> {
                if (authState.newPassword.length < 8) {
                    showError("Новый пароль должен быть не меньше 8 символов.")
                    return
                }
                submitPasswordReset()
            }
            AuthStep.QrLogin, AuthStep.DeviceLinkWaiting -> Unit
        }
    }

    /** «Забыли пароль?» on the password step: a code to the address from the step before (Cloudflare check first). */
    private fun requestPasswordReset(captchaToken: String? = null) {
        val email = normalizeEmail(authState.email)
        if (!isValidEmail(email)) {
            showError("Сначала введите почту.")
            return
        }
        resendCountdownJob?.cancel()
        authState = authState.copy(email = email, isSubmitting = true, errorMessage = null, noticeMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                val result = apiClient.forgotPassword(email = email, captchaToken = captchaToken)
                authState = authState.copy(
                    step = AuthStep.ResetCode,
                    code = "",
                    newPassword = "",
                    isSubmitting = false,
                    errorMessage = null,
                    noticeMessage = result.message
                )
                startResendCooldown(result.nextWaitSec)
            } catch (error: AloApiException) {
                if (handleHumanChallengeIfNeeded(error) { token -> requestPasswordReset(token) }) {
                    return@launch
                }
                val waitSec = error.payload.optInt("retryAfterSec", 0)
                authState = authState.copy(isSubmitting = false, errorMessage = error.message)
                if (waitSec > 0) startResendCooldown(waitSec)
            } catch (_error: Exception) {
                authState = authState.copy(isSubmitting = false, errorMessage = "Не удалось подключиться к серверу.")
            }
        }
    }

    private fun submitPasswordReset() {
        authState = authState.copy(isSubmitting = true, errorMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                val signedIn = apiClient.resetPassword(
                    email = authState.email,
                    code = authState.code,
                    password = authState.newPassword
                )
                if (signedIn) {
                    openNativeApp()
                } else {
                    // A blocked account gets its new password but no session until the block ends.
                    resendCountdownJob?.cancel()
                    authState = AuthUiState(
                        step = AuthStep.LoginEmail,
                        email = authState.email,
                        savedAccounts = authState.savedAccounts,
                        noticeMessage = "Пароль изменён. Войти можно будет, когда закончится блокировка аккаунта."
                    )
                }
            } catch (error: AloApiException) {
                // A wrong or stale code is fixed on the code step; a weak password stays here.
                val codeProblem = error.code == "INVALID_VERIFICATION_CODE" || error.code == "VERIFICATION_CODE_LOCKED"
                authState = authState.copy(
                    step = if (codeProblem) AuthStep.ResetCode else AuthStep.ResetPassword,
                    code = if (error.code == "VERIFICATION_CODE_LOCKED") "" else authState.code,
                    isSubmitting = false,
                    errorMessage = error.message
                )
            } catch (_error: Exception) {
                authState = authState.copy(isSubmitting = false, errorMessage = "Не удалось подключиться к серверу.")
            }
        }
    }

    private fun submitLogin(captchaToken: String? = null) {
        authState = authState.copy(isSubmitting = true, errorMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                val result = apiClient.login(
                    email = authState.email,
                    password = authState.password,
                    captchaToken = captchaToken
                )

                authState = authState.copy(
                    step = AuthStep.LoginCode,
                    challengeToken = result.challengeToken,
                    verificationRequestId = UUID.randomUUID().toString().replace("-", "").lowercase(),
                    code = "",
                    isSubmitting = false,
                    errorMessage = null,
                    noticeMessage = result.message,
                    resendCooldownSec = 0
                )
                startResendCooldown(0)
            } catch (error: AloApiException) {
                if (error.code == "EMAIL_NOT_VERIFIED") {
                    val waitSec = error.payload.optInt("retryAfterSec", 0)
                    authState = authState.copy(
                        step = AuthStep.VerifyEmailCode,
                        emailVerificationOrigin = EmailVerificationOrigin.ExistingAccount,
                        challengeToken = "",
                        code = "",
                        isSubmitting = false,
                        errorMessage = null,
                        noticeMessage = error.message,
                        resendCooldownSec = waitSec
                    )
                    startResendCooldown(waitSec)
                } else if (handleHumanChallengeIfNeeded(error) { token -> submitLogin(token) }) {
                    return@launch
                } else {
                    authState = authState.copy(
                        isSubmitting = false,
                        errorMessage = error.message,
                        isAccountBlockedByModeration = error.code == "ACCOUNT_BANNED" || error.code == "ACCOUNT_BLOCKED"
                    )
                }
            } catch (_error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = "Не удалось подключиться к серверу."
                )
            }
        }
    }

    private fun submitRegistration(captchaToken: String? = null) {
        authState = authState.copy(isSubmitting = true, errorMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                val result = apiClient.register(
                    name = authState.name,
                    username = authState.username,
                    email = authState.email,
                    password = authState.password,
                    captchaToken = captchaToken
                )

                authState = authState.copy(
                    step = AuthStep.VerifyEmailCode,
                    emailVerificationOrigin = EmailVerificationOrigin.Registration,
                    code = "",
                    isSubmitting = false,
                    errorMessage = null,
                    noticeMessage = result.message,
                    resendCooldownSec = 0
                )
                startResendCooldown(0)
            } catch (error: AloApiException) {
                if (handleHumanChallengeIfNeeded(error) { token -> submitRegistration(token) }) {
                    return@launch
                } else {
                    authState = authState.copy(
                        isSubmitting = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = "Не удалось подключиться к серверу."
                )
            }
        }
    }

    private fun verifyLogin() {
        val verificationRequestId = authState.verificationRequestId.ifBlank {
            UUID.randomUUID().toString().replace("-", "").lowercase()
        }
        authState = authState.copy(
            verificationRequestId = verificationRequestId,
            isSubmitting = true,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                apiClient.verifyLogin(
                    email = authState.email,
                    code = authState.code,
                    challengeToken = authState.challengeToken,
                    verificationRequestId = verificationRequestId
                )
                openNativeApp()
            } catch (error: AloApiException) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = error.message
                )
            } catch (_error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = "Не удалось подключиться к серверу."
                )
            }
        }
    }

    private fun verifyEmail() {
        authState = authState.copy(isSubmitting = true, errorMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                apiClient.verifyEmail(
                    email = authState.email,
                    code = authState.code
                )
                openNativeApp()
            } catch (error: AloApiException) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = error.message
                )
            } catch (_error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = "Не удалось подключиться к серверу."
                )
            }
        }
    }

    private fun resendCode() {
        if (authState.isSubmitting || authState.resendCooldownSec > 0) return

        authState = authState.copy(isSubmitting = true, errorMessage = null, isAccountBlockedByModeration = false)

        lifecycleScope.launch {
            try {
                val result = when (authState.step) {
                    AuthStep.LoginCode -> apiClient.resendLoginCode(
                        email = authState.email,
                        challengeToken = authState.challengeToken
                    )
                    AuthStep.VerifyEmailCode -> apiClient.resendVerification(authState.email)
                    AuthStep.ResetCode -> apiClient.forgotPassword(email = authState.email, resend = true)
                    else -> return@launch
                }

                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = null,
                    noticeMessage = result.message,
                    resendCooldownSec = result.nextWaitSec
                )
                startResendCooldown(result.nextWaitSec)
            } catch (error: AloApiException) {
                val waitSec = error.payload.optInt("retryAfterSec", 0)
                if (waitSec > 0) {
                    startResendCooldown(waitSec)
                }
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = error.message,
                    resendCooldownSec = waitSec
                )
            } catch (_error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = "Не удалось подключиться к серверу."
                )
            }
        }
    }

    private fun startResendCooldown(seconds: Int) {
        resendCountdownJob?.cancel()
        authState = authState.copy(resendCooldownSec = seconds.coerceAtLeast(0))

        if (seconds <= 0) return

        resendCountdownJob = lifecycleScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining -= 1
                authState = authState.copy(resendCooldownSec = remaining)
            }
        }
    }

    private fun showError(message: String) {
        authState = authState.copy(errorMessage = message, noticeMessage = null)
    }

    private suspend fun loadNativeApp(isInitial: Boolean) {
        val startingOwner = appState.bootstrap?.me?.id
        val sessionCookie = currentCookieHeader()
        val expectedOwnerId = secureAccountStore.offlineOwnerForSession(sessionCookie)
        fun sameSession() = AloOfflineAccountPolicy.matchesSession(sessionCookie, currentCookieHeader())
        if (!isInitial) {
            appState = appState.copy(isRefreshing = true, errorMessage = null)
            rootScreen = RootScreen.App
        }

        try {
            val snapshot = apiClient.loadBootstrapSnapshot()
            val bootstrap = snapshot.data
            // An older account's request may finish after the switcher has
            // installed a different cookie. Never bind its cache to that session.
            if (!sameSession() || (expectedOwnerId > 0 && bootstrap.me.id != expectedOwnerId)) return
            secureAccountStore.saveCurrentSession(bootstrap.me, sessionCookie)
            val savedAccounts = secureAccountStore.accounts()
            offlineCacheStore.inBackground { saveBootstrap(snapshot.rawJson) }
            val blockedUserIds = loadBlockedUserIds(bootstrap.me.id)
            if (!sameSession()) return
            activatePendingChatOutbox(bootstrap.me.id)
            val resolvedPeerId = resolveActiveConversationPeerId(
                bootstrap = bootstrap,
                preferredPeerId = appState.activeConversationPeerId
            )
            val activeConversation = loadInitialConversationDetail(
                bootstrap = bootstrap,
                peerId = resolvedPeerId
            )
            if (!sameSession()) return
            val mergedActiveConversation = activeConversation?.let { mergePendingLocalMessages(it) }
            syncChatPins(bootstrap.me.id)
            appState = appState.copy(
                bootstrap = bootstrap,
                activeConversationPeerId = resolvedPeerId,
                activeConversation = mergedActiveConversation,
                activeForumTopicId = mergedActiveConversation?.let {
                    selectedForumTopicId(it)
                } ?: 0,
                activeChatAppearance = chatAppearanceStore.read(mergedActiveConversation),
                blockedUserIds = blockedUserIds,
                blockedUserPeers = latestBlockedUserPeers,
                blockActionUserId = null,
                isRefreshing = false,
                isSubmittingPost = false,
                isSubmittingComment = false,
                isLoadingConversation = false,
                isCommentsLoading = false,
                isSendingMessage = false,
                savedAccounts = savedAccounts,
                isAccountSecurityLoading = false,
                isOfflineCached = false,
                pagedListsEnded = emptySet(),
                pagedListsLoading = emptySet(),
                likedPosts = if (startingOwner == bootstrap.me.id) appState.likedPosts else emptyList(),
                pendingLikePostId = null,
                pendingRepostPostId = null,
                errorMessage = apiClient.displayedE2eeWarning
            )
            startChatRealtime(bootstrap.me.id)
            refreshAccountSummaries()
            secretChats.refreshAll()
            if (startingOwner != bootstrap.me.id) restoreActiveMessageDraft()
            scheduleGlobalConversationSearchIndex(bootstrap)
            if (mergedActiveConversation != null) {
                syncConversationPreview(resolvedPeerId, mergedActiveConversation.items.lastOrNull())
            }
            processPendingChatOperations()
            AloPushRegistration.sync(this@MainActivity, apiClient)
            publicationQueue.activate(bootstrap.me.id, sessionCookie)
            rootScreen = RootScreen.App
            callCoordinator.startDirectCallPolling()
            callCoordinator.startGroupCallPolling()
            refreshStories(silently = true)
            processPendingDeepLinkIfReady()
        } catch (error: AloApiException) {
            if (!sameSession()) return
            if (error.status == 401) {
                handleSessionExpired()
            } else {
                if (appState.bootstrap == null && restoreCachedBootstrap()) {
                    return
                }
                appState = appState.copy(
                    isRefreshing = false,
                    isSubmittingPost = false,
                    isSubmittingComment = false,
                    isLoadingConversation = false,
                    isCommentsLoading = false,
                    isSendingMessage = false,
                    pendingLikePostId = null,
                    pendingRepostPostId = null,
                    errorMessage = if (
                        appState.bootstrap != null &&
                        (error.status == 0 || error.code == "CONNECTION_FAILED")
                    ) null else error.message
                )
                rootScreen = RootScreen.App
            }
        } catch (_error: Exception) {
            if (!sameSession()) return
            if (appState.bootstrap == null && restoreCachedBootstrap()) {
                return
            }
                appState = appState.copy(
                    isRefreshing = false,
                    isSubmittingPost = false,
                    isSubmittingComment = false,
                    isLoadingConversation = false,
                    isCommentsLoading = false,
                    isSendingMessage = false,
                    isOfflineCached = appState.bootstrap == null,
                    pendingLikePostId = null,
                    pendingRepostPostId = null,
                    errorMessage = null
                )
            rootScreen = RootScreen.App
        }
    }

    private suspend fun restoreCachedBootstrap(): Boolean {
        val cookie = currentCookieHeader()
        val (bootstrap, blockedUserIds) = withContext(Dispatchers.Default) {
            val ownerUserId = secureAccountStore.offlineOwnerForSession(cookie)
            if (ownerUserId <= 0) return@withContext null
            val cached = offlineCacheStore.readBootstrapRaw(ownerUserId) ?: return@withContext null
            val parsed = runCatching { apiClient.parseBootstrapResponse(cached) }.getOrNull() ?: return@withContext null
            if (parsed.me.id != ownerUserId) return@withContext null
            parsed to offlineCacheStore.readBlockedUserIds(parsed.me.id)
        } ?: return false
        activatePendingChatOutbox(bootstrap.me.id)
        val resolvedPeerId = resolveActiveConversationPeerId(
            bootstrap = bootstrap,
            preferredPeerId = appState.activeConversationPeerId
        )
        val activeConversation = buildConversationDetailFromBootstrap(bootstrap, resolvedPeerId)
        val mergedActiveConversation = activeConversation?.let { mergePendingLocalMessages(it) }
        appState = appState.copy(
            bootstrap = bootstrap,
            activeConversationPeerId = resolvedPeerId,
            activeConversation = mergedActiveConversation,
            activeForumTopicId = mergedActiveConversation?.let {
                selectedForumTopicId(it)
            } ?: 0,
            activeChatAppearance = mergedActiveConversation?.let(chatAppearanceStore::read) ?: AloChatAppearance(),
            blockedUserIds = blockedUserIds,
            blockActionUserId = null,
            isRefreshing = false,
            isSubmittingPost = false,
            isSubmittingComment = false,
            isLoadingConversation = false,
            isCommentsLoading = false,
            isSendingMessage = false,
            isOfflineCached = true,
            pendingLikePostId = null,
            pendingRepostPostId = null,
            errorMessage = null
        )
        startChatRealtime(bootstrap.me.id)
        restoreActiveMessageDraft()
        publicationQueue.activate(bootstrap.me.id, currentCookieHeader())
        rootScreen = RootScreen.App
        processPendingDeepLinkIfReady()
        return true
    }

    private fun restoreActiveMessageDraft() {
        val state = appState
        val owner = state.bootstrap?.me?.id ?: return
        val detail = state.activeConversation ?: return
        val draft = messageDraftStore.restoredFor(owner, detail, state.activeForumTopicId) ?: return
        appState = state.copy(messageComposerText = draft.text, messageRecorderMode = draft.recorderMode,
            messageDraftAttachment = draft.attachment, messageDraftAttachments = draft.attachments,
            messageForwardDraft = draft.forwardDraft, messageReplyTo = draft.replyTo, messageSelfDestructSeconds = draft.selfDestructSeconds,
            messageCaptureState = if (draft.attachment != null || draft.attachments.isNotEmpty()) AloChatCaptureState.Preview else AloChatCaptureState.Idle)
    }

    private fun captureDeepLink(intent: Intent?) {
        if (intent?.hasExtra("openToken") == true) AloCrashReporter.note("push_tap")
        runCatching { pushOpenStore.capture(intent?.getStringExtra("openToken"), intent?.getStringExtra("payloadVersion")) }
        intent?.getStringExtra("accountId")?.toIntOrNull()?.takeIf { it > 0 }?.let { pendingPushAccountId = it }
        val uri = intent?.data ?: return
        if (AloDeepLinkTarget.parse(uri) == null) return
        appPrefs.edit().putString(PENDING_DEEP_LINK_KEY, uri.toString()).apply()
    }

    private fun processPendingPushOpenIfReady() {
        val owner = appState.bootstrap?.me?.id ?: return
        if (rootScreen != RootScreen.App || isProcessingPushOpen) return
        val wantedAccount = pendingPushAccountId
        if (wantedAccount > 0) {
            pendingPushAccountId = 0
            if (wantedAccount != owner && secureAccountStore.sessionFor(wantedAccount) != null) {
                // The push stays in pushOpenStore and opens once the other account has loaded.
                switchSecurityAccount(wantedAccount)
                return
            }
        }
        val cookie = currentCookieHeader()
        fun current() = appState.bootstrap?.me?.id == owner && AloOfflineAccountPolicy.matchesSession(cookie, currentCookieHeader())
        isProcessingPushOpen = true
        lifecycleScope.launch {
            try {
                while (current()) {
                    val token = pushOpenStore.next(owner) ?: break
                    try {
                        val target = apiClient.resolvePushOpen(token)
                        if (!current()) return@launch
                        when (target.getString("kind")) {
                            "chat" -> {
                                val room = target.optInt("roomId")
                                val peer = target.getInt("peerId")
                                require(AloPendingPushOpenStore.isValidChatTarget(peer, room))
                                handleAppAction(AloNativeAppAction.SelectTab(AloAppTab.Messages))
                                openNativeConversation(peer, if (room > 0) "group" else "direct", room)
                            }
                            "post" -> {
                                val post = apiClient.loadPost(target.getInt("postId"))
                                if (!current()) return@launch
                                handleAppAction(AloNativeAppAction.SelectTab(AloAppTab.Feed))
                                openLegacyPostComments(post, target.optInt("commentId"))
                            }
                            "friendRequest", "notifications" -> {
                                handleAppAction(AloNativeAppAction.SelectTab(AloAppTab.Alerts))
                                apiClient.parseNotificationUpdate(target.optJSONObject("notification"))?.let { item ->
                                    updateBootstrap { it.copy(notifications = listOf(item) + it.notifications.filter { row -> row.id != item.id }) }
                                }
                            }
                            else -> error("Неизвестная цель уведомления")
                        }
                        pushOpenStore.complete(token)
                    } catch (error: AloApiException) {
                        if (!current()) return@launch
                        if (error.status == 404 || error.status == 403) {
                            // May belong to a different saved account. Retain it
                            // for that account, but don't repeatedly open it here.
                            pushOpenStore.unavailableFor(token, owner)
                            appState = appState.copy(notice = "Уведомление недоступно в этом аккаунте")
                        } else break // Offline/auth: replay after reconnect/sign-in.
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Keep the pending token for a later retry. */ }
            finally { isProcessingPushOpen = false }
        }
    }

    private fun processPendingDeepLinkIfReady() {
        processPendingPushOpenIfReady()
        if (rootScreen != RootScreen.App || isProcessingDeepLink) return
        val raw = appPrefs.getString(PENDING_DEEP_LINK_KEY, null) ?: return
        val target = AloDeepLinkTarget.parse(Uri.parse(raw)) ?: run {
            appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
            return
        }
        isProcessingDeepLink = true
        lifecycleScope.launch {
            try {
                when (target) {
                    is AloDeepLinkTarget.Invite,
                    is AloDeepLinkTarget.Community -> {
                        val (peerId, pendingApproval) = when (target) {
                            is AloDeepLinkTarget.Invite -> apiClient.joinGroupInvite(target.token)
                            is AloDeepLinkTarget.Community -> apiClient.joinPublicGroup(target.username)
                            else -> error("Unsupported community link")
                        }
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        if (pendingApproval) {
                            Toast.makeText(
                                this@MainActivity,
                                "Заявка на вступление отправлена.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (peerId > 0) {
                            loadNativeApp(isInitial = false)
                            openNativeConversation(peerId)
                        }
                    }
                    is AloDeepLinkTarget.Post -> {
                        val post = apiClient.loadPost(target.postId)
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        appState = appState.copy(activeTab = AloAppTab.Feed)
                        openPostComments(post)
                    }
                    is AloDeepLinkTarget.PostCode -> {
                        val post = apiClient.loadPostByShareCode(target.code)
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        appState = appState.copy(activeTab = AloAppTab.Feed)
                        openPostComments(post)
                    }
                    is AloDeepLinkTarget.User -> {
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        openViewedProfile(AloProfileTarget.User(target.userId))
                    }
                    is AloDeepLinkTarget.Channel -> {
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        openViewedProfile(AloProfileTarget.Channel(target.channelId))
                    }
                    is AloDeepLinkTarget.QrLogin -> {
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        approveQrLoginPayload(raw)
                    }
                    is AloDeepLinkTarget.Story -> {
                        val result = apiClient.resolveStoryLink(target.code)
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        appState = when (result) {
                            is AloStoryLinkResult.Live -> appState.copy(linkedStory = result.group, expiredStoryLink = null)
                            is AloStoryLinkResult.Expired -> appState.copy(linkedStory = null, expiredStoryLink = result)
                        }
                    }
                    is AloDeepLinkTarget.ForumTopic -> {
                        val resolved = apiClient.resolveForumTopicLink(target.roomId, target.topicId)
                        appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        loadNativeApp(isInitial = false)
                        openNativeConversation(
                            peerId = resolved.peerId,
                            fallbackKind = "group",
                            fallbackRoomId = resolved.roomId,
                            requestedTopicId = resolved.topicId
                        )
                    }
                    is AloDeepLinkTarget.Handle -> {
                        val resolved = apiClient.resolveAppLink(target.handle)
                        when (resolved.kind) {
                            "user" -> {
                                appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                                openViewedProfile(AloProfileTarget.User(resolved.id))
                            }
                            "channel" -> {
                                appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                                openViewedProfile(AloProfileTarget.Channel(resolved.id))
                            }
                            "community", "messenger_channel" -> {
                                val (peerId, pendingApproval) = apiClient.joinPublicGroup(resolved.handle)
                                appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                                if (pendingApproval) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Заявка на вступление отправлена.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (peerId > 0) {
                                    loadNativeApp(isInitial = false)
                                    openNativeConversation(peerId)
                                }
                            }
                            else -> appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                        }
                    }
                }
            } catch (error: AloApiException) {
                if (error.status != 0 && error.status != 401 && error.code != "CONNECTION_FAILED") {
                    appPrefs.edit().remove(PENDING_DEEP_LINK_KEY).apply()
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                // Keep the link and retry after the connection is restored.
            } finally {
                isProcessingDeepLink = false
            }
        }
    }

    private fun hasStoredSessionCookie(): Boolean {
        return !AloCookies.get(AloServerConfiguration.apiBaseUrl).isNullOrBlank()
    }

    private fun restoreActiveAccountCookiesIfNeeded() {
        if (hasStoredSessionCookie()) return
        val accountId = secureAccountStore.activeAccountId()
        if (accountId <= 0) return
        val sessionCookie = secureAccountStore.sessionFor(accountId).orEmpty()
        if (sessionCookie.isBlank()) return
        restoreCookieHeader(sessionCookie)
    }

    private fun restoreCookieHeader(cookieHeader: String) {
        val cookieManager = AloCookies
        cookieManager.removeAll()
        cookieHeader.split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { cookie -> cookieManager.set(AloServerConfiguration.apiBaseUrl, cookie) }
        cookieManager.flush()
    }

    private fun currentCookieHeader(): String = AloCookies.get(AloServerConfiguration.apiBaseUrl)
        .orEmpty()
        .trim()

    private fun clearOfflineCache() {
        globalConversationSearchIndexJob?.cancel()
        globalConversationSearchIndexJob = null
        val ownerUserId = appState.bootstrap?.me?.id
            ?: secureAccountStore.offlineOwnerForSession(currentCookieHeader())
        offlineCacheStore.clear(ownerUserId)
    }

    /** Filled with every load of the blocked list; goes into the state together with the ids. */
    private var latestBlockedUserPeers: List<AloConversationPeer> = emptyList()

    /** After blocking or unblocking: the list with names again, so «Чёрный список» shows the new person by name. */
    private fun refreshBlockedUserPeers(ownerUserId: Int) {
        lifecycleScope.launch {
            val peers = runCatching { apiClient.loadBlockedUsers().second }.getOrNull() ?: return@launch
            if (appState.bootstrap?.me?.id != ownerUserId) return@launch
            latestBlockedUserPeers = peers
            appState = appState.copy(blockedUserPeers = peers)
        }
    }

    private suspend fun loadBlockedUserIds(ownerUserId: Int): Set<Int> {
        if (ownerUserId <= 0) return emptySet()
        return try {
            val (blockedUserIds, peers) = apiClient.loadBlockedUsers()
            latestBlockedUserPeers = peers
            withContext(Dispatchers.IO) {
                offlineCacheStore.saveBlockedUserIds(ownerUserId, blockedUserIds)
            }
            blockedUserIds
        } catch (error: AloApiException) {
            if (error.status == 401) throw error
            withContext(Dispatchers.IO) {
                offlineCacheStore.readBlockedUserIds(ownerUserId)
            }
        } catch (_error: Exception) {
            withContext(Dispatchers.IO) {
                offlineCacheStore.readBlockedUserIds(ownerUserId)
            }
        }
    }

    private fun updateUserBlock(userId: Int, blocked: Boolean) {
        val ownerUserId = appState.bootstrap?.me?.id ?: return
        if (userId <= 0 || userId == ownerUserId) return
        if (appState.blockActionUserId != null || isBlockedUser(userId) == blocked) return

        appState = appState.copy(blockActionUserId = userId, errorMessage = null)
        lifecycleScope.launch {
            try {
                if (blocked) {
                    apiClient.blockUser(userId)
                } else {
                    apiClient.unblockUser(userId)
                }
                if (appState.bootstrap?.me?.id != ownerUserId) return@launch

                val updatedBlockedUserIds = AloUserBlockPolicy.updated(
                    blockedUserIds = appState.blockedUserIds,
                    userId = userId,
                    blocked = blocked
                )
                withContext(Dispatchers.IO) {
                    offlineCacheStore.saveBlockedUserIds(ownerUserId, updatedBlockedUserIds)
                }
                appState = appState.copy(
                    blockedUserIds = updatedBlockedUserIds,
                    blockActionUserId = null,
                    errorMessage = null
                )
                refreshBlockedUserPeers(ownerUserId)
                if (blocked) {
                    if (isBlockedDirectConversation()) {
                        cleanupComposerTransientState()
                    }
                    if (!isProcessingPendingChatOperations) {
                        discardPendingOutgoingMessagesToBlockedUser(userId)
                    }
                    if (appState.activeDirectCall?.peerId == userId) {
                        callCoordinator.endDirectCall()
                    }
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (appState.bootstrap?.me?.id == ownerUserId) {
                    appState = appState.copy(
                        blockActionUserId = null,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                if (appState.bootstrap?.me?.id == ownerUserId) {
                    appState = appState.copy(
                        blockActionUserId = null,
                        errorMessage = if (blocked) {
                            "Не удалось заблокировать пользователя."
                        } else {
                            "Не удалось разблокировать пользователя."
                        }
                    )
                }
            }
        }
    }

    private fun isBlockedUser(userId: Int): Boolean =
        AloUserBlockPolicy.isBlockedUser(userId, appState.blockedUserIds)

    private fun isBlockedDirectConversation(
        conversation: AloConversationDetail? = appState.activeConversation
    ): Boolean = conversation?.isBlockedDirectPeer(appState.blockedUserIds) == true

    private fun isBlockedDirectTarget(target: PendingChatTarget): Boolean =
        AloUserBlockPolicy.blocksDirectCommunication(
            kind = target.kind,
            peerId = target.peerId,
            entityType = "",
            blockedUserIds = appState.blockedUserIds
        )

    private fun showBlockedPeerError() {
        appState = appState.copy(errorMessage = AloUserBlockPolicy.BLOCKED_PEER_MESSAGE)
    }

    private fun discardPendingOutgoingMessagesToBlockedUser(userId: Int) {
        val discarded = pendingChatOperations.filter { operation ->
            operation.isOutgoingMessageTo(userId)
        }
        if (discarded.isEmpty()) return

        pendingChatOperations.removeAll(discarded.toSet())
        discarded.filterIsInstance<PendingChatOperation.SendDraft>().forEach { operation ->
            operation.attachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
            deletePersistedDraftAttachments(
                clientMessageId = operation.clientMessageId,
                count = operation.attachments.size
            )
        }
        val localMessageIds = discarded.mapNotNull { operation ->
            when (operation) {
                is PendingChatOperation.SendText -> operation.localId
                is PendingChatOperation.SendDraft -> operation.localId
                else -> null
            }
        }.toSet()
        val targets = discarded.map(PendingChatOperation::target).toSet()
        targets.forEach { target ->
            replaceActiveConversationItems(target) { messages ->
                messages.filterNot { message -> message.id in localMessageIds }
            }
        }
        persistPendingChatOperations()
        appState = appState.copy(
            pendingChatMessageIds = appState.pendingChatMessageIds - localMessageIds,
            isSendingMessage = false
        )
    }

    private fun PendingChatOperation.isOutgoingMessageTo(userId: Int): Boolean {
        return (this is PendingChatOperation.SendText || this is PendingChatOperation.SendDraft) &&
            target.kind.equals("direct", ignoreCase = true) &&
            target.peerId == userId
    }

    private fun updateThemeMode(mode: AloThemeMode) {
        if (appState.themeMode == mode) return
        appState = appState.copy(themeMode = mode)
        appPrefs.edit().putString("theme_mode", mode.name).apply()
    }

    private fun readSavedThemeMode(): AloThemeMode {
        return runCatching {
            val raw = appPrefs.getString("theme_mode", AloThemeMode.Dark.name)
            AloThemeMode.valueOf(raw ?: AloThemeMode.Dark.name)
        }.getOrDefault(AloThemeMode.Dark)
    }

    private fun requestHumanChallenge(
        message: String,
        retry: (String) -> Unit
    ) {
        val challenge = AloHumanChallengeUiState(
            url = buildAppUrl("/auth/turnstile-mobile.html"),
            message = message.ifBlank { "Подтвердите, что вы не робот." }
        )
        pendingHumanChallengeRetry = retry
        if (rootScreen == RootScreen.App) {
            appState = appState.copy(
                humanChallenge = challenge,
                isSubmittingPost = false,
                isSubmittingComment = false,
                isSendingMessage = false,
                errorMessage = null
            )
        } else {
            authHumanChallenge = challenge
            authState = authState.copy(isSubmitting = false, errorMessage = null)
        }
    }

    private fun handleHumanChallengeIfNeeded(
        error: AloApiException,
        retry: (String) -> Unit
    ): Boolean {
        if (error.code != "CHALLENGE_REQUIRED") return false
        requestHumanChallenge(error.message, retry)
        return true
    }

    private fun submitHumanChallenge(token: String) {
        val retry = pendingHumanChallengeRetry
        pendingHumanChallengeRetry = null
        appState = appState.copy(humanChallenge = null, errorMessage = null)
        authHumanChallenge = null
        if (token.isNotBlank()) {
            retry?.invoke(token)
        }
    }

    private fun closeHumanChallenge() {
        pendingHumanChallengeRetry = null
        appState = appState.copy(humanChallenge = null)
        authHumanChallenge = null
    }

    private fun submitNativePost(captchaToken: String? = null) {
        if (designPreview == null) {
            enqueuePublication("post", appState.composeText.trim(), emptyList(), onResult = { error ->
                if (error != null) appState = appState.copy(errorMessage = error)
            })
            return
        }
        if (appState.isSubmittingPost) return
        val body = appState.composeText.trim()
        if (body.isEmpty()) {
            appState = appState.copy(errorMessage = "Пост не должен быть пустым.")
            return
        }
        if (body.length > 1200) {
            appState = appState.copy(errorMessage = "Пост слишком длинный.")
            return
        }

        appState = appState.copy(isSubmittingPost = true, errorMessage = null)

        lifecycleScope.launch {
            try {
                val created = apiClient.createPost(body, captchaToken)
                appState = appState.copy(
                    composeText = "",
                    isSubmittingPost = false,
                    errorMessage = null
                )
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = prependPost(data.feedPopular, created),
                        feedSubscriptions = prependPost(data.feedSubscriptions, created),
                        profilePosts = prependPost(data.profilePosts, created)
                    )
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token -> submitNativePost(token) }) {
                    return@launch
                } else {
                    appState = appState.copy(
                        isSubmittingPost = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSubmittingPost = false,
                    errorMessage = "Не удалось опубликовать пост."
                )
            }
        }
    }

    /** A photo or video picked on this device: its pixel size, so the feed reserves the right height. */
    private fun mediaDimensions(uri: android.net.Uri, isVideo: Boolean): Pair<Int, Int> = runCatching {
        if (isVideo) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                if (rotation % 180 != 0) h to w else w to h
            } finally {
                retriever.release()
            }
        } else {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            options.outWidth to options.outHeight
        }
    }.getOrDefault(0 to 0)

    /** Uploads picked media for a post or a draft (purpose "post", as on iOS). */
    private suspend fun uploadPostMedia(uploads: List<AloStoryUploadPage>): List<AloMediaItem> = uploads.map { page ->
        withContext(Dispatchers.IO) {
            val source = page.uri ?: throw IllegalStateException("Нет файла для загрузки.")
            val (width, height) = mediaDimensions(source, page.type == "video")
            val temp = File.createTempFile("alo-post", if (page.type == "video") ".mp4" else ".jpg", cacheDir)
            try {
                contentResolver.openInputStream(source)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("Не удалось прочитать медиа.")
                val uploaded = apiClient.uploadPostFile(temp, page.mime)
                AloMediaItem(type = page.type, url = uploaded.url, posterUrl = "", alt = "", caption = "", width = width, height = height)
            } finally {
                temp.delete()
            }
        }
    }

    private fun publishPost(action: AloNativeAppAction.PublishPost, captchaToken: String? = null) {
        if (designPreview == null) {
            enqueuePublication("post", appState.composeText.trim(), action.uploads, action.draftMedia, action.draftId, onResult = action.onResult)
            return
        }
        if (appState.isSubmittingPost && captchaToken == null) return
        val body = appState.composeText.trim()
        if (body.length > 1200) { action.onResult("Пост слишком длинный."); return }
        appState = appState.copy(isSubmittingPost = true)
        lifecycleScope.launch {
            try {
                val created = if (designPreview != null) {
                    kotlinx.coroutines.delay(400)
                    val me = appState.bootstrap?.me ?: return@launch
                    val media = action.draftMedia + action.uploads.mapNotNull { page ->
                        page.uri?.let { uri ->
                            val (w, h) = mediaDimensions(uri, page.type == "video")
                            AloMediaItem(type = page.type, url = uri.toString(), posterUrl = "", alt = "", caption = "", width = w, height = h)
                        }
                    }
                    AloPost(
                        id = (System.currentTimeMillis() % 100_000).toInt() + 800_000, creatorId = me.id, body = body, media = media,
                        authorSignature = "", commentsEnabled = true, discussionRoomId = 0, discussionTopicId = 0, messengerRoomId = 0,
                        likeCount = 0, commentCount = 0, repostCount = 0, viewCount = 0, viewerLiked = false, viewerReposted = false,
                        createdAt = java.time.Instant.now().toString(),
                        author = AloEntitySummary(me.id, me.name, me.username, me.avatarUrl, me.verified), feedReason = "own"
                    )
                } else {
                    val media = action.draftMedia + uploadPostMedia(action.uploads)
                    apiClient.createPost(body, captchaToken, media, action.draftId)
                }
                appState = appState.copy(
                    composeText = "",
                    isSubmittingPost = false,
                    postDrafts = appState.postDrafts.filter { it.id != action.draftId },
                    errorMessage = null
                )
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = prependPost(data.feedPopular, created),
                        feedSubscriptions = prependPost(data.feedSubscriptions, created),
                        profilePosts = prependPost(data.profilePosts, created)
                    )
                }
                action.onResult(null)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token -> publishPost(action, token) }) {
                    return@launch
                } else {
                    appState = appState.copy(isSubmittingPost = false)
                    action.onResult(error.message ?: "Не удалось опубликовать пост.")
                }
            } catch (_: Exception) {
                appState = appState.copy(isSubmittingPost = false)
                action.onResult("Не удалось опубликовать пост. Проверьте подключение.")
            }
        }
    }

    private fun savePostDraft(action: AloNativeAppAction.SavePostDraft) {
        val body = appState.composeText
        lifecycleScope.launch {
            try {
                val saved = if (designPreview != null) {
                    AloPostDraft(
                        id = action.draftId ?: (990950 + appState.postDrafts.size), body = body,
                        media = action.draftMedia + action.uploads.mapNotNull { page ->
                            page.uri?.let { AloMediaItem(type = page.type, url = it.toString(), posterUrl = "", alt = "", caption = "") }
                        },
                        updatedAt = java.time.Instant.now().toString()
                    )
                } else {
                    apiClient.savePostDraft(action.draftId, body, action.draftMedia + uploadPostMedia(action.uploads))
                }
                appState = appState.copy(
                    postDrafts = listOf(saved) + appState.postDrafts.filter { it.id != saved.id },
                    composeText = "",
                    notice = "Черновик сохранён"
                )
                action.onResult(null)
            } catch (error: AloApiException) {
                action.onResult(error.message ?: "Не удалось сохранить черновик.")
            } catch (_: Exception) {
                action.onResult("Не удалось сохранить черновик. Проверьте подключение.")
            }
        }
    }

    private fun refreshStories(silently: Boolean = false) {
        if (appState.isStoriesLoading || designPreview != null) return
        appState = appState.copy(
            isStoriesLoading = true,
            errorMessage = if (silently) appState.errorMessage else null
        )
        lifecycleScope.launch {
            try {
                val stories = apiClient.loadStories()
                appState = appState.copy(
                    stories = stories,
                    isStoriesLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isStoriesLoading = false,
                        errorMessage = if (silently) appState.errorMessage else error.message
                    )
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isStoriesLoading = false,
                    errorMessage = if (silently) appState.errorMessage else "Не удалось обновить истории."
                )
            }
        }
    }

    private fun submitStory(rawBody: String) {
        if (appState.isSubmittingStory) return
        val body = rawBody.trim()
        if (body.isBlank()) {
            appState = appState.copy(errorMessage = "История не должна быть пустой.")
            return
        }
        if (body.length > 600) {
            appState = appState.copy(errorMessage = "Текст истории слишком длинный.")
            return
        }
        if (designPreview == null) {
            enqueuePublication("story", body, emptyList(), onResult = { error ->
                if (error != null) appState = appState.copy(errorMessage = error)
            })
            return
        }
        appState = appState.copy(isSubmittingStory = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val created = apiClient.createStory(body = body)
                val me = appState.bootstrap?.me
                val myStory = created.copy(viewerHasSeen = true)
                val updatedStories = if (me == null) {
                    appState.stories
                } else {
                    val author = AloEntitySummary(
                        id = me.id,
                        name = me.name,
                        username = me.username,
                        avatarUrl = me.avatarUrl,
                        verified = me.verified
                    )
                    val existingIndex = appState.stories.indexOfFirst { it.user.id == me.id }
                    if (existingIndex >= 0) {
                        appState.stories.toMutableList().apply {
                            val existing = get(existingIndex)
                            set(existingIndex, existing.copy(stories = listOf(myStory) + existing.stories, hasUnseen = false))
                        }
                    } else {
                        listOf(AloStoryGroup(user = author, stories = listOf(myStory), hasUnseen = false)) + appState.stories
                    }
                }
                appState = appState.copy(
                    stories = updatedStories,
                    isSubmittingStory = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isSubmittingStory = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isSubmittingStory = false,
                    errorMessage = "Не удалось опубликовать историю."
                )
            }
        }
    }

    private fun loadStoryViewers(storyId: Int) {
        appState = appState.copy(storyViewers = emptyList(), isStoryViewersLoading = true, storyViewersFailed = false)
        if (designPreview != null) {
            appState = appState.copy(storyViewers = AloDesignFixture.storyViewers(), isStoryViewersLoading = false)
            return
        }
        lifecycleScope.launch {
            appState = try {
                appState.copy(storyViewers = apiClient.loadStoryViewers(storyId), isStoryViewersLoading = false)
            } catch (_: Exception) {
                appState.copy(isStoryViewersLoading = false, storyViewersFailed = true)
            }
        }
    }

    private fun copyStoryLink(storyId: Int, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                val code = if (designPreview != null) "PreviewStory$storyId" else apiClient.createStoryLink(storyId)
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Alo", buildAloLink("s", code)))
                onResult(true)
            } catch (error: AloApiException) {
                appState = appState.copy(notice = error.message)
                onResult(false)
            } catch (_: Exception) {
                appState = appState.copy(notice = "Не удалось создать ссылку.")
                onResult(false)
            }
        }
    }

    private fun deleteStory(storyId: Int, onDone: () -> Unit) {
        val strip = {
            appState = appState.copy(
                stories = appState.stories.map { it.copy(stories = it.stories.filter { story -> story.id != storyId }) }
                    .filter { it.stories.isNotEmpty() }
            )
            onDone()
        }
        if (designPreview != null) { strip(); return }
        lifecycleScope.launch {
            try {
                apiClient.deleteStory(storyId)
                strip()
                refreshStories(silently = true)
            } catch (_: Exception) {
                appState = appState.copy(notice = "Не удалось удалить историю.")
            }
        }
    }

    private fun loadHighlights(userId: Int) {
        if (userId <= 0) return
        if (designPreview != null) {
            if (userId == appState.bootstrap?.me?.id) {
                appState = appState.copy(highlights = appState.highlights + (userId to AloDesignFixture.highlights(appState.storyArchive.ifEmpty {
                    AloDesignFixture.storyArchive(AloDesignFixture.sampleImageUri(this))
                })))
            }
            return
        }
        lifecycleScope.launch {
            runCatching { apiClient.loadHighlights(userId) }.onSuccess {
                appState = appState.copy(highlights = appState.highlights + (userId to it))
            }
        }
    }

    /** Creates or edits one of your highlights, then reloads your list. */
    private fun changeHighlights(onDone: (Boolean) -> Unit, change: suspend () -> AloStoryHighlight?) {
        val owner = appState.bootstrap?.me?.id ?: return
        if (designPreview != null) {
            appState = appState.copy(notice = "Добавлено в актуальное")
            onDone(true)
            return
        }
        lifecycleScope.launch {
            try {
                change()
                appState = appState.copy(highlights = appState.highlights + (owner to apiClient.loadHighlights(owner)))
                onDone(true)
            } catch (error: AloApiException) {
                appState = appState.copy(notice = error.message)
                onDone(false)
            } catch (_: Exception) {
                appState = appState.copy(notice = "Не удалось сохранить актуальное. Проверьте соединение.")
                onDone(false)
            }
        }
    }

    private fun loadCreatorStats(days: Int) {
        appState = appState.copy(isCreatorStatsLoading = true, creatorStatsError = "")
        if (designPreview != null) {
            appState = appState.copy(
                creatorStats = AloDesignFixture.creatorStats(days, appState.bootstrap?.feedPopular.orEmpty()),
                isCreatorStatsLoading = false
            )
            return
        }
        lifecycleScope.launch {
            appState = try {
                appState.copy(creatorStats = apiClient.loadCreatorStats(days), isCreatorStatsLoading = false)
            } catch (error: AloApiException) {
                appState.copy(isCreatorStatsLoading = false, creatorStatsError = error.message ?: "Статистика недоступна.")
            } catch (_: Exception) {
                appState.copy(isCreatorStatsLoading = false, creatorStatsError = "Проверьте подключение и попробуйте ещё раз.")
            }
        }
    }

    private fun loadStoryArchive(more: Boolean) {
        if (appState.isStoryArchiveLoading) return
        if (designPreview != null) {
            appState = appState.copy(storyArchive = AloDesignFixture.storyArchive(AloDesignFixture.sampleImageUri(this)), storyArchiveNext = "")
            return
        }
        val before = if (more) appState.storyArchiveNext.takeIf { it.isNotBlank() } ?: return else ""
        appState = appState.copy(isStoryArchiveLoading = true)
        lifecycleScope.launch {
            appState = try {
                val (stories, next) = apiClient.loadStoryArchive(before)
                appState.copy(
                    storyArchive = if (more) appState.storyArchive + stories else stories,
                    storyArchiveNext = next,
                    isStoryArchiveLoading = false
                )
            } catch (_: Exception) {
                appState.copy(isStoryArchiveLoading = false, notice = "Не удалось загрузить архив историй.")
            }
        }
    }

    /** Your friends and close friends, for the story audience sheet. */
    private fun loadStoryAudience() {
        if (designPreview != null) {
            appState = appState.copy(friends = AloDesignFixture.friends())
            return
        }
        lifecycleScope.launch {
            runCatching { apiClient.loadFriendsList() }.onSuccess { appState = appState.copy(friends = it) }
            runCatching { apiClient.loadStorySettings() }.onSuccess { appState = appState.copy(storySettings = it) }
        }
    }

    private fun updateCloseFriends(userIds: List<Int>) {
        val chosen = appState.friends.filter { it.id in userIds }
        appState = appState.copy(storySettings = appState.storySettings.copy(closeFriends = chosen))
        if (designPreview != null) return
        lifecycleScope.launch {
            try {
                appState = appState.copy(storySettings = appState.storySettings.copy(closeFriends = apiClient.updateCloseFriends(userIds)))
            } catch (_: Exception) {
                appState = appState.copy(notice = "Не удалось сохранить близких друзей.")
            }
        }
    }

    /** Uploads each page (flattened picture or original media), then publishes the story. */
    private fun publishStory(action: AloNativeAppAction.PublishStory) {
        if (designPreview == null) {
            enqueuePublication("story", action.body, action.pages, lifetimeHours = action.lifetimeHours,
                audience = action.audience, audienceUserIds = action.audienceUserIds, onResult = action.onResult)
            return
        }
        if (appState.isSubmittingStory) return
        val me = appState.bootstrap?.me ?: return
        appState = appState.copy(isSubmittingStory = true)
        lifecycleScope.launch {
            try {
                val created = if (designPreview != null) {
                    kotlinx.coroutines.delay(500)
                    val media = action.pages.mapIndexed { index, page ->
                        val file = File(cacheDir, "alo-ui-fixture/story-$index-${System.currentTimeMillis()}.jpg")
                        page.bytes?.let { file.writeBytes(it) }
                        AloMediaItem(type = page.type, url = page.uri?.toString() ?: "file://${file.absolutePath}", posterUrl = "", alt = "", caption = "")
                    }
                    AloStory(
                        id = (System.currentTimeMillis() % 100_000).toInt() + 900_000, userId = me.id, body = action.body, media = media,
                        createdAt = java.time.Instant.now().toString(),
                        expiresAt = java.time.Instant.now().plusSeconds(action.lifetimeHours * 3_600L).toString(),
                        viewerHasSeen = true, viewCount = 0, audience = action.audience
                    )
                } else {
                    val media = action.pages.map { page ->
                        val uploaded = withContext(Dispatchers.IO) {
                            val bytes = page.bytes
                            if (bytes != null) {
                                apiClient.uploadPostMedia(bytes, page.mime)
                            } else {
                                val source = page.uri ?: throw IllegalStateException("Нет файла для загрузки.")
                                val temp = File.createTempFile("alo-story", if (page.type == "video") ".mp4" else ".jpg", cacheDir)
                                try {
                                    contentResolver.openInputStream(source)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                                        ?: throw IllegalStateException("Не удалось прочитать медиа.")
                                    apiClient.uploadPostFile(temp, page.mime)
                                } finally {
                                    temp.delete()
                                }
                            }
                        }
                        AloMediaItem(type = page.type, url = uploaded.url, posterUrl = "", alt = "", caption = "")
                    }
                    apiClient.createStory(
                        body = action.body, media = media, lifetimeHours = action.lifetimeHours,
                        audience = action.audience, audienceUserIds = action.audienceUserIds
                    )
                }
                val author = AloEntitySummary(id = me.id, name = me.name, username = me.username, avatarUrl = me.avatarUrl, verified = me.verified)
                val mine = created.copy(viewerHasSeen = true)
                val index = appState.stories.indexOfFirst { it.user.id == me.id }
                val stories = if (index >= 0) {
                    appState.stories.toMutableList().apply { set(index, get(index).copy(stories = get(index).stories + mine, hasUnseen = false)) }
                } else {
                    listOf(AloStoryGroup(user = author, stories = listOf(mine), hasUnseen = false)) + appState.stories
                }
                appState = appState.copy(stories = stories, isSubmittingStory = false, notice = "История опубликована")
                action.onResult(null)
            } catch (error: AloApiException) {
                appState = appState.copy(isSubmittingStory = false)
                action.onResult(error.message ?: "Не удалось опубликовать историю.")
            } catch (_: Exception) {
                appState = appState.copy(isSubmittingStory = false)
                action.onResult("Не удалось опубликовать историю. Проверьте соединение.")
            }
        }
    }

    private fun enqueuePublication(kind: String, body: String, pages: List<AloStoryUploadPage>, media: List<AloMediaItem> = emptyList(),
        draftId: Int? = null, lifetimeHours: Int = 24, audience: String = "everyone", audienceUserIds: List<Int> = emptyList(), onResult: (String?) -> Unit) {
        if ((kind == "post" && appState.isSubmittingPost) || (kind == "story" && appState.isSubmittingStory)) return
        val owner = appState.bootstrap?.me?.id ?: return
        val cookie = currentCookieHeader()
        fun current() = appState.bootstrap?.me?.id == owner && AloOfflineAccountPolicy.matchesSession(cookie, currentCookieHeader())
        appState = appState.copy(isSubmittingPost = if (kind == "post") true else appState.isSubmittingPost,
            isSubmittingStory = if (kind == "story") true else appState.isSubmittingStory)
        lifecycleScope.launch {
            try {
                publicationQueue.enqueue(owner, kind, body, pages, media, draftId, lifetimeHours, audience, audienceUserIds)
                if (!current()) return@launch
                appState = appState.copy(isSubmittingPost = if (kind == "post") false else appState.isSubmittingPost,
                    isSubmittingStory = if (kind == "story") false else appState.isSubmittingStory,
                    composeText = if (kind == "post" && appState.composeText.trim() == body) "" else appState.composeText,
                    notice = "Сохранено в очередь отправки")
                // The editor may now close; files are owned by the durable queue.
                onResult(null)
                publicationQueue.activate(owner, cookie)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                if (current()) {
                    appState = appState.copy(isSubmittingPost = if (kind == "post") false else appState.isSubmittingPost,
                        isSubmittingStory = if (kind == "story") false else appState.isSubmittingStory)
                    onResult(if (error is IllegalArgumentException) error.message else "Не удалось сохранить публикацию. Проверьте свободное место. Редактор не закрыт.")
                }
            }
        }
    }

    private fun markStoryViewed(storyId: Int) {
        if (storyId <= 0) return
        appState = appState.copy(
            stories = appState.stories.map { group ->
                val updatedStories = group.stories.map { story ->
                    if (story.id == storyId) story.copy(viewerHasSeen = true) else story
                }
                group.copy(stories = updatedStories, hasUnseen = updatedStories.any { !it.viewerHasSeen })
            }
        )
        lifecycleScope.launch {
            try {
                apiClient.markStoryViewed(storyId)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
            } catch (_: Exception) {
                // The optimistic seen state is safe; the server will reconcile on the next refresh.
            }
        }
    }

    private fun loadBusinessProfile() {
        // Design previews are offline: nothing to load, and no spinner that never ends.
        if (designPreview != null) return
        if (appState.isBusinessProfileLoading) return
        appState = appState.copy(isBusinessProfileLoading = true)
        lifecycleScope.launch {
            try {
                appState = appState.copy(
                    businessProfileData = apiClient.loadBusinessProfile(),
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isBusinessProfileLoading = false,
                        errorMessage = error.message
                    )
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось загрузить бизнес-профиль."
                )
            }
        }
    }

    private fun saveBusinessProfile(
        enabled: Boolean,
        businessName: String,
        category: String,
        hours: Map<String, String>,
        greetingMessage: String,
        autoReplyEnabled: Boolean,
        autoReplyMessage: String
    ) {
        if (appState.isBusinessProfileLoading) return
        appState = appState.copy(isBusinessProfileLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val profile = apiClient.updateBusinessProfile(
                    enabled = enabled,
                    businessName = businessName,
                    category = category,
                    hours = hours,
                    greetingMessage = greetingMessage,
                    autoReplyEnabled = autoReplyEnabled,
                    autoReplyMessage = autoReplyMessage
                )
                appState = appState.copy(
                    businessProfileData = (appState.businessProfileData
                        ?: AloBusinessProfileData(profile, emptyList(), emptyList()))
                        .copy(profile = profile),
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isBusinessProfileLoading = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось сохранить бизнес-профиль."
                )
            }
        }
    }

    private fun addBusinessQuickReply(shortcut: String, title: String, body: String) {
        if (appState.isBusinessProfileLoading) return
        appState = appState.copy(isBusinessProfileLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val quickReply = apiClient.createBusinessQuickReply(shortcut, title, body)
                val current = appState.businessProfileData ?: apiClient.loadBusinessProfile()
                appState = appState.copy(
                    businessProfileData = current.copy(
                        quickReplies = (current.quickReplies + quickReply).sortedBy { it.sortOrder }
                    ),
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isBusinessProfileLoading = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось добавить быстрый ответ."
                )
            }
        }
    }

    private fun deleteBusinessQuickReply(quickReplyId: Int) {
        if (quickReplyId <= 0 || appState.isBusinessProfileLoading) return
        appState = appState.copy(isBusinessProfileLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.deleteBusinessQuickReply(quickReplyId)
                appState = appState.copy(
                    businessProfileData = appState.businessProfileData?.let { data ->
                        data.copy(quickReplies = data.quickReplies.filterNot { it.id == quickReplyId })
                    },
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isBusinessProfileLoading = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось удалить быстрый ответ."
                )
            }
        }
    }

    private fun saveBusinessChatLabel(
        chatKind: String,
        peerId: Int,
        roomId: Int,
        label: String,
        color: String
    ) {
        val normalizedKind = chatKind.trim().lowercase()
        val normalizedLabel = label.trim()
        val validTarget = when (normalizedKind) {
            "direct" -> peerId > 0
            "group" -> roomId > 0
            else -> false
        }
        if (!validTarget || normalizedLabel.isBlank() || appState.isBusinessProfileLoading) return

        appState = appState.copy(isBusinessProfileLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val saved = apiClient.saveBusinessChatLabel(
                    chatKind = normalizedKind,
                    peerId = if (normalizedKind == "direct") peerId else 0,
                    roomId = if (normalizedKind == "group") roomId else 0,
                    label = normalizedLabel,
                    color = color
                )
                val current = appState.businessProfileData ?: apiClient.loadBusinessProfile()
                val labels = current.chatLabels
                    .filterNot { existing ->
                        existing.id == saved.id || (
                            existing.chatKind == saved.chatKind &&
                                existing.peerId == saved.peerId &&
                                existing.roomId == saved.roomId
                            )
                    }
                    .plus(saved)
                    .sortedByDescending { it.updatedAt }
                appState = appState.copy(
                    businessProfileData = current.copy(chatLabels = labels),
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isBusinessProfileLoading = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось сохранить метку чата."
                )
            }
        }
    }

    private fun deleteBusinessChatLabel(chatLabelId: Int) {
        if (chatLabelId <= 0 || appState.isBusinessProfileLoading) return
        appState = appState.copy(isBusinessProfileLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.deleteBusinessChatLabel(chatLabelId)
                appState = appState.copy(
                    businessProfileData = appState.businessProfileData?.let { data ->
                        data.copy(chatLabels = data.chatLabels.filterNot { it.id == chatLabelId })
                    },
                    isBusinessProfileLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(isBusinessProfileLoading = false, errorMessage = error.message)
                }
            } catch (_: Exception) {
                appState = appState.copy(
                    isBusinessProfileLoading = false,
                    errorMessage = "Не удалось удалить метку чата."
                )
            }
        }
    }

    private fun toggleNativePostLike(
        postId: Int,
        captchaToken: String? = null
    ) {
        if (appState.pendingLikePostId == postId && captchaToken.isNullOrBlank()) return
        appState = appState.copy(pendingLikePostId = postId, errorMessage = null)

        lifecycleScope.launch {
            try {
                val updated = apiClient.toggleLike(postId, captchaToken)
                appState = appState.copy(pendingLikePostId = null)
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = replacePost(data.feedPopular, updated),
                        feedSubscriptions = replacePost(data.feedSubscriptions, updated),
                        profilePosts = replacePost(data.profilePosts, updated)
                    )
                }
                appState = appState.copy(likedPosts = replacePost(appState.likedPosts, updated))
                appState.viewedProfile?.let { viewed ->
                    appState = appState.copy(
                        viewedProfile = viewed.copy(
                            posts = replacePost(viewed.posts, updated)
                        )
                    )
                }
                if (appState.commentsPostId == postId) {
                    appState = appState.copy(commentsPost = updated)
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (error.code == "CHALLENGE_REQUIRED") {
                    appState = appState.copy(pendingLikePostId = null)
                    handleHumanChallengeIfNeeded(error) { token -> toggleNativePostLike(postId, token) }
                    return@launch
                } else {
                    appState = appState.copy(
                        pendingLikePostId = null,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    pendingLikePostId = null,
                    errorMessage = "Не удалось обновить лайк."
                )
            }
        }
    }

    private fun toggleNativePostRepost(
        postId: Int,
        captchaToken: String? = null
    ) {
        if (appState.pendingRepostPostId == postId && captchaToken.isNullOrBlank()) return
        appState = appState.copy(pendingRepostPostId = postId, errorMessage = null)

        lifecycleScope.launch {
            try {
                val updated = apiClient.toggleRepost(postId, captchaToken)
                appState = appState.copy(pendingRepostPostId = null)
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = replacePost(data.feedPopular, updated),
                        feedSubscriptions = replacePost(data.feedSubscriptions, updated),
                        profilePosts = replacePost(data.profilePosts, updated)
                    )
                }
                appState.viewedProfile?.let { viewed ->
                    appState = appState.copy(
                        viewedProfile = viewed.copy(
                            posts = replacePost(viewed.posts, updated)
                        )
                    )
                }
                if (appState.commentsPostId == postId) {
                    appState = appState.copy(commentsPost = updated)
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (error.code == "CHALLENGE_REQUIRED") {
                    appState = appState.copy(pendingRepostPostId = null)
                    handleHumanChallengeIfNeeded(error) { token -> toggleNativePostRepost(postId, token) }
                    return@launch
                } else {
                    appState = appState.copy(
                        pendingRepostPostId = null,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    pendingRepostPostId = null,
                    errorMessage = "Не удалось обновить репост."
                )
            }
        }
    }

    // ---- Feed curation (the same rules as iOS) ----

    private fun hideFeedPost(postId: Int, reason: String) {
        appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts + (postId to reason))
        if (designPreview != null) return
        lifecycleScope.launch {
            try {
                apiClient.hideFeedPost(postId, reason)
            } catch (error: AloApiException) {
                appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts - postId, notice = error.message)
            } catch (_: Exception) {
                appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts - postId, notice = "Не удалось скрыть публикацию.")
            }
        }
    }

    private fun undoHideFeedPost(postId: Int) {
        val reason = appState.hiddenFeedPosts[postId] ?: return
        appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts - postId)
        if (designPreview != null) return
        lifecycleScope.launch {
            try {
                apiClient.unhideFeedPost(postId)
            } catch (_: Exception) {
                appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts + (postId to reason), notice = "Не удалось вернуть публикацию.")
            }
        }
    }

    /** A report; a reported post also leaves the feed, as on iOS. */
    private fun reportContent(action: AloNativeAppAction.Report) {
        if (designPreview != null) {
            if (action.targetType == "post") {
                appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts + (action.targetId to "report"))
            }
            action.onResult(null)
            return
        }
        lifecycleScope.launch {
            try {
                apiClient.report(action.targetType, action.targetId, action.reason, action.details)
                if (action.targetType == "post") {
                    appState = appState.copy(hiddenFeedPosts = appState.hiddenFeedPosts + (action.targetId to "report"))
                    runCatching { apiClient.hideFeedPost(action.targetId, "report") }
                }
                action.onResult(null)
            } catch (error: AloApiException) {
                action.onResult(error.message ?: "Не удалось отправить жалобу.")
            } catch (_: Exception) {
                action.onResult("Не удалось отправить жалобу. Проверьте подключение.")
            }
        }
    }

    /** A reply names the person it answers and starts with their handle, as on iOS. */
    private fun setCommentReplyTo(comment: AloPostComment?) {
        if (comment == null) {
            val previous = appState.commentsReplyTo
            val handle = previous?.author?.username?.takeIf { it.isNotBlank() }?.let { "@$it " }
            appState = appState.copy(
                commentsReplyTo = null,
                commentsComposerText = if (handle != null) appState.commentsComposerText.removePrefix(handle) else appState.commentsComposerText
            )
            return
        }
        val handle = comment.author.username.takeIf { it.isNotBlank() }?.let { "@$it " }.orEmpty()
        val text = appState.commentsComposerText.trimStart()
        appState = appState.copy(
            commentsReplyTo = comment,
            commentsEditingCommentId = null,
            commentsComposerText = if (handle.isNotEmpty() && !text.startsWith(handle)) handle + text else text
        )
    }

    private fun toggleCommentLike(commentId: Int) {
        val postId = appState.commentsPostId
        val original = appState.comments.firstOrNull { it.id == commentId } ?: return
        if (postId <= 0 || commentId in appState.pendingCommentLikeIds) return
        val optimistic = original.copy(
            viewerLiked = !original.viewerLiked,
            likeCount = (original.likeCount + if (original.viewerLiked) -1 else 1).coerceAtLeast(0)
        )
        appState = appState.copy(
            comments = appState.comments.map { if (it.id == commentId) optimistic else it },
            pendingCommentLikeIds = appState.pendingCommentLikeIds + commentId
        )
        if (designPreview != null) {
            appState = appState.copy(pendingCommentLikeIds = appState.pendingCommentLikeIds - commentId)
            return
        }
        lifecycleScope.launch {
            val updated = runCatching { apiClient.toggleCommentLike(postId, commentId) }.getOrNull()
            appState = appState.copy(
                comments = appState.comments.map { if (it.id == commentId) (updated ?: original) else it },
                pendingCommentLikeIds = appState.pendingCommentLikeIds - commentId
            )
        }
    }

    private var mentionJob: kotlinx.coroutines.Job? = null

    private fun loadMentionSuggestions(query: String?) {
        mentionJob?.cancel()
        if (query == null) {
            appState = appState.copy(mentionSuggestions = emptyList())
            return
        }
        mentionJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(160)
            val people = runCatching { apiClient.mentionSuggestions(query) }.getOrDefault(emptyList())
            appState = appState.copy(mentionSuggestions = people)
        }
    }

    private fun chatsByKeys(keys: List<String>): List<AloConversationSummary> {
        val wanted = keys.toSet()
        return appState.bootstrap?.messagesConversations.orEmpty().filter { AloChatListPrefs.key(it) in wanted }
    }

    private fun markConversationRead(peerId: Int) {
        val data = appState.bootstrap ?: return
        val summary = data.messagesConversations.firstOrNull { it.peerId == peerId } ?: return
        unreadTracker.markRead(data.me.id, summary)
        appState = appState.copy(unreadRevision = appState.unreadRevision + 1)
    }

    /** Flips the server-side mute for a chat from the list, without opening it (as on iOS). */
    private fun toggleChatMuted(key: String) {
        val summary = chatsByKeys(listOf(key)).firstOrNull() ?: return
        if (designPreview != null) {
            appState = appState.copy(notice = "Уведомления чата выключены.")
            return
        }
        lifecycleScope.launch {
            try {
                // Muting from the list does not open the chat, so it must not read it either.
                val detail = apiClient.loadConversation(summary.kind, summary.peerId, summary.roomId, markRead = false)
                val muted = apiClient.chatMuteState(detail)
                val now = apiClient.setChatMuted(!muted, detail)
                appState = appState.copy(notice = if (now) "Уведомления чата выключены." else "Уведомления чата включены.")
            } catch (error: AloApiException) {
                appState = appState.copy(notice = error.message)
            } catch (_: Exception) {
                appState = appState.copy(notice = "Не удалось изменить уведомления чата.")
            }
        }
    }

    // Pinned chats belong to the account (as in Telegram): the server keeps the order, this device shows its copy.
    private var chatPinsUploadJob: Job? = null

    private fun applyChatPins(owner: Int, pinned: List<String>) {
        if (owner <= 0 || appState.bootstrap?.me?.id != owner) return
        if (chatListPrefs.state(owner).pinned == pinned) return
        chatListPrefs.replacePinned(owner, pinned)
        appState = appState.copy(chatPrefsRevision = appState.chatPrefsRevision + 1)
    }

    /** At start and after a gap in the updates. The first time, pins this device kept by itself join the account's list. */
    private fun syncChatPins(owner: Int) {
        if (owner <= 0 || designPreview != null) return
        lifecycleScope.launch {
            runCatching {
                val pinned = if (!chatListPrefs.pinsImported(owner)) {
                    val local = chatListPrefs.state(owner).pinned
                    val merged = if (local.isEmpty()) apiClient.chatPins() else apiClient.importChatPins(local)
                    chatListPrefs.markPinsImported(owner)
                    merged
                } else {
                    apiClient.chatPins()
                }
                if (chatPinsUploadJob?.isActive != true) applyChatPins(owner, pinned)
            }
        }
    }

    /** Sends this device's list after a pin, an unpin or a deleted chat; a refusal brings back the account's list. */
    private fun uploadChatPins(owner: Int) {
        if (designPreview != null) return
        chatPinsUploadJob?.cancel()
        chatPinsUploadJob = lifecycleScope.launch {
            val pinned = try {
                apiClient.saveChatPins(chatListPrefs.state(owner).pinned)
            } catch (error: AloApiException) {
                appState = appState.copy(notice = error.message)
                runCatching { apiClient.chatPins() }.getOrNull()
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (_: Exception) {
                null
            }
            chatPinsUploadJob = null
            if (pinned != null) applyChatPins(owner, pinned)
        }
    }

    /** One-to-one chats are deleted for this account only; groups and channels are left. */
    private fun deleteChats(keys: List<String>) {
        val data = appState.bootstrap ?: return
        val targets = chatsByKeys(keys).filter { it.peerId != data.me.id }
        if (targets.isEmpty()) return
        val removed = targets.map(AloChatListPrefs::key)
        val wasPinned = chatListPrefs.state(data.me.id).pinned.any { it in removed }
        chatListPrefs.setPinned(data.me.id, removed, pinned = false)
        chatListPrefs.setArchived(data.me.id, removed, archived = false)
        if (wasPinned) uploadChatPins(data.me.id)
        appState = appState.copy(
            bootstrap = data.copy(messagesConversations = data.messagesConversations.filter { AloChatListPrefs.key(it) !in removed }),
            chatPrefsRevision = appState.chatPrefsRevision + 1,
            notice = if (targets.size == 1) "Чат удалён." else "Чаты удалены."
        )
        if (designPreview != null) return
        lifecycleScope.launch {
            var failed = false
            targets.forEach { target ->
                try {
                    if (target.kind == "group" && target.roomId > 0) {
                        apiClient.leaveGroupChat(target.roomId)
                    } else {
                        apiClient.deleteDirectChatForMe(target.peerId)
                    }
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) {
                appState = appState.copy(notice = "Не все чаты удалось удалить. Проверьте соединение.")
                loadNativeApp(isInitial = false)
            }
        }
    }

    private fun loadCallHistory(more: Boolean) {
        if (appState.isCallHistoryLoading) return
        if (designPreview != null) {
            appState = appState.copy(callHistory = AloDesignFixture.callHistory(), callHistoryHasMore = false, callHistoryError = null)
            return
        }
        val beforeId = if (more) appState.callHistory.lastOrNull()?.id ?: return else 0
        appState = appState.copy(isCallHistoryLoading = true, callHistoryError = null)
        lifecycleScope.launch {
            try {
                val (items, hasMore) = apiClient.loadCallHistory(beforeId)
                appState = appState.copy(
                    callHistory = if (more) appState.callHistory + items else items,
                    callHistoryHasMore = hasMore,
                    isCallHistoryLoading = false
                )
            } catch (error: AloApiException) {
                appState = appState.copy(
                    isCallHistoryLoading = false,
                    callHistoryHasMore = false,
                    callHistoryError = if (more) null else if (error.status == 404) "Сервер пока не поддерживает список звонков." else error.message
                )
            } catch (_: Exception) {
                appState = appState.copy(
                    isCallHistoryLoading = false,
                    callHistoryHasMore = false,
                    callHistoryError = if (more) null else "Не удалось загрузить звонки. Проверьте соединение."
                )
            }
        }
    }

    /** «Перезвонить»: opens the chat with that person, then starts the same kind of call. */
    private fun callBack(peerId: Int, video: Boolean) {
        handleAppAction(AloNativeAppAction.SelectTab(AloAppTab.Messages))
        openNativeConversation(peerId, fallbackKind = "direct", fallbackRoomId = 0)
        lifecycleScope.launch {
            repeat(50) {
                val detail = appState.activeConversation
                if (detail?.peerId == peerId && !appState.isLoadingConversation) {
                    handleAppAction(if (video) AloNativeAppAction.StartVideoCall else AloNativeAppAction.StartVoiceCall)
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
            appState = appState.copy(notice = "Не удалось начать звонок. Проверьте соединение.")
        }
    }

    /** A finished video-chat recording goes to «Избранное» as an audio file, as on iOS. */
    private fun saveCallRecording(file: File, seconds: Int, title: String) {
        val data = appState.bootstrap ?: return
        val me = data.me
        lifecycleScope.launch {
            try {
                val conversation = buildConversationDetailFromBootstrap(data, me.id)
                    ?: apiClient.loadConversation("direct", me.id)
                val safeTitle = title.replace("/", "-").trim()
                val draft = AloChatDraftAttachment(
                    localUri = android.net.Uri.fromFile(file).toString(),
                    attachmentType = "audio",
                    mimeType = "audio/mp4",
                    name = "Запись видеочата $safeTitle.m4a",
                    durationSec = seconds
                )
                val body = "Запись видеочата «$safeTitle» · ${seconds / 60}:${String.format(java.util.Locale.US, "%02d", seconds % 60)}"
                sendMessageDraftOptimistically(
                    body = body, conversation = conversation, drafts = listOf(draft),
                    selfDestructSeconds = 0, silent = true, preserveComposerState = true
                )
                appState = appState.copy(notice = "Запись видеочата сохранена в «Избранное»")
            } catch (_: Exception) {
                appState = appState.copy(errorMessage = "Не удалось сохранить запись видеочата.")
            }
        }
    }

    private fun loadFeedAds() {
        if (designPreview != null) return
        lifecycleScope.launch {
            runCatching { apiClient.loadFeedAds() }.onSuccess { (ads, every) ->
                appState = appState.copy(feedAds = ads, feedAdEvery = every)
            }
        }
    }

    private fun deleteNativePost(postId: Int) {
        val bootstrap = appState.bootstrap ?: return
        val currentUserId = bootstrap.me.id
        val post = findPostById(bootstrap, postId)
            ?: appState.viewedProfile?.posts?.firstOrNull { it.id == postId }
            ?: return

        if (post.creatorId != currentUserId && post.author.id != currentUserId) {
            appState = appState.copy(errorMessage = "Можно удалить только свой пост.")
            return
        }

        lifecycleScope.launch {
            try {
                val deletedPostId = apiClient.deletePost(postId)
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = removePost(data.feedPopular, deletedPostId),
                        feedSubscriptions = removePost(data.feedSubscriptions, deletedPostId),
                        profilePosts = removePost(data.profilePosts, deletedPostId)
                    )
                }
                appState = appState.copy(likedPosts = removePost(appState.likedPosts, deletedPostId))
                appState.viewedProfile?.let { viewed ->
                    appState = appState.copy(
                        viewedProfile = viewed.copy(posts = removePost(viewed.posts, deletedPostId))
                    )
                }
                if (appState.commentsPostId == deletedPostId) {
                    appState = appState.copy(
                        commentsPostId = 0,
                        commentsPost = null,
                        comments = emptyList(),
                        commentsComposerText = "",
                        commentsEditingCommentId = null
                    )
                }
                appState = appState.copy(errorMessage = null)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось удалить пост.")
            }
        }
    }

    /** Posts of [incoming] that [existing] does not have yet, after it. */
    private fun appendNewPosts(existing: List<AloPost>, incoming: List<AloPost>): List<AloPost> {
        val known = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { known.add(it.id) }
    }

    /** An edited post in a list: the server's text and time, with why the feed shows it kept. */
    private fun replaceEditedPost(posts: List<AloPost>, updated: AloPost): List<AloPost> =
        posts.map { if (it.id == updated.id) updated.copy(feedReason = it.feedReason) else it }

    /** Asks for the page after the ones a list already has; the end of a list calls this as the person scrolls to it. */
    private fun loadMorePosts(list: AloPagedList) {
        if (designPreview != null) return
        val bootstrap = appState.bootstrap ?: return
        if (list in appState.pagedListsEnded || list in appState.pagedListsLoading) return
        if (list == AloPagedList.ViewedProfilePosts && appState.viewedProfile == null) return
        appState = appState.copy(pagedListsLoading = appState.pagedListsLoading + list)
        val ownerId = bootstrap.me.id
        lifecycleScope.launch {
            var ended = false
            try {
                when (list) {
                    AloPagedList.FeedPopular, AloPagedList.FeedSubscriptions -> {
                        val subscriptions = list == AloPagedList.FeedSubscriptions
                        val loaded = appState.bootstrap?.let { if (subscriptions) it.feedSubscriptions else it.feedPopular }.orEmpty()
                        val page = apiClient.loadFeedPage(subscriptions, loaded.size)
                        if (appState.bootstrap?.me?.id != ownerId) return@launch
                        updateBootstrap { data ->
                            if (subscriptions) data.copy(feedSubscriptions = appendNewPosts(data.feedSubscriptions, page.items))
                            else data.copy(feedPopular = appendNewPosts(data.feedPopular, page.items))
                        }
                        ended = !page.hasMore || page.items.isEmpty()
                    }
                    AloPagedList.ProfilePosts -> {
                        val loaded = appState.bootstrap?.profilePosts.orEmpty()
                        val page = apiClient.loadUserPostsPage(ownerId, loaded.size)
                        if (appState.bootstrap?.me?.id != ownerId) return@launch
                        updateBootstrap { data -> data.copy(profilePosts = appendNewPosts(data.profilePosts, page.items)) }
                        ended = !page.hasMore || page.items.isEmpty()
                    }
                    AloPagedList.Notifications -> {
                        val loaded = appState.bootstrap?.notifications.orEmpty()
                        val page = apiClient.loadNotificationsPage(loaded.size)
                        if (appState.bootstrap?.me?.id != ownerId) return@launch
                        updateBootstrap { data ->
                            val known = data.notifications.mapTo(HashSet()) { it.id }
                            data.copy(notifications = data.notifications + page.items.filter { known.add(it.id) })
                        }
                        ended = !page.hasMore || page.items.isEmpty()
                    }
                    AloPagedList.LikedPosts -> {
                        val page = apiClient.loadLikedPostsPage(appState.likedPosts.size)
                        if (appState.bootstrap?.me?.id != ownerId) return@launch
                        appState = appState.copy(likedPosts = appendNewPosts(appState.likedPosts, page.items))
                        ended = !page.hasMore || page.items.isEmpty()
                    }
                    AloPagedList.ViewedProfilePosts -> {
                        val viewed = appState.viewedProfile ?: return@launch
                        val page = if (viewed.kind == "channel") {
                            apiClient.loadChannelPostsPage(viewed.id, viewed.posts.size)
                        } else {
                            apiClient.loadUserPostsPage(viewed.id, viewed.posts.size)
                        }
                        appState.viewedProfile?.takeIf { it.id == viewed.id && it.kind == viewed.kind }?.let { current ->
                            appState = appState.copy(viewedProfile = current.copy(posts = appendNewPosts(current.posts, page.items)))
                        }
                        ended = !page.hasMore || page.items.isEmpty()
                    }
                }
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else ended = true
            } catch (_error: Exception) {
                // A failing list stops asking until the next refresh, so the end of the screen does not retry in a loop.
                ended = true
            } finally {
                appState = appState.copy(
                    pagedListsLoading = appState.pagedListsLoading - list,
                    pagedListsEnded = if (ended) appState.pagedListsEnded + list else appState.pagedListsEnded
                )
            }
        }
    }

    private fun editNativePost(postId: Int, body: String, onResult: (String?) -> Unit) {
        val bootstrap = appState.bootstrap ?: return onResult("Нет данных аккаунта.")
        val post = findPostById(bootstrap, postId)
            ?: appState.viewedProfile?.posts?.firstOrNull { it.id == postId }
            ?: appState.commentsPost?.takeIf { it.id == postId }
            ?: return onResult("Пост не найден.")
        if (post.creatorId != bootstrap.me.id && post.author.id != bootstrap.me.id) {
            return onResult("Можно изменить только свой пост.")
        }
        lifecycleScope.launch {
            try {
                val updated = apiClient.editPost(postId, body.trim()) ?: return@launch onResult("Не удалось изменить пост.")
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = replaceEditedPost(data.feedPopular, updated),
                        feedSubscriptions = replaceEditedPost(data.feedSubscriptions, updated),
                        profilePosts = replaceEditedPost(data.profilePosts, updated)
                    )
                }
                appState = appState.copy(
                    likedPosts = replaceEditedPost(appState.likedPosts, updated),
                    viewedProfile = appState.viewedProfile?.let { it.copy(posts = replaceEditedPost(it.posts, updated)) },
                    commentsPost = appState.commentsPost?.takeIf { it.id == postId }?.let { replaceEditedPost(listOf(it), updated).first() }
                        ?: appState.commentsPost
                )
                onResult(null)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
                onResult(error.message)
            } catch (_error: Exception) {
                onResult("Не удалось изменить пост.")
            }
        }
    }

    private fun updateProfileInfo(name: String, username: String, bio: String, onResult: (String?) -> Unit) {
        appState.bootstrap ?: return onResult("Нет данных аккаунта.")
        lifecycleScope.launch {
            try {
                val updated = apiClient.updateProfileInfo(name.trim(), username.trim().removePrefix("@"), bio.trim())
                applyUpdatedCurrentUser(updated)
                onResult(null)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
                onResult(error.message)
            } catch (_error: Exception) {
                onResult("Не удалось сохранить профиль.")
            }
        }
    }

    private var searchRemoteJob: kotlinx.coroutines.Job? = null

    /** Looks the typed text up on the server: people, communities and posts. The newest query wins. */
    private fun searchRemote(query: String) {
        if (designPreview != null) return
        val text = query.trim()
        searchRemoteJob?.cancel()
        if (text.length < 2) {
            appState = appState.copy(
                searchRemoteQuery = "", searchRemoteUsers = emptyList(), searchRemoteChannels = emptyList(),
                searchRemotePosts = emptyList(), searchRemotePostsHasMore = false, isSearchingRemote = false
            )
            return
        }
        appState = appState.copy(isSearchingRemote = true)
        searchRemoteJob = lifecycleScope.launch {
            delay(250)
            try {
                val users = runCatching { apiClient.searchUsers(text) }.getOrDefault(emptyList())
                val channels = runCatching { apiClient.searchChannelsPage(text, 0).items }.getOrDefault(emptyList())
                val posts = runCatching { apiClient.searchPostsPage(text, 0) }.getOrNull()
                appState = appState.copy(
                    searchRemoteQuery = text,
                    searchRemoteUsers = users,
                    searchRemoteChannels = channels,
                    searchRemotePosts = posts?.items.orEmpty(),
                    searchRemotePostsHasMore = posts?.hasMore == true,
                    isSearchingRemote = false
                )
            } catch (_cancelled: kotlinx.coroutines.CancellationException) {
                throw _cancelled
            } catch (_error: Exception) {
                appState = appState.copy(isSearchingRemote = false)
            }
        }
    }

    private fun loadMoreSearchPosts() {
        if (designPreview != null) return
        val query = appState.searchRemoteQuery
        if (query.isBlank() || !appState.searchRemotePostsHasMore || appState.isSearchingRemote) return
        appState = appState.copy(isSearchingRemote = true)
        lifecycleScope.launch {
            try {
                val page = apiClient.searchPostsPage(query, appState.searchRemotePosts.size)
                if (appState.searchRemoteQuery == query) {
                    appState = appState.copy(
                        searchRemotePosts = appendNewPosts(appState.searchRemotePosts, page.items),
                        searchRemotePostsHasMore = page.hasMore && page.items.isNotEmpty()
                    )
                }
            } catch (_error: Exception) {
                // The next scroll to the end asks again.
            } finally {
                appState = appState.copy(isSearchingRemote = false)
            }
        }
    }

    private fun updateNotificationSettings(settings: AloNotificationSettings) {
        val bootstrap = appState.bootstrap ?: return
        val previous = bootstrap.notificationSettings
        appState = appState.copy(
            bootstrap = bootstrap.copy(notificationSettings = settings),
            errorMessage = null
        )

        lifecycleScope.launch {
            AloAccountScopedSave.run(
                accountId = bootstrap.me.id,
                activeAccountId = { appState.bootstrap?.me?.id },
                previous = previous,
                save = { apiClient.updateNotificationSettings(settings) },
                onSaved = { saved -> updateBootstrap { data -> data.copy(notificationSettings = saved) } },
                onRolledBack = { old, message ->
                    updateBootstrap { data -> data.copy(notificationSettings = old) }
                    appState = appState.copy(errorMessage = message)
                },
                onSessionExpired = ::handleSessionExpired,
                failureMessage = "Не удалось сохранить настройки уведомлений."
            )
        }
    }

    private fun loadActiveChatMuteState() {
        val conversation = appState.activeConversation ?: return
        val peerId = conversation.peerId
        val roomId = conversation.roomId
        lifecycleScope.launch {
            try {
                val muted = apiClient.chatMuteState(conversation)
                val current = appState.activeConversation
                if (current?.peerId == peerId && current.roomId == roomId) {
                    appState = appState.copy(activeChatMuted = muted)
                }
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
            } catch (_error: IOException) {
                // No connection (or the server is down): the profile still opens, with the last known state.
            }
        }
    }

    private fun toggleActiveChatMuted() {
        val conversation = appState.activeConversation ?: return
        val previous = appState.activeChatMuted
        val next = !previous
        val peerId = conversation.peerId
        val roomId = conversation.roomId
        appState = appState.copy(activeChatMuted = next, errorMessage = null)
        lifecycleScope.launch {
            try {
                val saved = apiClient.setChatMuted(next, conversation)
                val current = appState.activeConversation
                if (current?.peerId == peerId && current.roomId == roomId) {
                    appState = appState.copy(activeChatMuted = saved)
                }
            } catch (error: AloApiException) {
                val current = appState.activeConversation
                if (current?.peerId == peerId && current.roomId == roomId) {
                    appState = appState.copy(activeChatMuted = previous)
                }
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                val current = appState.activeConversation
                if (current?.peerId == peerId && current.roomId == roomId) {
                    appState = appState.copy(
                        activeChatMuted = previous,
                        errorMessage = "Не удалось изменить уведомления чата."
                    )
                }
            }
        }
    }

    private fun updatePrivacySettings(settings: AloPrivacySettings) {
        val bootstrap = appState.bootstrap ?: return
        val previous = bootstrap.privacySettings
        appState = appState.copy(
            bootstrap = bootstrap.copy(privacySettings = settings),
            errorMessage = null
        )

        lifecycleScope.launch {
            AloAccountScopedSave.run(
                accountId = bootstrap.me.id,
                activeAccountId = { appState.bootstrap?.me?.id },
                previous = previous,
                save = { apiClient.updatePrivacySettings(settings) },
                onSaved = { saved -> updateBootstrap { data -> data.copy(privacySettings = saved) } },
                onRolledBack = { old, message ->
                    updateBootstrap { data -> data.copy(privacySettings = old) }
                    appState = appState.copy(errorMessage = message)
                },
                onSessionExpired = ::handleSessionExpired,
                failureMessage = "Не удалось сохранить настройки приватности."
            )
        }
    }

    private fun loadSecuritySessions() {
        if (designPreview != null) return
        if (appState.isSecuritySessionsLoading) return
        appState = appState.copy(
            isSecuritySessionsLoading = true,
            securityNotice = ""
        )
        lifecycleScope.launch {
            try {
                val sessions = apiClient.loadSecuritySessions()
                val events = runCatching { apiClient.loadSecurityEvents() }.getOrDefault(appState.securityEvents)
                appState = appState.copy(
                    securitySessions = sessions,
                    securityEvents = events,
                    isSecuritySessionsLoading = false,
                    securityNotice = ""
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSecuritySessionsLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSecuritySessionsLoading = false,
                    securityNotice = "Не удалось загрузить активные устройства."
                )
            }
        }
    }

    private fun revokeSecuritySession(sessionId: Int) {
        val session = appState.securitySessions.firstOrNull { it.id == sessionId } ?: return
        if (session.current || !session.canRevoke || appState.isSecuritySessionsLoading) return
        appState = appState.copy(isSecuritySessionsLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                apiClient.revokeSecuritySession(sessionId)
                val sessions = apiClient.loadSecuritySessions()
                val events = runCatching { apiClient.loadSecurityEvents() }.getOrDefault(appState.securityEvents)
                appState = appState.copy(
                    securitySessions = sessions,
                    securityEvents = events,
                    isSecuritySessionsLoading = false,
                    securityNotice = "Сеанс завершён."
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSecuritySessionsLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSecuritySessionsLoading = false,
                    securityNotice = "Не удалось завершить сеанс."
                )
            }
        }
    }

    private fun revokeOtherSecuritySessions() {
        if (appState.isSecuritySessionsLoading) return
        appState = appState.copy(isSecuritySessionsLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                val revoked = apiClient.revokeOtherSecuritySessions()
                val sessions = apiClient.loadSecuritySessions()
                val events = runCatching { apiClient.loadSecurityEvents() }.getOrDefault(appState.securityEvents)
                appState = appState.copy(
                    securitySessions = sessions,
                    securityEvents = events,
                    isSecuritySessionsLoading = false,
                    securityNotice = if (revoked > 0) {
                        "Завершено других сеансов: $revoked."
                    } else {
                        "Других активных сеансов нет."
                    }
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSecuritySessionsLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSecuritySessionsLoading = false,
                    securityNotice = "Не удалось завершить другие сеансы."
                )
            }
        }
    }

    private fun changePassword(currentPassword: String, newPassword: String) {
        if (appState.isSecuritySessionsLoading) return
        appState = appState.copy(isSecuritySessionsLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                val revoked = apiClient.changePassword(currentPassword, newPassword)
                val sessions = apiClient.loadSecuritySessions()
                val events = runCatching { apiClient.loadSecurityEvents() }.getOrDefault(appState.securityEvents)
                appState = appState.copy(
                    securitySessions = sessions,
                    securityEvents = events,
                    isSecuritySessionsLoading = false,
                    securityNotice = if (revoked > 0) {
                        "Пароль изменён. Завершено других сеансов: $revoked."
                    } else {
                        "Пароль изменён."
                    }
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSecuritySessionsLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSecuritySessionsLoading = false,
                    securityNotice = "Не удалось изменить пароль."
                )
            }
        }
    }

    /** Unread messages of the other accounts saved here, asked with each one's session (GET /api/auth/session-summary). */
    private fun refreshAccountSummaries() {
        val me = appState.bootstrap?.me?.id ?: return
        val others = secureAccountStore.accounts().filter { it.userId != me }
        if (others.isEmpty()) {
            if (appState.accountUnread.isNotEmpty()) appState = appState.copy(accountUnread = emptyMap())
            return
        }
        lifecycleScope.launch {
            val unread = mutableMapOf<Int, Int>()
            val ended = mutableListOf<Int>()
            for (account in others) {
                val cookie = secureAccountStore.sessionFor(account.userId)
                if (cookie == null) { ended += account.userId; continue }
                // Null: that session is over (signed out elsewhere, expired) and it leaves the switcher; offline keeps it.
                val summary = runCatching { apiClient.loadAccountSummary(cookie) }
                summary.onSuccess { result -> if (result == null) ended += account.userId else unread[account.userId] = result.unreadMessages }
            }
            ended.forEach(secureAccountStore::removeAccount)
            if (appState.bootstrap?.me?.id == me) {
                appState = appState.copy(accountUnread = unread, savedAccounts = secureAccountStore.accounts())
            }
        }
    }

    private fun loadSecurityAccounts() {
        appState = appState.copy(
            savedAccounts = secureAccountStore.accounts(),
            isAccountSecurityLoading = false
        )
    }

    /** Saves this session on the device, then opens sign-in for another account; both stay switchable. */
    private fun addAnotherAccount() {
        val me = appState.bootstrap?.me ?: return
        if (designPreview != null || appState.isAccountSecurityLoading) return
        if (secureAccountStore.accounts().count { it.userId != me.id } + 1 >= AloAccountSwitcher.MAX_ACCOUNTS) {
            val text = "На одном устройстве можно держать до ${AloAccountSwitcher.MAX_ACCOUNTS} аккаунтов. Выйдите из одного, чтобы добавить новый."
            appState = appState.copy(notice = text, securityNotice = text)
            return
        }
        lifecycleScope.launch {
            runCatching { secureAccountStore.saveCurrentSession(me, currentCookieHeader()) }
                .onFailure {
                    appState = appState.copy(securityNotice = it.message?.takeIf(String::isNotBlank) ?: "Не удалось сохранить текущий сеанс.")
                    return@launch
                }
            stopActiveRecordingForNavigation()
            apiClient.clearCookies()
            appState = AloNativeAppUiState(themeMode = appState.themeMode, savedAccounts = secureAccountStore.accounts())
            showAuthFlow("Войдите в другой аккаунт. Текущий сохранён на этом устройстве.")
        }
    }

    private fun switchSecurityAccount(userId: Int) {
        val currentUserId = appState.bootstrap?.me?.id ?: 0
        if (userId <= 0 || appState.isAccountSecurityLoading) return
        if (userId == currentUserId) {
            secureAccountStore.markActive(userId)
            loadSecurityAccounts()
            return
        }
        val targetCookie = secureAccountStore.sessionFor(userId).orEmpty()
        if (targetCookie.isBlank()) {
            appState = appState.copy(securityNotice = "Сессия этого аккаунта больше не доступна на устройстве.")
            return
        }
        val previousCookie = currentCookieHeader()
        // From the sign-in screen («Войти в сохранённый аккаунт») a failure returns there, not to an empty app.
        val fromSignIn = rootScreen == RootScreen.Auth
        appState = appState.copy(isAccountSecurityLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                stopActiveRecordingForNavigation()
                stopChatRealtime()
                callCoordinator.stop()
                apiClient.clearCookies()
                restoreCookieHeader(targetCookie)
                if (!apiClient.hasSession()) {
                    secureAccountStore.removeAccount(userId)
                    throw IllegalStateException("Сессия этого аккаунта завершена. Войдите в него снова.")
                }
                secureAccountStore.markActive(userId)
                appState = AloNativeAppUiState(
                    themeMode = appState.themeMode,
                    savedAccounts = secureAccountStore.accounts(),
                    isAccountSecurityLoading = true,
                    isRefreshing = true
                )
                rootScreen = RootScreen.Loading
                loadNativeApp(isInitial = true)
            } catch (error: Exception) {
                apiClient.clearCookies()
                if (previousCookie.isNotBlank()) restoreCookieHeader(previousCookie)
                appState = appState.copy(
                    savedAccounts = secureAccountStore.accounts(),
                    isAccountSecurityLoading = false,
                    securityNotice = securityErrorMessage(error, "Не удалось переключить аккаунт.")
                )
                if (fromSignIn) {
                    authState = authState.copy(
                        savedAccounts = secureAccountStore.accounts(),
                        errorMessage = securityErrorMessage(error, "Не удалось войти в сохранённый аккаунт.")
                    )
                    rootScreen = RootScreen.Auth
                } else {
                    rootScreen = RootScreen.App
                }
            }
        }
    }

    private fun loadPasskeys() {
        if (designPreview != null) return
        if (appState.isPasskeyLoading) return
        appState = appState.copy(isPasskeyLoading = true)
        lifecycleScope.launch {
            try {
                appState = appState.copy(
                    passkeys = apiClient.loadPasskeys(),
                    isPasskeyLoading = false
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isPasskeyLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (error: Exception) {
                appState = appState.copy(
                    isPasskeyLoading = false,
                    securityNotice = securityErrorMessage(error, "Не удалось загрузить ключи доступа.")
                )
            }
        }
    }

    private fun registerPasskey() {
        if (appState.isPasskeyLoading) return
        appState = appState.copy(isPasskeyLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                val challenge = apiClient.requestPasskeyRegistrationOptions()
                val credential = passkeyClient.create(this@MainActivity, challenge.optionsJson)
                apiClient.verifyPasskeyRegistration(challenge, credential)
                appState = appState.copy(
                    passkeys = apiClient.loadPasskeys(),
                    isPasskeyLoading = false,
                    securityNotice = "Ключ доступа добавлен."
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isPasskeyLoading = false,
                        securityNotice = error.message
                    )
                }
            } catch (error: Exception) {
                appState = appState.copy(
                    isPasskeyLoading = false,
                    securityNotice = securityErrorMessage(
                        error,
                        "Ключи доступа недоступны на этом устройстве."
                    )
                )
            }
        }
    }

    private fun authenticateWithPasskey() {
        if (authState.isSubmitting) return
        authState = authState.copy(isSubmitting = true, errorMessage = null, noticeMessage = null)
        lifecycleScope.launch {
            try {
                val challenge = apiClient.requestPasskeyAuthenticationOptions()
                val credential = passkeyClient.authenticate(this@MainActivity, challenge.optionsJson)
                apiClient.verifyPasskeyAuthentication(challenge, credential)
                openNativeApp()
            } catch (error: AloApiException) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = error.message
                )
            } catch (error: Exception) {
                authState = authState.copy(
                    isSubmitting = false,
                    errorMessage = securityErrorMessage(
                        error,
                        "Ключи доступа недоступны на этом устройстве."
                    )
                )
            }
        }
    }

    private fun startQrLogin() {
        qrLoginPollingJob?.cancel()
        authState = AuthUiState(
            step = AuthStep.QrLogin,
            isQrLoginLoading = true,
            savedAccounts = authState.savedAccounts
        )
        lifecycleScope.launch {
            try {
                val session = apiClient.startQrLogin()
                authState = authState.copy(
                    qrLoginSession = session,
                    isQrLoginLoading = false,
                    errorMessage = null
                )
                pollQrLoginUntilComplete(session)
            } catch (error: AloApiException) {
                authState = authState.copy(
                    isQrLoginLoading = false,
                    errorMessage = error.message
                )
            } catch (error: Exception) {
                authState = authState.copy(
                    isQrLoginLoading = false,
                    errorMessage = securityErrorMessage(error, "Не удалось создать QR-код входа.")
                )
            }
        }
    }

    private fun pollQrLoginUntilComplete(initial: AloQrLoginSession) {
        qrLoginPollingJob?.cancel()
        qrLoginPollingJob = lifecycleScope.launch {
            var session = initial
            while (isActive && rootScreen == RootScreen.Auth && authState.step == AuthStep.QrLogin) {
                delay(QR_LOGIN_POLL_INTERVAL_MS)
                try {
                    session = apiClient.getQrLoginStatus(session.id, session.secret)
                    authState = authState.copy(qrLoginSession = session)
                    if (session.isExpired) {
                        authState = authState.copy(
                            isQrLoginLoading = false,
                            errorMessage = "Срок действия QR-кода истёк. Создайте новый."
                        )
                        break
                    }
                    if (session.isApproved) {
                        apiClient.completeQrLogin(session.id, session.secret)
                        openNativeApp()
                        break
                    }
                } catch (error: AloApiException) {
                    if (error.status == 401 || error.status == 404) {
                        authState = authState.copy(
                            isQrLoginLoading = false,
                            errorMessage = error.message
                        )
                        break
                    }
                    // The code is still valid: keep polling transient network failures.
                    authState = authState.copy(
                        isQrLoginLoading = false,
                        noticeMessage = "Ожидаем подтверждение. Проверим соединение ещё раз."
                    )
                } catch (_: Exception) {
                    authState = authState.copy(
                        isQrLoginLoading = false,
                        noticeMessage = "Ожидаем подтверждение. Проверим соединение ещё раз."
                    )
                }
            }
        }
    }

    /**
     * New device: the code a signed-in device shows («Показать QR для входа»). Claim it, wait for the
     * owner to confirm there, then finish the sign-in here, as iOS's AloDeviceLinkScanFlow.
     */
    private fun signInWithDeviceLink(rawPayload: String) {
        val payload = AloQrLoginPayload.parse(rawPayload)
        if (payload == null || !payload.isDeviceLink) {
            authState = authState.copy(
                isDeviceLinkScannerOpen = false,
                errorMessage = if (payload == null) "Это не QR-код входа Alo."
                else "Этот QR-код создан для входа на другом устройстве. Отсканируйте его в Alo, где вы уже вошли."
            )
            return
        }
        qrLoginPollingJob?.cancel()
        authState = AuthUiState(step = AuthStep.DeviceLinkWaiting, noticeMessage = "Проверяем код…", savedAccounts = authState.savedAccounts)
        qrLoginPollingJob = lifecycleScope.launch {
            try {
                apiClient.claimDeviceLink(payload.id, payload.secret)
                authState = authState.copy(noticeMessage = null)
                while (isActive && authState.step == AuthStep.DeviceLinkWaiting) {
                    delay(QR_LOGIN_POLL_INTERVAL_MS)
                    val session = apiClient.getQrLoginStatus(payload.id, payload.secret)
                    if (session.isExpired) throw IllegalStateException("Срок действия QR-кода истёк. Покажите новый код.")
                    if (session.isApproved) {
                        authState = authState.copy(noticeMessage = "Завершаем вход…")
                        apiClient.completeQrLogin(payload.id, payload.secret)
                        openNativeApp()
                        return@launch
                    }
                }
            } catch (error: AloApiException) {
                authState = authState.copy(noticeMessage = null, errorMessage = error.message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                authState = authState.copy(noticeMessage = null, errorMessage = securityErrorMessage(error, "Не удалось войти по QR-коду."))
            }
        }
    }

    private fun openQrLoginScanner() {
        requestAppPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
            appState = if (granted) {
                appState.copy(isQrScannerOpen = true, securityNotice = "")
            } else {
                appState.copy(securityNotice = "Для сканирования QR-кода нужен доступ к камере.")
            }
        }
    }

    /**
     * «Подключить устройство», as in Telegram: a scanned code (or the phone camera's link) only opens a question —
     * the server says which device asks («Войти на устройстве Chrome · macOS?») — and the button approves it.
     */
    private fun approveQrLoginPayload(rawPayload: String) {
        val payload = AloQrLoginPayload.parse(rawPayload)
        val problem = when {
            payload == null -> "Это не QR-код входа Alo. Откройте на компьютере страницу входа Alo и наведите камеру на код."
            payload.isDeviceLink -> "Этот код показывает устройство, где уже выполнен вход. Отсканируйте его на новом устройстве с экрана входа."
            !payload.isFromTrustedHost -> "Этот QR-код с другого сайта (${payload.webHost}). Входите только на сайте Alo."
            else -> null
        }
        if (payload == null || problem != null) {
            appState = appState.copy(isQrScannerOpen = false, securityNotice = problem.orEmpty())
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        if (appState.isAccountSecurityLoading) return
        appState = appState.copy(isQrScannerOpen = false, isAccountSecurityLoading = true, securityNotice = "", qrLoginConfirmation = null)
        lifecycleScope.launch {
            try {
                val device = try {
                    apiClient.inspectQrLogin(payload.id, payload.secret)
                } catch (error: AloApiException) {
                    // A server without `inspect` still lets the owner confirm, only without the device's name.
                    if (error.status == 404 && error.code.isNullOrBlank()) org.json.JSONObject() else throw error
                }
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    qrLoginConfirmation = AloQrLoginConfirmation(
                        requestId = payload.id,
                        secret = payload.secret,
                        deviceLabel = device.optString("label").trim(),
                        ip = device.optString("ip").trim(),
                        deviceType = device.optString("deviceType")
                    )
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failQrLogin(error)
            }
        }
    }

    private fun confirmQrLogin() {
        val confirmation = appState.qrLoginConfirmation ?: return
        if (confirmation.isApproving) return
        appState = appState.copy(qrLoginConfirmation = confirmation.copy(isApproving = true))
        lifecycleScope.launch {
            try {
                apiClient.approveQrLogin(confirmation.requestId, confirmation.secret)
                val done = "Вход подтверждён: ${confirmation.deviceLabel.ifBlank { "устройство" }} откроет ваш аккаунт."
                appState = appState.copy(qrLoginConfirmation = null, securityNotice = done)
                Toast.makeText(this@MainActivity, done, Toast.LENGTH_LONG).show()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failQrLogin(error)
            }
        }
    }

    private fun failQrLogin(error: Exception) {
        val message = when {
            error is AloApiException && error.status == 401 -> "Текущий сеанс завершён. Войдите снова."
            error is AloApiException && error.code == "QR_LOGIN_NOT_PENDING" ->
                "Этот QR-код уже использован или истёк. Обновите код на устройстве, где выполняется вход."
            error is AloApiException -> error.message
            else -> securityErrorMessage(error, "Не удалось подтвердить вход по QR-коду.")
        }
        appState = appState.copy(isAccountSecurityLoading = false, qrLoginConfirmation = null, securityNotice = message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (error is AloApiException && error.status == 401) handleSessionExpired()
    }

    private fun exportAccountData(currentPassword: String) {
        if (currentPassword.isBlank()) {
            appState = appState.copy(securityNotice = "Введите текущий пароль для экспорта.")
            return
        }
        if (appState.isAccountSecurityLoading) return
        appState = appState.copy(isAccountSecurityLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                val export = apiClient.requestAccountExport(currentPassword)
                pendingAccountExportPayload?.file?.delete()
                pendingAccountExportPayload = export
                appState = appState.copy(
                    accountExport = export.export,
                    isAccountSecurityLoading = false,
                    securityNotice = "Выберите место для сохранения экспорта."
                )
                accountExportDocumentLauncher.launch(export.export.fileName)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    securityNotice = if (error.status == 401) {
                        "Текущий сеанс завершён. Войдите снова."
                    } else {
                        error.message
                    }
                )
            } catch (error: Exception) {
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    securityNotice = securityErrorMessage(error, "Не удалось подготовить экспорт данных.")
                )
            }
        }
    }

    private fun deleteCurrentAccount(currentPassword: String) {
        if (currentPassword.isBlank() || appState.isAccountSecurityLoading) return
        val userId = appState.bootstrap?.me?.id ?: return
        appState = appState.copy(isAccountSecurityLoading = true, securityNotice = "")
        lifecycleScope.launch {
            try {
                apiClient.deleteAccount(currentPassword)
                secureAccountStore.removeAccount(userId)
                apiClient.clearCookies()
                stopChatRealtime()
                callCoordinator.stop()
                clearPendingChatOutboxForLogout()
                val savedThemeMode = appState.themeMode
                messageDraftStore.clearActive(appState)
                appState = AloNativeAppUiState(themeMode = savedThemeMode)
                authState = AuthUiState(noticeMessage = "Аккаунт удалён.")
                rootScreen = RootScreen.Auth
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired()
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    securityNotice = if (error.status == 401) {
                        "Текущий сеанс завершён. Войдите снова."
                    } else {
                        error.message
                    }
                )
            } catch (error: Exception) {
                appState = appState.copy(
                    isAccountSecurityLoading = false,
                    securityNotice = securityErrorMessage(error, "Не удалось удалить аккаунт.")
                )
            }
        }
    }

    private fun securityErrorMessage(error: Exception, fallback: String): String {
        val message = error.message.orEmpty().trim()
        return when {
            message.contains("NoCredential", ignoreCase = true) -> "На этом устройстве нет подходящего ключа доступа."
            message.contains("canceled", ignoreCase = true) ||
                message.contains("cancelled", ignoreCase = true) -> "Действие отменено."
            message.isNotBlank() -> message
            else -> fallback
        }
    }

    private fun updateChannelSettings(action: AloNativeAppAction.UpdateChannelSettings) {
        val previous = appState.viewedProfile
        if (
            previous == null ||
            previous.kind != "channel" ||
            previous.id != action.channelId
        ) {
            return
        }
        appState = appState.copy(
            viewedProfile = previous.copy(
                commentsEnabled = action.commentsEnabled,
                signMessages = action.signMessages,
                showAuthorProfiles = action.signMessages && action.showAuthorProfiles
            ),
            errorMessage = null
        )
        lifecycleScope.launch {
            try {
                val updated = apiClient.updateChannelSettings(
                    channelId = action.channelId,
                    commentsEnabled = action.commentsEnabled,
                    signMessages = action.signMessages,
                    showAuthorProfiles = action.signMessages && action.showAuthorProfiles
                )
                appState = appState.copy(viewedProfile = updated)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        viewedProfile = previous,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    viewedProfile = previous,
                    errorMessage = "Не удалось сохранить настройки канала."
                )
            }
        }
    }

    private fun openPostComments(postId: Int) {
        val bootstrap = appState.bootstrap ?: return
        val post = findPostById(bootstrap, postId)
            ?: appState.viewedProfile?.posts?.firstOrNull { it.id == postId }
            ?: return

        openPostComments(post)
    }

    private fun openPostComments(post: AloPost) {
        if (post.author.isChannel) {
            lifecycleScope.launch {
                try {
                    val discussion = apiClient.openPostDiscussion(post.id)
                    // Back from the comments goes to the channel they were opened from (Telegram); its row under the
                    // post loses the dot at once.
                    val from = appState.activeConversation
                    val returnPeerId = if (from != null && from.roomId != discussion.roomId) appState.activeConversationPeerId else 0
                    appState = appState.copy(
                        commentsPostId = 0,
                        commentsPost = null,
                        comments = emptyList(),
                        isCommentsLoading = false,
                        errorMessage = null,
                        commentsThread = AloCommentsThread(
                            roomId = discussion.roomId,
                            topicId = discussion.topicId,
                            postId = post.id,
                            post = discussion.post ?: post,
                            count = discussion.commentCount
                        ),
                        commentsReturnPeerId = returnPeerId
                    )
                    openNativeConversation(
                        peerId = discussion.peerId,
                        fallbackKind = "group",
                        fallbackRoomId = discussion.roomId,
                        requestedTopicId = discussion.topicId
                    )
                } catch (error: AloApiException) {
                    if (error.code == "discussion_not_linked") {
                        openLegacyPostComments(post)
                    } else {
                        appState = appState.copy(errorMessage = error.message)
                    }
                } catch (_error: Exception) {
                    appState = appState.copy(errorMessage = "Не удалось открыть обсуждение публикации.")
                }
            }
            return
        }
        openLegacyPostComments(post)
    }

    private fun openLegacyPostComments(post: AloPost, focusCommentId: Int = 0) {
        val postId = post.id
        val owner = appState.bootstrap?.me?.id
        val cookie = currentCookieHeader()
        appState = appState.copy(
            commentsPostId = postId,
            commentsPost = post,
            commentsFocusId = focusCommentId,
            comments = emptyList(),
            commentsComposerText = "",
            commentsEditingCommentId = null,
            isCommentsLoading = true,
            errorMessage = null
        )
        if (designPreview != null) {
            appState = appState.copy(comments = AloDesignFixture.comments(), isCommentsLoading = false)
            return
        }

        lifecycleScope.launch {
            try {
                val comments = apiClient.loadComments(postId)
                if (appState.bootstrap?.me?.id != owner || appState.commentsPostId != postId || !AloOfflineAccountPolicy.matchesSession(cookie, currentCookieHeader())) return@launch
                appState = appState.copy(
                    comments = comments,
                    isCommentsLoading = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isCommentsLoading = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isCommentsLoading = false,
                    errorMessage = "Не удалось загрузить комментарии."
                )
            }
        }
    }

    private fun submitPostComment(captchaToken: String? = null) {
        val postId = appState.commentsPostId
        val body = appState.commentsComposerText.trim()
        val editingCommentId = appState.commentsEditingCommentId
        if (postId <= 0) return
        if (body.isEmpty()) {
            appState = appState.copy(errorMessage = "Комментарий не должен быть пустым.")
            return
        }
        if (appState.isSubmittingComment) return

        appState = appState.copy(
            isSubmittingComment = true,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                val result = if (editingCommentId != null) {
                    apiClient.updateComment(postId, editingCommentId, body)
                } else {
                    apiClient.createComment(
                        postId,
                        body,
                        replyToCommentId = appState.commentsReplyTo?.id ?: 0,
                        captchaToken = captchaToken
                    )
                }
                val updatedPost = result.post
                val updatedComments = if (result.comment != null) {
                    if (editingCommentId != null) {
                        appState.comments.map { comment ->
                            if (comment.id == result.comment.id) result.comment else comment
                        }
                    } else {
                        // The realtime event may have added it already, before this reply came.
                        aloUpsertComment(appState.comments, result.comment)
                    }
                } else {
                    apiClient.loadComments(postId)
                }

                if (updatedPost != null) {
                    updateBootstrap { data ->
                        data.copy(
                            feedPopular = replacePost(data.feedPopular, updatedPost),
                            feedSubscriptions = replacePost(data.feedSubscriptions, updatedPost),
                            profilePosts = replacePost(data.profilePosts, updatedPost)
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                posts = replacePost(viewed.posts, updatedPost)
                            )
                        )
                    }
                }

                appState = appState.copy(
                    comments = updatedComments,
                    commentsPost = updatedPost ?: appState.commentsPost,
                    commentsComposerText = "",
                    commentsEditingCommentId = null,
                    commentsReplyTo = null,
                    mentionSuggestions = emptyList(),
                    isSubmittingComment = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token -> submitPostComment(token) }) {
                    return@launch
                } else {
                    appState = appState.copy(
                        isSubmittingComment = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSubmittingComment = false,
                    errorMessage = "Не удалось отправить комментарий."
                )
            }
        }
    }

    private fun deletePostComment(commentId: Int) {
        val postId = appState.commentsPostId
        if (postId <= 0 || commentId <= 0 || appState.isSubmittingComment) return

        appState = appState.copy(isSubmittingComment = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val result = apiClient.deleteComment(postId, commentId)
                val updatedPost = result.post
                val updatedComments = appState.comments.filterNot { it.id == result.commentId }

                if (updatedPost != null) {
                    updateBootstrap { data ->
                        data.copy(
                            feedPopular = replacePost(data.feedPopular, updatedPost),
                            feedSubscriptions = replacePost(data.feedSubscriptions, updatedPost),
                            profilePosts = replacePost(data.profilePosts, updatedPost)
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                posts = replacePost(viewed.posts, updatedPost)
                            )
                        )
                    }
                }

                appState = appState.copy(
                    comments = updatedComments,
                    commentsPost = updatedPost ?: appState.commentsPost,
                    commentsComposerText = if (appState.commentsEditingCommentId == commentId) "" else appState.commentsComposerText,
                    commentsEditingCommentId = if (appState.commentsEditingCommentId == commentId) null else appState.commentsEditingCommentId,
                    isSubmittingComment = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSubmittingComment = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSubmittingComment = false,
                    errorMessage = "Не удалось удалить комментарий."
                )
            }
        }
    }

    private fun openViewedProfile(target: AloProfileTarget) {
        val meId = appState.bootstrap?.me?.id ?: 0
        if (target is AloProfileTarget.User && target.userId == meId) {
            appState = appState.copy(
                activeTab = AloAppTab.Profile,
                activeViewedProfileTarget = null,
                viewedProfile = null,
                isViewedProfileLoading = false,
                isChatPeerProfileOpen = false,
                errorMessage = null
            )
            return
        }

        appState = appState.copy(
            activeViewedProfileTarget = target,
            viewedProfile = null,
            isViewedProfileLoading = true,
            isChatPeerProfileOpen = false,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                val profile = when (target) {
                    is AloProfileTarget.User -> apiClient.loadUserProfile(target.userId)
                    is AloProfileTarget.Channel -> apiClient.loadChannelProfile(target.channelId)
                    is AloProfileTarget.Group -> apiClient.loadGroupProfile(target.roomId)
                }
                if (appState.activeViewedProfileTarget != target) return@launch
                appState = appState.copy(
                    viewedProfile = profile,
                    isViewedProfileLoading = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (appState.activeViewedProfileTarget == target) {
                    appState = appState.copy(
                        activeViewedProfileTarget = null,
                        viewedProfile = null,
                        isViewedProfileLoading = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                if (appState.activeViewedProfileTarget == target) {
                    appState = appState.copy(
                        activeViewedProfileTarget = null,
                        viewedProfile = null,
                        isViewedProfileLoading = false,
                        errorMessage = "Не удалось открыть профиль."
                    )
                }
            }
        }
    }

    private fun openNativeConversation(
        peerId: Int,
        fallbackKind: String? = null,
        fallbackRoomId: Int = 0,
        requestedTopicId: Int = 0
    ) {
        val bootstrap = appState.bootstrap ?: return
        val conversation = bootstrap.messagesConversations.find { it.peerId == peerId }
        val kind = conversation?.kind ?: fallbackKind ?: return
        val roomId = conversation?.roomId ?: fallbackRoomId
        AloMessageNotifications.cancelChat(applicationContext, kind, peerId, roomId)
        // The header and whatever is in memory now; messages saved on disk arrive a moment later, without holding the tap.
        val rememberedDetail = offlineCacheStore.recentConversation(peerId, bootstrap.me.id)
        val bootstrapDetail = rememberedDetail ?: buildConversationDetailFromBootstrap(bootstrap, peerId)
        var projectedBootstrapDetail = bootstrapDetail?.let(::mergePendingLocalMessages)
        messageDraftStore.persistFrom(appState)
        val initialTopicId = projectedBootstrapDetail?.let {
            requestedTopicId.takeIf { id -> id > 0 && it.forumTopics.any { topic -> topic.id == id && !topic.isHidden } }
                ?: selectedForumTopicId(it)
        } ?: 0
        val cachedDraft = projectedBootstrapDetail?.let { messageDraftStore.restoredFor(bootstrap.me.id, it, initialTopicId) }
        val openingCookie = currentCookieHeader()
        fun stillOpening() = appState.bootstrap?.me?.id == bootstrap.me.id && appState.activeConversationPeerId == peerId &&
            AloOfflineAccountPolicy.matchesSession(openingCookie, currentCookieHeader())

        appState = appState.copy(
            activeConversationPeerId = peerId,
            activeConversation = projectedBootstrapDetail,
            activeForumTopicId = projectedBootstrapDetail?.let {
                requestedTopicId.takeIf { topicId ->
                    topicId > 0 && it.forumTopics.any { topic -> topic.id == topicId && !topic.isHidden }
                } ?: selectedForumTopicId(it)
            } ?: 0,
            activeChatAppearance = projectedBootstrapDetail?.let(chatAppearanceStore::read)
                ?: appState.activeChatAppearance,
            isChatPeerProfileOpen = false,
            isLoadingConversation = true,
            messageEditingId = null,
            messageComposerText = cachedDraft?.text.orEmpty(),
            messageRecorderMode = cachedDraft?.recorderMode ?: AloChatRecordMode.Voice,
            messageDraftAttachment = cachedDraft?.attachment,
            messageDraftAttachments = cachedDraft?.attachments.orEmpty(),
            messageForwardDraft = cachedDraft?.forwardDraft.orEmpty(),
            messageCaptureState = if (cachedDraft?.attachment != null || !cachedDraft?.attachments.isNullOrEmpty()) AloChatCaptureState.Preview else AloChatCaptureState.Idle,
            messageReplyTo = cachedDraft?.replyTo,
            messageSelfDestructSeconds = cachedDraft?.selfDestructSeconds ?: 0,
            errorMessage = null
        )

        if (rememberedDetail == null) {
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.Default) {
                    offlineCacheStore.readConversation(peerId, bootstrap.me.id)
                } ?: return@launch
                // The server's answer came first, or the person has already left: the saved copy is not needed.
                if (!stillOpening() || !appState.isLoadingConversation) return@launch
                val projected = mergePendingLocalMessages(saved)
                projectedBootstrapDetail = projected
                appState = appState.copy(
                    activeConversation = projected,
                    activeForumTopicId = requestedTopicId.takeIf { topicId ->
                        topicId > 0 && projected.forumTopics.any { topic -> topic.id == topicId && !topic.isHidden }
                    } ?: selectedForumTopicId(projected),
                    activeChatAppearance = chatAppearanceStore.read(projected)
                )
            }
        }

        lifecycleScope.launch {
            try {
                val detail = apiClient.loadConversation(
                    kind = kind,
                    peerId = conversation?.peerId ?: peerId,
                    roomId = roomId
                )
                if (!stillOpening()) return@launch
                val mergedDetail = mergePendingLocalMessages(detail)
                val selectedTopicId = requestedTopicId.takeIf { topicId ->
                    topicId > 0 && mergedDetail.forumTopics.any { topic -> topic.id == topicId && !topic.isHidden }
                } ?: selectedForumTopicId(mergedDetail)
                val restoredDraft = messageDraftStore.restoredFor(bootstrap.me.id, detail, selectedTopicId)
                val remoteTopicDraft = mergedDetail.forumTopicStates
                    .firstOrNull { it.topicId == selectedTopicId }
                    ?.draftText
                    .orEmpty()
                appState = appState.copy(
                    activeConversation = mergedDetail,
                    activeForumTopicId = selectedTopicId,
                    activeChatAppearance = chatAppearanceStore.read(mergedDetail),
                    isLoadingConversation = false,
                    messageComposerText = restoredDraft?.text ?: remoteTopicDraft,
                    messageRecorderMode = restoredDraft?.recorderMode ?: AloChatRecordMode.Voice,
                    messageCaptureState = if (restoredDraft?.attachment != null || !restoredDraft?.attachments.isNullOrEmpty()) {
                        AloChatCaptureState.Preview
                    } else {
                        AloChatCaptureState.Idle
                    },
                    messageRecordingDurationSec = restoredDraft?.attachment?.durationSec ?: 0,
                    messageDraftAttachment = restoredDraft?.attachment,
                    messageDraftAttachments = restoredDraft?.attachments.orEmpty(),
                    messageForwardDraft = restoredDraft?.forwardDraft.orEmpty(),
                    messageEditingId = null,
                    messageReplyTo = restoredDraft?.replyTo,
                    messageSelfDestructSeconds = restoredDraft?.selfDestructSeconds ?: 0,
                    errorMessage = null
                )
                startActiveConversationRefresh(mergedDetail)
                if (selectedTopicId > 0) {
                    markForumTopicRead(mergedDetail, selectedTopicId)
                }
                syncConversationPreview(peerId, mergedDetail.items.lastOrNull())
                scheduleConversationHistoryBackfill(mergedDetail)
                processPendingChatOperations()
            } catch (error: AloApiException) {
                if (!stillOpening()) return@launch
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        activeConversation = projectedBootstrapDetail,
                        activeChatAppearance = projectedBootstrapDetail?.let(chatAppearanceStore::read)
                            ?: appState.activeChatAppearance,
                        isLoadingConversation = false,
                        errorMessage = if (projectedBootstrapDetail == null) error.message else null
                    )
                }
            } catch (_error: Exception) {
                if (!stillOpening()) return@launch
                appState = appState.copy(
                    activeConversation = projectedBootstrapDetail,
                    activeChatAppearance = projectedBootstrapDetail?.let(chatAppearanceStore::read)
                        ?: appState.activeChatAppearance,
                    isLoadingConversation = false,
                    errorMessage = if (projectedBootstrapDetail == null) "Не удалось открыть чат." else null
                )
            }
        }
    }

    /**
     * The chat on screen, told to the server: a message to it sends no push to any device (as in Telegram), and a
     * private chat shows «в чате» to the other side. Repeated every 25 s while the chat stays open in the foreground
     * (the server forgets it after a minute); cleared when the chat closes or the app goes to the background.
     */
    private var chatPresenceJob: Job? = null

    private fun startActiveConversationRefresh(conversation: AloConversationDetail) {
        val peerId = conversation.peerId
        chatPresenceJob?.cancel()
        chatPresenceJob = lifecycleScope.launch {
            while (isActive && appState.activeConversationPeerId == peerId) {
                runCatching { apiClient.updateChatPresence(peerId) }
                delay(25_000)
            }
        }
    }

    private fun stopActiveConversationRefresh() {
        chatPresenceJob?.cancel()
        chatPresenceJob = null
        lifecycleScope.launch {
            runCatching { apiClient.updateChatPresence(0) }
        }
    }

    private fun startChatRealtime(ownerUserId: Int) {
        if (ownerUserId <= 0) return
        if (chatRealtimeOwnerUserId == ownerUserId && chatRealtimeJob?.isActive == true) return
        stopChatRealtime()
        chatRealtimeOwnerUserId = ownerUserId
        chatRealtimeJob = lifecycleScope.launch {
            var retryDelayMs = 500L
            while (isActive && chatRealtimeOwnerUserId == ownerUserId) {
                val cursor = readChatStreamCursor(ownerUserId)
                var reconnectFromStoredCursor = false
                try {
                    apiClient.updatesEventStream(afterCursor = cursor).collect { event ->
                        if (handleChatStreamEvent(event, ownerUserId)) {
                            reconnectFromStoredCursor = true
                            throw UpdatesCursorGapException()
                        }
                        retryDelayMs = 500L
                    }
                } catch (error: AloApiException) {
                    if (error.status == 401) {
                        handleSessionExpired()
                        return@launch
                    }
                } catch (_error: Exception) {
                    // Cached conversations stay readable while reconnecting from the last cursor.
                }
                delay(if (reconnectFromStoredCursor) 75L else retryDelayMs)
                retryDelayMs = if (reconnectFromStoredCursor) {
                    500L
                } else {
                    (retryDelayMs * 2).coerceAtMost(20_000L)
                }
            }
        }
    }

    private fun stopChatRealtime() {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        chatRealtimeRefreshJob?.cancel()
        chatRealtimeRefreshJob = null
        updatesBootstrapRefreshJob?.cancel()
        updatesBootstrapRefreshJob = null
        updatesAcknowledgementJob?.cancel()
        updatesAcknowledgementJob = null
        pendingUpdatesAcknowledgementCursor = 0
        chatRealtimeOwnerUserId = 0
        chatRealtimeNeedsActiveRefresh = false
    }

    private fun handleChatStreamEvent(event: AloChatStreamEvent, ownerUserId: Int): Boolean {
        val active = appState.activeConversation?.let {
            AloActiveConversationKey(peerId = it.peerId, roomId = it.roomId, kind = it.kind)
        }
        return when (
            val decision = AloChatRealtimeReducer.reduce(
                event = event,
                storedCursor = readChatStreamCursor(ownerUserId),
                activeConversation = active
            )
        ) {
            AloChatRealtimeDecision.Ignore -> false
            is AloChatRealtimeDecision.ResetCursor -> {
                writeChatStreamCursor(ownerUserId, decision.cursor)
                scheduleUpdatesAcknowledgement(ownerUserId, decision.cursor)
                if (decision.refreshConversations) {
                    scheduleChatStreamRefresh(decision.refreshActiveConversation)
                }
                applyUnifiedUpdateAction(event, ownerUserId)
                false
            }
            is AloChatRealtimeDecision.AdvanceCursor -> {
                writeChatStreamCursor(ownerUserId, decision.cursor)
                scheduleUpdatesAcknowledgement(ownerUserId, decision.cursor)
                if (decision.refreshConversations) {
                    scheduleChatStreamRefresh(decision.refreshActiveConversation)
                }
                applyUnifiedUpdateAction(event, ownerUserId)
                false
            }
            is AloChatRealtimeDecision.ReconnectFromStoredCursor -> {
                if (decision.refreshConversations) {
                    scheduleChatStreamRefresh(decision.refreshActiveConversation)
                }
                scheduleBootstrapRefreshFromUpdates(ownerUserId)
                true
            }
        }
    }

    private fun applyUnifiedUpdateAction(event: AloChatStreamEvent, ownerUserId: Int) {
        when (val action = AloUnifiedUpdateRouter.action(event)) {
            AloUnifiedUpdateAction.None -> Unit
            AloUnifiedUpdateAction.RefreshBootstrap ->
                scheduleBootstrapRefreshFromUpdates(ownerUserId)
            AloUnifiedUpdateAction.ApplyNotification -> {
                val notification = apiClient.parseNotificationUpdate(
                    event.payload
                        .optJSONObject("data")
                        ?.optJSONObject("notification")
                )
                if (notification == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    updateBootstrap { data ->
                        data.copy(
                            notifications = (
                                listOf(notification) +
                                    data.notifications.filter { it.id != notification.id }
                                ).take(50)
                        )
                    }
                }
            }
            is AloUnifiedUpdateAction.MarkNotificationsRead -> {
                updateBootstrap { data ->
                    data.copy(
                        notifications = data.notifications.map { notification ->
                            if (
                                notification.id <= action.throughNotificationId ||
                                notification.id in action.notificationIds
                            ) {
                                notification.copy(isRead = true)
                            } else {
                                notification
                            }
                        }
                    )
                }
            }
            is AloUnifiedUpdateAction.DeleteNotification -> {
                updateBootstrap { data ->
                    data.copy(
                        notifications = data.notifications.filter {
                            it.id != action.notificationId
                        }
                    )
                }
            }
            is AloUnifiedUpdateAction.ApplyNotificationSettings -> {
                updateBootstrap { data ->
                    data.copy(notificationSettings = action.settings)
                }
            }
            is AloUnifiedUpdateAction.ApplyChatPins -> {
                val pinned = action.pinned
                if (pinned == null || chatPinsUploadJob?.isActive == true) syncChatPins(ownerUserId)
                else applyChatPins(ownerUserId, pinned)
            }
            is AloUnifiedUpdateAction.ApplyChatMute -> {
                val active = appState.activeConversation
                val activeKind = if ((active?.roomId ?: 0) > 0 || active?.kind == "group") "group" else "direct"
                val activeTargetId = if (activeKind == "group") active?.roomId ?: 0 else active?.peerId ?: 0
                if (active != null && activeKind == action.chatKind && activeTargetId == action.targetId) {
                    appState = appState.copy(activeChatMuted = action.muted)
                }
            }
            is AloUnifiedUpdateAction.ApplyPost -> {
                val post = apiClient.parsePostUpdate(
                    event.payload
                        .optJSONObject("data")
                        ?.optJSONObject("post")
                )
                if (post == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    updateBootstrap { data ->
                        if (action.isNew) {
                            data.copy(
                                feedPopular = prependPost(data.feedPopular, post),
                                feedSubscriptions = prependPost(data.feedSubscriptions, post),
                                profilePosts = if (
                                    !post.author.isChannel &&
                                    post.creatorId == data.me.id
                                ) {
                                    prependPost(data.profilePosts, post)
                                } else {
                                    data.profilePosts
                                }
                            )
                        } else {
                            data.copy(
                                feedPopular = replacePost(data.feedPopular, post),
                                feedSubscriptions = replacePost(data.feedSubscriptions, post),
                                profilePosts = replacePost(data.profilePosts, post)
                            )
                        }
                    }
                    appState.viewedProfile?.let { viewed ->
                        val ownsNewPost =
                            action.isNew &&
                                (
                                    viewed.kind == "channel" &&
                                        post.author.isChannel &&
                                        post.author.id == viewed.id ||
                                    viewed.kind == "user" &&
                                        !post.author.isChannel &&
                                        post.creatorId == viewed.id
                                    )
                        if (ownsNewPost || viewed.posts.any { it.id == post.id }) {
                            appState = appState.copy(
                                viewedProfile = viewed.copy(
                                    posts = if (ownsNewPost) {
                                        prependPost(viewed.posts, post)
                                    } else {
                                        replacePost(viewed.posts, post)
                                    }
                                )
                            )
                        }
                    }
                    if (appState.commentsPostId == post.id) {
                        appState = appState.copy(commentsPost = post)
                    }
                }
            }
            is AloUnifiedUpdateAction.DeletePost -> {
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = removePost(data.feedPopular, action.postId),
                        feedSubscriptions = removePost(data.feedSubscriptions, action.postId),
                        profilePosts = removePost(data.profilePosts, action.postId)
                    )
                }
                appState.viewedProfile?.let { viewed ->
                    appState = appState.copy(
                        viewedProfile = viewed.copy(
                            posts = removePost(viewed.posts, action.postId)
                        )
                    )
                }
                if (appState.commentsPostId == action.postId) {
                    appState = appState.copy(
                        commentsPostId = 0,
                        commentsPost = null,
                        comments = emptyList(),
                        commentsComposerText = "",
                        commentsEditingCommentId = null
                    )
                }
            }
            is AloUnifiedUpdateAction.ApplyComment -> {
                val data = event.payload.optJSONObject("data")
                val comment = apiClient.parseCommentUpdate(data?.optJSONObject("comment"))
                if (comment == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    if (appState.commentsPostId == action.postId) {
                        appState = appState.copy(
                            comments = if (action.isNew) {
                                aloUpsertComment(appState.comments, comment)
                            } else {
                                appState.comments.map {
                                    if (it.id == comment.id) comment else it
                                }
                            }
                        )
                    }
                    apiClient.parsePostUpdate(data?.optJSONObject("post"))?.let { post ->
                        updateBootstrap { bootstrap ->
                            bootstrap.copy(
                                feedPopular = replacePost(bootstrap.feedPopular, post),
                                feedSubscriptions = replacePost(bootstrap.feedSubscriptions, post),
                                profilePosts = replacePost(bootstrap.profilePosts, post)
                            )
                        }
                        appState.viewedProfile?.let { viewed ->
                            if (viewed.posts.any { it.id == post.id }) {
                                appState = appState.copy(
                                    viewedProfile = viewed.copy(
                                        posts = replacePost(viewed.posts, post)
                                    )
                                )
                            }
                        }
                        if (appState.commentsPostId == post.id) {
                            appState = appState.copy(commentsPost = post)
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.DeleteComment -> {
                if (appState.commentsPostId == action.postId) {
                    appState = appState.copy(
                        comments = appState.comments.filterNot {
                            it.id == action.commentId
                        }
                    )
                }
                apiClient.parsePostUpdate(
                    event.payload.optJSONObject("data")?.optJSONObject("post")
                )?.let { post ->
                    updateBootstrap { bootstrap ->
                        bootstrap.copy(
                            feedPopular = replacePost(bootstrap.feedPopular, post),
                            feedSubscriptions = replacePost(bootstrap.feedSubscriptions, post),
                            profilePosts = replacePost(bootstrap.profilePosts, post)
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        if (viewed.posts.any { it.id == post.id }) {
                            appState = appState.copy(
                                viewedProfile = viewed.copy(
                                    posts = replacePost(viewed.posts, post)
                                )
                            )
                        }
                    }
                    if (appState.commentsPostId == post.id) {
                        appState = appState.copy(commentsPost = post)
                    }
                }
            }
            AloUnifiedUpdateAction.ApplyCurrentUser -> {
                val current = appState.bootstrap
                val user = current?.let { data ->
                    apiClient.parseCurrentUserUpdate(
                        event.payload
                            .optJSONObject("data")
                            ?.optJSONObject("user"),
                        data.me
                    )
                }
                if (current == null || user == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    updateBootstrap { data -> data.copy(me = user) }
                    appState.viewedProfile?.let { viewed ->
                        if (viewed.kind == "user" && viewed.id == user.id) {
                            appState = appState.copy(
                                viewedProfile = viewed.copy(
                                    title = user.name,
                                    username = user.username,
                                    avatarUrl = user.avatarUrl,
                                    avatarGallery = user.avatarGallery,
                                    verified = user.verified,
                                    coverStyle = user.coverStyle,
                                    coverImageUrl = user.coverImageUrl,
                                    description = user.bio,
                                    meta = user.registeredLabel
                                )
                            )
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyUserRelation -> {
                val update = apiClient.parseUserRelationUpdate(
                    event.payload.optJSONObject("data")
                )
                if (update == null || update.userId != action.userId) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    updateBootstrap { data ->
                        data.copy(
                            profileStats = update.stats,
                            profileRelations = data.profileRelations.copy(
                                friendsCount = update.stats.friendsCount,
                                followingCount = update.stats.followingCount,
                                followersCount = update.stats.followersCount
                            )
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        if (viewed.kind == "user" && viewed.id == update.userId) {
                            appState = appState.copy(
                                viewedProfile = viewed.copy(
                                    metrics = viewed.metrics.map { metric ->
                                        when (metric.label) {
                                            "Подписки" -> metric.copy(
                                                value = update.stats.followingCount.toString()
                                            )
                                            "Подписчики" -> metric.copy(
                                                value = update.stats.followersCount.toString()
                                            )
                                            "Друзья" -> metric.copy(
                                                value = update.stats.friendsCount.toString()
                                            )
                                            else -> metric
                                        }
                                    },
                                    friendStatus = update.friendStatus,
                                    incomingFriendRequestId = update.incomingRequestId,
                                    outgoingFriendRequestId = update.outgoingRequestId,
                                    canSendFriendRequest = update.canSendFriendRequest
                                )
                            )
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyChannelPostComments -> {
                val data = action.data
                val peerId = data.optInt("peerId")
                val postId = data.optInt("postId")
                val conversation = appState.activeConversation
                if (peerId > 0 && postId > 0 && conversation != null && appState.activeConversationPeerId == peerId) {
                    val commenters = apiClient.parseConversationPeers(data.optJSONArray("recentCommenters")).take(3)
                    appState = appState.copy(
                        activeConversation = conversation.copy(items = conversation.items.map { item ->
                            if (item.channelPostId != postId) item else item.copy(
                                channelPostCommentCount = data.optInt("commentCount").coerceAtLeast(0),
                                channelPostRecentCommenters = commenters,
                                channelPostHasUnreadComments = data.optBoolean("hasUnread")
                            )
                        })
                    )
                }
            }
            is AloUnifiedUpdateAction.ApplyChannel -> {
                val profile = apiClient.parseChannelProfileUpdate(
                    event.payload
                        .optJSONObject("data")
                        ?.optJSONObject("profile")
                )
                if (profile == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    val summary = AloEntitySummary(
                        id = profile.id,
                        name = profile.title,
                        username = profile.username,
                        avatarUrl = profile.avatarUrl,
                        verified = profile.verified,
                        isChannel = true
                    )
                    updateBootstrap { data ->
                        val exists = data.searchChannels.any { it.id == profile.id }
                        data.copy(
                            searchChannels = if (action.isNew || !exists) {
                                listOf(summary) +
                                    data.searchChannels.filterNot { it.id == profile.id }
                            } else {
                                data.searchChannels.map {
                                    if (it.id == profile.id) summary else it
                                }
                            }
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        if (viewed.kind == "channel" && viewed.id == profile.id) {
                            appState = appState.copy(
                                viewedProfile = profile.copy(
                                    adminEvents = viewed.adminEvents
                                )
                            )
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.DeleteChannel -> {
                val keepPost: (AloPost) -> Boolean = { post ->
                    !(post.author.isChannel && post.author.id == action.channelId)
                }
                updateBootstrap { data ->
                    data.copy(
                        feedPopular = data.feedPopular.filter(keepPost),
                        feedSubscriptions = data.feedSubscriptions.filter(keepPost),
                        profilePosts = data.profilePosts.filter(keepPost),
                        searchChannels = data.searchChannels.filterNot {
                            it.id == action.channelId
                        }
                    )
                }
                if (
                    appState.viewedProfile?.kind == "channel" &&
                    appState.viewedProfile?.id == action.channelId
                ) {
                    appState = appState.copy(viewedProfile = null)
                }
            }
            is AloUnifiedUpdateAction.ApplyChannelSubscription -> {
                appState.viewedProfile?.let { viewed ->
                    if (viewed.kind == "channel" && viewed.id == action.channelId) {
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                metrics = viewed.metrics.map { metric ->
                                    if (metric.label == "Подписчики") {
                                        metric.copy(value = action.subscribersCount.toString())
                                    } else {
                                        metric
                                    }
                                }
                            )
                        )
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyChannelSubscribers -> {
                appState.viewedProfile?.let { viewed ->
                    if (viewed.kind == "channel" && viewed.id == action.channelId) {
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                metrics = viewed.metrics.map { metric ->
                                    if (metric.label == "Подписчики") {
                                        metric.copy(value = action.subscribersCount.toString())
                                    } else {
                                        metric
                                    }
                                }
                            )
                        )
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyChannelManagementDelta -> {
                val data = event.payload.optJSONObject("data")
                val delta = apiClient.parseChannelManagementDelta(
                    data?.optJSONObject("delta")
                )
                val viewed = appState.viewedProfile
                if (
                    delta == null ||
                    viewed?.kind != "channel" ||
                    viewed.id != action.channelId
                ) {
                    val target = appState.activeViewedProfileTarget
                    if (
                        delta == null &&
                        target is AloProfileTarget.Channel &&
                        target.channelId == action.channelId
                    ) {
                        refreshViewedProfileFromUpdate(target)
                    }
                } else {
                    val kind = data?.optString("kind").orEmpty()
                    val targetUserId = data?.optInt("targetUserId") ?: 0
                    val nextCanManage = if (
                        targetUserId == ownerUserId &&
                        !viewed.isOwner
                    ) {
                        when (kind) {
                            "moderator_added" -> true
                            "moderator_removed" -> false
                            else -> viewed.canManage
                        }
                    } else {
                        viewed.canManage
                    }
                    val events = viewed.adminEvents.toMutableList()
                    delta.adminEvent?.let { adminEvent ->
                        events.removeAll { it.id == adminEvent.id }
                        events.add(0, adminEvent)
                    }
                    if (events.size > 200) {
                        events.subList(200, events.size).clear()
                    }
                    appState = appState.copy(
                        viewedProfile = viewed.copy(
                            metrics = viewed.metrics.map { metric ->
                                if (metric.label == "Модераторы") {
                                    metric.copy(value = delta.moderatorCount.toString())
                                } else {
                                    metric
                                }
                            },
                            canManage = nextCanManage,
                            commentsEnabled = if (delta.hasSettings) {
                                delta.commentsEnabled ?: viewed.commentsEnabled
                            } else {
                                viewed.commentsEnabled
                            },
                            signMessages = if (delta.hasSettings) {
                                delta.signMessages ?: viewed.signMessages
                            } else {
                                viewed.signMessages
                            },
                            showAuthorProfiles = if (delta.hasSettings) {
                                delta.showAuthorProfiles ?: viewed.showAuthorProfiles
                            } else {
                                viewed.showAuthorProfiles
                            },
                            discussionRoomId = if (delta.hasSettings) {
                                delta.discussionRoomId ?: 0
                            } else {
                                viewed.discussionRoomId
                            },
                            adminEvents = events
                        )
                    )
                }
            }
            is AloUnifiedUpdateAction.RefreshChannelManagement -> {
                val target = appState.activeViewedProfileTarget
                if (
                    target is AloProfileTarget.Channel &&
                    target.channelId == action.channelId
                ) {
                    refreshViewedProfileFromUpdate(target)
                }
            }
            is AloUnifiedUpdateAction.ApplyGroup -> {
                val room = event.payload
                    .optJSONObject("data")
                    ?.optJSONObject("room")
                val roomId = room?.optInt("id") ?: 0
                val existing = appState.bootstrap
                    ?.messagesConversations
                    ?.firstOrNull { it.kind == "group" && it.roomId == roomId }
                val summary = apiClient.parseGroupRoomUpdate(room, existing)
                if (summary == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    updateBootstrap { data ->
                        val exists = data.messagesConversations.any {
                            it.kind == "group" && it.roomId == summary.roomId
                        }
                        data.copy(
                            messagesConversations = if (action.isNew || !exists) {
                                listOf(summary) +
                                    data.messagesConversations.filterNot {
                                        it.kind == "group" &&
                                            it.roomId == summary.roomId
                                    }
                            } else {
                                data.messagesConversations.map {
                                    if (
                                        it.kind == "group" &&
                                        it.roomId == summary.roomId
                                    ) {
                                        summary
                                    } else {
                                        it
                                    }
                                }
                            }
                        )
                    }
                    appState.viewedProfile?.let { viewed ->
                        if (viewed.kind == "group" && viewed.id == summary.roomId) {
                            appState = appState.copy(
                                viewedProfile = viewed.copy(
                                    title = summary.peer.name,
                                    avatarUrl = summary.peer.avatarUrl,
                                    description = room?.optString("description").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.DeleteGroup -> {
                updateBootstrap { data ->
                    data.copy(
                        messagesConversations = data.messagesConversations.filterNot {
                            it.kind == "group" && it.roomId == action.roomId
                        }
                    )
                }
                if (
                    appState.viewedProfile?.kind == "group" &&
                    appState.viewedProfile?.id == action.roomId
                ) {
                    appState = appState.copy(
                        activeViewedProfileTarget = null,
                        viewedProfile = null,
                        isViewedProfileLoading = false
                    )
                }
                if (
                    appState.activeConversation?.kind == "group" &&
                    appState.activeConversation?.roomId == action.roomId
                ) {
                    stopActiveConversationRefresh()
                    appState = appState.copy(
                        activeConversationPeerId = 0,
                        activeConversation = null,
                        activeChatAppearance = AloChatAppearance(),
                        activeChatMuted = false,
                        isChatPeerProfileOpen = false,
                        activeMedia = null,
                        isLoadingConversation = false,
                        isSendingMessage = false,
                        messageComposerText = "",
                        messageCaptureState = AloChatCaptureState.Idle,
                        messageRecordingDurationSec = 0,
                        messageRecordingLevels = emptyList(),
                        messageDraftAttachment = null,
                        messageDraftAttachments = emptyList(),
                        activeForumTopicId = 0,
                        messageForwardDraft = emptyList(),
                        messageEditingId = null,
                        groupManagement = null,
                        isGroupManagementLoading = false
                    )
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupPolicy -> {
                val policy = apiClient.parseGroupPolicyUpdate(
                    event.payload.optJSONObject("data")?.optJSONObject("policy")
                )
                if (policy == null) {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else {
                    appState.activeConversation?.let { conversation ->
                        if (
                            conversation.kind == "group" &&
                            conversation.roomId == action.roomId
                        ) {
                            appState = appState.copy(
                                activeConversation = conversation.copy(groupPolicy = policy),
                                groupManagement = appState.groupManagement?.copy(policy = policy)
                            )
                        }
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupMembers -> {
                val members = apiClient.parseGroupMembersUpdate(
                    event.payload.optJSONObject("data")?.optJSONArray("members")
                )
                updateBootstrap { data ->
                    data.copy(
                        messagesConversations = data.messagesConversations.map { summary ->
                            if (
                                summary.kind == "group" &&
                                summary.roomId == action.roomId
                            ) {
                                summary.copy(memberCount = members.size)
                            } else {
                                summary
                            }
                        }
                    )
                }
                appState.activeConversation?.let { conversation ->
                    if (
                        conversation.kind == "group" &&
                        conversation.roomId == action.roomId
                    ) {
                        appState = appState.copy(
                            activeConversation = conversation.copy(
                                memberCount = members.size,
                                members = members
                            ),
                            groupManagement = appState.groupManagement?.copy(
                                members = members
                            )
                        )
                    }
                }
                appState.viewedProfile?.let { viewed ->
                    if (viewed.kind == "group" && viewed.id == action.roomId) {
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                metrics = viewed.metrics.map { metric ->
                                    if (metric.label == "Участники") {
                                        metric.copy(value = members.size.toString())
                                    } else {
                                        metric
                                    }
                                }
                            )
                        )
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupMemberCount -> {
                updateBootstrap { data ->
                    data.copy(
                        messagesConversations = data.messagesConversations.map { summary ->
                            if (
                                summary.kind == "group" &&
                                summary.roomId == action.roomId
                            ) {
                                summary.copy(memberCount = action.memberCount)
                            } else {
                                summary
                            }
                        }
                    )
                }
                appState.activeConversation?.let { conversation ->
                    if (
                        conversation.kind == "group" &&
                        conversation.roomId == action.roomId
                    ) {
                        appState = appState.copy(
                            activeConversation = conversation.copy(
                                memberCount = action.memberCount,
                                subtitle = if (
                                    conversation.entityType == "channel" ||
                                    conversation.groupPolicy?.kind == "channel"
                                ) {
                                    formatSubscriberCount(action.memberCount)
                                } else {
                                    formatMemberCount(action.memberCount)
                                }
                            )
                        )
                    }
                }
                appState.viewedProfile?.let { viewed ->
                    if (viewed.kind == "group" && viewed.id == action.roomId) {
                        appState = appState.copy(
                            viewedProfile = viewed.copy(
                                metrics = viewed.metrics.map { metric ->
                                    if (metric.label == "Участники") {
                                        metric.copy(value = action.memberCount.toString())
                                    } else {
                                        metric
                                    }
                                }
                            )
                        )
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupMembership -> {
                if (action.status in setOf("removed", "banned", "left")) {
                    updateBootstrap { data ->
                        data.copy(
                            messagesConversations = data.messagesConversations.filterNot {
                                it.kind == "group" && it.roomId == action.roomId
                            }
                        )
                    }
                    if (
                        appState.viewedProfile?.kind == "group" &&
                        appState.viewedProfile?.id == action.roomId
                    ) {
                        appState = appState.copy(
                            activeViewedProfileTarget = null,
                            viewedProfile = null,
                            isViewedProfileLoading = false
                        )
                    }
                    if (
                        appState.activeConversation?.kind == "group" &&
                        appState.activeConversation?.roomId == action.roomId
                    ) {
                        stopActiveConversationRefresh()
                        appState = appState.copy(
                            activeConversationPeerId = 0,
                            activeConversation = null,
                            activeChatAppearance = AloChatAppearance(),
                            activeChatMuted = false,
                            isChatPeerProfileOpen = false,
                            activeMedia = null,
                            isLoadingConversation = false,
                            isSendingMessage = false,
                            messageComposerText = "",
                            messageCaptureState = AloChatCaptureState.Idle,
                            messageRecordingDurationSec = 0,
                            messageRecordingLevels = emptyList(),
                            messageDraftAttachment = null,
                            messageDraftAttachments = emptyList(),
                            activeForumTopicId = 0,
                            messageForwardDraft = emptyList(),
                            messageEditingId = null,
                            messageReplyTo = null,
                            groupManagement = null,
                            isGroupManagementLoading = false
                        )
                    }
                } else if (action.status == "joined") {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                } else if (action.status == "membership_updated") {
                    scheduleBootstrapRefreshFromUpdates(ownerUserId)
                    if (
                        appState.activeConversation?.kind == "group" &&
                        appState.activeConversation?.roomId == action.roomId
                    ) {
                        scheduleChatStreamRefresh(true)
                    }
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupManagementDelta -> {
                val delta = apiClient.parseGroupManagementDelta(
                    event.payload
                        .optJSONObject("data")
                        ?.optJSONObject("delta")
                )
                val currentManagement = appState.groupManagement
                if (
                    delta == null ||
                    currentManagement == null ||
                    appState.activeConversation?.kind != "group" ||
                    appState.activeConversation?.roomId != action.roomId
                ) {
                    if (
                        currentManagement != null &&
                        appState.activeConversation?.kind == "group" &&
                        appState.activeConversation?.roomId == action.roomId
                    ) {
                        lifecycleScope.launch {
                            runCatching { refreshGroupManagement(action.roomId) }
                        }
                    }
                } else {
                    val members = currentManagement.members
                        .filterNot { it.user.id in delta.memberIdsRemoved }
                        .toMutableList()
                    delta.membersUpserted.forEach { member ->
                        members.removeAll { it.user.id == member.user.id }
                        members += member
                    }
                    members.sortWith(
                        compareBy<AloGroupMember> {
                            when (it.role) {
                                "owner" -> 0
                                "admin" -> 1
                                else -> 2
                            }
                        }.thenBy { it.user.name.lowercase() }
                    )

                    val requests = currentManagement.requests
                        .filterNot { it.user.id in delta.requestUserIdsRemoved }
                        .toMutableList()
                    delta.requestsUpserted.forEach { request ->
                        requests.removeAll { it.user.id == request.user.id }
                        requests += request
                    }
                    requests.sortBy { it.createdAt }

                    val bans = currentManagement.bans
                        .filterNot { it.user.id in delta.banUserIdsRemoved }
                        .toMutableList()
                    delta.bansUpserted.forEach { ban ->
                        bans.removeAll { it.user.id == ban.user.id }
                        bans.add(0, ban)
                    }

                    val invites = currentManagement.invites.toMutableList()
                    delta.invitesUpserted.forEach { invite ->
                        invites.removeAll { it.id == invite.id }
                        invites.add(0, invite)
                    }

                    val topics = currentManagement.topics
                        .filterNot { it.id in delta.topicIdsRemoved }
                        .toMutableList()
                    delta.topicsUpserted.forEach { topic ->
                        topics.removeAll { it.id == topic.id }
                        topics += topic
                    }
                    topics.sortWith(
                        compareBy<AloForumTopic> { it.isHidden }
                            .thenByDescending { it.isGeneral }
                            .thenBy { it.isClosed }
                            .thenByDescending { it.updatedAt }
                    )

                    val events = currentManagement.events.toMutableList()
                    delta.adminEvent?.let { adminEvent ->
                        events.removeAll { it.id == adminEvent.id }
                        events.add(0, adminEvent)
                    }
                    if (events.size > 200) {
                        events.subList(200, events.size).clear()
                    }

                    appState = appState.copy(
                        groupManagement = currentManagement.copy(
                            policy = delta.policy ?: currentManagement.policy,
                            members = members,
                            requests = requests,
                            invites = invites,
                            events = events,
                            topics = topics,
                            bans = bans
                        )
                    )
                }
            }
            is AloUnifiedUpdateAction.ApplyGroupTopicState -> {
                val current = appState.activeConversation
                val state = apiClient.parseForumTopicStateUpdate(
                    event.payload
                        .optJSONObject("data")
                        ?.optJSONObject("state")
                )
                if (
                    current != null &&
                    current.kind == "group" &&
                    current.roomId == action.roomId &&
                    state != null
                ) {
                    val states = current.forumTopicStates
                        .filterNot { it.topicId == state.topicId }
                        .plus(state)
                    appState = appState.copy(
                        activeConversation = current.copy(forumTopicStates = states),
                        messageComposerText = if (
                            appState.activeForumTopicId == state.topicId &&
                            appState.messageComposerText.isEmpty()
                        ) {
                            state.draftText
                        } else {
                            appState.messageComposerText
                        }
                    )
                }
            }
            is AloUnifiedUpdateAction.RefreshSecretChats ->
                secretChats.onUpdate(action.secretChatId, action.closed)
            is AloUnifiedUpdateAction.RefreshGroupManagement -> {
                if (
                    appState.groupManagement != null &&
                    appState.activeConversation?.kind == "group" &&
                    appState.activeConversation?.roomId == action.roomId
                ) {
                    lifecycleScope.launch {
                        runCatching { refreshGroupManagement(action.roomId) }
                    }
                }
            }
        }
    }

    private fun refreshViewedProfileFromUpdate(target: AloProfileTarget) {
        lifecycleScope.launch {
            val profile = runCatching {
                when (target) {
                    is AloProfileTarget.User -> apiClient.loadUserProfile(target.userId)
                    is AloProfileTarget.Channel -> apiClient.loadChannelProfile(target.channelId)
                    is AloProfileTarget.Group -> apiClient.loadGroupProfile(target.roomId)
                }
            }.getOrNull() ?: return@launch
            if (appState.activeViewedProfileTarget == target) {
                appState = appState.copy(viewedProfile = profile)
            }
        }
    }

    private fun scheduleBootstrapRefreshFromUpdates(ownerUserId: Int) {
        if (ownerUserId <= 0 || ownerUserId != chatRealtimeOwnerUserId) return
        if (updatesBootstrapRefreshJob?.isActive == true) return
        updatesBootstrapRefreshJob = lifecycleScope.launch {
            delay(220)
            updatesBootstrapRefreshJob = null
            if (ownerUserId != chatRealtimeOwnerUserId) return@launch
            runCatching {
                val snapshot = apiClient.loadBootstrapSnapshot()
                if (snapshot.data.me.id != ownerUserId) return@runCatching
                offlineCacheStore.inBackground { saveBootstrap(snapshot.rawJson) }
                appState = appState.copy(
                    bootstrap = snapshot.data,
                    isOfflineCached = false,
                    errorMessage = null
                )
                activatePendingChatOutbox(ownerUserId)
                syncChatPins(ownerUserId)
            }
        }
    }

    private fun scheduleUpdatesAcknowledgement(ownerUserId: Int, cursor: Long) {
        if (cursor <= 0 || ownerUserId != chatRealtimeOwnerUserId) return
        pendingUpdatesAcknowledgementCursor =
            maxOf(pendingUpdatesAcknowledgementCursor, cursor)
        if (updatesAcknowledgementJob?.isActive == true) return
        updatesAcknowledgementJob = lifecycleScope.launch {
            delay(600)
            if (ownerUserId != chatRealtimeOwnerUserId) return@launch
            val cursorToAcknowledge = pendingUpdatesAcknowledgementCursor
            pendingUpdatesAcknowledgementCursor = 0
            updatesAcknowledgementJob = null
            if (cursorToAcknowledge <= 0) return@launch
            runCatching {
                apiClient.acknowledgeUpdates(
                    cursor = cursorToAcknowledge,
                    deviceId = updatesDeviceId()
                )
            }
        }
    }

    private fun scheduleChatStreamRefresh(refreshActive: Boolean) {
        chatRealtimeNeedsActiveRefresh = chatRealtimeNeedsActiveRefresh || refreshActive
        if (chatRealtimeRefreshJob?.isActive == true) return
        chatRealtimeRefreshJob = lifecycleScope.launch {
            delay(140)
            val shouldRefreshActive = chatRealtimeNeedsActiveRefresh
            chatRealtimeNeedsActiveRefresh = false
            chatRealtimeRefreshJob = null
            refreshChatStateFromStream(refreshActive = shouldRefreshActive)
        }
    }

    private suspend fun refreshChatStateFromStream(refreshActive: Boolean) {
        val conversations = runCatching { apiClient.loadConversationSummaries() }.getOrNull() ?: return
        updateBootstrap { bootstrap ->
            bootstrap.copy(messagesConversations = conversations)
        }
        appState.bootstrap?.let(offlineCacheStore::scheduleMessagesUpdate)

        val current = appState.activeConversation ?: return
        if (!refreshActive) return
        val refreshed = runCatching {
            apiClient.loadConversation(
                kind = current.kind,
                peerId = current.peerId,
                roomId = current.roomId
            )
        }.getOrNull() ?: return
        val latest = appState.activeConversation
        if (
            latest == null ||
            latest.peerId != current.peerId ||
            latest.roomId != current.roomId ||
            latest.kind != current.kind
        ) {
            return
        }
        val merged = mergePendingLocalMessages(refreshed)
        appState = appState.copy(
            activeConversation = merged,
            activeForumTopicId = selectedForumTopicId(merged),
            isOfflineCached = false
        )
        syncConversationPreview(current.peerId, merged.items.lastOrNull())
        processPendingChatOperations()
    }

    private fun readChatStreamCursor(ownerUserId: Int): Long =
        appPrefs.getLong("updates_stream_cursor_$ownerUserId", 0).coerceAtLeast(0)

    private fun writeChatStreamCursor(ownerUserId: Int, cursor: Long) {
        appPrefs.edit()
            .putLong("updates_stream_cursor_$ownerUserId", cursor.coerceAtLeast(0))
            .apply()
    }

    private fun updatesDeviceId(): String =
        appPrefs.getString("updates_device_id", null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().lowercase().also {
                appPrefs.edit().putString("updates_device_id", it).apply()
            }

    private fun sendTypingSignalIfNeeded(value: String) {
        if (value.trim().isEmpty()) return
        val conversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(conversation)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTypingSignalAtMs < 1400) return
        lastTypingSignalAtMs = now
        lifecycleScope.launch {
            runCatching {
                apiClient.sendTyping(
                    kind = conversation.kind,
                    peerId = conversation.peerId,
                    roomId = conversation.roomId
                )
            }
        }
    }

    private fun pendingTarget(conversation: AloConversationDetail): PendingChatTarget {
        return PendingChatTarget(
            peerId = conversation.peerId,
            roomId = conversation.roomId,
            kind = conversation.kind
        )
    }

    private fun selectedForumTopicId(
        conversation: AloConversationDetail,
        preferredTopicId: Int = appState.activeForumTopicId
    ): Int {
        if (conversation.groupPolicy?.isForum != true) return 0
        val topics = conversation.forumTopics
        return topics.firstOrNull { it.id == preferredTopicId && !it.isClosed && !it.isHidden }?.id
            ?: topics.firstOrNull { it.isGeneral && !it.isClosed && !it.isHidden }?.id
            ?: topics.firstOrNull { !it.isClosed && !it.isHidden }?.id
            ?: 0
    }

    private fun applyForumTopicState(roomId: Int, state: AloForumTopicState) {
        val conversation = appState.activeConversation ?: return
        if (conversation.kind != "group" || conversation.roomId != roomId) return
        appState = appState.copy(
            activeConversation = conversation.copy(
                forumTopicStates = conversation.forumTopicStates
                    .filterNot { it.topicId == state.topicId }
                    .plus(state)
            )
        )
    }

    private fun markForumTopicRead(conversation: AloConversationDetail, topicId: Int) {
        if (conversation.kind != "group" || conversation.roomId <= 0 || topicId <= 0) return
        val cursor = conversation.forumTopicStates
            .firstOrNull { it.topicId == topicId }
            ?.cursor
            ?: conversation.items
                .filter { it.topicId == topicId && it.id > 0 }
                .maxOfOrNull { it.id }
            ?: 0
        lifecycleScope.launch {
            runCatching {
                apiClient.updateGroupTopicState(
                    roomId = conversation.roomId,
                    topicId = topicId,
                    readThroughMessageId = cursor
                )
            }.getOrNull()?.let { state ->
                applyForumTopicState(conversation.roomId, state)
            }
        }
    }

    private fun scheduleForumTopicDraftSync(text: String) {
        val conversation = appState.activeConversation ?: return
        val topicId = appState.activeForumTopicId
        if (conversation.kind != "group" || conversation.roomId <= 0 || topicId <= 0) return
        forumTopicDraftSyncJob?.cancel()
        forumTopicDraftSyncJob = lifecycleScope.launch {
            delay(650)
            persistForumTopicDraft(conversation, topicId, text)
        }
    }

    private suspend fun persistForumTopicDraft(
        conversation: AloConversationDetail,
        topicId: Int,
        text: String
    ) {
        if (conversation.kind != "group" || conversation.roomId <= 0 || topicId <= 0) return
        val baseRevision = appState.activeConversation
            ?.forumTopicStates
            ?.firstOrNull { it.topicId == topicId }
            ?.draftRevision
            ?: 0
        try {
            val state = apiClient.updateGroupTopicState(
                roomId = conversation.roomId,
                topicId = topicId,
                draftText = text,
                updateDraft = true,
                baseDraftRevision = baseRevision
            )
            applyForumTopicState(conversation.roomId, state)
        } catch (_error: IOException) {
            // Offline: the draft stays in the composer and is saved with the next change.
            return
        } catch (error: AloApiException) {
            if (error.code != "GROUP_TOPIC_DRAFT_CONFLICT") return
            val latest = runCatching {
                apiClient.loadGroupTopicStates(conversation.roomId)
                    .firstOrNull { it.topicId == topicId }
            }.getOrNull() ?: return
            applyForumTopicState(conversation.roomId, latest)
            runCatching {
                apiClient.updateGroupTopicState(
                    roomId = conversation.roomId,
                    topicId = topicId,
                    draftText = text,
                    updateDraft = true,
                    baseDraftRevision = latest.draftRevision
                )
            }.getOrNull()?.let { state ->
                applyForumTopicState(conversation.roomId, state)
            }
        }
    }

    private fun isActivePendingTarget(target: PendingChatTarget): Boolean {
        val current = appState.activeConversation ?: return false
        return current.peerId == target.peerId &&
            current.roomId == target.roomId &&
            current.kind == target.kind
    }

    private fun currentIsoTimestamp(): String = Instant.now().toString()

    private fun shouldQueueForConnectivity(error: AloApiException): Boolean {
        return error.status == 0 || error.code == "CONNECTION_FAILED"
    }

    private fun shouldKeepPending(error: AloApiException): Boolean {
        if (shouldQueueForConnectivity(error)) return true
        return error.code in setOf(
            "E2EE_KEYS_MISSING",
            "E2EE_REQUESTER_DEVICE_NOT_REGISTERED",
            "LIBSIGNAL_KEYS_MISSING",
            "LIBSIGNAL_PREKEYS_MISSING",
            "LIBSIGNAL_GROUP_KEYS_MISSING",
            "LIBSIGNAL_REQUESTER_DEVICE_NOT_REGISTERED"
        )
    }

    private fun retryDelayMs(error: AloApiException): Long {
        return if (shouldQueueForConnectivity(error)) 2_500L else 15_000L
    }

    private fun mergePendingLocalMessages(refreshed: AloConversationDetail): AloConversationDetail {
        val target = pendingTarget(refreshed)
        val deleteProjected = projectPendingDeletes(refreshed)
        val editProjectedItems = AloPendingChatProjection.applyingPendingEdits(
            messages = deleteProjected.items,
            bodyByMessageId = pendingEditedBodies(target),
            editedAt = currentIsoTimestamp()
        )
        val reactionProjectedItems = AloPendingChatProjection.applyingDesiredReactions(
            messages = editProjectedItems,
            desiredEmojiByMessageId = pendingDesiredReactions(target),
            me = reactionVoterSelf()
        )
        val projected = deleteProjected.copy(items = reactionProjectedItems)
        val pendingLocalMessages = pendingLocalMessages(target)
        if (pendingLocalMessages.isEmpty()) return projected
        val merged = AloPendingChatProjection.mergingPendingMessages(
            serverMessages = projected.items,
            pendingMessages = pendingLocalMessages
        )
            .sortedBy { parseIsoTimestampMillis(it.createdAt) }
        return projected.copy(items = merged)
    }

    private fun pendingLocalMessages(target: PendingChatTarget): List<AloChatMessage> {
        val me = appState.bootstrap?.me ?: return emptyList()
        return pendingChatOperations.mapNotNull { operation ->
            when (operation) {
                is PendingChatOperation.SendText -> {
                    if (operation.target != target) return@mapNotNull null
                    val markup = AloMessageMarkup.parse(operation.body)
                    AloChatMessage(
                        id = operation.localId,
                        roomId = target.roomId,
                        senderId = me.id,
                        recipientId = if (target.kind == "direct") target.peerId else 0,
                        body = markup.body,
                        attachmentUrl = "",
                        attachmentType = "",
                        attachmentName = "",
                        createdAt = operation.createdAt,
                        editedAt = "",
                        readAt = "",
                        sender = me.asConversationPeer(),
                        entities = markup.entities,
                        replyToMessageId = operation.replyToMessageId,
                        topicId = operation.topicId,
                        scheduledAt = operation.scheduledAt,
                        sendWhenOnline = operation.sendWhenOnline,
                        silent = operation.silent,
                        deliveryState = if (operation.scheduledAt.isNotBlank() || operation.sendWhenOnline) "scheduled" else ""
                    )
                }
                is PendingChatOperation.SendDraft -> {
                    if (operation.target != target) return@mapNotNull null
                    val markup = AloMessageMarkup.parse(operation.body)
                    val localAttachments = if (operation.attachments.isEmpty()) {
                        operation.uploadedAttachments
                    } else {
                        operation.attachments.map { it.toLocalOutgoingAttachment() }
                    }
                    val primaryAttachment = localAttachments.firstOrNull()
                    AloChatMessage(
                        id = operation.localId,
                        roomId = target.roomId,
                        senderId = me.id,
                        recipientId = if (target.kind == "direct") target.peerId else 0,
                        body = markup.body,
                        attachmentUrl = primaryAttachment?.url.orEmpty(),
                        attachmentType = primaryAttachment?.type.orEmpty(),
                        attachmentName = primaryAttachment?.name.orEmpty(),
                        attachments = localAttachments,
                        createdAt = operation.createdAt,
                        editedAt = "",
                        readAt = "",
                        sender = me.asConversationPeer(),
                        entities = markup.entities,
                        selfDestructSeconds = operation.selfDestructSeconds,
                        replyToMessageId = operation.replyToMessageId,
                        topicId = operation.topicId,
                        scheduledAt = operation.scheduledAt,
                        sendWhenOnline = operation.sendWhenOnline,
                        silent = operation.silent,
                        deliveryState = if (operation.scheduledAt.isNotBlank() || operation.sendWhenOnline) "scheduled" else ""
                    )
                }
                else -> null
            }
        }
    }

    private fun AloCurrentUser.asConversationPeer(): AloConversationPeer {
        return AloConversationPeer(
            id = id,
            name = name,
            username = username,
            avatarUrl = avatarUrl,
            verified = verified,
            entityType = "user"
        )
    }

    private fun projectPendingDeletes(
        refreshed: AloConversationDetail
    ): AloConversationDetail {
        val target = pendingTarget(refreshed)
        val deletedMessageIds = pendingChatOperations
            .asSequence()
            .filterIsInstance<PendingChatOperation.Delete>()
            .filter { it.target == target }
            .flatMap { it.messageIds.asSequence() }
            .toSet()
        val visibleMessages = AloPendingChatProjection.removingDeletedMessages(
            messages = refreshed.items,
            deletedMessageIds = deletedMessageIds
        )
        val canceledScheduledMessageIds = pendingChatOperations
            .asSequence()
            .filterIsInstance<PendingChatOperation.CancelScheduled>()
            .filter { it.target == target }
            .map { it.scheduledMessageId }
            .toSet()
        val scheduledAtByMessageId = pendingChatOperations
            .asSequence()
            .filterIsInstance<PendingChatOperation.Reschedule>()
            .filter { it.target == target }
            .associate { it.scheduledMessageId to it.scheduledAt }
        val projectedMessages = AloPendingChatProjection.applyingScheduledChanges(
            messages = visibleMessages,
            canceledScheduledMessageIds = canceledScheduledMessageIds,
            scheduledAtByMessageId = scheduledAtByMessageId
        )
        return if (projectedMessages == refreshed.items) {
            refreshed
        } else {
            refreshed.copy(items = projectedMessages)
        }
    }

    private fun pendingEditedBodies(target: PendingChatTarget): Map<Int, String> {
        return pendingChatOperations
            .asSequence()
            .filterIsInstance<PendingChatOperation.Edit>()
            .filter { it.target == target }
            .associate { it.messageId to it.body }
    }

    private fun pendingDesiredReactions(target: PendingChatTarget): Map<Int, String> {
        return pendingChatOperations
            .asSequence()
            .filterIsInstance<PendingChatOperation.Reaction>()
            .filter { it.target == target }
            .associate { it.messageId to it.emoji }
    }

    private fun hasPendingMutation(target: PendingChatTarget, messageId: Int): Boolean {
        return pendingChatOperations.any { operation ->
            when (operation) {
                is PendingChatOperation.Reaction ->
                    operation.target == target && operation.messageId == messageId
                is PendingChatOperation.Edit ->
                    operation.target == target && operation.messageId == messageId
                is PendingChatOperation.Delete ->
                    operation.target == target && messageId in operation.messageIds
                is PendingChatOperation.Reschedule ->
                    operation.target == target && operation.messageId == messageId
                is PendingChatOperation.CancelScheduled ->
                    operation.target == target && operation.messageId == messageId
                else -> false
            }
        }
    }

    private fun hasPendingEdit(target: PendingChatTarget, messageId: Int): Boolean {
        return pendingChatOperations.any { operation ->
            operation is PendingChatOperation.Edit &&
                operation.target == target &&
                operation.messageId == messageId
        }
    }

    private fun settlePendingMessageIndicator(target: PendingChatTarget, messageId: Int) {
        if (hasPendingMutation(target, messageId)) return
        appState = appState.copy(
            pendingChatMessageIds = appState.pendingChatMessageIds - messageId
        )
    }

    private fun replaceActiveConversationItems(
        target: PendingChatTarget,
        transform: (List<AloChatMessage>) -> List<AloChatMessage>
    ) {
        val current = appState.activeConversation ?: return
        if (!isActivePendingTarget(target)) return
        appState = appState.copy(
            activeConversation = current.copy(items = transform(current.items))
        )
    }

    private fun reprojectActiveConversation(target: PendingChatTarget) {
        val current = appState.activeConversation ?: return
        if (!isActivePendingTarget(target)) return
        appState = appState.copy(activeConversation = mergePendingLocalMessages(current))
    }

    private fun applyOptimisticReaction(messageId: Int, desiredEmoji: String) {
        val conversation = appState.activeConversation ?: return
        val target = pendingTarget(conversation)
        replaceActiveConversationItems(target) { messages ->
            AloPendingChatProjection.applyingDesiredReactions(
                messages = messages,
                desiredEmojiByMessageId = mapOf(messageId to desiredEmoji),
                me = reactionVoterSelf()
            )
        }
    }

    /** The local user as one of the people on a reaction chip (see AloPendingChatProjection). */
    private fun reactionVoterSelf(): AloConversationPeer? = appState.bootstrap?.me?.let { me ->
        AloConversationPeer(id = me.id, name = me.name, username = me.username, avatarUrl = me.avatarUrl, verified = me.verified)
    }

    private fun enqueuePendingChatOperation(operation: PendingChatOperation) {
        if (pendingChatOperationsOwnerUserId == null) {
            pendingChatOperationsOwnerUserId = appState.bootstrap?.me?.id ?: return
        }
        when (operation) {
            is PendingChatOperation.Reaction -> {
                val retained = pendingChatOperations.filterIndexed { index, pending ->
                    if (isProcessingPendingChatOperations && index == 0) {
                        true
                    } else {
                        pending !is PendingChatOperation.Reaction ||
                            pending.target != operation.target ||
                            pending.messageId != operation.messageId
                    }
                }
                pendingChatOperations.clear()
                pendingChatOperations.addAll(retained)
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId,
                    errorMessage = null
                )
            }
            is PendingChatOperation.Edit -> {
                val retained = pendingChatOperations.filterIndexed { index, pending ->
                    if (isProcessingPendingChatOperations && index == 0) {
                        true
                    } else {
                        pending !is PendingChatOperation.Edit ||
                            pending.target != operation.target ||
                            pending.messageId != operation.messageId
                    }
                }
                pendingChatOperations.clear()
                pendingChatOperations.addAll(retained)
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId,
                    errorMessage = null
                )
            }
            is PendingChatOperation.SendText -> {
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.localId,
                    errorMessage = null
                )
            }
            is PendingChatOperation.SendDraft -> {
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.localId,
                    errorMessage = null
                )
            }
            is PendingChatOperation.Delete -> {
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageIds,
                    errorMessage = null
                )
            }
            is PendingChatOperation.Pin -> {
                pendingChatOperations.removeAll { pending ->
                    pending is PendingChatOperation.Pin &&
                        pending.target == operation.target &&
                        pending.messageId == operation.messageId
                }
                appState = appState.copy(errorMessage = null)
            }
            is PendingChatOperation.Reschedule -> {
                val retained = pendingChatOperations.filterIndexed { index, pending ->
                    if (isProcessingPendingChatOperations && index == 0) {
                        true
                    } else {
                        pending !is PendingChatOperation.Reschedule ||
                            pending.target != operation.target ||
                            pending.scheduledMessageId != operation.scheduledMessageId
                    }
                }
                pendingChatOperations.clear()
                pendingChatOperations.addAll(retained)
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId,
                    errorMessage = null
                )
            }
            is PendingChatOperation.CancelScheduled -> {
                val retained = pendingChatOperations.filterIndexed { index, pending ->
                    if (isProcessingPendingChatOperations && index == 0) {
                        true
                    } else {
                        val pendingScheduledMessageId = when (pending) {
                            is PendingChatOperation.Reschedule -> pending.scheduledMessageId
                            is PendingChatOperation.CancelScheduled -> pending.scheduledMessageId
                            else -> 0
                        }
                        pending.target != operation.target ||
                            pendingScheduledMessageId != operation.scheduledMessageId
                    }
                }
                pendingChatOperations.clear()
                pendingChatOperations.addAll(retained)
                appState = appState.copy(
                    pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId,
                    errorMessage = null
                )
            }
        }
        pendingChatOperations += operation
        persistPendingChatOperations()
        processPendingChatOperations()
    }

    private fun schedulePendingChatRetry(delayMs: Long = 2_500L) {
        pendingChatRetryJob?.cancel()
        pendingChatRetryJob = lifecycleScope.launch {
            delay(delayMs)
            pendingChatRetryJob = null
            processPendingChatOperations()
        }
    }

    private fun processPendingChatOperations() {
        if (isProcessingPendingChatOperations || pendingChatOperations.isEmpty()) return
        pendingChatRetryJob?.cancel()
        pendingChatRetryJob = null
        isProcessingPendingChatOperations = true
        lifecycleScope.launch {
            try {
                var operationsRemainingInPass = pendingChatOperations.size
                var deferredRetryDelayMs: Long? = null
                while (pendingChatOperations.isNotEmpty() && operationsRemainingInPass > 0) {
                    operationsRemainingInPass -= 1
                    val operation = pendingChatOperations.first()
                    if (operation is PendingChatOperation.SendDraft && operation.localId in pausedUploadIds) {
                        // A paused file waits at the back; the rest of the queue keeps going.
                        pendingChatOperations.removeAt(0)
                        pendingChatOperations += operation
                        continue
                    }
                    if (
                        (operation is PendingChatOperation.SendText || operation is PendingChatOperation.SendDraft) &&
                        isBlockedDirectTarget(operation.target)
                    ) {
                        discardBlockedPendingMessage(operation)
                        continue
                    }
                    try {
                        when (operation) {
                            is PendingChatOperation.SendText -> {
                                val voteEvent = AloPollCodec.decode(operation.body) as? AloPollEvent.Vote
                                if (operation.pollOnServer && voteEvent != null) {
                                    // A poll of a cloud chat is counted by the server: the vote is not a chat message.
                                    val canonical = apiClient.votePoll(voteEvent.pollId, voteEvent.optionIds)
                                    pendingChatOperations.removeAt(0)
                                    persistPendingChatOperations()
                                    appState = appState.copy(
                                        pendingChatMessageIds = appState.pendingChatMessageIds - operation.localId,
                                        isSendingMessage = false,
                                        errorMessage = null
                                    )
                                    replaceActiveConversationItems(operation.target) { messages ->
                                        messages.filterNot { message -> message.id == operation.localId }
                                    }
                                    applyCanonicalPollState(operation.target, canonical)
                                    refreshConversationIfCurrent(operation.target)
                                    continue
                                }
                                var preparedOperation: PendingChatOperation.SendText = operation
                                if (preparedOperation.encryptedPayload.isBlank() || isSavedTarget(operation.target)) {
                                    val encryptedPayload = apiClient.prepareMessagePayload(
                                        kind = operation.target.kind,
                                        peerId = operation.target.peerId,
                                        roomId = operation.target.roomId,
                                        body = operation.body,
                                        attachments = emptyList(),
                                        forwardFrom = null
                                    )
                                    preparedOperation = operation.copy(
                                        encryptedPayload = encryptedPayload.toString()
                                    )
                                    pendingChatOperations[0] = preparedOperation
                                    persistPendingChatOperations()
                                }
                                val acknowledgement = apiClient.sendPreparedMessage(
                                    kind = operation.target.kind,
                                    peerId = operation.target.peerId,
                                    roomId = operation.target.roomId,
                                    encryptedPayload = JSONObject(preparedOperation.encryptedPayload),
                                    replyToMessageId = operation.replyToMessageId,
                                    topicId = operation.topicId,
                                    clientMessageId = operation.clientMessageId,
                                    silent = operation.silent,
                                    scheduledAt = operation.scheduledAt,
                                    sendWhenOnline = preparedOperation.sendWhenOnline,
                                    scheduledPoll = operation.pollDefinition.takeIf {
                                        AloPollCodec.decode(operation.body) is AloPollEvent.Create
                                    }
                                )
                                when (val pollEvent = AloPollCodec.decode(operation.body)) {
                                    is AloPollEvent.Create -> if (!acknowledgement.scheduled) {
                                        apiClient.registerPoll(
                                            definition = operation.pollDefinition ?: pollEvent.definition,
                                            messageId = acknowledgement.messageId,
                                            kind = operation.target.kind,
                                            roomId = operation.target.roomId
                                        )
                                    }
                                    is AloPollEvent.Vote -> if (operation.pollDefinition?.isAnonymous == false) {
                                        apiClient.votePoll(
                                            pollId = pollEvent.pollId,
                                            optionIds = pollEvent.optionIds
                                        )
                                    }
                                    is AloPollEvent.Reveal -> Unit
                                    null -> Unit
                                }
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(
                                    pendingChatMessageIds = appState.pendingChatMessageIds - operation.localId,
                                    isSendingMessage = false,
                                    errorMessage = null
                                )
                                acknowledgePendingMessage(
                                    localId = operation.localId,
                                    target = operation.target,
                                    acknowledgement = acknowledgement
                                )
                                refreshConversationIfCurrent(operation.target)
                            }
                            is PendingChatOperation.SendDraft -> {
                                var preparedOperation: PendingChatOperation.SendDraft = operation
                                val outgoingAttachments = operation.uploadedAttachments.toMutableList()
                                // A cloud chat keeps files as they are; an old chat uploads them sealed.
                                val cloudUpload = outgoingAttachments.size < operation.attachments.size &&
                                    apiClient.cloudMessagesAllowed(
                                        operation.target.kind,
                                        operation.target.peerId,
                                        operation.target.roomId
                                    )
                                for (index in outgoingAttachments.size until operation.attachments.size) {
                                    val draft = operation.attachments[index]
                                    val reporter = AloUploadReporter(
                                        onProgress = { sent, total ->
                                            lifecycleScope.launch(Dispatchers.Main.immediate) {
                                                appState = appState.copy(
                                                    uploadProgress = appState.uploadProgress +
                                                        (operation.localId to AloUploadProgress(sent, total, index, operation.attachments.size))
                                                )
                                            }
                                        },
                                        shouldPause = { operation.localId in pausedUploadIds }
                                    )
                                    val uploaded = uploadMessageDraftAttachment(
                                        draft = draft,
                                        resumeCheckpoint = preparedOperation.uploadCheckpoint,
                                        reporter = reporter,
                                        cloud = cloudUpload
                                    ) { checkpoint ->
                                        preparedOperation = preparedOperation.copy(
                                            uploadCheckpoint = checkpoint
                                        )
                                        pendingChatOperations[0] = preparedOperation
                                        persistPendingChatOperations()
                                    }
                                    outgoingAttachments += outgoingAttachmentFor(draft, uploaded)
                                    preparedOperation = preparedOperation.copy(
                                        uploadedAttachments = outgoingAttachments.toList(),
                                        encryptedPayload = "",
                                        uploadCheckpoint = null
                                    )
                                    pendingChatOperations[0] = preparedOperation
                                    persistPendingChatOperations()
                                }
                                if (preparedOperation.encryptedPayload.isBlank() || isSavedTarget(operation.target)) {
                                    val encryptedPayload = apiClient.prepareMessagePayload(
                                        kind = operation.target.kind,
                                        peerId = operation.target.peerId,
                                        roomId = operation.target.roomId,
                                        body = operation.body,
                                        attachments = outgoingAttachments,
                                        forwardFrom = null
                                    )
                                    preparedOperation = preparedOperation.copy(
                                        uploadedAttachments = outgoingAttachments.toList(),
                                        encryptedPayload = encryptedPayload.toString()
                                    )
                                    pendingChatOperations[0] = preparedOperation
                                    persistPendingChatOperations()
                                }
                                val acknowledgement = apiClient.sendPreparedMessage(
                                    kind = operation.target.kind,
                                    peerId = operation.target.peerId,
                                    roomId = operation.target.roomId,
                                    encryptedPayload = JSONObject(preparedOperation.encryptedPayload),
                                    attachments = outgoingAttachments,
                                    selfDestructSeconds = operation.selfDestructSeconds,
                                    replyToMessageId = operation.replyToMessageId,
                                    topicId = operation.topicId,
                                    clientMessageId = operation.clientMessageId,
                                    silent = operation.silent,
                                    scheduledAt = operation.scheduledAt,
                                    sendWhenOnline = preparedOperation.sendWhenOnline
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                operation.attachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
                                deletePersistedDraftAttachments(
                                    clientMessageId = operation.clientMessageId,
                                    count = operation.attachments.size
                                )
                                appState = appState.copy(
                                    pendingChatMessageIds = appState.pendingChatMessageIds - operation.localId,
                                    uploadProgress = appState.uploadProgress - operation.localId,
                                    isSendingMessage = false,
                                    errorMessage = null
                                )
                                acknowledgePendingMessage(
                                    localId = operation.localId,
                                    target = operation.target,
                                    acknowledgement = acknowledgement,
                                    serverAttachments = preparedOperation.uploadedAttachments
                                )
                                refreshConversationIfCurrent(operation.target)
                            }
                            is PendingChatOperation.Reaction -> {
                                val reactions = apiClient.toggleMessageReaction(
                                    kind = operation.target.kind,
                                    roomId = operation.target.roomId,
                                    messageId = operation.messageId,
                                    desiredEmoji = operation.emoji,
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(
                                    errorMessage = null
                                )
                                settlePendingMessageIndicator(operation.target, operation.messageId)
                                replaceActiveConversationItems(operation.target) { messages ->
                                    messages.map { message ->
                                        if (message.id == operation.messageId) {
                                            message.copy(reactions = reactions)
                                        } else {
                                            message
                                        }
                                    }
                                }
                                reprojectActiveConversation(operation.target)
                            }
                            is PendingChatOperation.Edit -> {
                                var preparedOperation: PendingChatOperation.Edit = operation
                                if (preparedOperation.encryptedPayload.isBlank()) {
                                    // An old encrypted message is edited encrypted; a cloud one with plain body and entities.
                                    val encryptedPayload = apiClient.prepareMessagePayload(
                                        kind = operation.target.kind,
                                        peerId = operation.target.peerId,
                                        roomId = operation.target.roomId,
                                        body = operation.body,
                                        attachments = if (operation.sourceEncrypted) operation.attachments else emptyList(),
                                        forwardFrom = operation.forwardFrom,
                                        forceEncrypted = operation.sourceEncrypted
                                    )
                                    preparedOperation = operation.copy(
                                        encryptedPayload = encryptedPayload.toString()
                                    )
                                    pendingChatOperations[0] = preparedOperation
                                    persistPendingChatOperations()
                                }
                                val editedAt = apiClient.sendPreparedMessageEdit(
                                    kind = operation.target.kind,
                                    roomId = operation.target.roomId,
                                    messageId = operation.messageId,
                                    encryptedPayload = JSONObject(preparedOperation.encryptedPayload),
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(
                                    errorMessage = null
                                )
                                settlePendingMessageIndicator(operation.target, operation.messageId)
                                replaceActiveConversationItems(operation.target) { messages ->
                                    messages.map { message ->
                                        if (message.id == operation.messageId) {
                                            message.copy(
                                                body = operation.body,
                                                editedAt = editedAt.ifBlank { message.editedAt }
                                            )
                                        } else {
                                            message
                                        }
                                    }
                                }
                                reprojectActiveConversation(operation.target)
                            }
                            is PendingChatOperation.Delete -> {
                                apiClient.deleteMessages(
                                    kind = operation.target.kind,
                                    roomId = operation.target.roomId,
                                    messageIds = operation.messageIds,
                                    forEveryone = operation.forEveryone,
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(errorMessage = null)
                                operation.messageIds.forEach { messageId ->
                                    settlePendingMessageIndicator(operation.target, messageId)
                                }
                            }
                            is PendingChatOperation.Pin -> {
                                apiClient.toggleMessagePin(
                                    kind = operation.target.kind,
                                    roomId = operation.target.roomId,
                                    messageId = operation.messageId,
                                    pinned = operation.pinned,
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                refreshConversationIfCurrent(operation.target)
                            }
                            is PendingChatOperation.Reschedule -> {
                                apiClient.updateScheduledMessage(
                                    scheduledMessageId = operation.scheduledMessageId,
                                    scheduledAt = operation.scheduledAt,
                                    silent = operation.silent,
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(
                                    errorMessage = null
                                )
                                settlePendingMessageIndicator(operation.target, operation.messageId)
                                refreshConversationIfCurrent(operation.target)
                            }
                            is PendingChatOperation.CancelScheduled -> {
                                apiClient.cancelScheduledMessage(
                                    scheduledMessageId = operation.scheduledMessageId,
                                    operationId = operation.operationId
                                )
                                pendingChatOperations.removeAt(0)
                                persistPendingChatOperations()
                                appState = appState.copy(
                                    errorMessage = null
                                )
                                settlePendingMessageIndicator(operation.target, operation.messageId)
                                refreshConversationIfCurrent(operation.target)
                            }
                        }
                    } catch (paused: AloUploadPausedException) {
                        // Paused between pieces: the checkpoint is saved, and it resumes from there.
                        val pausedOperation = pendingChatOperations.removeAt(0)
                        pendingChatOperations += pausedOperation
                        persistPendingChatOperations()
                        continue
                    } catch (error: AloApiException) {
                        if (error.status == 401) {
                            handleSessionExpired()
                            break
                        }
                        if (shouldQueueForConnectivity(error)) {
                            schedulePendingChatRetry(retryDelayMs(error))
                            break
                        }
                        if (shouldKeepPending(error)) {
                            val deferredOperation = pendingChatOperations.removeAt(0)
                            pendingChatOperations += deferredOperation
                            persistPendingChatOperations()
                            val delay = retryDelayMs(error)
                            deferredRetryDelayMs = minOf(deferredRetryDelayMs ?: delay, delay)
                            continue
                        }
                        pendingChatOperations.removeAt(0)
                        persistPendingChatOperations()
                        val failedIds = when (operation) {
                            is PendingChatOperation.SendText -> setOf(operation.localId)
                            is PendingChatOperation.SendDraft -> setOf(operation.localId)
                            is PendingChatOperation.Reaction -> setOf(operation.messageId)
                            is PendingChatOperation.Edit -> setOf(operation.messageId)
                            is PendingChatOperation.Delete -> operation.messageIds.toSet()
                            is PendingChatOperation.Pin -> emptySet()
                            is PendingChatOperation.Reschedule -> setOf(operation.messageId)
                            is PendingChatOperation.CancelScheduled -> setOf(operation.messageId)
                        }
                        val settledIds = failedIds.filterNot { messageId ->
                            hasPendingMutation(operation.target, messageId)
                        }.toSet()
                        if (operation is PendingChatOperation.SendDraft) {
                            operation.attachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
                            deletePersistedDraftAttachments(
                                clientMessageId = operation.clientMessageId,
                                count = operation.attachments.size
                            )
                        }
                        if (operation is PendingChatOperation.SendText || operation is PendingChatOperation.SendDraft) {
                            replaceActiveConversationItems(operation.target) { messages ->
                                messages.filterNot { message -> failedIds.contains(message.id) }
                            }
                        } else if (operation is PendingChatOperation.Edit) {
                            if (!hasPendingEdit(operation.target, operation.messageId)) {
                                replaceActiveConversationItems(operation.target) { messages ->
                                    messages.map { message ->
                                        if (message.id == operation.messageId) {
                                            message.copy(body = operation.originalBody)
                                        } else {
                                            message
                                        }
                                    }
                                }
                            }
                        } else if (operation is PendingChatOperation.Pin) {
                            refreshConversationIfCurrent(operation.target)
                        } else if (
                            operation is PendingChatOperation.Reschedule ||
                            operation is PendingChatOperation.CancelScheduled
                        ) {
                            refreshConversationIfCurrent(operation.target)
                        }
                        appState = appState.copy(
                            pendingChatMessageIds = appState.pendingChatMessageIds - settledIds,
                            uploadProgress = appState.uploadProgress - settledIds,
                            isSendingMessage = false,
                            errorMessage = error.message
                        )
                        if (operation is PendingChatOperation.Reaction || operation is PendingChatOperation.Edit) {
                            refreshConversationIfCurrent(operation.target)
                        }
                    } catch (_error: Exception) {
                        val deferredOperation = pendingChatOperations.removeAt(0)
                        pendingChatOperations += deferredOperation
                        persistPendingChatOperations()
                        deferredRetryDelayMs = minOf(deferredRetryDelayMs ?: 2_500L, 2_500L)
                    }
                }
                if (pendingChatOperations.isNotEmpty() && pendingChatRetryJob == null) {
                    schedulePendingChatRetry(deferredRetryDelayMs ?: 15_000L)
                }
            } finally {
                isProcessingPendingChatOperations = false
            }
        }
    }

    private fun discardBlockedPendingMessage(operation: PendingChatOperation) {
        if (pendingChatOperations.firstOrNull() != operation) return
        pendingChatOperations.removeAt(0)
        persistPendingChatOperations()

        val localMessageId = when (operation) {
            is PendingChatOperation.SendText -> operation.localId
            is PendingChatOperation.SendDraft -> operation.localId
            else -> return
        }
        if (operation is PendingChatOperation.SendDraft) {
            operation.attachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
            deletePersistedDraftAttachments(
                clientMessageId = operation.clientMessageId,
                count = operation.attachments.size
            )
        }
        replaceActiveConversationItems(operation.target) { messages ->
            messages.filterNot { message -> message.id == localMessageId }
        }
        appState = appState.copy(
            pendingChatMessageIds = appState.pendingChatMessageIds - localMessageId,
            isSendingMessage = false
        )
    }

    private fun acknowledgePendingMessage(
        localId: Int,
        target: PendingChatTarget,
        acknowledgement: AloMessageSendAcknowledgement,
        /** Files as the server keeps them. The draft copies are deleted on success, so the row must stop pointing at them. */
        serverAttachments: List<AloOutgoingMessageAttachment> = emptyList()
    ) {
        // Cloud files only: an end-to-end file on the server is ciphertext, and its row keeps the decrypted local copy.
        val uploaded = serverAttachments
            .takeIf { list -> list.isNotEmpty() && list.all { it.mediaEncryption.isEmpty() && it.url.isNotBlank() } }
            ?.map { it.copy(url = AloServerConfiguration.apiUrl(it.url)) }
        replaceActiveConversationItems(target) { messages ->
            val alreadyContainsServerMessage = acknowledgement.messageId > 0 &&
                messages.any { it.id == acknowledgement.messageId }
            messages.mapNotNull { message ->
                if (message.id != localId) {
                    // The server copy arrived first: it takes over the local row.
                    if (alreadyContainsServerMessage && message.id == acknowledgement.messageId && message.localKey == 0) {
                        message.copy(localKey = localId)
                    } else {
                        message
                    }
                } else if (alreadyContainsServerMessage) {
                    null
                } else {
                    (if (uploaded != null && message.attachments.size == uploaded.size) {
                        message.copy(
                            attachments = uploaded,
                            attachmentUrl = uploaded.first().url,
                            attachmentType = uploaded.first().type,
                            attachmentName = uploaded.first().name
                        )
                    } else message).copy(
                        localKey = localId,
                        id = acknowledgement.messageId.takeIf { it > 0 } ?: message.id,
                        createdAt = acknowledgement.createdAt.ifBlank { message.createdAt },
                        scheduledMessageId = acknowledgement.scheduledMessageId,
                        scheduledAt = acknowledgement.scheduledAt,
                        sendWhenOnline = acknowledgement.sendWhenOnline,
                        deliveryState = if (acknowledgement.scheduled) "scheduled" else message.deliveryState
                    )
                }
            }
        }
    }

    private fun isSavedTarget(target: PendingChatTarget): Boolean {
        return target.kind != "group" &&
            target.roomId <= 0 &&
            target.peerId == appState.bootstrap?.me?.id
    }

    private fun persistPendingChatOperations() {
        val ownerUserId = pendingChatOperationsOwnerUserId ?: return
        runCatching {
            val records = JSONArray()
            pendingChatOperations.forEach { operation ->
                records.put(pendingChatOperationJson(operation, ownerUserId))
            }
            secureOutboxStore.save(records, ownerUserId)
        }
    }

    private fun activatePendingChatOutbox(ownerUserId: Int) {
        if (ownerUserId <= 0 || pendingChatOperationsOwnerUserId == ownerUserId) return
        pendingChatOperations.clear()
        appState = appState.copy(pendingChatMessageIds = emptySet())
        nextLocalMessageId = -1
        pendingChatOperationsOwnerUserId = ownerUserId
        restorePendingChatOperations(ownerUserId)
    }

    private fun restorePendingChatOperations(ownerUserId: Int) {
        if (pendingChatOperations.isNotEmpty()) return
        val records = secureOutboxStore.load(ownerUserId)
        for (index in 0 until records.length()) {
            val operation = runCatching {
                pendingChatOperationFromJson(records.getJSONObject(index), ownerUserId)
            }.getOrNull() ?: continue
            pendingChatOperations += operation
            when (operation) {
                is PendingChatOperation.SendText -> {
                    nextLocalMessageId = minOf(nextLocalMessageId, operation.localId - 1)
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.localId
                    )
                }
                is PendingChatOperation.SendDraft -> {
                    nextLocalMessageId = minOf(nextLocalMessageId, operation.localId - 1)
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.localId
                    )
                }
                is PendingChatOperation.Reaction -> {
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId
                    )
                }
                is PendingChatOperation.Edit -> {
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId
                    )
                }
                is PendingChatOperation.Delete -> {
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageIds
                    )
                }
                is PendingChatOperation.Reschedule -> {
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId
                    )
                }
                is PendingChatOperation.CancelScheduled -> {
                    appState = appState.copy(
                        pendingChatMessageIds = appState.pendingChatMessageIds + operation.messageId
                    )
                }
                is PendingChatOperation.Pin -> Unit
            }
        }
        persistPendingChatOperations()
    }

    private fun clearPendingChatOutboxForLogout() {
        val ownerUserId = pendingChatOperationsOwnerUserId ?: appState.bootstrap?.me?.id
        pendingChatOperations.filterIsInstance<PendingChatOperation.SendDraft>()
            .flatMap(PendingChatOperation.SendDraft::attachments)
            .forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
        if (ownerUserId != null && ownerUserId > 0) {
            secureOutboxStore.clear(ownerUserId)
        }
        pendingChatOperations.clear()
        pendingChatOperationsOwnerUserId = null
        nextLocalMessageId = -1
    }

    private fun pendingChatOperationJson(
        operation: PendingChatOperation,
        ownerUserId: Int
    ): JSONObject {
        return when (operation) {
            is PendingChatOperation.SendText -> JSONObject()
                .put("type", "sendText")
                .put("localId", operation.localId)
                .put("target", pendingChatTargetJson(operation.target))
                .put("body", operation.body)
                .put("createdAt", operation.createdAt)
                .put("replyToMessageId", operation.replyToMessageId)
                .put("topicId", operation.topicId)
                .put("clientMessageId", operation.clientMessageId)
                .put("encryptedPayload", operation.encryptedPayload)
                .put("silent", operation.silent)
                .put("scheduledAt", operation.scheduledAt)
                .put("sendWhenOnline", operation.sendWhenOnline)
                .put("pollOnServer", operation.pollOnServer)
                .also { json ->
                    operation.pollDefinition?.let { json.put("pollDefinition", pollDefinitionJson(it)) }
                }

            is PendingChatOperation.SendDraft -> {
                val attachments = JSONArray()
                operation.attachments.forEachIndexed { index, attachment ->
                val fileId = "${operation.clientMessageId}-$index"
                    val record = JSONObject()
                        .put("fileId", fileId)
                        .put("attachmentType", attachment.attachmentType)
                        .put("mimeType", attachment.mimeType)
                        .put("name", attachment.name)
                        .put("durationSec", attachment.durationSec)
                        .put("isVideoCircle", attachment.isVideoCircle)
                        .put("width", attachment.width)
                        .put("height", attachment.height)
                    if (attachment.encryptedSourcePath.isNotBlank()) {
                        // New drafts point at an app-private ciphertext file.
                        // Persisting its path and envelope metadata avoids
                        // loading the attachment into memory on restart.
                        record
                            .put("encryptedSourcePath", attachment.encryptedSourcePath)
                            .put("mediaEncryption", JSONObject(attachment.mediaEncryption))
                            .put("encryptedChecksum", attachment.encryptedChecksum)
                    } else {
                        // Compatibility path for drafts created by earlier
                        // builds. New attachments never take this branch.
                        secureOutboxStore.saveAttachment(
                            readDraftAttachmentBytes(attachment),
                            fileId,
                            ownerUserId
                        )
                    }
                    attachments.put(record)
                }
                JSONObject()
                    .put("type", "sendDraft")
                    .put("localId", operation.localId)
                    .put("target", pendingChatTargetJson(operation.target))
                    .put("body", operation.body)
                    .put("createdAt", operation.createdAt)
                    .put("attachments", attachments)
                    .put("selfDestructSeconds", operation.selfDestructSeconds)
                    .put("replyToMessageId", operation.replyToMessageId)
                    .put("topicId", operation.topicId)
                    .put(
                        "uploadedAttachments",
                        JSONArray().also { array ->
                            operation.uploadedAttachments.forEach { attachment ->
                                array.put(outgoingAttachmentJson(attachment))
                            }
                        }
                    )
                    .put("clientMessageId", operation.clientMessageId)
                    .put("encryptedPayload", operation.encryptedPayload)
                    .also { json ->
                        operation.uploadCheckpoint?.let { checkpoint ->
                            json.put("uploadCheckpoint", JSONObject()
                                .put("uploadId", checkpoint.uploadId)
                                .put("offset", checkpoint.offset)
                                .put("total", checkpoint.total)
                                .put("checksum", checkpoint.checksum)
                            )
                        }
                    }
                    .put("silent", operation.silent)
                    .put("scheduledAt", operation.scheduledAt)
                    .put("sendWhenOnline", operation.sendWhenOnline)
            }

            is PendingChatOperation.Reaction -> JSONObject()
                .put("type", "reaction")
                .put("target", pendingChatTargetJson(operation.target))
                .put("messageId", operation.messageId)
                .put("emoji", operation.emoji)
                .put("operationId", operation.operationId)

            is PendingChatOperation.Edit -> JSONObject()
                .put("type", "edit")
                .put("target", pendingChatTargetJson(operation.target))
                .put("messageId", operation.messageId)
                .put("originalBody", operation.originalBody)
                .put("body", operation.body)
                .put(
                    "attachments",
                    JSONArray().also { array ->
                        operation.attachments.forEach { attachment ->
                            array.put(outgoingAttachmentJson(attachment))
                        }
                    }
                )
                .put("forwardFrom", operation.forwardFrom?.let(::messageForwardSourceJson))
                .put("operationId", operation.operationId)
                .put("encryptedPayload", operation.encryptedPayload)
                .put("sourceEncrypted", operation.sourceEncrypted)

            is PendingChatOperation.Delete -> JSONObject()
                .put("type", "delete")
                .put("target", pendingChatTargetJson(operation.target))
                .put(
                    "messageIds",
                    JSONArray().also { array -> operation.messageIds.forEach(array::put) }
                )
                .put("forEveryone", operation.forEveryone)
                .put("operationId", operation.operationId)

            is PendingChatOperation.Pin -> JSONObject()
                .put("type", "pin")
                .put("target", pendingChatTargetJson(operation.target))
                .put("messageId", operation.messageId)
                .put("pinned", operation.pinned)
                .put("operationId", operation.operationId)

            is PendingChatOperation.Reschedule -> JSONObject()
                .put("type", "scheduledReschedule")
                .put("target", pendingChatTargetJson(operation.target))
                .put("messageId", operation.messageId)
                .put("scheduledMessageId", operation.scheduledMessageId)
                .put("originalScheduledAt", operation.originalScheduledAt)
                .put("scheduledAt", operation.scheduledAt)
                .put("silent", operation.silent)
                .put("operationId", operation.operationId)

            is PendingChatOperation.CancelScheduled -> JSONObject()
                .put("type", "scheduledCancel")
                .put("target", pendingChatTargetJson(operation.target))
                .put("messageId", operation.messageId)
                .put("scheduledMessageId", operation.scheduledMessageId)
                .put("operationId", operation.operationId)
        }
    }

    private fun pendingChatOperationFromJson(
        record: JSONObject,
        ownerUserId: Int
    ): PendingChatOperation? {
        val target = pendingChatTargetFromJson(record.optJSONObject("target") ?: return null) ?: return null
        return when (record.optString("type")) {
            "sendText" -> {
                val localId = record.optInt("localId")
                val clientMessageId = record.optString("clientMessageId")
                if (localId >= 0 || !isValidClientOperationId(clientMessageId)) return null
                PendingChatOperation.SendText(
                    localId = localId,
                    target = target,
                    body = record.optString("body"),
                    createdAt = record.optString("createdAt").ifBlank { currentIsoTimestamp() },
                    replyToMessageId = record.optInt("replyToMessageId").coerceAtLeast(0),
                    topicId = record.optInt("topicId").coerceAtLeast(0),
                    clientMessageId = clientMessageId,
                    encryptedPayload = record.optString("encryptedPayload"),
                    pollDefinition = record.optJSONObject("pollDefinition")?.let(::pollDefinitionFromJson),
                    silent = record.optBoolean("silent"),
                    scheduledAt = record.optString("scheduledAt"),
                    sendWhenOnline = record.optBoolean("sendWhenOnline"),
                    pollOnServer = record.optBoolean("pollOnServer")
                )
            }

            "sendDraft" -> {
                val localId = record.optInt("localId")
                val clientMessageId = record.optString("clientMessageId")
                if (localId >= 0 || !isValidClientOperationId(clientMessageId)) return null
                val attachmentRecords = record.optJSONArray("attachments") ?: JSONArray()
                val attachments = buildList {
                    for (index in 0 until attachmentRecords.length()) {
                        val item = attachmentRecords.getJSONObject(index)
                        restoreDraftAttachment(item, ownerUserId)?.let(::add)
                    }
                }
                if (attachments.size != attachmentRecords.length()) return null
                val uploadedRecords = record.optJSONArray("uploadedAttachments") ?: JSONArray()
                val uploadedAttachments = buildList {
                    for (index in 0 until uploadedRecords.length()) {
                        outgoingAttachmentFromJson(uploadedRecords.getJSONObject(index))?.let(::add)
                    }
                }
                if (uploadedAttachments.size != uploadedRecords.length()) return null
                PendingChatOperation.SendDraft(
                    localId = localId,
                    target = target,
                    body = record.optString("body"),
                    createdAt = record.optString("createdAt").ifBlank { currentIsoTimestamp() },
                    attachments = attachments,
                    uploadedAttachments = uploadedAttachments,
                    selfDestructSeconds = record.optInt("selfDestructSeconds").coerceAtLeast(0),
                    replyToMessageId = record.optInt("replyToMessageId").coerceAtLeast(0),
                    topicId = record.optInt("topicId").coerceAtLeast(0),
                    clientMessageId = clientMessageId,
                    encryptedPayload = record.optString("encryptedPayload"),
                    uploadCheckpoint = record.optJSONObject("uploadCheckpoint")
                        ?.let(::uploadCheckpointFromJson),
                    silent = record.optBoolean("silent"),
                    scheduledAt = record.optString("scheduledAt"),
                    sendWhenOnline = record.optBoolean("sendWhenOnline")
                )
            }

            "reaction" -> PendingChatOperation.Reaction(
                target = target,
                messageId = record.optInt("messageId"),
                emoji = record.optString("emoji"),
                operationId = pendingOperationId(record)
            )

            "edit" -> {
                val attachmentsJson = record.optJSONArray("attachments") ?: JSONArray()
                val attachments = buildList {
                    for (index in 0 until attachmentsJson.length()) {
                        outgoingAttachmentFromJson(attachmentsJson.getJSONObject(index))?.let(::add)
                    }
                }
                if (attachments.size != attachmentsJson.length()) return null
                PendingChatOperation.Edit(
                    target = target,
                    messageId = record.optInt("messageId"),
                    originalBody = record.optString("originalBody"),
                    body = record.optString("body"),
                    attachments = attachments,
                    forwardFrom = record.optJSONObject("forwardFrom")?.let(::messageForwardSourceFromJson),
                    operationId = pendingOperationId(record),
                    encryptedPayload = record.optString("encryptedPayload"),
                    sourceEncrypted = record.optBoolean("sourceEncrypted")
                )
            }

            "delete" -> {
                val ids = record.optJSONArray("messageIds") ?: JSONArray()
                PendingChatOperation.Delete(
                    target = target,
                    messageIds = buildList {
                        for (index in 0 until ids.length()) add(ids.optInt(index))
                    }.filter { it > 0 },
                    forEveryone = record.optBoolean("forEveryone"),
                    operationId = pendingOperationId(record)
                )
            }

            "pin" -> PendingChatOperation.Pin(
                target = target,
                messageId = record.optInt("messageId"),
                pinned = record.optBoolean("pinned"),
                operationId = pendingOperationId(record)
            ).takeIf { it.messageId > 0 }

            "scheduledReschedule" -> PendingChatOperation.Reschedule(
                target = target,
                messageId = record.optInt("messageId"),
                scheduledMessageId = record.optInt("scheduledMessageId"),
                originalScheduledAt = record.optString("originalScheduledAt"),
                scheduledAt = record.optString("scheduledAt"),
                silent = record.optBoolean("silent"),
                operationId = pendingOperationId(record)
            ).takeIf {
                it.messageId != 0 && it.scheduledMessageId > 0 && it.scheduledAt.isNotBlank()
            }

            "scheduledCancel" -> PendingChatOperation.CancelScheduled(
                target = target,
                messageId = record.optInt("messageId"),
                scheduledMessageId = record.optInt("scheduledMessageId"),
                operationId = pendingOperationId(record)
            ).takeIf { it.messageId != 0 && it.scheduledMessageId > 0 }

            else -> null
        }
    }

    private fun pendingChatTargetJson(target: PendingChatTarget): JSONObject {
        return JSONObject()
            .put("peerId", target.peerId)
            .put("roomId", target.roomId)
            .put("kind", target.kind)
    }

    private fun pendingChatTargetFromJson(record: JSONObject): PendingChatTarget? {
        val target = PendingChatTarget(
            peerId = record.optInt("peerId"),
            roomId = record.optInt("roomId"),
            kind = record.optString("kind")
        )
        if ((target.peerId <= 0 && target.roomId <= 0) || target.kind.isBlank()) return null
        return target
    }

    private fun uploadCheckpointFromJson(record: JSONObject): AloResumableUploadCheckpoint? {
        val uploadId = record.optString("uploadId").lowercase()
        val offset = record.optLong("offset", -1L)
        val total = record.optLong("total", -1L)
        val checksum = record.optString("checksum").lowercase()
        return AloResumableUploadCheckpoint(
            uploadId = uploadId,
            offset = offset,
            total = total,
            checksum = checksum
        ).takeIf {
            it.uploadId.matches(Regex("[a-f0-9]{32}")) &&
                it.offset in 0L..it.total &&
                it.total > 0L &&
                it.checksum.matches(Regex("[a-f0-9]{64}"))
        }
    }

    private fun pollDefinitionJson(definition: AloPollDefinition): JSONObject {
        return JSONObject()
            .put("id", definition.id)
            .put("question", definition.question)
            .put(
                "options",
                JSONArray().also { values ->
                    definition.options.forEach { option ->
                        values.put(JSONObject().put("id", option.id).put("text", option.text))
                    }
                }
            )
            .put("allowsMultipleAnswers", definition.allowsMultipleAnswers)
            .put("isQuiz", definition.isQuiz)
            .put("quizAnswerCommitment", definition.quizAnswerCommitment)
            .put(
                "correctOptionIds",
                JSONArray().also { values -> definition.correctOptionIds.sorted().forEach(values::put) }
            )
            .put("explanation", definition.explanation)
            .put("isAnonymous", definition.isAnonymous)
            .put("allowsRevoting", definition.allowsRevoting)
            .put("closesAt", definition.closesAt)
    }

    private fun pollDefinitionFromJson(record: JSONObject): AloPollDefinition? {
        val optionsJson = record.optJSONArray("options") ?: return null
        val options = buildList {
            for (index in 0 until optionsJson.length()) {
                val item = optionsJson.optJSONObject(index) ?: return null
                add(AloPollOption(id = item.optString("id"), text = item.optString("text")))
            }
        }
        val correctJson = record.optJSONArray("correctOptionIds") ?: JSONArray()
        val definition = AloPollDefinition(
            id = record.optString("id"),
            question = record.optString("question"),
            options = options,
            allowsMultipleAnswers = record.optBoolean("allowsMultipleAnswers"),
            isQuiz = record.optBoolean("isQuiz"),
            correctOptionIds = buildSet {
                for (index in 0 until correctJson.length()) add(correctJson.optString(index))
            },
            quizAnswerCommitment = record.optString("quizAnswerCommitment"),
            explanation = record.optString("explanation"),
            isAnonymous = record.optBoolean("isAnonymous", true),
            allowsRevoting = record.optBoolean("allowsRevoting", !record.optBoolean("isQuiz")),
            closesAt = record.optString("closesAt")
        )
        return definition.takeIf { AloPollCodec.encodeCreate(it) != null }
    }

    private fun outgoingAttachmentJson(attachment: AloOutgoingMessageAttachment): JSONObject =
        AloCloudMessages.storedAttachmentJson(attachment)

    private fun outgoingAttachmentFromJson(record: JSONObject): AloOutgoingMessageAttachment? =
        AloCloudMessages.storedAttachmentFrom(record)

    private fun messageForwardSourceJson(source: AloMessageForwardSource): JSONObject =
        AloCloudMessages.forwardSourceJson(source)

    private fun messageForwardSourceFromJson(record: JSONObject): AloMessageForwardSource =
        AloCloudMessages.forwardSourceFrom(record)

    private fun readDraftAttachmentBytes(attachment: AloChatDraftAttachment): ByteArray {
        val uri = Uri.parse(attachment.localUri.ifBlank { attachment.previewUri })
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return File(requireNotNull(uri.path)).readBytes()
        }
        return contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to persist pending attachment: $uri")
    }

    private fun restoreDraftAttachment(
        record: JSONObject,
        ownerUserId: Int
    ): AloChatDraftAttachment? {
        if (record.optString("encryptedSourcePath").isNotBlank()) {
            return draftAttachmentStore.restoreEncryptedDraft(record)
        }
        val fileId = record.optString("fileId")
        val bytes = secureOutboxStore.loadAttachment(fileId, ownerUserId) ?: return null
        val restoreDir = File(cacheDir, "alo-outbox-restore").apply { mkdirs() }
        val safeName = record.optString("name")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "attachment.bin" }
        val temporary = File(restoreDir, "$fileId-$safeName")
        return runCatching {
            temporary.writeBytes(bytes)
            draftAttachmentStore.buildDraftFromContentUri(
                uri = Uri.fromFile(temporary),
                forcedName = record.optString("name"),
                forceVideoCircle = record.optBoolean("isVideoCircle")
            ).copy(
                attachmentType = record.optString("attachmentType"),
                mimeType = record.optString("mimeType"),
                name = record.optString("name"),
                durationSec = record.optInt("durationSec"),
                isVideoCircle = record.optBoolean("isVideoCircle")
            )
        }.getOrNull().also {
            temporary.delete()
        }
    }

    private fun deletePersistedDraftAttachments(clientMessageId: String, count: Int) {
        val ownerUserId = pendingChatOperationsOwnerUserId ?: return
        repeat(count) { index ->
            secureOutboxStore.deleteAttachment("$clientMessageId-$index", ownerUserId)
        }
    }

    private fun isValidClientOperationId(value: String): Boolean {
        return value.length in 16..128 && value.all {
            it.isLowerCase() || it.isDigit() || it == '_' || it == '-'
        }
    }

    private fun pendingOperationId(record: JSONObject): String {
        return record.optString("operationId")
            .takeIf(::isValidClientOperationId)
            ?: newClientOperationId()
    }

    private fun newClientOperationId(): String {
        return UUID.randomUUID().toString().replace("-", "").lowercase()
    }

    private suspend fun refreshConversationIfCurrent(target: PendingChatTarget) {
        if (!isActivePendingTarget(target)) return
        val refreshed = runCatching {
            apiClient.loadConversation(
                kind = target.kind,
                peerId = target.peerId,
                roomId = target.roomId
            )
        }.getOrNull() ?: return
        if (!isActivePendingTarget(target)) return
        // Sent messages keep their local row key through the refresh.
        val localKeys = appState.activeConversation?.items.orEmpty()
            .filter { it.localKey != 0 }
            .associate { it.id to it.localKey }
        val merged = mergePendingLocalMessages(refreshed).let { conversation ->
            if (localKeys.isEmpty()) conversation else conversation.copy(
                items = conversation.items.map { message ->
                    localKeys[message.id]?.takeIf { message.localKey == 0 }?.let { message.copy(localKey = it) } ?: message
                }
            )
        }
        appState = appState.copy(
            activeConversation = merged,
            activeConversationPeerId = merged.peerId,
            activeForumTopicId = selectedForumTopicId(merged),
            activeChatAppearance = chatAppearanceStore.read(merged),
            isLoadingConversation = false,
            isOfflineCached = false
        )
        syncConversationPreview(merged.peerId, merged.items.lastOrNull())
    }

    private fun sendNativeMessageOptimistically(
        body: String,
        conversation: AloConversationDetail,
        preserveComposer: Boolean = false,
        pollDefinition: AloPollDefinition? = null,
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false,
        pollOnServer: Boolean = false
    ) {
        val me = appState.bootstrap?.me ?: return
        val target = pendingTarget(conversation)
        val localId = nextLocalMessageId--
        val createdAt = scheduledAt.ifBlank(::currentIsoTimestamp)
        val markup = AloMessageMarkup.parse(body)
        val topicId = selectedForumTopicId(conversation)
        val localMessage = AloChatMessage(
            id = localId,
            roomId = conversation.roomId,
            senderId = me.id,
            recipientId = if (conversation.kind == "direct") conversation.peerId else 0,
            body = markup.body,
            attachmentUrl = "",
            attachmentType = "",
            attachmentName = "",
            createdAt = createdAt,
            editedAt = "",
            readAt = "",
            sender = AloConversationPeer(
                id = me.id,
                name = me.name,
                username = me.username,
                avatarUrl = me.avatarUrl,
                verified = me.verified,
                entityType = "user"
            ),
            entities = markup.entities,
            replyToMessageId = if (preserveComposer) 0 else appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0,
            topicId = topicId,
            scheduledAt = scheduledAt,
            sendWhenOnline = sendWhenOnline,
            silent = silent,
            deliveryState = if (scheduledAt.isNotBlank() || sendWhenOnline) "scheduled" else ""
        )
        val updatedConversation = conversation.copy(items = conversation.items + localMessage)
        if (preserveComposer) {
            appState = appState.copy(
                activeConversation = updatedConversation,
                isSendingMessage = false,
                errorMessage = null
            )
        } else {
            forumTopicDraftSyncJob?.cancel()
            messageDraftStore.clearActive(appState)
            appState = appState.copy(
                activeConversation = updatedConversation,
                messageComposerText = "",
                messageForwardDraft = emptyList(),
                messageReplyTo = null,
                isSendingMessage = false,
                errorMessage = null
            )
        }
        syncConversationPreview(conversation.peerId, localMessage)
        if (topicId > 0 && !preserveComposer) {
            lifecycleScope.launch {
                persistForumTopicDraft(conversation, topicId, "")
            }
        }
        enqueuePendingChatOperation(
            PendingChatOperation.SendText(
                localId = localId,
                target = target,
                body = body,
                createdAt = createdAt,
                replyToMessageId = localMessage.replyToMessageId,
                topicId = topicId,
                clientMessageId = UUID.randomUUID().toString().replace("-", "").lowercase(),
                pollDefinition = pollDefinition,
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline,
                pollOnServer = pollOnServer
            )
        )
    }

    /** The server's count of a poll after our own vote, shown at once instead of waiting for the refresh. */
    private fun applyCanonicalPollState(target: PendingChatTarget, state: AloCanonicalPollState) {
        val current = appState.activeConversation ?: return
        if (!isActivePendingTarget(target)) return
        appState = appState.copy(
            activeConversation = current.copy(canonicalPolls = current.canonicalPolls + (state.id to state))
        )
    }

    private fun sendMessageDraftOptimistically(
        body: String,
        conversation: AloConversationDetail,
        drafts: List<AloChatDraftAttachment>,
        uploadedAttachments: List<AloOutgoingMessageAttachment> = emptyList(),
        selfDestructSeconds: Int,
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false,
        preserveComposerState: Boolean = false,
        explicitReplyToMessageId: Int? = null,
        explicitTopicId: Int? = null
    ): Boolean {
        val me = appState.bootstrap?.me ?: return false
        if (drafts.isEmpty() && uploadedAttachments.isEmpty()) return false
        val target = pendingTarget(conversation)
        val localId = nextLocalMessageId--
        val createdAt = scheduledAt.ifBlank(::currentIsoTimestamp)
        val localAttachments = if (drafts.isEmpty()) {
            uploadedAttachments
        } else {
            drafts.map { draft -> draft.toLocalOutgoingAttachment() }
        }
        val primaryAttachment = localAttachments.firstOrNull()
        val markup = AloMessageMarkup.parse(body)
        val topicId = explicitTopicId?.coerceAtLeast(0) ?: selectedForumTopicId(conversation)
        val replyToMessageId = explicitReplyToMessageId?.coerceAtLeast(0)
            ?: (appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0)
        val localMessage = AloChatMessage(
            id = localId,
            roomId = conversation.roomId,
            senderId = me.id,
            recipientId = if (conversation.kind == "direct") conversation.peerId else 0,
            body = markup.body,
            attachmentUrl = primaryAttachment?.url.orEmpty(),
            attachmentType = primaryAttachment?.type.orEmpty(),
            attachmentName = primaryAttachment?.name.orEmpty(),
            attachments = localAttachments,
            createdAt = createdAt,
            editedAt = "",
            readAt = "",
            sender = AloConversationPeer(
                id = me.id,
                name = me.name,
                username = me.username,
                avatarUrl = me.avatarUrl,
                verified = me.verified,
                entityType = "user"
            ),
            entities = markup.entities,
            selfDestructSeconds = selfDestructSeconds.coerceAtLeast(0),
            replyToMessageId = replyToMessageId,
            topicId = topicId,
            scheduledAt = scheduledAt,
            sendWhenOnline = sendWhenOnline,
            silent = silent,
            deliveryState = if (scheduledAt.isNotBlank() || sendWhenOnline) "scheduled" else ""
        )
        val currentActiveConversation = appState.activeConversation
        val activeConversationStillMatches = currentActiveConversation?.let(::pendingTarget) == target
        val conversationWithLatestItems = if (activeConversationStillMatches) {
            currentActiveConversation ?: conversation
        } else {
            conversation
        }
        val updatedConversation = conversationWithLatestItems.copy(
            items = conversationWithLatestItems.items + localMessage
        )
        val shouldClearComposer = activeConversationStillMatches && !preserveComposerState
        if (shouldClearComposer) {
            forumTopicDraftSyncJob?.cancel()
            messageDraftStore.clearActive(appState)
        }
        val shouldClearReply = activeConversationStillMatches && (
            explicitReplyToMessageId == null ||
                (appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0) == replyToMessageId
            )
        appState = appState.copy(
            activeConversation = if (activeConversationStillMatches) updatedConversation else currentActiveConversation,
            messageComposerText = if (shouldClearComposer) "" else appState.messageComposerText,
            messageCaptureState = if (shouldClearComposer) AloChatCaptureState.Idle else appState.messageCaptureState,
            messageRecordingDurationSec = if (shouldClearComposer) 0 else appState.messageRecordingDurationSec,
            messageRecordingLevels = if (shouldClearComposer) emptyList() else appState.messageRecordingLevels,
            messageDraftAttachment = if (shouldClearComposer) null else appState.messageDraftAttachment,
            messageDraftAttachments = if (shouldClearComposer) emptyList() else appState.messageDraftAttachments,
            messageSelfDestructSeconds = if (shouldClearComposer) 0 else appState.messageSelfDestructSeconds,
            messageReplyTo = if (shouldClearReply) null else appState.messageReplyTo,
            isSendingMessage = false,
            errorMessage = null
        )
        if (activeConversationStillMatches && preserveComposerState) {
            messageDraftStore.persistFrom(appState)
        }
        syncConversationPreview(conversation.peerId, localMessage)
        if (topicId > 0 && shouldClearComposer) {
            lifecycleScope.launch {
                persistForumTopicDraft(conversation, topicId, "")
            }
        }
        enqueuePendingChatOperation(
            PendingChatOperation.SendDraft(
                localId = localId,
                target = target,
                body = body,
                createdAt = createdAt,
                attachments = drafts,
                uploadedAttachments = uploadedAttachments,
                selfDestructSeconds = selfDestructSeconds.coerceAtLeast(0),
                replyToMessageId = localMessage.replyToMessageId,
                topicId = topicId,
                clientMessageId = UUID.randomUUID().toString().replace("-", "").lowercase(),
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
        )
        return true
    }

    private fun sendCatalogExpression(media: AloCatalogExpressionMedia) {
        val conversation = appState.activeConversation ?: return
        if (appState.bootstrap == null) return
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        if (!media.isSticker && !media.isGif) {
            appState = appState.copy(errorMessage = "Этот тип медиа нельзя отправить из каталога.")
            return
        }
        if (appState.isSendingMessage) return

        val target = pendingTarget(conversation)
        val replyToMessageId = appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0
        val topicId = selectedForumTopicId(conversation)
        appState = appState.copy(isSendingMessage = true, errorMessage = null)
        lifecycleScope.launch {
            var downloadedFile: File? = null
            var encryptedDraft: AloChatDraftAttachment? = null
            var handedOffToOutbox = false
            try {
                // A cloud chat takes a sticker as a small card with the catalog address: nothing to download or upload.
                val stickerUrl = media.takeIf { it.isSticker }?.let { apiClient.stickerCardUrl(it.assetUrl) }
                if (
                    stickerUrl != null &&
                    apiClient.cloudMessagesAllowed(target.kind, target.peerId, target.roomId)
                ) {
                    val card = AloOutgoingMessageAttachment(
                        url = media.assetUrl,
                        type = "sticker",
                        name = media.title.ifBlank { "Стикер" },
                        metadata = buildMap {
                            put("id", "sticker-${media.id}")
                            put("stickerId", media.id)
                            if (media.title.isNotBlank()) put("alt", media.title)
                            if (media.width > 0) put("width", media.width.toString())
                            if (media.height > 0) put("height", media.height.toString())
                        },
                        posterUrl = apiClient.stickerCardUrl(media.previewUrl).orEmpty()
                    )
                    val cardConversation = appState.activeConversation
                        ?.takeIf { pendingTarget(it) == target }
                        ?: conversation
                    handedOffToOutbox = sendMessageDraftOptimistically(
                        body = "",
                        conversation = cardConversation,
                        drafts = emptyList(),
                        uploadedAttachments = listOf(card),
                        selfDestructSeconds = 0,
                        preserveComposerState = true,
                        explicitReplyToMessageId = replyToMessageId,
                        explicitTopicId = topicId
                    )
                    if (!handedOffToOutbox) {
                        throw IllegalStateException("Не удалось поставить стикер в очередь отправки.")
                    }
                    return@launch
                }
                val sourceFile = apiClient.downloadCatalogMedia(media)
                downloadedFile = sourceFile
                val actualExtension = sourceFile.extension
                    .lowercase(Locale.ROOT)
                    .takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "webm") }
                    ?: if (media.isGif) "gif" else "webp"
                val catalogName = media.suggestedFileName().substringBeforeLast('.') + "." + actualExtension
                val preparedDraft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = Uri.fromFile(sourceFile),
                        forcedName = catalogName,
                        forceVideoCircle = false
                    )
                }.copy(
                    attachmentType = if (media.isSticker) "sticker" else "gif",
                    name = catalogName,
                    durationSec = maxOf(
                        0,
                        media.durationSeconds.roundToInt(),
                        withContext(Dispatchers.IO) {
                            draftAttachmentStore.resolveMediaDurationSec(Uri.fromFile(sourceFile))
                        }
                    )
                )
                encryptedDraft = preparedDraft
                val currentConversation = appState.activeConversation
                    ?.takeIf { pendingTarget(it) == target }
                    ?: conversation
                handedOffToOutbox = sendMessageDraftOptimistically(
                    body = "",
                    conversation = currentConversation,
                    drafts = listOf(preparedDraft),
                    selfDestructSeconds = 0,
                    preserveComposerState = true,
                    explicitReplyToMessageId = replyToMessageId,
                    explicitTopicId = topicId
                )
                if (!handedOffToOutbox) {
                    throw IllegalStateException("Не удалось поставить медиа в очередь отправки.")
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        isSendingMessage = false,
                        errorMessage = error.message
                    )
                }
            } catch (error: Exception) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = error.localizedMessage
                        ?.takeIf(String::isNotBlank)
                        ?: "Не удалось отправить стикер или GIF."
                )
            } finally {
                if (!handedOffToOutbox) {
                    encryptedDraft?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
                    if (appState.isSendingMessage) {
                        appState = appState.copy(isSendingMessage = false)
                    }
                }
                downloadedFile?.delete()
            }
        }
    }

    private fun shareContact(contact: AloEntitySummary) {
        val conversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        if (contact.isChannel || contact.id <= 0) return
        sendMessageDraftOptimistically(
            body = appState.messageComposerText.trim(),
            conversation = conversation,
            drafts = emptyList(),
            uploadedAttachments = listOf(AloOutgoingMessageAttachment.contact(contact)),
            selfDestructSeconds = 0
        )
    }

    /** Static coordinates live only inside the encrypted message attachment. */
    private fun shareStaticLocation(
        latitude: Double,
        longitude: Double,
        title: String = "Текущее местоположение",
        address: String = ""
    ) {
        val conversation = appState.activeConversation ?: return
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return
        }
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        sendMessageDraftOptimistically(
            body = appState.messageComposerText.trim(),
            conversation = conversation,
            drafts = emptyList(),
            uploadedAttachments = listOf(
                AloOutgoingMessageAttachment.location(
                    latitude = latitude,
                    longitude = longitude,
                    title = title,
                    address = address
                )
            ),
            selfDestructSeconds = 0
        )
    }

    private fun shareLiveLocation(request: AloLiveLocationRequest) {
        val conversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        appState = appState.copy(isSendingMessage = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.sendLiveLocation(
                    kind = conversation.kind,
                    peerId = conversation.peerId,
                    roomId = conversation.roomId,
                    request = request,
                    topicId = selectedForumTopicId(conversation)
                )
                val refreshed = apiClient.loadConversation(
                    kind = conversation.kind,
                    peerId = conversation.peerId,
                    roomId = conversation.roomId
                )
                appState = appState.copy(
                    activeConversation = refreshed,
                    isSendingMessage = false,
                    errorMessage = null
                )
                syncConversationPreview(conversation.peerId, refreshed.items.lastOrNull())
            } catch (error: AloApiException) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = if (error.status == 404) {
                        "Живая геопозиция станет доступна после обновления сервера Alo."
                    } else {
                        error.message
                    }
                )
            } catch (_: Exception) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = "Не удалось отправить живую геопозицию."
                )
            }
        }
    }

    /**
     * A repeating send must be owned by the server so it survives process death
     * and works when the sender is offline. The endpoint is intentionally kept
     * separate from one-off scheduling.
     */
    private fun scheduleRepeatingCurrentMessage(repeat: AloMessageRepeatSchedule) {
        val conversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        val body = appState.messageComposerText.trim()
        if (body.isBlank()) {
            appState = appState.copy(errorMessage = "Введите сообщение для повторной отправки.")
            return
        }
        if (appState.messageDraftAttachment != null || appState.messageDraftAttachments.isNotEmpty() ||
            appState.messageForwardDraft.isNotEmpty()
        ) {
            appState = appState.copy(
                errorMessage = "Повторная отправка пока доступна только для текстовых сообщений."
            )
            return
        }
        appState = appState.copy(isSendingMessage = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.scheduleRepeatingMessage(
                    kind = conversation.kind,
                    peerId = conversation.peerId,
                    roomId = conversation.roomId,
                    body = body,
                    repeat = repeat,
                    replyToMessageId = appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0,
                    topicId = selectedForumTopicId(conversation)
                )
                val refreshed = apiClient.loadConversation(
                    kind = conversation.kind,
                    peerId = conversation.peerId,
                    roomId = conversation.roomId
                )
                messageDraftStore.clearActive(appState)
                appState = appState.copy(
                    activeConversation = refreshed,
                    messageComposerText = "",
                    messageReplyTo = null,
                    isSendingMessage = false,
                    errorMessage = null
                )
                syncConversationPreview(conversation.peerId, refreshed.items.lastOrNull())
            } catch (error: AloApiException) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = if (error.status == 404) {
                        "Повторные сообщения станут доступны после обновления сервера Alo."
                    } else {
                        error.message
                    }
                )
            } catch (_: Exception) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = "Не удалось создать повторную отправку."
                )
            }
        }
    }

    private suspend fun refreshViewedProfileIfOpen() {
        val target = appState.activeViewedProfileTarget ?: return
        val profile = when (target) {
            is AloProfileTarget.User -> apiClient.loadUserProfile(target.userId)
            is AloProfileTarget.Channel -> apiClient.loadChannelProfile(target.channelId)
            is AloProfileTarget.Group -> apiClient.loadGroupProfile(target.roomId)
        }
        if (appState.activeViewedProfileTarget == target) {
            appState = appState.copy(
                viewedProfile = profile,
                isViewedProfileLoading = false,
                errorMessage = null
            )
        }
    }

    private fun sendNativeMessage(
        bodyOverride: String? = null,
        captchaToken: String? = null,
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false
    ) {
        val activeConversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(activeConversation)) {
            showBlockedPeerError()
            return
        }
        if (captchaToken == null && appState.messageEditingId != null) {
            submitEditedChatMessage(activeConversation)
            return
        }
        val body = bodyOverride?.trim().orEmpty().ifBlank { appState.messageComposerText.trim() }
        val replyToMessageId = appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0
        val forwardDraft = appState.messageForwardDraft
        if (body.isEmpty() && forwardDraft.isEmpty()) {
            appState = appState.copy(errorMessage = "Сообщение не должно быть пустым.")
            return
        }
        if (captchaToken == null && forwardDraft.isEmpty()) {
            sendNativeMessageOptimistically(
                body = body,
                conversation = activeConversation,
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
            return
        }

        appState = appState.copy(
            isSendingMessage = true,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                if (forwardDraft.isNotEmpty()) {
                    forwardDraft.forEachIndexed { index, forwardSource ->
                        apiClient.sendMessage(
                            kind = activeConversation.kind,
                            peerId = activeConversation.peerId,
                            roomId = activeConversation.roomId,
                            body = if (index == 0) body else "",
                            forwardFrom = forwardSource,
                            replyToMessageId = if (index == 0) replyToMessageId else 0,
                            topicId = selectedForumTopicId(activeConversation),
                            captchaToken = captchaToken,
                            silent = silent,
                            scheduledAt = scheduledAt,
                            sendWhenOnline = sendWhenOnline
                        )
                    }
                } else {
                    apiClient.sendMessage(
                        kind = activeConversation.kind,
                        peerId = activeConversation.peerId,
                        roomId = activeConversation.roomId,
                        body = body,
                        replyToMessageId = replyToMessageId,
                        topicId = selectedForumTopicId(activeConversation),
                        captchaToken = captchaToken,
                        silent = silent,
                        scheduledAt = scheduledAt,
                        sendWhenOnline = sendWhenOnline
                    )
                }

                val refreshed = apiClient.loadConversation(
                    kind = activeConversation.kind,
                    peerId = activeConversation.peerId,
                    roomId = activeConversation.roomId
                )

                messageDraftStore.clearActive(appState)
                appState = appState.copy(
                    activeConversation = refreshed,
                    messageComposerText = "",
                    messageForwardDraft = emptyList(),
                    messageReplyTo = null,
                    isSendingMessage = false,
                    errorMessage = null
                )
                syncConversationPreview(activeConversation.peerId, refreshed.items.lastOrNull())
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token ->
                        sendNativeMessage(
                            bodyOverride = bodyOverride,
                            captchaToken = token,
                            silent = silent,
                            scheduledAt = scheduledAt,
                            sendWhenOnline = sendWhenOnline
                        )
                    }) {
                    return@launch
                } else {
                    appState = appState.copy(
                        isSendingMessage = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = "Не удалось отправить сообщение."
                )
            }
        }
    }

    private fun sendCurrentMessage(
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false
    ) {
        val hasDraft = appState.messageDraftAttachment != null || appState.messageDraftAttachments.isNotEmpty()
        if (hasDraft) {
            sendMessageDraft(
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
        } else {
            sendNativeMessage(
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
        }
    }

    private fun showScheduleMessagePicker(
        initialTimestamp: Long = System.currentTimeMillis() + 3_600_000L,
        onSelected: (String) -> Unit = { scheduledAt -> sendCurrentMessage(scheduledAt = scheduledAt) }
    ) {
        val minimumTimestamp = System.currentTimeMillis() + 60_000L
        val maximumTimestamp = System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1_000L
        val initial = Calendar.getInstance().apply {
            timeInMillis = initialTimestamp.coerceIn(minimumTimestamp, maximumTimestamp)
        }
        val dateDialog = android.app.DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                android.app.TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        val selected = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            set(Calendar.HOUR_OF_DAY, hourOfDay)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (selected.timeInMillis < minimumTimestamp) {
                            Toast.makeText(this, "Выберите время хотя бы на минуту позже.", Toast.LENGTH_SHORT).show()
                            return@TimePickerDialog
                        }
                        onSelected(Instant.ofEpochMilli(selected.timeInMillis).toString())
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    true
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        )
        dateDialog.datePicker.minDate = minimumTimestamp
        dateDialog.datePicker.maxDate = maximumTimestamp
        dateDialog.show()
    }

    private fun AloPollDefinition.adjustCloseDeadlineForSchedule(scheduledAt: String): AloPollDefinition {
        if (scheduledAt.isBlank() || closesAt.isBlank()) return this
        val scheduledMillis = runCatching { Instant.parse(scheduledAt).toEpochMilli() }.getOrNull()
            ?: return this
        val provisionalCloseMillis = runCatching { Instant.parse(closesAt).toEpochMilli() }.getOrNull()
            ?: return this
        val remainingMillis = (provisionalCloseMillis - System.currentTimeMillis())
            .coerceAtLeast(60_000L)
        return copy(closesAt = Instant.ofEpochMilli(scheduledMillis + remainingMillis).toString())
    }

    private fun toggleMessageRecorderMode() {
        if (appState.isSendingMessage || appState.messageCaptureState == AloChatCaptureState.Recording) return
        if (appState.messageDraftAttachment != null || appState.messageDraftAttachments.isNotEmpty()) return
        appState = appState.copy(
            messageRecorderMode = if (appState.messageRecorderMode == AloChatRecordMode.Voice) {
                AloChatRecordMode.VideoCircle
            } else {
                AloChatRecordMode.Voice
            },
            errorMessage = null
        )
    }

    private fun startMessageRecording() {
        val conversation = appState.activeConversation
        if (conversation == null) {
            appState = appState.copy(errorMessage = "Сначала открой чат.")
            return
        }
        if (isBlockedDirectConversation(conversation)) {
            showBlockedPeerError()
            return
        }
        if (appState.isSendingMessage) return
        if (appState.messageDraftAttachment != null || appState.messageDraftAttachments.isNotEmpty()) {
            appState = appState.copy(errorMessage = "Сначала отправьте или удалите текущее вложение.")
            return
        }

        when (appState.messageRecorderMode) {
            AloChatRecordMode.Voice -> {
                requestAppPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                    if (!granted) {
                        appState = appState.copy(errorMessage = "Для записи голосового нужен доступ к микрофону.")
                        return@requestAppPermissions
                    }
                    if (isBlockedDirectConversation(conversation)) {
                        showBlockedPeerError()
                        return@requestAppPermissions
                    }
                    startAudioMessageRecording()
                }
            }

            AloChatRecordMode.VideoCircle -> {
                requestAppPermissions(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                ) { granted ->
                    if (!granted) {
                        appState = appState.copy(
                            errorMessage = "Для записи кружка нужны камера и микрофон."
                        )
                        return@requestAppPermissions
                    }
                    if (isBlockedDirectConversation(conversation)) {
                        showBlockedPeerError()
                        return@requestAppPermissions
                    }
	                    appState = appState.copy(
	                        messageCaptureState = AloChatCaptureState.Recording,
	                        messageRecordingDurationSec = 0,
	                        messageRecordingLevels = emptyList(),
	                        messageDraftAttachment = null,
                        messageDraftAttachments = emptyList(),
                        errorMessage = null
                    )
                    startRecordingActivitySignal()
                }
            }
        }
    }

    /** Recording samples squeezed into the 48 bars every app draws (the loudest sample of each slice). */
    private fun voiceWaveformOf(samples: List<Float>, bars: Int = 48): List<Float> {
        if (samples.isEmpty()) return emptyList()
        return List(bars) { index ->
            val from = index * samples.size / bars
            val to = maxOf(from + 1, (index + 1) * samples.size / bars).coerceAtMost(samples.size)
            samples.subList(from.coerceAtMost(samples.size - 1), to).maxOrNull()?.coerceIn(0f, 1f) ?: 0f
        }
    }

    /** A started recorder writing AAC in MP4 to [outputFile]: what every app plays as it is (an ordinary or a secret chat). */
    private fun startVoiceRecorder(outputFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        } catch (error: Exception) {
            recorder.release()
            throw error
        }
        return recorder
    }

    private fun startAudioMessageRecording() {
        if (activeAudioRecorder != null) return

        runCatching {
            val outputFile = File(draftAttachmentStore.ensureChatDraftsDir(), "voice-${System.currentTimeMillis()}.m4a")
            activeAudioRecordingFile = outputFile
            val recorder = startVoiceRecorder(outputFile)
            activeAudioRecorder = recorder
            activeAudioRecordingFile = outputFile
            voiceRecordingSamples.clear()
	            appState = appState.copy(
	                messageCaptureState = AloChatCaptureState.Recording,
	                messageRecordingDurationSec = 0,
	                messageRecordingLevels = emptyList(),
	                messageDraftAttachment = null,
                messageDraftAttachments = emptyList(),
                errorMessage = null
            )
            startMessageRecordingTicker()
            startRecordingActivitySignal()
        }.onFailure {
            activeAudioRecorder?.release()
            activeAudioRecorder = null
            activeAudioRecordingFile?.delete()
            activeAudioRecordingFile = null
            appState = appState.copy(errorMessage = "Не удалось начать запись голосового.")
        }
    }

    /**
     * While a voice or video message is being recorded the other side sees «записывает голосовое» /
     * «записывает видеосообщение» (Telegram's sendMessageRecordAudioAction / RecordRound). The server forgets it after
     * ~4.5 s, so it is repeated every 3 s until the recording ends.
     */
    private var recordingActivityJob: Job? = null

    private fun startRecordingActivitySignal() {
        val conversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(conversation)) return
        recordingActivityJob?.cancel()
        recordingActivityJob = lifecycleScope.launch {
            while (isActive && appState.messageCaptureState == AloChatCaptureState.Recording &&
                appState.activeConversationPeerId == conversation.peerId
            ) {
                val action = if (appState.messageRecorderMode == AloChatRecordMode.VideoCircle) "circle" else "voice"
                runCatching {
                    apiClient.sendTyping(kind = conversation.kind, peerId = conversation.peerId, roomId = conversation.roomId, action = action)
                }
                delay(3_000)
            }
        }
    }

	    private fun startMessageRecordingTicker() {
	        messageRecordingTickerJob?.cancel()
	        messageRecordingTickerJob = lifecycleScope.launch {
	            val startedAt = SystemClock.elapsedRealtime()
	            while (isActive && appState.messageCaptureState == AloChatCaptureState.Recording) {
	                delay(90)
	                val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
	                val nextLevels = if (appState.messageRecorderMode == AloChatRecordMode.Voice) {
	                    val amplitude = runCatching { activeAudioRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
	                    val normalized = sqrt((amplitude / 32767f).coerceIn(0f, 1f)).coerceIn(0.08f, 1f)
	                    voiceRecordingSamples += normalized
	                    (appState.messageRecordingLevels + normalized).takeLast(28)
	                } else {
	                    appState.messageRecordingLevels
	                }
	                appState = appState.copy(
	                    messageRecordingDurationSec = elapsed,
	                    messageRecordingLevels = nextLevels
	                )
	                if (appState.messageRecorderMode == AloChatRecordMode.Voice && elapsed >= AloCloudMessages.MAX_VOICE_SECONDS) {
	                    stopMessageRecording(cancel = false)
	                }
	            }
	        }
	    }

    private fun stopMessageRecording(cancel: Boolean) {
        // The limit has already ended this recording and left it as a draft: releasing the button must not drop it.
        if (!cancel && appState.messageRecorderMode == AloChatRecordMode.Voice &&
            appState.messageCaptureState != AloChatCaptureState.Recording
        ) return
        messageRecordingTickerJob?.cancel()
        messageRecordingTickerJob = null

        when (appState.messageRecorderMode) {
            AloChatRecordMode.Voice -> {
                val draft = stopAudioRecorder(cancel = cancel)
                if (draft != null) {
	                    appState = appState.copy(
	                        messageCaptureState = AloChatCaptureState.Preview,
	                        messageRecordingDurationSec = draft.durationSec,
                        messageDraftAttachment = draft,
                        messageDraftAttachments = emptyList(),
                        errorMessage = null
                    )
                } else {
	                    appState = appState.copy(
	                        messageCaptureState = AloChatCaptureState.Idle,
	                        messageRecordingDurationSec = 0,
	                        messageRecordingLevels = emptyList(),
	                        messageDraftAttachment = null,
                        messageDraftAttachments = emptyList(),
                        errorMessage = if (cancel) null else appState.errorMessage
                    )
                }
            }

            AloChatRecordMode.VideoCircle -> {
	                appState = appState.copy(
	                    messageCaptureState = AloChatCaptureState.Idle,
	                    messageRecordingDurationSec = 0,
	                    messageRecordingLevels = emptyList(),
	                    errorMessage = if (cancel) null else appState.errorMessage
	                )
            }
        }
    }

    private fun stopAudioRecorder(
        cancel: Boolean,
        elapsedSec: Int = appState.messageRecordingDurationSec
    ): AloChatDraftAttachment? {
        val recorder = activeAudioRecorder
        val outputFile = activeAudioRecordingFile

        activeAudioRecorder = null
        activeAudioRecordingFile = null

        if (recorder == null || outputFile == null) return null

        return try {
            runCatching {
                recorder.stop()
            }
            recorder.release()

            if (cancel || !outputFile.exists() || outputFile.length() <= 1024L) {
                outputFile.delete()
                return null
            }

            val durationSec = draftAttachmentStore.resolveMediaDurationSec(Uri.fromFile(outputFile))
                .coerceAtLeast(elapsedSec)
            val draft = draftAttachmentStore.buildDraftFromContentUri(
                uri = Uri.fromFile(outputFile),
                forcedName = outputFile.name,
                forceVideoCircle = false
            )
            outputFile.delete()
            draft.copy(
                attachmentType = "voice",
                mimeType = "audio/mp4",
                name = outputFile.name,
                durationSec = durationSec.coerceAtLeast(1),
                isVideoCircle = false,
                waveform = voiceWaveformOf(voiceRecordingSamples.toList())
            )
        } catch (_error: Exception) {
            recorder.release()
            outputFile.delete()
            appState = appState.copy(errorMessage = "Не удалось завершить запись голосового.")
            null
        }
    }

    // ---- Voice messages and circles of the open secret chat ----
    // The same recorder and camera as an ordinary chat; the clip becomes a draft of the secret chat and is sealed for
    // the one device at the other end (AloSecretChatCoordinator), described as `voice` / `circle` like a cloud chat.

    private fun startSecretRecording(mode: AloChatRecordMode) {
        val state = appState
        val chatId = state.activeSecretChatId
        val chat = state.secretChats.firstOrNull { it.id == chatId }
        if (chat == null || !chat.isActive || state.isSecretChatGone || state.isSendingSecret) return
        if (state.secretCaptureState == AloChatCaptureState.Recording || activeAudioRecorder != null) return
        val permissions = if (mode == AloChatRecordMode.Voice) {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        }
        requestAppPermissions(permissions) { granted ->
            if (!granted) {
                appState = appState.copy(
                    errorMessage = if (mode == AloChatRecordMode.Voice) {
                        "Для записи голосового нужен доступ к микрофону."
                    } else {
                        "Для записи кружка нужны камера и микрофон."
                    }
                )
                return@requestAppPermissions
            }
            if (appState.activeSecretChatId != chatId || activeAudioRecorder != null) return@requestAppPermissions
            when (mode) {
                AloChatRecordMode.Voice -> startSecretVoiceRecording()
                // The camera overlay of the secret chat records the circle and reports it (CompleteSecretCircleRecording).
                AloChatRecordMode.VideoCircle -> appState = appState.copy(
                    secretRecorderMode = AloChatRecordMode.VideoCircle,
                    secretCaptureState = AloChatCaptureState.Recording,
                    secretRecordingDurationSec = 0,
                    secretRecordingLevels = emptyList(),
                    errorMessage = null
                )
            }
        }
    }

    private fun startSecretVoiceRecording() {
        val outputFile = File(draftAttachmentStore.ensureChatDraftsDir(), "voice-${System.currentTimeMillis()}.m4a")
        runCatching { startVoiceRecorder(outputFile) }
            .onSuccess { recorder ->
                activeAudioRecorder = recorder
                activeAudioRecordingFile = outputFile
                voiceRecordingSamples.clear()
                appState = appState.copy(
                    secretRecorderMode = AloChatRecordMode.Voice,
                    secretCaptureState = AloChatCaptureState.Recording,
                    secretRecordingDurationSec = 0,
                    secretRecordingLevels = emptyList(),
                    errorMessage = null
                )
                startSecretRecordingTicker()
            }
            .onFailure {
                outputFile.delete()
                appState = appState.copy(errorMessage = "Не удалось начать запись голосового.")
            }
    }

    private fun startSecretRecordingTicker() {
        secretRecordingTickerJob?.cancel()
        secretRecordingTickerJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive && appState.secretCaptureState == AloChatCaptureState.Recording) {
                delay(90)
                val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
                val amplitude = runCatching { activeAudioRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = sqrt((amplitude / 32767f).coerceIn(0f, 1f)).coerceIn(0.08f, 1f)
                voiceRecordingSamples += normalized
                appState = appState.copy(
                    secretRecordingDurationSec = elapsed,
                    secretRecordingLevels = (appState.secretRecordingLevels + normalized).takeLast(28)
                )
                if (elapsed >= AloCloudMessages.MAX_VOICE_SECONDS) stopSecretRecording(cancel = false, send = false)
            }
        }
    }

    /** Ends the voice recording of the secret chat (a circle ends in its camera overlay): kept as a draft, or sent. */
    private fun stopSecretRecording(cancel: Boolean, send: Boolean) {
        secretRecordingTickerJob?.cancel()
        secretRecordingTickerJob = null
        val state = appState
        if (state.secretCaptureState != AloChatCaptureState.Recording) return
        val chatId = state.activeSecretChatId
        val draft = if (state.secretRecorderMode == AloChatRecordMode.Voice) {
            stopAudioRecorder(cancel = cancel, elapsedSec = state.secretRecordingDurationSec)
        } else {
            null
        }
        appState = appState.copy(
            secretCaptureState = AloChatCaptureState.Idle,
            secretRecordingDurationSec = 0,
            secretRecordingLevels = emptyList()
        )
        if (draft != null && !cancel) secretChats.addRecordedDraft(chatId, draft, sendNow = send)
    }

    /** The camera overlay of the secret chat finished a circle: it is sealed and sent, as Telegram sends a circle. */
    private fun completeSecretCircleRecording(localUri: String, durationSec: Int, send: Boolean = true) {
        val chatId = appState.activeSecretChatId
        appState = appState.copy(
            secretCaptureState = AloChatCaptureState.Idle,
            secretRecordingDurationSec = 0,
            secretRecordingLevels = emptyList()
        )
        lifecycleScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = Uri.parse(localUri),
                        forcedName = buildVideoCircleFileName("video/mp4"),
                        forceVideoCircle = true
                    )
                }
                // The length read from the file wins over the overlay's counter (it runs fast on a busy device).
                val seconds = draft.durationSec.takeIf { it > 0 } ?: durationSec
                secretChats.addRecordedDraft(chatId, draft.copy(durationSec = seconds.coerceAtLeast(1)), sendNow = send)
            } catch (_: Exception) {
                appState = appState.copy(errorMessage = "Не удалось подготовить видео-кружок.")
            }
        }
    }

    private fun discardMessageDraft() {
        appState.messageDraftAttachment?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
        appState.messageDraftAttachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
        messageDraftStore.clearActive(appState)
	        appState = appState.copy(
	            messageCaptureState = AloChatCaptureState.Idle,
	            messageRecordingDurationSec = 0,
	            messageRecordingLevels = emptyList(),
	            messageDraftAttachment = null,
            messageDraftAttachments = emptyList(),
            errorMessage = null
        )
    }

    private fun removeMessageDraftAttachment(localUri: String) {
        if (localUri.isBlank()) return
        var removedDraft: AloChatDraftAttachment? = null
        val singleDraft = appState.messageDraftAttachment
        val nextSingleDraft = if (singleDraft?.localUri == localUri) {
            removedDraft = singleDraft
            null
        } else {
            singleDraft
        }
        val nextGalleryDrafts = appState.messageDraftAttachments.filter { draft ->
            val keep = draft.localUri != localUri
            if (!keep) removedDraft = draft
            keep
        }
        val hasAnyDraft = nextSingleDraft != null || nextGalleryDrafts.isNotEmpty()

        appState = appState.copy(
            messageCaptureState = if (hasAnyDraft) AloChatCaptureState.Preview else AloChatCaptureState.Idle,
            messageRecordingDurationSec = nextSingleDraft?.durationSec ?: 0,
            messageRecordingLevels = if (hasAnyDraft) appState.messageRecordingLevels else emptyList(),
            messageDraftAttachment = nextSingleDraft,
            messageDraftAttachments = nextGalleryDrafts,
            errorMessage = null
        )
        removedDraft?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
        messageDraftStore.persistFrom(appState)
    }

    private fun discardMessageForwardDraft() {
        appState = appState.copy(
            messageForwardDraft = emptyList(),
            errorMessage = null
        )
        messageDraftStore.persistFrom(appState)
    }

    private fun sendMessageDraft(
        captchaToken: String? = null,
        silent: Boolean = false,
        scheduledAt: String = "",
        sendWhenOnline: Boolean = false
    ) {
        val activeConversation = appState.activeConversation ?: return
        if (isBlockedDirectConversation(activeConversation)) {
            showBlockedPeerError()
            return
        }
        val singleDraft = appState.messageDraftAttachment
        val galleryDrafts = appState.messageDraftAttachments
        val selfDestructSeconds = appState.messageSelfDestructSeconds
        if (singleDraft == null && galleryDrafts.isEmpty()) return
        val body = if (singleDraft?.attachmentType == "voice" || singleDraft?.isVideoCircle == true) {
            ""
        } else {
            appState.messageComposerText.trim()
        }
        val draftsToSend = if (galleryDrafts.isNotEmpty()) {
            galleryDrafts
        } else {
            listOfNotNull(singleDraft)
        }
        if (captchaToken == null) {
            sendMessageDraftOptimistically(
                body = body,
                conversation = activeConversation,
                drafts = draftsToSend,
                selfDestructSeconds = selfDestructSeconds,
                silent = silent,
                scheduledAt = scheduledAt,
                sendWhenOnline = sendWhenOnline
            )
            return
        }

        appState = appState.copy(
            isSendingMessage = true,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                val cloudUpload = apiClient.cloudMessagesAllowed(
                    activeConversation.kind,
                    activeConversation.peerId,
                    activeConversation.roomId
                )
                val outgoingAttachments = if (galleryDrafts.isNotEmpty()) {
                    galleryDrafts.map { draft ->
                        val uploaded = uploadMessageDraftAttachment(draft, cloud = cloudUpload)
                        outgoingAttachmentFor(draft, uploaded)
                    }
                } else {
                    val draft = singleDraft ?: error("Missing single draft attachment")
                    val uploaded = uploadMessageDraftAttachment(draft, cloud = cloudUpload)
                    listOf(outgoingAttachmentFor(draft, uploaded))
                }

                apiClient.sendMessage(
                    kind = activeConversation.kind,
                    peerId = activeConversation.peerId,
                    roomId = activeConversation.roomId,
                    body = body,
                    attachment = outgoingAttachments.firstOrNull(),
                    attachments = outgoingAttachments,
                    selfDestructSeconds = selfDestructSeconds,
                    replyToMessageId = appState.messageReplyTo?.id?.takeIf { it > 0 } ?: 0,
                    topicId = selectedForumTopicId(activeConversation),
                    captchaToken = captchaToken,
                    silent = silent,
                    scheduledAt = scheduledAt,
                    sendWhenOnline = sendWhenOnline
                )

                val refreshed = apiClient.loadConversation(
                    kind = activeConversation.kind,
                    peerId = activeConversation.peerId,
                    roomId = activeConversation.roomId
                )

                singleDraft?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
                galleryDrafts.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
                messageDraftStore.clearActive(appState)
                appState = appState.copy(
                    activeConversation = refreshed,
                    isSendingMessage = false,
	                    messageComposerText = "",
	                    messageCaptureState = AloChatCaptureState.Idle,
	                    messageRecordingDurationSec = 0,
	                    messageRecordingLevels = emptyList(),
                    messageDraftAttachment = null,
                    messageDraftAttachments = emptyList(),
                    messageSelfDestructSeconds = 0,
                    messageReplyTo = null,
                    errorMessage = null
                )
                syncConversationPreview(activeConversation.peerId, refreshed.items.lastOrNull())
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token ->
                        sendMessageDraft(
                            captchaToken = token,
                            silent = silent,
                            scheduledAt = scheduledAt,
                            sendWhenOnline = sendWhenOnline
                        )
                    }) {
                    return@launch
                } else {
                    appState = appState.copy(
                        isSendingMessage = false,
                        errorMessage = error.message
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    isSendingMessage = false,
                    errorMessage = "Не удалось отправить вложение."
                )
            }
        }
    }

    private fun prepareMessageMediaOpen(
        message: AloChatMessage,
        onReady: (AloChatMessage) -> Unit
    ) {
        val conversation = appState.activeConversation ?: return
        // A disappearing attachment is opened on the server; one the auto-download rules skipped is downloaded on this tap.
        val needsDownload = message.selfDestructSeconds <= 0 && message.hasUndownloadedMedia()
        if (message.id <= 0 || (message.selfDestructSeconds <= 0 && !needsDownload)) {
            onReady(message)
            return
        }
        lifecycleScope.launch {
            try {
                val prepared = apiClient.prepareMessageMediaOpen(
                    kind = conversation.kind,
                    roomId = conversation.roomId,
                    message = message
                )
                val current = appState.activeConversation
                if (current != null && current.peerId == conversation.peerId) {
                    appState = appState.copy(
                        activeConversation = current.copy(
                            items = current.items.map { item ->
                                if (item.id == prepared.id) prepared else item
                            }
                        ),
                        errorMessage = null
                    )
                }
                onReady(prepared)
                scheduleMessageExpiryRemoval(source = message, prepared = prepared)
            } catch (error: AloApiException) {
                if (error.status == 410 || error.code == "media_expired") {
                    apiClient.purgeCachedMessageMedia(message)
                    replaceActiveConversationItems(pendingTarget(conversation)) { items ->
                        items.filterNot { it.id == message.id }
                    }
                } else if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(
                    errorMessage = if (needsDownload) "Не удалось скачать вложение." else "Не удалось открыть исчезающее вложение."
                )
            }
        }
    }

    private fun openMessageAttachment(message: AloChatMessage) {
        prepareMessageMediaOpen(message) { prepared ->
            val attachment = prepared.attachments.firstOrNull()
            val rawUrl = attachment?.url.orEmpty().ifBlank { prepared.attachmentUrl }
            if (rawUrl.isBlank()) {
                appState = appState.copy(errorMessage = "Файл недоступен.")
                return@prepareMessageMediaOpen
            }
            val name = attachment?.name.orEmpty().ifBlank { prepared.attachmentName }
            val uri = Uri.parse(rawUrl)
            val extension = MimeTypeMap.getFileExtensionFromUrl(name)
                .ifBlank { MimeTypeMap.getFileExtensionFromUrl(rawUrl) }
                .lowercase(Locale.getDefault())
            val mime = contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (_error: ActivityNotFoundException) {
                appState = appState.copy(errorMessage = "На устройстве нет приложения для открытия этого файла.")
            }
        }
    }

    private fun scheduleMessageExpiryRemoval(
        source: AloChatMessage,
        prepared: AloChatMessage
    ) {
        val expiresAt = runCatching { Instant.parse(prepared.expiresAt) }.getOrNull() ?: return
        val waitMs = (expiresAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        lifecycleScope.launch {
            delay(waitMs)
            apiClient.purgeCachedMessageMedia(source)
            apiClient.purgeCachedMessageMedia(prepared)
            val conversation = appState.activeConversation ?: return@launch
            replaceActiveConversationItems(pendingTarget(conversation)) { items ->
                items.filterNot { it.id == prepared.id }
            }
        }
    }

    private fun deleteChatMessage(messageId: Int, forEveryone: Boolean) {
        deleteChatMessages(listOf(messageId), forEveryone)
    }

    private fun rescheduleChatMessage(message: AloChatMessage, scheduledAt: String) {
        val conversation = appState.activeConversation ?: return
        if (!message.isScheduled || message.scheduledMessageId <= 0) return
        val target = pendingTarget(conversation)
        replaceActiveConversationItems(target) { items ->
            items.map { item ->
                if (item.id == message.id) {
                    item.copy(scheduledAt = scheduledAt, sendWhenOnline = false)
                } else {
                    item
                }
            }
        }
        enqueuePendingChatOperation(
            PendingChatOperation.Reschedule(
                target = target,
                messageId = message.id,
                scheduledMessageId = message.scheduledMessageId,
                originalScheduledAt = message.scheduledAt,
                scheduledAt = scheduledAt,
                silent = message.silent,
                operationId = newClientOperationId()
            )
        )
    }

    private fun cancelScheduledChatMessage(messageId: Int) {
        val conversation = appState.activeConversation ?: return
        val message = conversation.items.firstOrNull { it.id == messageId } ?: return
        if (!message.isScheduled || message.scheduledMessageId <= 0) return
        val target = pendingTarget(conversation)
        replaceActiveConversationItems(target) { items ->
            items.filterNot { it.scheduledMessageId == message.scheduledMessageId }
        }
        enqueuePendingChatOperation(
            PendingChatOperation.CancelScheduled(
                target = target,
                messageId = message.id,
                scheduledMessageId = message.scheduledMessageId,
                operationId = newClientOperationId()
            )
        )
    }

    private fun deleteChatMessages(messageIds: List<Int>, forEveryone: Boolean) {
        val activeConversation = appState.activeConversation ?: return
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return
        val serverIds = ids.filter { it > 0 }
        val target = pendingTarget(activeConversation)
        replaceActiveConversationItems(target) { messages ->
            messages.filterNot { message -> ids.contains(message.id) }
        }
        val ownerUserId = appState.bootstrap?.me?.id ?: 0
        val removedIds = serverIds.toSet()
        offlineCacheStore.inBackground {
            removeSearchMessages(peerId = target.peerId, ownerUserId = ownerUserId, messageIds = removedIds)
        }
        appState.activeConversation?.let { updated ->
            syncConversationPreview(updated.peerId, updated.items.lastOrNull())
        }
        if (serverIds.isEmpty()) {
            appState = appState.copy(
                pendingChatMessageIds = appState.pendingChatMessageIds - ids.toSet(),
                errorMessage = null
            )
            return
        }
        enqueuePendingChatOperation(
            PendingChatOperation.Delete(
                target = target,
                messageIds = serverIds,
                forEveryone = forEveryone,
                operationId = newClientOperationId()
            )
        )
    }

    private fun toggleChatMessageReaction(messageId: Int, emoji: String) {
        val activeConversation = appState.activeConversation ?: return
        if (messageId <= 0 || emoji.isBlank()) return
        val message = activeConversation.items.firstOrNull { it.id == messageId } ?: return
        if (message.toCallLogMeta() != null) return
        val target = pendingTarget(activeConversation)
        val desiredEmoji = if (message.reactions.any { it.reactedByMe && it.emoji == emoji }) "" else emoji
        applyOptimisticReaction(messageId, desiredEmoji)
        enqueuePendingChatOperation(
            PendingChatOperation.Reaction(
                target = target,
                messageId = messageId,
                emoji = desiredEmoji,
                operationId = newClientOperationId()
            )
        )
    }

    private fun toggleChatMessagePin(messageId: Int) {
        val conversation = appState.activeConversation ?: return
        if (messageId <= 0 || conversation.items.none { it.id == messageId }) return
        val target = pendingTarget(conversation)
        val willPin = messageId !in conversation.pinnedMessageIds
        val pinnedMessageIds = if (willPin) {
            listOf(messageId) + conversation.pinnedMessageIds.filterNot { it == messageId }
        } else {
            conversation.pinnedMessageIds.filterNot { it == messageId }
        }
        appState = appState.copy(
            activeConversation = conversation.copy(pinnedMessageIds = pinnedMessageIds),
            errorMessage = null
        )
        enqueuePendingChatOperation(
            PendingChatOperation.Pin(
                target = target,
                messageId = messageId,
                pinned = willPin,
                operationId = newClientOperationId()
            )
        )
    }

    private fun startEditingChatMessage(messageId: Int) {
        val conversation = appState.activeConversation ?: return
        val meId = appState.bootstrap?.me?.id ?: return
        val message = conversation.items.firstOrNull { it.id == messageId } ?: return
        val isChannel = conversation.isChannelCommunity()
        val canEditOthers = when {
            !isChannel -> false
            conversation.memberRole == "owner" -> true
            conversation.memberRole != "admin" -> false
            conversation.groupPolicy?.memberAdminRights.isNullOrEmpty() -> true
            else -> conversation.groupPolicy?.memberAdminRights?.get("editMessages") == true
        }
        if (message.id <= 0 || (message.senderId != meId && !canEditOthers) || message.toCallLogMeta() != null) return
        if (message.attachmentType == "voice" || message.attachmentName.contains("circle", ignoreCase = true)) return
        appState = appState.copy(
            messageEditingId = message.id,
            messageComposerText = AloMessageMarkup.source(message.body, message.entities),
            messageDraftAttachment = null,
            messageDraftAttachments = emptyList(),
            messageForwardDraft = emptyList(),
            messageCaptureState = AloChatCaptureState.Idle,
            errorMessage = null
        )
    }

    private fun cancelEditingChatMessage() {
        appState = appState.copy(
            messageEditingId = null,
            messageComposerText = "",
            errorMessage = null
        )
    }

    private fun startReplyingToMessage(message: AloChatMessage) {
        if (message.id <= 0 || message.toCallLogMeta() != null) return
        appState = appState.copy(
            messageReplyTo = message,
            messageEditingId = null,
            messageForwardDraft = emptyList(),
            errorMessage = null
        )
    }

    private fun cancelReplyingToMessage() {
        appState = appState.copy(messageReplyTo = null, errorMessage = null)
    }

    private fun submitEditedChatMessage(conversation: AloConversationDetail) {
        val messageId = appState.messageEditingId ?: return
        val message = conversation.items.firstOrNull { it.id == messageId } ?: run {
            cancelEditingChatMessage()
            return
        }
        val nextBody = appState.messageComposerText.trim()
        val markup = AloMessageMarkup.parse(nextBody)
        if (markup.body == message.body && markup.entities == message.entities) {
            cancelEditingChatMessage()
            return
        }
        if (nextBody.isBlank() && message.attachments.isEmpty()) {
            appState = appState.copy(errorMessage = "Сообщение не должно быть пустым.")
            return
        }
        val target = pendingTarget(conversation)
        replaceActiveConversationItems(target) { messages ->
            messages.map { item ->
                if (item.id == messageId) {
                    item.copy(
                        body = markup.body,
                        entities = markup.entities,
                        editedAt = currentIsoTimestamp()
                    )
                } else {
                    item
                }
            }
        }
        appState = appState.copy(
            messageEditingId = null,
            messageComposerText = "",
            errorMessage = null
        )
        enqueuePendingChatOperation(
            PendingChatOperation.Edit(
                target = target,
                messageId = messageId,
                originalBody = message.body,
                body = nextBody,
                attachments = message.attachments,
                forwardFrom = message.forwardFrom,
                operationId = newClientOperationId(),
                sourceEncrypted = message.encrypted
            )
        )
    }

    private fun forwardChatMessage(
        message: AloChatMessage,
        target: AloConversationSummary
    ) {
        forwardChatMessages(listOf(message), target)
    }

    private fun forwardChatMessages(
        messages: List<AloChatMessage>,
        target: AloConversationSummary
    ) {
        if (target.isBlockedDirectPeer(appState.blockedUserIds)) {
            showBlockedPeerError()
            return
        }
        val validMessages = messages.filter { it.id > 0 && it.senderId > 0 }
        if (validMessages.isEmpty()) return
        val forwardDraft = validMessages.map { message ->
            val sourceUserId = message.senderId
            AloMessageForwardSource(
                userId = sourceUserId,
                messageId = message.id,
                userName = message.sender?.name.orEmpty().ifBlank {
                    appState.bootstrap?.me?.takeIf { it.id == sourceUserId }?.name ?: "Автор"
                },
                // What the message says, so a cloud chat can send it as the new message's text (and an old encrypted
                // message, which the server cannot copy, can be forwarded at all).
                body = message.body,
                entities = message.entities,
                attachments = message.attachments,
                encrypted = message.encrypted
            )
        }
        openForwardDraftInConversation(target, forwardDraft)
    }

    private fun retargetMessageForwardDraft(target: AloConversationSummary) {
        if (target.isBlockedDirectPeer(appState.blockedUserIds)) {
            showBlockedPeerError()
            return
        }
        val forwardDraft = appState.messageForwardDraft
        if (forwardDraft.isEmpty()) return
        openForwardDraftInConversation(target, forwardDraft)
    }

    private fun openForwardDraftInConversation(
        target: AloConversationSummary,
        forwardDraft: List<AloMessageForwardSource>
    ) {
        val owner = appState.bootstrap?.me?.id ?: return
        val cookie = currentCookieHeader()
        fun current() = appState.bootstrap?.me?.id == owner && appState.activeConversationPeerId == target.peerId &&
            AloOfflineAccountPolicy.matchesSession(cookie, currentCookieHeader())
        if (target.isBlockedDirectPeer(appState.blockedUserIds)) {
            showBlockedPeerError()
            return
        }
        val fallbackDetail = AloConversationDetail(
            peerId = target.peerId,
            kind = target.kind,
            roomId = target.roomId,
            title = target.peer.name,
            subtitle = if (target.peer.entityType == "saved") {
                "Личное облако"
            } else if (target.kind == "group") {
                formatMemberCount(target.memberCount)
            } else {
                when {
                    target.peer.online -> "в сети"
                    target.peer.lastSeenAt.isNotBlank() -> target.peer.lastSeenAt
                    target.peer.username.isNotBlank() -> "@${target.peer.username}"
                    else -> "был(а) недавно"
                }
            },
            username = target.peer.username,
            lastSeenAt = target.peer.lastSeenAt,
            avatarUrl = target.peer.avatarUrl,
            verified = target.peer.verified,
            online = target.peer.online,
            memberCount = target.memberCount,
            entityType = target.peer.entityType,
            memberRole = "",
            canManage = false,
            members = emptyList(),
            items = emptyList()
        )
        messageDraftStore.persistFrom(appState)
        stopActiveRecordingForNavigation()
        val targetDraft = (messageDraftStore.restoredFor(owner, fallbackDetail)
            ?: PersistedMessageDraft("", AloChatRecordMode.Voice, null)).copy(forwardDraft = forwardDraft)
        messageDraftStore.put(owner, fallbackDetail, targetDraft)
        appState = appState.copy(
            activeTab = AloAppTab.Messages,
            activeConversationPeerId = target.peerId,
            isChatPeerProfileOpen = false,
            activeViewedProfileTarget = null,
            viewedProfile = null,
            isViewedProfileLoading = false,
            activeMedia = null,
            isLoadingConversation = true,
            activeConversation = fallbackDetail,
            activeForumTopicId = 0,
            activeChatAppearance = chatAppearanceStore.read(fallbackDetail),
            messageComposerText = targetDraft.text,
            messageRecorderMode = targetDraft.recorderMode,
            messageCaptureState = if (targetDraft.attachment != null || targetDraft.attachments.isNotEmpty()) AloChatCaptureState.Preview else AloChatCaptureState.Idle,
            messageRecordingDurationSec = 0,
            messageRecordingLevels = emptyList(),
            messageDraftAttachment = targetDraft.attachment,
            messageDraftAttachments = targetDraft.attachments,
            messageReplyTo = targetDraft.replyTo,
            messageEditingId = null,
            messageSelfDestructSeconds = targetDraft.selfDestructSeconds,
            messageForwardDraft = forwardDraft,
            errorMessage = null
        )

        lifecycleScope.launch {
            try {
                val detail = apiClient.loadConversation(
                    kind = target.kind,
                    peerId = target.peerId,
                    roomId = target.roomId
                )
                if (!current()) return@launch
                appState = appState.copy(
                    activeConversation = detail,
                    activeConversationPeerId = detail.peerId,
                    activeChatAppearance = chatAppearanceStore.read(detail),
                    isLoadingConversation = false,
                    errorMessage = null
                )
                messageDraftStore.persistFrom(appState)
                syncConversationPreview(detail.peerId, detail.items.lastOrNull())
            } catch (error: AloApiException) {
                if (!current()) return@launch
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(
                        activeConversation = fallbackDetail,
                        activeChatAppearance = chatAppearanceStore.read(fallbackDetail),
                        isLoadingConversation = false,
                        errorMessage = null
                    )
                }
            } catch (_error: Exception) {
                if (!current()) return@launch
                appState = appState.copy(
                    activeConversation = fallbackDetail,
                    activeChatAppearance = chatAppearanceStore.read(fallbackDetail),
                    isLoadingConversation = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun updateGroupChatProfile(roomId: Int, title: String) {
        val activeConversation = appState.activeConversation ?: return
        if (roomId <= 0 || title.trim().length < 2) return

        lifecycleScope.launch {
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

    private fun addGroupChatMember(roomId: Int, username: String) {
        if (roomId <= 0 || username.trim().isBlank()) return
        lifecycleScope.launch {
            try {
                apiClient.addGroupChatMember(roomId, username)
                reloadActiveConversationAfterGroupChange("Участник добавлен.")
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось добавить участника.")
            }
        }
    }

    private fun removeGroupChatMember(roomId: Int, userId: Int) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.removeGroupChatMember(roomId, userId)
                reloadActiveConversationAfterGroupChange("Участник удален.")
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось удалить участника.")
            }
        }
    }

    private fun leaveGroupChat(roomId: Int, delete: Boolean) {
        if (roomId <= 0) return
        lifecycleScope.launch {
            try {
                if (delete) {
                    apiClient.deleteGroupChat(roomId)
                } else {
                    apiClient.leaveGroupChat(roomId)
                }
                stopActiveConversationRefresh()
                appState = appState.copy(
                    activeConversationPeerId = 0,
                    activeConversation = null,
                    activeChatAppearance = AloChatAppearance(),
                    activeChatMuted = false,
                    activeViewedProfileTarget = null,
                    viewedProfile = null,
                    isViewedProfileLoading = false,
                    isChatPeerProfileOpen = false,
                    activeMedia = null,
                    isLoadingConversation = false,
                    isSendingMessage = false,
                    messageComposerText = "",
                    messageDraftAttachment = null,
                    messageDraftAttachments = emptyList(),
                    activeForumTopicId = 0,
                    messageForwardDraft = emptyList(),
                    messageEditingId = null,
                    groupManagement = null,
                    isGroupManagementLoading = false,
                    errorMessage = null
                )
                loadNativeApp(isInitial = false)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState = appState.copy(
                    errorMessage = if (delete) "Не удалось удалить сообщество." else "Не удалось покинуть сообщество."
                )
            }
        }
    }

    private fun updateGroupChatMemberRole(roomId: Int, userId: Int, role: String) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.updateGroupChatMemberRole(roomId, userId, role)
                reloadActiveConversationAfterGroupChange("Роль участника обновлена.")
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить роль.")
            }
        }
    }

    private fun createGroupChat(action: AloNativeAppAction.CreateGroupChat) {
        appState = appState.copy(
            activeTab = AloAppTab.Messages,
            isRefreshing = true,
            errorMessage = null
        )
        lifecycleScope.launch {
            try {
                val (roomId, peerId) = apiClient.createGroupChat(
                    title = action.title,
                    description = action.description,
                    kind = action.kind,
                    joinPolicy = action.joinPolicy,
                    username = action.username
                )
                loadNativeApp(isInitial = false)
                openNativeConversation(
                    peerId = peerId,
                    fallbackKind = "group",
                    fallbackRoomId = roomId
                )
            } catch (error: AloApiException) {
                appState = appState.copy(
                    isRefreshing = false,
                    errorMessage = error.message
                )
            } catch (error: Exception) {
                appState = appState.copy(
                    isRefreshing = false,
                    errorMessage = error.message ?: "Не удалось создать сообщество."
                )
            }
        }
    }

    private fun loadGroupManagement(roomId: Int) {
        if (roomId <= 0 || designPreview != null) return
        appState = appState.copy(isGroupManagementLoading = true)
        lifecycleScope.launch {
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

    private fun loadEarlierGroupAdminLog(roomId: Int) {
        val current = appState.groupManagement
        val beforeId = current?.nextAdminLogBeforeId ?: return
        if (roomId <= 0 || appState.isGroupManagementLoading) return
        appState = appState.copy(isGroupManagementLoading = true)
        lifecycleScope.launch {
            try {
                val page = apiClient.loadGroupAdminLog(roomId = roomId, beforeId = beforeId)
                val existingIds = current.events.mapTo(mutableSetOf()) { it.id }
                appState = appState.copy(
                    groupManagement = current.copy(
                        events = current.events + page.events.filter { existingIds.add(it.id) },
                        nextAdminLogBeforeId = page.nextBeforeId
                    ),
                    isGroupManagementLoading = false,
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось загрузить предыдущие действия.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось загрузить предыдущие действия."
                )
            }
        }
    }

    private fun createGroupInviteLink(action: AloNativeAppAction.CreateGroupInviteLink) {
        if (action.roomId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val (_, token) = apiClient.createGroupInviteLink(
                    roomId = action.roomId,
                    label = action.label,
                    requiresApproval = action.requiresApproval,
                    usageLimit = action.usageLimit,
                    expiresAt = action.expiresAt
                )
                if (token.isNotBlank()) {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("Alo invite", AloDeepLinkTarget.inviteUrl(token))
                    )
                }
                refreshGroupManagement(action.roomId)
                appState = appState.copy(
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось создать ссылку.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось создать ссылку."
                )
            }
        }
    }

    private fun updateGroupInviteLink(action: AloNativeAppAction.UpdateGroupInviteLink) {
        if (action.roomId <= 0 || action.inviteId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.updateGroupInviteLink(
                    roomId = action.roomId,
                    inviteId = action.inviteId,
                    label = action.label,
                    requiresApproval = action.requiresApproval,
                    usageLimit = action.usageLimit,
                    expiresAt = action.expiresAt
                )
                refreshGroupManagement(action.roomId)
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось изменить ссылку.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось изменить ссылку."
                )
            }
        }
    }

    private fun revokeGroupInviteLink(roomId: Int, inviteId: Int) {
        if (roomId <= 0 || inviteId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.revokeGroupInviteLink(roomId, inviteId)
                refreshGroupManagement(roomId)
                appState = appState.copy(
                    errorMessage = null
                )
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось отозвать ссылку.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось отозвать ссылку."
                )
            }
        }
    }

    private fun joinGroupInvite(token: String) {
        if (token.isBlank()) return
        appState = appState.copy(isRefreshing = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val (peerId, pendingApproval) = apiClient.joinGroupInvite(token)
                if (pendingApproval) {
                    appState = appState.copy(isRefreshing = false, errorMessage = null)
                    Toast.makeText(
                        this@MainActivity,
                        "Заявка на вступление отправлена.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    loadNativeApp(isInitial = false)
                    if (peerId > 0) {
                        openNativeConversation(peerId)
                    }
                }
            } catch (error: AloApiException) {
                appState = appState.copy(
                    isRefreshing = false,
                    errorMessage = error.message
                )
            } catch (_error: Exception) {
                appState = appState.copy(
                    isRefreshing = false,
                    errorMessage = "Не удалось открыть приглашение."
                )
            }
        }
    }

    private fun updateGroupSettings(roomId: Int, policy: AloGroupPolicy) {
        if (roomId <= 0) return
        val conversation = appState.activeConversation?.takeIf { it.roomId == roomId } ?: return
        val persistedPolicy = conversation.withImmutableCommunityKind(policy)
        val communityLabel = if (conversation.isChannelCommunity()) "канала" else "группы"
        appState = appState.copy(isGroupManagementLoading = true)
        lifecycleScope.launch {
            try {
                apiClient.updateGroupSettings(roomId, persistedPolicy)
                refreshGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Настройки $communityLabel сохранены.")
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось сохранить настройки $communityLabel.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось сохранить настройки $communityLabel."
                )
            }
        }
    }

    private fun updateDirectChatAutoDelete(peerId: Int, seconds: Int) {
        val activeConversation = appState.activeConversation
            ?.takeIf { it.kind == "direct" && it.peerId == peerId && it.entityType != "saved" }
            ?: return
        appState = appState.copy(isLoadingConversation = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.setDirectChatAutoDeleteSeconds(peerId, seconds)
                val refreshed = mergePendingLocalMessages(
                    apiClient.loadConversation(kind = activeConversation.kind, peerId = peerId)
                )
                appState = appState.copy(
                    activeConversation = refreshed,
                    isLoadingConversation = false,
                    errorMessage = "Автоудаление обновлено. Новые сообщения будут удаляться автоматически."
                )
            } catch (error: AloApiException) {
                appState = appState.copy(isLoadingConversation = false, errorMessage = error.message)
            } catch (_: Exception) {
                appState = appState.copy(
                    isLoadingConversation = false,
                    errorMessage = "Не удалось обновить автоудаление."
                )
            }
        }
    }

    private fun createGroupTopic(roomId: Int, title: String, emoji: String) {
        if (roomId <= 0 || title.isBlank()) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                val topic = apiClient.createGroupTopic(roomId, title, emoji)
                refreshGroupManagement(roomId)
                val conversation = appState.activeConversation
                if (conversation?.roomId == roomId) {
                    val refreshed = apiClient.loadConversation(
                        kind = conversation.kind,
                        peerId = conversation.peerId,
                        roomId = roomId
                    )
                    appState = appState.copy(
                        activeConversation = refreshed,
                        activeForumTopicId = topic.id,
                        errorMessage = null
                    )
                }
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось создать тему.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось создать тему."
                )
            }
        }
    }

    private fun updateGroupTopic(
        roomId: Int,
        topicId: Int,
        title: String?,
        emoji: String?,
        isClosed: Boolean?,
        isHidden: Boolean?
    ) {
        if (roomId <= 0 || topicId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.updateGroupTopic(
                    roomId,
                    topicId,
                    title,
                    emoji,
                    isClosed,
                    isHidden
                )
                refreshGroupManagement(roomId)
                val conversation = appState.activeConversation
                if (conversation?.roomId == roomId) {
                    val refreshed = apiClient.loadConversation(
                        kind = conversation.kind,
                        peerId = conversation.peerId,
                        roomId = roomId
                    )
                    appState = appState.copy(
                        activeConversation = refreshed,
                        activeForumTopicId = selectedForumTopicId(
                            refreshed,
                            preferredTopicId = appState.activeForumTopicId
                        ),
                        errorMessage = null
                    )
                }
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось обновить тему.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось обновить тему."
                )
            }
        }
    }

    private fun deleteGroupTopic(roomId: Int, topicId: Int) {
        if (roomId <= 0 || topicId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.deleteGroupTopic(roomId, topicId)
                refreshGroupManagement(roomId)
                val conversation = appState.activeConversation
                if (conversation?.roomId == roomId) {
                    val refreshed = apiClient.loadConversation(
                        kind = conversation.kind,
                        peerId = conversation.peerId,
                        roomId = roomId
                    )
                    appState = appState.copy(
                        activeConversation = refreshed,
                        activeForumTopicId = selectedForumTopicId(refreshed),
                        errorMessage = null
                    )
                }
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось удалить тему.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось удалить тему."
                )
            }
        }
    }

    private fun mergeGroupTopic(roomId: Int, sourceTopicId: Int, targetTopicId: Int) {
        if (roomId <= 0 || sourceTopicId <= 0 || targetTopicId <= 0) return
        appState = appState.copy(isGroupManagementLoading = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                apiClient.mergeGroupTopic(roomId, sourceTopicId, targetTopicId)
                val conversationBeforeRefresh = appState.activeConversation
                if (conversationBeforeRefresh?.roomId == roomId) {
                    val historyOwnerId = appState.bootstrap?.me?.id ?: 0
                    offlineCacheStore.inBackground {
                        reassignSearchHistoryTopic(
                            peerId = conversationBeforeRefresh.peerId,
                            ownerUserId = historyOwnerId,
                            sourceTopicId = sourceTopicId,
                            targetTopicId = targetTopicId
                        )
                    }
                }
                refreshGroupManagement(roomId)
                val conversation = appState.activeConversation
                if (conversation?.roomId == roomId) {
                    val refreshed = apiClient.loadConversation(
                        kind = conversation.kind,
                        peerId = conversation.peerId,
                        roomId = roomId
                    )
                    appState = appState.copy(
                        activeConversation = refreshed,
                        activeForumTopicId = targetTopicId,
                        errorMessage = null
                    )
                }
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось объединить темы.")
            } catch (_error: Exception) {
                appState = appState.copy(
                    isGroupManagementLoading = false,
                    errorMessage = "Не удалось объединить темы."
                )
            }
        }
    }

    private fun reviewGroupJoinRequest(roomId: Int, userId: Int, approve: Boolean) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.reviewGroupJoinRequest(roomId, userId, approve)
                refreshGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange(
                    if (approve) "Заявка одобрена." else "Заявка отклонена."
                )
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось обработать заявку.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обработать заявку.")
            }
        }
    }

    private fun updateGroupMemberAccess(
        roomId: Int,
        userId: Int,
        role: String,
        adminTitle: String,
        isAnonymous: Boolean,
        adminRights: Map<String, Boolean>,
        restrictions: Map<String, Boolean>,
        changeRole: Boolean
    ) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.updateGroupMemberAccess(
                    roomId = roomId,
                    userId = userId,
                    role = role,
                    adminTitle = adminTitle,
                    isAnonymous = isAnonymous,
                    adminRights = adminRights,
                    restrictions = restrictions,
                    changeRole = changeRole
                )
                refreshGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Права участника обновлены.")
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось обновить права участника.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить права участника.")
            }
        }
    }

    private fun transferGroupOwnership(roomId: Int, userId: Int, currentPassword: String) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.transferGroupOwnership(roomId, userId, currentPassword)
                loadGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Владелец сообщества изменён.")
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось передать сообщество.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось передать сообщество.")
            }
        }
    }

    private fun banGroupMember(roomId: Int, userId: Int, reason: String, expiresAt: String) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.banGroupMember(roomId, userId, reason, expiresAt)
                loadGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Участник заблокирован.")
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось заблокировать участника.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось заблокировать участника.")
            }
        }
    }

    private fun batchRemoveGroupMembers(roomId: Int, userIds: List<Int>, ban: Boolean) {
        if (roomId <= 0 || userIds.isEmpty()) return
        lifecycleScope.launch {
            try {
                val (_, skipped) = apiClient.batchRemoveGroupMembers(roomId, userIds, ban)
                loadGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange(
                    if (skipped > 0) {
                        "Некоторых участников нельзя ${if (ban) "заблокировать" else "удалить"}: владельца и администраторов может убрать только владелец."
                    } else if (ban) "Участники заблокированы." else "Участники удалены."
                )
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось удалить участников.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось удалить участников.")
            }
        }
    }

    private fun unbanGroupMember(roomId: Int, userId: Int) {
        if (roomId <= 0 || userId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.unbanGroupMember(roomId, userId)
                loadGroupManagement(roomId)
                reloadActiveConversationAfterGroupChange("Блокировка снята.")
            } catch (error: AloApiException) {
                handleGroupManagementError(error, "Не удалось снять блокировку.")
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось снять блокировку.")
            }
        }
    }

    private suspend fun refreshGroupManagement(roomId: Int) {
        appState = appState.copy(
            groupManagement = apiClient.loadGroupManagement(roomId),
            isGroupManagementLoading = false,
            errorMessage = null
        )
    }

    private fun handleGroupManagementError(error: AloApiException, fallback: String) {
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

    private fun sendFriendRequest(
        userId: Int,
        captchaToken: String? = null
    ) {
        if (userId <= 0) return
        lifecycleScope.launch {
            try {
                val status = apiClient.sendFriendRequest(userId, captchaToken)
                loadNativeApp(isInitial = false)
                refreshViewedProfileIfOpen()
                // A short confirmation at the bottom, not the red error line with «Обновить».
                appState = appState.copy(
                    notice = when (status) {
                        "friends" -> "Пользователь добавлен в друзья."
                        "outgoing" -> "Заявка отправлена."
                        else -> "Заявка обновлена."
                    }
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else if (handleHumanChallengeIfNeeded(error) { token -> sendFriendRequest(userId, token) }) {
                    return@launch
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось отправить заявку.")
            }
        }
    }

    /** A follow, a cancelled request or a removed friend: the change, then the lists and the open profile again. */
    private fun changeRelation(failure: String, work: suspend () -> String) {
        lifecycleScope.launch {
            try {
                val notice = work()
                loadNativeApp(isInitial = false)
                refreshViewedProfileIfOpen()
                appState = appState.copy(notice = notice)
            } catch (error: AloApiException) {
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = failure)
            }
        }
    }

    private fun respondFriendRequest(requestId: Int, accept: Boolean) {
        if (requestId <= 0) return
        lifecycleScope.launch {
            try {
                val status = if (accept) {
                    apiClient.acceptFriendRequest(requestId)
                } else {
                    apiClient.declineFriendRequest(requestId)
                }
                loadNativeApp(isInitial = false)
                refreshViewedProfileIfOpen()
                appState = appState.copy(
                    notice = when (status) {
                        "friends" -> "Заявка принята."
                        "declined" -> "Заявка отклонена."
                        else -> "Заявка обновлена."
                    }
                )
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить заявку.")
            }
        }
    }

    private fun markAllNotificationsRead() {
        val data = appState.bootstrap ?: return
        val unreadIds = data.notifications.filter { !it.isRead }.map { it.id }
        if (unreadIds.isEmpty()) return
        updateBootstrap { bootstrap ->
            bootstrap.copy(
                notifications = bootstrap.notifications.map {
                    it.copy(isRead = true)
                }
            )
        }
        lifecycleScope.launch {
            runCatching {
                apiClient.markNotificationsRead(all = true)
            }.onFailure {
                val unreadSet = unreadIds.toSet()
                updateBootstrap { bootstrap ->
                    bootstrap.copy(
                        notifications = bootstrap.notifications.map { notification ->
                            if (notification.id in unreadSet) {
                                notification.copy(isRead = false)
                            } else {
                                notification
                            }
                        }
                    )
                }
            }
        }
    }

    private fun deleteNotification(notificationId: Int) {
        if (notificationId <= 0) return
        lifecycleScope.launch {
            try {
                apiClient.deleteNotification(notificationId)
                updateBootstrap { bootstrap ->
                    bootstrap.copy(
                        notifications = bootstrap.notifications.filter {
                            it.id != notificationId
                        }
                    )
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось удалить уведомление.")
            }
        }
    }

    private fun pickMessageAttachment() {
        if (isBlockedDirectConversation()) {
            showBlockedPeerError()
            return
        }
        if (appState.isSendingMessage || appState.messageCaptureState == AloChatCaptureState.Recording) return
        pickerTarget = PickerTarget.MessageAttachment
        messageAttachmentPickerLauncher.launch("*/*")
    }

    private fun pickSecretAttachment() {
        pickerTarget = PickerTarget.SecretAttachment
        messageAttachmentPickerLauncher.launch("*/*")
    }

    private fun pickProfileAvatar() {
        pickerTarget = PickerTarget.ProfileAvatar
        messageAttachmentPickerLauncher.launch("image/*")
    }

    private fun pickGroupAvatar(roomId: Int) {
        if (roomId <= 0) return
        pendingGroupAvatarRoomId = roomId
        pickerTarget = PickerTarget.GroupAvatar
        messageAttachmentPickerLauncher.launch("image/*")
    }

    private fun preparePickedProfileAvatar(uri: Uri) {
        lifecycleScope.launch {
            var draftToDelete: AloChatDraftAttachment? = null
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = uri,
                        forcedName = null,
                        forceVideoCircle = false
                    )
                }
                draftToDelete = draft
                if (draft.attachmentType != "image") {
                    appState = appState.copy(errorMessage = "Для аватара нужна фотография.")
                    return@launch
                }
                val dataUrl = draftAttachmentStore.buildDataUrlForDraft(draft)
                val uploaded = apiClient.uploadProfileAvatar(dataUrl)
                val updated = apiClient.updateProfileAvatar(uploaded.url)
                applyUpdatedCurrentUser(updated)
                appState = appState.copy(errorMessage = null)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить аватар.")
            } finally {
                draftToDelete?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
            }
        }
    }

    private fun preparePickedGroupAvatar(uri: Uri) {
        val roomId = pendingGroupAvatarRoomId
        val activeConversation = appState.activeConversation
        if (roomId <= 0 || activeConversation == null || activeConversation.roomId != roomId) return
        lifecycleScope.launch {
            var draftToDelete: AloChatDraftAttachment? = null
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = uri,
                        forcedName = null,
                        forceVideoCircle = false
                    )
                }
                draftToDelete = draft
                if (draft.attachmentType != "image") {
                    appState = appState.copy(errorMessage = "Для аватарки группы нужна фотография.")
                    return@launch
                }
                val dataUrl = draftAttachmentStore.buildDataUrlForDraft(draft)
                val uploaded = apiClient.uploadProfileAvatar(dataUrl)
                apiClient.updateGroupChatProfile(
                    roomId = roomId,
                    title = activeConversation.title,
                    avatarUrl = uploaded.url
                )
                reloadActiveConversationAfterGroupChange("Аватарка группы обновлена.")
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить аватарку группы.")
            } finally {
                draftToDelete?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
            }
        }
    }

    private fun setFavoriteReaction(emoji: String) {
        val me = appState.bootstrap?.me ?: return
        if (emoji.isBlank() || emoji == me.favoriteReaction) return
        // Shown at once; put back if the server refuses.
        applyUpdatedCurrentUser(me.copy(favoriteReaction = emoji))
        lifecycleScope.launch {
            try {
                val saved = apiClient.setFavoriteReaction(emoji)
                appState.bootstrap?.me?.let { applyUpdatedCurrentUser(it.copy(favoriteReaction = saved)) }
            } catch (error: AloApiException) {
                appState.bootstrap?.me?.let { applyUpdatedCurrentUser(it.copy(favoriteReaction = me.favoriteReaction)) }
                if (error.status == 401) handleSessionExpired() else appState = appState.copy(errorMessage = error.message)
            } catch (_error: Exception) {
                appState.bootstrap?.me?.let { applyUpdatedCurrentUser(it.copy(favoriteReaction = me.favoriteReaction)) }
                appState = appState.copy(errorMessage = "Не удалось сохранить любимую реакцию.")
            }
        }
    }

    private fun updateProfileCoverStyle(style: String) {
        if (style.isBlank()) return
        lifecycleScope.launch {
            try {
                val updated = apiClient.updateProfileCoverStyle(style)
                applyUpdatedCurrentUser(updated)
                appState = appState.copy(errorMessage = null)
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось обновить баннер.")
            }
        }
    }

    private fun stopActiveRecordingForNavigation() {
        messageRecordingTickerJob?.cancel()
        messageRecordingTickerJob = null
        stopAudioRecorder(cancel = true)
    }

    private fun cleanupComposerTransientState() {
        messageRecordingTickerJob?.cancel()
        messageRecordingTickerJob = null
        stopAudioRecorder(cancel = true)
        appState.messageDraftAttachment?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
        appState.messageDraftAttachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
        appState = appState.copy(
	            messageCaptureState = AloChatCaptureState.Idle,
	            messageRecordingDurationSec = 0,
	            messageRecordingLevels = emptyList(),
	            messageDraftAttachment = null,
            messageDraftAttachments = emptyList(),
            errorMessage = null
        )
    }

    private fun preparePickedMessageAttachment(uri: Uri) {
        if (isBlockedDirectConversation()) return
        lifecycleScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = uri,
                        forcedName = null,
                        forceVideoCircle = false
                    )
                }
                if (draft.attachmentType == "image" || draft.attachmentType == "video") {
                    appState.messageDraftAttachment?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
                    val existingMedia = appState.messageDraftAttachments
                        .filter { it.attachmentType == "image" || it.attachmentType == "video" }
                    appState = appState.copy(
                        messageCaptureState = AloChatCaptureState.Preview,
                        messageRecordingDurationSec = 0,
                        messageRecordingLevels = emptyList(),
                        messageDraftAttachment = null,
                        messageDraftAttachments = (existingMedia + draft).distinctBy { it.localUri }.take(10),
                        errorMessage = null
                    )
                } else {
                    appState.messageDraftAttachment?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
                    appState.messageDraftAttachments.forEach(draftAttachmentStore::deleteDraftAttachmentFiles)
                    appState = appState.copy(
                        messageCaptureState = AloChatCaptureState.Preview,
                        messageRecordingDurationSec = draft.durationSec,
                        messageDraftAttachment = draft,
                        messageDraftAttachments = emptyList(),
                        errorMessage = null
                    )
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось подготовить вложение.")
            }
        }
    }

    private fun preparePickedMessageAttachments(uris: List<Uri>) {
        if (isBlockedDirectConversation()) return
        val source = uris.distinctBy { it.toString() }.take(10)
        if (source.isEmpty()) return
        lifecycleScope.launch {
            try {
                val drafts = withContext(Dispatchers.IO) {
                    source.mapNotNull { uri ->
                        runCatching {
                            draftAttachmentStore.buildDraftFromContentUri(
                                uri = uri,
                                forcedName = null,
                                forceVideoCircle = false
                            )
                        }.getOrNull()
                    }.filter { draft ->
                        draft.attachmentType == "image" || draft.attachmentType == "video"
                    }
                }
                if (drafts.isEmpty()) {
                    appState = appState.copy(errorMessage = "Не удалось подготовить выбранные фото или видео.")
                    return@launch
                }
                appState.messageDraftAttachment?.let(draftAttachmentStore::deleteDraftAttachmentFiles)
                val existingMedia = appState.messageDraftAttachments
                    .filter { it.attachmentType == "image" || it.attachmentType == "video" }
                appState = appState.copy(
		                    messageCaptureState = AloChatCaptureState.Preview,
		                    messageRecordingDurationSec = 0,
		                    messageRecordingLevels = emptyList(),
		                    messageDraftAttachment = null,
                    messageDraftAttachments = (existingMedia + drafts).distinctBy { it.localUri }.take(10),
                    errorMessage = null
                )
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось подготовить выбранные фото или видео.")
            }
        }
    }

    private fun completeVideoCircleRecording(localUri: String, durationSec: Int, send: Boolean = false) {
        if (isBlockedDirectConversation()) return
        lifecycleScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    draftAttachmentStore.buildDraftFromContentUri(
                        uri = Uri.parse(localUri),
                        forcedName = buildVideoCircleFileName("video/mp4"),
                        forceVideoCircle = true
                    )
                }
	                appState = appState.copy(
	                    messageCaptureState = AloChatCaptureState.Preview,
	                    messageRecordingDurationSec = durationSec.coerceAtLeast(draft.durationSec),
	                    messageRecordingLevels = emptyList(),
	                    messageDraftAttachment = draft.copy(durationSec = durationSec.coerceAtLeast(draft.durationSec)),
                    messageDraftAttachments = emptyList(),
                    errorMessage = null
                )
                // Released on the mic (Telegram): the circle goes out without a preview.
                if (send) sendMessageDraft()
            } catch (_error: Exception) {
	                appState = appState.copy(
	                    messageCaptureState = AloChatCaptureState.Idle,
	                    messageRecordingDurationSec = 0,
	                    messageRecordingLevels = emptyList(),
	                    messageDraftAttachment = null,
                    messageDraftAttachments = emptyList(),
                    errorMessage = "Не удалось подготовить видео-кружок."
                )
            }
        }
    }

    private fun requestAppPermissions(
        permissions: Array<String>,
        onResult: (Boolean) -> Unit
    ) {
        val missingPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            onResult(true)
            return
        }

        pendingAppPermissions = missingPermissions.toTypedArray()
        pendingAppPermissionsCallback = onResult
        appPermissionsLauncher.launch(pendingAppPermissions)
    }

    private fun showAppPermissionSettingsDialog(permissions: Array<String>) {
        val needsCamera = permissions.contains(Manifest.permission.CAMERA)
        val needsMicrophone = permissions.contains(Manifest.permission.RECORD_AUDIO)
        val accessName = when {
            needsCamera && needsMicrophone -> "камере и микрофону"
            needsCamera -> "камере"
            needsMicrophone -> "микрофону"
            else -> "необходимым функциям"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Разрешение отключено")
            .setMessage(
                "Разрешите Alo доступ к $accessName в настройках приложения."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Настройки") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .show()
    }

    private fun requestScreenCapturePermission(onResult: (Intent?) -> Unit) {
        if (pendingScreenCaptureCallback != null) {
            onResult(null)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        pendingScreenCaptureCallback = onResult
        // The foreground service starts after the person agrees: Android 14+ refuses it before that (AloCallCoordinator).
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestStartupPermissionsIfNeeded() {
        if (appPrefs.getBoolean(PREF_STARTUP_PERMISSIONS_REQUESTED, false)) return

        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct().toTypedArray()

        if (permissions.isEmpty()) {
            appPrefs.edit().putBoolean(PREF_STARTUP_PERMISSIONS_REQUESTED, true).apply()
            return
        }

        requestAppPermissions(permissions) {
            appPrefs.edit().putBoolean(PREF_STARTUP_PERMISSIONS_REQUESTED, true).apply()
        }
    }

    private suspend fun uploadMessageDraftAttachment(
        draft: AloChatDraftAttachment,
        resumeCheckpoint: AloResumableUploadCheckpoint? = null,
        reporter: AloUploadReporter? = null,
        /** A cloud chat: the file goes up as it is (the sealed draft is opened for the upload only). */
        cloud: Boolean = false,
        onCheckpoint: ((AloResumableUploadCheckpoint) -> Unit)? = null
    ): AloUploadedFile {
        if (cloud) {
            // A song picked as a file (not a recording) keeps its format: the server would make a voice message of it.
            val song = AloMusic.isSongDraft(draft)
            if (draft.encryptedSourcePath.isNotBlank()) {
                val encryptedFile = draftAttachmentStore.encryptedFileForDraft(draft)
                    ?: throw IllegalStateException("Защищённый черновик вложения больше недоступен.")
                return apiClient.uploadMessagePlainAttachment(
                    encryptedFile = encryptedFile,
                    mediaEncryption = draft.mediaEncryption,
                    resumeCheckpoint = resumeCheckpoint,
                    onCheckpoint = onCheckpoint,
                    reporter = reporter,
                    keepOriginal = song
                )
            }
            val plainFile = draftAttachmentStore.fileForDraft(draft)
            return if (plainFile != null) {
                apiClient.uploadMessagePlainFile(
                    file = plainFile,
                    mime = draft.mimeType.ifBlank { "application/octet-stream" },
                    resumeCheckpoint = resumeCheckpoint,
                    onCheckpoint = onCheckpoint,
                    reporter = reporter,
                    keepOriginal = song
                )
            } else {
                apiClient.uploadMessageAttachment(draftAttachmentStore.buildDataUrlForDraft(draft))
            }
        }
        if (draft.encryptedSourcePath.isNotBlank()) {
            val encryptedFile = draftAttachmentStore.encryptedFileForDraft(draft)
                ?: throw IllegalStateException("Защищённый черновик вложения больше недоступен.")
            return apiClient.uploadMessageE2eeAttachment(
                encryptedFile = encryptedFile,
                mediaEncryption = draft.mediaEncryption,
                checksum = draft.encryptedChecksum,
                resumeCheckpoint = resumeCheckpoint,
                onCheckpoint = onCheckpoint,
                reporter = reporter
            )
        }
        val localFile = draftAttachmentStore.fileForDraft(draft)
        return if (localFile != null) {
            apiClient.uploadMessageE2eeAttachment(
                file = localFile,
                mime = draft.mimeType.ifBlank { "application/octet-stream" },
                reporter = reporter
            )
        } else {
            // Old drafts created before the private draft store may retain a
            // content URI. Preserve their sendability while new drafts stay
            // fully file-streamed.
            apiClient.uploadMessageE2eeAttachment(
                draftAttachmentStore.buildDataUrlForDraft(draft)
            )
        }
    }

    /**
     * The attachment object of a sent file. Every chat kind gets the same description: size of a photo or video, the
     * length of a voice message, circle or video, and the bars of a voice message — the iPhone and the website send the
     * same and draw from it (an end-to-end message carries it inside the encrypted payload).
     */
    private fun outgoingAttachmentFor(draft: AloChatDraftAttachment, uploaded: AloUploadedFile): AloOutgoingMessageAttachment {
        val type = resolveOutgoingAttachmentType(draft, uploaded)
        return AloOutgoingMessageAttachment(
            url = uploaded.url,
            type = type,
            name = resolveOutgoingAttachmentName(draft, uploaded),
            mediaEncryption = uploaded.mediaEncryption,
            metadata = if (draft.durationSec > 0) {
                draft.mediaMetadata + ("durationSeconds" to draft.durationSec.toString())
            } else {
                draft.mediaMetadata
            },
            waveform = if (type == "voice") draft.waveform else emptyList()
        )
    }

    private fun resolveOutgoingAttachmentType(
        draft: AloChatDraftAttachment,
        uploaded: AloUploadedFile
    ): String {
        return when {
            draft.isVideoCircle -> "video"
            draft.attachmentType == "sticker" -> "sticker"
            draft.attachmentType == "gif" -> "gif"
            draft.attachmentType == "image" -> "image"
            draft.attachmentType == "video" -> "video"
            draft.attachmentType == "voice" -> "voice"
            uploaded.type.equals("voice", ignoreCase = true) -> "voice"
            uploaded.type.equals("image", ignoreCase = true) -> "image"
            uploaded.type.equals("video", ignoreCase = true) -> "video"
            else -> "file"
        }
    }

    private fun resolveOutgoingAttachmentName(
        draft: AloChatDraftAttachment,
        uploaded: AloUploadedFile
    ): String {
        return when {
            draft.isVideoCircle -> buildVideoCircleFileName(uploaded.mime.ifBlank { draft.mimeType })
            draft.name.isNotBlank() -> draft.name
            uploaded.type.equals("voice", ignoreCase = true) -> "voice-${System.currentTimeMillis()}.ogg"
            uploaded.type.equals("image", ignoreCase = true) -> "image-${System.currentTimeMillis()}.${extensionFromMime(uploaded.mime).ifBlank { "jpg" }}"
            uploaded.type.equals("video", ignoreCase = true) -> "video-${System.currentTimeMillis()}.${extensionFromMime(uploaded.mime).ifBlank { "mp4" }}"
            else -> "file-${System.currentTimeMillis()}"
        }
    }

    private fun AloChatDraftAttachment.toLocalOutgoingAttachment(): AloOutgoingMessageAttachment {
        val type = when {
            isVideoCircle -> "video"
            attachmentType.isNotBlank() -> attachmentType
            else -> "file"
        }
        return AloOutgoingMessageAttachment(
            url = localUri.ifBlank { previewUri },
            type = type,
            name = name.ifBlank {
                when (type) {
                    "voice" -> "voice-local.ogg"
                    "image" -> "image-local.jpg"
                    "video" -> if (isVideoCircle) "video-circle-local.mp4" else "video-local.mp4"
                    else -> "file-local"
                }
            },
            mediaEncryption = mediaEncryption,
            // The bubble shown while the file uploads already has the real length and bars of the recording.
            metadata = if (durationSec > 0) mediaMetadata + ("durationSeconds" to durationSec.toString()) else mediaMetadata,
            waveform = if (type == "voice") waveform else emptyList()
        )
    }

    private fun updateBootstrap(transform: (AloBootstrapData) -> AloBootstrapData) {
        val current = appState.bootstrap ?: return
        appState = appState.copy(bootstrap = transform(current))
    }

    private fun applyUpdatedCurrentUser(updated: AloCurrentUser) {
        updateBootstrap { data ->
            data.copy(me = updated)
        }
        appState = appState.copy(
            viewedProfile = appState.viewedProfile?.takeIf {
                it.kind == "user" && it.id == updated.id
            }?.copy(
                title = updated.name,
                username = updated.username,
                avatarUrl = updated.avatarUrl,
                avatarGallery = updated.avatarGallery,
                verified = updated.verified,
                coverStyle = updated.coverStyle,
                coverImageUrl = updated.coverImageUrl,
                description = updated.bio,
                meta = updated.registeredLabel
            )
        )
    }

    private suspend fun loadInitialConversationDetail(
        bootstrap: AloBootstrapData,
        peerId: Int
    ): AloConversationDetail? {
        if (peerId <= 0) return null

        val initial = buildConversationDetailFromBootstrap(bootstrap, peerId)
        if (peerId == bootstrap.messagesActivePeerId && initial != null && initial.items.isNotEmpty()) {
            return initial
        }

        val summary = bootstrap.messagesConversations.find { it.peerId == peerId } ?: return initial
        return try {
            apiClient.loadConversation(
                kind = summary.kind,
                peerId = summary.peerId,
                roomId = summary.roomId
            )
        } catch (_error: Exception) {
            initial
        }
    }

    private fun buildConversationDetailFromBootstrap(
        bootstrap: AloBootstrapData,
        peerId: Int
    ): AloConversationDetail? {
        val summary = bootstrap.messagesConversations.find { it.peerId == peerId } ?: return null
        val items = if (bootstrap.messagesActivePeerId == peerId) {
            bootstrap.messagesItems
        } else {
            emptyList()
        }

        val subtitle = if (summary.peer.entityType == "saved") {
            "Личное облако"
        } else if (summary.kind == "group") {
            formatMemberCount(summary.memberCount)
        } else {
            when {
                summary.peer.online -> "в сети"
                summary.peer.username.isNotBlank() -> "@${summary.peer.username}"
                else -> "Личный чат"
            }
        }

        return AloConversationDetail(
            peerId = summary.peerId,
            kind = summary.kind,
            roomId = summary.roomId,
            title = summary.peer.name,
            subtitle = subtitle,
            username = summary.peer.username,
            lastSeenAt = summary.peer.lastSeenAt,
            avatarUrl = summary.peer.avatarUrl,
            verified = summary.peer.verified,
            online = summary.peer.online,
            memberCount = summary.memberCount,
            entityType = summary.peer.entityType,
            items = items
        )
    }

    private fun syncConversationPreview(
        peerId: Int,
        lastMessage: AloChatMessage?
    ) {
        val visibleLastMessage = if (
            lastMessage != null &&
            AloPollCodec.decode(lastMessage.body).let { it is AloPollEvent.Vote || it is AloPollEvent.Reveal }
        ) {
            appState.activeConversation
                ?.items
                ?.asReversed()
                ?.firstOrNull {
                    AloPollCodec.decode(it.body).let { event ->
                        event !is AloPollEvent.Vote && event !is AloPollEvent.Reveal
                    }
                }
        } else {
            lastMessage
        }
        updateBootstrap { data ->
            val updatedConversations = data.messagesConversations
                .map { conversation ->
                    if (conversation.peerId == peerId && visibleLastMessage != null) {
                        conversation.copy(
                            lastBody = buildConversationPreview(visibleLastMessage),
                            lastAt = visibleLastMessage.createdAt
                        )
                    } else {
                        conversation
                    }
                }
                .sortedWith(
                    compareByDescending<AloConversationSummary> { conversation ->
                        conversation.peer.entityType == "saved"
                    }.thenByDescending { conversation ->
                        parseIsoTimestampMillis(conversation.lastAt)
                    }
                )

            data.copy(
                messagesConversations = updatedConversations,
                messagesActivePeerId = peerId,
                messagesItems = appState.activeConversation?.items ?: data.messagesItems
            )
        }
        appState.bootstrap?.let(offlineCacheStore::scheduleMessagesUpdate)
        val ownerUserId = appState.bootstrap?.me?.id ?: 0
        appState.activeConversation?.let { conversation ->
            offlineCacheStore.scheduleConversationSave(conversation, ownerUserId)
        }
    }

    private fun scheduleConversationHistoryBackfill(seed: AloConversationDetail) {
        val ownerUserId = appState.bootstrap?.me?.id ?: return
        val peerId = seed.peerId
        if (peerId <= 0) return
        if (conversationHistoryBackfillJobs[peerId]?.isActive == true) return

        conversationHistoryBackfillJobs[peerId] = lifecycleScope.launch {
            try {
                backfillConversationSearchHistory(seed, ownerUserId)
            } catch (_error: Exception) {
                // A later reconnect or chat open resumes from the oldest encrypted local page.
            } finally {
                conversationHistoryBackfillJobs.remove(peerId)
            }
        }
    }

    /**
     * Index chats one at a time after a successful bootstrap. This lets the
     * global search cover complete local history without a fan-out of requests
     * or any plaintext index on the server.
     */
    private fun scheduleGlobalConversationSearchIndex(bootstrap: AloBootstrapData) {
        val ownerUserId = bootstrap.me.id
        if (ownerUserId <= 0 || globalConversationSearchIndexJob?.isActive == true) return
        val conversations = bootstrap.messagesConversations
        if (conversations.isEmpty()) return

        globalConversationSearchIndexJob = lifecycleScope.launch {
            try {
                for (summary in conversations) {
                    if (!isActive) break
                    if (withContext(Dispatchers.IO) {
                            offlineCacheStore.isSearchHistoryComplete(summary.peerId, ownerUserId)
                        }) {
                        continue
                    }

                    val seed = withContext(Dispatchers.IO) {
                        offlineCacheStore.readConversation(summary.peerId, ownerUserId)
                    } ?: runCatching {
                        // Indexing is not reading: the chat stays unread here and for the sender.
                        apiClient.loadConversation(summary.kind, summary.peerId, summary.roomId, markRead = false)
                    }.getOrNull() ?: continue

                    withContext(Dispatchers.IO) {
                        offlineCacheStore.saveConversation(seed, ownerUserId)
                    }
                    backfillConversationSearchHistory(seed, ownerUserId)
                    delay(160)
                }
            } finally {
                globalConversationSearchIndexJob = null
            }
        }
    }

    private suspend fun backfillConversationSearchHistory(
        seed: AloConversationDetail,
        ownerUserId: Int
    ) {
        val peerId = seed.peerId
        if (peerId <= 0 || withContext(Dispatchers.IO) {
                offlineCacheStore.isSearchHistoryComplete(peerId, ownerUserId)
            }) {
            return
        }

        var indexed = seed
        var beforeId = withContext(Dispatchers.IO) {
            offlineCacheStore.oldestSearchMessageId(peerId, ownerUserId)
        }.takeIf { it > 0 } ?: seed.items.asSequence()
            .map(AloChatMessage::id)
            .filter { it > 0 }
            .minOrNull()
            ?: 0
        if (beforeId <= 0) {
            withContext(Dispatchers.IO) {
                offlineCacheStore.markSearchHistoryComplete(peerId, ownerUserId)
            }
            return
        }

        while (coroutineContext.isActive) {
            val page = apiClient.loadConversationHistoryPage(peerId, beforeId)
            val mergedItems = buildMap<Int, AloChatMessage> {
                indexed.items.forEach { message ->
                    if (message.id > 0) put(message.id, message)
                }
                page.items.forEach { message ->
                    if (message.id > 0) put(message.id, message)
                }
            }.values.sortedBy(AloChatMessage::id)
            indexed = indexed.copy(items = mergedItems)
            withContext(Dispatchers.IO) {
                offlineCacheStore.mergeSearchHistory(
                    indexed,
                    ownerUserId,
                    complete = !page.hasMore
                )
            }
            if (!page.hasMore || page.nextBeforeId <= 0 || page.nextBeforeId >= beforeId) break
            beforeId = page.nextBeforeId
        }
    }

    private fun updateAuthState(update: AuthUiState.() -> AuthUiState) {
        authState = authState.update().copy(errorMessage = null)
    }

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback

        val intent = buildFileChooserIntent(params)

        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (_error: ActivityNotFoundException) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        return try {
            params?.createIntent() ?: fallbackFileChooserIntent(params)
        } catch (_error: Exception) {
            fallbackFileChooserIntent(params)
        }
    }

    private fun fallbackFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val mimeTypes = acceptedMimeTypes(params)
        val primaryType = mimeTypes.firstOrNull() ?: "*/*"

        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
        }
    }

    private fun acceptedMimeTypes(params: WebChromeClient.FileChooserParams?): Array<String> {
        return params?.acceptTypes
            ?.flatMap { value ->
                value.split(",")
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
            }
            ?.distinct()
            ?.toTypedArray()
            ?: emptyArray()
    }

    private fun requestWebPermission(request: PermissionRequest) {
        val resources = supportedResources(request)
        if (resources.isEmpty()) {
            request.deny()
            return
        }

        val missingPermissions = requiredAndroidPermissions(resources).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            request.grant(resources)
            return
        }

        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        permissionLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun supportedResources(request: PermissionRequest): Array<String> {
        return request.resources
            .distinct()
            .filter { resource ->
                resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            .toTypedArray()
    }

    private fun requiredAndroidPermissions(resources: Array<String>): Array<String> {
        val permissions = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) {
            permissions += Manifest.permission.CAMERA
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        return permissions.distinct().toTypedArray()
    }

    private fun openExternalLink(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    companion object {
        /** The sending file in the «upload» design preview, as iOS's fixture 990250. */
        private const val UPLOAD_PREVIEW_MESSAGE_ID = 990250
        private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")
        private const val QR_LOGIN_POLL_INTERVAL_MS = 1_600L
        private const val PREF_STARTUP_PERMISSIONS_REQUESTED = "startup_permissions_requested"
        private const val PENDING_DEEP_LINK_KEY = "pending_deep_link_v1"
    }
}
