package com.alo.android

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
internal fun VoiceMessageAttachment(
    modifier: Modifier = Modifier,
    message: AloChatMessage,
    isOutgoing: Boolean,
    fallbackDurationLabel: String,
    /** Who sent it: the name on the player bar. */
    title: String = "",
    /** Told when this clip starts (a chat stops its circle). */
    onRequestPlayback: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    mediaAccessGranted: Boolean = true,
    /** The voice message is still on the server (auto-download is off for it): the button downloads it. */
    downloadPending: Boolean = false,
    onRequestMediaAccess: () -> Unit = {},
    /** The text of this voice message once it has been turned into text, and the «Aa» button's action (`null`: no button). */
    transcriptState: AloTranscriptState? = null,
    onTranscribe: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isPrepared by remember(message.id, message.attachmentUrl) { mutableStateOf(false) }
    var preparedMs by remember(message.id, message.attachmentUrl) { mutableStateOf(0) }
    var playAfterMediaAccess by remember(message.id) { mutableStateOf(false) }
    // The clip plays in the app-wide player (AloVoicePlayback.kt), so it goes on when the chat closes; this bubble's own
    // player only opens the file to learn its length and that it can be played.
    val player = remember(message.id, message.attachmentUrl) { MediaPlayer() }
    val isActive = AloVoicePlayer.isCurrent(message.id, message.attachmentUrl)
    val isPlaying = isActive && AloVoicePlayer.playing
    val currentMs = if (isActive) AloVoicePlayer.positionMs else 0
    val durationMs = if (isActive && AloVoicePlayer.durationMs > 0) AloVoicePlayer.durationMs else preparedMs
    // A cloud voice message is fetched to a file first: streamed over HTTP, an Ogg recording (what the server keeps)
    // has no known length, so the player could neither show the time nor seek.
    var playableSource by remember(message.id, message.attachmentUrl) { mutableStateOf<String?>(null) }
    // A file that cannot be opened shows a retry sign instead of an endless spinner; a tap fetches it again.
    var loadFailed by remember(message.id, message.attachmentUrl) { mutableStateOf(false) }
    var loadAttempt by remember(message.id, message.attachmentUrl) { mutableStateOf(0) }
    LaunchedEffect(message.attachmentUrl, mediaAccessGranted, loadAttempt) {
        if (!mediaAccessGranted) return@LaunchedEffect
        loadFailed = false
        playableSource = null
        playableSource = AloVoiceFileCache.fetch(context, message.attachmentUrl) ?: message.attachmentUrl
    }

    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    DisposableEffect(message.id, playableSource, mediaAccessGranted) {
        val source = playableSource
        if (mediaAccessGranted && source != null) runCatching {
            player.reset()
            val headers = aloAuthenticatedMediaHeaders(source)
            if (source.startsWith("/")) {
                player.setDataSource(source)
            } else if (headers.isEmpty()) {
                player.setDataSource(context, Uri.parse(source))
            } else {
                player.setDataSource(context, Uri.parse(source), headers)
            }
            player.setOnPreparedListener { mediaPlayer ->
                isPrepared = true
                // The sender's length is the fallback when the file does not say (a stream that is still arriving), and
                // wins when the file says much less: a browser records a fragmented MP4 (secret chats keep it as it is),
                // and the player reports only its first fragment, a quarter of a second, though it plays the whole clip.
                preparedMs = voicePlaybackDurationMs(mediaPlayer.duration, attachmentDurationSeconds(message))
            }
            player.setOnErrorListener { _, _, _ ->
                isPrepared = false
                loadFailed = true
                true
            }
            player.prepareAsync()
        }.onFailure { loadFailed = true }

        onDispose {
            // A new source (the downloaded file) reuses this player; it is released only when the message leaves.
            runCatching {
                player.reset()
            }
        }
    }

    fun togglePlayback() {
        val source = playableSource ?: return
        if (!isPlaying) onRequestPlayback()
        AloVoicePlayer.toggle(context, message.id, message.attachmentUrl, source, title, durationMs)
    }

    LaunchedEffect(mediaAccessGranted, isPrepared, playAfterMediaAccess) {
        if (!mediaAccessGranted || !isPrepared || !playAfterMediaAccess) return@LaunchedEffect
        playAfterMediaAccess = false
        togglePlayback()
    }

    val progress = if (durationMs > 0) {
        (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val durationText = if (durationMs > 0) {
        formatPlaybackDuration(if (isPlaying || currentMs > 0) currentMs else durationMs)
    } else {
        fallbackDurationLabel.ifBlank { "0:00" }
    }
    val voiceMetaTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 10.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val mediaControlColor = AloNativeChatAccentBlue
    // Colours follow the bubble, not the direction: in the light theme an outgoing bubble is light too, and white bars
    // and a white time on it could not be seen.
    val lightBubble = (if (isOutgoing) AloNativeChatOutgoing else AloNativeChatIncoming).luminance() > 0.5f
    val barPlayed = if (lightBubble) mediaControlColor else Color.White
    val barRest = if (lightBubble) mediaControlColor.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.36f)
    val metaColor = if (lightBubble) AloNativeAppMuted else Color.White.copy(alpha = 0.68f)

    val rowModifier = if (onLongPress == null) {
        modifier
    } else {
        modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongPress
        )
    }

    Row(
        modifier = rowModifier
            .height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Telegram: a filled accent circle with a white sign in an incoming bubble, a white circle in an outgoing one.
        val buttonFill = if (lightBubble) mediaControlColor else Color.White.copy(alpha = 0.92f)
        val buttonSign = if (lightBubble) Color.White else mediaControlColor
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = buttonFill
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        if (!mediaAccessGranted) {
                            playAfterMediaAccess = true
                            onRequestMediaAccess()
                            return@clickable
                        }
                        if (loadFailed) {
                            loadAttempt += 1
                            return@clickable
                        }
                        if (!isPrepared) return@clickable
                        togglePlayback()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!mediaAccessGranted) {
                    Icon(
                        imageVector = if (downloadPending) Icons.Rounded.Download else Icons.Rounded.Timer,
                        contentDescription = if (downloadPending) "Скачать голосовое сообщение" else "Открыть исчезающее голосовое сообщение",
                        tint = buttonSign,
                        modifier = Modifier.size(20.dp)
                    )
                } else if (loadFailed) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Повторить загрузку голосового сообщения",
                        tint = buttonSign,
                        modifier = Modifier.size(22.dp)
                    )
                } else if (!isPrepared) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.8.dp,
                        color = buttonSign
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести голосовое сообщение",
                        tint = buttonSign,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(isPrepared, durationMs) {
                        if (!isPrepared || durationMs <= 0) return@pointerInput
                        fun seekTo(x: Float) {
                            val source = playableSource ?: return
                            val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            val target = (durationMs * fraction).toInt().coerceIn(0, durationMs)
                            AloVoicePlayer.seek(context, message.id, message.attachmentUrl, source, title, durationMs, target)
                        }
                        detectDragGestures(
                            onDragStart = { offset -> seekTo(offset.x) },
                            onDrag = { change, _ ->
                                change.consume()
                                seekTo(change.position.x)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val levels = remember(message.id, message.attachmentUrl) { voiceBarLevels(message, VOICE_BAR_COUNT) }
                    val bars = levels.map { level -> (3f + level * 17f).dp }
                    bars.forEachIndexed { index, barHeight ->
                        val threshold = (index + 1) / bars.size.toFloat()
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(barHeight)
                                .clip(CircleShape)
                                .background(if (progress >= threshold) barPlayed else barRest)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = durationText,
                    style = voiceMetaTextStyle,
                    color = metaColor
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildMessageMetaLabel(message.createdAt, message.editedAt),
                        style = voiceMetaTextStyle,
                        color = metaColor
                    )
                    if (isOutgoing) {
                        Box(
                            modifier = Modifier.size(11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (message.readAt.isNotBlank()) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                                contentDescription = null,
                                tint = metaColor,
                                modifier = Modifier.size(if (message.readAt.isNotBlank()) 12.dp else 10.dp)
                            )
                        }
                    }
                }
            }
        }
        if (onTranscribe != null) {
            AloTranscriptButton(state = transcriptState, isOutgoing = isOutgoing, onClick = onTranscribe)
        }
    }
}

private const val VOICE_BAR_COUNT = 28

/** The voice attachment of a message: `voice`/`audio`, or an audio file named like a recording. */
internal fun voiceAttachmentOf(message: AloChatMessage): AloOutgoingMessageAttachment? =
    message.attachments.firstOrNull { it.type.equals("voice", true) || it.type.equals("audio", true) }
        ?: message.attachments.firstOrNull()

/**
 * Bar heights 0…1 of a voice message: the sender's waveform resampled to [count] bars (every app records one), or, when a
 * sender gave none or a flat one, a steady pattern seeded by the file address — the same rule as the website, so a message looks alike
 * everywhere and two messages never share one picture.
 */
internal fun voiceBarLevels(message: AloChatMessage, count: Int): List<Float> {
    // A flat waveform counts as none: Safari on iPhone recorded silence into the levels (every bar 0.08), a straight line.
    val given = voiceAttachmentOf(message)?.waveform.orEmpty().filter { it.isFinite() }
        .takeIf { levels -> levels.isNotEmpty() && (levels.max() - levels.min()) >= 0.03f }.orEmpty()
    if (given.isNotEmpty()) {
        return List(count) { index ->
            val at = if (count == 1) 0 else Math.round(index.toFloat() / (count - 1) * (given.size - 1))
            given[at.coerceIn(0, given.lastIndex)].coerceIn(0.14f, 1f)
        }
    }
    var seed = 7L
    val key = voiceAttachmentOf(message)?.url?.ifBlank { null } ?: message.attachmentUrl.ifBlank { message.attachmentName }
    for (char in key) seed = (seed * 31 + char.code) and 0xffffffffL
    return List(count) {
        seed = (seed * 1103515245L + 12345L) and 0xffffffffL
        (0.25f + ((seed ushr 8) % 1000) / 1000f * 0.7f).coerceIn(0.14f, 1f)
    }
}

/** The length the voice player works with: the file's, unless it is unknown or less than half of what the sender wrote. */
internal fun voicePlaybackDurationMs(playerMs: Int, declaredSeconds: Int): Int {
    val declaredMs = declaredSeconds.coerceAtLeast(0) * 1000
    return when {
        playerMs <= 0 -> declaredMs
        declaredMs > 0 && playerMs < declaredMs / 2 -> declaredMs
        else -> playerMs
    }
}

/** The length the sender wrote into the voice message or circle (`metadata.durationSeconds`), 0 when unknown. */
internal fun attachmentDurationSeconds(message: AloChatMessage): Int {
    val values = message.attachments.mapNotNull { attachment ->
        (attachment.metadata["durationSeconds"] ?: attachment.mediaEncryption["durationSeconds"])
            ?.trim()?.replace(',', '.')?.toDoubleOrNull()
    }
    val seconds = values.firstOrNull { it.isFinite() && it > 0 } ?: return 0
    return Math.round(seconds).toInt().coerceIn(1, 24 * 3600)
}

/**
 * Voice messages of cloud chats as local files (cache/alo-voice). The player gets a file, so the length is known at once
 * and the waveform can be scrubbed; a failed download returns null and the player streams the address as before.
 */
internal object AloVoiceFileCache {
    private const val MAX_BYTES = 25L * 1024 * 1024

    suspend fun fetch(context: android.content.Context, url: String): String? {
        val raw = url.trim()
        if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "alo-voice").apply { mkdirs() }
                val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(40)
                val extension = Regex("\\.([a-z0-9]{2,5})(?:[?#].*)?$", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1)?.lowercase() ?: "bin"
                val target = File(directory, "$digest.$extension")
                if (target.isFile && target.length() > 0) return@runCatching target.absolutePath
                val connection = (URL(raw).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    aloAuthenticatedMediaHeaders(raw).forEach { (name, value) -> setRequestProperty(name, value) }
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val partial = File(directory, "$digest.part")
                    var total = 0L
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) throw IllegalStateException("voice message too large")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (total <= 0 || !partial.renameTo(target)) {
                        partial.delete()
                        return@runCatching null
                    }
                    target.absolutePath
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
}
