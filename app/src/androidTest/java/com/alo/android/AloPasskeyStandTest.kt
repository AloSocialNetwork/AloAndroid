package com.alo.android

import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's real API client against a real server, from «add a passkey» to signing in with it. Only the system's Credential
 * Manager sheet is replaced (it needs a Google account): a software authenticator answers what it would, with the origin
 * computed from this app's real signing certificate. Runs only with a throwaway server:
 *   -e alo_stand_url http://127.0.0.1:<port>/ -e alo_stand_token <session> -e alo_stand_rp <rpId>
 */
@RunWith(AndroidJUnit4::class)
class AloPasskeyStandTest {
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun cborBytes(value: ByteArray) = ByteArrayOutputStream().apply {
        if (value.size < 24) write(0x40 + value.size) else if (value.size < 256) { write(0x58); write(value.size) } else { write(0x59); write(value.size shr 8); write(value.size and 255) }
        write(value)
    }.toByteArray()

    private fun cborText(value: String) = byteArrayOf((0x60 + value.length).toByte()) + value.toByteArray()

    private fun coordinate(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    @Suppress("DEPRECATION")
    private fun appOrigin(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.first()
        } else {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        }
        return "android:apk-key-hash:" + b64(sha256(certificate.toByteArray()))
    }

    private fun create(challenge: JSONObject, keys: KeyPair, id: ByteArray, rp: String, origin: String): String {
        val key = keys.public as ECPublicKey
        val cose = byteArrayOf(0xa5.toByte(), 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21) + cborBytes(coordinate(key.w.affineX)) +
            byteArrayOf(0x22) + cborBytes(coordinate(key.w.affineY))
        val authData = sha256(rp.toByteArray()) + byteArrayOf(0x45) + ByteArray(4) + ByteArray(16) + byteArrayOf(0, id.size.toByte()) + id + cose
        val attestation = byteArrayOf(0xa3.toByte()) + cborText("fmt") + cborText("none") + cborText("attStmt") + byteArrayOf(0xa0.toByte()) +
            cborText("authData") + cborBytes(authData)
        val clientData = JSONObject().put("type", "webauthn.create").put("challenge", challenge.getString("challenge")).put("origin", origin).put("crossOrigin", false)
        return JSONObject().put("id", b64(id)).put("rawId", b64(id)).put("type", "public-key").put("clientExtensionResults", JSONObject())
            .put("response", JSONObject().put("clientDataJSON", b64(clientData.toString().toByteArray())).put("attestationObject", b64(attestation)))
            .toString()
    }

    private fun sign(challenge: JSONObject, keys: KeyPair, id: ByteArray, rp: String, origin: String): String {
        val authData = sha256(rp.toByteArray()) + byteArrayOf(0x05) + byteArrayOf(0, 0, 0, 1)
        val clientData = JSONObject().put("type", "webauthn.get").put("challenge", challenge.getString("challenge")).put("origin", origin).put("crossOrigin", false).toString().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(authData + sha256(clientData)) }.sign()
        return JSONObject().put("id", b64(id)).put("rawId", b64(id)).put("type", "public-key").put("clientExtensionResults", JSONObject())
            .put("response", JSONObject().put("clientDataJSON", b64(clientData)).put("authenticatorData", b64(authData)).put("signature", b64(signature)))
            .toString()
    }

    @Test fun addsAPasskeyAndSignsInWithIt() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("alo_stand_url").orEmpty()
        val token = arguments.getString("alo_stand_token").orEmpty()
        val rp = arguments.getString("alo_stand_rp").orEmpty()
        assumeTrue("needs a throwaway server", url.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = appOrigin()
        val client = AloApiClient(url, context)
        val cookies = CookieManager.getInstance()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cookies.removeAllCookies(null)
            cookies.setCookie(url, "alo_session=$token")
            cookies.flush()
        }
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = ByteArray(32).also(SecureRandom()::nextBytes)

        assertEquals(0, client.loadPasskeys().size)
        val registration = client.requestPasskeyRegistrationOptions()
        assertEquals(rp, JSONObject(registration.optionsJson).getJSONObject("rp").getString("id"))
        client.verifyPasskeyRegistration(registration, create(JSONObject(registration.optionsJson), keys, id, rp, origin))
        assertEquals(1, client.loadPasskeys().size)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cookies.removeAllCookies(null)
            cookies.flush()
        }
        assertFalse("signed out before the passkey", client.hasSession())
        val authentication = client.requestPasskeyAuthenticationOptions()
        client.verifyPasskeyAuthentication(authentication, sign(JSONObject(authentication.optionsJson), keys, id, rp, origin))
        assertTrue("signed in by the passkey", client.hasSession())
        assertEquals(1, client.loadPasskeys().size)
    }
}
