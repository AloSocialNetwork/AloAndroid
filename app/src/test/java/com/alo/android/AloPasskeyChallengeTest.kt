package com.alo.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AloPasskeyChallengeTest {
    private val answer = JSONObject("""{"ok":true,"requestId":"request-1234567890","expiresAt":"x","options":{"challenge":"abc","rpId":"site"}}""")

    @Test
    fun keepsTheRequestIdAndHandsOnlyTheOptionsToTheSystem() {
        val challenge = parseAloPasskeyChallenge(answer)
        assertEquals("request-1234567890", challenge.requestId)
        assertEquals("abc", JSONObject(challenge.optionsJson).getString("challenge"))
        assertEquals(false, JSONObject(challenge.optionsJson).has("requestId"))
    }

    @Test
    fun theAnswerCarriesTheRequestId() {
        val body = aloPasskeyVerifyBody(parseAloPasskeyChallenge(answer), """{"id":"key","type":"public-key"}""")
        assertEquals("request-1234567890", body.getString("requestId"))
        assertEquals("key", body.getJSONObject("credential").getString("id"))
    }

    @Test
    fun anIncompleteAnswerIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { parseAloPasskeyChallenge(JSONObject("""{"ok":true,"options":{"challenge":"abc"}}""")) }
        assertThrows(IllegalArgumentException::class.java) { parseAloPasskeyChallenge(JSONObject("""{"ok":true,"requestId":"request-1"}""")) }
    }

    @Test
    fun readsTheListTheServerSends() {
        val list = parseAloPasskeys(JSONObject("""{"ok":true,"enabled":true,"credentials":[{"id":7,"label":"Ключ доступа","createdAt":"a","lastUsedAt":"b"},{"id":0}]}"""))
        assertEquals(1, list.size)
        assertEquals("7", list.first().id)
        assertEquals("Ключ доступа", list.first().name)
        assertEquals("a", list.first().createdAt)
        assertEquals(0, parseAloPasskeys(JSONObject("""{"ok":true}""")).size)
    }
}
