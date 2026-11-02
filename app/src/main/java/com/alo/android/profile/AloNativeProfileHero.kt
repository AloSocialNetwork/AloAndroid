package com.alo.android

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Add

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.util.Locale

/**
 * iOS's ProfileHeroCard for your own profile (and the web's profile head): a 154dp banner with the settings and appearance glass
 * buttons, a 116dp avatar with a camera badge overlapping it, your name and @handle beside it.
 */
@Composable
internal fun ProfileHeroPanel(
    me: AloCurrentUser,
    stats: AloProfileStats,
    relations: AloProfileRelations,
    onOpenSettings: () -> Unit,
    onOpenAvatarSheet: () -> Unit,
    onOpenCoverEditor: () -> Unit
) {
    val coverHeight = 154.dp
    val avatarSize = 116.dp
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .clip(RoundedCornerShape(AloNativeLayout.radiusLarge))
        ) {
            if (me.coverImageUrl.isNotBlank()) {
                AsyncImage(model = me.coverImageUrl, contentDescription = me.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(profileCoverBrush(me.coverStyle)))
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileGlassButton(Icons.Outlined.Settings, "Настройки профиля", onOpenSettings)
                ProfileGlassButton(Icons.Outlined.Palette, "Оформление профиля", onOpenCoverEditor)
            }
        }
        ProfileAvatarIdentityRow(
            avatarSize = avatarSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AloNativeLayout.spacingLarge),
            avatar = {
                Box {
                    // The avatar holds your stories; the camera badge changes the photo.
                    AloProfileStoryAvatar(
                        user = AloEntitySummary(id = me.id, name = me.name, username = me.username, avatarUrl = me.avatarUrl, verified = me.verified),
                        isOwn = true
                    ) { opensStories ->
                        AvatarBadge(
                            name = me.name, avatarUrl = me.avatarUrl, verified = false, size = avatarSize,
                            colorSeed = me.id.toString(), onClick = if (opensStories) null else onOpenAvatarSheet
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(AloNativeAppBackground)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(AloNativeAppAccent)
                            .clickable(onClick = onOpenAvatarSheet),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CameraAlt, "Сменить фото", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            },
            identity = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            text = me.name,
                            color = AloNativeAppText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (me.verified) Icon(Icons.Rounded.Verified, null, tint = AloNativeAppAccent, modifier = Modifier.size(20.dp))
                    }
                    Text(text = "@${me.username}", color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        )
        ProfileHeroDetails(
            bio = me.bio,
            counts = listOf(
                formatCompactCount(stats.followingCount) to aloRuPlural(stats.followingCount, "подписка", "подписки", "подписок"),
                formatCompactCount(stats.followersCount) to aloRuPlural(stats.followersCount, "подписчик", "подписчика", "подписчиков")
            ),
            meta = "",
            // From the banner's left edge, as the posts below (the owner, 2026-10-28): not indented under it.
            modifier = Modifier.padding(horizontal = 2.dp).padding(top = 10.dp)
        )
        Spacer(Modifier.height(AloNativeLayout.spacingLarge))
    }
}

/**
 * The avatar over the banner's lower edge with the identity beside it, as iOS's HStack with a negative top padding: the row lifts by half the
 * avatar and takes that height back, and its height is whichever is taller (the avatar, or the identity under its 8dp offset), so what comes next
 * never lands on the avatar.
 */
@Composable
private fun ProfileAvatarIdentityRow(
    avatarSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    /** Over the banner's lower edge, the gap to the identity and where the identity starts (null: the defaults). */
    lift: androidx.compose.ui.unit.Dp? = null,
    gap: androidx.compose.ui.unit.Dp = 10.dp,
    identityTop: androidx.compose.ui.unit.Dp? = null,
    avatar: @Composable () -> Unit,
    identity: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(content = { avatar(); identity() }, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val liftPx = (lift ?: (avatarSize / 2 - 2.dp)).roundToPx()
        val identityTopPx = (identityTop ?: (avatarSize / 2 + 8.dp)).roundToPx()
        val avatarPlaceable = measurables[0].measure(androidx.compose.ui.unit.Constraints())
        val identityWidth = (constraints.maxWidth - avatarPlaceable.width - gapPx).coerceAtLeast(0)
        val identityPlaceable = measurables[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = identityWidth))
        val height = (maxOf(avatarPlaceable.height, identityTopPx + identityPlaceable.height) - liftPx).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            avatarPlaceable.place(0, -liftPx)
            identityPlaceable.place(avatarPlaceable.width + gapPx, identityTopPx - liftPx)
        }
    }
}

/** «1 204», «12.4K»: the website's compact count (the server sends plain numbers). */
private fun formatProfileCount(value: String): String = value.toIntOrNull()?.let(::formatCompactCount) ?: value

/** «1 подписчик», «2 подписки», «5 подписчиков», as the website writes them (the server's labels are plural titles). */
private fun profileCountLabel(metric: AloProfileMetric): String {
    val count = metric.value.filter(Char::isDigit).toIntOrNull() ?: return metric.label.lowercase(Locale.getDefault())
    return when (metric.label.lowercase(Locale.getDefault())) {
        "подписки" -> aloRuPlural(count, "подписка", "подписки", "подписок")
        "подписчики" -> aloRuPlural(count, "подписчик", "подписчика", "подписчиков")
        "публикации" -> aloRuPlural(count, "публикация", "публикации", "публикаций")
        "друзья" -> aloRuPlural(count, "друг", "друга", "друзей")
        "посты" -> aloRuPlural(count, "запись", "записи", "записей")
        else -> metric.label.lowercase(Locale.getDefault())
    }
}

/** A button of the profile's action row (the website's `.btn` / `.btn.secondary`): 44dp, a pill, the label on one line. */
@Composable
private fun ProfileActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val content = if (filled) AloNativeAppButtonText else AloNativeAppText
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) AloNativeAppAccent else AloNativeAppSurfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = content.copy(alpha = if (enabled) 1f else 0.55f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Under the avatar, the full width of the card: the bio, then the counts (side by side, wrapping whole), then the date. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileHeroDetails(bio: String, counts: List<Pair<String, String>>, meta: String, modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 8.dp) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        if (bio.isNotBlank()) {
            Text(text = bio, fontSize = 17.sp, lineHeight = 22.sp, color = AloNativeAppText)
        }
        if (counts.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                counts.forEach { (value, label) -> ProfileStatText(value = value, label = label) }
            }
        }
        if (meta.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = AloNativeAppMuted, modifier = Modifier.size(15.dp))
                Text(text = meta, style = MaterialTheme.typography.bodySmall, color = AloNativeAppMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProfileGlassButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.28f))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ProfileStatText(
    value: String,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AloNativeAppText
        )
        Text(
            text = label,
            fontSize = 16.sp,
            color = AloNativeAppMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** The website's «Записи» over a profile's posts (`.section-title.posts-title`): 20sp bold and a hairline under it. */
@Composable
internal fun ProfilePostsTitle() {
    Column(Modifier.fillMaxWidth()) {
        Text("Записи", color = AloNativeAppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AloNativeAppBorder))
    }
}

@Composable
internal fun ProfilePostsTabs(
    postsTitle: String,
    selectedTab: String,
    onSelect: (String) -> Unit,
    backdrop: AloGlassBackdrop? = null
) {
    // With a backdrop the switcher is liquid glass (the posts show through it as they scroll under); the same 44dp capsule as the feed's.
    if (backdrop != null) {
        AloSocialSegments(
            titles = listOf(postsTitle, "Нравится"),
            selected = if (selectedTab == "likes") 1 else 0,
            backdrop = backdrop,
            onSelect = { onSelect(if (it == 1) "likes" else "posts") }
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
        color = AloNativeAppSurface,
        border = BorderStroke(1.dp, AloNativeAppBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeedModeTab(
                title = postsTitle,
                selected = selectedTab == "posts",
                onClick = { onSelect("posts") },
                modifier = Modifier.weight(1f)
            )
            FeedModeTab(
                title = "Нравится",
                selected = selectedTab == "likes",
                onClick = { onSelect("likes") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Someone else's profile, as iOS's ProfileHeroCard: the banner carries the back button and, in its corner, the friend or subscribe pill, a
 * message button and a menu; the 106dp avatar overlaps it with the name, @handle, counts and bio beside it.
 */
@Composable
internal fun ViewedProfileHeroPanel(
    profile: AloViewedProfile,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenAvatarSheet: () -> Unit,
    isBlocked: Boolean,
    isBlockActionInProgress: Boolean,
    onToggleBlock: () -> Unit,
    onMessageAction: () -> Unit
) {
    // The website's profile (the owner, 2026-10-28): a bar «‹ name ⋯» above the card, nothing on the cover, the buttons under
    // the bio and the counts.
    Column {
        ViewedProfileHeroCard(
            profile = profile,
            onBack = onBack,
            onPrimaryAction = onPrimaryAction,
            onOpenAvatarSheet = onOpenAvatarSheet,
            isBlocked = isBlocked,
            isBlockActionInProgress = isBlockActionInProgress,
            onToggleBlock = onToggleBlock,
            onMessageAction = onMessageAction
        )
    }
}

@Composable
private fun ViewedProfileHeroCard(
    profile: AloViewedProfile,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenAvatarSheet: () -> Unit,
    isBlocked: Boolean,
    isBlockActionInProgress: Boolean,
    onToggleBlock: () -> Unit,
    onMessageAction: () -> Unit
) {
    var blockMenuExpanded by remember(profile.id) { mutableStateOf(false) }
    val canMessage = profile.canMessage && !isBlocked
    // Staff with the «Верификация» right give or take the checkmark on the spot (admin/AloVerification.kt).
    val staffRights = LocalAloStaffRights.current
    val moderation = LocalAloModeration.current
    val onVerifiedChanged = LocalAloVerifiedChanged.current
    val heroScope = androidx.compose.runtime.rememberCoroutineScope()
    // One friend / follow button, the rule of the website and iPhone (the owner, 2026-10-28): a friend request first;
    // following only for a person who closed friend requests (a follow back makes friends on the server). «В друзьях»
    // and «Заявка отправлена» ask whether to undo them.
    var undoRelationAsked by remember(profile.id) { mutableStateOf(false) }
    val primaryAction = when (profile.kind) {
        "channel" -> "Подписаться"
        "group" -> "Написать"
        else -> when (profile.friendStatus) {
            "friends" -> "В друзьях"
            "outgoing" -> "Заявка отправлена"
            "incoming" -> "Принять заявку"
            else -> when {
                profile.canSendFriendRequest -> "В друзья" // «Добавить в друзья» did not fit (the owner, 2026-10-28)
                profile.following -> "Вы подписаны"
                profile.followedBy -> "Подписаться в ответ"
                else -> "Подписаться"
            }
        }
    }
    val primaryEnabled = when (profile.kind) {
        "user" -> when (profile.friendStatus) {
            "incoming" -> profile.incomingFriendRequestId > 0
            "outgoing" -> profile.outgoingFriendRequestId > 0
            else -> true
        }
        else -> true
    }
    val onPrimaryTap = {
        if (profile.kind == "user" && profile.friendStatus in setOf("friends", "outgoing")) undoRelationAsked = true
        else onPrimaryAction()
    }
    if (undoRelationAsked) {
        val friends = profile.friendStatus == "friends"
        AlertDialog(
            onDismissRequest = { undoRelationAsked = false },
            // The name is not declined («Удалить Машка из друзей?»): it goes into the text as it is.
            title = { Text(if (friends) "Удалить из друзей?" else "Отменить заявку в друзья?") },
            text = { Text(if (friends) "${profile.title} больше не будет в списке ваших друзей." else "${profile.title} не получит вашу заявку.") },
            confirmButton = {
                TextButton(onClick = {
                    undoRelationAsked = false
                    onPrimaryAction()
                }) {
                    Text(if (friends) "Удалить" else "Отменить заявку", color = Color(0xFFFF5C68))
                }
            },
            dismissButton = {
                TextButton(onClick = { undoRelationAsked = false }) { Text("Оставить") }
            }
        )
    }
    // The website's sizes: a 168dp cover, a 112dp avatar in a 4dp ring of the page colour.
    val coverHeight = 168.dp
    val avatarSize = 112.dp
    val ringWidth = 4.dp
    Row(
        Modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight).padding(horizontal = AloScreenChrome.BackStart),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AloGlassCircleButton(onClick = onBack, description = "Назад", diameter = AloScreenChrome.BackDiameter)
        Text(
            text = profile.title,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            color = AloNativeAppText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (profile.kind == "user") {
            Box {
                AloNativeGlassPanel(shape = CircleShape) {
                    Box(
                        Modifier
                            .size(AloScreenChrome.BackDiameter)
                            .clip(CircleShape)
                            .clickable(enabled = !isBlockActionInProgress) { blockMenuExpanded = true }
                            .semantics { contentDescription = "Действия с пользователем" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MoreHoriz, null, tint = AloNativeAppText, modifier = Modifier.size(22.dp))
                    }
                }
            AloDropdownMenu(expanded = blockMenuExpanded, onDismissRequest = { blockMenuExpanded = false }) {
                val secretEntry = LocalAloSecretChatEntry.current
                if (secretEntry != null && secretEntry.available && !isBlocked && profile.kind == "user") {
                    DropdownMenuItem(
                        text = { Text("Начать секретный чат", color = AloSecretGreen) },
                        onClick = {
                            blockMenuExpanded = false
                            secretEntry.start(profile.id)
                        }
                    )
                }
                if ("verification" in staffRights && moderation != null) {
                    DropdownMenuItem(
                        text = { Text(if (profile.verified) "Снять галочку" else "Выдать галочку") },
                        onClick = {
                            blockMenuExpanded = false
                            val next = !profile.verified
                            heroScope.launch {
                                runCatching { moderation.setVerified("user", profile.id, next) }
                                    .onSuccess { onVerifiedChanged("user", profile.id, next) }
                            }
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (isBlocked) "Разблокировать" else "Заблокировать") },
                    enabled = !isBlockActionInProgress,
                    onClick = {
                        blockMenuExpanded = false
                        onToggleBlock()
                    }
                )
            }
            }
        } else {
            Spacer(Modifier.size(AloScreenChrome.BackDiameter))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = AloNativeLayout.pageInset).padding(top = 4.dp, bottom = 2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .clip(RoundedCornerShape(AloNativeLayout.radiusLarge))
        ) {
            if (profile.coverImageUrl.isNotBlank()) {
                AsyncImage(
                    model = profile.coverImageUrl,
                    contentDescription = profile.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(profileCoverBrush(profile.coverStyle)))
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0x14070B12), Color(0x36070B12))))
            )
        }
        // The website's geometry, measured at a phone's width (#profile, 402px): the ringed avatar starts 17 in and 45 above the
        // cover's lower edge, the name 19 to its right and 63 below its top, the bio 15 under the ring.
        ProfileAvatarIdentityRow(
            avatarSize = avatarSize + ringWidth * 2,
            modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = AloNativeLayout.spacingLarge),
            lift = 45.dp,
            gap = 19.dp,
            identityTop = 63.dp,
            avatar = {
                AloProfileStoryAvatar(
                    user = AloEntitySummary(id = profile.id, name = profile.title, username = profile.username,
                        avatarUrl = profile.avatarUrl, verified = profile.verified),
                    isOwn = false
                ) { opensStories ->
                    // The checkmark sits by the name (as on your own profile): drawn on the avatar, the story ring cut it to a sliver.
                    Box {
                    Box(
                        Modifier.size(avatarSize + ringWidth * 2).clip(CircleShape).background(AloNativeAppBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarBadge(
                            name = profile.title,
                            avatarUrl = profile.avatarUrl,
                            verified = false,
                            size = avatarSize,
                            colorSeed = profile.id.toString(),
                            onClick = if (opensStories) null else onOpenAvatarSheet
                        )
                    }
                    if (profile.kind == "user" && profile.status == "в сети") {
                        // The website's `.profile-online`: a green dot in a ring of the page colour, at the lower right.
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-6).dp, y = (-6).dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(AloNativeAppBackground)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF30D158))
                                .semantics { contentDescription = "В сети" }
                        )
                    }
                    }
                }
            },
            identity = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            text = profile.title,
                            fontSize = 26.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AloNativeAppText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (profile.verified) Icon(Icons.Rounded.Verified, "Подтверждённый профиль", tint = AloNativeAppAccent, modifier = Modifier.size(20.dp))
                    }
                    if (profile.username.isNotBlank()) {
                        Text(
                            text = "@${profile.username}",
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 17.sp,
                            color = AloNativeAppMuted
                        )
                    }
                }
            }
        )
        // The bio, the counts and the registration date go under the avatar, across the whole card (as on the website and the
        // iPhone): beside the avatar a long bio ran far down in a narrow column.
        // The website shows no registration date on a profile.
        ProfileHeroDetails(
            bio = profile.description,
            counts = profile.metrics.take(2).map { metric -> formatProfileCount(metric.value) to profileCountLabel(metric) },
            meta = "",
            // From the banner's left edge, as the posts below (the owner, 2026-10-28): not indented under it; the website's
            // 12 between the bio and the counts.
            modifier = Modifier.padding(horizontal = 2.dp).padding(top = 15.dp),
            spacing = 12.dp
        )
        // The website's `.profile-actions`: two buttons of the same size, the friend/follow one filled when it calls to act.
        val showsPrimary = !(profile.kind == "channel" && profile.canManage)
        val showsMessage = profile.kind == "user"
        if (showsPrimary || showsMessage) {
            val invitation = when (profile.kind) {
                "user" -> when (profile.friendStatus) {
                    "friends", "outgoing" -> false
                    "incoming" -> true
                    else -> profile.canSendFriendRequest || !profile.following
                }
                else -> true
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showsPrimary) ProfileActionButton(
                    title = primaryAction,
                    icon = if (invitation && profile.kind == "user" && profile.friendStatus == "none" && profile.canSendFriendRequest) Icons.Rounded.Add else null,
                    filled = invitation,
                    enabled = primaryEnabled,
                    onClick = onPrimaryTap,
                    modifier = Modifier.weight(1f)
                )
                if (showsMessage) ProfileActionButton(
                    title = "Сообщение",
                    icon = Icons.Rounded.ChatBubbleOutline,
                    filled = false,
                    enabled = canMessage,
                    onClick = onMessageAction,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(AloNativeLayout.spacingSmall))
    }
}

internal fun profileCoverBrush(style: String): Brush {
    // The ten banner colours the server accepts, exactly as iOS's aloProfileGradient draws them.
    fun rgb(r: Double, g: Double, b: Double) = Color(r.toFloat(), g.toFloat(), b.toFloat())
    val colors = when (style.trim().lowercase(Locale.getDefault())) {
        "sunset" -> listOf(rgb(0.60, 0.20, 0.07), rgb(0.92, 0.35, 0.03), rgb(0.98, 0.72, 0.18))
        "violet" -> listOf(rgb(0.36, 0.13, 0.71), rgb(0.49, 0.23, 0.92), rgb(0.73, 0.67, 0.98))
        "azure" -> listOf(rgb(0.02, 0.34, 0.52), rgb(0.02, 0.52, 0.78), rgb(0.49, 0.83, 0.98))
        "ocean" -> listOf(rgb(0.03, 0.16, 0.40), rgb(0.05, 0.36, 0.62), rgb(0.16, 0.72, 0.78))
        "rose" -> listOf(rgb(0.62, 0.09, 0.30), rgb(0.86, 0.15, 0.47), rgb(0.98, 0.66, 0.82))
        "emerald" -> listOf(rgb(0.06, 0.46, 0.43), rgb(0.09, 0.64, 0.29), rgb(0.37, 0.92, 0.68))
        "amber" -> listOf(rgb(0.55, 0.32, 0.02), rgb(0.88, 0.58, 0.05), rgb(1.00, 0.84, 0.36))
        "forest" -> listOf(rgb(0.07, 0.22, 0.12), rgb(0.16, 0.40, 0.20), rgb(0.53, 0.70, 0.35))
        "graphite" -> listOf(rgb(0.10, 0.11, 0.13), rgb(0.24, 0.26, 0.30), rgb(0.50, 0.53, 0.58))
        else -> listOf(rgb(0.00, 0.33, 0.86), rgb(0.04, 0.52, 1.00), rgb(0.42, 0.27, 0.93))
    }
    return Brush.linearGradient(colors = colors, start = Offset.Zero, end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
}
