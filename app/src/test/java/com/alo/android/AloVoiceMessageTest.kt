package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice bubble draws what the sender recorded (bars and length), never an invented picture. */
class AloVoiceMessageTest {
    private fun message(id: Int, attachment: AloOutgoingMessageAttachment) = AloChatMessage(
        id = id, roomId = 0, senderId = 1, recipientId = 2, body = "",
        attachmentUrl = attachment.url, attachmentType = attachment.type, attachmentName = attachment.name,
        attachments = listOf(attachment), createdAt = "2026-10-26T10:00:00Z", editedAt = "", readAt = ""
    )

    private fun voice(url: String, waveform: List<Float> = emptyList(), duration: String? = null) =
        AloOutgoingMessageAttachment(
            url = url, type = "voice", name = "voice-1.ogg",
            metadata = duration?.let { mapOf("durationSeconds" to it) } ?: emptyMap(),
            waveform = waveform
        )

    @Test
    fun theSendersWaveformIsResampledToTheBars() {
        val levels = voiceBarLevels(message(7, voice("https://x/a.ogg", listOf(0.2f, 1f, 0.5f))), 5)
        assertEquals(listOf(0.2f, 1f, 1f, 0.5f, 0.5f).size, levels.size)
        assertEquals(0.2f, levels.first(), 0.001f)
        assertEquals(0.5f, levels.last(), 0.001f)
        assertTrue(levels.contains(1f))
    }

    @Test
    fun withoutAWaveformEachFileGetsItsOwnSteadyPattern() {
        val first = voiceBarLevels(message(1, voice("https://x/a.ogg")), 28)
        val again = voiceBarLevels(message(99, voice("https://x/a.ogg")), 28)
        val other = voiceBarLevels(message(1, voice("https://x/b.ogg")), 28)
        assertEquals("the same file looks the same in every message", first, again)
        assertNotEquals("two files do not share one picture", first, other)
        assertTrue(first.all { it in 0.14f..1f })
    }

    @Test
    fun aFlatWaveformIsDrawnLikeNoneNotAsAStraightLine() {
        val flat = voiceBarLevels(message(1, voice("https://x/a.ogg", List(30) { 0.08f })), 28)
        val none = voiceBarLevels(message(1, voice("https://x/a.ogg")), 28)
        assertEquals("Safari's silent levels get the file's own pattern", none, flat)
        assertTrue(flat.toSet().size > 5)
    }

    @Test
    fun theLengthComesFromTheMessageWhateverTheNumberFormat() {
        assertEquals(4, attachmentDurationSeconds(message(1, voice("u", duration = "4.0"))))
        assertEquals(3, attachmentDurationSeconds(message(1, voice("u", duration = "3"))))
        assertEquals(3, attachmentDurationSeconds(message(1, voice("u", duration = "2,6"))))
        assertEquals(0, attachmentDurationSeconds(message(1, voice("u"))))
        assertEquals("0:07", estimateAttachmentDurationLabel(message(42, voice("u", duration = "7"))))
        assertEquals("an unknown length is not invented", "", estimateAttachmentDurationLabel(message(42, voice("u"))))
    }

    @Test
    fun aBrowserRecordingKeepsTheLengthTheSenderWrote() {
        // A fragmented MP4 from a browser: the player reports its first fragment only.
        assertEquals(4_000, voicePlaybackDurationMs(playerMs = 250, declaredSeconds = 4))
        // Unknown to the player: the sender's length.
        assertEquals(3_000, voicePlaybackDurationMs(playerMs = 0, declaredSeconds = 3))
        // A file that knows its length keeps it (the sender rounded to whole seconds).
        assertEquals(3_420, voicePlaybackDurationMs(playerMs = 3_420, declaredSeconds = 3))
        assertEquals(61_250, voicePlaybackDurationMs(playerMs = 61_250, declaredSeconds = 0))
    }
}
