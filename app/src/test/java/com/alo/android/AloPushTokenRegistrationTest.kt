package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloPushTokenRegistrationTest {
    @Test
    fun sendsTheSoundChoiceAsARealBoolean() {
        val off = pushTokenRegistrationBody("token", "device", "0.1.0", soundsEnabled = false)
        assertTrue(off.get("soundsEnabled") is Boolean)
        assertFalse(off.getBoolean("soundsEnabled"))
        assertTrue(pushTokenRegistrationBody("token", "device", "0.1.0", soundsEnabled = true).getBoolean("soundsEnabled"))
    }

    @Test
    fun leavesTheSoundChoiceOutWhenUnknownSoTheServerKeepsItsOwn() {
        val body = pushTokenRegistrationBody("token", "device", "0.1.0", soundsEnabled = null)
        assertFalse(body.has("soundsEnabled"))
    }

    @Test
    fun trimsTheFieldsAndNamesTheAndroidPlatform() {
        val body = pushTokenRegistrationBody("  token  ", " device ", " 0.1.0 ", soundsEnabled = true)
        assertEquals("android", body.getString("platform"))
        assertEquals("token", body.getString("token"))
        assertEquals("device", body.getString("deviceId"))
        assertEquals("0.1.0", body.getString("appVersion"))
    }
}
