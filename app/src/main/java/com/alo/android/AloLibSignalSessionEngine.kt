package com.alo.android

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom
import java.util.UUID

data class AloLibSignalPreKeyBundle(
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: ByteArray,
    val preKeyId: Int,
    val preKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray
)

data class AloLibSignalCiphertext(
    val type: Int,
    val bytes: ByteArray
)

data class AloLibSignalOneTimePreKey(
    val preKeyId: Int,
    val preKey: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray
)

data class AloLibSignalDeviceBundle(
    val registrationId: Int,
    val protocolDeviceId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val preKeys: List<AloLibSignalOneTimePreKey>
)

class AloLibSignalSessionEngine(
    private val store: SignalProtocolStore,
    private val localUserId: Int,
    val localDeviceId: Int
) {
    init {
        require(localUserId > 0)
        require(localDeviceId in 1..127)
    }

    private val localAddress: SignalProtocolAddress
        get() = protocolAddress(localUserId, localDeviceId)

    @Synchronized
    fun generatePreKeyBundle(): AloLibSignalPreKeyBundle {
        val bundle = generateDeviceBundle(1)
        val oneTime = bundle.preKeys.single()
        return AloLibSignalPreKeyBundle(
            registrationId = bundle.registrationId,
            deviceId = bundle.protocolDeviceId,
            identityKey = bundle.identityKey,
            preKeyId = oneTime.preKeyId,
            preKey = oneTime.preKey,
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKey = bundle.signedPreKey,
            signedPreKeySignature = bundle.signedPreKeySignature,
            kyberPreKeyId = oneTime.kyberPreKeyId,
            kyberPreKey = oneTime.kyberPreKey,
            kyberPreKeySignature = oneTime.kyberPreKeySignature
        )
    }

    @Synchronized
    fun generateDeviceBundle(preKeyCount: Int): AloLibSignalDeviceBundle {
        require(preKeyCount in 1..80)
        val identity = store.identityKeyPair
        val signedPreKeyId = nextKeyId()
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = identity.privateKey.calculateSignature(
            signedPreKeyPair.publicKey.serialize()
        )
        val now = System.currentTimeMillis()
        store.storeSignedPreKey(
            signedPreKeyId,
            SignedPreKeyRecord(signedPreKeyId, now, signedPreKeyPair, signedPreKeySignature)
        )

        val preKeys = buildList {
            repeat(preKeyCount) {
                val preKeyId = nextKeyId()
                val kyberPreKeyId = nextKeyId()
                val preKeyPair = ECKeyPair.generate()
                val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val kyberPreKeySignature = identity.privateKey.calculateSignature(
                    kyberPreKeyPair.publicKey.serialize()
                )
                store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
                store.storeKyberPreKey(
                    kyberPreKeyId,
                    KyberPreKeyRecord(kyberPreKeyId, now, kyberPreKeyPair, kyberPreKeySignature)
                )
                add(
                    AloLibSignalOneTimePreKey(
                        preKeyId = preKeyId,
                        preKey = preKeyPair.publicKey.serialize(),
                        kyberPreKeyId = kyberPreKeyId,
                        kyberPreKey = kyberPreKeyPair.publicKey.serialize(),
                        kyberPreKeySignature = kyberPreKeySignature
                    )
                )
            }
        }
        return AloLibSignalDeviceBundle(
            registrationId = store.localRegistrationId,
            protocolDeviceId = localDeviceId,
            identityKey = identity.publicKey.serialize(),
            signedPreKeyId = signedPreKeyId,
            signedPreKey = signedPreKeyPair.publicKey.serialize(),
            signedPreKeySignature = signedPreKeySignature,
            preKeys = preKeys
        )
    }

    @Synchronized
    fun processRemotePreKeyBundle(remoteUserId: Int, bundle: AloLibSignalPreKeyBundle) {
        require(remoteUserId > 0)
        val remoteAddress = protocolAddress(remoteUserId, bundle.deviceId)
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            bundle.preKeyId,
            ECPublicKey(bundle.preKey),
            bundle.signedPreKeyId,
            ECPublicKey(bundle.signedPreKey),
            bundle.signedPreKeySignature,
            IdentityKey(bundle.identityKey),
            bundle.kyberPreKeyId,
            KEMPublicKey(bundle.kyberPreKey),
            bundle.kyberPreKeySignature
        )
        SessionBuilder(store, remoteAddress, localAddress).process(preKeyBundle)
    }

    @Synchronized
    fun hasSession(remoteUserId: Int, remoteDeviceId: Int): Boolean =
        store.containsSession(protocolAddress(remoteUserId, remoteDeviceId))

    /**
     * True when the session at the address was made with these keys. A different identity means the server gave the
     * address to another device (a new browser in a revoked one's slot): the old session is dropped and the new
     * identity remembered, so the caller fetches the new device's bundle instead of encrypting for keys nobody holds.
     */
    @Synchronized
    fun sessionMatches(remoteUserId: Int, remoteDeviceId: Int, identityKey: ByteArray): Boolean {
        val address = protocolAddress(remoteUserId, remoteDeviceId)
        if (!store.containsSession(address)) return false
        val stored = store.getIdentity(address) ?: return true
        if (stored.serialize().contentEquals(identityKey)) return true
        store.deleteSession(address)
        runCatching { store.saveIdentity(address, org.signal.libsignal.protocol.IdentityKey(identityKey)) }
        return false
    }

    @Synchronized
    fun encrypt(remoteUserId: Int, remoteDeviceId: Int, plaintext: ByteArray): AloLibSignalCiphertext {
        val remoteAddress = protocolAddress(remoteUserId, remoteDeviceId)
        val ciphertext = SessionCipher(store, localAddress, remoteAddress).encrypt(plaintext)
        return AloLibSignalCiphertext(type = ciphertext.type, bytes = ciphertext.serialize())
    }

    @Synchronized
    fun decrypt(
        remoteUserId: Int,
        remoteDeviceId: Int,
        ciphertextType: Int,
        ciphertext: ByteArray
    ): ByteArray {
        val remoteAddress = protocolAddress(remoteUserId, remoteDeviceId)
        val cipher = SessionCipher(store, localAddress, remoteAddress)
        return when (ciphertextType) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(ciphertext))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(ciphertext))
            else -> error("Unsupported libsignal ciphertext type $ciphertextType")
        }
    }

    @Synchronized
    fun createSenderKeyDistribution(distributionId: UUID): ByteArray =
        GroupSessionBuilder(store).create(localAddress, distributionId).serialize()

    @Synchronized
    fun processSenderKeyDistribution(
        remoteUserId: Int,
        remoteDeviceId: Int,
        serialized: ByteArray
    ) {
        GroupSessionBuilder(store).process(
            protocolAddress(remoteUserId, remoteDeviceId),
            SenderKeyDistributionMessage(serialized)
        )
    }

    @Synchronized
    fun groupEncrypt(distributionId: UUID, plaintext: ByteArray): ByteArray =
        GroupCipher(store, localAddress).encrypt(distributionId, plaintext).serialize()

    @Synchronized
    fun groupDecrypt(remoteUserId: Int, remoteDeviceId: Int, ciphertext: ByteArray): ByteArray =
        GroupCipher(store, protocolAddress(remoteUserId, remoteDeviceId)).decrypt(ciphertext)

    private fun protocolAddress(userId: Int, deviceId: Int): SignalProtocolAddress {
        require(userId > 0)
        require(deviceId in 1..127)
        return SignalProtocolAddress("alo-user-$userId", deviceId)
    }

    private fun nextKeyId(): Int = SecureRandom().nextInt(0x00ff_ffff) + 1
}
