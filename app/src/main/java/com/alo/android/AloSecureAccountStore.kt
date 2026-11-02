package com.alo.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Public, non-secret metadata used to render the account switcher.
 *
 * Session cookies remain inside [AloSecureAccountStore] and are never part of
 * this type, so UI state cannot accidentally expose authentication material.
 */
data class AloStoredAccount(
    val userId: Int,
    val name: String,
    val username: String,
    val email: String,
    val avatarUrl: String,
    val lastUsedAtMillis: Long
) {
    val displayName: String
        get() = name.ifBlank { username.ifBlank { email.ifBlank { "Аккаунт Alo" } } }
}

/**
 * Stores the device installation identifier and restorable account sessions in
 * app-private, AES-GCM encrypted files. The AES key is non-exportable and lives
 * in Android Keystore, so a session cookie is never written to SharedPreferences
 * or to an unencrypted file. Passwords are deliberately not part of this store.
 */
internal class AloSecureAccountStore(context: Context) {

    private data class StoredAccount(
        val account: AloStoredAccount,
        val sessionCookie: String
    )

    private val root = File(context.filesDir, "alo-account-security")
    private val identityFile = File(root, "install-identity-v1.bin")
    private val accountsFile = File(root, "accounts-v1.bin")
    private val lock = Any()

    fun installDeviceId(): String = synchronized(lock) {
        val existing = runCatching {
            JSONObject(String(readEncrypted(identityFile), Charsets.UTF_8))
                .optString("deviceId")
                .trim()
        }.getOrDefault("")
        if (existing.isNotBlank()) return@synchronized existing

        val deviceId = UUID.randomUUID().toString().lowercase()
        writeEncrypted(
            identityFile,
            JSONObject().put("deviceId", deviceId).toString().toByteArray(Charsets.UTF_8)
        )
        deviceId
    }

    fun accounts(): List<AloStoredAccount> = synchronized(lock) {
        readStoredAccounts()
            .map(StoredAccount::account)
            .sortedByDescending(AloStoredAccount::lastUsedAtMillis)
    }

    fun activeAccountId(): Int = synchronized(lock) {
        readAccountsDocument().optInt("activeAccountId").takeIf { it > 0 } ?: 0
    }

    fun offlineOwnerForSession(cookieHeader: String): Int = synchronized(lock) {
        val selectedOwnerId = readAccountsDocument().optInt("activeAccountId")
        val savedCookie = readStoredAccounts().firstOrNull { it.account.userId == selectedOwnerId }?.sessionCookie
        AloOfflineAccountPolicy.ownerForSession(selectedOwnerId, savedCookie, cookieHeader)
    }

    fun saveCurrentSession(user: AloCurrentUser, cookieHeader: String) {
        val safeCookie = cookieHeader.trim()
        if (user.id <= 0 || safeCookie.isBlank()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val stored = readStoredAccounts().toMutableList()
            val index = stored.indexOfFirst { it.account.userId == user.id }
            val record = StoredAccount(
                account = AloStoredAccount(
                    userId = user.id,
                    name = user.name.trim(),
                    username = user.username.trim(),
                    email = user.email.trim(),
                    avatarUrl = user.avatarUrl.trim(),
                    lastUsedAtMillis = now
                ),
                sessionCookie = safeCookie
            )
            if (index >= 0) stored[index] = record else stored += record
            writeAccountsDocument(activeAccountId = user.id, stored = stored)
        }
    }

    fun sessionFor(userId: Int): String? = synchronized(lock) {
        readStoredAccounts()
            .firstOrNull { it.account.userId == userId }
            ?.sessionCookie
            ?.takeIf(String::isNotBlank)
    }

    fun markActive(userId: Int) {
        if (userId <= 0) return
        synchronized(lock) {
            val stored = readStoredAccounts().toMutableList()
            val index = stored.indexOfFirst { it.account.userId == userId }
            if (index < 0) return
            val previous = stored[index]
            stored[index] = previous.copy(
                account = previous.account.copy(lastUsedAtMillis = System.currentTimeMillis())
            )
            writeAccountsDocument(activeAccountId = userId, stored = stored)
        }
    }

    fun removeAccount(userId: Int) {
        if (userId <= 0) return
        synchronized(lock) {
            val remaining = readStoredAccounts().filterNot { it.account.userId == userId }
            val active = readAccountsDocument().optInt("activeAccountId")
            writeAccountsDocument(
                activeAccountId = active.takeIf { it != userId } ?: remaining.maxByOrNull {
                    it.account.lastUsedAtMillis
                }?.account?.userId.orEmptyAsZero(),
                stored = remaining
            )
        }
    }

    fun clearAllAccounts() = synchronized(lock) {
        runCatching { accountsFile.delete() }
    }

    private fun Int?.orEmptyAsZero(): Int = this ?: 0

    private fun readStoredAccounts(): List<StoredAccount> {
        val document = readAccountsDocument()
        val array = document.optJSONArray("accounts") ?: JSONArray()
        return buildList {
            repeat(array.length()) { index ->
                val json = array.optJSONObject(index) ?: return@repeat
                val userId = json.optInt("userId")
                val cookie = json.optString("sessionCookie").trim()
                if (userId <= 0 || cookie.isBlank()) return@repeat
                add(
                    StoredAccount(
                        account = AloStoredAccount(
                            userId = userId,
                            name = json.optString("name"),
                            username = json.optString("username"),
                            email = json.optString("email"),
                            avatarUrl = json.optString("avatarUrl"),
                            lastUsedAtMillis = json.optLong("lastUsedAtMillis")
                        ),
                        sessionCookie = cookie
                    )
                )
            }
        }
    }

    private fun readAccountsDocument(): JSONObject {
        if (!accountsFile.isFile) return JSONObject()
        return runCatching {
            JSONObject(String(readEncrypted(accountsFile), Charsets.UTF_8))
        }.getOrElse { JSONObject() }
    }

    private fun writeAccountsDocument(activeAccountId: Int, stored: List<StoredAccount>) {
        val document = JSONObject()
            .put("version", 1)
            .put("activeAccountId", activeAccountId)
            .put("accounts", JSONArray().also { array ->
                stored.forEach { record ->
                    array.put(
                        JSONObject()
                            .put("userId", record.account.userId)
                            .put("name", record.account.name)
                            .put("username", record.account.username)
                            .put("email", record.account.email)
                            .put("avatarUrl", record.account.avatarUrl)
                            .put("lastUsedAtMillis", record.account.lastUsedAtMillis)
                            .put("sessionCookie", record.sessionCookie)
                    )
                }
            })
        writeEncrypted(accountsFile, document.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(bytes)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun readEncrypted(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.isNotEmpty())
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size > 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize))
        )
        return cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "alo.android.account-sessions.v1"
    }
}
