package com.alo.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Songs in chats, after Telegram (the same as the website, ~/alo/web/js/player.js, and the iPhone app,
 * AloMusicPlayer.swift): an audio file that is not a recorded voice message is a track. Its bubble is a round play
 * button with the title and performer (no seek line inside the bubble); while it plays, an oval under the chat header
 * holds «назад», play/pause, «далее», the title, «закрыть» and a line to seek. Tracks of the open chat play one after
 * another. A song is sent as a file with `keepOriginal` (the server would otherwise make a voice message of it) and
 * carries `title`, `performer` and `durationSeconds` in its metadata.
 */
data class AloMusicTrack(
    val key: String,
    val messageId: Int,
    val url: String,
    val title: String,
    val performer: String,
    val durationSeconds: Int
)

object AloMusic {
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "aiff", "aif", "alac")

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** A file picked to send that is a song (a recording is made a voice message by the recorder itself). */
    fun isSongDraft(draft: AloChatDraftAttachment): Boolean =
        draft.attachmentType == "file" && !draft.isVideoCircle &&
            (draft.mimeType.lowercase(Locale.ROOT).startsWith("audio/") || extensionOf(draft.name) in extensions)

    fun isSong(attachment: AloOutgoingMessageAttachment): Boolean {
        val type = attachment.type.lowercase(Locale.ROOT)
        if (type != "file" && type != "audio") return false
        if (attachment.name.lowercase(Locale.ROOT).startsWith("voice")) return false
        val mime = (attachment.metadata["mime"] ?: attachment.mediaEncryption["mime"]).orEmpty().lowercase(Locale.ROOT)
        return mime.startsWith("audio/") || extensionOf(attachment.name) in extensions
    }

    /** The song of a message, if it is one (a plain message with one audio file). */
    fun trackOf(message: AloChatMessage): AloMusicTrack? {
        val attachment = message.attachments.firstOrNull { isSong(it) }
            ?: if (message.attachments.isEmpty() && message.attachmentType.equals("file", true) &&
                !message.attachmentName.lowercase(Locale.ROOT).startsWith("voice") &&
                extensionOf(message.attachmentName) in extensions
            ) AloOutgoingMessageAttachment(url = message.attachmentUrl, type = "file", name = message.attachmentName) else null
        attachment ?: return null
        val url = attachment.url.ifBlank { message.attachmentUrl }
        if (url.isBlank()) return null
        val (title, performer) = titles(attachment)
        val seconds = attachment.metadata["durationSeconds"]?.replace(',', '.')?.toDoubleOrNull()?.toInt() ?: 0
        return AloMusicTrack("${message.id}:$url", message.id, url, title, performer, seconds.coerceAtLeast(0))
    }

    /** Title and performer from the tags the sender read, else «Исполнитель - Название.mp3». */
    fun titles(attachment: AloOutgoingMessageAttachment): Pair<String, String> {
        attachment.metadata["title"]?.takeIf { it.isNotBlank() }?.let { return it to attachment.metadata["performer"].orEmpty() }
        val base = attachment.name.substringBeforeLast('.').ifBlank { "Аудио" }
        for (separator in listOf(" - ", " – ", " — ")) {
            val parts = base.split(separator)
            if (parts.size >= 2) return parts.drop(1).joinToString(" – ") to parts[0]
        }
        return base to ""
    }

    /** Title, performer and length read from a song's tags before it is sent. */
    fun readTags(context: Context, uri: Uri): Triple<String, String, Int> = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Triple(title, artist, ((ms + 500) / 1000).toInt())
        } finally {
            retriever.release()
        }
    }.getOrDefault(Triple("", "", 0))

    fun clock(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
    }
}

/** The chat's songs, in order: the queue a song started from a bubble plays through. */
data class AloMusicQueue(val chatKey: String = "", val tracks: List<AloMusicTrack> = emptyList())

val LocalAloMusicQueue = androidx.compose.runtime.staticCompositionLocalOf { AloMusicQueue() }

/** The one music player of the app: what plays, the queue of the chat it was started in, and its position. */
object AloMusicPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var appContext: Context? = null

    var track by mutableStateOf<AloMusicTrack?>(null)
        private set
    var queue by mutableStateOf<List<AloMusicTrack>>(emptyList())
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    /** The chat the queue belongs to (every chat shows the bar since 2026-10-31). */
    var scopeKey by mutableStateOf("")
        private set

    private var ticker: kotlinx.coroutines.Job? = null

    fun toggle(context: Context, next: AloMusicTrack, chatQueue: List<AloMusicTrack>, chatKey: String) {
        if (track?.key == next.key) {
            if (playing) pause() else resume()
            return
        }
        start(context, next, chatQueue, chatKey)
    }

    fun start(context: Context, next: AloMusicTrack, chatQueue: List<AloMusicTrack>, chatKey: String) {
        AloVoicePlayer.stop() // a song takes the voice message's (or the circle's) place
        AloCirclePlayer.stop()
        appContext = context.applicationContext
        queue = chatQueue.ifEmpty { listOf(next) }
        scopeKey = chatKey
        track = next
        positionMs = 0
        durationMs = next.durationSeconds * 1000
        loading = true
        playing = false
        release()
        val context = appContext ?: return
        scope.launch {
            // The file is fetched first (as a voice message is): a stream over HTTP may have no length to seek in.
            val source = AloVoiceFileCache.fetch(context, next.url) ?: next.url
            if (track?.key != next.key) return@launch
            val media = MediaPlayer()
            player = media
            runCatching {
                media.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                val headers = aloAuthenticatedMediaHeaders(source)
                when {
                    source.startsWith("/") -> media.setDataSource(source)
                    headers.isEmpty() -> media.setDataSource(context, Uri.parse(source))
                    else -> media.setDataSource(context, Uri.parse(source), headers)
                }
                media.setOnPreparedListener { prepared ->
                    if (player !== prepared) return@setOnPreparedListener
                    loading = false
                    if (prepared.duration > 0) durationMs = prepared.duration
                    prepared.start()
                    playing = true
                    tick()
                }
                media.setOnCompletionListener { finished ->
                    if (player !== finished) return@setOnCompletionListener
                    playing = false
                    positionMs = durationMs
                    if (!skip(1)) stop()
                }
                media.setOnErrorListener { failed, _, _ ->
                    if (player === failed) {
                        loading = false
                        playing = false
                    }
                    true
                }
                media.prepareAsync()
            }.onFailure {
                loading = false
                playing = false
            }
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { media -> runCatching { if (media.isPlaying) positionMs = media.currentPosition } }
                delay(250)
            }
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        playing = false
    }

    fun resume() {
        val media = player ?: return
        runCatching { media.start() }
        playing = true
        tick()
    }

    fun seek(fraction: Float) {
        val media = player ?: return
        val target = (fraction.coerceIn(0f, 1f) * durationMs).toInt().coerceAtMost((durationMs - 500).coerceAtLeast(0))
        runCatching { media.seekTo(target) }
        positionMs = target
    }

    /** «назад» restarts a song that has played more than 3 s, as players do; otherwise goes to the one before. */
    fun previous() {
        if (positionMs > 3000) seek(0f) else if (!skip(-1)) seek(0f)
    }

    fun next() {
        skip(1)
    }

    private fun skip(step: Int): Boolean {
        val current = track ?: return false
        val context = appContext ?: return false
        val index = queue.indexOfFirst { it.key == current.key }
        val target = queue.getOrNull(index + step) ?: return false
        start(context, target, queue, scopeKey)
        return true
    }

    fun hasPrevious(): Boolean = queue.indexOfFirst { it.key == track?.key } > 0 || positionMs > 3000
    fun hasNext(): Boolean {
        val index = queue.indexOfFirst { it.key == track?.key }
        return index >= 0 && index < queue.lastIndex
    }

    fun stop() {
        release()
        track = null
        playing = false
        loading = false
        positionMs = 0
    }

    private fun release() {
        ticker?.cancel()
        player?.let { media -> runCatching { media.release() } }
        player = null
    }
}

/** The track row inside a message bubble: a round button, the title, the performer and the length. */
@Composable
internal fun AloMusicTrackRow(
    track: AloMusicTrack,
    queue: List<AloMusicTrack>,
    chatKey: String,
    isOutgoing: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val current = AloMusicPlayer.track?.key == track.key
    val playing = current && AloMusicPlayer.playing
    val loading = current && AloMusicPlayer.loading
    val accent = if (isOutgoing) Color.White else AloNativeChatAccentBlue
    val glyph = if (isOutgoing) AloNativeChatOutgoing else Color.White
    Row(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .pointerInput(track.key) {
                detectTapGestures(
                    onTap = { AloMusicPlayer.toggle(context, track, queue, chatKey) },
                    onLongPress = { onLongPress() }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(color = glyph, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Пауза" else "Слушать",
                    tint = glyph,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val seconds = if (current && AloMusicPlayer.durationMs > 0) AloMusicPlayer.durationMs / 1000 else track.durationSeconds
            val time = when {
                current && (playing || AloMusicPlayer.positionMs > 0) -> AloMusic.clock(AloMusicPlayer.positionMs / 1000) + " / " + AloMusic.clock(seconds)
                seconds > 0 -> AloMusic.clock(seconds)
                else -> ""
            }
            Text(
                text = listOf(track.performer.ifBlank { "Неизвестный исполнитель" }, time).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = textColor.alpha * 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The oval under the chat header while a song plays: «назад», play/pause, «далее», the title, «закрыть» and a seek line. */
@Composable
internal fun AloMusicPlaybackBar(modifier: Modifier = Modifier, onOpenMessage: (Int) -> Unit) {
    val track = AloMusicPlayer.track ?: return
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = AloMusicPlayer.durationMs
    val played = scrub ?: if (duration > 0) AloMusicPlayer.positionMs.toFloat() / duration else 0f
    AloNativeGlassPanel(modifier = modifier.fillMaxWidth(), shape = AloSquircle(22.dp)) {
        Column(modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MusicBarButton(Icons.Rounded.SkipPrevious, "Назад", enabled = AloMusicPlayer.hasPrevious()) { AloMusicPlayer.previous() }
                MusicBarButton(if (AloMusicPlayer.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (AloMusicPlayer.playing) "Пауза" else "Слушать") {
                    if (AloMusicPlayer.playing) AloMusicPlayer.pause() else AloMusicPlayer.resume()
                }
                MusicBarButton(Icons.Rounded.SkipNext, "Далее", enabled = AloMusicPlayer.hasNext()) { AloMusicPlayer.next() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .clickable { onOpenMessage(track.messageId) }
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(track.performer, AloMusic.clock(((scrub?.let { it * duration } ?: AloMusicPlayer.positionMs.toFloat()) / 1000).toInt()) + " / " + AloMusic.clock(duration / 1000))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Rounded.MusicNote, null, tint = AloNativeAppMuted, modifier = Modifier.size(16.dp))
                MusicBarButton(Icons.Rounded.Close, "Закрыть", tint = AloNativeAppMuted) { AloMusicPlayer.stop() }
            }
            // The seek line: tap or drag.
            val lineColor = AloNativeAppMuted.copy(alpha = 0.3f)
            val accent = AloNativeAppAccent
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(22.dp)
                    .pointerInput(track.key) {
                        detectTapGestures { offset ->
                            AloMusicPlayer.seek(offset.x / size.width)
                            AloMusicPlayer.resume()
                        }
                    }
                    .pointerInput(track.key) {
                        // The song is silent while the line is dragged (no fast-forward squeak), and plays from the
                        // new place when it is let go (the owner, 2026-10-28; the same on the website).
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                AloMusicPlayer.pause()
                                scrub = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                scrub?.let { AloMusicPlayer.seek(it) }
                                scrub = null
                                AloMusicPlayer.resume()
                            },
                            onDragCancel = {
                                scrub = null
                                AloMusicPlayer.resume()
                            }
                        )
                    }
            ) {
                val line = 3.dp.toPx()
                val top = (size.height - line) / 2f
                drawRoundRect(lineColor, Offset(0f, top), Size(size.width, line), CornerRadius(line / 2f))
                val x = size.width * played.coerceIn(0f, 1f)
                drawRoundRect(accent, Offset(0f, top), Size(x, line), CornerRadius(line / 2f))
                drawCircle(accent, radius = (if (scrub != null) 6.dp else 4.5.dp).toPx(), center = Offset(x, size.height / 2f))
            }
        }
    }
}

@Composable
private fun MusicBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = AloNativeAppText,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.35f), modifier = Modifier.size(22.dp))
    }
}
