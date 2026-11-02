package com.alo.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class AloCallTone {
    Incoming,
    Ringback,
    Connecting,
    Connected,
    Busy,
    NoAnswer,
    Failed,
    Ended
}

internal class AloCallTonePlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var playbackJob: Job? = null
    private var ringtone: Ringtone? = null
    private var generator: ToneGenerator? = null
    private var currentTone: AloCallTone? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    fun play(tone: AloCallTone) {
        if (currentTone == tone) return
        stop()
        currentTone = tone
        when (tone) {
            AloCallTone.Incoming -> playIncoming()
            AloCallTone.Ringback -> playPattern(
                toneType = ToneGenerator.TONE_SUP_RINGTONE,
                durationMillis = 850,
                pauseMillis = 2_100,
                repetitions = null
            )
            AloCallTone.Connecting -> playPattern(
                toneType = ToneGenerator.TONE_PROP_BEEP2,
                durationMillis = 100,
                pauseMillis = 900,
                repetitions = null
            )
            AloCallTone.Connected -> playPattern(
                toneType = ToneGenerator.TONE_PROP_ACK,
                durationMillis = 180,
                pauseMillis = 0,
                repetitions = 1
            )
            AloCallTone.Busy -> playPattern(
                toneType = ToneGenerator.TONE_SUP_BUSY,
                durationMillis = 260,
                pauseMillis = 240,
                repetitions = 3
            )
            AloCallTone.NoAnswer -> playPattern(
                toneType = ToneGenerator.TONE_CDMA_CALLDROP_LITE,
                durationMillis = 620,
                pauseMillis = 0,
                repetitions = 1
            )
            AloCallTone.Failed -> playPattern(
                toneType = ToneGenerator.TONE_SUP_ERROR,
                durationMillis = 650,
                pauseMillis = 0,
                repetitions = 1
            )
            AloCallTone.Ended -> playPattern(
                toneType = ToneGenerator.TONE_PROP_ACK,
                durationMillis = 230,
                pauseMillis = 0,
                repetitions = 1
            )
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        ringtone?.stop()
        ringtone = null
        generator?.stopTone()
        generator?.release()
        generator = null
        abandonAudioFocus()
        currentTone = null
    }

    private fun playIncoming() {
        requestAudioFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val systemRingtone = runCatching {
            RingtoneManager.getRingtone(appContext, uri)
        }.getOrNull()
        if (systemRingtone == null) {
            playPattern(
                toneType = ToneGenerator.TONE_SUP_RINGTONE,
                durationMillis = 900,
                pauseMillis = 1_100,
                repetitions = null
            )
            return
        }

        runCatching {
            systemRingtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            systemRingtone.isLooping = true
        }
        ringtone = systemRingtone
        systemRingtone.play()
        playbackJob = scope.launch {
            while (isActive) {
                if (ringtone?.isPlaying == false) {
                    ringtone?.play()
                }
                delay(500)
            }
        }
    }

    private fun playPattern(
        toneType: Int,
        durationMillis: Int,
        pauseMillis: Long,
        repetitions: Int?
    ) {
        requestAudioFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        val toneGenerator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 82)
        }.getOrNull() ?: return
        generator = toneGenerator
        playbackJob = scope.launch {
            var played = 0
            while (isActive && (repetitions == null || played < repetitions)) {
                toneGenerator.startTone(toneType, durationMillis)
                delay(durationMillis.toLong() + pauseMillis)
                played += 1
            }
        }
    }

    private fun requestAudioFocus(gain: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                gain
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
