package com.alo.android

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import androidx.compose.runtime.getValue
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
 * The video circle that plays with sound, one for the whole app (the website and the iPhone app do the same). It used to
 * be the bubble's own MediaPlayer, released with the bubble's video surface, so leaving the chat stopped it; now the
 * picture goes to the bubble's surface while the bubble is on screen, and the sound goes on without it in other chats,
 * with a bar under the chat header (the owner, 2026-10-31: «только полоса», no floating circle).
 */
object AloCirclePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var ticker: Job? = null
    private var pendingSeekMs = 0

    /** The circle: its message and file (`null`: no bar). */
    var messageId by mutableStateOf<Int?>(null)
        private set
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("Видеосообщение")
        private set
    var playing by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    /** The picture's size, for the bubble's round crop. */
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set

    fun isCurrent(id: Int, attachmentUrl: String): Boolean = messageId == id && url == attachmentUrl

    fun toggle(context: Context, id: Int, attachmentUrl: String, name: String) {
        if (isCurrent(id, attachmentUrl) && player != null) {
            if (playing) pause() else resume()
            return
        }
        start(context, id, attachmentUrl, name)
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        playing = false
    }

    fun resume() {
        val media = player ?: return
        quietOthers()
        playing = true
        if (!ready) return // starts when it is ready
        runCatching { media.start() }
        tick()
    }

    fun seekTo(targetMs: Int) {
        val target = if (durationMs > 0) targetMs.coerceIn(0, durationMs) else targetMs.coerceAtLeast(0)
        positionMs = target
        if (ready) runCatching { player?.seekTo(target) } else pendingSeekMs = target
    }

    /** A bubble of this circle came on screen: the picture goes there. */
    fun attachSurface(next: Surface) {
        surface = next
        runCatching { player?.setSurface(next) }
    }

    /** Its bubble left the screen: the sound goes on without a picture. */
    fun detachSurface(gone: Surface) {
        if (surface !== gone) return
        surface = null
        runCatching { player?.setSurface(null) }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { media -> runCatching { media.release() } }
        player = null
        messageId = null
        url = ""
        playing = false
        ready = false
        positionMs = 0
        durationMs = 0
        pendingSeekMs = 0
    }

    private fun start(context: Context, id: Int, attachmentUrl: String, name: String) {
        stop()
        quietOthers()
        val appContext = context.applicationContext
        messageId = id
        url = attachmentUrl
        title = name.ifBlank { "Видеосообщение" }
        playing = true
        val media = MediaPlayer()
        player = media
        runCatching {
            surface?.let { media.setSurface(it) }
            val headers = aloAuthenticatedMediaHeaders(attachmentUrl)
            if (headers.isEmpty()) {
                media.setDataSource(appContext, Uri.parse(attachmentUrl))
            } else {
                media.setDataSource(appContext, Uri.parse(attachmentUrl), headers)
            }
            media.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                ready = true
                durationMs = prepared.duration.coerceAtLeast(0)
                videoWidth = prepared.videoWidth
                videoHeight = prepared.videoHeight
                if (pendingSeekMs > 0) runCatching { prepared.seekTo(pendingSeekMs) }
                if (playing) {
                    runCatching { prepared.start() }
                    tick()
                }
            }
            media.setOnVideoSizeChangedListener { sized, width, height ->
                if (player !== sized) return@setOnVideoSizeChangedListener
                videoWidth = width
                videoHeight = height
            }
            media.setOnCompletionListener { finished -> if (player === finished) stop() }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            media.prepareAsync()
        }.onFailure { stop() }
    }

    /** A circle takes the voice message's or the song's place: one bar under the header. */
    private fun quietOthers() {
        if (AloVoicePlayer.messageId != null) AloVoicePlayer.stop()
        if (AloMusicPlayer.track != null) AloMusicPlayer.stop()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { media -> runCatching { if (media.isPlaying) positionMs = media.currentPosition } }
                delay(80)
            }
        }
    }
}
