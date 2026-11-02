package com.alo.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AloCallSecurityV2Test {
    @Test
    fun matchesCrossPlatformVector() {
        val callerPrivate = decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val callerPublic = decode("j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8")
        val callerNonce = decode("QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8")
        val calleePrivate = decode("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8")
        val calleePublic = decode("NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ")
        val calleeNonce = decode("YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8")
        val offerFingerprint =
            "sha-256:000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
        val answerFingerprint =
            "sha-256:FFFEFDFCFBFAF9F8F7F6F5F4F3F2F1F0EFEEEDECEBEAE9E8E7E6E5E4E3E2E1E0"
        val expectedCommitment = decode("xbI0QvSBGX95wPCXGfywoYwdZtmqO1DHO-Xp-bclZ0s")
        val expectedSharedKey = decode("8l9bYSpf_Tgi58t0Y9zBpymN7w1asLa0pzJLCBHOTm0")
        val expectedOfferHash = decode("6lQ-MOyBdHO-hTLOW7WXL8y3Nuteh_tBIA5sLg5FGRc")
        val expectedTranscript = decode("KPlidH_3w0qEnZgHzK-_ZVEE1aw76NcqV4lrp0bqsLU")

        val callerSharedKey = AloCallSecurityV2.sharedKey(
            privateKey = callerPrivate,
            remotePublicKey = calleePublic,
            callId = 4_242,
            callerId = 1_001,
            calleeId = 2_002
        )
        val calleeSharedKey = AloCallSecurityV2.sharedKey(
            privateKey = calleePrivate,
            remotePublicKey = callerPublic,
            callId = 4_242,
            callerId = 1_001,
            calleeId = 2_002
        )
        assertArrayEquals(expectedSharedKey, callerSharedKey)
        assertArrayEquals(expectedSharedKey, calleeSharedKey)

        val commitment = AloCallSecurityV2.commitment(
            callId = 4_242,
            callerId = 1_001,
            calleeId = 2_002,
            callerPublicKey = callerPublic,
            callerNonce = callerNonce
        )
        assertArrayEquals(expectedCommitment, commitment)

        val offerHash = AloCallSecurityV2.offerTranscriptHash(
            callId = 4_242,
            callerId = 1_001,
            calleeId = 2_002,
            callerCommitment = commitment,
            callerPublicKey = callerPublic,
            callerNonce = callerNonce,
            calleePublicKey = calleePublic,
            calleeNonce = calleeNonce,
            offerFingerprint = offerFingerprint
        )
        assertArrayEquals(expectedOfferHash, offerHash)

        val transcript = AloCallSecurityV2.transcriptHash(
            callId = 4_242,
            callerId = 1_001,
            calleeId = 2_002,
            callerCommitment = commitment,
            callerPublicKey = callerPublic,
            callerNonce = callerNonce,
            calleePublicKey = calleePublic,
            calleeNonce = calleeNonce,
            offerFingerprint = offerFingerprint,
            answerFingerprint = answerFingerprint
        )
        assertArrayEquals(expectedTranscript, transcript)
        assertArrayEquals(
            decode("Q5QFKAImnOA2xwhZuvNe1ECV6KVt0XB7C3U-KqmjQic"),
            AloCallSecurityV2.proof(callerSharedKey, "caller-offer", offerHash)
        )
        assertArrayEquals(
            decode("FrKJVMOqgosWhjaQhQ26OkBfIRGg1BrmjFdv8Ta5wuE"),
            AloCallSecurityV2.proof(callerSharedKey, "callee-answer", transcript)
        )
        assertArrayEquals(
            decode("pAnLZfQVbXyAW70mzRTpO7lR3Y4WLSsw11rkWz5M9_A"),
            AloCallSecurityV2.proof(callerSharedKey, "caller-confirm", transcript)
        )
        assertEquals(
            listOf("🐟", "🔮", "👭", "🔟"),
            AloCallSecurityV2.emojis(callerSharedKey, transcript, callerPublic)
        )

        val reconnectOfferSdp =
            "v=0\r\na=fingerprint:sha-256 00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F\r\na=ice-ufrag:restart-a\r\n"
        val reconnectAnswerSdp =
            "v=0\r\na=fingerprint:sha-256 FF:FE:FD:FC:FB:FA:F9:F8:F7:F6:F5:F4:F3:F2:F1:F0:EF:EE:ED:EC:EB:EA:E9:E8:E7:E6:E5:E4:E3:E2:E1:E0\r\na=ice-ufrag:restart-b\r\n"
        val reconnectOfferSdpHash = AloCallSecurityV2.sdpHash(reconnectOfferSdp)
        val reconnectAnswerSdpHash = AloCallSecurityV2.sdpHash(reconnectAnswerSdp)
        assertArrayEquals(
            decode("dwxeUu3lzu9NarthkYOSuV2uqdcxYnqFYl_W4EZf8c4"),
            reconnectOfferSdpHash
        )
        assertArrayEquals(
            decode("x56smKHHEFjU-PQV7OzAvn5QiTBatid3gick5IVd2zw"),
            reconnectAnswerSdpHash
        )
        val reconnectOfferHash = AloCallSecurityV2.reconnectTranscriptHash(
            callId = 4_242,
            generation = 3,
            initialTranscriptHash = transcript,
            offerFingerprint = offerFingerprint,
            offerSdpHash = reconnectOfferSdpHash
        )
        assertArrayEquals(
            decode("H2jFb_2XmS2bOp7IdoFjlJUBxTo0WPLrn2zPt1FjlYs"),
            reconnectOfferHash
        )
        assertArrayEquals(
            decode("9fgNPdihS89D1slXcX6aFt58E2NisSdt-XosYIURKSo"),
            AloCallSecurityV2.proof(
                callerSharedKey,
                "caller-reconnect-offer",
                reconnectOfferHash
            )
        )
        val reconnectAnswerHash = AloCallSecurityV2.reconnectTranscriptHash(
            callId = 4_242,
            generation = 3,
            initialTranscriptHash = transcript,
            offerFingerprint = offerFingerprint,
            offerSdpHash = reconnectOfferSdpHash,
            answerFingerprint = answerFingerprint,
            answerSdpHash = reconnectAnswerSdpHash
        )
        assertArrayEquals(
            decode("zGQI93XcERsNX-PLQWX6jN9cMWIfMNP5a-KMR1vQ7mY"),
            reconnectAnswerHash
        )
        assertArrayEquals(
            decode("HZOTsaSrAdIk9ojsEQ6OEPAeyQilfHLl5GQPL7-42bs"),
            AloCallSecurityV2.proof(
                callerSharedKey,
                "callee-reconnect-answer",
                reconnectAnswerHash
            )
        )
    }

    private fun decode(value: String): ByteArray = AloCallSecurityV2.decode32(value)
}
