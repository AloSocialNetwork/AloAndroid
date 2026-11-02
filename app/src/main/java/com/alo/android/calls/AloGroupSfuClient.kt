package com.alo.android

import android.content.Context
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.SurfaceViewRenderer

internal data class AloGroupSfuSnapshot(
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val localUserId: Int = 0,
    val videoParticipantIds: Set<Int> = emptySet(),
    val speakingParticipantIds: Set<Int> = emptySet(),
    val mutedParticipantIds: Set<Int> = emptySet()
)

internal class AloGroupSfuClient(
    context: Context,
    private val scope: CoroutineScope,
    private val onSnapshot: (AloGroupSfuSnapshot) -> Unit,
    private val onError: (String) -> Unit
) {
    private val room: Room = LiveKit.create(
        appContext = context.applicationContext,
        options = RoomOptions(
            adaptiveStream = true,
            dynacast = true
        )
    )
    private val renderers = linkedMapOf<Int, SurfaceViewRenderer>()
    private val boundTracks = linkedMapOf<Int, VideoTrack>()
    private var eventJob: Job? = null
    /** The video-chat recording: one sink per audio track it hears (iOS's recorder taps). */
    private var recorder: AloGroupCallRecorder? = null
    private val recordedTracks = mutableMapOf<String, Pair<AudioTrack, AudioTrackSink>>()

    suspend fun connect(join: AloGroupCallSfuJoin, muted: Boolean) {
        eventJob?.cancel()
        eventJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.FailedToConnect -> onError(
                        event.error.message ?: "Не удалось подключиться к медиасерверу."
                    )
                    else -> Unit
                }
                refresh()
            }
        }
        room.connect(url = join.serverUrl, token = join.token)
        room.localParticipant.setMicrophoneEnabled(!muted)
        setSpeakerEnabled(true)
        refresh()
    }

    fun startRecording(recorder: AloGroupCallRecorder) {
        this.recorder = recorder
        attachRecorder()
    }

    fun stopRecording() {
        recordedTracks.values.forEach { (track, sink) -> runCatching { track.removeSink(sink) } }
        recordedTracks.clear()
        recorder = null
    }

    /** Taps the microphone and every remote voice not yet heard; called again as people join. */
    private fun attachRecorder() {
        val recorder = recorder ?: return
        val tracks = buildList {
            (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? AudioTrack)?.let { add("local" to it) }
            room.remoteParticipants.values.forEach { participant ->
                participant.audioTrackPublications.forEach { (publication, track) ->
                    (track as? AudioTrack)?.let { add(publication.sid to it) }
                }
            }
        }
        tracks.forEach { (key, track) ->
            if (key in recordedTracks) return@forEach
            val sink = recorder.sink(key)
            runCatching { track.addSink(sink) }.onSuccess { recordedTracks[key] = track to sink }
        }
    }

    fun close() {
        stopRecording()
        eventJob?.cancel()
        eventJob = null
        boundTracks.forEach { (userId, track) ->
            renderers[userId]?.let(track::removeRenderer)
        }
        boundTracks.clear()
        renderers.values.forEach { renderer ->
            runCatching { renderer.release() }
        }
        renderers.clear()
        room.disconnect()
        room.release()
        onSnapshot(AloGroupSfuSnapshot())
    }

    suspend fun setMuted(muted: Boolean) {
        room.localParticipant.setMicrophoneEnabled(!muted)
        refresh()
    }

    suspend fun setVideoEnabled(enabled: Boolean) {
        room.localParticipant.setCameraEnabled(enabled)
        refresh()
    }

    fun setCameraPosition(frontCamera: Boolean) {
        val track = room.localParticipant
            .getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: throw IllegalStateException("Камера ещё не запущена.")
        track.switchCamera(
            position = if (frontCamera) CameraPosition.FRONT else CameraPosition.BACK
        )
        refresh()
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        val handler = room.audioHandler as? AudioSwitchHandler ?: return
        val device = handler.availableAudioDevices.firstOrNull {
            if (enabled) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
        }
        if (device != null) {
            handler.selectDevice(device)
        }
    }

    fun bindRenderer(userId: Int, renderer: SurfaceViewRenderer?) {
        val oldRenderer = renderers.remove(userId)
        val oldTrack = boundTracks.remove(userId)
        if (oldRenderer != null && oldTrack != null) {
            oldTrack.removeRenderer(oldRenderer)
        }
        if (renderer == null) return
        room.initVideoRenderer(renderer)
        renderer.setMirror(userId == localUserId() && isFrontCamera())
        renderers[userId] = renderer
        bindTrack(userId)
    }

    fun unbindRenderer(userId: Int, renderer: SurfaceViewRenderer) {
        if (renderers[userId] !== renderer) return
        boundTracks.remove(userId)?.removeRenderer(renderer)
        renderers.remove(userId)
        runCatching { renderer.release() }
    }

    private fun refresh() {
        val videoIds = linkedSetOf<Int>()
        if (preferredVideoTrack(room.localParticipant) != null) {
            videoIds += localUserId()
        }
        room.remoteParticipants.values.forEach { participant ->
            val userId = userId(participant)
            if (userId > 0 && preferredVideoTrack(participant) != null) {
                videoIds += userId
            }
        }
        val speaking = room.activeSpeakers.map(::userId).filter { it > 0 }.toSet()
        val muted = room.remoteParticipants.values.filter { participant ->
            participant.getTrackPublication(Track.Source.MICROPHONE)?.muted ?: true
        }.map(::userId).filter { it > 0 }.toSet()
        attachRecorder()
        onSnapshot(
            AloGroupSfuSnapshot(
                connected = room.state == Room.State.CONNECTED ||
                    room.state == Room.State.RECONNECTING,
                reconnecting = room.state == Room.State.RECONNECTING,
                localUserId = localUserId(),
                videoParticipantIds = videoIds,
                speakingParticipantIds = speaking,
                mutedParticipantIds = muted
            )
        )
        renderers.keys.toList().forEach(::bindTrack)
    }

    private fun bindTrack(userId: Int) {
        val renderer = renderers[userId] ?: return
        val nextTrack = videoTrack(userId)
        val previousTrack = boundTracks[userId]
        if (previousTrack === nextTrack) return
        previousTrack?.removeRenderer(renderer)
        if (nextTrack != null) {
            nextTrack.addRenderer(renderer)
            boundTracks[userId] = nextTrack
        } else {
            boundTracks.remove(userId)
        }
    }

    private fun videoTrack(userId: Int): VideoTrack? {
        if (userId == localUserId()) {
            return preferredVideoTrack(room.localParticipant)
        }
        return room.remoteParticipants.values
            .firstOrNull { userId(it) == userId }
            ?.let(::preferredVideoTrack)
    }

    private fun preferredVideoTrack(participant: Participant): VideoTrack? {
        val screen = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
        if (screen != null && !screen.muted) {
            (screen.track as? VideoTrack)?.let { return it }
        }
        val camera = participant.getTrackPublication(Track.Source.CAMERA)
        if (camera != null && !camera.muted) {
            return camera.track as? VideoTrack
        }
        return null
    }

    private fun localUserId(): Int = userId(room.localParticipant)

    private fun userId(participant: Participant): Int {
        return participant.identity
            ?.value
            ?.removePrefix("alo-user-")
            ?.toIntOrNull()
            ?: 0
    }

    private fun isFrontCamera(): Boolean {
        val track = room.localParticipant
            .getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
        return track?.options?.position != CameraPosition.BACK
    }
}
