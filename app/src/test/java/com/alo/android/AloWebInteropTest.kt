package com.alo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Interoperability of the app's real libsignal code (AloLibSignalSessionEngine, libsignal 0.97.1 for Android) with the
 * browser build (the same Rust core compiled to WebAssembly). The browser side is a Node script,
 * ~/alo/tools/native-interop/orchestrator.cjs, that exchanges JSON files with this test through ALO_INTEROP_DIR:
 *
 *   node ~/alo/tools/native-interop/orchestrator.cjs /tmp/alo-interop android &
 *   ALO_INTEROP_DIR=/tmp/alo-interop ./gradlew --offline :app:testDebugUnitTest --tests '*AloWebInteropTest'
 */
class AloWebInteropTest {
    private fun write(directory: File, name: String, value: JSONObject) {
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(value.toString())
        temporary.renameTo(File(directory, name))
    }

    private fun read(directory: File, name: String, timeoutMs: Long = 900_000): JSONObject {
        val file = File(directory, name)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) {
                runCatching { return JSONObject(file.readText()) }
            }
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $name")
    }

    // The server stores base64url without padding; the app reads both alphabets.
    private fun bytes(value: String): ByteArray {
        var normalized = value.replace('-', '+').replace('_', '/')
        if (normalized.length % 4 != 0) normalized += "=".repeat(4 - normalized.length % 4)
        return Base64.getDecoder().decode(normalized)
    }

    private fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    @Test
    fun appEngineInteroperatesWithTheBrowserBuild() {
        val path = System.getenv("ALO_INTEROP_DIR")
        assumeTrue("ALO_INTEROP_DIR is not set: run through tools/native-interop/orchestrator.cjs", !path.isNullOrBlank())
        val directory = File(path!!).also { it.mkdirs() }

        val store = InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))
        val engine = AloLibSignalSessionEngine(store, localUserId = 2, localDeviceId = 1)
        val webUserId = 1
        val webDeviceId = 1

        // 1. The app registers a device the way registerLibSignalDeviceIfPossible does.
        val bundle = engine.generateDeviceBundle(3)
        write(directory, "android-bundle.json", JSONObject().apply {
            put("registrationId", bundle.registrationId)
            put("protocolDeviceId", bundle.protocolDeviceId)
            put("identityKey", b64(bundle.identityKey))
            put("signedPreKeyId", bundle.signedPreKeyId)
            put("signedPreKey", b64(bundle.signedPreKey))
            put("signedPreKeySignature", b64(bundle.signedPreKeySignature))
            put("preKeys", JSONArray(bundle.preKeys.map { key ->
                JSONObject().apply {
                    put("preKeyId", key.preKeyId)
                    put("preKey", b64(key.preKey))
                    put("kyberPreKeyId", key.kyberPreKeyId)
                    put("kyberPreKey", b64(key.kyberPreKey))
                    put("kyberPreKeySignature", b64(key.kyberPreKeySignature))
                }
            }))
        })

        // 2. The browser starts a session with that bundle (PQXDH + Kyber1024) and sends messages, some out of order.
        val inbound = read(directory, "web-inbound.json").getJSONArray("messages")
        assertTrue(inbound.length() >= 4)
        assertEquals(CiphertextMessage.PREKEY_TYPE, inbound.getJSONObject(0).getInt("type"))
        for (index in 0 until inbound.length()) {
            val message = inbound.getJSONObject(index)
            val opened = engine.decrypt(webUserId, webDeviceId, message.getInt("type"), bytes(message.getString("ciphertext")))
            assertEquals("browser message $index", message.getString("plaintext"), String(opened, Charsets.UTF_8))
        }

        // 3. The app answers (Double Ratchet) and also shares a sender key for a group.
        val reply = engine.encrypt(webUserId, webDeviceId, "ответ из Android".toByteArray())
        val distributionId = UUID.randomUUID()
        val distribution = engine.createSenderKeyDistribution(distributionId)
        val groupCiphertext = engine.groupEncrypt(distributionId, "группа из Android".toByteArray())
        write(directory, "android-reply.json", JSONObject().apply {
            put("type", reply.type)
            put("ciphertext", b64(reply.bytes))
            put("plaintext", "ответ из Android")
            put("group", JSONObject().apply {
                put("distributionId", distributionId.toString().lowercase())
                put("distribution", b64(distribution))
                put("ciphertext", b64(groupCiphertext))
                put("plaintext", "группа из Android")
            })
        })

        // 4. The app starts a session with the browser's bundle exactly as the server returns it (base64url, no padding).
        val web = read(directory, "web-bundle.json")
        val remote = AloLibSignalPreKeyBundle(
            registrationId = web.getInt("registrationId"),
            deviceId = web.getInt("deviceId"),
            identityKey = bytes(web.getString("identityKey")),
            preKeyId = web.getInt("preKeyId"),
            preKey = bytes(web.getString("preKey")),
            signedPreKeyId = web.getInt("signedPreKeyId"),
            signedPreKey = bytes(web.getString("signedPreKey")),
            signedPreKeySignature = bytes(web.getString("signedPreKeySignature")),
            kyberPreKeyId = web.getInt("kyberPreKeyId"),
            kyberPreKey = bytes(web.getString("kyberPreKey")),
            kyberPreKeySignature = bytes(web.getString("kyberPreKeySignature"))
        )
        assertEquals(33, remote.identityKey.size)
        engine.processRemotePreKeyBundle(webUserId, remote)
        val initiated = engine.encrypt(webUserId, remote.deviceId, "первое сообщение из Android".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, initiated.type)
        write(directory, "android-initiated.json", JSONObject().apply {
            put("type", initiated.type)
            put("ciphertext", b64(initiated.bytes))
            put("preKeyId", remote.preKeyId)
            put("kyberPreKeyId", remote.kyberPreKeyId)
            put("plaintext", "первое сообщение из Android")
        })

        // 5. The browser's sender key for a group.
        val group = read(directory, "web-group.json")
        engine.processSenderKeyDistribution(webUserId, webDeviceId, bytes(group.getString("distribution")))
        val groupPlain = engine.groupDecrypt(webUserId, webDeviceId, bytes(group.getString("ciphertext")))
        assertEquals(group.getString("plaintext"), String(groupPlain, Charsets.UTF_8))

        write(directory, "android-done.json", JSONObject().put("ok", true))
    }
}
