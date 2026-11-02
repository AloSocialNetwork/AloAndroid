package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloMediaCacheTest {
    private val mb = 1_024L * 1_024L

    @Test
    fun offPresetLoadsNothing() {
        assertFalse(AloMediaDownloadPreset.Off.permits("image", 1))
        assertFalse(AloMediaDownloadPreset.Off.permits("voice", 1))
    }

    @Test
    fun photosAndVoiceFollowTelegramLimits() {
        assertTrue(AloMediaDownloadPreset.Low.permits("image/jpeg", mb))
        assertFalse(AloMediaDownloadPreset.Low.permits("image/jpeg", mb + 1))
        assertTrue(AloMediaDownloadPreset.Low.permits("voice", 2 * mb))
        assertFalse(AloMediaDownloadPreset.Medium.permits("voice", 2 * mb + 1))
        assertTrue(AloMediaDownloadPreset.High.permits("voice", 3 * mb))
    }

    @Test
    fun lowNeverLoadsVideoOrFilesAndHighLoadsMore() {
        assertFalse(AloMediaDownloadPreset.Low.permits("video", 1))
        assertFalse(AloMediaDownloadPreset.Low.permits("file", 1))
        assertTrue(AloMediaDownloadPreset.Medium.permits("video/mp4", 5 * mb / 2))
        assertFalse(AloMediaDownloadPreset.Medium.permits("video/mp4", 5 * mb / 2 + 1))
        assertTrue(AloMediaDownloadPreset.High.permits("video", 10 * mb))
        assertTrue(AloMediaDownloadPreset.Medium.permits("application/pdf", mb))
        assertFalse(AloMediaDownloadPreset.Medium.permits("application/pdf", mb + 1))
        assertTrue(AloMediaDownloadPreset.High.permits("application/pdf", 3 * mb))
    }

    @Test
    fun unknownSizeWaitsForATap() {
        assertFalse(AloMediaDownloadPreset.High.permits("image", null))
        assertFalse(AloMediaDownloadPreset.High.permits("image", 0))
    }

    @Test
    fun connectionPicksThePresetAndATapAlwaysLoads() {
        val settings = AloMediaCacheSettings(
            wifi = AloMediaDownloadPreset.High,
            cellular = AloMediaDownloadPreset.Off
        )
        assertTrue(settings.permits("image", mb, AloMediaNetwork.Wifi, userInitiated = false))
        assertFalse(settings.permits("image", mb, AloMediaNetwork.Cellular, userInitiated = false))
        assertFalse(settings.permits("image", mb, AloMediaNetwork.Constrained, userInitiated = false))
        assertTrue(settings.permits("video", 500 * mb, AloMediaNetwork.Cellular, userInitiated = true))
        assertFalse(settings.permits("image", mb, AloMediaNetwork.Offline, userInitiated = true))
    }

    @Test
    fun defaultsMatchTheIPhone() {
        val settings = AloMediaCacheSettings()
        assertEquals(AloMediaDownloadPreset.High, settings.wifi)
        assertEquals(AloMediaDownloadPreset.Medium, settings.cellular)
        assertEquals(2L * AloMediaCacheSettings.GB, settings.maximumBytes)
    }

    @Test
    fun undownloadedMeansEncryptedAndStillRemote() {
        val remote = AloOutgoingMessageAttachment("/uploads/messages/a.bin", "image", "a.jpg", mediaEncryption = mapOf("algorithm" to "x"))
        assertTrue(remote.isUndownloaded)
        assertFalse(remote.copy(url = "content://com.alo.android.e2ee/media/t").isUndownloaded)
        assertFalse(remote.copy(url = "file:///data/a").isUndownloaded)
        assertFalse(remote.copy(mediaEncryption = emptyMap()).isUndownloaded)
    }
}
