package com.alo.android

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun buildPostMeta(post: AloPost): String {
    return buildString {
        if (post.author.username.isNotBlank()) {
            append("@")
            append(post.author.username)
            append(" · ")
        }
        append(formatFeedDate(post.createdAt))
        if (post.editedAt.isNotBlank()) append(" · изменено")
    }
}

/** The status under the name while the other side is busy, worded as on the site and the iPhone. */
internal fun typingStatusText(action: String): String = when (action) {
    "voice" -> "записывает голосовое"
    "circle" -> "записывает видеосообщение"
    else -> "печатает"
}

internal fun buildConversationHeaderSubtitle(
    detail: AloConversationDetail?,
    isLoading: Boolean,
    isOfflineCached: Boolean = false
): String {
    if (detail?.entityType == "saved") return "Личное облако"
    if (isOfflineCached && detail?.kind != "group") return "соединение"
    if (isLoading) return "Открываем переписку..."
    if (detail == null) return "Чат"
    if (detail.isTyping) return typingStatusText(detail.typingAction) + "..."
    return buildConversationPresenceLabel(detail).ifBlank {
        detail.subtitle.ifBlank { "Чат" }
    }
}

internal fun buildConversationPresenceLabel(detail: AloConversationDetail): String {
    return when {
        detail.entityType == "saved" -> "Личное облако"
        detail.kind == "group" -> detail.subtitle.ifBlank { "Групповой чат" }
        detail.online -> "в сети"
        detail.lastSeenAt.isNotBlank() -> formatLastSeenLabel(detail.lastSeenAt)
        else -> ""
    }
}

internal fun formatFeedDate(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        cachedDateFormat("d MMM").format(requireParsedDate(value))
    }.getOrDefault(value.take(10))
}

internal fun formatPlaybackDuration(valueMs: Int): String {
    val totalSeconds = (valueMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatLastSeenLabel(value: String): String {
    return runCatching {
        val date = requireParsedDate(value)
        val zoned = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val time = cachedDateFormat("HH:mm").format(date)
        when {
            sameDay(zoned, now) -> {
                "был(а) сегодня в $time"
            }
            sameDay(zoned, yesterday) -> {
                "был(а) вчера в $time"
            }
            else -> {
                "был(а) ${cachedDateFormat("d MMM").format(date)} в $time"
            }
        }
    }.getOrDefault(value)
}

internal fun formatMessageTime(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        cachedDateFormat("HH:mm").format(requireParsedDate(value))
    }.getOrDefault(formatFeedDate(value))
}

internal fun formatMessageDateLabel(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val date = requireParsedDate(value)
        val day = Calendar.getInstance().apply { time = date }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        when {
            sameDay(day, today) -> "Сегодня"
            sameDay(day, yesterday) -> "Вчера"
            day.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                cachedDateFormat("d MMMM").format(date)
            else -> cachedDateFormat("d MMMM yyyy").format(date)
        }
    }.getOrDefault(value.take(10))
}

internal fun formatMessageDayKey(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        cachedDateFormat("yyyy-MM-dd").format(requireParsedDate(value))
    }.getOrDefault(value.take(10))
}

internal fun parseIsoTimestampMillis(value: String): Long {
    if (value.isBlank()) return 0L
    return parseIsoDate(value)?.time ?: 0L
}

internal fun buildMessageMetaLabel(createdAt: String, editedAt: String): String {
    val base = formatMessageTime(createdAt)
    if (editedAt.isBlank()) return base
    return if (base.isBlank()) "изменено" else "$base · изменено"
}

internal fun requireParsedDate(value: String): Date {
    return requireNotNull(parseIsoDate(value)) { "Invalid date: $value" }
}

/**
 * Dates are parsed and formatted while lists scroll, several times per row, so nothing here may build a SimpleDateFormat
 * per call: that alone was a visible share of a chat's scrolling. The server's two shapes are read by hand, each text once.
 */
private val parsedDates = object : LinkedHashMap<String, Long>(1024, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2048
}
private const val UNPARSABLE_DATE = Long.MIN_VALUE

private fun parseIsoDate(value: String): Date? {
    val normalized = value.trim()
    if (normalized.isBlank()) return null
    val known = synchronized(parsedDates) { parsedDates[normalized] }
    val millis = known ?: (parseServerDateMillis(normalized) ?: parseUnusualDate(normalized) ?: UNPARSABLE_DATE)
        .also { synchronized(parsedDates) { parsedDates[normalized] = it } }
    return if (millis == UNPARSABLE_DATE) null else Date(millis)
}

private fun parseUnusualDate(value: String): Long? {
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
        val formatter = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = runCatching { formatter.parse(value) }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

/** `2026-11-02T03:31:05.123Z`, `…+03:00`, `2026-11-02 03:31:05` (UTC, as SQLite writes it) or `2026-11-02`; anything else is null. */
internal fun parseServerDateMillis(text: String): Long? {
    fun number(from: Int, to: Int): Int {
        if (to > text.length) return -1
        var result = 0
        for (index in from until to) {
            val digit = text[index] - '0'
            if (digit !in 0..9) return -1
            result = result * 10 + digit
        }
        return result
    }
    if (text.length < 10 || text[4] != '-' || text[7] != '-') return null
    val year = number(0, 4)
    val month = number(5, 7)
    val day = number(8, 10)
    if (year < 1 || month !in 1..12 || day < 1) return null
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val monthDays = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    if (day > monthDays) return null
    // Days since 1970 (Howard Hinnant's days_from_civil).
    val y = if (month <= 2) year - 1 else year
    val era = y / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val epochDay = era * 146097L + dayOfEra - 719468L
    if (text.length == 10) return epochDay * 86_400_000L

    val separator = text[10]
    if ((separator != 'T' && separator != ' ') || text.length < 19 || text[13] != ':' || text[16] != ':') return null
    val hour = number(11, 13)
    val minute = number(14, 16)
    val second = number(17, 19)
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    var position = 19
    var millis = 0
    if (position < text.length && text[position] == '.') {
        position += 1
        var digits = 0
        while (position < text.length && text[position] in '0'..'9') {
            if (digits < 3) millis = millis * 10 + (text[position] - '0')
            digits += 1
            position += 1
        }
        if (digits == 0) return null
        repeat((3 - digits).coerceAtLeast(0)) { millis *= 10 }
    }
    var offsetMinutes = 0
    if (position == text.length) {
        if (separator == 'T') return null
    } else if (text[position] == 'Z' && position + 1 == text.length) {
        // UTC.
    } else if (text[position] == '+' || text[position] == '-') {
        val sign = if (text[position] == '+') 1 else -1
        val hours = number(position + 1, position + 3)
        val rest = text.length - position - 3
        val minutes = when {
            rest == 0 -> 0
            rest == 2 -> number(position + 3, position + 5)
            rest == 3 && text[position + 3] == ':' -> number(position + 4, position + 6)
            else -> return null
        }
        if (hours !in 0..23 || minutes !in 0..59) return null
        offsetMinutes = sign * (hours * 60 + minutes)
    } else {
        return null
    }
    return epochDay * 86_400_000L + (hour * 3600L + minute * 60L + second - offsetMinutes * 60L) * 1000L + millis
}

private val dateFormats = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

/** The same formatter for a pattern on each thread; the time zone is re-read because the phone may have travelled. */
private fun cachedDateFormat(pattern: String): SimpleDateFormat {
    val formatter = dateFormats.get()!!.getOrPut(pattern) {
        SimpleDateFormat(pattern, if (pattern == "yyyy-MM-dd") Locale.US else Locale.forLanguageTag("ru"))
    }
    formatter.timeZone = TimeZone.getDefault()
    return formatter
}

private fun sameDay(left: Calendar, right: Calendar): Boolean {
    return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
}

/** iOS's post time: «сейчас», «12 мин», «3 ч», «2 д», then a date. */
internal fun formatPostTimestamp(value: String, nowMs: Long = System.currentTimeMillis()): String {
    if (value.isBlank()) return ""
    return runCatching {
        val date = requireParsedDate(value)
        val seconds = ((nowMs - date.time) / 1000).coerceAtLeast(0)
        when {
            seconds < 60 -> "сейчас"
            seconds < 3600 -> "${seconds / 60} мин"
            seconds < 86_400 -> "${seconds / 3600} ч"
            seconds < 604_800 -> "${seconds / 86_400} д"
            else -> cachedDateFormat("d MMM").format(date)
        }
    }.getOrDefault(value.take(10))
}

/** The Russian plural for a count: 1 подписчик, 2 подписчика, 5 подписчиков (11–14 take the last form). From a thousand on
 * the count is shown rounded («2.4K»), which reads with the last form: «2.4K подписчиков», not «2.4K подписчик». */
internal fun aloRuPlural(count: Int, one: String, few: String, many: String): String {
    if (kotlin.math.abs(count) >= 1_000) return many
    val mod10 = kotlin.math.abs(count) % 10
    val mod100 = kotlin.math.abs(count) % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

/** «1.2K», exactly as iOS's AloCompactCount: truncated to tenths, no fraction from 100 up. */
internal fun formatCompactCount(value: Int): String {
    val count = value.coerceAtLeast(0)
    for ((divisor, suffix) in listOf(1_000_000_000 to "B", 1_000_000 to "M", 1_000 to "K")) {
        if (count >= divisor) {
            val tenths = count / (divisor / 10)
            val whole = tenths / 10
            val fraction = tenths % 10
            return if (fraction == 0 || whole >= 100) "$whole$suffix" else "$whole.$fraction$suffix"
        }
    }
    return count.toString()
}
