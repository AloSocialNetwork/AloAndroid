package com.alo.android

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.runtime.mutableStateListOf

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.material3.DropdownMenuItem

import androidx.compose.material3.DropdownMenu

import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight

import androidx.compose.material.icons.rounded.Check

import androidx.compose.material.icons.rounded.Close

import androidx.compose.material.icons.rounded.CallMerge

import androidx.compose.material.icons.rounded.VisibilityOff

import androidx.compose.material.icons.rounded.Visibility

import androidx.compose.material.icons.rounded.LockOpen

import androidx.compose.material.icons.rounded.Lock

import androidx.compose.material.icons.rounded.MoreHoriz

import androidx.compose.material.icons.rounded.LinkOff

import androidx.compose.material.icons.rounded.Link

import androidx.compose.material.icons.rounded.AddLink

import androidx.compose.foundation.layout.Spacer

import androidx.compose.ui.unit.sp

import androidx.compose.material.icons.rounded.RadioButtonUnchecked

import androidx.compose.material.icons.rounded.CheckCircle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale
import java.time.Instant

/** «Выбрать» in the member list: remove or ban everyone chosen in one go (roomId, userIds, ban). */
internal val LocalAloGroupMemberBatch = androidx.compose.runtime.staticCompositionLocalOf<((Int, List<Int>, Boolean) -> Unit)?> { null }

@Composable
internal fun GroupChatSettingsSheet(
    detail: AloConversationDetail,
    onDismiss: () -> Unit,
    onPickAvatar: () -> Unit,
    onSaveTitle: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (Int) -> Unit,
    onUpdateMemberRole: (Int, String) -> Unit,
    management: AloGroupManagementSnapshot?,
    isManagementLoading: Boolean,
    onLoadManagement: () -> Unit,
    onLoadEarlierAdminEvents: () -> Unit,
    onUpdateSettings: (AloGroupPolicy) -> Unit,
    onCreateTopic: (String, String) -> Unit,
    onUpdateTopic: (Int, String?, String?, Boolean?, Boolean?) -> Unit,
    onDeleteTopic: (Int) -> Unit,
    onMergeTopic: (Int, Int) -> Unit,
    onReviewJoinRequest: (Int, Boolean) -> Unit,
    onCreateInviteLink: (String, Boolean, Int, String) -> Unit,
    onUpdateInviteLink: (Int, String, Boolean, Int, String) -> Unit,
    onRevokeInviteLink: (Int) -> Unit,
    onLoadInviteStats: suspend (Int) -> AloGroupInviteStats,
    onUpdateMemberAccess: (
        Int,
        String,
        String,
        Boolean,
        Map<String, Boolean>,
        Map<String, Boolean>
    ) -> Unit,
    onTransferOwnership: (Int, String) -> Unit,
    onBanMember: (Int, String, String) -> Unit,
    onUnbanMember: (Int) -> Unit
) {
    // iOS's GroupManagementSheet: «Управление» with Закрыть / Сохранить, then the community's
    // sections in a Form. Android keeps its name, photo and «добавить по @username» on top.
    val isChannel = detail.isChannelCommunity()
    val isOwner = detail.memberRole.lowercase(Locale.getDefault()) == "owner"
    var title by rememberSaveable(detail.roomId) { mutableStateOf(detail.title) }
    var policy by remember(detail.roomId, management?.policy, detail.groupPolicy) {
        mutableStateOf((management?.policy ?: detail.groupPolicy)?.let { detail.withImmutableCommunityKind(it) })
    }
    val originalPolicy = (management?.policy ?: detail.groupPolicy)?.let { detail.withImmutableCommunityKind(it) }
    var usernameToAdd by rememberSaveable(detail.roomId) { mutableStateOf("") }
    var memberQuery by rememberSaveable(detail.roomId) { mutableStateOf("") }
    var memberRoleFilter by rememberSaveable(detail.roomId) { mutableStateOf("all") }
    var selectedMember by remember(detail.roomId) { mutableStateOf<AloGroupMember?>(null) }
    val batchRemove = LocalAloGroupMemberBatch.current
    var isSelectingMembers by remember(detail.roomId) { mutableStateOf(false) }
    val selectedMemberIds = remember(detail.roomId) { mutableStateListOf<Int>() }
    var pendingBatchBan by remember(detail.roomId) { mutableStateOf<Boolean?>(null) }
    var inviteLabel by rememberSaveable(detail.roomId) { mutableStateOf("") }
    var inviteRequiresApproval by rememberSaveable(detail.roomId) { mutableStateOf(false) }
    var inviteUsageLimit by rememberSaveable(detail.roomId) { mutableStateOf(0) }
    var inviteExpiryHours by rememberSaveable(detail.roomId) { mutableStateOf(0) }
    var inviteEditing by remember { mutableStateOf<AloGroupInviteLink?>(null) }
    var inviteStatsTarget by remember { mutableStateOf<AloGroupInviteLink?>(null) }
    var inviteStats by remember { mutableStateOf<AloGroupInviteStats?>(null) }
    var inviteStatsLoading by remember { mutableStateOf(false) }
    var inviteStatsError by remember { mutableStateOf("") }
    var inviteStatsReloadVersion by remember { mutableStateOf(0) }
    var topicTitle by rememberSaveable(detail.roomId) { mutableStateOf("") }
    var topicEmoji by rememberSaveable(detail.roomId) { mutableStateOf("") }
    var topicPendingDeletion by remember { mutableStateOf<AloForumTopic?>(null) }
    var topicPendingMerge by remember { mutableStateOf<AloForumTopic?>(null) }
    val members = management?.members ?: detail.members
    val visibleMembers = members.filter { member ->
        val roleMatches = when (memberRoleFilter) {
            "admins" -> member.role == "owner" || member.role == "admin"
            "members" -> member.role != "owner" && member.role != "admin"
            else -> true
        }
        val query = memberQuery.trim().lowercase(Locale.getDefault())
        roleMatches && (query.isBlank() || member.user.name.lowercase(Locale.getDefault()).contains(query) ||
            member.user.username.lowercase(Locale.getDefault()).contains(query))
    }
    val titleChanged = title.trim().length >= 2 && title.trim() != detail.title.trim()
    val policyChanged = policy != null && policy != originalPolicy
    LaunchedEffect(detail.roomId) { onLoadManagement() }
    LaunchedEffect(inviteStatsTarget?.id, inviteStatsReloadVersion) {
        val target = inviteStatsTarget ?: return@LaunchedEffect
        inviteStats = null
        inviteStatsError = ""
        inviteStatsLoading = true
        runCatching { onLoadInviteStats(target.id) }
            .onSuccess { inviteStats = it }
            .onFailure { inviteStatsError = it.message ?: "Не удалось загрузить статистику ссылки." }
        inviteStatsLoading = false
    }
    fun save() {
        policy?.takeIf { policyChanged }?.let(onUpdateSettings)
        if (titleChanged) onSaveTitle(title.trim()) else onDismiss()
    }

    AloFormSheet(
        title = "Управление",
        onDismiss = onDismiss,
        leading = "Закрыть",
        trailing = "Сохранить",
        trailingEnabled = titleChanged || policyChanged,
        onTrailing = ::save
    ) {
        // Name and photo.
        AloFormSection {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(Modifier.size(60.dp).clip(CircleShape).clickable(onClick = onPickAvatar), contentAlignment = Alignment.Center) {
                    AvatarBadge(name = detail.title, avatarUrl = detail.avatarUrl, verified = detail.verified, size = 60.dp, colorSeed = detail.peerId.toString())
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(AloNativeChatAccentBlue),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Edit, "Сменить фото", tint = Color.White, modifier = Modifier.size(13.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box {
                        if (title.isEmpty()) Text(if (isChannel) "Название канала" else "Название группы", color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it.take(60) },
                            singleLine = true,
                            textStyle = TextStyle(color = AloNativeAppText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(AloNativeAppAccent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "${detail.memberCount} ${if (isChannel) "подписчиков" else "участников"} · ${groupRoleLabel(detail.memberRole)}",
                        color = AloNativeAppMuted, fontSize = 13.sp
                    )
                }
            }
        }

        policy?.let { current ->
            AloFormSection(header = "Тип и доступ") {
                AloFormValueRow("Тип", if (isChannel) "Канал" else "Группа")
                AloFormDivider()
                AloFormMenuRow(
                    "Вступление",
                    listOf("invite" to "По приглашению", "request" to "По заявке", "public" to "Публичное"),
                    current.joinPolicy.ifBlank { "invite" }
                ) { policy = current.copy(joinPolicy = it) }
                if (current.joinPolicy == "public") {
                    AloFormDivider()
                    AloFormTextField(current.username, { policy = current.copy(username = it.trim().take(32)) }, "Публичное имя")
                }
                if (!isChannel) {
                    AloFormDivider()
                    AloFormSwitchRow("Темы", current.isForum) { policy = current.copy(isForum = it) }
                    AloFormDivider()
                    AloFormSwitchRow("История для новых участников", current.historyVisible) { policy = current.copy(historyVisible = it) }
                    AloFormDivider()
                    AloFormMenuRow(
                        "Медленный режим",
                        listOf(0, 10, 30, 60, 300, 3600).map { it.toString() to slowModeLabel(it) },
                        current.slowModeSeconds.toString()
                    ) { policy = current.copy(slowModeSeconds = it.toInt()) }
                }
                AloFormDivider()
                AloFormMenuRow(
                    "Автоудаление",
                    listOf(0, 86400, 604800, 2592000, 7776000, 31536000).map { it.toString() to autoDeleteLabel(it) },
                    current.autoDeleteSeconds.toString()
                ) { policy = current.copy(autoDeleteSeconds = it.toInt()) }
            }
            AloFormSection(
                footer = if (isChannel) "Подписчики не смогут пересылать, копировать и сохранять публикации канала."
                else "Участники не смогут пересылать, копировать и сохранять сообщения из группы."
            ) {
                AloFormSwitchRow("Запретить копирование", current.protectedContent) { policy = current.copy(protectedContent = it) }
            }
            if (!isChannel) {
                AloFormSection(header = "Разрешения участников") {
                    groupPermissionItems.forEachIndexed { index, (key, label) ->
                        if (index > 0) AloFormDivider()
                        AloFormSwitchRow(label, current.defaultPermissions[key] == true) { enabled ->
                            policy = current.copy(defaultPermissions = current.defaultPermissions + (key to enabled))
                        }
                    }
                }
            }
        }

        AloFormSection(header = "Ссылки приглашения") {
            AloFormTextField(inviteLabel, { inviteLabel = it.take(40) }, "Название ссылки")
            AloFormDivider()
            AloFormSwitchRow("Одобрять заявки", inviteRequiresApproval) { inviteRequiresApproval = it }
            AloFormDivider()
            AloFormMenuRow(
                "Лимит вступлений",
                listOf(0 to "Без лимита", 1 to "1", 10 to "10", 100 to "100").map { (value, label) -> value.toString() to label },
                inviteUsageLimit.toString()
            ) { inviteUsageLimit = it.toInt() }
            AloFormDivider()
            AloFormMenuRow(
                "Срок",
                listOf(0, 24, 168, 720).map { it.toString() to groupInviteExpiryLabel(it) },
                inviteExpiryHours.toString()
            ) { inviteExpiryHours = it.toInt() }
            AloFormDivider()
            AloFormIconButtonRow(Icons.Rounded.AddLink, "Создать ссылку", enabled = !isManagementLoading) {
                onCreateInviteLink(inviteLabel.trim(), inviteRequiresApproval, inviteUsageLimit, inviteExpiryIso(inviteExpiryHours))
                inviteLabel = ""
            }
            management?.invites.orEmpty().forEach { invite ->
                AloFormDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (invite.isActive) Icons.Rounded.Link else Icons.Rounded.LinkOff, null,
                        tint = if (invite.isActive) AloNativeAppAccent else AloNativeAppMuted, modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.weight(1f).clickable { inviteStatsTarget = invite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(invite.label.ifBlank { "Ссылка без названия" }, color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(groupInviteSummary(invite), color = AloNativeAppMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        invite.creator?.name?.takeIf { it.isNotBlank() }?.let {
                            Text("Создал: $it", color = AloNativeAppMuted.copy(alpha = 0.82f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (invite.isActive) {
                        AloFormIconButton(Icons.Rounded.Edit, "Изменить ссылку", AloNativeAppAccent) { inviteEditing = invite }
                        AloFormIconButton(Icons.Rounded.Delete, "Отозвать ссылку", AloNativeAppDanger) { onRevokeInviteLink(invite.id) }
                    }
                }
            }
        }

        if (!isChannel && policy?.isForum == true) {
            AloFormSection(header = "Темы форума") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(58.dp, 36.dp), contentAlignment = Alignment.Center) {
                        if (topicEmoji.isEmpty()) Text("Иконка", color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 15.sp)
                        BasicTextField(
                            value = topicEmoji, onValueChange = { topicEmoji = it.take(4) }, singleLine = true,
                            textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp, textAlign = TextAlign.Center),
                            cursorBrush = SolidColor(AloNativeAppAccent), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (topicTitle.isEmpty()) Text("Новая тема", color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 17.sp)
                        BasicTextField(
                            value = topicTitle, onValueChange = { topicTitle = it.take(60) }, singleLine = true,
                            textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp),
                            cursorBrush = SolidColor(AloNativeAppAccent), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val canCreate = topicTitle.isNotBlank()
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (canCreate) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                            .clickable(enabled = canCreate) {
                                onCreateTopic(topicTitle.trim(), topicEmoji.trim())
                                topicTitle = ""
                                topicEmoji = ""
                            },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Add, "Создать тему", tint = if (canCreate) Color.White else AloNativeAppMuted, modifier = Modifier.size(18.dp)) }
                }
                management?.topics.orEmpty().forEach { topic ->
                    AloFormDivider()
                    var menuOpen by remember(topic.id) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(AloNativeAppSurface), contentAlignment = Alignment.Center) {
                            Text(topic.iconEmoji.ifBlank { if (topic.isGeneral) "💬" else "#" }, fontSize = 19.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(topic.title, color = AloNativeAppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    topic.isGeneral && topic.isHidden -> "Основная тема · скрыта"
                                    topic.isGeneral -> "Основная тема"
                                    topic.isClosed -> "Закрыта"
                                    else -> "Открыта"
                                },
                                color = AloNativeAppMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box {
                            AloFormIconButton(Icons.Rounded.MoreHoriz, "Действия с темой", AloNativeAppMuted) { menuOpen = true }
                            AloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = AloNativeAppSurface) {
                                DropdownMenuItem(
                                    text = { Text(if (topic.isClosed) "Открыть тему" else "Закрыть тему", color = AloNativeAppText) },
                                    leadingIcon = { Icon(if (topic.isClosed) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = AloNativeAppText) },
                                    onClick = { menuOpen = false; onUpdateTopic(topic.id, null, null, !topic.isClosed, null) }
                                )
                                if (topic.isGeneral) {
                                    DropdownMenuItem(
                                        text = { Text(if (topic.isHidden) "Показать общую тему" else "Скрыть общую тему", color = AloNativeAppText) },
                                        leadingIcon = { Icon(if (topic.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, tint = AloNativeAppText) },
                                        onClick = { menuOpen = false; onUpdateTopic(topic.id, null, null, null, !topic.isHidden) }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Объединить тему", color = AloNativeAppText) },
                                        leadingIcon = { Icon(Icons.Rounded.CallMerge, null, tint = AloNativeAppText) },
                                        onClick = { menuOpen = false; topicPendingMerge = topic }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Удалить тему", color = AloNativeAppDanger) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = AloNativeAppDanger) },
                                        onClick = { menuOpen = false; topicPendingDeletion = topic }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val requests = management?.requests.orEmpty()
        if (requests.isNotEmpty()) {
            AloFormSection(header = "Заявки на вступление") {
                requests.forEachIndexed { index, request ->
                    if (index > 0) AloFormDivider()
                    AloUserCell(
                        name = request.user.name,
                        status = "@${request.user.username}",
                        avatarUrl = request.user.avatarUrl,
                        colorSeed = request.user.id.toString(),
                        showDivider = false,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                AloFormIconButton(Icons.Rounded.Close, "Отклонить", AloNativeAppDanger) { onReviewJoinRequest(request.user.id, false) }
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(AloNativeAppAccent).clickable { onReviewJoinRequest(request.user.id, true) },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.Check, "Принять", tint = Color.White, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    )
                }
            }
        }

        AloFormSection(header = if (isChannel) "Подписчики" else "Участники") {
            // Android also adds people by @username here.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (usernameToAdd.isEmpty()) Text("@username участника", color = AloNativeAppMuted.copy(alpha = 0.55f), fontSize = 17.sp)
                    BasicTextField(
                        value = usernameToAdd, onValueChange = { usernameToAdd = it.take(40) }, singleLine = true,
                        textStyle = TextStyle(color = AloNativeAppText, fontSize = 17.sp),
                        cursorBrush = SolidColor(AloNativeAppAccent), modifier = Modifier.fillMaxWidth()
                    )
                }
                val canAdd = usernameToAdd.trim().removePrefix("@").length >= 3
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (canAdd) AloNativeAppAccent else AloNativeAppSurfaceRaised)
                        .clickable(enabled = canAdd) {
                            onAddMember(usernameToAdd.trim())
                            usernameToAdd = ""
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, "Добавить участника", tint = if (canAdd) Color.White else AloNativeAppMuted, modifier = Modifier.size(18.dp)) }
            }
            AloFormDivider()
            AloFormTextField(memberQuery, { memberQuery = it.take(80) }, "Поиск по имени или username")
            AloFormDivider()
            AloFormSegments(
                listOf("all" to "Все", "admins" to "Админы", "members" to "Остальные"),
                memberRoleFilter
            ) { memberRoleFilter = it }
            if (visibleMembers.isEmpty()) {
                AloFormDivider()
                AloFormTextRow(if (memberQuery.isEmpty()) "Список пуст" else "Ничего не найдено", AloNativeAppMuted)
            }
            if (batchRemove != null && (isOwner || detail.memberRole.equals("admin", ignoreCase = true))) {
                AloFormDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        if (isSelectingMembers) "Отмена" else "Выбрать",
                        color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            isSelectingMembers = !isSelectingMembers
                            selectedMemberIds.clear()
                        }.padding(vertical = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (isSelectingMembers) {
                        val enabled = selectedMemberIds.isNotEmpty()
                        val danger = if (enabled) AloNativeAppDanger else AloNativeAppDanger.copy(alpha = 0.4f)
                        Text(
                            "Удалить (${selectedMemberIds.size})", color = danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = enabled) { pendingBatchBan = false }.padding(vertical = 8.dp)
                        )
                        Text(
                            "Заблокировать", color = danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = enabled) { pendingBatchBan = true }.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            visibleMembers.forEach { member ->
                AloFormDivider()
                val manageable = member.role != "owner" && (isOwner || member.role != "admin")
                val chosen = member.user.id in selectedMemberIds
                AloUserCell(
                    name = member.user.name,
                    status = member.user.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Участник",
                    avatarUrl = member.user.avatarUrl,
                    colorSeed = member.user.id.toString(),
                    verified = member.user.verified,
                    // Like Telegram, only an owner, an admin or a titled member carries a tag.
                    tag = member.adminTitle.ifBlank { groupRoleLabel(member.role).takeIf { member.role == "owner" || member.role == "admin" } },
                    showDivider = false,
                    onClick = if (manageable) ({
                        if (isSelectingMembers) {
                            if (chosen) selectedMemberIds.remove(member.user.id) else selectedMemberIds.add(member.user.id)
                        } else {
                            selectedMember = member
                        }
                    }) else null,
                    leading = if (isSelectingMembers) ({
                        Icon(
                            if (chosen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                            tint = if (chosen) AloNativeAppAccent else AloNativeAppMuted.copy(alpha = if (manageable) 0.7f else 0.25f),
                            modifier = Modifier.size(24.dp)
                        )
                    }) else null,
                    trailing = if (!isSelectingMembers && manageable) ({
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted, modifier = Modifier.size(16.dp))
                    }) else null
                )
            }
        }

        val bans = management?.bans.orEmpty()
        if (bans.isNotEmpty()) {
            AloFormSection(header = "Заблокированные") {
                bans.forEachIndexed { index, ban ->
                    if (index > 0) AloFormDivider()
                    AloUserCell(
                        name = ban.user.name,
                        status = ban.reason.ifBlank { "Причина не указана" },
                        avatarUrl = ban.user.avatarUrl,
                        colorSeed = ban.user.id.toString(),
                        showDivider = false,
                        trailing = {
                            Text(
                                "Разблокировать", color = AloNativeAppAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { onUnbanMember(ban.user.id) }.padding(horizontal = 6.dp, vertical = 10.dp)
                            )
                        }
                    )
                }
            }
        }

        if (management != null) {
            AloFormSection(header = "Недавние действия") {
                if (management.events.isEmpty()) {
                    AloFormTextRow("Действий пока нет", AloNativeAppMuted)
                } else {
                    management.events.forEachIndexed { index, event ->
                        if (index > 0) AloFormDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(groupAdminEventTitle(event), color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(formatPostTimestamp(event.createdAt), color = AloNativeAppMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (management.nextAdminLogBeforeId != null) {
                    AloFormDivider()
                    AloFormButtonRow("Показать предыдущие", AloNativeAppAccent, onClick = onLoadEarlierAdminEvents)
                }
            }
        } else if (isManagementLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AloNativeAppAccent)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    pendingBatchBan?.let { ban ->
        val place = if (isChannel) "канал" else "группу"
        AlertDialog(
            onDismissRequest = { pendingBatchBan = null },
            title = { Text(if (ban) "Заблокировать выбранных?" else "Удалить выбранных?", fontWeight = FontWeight.Bold) },
            text = { Text(if (ban) "Они покинут $place и не смогут вернуться по ссылке." else "Они покинут $place, но смогут вернуться по приглашению.") },
            confirmButton = {
                TextButton(onClick = {
                    batchRemove?.invoke(detail.roomId, selectedMemberIds.toList(), ban)
                    selectedMemberIds.clear()
                    isSelectingMembers = false
                    pendingBatchBan = null
                }) {
                    Text(if (ban) "Заблокировать (${selectedMemberIds.size})" else "Удалить (${selectedMemberIds.size})", color = AloNativeAppDanger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingBatchBan = null }) { Text("Отмена") } }
        )
    }
    selectedMember?.let { member ->
        GroupMemberAccessDialog(
            member = member,
            canChangeRole = isOwner,
            onDismiss = { selectedMember = null },
            onSave = { role, adminTitle, isAnonymous, rights, restrictions ->
                onUpdateMemberAccess(member.user.id, role, adminTitle, isAnonymous, rights, restrictions)
                selectedMember = null
            },
            onTransferOwnership = { password ->
                onTransferOwnership(member.user.id, password)
                selectedMember = null
            },
            onBan = { reason, expiresAt ->
                onBanMember(member.user.id, reason, expiresAt)
                selectedMember = null
            }
        )
    }
    topicPendingDeletion?.let { topic ->
        AlertDialog(
            onDismissRequest = { topicPendingDeletion = null },
            title = { Text("Удалить тему?") },
            text = { Text("Тема «${topic.title}» и все её сообщения будут удалены у всех участников.") },
            confirmButton = {
                TextButton(onClick = {
                    topicPendingDeletion = null
                    onDeleteTopic(topic.id)
                }) { Text("Удалить", color = AloNativeAppDanger) }
            },
            dismissButton = { TextButton(onClick = { topicPendingDeletion = null }) { Text("Отмена") } }
        )
    }
    topicPendingMerge?.let { source ->
        AlertDialog(
            onDismissRequest = { topicPendingMerge = null },
            title = { Text("Куда перенести историю?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    management?.topics.orEmpty()
                        .filter { it.id != source.id && !it.isClosed && !it.isHidden }
                        .forEach { target ->
                            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                topicPendingMerge = null
                                onMergeTopic(source.id, target.id)
                            }) { Text("${target.iconEmoji} ${target.title}", modifier = Modifier.fillMaxWidth(), color = AloNativeAppText) }
                        }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { topicPendingMerge = null }) { Text("Отмена") } }
        )
    }
    inviteEditing?.let { invite ->
        GroupInviteEditDialog(
            invite = invite,
            onDismiss = { inviteEditing = null },
            onSave = { label, requiresApproval, usageLimit, expiresAt ->
                onUpdateInviteLink(invite.id, label, requiresApproval, usageLimit, expiresAt)
                inviteEditing = null
            }
        )
    }
    inviteStatsTarget?.let { invite ->
        GroupInviteStatsDialog(
            invite = invite,
            stats = inviteStats,
            isLoading = inviteStatsLoading,
            errorMessage = inviteStatsError,
            onRetry = { inviteStatsReloadVersion += 1 },
            onDismiss = { inviteStatsTarget = null }
        )
    }
}

private val groupPermissionItems = listOf(
    "sendMessages" to "Отправка сообщений",
    "sendMedia" to "Отправка медиа",
    "addMembers" to "Добавление участников",
    "pinMessages" to "Закрепление сообщений",
    "changeInfo" to "Изменение профиля сообщества"
)

/** A Form button with its symbol, as iOS's Label in a Button. */
@Composable
private fun AloFormIconButtonRow(icon: ImageVector, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = AloNativeAppAccent, modifier = Modifier.size(22.dp))
        Text(title, color = if (enabled) AloNativeAppAccent else AloNativeAppMuted, fontSize = 17.sp)
    }
}

@Composable
private fun AloFormIconButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** iOS's segmented Picker inside a Form row. */
@Composable
private fun AloFormSegments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(32.dp)
            .clip(RoundedCornerShape(percent = 50)).background(AloNativeAppText.copy(alpha = 0.11f)).padding(2.dp)
    ) {
        options.forEach { (value, title) ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(percent = 50))
                    .background(if (value == selected) AloNativeAppText.copy(alpha = 0.28f) else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(title, color = AloNativeAppText, fontSize = 14.sp, fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun GroupChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(14.dp))
            .clickable(onClick = onClick)
            .background(AloNativeAppBackgroundSecondary)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = AloNativeAppText, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = AloNativeAppAccent,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun GroupSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AloSquircle(14.dp))
            .background(AloNativeAppBackgroundSecondary)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AloNativeAppText,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GroupMemberAccessDialog(
    member: AloGroupMember,
    canChangeRole: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Map<String, Boolean>, Map<String, Boolean>) -> Unit,
    onTransferOwnership: (String) -> Unit,
    onBan: (String, String) -> Unit
) {
    var role by remember(member.user.id) { mutableStateOf(member.role) }
    var adminTitle by remember(member.user.id) { mutableStateOf(member.adminTitle) }
    var isAnonymous by remember(member.user.id) { mutableStateOf(member.isAnonymous) }
    var rights by remember(member.user.id) { mutableStateOf(member.adminRights) }
    var restrictions by remember(member.user.id) { mutableStateOf(member.restrictions) }
    var transferPassword by rememberSaveable(member.user.id) { mutableStateOf("") }
    var banReason by rememberSaveable(member.user.id) {
        mutableStateOf("Заблокирован администратором")
    }
    var banDurationHours by rememberSaveable(member.user.id) { mutableStateOf(0) }
    val adminItems = listOf(
        "changeInfo" to "Изменение профиля",
        "deleteMessages" to "Удаление сообщений",
        "banUsers" to "Блокировка участников",
        "inviteUsers" to "Приглашения и заявки",
        "pinMessages" to "Закрепление сообщений",
        "manageVideoChats" to "Управление видеочатами",
        "addAdmins" to "Назначение администраторов",
        "manageTopics" to "Управление темами",
        "postMessages" to "Публикация сообщений",
        "editMessages" to "Редактирование сообщений"
    )
    val restrictionItems = listOf(
        "sendMessages" to "Запретить сообщения",
        "sendMedia" to "Запретить медиа",
        "addMembers" to "Запретить приглашения",
        "pinMessages" to "Запретить закрепление",
        "changeInfo" to "Запретить изменение профиля"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AloNativeAppSurface,
        title = {
            Text(
                text = member.user.name,
                color = AloNativeAppText,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
    LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (canChangeRole) {
                    item {
                        GroupChoiceRow(
                            title = "Роль",
                            value = groupRoleLabel(role)
                        ) {
                            role = if (role == "admin") "member" else "admin"
                            if (role == "admin" && rights.isEmpty()) {
                                rights = adminItems.associate { it.first to (it.first != "addAdmins") }
                            }
                        }
                    }
                }
                if (role == "admin") {
                    item {
                        GroupSwitchRow("Оставаться анонимным", isAnonymous) { isAnonymous = it }
                    }
                    item {
                        BasicTextField(
                            value = adminTitle,
                            onValueChange = { adminTitle = it.take(16) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = AloNativeAppText,
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            cursorBrush = SolidColor(AloNativeAppAccent),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(AloSquircle(14.dp))
                                        .background(AloNativeAppBackgroundSecondary)
                                        .padding(horizontal = 13.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (adminTitle.isBlank()) {
                                        Text("Подпись администратора", color = AloNativeAppMuted)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                }
                val accessItems = if (role == "admin") adminItems else restrictionItems
                items(accessItems, key = { it.first }) { item ->
                    val source = if (role == "admin") rights else restrictions
                    GroupSwitchRow(item.second, source[item.first] == true) { value ->
                        if (role == "admin") {
                            rights = rights + (item.first to value)
                        } else {
                            restrictions = restrictions + (item.first to value)
                        }
                    }
                }
                if (canChangeRole) {
                    item {
                        BasicTextField(
                            value = transferPassword,
                            onValueChange = { transferPassword = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = AloNativeAppText,
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            cursorBrush = SolidColor(AloNativeAppAccent),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(AloSquircle(14.dp))
                                        .background(AloNativeAppBackgroundSecondary)
                                        .padding(horizontal = 13.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (transferPassword.isBlank()) {
                                        Text("Пароль владельца", color = AloNativeAppMuted)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    item {
                        TextButton(
                            onClick = { onTransferOwnership(transferPassword) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = transferPassword.isNotBlank()
                        ) {
                            Text("Передать права владельца", color = AloNativeAppAccent)
                        }
                    }
                }
                item {
                    BasicTextField(
                        value = banReason,
                        onValueChange = { banReason = it.take(200) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AloNativeAppText,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        cursorBrush = SolidColor(AloNativeAppAccent),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(AloSquircle(14.dp))
                                    .background(AloNativeAppBackgroundSecondary)
                                    .padding(horizontal = 13.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (banReason.isBlank()) Text("Причина блокировки", color = AloNativeAppMuted)
                                inner()
                            }
                        }
                    )
                }
                item {
                    GroupChoiceRow(
                        title = "Срок блокировки",
                        value = groupBanDurationLabel(banDurationHours)
                    ) {
                        banDurationHours = nextBanDurationHours(banDurationHours)
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            onBan(
                                banReason.ifBlank { "Заблокирован администратором" },
                                inviteExpiryIso(banDurationHours)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Заблокировать участника", color = Color(0xFFFF5364))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(role, adminTitle, role == "admin" && isAnonymous, rights, restrictions) }) {
                Text("Сохранить", color = AloNativeAppAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = AloNativeAppMuted)
            }
        }
    )
}

@Composable
private fun GroupInviteEditDialog(
    invite: AloGroupInviteLink,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Int, String) -> Unit
) {
    var label by rememberSaveable(invite.id) { mutableStateOf(invite.label) }
    var requiresApproval by rememberSaveable(invite.id) {
        mutableStateOf(invite.requiresApproval)
    }
    var usageLimit by rememberSaveable(invite.id) { mutableStateOf(invite.usageLimit) }
    var expiryHours by rememberSaveable(invite.id) {
        mutableStateOf(groupInviteRemainingHours(invite.expiresAt))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AloNativeAppSurface,
        title = { Text("Ссылка приглашения", color = AloNativeAppText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                BasicTextField(
                    value = label,
                    onValueChange = { label = it.take(64) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = AloNativeAppText,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    cursorBrush = SolidColor(AloNativeAppAccent),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(AloSquircle(14.dp))
                                .background(AloNativeAppBackgroundSecondary)
                                .padding(horizontal = 13.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (label.isBlank()) Text("Название", color = AloNativeAppMuted)
                            inner()
                        }
                    }
                )
                GroupSwitchRow("Одобрять заявки", requiresApproval) {
                    requiresApproval = it
                }
                GroupChoiceRow(
                    title = "Лимит",
                    value = if (usageLimit == 0) "Без лимита" else usageLimit.toString()
                ) {
                    usageLimit = when (usageLimit) {
                        0 -> maxOf(1, invite.usageCount)
                        1 -> maxOf(10, invite.usageCount)
                        10 -> maxOf(100, invite.usageCount)
                        else -> 0
                    }
                }
                GroupChoiceRow(
                    title = "Срок",
                    value = groupInviteExpiryLabel(expiryHours)
                ) {
                    expiryHours = nextInviteExpiryHours(expiryHours)
                }
                Text(
                    text = "Использовано: ${invite.usageCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AloNativeAppMuted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label,
                        requiresApproval,
                        usageLimit,
                        inviteExpiryIso(expiryHours)
                    )
                },
                enabled = usageLimit == 0 || usageLimit >= invite.usageCount
            ) {
                Text("Сохранить", color = AloNativeAppAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = AloNativeAppMuted)
            }
        }
    )
}

@Composable
private fun GroupInviteStatsDialog(
    invite: AloGroupInviteLink,
    stats: AloGroupInviteStats?,
    isLoading: Boolean,
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AloNativeAppSurface,
        title = { Text("Статистика ссылки", color = AloNativeAppText) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = invite.label.ifBlank { "Ссылка без названия" },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText
                    )
                    Text(
                        text = "Вступили: ${stats?.joined?.size ?: invite.joinedCount} · ожидают: ${stats?.pending?.size ?: invite.pendingCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AloNativeAppMuted
                    )
                    invite.creator?.name?.takeIf { it.isNotBlank() }?.let { creatorName ->
                        Text(
                            text = "Создал: $creatorName",
                            style = MaterialTheme.typography.labelSmall,
                            color = AloNativeAppMuted
                        )
                    }
                }
                when {
                    isLoading -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AloNativeAppAccent)
                        }
                    }
                    errorMessage.isNotBlank() -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(errorMessage, color = Color(0xFFFF6B7A))
                            TextButton(onClick = onRetry) {
                                Text("Повторить", color = AloNativeAppAccent)
                            }
                        }
                    }
                    stats != null -> {
                        item { GroupInviteStatsHeader("Вступили") }
                        if (stats.joined.isEmpty()) {
                            item { GroupInviteStatsEmptyRow() }
                        } else {
                            items(stats.joined, key = { "joined-${it.user.id}" }) { participant ->
                                GroupInviteParticipantRow(participant)
                            }
                        }
                        item { GroupInviteStatsHeader("Ожидают одобрения") }
                        if (stats.pending.isEmpty()) {
                            item { GroupInviteStatsEmptyRow() }
                        } else {
                            items(stats.pending, key = { "pending-${it.user.id}" }) { participant ->
                                GroupInviteParticipantRow(participant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово", color = AloNativeAppAccent)
            }
        }
    )
}

@Composable
private fun GroupInviteStatsHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = AloNativeAppText
    )
}

@Composable
private fun GroupInviteStatsEmptyRow() {
    Text(
        text = "Пока никого",
        style = MaterialTheme.typography.bodySmall,
        color = AloNativeAppMuted
    )
}

@Composable
private fun GroupInviteParticipantRow(participant: AloGroupInviteParticipant) {
    AloUserCell(
        name = participant.user.name,
        status = buildString {
            if (participant.user.username.isNotBlank()) append("@${participant.user.username} · ")
            append(formatFeedDate(participant.createdAt))
        },
        avatarUrl = participant.user.avatarUrl,
        colorSeed = participant.user.id.toString(),
        verified = participant.user.verified,
        style = AloUserCellStyle.Contact,
        showDivider = false
    )
}

private fun inviteExpiryIso(hours: Int): String = if (hours > 0) {
    Instant.now().plusSeconds(hours.toLong() * 60L * 60L).toString()
} else {
    ""
}

private fun groupInviteRemainingHours(expiresAt: String): Int {
    if (expiresAt.isBlank()) return 0
    return runCatching {
        val seconds = Instant.parse(expiresAt).epochSecond - Instant.now().epochSecond
        ((seconds.coerceAtLeast(3600L) + 3599L) / 3600L).toInt()
    }.getOrDefault(0)
}

private fun nextInviteExpiryHours(current: Int): Int = when (current) {
    0 -> 24
    24 -> 168
    168 -> 720
    else -> 0
}

private fun groupInviteExpiryLabel(hours: Int): String = when (hours) {
    0 -> "Без срока"
    24 -> "1 день"
    168 -> "1 неделя"
    720 -> "1 месяц"
    else -> "$hours ч."
}

private fun nextBanDurationHours(current: Int): Int = when (current) {
    0 -> 1
    1 -> 24
    24 -> 168
    168 -> 720
    else -> 0
}

private fun groupBanDurationLabel(hours: Int): String = when (hours) {
    0 -> "Навсегда"
    1 -> "1 час"
    24 -> "1 день"
    168 -> "1 неделя"
    720 -> "1 месяц"
    else -> "$hours ч."
}

private fun slowModeLabel(seconds: Int): String = when (seconds) {
    10 -> "10 секунд"
    30 -> "30 секунд"
    60 -> "1 минута"
    300 -> "5 минут"
    3600 -> "1 час"
    else -> "Выключен"
}

private fun autoDeleteLabel(seconds: Int): String = when (seconds) {
    86400 -> "1 день"
    604800 -> "1 неделя"
    2592000 -> "1 месяц"
    7776000 -> "3 месяца"
    31536000 -> "1 год"
    else -> "Выключено"
}

private fun groupInviteSummary(invite: AloGroupInviteLink): String {
    if (!invite.isActive) return "Отозвана"
    val joined = if (invite.usageLimit > 0) {
        "${invite.joinedCount} из ${invite.usageLimit} вступили"
    } else {
        "${invite.joinedCount} вступили"
    }
    return buildList {
        add(joined)
        if (invite.pendingCount > 0) {
            add("${invite.pendingCount} ожидают")
        } else if (invite.requiresApproval) {
            add("вступление по заявке")
        }
        if (invite.expiresAt.isNotBlank()) add("до ${invite.expiresAt.take(10)}")
    }.joinToString(" · ")
}

private fun groupAdminEventTitle(event: AloGroupAdminEvent): String {
    val actor = event.actor.name
    val target = event.target?.name.orEmpty()
    return when (event.action) {
        "profile_updated" -> "$actor изменил профиль группы"
        "settings_updated" -> "$actor изменил настройки"
        "member_added" -> "$actor добавил $target"
        "member_removed" -> "$actor удалил $target"
        "member_promoted" -> "$actor назначил $target администратором"
        "member_demoted" -> "$actor снял права администратора у $target"
        "member_permissions_updated" -> "$actor изменил права $target"
        "member_banned" -> "$actor заблокировал $target"
        "member_unbanned" -> "$actor разблокировал $target"
        "ownership_transferred" -> "$actor передал сообщество пользователю $target"
        "join_request_approved" -> "$actor одобрил заявку $target"
        "join_request_rejected" -> "$actor отклонил заявку $target"
        "invite_link_created" -> "$actor создал ссылку-приглашение"
        "invite_link_updated" -> "$actor изменил ссылку-приглашение"
        "invite_link_revoked" -> "$actor отозвал ссылку-приглашение"
        else -> "$actor: ${event.action}"
    }
}

private fun groupRoleLabel(role: String): String {
    return when (role.lowercase(Locale.getDefault())) {
        "owner" -> "Владелец"
        "admin" -> "Администратор"
        else -> "Участник"
    }
}
