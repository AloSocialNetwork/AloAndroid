package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AloQrLoginPayloadTest {
    private val id = "a".repeat(32)
    private val secret = "B_-".repeat(15)

    @Test
    fun readsTheServersQrLoginCode() {
        val payload = AloQrLoginPayload.parse("alo://login/qr/$id/$secret")!!

        assertEquals(id, payload.id)
        assertEquals(secret, payload.secret)
        assertFalse(payload.isDeviceLink)
    }

    @Test
    fun readsADeviceLinkCode() {
        val payload = AloQrLoginPayload.parse("alo://login/link/$id/$secret")!!

        assertEquals(id, payload.id)
        assertTrue(payload.isDeviceLink)
    }

    @Test
    fun stillReadsTheOlderQueryForm() {
        val payload = AloQrLoginPayload.parse("alo://qr-login?id=$id&secret=$secret")!!

        assertEquals(secret, payload.secret)
    }

    @Test
    fun readsTheWebsitesCameraLink() {
        val payload = AloQrLoginPayload.parse("https://test.xn--80awj.online/qr#$id.$secret")!!

        assertEquals(id, payload.id)
        assertEquals(secret, payload.secret)
        assertEquals("test.xn--80awj.online", payload.webHost)
        assertFalse(payload.isDeviceLink)
        assertNull(AloQrLoginPayload.parse("https://test.xn--80awj.online/qr?x=1#$id.$secret"))
        assertNull(AloQrLoginPayload.parse("https://test.xn--80awj.online/qr#$id"))
        assertNull(AloQrLoginPayload.parse("https://test.xn--80awj.online/other#$id.$secret"))
        assertNull(AloQrLoginPayload.parse("https://test.xn--80awj.online/qr#$id.$secret.x"))
    }

    @Test
    fun trustsOnlyTheAloSiteThisAppTalksTo() {
        val api = "https://api.xn--80awj.online/"
        assertTrue(AloQrLoginPayload.isAloWebHost("xn--80awj.online", appLinkHost = "xn--80awj.online", apiBaseUrl = api))
        assertTrue(AloQrLoginPayload.isAloWebHost("test.xn--80awj.online", appLinkHost = "xn--80awj.online", apiBaseUrl = "https://test.xn--80awj.online/"))
        assertTrue(AloQrLoginPayload.isAloWebHost("xn--80awj.online", appLinkHost = "", apiBaseUrl = api))
        assertFalse(AloQrLoginPayload.isAloWebHost("attacker.example", appLinkHost = "xn--80awj.online", apiBaseUrl = api))
        assertFalse(AloQrLoginPayload.isAloWebHost("", appLinkHost = "", apiBaseUrl = api))
    }

    @Test
    fun rejectsOtherLinksAndShortSecrets() {
        assertNull(AloQrLoginPayload.parse("https://example.com/login/qr/$id/$secret"))
        assertNull(AloQrLoginPayload.parse("alo://login/qr/$id/short"))
        assertNull(AloQrLoginPayload.parse("alo://login/other/$id/$secret"))
        assertNull(AloQrLoginPayload.parse("alo://login/qr/$id/$secret?x=1"))
    }
}
