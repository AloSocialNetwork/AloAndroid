package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloEncryptedContactAttachmentTest {
    @Test
    fun contactCardKeepsIdentityInsideVirtualAttachmentMetadata() {
        val contact = AloEntitySummary(
            id = 41,
            name = "Алексей",
            username = "alex",
            avatarUrl = "https://cdn.example/avatar.jpg",
            verified = true
        )

        val attachment = AloOutgoingMessageAttachment.contact(contact)

        assertTrue(attachment.isVirtualCard)
        assertTrue(attachment.isContactCard)
        assertEquals("", attachment.url)
        assertEquals("41", attachment.metadata["userId"])
        assertEquals("Алексей", attachment.metadata["displayName"])
        assertEquals("true", attachment.metadata["verified"])
        assertFalse(attachment.mediaEncryption.isNotEmpty())
    }

    @Test
    fun staticLocationCardIsVirtualAndHasNoMediaUrl() {
        val attachment = AloOutgoingMessageAttachment.location(55.755826, 37.6173)

        assertTrue(attachment.isVirtualCard)
        assertTrue(attachment.isLocationCard)
        assertEquals("", attachment.url)
        assertEquals("55.75583", attachment.metadata["latitude"])
        assertEquals("37.61730", attachment.metadata["longitude"])
        assertFalse(attachment.mediaEncryption.isNotEmpty())
    }
}
