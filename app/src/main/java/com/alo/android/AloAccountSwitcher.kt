package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Several accounts on one phone, as in Telegram: up to three, switched without a password. The sessions are kept by
 * [AloSecureAccountStore]; this file draws the switcher (a hold on «Профиль») and its rows in Settings.
 */
object AloAccountSwitcher {
    /** The server keeps the same limit for a browser (server/src/modules/auth/accounts.js). */
    const val MAX_ACCOUNTS = 3

    fun canAdd(accounts: List<AloStoredAccount>, currentUserId: Int): Boolean =
        accounts.count { it.userId != currentUserId } + 1 < MAX_ACCOUNTS

    fun unreadLabel(count: Int): String = if (count > 99) "99+" else count.toString()
}

/** The unread count of another account: the accent pill Telegram puts beside it. */
@Composable
internal fun AloAccountUnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(AloNativeAppAccent)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(AloAccountSwitcher.unreadLabel(count), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
internal fun AloAccountRow(
    name: String,
    subtitle: String,
    avatarUrl: String,
    userId: Int,
    unread: Int,
    current: Boolean,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarBadge(name = name, avatarUrl = avatarUrl, verified = false, size = 38.dp, colorSeed = userId.toString())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) {
            Icon(Icons.Rounded.Check, contentDescription = "Текущий аккаунт", tint = AloNativeAppAccent, modifier = Modifier.size(22.dp))
        } else {
            AloAccountUnreadBadge(unread)
        }
    }
}

@Composable
internal fun AloAddAccountRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = AloNativeAppAccent, modifier = Modifier.size(26.dp))
        }
        Text("Добавить аккаунт", color = AloNativeAppAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The switcher: the current account, the others with their unread count, «Добавить аккаунт». */
@Composable
internal fun AloAccountSwitcherSheet(
    me: AloCurrentUser,
    accounts: List<AloStoredAccount>,
    unread: Map<Int, Int>,
    onSwitch: (Int) -> Unit,
    onAdd: () -> Unit,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    val others = accounts.filter { it.userId != me.id }
    AloFormSheet(title = "Аккаунты", onDismiss = onDismiss, leading = null, trailing = "Закрыть", onTrailing = onDismiss, fullHeight = false) {
        AloFormSection {
            AloAccountRow(
                name = me.name.ifBlank { me.username },
                subtitle = "Мой профиль",
                avatarUrl = me.avatarUrl,
                userId = me.id,
                unread = 0,
                current = true,
                onClick = onOpenProfile
            )
            others.forEach { account ->
                AloFormDivider()
                AloAccountRow(
                    name = account.displayName,
                    subtitle = account.username.takeIf(String::isNotBlank)?.let { "@$it" } ?: "Сохранённый вход",
                    avatarUrl = account.avatarUrl,
                    userId = account.userId,
                    unread = unread[account.userId] ?: 0,
                    current = false,
                    onClick = { onSwitch(account.userId) }
                )
            }
        }
        if (AloAccountSwitcher.canAdd(accounts, me.id)) {
            AloFormSection { AloAddAccountRow(onAdd) }
        } else {
            AloFormSection(footer = "На телефоне уже ${AloAccountSwitcher.MAX_ACCOUNTS} аккаунта — это максимум. Выйдите из одного, чтобы добавить другой.") {}
        }
        Spacer(Modifier.height(8.dp))
    }
}
