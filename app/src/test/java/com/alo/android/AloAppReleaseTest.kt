package com.alo.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AloAppReleaseTest {
    private val sha = "a".repeat(64)
    private fun answer(url: String = "/downloads/android/alo-0.1.2-3.apk", code: Int = 3, size: Long = 1000, hash: String = sha) = JSONObject(
        """{"ok":true,"release":{"versionCode":$code,"versionName":"0.1.2","notes":"Быстрее","size":$size,"sha256":"$hash","url":"$url"}}"""
    )

    @Test
    fun readsARelease() {
        val release = parseAloAppRelease(answer())!!
        assertEquals(3, release.versionCode)
        assertEquals("0.1.2", release.versionName)
        assertEquals("Быстрее", release.notes)
        assertEquals("alo-0.1.2-3.apk", release.fileName)
    }

    @Test
    fun noReleaseMeansNothingToOffer() {
        assertNull(parseAloAppRelease(JSONObject("""{"ok":true,"release":null}""")))
        assertNull(parseAloAppRelease(JSONObject("""{"ok":true}""")))
    }

    @Test
    fun ignoresAnAnswerThatPointsAnywhereElse() {
        assertNull(parseAloAppRelease(answer(url = "https://example.com/alo.apk")))
        assertNull(parseAloAppRelease(answer(url = "/downloads/android/../../etc/passwd")))
        assertNull(parseAloAppRelease(answer(url = "/uploads/messages/alo-1.apk")))
        assertNull(parseAloAppRelease(answer(code = 0)))
        assertNull(parseAloAppRelease(answer(size = 0)))
        assertNull(parseAloAppRelease(answer(hash = "abc")))
    }
}
