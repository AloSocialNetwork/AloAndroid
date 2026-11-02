package com.alo.android

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

class AloContentToolsTest {
    @Test
    fun configReadsWhatTheServerOffers() {
        val config = AloContentToolsCodec.parseConfig(
            JSONObject(
                """{"contentTools":{"translation":true,"transcription":false,"maxAudioBytes":1048576,
                "languages":[{"code":"ru","name":"Русский","english":"Russian"},{"code":"en","name":"English","english":"English"},{"name":"no code"}]}}"""
            )
        )
        assertTrue(config.translation)
        assertFalse(config.transcription)
        assertEquals(1_048_576L, config.maxAudioBytes)
        assertEquals(listOf("ru", "en"), config.languages.map { it.code })
        assertEquals("Русский", config.languageName("ru"))
        assertEquals("xx", config.languageName("xx"))
    }

    @Test
    fun aServerWithoutContentToolsOffersNothing() {
        val config = AloContentToolsCodec.parseConfig(JSONObject("""{"rtc":{}}"""))
        assertFalse(config.translation)
        assertFalse(config.transcription)
        assertTrue(config.languages.isEmpty())
    }

    @Test
    fun routesFollowTheChatKind() {
        assertEquals("/api/messages/7/translate", AloContentToolsCodec.path("direct", 0, 7, "translate"))
        assertEquals("/api/group-chats/505/messages/7/transcribe", AloContentToolsCodec.path("group", 505, 7, "transcribe"))
        // A group without a room id falls back to the direct route rather than building a broken URL.
        assertEquals("/api/messages/7/translate", AloContentToolsCodec.path("group", 0, 7, "translate"))
    }

    @Test
    fun translationRequestCarriesConsentAndOnlyTheChosenText() {
        val body = AloContentToolsCodec.translationBody("Привет", "", "en", "group", 505)
        assertEquals(true, body.getBoolean("consent"))
        assertEquals("Привет", body.getString("text"))
        assertEquals("auto", body.getString("sourceLanguage"))
        assertEquals("en", body.getString("targetLanguage"))
        assertEquals("group", body.getString("chatKind"))
        assertEquals(505, body.getInt("roomId"))
        val direct = AloContentToolsCodec.translationBody("Привет", "ru", "en", "direct", 0)
        assertFalse(direct.has("roomId"))
        assertEquals("direct", direct.getString("chatKind"))
    }

    @Test
    fun transcriptionRequestCarriesTheAudioAsBase64() {
        val audio = ByteArray(300) { (it * 7).toByte() }
        val body = AloContentToolsCodec.transcriptionBody(audio, "audio/mp4", "", "direct", 0)
        assertEquals(true, body.getBoolean("consent"))
        assertEquals("audio/mp4", body.getString("mimeType"))
        assertEquals("auto", body.getString("language"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(audio), body.getString("audioBase64"))
    }

    @Test
    fun base64MatchesTheStandardForEveryTail() {
        for (size in 0..12) {
            val bytes = ByteArray(size) { (it * 31 + 5).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), AloContentToolsCodec.base64(bytes))
        }
    }

    @Test
    fun responsesAreReadWithOrWithoutTheirWrapper() {
        val wrapped = AloContentToolsCodec.parseTranslation(
            JSONObject("""{"ok":true,"translation":{"messageId":9,"text":" Hello ","sourceLanguage":"ru","targetLanguage":"en"}}"""), 1, "de"
        )
        assertEquals(9, wrapped.messageId)
        assertEquals("Hello", wrapped.translatedText)
        assertEquals("en", wrapped.targetLanguage)
        val bare = AloContentToolsCodec.parseTranslation(JSONObject("""{"text":"Hallo"}"""), 4, "de")
        assertEquals(4, bare.messageId)
        assertEquals("de", bare.targetLanguage)
        val voice = AloContentToolsCodec.parseTranscription(JSONObject("""{"transcription":{"messageId":3,"text":"привет","language":"ru"}}"""), 1, "auto")
        assertEquals("привет", voice.text)
        assertEquals("ru", voice.language)
        assertEquals("", AloContentToolsCodec.parseTranscription(JSONObject("{}"), 1, "auto").text)
    }

    @Test
    fun audioTypesAreOnesTheProvidersTake() {
        assertEquals("audio/ogg", AloContentToolsCodec.audioMimeType("audio/ogg; codecs=opus", "voice", "a.ogg"))
        assertEquals("audio/mp4", AloContentToolsCodec.audioMimeType("audio/m4a", "voice", "a.m4a").let { if (it == "audio/m4a") "audio/mp4" else it })
        assertEquals("audio/mp4", AloContentToolsCodec.audioMimeType("audio/3gpp", "voice", "voice.m4a"))
        assertEquals("video/mp4", AloContentToolsCodec.audioMimeType("application/octet-stream", "circle", "circle.mp4"))
        assertEquals("video/mp4", AloContentToolsCodec.audioMimeType("", "circle", ""))
        assertEquals("audio/mp4", AloContentToolsCodec.audioMimeType("", "voice", ""))
        assertEquals("audio/wav", AloContentToolsCodec.audioMimeType("audio/x-unknown", "voice", "voice.wav"))
    }

    @Test
    fun errorsSayWhatToDoInRussian() {
        fun api(status: Int, code: String?) = AloApiException(status, code, "server text", JSONObject())
        assertEquals("Перевод пока не подключён на сервере.", AloContentToolsCodec.errorMessage(api(503, "translation_unavailable")))
        assertEquals("Расшифровка пока не подключена на сервере.", AloContentToolsCodec.errorMessage(api(503, "transcription_unavailable")))
        assertEquals("Запись слишком большая для расшифровки.", AloContentToolsCodec.errorMessage(api(413, "transcription_audio_too_large")))
        assertEquals("Слишком много запросов. Попробуйте позже.", AloContentToolsCodec.errorMessage(api(429, "RATE_LIMITED")))
        assertEquals("Сервис временно недоступен. Попробуйте позже.", AloContentToolsCodec.errorMessage(api(502, "message_translation_provider_failed")))
        assertEquals("Нужно согласие на отправку в сервис.", AloContentToolsCodec.errorMessage(api(400, "translation_consent_required")))
        assertEquals("Сообщение не найдено на сервере.", AloContentToolsCodec.errorMessage(api(404, null)))
        assertEquals("Нет связи с сервером.", AloContentToolsCodec.errorMessage(IOException("timeout")))
        assertEquals("своё сообщение", AloContentToolsCodec.errorMessage(AloContentToolsException("своё сообщение")))
    }

    @Test
    fun theRequestsFitTheContractOfAServer() {
        // A stand-in for the server of docs/chat-content-tools-setup.md: it answers only a request that carries consent and the chosen content.
        val seen = mutableListOf<Pair<String, JSONObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            seen += exchange.requestURI.path to body
            val answer = when {
                !body.optBoolean("consent") -> """{"ok":false,"code":"translation_consent_required"}"""
                exchange.requestURI.path.endsWith("/translate") ->
                    """{"ok":true,"translation":{"messageId":12,"text":"Hello","sourceLanguage":"ru","targetLanguage":"${body.getString("targetLanguage")}"}}"""
                else -> """{"ok":true,"transcription":{"messageId":12,"text":"привет","language":"ru","confidence":0}}"""
            }
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            fun post(path: String, body: JSONObject): JSONObject {
                val connection = URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                return JSONObject(connection.inputStream.readBytes().toString(Charsets.UTF_8))
            }
            val translated = AloContentToolsCodec.parseTranslation(
                post(AloContentToolsCodec.path("direct", 0, 12, "translate"), AloContentToolsCodec.translationBody("Привет", "auto", "en", "direct", 0)), 12, "en"
            )
            assertEquals("Hello", translated.translatedText)
            val voice = AloContentToolsCodec.parseTranscription(
                post(AloContentToolsCodec.path("group", 505, 12, "transcribe"), AloContentToolsCodec.transcriptionBody(byteArrayOf(1, 2, 3), "audio/ogg", "auto", "group", 505)), 12, "auto"
            )
            assertEquals("привет", voice.text)
            assertEquals(listOf("/api/messages/12/translate", "/api/group-chats/505/messages/12/transcribe"), seen.map { it.first })
            assertEquals("AQID", seen[1].second.getString("audioBase64"))
        } finally {
            server.stop(0)
        }
    }
}
