package com.alo.android

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.min

/**
 * File-based AES-GCM envelope used by new E2EE attachments. The metadata is
 * transported in the message E2EE payload, while this class writes only opaque
 * encrypted bytes to disk.
 */
object AloChunkedMediaCrypto {
    const val SCHEME = "alo-aes-gcm-chunked-v1"
    const val DEFAULT_CHUNK_SIZE = 1_024 * 1_024
    const val MAX_PLAINTEXT_BYTES = 2L * 1_024L * 1_024L * 1_024L

    private const val AES_KEY_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16L
    private const val AAD_PREFIX = "alo-media-stream-v1"

    data class Metadata(
        val key: String,
        val noncePrefix: String,
        val chunkSize: Int,
        val plaintextSize: Long,
        val mime: String
    ) {
        fun asMap(): Map<String, String> = mapOf(
            "algorithm" to SCHEME,
            "key" to key,
            "noncePrefix" to noncePrefix,
            "chunkSize" to chunkSize.toString(),
            "plaintextSize" to plaintextSize.toString(),
            "mime" to mime
        )
    }

    data class EncryptionResult(
        val encryptedFile: File,
        val metadata: Metadata,
        val encryptedSize: Long,
        val sha256: String
    )

    fun encrypt(
        source: File,
        destination: File,
        mime: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): EncryptionResult {
        require(source.isFile) { "Вложение не найдено." }
        require(source.canonicalFile != destination.canonicalFile) { "Нельзя зашифровать файл поверх исходного." }
        val plaintextSize = source.length()
        require(plaintextSize in 1..MAX_PLAINTEXT_BYTES) { "Размер вложения должен быть от 1 байта до 2 ГБ." }
        require(chunkSize in 64 * 1_024..DEFAULT_CHUNK_SIZE && chunkSize % 16 == 0) {
            "Некорректный размер чанка."
        }

        val key = ByteArray(AES_KEY_BYTES).also(SecureRandom()::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
        val keyEncoded = encodeBase64Url(key)
        val prefixEncoded = encodeBase64Url(noncePrefix)
        val temporary = File(
            destination.parentFile ?: source.parentFile,
            ".${destination.name}.upload-${System.nanoTime()}.part"
        )

        try {
            temporary.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var chunkIndex = 0L
            val buffer = ByteArray(chunkSize)

            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    while (true) {
                        val count = readChunk(input, buffer)
                        if (count <= 0) break
                        val plaintext = buffer.copyOf(count)
                        val encrypted = encryptChunk(
                            plaintext = plaintext,
                            key = key,
                            noncePrefix = noncePrefix,
                            plaintextSize = plaintextSize,
                            chunkSize = chunkSize,
                            chunkIndex = chunkIndex
                        )
                        try {
                            output.write(encrypted)
                            digest.update(encrypted)
                            written += encrypted.size.toLong()
                            chunkIndex += 1
                        } finally {
                            plaintext.fill(0)
                            encrypted.fill(0)
                        }
                    }
                    output.fd.sync()
                }
            }

            check(written == expectedCiphertextSize(plaintextSize, chunkSize)) {
                "Размер зашифрованного вложения не совпал с ожидаемым."
            }
            replaceAtomically(temporary, destination)
            return EncryptionResult(
                encryptedFile = destination,
                metadata = Metadata(
                    key = keyEncoded,
                    noncePrefix = prefixEncoded,
                    chunkSize = chunkSize,
                    plaintextSize = plaintextSize,
                    mime = mime.ifBlank { "application/octet-stream" }
                ),
                encryptedSize = written,
                sha256 = digest.digest().toHex()
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            key.fill(0)
            noncePrefix.fill(0)
        }
    }

    fun parseMetadata(encryption: JSONObject): Metadata? {
        if (encryption.optString("algorithm") != SCHEME) return null
        val key = encryption.optString("key").trim()
        val noncePrefix = encryption.optString("noncePrefix").trim()
        val chunkSize = encryption.optLongValue("chunkSize")
        val plaintextSize = encryption.optLongValue("plaintextSize")
        if (
            key.isBlank() || noncePrefix.isBlank() ||
            chunkSize !in 64L * 1_024L..DEFAULT_CHUNK_SIZE.toLong() ||
            chunkSize % 16L != 0L ||
            plaintextSize !in 1..MAX_PLAINTEXT_BYTES
        ) {
            return null
        }
        return runCatching {
            require(decodeBase64Url(key).size == AES_KEY_BYTES)
            require(decodeBase64Url(noncePrefix).size == NONCE_PREFIX_BYTES)
            Metadata(
                key = key,
                noncePrefix = noncePrefix,
                chunkSize = chunkSize.toInt(),
                plaintextSize = plaintextSize,
                mime = encryption.optString("mime").ifBlank { "application/octet-stream" }
            )
        }.getOrNull()
    }

    fun plaintextSize(metadata: Metadata): Long = metadata.plaintextSize

    fun readRange(
        encryptedFile: File,
        metadata: Metadata,
        offset: Long,
        size: Int
    ): ByteArray {
        if (offset < 0 || size <= 0 || offset >= metadata.plaintextSize) return ByteArray(0)
        validateCiphertextFile(encryptedFile, metadata)
        val endExclusive = min(metadata.plaintextSize, offset + size.toLong())
        val firstChunk = offset / metadata.chunkSize
        val lastChunk = (endExclusive - 1L) / metadata.chunkSize
        val output = ByteArrayOutputStream((endExclusive - offset).toInt())
        RandomAccessFile(encryptedFile, "r").use { input ->
            var index = firstChunk
            while (index <= lastChunk) {
                val chunkStart = index * metadata.chunkSize.toLong()
                val plaintextLength = min(
                    metadata.chunkSize.toLong(),
                    metadata.plaintextSize - chunkStart
                ).toInt()
                val plaintext = decryptChunk(input, metadata, index, plaintextLength)
                try {
                    val from = maxOf(offset, chunkStart)
                    val to = min(endExclusive, chunkStart + plaintextLength)
                    output.write(
                        plaintext,
                        (from - chunkStart).toInt(),
                        (to - from).toInt()
                    )
                } finally {
                    plaintext.fill(0)
                }
                index += 1
            }
        }
        return output.toByteArray()
    }

    fun decryptTo(
        encryptedFile: File,
        metadata: Metadata,
        output: OutputStream
    ) {
        validateCiphertextFile(encryptedFile, metadata)
        RandomAccessFile(encryptedFile, "r").use { input ->
            val chunkCount = chunksFor(metadata.plaintextSize, metadata.chunkSize)
            for (index in 0 until chunkCount) {
                val chunkStart = index * metadata.chunkSize.toLong()
                val plaintextLength = min(
                    metadata.chunkSize.toLong(),
                    metadata.plaintextSize - chunkStart
                ).toInt()
                val plaintext = decryptChunk(input, metadata, index, plaintextLength)
                try {
                    output.write(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }

    fun expectedCiphertextSize(plaintextSize: Long, chunkSize: Int): Long {
        require(plaintextSize >= 0)
        if (plaintextSize == 0L) return 0L
        return plaintextSize + chunksFor(plaintextSize, chunkSize) * GCM_TAG_BYTES
    }

    private fun encryptChunk(
        plaintext: ByteArray,
        key: ByteArray,
        noncePrefix: ByteArray,
        plaintextSize: Long,
        chunkSize: Int,
        chunkIndex: Long
    ): ByteArray {
        val nonce = nonce(noncePrefix, chunkIndex)
        val aad = aad(plaintextSize, chunkSize, chunkIndex)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }.doFinal(plaintext)
        } finally {
            nonce.fill(0)
            aad.fill(0)
        }
    }

    private fun decryptChunk(
        input: RandomAccessFile,
        metadata: Metadata,
        chunkIndex: Long,
        plaintextLength: Int
    ): ByteArray {
        val encryptedLength = plaintextLength + GCM_TAG_BYTES.toInt()
        val storedOffset = chunkIndex * (metadata.chunkSize.toLong() + GCM_TAG_BYTES)
        val encrypted = ByteArray(encryptedLength)
        input.seek(storedOffset)
        input.readFully(encrypted)
        val key = decodeBase64Url(metadata.key)
        val prefix = decodeBase64Url(metadata.noncePrefix)
        val nonce = nonce(prefix, chunkIndex)
        val aad = aad(metadata.plaintextSize, metadata.chunkSize, chunkIndex)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }.doFinal(encrypted)
        } finally {
            encrypted.fill(0)
            key.fill(0)
            prefix.fill(0)
            nonce.fill(0)
            aad.fill(0)
        }
    }

    private fun validateCiphertextFile(file: File, metadata: Metadata) {
        require(file.isFile) { "Зашифрованное вложение не найдено." }
        require(file.length() == expectedCiphertextSize(metadata.plaintextSize, metadata.chunkSize)) {
            "Зашифрованное вложение повреждено или загружено не полностью."
        }
    }

    private fun readChunk(input: FileInputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        return total
    }

    private fun nonce(prefix: ByteArray, chunkIndex: Long): ByteArray {
        require(prefix.size == NONCE_PREFIX_BYTES)
        require(chunkIndex in 0..0xffff_ffffL)
        return prefix + ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(chunkIndex.toInt())
            .array()
    }

    private fun aad(plaintextSize: Long, chunkSize: Int, chunkIndex: Long): ByteArray =
        "$AAD_PREFIX:$plaintextSize:$chunkSize:$chunkIndex".toByteArray(Charsets.US_ASCII)

    private fun chunksFor(plaintextSize: Long, chunkSize: Int): Long =
        ceil(plaintextSize.toDouble() / chunkSize.toDouble()).toLong()

    private fun replaceAtomically(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            error("Не удалось заменить временный файл вложения.")
        }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun JSONObject.optLongValue(name: String): Long {
        val string = optString(name).trim()
        return string.toLongOrNull() ?: optLong(name, -1L)
    }

    private fun encodeBase64Url(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
        value.replace('-', '+').replace('_', '/') + "=".repeat((4 - value.length % 4) % 4),
        Base64.DEFAULT
    )

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
