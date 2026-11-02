package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// iOS's AloModerationTeamViews: who is on the team at which level, what each may do, and the people
// search for appointing or restricting someone. The server enforces the ladder; the screens only
// offer what this account may actually do.

private val AloStaffLevels = listOf(
    AloStaffHierarchy.Owner, AloStaffHierarchy.Admin, AloStaffHierarchy.LeadModerator, AloStaffHierarchy.Moderator
)

@Composable
internal fun AloModerationTeam(service: AloModerationService, me: AloCurrentUser, onNotice: (String) -> Unit) {
    val staff = remember { mutableStateListOf<AloStaffMember>() }
    var assignable by remember { mutableStateOf(emptyList<String>()) }
    var isLoading by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<AloStaffMember?>(null) }
    var isSearchOpen by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { service.team() }.onSuccess { (members, rights) -> staff.clear(); staff.addAll(members); assignable = rights }
        isLoading = false
    }
    LaunchedEffect(Unit) { load() }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .aloLiquidGlass(RoundedCornerShape(percent = 50))
                .clickable { isSearchOpen = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
            Text("Найти человека, чтобы назначить", color = AloNativeAppMuted, fontSize = 16.sp)
        }
        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AloNativeAppAccent)
            }
        }
        AloStaffLevels.forEach { level ->
            val members = staff.filter { it.hierarchy == level }
            if (members.isNotEmpty()) {
                AloModerationCard(level.plural) {
                    members.forEachIndexed { index, member ->
                        if (index > 0) AloModerationDivider()
                        AloStaffRow(member, isMe = member.id == me.id) { opened = member }
                    }
                }
            }
        }
        if (!isLoading) {
            Text(
                "Владелец назначает администраторов. Администраторы и старшие модераторы назначают модераторов ниже себя и не могут выдать права выше своих. Ограничивать и блокировать аккаунты могут администраторы. Все изменения попадают в журнал.",
                color = AloNativeAppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    opened?.let { member ->
        AloFormSheet(title = if (member.hierarchy == AloStaffHierarchy.User) "Пользователь" else "Участник",
            onDismiss = { opened = null }, leading = "Закрыть") {
            AloStaffMemberDetail(member, assignable, me, service, onNotice) {
                opened = null
                scope.launch { load() }
            }
        }
    }
    if (isSearchOpen) {
        AloStaffSearchSheet(service, assignable, me, onNotice, onDismiss = { isSearchOpen = false }) {
            isSearchOpen = false
            scope.launch { load() }
        }
    }
}

/** A person in the team or in the search: avatar, name, what they may do, their level. */
@Composable
internal fun AloStaffRow(member: AloStaffMember, isMe: Boolean, onClick: () -> Unit) {
    val summary = when (member.hierarchy) {
        AloStaffHierarchy.Owner, AloStaffHierarchy.Admin -> "Все права"
        AloStaffHierarchy.User -> if (member.user.username.isBlank()) "Пользователь" else "@${member.user.username}"
        else -> AloModeratorPermission.entries.filter { it.raw in member.permissions }.joinToString(" · ") { it.short }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarBadge(name = member.user.name, avatarUrl = member.user.avatarUrl, verified = false, size = 42.dp, colorSeed = member.id.toString())
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(member.user.name, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (isMe) Text("вы", color = AloNativeAppMuted, fontSize = 13.sp)
            }
            Text(summary, color = AloNativeAppMuted, fontSize = 13.5.sp, maxLines = 1)
            member.accountStatusTitle?.let { status ->
                Text(status, color = if (member.accountStatus == "banned") AloNativeAppDanger else AloModerationWarning,
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (member.hierarchy != AloStaffHierarchy.User) {
            Icon(member.hierarchy.icon, member.hierarchy.title, tint = member.hierarchy.tint, modifier = Modifier.size(18.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted, modifier = Modifier.size(22.dp))
    }
}

private enum class AloStaffConfirmation { RevokeModerator, GrantAdmin, RevokeAdmin, BlockDay, BlockWeek, Ban, Restore }

/** One person: their level and rights, and what this account may change about them. */
@Composable
private fun AloStaffMemberDetail(
    initial: AloStaffMember,
    assignable: List<String>,
    me: AloCurrentUser,
    service: AloModerationService,
    onNotice: (String) -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var member by remember(initial.id) { mutableStateOf(initial) }
    var selected by remember(initial.id) { mutableStateOf(initial.permissions.toSet()) }
    var reason by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<AloStaffConfirmation?>(null) }

    val actor = me.staffHierarchy
    val isMe = me.id == member.id
    val canManage = !isMe && actor.rank >= 2 && actor.rank > member.hierarchy.rank
    // A restricted account is restored first, then appointed.
    val editableRights = canManage && member.hierarchy.rank < AloStaffHierarchy.Admin.rank &&
        (member.hierarchy != AloStaffHierarchy.User || member.accountStatus == "active")
    val canAppointAdmins = actor == AloStaffHierarchy.Owner && !isMe && member.hierarchy != AloStaffHierarchy.Owner
    val canRestrict = !isMe && actor.rank >= AloStaffHierarchy.Admin.rank && actor.rank > member.hierarchy.rank

    fun run(notice: String, work: suspend () -> Unit) {
        isSaving = true
        error = ""
        scope.launch {
            runCatching { work() }
                .onSuccess { onNotice(notice); onChanged() }
                .onFailure { error = it.moderationMessage("Не удалось сохранить.") }
            isSaving = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AvatarBadge(name = member.user.name, avatarUrl = member.user.avatarUrl, verified = false, size = 76.dp, colorSeed = member.id.toString())
        Text(member.user.name, color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (member.user.username.isNotBlank()) Text("@${member.user.username}", color = AloNativeAppMuted, fontSize = 15.sp)
        AloStaffBadge(member.hierarchy)
        member.accountStatusTitle?.let { status ->
            Text(
                if (member.blockReason.isBlank()) status else "$status · ${member.blockReason}",
                color = if (member.accountStatus == "banned") AloNativeAppDanger else AloModerationWarning,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }

    if (editableRights) {
        AloFormSection(header = "Права модератора") {
            val options = AloModeratorPermission.entries.filter { it.raw in assignable }
            options.forEachIndexed { index, permission ->
                if (index > 0) AloFormDivider()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selected = if (permission.raw in selected) selected - permission.raw else selected + permission.raw
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(permission.title, color = AloNativeAppText, fontSize = 16.sp)
                        Text(permission.footnote, color = AloNativeAppMuted, fontSize = 13.sp)
                    }
                    AloSwitch(checked = permission.raw in selected, onCheckedChange = { on ->
                        selected = if (on) selected + permission.raw else selected - permission.raw
                    })
                }
            }
        }
        val changed = selected != member.permissions.toSet()
        AloFormSection {
            AloFormButtonRow(
                if (member.hierarchy == AloStaffHierarchy.User) "Назначить модератором" else "Сохранить права",
                AloNativeAppAccent, enabled = !isSaving && selected.isNotEmpty() && changed
            ) {
                val permissions = AloModeratorPermission.entries.map { it.raw }.filter { it in selected }
                run(if (member.hierarchy == AloStaffHierarchy.User) "Назначен модератором" else "Права сохранены") {
                    service.setModeratorPermissions(member, permissions)
                }
            }
            if (member.hierarchy != AloStaffHierarchy.User) {
                AloFormDivider()
                AloFormButtonRow("Снять с модерации", AloNativeAppDanger, enabled = !isSaving) {
                    confirmation = AloStaffConfirmation.RevokeModerator
                }
            }
        }
    } else if (!canManage && !isMe && member.hierarchy != AloStaffHierarchy.User) {
        Text("Этот уровень не ниже вашего: изменить его права нельзя.", color = AloNativeAppMuted, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
    }

    if (canAppointAdmins && (member.accountStatus == "active" || member.hierarchy == AloStaffHierarchy.Admin)) {
        AloFormSection(
            header = "Администратор",
            footer = "Администратор получает все права модерации, может ограничивать аккаунты и назначать модераторов. Назначает и снимает администраторов только владелец."
        ) {
            val isAdmin = member.hierarchy == AloStaffHierarchy.Admin
            AloFormButtonRow(
                if (isAdmin) "Снять с должности администратора" else "Сделать администратором",
                if (isAdmin) AloNativeAppDanger else AloNativeAppAccent, enabled = !isSaving
            ) { confirmation = if (isAdmin) AloStaffConfirmation.RevokeAdmin else AloStaffConfirmation.GrantAdmin }
        }
    }

    if (canRestrict) {
        AloFormSection(
            header = "Доступ к аккаунту",
            footer = "Ограниченный или заблокированный аккаунт сразу выходит со всех устройств. Человек может обжаловать решение со страницы входа."
        ) {
            if (member.accountStatus == "active") {
                AloStaffActionRow("Ограничить на сутки", Icons.Rounded.Schedule, AloModerationWarning) { confirmation = AloStaffConfirmation.BlockDay }
                AloFormDivider()
                AloStaffActionRow("Ограничить на неделю", Icons.Rounded.CalendarMonth, AloModerationWarning) { confirmation = AloStaffConfirmation.BlockWeek }
                AloFormDivider()
                AloStaffActionRow("Заблокировать навсегда", Icons.Rounded.Block, AloNativeAppDanger) { confirmation = AloStaffConfirmation.Ban }
                AloFormDivider()
                AloFormTextField(reason, { reason = it }, "Причина (её увидит человек)", minLines = 1, maxLines = 3)
            } else {
                AloStaffActionRow("Восстановить доступ", Icons.AutoMirrored.Rounded.Undo, AloNativeAppSuccess) { confirmation = AloStaffConfirmation.Restore }
            }
        }
    }
    if (error.isNotEmpty()) AloFormSection { AloFormTextRow(error, AloNativeAppDanger) }
    Spacer(Modifier.height(24.dp))

    confirmation?.let { item ->
        val name = member.user.name
        val (title, button, destructive) = when (item) {
            AloStaffConfirmation.RevokeModerator -> Triple("Снять $name с модерации?", "Снять с модерации", true)
            AloStaffConfirmation.GrantAdmin -> Triple("Сделать $name администратором?", "Назначить", false)
            AloStaffConfirmation.RevokeAdmin -> Triple("Снять $name с должности администратора?", "Снять", true)
            AloStaffConfirmation.BlockDay -> Triple("Ограничить доступ на сутки?", "Ограничить", true)
            AloStaffConfirmation.BlockWeek -> Triple("Ограничить доступ на неделю?", "Ограничить", true)
            AloStaffConfirmation.Ban -> Triple("Заблокировать $name навсегда?", "Заблокировать", true)
            AloStaffConfirmation.Restore -> Triple("Восстановить доступ $name?", "Восстановить", false)
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            containerColor = AloNativeAppBackgroundSecondary,
            title = { Text(title, color = AloNativeAppText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    when (item) {
                        AloStaffConfirmation.RevokeModerator -> run("Снят с модерации") { service.setModeratorPermissions(member, emptyList()) }
                        AloStaffConfirmation.GrantAdmin -> run("Назначен администратором") { member = service.setAdministrator(member, true) }
                        AloStaffConfirmation.RevokeAdmin -> run("Снят с должности администратора") { member = service.setAdministrator(member, false) }
                        AloStaffConfirmation.BlockDay -> run("Доступ ограничен") { member = service.setAccountStatus(member, "blocked", 24, reason) }
                        AloStaffConfirmation.BlockWeek -> run("Доступ ограничен") { member = service.setAccountStatus(member, "blocked", 24 * 7, reason) }
                        AloStaffConfirmation.Ban -> run("Аккаунт заблокирован") { member = service.setAccountStatus(member, "banned", 24, reason) }
                        AloStaffConfirmation.Restore -> run("Доступ восстановлен") { member = service.setAccountStatus(member, "active", 24, "") }
                    }
                }) { Text(button, color = if (destructive) AloNativeAppDanger else AloNativeAppAccent) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена", color = AloNativeAppMuted) } }
        )
    }
}

@Composable
private fun AloStaffActionRow(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, color = color, fontSize = 17.sp)
    }
}

/** Anyone by name, @username or id, to appoint as a moderator or to restrict. */
@Composable
private fun AloStaffSearchSheet(
    service: AloModerationService,
    assignable: List<String>,
    me: AloCurrentUser,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<AloStaffMember>()) }
    var opened by remember { mutableStateOf<AloStaffMember?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(350); runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        results = runCatching { service.searchStaff(text) }.getOrDefault(results)
    }
    val member = opened
    AloFormSheet(
        title = if (member == null) "Найти человека" else if (member.hierarchy == AloStaffHierarchy.User) "Пользователь" else "Участник",
        onDismiss = { if (opened != null) opened = null else onDismiss() },
        leading = if (member == null) "Готово" else "Назад"
    ) {
        if (member != null) {
            AloStaffMemberDetail(member, assignable, me, service, onNotice, onChanged)
            return@AloFormSheet
        }
        // The sheet already sets the 16dp edge, so the field lines up with «Готово» above it.
        Row(
            Modifier
                .padding(top = 6.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(44.dp)
                .aloFormGlass(RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Rounded.Search, null, tint = AloNativeAppMuted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Имя, @username или номер", color = AloNativeAppMuted, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it.take(64) }, singleLine = true,
                    textStyle = TextStyle(color = AloNativeAppText, fontSize = 16.sp), cursorBrush = SolidColor(AloNativeAppAccent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        }
        if (results.isNotEmpty()) {
            AloModerationCard {
                results.forEachIndexed { index, result ->
                    if (index > 0) AloModerationDivider()
                    AloStaffRow(result, isMe = result.id == me.id) { opened = result }
                }
            }
        } else {
            Text(
                if (query.isBlank()) "Найдутся и заблокированные аккаунты." else "Никого не нашли",
                color = AloNativeAppMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
