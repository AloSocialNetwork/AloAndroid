package com.alo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

internal class AloDraftAttachmentStore(
    private val context: Context
) {
    suspend fun buildDataUrlForDraft(draft: AloChatDraftAttachment): String {
        return withContext(Dispatchers.IO) {
            val sourceUri = Uri.parse(
                draft.localUri.ifBlank { draft.previewUri }
            )
            val bytes = readAllBytes(sourceUri, maxBytes = MAX_INLINE_DATA_URL_BYTES)
            val mimeType = draft.mimeType.ifBlank { "application/octet-stream" }
            "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }
    }

    fun buildDraftFromContentUri(
        uri: Uri,
        forcedName: String?,
        forceVideoCircle: Boolean
    ): AloChatDraftAttachment {
        val displayName = forcedName?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri)
        val mimeType = resolveContentMimeType(uri, displayName, forceVideoCircle)
        val plaintextFile = copyUriToCacheFile(uri, displayName, mimeType)
        try {
            val plaintextUri = Uri.fromFile(plaintextFile)
            val attachmentType = when {
                forceVideoCircle -> "video"
                else -> resolveDraftAttachmentType(mimeType, plaintextFile.name)
            }
            val name = if (forceVideoCircle) buildVideoCircleFileName(mimeType) else displayName
            // A persistent JPEG preview would disclose a frame from protected
            // media in plaintext. Image previews are served through the
            // encrypted provider below; video UI uses its encrypted source.
            val previewUri = ""
            val isSong = attachmentType == "file" && mimeType.lowercase(Locale.ROOT).startsWith("audio/")
            val songTags = if (isSong) AloMusic.readTags(context, plaintextUri) else Triple("", "", 0)
            val durationSec = if (attachmentType == "video" || attachmentType == "voice") {
                resolveMediaDurationSec(plaintextUri).coerceAtLeast(1)
            } else {
                songTags.third
            }
            // Read from headers while the plaintext copy still exists; it is deleted below.
            val (width, height) = resolveMediaSize(plaintextFile, attachmentType)
            val destination = File(
                ensureEncryptedDraftsDir(),
                "${UUID.randomUUID().toString().replace("-", "")}.bin"
            )
            val sealed = AloChunkedMediaCrypto.encrypt(
                source = plaintextFile,
                destination = destination,
                mime = mimeType
            )
            val secureUri = try {
                AloE2eeMediaContentProvider.register(
                    context = context,
                    encryptedFile = sealed.encryptedFile,
                    encryption = JSONObject(sealed.metadata.asMap()),
                    type = attachmentType,
                    name = name
                )
            } catch (error: Throwable) {
                sealed.encryptedFile.delete()
                throw error
            }
            return AloChatDraftAttachment(
                localUri = secureUri.toString(),
                previewUri = previewUri.ifBlank {
                    if (attachmentType == "image") secureUri.toString() else ""
                },
                attachmentType = attachmentType,
                mimeType = mimeType,
                name = name,
                durationSec = durationSec,
                isVideoCircle = forceVideoCircle,
                encryptedSourcePath = sealed.encryptedFile.absolutePath,
                mediaEncryption = sealed.metadata.asMap(),
                encryptedChecksum = sealed.sha256,
                width = width,
                height = height,
                songTitle = songTags.first,
                songPerformer = songTags.second
            )
        } finally {
            plaintextFile.delete()
        }
    }

    fun resolveMediaDurationSec(uri: Uri): Int {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                (durationMs / 1000L).toInt().coerceAtLeast(0)
            } finally {
                retriever.release()
            }
        }.getOrDefault(0)
    }

    /** Display size from headers only: no full decode of a large photo or video. */
    fun resolveMediaSize(file: File, attachmentType: String): Pair<Int, Int> {
        return runCatching {
            when (attachmentType) {
                "image" -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    val orientation = runCatching {
                        ExifInterface(file.path).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    AloMediaSize.display(options.outWidth, options.outHeight, AloMediaSize.exifSwapsAxes(orientation))
                }
                "video" -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.path)
                        fun key(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
                        AloMediaSize.display(
                            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                            AloMediaSize.rotationSwapsAxes(key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                        )
                    } finally {
                        retriever.release()
                    }
                }
                else -> 0 to 0
            }
        }.getOrDefault(0 to 0)
    }

    fun ensureChatDraftsDir(): File {
        return File(context.cacheDir, "chat-drafts").apply {
            mkdirs()
        }
    }

    fun ensureEncryptedDraftsDir(): File {
        return File(context.filesDir, "alo-e2ee-media/drafts").apply {
            mkdirs()
        }
    }

    /**
     * Drafts are copied into app-private storage when selected. Returning that
     * file lets the encrypted uploader stream it without first making a
     * Base64/ByteArray copy in memory.
     */
    fun fileForDraft(draft: AloChatDraftAttachment): File? {
        encryptedFileForDraft(draft)?.let { return it }
        val uri = runCatching { Uri.parse(draft.localUri.ifBlank { draft.previewUri }) }.getOrNull()
            ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val file = File(path)
        val draftsRoot = ensureChatDraftsDir().canonicalFile
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val allowedPrefix = "${draftsRoot.path}${File.separator}"
        return candidate.takeIf { it.isFile && candidate.path.startsWith(allowedPrefix) }
    }

    fun encryptedFileForDraft(draft: AloChatDraftAttachment): File? {
        if (draft.encryptedSourcePath.isBlank() || draft.mediaEncryption.isEmpty()) return null
        val encryptedRoot = ensureEncryptedDraftsDir().canonicalFile
        val candidate = runCatching { File(draft.encryptedSourcePath).canonicalFile }.getOrNull()
            ?: return null
        val prefix = "${encryptedRoot.path}${File.separator}"
        if (!candidate.isFile || !candidate.path.startsWith(prefix)) return null
        val metadata = AloChunkedMediaCrypto.parseMetadata(JSONObject(draft.mediaEncryption)) ?: return null
        val expectedSize = AloChunkedMediaCrypto.expectedCiphertextSize(
            metadata.plaintextSize,
            metadata.chunkSize
        )
        return candidate.takeIf { it.length() == expectedSize }
    }

    fun restoreEncryptedDraft(record: JSONObject): AloChatDraftAttachment? {
        val mediaEncryptionJson = record.optJSONObject("mediaEncryption") ?: return null
        val mediaEncryption = buildMap {
            val keys = mediaEncryptionJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, mediaEncryptionJson.optString(key))
            }
        }
        val draft = AloChatDraftAttachment(
            attachmentType = record.optString("attachmentType"),
            mimeType = record.optString("mimeType"),
            name = record.optString("name"),
            durationSec = record.optInt("durationSec"),
            isVideoCircle = record.optBoolean("isVideoCircle"),
            encryptedSourcePath = record.optString("encryptedSourcePath"),
            mediaEncryption = mediaEncryption,
            encryptedChecksum = record.optString("encryptedChecksum"),
            width = record.optInt("width"),
            height = record.optInt("height")
        )
        val encryptedFile = encryptedFileForDraft(draft) ?: return null
        val secureUri = runCatching {
            AloE2eeMediaContentProvider.register(
                context = context,
                encryptedFile = encryptedFile,
                encryption = JSONObject(mediaEncryption),
                type = draft.attachmentType,
                name = draft.name
            )
        }.getOrNull() ?: return null
        return draft.copy(
            localUri = secureUri.toString(),
            previewUri = if (draft.attachmentType == "image") secureUri.toString() else ""
        )
    }

    fun deleteDraftAttachmentFiles(draft: AloChatDraftAttachment) {
        encryptedFileForDraft(draft)?.delete()
        deleteDraftFileByUri(draft.localUri)
        if (draft.previewUri.isNotBlank() && draft.previewUri != draft.localUri) {
            deleteDraftFileByUri(draft.previewUri)
        }
    }

    private fun readAllBytes(uri: Uri, maxBytes: Int): ByteArray {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = File(requireNotNull(uri.path))
            require(file.length() <= maxBytes) { "Файл слишком велик для этого действия." }
            return file.readBytes()
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "Файл слишком велик для этого действия." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        error("Unable to read uri: $uri")
    }

    private fun copyUriToCacheFile(
        uri: Uri,
        preferredName: String,
        mimeType: String
    ): File {
        val safeName = sanitizeFileName(preferredName.ifBlank {
            "attachment-${System.currentTimeMillis()}.${extensionFromMime(mimeType).ifBlank { "bin" }}"
        })
        val target = File(
            ensureChatDraftsDir(),
            "${UUID.randomUUID().toString().replace("-", "")}-${safeName}"
        )
        try {
            val knownSize = knownSize(uri)
            require(knownSize == null || knownSize in 1L..MAX_PLAINTEXT_ATTACHMENT_BYTES) {
                "Размер вложения должен быть от 1 байта до 2 ГБ."
            }
            val input = if (uri.scheme.equals("file", ignoreCase = true)) {
                val source = File(requireNotNull(uri.path))
                require(source.isFile) { "Вложение не найдено." }
                source.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open uri: $uri")
            }
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count.toLong()
                        require(copied <= MAX_PLAINTEXT_ATTACHMENT_BYTES) {
                            "Размер вложения должен быть от 1 байта до 2 ГБ."
                        }
                        output.write(buffer, 0, count)
                    }
                    require(copied > 0L) { "Нельзя отправить пустой файл." }
                    output.fd.sync()
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun knownSize(uri: Uri): Long? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return File(requireNotNull(uri.path)).takeIf(File::isFile)?.length()
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex).takeIf { it >= 0L }
            }
        }
        return null
    }

    private fun resolveContentMimeType(
        uri: Uri,
        displayName: String,
        forceVideoCircle: Boolean
    ): String {
        if (forceVideoCircle) {
            return context.contentResolver.getType(uri)?.trim().takeUnless { it.isNullOrBlank() } ?: "video/mp4"
        }
        return context.contentResolver.getType(uri)?.trim().takeUnless { it.isNullOrBlank() }
            ?: guessMimeTypeFromName(displayName)
    }

    private fun resolveDraftAttachmentType(
        mimeType: String,
        name: String
    ): String {
        val mime = mimeType.trim().lowercase(Locale.ROOT)
        val loweredName = name.lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            // A picked audio file is a song (sent as a file, AloMusic.kt); the recorder marks its recordings "voice" itself.
            mime.startsWith("audio/") -> "file"
            loweredName.endsWith(".ogg") ||
                loweredName.endsWith(".opus") ||
                loweredName.endsWith(".m4a") ||
                loweredName.endsWith(".mp3") ||
                loweredName.endsWith(".wav") -> "file"
            else -> "file"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex).orEmpty()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "attachment-${System.currentTimeMillis()}"
    }

    private fun deleteDraftFileByUri(uriString: String) {
        if (uriString.isBlank()) return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (!uri.scheme.equals("file", ignoreCase = true)) return
        val path = uri.path ?: return
        if (!path.startsWith(ensureChatDraftsDir().absolutePath)) return
        runCatching { File(path).delete() }
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_INLINE_DATA_URL_BYTES = 24 * 1024 * 1024
        const val MAX_PLAINTEXT_ATTACHMENT_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
