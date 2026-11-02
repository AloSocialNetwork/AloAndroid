package com.alo.android

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class AloDirectCallConnectionState {
    New,
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed
}

class AloDirectAudioCallClient(
    context: Context,
    rtcConfig: AloRtcConfig,
    enableVideo: Boolean,
    private val onLocalDescription: (type: String, sdp: String) -> Unit,
    private val onIceCandidate: (sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit,
    private val onConnectionStateChanged: (AloDirectCallConnectionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onScreenShareStopped: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode
    private val previousCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice
    } else {
        null
    }
    private val previousSpeakerphoneOn = legacySpeakerphoneState()
    private val eglBase = EglBase.create()
    private val audioModule: JavaAudioDeviceModule
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    /** Candidates that came before the other side's description: WebRTC drops them, so they wait until it is set. */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var cameraSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var isCameraCapturing = false
    private var isScreenCapturing = false
    private var isStoppingScreenCapture = false
    private var localRenderer: AloCallRenderer? = null
    private var remoteRenderer: AloCallRenderer? = null
    private var currentCameraDeviceName: String? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        audioModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            )
            .setUseHardwareNoiseSuppressor(
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            )
            .setUseLowLatency(true)
            .createAudioDeviceModule()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        createPeerConnection(rtcConfig)
        if (enableVideo && !setVideoEnabled(true)) {
            throw IllegalStateException("Не удалось запустить камеру звонка.")
        }
    }

    fun bindLocalRenderer(renderer: AloCallRenderer?) {
        if (localRenderer === renderer) return
        localVideoTrack?.removeSink(localRenderer)
        localRenderer?.let { previous ->
            previous.clearImage()
            runCatching { previous.release() }
        }
        localRenderer = renderer
        renderer?.apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(true)
        }
        localVideoTrack?.addSink(renderer)
    }

    fun unbindLocalRenderer(renderer: AloCallRenderer) {
        if (localRenderer !== renderer) return
        localVideoTrack?.removeSink(renderer)
        renderer.clearImage()
        runCatching { renderer.release() }
        localRenderer = null
    }

    fun bindRemoteRenderer(renderer: AloCallRenderer?) {
        if (remoteRenderer === renderer) return
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteRenderer?.let { previous ->
            previous.clearImage()
            runCatching { previous.release() }
        }
        remoteRenderer = renderer
        renderer?.apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(false)
        }
        remoteVideoTrack?.addSink(renderer)
    }

    fun unbindRemoteRenderer(renderer: AloCallRenderer) {
        if (remoteRenderer !== renderer) return
        remoteVideoTrack?.removeSink(renderer)
        renderer.clearImage()
        runCatching { renderer.release() }
        remoteRenderer = null
    }

    fun prepareIncoming() = Unit

    fun startOutgoing() {
        createLocalOffer(kind = "offer", restartingIce = false)
    }

    fun restartIce(): Boolean {
        val connection = peerConnection ?: return false
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) return false
        createLocalOffer(kind = "reconnect-offer", restartingIce = true)
        return true
    }

    private fun createLocalOffer(kind: String, restartingIce: Boolean) {
        val connection = peerConnection ?: return
        if (restartingIce) {
            connection.restartIce()
        }
        connection.createOffer(
            sdpObserver(
                onCreateSuccess = { description ->
                    val connection = peerConnection ?: return@sdpObserver
                    connection.setLocalDescription(
                        sdpObserver(
                            onSetSuccess = {
                                onLocalDescription(kind, description.description)
                            },
                            onFailure = { error ->
                                if (!restartingIce) onError(error)
                            }
                        ),
                        description
                    )
                },
                onFailure = { error ->
                    if (!restartingIce) onError(error)
                }
            ),
            offerConstraints()
        )
    }

    fun handleRemoteOffer(sdp: String) {
        handleRemoteOffer(sdp, answerKind = "answer", reconnecting = false)
    }

    fun handleRemoteReconnectOffer(sdp: String) {
        handleRemoteOffer(sdp, answerKind = "reconnect-answer", reconnecting = true)
    }

    private fun handleRemoteOffer(
        sdp: String,
        answerKind: String,
        reconnecting: Boolean
    ) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            sdpObserver(
                onSetSuccess = {
                    flushPendingRemoteCandidates()
                    connection.createAnswer(
                        sdpObserver(
                            onCreateSuccess = { description ->
                                connection.setLocalDescription(
                                    sdpObserver(
                                        onSetSuccess = {
                                            onLocalDescription(answerKind, description.description)
                                        },
                                        onFailure = { error ->
                                            if (!reconnecting) onError(error)
                                        }
                                    ),
                                    description
                                )
                            },
                            onFailure = { error ->
                                if (!reconnecting) onError(error)
                            }
                        ),
                        offerConstraints()
                    )
                },
                onFailure = { error ->
                    if (!reconnecting) onError(error)
                }
            ),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    fun handleRemoteAnswer(sdp: String) {
        handleRemoteAnswer(sdp, reconnecting = false)
    }

    fun handleRemoteReconnectAnswer(sdp: String) {
        handleRemoteAnswer(sdp, reconnecting = true)
    }

    private fun handleRemoteAnswer(sdp: String, reconnecting: Boolean) {
        peerConnection?.setRemoteDescription(
            sdpObserver(
                onSetSuccess = { flushPendingRemoteCandidates() },
                onFailure = { error ->
                    if (!reconnecting) onError(error)
                }
            ),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val connection = peerConnection ?: return
        val remote = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(pendingRemoteCandidates) {
            if (connection.remoteDescription == null) {
                pendingRemoteCandidates += remote
                return
            }
        }
        connection.addIceCandidate(remote)
    }

    private fun flushPendingRemoteCandidates() {
        val connection = peerConnection ?: return
        val pending = synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        pending.forEach { connection.addIceCandidate(it) }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setSpeakerOn(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = if (enabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val target = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == targetType
            }
            if (target != null) {
                audioManager.setCommunicationDevice(target)
            } else if (!enabled) {
                audioManager.clearCommunicationDevice()
            }
        } else {
            setLegacySpeakerphoneState(enabled)
        }
    }

    fun setVideoEnabled(enabled: Boolean): Boolean {
        ensureLocalVideoTrack()
        if (!enabled) {
            stopCameraCapture()
            localVideoTrack?.setEnabled(false)
            return true
        }
        stopScreenCapture()
        val started = startCameraCapture()
        localVideoTrack?.setEnabled(started)
        return started
    }

    fun setScreenSharingEnabled(permissionData: Intent?): Boolean {
        ensureLocalVideoTrack()
        if (permissionData == null) {
            stopScreenCapture()
            localVideoTrack?.setEnabled(false)
            return true
        }
        val shouldRestoreCamera = isCameraCapturing
        stopCameraCapture()
        val started = startScreenCapture(permissionData)
        if (!started && shouldRestoreCamera) {
            val cameraRestored = startCameraCapture()
            localVideoTrack?.setEnabled(cameraRestored)
        } else {
            localVideoTrack?.setEnabled(started)
        }
        return started
    }

    /** Which camera the capturer uses now (it stays the same when the camera is switched off and on again). */
    val isFrontCamera: Boolean
        get() = isFrontCameraActive()

    fun switchCamera(onComplete: (Result<Boolean>) -> Unit = {}) {
        setCameraPosition(!isFrontCameraActive(), onComplete)
    }

    fun setCameraPosition(
        frontCamera: Boolean,
        onComplete: (Result<Boolean>) -> Unit = {}
    ) {
        val capturer = cameraCapturer
        if (capturer == null || !isCameraCapturing) {
            onComplete(Result.failure(IllegalStateException("Камера сейчас не активна.")))
            return
        }
        if (isFrontCameraActive() == frontCamera) {
            localRenderer?.setMirror(frontCamera)
            onComplete(Result.success(frontCamera))
            return
        }
        val enumerator = cameraEnumerator()
        val targetDeviceName = enumerator.deviceNames.firstOrNull {
            if (frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }
        if (targetDeviceName == null) {
            onComplete(
                Result.failure(
                    IllegalStateException(
                        if (frontCamera) "Передняя камера недоступна." else "Задняя камера недоступна."
                    )
                )
            )
            return
        }
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                currentCameraDeviceName = targetDeviceName
                localRenderer?.setMirror(isFrontCamera)
                onComplete(Result.success(isFrontCamera))
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                val message = errorDescription ?: "Не удалось переключить камеру."
                onError(message)
                onComplete(Result.failure(IllegalStateException(message)))
            }
        }, targetDeviceName)
    }

    fun close() {
        runCatching { localVideoTrack?.removeSink(localRenderer) }
        runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        localRenderer?.let { renderer ->
            renderer.clearImage()
            runCatching { renderer.release() }
        }
        remoteRenderer?.let { renderer ->
            renderer.clearImage()
            runCatching { renderer.release() }
        }
        localRenderer = null
        remoteRenderer = null
        stopCameraCapture()
        stopScreenCapture()
        // The audio route goes back now, before a next call can take it; restoring it later would cut that call's sound.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (previousCommunicationDevice != null) {
                    audioManager.setCommunicationDevice(previousCommunicationDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                setLegacySpeakerphoneState(previousSpeakerphoneOn)
            }
            audioManager.mode = previousAudioMode
        }
        val connection = peerConnection
        val capturer = cameraCapturer
        val cameraHelper = cameraSurfaceTextureHelper
        val videoTrack = localVideoTrack
        val videoSource = localVideoSource
        val audioTrack = localAudioTrack
        val microphone = audioSource
        peerConnection = null
        localVideoTrack = null
        localVideoSource = null
        localAudioTrack = null
        audioSource = null
        cameraCapturer = null
        cameraSurfaceTextureHelper = null
        screenSurfaceTextureHelper = null
        remoteVideoTrack = null
        // Closing the connection waits for WebRTC's network and signalling threads (TURN deallocation over a slow
        // network can take seconds) and the factory joins its threads: done on the UI thread, hang-up froze the app.
        Thread({
            runCatching { capturer?.dispose() }
            runCatching { cameraHelper?.dispose() }
            runCatching { connection?.close() }
            runCatching { connection?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { microphone?.dispose() }
            runCatching { peerConnectionFactory.dispose() }
            runCatching { audioModule.release() }
            runCatching { eglBase.release() }
        }, "alo-call-teardown").start()
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphoneState(): Boolean = audioManager.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphoneState(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    private fun createPeerConnection(rtcConfig: AloRtcConfig) {
        val servers = rtcConfig.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username)
                .setPassword(server.credential)
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = peerConnectionFactory.createPeerConnection(config, peerObserver())
        if (peerConnection == null) {
            onError("Не удалось создать peer connection.")
            return
        }
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("alo-audio", audioSource).also { track ->
            peerConnection?.addTrack(track, listOf("alo-stream"))
        }
        ensureLocalVideoTrack()
    }

    private fun ensureLocalVideoTrack() {
        if (localVideoTrack != null) return
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val track = peerConnectionFactory.createVideoTrack("alo-video", videoSource)
        localVideoSource = videoSource
        localVideoTrack = track
        localVideoSender = peerConnection?.addTrack(track, listOf("alo-stream"))
        track.setEnabled(false)
        localRenderer?.let { renderer ->
            track.addSink(renderer)
        }
    }

    private fun startCameraCapture(): Boolean {
        if (isCameraCapturing) return true
        val source = localVideoSource ?: return false
        if (cameraCapturer == null) {
            val capturer = createVideoCapturer() ?: return false
            val helper = SurfaceTextureHelper.create(
                "alo-direct-camera",
                eglBase.eglBaseContext
            )
            capturer.initialize(helper, appContext, source.capturerObserver)
            cameraCapturer = capturer
            cameraSurfaceTextureHelper = helper
        }
        val capturer = cameraCapturer ?: return false
        source.setIsScreencast(false)
        localRenderer?.setMirror(isFrontCameraActive())
        return runCatching {
            capturer.startCapture(720, 1280, 24)
            isCameraCapturing = true
            true
        }.getOrDefault(false)
    }

    private fun stopCameraCapture() {
        if (!isCameraCapturing) return
        isCameraCapturing = false
        runCatching { cameraCapturer?.stopCapture() }
    }

    private fun startScreenCapture(permissionData: Intent): Boolean {
        if (isScreenCapturing) return true
        val source = localVideoSource ?: return false
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (isStoppingScreenCapture) return
                isStoppingScreenCapture = true
                isScreenCapturing = false
                localVideoTrack?.setEnabled(false)
                runCatching { screenCapturer?.dispose() }
                runCatching { screenSurfaceTextureHelper?.dispose() }
                screenCapturer = null
                screenSurfaceTextureHelper = null
                localVideoSource?.setIsScreencast(false)
                AloScreenShareService.stop(appContext)
                isStoppingScreenCapture = false
                onScreenShareStopped()
            }
        }
        val capturer = ScreenCapturerAndroid(permissionData, callback)
        val helper = SurfaceTextureHelper.create(
            "alo-direct-screen",
            eglBase.eglBaseContext
        )
        val metrics = appContext.resources.displayMetrics
        return runCatching {
            AloScreenShareService.start(appContext)
            source.setIsScreencast(true)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)
            screenCapturer = capturer
            screenSurfaceTextureHelper = helper
            isScreenCapturing = true
            localRenderer?.setMirror(false)
            true
        }.getOrElse {
            runCatching { capturer.dispose() }
            runCatching { helper.dispose() }
            source.setIsScreencast(false)
            AloScreenShareService.stop(appContext)
            false
        }
    }

    private fun stopScreenCapture() {
        val capturer = screenCapturer
        val helper = screenSurfaceTextureHelper
        if (capturer == null && helper == null && !isScreenCapturing) return
        isStoppingScreenCapture = true
        isScreenCapturing = false
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        screenCapturer = null
        screenSurfaceTextureHelper = null
        localVideoSource?.setIsScreencast(false)
        AloScreenShareService.stop(appContext)
        isStoppingScreenCapture = false
    }

    private fun createVideoCapturer(): CameraVideoCapturer? {
        val enumerator = cameraEnumerator()
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val selected = front ?: enumerator.deviceNames.firstOrNull() ?: return null
        currentCameraDeviceName = selected
        return enumerator.createCapturer(selected, null)
    }

    private fun cameraEnumerator() = if (Camera2Enumerator.isSupported(appContext)) {
        Camera2Enumerator(appContext)
    } else {
        Camera1Enumerator(true)
    }

    private fun isFrontCameraActive(): Boolean {
        val active = currentCameraDeviceName ?: return true
        return cameraEnumerator().isFrontFacing(active)
    }

    private fun offerConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
    }

    private fun peerObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            onIceCandidate(
                candidate.sdpMid ?: return,
                candidate.sdpMLineIndex,
                candidate.sdp
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            remoteVideoTrack?.removeSink(remoteRenderer)
            remoteVideoTrack = track
            remoteRenderer?.let { renderer ->
                track.addSink(renderer)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            onConnectionStateChanged(
                when (newState) {
                    PeerConnection.PeerConnectionState.NEW -> AloDirectCallConnectionState.New
                    PeerConnection.PeerConnectionState.CONNECTING -> AloDirectCallConnectionState.Connecting
                    PeerConnection.PeerConnectionState.CONNECTED -> AloDirectCallConnectionState.Connected
                    PeerConnection.PeerConnectionState.DISCONNECTED -> AloDirectCallConnectionState.Disconnected
                    PeerConnection.PeerConnectionState.FAILED -> AloDirectCallConnectionState.Failed
                    PeerConnection.PeerConnectionState.CLOSED -> AloDirectCallConnectionState.Closed
                }
            )
        }
    }

    private fun sdpObserver(
        onCreateSuccess: (SessionDescription) -> Unit = {},
        onSetSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = onError
    ) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            onCreateSuccess(description)
        }

        override fun onSetSuccess() {
            onSetSuccess()
        }

        override fun onCreateFailure(error: String?) {
            onFailure(error ?: "Не удалось создать SDP.")
        }

        override fun onSetFailure(error: String?) {
            onFailure(error ?: "Не удалось применить SDP.")
        }
    }
}
