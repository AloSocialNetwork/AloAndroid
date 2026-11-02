package com.alo.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

class AloLibSignalSessionEngineTest {
    @Test
    fun pqxdhDoubleRatchetAndSenderKeysInteroperate() {
        val aliceStore = InMemorySignalProtocolStore(
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false)
        )
        val bobStore = InMemorySignalProtocolStore(
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false)
        )
        val alice = AloLibSignalSessionEngine(aliceStore, localUserId = 1, localDeviceId = 1)
        val bob = AloLibSignalSessionEngine(bobStore, localUserId = 2, localDeviceId = 1)

        val bobBundle = bob.generatePreKeyBundle()
        alice.processRemotePreKeyBundle(remoteUserId = 2, bundle = bobBundle)

        val firstPlaintext = "first pqxdh message".toByteArray()
        val firstCiphertext = alice.encrypt(2, 1, firstPlaintext)
        assertEquals(CiphertextMessage.PREKEY_TYPE, firstCiphertext.type)
        assertArrayEquals(firstPlaintext, bob.decrypt(1, 1, firstCiphertext.type, firstCiphertext.bytes))

        val replyPlaintext = "double ratchet reply".toByteArray()
        val replyCiphertext = bob.encrypt(1, 1, replyPlaintext)
        assertEquals(CiphertextMessage.WHISPER_TYPE, replyCiphertext.type)
        assertArrayEquals(replyPlaintext, alice.decrypt(2, 1, replyCiphertext.type, replyCiphertext.bytes))

        val outOfOrder = (0 until 8).map { index ->
            val plaintext = "message-$index".toByteArray()
            plaintext to alice.encrypt(2, 1, plaintext)
        }
        listOf(5, 1, 7, 0, 6, 3, 2, 4).forEach { index ->
            val (plaintext, ciphertext) = outOfOrder[index]
            assertArrayEquals(plaintext, bob.decrypt(1, 1, ciphertext.type, ciphertext.bytes))
        }

        val distributionId = UUID.randomUUID()
        val distribution = alice.createSenderKeyDistribution(distributionId)
        bob.processSenderKeyDistribution(1, 1, distribution)
        val groupPlaintext = "sender-key group message".toByteArray()
        val groupCiphertext = alice.groupEncrypt(distributionId, groupPlaintext)
        assertArrayEquals(groupPlaintext, bob.groupDecrypt(1, 1, groupCiphertext))
        assertTrue(groupCiphertext.isNotEmpty())

        val reorderedGroupMessages = (0 until 6).map { index ->
            val nextDistribution = alice.createSenderKeyDistribution(distributionId)
            val plaintext = "sender-key-$index".toByteArray()
            Triple(plaintext, nextDistribution, alice.groupEncrypt(distributionId, plaintext))
        }
        listOf(4, 1, 5, 0, 3, 2).forEach { index ->
            val (plaintext, nextDistribution, ciphertext) = reorderedGroupMessages[index]
            bob.processSenderKeyDistribution(1, 1, nextDistribution)
            assertArrayEquals(plaintext, bob.groupDecrypt(1, 1, ciphertext))
        }
    }
}
