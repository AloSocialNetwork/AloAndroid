package com.alo.android

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AloLibSignalStore(
    private val prefs: SharedPreferences,
    namespace: String = "primary"
) : SignalProtocolStore {
    companion object {
        private const val ROOT_PREFIX = "alo.android.libsignal.v1"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    private val prefix = "$ROOT_PREFIX.${namespaceToken(namespace)}"
    private val identityPairKey = "$prefix.identity-pair"
    private val registrationIdKey = "$prefix.registration-id"
    private val sessionPrefix = "$prefix.session."
    private val remoteIdentityPrefix = "$prefix.remote-identity."
    private val preKeyPrefix = "$prefix.prekey."
    private val signedPreKeyPrefix = "$prefix.signed-prekey."
    private val kyberPreKeyPrefix = "$prefix.kyber-prekey."
    private val senderKeyPrefix = "$prefix.sender-key."
    private val wrapKeyAlias = "$prefix.wrap-key"
    /** Opened records by storage key, with the sealed box they came from. Guarded by the store's monitor. */
    private val openedRecords = HashMap<String, Pair<String, ByteArray>>()
    @Volatile
    private var wrappingKeyCache: SecretKey? = null

    @Synchronized
    override fun getIdentityKeyPair(): IdentityKeyPair {
        readRecord(identityPairKey)?.let { return IdentityKeyPair(it) }
        return IdentityKeyPair.generate().also { pair ->
            writeRecord(identityPairKey, pair.serialize())
        }
    }

    @Synchronized
    override fun getLocalRegistrationId(): Int {
        val stored = prefs.getInt(registrationIdKey, 0)
        if (stored in 1..16380) return stored
        return KeyHelper.generateRegistrationId(false).also { next ->
            prefs.edit().putInt(registrationIdKey, next).commit()
        }
    }

    @Synchronized
    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val storageKey = remoteIdentityStorageKey(address)
        val previous = readRecord(storageKey)
        writeRecord(storageKey, identityKey.serialize())
        return if (previous == null || previous.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    @Synchronized
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val known = readRecord(remoteIdentityStorageKey(address)) ?: return true
        return known.contentEquals(identityKey.serialize())
    }

    @Synchronized
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        readRecord(remoteIdentityStorageKey(address))?.let(::IdentityKey)

    @Synchronized
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        readRecord(preKeyStorageKey(preKeyId))
            ?.let(::PreKeyRecord)
            ?: throw InvalidKeyIdException("Missing libsignal prekey $preKeyId")

    @Synchronized
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        writeRecord(preKeyStorageKey(preKeyId), record.serialize())
    }

    @Synchronized
    override fun containsPreKey(preKeyId: Int): Boolean = containsRecord(preKeyStorageKey(preKeyId))

    @Synchronized
    override fun removePreKey(preKeyId: Int) {
        removeRecord(preKeyStorageKey(preKeyId))
    }

    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        readRecord(sessionStorageKey(address))?.let(::SessionRecord)

    @Synchronized
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.mapTo(mutableListOf()) { address ->
            loadSession(address) ?: throw NoSessionException(address, "Missing libsignal session for $address")
        }

    @Synchronized
    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        val prefix = "$sessionPrefix${addressNameToken(name)}."
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .filter { it != 1 }
            .distinct()
            .sorted()
            .toMutableList()
    }

    @Synchronized
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        writeRecord(sessionStorageKey(address), record.serialize())
    }

    @Synchronized
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        containsRecord(sessionStorageKey(address))

    @Synchronized
    override fun deleteSession(address: SignalProtocolAddress) {
        removeRecord(sessionStorageKey(address))
    }

    @Synchronized
    override fun deleteAllSessions(name: String) {
        removeRecordsWithPrefix("$sessionPrefix${addressNameToken(name)}.")
    }

    @Synchronized
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        readRecord(signedPreKeyStorageKey(signedPreKeyId))
            ?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("Missing libsignal signed prekey $signedPreKeyId")

    @Synchronized
    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
        recordsWithPrefix(signedPreKeyPrefix).mapTo(mutableListOf(), ::SignedPreKeyRecord)

    @Synchronized
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        writeRecord(signedPreKeyStorageKey(signedPreKeyId), record.serialize())
    }

    @Synchronized
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        containsRecord(signedPreKeyStorageKey(signedPreKeyId))

    @Synchronized
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        removeRecord(signedPreKeyStorageKey(signedPreKeyId))
    }

    @Synchronized
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        readRecord(kyberPreKeyStorageKey(kyberPreKeyId))
            ?.let(::KyberPreKeyRecord)
            ?: throw InvalidKeyIdException("Missing libsignal Kyber prekey $kyberPreKeyId")

    @Synchronized
    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        recordsWithPrefix(kyberPreKeyPrefix).mapTo(mutableListOf(), ::KyberPreKeyRecord)

    @Synchronized
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        writeRecord(kyberPreKeyStorageKey(kyberPreKeyId), record.serialize())
    }

    @Synchronized
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        containsRecord(kyberPreKeyStorageKey(kyberPreKeyId))

    @Synchronized
    @Throws(ReusedBaseKeyException::class)
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (!containsKyberPreKey(kyberPreKeyId)) throw ReusedBaseKeyException()
        val baseKeyDigest = MessageDigest.getInstance("SHA-256")
            .digest(baseKey.serialize())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val replayKey = "$prefix.kyber-use.$kyberPreKeyId.$signedPreKeyId.$baseKeyDigest"
        if (containsRecord(replayKey)) throw ReusedBaseKeyException()
        writeRecord(replayKey, byteArrayOf(1))
    }

    @Synchronized
    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        writeRecord(senderKeyStorageKey(sender, distributionId), record.serialize())
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        readRecord(senderKeyStorageKey(sender, distributionId))?.let(::SenderKeyRecord)

    fun identityFingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(identityKeyPair.publicKey.serialize())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .chunked(5)
        .joinToString("-")

    @Synchronized
    fun storeMessagePlaintext(contextId: String, plaintext: ByteArray) {
        require(contextId.matches(Regex("^[a-zA-Z0-9_-]{12,160}$")))
        writeRecord(messagePlaintextStorageKey(contextId), plaintext)
    }

    /** The text of a message that is gone (a secret chat's timer ran out, the chat was deleted) leaves the device with it. */
    @Synchronized
    fun forgetMessagePlaintext(contextId: String) {
        if (!contextId.matches(Regex("^[a-zA-Z0-9_-]{12,160}$"))) return
        removeRecord(messagePlaintextStorageKey(contextId))
    }

    @Synchronized
    fun loadMessagePlaintext(contextId: String): ByteArray? {
        if (!contextId.matches(Regex("^[a-zA-Z0-9_-]{12,160}$"))) return null
        return readRecord(messagePlaintextStorageKey(contextId))
    }

    private fun sessionStorageKey(address: SignalProtocolAddress): String =
        "$sessionPrefix${addressNameToken(address.name)}.${address.deviceId}"

    private fun remoteIdentityStorageKey(address: SignalProtocolAddress): String =
        "$remoteIdentityPrefix${addressNameToken(address.name)}.${address.deviceId}"

    private fun preKeyStorageKey(id: Int): String = "$preKeyPrefix$id"

    private fun signedPreKeyStorageKey(id: Int): String = "$signedPreKeyPrefix$id"

    private fun kyberPreKeyStorageKey(id: Int): String = "$kyberPreKeyPrefix$id"

    private fun senderKeyStorageKey(sender: SignalProtocolAddress, distributionId: UUID): String =
        "$senderKeyPrefix${addressNameToken(sender.name)}.${sender.deviceId}.$distributionId"

    private fun messagePlaintextStorageKey(contextId: String): String = "$prefix.plaintext.$contextId"

    private fun addressNameToken(name: String): String = Base64.encodeToString(
        name.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun containsRecord(key: String): Boolean = prefs.contains(key)

    private fun writeRecord(key: String, data: ByteArray) {
        // The same identity is saved on every message: an unchanged record is not sealed and written again.
        val current = prefs.getString(key, null)
        val opened = openedRecords[key]
        if (current != null && opened != null && opened.first == current && opened.second.contentEquals(data)) return
        val box = seal(data)
        check(prefs.edit().putString(key, box).commit()) { "Unable to persist libsignal state" }
        openedRecords[key] = box to data.copyOf()
    }

    /**
     * Records are opened once: every Keystore decryption is a round trip into the secure hardware, and a call signal
     * reads the session, identity and prekeys several times. The cache is checked against the stored box, so a record
     * changed or removed behind the store is read again.
     */
    private fun readRecord(key: String): ByteArray? {
        val box = prefs.getString(key, null) ?: run {
            openedRecords.remove(key)
            return null
        }
        openedRecords[key]?.let { (cachedBox, data) -> if (cachedBox == box) return data.copyOf() }
        val data = open(box)
        openedRecords[key] = box to data
        return data.copyOf()
    }

    private fun removeRecord(key: String) {
        prefs.edit().remove(key).commit()
        openedRecords.remove(key)
    }

    private fun recordsWithPrefix(prefix: String): List<ByteArray> = prefs.all.keys.asSequence()
        .filter { it.startsWith(prefix) }
        .sorted()
        .mapNotNull(::readRecord)
        .toList()

    private fun removeRecordsWithPrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.commit()
        openedRecords.keys.removeAll { it.startsWith(prefix) }
    }

    private fun seal(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return "${base64(cipher.iv)}.${base64(cipher.doFinal(data))}"
    }

    private fun open(box: String): ByteArray {
        val parts = box.split('.', limit = 2)
        check(parts.size == 2) { "Invalid libsignal state box" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, decode64(parts[0])))
        return cipher.doFinal(decode64(parts[1]))
    }

    private fun wrappingKey(): SecretKey {
        wrappingKeyCache?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(wrapKeyAlias, null) as? SecretKey)?.let { key ->
            wrappingKeyCache = key
            return key
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    wrapKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }.also { wrappingKeyCache = it }
    }

    private fun base64(data: ByteArray): String = Base64.encodeToString(
        data,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decode64(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun namespaceToken(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
