package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// The moderation panel for admins and moderators, as iOS's AloModerationViews: a root list of the
// queues (with counts), the team, the log and Alo's own ads, each opening its own screen.

internal val AloModerationWarning = Color(0xFFFF9F0A)

private enum class AloModerationScreen(val title: String) {
    Root("Модерация"), Reports("Жалобы"), Appeals("Апелляции"), Verification("Галочки"), Team("Команда"), Audit("Журнал"), Ads("Реклама")
}

@Composable
internal fun AloModerationPanel(me: AloCurrentUser, onBack: () -> Unit) {
    val service = LocalAloModeration.current ?: return
    var screenName by rememberSaveable { mutableStateOf(AloModerationScreen.Root.name) }
    val screen = AloModerationScreen.valueOf(screenName)
    var reportCount by remember { mutableStateOf<Int?>(null) }
    var appealCount by remember { mutableStateOf<Int?>(null) }
    var verificationCount by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) { delay(2_600); notice = "" }
    }
    LaunchedEffect(Unit) {
        if ("verification" in me.adminCan) verificationCount = runCatching { service.verificationQueue("pending").size }.getOrNull()
        if ("reports" in me.adminCan) {
            reportCount = runCatching { service.reports().size }.getOrNull()
            appealCount = runCatching { service.appeals().size }.getOrNull()
        }
    }
    fun back() { if (screen == AloModerationScreen.Root) onBack() else screenName = AloModerationScreen.Root.name }
    val pageShiftPx = rememberAloPageShiftPx()
    // On the root the back swipe belongs to the page around the panel (AloSwipeBackPage), which follows the finger. From a
    // section it is seekable, as in the settings: the section leaves and the list comes back with the finger.
    val screenTransition = remember { SeekableTransitionState(screen) }
    LaunchedEffect(screen) { screenTransition.animateTo(screen) }
    PredictiveBackHandler(enabled = screen != AloModerationScreen.Root) { events ->
        try {
            events.collect { event -> screenTransition.seekTo(event.progress, targetState = AloModerationScreen.Root) }
            screenName = AloModerationScreen.Root.name
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            screenTransition.animateTo(screen)
        }
    }

    AloNaturalLineHeight {
    Box(Modifier.fillMaxSize().background(AloNativeAppBackground)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // iOS pushes moderation on the system navigation bar: the text «‹ Назад» and a centred title,
            // the same bar as the settings.
            AloSettingsNavigationBar(title = screen.title, onBack = ::back)
            rememberTransition(screenTransition, label = "moderationScreen").AnimatedContent(
                // Telegram's page motion: a pushed screen fades in from 48dp to the right; back reverses it.
                transitionSpec = { aloPageTransform(targetState != AloModerationScreen.Root, pageShiftPx) },
                modifier = Modifier.weight(1f)
            ) { shown ->
                when (shown) {
                    AloModerationScreen.Root -> AloModerationRoot(me, reportCount, appealCount, verificationCount) { screenName = it.name }
                    AloModerationScreen.Verification -> AloVerificationQueue(service, onNotice = { notice = it }, onCount = { verificationCount = it })
                    AloModerationScreen.Reports -> AloReportQueue(service, onCount = { reportCount = it })
                    AloModerationScreen.Appeals -> AloAppealQueue(service, onNotice = { notice = it }, onCount = { appealCount = it })
                    AloModerationScreen.Team -> AloModerationTeam(service, me, onNotice = { notice = it })
                    AloModerationScreen.Audit -> AloAuditLogList(service)
                    AloModerationScreen.Ads -> AloAdCampaignList(service)
                }
            }
        }
        AnimatedVisibility(
            visible = notice.isNotEmpty(),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)
        ) {
            Text(
                notice, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .aloLiquidGlass(AloSquircle(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
    }
}

/** The round glass back button with an optically centred chevron. */
/** The root: who you are in the team, then the sections this account may open. */
@Composable
private fun AloModerationRoot(me: AloCurrentUser, reportCount: Int?, appealCount: Int?, verificationCount: Int?, onOpen: (AloModerationScreen) -> Unit) {
    val can = me.adminCan
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarBadge(name = me.name, avatarUrl = me.avatarUrl, verified = false, size = 48.dp, colorSeed = me.id.toString())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(me.name, color = AloNativeAppText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                AloStaffBadge(me.staffHierarchy)
            }
        }
        if ("reports" in can) {
            AloModerationCard("Очередь") {
                AloModerationNavRow("Жалобы", "Публикации, комментарии, люди и каналы", Icons.Rounded.Flag, AloNativeAppDanger, reportCount) {
                    onOpen(AloModerationScreen.Reports)
                }
                AloModerationDivider()
                AloModerationNavRow("Апелляции", "Обжалования решений и блокировок", Icons.AutoMirrored.Rounded.Undo, AloModerationWarning, appealCount) {
                    onOpen(AloModerationScreen.Appeals)
                }
            }
        }
        if ("verification" in can) {
            AloModerationCard("Верификация") {
                AloModerationNavRow("Галочки", "Заявки на галочку у страниц и сообществ", Icons.Rounded.Verified, AloNativeAppAccent, verificationCount) {
                    onOpen(AloModerationScreen.Verification)
                }
            }
        }
        if ("moderation" in can) {
            AloModerationCard("Команда") {
                AloModerationNavRow("Команда и права", "Модераторы, уровни и доступ к аккаунтам", Icons.Rounded.Groups, Color(0xFF8769F0)) {
                    onOpen(AloModerationScreen.Team)
                }
            }
        }
        if ("reports" in can || "moderation" in can) {
            AloModerationCard("История и реклама") {
                if ("reports" in can) {
                    AloModerationNavRow("Журнал действий", "Кто, что и когда решил", Icons.AutoMirrored.Rounded.ListAlt, AloNativeAppAccent) {
                        onOpen(AloModerationScreen.Audit)
                    }
                }
                if ("reports" in can && "moderation" in can) AloModerationDivider()
                if ("moderation" in can) {
                    AloModerationNavRow("Реклама", "Кампании, показы и клики", Icons.Rounded.Campaign, AloNativeAppSuccess) {
                        onOpen(AloModerationScreen.Ads)
                    }
                }
            }
        }
    }
}

/** A settings-style card: an uppercase grey title over rows on the surface colour (iOS's AloModerationCard). */
@Composable
internal fun AloModerationCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) {
            Text(title.uppercase(), color = AloNativeAppMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).padding(horizontal = 14.dp),
            content = content
        )
    }
}

@Composable
internal fun AloModerationDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
}

/** An icon tile, a title with a line of explanation, a count and a chevron. */
@Composable
private fun AloModerationNavRow(
    title: String, subtitle: String, icon: ImageVector, tint: Color, badge: Int? = null, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(34.dp).clip(AloSquircle(9.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AloNativeAppMuted, fontSize = 13.5.sp, maxLines = 2)
        }
        if (badge != null && badge > 0) {
            Box(
                Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(percent = 50)).background(AloNativeAppDanger).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text("$badge", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted, modifier = Modifier.size(22.dp))
    }
}

internal val AloStaffHierarchy.tint: Color
    @Composable get() = when (this) {
        AloStaffHierarchy.Owner -> Color(0xFFFFC233)
        AloStaffHierarchy.Admin -> AloNativeAppDanger
        AloStaffHierarchy.LeadModerator -> Color(0xFFB085FF)
        AloStaffHierarchy.Moderator -> AloNativeAppAccent
        AloStaffHierarchy.User -> AloNativeAppMuted
    }

internal val AloStaffHierarchy.icon: ImageVector
    get() = when (this) {
        AloStaffHierarchy.Owner -> Icons.Rounded.WorkspacePremium
        AloStaffHierarchy.Admin -> Icons.Rounded.AdminPanelSettings
        AloStaffHierarchy.LeadModerator -> Icons.Rounded.Stars
        AloStaffHierarchy.Moderator -> Icons.Rounded.Shield
        AloStaffHierarchy.User -> Icons.Rounded.Person
    }

/** A level on the moderation ladder: its symbol and name in its colour. */
@Composable
internal fun AloStaffBadge(hierarchy: AloStaffHierarchy) {
    val tint = hierarchy.tint
    Row(
        Modifier.height(22.dp).clip(RoundedCornerShape(percent = 50)).background(tint.copy(alpha = 0.15f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(hierarchy.icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(hierarchy.title, color = tint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** iOS 26's segmented Picker: a grey capsule with a lighter sliding thumb. */
@Composable
private fun AloModerationSegments(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = AloNativeAppBackground.luminance() < 0.3f
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (dark) Color.White.copy(alpha = 0.11f) else Color.Black.copy(alpha = 0.06f))
            .padding(2.dp)
    ) {
        val part = maxWidth / titles.size
        val offset by animateDpAsState(part * selected, spring(dampingRatio = 0.86f, stiffness = 520f), label = "moderationSegment")
        // An offset, not a start padding: the spring overshoots 0 on the way back and a negative padding throws.
        Box(
            Modifier.offset(x = offset).width(part).fillMaxHeight().clip(RoundedCornerShape(percent = 50))
                .background(if (dark) Color.White.copy(alpha = 0.30f) else Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            titles.forEachIndexed { index, title ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title, color = AloNativeAppText, fontSize = 15.sp, maxLines = 1,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AloRefreshableList(
    isLoading: Boolean,
    isEmpty: Boolean,
    onRefresh: suspend () -> Unit,
    empty: @Composable () -> Unit,
    list: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { scope.launch { isRefreshing = true; onRefresh(); isRefreshing = false } },
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AloNativeAppAccent) }
            isEmpty -> Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) { empty() }
            else -> list()
        }
    }
}

@Composable
private fun AloListSeparator(start: Int = 16) {
    HorizontalDivider(Modifier.padding(start = start.dp, end = 16.dp), thickness = 0.5.dp, color = AloNativeAppSeparator)
}

// MARK: - Reports

/** Pending reports, newest first; a tap opens the decision. */
@Composable
private fun AloReportQueue(service: AloModerationService, onCount: (Int) -> Unit) {
    val reports = remember { mutableStateListOf<AloModerationReport>() }
    var isLoading by remember { mutableStateOf(true) }
    var deciding by remember { mutableStateOf<AloModerationReport?>(null) }
    suspend fun load() {
        runCatching { service.reports() }.onSuccess { reports.clear(); reports.addAll(it) }
        isLoading = false
        onCount(reports.size)
    }
    LaunchedEffect(Unit) { load() }
    AloRefreshableList(
        isLoading = isLoading,
        isEmpty = reports.isEmpty(),
        onRefresh = { load() },
        empty = { AloUnavailableView(Icons.Outlined.VerifiedUser, "Жалоб нет", "Новые жалобы пользователей появятся здесь.") }
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(reports, key = { _, report -> report.id }) { _, report ->
                Box(Modifier.animateItem()) { AloReportRow(report) { deciding = report } }
            }
        }
    }
    deciding?.let { report ->
        AloReportDecisionSheet(
            report = report,
            service = service,
            onDismiss = { deciding = null },
            onDecided = { reports.removeAll { it.id == report.id }; onCount(reports.size); deciding = null }
        )
    }
}

@Composable
private fun AloReportReason(reason: String) {
    Box(
        Modifier.height(24.dp).clip(RoundedCornerShape(percent = 50)).background(AloNativeAppDanger.copy(alpha = 0.85f)).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(reason, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** A report in the queue: the reason, what it is about with a short preview, who sent it. */
@Composable
private fun AloReportRow(report: AloModerationReport, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AloReportReason(report.reason)
            Text(report.targetTitle, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatPostTimestamp(report.createdAt), color = AloNativeAppMuted, fontSize = 13.sp)
        }
        val target = report.target
        if (target != null) {
            AloReportedContentView(target, compact = true)
        } else if (report.targetPostBody.isNotEmpty()) {
            Text(report.targetPostBody, color = AloNativeAppText, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "От ${report.reporter.name}" + if (report.details.isEmpty()) "" else ": «${report.details}»",
                color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (report.otherPendingCount > 0) {
                Text("ещё ${report.otherPendingCount}", color = AloNativeAppDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * What a report points at: the post or comment with its author, text and media, the person, or the
 * channel. `compact` is the queue's short form; the decision shows everything and photos open.
 */
@Composable
internal fun AloReportedContentView(content: AloReportedContent, compact: Boolean = false, onOpenMedia: (Int) -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(14.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.7f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (content.deleted) {
            Text("Уже удалено", color = AloNativeAppMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            return@Column
        }
        @Composable
        fun person(user: AloEntitySummary, size: androidx.compose.ui.unit.Dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarBadge(name = user.name, avatarUrl = user.avatarUrl, verified = false, size = size, colorSeed = user.id.toString())
                Column {
                    Text(user.name, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (user.username.isNotEmpty()) Text("@${user.username}", color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        when (content.kind) {
            "post", "comment" -> {
                content.author?.let { person(it, 30.dp) }
                if (content.body.isNotEmpty()) {
                    Text(content.body, color = AloNativeAppText, fontSize = 15.sp,
                        maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                }
                if (content.media.isNotEmpty()) {
                    if (compact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            content.media.take(4).forEach { item ->
                                AloRemoteMediaImage(
                                    model = item.posterUrl.ifBlank { item.url }, contentDescription = null,
                                    modifier = Modifier.size(56.dp).clip(AloSquircle(8.dp)), contentScale = ContentScale.Crop,
                                    isVideo = item.type.startsWith("video")
                                )
                            }
                            if (content.media.size > 4) Text("+${content.media.size - 4}", color = AloNativeAppMuted, fontSize = 13.sp)
                        }
                    } else {
                        AloTimelineMediaGrid(content.media, onOpen = onOpenMedia)
                    }
                }
                if (!compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (content.kind == "post") {
                            Text("♡ ${content.likeCount}", color = AloNativeAppMuted, fontSize = 13.sp)
                            Text("💬 ${content.commentCount}", color = AloNativeAppMuted, fontSize = 13.sp)
                        } else if (content.postId > 0) {
                            Text("Комментарий к публикации #${content.postId}", color = AloNativeAppMuted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        if (content.createdAt.isNotEmpty()) Text(formatPostTimestamp(content.createdAt), color = AloNativeAppMuted, fontSize = 13.sp)
                    }
                }
            }
            "channel" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarBadge(name = content.title, avatarUrl = content.avatar, verified = false,
                        size = if (compact) 34.dp else 48.dp, colorSeed = "channel-${content.id}")
                    Column {
                        Text(content.title, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text((if (content.slug.isEmpty()) "" else "@${content.slug} · ") + "${formatCompactCount(content.subscribersCount)} подписчиков",
                            color = AloNativeAppMuted, fontSize = 13.sp)
                    }
                }
                if (content.description.isNotEmpty()) {
                    Text(content.description, color = AloNativeAppText, fontSize = 14.sp, maxLines = if (compact) 2 else Int.MAX_VALUE)
                }
                if (!compact) content.author?.let { Text("Владелец: ${it.name}", color = AloNativeAppMuted, fontSize = 13.sp) }
            }
            else -> {
                content.user?.let { person(it, if (compact) 34.dp else 48.dp) }
                if (content.bio.isNotEmpty()) {
                    Text(content.bio, color = AloNativeAppText, fontSize = 14.sp, maxLines = if (compact) 2 else Int.MAX_VALUE)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (content.hierarchy != AloStaffHierarchy.User) AloStaffBadge(content.hierarchy)
                    when (content.accountStatus) {
                        "blocked" -> Text("Доступ ограничен", color = AloModerationWarning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        "banned" -> Text("Заблокирован", color = AloNativeAppDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Approve with a measure, or reject; the decision and its note go to the audit log. */
@Composable
private fun AloReportDecisionSheet(
    report: AloModerationReport,
    service: AloModerationService,
    onDismiss: () -> Unit,
    onDecided: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf(AloModerationAction.Warn) }
    var blockHours by remember { mutableIntStateOf(24) }
    var note by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val durations = listOf(24 to "Сутки", 24 * 7 to "Неделя", 24 * 30 to "Месяц")
    fun decide(approve: Boolean) {
        isSaving = true
        scope.launch {
            runCatching { service.resolveReport(report, approve, action, note, if (action == AloModerationAction.Block) blockHours else 24) }
                .onSuccess { onDecided() }
                .onFailure { error = it.moderationMessage("Не удалось сохранить решение.") }
            isSaving = false
        }
    }
    AloFormSheet(title = "Решение", onDismiss = onDismiss) {
        // The report on a card at the sheet's 16dp edge, like the sections under it.
        AloFormSection(header = "Жалоба") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AloReportReason(report.reason)
                Text(report.targetTitle, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("От ${report.reporter.name} · ${formatPostTimestamp(report.createdAt)}", color = AloNativeAppMuted, fontSize = 13.sp)
            if (report.details.isNotEmpty()) Text("«${report.details}»", color = AloNativeAppText, fontSize = 15.sp)
            if (report.otherPendingCount > 0) {
                Text("На это пожаловались ещё ${report.otherPendingCount}", color = AloNativeAppDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            report.target?.let { AloReportedContentView(it, onOpenMedia = { index -> viewing = index }) }
                ?: if (report.targetPostBody.isNotEmpty()) Text(report.targetPostBody, color = AloNativeAppText, fontSize = 15.sp) else Unit
        }
        }
        AloFormSection(header = "Мера") {
            AloModerationAction.available(report.targetType).forEach { option ->
                AloFormCheckRow(option.title, checked = option == action) { action = option }
                AloFormDivider()
            }
            if (action == AloModerationAction.Block) {
                AloModerationSegments(
                    titles = durations.map { it.second },
                    selected = durations.indexOfFirst { it.first == blockHours }.coerceAtLeast(0),
                    onSelect = { blockHours = durations[it].first },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
                AloFormDivider()
            }
            AloFormTextField(note, { note = it }, "Комментарий для журнала", minLines = 2, maxLines = 4)
        }
        if (error.isNotEmpty()) AloFormSection { AloFormTextRow(error, AloNativeAppDanger) }
        AloFormSection {
            AloFormButtonRow(
                "Подтвердить: ${action.title.lowercase(Locale.forLanguageTag("ru"))}",
                if (action.isDestructive) AloNativeAppDanger else AloNativeAppAccent,
                enabled = !isSaving
            ) { decide(approve = true) }
            AloFormDivider()
            AloFormButtonRow("Отклонить жалобу", AloNativeAppAccent, enabled = !isSaving) { decide(approve = false) }
        }
    }
    viewing?.let { start ->
        val media = report.target?.media.orEmpty()
        if (media.isNotEmpty()) {
            Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                MediaViewerOverlay(AloMediaViewerState(items = media, startIndex = start.coerceIn(0, media.lastIndex)), onDismiss = { viewing = null })
            }
        }
    }
}

// MARK: - Appeals

@Composable
private fun AloAppealQueue(service: AloModerationService, onNotice: (String) -> Unit, onCount: (Int) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val appeals = remember { mutableStateListOf<AloModerationAppeal>() }
    var isLoading by remember { mutableStateOf(true) }
    suspend fun load() {
        runCatching { service.appeals() }.onSuccess { appeals.clear(); appeals.addAll(it) }
        isLoading = false
        onCount(appeals.size)
    }
    fun resolve(appeal: AloModerationAppeal, overturn: Boolean) {
        scope.launch {
            runCatching { service.resolveAppeal(appeal, overturn) }
                .onSuccess {
                    appeals.removeAll { it.id == appeal.id }
                    onCount(appeals.size)
                    onNotice(if (overturn) "Решение отменено, доступ восстановлен" else "Решение оставлено в силе")
                }
                .onFailure { onNotice(it.moderationMessage("Не удалось сохранить решение.")) }
        }
    }
    LaunchedEffect(Unit) { load() }
    AloRefreshableList(
        isLoading = isLoading,
        isEmpty = appeals.isEmpty(),
        onRefresh = { load() },
        empty = { AloUnavailableView(Icons.Outlined.Balance, "Обжалований нет", "Когда кто-то оспорит решение, оно появится здесь.") }
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(appeals, key = { _, appeal -> appeal.id }) { _, appeal ->
                Column(Modifier.animateItem()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            appeal.user?.let { user ->
                                AvatarBadge(name = user.name, avatarUrl = user.avatarUrl, verified = false, size = 36.dp, colorSeed = user.id.toString())
                                Column(Modifier.weight(1f)) {
                                    Text(user.name, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(appeal.actionTitle, color = AloNativeAppMuted, fontSize = 12.sp, maxLines = 1)
                                }
                            } ?: Spacer(Modifier.weight(1f))
                            Text(formatPostTimestamp(appeal.createdAt), color = AloNativeAppMuted, fontSize = 12.sp)
                        }
                        Text("«${appeal.text}»", color = AloNativeAppText, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AloModerationButton("Отменить решение", prominent = true) { resolve(appeal, overturn = true) }
                            AloModerationButton("Оставить в силе", prominent = false) { resolve(appeal, overturn = false) }
                        }
                    }
                    AloListSeparator()
                }
            }
        }
    }
}

/** iOS's .borderedProminent (green) and .bordered buttons. */
@Composable
private fun AloModerationButton(title: String, prominent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (prominent) AloNativeAppSuccess else AloNativeAppText.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (prominent) Color.White else AloNativeAppAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// MARK: - Audit log

private val AloModerationAuditEntry.icon: ImageVector
    get() = when (action) {
        "report_rejected" -> Icons.Outlined.HighlightOff
        "appeal_overturned" -> Icons.Outlined.Replay
        "appeal_upheld" -> Icons.Outlined.Verified
        "warn" -> Icons.Outlined.ReportProblem
        "block", "ban", "account_blocked", "account_banned" -> Icons.Outlined.DoNotTouch
        "account_active" -> Icons.Outlined.Replay
        "moderator_granted", "moderator_updated" -> Icons.Rounded.Shield
        "admin_granted" -> Icons.Rounded.AdminPanelSettings
        "moderator_revoked", "admin_revoked" -> Icons.Rounded.PersonRemove
        else -> Icons.Outlined.DeleteOutline
    }

@Composable
private fun AloAuditLogList(service: AloModerationService) {
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<AloModerationAuditEntry>() }
    var nextBefore by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { service.audit(null) }.onSuccess { (page, next) -> entries.clear(); entries.addAll(page); nextBefore = next }
        isLoading = false
    }
    fun loadMore() {
        val before = nextBefore ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        scope.launch {
            runCatching { service.audit(before) }.onSuccess { (page, next) -> entries.addAll(page); nextBefore = next }
            isLoadingMore = false
        }
    }
    LaunchedEffect(Unit) { load() }
    AloRefreshableList(
        isLoading = isLoading,
        isEmpty = entries.isEmpty(),
        onRefresh = { load() },
        empty = { AloUnavailableView(Icons.Outlined.Assignment, "Журнал пуст", "Здесь будут все решения модераторов.") }
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                if (index == entries.lastIndex && nextBefore != null) LaunchedEffect(entry.id) { loadMore() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(AloNativeAppAccent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(entry.icon, null, tint = AloNativeAppAccent, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(entry.actor.name, entry.target?.let { "→ ${it.name}" }).joinToString(" "),
                            color = AloNativeAppMuted, fontSize = 13.sp
                        )
                        if (entry.readableDetails.isNotEmpty()) Text(entry.readableDetails, color = AloNativeAppText.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                    Text(formatPostTimestamp(entry.createdAt), color = AloNativeAppMuted, fontSize = 12.sp)
                }
                AloListSeparator(start = 58)
            }
        }
    }
}

// MARK: - Ads admin

@Composable
private fun AloAdCampaignList(service: AloModerationService) {
    val campaigns = remember { mutableStateListOf<AloAdCampaign>() }
    var isLoading by remember { mutableStateOf(true) }
    // null: closed; Pair(null): a new campaign.
    var editing by remember { mutableStateOf<Pair<AloAdCampaign?, Int>?>(null) }
    var editorKey by remember { mutableIntStateOf(0) }
    suspend fun load() {
        runCatching { service.campaigns() }.onSuccess { campaigns.clear(); campaigns.addAll(it) }
        isLoading = false
    }
    LaunchedEffect(Unit) { load() }
    AloRefreshableList(isLoading = isLoading, isEmpty = false, onRefresh = { load() }, empty = {}) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp)) {
            item(key = "new") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(AloSquircle(26.dp)).background(AloNativeAppSurfaceRaised.copy(alpha = 0.7f))
                        .clickable { editing = null to ++editorKey }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Rounded.AddCircle, null, tint = AloNativeAppAccent, modifier = Modifier.size(24.dp))
                    Text("Новая кампания", color = AloNativeAppAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Реклама Alo показывается с меткой «Реклама» в ленте (одна на 6 публикаций) и под постами каналов. Не больше трёх показов одного объявления в день.",
                    color = AloNativeAppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 26.dp)
                )
            }
            itemsIndexed(campaigns, key = { _, campaign -> campaign.id }) { index, campaign ->
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) 26.dp else 0.dp, topEnd = if (index == 0) 26.dp else 0.dp,
                    bottomStart = if (index == campaigns.lastIndex) 26.dp else 0.dp, bottomEnd = if (index == campaigns.lastIndex) 26.dp else 0.dp
                )
                Column(Modifier.fillMaxWidth().clip(shape).background(AloNativeAppSurfaceRaised.copy(alpha = 0.55f))) {
                    if (index > 0) AloListSeparator()
                    AloAdCampaignRow(campaign) { editing = campaign to ++editorKey }
                }
            }
        }
    }
    editing?.let { (campaign, key) ->
        androidx.compose.runtime.key(key) {
            AloAdCampaignEditor(
                campaign = campaign,
                service = service,
                onDismiss = { editing = null },
                onSaved = { saved ->
                    val index = campaigns.indexOfFirst { it.id == saved.id }
                    if (index >= 0) campaigns[index] = saved else campaigns.add(0, saved)
                    editing = null
                }
            )
        }
    }
}

@Composable
private fun AloAdCampaignRow(campaign: AloAdCampaign, onClick: () -> Unit) {
    val active = campaign.status == "active"
    val statusColor = if (active) AloNativeAppSuccess else AloNativeAppMuted
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(campaign.name, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier.height(22.dp).clip(RoundedCornerShape(percent = 50)).background(statusColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text(campaign.statusTitle, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Text("${if (campaign.ad.placement == "channel") "Каналы" else "Лента"} · ${campaign.ad.advertiser}", color = AloNativeAppMuted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth()) {
            AloCampaignMetric(formatCompactCount(campaign.impressions), "показов", Modifier.weight(1f))
            AloCampaignMetric(formatCompactCount(campaign.reach), "охват", Modifier.weight(1f))
            AloCampaignMetric(formatCompactCount(campaign.clicks), "кликов", Modifier.weight(1f))
            AloCampaignMetric(String.format(Locale.US, "%.1f%%", campaign.ctr), "CTR", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AloCampaignMetric(value: String, title: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default, maxLines = 1)
        Text(title, color = AloNativeAppMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun AloAdCampaignEditor(
    campaign: AloAdCampaign?,
    service: AloModerationService,
    onDismiss: () -> Unit,
    onSaved: (AloAdCampaign) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(campaign?.name.orEmpty()) }
    var advertiser by remember { mutableStateOf(campaign?.ad?.advertiser ?: "Alo") }
    var title by remember { mutableStateOf(campaign?.ad?.title.orEmpty()) }
    var text by remember { mutableStateOf(campaign?.ad?.body.orEmpty()) }
    var ctaLabel by remember { mutableStateOf(campaign?.ad?.ctaLabel ?: "Подробнее") }
    var ctaUrl by remember { mutableStateOf(campaign?.ad?.ctaUrl.orEmpty()) }
    var placement by remember { mutableStateOf(campaign?.ad?.placement ?: "feed") }
    var status by remember { mutableStateOf(campaign?.status ?: "draft") }
    var impressionLimit by remember { mutableIntStateOf(campaign?.impressionLimit ?: 0) }
    var error by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val preview = AloAd(
        id = campaign?.id ?: 0, placement = placement, label = "Реклама", advertiser = advertiser, title = title,
        body = text.ifBlank { "Текст объявления" }, ctaLabel = ctaLabel.ifBlank { "Подробнее" }, ctaUrl = ctaUrl, why = ""
    )
    fun save() {
        isSaving = true
        error = ""
        val fields = AloAdCampaignFields(name, advertiser, title, text, ctaLabel, ctaUrl, placement, status, impressionLimit)
        scope.launch {
            runCatching { service.saveCampaign(campaign?.id, fields) }
                .onSuccess(onSaved)
                .onFailure { error = it.moderationMessage("Не удалось сохранить кампанию.") }
            isSaving = false
        }
    }
    AloFormSheet(
        title = if (campaign == null) "Новая кампания" else "Кампания",
        onDismiss = onDismiss,
        trailing = "Сохранить",
        trailingEnabled = !isSaving,
        onTrailing = ::save
    ) {
        AloFormSection(header = "Кампания") {
            AloFormTextField(name, { name = it }, "Название")
            AloFormDivider()
            AloFormMenuRow("Где показывать", listOf("feed" to "Лента", "channel" to "Каналы"), placement) { placement = it }
            AloFormDivider()
            AloFormMenuRow(
                "Статус",
                listOf("draft" to "Черновик", "active" to "Показывается", "paused" to "На паузе", "ended" to "Завершена"),
                status
            ) { status = it }
            AloFormDivider()
            AloFormStepperRow(
                if (impressionLimit == 0) "Без лимита показов" else "Лимит: ${formatCompactCount(impressionLimit)}",
                canDecrease = impressionLimit > 0,
                onDecrease = { impressionLimit = (impressionLimit - 1_000).coerceAtLeast(0) },
                onIncrease = { impressionLimit = (impressionLimit + 1_000).coerceAtMost(1_000_000) }
            )
        }
        AloFormSection(header = "Объявление") {
            AloFormTextField(advertiser, { advertiser = it }, "Рекламодатель")
            AloFormDivider()
            AloFormTextField(title, { title = it }, "Заголовок")
            AloFormDivider()
            AloFormTextField(text, { text = it }, "Текст", minLines = 3, maxLines = 6)
            AloFormDivider()
            AloFormTextField(ctaLabel, { ctaLabel = it }, "Кнопка")
            AloFormDivider()
            AloFormTextField(ctaUrl, { ctaUrl = it }, "Ссылка (https:// или alo://)", keyboardType = KeyboardType.Uri)
        }
        AloFormSection(header = "Так увидят пользователи") {
            AloSponsoredCard(preview, onOpen = {}, onWhy = {}, onHide = {}, onReport = {})
        }
        if (error.isNotEmpty()) AloFormSection { AloFormTextRow(error, AloNativeAppDanger) }
    }
}

// MARK: - Appeals

/** Contest a moderation decision about you, from its notification or from a blocked sign-in. */
@Composable
internal fun AloAppealSheet(title: String, onDismiss: () -> Unit, onSend: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var isSent by remember { mutableStateOf(false) }
    fun send() {
        isSending = true
        error = ""
        scope.launch {
            runCatching { onSend(text.trim()) }
                .onSuccess { isSent = true }
                .onFailure { error = it.moderationMessage("Не удалось отправить обжалование.") }
            isSending = false
        }
    }
    AloFormSheet(
        title = title,
        onDismiss = onDismiss,
        leading = if (isSent) "Готово" else "Отмена",
        trailing = if (isSent) null else "Отправить",
        trailingEnabled = !isSending && text.trim().length >= 10,
        onTrailing = ::send,
        fullHeight = false
    ) {
        if (isSent) {
            AloFormSection {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Verified, null, tint = AloNativeAppSuccess, modifier = Modifier.size(24.dp))
                    Text("Обжалование отправлено. Модераторы рассмотрят его и пришлют ответ.", color = AloNativeAppSuccess, fontSize = 17.sp)
                }
            }
        } else {
            AloFormSection(footer = "Модератор, который не принимал первое решение, посмотрит ещё раз. Ответ придёт в уведомлениях.") {
                AloFormTextField(text, { text = it }, "Почему решение неверно?", minLines = 4, maxLines = 8)
            }
            if (error.isNotEmpty()) AloFormSection { AloFormTextRow(error, AloNativeAppDanger) }
        }
        Spacer(Modifier.height(80.dp))
    }
}
