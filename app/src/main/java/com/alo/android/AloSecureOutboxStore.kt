package com.alo.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AloSecureOutboxStore(
    context: Context
) {
    private val baseRoot = File(context.filesDir, "alo-secure-outbox")

    @Synchronized
    fun saveDocument(name: String, records: JSONArray, ownerUserId: Int) {
        require(name in setOf("drafts", "publications"))
        writeEncrypted(File(root(ownerUserId), "$name-v1.bin"), records.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun loadDocument(name: String, ownerUserId: Int): JSONArray {
        require(name in setOf("drafts", "publications"))
        val file = File(root(ownerUserId), "$name-v1.bin")
        // Do not overwrite unreadable data with an empty queue.
        return if (file.isFile || File(file.path + ".bak").isFile) JSONArray(String(readEncrypted(file), Charsets.UTF_8)) else JSONArray()
    }

    fun save(records: JSONArray, ownerUserId: Int) {
        val root = root(ownerUserId)
        root.mkdirs()
        writeEncrypted(File(root, "queue-v1.bin"), records.toString().toByteArray(Charsets.UTF_8))
    }

    fun load(ownerUserId: Int): JSONArray {
        val queueFile = File(root(ownerUserId), "queue-v1.bin")
        if (!queueFile.isFile) return JSONArray()
        return runCatching {
            JSONArray(String(readEncrypted(queueFile), Charsets.UTF_8))
        }.getOrElse { JSONArray() }
    }

    fun saveAttachment(bytes: ByteArray, id: String, ownerUserId: Int) {
        val root = root(ownerUserId)
        root.mkdirs()
        writeEncrypted(attachmentFile(id, ownerUserId), bytes)
    }

    fun loadAttachment(id: String, ownerUserId: Int): ByteArray? {
        val file = runCatching { attachmentFile(id, ownerUserId) }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching { readEncrypted(file) }.getOrNull()
    }

    data class QuizRevealSecret(
        val correctOptionIds: Set<String>,
        val salt: String
    )

    fun saveQuizReveal(
        pollId: String,
        correctOptionIds: Set<String>,
        salt: String,
        ownerUserId: Int
    ) {
        require(correctOptionIds.size == 1 && correctOptionIds.all(::isSafeQuizOptionId))
        require(AloPollQuizCommitment.isValidSalt(salt))
        writeEncrypted(
            quizRevealFile(pollId, ownerUserId),
            JSONObject()
                .put("correctOptionIds", JSONArray().also { values ->
                    correctOptionIds.sorted().forEach(values::put)
                })
                .put("salt", salt)
                .toString()
                .toByteArray(Charsets.UTF_8)
        )
    }

    fun loadQuizReveal(pollId: String, ownerUserId: Int): QuizRevealSecret? {
        val file = runCatching { quizRevealFile(pollId, ownerUserId) }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching {
            val record = JSONObject(String(readEncrypted(file), Charsets.UTF_8))
            val values = record.optJSONArray("correctOptionIds") ?: JSONArray()
            val optionIds = buildSet {
                repeat(values.length()) { index -> add(values.optString(index)) }
            }
            val salt = record.optString("salt")
            require(optionIds.size == 1 && optionIds.all(::isSafeQuizOptionId))
            require(AloPollQuizCommitment.isValidSalt(salt))
            QuizRevealSecret(optionIds, salt)
        }.getOrNull()
    }

    fun deleteQuizReveal(pollId: String, ownerUserId: Int) {
        runCatching { quizRevealFile(pollId, ownerUserId).delete() }
    }

    fun deleteAttachment(id: String, ownerUserId: Int) {
        runCatching { attachmentFile(id, ownerUserId).delete() }
    }

    fun clear(ownerUserId: Int) {
        val root = root(ownerUserId)
        File(root, "queue-v1.bin").delete()
        root.listFiles()?.filter { it.name.startsWith("attachment-") }?.forEach { it.delete() }
    }

    fun discardLegacyUnscopedData() {
        baseRoot.listFiles()?.forEach { file ->
            if (file.isFile && (file.name == "queue-v1.bin" || file.name.startsWith("attachment-"))) {
                file.delete()
            }
        }
    }

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(bytes)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
            atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private fun readEncrypted(file: File): ByteArray {
        val bytes = AtomicFile(file).openRead().use { it.readBytes() }
        require(bytes.isNotEmpty())
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size > 1 + ivSize)
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
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
        generator.generateKey()
    }

    private fun root(ownerUserId: Int): File {
        require(ownerUserId > 0)
        return File(baseRoot, "user-$ownerUserId")
    }

    private fun attachmentFile(id: String, ownerUserId: Int): File {
        val safeId = id.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        require(safeId.length >= 8)
        return File(root(ownerUserId), "attachment-$safeId.bin")
    }

    private fun quizRevealFile(pollId: String, ownerUserId: Int): File {
        val safeId = pollId.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        require(safeId.length >= 8)
        return File(root(ownerUserId), "quiz-$safeId.reveal")
    }

    private fun isSafeQuizOptionId(value: String): Boolean = value.length in 1..64 &&
        value.all { (it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '-' }

    private companion object {
        val KEY_LOCK = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "alo.android.secure-outbox.v1"
    }
}
