package com.alo.android

data class AloChatAppearance(
    val wallpaper: String = "classic",
    val palette: String = "blue",
    val gradientStyle: String = "diagonal",
    val patternStyle: String = "ink",
    val wallpaperHue: Float = -1f,
    val bubbleHue: Float = -1f,
    val wallpaperBrightness: Float = 0.02f,
    val outgoingBrightness: Float = 0.78f,
    val incomingBrightness: Float = 0.16f,
    val patternIntensity: Float = 0.055f,
    val compactSpacing: Boolean = false
)

enum class AloAppTab {
    Feed,
    Search,
    Messages,
    Alerts,
    Profile
}

enum class AloFeedMode {
    Popular,
    Subscriptions
}

enum class AloChatRecordMode {
    Voice,
    VideoCircle
}

/** The lists the first screen only shows the top of; the next pages are asked for as the person scrolls to the end. */
enum class AloPagedList { FeedPopular, FeedSubscriptions, ProfilePosts, Notifications, LikedPosts, ViewedProfilePosts }

enum class AloThemeMode {
    Dark,
    Light,
    System
}

enum class AloChatCaptureState {
    Idle,
    Recording,
    Preview
}

sealed interface AloProfileTarget {
    data class User(val userId: Int) : AloProfileTarget
    data class Channel(val channelId: Int) : AloProfileTarget
    data class Group(val roomId: Int) : AloProfileTarget
}

data class AloChatDraftAttachment(
    val localUri: String = "",
    val previewUri: String = "",
    val attachmentType: String = "",
    val mimeType: String = "",
    val name: String = "",
    val durationSec: Int = 0,
    val isVideoCircle: Boolean = false,
    /** App-private ciphertext source for durable E2EE uploads. */
    val encryptedSourcePath: String = "",
    val mediaEncryption: Map<String, String> = emptyMap(),
    val encryptedChecksum: String = "",
    /** Display size (rotation applied); 0 when unknown or not visual media. */
    val width: Int = 0,
    val height: Int = 0,
    /** Loudness bars 0…1 of a recorded voice message (sent as its waveform). */
    val waveform: List<Float> = emptyList(),
    /** A song's tags (AloMusic.kt): sent as `title` / `performer`, as the website and the iPhone app send them. */
    val songTitle: String = "",
    val songPerformer: String = ""
) {
    /**
     * Travels inside the E2EE payload (never the server envelope), so a receiver
     * can size the photo bubble before it downloads and decrypts the file.
     */
    val mediaMetadata: Map<String, String>
        get() = buildMap {
            if (width > 0 && height > 0) {
                put("width", width.toString())
                put("height", height.toString())
            }
            if (attachmentType == "file" && mimeType.startsWith("audio/")) {
                put("mime", mimeType)
                if (songTitle.isNotBlank()) put("title", songTitle)
                if (songPerformer.isNotBlank()) put("performer", songPerformer)
            }
        }
}

internal object AloMediaSize {
    /** EXIF orientations 5–8 and 90°/270° video rotations swap the display axes. */
    fun display(width: Int, height: Int, swapsAxes: Boolean): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return 0 to 0
        return if (swapsAxes) height to width else width to height
    }

    fun exifSwapsAxes(orientation: Int): Boolean = orientation in 5..8

    fun rotationSwapsAxes(degrees: Int): Boolean = ((degrees % 360) + 360) % 360 in setOf(90, 270)

    private const val MAX_SIDE = 100_000
}

data class AloMediaViewerState(
    val items: List<AloMediaItem>,
    val startIndex: Int = 0,
    val isSensitive: Boolean = items.any(AloMediaItem::isSensitive)
)

data class AloCallLogMeta(
    val callType: String,
    val status: String,
    val durationSec: Int,
    val scope: String = "direct"
)

enum class AloDirectCallPhase {
    Incoming,
    Outgoing,
    Connecting,
    Reconnecting,
    Active,
    NoAnswer,
    Interrupted,
    Busy,
    Declined;

    val isTerminal: Boolean
        get() = this == NoAnswer ||
            this == Interrupted ||
            this == Busy ||
            this == Declined
}

data class AloGroupCallUiState(
    val callId: Int,
    val roomId: Int,
    val roomTitle: String,
    val type: String = "voice",
    val participants: List<AloEntitySummary> = emptyList(),
    val videoParticipantIds: Set<Int> = emptySet(),
    val speakingParticipantIds: Set<Int> = emptySet(),
    val usesSfu: Boolean = false,
    val isReconnecting: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isVideoEnabled: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isCameraSwitching: Boolean = false,
    val isMinimized: Boolean = false,
    /** Telegram's video-chat controls (iOS's AloGroupCallControls). */
    val controls: AloGroupCallControls = AloGroupCallControls(),
    /** This phone is the one recording the chat. */
    val isRecordingHere: Boolean = false,
    val mutedParticipantIds: Set<Int> = emptySet(),
    val localUserId: Int = 0
)

data class AloGroupCallControls(
    val canManage: Boolean = false,
    val joinMuted: Boolean = false,
    /** Who raised a hand, and when (millis), oldest first in the list. */
    val raisedHands: Map<Int, Long> = emptyMap(),
    val mutedByAdmin: Set<Int> = emptySet(),
    val recording: AloGroupCallRecording? = null
)

data class AloGroupCallRecording(val startedAtMillis: Long, val byUserId: Int, val title: String)

data class AloDirectCallUiState(
    val callId: Int,
    val peerId: Int,
    val peerName: String,
    val peerUsername: String,
    val peerAvatarUrl: String,
    val verified: Boolean = false,
    val type: String = "voice",
    val phase: AloDirectCallPhase,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isIncoming: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isCameraSwitching: Boolean = false,
    val isScreenSharing: Boolean = false,
    val isScreenShareStarting: Boolean = false,
    val isRemoteVideoEnabled: Boolean = false,
    val isRemoteScreenSharing: Boolean = false,
    val startedAtMillis: Long = 0L,
    val callSecret: String = "",
    val safetyEmojis: List<String> = emptyList(),
    val isSecurityConfirmed: Boolean = false,
    val isMinimized: Boolean = false
) {
    val hasLocalVideo: Boolean
        get() = isVideoEnabled || isScreenSharing

    val hasRemoteVideo: Boolean
        get() = isRemoteVideoEnabled || isRemoteScreenSharing

    val hasVideoSurface: Boolean
        get() = type == "video" || hasLocalVideo || hasRemoteVideo
}

/** A file on its way out: sent and total bytes, and which of a message's files it is. */
data class AloUploadProgress(val sent: Long, val total: Long, val index: Int = 0, val count: Int = 1) {
    val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f

    /** «512 МБ из 1,48 ГБ», and «· файл 2 из 3» when a message has several, as iOS's label. */
    val label: String
        get() {
            val bytes = "${aloFileSize(sent)} из ${aloFileSize(total)}"
            return if (count > 1) "$bytes · файл ${index + 1} из $count" else bytes
        }
}

/** iOS's ByteCountFormatter(.file): decimal units, three significant digits, Russian names. */
internal fun aloFileSize(bytes: Long): String {
    val units = listOf("КБ" to 1_000.0, "МБ" to 1_000_000.0, "ГБ" to 1_000_000_000.0)
    if (bytes < 1_000) return "$bytes байт"
    val (name, size) = units.last { bytes >= it.second }
    val value = bytes / size
    val digits = when {
        value < 10 -> 2
        value < 100 -> 1
        else -> 0
    }
    val text = String.format(java.util.Locale.US, "%.${digits}f", value).trimEnd('0').trimEnd('.').replace('.', ',')
    return "$text $name"
}
