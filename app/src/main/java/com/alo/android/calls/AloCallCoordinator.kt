package com.alo.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.SurfaceViewRenderer as LiveKitSurfaceViewRenderer
import org.json.JSONObject

internal class AloCallCoordinator(
    private val activity: ComponentActivity,
    private val apiClient: AloApiClient,
    private val scope: CoroutineScope,
    private val getRootScreen: () -> RootScreen,
    private val getAppState: () -> AloNativeAppUiState,
    private val setAppState: (AloNativeAppUiState) -> Unit,
    private val isUserBlocked: (Int) -> Boolean,
    private val requestAppPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
    private val requestScreenCapturePermission: ((Intent?) -> Unit) -> Unit,
    private val handleSessionExpired: () -> Unit
) {
    private var callSignalStreamJob: Job? = null
    private var callHeartbeatJob: Job? = null
    private var directCallDisconnectJob: Job? = null
    private var directCallReconnectJob: Job? = null
    private var directCallSecurityBootstrapJob: Job? = null
    private var directCallRingTimeoutJob: Job? = null
    private var directCallTerminalJob: Job? = null
    private var rtcConfigCache: AloRtcConfig? = null
    private var activeDirectCallClient: AloDirectAudioCallClient? = null
    private val activeGroupCallClients = linkedMapOf<Int, AloDirectAudioCallClient>()
    private var activeGroupSfuClient: AloGroupSfuClient? = null
    private var latestGroupSfuSnapshot = AloGroupSfuSnapshot()
    private var groupControlsJob: Job? = null
    private var groupRecorder: AloGroupCallRecorder? = null
    /** A debug-fixture video chat: controls change locally, nothing reaches the server. */
    private var isGroupCallPreview = false
    /** Where a finished recording goes (iOS saves it to «Избранное»): file, seconds, chat title. */
    var onGroupCallRecording: ((java.io.File, Int, String) -> Unit)? = null
    private var lastDirectCallSignalId: Int = 0
    private var lastGroupCallSignalId: Int = 0
    private var isRefreshingDirectSignals = false
    private var isRefreshingGroupSignals = false
    private var directSignalsChangedDuringRefresh = false
    private var groupSignalsChangedDuringRefresh = false
    private val directCallSignalOrder = Mutex()
    private var acceptingDirectCallId: Int = 0
    // Sent by the caller only once the call was answered (the commitment and media state may come while it rings).
    private val directCallProgressKinds = setOf("offer", "ice", "security-confirm", "reconnect-offer")
    private var directCallLocalRenderer: AloCallRenderer? = null
    private var directCallRemoteRenderer: AloCallRenderer? = null
    private var groupCallLocalRenderer: AloCallRenderer? = null
    private var groupCallRemoteRenderer: AloCallRenderer? = null
    private var directCallSecurityHandshake: AloCallSecurityV2Handshake? = null
    private val directCallTonePlayer = AloCallTonePlayer(activity, scope)

    private var appState: AloNativeAppUiState
        get() = getAppState()
        set(value) {
            setAppState(value)
            syncCallService(value)
        }

    // A placed or answered call keeps a foreground service, so it survives switching to another app (AloCallService).
    private fun syncCallService(state: AloNativeAppUiState) {
        val direct = state.activeDirectCall?.takeIf {
            it.phase == AloDirectCallPhase.Outgoing || it.phase == AloDirectCallPhase.Connecting ||
                it.phase == AloDirectCallPhase.Reconnecting || it.phase == AloDirectCallPhase.Active
        }
        val group = state.activeGroupCall
        when {
            direct != null -> AloCallService.sync(activity, direct.peerName, direct.type == "video" && direct.isVideoEnabled)
            group != null -> AloCallService.sync(activity, group.roomTitle, group.isVideoEnabled)
            else -> AloCallService.sync(activity, null, false)
        }
        AloCallService.onHangUp = when {
            direct != null -> ({ endDirectCall() })
            group != null -> ({ leaveGroupCall() })
            else -> null
        }
    }

    fun startDirectCallPolling() {
        startCallSignalStream()
    }

    private fun startCallSignalStream() {
        if (callSignalStreamJob?.isActive == true) return
        callSignalStreamJob = scope.launch {
            var retryDelayMs = 500L
            while (isActive && getRootScreen() == RootScreen.App) {
                refreshDirectSignals()
                refreshGroupSignals()
                try {
                    apiClient.callEventStream().collect { event ->
                        when {
                            event.name == "call_ready" -> {
                                refreshDirectSignals()
                                refreshGroupSignals()
                            }
                            event.name == "call_signal_available" &&
                                event.payload.optString("channel") == "group" -> {
                                refreshGroupSignals()
                            }
                            event.name == "call_signal_available" -> {
                                refreshDirectSignals()
                            }
                        }
                        retryDelayMs = 500L
                    }
                } catch (error: AloApiException) {
                    if (error.status == 401) {
                        handleSessionExpired()
                        break
                    }
                } catch (_error: Exception) {
                    // Cached state remains visible while the stream reconnects.
                }
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(20_000L)
            }
        }
        callHeartbeatJob = scope.launch {
            while (isActive && getRootScreen() == RootScreen.App) {
                delay(20_000)
                appState.activeDirectCall?.callId
                    ?.takeIf { it > 0 }
                    ?.let { callId ->
                        runCatching { apiClient.heartbeatDirectCall(callId) }
                    }
                appState.activeGroupCall?.callId
                    ?.takeIf { it > 0 }
                    ?.let { callId ->
                        runCatching { apiClient.heartbeatGroupCall(callId) }
                    }
                refreshDirectSignals()
                refreshGroupSignals()
            }
        }
    }

    private suspend fun refreshDirectSignals() {
        if (isRefreshingDirectSignals) {
            // A signal came while the poll was in flight: poll once more after it, instead of waiting for the
            // heartbeat 20 s later (that is how an incoming offer used to sit unanswered until the caller gave up).
            directSignalsChangedDuringRefresh = true
            return
        }
        isRefreshingDirectSignals = true
        try {
            do {
                directSignalsChangedDuringRefresh = false
                val signals = apiClient.pollDirectCallSignals(
                    afterId = lastDirectCallSignalId,
                    activeCallId = null
                )
                if (signals.isNotEmpty()) {
                    lastDirectCallSignalId = maxOf(
                        lastDirectCallSignalId,
                        signals.maxOf { it.id }
                    )
                    processDirectCallSignals(signals)
                }
            } while (directSignalsChangedDuringRefresh)
        } catch (error: AloApiException) {
            if (error.status == 401) handleSessionExpired()
        } catch (_error: Exception) {
            // Stream reconnect and heartbeat fallback will retry.
        } finally {
            isRefreshingDirectSignals = false
        }
    }

    private suspend fun refreshGroupSignals() {
        if (isRefreshingGroupSignals) {
            groupSignalsChangedDuringRefresh = true
            return
        }
        isRefreshingGroupSignals = true
        try {
            do {
                groupSignalsChangedDuringRefresh = false
                val signals = apiClient.pollGroupCallSignals(lastGroupCallSignalId)
                if (signals.isNotEmpty()) {
                    lastGroupCallSignalId = maxOf(
                        lastGroupCallSignalId,
                        signals.maxOf { it.id }
                    )
                    processGroupCallSignals(signals)
                }
            } while (groupSignalsChangedDuringRefresh)
        } catch (error: AloApiException) {
            if (error.status == 401) handleSessionExpired()
        } catch (_error: Exception) {
            // Stream reconnect and heartbeat fallback will retry.
        } finally {
            isRefreshingGroupSignals = false
        }
    }

    fun stop() {
        callSignalStreamJob?.cancel()
        callSignalStreamJob = null
        callHeartbeatJob?.cancel()
        callHeartbeatJob = null
        directCallDisconnectJob?.cancel()
        directCallDisconnectJob = null
        directCallReconnectJob?.cancel()
        directCallReconnectJob = null
        directCallSecurityBootstrapJob?.cancel()
        directCallSecurityBootstrapJob = null
        directCallRingTimeoutJob?.cancel()
        directCallRingTimeoutJob = null
        directCallTerminalJob?.cancel()
        directCallTerminalJob = null
        directCallTonePlayer.stop()
        activeDirectCallClient?.close()
        activeDirectCallClient = null
        directCallSecurityHandshake = null
        directCallLocalRenderer = null
        directCallRemoteRenderer = null
        activeGroupCallClients.values.forEach { client ->
            runCatching { client.close() }
        }
        activeGroupCallClients.clear()
        activeGroupSfuClient?.close()
        activeGroupSfuClient = null
        latestGroupSfuSnapshot = AloGroupSfuSnapshot()
        groupCallLocalRenderer = null
        groupCallRemoteRenderer = null
        lastDirectCallSignalId = 0
        lastGroupCallSignalId = 0
        appState = appState.copy(activeDirectCall = null, activeGroupCall = null)
    }

    fun startGroupCallPolling() {
        startCallSignalStream()
    }

    private suspend fun processDirectCallSignals(signals: List<AloDirectCallSignal>) {
        signals.forEach { signal ->
            if (signal.kind in directCallProgressKinds && directCallAnsweredElsewhere(signal.callId)) {
                // The caller sends the offer only after someone answered. It was not this phone, so the
                // person took the call on another device: stop ringing here, and do not end the call for everyone.
                finishDirectCall(message = null, terminalTone = null)
                appState = appState.copy(errorMessage = "Звонок принят на другом устройстве.")
                return@forEach
            }
            when (signal.kind) {
                "invite" -> handleIncomingDirectCall(signal)
                "accept" -> handleAcceptedDirectCall(signal)
                "security-commit" -> handleDirectCallSecurityCommit(signal)
                "security-response" -> handleDirectCallSecurityResponse(signal)
                "offer" -> handleOfferDirectCall(signal)
                "answer" -> handleAnswerDirectCall(signal)
                "security-confirm" -> handleDirectCallSecurityConfirm(signal)
                "reconnect-offer" -> handleDirectCallReconnectOffer(signal)
                "reconnect-answer" -> handleDirectCallReconnectAnswer(signal)
                "ice" -> handleIceDirectCall(signal)
                "media-state" -> handleDirectCallMediaState(signal)
                "busy" -> handleBusyDirectCall(signal)
                "decline" -> handleDeclinedDirectCall(signal)
                "hangup" -> handleHangupDirectCall(signal)
            }
        }
    }

    private suspend fun processGroupCallSignals(signals: List<AloGroupCallSignal>) {
        signals.forEach { signal ->
            when (signal.kind) {
                "participant_joined" -> handleGroupParticipantJoined(signal)
                "participant_left" -> handleGroupParticipantLeft(signal)
                "offer" -> handleGroupOffer(signal)
                "answer" -> handleGroupAnswer(signal)
                "reconnect-offer" -> handleGroupReconnectOffer(signal)
                "reconnect-answer" -> handleGroupReconnectAnswer(signal)
                "ice" -> handleGroupIce(signal)
                "media-state" -> handleGroupMediaState(signal)
            }
        }
    }

    private suspend fun handleGroupParticipantJoined(signal: AloGroupCallSignal) {
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val user = parseGroupCallUser(signal.payload) ?: return
        val updated = if (current.participants.any { it.id == user.id }) {
            current
        } else {
            current.copy(participants = current.participants + user)
        }
        appState = appState.copy(activeGroupCall = updated)
        if (activeGroupSfuClient == null) {
            syncGroupVoiceMesh(updated)
            sendGroupMediaState(updated, user.id)
        }
    }

    private fun handleGroupParticipantLeft(signal: AloGroupCallSignal) {
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val userId = signal.payload.optInt("userId")
        if (userId <= 0) return
        if (activeGroupSfuClient == null) {
            activeGroupCallClients.remove(userId)?.close()
        }
        val updated = current.copy(
            participants = current.participants.filterNot { it.id == userId },
            videoParticipantIds = current.videoParticipantIds - userId
        )
        appState = appState.copy(activeGroupCall = updated)
        if (activeGroupSfuClient == null) {
            bindActiveGroupVideoClient(updated)
        }
    }

    private suspend fun handleGroupOffer(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val sdp = signal.payload.optString("sdp")
        if (sdp.isBlank()) return
        val client = ensureGroupCallClient(current, signal.fromUserId)
        client.prepareIncoming()
        client.handleRemoteOffer(sdp)
    }

    private suspend fun handleGroupAnswer(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val sdp = signal.payload.optString("sdp")
        if (sdp.isBlank()) return
        ensureGroupCallClient(current, signal.fromUserId).handleRemoteAnswer(sdp)
    }

    private suspend fun handleGroupReconnectOffer(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val sdp = signal.payload.optString("sdp")
        if (sdp.isBlank()) return
        ensureGroupCallClient(current, signal.fromUserId).handleRemoteReconnectOffer(sdp)
    }

    private suspend fun handleGroupReconnectAnswer(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val sdp = signal.payload.optString("sdp")
        if (sdp.isBlank()) return
        ensureGroupCallClient(current, signal.fromUserId).handleRemoteReconnectAnswer(sdp)
    }

    private suspend fun handleGroupIce(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val candidate = signal.payload.optString("candidate")
        val sdpMid = signal.payload.optString("sdpMid")
        val sdpMLineIndex = signal.payload.optInt("sdpMLineIndex", -1)
        if (candidate.isBlank() || sdpMid.isBlank() || sdpMLineIndex < 0) return
        ensureGroupCallClient(current, signal.fromUserId).addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    private fun handleGroupMediaState(signal: AloGroupCallSignal) {
        if (activeGroupSfuClient != null) return
        val current = appState.activeGroupCall ?: return
        if (current.callId != signal.callId) return
        val videoParticipants = if (signal.payload.optBoolean("videoEnabled")) {
            current.videoParticipantIds + signal.fromUserId
        } else {
            current.videoParticipantIds - signal.fromUserId
        }
        appState = appState.copy(
            activeGroupCall = current.copy(videoParticipantIds = videoParticipants)
        )
    }

    private suspend fun handleIncomingDirectCall(signal: AloDirectCallSignal) {
        if (isUserBlocked(signal.fromUserId)) {
            runCatching { apiClient.endDirectCall(signal.callId, "declined") }
            return
        }
        if (appState.activeDirectCall?.phase?.isTerminal == true) {
            finishDirectCall(message = null, terminalTone = null)
        }
        if (appState.activeGroupCall != null) {
            runCatching { apiClient.endDirectCall(signal.callId, "busy") }
            return
        }
        val current = appState.activeDirectCall
        if (current != null && current.callId == signal.callId) return
        if (current != null && current.callId != signal.callId) {
            runCatching { apiClient.endDirectCall(signal.callId, "busy") }
            return
        }
        val peer = resolveDirectCallPeer(signal.fromUserId, signal.fromUser)
        val handshake = runCatching {
            AloCallSecurityV2Handshake(
                role = AloCallSecurityV2Role.Callee,
                callId = signal.callId,
                callerId = signal.callerId,
                calleeId = signal.calleeId
            )
        }.getOrElse {
            runCatching { apiClient.endDirectCall(signal.callId, "security_mismatch") }
            return
        }
        directCallSecurityHandshake = handshake
        appState = appState.copy(
            activeDirectCall = AloDirectCallUiState(
                callId = signal.callId,
                peerId = peer.id,
                peerName = peer.name.ifBlank { "Собеседник" },
                peerUsername = peer.username,
                peerAvatarUrl = peer.avatarUrl,
                verified = peer.verified,
                type = signal.callType.ifBlank { "voice" },
                phase = AloDirectCallPhase.Incoming,
                isMuted = false,
                isSpeakerOn = signal.callType.ifBlank { "voice" } == "video",
                isIncoming = true,
                isVideoEnabled = signal.callType.ifBlank { "voice" } == "video",
                isFrontCamera = true,
                isRemoteVideoEnabled = false,
                startedAtMillis = 0L,
                callSecret = "",
                safetyEmojis = emptyList(),
                isSecurityConfirmed = false
            ),
            errorMessage = null
        )
        directCallTonePlayer.play(AloCallTone.Incoming)
        scheduleDirectCallRingTimeout(signal.callId, isOutgoing = false)
    }

    private suspend fun handleAcceptedDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId || current.phase != AloDirectCallPhase.Outgoing) return
        cancelDirectCallRingTimeout()
        val connecting = current.copy(
            phase = AloDirectCallPhase.Connecting,
            isIncoming = false
        )
        appState = appState.copy(activeDirectCall = connecting, errorMessage = null)
        directCallSecurityHandshake?.didAccept = true
        directCallTonePlayer.play(AloCallTone.Connecting)
        directCallSecurityHandshake?.let { handshake ->
            if (!handshake.didSendCommit) {
                sendDirectCallSecurityCommit(handshake)
            }
        }
        beginOutgoingDirectCallIfSecurityReady()
    }

    private suspend fun sendDirectCallSecurityCommit(
        handshake: AloCallSecurityV2Handshake,
        scheduleRetryOnFailure: Boolean = true
    ): Boolean {
        val current = appState.activeDirectCall
        val commitment = handshake.callerCommitment
        if (
            handshake.role != AloCallSecurityV2Role.Caller ||
            current == null ||
            current.callId != handshake.callId ||
            commitment == null
        ) {
            return false
        }
        if (handshake.didSendCommit) return true
        val sent = sendDirectCallSignal(
            callId = current.callId,
            peerId = current.peerId,
            kind = "security-commit",
            payload = JSONObject()
                .put("securityVersion", AloCallSecurityV2.VERSION)
                .put("callerCommitment", AloCallSecurityV2.base64Url(commitment)),
            retryBootstrapOnFailure = true
        )
        handshake.didSendCommit = sent
        if (!sent && scheduleRetryOnFailure) {
            scheduleDirectCallSecurityBootstrapRetry(current.callId)
        }
        return sent
    }

    private suspend fun sendDirectCallSecurityResponseIfReady(
        scheduleRetryOnFailure: Boolean = true
    ): Boolean {
        val handshake = directCallSecurityHandshake ?: return true
        val current = appState.activeDirectCall ?: return true
        val commitment = handshake.callerCommitment
        if (
            handshake.role != AloCallSecurityV2Role.Callee ||
            !handshake.didAccept ||
            handshake.callId != current.callId ||
            commitment == null
        ) {
            return true
        }
        if (handshake.didSendResponse) return true
        val sent = sendDirectCallSignal(
            callId = current.callId,
            peerId = current.peerId,
            kind = "security-response",
            payload = JSONObject()
                .put("securityVersion", AloCallSecurityV2.VERSION)
                .put("callerCommitment", AloCallSecurityV2.base64Url(commitment))
                .put(
                    "calleePublicKey",
                    AloCallSecurityV2.base64Url(handshake.localKeyPair.publicKey)
                )
                .put("calleeNonce", AloCallSecurityV2.base64Url(handshake.localNonce)),
            retryBootstrapOnFailure = true
        )
        handshake.didSendResponse = sent
        if (!sent && scheduleRetryOnFailure) {
            scheduleDirectCallSecurityBootstrapRetry(current.callId)
        }
        return sent
    }

    private suspend fun handleDirectCallSecurityCommit(signal: AloDirectCallSignal) {
        val handshake = directCallSecurityHandshake
        if (
            handshake == null ||
            handshake.role != AloCallSecurityV2Role.Callee ||
            handshake.callId != signal.callId ||
            signal.fromUserId != handshake.callerId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION
        ) {
            failDirectCallSecurity()
            return
        }
        try {
            val commitment = AloCallSecurityV2.decode32(
                signal.payload.optString("callerCommitment")
            )
            handshake.callerCommitment?.let { current ->
                require(AloCallSecurityV2.timingSafeEqual(current, commitment))
            }
            handshake.callerCommitment = commitment
            sendDirectCallSecurityResponseIfReady()
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleDirectCallSecurityResponse(signal: AloDirectCallSignal) {
        val handshake = directCallSecurityHandshake
        if (
            handshake == null ||
            handshake.role != AloCallSecurityV2Role.Caller ||
            handshake.callId != signal.callId ||
            signal.fromUserId != handshake.calleeId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION
        ) {
            failDirectCallSecurity()
            return
        }
        try {
            val expectedCommitment = requireNotNull(handshake.callerCommitment)
            val commitment = AloCallSecurityV2.decode32(
                signal.payload.optString("callerCommitment")
            )
            val publicKey = AloCallSecurityV2.decode32(
                signal.payload.optString("calleePublicKey")
            )
            val nonce = AloCallSecurityV2.decode32(
                signal.payload.optString("calleeNonce")
            )
            require(AloCallSecurityV2.timingSafeEqual(commitment, expectedCommitment))
            handshake.remotePublicKey?.let { current ->
                require(AloCallSecurityV2.timingSafeEqual(current, publicKey))
            }
            handshake.remoteNonce?.let { current ->
                require(AloCallSecurityV2.timingSafeEqual(current, nonce))
            }
            handshake.remotePublicKey = publicKey
            handshake.remoteNonce = nonce
            handshake.deriveSharedKeyIfPossible()
            beginOutgoingDirectCallIfSecurityReady()
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun beginOutgoingDirectCallIfSecurityReady() {
        val handshake = directCallSecurityHandshake ?: return
        val current = appState.activeDirectCall ?: return
        if (
            handshake.role != AloCallSecurityV2Role.Caller ||
            handshake.callId != current.callId ||
            !handshake.didAccept ||
            handshake.remotePublicKey == null ||
            handshake.remoteNonce == null ||
            handshake.didStartOffer
        ) {
            return
        }
        handshake.didStartOffer = true
        try {
            handshake.deriveSharedKeyIfPossible()
            val client = ensureDirectCallClient(current)
            client.prepareIncoming()
            client.startOutgoing()
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleOfferDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        val sdp = signal.payload.optString("sdp")
        val handshake = directCallSecurityHandshake
        if (
            current.callId != signal.callId ||
            handshake == null ||
            handshake.role != AloCallSecurityV2Role.Callee ||
            handshake.callId != signal.callId ||
            signal.fromUserId != handshake.callerId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION ||
            sdp.isBlank()
        ) {
            failDirectCallSecurity()
            return
        }
        try {
            val actualFingerprint = requireNotNull(AloCallSecurityV2.dtlsFingerprint(sdp))
            val claimedFingerprint = signal.payload.optString("offerFingerprint")
            val expectedCommitment = requireNotNull(handshake.callerCommitment)
            val callerPublicKey = AloCallSecurityV2.decode32(
                signal.payload.optString("callerPublicKey")
            )
            val callerNonce = AloCallSecurityV2.decode32(
                signal.payload.optString("callerNonce")
            )
            val commitment = AloCallSecurityV2.decode32(
                signal.payload.optString("callerCommitment")
            )
            val echoedCalleePublicKey = AloCallSecurityV2.decode32(
                signal.payload.optString("calleePublicKey")
            )
            val echoedCalleeNonce = AloCallSecurityV2.decode32(
                signal.payload.optString("calleeNonce")
            )
            val callerProof = AloCallSecurityV2.decode32(
                signal.payload.optString("callerProof")
            )
            val recomputedCommitment = AloCallSecurityV2.commitment(
                callId = handshake.callId,
                callerId = handshake.callerId,
                calleeId = handshake.calleeId,
                callerPublicKey = callerPublicKey,
                callerNonce = callerNonce
            )
            require(actualFingerprint == claimedFingerprint)
            require(AloCallSecurityV2.timingSafeEqual(commitment, expectedCommitment))
            require(AloCallSecurityV2.timingSafeEqual(commitment, recomputedCommitment))
            require(
                AloCallSecurityV2.timingSafeEqual(
                    echoedCalleePublicKey,
                    handshake.localKeyPair.publicKey
                )
            )
            require(
                AloCallSecurityV2.timingSafeEqual(
                    echoedCalleeNonce,
                    handshake.localNonce
                )
            )
            handshake.remotePublicKey = callerPublicKey
            handshake.remoteNonce = callerNonce
            handshake.offerFingerprint = actualFingerprint
            val sharedKey = handshake.deriveSharedKeyIfPossible()
            val offerHash = directCallOfferTranscriptHash(handshake)
            val expectedProof = AloCallSecurityV2.proof(
                sharedKey = sharedKey,
                role = "caller-offer",
                transcriptHash = offerHash
            )
            require(AloCallSecurityV2.timingSafeEqual(callerProof, expectedProof))
            val client = ensureDirectCallClient(current)
            client.prepareIncoming()
            client.handleRemoteOffer(sdp)
            if (current.phase == AloDirectCallPhase.Incoming) {
                appState = appState.copy(
                    activeDirectCall = current.copy(phase = AloDirectCallPhase.Connecting)
                )
                directCallTonePlayer.play(AloCallTone.Connecting)
            }
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleAnswerDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        val sdp = signal.payload.optString("sdp")
        val handshake = directCallSecurityHandshake
        if (
            current.callId != signal.callId ||
            handshake == null ||
            handshake.role != AloCallSecurityV2Role.Caller ||
            handshake.callId != signal.callId ||
            signal.fromUserId != handshake.calleeId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION ||
            sdp.isBlank()
        ) {
            failDirectCallSecurity()
            return
        }
        try {
            val actualFingerprint = requireNotNull(AloCallSecurityV2.dtlsFingerprint(sdp))
            val claimedFingerprint = signal.payload.optString("answerFingerprint")
            val echoedOfferFingerprint = signal.payload.optString("offerFingerprint")
            val commitment = AloCallSecurityV2.decode32(
                signal.payload.optString("callerCommitment")
            )
            val calleePublicKey = AloCallSecurityV2.decode32(
                signal.payload.optString("calleePublicKey")
            )
            val calleeNonce = AloCallSecurityV2.decode32(
                signal.payload.optString("calleeNonce")
            )
            val claimedTranscript = AloCallSecurityV2.decode32(
                signal.payload.optString("transcriptHash")
            )
            val calleeProof = AloCallSecurityV2.decode32(
                signal.payload.optString("calleeProof")
            )
            val expectedCommitment = requireNotNull(handshake.callerCommitment)
            val expectedCalleePublicKey = requireNotNull(handshake.remotePublicKey)
            val expectedCalleeNonce = requireNotNull(handshake.remoteNonce)
            require(actualFingerprint == claimedFingerprint)
            require(echoedOfferFingerprint == handshake.offerFingerprint)
            require(AloCallSecurityV2.timingSafeEqual(commitment, expectedCommitment))
            require(AloCallSecurityV2.timingSafeEqual(calleePublicKey, expectedCalleePublicKey))
            require(AloCallSecurityV2.timingSafeEqual(calleeNonce, expectedCalleeNonce))
            handshake.answerFingerprint = actualFingerprint
            val transcript = directCallFullTranscriptHash(handshake)
            require(AloCallSecurityV2.timingSafeEqual(transcript, claimedTranscript))
            val sharedKey = handshake.deriveSharedKeyIfPossible()
            val expectedProof = AloCallSecurityV2.proof(
                sharedKey = sharedKey,
                role = "callee-answer",
                transcriptHash = transcript
            )
            require(AloCallSecurityV2.timingSafeEqual(calleeProof, expectedProof))
            handshake.transcriptHash = transcript
            ensureDirectCallClient(current).handleRemoteAnswer(sdp)
            val callerProof = AloCallSecurityV2.proof(
                sharedKey = sharedKey,
                role = "caller-confirm",
                transcriptHash = transcript
            )
            handshake.didSendConfirm = true
            val sent = sendDirectCallSignal(
                callId = current.callId,
                peerId = current.peerId,
                kind = "security-confirm",
                payload = JSONObject()
                    .put("securityVersion", AloCallSecurityV2.VERSION)
                    .put("transcriptHash", AloCallSecurityV2.base64Url(transcript))
                    .put("callerProof", AloCallSecurityV2.base64Url(callerProof))
            )
            if (sent) {
                markDirectCallSecurityConfirmed(handshake)
            }
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleDirectCallSecurityConfirm(signal: AloDirectCallSignal) {
        val handshake = directCallSecurityHandshake
        if (
            handshake == null ||
            handshake.role != AloCallSecurityV2Role.Callee ||
            handshake.callId != signal.callId ||
            signal.fromUserId != handshake.callerId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION
        ) {
            failDirectCallSecurity()
            return
        }
        try {
            val expectedTranscript = requireNotNull(handshake.transcriptHash)
            val transcript = AloCallSecurityV2.decode32(
                signal.payload.optString("transcriptHash")
            )
            val callerProof = AloCallSecurityV2.decode32(
                signal.payload.optString("callerProof")
            )
            val expectedProof = AloCallSecurityV2.proof(
                sharedKey = handshake.deriveSharedKeyIfPossible(),
                role = "caller-confirm",
                transcriptHash = expectedTranscript
            )
            require(AloCallSecurityV2.timingSafeEqual(transcript, expectedTranscript))
            require(AloCallSecurityV2.timingSafeEqual(callerProof, expectedProof))
            markDirectCallSecurityConfirmed(handshake)
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleDirectCallReconnectOffer(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        val handshake = directCallSecurityHandshake ?: return
        if (
            current.callId != signal.callId ||
            handshake.role != AloCallSecurityV2Role.Callee ||
            !handshake.isConfirmed ||
            signal.fromUserId != handshake.callerId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION
        ) {
            return
        }
        try {
            val generation = signal.payload.optInt("generation")
            if (generation <= handshake.lastHandledReconnectGeneration) return
            val sdp = signal.payload.optString("sdp")
            val fingerprint = requireNotNull(AloCallSecurityV2.dtlsFingerprint(sdp))
            val initialTranscript = requireNotNull(handshake.transcriptHash)
            val sharedKey = requireNotNull(handshake.sharedKey)
            val claimedInitialTranscript = AloCallSecurityV2.decode32(
                signal.payload.optString("initialTranscriptHash")
            )
            val claimedOfferHash = AloCallSecurityV2.decode32(
                signal.payload.optString("offerSdpHash")
            )
            val callerProof = AloCallSecurityV2.decode32(
                signal.payload.optString("callerProof")
            )
            val computedOfferHash = AloCallSecurityV2.sdpHash(sdp)
            require(fingerprint == handshake.offerFingerprint)
            require(signal.payload.optString("offerFingerprint") == fingerprint)
            require(
                AloCallSecurityV2.timingSafeEqual(
                    claimedInitialTranscript,
                    initialTranscript
                )
            )
            require(AloCallSecurityV2.timingSafeEqual(claimedOfferHash, computedOfferHash))
            val reconnectHash = AloCallSecurityV2.reconnectTranscriptHash(
                callId = current.callId,
                generation = generation,
                initialTranscriptHash = initialTranscript,
                offerFingerprint = fingerprint,
                offerSdpHash = computedOfferHash
            )
            val expectedProof = AloCallSecurityV2.proof(
                sharedKey = sharedKey,
                role = "caller-reconnect-offer",
                transcriptHash = reconnectHash
            )
            require(AloCallSecurityV2.timingSafeEqual(callerProof, expectedProof))
            handshake.reconnectGeneration = generation
            handshake.lastHandledReconnectGeneration = generation
            handshake.reconnectOfferHash = computedOfferHash
            enterDirectCallReconnecting(current.callId)
            ensureDirectCallClient(current).handleRemoteReconnectOffer(sdp)
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleDirectCallReconnectAnswer(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        val handshake = directCallSecurityHandshake ?: return
        if (
            current.callId != signal.callId ||
            handshake.role != AloCallSecurityV2Role.Caller ||
            !handshake.isConfirmed ||
            signal.fromUserId != handshake.calleeId ||
            signal.payload.optInt("securityVersion") != AloCallSecurityV2.VERSION
        ) {
            return
        }
        try {
            val generation = signal.payload.optInt("generation")
            if (
                generation != handshake.reconnectGeneration ||
                generation <= handshake.lastHandledReconnectGeneration
            ) {
                return
            }
            val sdp = signal.payload.optString("sdp")
            val fingerprint = requireNotNull(AloCallSecurityV2.dtlsFingerprint(sdp))
            val initialTranscript = requireNotNull(handshake.transcriptHash)
            val sharedKey = requireNotNull(handshake.sharedKey)
            val expectedOfferHash = requireNotNull(handshake.reconnectOfferHash)
            val claimedInitialTranscript = AloCallSecurityV2.decode32(
                signal.payload.optString("initialTranscriptHash")
            )
            val claimedOfferHash = AloCallSecurityV2.decode32(
                signal.payload.optString("offerSdpHash")
            )
            val claimedAnswerHash = AloCallSecurityV2.decode32(
                signal.payload.optString("answerSdpHash")
            )
            val calleeProof = AloCallSecurityV2.decode32(
                signal.payload.optString("calleeProof")
            )
            val computedAnswerHash = AloCallSecurityV2.sdpHash(sdp)
            require(fingerprint == handshake.answerFingerprint)
            require(
                signal.payload.optString("offerFingerprint") ==
                    handshake.offerFingerprint
            )
            require(signal.payload.optString("answerFingerprint") == fingerprint)
            require(
                AloCallSecurityV2.timingSafeEqual(
                    claimedInitialTranscript,
                    initialTranscript
                )
            )
            require(AloCallSecurityV2.timingSafeEqual(claimedOfferHash, expectedOfferHash))
            require(AloCallSecurityV2.timingSafeEqual(claimedAnswerHash, computedAnswerHash))
            val reconnectHash = AloCallSecurityV2.reconnectTranscriptHash(
                callId = current.callId,
                generation = generation,
                initialTranscriptHash = initialTranscript,
                offerFingerprint = handshake.offerFingerprint,
                offerSdpHash = expectedOfferHash,
                answerFingerprint = fingerprint,
                answerSdpHash = computedAnswerHash
            )
            val expectedProof = AloCallSecurityV2.proof(
                sharedKey = sharedKey,
                role = "callee-reconnect-answer",
                transcriptHash = reconnectHash
            )
            require(AloCallSecurityV2.timingSafeEqual(calleeProof, expectedProof))
            handshake.lastHandledReconnectGeneration = generation
            ensureDirectCallClient(current).handleRemoteReconnectAnswer(sdp)
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private suspend fun handleIceDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId) return
        val candidate = signal.payload.optString("candidate")
        val sdpMid = signal.payload.optString("sdpMid")
        val sdpMLineIndex = signal.payload.optInt("sdpMLineIndex", -1)
        if (candidate.isBlank() || sdpMid.isBlank() || sdpMLineIndex < 0) return
        ensureDirectCallClient(current).addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    private fun handleDirectCallMediaState(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId || signal.fromUserId != current.peerId) return
        appState = appState.copy(
            activeDirectCall = current.copy(
                isRemoteVideoEnabled = signal.payload.optBoolean("videoEnabled"),
                isRemoteScreenSharing = signal.payload.optBoolean("screenSharing")
            )
        )
    }

    private suspend fun handleHangupDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId) return
        val reason = signal.payload.optString("reason")
        val status = signal.payload.optString("status").ifBlank { signal.callStatus }
        when {
            current.phase == AloDirectCallPhase.Outgoing && status == "missed" -> {
                showDirectCallTerminal(
                    phase = AloDirectCallPhase.NoAnswer,
                    terminalTone = AloCallTone.NoAnswer
                )
            }
            status == "declined" || reason == "declined" -> {
                showDirectCallTerminal(
                    phase = AloDirectCallPhase.Declined,
                    terminalTone = AloCallTone.Ended
                )
            }
            else -> finishDirectCall(message = null, terminalTone = AloCallTone.Ended)
        }
    }

    private suspend fun handleBusyDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId) return
        showDirectCallTerminal(
            phase = AloDirectCallPhase.Busy,
            terminalTone = AloCallTone.Busy
        )
    }

    private suspend fun handleDeclinedDirectCall(signal: AloDirectCallSignal) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != signal.callId) return
        showDirectCallTerminal(
            phase = AloDirectCallPhase.Declined,
            terminalTone = AloCallTone.Ended
        )
    }

    fun startDirectVoiceCall() {
        val detail = appState.activeConversation
        if (detail == null || detail.kind != "direct") {
            appState = appState.copy(errorMessage = "Голосовой звонок доступен только в личном чате.")
            return
        }
        if (isUserBlocked(detail.peerId)) {
            appState = appState.copy(errorMessage = AloUserBlockPolicy.BLOCKED_PEER_MESSAGE)
            return
        }
        if (appState.activeDirectCall != null || appState.activeGroupCall != null) return
        requestAppPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
            if (!granted) {
                appState = appState.copy(errorMessage = "Для звонка нужен доступ к микрофону.")
                return@requestAppPermissions
            }
            if (isUserBlocked(detail.peerId)) {
                appState = appState.copy(errorMessage = AloUserBlockPolicy.BLOCKED_PEER_MESSAGE)
                return@requestAppPermissions
            }
            scope.launch {
                try {
                    val session = apiClient.startDirectCall(detail.peerId, "voice")
                    val handshake = AloCallSecurityV2Handshake(
                        role = AloCallSecurityV2Role.Caller,
                        callId = session.id,
                        callerId = session.callerId,
                        calleeId = session.calleeId
                    )
                    directCallSecurityHandshake = handshake
                    appState = appState.copy(
                        activeDirectCall = AloDirectCallUiState(
                            callId = session.id,
                            peerId = detail.peerId,
                            peerName = detail.title,
                            peerUsername = detail.username,
                            peerAvatarUrl = detail.avatarUrl,
                            verified = detail.verified,
                            type = session.type,
                            phase = AloDirectCallPhase.Outgoing,
                            isMuted = false,
                            isSpeakerOn = false,
                            isIncoming = false,
                            isVideoEnabled = false,
                            isFrontCamera = true,
                            startedAtMillis = 0L,
                            callSecret = "",
                            safetyEmojis = emptyList(),
                            isSecurityConfirmed = false
                        ),
                        errorMessage = null
                    )
                    directCallTonePlayer.play(AloCallTone.Ringback)
                    scheduleDirectCallRingTimeout(session.id, isOutgoing = true)
                    sendDirectCallSecurityCommit(handshake)
                } catch (error: AloApiException) {
                    directCallSecurityHandshake = null
                    if (error.status == 401) {
                        handleSessionExpired()
                    } else {
                        appState = appState.copy(errorMessage = error.message)
                    }
                } catch (_error: Exception) {
                    directCallSecurityHandshake = null
                    appState = appState.copy(errorMessage = "Не удалось начать звонок.")
                }
            }
        }
    }

    fun startGroupVoiceCall() {
        startGroupCall()
    }

    fun startGroupVideoCall() {
        startGroupCall()
    }

    private fun startGroupCall() {
        val detail = appState.activeConversation
        if (detail == null || (detail.kind != "group" && detail.roomId <= 0)) {
            appState = appState.copy(errorMessage = "Групповой звонок доступен только в групповом чате.")
            return
        }
        if (appState.activeDirectCall != null || appState.activeGroupCall != null) return
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
        requestAppPermissions(permissions) { granted ->
            if (!granted) {
                appState = appState.copy(
                    errorMessage = "Для видеочата нужен доступ к микрофону."
                )
                return@requestAppPermissions
            }
            scope.launch {
            try {
                val call = apiClient.startGroupCall(detail.roomId, "video")
                val participants = (call.participants + listOfNotNull(appState.bootstrap?.me?.let {
                    AloEntitySummary(
                        id = it.id,
                        name = it.name,
                        username = it.username,
                        avatarUrl = it.avatarUrl,
                        verified = it.verified,
                        isChannel = false
                    )
                })).distinctBy { it.id }
                appState = appState.copy(
                    activeGroupCall = AloGroupCallUiState(
                        callId = call.id,
                        roomId = call.roomId,
                        roomTitle = call.roomTitle.ifBlank { detail.title },
                        type = "video",
                        participants = participants,
                        isMuted = false,
                        isSpeakerOn = true,
                        isVideoEnabled = false,
                        isFrontCamera = true
                    ),
                    errorMessage = null
                )
                appState.activeGroupCall?.let { active ->
                    appState = appState.copy(activeGroupCall = active.copy(localUserId = appState.bootstrap?.me?.id ?: 0))
                    startGroupControlsPolling(active.callId)
                    connectGroupSfuOrFallback(active)
                }
            } catch (error: AloApiException) {
                if (error.status == 401) {
                    handleSessionExpired()
                } else {
                    appState = appState.copy(errorMessage = error.message)
                }
            } catch (_error: Exception) {
                appState = appState.copy(errorMessage = "Не удалось начать групповой звонок.")
            }
            }
        }
    }

    private suspend fun connectGroupSfuOrFallback(call: AloGroupCallUiState) {
        val join = runCatching { apiClient.loadGroupCallSfuJoin(call.callId) }.getOrNull()
        if (join == null) {
            if (!AloGroupCallAudioPolicy.canUsePeerToPeerFallback(call.participants.size)) {
                runCatching { apiClient.leaveGroupCall(call.callId) }
                appState = appState.copy(
                    activeGroupCall = null,
                    errorMessage = AloGroupCallAudioPolicy.SFU_REQUIRED_MESSAGE
                )
                return
            }
            syncGroupVoiceMesh(call)
            broadcastGroupMediaState(call)
            return
        }

        lateinit var client: AloGroupSfuClient
        client = AloGroupSfuClient(
            context = activity,
            scope = scope,
            onSnapshot = { snapshot ->
                activity.runOnUiThread {
                    if (activeGroupSfuClient !== client) return@runOnUiThread
                    latestGroupSfuSnapshot = snapshot
                    applyGroupSfuSnapshot(snapshot)
                }
            },
            onError = { message ->
                activity.runOnUiThread {
                    val current = appState.activeGroupCall ?: return@runOnUiThread
                    if (activeGroupSfuClient !== client || current.callId != call.callId) {
                        return@runOnUiThread
                    }
                    appState = appState.copy(errorMessage = message)
                }
            }
        )
        activeGroupSfuClient = client
        try {
            client.connect(join, muted = call.isMuted)
            activeGroupCallClients.values.forEach { runCatching { it.close() } }
            activeGroupCallClients.clear()
            applyGroupSfuSnapshot(latestGroupSfuSnapshot)
        } catch (_error: Exception) {
            if (activeGroupSfuClient === client) {
                activeGroupSfuClient = null
            }
            runCatching { client.close() }
            latestGroupSfuSnapshot = AloGroupSfuSnapshot()
            val current = appState.activeGroupCall
            if (current != null && current.callId == call.callId) {
                appState = appState.copy(
                    activeGroupCall = current.copy(usesSfu = false, isReconnecting = false)
                )
                syncGroupVoiceMesh(current)
                broadcastGroupMediaState(current)
            }
        }
    }

    private fun applyGroupSfuSnapshot(snapshot: AloGroupSfuSnapshot) {
        val current = appState.activeGroupCall ?: return
        val usesSfu = activeGroupSfuClient != null
        appState = appState.copy(
            activeGroupCall = current.copy(
                videoParticipantIds = snapshot.videoParticipantIds,
                speakingParticipantIds = snapshot.speakingParticipantIds,
                mutedParticipantIds = snapshot.mutedParticipantIds,
                usesSfu = usesSfu,
                isReconnecting = usesSfu && snapshot.reconnecting
            )
        )
    }

    fun startDirectVideoCall() {
        val detail = appState.activeConversation
        if (detail == null || detail.kind != "direct") {
            appState = appState.copy(errorMessage = "Видеозвонок доступен только в личном чате.")
            return
        }
        if (isUserBlocked(detail.peerId)) {
            appState = appState.copy(errorMessage = AloUserBlockPolicy.BLOCKED_PEER_MESSAGE)
            return
        }
        if (appState.activeDirectCall != null || appState.activeGroupCall != null) return
        requestAppPermissions(videoCallPermissions()) { granted ->
            if (!granted) {
                appState = appState.copy(errorMessage = "Для видеозвонка нужны камера и микрофон.")
                return@requestAppPermissions
            }
            if (isUserBlocked(detail.peerId)) {
                appState = appState.copy(errorMessage = AloUserBlockPolicy.BLOCKED_PEER_MESSAGE)
                return@requestAppPermissions
            }
            scope.launch {
                try {
                    val session = apiClient.startDirectCall(detail.peerId, "video")
                    val handshake = AloCallSecurityV2Handshake(
                        role = AloCallSecurityV2Role.Caller,
                        callId = session.id,
                        callerId = session.callerId,
                        calleeId = session.calleeId
                    )
                    directCallSecurityHandshake = handshake
                    appState = appState.copy(
                        activeDirectCall = AloDirectCallUiState(
                            callId = session.id,
                            peerId = detail.peerId,
                            peerName = detail.title,
                            peerUsername = detail.username,
                            peerAvatarUrl = detail.avatarUrl,
                            verified = detail.verified,
                            type = session.type,
                            phase = AloDirectCallPhase.Outgoing,
                            isMuted = false,
                            isSpeakerOn = true,
                            isIncoming = false,
                            isVideoEnabled = true,
                            isFrontCamera = true,
                            startedAtMillis = 0L,
                            callSecret = "",
                            safetyEmojis = emptyList(),
                            isSecurityConfirmed = false
                        ),
                        errorMessage = null
                    )
                    val active = requireNotNull(appState.activeDirectCall)
                    ensureDirectCallClient(active)
                    directCallTonePlayer.play(AloCallTone.Ringback)
                    scheduleDirectCallRingTimeout(session.id, isOutgoing = true)
                    sendDirectCallSecurityCommit(handshake)
                } catch (error: AloApiException) {
                    directCallSecurityHandshake = null
                    if (error.status == 401) {
                        handleSessionExpired()
                    } else {
                        appState = appState.copy(errorMessage = error.message)
                    }
                } catch (_error: Exception) {
                    directCallSecurityHandshake = null
                    val failedCall = appState.activeDirectCall
                    if (failedCall?.phase == AloDirectCallPhase.Outgoing) {
                        runCatching { apiClient.endDirectCall(failedCall.callId, "ended") }
                    }
                    activeDirectCallClient?.close()
                    activeDirectCallClient = null
                    appState = appState.copy(activeDirectCall = null)
                    appState = appState.copy(errorMessage = "Не удалось начать видеозвонок.")
                }
            }
        }
    }

    fun acceptDirectCall() {
        val current = appState.activeDirectCall ?: return
        if (!current.isIncoming) return
        val permissions = if (current.type == "video") {
            videoCallPermissions()
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        requestAppPermissions(permissions) { granted ->
            if (!granted) {
                appState = appState.copy(
                    errorMessage = if (current.type == "video") {
                        "Для видеозвонка нужны камера и микрофон."
                    } else {
                        "Для звонка нужен доступ к микрофону."
                    }
                )
                declineDirectCall()
                return@requestAppPermissions
            }
            scope.launch {
                acceptingDirectCallId = current.callId
                try {
                    apiClient.acceptDirectCall(current.callId)
                    cancelDirectCallRingTimeout()
                    val connecting = current.copy(phase = AloDirectCallPhase.Connecting)
                    appState = appState.copy(activeDirectCall = connecting, errorMessage = null)
                    directCallSecurityHandshake?.didAccept = true
                    directCallTonePlayer.play(AloCallTone.Connecting)
                    ensureDirectCallClient(connecting).prepareIncoming()
                    sendDirectCallSecurityResponseIfReady()
                } catch (error: AloApiException) {
                    if (error.status == 401) {
                        handleSessionExpired()
                    } else {
                        finishDirectCall(
                            message = error.message,
                            terminalTone = AloCallTone.Failed
                        )
                    }
                } catch (_error: Exception) {
                    finishDirectCall(
                        message = "Не удалось принять звонок.",
                        terminalTone = AloCallTone.Failed
                    )
                }
            }
        }
    }

    fun declineDirectCall() {
        val current = appState.activeDirectCall ?: return
        finishDirectCall(message = null)
        scope.launch {
            runCatching { apiClient.endDirectCall(current.callId, "declined") }
        }
    }

    fun endDirectCall() {
        val current = appState.activeDirectCall ?: return
        finishDirectCall(message = null)
        scope.launch {
            val reason = if (current.phase == AloDirectCallPhase.Outgoing) "ended" else ""
            runCatching { apiClient.endDirectCall(current.callId, reason) }
        }
    }

    fun toggleDirectCallMute() {
        val current = appState.activeDirectCall ?: return
        val updated = current.copy(isMuted = !current.isMuted)
        activeDirectCallClient?.setMuted(updated.isMuted)
        appState = appState.copy(activeDirectCall = updated)
    }

    fun toggleDirectCallSpeaker() {
        val current = appState.activeDirectCall ?: return
        val updated = current.copy(isSpeakerOn = !current.isSpeakerOn)
        activeDirectCallClient?.setSpeakerOn(updated.isSpeakerOn)
        appState = appState.copy(activeDirectCall = updated)
    }

    fun toggleDirectCallVideo() = startOrStopDirectCallVideo(frontCamera = null)

    /** «Источник видео» → «Передняя»/«Задняя» while the camera is off or the screen is shared: the camera starts as chosen. */
    fun startDirectCallCamera(frontCamera: Boolean) {
        val current = appState.activeDirectCall ?: return
        if (current.isVideoEnabled && !current.isScreenSharing) {
            selectDirectCallCamera(frontCamera)
        } else {
            startOrStopDirectCallVideo(frontCamera)
        }
    }

    private fun startOrStopDirectCallVideo(frontCamera: Boolean?) {
        val current = appState.activeDirectCall ?: return
        val enabled = !current.isVideoEnabled || current.isScreenSharing
        if (!enabled) {
            activeDirectCallClient?.setVideoEnabled(false)
            val updated = current.copy(isVideoEnabled = false, isScreenSharing = false)
            appState = appState.copy(activeDirectCall = updated)
            scope.launch { sendDirectCallMediaState(updated) }
            return
        }
        requestAppPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
            if (!granted) {
                appState = appState.copy(errorMessage = "Для включения камеры нужен доступ к камере.")
                return@requestAppPermissions
            }
            scope.launch {
                val latest = appState.activeDirectCall
                if (latest == null || latest.callId != current.callId) return@launch
                val client = runCatching { ensureDirectCallClient(latest) }.getOrElse {
                    appState = appState.copy(errorMessage = "Не удалось подготовить камеру звонка.")
                    return@launch
                }
                val started = client.setVideoEnabled(true)
                val active = appState.activeDirectCall
                if (active == null || active.callId != current.callId) return@launch
                if (!started) {
                    appState = appState.copy(errorMessage = "Не удалось включить камеру.")
                    return@launch
                }
                client.setSpeakerOn(true)
                val updated = active.copy(
                    isSpeakerOn = true,
                    isVideoEnabled = true,
                    isScreenSharing = false,
                    isScreenShareStarting = false,
                    // The capturer keeps the camera it had: the state must say which one, or «Задняя» looks chosen already.
                    isFrontCamera = client.isFrontCamera
                )
                appState = appState.copy(activeDirectCall = updated, errorMessage = null)
                sendDirectCallMediaState(updated)
                if (frontCamera != null && frontCamera != updated.isFrontCamera) selectDirectCallCamera(frontCamera)
            }
        }
    }

    fun toggleDirectCallScreenShare() {
        val current = appState.activeDirectCall ?: return
        if (current.isScreenSharing) {
            activeDirectCallClient?.setScreenSharingEnabled(null)
            val updated = current.copy(
                isVideoEnabled = false,
                isScreenSharing = false,
                isScreenShareStarting = false
            )
            appState = appState.copy(activeDirectCall = updated)
            scope.launch { sendDirectCallMediaState(updated) }
            return
        }
        if (
            current.phase != AloDirectCallPhase.Connecting &&
            current.phase != AloDirectCallPhase.Active
        ) {
            appState = appState.copy(
                errorMessage = "Демонстрацию экрана можно включить после соединения."
            )
            return
        }
        appState = appState.copy(
            activeDirectCall = current.copy(isScreenShareStarting = true),
            errorMessage = null
        )
        requestScreenCapturePermission { permissionData ->
            val latest = appState.activeDirectCall
            if (latest == null || latest.callId != current.callId) {
                AloScreenShareService.stop(activity)
                return@requestScreenCapturePermission
            }
            if (permissionData == null) {
                AloScreenShareService.stop(activity)
                appState = appState.copy(
                    activeDirectCall = latest.copy(isScreenShareStarting = false)
                )
                return@requestScreenCapturePermission
            }
            scope.launch {
                val client = runCatching { ensureDirectCallClient(latest) }.getOrNull()
                val started = AloScreenShareService.startAndAwait(activity) &&
                    client?.setScreenSharingEnabled(permissionData) == true
                val active = appState.activeDirectCall
                if (active == null || active.callId != current.callId) return@launch
                if (!started) {
                    appState = appState.copy(
                        activeDirectCall = active.copy(isScreenShareStarting = false),
                        errorMessage = "Не удалось начать демонстрацию экрана."
                    )
                    return@launch
                }
                client.setSpeakerOn(true)
                val updated = active.copy(
                    isSpeakerOn = true,
                    isVideoEnabled = false,
                    isScreenSharing = true,
                    isScreenShareStarting = false
                )
                appState = appState.copy(activeDirectCall = updated, errorMessage = null)
                sendDirectCallMediaState(updated)
            }
        }
    }

    fun flipDirectCallCamera() {
        val current = appState.activeDirectCall ?: return
        if (!current.isVideoEnabled || current.isScreenSharing || current.isCameraSwitching) return
        selectDirectCallCamera(!current.isFrontCamera)
    }

    fun selectDirectCallCamera(frontCamera: Boolean) {
        val current = appState.activeDirectCall ?: return
        if (!current.isVideoEnabled || current.isScreenSharing || current.isCameraSwitching) return
        if (current.isFrontCamera == frontCamera) return
        val client = activeDirectCallClient
        if (client == null) {
            appState = appState.copy(errorMessage = "Камера звонка ещё не готова.")
            return
        }
        appState = appState.copy(
            activeDirectCall = current.copy(isCameraSwitching = true),
            errorMessage = null
        )
        client.setCameraPosition(frontCamera) { result ->
            activity.runOnUiThread {
                val latest = appState.activeDirectCall
                if (latest == null || latest.callId != current.callId) return@runOnUiThread
                result.fold(
                    onSuccess = { isFrontCamera ->
                        appState = appState.copy(
                            activeDirectCall = latest.copy(
                                isFrontCamera = isFrontCamera,
                                isCameraSwitching = false
                            ),
                            errorMessage = null
                        )
                    },
                    onFailure = { error ->
                        appState = appState.copy(
                            activeDirectCall = latest.copy(isCameraSwitching = false),
                            errorMessage = error.message ?: "Не удалось переключить камеру."
                        )
                    }
                )
            }
        }
    }

    private suspend fun ensureDirectCallClient(call: AloDirectCallUiState): AloDirectAudioCallClient {
        activeDirectCallClient?.let { currentClient ->
            if (appState.activeDirectCall?.callId == call.callId) {
                return currentClient
            }
            currentClient.close()
            activeDirectCallClient = null
        }
        val rtcConfig = rtcConfigCache ?: apiClient.loadRtcConfig().also { rtcConfigCache = it }
        return AloDirectAudioCallClient(
            context = activity,
            rtcConfig = rtcConfig,
            enableVideo = call.isVideoEnabled,
            onLocalDescription = { type, sdp ->
                scope.launch {
                    sendDirectCallLocalDescription(
                        callId = call.callId,
                        peerId = call.peerId,
                        kind = type,
                        sdp = sdp
                    )
                }
            },
            onIceCandidate = { sdpMid, sdpMLineIndex, candidate ->
                scope.launch {
                    inDirectCallSignalOrder(call.callId) {
                        apiClient.sendDirectCallSignal(
                            call.callId,
                            "ice",
                            JSONObject()
                                .put("sdpMid", sdpMid)
                                .put("sdpMLineIndex", sdpMLineIndex)
                                .put("candidate", candidate),
                            peerId = call.peerId
                        )
                    }
                }
            },
            onConnectionStateChanged = { state ->
                activity.runOnUiThread {
                    val current = appState.activeDirectCall ?: return@runOnUiThread
                    if (current.callId != call.callId) return@runOnUiThread
                    when (state) {
                        AloDirectCallConnectionState.Connected -> {
                            if (current.phase.isTerminal) return@runOnUiThread
                            cancelDirectCallDisconnectTimeout()
                            cancelDirectCallRingTimeout()
                            directCallTonePlayer.stop()
                            directCallTonePlayer.play(AloCallTone.Connected)
                            val connected = current.copy(
                                phase = AloDirectCallPhase.Active,
                                startedAtMillis = if (current.startedAtMillis > 0L) {
                                    current.startedAtMillis
                                } else {
                                    System.currentTimeMillis()
                                }
                            )
                            appState = appState.copy(
                                activeDirectCall = connected
                            )
                            scope.launch { sendDirectCallMediaState(connected) }
                        }
                        AloDirectCallConnectionState.Connecting -> {
                            if (
                                current.phase != AloDirectCallPhase.Active &&
                                current.phase != AloDirectCallPhase.Reconnecting &&
                                !current.phase.isTerminal
                            ) {
                                directCallTonePlayer.play(AloCallTone.Connecting)
                                appState = appState.copy(
                                    activeDirectCall = current.copy(phase = AloDirectCallPhase.Connecting)
                                )
                            }
                        }
                        AloDirectCallConnectionState.Disconnected -> {
                            enterDirectCallReconnecting(call.callId)
                        }
                        AloDirectCallConnectionState.Failed -> {
                            enterDirectCallReconnecting(call.callId)
                        }
                        AloDirectCallConnectionState.Closed -> {
                            if (!current.phase.isTerminal) {
                                finishDirectCall(message = null, terminalTone = AloCallTone.Ended)
                            }
                        }
                        AloDirectCallConnectionState.New -> Unit
                    }
                }
            },
            onError = { message ->
                activity.runOnUiThread {
                    val current = appState.activeDirectCall
                    if (
                        current?.callId == call.callId &&
                        (
                            current.phase == AloDirectCallPhase.Active ||
                                current.phase == AloDirectCallPhase.Reconnecting
                            )
                    ) {
                        enterDirectCallReconnecting(call.callId)
                    } else {
                        scope.launch {
                            runCatching { apiClient.endDirectCall(call.callId) }
                            finishDirectCall(message = message, terminalTone = AloCallTone.Failed)
                        }
                    }
                }
            },
            onScreenShareStopped = {
                activity.runOnUiThread {
                    val current = appState.activeDirectCall ?: return@runOnUiThread
                    if (!current.isScreenSharing) return@runOnUiThread
                    val updated = current.copy(
                        isScreenSharing = false,
                        isScreenShareStarting = false
                    )
                    appState = appState.copy(activeDirectCall = updated)
                    scope.launch { sendDirectCallMediaState(updated) }
                }
            }
        ).also { client ->
            client.setMuted(call.isMuted)
            client.setSpeakerOn(call.isSpeakerOn)
            val cameraStarted = client.setVideoEnabled(call.isVideoEnabled)
            if (call.isVideoEnabled && !cameraStarted) {
                // No camera, or it did not start: the video call goes on with the camera off (not a black «camera on»
                // preview); the peer learns it from the media state sent when the call connects.
                appState.activeDirectCall?.takeIf { it.callId == call.callId }?.let { latest ->
                    appState = appState.copy(activeDirectCall = latest.copy(isVideoEnabled = false))
                }
            }
            directCallLocalRenderer?.let(client::bindLocalRenderer)
            directCallRemoteRenderer?.let(client::bindRemoteRenderer)
            activeDirectCallClient = client
        }
    }

    /** A device without a camera (some tablets, TV boxes) has nothing to allow: its video calls start with the camera off. */
    private fun videoCallPermissions(): Array<String> =
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    private suspend fun sendDirectCallMediaState(call: AloDirectCallUiState) {
        inDirectCallSignalOrder(call.callId) {
            apiClient.sendDirectCallSignal(
                callId = call.callId,
                kind = "media-state",
                payload = JSONObject()
                    .put("videoEnabled", call.isVideoEnabled)
                    .put("screenSharing", call.isScreenSharing),
                peerId = call.peerId
            )
        }
    }

    private fun finishDirectCall(
        message: String?,
        terminalTone: AloCallTone? = AloCallTone.Ended
    ) {
        if (!message.isNullOrBlank()) {
            showDirectCallTerminal(
                phase = AloDirectCallPhase.Interrupted,
                terminalTone = terminalTone ?: AloCallTone.Failed
            )
            return
        }
        cancelDirectCallDisconnectTimeout()
        cancelDirectCallRingTimeout()
        directCallTerminalJob?.cancel()
        directCallTerminalJob = null
        directCallTonePlayer.stop()
        activeDirectCallClient?.close()
        activeDirectCallClient = null
        directCallSecurityHandshake = null
        directCallLocalRenderer = null
        directCallRemoteRenderer = null
        appState = appState.copy(
            activeDirectCall = null,
            errorMessage = null
        )
        terminalTone?.let(directCallTonePlayer::play)
    }

    private fun showDirectCallTerminal(
        phase: AloDirectCallPhase,
        terminalTone: AloCallTone
    ) {
        val current = appState.activeDirectCall ?: return
        if (!phase.isTerminal) return
        cancelDirectCallDisconnectTimeout()
        cancelDirectCallRingTimeout()
        directCallTerminalJob?.cancel()
        directCallTonePlayer.stop()

        appState = appState.copy(
            activeDirectCall = current.copy(
                phase = phase,
                isVideoEnabled = false,
                isScreenSharing = false,
                isScreenShareStarting = false,
                isRemoteVideoEnabled = false,
                isRemoteScreenSharing = false
            ),
            errorMessage = null
        )
        activeDirectCallClient?.close()
        activeDirectCallClient = null
        directCallSecurityHandshake = null
        directCallLocalRenderer = null
        directCallRemoteRenderer = null
        directCallTonePlayer.play(terminalTone)

        val callId = current.callId
        directCallTerminalJob = scope.launch {
            delay(1_450)
            val latest = appState.activeDirectCall ?: return@launch
            if (latest.callId != callId || latest.phase != phase) return@launch
            appState = appState.copy(activeDirectCall = null, errorMessage = null)
            directCallTerminalJob = null
        }
    }

    private fun enterDirectCallReconnecting(callId: Int) {
        val current = appState.activeDirectCall ?: return
        if (current.callId != callId || current.phase.isTerminal) return
        if (current.phase != AloDirectCallPhase.Reconnecting) {
            directCallTonePlayer.stop()
            appState = appState.copy(
                activeDirectCall = current.copy(phase = AloDirectCallPhase.Reconnecting)
            )
        }
        scheduleDirectCallDisconnectTimeout(callId)
        scheduleDirectCallReconnectAttempts(callId)
    }

    private fun scheduleDirectCallSecurityBootstrapRetry(callId: Int) {
        if (directCallSecurityBootstrapJob?.isActive == true) return
        directCallSecurityBootstrapJob = scope.launch {
            var retryDelay = 700L
            while (isActive) {
                delay(retryDelay)
                val current = appState.activeDirectCall ?: return@launch
                val handshake = directCallSecurityHandshake ?: return@launch
                if (
                    current.callId != callId ||
                    current.phase.isTerminal ||
                    handshake.callId != callId
                ) {
                    return@launch
                }

                val sent = when (handshake.role) {
                    AloCallSecurityV2Role.Caller -> {
                        if (handshake.didSendCommit) return@launch
                        sendDirectCallSecurityCommit(
                            handshake,
                            scheduleRetryOnFailure = false
                        )
                    }
                    AloCallSecurityV2Role.Callee -> {
                        if (!handshake.didAccept || handshake.callerCommitment == null) {
                            retryDelay = 1_200L
                            continue
                        }
                        if (handshake.didSendResponse) return@launch
                        sendDirectCallSecurityResponseIfReady(
                            scheduleRetryOnFailure = false
                        )
                    }
                }
                if (sent) return@launch
                retryDelay = minOf(retryDelay + 500L, 2_000L)
            }
        }
    }

    private fun scheduleDirectCallReconnectAttempts(callId: Int) {
        if (directCallReconnectJob?.isActive == true) return
        val handshake = directCallSecurityHandshake ?: return
        if (
            handshake.callId != callId ||
            handshake.role != AloCallSecurityV2Role.Caller ||
            !handshake.isConfirmed
        ) {
            return
        }
        directCallReconnectJob = scope.launch {
            for (retryDelay in listOf(350L, 3_000L, 6_000L, 9_000L)) {
                delay(retryDelay)
                val current = appState.activeDirectCall ?: return@launch
                val currentHandshake = directCallSecurityHandshake ?: return@launch
                val client = activeDirectCallClient ?: return@launch
                if (
                    current.callId != callId ||
                    current.phase != AloDirectCallPhase.Reconnecting ||
                    currentHandshake.callId != callId ||
                    !currentHandshake.isConfirmed
                ) {
                    return@launch
                }
                currentHandshake.reconnectGeneration += 1
                currentHandshake.reconnectOfferHash = null
                if (!client.restartIce()) {
                    currentHandshake.reconnectGeneration -= 1
                }
            }
        }
    }

    private fun scheduleDirectCallDisconnectTimeout(callId: Int) {
        if (directCallDisconnectJob?.isActive == true) return
        directCallDisconnectJob = scope.launch {
            delay(60_000)
            val current = appState.activeDirectCall ?: return@launch
            if (
                current.callId != callId ||
                current.phase != AloDirectCallPhase.Reconnecting
            ) {
                return@launch
            }
            runCatching { apiClient.endDirectCall(callId) }
            showDirectCallTerminal(
                phase = AloDirectCallPhase.Interrupted,
                terminalTone = AloCallTone.Failed
            )
        }
    }

    private fun cancelDirectCallDisconnectTimeout() {
        directCallDisconnectJob?.cancel()
        directCallDisconnectJob = null
        directCallReconnectJob?.cancel()
        directCallReconnectJob = null
        directCallSecurityBootstrapJob?.cancel()
        directCallSecurityBootstrapJob = null
    }

    private fun scheduleDirectCallRingTimeout(callId: Int, isOutgoing: Boolean) {
        directCallRingTimeoutJob?.cancel()
        directCallRingTimeoutJob = scope.launch {
            delay(35_000)
            val current = appState.activeDirectCall ?: return@launch
            if (current.callId != callId) return@launch
            if (current.phase != AloDirectCallPhase.Outgoing && current.phase != AloDirectCallPhase.Incoming) return@launch
            runCatching { apiClient.endDirectCall(callId) }
            if (isOutgoing) {
                showDirectCallTerminal(
                    phase = AloDirectCallPhase.NoAnswer,
                    terminalTone = AloCallTone.NoAnswer
                )
            } else {
                finishDirectCall(message = null, terminalTone = AloCallTone.Ended)
            }
        }
    }

    private fun cancelDirectCallRingTimeout() {
        directCallRingTimeoutJob?.cancel()
        directCallRingTimeoutJob = null
    }

    fun bindDirectCallLocalRenderer(renderer: AloCallRenderer?) {
        directCallLocalRenderer = renderer
        activeDirectCallClient?.bindLocalRenderer(renderer)
    }

    fun unbindDirectCallLocalRenderer(renderer: AloCallRenderer) {
        if (directCallLocalRenderer !== renderer) return
        directCallLocalRenderer = null
        activeDirectCallClient?.unbindLocalRenderer(renderer)
    }

    fun bindDirectCallRemoteRenderer(renderer: AloCallRenderer?) {
        directCallRemoteRenderer = renderer
        activeDirectCallClient?.bindRemoteRenderer(renderer)
    }

    fun unbindDirectCallRemoteRenderer(renderer: AloCallRenderer) {
        if (directCallRemoteRenderer !== renderer) return
        directCallRemoteRenderer = null
        activeDirectCallClient?.unbindRemoteRenderer(renderer)
    }

    fun bindGroupCallLocalRenderer(renderer: AloCallRenderer?) {
        groupCallLocalRenderer = renderer
        bindActiveGroupVideoClient()
    }

    fun unbindGroupCallLocalRenderer(renderer: AloCallRenderer) {
        if (groupCallLocalRenderer !== renderer) return
        groupCallLocalRenderer = null
        activeGroupCallClients.values.forEach { client ->
            client.unbindLocalRenderer(renderer)
        }
    }

    fun bindGroupCallRemoteRenderer(renderer: AloCallRenderer?) {
        groupCallRemoteRenderer = renderer
        bindActiveGroupVideoClient()
    }

    fun unbindGroupCallRemoteRenderer(renderer: AloCallRenderer) {
        if (groupCallRemoteRenderer !== renderer) return
        groupCallRemoteRenderer = null
        activeGroupCallClients.values.forEach { client ->
            client.unbindRemoteRenderer(renderer)
        }
    }

    fun bindGroupSfuRenderer(userId: Int, renderer: LiveKitSurfaceViewRenderer?) {
        activeGroupSfuClient?.bindRenderer(userId, renderer)
    }

    fun unbindGroupSfuRenderer(userId: Int, renderer: LiveKitSurfaceViewRenderer) {
        activeGroupSfuClient?.unbindRenderer(userId, renderer)
    }

    // ---- Telegram's video-chat controls (iOS's AloGroupCallCoordinator) ----

    private fun isMutedByAdmin(call: AloGroupCallUiState): Boolean =
        call.localUserId in call.controls.mutedByAdmin && !call.controls.canManage

    private fun updateControls(change: (AloGroupCallControls) -> AloGroupCallControls) {
        val current = appState.activeGroupCall ?: return
        appState = appState.copy(activeGroupCall = current.copy(controls = change(current.controls)))
    }

    private fun startGroupControlsPolling(callId: Int) {
        groupControlsJob?.cancel()
        groupControlsJob = scope.launch {
            while (true) {
                refreshGroupCallControls(callId)
                kotlinx.coroutines.delay(2_500)
            }
        }
    }

    private suspend fun refreshGroupCallControls(callId: Int) {
        if (isGroupCallPreview) return
        val state = runCatching { apiClient.loadGroupCallControls(callId) }.getOrNull() ?: return
        val current = appState.activeGroupCall ?: return
        if (current.callId != callId) return
        val wasMutedByAdmin = isMutedByAdmin(current)
        // A recording this phone runs stays shown as its own until it stops.
        val merged = if (current.isRecordingHere) state.copy(recording = current.controls.recording) else state
        val updated = current.copy(controls = merged)
        appState = appState.copy(activeGroupCall = updated)
        // An admin turned this microphone off: follow it, as Telegram does.
        if (!wasMutedByAdmin && isMutedByAdmin(updated) && !updated.isMuted) toggleGroupCallMute()
    }

    /** Raise or lower a hand; while muted by an admin the big button does this. */
    private fun toggleGroupCallHand() {
        val current = appState.activeGroupCall ?: return
        val raised = current.localUserId !in current.controls.raisedHands
        updateControls { controls ->
            controls.copy(raisedHands = if (raised) controls.raisedHands + (current.localUserId to System.currentTimeMillis())
            else controls.raisedHands - current.localUserId)
        }
        if (isGroupCallPreview) return
        scope.launch {
            runCatching { apiClient.setGroupCallHand(current.callId, raised) }
                .onFailure { appState = appState.copy(errorMessage = "Не удалось поднять руку.") }
        }
    }

    fun setGroupCallMicrophone(userId: Int, allowed: Boolean) {
        val current = appState.activeGroupCall ?: return
        if (!current.controls.canManage) return
        updateControls { controls ->
            if (allowed) controls.copy(mutedByAdmin = controls.mutedByAdmin - userId, raisedHands = controls.raisedHands - userId)
            else controls.copy(mutedByAdmin = controls.mutedByAdmin + userId)
        }
        if (isGroupCallPreview) return
        scope.launch {
            runCatching { apiClient.muteGroupCallParticipant(current.callId, userId, muted = !allowed) }
                .onFailure {
                    refreshGroupCallControls(current.callId)
                    appState = appState.copy(errorMessage = (it as? AloApiException)?.message ?: "Не удалось изменить микрофон участника.")
                }
        }
    }

    fun muteGroupCallEveryone(muted: Boolean) {
        val current = appState.activeGroupCall ?: return
        if (!current.controls.canManage) return
        updateControls { it.copy(joinMuted = muted) }
        if (isGroupCallPreview) {
            val others = current.participants.map { it.id }.toSet() - current.localUserId
            updateControls { if (muted) it.copy(mutedByAdmin = it.mutedByAdmin + others) else it.copy(mutedByAdmin = it.mutedByAdmin - others) }
            return
        }
        scope.launch {
            try {
                val userIds = apiClient.muteGroupCallEveryone(current.callId, muted)
                updateControls { if (muted) it.copy(mutedByAdmin = it.mutedByAdmin + userIds) else it.copy(mutedByAdmin = it.mutedByAdmin - userIds) }
                appState = appState.copy(notice = if (muted) "Микрофоны участников выключены" else "Все участники могут говорить")
            } catch (error: Exception) {
                refreshGroupCallControls(current.callId)
                appState = appState.copy(errorMessage = (error as? AloApiException)?.message ?: "Не удалось изменить микрофоны.")
            }
        }
    }

    /** Starts recording on this phone, or stops (and saves) the recording it runs. */
    fun toggleGroupCallRecording() {
        val current = appState.activeGroupCall ?: return
        if (!current.controls.canManage) return
        if (current.isRecordingHere) {
            finishGroupCallRecording(saving = true)
            return
        }
        if (current.controls.recording != null) {
            appState = appState.copy(notice = "Запись уже ведёт другой администратор")
            return
        }
        val recording = AloGroupCallRecording(System.currentTimeMillis(), current.localUserId, current.roomTitle)
        if (isGroupCallPreview) {
            appState = appState.copy(activeGroupCall = current.copy(isRecordingHere = true, controls = current.controls.copy(recording = recording)))
            return
        }
        val client = activeGroupSfuClient
        if (client == null) {
            appState = appState.copy(errorMessage = "Запись доступна, когда видеочат идёт через медиасервер.")
            return
        }
        val stamp = java.time.Instant.now().toString().replace(":", "-")
        val recorder = AloGroupCallRecorder(java.io.File(activity.cacheDir, "call-recordings/alo-video-chat-$stamp.m4a"))
        try {
            recorder.start()
        } catch (error: Exception) {
            appState = appState.copy(errorMessage = "Не удалось начать запись: ${error.message}")
            return
        }
        groupRecorder = recorder
        client.startRecording(recorder)
        appState = appState.copy(activeGroupCall = current.copy(isRecordingHere = true, controls = current.controls.copy(recording = recording)))
        scope.launch {
            runCatching { apiClient.setGroupCallRecording(current.callId, active = true, title = current.roomTitle) }
        }
    }

    private fun finishGroupCallRecording(saving: Boolean) {
        val current = appState.activeGroupCall
        if (current != null) {
            appState = appState.copy(activeGroupCall = current.copy(isRecordingHere = false, controls = current.controls.copy(recording = null)))
        }
        if (isGroupCallPreview) return
        val recorder = groupRecorder ?: return
        groupRecorder = null
        activeGroupSfuClient?.stopRecording()
        val callId = current?.callId ?: 0
        val title = current?.roomTitle?.ifBlank { null } ?: "Видеочат"
        scope.launch {
            val seconds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { recorder.stop() }.getOrNull() }
            if (callId > 0) runCatching { apiClient.setGroupCallRecording(callId, active = false, title = "") }
            if (saving && seconds != null) onGroupCallRecording?.invoke(recorder.file, seconds, title)
        }
    }

    /** `--ez alo_ui_group_call true`: a running video chat with a raised hand, admin mutes and a recording. */
    fun installGroupCallPreview(meId: Int, member: Boolean) {
        isGroupCallPreview = true
        val people = listOf(meId to "Тест интерфейса", 990010 to "Маша", 990011 to "Александр", 990012 to "Даша", 990013 to "Слава")
        val now = System.currentTimeMillis()
        appState = appState.copy(
            activeGroupCall = AloGroupCallUiState(
                callId = 4242, roomId = 77, roomTitle = "Команда Alo", type = "video",
                participants = people.map { (id, name) -> AloEntitySummary(id = id, name = name, username = "", avatarUrl = "", verified = false) },
                speakingParticipantIds = setOf(990011),
                isMuted = member, isSpeakerOn = true, isVideoEnabled = false, localUserId = meId,
                isRecordingHere = !member,
                controls = AloGroupCallControls(
                    canManage = !member,
                    joinMuted = member,
                    raisedHands = mapOf(990012 to now - 40_000),
                    mutedByAdmin = if (member) setOf(meId, 990012, 990013) else setOf(990012, 990013),
                    recording = AloGroupCallRecording(now - 95_000, if (member) 990011 else meId, "Команда Alo")
                )
            )
        )
    }

    fun toggleGroupCallMute() {
        val current = appState.activeGroupCall ?: return
        if (isMutedByAdmin(current) && current.isMuted) {
            toggleGroupCallHand()
            return
        }
        val updated = current.copy(isMuted = !current.isMuted)
        if (isGroupCallPreview) {
            appState = appState.copy(activeGroupCall = updated)
            return
        }
        val sfuClient = activeGroupSfuClient
        if (sfuClient != null) {
            scope.launch {
                runCatching { sfuClient.setMuted(updated.isMuted) }
                    .onFailure {
                        appState = appState.copy(errorMessage = "Не удалось изменить микрофон.")
                    }
            }
        } else {
            activeGroupCallClients.values.forEach { client ->
                client.setMuted(updated.isMuted)
            }
        }
        appState = appState.copy(activeGroupCall = updated)
        if (sfuClient == null) {
            broadcastGroupMediaState(updated)
        }
    }

    fun toggleGroupCallSpeaker() {
        val current = appState.activeGroupCall ?: return
        val updated = current.copy(isSpeakerOn = !current.isSpeakerOn)
        activeGroupSfuClient?.setSpeakerEnabled(updated.isSpeakerOn)
            ?: activeGroupCallClients.values.forEach { client ->
                client.setSpeakerOn(updated.isSpeakerOn)
            }
        appState = appState.copy(activeGroupCall = updated)
    }

    private fun setGroupCallVideoEnabled(current: AloGroupCallUiState, enabled: Boolean) {
        val meId = appState.bootstrap?.me?.id ?: 0
        val updated = current.copy(
            isVideoEnabled = enabled,
            isSpeakerOn = if (enabled) true else current.isSpeakerOn,
            videoParticipantIds = if (enabled) {
                current.videoParticipantIds + meId
            } else {
                current.videoParticipantIds - meId
            }
        )
        val sfuClient = activeGroupSfuClient
        if (sfuClient != null) {
            if (enabled) sfuClient.setSpeakerEnabled(true)
            scope.launch {
                runCatching { sfuClient.setVideoEnabled(enabled) }
                    .onFailure {
                        appState = appState.copy(
                            activeGroupCall = appState.activeGroupCall?.copy(isVideoEnabled = false),
                            errorMessage = "Не удалось изменить состояние камеры."
                        )
                    }
            }
        } else {
            activeGroupCallClients.values.forEach {
                if (enabled) it.setSpeakerOn(true)
                it.setVideoEnabled(enabled)
            }
        }
        appState = appState.copy(activeGroupCall = updated)
        if (sfuClient == null) {
            broadcastGroupMediaState(updated)
        }
    }

    fun toggleGroupCallVideo() {
        val current = appState.activeGroupCall ?: return
        if (activeGroupSfuClient == null && current.participants.size > 2) {
            appState = appState.copy(errorMessage = "Камера для больших видеочатов будет доступна после подключения медиасервера.")
            return
        }
        if (current.isVideoEnabled) {
            setGroupCallVideoEnabled(current, false)
            return
        }
        requestAppPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
            if (!granted) {
                appState = appState.copy(errorMessage = "Для включения камеры разрешите Alo доступ к камере.")
                return@requestAppPermissions
            }
            val latest = appState.activeGroupCall ?: return@requestAppPermissions
            setGroupCallVideoEnabled(latest, true)
        }
    }

    fun flipGroupCallCamera() {
        val current = appState.activeGroupCall ?: return
        if (current.type != "video" || current.isCameraSwitching) return
        val sfuClient = activeGroupSfuClient
        if (sfuClient != null) {
            appState = appState.copy(
                activeGroupCall = current.copy(isCameraSwitching = true),
                errorMessage = null
            )
            val nextFront = !current.isFrontCamera
            runCatching { sfuClient.setCameraPosition(nextFront) }
                .onSuccess {
                    appState = appState.copy(
                        activeGroupCall = appState.activeGroupCall?.copy(
                            isFrontCamera = nextFront,
                            isCameraSwitching = false
                        )
                    )
                }
                .onFailure {
                    appState = appState.copy(
                        activeGroupCall = appState.activeGroupCall?.copy(isCameraSwitching = false),
                        errorMessage = it.message ?: "Не удалось переключить камеру."
                    )
                }
            return
        }
        val clients = activeGroupCallClients.values.toList()
        if (clients.isEmpty()) {
            appState = appState.copy(errorMessage = "Камера звонка ещё не готова.")
            return
        }
        appState = appState.copy(
            activeGroupCall = current.copy(isCameraSwitching = true),
            errorMessage = null
        )
        var remaining = clients.size
        var resolvedFrontCamera = current.isFrontCamera
        var failureMessage: String? = null
        clients.forEach { client ->
            client.switchCamera { result ->
                activity.runOnUiThread {
                    val latest = appState.activeGroupCall
                    if (latest == null || latest.callId != current.callId) return@runOnUiThread
                    result
                        .onSuccess { resolvedFrontCamera = it }
                        .onFailure { failureMessage = it.message ?: "Не удалось переключить камеру." }
                    remaining -= 1
                    if (remaining == 0) {
                        appState = appState.copy(
                            activeGroupCall = latest.copy(
                                isFrontCamera = resolvedFrontCamera,
                                isCameraSwitching = false
                            ),
                            errorMessage = failureMessage
                        )
                    }
                }
            }
        }
    }

    fun selectGroupCallCamera(frontCamera: Boolean) {
        val current = appState.activeGroupCall ?: return
        if (current.type != "video" || current.isCameraSwitching) return
        if (current.isVideoEnabled) {
            if (current.isFrontCamera != frontCamera) {
                flipGroupCallCamera()
            }
            return
        }
        appState = appState.copy(
            activeGroupCall = current.copy(isFrontCamera = frontCamera),
            errorMessage = null
        )
        toggleGroupCallVideo()
    }

    fun leaveGroupCall() {
        val current = appState.activeGroupCall ?: return
        groupControlsJob?.cancel()
        groupControlsJob = null
        if (current.isRecordingHere) finishGroupCallRecording(saving = true)
        if (isGroupCallPreview) {
            isGroupCallPreview = false
            appState = appState.copy(activeGroupCall = null)
            return
        }
        scope.launch {
            runCatching { apiClient.leaveGroupCall(current.callId) }
            activeGroupSfuClient?.close()
            activeGroupSfuClient = null
            latestGroupSfuSnapshot = AloGroupSfuSnapshot()
            activeGroupCallClients.values.forEach { client ->
                runCatching { client.close() }
            }
            activeGroupCallClients.clear()
            bindGroupCallLocalRenderer(null)
            bindGroupCallRemoteRenderer(null)
            appState = appState.copy(activeGroupCall = null)
        }
    }

    private fun parseGroupCallUser(payload: JSONObject): AloEntitySummary? {
        val user = payload.optJSONObject("user") ?: return null
        val id = user.optInt("id")
        if (id <= 0) return null
        return AloEntitySummary(
            id = id,
            name = user.optString("name"),
            username = user.optString("username"),
            avatarUrl = user.optString("avatarUrl").ifBlank { user.optString("avatarEmoji") },
            verified = user.optBoolean("verified"),
            isChannel = false
        )
    }

    private suspend fun syncGroupVoiceMesh(call: AloGroupCallUiState) {
        if (!AloGroupCallAudioPolicy.canUsePeerToPeerFallback(call.participants.size)) {
            appState = appState.copy(errorMessage = AloGroupCallAudioPolicy.SFU_REQUIRED_MESSAGE)
            return
        }
        val me = appState.bootstrap?.me ?: return
        val peers = call.participants
            .filter { it.id > 0 && it.id != me.id }
            .sortedBy { it.id }
        peers.forEach { peer ->
            if (me.id < peer.id && !activeGroupCallClients.containsKey(peer.id)) {
                val client = ensureGroupCallClient(call, peer.id)
                client.prepareIncoming()
                client.startOutgoing()
            }
        }
    }

    private suspend fun ensureGroupCallClient(call: AloGroupCallUiState, remoteUserId: Int): AloDirectAudioCallClient {
        activeGroupCallClients[remoteUserId]?.let { existing ->
            if (shouldBindGroupVideo(call, remoteUserId)) {
                existing.bindLocalRenderer(groupCallLocalRenderer)
                existing.bindRemoteRenderer(groupCallRemoteRenderer)
            }
            return existing
        }
        val rtcConfig = rtcConfigCache ?: apiClient.loadRtcConfig().also { rtcConfigCache = it }
        val client = AloDirectAudioCallClient(
            context = activity,
            rtcConfig = rtcConfig,
            enableVideo = shouldBindGroupVideo(call, remoteUserId),
            onLocalDescription = { type, sdp ->
                scope.launch {
                    runCatching {
                        apiClient.sendGroupCallSignal(
                            callId = call.callId,
                            kind = type,
                            payload = JSONObject().put("sdp", sdp),
                            toUserId = remoteUserId
                        )
                    }
                }
            },
            onIceCandidate = { sdpMid, sdpMLineIndex, candidate ->
                scope.launch {
                    runCatching {
                        apiClient.sendGroupCallSignal(
                            callId = call.callId,
                            kind = "ice",
                            payload = JSONObject()
                                .put("sdpMid", sdpMid)
                                .put("sdpMLineIndex", sdpMLineIndex)
                                .put("candidate", candidate),
                            toUserId = remoteUserId
                        )
                    }
                }
            },
            onConnectionStateChanged = { state ->
                if (state == AloDirectCallConnectionState.Failed || state == AloDirectCallConnectionState.Disconnected) {
                    activity.runOnUiThread {
                        activeGroupCallClients[remoteUserId]?.restartIce()
                    }
                }
            },
            onError = { message ->
                activity.runOnUiThread {
                    appState = appState.copy(errorMessage = message)
                    activeGroupCallClients.remove(remoteUserId)?.close()
                }
            }
        ).also { created ->
            created.setMuted(call.isMuted)
            created.setSpeakerOn(call.isSpeakerOn)
            created.setVideoEnabled(call.isVideoEnabled)
            if (shouldBindGroupVideo(call, remoteUserId)) {
                created.bindLocalRenderer(groupCallLocalRenderer)
                created.bindRemoteRenderer(groupCallRemoteRenderer)
            }
        }
        activeGroupCallClients[remoteUserId] = client
        return client
    }

    private fun broadcastGroupMediaState(call: AloGroupCallUiState) {
        val meId = appState.bootstrap?.me?.id ?: return
        call.participants
            .filter { it.id > 0 && it.id != meId }
            .forEach { sendGroupMediaState(call, it.id) }
    }

    private fun sendGroupMediaState(call: AloGroupCallUiState, toUserId: Int) {
        scope.launch {
            runCatching {
                apiClient.sendGroupCallSignal(
                    callId = call.callId,
                    kind = "media-state",
                    payload = JSONObject()
                        .put("videoEnabled", call.isVideoEnabled)
                        .put("muted", call.isMuted),
                    toUserId = toUserId
                )
            }
        }
    }

    private fun shouldBindGroupVideo(call: AloGroupCallUiState, remoteUserId: Int): Boolean {
        if (call.type != "video" || call.participants.size > 2) return false
        return remoteUserId == resolveGroupVideoPeerId(call)
    }

    private fun resolveGroupVideoPeerId(call: AloGroupCallUiState): Int {
        val meId = appState.bootstrap?.me?.id ?: return 0
        return call.participants.firstOrNull { it.id > 0 && it.id != meId }?.id ?: 0
    }

    private fun bindActiveGroupVideoClient(call: AloGroupCallUiState? = appState.activeGroupCall) {
        val active = call ?: return
        val peerId = resolveGroupVideoPeerId(active)
        activeGroupCallClients.forEach { (remoteUserId, client) ->
            if (shouldBindGroupVideo(active, remoteUserId) && remoteUserId == peerId) {
                client.bindLocalRenderer(groupCallLocalRenderer)
                client.bindRemoteRenderer(groupCallRemoteRenderer)
            } else {
                client.bindLocalRenderer(null)
                client.bindRemoteRenderer(null)
            }
        }
    }

    private fun resolveDirectCallPeer(
        userId: Int,
        fallback: AloEntitySummary?
    ): AloEntitySummary {
        fallback?.let { return it }
        val bootstrap = appState.bootstrap
        val fromConversation = bootstrap?.messagesConversations
            ?.firstOrNull { it.peer.id == userId }
            ?.peer
        if (fromConversation != null) {
            return AloEntitySummary(
                id = fromConversation.id,
                name = fromConversation.name,
                username = fromConversation.username,
                avatarUrl = fromConversation.avatarUrl,
                verified = fromConversation.verified,
                isChannel = false
            )
        }
        val fromSearch = bootstrap?.searchUsers?.firstOrNull { it.id == userId }
        if (fromSearch != null) return fromSearch
        return AloEntitySummary(
            id = userId,
            name = "Пользователь",
            username = "",
            avatarUrl = "",
            verified = false,
            isChannel = false
        )
    }

    private suspend fun sendDirectCallLocalDescription(
        callId: Int,
        peerId: Int,
        kind: String,
        sdp: String
    ) {
        val handshake = directCallSecurityHandshake
        if (handshake == null || handshake.callId != callId) {
            failDirectCallSecurity()
            return
        }
        try {
            val fingerprint = requireNotNull(AloCallSecurityV2.dtlsFingerprint(sdp))
            val payload = when (kind.lowercase()) {
                "offer" -> {
                    require(handshake.role == AloCallSecurityV2Role.Caller)
                    val commitment = requireNotNull(handshake.callerCommitment)
                    val callerPublicKey = requireNotNull(handshake.callerPublicKey)
                    val callerNonce = requireNotNull(handshake.callerNonce)
                    val calleePublicKey = requireNotNull(handshake.calleePublicKey)
                    val calleeNonce = requireNotNull(handshake.calleeNonce)
                    if (handshake.offerFingerprint.isNotBlank()) {
                        require(handshake.offerFingerprint == fingerprint)
                    }
                    handshake.offerFingerprint = fingerprint
                    val sharedKey = handshake.deriveSharedKeyIfPossible()
                    val offerHash = directCallOfferTranscriptHash(handshake)
                    val callerProof = AloCallSecurityV2.proof(
                        sharedKey = sharedKey,
                        role = "caller-offer",
                        transcriptHash = offerHash
                    )
                    JSONObject()
                        .put("sdp", sdp)
                        .put("securityVersion", AloCallSecurityV2.VERSION)
                        .put(
                            "callerCommitment",
                            AloCallSecurityV2.base64Url(commitment)
                        )
                        .put(
                            "callerPublicKey",
                            AloCallSecurityV2.base64Url(callerPublicKey)
                        )
                        .put("callerNonce", AloCallSecurityV2.base64Url(callerNonce))
                        .put(
                            "calleePublicKey",
                            AloCallSecurityV2.base64Url(calleePublicKey)
                        )
                        .put("calleeNonce", AloCallSecurityV2.base64Url(calleeNonce))
                        .put("offerFingerprint", fingerprint)
                        .put("callerProof", AloCallSecurityV2.base64Url(callerProof))
                }
                "answer" -> {
                    require(handshake.role == AloCallSecurityV2Role.Callee)
                    val commitment = requireNotNull(handshake.callerCommitment)
                    val calleePublicKey = requireNotNull(handshake.calleePublicKey)
                    val calleeNonce = requireNotNull(handshake.calleeNonce)
                    require(handshake.offerFingerprint.isNotBlank())
                    if (handshake.answerFingerprint.isNotBlank()) {
                        require(handshake.answerFingerprint == fingerprint)
                    }
                    handshake.answerFingerprint = fingerprint
                    val sharedKey = handshake.deriveSharedKeyIfPossible()
                    val transcript = directCallFullTranscriptHash(handshake)
                    handshake.transcriptHash = transcript
                    val calleeProof = AloCallSecurityV2.proof(
                        sharedKey = sharedKey,
                        role = "callee-answer",
                        transcriptHash = transcript
                    )
                    JSONObject()
                        .put("sdp", sdp)
                        .put("securityVersion", AloCallSecurityV2.VERSION)
                        .put(
                            "callerCommitment",
                            AloCallSecurityV2.base64Url(commitment)
                        )
                        .put(
                            "calleePublicKey",
                            AloCallSecurityV2.base64Url(calleePublicKey)
                        )
                        .put("calleeNonce", AloCallSecurityV2.base64Url(calleeNonce))
                        .put("offerFingerprint", handshake.offerFingerprint)
                        .put("answerFingerprint", fingerprint)
                        .put("transcriptHash", AloCallSecurityV2.base64Url(transcript))
                        .put("calleeProof", AloCallSecurityV2.base64Url(calleeProof))
                }
                "reconnect-offer" -> {
                    require(handshake.role == AloCallSecurityV2Role.Caller)
                    require(handshake.isConfirmed)
                    require(handshake.reconnectGeneration > 0)
                    require(fingerprint == handshake.offerFingerprint)
                    val initialTranscript = requireNotNull(handshake.transcriptHash)
                    val sharedKey = requireNotNull(handshake.sharedKey)
                    val offerSdpHash = AloCallSecurityV2.sdpHash(sdp)
                    handshake.reconnectOfferHash = offerSdpHash
                    val reconnectHash = AloCallSecurityV2.reconnectTranscriptHash(
                        callId = callId,
                        generation = handshake.reconnectGeneration,
                        initialTranscriptHash = initialTranscript,
                        offerFingerprint = fingerprint,
                        offerSdpHash = offerSdpHash
                    )
                    val callerProof = AloCallSecurityV2.proof(
                        sharedKey = sharedKey,
                        role = "caller-reconnect-offer",
                        transcriptHash = reconnectHash
                    )
                    JSONObject()
                        .put("sdp", sdp)
                        .put("securityVersion", AloCallSecurityV2.VERSION)
                        .put("generation", handshake.reconnectGeneration)
                        .put(
                            "initialTranscriptHash",
                            AloCallSecurityV2.base64Url(initialTranscript)
                        )
                        .put("offerFingerprint", fingerprint)
                        .put("offerSdpHash", AloCallSecurityV2.base64Url(offerSdpHash))
                        .put("callerProof", AloCallSecurityV2.base64Url(callerProof))
                }
                "reconnect-answer" -> {
                    require(handshake.role == AloCallSecurityV2Role.Callee)
                    require(handshake.isConfirmed)
                    require(handshake.reconnectGeneration > 0)
                    require(fingerprint == handshake.answerFingerprint)
                    val initialTranscript = requireNotNull(handshake.transcriptHash)
                    val sharedKey = requireNotNull(handshake.sharedKey)
                    val offerSdpHash = requireNotNull(handshake.reconnectOfferHash)
                    val answerSdpHash = AloCallSecurityV2.sdpHash(sdp)
                    val reconnectHash = AloCallSecurityV2.reconnectTranscriptHash(
                        callId = callId,
                        generation = handshake.reconnectGeneration,
                        initialTranscriptHash = initialTranscript,
                        offerFingerprint = handshake.offerFingerprint,
                        offerSdpHash = offerSdpHash,
                        answerFingerprint = fingerprint,
                        answerSdpHash = answerSdpHash
                    )
                    val calleeProof = AloCallSecurityV2.proof(
                        sharedKey = sharedKey,
                        role = "callee-reconnect-answer",
                        transcriptHash = reconnectHash
                    )
                    JSONObject()
                        .put("sdp", sdp)
                        .put("securityVersion", AloCallSecurityV2.VERSION)
                        .put("generation", handshake.reconnectGeneration)
                        .put(
                            "initialTranscriptHash",
                            AloCallSecurityV2.base64Url(initialTranscript)
                        )
                        .put("offerFingerprint", handshake.offerFingerprint)
                        .put("offerSdpHash", AloCallSecurityV2.base64Url(offerSdpHash))
                        .put("answerFingerprint", fingerprint)
                        .put("answerSdpHash", AloCallSecurityV2.base64Url(answerSdpHash))
                        .put("calleeProof", AloCallSecurityV2.base64Url(calleeProof))
                }
                else -> error("Unsupported local description")
            }
            sendDirectCallSignal(
                callId = callId,
                peerId = peerId,
                kind = kind.lowercase(),
                payload = payload
            )
        } catch (_error: Exception) {
            failDirectCallSecurity()
        }
    }

    private fun directCallOfferTranscriptHash(
        handshake: AloCallSecurityV2Handshake
    ): ByteArray = AloCallSecurityV2.offerTranscriptHash(
        callId = handshake.callId,
        callerId = handshake.callerId,
        calleeId = handshake.calleeId,
        callerCommitment = requireNotNull(handshake.callerCommitment),
        callerPublicKey = requireNotNull(handshake.callerPublicKey),
        callerNonce = requireNotNull(handshake.callerNonce),
        calleePublicKey = requireNotNull(handshake.calleePublicKey),
        calleeNonce = requireNotNull(handshake.calleeNonce),
        offerFingerprint = handshake.offerFingerprint
    )

    private fun directCallFullTranscriptHash(
        handshake: AloCallSecurityV2Handshake
    ): ByteArray = AloCallSecurityV2.transcriptHash(
        callId = handshake.callId,
        callerId = handshake.callerId,
        calleeId = handshake.calleeId,
        callerCommitment = requireNotNull(handshake.callerCommitment),
        callerPublicKey = requireNotNull(handshake.callerPublicKey),
        callerNonce = requireNotNull(handshake.callerNonce),
        calleePublicKey = requireNotNull(handshake.calleePublicKey),
        calleeNonce = requireNotNull(handshake.calleeNonce),
        offerFingerprint = handshake.offerFingerprint,
        answerFingerprint = handshake.answerFingerprint
    )

    private fun markDirectCallSecurityConfirmed(
        handshake: AloCallSecurityV2Handshake
    ) {
        val current = appState.activeDirectCall ?: error("Missing active call")
        require(current.callId == handshake.callId)
        val sharedKey = requireNotNull(handshake.sharedKey)
        val transcript = requireNotNull(handshake.transcriptHash)
        val callerPublicKey = requireNotNull(handshake.callerPublicKey)
        val emojis = AloCallSecurityV2.emojis(
            sharedKey = sharedKey,
            transcriptHash = transcript,
            callerPublicKey = callerPublicKey
        )
        handshake.isConfirmed = true
        appState = appState.copy(
            activeDirectCall = current.copy(
                callSecret = AloCallSecurityV2.base64Url(sharedKey),
                safetyEmojis = emojis,
                isSecurityConfirmed = true
            )
        )
    }

    private suspend fun sendDirectCallSignal(
        callId: Int,
        peerId: Int,
        kind: String,
        payload: JSONObject,
        retryBootstrapOnFailure: Boolean = false
    ): Boolean {
        if (appState.activeDirectCall?.callId != callId) return false
        var retryableFailure = false
        val failureMessage = try {
            val sent = directCallSignalOrder.withLock {
                // The call may have ended while this signal waited for the ones before it.
                if (appState.activeDirectCall?.callId != callId) return@withLock false
                apiClient.sendDirectCallSignal(
                    callId = callId,
                    kind = kind,
                    payload = payload,
                    peerId = peerId
                )
                true
            }
            return sent
        } catch (error: AloApiException) {
            // 409: the call is already over on the server (the other side hung up, or another device of ours ended it).
            // That is an ordinary end of the call, not a failed security check: no «Защищённая сессия… не активна».
            if (error.status == 409) {
                if (appState.activeDirectCall?.callId == callId) {
                    finishDirectCall(message = null, terminalTone = AloCallTone.Ended)
                }
                return false
            }
            retryableFailure = isRetryableDirectCallSecurityBootstrapFailure(error)
            error.message
        } catch (_error: Exception) {
            retryableFailure = true
            "Не удалось отправить защищённый сигнал звонка."
        }
        if (retryBootstrapOnFailure && retryableFailure) {
            return false
        }
        if (
            kind != "ice" &&
            kind != "media-state" &&
            kind != "reconnect-offer" &&
            kind != "reconnect-answer"
        ) {
            failDirectCallSecurity(failureMessage)
        }
        return false
    }

    /**
     * Signals of a direct call leave one by one in the order they were made: the answer before its ICE candidates, and
     * nothing that was still waiting once the call is over (a backlog used to keep the phone busy long after hang-up).
     * Failures of these best-effort signals are ignored, as before.
     */
    private suspend fun inDirectCallSignalOrder(callId: Int, send: suspend () -> Unit) {
        runCatching {
            directCallSignalOrder.withLock {
                if (appState.activeDirectCall?.callId == callId) send()
            }
        }
    }

    private fun isRetryableDirectCallSecurityBootstrapFailure(error: AloApiException): Boolean {
        val fatalSecurityCodes = setOf(
            "E2EE_KEY_CHANGED",
            "E2EE_IDENTITY_DOWNGRADE_BLOCKED",
            "E2EE_SENDER_IDENTITY_INVALID",
            "E2EE_SENDER_IDENTITY_MISMATCH",
            "LIBSIGNAL_IDENTITY_FINGERPRINT_INVALID"
        )
        if (error.code in fatalSecurityCodes) return false
        if (error.status <= 0) return true
        return error.status == 408 ||
            error.status == 425 ||
            error.status == 429 ||
            error.status >= 500
    }

    private fun directCallAnsweredElsewhere(callId: Int): Boolean {
        val current = appState.activeDirectCall ?: return false
        val handshake = directCallSecurityHandshake
        return current.callId == callId &&
            current.isIncoming &&
            current.phase == AloDirectCallPhase.Incoming &&
            acceptingDirectCallId != callId &&
            handshake?.didAccept != true
    }

    private suspend fun failDirectCallSecurity(
        message: String = "Проверка защищённого соединения звонка не прошла."
    ) {
        val current = appState.activeDirectCall ?: return
        if (directCallAnsweredElsewhere(current.callId)) {
            finishDirectCall(message = null, terminalTone = null)
            return
        }
        runCatching {
            apiClient.endDirectCall(current.callId, "security_mismatch")
        }
        showDirectCallTerminal(
            phase = AloDirectCallPhase.Interrupted,
            terminalTone = AloCallTone.Failed
        )
        appState = appState.copy(errorMessage = message)
    }
}

internal object AloGroupCallAudioPolicy {
    const val MAX_PEER_TO_PEER_PARTICIPANTS = 2
    const val SFU_REQUIRED_MESSAGE =
        "Для видеочата с тремя и более участниками требуется медиасервер."

    fun canUsePeerToPeerFallback(participantCount: Int): Boolean =
        participantCount in 1..MAX_PEER_TO_PEER_PARTICIPANTS
}
