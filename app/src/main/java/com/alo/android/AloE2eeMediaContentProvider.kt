package com.alo.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class AloE2eeMediaContentProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(Registry::purgeExpired)
        return true
    }

    override fun getType(uri: Uri): String? = context?.let { Registry.load(it, token(uri))?.mime }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val entry = requireEntry(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> row.add(entry.name)
                    OpenableColumns.SIZE -> row.add(contentSize(entry))
                    else -> row.add(null)
                }
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Encrypted media is read-only")
        val entry = requireEntry(uri)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) openSeekable(entry) else openPipe(entry)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun requireEntry(uri: Uri): Registry.Entry {
        val appContext = context ?: throw FileNotFoundException("Provider is unavailable")
        val entry = Registry.load(appContext, token(uri))
            ?: throw FileNotFoundException("Encrypted media registration is missing")
        if (Registry.isExpired(entry)) {
            Registry.remove(appContext, token(uri))?.file?.delete()
            throw FileNotFoundException("Encrypted media has expired")
        }
        val candidate = entry.file.canonicalFile
        val allowedRoots = listOf(
            File(appContext.cacheDir, "alo-e2ee-media/encrypted"),
            // Outgoing drafts must survive a process restart, so they live in
            // app-private files rather than a purgeable cache directory.
            File(appContext.filesDir, "alo-e2ee-media/drafts")
        ).map { it.canonicalFile }
        val insideManagedRoot = allowedRoots.any { root ->
            candidate.path.startsWith("${root.path}${File.separator}")
        }
        if (!insideManagedRoot || !candidate.isFile) {
            throw FileNotFoundException("Encrypted media cache entry is invalid")
        }
        return entry.copy(file = candidate)
    }

    private fun token(uri: Uri): String =
        uri.pathSegments.takeIf { it.size >= 2 && it[0] == PATH_MEDIA }?.get(1).orEmpty()

    private fun decrypt(entry: Registry.Entry): ByteArray {
        val key = decodeBase64Url(entry.key)
        val iv = decodeBase64Url(entry.iv)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(entry.file.readBytes())
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    private fun contentSize(entry: Registry.Entry): Long = when (entry.algorithm) {
        AloChunkedMediaCrypto.SCHEME -> entry.plaintextSize
        else -> (entry.file.length() - GCM_TAG_BYTES).coerceAtLeast(0L)
    }

    private fun chunkedMetadata(entry: Registry.Entry): AloChunkedMediaCrypto.Metadata {
        return AloChunkedMediaCrypto.parseMetadata(
            JSONObject()
                .put("algorithm", entry.algorithm)
                .put("key", entry.key)
                .put("noncePrefix", entry.noncePrefix)
                .put("chunkSize", entry.chunkSize)
                .put("plaintextSize", entry.plaintextSize)
                .put("mime", entry.mime)
        ) ?: throw FileNotFoundException("Encrypted media metadata is invalid")
    }

    private fun readRange(entry: Registry.Entry, offset: Long, size: Int): ByteArray {
        if (entry.algorithm == AloChunkedMediaCrypto.SCHEME) {
            return AloChunkedMediaCrypto.readRange(
                encryptedFile = entry.file,
                metadata = chunkedMetadata(entry),
                offset = offset,
                size = size
            )
        }
        val plaintext = decrypt(entry)
        return try {
            if (offset < 0 || offset >= plaintext.size || size <= 0) return ByteArray(0)
            plaintext.copyOfRange(
                offset.toInt(),
                minOf(plaintext.size, offset.toInt() + size)
            )
        } finally {
            plaintext.fill(0)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun openSeekable(entry: Registry.Entry): ParcelFileDescriptor {
        val appContext = context ?: throw FileNotFoundException("Provider is unavailable")
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                private var plaintext: ByteArray? = null

                @Synchronized
                private fun bytes(): ByteArray = plaintext ?: decrypt(entry).also { plaintext = it }

                override fun onGetSize(): Long = contentSize(entry)

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset < 0 || offset >= contentSize(entry)) return 0
                    val requested = minOf(size, data.size)
                    if (requested <= 0) return 0
                    if (entry.algorithm == AloChunkedMediaCrypto.SCHEME) {
                        val source = readRange(entry, offset, requested)
                        return try {
                            source.copyInto(data, destinationOffset = 0)
                            source.size
                        } finally {
                            source.fill(0)
                        }
                    }
                    val source = bytes()
                    val start = offset.toInt()
                    val count = minOf(requested, source.size - start)
                    source.copyInto(data, destinationOffset = 0, startIndex = start, endIndex = start + count)
                    return count
                }

                override fun onRelease() {
                    synchronized(this) {
                        plaintext?.fill(0)
                        plaintext = null
                    }
                }
            },
            Handler(proxyThread.looper)
        )
    }

    private fun openPipe(entry: Registry.Entry): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        pipeExecutor.execute {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { output ->
                    if (entry.algorithm == AloChunkedMediaCrypto.SCHEME) {
                        AloChunkedMediaCrypto.decryptTo(
                            encryptedFile = entry.file,
                            metadata = chunkedMetadata(entry),
                            output = output
                        )
                    } else {
                        val plaintext = decrypt(entry)
                        try {
                            output.write(plaintext)
                        } finally {
                            plaintext.fill(0)
                        }
                    }
                }
            } catch (error: Throwable) {
                runCatching { pipe[1].closeWithError(error.message ?: "Unable to decrypt media") }
            } finally {
                runCatching { pipe[1].close() }
            }
        }
        return pipe[0]
    }

    companion object {
        private const val PATH_MEDIA = "media"
        private const val GCM_TAG_BYTES = 16L
        private const val LEGACY_MEDIA_SCHEME = "aes-256-gcm-v1"
        private val pipeExecutor = Executors.newCachedThreadPool()
        private val proxyThread = HandlerThread("alo-e2ee-media-proxy").apply { start() }

        fun register(
            context: Context,
            encryptedFile: File,
            encryption: JSONObject,
            type: String,
            name: String,
            expiresAt: String = ""
        ): Uri {
            val algorithm = encryption.optString("algorithm").trim()
            val key = encryption.optString("key")
            val iv = encryption.optString("iv")
            val chunked = AloChunkedMediaCrypto.parseMetadata(encryption)
            require(
                (algorithm == LEGACY_MEDIA_SCHEME && key.isNotBlank() && iv.isNotBlank()) ||
                    chunked != null
            ) { "Invalid media encryption metadata" }
            val canonicalFile = encryptedFile.canonicalFile
            val token = digest(
                listOf(
                    canonicalFile.path,
                    algorithm,
                    key,
                    iv,
                    chunked?.noncePrefix.orEmpty(),
                    chunked?.chunkSize?.toString().orEmpty(),
                    chunked?.plaintextSize?.toString().orEmpty()
                ).joinToString("|")
            )
            val mime = encryption.optString("mime").ifBlank { mimeFor(type, name) }
            val displayName = name.trim().ifBlank { "media.${extensionFor(mime, type)}" }
            Registry.save(
                context.applicationContext,
                token,
                Registry.Entry(
                    file = canonicalFile,
                    algorithm = algorithm,
                    key = key,
                    iv = iv,
                    noncePrefix = chunked?.noncePrefix.orEmpty(),
                    chunkSize = chunked?.chunkSize ?: 0,
                    plaintextSize = chunked?.plaintextSize ?: 0L,
                    mime = mime,
                    name = displayName,
                    expiresAt = expiresAt.trim()
                )
            )
            return Uri.Builder()
                .scheme("content")
                .authority("${BuildConfig.APPLICATION_ID}.e2ee-media")
                .appendPath(PATH_MEDIA)
                .appendPath(token)
                .appendPath(displayName.replace('/', '_'))
                .build()
        }

        fun unregister(context: Context, uri: Uri) {
            val token = uri.pathSegments
                .takeIf { it.size >= 2 && it[0] == PATH_MEDIA }
                ?.get(1)
                .orEmpty()
            Registry.remove(context.applicationContext, token)?.file?.delete()
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun mimeFor(type: String, name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                type.equals("image", true) || extension in setOf("jpg", "jpeg", "png", "webp", "gif") ->
                    "image/${if (extension == "jpg") "jpeg" else extension.ifBlank { "jpeg" }}"
                type.contains("voice", true) || extension in setOf("m4a", "aac", "mp3", "ogg", "wav") ->
                    "audio/${extension.ifBlank { "mp4" }}"
                type.contains("video", true) || extension in setOf("mp4", "mov", "webm") ->
                    "video/${extension.ifBlank { "mp4" }}"
                else -> "application/octet-stream"
            }
        }

        private fun extensionFor(mime: String, type: String): String = when {
            mime.contains("jpeg") -> "jpg"
            mime.contains('/') -> mime.substringAfter('/').substringBefore(';').ifBlank { "bin" }
            type.contains("video", true) -> "mp4"
            type.contains("voice", true) -> "m4a"
            type.equals("image", true) -> "jpg"
            else -> "bin"
        }

        private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
            value.replace('-', '+').replace('_', '/') + "=".repeat((4 - value.length % 4) % 4),
            Base64.DEFAULT
        )
    }

    private object Registry {
        private const val PREFS = "alo-e2ee-media-registry-v1"
        private const val KEY_ALIAS = "alo.e2ee.media.registry.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        data class Entry(
            val file: File,
            val algorithm: String,
            val key: String,
            val iv: String,
            val noncePrefix: String,
            val chunkSize: Int,
            val plaintextSize: Long,
            val mime: String,
            val name: String,
            val expiresAt: String
        )

        @Synchronized
        fun save(context: Context, token: String, entry: Entry) {
            val json = JSONObject()
                .put("path", entry.file.path)
                .put("algorithm", entry.algorithm)
                .put("key", entry.key)
                .put("iv", entry.iv)
                .put("noncePrefix", entry.noncePrefix)
                .put("chunkSize", entry.chunkSize)
                .put("plaintextSize", entry.plaintextSize)
                .put("mime", entry.mime)
                .put("name", entry.name)
                .put("expiresAt", entry.expiresAt)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(token, seal(json.toString().toByteArray(Charsets.UTF_8)))
                .apply()
        }

        @Synchronized
        fun load(context: Context, token: String): Entry? {
            if (token.isBlank()) return null
            val sealed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(token, null) ?: return null
            return runCatching {
                val json = JSONObject(String(open(sealed), Charsets.UTF_8))
                Entry(
                    file = File(json.getString("path")),
                    algorithm = json.optString("algorithm", LEGACY_MEDIA_SCHEME),
                    key = json.getString("key"),
                    iv = json.optString("iv"),
                    noncePrefix = json.optString("noncePrefix"),
                    chunkSize = json.optInt("chunkSize", 0),
                    plaintextSize = json.optLong("plaintextSize", 0L),
                    mime = json.optString("mime", "application/octet-stream"),
                    name = json.optString("name", "media.bin"),
                    expiresAt = json.optString("expiresAt")
                )
            }.getOrNull()
        }

        @Synchronized
        fun remove(context: Context, token: String): Entry? {
            if (token.isBlank()) return null
            val entry = load(context, token)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(token)
                .apply()
            return entry
        }

        @Synchronized
        fun purgeExpired(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.all.keys.toList().forEach { token ->
                val entry = load(context, token) ?: return@forEach
                if (isExpired(entry)) {
                    remove(context, token)?.file?.delete()
                }
            }
        }

        fun isExpired(entry: Entry, nowMillis: Long = System.currentTimeMillis()): Boolean {
            if (entry.expiresAt.isBlank()) return false
            val expiresAt = runCatching { Instant.parse(entry.expiresAt).toEpochMilli() }.getOrNull()
            // A malformed deadline must never make protected media durable.
            return expiresAt == null || expiresAt <= nowMillis
        }

        private fun seal(plaintext: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            plaintext.fill(0)
            return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        }

        private fun open(value: String): ByteArray {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            require(combined.size > 28) { "Invalid registry value" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            return cipher.doFinal(combined.copyOfRange(12, combined.size))
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
