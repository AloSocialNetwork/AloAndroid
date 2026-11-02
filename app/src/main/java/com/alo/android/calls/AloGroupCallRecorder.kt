package com.alo.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import livekit.org.webrtc.AudioTrackSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Records a video chat's sound on the admin's phone, as iOS's AloGroupCallRecorder does: this phone's
 * microphone and everyone else's voices, mixed into one 48 kHz mono AAC file.
 *
 * Every source (one sink per audio track) delivers 10 ms PCM chunks on WebRTC's audio threads. Each
 * keeps a cursor on a shared timeline that starts with the recording; chunks are added there, and a
 * cursor that drifts from the wall clock by more than a quarter second snaps back. Mixed audio older
 * than half a second is final and goes to the encoder.
 */
internal class AloGroupCallRecorder(val file: File) {
    private val lock = Any()
    private val startedAtNanos = System.nanoTime()
    /** 10 ms chunks of mixed audio by index on the timeline. */
    private val chunks = HashMap<Long, FloatArray>()
    private val cursors = HashMap<Any, Long>()
    private var nextChunkToEncode = 0L
    private val encoder = Executors.newSingleThreadExecutor()
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var encodedFrames = 0L
    private var stopped = false

    fun start() {
        file.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2 * 8)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** A sink for one audio track; [source] tells tracks apart. */
    fun sink(source: Any): AudioTrackSink = AudioTrackSink { data, bits, rate, channels, frames, _ ->
        receive(source, data, bits, rate, channels, frames)
    }

    private fun elapsedFrames(): Long = (System.nanoTime() - startedAtNanos) * RATE / 1_000_000_000L

    private fun receive(source: Any, data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
        if (bits != 16 || rate <= 0 || channels <= 0 || frames <= 0) return
        val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += pcm.get(frame * channels + channel) / 32_768f
            sum / channels
        }
        val samples = if (rate == RATE) mono else resample(mono, rate)
        val ready: List<FloatArray>
        synchronized(lock) {
            if (stopped) return
            val now = elapsedFrames()
            var cursor = cursors[source] ?: now
            if (abs(cursor - now) > RATE / 4) cursor = now
            samples.forEachIndexed { index, value ->
                val position = cursor + index
                val chunk = chunks.getOrPut(position / CHUNK_FRAMES) { FloatArray(CHUNK_FRAMES) }
                chunk[(position % CHUNK_FRAMES).toInt()] += value
            }
            cursors[source] = cursor + samples.size
            ready = takeFinalChunks(upTo = (now - RATE / 2) / CHUNK_FRAMES)
        }
        if (ready.isNotEmpty()) encoder.execute { ready.forEach(::encode) }
    }

    /** Chunks before [upTo] in order; a gap (nobody spoke, nothing played) is silence. */
    private fun takeFinalChunks(upTo: Long): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        while (nextChunkToEncode < upTo) {
            result += chunks.remove(nextChunkToEncode) ?: FloatArray(CHUNK_FRAMES)
            nextChunkToEncode++
        }
        return result
    }

    private fun resample(input: FloatArray, rate: Int): FloatArray {
        val outCount = (input.size.toLong() * RATE / rate).toInt().coerceAtLeast(1)
        return FloatArray(outCount) { index ->
            val position = index.toFloat() * rate / RATE
            val left = position.toInt().coerceAtMost(input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = position - left
            input[left] * (1 - fraction) + input[right] * fraction
        }
    }

    private fun encode(chunk: FloatArray, endOfStream: Boolean = false) {
        val codec = codec ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val buffer = codec.getInputBuffer(inputIndex) ?: return
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            chunk.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * 32_767f).roundToInt().toShort()) }
            val presentationUs = encodedFrames * 1_000_000L / RATE
            encodedFrames += chunk.size
            codec.queueInputBuffer(
                inputIndex, 0, chunk.size * 2, presentationUs,
                if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
        }
        drain(endOfStream)
    }

    private fun drain(untilEnd: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, output, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!untilEnd || index != MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
    }

    /** Stops listening and closes the file: its length in seconds, or null when nothing was recorded. */
    fun stop(): Int? {
        val remaining: List<FloatArray>
        synchronized(lock) {
            stopped = true
            val end = (cursors.values.maxOrNull() ?: 0L) / CHUNK_FRAMES + 1
            remaining = takeFinalChunks(upTo = end)
        }
        encoder.execute {
            remaining.forEach(::encode)
            encode(FloatArray(0), endOfStream = true)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            codec = null
            muxer = null
        }
        encoder.shutdown()
        encoder.awaitTermination(5, TimeUnit.SECONDS)
        val seconds = (encodedFrames / RATE).toInt()
        if (!muxerStarted || encodedFrames <= 0) {
            file.delete()
            return null
        }
        return seconds
    }

    private companion object {
        const val RATE = 48_000
        const val CHUNK_FRAMES = 480
    }
}
