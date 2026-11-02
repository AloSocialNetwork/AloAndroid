package com.alo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloPushSoundTest {
    @Test
    fun playsASoundOnlyWhenNeitherTheServerNorTheUserAskedForSilence() {
        assertFalse(AloPushRegistration.isSilent(serverSilent = false, soundsEnabled = true))
    }

    @Test
    fun theSettingSilencesEveryPush() {
        assertTrue(AloPushRegistration.isSilent(serverSilent = false, soundsEnabled = false))
        assertTrue(AloPushRegistration.isSilent(serverSilent = true, soundsEnabled = false))
    }

    @Test
    fun theServerCanStillSilenceAPushWhenSoundsAreOn() {
        assertTrue(AloPushRegistration.isSilent(serverSilent = true, soundsEnabled = true))
    }
}
