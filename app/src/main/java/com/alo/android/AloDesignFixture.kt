package com.alo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Explicit, offline UI fixture for design screenshots, the twin of iOS's MessengerDesignFixture:
 * the same people, posts and stories, so both clients can be compared screen by screen.
 * Debug builds only, started with `--es alo_ui_preview <content>`; never an account, never persisted.
 */
internal object AloDesignFixture {
    const val EXTRA = "alo_ui_preview"
    const val ME_ID = 990001

    /** Locally drawn test media, never downloaded or presented as user content. */
    fun sampleImageUri(context: Context): String {
        val directory = File(context.cacheDir, "alo-ui-fixture").apply { mkdirs() }
        val file = File(directory, "sample.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF0A84FF.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("ALO", 30f, 100f, paint)
            canvas.drawText("UI TEST", 30f, 148f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "file://${file.absolutePath}"
    }

    /** A few seconds of soft tones as a voice message to play, drawn locally like [sampleImageUri]. */
    fun sampleVoiceUri(context: Context): String {
        val directory = File(context.cacheDir, "alo-ui-fixture").apply { mkdirs() }
        val file = File(directory, "voice.wav")
        if (!file.exists()) {
            val rate = 16_000
            val seconds = 7
            val samples = ShortArray(rate * seconds) { index ->
                val t = index / rate.toDouble()
                val pulse = 0.5 + 0.5 * Math.sin(t * 5.1)
                (Math.sin(2 * Math.PI * (220 + 60 * Math.sin(t * 1.7)) * t) * 9_000 * pulse).toInt().toShort()
            }
            val data = java.nio.ByteBuffer.allocate(44 + samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples.size * 2)
            samples.forEach { data.putShort(it) }
            file.writeBytes(data.array())
        }
        return "file://${file.absolutePath}"
    }

    /** A short video for the circle (`adb push` it as `alo-ui-fixture/circle.mp4` into the app's cache); without it the circle has no picture. */
    fun sampleCircleUri(context: Context): String {
        val file = File(File(context.cacheDir, "alo-ui-fixture"), "circle.mp4")
        return if (file.exists()) "file://${file.absolutePath}" else ""
    }

    private fun ago(seconds: Long): String = Instant.now().minusSeconds(seconds).toString()

    private fun person(id: Int, name: String, username: String = "", verified: Boolean = false) =
        JSONObject().put("id", id).put("name", name).put("username", username).put("verified", verified)

    private val alex get() = person(990002, "Александр", "alex")
    private val maria get() = person(990003, "Мария", "maria")

    fun bootstrap(apiClient: AloApiClient, content: String, imageUri: String): AloBootstrapData {
        val authors = listOf(person(ME_ID, "makkarov", "makkarov", verified = true), alex, maria)
        val bodies = listOf(
            "Осень в городе. Сохраняю эти моменты.",
            "Лучшие идеи приходят после прогулки.\nА вы где находите вдохновение?",
            "Небольшая подборка фотографий за неделю."
        )
        val posts = JSONArray()
        repeat(12) { index ->
            val post = JSONObject()
                .put("id", 990400 + index)
                .put("body", bodies[index % 3])
                .put("likeCount", if (index == 0) 42 else 18)
                .put("commentCount", if (index == 0) 8 else 12)
                .put("repostCount", if (index == 0) 3 else 2)
                .put("viewCount", if (index == 0) 1200 else 640)
                .put("viewerLiked", index == 0)
                .put("author", authors[index % 3])
                .put("feedReason", listOf("own", "friend", "popular")[index % 3])
                .put("createdAt", ago(720L + index * 960L))
            if (index % 3 != 1) {
                post.put("media", JSONArray().apply {
                    repeat(if (index % 3 == 0) 1 else 2) {
                        put(JSONObject().put("type", "image").put("url", imageUri).put("poster", imageUri))
                    }
                })
            }
            if (content == "feed-albums") {
                // Photos of every shape, sized by the server as real uploads are, for the album mosaic (as on iOS).
                val shapes = listOf(
                    listOf(1080 to 1440), listOf(1920 to 1080), listOf(1080 to 1440, 1920 to 1080),
                    listOf(1080 to 1920, 1080 to 1080, 1440 to 1080), listOf(1920 to 1080, 1080 to 1350, 1080 to 1350, 1080 to 1350),
                    listOf(1080 to 1080, 1080 to 1440, 1920 to 1080, 1080 to 1080, 1440 to 1080), listOf(3000 to 1000), listOf(1080 to 1920)
                )
                post.put("media", JSONArray().apply {
                    shapes[index % shapes.size].forEach { (w, h) ->
                        put(JSONObject().put("type", "image").put("url", imageUri).put("poster", imageUri).put("width", w).put("height", h))
                    }
                })
            }
            posts.put(post)
        }
        val subs = JSONArray().apply { for (i in 9 until 12) put(posts.getJSONObject(i)) }

        val chatBodies = listOf(
            "Привет! Посмотрел фотографии с поездки?",
            "Да, очень красиво. Особенно тот кадр у озера.",
            "Давай на выходных выберемся ещё раз. Можно взять велосипеды и поехать по набережной.",
            "Отличная идея! В субботу после обеда я свободен.",
            "Тогда встречаемся в два у парка?",
            "Договорились. Возьму кофе с собой ☕️",
            "До встречи!"
        )
        val items = JSONArray()
        chatBodies.forEachIndexed { index, body ->
            val fromPeer = index % 2 == 0
            items.put(
                JSONObject()
                    .put("id", 990100 + index)
                    .put("senderId", if (fromPeer) 990002 else ME_ID)
                    .put("recipientId", if (fromPeer) ME_ID else 990002)
                    .put("body", body)
                    .put("createdAt", "2026-09-05T10:0$index:00Z")
                    .put("readAt", "2026-09-05T10:07:00Z")
            )
        }
        if (content == "formatting") {
            // A quote and a code block, parsed the way the composer sends them (iOS's fixture).
            val quoted = AloMessageMarkup.parse("> Встречаемся в **семь**, не опаздывай\nХорошо, буду вовремя ||сюрприз||")
            val code = AloMessageMarkup.parse("Вот функция:\n```swift\nfunc hello() {\n    print(\"Привет, Alo\")\n}\n```")
            fun message(id: Int, result: AloMessageMarkupResult, mine: Boolean) = JSONObject()
                .put("id", id).put("senderId", if (mine) ME_ID else 990002).put("recipientId", if (mine) 990002 else ME_ID)
                .put("body", result.body).put("createdAt", "2026-09-05T10:08:00Z")
                .put("entities", JSONArray(result.entities.map { it.toJson() }))
            while (items.length() > 0) items.remove(0)
            items.put(message(990200, quoted, mine = false)).put(message(990201, code, mine = true))
        }
        val peer = person(990002, "Александр", "alex_ui_fixture")
            .put("online", false).put("lastSeenAt", "2026-09-05T09:40:00Z")
        val conversations = JSONArray().put(
            JSONObject().put("peerId", 990002).put("kind", "direct").put("peer", peer)
                .put("lastBody", "До встречи!").put("lastAt", "2026-09-05T10:05:00Z")
        )
        if (content == "chat-list") {
            val names = listOf("Мария", "Команда Alo", "Илья", "Даша", "Анна", "Семья", "Работа", "Макс", "Оля", "Университет", "Соседи")
            val texts = listOf(
                "Привет! Когда увидимся на выходных? Можно сходить в кино или просто прогуляться по набережной.",
                "Новая версия уже в TestFlight", "Спасибо!", "Фото с поездки 📷", "Договорились, до встречи",
                "Не забудь купить хлеб", "Созвон в 15:00", "Ок", "Посмотри это видео", "Расписание на завтра", "Отопление включили"
            )
            names.forEachIndexed { index, name ->
                val group = index == 5 || index == 9
                conversations.put(
                    JSONObject().put("peerId", 990100 + index).put("kind", if (group) "group" else "direct")
                        .put("roomId", if (group) 500 + index else 0)
                        .put("unreadCount", mapOf(0 to 3, 2 to 1, 4 to 12, 5 to 2)[index] ?: 0)
                        .put("peer", person(990100 + index, name, "fixture_$index"))
                        .put("lastBody", texts[index]).put("lastAt", ago(index * 5400L + 600L))
                        .put("lastMessage", JSONObject().put("id", 7000 + index).put("senderId", 990100 + index))
                )
            }
        }
        val notificationTypes = listOf("comment", "like", "friend_request", "message", "repost", "comment_like")
        val notifications = JSONArray()
        if (content == "notifications") {
            val texts = listOf(
                "прокомментировал ваш пост", "нравится ваша фотография", "хочет добавить вас в друзья",
                "ответил вам", "сделал репост вашей записи", "нравится ваш комментарий"
            )
            val actors = listOf("Александр", "Мария", "Даша", "Илья", "Александр", "Анна")
            notificationTypes.forEachIndexed { index, type ->
                notifications.put(
                    JSONObject().put("id", 990900 + index).put("type", type).put("text", texts[index])
                        .put("actor", person(990002 + index, actors[index]))
                        .put("createdAt", if (index < 4) ago(300L + index * 600L) else ago(86_400))
                        .put("isRead", index >= 4)
                        .put("friendRequestActionable", type == "friend_request")
                        .put("entityType", if (type == "friend_request") "friend_request" else "post")
                        .put("entityId", if (type == "friend_request") 990012 else 990400)
                )
            }
        }
        if (content == "appeal") {
            // A moderation decision about you, with «Обжаловать».
            notifications.put(
                JSONObject().put("id", 990950).put("type", "moderation")
                    .put("text", "Модераторы удалили вашу публикацию: спам. Если это ошибка, решение можно обжаловать.")
                    .put("createdAt", ago(600)).put("isRead", false)
                    .put("entityType", "moderation").put("entityId", 14)
            )
        }
        val data = JSONObject()
            .put("me", person(ME_ID, "Тест интерфейса", "ui_fixture").apply {
                // The same profile as iOS's fixture.
                put("bio", "Пишу про путешествия и мобильную разработку. Москва → Байкал → снова Москва.")
                put("registeredLabel", "март 2025 г.")
                put("coverStyle", "violet")
                if (content.startsWith("moderation")) {
                    put("adminScope", JSONObject().put("can", JSONObject().put("reports", true).put("moderation", true)).put("hierarchy", "owner"))
                }
            })
            .put("feed", JSONObject().put("popular", posts).put("subs", subs))
            .put("profile", JSONObject().put("posts", posts)
                .put("stats", JSONObject().put("friendsCount", 128).put("followersCount", 2_431).put("followingCount", 312)))
            .put("messages", JSONObject().put("conversations", conversations).put("items", items).put("activePeerId", 990002))
            .put("search", JSONObject().put("users", JSONArray().put(peer)))
            .put("notifications", notifications)
        return apiClient.parseBootstrapResponse(JSONObject().put("data", data))
    }

    /** iOS's preview statistics: steady growth with a spike every fourth day, and eight recent posts. */
    fun channelStatsService(): AloChannelStatsService = AloChannelStatsService { _, days ->
        val today = java.time.LocalDate.now()
        AloChannelStats(
            days = days, subscribers = 12_840, posts = 18, views = 41_200, likes = 2_310, comments = 288, reposts = 402,
            growth = (0 until days).map { index ->
                today.minusDays(index.toLong()).toString() to (3 + (days - index) / 3 + if (index % 4 == 0) 6 else 0)
            },
            publications = (0 until 8).map { index ->
                AloChannelStats.Publication(
                    postId = 990400 + index, publishedAt = Instant.now().minusSeconds(index * 86_400L * 3).toString(),
                    views = 2_400 - index * 180, likes = 140 - index * 9, comments = 22 - index, reposts = 31 - index * 2
                )
            }
        )
    }

    /** A channel this account runs, for its admin tools and statistics (iOS's -ALO_UI_CHANNEL_PROFILE). */
    fun channelProfile(posts: List<AloPost>) = AloViewedProfile(
        kind = "channel", id = 990600, title = "Alo Новости", username = "alo_news", avatarUrl = "", avatarGallery = emptyList(),
        verified = false, coverStyle = "", coverImageUrl = "", status = "", meta = "",
        description = "Обновления приложения и истории команды.",
        metrics = listOf(AloProfileMetric("подписчиков", "12 840"), AloProfileMetric("публикаций", "18")),
        posts = posts.take(3), canManage = true, isOwner = true
    )

    /** Someone else's profile as people see it: not yet a friend, who can be written to. */
    fun userProfile(posts: List<AloPost>) = AloViewedProfile(
        kind = "user", id = 990011, title = "Александр Петров", username = "alex", avatarUrl = "", avatarGallery = emptyList(),
        verified = true, coverStyle = "ocean", coverImageUrl = "", status = "", meta = "12 сентября 2026",
        description = "Фотографирую город по утрам и записываю, что из этого вышло.",
        metrics = listOf(AloProfileMetric("подписок", "86"), AloProfileMetric("подписчиков", "1 204"), AloProfileMetric("друзей", "34")),
        posts = posts.take(3), friendStatus = "none", canSendFriendRequest = true, canMessage = true
    )

    /** A code that a «new device» scans on the second check, so the confirmation can be seen offline. */
    fun deviceLinkService(): AloDeviceLinkService = object : AloDeviceLinkService {
        var checks = 0
        override suspend fun start(): AloQrLoginSession {
            checks = 0
            val id = "fixtureDeviceLink" + "0".repeat(15)
            val secret = "fixtureSecret" + "x".repeat(30)
            return AloQrLoginSession(id, secret, "alo://login/link/$id/$secret", "pending", Instant.now().plusSeconds(120).toString())
        }
        override suspend fun status(session: AloQrLoginSession) = session.copy(claimed = ++checks >= 3)
        override suspend fun approve(session: AloQrLoginSession) = kotlinx.coroutines.delay(600)
        override suspend fun deny(session: AloQrLoginSession) = Unit
    }

    /** iOS's preview reports, appeals, audit log and campaigns; decisions are kept in memory only. */
    fun moderationService(imageUri: String = ""): AloModerationService = object : AloModerationService {
        val me = summary(ME_ID, "Тест интерфейса")
        val spammer = summary(990013, "Быстрые деньги", "money_fast")

        override suspend fun reports() = listOf(
            AloModerationReport(
                id = 14, reporter = summary(990010, "Маша", "masha"), targetType = "post", targetId = 990402, targetUserId = null,
                targetPostBody = "Заработок без вложений! Пиши в личку 💸", targetPostMediaCount = 1, reason = "Спам",
                details = "Одна и та же ссылка во всех комментариях", status = "pending", resolutionAction = "", createdAt = ago(1_800),
                target = AloReportedContent(
                    kind = "post", id = 990402, deleted = false, author = spammer,
                    body = "Заработок без вложений! Пиши в личку 💸 Осталось три места на марафон.",
                    media = if (imageUri.isBlank()) emptyList() else listOf(image(imageUri).copy(width = 1080, height = 1350)),
                    likeCount = 3, commentCount = 41, createdAt = ago(5_400)
                ),
                otherPendingCount = 2
            ),
            AloModerationReport(
                id = 15, reporter = summary(990012, "Даша", "dasha"), targetType = "user", targetId = 990013, targetUserId = 990013,
                targetPostBody = "", targetPostMediaCount = 0, reason = "Оскорбления", details = "", status = "pending",
                resolutionAction = "", createdAt = ago(7_200),
                target = AloReportedContent(kind = "user", id = 990013, deleted = false, user = spammer,
                    bio = "Научу зарабатывать от 100 000 в месяц")
            ),
            AloModerationReport(
                id = 16, reporter = summary(990011, "Александр", "alex"), targetType = "comment", targetId = 77, targetUserId = 990013,
                targetPostBody = "", targetPostMediaCount = 0, reason = "Спам", details = "", status = "pending",
                resolutionAction = "", createdAt = ago(9_000),
                target = AloReportedContent(kind = "comment", id = 77, deleted = false, postId = 990400, author = spammer,
                    body = "Переходи по ссылке в профиле, там всё объясню 👇")
            )
        )

        override suspend fun resolveReport(report: AloModerationReport, approve: Boolean, action: AloModerationAction, note: String, blockHours: Int) = Unit

        private fun staff(id: Int, name: String, username: String, level: AloStaffHierarchy, permissions: List<String>,
                          status: String = "active", until: String = "") =
            AloStaffMember(summary(id, name, username), permissions, level, level == AloStaffHierarchy.Owner, status, until, "")

        val allRights = AloModeratorPermission.entries.map { it.raw }
        val team = listOf(
            staff(ME_ID, "Тест интерфейса", "ui_fixture", AloStaffHierarchy.Owner, allRights),
            staff(990011, "Александр", "alex", AloStaffHierarchy.Admin, allRights),
            staff(990010, "Маша", "masha", AloStaffHierarchy.LeadModerator, listOf("reports.review", "moderation.manage")),
            staff(990012, "Даша", "dasha", AloStaffHierarchy.Moderator, listOf("reports.review")),
            staff(990014, "Слава", "slava", AloStaffHierarchy.Moderator, listOf("reports.review", "stats.view"))
        )
        val people = listOf(
            staff(990003, "Мария", "maria", AloStaffHierarchy.User, emptyList()),
            staff(990013, "Быстрые деньги", "money_fast", AloStaffHierarchy.User, emptyList(), "blocked",
                java.time.Instant.now().plusSeconds(86_400L * 6).toString())
        )

        override suspend fun team() = team to allRights
        override suspend fun searchStaff(query: String) = (team + people).filter {
            it.user.name.contains(query, ignoreCase = true) || it.user.username.contains(query, ignoreCase = true)
        }
        override suspend fun setModeratorPermissions(member: AloStaffMember, permissions: List<String>) = Unit
        override suspend fun setAccountStatus(member: AloStaffMember, status: String, hours: Int, reason: String) = member
        override suspend fun setAdministrator(member: AloStaffMember, granted: Boolean) = member

        override suspend fun appeals() = listOf(
            AloModerationAppeal(
                id = 1, kind = "account", reportId = 12, text = "Это был анонс нашей встречи, а не реклама. Прошу снять ограничение.",
                status = "pending", decisionNote = "", action = "block", accountStatus = "", createdAt = ago(5_400),
                user = summary(990011, "Александр", "alex")
            )
        )

        override suspend fun resolveAppeal(appeal: AloModerationAppeal, overturn: Boolean) = Unit

        override suspend fun audit(before: Int?): Pair<List<AloModerationAuditEntry>, Int?> {
            val rows = listOf(
                Triple("report_rejected", "жалоба #14 · нарушений нет", "Маша"),
                Triple("appeal_overturned", "доступ к аккаунту восстановлен", "Александр"),
                Triple("block", "жалоба #12 · реклама", "Александр"),
                Triple("delete_post", "жалоба #9 · спам-ссылки", "Даша")
            )
            return rows.mapIndexed { index, (action, details, target) ->
                AloModerationAuditEntry(40 - index, action, details, ago((index + 1) * 3_000L), me, summary(990010 + index, target))
            } to null
        }

        override suspend fun campaigns() = listOf(
            AloAdCampaign(
                AloAd(1, "feed", "Реклама", "Alo", "Истории в Alo", "Делитесь моментами на 24 часа.", "Попробовать", "alo://stories/new", ""),
                name = "Запуск историй", status = "active", impressionLimit = 0,
                impressions = 12_480, reach = 4_210, clicks = 386, hides = 41, ctr = 3.1
            ),
            AloAdCampaign(
                AloAd(2, "channel", "Реклама", "Alo", "", "Подпишитесь на каналы вашего вуза.", "Подробнее", "https://alo.example/channels", ""),
                name = "Каналы для учёбы", status = "paused", impressionLimit = 0,
                impressions = 3_020, reach = 1_870, clicks = 75, hides = 12, ctr = 2.5
            )
        )

        override suspend fun saveCampaign(id: Int?, fields: AloAdCampaignFields) = AloAdCampaign(
            AloAd(id ?: 3, fields.placement, "Реклама", fields.advertiser, fields.title, fields.body, fields.ctaLabel, fields.ctaUrl, ""),
            name = fields.name, status = fields.status, impressionLimit = fields.impressionLimit,
            impressions = 0, reach = 0, clicks = 0, hides = 0, ctr = 0.0
        )

        override suspend fun appealDecision(reportId: Int, text: String) = Unit
    }

    private fun summary(id: Int, name: String, username: String = "") =
        AloEntitySummary(id = id, name = name, username = username, avatarUrl = "", verified = false)

    private fun image(uri: String) =
        AloMediaItem(type = "image", url = uri, posterUrl = uri, alt = "", caption = "")

    fun stories(imageUri: String): List<AloStoryGroup> {
        val own = AloStoryGroup(
            user = summary(ME_ID, "Тест интерфейса", "ui_fixture"),
            hasUnseen = false,
            stories = listOf(
                AloStory(
                    id = 990799, userId = ME_ID, body = "", media = listOf(image(imageUri)),
                    createdAt = ago(3_600), expiresAt = ago(-82_800), viewerHasSeen = true, viewCount = 4
                )
            )
        )
        return listOf(own) + listOf("Маша", "Александр", "Даша", "Друзья").mapIndexed { index, name ->
            AloStoryGroup(
                user = summary(990010 + index, name),
                hasUnseen = true,
                stories = listOf(
                    AloStory(
                        id = 990800 + index, userId = 990010 + index, body = "", media = listOf(image(imageUri)),
                        createdAt = ago(0), expiresAt = ago(-86_400), viewerHasSeen = false, viewCount = 0
                    )
                )
            )
        }
    }

    val feedAds: List<AloAd>
        get() = listOf(
            AloAd(
                id = 1, placement = "feed", label = "Реклама", advertiser = "Alo", title = "Истории в Alo",
                body = "Делитесь моментами, которые исчезнут через сутки. Выберите, кто их увидит.",
                ctaLabel = "Попробовать", ctaUrl = "alo://stories/new", why = ""
            )
        )
    const val FEED_AD_EVERY = 3

    /** iOS's chat-list fixture: one pinned chat, two archived, chats 0, 2 and 4 with an unseen message. */
    fun installChatList(prefs: AloChatListPrefs, tracker: AloUnreadTracker) {
        prefs.replace(ME_ID, pinned = listOf("direct:990101"), archived = listOf("direct:990108", "direct:990110"))
        tracker.replaceSeen((0 until 11).associate { index ->
            "$ME_ID:direct:${990100 + index}" to if (index % 2 == 0 && index < 5) 7000 + index - 1 else 7000 + index
        })
    }

    fun callHistory(): List<AloCallHistoryItem> {
        fun item(id: Int, name: String, peer: Int, outgoing: Boolean, video: Boolean, outcome: String, secondsAgo: Long, duration: Int) =
            AloCallHistoryItem(id, outgoing, video, outcome, ago(secondsAgo), duration, summary(peer, name, "u$peer"))
        return listOf(
            item(6, "Мария", 990100, false, false, "missed", 600, 0),
            item(5, "Александр", 990002, true, true, "completed", 3_600, 754),
            item(4, "Илья", 990103, false, false, "completed", 7_200, 63),
            item(3, "Даша", 990104, true, false, "cancelled", 90_000, 0),
            item(2, "Мария", 990100, false, true, "declined", 180_000, 0),
            item(1, "Анна", 990105, true, false, "completed", 400_000, 1_820)
        )
    }

    fun storyViewers(): List<AloStoryViewerEntry> =
        listOf(Triple("Маша", "masha", 2L), Triple("Александр", "alex", 14L), Triple("Даша", "dasha", 47L), Triple("Слава", "slava", 130L))
            .mapIndexed { index, (name, username, minutes) ->
                AloStoryViewerEntry(summary(990010 + index, name, username), ago(minutes * 60))
            }

    fun friends(): List<AloEntitySummary> =
        listOf("Александр" to "alex", "Даша" to "dasha", "Маша" to "masha", "Слава" to "slava")
            .mapIndexed { index, (name, username) -> summary(990010 + index, name, username) }

    /** Seven past stories, every third a text story, as on iOS. */
    fun storyArchive(imageUri: String): List<AloStory> = (0 until 7).map { index ->
        val created = (index + 1) * 86_400L * 2
        AloStory(
            id = 990700 + index, userId = ME_ID,
            body = if (index % 3 == 2) "Вечер у моря" else "",
            media = if (index % 3 == 2) emptyList() else listOf(image(imageUri)),
            createdAt = ago(created), expiresAt = ago(created - 86_400L),
            viewerHasSeen = true, viewCount = 3 + index
        )
    }

    fun highlights(archive: List<AloStory>): List<AloStoryHighlight> =
        listOf(AloStoryHighlight(id = 1, title = "Лето", coverStoryId = archive.first().id, stories = archive.take(2)))

    /** Thirty days of reach that grow towards today, against a quieter month before (iOS's fixture). */
    fun creatorStats(days: Int, feed: List<AloPost>): AloCreatorStats {
        val today = java.time.LocalDate.now()
        val daily = (0 until days).map { index ->
            val wave = (40 * (1 + kotlin.math.sin(index / 3.0))).toInt()
            AloCreatorStats.Day(today.minusDays((days - 1 - index).toLong()).toString(), 60 + index * 9 + wave, 6 + index / 2 + wave / 8, 1 + index / 6)
        }
        return AloCreatorStats(
            days = days,
            views = AloCreatorStats.Metric(daily.sumOf { it.views }, 3_900),
            likes = AloCreatorStats.Metric(daily.sumOf { it.likes }, 520),
            comments = AloCreatorStats.Metric(daily.sumOf { it.comments }, 61),
            reposts = AloCreatorStats.Metric(34, 41),
            followers = AloCreatorStats.Metric(58, 31),
            engagementRate = 12.4,
            postsInPeriod = 9,
            allTime = AloCreatorStats.AllTime(46, 48_200, 5_310, 704, 212, 1_284),
            daily = daily,
            topPosts = feed.filter { it.author.id == ME_ID }.take(3)
        )
    }

    fun postDrafts(imageUri: String): List<AloPostDraft> = listOf(
        AloPostDraft(990901, "Черновик про поездку на Байкал: лёд, ветер и самый прозрачный…", listOf(image(imageUri)), ago(1_800)),
        AloPostDraft(990902, "Идеи для следующего поста", emptyList(), ago(86_400L * 2))
    )

    fun comments(): List<AloPostComment> {
        val alex = summary(990002, "Александр", "alex")
        val maria = summary(990003, "Мария", "maria")
        return listOf(
            AloPostComment(990700, "Отличный кадр! Где это снято?", emptyList(), ago(3_600),
                likeCount = 2, viewerLiked = false, author = alex),
            AloPostComment(990703, "@alex похоже на Карелию 🌲", emptyList(), ago(3_000),
                likeCount = 1, viewerLiked = false, author = maria, parentId = 990700, replyTo = alex),
            AloPostComment(990704, "@maria угадала!", emptyList(), ago(2_400),
                likeCount = 0, viewerLiked = false, author = alex, parentId = 990700, replyTo = maria),
            AloPostComment(990705, "Очень атмосферно, сохранила себе.", emptyList(), ago(1_200),
                likeCount = 5, viewerLiked = true, author = maria)
        )
    }

    const val GROUP_ROOM_ID = 505
    const val GROUP_PINNED_MESSAGE_ID = 990301
    private const val GROUP_OWNER_ID = 990010
    private const val GROUP_ADMIN_ID = 990011

    private val groupPeople = listOf(
        Triple(ME_ID, "Тест интерфейса", "member"),
        Triple(GROUP_OWNER_ID, "Маша", "owner"),
        Triple(GROUP_ADMIN_ID, "Александр", "admin"),
        Triple(990012, "Даша", "member"),
        Triple(990013, "Слава", "member")
    )

    /** «group-chat»: the members of a group where several people write, one of them the owner and one an admin with a title. */
    fun groupChatMembers(): List<AloGroupMember> = groupPeople.map { (id, name, role) ->
        AloGroupMember(
            AloEntitySummary(id = id, name = name, username = "", avatarUrl = "", verified = false),
            role = role,
            adminTitle = if (id == GROUP_ADMIN_ID) "Модератор" else ""
        )
    }

    /**
     * «group-chat»: runs of two and three messages from one sender (mine too), the owner and an admin, a quote, reactions that wrap, a picture: everything a busy group chat shows around its bubbles.
     */
    /**
     * «group-chat-long»: the same busy group with a screenful-and-a-half of older talk above it and a late quote of the very first message, so the «down» button,
     * the jump-to-quote highlight and «печатает» can be seen on a screen.
     */
    const val LONG_CHAT_FIRST_ID = 990100

    fun groupChatLongItems(imageUri: String): List<AloChatMessage> {
        val talk = listOf(
            "Всем привет!", "Кто завтра едет?", "Я могу после обеда", "А погода какая?", "Обещают солнце", "Тогда берём пледы",
            "Кто за рулём?", "Могу я", "Отлично", "Во сколько встречаемся?", "В два у метро", "Принято", "Я чуть опоздаю",
            "Ничего страшного", "Захватите воды", "Хорошо", "Положу в рюкзак", "Фотоаппарат кто берёт?", "Я возьму",
            "Супер", "Тогда до завтра", "До завтра!", "Спокойной ночи", "И вам", "Проснулись?", "Уже в пути", "Ждём у входа"
        )
        val senders = listOf(990010, 990011, 990012, 990013, ME_ID)
        val old = talk.mapIndexed { index, body ->
            val senderId = senders[index % senders.size]
            val sender = groupPeople.first { it.first == senderId }.let { (_, name, _) ->
                AloConversationPeer(id = senderId, name = name, username = "", avatarUrl = "", verified = false)
            }
            AloChatMessage(
                id = LONG_CHAT_FIRST_ID + index, roomId = GROUP_ROOM_ID, senderId = senderId, recipientId = 0, body = body,
                attachmentUrl = "", attachmentType = "", attachmentName = "",
                createdAt = "2026-09-04T%02d:%02d:00Z".format(8 + index / 4, (index % 4) * 15), editedAt = "", readAt = "2026-09-05T10:30:00Z",
                sender = sender
            )
        }
        val quote = groupChatItems(imageUri).last().let { last ->
            last.copy(id = 990320, body = "Это я про самое первое сообщение", createdAt = "2026-09-05T10:07:00Z", replyToMessageId = LONG_CHAT_FIRST_ID, reactions = emptyList(), attachments = emptyList(), attachmentUrl = "", attachmentType = "", attachmentName = "")
        }
        return old + groupChatItems(imageUri) + quote
    }

    /**
     * «chat-tools»: what «Перевести» and «Aa» work on: a text in another language, a voice message and a circle (the answers come from
     * [AloContentToolsPreviewBackend], since a preview has no server).
     */
    fun chatToolsItems(voiceUri: String, circleUri: String): List<AloChatMessage> {
        fun peer(id: Int) = groupPeople.first { it.first == id }.let { (_, name, _) ->
            AloConversationPeer(id = id, name = name, username = "", avatarUrl = "", verified = false)
        }
        fun message(id: Int, senderId: Int, body: String, minute: Int, type: String = "", name: String = "", url: String = "") = AloChatMessage(
            id = id, roomId = GROUP_ROOM_ID, senderId = senderId, recipientId = 0, body = body,
            attachmentUrl = url, attachmentType = type, attachmentName = name,
            attachments = if (type.isBlank()) emptyList() else listOf(AloOutgoingMessageAttachment(url = url, type = type, name = name)),
            createdAt = "2026-09-05T10:%02d:00Z".format(minute), editedAt = "", readAt = "2026-09-05T10:30:00Z",
            sender = peer(senderId), senderAdminTitle = if (senderId == GROUP_ADMIN_ID) "Модератор" else ""
        )
        return listOf(
            message(990400, 990012, "Hello! Are we still meeting at six near the embankment? I will bring a thermos and a blanket.", 1),
            message(990401, GROUP_OWNER_ID, "", 2, type = "voice", name = "voice.wav", url = voiceUri),
            message(990402, 990013, "", 3, type = "circle", name = "circle.mp4", url = circleUri),
            message(990403, ME_ID, "", 4, type = "voice", name = "voice.wav", url = voiceUri),
            message(990404, ME_ID, "Договорились ✅", 5)
        )
    }

    fun groupChatItems(imageUri: String): List<AloChatMessage> {
        fun peer(id: Int) = groupPeople.first { it.first == id }.let { (_, name, _) ->
            AloConversationPeer(id = id, name = name, username = "", avatarUrl = "", verified = false)
        }
        fun message(
            id: Int,
            senderId: Int,
            body: String,
            minute: Int,
            replyTo: Int = 0,
            reactions: List<AloMessageReaction> = emptyList(),
            image: Boolean = false
        ) = AloChatMessage(
            id = id, roomId = GROUP_ROOM_ID, senderId = senderId, recipientId = 0, body = body,
            attachmentUrl = if (image) imageUri else "", attachmentType = if (image) "image" else "",
            attachmentName = if (image) "sample.png" else "",
            attachments = if (image) listOf(AloOutgoingMessageAttachment(url = imageUri, type = "image", name = "sample.png")) else emptyList(),
            createdAt = "2026-09-05T10:%02d:00Z".format(minute), editedAt = "", readAt = "2026-09-05T10:30:00Z",
            sender = peer(senderId), senderAdminTitle = if (senderId == GROUP_ADMIN_ID) "Модератор" else "",
            reactions = reactions, replyToMessageId = replyTo
        )
        return listOf(
            message(990300, 990012, "", 0, image = true,
                reactions = listOf(AloMessageReaction("❤️", 3, false), AloMessageReaction("😂", 1, false))),
            message(GROUP_PINNED_MESSAGE_ID, GROUP_OWNER_ID,
                "Значит так: успокоились все. Задача не в том, чтобы мериться шутками, а в том, чтобы выбрать маршрут на субботу. Предлагаю набережную, велосипеды и кофе.",
                1, reactions = listOf(
                    AloMessageReaction("❤️", 3, true), AloMessageReaction("🔥", 2, false), AloMessageReaction("👍", 1, false),
                    AloMessageReaction("😂", 4, false), AloMessageReaction("😮", 1, false), AloMessageReaction("🎉", 2, false)
                )),
            message(990302, GROUP_ADMIN_ID, "Мы и так уже всё решили", 2,
                reactions = listOf(
                    AloMessageReaction("👍", 1, false, users = listOf(peer(GROUP_OWNER_ID))),
                    AloMessageReaction("🔥", 2, false, users = listOf(peer(990012), peer(990013))),
                    AloMessageReaction("😂", 3, false, users = listOf(peer(990012), peer(990013), peer(GROUP_OWNER_ID)))
                )),
            message(990303, GROUP_ADMIN_ID, "Я за набережную", 2, replyTo = GROUP_PINNED_MESSAGE_ID),
            message(990304, 990013, "Развеял обстановку 😄", 3, replyTo = 990302,
                reactions = listOf(AloMessageReaction("❤️", 2, false))),
            message(990305, ME_ID, "Отлично, я с вами! Вылетаем в 14:00", 4, replyTo = GROUP_PINNED_MESSAGE_ID),
            message(990308, ME_ID, "Возьму кофе с собой ☕️", 4,
                reactions = listOf(AloMessageReaction("❤️", 2, false), AloMessageReaction("👍", 1, true))),
            message(990306, 990012, "Беру термос и плед", 5, replyTo = 990308),
            message(990309, 990012, "И пирожки", 5),
            message(990310, 990012, "Кому-нибудь взять зонт?", 5),
            // A photo the auto-download rules left on the server (3.4 MB is over the 1 MB a photo may load by itself).
            message(990311, 990013, "", 6).let { base ->
                val pending = AloOutgoingMessageAttachment(
                    url = "/uploads/messages/pending-photo.bin", type = "image", name = "route.jpg",
                    mediaEncryption = mapOf("algorithm" to "alo-aes-gcm-chunked-v1", "plaintextSize" to "3400000")
                )
                base.copy(attachmentUrl = pending.url, attachmentType = "image", attachmentName = pending.name, attachments = listOf(pending))
            },
            message(990307, GROUP_OWNER_ID, "Договорились ✅", 6, reactions = listOf(AloMessageReaction("👍", 1, true)))
        )
    }
}
