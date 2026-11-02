package com.alo.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The voice message that plays, one for the whole app (the website and the iPhone app do the same). It used to be the
 * bubble's own MediaPlayer, released with the bubble, so leaving the chat stopped the clip; now the bubbles and the bar
 * under the chat header only show and steer this player, and the clip goes on until it ends, is closed, or a song starts.
 */
object AloVoicePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** The clip: its message and file (`null`: no bar). */
    var messageId by mutableStateOf<Int?>(null)
        private set
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("Голосовое сообщение")
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    /** 1× → 1,5× → 2× → 1×, kept for the next clips too. */
    var speed by mutableFloatStateOf(1f)
        private set

    fun isCurrent(id: Int, attachmentUrl: String): Boolean = messageId == id && url == attachmentUrl

    /** The bubble's play button: the same clip pauses or goes on, another one starts from the beginning. */
    fun toggle(context: Context, id: Int, attachmentUrl: String, source: String, name: String, declaredMs: Int) {
        if (isCurrent(id, attachmentUrl) && player != null) {
            if (playing) pause() else resume()
            return
        }
        start(context, id, attachmentUrl, source, name, declaredMs, startAtMs = 0, play = true)
    }

    /** Dragging along a bubble's bars; a clip that is not open yet is opened there, paused. */
    fun seek(context: Context, id: Int, attachmentUrl: String, source: String, name: String, declaredMs: Int, targetMs: Int) {
        if (!isCurrent(id, attachmentUrl) || player == null) {
            start(context, id, attachmentUrl, source, name, declaredMs, startAtMs = targetMs, play = false)
            return
        }
        seekTo(targetMs)
    }

    fun seekTo(targetMs: Int) {
        val target = if (durationMs > 0) targetMs.coerceIn(0, durationMs) else targetMs.coerceAtLeast(0)
        if (!loading) runCatching { player?.seekTo(target) }
        positionMs = target
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        playing = false
    }

    fun resume() {
        val media = player ?: return
        if (AloMusicPlayer.track != null) AloMusicPlayer.stop() // a voice message takes the player's place, as in Telegram
        if (AloCirclePlayer.messageId != null) AloCirclePlayer.stop()
        playing = true
        if (loading) return // starts when it is ready
        runCatching {
            media.playbackParams = media.playbackParams.setSpeed(speed)
            media.start()
        }
        tick()
    }

    fun cycleSpeed() {
        speed = when {
            speed < 1.25f -> 1.5f
            speed < 1.75f -> 2f
            else -> 1f
        }
        val media = player ?: return
        if (!loading) runCatching {
            media.playbackParams = media.playbackParams.setSpeed(speed)
            // Setting the speed starts a paused MediaPlayer.
            if (!playing && media.isPlaying) media.pause()
        }
    }

    fun stop() {
        release()
        messageId = null
        url = ""
        playing = false
        loading = false
        positionMs = 0
        durationMs = 0
    }

    private fun start(context: Context, id: Int, attachmentUrl: String, source: String, name: String, declaredMs: Int, startAtMs: Int, play: Boolean) {
        release()
        val appContext = context.applicationContext
        messageId = id
        url = attachmentUrl
        title = name.ifBlank { "Голосовое сообщение" }
        durationMs = declaredMs
        positionMs = startAtMs.coerceAtLeast(0)
        loading = true
        playing = play
        if (play && AloMusicPlayer.track != null) AloMusicPlayer.stop()
        if (play && AloCirclePlayer.messageId != null) AloCirclePlayer.stop()
        val media = MediaPlayer()
        player = media
        runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val headers = aloAuthenticatedMediaHeaders(source)
            when {
                source.startsWith("/") -> media.setDataSource(source)
                headers.isEmpty() -> media.setDataSource(appContext, Uri.parse(source))
                else -> media.setDataSource(appContext, Uri.parse(source), headers)
            }
            media.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                loading = false
                // The sender's length wins when the file says much less (a browser's fragmented MP4): see the bubble.
                durationMs = voicePlaybackDurationMs(prepared.duration, declaredMs / 1000)
                if (positionMs in 1 until durationMs) runCatching { prepared.seekTo(positionMs) }
                if (playing) {
                    runCatching {
                        prepared.playbackParams = prepared.playbackParams.setSpeed(speed)
                        prepared.start()
                    }
                    tick()
                }
            }
            media.setOnCompletionListener { finished ->
                if (player === finished) stop()
            }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            media.prepareAsync()
        }.onFailure { stop() }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { media -> runCatching { if (media.isPlaying) positionMs = media.currentPosition } }
                delay(120)
            }
        }
    }

    private fun release() {
        ticker?.cancel()
        ticker = null
        player?.let { media -> runCatching { media.release() } }
        player = null
    }
}
