package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale

/** A channel's statistics for a period, as its admins see them (iOS's AloChannelStats). */
data class AloChannelStats(
    val days: Int,
    val subscribers: Int,
    val posts: Int,
    val views: Int,
    val likes: Int,
    val comments: Int,
    val reposts: Int,
    val growth: List<Pair<String, Int>>,
    val publications: List<Publication>
) {
    data class Publication(val postId: Int, val publishedAt: String, val views: Int, val likes: Int, val comments: Int, val reposts: Int)

    val viewsPerPost: Int get() = if (posts > 0) views / posts else 0
    val joinedInPeriod: Int get() = growth.sumOf { it.second }

    /** Every day of the period, zeros included: who joined, and views of posts published that day. */
    fun byDay(today: LocalDate = LocalDate.now()): List<Triple<String, Int, Int>> {
        val joined = growth.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val views = publications.groupBy({ it.publishedAt.take(10) }, { it.views }).mapValues { it.value.sum() }
        return (maxOf(1, days) - 1 downTo 0).map { offset ->
            val key = today.minusDays(offset.toLong()).toString()
            Triple(key, joined[key] ?: 0, views[key] ?: 0)
        }
    }
}

internal fun interface AloChannelStatsService {
    suspend fun stats(channelId: Int, days: Int): AloChannelStats
}

internal val LocalAloChannelStats = staticCompositionLocalOf<AloChannelStatsService?> { null }

/**
 * Telegram's channel statistics, as iOS's AloChannelStatsScreen: the period, an overview, follower
 * growth, views of publications by the day they came out, and the latest posts with their reach.
 */
@Composable
internal fun AloChannelStatsScreen(channelId: Int, posts: List<AloPost>, onDismiss: () -> Unit) {
    val service = LocalAloChannelStats.current
    var days by remember { mutableIntStateOf(30) }
    var stats by remember { mutableStateOf<AloChannelStats?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(days) {
        val loaded = runCatching { service?.stats(channelId, days) ?: error("Статистика недоступна.") }
        loaded.onSuccess { stats = it; error = "" }
            .onFailure { error = it.moderationMessage("Проверьте подключение и попробуйте ещё раз.") }
    }
    BackHandler(onBack = onDismiss)
    AloNaturalLineHeight {
        Column(Modifier.fillMaxSize().background(AloNativeAppBackground).windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
                Text("Статистика", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Box(
                    Modifier.align(Alignment.CenterEnd).height(44.dp).clip(RoundedCornerShape(percent = 50))
                        .background(AloNativeAppText.copy(alpha = 0.10f))
                        .border(0.5.dp, AloNativeAppText.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                        .clickable(onClick = onDismiss).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Готово", color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AloPeriodSegments(days) { days = it }
                val current = stats
                when {
                    current != null -> {
                        AloStatsCard("Обзор") {
                            val tiles = listOf(
                                Triple(formatCompactCount(current.subscribers), "Подписчики", current.joinedInPeriod.takeIf { it > 0 }?.let { "+${formatCompactCount(it)}" }),
                                Triple(formatCompactCount(current.viewsPerPost), "Просмотров на пост", null),
                                Triple(formatCompactCount(current.posts), "Публикаций", null),
                                Triple(formatCompactCount(current.views), "Просмотры", null),
                                Triple(formatCompactCount(current.likes), "Отметки «Нравится»", null),
                                Triple(formatCompactCount(current.reposts), "Репосты", null)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                tiles.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { (value, title, change) -> AloChannelStatTile(value, title, change, Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        val daily = current.byDay()
                        AloStatsCard("Новые подписчики") { AloDailyBars(daily.map { it.first }, daily.map { it.second }, AloNativeAppSuccess) }
                        AloStatsCard("Просмотры публикаций по дню выхода") { AloDailyBars(daily.map { it.first }, daily.map { it.third }, AloNativeAppAccent) }
                        if (current.publications.isNotEmpty()) {
                            AloStatsCard("Последние публикации") {
                                val shown = current.publications.take(10)
                                Column {
                                    shown.forEachIndexed { index, publication ->
                                        val post = posts.firstOrNull { it.id == publication.postId }
                                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    post?.let { it.body.ifBlank { "Фото или видео" } } ?: "Публикация",
                                                    color = AloNativeAppText, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                                )
                                                Text(formatPostTimestamp(publication.publishedAt), color = AloNativeAppMuted, fontSize = 12.sp)
                                            }
                                            Spacer(Modifier.size(8.dp))
                                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                AloStatsCount(Icons.Outlined.Visibility, formatCompactCount(publication.views))
                                                AloStatsCount(Icons.Rounded.Repeat, formatCompactCount(publication.reposts))
                                            }
                                        }
                                        if (index < shown.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppBorder))
                                    }
                                }
                            }
                        }
                    }
                    error.isNotBlank() -> AloUnavailableView(Icons.Outlined.QueryStats, "Статистика недоступна", error, Modifier.padding(top = 40.dp))
                    else -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AloNativeAppAccent)
                    }
                }
            }
        }
    }
}

/** iOS's segmented «7 дней / 30 дней / 90 дней». */
@Composable
private fun AloPeriodSegments(days: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(percent = 50))
            .background(AloNativeAppText.copy(alpha = 0.11f)).padding(2.dp)
    ) {
        listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней").forEach { (value, title) ->
            Box(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (days == value) AloNativeAppText.copy(alpha = 0.28f) else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(title, color = AloNativeAppText, fontSize = 14.sp, fontWeight = if (days == value) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** iOS's AloStatsSection: an uppercase grey title over a rounded card. */
@Composable
private fun AloStatsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.55f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title.uppercase(Locale.forLanguageTag("ru")), color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun AloChannelStatTile(value: String, title: String, change: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (change != null) Text(change, color = AloNativeAppSuccess, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(title, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AloStatsCount(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, tint = AloNativeAppMuted, modifier = Modifier.size(13.dp))
        Text(value, color = AloNativeAppMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A bar per day with rounded tops, the scale on the trailing edge and four dates below, as Swift Charts draws it. */
@Composable
private fun AloDailyBars(days: List<String>, values: List<Int>, color: Color) {
    val grid = AloNativeAppBorder
    val (step, top) = niceScale(values.maxOrNull() ?: 0)
    val ticks = (top downTo 0 step step).toList()
    Column {
        Row {
            Canvas(Modifier.weight(1f).height(150.dp)) {
                ticks.forEach { tick ->
                    val y = size.height * (1f - tick / top.toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (values.isEmpty()) return@Canvas
                val slot = size.width / values.size
                val bar = (slot * 0.6f).coerceAtLeast(1f)
                values.forEachIndexed { index, value ->
                    if (value <= 0) return@forEachIndexed
                    val height = size.height * value / top
                    drawRoundRect(
                        color, Offset(slot * index + (slot - bar) / 2, size.height - height), Size(bar, height),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
            Column(Modifier.height(150.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                ticks.forEach { Text(String.format(Locale.forLanguageTag("ru"), "%,d", it), color = AloNativeAppMuted, fontSize = 10.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val format = SimpleDateFormat("d MMM", Locale.forLanguageTag("ru"))
            val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val step = (days.size / 4).coerceAtLeast(1)
            days.indices.filter { it % step == 0 }.take(4).forEach { index ->
                Text(runCatching { format.format(parse.parse(days[index])!!) }.getOrDefault(""), color = AloNativeAppMuted, fontSize = 10.sp)
            }
        }
    }
}

/** Swift Charts' axis: a step of 1, 2 or 5 times a power of ten giving about four bands, and the top above the largest value. */
internal fun niceScale(maximum: Int): Pair<Int, Int> {
    val raw = maxOf(1.0, maximum / 4.0)
    var magnitude = 1
    while (magnitude * 10 <= raw) magnitude *= 10
    val step = listOf(1, 2, 5, 10).map { it * magnitude }.first { it >= raw }
    val top = maxOf(step, ((maximum + step - 1) / step) * step)
    return step to top
}
