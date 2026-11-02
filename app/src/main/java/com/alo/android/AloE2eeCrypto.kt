package com.alo.android

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AloE2eeRecipientKey(
    val userId: Int,
    val deviceId: String,
    val publicKeyJwk: JSONObject,
    val fingerprint: String,
    val signingPublicKeyJwk: JSONObject? = null,
    val identityFingerprint: String = "",
    val oneTimePreKey: AloE2eeOneTimePreKey? = null
)

data class AloE2eeOneTimePreKey(
    val keyId: String,
    val publicKeyJwk: JSONObject,
    val signature: String,
    val signatureVersion: Int = 1,
    val algorithm: String = AloE2eeCrypto.SCHEME
)

data class AloE2eeMediaBox(
    val data: ByteArray,
    val key: String,
    val iv: String
)

class AloE2eeTrustStore(private val prefs: SharedPreferences) {
    companion object {
        private const val TRUSTED_FINGERPRINTS_KEY = "alo.android.e2ee.trusted-fingerprints.v1"
    }

    fun validate(keys: List<AloE2eeRecipientKey>, currentUserId: Int) {
        val trusted = load()
        var changed = false

        keys.forEach { key ->
            val fingerprint = key.identityFingerprint.ifBlank { key.fingerprint }
            if (key.userId == currentUserId || fingerprint.isBlank()) return@forEach
            val id = storageId(key)
            val known = trusted[id]
            if (known == null) {
                trusted[id] = fingerprint
                changed = true
            } else if (known != fingerprint) {
                throw AloApiException(
                    status = -1,
                    code = "E2EE_KEY_CHANGED",
                    message = "Ключ шифрования собеседника изменился. Сверьте код безопасности перед отправкой.",
                    payload = JSONObject()
                        .put("userId", key.userId)
                        .put("deviceId", key.deviceId)
                        .put("oldFingerprint", known)
                        .put("newFingerprint", fingerprint)
                )
            }
        }

        if (changed) {
            save(trusted)
        }
    }

    fun validateEmbeddedSender(encryptedPayload: JSONObject, currentUserId: Int) {
        if (encryptedPayload.optString("scheme") != AloE2eeCrypto.MESSAGE_SCHEME_V2) return
        val recipients = encryptedPayload.optJSONArray("recipients") ?: return
        val envelope = recipients.optJSONObject(0) ?: return
        val agreementPublicKeyJwk = envelope.optJSONObject("senderAgreementPublicKeyJwk") ?: return
        val signingPublicKeyJwk = envelope.optJSONObject("senderSigningPublicKeyJwk") ?: return
        val senderUserId = envelope.optInt("senderUserId")
        val senderDeviceId = envelope.optString("senderDeviceId")
        val senderFingerprint = envelope.optString("senderIdentityFingerprint")
        if (senderUserId <= 0 || senderDeviceId.isBlank() || senderFingerprint.isBlank()) {
            throw AloApiException(
                status = -1,
                code = "E2EE_SENDER_IDENTITY_INVALID",
                message = "Identity отправителя в зашифрованном сообщении повреждена.",
                payload = JSONObject()
            )
        }
        for (index in 0 until recipients.length()) {
            val candidate = recipients.optJSONObject(index) ?: continue
            if (
                candidate.optInt("senderUserId") != senderUserId ||
                candidate.optString("senderDeviceId") != senderDeviceId ||
                candidate.optString("senderIdentityFingerprint") != senderFingerprint ||
                !samePublicKey(candidate.optJSONObject("senderAgreementPublicKeyJwk"), agreementPublicKeyJwk) ||
                !samePublicKey(candidate.optJSONObject("senderSigningPublicKeyJwk"), signingPublicKeyJwk)
            ) {
                throw AloApiException(
                    status = -1,
                    code = "E2EE_SENDER_IDENTITY_MISMATCH",
                    message = "Конверты сообщения содержат разные identity отправителя.",
                    payload = JSONObject()
                )
            }
        }
        validate(
            listOf(
                AloE2eeRecipientKey(
                    userId = senderUserId,
                    deviceId = senderDeviceId,
                    publicKeyJwk = agreementPublicKeyJwk,
                    fingerprint = "",
                    signingPublicKeyJwk = signingPublicKeyJwk,
                    identityFingerprint = senderFingerprint
                )
            ),
            currentUserId
        )
    }

    private fun samePublicKey(left: JSONObject?, right: JSONObject?): Boolean =
        left != null && right != null &&
            left.optString("kty") == right.optString("kty") &&
            left.optString("crv") == right.optString("crv") &&
            left.optString("x") == right.optString("x") &&
            left.optString("y") == right.optString("y")

    private fun storageId(key: AloE2eeRecipientKey): String =
        "${if (key.identityFingerprint.isBlank()) "v1" else "v2"}:${key.userId}:${key.deviceId}"

    private fun load(): MutableMap<String, String> {
        val raw = prefs.getString(TRUSTED_FINGERPRINTS_KEY, "").orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val fingerprint = json.optString(id)
                if (id.isNotBlank() && fingerprint.isNotBlank()) {
                    map[id] = fingerprint
                }
            }
            map
        }.getOrElse { mutableMapOf() }
    }

    private fun save(trusted: Map<String, String>) {
        val json = JSONObject()
        trusted.forEach { (id, fingerprint) -> json.put(id, fingerprint) }
        prefs.edit().putString(TRUSTED_FINGERPRINTS_KEY, json.toString()).apply()
    }
}

class AloE2eeCrypto(private val prefs: SharedPreferences) {
    companion object {
        const val SCHEME = "ecdh-p256-aes-gcm-v1"
        const val MESSAGE_SCHEME_V2 = "alo-e2ee-p256-aes-gcm-v2"
        const val MEDIA_SCHEME = "aes-256-gcm-v1"
        const val RATCHET_SCHEME = "alo-chain-ratchet-v1"
        private const val DEVICE_ID_KEY = "alo.android.e2ee.device-id.v1"
        private const val PRIVATE_KEY_KEY = "alo.android.e2ee.private-key.v1"
        private const val PRIVATE_KEY_ENCRYPTED_KEY = "alo.android.e2ee.private-key.enc.v1"
        private const val PRIVATE_KEY_IV_KEY = "alo.android.e2ee.private-key.iv.v1"
        private const val PUBLIC_KEY_KEY = "alo.android.e2ee.public-key.v1"
        private const val SIGNING_PRIVATE_KEY_ENCRYPTED_KEY = "alo.android.e2ee.signing-private-key.enc.v2"
        private const val SIGNING_PRIVATE_KEY_IV_KEY = "alo.android.e2ee.signing-private-key.iv.v2"
        private const val SIGNING_PUBLIC_KEY_KEY = "alo.android.e2ee.signing-public-key.v2"
        private const val ONE_TIME_PREKEYS_KEY = "alo.android.e2ee.one-time-prekeys.v1"
        private const val RATCHET_SESSIONS_KEY = "alo.android.e2ee.ratchet-sessions.v1"
        private const val RATCHET_HISTORY_KEYS_KEY = "alo.android.e2ee.ratchet-history-keys.v1"
        private const val PLAINTEXT_CACHE_KEY = "alo.android.e2ee.plaintext-cache.v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "alo.android.e2ee.wrap-key.v1"
        private const val MAX_RATCHET_SKIP = 64
        private const val RATCHET_ROTATION_MESSAGES = 64
        private const val MAX_RATCHET_HISTORY_KEYS = 4000
        private const val MAX_PLAINTEXT_CACHE_ITEMS = 600
        private val SALT = "alo-e2ee-v1".toByteArray(Charsets.UTF_8)
        private val INFO = "alo-message-envelope-v1".toByteArray(Charsets.UTF_8)
        private val SALT_V2 = "alo-e2ee-v2".toByteArray(Charsets.UTF_8)
        private val INFO_V2 = "alo-message-envelope-v2".toByteArray(Charsets.UTF_8)
    }

    val deviceId: String
        get() {
            val stored = prefs.getString(DEVICE_ID_KEY, "").orEmpty()
            if (stored.matches(Regex("^[a-zA-Z0-9_-]{12,128}$"))) return stored
            val next = "android_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
            prefs.edit().putString(DEVICE_ID_KEY, next).apply()
            return next
        }

    fun localPublicKeyJwk(): JSONObject {
        val publicKey = localKeyPair().second as ECPublicKey
        return publicKeyJwk(publicKey)
    }

    fun localSigningPublicKeyJwk(): JSONObject {
        val publicKey = localSigningKeyPair().second as ECPublicKey
        return publicKeyJwk(publicKey)
    }

    fun localIdentitySignature(): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(localSigningKeyPair().first)
        signer.update(identitySigningPayload(deviceId, localPublicKeyJwk(), localSigningPublicKeyJwk()))
        return base64Url(signer.sign())
    }

    fun localIdentityFingerprint(): String = identityFingerprint(
        agreementPublicKeyJwk = localPublicKeyJwk(),
        signingPublicKeyJwk = localSigningPublicKeyJwk()
    )

    fun prepareOneTimePreKeysForUpload(
        serverAvailable: Int,
        targetCount: Int = 24,
        maxUpload: Int = 48
    ): List<AloE2eeOneTimePreKey> {
        val store = loadOneTimePreKeyStore()
        val pendingCount = countPendingOneTimePreKeys(store)
        val needed = (targetCount - serverAvailable.coerceAtLeast(0) - pendingCount)
            .coerceAtLeast(0)
            .coerceAtMost(maxUpload)
        repeat(needed) {
            val pair = generateKeyPair()
            val privateBytes = pair.first.encoded ?: return@repeat
            val sealed = encryptWithWrappingKey(privateBytes)
            val keyId = "otpk_${UUID.randomUUID().toString().replace("-", "").lowercase()}"
            val publicKeyJwk = publicKeyJwk(pair.second as ECPublicKey)
            val signature = signOneTimePreKey(keyId, SCHEME, publicKeyJwk)
            store.put(
                keyId,
                JSONObject()
                    .put("keyId", keyId)
                    .put("publicKeyJwk", publicKeyJwk)
                    .put("signature", signature)
                    .put("signatureVersion", 2)
                    .put("privateKey", sealed.first)
                    .put("iv", sealed.second)
                    .put("algorithm", SCHEME)
                    .put("createdAt", System.currentTimeMillis())
                    .put("published", false)
            )
        }
        trimOneTimePreKeyStore(store)
        saveOneTimePreKeyStore(store)
        return oneTimePreKeysPendingUpload(store).take(maxUpload)
    }

    fun markOneTimePreKeysPublished(keyIds: Collection<String>) {
        if (keyIds.isEmpty()) return
        val ids = keyIds.toSet()
        val store = loadOneTimePreKeyStore()
        var changed = false
        val keys = store.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (ids.contains(key)) {
                store.optJSONObject(key)?.put("published", true)
                changed = true
            }
        }
        if (changed) saveOneTimePreKeyStore(store)
    }

    fun encryptMedia(plaintext: ByteArray): AloE2eeMediaBox {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return AloE2eeMediaBox(
            data = cipher.doFinal(plaintext),
            key = base64Url(key),
            iv = base64Url(iv)
        )
    }

    fun decryptMedia(encrypted: ByteArray, mediaEncryption: JSONObject): ByteArray {
        if (mediaEncryption.optString("algorithm") != MEDIA_SCHEME) error("Unsupported E2EE media scheme")
        val key = base64UrlDecode(mediaEncryption.optString("key"))
        val iv = base64UrlDecode(mediaEncryption.optString("iv"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    fun encrypt(
        plaintext: ByteArray,
        recipient: AloE2eeRecipientKey,
        senderUserId: Int = 0,
        purpose: String = "message",
        contextId: String = "",
        useV2: Boolean = false,
        allowRatchetBootstrap: Boolean = true
    ): JSONObject {
        if (useV2) {
            return encryptV2(
                plaintext,
                recipient,
                senderUserId,
                purpose,
                contextId,
                allowRatchetBootstrap
            )
        }
        if (allowRatchetBootstrap) {
            loadRatchetSession(sendRatchetStorageId(recipient.userId, recipient.deviceId))?.let { session ->
                if (session.optBoolean("confirmed", false)) {
                    return encryptWithRatchetSession(plaintext, recipient, session)
                }
            }
        }

        val oneTimePreKey = (if (allowRatchetBootstrap) recipient.oneTimePreKey else null)?.also { preKey ->
            if (!verifyOneTimePreKey(recipient, preKey)) error("Invalid E2EE one-time prekey signature")
        }
        val remotePublicKey = publicKeyFromJwk(oneTimePreKey?.publicKeyJwk ?: recipient.publicKeyJwk)
        val ephemeral = generateKeyPair()
        val sharedSecret = deriveSharedSecret(ephemeral.first, remotePublicKey)
        val aesKey = hkdfSha256(sharedSecret, SALT, INFO, 32)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return JSONObject()
            .put("userId", recipient.userId)
            .put("deviceId", recipient.deviceId)
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
            .put("ephemeralPublicKeyJwk", publicKeyJwk(ephemeral.second as ECPublicKey))
            .put("algorithm", SCHEME)
            .also { envelope ->
                if (oneTimePreKey != null) {
                    val sessionId = ratchetSessionId(sharedSecret)
                    storeRatchetSession(
                        storageKey = sendRatchetStorageId(recipient.userId, recipient.deviceId),
                        sessionId = sessionId,
                        chainKey = ratchetChainKey(sharedSecret, sessionId),
                        nextMessageNumber = 0,
                        peerUserId = recipient.userId,
                        peerDeviceId = recipient.deviceId,
                        confirmed = false
                    )
                    envelope.put("ratchet", RATCHET_SCHEME)
                    envelope.put("ratchetMode", "init")
                    envelope.put("ratchetSessionId", sessionId)
                    envelope.put("ratchetMessageNumber", 0)
                    envelope.put("ratchetPublicKeyJwk", publicKeyJwk(ephemeral.second as ECPublicKey))
                }
                if (oneTimePreKey != null) {
                    envelope.put("preKeyId", oneTimePreKey.keyId)
                    envelope.put("preKeyAlgorithm", oneTimePreKey.algorithm)
                }
            }
    }

    private fun encryptV2(
        plaintext: ByteArray,
        recipient: AloE2eeRecipientKey,
        senderUserId: Int,
        purpose: String,
        contextId: String,
        allowRatchetBootstrap: Boolean
    ): JSONObject {
        require(senderUserId > 0)
        require(purpose == "message" || purpose == "call" || purpose == "draft")
        require(contextId.matches(Regex("^[a-zA-Z0-9_-]{12,160}$")))
        require(recipient.signingPublicKeyJwk != null && recipient.identityFingerprint.isNotBlank())
        if (allowRatchetBootstrap) {
            loadRatchetSession(sendRatchetStorageId(recipient.userId, recipient.deviceId, protocolVersion = 2))?.let { session ->
                if (
                    session.optBoolean("confirmed", false) &&
                    (session.optInt("nextMessageNumber", 0) < RATCHET_ROTATION_MESSAGES || recipient.oneTimePreKey == null)
                ) {
                    return encryptWithRatchetSessionV2(
                        plaintext = plaintext,
                        recipient = recipient,
                        session = session,
                        senderUserId = senderUserId,
                        purpose = purpose,
                        contextId = contextId
                    )
                }
            }
        }

        if (!allowRatchetBootstrap) {
            return encryptStaticV2(plaintext, recipient, senderUserId, purpose, contextId)
        }

        val oneTimePreKey = recipient.oneTimePreKey?.also { preKey ->
            if (!verifyOneTimePreKey(recipient, preKey)) error("Invalid E2EE one-time prekey signature")
        } ?: error("Missing E2EE v2 one-time prekey")
        val remotePublicKey = publicKeyFromJwk(oneTimePreKey.publicKeyJwk)
        val ephemeral = generateKeyPair()
        val ephemeralPublicKeyJwk = publicKeyJwk(ephemeral.second as ECPublicKey)
        val sharedSecret = deriveSharedSecret(ephemeral.first, remotePublicKey)
        val aesKey = hkdfSha256(sharedSecret, SALT_V2, INFO_V2, 32)
        val envelope = baseV2Envelope(
            recipient = recipient,
            senderUserId = senderUserId,
            purpose = purpose,
            contextId = contextId
        )
            .put("ephemeralPublicKeyJwk", ephemeralPublicKeyJwk)

        val sessionId = ratchetSessionId(sharedSecret)
        storeRatchetSession(
            storageKey = sendRatchetStorageId(recipient.userId, recipient.deviceId, protocolVersion = 2),
            sessionId = sessionId,
            chainKey = ratchetChainKey(sharedSecret, sessionId),
            nextMessageNumber = 0,
            peerUserId = recipient.userId,
            peerDeviceId = recipient.deviceId,
            confirmed = false
        )
        envelope
            .put("ratchet", RATCHET_SCHEME)
            .put("ratchetMode", "init")
            .put("ratchetSessionId", sessionId)
            .put("ratchetMessageNumber", 0)
            .put("ratchetPublicKeyJwk", ephemeralPublicKeyJwk)
            .put("preKeyId", oneTimePreKey.keyId)
            .put("preKeyAlgorithm", oneTimePreKey.algorithm)

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(envelopeAssociatedDataV2(envelope))
        val ciphertext = cipher.doFinal(plaintext)
        envelope
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
            .put("signature", signEnvelopeV2(envelope))
        return envelope
    }

    private fun encryptStaticV2(
        plaintext: ByteArray,
        recipient: AloE2eeRecipientKey,
        senderUserId: Int,
        purpose: String,
        contextId: String
    ): JSONObject {
        val remotePublicKey = publicKeyFromJwk(recipient.publicKeyJwk)
        val ephemeral = generateKeyPair()
        val sharedSecret = deriveSharedSecret(ephemeral.first, remotePublicKey)
        val aesKey = hkdfSha256(sharedSecret, SALT_V2, INFO_V2, 32)
        val envelope = baseV2Envelope(
            recipient = recipient,
            senderUserId = senderUserId,
            purpose = purpose,
            contextId = contextId
        ).put("ephemeralPublicKeyJwk", publicKeyJwk(ephemeral.second as ECPublicKey))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(envelopeAssociatedDataV2(envelope))
        val ciphertext = cipher.doFinal(plaintext)
        envelope
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
        envelope.put("signature", signEnvelopeV2(envelope))
        return envelope
    }

    private fun baseV2Envelope(
        recipient: AloE2eeRecipientKey,
        senderUserId: Int,
        purpose: String,
        contextId: String
    ): JSONObject = JSONObject()
        .put("userId", recipient.userId)
        .put("deviceId", recipient.deviceId)
        .put("algorithm", SCHEME)
        .put("protocolVersion", 2)
        .put("purpose", purpose)
        .put("contextId", contextId)
        .put("senderUserId", senderUserId)
        .put("senderDeviceId", deviceId)
        .put("senderAgreementPublicKeyJwk", localPublicKeyJwk())
        .put("senderSigningPublicKeyJwk", localSigningPublicKeyJwk())
        .put("senderIdentityFingerprint", localIdentityFingerprint())

    @Synchronized
    fun decrypt(encryptedPayload: JSONObject): ByteArray {
        val messageScheme = encryptedPayload.optString("scheme")
        if (messageScheme != SCHEME && messageScheme != MESSAGE_SCHEME_V2) error("Unsupported E2EE scheme")
        val recipients = encryptedPayload.optJSONArray("recipients") ?: error("Missing E2EE recipients")
        var envelope: JSONObject? = null
        for (index in 0 until recipients.length()) {
            val item = recipients.optJSONObject(index) ?: continue
            if (item.optString("deviceId") == deviceId) {
                envelope = item
                break
            }
        }
        val selected = envelope ?: error("Missing E2EE envelope for this device")
        if (messageScheme == MESSAGE_SCHEME_V2) {
            verifyEnvelopeV2(selected)
        }
        loadCachedPlaintext(messageScheme, selected)?.let { return it }
        if (selected.optString("ratchet") == RATCHET_SCHEME && selected.optString("ratchetMode") == "chain") {
            val opened = decryptWithRatchetSession(selected)
            if (storeCachedPlaintext(messageScheme, selected, opened)) {
                retireConsumedEnvelopeKeys(selected)
            }
            return opened
        }

        val preKeyId = selected.optString("preKeyId").trim()
        val privateKey = if (preKeyId.isNotBlank()) {
            loadOneTimePreKeyPrivateKey(preKeyId) ?: error("Missing E2EE one-time prekey")
        } else {
            localKeyPair().first
        }
        val ephemeralPublicKey = publicKeyFromJwk(selected.optJSONObject("ephemeralPublicKeyJwk") ?: JSONObject())
        val sharedSecret = deriveSharedSecret(privateKey, ephemeralPublicKey)
        val aesKey = if (messageScheme == MESSAGE_SCHEME_V2) {
            hkdfSha256(sharedSecret, SALT_V2, INFO_V2, 32)
        } else {
            hkdfSha256(sharedSecret, SALT, INFO, 32)
        }
        val iv = base64UrlDecode(selected.optString("iv"))
        val ciphertext = base64UrlDecode(selected.optString("ciphertext"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        if (messageScheme == MESSAGE_SCHEME_V2) {
            cipher.updateAAD(envelopeAssociatedDataV2(selected))
        }
        val opened = cipher.doFinal(ciphertext)
        if (selected.optString("ratchet") == RATCHET_SCHEME && selected.optString("ratchetMode") == "init") {
            val sessionId = selected.optString("ratchetSessionId").trim()
            if (sessionId.isNotBlank()) {
                storeRatchetSession(
                    storageKey = receiveRatchetStorageId(sessionId),
                    sessionId = sessionId,
                    chainKey = ratchetChainKey(sharedSecret, sessionId),
                    nextMessageNumber = 0,
                    peerUserId = if (messageScheme == MESSAGE_SCHEME_V2) {
                        selected.optInt("senderUserId")
                    } else {
                        selected.optInt("userId")
                    },
                    peerDeviceId = if (messageScheme == MESSAGE_SCHEME_V2) {
                        selected.optString("senderDeviceId")
                    } else {
                        selected.optString("deviceId")
                    }
                )
            }
        }
        if (storeCachedPlaintext(messageScheme, selected, opened)) {
            retireConsumedEnvelopeKeys(selected)
        }
        return opened
    }

    @Synchronized
    fun markRatchetSessionsConfirmed(encryptedPayload: JSONObject) {
        val recipients = encryptedPayload.optJSONArray("recipients") ?: return
        val store = loadRatchetStore()
        var changed = false
        for (index in 0 until recipients.length()) {
            val envelope = recipients.optJSONObject(index) ?: continue
            if (envelope.optString("ratchet") != RATCHET_SCHEME || envelope.optString("ratchetMode") != "init") continue
            val userId = envelope.optInt("userId", 0)
            val deviceId = envelope.optString("deviceId").trim()
            val sessionId = envelope.optString("ratchetSessionId").trim()
            if (userId <= 0 || deviceId.isBlank() || sessionId.isBlank()) continue
            val protocolVersion = if (envelope.optInt("protocolVersion") == 2) 2 else 1
            val session = store.optJSONObject(sendRatchetStorageId(userId, deviceId, protocolVersion)) ?: continue
            if (session.optString("sessionId") != sessionId) continue
            session.put("confirmed", true)
            session.put("updatedAt", System.currentTimeMillis())
            changed = true
        }
        if (changed) saveRatchetStore(store)
    }

    @Synchronized
    fun hasConfirmedRatchetSession(userId: Int, deviceId: String, useV2: Boolean = false): Boolean =
        loadRatchetSession(
            sendRatchetStorageId(userId, deviceId, protocolVersion = if (useV2) 2 else 1)
        )?.let { session ->
            session.optBoolean("confirmed", false) &&
                (!useV2 || session.optInt("nextMessageNumber", 0) < RATCHET_ROTATION_MESSAGES)
        } == true

    private fun localKeyPair(): Pair<PrivateKey, PublicKey> {
        val storedPublic = prefs.getString(PUBLIC_KEY_KEY, "").orEmpty()
        val factory = KeyFactory.getInstance("EC")
        val encryptedPrivate = loadEncryptedPrivateKey()
        if (encryptedPrivate != null && storedPublic.isNotBlank()) {
            runCatching {
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(encryptedPrivate))
                val publicKey = factory.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP)))
                return privateKey to publicKey
            }
        }
        val legacyStored = prefs.getString(PRIVATE_KEY_KEY, "").orEmpty()
        if (legacyStored.isNotBlank() && storedPublic.isNotBlank()) {
            runCatching {
                val privateBytes = Base64.decode(legacyStored, Base64.NO_WRAP)
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                val publicKey = factory.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP)))
                storeEncryptedPrivateKey(privateBytes)
                prefs.edit().remove(PRIVATE_KEY_KEY).apply()
                return privateKey to publicKey
            }
        }
        val generated = generateKeyPair()
        prefs.edit()
            .putString(PUBLIC_KEY_KEY, Base64.encodeToString(generated.second.encoded, Base64.NO_WRAP))
            .apply()
        storeEncryptedPrivateKey(generated.first.encoded ?: error("Missing E2EE private key bytes"))
        return generated
    }

    private fun localSigningKeyPair(): Pair<PrivateKey, PublicKey> {
        val storedPublic = prefs.getString(SIGNING_PUBLIC_KEY_KEY, "").orEmpty()
        val encryptedPrivate = prefs.getString(SIGNING_PRIVATE_KEY_ENCRYPTED_KEY, "").orEmpty()
        val iv = prefs.getString(SIGNING_PRIVATE_KEY_IV_KEY, "").orEmpty()
        val factory = KeyFactory.getInstance("EC")
        if (storedPublic.isNotBlank() && encryptedPrivate.isNotBlank() && iv.isNotBlank()) {
            runCatching {
                val privateBytes = decryptWithWrappingKey(encryptedPrivate, iv)
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                val publicKey = factory.generatePublic(
                    java.security.spec.X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP))
                )
                return privateKey to publicKey
            }
        }
        val generated = generateKeyPair()
        val sealed = encryptWithWrappingKey(generated.first.encoded ?: error("Missing E2EE signing key bytes"))
        prefs.edit()
            .putString(SIGNING_PRIVATE_KEY_ENCRYPTED_KEY, sealed.first)
            .putString(SIGNING_PRIVATE_KEY_IV_KEY, sealed.second)
            .putString(SIGNING_PUBLIC_KEY_KEY, Base64.encodeToString(generated.second.encoded, Base64.NO_WRAP))
            .apply()
        return generated
    }

    private fun loadEncryptedPrivateKey(): ByteArray? {
        val encrypted = prefs.getString(PRIVATE_KEY_ENCRYPTED_KEY, "").orEmpty()
        val iv = prefs.getString(PRIVATE_KEY_IV_KEY, "").orEmpty()
        if (encrypted.isBlank() || iv.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateWrappingKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        }.getOrNull()
    }

    private fun storeEncryptedPrivateKey(privateKeyBytes: ByteArray) {
        val encrypted = encryptWithWrappingKey(privateKeyBytes)
        prefs.edit()
            .putString(PRIVATE_KEY_ENCRYPTED_KEY, encrypted.first)
            .putString(PRIVATE_KEY_IV_KEY, encrypted.second)
            .remove(PRIVATE_KEY_KEY)
            .apply()
    }

    private fun encryptWithWrappingKey(bytes: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val encrypted = cipher.doFinal(bytes)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decryptWithWrappingKey(encrypted: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
    }

    private fun loadOneTimePreKeyStore(): JSONObject {
        val raw = prefs.getString(ONE_TIME_PREKEYS_KEY, "").orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun saveOneTimePreKeyStore(store: JSONObject) {
        prefs.edit().putString(ONE_TIME_PREKEYS_KEY, store.toString()).apply()
    }

    private fun oneTimePreKeysPendingUpload(store: JSONObject): List<AloE2eeOneTimePreKey> {
        val items = mutableListOf<AloE2eeOneTimePreKey>()
        val keys = store.keys()
        while (keys.hasNext()) {
            val storageKey = keys.next()
            val json = store.optJSONObject(storageKey) ?: continue
            if (json.optBoolean("published")) continue
            val keyId = json.optString("keyId")
            val publicKeyJwk = json.optJSONObject("publicKeyJwk") ?: continue
            if (keyId.isBlank()) continue
            items += AloE2eeOneTimePreKey(
                keyId = keyId,
                publicKeyJwk = publicKeyJwk,
                signature = json.optString("signature"),
                signatureVersion = json.optInt("signatureVersion", 1),
                algorithm = json.optString("algorithm").ifBlank { SCHEME }
            )
        }
        return items
    }

    private fun countPendingOneTimePreKeys(store: JSONObject): Int {
        var count = 0
        val keys = store.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val json = store.optJSONObject(key) ?: continue
            if (!json.optBoolean("published")) count += 1
        }
        return count
    }

    private fun loadOneTimePreKeyPrivateKey(keyId: String): PrivateKey? {
        val store = loadOneTimePreKeyStore()
        val json = store.optJSONObject(keyId) ?: return null
        return runCatching {
            val privateBytes = decryptWithWrappingKey(
                encrypted = json.optString("privateKey"),
                iv = json.optString("iv")
            )
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        }.getOrNull()
    }

    private fun markOneTimePreKeyConsumed(keyId: String) {
        val store = loadOneTimePreKeyStore()
        val json = store.optJSONObject(keyId) ?: return
        json.put("consumedAt", System.currentTimeMillis())
        json.remove("privateKey")
        json.remove("iv")
        saveOneTimePreKeyStore(store)
    }

    private fun trimOneTimePreKeyStore(store: JSONObject, maxPending: Int = 120) {
        val keys = mutableListOf<String>()
        val iterator = store.keys()
        while (iterator.hasNext()) keys += iterator.next()
        val pending = keys
            .mapNotNull { key ->
                val json = store.optJSONObject(key) ?: return@mapNotNull null
                if (json.optBoolean("published")) return@mapNotNull null
                key to json.optLong("createdAt", 0L)
            }
            .sortedBy { it.second }
        if (pending.size <= maxPending) return
        pending.take(pending.size - maxPending).forEach { store.remove(it.first) }
    }

    private fun encryptWithRatchetSession(
        plaintext: ByteArray,
        recipient: AloE2eeRecipientKey,
        session: JSONObject
    ): JSONObject {
        val sessionId = session.optString("sessionId").trim()
        val messageNumber = session.optInt("nextMessageNumber", 0).coerceAtLeast(0)
        val chainKey = ratchetChainKeyFromSession(session)
        val messageKey = ratchetMessageKey(chainKey)
        val nextChainKey = ratchetNextChainKey(chainKey)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        storeRatchetSession(
            storageKey = sendRatchetStorageId(recipient.userId, recipient.deviceId),
            sessionId = sessionId,
            chainKey = nextChainKey,
            nextMessageNumber = messageNumber + 1,
            peerUserId = recipient.userId,
            peerDeviceId = recipient.deviceId
        )
        return JSONObject()
            .put("userId", recipient.userId)
            .put("deviceId", recipient.deviceId)
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
            .put("algorithm", SCHEME)
            .put("ratchet", RATCHET_SCHEME)
            .put("ratchetMode", "chain")
            .put("ratchetSessionId", sessionId)
            .put("ratchetMessageNumber", messageNumber)
    }

    private fun encryptWithRatchetSessionV2(
        plaintext: ByteArray,
        recipient: AloE2eeRecipientKey,
        session: JSONObject,
        senderUserId: Int,
        purpose: String,
        contextId: String
    ): JSONObject {
        val sessionId = session.optString("sessionId").trim()
        val messageNumber = session.optInt("nextMessageNumber", 0).coerceAtLeast(0)
        val chainKey = ratchetChainKeyFromSession(session)
        val messageKey = ratchetMessageKey(chainKey)
        val nextChainKey = ratchetNextChainKey(chainKey)
        val envelope = baseV2Envelope(recipient, senderUserId, purpose, contextId)
            .put("ratchet", RATCHET_SCHEME)
            .put("ratchetMode", "chain")
            .put("ratchetSessionId", sessionId)
            .put("ratchetMessageNumber", messageNumber)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(envelopeAssociatedDataV2(envelope))
        val ciphertext = cipher.doFinal(plaintext)
        envelope
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
            .put("signature", signEnvelopeV2(envelope))
        storeRatchetSession(
            storageKey = sendRatchetStorageId(recipient.userId, recipient.deviceId, protocolVersion = 2),
            sessionId = sessionId,
            chainKey = nextChainKey,
            nextMessageNumber = messageNumber + 1,
            peerUserId = recipient.userId,
            peerDeviceId = recipient.deviceId
        )
        return envelope
    }

    private fun decryptWithRatchetSession(envelope: JSONObject): ByteArray {
        val sessionId = envelope.optString("ratchetSessionId").trim()
        if (sessionId.isBlank()) error("Missing E2EE ratchet session")
        val storageKey = receiveRatchetStorageId(sessionId)
        val session = loadRatchetSession(storageKey) ?: error("Missing E2EE ratchet state")
        val targetMessageNumber = envelope.optInt("ratchetMessageNumber", -1)
        if (targetMessageNumber < 0) error("Invalid E2EE ratchet counter")
        val currentMessageNumber = session.optInt("nextMessageNumber", 0).coerceAtLeast(0)
        if (targetMessageNumber < currentMessageNumber) {
            val retainedKey = loadRatchetHistoryMessageKey(sessionId, targetMessageNumber)
                ?: error("E2EE ratchet message key is no longer available")
            return decryptRatchetEnvelope(envelope, retainedKey)
        }
        if (targetMessageNumber - currentMessageNumber > MAX_RATCHET_SKIP) error("E2EE ratchet gap is too large")

        var chainKey = ratchetChainKeyFromSession(session)
        var nextNumber = currentMessageNumber
        while (nextNumber < targetMessageNumber) {
            storeRatchetHistoryMessageKey(sessionId, nextNumber, ratchetMessageKey(chainKey))
            chainKey = ratchetNextChainKey(chainKey)
            nextNumber += 1
        }
        val messageKey = ratchetMessageKey(chainKey)
        val nextChainKey = ratchetNextChainKey(chainKey)
        return decryptRatchetEnvelope(envelope, messageKey).also {
            storeRatchetSession(
                storageKey = storageKey,
                sessionId = sessionId,
                chainKey = nextChainKey,
                nextMessageNumber = targetMessageNumber + 1,
                peerUserId = session.optInt("peerUserId"),
                peerDeviceId = session.optString("peerDeviceId")
            )
        }
    }

    private fun decryptRatchetEnvelope(envelope: JSONObject, messageKey: ByteArray): ByteArray {
        val iv = base64UrlDecode(envelope.optString("iv"))
        val ciphertext = base64UrlDecode(envelope.optString("ciphertext"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, iv))
        if (envelope.optInt("protocolVersion") == 2) {
            cipher.updateAAD(envelopeAssociatedDataV2(envelope))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun loadRatchetSession(storageKey: String): JSONObject? {
        val store = loadRatchetStore()
        return store.optJSONObject(storageKey)
    }

    private fun storeRatchetSession(
        storageKey: String,
        sessionId: String,
        chainKey: ByteArray,
        nextMessageNumber: Int,
        peerUserId: Int,
        peerDeviceId: String,
        confirmed: Boolean = true
    ) {
        if (storageKey.isBlank() || sessionId.isBlank()) return
        val sealed = encryptWithWrappingKey(chainKey)
        val store = loadRatchetStore()
        store.put(
            storageKey,
            JSONObject()
                .put("sessionId", sessionId)
                .put("peerUserId", peerUserId)
                .put("peerDeviceId", peerDeviceId)
                .put("confirmed", confirmed)
                .put("chainKey", sealed.first)
                .put("iv", sealed.second)
                .put("nextMessageNumber", nextMessageNumber.coerceAtLeast(0))
                .put("updatedAt", System.currentTimeMillis())
        )
        saveRatchetStore(store)
    }

    private fun loadRatchetStore(): JSONObject {
        val raw = prefs.getString(RATCHET_SESSIONS_KEY, "").orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun saveRatchetStore(store: JSONObject) {
        prefs.edit().putString(RATCHET_SESSIONS_KEY, store.toString()).apply()
    }

    private fun loadRatchetHistoryMessageKey(sessionId: String, messageNumber: Int): ByteArray? {
        val item = loadRatchetHistoryStore().optJSONObject(ratchetHistoryStorageId(sessionId, messageNumber)) ?: return null
        return runCatching {
            decryptWithWrappingKey(item.optString("key"), item.optString("iv"))
        }.getOrNull()
    }

    private fun storeRatchetHistoryMessageKey(sessionId: String, messageNumber: Int, messageKey: ByteArray) {
        if (sessionId.isBlank() || messageNumber < 0 || messageKey.size != 32) return
        val sealed = encryptWithWrappingKey(messageKey)
        val store = loadRatchetHistoryStore()
        store.put(
            ratchetHistoryStorageId(sessionId, messageNumber),
            JSONObject()
                .put("key", sealed.first)
                .put("iv", sealed.second)
                .put("createdAt", System.currentTimeMillis())
        )
        trimRatchetHistoryStore(store)
        prefs.edit().putString(RATCHET_HISTORY_KEYS_KEY, store.toString()).apply()
    }

    private fun loadRatchetHistoryStore(): JSONObject {
        val raw = prefs.getString(RATCHET_HISTORY_KEYS_KEY, "").orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun removeRatchetHistoryMessageKey(sessionId: String, messageNumber: Int) {
        if (sessionId.isBlank() || messageNumber < 0) return
        val store = loadRatchetHistoryStore()
        store.remove(ratchetHistoryStorageId(sessionId, messageNumber))
        prefs.edit().putString(RATCHET_HISTORY_KEYS_KEY, store.toString()).apply()
    }

    private fun loadCachedPlaintext(messageScheme: String, envelope: JSONObject): ByteArray? {
        val item = loadPlaintextCacheStore().optJSONObject(plaintextCacheId(messageScheme, envelope)) ?: return null
        return runCatching {
            decryptWithWrappingKey(item.optString("box"), item.optString("iv"))
        }.getOrNull()
    }

    private fun storeCachedPlaintext(messageScheme: String, envelope: JSONObject, plaintext: ByteArray): Boolean {
        return runCatching {
            val sealed = encryptWithWrappingKey(plaintext)
            val store = loadPlaintextCacheStore()
            store.put(
                plaintextCacheId(messageScheme, envelope),
                JSONObject()
                    .put("box", sealed.first)
                    .put("iv", sealed.second)
                    .put("createdAt", System.currentTimeMillis())
            )
            trimPlaintextCacheStore(store)
            prefs.edit().putString(PLAINTEXT_CACHE_KEY, store.toString()).commit()
        }.getOrDefault(false)
    }

    private fun loadPlaintextCacheStore(): JSONObject {
        val raw = prefs.getString(PLAINTEXT_CACHE_KEY, "").orEmpty()
        return if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun trimPlaintextCacheStore(store: JSONObject) {
        if (store.length() <= MAX_PLAINTEXT_CACHE_ITEMS) return
        val items = mutableListOf<Pair<String, Long>>()
        val keys = store.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            items += key to (store.optJSONObject(key)?.optLong("createdAt", 0L) ?: 0L)
        }
        items.sortedBy { it.second }
            .take(store.length() - MAX_PLAINTEXT_CACHE_ITEMS)
            .forEach { store.remove(it.first) }
    }

    private fun plaintextCacheId(messageScheme: String, envelope: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$messageScheme|${envelope.optString("deviceId")}|${envelope.optString("iv")}|${envelope.optString("ciphertext")}|${envelope.optString("signature")}".toByteArray(
                Charsets.UTF_8
            )
        )
        return base64Url(digest)
    }

    private fun retireConsumedEnvelopeKeys(envelope: JSONObject) {
        val preKeyId = envelope.optString("preKeyId").trim()
        if (preKeyId.isNotBlank()) markOneTimePreKeyConsumed(preKeyId)
        if (envelope.optString("ratchet") == RATCHET_SCHEME && envelope.optString("ratchetMode") == "chain") {
            removeRatchetHistoryMessageKey(
                envelope.optString("ratchetSessionId").trim(),
                envelope.optInt("ratchetMessageNumber", -1)
            )
        }
    }

    private fun trimRatchetHistoryStore(store: JSONObject) {
        if (store.length() <= MAX_RATCHET_HISTORY_KEYS) return
        val items = mutableListOf<Pair<String, Long>>()
        val keys = store.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            items += key to (store.optJSONObject(key)?.optLong("createdAt", 0L) ?: 0L)
        }
        items.sortedBy { it.second }
            .take(store.length() - MAX_RATCHET_HISTORY_KEYS)
            .forEach { store.remove(it.first) }
    }

    private fun ratchetHistoryStorageId(sessionId: String, messageNumber: Int): String =
        "$sessionId:$messageNumber"

    private fun ratchetChainKeyFromSession(session: JSONObject): ByteArray =
        decryptWithWrappingKey(
            encrypted = session.optString("chainKey"),
            iv = session.optString("iv")
        )

    private fun sendRatchetStorageId(userId: Int, deviceId: String, protocolVersion: Int = 1): String =
        "send:v${if (protocolVersion == 2) 2 else 1}:$userId:$deviceId"

    private fun receiveRatchetStorageId(sessionId: String): String = "recv:$sessionId"

    private fun ratchetSessionId(sharedSecret: ByteArray): String =
        base64Url(hmacSha256(sharedSecret, "session".toByteArray(Charsets.UTF_8)))

    private fun ratchetChainKey(sharedSecret: ByteArray, sessionId: String): ByteArray =
        hkdfSha256(sharedSecret, SALT, "$RATCHET_SCHEME|$sessionId".toByteArray(Charsets.UTF_8), 32)

    private fun ratchetMessageKey(chainKey: ByteArray): ByteArray =
        hmacSha256(chainKey, "message-key".toByteArray(Charsets.UTF_8))

    private fun ratchetNextChainKey(chainKey: ByteArray): ByteArray =
        hmacSha256(chainKey, "chain-key".toByteArray(Charsets.UTF_8))

    private fun signOneTimePreKey(keyId: String, algorithm: String, publicKeyJwk: JSONObject): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(localSigningKeyPair().first)
        signer.update(oneTimePreKeySigningPayload(deviceId, keyId, algorithm, publicKeyJwk))
        return base64Url(signer.sign())
    }

    private fun verifyOneTimePreKey(recipient: AloE2eeRecipientKey, preKey: AloE2eeOneTimePreKey): Boolean =
        runCatching {
            val verifier = Signature.getInstance("SHA256withECDSA")
            val signerKey = if (preKey.signatureVersion == 2) {
                recipient.signingPublicKeyJwk ?: return false
            } else {
                recipient.publicKeyJwk
            }
            verifier.initVerify(publicKeyFromJwk(signerKey))
            verifier.update(oneTimePreKeySigningPayload(recipient.deviceId, preKey.keyId, preKey.algorithm, preKey.publicKeyJwk))
            verifier.verify(base64UrlDecode(preKey.signature))
        }.getOrDefault(false)

    private fun oneTimePreKeySigningPayload(
        signedDeviceId: String,
        keyId: String,
        algorithm: String,
        publicKeyJwk: JSONObject
    ): ByteArray =
        "alo-e2ee-prekey-v1|$signedDeviceId|$keyId|$algorithm|${publicKeyJwk.optString("x")}|${publicKeyJwk.optString("y")}".toByteArray(Charsets.UTF_8)

    private fun identitySigningPayload(
        signedDeviceId: String,
        agreementPublicKeyJwk: JSONObject,
        signingPublicKeyJwk: JSONObject
    ): ByteArray =
        "alo-e2ee-identity-v2|$signedDeviceId|${agreementPublicKeyJwk.optString("x")}|${agreementPublicKeyJwk.optString("y")}|${signingPublicKeyJwk.optString("x")}|${signingPublicKeyJwk.optString("y")}".toByteArray(
            Charsets.UTF_8
        )

    private fun identityFingerprint(
        agreementPublicKeyJwk: JSONObject,
        signingPublicKeyJwk: JSONObject
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            identitySigningPayload("", agreementPublicKeyJwk, signingPublicKeyJwk)
        )
        return digest.take(20).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .chunked(5)
            .joinToString("-")
    }

    private fun envelopeAssociatedDataV2(envelope: JSONObject): ByteArray {
        val ephemeral = envelope.optJSONObject("ephemeralPublicKeyJwk") ?: JSONObject()
        val ratchetPublic = envelope.optJSONObject("ratchetPublicKeyJwk") ?: JSONObject()
        val senderAgreement = envelope.optJSONObject("senderAgreementPublicKeyJwk") ?: JSONObject()
        val senderSigning = envelope.optJSONObject("senderSigningPublicKeyJwk") ?: JSONObject()
        val fields = listOf(
            "alo-e2ee-envelope-v2",
            MESSAGE_SCHEME_V2,
            envelope.optString("purpose"),
            envelope.optString("contextId"),
            envelope.optInt("senderUserId").toString(),
            envelope.optString("senderDeviceId"),
            envelope.optInt("userId").toString(),
            envelope.optString("deviceId"),
            envelope.optString("algorithm"),
            envelope.optString("ratchet"),
            envelope.optString("ratchetMode"),
            envelope.optString("ratchetSessionId"),
            envelope.optInt("ratchetMessageNumber", -1).toString(),
            envelope.optString("preKeyId"),
            envelope.optString("preKeyAlgorithm"),
            ephemeral.optString("x"),
            ephemeral.optString("y"),
            ratchetPublic.optString("x"),
            ratchetPublic.optString("y"),
            senderAgreement.optString("x"),
            senderAgreement.optString("y"),
            senderSigning.optString("x"),
            senderSigning.optString("y"),
            envelope.optString("senderIdentityFingerprint")
        )
        return fields.joinToString("|") { value ->
            base64Url(value.toByteArray(Charsets.UTF_8))
        }.toByteArray(Charsets.UTF_8)
    }

    private fun envelopeSignaturePayloadV2(envelope: JSONObject): ByteArray =
        envelopeAssociatedDataV2(envelope) +
            ".${envelope.optString("iv")}.${envelope.optString("ciphertext")}".toByteArray(Charsets.UTF_8)

    private fun signEnvelopeV2(envelope: JSONObject): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(localSigningKeyPair().first)
        signer.update(envelopeSignaturePayloadV2(envelope))
        return base64Url(signer.sign())
    }

    private fun verifyEnvelopeV2(envelope: JSONObject) {
        if (envelope.optInt("protocolVersion") != 2) error("Invalid E2EE v2 envelope")
        if (envelope.optString("purpose") !in setOf("message", "call", "draft")) {
            error("Invalid E2EE v2 purpose")
        }
        val agreementPublicKeyJwk = envelope.optJSONObject("senderAgreementPublicKeyJwk")
            ?: error("Missing E2EE sender agreement identity")
        val signingPublicKeyJwk = envelope.optJSONObject("senderSigningPublicKeyJwk")
            ?: error("Missing E2EE sender signing identity")
        val expectedFingerprint = identityFingerprint(agreementPublicKeyJwk, signingPublicKeyJwk)
        if (expectedFingerprint != envelope.optString("senderIdentityFingerprint")) {
            error("Invalid E2EE sender identity fingerprint")
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKeyFromJwk(signingPublicKeyJwk))
        verifier.update(envelopeSignaturePayloadV2(envelope))
        if (!verifier.verify(base64UrlDecode(envelope.optString("signature")))) {
            error("Invalid E2EE sender signature")
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun generateKeyPair(): Pair<PrivateKey, PublicKey> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        return pair.private to pair.public
    }

    private fun publicKeyJwk(publicKey: ECPublicKey): JSONObject {
        val point = publicKey.w
        return JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", base64Url(point.affineX.toUnsignedFixed(32)))
            .put("y", base64Url(point.affineY.toUnsignedFixed(32)))
            .put("ext", true)
    }

    private fun publicKeyFromJwk(jwk: JSONObject): PublicKey {
        val x = BigInteger(1, base64UrlDecode(jwk.optString("x")))
        val y = BigInteger(1, base64UrlDecode(jwk.optString("y")))
        val spec = ecParameterSpec()
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
    }

    private fun ecParameterSpec(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(input)
        val result = ByteArray(outputLength)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < outputLength) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copyLength = minOf(previous.size, outputLength - generated)
            previous.copyInto(result, destinationOffset = generated, endIndex = copyLength)
            generated += copyLength
            counter += 1
        }
        return result
    }

    private fun BigInteger.toUnsignedFixed(length: Int): ByteArray {
        val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        if (raw.size == length) return raw
        return ByteArray(length).also { target ->
            raw.copyInto(target, destinationOffset = (length - raw.size).coerceAtLeast(0), startIndex = raw.size.minus(length).coerceAtLeast(0))
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
}
