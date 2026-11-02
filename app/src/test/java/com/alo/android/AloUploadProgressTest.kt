package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloUploadProgressTest {
    @Test
    fun sizesReadAsOnIos() {
        assertEquals("512 МБ", aloFileSize(512_000_000))
        assertEquals("1,48 ГБ", aloFileSize(1_480_000_000))
        assertEquals("12,3 МБ", aloFileSize(12_300_000))
        assertEquals("1 ГБ", aloFileSize(1_000_000_000))
        assertEquals("999 байт", aloFileSize(999))
    }

    @Test
    fun labelNamesTheFileWhenAMessageHasSeveral() {
        assertEquals("512 МБ из 1,48 ГБ", AloUploadProgress(512_000_000, 1_480_000_000).label)
        assertEquals("1 МБ из 2 МБ · файл 2 из 3", AloUploadProgress(1_000_000, 2_000_000, index = 1, count = 3).label)
        assertEquals(0.5f, AloUploadProgress(1, 2).fraction)
    }
}
