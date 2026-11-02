package com.alo.android

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local encrypted intent is committed before any request. The operation ID and
 * uploaded URLs/checkpoints survive process death and every retry uses that ID. */
internal class AloPublicationQueue(
    private val context: Context,
    private val api: AloApiClient,
    private val scope: CoroutineScope,
    private val session: () -> String,
    private val onStatus: (Int, String) -> Unit,
    private val onPost: (Int, AloPost, Int?) -> Unit,
    private val onStory: (Int, AloStory) -> Unit,
    private val onChallenge: (Int, AloApiException, (String) -> Unit) -> Boolean = { _, _, _ -> false },
    private val maxPendingBytes: Long = 2L * 1024 * 1024 * 1024
) {
    private val storage = AloSecureOutboxStore(context.applicationContext)
    // Activity recreation can briefly overlap the cancelled uploader's IO.
    // Share locks across queue instances so a late checkpoint cannot lose a newly queued job.
    private val lock = DOCUMENT_LOCK
    private val staging = STAGING_LOCK
    private var worker: Job? = null
    @Volatile private var owner = 0
    @Volatile private var cookie = ""
    @Volatile private var generation = 0

    private fun read(owner: Int): JSONArray = storage.loadDocument("publications", owner).also { array ->
        repeat(array.length()) { require(array.getJSONObject(it).getInt("owner") == owner) }
    }
    private fun change(owner: Int, block: (MutableList<JSONObject>) -> Unit) = synchronized(lock) {
        val array = read(owner)
        val records = (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
        block(records)
        storage.saveDocument("publications", JSONArray(records), owner)
    }
    private fun update(owner: Int, id: String, block: (JSONObject) -> Unit) = change(owner) { list ->
        list.firstOrNull { it.getString("id") == id }?.let(block)
    }

    suspend fun enqueue(owner: Int, kind: String, body: String, pages: List<AloStoryUploadPage>,
        media: List<AloMediaItem> = emptyList(), draftId: Int? = null, lifetimeHours: Int = 24,
        audience: String = "everyone", audienceUserIds: List<Int> = emptyList()): String = withContext(Dispatchers.IO) { staging.withLock {
        require(owner > 0 && kind in setOf("post", "story"))
        require(body.isNotBlank() || pages.isNotEmpty() || media.isNotEmpty()) { "Публикация не должна быть пустой." }
        require(body.length <= if (kind == "post") 1200 else 600) { "Слишком длинный текст публикации." }
        synchronized(lock) { require(read(owner).length() < 20) { "В очереди уже 20 публикаций. Дождитесь отправки." } }
        val id = UUID.randomUUID().toString()
        val directory = directory(owner, id).apply { mkdirs() }
        try {
            val files = JSONArray()
            pages.forEachIndexed { index, page ->
                val used = File(context.filesDir, "alo-publications/user-$owner").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val remaining = maxPendingBytes - used
                val encryptionOverhead = (1 + remaining.coerceAtLeast(0) / AloChunkedMediaCrypto.DEFAULT_CHUNK_SIZE) * 16
                val budget = minOf(remaining - encryptionOverhead, (context.filesDir.usableSpace - 256L * 1024 * 1024) / 2)
                require(budget > 0) { "Недостаточно места в очереди публикаций. Дождитесь отправки или освободите память." }
                val plain = File.createTempFile("alo-publication-", ".tmp", context.cacheDir)
                try {
                    plain.outputStream().use { output ->
                        if (page.bytes != null) {
                            require(page.bytes.size <= budget) { "Медиа превышает свободное место в очереди публикаций." }
                            output.write(page.bytes)
                        } else context.contentResolver.openInputStream(requireNotNull(page.uri))?.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= budget) { "Медиа превышает свободное место в очереди публикаций." }
                                output.write(buffer, 0, count)
                            }
                        } ?: error("Не удалось прочитать медиа.")
                    }
                    val encrypted = AloChunkedMediaCrypto.encrypt(plain, File(directory, "$index.bin"), page.mime)
                    files.put(JSONObject().put("type", page.type).put("mime", page.mime)
                        .put("encryption", JSONObject(encrypted.metadata.asMap())))
                } finally { plain.delete() }
            }
            val job = JSONObject().put("id", id).put("owner", owner).put("kind", kind).put("body", body)
                .put("files", files).put("media", JSONArray(media.map(::mediaJson)))
                .put("draftId", draftId).put("lifetimeHours", lifetimeHours).put("audience", audience)
                .put("audienceUserIds", JSONArray(audienceUserIds)).put("error", "").put("code", "")
            change(owner) { list ->
                require(list.size < 20) { "В очереди уже 20 публикаций. Дождитесь отправки." }
                require(draftId == null || list.none { it.optString("kind") == "post" && it.optInt("draftId") == draftId }) {
                    "Этот черновик уже находится в очереди отправки."
                }
                list.add(job)
            }
            id
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    } }

    fun activate(owner: Int, cookie: String) {
        if (owner <= 0) return
        if (this.owner != owner || this.cookie != cookie) {
            generation++
            worker?.cancel()
            worker = null
            this.owner = owner
            this.cookie = cookie
        }
        resume()
    }

    fun retry() {
        if (owner <= 0) return
        val target = owner
        scope.launch {
            withContext(Dispatchers.IO) { change(target) { list -> list.filter { it.optString("code") != "PUBLICATION_DELETED" }.forEach { it.put("error", "").put("code", "") } } }
            if (owner == target) resume()
        }
    }

    fun resume() {
        if (owner <= 0 || worker?.isActive == true) return
        val user = owner
        val epoch = generation
        val capturedCookie = cookie
        fun current() = generation == epoch && owner == user && AloOfflineAccountPolicy.matchesSession(capturedCookie, session())
        worker = scope.launch {
            var failures = 0
            try {
                withContext(Dispatchers.IO) { staging.withLock {
                    val retained = synchronized(lock) { read(user) }.let { all -> (0 until all.length()).map { all.getJSONObject(it).getString("id") }.toSet() }
                    File(context.filesDir, "alo-publications/user-$user").listFiles()?.filter {
                        it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) && it.name !in retained
                    }?.forEach { it.deleteRecursively() }
                } }
                while (current()) {
                    val all = withContext(Dispatchers.IO) { synchronized(lock) { read(user) } }
                    if (!current()) return@launch
                    val job = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("error").isBlank() }
                    if (job == null) {
                        onStatus(user, if (all.length() == 0) "" else "Публикации: ${all.length()} · ошибка отправки. Нажмите для повтора")
                        break
                    }
                    val id = job.getString("id")
                    try {
                        val uploaded = job.getJSONArray("media")
                        val files = job.getJSONArray("files")
                        val baseCount = job.optInt("baseCount", uploaded.length()).also {
                            if (!job.has("baseCount")) withContext(Dispatchers.IO) { update(user, id) { record -> record.put("baseCount", it) } }
                        }
                        for (index in (uploaded.length() - baseCount) until files.length()) {
                            if (!current()) return@launch
                            val file = files.getJSONObject(index)
                            onStatus(user, "Отправка ${if (job.getString("kind") == "post") "поста" else "истории"}: ${index + 1}/${files.length()}")
                            val plain = withContext(Dispatchers.IO) {
                                val metadata = requireNotNull(AloChunkedMediaCrypto.parseMetadata(file.getJSONObject("encryption")))
                                File.createTempFile("alo-publication-upload-", ".tmp", context.cacheDir).also { output ->
                                    try { output.outputStream().use { AloChunkedMediaCrypto.decryptTo(File(directory(user, id), "$index.bin"), metadata, it) } }
                                    catch (error: Exception) { output.delete(); throw error }
                                }
                            }
                            try {
                                val checkpoint = file.optJSONObject("checkpoint")?.let { AloResumableUploadCheckpoint(it.getString("id"), it.getLong("offset"), it.getLong("total"), it.getString("checksum")) }
                                val result = withContext(Dispatchers.IO) { api.uploadPostFile(plain, file.getString("mime"), user, checkpoint,
                                    onCheckpoint = { value -> update(user, id) { record -> record.getJSONArray("files").getJSONObject(index)
                                        .put("checkpoint", JSONObject().put("id", value.uploadId).put("offset", value.offset).put("total", value.total).put("checksum", value.checksum)) } },
                                    reporter = AloUploadReporter(shouldPause = { !current() })) }
                                if (!current()) return@launch
                                uploaded.put(mediaJson(AloMediaItem(type = file.getString("type"), url = result.url, posterUrl = "", alt = "", caption = "")))
                                withContext(Dispatchers.IO) { update(user, id) { it.put("media", uploaded) } }
                            } finally { plain.delete() }
                        }
                        if (!current()) return@launch
                        onStatus(user, "Публикация · ожидание подтверждения…")
                        val media = (0 until uploaded.length()).map { mediaValue(uploaded.getJSONObject(it)) }
                        val post = if (job.getString("kind") == "post") api.createPost(job.getString("body"), captchaToken = job.optString("captcha").takeIf { it.isNotBlank() }, media = media,
                            draftId = job.optInt("draftId").takeIf { it > 0 }, clientOperationId = id, expectedOwnerId = user) else null
                        val story = if (job.getString("kind") == "story") api.createStory(job.getString("body"), media,
                            job.getInt("lifetimeHours"), job.getString("audience"), job.getJSONArray("audienceUserIds").let { array -> (0 until array.length()).map { array.getInt(it) } }, id, user) else null
                        // Remove only after a positive object ACK. The same key is retained if
                        // saving/removal is interrupted; replay returns the original object.
                        withContext(Dispatchers.IO) {
                            change(user) { it.removeAll { record -> record.getString("id") == id } }
                            directory(user, id).deleteRecursively()
                        }
                        if (!current()) return@launch
                        post?.let { onPost(user, it, job.optInt("draftId").takeIf { value -> value > 0 }) }
                        story?.let { onStory(user, it) }
                        failures = 0
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (!current()) return@launch
                        val apiError = error as? AloApiException
                        val permanent = apiError != null && apiError.status in 400..499 && apiError.status !in setOf(401, 408, 429)
                        if (permanent) {
                            withContext(Dispatchers.IO) { update(user, id) { it.put("error", error.message ?: "Ошибка отправки").put("code", apiError?.code.orEmpty()) } }
                            if (current() && apiError != null) onChallenge(user, apiError) { token ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { update(user, id) { it.put("captcha", token).put("error", "").put("code", "") } }
                                    if (current()) resume()
                                }
                            }
                        } else {
                            onStatus(user, "Публикация сохранена · повтор при подключении…")
                            delay((1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000L))
                            failures++
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (current()) onStatus(user, "Не удалось прочитать очередь публикаций. Нажмите для повтора") }
        }
    }

    private fun directory(owner: Int, id: String): File {
        require(owner > 0 && id.matches(Regex("[a-f0-9-]{36}")))
        return File(context.filesDir, "alo-publications/user-$owner/$id")
    }

    private fun mediaJson(item: AloMediaItem): JSONObject = JSONObject().put("type", item.type).put("url", item.url)
        .put("poster", item.posterUrl).put("alt", item.alt).put("caption", item.caption).put("width", item.width).put("height", item.height)
    private fun mediaValue(item: JSONObject) = AloMediaItem(type = item.getString("type"), url = item.getString("url"),
        posterUrl = item.optString("poster"), alt = item.optString("alt"), caption = item.optString("caption"), width = item.optInt("width"), height = item.optInt("height"))

    private companion object {
        val DOCUMENT_LOCK = Any()
        val STAGING_LOCK = Mutex()
    }
}
