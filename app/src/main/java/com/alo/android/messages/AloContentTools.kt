package com.alo.android

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A language the server offers for translation (`/api/config` → `contentTools.languages`). */
data class AloContentLanguage(val code: String, val name: String, val english: String = "")

/**
 * What the server can do with a message the person picks: translate its text, turn a voice message or a video circle into text.
 * Both depend on a provider the operator configures (docs/chat-content-tools-setup.md); a client shows the buttons only when they are on.
 */
data class AloContentToolsConfig(
    val translation: Boolean = false,
    val transcription: Boolean = false,
    val maxAudioBytes: Long = AloContentToolsCodec.DEFAULT_MAX_AUDIO_BYTES,
    val languages: List<AloContentLanguage> = emptyList()
) {
    fun languageName(code: String): String =
        languages.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name ?: code
}

/** The text of a message or the audio of a voice message or circle, in the shapes `docs/chat-content-tools-setup.md` fixes. */
internal object AloContentToolsCodec {
    const val DEFAULT_MAX_AUDIO_BYTES = 24L * 1024L * 1024L
    const val MAX_TRANSLATION_CHARS = 12_000

    private val supportedAudioTypes = setOf(
        "audio/ogg", "audio/opus", "application/ogg", "audio/webm", "video/webm", "audio/mp4", "audio/x-m4a", "audio/m4a",
        "audio/aac", "video/mp4", "video/quicktime", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/flac"
    )

    /** Standard Base64 without line breaks (java.util.Base64 needs API 26, and a JVM test has no android.util.Base64). */
    fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (index + 1 < bytes.size) alphabet[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (index + 2 < bytes.size) alphabet[b2 and 63] else '=')
            index += 3
        }
        return out.toString()
    }

    fun parseConfig(response: JSONObject): AloContentToolsConfig {
        val tools = response.optJSONObject("contentTools") ?: return AloContentToolsConfig()
        val languages = tools.optJSONArray("languages") ?: JSONArray()
        return AloContentToolsConfig(
            translation = tools.optBoolean("translation", false),
            transcription = tools.optBoolean("transcription", false),
            maxAudioBytes = tools.optLong("maxAudioBytes", DEFAULT_MAX_AUDIO_BYTES).takeIf { it > 0 } ?: DEFAULT_MAX_AUDIO_BYTES,
            languages = (0 until languages.length()).mapNotNull { index ->
                val item = languages.optJSONObject(index) ?: return@mapNotNull null
                val code = item.optString("code").trim()
                if (code.isBlank()) null else AloContentLanguage(code, item.optString("name").ifBlank { code }, item.optString("english"))
            }
        )
    }

    fun path(kind: String, roomId: Int, messageId: Int, action: String): String =
        if (kind == "group" && roomId > 0) "/api/group-chats/$roomId/messages/$messageId/$action" else "/api/messages/$messageId/$action"

    fun translationBody(text: String, sourceLanguage: String, targetLanguage: String, kind: String, roomId: Int): JSONObject =
        JSONObject()
            .put("consent", true)
            .put("text", text)
            .put("sourceLanguage", sourceLanguage.ifBlank { "auto" })
            .put("targetLanguage", targetLanguage)
            .put("chatKind", if (kind == "group") "group" else "direct")
            .apply { if (kind == "group" && roomId > 0) put("roomId", roomId) }

    fun transcriptionBody(audio: ByteArray, mimeType: String, language: String, kind: String, roomId: Int): JSONObject =
        JSONObject()
            .put("consent", true)
            .put("audioBase64", base64(audio))
            .put("mimeType", mimeType)
            .put("language", language.ifBlank { "auto" })
            .put("chatKind", if (kind == "group") "group" else "direct")
            .apply { if (kind == "group" && roomId > 0) put("roomId", roomId) }

    fun parseTranslation(response: JSONObject, messageId: Int, targetLanguage: String): AloMessageTranslationResult {
        val translation = response.optJSONObject("translation") ?: response
        return AloMessageTranslationResult(
            messageId = translation.optInt("messageId", messageId),
            translatedText = translation.optString("text").trim(),
            sourceLanguage = translation.optString("sourceLanguage"),
            targetLanguage = translation.optString("targetLanguage").ifBlank { targetLanguage }
        )
    }

    fun parseTranscription(response: JSONObject, messageId: Int, language: String): AloVoiceTranscriptionResult {
        val transcription = response.optJSONObject("transcription") ?: response
        return AloVoiceTranscriptionResult(
            messageId = transcription.optInt("messageId", messageId),
            text = transcription.optString("text").trim(),
            language = transcription.optString("language").ifBlank { language }
        )
    }

    /** A MIME type the server's providers take: the file's own when it is one, else what the name or the kind of message says. */
    fun audioMimeType(rawMime: String, attachmentType: String, name: String): String {
        val mime = rawMime.substringBefore(';').trim().lowercase()
        if (mime in supportedAudioTypes) return mime
        return when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "aac" -> "audio/mp4"
            "mp4", "mov", "m4v" -> "video/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> if (attachmentType.equals("circle", true) || attachmentType.contains("video", true)) "video/mp4" else "audio/mp4"
        }
    }

    /** A short Russian sentence for what went wrong, from the server's error code. */
    fun errorMessage(error: Throwable): String {
        if (error is AloContentToolsException) return error.message
        val api = error as? AloApiException
        if (api != null) {
            val code = api.code.orEmpty().lowercase()
            return when {
                code == "translation_unavailable" -> "Перевод пока не подключён на сервере."
                code == "transcription_unavailable" -> "Расшифровка пока не подключена на сервере."
                code.endsWith("_consent_required") -> "Нужно согласие на отправку в сервис."
                code == "translation_invalid" -> "Не удалось перевести этот текст."
                code == "transcription_audio_too_large" || api.status == 413 -> "Запись слишком большая для расшифровки."
                code == "rate_limited" || api.status == 429 -> "Слишком много запросов. Попробуйте позже."
                code.endsWith("_provider_failed") || code.endsWith("_provider_invalid") || api.status == 502 ->
                    "Сервис временно недоступен. Попробуйте позже."
                api.status == 404 -> "Сообщение не найдено на сервере."
                else -> api.message.ifBlank { "Не удалось выполнить запрос." }
            }
        }
        if (error is IOException) return "Нет связи с сервером."
        return error.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить запрос."
    }
}

internal class AloContentToolsException(override val message: String) : IllegalStateException(message)

/** What the controller needs from the network; the preview backend answers without one. */
internal interface AloContentToolsBackend {
    suspend fun loadConfig(): AloContentToolsConfig
    suspend fun translate(kind: String, roomId: Int, messageId: Int, text: String, sourceLanguage: String, targetLanguage: String): AloMessageTranslationResult
    suspend fun transcribe(kind: String, roomId: Int, messageId: Int, audio: ByteArray, mimeType: String, language: String): AloVoiceTranscriptionResult
}

internal class AloContentToolsApiBackend(private val api: AloApiClient) : AloContentToolsBackend {
    override suspend fun loadConfig() = api.loadContentTools()
    override suspend fun translate(kind: String, roomId: Int, messageId: Int, text: String, sourceLanguage: String, targetLanguage: String) =
        api.translateMessageText(kind, roomId, messageId, text, sourceLanguage, targetLanguage)
    override suspend fun transcribe(kind: String, roomId: Int, messageId: Int, audio: ByteArray, mimeType: String, language: String) =
        api.transcribeMessageAudio(kind, roomId, messageId, audio, mimeType, language)
}

/** What the controller shows for one voice message or circle. */
internal sealed interface AloTranscriptState {
    data object Loading : AloTranscriptState
    /** [fresh]: it just arrived, so its text appears letter by letter; a cached one is already there. */
    data class Ready(val text: String, val expanded: Boolean, val fresh: Boolean) : AloTranscriptState
    data class Failed(val message: String) : AloTranscriptState
}

internal sealed interface AloTranslationState {
    data class Loading(val languageName: String) : AloTranslationState
    data class Ready(val text: String, val languageCode: String, val languageName: String, val fresh: Boolean) : AloTranslationState
    data class Failed(val message: String) : AloTranslationState
}

/** The one-time question before a message leaves the device for the translation or transcription service. */
internal class AloContentToolsConsentRequest(val transcription: Boolean, val proceed: () -> Unit)

/**
 * Consent, the last language and the results, kept on this device: per account and message, never on the server.
 * The consent is a device setting (Settings → Данные и память), so it is shared by every controller.
 */
internal object AloContentToolsPrefs {
    private const val PREFS = "alo-content-tools"
    private const val CONSENT = "consent_v1"
    private const val LAST_LANGUAGE = "last_language_v1"
    private const val MAX_ENTRIES = 400

    var consent by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        consent = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)
    }

    fun setConsent(context: Context, value: Boolean) {
        load(context)
        consent = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONSENT, value).apply()
    }

    fun lastLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_LANGUAGE, "").orEmpty()

    fun setLastLanguage(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_LANGUAGE, code).apply()
    }

    private fun cacheKey(accountId: Int) = "cache_v1_$accountId"

    fun readCache(context: Context, accountId: Int): LinkedHashMap<String, String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(cacheKey(accountId), null) ?: return LinkedHashMap()
        return runCatching {
            val json = JSONObject(raw)
            LinkedHashMap<String, String>().also { map -> json.keys().forEach { key -> map[key] = json.optString(key) } }
        }.getOrDefault(LinkedHashMap())
    }

    fun writeCache(context: Context, accountId: Int, entries: Map<String, String>) {
        val trimmed = entries.entries.toList().takeLast(MAX_ENTRIES)
        val json = JSONObject()
        trimmed.forEach { json.put(it.key, it.value) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(cacheKey(accountId), json.toString()).apply()
    }

    fun clearCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cache_v1_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}

/**
 * Telegram's «Aa» and «Перевести», for the chat that is open: what each voice message, circle and text shows, and the work behind it.
 * The UI reads [transcripts] and [translations]; a tap calls [toggleTranscript] / [translate]. Nothing leaves the device before [AloContentToolsPrefs.consent].
 */
internal class AloContentTools(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backend: AloContentToolsBackend,
    /** Where the plaintext of a voice message or circle comes from; a preview build has no files to read. */
    private val readAudio: (Context, AloChatMessage, Long) -> AloAudioPayload = ::readMessageAudio
) {
    var config by mutableStateOf(AloContentToolsConfig())
        private set
    val transcripts = mutableStateMapOf<Int, AloTranscriptState>()
    val translations = mutableStateMapOf<Int, AloTranslationState>()
    var consentRequest by mutableStateOf<AloContentToolsConsentRequest?>(null)
        private set
    /** The text message whose language is being chosen. */
    var languagePickerFor by mutableStateOf<AloChatMessage?>(null)

    private var accountId = 0
    private var cache = LinkedHashMap<String, String>()
    private var configLoadedAt = 0L

    init { AloContentToolsPrefs.load(context) }

    fun bindAccount(id: Int) {
        if (id == accountId) return
        accountId = id
        cache = AloContentToolsPrefs.readCache(context, id)
        transcripts.clear()
        translations.clear()
    }

    fun refreshConfig(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && configLoadedAt != 0L && now - configLoadedAt < CONFIG_TTL_MS) return
        configLoadedAt = now
        scope.launch {
            runCatching { backend.loadConfig() }
                .onSuccess { config = it }
                .onFailure { configLoadedAt = 0L }
        }
    }

    /** A voice message or circle gets the «Aa» button when the server can do it and the chat lets its content leave. */
    fun canTranscribe(message: AloChatMessage, protectedChat: Boolean): Boolean =
        config.transcription && !protectedChat && message.id > 0 && message.selfDestructSeconds <= 0

    fun canTranslate(message: AloChatMessage, protectedChat: Boolean): Boolean =
        config.translation && !protectedChat && message.id > 0 && message.body.isNotBlank() && message.selfDestructSeconds <= 0

    fun transcriptState(message: AloChatMessage): AloTranscriptState? {
        transcripts[message.id]?.let { return it }
        val cached = cache[transcriptKey(message.id)] ?: return null
        return AloTranscriptState.Ready(cached, expanded = false, fresh = false)
    }

    /** First tap: ask (once), send the audio, show the text. Later taps fold and unfold it. */
    fun toggleTranscript(message: AloChatMessage, kind: String, roomId: Int) {
        val current = transcriptState(message)
        if (current is AloTranscriptState.Ready) {
            transcripts[message.id] = current.copy(expanded = !current.expanded, fresh = false)
            return
        }
        if (current is AloTranscriptState.Loading) return
        withConsent(transcription = true) { startTranscription(message, kind, roomId) }
    }

    private fun startTranscription(message: AloChatMessage, kind: String, roomId: Int) {
        transcripts[message.id] = AloTranscriptState.Loading
        scope.launch {
            val result = runCatching {
                val payload = withContext(Dispatchers.IO) { readAudio(context, message, config.maxAudioBytes) }
                backend.transcribe(kind, roomId, message.id, payload.bytes, payload.mimeType, "auto")
            }
            result.onSuccess { value ->
                val text = value.text.trim()
                if (text.isBlank()) {
                    transcripts[message.id] = AloTranscriptState.Failed("Речь не распознана.")
                } else {
                    remember(transcriptKey(message.id), text)
                    transcripts[message.id] = AloTranscriptState.Ready(text, expanded = true, fresh = true)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                transcripts[message.id] = AloTranscriptState.Failed(AloContentToolsCodec.errorMessage(error))
            }
        }
    }

    fun dismissTranscriptError(messageId: Int) {
        if (transcripts[messageId] is AloTranscriptState.Failed) transcripts.remove(messageId)
    }

    fun translate(message: AloChatMessage, language: AloContentLanguage, kind: String, roomId: Int) {
        AloContentToolsPrefs.setLastLanguage(context, language.code)
        languagePickerFor = null
        val cached = cache[translationKey(message.id, language.code)]
        if (cached != null) {
            translations[message.id] = AloTranslationState.Ready(cached, language.code, language.name, fresh = false)
            return
        }
        withConsent(transcription = false) { startTranslation(message, language, kind, roomId) }
    }

    private fun startTranslation(message: AloChatMessage, language: AloContentLanguage, kind: String, roomId: Int) {
        translations[message.id] = AloTranslationState.Loading(language.name)
        scope.launch {
            runCatching {
                backend.translate(kind, roomId, message.id, message.body.take(AloContentToolsCodec.MAX_TRANSLATION_CHARS), "auto", language.code)
            }.onSuccess { value ->
                val text = value.translatedText.trim()
                if (text.isBlank()) {
                    translations[message.id] = AloTranslationState.Failed("Сервис не вернул перевод.")
                } else {
                    remember(translationKey(message.id, language.code), text)
                    translations[message.id] = AloTranslationState.Ready(text, language.code, language.name, fresh = true)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                translations[message.id] = AloTranslationState.Failed(AloContentToolsCodec.errorMessage(error))
            }
        }
    }

    fun hideTranslation(messageId: Int) {
        translations.remove(messageId)
    }

    /** The languages for the picker: the last one first, then the server's order. */
    fun pickerLanguages(): List<AloContentLanguage> {
        val last = AloContentToolsPrefs.lastLanguage(context)
        val all = config.languages
        return all.filter { it.code == last } + all.filter { it.code != last }
    }

    fun confirmConsent() {
        val request = consentRequest ?: return
        consentRequest = null
        AloContentToolsPrefs.setConsent(context, true)
        request.proceed()
    }

    fun declineConsent() {
        consentRequest = null
    }

    private fun withConsent(transcription: Boolean, proceed: () -> Unit) {
        if (AloContentToolsPrefs.consent) proceed() else consentRequest = AloContentToolsConsentRequest(transcription, proceed)
    }

    private fun remember(key: String, value: String) {
        cache.remove(key)
        cache[key] = value
        if (accountId > 0) AloContentToolsPrefs.writeCache(context, accountId, cache)
    }

    private fun transcriptKey(messageId: Int) = "v:$messageId"
    private fun translationKey(messageId: Int, language: String) = "t:$messageId:$language"

    private companion object {
        const val CONFIG_TTL_MS = 10 * 60 * 1000L
    }
}

internal val LocalAloContentTools = staticCompositionLocalOf<AloContentTools?> { null }

internal class AloAudioPayload(val bytes: ByteArray, val mimeType: String)

/**
 * The plaintext of a downloaded voice message or circle: its content URI (the E2EE media provider decrypts it on the way out) or a plain URL.
 * Reads at most [maxBytes]; a longer recording is refused here, before it is sent.
 */
internal fun readMessageAudio(context: Context, message: AloChatMessage, maxBytes: Long): AloAudioPayload {
    val attachment = message.attachments.firstOrNull { !it.isVirtualCard }
    val url = message.attachmentUrl.ifBlank { attachment?.url.orEmpty() }
    val name = message.attachmentName.ifBlank { attachment?.name.orEmpty() }
    val type = message.attachmentType.ifBlank { attachment?.type.orEmpty() }
    if (url.isBlank() || message.hasUndownloadedMedia()) throw AloContentToolsException("Сначала скачайте вложение.")
    val uri = Uri.parse(url)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    fun copy(stream: java.io.InputStream) {
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > maxBytes) throw AloContentToolsException("Запись слишком большая для расшифровки.")
            }
        }
    }
    val rawMime: String
    when (uri.scheme?.lowercase()) {
        "content", "file", "android.resource" -> {
            rawMime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            copy(context.contentResolver.openInputStream(uri) ?: throw IOException("Файл недоступен."))
        }
        "http", "https" -> {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                aloAuthenticatedMediaHeaders(url).forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (connection.responseCode !in 200..299) throw IOException("Файл недоступен.")
                rawMime = connection.contentType.orEmpty()
                copy(connection.inputStream)
            } finally {
                connection.disconnect()
            }
        }
        else -> throw AloContentToolsException("Вложение недоступно для расшифровки.")
    }
    if (output.size() == 0) throw AloContentToolsException("В записи нет звука.")
    return AloAudioPayload(output.toByteArray(), AloContentToolsCodec.audioMimeType(rawMime, type, name))
}

/** What a preview build answers instead of a network (`--es alo_ui_preview …`): fixed, plainly sample text, never used with an account. */
internal class AloContentToolsPreviewBackend : AloContentToolsBackend {
    override suspend fun loadConfig() = AloContentToolsConfig(
        translation = true,
        transcription = true,
        languages = listOf(
            AloContentLanguage("ru", "Русский", "Russian"), AloContentLanguage("en", "English", "English"),
            AloContentLanguage("de", "Deutsch", "German"), AloContentLanguage("es", "Español", "Spanish"),
            AloContentLanguage("fr", "Français", "French"), AloContentLanguage("uk", "Українська", "Ukrainian"),
            AloContentLanguage("tr", "Türkçe", "Turkish"), AloContentLanguage("zh", "中文", "Chinese")
        )
    )

    override suspend fun translate(kind: String, roomId: Int, messageId: Int, text: String, sourceLanguage: String, targetLanguage: String): AloMessageTranslationResult {
        delay(700)
        val translated = when (targetLanguage) {
            "ru" -> "Привет! Мы всё ещё встречаемся в шесть у набережной? Я возьму термос и плед."
            "en" -> "Hello! Are we still meeting at six by the embankment? I will bring a thermos and a blanket."
            else -> "[$targetLanguage] $text"
        }
        return AloMessageTranslationResult(messageId, translated, "en", targetLanguage)
    }

    override suspend fun transcribe(kind: String, roomId: Int, messageId: Int, audio: ByteArray, mimeType: String, language: String): AloVoiceTranscriptionResult {
        delay(1_400)
        return AloVoiceTranscriptionResult(
            messageId, "Привет! Это пример расшифровки голосового сообщения. Встречаемся в шесть у набережной, возьми, пожалуйста, термос.", "ru"
        )
    }
}
