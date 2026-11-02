package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloMessageMarkupTest {
    @Test
    fun markupProducesEncryptedTextEntitiesWithUtf16Offsets() {
        val result = AloMessageMarkup.parse(
            "😀 **bold** __italic__ ||secret|| https://alo.example"
        )

        assertEquals("😀 bold italic secret https://alo.example", result.body)
        assertEquals(
            listOf("bold", "italic", "spoiler", "link"),
            result.entities.map { it.type }
        )
        assertEquals(3, result.entities.first().offset)
        assertEquals(4, result.entities.first().length)
        assertEquals("https://alo.example", result.entities.last().url)
    }

    @Test
    fun markupCanBeRestoredForEditing() {
        val initial = AloMessageMarkup.parse(
            "**bold** __italic__ ++under++ ~~strike~~ ||secret|| `code`"
        )
        val restored = AloMessageMarkup.source(initial.body, initial.entities)
        val reparsed = AloMessageMarkup.parse(restored)

        assertEquals(initial.body, reparsed.body)
        assertEquals(initial.entities, reparsed.entities)
    }

    @Test
    fun usernameMentionStaysInsideTheEncryptedEntityPayload() {
        val result = AloMessageMarkup.parse("😀 привет @Alo_User")

        val mention = result.entities.single { it.type == "mention" }
        assertEquals(10, mention.offset)
        assertEquals("@Alo_User".length, mention.length)
        assertEquals(buildAloLink("r", "alo_user"), mention.url)
    }
}
