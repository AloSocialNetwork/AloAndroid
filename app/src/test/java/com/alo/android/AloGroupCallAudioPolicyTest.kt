package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloGroupCallAudioPolicyTest {
    @Test
    fun peerToPeerFallbackSupportsOnlyOneToOneAudio() {
        assertTrue(AloGroupCallAudioPolicy.canUsePeerToPeerFallback(1))
        assertTrue(AloGroupCallAudioPolicy.canUsePeerToPeerFallback(2))
        assertFalse(AloGroupCallAudioPolicy.canUsePeerToPeerFallback(0))
        assertFalse(AloGroupCallAudioPolicy.canUsePeerToPeerFallback(3))
    }
}
