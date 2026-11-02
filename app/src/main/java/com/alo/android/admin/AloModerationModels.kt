package com.alo.android

import androidx.compose.runtime.staticCompositionLocalOf

// The moderation panel (reports, appeals, audit log) and Alo's own ads, as iOS's AloModerationModels.

data class AloModerationReport(
    val id: Int,
    val reporter: AloEntitySummary,
    val targetType: String,
    val targetId: Int,
    val targetUserId: Int?,
    val targetPostBody: String,
    val targetPostMediaCount: Int,
    val reason: String,
    val details: String,
    val status: String,
    val resolutionAction: String,
    val createdAt: String,
    /** What was reported, as the moderator needs to see it. */
    val target: AloReportedContent? = null,
    /** Other pending reports about the same post, comment, person or channel. */
    val otherPendingCount: Int = 0
) {
    val targetTitle: String
        get() = when (targetType) {
            "post" -> "Публикация"
            "comment" -> "Комментарий"
            "channel" -> "Канал"
            else -> "Пользователь"
        }
}

/** The reported post, comment, person or channel; `deleted` once it is gone (iOS's AloReportedContent). */
data class AloReportedContent(
    val kind: String,
    val id: Int,
    val deleted: Boolean,
    val author: AloEntitySummary? = null,
    val body: String = "",
    val media: List<AloMediaItem> = emptyList(),
    val createdAt: String = "",
    val postId: Int = 0,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val user: AloEntitySummary? = null,
    val bio: String = "",
    val hierarchy: AloStaffHierarchy = AloStaffHierarchy.User,
    val accountStatus: String = "active",
    val title: String = "",
    val slug: String = "",
    val description: String = "",
    val avatar: String = "",
    val subscribersCount: Int = 0
)

/** The moderation ladder: nobody acts on someone at or above them. */
enum class AloStaffHierarchy(val raw: String, val rank: Int, val title: String, val plural: String) {
    Owner("owner", 4, "Владелец", "Владелец"),
    Admin("admin", 3, "Администратор", "Администраторы"),
    LeadModerator("lead_moderator", 2, "Старший модератор", "Старшие модераторы"),
    Moderator("moderator", 1, "Модератор", "Модераторы"),
    User("user", 0, "Пользователь", "Пользователи");

    companion object {
        fun from(raw: String?): AloStaffHierarchy? = entries.firstOrNull { it.raw == raw }
    }
}

/** What a moderator may be given, with the name the panel shows. */
enum class AloModeratorPermission(val raw: String, val title: String, val short: String, val footnote: String) {
    Reports("reports.review", "Жалобы, апелляции и журнал", "Жалобы", "Решает жалобы и обжалования, видит журнал."),
    Stats("stats.view", "Статистика", "Статистика", "Видит общую статистику сервиса."),
    Verification("verification.review", "Верификация", "Верификация", "Рассматривает заявки на галочку."),
    Censorship("censorship.manage", "Стоп-слова", "Стоп-слова", "Ведёт список запрещённых слов."),
    Team("moderation.manage", "Управление командой", "Команда", "Делает старшим модератором: назначает модераторов ниже себя.");

    companion object {
        fun from(raw: String): AloModeratorPermission? = entries.firstOrNull { it.raw == raw }
    }
}

/** Someone in the moderation team, or a person found to appoint or restrict. */
data class AloStaffMember(
    val user: AloEntitySummary,
    val permissions: List<String>,
    val hierarchy: AloStaffHierarchy,
    val isOwner: Boolean,
    val accountStatus: String,
    val blockedUntil: String,
    val blockReason: String
) {
    val id: Int get() = user.id
    val accountStatusTitle: String?
        get() = when (accountStatus) {
            "blocked" -> if (blockedUntil.isBlank()) "Доступ ограничен" else "Ограничен до ${aloModerationDate(blockedUntil)}"
            "banned" -> "Заблокирован навсегда"
            else -> null
        }
}

/** A moment ahead or behind as «22 октября, 21:28» (the year only when it is not this one). */
internal fun aloModerationDate(iso: String): String = runCatching {
    // Android's formatter gives the nominative month («октябрь»); a date needs «октября».
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
        "сентября", "октября", "ноября", "декабря")
    val date = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
    val year = if (date.year == java.time.LocalDate.now().year) "" else " ${date.year}"
    "${date.dayOfMonth} ${months[date.monthValue - 1]}$year, %02d:%02d".format(date.hour, date.minute)
}.getOrDefault(iso)

/** What a moderator can do with an approved report, by what it is about. */
enum class AloModerationAction(val raw: String, val title: String) {
    None("none", "Без наказания"),
    Warn("warn", "Предупредить"),
    Block("block", "Ограничить доступ"),
    Ban("ban", "Заблокировать навсегда"),
    DeletePost("delete_post", "Удалить публикацию"),
    DeleteComment("delete_comment", "Удалить комментарий"),
    DeleteChannel("delete_channel", "Удалить канал");

    val isDestructive: Boolean get() = this != None && this != Warn

    companion object {
        fun from(raw: String): AloModerationAction? = entries.firstOrNull { it.raw == raw }

        fun available(targetType: String): List<AloModerationAction> {
            val content = when (targetType) {
                "post" -> listOf(DeletePost)
                "comment" -> listOf(DeleteComment)
                "channel" -> listOf(DeleteChannel)
                else -> emptyList()
            }
            return listOf(Warn) + content + listOf(Block, Ban)
        }
    }
}

data class AloModerationAppeal(
    val id: Int,
    val kind: String,
    val reportId: Int?,
    val text: String,
    val status: String,
    val decisionNote: String,
    val action: String,
    val accountStatus: String,
    val createdAt: String,
    val user: AloEntitySummary?
) {
    val actionTitle: String
        get() = AloModerationAction.from(action)?.title ?: if (kind == "account") "Блокировка аккаунта" else "Решение модерации"
}

data class AloModerationAuditEntry(
    val id: Int,
    val action: String,
    val details: String,
    val createdAt: String,
    val actor: AloEntitySummary,
    val target: AloEntitySummary?
) {
    val title: String
        get() = when (action) {
            "report_rejected" -> "Жалоба отклонена"
            "appeal_overturned" -> "Обжалование принято"
            "appeal_upheld" -> "Обжалование отклонено"
            "moderator_granted" -> "Назначен модератор"
            "moderator_updated" -> "Права модератора изменены"
            "moderator_revoked" -> "Снят с модерации"
            "admin_granted" -> "Назначен администратор"
            "admin_revoked" -> "Снят администратор"
            "account_blocked" -> "Доступ ограничен"
            "account_banned" -> "Аккаунт заблокирован"
            "account_active" -> "Доступ восстановлен"
            "block" -> "Доступ ограничен по жалобе"
            "ban" -> "Заблокирован по жалобе"
            else -> AloModerationAction.from(action)?.title ?: action
        }

    /** Permission ids in the details of a role change, by their panel names. */
    val readableDetails: String
        get() = if (!action.startsWith("moderator_")) details else details.split(",")
            .joinToString(", ") { part -> AloModeratorPermission.from(part.trim())?.title ?: part.trim() }
}

/** A campaign in the admin panel, with its reach. */
data class AloAdCampaign(
    val ad: AloAd,
    val name: String,
    val status: String,
    val impressionLimit: Int,
    val impressions: Int,
    val reach: Int,
    val clicks: Int,
    val hides: Int,
    val ctr: Double
) {
    val id: Int get() = ad.id
    val statusTitle: String
        get() = when (status) {
            "active" -> "Показывается"
            "paused" -> "На паузе"
            "ended" -> "Завершена"
            else -> "Черновик"
        }
}

/** The editable part of a campaign, as the server takes it. */
data class AloAdCampaignFields(
    val name: String,
    val advertiser: String,
    val title: String,
    val body: String,
    val ctaLabel: String,
    val ctaUrl: String,
    val placement: String,
    val status: String,
    val impressionLimit: Int
)

/**
 * What the moderation screens ask of the app: the server, or the offline fixture in design previews.
 * The screens keep their own lists, as iOS's do.
 */
internal interface AloModerationService {
    suspend fun reports(): List<AloModerationReport>
    suspend fun resolveReport(report: AloModerationReport, approve: Boolean, action: AloModerationAction, note: String, blockHours: Int = 24)
    /** The team, owner first, and the rights this account may hand out. */
    suspend fun team(): Pair<List<AloStaffMember>, List<String>>
    /** Anyone by name, @username or id, blocked accounts too. */
    suspend fun searchStaff(query: String): List<AloStaffMember>
    /** Appoints, re-grants or (with no rights left) removes a moderator. */
    suspend fun setModeratorPermissions(member: AloStaffMember, permissions: List<String>)
    /** "active", "blocked" (for `hours`) or "banned"; administrators only. */
    suspend fun setAccountStatus(member: AloStaffMember, status: String, hours: Int, reason: String): AloStaffMember
    /** The owner appoints or removes an administrator. */
    suspend fun setAdministrator(member: AloStaffMember, granted: Boolean): AloStaffMember
    suspend fun appeals(): List<AloModerationAppeal>
    suspend fun resolveAppeal(appeal: AloModerationAppeal, overturn: Boolean)
    suspend fun audit(before: Int?): Pair<List<AloModerationAuditEntry>, Int?>
    suspend fun campaigns(): List<AloAdCampaign>
    suspend fun saveCampaign(id: Int?, fields: AloAdCampaignFields): AloAdCampaign
    /** Contest a decision named in a moderation notification. */
    suspend fun appealDecision(reportId: Int, text: String)
    // Checkmarks (AloVerification.kt). Defaults keep the design fixture working without them.
    suspend fun verificationQueue(status: String): List<AloVerificationRequest> = emptyList()
    suspend fun reviewVerification(id: Int, approve: Boolean, note: String) {}
    /** Staff: give or take the checkmark with no request. */
    suspend fun setVerified(targetType: String, targetId: Int, verified: Boolean) {}
    suspend fun requestVerification(targetType: String, targetId: Int, note: String) {}
    suspend fun myVerificationRequests(): List<AloVerificationRequest> = emptyList()
}

internal class AloApiModerationService(private val api: AloApiClient) : AloModerationService {
    override suspend fun reports() = api.loadModerationReports("pending")
    override suspend fun resolveReport(report: AloModerationReport, approve: Boolean, action: AloModerationAction, note: String, blockHours: Int) =
        api.resolveModerationReport(report.id, approve, action, note, blockHours)
    override suspend fun team() = api.loadModerationTeam()
    override suspend fun searchStaff(query: String) = api.searchStaffCandidates(query)
    override suspend fun setModeratorPermissions(member: AloStaffMember, permissions: List<String>) = when {
        permissions.isEmpty() -> api.revokeModerator(member.id)
        member.hierarchy == AloStaffHierarchy.User -> api.grantModerator(member.id, permissions)
        else -> api.updateModerator(member.id, permissions)
    }
    override suspend fun setAccountStatus(member: AloStaffMember, status: String, hours: Int, reason: String) =
        api.setAccountStatus(member.id, status, hours, reason)
    override suspend fun setAdministrator(member: AloStaffMember, granted: Boolean) = api.setAdministrator(member.id, granted)
    override suspend fun appeals() = api.loadModerationAppeals("pending")
    override suspend fun resolveAppeal(appeal: AloModerationAppeal, overturn: Boolean) =
        api.resolveModerationAppeal(appeal.id, overturn, note = "")
    override suspend fun audit(before: Int?) = api.loadModerationAudit(before)
    override suspend fun campaigns() = api.loadAdCampaigns()
    override suspend fun saveCampaign(id: Int?, fields: AloAdCampaignFields) = api.saveAdCampaign(id, fields)
    override suspend fun appealDecision(reportId: Int, text: String) = api.appealModerationDecision(reportId, text)
    override suspend fun verificationQueue(status: String) = api.loadVerificationQueue(status)
    override suspend fun reviewVerification(id: Int, approve: Boolean, note: String) = api.reviewVerification(id, approve, note)
    override suspend fun setVerified(targetType: String, targetId: Int, verified: Boolean) = api.setVerified(targetType, targetId, verified)
    override suspend fun requestVerification(targetType: String, targetId: Int, note: String) = api.requestVerification(targetType, targetId, note)
    override suspend fun myVerificationRequests() = api.loadMyVerificationRequests()
}

internal val LocalAloModeration = staticCompositionLocalOf<AloModerationService?> { null }

/** The server's message for a failed request, or [fallback]. */
internal fun Throwable.moderationMessage(fallback: String): String =
    (this as? AloApiException)?.message?.takeIf { it.isNotBlank() } ?: fallback
