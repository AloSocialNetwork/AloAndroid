package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloMediaSizeTest {
    @Test
    fun rotatedPhotoAndVideoReportTheirDisplaySize() {
        assertEquals(3024 to 4032, AloMediaSize.display(4032, 3024, AloMediaSize.exifSwapsAxes(6)))
        assertEquals(4032 to 3024, AloMediaSize.display(4032, 3024, AloMediaSize.exifSwapsAxes(1)))
        assertEquals(1080 to 1920, AloMediaSize.display(1920, 1080, AloMediaSize.rotationSwapsAxes(90)))
        assertEquals(1080 to 1920, AloMediaSize.display(1920, 1080, AloMediaSize.rotationSwapsAxes(-90)))
        assertEquals(1920 to 1080, AloMediaSize.display(1920, 1080, AloMediaSize.rotationSwapsAxes(180)))
    }

    @Test
    fun unknownSizesAreLeftOutOfTheEncryptedPayload() {
        assertEquals(0 to 0, AloMediaSize.display(-1, -1, false))
        assertEquals(emptyMap<String, String>(), AloChatDraftAttachment(attachmentType = "image").mediaMetadata)
        assertEquals(
            mapOf("width" to "1080", "height" to "1440"),
            AloChatDraftAttachment(attachmentType = "image", width = 1080, height = 1440).mediaMetadata
        )
    }
}
