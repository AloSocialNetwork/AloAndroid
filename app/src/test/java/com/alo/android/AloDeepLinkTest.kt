package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AloDeepLinkTest {
    private val host = "links.alo.test"

    @Test
    fun canonicalRoutesMatchTheSharedContract() {
        assertEquals(AloDeepLinkTarget.Post(42), parse("https://$host/p/42"))
        assertEquals(AloDeepLinkTarget.User(7), parse("https://$host/u/7"))
        assertEquals(AloDeepLinkTarget.Channel(9), parse("https://$host/ch/9"))
        assertEquals(AloDeepLinkTarget.ForumTopic(77, 13), parse("https://$host/t/77/13"))
        assertEquals(AloDeepLinkTarget.Handle("public-name"), parse("https://$host/public-name"))
        assertEquals(AloDeepLinkTarget.Handle("public_name"), parse("alo://r/public_name"))
        assertEquals(AloDeepLinkTarget.Story("AbC_12-xyz"), parse("https://$host/s/AbC_12-xyz"))
        assertEquals(AloDeepLinkTarget.Story("AbC_12-xyz"), parse("alo://s/AbC_12-xyz"))
        assertNull(parse("alo://s/short"))
        assertEquals(
            AloDeepLinkTarget.Invite("abcdefghijklmnop"),
            parse("https://$host/join/abcdefghijklmnop")
        )
    }

    @Test
    fun parserRejectsForeignInsecureAndAmbiguousUrls() {
        assertNull(parse("https://other.test/p/42"))
        assertNull(parse("http://$host/p/42"))
        assertNull(parse("https://$host/p/42/extra"))
        assertNull(parse("https://$host/t/77/not-a-topic"))
        assertNull(parse("https://$host/p/not-a-code"))
        assertNull(parse("https://$host/p/012345678901"))
        assertNull(parse("javascript:alert(1)"))
    }

    @Test
    fun postLinksUseTheRandomCodeAndStillReadOldNumbers() {
        assertEquals(AloDeepLinkTarget.PostCode("Ab3xY9kLm0Qz"), parse("https://$host/p/Ab3xY9kLm0Qz"))
        assertEquals(AloDeepLinkTarget.PostCode("Ab3xY9kLm0Qz"), parse("alo://p/Ab3xY9kLm0Qz"))
        assertEquals(AloDeepLinkTarget.Post(42), parse("https://$host/p/42"))
        assertNull(parse("https://$host/p/short"))
        assertEquals(false, AloDeepLinkTarget.isPostCode("../../x/abcde"))
    }

    private fun parse(value: String): AloDeepLinkTarget? =
        AloDeepLinkTarget.parse(value, configuredWebHost = host)
}
