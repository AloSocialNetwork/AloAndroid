package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

internal enum class AloUserCellStyle { Manage, Contact }

private class AloUserCellSpec(
    val height: androidx.compose.ui.unit.Dp, val avatarSize: androidx.compose.ui.unit.Dp, val avatarStart: androidx.compose.ui.unit.Dp,
    val avatarTop: androidx.compose.ui.unit.Dp, val textGap: androidx.compose.ui.unit.Dp, val nameSize: androidx.compose.ui.unit.TextUnit,
    val statusSize: androidx.compose.ui.unit.TextUnit, val nameTop: androidx.compose.ui.unit.Dp, val statusGap: androidx.compose.ui.unit.Dp,
    val dividerStart: androidx.compose.ui.unit.Dp
)

private fun specFor(style: AloUserCellStyle): AloUserCellSpec = when (style) {
    AloUserCellStyle.Manage -> AloTelegramMetrics.UserCell.let {
        AloUserCellSpec(it.Height, it.AvatarSize, it.AvatarStart, it.AvatarTop, it.TextGap, it.NameSize, it.StatusSize, it.NameTop, it.StatusGap, it.DividerStart)
    }
    AloUserCellStyle.Contact -> AloTelegramMetrics.ContactCell.let {
        AloUserCellSpec(it.Height, it.AvatarSize, it.AvatarStart, it.AvatarTop, it.TextGap, it.NameSize, it.StatusSize, it.NameTop, it.StatusGap, it.DividerStart)
    }
}

/**
 * A person in a list of a group's members, requests or bans: Telegram 13.0.3's `ManageChatUserCell` (64dp high, a 46dp avatar 13dp in,
 * the name 17sp 11.5dp from the top and a 14sp status 34.5dp, both 70dp in) with the role as `UserCell`'s 14sp pill, and the divider from 68dp.
 */
@Composable
internal fun AloUserCell(
    name: String,
    status: String,
    avatarUrl: String,
    colorSeed: String,
    modifier: Modifier = Modifier,
    verified: Boolean = false,
    /** The role (UserCell's `adminTextView`): 14sp in a pill of the accent at 12%. */
    tag: String? = null,
    /** A selection mark before the avatar. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
    /** Manage: a group's members, requests and bans (64dp). Contact: friends, followers and people to pick (58dp, `UserCell`). */
    style: AloUserCellStyle = AloUserCellStyle.Manage,
    onClick: (() -> Unit)? = null
) {
    val metrics = specFor(style)
    val tagMetrics = AloTelegramMetrics.UserCell
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(metrics.height)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.Top
        ) {
            if (leading != null) {
                Box(Modifier.padding(start = metrics.avatarStart - 2.dp, top = (metrics.height - 24.dp) / 2)) { leading() }
                Spacer(Modifier.width(2.dp))
            }
            Box(Modifier.padding(start = if (leading != null) 6.dp else metrics.avatarStart, top = metrics.avatarTop)) {
                AvatarBadge(name = name, avatarUrl = avatarUrl, verified = verified, size = metrics.avatarSize, colorSeed = colorSeed)
            }
            Column(Modifier.weight(1f).padding(start = metrics.textGap, top = metrics.nameTop)) {
                Text(
                    name, color = AloNativeAppText, fontSize = metrics.nameSize, lineHeight = tagMetrics.LineHeight,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(tagMetrics.LineBoxHeight)
                )
                Spacer(Modifier.height(metrics.statusGap))
                Text(
                    status, color = AloNativeAppMuted, fontSize = metrics.statusSize, lineHeight = tagMetrics.LineHeight,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(tagMetrics.LineBoxHeight)
                )
            }
            if (tag != null) {
                Box(Modifier.padding(start = 8.dp, end = if (trailing != null) 6.dp else tagMetrics.TagEnd, top = metrics.nameTop)) {
                    Text(
                        tag, color = AloNativeAppAccent, fontSize = tagMetrics.TagSize, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(32.dp)).background(AloNativeAppAccent.copy(alpha = 0.12f))
                            .padding(horizontal = tagMetrics.TagPadding, vertical = 1.dp)
                    )
                }
            }
            if (trailing != null) Box(Modifier.height(metrics.height).padding(end = 8.dp), contentAlignment = Alignment.Center) { trailing() }
        }
        if (showDivider) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = metrics.dividerStart).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
        }
    }
}
